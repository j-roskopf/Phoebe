package com.phoebe.app.feature.playback

import com.phoebe.app.domain.AudioAnalysisFrame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

internal data class AudioVisualizerRenderState(
    val bands: List<Float>,
    val envelope: Float,
    val phase: Float,
    val mesh: WireframeSpectrumMesh,
) {
    companion object {
        const val BandCount = 64
        const val HistoryDepth = 18

        fun from(
            frame: AudioAnalysisFrame,
            positionMs: Long,
            isPlaying: Boolean,
        ): AudioVisualizerRenderState {
            val bands = normalizedBands(frame, BandCount)
            val envelope = if (bands.isEmpty()) {
                frame.amplitude
            } else {
                max(frame.amplitude, bands.average().toFloat())
            }.coerceIn(0f, 1f)
            val phase = positionMs.coerceAtLeast(0L) / 1000f
            return AudioVisualizerRenderState(
                bands = bands,
                envelope = envelope,
                phase = phase,
                mesh = WireframeSpectrumMesh.from(
                    bands = bands,
                    envelope = envelope,
                    phase = phase,
                    isPlaying = isPlaying,
                ),
            )
        }

        private fun normalizedBands(frame: AudioAnalysisFrame, count: Int): List<Float> =
            List(count.coerceAtLeast(1)) { index ->
                val raw = if (frame.bands.isEmpty()) {
                    frame.amplitude
                } else {
                    val sourceIndex = ((index.toFloat() / count) * frame.bands.size)
                        .toInt()
                        .coerceIn(frame.bands.indices)
                    frame.bands[sourceIndex]
                }
                raw.coerceIn(0f, 1f)
            }
    }
}

internal data class WireframeSpectrumMesh(
    val vertices: List<WireframeVertex>,
    val horizontalSegments: List<WireframeSegment>,
    val diagonalSegments: List<WireframeSegment>,
    val centerSegments: List<WireframeSegment>,
) {
    companion object {
        fun from(
            bands: List<Float>,
            envelope: Float,
            phase: Float,
            isPlaying: Boolean,
            historyDepth: Int = AudioVisualizerRenderState.HistoryDepth,
        ): WireframeSpectrumMesh {
            val safeBands = if (bands.isEmpty()) List(AudioVisualizerRenderState.BandCount) { 0f } else bands
            val columns = safeBands.size
            val rows = historyDepth.coerceAtLeast(2)
            val motion = if (isPlaying) 1f else 0.18f
            val vertices = ArrayList<WireframeVertex>(columns * rows * 2)

            repeat(rows) { row ->
                val z = row.toFloat() / (rows - 1).toFloat()
                val recency = (1f - z).coerceIn(0f, 1f)
                repeat(columns) { column ->
                    val x = column.toFloat() / (columns - 1).toFloat()
                    val distanceFromCenter = abs((x * 2f) - 1f)
                    val lobeProfile = sin(distanceFromCenter * PI.toFloat())
                        .coerceAtLeast(0f)
                        .pow(0.55f)
                    val centerProfile = (1f - distanceFromCenter).coerceIn(0f, 1f).pow(2.2f)
                    val edgeDamping = sin(x * PI.toFloat()).coerceAtLeast(0f).pow(0.72f)
                    val lowBand = safeBands.sampleAt((1f - lobeProfile) * 0.18f)
                    val midBand = safeBands.sampleAt(0.34f + centerProfile * 0.2f)
                    val highBand = safeBands.sampleAt(0.76f + centerProfile * 0.18f)
                    val energy = lowBand.pow(1.18f) * lobeProfile * 0.72f +
                        midBand.pow(1.12f) * centerProfile * 0.16f +
                        highBand.pow(1.05f) * (1f - lobeProfile) * 0.06f
                    val band = (lowBand * lobeProfile + midBand * centerProfile * 0.35f + highBand * 0.12f)
                        .coerceIn(0f, 1f)
                    val ripple = sin(phase * (0.85f + x * 1.7f) - row * 0.72f + column * 0.17f) *
                        0.045f *
                        motion *
                        (0.28f + lobeProfile * 0.72f)
                    val height = (
                        0.014f +
                            energy +
                            envelope * (0.026f + lobeProfile * 0.068f) +
                            ripple
                        ) * edgeDamping * (0.34f + recency * 0.86f)
                    val normalizedHeight = height.coerceIn(0.006f, 1f)
                    vertices += WireframeVertex(x = x, y = normalizedHeight, z = z, intensity = band)
                    vertices += WireframeVertex(x = x, y = -normalizedHeight, z = z, intensity = band)
                }
            }

            val horizontal = ArrayList<WireframeSegment>()
            val diagonal = ArrayList<WireframeSegment>()
            repeat(rows) { row ->
                val rowOffset = row * columns * 2
                repeat(columns - 1) { column ->
                    val index = rowOffset + column * 2
                    horizontal += WireframeSegment(index, index + 2)
                    horizontal += WireframeSegment(index + 1, index + 3)
                }
            }
            repeat(rows - 1) { row ->
                val rowOffset = row * columns * 2
                val nextRowOffset = (row + 1) * columns * 2
                repeat(columns - 1) { column ->
                    val index = rowOffset + column * 2
                    val nextIndex = nextRowOffset + column * 2
                    diagonal += WireframeSegment(index, nextIndex + 2)
                    diagonal += WireframeSegment(index + 2, nextIndex)
                    diagonal += WireframeSegment(index + 1, nextIndex + 3)
                    diagonal += WireframeSegment(index + 3, nextIndex + 1)
                }
            }

            val center = List(columns - 1) { column ->
                val index = column * 2
                WireframeSegment(index, index + 2)
            }

            return WireframeSpectrumMesh(
                vertices = vertices,
                horizontalSegments = horizontal,
                diagonalSegments = diagonal,
                centerSegments = center,
            )
        }
    }
}

private fun List<Float>.sampleAt(position: Float): Float {
    if (isEmpty()) return 0f
    if (size == 1) return first().coerceIn(0f, 1f)
    val scaled = position.coerceIn(0f, 1f) * (lastIndex)
    val lowerIndex = floor(scaled).toInt().coerceIn(indices)
    val upperIndex = (lowerIndex + 1).coerceIn(indices)
    val t = scaled - lowerIndex
    return (this[lowerIndex] * (1f - t) + this[upperIndex] * t).coerceIn(0f, 1f)
}

internal data class WireframeVertex(
    val x: Float,
    val y: Float,
    val z: Float,
    val intensity: Float,
)

internal data class WireframeSegment(
    val from: Int,
    val to: Int,
)
