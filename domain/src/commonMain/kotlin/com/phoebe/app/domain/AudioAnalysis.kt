package com.phoebe.app.domain

import kotlinx.serialization.Serializable

@Serializable
enum class NowPlayingVisualizerPreset(
    val label: String,
    val familyLabel: String = label,
) {
    Artwork("Artwork"),
    Alchemy("Alchemy"),
    Battery("Battery"),
    BarsAndWaves("Bars & Waves"),
    BlazingColors("Blazing Colors"),
    Plenoptic("Plenoptic"),
    VortexSpectrum("Vortex Spectrum"),
    ClassicEQ("Classic EQ"),
    HaloSpectrum("Halo Spectrum"),
    WireframeSpectrum3D("Wireframe Spectrum 3D", familyLabel = "3D visualizer"),
    CanyonWire3D("Canyon Wire 3D", familyLabel = "3D visualizer"),
    PulseTunnel3D("Pulse Tunnel 3D", familyLabel = "3D visualizer"),
    OrbitalHalo3D("Orbital Halo 3D", familyLabel = "3D visualizer"),
    SpiralGalaxy3D("Spiral Galaxy 3D", familyLabel = "3D visualizer"),
    AuroraRibbon3D("Aurora Ribbon 3D", familyLabel = "3D visualizer"),
    CrystalPeaks3D("Crystal Peaks 3D", familyLabel = "3D visualizer"),
    PrismFan3D("Prism Fan 3D", familyLabel = "3D visualizer"),
    WaveRibbon3D("Wave Ribbon 3D", familyLabel = "3D visualizer"),
    KaleidoscopeWeb3D("Kaleidoscope Web 3D", familyLabel = "3D visualizer"),
    StarfieldWeb3D("Starfield Web 3D", familyLabel = "3D visualizer");

    val isVisualizer: Boolean
        get() = this != Artwork

    companion object {
        val Default = Artwork

        fun fromStoredName(value: String?): NowPlayingVisualizerPreset =
            entries.firstOrNull { it.name == value } ?: Default
    }
}

enum class AudioAnalysisSource {
    None,
    Pcm,
    Spectrum,
    WebAudio,
}

data class AudioAnalysisFrame(
    val amplitude: Float = 0f,
    val bands: List<Float> = emptyList(),
    val timestampMs: Long = 0L,
    val source: AudioAnalysisSource = AudioAnalysisSource.None,
) {
    fun normalized(maxBands: Int = MaxBands): AudioAnalysisFrame {
        val normalizedBands = bands
            .take(maxBands.coerceAtLeast(1))
            .map { it.coerceIn(0f, 1f) }
        return copy(
            amplitude = amplitude.coerceIn(0f, 1f),
            bands = normalizedBands,
            timestampMs = timestampMs.coerceAtLeast(0L),
        )
    }

    companion object {
        const val MaxBands = 128
        val Empty = AudioAnalysisFrame()
    }
}
