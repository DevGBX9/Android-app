package com.brainrot.pigeon.data

import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * موعد نشر ريل على إنستغرام من سيرفرات Cloudflare.
 *
 * [at] هو الوقت الفعلي (epoch millis) بتوقيت الجزائر، و[state] يكتبه الـWorker:
 * scheduled / creating / publishing / published / failed.
 */
@Serializable
data class PublishSchedule(
    val at: Long = 0L,
    val timezone: String = ScheduleSlots.TIMEZONE,
    val state: String = ScheduleSlots.STATE_SCHEDULED,
    val error: String? = null,
    val attempts: Int = 0,
    val containerId: String? = null,
    val mediaId: String? = null,
    val publishedAt: Long = 0L,
)

/** الريل نُشر فعلاً على إنستغرام: يُحذف من القائمة بعدها. */
val PublishSchedule.isPublished: Boolean
    get() = state == ScheduleSlots.STATE_PUBLISHED || publishedAt > 0L

/** خطة الموعد التي تظهر في نافذة المشاركة. */
sealed interface SchedulePlan {
    /** لا يوجد أي ريل مجدول بعد: المستخدم يختار الوقت ثم التاريخ. */
    data object Manual : SchedulePlan

    /** يوجد ريل سابق: نحسب الموعد التالي تلقائياً. */
    data class Auto(val at: Long) : SchedulePlan
}

/**
 * قواعد الجدولة الثابتة: 19:00 بتوقيت الجزائر، ويومان كاملان بين كل ريلين
 * (أي النشر في اليوم الثالث بعد آخر ريل). الاختبارات في ScheduleSlotsTest.
 */
object ScheduleSlots {

    const val TIMEZONE = "Africa/Algiers"
    const val PUBLISH_HOUR = 19
    const val PUBLISH_MINUTE = 0

    /** يومان كاملان فارغان بين الريلين، ثم يُنشر الثالث. */
    const val DAYS_BETWEEN = 2

    const val STATE_SCHEDULED = "scheduled"
    const val STATE_CREATING = "creating"
    const val STATE_PUBLISHING = "publishing"
    const val STATE_PUBLISHED = "published"
    const val STATE_FAILED = "failed"

    private val zone: ZoneId = ZoneId.of(TIMEZONE)

    private val fullFormat =
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy · h:mm a", Locale.ENGLISH)
    private val shortFormat =
        DateTimeFormatter.ofPattern("d MMM · h:mm a", Locale.ENGLISH)
    private val dateFormat =
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
    // الوقت يظهر بنظام 12 ساعة مع AM/PM: 7:00 PM و 7:00 AM.
    private val timeFormat =
        DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    fun at(date: LocalDate, hour: Int, minute: Int): Long =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), zone)
            .toInstant()
            .toEpochMilli()

    fun dateOf(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    fun timeOf(epochMillis: Long): LocalTime =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()

    /**
     * الموعد التالي بعد [lastAt]: نزيد يومين كاملين على تاريخ آخر ريل، ثم
     * ننشر في اليوم الثالث الساعة 19:00 بتوقيت الجزائر. لو كان الموعد المحسوب
     * في الماضي (التطبيق ما فتح من مدة) نتقدّم بخطوات كاملة حتى موعد مستقبلي.
     */
    fun nextAfter(lastAt: Long, now: Long = System.currentTimeMillis()): Long {
        var date = dateOf(lastAt).plusDays(DAYS_BETWEEN.toLong() + 1L)
        var candidate = at(date, PUBLISH_HOUR, PUBLISH_MINUTE)
        while (candidate <= now) {
            date = date.plusDays(DAYS_BETWEEN.toLong() + 1L)
            candidate = at(date, PUBLISH_HOUR, PUBLISH_MINUTE)
        }
        return candidate
    }

    fun isFuture(at: Long, now: Long = System.currentTimeMillis()): Boolean = at > now

    fun formatFull(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(fullFormat)

    fun formatShort(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(shortFormat)

    fun formatDate(date: LocalDate): String = date.format(dateFormat)

    /** "7:00 PM" لساعة 24 داخلية (19, 0) مثلًا. */
    fun formatTime(hour24: Int, minute: Int): String =
        LocalTime.of(hour24.coerceIn(0, 23), minute.coerceIn(0, 59)).format(timeFormat)

    /** 19 → 7، و 0 → 12، و 12 → 12. */
    fun hour12(hour24: Int): Int {
        val hour = hour24.coerceIn(0, 23) % 12
        return if (hour == 0) 12 else hour
    }

    fun isPm(hour24: Int): Boolean = hour24 >= 12

    /** 7 PM → 19، و 12 AM → 0، و 12 PM → 12. */
    fun hour24(hour12: Int, pm: Boolean): Int =
        (hour12.coerceIn(1, 12) % 12) + (if (pm) 12 else 0)
}

/**
 * يحوّل عناصر مشاركة واحدة إلى عناصر مواعيدها محددة بالترتيب:
 * أول ريل يأخذ الموعد المختار، وكل ريل بعده يأخذ الموعد التالي تلقائياً.
 * الملفات المحلية (بلا رابط) تبقى بلا موعد لأن Cloudflare لا يستطيع نشرها.
 */
object SchedulePlanner {

    fun assign(
        items: List<SavedItem>,
        firstAt: Long,
        now: Long = System.currentTimeMillis(),
    ): List<SavedItem> {
        var last: Long? = null

        return items.map { item ->
            if (!SchedulePolicy.canSchedule(item)) {
                item
            } else {
                val at = last?.let { ScheduleSlots.nextAfter(it, now) } ?: firstAt
                last = at
                item.copy(schedule = PublishSchedule(at = at))
            }
        }
    }

    /**
     * تاريخ البداية للاختيار اليدوي: اليوم إذا كان الوقت المختار ما زال
     * مستقبلاً، وإلا غداً.
     */
    fun defaultManualDate(
        hour: Int,
        minute: Int,
        now: Long = System.currentTimeMillis(),
    ): LocalDate {
        val today = ScheduleSlots.dateOf(now)
        return if (ScheduleSlots.at(today, hour, minute) > now) today else today.plusDays(1)
    }
}

/**
 * روابط الريلز القابلة للأرشفة والجدولة. نستخدم نفس القواعد هنا وفي الـWorker
 * حتى ما نطلب أرشفة بروفايل إنستغرام أو رابط صوت.
 */
object SchedulePolicy {

    private val instagramPost =
        Regex("""^/(?:reel|reels|p|tv)/([A-Za-z0-9_-]{5,})""")

    fun canSchedule(item: SavedItem): Boolean = canScheduleUrl(item.url)

    fun canScheduleUrl(link: String?): Boolean {
        if (link.isNullOrBlank()) return false

        val uri = runCatching { URI(link) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        val path = uri.path.orEmpty()

        if (host == "instagram.com" || host.endsWith(".instagram.com")) {
            val code = instagramPost.find(path)?.groupValues?.getOrNull(1) ?: return false
            return !code.equals("audio", ignoreCase = true)
        }

        if (host == "tiktok.com" || host.endsWith(".tiktok.com")) {
            if (host.startsWith("vm.") || host.startsWith("vt.")) return true
            if (path.startsWith("/t/")) return true
            if (path.contains("/video/")) return true
            return Regex("""^/v/\d{6,30}""").containsMatchIn(path)
        }

        return false
    }
}
