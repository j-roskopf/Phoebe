package com.phoebe.app.player

import com.phoebe.app.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

actual fun createCastController(audioPlayer: AudioPlayer): CastController =
    IosCastControllerHolder.instance.also {
        it.bindAudioPlayer(audioPlayer)
        IosCastBridge.attach(it)
    }

private object IosCastControllerHolder {
    val instance: IosCastController by lazy { IosCastController() }
}

private data class PendingIosCastHandoff(
    val queue: List<Track>,
    val index: Int,
    val positionMs: Long,
    val requestId: Long,
)

class IosCastController : CastController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableState = MutableStateFlow(
        CastState(message = "Chromecast on iOS needs the Google Cast SDK in the host app."),
    )
    override val state: StateFlow<CastState> = mutableState

    private var audioPlayer: AudioPlayer? = null
    private var pendingHandoff: PendingIosCastHandoff? = null
    private var loadTimeoutJob: Job? = null
    private var loadRequestId = 0L

    fun bindAudioPlayer(audioPlayer: AudioPlayer) {
        this.audioPlayer = audioPlayer
    }

    override fun canLoadQueue(queue: List<Track>): CastQueueSupport =
        queue.chromecastQueueSupport()

    override fun showDevicePicker() {
        if (!IosCastBridge.showDevicePicker()) {
            mutableState.update {
                it.copy(message = "Chromecast on iOS needs the Google Cast SDK in the host app.")
            }
        }
    }

    override fun disconnect() {
        IosCastBridge.disconnect()
        disconnectState(restoreLocalPlayback = true)
    }

    override fun loadQueue(queue: List<Track>, startIndex: Int, startPositionMs: Long) {
        loadQueueInternal(queue, startIndex, startPositionMs = startPositionMs)
    }

    private fun loadQueueInternal(queue: List<Track>, startIndex: Int, startPositionMs: Long) {
        val support = canLoadQueue(queue)
        if (!support.isSupported) {
            mutableState.update { it.copy(message = support.message) }
            return
        }
        if (!IosCastBridge.hasConnectedSession()) {
            mutableState.update { it.copy(message = "Choose a Chromecast before casting.") }
            showDevicePicker()
            return
        }
        val index = startIndex.coerceIn(queue.indices)
        val track = queue[index]
        val positionMs = startPositionMs.coerceAtLeast(0L)
        loadRequestId++
        val requestId = loadRequestId
        pendingHandoff = PendingIosCastHandoff(
            queue = queue,
            index = index,
            positionMs = positionMs,
            requestId = requestId,
        )
        mutableState.update {
            it.copy(
                isAvailable = true,
                isConnected = true,
                queue = queue,
                currentIndex = index,
                isPlaying = false,
                isBuffering = true,
                positionMs = positionMs,
                durationMs = track.durationMs,
                message = null,
            )
        }
        val loaded = IosCastBridge.loadMedia(requestId, track.toCastMediaDescriptor(), positionMs)
        if (loaded) {
            scheduleLoadTimeout(requestId)
        } else {
            onCastLoadFailed(requestId, "Choose a Chromecast before casting.")
        }
    }

    override fun togglePlayPause() {
        if (IosCastBridge.togglePlayPause()) {
            mutableState.update { it.copy(isPlaying = !it.isPlaying, isBuffering = false) }
        }
    }

    override fun next() {
        val current = mutableState.value
        val target = current.currentIndex + 1
        if (target in current.queue.indices) {
            loadQueue(current.queue, target)
        }
    }

    override fun previous() {
        val current = mutableState.value
        val target = (current.currentIndex - 1).coerceAtLeast(0)
        if (target in current.queue.indices) {
            loadQueue(current.queue, target)
        }
    }

    override fun seekTo(positionMs: Long) {
        if (IosCastBridge.seekTo(positionMs)) {
            mutableState.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
        }
    }

    internal fun updateAvailability(isAvailable: Boolean, message: String?) {
        mutableState.update { it.copy(isAvailable = isAvailable, message = message) }
    }

    internal fun sessionStarted(deviceName: String?, receiverHasMedia: Boolean) {
        mutableState.update {
            it.copy(
                isAvailable = true,
                isConnected = true,
                deviceName = deviceName,
                isBuffering = false,
                message = null,
            )
        }
        if (!receiverHasMedia || localPlaybackIsActive()) {
            castCurrentLocalQueueIfPossible()
        }
    }

    internal fun sessionSuspended() {
        mutableState.update { it.copy(isBuffering = true) }
    }

    internal fun sessionStartFailed(message: String) {
        mutableState.update { it.copy(isAvailable = true, isBuffering = false, message = message) }
    }

    internal fun sessionEnded() {
        disconnectState(restoreLocalPlayback = true)
    }

    internal fun remoteMediaStatus(
        trackId: String?,
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
        streamUrl: String?,
        castUrl: String?,
        downloadUrl: String?,
        thumbUrl: String?,
        filepath: String?,
        audioCodec: String?,
        positionMs: Long,
        isPlaying: Boolean,
        isBuffering: Boolean,
        deviceName: String?,
    ) {
        val previous = mutableState.value
        val remoteTrack = if (!streamUrl.isNullOrBlank() || !castUrl.isNullOrBlank() || !title.isNullOrBlank()) {
            castTrackFromMediaFields(
                trackId = trackId,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                streamUrl = streamUrl,
                castUrl = castUrl,
                downloadUrl = downloadUrl,
                thumbUrl = thumbUrl,
                filepath = filepath,
                audioCodec = audioCodec,
            )
        } else {
            null
        }
        val previousQueueIndex = remoteTrack?.let { track ->
            previous.queue.indexOfFirst { it.matchesCastMedia(track, castUrl) }.takeIf { index -> index >= 0 }
        }
        val reusingPreviousQueue = previousQueueIndex != null
        val queue = remoteTrack?.let { track ->
            if (reusingPreviousQueue) previous.queue else listOf(track)
        } ?: previous.queue
        val currentIndex = when {
            queue.isEmpty() -> previous.currentIndex
            previousQueueIndex != null -> previousQueueIndex.coerceIn(queue.indices)
            else -> 0
        }
        mutableState.update {
            it.copy(
                isAvailable = true,
                isConnected = true,
                deviceName = deviceName ?: it.deviceName,
                queue = queue,
                currentIndex = currentIndex,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.takeIf { value -> value > 0L }
                    ?: remoteTrack?.durationMs?.takeIf { value -> value > 0L }
                    ?: it.durationMs,
                message = null,
            )
        }
        if (isPlaying) {
            suspendLocalPlayback()
        }
    }

    internal fun onCastLoadSucceeded(requestId: Long) {
        if (pendingHandoff?.requestId != requestId) return
        pendingHandoff = null
        loadTimeoutJob?.cancel()
        loadTimeoutJob = null
        suspendLocalPlayback()
        mutableState.update { it.copy(isBuffering = false, isPlaying = true, message = null) }
    }

    internal fun onCastLoadFailed(requestId: Long, message: String) {
        val handoff = pendingHandoff?.takeIf { it.requestId == requestId } ?: return
        pendingHandoff = null
        loadTimeoutJob?.cancel()
        loadTimeoutJob = null
        restoreLocalPlayback(handoff)
        mutableState.update {
            it.copy(
                queue = emptyList(),
                currentIndex = -1,
                isPlaying = false,
                isBuffering = false,
                positionMs = 0L,
                message = message,
            )
        }
    }

    private fun disconnectState(restoreLocalPlayback: Boolean) {
        loadTimeoutJob?.cancel()
        loadTimeoutJob = null
        val pending = pendingHandoff
        pendingHandoff = null
        val previous = mutableState.value
        if (restoreLocalPlayback) {
            if (pending != null) {
                restoreLocalPlayback(pending)
            } else if (previous.isConnected && previous.queue.isNotEmpty() && previous.currentIndex in previous.queue.indices) {
                audioPlayer?.play(previous.queue, previous.currentIndex)
                if (previous.positionMs > 0L) {
                    audioPlayer?.seekTo(previous.positionMs)
                }
            }
        }
        mutableState.update {
            it.copy(
                isConnected = false,
                deviceName = null,
                isPlaying = false,
                isBuffering = false,
            )
        }
    }

    private fun castCurrentLocalQueueIfPossible() {
        val localPlayer = audioPlayer ?: return
        val current = localPlayer.state.value
        val index = current.currentIndex
        if (index !in current.queue.indices) return
        val support = canLoadQueue(current.queue)
        if (!support.isSupported) {
            mutableState.update { it.copy(message = support.message) }
            return
        }
        loadQueueInternal(current.queue, index, current.positionMs)
    }

    private fun localPlaybackIsActive(): Boolean {
        val current = audioPlayer?.state?.value ?: return false
        return current.queue.isNotEmpty() &&
            current.currentIndex in current.queue.indices &&
            (current.isPlaying || current.isBuffering)
    }

    private fun restoreLocalPlayback(handoff: PendingIosCastHandoff) {
        val localPlayer = audioPlayer ?: return
        localPlayer.play(handoff.queue, handoff.index)
        if (handoff.positionMs > 0L) {
            localPlayer.seekTo(handoff.positionMs)
        }
    }

    private fun suspendLocalPlayback() {
        val localPlayer = audioPlayer ?: return
        val localState = localPlayer.state.value
        if (localState.isPlaying || localState.isBuffering) {
            localPlayer.togglePlayPause()
        }
    }

    private fun scheduleLoadTimeout(requestId: Long) {
        loadTimeoutJob?.cancel()
        loadTimeoutJob = scope.launch {
            delay(LOAD_TIMEOUT_MS)
            if (pendingHandoff?.requestId == requestId) {
                onCastLoadFailed(requestId, "Chromecast didn't respond in time. Playing on this device.")
            }
        }
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 30_000L
    }
}

