package com.phoebe.app.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Now-playing visual surface selection.
 *
 * Stored as a compact string via [toStoredName] / [fromStoredName]:
 * - `Artwork`
 * - `Shuffle` or `Shuffle:<packId>`
 * - `Pinned:<packId>:<presetId>`
 *
 * Unknown legacy enum names (Alchemy, …) degrade to [Artwork] so old backups
 * never crash. A pinned preset whose pack is gone is resolved to [Shuffle] at
 * runtime by the pack manager (not here).
 */
@Serializable
sealed class NowPlayingVisualizerPreset {
    abstract val label: String
    abstract val isVisualizer: Boolean

    @Serializable
    @SerialName("Artwork")
    data object Artwork : NowPlayingVisualizerPreset() {
        override val label: String get() = "Artwork"
        override val isVisualizer: Boolean get() = false
    }

    @Serializable
    @SerialName("Shuffle")
    data class Shuffle(
        val packId: String? = null,
    ) : NowPlayingVisualizerPreset() {
        override val label: String
            get() = if (packId.isNullOrBlank()) "Shuffle" else "Shuffle ($packId)"
        override val isVisualizer: Boolean get() = true
    }

    @Serializable
    @SerialName("Pinned")
    data class Pinned(
        val packId: String,
        val presetId: String,
        val displayName: String = presetId,
    ) : NowPlayingVisualizerPreset() {
        override val label: String get() = displayName
        override val isVisualizer: Boolean get() = true
    }

    fun toStoredName(): String = when (this) {
        Artwork -> "Artwork"
        is Shuffle -> if (packId.isNullOrBlank()) "Shuffle" else "Shuffle:$packId"
        is Pinned -> "Pinned:$packId:$presetId"
    }

    companion object {
        val Default: NowPlayingVisualizerPreset = Artwork
        val DefaultVisualizer: NowPlayingVisualizerPreset = Shuffle()

        fun fromStoredName(value: String?): NowPlayingVisualizerPreset {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty() || raw == "Artwork") return Artwork
            if (raw == "Shuffle") return Shuffle()
            if (raw.startsWith("Shuffle:")) {
                val pack = raw.removePrefix("Shuffle:").trim()
                return if (pack.isEmpty()) Shuffle() else Shuffle(pack)
            }
            if (raw.startsWith("Pinned:")) {
                val rest = raw.removePrefix("Pinned:")
                val pack = rest.substringBefore(':', missingDelimiterValue = "").trim()
                val preset = rest.substringAfter(':', missingDelimiterValue = "").trim()
                if (pack.isNotEmpty() && preset.isNotEmpty()) {
                    val display = BundledVisualizerPacks.find(pack, preset)?.displayName ?: preset
                    return Pinned(packId = pack, presetId = preset, displayName = display)
                }
            }
            // Legacy Kotlin enum names and anything else → Artwork (safe degrade).
            return Artwork
        }
    }
}

enum class AudioAnalysisSource {
    None,
    Pcm,
    Spectrum,
    WebAudio,
}

/**
 * Lightweight amplitude/band frame kept for non-visualizer UI (equalizer chrome).
 * The projectM path does **not** consume this — see [VisualizerPcmSink].
 */
data class AudioAnalysisFrame(
    val amplitude: Float = 0f,
    val bands: List<Float> = emptyList(),
    val timestampMs: Long = 0L,
    val source: AudioAnalysisSource = AudioAnalysisSource.None,
) {
    fun normalized(maxBands: Int = MaxBands): AudioAnalysisFrame {
        val bandLimit = maxBands.coerceAtLeast(1)
        if (isNormalized(bandLimit)) return this
        val normalizedBands = bands
            .take(bandLimit)
            .map { it.coerceIn(0f, 1f) }
        return copy(
            amplitude = amplitude.coerceIn(0f, 1f),
            bands = normalizedBands,
            timestampMs = timestampMs.coerceAtLeast(0L),
        )
    }

    private fun isNormalized(bandLimit: Int): Boolean =
        amplitude in 0f..1f &&
            timestampMs >= 0L &&
            bands.size <= bandLimit &&
            bands.all { it in 0f..1f }

    companion object {
        const val MaxBands = 128
        val Empty = AudioAnalysisFrame()
    }
}

/** Catalog entry for a milk / Butterchurn preset inside an installed pack. */
@Serializable
data class VisualizerPresetRef(
    val packId: String,
    val presetId: String,
    val displayName: String,
    val milkRelativePath: String? = null,
    val butterchurnRelativePath: String? = null,
)

@Serializable
data class VisualizerPackManifest(
    val id: String,
    val name: String,
    val version: String,
    val presets: List<VisualizerPresetRef> = emptyList(),
)

object BundledVisualizerPacks {
    const val BundledPackId = "bundled"

    private fun milk(
        presetId: String,
        displayName: String,
    ): VisualizerPresetRef = VisualizerPresetRef(
        packId = BundledPackId,
        presetId = presetId,
        displayName = displayName,
        milkRelativePath = "projectm-presets/$presetId.milk",
        butterchurnRelativePath = "butterchurn-presets/$presetId.json",
    )

    val default: List<VisualizerPackManifest> = listOf(
        VisualizerPackManifest(
            id = BundledPackId,
            name = "Phoebe bundled",
            version = "2",
            presets = listOf(
                milk("geiss-swirlie-1", "Geiss — Swirlie 1"),
                milk("geiss-swirlie-4", "Geiss — Swirlie 4"),
                milk("aderrasi-agitator", "Aderrasi — Agitator"),
                milk("aderrasi-blender", "Aderrasi — Blender"),
                milk("aderrasi-visitor", "Aderrasi — Visitor"),
                milk("aderrasi-see", "Aderrasi — See"),
                milk("aderrasi-songflower", "Aderrasi — Songflower"),
                milk("phoebe-pulse", "Phoebe Pulse"),
                milk("fvese-rebirth", "Fvese — Rebirth"),
                milk("che-escape", "Che — Escape"),
                milk("martin-foggy-notion", "Martin — Foggy Notion"),
                milk("flexi-mindblob", "Flexi — Mindblob"),
                milk("flexi-dive", "Flexi — Dive"),
                milk("benski-atom-smasher", "Benski — Atom Smasher"),
                milk("yin-temporal", "Yin — Temporal"),
                milk("rovastar-solarized-space", "Rovastar — Solarized Space"),
                milk("stahlregen-washing-machine", "Stahlregen — Washing Machine"),
                milk("eos-glowsticks", "Eo.S. — Glowsticks"),
                milk("phat-cool-bug", "Eo.S.+Phat — Cool Bug"),
                milk("zylot-skylight", "Eo.S.+Zylot — Skylight"),
            ),
        ),
    )

    val allPresets: List<VisualizerPresetRef>
        get() = default.flatMap { it.presets }

    fun find(packId: String, presetId: String): VisualizerPresetRef? =
        allPresets.firstOrNull { it.packId == packId && it.presetId == presetId }
}
