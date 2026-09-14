package com.phoebe.app.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MprisMetadataTest {
    private fun snapshot(
        trackId: String = "track-42",
        title: String = "Blue Monday",
        artist: String = "New Order",
        album: String = "Power, Corruption & Lies",
        artworkUrl: String = "https://plex.example/art.jpg",
        positionBucketMs: Long = 90L,
        durationMs: Long = 450_000L,
        playing: Boolean = true,
    ) = NowPlayingSnapshot(
        trackId = trackId,
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        positionBucketMs = positionBucketMs,
        durationMs = durationMs,
        playing = playing,
    )

    @Test
    fun playbackStatusUsesSpecCasing() {
        assertEquals("Playing", MprisMetadata.playbackStatus(playing = true))
        assertEquals("Paused", MprisMetadata.playbackStatus(playing = false))
    }

    @Test
    fun lengthConvertsMillisecondsToMicroseconds() {
        assertEquals(450_000_000L, MprisMetadata.lengthMicros(450_000L))
        assertEquals(0L, MprisMetadata.lengthMicros(0L))
    }

    @Test
    fun positionConvertsSecondsToMicroseconds() {
        // positionBucketMs holds seconds despite its name.
        assertEquals(90_000_000L, MprisMetadata.positionMicros(90L))
        assertEquals(0L, MprisMetadata.positionMicros(0L))
    }

    @Test
    fun trackIdPathIsAValidObjectPath() {
        val path = MprisMetadata.trackIdPath("track-42")
        assertTrue(path.startsWith("/com/phoebe/app/track/"))
        assertTrue(path.all { it.isLetterOrDigit() || it == '/' || it == '_' })
    }

    @Test
    fun trackIdPathSanitizesCharactersIllegalInObjectPaths() {
        val path = MprisMetadata.trackIdPath("plex://library/metadata/1234")
        assertFalse(path.contains(':'))
        assertFalse(path.contains('-'))
        assertTrue(path.all { it.isLetterOrDigit() || it == '/' || it == '_' })
    }

    @Test
    fun trackIdPathFallsBackWhenTrackIdIsBlank() {
        assertEquals("/com/phoebe/app/track/NoTrack", MprisMetadata.trackIdPath(""))
    }

    @Test
    fun metadataMapsEveryPopulatedField() {
        val m = MprisMetadata.metadata(snapshot())
        assertEquals("/com/phoebe/app/track/track_42", m["mpris:trackid"])
        assertEquals(450_000_000L, m["mpris:length"])
        assertEquals("https://plex.example/art.jpg", m["mpris:artUrl"])
        assertEquals("Blue Monday", m["xesam:title"])
        assertEquals(listOf("New Order"), m["xesam:artist"])
        assertEquals("Power, Corruption & Lies", m["xesam:album"])
    }

    @Test
    fun metadataOmitsBlankOptionalFields() {
        val m = MprisMetadata.metadata(snapshot(artworkUrl = "", album = "", artist = ""))
        assertFalse(m.containsKey("mpris:artUrl"))
        assertFalse(m.containsKey("xesam:album"))
        assertFalse(m.containsKey("xesam:artist"))
        // trackid and title are always present so clients always have something to show.
        assertTrue(m.containsKey("mpris:trackid"))
        assertTrue(m.containsKey("xesam:title"))
    }

    @Test
    fun metadataOmitsLengthWhenDurationUnknown() {
        val m = MprisMetadata.metadata(snapshot(durationMs = 0L))
        assertFalse(m.containsKey("mpris:length"))
    }
}
