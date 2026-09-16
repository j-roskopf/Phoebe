package com.phoebe.app.feature.playback

/**
 * wasmJs: Butterchurn loads JSON via fetch from [relativeFetchPath].
 * Payload body is left null so the host can stream the file.
 */
actual object BundledPresetLoader {
    actual suspend fun loadPresetPayload(presetId: String): String? = null

    actual fun relativeFetchPath(presetId: String): String? {
        val id = presetId.trim()
        if (id.isEmpty()) return null
        return "butterchurn-presets/$id.json"
    }
}
