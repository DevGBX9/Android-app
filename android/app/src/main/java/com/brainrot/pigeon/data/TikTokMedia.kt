package com.brainrot.pigeon.data

import android.util.Log
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val LOG_TAG = "PigeonTikTok"
private const val RESOLVER = "https://www.tikwm.com/api/?url="

/**
 * يحلّ رابط فيديو تيك توك إلى رابط CDN مباشر بلا علامة مائية.
 *
 * Cloudflare لا يستطيع الحل بنفسه: كل رابط يخرج من صفحة تيك توك هو رابط
 * `*-prime.tiktok.com` موقع يرد 403، وخدمة الحل تحجب عناوين Cloudflare. شبكة
 * الهاتف عادية فتُحلّ هنا، ثم ينزّل الـWorker الملف من `*.tiktokcdn.com`
 * ويخزنه في R2 بنفس مسار إنستغرام.
 */
class TikTokMedia(private val client: OkHttpClient) {

    /** رابط CDN نظيف، أو null إذا تعذّر الحل (فيعتمد الـWorker على نفسه). */
    suspend fun resolve(link: String?): String? = withContext(Dispatchers.IO) {
        val target = link?.takeIf { it.contains("tiktok.com") } ?: return@withContext null

        val body = runCatching {
            val request = Request.Builder()
                .url(RESOLVER + URLEncoder.encode(target, "UTF-8"))
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrElse { error ->
            Log.w(LOG_TAG, "resolve failed: ${error.message}")
            null
        } ?: return@withContext null

        val data = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull()
            ?: return@withContext null

        // hdplay/play نسختان بلا علامة مائية؛ نتجاهل wmplay المعلّمة.
        sequenceOf("hdplay", "play")
            .map { key -> data.optString(key, "") }
            .firstOrNull { it.startsWith("http") && isCdn(it) }
    }

    private fun isCdn(url: String): Boolean {
        val host = url.substringAfter("://", url).substringBefore('/').lowercase()
        return host.endsWith(".tiktokcdn.com") ||
            host.endsWith(".tiktokcdn-us.com") ||
            host.endsWith(".tiktokcdn-eu.com")
    }
}
