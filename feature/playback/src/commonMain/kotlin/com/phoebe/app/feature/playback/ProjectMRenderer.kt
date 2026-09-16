package com.phoebe.app.feature.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Thin expect/actual façade over projectM / Butterchurn (Phase 2).
 * Surface hosts own the GL/WebGL context; this API is for non-UI callers
 * (smoke tests, quarantine) that already have a context or only need metadata.
 */
expect object ProjectMRenderer {
    fun isNativeAvailable(): Boolean
}

/**
 * Runtime gate so a failed native create can fall back to artwork instead of
 * leaving a black GL surface. Read during composition.
 */
object ProjectMHostGate {
    var healthy by mutableStateOf(true)
        private set

    fun markFailed() {
        healthy = false
    }

    fun reset() {
        healthy = true
    }
}
