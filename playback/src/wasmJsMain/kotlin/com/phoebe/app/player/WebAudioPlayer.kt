package com.phoebe.app.player

import com.phoebe.app.domain.AudioAnalysisFrame
import com.phoebe.app.domain.AudioAnalysisSource
import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.resolveWebDownloadObjectUrl
import com.phoebe.app.sources.resolveWebLocalAudioUri
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLAudioElement

actual fun createAudioPlayer(): AudioPlayer = WebAudioPlayer()

@OptIn(ExperimentalWasmJsInterop::class)
fun createWebAudioPlayerForTests(diagnostics: PlaybackDiagnostics): AudioPlayer =
    WebAudioPlayer(diagnostics)

@OptIn(ExperimentalWasmJsInterop::class)
private class WebAudioPlayer(
    private val diagnostics: PlaybackDiagnostics = PlaybackDiagnostics.None,
) : SimpleAudioPlayer() {
    override val useProgressTicker: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentUri: String? = null
    private var retryJob: Job? = null
    private var retryGeneration = -1
    private var retryCount = 0
    private var sourcePreparedForWebEqualizer = false
    private var pendingSeekAfterLoadSeconds: Double? = null
    private var pendingPlayAfterLoad = false
    private var audioUsesCors = true
    private var corsFallbackAttempted = false
    private var equalizerUnavailableForCurrentStream = false
    private var equalizerUnavailableNoticeShown = false
    private var prefetchGeneration = -1
    private var prefetchStartedGeneration = -1
    private var prefetchedBufferFraction = 0.0
    private var webCrossfadeGeneration = -1
    private var webCrossfadeJob: Job? = null
    private var webCrossfadeIncoming: HTMLAudioElement? = null
    private var webGaplessAudio: HTMLAudioElement? = null
    private var webGaplessGeneration = -1
    private var webGaplessTrackId: String? = null
    private var webGaplessUri: String? = null
    private var webGaplessUsesCors = true
    private var webGaplessPrerolled = false
    private var webGaplessHotStartRequested = false
    private var webGaplessHotStarted = false
    private var webGaplessHotStartJob: Job? = null

    private var audio = createAudioElement(useCors = true)

    override fun stopCurrentPlaybackImmediately() {
        retryJob?.cancel()
        stopWebCrossfade()
        stopWebGapless()
        stopWebAudioAnalysis(audio)
        cancelWebAudioPrefetch()
        resetWebAudioPrefetch()
        clearPendingReloadRestore()
        audio.pause()
    }

    override fun playTrack(track: Track) {
        val localUri = track.localUri?.takeIf { it.isNotBlank() }
        val streamUri = track.streamUrl.takeIf { it.isNotBlank() }
        playUri(
            uri = localUri ?: streamUri.orEmpty(),
            fallbackUri = streamUri?.takeIf { localUri?.startsWith("web-download://") == true },
        )
    }

    override fun playUri(uri: String) {
        playUri(uri, fallbackUri = null)
    }

    private fun playUri(uri: String, fallbackUri: String?) {
        if (uri.isBlank()) {
            markPlaybackFailed()
            return
        }
        if (uri.startsWith("web-download://")) {
            val generation = activePlayGeneration
            resolveWebDownloadObjectUrl(uri) { resolved ->
                if (generation != activePlayGeneration) return@resolveWebDownloadObjectUrl
                if (resolved.isBlank()) {
                    if (!fallbackUri.isNullOrBlank()) {
                        playResolvedUri(resolveWebLocalAudioUri(fallbackUri))
                    } else {
                        markPlaybackFailed(generation)
                    }
                } else {
                    playResolvedUri(resolved)
                }
            }
            return
        }
        playResolvedUri(resolveWebLocalAudioUri(uri))
    }

    private fun playResolvedUri(playbackUri: String) {
        val generation = activePlayGeneration
        diagnostics.engineSelected(PlaybackEnginePath.WebAudioElement)
        currentUri = playbackUri
        retryGeneration = generation
        retryCount = 0
        stopWebCrossfade()
        stopWebGapless()
        corsFallbackAttempted = false
        equalizerUnavailableForCurrentStream = false
        equalizerUnavailableNoticeShown = false
        retryJob?.cancel()
        cancelWebAudioPrefetch()
        resetWebAudioPrefetch(generation)
        if (!audioUsesCors) {
            val previousAudio = audio
            audio = createAudioElement(useCors = true)
            audioUsesCors = true
            sourcePreparedForWebEqualizer = false
            disposeWebAudioElement(previousAudio)
        }
        audio.volume = effectiveOutputVolume().toDouble().coerceIn(0.0, 1.0)
        prepareAudioElementForCurrentEqualizer()
        setWebAudioCurrentTime(audio, 0.0)
        installAudioEventHandlers(generation)
        audio.src = playbackUri
        audio.load()
        applyCurrentEqualizer()
        startAudioAnalysisForCurrentElement()
        if (playWhenReady) {
            playWebAudio(generation)
        }
    }

    override fun pause() {
        retryJob?.cancel()
        pendingPlayAfterLoad = false
        stopWebCrossfade()
        stopWebGapless()
        audio.pause()
    }

    override fun resume() {
        playWebAudio(activePlayGeneration)
    }

    override fun seek(positionMs: Long) {
        stopWebCrossfade()
        stopWebGapless()
        setWebAudioCurrentTime(audio, positionMs / 1000.0)
    }

    override fun setOutputVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        if (setWebAudioOutputGain(audio, v.toDouble(), create = false)) {
            audio.volume = 1.0
        } else {
            audio.volume = v.toDouble()
        }
        webCrossfadeIncoming?.let { incoming ->
            if (webCrossfadeJob == null) {
                if (setWebAudioOutputGain(incoming, v.toDouble(), create = false)) {
                    incoming.volume = 1.0
                } else {
                    incoming.volume = v.toDouble()
                }
            }
        }
        webGaplessAudio?.let { prepared ->
            prepared.volume = v.toDouble()
        }
    }

    override fun applyEqualizer(profile: EqualizerProfile) {
        val normalized = profile.normalized()
        if (normalized.enabled && !sourcePreparedForWebEqualizer) {
            prepareAudioElementForCurrentEqualizer()
        }
        applyCurrentEqualizer()
        startAudioAnalysisForCurrentElement()
    }

    override fun startGaplessPrepareOnPlatform(
        queue: List<Track>,
        targetIndex: Int,
        track: Track,
        generation: Int,
    ): Boolean {
        if (targetIndex !in queue.indices || audio.paused) return false
        val rawUri = track.localUri?.takeIf { it.isNotBlank() } ?: track.streamUrl.takeIf { it.isNotBlank() } ?: return false
        if (rawUri.startsWith("web-download://")) return false
        val playbackUri = resolveWebLocalAudioUri(rawUri)
        stopWebGapless()
        val prepared = createAudioElement(useCors = audioUsesCors)
        webGaplessAudio = prepared
        webGaplessGeneration = generation
        webGaplessTrackId = track.id
        webGaplessUri = playbackUri
        webGaplessUsesCors = audioUsesCors
        resetWebGaplessHotStartState()
        prepared.muted = true
        prepared.volume = 0.0
        prepared.onplaying = {
            if (webGaplessAudio === prepared && webGaplessGeneration == generation) {
                when {
                    webGaplessHotStartRequested -> {
                        webGaplessHotStarted = true
                    }
                    !webGaplessPrerolled -> {
                        prepared.pause()
                        setWebAudioCurrentTime(prepared, 0.0)
                        prepared.muted = false
                        prepared.volume = effectiveOutputVolume().toDouble().coerceIn(0.0, 1.0)
                        webGaplessPrerolled = true
                    }
                }
            }
        }
        prepared.onerror = { _, _, _, _, _ ->
            if (webGaplessAudio === prepared) stopWebGapless()
            null
        }
        prepared.src = playbackUri
        prepared.load()
        playWebAudio(prepared) { message ->
            if (webGaplessAudio !== prepared || webGaplessGeneration != generation) return@playWebAudio
            if (!message.isBrowserAutoplayBlockedFailure()) {
                stopWebGapless()
                return@playWebAudio
            }
            prepared.muted = false
            prepared.volume = effectiveOutputVolume().toDouble().coerceIn(0.0, 1.0)
        }
        webGaplessHotStartJob?.cancel()
        webGaplessHotStartJob = scope.launch {
            while (webGaplessAudio === prepared && webGaplessGeneration == generation) {
                val durationMs = webAudioPlaybackDurationMs(
                    currentDurationMs = state.value.durationMs,
                    browserDurationSeconds = audio.duration,
                )
                val positionMs = (audio.currentTime * 1000.0).toLong().coerceAtLeast(0L)
                maybeHotStartWebGapless(generation, positionMs, durationMs)
                maybeCommitWebGaplessBoundary(generation, positionMs, durationMs)
                if (webGaplessAudio !== prepared) break
                delay(WebGaplessHotStartPollMs)
            }
        }
        return true
    }

    override fun commitGaplessPreparedOnPlatform(
        queue: List<Track>,
        targetIndex: Int,
        track: Track,
        generation: Int,
    ): Boolean {
        val incoming = webGaplessAudio ?: return false
        val playbackUri = webGaplessUri ?: return false
        if (webGaplessGeneration != generation || webGaplessTrackId != track.id) return false
        val outgoing = audio
        stopWebAudioAnalysis(outgoing)
        audio = incoming
        audioUsesCors = webGaplessUsesCors
        currentUri = playbackUri
        retryGeneration = generation
        retryCount = 0
        corsFallbackAttempted = !webGaplessUsesCors
        equalizerUnavailableForCurrentStream = !webGaplessUsesCors && playbackUri.isRemoteWebAudioUri()
        equalizerUnavailableNoticeShown = false
        sourcePreparedForWebEqualizer = false
        val hotStarted = webGaplessHotStarted && !incoming.paused && !isWebAudioEnded(incoming)
        webGaplessHotStartJob?.cancel()
        webGaplessHotStartJob = null
        webGaplessAudio = null
        webGaplessGeneration = -1
        webGaplessTrackId = null
        webGaplessUri = null
        incoming.muted = false
        incoming.volume = effectiveOutputVolume().toDouble().coerceIn(0.0, 1.0)
        if (!hotStarted) {
            setWebAudioCurrentTime(incoming, 0.0)
        }
        resetWebGaplessHotStartState()
        installAudioEventHandlers(generation)
        prepareAudioElementForCurrentEqualizer()
        applyCurrentEqualizer()
        setOutputVolume(effectiveOutputVolume())
        startAudioAnalysisForCurrentElement()
        disposeWebAudioElement(outgoing)
        if (playWhenReady && !hotStarted) {
            playWebAudio(generation)
        }
        syncFromAudio(generation, isBuffering = false)
        return true
    }

    override fun cancelGaplessPrepareOnPlatform(generation: Int) {
        if (webGaplessGeneration == generation) {
            stopWebGapless()
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
        if (webCrossfadeGeneration == generation) return true
        if (targetIndex !in queue.indices || audio.paused) return false
        val outgoing = audio
        webCrossfadeGeneration = generation
        val accepted = resolveWebCrossfadeUri(track, generation) { playbackUri ->
            startWebCrossfade(
                outgoing = outgoing,
                playbackUri = playbackUri,
                queue = queue,
                targetIndex = targetIndex,
                track = track,
                durationMs = durationMs,
                baseVolume = baseVolume,
                generation = generation,
                useCors = audioUsesCors,
                allowCorsFallback = true,
            )
        }
        if (!accepted) {
            webCrossfadeGeneration = -1
            return false
        }
        return true
    }

    private fun resolveWebCrossfadeUri(
        track: Track,
        generation: Int,
        onResolved: (String) -> Unit,
    ): Boolean {
        val localUri = track.localUri?.takeIf { it.isNotBlank() }
        val streamUri = track.streamUrl.takeIf { it.isNotBlank() }
        val uri = localUri ?: streamUri.orEmpty()
        if (uri.isBlank()) return false
        if (uri.startsWith("web-download://")) {
            resolveWebDownloadObjectUrl(uri) { resolved ->
                if (!isPlayRequestCurrent(generation) || webCrossfadeGeneration != generation) return@resolveWebDownloadObjectUrl
                val playbackUri = if (resolved.isBlank()) {
                    streamUri
                        ?.takeIf { localUri?.startsWith("web-download://") == true }
                        ?.let { resolveWebLocalAudioUri(it) }
                } else {
                    resolved
                }
                if (playbackUri.isNullOrBlank()) {
                    webCrossfadeGeneration = -1
                    return@resolveWebDownloadObjectUrl
                }
                onResolved(playbackUri)
            }
            return true
        }
        onResolved(resolveWebLocalAudioUri(uri))
        return true
    }

    private fun startWebCrossfade(
        outgoing: HTMLAudioElement,
        playbackUri: String,
        queue: List<Track>,
        targetIndex: Int,
        track: Track,
        durationMs: Long,
        baseVolume: Float,
        generation: Int,
        useCors: Boolean,
        allowCorsFallback: Boolean,
    ) {
        if (!isWebCrossfadeCurrent(generation, outgoing)) return
        val incoming = createAudioElement(useCors = useCors)
        webCrossfadeIncoming?.let { previous ->
            if (previous !== incoming) disposeWebAudioElement(previous)
        }
        webCrossfadeIncoming = incoming
        val outgoingOnEnded = outgoing.onended

        fun fail(message: String?) {
            if (!isPlayRequestCurrent(generation) || webCrossfadeIncoming !== incoming) return
            if (allowCorsFallback && useCors && playbackUri.isRemoteWebAudioUri()) {
                disposeWebAudioElement(incoming)
                webCrossfadeIncoming = null
                startWebCrossfade(
                    outgoing = outgoing,
                    playbackUri = playbackUri,
                    queue = queue,
                    targetIndex = targetIndex,
                    track = track,
                    durationMs = durationMs,
                    baseVolume = baseVolume,
                    generation = generation,
                    useCors = false,
                    allowCorsFallback = false,
                )
                return
            }
            if (audio === outgoing) {
                outgoing.onended = outgoingOnEnded
            }
            diagnostics.playbackError(PlaybackEnginePath.WebAudioElement, message)
            failWebCrossfade(incoming, generation)
        }

        var started = false
        val incomingCanUseWebAudioGain = useCors || !playbackUri.isRemoteWebAudioUri()
        val incomingUsesWebAudioGain = incomingCanUseWebAudioGain &&
            setWebAudioOutputGain(incoming, 0.0, create = true)
        if (incomingUsesWebAudioGain) {
            incoming.volume = 1.0
        } else {
            incoming.volume = 0.0
        }
        incoming.onplaying = {
            if (!started && isWebCrossfadeCurrent(generation, outgoing, incoming)) {
                started = true
                outgoing.onended = {}
                val outgoingCanUseWebAudioGain = audioUsesCors || currentUri?.isRemoteWebAudioUri() != true
                val outgoingUsesWebAudioGain = outgoingCanUseWebAudioGain &&
                    setWebAudioOutputGain(outgoing, baseVolume.toDouble(), create = true)
                if (outgoingUsesWebAudioGain) {
                    outgoing.volume = 1.0
                }
                diagnostics.crossfadeStarted(
                    engine = PlaybackEnginePath.WebAudioElement,
                    outgoingTrackId = state.value.currentTrack?.id,
                    incomingTrackId = track.id,
                    durationMs = durationMs,
                )
                runWebCrossfade(
                    outgoing = outgoing,
                    incoming = incoming,
                    playbackUri = playbackUri,
                    queue = queue,
                    targetIndex = targetIndex,
                    durationMs = durationMs,
                    baseVolume = baseVolume,
                    generation = generation,
                    useCors = useCors,
                    outgoingUsesWebAudioGain = outgoingUsesWebAudioGain,
                    incomingUsesWebAudioGain = incomingUsesWebAudioGain,
                )
            }
        }
        incoming.onerror = { _, _, _, _, _ ->
            fail(webAudioErrorMessage(incoming))
            null
        }
        incoming.src = playbackUri
        incoming.load()
        playWebAudio(incoming) { message -> fail(message) }
    }

    private fun runWebCrossfade(
        outgoing: HTMLAudioElement,
        incoming: HTMLAudioElement,
        playbackUri: String,
        queue: List<Track>,
        targetIndex: Int,
        durationMs: Long,
        baseVolume: Float,
        generation: Int,
        useCors: Boolean,
        outgoingUsesWebAudioGain: Boolean,
        incomingUsesWebAudioGain: Boolean,
    ) {
        webCrossfadeJob?.cancel()
        webCrossfadeJob = scope.launch {
            try {
                val fadeDurationMs = webCrossfadeFadeDurationMs(outgoing, durationMs)
                val stepDelay = (fadeDurationMs / WebCrossfadeSteps).coerceAtLeast(16L)
                repeat(WebCrossfadeSteps) { index ->
                    if (!isWebCrossfadeCurrent(generation, outgoing, incoming)) return@launch
                    val progress = (index + 1).toFloat() / WebCrossfadeSteps.toFloat()
                    val outgoingVolume = (baseVolume * (1f - progress)).coerceIn(0f, 1f)
                    val incomingVolume = (baseVolume * progress).coerceIn(0f, 1f)
                    diagnostics.crossfadeVolume(
                        engine = PlaybackEnginePath.WebAudioElement,
                        step = index + 1,
                        outgoingVolume = outgoingVolume,
                        incomingVolume = incomingVolume,
                    )
                    if (outgoingUsesWebAudioGain) {
                        setWebAudioOutputGain(outgoing, outgoingVolume.toDouble(), create = false)
                    } else {
                        outgoing.volume = outgoingVolume.toDouble()
                    }
                    if (incomingUsesWebAudioGain) {
                        setWebAudioOutputGain(incoming, incomingVolume.toDouble(), create = false)
                    } else {
                        incoming.volume = incomingVolume.toDouble()
                    }
                    delay(stepDelay)
                }
                commitWebCrossfade(
                    outgoing = outgoing,
                    incoming = incoming,
                    playbackUri = playbackUri,
                    queue = queue,
                    targetIndex = targetIndex,
                    generation = generation,
                    useCors = useCors,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                diagnostics.playbackError(PlaybackEnginePath.WebAudioElement, error.message)
                failWebCrossfade(incoming, generation)
            }
        }
    }

    private fun commitWebCrossfade(
        outgoing: HTMLAudioElement,
        incoming: HTMLAudioElement,
        playbackUri: String,
        queue: List<Track>,
        targetIndex: Int,
        generation: Int,
        useCors: Boolean,
    ) {
        if (!isWebCrossfadeCurrent(generation, outgoing, incoming)) return
        val positionMs = (incoming.currentTime * 1000.0).toLong().coerceAtLeast(0L)
        disposeWebAudioElement(outgoing)
        audio = incoming
        audioUsesCors = useCors
        currentUri = playbackUri
        retryGeneration = generation
        retryCount = 0
        corsFallbackAttempted = !useCors
        equalizerUnavailableForCurrentStream = !useCors && playbackUri.isRemoteWebAudioUri()
        equalizerUnavailableNoticeShown = false
        sourcePreparedForWebEqualizer = false
        webCrossfadeIncoming = null
        webCrossfadeGeneration = -1
        webCrossfadeJob = null
        cancelWebAudioPrefetch()
        resetWebAudioPrefetch(generation)
        installAudioEventHandlers(generation)
        prepareAudioElementForCurrentEqualizer()
        applyCurrentEqualizer()
        setOutputVolume(effectiveOutputVolume())
        adoptCrossfadeTarget(
            queue = queue,
            targetIndex = targetIndex,
            positionMs = positionMs,
            generation = generation,
        )
        diagnostics.crossfadeCommitted(
            engine = PlaybackEnginePath.WebAudioElement,
            incomingTrackId = queue[targetIndex].id,
        )
        syncFromAudio(generation, isBuffering = false)
        startWebAudioPrefetchIfNeeded(currentUri, generation)
    }

    private fun failWebCrossfade(incoming: HTMLAudioElement, generation: Int) {
        if (webCrossfadeIncoming !== incoming || webCrossfadeGeneration != generation) return
        webCrossfadeJob?.cancel()
        webCrossfadeJob = null
        disposeWebAudioElement(incoming)
        webCrossfadeIncoming = null
        webCrossfadeGeneration = -1
        setOutputVolume(effectiveOutputVolume())
    }

    private fun stopWebCrossfade() {
        webCrossfadeJob?.cancel()
        webCrossfadeJob = null
        webCrossfadeIncoming?.let { disposeWebAudioElement(it) }
        webCrossfadeIncoming = null
        webCrossfadeGeneration = -1
        setOutputVolume(effectiveOutputVolume())
    }

    private fun stopWebGapless() {
        webGaplessHotStartJob?.cancel()
        webGaplessHotStartJob = null
        webGaplessAudio?.let { disposeWebAudioElement(it) }
        webGaplessAudio = null
        webGaplessGeneration = -1
        webGaplessTrackId = null
        webGaplessUri = null
        webGaplessUsesCors = true
        resetWebGaplessHotStartState()
    }

    private fun resetWebGaplessHotStartState() {
        webGaplessPrerolled = false
        webGaplessHotStartRequested = false
        webGaplessHotStarted = false
    }

    private fun isWebCrossfadeCurrent(
        generation: Int,
        outgoing: HTMLAudioElement,
        incoming: HTMLAudioElement? = webCrossfadeIncoming,
    ): Boolean =
        isPlayRequestCurrent(generation) &&
            webCrossfadeGeneration == generation &&
            audio === outgoing &&
            (incoming == null || webCrossfadeIncoming === incoming) &&
            playWhenReady

    private fun webCrossfadeFadeDurationMs(outgoing: HTMLAudioElement, configuredDurationMs: Long): Long {
        val durationSeconds = outgoing.duration
        val remainingMs = if (durationSeconds.isFinite() && durationSeconds > 0.0) {
            ((durationSeconds - outgoing.currentTime).coerceAtLeast(0.0) * 1000.0).toLong()
        } else {
            configuredDurationMs
        }
        return remainingMs
            .coerceAtMost(configuredDurationMs)
            .coerceAtLeast(WebCrossfadeMinimumFadeMs)
    }

    private fun prepareAudioElementForCurrentEqualizer() {
        val enabled = equalizerProfile.normalized().enabled
        prepareWebEqualizerAudio(audio, enabled && audioUsesCors)
        sourcePreparedForWebEqualizer = (enabled && audioUsesCors) || isWebEqualizerAttached(audio)
    }

    private fun applyCurrentEqualizer() {
        val normalized = equalizerProfile.normalized()
        val effectiveProfile = if (GraphicEqualizerProcessor.isActive(normalized) && !sourcePreparedForWebEqualizer) {
            surfaceEqualizerUnavailableNoticeIfNeeded()
            normalized.copy(enabled = false)
        } else {
            normalized
        }
        applyWebEqualizer(audio, webEqualizerPayload(effectiveProfile))
    }

    private fun startAudioAnalysisForCurrentElement() {
        val currentAudio = audio
        startWebAudioAnalysis(currentAudio) { amplitude, bandsCsv, timestampMs ->
            if (currentAudio !== audio || !isPlayRequestCurrent(activePlayGeneration)) return@startWebAudioAnalysis
            val bands = bandsCsv
                .split(',')
                .mapNotNull { it.toFloatOrNull()?.coerceIn(0f, 1f) }
            publishAudioAnalysis(
                AudioAnalysisFrame(
                    amplitude = amplitude.toFloat().coerceIn(0f, 1f),
                    bands = bands,
                    timestampMs = timestampMs.toLong().coerceAtLeast(0L),
                    source = AudioAnalysisSource.WebAudio,
                ),
            )
        }
    }

    private fun installAudioEventHandlers(generation: Int) {
        val eventAudio = audio

        fun isCurrentAudioEvent(): Boolean =
            audio === eventAudio && isPlayRequestCurrent(generation)

        eventAudio.onloadedmetadata = {
            if (isCurrentAudioEvent()) {
                restorePendingReloadPosition(generation)
                syncFromAudio(generation, isBuffering = eventAudio.paused && playWhenReady)
            }
        }
        eventAudio.onloadeddata = {
            if (isCurrentAudioEvent()) {
                syncFromAudio(generation, isBuffering = eventAudio.paused && playWhenReady)
            }
        }
        eventAudio.ondurationchange = {
            if (isCurrentAudioEvent()) {
                syncFromAudio(generation, isBuffering = eventAudio.paused && playWhenReady)
            }
        }
        eventAudio.onplaying = {
            if (isCurrentAudioEvent()) {
                retryCount = 0
                startWebAudioPrefetchIfNeeded(currentUri, generation)
                syncFromAudio(generation, isBuffering = false)
                diagnostics.platformPlaying(
                    PlaybackEnginePath.WebAudioElement,
                    (eventAudio.currentTime * 1000.0).toLong().coerceAtLeast(0L),
                    webAudioPlaybackDurationMs(
                        currentDurationMs = state.value.durationMs,
                        browserDurationSeconds = eventAudio.duration,
                    ),
                )
                markPlaybackReady(generation = generation)
            }
        }
        eventAudio.onpause = {
            if (isCurrentAudioEvent()) {
                syncFromAudio(generation, isBuffering = false)
            }
        }
        eventAudio.onwaiting = {
            if (isCurrentAudioEvent()) {
                syncFromAudio(generation, isBuffering = true)
            }
        }
        eventAudio.onstalled = {
            if (isCurrentAudioEvent()) {
                syncFromAudio(generation, isBuffering = true)
                scheduleRetry(generation, reload = false)
            }
        }
        eventAudio.oncanplay = {
            if (isCurrentAudioEvent()) {
                restorePendingReloadPosition(generation)
                syncFromAudio(generation, isBuffering = eventAudio.paused && playWhenReady)
            }
        }
        eventAudio.oncanplaythrough = {
            if (isCurrentAudioEvent()) {
                syncFromAudio(generation, isBuffering = eventAudio.paused && playWhenReady)
            }
        }
        installWebAudioProgressHandler(eventAudio) {
            if (isCurrentAudioEvent()) {
                syncFromAudio(generation, isBuffering = eventAudio.paused && playWhenReady)
            }
        }
        eventAudio.onsuspend = {
            if (isCurrentAudioEvent()) {
                syncFromAudio(generation, isBuffering = eventAudio.paused && playWhenReady)
            }
        }
        eventAudio.ontimeupdate = {
            if (isCurrentAudioEvent()) {
                syncFromAudio(generation, isBuffering = false)
            }
        }
        eventAudio.onended = {
            if (isCurrentAudioEvent()) {
                syncEndedPositionFromAudio(generation)
                advanceAfterPlatformTrackEnded(generation)
            }
        }
        eventAudio.onerror = { _, _, _, _, _ ->
            if (isCurrentAudioEvent()) {
                val message = webAudioErrorMessage(eventAudio)
                if (!shouldIgnoreBrowserPlaybackFailure(eventAudio, message)) {
                    clearPendingReloadRestore()
                    if (!retryWithoutCors(generation)) {
                        diagnostics.playbackError(PlaybackEnginePath.WebAudioElement, message)
                        scheduleRetry(generation, reload = true)
                    }
                }
            }
            null
        }
    }

    private fun restorePendingReloadPosition(generation: Int) {
        val positionSeconds = pendingSeekAfterLoadSeconds ?: return
        if (!isPlayRequestCurrent(generation)) {
            clearPendingReloadRestore()
            return
        }
        val boundedPositionSeconds = if (audio.duration.isFinite() && audio.duration > 0.0) {
            positionSeconds.coerceIn(0.0, audio.duration)
        } else {
            positionSeconds.coerceAtLeast(0.0)
        }
        if (!setWebAudioCurrentTime(audio, boundedPositionSeconds)) return
        pendingSeekAfterLoadSeconds = null
        if (pendingPlayAfterLoad && playWhenReady) {
            pendingPlayAfterLoad = false
            playWebAudio(generation)
        } else {
            pendingPlayAfterLoad = false
        }
    }

    private fun clearPendingReloadRestore() {
        pendingSeekAfterLoadSeconds = null
        pendingPlayAfterLoad = false
    }

    private fun retryWithoutCors(generation: Int): Boolean {
        val uri = currentUri ?: return false
        if (!audioUsesCors || corsFallbackAttempted || !isPlayRequestCurrent(generation)) return false
        corsFallbackAttempted = true
        val previousAudio = audio
        val positionSeconds = previousAudio.currentTime.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        audio = createAudioElement(useCors = false)
        audioUsesCors = false
        stopWebGapless()
        cancelWebAudioPrefetch()
        resetWebAudioPrefetch()
        sourcePreparedForWebEqualizer = false
        equalizerUnavailableForCurrentStream = true
        equalizerUnavailableNoticeShown = false
        audio.volume = previousAudio.volume
        installAudioEventHandlers(generation)
        if (positionSeconds > 0.0) {
            pendingSeekAfterLoadSeconds = positionSeconds
            pendingPlayAfterLoad = playWhenReady
        }
        audio.src = uri
        audio.load()
        applyCurrentEqualizer()
        disposeWebAudioElement(previousAudio)
        if (playWhenReady) {
            playWebAudio(generation)
        }
        return true
    }

    private fun surfaceEqualizerUnavailableNoticeIfNeeded(generation: Int = activePlayGeneration) {
        if (!equalizerUnavailableForCurrentStream || equalizerUnavailableNoticeShown) return
        if (!GraphicEqualizerProcessor.isActive(equalizerProfile)) return
        equalizerUnavailableNoticeShown = true
        val title = state.value.currentTrack?.title?.takeIf { it.isNotBlank() } ?: "this song"
        surfacePlaybackNotice(
            generation = generation,
            message = "Equalizer isn't available for $title in the browser because its stream blocks WebAudio access. Playback continues without EQ.",
        )
    }

    private fun syncFromAudio(generation: Int, isBuffering: Boolean) {
        if (!isPlayRequestCurrent(generation)) return
        val durationMs = webAudioPlaybackDurationMs(
            currentDurationMs = state.value.durationMs,
            browserDurationSeconds = audio.duration,
        )
        val positionMs = (audio.currentTime * 1000.0).toLong().coerceAtLeast(0L)
        maybeHotStartWebGapless(generation, positionMs, durationMs)
        maybeCommitWebGaplessBoundary(generation, positionMs, durationMs)
        diagnostics.playbackProgress(PlaybackEnginePath.WebAudioElement, positionMs, durationMs)
        if (!audio.paused && !isBuffering) {
            diagnostics.platformPlaying(PlaybackEnginePath.WebAudioElement, positionMs, durationMs)
        }
        applyPlatformPlayback(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = !audio.paused && !isBuffering,
            isBuffering = isBuffering,
            bufferedPositionMs = bufferedPositionMs(positionMs, durationMs),
            generation = generation,
        )
    }

    private fun maybeHotStartWebGapless(
        generation: Int,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (durationMs <= 0L || positionMs < 0L) return
        val remainingMs = durationMs - positionMs
        if (remainingMs !in 0L..WebGaplessHotStartLeadMs) return
        val prepared = webGaplessAudio ?: return
        if (webGaplessGeneration != generation ||
            webGaplessHotStartRequested ||
            !webGaplessPrerolled ||
            audio.paused
        ) {
            return
        }
        webGaplessHotStartRequested = true
        prepared.muted = true
        prepared.volume = 0.0
        setWebAudioCurrentTime(prepared, 0.0)
        playWebAudio(prepared) { message ->
            if (webGaplessAudio !== prepared || webGaplessGeneration != generation) return@playWebAudio
            webGaplessHotStartRequested = false
            if (!message.isBrowserAutoplayBlockedFailure()) {
                stopWebGapless()
            }
        }
    }

    private fun maybeCommitWebGaplessBoundary(
        generation: Int,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (durationMs <= 0L || positionMs < 0L) return
        val remainingMs = durationMs - positionMs
        if (remainingMs !in 0L..WebGaplessBoundaryCommitLeadMs) return
        if (webGaplessGeneration != generation ||
            webGaplessAudio == null ||
            !webGaplessPrerolled ||
            audio.paused
        ) {
            return
        }
        commitPreparedGapless(generation)
    }

    private fun syncEndedPositionFromAudio(generation: Int) {
        if (!isPlayRequestCurrent(generation)) return
        val durationMs = webAudioPlaybackDurationMs(
            currentDurationMs = state.value.durationMs,
            browserDurationSeconds = audio.duration,
        )
        val currentPositionMs = (audio.currentTime * 1000.0).toLong().coerceAtLeast(0L)
        val endedPositionMs = if (durationMs > 0L) durationMs else currentPositionMs
        diagnostics.playbackProgress(PlaybackEnginePath.WebAudioElement, endedPositionMs, durationMs)
        applyPlatformPlayback(
            positionMs = endedPositionMs,
            durationMs = durationMs,
            isPlaying = playWhenReady,
            isBuffering = false,
            bufferedPositionMs = endedPositionMs,
            generation = generation,
        )
    }

    private fun bufferedPositionMs(positionMs: Long, durationMs: Long): Long {
        if (durationMs > 0L && currentUri?.isRemoteWebAudioUri() == false) return durationMs
        return webAudioBufferedPositionMs(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedRanges = audio.buffered.toWebAudioTimeRanges(),
            prefetchedPositionMs = prefetchedBufferedPositionMs(durationMs),
        )
    }

    private fun startWebAudioPrefetch(uri: String, generation: Int) {
        if (!uri.startsWith("http://", ignoreCase = true) && !uri.startsWith("https://", ignoreCase = true)) return
        startWebAudioPrefetch(
            uri,
            { loadedBytes, totalBytes -> handleWebAudioPrefetchProgress(generation, loadedBytes, totalBytes) },
            { completed -> handleWebAudioPrefetchComplete(generation, completed) },
        )
    }

    private fun startWebAudioPrefetchIfNeeded(uri: String?, generation: Int) {
        if (uri.isNullOrBlank() || prefetchStartedGeneration == generation) return
        val track = state.value.queue.find { it.streamUrl == uri || it.localUri == uri }
            ?: state.value.currentTrack?.takeIf { it.streamUrl == uri || it.localUri == uri }
        if (track?.id?.startsWith("radio:") == true || track?.durationMs == 0L) return
        prefetchStartedGeneration = generation
        startWebAudioPrefetch(uri, generation)
    }

    private fun handleWebAudioPrefetchProgress(generation: Int, loadedBytes: Double, totalBytes: Double) {
        if (generation != prefetchGeneration || !isPlayRequestCurrent(generation)) return
        if (!loadedBytes.isFinite() || !totalBytes.isFinite() || totalBytes <= 0.0) return
        val fraction = (loadedBytes / totalBytes).coerceIn(0.0, 1.0)
        if (fraction <= prefetchedBufferFraction + WebAudioPrefetchUpdateThreshold && fraction < 1.0) return
        prefetchedBufferFraction = maxOf(prefetchedBufferFraction, fraction)
        updatePrefetchedBufferedPosition(generation)
    }

    private fun handleWebAudioPrefetchComplete(generation: Int, completed: Boolean) {
        if (!completed || generation != prefetchGeneration || !isPlayRequestCurrent(generation)) return
        prefetchedBufferFraction = 1.0
        updatePrefetchedBufferedPosition(generation)
    }

    private fun updatePrefetchedBufferedPosition(generation: Int) {
        val bufferedPositionMs = prefetchedBufferedPositionMs(currentDurationMs())
        if (bufferedPositionMs > 0L) {
            updateBufferedPosition(bufferedPositionMs, generation)
        }
    }

    private fun currentDurationMs(): Long {
        val stateDurationMs = state.value.durationMs
        if (stateDurationMs > 0L) return stateDurationMs
        return if (audio.duration.isFinite() && audio.duration > 0.0) {
            (audio.duration * 1000.0).toLong()
        } else {
            0L
        }
    }

    private fun prefetchedBufferedPositionMs(durationMs: Long): Long {
        if (durationMs <= 0L || prefetchedBufferFraction <= 0.0) return 0L
        return (durationMs * prefetchedBufferFraction).toLong().coerceIn(0L, durationMs)
    }

    private fun resetWebAudioPrefetch(generation: Int = -1) {
        prefetchGeneration = generation
        prefetchStartedGeneration = -1
        prefetchedBufferFraction = 0.0
    }

    private fun scheduleRetry(generation: Int, reload: Boolean) {
        if (!isPlayRequestCurrent(generation) || !playWhenReady) return
        if (retryGeneration != generation) {
            retryGeneration = generation
            retryCount = 0
        }
        if (retryCount >= MaxStreamRetryCount) {
            markPlaybackFailed(generation)
            return
        }
        retryCount++
        val positionSeconds = audio.currentTime
        syncFromAudio(generation, isBuffering = true)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(StreamRetryBaseDelayMs * retryCount)
            if (!isPlayRequestCurrent(generation) || !playWhenReady) return@launch
            if (reload) {
                val uri = currentUri ?: return@launch
                pendingSeekAfterLoadSeconds = positionSeconds
                pendingPlayAfterLoad = true
                prepareAudioElementForCurrentEqualizer()
                audio.src = uri
                audio.load()
                applyCurrentEqualizer()
                if (playWhenReady) {
                    playWebAudio(generation)
                }
            }
            if (!reload) {
                playWebAudio(generation)
            }
        }
    }

    private fun playWebAudio(generation: Int) {
        val playbackAudio = audio
        playWebAudio(playbackAudio) { message ->
            if (shouldIgnoreBrowserPlaybackFailure(playbackAudio, message)) return@playWebAudio
            if (!isPlayRequestCurrent(generation) || !playWhenReady) return@playWebAudio
            if (message.isBrowserAutoplayBlockedFailure()) {
                markPlaybackWaitingForUserGesture(generation)
                return@playWebAudio
            }
            if (audioUsesCors && currentUri?.isRemoteWebAudioUri() == true && retryWithoutCors(generation)) {
                return@playWebAudio
            }
            diagnostics.playbackError(PlaybackEnginePath.WebAudioElement, message)
            markPlaybackFailed(generation = generation, message = message)
        }
    }

    private fun shouldIgnoreBrowserPlaybackFailure(playbackAudio: HTMLAudioElement, message: String?): Boolean {
        if (audio !== playbackAudio) return true
        return message.isUnsupportedSourceFailure() && isWebAudioEnded(playbackAudio)
    }

    private companion object {
        const val MaxStreamRetryCount = 5
        const val StreamRetryBaseDelayMs = 1_000L
        const val WebCrossfadeSteps = 24
        const val WebCrossfadeMinimumFadeMs = 500L
    }
}

