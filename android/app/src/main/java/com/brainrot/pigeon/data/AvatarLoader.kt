package com.brainrot.pigeon.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * تحميل صورة البروفايل بدون مكتبات صور إضافية.
 * المفتاح هو مسار الصورة فقط، لأن روابط إنستغرام تُوقَّع من جديد مع كل طلب
 * (?oh=...&oe=...)، والمقارنة بالرابط الكامل كانت تسبب أنيميشن وهمي.
 */
class AvatarLoader(private val client: OkHttpClient) {

    private val cache = LruCache<String, ImageBitmap>(12)

    fun cacheKey(url: String): String =
        runCatching { Uri.parse(url).path }.getOrNull() ?: url

    suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null

        val key = cacheKey(url)
        cache.get(key)?.let { return@withContext it }

        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Referrer-Policy", "no-referrer")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val bytes = response.body?.bytes() ?: throw IOException("empty body")
                val bitmap: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IOException("decode failed")
                bitmap.asImageBitmap().also { cache.put(key, it) }
            }
        }.getOrNull()
    }
}
