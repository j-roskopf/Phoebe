@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.phoebe.app.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.platform.PhoebeAppLifecycle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
internal object AndroidPlaybackDiagnostics {
    var diagnostics: PlaybackDiagnostics = PlaybackDiagnostics.None

    fun newPlayerBuilder(
        context: Context,
        engine: PlaybackEnginePath,
    ): ExoPlayer.Builder {
        diagnostics.engineSelected(engine)
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        return ExoPlayer.Builder(
            context,
            PhoebeRenderersFactory(
                context = context,
                diagnostics = diagnostics,
                engine = engine,
            ),
        ).setMediaSourceFactory(
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpDataSourceFactory)),
        ).setLoadControl(
            PhoebeLoadControlConfig.create(
                engine = engine,
                constrainedNetwork = context.hasConstrainedPlaybackNetwork(),
                uiVisible = PhoebeAppLifecycle.isUiVisible,
            ),
        )
    }

    fun reset() {
        diagnostics = PlaybackDiagnostics.None
    }
}

@OptIn(UnstableApi::class)
internal object PhoebeLoadControlConfig {
    const val RelaxedMainMinBufferMs = 15_000
    const val RelaxedMainMaxBufferMs = 75_000
    const val RelaxedMainTargetBufferBytes = 6 * 1024 * 1024
    // A metered network is often also the least reliable one. Keeping a modestly larger
    // runway here avoids a cycle of short re-buffers when reception briefly drops.
    const val ConstrainedMainMinBufferMs = 20_000
    const val ConstrainedMainMaxBufferMs = 90_000
    const val ConstrainedMainTargetBufferBytes = 4 * 1024 * 1024
    const val CrossfadeMinBufferMs = 2_000
    const val CrossfadeMaxBufferMs = 12_000
    const val CrossfadeTargetBufferBytes = 1 * 1024 * 1024
    const val BufferForPlaybackMs = 750
    const val BufferForPlaybackAfterRebufferMs = 5_000

    fun create(
        engine: PlaybackEnginePath,
        constrainedNetwork: Boolean = false,
        uiVisible: Boolean = true,
    ): DefaultLoadControl =
        create(profileFor(engine, constrainedNetwork, uiVisible))

    fun profileFor(
        engine: PlaybackEnginePath,
        constrainedNetwork: Boolean = false,
        uiVisible: Boolean = true,
    ): PhoebeLoadControlProfile =
        if (engine == PlaybackEnginePath.Media3Crossfade) {
            PhoebeLoadControlProfile(
                minBufferMs = CrossfadeMinBufferMs,
                maxBufferMs = CrossfadeMaxBufferMs,
                targetBufferBytes = CrossfadeTargetBufferBytes,
            )
        } else if (!uiVisible || constrainedNetwork) {
            PhoebeLoadControlProfile(
                minBufferMs = ConstrainedMainMinBufferMs,
                maxBufferMs = ConstrainedMainMaxBufferMs,
                targetBufferBytes = ConstrainedMainTargetBufferBytes,
            )
        } else {
            PhoebeLoadControlProfile(
                minBufferMs = RelaxedMainMinBufferMs,
                maxBufferMs = RelaxedMainMaxBufferMs,
                targetBufferBytes = RelaxedMainTargetBufferBytes,
            )
        }

    private fun create(profile: PhoebeLoadControlProfile): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.minBufferMs,
                profile.maxBufferMs,
                BufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs(profile),
            )
            .setTargetBufferBytes(profile.targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

    internal fun bufferForPlaybackAfterRebufferMs(profile: PhoebeLoadControlProfile): Int =
        minOf(BufferForPlaybackAfterRebufferMs, profile.minBufferMs)
}

internal data class PhoebeLoadControlProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val targetBufferBytes: Int,
)

internal object AndroidEqualizerState {
    @Volatile
    var profile: EqualizerProfile = EqualizerProfile.Default.normalized()
}

