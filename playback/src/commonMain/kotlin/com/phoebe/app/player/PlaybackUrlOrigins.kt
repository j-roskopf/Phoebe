package com.phoebe.app.player

import com.phoebe.app.data.reachableBaseUris
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.Track
import io.ktor.http.Url

private const val MaxPlaybackFallbackOrigins = 8

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
