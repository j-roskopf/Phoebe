package com.phoebe.app.feature.playback

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide bridge so GL threads can notify [VisualizerPresetSentinel]
 * without blocking the render loop.
 */
object VisualizerSentinelBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var sentinel: VisualizerPresetSentinel? = null

    fun attach(value: VisualizerPresetSentinel) {
        sentinel = value
        scope.launch { value.recoverOnLaunch() }
    }

    fun beginLoadFromPath(path: String) {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        val presetId = fileName.substringBeforeLast('.').ifBlank { fileName }
        beginLoad("bundled", presetId)
    }

    fun beginLoadRaw(packId: String = "bundled", presetId: String = "inline") {
        beginLoad(packId, presetId)
    }

    fun beginLoad(packId: String, presetId: String) {
        val active = sentinel ?: return
        scope.launch { active.beginLoad(packId, presetId) }
    }

    fun onSuccessfulFrame() {
        val active = sentinel ?: return
        scope.launch { active.onSuccessfulFrame() }
    }
}
