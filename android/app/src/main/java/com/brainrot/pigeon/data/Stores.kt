package com.brainrot.pigeon.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "pigeon_settings")
val Context.savedStore: DataStore<Preferences> by preferencesDataStore(name = "pigeon_saved")
val Context.profileStore: DataStore<Preferences> by preferencesDataStore(name = "pigeon_profile")
