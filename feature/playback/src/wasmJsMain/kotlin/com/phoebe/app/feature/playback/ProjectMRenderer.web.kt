package com.phoebe.app.feature.playback

actual object ProjectMRenderer {
    /** Butterchurn HTML overlay is the wasm visualizer host. */
    actual fun isNativeAvailable(): Boolean = true
}
