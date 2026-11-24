package com.brainrot.pigeon.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

// نسخة جديدة من المفتاح: نتجاهل أي كاش قديم كان يحمل الأرقام الافتراضية
// (3099/48/5) حتى لا تظهر أبداً بعد التحديث.
private val PROFILE_JSON_KEY = stringPreferencesKey("profile_json_v2")
// تحديث شبه لحظي: فحص كل 5 ثوانٍ، وأقل فاصل حقيقي بين طلبين ثانيتان.
private const val MIN_REQUEST_GAP_MS = 2_000L
private const val GRAPH_HEAD_START_MS = 1_200L
private const val GRAPH_BASE = "https://graph.facebook.com"
private const val GRAPH_VERSION = "v27.0"
private const val GRAPH_FIELDS =
    "id,username,name,profile_picture_url,biography,followers_count,follows_count,media_count"
private const val LOG_TAG = "PigeonProfile"
private const val MAX_ERROR_LENGTH = 90

/**
 * يجيب بيانات الحساب بالتوكن المدمج في البناء، بمسارين:
 * 1. Instagram Graph API مباشرة (`graph.facebook.com`).
 * 2. عند فشل الأول: بروكسي Cloudflare Worker المنشور (بديل خارجي)،
 *    لأن بعض الشبكات تحجب `graph.facebook.com` من الهاتف.
 *
 * سبب الفشل يُسجَّل في logcat (PigeonProfile) ويُعرض للمستخدم بدل ما تنعرض
 * الأرقام الاحتياطية وكأنها حقيقية.
 */
class ProfileRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val client: OkHttpClient,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _profile = MutableStateFlow(Profile())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** آخر سبب فشل، أو null بعد نجاح آخر تحديث. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val mutex = Mutex()
    private var lastRequestAt = 0L
    private var cacheLoaded = false
    private var lastPersisted: Profile? = null

    // طلبات السباق تعمل خارج نطاق الـ ViewModel: الخاسر يُلغى في الخلفية بدل
    // إبقاء المستخدم منتظراً اتصالاً لا يهمّه.
    private val liveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var apiDisabled: Boolean = false
        private set

    suspend fun loadCached() {
        if (cacheLoaded) return
        cacheLoaded = true

        val raw = runCatching { context.profileStore.data.first()[PROFILE_JSON_KEY] }
            .getOrNull()
            ?: return

        runCatching { json.decodeFromString<Profile>(raw) }
            .onSuccess { cached ->
                lastPersisted = cached
                if (_profile.value.fetchedAt == 0L) _profile.value = cached
            }
    }

    /** يعيد تفعيل الطلبات بعد توقفها (يُستدعى عند كل عودة للتطبيق). */
    fun reset() {
        apiDisabled = false
        lastRequestAt = 0L
    }

    suspend fun refresh(minGapMs: Long = MIN_REQUEST_GAP_MS): Result<Profile> =
        mutex.withLock {
            val config = settings.current()
            if (config.igUserId.isBlank() || config.accessToken.isBlank()) {
                _error.value = "missing credentials"
                return@withLock Result.failure(IllegalStateException("missing credentials"))
            }
            if (apiDisabled) {
                return@withLock Result.failure(IllegalStateException("api disabled"))
            }

            val now = System.currentTimeMillis()
            if (now - lastRequestAt < minGapMs) {
                return@withLock Result.failure(IllegalStateException("too soon"))
            }
            lastRequestAt = now

            val outcome = fetchLive(config)
            outcome.profile?.let { fresh ->
                if (outcome.viaProxy) Log.i(LOG_TAG, "profile loaded through worker proxy")
                return@withLock publish(fresh)
            }

            val directError = outcome.directError
            val proxyError = outcome.proxyError
            if (directError != null) {
                Log.w(LOG_TAG, "graph.facebook.com failed: ${directError.message}", directError)
            }
            if (proxyError != null) {
                Log.w(LOG_TAG, "worker proxy failed: ${proxyError.message}", proxyError)
            }

            // التوكن نفسه مرفوض من المصدرين: نوقف المحاولات حتى العودة للتطبيق.
            if ((directError as? GraphCallError)?.authFailed == true &&
                (proxyError as? GraphCallError)?.authFailed == true
            ) {
                apiDisabled = true
            }

            _error.value = shorten(directError ?: proxyError)
            Result.failure(directError ?: proxyError ?: IllegalStateException("unknown"))
        }

    /**
     * سباق بين المسارين بدل الانتظار التسلسلي: البروكسي (Cloudflare edge) يبدأ
     * فوراً، وGraph مباشرة تنطلق بعده بمهلة قصيرة فقط إذا لم يصل رد. أول رد
     * ناجح يُعرض فوراً، والخاسر يُلغى في الخلفية.
     */
    private suspend fun fetchLive(config: AppSettings): LiveOutcome {
        val results = Channel<PathResult>(Channel.UNLIMITED)
        val jobs: List<Job> = listOf(
            liveScope.launch {
                results.trySend(
                    PathResult(proxy = true, result = runCatching { fetchViaProxy(config) })
                )
            },
            liveScope.launch {
                delay(GRAPH_HEAD_START_MS)
                results.trySend(
                    PathResult(proxy = false, result = runCatching { fetchGraph(config) })
                )
            },
        )

        var profile: Profile? = null
        var viaProxy = false
        var proxyError: Throwable? = null
        var directError: Throwable? = null

        try {
            repeat(2) {
                if (profile != null) return@repeat
                val path = results.receive()
                val value = path.result.getOrNull()
                if (value != null) {
                    profile = value
                    viaProxy = path.proxy
                } else if (path.proxy) {
                    proxyError = path.result.exceptionOrNull()
                } else {
                    directError = path.result.exceptionOrNull()
                }
            }
        } finally {
            jobs.forEach { it.cancel() }
            results.close()
        }

        return LiveOutcome(profile, viaProxy, directError, proxyError)
    }

    private suspend fun publish(fresh: Profile): Result<Profile> {
        _profile.value = fresh
        _error.value = null
        persist(fresh)
        return Result.success(fresh)
    }

    // OkHttp هنا متزامن، ولا بد من Dispatchers.IO: استدعاؤه على Main يرمي
    // NetworkOnMainThreadException ويظهر للمستخدم كفشل تحديث.
    private suspend fun fetchGraph(config: AppSettings): Profile = withContext(Dispatchers.IO) {
        val url = "$GRAPH_BASE/$GRAPH_VERSION/${config.igUserId.trim()}".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("fields", GRAPH_FIELDS)
            ?.addQueryParameter("access_token", config.accessToken.trim())
            ?.build()
            ?: throw GraphCallError("bad ig user id", authFailed = false)

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .cacheControl(CacheControl.Builder().noCache().build())
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = runCatching {
                    json.decodeFromString<GraphErrorEnvelope>(body)
                }.getOrNull()?.error
                val authFailed =
                    response.code == 401 || response.code == 403 || error?.code == 190
                throw GraphCallError(
                    "graph HTTP ${response.code}: ${error?.message ?: "request failed"}",
                    authFailed,
                )
            }
            if (body.isBlank()) throw GraphCallError("graph empty response", false)
            json.decodeFromString<GraphProfileDto>(body).toProfile(_profile.value)
        }
    }

    private suspend fun fetchViaProxy(config: AppSettings): Profile =
        withContext(Dispatchers.IO) {
            val base = config.apiEndpoint.trim()
            if (base.isBlank()) throw GraphCallError("no proxy endpoint", false)

            val url = base.trimEnd('/').toHttpUrlOrNull()
                ?: throw GraphCallError("bad proxy endpoint", false)

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .cacheControl(CacheControl.Builder().noCache().build())
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw GraphCallError(
                        "proxy HTTP ${response.code}",
                        authFailed = response.code == 401 || response.code == 403,
                    )
                }
                if (body.isBlank()) throw GraphCallError("proxy empty response", false)

                val dto = json.decodeFromString<ProxyProfileDto>(body)
                Profile(
                    username = dto.username?.takeIf { it.isNotBlank() }
                        ?: _profile.value.username,
                    bio = dto.bio.orEmpty(),
                    posts = dto.posts ?: _profile.value.posts,
                    followers = dto.followers ?: _profile.value.followers,
                    following = dto.following ?: _profile.value.following,
                    avatarUrl = dto.avatar?.takeIf { it.isNotBlank() }
                        ?: _profile.value.avatarUrl,
                    fetchedAt = System.currentTimeMillis(),
                )
            }
        }

    private suspend fun persist(profile: Profile) {
        // مع التحديث السريع نكتب على القرص فقط لما تتغير قيمة فعلية.
        val previous = lastPersisted
        if (previous != null && previous.copy(fetchedAt = 0L) == profile.copy(fetchedAt = 0L)) {
            return
        }
        lastPersisted = profile
        runCatching {
            context.profileStore.edit { prefs ->
                prefs[PROFILE_JSON_KEY] = json.encodeToString(profile)
            }
        }
    }

    private fun shorten(error: Throwable?): String {
        val message = error?.message?.takeIf { it.isNotBlank() } ?: error?.javaClass?.simpleName
            ?: "unknown error"
        return if (message.length <= MAX_ERROR_LENGTH) message else message.take(MAX_ERROR_LENGTH)
    }

    private fun GraphProfileDto.toProfile(previous: Profile): Profile = Profile(
        username = username?.takeIf { it.isNotBlank() } ?: previous.username,
        bio = biography.orEmpty(),
        posts = mediaCount ?: previous.posts,
        followers = followersCount ?: previous.followers,
        following = followsCount ?: previous.following,
        avatarUrl = profilePictureUrl?.takeIf { it.isNotBlank() } ?: previous.avatarUrl,
        fetchedAt = System.currentTimeMillis(),
    )
}

private class GraphCallError(message: String, val authFailed: Boolean) : IOException(message)

private data class PathResult(val proxy: Boolean, val result: Result<Profile>)

private data class LiveOutcome(
    val profile: Profile?,
    val viaProxy: Boolean,
    val directError: Throwable?,
    val proxyError: Throwable?,
)

@Serializable
private data class GraphProfileDto(
    val username: String? = null,
    val biography: String? = null,
    @SerialName("media_count") val mediaCount: Int? = null,
    @SerialName("followers_count") val followersCount: Int? = null,
    @SerialName("follows_count") val followsCount: Int? = null,
    @SerialName("profile_picture_url") val profilePictureUrl: String? = null,
)

@Serializable
private data class ProxyProfileDto(
    val username: String? = null,
    val bio: String? = null,
    val posts: Int? = null,
    val followers: Int? = null,
    val following: Int? = null,
    val avatar: String? = null,
)

@Serializable
private data class GraphErrorEnvelope(val error: GraphError? = null)

@Serializable
private data class GraphError(
    val message: String? = null,
    val code: Int? = null,
)
