package com.brainrot.pigeon.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private val PROFILE_JSON_KEY = stringPreferencesKey("profile_json")
private const val MIN_REQUEST_GAP_MS = 20_000L
private const val GRAPH_BASE = "https://graph.facebook.com"
private const val GRAPH_VERSION = "v27.0"
private const val GRAPH_FIELDS =
    "id,username,name,profile_picture_url,biography,followers_count,follows_count,media_count"

/**
 * يجيب بيانات الحساب مباشرة من Instagram Graph API بالتوكن المدمج في البناء
 * (بدون بروكسي أو مفتاح وسيط وبدون شاشة إعدادات).
 * - يبدأ ببيانات محفوظة على الجهاز (أو القيم الاحتياطية).
 * - يمنع تراكم الطلبات بـ Mutex + أقل فاصل زمني بين طلبين.
 * - يوقف نفسه إذا رفض Graph التوكن (401/403 أو رمز 190) بدل ما يضرب كل 30 ثانية،
 *   ويرجع يشتغل عند تغيير الإعدادات عبر [reset].
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

    private val mutex = Mutex()
    private var lastRequestAt = 0L
    private var cacheLoaded = false

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

            val url = "$GRAPH_BASE/$GRAPH_VERSION/${config.igUserId.trim()}".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("fields", GRAPH_FIELDS)
                ?.addQueryParameter("access_token", config.accessToken.trim())
                ?.build()
                ?: return@withLock Result.failure(IllegalArgumentException("bad ig user id"))

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .cacheControl(CacheControl.Builder().noCache().build())
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty() ?: ""
                    if (!response.isSuccessful) {
                        val error = runCatching {
                            json.decodeFromString<GraphErrorEnvelope>(body)
                        }.getOrNull()?.error
                        if (response.code == 401 || response.code == 403 || error?.code == 190) {
                            apiDisabled = true
                        }
                        throw IOException(error?.message ?: "HTTP ${response.code}")
                    }
                    if (body.isBlank()) throw IOException("empty response")
                    json.decodeFromString<GraphProfileDto>(body)
                }
            }
                .mapCatching { dto -> dto.toProfile(_profile.value) }
                .onSuccess { fresh ->
                    _profile.value = fresh
                    persist(fresh)
                }
        }

    private suspend fun persist(profile: Profile) {
        runCatching {
            context.profileStore.edit { prefs ->
                prefs[PROFILE_JSON_KEY] = json.encodeToString(profile)
            }
        }
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
private data class GraphErrorEnvelope(val error: GraphError? = null)

@Serializable
private data class GraphError(
    val message: String? = null,
    val code: Int? = null,
)
