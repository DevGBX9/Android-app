package com.brainrot.pigeon.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleSlotsTest {

    @Test
    fun nextSlotLeavesTwoFullDaysThenPublishesAtSeven() {
        val last = ScheduleSlots.at(LocalDate.of(2026, 11, 23), 19, 0)
        val now = ScheduleSlots.at(LocalDate.of(2026, 11, 24), 10, 0)

        val next = ScheduleSlots.nextAfter(last, now)

        assertEquals(ScheduleSlots.at(LocalDate.of(2026, 11, 26), 19, 0), next)
        assertEquals(19, ScheduleSlots.timeOf(next).hour)
        assertEquals(0, ScheduleSlots.timeOf(next).minute)
    }

    @Test
    fun nextSlotSkipsPastSlots() {
        val last = ScheduleSlots.at(LocalDate.of(2026, 1, 1), 19, 0)
        val now = ScheduleSlots.at(LocalDate.of(2026, 11, 24), 10, 0)

        val next = ScheduleSlots.nextAfter(last, now)

        assertTrue(next > now)
        assertEquals(19, ScheduleSlots.timeOf(next).hour)
        assertEquals(0, ScheduleSlots.timeOf(next).minute)
        assertEquals(
            0L,
            (ScheduleSlots.dateOf(next).toEpochDay() - ScheduleSlots.dateOf(last).toEpochDay()) % 3,
        )
    }

    @Test
    fun manualSlotUsesAlgiersTime() {
        val at = ScheduleSlots.at(LocalDate.of(2026, 11, 24), 19, 0)
        assertEquals(18, java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneOffset.UTC).hour)
    }

    @Test
    fun instagramReelsAndPostsAreSchedulable() {
        assertTrue(SchedulePolicy.canScheduleUrl("https://www.instagram.com/reel/ABC123/"))
        assertTrue(SchedulePolicy.canScheduleUrl("https://instagram.com/reels/ABC123/"))
        assertTrue(SchedulePolicy.canScheduleUrl("https://www.instagram.com/p/ABC123/"))
        assertTrue(SchedulePolicy.canScheduleUrl("https://www.instagram.com/tv/ABC123/"))
    }

    @Test
    fun instagramProfilesAndAudioAreNotSchedulable() {
        assertFalse(SchedulePolicy.canScheduleUrl("https://www.instagram.com/brainrot.pigeon/"))
        assertFalse(SchedulePolicy.canScheduleUrl("https://www.instagram.com/reels/audio/123456/"))
    }

    @Test
    fun tiktokVideosAreSchedulableButProfilesAreNot() {
        assertTrue(SchedulePolicy.canScheduleUrl("https://www.tiktok.com/@user/video/1234567890"))
        assertTrue(SchedulePolicy.canScheduleUrl("https://m.tiktok.com/v/1234567890.html"))
        assertTrue(SchedulePolicy.canScheduleUrl("https://vm.tiktok.com/ABC123/"))
        assertFalse(SchedulePolicy.canScheduleUrl("https://www.tiktok.com/@user"))
    }

    @Test
    fun plannerSpacesScheduledItemsByThreeDays() {
        val now = ScheduleSlots.at(LocalDate.of(2026, 11, 24), 10, 0)
        val first = ScheduleSlots.at(LocalDate.of(2026, 11, 24), 19, 0)
        val items = listOf(link("ABC123"), localFile(), link("DEF456"))

        val planned = SchedulePlanner.assign(items, first, now)

        assertEquals(first, planned[0].schedule?.at)
        assertNull(planned[1].schedule)
        assertEquals(
            ScheduleSlots.at(LocalDate.of(2026, 11, 27), 19, 0),
            planned[2].schedule?.at,
        )
    }

    @Test
    fun manualDateDefaultsToTomorrowWhenTodayHasPassed() {
        val now = ScheduleSlots.at(LocalDate.of(2026, 11, 24), 20, 0)

        assertEquals(
            LocalDate.of(2026, 11, 25),
            SchedulePlanner.defaultManualDate(19, 0, now),
        )
        assertEquals(
            LocalDate.of(2026, 11, 24),
            SchedulePlanner.defaultManualDate(21, 0, now),
        )
    }

    @Test
    fun localFilesAndOtherLinksAreNotSchedulable() {
        assertFalse(SchedulePolicy.canScheduleUrl(null))
        assertFalse(SchedulePolicy.canScheduleUrl("https://example.com/video/123"))
        assertFalse(SchedulePolicy.canSchedule(localFile()))
    }

    private fun link(code: String) = SavedItem(
        id = "id-$code",
        platform = "instagram",
        kind = "reel",
        url = "https://www.instagram.com/reel/$code/",
    )

    private fun localFile() = SavedItem(
        id = "file",
        platform = "video",
        kind = "file",
        filePath = "/tmp/a.mp4",
    )
}
