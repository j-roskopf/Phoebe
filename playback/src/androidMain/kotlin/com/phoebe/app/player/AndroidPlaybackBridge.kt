package com.phoebe.app.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import com.phoebe.app.domain.Track

/**
 * Process-wide glue between [PlaybackService] (ExoPlayer + MediaLibrarySession) and
 * [AndroidAudioPlayer] (shared queue logic in [SimpleAudioPlayer]).
 */
object AndroidPlaybackBridge {
    var onSkipNext: (() -> Unit)? = null
    var onSkipPrevious: (() -> Unit)? = null
    var hasNextTrack: (() -> Boolean)? = null
    var hasPreviousTrack: (() -> Boolean)? = null
    var onTrackEnded: (() -> Unit)? = null
    var onServicePlayerChanged: (() -> Unit)? = null
    var onPlayQueue: ((List<Track>, Int) -> Unit)? = null
    var onAdoptQueue: ((List<Track>, Int, Boolean) -> Unit)? = null
    var onToggleLikedTrack: (suspend (Track) -> Unit)? = null
    var isLikeAvailable: ((Track) -> Boolean)? = null
    var isTrackLiked: ((Track) -> Boolean)? = null
    var isCastActive: (() -> Boolean)? = null
    var onCastTogglePlayPause: (() -> Unit)? = null
    var onCastPlay: (() -> Unit)? = null
    var onCastPause: (() -> Unit)? = null
    var onCastSkipNext: (() -> Unit)? = null
    var onCastSkipPrevious: (() -> Unit)? = null
    var onCastSeekTo: ((Long) -> Unit)? = null
    var onEnsureLocalPlaybackPaused: (() -> Unit)? = null
    var onLocalMediaSessionPlay: (() -> Unit)? = null
    var onLocalMediaSessionPause: (() -> Unit)? = null
    var onLocalMediaSessionSeekTo: ((Long) -> Unit)? = null
    var readCastVolume: (() -> Float)? = null
    var applyCastVolume: ((Float) -> Unit)? = null
    var adjustCastVolumeStep: ((up: Boolean) -> Boolean)? = null
    var onCastVolumeChanged: ((Float) -> Unit)? = null
    var onCastDisconnect: (() -> Unit)? = null
    var onCastMediaSessionState: ((CastMediaSessionState?) -> Unit)? = null
    var onLocalMediaSessionState: ((LocalMediaSessionState?) -> Unit)? = null

    @Volatile
    var suppressServiceEndedCallback: Boolean = false

    @Volatile
    private var suspendingLocalPlayback = false

    @Volatile
    var servicePlayer: Player? = null
        private set

    @Volatile
    private var servicePlaybackActive = false

    fun isServicePlaybackActive(): Boolean = servicePlaybackActive

    fun updateServicePlayerState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { updateServicePlayerState() }
            return
        }
        val player = servicePlayer ?: run {
            servicePlaybackActive = false
            return
        }
        servicePlaybackActive = player.isPlaying || player.playWhenReady
    }

    /** Stop audible output from the foreground [PlaybackService] player immediately. */
    fun pauseLocalPlaybackImmediately() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { pauseLocalPlaybackImmediately() }
            return
        }
        if (suspendingLocalPlayback) return
        val player = servicePlayer ?: run {
            onEnsureLocalPlaybackPaused?.invoke()
            servicePlaybackActive = false
            return
        }
        if (!player.isPlaying && !player.playWhenReady) {
            updateServicePlayerState()
            onEnsureLocalPlaybackPaused?.invoke()
            return
        }
        suspendingLocalPlayback = true
        try {
            player.playWhenReady = false
            if (player.isPlaying) {
                player.pause()
            }
            updateServicePlayerState()
            onEnsureLocalPlaybackPaused?.invoke()
        } finally {
            suspendingLocalPlayback = false
        }
    }

    fun attachServicePlayer(player: Player, listener: Player.Listener) {
        servicePlayer?.removeListener(listener)
        servicePlayer = player
        player.addListener(listener)
        updateServicePlayerState()
    }

    fun detachServicePlayer(listener: Player.Listener) {
        servicePlayer?.removeListener(listener)
        servicePlayer = null
        servicePlaybackActive = false
    }
}

data class CastMediaSessionState(
    val track: Track,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

data class LocalMediaSessionState(
    val track: Track,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val positionMs: Long,
    val bufferedPositionMs: Long,
    val durationMs: Long,
)
