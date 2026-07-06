package com.phoebe.app.player

import com.phoebe.app.data.PlexClient
import com.phoebe.app.domain.AudioAnalysisSource
import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.DesktopInlineRadioMapCoordinator
import com.phoebe.app.platform.PhoebeLog
import javazoom.spi.mpeg.sampled.file.MpegAudioFileReader
import javazoom.spi.vorbis.sampled.file.VorbisAudioFileReader
import javafx.application.Platform
import javafx.scene.media.AudioSpectrumListener
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import org.jflac.sound.spi.FlacAudioFileReader

actual fun createAudioPlayer(): AudioPlayer = DesktopAudioPlayer()

internal fun desktopPlaybackUriForTrack(track: Track): String {
    val localUri = track.localUri?.takeIf { it.isNotBlank() }
    val streamUri = track.streamUrl.takeIf { it.isNotBlank() }
    if (localUri == null) return streamUri.orEmpty()
    val localFile = desktopPlaybackLocalFile(localUri)
    return when {
        localFile?.isFile == true -> localUri
        !streamUri.isNullOrBlank() -> streamUri
        else -> localUri
    }
}

private fun desktopPlaybackLocalFile(uri: String): File? = runCatching {
    when {
        uri.startsWith("file:", ignoreCase = true) -> File(URI(uri))
        uri.startsWith("/") && !uri.contains("://") -> File(uri)
        else -> null
    }
}.getOrNull()

private fun Track.isDesktopLiveStream(): Boolean =
    id.startsWith("radio:") || radioNowPlayingSource != null

/**
 * Desktop playback uses JavaFX [MediaPlayer] for MP3/AAC-style sources and Java Sound for sampled-friendly
 * formats such as WAV/FLAC/OGG. Filesystem checks and remote buffering stay off the UI thread.
 */
