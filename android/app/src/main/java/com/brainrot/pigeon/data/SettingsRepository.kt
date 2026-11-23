package com.brainrot.pigeon.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.brainrot.pigeon.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val R2_ENDPOINT_KEY = stringPreferencesKey("r2_upload_endpoint")
private val R2_TOKEN_KEY = stringPreferencesKey("r2_token")

/**
 * التطبيق شخصي: توكن إنستغرام وUser ID مدمجان في البناء ولا يمكن تعديلهما
 * من داخل التطبيق. لا توجد شاشة إعدادات.
 */
data class AppSettings(
    val igUserId: String,
    val accessToken: String,
    /** بروكسي الموقع على Cloudflare Workers (يُستخدم لو حجب Graph من الهاتف). */
    val apiEndpoint: String,
    val r2UploadEndpoint: String,
    val r2Token: String,
)

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        AppSettings(
            igUserId = BuildConfig.DEFAULT_IG_USER_ID,
            accessToken = BuildConfig.DEFAULT_IG_ACCESS_TOKEN,
            apiEndpoint = BuildConfig.DEFAULT_API_ENDPOINT,
            r2UploadEndpoint = prefs[R2_ENDPOINT_KEY]?.trim()
                ?: BuildConfig.DEFAULT_R2_ENDPOINT,
            r2Token = prefs[R2_TOKEN_KEY].orEmpty(),
        )
    }

    suspend fun current(): AppSettings = settings.first()
}
