package com.phoebe.app.player

import com.phoebe.app.domain.PlayerState
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.isLocalMediaPlayback
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
    fun canLoadQueue(queue: List<Track>, startIndex: Int): CastQueueSupport = canLoadQueue(queue)
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

/**
 * True when a receiver can fetch this track on its own.
 *
 * The test is the URL, not which library the row came from: an absolute http(s) stream is
 * loadable whether it was built by Plex, Jellyfin, Emby, Subsonic/Navidrome, or an internet
 * radio station. Gating on the catalog prefix instead is what refused Navidrome queues on
 * mobile and radio everywhere, even though those URLs cast fine.
 */
fun Track.isChromecastPlayable(): Boolean =
    toCastMediaDescriptor().castUrl.isCastReceiverLoadableUrl()

fun List<Track>.isChromecastPlayableQueue(): Boolean = isNotEmpty() && all { it.isChromecastPlayable() }

fun List<Track>.chromecastQueueSupport(): CastQueueSupport {
    if (isEmpty()) return CastQueueSupport.unsupported(EmptyChromecastQueueMessage)
    val blocked = firstOrNull { !it.isChromecastPlayable() } ?: return CastQueueSupport.supported()
    return CastQueueSupport.unsupported(blocked.chromecastQueueBlockedMessage())
}

/**
 * Name the song holding the queue back. Callers may validate a receiver window rather than the
 * entire device queue, so this identifies the first item in the attempted window.
 */
private fun Track.chromecastQueueBlockedMessage(): String {
    val name = title.takeIf { it.isNotBlank() } ?: "This song"
    return if (isLocalMediaPlayback()) {
        "“$name” plays from this device, so it can't be cast."
    } else {
        "“$name” has no streaming address to cast."
    }
}

fun String.isCastReceiverLoadableUrl(): Boolean {
    val value = trim()
    if (value.isBlank()) return false
    if (value.startsWith("phoebe-web-", ignoreCase = true)) return false
    return value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)
}

const val EmptyChromecastQueueMessage = "Choose songs before casting to Chromecast."

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
