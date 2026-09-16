package com.phoebe.app.feature.playback

import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.PlatformStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sentinel-based crash quarantine for milk presets.
 * Writes "loading packId::presetId" before handing a preset to projectM;
 * clears after [successFrameThreshold] successful frames. A surviving marker
 * on next launch quarantines that preset.
 */
class VisualizerPresetSentinel(
    private val store: VisualizerPackStore,
    private val storage: PlatformStorage = PlatformStorage(),
    private val successFrameThreshold: Int = 3,
) {
    private var pendingKey: String? = null
    private var successFrames: Int = 0

    suspend fun recoverOnLaunch() {
        val marker = readMarker() ?: return
        val parts = marker.split("::", limit = 2)
        if (parts.size == 2) {
            store.quarantine(parts[0], parts[1])
            PhoebeLog.d("VisualizerSentinel") { "quarantined surviving marker $marker" }
        }
        clearMarker()
    }

    suspend fun beginLoad(packId: String, presetId: String) {
        val key = VisualizerPackStore.presetKey(packId, presetId)
        pendingKey = key
        successFrames = 0
        writeMarker(key)
    }

    suspend fun onSuccessfulFrame() {
        val key = pendingKey ?: return
        successFrames += 1
        if (successFrames >= successFrameThreshold) {
            clearMarker()
            pendingKey = null
            successFrames = 0
            PhoebeLog.d("VisualizerSentinel") { "cleared marker after frames for $key" }
        }
    }

    private suspend fun writeMarker(value: String) = withContext(Dispatchers.Default) {
        runCatching { storage.writeText(MarkerFile, value) }
    }

    private suspend fun readMarker(): String? = withContext(Dispatchers.Default) {
        runCatching {
            storage.readText(MarkerFile)?.trim()?.ifEmpty { null }
        }.getOrNull()
    }

    private suspend fun clearMarker() = withContext(Dispatchers.Default) {
        runCatching { storage.delete(MarkerFile) }
    }

    private companion object {
        const val MarkerFile = "visualizer-sentinel.txt"
    }
}
