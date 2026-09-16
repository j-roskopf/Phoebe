package com.phoebe.app.feature.playback

import com.phoebe.app.domain.BundledVisualizerPacks
import com.phoebe.app.domain.VisualizerPackManifest
import com.phoebe.app.domain.VisualizerPresetRef
import com.phoebe.app.platform.PhoebeLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Installed pack catalog + active preset resolution for projectM / Butterchurn.
 */
class VisualizerPackStore(
    private val bundled: List<VisualizerPackManifest> = BundledVisualizerPacks.default,
) {
    private val mutableInstalled = MutableStateFlow(bundled)
    val installed: StateFlow<List<VisualizerPackManifest>> = mutableInstalled.asStateFlow()

    private val mutableQuarantine = MutableStateFlow<Set<String>>(emptySet())
    val quarantinedPresetKeys: StateFlow<Set<String>> = mutableQuarantine.asStateFlow()

    fun allPresets(): List<VisualizerPresetRef> =
        mutableInstalled.value.flatMap { pack ->
            pack.presets.filterNot { ref ->
                presetKey(ref.packId, ref.presetId) in mutableQuarantine.value
            }
        }

    fun findPreset(packId: String, presetId: String): VisualizerPresetRef? =
        allPresets().firstOrNull { it.packId == packId && it.presetId == presetId }

    fun install(pack: VisualizerPackManifest) {
        mutableInstalled.update { current ->
            current.filterNot { it.id == pack.id } + pack
        }
    }

    fun uninstall(packId: String) {
        mutableInstalled.update { current -> current.filterNot { it.id == packId } }
    }

    fun quarantine(packId: String, presetId: String) {
        mutableQuarantine.update { it + presetKey(packId, presetId) }
        PhoebeLog.d("VisualizerPackStore") { "quarantined $packId/$presetId" }
    }

    fun clearQuarantine(packId: String, presetId: String) {
        mutableQuarantine.update { it - presetKey(packId, presetId) }
    }

    fun pickShuffle(packId: String? = null, excludeKey: String? = null): VisualizerPresetRef? {
        val pool = allPresets()
            .filter { packId == null || it.packId == packId }
            .filterNot { presetKey(it.packId, it.presetId) == excludeKey }
        return pool.randomOrNull()
    }

    companion object {
        fun presetKey(packId: String, presetId: String): String = "$packId::$presetId"
    }
}
