package com.phoebe.app.platform

import com.phoebe.app.telemetry.Telemetry

/** True for local/dev builds; false for store/release artifacts. */
expect fun isDebugBuild(): Boolean

/**
 * Local platform output is debug-only; telemetry still receives logs when configured.
 * Prefer [v] for high-volume traces and [d] for lifecycle / error detail.
 */
object PhoebeLog {
    fun v(tag: String, message: String) {
        if (isDebugBuild()) platformLog(tag, message)
        Telemetry.log(tag, message)
    }

    fun v(tag: String, lazyMessage: () -> String) {
        val message = lazyMessage()
        if (isDebugBuild()) platformLog(tag, message)
        Telemetry.log(tag, message)
    }

    fun d(tag: String, message: String) {
        if (isDebugBuild()) platformLog(tag, message)
        Telemetry.log(tag, message)
    }

    fun d(tag: String, lazyMessage: () -> String) {
        val message = lazyMessage()
        if (isDebugBuild()) platformLog(tag, message)
        Telemetry.log(tag, message)
    }
}

/**
 * Compact class + message chain for probe/player diagnostics.
 * Strips query strings from http(s) URLs so tokens do not land in Sentry.
 */
fun Throwable.logDetail(maxDepth: Int = 3): String = buildString {
    var current: Throwable? = this@logDetail
    var depth = 0
    while (current != null && depth < maxDepth) {
        if (depth > 0) append(" | ")
        append(current::class.simpleName ?: "Throwable")
        current.message?.takeIf { it.isNotBlank() }?.let { message ->
            append(": ").append(redactUrlQueryParams(message).take(240))
        }
        current = current.cause
        depth++
    }
}

private fun redactUrlQueryParams(text: String): String =
    text.replace(Regex("""(https?://[^\s"'<>?]+)(\?[^\s"'<>]*)""", RegexOption.IGNORE_CASE)) { match ->
        match.groupValues[1]
    }

internal expect fun platformLog(tag: String, message: String)
