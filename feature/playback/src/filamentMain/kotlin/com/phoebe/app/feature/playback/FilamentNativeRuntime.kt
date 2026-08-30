package com.phoebe.app.feature.playback

internal expect fun probeFilamentNativeRuntime(): Boolean

internal expect val filamentVisualizerMaxFramebufferEdgePx: Int?

internal val filamentNativeRuntimeAvailable: Boolean by lazy { probeFilamentNativeRuntime() }
