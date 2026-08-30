package com.phoebe.app.feature.playback

import com.phoebe.app.domain.AudioAnalysisFrame
import com.phoebe.app.domain.AudioAnalysisSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioVisualizerRenderStateTest {
    @Test
    fun renderStateClampsExtremeInput() {
        val state = AudioVisualizerRenderState.from(
            frame = AudioAnalysisFrame(
                amplitude = 2f,
                bands = listOf(-1f, 0.25f, 4f),
                timestampMs = 42L,
                source = AudioAnalysisSource.Pcm,
            ),
            positionMs = -100L,
            isPlaying = true,
        )

        assertEquals(AudioVisualizerRenderState.BandCount, state.bands.size)
        assertTrue(state.envelope in 0f..1f)
        assertTrue(state.bands.all { it in 0f..1f })
        assertEquals(0f, state.phase)
    }

    @Test
    fun meshHasStableMirroredGeometry() {
        val state = AudioVisualizerRenderState.from(
            frame = AudioAnalysisFrame(
                amplitude = 0.55f,
                bands = List(128) { index -> ((index * 17) % 100) / 100f },
                timestampMs = 42L,
                source = AudioAnalysisSource.Pcm,
            ),
            positionMs = 96_000L,
            isPlaying = true,
        )
        val columns = AudioVisualizerRenderState.BandCount
        val rows = AudioVisualizerRenderState.HistoryDepth

        assertEquals(columns * rows * 2, state.mesh.vertices.size)
        assertEquals(rows * (columns - 1) * 2, state.mesh.horizontalSegments.size)
        assertEquals((rows - 1) * (columns - 1) * 4, state.mesh.diagonalSegments.size)
        assertEquals(columns - 1, state.mesh.centerSegments.size)

        state.mesh.vertices.chunked(2).forEach { pair ->
            assertEquals(pair[0].x, pair[1].x)
            assertEquals(pair[0].z, pair[1].z)
            assertTrue(abs(pair[0].y + pair[1].y) < 0.0001f)
        }
    }

    @Test
    fun meshPlacesEnergyInMirroredLobesInsteadOfOneSidedTail() {
        val state = AudioVisualizerRenderState.from(
            frame = AudioAnalysisFrame(
                amplitude = 0.45f,
                bands = List(128) { index -> if (index < 16) 1f else 0.12f },
                timestampMs = 42L,
                source = AudioAnalysisSource.Pcm,
            ),
            positionMs = 24_000L,
            isPlaying = true,
        )
        val columns = AudioVisualizerRenderState.BandCount

        fun upperY(column: Int): Float = state.mesh.vertices[column * 2].y

        val leftLobe = upperY(columns / 4)
        val rightLobe = upperY((columns * 3) / 4)
        val center = upperY(columns / 2)
        val rightEdge = upperY(columns - 1)

        assertTrue(leftLobe > center * 1.7f)
        assertTrue(rightLobe > center * 1.7f)
        assertTrue(rightLobe > rightEdge * 8f)
        assertTrue(abs(leftLobe - rightLobe) < 0.18f)
    }

    @Test
    fun meshKeepsTopologyWhenHeightsUpdate() {
        val mesh = WireframeSpectrumMesh.create()
        mesh.updateHeights(
            bands = List(AudioVisualizerRenderState.BandCount) { 0.12f },
            envelope = 0.2f,
            phase = 0f,
            isPlaying = true,
        )
        val xs = mesh.vertices.map { it.x }
        val zs = mesh.vertices.map { it.z }
        val signs = mesh.vertices.map { it.y > 0f }
        val horizontal = mesh.horizontalSegments.toList()
        val lobeIndex = (AudioVisualizerRenderState.BandCount / 4) * 2
        val firstHeight = mesh.vertices[lobeIndex].y

        mesh.updateHeights(
            bands = List(AudioVisualizerRenderState.BandCount) { 0.95f },
            envelope = 0.9f,
            phase = 1.6f,
            isPlaying = true,
        )

        assertEquals(xs, mesh.vertices.map { it.x })
        assertEquals(zs, mesh.vertices.map { it.z })
        assertEquals(signs, mesh.vertices.map { it.y > 0f })
        assertEquals(horizontal, mesh.horizontalSegments)
        assertTrue(mesh.vertices[lobeIndex].y > firstHeight)
        assertTrue(mesh.vertices[lobeIndex].y > 0f)
        assertTrue(mesh.vertices[lobeIndex + 1].y < 0f)
    }

    @Test
    fun envelopeFollowsPeakBandNotAverage() {
        val frame = AudioAnalysisFrame(
            amplitude = 0.2f,
            bands = List(64) { index -> if (index == 8) 1f else 0.05f },
            timestampMs = 42L,
            source = AudioAnalysisSource.Pcm,
        )
        val bands = AudioVisualizerRenderState.normalizedBands(frame)
        val envelope = AudioVisualizerRenderState.envelopeFor(frame, bands)
        assertTrue(envelope > 0.9f)
    }
}