internal object AndroidAudioAnalysisState {
    @Volatile
    var sink: ((FloatArray, Float) -> Unit)? = null

    @Volatile
    var shouldSample: (() -> Boolean)? = null
}

@OptIn(UnstableApi::class)
internal fun Player.applyPhoebeAudioOffloadPreference() {
    // Audio offload bypasses custom AudioProcessors (including analysis + EQ processors),
    // so we always keep it disabled to ensure live visualizer data and EQ remain functional.
    trackSelectionParameters = trackSelectionParameters
        .buildUpon()
        .setAudioOffloadPreferences(
            TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(
                    TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
                )
                .build(),
        )
        .build()
}

private class PhoebeRenderersFactory(
    context: Context,
    private val diagnostics: PlaybackDiagnostics,
    private val engine: PlaybackEnginePath,
) : DefaultRenderersFactory(context) {
    init {
        // Recover from hardware codec failures instead of leaving MediaCodec in a fatal state.
        setEnableDecoderFallback(true)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(
                buildList<AudioProcessor> {
                    add(AndroidAudioAnalysisAudioProcessor(AndroidAudioAnalysisState))
                    add(AndroidEqualizerAudioProcessor(AndroidEqualizerState))
                    if (diagnostics !== PlaybackDiagnostics.None) {
                        add(DiagnosticAudioProcessor(diagnostics, engine))
                    }
                }.toTypedArray(),
            )
            .build()
}

private class AndroidAudioAnalysisAudioProcessor(
    private val analysisState: AndroidAudioAnalysisState,
) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (Util.isEncodingLinearPcm(inputAudioFormat.encoding)) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return
        val sink = analysisState.sink
        if (sink != null && analysisState.shouldSample?.invoke() != false) {
            val probe = inputBuffer.asReadOnlyBuffer()
            media3PcmSamples(
                buffer = probe,
                encoding = inputAudioFormat.encoding,
                channelCount = inputAudioFormat.channelCount,
            )?.let { samples ->
                sink(samples, inputAudioFormat.sampleRate.toFloat())
            }
        }
        val output = replaceOutputBuffer(remaining)
        output.put(inputBuffer)
        output.flip()
    }
}

