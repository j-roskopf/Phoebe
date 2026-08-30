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
            val envelope = envelopeFor(frame, bands)
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

        internal fun normalizedBands(frame: AudioAnalysisFrame, count: Int = BandCount): List<Float> =
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

        internal fun envelopeFor(frame: AudioAnalysisFrame, bands: List<Float>): Float {
            val peak = bands.maxOrNull() ?: 0f
            return max(frame.amplitude, peak).coerceIn(0f, 1f)
        }
    }
}

internal class WireframeSpectrumMesh(
    val vertices: List<WireframeVertex>,
    val horizontalSegments: List<WireframeSegment>,
    val diagonalSegments: List<WireframeSegment>,
    val centerSegments: List<WireframeSegment>,
    val columns: Int,
    val rows: Int,
) {
    fun updateHeights(
        bands: List<Float>,
        envelope: Float,
        phase: Float,
        isPlaying: Boolean,
    ) {
        val safeBands = if (bands.isEmpty()) List(columns) { 0f } else bands
        val motion = if (isPlaying) 1f else 0.18f
        var vertexIndex = 0
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
                vertices[vertexIndex].y = normalizedHeight
                vertices[vertexIndex].intensity = band
                vertices[vertexIndex + 1].y = -normalizedHeight
                vertices[vertexIndex + 1].intensity = band
                vertexIndex += 2
            }
        }
    }

    companion object {
        fun create(
            columns: Int = AudioVisualizerRenderState.BandCount,
            historyDepth: Int = AudioVisualizerRenderState.HistoryDepth,
        ): WireframeSpectrumMesh {
            val safeColumns = columns.coerceAtLeast(2)
            val rows = historyDepth.coerceAtLeast(2)
            val vertices = ArrayList<WireframeVertex>(safeColumns * rows * 2)
            repeat(rows) { row ->
                val z = row.toFloat() / (rows - 1).toFloat()
                repeat(safeColumns) { column ->
                    val x = column.toFloat() / (safeColumns - 1).toFloat()
                    vertices += WireframeVertex(x = x, y = 0.006f, z = z, intensity = 0f)
                    vertices += WireframeVertex(x = x, y = -0.006f, z = z, intensity = 0f)
                }
            }

            val horizontal = ArrayList<WireframeSegment>()
            val diagonal = ArrayList<WireframeSegment>()
            repeat(rows) { row ->
                val rowOffset = row * safeColumns * 2
                repeat(safeColumns - 1) { column ->
                    val index = rowOffset + column * 2
                    horizontal += WireframeSegment(index, index + 2)
                    horizontal += WireframeSegment(index + 1, index + 3)
                }
            }
            repeat(rows - 1) { row ->
                val rowOffset = row * safeColumns * 2
                val nextRowOffset = (row + 1) * safeColumns * 2
                repeat(safeColumns - 1) { column ->
                    val index = rowOffset + column * 2
                    val nextIndex = nextRowOffset + column * 2
                    diagonal += WireframeSegment(index, nextIndex + 2)
                    diagonal += WireframeSegment(index + 2, nextIndex)
                    diagonal += WireframeSegment(index + 1, nextIndex + 3)
                    diagonal += WireframeSegment(index + 3, nextIndex + 1)
                }
            }

            val center = List(safeColumns - 1) { column ->
                val index = column * 2
                WireframeSegment(index, index + 2)
            }

            return WireframeSpectrumMesh(
                vertices = vertices,
                horizontalSegments = horizontal,
                diagonalSegments = diagonal,
                centerSegments = center,
                columns = safeColumns,
                rows = rows,
            )
        }

        fun from(
            bands: List<Float>,
            envelope: Float,
            phase: Float,
            isPlaying: Boolean,
            historyDepth: Int = AudioVisualizerRenderState.HistoryDepth,
        ): WireframeSpectrumMesh {
            val columns = if (bands.isEmpty()) {
                AudioVisualizerRenderState.BandCount
            } else {
                bands.size.coerceAtLeast(2)
            }
            return create(columns = columns, historyDepth = historyDepth).also { mesh ->
                mesh.updateHeights(bands, envelope, phase, isPlaying)
            }
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

internal class WireframeVertex(
    val x: Float,
    var y: Float,
    val z: Float,
    var intensity: Float,
) {
    fun copy(
        x: Float = this.x,
        y: Float = this.y,
        z: Float = this.z,
        intensity: Float = this.intensity,
    ): WireframeVertex = WireframeVertex(x, y, z, intensity)
}

internal data class WireframeSegment(
    val from: Int,
    val to: Int,
)
