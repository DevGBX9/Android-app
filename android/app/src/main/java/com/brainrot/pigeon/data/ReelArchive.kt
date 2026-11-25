package com.brainrot.pigeon.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val ARCHIVE_MEDIA = "application/json; charset=utf-8".toMediaType()

// إعادة محاولة قصيرة عند أخطاء الشبكة أو 5xx (الـWorker قد يكون يعيد النشر).
private const val REQUEST_ATTEMPTS = 3

/** حالة معالجة الريل على الـWorker (نفس الشكل المكتوب في Firebase). */
@Serializable
data class ArchiveStatus(
    val state: String = "unknown",
    val progress: Int = 0,
    val url: String? = null,
    val bytes: Long = 0L,
    val error: String? = null,
)

@Serializable
private data class ArchiveRequest(
    val id: String,
    val url: String,
    val thumbnail: String? = null,
    // رابط CDN النظيف لتيك توك (يُحل من الهاتف لأن Cloudflare محجوب عن الحل).
    val media: String? = null,
)

/**
 * عميل Pigeon Reels API على Cloudflare: يطلب أرشفة ريل إنستغرام في R2،
 * ثم ينزّل الملف المخزّن إلى مجلد Downloads على الجهاز مع تقدّم 0..100.
 */
class ReelArchive(
    context: Context,
    baseClient: OkHttpClient,
    endpoint: String,
    private val token: String,
) {

    private val appContext = context.applicationContext
    private val base = endpoint.trim().trimEnd('/')

    // التحميل الحقيقي قد يأخذ دقائق: بلا مهلة إجمالية ومهلة قراءة سخية.
    private val http: OkHttpClient = baseClient.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val configured: Boolean get() = base.isNotBlank()

    /**
     * يطلب من الخادم تنزيل الريل وتخزينه. يعيد `null` إذا قُبل الطلب،
     * أو رسالة الخطأ القادمة من الشبكة/الخادم حتى تظهر للمستخدم.
     */
    suspend fun request(item: SavedItem, media: String? = null): String? =
        withContext(Dispatchers.IO) {
        val link = item.url?.takeIf { it.isNotBlank() } ?: return@withContext "the reel has no link"
        if (!configured) return@withContext "the archive service is not configured"

        val body = json
            .encodeToString(
                ArchiveRequest(
                    id = item.id,
                    url = link,
                    thumbnail = item.thumbnail,
                    media = media?.takeIf { it.startsWith("https://") },
                ),
            )
            .toRequestBody(ARCHIVE_MEDIA)
        val request = Request.Builder()
            .url("$base/reels")
            .header("Accept", "application/json")
            .apply { if (token.isNotBlank()) header("X-Api-Key", token) }
            .post(body)
            .build()

        var lastError = "the archive request failed"
        for (attempt in 0 until REQUEST_ATTEMPTS) {
            if (attempt > 0) delay(1_500L * attempt)

            runCatching {
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return@withContext null
                    val detail = runCatching { response.body?.string().orEmpty() }
                        .getOrDefault("")
                        .take(160)
                    "server HTTP ${response.code}" + if (detail.isBlank()) "" else ": $detail"
                }
            }.onSuccess { message ->
                lastError = message
                // 4xx يعني أن الطلب نفسه مرفوض: لا فائدة من التكرار.
                if (!message.startsWith("server HTTP 5")) return@withContext message
            }.onFailure { error ->
                lastError = error.message?.let { "network: $it" } ?: "network error"
            }
        }
        lastError
    }

    /**
     * يطلب من الـWorker تشغيل المجدول الآن. الـWorker يتحقق من الوقت بنفسه،
     * فلا ينشر شيئاً قبل موعده؛ الفائدة أن النشر يبدأ في الثانية نفسها بدل
     * انتظار cron الذي قد يتأخر حتى دقيقة كاملة.
     */
    suspend fun publishDue(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!configured) return@runCatching

            val request = Request.Builder()
                .url("$base/publish")
                .header("Accept", "application/json")
                .apply { if (token.isNotBlank()) header("X-Api-Key", token) }
                .post("{}".toRequestBody(ARCHIVE_MEDIA))
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("server HTTP ${response.code}")
            }
        }
    }

    /**
     * يحذف الريل من الخادم: ملفه من R2 وكل معلوماته من Firebase.
     * لا يُقبل الحذف أثناء خطوة نشر جارية (يردّ الخادم 409).
     */
    suspend fun deleteRemote(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!configured) return@runCatching

            val request = Request.Builder()
                .url("$base/reels/$id")
                .header("Accept", "application/json")
                .apply { if (token.isNotBlank()) header("X-Api-Key", token) }
                .delete()
                .build()

            http.newCall(request).execute().use { response ->
                // 404: لا شيء متبقٍ على الخادم أصلاً، فالحذف ناجح.
                if (response.code == 404) return@runCatching
                if (!response.isSuccessful) {
                    val detail = runCatching { response.body?.string().orEmpty() }
                        .getOrDefault("")
                        .take(160)
                    throw IOException(
                        "server HTTP ${response.code}" + if (detail.isBlank()) "" else ": $detail",
                    )
                }
            }
        }
    }

    /**
     * ينزّل الملف المخزّن إلى Downloads (أو إلى مجلد التطبيق على أندرويد قديم).
     * [onProgress] تستقبل نسبة 0..1 من التحميل المحلي.
     */
    suspend fun downloadToDevice(
        item: SavedItem,
        onProgress: (Float) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val link = item.downloadUrl?.takeIf { it.isNotBlank() }
                ?: "$base/download/${item.id}"

            val request = Request.Builder()
                .url(link)
                .apply { if (token.isNotBlank()) header("X-Api-Key", token) }
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("server HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("empty response")
                val total = body.contentLength()
                val name = "pigeon-${item.id}.mp4"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveToDownloads(name, total, body.byteStream(), onProgress)
                } else {
                    saveToAppFolder(name, total, body.byteStream(), onProgress)
                }
            }
        }
    }

    private fun saveToDownloads(
        name: String,
        total: Long,
        input: java.io.InputStream,
        onProgress: (Float) -> Unit,
    ): String {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/brainrot.pigeon",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("cannot create the download entry")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                copy(input, output, total, onProgress)
            } ?: throw IOException("cannot open the download stream")

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }

        onProgress(1f)
        return uri.toString()
    }

    private fun saveToAppFolder(
        name: String,
        total: Long,
        input: java.io.InputStream,
        onProgress: (Float) -> Unit,
    ): String {
        val folder = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "downloads")
        folder.mkdirs()
        val file = File(folder, name)
        file.outputStream().use { output -> copy(input, output, total, onProgress) }
        onProgress(1f)
        return file.absolutePath
    }

    private fun copy(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        total: Long,
        onProgress: (Float) -> Unit,
    ) {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            copied += read
            if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
        }
    }
}
