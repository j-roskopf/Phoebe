package com.phoebe.app.data

import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun cachedArtworkPathForUrl(url: String): String {
    // Host-independent key so covers survive plex.direct relay rotation.
    val keySource = artworkCacheKeyPath(url).ifBlank { url }
    val extension = url.substringBefore('?')
        .substringAfterLast('/', "")
        .substringAfterLast('.', "")
        .takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
        ?: "jpg"
    return "artwork/cache-${keySource.stableArtworkHash()}.$extension"
}

/**
 * Path+query without scheme/host or credentials, so the artwork disk cache survives both an
 * origin change and a token rotation.
 *
 * Credentials must be stripped: URLs are bound at request time and always carry a token now, so
 * leaving it in the key would orphan every cached cover the next time the session token changed.
 */
fun artworkCacheKeyPath(url: String): String {
    val withoutFragment = url.substringBefore('#')
    val pathAndQuery = if ("://" !in withoutFragment) {
        withoutFragment
    } else {
        val afterHost = withoutFragment.substringAfter("://").substringAfter('/', missingDelimiterValue = "")
        if (afterHost.isBlank()) return withoutFragment else "/$afterHost"
    }
    val path = pathAndQuery.substringBefore('?')
    val query = pathAndQuery.substringAfter('?', missingDelimiterValue = "")
    if (query.isBlank()) return path
    val kept = query.split('&')
        .filter { param ->
            val key = param.substringBefore('=')
            param.isNotBlank() && key.lowercase() !in ArtworkCacheKeyCredentialParams
        }
    return if (kept.isEmpty()) path else "$path?${kept.joinToString("&")}"
}

private val ArtworkCacheKeyCredentialParams = setOf(
    "x-plex-token",
    "api_key",
    "apikey",
    "p",
    "t",
    "s",
    "u",
)

data class ArtworkOriginSnapshot(
    val liveOrigin: String? = null,
    val fallbackOrigins: List<String> = emptyList(),
) {
    fun candidateOrigins(): List<String> =
        (listOfNotNull(liveOrigin) + fallbackOrigins).distinct()
}

/** Live Plex (or other) media-server bases for rebasing stamped artwork URLs at request time. */
object ArtworkOriginHolder {
    private val mutable = MutableStateFlow(ArtworkOriginSnapshot())
    val snapshot: StateFlow<ArtworkOriginSnapshot> = mutable.asStateFlow()

    val liveOrigin: String?
        get() = mutable.value.liveOrigin

    val fallbackOrigins: List<String>
        get() = mutable.value.fallbackOrigins

    fun update(live: String?, fallbacks: List<String> = emptyList()) {
        val next = ArtworkOriginSnapshot(
            liveOrigin = live?.trimEnd('/')?.takeIf { it.isNotBlank() },
            fallbackOrigins = fallbacks.map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct(),
        )
        if (mutable.value == next) return
        mutable.value = next
    }

    fun clear() {
        mutable.value = ArtworkOriginSnapshot()
    }

    fun candidateOrigins(): List<String> = mutable.value.candidateOrigins()
}

/**
 * Swap scheme/host/port onto [origin]; keep path/query/fragment.
 * Used so Coil can retry artwork on ranked Plex bases without waiting for a catalog rewrite.
 */
fun rebaseArtworkUrlOrigin(url: String, origin: String): String? {
    if (url.isBlank() || origin.isBlank()) return null
    if (!url.startsWith("http://") && !url.startsWith("https://")) return null
    val parsed = runCatching { Url(url) }.getOrNull() ?: return null
    val originTrimmed = origin.trimEnd('/')
    val originParsed = runCatching { Url(originTrimmed) }.getOrNull() ?: return null
    if (originParsed.host.isBlank()) return null
    if (parsed.protocol.name == originParsed.protocol.name &&
        parsed.host.equals(originParsed.host, ignoreCase = true) &&
        parsed.port == originParsed.port
    ) {
        return url
    }
    val path = parsed.encodedPath.ifBlank { "/" }
    val query = if ('?' in url) url.substringAfter('?').substringBefore('#') else ""
    val fragment = if ('#' in url) url.substringAfter('#') else ""
    return buildString {
        append(originTrimmed)
        if (!path.startsWith('/')) append('/')
        append(path)
        if (query.isNotEmpty()) {
            append('?')
            append(query)
        }
        if (fragment.isNotEmpty()) {
            append('#')
            append(fragment)
        }
    }
}

/**
 * Artwork that must be bound onto [ArtworkOriginHolder.liveOrigin] at request time —
 * relative PMS paths or legacy absolute Plex hosts.
 */
fun String.isRebaseableServerArtworkUrl(): Boolean = isPlexMediaPathOrUrl()

private fun String.stableArtworkHash(): String {
    var hash = 1125899906842597L
    forEach { c -> hash = (hash * 31) + c.code }
    return hash.toString()
}
