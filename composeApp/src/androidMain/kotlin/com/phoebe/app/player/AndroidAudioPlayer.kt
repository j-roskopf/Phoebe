package com.phoebe.app.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.phoebe.app.AndroidContextHolder
import com.phoebe.app.data.PlexClient
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.PhoebeLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual fun createAudioPlayer(): AudioPlayer = AndroidAudioPlayerHolder.instance

internal object AndroidAudioPlayerHolder {
    private val player: AndroidAudioPlayer by lazy { AndroidAudioPlayer() }

    val instance: AudioPlayer
        get() = player

    fun ensureConnected() {
        player.ensureConnected()
    }
}

private class AndroidAudioPlayer : SimpleAudioPlayer() {
    override val useProgressTicker: Boolean = false

    private val appContext: Context
        get() = AndroidContextHolder.application

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var positionSyncJob: Job? = null
    private var platformLoadJob: Job? = null
    private var seekJob: Job? = null
    private var bufferingTimeoutJob: Job? = null
    private var fullTrackBufferJob: Job? = null
    private var retryJob: Job? = null
    private var crossfadeJob: Job? = null
    private var crossfadePlayer: ExoPlayer? = null
    private var crossfadeGeneration = -1
    private var crossfadeOwnedTrackId: String? = null
    private var retryGeneration = -1
    private var retryCount = 0
    private val controllerMutex = Mutex()
    private var loadedQueueIds: List<String>? = null

