package com.brainrot.pigeon.data

import kotlinx.serialization.Serializable

/**
 * عنصر محفوظ وصل عن طريق Share Sheet (رابط Reel/TikTok أو ملف وسائط).
 * إذا كان ملفاً ننسخه إلى مساحة التطبيق الخاصة لأن صلاحية الـ Uri من
 * التطبيق المُرسل مؤقتة وتنتهي بعد إنهاء العملية.
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
    val createdAt: Long = 0L,
)
