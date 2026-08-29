package com.phoebe.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverArtCachePathTest {
    @Test
    fun pathsLiveUnderCoverart() {
        assertTrue(coverArtCachePath("https://plex.example/art.jpg").startsWith("coverart/"))
    }

    @Test
    fun sameUrlGivesSamePath() {
        val a = coverArtCachePath("https://plex.example/art.jpg?token=abc")
        val b = coverArtCachePath("https://plex.example/art.jpg?token=abc")
        assertEquals(a, b)
    }

    @Test
    fun differentUrlsGiveDifferentPaths() {
        val a = coverArtCachePath("https://plex.example/one.jpg")
        val b = coverArtCachePath("https://plex.example/two.jpg")
        assertTrue(a != b)
    }

    @Test
    fun everyEntryIsJpgRegardlessOfSourceExtension() {
        // Entries are re-encoded to JPEG when cached, so the source extension is
        // irrelevant and the name should not imply otherwise.
        assertTrue(coverArtCachePath("https://plex.example/art.png?token=abc").endsWith(".jpg"))
        assertTrue(coverArtCachePath("https://plex.example/art.webp").endsWith(".jpg"))
        assertTrue(coverArtCachePath("https://plex.example/library/metadata/1/thumb/17772").endsWith(".jpg"))
    }

    @Test
    fun pathContainsNoQueryOrSeparators() {
        val path = coverArtCachePath("https://plex.example/a/b/art.jpg?token=abc&x=1")
        val name = path.removePrefix("coverart/")
        assertTrue(name.none { it == '/' || it == '?' || it == '&' })
    }
}