class DesktopAudioPlayer(
    private val diagnostics: PlaybackDiagnostics = PlaybackDiagnostics.None,
) : SimpleAudioPlayer() {
    override val useProgressTicker: Boolean = false

    init {
        JavaFxRuntime.warmUp()
        if (!DesktopSandboxPlayback.isFlatpakSandbox()) {
            JavaFxRuntime.warmUpMedia()
        }
    }

    private var player: MediaPlayer? = null
    private var lastPlaybackUiSyncAtMs = 0L
    private var javaFxProgressProbeStop: AtomicBoolean? = null
    private var fadingOutPlayer: MediaPlayer? = null
    private var desktopCrossfadeGeneration = -1
    private var sampledClip: Clip? = null
    private var sampledStream: StreamingSampledPlayback? = null
    private var sampledStreamSource: SampledStreamSource? = null
    private var remoteSampledFile: File? = null
    private var prefetchedCrossfade: PrefetchedCrossfade? = null
    private var crossfadePrefetchFuture: CompletableFuture<PrefetchedCrossfade?>? = null
    private var fullyBufferedPlayback = false
    private var pendingManualSeekGeneration = -1
    private var pendingManualSeekPositionMs = 0L
    private var pendingManualSeekUntilMs = 0L
    private var javaFxStartupWatchdogStop: AtomicBoolean? = null
    private val httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val playbackExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Phoebe-desktop-playback").apply { isDaemon = true }
    }
    private val crossfadePrefetchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Phoebe-desktop-crossfade-prefetch").apply { isDaemon = true }
    }

    private data class PrefetchedCrossfade(
        val trackId: String,
        val file: File,
    )

    private data class SampledStreamSource(
        val uri: String?,
        val file: File?,
        val extension: String,
        val fullyBufferedDurationMs: Long?,
        val diagnosticLabel: String,
        val decoder: SampledStreamDecoder = SampledStreamDecoder.JavaSound,
    )

    private enum class SampledStreamDecoder {
        JavaSound,
        FfmpegPcm,
    }

    private class StreamingSampledPlayback(
        val line: SourceDataLine,
        val stream: AudioInputStream,
        val equalizerProcessor: GraphicEqualizerProcessor?,
        val fullyBufferedDurationMs: Long?,
        val startPositionMs: Long,
        val onStop: (() -> Unit)? = null,
    ) {
        val stopped = AtomicBoolean(false)
        @Volatile
        var writtenPcmBytes: Long = stream.format.pcmBytesForDurationMs(startPositionMs)
        @Volatile
        var lastWriteAtNs: Long = System.nanoTime()
        @Volatile
        var paused = false
        @Volatile
        var reportedEnergy = false
        @Volatile
        var reportedOutput = false
        private val startedAtNs = System.nanoTime()
        @Volatile
        private var pausedAtNs: Long? = null
        @Volatile
        private var pausedDurationNs: Long = 0L

        fun playbackPositionMs(nowNs: Long = System.nanoTime()): Long {
            val effectiveNowNs = pausedAtNs ?: nowNs
            val elapsedNs = (effectiveNowNs - startedAtNs - pausedDurationNs).coerceAtLeast(0L)
            return startPositionMs + elapsedNs / 1_000_000L
        }

        fun stop() {
            stopped.set(true)
            paused = false
            runCatching { line.stop() }
            runCatching { line.flush() }
            runCatching { line.close() }
            runCatching { stream.close() }
            runCatching { onStop?.invoke() }
        }

        fun pause() {
            if (!paused) {
                pausedAtNs = System.nanoTime()
                paused = true
            }
            runCatching { line.stop() }
        }

        fun resume() {
            if (paused) {
                val nowNs = System.nanoTime()
                pausedAtNs?.let { pausedSince ->
                    pausedDurationNs += (nowNs - pausedSince).coerceAtLeast(0L)
                }
                pausedAtNs = null
                paused = false
            }
            runCatching { line.start() }
        }
    }

    override fun playUri(uri: String) {
        playUri(
            uri = uri,
            preferredSampledExtension = null,
            preferredStreamingExtension = null,
            preferredJavaFxExtension = DesktopPlaybackStartupPolicy.javaFxPlaybackExtensionFromUri(uri),
            fallbackUri = null,
            downloadUri = null,
            preferJavaFxForLocalStreaming = false,
            isKnownLiveStream = isRemoteUri(uri) &&
                DesktopPlaybackStartupPolicy.javaFxPlaybackExtensionFromUri(uri) == null,
        )
    }

    override fun playTrack(track: Track) {
        playTrack(track, preferJavaFxForLocalStreaming = false)
    }

    private fun playTrack(
        track: Track,
        preferJavaFxForLocalStreaming: Boolean,
    ) {
        val localUri = track.localUri?.takeIf { it.isNotBlank() }
        val streamUri = DesktopSandboxPlayback.playbackStreamUrlForTrack(track).takeIf { it.isNotBlank() }
        val downloadUri = track.downloadUrl.takeIf { it.isNotBlank() }
        val uri = localUri ?: streamUri.orEmpty()
        playUri(
            uri = uri,
            preferredSampledExtension = sampledPlaybackExtensionFromTrack(track, uri),
            preferredStreamingExtension = streamingSampledExtensionFromTrack(track, uri),
            preferredJavaFxExtension = javaFxPlaybackExtensionFromTrack(track, uri),
            fallbackUri = streamUri?.takeIf { localUri != null },
            downloadUri = downloadUri,
            preferJavaFxForLocalStreaming = preferJavaFxForLocalStreaming,
            isKnownLiveStream = track.isDesktopLiveStream(),
        )
    }

    override fun playQueueOnPlatform(
        queue: List<Track>,
        startIndex: Int,
        track: Track,
        generation: Int,
    ) {
        playTrack(
            track = track,
            preferJavaFxForLocalStreaming = shouldPreferJavaFxForCrossfade(queue, startIndex, track),
        )
        scheduleCrossfadePrefetchAfterLoad(queue, startIndex, generation)
    }

    override fun skipToInQueueOnPlatform(
        queue: List<Track>,
        startIndex: Int,
        track: Track,
        generation: Int,
    ) {
        playTrack(
            track = track,
            preferJavaFxForLocalStreaming = shouldPreferJavaFxForCrossfade(queue, startIndex, track),
        )
        scheduleCrossfadePrefetchAfterLoad(queue, startIndex, generation)
    }

    override fun stopCurrentPlaybackImmediately() {
        stopJavaFxProgressProbe()
        runCatching { sampledClip?.stop() }
        runCatching { sampledStream?.stop() }
        JavaFxRuntime.runLater {
            runCatching { player?.pause() }
            runCatching { fadingOutPlayer?.pause() }
        }
    }

    private fun playUri(
        uri: String,
        preferredSampledExtension: String?,
        preferredStreamingExtension: String?,
        preferredJavaFxExtension: String?,
        fallbackUri: String?,
        downloadUri: String?,
        preferJavaFxForLocalStreaming: Boolean,
        isKnownLiveStream: Boolean,
    ) {
        if (uri.isBlank()) {
            finishPlaybackFailed()
            return
        }
        val generation = activePlayGeneration
        playbackExecutor.execute {
            if (!isPlayRequestCurrent(generation)) return@execute
            runCatching {
                disposeAllOnPlaybackThread()
                if (!isPlayRequestCurrent(generation)) return@execute
                var activeUri = uri
                var file = uriToLocalFile(activeUri)
                if ((file == null || !file.isFile) && !fallbackUri.isNullOrBlank() && fallbackUri != activeUri) {
                    PhoebeLog.d("DesktopAudioPlayer") { "offline file missing, falling back to stream" }
                    activeUri = fallbackUri
                    file = uriToLocalFile(activeUri)
                }
                if (file == null) {
                    resolveDesktopPlaylistStreamUri(activeUri)?.let { resolved ->
                        PhoebeLog.d("DesktopAudioPlayer") { "resolved playlist stream $activeUri -> $resolved" }
                        activeUri = resolved
                    }
                }
                val routePcmBeforeJavaFx = file == null &&
                    !DesktopSandboxPlayback.isFlatpakSandbox() &&
                    DesktopPlaybackStartupPolicy.shouldUsePcmStreamBeforeJavaFx(
                        uri = activeUri,
                        isKnownLiveStream = isKnownLiveStream,
                        preferredJavaFxExtension = preferredJavaFxExtension,
                    )
                if (routePcmBeforeJavaFx) {
                    if (isKnownLiveStream) {
                        DesktopInlineRadioMapCoordinator.beginLiveRadioStartup()
                    }
                    if (!isPlayRequestCurrent(generation)) return@execute
                    if (tryStartFfmpegPcmStream(activeUri, generation)) {
                        return@execute
                    }
                    if (isKnownLiveStream) {
                        if (tryLiveRadioSampledPlayback(
                                activeUri = activeUri,
                                preferredStreamingExtension = preferredStreamingExtension,
                                preferredSampledExtension = preferredSampledExtension,
                                generation = generation,
                            )
                        ) {
                            return@execute
                        }
                        PhoebeLog.d("DesktopAudioPlayer") {
                            "ffmpeg live stream unavailable for $activeUri; trying JavaFX and sampled fallbacks"
                        }
                    }
                }
                if (file == null) {
                    val streamingExtension = preferredStreamingExtension ?: streamingSampledExtensionFromUri(activeUri)
                    if (streamingExtension != null &&
                        !preferJavaFxForLocalStreaming &&
                        DesktopPlaybackStartupPolicy.shouldStreamRemoteSampledPlayback(
                            uri = activeUri,
                            preferredStreamingExtension = streamingExtension,
                            isFlatpakSandbox = DesktopSandboxPlayback.isFlatpakSandbox(),
                        ) &&
                        tryStartSampledStream(activeUri, streamingExtension, generation)
                    ) {
                        return@execute
                    }
                    if (!isPlayRequestCurrent(generation)) return@execute
                }
                if (file == null && shouldEagerlyBufferRemotePlayback(activeUri, preferredSampledExtension)) {
                    val extension = preferredSampledExtension
                        ?: sampledPlaybackExtensionFromUri(activeUri)
                        ?: "mp3"
                    val downloaded = downloadRemoteAudio(bufferedRemotePlaybackUri(activeUri, downloadUri), extension)
                    if (!isPlayRequestCurrent(generation)) {
                        runCatching { downloaded.delete() }
                        return@execute
                    }
                    remoteSampledFile = downloaded
                    file = downloaded
                }
                var sampledFileStreamAttempted = false
                if (file != null) {
                    val localStreamingExtension = streamingSampledExtensionFromUri(file.toURI().toString())
                    if (localStreamingExtension != null &&
                        !preferJavaFxForLocalStreaming &&
                        preferSampledStreamInSandbox(localStreamingExtension)
                    ) {
                        sampledFileStreamAttempted = true
                        if (tryStartSampledFileStream(file, localStreamingExtension, generation)) {
                            return@execute
                        }
                    }
                }
                if (file != null && !preferJavaFxForLocalStreaming && preferSampledPlayback(file)) {
                    val clip = openAndStartSampledClip(file)
                    if (clip != null) {
                        if (!isPlayRequestCurrent(generation)) {
                            runCatching { clip.stop(); clip.close() }
                            return@execute
                        }
                        sampledClip = clip
                        diagnostics.engineSelected(PlaybackEnginePath.SampledClip)
                        startSampledProgressProbe(clip, generation)
                        applyVolumesFromState()
                        updateBufferedPosition(trackDurationOrClipDuration(generation, clip), generation)
                        finishPlaybackReady(generation = generation)
                        return@execute
                    }
                }
                if (file != null) {
                    val localStreamingExtension = streamingSampledExtensionFromUri(file.toURI().toString())
                    if (localStreamingExtension != null &&
                        !preferJavaFxForLocalStreaming &&
                        (!preferSampledPlayback(file) || preferSampledStreamInSandbox(localStreamingExtension)) &&
                        !sampledFileStreamAttempted &&
                        tryStartSampledFileStream(file, localStreamingExtension, generation)
                    ) {
                        return@execute
                    }
                }
                val remoteExtension = preferredSampledExtension ?: sampledPlaybackExtensionFromUri(activeUri)
                if (file == null && !preferJavaFxForLocalStreaming && remoteExtension != null) {
                    val downloaded = downloadRemoteAudio(bufferedRemotePlaybackUri(activeUri, downloadUri), remoteExtension)
                    if (!isPlayRequestCurrent(generation)) {
                        runCatching { downloaded.delete() }
                        return@execute
                    }
                    remoteSampledFile = downloaded
                    val clip = openAndStartSampledClip(downloaded)
                    if (clip != null) {
                        if (!isPlayRequestCurrent(generation)) {
                            runCatching { clip.stop(); clip.close() }
                            runCatching { downloaded.delete() }
                            return@execute
                        }
                        sampledClip = clip
                        diagnostics.engineSelected(PlaybackEnginePath.SampledClip)
                        startSampledProgressProbe(clip, generation)
                        applyVolumesFromState()
                        updateBufferedPosition(trackDurationOrClipDuration(generation, clip), generation)
                        finishPlaybackReady(generation = generation)
                        return@execute
                    }
                    disposeSampled()
                }
                if (file == null &&
                    isRemoteUri(activeUri) &&
                    streamingSampledExtensionFromUri(activeUri) == null &&
                    DesktopPlaybackStartupPolicy.shouldStreamRemoteSampledPlayback(
                        uri = activeUri,
                        preferredStreamingExtension = null,
                        isFlatpakSandbox = DesktopSandboxPlayback.isFlatpakSandbox(),
                    ) &&
                    tryStartSampledStream(activeUri, extension = null, generation)
                ) {
                    return@execute
                }
                if (!isPlayRequestCurrent(generation)) return@execute
                val playbackUri = file?.toURI()?.toString() ?: activeUri
                fullyBufferedPlayback = file != null
                if (shouldAttemptJavaFxForPlayback(
                        activeUri = activeUri,
                        file = file,
                        preferredSampledExtension = preferredSampledExtension,
                        preferredStreamingExtension = preferredStreamingExtension,
                        preferredJavaFxExtension = preferredJavaFxExtension,
                        preferJavaFxForLocalStreaming = preferJavaFxForLocalStreaming,
                    )
                ) {
                    val javaFxScheduled = startJavaFxPlayback(playbackUri, generation) {
                        if (!continuePlaybackAfterJavaFxFailure(
                                activeUri = activeUri,
                                downloadUri = downloadUri,
                                preferredSampledExtension = preferredSampledExtension,
                                preferredStreamingExtension = preferredStreamingExtension,
                                file = file,
                                generation = generation,
                            )
                        ) {
                            finishPlaybackFailed(generation = generation)
                        }
                    }
                    if (!javaFxScheduled &&
                        !continuePlaybackAfterJavaFxFailure(
                            activeUri = activeUri,
                            downloadUri = downloadUri,
                            preferredSampledExtension = preferredSampledExtension,
                            preferredStreamingExtension = preferredStreamingExtension,
                            file = file,
                            generation = generation,
                        )
                    ) {
                        finishPlaybackFailed(generation = generation)
                    }
                    return@execute
                }
                if (!continuePlaybackAfterJavaFxFailure(
                        activeUri = activeUri,
                        downloadUri = downloadUri,
                        preferredSampledExtension = preferredSampledExtension,
                        preferredStreamingExtension = preferredStreamingExtension,
                        file = file,
                        generation = generation,
                    )
                ) {
                    diagnostics.playbackError(PlaybackEnginePath.JavaFxMediaPlayer, "Desktop playback failed to start")
                    finishPlaybackFailed(generation = generation)
                }
            }.onFailure { error ->
                if (!isPlayRequestCurrent(generation)) return@execute
                logPlaybackFailure(error)
                diagnostics.playbackError(PlaybackEnginePath.JavaFxMediaPlayer, error.message)
                finishPlaybackFailed(generation = generation)
            }
        }
    }

    override fun pause() {
        playbackExecutor.execute {
            val stream = sampledStream
            if (stream != null) {
                stream.pause()
                return@execute
            }
            val clip = sampledClip
            if (clip != null) {
                runCatching { clip.stop() }
            } else {
                JavaFxRuntime.runLater { player?.pause() }
            }
        }
    }

    override fun resume() {
        playbackExecutor.execute {
            val stream = sampledStream
            if (stream != null) {
                stream.resume()
                return@execute
            }
            val clip = sampledClip
            if (clip != null) {
                runCatching { clip.start() }
            } else {
                JavaFxRuntime.runLater { player?.play() }
            }
        }
    }

    override fun seek(positionMs: Long) {
        val generation = activePlayGeneration
        playbackExecutor.execute {
            if (!isPlayRequestCurrent(generation)) return@execute
            pendingManualSeekGeneration = generation
            pendingManualSeekPositionMs = positionMs.coerceAtLeast(0L)
            pendingManualSeekUntilMs = System.currentTimeMillis() + ManualSeekPlatformSettleMs
            val stream = sampledStream
            if (stream != null) {
                val source = sampledStreamSource
                if (source != null && restartSampledStreamAt(source, pendingManualSeekPositionMs, generation)) {
                    return@execute
                }
                runCatching { stream.line.flush() }
                return@execute
            }
            val clip = sampledClip
            if (clip != null) {
                runCatching {
                    val wasPlaying = clip.isActive
                    clip.stop()
                    clip.microsecondPosition = positionMs.coerceAtLeast(0L) * 1000L
                    if (wasPlaying) clip.start()
                }
            } else {
                JavaFxRuntime.runLater {
                    if (!isPlayRequestCurrent(generation)) return@runLater
                    player?.seek(javafx.util.Duration.millis(positionMs.toDouble()))
                }
            }
        }
    }

    override fun setOutputVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        playbackExecutor.execute {
            JavaFxRuntime.runLater { player?.volume = v.toDouble() }
            applySampledVolume(v)
        }
    }

    override fun applyEqualizer(profile: EqualizerProfile) {
        val normalized = profile.normalized()
        playbackExecutor.execute {
            JavaFxRuntime.runLater {
                applyJavaFxEqualizer(player, normalized)
                applyJavaFxEqualizer(fadingOutPlayer, normalized)
            }
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
        val uri = desktopPlaybackUriForTrack(track)
        if (uri.isBlank()) return false
        val localFile = uriToLocalFile(uri)
        val prefetchedFile = if (localFile == null) {
            prefetchedCrossfade
                ?.takeIf { it.trackId == track.id && it.file.exists() }
                ?.file
        } else {
            null
        }
        val file = localFile ?: prefetchedFile
        val outgoingSampledClip = sampledClip
        val outgoingSampledStream = sampledStream
        if (outgoingSampledClip != null || outgoingSampledStream != null) {
            file ?: return false
            if (!preferSampledPlayback(file) || file.extension.lowercase() in JavaFxPreferredLocalCrossfadeExtensions) {
                return false
            }
            return startSampledCrossfadeOnPlatform(
                outgoingClip = outgoingSampledClip,
                outgoingStream = outgoingSampledStream,
                incomingFile = file,
                incomingTempFile = prefetchedFile,
                queue = queue,
                targetIndex = targetIndex,
                durationMs = durationMs,
                baseVolume = baseVolume,
                generation = generation,
            )
        }
        val outgoing = player ?: return false
        if (file != null &&
            preferSampledPlayback(file) &&
            file.extension.lowercase() !in JavaFxPreferredLocalCrossfadeExtensions
        ) {
            return false
        }
        if (desktopCrossfadeGeneration == generation) return true
        desktopCrossfadeGeneration = generation
        val playbackUri = file?.toURI()?.toString() ?: uri
        JavaFxRuntime.runLater {
            if (!isPlayRequestCurrent(generation) || player !== outgoing) {
                if (desktopCrossfadeGeneration == generation) desktopCrossfadeGeneration = -1
                return@runLater
            }
            outgoing.setOnEndOfMedia {}
            runCatching {
                val media = Media(playbackUri)
                val incoming = MediaPlayer(media)
                applyJavaFxEqualizer(incoming, equalizerProfile)
                var committed = false
                var failed = false
                fun fallbackToNormalPlayback() {
                    if (failed) return
                    failed = true
                    if (desktopCrossfadeGeneration == generation) desktopCrossfadeGeneration = -1
                    runCatching { incoming.dispose() }
                    if (isPlayRequestCurrent(generation)) {
                        play(queue, targetIndex)
                    }
                }
                incoming.volume = 0.0
                incoming.setOnError {
                    PhoebeLog.d("DesktopAudioPlayer") {
                        "crossfade playback error: ${incoming.error?.message ?: incoming.error?.type}"
                    }
                    diagnostics.playbackError(PlaybackEnginePath.JavaFxMediaPlayer, incoming.error?.message)
                    fallbackToNormalPlayback()
                }
                media.setOnError {
                    PhoebeLog.d("DesktopAudioPlayer") { "crossfade media error: ${media.error?.message}" }
                    diagnostics.playbackError(PlaybackEnginePath.JavaFxMediaPlayer, media.error?.message)
                    fallbackToNormalPlayback()
                }
                incoming.setOnReady {
                    if (!isPlayRequestCurrent(generation) || player !== outgoing) {
                        fallbackToNormalPlayback()
                        return@setOnReady
                    }
                    incoming.play()
                }
                incoming.setOnPlaying {
                    if (committed || !isPlayRequestCurrent(generation) || player !== outgoing) {
                        runCatching { incoming.dispose() }
                        if (desktopCrossfadeGeneration == generation) desktopCrossfadeGeneration = -1
                        return@setOnPlaying
                    }
                    incoming.setOnPlaying(null)
                    fadingOutPlayer = outgoing
                    committed = true
                    if (prefetchedCrossfade?.trackId == track.id) {
                        prefetchedCrossfade = null
                    }
                    diagnostics.crossfadeStarted(
                        engine = PlaybackEnginePath.JavaFxMediaPlayer,
                        outgoingTrackId = state.value.currentTrack?.id,
                        incomingTrackId = track.id,
                        durationMs = durationMs,
                    )
                    runDesktopCrossfade(
                        outgoing = outgoing,
                        incoming = incoming,
                        incomingTempFile = prefetchedFile,
                        queue = queue,
                        targetIndex = targetIndex,
                        durationMs = durationMs,
                        baseVolume = baseVolume,
                        generation = generation,
                    )
                }
                Thread({
                    Thread.sleep(JavaFxCrossfadeReadyTimeoutMs)
                    JavaFxRuntime.runLater {
                        if (!committed && !failed && isPlayRequestCurrent(generation) && player === outgoing) {
                            fallbackToNormalPlayback()
                        }
                    }
                }, "Phoebe-desktop-crossfade-timeout").apply { isDaemon = true }.start()
            }.onFailure { error ->
                if (desktopCrossfadeGeneration == generation) desktopCrossfadeGeneration = -1
                logPlaybackFailure(error)
                diagnostics.playbackError(PlaybackEnginePath.JavaFxMediaPlayer, error.message)
            }
        }
        return true
    }

    private fun startSampledCrossfadeOnPlatform(
        outgoingClip: Clip?,
        outgoingStream: StreamingSampledPlayback?,
        incomingFile: File,
        incomingTempFile: File?,
        queue: List<Track>,
        targetIndex: Int,
        durationMs: Long,
        baseVolume: Float,
        generation: Int,
    ): Boolean {
        if (desktopCrossfadeGeneration == generation) return true
        desktopCrossfadeGeneration = generation
        playbackExecutor.execute {
            if (!isPlayRequestCurrent(generation) ||
                sampledClip !== outgoingClip ||
                sampledStream !== outgoingStream
            ) {
                if (desktopCrossfadeGeneration == generation) desktopCrossfadeGeneration = -1
                return@execute
            }
            val incoming = openAndStartSampledClip(incomingFile, initialVolume = 0f)
            if (incoming == null) {
                if (desktopCrossfadeGeneration == generation) desktopCrossfadeGeneration = -1
                if (isPlayRequestCurrent(generation)) play(queue, targetIndex)
                return@execute
            }
            if (prefetchedCrossfade?.trackId == queue[targetIndex].id) {
                prefetchedCrossfade = null
            }
            diagnostics.crossfadeStarted(
                engine = PlaybackEnginePath.SampledClip,
                outgoingTrackId = state.value.currentTrack?.id,
                incomingTrackId = queue[targetIndex].id,
                durationMs = durationMs,
            )
            runSampledCrossfade(
                outgoingClip = outgoingClip,
                outgoingStream = outgoingStream,
                incoming = incoming,
                incomingTempFile = incomingTempFile,
                queue = queue,
                targetIndex = targetIndex,
                durationMs = durationMs,
                baseVolume = baseVolume,
                generation = generation,
            )
        }
        return true
    }

    private fun runSampledCrossfade(
        outgoingClip: Clip?,
        outgoingStream: StreamingSampledPlayback?,
        incoming: Clip,
        incomingTempFile: File?,
        queue: List<Track>,
        targetIndex: Int,
        durationMs: Long,
        baseVolume: Float,
        generation: Int,
    ) {
        var committed = false
        try {
            val steps = 24
            val stepDelay = (durationMs / steps).coerceAtLeast(16L)
            repeat(steps) { index ->
                if (!isPlayRequestCurrent(generation)) {
                    return
                }
                val progress = (index + 1).toDouble() / steps.toDouble()
                val outgoingVolume = (baseVolume * (1.0 - progress)).toFloat().coerceIn(0f, 1f)
                val incomingVolume = (baseVolume * progress).toFloat().coerceIn(0f, 1f)
                diagnostics.crossfadeVolume(
                    engine = PlaybackEnginePath.SampledClip,
                    step = index + 1,
                    outgoingVolume = outgoingVolume,
                    incomingVolume = incomingVolume,
                )
                outgoingClip?.let { applyVolumeToClip(it, outgoingVolume) }
                outgoingStream?.let { applyVolumeToLine(it.line, outgoingVolume) }
                applyVolumeToClip(incoming, incomingVolume)
                Thread.sleep(stepDelay)
            }
            runCatching {
                outgoingClip?.stop()
                outgoingClip?.close()
            }
            runCatching { outgoingStream?.stop() }
            if (desktopCrossfadeGeneration == generation) desktopCrossfadeGeneration = -1
            if (isPlayRequestCurrent(generation)) {
                sampledClip = incoming
                sampledStream = null
                sampledStreamSource = null
                incomingTempFile?.let { temp ->
                    remoteSampledFile?.takeIf { it != temp }?.delete()
                    remoteSampledFile = temp
                }
                applyVolumeToClip(incoming, effectiveOutputVolume())
                adoptCrossfadeTarget(
                    queue = queue,
                    targetIndex = targetIndex,
                    positionMs = (incoming.microsecondPosition / 1_000L).coerceAtLeast(0L),
                    generation = generation,
                )
                diagnostics.crossfadeCommitted(
                    engine = PlaybackEnginePath.SampledClip,
                    incomingTrackId = queue[targetIndex].id,
                )
                startSampledProgressProbe(incoming, generation)
                prefetchCrossfadeCandidate(queue, targetIndex, generation)
                committed = true
            }
        } finally {
            if (!committed) {
                runCatching {
                    incoming.stop()
                    incoming.close()
                }
            }
        }
    }

    private fun runDesktopCrossfade(
        outgoing: MediaPlayer,
        incoming: MediaPlayer,
        incomingTempFile: File?,
        queue: List<Track>,
        targetIndex: Int,
        durationMs: Long,
        baseVolume: Float,
        generation: Int,
    ) {
        playbackExecutor.execute {
            val steps = 24
            val stepDelay = (durationMs / steps).coerceAtLeast(16L)
            repeat(steps) { index ->
                if (!isPlayRequestCurrent(generation)) return@execute
                val progress = (index + 1).toDouble() / steps.toDouble()
                val outgoingVolume = (baseVolume * (1.0 - progress)).toFloat().coerceIn(0f, 1f)
                val incomingVolume = (baseVolume * progress).toFloat().coerceIn(0f, 1f)
                diagnostics.crossfadeVolume(
                    engine = PlaybackEnginePath.JavaFxMediaPlayer,
                    step = index + 1,
                    outgoingVolume = outgoingVolume,
                    incomingVolume = incomingVolume,
                )
                JavaFxRuntime.runLater {
                    outgoing.volume = outgoingVolume.toDouble()
                    incoming.volume = incomingVolume.toDouble()
                }
                Thread.sleep(stepDelay)
            }
            JavaFxRuntime.runLater {
                runCatching {
                    outgoing.stop()
                    outgoing.dispose()
                }
                if (fadingOutPlayer === outgoing) fadingOutPlayer = null
                if (desktopCrossfadeGeneration == generation) desktopCrossfadeGeneration = -1
                if (isPlayRequestCurrent(generation)) {
                    player = incoming
                    incomingTempFile?.let { temp ->
                        remoteSampledFile?.takeIf { it != temp }?.delete()
                        remoteSampledFile = temp
                    }
                    incoming.volume = effectiveOutputVolume().toDouble().coerceIn(0.0, 1.0)
                    incoming.setOnEndOfMedia {
                        if (isPlayRequestCurrent(generation)) next()
                    }
                    incoming.bufferProgressTimeProperty().addListener { _, _, _ ->
                        if (player === incoming) syncJavaFxPlayback(incoming, generation, isBuffering = false)
                    }
                    incoming.currentTimeProperty().addListener { _, _, _ ->
                        if (player === incoming) syncJavaFxPlayback(incoming, generation, isBuffering = false)
                    }
                    adoptCrossfadeTarget(
                        queue = queue,
                        targetIndex = targetIndex,
                        positionMs = incoming.currentTime.toMillis().toLong().coerceAtLeast(0L),
                        generation = generation,
                    )
                    diagnostics.crossfadeCommitted(
                        engine = PlaybackEnginePath.JavaFxMediaPlayer,
                        incomingTrackId = queue[targetIndex].id,
                    )
                    syncJavaFxPlayback(incoming, generation, isBuffering = false)
                    prefetchCrossfadeCandidate(queue, targetIndex, generation)
                } else {
                    runCatching {
                        incoming.stop()
                        incoming.dispose()
                    }
                }
            }
        }
    }

    private fun applyVolumesFromState() {
        val v = effectiveOutputVolume()
        JavaFxRuntime.runLater { player?.volume = v.toDouble() }
        applySampledVolume(v)
    }

    private fun shouldPreferJavaFxForCrossfade(
        queue: List<Track>,
        currentIndex: Int,
        track: Track,
    ): Boolean {
        if (!isCrossfadeConfigured || queue.getOrNull(currentIndex + 1) == null) return false
        val playbackUri = desktopPlaybackUriForTrack(track)
        val extension = sampledPlaybackExtensionFromTrack(track, playbackUri)
            ?: streamingSampledExtensionFromTrack(track, playbackUri)
            ?: runCatching { URI(playbackUri).path }.getOrNull()
                ?.substringAfterLast('.', missingDelimiterValue = "")
        return extension?.lowercase() in JavaFxPreferredLocalCrossfadeExtensions
    }

    private fun applyJavaFxEqualizer(mediaPlayer: MediaPlayer?, profile: EqualizerProfile) {
        val player = mediaPlayer ?: return
        val normalized = profile.normalized()
        val equalizer = player.audioEqualizer
        val active = javaFxEqualizerActive(normalized)
        if (!active) {
            equalizer.isEnabled = false
            if (player.status != MediaPlayer.Status.PLAYING && player.status != MediaPlayer.Status.STALLED) {
                equalizer.bands.clear()
            }
            return
        }
        val platformBands = EqualizerProfile.bandsForCount(31)
        val desiredBands = platformBands.map { band ->
            javafx.scene.media.EqualizerBand(
                band.frequencyHz.toDouble(),
                equalizerBandwidthHz(band.frequencyHz, normalized.bandCount).toDouble(),
                gainForJavaFxBand(normalized, band.frequencyHz).toDouble(),
            )
        }
        val currentBands = equalizer.bands
        val sameBandLayout = currentBands.size == desiredBands.size &&
            currentBands.indices.all { index ->
                kotlin.math.abs(currentBands[index].centerFrequency - desiredBands[index].centerFrequency) < 0.01
            }
        if (!sameBandLayout) {
            if (player.status == MediaPlayer.Status.PLAYING || player.status == MediaPlayer.Status.STALLED) {
                currentBands.forEach { band ->
                    band.gain = gainForJavaFxBand(normalized, band.centerFrequency.toFloat()).toDouble()
                }
                equalizer.isEnabled = true
                return
            }
            equalizer.isEnabled = false
            currentBands.setAll(desiredBands)
        } else {
            desiredBands.forEachIndexed { index, desired ->
                currentBands[index].bandwidth = desired.bandwidth
                currentBands[index].gain = desired.gain
            }
        }
        equalizer.isEnabled = true
    }

    private fun javaFxEqualizerActive(profile: EqualizerProfile): Boolean =
        profile.normalized().let { normalized ->
            normalized.enabled && EqualizerProfile.bandsForCount(31)
                .any { band -> gainForJavaFxBand(normalized, band.frequencyHz) != 0f }
        }

    private fun gainForJavaFxBand(profile: EqualizerProfile, centerFrequencyHz: Float): Float {
        if (!profile.enabled) return 0f
        val center = centerFrequencyHz.coerceAtLeast(1f)
        var closestIndex = -1
        var closestDistance = Float.MAX_VALUE
        profile.bands.forEachIndexed { index, band ->
            val distance = kotlin.math.abs(kotlin.math.ln(center / band.frequencyHz))
            if (distance < closestDistance) {
                closestDistance = distance
                closestIndex = index
            }
        }
        return if (closestIndex >= 0 && closestDistance <= JavaFxEqualizerBandMatchTolerance) {
            profile.gainsDb.getOrElse(closestIndex) { 0f }
        } else {
            0f
        }
    }

    private fun equalizerBandwidthHz(centerFrequencyHz: Float, bandCount: Int): Float {
        val multiplier = when (bandCount) {
            31 -> 0.23f
            15 -> 0.42f
            5 -> 0.95f
            else -> 0.62f
        }
        return (centerFrequencyHz * multiplier).coerceAtLeast(20f)
    }

    private fun applySampledVolume(volume: Float) {
        sampledClip?.let { clip ->
            applyVolumeToClip(clip, volume)
        }
        sampledStream?.let { stream ->
            applyVolumeToLine(stream.line, volume)
        }
    }

    private fun applyVolumeToClip(clip: Clip, volume: Float) {
        applyVolumeControl(clip, volume)
    }

    private fun applyVolumeToLine(line: SourceDataLine, volume: Float) {
        applyVolumeControl(line, volume)
    }

    private fun applyVolumeControl(line: javax.sound.sampled.Line, volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        runCatching {
            val control = line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            control.value = sampledMasterGainDb(
                volume = v,
                minimum = control.minimum,
                maximum = control.maximum,
            )
        }.onFailure {
            runCatching {
                val control = line.getControl(FloatControl.Type.VOLUME) as FloatControl
                control.value = control.minimum + (control.maximum - control.minimum) * v
            }
        }
    }

    private fun uriToLocalFile(uri: String): File? =
        desktopPlaybackLocalFile(uri)?.takeIf { it.isFile }

    private fun preferSampledPlayback(file: File): Boolean =
        DesktopSandboxPlayback.sampledPlaybackExtensionFromSuffix(file.extension) != null

    private fun preferSampledStreamInSandbox(extension: String): Boolean =
        DesktopSandboxPlayback.isFlatpakSandbox() && extension.lowercase() == "mp3"

    private fun preferSampledFallbackAfterJavaFxFailure(file: File): Boolean =
        preferSampledPlayback(file)

    private fun shouldAttemptJavaFxForPlayback(
        activeUri: String,
        file: File?,
        preferredSampledExtension: String?,
        preferredStreamingExtension: String?,
        preferredJavaFxExtension: String?,
        preferJavaFxForLocalStreaming: Boolean,
    ): Boolean {
        if (JavaFxRuntime.hasFailed) return false
        if (file != null && preferJavaFxForLocalStreaming) return true
        if (file != null && preferSampledPlayback(file)) return false
        val extension = preferredSampledExtension
            ?: file?.extension?.takeIf { it.isNotBlank() }
            ?: sampledPlaybackExtensionFromUri(activeUri)
            ?: streamingSampledExtensionFromUri(activeUri)
            ?: preferredJavaFxExtension
        if (extension != null &&
            DesktopSandboxPlayback.sampledPlaybackExtensionFromSuffix(extension) != null
        ) {
            return false
        }
        if (file == null &&
            DesktopPlaybackStartupPolicy.shouldStreamRemoteSampledPlayback(
                uri = activeUri,
                preferredStreamingExtension = preferredStreamingExtension,
                isFlatpakSandbox = DesktopSandboxPlayback.isFlatpakSandbox(),
            )
        ) {
            return false
        }
        return true
    }

    private fun finishPlaybackReady(isPlaying: Boolean = true, generation: Int = activePlayGeneration) {
        DesktopInlineRadioMapCoordinator.endLiveRadioStartup()
        markPlaybackReady(isPlaying = isPlaying, generation = generation)
    }

    private fun finishPlaybackFailed(generation: Int = activePlayGeneration, message: String? = null) {
        DesktopInlineRadioMapCoordinator.endLiveRadioStartup()
        markPlaybackFailed(generation = generation, message = message)
    }

    private fun tryLiveRadioSampledPlayback(
        activeUri: String,
        preferredStreamingExtension: String?,
        preferredSampledExtension: String?,
        generation: Int,
    ): Boolean {
        val extensions = linkedSetOf<String?>()
        preferredStreamingExtension?.let { extensions.add(it) }
        streamingSampledExtensionFromUri(activeUri)?.let { extensions.add(it) }
        preferredSampledExtension?.let { extensions.add(it) }
        extensions.add(null)
        for (extension in extensions) {
            if (extension != null &&
                !DesktopPlaybackStartupPolicy.shouldStreamRemoteSampledPlayback(
                    uri = activeUri,
                    preferredStreamingExtension = extension,
                    isFlatpakSandbox = DesktopSandboxPlayback.isFlatpakSandbox(),
                ) &&
                DesktopSandboxPlayback.streamingSampledExtensionFromSuffix(extension) == null &&
                DesktopSandboxPlayback.sampledPlaybackExtensionFromSuffix(extension) == null
            ) {
                continue
            }
            if (tryStartSampledStream(activeUri, extension, generation)) {
                return true
            }
        }
        return false
    }

    private fun continuePlaybackAfterJavaFxFailure(
        activeUri: String,
        downloadUri: String?,
        preferredSampledExtension: String?,
        preferredStreamingExtension: String?,
        file: File?,
        generation: Int,
    ): Boolean {
        if (!isPlayRequestCurrent(generation)) return true
        if (file != null && trySampledFallbackAfterJavaFxFailure(file, generation)) {
            return true
        }
        if (file == null &&
            DesktopPlaybackStartupPolicy.shouldStreamRemoteSampledPlayback(
                uri = activeUri,
                preferredStreamingExtension = preferredStreamingExtension,
                isFlatpakSandbox = DesktopSandboxPlayback.isFlatpakSandbox(),
            ) &&
            tryStartSampledStream(
                activeUri,
                preferredStreamingExtension ?: streamingSampledExtensionFromUri(activeUri),
                generation,
            )
        ) {
            return true
        }
        if (file == null && tryStartFfmpegPcmStream(activeUri, generation)) {
            return true
        }
        if (file == null &&
            tryBufferedRemotePlaybackFallback(activeUri, downloadUri, preferredSampledExtension, generation)
        ) {
            return true
        }
        return false
    }

    private fun cancelJavaFxStartupWatchdog() {
        javaFxStartupWatchdogStop?.set(true)
        javaFxStartupWatchdogStop = null
    }

    private fun scheduleJavaFxStartupWatchdog(
        stop: AtomicBoolean,
        generation: Int,
        mediaReady: AtomicBoolean,
        onStartupFailed: () -> Unit,
    ) {
        CompletableFuture.delayedExecutor(JavaFxMediaReadyTimeoutMs, TimeUnit.MILLISECONDS)
            .execute {
                if (stop.get()) return@execute
                if (mediaReady.get() || !isPlayRequestCurrent(generation)) return@execute
                stop.set(true)
                playbackExecutor.execute {
                    if (!isPlayRequestCurrent(generation)) return@execute
                    PhoebeLog.d("DesktopAudioPlayer") { "JavaFX media did not become ready in ${JavaFxMediaReadyTimeoutMs}ms" }
                    disposeJavaFxBlocking()
                    onStartupFailed()
                }
            }
    }

    private fun startJavaFxPlayback(
        uri: String,
        generation: Int,
        onStartupFailed: () -> Unit,
    ): Boolean {
        if (JavaFxRuntime.hasFailed) return false
        diagnostics.playbackStartupEvent(PlaybackEnginePath.JavaFxMediaPlayer, "scheduled")
        cancelJavaFxStartupWatchdog()
        val watchdogStop = AtomicBoolean(false)
        javaFxStartupWatchdogStop = watchdogStop
        val mediaReady = AtomicBoolean(false)
        val playingStarted = AtomicBoolean(false)
        val playingWatchdogStop = AtomicBoolean(false)
        val startupFailed = AtomicBoolean(false)
        fun failStartup() {
            if (!startupFailed.compareAndSet(false, true)) return
            cancelJavaFxStartupWatchdog()
            playingWatchdogStop.set(true)
            playbackExecutor.execute {
                if (!isPlayRequestCurrent(generation)) return@execute
                onStartupFailed()
            }
        }
        fun schedulePlayingWatchdog() {
            CompletableFuture.delayedExecutor(JavaFxMediaPlayingTimeoutMs, TimeUnit.MILLISECONDS).execute {
                if (playingWatchdogStop.get() || playingStarted.get()) return@execute
                if (!isPlayRequestCurrent(generation)) return@execute
                playingWatchdogStop.set(true)
                playbackExecutor.execute {
                    if (playingStarted.get() || !isPlayRequestCurrent(generation)) return@execute
                    PhoebeLog.d("DesktopAudioPlayer") {
                        "JavaFX media ready but never started playing within ${JavaFxMediaPlayingTimeoutMs}ms"
                    }
                    failStartup()
                }
            }
        }
        scheduleJavaFxStartupWatchdog(
            stop = watchdogStop,
            generation = generation,
            mediaReady = mediaReady,
            onStartupFailed = { failStartup() },
        )
        JavaFxRuntime.runLater(
            block = {
                runCatching {
                    diagnostics.playbackStartupEvent(PlaybackEnginePath.JavaFxMediaPlayer, "javafx-thread")
                    diagnostics.engineSelected(PlaybackEnginePath.JavaFxMediaPlayer)
                    val media = Media(uri)
                    diagnostics.playbackStartupEvent(PlaybackEnginePath.JavaFxMediaPlayer, "media-created")
                    val mediaPlayer = MediaPlayer(media)
                    diagnostics.playbackStartupEvent(PlaybackEnginePath.JavaFxMediaPlayer, "player-created")
                    mediaPlayer.volume = effectiveOutputVolume().toDouble().coerceIn(0.0, 1.0)
                    applyJavaFxEqualizer(mediaPlayer, equalizerProfile)
                    mediaPlayer.audioSpectrumInterval = 0.05
                    mediaPlayer.audioSpectrumNumBands = 128
                    mediaPlayer.audioSpectrumThreshold = -80
                    mediaPlayer.audioSpectrumListener = AudioSpectrumListener { _, _, magnitudes, _ ->
                        publishAudioAnalysisMagnitudesDb(magnitudes, AudioAnalysisSource.Spectrum)
                        val maxMagnitude = magnitudes.maxOrNull() ?: return@AudioSpectrumListener
                        val rms = Math.pow(10.0, maxMagnitude.toDouble() / 20.0)
                        if (rms.isFinite() && rms > 0.0) {
                            diagnostics.decodedAudioEnergy(PlaybackEnginePath.JavaFxMediaPlayer, rms)
                        }
                    }
                    mediaPlayer.setOnError {
                        PhoebeLog.d("DesktopAudioPlayer") {
                            "playback error: ${mediaPlayer.error?.message ?: mediaPlayer.error?.type}"
                        }
                        diagnostics.playbackError(PlaybackEnginePath.JavaFxMediaPlayer, mediaPlayer.error?.message)
                        failStartup()
                    }
                    mediaPlayer.setOnEndOfMedia {
                        if (isPlayRequestCurrent(generation)) {
                            next()
                        }
                    }
                    media.setOnError {
                        PhoebeLog.d("DesktopAudioPlayer") { "media error: ${media.error?.message}" }
                        diagnostics.playbackError(PlaybackEnginePath.JavaFxMediaPlayer, media.error?.message)
                        failStartup()
                    }
                    mediaPlayer.setOnPlaying {
                        if (!isPlayRequestCurrent(generation)) return@setOnPlaying
                        diagnostics.playbackStartupEvent(PlaybackEnginePath.JavaFxMediaPlayer, "playing")
                        playingStarted.set(true)
                        playingWatchdogStop.set(true)
                        cancelJavaFxStartupWatchdog()
                        syncJavaFxPlayback(mediaPlayer, generation, isBuffering = false)
                        playbackExecutor.execute {
                            if (!isPlayRequestCurrent(generation)) return@execute
                            applyVolumesFromState()
                            finishPlaybackReady(generation = generation)
                        }
                    }
                    mediaPlayer.setOnStalled {
                        syncJavaFxPlayback(mediaPlayer, generation, isBuffering = true)
                    }
                    mediaPlayer.setOnReady {
                        if (!isPlayRequestCurrent(generation)) return@setOnReady
                        diagnostics.playbackStartupEvent(PlaybackEnginePath.JavaFxMediaPlayer, "ready")
                        mediaReady.set(true)
                        cancelJavaFxStartupWatchdog()
                        schedulePlayingWatchdog()
                        syncJavaFxPlayback(mediaPlayer, generation, isBuffering = false)
                        mediaPlayer.bufferProgressTimeProperty().addListener { _, _, _ ->
                            syncJavaFxPlayback(mediaPlayer, generation, isBuffering = false)
                        }
                        mediaPlayer.currentTimeProperty().addListener { _, _, _ ->
                            syncJavaFxPlayback(mediaPlayer, generation, isBuffering = false)
                        }
                        if (playWhenReady && isPlayRequestCurrent(generation)) {
                            mediaPlayer.play()
                        }
                    }
                    player = mediaPlayer
                    startJavaFxProgressProbe(mediaPlayer, generation)
                    if (playWhenReady && isPlayRequestCurrent(generation)) {
                        mediaPlayer.play()
                    }
                }.onFailure { error ->
                    logPlaybackFailure(error)
                    diagnostics.playbackError(PlaybackEnginePath.JavaFxMediaPlayer, error.message)
                    failStartup()
                }
            },
            onError = { error ->
                logPlaybackFailure(error)
                diagnostics.playbackError(PlaybackEnginePath.JavaFxMediaPlayer, error.message)
                failStartup()
            },
        )
        return true
    }

    private fun trySampledFallbackAfterJavaFxFailure(file: File, generation: Int): Boolean {
        if (!preferSampledFallbackAfterJavaFxFailure(file)) return false
        if (!isPlayRequestCurrent(generation)) return true
        disposeJavaFxBlocking()
        val clip = openAndStartSampledClip(file) ?: run {
            disposeSampled()
            return false
        }
        if (!isPlayRequestCurrent(generation)) {
            runCatching {
                clip.stop()
                clip.close()
            }
            return true
        }
        sampledClip = clip
        diagnostics.engineSelected(PlaybackEnginePath.SampledClip)
        startSampledProgressProbe(clip, generation)
        applyVolumesFromState()
        updateBufferedPosition(trackDurationOrClipDuration(generation, clip), generation)
        finishPlaybackReady(generation = generation)
        return true
    }

    private fun tryBufferedRemotePlaybackFallback(
        uri: String,
        downloadUri: String?,
        preferredSampledExtension: String?,
        generation: Int,
    ): Boolean {
        if (!isRemoteUri(uri) || !isPlayRequestCurrent(generation)) return false
        if (state.value.durationMs <= 0L) return false
        disposeJavaFxBlocking()
        val extension = preferredSampledExtension
            ?: sampledPlaybackExtensionFromUri(uri)
            ?: "mp3"
        val downloaded = runCatching { downloadRemoteAudio(bufferedRemotePlaybackUri(uri, downloadUri), extension) }
            .getOrElse { error ->
                logPlaybackFailure(error)
                diagnostics.playbackError(PlaybackEnginePath.JavaFxMediaPlayer, error.message)
                return false
            }
        if (!isPlayRequestCurrent(generation)) {
            runCatching { downloaded.delete() }
            return true
        }
        remoteSampledFile = downloaded
        if (preferSampledPlayback(downloaded)) {
            val clip = openAndStartSampledClip(downloaded)
            if (clip != null) {
                if (!isPlayRequestCurrent(generation)) {
                    runCatching {
                        clip.stop()
                        clip.close()
                    }
                    return true
                }
                sampledClip = clip
                diagnostics.engineSelected(PlaybackEnginePath.SampledClip)
                startSampledProgressProbe(clip, generation)
                applyVolumesFromState()
                updateBufferedPosition(trackDurationOrClipDuration(generation, clip), generation)
                finishPlaybackReady(generation = generation)
                return true
            }
            disposeSampled()
            return false
        }
        fullyBufferedPlayback = true
        return startJavaFxPlayback(downloaded.toURI().toString(), generation) {
            if (!trySampledFallbackAfterJavaFxFailure(downloaded, generation)) {
                finishPlaybackFailed(generation = generation)
            }
        }
    }

    private fun tryStartSampledStream(uri: String, extension: String?, generation: Int): Boolean {
        if (!isRemoteUri(uri) || !isPlayRequestCurrent(generation)) return false
        val response = runCatching { openRemoteAudioStream(uri) }
            .getOrElse { error ->
                logPlaybackFailure(error)
                diagnostics.playbackError(PlaybackEnginePath.SampledStream, error.message)
                return false
            }
        val resolvedExtension = extension ?: sampledStreamingExtensionFromContentType(response.contentType)
        if (resolvedExtension == null) {
            runCatching { response.inputStream.close() }
            return false
        }
        return tryStartSampledStreamFromInput(
            inputStream = response.inputStream,
            extension = resolvedExtension,
            generation = generation,
            fullyBufferedDurationMs = null,
            source = SampledStreamSource(
                uri = uri,
                file = null,
                extension = resolvedExtension,
                fullyBufferedDurationMs = null,
                diagnosticLabel = "remote-$resolvedExtension",
            ),
        )
    }

    private fun tryStartSampledFileStream(file: File, extension: String, generation: Int): Boolean {
        if (!isPlayRequestCurrent(generation)) return false
        val inputStream = runCatching { file.inputStream() }
            .getOrElse { error ->
                logPlaybackFailure(error)
                diagnostics.playbackError(PlaybackEnginePath.SampledStream, error.message)
                return false
            }
        return tryStartSampledStreamFromInput(
            inputStream = inputStream,
            extension = extension,
            generation = generation,
            fullyBufferedDurationMs = state.value.durationMs.takeIf { it > 0L },
            source = SampledStreamSource(
                uri = null,
                file = file,
                extension = extension,
                fullyBufferedDurationMs = state.value.durationMs.takeIf { it > 0L },
                diagnosticLabel = "file-$extension",
            ),
        )
    }

    private fun tryStartFfmpegPcmStream(
        uri: String,
        generation: Int,
        initialPositionMs: Long = 0L,
        fullyBufferedDurationMs: Long? = state.value.durationMs.takeIf { it > 0L },
    ): Boolean {
        if (!isRemoteUri(uri) || !isPlayRequestCurrent(generation)) return false
        val ffmpeg = findFfmpegExecutable() ?: return false
        val startPositionMs = initialPositionMs.coerceAtLeast(0L).let { position ->
            fullyBufferedDurationMs?.takeIf { it > 0L }?.let { duration -> position.coerceAtMost(duration) }
                ?: position
        }
        val command = mutableListOf(
            ffmpeg,
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-reconnect",
            "1",
            "-reconnect_streamed",
            "1",
            "-reconnect_delay_max",
            "5",
            "-user_agent",
            "Phoebe/0.1.0 (https://github.com/phoebe)",
        )
        if (startPositionMs > 0L) {
            command += "-ss"
            command += (startPositionMs.toDouble() / 1_000.0).toString()
        }
        command += listOf(
            "-i",
            uri,
            "-vn",
            "-f",
            "s16le",
            "-acodec",
            "pcm_s16le",
            "-ar",
            FfmpegPcmSampleRateHz.toString(),
            "-ac",
            FfmpegPcmChannels.toString(),
            "-flush_packets",
            "1",
            "pipe:1",
        )
        val process = runCatching {
            ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.getOrElse { error ->
            PhoebeLog.d("DesktopAudioPlayer") { "ffmpeg fallback unavailable: ${error.message}" }
            return false
        }
        val format = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            FfmpegPcmSampleRateHz.toFloat(),
            16,
            FfmpegPcmChannels,
            FfmpegPcmChannels * 2,
            FfmpegPcmSampleRateHz.toFloat(),
            false,
        )
        val stream = AudioInputStream(
            BufferedInputStream(process.inputStream, RemoteAudioProbeBufferBytes),
            format,
            AudioSystem.NOT_SPECIFIED.toLong(),
        )
        val started = tryStartPcmSampledStream(
            pcmStream = stream,
            generation = generation,
            fullyBufferedDurationMs = fullyBufferedDurationMs,
            initialPositionMs = startPositionMs,
            skipInputToInitialPosition = false,
            source = SampledStreamSource(
                uri = uri,
                file = null,
                extension = FfmpegPcmStreamExtension,
                fullyBufferedDurationMs = fullyBufferedDurationMs,
                diagnosticLabel = "ffmpeg-pcm",
                decoder = SampledStreamDecoder.FfmpegPcm,
            ),
            onStop = { process.destroyForcibly() },
            diagnosticLabel = "ffmpeg-pcm",
        )
        if (!started) {
            runCatching { stream.close() }
            runCatching { process.destroyForcibly() }
        } else {
            PhoebeLog.d("DesktopAudioPlayer") { "ffmpeg pcm fallback started for $uri" }
        }
        return started
    }

    private fun findFfmpegExecutable(): String? {
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("windows")
        val executableNames = if (isWindows) listOf("ffmpeg.exe", "ffmpeg") else listOf("ffmpeg")
        val pathCandidates = System.getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .flatMap { directory -> executableNames.map { name -> File(directory, name) } }
        return (pathCandidates + FfmpegFallbackPaths.map(::File))
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    private fun resolveDesktopPlaylistStreamUri(uri: String): String? {
        if (!isRemoteUri(uri) || !isLikelyDesktopPlaylistUri(uri)) return null
        val request = HttpRequest.newBuilder(URI(uri))
            .GET()
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "Phoebe/0.1.0 (https://github.com/phoebe)")
            .build()
        val response = runCatching { httpClient.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
            ?: return null
        if (response.statusCode() !in 200..299) return null
        return parseDesktopPlaylistStreamUri(response.body(), uri)
    }

    private fun tryStartSampledStreamFromInput(
        inputStream: InputStream,
        extension: String,
        generation: Int,
        fullyBufferedDurationMs: Long?,
        source: SampledStreamSource? = null,
        initialPositionMs: Long = 0L,
    ): Boolean {
        val pcmStream = runCatching {
            val raw = openStreamingRawAudioInputStream(inputStream, extension)
            preparePcmForSourceLine(raw)
        }.getOrElse { error ->
            runCatching { inputStream.close() }
            logPlaybackFailure(error)
            diagnostics.playbackError(PlaybackEnginePath.SampledStream, error.message)
            return false
        }
        return tryStartPcmSampledStream(
            pcmStream = pcmStream,
            generation = generation,
            fullyBufferedDurationMs = fullyBufferedDurationMs,
            initialPositionMs = initialPositionMs,
            source = source,
            diagnosticLabel = source?.diagnosticLabel ?: "pcm",
        )
    }

    private fun tryStartPcmSampledStream(
        pcmStream: AudioInputStream,
        generation: Int,
        fullyBufferedDurationMs: Long?,
        initialPositionMs: Long = 0L,
        skipInputToInitialPosition: Boolean = true,
        source: SampledStreamSource? = null,
        onStop: (() -> Unit)? = null,
        diagnosticLabel: String = "pcm",
    ): Boolean {
        val startPositionMs = initialPositionMs.coerceAtLeast(0L).let { position ->
            fullyBufferedDurationMs?.takeIf { it > 0L }?.let { duration -> position.coerceAtMost(duration) }
                ?: position
        }
        if (startPositionMs > 0L &&
            skipInputToInitialPosition &&
            !skipPcmStreamToPosition(pcmStream, startPositionMs)
        ) {
            runCatching { pcmStream.close() }
            runCatching { onStop?.invoke() }
            return false
        }
        val format = pcmStream.format
        val bufferSize = sourceLineBufferSize(format)
        val line = runCatching {
            val info = DataLine.Info(SourceDataLine::class.java, format)
            (AudioSystem.getLine(info) as SourceDataLine).also { it.open(format, bufferSize) }
        }.getOrElse { error ->
            runCatching { pcmStream.close() }
            runCatching { onStop?.invoke() }
            logPlaybackFailure(error)
            diagnostics.playbackError(PlaybackEnginePath.SampledStream, error.message)
            return false
        }
        val firstBuffer = ByteArray(StreamingPcmBufferBytes)
        val firstRead = runCatching { pcmStream.read(firstBuffer, 0, firstBuffer.size) }
            .getOrElse { error ->
                runCatching { line.close() }
                runCatching { pcmStream.close() }
                runCatching { onStop?.invoke() }
                logPlaybackFailure(error)
                diagnostics.playbackError(PlaybackEnginePath.SampledStream, error.message)
                return false
            }
        if (firstRead < 0) {
            runCatching { line.close() }
            runCatching { pcmStream.close() }
            runCatching { onStop?.invoke() }
            return false
        }
        if (!isPlayRequestCurrent(generation)) {
            runCatching { line.close() }
            runCatching { pcmStream.close() }
            runCatching { onStop?.invoke() }
            return true
        }
        val playback = StreamingSampledPlayback(
            line = line,
            stream = pcmStream,
            equalizerProcessor = streamingEqualizerProcessor(format),
            fullyBufferedDurationMs = fullyBufferedDurationMs,
            startPositionMs = startPositionMs,
            onStop = onStop,
        )
        sampledStream = playback
        sampledStreamSource = source
        PhoebeLog.d("DesktopAudioPlayer") { "sampled stream playback source=$diagnosticLabel format=$format" }
        diagnostics.engineSelected(PlaybackEnginePath.SampledStream)
        applyVolumeToLine(line, effectiveOutputVolume())
        line.start()
        if (firstRead > 0) {
            writeSampledStreamBuffer(
                playback = playback,
                buffer = firstBuffer,
                length = firstRead,
                generation = generation,
            )
        }
        syncSampledStreamPlayback(playback, generation)
        startSampledStreamPump(playback, bufferSize, generation)
        return true
    }

    private fun restartSampledStreamAt(
        source: SampledStreamSource,
        positionMs: Long,
        generation: Int,
    ): Boolean {
        if (!isPlayRequestCurrent(generation)) return true
        if (source.decoder == SampledStreamDecoder.FfmpegPcm) {
            val uri = source.uri ?: return false
            val previous = sampledStream
            runCatching { previous?.stop() }
            if (sampledStream === previous) {
                sampledStream = null
            }
            return tryStartFfmpegPcmStream(
                uri = uri,
                generation = generation,
                initialPositionMs = positionMs,
                fullyBufferedDurationMs = source.fullyBufferedDurationMs,
            )
        }
        val inputStream = runCatching {
            source.file?.inputStream()
                ?: source.uri?.let { openRemoteAudioStream(it).inputStream }
                ?: error("Missing sampled stream source")
        }.getOrElse { error ->
            logPlaybackFailure(error)
            diagnostics.playbackError(PlaybackEnginePath.SampledStream, error.message)
            return false
        }
        val pcmStream = runCatching {
            val raw = openStreamingRawAudioInputStream(inputStream, source.extension)
            preparePcmForSourceLine(raw)
        }.getOrElse { error ->
            runCatching { inputStream.close() }
            logPlaybackFailure(error)
            diagnostics.playbackError(PlaybackEnginePath.SampledStream, error.message)
            return false
        }
        val previous = sampledStream
        runCatching { previous?.stop() }
        if (sampledStream === previous) {
            sampledStream = null
        }
        return tryStartPcmSampledStream(
            pcmStream = pcmStream,
            generation = generation,
            fullyBufferedDurationMs = source.fullyBufferedDurationMs,
            initialPositionMs = positionMs,
            source = source,
            diagnosticLabel = source.diagnosticLabel,
        )
    }

    private fun skipPcmStreamToPosition(stream: AudioInputStream, positionMs: Long): Boolean = runCatching {
        var remainingBytes = stream.format.pcmBytesForDurationMs(positionMs)
        val buffer = ByteArray(StreamingPcmBufferBytes)
        while (remainingBytes > 0L) {
            val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remainingBytes).toInt())
            if (read < 0) return false
            if (read == 0) continue
            remainingBytes -= read.toLong()
        }
        true
    }.getOrDefault(false)

    private fun openRemoteAudioStream(uri: String): RemoteAudioStream {
        val request = HttpRequest.newBuilder(URI(uri))
            .GET()
            .timeout(Duration.ofSeconds(45))
            .header("User-Agent", "Phoebe/0.1.0 (https://github.com/phoebe)")
            .header("X-Plex-Client-Identifier", PlexClient.ClientIdentifier)
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            response.body().close()
            error("Plex stream request failed (${response.statusCode()})")
        }
        val contentType = response.headers().firstValue("content-type").orElse("").lowercase()
        if (contentType.startsWith("text/") ||
            contentType.contains("html") ||
            contentType.contains("json") ||
            contentType.contains("xml")
        ) {
            response.body().close()
            error("Plex stream returned $contentType instead of audio")
        }
        return RemoteAudioStream(response.body(), contentType)
    }

    private data class RemoteAudioStream(
        val inputStream: InputStream,
        val contentType: String,
    )

    private fun sampledStreamingExtensionFromContentType(contentType: String): String? {
        val normalized = contentType.substringBefore(';').trim().lowercase()
        return when {
            normalized == "audio/mpeg" || normalized == "audio/mp3" || normalized == "audio/x-mpeg" -> "mp3"
            normalized == "audio/flac" || normalized == "audio/x-flac" -> "flac"
            normalized == "audio/ogg" || normalized == "application/ogg" -> "ogg"
            normalized == "audio/wav" || normalized == "audio/x-wav" -> "wav"
            normalized == "audio/aiff" || normalized == "audio/x-aiff" -> "aiff"
            else -> null
        }
    }

    private fun openStreamingRawAudioInputStream(input: InputStream, extension: String): AudioInputStream {
        val buffered = BufferedInputStream(input, RemoteAudioProbeBufferBytes)
        return when (extension.lowercase()) {
            "mp3", "mpeg", "mpga" -> MpegAudioFileReader().getAudioInputStream(buffered)
            "flac" -> FlacAudioFileReader().getAudioInputStream(buffered)
            "ogg" -> VorbisAudioFileReader().getAudioInputStream(buffered)
            "opus" -> runCatching { VorbisAudioFileReader().getAudioInputStream(buffered) }
                .getOrElse { AudioSystem.getAudioInputStream(buffered) }
            else -> AudioSystem.getAudioInputStream(buffered)
        }
    }

    private fun preparePcmForSourceLine(stream: AudioInputStream): AudioInputStream {
        val format = stream.format
        val enc = format.encoding
        val pcmReady = (enc == AudioFormat.Encoding.PCM_SIGNED || enc == AudioFormat.Encoding.PCM_UNSIGNED) &&
            format.frameSize > 0
        val channels = format.channels.takeIf { it > 0 } ?: 2
        val sampleRate = format.sampleRate.takeIf { it > 0f && !it.isNaN() } ?: 44100f
        val target = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            sampleRate,
            16,
            channels,
            channels * 2,
            sampleRate,
            false,
        )
        return when {
            AudioSystem.isConversionSupported(target, format) -> AudioSystem.getAudioInputStream(target, stream)
            pcmReady -> stream
            else -> error("Unsupported streaming audio conversion: $format")
        }
    }

    private fun sourceLineBufferSize(format: AudioFormat): Int {
        val frameSize = format.frameSize.takeIf { it > 0 } ?: return StreamingPcmBufferBytes
        val sampleRate = format.sampleRate.takeIf { it > 0f && !it.isNaN() } ?: return StreamingPcmBufferBytes
        val frames = (sampleRate * 1.5f).toInt().coerceAtLeast(1)
        return (frames * frameSize)
            .coerceAtLeast(StreamingPcmBufferBytes)
            .coerceAtMost(MaxStreamingLineBufferBytes)
    }

    private fun startSampledStreamPump(
        playback: StreamingSampledPlayback,
        bufferSize: Int,
        generation: Int,
    ) {
        Thread({
            val buffer = ByteArray(StreamingPcmBufferBytes)
            try {
                while (isPlayRequestCurrent(generation) && sampledStream === playback && !playback.stopped.get()) {
                    waitIfSampledStreamPaused(playback)
                    val read = playback.stream.read(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) continue
                    writeSampledStreamBuffer(playback, buffer, read, generation)
                    syncSampledStreamPlayback(playback, generation)
                }
                if (isPlayRequestCurrent(generation) && sampledStream === playback && !playback.stopped.get()) {
                    runCatching { playback.line.drain() }
                    next()
                }
            } catch (error: Throwable) {
                if (isPlayRequestCurrent(generation) && sampledStream === playback && !playback.stopped.get()) {
                    logPlaybackFailure(error)
                    diagnostics.playbackError(PlaybackEnginePath.SampledStream, error.message)
                    finishPlaybackFailed(generation = generation)
                }
            } finally {
                if (sampledStream === playback) {
                    sampledStream = null
                }
                playback.stop()
            }
        }, "Phoebe-sampled-stream-playback").apply {
            isDaemon = true
            start()
        }
    }

    private fun waitIfSampledStreamPaused(playback: StreamingSampledPlayback) {
        while (playback.paused && !playback.stopped.get()) {
            Thread.sleep(25L)
        }
    }

    private fun writeSampledStreamBuffer(
        playback: StreamingSampledPlayback,
        buffer: ByteArray,
        length: Int,
        generation: Int,
    ) {
        if (!isPlayRequestCurrent(generation) || playback.stopped.get()) return
        applyEqualizerToPcmBuffer(
            buffer = buffer,
            length = length,
            format = playback.stream.format,
            processor = playback.equalizerProcessor,
        )
        pcmFloatSamples(buffer, length, playback.stream.format)?.let { samples ->
            publishAudioAnalysisPcm(samples, playback.stream.format.sampleRate, AudioAnalysisSource.Pcm)
        }
        val written = playback.line.write(buffer, 0, length).coerceAtLeast(0)
        playback.lastWriteAtNs = System.nanoTime()
        playback.writtenPcmBytes += written.toLong()
        if (!playback.reportedEnergy) {
            val rms = pcmRms(buffer.copyOf(length), playback.stream.format)
            if (rms > 0.0 && rms.isFinite()) {
                playback.reportedEnergy = true
                diagnostics.decodedAudioEnergy(PlaybackEnginePath.SampledStream, rms)
            }
        }
    }

    private fun streamingEqualizerProcessor(format: AudioFormat): GraphicEqualizerProcessor? {
        val profile = equalizerProfile.normalized()
        if (!GraphicEqualizerProcessor.isActive(profile) ||
            format.sampleSizeInBits != 16 ||
            format.frameSize <= 0 ||
            format.channels <= 0 ||
            format.sampleRate <= 0f ||
            (format.encoding != AudioFormat.Encoding.PCM_SIGNED && format.encoding != AudioFormat.Encoding.PCM_UNSIGNED)
        ) {
            return null
        }
        return GraphicEqualizerProcessor(
            sampleRateHz = format.sampleRate,
            channelCount = format.channels,
            profile = profile,
        )
    }

    private fun applyEqualizerToPcmBuffer(
        buffer: ByteArray,
        length: Int,
        format: AudioFormat,
        processor: GraphicEqualizerProcessor?,
    ) {
        if (processor == null) return
        var offset = 0
        while (offset + format.frameSize <= length) {
            repeat(format.channels) { channel ->
                val sampleOffset = offset + channel * 2
                val sample = readPcm16(buffer, sampleOffset, format.isBigEndian, format.encoding) / 32768f
                val processed = (processor.process(channel, sample) * 32767f)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                writePcm16(buffer, sampleOffset, processed, format.isBigEndian, format.encoding)
            }
            offset += format.frameSize
        }
    }

    private fun syncSampledStreamPlayback(playback: StreamingSampledPlayback, generation: Int) {
        if (!isPlayRequestCurrent(generation) || sampledStream !== playback) return
        val decodedPositionMs = playback.stream.format.durationMsForPcmBytes(playback.writtenPcmBytes)
        val durationMs = state.value.durationMs
        val lastWriteElapsedMs = (System.nanoTime() - playback.lastWriteAtNs) / 1_000_000L
        val linePlaying = playback.line.isActive || playback.line.isRunning || (lastWriteElapsedMs < 1200L)
        val audiblePositionMs = if (!playback.paused && linePlaying) {
            playback.playbackPositionMs()
        } else {
            (playback.line.microsecondPosition / 1_000L).coerceAtLeast(0L)
        }
        val manualSeekSettling = isManualSeekSettling(generation)
        val stabilizedPositionMs = stabilizedPlatformPositionMs(audiblePositionMs, generation)
        val positionMs = stabilizedPositionMs
            .let { position ->
                if (manualSeekSettling) {
                    position
                } else {
                    position.coerceAtMost(decodedPositionMs.takeIf { it > 0L } ?: audiblePositionMs)
                }
            }
            .let { position -> if (durationMs > 0L) position.coerceAtMost(durationMs) else position }
        val decodedBufferedMs = maxOf(positionMs, decodedPositionMs)
        val bufferedPositionMs = playback.fullyBufferedDurationMs
            ?.takeIf { it > 0L }
            ?: decodedBufferedMs
        diagnostics.playbackProgress(PlaybackEnginePath.SampledStream, positionMs, durationMs)
        if (linePlaying) {
            diagnostics.platformPlaying(PlaybackEnginePath.SampledStream, positionMs, durationMs)
        }
        if (!playback.reportedOutput && linePlaying && playback.writtenPcmBytes > 0L) {
            playback.reportedOutput = true
            finishPlaybackReady(generation = generation)
        }
        val isStarting = !playback.paused && !playback.reportedOutput
        val isStarved = !playback.paused && !linePlaying && playback.reportedOutput
        val isBuffering = isStarting || isStarved
        applyPlatformPlayback(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = !playback.paused && linePlaying && !isStarting,
            isBuffering = isBuffering,
            bufferedPositionMs = bufferedPositionMs,
            generation = generation,
            forceBuffering = isBuffering,
        )
    }

    private fun sampledPlaybackExtensionFromUri(uri: String): String? {
        return DesktopPlaybackStartupPolicy.sampledPlaybackExtensionFromUri(uri)
    }

    private fun sampledPlaybackExtensionFromTrack(track: Track, playbackUri: String): String? {
        sampledPlaybackExtensionFromSuffix(track.audioCodec.orEmpty())?.let { return it }
        sampledPlaybackExtensionFromUri(playbackUri)?.let { return it }
        return sampledPlaybackExtensionFromSuffix(track.filepath.orEmpty().substringAfterLast('.', missingDelimiterValue = ""))
    }

    private fun streamingSampledExtensionFromUri(uri: String): String? {
        val path = runCatching { URI(uri).path }.getOrNull()
            ?: uri.substringBefore('?').substringBefore('#')
        return DesktopSandboxPlayback.streamingSampledExtensionFromSuffix(
            path.substringAfterLast('.', missingDelimiterValue = ""),
        )
    }

    private fun streamingSampledExtensionFromTrack(track: Track, playbackUri: String): String? {
        DesktopSandboxPlayback.streamingSampledExtensionFromSuffix(track.audioCodec.orEmpty())?.let { return it }
        streamingSampledExtensionFromUri(playbackUri)?.let { return it }
        return DesktopSandboxPlayback.streamingSampledExtensionFromSuffix(
            track.filepath.orEmpty().substringAfterLast('.', missingDelimiterValue = ""),
        )
    }

    private fun javaFxPlaybackExtensionFromTrack(track: Track, playbackUri: String): String? =
        DesktopPlaybackStartupPolicy.javaFxPlaybackExtensionFromMetadata(
            audioCodec = track.audioCodec,
            filepath = track.filepath,
            uri = playbackUri,
        )

    private fun sampledPlaybackExtensionFromSuffix(extension: String): String? =
        DesktopSandboxPlayback.sampledPlaybackExtensionFromSuffix(extension)

    /**
     * JavaFX cannot decode every format Phoebe supports. For sampled-only formats,
     * buffer the remote file first so startup reaches the Clip path directly.
     */
    private fun shouldEagerlyBufferRemotePlayback(uri: String, preferredSampledExtension: String?): Boolean {
        if (state.value.durationMs <= 0L) return false
        return DesktopSandboxPlayback.shouldEagerlyBufferRemotePlayback(uri, preferredSampledExtension)
    }

    private fun shouldPrefetchRemoteForCrossfade(uri: String): Boolean =
        DesktopPlaybackStartupPolicy.shouldPrefetchRemoteForCrossfade(uri)

    private fun scheduleCrossfadePrefetchAfterLoad(queue: List<Track>, currentIndex: Int, generation: Int) {
        playbackExecutor.execute {
            if (isPlayRequestCurrent(generation)) {
                prefetchCrossfadeCandidate(queue, currentIndex, generation)
            }
        }
    }

    private fun prefetchCrossfadeCandidate(queue: List<Track>, currentIndex: Int, generation: Int) {
        val nextTrack = queue.getOrNull(currentIndex + 1) ?: return clearCrossfadePrefetch()
        val uri = desktopPlaybackUriForTrack(nextTrack)
        if (!shouldPrefetchRemoteForCrossfade(uri)) return clearCrossfadePrefetch()
        val downloadUri = bufferedRemotePlaybackUri(uri, nextTrack.downloadUrl.takeIf { it.isNotBlank() })
        if (prefetchedCrossfade?.trackId == nextTrack.id && prefetchedCrossfade?.file?.exists() == true) return
        crossfadePrefetchFuture?.cancel(true)
        crossfadePrefetchFuture = CompletableFuture.supplyAsync({
            if (!isPlayRequestCurrent(generation)) return@supplyAsync null
            runCatching {
                val extension = sampledPlaybackExtensionFromTrack(nextTrack, uri)
                    ?: sampledPlaybackExtensionFromUri(uri)
                    ?: "mp3"
                val file = downloadRemoteAudioForCrossfade(downloadUri, extension)
                PrefetchedCrossfade(nextTrack.id, file)
            }.getOrNull()
        }, crossfadePrefetchExecutor).whenComplete { prefetched, _ ->
            val current = state.value
            val stillNext = current.queue.getOrNull(current.currentIndex + 1)?.id == prefetched?.trackId
            if (!isPlayRequestCurrent(generation) || prefetched == null || !stillNext) {
                prefetched?.file?.delete()
                return@whenComplete
            }
            val previous = prefetchedCrossfade
            if (previous?.trackId != prefetched.trackId) {
                previous?.file?.delete()
            }
            prefetchedCrossfade = prefetched
        }
    }

    private fun clearCrossfadePrefetch() {
        crossfadePrefetchFuture?.cancel(true)
        crossfadePrefetchFuture = null
        prefetchedCrossfade?.file?.delete()
        prefetchedCrossfade = null
    }

    private fun downloadRemoteAudioForCrossfade(uri: String, extension: String): File =
        downloadRemoteAudio(uri, extension)

    /** Avoid SPI probe order issues (e.g. JFlac throwing on Ogg before Vorbis runs). */
    private fun openRawAudioInputStream(file: File): AudioInputStream {
        return when (file.extension.lowercase()) {
            "flac" -> FlacAudioFileReader().getAudioInputStream(file)
            "ogg" -> VorbisAudioFileReader().getAudioInputStream(file)
            "opus" -> runCatching { VorbisAudioFileReader().getAudioInputStream(file) }
                .getOrElse { AudioSystem.getAudioInputStream(file) }
            else -> AudioSystem.getAudioInputStream(file)
        }
    }

    /**
     * [Clip] only accepts PCM lines. Vorbis/MP3 SPIs return encoded formats until converted.
     * FLAC often decodes to **24-bit PCM** with unknown frame size; converting straight to **16-bit**
     * is not always registered, so we **re-open the file** and try **source bit depth, then 24, then 16**.
     */
    private fun decodeToPcmStream(file: File): AudioInputStream {
        val probe = openRawAudioInputStream(file)
        val format = probe.format
        val enc = format.encoding
        val pcmReady = (enc == AudioFormat.Encoding.PCM_SIGNED || enc == AudioFormat.Encoding.PCM_UNSIGNED) &&
            format.frameSize > 0
        if (pcmReady) {
            return probe
        }
        runCatching { probe.close() }

        val channels = format.channels.takeIf { it > 0 } ?: 2
        val sampleRate = format.sampleRate.takeIf { it > 0f && !it.isNaN() } ?: 44100f
        val bitCandidates = buildList {
            val sb = format.sampleSizeInBits
            if (sb > 0) add(sb.coerceIn(8, 32))
            add(24)
            add(16)
        }.distinct()

        var lastError: Throwable? = null
        for (bits in bitCandidates) {
            if (bits % 8 != 0) continue
            val bytesPerSample = bits / 8
            val frameSize = channels * bytesPerSample
            val target = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                bits,
                channels,
                frameSize,
                sampleRate,
                false,
            )
            val raw = runCatching { openRawAudioInputStream(file) }.getOrElse { ex ->
                lastError = ex
                continue
            }
            try {
                return AudioSystem.getAudioInputStream(target, raw)
            } catch (e: Throwable) {
                lastError = e
                runCatching { raw.close() }
            }
        }
        throw lastError ?: IllegalStateException("Unsupported audio conversion")
    }

    private fun prepareStreamForClip(stream: AudioInputStream): AudioInputStream {
        val len = stream.frameLength
        if (len > 0L) {
            return stream
        }
        val format = stream.format
        val frameSize = format.frameSize.takeIf { it > 0 }
            ?: run {
                stream.close()
                error("Unsupported audio stream (unknown frame size)")
            }
        val bytes = stream.use { it.readAllBytes() }
        val frames = bytes.size.toLong() / frameSize
        return AudioInputStream(ByteArrayInputStream(bytes), format, frames)
    }

    /**
     * [Clip] on many JDKs only supports 16-bit (or 8-bit) PCM. FLAC often yields **24-bit** PCM; the SPI
     * may not offer a direct path to 16-bit, so we buffer PCM then downsample in software when needed.
     */
    private fun downsample24BitStereoLittleEndianTo16(input: ByteArray): ByteArray {
        require(input.size % 6 == 0) { "24-bit stereo PCM must be multiple of 6 bytes" }
        val frameCount = input.size / 6
        val out = ByteArray(frameCount * 4)
        var i = 0
        var o = 0
        repeat(frameCount) {
            fun sample24(b0: Int, b1: Int, b2: Int): Int {
                val u = (b0 or (b1 shl 8) or (b2 shl 16)) and 0xFFFFFF
                val s = (u shl 8) shr 8 // sign-extend 24-bit → Int
                return s shr 8 // drop 8 LSBs → 16-bit
            }
            val l = sample24(input[i].toInt() and 0xFF, input[i + 1].toInt() and 0xFF, input[i + 2].toInt() and 0xFF)
            val r = sample24(input[i + 3].toInt() and 0xFF, input[i + 4].toInt() and 0xFF, input[i + 5].toInt() and 0xFF)
            out[o++] = (l and 0xFF).toByte()
            out[o++] = ((l shr 8) and 0xFF).toByte()
            out[o++] = (r and 0xFF).toByte()
            out[o++] = ((r shr 8) and 0xFF).toByte()
            i += 6
        }
        return out
    }

    private fun bufferPcmForClipWithOptionalDownsampleTo16(stream: AudioInputStream): AudioInputStream {
        val bounded = prepareStreamForClip(stream)
        val fmt = bounded.format
        if (fmt.sampleSizeInBits <= 16) {
            return bounded
        }
        if (fmt.sampleSizeInBits == 24 &&
            fmt.channels == 2 &&
            !fmt.isBigEndian &&
            (fmt.encoding == AudioFormat.Encoding.PCM_SIGNED || fmt.encoding == AudioFormat.Encoding.PCM_UNSIGNED)
        ) {
            val pcmBytes = bounded.use { it.readAllBytes() }
            val out = downsample24BitStereoLittleEndianTo16(pcmBytes)
            val outFmt = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                fmt.sampleRate,
                16,
                2,
                4,
                fmt.sampleRate,
                false,
            )
            val frames = out.size / 4L
            return AudioInputStream(ByteArrayInputStream(out), outFmt, frames)
        }
        runCatching { bounded.close() }
        error("Clip playback needs 16-bit PCM; unsupported format: $fmt")
    }

    private fun openAndStartSampledClip(file: File, initialVolume: Float = effectiveOutputVolume()): Clip? {
        val pcmStream = try {
            decodeToPcmStream(file)
        } catch (e: Throwable) {
            logPlaybackFailure(e)
            return null
        }
        val prepared = try {
            bufferPcmForClipWithOptionalDownsampleTo16(pcmStream)
        } catch (e: Throwable) {
            runCatching { pcmStream.close() }
            logPlaybackFailure(e)
            return null
        }
        val playbackStream = applyEqualizerToPcmStream(prepared)
        return runCatching {
            val clip = AudioSystem.getClip()
            val diagnosticsStream = copyPcmStreamForDiagnostics(playbackStream)
            try {
                clip.open(diagnosticsStream)
            } catch (e: Throwable) {
                runCatching { diagnosticsStream.close() }
                throw e
            }
            applyVolumeToClip(clip, initialVolume)
            clip.start()
            clip
        }.getOrElse { e ->
            logPlaybackFailure(e)
            diagnostics.playbackError(PlaybackEnginePath.SampledClip, e.message)
            null
        }
    }

    private fun applyEqualizerToPcmStream(stream: AudioInputStream): AudioInputStream {
        val profile = equalizerProfile.normalized()
        if (!GraphicEqualizerProcessor.isActive(profile)) return stream
        val format = stream.format
        if (format.sampleSizeInBits != 16 ||
            format.frameSize <= 0 ||
            format.channels <= 0 ||
            format.sampleRate <= 0f ||
            (format.encoding != AudioFormat.Encoding.PCM_SIGNED && format.encoding != AudioFormat.Encoding.PCM_UNSIGNED)
        ) {
            return stream
        }
        val input = stream.use { it.readAllBytes() }
        val output = input.copyOf()
        val processor = GraphicEqualizerProcessor(
            sampleRateHz = format.sampleRate,
            channelCount = format.channels,
            profile = profile,
        )
        var offset = 0
        while (offset + format.frameSize <= output.size) {
            repeat(format.channels) { channel ->
                val sampleOffset = offset + channel * 2
                val sample = readPcm16(output, sampleOffset, format.isBigEndian, format.encoding) / 32768f
                val processed = (processor.process(channel, sample) * 32767f)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                writePcm16(output, sampleOffset, processed, format.isBigEndian, format.encoding)
            }
            offset += format.frameSize
        }
        val frames = output.size.toLong() / format.frameSize.toLong()
        return AudioInputStream(ByteArrayInputStream(output), format, frames)
    }

    private fun downloadRemoteAudio(uri: String, extension: String): File {
        val request = HttpRequest.newBuilder(URI(uri))
            .GET()
            .timeout(Duration.ofSeconds(45))
            .header("User-Agent", "Phoebe/0.1.0 (https://github.com/phoebe)")
            .header("X-Plex-Client-Identifier", PlexClient.ClientIdentifier)
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            response.body().close()
            error("Plex stream request failed (${response.statusCode()})")
        }
        val contentType = response.headers().firstValue("content-type").orElse("").lowercase()
        if (contentType.startsWith("text/") ||
            contentType.contains("html") ||
            contentType.contains("json") ||
            contentType.contains("xml")
        ) {
            response.body().close()
            error("Plex stream returned $contentType instead of audio")
        }
        val resolvedExtension = extensionFromContentType(contentType)
            ?: sampledPlaybackExtensionFromSuffix(extension)
            ?: extension.takeIf { it.isNotBlank() && it != "bin" }
            ?: "mp3"
        val suffix = ".$resolvedExtension"
        val temp = Files.createTempFile("phoebe-plex-stream-", suffix).toFile()
        temp.deleteOnExit()
        try {
            response.body().use { input ->
                Files.copy(input, temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
        return temp
    }

    private fun disposeSampled() {
        runCatching { sampledStream?.stop() }
        sampledStream = null
        sampledStreamSource = null
        runCatching {
            sampledClip?.stop()
            sampledClip?.close()
        }
        sampledClip = null
        remoteSampledFile?.let { temp ->
            runCatching { temp.delete() }
        }
        remoteSampledFile = null
        fullyBufferedPlayback = false
    }

    private fun disposeJavaFxBlocking() {
        if (player == null && fadingOutPlayer == null) return
        stopJavaFxProgressProbe()
        val latch = CountDownLatch(1)
        JavaFxRuntime.runLater {
            runCatching {
                player?.stop()
                player?.dispose()
            }
            player = null
            runCatching {
                fadingOutPlayer?.stop()
                fadingOutPlayer?.dispose()
            }
            fadingOutPlayer = null
            latch.countDown()
        }
        if (latch.await(30, TimeUnit.SECONDS)) {
            Thread.sleep(JavaFxDisposeSettleMs)
        }
    }

    private fun disposeAllOnPlaybackThread() {
        cancelJavaFxStartupWatchdog()
        disposeJavaFxBlocking()
        disposeSampled()
        clearCrossfadePrefetch()
    }

    private fun isRemoteUri(uri: String): Boolean =
        DesktopPlaybackStartupPolicy.isRemoteUri(uri)

    private fun bufferedRemotePlaybackUri(uri: String, downloadUri: String?): String =
        DesktopSandboxPlayback.bufferedRemotePlaybackUri(uri, downloadUri)

    private fun startJavaFxProgressProbe(mediaPlayer: MediaPlayer, generation: Int) {
        stopJavaFxProgressProbe()
        val stop = AtomicBoolean(false)
        javaFxProgressProbeStop = stop
        Thread({
            var lastRawPositionMs = 0L
            var fallbackBasePositionMs = 0L
            var fallbackBaseAtNs = System.nanoTime()
            while (!stop.get() && isPlayRequestCurrent(generation)) {
                val latch = CountDownLatch(1)
                JavaFxRuntime.runLater {
                    runCatching {
                        if (stop.get() || !isPlayRequestCurrent(generation) || player !== mediaPlayer) {
                            latch.countDown()
                            return@runLater
                        }
                        val rawPositionMs = javafxDurationMs(mediaPlayer.currentTime)
                        val playing = mediaPlayer.status == MediaPlayer.Status.PLAYING
                        val nowNs = System.nanoTime()
                        if (rawPositionMs > lastRawPositionMs || !playing) {
                            lastRawPositionMs = rawPositionMs
                            fallbackBasePositionMs = rawPositionMs
                            fallbackBaseAtNs = nowNs
                        }
                        val fallbackPositionMs = if (playing) {
                            val elapsedMs = (nowNs - fallbackBaseAtNs).coerceAtLeast(0L) / 1_000_000L
                            val durationMs = javafxDurationMs(mediaPlayer.media.duration)
                            maxOf(rawPositionMs, fallbackBasePositionMs + elapsedMs).let { position ->
                                if (durationMs > 0L) position.coerceAtMost(durationMs) else position
                            }
                        } else {
                            rawPositionMs
                        }
                        syncJavaFxPlayback(
                            mediaPlayer = mediaPlayer,
                            generation = generation,
                            isBuffering = mediaPlayer.status == MediaPlayer.Status.STALLED,
                            fallbackPositionMs = fallbackPositionMs,
                        )
                    }
                    latch.countDown()
                }
                latch.await(1, TimeUnit.SECONDS)
                Thread.sleep(250L)
            }
        }, "Phoebe-javafx-playback-diagnostics").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopJavaFxProgressProbe() {
        javaFxProgressProbeStop?.set(true)
        javaFxProgressProbeStop = null
    }

    private fun syncJavaFxPlayback(
        mediaPlayer: MediaPlayer,
        generation: Int,
        isBuffering: Boolean,
        fallbackPositionMs: Long? = null,
    ) {
        if (!isPlayRequestCurrent(generation)) return
        val platformPositionMs = stabilizedPlatformPositionMs(javafxDurationMs(mediaPlayer.currentTime), generation)
        val positionMs = maxOf(platformPositionMs, fallbackPositionMs ?: platformPositionMs)
        val durationMs = javafxDurationMs(mediaPlayer.media.duration)
        val platformBufferedMs = javafxDurationMs(mediaPlayer.bufferProgressTime).coerceAtLeast(positionMs)
        val bufferedMs = if (fullyBufferedPlayback && durationMs > 0L) durationMs else platformBufferedMs
        val playing = mediaPlayer.status == MediaPlayer.Status.PLAYING
        val current = state.value
        val nowMs = System.currentTimeMillis()
        val playbackFlagsChanged = playing != current.isPlaying || isBuffering != current.isBuffering
        val bufferedAdvanced = bufferedMs > current.bufferedPositionMs + 500L
        if (!playbackFlagsChanged &&
            !bufferedAdvanced &&
            nowMs - lastPlaybackUiSyncAtMs < PlaybackUiSyncIntervalMs &&
            kotlin.math.abs(positionMs - current.positionMs) < PlaybackUiSyncIntervalMs
        ) {
            return
        }
        lastPlaybackUiSyncAtMs = nowMs
        diagnostics.playbackProgress(PlaybackEnginePath.JavaFxMediaPlayer, positionMs, durationMs)
        if (mediaPlayer.status == MediaPlayer.Status.PLAYING) {
            diagnostics.platformPlaying(PlaybackEnginePath.JavaFxMediaPlayer, positionMs, durationMs)
        }
        val waitingForPlayback = playWhenReady && !playing
        applyPlatformPlayback(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = playing,
            isBuffering = isBuffering || waitingForPlayback,
            bufferedPositionMs = bufferedMs,
            generation = generation,
            forceBuffering = waitingForPlayback || isBuffering,
        )
    }

    private fun stabilizedPlatformPositionMs(platformPositionMs: Long, generation: Int): Long {
        if (pendingManualSeekGeneration != generation) return platformPositionMs
        val nowMs = System.currentTimeMillis()
        if (nowMs > pendingManualSeekUntilMs) {
            pendingManualSeekGeneration = -1
            return platformPositionMs
        }
        val targetPositionMs = pendingManualSeekPositionMs
        if (kotlin.math.abs(platformPositionMs - targetPositionMs) <= ManualSeekPlatformSettleToleranceMs) {
            pendingManualSeekGeneration = -1
            return platformPositionMs
        }
        return targetPositionMs
    }

    private fun isManualSeekSettling(generation: Int): Boolean =
        pendingManualSeekGeneration == generation && System.currentTimeMillis() <= pendingManualSeekUntilMs

    private fun trackDurationOrClipDuration(generation: Int, clip: Clip): Long {
        val stateDuration = state.value.durationMs.takeIf { it > 0L }
        if (stateDuration != null && isPlayRequestCurrent(generation)) return stateDuration
        return (clip.microsecondLength / 1_000L).coerceAtLeast(0L)
    }

    private fun startSampledProgressProbe(clip: Clip, generation: Int) {
        Thread({
            var lastRawPositionMs = (clip.microsecondPosition / 1_000L).coerceAtLeast(0L)
            var fallbackBasePositionMs = lastRawPositionMs
            var fallbackBaseAtNs = System.nanoTime()
            var endDispatched = false
            while (isPlayRequestCurrent(generation) && sampledClip === clip && clip.isOpen) {
                val rawPositionMs = (clip.microsecondPosition / 1_000L).coerceAtLeast(0L)
                val durationMs = (clip.microsecondLength / 1_000L).coerceAtLeast(0L)
                val reachedEnd = durationMs > 0L && rawPositionMs + SampledClipEndToleranceMs >= durationMs
                val playing = !reachedEnd && (clip.isActive || clip.isRunning)
                val nowNs = System.nanoTime()
                if (rawPositionMs != lastRawPositionMs || !playing) {
                    lastRawPositionMs = rawPositionMs
                    fallbackBasePositionMs = rawPositionMs
                    fallbackBaseAtNs = nowNs
                }
                val platformPositionMs = if (playing) {
                    val elapsedMs = (nowNs - fallbackBaseAtNs).coerceAtLeast(0L) / 1_000_000L
                    maxOf(rawPositionMs, fallbackBasePositionMs + elapsedMs).let { position ->
                        if (durationMs > 0L) position.coerceAtMost(durationMs) else position
                    }
                } else {
                    rawPositionMs
                }
                val positionMs = stabilizedPlatformPositionMs(platformPositionMs, generation)
                val completed = durationMs > 0L && positionMs + SampledClipEndToleranceMs >= durationMs
                val effectivePlaying = playing && !completed
                diagnostics.playbackProgress(PlaybackEnginePath.SampledClip, positionMs, durationMs)
                if (effectivePlaying) {
                    diagnostics.platformPlaying(PlaybackEnginePath.SampledClip, positionMs, durationMs)
                }
                applyPlatformPlayback(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    isPlaying = effectivePlaying,
                    isBuffering = false,
                    bufferedPositionMs = durationMs.takeIf { it > 0L } ?: positionMs,
                    generation = generation,
                )
                if ((reachedEnd || completed) && !endDispatched) {
                    endDispatched = true
                    next()
                    break
                }
                Thread.sleep(250L)
            }
        }, "Phoebe-sampled-playback-diagnostics").apply {
            isDaemon = true
            start()
        }
    }

    private fun copyPcmStreamForDiagnostics(stream: AudioInputStream): AudioInputStream {
        val format = stream.format
        val bytes = stream.use { it.readAllBytes() }
        pcmFloatSamples(bytes, bytes.size, format)?.let { samples ->
            publishAudioAnalysisPcm(samples, format.sampleRate, AudioAnalysisSource.Pcm)
        }
        val rms = pcmRms(bytes, format)
        if (rms > 0.0 && rms.isFinite()) {
            diagnostics.decodedAudioEnergy(PlaybackEnginePath.SampledClip, rms)
        }
        val frameSize = format.frameSize.takeIf { it > 0 } ?: 1
        return AudioInputStream(ByteArrayInputStream(bytes), format, bytes.size.toLong() / frameSize.toLong())
    }

    override fun close() {
        release(timeoutSeconds = 2)
    }

    fun releaseForTests() {
        release(timeoutSeconds = 30)
    }

    private fun release(timeoutSeconds: Long) {
        runCatching {
            playbackExecutor.submit {
                disposeAllOnPlaybackThread()
            }.get(timeoutSeconds, TimeUnit.SECONDS)
        }
        playbackExecutor.shutdownNow()
        crossfadePrefetchExecutor.shutdownNow()
    }

    private fun extensionFromContentType(contentType: String): String? = when {
        contentType.contains("flac") -> "flac"
        contentType.contains("mpeg") || contentType.contains("mp3") -> "mp3"
        contentType.contains("mp4") || contentType.contains("m4a") || contentType.contains("aac") -> "m4a"
        contentType.contains("ogg") || contentType.contains("vorbis") -> "ogg"
        contentType.contains("opus") -> "opus"
        contentType.contains("wav") -> "wav"
        else -> null
    }

    private fun logPlaybackFailure(error: Throwable) {
        val message = error.message ?: error::class.simpleName.orEmpty()
        PhoebeLog.d("DesktopAudioPlayer") { "playback error: $message" }
    }

    private companion object {
        const val JavaFxEqualizerBandMatchTolerance = 0.045f
        const val JavaFxMediaReadyTimeoutMs = DesktopPlaybackStartupPolicy.JavaFxFailureFallbackDelayMs
        const val JavaFxMediaPlayingTimeoutMs = 8_000L
        const val JavaFxCrossfadeReadyTimeoutMs = 3_000L
        const val JavaFxDisposeSettleMs = 250L
        const val PlaybackUiSyncIntervalMs = 250L
        const val SampledClipEndToleranceMs = 20L
        const val RemoteAudioProbeBufferBytes = 128 * 1024
        const val StreamingPcmBufferBytes = 16 * 1024
        const val MaxStreamingLineBufferBytes = 1024 * 1024
        const val FfmpegPcmSampleRateHz = 44_100
        const val FfmpegPcmChannels = 2
        const val FfmpegPcmStreamExtension = "ffmpeg-pcm"
        val FfmpegFallbackPaths = listOf(
            "/opt/homebrew/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
            "/usr/bin/ffmpeg",
        )
        val JavaFxPreferredLocalCrossfadeExtensions = setOf("mp3", "mpeg", "mpga")
    }
}

internal fun isLikelyDesktopPlaylistUri(uri: String): Boolean {
    val path = runCatching { URI(uri).path }.getOrNull()
        ?: uri.substringBefore('?').substringBefore('#')
    val lower = path.lowercase()
    return lower.endsWith(".pls") || (lower.endsWith(".m3u") && !lower.endsWith(".m3u8"))
}

internal fun parseDesktopPlaylistStreamUri(body: String, playlistUri: String): String? {
    val base = runCatching { URI(playlistUri) }.getOrNull()
    val candidates = body
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            if (line.startsWith("File", ignoreCase = true) && line.contains('=')) {
                line.substringAfter('=').trim()
            } else {
                line
            }
        }
        .filterNot { line ->
            line.startsWith("[") ||
                line.startsWith("Title", ignoreCase = true) ||
                line.startsWith("Length", ignoreCase = true) ||
                line.startsWith("NumberOfEntries", ignoreCase = true) ||
                line.startsWith("Version", ignoreCase = true)
        }
    return candidates.firstNotNullOfOrNull { candidate ->
        when {
            candidate.startsWith("http://", ignoreCase = true) ||
                candidate.startsWith("https://", ignoreCase = true) -> candidate
            base != null -> runCatching { base.resolve(candidate).toString() }.getOrNull()
            else -> null
        }
    }
}

private fun pcmRms(bytes: ByteArray, format: AudioFormat): Double {
    val frameSize = format.frameSize.takeIf { it > 0 } ?: return 0.0
    val channels = format.channels.takeIf { it > 0 } ?: return 0.0
    val sampleBytes = ((format.sampleSizeInBits + 7) / 8).takeIf { it in 1..4 } ?: return 0.0
    var sumSquares = 0.0
    var sampleCount = 0L
    var frameOffset = 0
    while (frameOffset + frameSize <= bytes.size) {
        var sampleOffset = frameOffset
        repeat(channels) {
            if (sampleOffset + sampleBytes <= frameOffset + frameSize) {
                val normalized = normalizedPcmSample(bytes, sampleOffset, sampleBytes, format)
                sumSquares += normalized * normalized
                sampleCount++
            }
            sampleOffset += sampleBytes
        }
        frameOffset += frameSize
    }
    return if (sampleCount == 0L) 0.0 else Math.sqrt(sumSquares / sampleCount.toDouble())
}

private fun pcmFloatSamples(
    bytes: ByteArray,
    length: Int,
    format: AudioFormat,
    maxSamples: Int = 2048,
): FloatArray? {
    val safeLength = length.coerceIn(0, bytes.size)
    val frameSize = format.frameSize.takeIf { it > 0 } ?: return null
    val channels = format.channels.takeIf { it > 0 } ?: return null
    val sampleBytes = ((format.sampleSizeInBits + 7) / 8).takeIf { it in 1..4 } ?: return null
    val frameCount = safeLength / frameSize
    val totalSamples = frameCount * channels
    if (totalSamples <= 0) return null
    val stride = (totalSamples / maxSamples.coerceAtLeast(1)).coerceAtLeast(1)
    val outputSize = ((totalSamples + stride - 1) / stride).coerceAtMost(maxSamples.coerceAtLeast(1))
    val output = FloatArray(outputSize)
    var frameOffset = 0
    var sampleOrdinal = 0
    var outputIndex = 0
    while (frameOffset + frameSize <= safeLength && outputIndex < output.size) {
        var sampleOffset = frameOffset
        repeat(channels) {
            if (sampleOffset + sampleBytes <= frameOffset + frameSize) {
                if (sampleOrdinal % stride == 0 && outputIndex < output.size) {
                    output[outputIndex++] = normalizedPcmSample(bytes, sampleOffset, sampleBytes, format).toFloat()
                }
                sampleOrdinal++
            }
            sampleOffset += sampleBytes
        }
        frameOffset += frameSize
    }
    return if (outputIndex == output.size) output else output.copyOf(outputIndex)
}

private fun AudioFormat.durationMsForPcmBytes(bytes: Long): Long {
    val frameSize = frameSize.takeIf { it > 0 } ?: return 0L
    val sampleRate = sampleRate.takeIf { it > 0f && !it.isNaN() } ?: return 0L
    val frames = bytes.coerceAtLeast(0L).toDouble() / frameSize.toDouble()
    return ((frames / sampleRate.toDouble()) * 1_000.0).toLong().coerceAtLeast(0L)
}

private fun AudioFormat.pcmBytesForDurationMs(durationMs: Long): Long {
    val frameSize = frameSize.takeIf { it > 0 } ?: return 0L
    val sampleRate = sampleRate.takeIf { it > 0f && !it.isNaN() } ?: return 0L
    val frames = (durationMs.coerceAtLeast(0L).toDouble() / 1_000.0) * sampleRate.toDouble()
    return (frames.toLong() * frameSize.toLong()).coerceAtLeast(0L)
}

private fun javafxDurationMs(duration: javafx.util.Duration?): Long {
    val millis = duration?.toMillis() ?: return 0L
    return millis
        .takeIf { it.isFinite() && it > 0.0 }
        ?.toLong()
        ?: 0L
}

private fun sampledMasterGainDb(
    volume: Float,
    minimum: Float,
    maximum: Float,
): Float {
    val linear = volume.coerceIn(0f, 1f)
    if (linear <= 0f) return minimum
    val db = (20.0 * kotlin.math.log10(linear.toDouble())).toFloat()
    return db.coerceIn(minimum, maximum.coerceAtMost(0f).takeIf { it >= minimum } ?: maximum)
}

private fun readPcm16(
    bytes: ByteArray,
    offset: Int,
    bigEndian: Boolean,
    encoding: AudioFormat.Encoding,
): Int {
    val b0 = bytes[offset].toInt() and 0xFF
    val b1 = bytes[offset + 1].toInt() and 0xFF
    val raw = if (bigEndian) (b0 shl 8) or b1 else b0 or (b1 shl 8)
    if (encoding == AudioFormat.Encoding.PCM_UNSIGNED) return raw - 32768
    return (raw shl 16) shr 16
}

private fun writePcm16(
    bytes: ByteArray,
    offset: Int,
    signedValue: Int,
    bigEndian: Boolean,
    encoding: AudioFormat.Encoding,
) {
    val raw = if (encoding == AudioFormat.Encoding.PCM_UNSIGNED) {
        (signedValue + 32768).coerceIn(0, 65535)
    } else {
        signedValue and 0xFFFF
    }
    if (bigEndian) {
        bytes[offset] = ((raw shr 8) and 0xFF).toByte()
        bytes[offset + 1] = (raw and 0xFF).toByte()
    } else {
        bytes[offset] = (raw and 0xFF).toByte()
        bytes[offset + 1] = ((raw shr 8) and 0xFF).toByte()
    }
}

private fun normalizedPcmSample(bytes: ByteArray, offset: Int, sampleBytes: Int, format: AudioFormat): Double {
    val unsigned = format.encoding == AudioFormat.Encoding.PCM_UNSIGNED
    val littleEndian = !format.isBigEndian
    var value = 0L
    repeat(sampleBytes) { index ->
        val sourceIndex = if (littleEndian) offset + index else offset + sampleBytes - 1 - index
        value = value or ((bytes[sourceIndex].toLong() and 0xFFL) shl (8 * index))
    }
    if (unsigned) {
        val midpoint = 1L shl (sampleBytes * 8 - 1)
        return (value - midpoint).toDouble() / midpoint.toDouble()
    }
    val shift = 64 - sampleBytes * 8
    val signed = (value shl shift) shr shift
    val denominator = (1L shl (sampleBytes * 8 - 1)).toDouble()
    return signed.toDouble() / denominator
}

internal object DesktopPlaybackStartupPolicy {
    const val JavaFxFailureFallbackDelayMs = 3_000L

    fun isRemoteUri(uri: String): Boolean =
        uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)

    fun javaFxPlaybackExtensionFromMetadata(
        audioCodec: String?,
        filepath: String?,
        uri: String,
    ): String? {
        javaFxPlaybackExtensionFromSuffix(audioCodec.orEmpty())?.let { return it }
        javaFxPlaybackExtensionFromUri(uri)?.let { return it }
        return javaFxPlaybackExtensionFromSuffix(
            filepath.orEmpty().substringAfterLast('.', missingDelimiterValue = ""),
        )
    }

    fun javaFxPlaybackExtensionFromUri(uri: String): String? {
        return javaFxPlaybackExtensionFromSuffix(extensionSuffixFromUri(uri))
    }

    fun javaFxPlaybackExtensionFromSuffix(extension: String): String? {
        return when (extension.lowercase()) {
            "mp3", "mpeg", "mpga" -> "mp3"
            "aac", "m4a", "m4b", "mp4", "alac" -> extension.lowercase()
            else -> null
        }
    }

    fun sampledPlaybackExtensionFromUri(uri: String): String? {
        return sampledPlaybackExtensionFromSuffix(extensionSuffixFromUri(uri))
    }

    fun streamingSampledExtensionFromUri(uri: String): String? {
        return streamingSampledExtensionFromSuffix(extensionSuffixFromUri(uri))
    }

    fun sampledPlaybackExtensionFromSuffix(extension: String): String? {
        return when (extension.lowercase()) {
            "wav", "wave", "aif", "aiff", "flac", "ogg", "opus" -> extension.lowercase()
            else -> null
        }
    }

    fun streamingSampledExtensionFromSuffix(extension: String): String? {
        return when (extension.lowercase()) {
            "wav", "wave" -> "wav"
            "aif", "aiff" -> "aiff"
            "flac", "ogg", "opus" -> extension.lowercase()
            else -> null
        }
    }

    fun shouldEagerlyBufferRemotePlayback(uri: String, preferredSampledExtension: String?): Boolean {
        if (!isRemoteUri(uri)) return false
        return (preferredSampledExtension ?: sampledPlaybackExtensionFromUri(uri)) != null
    }

    fun shouldStreamRemoteSampledPlayback(
        uri: String,
        preferredStreamingExtension: String?,
        isFlatpakSandbox: Boolean,
    ): Boolean {
        if (!isRemoteUri(uri)) return false
        val extension = preferredStreamingExtension
            ?: streamingSampledExtensionFromUri(uri)
            ?: sampledPlaybackExtensionFromUri(uri)
            ?: extensionSuffixFromUri(uri).takeIf { isFlatpakSandbox }
            ?: return false
        if (isFlatpakSandbox) {
            return when (extension.lowercase()) {
                "mp3", "mpeg", "mpga" -> true
                else -> streamingSampledExtensionFromSuffix(extension) != null
            }
        }
        return streamingSampledExtensionFromSuffix(extension) != null
    }

    fun shouldUsePcmStreamBeforeJavaFx(
        uri: String,
        isKnownLiveStream: Boolean,
        preferredJavaFxExtension: String?,
    ): Boolean {
        if (!isRemoteUri(uri)) return false
        return isKnownLiveStream
    }

    fun startupPlanForRemotePlayback(
        uri: String,
        isKnownLiveStream: Boolean,
        preferredJavaFxExtension: String?,
        preferredSampledExtension: String?,
        preferredStreamingExtension: String?,
        durationMs: Long,
        isFlatpakSandbox: Boolean,
    ): DesktopPlaybackStartupPlan {
        if (!isRemoteUri(uri)) {
            return instantStartupPlan(DesktopPlaybackStartupPath.JavaFxMediaPlayer)
        }
        if (!isFlatpakSandbox &&
            shouldUsePcmStreamBeforeJavaFx(
                uri = uri,
                isKnownLiveStream = isKnownLiveStream,
                preferredJavaFxExtension = preferredJavaFxExtension,
            )
        ) {
            return instantStartupPlan(DesktopPlaybackStartupPath.FfmpegPcmStream)
        }
        val streamingExtension = preferredStreamingExtension ?: streamingSampledExtensionFromUri(uri)
        if (streamingExtension != null &&
            shouldStreamRemoteSampledPlayback(
                uri = uri,
                preferredStreamingExtension = streamingExtension,
                isFlatpakSandbox = isFlatpakSandbox,
            )
        ) {
            return instantStartupPlan(DesktopPlaybackStartupPath.SampledStream)
        }
        if (durationMs > 0L && shouldEagerlyBufferRemotePlayback(uri, preferredSampledExtension)) {
            return instantStartupPlan(DesktopPlaybackStartupPath.BufferedSampledPlayback)
        }
        val javaFxExtension = preferredJavaFxExtension ?: javaFxPlaybackExtensionFromUri(uri)
        if (javaFxExtension != null && javaFxPlaybackExtensionFromSuffix(javaFxExtension) != null) {
            return instantStartupPlan(DesktopPlaybackStartupPath.JavaFxMediaPlayer)
        }
        val sampledExtension = preferredSampledExtension
            ?: sampledPlaybackExtensionFromUri(uri)
            ?: streamingExtension
        if (sampledExtension != null && sampledPlaybackExtensionFromSuffix(sampledExtension) != null) {
            return instantStartupPlan(DesktopPlaybackStartupPath.FfmpegPcmStream)
        }
        return DesktopPlaybackStartupPlan(
            path = DesktopPlaybackStartupPath.JavaFxThenFallback,
            deterministicDelayBeforeFirstEngineMs = JavaFxFailureFallbackDelayMs,
            waitsForJavaFxFailureBeforeFallback = true,
        )
    }

    fun shouldPrefetchRemoteForCrossfade(uri: String): Boolean = isRemoteUri(uri)

    private fun instantStartupPlan(path: DesktopPlaybackStartupPath): DesktopPlaybackStartupPlan =
        DesktopPlaybackStartupPlan(
            path = path,
            deterministicDelayBeforeFirstEngineMs = 0L,
            waitsForJavaFxFailureBeforeFallback = false,
        )

    private fun extensionSuffixFromUri(uri: String): String {
        val path = runCatching { URI(uri).path }.getOrNull()
            ?: uri.substringBefore('?').substringBefore('#')
        return path.substringAfterLast('.', missingDelimiterValue = "")
    }
}

