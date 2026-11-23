package com.brainrot.pigeon.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareParserTest {

    @Test
    fun instagramReelIsNormalised() {
        val target = ShareParser.classify("https://www.instagram.com/reel/C8xYz12/?igsh=abc")

        assertNotNull(target)
        assertEquals(ShareParser.PLATFORM_INSTAGRAM, target?.platform)
        assertEquals("reel", target?.kind)
        assertEquals("https://www.instagram.com/reel/C8xYz12", target?.url)
        assertTrue(target?.title?.contains("C8xYz12") == true)
    }

    @Test
    fun instagramPostKeepsPostPath() {
        val target = ShareParser.classify("https://instagram.com/p/AbC123/")

        assertEquals(ShareParser.PLATFORM_INSTAGRAM, target?.platform)
        assertEquals("post", target?.kind)
        assertEquals("https://www.instagram.com/p/AbC123", target?.url)
    }

    @Test
    fun tiktokVideoIsRecognised() {
        val target = ShareParser.classify(
            "https://www.tiktok.com/@pigeon/video/7391234567890123456?is_from_webapp=1",
        )

        assertEquals(ShareParser.PLATFORM_TIKTOK, target?.platform)
        assertEquals("video", target?.kind)
        assertEquals(
            "https://www.tiktok.com/@pigeon/video/7391234567890123456",
            target?.url,
        )
    }

    @Test
    fun tiktokShortLinkIsRecognised() {
        val target = ShareParser.classify("https://vm.tiktok.com/ZMabc123/")

        assertEquals(ShareParser.PLATFORM_TIKTOK, target?.platform)
        assertEquals("short", target?.kind)
        assertEquals("https://vm.tiktok.com/ZMabc123/", target?.url)
    }

    @Test
    fun textShareFindsEmbeddedUrl() {
        val target = ShareParser.fromText("شوف هذا الريل https://www.instagram.com/reel/Abc123/ 😂")

        assertEquals(ShareParser.PLATFORM_INSTAGRAM, target?.platform)
    }

    @Test
    fun unknownLinkFallsBackToGenericLink() {
        val target = ShareParser.classify("https://example.com/watch?v=1")

        assertEquals(ShareParser.PLATFORM_LINK, target?.platform)
        assertEquals("example.com", target?.title)
    }

    @Test
    fun textWithoutUrlReturnsNull() {
        assertNull(ShareParser.fromText("ما في رابط هون"))
        assertNull(ShareParser.classify("not a url"))
    }
}
