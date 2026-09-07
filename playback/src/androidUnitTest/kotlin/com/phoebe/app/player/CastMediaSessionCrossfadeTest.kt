package com.phoebe.app.player

import android.app.Application
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.phoebe.app.domain.Track
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Android Auto / MediaSession behavior while local crossfade is active or committing.
 *
 * These tests mirror the [LocalMediaSessionState] sequence [AndroidAudioPlayer] publishes:
 * outgoing track during the ramp, incoming track after commit, with cast taking priority when active.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CastMediaSessionCrossfadeTest {
    @Test
    fun androidAutoShowsOutgoingTrackDuringCrossfadeUntilCommit() {
        val delegate = FakeSessionDelegate()
        val player = CastMediaSessionPlayer(delegate)
        val outgoing = testTrack("track-outgoing")
        val incoming = testTrack("track-incoming")

        try {
            delegate.setStateForTest(
                delegateState(
                    tracks = listOf(outgoing, incoming),
                    currentIndex = 0,
                ).build(),
            )

            player.updateLocalState(
                LocalMediaSessionState(
                    track = outgoing,
                    isPlaying = true,
                    isBuffering = false,
                    positionMs = 55_000,
                    bufferedPositionMs = 60_000,
                    durationMs = 60_000,
                ),
            )
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("track-outgoing", player.currentMediaItem?.mediaId)
            assertEquals(0, player.currentMediaItemIndex)
            assertTrue(player.isPlaying)
            assertEquals(55_000L, player.currentPosition)

            player.updateLocalState(
                LocalMediaSessionState(
                    track = incoming,
                    isPlaying = true,
                    isBuffering = false,
                    positionMs = 6_000,
                    bufferedPositionMs = 12_000,
                    durationMs = 90_000,
                ),
            )
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("track-incoming", player.currentMediaItem?.mediaId)
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals(6_000L, player.currentPosition)
            assertEquals(90_000L, player.duration)
        } finally {
            player.release()
        }
    }

    @Test
    fun castActiveOverridesLocalCrossfadeStateForAndroidAuto() {
        val player = CastMediaSessionPlayer(FakeSessionDelegate())
        val localOutgoing = testTrack("local-outgoing")
        val castTrack = testTrack("cast-current")

        try {
            player.updateLocalState(
                LocalMediaSessionState(
                    track = localOutgoing,
                    isPlaying = true,
                    isBuffering = false,
                    positionMs = 55_000,
                    bufferedPositionMs = 60_000,
                    durationMs = 60_000,
                ),
            )
            player.updateCastState(
                CastMediaSessionState(
                    track = castTrack,
                    isPlaying = true,
                    isBuffering = false,
                    positionMs = 12_000,
                    durationMs = 180_000,
                ),
            )
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("cast-current", player.currentMediaItem?.mediaId)
            assertEquals(12_000L, player.currentPosition)
            assertEquals(180_000L, player.duration)
        } finally {
            player.release()
        }
    }

    @Test
    fun androidAutoPauseDuringLocalCrossfadeRoutesToLocalPauseCallback() {
        val player = CastMediaSessionPlayer(FakeSessionDelegate())
        var pauseCalls = 0
        val previousPause = AndroidPlaybackBridge.onLocalMediaSessionPause

        try {
            AndroidPlaybackBridge.onLocalMediaSessionPause = { pauseCalls++ }
            player.updateLocalState(
                LocalMediaSessionState(
                    track = testTrack("track-crossfade-outgoing"),
                    isPlaying = true,
                    isBuffering = false,
                    positionMs = 56_000,
                    bufferedPositionMs = 60_000,
                    durationMs = 60_000,
                ),
            )
            shadowOf(Looper.getMainLooper()).idle()

            player.pause()

            assertEquals(1, pauseCalls)
        } finally {
            AndroidPlaybackBridge.onLocalMediaSessionPause = previousPause
            player.release()
        }
    }

    @Test
    fun androidAutoSkipDuringLocalCrossfadeRoutesToPhoebeSkipNext() {
        val player = CastMediaSessionPlayer(FakeSessionDelegate())
        var skipNextCalls = 0
        val previousHasNext = AndroidPlaybackBridge.hasNextTrack
        val previousSkipNext = AndroidPlaybackBridge.onSkipNext

        try {
            AndroidPlaybackBridge.hasNextTrack = { true }
            AndroidPlaybackBridge.onSkipNext = { skipNextCalls++ }
            player.updateLocalState(
                LocalMediaSessionState(
                    track = testTrack("track-crossfade-outgoing"),
                    isPlaying = true,
                    isBuffering = false,
                    positionMs = 57_000,
                    bufferedPositionMs = 60_000,
                    durationMs = 60_000,
                ),
            )
            shadowOf(Looper.getMainLooper()).idle()

            player.seekToNext()

            assertEquals(1, skipNextCalls)
        } finally {
            AndroidPlaybackBridge.hasNextTrack = previousHasNext
            AndroidPlaybackBridge.onSkipNext = previousSkipNext
            player.release()
        }
    }

    @Test
    fun localCrossfadeCommitPreservesDelegateTimelineWindow() {
        val delegate = FakeSessionDelegate()
        val player = CastMediaSessionPlayer(delegate)
        val first = testTrack("delegate-first")
        val second = testTrack("delegate-second")

        try {
            delegate.setStateForTest(
                delegateState(
                    tracks = listOf(first, second),
                    currentIndex = 0,
                ).build(),
            )

            player.updateLocalState(
                LocalMediaSessionState(
                    track = first,
                    isPlaying = true,
                    isBuffering = false,
                    positionMs = 55_000,
                    bufferedPositionMs = 60_000,
                    durationMs = 60_000,
                ),
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, player.currentTimeline.windowCount)
            assertEquals(0, player.currentMediaItemIndex)
            val window = Timeline.Window()
            val firstWindowUid = player.currentTimeline.getWindow(0, window).uid
            val secondWindowUid = player.currentTimeline.getWindow(1, window).uid

            player.updateLocalState(
                LocalMediaSessionState(
                    track = second,
                    isPlaying = true,
                    isBuffering = false,
                    positionMs = 4_000,
                    bufferedPositionMs = 10_000,
                    durationMs = 90_000,
                ),
            )
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(2, player.currentTimeline.windowCount)
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals("delegate-second", player.currentMediaItem?.mediaId)
            // Stable window UIDs keep Android Auto on Now Playing across skips.
            assertEquals(firstWindowUid, player.currentTimeline.getWindow(0, window).uid)
            assertEquals(secondWindowUid, player.currentTimeline.getWindow(1, window).uid)
        } finally {
            player.release()
        }
    }

    @Test
    fun clearingLocalStateAfterCrossfadeFallsBackToDelegateTimeline() {
        val delegate = FakeSessionDelegate()
        val player = CastMediaSessionPlayer(delegate)
        val first = testTrack("delegate-first")
        val second = testTrack("delegate-second")

        try {
            delegate.setStateForTest(
                delegateState(
                    tracks = listOf(first, second),
                    currentIndex = 1,
                ).build(),
            )
            player.updateLocalState(
                LocalMediaSessionState(
                    track = second,
                    isPlaying = true,
                    isBuffering = false,
                    positionMs = 8_000,
                    bufferedPositionMs = 15_000,
                    durationMs = 90_000,
                ),
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("delegate-second", player.currentMediaItem?.mediaId)

            player.updateLocalState(null)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("delegate-second", player.currentMediaItem?.mediaId)
            assertEquals(1, player.currentMediaItemIndex)
        } finally {
            player.release()
        }
    }

    @Test
    fun androidAutoDoesNotRouteCastPauseToLocalCrossfadeCallbacks() {
        val player = CastMediaSessionPlayer(FakeSessionDelegate())
        var localPauseCalls = 0
        var castPauseCalls = 0
        val previousLocalPause = AndroidPlaybackBridge.onLocalMediaSessionPause
        val previousCastPause = AndroidPlaybackBridge.onCastPause

        try {
            AndroidPlaybackBridge.onLocalMediaSessionPause = { localPauseCalls++ }
            AndroidPlaybackBridge.onCastPause = { castPauseCalls++ }
            player.updateCastState(
                CastMediaSessionState(
                    track = testTrack("cast-track"),
                    isPlaying = true,
                    isBuffering = false,
                    positionMs = 20_000,
                    durationMs = 180_000,
                ),
            )
            shadowOf(Looper.getMainLooper()).idle()

            player.pause()

            assertEquals(1, castPauseCalls)
            assertEquals(0, localPauseCalls)
        } finally {
            AndroidPlaybackBridge.onLocalMediaSessionPause = previousLocalPause
            AndroidPlaybackBridge.onCastPause = previousCastPause
            player.release()
        }
    }

    private class FakeSessionDelegate : SimpleBasePlayer(Looper.getMainLooper()) {
        private var state = SimpleBasePlayer.State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaybackState(Player.STATE_IDLE)
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(0L)
            .setContentBufferedPositionMs(SimpleBasePlayer.PositionSupplier.getConstant(0L))
            .setTotalBufferedDurationMs(SimpleBasePlayer.PositionSupplier.ZERO)
            .build()

        override fun getState(): SimpleBasePlayer.State = state

        fun setStateForTest(state: SimpleBasePlayer.State) {
            this.state = state
            invalidateState()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            state = state.buildUpon()
                .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int,
        ): ListenableFuture<*> {
            state = state.buildUpon()
                .setContentPositionMs(positionMs.coerceAtLeast(0L))
                .build()
            return Futures.immediateVoidFuture()
        }

        override fun handleStop(): ListenableFuture<*> {
            state = state.buildUpon()
                .setPlaybackState(Player.STATE_IDLE)
                .setContentPositionMs(C.TIME_UNSET)
                .build()
            return Futures.immediateVoidFuture()
        }

        override fun handleRelease(): ListenableFuture<*> =
            Futures.immediateVoidFuture()
    }

    private fun delegateState(
        tracks: List<Track>,
        currentIndex: Int,
    ): SimpleBasePlayer.State.Builder {
        val items = tracks.map { track ->
            val mediaItem = playbackMediaItem(track, inAppPlayback = true)
            SimpleBasePlayer.MediaItemData.Builder(track.id)
                .setMediaItem(mediaItem)
                .setMediaMetadata(mediaItem.mediaMetadata)
                .setDurationUs(track.durationMs * 1_000L)
                .setIsSeekable(true)
                .build()
        }
        return SimpleBasePlayer.State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(items)
            .setCurrentMediaItemIndex(currentIndex)
    }

    private fun testTrack(id: String): Track =
        Track(
            id = id,
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000,
            streamUrl = "https://example.test/$id.mp3",
            downloadUrl = "",
        )
}