private const val ManualSeekPlatformSettleMs = 1_500L
private const val ManualSeekPlatformSettleToleranceMs = 750L

internal enum class DesktopPlaybackStartupPath {
    JavaFxMediaPlayer,
    SampledStream,
    FfmpegPcmStream,
    BufferedSampledPlayback,
    JavaFxThenFallback,
}

internal data class DesktopPlaybackStartupPlan(
    val path: DesktopPlaybackStartupPath,
    val deterministicDelayBeforeFirstEngineMs: Long,
    val waitsForJavaFxFailureBeforeFallback: Boolean,
)

private object JavaFxRuntime {
    private val started = AtomicBoolean(false)
    private val mediaWarmupStarted = AtomicBoolean(false)
    private val ready = CompletableFuture<Unit>()

    fun warmUp() {
        start()
    }

    fun warmUpMedia() {
        if (!mediaWarmupStarted.compareAndSet(false, true)) return
        runLater(
            block = {
                val warmupFile = runCatching { createJavaFxMediaWarmupFile() }
                    .getOrElse { error ->
                        PhoebeLog.d("DesktopAudioPlayer") { "JavaFX media warmup file failed: ${error.message}" }
                        return@runLater
                    }
                var warmupPlayer: MediaPlayer? = null
                fun disposeWarmup() {
                    runCatching {
                        warmupPlayer?.stop()
                        warmupPlayer?.dispose()
                    }
                    warmupPlayer = null
                    runCatching { warmupFile.delete() }
                }
                runCatching {
                    val media = Media(warmupFile.toURI().toString())
                    val player = MediaPlayer(media)
                    warmupPlayer = player
                    player.setMute(true)
                    player.volume = 0.0
                    media.setOnError {
                        PhoebeLog.d("DesktopAudioPlayer") { "JavaFX media warmup media error: ${media.error?.message}" }
                        disposeWarmup()
                    }
                    player.setOnError {
                        PhoebeLog.d("DesktopAudioPlayer") {
                            "JavaFX media warmup player error: ${player.error?.message ?: player.error?.type}"
                        }
                        disposeWarmup()
                    }
                    player.setOnReady {
                        player.play()
                    }
                    player.setOnPlaying {
                        disposeWarmup()
                    }
                    player.play()
                    CompletableFuture.delayedExecutor(JavaFxMediaWarmupTimeoutMs, TimeUnit.MILLISECONDS)
                        .execute {
                            runLater(
                                block = { disposeWarmup() },
                                onError = { runCatching { warmupFile.delete() } },
                            )
                        }
                }.onFailure { error ->
                    PhoebeLog.d("DesktopAudioPlayer") { "JavaFX media warmup failed: ${error.message}" }
                    disposeWarmup()
                }
            },
            onError = { error ->
                PhoebeLog.d("DesktopAudioPlayer") { "JavaFX media warmup startup error: ${error.message}" }
            },
        )
    }

