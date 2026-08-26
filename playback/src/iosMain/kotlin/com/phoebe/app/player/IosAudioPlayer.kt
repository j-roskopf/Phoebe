package com.phoebe.app.player

import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.PhoebeLog
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import kotlinx.cinterop.CValue
import platform.AVFoundation.AVMutableAudioMix
import platform.AVFoundation.AVMutableAudioMixInputParameters
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVKeyValueStatusLoaded
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemPlaybackStalledNotification
import platform.AVFoundation.AVPlayerItemTrack
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.automaticallyWaitsToMinimizeStalling
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.isPlaybackLikelyToKeepUp
import platform.AVFoundation.loadValuesAsynchronouslyForKeys
import platform.AVFoundation.mediaType
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.preferredForwardBufferDuration
import platform.AVFoundation.rate
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setAudioMix
import platform.AVFoundation.statusOfValueForKey
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.tracks
import platform.AVFoundation.volume
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatFlagIsFloat
import platform.CoreAudioTypes.kAudioFormatFlagIsNonInterleaved
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.MediaToolbox.MTAudioProcessingTapCallbacks
import platform.MediaToolbox.MTAudioProcessingTapCreate
import platform.MediaToolbox.MTAudioProcessingTapGetSourceAudio
import platform.MediaToolbox.MTAudioProcessingTapGetStorage
import platform.MediaToolbox.MTAudioProcessingTapRef
import platform.MediaToolbox.MTAudioProcessingTapRefVar
import platform.MediaToolbox.kMTAudioProcessingTapCallbacksVersion_0
import platform.MediaToolbox.kMTAudioProcessingTapCreationFlag_PostEffects
import platform.darwin.NSEC_PER_SEC
import com.phoebe.app.domain.AudioAnalysisSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

actual fun createAudioPlayer(): AudioPlayer = IosAudioPlayer()

fun createIosAudioPlayerForSmoke(diagnostics: PlaybackDiagnostics): AudioPlayer =
    IosAudioPlayer(diagnostics)

