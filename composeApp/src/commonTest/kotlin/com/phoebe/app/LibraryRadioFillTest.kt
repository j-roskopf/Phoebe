package com.phoebe.app

import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.PlexRadioStation
import com.phoebe.app.domain.PlexRadioStationCategory
import com.phoebe.app.domain.Track
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryRadioFillTest {
    @Test
    fun wholeLibraryStationMatchesNamedAndNumericLibrarySlugs() {
        assertTrue(station("/library/sections/3/stations/library").isWholeLibraryStation())
        assertTrue(station("/library/sections/3/stations/1?type=10").isWholeLibraryStation())
        assertFalse(station("/library/sections/3/stations/deepCuts").isWholeLibraryStation())
        assertFalse(station("/library/sections/3/stations/timeTravel").isWholeLibraryStation())
        assertFalse(
            station("/library/sections/3/stations/library", PlexRadioStationCategory.Artist)
                .isWholeLibraryStation(),
        )
    }

    @Test
    fun fillCandidatesCoverThePlexLibraryMinusTheSeedQueue() {
        val seed = listOf(track("plex:seed1"), track("plex:seed2"))
        val catalog = CatalogSnapshot(
            tracksByParent = mapOf(
                "plex:album1" to listOf(track("plex:seed1"), track("plex:a"), track("plex:b")),
                "plex:album2" to listOf(track("plex:b"), track("plex:c")),
                "local_1:album3" to listOf(track("local_1:d")),
                "plex:album4" to listOf(track("plex:e", streamUrl = "")),
            ),
        )

        val candidates = libraryRadioFillCandidates(catalog, seed, Random(7))

        assertEquals(listOf("plex:a", "plex:b", "plex:c").sorted(), candidates.map { it.id }.sorted())
    }

    @Test
    fun fillCandidatesAreEmptyWhenTheCatalogOnlyHoldsTheSeed() {
        val seed = listOf(track("plex:seed1"))
        val catalog = CatalogSnapshot(tracksByParent = mapOf("plex:album1" to seed))

        assertEquals(emptyList(), libraryRadioFillCandidates(catalog, seed, Random(7)))
    }

    private fun station(
        key: String,
        category: PlexRadioStationCategory = PlexRadioStationCategory.Library,
    ): PlexRadioStation =
        PlexRadioStation(
            id = "station",
            title = "Library Radio",
            subtitle = "",
            key = key,
            category = category,
        )

    private fun track(id: String, streamUrl: String = "https://plex.example/$id"): Track =
        Track(
            id = id,
            title = id,
            artist = "Artist",
            album = "Album",
            durationMs = 1_000L,
            streamUrl = streamUrl,
            downloadUrl = "",
        )
}
