package com.brainrot.pigeon.data

import android.util.Log
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private const val LOG_TAG = "PigeonThumbs"
private const val OEMBED_ENDPOINT = "https://www.instagram.com/api/v1/oembed/"
private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/**
 * يجيب صورة غلاف الريل من نقطة oEmbed العامة في إنستغرام (بدون توكن):
 * `<endpoint>?url=https://www.instagram.com/reel/<code>/` → `thumbnail_url`.
 */
class ReelThumbnails(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun thumbnailFor(reelUrl: String?): String? = withContext(Dispatchers.IO) {
        val code = shortcode(reelUrl) ?: return@withContext null

        val url = OEMBED_ENDPOINT.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("url", "https://www.instagram.com/reel/$code/")
            ?.build()
            ?: return@withContext null

        runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", MOBILE_UA)
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(LOG_TAG, "oembed HTTP ${response.code}")
                    return@use null
                }

                val body = response.body?.string().orEmpty()
                json.decodeFromString<OembedDto>(body)
                    .thumbnailUrl
                    ?.takeIf { it.startsWith("https://") }
            }
        }.getOrElse { error ->
            Log.w(LOG_TAG, "oembed failed: ${error.message}")
            null
        }
    }

    /** يستخرج كود الريل من رابط إنستغرام (reel/reels/p/tv). */
    fun shortcode(reelUrl: String?): String? {
        if (reelUrl.isNullOrBlank()) return null

        val uri = runCatching { URI(reelUrl.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        if (host != "instagram.com" && !host.endsWith(".instagram.com")) return null

        val segments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        val type = segments.firstOrNull()?.lowercase(Locale.US)
        if (type !in setOf("reel", "reels", "p", "tv")) return null

        return segments.getOrNull(1)?.takeIf { it.isNotBlank() }
    }
}

@Serializable
private data class OembedDto(
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
)
