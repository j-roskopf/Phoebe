package com.phoebe.app.telemetry

import com.phoebe.app.platform.isDebugBuild

object Telemetry {
    private const val Dsn = "https://86de8cb5898a6e529bb04cef2cad8677@o4511214016200704.ingest.us.sentry.io/4511412309524480"
    private var initialized = false
    private var lastScreen: String? = null

    fun initialize() {
        if (initialized) return
        if (isDebugBuild()) return // LOCAL ONLY - do not commit
        platformInitializeTelemetry(
            dsn = Dsn,
            debug = isDebugBuild(),
            environment = if (isDebugBuild()) "debug" else "production",
        )
        initialized = true
        log("Telemetry", "Sentry initialized")
    }

    fun trackScreen(name: String) {
        initialize()
        val previous = lastScreen
        lastScreen = name
        platformTrackScreen(name, previous)
    }

    fun log(tag: String, message: String) {
        if (!initialized) return
        platformLogTelemetry(tag, message)
    }

    fun captureException(throwable: Throwable) {
        initialize()
        platformCaptureException(throwable)
    }

    fun close() {
        if (!initialized) return
        platformCloseTelemetry()
        initialized = false
    }
}

internal expect fun platformInitializeTelemetry(
    dsn: String,
    debug: Boolean,
    environment: String,
)

internal expect fun platformTrackScreen(name: String, previous: String?)

internal expect fun platformLogTelemetry(tag: String, message: String)

internal expect fun platformCaptureException(throwable: Throwable)

internal expect fun platformCloseTelemetry()
