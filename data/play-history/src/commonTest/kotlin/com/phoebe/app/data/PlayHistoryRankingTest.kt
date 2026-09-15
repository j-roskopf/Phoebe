package com.phoebe.app.data

import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.MostPlayedEntry
import com.phoebe.app.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayHistoryRankingTest {
    @Test
    fun topMostPlayedSongsUsesCountThenLastPlayedAndHandlesEmptyHistory() {
        val history = PlayHistorySnapshot(
            byTrack = mapOf("older" to 10L, "newer" to 20L),
            playCountByTrack = mapOf("older" to 3L, "newer" to 3L, "zero" to 0L),
        )

        assertEquals(listOf("newer", "older"), history.topMostPlayedSongs().map { it.trackId })
        assertEquals(emptyList(), PlayHistorySnapshot().topMostPlayedSongs())
    }

    @Test
    fun topMostPlayedArtistsAggregatesTracksAndResolvesIdAndArtwork() {
        val history = PlayHistorySnapshot(
            topMostPlayed = listOf(
                MostPlayedEntry("t1", 5L, 100L, "Artist One", "Album"),
                MostPlayedEntry("t2", 2L, 200L, "Artist One", "Album"),
                MostPlayedEntry("t3", 6L, 50L, "Artist Two", "Album"),
                MostPlayedEntry("t4", 6L, 60L, "Artist Three", "Album"),
            ),
        )
        val catalog = CatalogSnapshot(
            artists = listOf(
                Artist("a1", "Artist One", thumbUrl = "artist-thumb"),
                Artist("a2", "Artist Two"),
                Artist("a3", "Artist Three"),
            ),
            tracksByParent = mapOf(
                "album" to listOf(
                    track("t1", "Artist One", "track-thumb-1"),
                    track("t2", "Artist One", "track-thumb-2"),
                    track("t3", "Artist Two", null),
                    track("t4", "Artist Three", null),
                ),
            ),
        )

        val result = history.topMostPlayedArtists(catalog)

        assertEquals(listOf("Artist One", "Artist Three", "Artist Two"), result.map { it.title })
        assertEquals(listOf(7L, 6L, 6L), result.map { it.playCount })
        assertEquals("a1", result[0].id)
        assertEquals("artist-thumb", result[0].thumbUrl)
    }

    @Test
    fun topMostPlayedArtistsKeepsUnresolvedHistoryRowsWithoutCatalogId() {
        val history = PlayHistorySnapshot(
            topMostPlayed = listOf(MostPlayedEntry("missing", 4L, 9L, "Unknown Artist", "Album")),
        )

        val result = history.topMostPlayedArtists(CatalogSnapshot())

        assertEquals(1, result.size)
        assertEquals("Unknown Artist", result.single().title)
        assertNull(result.single().id)
        assertEquals(4L, result.single().playCount)
    }

    private fun track(id: String, artist: String, thumbUrl: String?) = Track(
        id = id,
        title = id,
        artist = artist,
        album = "Album",
        durationMs = 1L,
        streamUrl = "",
        downloadUrl = "",
        thumbUrl = thumbUrl,
    )
}
