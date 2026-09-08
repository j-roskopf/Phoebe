package com.phoebe.app.player

import android.app.Application
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.phoebe.app.domain.Track
import org.junit.Assert.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CastMediaSessionPlayerTest {
    @Test
    fun nativeSkipUsesDelegateSeekWhenPlaylistAlreadyHasNext() {
        val delegate = FakeSessionDelegate()
        val player = CastMediaSessionPlayer(delegate)
        val first = testTrack("native-first")
        val second = testTrack("native-second")
        var skipNextCalls = 0
        val previousHasNext = AndroidPlaybackBridge.hasNextTrack
        val previousSkipNext = AndroidPlaybackBridge.onSkipNext

        try {
            AndroidPlaybackBridge.hasNextTrack = { true }
            AndroidPlaybackBridge.onSkipNext = { skipNextCalls++ }
            delegate.setStateForTest(
                delegateState(
                    tracks = listOf(first, second),
                    currentIndex = 0,
                ).build(),
            )
            shadowOf(Looper.getMainLooper()).idle()

            player.seekToNext()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(0, skipNextCalls)
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals("native-second", player.currentMediaItem?.mediaId)
        } finally {
            AndroidPlaybackBridge.hasNextTrack = previousHasNext
            AndroidPlaybackBridge.onSkipNext = previousSkipNext
            player.release()
        }
    }

    @Test
    fun localStateOverridesPausedDelegateForAndroidAuto() {
        val player = CastMediaSessionPlayer(FakeSessionDelegate())
        val track = testTrack("track-crossfade")

        try {
            player.updateLocalState(
                LocalMediaSessionState(
                    track = track,
                    isPlaying = true,
                    isBuffering = false,
                    positionMs = 42_000,
                    bufferedPositionMs = 60_000,
                    durationMs = 180_000,
                ),
            )

            assertEquals("track-crossfade", player.currentMediaItem?.mediaId)
            assertTrue(player.playWhenReady)
            assertTrue(player.isPlaying)
            assertTrue(player.isCommandAvailable(Player.COMMAND_GET_TIMELINE))
            assertTrue(player.currentPosition >= 42_000)
            assertEquals(180_000, player.duration)
        } finally {
            player.release()
        }
    }

    @Test
    fun localStateCanBeUpdatedOffMainThread() {
        val player = CastMediaSessionPlayer(FakeSessionDelegate())
        val error = AtomicReference<Throwable?>()

        try {
            val thread = Thread {
                runCatching {
                    player.updateLocalState(
                        LocalMediaSessionState(
                            track = testTrack("track-background"),
                            isPlaying = true,
                            isBuffering = false,
                            positionMs = 7_000,
                            bufferedPositionMs = 8_000,
                            durationMs = 90_000,
                        ),
                    )
                }.exceptionOrNull()?.let(error::set)
            }

            thread.start()
            thread.join()
            assertNull(error.get())

            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("track-background", player.currentMediaItem?.mediaId)
            assertTrue(player.playWhenReady)
            assertEquals(90_000, player.duration)
        } finally {
            player.release()
        }
    }

    @Test
    fun localStatePreservesDelegateTimelineWindowForControllerMerges() {
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
                    track = second,
                    isPlaying = true,
                    isBuffering = false,
                    positionMs = 10_000,
                    bufferedPositionMs = 20_000,
                    durationMs = 180_000,
                ),
            )

            assertEquals(2, player.currentTimeline.windowCount)
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals("delegate-second", player.currentMediaItem?.mediaId)
        } finally {
            player.release()
        }
    }

    @Test
    fun localStateDoesNotReplayDelegatePositionDiscontinuity() {
        val delegate = FakeSessionDelegate()
        val player = CastMediaSessionPlayer(delegate)
        val track = testTrack("track-local")
        var discontinuityCalls = 0

        try {
            player.updateLocalState(
                LocalMediaSessionState(
                    track = track,
                    isPlaying = true,
                    isBuffering = false,
                    positionMs = 10_000,
                    bufferedPositionMs = 20_000,
                    durationMs = 180_000,
                ),
            )
            player.addListener(
                object : Player.Listener {
                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int,
                    ) {
                        discontinuityCalls++
                    }
                },
            )
            delegate.setStateForTest(
                delegateStateWithPositionDiscontinuity(
                    first = testTrack("delegate-first"),
                    second = testTrack("delegate-second"),
                ),
            )
            shadowOf(Looper.getMainLooper()).idle()
            discontinuityCalls = 0

            player.updateLocalState(
                LocalMediaSessionState(
                    track = track,
                    isPlaying = true,
                    isBuffering = false,
                    positionMs = 10_000,
                    bufferedPositionMs = 20_000,
                    durationMs = 180_000,
                ),
            )
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("track-local", player.currentMediaItem?.mediaId)
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals(0, discontinuityCalls)
        } finally {
            player.release()
        }
    }

    @Test
    fun delegateRoutesSkipNextToPhoebeQueueWhenAppHasMoreTracks() {
        val player = CastMediaSessionPlayer(FakeSessionDelegate())
        var skipNextCalls = 0
        val previousHasNext = AndroidPlaybackBridge.hasNextTrack
        val previousSkipNext = AndroidPlaybackBridge.onSkipNext
        try {
            AndroidPlaybackBridge.hasNextTrack = { true }
            AndroidPlaybackBridge.onSkipNext = { skipNextCalls++ }

            player.seekToNext()

            assertEquals(1, skipNextCalls)
        } finally {
            AndroidPlaybackBridge.hasNextTrack = previousHasNext
            AndroidPlaybackBridge.onSkipNext = previousSkipNext
            player.release()
        }
    }

    @Test
    fun localStateRoutesSessionControlsToLocalCallbacks() {
        val player = CastMediaSessionPlayer(FakeSessionDelegate())
        var pauseCalls = 0
        var seekPositionMs: Long? = null
        val previousPause = AndroidPlaybackBridge.onLocalMediaSessionPause
        val previousSeek = AndroidPlaybackBridge.onLocalMediaSessionSeekTo
        try {
            AndroidPlaybackBridge.onLocalMediaSessionPause = { pauseCalls++ }
            AndroidPlaybackBridge.onLocalMediaSessionSeekTo = { seekPositionMs = it }
            player.updateLocalState(
                LocalMediaSessionState(
                    track = testTrack("track-control"),
                    isPlaying = true,
                    isBuffering = false,
                    positionMs = 10_000,
                    bufferedPositionMs = 20_000,
                    durationMs = 180_000,
                ),
            )

            player.pause()
            player.seekTo(55_000)

            assertEquals(1, pauseCalls)
            assertEquals(55_000, seekPositionMs)
        } finally {
            AndroidPlaybackBridge.onLocalMediaSessionPause = previousPause
            AndroidPlaybackBridge.onLocalMediaSessionSeekTo = previousSeek
            player.release()
        }
    }

    @Test
    fun catalogDurationMakesUnseekableDelegateSeekableForAndroidAuto() {
        val delegate = FakeSessionDelegate()
        val player = CastMediaSessionPlayer(delegate)
        val track = testTrack("track-seekable")

        try {
            delegate.setStateForTest(
                delegateState(
                    tracks = listOf(track),
                    currentIndex = 0,
                    seekable = false,
                    includeDuration = false,
                    includeSeekCommand = false,
                ).build(),
            )
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            assertEquals(180_000, player.duration)
            assertTrue(player.isCurrentMediaItemSeekable)
            assertFalse(player.isCurrentMediaItemLive)
        } finally {
            player.release()
        }
    }

    @Test
    fun catalogSeekabilitySurvivesSkipToSecondUnseekableItem() {
        val delegate = FakeSessionDelegate()
        val player = CastMediaSessionPlayer(delegate)
        val first = testTrack("track-first", durationMs = 180_000)
        val second = testTrack("track-second", durationMs = 240_000)

        try {
            delegate.setStateForTest(
                delegateState(
                    tracks = listOf(first, second),
                    currentIndex = 1,
                    seekable = false,
                    includeDuration = false,
                    includeSeekCommand = false,
                ).build(),
            )
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("track-second", player.currentMediaItem?.mediaId)
            assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            assertEquals(240_000, player.duration)
            assertTrue(player.isCurrentMediaItemSeekable)
            assertFalse(player.isCurrentMediaItemLive)
            assertFalse(player.isCurrentMediaItemDynamic)
        } finally {
            player.release()
        }
    }

    @Test
    fun catalogSeekabilityClearsLiveConfigurationSoAndroidAutoKeepsSeekBar() {
        val delegate = FakeSessionDelegate()
        val player = CastMediaSessionPlayer(delegate)
        val first = testTrack("live-first")
        val second = testTrack("live-second", durationMs = 210_000)

        try {
            delegate.setStateForTest(
                delegateState(
                    tracks = listOf(first, second),
                    currentIndex = 1,
                    seekable = false,
                    includeDuration = false,
                    includeSeekCommand = false,
                    live = true,
                ).build(),
            )
            shadowOf(Looper.getMainLooper()).idle()

            // Media3 legacy stub strips ACTION_SEEK_TO while isCurrentMediaItemLive is true.
            assertFalse(player.isCurrentMediaItemLive)
            assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            assertEquals(210_000, player.duration)
            assertTrue(player.isCurrentMediaItemSeekable)
        } finally {
            player.release()
        }
    }

    @Test
    fun radioLiveTransportHidesSeekAndSkipCommands() {
        val delegate = FakeSessionDelegate()
        val player = CastMediaSessionPlayer(delegate)
        val radio = testTrack("radio:kexp", durationMs = 0)
        val previousHasNext = AndroidPlaybackBridge.hasNextTrack
        val previousHasPrevious = AndroidPlaybackBridge.hasPreviousTrack

        try {
            AndroidPlaybackBridge.hasNextTrack = { false }
            AndroidPlaybackBridge.hasPreviousTrack = { false }
            delegate.setStateForTest(
                delegateState(
                    tracks = listOf(radio),
                    currentIndex = 0,
                    seekable = true,
                    includeDuration = false,
                    includeSeekCommand = true,
                    live = false,
                ).build(),
            )
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(player.isCurrentMediaItemSeekable)
            assertTrue(player.isCurrentMediaItemLive)
            assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
            assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        } finally {
            AndroidPlaybackBridge.hasNextTrack = previousHasNext
            AndroidPlaybackBridge.hasPreviousTrack = previousHasPrevious
            player.release()
        }
    }

    @Test
    fun catalogSeekabilityFallsBackToBridgeTrackDurationWhenMetadataMissing() {
        val delegate = FakeSessionDelegate()
        val player = CastMediaSessionPlayer(delegate)
        val track = testTrack("bridge-duration", durationMs = 0)
        val previousCurrentTrack = AndroidPlaybackBridge.currentTrack

        try {
            AndroidPlaybackBridge.currentTrack = {
                testTrack("bridge-duration", durationMs = 195_000)
            }
            delegate.setStateForTest(
                delegateState(
                    tracks = listOf(track),
                    currentIndex = 0,
                    seekable = false,
                    includeDuration = false,
                    includeSeekCommand = false,
                ).build(),
            )
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            assertEquals(195_000, player.duration)
            assertTrue(player.isCurrentMediaItemSeekable)
        } finally {
            AndroidPlaybackBridge.currentTrack = previousCurrentTrack
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
            val targetIndex = when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                -> (state.currentMediaItemIndex + 1).takeIf { it < state.playlist.size }
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                -> (state.currentMediaItemIndex - 1).takeIf { it >= 0 }
                else -> mediaItemIndex.takeIf { it in state.playlist.indices }
            } ?: state.currentMediaItemIndex
            state = state.buildUpon()
                .setCurrentMediaItemIndex(targetIndex)
                .setContentPositionMs(positionMs.coerceAtLeast(0L))
                .build()
            invalidateState()
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

    private fun delegateStateWithPositionDiscontinuity(
        first: Track,
        second: Track,
    ): SimpleBasePlayer.State {
        return delegateState(
            tracks = listOf(first, second),
            currentIndex = 1,
        )
            .setCurrentAd(C.INDEX_UNSET, C.INDEX_UNSET)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(12_000)
            .setContentBufferedPositionMs(SimpleBasePlayer.PositionSupplier.getConstant(20_000))
            .setTotalBufferedDurationMs(SimpleBasePlayer.PositionSupplier.ZERO)
            .setPositionDiscontinuity(Player.DISCONTINUITY_REASON_SKIP, 12_000)
            .build()
    }

    private fun delegateState(
        tracks: List<Track>,
        currentIndex: Int,
        seekable: Boolean = true,
        includeDuration: Boolean = true,
        includeSeekCommand: Boolean = true,
        live: Boolean = false,
    ): SimpleBasePlayer.State.Builder {
        val items = tracks.map { track ->
            val mediaItem = playbackMediaItem(track, inAppPlayback = true)
            val builder = SimpleBasePlayer.MediaItemData.Builder(track.id)
                .setMediaItem(mediaItem)
                .setMediaMetadata(mediaItem.mediaMetadata)
                .setIsSeekable(seekable)
                .setIsDynamic(live)
            if (live) {
                builder.setLiveConfiguration(MediaItem.LiveConfiguration.UNSET)
            }
            if (includeDuration) {
                builder.setDurationUs(track.durationMs * 1_000L)
            }
            builder.build()
        }
        val commands = Player.Commands.Builder().addAllCommands()
        if (!includeSeekCommand) {
            commands.remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        }
        return SimpleBasePlayer.State.Builder()
            .setAvailableCommands(commands.build())
            .setPlaylist(items)
            .setCurrentMediaItemIndex(currentIndex)
    }

    private fun testTrack(id: String, durationMs: Long = 180_000): Track =
        Track(
            id = id,
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = durationMs,
            streamUrl = "https://example.test/$id.mp3",
            downloadUrl = "",
        )
}