private class AndroidEqualizerAudioProcessor(
    private val equalizerState: AndroidEqualizerState,
) : BaseAudioProcessor() {
    private var processor: GraphicEqualizerProcessor? = null
    private var processorProfile: EqualizerProfile? = null
    private var processorSampleRate = 0
    private var processorChannelCount = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (Util.isEncodingLinearPcm(inputAudioFormat.encoding)) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return
        val profile = equalizerState.profile.normalized()
        val sampleBytes = media3SampleBytes(inputAudioFormat.encoding)
        if (!GraphicEqualizerProcessor.isActive(profile) ||
            sampleBytes == null ||
            inputAudioFormat.channelCount <= 0 ||
            inputAudioFormat.sampleRate <= 0 ||
            inputAudioFormat.encoding !in supportedEqualizerEncodings
        ) {
            val output = replaceOutputBuffer(remaining)
            output.put(inputBuffer)
            output.flip()
            return
        }

        val eq = equalizerProcessor(profile)
        val input = inputBuffer.order(byteOrderForEncoding(inputAudioFormat.encoding))
        val output = replaceOutputBuffer(remaining).order(byteOrderForEncoding(inputAudioFormat.encoding))
        val channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        val frameSize = sampleBytes * channelCount
        while (input.remaining() >= frameSize) {
            repeat(channelCount) { channel ->
                val sample = when (inputAudioFormat.encoding) {
                    C.ENCODING_PCM_FLOAT -> input.float.coerceIn(-1f, 1f)
                    else -> input.short.toFloat() / 32768f
                }
                val processed = eq.process(channel, sample).coerceIn(-1f, 1f)
                when (inputAudioFormat.encoding) {
                    C.ENCODING_PCM_FLOAT -> output.putFloat(processed)
                    else -> output.putShort((processed * 32767f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
            }
        }
        while (input.hasRemaining()) {
            output.put(input.get())
        }
        output.flip()
    }

    override fun onFlush() {
        resetProcessor()
    }

    override fun onReset() {
        resetProcessor()
    }

    private fun equalizerProcessor(profile: EqualizerProfile): GraphicEqualizerProcessor {
        val needsNew = processor == null ||
            processorProfile != profile ||
            processorSampleRate != inputAudioFormat.sampleRate ||
            processorChannelCount != inputAudioFormat.channelCount
        if (needsNew) {
            processor = GraphicEqualizerProcessor(
                sampleRateHz = inputAudioFormat.sampleRate.toFloat(),
                channelCount = inputAudioFormat.channelCount.coerceAtLeast(1),
                profile = profile,
            )
            processorProfile = profile
            processorSampleRate = inputAudioFormat.sampleRate
            processorChannelCount = inputAudioFormat.channelCount
        }
        return processor ?: GraphicEqualizerProcessor(
            sampleRateHz = inputAudioFormat.sampleRate.toFloat(),
            channelCount = inputAudioFormat.channelCount.coerceAtLeast(1),
            profile = profile,
        )
    }

    private fun resetProcessor() {
        processor = null
        processorProfile = null
        processorSampleRate = 0
        processorChannelCount = 0
    }
}

private class DiagnosticAudioProcessor(
    private val diagnostics: PlaybackDiagnostics,
    private val engine: PlaybackEnginePath,
) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (Util.isEncodingLinearPcm(inputAudioFormat.encoding)) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val probeBuffer = inputBuffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        val rms = media3PcmRms(
            buffer = probeBuffer,
            encoding = inputAudioFormat.encoding,
            channelCount = inputAudioFormat.channelCount,
        )
        if (rms > 0.0 && rms.isFinite()) {
            diagnostics.decodedAudioEnergy(engine, rms)
        }

        val output = replaceOutputBuffer(remaining)
        output.put(inputBuffer)
        output.flip()
    }
}

private fun media3PcmRms(
    buffer: ByteBuffer,
    encoding: Int,
    channelCount: Int,
): Double {
    val sampleBytes = media3SampleBytes(encoding) ?: return 0.0
    val frameSize = sampleBytes * channelCount.coerceAtLeast(1)
    if (frameSize <= 0) return 0.0
    val start = buffer.position()
    val end = buffer.limit()
    var frameOffset = start
    var sumSquares = 0.0
    var sampleCount = 0L
    while (frameOffset + frameSize <= end) {
        var sampleOffset = frameOffset
        repeat(channelCount.coerceAtLeast(1)) {
            val normalized = media3NormalizedSample(buffer, sampleOffset, encoding)
            sumSquares += normalized * normalized
            sampleCount++
            sampleOffset += sampleBytes
        }
        frameOffset += frameSize
    }
    return if (sampleCount == 0L) 0.0 else kotlin.math.sqrt(sumSquares / sampleCount.toDouble())
}

private fun media3PcmSamples(
    buffer: ByteBuffer,
    encoding: Int,
    channelCount: Int,
    maxSamples: Int = 2048,
): FloatArray? {
    val sampleBytes = media3SampleBytes(encoding) ?: return null
    val channels = channelCount.coerceAtLeast(1)
    val frameSize = sampleBytes * channels
    if (frameSize <= 0) return null
    val start = buffer.position()
    val end = buffer.limit()
    val frameCount = ((end - start) / frameSize).coerceAtLeast(0)
    val totalSamples = frameCount * channels
    if (totalSamples <= 0) return null
    val stride = (totalSamples / maxSamples.coerceAtLeast(1)).coerceAtLeast(1)
    val outputSize = ((totalSamples + stride - 1) / stride).coerceAtMost(maxSamples.coerceAtLeast(1))
    val output = FloatArray(outputSize)
    var frameOffset = start
    var sampleOrdinal = 0
    var outputIndex = 0
    while (frameOffset + frameSize <= end && outputIndex < output.size) {
        var sampleOffset = frameOffset
        repeat(channels) {
            if (sampleOrdinal % stride == 0 && outputIndex < output.size) {
                output[outputIndex++] = media3NormalizedSample(buffer, sampleOffset, encoding).toFloat()
            }
            sampleOrdinal++
            sampleOffset += sampleBytes
        }
        frameOffset += frameSize
    }
    return if (outputIndex == output.size) output else output.copyOf(outputIndex)
}

