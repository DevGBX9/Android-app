package com.brainrot.pigeon.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.brainrot.pigeon.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val API_ENDPOINT_KEY = stringPreferencesKey("api_endpoint")
private val R2_ENDPOINT_KEY = stringPreferencesKey("r2_upload_endpoint")
private val R2_TOKEN_KEY = stringPreferencesKey("r2_token")

data class AppSettings(
    val apiEndpoint: String,
    val r2UploadEndpoint: String,
    val r2Token: String,
)

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        AppSettings(
            apiEndpoint = prefs[API_ENDPOINT_KEY]?.trim()?.takeIf { it.isNotEmpty() }
                ?: BuildConfig.DEFAULT_API_ENDPOINT,
            r2UploadEndpoint = prefs[R2_ENDPOINT_KEY]?.trim()
                ?: BuildConfig.DEFAULT_R2_ENDPOINT,
            r2Token = prefs[R2_TOKEN_KEY].orEmpty(),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun save(apiEndpoint: String, r2UploadEndpoint: String, r2Token: String) {
        context.settingsStore.edit { prefs ->
            prefs[API_ENDPOINT_KEY] = apiEndpoint.trim()
            prefs[R2_ENDPOINT_KEY] = r2UploadEndpoint.trim()
            prefs[R2_TOKEN_KEY] = r2Token.trim()
        }
    }
}
