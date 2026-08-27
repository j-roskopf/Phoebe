package com.phoebe.app.ui

import coil3.compose.AsyncImagePainter
import kotlin.test.Test
import kotlin.test.assertEquals

class CoilArtworkVisualStateTest {
    @Test
    fun emptyPainterStateDoesNotShowLoadingOverlayWhenCandidateExists() {
        assertEquals(
            RemoteArtworkVisualState.Image,
            resolveCoilArtworkVisualState(
                painterState = AsyncImagePainter.State.Empty,
                hasCandidate = true,
                exhaustedCandidates = true,
            ),
        )
    }

    @Test
    fun loadingWithoutPainterShowsLoadingOverlay() {
        assertEquals(
            RemoteArtworkVisualState.Loading,
            resolveCoilArtworkVisualState(
                painterState = AsyncImagePainter.State.Loading(painter = null),
                hasCandidate = true,
                exhaustedCandidates = true,
            ),
        )
    }

    @Test
    fun missingCandidateShowsMissingState() {
        assertEquals(
            RemoteArtworkVisualState.Missing,
            resolveCoilArtworkVisualState(
                painterState = AsyncImagePainter.State.Empty,
                hasCandidate = false,
                exhaustedCandidates = true,
            ),
        )
    }
}
