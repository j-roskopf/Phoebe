package com.phoebe.app.feature.playback

/**
 * Loads bundled visualizer payload text for the active host.
 *
 * Native targets return `.milk` body for libprojectM.
 * wasmJs returns a relative fetch path for Butterchurn JSON.
 */
expect object BundledPresetLoader {
    suspend fun loadPresetPayload(presetId: String): String?

    /** Relative URL for hosts that prefer fetch-by-path (wasm Butterchurn). */
    fun relativeFetchPath(presetId: String): String?
}