    private val controllerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncFromController()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncFromController()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncFromController()
        }

        override fun onPlayerError(error: PlaybackException) {
            PhoebeLog.d("AndroidAudioPlayer") { "playback failed: ${error.message}" }
            stopBufferingTimeout()
            schedulePlaybackRetry(error, activePlayGeneration)
            stopPositionSyncLoop()
        }
    }

    init {
        AndroidPlaybackBridge.onSkipNext = { next() }
        AndroidPlaybackBridge.onSkipPrevious = { previous() }
        AndroidPlaybackBridge.onTrackEnded = { next() }
        AndroidPlaybackBridge.onPlayQueue = { queue, index -> play(queue, index) }
        AndroidPlaybackBridge.onAdoptQueue = { queue, index, playing ->
            loadedQueueIds = queue.map { it.id }
            adoptQueueState(queue, index, playing)
        }
        AndroidPlaybackBridge.onEnsureLocalPlaybackPaused = { forceLocalPlaybackPaused() }
        scope.launch { ensureController() }
    }

    fun ensureConnected() {
        if (controller == null) {
            scope.launch { ensureController() }
        }
    }

    override fun skipToInQueueOnPlatform(
        queue: List<Track>,
        startIndex: Int,
        track: Track,
        generation: Int,
    ) {
        runPlatformLoad(generation) { player ->
            val targetIndex = startIndex.coerceIn(queue.indices)
            val queueIds = queue.map { it.id }
            if (loadedQueueIds == queueIds &&
                player.mediaItemCount == queue.size &&
                targetIndex < player.mediaItemCount
            ) {
                startFullTrackBufferProbe(track, generation)
                player.pause()
                player.seekTo(targetIndex, 0L)
                player.volume = effectiveOutputVolume()
                if (playWhenReady) {
                    player.play()
                }
            } else {
                loadQueueOnPlayer(player, queue, targetIndex, queueIds, generation)
            }
        }
    }

    override fun playQueueOnPlatform(
        queue: List<Track>,
        startIndex: Int,
        track: Track,
        generation: Int,
    ) {
        runPlatformLoad(generation) { player ->
            loadQueueOnPlayer(player, queue, startIndex.coerceIn(queue.indices), queue.map { it.id }, generation)
        }
    }

    override fun stopCurrentPlaybackImmediately() {
        platformLoadJob?.cancel()
        platformLoadJob = null
        stopAndroidCrossfade()
        stopBufferingTimeout()
        stopFullTrackBufferProbe()
        stopRetry()
        loadedQueueIds = null
        scope.launch {
            controllerMutex.withLock {
                controller?.run {
                    pause()
                    stop()
                    clearMediaItems()
                }
            }
        }
    }

    override fun pause() {
        scope.launch {
            val ownedPlayer = ownedCrossfadePlayer()
            if (ownedPlayer != null) {
                ownedPlayer.pause()
                syncFromCrossfadePlayer(ownedPlayer)
            } else {
                controllerMutex.withLock { controller?.pause() }
                syncFromController()
            }
        }
    }

    override fun resume() {
        scope.launch {
            val ownedPlayer = ownedCrossfadePlayer()
            if (ownedPlayer != null) {
                ownedPlayer.volume = effectiveOutputVolume()
                ownedPlayer.play()
                syncFromCrossfadePlayer(ownedPlayer)
            } else {
                controllerMutex.withLock {
                    controller?.run {
                        volume = effectiveOutputVolume()
                        play()
                    }
                }
                syncFromController()
            }
        }
    }

    override fun seek(positionMs: Long) {
        seekJob?.cancel()
        val generation = activePlayGeneration
        seekJob = scope.launch {
            val ownedPlayer = ownedCrossfadePlayer()
            if (ownedPlayer != null) {
                if (!isPlayRequestCurrent(generation)) return@launch
                ownedPlayer.seekTo(positionMs)
                syncFromCrossfadePlayer(ownedPlayer, generation)
            } else {
                controllerMutex.withLock {
                    if (!isPlayRequestCurrent(generation)) return@withLock
                    controller?.seekTo(positionMs)
                }
                syncFromController(generation)
            }
        }
    }

    override fun setOutputVolume(volume: Float) {
        scope.launch {
            val ownedPlayer = ownedCrossfadePlayer()
            if (ownedPlayer != null) {
                ownedPlayer.volume = volume
                return@launch
            }
            controllerMutex.withLock { controller?.volume = volume }
        }
    }

    override fun startCrossfadeOnPlatform(
        queue: List<Track>,
        targetIndex: Int,
        track: Track,
        durationMs: Long,
        baseVolume: Float,
        generation: Int,
    ): Boolean {
        if (crossfadeGeneration == generation) return true
        if (targetIndex !in queue.indices) return false
        val ownedOutgoing = ownedCrossfadePlayer()
        if (ownedOutgoing == null && controller == null) return false
        crossfadeGeneration = generation
        crossfadeJob?.cancel()
        crossfadeJob = scope.launch {
            var incoming: ExoPlayer? = null
            var incomingOwnedByPlayback = false
            var suppressingServiceEndedCallback = false
            var fallbackToNormalPlayback = false
            try {
                val outgoingOwnedByPlayback = ownedCrossfadePlayer()
                val outgoing: Player = outgoingOwnedByPlayback ?: controller ?: return@launch
                incoming = ExoPlayer.Builder(appContext)
                    .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ false)
                    .build()
                incoming.volume = 0f
                incoming.setMediaItem(playbackMediaItem(track, inAppPlayback = true))
                incoming.prepare()
                incoming.play()
                if (!waitUntilReady(incoming, generation, CrossfadePrepareTimeoutMs)) return@launch
                if (!isPlayRequestCurrent(generation)) return@launch
                if (outgoingOwnedByPlayback == null && controller !== outgoing) return@launch
                if (outgoingOwnedByPlayback != null && crossfadePlayer !== outgoingOwnedByPlayback) return@launch

                val remainingMs = outgoing.duration
                    .takeIf { it > 0L }
                    ?.let { duration -> duration - outgoing.currentPosition.coerceAtLeast(0L) }
                    ?: durationMs
                val fadeDurationMs = remainingMs
                    .coerceAtMost(durationMs)
                    .coerceAtLeast(CrossfadeMinimumFadeMs)
                AndroidPlaybackBridge.suppressServiceEndedCallback = true
                suppressingServiceEndedCallback = true
                fadeVolumes(outgoing, incoming, fadeDurationMs, baseVolume, generation)
                if (!isPlayRequestCurrent(generation)) return@launch
                if (outgoingOwnedByPlayback == null && controller !== outgoing) return@launch
                if (outgoingOwnedByPlayback != null && crossfadePlayer !== outgoingOwnedByPlayback) return@launch

                if (outgoingOwnedByPlayback != null) {
                    outgoing.pause()
                    outgoing.volume = 0f
                    outgoingOwnedByPlayback.release()
                } else {
                    controllerMutex.withLock {
                        if (!isPlayRequestCurrent(generation) || controller !== outgoing) return@withLock
                        outgoing.pause()
                        outgoing.volume = 0f
                    }
                }
                if (!isPlayRequestCurrent(generation)) return@launch
                incoming.volume = effectiveOutputVolume()
                incomingOwnedByPlayback = true
                crossfadePlayer = incoming
                crossfadeOwnedTrackId = track.id
                adoptCrossfadeTarget(queue, targetIndex, incoming.currentPosition.coerceAtLeast(0L), generation)
                startFullTrackBufferProbe(track, generation)
                startCrossfadeOwnedSync(incoming, queue, targetIndex, generation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                PhoebeLog.d("AndroidAudioPlayer") { "android crossfade failed: ${error.message}" }
                fallbackToNormalPlayback = suppressingServiceEndedCallback
            } finally {
                if (suppressingServiceEndedCallback) {
                    AndroidPlaybackBridge.suppressServiceEndedCallback = false
                }
                if (!incomingOwnedByPlayback) {
                    incoming?.release()
                    if (crossfadePlayer === incoming) crossfadePlayer = null
                }
                if (crossfadeGeneration == generation) crossfadeGeneration = -1
                if (fallbackToNormalPlayback && isPlayRequestCurrent(generation)) {
                    scope.launch { play(queue, targetIndex) }
                }
            }
        }
        return true
    }

    override fun playUri(uri: String) {
        if (uri.isBlank()) return
        val generation = activePlayGeneration
        runPlatformLoad(generation) { player ->
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            if (playWhenReady) {
                player.play()
            }
        }
    }

    private fun forceLocalPlaybackPaused() {
        cancelPlayIntent()
        stopPositionSyncLoop()
        stopBufferingTimeout()
        val current = state.value
        val positionMs = AndroidPlaybackBridge.servicePlayer?.currentPosition?.coerceAtLeast(0L)
            ?: current.positionMs
        applyPlatformPlayback(
            positionMs = positionMs,
            durationMs = current.durationMs,
            isPlaying = false,
            isBuffering = false,
        )
    }

    private fun stopAndroidCrossfade() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        crossfadeGeneration = -1
        crossfadeOwnedTrackId = null
        AndroidPlaybackBridge.suppressServiceEndedCallback = false
        crossfadePlayer?.release()
        crossfadePlayer = null
    }

    private fun ownedCrossfadePlayer(): ExoPlayer? =
        crossfadePlayer?.takeIf { crossfadeOwnedTrackId != null }

    private suspend fun waitUntilReady(player: Player, generation: Int, timeoutMs: Long): Boolean {
        var waitedMs = 0L
        while (waitedMs < timeoutMs && isPlayRequestCurrent(generation)) {
            if (player.playbackState == Player.STATE_READY && player.playWhenReady) return true
            if (player.playbackState == Player.STATE_ENDED) return false
            delay(50)
            waitedMs += 50
        }
        return false
    }

    private suspend fun fadeVolumes(
        outgoing: Player,
        incoming: Player,
        durationMs: Long,
        baseVolume: Float,
        generation: Int,
    ) {
        val stepDelayMs = (durationMs / CrossfadeSteps).coerceAtLeast(16L)
        repeat(CrossfadeSteps) { index ->
            if (!isPlayRequestCurrent(generation)) return
            val progress = (index + 1).toFloat() / CrossfadeSteps.toFloat()
            outgoing.volume = (baseVolume * (1f - progress)).coerceIn(0f, 1f)
            incoming.volume = (baseVolume * progress).coerceIn(0f, 1f)
            delay(stepDelayMs)
        }
    }

    private fun startCrossfadeOwnedSync(
        player: Player,
        queue: List<Track>,
        targetIndex: Int,
        generation: Int,
    ) {
        stopPositionSyncLoop()
        positionSyncJob = scope.launch {
            while (isActive && isPlayRequestCurrent(generation) && crossfadePlayer === player) {
                val positionMs = player.currentPosition.coerceAtLeast(0L)
                applyPlatformPlayback(
                    positionMs = positionMs,
                    durationMs = player.duration.coerceAtLeast(queue.getOrNull(targetIndex)?.durationMs ?: 0L),
                    isPlaying = player.isPlaying,
                    isBuffering = player.playbackState == Player.STATE_BUFFERING,
                    bufferedPositionMs = player.bufferedPosition.coerceAtLeast(positionMs).coerceAtLeast(0L),
                    generation = generation,
                )
                if (player.playbackState == Player.STATE_ENDED) {
                    next()
                    break
                }
                delay(250)
            }
        }
    }

    private fun syncFromCrossfadePlayer(
        player: Player,
        generation: Int = activePlayGeneration,
    ) {
        if (!isPlayRequestCurrent(generation) || crossfadePlayer !== player) return
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        applyPlatformPlayback(
            positionMs = positionMs,
            durationMs = player.duration.coerceAtLeast(state.value.currentTrack?.durationMs ?: 0L),
            isPlaying = player.isPlaying && player.playbackState != Player.STATE_BUFFERING,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(positionMs).coerceAtLeast(0L),
            generation = generation,
        )
    }

    private fun runPlatformLoad(generation: Int, block: suspend (Player) -> Unit) {
        platformLoadJob?.cancel()
        stopAndroidCrossfade()
        seekJob?.cancel()
        stopBufferingTimeout()
        stopRetry()
        resetRetries(generation)
        platformLoadJob = scope.launch {
            try {
                startPlaybackService()
                ensureController()
                controllerMutex.withLock {
                    val player = controller ?: return@withLock
                    if (!isPlayRequestCurrent(generation)) return@withLock
                    block(player)
                    if (isPlayRequestCurrent(generation)) {
                        syncFromController(generation)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                PhoebeLog.d("AndroidAudioPlayer") { "platform load failed: ${error.message}" }
                stopBufferingTimeout()
                markPlaybackFailed(generation)
            }
        }
    }

    private fun loadQueueOnPlayer(
        player: Player,
        queue: List<Track>,
        targetIndex: Int,
        queueIds: List<String>,
        generation: Int,
    ) {
        player.pause()
        player.stop()
        player.clearMediaItems()
        player.volume = effectiveOutputVolume()
        player.setMediaItems(queue.map { playbackMediaItem(it, inAppPlayback = true) }, targetIndex, 0L)
        player.prepare()
        loadedQueueIds = queueIds
        queue.getOrNull(targetIndex)?.let { startFullTrackBufferProbe(it, generation) }
        if (playWhenReady) {
            player.play()
        }
    }

    private fun startFullTrackBufferProbe(track: Track, generation: Int) {
        stopFullTrackBufferProbe()
        val durationMs = track.durationMs.takeIf { it > 0L } ?: return
        val uri = track.localUri ?: track.streamUrl
        if (uri.isBlank()) return
        if (!uri.startsWith("http://", ignoreCase = true) && !uri.startsWith("https://", ignoreCase = true)) {
            updateBufferedPosition(durationMs, generation)
            return
        }
        val estimatedBitrateBytesPerSecond = ((track.bitrateKbps ?: FallbackBitrateKbps).coerceAtLeast(64) * 1_000L) / 8L
        fullTrackBufferJob = scope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            var temp: File? = null
            try {
                connection = (URL(uri).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 45_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Phoebe/0.1.0 (https://github.com/phoebe)")
                    setRequestProperty("X-Plex-Client-Identifier", PlexClient.ClientIdentifier)
                }
                val status = connection.responseCode
                if (status !in 200..299) return@launch
                val contentLength = connection.contentLengthLong.takeIf { it > 0L }
                val buffer = ByteArray(64 * 1024)
                var bytesReadTotal = 0L
                var lastReportMs = 0L
                temp = File.createTempFile("phoebe-android-buffer-", ".audio", appContext.cacheDir)
                connection.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        while (isActive && isPlayRequestCurrent(generation)) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            bytesReadTotal += read
                            val bufferedMs = if (contentLength != null) {
                                val bufferedMs = ((bytesReadTotal.toDouble() / contentLength.toDouble()) * durationMs)
                                    .toLong()
                                    .coerceIn(0L, durationMs)
                                bufferedMs
                            } else {
                                ((bytesReadTotal * 1_000L) / estimatedBitrateBytesPerSecond)
                                    .coerceIn(0L, durationMs)
                            }
                            if (bufferedMs - lastReportMs >= BufferProbeReportIntervalMs || bufferedMs == durationMs) {
                                lastReportMs = bufferedMs
                                updateBufferedPosition(bufferedMs, generation)
                            }
                        }
                    }
                }
                if (isActive && isPlayRequestCurrent(generation)) {
                    updateBufferedPosition(durationMs, generation)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                PhoebeLog.d("AndroidAudioPlayer") { "full-track buffer probe stopped: ${error.message}" }
            } finally {
                connection?.disconnect()
                temp?.delete()
            }
        }
    }

    private fun stopFullTrackBufferProbe() {
        fullTrackBufferJob?.cancel()
        fullTrackBufferJob = null
    }

    private fun startPlaybackService() {
        appContext.startService(
            Intent(appContext, PlaybackService::class.java),
        )
    }

    private suspend fun ensureController() {
        if (controller != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val connected = MediaController.Builder(appContext, token).buildAsync().await()
        connected.addListener(controllerListener)
        controller = connected
        syncFromController()
    }

    private fun syncFromController(generation: Int = activePlayGeneration) {
        if (!isPlayRequestCurrent(generation)) return
        if (crossfadePlayer != null && crossfadeOwnedTrackId != null) return
        val player = controller ?: return
        val appState = state.value
        val controllerIndex = player.currentMediaItemIndex
        if (appState.currentIndex >= 0 &&
            controllerIndex >= 0 &&
            controllerIndex != appState.currentIndex
        ) {
            val queueIds = appState.queue.map { it.id }
            if (loadedQueueIds == queueIds && controllerIndex in appState.queue.indices) {
                adoptQueueState(appState.queue, controllerIndex, player.isPlaying)
            } else {
                return
            }
        }
        val controllerPosition = player.currentPosition.coerceAtLeast(0L)
        if (appState.isBuffering &&
            appState.positionMs == 0L &&
            controllerPosition > 1_500L
        ) {
            return
        }
        val buffering = player.playbackState == Player.STATE_BUFFERING
        applyPlatformPlayback(
            positionMs = controllerPosition,
            durationMs = player.duration.coerceAtLeast(0L),
            isPlaying = player.isPlaying && !buffering,
            isBuffering = buffering,
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(controllerPosition).coerceAtLeast(0L),
            generation = generation,
        )
        if (player.isPlaying && playWhenReady) {
            stopBufferingTimeout()
            resetRetries(generation)
            startPositionSyncLoop(generation)
        } else {
            stopPositionSyncLoop()
            if (buffering && playWhenReady) {
                startBufferingTimeout(generation)
            } else {
                stopBufferingTimeout()
            }
        }
    }

    private fun controllerMatchesAppState(player: Player, generation: Int): Boolean {
        if (!isPlayRequestCurrent(generation)) return false
        val appIndex = state.value.currentIndex
        if (appIndex < 0) return true
        val controllerIndex = player.currentMediaItemIndex
        return controllerIndex < 0 || controllerIndex == appIndex
    }

    private fun startPositionSyncLoop(generation: Int) {
        if (positionSyncJob?.isActive == true) return
        positionSyncJob = scope.launch {
            while (isActive) {
                delay(250)
                val player = controller ?: break
                if (!player.isPlaying || !controllerMatchesAppState(player, generation)) break
                applyPlatformPlayback(
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    durationMs = player.duration.coerceAtLeast(0L),
                    isPlaying = true,
                    isBuffering = false,
                    bufferedPositionMs = player.bufferedPosition
                        .coerceAtLeast(player.currentPosition)
                        .coerceAtLeast(0L),
                    generation = generation,
                )
            }
        }
    }

    private fun stopPositionSyncLoop() {
        positionSyncJob?.cancel()
        positionSyncJob = null
    }

    private fun startBufferingTimeout(generation: Int) {
        if (bufferingTimeoutJob?.isActive == true) return
        bufferingTimeoutJob = scope.launch {
            delay(PlaybackBufferingTimeoutMs)
            if (!isPlayRequestCurrent(generation) || !state.value.isBuffering) return@launch
            PhoebeLog.d("AndroidAudioPlayer") { "playback timed out while buffering" }
            schedulePlaybackRetry(null, generation)
        }
    }

    private fun stopBufferingTimeout() {
        bufferingTimeoutJob?.cancel()
        bufferingTimeoutJob = null
    }

    private fun schedulePlaybackRetry(error: PlaybackException?, generation: Int) {
        if (!isPlayRequestCurrent(generation) || !playWhenReady) return
        if (error != null && !error.isRecoverableStreamError()) {
            markPlaybackFailed(generation)
            return
        }
        if (retryGeneration != generation) {
            retryGeneration = generation
            retryCount = 0
        }
        if (retryCount >= MaxStreamRetryCount) {
            PhoebeLog.d("AndroidAudioPlayer") { "stream retry exhausted" }
            markPlaybackFailed(generation)
            return
        }
        retryCount++
        retryJob?.cancel()
        val delayMs = StreamRetryBaseDelayMs * retryCount
        retryJob = scope.launch {
            val player = controller ?: return@launch
            val positionMs = player.currentPosition.coerceAtLeast(0L)
            applyPlatformPlayback(
                positionMs = positionMs,
                durationMs = player.duration.coerceAtLeast(0L),
                isPlaying = false,
                isBuffering = true,
                bufferedPositionMs = player.bufferedPosition.coerceAtLeast(positionMs).coerceAtLeast(0L),
                generation = generation,
            )
            delay(delayMs)
            if (!isPlayRequestCurrent(generation) || !playWhenReady) return@launch
            controllerMutex.withLock {
                val retryPlayer = controller ?: return@withLock
                retryPlayer.seekTo(positionMs)
                retryPlayer.prepare()
                retryPlayer.play()
            }
            syncFromController(generation)
        }
    }

    private fun resetRetries(generation: Int) {
        retryGeneration = generation
        retryCount = 0
        retryJob?.cancel()
        retryJob = null
    }

    private fun stopRetry() {
        retryJob?.cancel()
        retryJob = null
        retryGeneration = -1
        retryCount = 0
    }

    private fun PlaybackException.isRecoverableStreamError(): Boolean =
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED

    private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            addListener(
                {
                    try {
                        continuation.resume(get())
                    } catch (error: Throwable) {
                        continuation.resumeWithException(error)
                    }
                },
                { command -> command.run() },
            )
        }

    private companion object {
        const val PlaybackBufferingTimeoutMs = 30_000L
        const val MaxStreamRetryCount = 5
        const val StreamRetryBaseDelayMs = 1_000L
        const val BufferProbeReportIntervalMs = 1_000L
        const val FallbackBitrateKbps = 256
        const val CrossfadeSteps = 24
        const val CrossfadePrepareTimeoutMs = 1_500L
        const val CrossfadeMinimumFadeMs = 500L
    }
}
