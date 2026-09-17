package com.phoebe.app

import com.phoebe.app.player.VisualizerSpectrumPcmSynth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisualizerSpectrumPcmSynthTest {
    @Test
    fun rendersStereoBlockSizedToElapsedTime() {
        val synth = VisualizerSpectrumPcmSynth(sampleRateHz = 44_100)
        val block = synth.render(FloatArray(32) { -20f }, timestampMs = 0L)
        assertTrue(block != null)
        // First block assumes the default 32 ms window => ~1411 stereo frames.
        assertTrue(block!!.size % 2 == 0, "stereo interleaved")
        assertTrue(block.size in 2_000..3_000, "unexpected block size ${block.size / 2}")
    }

    @Test
    fun silentSpectrumProducesSilence() {
        val synth = VisualizerSpectrumPcmSynth()
        val block = synth.render(FloatArray(32) { -80f }, timestampMs = 0L)!!
        assertTrue(block.all { it == 0f }, "silence should map to zeros")
    }

    @Test
    fun loudSpectrumProducesAudibleEnergyWithinRange() {
        val synth = VisualizerSpectrumPcmSynth()
        val block = synth.render(FloatArray(32) { 0f }, timestampMs = 0L)!!
        assertTrue(block.any { it != 0f }, "expected non-zero samples")
        assertTrue(block.all { it in -1f..1f }, "samples must stay within [-1, 1]")
        val left = block.filterIndexed { index, _ -> index % 2 == 0 }
        val right = block.filterIndexed { index, _ -> index % 2 == 1 }
        assertEquals(left, right, "channels should be identical")
    }

    @Test
    fun emptySpectrumIsIgnored() {
        val synth = VisualizerSpectrumPcmSynth()
        assertTrue(synth.render(FloatArray(0), timestampMs = 0L) == null)
    }
}
