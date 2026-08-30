package com.phoebe.app.feature.playback

import com.phoebe.app.platform.PhoebeLog
import io.github.erkko68.filament.ffm.FilamentLoader
import io.github.erkko68.filament.filamat.Filamat

fun prepareDesktopFilamentNativeRuntime() {
    loadLinuxFilamentRuntimeDependencies()
}

internal actual val filamentVisualizerMaxFramebufferEdgePx: Int? = 1_280

internal actual fun probeFilamentNativeRuntime(): Boolean {
    loadLinuxFilamentRuntimeDependencies()
    return runCatching {
        FilamentLoader.load()
        Filamat.init()
    }.onFailure { error ->
        PhoebeLog.d("FilamentVisualizer") {
            "Filament native runtime unavailable, using canvas fallback: ${error.message}"
        }
    }.isSuccess
}
