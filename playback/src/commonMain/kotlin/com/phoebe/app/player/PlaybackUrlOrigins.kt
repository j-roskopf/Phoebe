package com.phoebe.app.player

import com.phoebe.app.data.isLocalOnlyServerOrigin
import com.phoebe.app.data.reachableBaseUris
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.Track
import io.ktor.http.Url

private const val MaxPlaybackFallbackOrigins = 8

/** Stop after this many distinct stream URIs, including the first. Each Android attempt can take 30s. */
internal const val MaxTriedPlaybackUris = 3

internal fun isMusicServerStreamUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val parsed = runCatching { Url(url) }.getOrNull() ?: return false
    if (parsed.protocol.name != "http" && parsed.protocol.name != "https") return false
    if (parsed.host.endsWith(".plex.direct", ignoreCase = true)) return true
    val path = parsed.encodedPath.lowercase()
    return path.contains("/library/parts/") ||
        path.contains("/library/metadata/") ||
        path.contains("/music/:/transcode/") ||
        (path.contains("/audio/") && path.contains("/stream")) ||
        path.contains("/rest/stream") ||
        path.contains("/rest/download")
}

internal fun rebaseHttpUrlOrigin(url: String, origin: String): String? {
    if (url.isBlank() || origin.isBlank()) return null
    val parsed = runCatching { Url(url) }.getOrNull() ?: return null
    if (parsed.protocol.name != "http" && parsed.protocol.name != "https") return null
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
    val query = if ('?' in url) {
        url.substringAfter('?').substringBefore('#')
    } else {
        ""
    }
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

fun playbackOriginCandidates(
    server: PlexServer?,
    preferredOrigin: String? = null,
): List<String> {
    val preferred = preferredOrigin?.trimEnd('/')?.takeIf { it.isNotBlank() }
        ?: server?.uri?.trimEnd('/')?.takeIf { it.isNotBlank() }
    val fromServer = server?.reachableBaseUris(preferred).orEmpty().map { it.trimEnd('/') }
    return (listOfNotNull(preferred) + fromServer)
        .filter { it.isNotBlank() }
        .distinct()
}

internal fun playbackUrlsForOrigins(url: String, origins: List<String>): List<String> {
    if (url.isBlank() || !isMusicServerStreamUrl(url)) return listOf(url)
    val rebased = origins
        .asSequence()
        .mapNotNull { origin -> rebaseHttpUrlOrigin(url, origin) }
        .distinct()
        .take(MaxPlaybackFallbackOrigins + 1)
        .toList()
    return rebased.ifEmpty { listOf(url) }
}

internal fun Track.playbackUriCandidates(): List<String> {
    localUri?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
    val primary = streamUrl.takeIf { it.isNotBlank() }
    return (listOfNotNull(primary) + playbackFallbackUrls)
        .filter { it.isNotBlank() }
        .distinct()
}

/**
 * Next stream origin after [failedUri], skipping LAN-only hosts once a remote URL
 * has already timed out. Plex advertises `172-16-1-2.<hash>.plex.direct:32400` next
 * to public relays; walking those from cellular burns 30s per skip with no audio.
 */
internal fun nextPlaybackFailoverCandidate(
    candidates: List<String>,
    tried: Set<String>,
    failedUri: String?,
    maxTriedUris: Int = MaxTriedPlaybackUris,
): String? {
    if (tried.size >= maxTriedUris) return null
    val skipLocalOrigins = failedUri != null &&
        failedUri.isNotBlank() &&
        !isLocalOnlyPlaybackOrigin(failedUri)
    return candidates.firstOrNull { candidate ->
        candidate.isNotBlank() &&
            candidate !in tried &&
            !(skipLocalOrigins && isLocalOnlyPlaybackOrigin(candidate))
    }
}

internal fun isLocalOnlyPlaybackOrigin(url: String): Boolean = isLocalOnlyServerOrigin(url)

internal fun playbackOriginOf(url: String): String? {
    if (url.isBlank()) return null
    val parsed = runCatching { Url(url) }.getOrNull() ?: return null
    if (parsed.protocol.name != "http" && parsed.protocol.name != "https") return null
    if (parsed.host.isBlank()) return null
    return "${parsed.protocol.name}://${parsed.host}:${parsed.port}"
}

/**
 * Put URLs on [origin] first without inventing hosts. Playlist tracks are stamped with a
 * LAN-first URL at queue start; after a relay actually works, later songs must not go back
 * to the dead private address.
 */
internal fun Track.preferPlaybackOrigin(origin: String): Track {
    val preferred = origin.trimEnd('/').takeIf { it.isNotBlank() } ?: return this
    val candidates = playbackUriCandidates()
    if (candidates.size <= 1) return this
    val matching = candidates.filter { candidate ->
        playbackOriginOf(candidate)?.equals(preferred, ignoreCase = true) == true
    }
    if (matching.isEmpty()) return this
    val rest = candidates.filter { candidate ->
        playbackOriginOf(candidate)?.equals(preferred, ignoreCase = true) != true
    }
    val ordered = matching + rest
    val nextStream = ordered.first()
    val nextFallbacks = ordered.drop(1)
    if (nextStream == streamUrl && nextFallbacks == playbackFallbackUrls) return this
    return copy(
        streamUrl = nextStream,
        playbackFallbackUrls = nextFallbacks,
    )
}

internal fun Track.preferPlaybackUri(uri: String): Track {
    if (uri.isBlank()) return this
    val origin = playbackOriginOf(uri) ?: return copy(
        streamUrl = uri,
        playbackFallbackUrls = playbackUriCandidates().filter { it != uri },
    )
    val rest = playbackUriCandidates().filter { it != uri }
    val sameOrigin = rest.filter { playbackOriginOf(it)?.equals(origin, ignoreCase = true) == true }
    val others = rest.filter { playbackOriginOf(it)?.equals(origin, ignoreCase = true) != true }
    val ordered = listOf(uri) + sameOrigin + others
    if (ordered.first() == streamUrl && ordered.drop(1) == playbackFallbackUrls) return this
    return copy(
        streamUrl = ordered.first(),
        playbackFallbackUrls = ordered.drop(1),
    )
}

/** JavaFX/player-engine timeouts on LAN hosts will hang the same way on every engine. */
internal fun shouldSkipAlternateEngineAfterPlayerTimeout(uri: String): Boolean {
    if (uri.isBlank()) return false
    val isHttp = uri.startsWith("http://", ignoreCase = true) ||
        uri.startsWith("https://", ignoreCase = true)
    return isHttp && isLocalOnlyPlaybackOrigin(uri)
}

fun Track.withPlaybackOrigins(
    preferredOrigin: String?,
    fallbackOrigins: List<String> = emptyList(),
): Track {
    if (id.startsWith("radio:")) return this
    if (!localUri.isNullOrBlank()) return this
    val origins = (listOfNotNull(preferredOrigin?.trimEnd('/')?.takeIf { it.isNotBlank() }) + fallbackOrigins)
        .map { it.trimEnd('/') }
        .filter { it.isNotBlank() }
        .distinct()
    if (origins.isEmpty()) return this
    val streamCandidates = playbackUrlsForOrigins(streamUrl, origins)
    val downloadCandidates = if (downloadUrl.isNotBlank() && isMusicServerStreamUrl(downloadUrl)) {
        playbackUrlsForOrigins(downloadUrl, origins)
    } else {
        listOf(downloadUrl)
    }
    val nextStream = streamCandidates.firstOrNull().orEmpty()
    val nextDownload = downloadCandidates.firstOrNull().orEmpty()
    val nextFallbacks = streamCandidates.drop(1)
    if (nextStream == streamUrl && nextDownload == downloadUrl && nextFallbacks == playbackFallbackUrls) {
        return this
    }
    return copy(
        streamUrl = nextStream.ifBlank { streamUrl },
        downloadUrl = nextDownload.ifBlank { downloadUrl },
        playbackFallbackUrls = nextFallbacks,
    )
}
