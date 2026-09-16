package com.phoebe.app.feature.playback

import com.phoebe.app.domain.VisualizerPackManifest
import com.phoebe.app.domain.VisualizerPresetRef
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.PickedVisualizerPresetFile
import com.phoebe.app.platform.PlatformStorage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Imports user-supplied `.milk` / Butterchurn `.json` files into [VisualizerPackStore]
 * and persists them under PlatformStorage for the next launch.
 */
class VisualizerUserPackRepository(
    private val store: VisualizerPackStore,
    private val storage: PlatformStorage = PlatformStorage(),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false },
) {
    suspend fun restore() {
        val raw = storage.readText(ManifestPath) ?: return
        val manifest = runCatching {
            json.decodeFromString(VisualizerPackManifest.serializer(), raw)
        }.getOrNull() ?: return
        if (manifest.presets.isEmpty()) return
        store.install(manifest)
        PhoebeLog.d(Tag) { "restored ${manifest.presets.size} user presets" }
    }

    suspend fun importFiles(files: List<PickedVisualizerPresetFile>): Int {
        val accepted = files.mapNotNull { file ->
            val name = file.fileName.substringAfterLast('/').substringAfterLast('\\')
            val base = name.substringBeforeLast('.', missingDelimiterValue = name).trim()
            if (base.isEmpty()) return@mapNotNull null
            val lower = name.lowercase()
            val isMilk = lower.endsWith(".milk")
            val isJson = lower.endsWith(".json")
            if (!isMilk && !isJson) return@mapNotNull null
            val body = file.text.trim()
            if (body.isEmpty()) return@mapNotNull null
            if (isJson && !body.startsWith("{")) return@mapNotNull null
            val presetId = sanitizeId(base)
            val storageName = if (isMilk) {
                "$StoragePrefix/$UserPackId/$presetId.milk"
            } else {
                "$StoragePrefix/$UserPackId/$presetId.json"
            }
            storage.writeText(storageName, body)
            VisualizerPresetRef(
                packId = UserPackId,
                presetId = presetId,
                displayName = humanize(base),
                milkRelativePath = if (isMilk) storageName else null,
                butterchurnRelativePath = if (isJson) storageName else null,
            )
        }
        if (accepted.isEmpty()) return 0

        val existing = store.findPack(UserPackId)?.presets.orEmpty()
            .filterNot { old -> accepted.any { it.presetId == old.presetId } }
        val merged = existing + accepted
        val manifest = VisualizerPackManifest(
            id = UserPackId,
            name = UserPackName,
            version = "local",
            presets = merged,
        )
        store.install(manifest)
        storage.writeText(ManifestPath, json.encodeToString(VisualizerPackManifest.serializer(), manifest))
        PhoebeLog.d(Tag) { "imported ${accepted.size} user presets (total ${merged.size})" }
        return accepted.size
    }

    suspend fun clear() {
        store.uninstall(UserPackId)
        storage.delete(ManifestPath)
    }

    suspend fun loadPayload(packId: String, presetId: String): String? {
        val milk = storage.readText("$StoragePrefix/$packId/$presetId.milk")
        if (!milk.isNullOrBlank()) return milk
        val jsonBody = storage.readText("$StoragePrefix/$packId/$presetId.json")
        if (!jsonBody.isNullOrBlank()) return jsonBody
        return null
    }

    private fun sanitizeId(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifEmpty { "preset" }

    private fun humanize(raw: String): String =
        raw.replace('_', ' ').replace('-', ' ').trim().ifEmpty { raw }

    companion object {
        const val UserPackId = "user"
        const val UserPackName = "My presets"
        private const val StoragePrefix = "visualizer-packs"
        private const val ManifestPath = "$StoragePrefix/$UserPackId/manifest.json"
        private const val Tag = "VisualizerUserPack"
    }
}

fun VisualizerPackStore.findPack(packId: String): VisualizerPackManifest? =
    installed.value.firstOrNull { it.id == packId }
