package com.phoebe.app.feature.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.phoebe.app.domain.BundledVisualizerPacks
import com.phoebe.app.domain.VisualizerPresetRef
import com.phoebe.app.platform.PickedVisualizerPresetFile

/**
 * Web Butterchurn paints above Skiko, so Compose DropdownMenus/Dialogs under the
 * visualizer slot get covered. Suppress HTML overlays while those are open.
 * Native iOS also freezes FBO readback while suppressed so menus stay responsive.
 */
object VisualizerHtmlOverlayGate {
    private var suppressions: Int = 0

    val isSuppressed: Boolean
        get() = suppressions > 0

    fun suppress() {
        suppressions += 1
        if (suppressions == 1) {
            setVisualizerHtmlOverlaysSuppressed(true)
        }
    }

    fun release() {
        suppressions = (suppressions - 1).coerceAtLeast(0)
        if (suppressions == 0) {
            setVisualizerHtmlOverlaysSuppressed(false)
        }
    }
}

@Composable
fun SuppressVisualizerHtmlOverlay(active: Boolean) {
    DisposableEffect(active) {
        if (active) {
            VisualizerHtmlOverlayGate.suppress()
            onDispose { VisualizerHtmlOverlayGate.release() }
        } else {
            onDispose { }
        }
    }
}

/**
 * Shared pack catalog for player overflow + Settings.
 * Persists user packs via [VisualizerUserPackRepository].
 */
object VisualizerPacks {
    val store: VisualizerPackStore = VisualizerPackStore()

    private val repository = VisualizerUserPackRepository(store)

    suspend fun restoreUserPacks() {
        repository.restore()
    }

    suspend fun importUserPresets(files: List<PickedVisualizerPresetFile>): Int =
        repository.importFiles(files)

    suspend fun clearUserPack() {
        repository.clear()
    }

    suspend fun loadUserPayload(packId: String, presetId: String): String? =
        repository.loadPayload(packId, presetId)

    fun externalPresets(): List<VisualizerPresetRef> =
        store.allPresets().filter { it.packId != BundledVisualizerPacks.BundledPackId }
}
