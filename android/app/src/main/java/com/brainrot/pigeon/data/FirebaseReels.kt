package com.brainrot.pigeon.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val LOG_TAG = "PigeonReels"
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * قائمة الريلز المجدولة في Firebase Realtime Database عبر REST مباشرة،
 * بدون SDK وبدون google-services.json: الشكل هو
 * `<db>/reels/<id>.json` بمفاتيح مسطّحة.
 *
 * كل السجلات تحمل [RemoteReel.registered] لتوضيح أنها مسجلة في التطبيق.
 */
class FirebaseReels(
    private val client: OkHttpClient,
    databaseUrl: String,
) {

    private val endpoint = databaseUrl.trim().trimEnd('/') + "/reels"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** يرفع سجلاً واحداً (أو يستبدله بنفس المفتاح). */
    suspend fun upload(item: SavedItem): Boolean = withContext(Dispatchers.IO) {
        val url = "$endpoint/${item.id}.json".toHttpUrlOrNull() ?: return@withContext false
        val body = json.encodeToString(RemoteReel.from(item)).toRequestBody(JSON_MEDIA)

        runCatching {
            client.newCall(Request.Builder().url(url).put(body).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(LOG_TAG, "upload HTTP ${response.code}")
                }
                response.isSuccessful
            }
        }.getOrElse { error ->
            Log.w(LOG_TAG, "upload failed: ${error.message}")
            false
        }
    }

    /** يسحب كل الريلز؛ القيمة `null` تعني قائمة فاضية وليست خطأ. */
    suspend fun fetchAll(): Result<List<SavedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$endpoint/.json".toHttpUrlOrNull() ?: error("bad database url")

            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build(),
            ).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("firebase HTTP ${response.code}")
                if (body.isBlank() || body.trim() == "null") {
                    return@use emptyList<SavedItem>()
                }

                val records = json.decodeFromString<Map<String, RemoteReel>>(body)
                records.mapNotNull { (id, record) -> record.toSavedItem(id) }
            }
        }.onFailure { error -> Log.w(LOG_TAG, "fetch failed: ${error.message}") }
    }
}

@Serializable
private data class RemoteReel(
    val platform: String = "instagram",
    val kind: String = "reel",
    val url: String? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    val registered: Boolean = true,
    val source: String = "android",
    val createdAt: Long = 0L,
) {
    fun toSavedItem(id: String): SavedItem? {
        val link = url?.takeIf { it.isNotBlank() } ?: return null
        return SavedItem(
            id = id,
            platform = platform,
            kind = kind,
            url = link,
            title = title,
            thumbnail = thumbnail?.takeIf { it.isNotBlank() },
            registered = registered,
            createdAt = if (createdAt > 0L) createdAt else System.currentTimeMillis(),
        )
    }

    companion object {
        fun from(item: SavedItem) = RemoteReel(
            platform = item.platform,
            kind = item.kind,
            url = item.url,
            title = item.title,
            thumbnail = item.thumbnail,
            registered = true,
            source = "android",
            createdAt = if (item.createdAt > 0L) item.createdAt else System.currentTimeMillis(),
        )
    }
}
