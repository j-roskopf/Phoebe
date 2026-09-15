package com.phoebe.app.feature.history

import com.phoebe.app.domain.MostPlayedEntry
import com.phoebe.app.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChartsUiStateTest {
    @Test
    fun defaultStateIsLoadingAndNotEmpty() {
        assertTrue(ChartsUiState.Loading.isLoading)
        assertFalse(ChartsUiState.Loading.isEmpty)
    }

    @Test
    fun stateWithOnlyOneListLoadedIsStillLoading() {
        val state = ChartsUiState(topArtists = emptyList(), topSongs = null)
        assertTrue(state.isLoading)
        assertFalse(state.isEmpty)
    }

    @Test
    fun emptyBothListsIsEmptyOnceLoaded() {
        val state = ChartsUiState(topArtists = emptyList(), topSongs = emptyList())
        assertFalse(state.isLoading)
        assertTrue(state.isEmpty)
    }

    @Test
    fun sampleStateIsNeitherLoadingNorEmpty() {
        val state = sampleChartsUiState()
        assertFalse(state.isLoading)
        assertFalse(state.isEmpty)
        assertTrue(state.topArtists!!.isNotEmpty())
        assertTrue(state.topSongs!!.isNotEmpty())
    }

    @Test
    fun buildChartsSongRanksCollapsesEquivalentRecordings() {
        val entries = listOf(
            MostPlayedEntry("local:1", 7L, 300L, "Luna North", "Moonlit Signals"),
            MostPlayedEntry("plex:1", 4L, 200L, "Luna North", "Moonlit Signals"),
            MostPlayedEntry("plex:2", 2L, 100L, "Echo Harbor", "Harbor Static"),
        )
        val tracks = mapOf(
            "local:1" to track("local:1", "Moonlit Signals", "Luna North"),
            "plex:1" to track("plex:1", "Moonlit Signals", "Luna North"),
            "plex:2" to track("plex:2", "Harbor Static", "Echo Harbor"),
        )

        val ranks = buildChartsSongRanks(entries) { tracks[it] }

        assertEquals(listOf("local:1", "plex:2"), ranks.map { it.id })
        assertEquals(listOf(7L, 2L), ranks.map { it.playCount })
        assertEquals(listOf("Moonlit Signals", "Harbor Static"), ranks.map { it.title })
    }

    @Test
    fun buildChartsSongRanksFallsBackToEntryMetadataWhenUnresolved() {
        val entries = listOf(MostPlayedEntry("missing", 3L, 10L, "Unknown Artist", "Some Album"))

        val ranks = buildChartsSongRanks(entries) { null }

        assertEquals(1, ranks.size)
        assertEquals("missing", ranks.single().title)
        assertEquals("Unknown Artist", ranks.single().artist)
        assertEquals("Some Album", ranks.single().album)
    }

    private fun track(id: String, title: String, artist: String) = Track(
        id = id,
        title = title,
        artist = artist,
        album = "Album",
        durationMs = 200_000L,
        streamUrl = "",
        downloadUrl = "",
    )
}
