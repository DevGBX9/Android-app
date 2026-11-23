package com.brainrot.pigeon.data

import kotlinx.serialization.Serializable

const val FALLBACK_USERNAME = "brainrot.pigeon"
const val FALLBACK_BIO = "I'm a pigeon"
const val FALLBACK_POSTS = 48
const val FALLBACK_FOLLOWERS = 3099
const val FALLBACK_FOLLOWING = 5
const val NOTE_TEXT = "Can't decide..."
const val PROFILE_PAGE_URL = "https://www.instagram.com/brainrot.pigeon/"

/**
 * نفس شكل البيانات اللي يرجعها بروكسي Cloudflare Worker، مع وقت آخر تحديث
 * حتى نعرف نعرض نسخة الكاش عند فتح التطبيق بدون انتظار الشبكة.
 */
@Serializable
data class Profile(
    val username: String = FALLBACK_USERNAME,
    val bio: String = FALLBACK_BIO,
    val posts: Int? = FALLBACK_POSTS,
    val followers: Int? = FALLBACK_FOLLOWERS,
    val following: Int? = FALLBACK_FOLLOWING,
    val avatarUrl: String = "",
    val fetchedAt: Long = 0L,
)
