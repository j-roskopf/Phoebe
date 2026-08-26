@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.phoebe.app.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.phoebe.app.AndroidContextHolder
import com.phoebe.app.domain.AudioAnalysisSource
import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.PhoebeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

actual fun createAudioPlayer(): AudioPlayer = AndroidAudioPlayerHolder.instance

internal const val AndroidAutoplayConfirmedPositionMs = 250L

internal fun shouldIgnoreAndroidServiceEndedCallback(
    crossfadeOwnedTrackId: String?,
    hasCrossfadePlayer: Boolean,
): Boolean = crossfadeOwnedTrackId != null && hasCrossfadePlayer

object AndroidAudioPlayerHolder {
    private val player: AndroidAudioPlayer by lazy { AndroidAudioPlayer() }

    val instance: AudioPlayer
        get() = player

    fun ensureConnected() {
        player.ensureConnected()
    }
}

private data class PendingControllerTarget(
    val queueIds: List<String>,
    val platformIndex: Int,
    val generation: Int,
)

private data class LoadedPlatformQueue(
    val queueIds: List<String>,
    val firstAppIndex: Int,
    val itemCount: Int,
) {
    fun platformIndexFor(appIndex: Int): Int? =
        (appIndex - firstAppIndex).takeIf { it in 0 until itemCount }

    fun appIndexFor(platformIndex: Int): Int? =
        (firstAppIndex + platformIndex).takeIf { platformIndex in 0 until itemCount }
}

private data class PendingPlatformSeek(
    val generation: Int,
    val trackId: String?,
    val positionMs: Long,
    val startedAtMs: Long,
) {
    fun matches(generation: Int, trackId: String?): Boolean =
        this.generation == generation && this.trackId == trackId
}

private data class PendingPlatformQueueRebase(
    val queue: List<Track>,
    val currentIndex: Int,
    val generation: Int,
)

private data class CrossfadeOutgoingSetup(
    val player: Player,
    val ownedPlayer: ExoPlayer?,
    val incoming: ExoPlayer,
)

internal const val AndroidPlatformQueueWindowSize = 24

internal fun platformQueueWindowEndExclusive(
    startIndex: Int,
    queueSize: Int,
    repeatMode: RepeatMode,
): Int {
    val windowSize = if (repeatMode == RepeatMode.One) 1 else AndroidPlatformQueueWindowSize
    return (startIndex + windowSize).coerceAtMost(queueSize)
}

