package com.phoebe.app.data

import com.phoebe.app.domain.EventSettings
import com.phoebe.app.domain.EventsBackendTarget
import com.phoebe.app.platform.isDebugBuild

expect fun defaultEventsLocalBackendUrl(): String

fun resolveEventsBackendBaseUrl(
    settings: EventSettings,
    debugBuild: Boolean = isDebugBuild(),
    productionBackendUrl: String = PhoebeBackendBuildConfig.productionBackendUrl,
): String? {
    val production = productionBackendUrl.trim().trimEnd('/').takeIf { it.isNotBlank() }
    if (!debugBuild || settings.backendTarget == EventsBackendTarget.Production) return production
    return settings.localBackendUrl
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?: defaultEventsLocalBackendUrl()
}
