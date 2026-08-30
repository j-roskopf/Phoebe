package com.phoebe.app.player

import com.phoebe.app.domain.AudioAnalysisFrame
import com.phoebe.app.domain.AudioAnalysisSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class AudioAnalysisAccumulator(
    private val bandCount: Int = DefaultBandCount,
    private val minPublishIntervalMs: Long = DefaultPublishIntervalMs,
) {
    private var lastPublishedAtMs = Long.MIN_VALUE
    private var smoothedBands = FloatArray(bandsSize())

    fun reset() {
        lastPublishedAtMs = Long.MIN_VALUE
        smoothedBands = FloatArray(bandsSize())
    }

    fun observePcm(
        samples: FloatArray,
        sampleRateHz: Float,
        timestampMs: Long,
        source: AudioAnalysisSource = AudioAnalysisSource.Pcm,
    ): AudioAnalysisFrame? {
        if (!canPublish(timestampMs)) return null
        if (samples.isEmpty() || sampleRateHz <= 0f) {
            return publish(AudioAnalysisFrame(timestampMs = timestampMs, source = source))
        }
        val trimmed = recentWindow(samples, AnalysisSampleLimit)
        var sumSquares = 0.0
        for (index in trimmed.indices) {
            val coerced = trimmed[index].coerceIn(-1f, 1f)
            trimmed[index] = coerced
            sumSquares += coerced * coerced
        }
        val amplitude = sqrt(sumSquares / trimmed.size.toDouble()).toFloat().coerceIn(0f, 1f)
        val raw = frequencyBands(trimmed, sampleRateHz)
        val bands = shapeBands(raw, timestampMs)
        return publish(
            AudioAnalysisFrame(
                amplitude = amplitude,
                bands = bands,
                timestampMs = timestampMs,
                source = source,
            ),
        )
    }

    fun observeMagnitudesDb(
        magnitudesDb: FloatArray,
        timestampMs: Long,
        source: AudioAnalysisSource = AudioAnalysisSource.Spectrum,
    ): AudioAnalysisFrame? {
        if (!canPublish(timestampMs)) return null
        if (magnitudesDb.isEmpty()) {
            return publish(AudioAnalysisFrame(timestampMs = timestampMs, source = source))
        }
        val raw = FloatArray(bandsSize()) { band ->
            val start = (band * magnitudesDb.size) / bandsSize()
            val end = (((band + 1) * magnitudesDb.size) / bandsSize()).coerceAtLeast(start + 1)
            var peak = 0f
            for (index in start until end.coerceAtMost(magnitudesDb.size)) {
                peak = max(peak, magnitudeDbToUnit(magnitudesDb[index]))
            }
            peak
        }
        // JavaFX/GStreamer spectrum is already temporally smoothed. A second
        // attack/release pass made kicks look even rarer than the song.
        val bands = raw.map { it.coerceIn(0f, 1f) }
        val amplitude = sqrt(bands.fold(0.0) { acc, band -> acc + band * band } / bands.size).toFloat()
        return publish(
            AudioAnalysisFrame(
                amplitude = amplitude.coerceIn(0f, 1f),
                bands = bands,
                timestampMs = timestampMs,
                source = source,
            ),
        )
    }

    internal fun canPublish(timestampMs: Long): Boolean =
        lastPublishedAtMs == Long.MIN_VALUE ||
            timestampMs - lastPublishedAtMs >= minPublishIntervalMs ||
            timestampMs < lastPublishedAtMs

    private fun publish(frame: AudioAnalysisFrame): AudioAnalysisFrame {
        lastPublishedAtMs = frame.timestampMs
        return frame.normalized(bandCount)
    }

    private fun bandsSize(): Int = bandCount.coerceAtLeast(1)

    private fun frequencyBands(samples: FloatArray, sampleRateHz: Float): FloatArray {
        val n = AnalysisSampleLimit
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val copy = minOf(n, samples.size)
        for (index in 0 until copy) {
            re[index] = samples[index].toDouble()
        }
        fftRadix2(re, im)
        val nyquistBins = n / 2
        val count = bandsSize()
        return FloatArray(count) { index ->
            val frequencyHz = logFrequency(index, count)
            val bin = ((frequencyHz / sampleRateHz) * n).toInt().coerceIn(1, nyquistBins - 1)
            val magnitude = sqrt(re[bin] * re[bin] + im[bin] * im[bin]).toFloat() / n
            magnitudeDbToUnit(linearMagnitudeToDb(magnitude))
        }
    }

    private fun shapeBands(raw: FloatArray, timestampMs: Long): List<Float> {
        val count = bandsSize()
        if (smoothedBands.size != count) {
            smoothedBands = FloatArray(count)
        }
        val dtSeconds = if (lastPublishedAtMs == Long.MIN_VALUE) {
            minPublishIntervalMs.coerceAtLeast(1L) / 1_000f
        } else {
            (timestampMs - lastPublishedAtMs).coerceAtLeast(0L) / 1_000f
        }
        val release = 1f - 0.5.pow((dtSeconds / BandReleaseHalfLifeSeconds).toDouble()).toFloat()
        for (index in 0 until count) {
            val target = raw.getOrElse(index) { 0f }.coerceIn(0f, 1f)
            val current = smoothedBands[index]
            val mix = if (target >= current) BandAttack else release
            smoothedBands[index] = (current + (target - current) * mix).coerceIn(0f, 1f)
        }
        return smoothedBands.toList()
    }

    private fun logFrequency(index: Int, count: Int): Float {
        val min = MinFrequencyHz
        val max = MaxFrequencyHz
        val t = if (count <= 1) 0f else index.toFloat() / (count - 1).toFloat()
        return (min * (max / min).pow(t)).coerceIn(min, max)
    }

    companion object {
        const val DefaultBandCount = 128
        const val DefaultPublishIntervalMs = 8L
        internal const val AnalysisSampleLimit = 256
        private const val MinFrequencyHz = 60f
        private const val MaxFrequencyHz = 12_000f
        private const val BandReleaseHalfLifeSeconds = 0.045f
        private const val BandAttack = 1f

        fun fallbackFrame(
            seed: String,
            positionMs: Long,
            isPlaying: Boolean,
            timestampMs: Long,
            bandCount: Int = DefaultBandCount,
        ): AudioAnalysisFrame {
            val phase = positionMs.coerceAtLeast(0L).toFloat() / 680f
            val seedHash = seed.fold(0) { acc, c -> acc * 31 + c.code }
            val seedPhase = (((seedHash % 997) + 997) % 997) * 0.013f
            val pulse = if (isPlaying) 0.42f + 0.28f * sin(phase) else 0.12f
            val bands = List(bandCount.coerceAtLeast(1)) { index ->
                val local = sin(phase * (0.65f + index * 0.018f) + index * 0.71f + seedPhase)
                val ripple = cos(phase * 0.43f + index * 0.37f)
                (0.18f + pulse * 0.55f + local * 0.17f + ripple * 0.10f).coerceIn(0.04f, 1f)
            }
            return AudioAnalysisFrame(
                amplitude = pulse.coerceIn(0f, 1f),
                bands = bands,
                timestampMs = timestampMs.coerceAtLeast(0L),
                source = AudioAnalysisSource.None,
            )
        }
    }
}

