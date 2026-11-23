package com.brainrot.pigeon

import android.content.Context
import com.brainrot.pigeon.data.AvatarLoader
import com.brainrot.pigeon.data.ProfileRepository
import com.brainrot.pigeon.data.R2Uploader
import com.brainrot.pigeon.data.SavedRepository
import com.brainrot.pigeon.data.SettingsRepository
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * تجميع التبعيات في مكان واحد. أي شاشة/ViewModel تاخذ ما تحتاجه من هنا فقط،
 * فتبقى الطبقات قابلة للاختبار والاستبدال.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(9, TimeUnit.SECONDS)
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .build()

    val settings = SettingsRepository(appContext)
    val saved = SavedRepository(appContext)
    val avatars = AvatarLoader(httpClient)
    val profile = ProfileRepository(appContext, settings, httpClient)
    val uploader = R2Uploader(httpClient)
}
