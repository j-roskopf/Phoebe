package com.phoebe.app

import com.phoebe.app.domain.AudioProcessingSettings
import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.player.PlaybackFailure
import com.phoebe.app.player.PlaybackFailureClassifier
import com.phoebe.app.player.SimpleAudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerStateTest {
    @Test
    fun playAndToggleUpdatesSharedState() {
        val player = TestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 1)

        assertEquals(tracks[1], player.state.value.currentTrack)
        assertTrue(player.state.value.isPlaying)
        assertEquals(tracks[1].streamUrl, player.lastUri)

        player.togglePlayPause()
        assertFalse(player.state.value.isPlaying)
    }

    @Test
    fun supersededPlayRequestDoesNotStartStaleTrack() {
        val player = SlowTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
            Track("t3", "Three", "Artist", "Album", 120_000, "http://c", ""),
        )

        player.play(tracks, 0)
        player.play(tracks, 2)

        assertEquals(tracks[2], player.state.value.currentTrack)
        assertFalse(player.state.value.isPlaying)

        player.finishLoad(1)
        assertFalse(player.state.value.isPlaying)

        player.finishLoad(2)
        assertTrue(player.state.value.isPlaying)
    }

    @Test
    fun pauseDuringBufferingCancelsAutoplay() {
        val player = SlowTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
        )

        player.play(tracks, 0)
        assertTrue(player.state.value.isBuffering)
        assertFalse(player.state.value.isPlaying)

        player.togglePlayPause()
        assertFalse(player.state.value.isBuffering)
        assertFalse(player.state.value.isPlaying)

        player.finishPendingLoad()

        assertFalse(player.state.value.isPlaying)
    }

    @Test
    fun stalledPlatformStartupFailsInsteadOfBufferingForever() = runTest {
        val player = TimeoutTestPlayer(this)
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
        )

        player.play(tracks, 0)

        assertTrue(player.state.value.isBuffering)
        advanceTimeBy(player.testStartupTimeoutMs + 1L)
        runCurrent()
        assertFalse(player.state.value.isPlaying)
        assertEquals(1, player.state.value.playbackErrorSerial)
        assertEquals(
            "Can't reach the music server. Check your connection and try again.",
            player.state.value.playbackErrorMessage,
        )
        assertFalse(player.playIntentActive())
    }

    @Test
    fun stalledPlatformStartupFailsoverToTheNextStreamOrigin() = runTest {
        val player = TimeoutTestPlayer(this)
        val liveUri = "https://live.example/library/parts/1/file.mp3"
        val tracks = listOf(
            Track(
                id = "t1",
                title = "One",
                artist = "Artist",
                album = "Album",
                durationMs = 60_000,
                streamUrl = "https://dead.example/library/parts/1/file.mp3",
                downloadUrl = "",
                playbackFallbackUrls = listOf(liveUri),
            ),
        )

        player.play(tracks, 0)
        advanceTimeBy(player.testStartupTimeoutMs + 1L)
        runCurrent()

        assertEquals(liveUri, player.state.value.currentTrack?.streamUrl)
        assertEquals(0, player.state.value.playbackErrorSerial)
        assertTrue(player.state.value.isBuffering)

        player.finishPendingLoad()

        assertTrue(player.state.value.isPlaying)
        assertNull(player.state.value.playbackErrorMessage)
    }

    @Test
    fun failoverRebasesLaterQueueTracksOntoTheWorkingOrigin() = runTest {
        val player = TimeoutTestPlayer(this)
        val lanOne = "https://172-16-1-2.abc.plex.direct:32400/library/parts/1/file.mp3"
        val remoteOne = "https://173-230-133-75.abc.plex.direct:8443/library/parts/1/file.mp3"
        val lanTwo = "https://172-16-1-2.abc.plex.direct:32400/library/parts/2/file.mp3"
        val remoteTwo = "https://173-230-133-75.abc.plex.direct:8443/library/parts/2/file.mp3"
        val tracks = listOf(
            Track(
                id = "t1",
                title = "One",
                artist = "Artist",
                album = "Album",
                durationMs = 60_000,
                streamUrl = lanOne,
                downloadUrl = "",
                playbackFallbackUrls = listOf(remoteOne),
            ),
            Track(
                id = "t2",
                title = "Two",
                artist = "Artist",
                album = "Album",
                durationMs = 60_000,
                streamUrl = lanTwo,
                downloadUrl = "",
                playbackFallbackUrls = listOf(remoteTwo),
            ),
        )

        player.play(tracks, 0)
        advanceTimeBy(player.testStartupTimeoutMs + 1L)
        runCurrent()

        assertEquals(remoteOne, player.state.value.queue[0].streamUrl)
        assertEquals(lanTwo, player.state.value.queue[1].streamUrl)

        player.finishPendingLoad()
        assertEquals(remoteTwo, player.state.value.queue[1].streamUrl)

        player.next()

        assertEquals(remoteTwo, player.state.value.currentTrack?.streamUrl)
        assertEquals(1, player.state.value.currentIndex)
    }

    @Test
    fun newPlayRequestsReuseTheOriginThatAlreadyWorked() = runTest {
        val player = TimeoutTestPlayer(this)
        val lanOne = "https://172-16-1-2.abc.plex.direct:32400/library/parts/1/file.mp3"
        val remoteOne = "https://173-230-133-75.abc.plex.direct:8443/library/parts/1/file.mp3"
        val lanTwo = "https://172-16-1-2.abc.plex.direct:32400/library/parts/2/file.mp3"
        val remoteTwo = "https://173-230-133-75.abc.plex.direct:8443/library/parts/2/file.mp3"

        player.play(
            listOf(
                Track(
                    id = "t1",
                    title = "One",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 60_000,
                    streamUrl = lanOne,
                    downloadUrl = "",
                    playbackFallbackUrls = listOf(remoteOne),
                ),
            ),
            0,
        )
        advanceTimeBy(player.testStartupTimeoutMs + 1L)
        runCurrent()
        player.finishPendingLoad()

        player.play(
            listOf(
                Track(
                    id = "t2",
                    title = "Two",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 60_000,
                    streamUrl = lanTwo,
                    downloadUrl = "",
                    playbackFallbackUrls = listOf(remoteTwo),
                ),
            ),
            0,
        )

        assertEquals(remoteTwo, player.state.value.currentTrack?.streamUrl)
    }

    @Test
    fun stopPlaybackClearsTheStickyOriginSoLanCanBeRediscovered() = runTest {
        val player = TimeoutTestPlayer(this)
        val lanOne = "https://172-16-1-2.abc.plex.direct:32400/library/parts/1/file.mp3"
        val remoteOne = "https://173-230-133-75.abc.plex.direct:8443/library/parts/1/file.mp3"
        val lanTwo = "https://172-16-1-2.abc.plex.direct:32400/library/parts/2/file.mp3"
        val remoteTwo = "https://173-230-133-75.abc.plex.direct:8443/library/parts/2/file.mp3"

        player.play(
            listOf(
                Track(
                    id = "t1",
                    title = "One",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 60_000,
                    streamUrl = lanOne,
                    downloadUrl = "",
                    playbackFallbackUrls = listOf(remoteOne),
                ),
            ),
            0,
        )
        advanceTimeBy(player.testStartupTimeoutMs + 1L)
        runCurrent()
        player.finishPendingLoad()
        player.stopPlayback()

        player.play(
            listOf(
                Track(
                    id = "t2",
                    title = "Two",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 60_000,
                    streamUrl = lanTwo,
                    downloadUrl = "",
                    playbackFallbackUrls = listOf(remoteTwo),
                ),
            ),
            0,
        )

        assertEquals(lanTwo, player.state.value.currentTrack?.streamUrl)
    }

    @Test
    fun startupWatchdogIgnoresSupersededPlayRequests() = runTest {
        val player = TimeoutTestPlayer(this)
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 60_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.play(tracks, 1)
        player.finishPendingLoad()

        advanceTimeBy(player.testStartupTimeoutMs + 1L)
        runCurrent()

        assertEquals(tracks[1], player.state.value.currentTrack)
        assertTrue(player.state.value.isPlaying)
        assertFalse(player.state.value.isBuffering)
        assertEquals(0, player.state.value.playbackErrorSerial)
    }

    @Test
    fun startupWatchdogStopsWhenPlaybackStops() = runTest {
        val player = TimeoutTestPlayer(this)
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
        )

        player.play(tracks, 0)
        player.stopPlayback()
        advanceTimeBy(player.testStartupTimeoutMs + 1L)
        runCurrent()

        assertFalse(player.state.value.isBuffering)
        assertEquals(0, player.state.value.playbackErrorSerial)
    }

    @Test
    fun clickingCurrentBufferingStreamTrackReassertsPlaybackIntent() {
        val player = SlowTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
        )

        player.play(tracks, 0)
        player.platformPlayWhenReady(false)
        player.play(tracks, 0)
        player.finishPendingLoad()

        assertTrue(player.state.value.isPlaying)
        assertEquals(1, player.resumeCalls)
    }

    @Test
    fun clickingCurrentBufferingDownloadedTrackReassertsPlaybackIntent() {
        val player = SlowTestPlayer()
        val tracks = listOf(
            Track(
                id = "t1",
                title = "One",
                artist = "Artist",
                album = "Album",
                durationMs = 60_000,
                streamUrl = "http://a",
                downloadUrl = "",
                localUri = "file:///downloads/one.mp3",
            ),
        )

        player.play(tracks, 0)
        player.platformPlayWhenReady(false)
        player.play(tracks, 0)
        player.finishPendingLoad()

        assertTrue(player.state.value.isPlaying)
        assertEquals(1, player.resumeCalls)
    }

    @Test
    fun rapidSameQueueSkipsOnlyPlayFinalTrack() {
        val player = SlowTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
            Track("t3", "Three", "Artist", "Album", 120_000, "http://c", ""),
            Track("t4", "Four", "Artist", "Album", 150_000, "http://d", ""),
        )

        player.play(tracks, 0)
        player.play(tracks, 1)
        player.play(tracks, 2)
        player.play(tracks, 3)

        assertEquals(tracks[3], player.state.value.currentTrack)
        assertTrue(player.state.value.isBuffering)
        assertFalse(player.state.value.isPlaying)

        player.finishLoad(1)
        player.finishLoad(2)
        player.finishLoad(3)
        assertEquals(tracks[3], player.state.value.currentTrack)
        assertFalse(player.state.value.isPlaying)

        player.finishLoad(4)
        assertEquals(tracks[3], player.state.value.currentTrack)
        assertTrue(player.state.value.isPlaying)
    }

    @Test
    fun playResetsPositionWhenSkippingTracks() {
        val player = PositionTrackingTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 180_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 180_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.finishPendingLoad()
        player.seekTo(120_000)
        assertEquals(120_000, player.state.value.positionMs)

        player.play(tracks, 1)
        player.finishPendingLoad()

        assertEquals(0L, player.state.value.positionMs)
        assertEquals(0L, player.lastSeekPositionMs)
    }

    @Test
    fun sameQueueSkipDoesNotReloadFromScratch() {
        val player = QueueAwareTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.finishPendingLoad()
        assertEquals(1, player.fullLoads)
        assertEquals(0, player.queueSkips)

        player.play(tracks, 1)
        player.finishPendingLoad()

        assertEquals(tracks[1], player.state.value.currentTrack)
        assertEquals(1, player.fullLoads)
        assertEquals(1, player.queueSkips)
    }

    @Test
    fun endOfQueueStopsPlayback() {
        val player = TestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
        )

        player.play(tracks, 0)
        assertTrue(player.state.value.isPlaying)

        player.next()

        assertFalse(player.state.value.isPlaying)
        assertFalse(player.state.value.isBuffering)
        assertEquals(60_000, player.state.value.positionMs)
    }

    @Test
    fun playingCurrentEndedTrackReloadsInsteadOfResumingEndedPlatformItem() {
        val player = EndedReplayTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.platformPlayback(
            positionMs = 60_000,
            durationMs = 60_000,
            bufferedPositionMs = 60_000,
            isPlaying = false,
        )
        player.play(tracks, 0)

        assertEquals(1, player.fullLoads)
        assertEquals(1, player.queueSkips)
        assertEquals(0, player.resumeCalls)
        assertEquals(tracks[0], player.state.value.currentTrack)
        assertEquals(0L, player.state.value.positionMs)
        assertTrue(player.state.value.isPlaying)
    }

    @Test
    fun repeatOneRestartsEndedTrackFromBeginning() {
        val player = EndedReplayTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.setRepeat(RepeatMode.One)
        player.platformPlayback(
            positionMs = 60_000,
            durationMs = 60_000,
            bufferedPositionMs = 60_000,
            isPlaying = false,
        )
        player.next()

        assertEquals(1, player.fullLoads)
        assertEquals(1, player.queueSkips)
        assertEquals(0, player.resumeCalls)
        assertEquals(tracks[0], player.state.value.currentTrack)
        assertEquals(0L, player.state.value.positionMs)
        assertTrue(player.state.value.isPlaying)
    }

    @Test
    fun bufferedPositionIsClampedToPositionAndDuration() {
        val player = PlatformStateTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
        )

        player.play(tracks, 0)
        player.platformPlayback(positionMs = 20_000, durationMs = 60_000, bufferedPositionMs = 10_000)

        assertEquals(20_000, player.state.value.bufferedPositionMs)

        player.platformPlayback(positionMs = 25_000, durationMs = 60_000, bufferedPositionMs = 90_000)

        assertEquals(60_000, player.state.value.bufferedPositionMs)
    }

    @Test
    fun platformPlayIntentCanResumeAfterAppPause() {
        val player = PlatformStateTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
        )

        player.play(tracks, 0)
        player.platformPlayback(positionMs = 10_000, durationMs = 60_000, bufferedPositionMs = 20_000)
        assertTrue(player.state.value.isPlaying)

        player.togglePlayPause()
        assertFalse(player.state.value.isPlaying)

        player.platformPlayback(positionMs = 11_000, durationMs = 60_000, bufferedPositionMs = 20_000)
        assertFalse(player.state.value.isPlaying)

        player.platformPlayWhenReady(true)
        player.platformPlayback(positionMs = 12_000, durationMs = 60_000, bufferedPositionMs = 21_000)

        assertTrue(player.state.value.isPlaying)
    }

    @Test
    fun newTrackResetsBufferedPosition() {
        val player = PlatformStateTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.platformPlayback(positionMs = 10_000, durationMs = 60_000, bufferedPositionMs = 50_000)
        assertEquals(50_000, player.state.value.bufferedPositionMs)

        player.play(tracks, 1)

        assertEquals(0L, player.state.value.bufferedPositionMs)
    }

    @Test
    fun bufferedPositionDoesNotMoveBackwardForCurrentTrack() {
        val player = PlatformStateTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
        )

        player.play(tracks, 0)
        player.platformPlayback(positionMs = 10_000, durationMs = 60_000, bufferedPositionMs = 50_000)
        player.platformPlayback(positionMs = 20_000, durationMs = 60_000, bufferedPositionMs = 30_000)

        assertEquals(50_000, player.state.value.bufferedPositionMs)
    }

    @Test
    fun bufferingStateClearsWhenPlayableBufferIsAvailable() {
        val player = PlatformStateTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
        )

        player.play(tracks, 0)
        player.platformPlayback(
            positionMs = 0L,
            durationMs = 60_000L,
            bufferedPositionMs = 20_000L,
            isPlaying = false,
            isBuffering = true,
        )

        assertFalse(player.state.value.isBuffering)
        assertFalse(player.state.value.isPlaying)
        assertEquals(20_000L, player.state.value.bufferedPositionMs)
    }

    @Test
    fun forcedStartupBufferingSurvivesPlayableBufferUntilAutoplayConfirmed() {
        val player = PlatformStateTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
        )

        player.play(tracks, 0)
        player.platformPlayback(
            positionMs = 0L,
            durationMs = 60_000L,
            bufferedPositionMs = 20_000L,
            isPlaying = false,
            isBuffering = true,
            forceBuffering = true,
        )

        assertTrue(player.state.value.isBuffering)
        assertFalse(player.state.value.isPlaying)
        assertEquals(20_000L, player.state.value.bufferedPositionMs)
    }

    @Test
    fun bufferingStateRemainsWhilePlayableBufferIsUnavailable() {
        val player = PlatformStateTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
        )

        player.play(tracks, 0)
        player.platformPlayback(
            positionMs = 10_000L,
            durationMs = 60_000L,
            bufferedPositionMs = 10_500L,
            isPlaying = false,
            isBuffering = true,
        )

        assertTrue(player.state.value.isBuffering)
        assertFalse(player.state.value.isPlaying)
        assertEquals(10_500L, player.state.value.bufferedPositionMs)
    }

    @Test
    fun playbackFailurePublishesOneShotErrorSignal() {
        val player = PlatformStateTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
        )

        player.play(tracks, 0)
        player.failPlayback("Nope")

        assertFalse(player.state.value.isPlaying)
        assertFalse(player.state.value.isBuffering)
        assertEquals(1, player.state.value.playbackErrorSerial)
        assertEquals("Nope", player.state.value.playbackErrorMessage)

        player.play(tracks, 0)

        assertEquals(1, player.state.value.playbackErrorSerial)
        assertEquals(null, player.state.value.playbackErrorMessage)
    }

    @Test
    fun infrastructureFailureStopsPlayIntentAndDoesNotAdvanceQueue() {
        val player = PlatformStateTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        val failure = PlaybackFailureClassifier.fromMessage(
            "ConnectException: Connection refused",
            streamUri = tracks[0].streamUrl,
        )
        player.publishFailure(failure)

        assertEquals(tracks[0], player.state.value.currentTrack)
        assertFalse(player.state.value.isPlaying)
        assertFalse(player.playIntentActive())
        assertEquals("Can't reach the music server. Check your connection and try again.", player.state.value.playbackErrorMessage)

        player.endCurrentTrack()

        assertEquals(tracks[0], player.state.value.currentTrack)
    }

    @Test
    fun platformCrossfadeDoesNotChangeTimelineUntilCommit() {
        val player = CrossfadeTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.setCrossfadeDurationMs(6_000)
        player.platformPlayback(positionMs = 55_000, durationMs = 60_000, bufferedPositionMs = 60_000)

        assertEquals(1, player.crossfadeStarts)
        assertEquals(tracks[0], player.state.value.currentTrack)
        assertEquals(55_000, player.state.value.positionMs)

        player.commitCrossfade(positionMs = 6_000)

        assertEquals(tracks[1], player.state.value.currentTrack)
        assertEquals(6_000, player.state.value.positionMs)
        assertEquals(90_000, player.state.value.durationMs)
    }

    @Test
    fun repeatedCrossfadeRequestsForSameTargetAreIgnoredUntilCommit() {
        val player = CrossfadeTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.setCrossfadeDurationMs(6_000)
        player.platformPlayback(positionMs = 55_000, durationMs = 60_000, bufferedPositionMs = 60_000)
        player.platformPlayback(positionMs = 56_000, durationMs = 60_000, bufferedPositionMs = 60_000)

        assertEquals(1, player.crossfadeStarts)
        assertEquals(tracks[0], player.state.value.currentTrack)
    }

    @Test
    fun automaticCrossfadeOnlyStartsInsideRemainingWindow() {
        val player = CrossfadeTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.setCrossfadeDurationMs(6_000)
        player.platformPlayback(positionMs = 53_000, durationMs = 60_000, bufferedPositionMs = 60_000)

        assertEquals(0, player.crossfadeStarts)

        player.platformPlayback(positionMs = 54_000, durationMs = 60_000, bufferedPositionMs = 60_000)

        assertEquals(1, player.crossfadeStarts)
        assertEquals(1, player.lastTargetIndex)
    }

    @Test
    fun manualSeekIntoCrossfadeWindowDoesNotStartAutomaticCrossfade() {
        val player = CrossfadeTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.setCrossfadeDurationMs(6_000)
        player.seekTo(55_000)
        player.platformPlayback(positionMs = 55_000, durationMs = 60_000, bufferedPositionMs = 60_000)
        player.platformPlayback(positionMs = 59_000, durationMs = 60_000, bufferedPositionMs = 60_000)

        assertEquals(0, player.crossfadeStarts)
        assertEquals(tracks[0], player.state.value.currentTrack)
    }

    @Test
    fun manualSeekBeforeCrossfadeWindowCanStillCrossfadeLater() {
        val player = CrossfadeTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.setCrossfadeDurationMs(6_000)
        player.seekTo(50_000)
        player.platformPlayback(positionMs = 54_000, durationMs = 60_000, bufferedPositionMs = 60_000)

        assertEquals(1, player.crossfadeStarts)
        assertEquals(1, player.lastTargetIndex)
    }

    @Test
    fun pausedPlaybackDoesNotStartAutomaticCrossfade() {
        val player = CrossfadeTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.setCrossfadeDurationMs(6_000)
        player.platformPlayback(
            positionMs = 55_000,
            durationMs = 60_000,
            bufferedPositionMs = 60_000,
            isPlaying = false,
        )

        assertEquals(0, player.crossfadeStarts)
        assertEquals(tracks[0], player.state.value.currentTrack)
    }

    @Test
    fun repeatAllCrossfadeTargetsFirstTrackFromQueueEnd() {
        val player = CrossfadeTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 1)
        player.setRepeat(RepeatMode.All)
        player.setCrossfadeDurationMs(6_000)
        player.platformPlayback(positionMs = 85_000, durationMs = 90_000, bufferedPositionMs = 90_000)

        assertEquals(1, player.crossfadeStarts)
        assertEquals(0, player.lastTargetIndex)

        player.commitCrossfade(positionMs = 4_000)

        assertEquals(tracks[0], player.state.value.currentTrack)
        assertEquals(4_000, player.state.value.positionMs)
    }

    @Test
    fun manualNextSkipsImmediatelyWhenCrossfadeIsEnabled() {
        val player = CrossfadeTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.setCrossfadeDurationMs(6_000)
        player.next()

        assertEquals(0, player.crossfadeStarts)
        assertEquals(tracks[1], player.state.value.currentTrack)
    }

    @Test
    fun unsupportedAutomaticCrossfadeDoesNotSkipEarly() {
        val player = PlatformStateTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.setCrossfadeDurationMs(6_000)
        player.platformPlayback(positionMs = 55_000, durationMs = 60_000, bufferedPositionMs = 60_000)

        assertEquals(tracks[0], player.state.value.currentTrack)
        assertEquals(55_000, player.state.value.positionMs)
    }

    @Test
    fun crossfadeCanRunAgainAfterCommitInSameQueue() {
        val player = CrossfadeTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
            Track("t3", "Three", "Artist", "Album", 120_000, "http://c", ""),
        )

        player.play(tracks, 0)
        player.setCrossfadeDurationMs(6_000)
        player.platformPlayback(positionMs = 55_000, durationMs = 60_000, bufferedPositionMs = 60_000)
        player.commitCrossfade(positionMs = 6_000)
        player.platformPlayback(positionMs = 85_000, durationMs = 90_000, bufferedPositionMs = 90_000)

        assertEquals(2, player.crossfadeStarts)
        assertEquals(tracks[1], player.state.value.currentTrack)

        player.commitCrossfade(positionMs = 6_000)

        assertEquals(tracks[2], player.state.value.currentTrack)
        assertEquals(6_000, player.state.value.positionMs)
    }

    @Test
    fun zeroSecondCrossfadeKeepsNormalNextBehavior() {
        val player = CrossfadeTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.setCrossfadeDurationMs(0)
        player.next()

        assertEquals(0, player.crossfadeStarts)
        assertEquals(tracks[1], player.state.value.currentTrack)
    }

    @Test
    fun gaplessPreparesOnlyInsideEndWindow() {
        val player = GaplessTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.setAudioProcessing(AudioProcessingSettings(gaplessEnabled = true))
        player.play(tracks, 0)
        player.platformPlayback(positionMs = 56_500, durationMs = 60_000, bufferedPositionMs = 60_000)

        assertEquals(0, player.gaplessStarts)

        player.platformPlayback(positionMs = 57_000, durationMs = 60_000, bufferedPositionMs = 60_000)

        assertEquals(1, player.gaplessStarts)
        assertEquals(1, player.lastGaplessTargetIndex)
    }

    @Test
    fun gaplessDoesNotPrepareWhenCrossfadeIsConfigured() {
        val player = GaplessTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.setAudioProcessing(AudioProcessingSettings(gaplessEnabled = true))
        player.setCrossfadeDurationMs(6_000)
        player.play(tracks, 0)
        player.platformPlayback(positionMs = 58_000, durationMs = 60_000, bufferedPositionMs = 60_000)

        assertEquals(0, player.gaplessStarts)
    }

    @Test
    fun pauseAndSeekCancelPreparedGaplessTrack() {
        val player = GaplessTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.setAudioProcessing(AudioProcessingSettings(gaplessEnabled = true))
        player.play(tracks, 0)
        player.platformPlayback(positionMs = 58_000, durationMs = 60_000, bufferedPositionMs = 60_000)

        assertEquals(1, player.gaplessStarts)

        player.togglePlayPause()

        assertEquals(1, player.gaplessCancels)

        player.togglePlayPause()
        player.platformPlayback(positionMs = 58_500, durationMs = 60_000, bufferedPositionMs = 60_000)
        player.seekTo(59_000)

        assertEquals(2, player.gaplessCancels)
    }

    @Test
    fun audioProcessingSettingsChangeCancelsPreparedGaplessTrack() {
        val player = GaplessTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.setAudioProcessing(AudioProcessingSettings(gaplessEnabled = true))
        player.play(tracks, 0)
        player.platformPlayback(positionMs = 58_000, durationMs = 60_000, bufferedPositionMs = 60_000)

        assertEquals(1, player.gaplessStarts)

        player.setAudioProcessing(
            AudioProcessingSettings(
                gaplessEnabled = true,
                crossfeedEnabled = true,
            ),
        )

        assertEquals(1, player.gaplessCancels)
    }

    @Test
    fun equalizerChangeCancelsPreparedGaplessTrack() {
        val player = GaplessTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.setAudioProcessing(AudioProcessingSettings(gaplessEnabled = true))
        player.play(tracks, 0)
        player.platformPlayback(positionMs = 58_000, durationMs = 60_000, bufferedPositionMs = 60_000)

        assertEquals(1, player.gaplessStarts)

        player.setEqualizer(EqualizerProfile.Default.withEnabled(true).withGain(index = 0, gainDb = 2f))

        assertEquals(1, player.gaplessCancels)
    }

    @Test
    fun preparedGaplessCommitAdvancesToPreparedTarget() {
        val player = GaplessTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.setAudioProcessing(AudioProcessingSettings(gaplessEnabled = true))
        player.play(tracks, 0)
        player.platformPlayback(positionMs = 58_000, durationMs = 60_000, bufferedPositionMs = 60_000)
        player.endCurrentTrack()

        assertEquals(1, player.gaplessCommits)
        assertEquals(tracks[1], player.state.value.currentTrack)
        assertEquals(0L, player.state.value.positionMs)
    }

    @Test
    fun failedGaplessCommitFallsBackToNormalNext() {
        val player = GaplessTestPlayer().also { it.commitAccepted = false }
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.setAudioProcessing(AudioProcessingSettings(gaplessEnabled = true))
        player.play(tracks, 0)
        player.platformPlayback(positionMs = 58_000, durationMs = 60_000, bufferedPositionMs = 60_000)
        player.endCurrentTrack()

        assertEquals(1, player.gaplessCommits)
        assertEquals(tracks[1], player.state.value.currentTrack)
    }

    @Test
    fun queueEditsNotifyPlatformWithoutChangingCurrentTrack() {
        val player = QueueEditHookTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
            Track("t3", "Three", "Artist", "Album", 120_000, "http://c", ""),
        )
        val inserted = Track("t4", "Four", "Artist", "Album", 150_000, "http://d", "")

        player.play(tracks, 0)
        player.addToUpNext(inserted)

        assertEquals(tracks[0], player.state.value.currentTrack)
        assertEquals(0, player.lastEditedCurrentIndex)
        assertEquals(listOf("t1", "t4", "t2", "t3"), player.lastEditedQueue.map { it.id })
    }

    @Test
    fun shuffledStartMarksShuffleWithoutQueueEdit() {
        val player = QueueEditHookTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t3", "Three", "Artist", "Album", 120_000, "http://c", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.playShuffled(tracks, 0)

        assertEquals(tracks[0], player.state.value.currentTrack)
        assertEquals(listOf("t1", "t3", "t2"), player.state.value.queue.map { it.id })
        assertTrue(player.state.value.shuffle)
        assertTrue(player.state.value.isPlaying)
        assertEquals(-2, player.lastEditedCurrentIndex)
        assertTrue(player.lastEditedQueue.isEmpty())
    }

    @Test
    fun clearingQueueNotifiesPlatformToDropFutureItems() {
        val player = QueueEditHookTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.clearQueue()

        assertEquals(tracks[0], player.state.value.currentTrack)
        assertEquals(0, player.lastEditedCurrentIndex)
        assertEquals(listOf("t1"), player.lastEditedQueue.map { it.id })
    }

    @Test
    fun clearQueueKeepsCurrentTrackButRemovesUpNext() {
        val player = TestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
            Track("t3", "Three", "Artist", "Album", 120_000, "http://c", ""),
        )

        player.play(tracks, 0)
        player.clearQueue()

        assertEquals(tracks[0], player.state.value.currentTrack)
        assertTrue(player.state.value.upNext.isEmpty())
    }

    @Test
    fun stopPlaybackClearsCurrentTrackAndUpNext() {
        val player = TestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.setVolume(0.5f)
        player.stopPlayback()

        assertEquals(null, player.state.value.currentTrack)
        assertTrue(player.state.value.queue.isEmpty())
        assertFalse(player.state.value.isPlaying)
        assertEquals(0.5f, player.state.value.volume)
    }

    @Test
    fun suspendPlaybackKeepsQueueWithoutReloadingPlatformOutput() {
        val player = SuspendTrackingTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        val loadsAfterPlay = player.playUriCalls
        val stopsAfterPlay = player.stopCalls

        player.suspendPlayback(tracks, 1, positionMs = 12_000)

        assertEquals(tracks[1], player.state.value.currentTrack)
        assertEquals(12_000, player.state.value.positionMs)
        assertFalse(player.state.value.isPlaying)
        assertFalse(player.state.value.isBuffering)
        assertEquals(loadsAfterPlay, player.playUriCalls)
        assertEquals(stopsAfterPlay + 1, player.stopCalls)
    }

    @Test
    fun suspendPlaybackDuringActiveCrossfadeCancelsTransitionAndStopsOutput() {
        val player = CrossfadeTestPlayer()
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 60_000, "http://a", ""),
            Track("t2", "Two", "Artist", "Album", 90_000, "http://b", ""),
        )

        player.play(tracks, 0)
        player.setCrossfadeDurationMs(6_000)
        player.platformPlayback(positionMs = 55_000, durationMs = 60_000, bufferedPositionMs = 60_000)

        assertEquals(1, player.crossfadeStarts)
        assertEquals(tracks[0], player.state.value.currentTrack)

        player.suspendPlayback(tracks, 0, positionMs = 55_000)

        assertEquals(tracks[0], player.state.value.currentTrack)
        assertEquals(55_000, player.state.value.positionMs)
        assertFalse(player.state.value.isPlaying)
        assertTrue(player.stopCalls >= 1)
    }
}