@OptIn(ExperimentalForeignApi::class)
private class IosAudioPlayer(
    private val diagnostics: PlaybackDiagnostics = PlaybackDiagnostics.None,
) : SimpleAudioPlayer() {
    private var player: AVPlayer? = null
    private var timeObserver: Any? = null
    private var endObserver: Any? = null
    private var stalledObserver: Any? = null
    private var failedObserver: Any? = null
    private var observedGeneration = -1
    private var currentUri: String? = null
    private var currentAsset: AVURLAsset? = null
    private var lastKnownPositionMs = 0L
    private var retryGeneration = -1
    private var retryCount = 0
    private var retryJob: Job? = null
    private var crossfadePlayer: AVPlayer? = null
    private var crossfadeJob: Job? = null
    private var crossfadeGeneration = -1
    private var crossfadeEqualizerTap: IosEqualizerTap? = null
    private var gaplessPlayer: AVPlayer? = null
    private var gaplessItem: AVPlayerItem? = null
    private var gaplessAsset: AVURLAsset? = null
    private var gaplessGeneration = -1
    private var gaplessTrackId: String? = null
    private var equalizerTap: IosEqualizerTap? = null
    private var equalizerTapTrackLoadRequested = false
    private var equalizerInstallSuppressedForCurrentItem = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val useProgressTicker: Boolean = false

    init {
        configureAudioSession()
    }

    override fun stopCurrentPlaybackImmediately() {
        retryJob?.cancel()
        stopIosCrossfade()
        stopIosGapless()
        player?.pause()
    }

    override fun playUri(uri: String) {
        if (uri.isBlank()) {
            markPlaybackFailed()
            return
        }
        val generation = activePlayGeneration
        val url = NSURL.URLWithString(uri)
        if (url == null) {
            markPlaybackFailed(generation)
            return
        }
        currentUri = uri
        currentAsset = null
        retryGeneration = generation
        retryCount = 0
        retryJob?.cancel()
        stopIosCrossfade()
        stopIosGapless()
        clearObservers()
        equalizerTap = null
        equalizerTapTrackLoadRequested = false
        equalizerInstallSuppressedForCurrentItem = false
        val asset = AVURLAsset.URLAssetWithURL(url, options = null)
        currentAsset = asset
        val item = if (isLiveStream(uri)) {
            AVPlayerItem(asset = asset)
        } else {
            AVPlayerItem(asset = asset, automaticallyLoadedAssetKeys = listOf(IosAssetTracksKey))
        }
        item.preferredForwardBufferDuration = PreferredForwardBufferSeconds
        installEqualizerTap(item, asset, allowTrackLoad = true)
        val avPlayer = player ?: AVPlayer().also { player = it }
        avPlayer.automaticallyWaitsToMinimizeStalling = true
        avPlayer.volume = effectiveOutputVolume()
        avPlayer.replaceCurrentItemWithPlayerItem(item)
        observePlayback(avPlayer, item, generation)
        if (playWhenReady) {
            avPlayer.play()
        }
    }

    override fun pause() {
        retryJob?.cancel()
        stopIosCrossfade()
        stopIosGapless()
        player?.pause()
    }

    override fun resume() {
        player?.play()
    }

    override fun seek(positionMs: Long) {
        stopIosCrossfade()
        stopIosGapless()
        val avPlayer = player ?: return
        val seconds = positionMs.coerceAtLeast(0L) / 1000.0
        val time = CMTimeMakeWithSeconds(seconds, NSEC_PER_SEC.toInt())
        avPlayer.seekToTime(time)
    }

    override fun setOutputVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        player?.volume = v
        crossfadePlayer?.volume = v
        gaplessPlayer?.volume = v
    }

    override fun applyEqualizer(profile: EqualizerProfile) {
        val normalized = profile.normalized()
        equalizerTap?.updateProfile(normalized)
        crossfadeEqualizerTap?.updateProfile(normalized)
        val item = player?.currentItem
        val asset = currentAsset
        if (item != null && asset != null && equalizerTap == null && !equalizerInstallSuppressedForCurrentItem) {
            installEqualizerTap(item, asset, allowTrackLoad = true)
        }
    }

    override fun startGaplessPrepareOnPlatform(
        queue: List<Track>,
        targetIndex: Int,
        track: Track,
        generation: Int,
    ): Boolean {
        if (targetIndex !in queue.indices || player == null) return false
        val uri = StreamingPlaybackPolicyHolder.resolvePlaybackUri(track).takeIf { it.isNotBlank() } ?: return false
        val url = NSURL.URLWithString(uri) ?: return false
        stopIosGapless()
        gaplessGeneration = generation
        gaplessTrackId = track.id
        val asset = AVURLAsset.URLAssetWithURL(url, options = null)
        val item = if (isLiveStream(uri) || track.id.startsWith("radio:")) {
            AVPlayerItem(asset = asset)
        } else {
            AVPlayerItem(asset = asset, automaticallyLoadedAssetKeys = listOf(IosAssetTracksKey))
        }
        item.preferredForwardBufferDuration = GaplessPreferredForwardBufferSeconds
        val prepared = AVPlayer()
        prepared.automaticallyWaitsToMinimizeStalling = true
        prepared.volume = effectiveOutputVolume()
        prepared.replaceCurrentItemWithPlayerItem(item)
        gaplessPlayer = prepared
        gaplessItem = item
        gaplessAsset = asset
        return true
    }

    override fun commitGaplessPreparedOnPlatform(
        queue: List<Track>,
        targetIndex: Int,
        track: Track,
        generation: Int,
    ): Boolean {
        if (gaplessGeneration != generation || gaplessTrackId != track.id) return false
        val incoming = gaplessPlayer ?: return false
        val item = gaplessItem ?: return false
        val asset = gaplessAsset ?: return false
        val uri = StreamingPlaybackPolicyHolder.resolvePlaybackUri(track).takeIf { it.isNotBlank() } ?: return false
        clearObservers()
        player?.pause()
        player?.replaceCurrentItemWithPlayerItem(null)
        player = incoming
        currentUri = uri
        currentAsset = asset
        lastKnownPositionMs = 0L
        retryGeneration = generation
        retryCount = 0
        equalizerTap = null
        equalizerTapTrackLoadRequested = false
        equalizerInstallSuppressedForCurrentItem = false
        gaplessPlayer = null
        gaplessItem = null
        gaplessAsset = null
        gaplessGeneration = -1
        gaplessTrackId = null
        installEqualizerTap(item, asset, allowTrackLoad = true)
        observePlayback(incoming, item, generation)
        incoming.seekToTime(CMTimeMakeWithSeconds(0.0, NSEC_PER_SEC.toInt()))
        if (playWhenReady) {
            incoming.play()
        }
        return true
    }

    override fun cancelGaplessPrepareOnPlatform(generation: Int) {
        if (gaplessGeneration == generation) {
            stopIosGapless()
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
        val outgoing = player ?: return false
        val uri = StreamingPlaybackPolicyHolder.resolvePlaybackUri(track).takeIf { it.isNotBlank() } ?: return false
        val url = NSURL.URLWithString(uri) ?: return false
        crossfadeGeneration = generation
        crossfadeJob?.cancel()
        crossfadeJob = scope.launch {
            var incoming: AVPlayer? = null
            var incomingEqualizerTap: IosEqualizerTap? = null
            var adopted = false
            try {
                val isLive = isLiveStream(uri) || track.id.startsWith("radio:")
                val asset = AVURLAsset.URLAssetWithURL(url, options = null)
                val item = if (isLive) {
                    AVPlayerItem(asset = asset)
                } else {
                    AVPlayerItem(asset = asset, automaticallyLoadedAssetKeys = listOf(IosAssetTracksKey))
                }
                item.preferredForwardBufferDuration = PreferredForwardBufferSeconds
                incomingEqualizerTap = if (isLive) null else attachEqualizerTapIfReady(item, asset, equalizerProfile)
                crossfadeEqualizerTap = incomingEqualizerTap
                incoming = AVPlayer().also { avPlayer ->
                    avPlayer.automaticallyWaitsToMinimizeStalling = true
                    avPlayer.volume = 0f
                    avPlayer.replaceCurrentItemWithPlayerItem(item)
                }
                crossfadePlayer = incoming
                incoming.play()
                if (!waitForIosCrossfadePlayback(incoming, generation)) return@launch
                if (!isIosCrossfadeCurrent(generation, outgoing, incoming)) return@launch

                clearEndObserver()
                diagnostics.crossfadeStarted(
                    engine = PlaybackEnginePath.AvPlayer,
                    outgoingTrackId = state.value.currentTrack?.id,
                    incomingTrackId = track.id,
                    durationMs = durationMs,
                )
                fadeIosCrossfadeVolumes(
                    outgoing = outgoing,
                    incoming = incoming,
                    durationMs = iosCrossfadeFadeDurationMs(outgoing, durationMs),
                    baseVolume = baseVolume,
                    generation = generation,
                )
                if (!isIosCrossfadeCurrent(generation, outgoing, incoming)) return@launch
                adopted = commitIosCrossfade(
                    outgoing = outgoing,
                    incoming = incoming,
                    item = item,
                    asset = asset,
                    uri = uri,
                    queue = queue,
                    targetIndex = targetIndex,
                    generation = generation,
                    incomingEqualizerTap = incomingEqualizerTap,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                PhoebeLog.d("IosAudioPlayer") { "ios crossfade failed: ${error.message}" }
                diagnostics.playbackError(PlaybackEnginePath.AvPlayer, error.message)
            } finally {
                if (!adopted) {
                    incoming?.pause()
                    incoming?.replaceCurrentItemWithPlayerItem(null)
                    if (crossfadePlayer === incoming) crossfadePlayer = null
                }
                if (!adopted && crossfadeEqualizerTap === incomingEqualizerTap) {
                    crossfadeEqualizerTap = null
                }
                if (crossfadeGeneration == generation) crossfadeGeneration = -1
                if (!adopted) {
                    player?.volume = effectiveOutputVolume()
                }
            }
        }
        return true
    }

    private suspend fun waitForIosCrossfadePlayback(avPlayer: AVPlayer, generation: Int): Boolean {
        var waitedMs = 0L
        while (waitedMs < CrossfadePrepareTimeoutMs && isPlayRequestCurrent(generation)) {
            if (avPlayer.timeControlStatus == AVPlayerTimeControlStatusPlaying) return true
            if (avPlayer.rate > 0f) return true
            delay(50)
            waitedMs += 50
        }
        return false
    }

    private suspend fun fadeIosCrossfadeVolumes(
        outgoing: AVPlayer,
        incoming: AVPlayer,
        durationMs: Long,
        baseVolume: Float,
        generation: Int,
    ) {
        val stepDelayMs = (durationMs / CrossfadeSteps).coerceAtLeast(16L)
        repeat(CrossfadeSteps) { index ->
            if (!isPlayRequestCurrent(generation) || crossfadePlayer !== incoming || player !== outgoing || !playWhenReady) {
                return
            }
            val progress = (index + 1).toFloat() / CrossfadeSteps.toFloat()
            val outgoingVolume = (baseVolume * (1f - progress)).coerceIn(0f, 1f)
            val incomingVolume = (baseVolume * progress).coerceIn(0f, 1f)
            diagnostics.crossfadeVolume(
                engine = PlaybackEnginePath.AvPlayer,
                step = index + 1,
                outgoingVolume = outgoingVolume,
                incomingVolume = incomingVolume,
            )
            outgoing.volume = outgoingVolume
            incoming.volume = incomingVolume
            delay(stepDelayMs)
        }
    }

    private fun commitIosCrossfade(
        outgoing: AVPlayer,
        incoming: AVPlayer,
        item: AVPlayerItem,
        asset: AVURLAsset,
        uri: String,
        queue: List<Track>,
        targetIndex: Int,
        generation: Int,
        incomingEqualizerTap: IosEqualizerTap?,
    ): Boolean {
        if (!isIosCrossfadeCurrent(generation, outgoing, incoming)) return false
        val positionMs = cmTimeToMs(incoming.currentTime())
        clearObservers()
        outgoing.pause()
        outgoing.replaceCurrentItemWithPlayerItem(null)
        player = incoming
        crossfadePlayer = null
        crossfadeJob = null
        crossfadeGeneration = -1
        currentUri = uri
        currentAsset = asset
        lastKnownPositionMs = positionMs
        retryGeneration = generation
        retryCount = 0
        retryJob?.cancel()
        equalizerTap = incomingEqualizerTap
        crossfadeEqualizerTap = null
        equalizerTapTrackLoadRequested = false
        equalizerInstallSuppressedForCurrentItem = incomingEqualizerTap == null
        incoming.volume = effectiveOutputVolume()
        observePlayback(incoming, item, generation)
        adoptCrossfadeTarget(
            queue = queue,
            targetIndex = targetIndex,
            positionMs = positionMs,
            generation = generation,
        )
        diagnostics.crossfadeCommitted(
            engine = PlaybackEnginePath.AvPlayer,
            incomingTrackId = queue[targetIndex].id,
        )
        applyPlatformPlayback(
            positionMs = positionMs,
            durationMs = cmTimeToMs(CMTimeGetSeconds(item.duration)),
            isPlaying = incoming.timeControlStatus == AVPlayerTimeControlStatusPlaying,
            isBuffering = false,
            bufferedPositionMs = estimatedBufferedPositionMs(
                positionMs = positionMs,
                durationMs = queue[targetIndex].durationMs,
                likelyReady = item.isPlaybackLikelyToKeepUp(),
            ),
            generation = generation,
        )
        return true
    }

    private fun stopIosCrossfade() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        crossfadePlayer?.pause()
        crossfadePlayer?.replaceCurrentItemWithPlayerItem(null)
        crossfadePlayer = null
        crossfadeEqualizerTap = null
        crossfadeGeneration = -1
        player?.volume = effectiveOutputVolume()
    }

    private fun stopIosGapless() {
        gaplessPlayer?.pause()
        gaplessPlayer?.replaceCurrentItemWithPlayerItem(null)
        gaplessPlayer = null
        gaplessItem = null
        gaplessAsset = null
        gaplessGeneration = -1
        gaplessTrackId = null
    }

    private fun clearEndObserver() {
        endObserver?.let { token -> NSNotificationCenter.defaultCenter.removeObserver(token) }
        endObserver = null
    }

    private fun isIosCrossfadeCurrent(
        generation: Int,
        outgoing: AVPlayer,
        incoming: AVPlayer,
    ): Boolean =
        isPlayRequestCurrent(generation) &&
            crossfadeGeneration == generation &&
            player === outgoing &&
            crossfadePlayer === incoming &&
            playWhenReady

    private fun iosCrossfadeFadeDurationMs(outgoing: AVPlayer, configuredDurationMs: Long): Long {
        val durationMs = outgoing.currentItem
            ?.let { cmTimeToMs(CMTimeGetSeconds(it.duration)) }
            ?.takeIf { it > 0L }
            ?: state.value.durationMs
        val positionMs = cmTimeToMs(outgoing.currentTime())
        val remainingMs = if (durationMs > 0L) {
            (durationMs - positionMs).coerceAtLeast(0L)
        } else {
            configuredDurationMs
        }
        return remainingMs
            .coerceAtMost(configuredDurationMs)
            .coerceAtLeast(CrossfadeMinimumFadeMs)
    }

    private fun installEqualizerTap(
        item: AVPlayerItem,
        asset: AVURLAsset,
        allowTrackLoad: Boolean,
    ) {
        if (equalizerTap != null) return
        val tap = attachEqualizerTapIfReady(item, asset, equalizerProfile)
        if (tap != null) {
            equalizerTap = tap
            return
        }
        val uri = currentUri ?: ""
        if (isLiveStream(uri)) return
        if (allowTrackLoad && !equalizerTapTrackLoadRequested) {
            equalizerTapTrackLoadRequested = true
            asset.loadValuesAsynchronouslyForKeys(listOf(IosAssetTracksKey)) {
                scope.launch {
                    if (player?.currentItem != item || equalizerTap != null || equalizerInstallSuppressedForCurrentItem) {
                        return@launch
                    }
                    val loaded = asset.statusOfValueForKey(IosAssetTracksKey, error = null) == AVKeyValueStatusLoaded
                    if (!loaded) {
                        PhoebeLog.d("IosAudioPlayer") { "equalizer audio track loading failed" }
                        return@launch
                    }
                    installEqualizerTap(item, asset, allowTrackLoad = false)
                }
            }
        }
    }

    private fun attachEqualizerTapIfReady(
        item: AVPlayerItem,
        asset: AVURLAsset,
        profile: EqualizerProfile,
    ): IosEqualizerTap? {
        val audioTrack = findAudioTrack(item, asset)
        if (audioTrack == null) {
            return null
        }
        return attachEqualizerTap(item, audioTrack, profile)
    }

    private fun attachEqualizerTap(
        item: AVPlayerItem,
        audioTrack: AVAssetTrack,
        profile: EqualizerProfile,
    ): IosEqualizerTap? {
        val tap = IosEqualizerTap(profile.normalized()) { samples, sampleRateHz ->
            publishAudioAnalysisPcm(samples, sampleRateHz, AudioAnalysisSource.Pcm)
        }
        val tapRef = createEqualizerProcessingTap(tap)
        if (tapRef == null) {
            PhoebeLog.d("IosAudioPlayer") { "equalizer audio tap creation failed" }
            return null
        }
        val inputParameters = AVMutableAudioMixInputParameters.audioMixInputParametersWithTrack(audioTrack)
        inputParameters.setAudioTapProcessor(tapRef)
        val audioMix = AVMutableAudioMix.audioMix()
        audioMix.setInputParameters(listOf(inputParameters))
        item.setAudioMix(audioMix)
        CFRelease(tapRef)
        return tap
    }

    private fun findAudioTrack(item: AVPlayerItem, asset: AVURLAsset): AVAssetTrack? =
        item.tracks()
            .asSequence()
            .mapNotNull { (it as? AVPlayerItemTrack)?.assetTrack }
            .firstOrNull { it.mediaType == AVMediaTypeAudio }
            ?: asset.tracks()
                .asSequence()
                .filterIsInstance<AVAssetTrack>()
                .firstOrNull { it.mediaType == AVMediaTypeAudio }

    private fun configureAudioSession() {
        runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(active = true, error = null)
        }.onFailure { error ->
            PhoebeLog.d("IosAudioPlayer") { "audio session setup failed: ${error.message}" }
        }
    }

    private fun observePlayback(avPlayer: AVPlayer, item: AVPlayerItem, generation: Int) {
        observedGeneration = generation
        val interval = CMTimeMakeWithSeconds(0.25, NSEC_PER_SEC.toInt())
        timeObserver = avPlayer.addPeriodicTimeObserverForInterval(interval, queue = null) { time ->
            if (!isPlayRequestCurrent(generation)) return@addPeriodicTimeObserverForInterval
            val positionMs = cmTimeToMs(time)
            lastKnownPositionMs = positionMs
            val durationMs = avPlayer.currentItem?.let { currentItem ->
                cmTimeToMs(CMTimeGetSeconds(currentItem.duration))
            } ?: 0L
            val waiting = avPlayer.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
            val playing = avPlayer.timeControlStatus == AVPlayerTimeControlStatusPlaying
            val likelyReady = avPlayer.currentItem?.isPlaybackLikelyToKeepUp() == true
            val isBuffering = playWhenReady && waiting && !likelyReady
            if (equalizerTap == null && !equalizerInstallSuppressedForCurrentItem) {
                currentAsset?.let { asset -> installEqualizerTap(item, asset, allowTrackLoad = false) }
            }
            if (playing && playWhenReady) {
                diagnostics.platformPlaying(PlaybackEnginePath.AvPlayer, positionMs, durationMs)
                retryCount = 0
                retryJob?.cancel()
                retryJob = null
            }
            applyPlatformPlayback(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = playing && playWhenReady,
                isBuffering = isBuffering,
                bufferedPositionMs = estimatedBufferedPositionMs(positionMs, durationMs, likelyReady),
                generation = generation,
            )
        }
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            if (!isPlayRequestCurrent(generation)) return@addObserverForName
            advanceAfterPlatformTrackEnded(generation)
        }
        stalledObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemPlaybackStalledNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            scheduleRetry(generation)
        }
        failedObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemFailedToPlayToEndTimeNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            scheduleRetry(generation)
        }
    }

    private fun clearObservers() {
        timeObserver?.let { token -> player?.removeTimeObserver(token) }
        timeObserver = null
        endObserver?.let { token -> NSNotificationCenter.defaultCenter.removeObserver(token) }
        endObserver = null
        stalledObserver?.let { token -> NSNotificationCenter.defaultCenter.removeObserver(token) }
        stalledObserver = null
        failedObserver?.let { token -> NSNotificationCenter.defaultCenter.removeObserver(token) }
        failedObserver = null
        observedGeneration = -1
    }

    private fun scheduleRetry(generation: Int) {
        if (!isPlayRequestCurrent(generation) || !playWhenReady) return
        if (retryGeneration != generation) {
            retryGeneration = generation
            retryCount = 0
        }
        if (retryCount >= MaxStreamRetryCount) {
            if (replayWithFailoverUri(generation, currentUri)) return
            markPlaybackFailed(generation)
            return
        }
        retryCount++
        val avPlayer = player ?: return
        val positionMs = lastKnownPositionMs
        val durationMs = avPlayer.currentItem?.let { cmTimeToMs(CMTimeGetSeconds(it.duration)) } ?: state.value.durationMs
        applyPlatformPlayback(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = false,
            isBuffering = true,
            bufferedPositionMs = state.value.bufferedPositionMs.coerceAtLeast(positionMs),
            generation = generation,
        )
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(StreamRetryBaseDelayMs * retryCount)
            if (!isPlayRequestCurrent(generation) || !playWhenReady) return@launch
            val uri = currentUri ?: return@launch
            val url = NSURL.URLWithString(uri) ?: return@launch
            clearObservers()
            equalizerTap = null
            equalizerTapTrackLoadRequested = false
            equalizerInstallSuppressedForCurrentItem = false
            val asset = AVURLAsset.URLAssetWithURL(url, options = null)
            currentAsset = asset
            val item = if (isLiveStream(uri)) {
                AVPlayerItem(asset = asset)
            } else {
                AVPlayerItem(asset = asset, automaticallyLoadedAssetKeys = listOf(IosAssetTracksKey))
            }
            item.preferredForwardBufferDuration = PreferredForwardBufferSeconds
            installEqualizerTap(item, asset, allowTrackLoad = true)
            avPlayer.replaceCurrentItemWithPlayerItem(item)
            observePlayback(avPlayer, item, generation)
            avPlayer.seekToTime(CMTimeMakeWithSeconds(positionMs.toDouble() / 1000.0, NSEC_PER_SEC.toInt()))
            avPlayer.play()
        }
    }

    private fun estimatedBufferedPositionMs(positionMs: Long, durationMs: Long, likelyReady: Boolean): Long {
        if (!likelyReady) return state.value.bufferedPositionMs.coerceAtLeast(positionMs)
        val target = positionMs + (PreferredForwardBufferSeconds * 1000.0).toLong()
        return if (durationMs > 0L) target.coerceAtMost(durationMs) else target
    }

    private fun cmTimeToMs(seconds: Double): Long {
        if (seconds.isNaN() || seconds.isInfinite()) return 0L
        return (seconds * 1000.0).toLong().coerceAtLeast(0L)
    }

    private fun cmTimeToMs(time: CValue<CMTime>): Long = cmTimeToMs(CMTimeGetSeconds(time))

    private fun isLiveStream(uri: String): Boolean {
        val lower = uri.lowercase()
        return lower.contains(".m3u8") ||
            lower.contains(".m3u") ||
            lower.contains("/live") ||
            lower.contains("://radio.") ||
            lower.contains(".radio.") ||
            lower.contains("/radio/") ||
            lower.endsWith("/radio")
    }

    private companion object {
        // Avoid asking AVPlayer to retain very large forward buffers on device.
        const val PreferredForwardBufferSeconds = 10.0
        const val GaplessPreferredForwardBufferSeconds = 5.0
        const val MaxStreamRetryCount = 5
        const val StreamRetryBaseDelayMs = 1_000L
        const val IosAssetTracksKey = "tracks"
        const val CrossfadeSteps = 24
        const val CrossfadePrepareTimeoutMs = 5_000L
        const val CrossfadeMinimumFadeMs = 500L
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosEqualizerTap(
    initialProfile: EqualizerProfile,
    private val analysisSink: (FloatArray, Float) -> Unit,
) {
    @Volatile
    private var profile: EqualizerProfile = initialProfile.normalized()
    private var format: IosEqualizerAudioFormat? = null
    private var processor: GraphicEqualizerProcessor? = null
    private var processorProfile: EqualizerProfile? = null
    private var processorFormat: IosEqualizerAudioFormat? = null

    fun updateProfile(next: EqualizerProfile) {
        profile = next.normalized()
    }

    fun prepare(processingFormat: AudioStreamBasicDescription) {
        format = IosEqualizerAudioFormat.from(processingFormat)
        processor = null
        processorProfile = null
        processorFormat = null
    }

    fun unprepare() {
        format = null
        processor = null
        processorProfile = null
        processorFormat = null
    }

    fun process(bufferList: AudioBufferList, frameCount: Int) {
        val currentFormat = format ?: return
        publishAnalysis(bufferList, frameCount, currentFormat)
        val currentProfile = profile
        if (!GraphicEqualizerProcessor.isActive(currentProfile)) {
            processor = null
            processorProfile = null
            processorFormat = null
            return
        }
        val currentProcessor = if (
            processor == null ||
            processorProfile != currentProfile ||
            processorFormat != currentFormat
        ) {
            GraphicEqualizerProcessor(
                sampleRateHz = currentFormat.sampleRateHz,
                channelCount = currentFormat.channelCount,
                profile = currentProfile,
            ).also {
                processor = it
                processorProfile = currentProfile
                processorFormat = currentFormat
            }
        } else {
            processor ?: return
        }
        processFloatBuffers(bufferList, frameCount, currentFormat, currentProcessor)
    }

    private fun publishAnalysis(
        bufferList: AudioBufferList,
        frameCount: Int,
        format: IosEqualizerAudioFormat,
        maxSamples: Int = 2048,
    ) {
        if (!format.isFloat32) return
        val numberBuffers = bufferList.mNumberBuffers.toInt().coerceAtLeast(0)
        if (numberBuffers <= 0 || frameCount <= 0) return
        val estimatedSamples = (frameCount * format.channelCount.coerceAtLeast(1)).coerceAtLeast(1)
        val stride = (estimatedSamples / maxSamples.coerceAtLeast(1)).coerceAtLeast(1)
        val outputSize = ((estimatedSamples + stride - 1) / stride).coerceAtMost(maxSamples.coerceAtLeast(1))
        val output = FloatArray(outputSize)
        var sampleOrdinal = 0
        var outputIndex = 0
        for (bufferIndex in 0 until numberBuffers) {
            val buffer = bufferList.mBuffers[bufferIndex]
            val data = buffer.mData?.reinterpret<FloatVar>() ?: continue
            val bufferChannels = buffer.mNumberChannels.toInt().coerceAtLeast(1)
            val availableSamples = (buffer.mDataByteSize / Float.SIZE_BYTES.toUInt()).toInt()
            val samples = if (format.isNonInterleaved) {
                frameCount.coerceAtMost(availableSamples)
            } else {
                (frameCount * bufferChannels).coerceAtMost(availableSamples)
            }
            for (sampleIndex in 0 until samples) {
                if (sampleOrdinal % stride == 0 && outputIndex < output.size) {
                    output[outputIndex++] = data[sampleIndex].coerceIn(-1f, 1f)
                }
                sampleOrdinal++
            }
        }
        if (outputIndex > 0) {
            analysisSink(if (outputIndex == output.size) output else output.copyOf(outputIndex), format.sampleRateHz)
        }
    }

    private fun processFloatBuffers(
        bufferList: AudioBufferList,
        frameCount: Int,
        format: IosEqualizerAudioFormat,
        processor: GraphicEqualizerProcessor,
    ) {
        if (!format.isFloat32) return
        val numberBuffers = bufferList.mNumberBuffers.toInt().coerceAtLeast(0)
        for (bufferIndex in 0 until numberBuffers) {
            val buffer = bufferList.mBuffers[bufferIndex]
            val data = buffer.mData?.reinterpret<FloatVar>() ?: continue
            val bufferChannels = buffer.mNumberChannels.toInt().coerceAtLeast(1)
            val availableSamples = (buffer.mDataByteSize / Float.SIZE_BYTES.toUInt()).toInt()
            if (format.isNonInterleaved) {
                val channel = bufferIndex.coerceIn(0, format.channelCount - 1)
                val samples = frameCount.coerceAtMost(availableSamples)
                for (sampleIndex in 0 until samples) {
                    data[sampleIndex] = processor.process(channel, data[sampleIndex])
                }
            } else {
                val samples = (frameCount * bufferChannels).coerceAtMost(availableSamples)
                for (sampleIndex in 0 until samples) {
                    val channel = sampleIndex % bufferChannels
                    data[sampleIndex] = processor.process(channel, data[sampleIndex])
                }
            }
        }
    }
}

private data class IosEqualizerAudioFormat(
    val sampleRateHz: Float,
    val channelCount: Int,
    val isFloat32: Boolean,
    val isNonInterleaved: Boolean,
) {
    companion object {
        @OptIn(ExperimentalForeignApi::class)
        fun from(description: AudioStreamBasicDescription): IosEqualizerAudioFormat? {
            if (description.mFormatID != kAudioFormatLinearPCM) return null
            val sampleRate = description.mSampleRate.toFloat()
            val channelCount = description.mChannelsPerFrame.toInt()
            if (sampleRate <= 0f || channelCount <= 0) return null
            val flags = description.mFormatFlags
            return IosEqualizerAudioFormat(
                sampleRateHz = sampleRate,
                channelCount = channelCount,
                isFloat32 = flags and kAudioFormatFlagIsFloat != 0u &&
                    description.mBitsPerChannel == Float.SIZE_BITS.toUInt(),
                isNonInterleaved = flags and kAudioFormatFlagIsNonInterleaved != 0u,
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createEqualizerProcessingTap(tap: IosEqualizerTap): MTAudioProcessingTapRef? =
    memScoped {
        val stableRef = StableRef.create(tap)
        val callbacks = alloc<MTAudioProcessingTapCallbacks>().apply {
            version = kMTAudioProcessingTapCallbacksVersion_0
            clientInfo = stableRef.asCPointer()
            init = staticCFunction(::iosEqualizerTapInit)
            finalize = staticCFunction(::iosEqualizerTapFinalize)
            prepare = staticCFunction(::iosEqualizerTapPrepare)
            unprepare = staticCFunction(::iosEqualizerTapUnprepare)
            process = staticCFunction(::iosEqualizerTapProcess)
        }
        val tapOut = alloc<MTAudioProcessingTapRefVar>()
        val status = MTAudioProcessingTapCreate(
            allocator = null,
            callbacks = callbacks.ptr,
            flags = kMTAudioProcessingTapCreationFlag_PostEffects,
            tapOut = tapOut.ptr,
        )
        if (status != 0 || tapOut.value == null) {
            stableRef.dispose()
            null
        } else {
            tapOut.value
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun iosEqualizerTapInit(
    tap: MTAudioProcessingTapRef?,
    clientInfo: COpaquePointer?,
    tapStorageOut: CPointer<COpaquePointerVar>?,
) {
    tapStorageOut?.pointed?.value = clientInfo
}

@OptIn(ExperimentalForeignApi::class)
private fun iosEqualizerTapFinalize(tap: MTAudioProcessingTapRef?) {
    val storage = MTAudioProcessingTapGetStorage(tap) ?: return
    storage.asStableRef<IosEqualizerTap>().dispose()
}

@OptIn(ExperimentalForeignApi::class)
private fun iosEqualizerTapPrepare(
    tap: MTAudioProcessingTapRef?,
    maxFrames: Long,
    processingFormat: CPointer<AudioStreamBasicDescription>?,
) {
    val equalizerTap = tap?.iosEqualizerTap() ?: return
    val format = processingFormat?.pointed ?: return
    equalizerTap.prepare(format)
}

@OptIn(ExperimentalForeignApi::class)
private fun iosEqualizerTapUnprepare(tap: MTAudioProcessingTapRef?) {
    tap?.iosEqualizerTap()?.unprepare()
}

@OptIn(ExperimentalForeignApi::class)
private fun iosEqualizerTapProcess(
    tap: MTAudioProcessingTapRef?,
    numberFrames: Long,
    flags: UInt,
    bufferListInOut: CPointer<AudioBufferList>?,
    numberFramesOut: CPointer<LongVar>?,
    flagsOut: CPointer<UIntVar>?,
) {
    val status = MTAudioProcessingTapGetSourceAudio(
        tap = tap,
        numberFrames = numberFrames,
        bufferListInOut = bufferListInOut,
        flagsOut = flagsOut,
        timeRangeOut = null,
        numberFramesOut = numberFramesOut,
    )
    if (status != 0 || bufferListInOut == null || numberFramesOut == null) return
    val frameCount = numberFramesOut.pointed.value.toInt().coerceAtLeast(0)
    tap?.iosEqualizerTap()?.process(bufferListInOut.pointed, frameCount)
}

@OptIn(ExperimentalForeignApi::class)
private fun MTAudioProcessingTapRef.iosEqualizerTap(): IosEqualizerTap? =
    MTAudioProcessingTapGetStorage(this)
        ?.asStableRef<IosEqualizerTap>()
        ?.get()
