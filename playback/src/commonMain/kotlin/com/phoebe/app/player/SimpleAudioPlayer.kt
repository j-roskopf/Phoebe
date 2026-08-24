package com.phoebe.app.player

import com.phoebe.app.domain.AudioAnalysisFrame
import com.phoebe.app.domain.AudioAnalysisSource
import com.phoebe.app.domain.AudioProcessingSettings
import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.domain.PlayerState
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.StreamingPolicySettings
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.currentTimeMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal const val PlaybackReadyBufferedAheadMs = 2_000L
internal const val PlaybackStartupTimeoutMs = 30_000L

internal fun hasPlaybackReadyBuffer(
    positionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
): Boolean {
    if (bufferedPositionMs <= positionMs) return false
    if (durationMs > 0L && bufferedPositionMs >= durationMs) return true
    return bufferedPositionMs - positionMs >= PlaybackReadyBufferedAheadMs
}

abstract class SimpleAudioPlayer(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : AudioPlayer {
    private val mutableState = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = mutableState
    private val mutableAudioAnalysis = MutableStateFlow(AudioAnalysisFrame.Empty)
    override val audioAnalysis: StateFlow<AudioAnalysisFrame> = mutableAudioAnalysis
    private val audioAnalysisAccumulator = AudioAnalysisAccumulator()
    private var progressJob: Job? = null
    private var playbackStartupJob: Job? = null
    private var preferUnityOutputVolume = false
    private var systemVolumeScale = 1f
    private var playGeneration = 0
    private var failoverGeneration = -1
    private val triedPlaybackUris = linkedSetOf<String>()
    private var crossfadeDurationMs = 0L
    private var crossfadeRequestKey: String? = null
    private var manualSeekCrossfadeSuppression: ManualSeekCrossfadeSuppression? = null
    private var audioProcessingSettings = AudioProcessingSettings().normalized()
    private var gaplessEnabled = false
    private var gaplessPrepareRequest: GaplessPrepareRequest? = null
    protected var equalizerProfile: EqualizerProfile = EqualizerProfile.Default.normalized()
        private set
    protected val isCrossfadeConfigured: Boolean
        get() = crossfadeDurationMs > 0L
    protected val isGaplessConfigured: Boolean
        get() = gaplessEnabled && crossfadeDurationMs == 0L

    /** When false, a superseded or user-paused load must not start audible playback. */
    protected var playWhenReady = false
        private set

    protected fun cancelPlayIntent() {
        playWhenReady = false
    }

    protected fun adoptPlatformPlayIntent(playWhenReady: Boolean) {
        this.playWhenReady = playWhenReady
    }

    protected val activePlayGeneration: Int
        get() = playGeneration

    protected fun isPlayRequestCurrent(generation: Int): Boolean = generation == playGeneration

    override fun play(queue: List<Track>, startIndex: Int) {
        val previous = mutableState.value
        val index = startIndex.coerceIn(queue.indices)
        val track = queue.getOrNull(index)
        val sameQueue = tracksMatch(previous.queue, queue)

        val sameCurrentTrack = sameQueue && track != null &&
            previous.currentIndex == index &&
            previous.currentTrack?.id == track.id
        val currentTrackEnded = sameCurrentTrack &&
            previous.durationMs > 0L &&
            previous.positionMs >= previous.durationMs

        if (sameCurrentTrack && !currentTrackEnded) {
            if (previous.isPlaying && !previous.isBuffering && playWhenReady) {
                return
            }
            clearCrossfadeRequestState()
            playWhenReady = true
            mutableState.value = previous.copy(
                isPlaying = !previous.isBuffering,
                playbackErrorMessage = null,
            )
            resume()
            if (!previous.isBuffering) {
                startProgressTicker()
            }
            return
        }

        playGeneration++
        clearCrossfadeRequestState()
        cancelGaplessPrepare()
        resetAudioAnalysis()
        playWhenReady = true
        val generation = playGeneration
        stopProgressTicker()
        if (!sameQueue) {
            stopCurrentPlaybackImmediately()
        }
        mutableState.value = previous.copy(
            queue = queue,
            currentIndex = if (track == null) -1 else index,
            isPlaying = false,
            isBuffering = track != null,
            positionMs = 0L,
            bufferedPositionMs = 0L,
            durationMs = track?.durationMs ?: 0L,
            playbackErrorMessage = null,
        )
        setOutputVolume(effectiveOutputVolume())
        if (track != null) {
            resetPlaybackUriFailover(generation)
            val initialUri = StreamingPlaybackPolicyHolder.resolvePlaybackUri(track)
                .ifBlank { track.playbackUriCandidates().firstOrNull().orEmpty() }
            if (initialUri.isNotBlank()) notePlaybackUri(initialUri, generation)
            startPlaybackStartupWatchdog(generation)
            if (sameQueue) {
                skipToInQueueOnPlatform(queue, index, track, generation)
            } else {
                playQueueOnPlatform(queue, index, track, generation)
            }
        }
    }

    override fun playShuffled(queue: List<Track>, startIndex: Int) {
        play(queue, startIndex)
        val state = mutableState.value
        if (!state.shuffle) {
            mutableState.value = state.copy(shuffle = true)
        }
    }

    override fun prepare(queue: List<Track>, startIndex: Int, positionMs: Long) {
        val previous = mutableState.value
        val index = startIndex.coerceIn(queue.indices)
        val track = queue.getOrNull(index)
        val generation = ++playGeneration
        clearCrossfadeRequestState()
        cancelGaplessPrepare()
        resetAudioAnalysis()
        playWhenReady = false
        stopProgressTicker()
        stopCurrentPlaybackImmediately()
        val boundedPositionMs = positionMs.coerceAtLeast(0L).let { position ->
            val duration = track?.durationMs ?: 0L
            if (duration > 0L) position.coerceAtMost(duration) else position
        }
        mutableState.value = previous.copy(
            queue = queue,
            currentIndex = if (track == null) -1 else index,
            isPlaying = false,
            isBuffering = track != null,
            positionMs = boundedPositionMs,
            bufferedPositionMs = boundedPositionMs,
            durationMs = track?.durationMs ?: 0L,
            playbackErrorMessage = null,
        )
        setOutputVolume(effectiveOutputVolume())
        if (track != null) {
            playQueueOnPlatform(queue, index, track, generation)
            if (boundedPositionMs > 0L) {
                seek(boundedPositionMs)
            }
        }
    }

    override fun suspendPlayback(queue: List<Track>, startIndex: Int, positionMs: Long) {
        val previous = mutableState.value
        val index = if (queue.isEmpty()) -1 else startIndex.coerceIn(queue.indices)
        val track = queue.getOrNull(index)
        playGeneration++
        clearCrossfadeRequestState()
        cancelGaplessPrepare()
        resetAudioAnalysis()
        playWhenReady = false
        stopProgressTicker()
        stopCurrentPlaybackImmediately()
        val boundedPositionMs = positionMs.coerceAtLeast(0L).let { position ->
            val duration = track?.durationMs ?: 0L
            if (duration > 0L) position.coerceAtMost(duration) else position
        }
        mutableState.value = previous.copy(
            queue = queue,
            currentIndex = if (track == null) -1 else index,
            isPlaying = false,
            isBuffering = false,
            positionMs = boundedPositionMs,
            bufferedPositionMs = boundedPositionMs,
            durationMs = track?.durationMs ?: 0L,
            playbackErrorMessage = null,
        )
        setOutputVolume(effectiveOutputVolume())
    }

    override fun togglePlayPause() {
        val state = mutableState.value
        if (state.isBuffering) {
            playWhenReady = false
            mutableState.value = state.copy(isPlaying = false, isBuffering = false)
            cancelGaplessPrepare()
            pause()
            stopCurrentPlaybackImmediately()
            return
        }
        val nextPlaying = !state.isPlaying
        playWhenReady = nextPlaying
        mutableState.value = state.copy(isPlaying = nextPlaying)
        if (nextPlaying) {
            resume()
            startProgressTicker()
        } else {
            pause()
            cancelGaplessPrepare()
            stopProgressTicker()
        }
    }

    override fun clearQueue() {
        val state = mutableState.value
        if (state.currentIndex < 0) {
            mutableState.value = state.copy(queue = emptyList())
            return
        }
        val keep = (state.currentIndex + 1).coerceAtMost(state.queue.size)
        val nextQueue = state.queue.subList(0, keep).toList()
        mutableState.value = state.copy(queue = nextQueue)
        cancelGaplessPrepare()
        onQueueEdited(nextQueue, state.currentIndex)
    }

    override fun stopPlayback() {
        playGeneration++
        playWhenReady = false
        clearCrossfadeRequestState()
        cancelGaplessPrepare()
        resetAudioAnalysis()
        stopProgressTicker()
        stopPlaybackStartupWatchdog()
        stopCurrentPlaybackImmediately()
        val volume = mutableState.value.volume
        mutableState.value = PlayerState(volume = volume)
    }

    override fun addToUpNext(track: Track) {
        val state = mutableState.value
        val deduped = state.queue.filterNot { it.id == track.id }
        val insertAt = (state.currentIndex + 1).coerceIn(0, deduped.size)
        val newQueue = deduped.toMutableList().also { it.add(insertAt, track) }
        val newCurrent = if (state.currentIndex < 0) state.currentIndex
        else newQueue.indexOfFirst { it.id == state.currentTrack?.id }.takeIf { it >= 0 } ?: state.currentIndex
        mutableState.value = state.copy(queue = newQueue, currentIndex = newCurrent)
        cancelGaplessPrepare()
        onQueueEdited(newQueue, newCurrent)
    }

    override fun appendToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val state = mutableState.value
        val existingIds = state.queue.map { it.id }.toMutableSet()
        val additions = tracks.filter { existingIds.add(it.id) }
        if (additions.isEmpty()) return
        val nextQueue = state.queue + additions
        mutableState.value = state.copy(queue = nextQueue)
        cancelGaplessPrepare()
        onQueueEdited(nextQueue, state.currentIndex)
    }

    override fun moveUpNext(fromIndex: Int, toIndex: Int) {
        val state = mutableState.value
        val base = state.currentIndex + 1
        val upNext = state.upNext
        if (fromIndex !in upNext.indices) return
        val target = toIndex.coerceIn(0, upNext.lastIndex)
        if (target == fromIndex) return
        val newQueue = state.queue.toMutableList()
        val moved = newQueue.removeAt(base + fromIndex)
        newQueue.add(base + target, moved)
        mutableState.value = state.copy(queue = newQueue)
        cancelGaplessPrepare()
        onQueueEdited(newQueue, state.currentIndex)
    }

    override fun removeUpNext(index: Int) {
        val state = mutableState.value
        val base = state.currentIndex + 1
        if (index !in state.upNext.indices) return
        val newQueue = state.queue.toMutableList().also { it.removeAt(base + index) }
        mutableState.value = state.copy(queue = newQueue)
        cancelGaplessPrepare()
        onQueueEdited(newQueue, state.currentIndex)
    }

    override fun next() {
        advanceNext(allowCrossfade = false)
    }

    private fun advanceNext(allowCrossfade: Boolean) {
        val state = mutableState.value
        if (state.currentIndex < 0 || state.queue.isEmpty()) return
        when (state.repeat) {
            RepeatMode.One -> {
                if (!allowCrossfade || !crossfadeToIndex(state.currentIndex)) play(state.queue, state.currentIndex)
            }
            RepeatMode.All -> {
                val target = if (state.currentIndex >= state.queue.lastIndex) 0 else state.currentIndex + 1
                if (!allowCrossfade || !crossfadeToIndex(target)) play(state.queue, target)
            }
            RepeatMode.Off -> {
                val target = state.currentIndex + 1
                if (target <= state.queue.lastIndex) {
                    if (!allowCrossfade || !crossfadeToIndex(target)) play(state.queue, target)
                } else {
                    playWhenReady = false
                    mutableState.value = state.copy(
                        isPlaying = false,
                        isBuffering = false,
                        positionMs = state.durationMs.takeIf { it > 0L } ?: state.positionMs,
                        bufferedPositionMs = state.durationMs.takeIf { it > 0L } ?: state.bufferedPositionMs,
                    )
                    stopProgressTicker()
                }
            }
        }
    }

    override fun previous() {
        val state = mutableState.value
        val previousIndex = (state.currentIndex - 1).coerceAtLeast(0)
        if (previousIndex >= 0) play(state.queue, previousIndex)
    }

    override fun seekTo(positionMs: Long) {
        val current = mutableState.value
        val boundedPositionMs = positionMs.coerceAtLeast(0L).let { position ->
            if (current.durationMs > 0L) position.coerceAtMost(current.durationMs) else position
        }
        manualSeekCrossfadeSuppression = if (shouldSuppressCrossfadeAfterManualSeek(current, boundedPositionMs)) {
            ManualSeekCrossfadeSuppression(
                generation = activePlayGeneration,
                trackId = current.currentTrack?.id,
            )
        } else {
            null
        }
        mutableState.value = current.copy(positionMs = boundedPositionMs)
        cancelGaplessPrepare()
        seek(boundedPositionMs)
    }

    override fun setShuffle(enabled: Boolean) {
        val state = mutableState.value
        if (enabled == state.shuffle) return
        if (!enabled) {
            mutableState.value = state.copy(shuffle = false)
            cancelGaplessPrepare()
            return
        }
        // Pre-shuffle just the upcoming portion of the queue so the current track
        // keeps playing and the user can see the new order in Up Next.
        if (state.currentIndex < 0 || state.currentIndex >= state.queue.lastIndex) {
            mutableState.value = state.copy(shuffle = true)
            cancelGaplessPrepare()
            return
        }
        val head = state.queue.subList(0, state.currentIndex + 1).toList()
        val tail = state.queue.subList(state.currentIndex + 1, state.queue.size).shuffled()
        val nextQueue = head + tail
        mutableState.value = state.copy(shuffle = true, queue = nextQueue)
        cancelGaplessPrepare()
        onQueueEdited(nextQueue, state.currentIndex)
    }

    override fun setRepeat(mode: RepeatMode) {
        mutableState.value = mutableState.value.copy(repeat = mode)
        cancelGaplessPrepare()
    }

    override fun setVolume(volume: Float) {
        val coerced = volume.coerceIn(0f, 1f)
        mutableState.value = mutableState.value.copy(volume = coerced)
        if (!preferUnityOutputVolume) {
            setOutputVolume(effectiveOutputVolume())
        }
    }

    override fun setCrossfadeDurationMs(durationMs: Long) {
        crossfadeDurationMs = durationMs.coerceIn(0L, MaxCrossfadeDurationMs)
        if (crossfadeDurationMs == 0L) {
            clearCrossfadeRequestState()
        } else {
            cancelGaplessPrepare()
        }
    }

    override fun setAudioProcessing(settings: AudioProcessingSettings) {
        val normalized = settings.normalized()
        if (audioProcessingSettings == normalized) return
        audioProcessingSettings = normalized
        gaplessEnabled = normalized.gaplessEnabled
        cancelGaplessPrepare()
    }

    override fun setStreamingPolicy(settings: StreamingPolicySettings) {
        StreamingPlaybackPolicyHolder.settings = settings.normalized()
    }

    override fun setEqualizer(profile: EqualizerProfile) {
        val normalized = profile.normalized()
        if (equalizerProfile == normalized) return
        equalizerProfile = normalized
        cancelGaplessPrepare()
        applyEqualizer(normalized)
    }

    override fun setUnityOutputVolume() {
        preferUnityOutputVolume = true
        setOutputVolume(effectiveOutputVolume())
    }

    override fun setSystemVolumeScale(scale: Float) {
        systemVolumeScale = scale.coerceIn(0f, 1f)
        setOutputVolume(effectiveOutputVolume())
    }

    override fun updateReportedVolume(volume: Float) {
        val coerced = volume.coerceIn(0f, 1f)
        if (mutableState.value.volume != coerced) {
            mutableState.value = mutableState.value.copy(volume = coerced)
        }
    }

    /** Adopt queue state without touching platform output (Android Auto / MediaSession playlist). */
    protected fun adoptQueueState(queue: List<Track>, startIndex: Int, isPlaying: Boolean) {
        val index = startIndex.coerceIn(queue.indices)
        val track = queue.getOrNull(index)
        mutableState.value = mutableState.value.copy(
            queue = queue,
            currentIndex = if (track == null) -1 else index,
            isPlaying = isPlaying && track != null,
            isBuffering = false,
            positionMs = 0L,
            bufferedPositionMs = 0L,
            durationMs = track?.durationMs ?: 0L,
        )
        manualSeekCrossfadeSuppression = null
        if (isPlaying && track != null && useProgressTicker) {
            startProgressTicker()
        } else {
            stopProgressTicker()
        }
    }

    /** When false, [applyPlatformPlayback] drives position instead of the 1s ticker (Android). */
    protected open val useProgressTicker: Boolean get() = true

    protected fun applyPlatformPlayback(
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        isBuffering: Boolean = false,
        bufferedPositionMs: Long = mutableState.value.bufferedPositionMs,
        generation: Int = playGeneration,
        forceBuffering: Boolean = false,
    ) {
        if (!isPlayRequestCurrent(generation)) return
        val current = mutableState.value
        val effectivePlaying = isPlaying && playWhenReady
        val effectiveDurationMs = if (durationMs > 0L) durationMs else current.durationMs
        val effectiveBufferedPositionMs = bufferedPositionMs
            .coerceAtLeast(positionMs)
            .coerceAtLeast(current.bufferedPositionMs)
            .let { buffered ->
                if (effectiveDurationMs > 0L) buffered.coerceAtMost(effectiveDurationMs) else buffered
            }
        val effectiveBuffering = isBuffering &&
            playWhenReady &&
            (forceBuffering ||
                !hasPlaybackReadyBuffer(
                    positionMs = positionMs,
                    bufferedPositionMs = effectiveBufferedPositionMs,
                    durationMs = effectiveDurationMs,
                ))
        if (current.positionMs == positionMs &&
            current.bufferedPositionMs == effectiveBufferedPositionMs &&
            current.durationMs == effectiveDurationMs &&
            current.isPlaying == effectivePlaying &&
            current.isBuffering == effectiveBuffering
        ) {
            return
        }
        mutableState.value = current.copy(
            positionMs = positionMs,
            bufferedPositionMs = effectiveBufferedPositionMs,
            durationMs = effectiveDurationMs,
            isPlaying = effectivePlaying,
            isBuffering = effectiveBuffering,
        )
        if (effectivePlaying && useProgressTicker) {
            startProgressTicker()
        } else {
            stopProgressTicker()
        }
        maybeStartCrossfadeAtPosition(generation, positionMs)
        maybeStartGaplessAtPosition(generation, positionMs)
    }

    protected fun publishAudioAnalysis(frame: AudioAnalysisFrame) {
        mutableAudioAnalysis.value = frame.normalized()
    }

    protected fun canPublishAudioAnalysis(timestampMs: Long = currentTimeMs()): Boolean =
        audioAnalysisAccumulator.canPublish(timestampMs)

    protected fun publishAudioAnalysisPcm(
        samples: FloatArray,
        sampleRateHz: Float,
        source: AudioAnalysisSource = AudioAnalysisSource.Pcm,
        timestampMs: Long = currentTimeMs(),
    ) {
        audioAnalysisAccumulator
            .observePcm(samples, sampleRateHz, timestampMs, source)
            ?.let(::publishAudioAnalysis)
    }

    protected fun publishAudioAnalysisMagnitudesDb(
        magnitudesDb: FloatArray,
        source: AudioAnalysisSource = AudioAnalysisSource.Spectrum,
        timestampMs: Long = currentTimeMs(),
    ) {
        audioAnalysisAccumulator
            .observeMagnitudesDb(magnitudesDb, timestampMs, source)
            ?.let(::publishAudioAnalysis)
    }

    protected fun resetAudioAnalysis() {
        audioAnalysisAccumulator.reset()
        mutableAudioAnalysis.value = AudioAnalysisFrame.Empty.copy(timestampMs = currentTimeMs())
    }

    /** Stop audible output immediately when leaving the current track (before the next loads). */
    protected open fun stopCurrentPlaybackImmediately() = Unit

    /** Keep a platform playlist aligned after queue-only edits that do not reload playback. */
    protected open fun onQueueEdited(queue: List<Track>, currentIndex: Int) = Unit

    protected fun markPlaybackReady(isPlaying: Boolean = true, generation: Int = playGeneration) {
        if (!isPlayRequestCurrent(generation)) return
        stopPlaybackStartupWatchdog()
        val current = mutableState.value
        val effectivePlaying = isPlaying && playWhenReady
        mutableState.value = current.copy(
            isBuffering = false,
            isPlaying = effectivePlaying,
            bufferedPositionMs = current.bufferedPositionMs.coerceAtLeast(current.positionMs),
            playbackErrorMessage = null,
        )
        if (effectivePlaying && useProgressTicker) {
            startProgressTicker()
        }
    }

    protected fun updateBufferedPosition(bufferedPositionMs: Long, generation: Int = playGeneration) {
        if (!isPlayRequestCurrent(generation)) return
        val current = mutableState.value
        val boundedBufferedPositionMs = bufferedPositionMs
            .coerceAtLeast(current.positionMs)
            .let { buffered ->
                if (current.durationMs > 0L) buffered.coerceAtMost(current.durationMs) else buffered
            }
        if (boundedBufferedPositionMs != current.bufferedPositionMs) {
            mutableState.value = current.copy(bufferedPositionMs = boundedBufferedPositionMs)
        }
    }

    protected fun publishPlaybackFailure(
        failure: PlaybackFailure,
        generation: Int = playGeneration,
    ) {
        markPlaybackFailed(
            generation = generation,
            message = failure.userMessage(mutableState.value.currentTrack?.title),
            cancelPlayIntent = failure.holdsQueue,
        )
    }

    protected fun markPlaybackFailed(
        generation: Int = playGeneration,
        message: String? = null,
        cancelPlayIntent: Boolean = false,
    ) {
        if (!isPlayRequestCurrent(generation)) return
        if (cancelPlayIntent) {
            playWhenReady = false
        }
        stopPlaybackStartupWatchdog()
        val current = mutableState.value
        mutableState.value = current.copy(
            isBuffering = false,
            isPlaying = false,
            playbackErrorSerial = current.playbackErrorSerial + 1,
            playbackErrorMessage = message,
        )
        cancelGaplessPrepare()
        stopProgressTicker()
    }

    protected fun markPlaybackWaitingForUserGesture(generation: Int = playGeneration) {
        if (!isPlayRequestCurrent(generation)) return
        stopPlaybackStartupWatchdog()
        val current = mutableState.value
        mutableState.value = current.copy(
            isBuffering = false,
            isPlaying = false,
            playbackErrorMessage = null,
        )
        cancelGaplessPrepare()
        stopProgressTicker()
    }

    protected fun surfacePlaybackNotice(generation: Int = playGeneration, message: String) {
        if (!isPlayRequestCurrent(generation)) return
        val current = mutableState.value
        mutableState.value = current.copy(
            playbackNoticeSerial = current.playbackNoticeSerial + 1,
            playbackNoticeMessage = message,
        )
    }

    protected abstract fun playUri(uri: String)

    /** Seek within an already-loaded queue without tearing down platform output. */
    protected open fun skipToInQueueOnPlatform(
        queue: List<Track>,
        startIndex: Int,
        track: Track,
        generation: Int,
    ) {
        playQueueOnPlatform(queue, startIndex, track, generation)
    }

    /** Push the active queue to the platform player; default plays only the current track. */
    protected open fun playQueueOnPlatform(
        queue: List<Track>,
        startIndex: Int,
        track: Track,
        generation: Int = activePlayGeneration,
        startPositionMs: Long = 0L,
    ) {
        playTrack(track)
        if (startPositionMs > 0L) seek(startPositionMs)
    }

    private fun tracksMatch(left: List<Track>, right: List<Track>): Boolean {
        if (left.size != right.size) return false
        return left.indices.all { left[it].id == right[it].id }
    }

    protected open fun playTrack(track: Track) {
        playUri(StreamingPlaybackPolicyHolder.resolvePlaybackUri(track))
    }
    protected open fun pause() = Unit
    protected open fun resume() = Unit
    protected open fun seek(positionMs: Long) = Unit
    protected open fun setOutputVolume(volume: Float) = Unit
    protected open fun applyEqualizer(profile: EqualizerProfile) = Unit

    protected open fun startCrossfadeOnPlatform(
        queue: List<Track>,
        targetIndex: Int,
        track: Track,
        durationMs: Long,
        baseVolume: Float,
        generation: Int,
    ): Boolean = false

    protected open fun startGaplessPrepareOnPlatform(
        queue: List<Track>,
        targetIndex: Int,
        track: Track,
        generation: Int,
    ): Boolean = false

    protected open fun commitGaplessPreparedOnPlatform(
        queue: List<Track>,
        targetIndex: Int,
        track: Track,
        generation: Int,
    ): Boolean = false

    protected open fun cancelGaplessPrepareOnPlatform(generation: Int) = Unit

    protected fun advanceAfterPlatformTrackEnded(generation: Int = playGeneration) {
        if (!playWhenReady) return
        if (commitPreparedGapless(generation)) return
        advanceNext(allowCrossfade = false)
    }

    protected fun clearGaplessPrepareState() {
        gaplessPrepareRequest = null
    }

    protected fun adoptCrossfadeTarget(
        queue: List<Track>,
        targetIndex: Int,
        positionMs: Long,
        generation: Int,
    ) {
        if (!isPlayRequestCurrent(generation)) return
        val track = queue.getOrNull(targetIndex) ?: return
        val boundedPositionMs = positionMs.coerceAtLeast(0L).let { position ->
            if (track.durationMs > 0L) position.coerceAtMost(track.durationMs) else position
        }
        mutableState.value = mutableState.value.copy(
            queue = queue,
            currentIndex = targetIndex,
            isPlaying = playWhenReady,
            isBuffering = false,
            positionMs = boundedPositionMs,
            bufferedPositionMs = boundedPositionMs,
            durationMs = track.durationMs,
            playbackErrorMessage = null,
        )
        clearCrossfadeRequestState()
        cancelGaplessPrepare()
    }

    protected fun adoptGaplessTarget(
        queue: List<Track>,
        targetIndex: Int,
        positionMs: Long,
        generation: Int,
    ) {
        if (!isPlayRequestCurrent(generation)) return
        val track = queue.getOrNull(targetIndex) ?: return
        val boundedPositionMs = positionMs.coerceAtLeast(0L).let { position ->
            if (track.durationMs > 0L) position.coerceAtMost(track.durationMs) else position
        }
        mutableState.value = mutableState.value.copy(
            queue = queue,
            currentIndex = targetIndex,
            isPlaying = playWhenReady,
            isBuffering = false,
            positionMs = boundedPositionMs,
            bufferedPositionMs = boundedPositionMs,
            durationMs = track.durationMs,
            playbackErrorMessage = null,
        )
        clearGaplessPrepareState()
    }

    protected fun effectiveOutputVolume(): Float {
        val playerLevel = if (preferUnityOutputVolume) 1f else mutableState.value.volume.coerceIn(0f, 1f)
        return (playerLevel * systemVolumeScale).coerceIn(0f, 1f)
    }

    private fun startProgressTicker() {
        if (!useProgressTicker) return
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                delay(1000)
                val current = mutableState.value
                if (!current.isPlaying) break
                val nextPosition = (current.positionMs + 1000L).coerceAtMost(current.durationMs)
                mutableState.value = current.copy(positionMs = nextPosition)
                maybeStartCrossfadeAtPosition(activePlayGeneration, nextPosition)
                maybeStartGaplessAtPosition(activePlayGeneration, nextPosition)
                if (nextPosition >= current.durationMs && current.durationMs > 0L) break
            }
        }
    }

    private fun stopProgressTicker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun startPlaybackStartupWatchdog(generation: Int) {
        playbackStartupJob?.cancel()
        playbackStartupJob = scope.launch {
            delay(playbackStartupTimeoutMs)
            markPlaybackStartupTimedOut(generation)
        }
    }

    private fun stopPlaybackStartupWatchdog() {
        playbackStartupJob?.cancel()
        playbackStartupJob = null
    }

    private fun markPlaybackStartupTimedOut(generation: Int) {
        onPlaybackStartupTimedOut(generation)
    }

    protected open fun onPlaybackStartupTimedOut(generation: Int) {
        if (!isPlayRequestCurrent(generation)) return
        if (!mutableState.value.isBuffering) {
            stopPlaybackStartupWatchdog()
            return
        }
        val track = mutableState.value.currentTrack
        val streamUri = track?.localUri?.takeIf { it.isNotBlank() }
            ?: track?.streamUrl?.takeIf { it.isNotBlank() }
        if (replayWithFailoverUri(generation, streamUri)) return
        publishPlaybackFailure(
            PlaybackFailureClassifier.fromMessage("Playback took too long to start.", streamUri),
            generation,
        )
    }

    protected fun resetPlaybackUriFailover(generation: Int) {
        failoverGeneration = generation
        triedPlaybackUris.clear()
        StreamingPlaybackPolicyHolder.clearDirectStreamPreference()
    }

    protected fun notePlaybackUri(uri: String, generation: Int) {
        if (failoverGeneration != generation) {
            resetPlaybackUriFailover(generation)
        }
        if (uri.isNotBlank()) triedPlaybackUris += uri
    }

    protected fun nextPlaybackFailoverUri(generation: Int, failedUri: String?): String? {
        notePlaybackUri(failedUri.orEmpty(), generation)
        val track = mutableState.value.currentTrack ?: return null
        return nextPlaybackFailoverCandidate(
            candidates = track.playbackUriCandidates(),
            tried = triedPlaybackUris,
            failedUri = failedUri,
        )
    }

    protected fun replayWithFailoverUri(generation: Int, failedUri: String?): Boolean {
        if (!isPlayRequestCurrent(generation) || !playWhenReady) return false
        val next = nextPlaybackFailoverUri(generation, failedUri) ?: return false
        val trackId = mutableState.value.currentTrack?.id
        if (!failedUri.isNullOrBlank() && failedUri.isPlexUniversalTranscodeUrl() && !trackId.isNullOrBlank()) {
            StreamingPlaybackPolicyHolder.preferDirectStreamFor(trackId)
        }
        notePlaybackUri(next, generation)
        adoptFailoverStreamUrl(next)
        val current = mutableState.value
        val track = current.currentTrack ?: return false
        val index = current.currentIndex.takeIf { it in current.queue.indices } ?: return false
        val resumePositionMs = current.positionMs.coerceAtLeast(0L)
        mutableState.value = current.copy(
            isBuffering = true,
            isPlaying = false,
            positionMs = resumePositionMs,
            playbackErrorMessage = null,
        )
        PhoebeLog.d("AudioPlayer") {
            "playback failover uri=${PlaybackFailureClassifier.redactStreamUri(next)} positionMs=$resumePositionMs"
        }
        startPlaybackStartupWatchdog(generation)
        playQueueOnPlatform(
            queue = mutableState.value.queue,
            startIndex = index,
            track = track.copy(streamUrl = next),
            generation = generation,
            startPositionMs = resumePositionMs,
        )
        return true
    }

    private fun adoptFailoverStreamUrl(uri: String) {
        val current = mutableState.value
        val index = current.currentIndex
        val track = current.queue.getOrNull(index) ?: return
        if (track.streamUrl == uri) return
        mutableState.value = current.copy(
            queue = current.queue.mapIndexed { itemIndex, item ->
                if (itemIndex == index) item.copy(streamUrl = uri) else item
            },
        )
    }

    protected open val playbackStartupTimeoutMs: Long
        get() = PlaybackStartupTimeoutMs

    protected fun maybeStartCrossfadeAtPosition(generation: Int, positionMs: Long) {
        val duration = crossfadeDurationMs
        if (duration <= 0L || !isPlayRequestCurrent(generation)) return
        val current = mutableState.value
        if (!current.isPlaying || current.durationMs <= 0L || current.currentIndex !in current.queue.indices) return
        if (manualSeekCrossfadeSuppression?.matches(generation, current.currentTrack?.id) == true) return
        if (current.currentIndex >= current.queue.lastIndex && current.repeat != RepeatMode.All) return
        val remaining = current.durationMs - positionMs
        if (remaining > duration) return
        val target = when (current.repeat) {
            RepeatMode.One -> current.currentIndex
            RepeatMode.All -> if (current.currentIndex >= current.queue.lastIndex) 0 else current.currentIndex + 1
            RepeatMode.Off -> current.currentIndex + 1
        }
        if (target in current.queue.indices) {
            crossfadeToIndex(target)
        }
    }

    private fun crossfadeToIndex(targetIndex: Int): Boolean {
        val duration = crossfadeDurationMs
        val current = mutableState.value
        if (duration <= 0L || !current.isPlaying || targetIndex !in current.queue.indices) return false
        val generation = activePlayGeneration
        val requestKey = "$generation:$targetIndex"
        if (crossfadeRequestKey == requestKey) return true
        val baseVolume = effectiveOutputVolume()
        val targetTrack = current.queue[targetIndex]
        crossfadeRequestKey = requestKey
        val accepted = startCrossfadeOnPlatform(current.queue, targetIndex, targetTrack, duration, baseVolume, generation)
        if (accepted) {
            return true
        }
        crossfadeRequestKey = null
        return false
    }

    protected fun maybeStartGaplessAtPosition(generation: Int, positionMs: Long) {
        if (!isGaplessConfigured || !isPlayRequestCurrent(generation)) return
        val current = mutableState.value
        if (!current.isPlaying ||
            current.isBuffering ||
            current.durationMs <= 0L ||
            current.currentIndex !in current.queue.indices
        ) {
            return
        }
        val remainingMs = current.durationMs - positionMs
        if (remainingMs > GaplessPrepareWindowMs) return
        val targetIndex = gaplessTargetIndex(current) ?: return
        val targetTrack = current.queue.getOrNull(targetIndex)?.takeIf { it.isGaplessCandidate() } ?: return
        val existing = gaplessPrepareRequest
        if (existing?.matches(generation, current.currentIndex, targetIndex, targetTrack.id) == true) return
        if (existing != null) cancelGaplessPrepare()
        val accepted = startGaplessPrepareOnPlatform(current.queue, targetIndex, targetTrack, generation)
        if (accepted) {
            gaplessPrepareRequest = GaplessPrepareRequest(
                generation = generation,
                sourceIndex = current.currentIndex,
                targetIndex = targetIndex,
                targetTrackId = targetTrack.id,
            )
        }
    }

    private fun gaplessTargetIndex(current: PlayerState): Int? =
        when (current.repeat) {
            RepeatMode.One -> current.currentIndex
            RepeatMode.All -> if (current.currentIndex >= current.queue.lastIndex) 0 else current.currentIndex + 1
            RepeatMode.Off -> (current.currentIndex + 1).takeIf { it <= current.queue.lastIndex }
        }

    protected fun commitPreparedGapless(generation: Int): Boolean {
        val request = gaplessPrepareRequest ?: return false
        val current = mutableState.value
        val targetTrack = current.queue.getOrNull(request.targetIndex)
        if (!isGaplessConfigured ||
            !request.matches(generation, current.currentIndex, request.targetIndex, targetTrack?.id)
        ) {
            cancelGaplessPrepare()
            return false
        }
        if (targetTrack == null || !targetTrack.isGaplessCandidate()) {
            cancelGaplessPrepare()
            return false
        }
        val committed = commitGaplessPreparedOnPlatform(current.queue, request.targetIndex, targetTrack, generation)
        if (!committed) {
            clearGaplessPrepareState()
            return false
        }
        adoptGaplessTarget(
            queue = current.queue,
            targetIndex = request.targetIndex,
            positionMs = 0L,
            generation = generation,
        )
        return true
    }

    private fun cancelGaplessPrepare() {
        val request = gaplessPrepareRequest ?: return
        gaplessPrepareRequest = null
        cancelGaplessPrepareOnPlatform(request.generation)
    }

    private fun clearCrossfadeRequestState() {
        crossfadeRequestKey = null
        manualSeekCrossfadeSuppression = null
    }

    private fun shouldSuppressCrossfadeAfterManualSeek(
        current: PlayerState,
        positionMs: Long,
    ): Boolean {
        val duration = crossfadeDurationMs
        if (duration <= 0L || current.durationMs <= 0L) return false
        if (current.currentTrack == null || current.currentIndex !in current.queue.indices) return false
        if (current.currentIndex >= current.queue.lastIndex && current.repeat != RepeatMode.All) return false
        return current.durationMs - positionMs <= duration
    }

    private companion object {
        const val MaxCrossfadeDurationMs = 12_000L
        const val GaplessPrepareWindowMs = 3_000L
    }
}

