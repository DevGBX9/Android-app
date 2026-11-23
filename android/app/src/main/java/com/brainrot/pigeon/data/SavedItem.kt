package com.brainrot.pigeon.data

import kotlinx.serialization.Serializable

/**
 * عنصر مجدول وصل عن طريق Share Sheet (رابط Reel/TikTok أو ملف وسائط).
 *
 * [registered] يعني أن السجل مرفوع ومحفوظ فعلاً في قائمة Firebase الخاصة
 * بالتطبيق؛ يصير true بعد نجاح الرفع أو بعد سحبه من قاعدة البيانات.
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
    val registered: Boolean = false,
    val createdAt: Long = 0L,
)
