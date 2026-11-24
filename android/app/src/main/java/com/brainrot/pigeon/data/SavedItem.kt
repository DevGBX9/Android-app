package com.brainrot.pigeon.data

import kotlinx.serialization.Serializable

/**
 * عنصر مجدول وصل عن طريق Share Sheet (رابط Reel/TikTok أو ملف وسائط).
 *
 * [registered] يعني أن السجل مرفوع ومحفوظ فعلاً في قائمة Firebase الخاصة
 * بالتطبيق؛ يصير true بعد نجاح الرفع أو بعد سحبه من قاعدة البيانات.
 *
 * حقول الأرشيف يكتبها Worker على Cloudflare بعد ما ينزّل الريل ويخزنه في R2:
 * [archiveState] إحدى queued / downloading / storing / ready / failed،
 * و[archiveProgress] من 0 إلى 100، و[downloadUrl] رابط الملف المخزّن.
 */
@Serializable
data class SavedItem(
    val id: String,
    val platform: String,
    val kind: String = "link",
    val url: String? = null,
    val title: String? = null,
    val filePath: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val remoteUrl: String? = null,
    /** رابط صورة غلاف الريل من إنستغرام (oEmbed). */
    val thumbnail: String? = null,
    val registered: Boolean = false,
    val archiveState: String? = null,
    val archiveProgress: Int = 0,
    val downloadUrl: String? = null,
    val createdAt: Long = 0L,
)
