package com.brainrot.pigeon.data

import kotlinx.serialization.Serializable

const val FALLBACK_USERNAME = "brainrot.pigeon"
const val FALLBACK_BIO = "I'm a pigeon"
const val NOTE_TEXT = "Can't decide..."
const val PROFILE_PAGE_URL = "https://www.instagram.com/brainrot.pigeon/"

/**
 * نفس شكل البيانات اللي يرجعها بروكسي Cloudflare Worker، مع وقت آخر تحديث
 * حتى نعرف نعرض نسخة الكاش عند فتح التطبيق بدون انتظار الشبكة.
 *
 * لا توجد أرقام افتراضية إطلاقاً: كل عدّاد `null` حتى تصل قيمته الحقيقية من
 * إنستغرام، والواجهة تعرض Skeleton أثناء الجلب.
 */
@Serializable
data class Profile(
    val username: String = FALLBACK_USERNAME,
    val bio: String = FALLBACK_BIO,
    val posts: Int? = null,
    val followers: Int? = null,
    val following: Int? = null,
    val avatarUrl: String = "",
    val fetchedAt: Long = 0L,
)
