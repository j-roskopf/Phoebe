package com.phoebe.app.feature.playback

import org.jetbrains.compose.resources.ExperimentalResourceApi
import phoebe.feature.playback.generated.resources.Res

@OptIn(ExperimentalResourceApi::class)
actual object BundledPresetLoader {
    actual suspend fun loadPresetPayload(presetId: String): String? {
        val id = presetId.trim()
        if (id.isEmpty()) return null
        return runCatching {
            Res.readBytes("files/projectm-presets/$id.milk").decodeToString()
        }.getOrNull()
    }

    actual fun relativeFetchPath(presetId: String): String? = null
}