private fun fftRadix2(re: DoubleArray, im: DoubleArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            val swapRe = re[i]
            re[i] = re[j]
            re[j] = swapRe
            val swapIm = im[i]
            im[i] = im[j]
            im[j] = swapIm
        }
    }
    var len = 2
    while (len <= n) {
        val angle = -2.0 * PI / len
        val wLenRe = cos(angle)
        val wLenIm = sin(angle)
        var start = 0
        while (start < n) {
            var wRe = 1.0
            var wIm = 0.0
            val half = len / 2
            for (k in 0 until half) {
                val even = start + k
                val odd = even + half
                val tRe = re[odd] * wRe - im[odd] * wIm
                val tIm = re[odd] * wIm + im[odd] * wRe
                re[odd] = re[even] - tRe
                im[odd] = im[even] - tIm
                re[even] += tRe
                im[even] += tIm
                val nextWRe = wRe * wLenRe - wIm * wLenIm
                wIm = wRe * wLenIm + wIm * wLenRe
                wRe = nextWRe
            }
            start += len
        }
        len = len shl 1
    }
}

private fun linearMagnitudeToDb(magnitude: Float): Float {
    if (!magnitude.isFinite() || magnitude <= 1e-8f) return -80f
    return (20.0 * log10(magnitude.toDouble())).toFloat()
}

private fun magnitudeDbToUnit(db: Float): Float {
    if (!db.isFinite()) return 0f
    return ((db.coerceIn(-80f, 0f) + 80f) / 80f).coerceIn(0f, 1f)
}

private fun recentWindow(samples: FloatArray, maxSamples: Int): FloatArray {
    if (samples.size <= maxSamples) return samples.copyOf()
    return samples.copyOfRange(samples.size - maxSamples, samples.size)
}
