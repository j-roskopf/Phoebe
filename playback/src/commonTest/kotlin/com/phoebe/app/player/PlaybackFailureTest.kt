package com.phoebe.app.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackFailureTest {
    @Test
    fun connectionFailuresHoldTheQueueAndRetryOnce() {
        val failure = PlaybackFailureClassifier.fromMessage(
            "ConnectException: Connection refused",
            streamUri = "http://10.10.0.1:8096/emby/Audio/1/stream",
        )

        assertEquals(PlaybackFailureKind.Unreachable, failure.kind)
        assertTrue(failure.shouldRetry)
        assertTrue(failure.holdsQueue)
        assertTrue(failure.userMessage("Song").contains("Can't reach the music server"))
    }

    @Test
    fun sourceErrorIsTreatedAsUnreachableSoTheQueueDoesNotAdvance() {
        val failure = PlaybackFailureClassifier.fromMedia3(
            errorCode = PlaybackFailureClassifier.Media3IoUnspecified,
            message = "Source error",
            streamUri = "https://music.example/stream",
        )

        assertEquals(PlaybackFailureKind.Unreachable, failure.kind)
        assertTrue(failure.holdsQueue)
        assertTrue(failure.shouldRetry)
    }

    @Test
    fun unauthorizedStreamIsNotRetriedAndDoesNotSkip() {
        val failure = PlaybackFailureClassifier.fromMessage(
            "Plex stream request failed (401)",
            streamUri = "https://plex.example/library/parts/1?X-Plex-Token=secret",
        )

        assertEquals(PlaybackFailureKind.Unauthorized, failure.kind)
        assertFalse(failure.shouldRetry)
        assertTrue(failure.holdsQueue)
        assertEquals(401, failure.statusCode)
    }

    @Test
    fun htmlInsteadOfAudioIsAServerProblemNotABadTrack() {
        val remote = PlaybackFailureClassifier.fromMessage(
            "Unrecognized file signature!",
            streamUri = "https://emby.example/Audio/1/stream",
        )
        val local = PlaybackFailureClassifier.fromMessage(
            "Unrecognized file signature!",
            streamUri = "file:///music/track.flac",
        )

        assertEquals(PlaybackFailureKind.NotAudio, remote.kind)
        assertTrue(remote.holdsQueue)
        assertFalse(remote.shouldRetry)
        assertEquals(PlaybackFailureKind.Unsupported, local.kind)
        assertFalse(local.holdsQueue)
    }

    @Test
    fun media3HttpStatusCodesMapToAuthAndNotFound() {
        val unauthorized = PlaybackFailureClassifier.fromMedia3(
            errorCode = PlaybackFailureClassifier.Media3IoBadHttpStatus,
            message = "Response code: 401",
            httpStatus = 401,
            streamUri = "https://plex.example/library/parts/1",
        )
        val missing = PlaybackFailureClassifier.fromMedia3(
            errorCode = PlaybackFailureClassifier.Media3IoBadHttpStatus,
            message = "Response code: 404",
            httpStatus = 404,
            streamUri = "https://plex.example/library/parts/missing",
        )

        assertEquals(PlaybackFailureKind.Unauthorized, unauthorized.kind)
        assertTrue(unauthorized.holdsQueue)
        assertEquals(PlaybackFailureKind.NotFound, missing.kind)
        assertFalse(missing.holdsQueue)
    }

    @Test
    fun plexTranscodeBadRequestIsNotTreatedAsUnauthorized() {
        val failure = PlaybackFailureClassifier.fromMedia3(
            errorCode = PlaybackFailureClassifier.Media3IoBadHttpStatus,
            message = "Source error status=400",
            httpStatus = 400,
            streamUri = "https://plex.example/music/:/transcode/universal/start.mp3",
        )

        assertEquals(PlaybackFailureKind.Unknown, failure.kind)
        assertEquals(400, failure.statusCode)
        assertEquals("Couldn't play Song.", failure.userMessage("Song"))
        assertFalse(failure.shouldRetry)
        assertTrue(failure.holdsQueue)
    }

    @Test
    fun bufferingTimeoutIsUnreachable() {
        val failure = PlaybackFailureClassifier.fromMessage("playback timed out while buffering")

        assertEquals(PlaybackFailureKind.Unreachable, failure.kind)
        assertTrue(failure.shouldRetry)
        assertTrue(failure.holdsQueue)
    }

    @Test
    fun streamUrisAreLoggedWithoutQuerySecrets() {
        val failure = PlaybackFailure(
            kind = PlaybackFailureKind.Unauthorized,
            message = "Plex stream request failed (401)",
            statusCode = 401,
            streamUri = "https://plex.example/library/parts/1?X-Plex-Token=s3cret",
        )

        val line = failure.logLine()
        assertTrue(line.contains("uri=https://plex.example/library/parts/1"))
        assertFalse(line.contains("s3cret"))
        assertFalse(line.contains("X-Plex-Token"))
    }

    @Test
    fun exceptionTextIsLoggedWithoutQuerySecrets() {
        val failure = PlaybackFailure(
            kind = PlaybackFailureKind.Unauthorized,
            message = "HttpDataSourceException: https://plex.example/library/parts/1?X-Plex-Token=s3cret",
            statusCode = 401,
            streamUri = "https://plex.example/library/parts/1?X-Plex-Token=s3cret",
            cause = "InvalidResponseCodeException: https://plex.example/library/parts/1?X-Plex-Token=s3cret",
        )

        val line = failure.logLine()
        assertTrue(line.contains("https://plex.example/library/parts/1"))
        assertFalse(line.contains("s3cret"))
        assertFalse(line.contains("X-Plex-Token"))
        assertFalse(line.contains("?"))
    }

    @Test
    fun gatewayAndRateLimitStatusesAreInfrastructureFailures() {
        val gateway = PlaybackFailureClassifier.fromMessage(
            "Plex stream request failed (504)",
            streamUri = "https://plex.example/library/parts/1",
        )
        val rateLimited = PlaybackFailureClassifier.fromMessage(
            "Plex stream request failed (429)",
            streamUri = "https://plex.example/library/parts/1",
        )

        assertEquals(PlaybackFailureKind.Transient, gateway.kind)
        assertEquals(504, gateway.statusCode)
        assertTrue(gateway.isInfrastructureFailure)
        assertEquals(PlaybackFailureKind.Transient, rateLimited.kind)
        assertEquals(429, rateLimited.statusCode)
        assertTrue(rateLimited.isInfrastructureFailure)
    }

    @Test
    fun localSourceErrorIsNotTreatedAsAnUnreachableServer() {
        val failure = PlaybackFailureClassifier.fromMedia3(
            errorCode = PlaybackFailureClassifier.Media3IoUnspecified,
            message = "Source error",
            streamUri = "file:///music/track.flac",
        )

        assertEquals(PlaybackFailureKind.Unknown, failure.kind)
        assertFalse(failure.isInfrastructureFailure)
        assertFalse(failure.shouldRetry)
    }

    @Test
    fun javaFxCouldNotCreatePlayerStaysACodecProblem() {
        val failure = PlaybackFailureClassifier.fromMessage(
            "com.sun.media.jfxmedia.MediaException: Could not create player!",
            streamUri = "https://music.example/track.m4a",
        )

        assertEquals(PlaybackFailureKind.Unsupported, failure.kind)
        assertFalse(failure.holdsQueue)
        assertTrue(failure.shouldTryAlternateEngine)
    }

    @Test
    fun remoteJavaFxStartupTimeoutsFallBackToAnotherEngineWithoutSkipping() {
        val timeout = PlaybackFailureClassifier.fromMessage(
            "JavaFX media did not become ready in 3000ms",
            streamUri = "https://music.example/Audio/1/stream",
        )
        val playingTimeout = PlaybackFailureClassifier.fromMessage(
            "JavaFX media ready but never started playing",
            streamUri = "http://10.10.0.1:8096/emby/Audio/1/stream",
        )
        val runtimeTimeout = PlaybackFailureClassifier.fromMessage(
            "JavaFX runtime did not become ready in 15000ms",
            streamUri = "file:///music/song.mp3",
        )

        assertEquals(PlaybackFailureKind.Unreachable, timeout.kind)
        assertEquals(PlaybackFailureKind.Unreachable, playingTimeout.kind)
        assertTrue(timeout.holdsQueue)
        assertTrue(timeout.shouldTryAlternateEngine)
        assertTrue(playingTimeout.shouldTryAlternateEngine)
        assertTrue(timeout.isPlayerEngineTimeout)
        assertTrue(timeout.isInfrastructureFailure)
        assertTrue(runtimeTimeout.isPlayerEngineTimeout)
        assertTrue(runtimeTimeout.shouldTryAlternateEngine)
    }

    @Test
    fun overallPlaybackStartupWatchdogStillStopsWithoutTryingAnotherEngine() {
        val failure = PlaybackFailureClassifier.fromMessage(
            "Playback took too long to start.",
            streamUri = "https://music.example/Audio/1/stream",
        )

        assertEquals(PlaybackFailureKind.Unreachable, failure.kind)
        assertTrue(failure.holdsQueue)
        assertFalse(failure.shouldTryAlternateEngine)
        assertFalse(failure.isPlayerEngineTimeout)
    }

    @Test
    fun htmlAndUnauthorizedFailuresDoNotFallThroughToAnotherEngine() {
        val html = PlaybackFailureClassifier.fromMessage(
            "Unrecognized file signature!",
            streamUri = "https://emby.example/Audio/1/stream",
        )
        val unauthorized = PlaybackFailureClassifier.fromMessage(
            "Plex stream request failed (401)",
            streamUri = "https://plex.example/library/parts/1",
        )

        assertFalse(html.shouldTryAlternateEngine)
        assertTrue(html.isInfrastructureFailure)
        assertFalse(unauthorized.shouldTryAlternateEngine)
        assertTrue(unauthorized.isInfrastructureFailure)
    }

    @Test
    fun javaFxUnavailableMediaIsUnreachable() {
        val failure = PlaybackFailureClassifier.fromSignals(
            texts = listOf("MediaException", "MEDIA_UNAVAILABLE", "Connection refused"),
            streamUri = "https://music.example/stream",
        )

        assertEquals(PlaybackFailureKind.Unreachable, failure.kind)
        assertTrue(failure.isInfrastructureFailure)
        assertFalse(failure.shouldTryAlternateEngine)
    }
}
