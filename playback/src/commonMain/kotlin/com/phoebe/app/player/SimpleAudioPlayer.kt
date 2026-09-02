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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal const val PlaybackReadyBufferedAheadMs = 500L
internal const val PlaybackStartupTimeoutMs = 9_000L
internal const val PlaybackStartupTimeoutLocalMs = 4_000L
internal const val PlaybackStartupTimeoutRemoteMs = 9_000L
/** Initial back-to-back races before slowing into sustained reconnect. */
internal const val ColdOriginResolveAttempts = 3
internal const val ColdOriginResolveRetryDelayMs = 500L
/**
 * Keep trying to bind a live origin while the user still wants audio. Giving up early left
 * Windows/cellular clients stuck until a manual tap, even after AppState later found a relay.
 */
internal const val ColdOriginResolveMaxAttempts = 40
internal const val ColdOriginResolveSustainedDelayMs = 2_000L
internal const val ColdOriginRediscoverEveryAttempts = 3

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
    private var originRediscoveryGeneration = -1
    private var originRetryGeneration = -1
    /** Origin the stream currently open on the platform player was built from. */
    private var openPlaybackOrigin: String? = null
    /**
     * [playGeneration] for which [launchPreparedPlayback] already handed work to the platform.
     * Used so same-track resume does not short-circuit a cold origin wait that never opened.
     */
    private var preparedPlayGeneration = -1
    private val triedPlaybackUris = linkedSetOf<String>()
    private var stickyPlaybackOrigin: String? = null
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
        if (queue.isEmpty()) return
        val previous = mutableState.value
        val playbackQueue = queue.preferStickyPlaybackOrigin()
        val index = startIndex.coerceIn(playbackQueue.indices)
        val track = playbackQueue.getOrNull(index)
        val sameQueue = tracksMatch(previous.queue, playbackQueue)

        val sameCurrentTrack = sameQueue && track != null &&
            previous.currentIndex == index &&
            previous.currentTrack?.id == track.id
        val currentTrackEnded = sameCurrentTrack &&
            previous.durationMs > 0L &&
            previous.positionMs >= previous.durationMs

        // Same track with a still-relative `/library/parts/...` path must re-enter origin
        // resolve. resume()/reloadOnResume would hand JavaFX or ExoPlayer a host-less URI.
        // Also require that this generation already prepared the platform — cold origin wait
        // buffers with nothing open yet, and sticky-binding alone must not resume into silence.
        val canResumeSameTrack = sameCurrentTrack &&
            !currentTrackEnded &&
            track != null &&
            !trackNeedsLiveOriginBind(track) &&
            preparedPlayGeneration == playGeneration
        if (canResumeSameTrack) {
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
            queue = playbackQueue,
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
            beginPlaybackAfterOriginResolve(
                queue = playbackQueue,
                startIndex = index,
                track = track,
                generation = generation,
                sameQueue = sameQueue,
            )
        }
    }

    private fun beginPlaybackAfterOriginResolve(
        queue: List<Track>,
        startIndex: Int,
        track: Track,
        generation: Int,
        sameQueue: Boolean,
    ) {
        // Local files and non-HTTP sources never need a Plex/media-server origin race.
        if (!track.localUri.isNullOrBlank() ||
            !isMusicServerStreamUrl(
                StreamingPlaybackPolicyHolder.resolvePlaybackUri(track).ifBlank { track.streamUrl },
            )
        ) {
            launchPreparedPlayback(
                queue = queue,
                startIndex = startIndex,
                generation = generation,
                sameQueue = sameQueue,
                origin = null,
            )
            return
        }
        val resolver = PlaybackOriginResolverHolder.resolver
        // Prefer a probed/known-good origin and start immediately.
        // With no warm origin, wait for the ranked race so we do not open a dead relay.
        clearStickyIfDemoted()
        val knownOrigin = acceptedPlaybackOrigin(resolver?.cachedOrigin())
            ?: acceptedPlaybackOrigin(stickyPlaybackOrigin)
        if (knownOrigin != null || resolver == null) {
            launchPreparedPlayback(
                queue = if (knownOrigin != null) queue.withResolvedOrigin(knownOrigin) else queue,
                startIndex = startIndex,
                generation = generation,
                sameQueue = sameQueue,
                origin = knownOrigin,
            )
            if (resolver != null) followOriginResolutionInBackground(generation, resolver)
            return
        }
        val primaryUri = StreamingPlaybackPolicyHolder.resolvePlaybackUri(track)
            .ifBlank { track.streamUrl }
        val mustWaitForLiveOrigin = track.holdsRelativePlexPath() ||
            (resolver.demoteLocalOrigins() && isLocalOnlyPlaybackOrigin(primaryUri))
        // Remote absolute URLs (cellular/off-LAN stamped relays) can start immediately.
        // Relative paths and demoted LAN-only primaries must wait for a probed origin.
        if (!mustWaitForLiveOrigin) {
            launchPreparedPlayback(
                queue = queue,
                startIndex = startIndex,
                generation = generation,
                sameQueue = sameQueue,
                origin = null,
            )
            followOriginResolutionInBackground(generation, resolver)
            return
        }
        scope.launch {
            val resolved = resolveColdOriginWithRetries(resolver, generation)
            if (!isPlayRequestCurrent(generation) || !playWhenReady) return@launch
            val accepted = acceptedPlaybackOrigin(resolved)
            if (accepted == null && track.holdsRelativePlexPath()) {
                failServerUnreachable(track, generation)
                return@launch
            }
            // Demoted LAN probe miss / rejected LAN: still open stamped fallbacks if any.
            val playbackQueue = if (accepted != null) queue.withResolvedOrigin(accepted) else queue
            launchPreparedPlayback(
                queue = playbackQueue,
                startIndex = startIndex,
                generation = generation,
                sameQueue = sameQueue,
                origin = accepted,
            )
        }
    }

    /**
     * A cold play tap with no cached origin joins whatever identity race is already in flight
     * (`PlexConnectionResolver` coalesces concurrent callers into one race) rather than getting
     * its own full [PlaybackOriginResolver.DefaultPlayResolveDeadlineMs] budget. That race can
     * miss by a hair — observed on-device: a race missed all candidates, and the very next race,
     * ~1s later, won — so one miss must not be a permanent failure while the server is this close
     * to answering.
     *
     * Keep racing (and periodically rediscovering connection lists) while [playWhenReady] so a
     * flaky plex.direct TLS path eventually binds instead of stranding the user on "can't play".
     */
    private suspend fun resolveColdOriginWithRetries(
        resolver: PlaybackOriginResolver,
        generation: Int,
    ): String? {
        var attempt = 0
        while (isPlayRequestCurrent(generation) && playWhenReady && attempt < ColdOriginResolveMaxAttempts) {
            attempt++
            val resolved = runCatching {
                resolver.resolveOrigin(PlaybackOriginResolver.DefaultPlayResolveDeadlineMs)
            }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            if (resolved != null) return resolved
            if (!isPlayRequestCurrent(generation) || !playWhenReady) return null
            if (attempt % ColdOriginRediscoverEveryAttempts == 0) {
                runCatching { resolver.rediscoverOrigins() }
                    .onFailure { if (it is CancellationException) throw it }
            }
            val delayMs = if (attempt < ColdOriginResolveAttempts) {
                ColdOriginResolveRetryDelayMs
            } else {
                ColdOriginResolveSustainedDelayMs
            }
            delay(delayMs)
        }
        return null
    }

    /**
     * The warm origin can be a dead relay from the last launch, so confirm it in the background
     * while audio is already starting.
     *
     * Resolving is all that happens here. When the race picks a different hop it publishes the
     * new base, which arrives back through [rebasePlaybackOrigins] and re-opens the current
     * stream — one mechanism for "the origin moved", shared with network handoffs.
     */
    private fun followOriginResolutionInBackground(
        generation: Int,
        resolver: PlaybackOriginResolver,
    ) {
        scope.launch {
            val resolved = runCatching {
                resolver.resolveOrigin(PlaybackOriginResolver.DefaultPlayResolveDeadlineMs)
            }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return@launch
            if (!isPlayRequestCurrent(generation) || !playWhenReady) return@launch
            acceptedPlaybackOrigin(resolved)?.let(::rememberStickyPlaybackOrigin)
        }
    }

    private fun launchPreparedPlayback(
        queue: List<Track>,
        startIndex: Int,
        generation: Int,
        sameQueue: Boolean,
        origin: String?,
    ) {
        if (!isPlayRequestCurrent(generation) || !playWhenReady) return
        val index = startIndex.coerceIn(queue.indices)
        val track = queue.getOrNull(index) ?: return
        if (origin != null) {
            rememberStickyPlaybackOrigin(origin)
        }
        val current = mutableState.value
        if (current.queue !== queue || current.currentIndex != index) {
            mutableState.value = current.copy(
                queue = queue,
                currentIndex = index,
                isBuffering = true,
                isPlaying = false,
                durationMs = track.durationMs,
            )
        }
        val initialUri = resolvedInitialPlaybackUriOrNull(track)
        if (initialUri == null) {
            failNoPlayableSource(track, generation)
            return
        }
        notePlaybackUri(initialUri, generation)
        openPlaybackOrigin = playbackOriginOf(initialUri)
        preparedPlayGeneration = generation
        startPlaybackStartupWatchdog(generation)
        if (sameQueue) {
            skipToInQueueOnPlatform(queue, index, track, generation)
        } else {
            playQueueOnPlatform(queue, index, track, generation)
        }
    }

    /**
     * The URI [launchPreparedPlayback]/[prepare] would hand the platform player, or null when
     * the track has neither a local file nor a bindable stream URL.
     *
     * Handing the platform player an empty URI does not fail cleanly: on Android, ExoPlayer
     * resolves a schemeless/empty URI to a local file path and crashes with a bare
     * "open failed: ENOENT" instead of a reportable player error. Checking here lets that one
     * track fail with a normal [PlaybackFailure] instead.
     *
     * A still-relative Plex path (`/library/parts/...`) is the same trap: it is non-blank, so
     * it survives the emptiness check, but no origin has been bound onto it yet. Every caller
     * must have resolved an origin first, so treat an unbound path as "nothing to play".
     */
    private fun resolvedInitialPlaybackUriOrNull(track: Track): String? =
        StreamingPlaybackPolicyHolder.resolvePlaybackUri(track)
            .ifBlank { track.playbackUriCandidates().firstOrNull().orEmpty() }
            .takeIf { it.isNotBlank() && !isUnboundServerPath(it) }

    private fun failNoPlayableSource(track: Track, generation: Int) {
        // An unbound server path means "no live origin", not "this file is missing". NotFound
        // walks the queue, which would march through every song while the server is down.
        if (track.localUri.isNullOrBlank() && track.holdsRelativePlexPath()) {
            failServerUnreachable(track, generation)
            return
        }
        PhoebeLog.d("AudioPlayer") { "no playable uri for track=${track.id}; skipping" }
        publishPlaybackFailure(
            PlaybackFailure(
                kind = PlaybackFailureKind.NotFound,
                message = "no playable source for track ${track.id}",
            ),
            generation,
        )
    }

    private fun failServerUnreachable(track: Track, generation: Int) {
        PhoebeLog.d("AudioPlayer") { "no live origin for track=${track.id}; server unreachable" }
        publishPlaybackFailure(
            PlaybackFailure(
                kind = PlaybackFailureKind.Unreachable,
                message = "no live music-server origin for track ${track.id}",
            ),
            generation,
        )
    }

    private fun List<Track>.withResolvedOrigin(origin: String): List<Track> {
        var changed = false
        val next = map { track ->
            val preferred = track.boundForPlaybackOrigin(origin)
            if (preferred !== track) changed = true
            preferred
        }
        return if (changed) next else this
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
            if (resolvedInitialPlaybackUriOrNull(track) == null) {
                failNoPlayableSource(track, generation)
            } else {
                playQueueOnPlatform(queue, index, track, generation)
                if (boundedPositionMs > 0L) {
                    seek(boundedPositionMs)
                }
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
        if (state.isPlaying) {
            playWhenReady = false
            mutableState.value = state.copy(isPlaying = false)
            pause()
            cancelGaplessPrepare()
            stopProgressTicker()
            return
        }
        val track = state.currentTrack
        // After an unreachable cold start the queue still holds relative Plex paths. Resume
        // (and desktop reloadOnResume → playTrack) would open those unbound; re-play instead.
        if (track != null &&
            trackNeedsLiveOriginBind(track) &&
            state.currentIndex in state.queue.indices
        ) {
            play(state.queue, state.currentIndex)
            return
        }
        playWhenReady = true
        mutableState.value = state.copy(isPlaying = true)
        resume()
        startProgressTicker()
    }

    /** True when this track still needs a live media-server origin before the platform player. */
    private fun trackNeedsLiveOriginBind(track: Track): Boolean {
        if (!track.localUri.isNullOrBlank()) return false
        if (track.holdsRelativePlexPath()) return true
        val resolved = StreamingPlaybackPolicyHolder.resolvePlaybackUri(track)
            .ifBlank { track.streamUrl }
        return isUnboundServerPath(resolved)
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
        stickyPlaybackOrigin = null
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
        val preferred = listOf(track).preferStickyPlaybackOrigin().first()
        val deduped = state.queue.filterNot { it.id == preferred.id }
        val insertAt = (state.currentIndex + 1).coerceIn(0, deduped.size)
        val newQueue = deduped.toMutableList().also { it.add(insertAt, preferred) }
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
        val additions = tracks.filter { existingIds.add(it.id) }.preferStickyPlaybackOrigin()
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
        if (state.queue.isEmpty() || state.currentIndex < 0) return
        val previousIndex = (state.currentIndex - 1).coerceAtLeast(0)
        play(state.queue, previousIndex)
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
        val readyUri = current.currentTrack?.let { track ->
            StreamingPlaybackPolicyHolder.resolvePlaybackUri(track).ifBlank { track.streamUrl }
        }
        rememberStickyPlaybackOrigin(readyUri)
        val playbackQueue = current.queue.preferStickyPlaybackOrigin()
        val effectivePlaying = isPlaying && playWhenReady
        if (playbackQueue !== current.queue) {
            PhoebeLog.d("AudioPlayer") {
                "playback origin sticky=$stickyPlaybackOrigin rebasedQueue=${playbackQueue.size}"
            }
        }
        mutableState.value = current.copy(
            queue = playbackQueue,
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
        forgetFailedPlaybackOrigin(failedUri)
        val next = nextPlaybackFailoverUri(generation, failedUri)
            ?: return resolveNewOriginAndRetry(generation, failedUri)
        val trackId = mutableState.value.currentTrack?.id
        if (!failedUri.isNullOrBlank() && failedUri.isPlexUniversalTranscodeUrl() && !trackId.isNullOrBlank()) {
            StreamingPlaybackPolicyHolder.preferDirectStreamFor(trackId)
        }
        notePlaybackUri(next, generation)
        return startFailoverAttempt(generation, next)
    }

    /**
     * Plex queues hold a relative part key, not a list of addresses, so there is no next URL to
     * walk — there is a next *origin*. The dead one has just been forgotten, so re-running the
     * ranked race picks a different hop, and re-reading the track's URI binds onto it.
     *
     * Returns true when a retry is in flight, so the caller defers surfacing an error.
     */
    private fun resolveNewOriginAndRetry(generation: Int, failedUri: String?): Boolean {
        val resolver = PlaybackOriginResolverHolder.resolver ?: return false
        val track = mutableState.value.currentTrack ?: return false
        if (!track.localUri.isNullOrBlank()) return false
        if (originRetryGeneration == generation) {
            return rediscoverOriginsAndRetry(generation, failedUri)
        }
        originRetryGeneration = generation
        scope.launch {
            val origin = runCatching {
                resolver.resolveOrigin(PlaybackOriginResolver.DefaultPlayResolveDeadlineMs)
            }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
                ?.trimEnd('/')
                ?.takeIf { it.isNotBlank() }
            if (!isPlayRequestCurrent(generation) || !playWhenReady) return@launch
            val current = mutableState.value.currentTrack ?: return@launch
            val next = origin
                ?.let { StreamingPlaybackPolicyHolder.resolvePlaybackUri(current.boundToLivePlaybackOrigin(it)) }
                ?.takeIf { it.isNotBlank() && it !in triedPlaybackUris }
            if (next == null) {
                if (!rediscoverOriginsAndRetry(generation, failedUri)) {
                    publishPlaybackFailure(
                        PlaybackFailure(
                            kind = PlaybackFailureKind.Unreachable,
                            message = "no reachable address for this server",
                            streamUri = failedUri,
                        ),
                        generation,
                    )
                }
                return@launch
            }
            PhoebeLog.d("AudioPlayer") { "playback retry on freshly resolved origin=$origin" }
            notePlaybackUri(next, generation)
            startFailoverAttempt(generation, next)
        }
        return true
    }

    private fun startFailoverAttempt(generation: Int, uri: String): Boolean {
        openPlaybackOrigin = playbackOriginOf(uri)
        adoptFailoverStreamUrl(uri)
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
            "playback failover uri=${PlaybackFailureClassifier.redactStreamUri(uri)} positionMs=$resumePositionMs"
        }
        startPlaybackStartupWatchdog(generation)
        playQueueOnPlatform(
            queue = mutableState.value.queue,
            startIndex = index,
            track = track.copy(streamUrl = uri),
            generation = generation,
            startPositionMs = resumePositionMs,
        )
        return true
    }

    private fun forgetFailedPlaybackOrigin(failedUri: String?) {
        val origin = failedUri?.let(::playbackOriginOf)?.takeIf { it.isNotBlank() } ?: return
        if (stickyPlaybackOrigin?.let { playbackOriginOf(it) }?.equals(origin, ignoreCase = true) == true) {
            stickyPlaybackOrigin = null
        }
        PlaybackOriginResolverHolder.resolver?.forgetOrigin(origin)
    }

    /**
     * Every URL we knew about failed. Ask the provider for the server's current addresses before
     * giving up: a server that changed address mid-session is unreachable on every stamped URL,
     * and refetching that list is the only reason quitting and relaunching the app recovered.
     *
     * Returns true when a refresh is in flight, so the caller defers surfacing an error — this
     * publishes the failure itself if nothing new turns up. Capped at one attempt per play
     * request so a genuinely offline server cannot spin.
     */
    private fun rediscoverOriginsAndRetry(generation: Int, failedUri: String?): Boolean {
        val resolver = PlaybackOriginResolverHolder.resolver ?: return false
        if (originRediscoveryGeneration == generation) return false
        val track = mutableState.value.currentTrack ?: return false
        if (!track.localUri.isNullOrBlank()) return false
        originRediscoveryGeneration = generation
        val alreadyTried = triedPlaybackUris.toSet()
        scope.launch {
            val origins = runCatching { resolver.rediscoverOrigins() }
                // Swallowing cancellation here would let the rest of this block publish a failure
                // and mutate player state after the scope that owns it is already gone.
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
                .orEmpty()
            if (!isPlayRequestCurrent(generation) || !playWhenReady) return@launch
            // An empty list means the addresses did not move, so the URLs already walked are still
            // the only ones that exist. Retrying would re-time-out on every one of them, and the
            // attempt budget may have cut the walk short while dead candidates remained.
            val target = if (origins.isEmpty()) {
                null
            } else if (track.holdsRelativePlexPath()) {
                // Plex: the queue holds a relative part key and must keep holding one. Stamping
                // the rediscovered addresses in would put the queue back to going stale on the
                // next network change. Bind the best new origin for this one attempt instead.
                origins.firstOrNull()
                    ?.let { origin ->
                        StreamingPlaybackPolicyHolder
                            .resolvePlaybackUri(track.boundToLivePlaybackOrigin(origin))
                    }
                    ?.takeIf { it.isNotBlank() && it !in alreadyTried }
            } else {
                restampQueueWithOrigins(origins)
                nextPlaybackFailoverCandidate(
                    candidates = mutableState.value.currentTrack?.playbackUriCandidates().orEmpty(),
                    tried = alreadyTried,
                    failedUri = failedUri,
                    // The addresses are new, so the exhausted budget no longer applies. Going
                    // through the shared candidate picker keeps the LAN-only skip after a remote
                    // failure, which a plain "first untried" scan would lose.
                    maxTriedUris = alreadyTried.size + MaxTriedPlaybackUris,
                )
            }
            if (target == null) {
                PhoebeLog.d("AudioPlayer") { "origin rediscovery surfaced no untried stream URL" }
                publishPlaybackFailure(
                    PlaybackFailure(
                        kind = PlaybackFailureKind.Unreachable,
                        message = "every known stream URL for this server failed",
                        streamUri = failedUri,
                    ),
                    generation,
                )
                return@launch
            }
            PhoebeLog.d("AudioPlayer") {
                "origin rediscovery found ${origins.size} origin(s); retrying playback"
            }
            resetPlaybackUriFailover(generation)
            notePlaybackUri(target, generation)
            startFailoverAttempt(generation, target)
        }
        return true
    }

    private fun restampQueueWithOrigins(origins: List<String>) {
        val preferred = origins.firstOrNull() ?: return
        val current = mutableState.value
        var changed = false
        val restamped = current.queue.map { track ->
            val next = track.withPlaybackOrigins(preferred, origins.drop(1))
            if (next !== track) changed = true
            next
        }
        if (changed) mutableState.value = current.copy(queue = restamped)
    }

    private fun adoptFailoverStreamUrl(uri: String) {
        val current = mutableState.value
        val index = current.currentIndex
        val track = current.queue.getOrNull(index) ?: return
        val nextTrack = track.preferPlaybackUri(uri)
        if (nextTrack === track) return
        mutableState.value = current.copy(
            queue = current.queue.mapIndexed { itemIndex, item ->
                if (itemIndex == index) nextTrack else item
            },
        )
    }

    private fun rememberStickyPlaybackOrigin(uri: String?) {
        val origin = uri?.let(::playbackOriginOf)?.takeIf { it.isNotBlank() } ?: return
        stickyPlaybackOrigin = origin
        clearStickyIfDemoted()
    }

    private fun acceptedPlaybackOrigin(origin: String?): String? {
        val trimmed = origin?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val demote = PlaybackOriginResolverHolder.resolver?.demoteLocalOrigins() == true
        if (demote && isLocalOnlyPlaybackOrigin(trimmed)) return null
        return trimmed
    }

    private fun clearStickyIfDemoted() {
        val sticky = stickyPlaybackOrigin ?: return
        if (PlaybackOriginResolverHolder.resolver?.demoteLocalOrigins() == true &&
            isLocalOnlyPlaybackOrigin(sticky)
        ) {
            stickyPlaybackOrigin = null
        }
    }

    private fun List<Track>.preferStickyPlaybackOrigin(): List<Track> {
        clearStickyIfDemoted()
        val origin = stickyPlaybackOrigin ?: return this
        var changed = false
        val next = map { track ->
            val preferred = track.boundForPlaybackOrigin(origin)
            if (preferred !== track) changed = true
            preferred
        }
        return if (changed) next else this
    }

    /** Relative Plex paths need a host; absolute stamped URLs only need reordering. */
    private fun Track.boundForPlaybackOrigin(origin: String): Track =
        if (holdsRelativePlexPath()) boundToLivePlaybackOrigin(origin) else preferPlaybackOrigin(origin)

    /**
     * The live server base changed — most often a Wi-Fi -> cellular handoff.
     *
     * Plex queue entries hold relative part keys, so nothing in the queue needs rewriting; the
     * next read binds onto the new base by itself. What does need attention is the stream that
     * is *already open* after a handoff ([networkChanged]): its socket points at the old address
     * and will sit there until the platform player's own timeout expires, which is 20-30s of
     * silence. Re-prepare it at the current position instead.
     *
     * A new origin on the same network is not that. Plex relay hosts rotate — a fresh connection
     * list or a second `/identity` race adopts a different `*.plex.direct:8443` address most
     * times it runs, and startup runs several — so treating every adoption as a moved server
     * re-opened a perfectly healthy stream once per adoption. At startup the position is still
     * near zero, so each one sounded like the song restarting from the top. If the origin we are
     * playing on really is dead, the stream stalls and the failover path recovers it.
     */
    override fun rebasePlaybackOrigins(origin: String, networkChanged: Boolean) {
        val trimmed = origin.trimEnd('/').takeIf { it.isNotBlank() } ?: return
        rememberStickyPlaybackOrigin(trimmed)
        val current = mutableState.value
        if (current.queue.isEmpty()) return
        // Non-Plex providers still carry stamped absolute URLs; keep rewriting those.
        val playbackQueue = current.queue.preferStickyPlaybackOrigin()
        if (playbackQueue !== current.queue) {
            PhoebeLog.d("AudioPlayer") {
                "rebasePlaybackOrigins origin=$trimmed queue=${playbackQueue.size}"
            }
            mutableState.value = current.copy(queue = playbackQueue)
            onQueueEdited(playbackQueue, current.currentIndex)
        }
        val state = mutableState.value
        // Cold play was waiting for a live base (or already failed unreachable). Origin is here
        // now — start immediately. Skip when a stream is already open; network handoffs use
        // reopenCurrentStreamOnNewOrigin below.
        val shouldStartDeferredPlay = state.currentTrack != null &&
            state.currentIndex in state.queue.indices &&
            openPlaybackOrigin == null &&
            !state.isPlaying &&
            (playWhenReady || state.isBuffering || state.playbackErrorMessage != null)
        if (shouldStartDeferredPlay) {
            PhoebeLog.d("AudioPlayer") {
                "rebasePlaybackOrigins starting deferred play on $trimmed"
            }
            play(state.queue, state.currentIndex)
            return
        }
        if (networkChanged) reopenCurrentStreamOnNewOrigin(trimmed)
    }

    private fun reopenCurrentStreamOnNewOrigin(origin: String) {
        if (!playWhenReady) return
        val state = mutableState.value
        if (!state.isPlaying && !state.isBuffering) return
        val track = state.currentTrack ?: return
        if (!track.localUri.isNullOrBlank()) return
        val openedOn = openPlaybackOrigin ?: return
        val nextOrigin = playbackOriginOf(origin) ?: origin
        if (openedOn.equals(nextOrigin, ignoreCase = true)) return
        val nextUri = StreamingPlaybackPolicyHolder
            .resolvePlaybackUri(track.boundToLivePlaybackOrigin(origin))
            .takeIf { it.isNotBlank() } ?: return
        if (playbackOriginOf(nextUri)?.equals(openedOn, ignoreCase = true) == true) return
        val generation = activePlayGeneration
        PhoebeLog.d("AudioPlayer") {
            "origin moved $openedOn -> $nextOrigin; re-opening current track at ${state.positionMs}ms"
        }
        // The old address is gone for this network, so drop it and start the attempt budget
        // over — this is a new network, not another failure on the old one.
        forgetFailedPlaybackOrigin(openedOn)
        resetPlaybackUriFailover(generation)
        notePlaybackUri(nextUri, generation)
        startFailoverAttempt(generation, nextUri)
    }

    protected open val playbackStartupTimeoutMs: Long
        get() {
            val track = mutableState.value.currentTrack
            // Local files are not network origins — give them the remote budget, not the
            // short LAN fail-fast window (file:// used to be misclassified as local-only).
            if (!track?.localUri.isNullOrBlank()) return PlaybackStartupTimeoutRemoteMs
            val uri = track?.let {
                StreamingPlaybackPolicyHolder.resolvePlaybackUri(it).ifBlank { it.streamUrl }
            }.orEmpty()
            return if (uri.isNotBlank() && isLocalOnlyPlaybackOrigin(uri)) {
                PlaybackStartupTimeoutLocalMs
            } else {
                PlaybackStartupTimeoutRemoteMs
            }
        }

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