class AndroidAudioPlayer(
    private val diagnostics: PlaybackDiagnostics = AndroidPlaybackDiagnostics.diagnostics,
) : SimpleAudioPlayer() {
    override val useProgressTicker: Boolean = false

    private val appContext: Context
        get() = AndroidContextHolder.application

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var positionSyncJob: Job? = null
    private var positionSyncGeneration = -1
    private var platformLoadJob: Job? = null
    private var platformStopJob: Job? = null
    private var seekJob: Job? = null
    private var bufferingTimeoutJob: Job? = null
    private var retryJob: Job? = null
    private var crossfadeJob: Job? = null
    private var crossfadePlayer: ExoPlayer? = null
    private var crossfadeIncomingPlayer: ExoPlayer? = null
    private var crossfadeGeneration = -1
    private var crossfadeOwnedTrackId: String? = null
    private var retryGeneration = -1
    private var retryCount = 0
    private var pendingAutoplayGeneration = -1
    private var pendingAutoplayStartedAtMs = 0L
    private var holdQueueOnInfrastructureFailure = false
    private val controllerMutex = Mutex()
    private var loadedPlatformQueue: LoadedPlatformQueue? = null
    private var appControllerMutationInProgress = false
    private var pendingControllerTarget: PendingControllerTarget? = null
    private var pendingPlatformSeek: PendingPlatformSeek? = null
    private var pendingPlatformQueueRebase: PendingPlatformQueueRebase? = null
    private var androidGaplessPrepareGeneration = -1

    private val controllerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncFromController()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncFromController()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            syncFromController()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncFromController()
        }

        override fun onPlayerError(error: PlaybackException) {
            pendingControllerTarget = null
            stopBufferingTimeout()
            schedulePlaybackRetry(error, activePlayGeneration)
            stopPositionSyncLoop()
        }
    }

    init {
        AndroidPlaybackDiagnostics.diagnostics = diagnostics
        AndroidAudioAnalysisState.sink = { samples, sampleRateHz ->
            publishAudioAnalysisPcm(samples, sampleRateHz, AudioAnalysisSource.Pcm)
        }
        AndroidAudioAnalysisState.shouldSample = { canPublishAudioAnalysis() }
        AndroidPlaybackBridge.onSkipNext = { next() }
        AndroidPlaybackBridge.onSkipPrevious = { previous() }
        AndroidPlaybackBridge.hasNextTrack = { hasNextTrack() }
        AndroidPlaybackBridge.hasPreviousTrack = { hasPreviousTrack() }
        AndroidPlaybackBridge.onTrackEnded = { scope.launch { handlePlatformPlaybackEnded() } }
        AndroidPlaybackBridge.onServicePlayerChanged = { scope.launch { syncFromController() } }
        AndroidPlaybackBridge.onPlayQueue = { queue, index -> play(queue, index) }
        AndroidPlaybackBridge.onAdoptQueue = { queue, index, playing ->
            loadedPlatformQueue = null
            adoptPlatformPlayIntent(playing)
            adoptQueueState(queue, index, playing)
        }
        AndroidPlaybackBridge.onEnsureLocalPlaybackPaused = { scope.launch { forceLocalPlaybackPaused() } }
        AndroidPlaybackBridge.onLocalMediaSessionPlay = { resume() }
        AndroidPlaybackBridge.onLocalMediaSessionPause = { pause() }
        AndroidPlaybackBridge.onLocalMediaSessionSeekTo = { positionMs -> seekTo(positionMs) }
        scope.launch { ensureController() }
    }

    fun ensureConnected() {
        if (controller == null) {
            scope.launch { ensureController() }
        } else {
            scope.launch { syncFromController() }
        }
    }

    suspend fun releaseForTests() {
        withContext(Dispatchers.Main.immediate) {
            scope.coroutineContext.cancelChildren()
            platformLoadJob?.cancel()
            platformLoadJob = null
            platformStopJob?.cancel()
            platformStopJob = null
            clearPendingAutoplay()
            pendingControllerTarget = null
            pendingPlatformSeek = null
            pendingPlatformQueueRebase = null
            seekJob?.cancel()
            seekJob = null
            stopAndroidCrossfade()
            stopPositionSyncLoop()
            stopBufferingTimeout()
            stopRetry()
            controllerMutex.withLock {
                controller?.removeListener(controllerListener)
                controller?.run {
                    pause()
                    stop()
                    clearMediaItems()
                    release()
                }
                controller = null
            }
            appContext.stopService(Intent(appContext, PlaybackService::class.java))
            AndroidAudioAnalysisState.sink = null
            AndroidAudioAnalysisState.shouldSample = null
            AndroidPlaybackDiagnostics.reset()
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
            val loaded = loadedPlatformQueue
            val platformIndex = loaded
                ?.takeIf { it.queueIds == queueIds && player.mediaItemCount == it.itemCount }
                ?.platformIndexFor(targetIndex)
            if (platformIndex != null) {
                expectControllerTarget(queueIds, platformIndex, generation)
                player.pause()
                player.seekTo(platformIndex, 0L)
                updateOptimisticLocalBufferedPosition(track, generation)
                player.volume = effectiveOutputVolume()
                if (playWhenReady) {
                    markPendingAutoplay(generation)
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
        startPositionMs: Long,
    ) {
        runPlatformLoad(generation) { player ->
            loadQueueOnPlayer(
                player = player,
                queue = queue,
                targetIndex = startIndex.coerceIn(queue.indices),
                queueIds = queue.map { it.id },
                generation = generation,
                startPositionMs = startPositionMs.coerceAtLeast(0L),
            )
        }
    }

    override fun stopCurrentPlaybackImmediately() {
        val priorLoad = platformLoadJob
        platformLoadJob = null
        stopPositionSyncLoop()
        stopBufferingTimeout()
        stopRetry()
        loadedPlatformQueue = null
        pendingPlatformSeek = null
        pendingPlatformQueueRebase = null
        androidGaplessPrepareGeneration = -1
        clearPendingAutoplay()
        clearLocalMediaSessionState()
        platformStopJob?.cancel()
        platformStopJob = scope.launch {
            priorLoad?.cancelAndJoin()
            crossfadeJob?.cancelAndJoin()
            stopAndroidCrossfade()
            controllerMutex.withLock {
                activeLocalPlayer()?.run {
                    pause()
                    stop()
                    clearMediaItems()
                }
            }
        }
    }

    override fun onQueueEdited(queue: List<Track>, currentIndex: Int) {
        if (currentIndex !in queue.indices) return
        val generation = activePlayGeneration
        pendingPlatformQueueRebase = PendingPlatformQueueRebase(queue, currentIndex, generation)
        scope.launch {
            rebasePlatformQueueOnCurrentTrack(queue, currentIndex, generation)
        }
    }

    override fun pause() {
        clearPendingAutoplay()
        scope.launch {
            if (crossfadeIncomingPlayer != null) {
                cancelAndroidCrossfadeTransition()
            }
            val ownedPlayer = ownedCrossfadePlayer()
            if (ownedPlayer != null) {
                ownedPlayer.pause()
                syncFromCrossfadePlayer(ownedPlayer)
            } else {
                controllerMutex.withLock { activeLocalPlayer()?.pause() }
                syncFromController()
            }
        }
    }

    override fun resume() {
        holdQueueOnInfrastructureFailure = false
        scope.launch {
            val ownedPlayer = ownedCrossfadePlayer()
            if (ownedPlayer != null) {
                ownedPlayer.volume = effectiveOutputVolume()
                ownedPlayer.play()
                syncFromCrossfadePlayer(ownedPlayer)
            } else {
                controllerMutex.withLock {
                    activeLocalPlayer()?.run {
                        if (playbackState == Player.STATE_IDLE || playerError != null) {
                            prepare()
                        }
                        markPendingAutoplay(activePlayGeneration)
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
        val targetPositionMs = positionMs.coerceAtLeast(0L)
        seekJob = scope.launch {
            if (crossfadeIncomingPlayer != null) {
                cancelAndroidCrossfadeTransition()
            }
            val ownedPlayer = ownedCrossfadePlayer()
            if (ownedPlayer != null) {
                if (!isPlayRequestCurrent(generation)) return@launch
                ownedPlayer.seekTo(targetPositionMs)
                syncFromCrossfadePlayer(ownedPlayer, generation)
            } else {
                val trackId = state.value.currentTrack?.id
                pendingPlatformSeek = PendingPlatformSeek(
                    generation = generation,
                    trackId = trackId,
                    positionMs = targetPositionMs,
                    startedAtMs = SystemClock.elapsedRealtime(),
                )
                stopPositionSyncLoop()
                if (crossfadeJob?.isActive == true) {
                    stopAndroidCrossfade()
                }
                val player = controllerMutex.withLock {
                    if (!isPlayRequestCurrent(generation)) return@withLock null
                    activeLocalPlayer()?.also { platformPlayer ->
                        val shouldResume = playWhenReady &&
                            (platformPlayer.isPlaying || platformPlayer.playWhenReady)
                        if (shouldResume) {
                            markPendingAutoplay(generation)
                            platformPlayer.pause()
                        }
                        platformPlayer.seekToCurrentItem(targetPositionMs)
                        if (shouldResume) {
                            platformPlayer.play()
                        }
                    }
                }
                if (player == null) {
                    pendingPlatformSeek = null
                    return@launch
                }
                waitForPlatformSeek(player, targetPositionMs, generation)
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
            controllerMutex.withLock { activeLocalPlayer()?.volume = volume }
        }
    }

    override fun applyEqualizer(profile: EqualizerProfile) {
        AndroidEqualizerState.profile = profile.normalized()
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
            AndroidPlaybackBridge.suppressServiceEndedCallback = true
            try {
                val setup = controllerMutex.withLock {
                    if (!isPlayRequestCurrent(generation)) return@launch
                    val outgoingOwnedByPlayback = ownedCrossfadePlayer()
                    val outgoingPlayer: Player = outgoingOwnedByPlayback ?: activeLocalPlayer() ?: return@launch
                    releaseAbandonedCrossfadePlayers(keepOutgoing = outgoingPlayer)
                    diagnostics.crossfadeStarted(
                        engine = PlaybackEnginePath.Media3Crossfade,
                        outgoingTrackId = state.value.currentTrack?.id,
                        incomingTrackId = track.id,
                        durationMs = durationMs,
                    )
                    val incomingPlayer = AndroidPlaybackDiagnostics.newPlayerBuilder(appContext, PlaybackEnginePath.Media3Crossfade)
                        .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ false)
                        .build()
                        .also { it.applyPhoebeAudioOffloadPreference() }
                    incomingPlayer.volume = 0f
                    incomingPlayer.setMediaItem(playbackMediaItem(track, inAppPlayback = true))
                    crossfadeIncomingPlayer = incomingPlayer
                    incomingPlayer.prepare()
                    incomingPlayer.play()
                    CrossfadeOutgoingSetup(
                        player = outgoingPlayer,
                        ownedPlayer = outgoingOwnedByPlayback,
                        incoming = incomingPlayer,
                    )
                }
                incoming = setup.incoming
                val outgoing = setup.player
                val outgoingOwnedByPlayback = setup.ownedPlayer
                if (!waitUntilReady(incoming, generation, CrossfadePrepareTimeoutMs)) return@launch
                if (!isAndroidCrossfadeTransitionCurrent(generation, incoming)) return@launch
                if (outgoingOwnedByPlayback == null && activeLocalPlayer() !== outgoing) return@launch
                if (outgoingOwnedByPlayback != null && crossfadePlayer !== outgoingOwnedByPlayback) return@launch

                val remainingMs = outgoing.duration
                    .takeIf { it > 0L }
                    ?.let { duration -> duration - outgoing.currentPosition.coerceAtLeast(0L) }
                    ?: durationMs
                val fadeDurationMs = remainingMs
                    .coerceAtMost(durationMs)
                    .coerceAtLeast(CrossfadeMinimumFadeMs)
                fadeVolumes(outgoing, incoming, fadeDurationMs, baseVolume, generation)
                if (!isAndroidCrossfadeTransitionCurrent(generation, incoming)) return@launch
                if (outgoingOwnedByPlayback == null && activeLocalPlayer() !== outgoing) return@launch
                if (outgoingOwnedByPlayback != null && crossfadePlayer !== outgoingOwnedByPlayback) return@launch

                if (outgoingOwnedByPlayback != null) {
                    incoming.volume = effectiveOutputVolume()
                    incomingOwnedByPlayback = true
                    crossfadeIncomingPlayer = null
                    crossfadePlayer = incoming
                    crossfadeOwnedTrackId = track.id
                    outgoing.pause()
                    outgoing.volume = 0f
                    outgoingOwnedByPlayback.release()
                } else {
                    controllerMutex.withLock {
                        if (!isAndroidCrossfadeTransitionCurrent(generation, incoming) ||
                            activeLocalPlayer() !== outgoing
                        ) {
                            return@withLock
                        }
                        incoming.volume = effectiveOutputVolume()
                        incomingOwnedByPlayback = true
                        crossfadeIncomingPlayer = null
                        crossfadePlayer = incoming
                        crossfadeOwnedTrackId = track.id
                        outgoing.pause()
                        outgoing.volume = 0f
                        outgoing.stop()
                        outgoing.clearMediaItems()
                    }
                }
                if (!isPlayRequestCurrent(generation) || crossfadePlayer !== incoming) return@launch
                adoptCrossfadeTarget(queue, targetIndex, incoming.currentPosition.coerceAtLeast(0L), generation)
                diagnostics.crossfadeCommitted(PlaybackEnginePath.Media3Crossfade, track.id)
                updateOptimisticLocalBufferedPosition(track, generation)
                publishLocalMediaSessionState(incoming, track)
                startCrossfadeOwnedSync(incoming, queue, targetIndex, generation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                PhoebeLog.d("AndroidAudioPlayer") { "android crossfade failed: ${error.message}" }
                diagnostics.playbackError(PlaybackEnginePath.Media3Crossfade, error.message)
            } finally {
                AndroidPlaybackBridge.suppressServiceEndedCallback = false
                if (!incomingOwnedByPlayback) {
                    incoming?.release()
                    if (crossfadeIncomingPlayer === incoming) crossfadeIncomingPlayer = null
                }
                if (crossfadeGeneration == generation) crossfadeGeneration = -1
            }
        }
        return true
    }

    override fun startGaplessPrepareOnPlatform(
        queue: List<Track>,
        targetIndex: Int,
        track: Track,
        generation: Int,
    ): Boolean {
        if (targetIndex !in queue.indices) return false
        val sourceIndex = state.value.currentIndex
        if (sourceIndex !in queue.indices) return false
        if (targetIndex <= sourceIndex) return false
        val queueIds = queue.map { it.id }
        androidGaplessPrepareGeneration = generation
        scope.launch {
            controllerMutex.withLock {
                if (!isPlayRequestCurrent(generation) || androidGaplessPrepareGeneration != generation) return@withLock
                val player = activeLocalPlayer() ?: return@withLock
                val current = state.value
                if (current.currentIndex != sourceIndex || current.queue.map { it.id } != queueIds) return@withLock
                val loaded = loadedPlatformQueue?.takeIf { it.queueIds == queueIds }
                val platformCurrentIndex = loaded?.platformIndexFor(sourceIndex)
                    ?: player.currentMediaItemIndex.takeIf { it >= 0 }
                    ?: return@withLock
                val platformTrackId = player.currentMediaItem?.mediaId
                if (platformTrackId != null && platformTrackId != current.currentTrack?.id) return@withLock
                appControllerMutationInProgress = true
                try {
                    repeat(platformCurrentIndex.coerceAtLeast(0)) {
                        if (player.mediaItemCount > 0) {
                            player.removeMediaItem(0)
                        }
                    }
                    val compactedCount = player.mediaItemCount.coerceAtLeast(1)
                    loadedPlatformQueue = LoadedPlatformQueue(
                        queueIds = queueIds,
                        firstAppIndex = sourceIndex,
                        itemCount = compactedCount,
                    )
                    val targetPlatformIndex = targetIndex - sourceIndex
                    if (targetPlatformIndex < 0) return@withLock
                    while (player.mediaItemCount > targetPlatformIndex + 1) {
                        player.removeMediaItem(player.mediaItemCount - 1)
                    }
                    if (player.mediaItemCount <= targetPlatformIndex) {
                        player.addMediaItem(playbackMediaItem(track, inAppPlayback = true))
                    }
                    loadedPlatformQueue = LoadedPlatformQueue(
                        queueIds = queueIds,
                        firstAppIndex = sourceIndex,
                        itemCount = player.mediaItemCount,
                    )
                } finally {
                    appControllerMutationInProgress = false
                }
            }
        }
        return true
    }

    override fun cancelGaplessPrepareOnPlatform(generation: Int) {
        if (androidGaplessPrepareGeneration == generation) {
            androidGaplessPrepareGeneration = -1
        }
        scope.launch {
            controllerMutex.withLock {
                val player = activeLocalPlayer() ?: return@withLock
                val current = state.value
                val loaded = loadedPlatformQueue ?: return@withLock
                val platformCurrentIndex = loaded.platformIndexFor(current.currentIndex) ?: return@withLock
                if (platformCurrentIndex < 0 || platformCurrentIndex >= player.mediaItemCount) {
                    return@withLock
                }
                appControllerMutationInProgress = true
                try {
                    while (player.mediaItemCount > platformCurrentIndex + 1) {
                        player.removeMediaItem(player.mediaItemCount - 1)
                    }
                    loadedPlatformQueue = LoadedPlatformQueue(
                        queueIds = loaded.queueIds,
                        firstAppIndex = current.currentIndex,
                        itemCount = player.mediaItemCount - platformCurrentIndex,
                    )
                } finally {
                    appControllerMutationInProgress = false
                }
            }
        }
    }

    override fun playUri(uri: String) {
        if (uri.isBlank()) return
        val generation = activePlayGeneration
        runPlatformLoad(generation) { player ->
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .apply {
                    if (uri.contains(".m3u8", ignoreCase = true)) {
                        setMimeType("application/x-mpegURL")
                    }
                }
                .build()
            player.setMediaItem(mediaItem)
            player.prepare()
            if (playWhenReady) {
                markPendingAutoplay(generation)
                player.play()
            }
        }
    }

    private fun forceLocalPlaybackPaused() {
        cancelPlayIntent()
        clearPendingAutoplay()
        cancelAndroidCrossfadeTransition()
        val ownedPlayer = ownedCrossfadePlayer()
        ownedPlayer?.let { player ->
            player.pause()
            syncFromCrossfadePlayer(player)
        }
        stopPositionSyncLoop()
        stopBufferingTimeout()
        val current = state.value
        val positionMs = ownedPlayer?.currentPosition?.coerceAtLeast(0L)
            ?: AndroidPlaybackBridge.servicePlayer?.currentPosition?.coerceAtLeast(0L)
            ?: current.positionMs
        applyPlatformPlayback(
            positionMs = positionMs,
            durationMs = current.durationMs,
            isPlaying = false,
            isBuffering = false,
        )
    }

    private fun activeLocalPlayer(): Player? =
        AndroidPlaybackBridge.servicePlayer ?: controller

    private fun hasNextTrack(): Boolean {
        val current = state.value
        if (current.currentIndex !in current.queue.indices) return false
        return when (current.repeat) {
            RepeatMode.One,
            RepeatMode.All,
            -> current.queue.isNotEmpty()
            RepeatMode.Off -> current.currentIndex < current.queue.lastIndex
        }
    }

    private fun hasPreviousTrack(): Boolean =
        state.value.currentIndex in state.value.queue.indices

    private fun handlePlatformPlaybackEnded() {
        if (shouldIgnoreAndroidServiceEndedCallback(crossfadeOwnedTrackId, crossfadePlayer != null)) return
        if (holdQueueOnInfrastructureFailure) return
        val player = activeLocalPlayer()?.takeIf { it.playbackState == Player.STATE_ENDED } ?: return
        if (player.playerError != null) return
        val endedAppIndex = endedPlatformAppIndex(player)
        if (endedAppIndex != null) {
            val queue = state.value.queue
            val endedTrack = queue.getOrNull(endedAppIndex)
            if (endedTrack != null) {
                if (endedAppIndex != state.value.currentIndex) {
                    adoptQueueState(queue, endedAppIndex, isPlaying = false)
                }
                val endedPositionMs = endedTrack.durationMs
                    .takeIf { it > 0L }
                    ?: state.value.positionMs
                applyPlatformPlayback(
                    positionMs = endedPositionMs,
                    durationMs = endedTrack.durationMs,
                    isPlaying = false,
                    isBuffering = false,
                    bufferedPositionMs = endedPositionMs,
                )
            }
        }
        advanceAfterPlatformTrackEnded()
    }

    private fun endedPlatformAppIndex(player: Player): Int? {
        val controllerIndex = player.currentMediaItemIndex.takeIf { it >= 0 } ?: return null
        val loaded = loadedPlatformQueue
        val appIndex = loaded?.appIndexFor(controllerIndex) ?: controllerIndex
        val currentQueue = state.value.queue
        if (appIndex !in currentQueue.indices) return null
        val loadedTrackId = loaded?.queueIds?.getOrNull(appIndex)
        if (loadedTrackId != null && currentQueue[appIndex].id != loadedTrackId) return null
        return appIndex
    }

    private suspend fun rebasePlatformQueueOnCurrentTrack(
        queue: List<Track>,
        currentIndex: Int,
        generation: Int,
    ) {
        if (!isPlayRequestCurrent(generation) || currentIndex !in queue.indices) return
        if (crossfadePlayer != null && crossfadeOwnedTrackId != null) return
        val currentTrack = queue[currentIndex]
        controllerMutex.withLock {
            if (!isPlayRequestCurrent(generation)) return@withLock
            val player = activeLocalPlayer() ?: return@withLock
            if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) return@withLock
            val platformTrackId = player.currentMediaItem?.mediaId
            if (platformTrackId != null && platformTrackId != currentTrack.id) return@withLock
            val positionMs = player.currentPosition.coerceAtLeast(0L)
            val shouldPlay = playWhenReady && (player.isPlaying || player.playWhenReady)
            appControllerMutationInProgress = true
            try {
                rebasePlatformQueueOnCurrentTrackLocked(
                    player = player,
                    queue = queue,
                    currentIndex = currentIndex,
                    generation = generation,
                    startPositionMs = positionMs,
                    shouldPlay = shouldPlay,
                )
            } finally {
                appControllerMutationInProgress = false
            }
            clearPendingPlatformQueueRebase(queue, currentIndex, generation)
            syncFromController(generation)
        }
    }

    private suspend fun rebasePlatformQueueOnCurrentTrackLocked(
        player: Player,
        queue: List<Track>,
        currentIndex: Int,
        generation: Int,
        startPositionMs: Long,
        shouldPlay: Boolean,
    ) {
        loadQueueOnPlayer(
            player = player,
            queue = queue,
            targetIndex = currentIndex,
            queueIds = queue.map { it.id },
            generation = generation,
            startPositionMs = startPositionMs,
            shouldPlay = shouldPlay,
        )
    }

    private fun clearPendingPlatformQueueRebase(
        queue: List<Track>,
        currentIndex: Int,
        generation: Int,
    ) {
        val pending = pendingPlatformQueueRebase ?: return
        if (pending.generation == generation &&
            pending.currentIndex == currentIndex &&
            pending.queue.map { it.id } == queue.map { it.id }
        ) {
            pendingPlatformQueueRebase = null
        }
    }

    private fun markPendingAutoplay(generation: Int) {
        if (pendingAutoplayGeneration != generation) {
            pendingAutoplayStartedAtMs = SystemClock.elapsedRealtime()
        }
        pendingAutoplayGeneration = generation
    }

    private fun clearPendingAutoplay() {
        pendingAutoplayGeneration = -1
        pendingAutoplayStartedAtMs = 0L
    }

    private fun stopAndroidCrossfade() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        crossfadeGeneration = -1
        crossfadeOwnedTrackId = null
        AndroidPlaybackBridge.suppressServiceEndedCallback = false
        val incoming = crossfadeIncomingPlayer
        crossfadeIncomingPlayer = null
        val owned = crossfadePlayer
        crossfadePlayer = null
        incoming?.release()
        owned?.release()
        clearLocalMediaSessionState()
    }

    private fun ownedCrossfadePlayer(): ExoPlayer? =
        crossfadePlayer?.takeIf { crossfadeOwnedTrackId != null }

    private fun cancelAndroidCrossfadeTransition() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        crossfadeGeneration = -1
        AndroidPlaybackBridge.suppressServiceEndedCallback = false
        crossfadeIncomingPlayer?.release()
        crossfadeIncomingPlayer = null
        crossfadePlayer?.volume = effectiveOutputVolume()
        activeLocalPlayer()?.volume = effectiveOutputVolume()
    }

    private fun isAndroidCrossfadeTransitionCurrent(generation: Int, incoming: Player): Boolean =
        isPlayRequestCurrent(generation) &&
            crossfadeGeneration == generation &&
            crossfadeIncomingPlayer === incoming &&
            playWhenReady

    private suspend fun waitForPlayerIdle(player: Player, timeoutMs: Long = CodecTeardownTimeoutMs): Boolean {
        if (player.playbackState == Player.STATE_IDLE) return true
        var waitedMs = 0L
        while (waitedMs < timeoutMs) {
            if (player.playbackState == Player.STATE_IDLE) return true
            delay(CodecTeardownPollMs)
            waitedMs += CodecTeardownPollMs
        }
        return player.playbackState == Player.STATE_IDLE
    }

    private suspend fun releasePlatformDecoderBeforeLoad(player: Player) {
        player.playWhenReady = false
        player.pause()
        player.stop()
        player.clearMediaItems()
        if (!waitForPlayerIdle(player)) {
            delay(CodecTeardownSettleMs)
        }
    }

    private fun releaseAbandonedCrossfadePlayers(keepOutgoing: Player) {
        crossfadeIncomingPlayer?.let { incoming ->
            incoming.release()
            crossfadeIncomingPlayer = null
        }
        crossfadePlayer?.takeIf { it !== keepOutgoing }?.let { stale ->
            stale.release()
            crossfadePlayer = null
            crossfadeOwnedTrackId = null
        }
    }

    private suspend fun waitUntilReady(player: Player, generation: Int, timeoutMs: Long): Boolean {
        var waitedMs = 0L
        while (waitedMs < timeoutMs && isPlayRequestCurrent(generation) && playWhenReady) {
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
            if (!isPlayRequestCurrent(generation) || !playWhenReady) return
            val progress = (index + 1).toFloat() / CrossfadeSteps.toFloat()
            val outgoingVolume = (baseVolume * (1f - progress)).coerceIn(0f, 1f)
            val incomingVolume = (baseVolume * progress).coerceIn(0f, 1f)
            diagnostics.crossfadeVolume(
                engine = PlaybackEnginePath.Media3Crossfade,
                step = index + 1,
                outgoingVolume = outgoingVolume,
                incomingVolume = incomingVolume,
            )
            outgoing.volume = outgoingVolume
            incoming.volume = incomingVolume
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
                adoptPlatformPlayIntent(player.playWhenReady)
                reportPlaybackDiagnostics(
                    engine = PlaybackEnginePath.Media3Crossfade,
                    positionMs = positionMs,
                    durationMs = player.duration.coerceAtLeast(queue.getOrNull(targetIndex)?.durationMs ?: 0L),
                    isPlaying = player.isPlaying,
                )
                publishLocalMediaSessionState(player, queue.getOrNull(targetIndex))
                applyPlatformPlayback(
                    positionMs = positionMs,
                    durationMs = player.duration.coerceAtLeast(queue.getOrNull(targetIndex)?.durationMs ?: 0L),
                    isPlaying = player.isPlaying,
                    isBuffering = player.playbackState == Player.STATE_BUFFERING,
                    bufferedPositionMs = player.bufferedPosition.coerceAtLeast(positionMs).coerceAtLeast(0L),
                    generation = generation,
                )
                if (player.playbackState == Player.STATE_ENDED) {
                    advanceAfterPlatformTrackEnded(generation)
                    break
                }
                delay(FinePositionSyncIntervalMs)
            }
        }
    }

    private fun syncFromCrossfadePlayer(
        player: Player,
        generation: Int = activePlayGeneration,
    ) {
        if (!isPlayRequestCurrent(generation) || crossfadePlayer !== player) return
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        adoptPlatformPlayIntent(player.playWhenReady)
        reportPlaybackDiagnostics(
            engine = PlaybackEnginePath.Media3Crossfade,
            positionMs = positionMs,
            durationMs = player.duration.coerceAtLeast(state.value.currentTrack?.durationMs ?: 0L),
            isPlaying = player.isPlaying && player.playbackState != Player.STATE_BUFFERING,
        )
        publishLocalMediaSessionState(player, state.value.currentTrack)
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
        val priorLoad = platformLoadJob
        platformStopJob?.cancel()
        platformStopJob = null
        pendingPlatformSeek = null
        seekJob?.cancel()
        stopBufferingTimeout()
        stopRetry()
        androidGaplessPrepareGeneration = -1
        resetRetries(generation)
        diagnostics.engineSelected(PlaybackEnginePath.Media3)
        stopPositionSyncLoop()
        if (playWhenReady) {
            markPendingAutoplay(generation)
        }
        platformLoadJob = scope.launch {
            var shouldSyncAfterMutation = false
            try {
                priorLoad?.cancelAndJoin()
                crossfadeJob?.cancelAndJoin()
                stopAndroidCrossfade()
                startPlaybackService()
                ensureController()
                controllerMutex.withLock {
                    val player = activeLocalPlayer() ?: return@withLock
                    if (!isPlayRequestCurrent(generation)) return@withLock
                    appControllerMutationInProgress = true
                    try {
                        block(player)
                        pendingPlatformQueueRebase
                            ?.takeIf { it.generation == generation && it.currentIndex in it.queue.indices }
                            ?.let { pending ->
                                val currentTrack = pending.queue[pending.currentIndex]
                                val platformTrackId = player.currentMediaItem?.mediaId
                                if (platformTrackId == null || platformTrackId == currentTrack.id) {
                                    rebasePlatformQueueOnCurrentTrackLocked(
                                        player = player,
                                        queue = pending.queue,
                                        currentIndex = pending.currentIndex,
                                        generation = generation,
                                        startPositionMs = player.currentPosition.coerceAtLeast(0L),
                                        shouldPlay = playWhenReady && (player.isPlaying || player.playWhenReady),
                                    )
                                    clearPendingPlatformQueueRebase(
                                        pending.queue,
                                        pending.currentIndex,
                                        generation,
                                    )
                                }
                            }
                        shouldSyncAfterMutation = isPlayRequestCurrent(generation)
                    } finally {
                        appControllerMutationInProgress = false
                    }
                }
                if (shouldSyncAfterMutation) {
                    syncFromController(generation)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                val failure = PlaybackFailureClassifier.fromThrowable(error, currentStreamUri())
                PhoebeLog.d("AndroidAudioPlayer") { "platform load failed: ${failure.logLine()}" }
                pendingControllerTarget = null
                clearPendingAutoplay()
                stopBufferingTimeout()
                holdQueueOnFailure(failure)
                publishPlaybackFailure(failure, generation)
            }
        }
    }

    private suspend fun loadQueueOnPlayer(
        player: Player,
        queue: List<Track>,
        targetIndex: Int,
        queueIds: List<String>,
        generation: Int,
        startPositionMs: Long = 0L,
        shouldPlay: Boolean = playWhenReady,
    ) {
        val windowStartIndex = targetIndex
        // Keep a bounded forward window in Media3. A queue selection still starts at the
        // requested track, but subsequent skips can seek to an already-installed item
        // instead of stopping the decoder and setting up a fresh stream request. Repeat
        // One must stay single-item so Media3 cannot advance before the app restarts it.
        val windowEndExclusive = platformQueueWindowEndExclusive(
            startIndex = windowStartIndex,
            queueSize = queue.size,
            repeatMode = state.value.repeat,
        )
        val windowTracks = queue.subList(windowStartIndex, windowEndExclusive)
        expectControllerTarget(queueIds, platformIndex = 0, generation)
        releasePlatformDecoderBeforeLoad(player)
        player.volume = effectiveOutputVolume()
        player.setMediaItems(
            windowTracks.map { playbackMediaItem(it, inAppPlayback = true) },
            0,
            startPositionMs.coerceAtLeast(0L),
        )
        player.prepare()
        loadedPlatformQueue = LoadedPlatformQueue(
            queueIds = queueIds,
            firstAppIndex = windowStartIndex,
            itemCount = windowTracks.size,
        )
        holdQueueOnInfrastructureFailure = false
        queue.getOrNull(targetIndex)?.let { updateOptimisticLocalBufferedPosition(it, generation) }
        if (shouldPlay && playWhenReady) {
            markPendingAutoplay(generation)
            player.playWhenReady = true
            player.play()
            startPositionSyncLoop(generation)
        }
    }

    private fun updateOptimisticLocalBufferedPosition(track: Track, generation: Int) {
        val durationMs = track.durationMs.takeIf { it > 0L } ?: return
        val uri = StreamingPlaybackPolicyHolder.resolvePlaybackUri(track)
        if (uri.isBlank()) return
        if (!uri.isHttpUrl()) {
            updateBufferedPosition(durationMs, generation)
        }
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
        val player = activeLocalPlayer() ?: return
        val controllerPosition = player.currentPosition.coerceAtLeast(0L)
        val durationMs = player.duration.coerceAtLeast(0L)
        val crossfadeTransitionActive = crossfadeIncomingPlayer != null && crossfadeGeneration == generation
        if (crossfadeTransitionActive) {
            if (shouldCancelAndroidCrossfadeForControllerPause(
                    crossfadeTransitionActive = crossfadeTransitionActive,
                    playWhenReady = playWhenReady,
                    controllerPlayWhenReady = player.playWhenReady,
                    appControllerMutationInProgress = appControllerMutationInProgress,
                )
            ) {
                cancelPlayIntent()
                clearPendingAutoplay()
                cancelAndroidCrossfadeTransition()
                applyPlatformPlayback(
                    positionMs = controllerPosition,
                    durationMs = durationMs,
                    isPlaying = false,
                    isBuffering = false,
                    bufferedPositionMs = player.bufferedPosition.coerceAtLeast(controllerPosition).coerceAtLeast(0L),
                    generation = generation,
                )
                stopPositionSyncLoop()
            }
            return
        }
        var appState = state.value
        val controllerIndex = player.currentMediaItemIndex
        val loaded = loadedPlatformQueue
        val appControllerIndex = loaded?.appIndexFor(controllerIndex) ?: controllerIndex
        if (pendingControllerTarget != null) {
            val queueIds = appState.queue.map { it.id }
            if (isWaitingForControllerTarget(queueIds, controllerIndex, generation)) return
        }
        if (appState.currentIndex >= 0 &&
            appControllerIndex >= 0 &&
            appControllerIndex != appState.currentIndex
        ) {
            if (holdQueueOnInfrastructureFailure) return
            if (appControllerMutationInProgress) return
            val queueIds = appState.queue.map { it.id }
            if (loaded?.queueIds == queueIds && appControllerIndex in appState.queue.indices) {
                adoptQueueState(appState.queue, appControllerIndex, player.isPlaying)
                clearGaplessPrepareState()
                androidGaplessPrepareGeneration = -1
                appState = state.value
            } else {
                val platformTrackId = player.currentMediaItem?.mediaId
                val queueIndexForPlatformItem = platformTrackId
                    ?.let { id -> appState.queue.indexOfFirst { it.id == id } }
                    ?.takeIf { it >= 0 }
                    ?: return
                adoptQueueState(appState.queue, queueIndexForPlatformItem, player.isPlaying)
                appState = state.value
                scope.launch {
                    rebasePlatformQueueOnCurrentTrack(appState.queue, queueIndexForPlatformItem, generation)
                }
            }
        }
        if (isWaitingForPlatformSeek(appState.currentTrack?.id, controllerPosition, durationMs, generation)) {
            startPositionSyncLoop(generation)
            return
        }
        val platformTrackId = player.currentMediaItem?.mediaId
        if (appState.isBuffering &&
            appState.positionMs == 0L &&
            controllerPosition > 1_500L &&
            platformTrackId != appState.currentTrack?.id
        ) {
            return
        }
        val bufferedPosition = player.bufferedPosition
            .coerceAtLeast(controllerPosition)
            .coerceAtLeast(0L)
        val buffering = player.playbackState == Player.STATE_BUFFERING
        val hasReadyBuffer = hasPlaybackReadyBuffer(
            positionMs = controllerPosition,
            bufferedPositionMs = bufferedPosition,
            durationMs = durationMs,
        )
        val retainPendingAutoplay = shouldRetainPendingAutoplay(
            pendingGeneration = pendingAutoplayGeneration,
            generation = generation,
            playWhenReady = playWhenReady,
            hasCurrentTrack = appState.currentTrack != null,
            playerIsPlaying = player.isPlaying,
            playbackState = player.playbackState,
            positionMs = controllerPosition,
        )
        val recoverUnconfirmedAutoplay = shouldRecoverUnconfirmedAutoplay(
            playWhenReady = playWhenReady,
            hasCurrentTrack = appState.currentTrack != null,
            playerIsPlaying = player.isPlaying,
            playbackState = player.playbackState,
            positionMs = controllerPosition,
            controllerMatchesAppState = controllerMatchesAppState(player, generation),
        )
        val autoplayPending = (retainPendingAutoplay || recoverUnconfirmedAutoplay) && !player.isPlaying
        if (autoplayPending) {
            if (pendingAutoplayGeneration != generation) {
                markPendingAutoplay(generation)
            }
            if (!appControllerMutationInProgress) {
                player.playWhenReady = true
                player.play()
            }
            val autoplayElapsedMs = SystemClock.elapsedRealtime() - pendingAutoplayStartedAtMs
            if (autoplayElapsedMs >= AutoplayStartRetryMs &&
                (player.playbackState == Player.STATE_READY || hasReadyBuffer) &&
                player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
            ) {
                schedulePlaybackRetry(null, generation)
                return
            }
            reportPlaybackDiagnostics(
                engine = PlaybackEnginePath.Media3,
                positionMs = controllerPosition,
                durationMs = player.duration.coerceAtLeast(0L),
                isPlaying = false,
            )
            applyPlatformPlayback(
                positionMs = controllerPosition,
                durationMs = durationMs,
                isPlaying = false,
                isBuffering = true,
                bufferedPositionMs = bufferedPosition,
                generation = generation,
                forceBuffering = true,
            )
            startBufferingTimeout(generation)
            startPositionSyncLoop(generation)
            return
        }
        if (pendingAutoplayGeneration == generation && !retainPendingAutoplay) {
            clearPendingAutoplay()
        }
        val transientPauseDuringAppLoad = playWhenReady && appState.isBuffering && !player.playWhenReady
        if (transientPauseDuringAppLoad) {
            startBufferingTimeout(generation)
            startPositionSyncLoop(generation)
            return
        }
        if (shouldAdoptPlatformPlayIntent(appControllerMutationInProgress, platformTrackId, appState.currentTrack?.id)) {
            adoptPlatformPlayIntent(player.playWhenReady)
        }
        reportPlaybackDiagnostics(
            engine = PlaybackEnginePath.Media3,
            positionMs = controllerPosition,
            durationMs = player.duration.coerceAtLeast(0L),
            isPlaying = player.isPlaying && !buffering,
        )
        applyPlatformPlayback(
            positionMs = controllerPosition,
            durationMs = durationMs,
            isPlaying = player.isPlaying && !buffering,
            isBuffering = buffering,
            bufferedPositionMs = bufferedPosition,
            generation = generation,
        )
        publishLocalMediaSessionState(player, appState.currentTrack)
        if (player.isPlaying && playWhenReady) {
            stopBufferingTimeout()
            resetRetries(generation)
            holdQueueOnInfrastructureFailure = false
            startPositionSyncLoop(generation)
        } else {
            if (buffering && playWhenReady) {
                startBufferingTimeout(generation)
                startPositionSyncLoop(generation)
            } else {
                stopPositionSyncLoop()
                stopBufferingTimeout()
            }
        }
    }

    private fun expectControllerTarget(queueIds: List<String>, platformIndex: Int, generation: Int) {
        pendingControllerTarget = PendingControllerTarget(
            queueIds = queueIds,
            platformIndex = platformIndex,
            generation = generation,
        )
    }

    private fun isWaitingForControllerTarget(
        queueIds: List<String>,
        controllerIndex: Int,
        generation: Int,
    ): Boolean {
        val pending = pendingControllerTarget ?: return false
        if (pending.generation != generation || pending.queueIds != queueIds) {
            pendingControllerTarget = null
            return false
        }
        if (controllerIndex == pending.platformIndex) {
            pendingControllerTarget = null
            return false
        }
        return true
    }

    private fun controllerMatchesAppState(player: Player, generation: Int): Boolean {
        if (!isPlayRequestCurrent(generation)) return false
        val appIndex = state.value.currentIndex
        if (appIndex < 0) return true
        val controllerIndex = player.currentMediaItemIndex
        val appControllerIndex = loadedPlatformQueue?.appIndexFor(controllerIndex) ?: controllerIndex
        return controllerIndex < 0 || appControllerIndex == appIndex
    }

    private fun startPositionSyncLoop(generation: Int) {
        if (positionSyncJob?.isActive == true) {
            if (positionSyncGeneration == generation) return
            stopPositionSyncLoop()
        }
        positionSyncGeneration = generation
        positionSyncJob = scope.launch {
            try {
                while (isActive && isPlayRequestCurrent(generation)) {
                    val player = activeLocalPlayer() ?: break
                    delay(positionSyncIntervalMs(player))
                    if (!controllerMatchesAppState(player, generation)) break
                    syncFromController(generation)
                    if (!shouldKeepPlatformSyncing(player, generation)) break
                }
            } finally {
                if (positionSyncGeneration == generation) {
                    positionSyncGeneration = -1
                }
            }
        }
    }

    private fun shouldKeepPlatformSyncing(player: Player, generation: Int): Boolean {
        if (!isPlayRequestCurrent(generation)) return false
        val pendingAutoplay = pendingAutoplayGeneration == generation &&
            playWhenReady &&
            state.value.currentTrack != null &&
            player.playbackState != Player.STATE_ENDED &&
            !player.isPlaying
        return player.isPlaying ||
            (playWhenReady && (player.playbackState == Player.STATE_BUFFERING || pendingAutoplay || state.value.isBuffering))
    }

    private fun positionSyncIntervalMs(player: Player): Long {
        val durationMs = state.value.durationMs.takeIf { it > 0L } ?: return NormalPositionSyncIntervalMs
        val remainingMs = durationMs - player.currentPosition.coerceAtLeast(0L)
        return if (remainingMs in 0L..FinePositionSyncWindowMs) {
            FinePositionSyncIntervalMs
        } else {
            NormalPositionSyncIntervalMs
        }
    }

    private fun stopPositionSyncLoop() {
        positionSyncJob?.cancel()
        positionSyncJob = null
        positionSyncGeneration = -1
    }

    private suspend fun waitForPlatformSeek(
        player: Player,
        targetPositionMs: Long,
        generation: Int,
    ) {
        val initialMediaItemIndex = player.currentMediaItemIndex
        var waitedMs = 0L
        var retried = false
        while (waitedMs < SeekSettleTimeoutMs && isPlayRequestCurrent(generation)) {
            val positionMs = player.currentPosition.coerceAtLeast(0L)
            val durationMs = player.duration.coerceAtLeast(0L)
            if (isSeekPositionSettled(positionMs, targetPositionMs, durationMs) ||
                player.currentMediaItemIndex != initialMediaItemIndex ||
                player.playbackState == Player.STATE_ENDED
            ) {
                return
            }
            if (!retried && waitedMs >= SeekRetryDelayMs) {
                player.seekToCurrentItem(targetPositionMs)
                retried = true
            }
            delay(SeekSettlePollMs)
            waitedMs += SeekSettlePollMs
        }
    }

    private fun Player.seekToCurrentItem(positionMs: Long) {
        val itemIndex = currentMediaItemIndex.takeIf { it in 0 until mediaItemCount }
        if (itemIndex != null) {
            seekTo(itemIndex, positionMs)
        } else {
            seekTo(positionMs)
        }
    }

    private fun isWaitingForPlatformSeek(
        trackId: String?,
        controllerPositionMs: Long,
        durationMs: Long,
        generation: Int,
    ): Boolean {
        val pending = pendingPlatformSeek ?: return false
        if (!pending.matches(generation, trackId)) {
            pendingPlatformSeek = null
            return false
        }
        if (isSeekPositionSettled(controllerPositionMs, pending.positionMs, durationMs) ||
            SystemClock.elapsedRealtime() - pending.startedAtMs >= SeekSettleTimeoutMs
        ) {
            pendingPlatformSeek = null
            return false
        }
        return true
    }

    private fun isSeekPositionSettled(
        positionMs: Long,
        targetPositionMs: Long,
        durationMs: Long,
    ): Boolean {
        val boundedTargetMs = if (durationMs > 0L) {
            targetPositionMs.coerceAtMost(durationMs)
        } else {
            targetPositionMs
        }
        return abs(positionMs - boundedTargetMs) <= SeekSettleToleranceMs
    }

    private fun startBufferingTimeout(generation: Int) {
        if (bufferingTimeoutJob?.isActive == true) return
        bufferingTimeoutJob = scope.launch {
            delay(PlaybackBufferingTimeoutMs)
            if (!isPlayRequestCurrent(generation) || !state.value.isBuffering) return@launch
            handleClassifiedPlaybackFailure(
                PlaybackFailureClassifier.fromMessage(
                    "playback timed out while buffering",
                    currentStreamUri(),
                ),
                generation,
            )
        }
    }

    private fun stopBufferingTimeout() {
        bufferingTimeoutJob?.cancel()
        bufferingTimeoutJob = null
    }

    private fun schedulePlaybackRetry(error: PlaybackException?, generation: Int) {
        if (error != null) {
            handleClassifiedPlaybackFailure(error.toPlaybackFailure(currentStreamUri()), generation)
            return
        }
        if (!isPlayRequestCurrent(generation) || !playWhenReady) return
        if (retryGeneration != generation) {
            retryGeneration = generation
            retryCount = 0
        }
        if (retryCount >= MaxStreamRetryCount) {
            handleClassifiedPlaybackFailure(
                PlaybackFailureClassifier.fromMessage("stream retry exhausted", currentStreamUri()),
                generation,
            )
            return
        }
        retryCount++
        retryCurrentStream(generation)
    }

    private fun handleClassifiedPlaybackFailure(failure: PlaybackFailure, generation: Int) {
        if (!isPlayRequestCurrent(generation)) return
        PhoebeLog.d("AndroidAudioPlayer") { failure.logLine() }
        diagnostics.playbackError(PlaybackEnginePath.Media3, failure.logLine())
        if (failure.holdsQueue) {
            holdQueueOnInfrastructureFailure = true
        }
        if (!playWhenReady) {
            holdQueueOnFailure(failure)
            return
        }
        if (!failure.shouldRetry) {
            if (replayWithFailoverUri(generation, failure.streamUri ?: currentStreamUri())) {
                return
            }
            holdQueueOnFailure(failure)
            publishPlaybackFailure(failure, generation)
            return
        }
        if (retryGeneration != generation) {
            retryGeneration = generation
            retryCount = 0
        }
        val maxRetries = if (failure.kind == PlaybackFailureKind.Unreachable) {
            MaxUnreachableRetryCount
        } else {
            MaxStreamRetryCount
        }
        if (retryCount < maxRetries) {
            retryCount++
            retryCurrentStream(generation)
            return
        }
        if (replayWithFailoverUri(generation, failure.streamUri ?: currentStreamUri())) {
            retryCount = 0
            return
        }
        PhoebeLog.d("AndroidAudioPlayer") { "stream retry exhausted kind=${failure.kind}" }
        holdQueueOnFailure(failure)
        publishPlaybackFailure(failure, generation)
    }

    private fun retryCurrentStream(generation: Int) {
        retryJob?.cancel()
        val delayMs = StreamRetryBaseDelayMs * retryCount
        retryJob = scope.launch {
            delay(delayMs)
            if (!isPlayRequestCurrent(generation) || !playWhenReady) return@launch
            val uri = currentStreamUri() ?: return@launch
            val positionMs = controllerMutex.withLock {
                activeLocalPlayer()?.currentPosition?.coerceAtLeast(0L) ?: 0L
            }
            applyPlatformPlayback(
                positionMs = positionMs,
                durationMs = state.value.durationMs,
                isPlaying = false,
                isBuffering = true,
                bufferedPositionMs = state.value.bufferedPositionMs.coerceAtLeast(positionMs),
                generation = generation,
                forceBuffering = true,
            )
            stopBufferingTimeout()
            controllerMutex.withLock {
                val retryPlayer = activeLocalPlayer() ?: return@withLock
                val track = state.value.currentTrack
                val mediaItem = if (track != null) {
                    playbackMediaItem(track.copy(streamUrl = uri), inAppPlayback = true)
                } else {
                    MediaItem.Builder().setUri(uri).build()
                }
                retryPlayer.setMediaItem(mediaItem, positionMs)
                retryPlayer.prepare()
                markPendingAutoplay(generation)
                retryPlayer.play()
            }
            startBufferingTimeout(generation)
            syncFromController(generation)
        }
    }

    private fun holdQueueOnFailure(failure: PlaybackFailure) {
        clearPendingAutoplay()
        holdQueueOnInfrastructureFailure = failure.holdsQueue
        if (failure.holdsQueue) {
            cancelPlayIntent()
            scope.launch {
                controllerMutex.withLock {
                    activeLocalPlayer()?.pause()
                }
            }
        }
    }

    private fun currentStreamUri(): String? {
        val track = state.value.currentTrack ?: return null
        return StreamingPlaybackPolicyHolder.resolvePlaybackUri(track).takeIf { it.isNotBlank() }
    }

    private fun PlaybackException.toPlaybackFailure(streamUri: String?): PlaybackFailure {
        val httpError = generateSequence(this as Throwable) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
        val causeChain = generateSequence(this as Throwable) { it.cause }
            .drop(1)
            .map { throwable ->
                listOfNotNull(throwable::class.simpleName, throwable.message).joinToString(": ")
            }
            .filter { it.isNotBlank() }
            .toList()
        return PlaybackFailureClassifier.fromMedia3(
            errorCode = errorCode,
            message = message,
            causeChain = causeChain,
            httpStatus = httpError?.responseCode,
            streamUri = httpError?.dataSpec?.uri?.toString() ?: streamUri,
        )
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

    private fun publishLocalMediaSessionState(player: Player, track: Track?) {
        if (crossfadePlayer != null && crossfadePlayer !== player) return
        val currentTrack = track ?: state.value.currentTrack
        if (currentTrack == null) {
            clearLocalMediaSessionState()
            return
        }
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val durationMs = player.duration
            .takeIf { it > 0L }
            ?.coerceAtLeast(currentTrack.durationMs)
            ?: currentTrack.durationMs
        AndroidPlaybackBridge.onLocalMediaSessionState?.invoke(
            LocalMediaSessionState(
                track = currentTrack,
                isPlaying = player.isPlaying && player.playbackState != Player.STATE_BUFFERING,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                positionMs = positionMs,
                bufferedPositionMs = player.bufferedPosition.coerceAtLeast(positionMs).coerceAtLeast(0L),
                durationMs = durationMs,
            ),
        )
    }

    private fun clearLocalMediaSessionState() {
        AndroidPlaybackBridge.onLocalMediaSessionState?.invoke(null)
    }

    private fun reportPlaybackDiagnostics(
        engine: PlaybackEnginePath,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ) {
        diagnostics.playbackProgress(engine, positionMs, durationMs)
        if (isPlaying) {
            diagnostics.platformPlaying(engine, positionMs, durationMs)
        }
    }

    private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel(false) }
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
        const val PlaybackBufferingTimeoutMs = 9_000L
        const val AutoplayStartRetryMs = 2_000L
        const val MaxStreamRetryCount = 2
        const val MaxUnreachableRetryCount = 1
        const val StreamRetryBaseDelayMs = 1_000L
        const val SeekSettleTimeoutMs = 1_500L
        const val SeekSettlePollMs = 50L
        const val SeekRetryDelayMs = 250L
        const val SeekSettleToleranceMs = 500L
        const val NormalPositionSyncIntervalMs = 1_000L
        const val FinePositionSyncIntervalMs = 250L
        const val FinePositionSyncWindowMs = 12_000L
        const val CrossfadeSteps = 24
        const val CrossfadePrepareTimeoutMs = 5_000L
        const val CrossfadeMinimumFadeMs = 500L
        const val CodecTeardownSettleMs = 64L
        const val CodecTeardownTimeoutMs = 200L
        const val CodecTeardownPollMs = 25L
    }
}

internal fun shouldRetainPendingAutoplay(
    pendingGeneration: Int,
    generation: Int,
    playWhenReady: Boolean,
    hasCurrentTrack: Boolean,
    playerIsPlaying: Boolean,
    playbackState: Int,
    positionMs: Long,
): Boolean {
    if (pendingGeneration != generation || !playWhenReady || !hasCurrentTrack) return false
    if (playbackState == Player.STATE_ENDED) return false
    return !playerIsPlaying || positionMs < AndroidAutoplayConfirmedPositionMs
}

internal fun shouldRecoverUnconfirmedAutoplay(
    playWhenReady: Boolean,
    hasCurrentTrack: Boolean,
    playerIsPlaying: Boolean,
    playbackState: Int,
    positionMs: Long,
    controllerMatchesAppState: Boolean,
): Boolean {
    return playWhenReady &&
        hasCurrentTrack &&
        !playerIsPlaying &&
        playbackState != Player.STATE_ENDED &&
        positionMs < AndroidAutoplayConfirmedPositionMs &&
        controllerMatchesAppState
}

internal fun shouldAdoptPlatformPlayIntent(
    appControllerMutationInProgress: Boolean,
    platformTrackId: String?,
    appTrackId: String?,
): Boolean {
    return !appControllerMutationInProgress && platformTrackId == appTrackId
}

internal fun shouldCancelAndroidCrossfadeForControllerPause(
    crossfadeTransitionActive: Boolean,
    playWhenReady: Boolean,
    controllerPlayWhenReady: Boolean,
    appControllerMutationInProgress: Boolean,
): Boolean {
    return crossfadeTransitionActive &&
        playWhenReady &&
        !controllerPlayWhenReady &&
        !appControllerMutationInProgress
}

private fun String.isHttpUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
