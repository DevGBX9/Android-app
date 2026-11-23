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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

private val PROFILE_JSON_KEY = stringPreferencesKey("profile_json")
private const val MIN_REQUEST_GAP_MS = 20_000L

/**
 * يجيب بيانات الحساب من نفس بروكسي Cloudflare Worker اللي يستخدمه الموقع.
 * - يبدأ ببيانات محفوظة على الجهاز (أو القيم الاحتياطية).
 * - يمنع تراكم الطلبات بـ Mutex + أقل فاصل زمني بين طلبين.
 * - يوقف نفسه إذا رفض الـ API التوكن (401/403) بدل ما يضرب كل 30 ثانية.
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

    suspend fun refresh(minGapMs: Long = MIN_REQUEST_GAP_MS): Result<Profile> =
        mutex.withLock {
            if (apiDisabled) {
                return@withLock Result.failure(IllegalStateException("api disabled"))
            }

            val now = System.currentTimeMillis()
            if (now - lastRequestAt < minGapMs) {
                return@withLock Result.failure(IllegalStateException("too soon"))
            }
            lastRequestAt = now

            val endpoint = settings.current().apiEndpoint
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .cacheControl(CacheControl.Builder().noCache().build())
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    if (response.code == 401 || response.code == 403) {
                        apiDisabled = true
                    }
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful || body.isBlank()) {
                        throw IOException("HTTP ${response.code}")
                    }
                    json.decodeFromString<ProfileDto>(body)
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

    private fun ProfileDto.toProfile(previous: Profile): Profile = Profile(
        username = username?.takeIf { it.isNotBlank() } ?: previous.username,
        bio = bio.orEmpty(),
        posts = posts ?: previous.posts,
        followers = followers ?: previous.followers,
        following = following ?: previous.following,
        avatarUrl = avatar.orEmpty(),
        fetchedAt = System.currentTimeMillis(),
    )
}

@Serializable
private data class ProfileDto(
    val username: String? = null,
    val bio: String? = null,
    val posts: Int? = null,
    val followers: Int? = null,
    val following: Int? = null,
    val avatar: String? = null,
)