private fun media3SampleBytes(encoding: Int): Int? =
    when (encoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT,
        C.ENCODING_PCM_16BIT_BIG_ENDIAN,
        -> 2
        C.ENCODING_PCM_24BIT,
        C.ENCODING_PCM_24BIT_BIG_ENDIAN,
        -> 3
        C.ENCODING_PCM_32BIT,
        C.ENCODING_PCM_32BIT_BIG_ENDIAN,
        C.ENCODING_PCM_FLOAT,
        -> 4
        else -> null
    }

private val supportedEqualizerEncodings = setOf(
    C.ENCODING_PCM_16BIT,
    C.ENCODING_PCM_16BIT_BIG_ENDIAN,
    C.ENCODING_PCM_FLOAT,
)

private fun byteOrderForEncoding(encoding: Int): ByteOrder =
    when (encoding) {
        C.ENCODING_PCM_16BIT_BIG_ENDIAN -> ByteOrder.BIG_ENDIAN
        else -> ByteOrder.LITTLE_ENDIAN
    }

private fun media3NormalizedSample(
    buffer: ByteBuffer,
    offset: Int,
    encoding: Int,
): Double {
    return when (encoding) {
        C.ENCODING_PCM_8BIT -> ((buffer.get(offset).toInt() and 0xFF) - 128).toDouble() / 128.0
        C.ENCODING_PCM_16BIT -> signedPcm(buffer, offset, 2, littleEndian = true).toDouble() / 32768.0
        C.ENCODING_PCM_16BIT_BIG_ENDIAN -> signedPcm(buffer, offset, 2, littleEndian = false).toDouble() / 32768.0
        C.ENCODING_PCM_24BIT -> signedPcm(buffer, offset, 3, littleEndian = true).toDouble() / 8388608.0
        C.ENCODING_PCM_24BIT_BIG_ENDIAN -> signedPcm(buffer, offset, 3, littleEndian = false).toDouble() / 8388608.0
        C.ENCODING_PCM_32BIT -> signedPcm(buffer, offset, 4, littleEndian = true).toDouble() / 2147483648.0
        C.ENCODING_PCM_32BIT_BIG_ENDIAN -> signedPcm(buffer, offset, 4, littleEndian = false).toDouble() / 2147483648.0
        C.ENCODING_PCM_FLOAT -> buffer.order(ByteOrder.nativeOrder()).getFloat(offset).toDouble().coerceIn(-1.0, 1.0)
        else -> 0.0
    }
}

internal fun Context.hasConstrainedPlaybackNetwork(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
    val network = connectivityManager.activeNetwork ?: return true
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return true
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return true
    return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

private fun signedPcm(
    buffer: ByteBuffer,
    offset: Int,
    sampleBytes: Int,
    littleEndian: Boolean,
): Long {
    var value = 0L
    repeat(sampleBytes) { index ->
        val sourceIndex = if (littleEndian) offset + index else offset + sampleBytes - 1 - index
        value = value or ((buffer.get(sourceIndex).toLong() and 0xFFL) shl (8 * index))
    }
    val shift = 64 - sampleBytes * 8
    return (value shl shift) shr shift
}