/**
 * Thin bridge for the Swift/iOS host app to connect Google Cast SDK callbacks to
 * the shared Phoebe player state.
 */
object IosCastBridge {
    private var controller: IosCastController? = null
    private var latestAvailability = false
    private var latestAvailabilityMessage: String? = "Chromecast on iOS needs the Google Cast SDK in the host app."

    var onShowDevicePicker: (() -> Boolean)? = null
    var onDisconnect: (() -> Unit)? = null
    var onLoadMedia: ((requestId: Long, descriptor: CastMediaDescriptor, startPositionMs: Long) -> Boolean)? = null
    var onTogglePlayPause: (() -> Boolean)? = null
    var onSeekTo: ((positionMs: Long) -> Boolean)? = null
    var onHasConnectedSession: (() -> Boolean)? = null
    var onReadVolume: (() -> Float)? = null
    var onSetVolume: ((volume: Float) -> Boolean)? = null

    internal var onVolumeChanged: ((Float) -> Unit)? = null

    internal fun attach(controller: IosCastController) {
        this.controller = controller
        controller.updateAvailability(latestAvailability, latestAvailabilityMessage)
    }

    fun setAvailable(isAvailable: Boolean, message: String? = null) {
        latestAvailability = isAvailable
        latestAvailabilityMessage = message
        controller?.updateAvailability(isAvailable, message)
    }

