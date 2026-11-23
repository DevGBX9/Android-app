package com.brainrot.pigeon.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val SAVED_ITEMS_KEY = stringPreferencesKey("saved_items_json")
private const val MAX_SAVED_ITEMS = 300

class SavedRepository(private val context: Context) {

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
    }

    suspend fun delete(id: String) {
        context.savedStore.edit { prefs ->
            val next = decode(prefs[SAVED_ITEMS_KEY]).filterNot { it.id == id }
            prefs[SAVED_ITEMS_KEY] = json.encodeToString(next)
        }
    }

    suspend fun markUploaded(id: String, remoteUrl: String) {
        context.savedStore.edit { prefs ->
            val next = decode(prefs[SAVED_ITEMS_KEY]).map { item ->
                if (item.id == id) item.copy(remoteUrl = remoteUrl) else item
            }
            prefs[SAVED_ITEMS_KEY] = json.encodeToString(next)
        }
    }

    suspend fun clear() {
        context.savedStore.edit { prefs -> prefs.remove(SAVED_ITEMS_KEY) }
    }

    private fun decode(raw: String?): List<SavedItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<SavedItem>>(raw) }
            .getOrDefault(emptyList())
    }
}
