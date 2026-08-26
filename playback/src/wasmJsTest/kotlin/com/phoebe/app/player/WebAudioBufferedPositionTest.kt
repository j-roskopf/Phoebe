package com.phoebe.app.player

import kotlin.test.Test
import kotlin.test.assertEquals

class WebAudioBufferedPositionTest {

    @Test
    fun knownDurationWinsOverBrowserCorrections() {
        assertEquals(
            287_000L,
            webAudioPlaybackDurationMs(
                currentDurationMs = 287_000L,
                browserDurationSeconds = 286.5,
            ),
        )

        assertEquals(
            287_000L,
            webAudioPlaybackDurationMs(
                currentDurationMs = 287_000L,
                browserDurationSeconds = 288.5,
            ),
        )
    }

    @Test
    fun browserDurationInitializesMissingDuration() {
        assertEquals(
            286_250L,
            webAudioPlaybackDurationMs(
                currentDurationMs = 0L,
                browserDurationSeconds = 286.25,
            ),
        )

        assertEquals(
            0L,
            webAudioPlaybackDurationMs(
                currentDurationMs = 0L,
                browserDurationSeconds = Double.POSITIVE_INFINITY,
            ),
        )
    }

    @Test
    fun remoteStreamsUseAutoPreloadForActiveTrack() {
        assertEquals("auto", webAudioPreloadForUri("https://plex.example/track.mp3"))
        assertEquals("none", webAudioPreloadForUri("https://plex.example/track.mp3", activeTrack = false))
        assertEquals("metadata", webAudioPreloadForUri("blob:https://music.example/local-file"))
        assertEquals("metadata", webAudioPreloadForUri("phoebe-test://music/alpha.mp3"))
    }

    @Test
    fun remoteUriDetectionOnlyTreatsHttpStreamsAsRemote() {
        assertEquals(true, "https://music.example/track.mp3".isRemoteWebAudioUri())
        assertEquals(true, "http://music.example/track.mp3".isRemoteWebAudioUri())
        assertEquals(false, "blob:https://music.example/local-file".isRemoteWebAudioUri())
        assertEquals(false, "phoebe-test://music/alpha.mp3".isRemoteWebAudioUri())
    }

    @Test
    fun seekableRangeDoesNotPretendTrackIsBuffered() {
        val buffered = listOf(WebAudioTimeRange(startMs = 0L, endMs = 65_000L))

        assertEquals(
            65_000L,
            webAudioBufferedPositionMs(
                positionMs = 5_000L,
                durationMs = 287_000L,
                bufferedRanges = buffered,
            ),
        )
    }

    @Test
    fun prefetchProgressCanAdvanceBeyondBrowserBufferWindow() {
        assertEquals(
            180_000L,
            webAudioBufferedPositionMs(
                positionMs = 5_000L,
                durationMs = 287_000L,
                bufferedRanges = listOf(WebAudioTimeRange(startMs = 0L, endMs = 65_000L)),
                prefetchedPositionMs = 180_000L,
            ),
        )
    }

    @Test
    fun disconnectedFutureRangesDoNotAdvanceFromCurrentPosition() {
        assertEquals(
            5_000L,
            webAudioBufferedPositionMs(
                positionMs = 5_000L,
                durationMs = 287_000L,
                bufferedRanges = listOf(WebAudioTimeRange(startMs = 90_000L, endMs = 120_000L)),
            ),
        )
    }

    @Test
    fun nearEndRangeSnapsToDuration() {
        assertEquals(
            287_000L,
            webAudioBufferedPositionMs(
                positionMs = 250_000L,
                durationMs = 287_000L,
                bufferedRanges = listOf(WebAudioTimeRange(startMs = 0L, endMs = 286_500L)),
            ),
        )
    }
}
