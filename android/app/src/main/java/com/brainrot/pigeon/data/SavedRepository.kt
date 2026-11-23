package com.brainrot.pigeon.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val SAVED_ITEMS_KEY = stringPreferencesKey("saved_items_json")
private const val MAX_SAVED_ITEMS = 300

/**
 * مصدر الحقيقة المحلي للريلز (DataStore) مع نسخة على Firebase:
 * الإضافة تُكتب محلياً فوراً ثم تُرفع في الخلفية، وعند كل فتح للتطبيق
 * تُسحب القائمة من Firebase وتُدمج مع المحلي.
 */
class SavedRepository(
    private val context: Context,
    private val remote: FirebaseReels,
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
     * ثم يرفع أي عنصر محلي ما وصل للقاعدة بعد.
     */
    suspend fun syncFromRemote(): Result<Unit> {
        val remoteItems = remote.fetchAll().getOrElse { return Result.failure(it) }
        val remoteIds = remoteItems.map { it.id }.toSet()
        var pending: List<SavedItem> = emptyList()

        context.savedStore.edit { prefs ->
            val local = decode(prefs[SAVED_ITEMS_KEY])
            pending = local.filter { it.url != null && !it.registered && it.id !in remoteIds }

            // LinkedHashMap: سجلات Firebase أولاً ثم ما هو محلي فقط.
            val merged = LinkedHashMap<String, SavedItem>()
            remoteItems.forEach { merged[it.id] = it.copy(registered = true) }
            local.forEach { item -> merged.putIfAbsent(item.id, item) }

            prefs[SAVED_ITEMS_KEY] = json.encodeToString(
                merged.values
                    .sortedByDescending { it.createdAt }
                    .take(MAX_SAVED_ITEMS),
            )
        }

        pending.forEach { register(it) }
        return Result.success(Unit)
    }

    private suspend fun register(item: SavedItem) {
        if (!remote.upload(item)) return

        context.savedStore.edit { prefs ->
            val next = decode(prefs[SAVED_ITEMS_KEY]).map { saved ->
                if (saved.id == item.id) saved.copy(registered = true) else saved
            }
            prefs[SAVED_ITEMS_KEY] = json.encodeToString(next)
        }
    }

    private fun decode(raw: String?): List<SavedItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<SavedItem>>(raw) }
            .getOrDefault(emptyList())
    }
}
