package com.brainrot.pigeon.data

import android.content.Context
import com.brainrot.pigeon.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * التطبيق شخصي: توكن إنستغرام وUser ID مدمجان في البناء ولا يمكن تعديلهما
 * من داخل التطبيق. لا توجد شاشة إعدادات.
 */
data class AppSettings(
    val igUserId: String,
    val accessToken: String,
    /** بروكسي API خارجي (Cloudflare Worker) يُستخدم لو حجب Graph من الهاتف. */
    val apiEndpoint: String,
)

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsStore.data.map {
        AppSettings(
            igUserId = BuildConfig.DEFAULT_IG_USER_ID,
            accessToken = BuildConfig.DEFAULT_IG_ACCESS_TOKEN,
            apiEndpoint = BuildConfig.DEFAULT_API_ENDPOINT,
        )
    }

    suspend fun current(): AppSettings = settings.first()
}
