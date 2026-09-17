package com.phoebe.app.player

import kotlin.math.pow
import kotlin.math.sin

/**
 * Bridges JavaFX `AudioSpectrumListener` magnitudes into a PCM block for
 * [VisualizerPcmBus].
 *
 * On macOS/Windows, remote streams that JavaFX plays natively never reach the
 * ffmpeg/sampled PCM path, so the projectM host had no audio and rendered black.
 * JavaFX exposes no PCM tap, but it does hand us per-band dB magnitudes; we sum
 * one oscillator per band to approximate the same spectrum in the time domain.
 *
 * Linux keeps its real Pulse/ffmpeg PCM tap and Android/iOS their decoder taps, so
 * this synth only runs where no PCM source exists.
 */
internal class VisualizerSpectrumPcmSynth(
    private val sampleRateHz: Int = 44_100,
    private val minFrequencyHz: Double = 40.0,
    private val maxFrequencyHz: Double = 16_000.0,
) {
    private var phases: FloatArray = FloatArray(0)
    private var angularSteps: FloatArray = FloatArray(0)
    private var lastTimestampMs = Long.MIN_VALUE

    val rateHz: Float = sampleRateHz.toFloat()

    fun reset() {
        lastTimestampMs = Long.MIN_VALUE
    }

    fun render(magnitudesDb: FloatArray, timestampMs: Long): FloatArray? {
        if (magnitudesDb.isEmpty()) return null
        ensureBands(magnitudesDb.size)

        val elapsedMs = if (lastTimestampMs == Long.MIN_VALUE) {
            DefaultBlockMs
        } else {
            (timestampMs - lastTimestampMs).coerceIn(MinBlockMs, MaxBlockMs)
        }
        lastTimestampMs = timestampMs

        val frames = ((sampleRateHz.toLong() * elapsedMs) / 1000L)
            .toInt()
            .coerceIn(MinFrames, sampleRateHz / 4)
        if (frames <= 0) return null

        val amplitudes = FloatArray(magnitudesDb.size)
        var energy = 0.0
        for (band in magnitudesDb.indices) {
            val db = magnitudesDb[band]
            val linear = if (db.isFinite()) 10.0.pow(db.coerceIn(MinDb, MaxDb) / 20.0) else 0.0
            val amplitude = if (linear < SilenceFloor) 0f else linear.toFloat()
            amplitudes[band] = amplitude
            energy += (amplitude * amplitude).toDouble()
        }
        // Keep the summed oscillators inside [-1, 1] without flattening quiet passages.
        val normalization = if (energy <= 1e-9) 0f else (1.0 / kotlin.math.sqrt(energy)).toFloat()

        val stereo = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            var sample = 0f
            for (band in amplitudes.indices) {
                var phase = phases[band] + angularSteps[band]
                if (phase > TwoPi) phase -= TwoPi
                phases[band] = phase
                sample += amplitudes[band] * sin(phase.toDouble()).toFloat()
            }
            val value = (sample * normalization).coerceIn(-1f, 1f)
            stereo[frame * 2] = value
            stereo[frame * 2 + 1] = value
        }
        return stereo
    }

    private fun ensureBands(count: Int) {
        if (phases.size == count) return
        phases = FloatArray(count)
        angularSteps = FloatArray(count)
        val ratio = maxFrequencyHz / minFrequencyHz
        for (band in 0 until count) {
            val fraction = if (count == 1) 0.0 else band.toDouble() / (count - 1).toDouble()
            val frequency = minFrequencyHz * ratio.pow(fraction)
            angularSteps[band] = (TwoPi * frequency / sampleRateHz).toFloat()
            // Stagger phases so bands don't all start at zero and spike on the first block.
            phases[band] = (TwoPi * (band + 1) / (count + 1)).toFloat()
        }
    }

    private companion object {
        const val TwoPi = 6.2831855f
        const val DefaultBlockMs = 32L
        const val MinBlockMs = 8L
        const val MaxBlockMs = 120L
        const val MinFrames = 64
        const val MinDb = -80f
        const val MaxDb = 0f
        const val SilenceFloor = 1e-3
    }
}
