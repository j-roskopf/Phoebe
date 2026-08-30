package com.phoebe.app.feature.playback

import io.github.erkko68.filament.filamat.Filamat

internal actual val filamentVisualizerMaxFramebufferEdgePx: Int? = null

internal actual fun probeFilamentNativeRuntime(): Boolean =
    runCatching { Filamat.init() }.isSuccess
