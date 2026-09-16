package com.phoebe.app.player

import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Unthrottled PCM fan-out for projectM / Butterchurn.
 * Does not publish through Compose StateFlow — callers register sinks directly.
 */
object VisualizerPcmBus {
    @Volatile
    private var sinks: List<VisualizerPcmSink> = emptyList()

    fun addSink(sink: VisualizerPcmSink) {
        val current = sinks
        if (current.any { it === sink }) return
        sinks = current + sink
    }

    fun removeSink(sink: VisualizerPcmSink) {
        sinks = sinks.filterNot { it === sink }
    }

    fun publish(samples: FloatArray, channels: Int, sampleRateHz: Float) {
        if (samples.isEmpty()) return
        val snapshot = sinks
        for (sink in snapshot) {
            runCatching { sink.onPcm(samples, channels, sampleRateHz) }
        }
    }

    fun sinkCount(): Int = sinks.size
}

fun interface VisualizerPcmSink {
    fun onPcm(samples: FloatArray, channels: Int, sampleRateHz: Float)
}

/**
 * Musically plausible synthetic PCM for headless smoke / interop gates.
 * Production visualizer hosts do not use this — they freeze when audio pauses.
 */
class SyntheticVisualizerPcm(
    private val sampleRateHz: Float = 44_100f,
    private val channels: Int = 2,
    seed: Long = 0L,
) {
    private var phase = 0L
    private val random = Random(seed)

    fun nextBlock(frames: Int = 512): FloatArray {
        val out = FloatArray(frames * channels)
        val tempoHz = 2.0 + (seedNoise() * 0.15)
        for (frame in 0 until frames) {
            val t = (phase + frame) / sampleRateHz.toDouble()
            val kickEnv = kickEnvelope(t, tempoHz)
            val bass = 0.55 * kickEnv * sin(2.0 * PI * 55.0 * t)
            val mid = 0.18 * sin(2.0 * PI * 220.0 * t + 0.3 * sin(2.0 * PI * tempoHz * t))
            val high = 0.08 * sin(2.0 * PI * 880.0 * t) * (0.4 + 0.6 * kickEnv)
            val sample = (bass + mid + high).toFloat().coerceIn(-1f, 1f)
            val base = frame * channels
            out[base] = sample
            if (channels > 1) {
                out[base + 1] = sample * 0.92f
            }
        }
        phase += frames
        return out
    }

    fun publishBlock(frames: Int = 512) {
        VisualizerPcmBus.publish(nextBlock(frames), channels, sampleRateHz)
    }

    private fun kickEnvelope(t: Double, tempoHz: Double): Double {
        val beat = (t * tempoHz) % 1.0
        return if (beat < 0.12) {
            (1.0 - beat / 0.12).coerceIn(0.0, 1.0)
        } else {
            0.08
        }
    }

    private fun seedNoise(): Double = random.nextDouble(-1.0, 1.0)
}
