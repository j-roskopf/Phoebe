package com.phoebe.app.player

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.phoebe.app.domain.Track

@OptIn(UnstableApi::class)
internal class CastMediaSessionPlayer(
    player: Player,
) : ForwardingSimpleBasePlayer(player) {
    private val sessionLooper = player.applicationLooper
    private val sessionHandler = Handler(sessionLooper)
    private var castState: CastMediaSessionState? = null
    private var localState: LocalMediaSessionState? = null

    fun updateCastState(state: CastMediaSessionState?) {
        updateSessionState {
            castState = state
            invalidateState()
        }
    }

    fun updateLocalState(state: LocalMediaSessionState?) {
        updateSessionState {
            localState = state
            invalidateState()
        }
    }

    override fun getState(): SimpleBasePlayer.State {
        val delegateState = safeDelegateState()
        castState?.let { cast ->
            return delegateState.withMediaSessionOverride(
                track = cast.track,
                isPlaying = cast.isPlaying,
                isBuffering = cast.isBuffering,
                positionMs = cast.positionMs,
                bufferedPositionMs = cast.durationMs.takeIf { it > 0L } ?: cast.positionMs,
                durationMs = cast.durationMs,
                playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
            )
            .withPhoebeQueueNavigationCommands(
                hasNext = AndroidPlaybackBridge.hasNextTrack?.invoke() == true,
                hasPrevious = AndroidPlaybackBridge.hasPreviousTrack?.invoke() == true,
            )
        }
        localState?.let { local ->
            return delegateState.withMediaSessionOverride(
                track = local.track,
                isPlaying = local.isPlaying,
                isBuffering = local.isBuffering,
                positionMs = local.positionMs,
                bufferedPositionMs = local.bufferedPositionMs,
                durationMs = local.durationMs,
                playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .withPhoebeQueueNavigationCommands(
                hasNext = AndroidPlaybackBridge.hasNextTrack?.invoke() == true,
                hasPrevious = AndroidPlaybackBridge.hasPreviousTrack?.invoke() == true,
            )
        }
        return delegateState.withPhoebeQueueNavigationCommands(
            hasNext = AndroidPlaybackBridge.hasNextTrack?.invoke() == true,
            hasPrevious = AndroidPlaybackBridge.hasPreviousTrack?.invoke() == true,
        )
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (castState != null) {
            if (playWhenReady) {
                AndroidPlaybackBridge.onCastPlay?.invoke()
            } else {
                AndroidPlaybackBridge.onCastPause?.invoke()
            }
            return Futures.immediateVoidFuture()
        }
        if (localState != null) {
            if (playWhenReady) {
                AndroidPlaybackBridge.onLocalMediaSessionPlay?.invoke()
            } else {
                AndroidPlaybackBridge.onLocalMediaSessionPause?.invoke()
            }
            return Futures.immediateVoidFuture()
        }
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (castState != null) {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                -> AndroidPlaybackBridge.onCastSkipNext?.invoke()
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                -> AndroidPlaybackBridge.onCastSkipPrevious?.invoke()
                else -> AndroidPlaybackBridge.onCastSeekTo?.invoke(positionMs.coerceAtLeast(0L))
            }
            return Futures.immediateVoidFuture()
        }
        if (localState != null) {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                -> AndroidPlaybackBridge.onSkipNext?.invoke()
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                -> AndroidPlaybackBridge.onSkipPrevious?.invoke()
                else -> AndroidPlaybackBridge.onLocalMediaSessionSeekTo?.invoke(positionMs.coerceAtLeast(0L))
            }
            return Futures.immediateVoidFuture()
        }
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            -> {
                if (AndroidPlaybackBridge.hasNextTrack?.invoke() == true) {
                    AndroidPlaybackBridge.onSkipNext?.invoke()
                    return Futures.immediateVoidFuture()
                }
            }
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> {
                if (AndroidPlaybackBridge.hasPreviousTrack?.invoke() == true) {
                    AndroidPlaybackBridge.onSkipPrevious?.invoke()
                    return Futures.immediateVoidFuture()
                }
            }
        }
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    private fun SimpleBasePlayer.State.withMediaSessionOverride(
        track: Track,
        isPlaying: Boolean,
        isBuffering: Boolean,
        positionMs: Long,
        bufferedPositionMs: Long,
        durationMs: Long,
        playWhenReadyChangeReason: Int,
    ): SimpleBasePlayer.State {
        val playlistOverride = mediaSessionOverridePlaylist(track, durationMs)
        val position = positionMs.coerceAtLeast(0L)
        val buffered = bufferedPositionMs
            .coerceAtLeast(position)
            .let { if (durationMs > 0L) it.coerceAtMost(durationMs) else it }
        return mediaSessionOverrideBuilder()
            .setPlaylist(playlistOverride.playlist)
            .setCurrentMediaItemIndex(playlistOverride.currentIndex)
            .setCurrentAd(C.INDEX_UNSET, C.INDEX_UNSET)
            .setPlayerError(null)
            .setPlaybackState(
                when {
                    isBuffering -> Player.STATE_BUFFERING
                    else -> Player.STATE_READY
                },
            )
            .setIsLoading(isBuffering)
            .setPlayWhenReady(isPlaying, playWhenReadyChangeReason)
            .setContentPositionMs(position)
            .setContentBufferedPositionMs(SimpleBasePlayer.PositionSupplier.getConstant(buffered))
            .setTotalBufferedDurationMs(SimpleBasePlayer.PositionSupplier.ZERO)
            .build()
    }

    private fun SimpleBasePlayer.State.mediaSessionOverridePlaylist(
        track: Track,
        durationMs: Long,
    ): MediaSessionOverridePlaylist {
        val delegatePlaylist = getPlaylist()
        val currentIndex = when {
            delegatePlaylist.isEmpty() -> 0
            else -> delegatePlaylist.indexOfFirst { it.mediaItem.mediaId == track.id }
                .takeIf { it >= 0 }
                ?: currentMediaItemIndex.takeIf { it in delegatePlaylist.indices }
                ?: 0
        }
        val existing = delegatePlaylist.getOrNull(currentIndex)
        val overrideItem = mediaSessionOverrideItem(
            track = track,
            durationMs = durationMs,
            existing = existing,
        )
        if (delegatePlaylist.isEmpty()) {
            return MediaSessionOverridePlaylist(listOf(overrideItem), currentIndex)
        }
        return MediaSessionOverridePlaylist(
            playlist = delegatePlaylist.toMutableList().also { it[currentIndex] = overrideItem },
            currentIndex = currentIndex,
        )
    }

    private fun mediaSessionOverrideItem(
        track: Track,
        durationMs: Long,
        existing: SimpleBasePlayer.MediaItemData?,
    ): SimpleBasePlayer.MediaItemData {
        val mediaItem = playbackMediaItem(track, inAppPlayback = true)
        // Keep the delegate UID when present. Minting a new uid per track/index made every
        // local-state publish look like a playlist rewrite to Android Auto.
        val builder = existing?.buildUpon()
            ?: SimpleBasePlayer.MediaItemData.Builder(track.id)
        return builder
            .setMediaItem(mediaItem)
            .setMediaMetadata(mediaItem.mediaMetadata)
            .setDurationUs(durationMs.takeIf { it > 0L }?.times(1_000L) ?: C.TIME_UNSET)
            .setIsSeekable(durationMs > 0L)
            .build()
    }

    private fun SimpleBasePlayer.State.mediaSessionOverrideBuilder(): SimpleBasePlayer.State.Builder =
        SimpleBasePlayer.State.Builder()
            .setAvailableCommands(availableCommands)
            .setPlaybackSuppressionReason(playbackSuppressionReason)
            .setRepeatMode(repeatMode)
            .setShuffleModeEnabled(shuffleModeEnabled)
            .setPlaybackParameters(playbackParameters)
            .setTrackSelectionParameters(trackSelectionParameters)
            .setAudioAttributes(audioAttributes)
            .setVolume(volume)
            .setVideoSize(videoSize)
            .setCurrentCues(currentCues)
            .setDeviceInfo(deviceInfo)
            .setDeviceVolume(deviceVolume)
            .setIsDeviceMuted(isDeviceMuted)
            .setSurfaceSize(surfaceSize)
            .setTimedMetadata(timedMetadata)
            .setPlaylistMetadata(playlistMetadata)
            .setSeekBackIncrementMs(seekBackIncrementMs)
            .setSeekForwardIncrementMs(seekForwardIncrementMs)
            .setMaxSeekToPreviousPositionMs(maxSeekToPreviousPositionMs)

    private fun safeDelegateState(): SimpleBasePlayer.State {
        val player = getPlayer()
        if (player.hasInvalidEmptyPlaylistSnapshot()) {
            return player.emptyIdleStateSnapshot()
        }
        return try {
            super.getState()
        } catch (error: IllegalArgumentException) {
            if (error.message != EmptyPlaylistStateError) throw error
            player.emptyIdleStateSnapshot()
        }
    }

    private fun Player.hasInvalidEmptyPlaylistSnapshot(): Boolean {
        val state = playbackState
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) return false
        return !isCommandAvailable(Player.COMMAND_GET_TIMELINE) || currentTimeline.isEmpty
    }

    private fun Player.emptyIdleStateSnapshot(): SimpleBasePlayer.State =
        SimpleBasePlayer.State.Builder()
            .setAvailableCommands(availableCommands)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(playbackSuppressionReason)
            .setPlaybackState(Player.STATE_IDLE)
            .setIsLoading(false)
            .setPlayerError(playerError)
            .setRepeatMode(repeatMode)
            .setShuffleModeEnabled(shuffleModeEnabled)
            .setPlaybackParameters(playbackParameters)
            .setTrackSelectionParameters(trackSelectionParameters)
            .setSeekBackIncrementMs(seekBackIncrement)
            .setSeekForwardIncrementMs(seekForwardIncrement)
            .setMaxSeekToPreviousPositionMs(maxSeekToPreviousPosition)
            .setVolume(volume)
            .setTotalBufferedDurationMs(SimpleBasePlayer.PositionSupplier.ZERO)
            .build()

    private fun updateSessionState(update: () -> Unit) {
        if (Looper.myLooper() == sessionLooper) {
            update()
        } else {
            sessionHandler.post { update() }
        }
    }

    private companion object {
        private const val EmptyPlaylistStateError = "Empty playlist only allowed in STATE_IDLE or STATE_ENDED"
    }

    private data class MediaSessionOverridePlaylist(
        val playlist: List<SimpleBasePlayer.MediaItemData>,
        val currentIndex: Int,
    )
}

@OptIn(UnstableApi::class)
internal fun SimpleBasePlayer.State.withPhoebeQueueNavigationCommands(
    hasNext: Boolean,
    hasPrevious: Boolean,
): SimpleBasePlayer.State {
    if (!hasNext && !hasPrevious) return this
    val commands = availableCommands.buildUpon().apply {
        if (hasNext) {
            add(Player.COMMAND_SEEK_TO_NEXT)
            add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        if (hasPrevious) {
            add(Player.COMMAND_SEEK_TO_PREVIOUS)
            add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
    }.build()
    return buildUpon()
        .setAvailableCommands(commands)
        .build()
}