    val hasFailed: Boolean
        get() = ready.isCompletedExceptionally

    fun runLater(block: () -> Unit) {
        runLater(block, null)
    }

    fun runLater(block: () -> Unit, onError: ((Throwable) -> Unit)?) {
        start()
        ready.whenComplete { _, error ->
            if (error != null) {
                onError?.invoke(error) ?: PhoebeLog.d("DesktopAudioPlayer") { "JavaFxRuntime startup error: ${error.message ?: error::class.simpleName.orEmpty()}" }
                return@whenComplete
            }
            try {
                Platform.runLater(block)
            } catch (t: Throwable) {
                onError?.invoke(t) ?: PhoebeLog.d("DesktopAudioPlayer") { "Platform.runLater failed: ${t.message ?: t::class.simpleName.orEmpty()}" }
            }
        }
    }

    private fun start() {
        if (started.compareAndSet(false, true)) {
            Thread({
                runCatching {
                    Platform.startup {
                        ready.complete(Unit)
                    }
                }.onFailure { error ->
                    if (error is IllegalStateException) {
                        ready.complete(Unit)
                    } else {
                        ready.completeExceptionally(error)
                    }
                }
            }, "Phoebe-JavaFX-Startup").apply {
                isDaemon = true
                start()
            }
        }
    }

