package com.brainrot.pigeon

import android.content.Context
import com.brainrot.pigeon.data.AvatarLoader
import com.brainrot.pigeon.data.FirebaseReels
import com.brainrot.pigeon.data.ProfileRepository
import com.brainrot.pigeon.data.ReelArchive
import com.brainrot.pigeon.data.ReelThumbnails
import com.brainrot.pigeon.data.SavedRepository
import com.brainrot.pigeon.data.SettingsRepository
import com.brainrot.pigeon.data.TikTokMedia
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    // أعمال خلفية تعيش مع التطبيق: رفع الريلز بعد المشاركة لا يُلغى بإغلاق الشاشة.
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings = SettingsRepository(appContext)
    val reels = FirebaseReels(httpClient, BuildConfig.DEFAULT_FIREBASE_DB)
    val thumbnails = ReelThumbnails(httpClient)
    // أرشيف الريلز على Cloudflare (Worker + R2): طلب الأرشفة والتحميل للجهاز.
    val archive = ReelArchive(
        context = appContext,
        baseClient = httpClient,
        endpoint = BuildConfig.DEFAULT_ARCHIVE_ENDPOINT,
        token = BuildConfig.DEFAULT_ARCHIVE_TOKEN,
    )
    // حل روابط تيك توك من شبكة الهاتف: Cloudflare محجوب عن صفحات تيك توك.
    val tiktokMedia = TikTokMedia(httpClient)
    val saved = SavedRepository(appContext, reels, thumbnails, archive, tiktokMedia, workScope)
    val avatars = AvatarLoader(httpClient)
    // صور أغلفة الريلز في الشبكة: كاش أكبر لأن الشبكة تعرض عشرات الصور.
    val reelImages = AvatarLoader(httpClient, cacheSize = 90)
    val profile = ProfileRepository(appContext, settings, httpClient)
}
