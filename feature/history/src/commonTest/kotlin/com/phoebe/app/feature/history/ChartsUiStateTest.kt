package com.phoebe.app.feature.history

import kotlin.test.Test
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
}