    private const val JavaFxMediaWarmupTimeoutMs = 10_000L
}

private fun createJavaFxMediaWarmupFile(): File {
    val file = Files.createTempFile("phoebe-javafx-media-warmup-", ".wav").toFile()
    file.deleteOnExit()
    Files.write(file.toPath(), javaFxMediaWarmupWavBytes())
    return file
}

internal fun javaFxMediaWarmupWavBytes(): ByteArray {
    val sampleCount = JavaFxWarmupSampleRateHz * JavaFxWarmupDurationMs / 1_000
    val dataSize = sampleCount * JavaFxWarmupChannels * JavaFxWarmupBitsPerSample / 8
    val bytes = ByteArray(WavHeaderBytes + dataSize)

    fun ascii(offset: Int, value: String) {
        value.forEachIndexed { index, c -> bytes[offset + index] = c.code.toByte() }
    }

    fun shortLe(offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    fun intLe(offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    val blockAlign = JavaFxWarmupChannels * JavaFxWarmupBitsPerSample / 8
    val byteRate = JavaFxWarmupSampleRateHz * blockAlign
    ascii(0, "RIFF")
    intLe(4, 36 + dataSize)
    ascii(8, "WAVE")
    ascii(12, "fmt ")
    intLe(16, 16)
    shortLe(20, 1)
    shortLe(22, JavaFxWarmupChannels)
    intLe(24, JavaFxWarmupSampleRateHz)
    intLe(28, byteRate)
    shortLe(32, blockAlign)
    shortLe(34, JavaFxWarmupBitsPerSample)
    ascii(36, "data")
    intLe(40, dataSize)
    return bytes
}

private const val WavHeaderBytes = 44
private const val JavaFxWarmupSampleRateHz = 8_000
private const val JavaFxWarmupDurationMs = 100
private const val JavaFxWarmupChannels = 1
private const val JavaFxWarmupBitsPerSample = 16
