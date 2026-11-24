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
class AvatarLoader(private val client: OkHttpClient, cacheSize: Int = 12) {

    private val cache = LruCache<String, ImageBitmap>(cacheSize)

    fun cacheKey(url: String): String =
        runCatching { Uri.parse(url).path }.getOrNull() ?: url

    /**
     * [targetSize] هو أكبر بعد مطلوب بالبكسل؛ تُفكّ الصورة بحجم مصغّر يناسبه
     * (مثل خانات الشبكة) حتى ما نحمّل صورة كبيرة ونصغّرها كل مرة — أخف على
     * الذاكرة وأسلس عند السحب. القيمة 0 تعني الحجم الكامل.
     */
    suspend fun load(url: String, targetSize: Int = 0): ImageBitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null

        val key = if (targetSize > 0) "${cacheKey(url)}@$targetSize" else cacheKey(url)
        cache.get(key)?.let { return@withContext it }

        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Referrer-Policy", "no-referrer")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val bytes = response.body?.bytes() ?: throw IOException("empty body")
                val sample = sampleSize(bytes, targetSize)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?: throw IOException("decode failed")
                bitmap.asImageBitmap().also { cache.put(key, it) }
            }
        }.getOrNull()
    }

    /** أكبر inSampleSize يجعل أكبر بعد في الصورة <= [targetSize]. */
    private fun sampleSize(bytes: ByteArray, targetSize: Int): Int {
        if (targetSize <= 0) return 1

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return 1

        var sample = 1
        while (longest / (sample * 2) >= targetSize) sample *= 2
        return sample
    }
}
