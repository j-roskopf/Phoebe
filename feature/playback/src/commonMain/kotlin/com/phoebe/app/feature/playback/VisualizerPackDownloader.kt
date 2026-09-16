package com.phoebe.app.feature.playback

import com.phoebe.app.domain.VisualizerPackManifest
import com.phoebe.app.domain.VisualizerPresetRef
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.PlatformStorage
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Downloads pack manifests + preset payloads from jsDelivr
 * (`cdn.jsdelivr.net/gh/<owner>/<repo>@<ref>/...`).
 *
 * Packs live in the separate `phoebe-visualizer-packs` repo once published
 * (Phase 7 — ask before creating that GitHub repo).
 */
class VisualizerPackDownloader(
    private val http: HttpClient,
    private val storage: PlatformStorage = PlatformStorage(),
    private val store: VisualizerPackStore,
    private val catalogBaseUrl: String = DefaultCatalogBaseUrl,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun refreshCatalog(): List<VisualizerPackManifest> {
        val text = http.get("$catalogBaseUrl/catalog.json").bodyAsText()
        val catalog = json.decodeFromString(VisualizerPackCatalog.serializer(), text)
        catalog.packs.forEach { pack -> store.install(pack) }
        return catalog.packs
    }

    suspend fun downloadPack(packId: String): VisualizerPackManifest? {
        val manifestText = http.get("$catalogBaseUrl/packs/$packId/manifest.json").bodyAsText()
        val manifest = json.decodeFromString(VisualizerPackManifest.serializer(), manifestText)
        for (preset in manifest.presets) {
            cachePresetPayload(preset)
        }
        store.install(manifest)
        PhoebeLog.d("VisualizerPackDownloader") { "installed pack ${manifest.id} v${manifest.version}" }
        return manifest
    }

    fun installUserPresets(
        packId: String = "user",
        displayName: String = "User presets",
        presets: List<VisualizerPresetRef>,
    ): VisualizerPackManifest {
        val manifest = VisualizerPackManifest(
            id = packId,
            name = displayName,
            version = "local",
            presets = presets,
        )
        store.install(manifest)
        return manifest
    }

    private suspend fun cachePresetPayload(preset: VisualizerPresetRef) {
        val milk = preset.milkRelativePath
        if (!milk.isNullOrBlank()) {
            runCatching {
                val body = http.get("$catalogBaseUrl/$milk").bodyAsText()
                storage.writeText("visualizer-packs/${preset.packId}/${preset.presetId}.milk", body)
            }
        }
        val butter = preset.butterchurnRelativePath
        if (!butter.isNullOrBlank()) {
            runCatching {
                val body = http.get("$catalogBaseUrl/$butter").bodyAsText()
                storage.writeText("visualizer-packs/${preset.packId}/${preset.presetId}.json", body)
            }
        }
    }

    companion object {
        /** Placeholder until Phase 7 publishes `phoebe-visualizer-packs`. */
        const val DefaultCatalogBaseUrl =
            "https://cdn.jsdelivr.net/gh/j-roskopf/phoebe-visualizer-packs@main"
    }
}

@Serializable
data class VisualizerPackCatalog(
    val packs: List<VisualizerPackManifest> = emptyList(),
)