private fun webEqualizerPayload(profile: EqualizerProfile): String {
    val normalized = profile.normalized()
    val bands = normalized.bands.joinToString(
        prefix = "[",
        postfix = "]",
    ) { band -> band.frequencyHz.toString() }
    val gains = normalized.gainsDb.joinToString(
        prefix = "[",
        postfix = "]",
    ) { gain -> gain.toString() }
    return """{"enabled":${normalized.enabled},"bandCount":${normalized.bandCount},"bands":$bands,"gains":$gains}"""
}

private fun createAudioElement(useCors: Boolean): HTMLAudioElement =
    (document.createElement("audio") as HTMLAudioElement).apply {
        preload = "auto"
        if (useCors) {
            crossOrigin = "anonymous"
        }
    }

data class WebAudioTimeRange(
    val startMs: Long,
    val endMs: Long,
)

fun String.isRemoteWebAudioUri(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

private fun String?.isUnsupportedSourceFailure(): Boolean {
    val text = this?.lowercase() ?: return false
    return "no supported source" in text ||
        "src_not_supported" in text ||
        ("not supported" in text && "source" in text)
}

private fun String?.isBrowserAutoplayBlockedFailure(): Boolean {
    val text = this?.lowercase() ?: return false
    return "notallowederror" in text ||
        "user didn't interact" in text ||
        "user did not interact" in text ||
        "user must interact" in text ||
        "not allowed by the user agent" in text
}

fun webAudioPlaybackDurationMs(
    currentDurationMs: Long,
    browserDurationSeconds: Double,
): Long {
    if (currentDurationMs > 0L) return currentDurationMs
    return if (browserDurationSeconds.isFinite() && browserDurationSeconds > 0.0) {
        (browserDurationSeconds * 1000.0).toLong()
    } else {
        0L
    }
}

fun webAudioBufferedPositionMs(
    positionMs: Long,
    durationMs: Long,
    bufferedRanges: List<WebAudioTimeRange>,
    prefetchedPositionMs: Long = 0L,
): Long {
    val boundedPositionMs = positionMs.coerceAtLeast(0L)
    val playableEndMs = bufferedRanges
        .fold(boundedPositionMs) { currentEndMs, range ->
            if (boundedPositionMs + WebAudioRangeStartToleranceMs >= range.startMs) {
                maxOf(currentEndMs, range.endMs)
            } else {
                currentEndMs
            }
        }
        .coerceAtLeast(prefetchedPositionMs)
        .coerceAtLeast(boundedPositionMs)

    if (durationMs <= 0L) return playableEndMs
    val boundedPlayableEndMs = playableEndMs.coerceAtMost(durationMs)
    return if (durationMs - boundedPlayableEndMs <= WebAudioDurationEndToleranceMs) {
        durationMs
    } else {
        boundedPlayableEndMs
    }
}

private fun org.w3c.dom.TimeRanges.toWebAudioTimeRanges(): List<WebAudioTimeRange> {
    val timeRanges = mutableListOf<WebAudioTimeRange>()
    for (index in 0 until length) {
        val startMs = start(index) * 1000.0
        val endMs = end(index) * 1000.0
        if (startMs.isFinite() && endMs.isFinite() && endMs > startMs) {
            timeRanges += WebAudioTimeRange(
                startMs = startMs.toLong().coerceAtLeast(0L),
                endMs = endMs.toLong().coerceAtLeast(0L),
            )
        }
    }
    return timeRanges
}

private const val WebAudioRangeStartToleranceMs = 250L
private const val WebAudioDurationEndToleranceMs = 750L
private const val WebAudioPrefetchUpdateThreshold = 0.005
private const val WebGaplessHotStartLeadMs = 90L
private const val WebGaplessBoundaryCommitLeadMs = 20L
private const val WebGaplessHotStartPollMs = 20L

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(audio, callback) => { audio.onprogress = () => callback(); }")
private external fun installWebAudioProgressHandler(audio: HTMLAudioElement, callback: () -> Unit)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """(uri, onProgress, onComplete) => {
        const previous = globalThis.__phoebeAudioPrefetchController;
        if (previous) {
            try { previous.abort(); } catch (_) {}
        }
        if (!uri || !/^https?:\/\//i.test(String(uri))) {
            try { onComplete(false); } catch (_) {}
            return;
        }
        const controller = new AbortController();
        globalThis.__phoebeAudioPrefetchController = controller;
        (async () => {
            let completed = false;
            try {
                const response = await fetch(uri, {
                    signal: controller.signal,
                    cache: "default",
                    credentials: "omit"
                });
                if (!response || !response.ok) {
                    throw new Error("prefetch failed: " + (response ? response.status : "no response"));
                }
                const total = Number(response.headers.get("Content-Length")) || 0;
                if (response.body && typeof response.body.getReader === "function") {
                    const reader = response.body.getReader();
                    let loaded = 0;
                    while (true) {
                        const chunk = await reader.read();
                        if (chunk.done) break;
                        loaded += chunk.value ? chunk.value.byteLength : 0;
                        if (total > 0) {
                            onProgress(loaded, total);
                        }
                    }
                    completed = true;
                } else {
                    const blob = await response.blob();
                    const size = blob?.size || total;
                    if (size > 0) {
                        onProgress(size, size);
                    }
                    completed = true;
                }
            } catch (error) {
                completed = false;
            } finally {
                if (globalThis.__phoebeAudioPrefetchController === controller) {
                    globalThis.__phoebeAudioPrefetchController = null;
                }
                try { onComplete(completed); } catch (_) {}
            }
        })();
    }""",
)
private external fun startWebAudioPrefetch(
    uri: String,
    onProgress: (Double, Double) -> Unit,
    onComplete: (Boolean) -> Unit,
)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """() => {
        const controller = globalThis.__phoebeAudioPrefetchController;
        if (controller) {
            try { controller.abort(); } catch (_) {}
            globalThis.__phoebeAudioPrefetchController = null;
        }
    }""",
)
private external fun cancelWebAudioPrefetch()

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """(audio, payload) => {
        const profile = JSON.parse(payload);
        if (!audio) return;
        const Ctx = globalThis.AudioContext || globalThis.webkitAudioContext;
        if (!Ctx) return;
        const active = profile.enabled && profile.gains?.some((gain) => Math.abs(gain) > 0.001);
        const crossfadeGains = globalThis.__phoebeCrossfadeGains;
        const crossfade = crossfadeGains?.get(audio);
        let eq = globalThis.__phoebeEqualizer;
        if (!active && (!eq || eq.audio !== audio) && !crossfade) return;
        if (!eq || eq.audio !== audio) {
            const context = crossfade?.context || eq?.context || new Ctx();
            let source;
            try {
                source = crossfade?.source || context.createMediaElementSource(audio);
            } catch (error) {
                // A media element can only have one source node. Reuse the previous one if it exists.
                if (!eq || eq.audio !== audio || !eq.source) return;
                source = eq.source;
            }
            const gainValue = Number(crossfade?.gain?.gain?.value ?? eq?.gainValue ?? 1);
            if (crossfade) {
                try { crossfade.gain.disconnect(); } catch (_) {}
                crossfadeGains?.delete(audio);
            }
            eq = { audio, context, source, nodes: [], gain: null, gainValue: Number.isFinite(gainValue) ? gainValue : 1 };
            globalThis.__phoebeEqualizer = eq;
        }
        eq.context.resume?.();
        try { eq.source.disconnect(); } catch (_) {}
        for (const node of eq.nodes || []) {
            try { node.disconnect(); } catch (_) {}
        }
        if (eq.gain) {
            try { eq.gain.disconnect(); } catch (_) {}
        } else {
            eq.gain = eq.context.createGain();
        }
        const gainValue = Number.isFinite(Number(eq.gainValue)) ? Number(eq.gainValue) : 1;
        try { eq.gain.gain.setValueAtTime(gainValue, eq.context.currentTime || 0); } catch (_) {
            eq.gain.gain.value = gainValue;
        }
        eq.nodes = [];
        let current = eq.source;
        const q = profile.bandCount === 31 ? 4.2 : profile.bandCount === 15 ? 2.1 : profile.bandCount === 5 ? 0.9 : 1.35;
        if (active) {
            for (let i = 0; i < profile.bands.length; i++) {
                const gain = profile.gains[i] || 0;
                if (Math.abs(gain) <= 0.001) continue;
                const filter = eq.context.createBiquadFilter();
                filter.type = "peaking";
                filter.frequency.value = profile.bands[i];
                filter.Q.value = q;
                filter.gain.value = gain;
                current.connect(filter);
                current = filter;
                eq.nodes.push(filter);
            }
        }
        current.connect(eq.gain);
        eq.gain.connect(eq.context.destination);
    }""",
)
private external fun applyWebEqualizer(audio: HTMLAudioElement, payload: String)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """(audio, enabled) => {
        if (!audio) return;
        if (enabled) {
            audio.crossOrigin = "anonymous";
        }
    }""",
)
private external fun prepareWebEqualizerAudio(audio: HTMLAudioElement, enabled: Boolean)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """(audio) => {
        const eq = globalThis.__phoebeEqualizer;
        return !!eq && eq.audio === audio && !!eq.source;
    }""",
)
private external fun isWebEqualizerAttached(audio: HTMLAudioElement): Boolean

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """(audio, gainValue, create) => {
        if (!audio) return false;
        const gain = Math.max(0, Math.min(1, Number(gainValue) || 0));
        const eq = globalThis.__phoebeEqualizer;
        if (eq && eq.audio === audio && eq.source) {
            if (!eq.gain) {
                eq.gain = eq.context.createGain();
            }
            eq.gainValue = gain;
            try { eq.context.resume?.(); } catch (_) {}
            try { eq.gain.gain.setValueAtTime(gain, eq.context.currentTime || 0); } catch (_) {
                eq.gain.gain.value = gain;
            }
            return true;
        }
        let gains = globalThis.__phoebeCrossfadeGains;
        if (!gains) {
            gains = new Map();
            globalThis.__phoebeCrossfadeGains = gains;
        }
        let entry = gains.get(audio);
        if (!entry && create) {
            const Ctx = globalThis.AudioContext || globalThis.webkitAudioContext;
            if (!Ctx) return false;
            const context = eq?.context || new Ctx();
            let source;
            try {
                source = context.createMediaElementSource(audio);
            } catch (error) {
                return false;
            }
            const gainNode = context.createGain();
            source.connect(gainNode);
            gainNode.connect(context.destination);
            entry = { context, source, gain: gainNode };
            gains.set(audio, entry);
        }
        if (!entry) return false;
        try { entry.context.resume?.(); } catch (_) {}
        try { entry.gain.gain.setValueAtTime(gain, entry.context.currentTime || 0); } catch (_) {
            entry.gain.gain.value = gain;
        }
        return true;
    }""",
)
private external fun setWebAudioOutputGain(audio: HTMLAudioElement, gain: Double, create: Boolean): Boolean

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """(audio, callback) => {
        if (!audio) return;
        const Ctx = globalThis.AudioContext || globalThis.webkitAudioContext;
        if (!Ctx) return;
        let analyses = globalThis.__phoebeAudioAnalyses;
        if (!analyses) {
            analyses = new Map();
            globalThis.__phoebeAudioAnalyses = analyses;
        }
        const previous = analyses.get(audio);
        if (previous) {
            try { clearInterval(previous.timer); } catch (_) {}
            try { previous.input.disconnect(previous.analyser); } catch (_) {}
        }
        try {
            let eq = globalThis.__phoebeEqualizer;
            if (!eq || eq.audio !== audio || !eq.source) {
                const context = eq?.context || new Ctx();
                let source;
                try {
                    source = context.createMediaElementSource(audio);
                } catch (error) {
                    return;
                }
                const gain = context.createGain();
                const gainValue = Number.isFinite(Number(audio.volume)) ? Number(audio.volume) : 1;
                try { gain.gain.setValueAtTime(gainValue, context.currentTime || 0); } catch (_) {
                    gain.gain.value = gainValue;
                }
                source.connect(gain);
                gain.connect(context.destination);
                audio.volume = 1;
                eq = { audio, context, source, nodes: [], gain, gainValue };
                globalThis.__phoebeEqualizer = eq;
            }
            const context = eq.context;
            const input = eq.gain || eq.source;
            const analyser = context.createAnalyser();
            analyser.fftSize = 256;
            analyser.smoothingTimeConstant = 0.72;
            input.connect(analyser);
            const data = new Uint8Array(analyser.frequencyBinCount);
            const timer = setInterval(() => {
                try {
                    context.resume?.();
                    analyser.getByteFrequencyData(data);
                    let sum = 0;
                    const bands = [];
                    const bandCount = 128;
                    const group = Math.max(1, Math.floor(data.length / bandCount));
                    for (let i = 0; i < bandCount; i++) {
                        let peak = 0;
                        const start = i * group;
                        const end = i === bandCount - 1 ? data.length : Math.min(data.length, start + group);
                        for (let j = start; j < end; j++) peak = Math.max(peak, data[j] || 0);
                        const v = Math.max(0, Math.min(1, peak / 255));
                        bands.push(v);
                        sum += v * v;
                    }
                    const amplitude = Math.sqrt(sum / Math.max(1, bands.length));
                    callback(amplitude, bands.join(","), Date.now());
                } catch (_) {}
            }, 50);
            analyses.set(audio, { timer, analyser, input });
        } catch (_) {}
    }""",
)
private external fun startWebAudioAnalysis(
    audio: HTMLAudioElement,
    callback: (Double, String, Double) -> Unit,
)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """(audio) => {
        const analyses = globalThis.__phoebeAudioAnalyses;
        const entry = analyses?.get(audio);
        if (!entry) return;
        try { clearInterval(entry.timer); } catch (_) {}
        try { entry.input.disconnect(entry.analyser); } catch (_) {}
        analyses.delete(audio);
    }""",
)
private external fun stopWebAudioAnalysis(audio: HTMLAudioElement)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """(audio) => {
        if (!audio) return;
        const analyses = globalThis.__phoebeAudioAnalyses;
        const analysis = analyses?.get(audio);
        if (analysis) {
            try { clearInterval(analysis.timer); } catch (_) {}
            try { analysis.input.disconnect(analysis.analyser); } catch (_) {}
            analyses.delete(audio);
        }
        try { audio.pause(); } catch (_) {}
        audio.onloadedmetadata = null;
        audio.onloadeddata = null;
        audio.ondurationchange = null;
        audio.onplaying = null;
        audio.onpause = null;
        audio.onwaiting = null;
        audio.onstalled = null;
        audio.oncanplay = null;
        audio.oncanplaythrough = null;
        audio.onprogress = null;
        audio.onsuspend = null;
        audio.ontimeupdate = null;
        audio.onended = null;
        audio.onerror = null;
        const eq = globalThis.__phoebeEqualizer;
        if (eq && eq.audio === audio) {
            try { eq.source.disconnect(); } catch (_) {}
            for (const node of eq.nodes || []) {
                try { node.disconnect(); } catch (_) {}
            }
            if (eq.gain) {
                try { eq.gain.disconnect(); } catch (_) {}
            }
            globalThis.__phoebeEqualizer = { context: eq.context, audio: null, source: null, nodes: [], gain: null, gainValue: 1 };
        }
        const gains = globalThis.__phoebeCrossfadeGains;
        const entry = gains?.get(audio);
        if (entry) {
            try { entry.source.disconnect(); } catch (_) {}
            try { entry.gain.disconnect(); } catch (_) {}
            gains.delete(audio);
        }
    }""",
)
private external fun disposeWebAudioElement(audio: HTMLAudioElement)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """(audio) => {
        const error = audio && audio.error;
        if (!error) return "";
        const code = Number(error.code || 0);
        const names = {
            1: "MEDIA_ERR_ABORTED",
            2: "MEDIA_ERR_NETWORK",
            3: "MEDIA_ERR_DECODE",
            4: "MEDIA_ERR_SRC_NOT_SUPPORTED"
        };
        return names[code] || ("HTMLMediaElement error " + code);
    }""",
)
private external fun webAudioErrorMessage(audio: HTMLAudioElement): String

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(audio) => !!(audio && audio.ended)")
private external fun isWebAudioEnded(audio: HTMLAudioElement): Boolean

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """(audio, seconds) => {
        try {
            if (!audio || !Number.isFinite(seconds)) return false;
            audio.currentTime = Math.max(0, seconds);
            return true;
        } catch (error) {
            console.warn("Phoebe web audio seek failed.", error);
            return false;
        }
    }""",
)
private external fun setWebAudioCurrentTime(audio: HTMLAudioElement, seconds: Double): Boolean

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """(audio, onFailure) => {
        try {
            const Ctx = globalThis.AudioContext || globalThis.webkitAudioContext;
            if (Ctx) {
                let eq = globalThis.__phoebeEqualizer;
                if (!eq || !eq.context) {
                    eq = { context: new Ctx(), audio: null, source: null, nodes: [] };
                    globalThis.__phoebeEqualizer = eq;
                }
                eq.context?.resume?.();
            }
            const activeEq = globalThis.__phoebeEqualizer;
            if (activeEq && activeEq.audio === audio) {
                activeEq.context?.resume?.();
            }
            const playResult = audio.play();
            if (playResult && typeof playResult.catch === "function") {
                playResult.catch((error) => {
                    const message = error?.message || error?.name || "Web audio playback failed.";
                    console.warn("Phoebe web audio playback was blocked or failed.", error);
                    try { onFailure(String(message)); } catch (_) {}
                });
            }
        } catch (error) {
            console.warn("Phoebe web audio playback failed.", error);
            const message = error?.message || error?.name || "Web audio playback failed.";
            try { onFailure(String(message)); } catch (_) {}
        }
    }""",
)
private external fun playWebAudio(audio: HTMLAudioElement, onFailure: (String) -> Unit)
