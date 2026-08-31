package com.phoebe.app.ui

import coil3.compose.AsyncImagePainter
import kotlin.test.Test
import kotlin.test.assertEquals

class CoilArtworkVisualStateTest {
    @Test
    fun emptyPainterStateShowsLoadingWhenNothingHasRendered() {
        assertEquals(
            RemoteArtworkVisualState.Loading,
            resolveCoilArtworkVisualState(
                painterState = AsyncImagePainter.State.Empty,
                hasCandidate = true,
                exhaustedCandidates = true,
            ),
        )
    }

    @Test
    fun retainedPainterStaysVisibleWhileReplacementPainterIsEmpty() {
        assertEquals(
            RemoteArtworkVisualState.Image,
            resolveCoilArtworkVisualState(
                painterState = AsyncImagePainter.State.Empty,
                hasCandidate = true,
                exhaustedCandidates = true,
                hasRetainedPainter = true,
            ),
        )
    }

    @Test
    fun startupFallbackStaysVisibleUntilOriginPainterCanDraw() {
        val waitingForOrigin = resolveCoilArtworkVisualState(
            painterState = AsyncImagePainter.State.Empty,
            hasCandidate = false,
            exhaustedCandidates = true,
            retainFallbackWhileLoading = true,
            waitingForLiveOrigin = true,
        )
        val originReadyButPainterEmpty = resolveCoilArtworkVisualState(
            painterState = AsyncImagePainter.State.Empty,
            hasCandidate = true,
            exhaustedCandidates = true,
            retainFallbackWhileLoading = true,
        )
        val originPainterLoading = resolveCoilArtworkVisualState(
            painterState = AsyncImagePainter.State.Loading(painter = null),
            hasCandidate = true,
            exhaustedCandidates = true,
            retainFallbackWhileLoading = true,
        )

        assertEquals(RemoteArtworkVisualState.Missing, waitingForOrigin)
        assertEquals(RemoteArtworkVisualState.Missing, originReadyButPainterEmpty)
        assertEquals(RemoteArtworkVisualState.Missing, originPainterLoading)
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
