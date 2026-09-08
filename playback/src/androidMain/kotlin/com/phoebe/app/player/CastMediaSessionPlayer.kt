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
            .withRadioLiveTransport()
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
            .withRadioLiveTransport()
            .withPhoebeQueueNavigationCommands(
                hasNext = AndroidPlaybackBridge.hasNextTrack?.invoke() == true,
                hasPrevious = AndroidPlaybackBridge.hasPreviousTrack?.invoke() == true,
            )
        }
        // Routine local playback no longer overlays LocalMediaSessionState (that rebuild
        // flashed Android Auto). Still promote catalog duration into seekability so AA
        // keeps its Now Playing scrubber when ExoPlayer has not yet marked the item seekable.
        // Radio streams take the opposite path: hide seek and disable skip.
        return delegateState
            .withCatalogSeekability()
            .withRadioLiveTransport()
            .withPhoebeQueueNavigationCommands(
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
        // Crossfade overlay: Phoebe owns transport until the ramp commits.
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
                // Prefer a native Media3 seek whenever the session playlist already has a
                // next item. Phoebe next()/play() is only for advancing past the platform
                // window (or wrapping repeat) — that path is what kicks Android Auto off
                // the song-detail page.
                if (hasNextMediaItem()) {
                    return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
                }
                if (AndroidPlaybackBridge.hasNextTrack?.invoke() == true) {
                    AndroidPlaybackBridge.onSkipNext?.invoke()
                    return Futures.immediateVoidFuture()
                }
            }
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> {
                if (hasPreviousMediaItem()) {
                    return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
                }
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
        if (delegatePlaylist.isEmpty()) {
            return MediaSessionOverridePlaylist(
                playlist = listOf(mediaSessionOverrideItem(track, durationMs, existing = null)),
                currentIndex = currentIndex,
            )
        }
        val existing = delegatePlaylist[currentIndex]
        // Keep the existing MediaItemData when the mediaId already matches. Rebuilding via
        // playbackMediaItem() every publish creates a new MediaItem instance and makes
        // Media3 report a playlist change — that flashes / dismisses Android Auto Now Playing.
        if (existing.mediaItem.mediaId == track.id) {
            val durationUs = durationMs.takeIf { it > 0L }?.times(1_000L) ?: C.TIME_UNSET
            val needsSeekabilityPatch = durationUs != C.TIME_UNSET && (
                existing.durationUs != durationUs ||
                    !existing.isSeekable ||
                    existing.isDynamic ||
                    existing.liveConfiguration != null
                )
            val item = if (needsSeekabilityPatch) {
                existing.buildUpon()
                    .setDurationUs(durationUs)
                    .setIsSeekable(true)
                    .setIsDynamic(false)
                    .setLiveConfiguration(null)
                    .build()
            } else {
                existing
            }
            return MediaSessionOverridePlaylist(
                playlist = if (item === existing) {
                    delegatePlaylist
                } else {
                    delegatePlaylist.toMutableList().also { it[currentIndex] = item }
                },
                currentIndex = currentIndex,
            )
        }
        val overrideItem = mediaSessionOverrideItem(track, durationMs, existing)
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
            .setIsDynamic(false)
            .setLiveConfiguration(null)
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

/**
 * Android Auto hides the Now Playing seek bar unless the legacy session exposes
 * ACTION_SEEK_TO. Media3 only publishes that when the current item is seekable and not live.
 * Catalog metadata already knows the track length; use it when ExoPlayer still reports
 * TIME_UNSET / not-seekable (common for progressive Plex/HTTP streams).
 *
 * Media3's legacy stub also strips ACTION_SEEK_TO whenever [Player.isCurrentMediaItemLive]
 * is true (`liveConfiguration != null`) — even if we already forced seekable + duration.
 * Fresh HLS windows often look live until the playlist resolves as VOD, which is why the
 * scrubber can show on song 1 and vanish after a skip. Clear liveConfiguration here too.
 */
@OptIn(UnstableApi::class)
internal fun SimpleBasePlayer.State.withCatalogSeekability(): SimpleBasePlayer.State {
    val playlist = getPlaylist()
    if (playlist.isEmpty()) return this
    val index = currentMediaItemIndex.takeIf { it in playlist.indices } ?: return this
    val item = playlist[index]
    if (item.mediaItem.mediaId.startsWith("radio:")) return this
    val metadataDurationMs = item.mediaMetadata?.durationMs
        ?: item.mediaItem.mediaMetadata.durationMs
    val bridgeDurationMs = AndroidPlaybackBridge.currentTrack?.invoke()
        ?.takeIf { it.id == item.mediaItem.mediaId }
        ?.durationMs
        ?.takeIf { it > 0L }
    val knownDurationUs = when {
        item.durationUs != C.TIME_UNSET && item.durationUs > 0L -> item.durationUs
        metadataDurationMs != null && metadataDurationMs > 0L -> metadataDurationMs * 1_000L
        bridgeDurationMs != null -> bridgeDurationMs * 1_000L
        else -> C.TIME_UNSET
    }
    if (knownDurationUs == C.TIME_UNSET) return this

    val needsItemPatch = item.durationUs != knownDurationUs ||
        !item.isSeekable ||
        item.isDynamic ||
        item.liveConfiguration != null
    val needsSeekCommand = !availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    if (!needsItemPatch && !needsSeekCommand) return this

    val patchedPlaylist = if (needsItemPatch) {
        playlist.toMutableList().also { items ->
            items[index] = item.buildUpon()
                .setDurationUs(knownDurationUs)
                .setIsSeekable(true)
                .setIsDynamic(false)
                .setLiveConfiguration(null)
                .build()
        }
    } else {
        playlist
    }
    val commands = if (needsSeekCommand) {
        availableCommands.buildUpon()
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            .build()
    } else {
        availableCommands
    }
    return buildUpon()
        .setPlaylist(patchedPlaylist)
        .setAvailableCommands(commands)
        .build()
}

/**
 * Live internet radio: hide the seek bar and strip skip commands so AA does not imply a queue.
 */
@OptIn(UnstableApi::class)
internal fun SimpleBasePlayer.State.withRadioLiveTransport(): SimpleBasePlayer.State {
    val playlist = getPlaylist()
    if (playlist.isEmpty()) return this
    val index = currentMediaItemIndex.takeIf { it in playlist.indices } ?: return this
    val item = playlist[index]
    val mediaId = item.mediaItem.mediaId
    val bridgeRadio = AndroidPlaybackBridge.currentTrack?.invoke()?.id?.startsWith("radio:") == true
    if (!mediaId.startsWith("radio:") && !bridgeRadio) return this

    val patchedItem = item.buildUpon()
        .setIsSeekable(false)
        .setIsDynamic(true)
        .setDurationUs(C.TIME_UNSET)
        .setLiveConfiguration(MediaItem.LiveConfiguration.UNSET)
        .build()
    val patchedPlaylist = playlist.toMutableList().also { it[index] = patchedItem }
    val commands = availableCommands.buildUpon()
        .remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        .remove(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
        .remove(Player.COMMAND_SEEK_TO_NEXT)
        .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
        .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .build()
    return buildUpon()
        .setPlaylist(patchedPlaylist)
        .setAvailableCommands(commands)
        .build()
}
