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
private const val IG_OEMBED_ENDPOINT = "https://www.instagram.com/api/v1/oembed/"
private const val TT_OEMBED_ENDPOINT = "https://www.tiktok.com/oembed"
private const val TT_MOBILE_PAGE = "https://m.tiktok.com/v/"
private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/** أول رابط صورة داخل صفحة تيك توك (الغلاف يظهر بصيغة `...cover:720:720.jpeg?...`). */
private val PAGE_IMAGE = Regex("""https?://[^"'\s\\<>]+\.(?:jpe?g|webp)(?:\?[^"'\s\\<>]*)""")

/**
 * صورة غلاف الفيديو، لنفس الفيديو الأصلي:
 *
 * - إنستغرام: نقطة oEmbed العامة (`/api/v1/oembed/?url=...`) → `thumbnail_url`.
 * - تيك توك: نقطة oEmbed العامة أولاً، وإن ما رجّعت صورة (معظم الفيديوهات
 *   حالياً) نقرأ صفحة الفيديو على `m.tiktok.com` ونأخذ رابط الغلاف الموقّع منها.
 *   روابط `vm/vt.tiktok.com` المختصرة تُتبع تحويلاتها أولاً للحصول على كود الفيديو.
 *
 * كل المسارات بدون توكن ولا مكتبات إضافية.
 */
class ReelThumbnails(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun thumbnailFor(url: String?): String? = withContext(Dispatchers.IO) {
        val link = url?.trim().orEmpty()
        if (link.isBlank()) return@withContext null

        when {
            isInstagram(link) -> instagramThumbnail(link)
            isTikTok(link) -> tiktokThumbnail(link)
            else -> null
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

    private fun instagramThumbnail(link: String): String? {
        val code = shortcode(link) ?: return null
        val endpoint = IG_OEMBED_ENDPOINT.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("url", "https://www.instagram.com/reel/$code/")
            ?.build()
            ?.toString()
            ?: return null
        return oembedThumbnail(endpoint)
    }

    private fun tiktokThumbnail(link: String): String? {
        val id = tiktokVideoId(link)
        if (id == null) {
            Log.w(LOG_TAG, "tiktok: no video id")
            return null
        }

        // 1) oEmbed العامة: ترجع thumbnail_url لبعض الفيديوهات فقط.
        oembedThumbnail("$TT_OEMBED_ENDPOINT?url=https%3A%2F%2Fwww.tiktok.com%2F%40tiktok%2Fvideo%2F$id")
            ?.let { return it }

        // 2) صفحة الفيديو: تحتوي رابط غلاف موقّع (تم التحقق: JPEG 200 OK).
        return tiktokPageCover(id)
    }

    private fun tiktokVideoId(link: String): String? {
        videoIdFromPath(link)?.let { return it }
        // رابط مختصر (vm/vt.tiktok.com أو /t/...): نتبع التحويل إلى /video/<id>.
        return followRedirects(link)?.let(::videoIdFromPath)
    }

    private fun videoIdFromPath(link: String): String? {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        val segments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        val index = segments.indexOfFirst { it.equals("video", ignoreCase = true) }
        if (index < 0) return null
        return segments.getOrNull(index + 1)
            ?.filter { it.isDigit() }
            ?.takeIf { it.length >= 8 }
    }

    private fun followRedirects(link: String): String? {
        val url = link.toHttpUrlOrNull() ?: return null
        return runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", MOBILE_UA)
                    .build(),
            ).execute().use { response -> response.request.url.toString() }
        }.getOrElse { error ->
            Log.w(LOG_TAG, "tiktok resolve failed: ${error.message}")
            null
        }
    }

    private fun tiktokPageCover(id: String): String? {
        val url = (TT_MOBILE_PAGE + "$id.html").toHttpUrlOrNull() ?: return null

        val found = runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", MOBILE_UA)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(LOG_TAG, "tiktok page HTTP ${response.code}")
                    return@use null
                }

                val html = response.body?.string().orEmpty()
                val images = PAGE_IMAGE.findAll(html).map { it.value }.toList()
                images.firstOrNull { it.contains("cover") || it.contains("tplv") }
                    ?: images.firstOrNull()
            }
        }.onFailure { error ->
            Log.w(LOG_TAG, "tiktok page failed: ${error.message}")
        }.getOrNull()

        return found?.replace("&amp;", "&")
    }

    private fun oembedThumbnail(endpoint: String): String? {
        val url = endpoint.toHttpUrlOrNull() ?: return null

        return runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", MOBILE_UA)
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(LOG_TAG, "oembed HTTP ${response.code} (${url.host})")
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

    private fun isInstagram(link: String): Boolean {
        val host = runCatching { URI(link).host }.getOrNull()?.lowercase(Locale.US) ?: return false
        return host == "instagram.com" || host.endsWith(".instagram.com")
    }

    private fun isTikTok(link: String): Boolean {
        val host = runCatching { URI(link).host }.getOrNull()?.lowercase(Locale.US) ?: return false
        return host == "tiktok.com" || host.endsWith(".tiktok.com")
    }
}

@Serializable
private data class OembedDto(
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
)
