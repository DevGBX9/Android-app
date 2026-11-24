package com.brainrot.pigeon.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val SAVED_ITEMS_KEY = stringPreferencesKey("saved_items_json")
private const val MAX_SAVED_ITEMS = 300
private const val MAX_THUMBNAIL_LOOKUPS = 30
private const val ARCHIVE_TIMEOUT_MS = 210_000L

/**
 * مصدر الحقيقة المحلي للريلز (DataStore) مع نسخة على Firebase:
 * الإضافة تُكتب محلياً فوراً ثم تُرفع في الخلفية، وعند كل فتح للتطبيق
 * تُسحب القائمة من Firebase وتُدمج مع المحلي.
 *
 * بعد نجاح الرفع يُطلب من Worker على Cloudflare أرشفة الريل في R2،
 * فتظهر نسبة المعالجة على البطاقة ثم يصير الريل قابلاً للتحميل على الجهاز.
 */
class SavedRepository(
    private val context: Context,
    private val remote: FirebaseReels,
    private val thumbnails: ReelThumbnails,
    private val archive: ReelArchive,
    private val scope: CoroutineScope,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val items: Flow<List<SavedItem>> = context.savedStore.data.map { prefs ->
        decode(prefs[SAVED_ITEMS_KEY])
    }

    suspend fun add(item: SavedItem) {
        context.savedStore.edit { prefs ->
            val current = decode(prefs[SAVED_ITEMS_KEY])
            val next = (listOf(item) + current.filterNot { it.id == item.id })
                .take(MAX_SAVED_ITEMS)
            prefs[SAVED_ITEMS_KEY] = json.encodeToString(next)
        }

        // الرفع لا يعطّل الواجهة ولا ينتهي بإغلاق شاشة المشاركة.
        if (item.url != null) scope.launch { register(item) }
    }

    suspend fun delete(id: String) {
        context.savedStore.edit { prefs ->
            val next = decode(prefs[SAVED_ITEMS_KEY]).filterNot { it.id == id }
            prefs[SAVED_ITEMS_KEY] = json.encodeToString(next)
        }
    }

    /**
     * يسحب القائمة من Firebase ويدمجها مع المحلي (سجلات Firebase تتقدّم)،
     * ثم يرفع أي عنصر محلي ما وصل للقاعدة بعد، ويطلب أرشفة ما لم يُؤرشف.
     */
    suspend fun syncFromRemote(): Result<Unit> {
        val remoteItems = remote.fetchAll().getOrElse { return Result.failure(it) }
        val remoteIds = remoteItems.map { it.id }.toSet()
        var pending: List<SavedItem> = emptyList()
        var toArchive: List<SavedItem> = emptyList()

        context.savedStore.edit { prefs ->
            val local = decode(prefs[SAVED_ITEMS_KEY])
            pending = local.filter { it.url != null && !it.registered && it.id !in remoteIds }

            // LinkedHashMap: سجلات Firebase أولاً ثم ما هو محلي فقط.
            val merged = LinkedHashMap<String, SavedItem>()
            remoteItems.forEach { merged[it.id] = it.copy(registered = true) }
            local.forEach { item -> merged.putIfAbsent(item.id, item) }

            val next = merged.values
                .sortedByDescending { it.createdAt }
                .take(MAX_SAVED_ITEMS)

            toArchive = next.filter {
                it.url != null &&
                    it.registered &&
                    it.archiveState == null &&
                    isArchivable(it.url)
            }

            // لا نكتب إذا ما تغيّر شيء، حتى لا تُعاد الواجهة كل ثانية.
            if (next != local) prefs[SAVED_ITEMS_KEY] = json.encodeToString(next)
        }

        pending.forEach { register(it) }
        toArchive.forEach { item -> scope.launch { archive.request(item) } }
        return Result.success(Unit)
    }

    /**
     * يضمن أن الريل مؤرشف في R2 ثم ينزّله إلى Downloads على الجهاز.
     * [onProgress] من 0 إلى 100: 0..85 معالجة الخادم، 85..100 تحميل الملف.
     */
    suspend fun download(item: SavedItem, onProgress: (Int) -> Unit): Result<String> {
        val archived = awaitArchive(item, onProgress).getOrElse { return Result.failure(it) }

        return archive.downloadToDevice(archived) { fraction ->
            onProgress(85 + (fraction * 15f).toInt().coerceIn(0, 15))
        }
    }

    /** ينتظر انتهاء معالجة الريل على الخادم، ويطلب البدء إن لم يبدأ بعد. */
    private suspend fun awaitArchive(
        item: SavedItem,
        onProgress: (Int) -> Unit,
    ): Result<SavedItem> {
        var current = item
        if (current.archiveState == "ready" && !current.downloadUrl.isNullOrBlank()) {
            return Result.success(current)
        }
        if (!archive.configured) {
            return Result.failure(IOException("the archive service is not configured"))
        }

        if (current.archiveState == null || current.archiveState == "failed") {
            archive.request(current)
        }

        val deadline = System.currentTimeMillis() + ARCHIVE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(1200)
            val status = remote.fetchArchive(current.id) ?: continue
            onProgress(status.progress.coerceIn(0, 100) * 85 / 100)

            when (status.state) {
                "ready" -> {
                    current = current.copy(
                        archiveState = "ready",
                        archiveProgress = 100,
                        downloadUrl = status.url?.takeIf { it.isNotBlank() },
                    )
                    updateArchive(current)
                    return Result.success(current)
                }

                "failed" -> {
                    return Result.failure(
                        IOException(status.error ?: "the server could not archive this reel"),
                    )
                }
            }
        }

        return Result.failure(IOException("the server is still processing this reel"))
    }

    /**
     * يجيب صور الغلاف للريلز القديمة التي انضافت قبل إضافة الثمبنيل،
     * بحد أقصى 30 محاولة في كل فتح للتطبيق.
     */
    suspend fun ensureThumbnails() {
        val local = runCatching { context.savedStore.data.first()[SAVED_ITEMS_KEY] }
            .getOrNull()
            ?.let(::decode)
            .orEmpty()

        local.asSequence()
            .filter { it.url != null && it.thumbnail == null }
            .take(MAX_THUMBNAIL_LOOKUPS)
            .forEach { item ->
                val thumb = thumbnails.thumbnailFor(item.url) ?: return@forEach
                updateThumbnail(item.id, thumb)
            }
    }

    private suspend fun register(item: SavedItem) {
        val link = item.url ?: return
        val thumb = item.thumbnail ?: thumbnails.thumbnailFor(link)
        if (thumb != null && thumb != item.thumbnail) updateThumbnail(item.id, thumb)

        if (!remote.upload(item.copy(thumbnail = thumb))) return
        markRegistered(item.id)

        // الأرشفة في Cloudflare: ريلز إنستغرام وفيديوهات تيك توك.
        if (isArchivable(link)) archive.request(item.copy(thumbnail = thumb))
    }

    private suspend fun markRegistered(id: String) {
        context.savedStore.edit { prefs ->
            val next = decode(prefs[SAVED_ITEMS_KEY]).map { saved ->
                if (saved.id == id) saved.copy(registered = true) else saved
            }
            prefs[SAVED_ITEMS_KEY] = json.encodeToString(next)
        }
    }

    private suspend fun updateThumbnail(id: String, thumbnail: String) {
        context.savedStore.edit { prefs ->
            val next = decode(prefs[SAVED_ITEMS_KEY]).map { saved ->
                if (saved.id == id) saved.copy(thumbnail = thumbnail) else saved
            }
            prefs[SAVED_ITEMS_KEY] = json.encodeToString(next)
        }
    }

    private suspend fun updateArchive(item: SavedItem) {
        context.savedStore.edit { prefs ->
            val next = decode(prefs[SAVED_ITEMS_KEY]).map { saved ->
                if (saved.id == item.id) {
                    saved.copy(
                        archiveState = item.archiveState,
                        archiveProgress = item.archiveProgress,
                        downloadUrl = item.downloadUrl,
                    )
                } else {
                    saved
                }
            }
            prefs[SAVED_ITEMS_KEY] = json.encodeToString(next)
        }
    }

    private fun decode(raw: String?): List<SavedItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<SavedItem>>(raw) }
            .getOrDefault(emptyList())
    }

    /**
     * الأرشفة تعمل لريلز إنستغرام وفيديوهات تيك توك فقط (لا بروفايلات تيك توك)،
     * بنفس معرّفات الـWorker.
     */
    private fun isArchivable(link: String?): Boolean {
        if (link == null) return false
        if (link.contains("instagram.com")) return true
        if (!link.contains("tiktok.com")) return false
        return link.contains("/video/") ||
            link.contains("vm.tiktok.com") ||
            link.contains("vt.tiktok.com") ||
            link.contains("/t/")
    }
}