    fun sessionStarted(deviceName: String?, receiverHasMedia: Boolean) {
        controller?.sessionStarted(deviceName, receiverHasMedia)
    }

    fun sessionSuspended() {
        controller?.sessionSuspended()
    }

    fun sessionStartFailed(message: String) {
        controller?.sessionStartFailed(message)
    }

    fun sessionEnded() {
        controller?.sessionEnded()
    }

    fun loadSucceeded(requestId: Long) {
        controller?.onCastLoadSucceeded(requestId)
    }

    fun loadFailed(requestId: Long, message: String) {
        controller?.onCastLoadFailed(requestId, message)
    }

    fun remoteMediaStatus(
        trackId: String?,
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
        streamUrl: String?,
        castUrl: String?,
        downloadUrl: String?,
        thumbUrl: String?,
        filepath: String?,
        audioCodec: String?,
        positionMs: Long,
        isPlaying: Boolean,
        isBuffering: Boolean,
        deviceName: String?,
    ) {
        controller?.remoteMediaStatus(
            trackId = trackId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            streamUrl = streamUrl,
            castUrl = castUrl,
            downloadUrl = downloadUrl,
            thumbUrl = thumbUrl,
            filepath = filepath,
            audioCodec = audioCodec,
            positionMs = positionMs,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            deviceName = deviceName,
        )
    }

    fun castVolumeChanged(volume: Float) {
        onVolumeChanged?.invoke(volume.coerceIn(0f, 1f))
    }

    fun isCasting(): Boolean = controller?.state?.value?.isConnected == true

    fun showDevicePicker(): Boolean = onShowDevicePicker?.invoke() == true

    fun disconnect() {
        onDisconnect?.invoke()
    }

    fun hasConnectedSession(): Boolean = onHasConnectedSession?.invoke() == true

    fun loadMedia(requestId: Long, descriptor: CastMediaDescriptor, startPositionMs: Long): Boolean =
        onLoadMedia?.invoke(requestId, descriptor, startPositionMs) == true

    fun togglePlayPause(): Boolean = onTogglePlayPause?.invoke() == true

    fun seekTo(positionMs: Long): Boolean =
        onSeekTo?.invoke(positionMs.coerceAtLeast(0L)) == true

    fun readCastVolume(): Float? = onReadVolume?.invoke()?.coerceIn(0f, 1f)

    fun setCastVolume(volume: Float): Boolean =
        onSetVolume?.invoke(volume.coerceIn(0f, 1f)) == true
}
