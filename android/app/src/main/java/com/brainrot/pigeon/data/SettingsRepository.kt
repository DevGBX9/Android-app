package com.brainrot.pigeon.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.brainrot.pigeon.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val IG_USER_ID_KEY = stringPreferencesKey("ig_user_id")
private val IG_TOKEN_KEY = stringPreferencesKey("ig_access_token")
private val R2_ENDPOINT_KEY = stringPreferencesKey("r2_upload_endpoint")
private val R2_TOKEN_KEY = stringPreferencesKey("r2_token")

data class AppSettings(
    val igUserId: String,
    val accessToken: String,
    val r2UploadEndpoint: String,
    val r2Token: String,
)

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        AppSettings(
            igUserId = prefs[IG_USER_ID_KEY]?.trim()?.takeIf { it.isNotEmpty() }
                ?: BuildConfig.DEFAULT_IG_USER_ID,
            accessToken = prefs[IG_TOKEN_KEY]?.trim().orEmpty(),
            r2UploadEndpoint = prefs[R2_ENDPOINT_KEY]?.trim()
                ?: BuildConfig.DEFAULT_R2_ENDPOINT,
            r2Token = prefs[R2_TOKEN_KEY].orEmpty(),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun save(
        igUserId: String,
        accessToken: String,
        r2UploadEndpoint: String,
        r2Token: String,
    ) {
        context.settingsStore.edit { prefs ->
            prefs[IG_USER_ID_KEY] = igUserId.trim()
            prefs[IG_TOKEN_KEY] = accessToken.trim()
            prefs[R2_ENDPOINT_KEY] = r2UploadEndpoint.trim()
            prefs[R2_TOKEN_KEY] = r2Token.trim()
        }
    }
}
