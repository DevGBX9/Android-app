package com.brainrot.pigeon.share

import java.net.URI
import java.util.Locale

data class ShareTarget(
    val platform: String,
    val kind: String,
    val url: String,
    val title: String,
)

/**
 * تحليل روابط Instagram Reels وTikTok القادمة من Share Sheet أو من روابط VIEW.
 * كلاس نقي بدون أي اعتماد على Android حتى ينختبر بـ JVM unit tests.
 */
object ShareParser {

    const val PLATFORM_INSTAGRAM = "instagram"
    const val PLATFORM_TIKTOK = "tiktok"
    const val PLATFORM_LINK = "link"

    private val urlRegex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    fun firstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = urlRegex.find(text)?.value ?: return null
        return match.trimEnd('.', ',', ')', ']', '}', '"', '\'')
    }

    fun fromText(text: String?): ShareTarget? {
        val url = firstUrl(text) ?: return null
        return classify(url)
    }

    fun classify(rawUrl: String): ShareTarget? {
        val normalized = when {
            rawUrl.startsWith("http://", ignoreCase = true) -> rawUrl
            rawUrl.startsWith("https://", ignoreCase = true) -> rawUrl
            rawUrl.startsWith("www.", ignoreCase = true) -> "https://$rawUrl"
            else -> return null
        }

        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        val path = uri.path.orEmpty()

        return when {
            host == "instagram.com" || host.endsWith(".instagram.com") ->
                instagram(host, path)

            host == "tiktok.com" || host.endsWith(".tiktok.com") ->
                tiktok(host, path)

            else -> ShareTarget(
                platform = PLATFORM_LINK,
                kind = "link",
                url = normalized,
                title = host.removePrefix("www."),
            )
        }
    }

    private fun instagram(host: String, path: String): ShareTarget {
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        val type = segments.firstOrNull()?.lowercase(Locale.US).orEmpty()
        val code = segments.getOrNull(1).orEmpty()

        val kind = when (type) {
            "reel", "reels" -> "reel"
            "p" -> "post"
            "tv" -> "tv"
            else -> "profile"
        }

        val url = when (kind) {
            "reel" -> "https://www.instagram.com/reel/$code"
            "post" -> "https://www.instagram.com/p/$code"
            "tv" -> "https://www.instagram.com/tv/$code"
            else -> "https://$host/" + segments.joinToString("/")
        }

        val title = when (kind) {
            "reel" -> "Instagram Reel" + if (code.isNotEmpty()) " · $code" else ""
            "post" -> "Instagram Post" + if (code.isNotEmpty()) " · $code" else ""
            "tv" -> "Instagram TV" + if (code.isNotEmpty()) " · $code" else ""
            else -> "Instagram · @${segments.firstOrNull().orEmpty()}"
        }

        return ShareTarget(PLATFORM_INSTAGRAM, kind, url, title)
    }

    private fun tiktok(host: String, path: String): ShareTarget {
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        val isVideo = segments.any { it.equals("video", ignoreCase = true) }
        val videoId = segments.lastOrNull { segment -> segment.all { it.isDigit() } }.orEmpty()

        val kind = if (isVideo) "video" else "short"
        val url = "https://$host$path"
        val title = if (kind == "video" && videoId.isNotEmpty()) {
            "TikTok · video $videoId"
        } else {
            "TikTok · $host"
        }

        return ShareTarget(PLATFORM_TIKTOK, kind, url, title)
    }
}
