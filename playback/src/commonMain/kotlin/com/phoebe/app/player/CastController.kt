package com.phoebe.app.player

import com.phoebe.app.domain.PlayerState
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.isLocalMediaPlayback
import com.phoebe.app.domain.isPlexLibraryTrack
import com.phoebe.app.domain.isRemoteLibraryTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CastState(
    val isAvailable: Boolean = false,
    val isConnected: Boolean = false,
    val deviceName: String? = null,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float? = null,
    val message: String? = null,
) {
    val currentTrack: Track? get() = queue.getOrNull(currentIndex)
}

val CastState.isPlaybackActive: Boolean
    get() = isConnected && queue.isNotEmpty()

data class CastQueueSupport(
    val isSupported: Boolean,
    val message: String? = null,
) {
    companion object {
        fun supported(): CastQueueSupport = CastQueueSupport(isSupported = true)
        fun unsupported(message: String): CastQueueSupport =
            CastQueueSupport(isSupported = false, message = message)
    }
}

interface CastController {
    val state: StateFlow<CastState>
    fun canLoadQueue(queue: List<Track>): CastQueueSupport
    fun showDevicePicker()
    fun disconnect()
    fun loadQueue(queue: List<Track>, startIndex: Int = 0, startPositionMs: Long = 0L)
    fun togglePlayPause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun readVolume(): Float? = state.value.volume
    fun setVolume(volume: Float): Boolean = false
}

open class UnavailableCastController(
    private val unavailableMessage: String = "Chromecast is not available on this platform.",
    surfaceInitialMessage: Boolean = true,
) : CastController {
    private val mutableState = MutableStateFlow(
        CastState(message = unavailableMessage.takeIf { surfaceInitialMessage }),
    )
    override val state: StateFlow<CastState> = mutableState

    override fun showDevicePicker() {
        mutableState.value = mutableState.value.copy(message = unavailableMessage)
    }

    override fun disconnect() = Unit
    override fun canLoadQueue(queue: List<Track>): CastQueueSupport =
        CastQueueSupport.unsupported(unavailableMessage)
    override fun loadQueue(queue: List<Track>, startIndex: Int, startPositionMs: Long) = showDevicePicker()
    override fun togglePlayPause() = Unit
    override fun next() = Unit
    override fun previous() = Unit
    override fun seekTo(positionMs: Long) = Unit
}

fun Track.isChromecastPlayable(): Boolean =
    isPlexLibraryTrack() && toCastMediaDescriptor().castUrl.isCastReceiverLoadableUrl()

fun List<Track>.isChromecastPlayableQueue(): Boolean = isNotEmpty() && all { it.isChromecastPlayable() }

fun List<Track>.plexChromecastQueueSupport(): CastQueueSupport =
    if (isChromecastPlayableQueue()) {
        CastQueueSupport.supported()
    } else {
        CastQueueSupport.unsupported("Chromecast can play Plex streaming songs only.")
    }

fun Track.isRemoteChromecastPlayable(): Boolean =
    isRemoteLibraryTrack() && toCastMediaDescriptor().castUrl.isCastReceiverLoadableUrl()

fun List<Track>.remoteChromecastQueueSupport(): CastQueueSupport {
    if (isEmpty()) {
        return CastQueueSupport.unsupported("Choose songs before casting to Chromecast.")
    }
    return if (all { it.isRemoteChromecastPlayable() }) {
        CastQueueSupport.supported()
    } else {
        CastQueueSupport.unsupported(RemoteChromecastQueueMessage)
    }
}

fun String.isCastReceiverLoadableUrl(): Boolean {
    val value = trim()
    if (value.isBlank()) return false
    if (value.startsWith("phoebe-web-", ignoreCase = true)) return false
    return value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)
}

const val RemoteChromecastQueueMessage = "Chromecast can play remote streaming songs only."

fun CastState.asPlayerState(fallback: PlayerState): PlayerState =
    fallback.copy(
        queue = queue,
        currentIndex = currentIndex,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        positionMs = positionMs,
        bufferedPositionMs = fallback.bufferedPositionMs,
        durationMs = durationMs.takeIf { it > 0L } ?: currentTrack?.durationMs ?: fallback.durationMs,
        volume = volume ?: fallback.volume,
    )

expect fun createCastController(audioPlayer: AudioPlayer): CastController
