package com.phoebe.app.data

import com.phoebe.app.domain.MediaProviderType
import dev.zacsweers.metro.Inject
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns what a person typed into the "Server URL" field into a base URL the provider clients can use.
 *
 * - Adds a scheme when none was typed (`nas.local` → `http://nas.local:8096`).
 * - When no port was typed, also tries the provider's default port. Candidates are probed in parallel against the
 *   provider's unauthenticated info endpoint, and the first one (in priority order) that answers like that kind of
 *   server wins. If none answer, the first candidate is returned so sign-in reports its usual error.
 *
 * Priority: an explicitly typed scheme is respected first (reverse proxies on 80/443), with the default port as a
 * fallback. A bare host tries the default port first, since that is how most self-hosted servers listen.
 */
@Inject
class ServerUrlResolver(
    private val httpClient: HttpClient,
) {
    suspend fun resolve(type: MediaProviderType, rawUrl: String): String {
        val candidates = serverUrlCandidates(rawUrl, type.defaultServerPort())
        if (candidates.size <= 1) return candidates.firstOrNull() ?: rawUrl.trim()
        val winner = coroutineScope {
            val probes = candidates.map { candidate ->
                async {
                    withTimeoutOrNull(ProbeTimeoutMillis) {
                        runCatching { probe(type, candidate) }.getOrDefault(false)
                    } ?: false
                }
            }
            var found: String? = null
            for ((index, probe) in probes.withIndex()) {
                if (probe.await()) {
                    found = candidates[index]
                    break
                }
            }
            probes.forEach { it.cancel() }
            found
        }
        return winner ?: candidates.first()
    }

    private suspend fun probe(type: MediaProviderType, baseUrl: String): Boolean {
        val probeUrl = when (type) {
            MediaProviderType.Jellyfin -> "$baseUrl/System/Info/Public"
            MediaProviderType.Emby -> if (baseUrl.endsWith("/emby", ignoreCase = true)) {
                "$baseUrl/System/Info/Public"
            } else {
                "$baseUrl/emby/System/Info/Public"
            }
            MediaProviderType.Navidrome -> "$baseUrl/rest/ping.view?f=json"
            MediaProviderType.MusicAssistant -> "$baseUrl/info"
            MediaProviderType.Plex -> return true
        }
        val response = httpClient.get(probeUrl)
        if (!response.status.isSuccess()) return false
        val body = response.bodyAsText()
        return when (type) {
            MediaProviderType.Jellyfin, MediaProviderType.Emby -> "\"Id\"" in body && "\"Version\"" in body
            MediaProviderType.Navidrome -> "subsonic-response" in body
            MediaProviderType.MusicAssistant -> "server_id" in body || "server_version" in body
            MediaProviderType.Plex -> true
        }
    }

    private companion object {
        const val ProbeTimeoutMillis = 4_000L
    }
}

fun MediaProviderType.defaultServerPort(): Int? = when (this) {
    MediaProviderType.Jellyfin, MediaProviderType.Emby -> 8096
    MediaProviderType.Navidrome -> 4533
    MediaProviderType.MusicAssistant -> 8095
    MediaProviderType.Plex -> 32400
}

/** Candidate base URLs for [rawUrl], most likely first. No trailing slash. */
internal fun serverUrlCandidates(rawUrl: String, defaultPort: Int?): List<String> {
    val trimmed = rawUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return emptyList()
    val schemeMatch = SchemePattern.find(trimmed)
    val scheme = schemeMatch?.groupValues?.get(1)?.lowercase()
    if (scheme != null && scheme != "http" && scheme != "https") return listOf(trimmed)
    val rest = if (schemeMatch != null) trimmed.substring(schemeMatch.range.last + 1) else trimmed
    val authority = rest.substringBefore('/')
    if (authority.isEmpty()) return listOf(trimmed)
    val path = rest.substring(authority.length)
    val port = authority.explicitPort()
    return when {
        port != null -> {
            val resolvedScheme = scheme ?: if (port == 443) "https" else "http"
            listOf("$resolvedScheme://$authority$path")
        }
        scheme != null -> listOfNotNull(
            "$scheme://$authority$path",
            defaultPort?.let { "$scheme://$authority:$it$path" },
        )
        else -> listOfNotNull(
            defaultPort?.let { "http://$authority:$it$path" },
            "https://$authority$path",
            "http://$authority$path",
        )
    }.distinct()
}

private val SchemePattern = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")

/** The port in `host:1234`, `user@host:1234` or `[::1]:1234`; null when none was typed. */
private fun String.explicitPort(): Int? {
    val hostPort = substringAfterLast('@')
    val portText = if (hostPort.startsWith("[")) {
        hostPort.substringAfter("]", "").removePrefix(":").takeIf { hostPort.substringAfter("]", "").startsWith(":") }
    } else {
        hostPort.split(':').takeIf { it.size == 2 }?.get(1)
    }
    return portText?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
}