private data class ManualSeekCrossfadeSuppression(
    val generation: Int,
    val trackId: String?,
) {
    fun matches(generation: Int, trackId: String?): Boolean =
        this.generation == generation && this.trackId == trackId
}

private data class GaplessPrepareRequest(
    val generation: Int,
    val sourceIndex: Int,
    val targetIndex: Int,
    val targetTrackId: String,
) {
    fun matches(
        generation: Int,
        sourceIndex: Int,
        targetIndex: Int,
        targetTrackId: String?,
    ): Boolean =
        this.generation == generation &&
            this.sourceIndex == sourceIndex &&
            this.targetIndex == targetIndex &&
            this.targetTrackId == targetTrackId
}

private fun Track.isGaplessCandidate(): Boolean {
    if (durationMs <= 0L) return false
    if (id.startsWith("radio:")) return false
    val uri = localUri?.takeIf { it.isNotBlank() } ?: streamUrl.takeIf { it.isNotBlank() } ?: return false
    val lower = uri.lowercase()
    return !lower.contains(".m3u8") &&
        !lower.contains(".m3u") &&
        !lower.contains("/live") &&
        !lower.contains("://radio.") &&
        !lower.contains(".radio.") &&
        !lower.contains("/radio/") &&
        !lower.endsWith("/radio")
}
