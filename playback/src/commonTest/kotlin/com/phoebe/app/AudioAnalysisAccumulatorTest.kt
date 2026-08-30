package com.phoebe.app

import com.phoebe.app.domain.AudioAnalysisSource
import com.phoebe.app.player.AudioAnalysisAccumulator
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AudioAnalysisAccumulatorTest {
    @Test
    fun silenceProducesClampedEmptySpectrum() {
        val accumulator = AudioAnalysisAccumulator(bandCount = 8, minPublishIntervalMs = 0)

        val frame = accumulator.observePcm(FloatArray(512), sampleRateHz = 44_100f, timestampMs = 1_000L)

        assertNotNull(frame)
        assertEquals(0f, frame.amplitude)
        assertEquals(AudioAnalysisSource.Pcm, frame.source)
        assertEquals(8, frame.bands.size)
        assertTrue(frame.bands.all { it == 0f })
    }

    @Test
    fun toneMapsEnergyIntoABand() {
        val accumulator = AudioAnalysisAccumulator(bandCount = 16, minPublishIntervalMs = 0)
        val sampleRate = 44_100f
        val samples = FloatArray(1024) { index ->
            sin((2.0 * PI * 440.0 * index) / sampleRate).toFloat() * 0.8f
        }

        val frame = accumulator.observePcm(samples, sampleRateHz = sampleRate, timestampMs = 1_000L)

        assertNotNull(frame)
        assertTrue(frame.amplitude > 0.4f)
        assertTrue(frame.bands.maxOrNull()!! > 0.1f)
    }

    @Test
    fun magnitudeFramesClampAndDownsample() {
        val accumulator = AudioAnalysisAccumulator(bandCount = 4, minPublishIntervalMs = 0)

        val frame = accumulator.observeMagnitudesDb(
            magnitudesDb = floatArrayOf(-200f, -48f, -6f, 24f, 0f, -80f, -18f, -3f),
            timestampMs = 1_000L,
        )

        assertNotNull(frame)
        assertEquals(4, frame.bands.size)
        assertTrue(frame.amplitude in 0f..1f)
        assertTrue(frame.bands.all { it in 0f..1f })
    }

    @Test
    fun publishIntervalThrottlesFrames() {
        val accumulator = AudioAnalysisAccumulator(bandCount = 8, minPublishIntervalMs = 50)
        val samples = FloatArray(128) { 0.25f }

        assertNotNull(accumulator.observePcm(samples, sampleRateHz = 44_100f, timestampMs = 1_000L))
        assertEquals(null, accumulator.observePcm(samples, sampleRateHz = 44_100f, timestampMs = 1_020L))
        assertNotNull(accumulator.observePcm(samples, sampleRateHz = 44_100f, timestampMs = 1_050L))
    }

    @Test
    fun canPublishExposesThrottleWithoutAllocatingSamples() {
        val accumulator = AudioAnalysisAccumulator(bandCount = 8, minPublishIntervalMs = 50)
        val samples = FloatArray(128) { 0.25f }

        assertTrue(accumulator.canPublish(1_000L))
        assertNotNull(accumulator.observePcm(samples, sampleRateHz = 44_100f, timestampMs = 1_000L))
        assertEquals(false, accumulator.canPublish(1_020L))
        assertTrue(accumulator.canPublish(1_050L))
    }

    @Test
    fun quieterSameShapePcmDoesNotRenormalizeToFullScale() {
        val accumulator = AudioAnalysisAccumulator(bandCount = 16, minPublishIntervalMs = 0)
        val sampleRate = 44_100f
        fun tone(amplitude: Float) = FloatArray(1024) { index ->
            sin((2.0 * PI * 440.0 * index) / sampleRate).toFloat() * amplitude
        }

        val loud = accumulator.observePcm(tone(0.8f), sampleRateHz = sampleRate, timestampMs = 1_000L)
        val quiet = accumulator.observePcm(tone(0.2f), sampleRateHz = sampleRate, timestampMs = 1_016L)

        assertNotNull(loud)
        assertNotNull(quiet)
        val loudPeak = loud.bands.maxOrNull()!!
        val quietPeak = quiet.bands.maxOrNull()!!
        assertTrue(loudPeak > 0.5f)
        assertTrue(quietPeak < loudPeak)
        assertTrue(quietPeak < 0.95f)
    }

    @Test
    fun quieterSameShapePcmDecaysOverTime() {
        val accumulator = AudioAnalysisAccumulator(bandCount = 16, minPublishIntervalMs = 0)
        val sampleRate = 44_100f
        fun tone(amplitude: Float) = FloatArray(1024) { index ->
            sin((2.0 * PI * 440.0 * index) / sampleRate).toFloat() * amplitude
        }

        val loud = accumulator.observePcm(tone(0.8f), sampleRateHz = sampleRate, timestampMs = 1_000L)
        val soonAfter = accumulator.observePcm(tone(0.2f), sampleRateHz = sampleRate, timestampMs = 1_016L)
        val later = accumulator.observePcm(tone(0.2f), sampleRateHz = sampleRate, timestampMs = 1_120L)

        assertNotNull(loud)
        assertNotNull(soonAfter)
        assertNotNull(later)
        assertTrue(later.bands.maxOrNull()!! < soonAfter.bands.maxOrNull()!!)
    }

    @Test
    fun fallbackFramesAreDeterministicForSameSeedAndPosition() {
        val first = AudioAnalysisAccumulator.fallbackFrame(
            seed = "track-1",
            positionMs = 12_345L,
            isPlaying = true,
            timestampMs = 1_000L,
            bandCount = 12,
        )
        val second = AudioAnalysisAccumulator.fallbackFrame(
            seed = "track-1",
            positionMs = 12_345L,
            isPlaying = true,
            timestampMs = 2_000L,
            bandCount = 12,
        )
        val differentSeed = AudioAnalysisAccumulator.fallbackFrame(
            seed = "track-2",
            positionMs = 12_345L,
            isPlaying = true,
            timestampMs = 1_000L,
            bandCount = 12,
        )

        assertEquals(first.amplitude, second.amplitude)
        assertEquals(first.bands, second.bands)
        assertNotEquals(first.bands, differentSeed.bands)
    }
}