private class TestPlayer : SimpleAudioPlayer() {
    var lastUri: String? = null

    override fun playUri(uri: String) {
        lastUri = uri
        markPlaybackReady()
    }
}

private class SuspendTrackingTestPlayer : SimpleAudioPlayer() {
    var playUriCalls = 0
    var stopCalls = 0

    override fun playUri(uri: String) {
        playUriCalls++
        markPlaybackReady()
    }

    override fun stopCurrentPlaybackImmediately() {
        stopCalls++
    }
}

private open class SlowTestPlayer(
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : SimpleAudioPlayer(scope) {
    private val pendingLoads = mutableSetOf<Int>()
    var resumeCalls = 0

    override fun playUri(uri: String) = Unit

    override fun resume() {
        resumeCalls++
    }

    fun playIntentActive(): Boolean = playWhenReady

    override fun playQueueOnPlatform(
        queue: List<Track>,
        startIndex: Int,
        track: Track,
        generation: Int,
        startPositionMs: Long,
    ) {
        pendingLoads += generation
    }

    override fun skipToInQueueOnPlatform(queue: List<Track>, startIndex: Int, track: Track, generation: Int) {
        pendingLoads += generation
    }

    fun finishLoad(generation: Int) {
        if (generation in pendingLoads) {
            markPlaybackReady(generation = generation)
        }
    }

    fun finishPendingLoad() {
        finishLoad(activePlayGeneration)
    }

    fun platformPlayWhenReady(playWhenReady: Boolean) {
        adoptPlatformPlayIntent(playWhenReady)
    }
}

private class TimeoutTestPlayer(scope: CoroutineScope) : SlowTestPlayer(scope) {
    val testStartupTimeoutMs = 50L

    override val playbackStartupTimeoutMs: Long
        get() = testStartupTimeoutMs
}

private class QueueAwareTestPlayer : SimpleAudioPlayer() {
    var fullLoads = 0
    var queueSkips = 0

    override fun playUri(uri: String) = Unit

    override fun playQueueOnPlatform(
        queue: List<Track>,
        startIndex: Int,
        track: Track,
        generation: Int,
        startPositionMs: Long,
    ) {
        fullLoads++
    }

    override fun skipToInQueueOnPlatform(queue: List<Track>, startIndex: Int, track: Track, generation: Int) {
        queueSkips++
    }

    fun finishPendingLoad() {
        markPlaybackReady(generation = activePlayGeneration)
    }
}

private class QueueEditHookTestPlayer : SimpleAudioPlayer() {
    var lastEditedQueue: List<Track> = emptyList()
    var lastEditedCurrentIndex: Int = -2

    override fun playUri(uri: String) {
        markPlaybackReady()
    }

    override fun onQueueEdited(queue: List<Track>, currentIndex: Int) {
        lastEditedQueue = queue
        lastEditedCurrentIndex = currentIndex
    }
}

private class EndedReplayTestPlayer : SimpleAudioPlayer() {
    var fullLoads = 0
    var queueSkips = 0
    var resumeCalls = 0

    override fun playUri(uri: String) = Unit

    override fun playQueueOnPlatform(
        queue: List<Track>,
        startIndex: Int,
        track: Track,
        generation: Int,
        startPositionMs: Long,
    ) {
        fullLoads++
        markPlaybackReady(generation = generation)
    }

    override fun skipToInQueueOnPlatform(queue: List<Track>, startIndex: Int, track: Track, generation: Int) {
        queueSkips++
        markPlaybackReady(generation = generation)
    }

    override fun resume() {
        resumeCalls++
    }

    fun platformPlayback(
        positionMs: Long,
        durationMs: Long,
        bufferedPositionMs: Long,
        isPlaying: Boolean,
    ) {
        applyPlatformPlayback(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            isBuffering = false,
            bufferedPositionMs = bufferedPositionMs,
        )
    }
}

private class PositionTrackingTestPlayer : SimpleAudioPlayer() {
    var lastSeekPositionMs: Long = -1

    override fun playUri(uri: String) = Unit

    override fun skipToInQueueOnPlatform(queue: List<Track>, startIndex: Int, track: Track, generation: Int) {
        lastSeekPositionMs = 0L
        markPlaybackReady(generation = generation)
    }

    override fun playQueueOnPlatform(
        queue: List<Track>,
        startIndex: Int,
        track: Track,
        generation: Int,
        startPositionMs: Long,
    ) {
        lastSeekPositionMs = 0L
        markPlaybackReady(generation = generation)
    }

    override fun seek(positionMs: Long) {
        lastSeekPositionMs = positionMs
    }

    fun finishPendingLoad() {
        markPlaybackReady(generation = activePlayGeneration)
    }
}

private class PlatformStateTestPlayer : SimpleAudioPlayer() {
    override fun playUri(uri: String) = Unit

    fun platformPlayback(
        positionMs: Long,
        durationMs: Long,
        bufferedPositionMs: Long,
        isPlaying: Boolean = true,
        isBuffering: Boolean = false,
        forceBuffering: Boolean = false,
    ) {
        applyPlatformPlayback(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            bufferedPositionMs = bufferedPositionMs,
            forceBuffering = forceBuffering,
        )
    }

    fun platformPlayWhenReady(playWhenReady: Boolean) {
        adoptPlatformPlayIntent(playWhenReady)
    }

    fun failPlayback(message: String? = null, cancelPlayIntent: Boolean = false) {
        markPlaybackFailed(message = message, cancelPlayIntent = cancelPlayIntent)
    }

    fun playIntentActive(): Boolean = playWhenReady

    fun publishFailure(failure: PlaybackFailure) {
        publishPlaybackFailure(failure)
    }

    fun endCurrentTrack() {
        advanceAfterPlatformTrackEnded()
    }
}

private class CrossfadeTestPlayer : SimpleAudioPlayer() {
    var crossfadeStarts = 0
    var lastTargetIndex = -1
    var stopCalls = 0
    private var pendingQueue: List<Track> = emptyList()
    private var pendingTargetIndex = -1
    private var pendingGeneration = -1

    override fun playUri(uri: String) {
        markPlaybackReady()
    }

    override fun stopCurrentPlaybackImmediately() {
        stopCalls++
    }

    override fun startCrossfadeOnPlatform(
        queue: List<Track>,
        targetIndex: Int,
        track: Track,
        durationMs: Long,
        baseVolume: Float,
        generation: Int,
    ): Boolean {
        crossfadeStarts++
        lastTargetIndex = targetIndex
        pendingQueue = queue
        pendingTargetIndex = targetIndex
        pendingGeneration = generation
        return true
    }

    fun platformPlayback(
        positionMs: Long,
        durationMs: Long,
        bufferedPositionMs: Long,
        isPlaying: Boolean = true,
    ) {
        applyPlatformPlayback(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            isBuffering = false,
            bufferedPositionMs = bufferedPositionMs,
        )
    }

    fun commitCrossfade(positionMs: Long) {
        adoptCrossfadeTarget(pendingQueue, pendingTargetIndex, positionMs, pendingGeneration)
    }
}

private class GaplessTestPlayer : SimpleAudioPlayer() {
    var gaplessStarts = 0
    var gaplessCommits = 0
    var gaplessCancels = 0
    var lastGaplessTargetIndex = -1
    var commitAccepted = true

    override fun playUri(uri: String) {
        markPlaybackReady()
    }

    override fun startGaplessPrepareOnPlatform(
        queue: List<Track>,
        targetIndex: Int,
        track: Track,
        generation: Int,
    ): Boolean {
        gaplessStarts++
        lastGaplessTargetIndex = targetIndex
        return true
    }

    override fun commitGaplessPreparedOnPlatform(
        queue: List<Track>,
        targetIndex: Int,
        track: Track,
        generation: Int,
    ): Boolean {
        gaplessCommits++
        return commitAccepted
    }

    override fun cancelGaplessPrepareOnPlatform(generation: Int) {
        gaplessCancels++
    }

    fun platformPlayback(
        positionMs: Long,
        durationMs: Long,
        bufferedPositionMs: Long,
        isPlaying: Boolean = true,
    ) {
        applyPlatformPlayback(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            isBuffering = false,
            bufferedPositionMs = bufferedPositionMs,
        )
    }

    fun endCurrentTrack() {
        advanceAfterPlatformTrackEnded()
    }
}
