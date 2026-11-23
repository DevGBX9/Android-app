package com.brainrot.pigeon.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.brainrot.pigeon.data.SavedItem
import com.brainrot.pigeon.data.SavedRepository
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_SHARED_FILE_BYTES = 150L * 1024 * 1024

/**
 * يحوّل محتوى Intent المشاركة إلى عناصر مجدولة:
 * نص فيه رابط، ملف واحد، عدة ملفات، أو رابط تم فتحه مباشرة.
 *
 * يعيد عناوين ما أُضيف فعلاً حتى تعرض النافذة الطافية اسم الريل.
 */
class ShareIntake(
    private val context: Context,
    private val saved: SavedRepository,
) {

    suspend fun handle(intent: Intent?): List<String> = withContext(Dispatchers.IO) {
        if (intent == null) return@withContext emptyList()

        val added = mutableListOf<String>()
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)

        when (intent.action) {
            Intent.ACTION_SEND -> {
                ShareParser.fromText(sharedText)?.let { target ->
                    saved.add(target.toSavedItem())
                    added += target.title
                }
                intent.singleStream()?.let { uri ->
                    saveFile(uri)?.let { added += it }
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                ShareParser.fromText(sharedText)?.let { target ->
                    saved.add(target.toSavedItem())
                    added += target.title
                }
                intent.allStreams().forEach { uri ->
                    saveFile(uri)?.let { added += it }
                }
            }

            Intent.ACTION_VIEW -> {
                intent.dataString?.let { raw ->
                    ShareParser.classify(raw)?.let { target ->
                        saved.add(target.toSavedItem())
                        added += target.title
                    }
                }
            }
        }

        added
    }

    /** يعيد عنوان الملف بعد نسخه، أو null إذا فشل. */
    private suspend fun saveFile(uri: Uri): String? = withContext(Dispatchers.IO) {
        var target: File? = null
        try {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val displayName = queryDisplayName(uri) ?: "shared-${System.currentTimeMillis()}"

            val dir = File(context.filesDir, "saved").apply { mkdirs() }
            val safeName = displayName
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .takeLast(60)
                .ifBlank { "shared" }
            val file = File(dir, "${System.currentTimeMillis()}-$safeName")
            target = file

            resolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_SHARED_FILE_BYTES) throw IOException("file too large")
                        output.write(buffer, 0, read)
                    }
                    if (total == 0L) throw IOException("empty file")
                }
            } ?: throw IOException("cannot open stream")

            saved.add(
                SavedItem(
                    id = UUID.randomUUID().toString(),
                    platform = if (mime.startsWith("video/")) "video" else "file",
                    kind = "file",
                    title = displayName,
                    filePath = file.absolutePath,
                    fileName = displayName,
                    mimeType = mime,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            displayName
        } catch (_: Exception) {
            target?.delete()
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun Intent.singleStream(): Uri? = getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.allStreams(): List<Uri> {
        val clip = clipData
        if (clip != null && clip.itemCount > 0) {
            return (0 until clip.itemCount).mapNotNull { index -> clip.getItemAt(index).uri }
        }
        return listOfNotNull(getParcelableExtra(Intent.EXTRA_STREAM))
    }
}

private fun ShareTarget.toSavedItem(now: Long = System.currentTimeMillis()) = SavedItem(
    id = UUID.randomUUID().toString(),
    platform = platform,
    kind = kind,
    url = url,
    title = title,
    createdAt = now,
)
