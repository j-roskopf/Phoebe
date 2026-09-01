package com.phoebe.app.player

import com.phoebe.app.data.ArtworkAuthHolder
import com.phoebe.app.data.ArtworkOriginHolder
import com.phoebe.app.data.bindPlexUrl
import com.phoebe.app.data.isLocalOnlyServerOrigin
import com.phoebe.app.data.isPlexMediaPathOrUrl
import com.phoebe.app.data.isPlexRelayOrigin
import com.phoebe.app.data.isPublicSynthesizedPlexHttpOrigin
import com.phoebe.app.data.plexAssetPath
import com.phoebe.app.data.reachableBaseUris
import com.phoebe.app.data.shouldSkipAdvertisedLan
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.currentNetworkIdentity
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter

private const val MaxPlaybackFallbackOrigins = 8

/** Stop after this many distinct stream URIs, including the first. Attempts are cheap after origin pre-flight. */
internal const val MaxTriedPlaybackUris = 5

internal fun isMusicServerStreamUrl(url: String): Boolean {
    if (url.isBlank()) return false
    if (url.isPlexMediaPathOrUrl() && !url.startsWith("http://") && !url.startsWith("https://")) {
        val path = url.substringBefore('?').lowercase()
        return path.contains("/library/parts/") ||
            path.contains("/library/metadata/") ||
            path.contains("/music/:/transcode/") ||
            path.startsWith("/:/")
    }
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

// Re-export shared rebase for playback tests / callers that imported the internal name.
internal fun rebaseHttpUrlOrigin(url: String, origin: String): String? =
    com.phoebe.app.data.rebaseHttpUrlOrigin(url, origin)

fun playbackOriginCandidates(
    server: PlexServer?,
    preferredOrigin: String? = null,
    demoteLocalOrigins: Boolean = false,
): List<String> {
    val preferred = preferredOrigin?.trimEnd('/')?.takeIf { it.isNotBlank() }
        ?: server?.uri?.trimEnd('/')?.takeIf { it.isNotBlank() }
    val fromServer = server?.let { plex ->
        plex.reachableBaseUris(
            preferredFirst = preferred,
            demoteLocalOrigins = demoteLocalOrigins,
        ).map { it.trimEnd('/') }
            .filterNot { origin ->
                isPublicSynthesizedPlexHttpOrigin(origin, plex.advertisedConnectionUris)
            }
    }.orEmpty()
    // reachableBaseUris already applies demotion to preferredFirst; do not re-prepend
    // server.uri / a stale LAN preferred and undo remote-first ordering.
    if (fromServer.isNotEmpty()) return fromServer
    return listOfNotNull(preferred).filter { it.isNotBlank() }
}

/**
 * Origins for bind-at-request artwork (Linthra: one session base + relative thumb).
 *
 * First paint must hit a host that works off-LAN. Unprobed remote `:32400`
 * plex.direct (usually closed) and private LAN both time out; the current
 * plex.tv relay (`:8443`) is the hop that actually serves thumbs. A probed
 * origin still wins when it is in the current advertised set. Demoted LAN
 * is omitted.
 */
fun rankedArtworkRequestOrigins(
    server: PlexServer?,
    probedOrigin: String? = null,
    demoteLocalOrigins: Boolean = false,
): List<String> {
    val probed = probedOrigin?.trimEnd('/')?.takeIf { it.isNotBlank() }
        ?.takeUnless { isLocalOnlyServerOrigin(it) }
    val all = playbackOriginCandidates(
        server = server,
        preferredOrigin = probed,
        demoteLocalOrigins = demoteLocalOrigins,
    )
    val usable = if (demoteLocalOrigins) {
        all.filterNot(::isLocalOnlyServerOrigin)
    } else {
        all
    }
    if (usable.isEmpty()) return all
    if (probed != null && usable.any { it.equals(probed, ignoreCase = true) }) {
        return listOf(probed) + usable.filter { !it.equals(probed, ignoreCase = true) }
    }
    val relays = usable.filter { isPlexRelayOrigin(it, server) }
    val otherRemote = usable.filterNot { origin ->
        isLocalOnlyServerOrigin(origin) || isPlexRelayOrigin(origin, server)
    }
    val lan = usable.filter(::isLocalOnlyServerOrigin)
    return relays + otherRemote + lan
}

internal fun playbackUrlsForOrigins(
    url: String,
    origins: List<String>,
    token: String = ArtworkAuthHolder.plexToken.orEmpty(),
): List<String> {
    if (url.isBlank()) return listOf(url)
    if (url.isPlexMediaPathOrUrl()) {
        val rebased = origins
            .asSequence()
            .map { origin -> bindPlexUrl(url, origin, token) }
            .distinct()
            .take(MaxPlaybackFallbackOrigins + 1)
            .toList()
        return rebased.ifEmpty { listOf(url) }
    }
    if (!isMusicServerStreamUrl(url)) return listOf(url)
    val rebased = origins
        .asSequence()
        .mapNotNull { origin -> rebaseHttpUrlOrigin(url, origin) }
        .distinct()
        .take(MaxPlaybackFallbackOrigins + 1)
        .toList()
    return rebased.ifEmpty { listOf(url) }
}

/**
 * Bind this track's relative Plex paths onto the origin that is live *right now*.
 *
 * The catalog stores `/library/parts/...` with no host and no token (shuttle2 keeps a relative
 * `externalId` and builds the URL in `PlexMediaInfoProvider.getMediaInfo`). Binding here, at the
 * moment the player asks for a URI, is what makes a Wi-Fi -> cellular handoff survivable: the
 * queue holds no addresses to go stale, so the next read simply picks up the new base.
 *
 * A legacy absolute URL is re-homed onto [origin] as well, so an old queue recovers too.
 */
fun Track.boundToLivePlaybackOrigin(
    origin: String? = ArtworkOriginHolder.liveOrigin,
    token: String = ArtworkAuthHolder.plexToken.orEmpty(),
): Track {
    if (id.startsWith("radio:")) return this
    if (!localUri.isNullOrBlank()) return this
    val base = origin?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return this
    val stream = if (streamUrl.isPlexMediaPathOrUrl()) bindPlexUrl(streamUrl, base, token) else streamUrl
    val download = if (downloadUrl.isPlexMediaPathOrUrl()) {
        bindPlexUrl(downloadUrl, base, token)
    } else {
        downloadUrl
    }
    if (stream == streamUrl && download == downloadUrl) return this
    return copy(streamUrl = stream, downloadUrl = download)
}

/**
 * Strip host and token back off, so a queue never carries an address that can go stale.
 * The inverse of [boundToLivePlaybackOrigin].
 */
fun Track.withRelativePlexPlaybackPaths(): Track {
    if (id.startsWith("radio:")) return this
    if (!localUri.isNullOrBlank()) return this
    val stream = plexAssetPath(streamUrl) ?: streamUrl
    val download = plexAssetPath(downloadUrl) ?: downloadUrl
    if (stream == streamUrl && download == downloadUrl && playbackFallbackUrls.isEmpty()) return this
    return copy(
        streamUrl = stream,
        downloadUrl = download,
        playbackFallbackUrls = emptyList(),
    )
}

/** True when this track's stream URL is a host-less server path, i.e. bound at request time. */
internal fun Track.holdsRelativePlexPath(): Boolean =
    localUri.isNullOrBlank() &&
        streamUrl.isNotBlank() &&
        !streamUrl.startsWith("http://", ignoreCase = true) &&
        !streamUrl.startsWith("https://", ignoreCase = true) &&
        streamUrl.isPlexMediaPathOrUrl()

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
            !(skipLocalOrigins && isLocalOnlyPlaybackOrigin(candidate)) &&
            !isPublicSynthesizedPlexHttpOrigin(candidate)
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
    val preferredRaw = origin.trimEnd('/').takeIf { it.isNotBlank() } ?: return this
    val preferred = playbackOriginOf(preferredRaw) ?: preferredRaw
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
    token: String = ArtworkAuthHolder.plexToken.orEmpty(),
): Track {
    if (id.startsWith("radio:")) return this
    if (!localUri.isNullOrBlank()) return this
    val origins = (listOfNotNull(preferredOrigin?.trimEnd('/')?.takeIf { it.isNotBlank() }) + fallbackOrigins)
        .map { it.trimEnd('/') }
        .filter { it.isNotBlank() }
        .distinct()
    if (origins.isEmpty()) return this
    val streamCandidates = playbackUrlsForOrigins(streamUrl, origins, token)
    val downloadSource = when {
        downloadUrl.isBlank() -> downloadUrl
        downloadUrl.isPlexMediaPathOrUrl() -> ensurePlexDownloadQuery(downloadUrl)
        isMusicServerStreamUrl(downloadUrl) -> downloadUrl
        else -> downloadUrl
    }
    val downloadCandidates = if (downloadSource.isNotBlank() &&
        (downloadSource.isPlexMediaPathOrUrl() || isMusicServerStreamUrl(downloadSource))
    ) {
        playbackUrlsForOrigins(downloadSource, origins, token)
    } else {
        listOf(downloadSource)
    }
    val nextStream = streamCandidates.firstOrNull().orEmpty()
    val nextDownload = downloadCandidates.firstOrNull().orEmpty()
    // Steady state: one live base. Keep a short emergency list if the live base dies mid-play.
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

fun List<Track>.withFreshPlaybackUrls(
    session: PlexSession?,
    liveOrigin: String? = null,
): List<Track> {
    if (session == null || isEmpty()) return this
    var changed = false
    val refreshed = map { track ->
        val next = track.withFreshPlaybackUrls(session, liveOrigin)
        if (next !== track) changed = true
        next
    }
    return if (changed) refreshed else this
}

fun Track.withFreshPlaybackUrls(
    session: PlexSession,
    liveOrigin: String? = null,
): Track {
    val identity = currentNetworkIdentity()
    val demoteLocalOrigins = StreamingPlaybackPolicyHolder.settings.shouldDemoteLocalOrigins(
        identity.demotesLocalOrigins,
    ) || session.selectedServer?.let { identity.shouldSkipAdvertisedLan(it) } == true
    val preferred = liveOrigin?.trimEnd('/')?.takeIf { it.isNotBlank() }
    val origins = playbackOriginCandidates(
        server = session.selectedServer,
        preferredOrigin = preferred,
        demoteLocalOrigins = demoteLocalOrigins,
    )
    if (session.providerType == MediaProviderType.Plex &&
        (streamUrl.isPlexMediaPathOrUrl() || downloadUrl.isPlexMediaPathOrUrl())
    ) {
        // Plex queues carry relative part keys only. Stamping an absolute origin here is what
        // made a queue go stale the instant the network changed: every entry pointed at the
        // address that happened to be live when the queue was built. The origin is bound at
        // open time instead, in StreamingPlaybackPolicyHolder.resolvePlaybackUri.
        return withRelativePlexPlaybackPaths()
    }
    val withOrigins = if (origins.isEmpty()) this else withPlaybackOrigins(origins.first(), origins.drop(1))
    val refreshedStreamUrl = withOrigins.streamUrl.withFreshPlaybackAuth(session)
    val refreshedDownloadUrl = withOrigins.downloadUrl.withFreshPlaybackAuth(session)
    val refreshedFallbacks = withOrigins.playbackFallbackUrls.map { url ->
        url.withFreshPlaybackAuth(session)
    }.filter { it.isNotBlank() && it != refreshedStreamUrl }.distinct()
    if (refreshedStreamUrl == streamUrl &&
        refreshedDownloadUrl == downloadUrl &&
        refreshedFallbacks == playbackFallbackUrls
    ) {
        return this
    }
    return withOrigins.copy(
        streamUrl = refreshedStreamUrl,
        downloadUrl = refreshedDownloadUrl,
        playbackFallbackUrls = refreshedFallbacks,
    )
}

fun String.withFreshPlaybackAuth(session: PlexSession): String {
    if (isBlank() || session.token.isBlank()) return this
    val parsed = runCatching { Url(this) }.getOrNull() ?: return this
    if (parsed.protocol.name != "http" && parsed.protocol.name != "https") return this
    return when (session.providerType) {
        MediaProviderType.Plex -> withQueryParameter(parsed, "X-Plex-Token", session.token)
        MediaProviderType.Jellyfin,
        MediaProviderType.Emby -> withQueryParameter(parsed, "api_key", session.token)
        MediaProviderType.Navidrome -> withQueryParameters(
            parsed,
            "u" to session.userName,
            "p" to session.token,
        )
        MediaProviderType.MusicAssistant -> this
    }
}

private fun withQueryParameter(url: Url, name: String, value: String): String =
    withQueryParameters(url, name to value)

private fun withQueryParameters(url: Url, vararg replacements: Pair<String, String>): String {
    val original = url.toString()
    val fragment = original.substringAfter('#', missingDelimiterValue = "")
    val withoutFragment = original.substringBefore('#')
    val base = withoutFragment.substringBefore('?')
    val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
    val replacementMap = replacements
        .filter { (_, value) -> value.isNotBlank() }
        .associate { (name, value) -> name to value }
    if (replacementMap.isEmpty()) return original
    val seen = mutableSetOf<String>()
    val pairs = query
        .split('&')
        .filter { it.isNotBlank() }
        .mapNotNull { pair ->
            val name = pair.substringBefore('=')
            val replacement = replacementMap[name] ?: return@mapNotNull pair
            seen += name
            "$name=${replacement.encodeURLParameter()}"
        }
        .toMutableList()
    replacementMap.forEach { (name, value) ->
        if (name !in seen) pairs += "$name=${value.encodeURLParameter()}"
    }
    val rebuilt = buildString {
        append(base)
        if (pairs.isNotEmpty()) {
            append('?')
            append(pairs.joinToString("&"))
        }
        if (fragment.isNotBlank()) {
            append('#')
            append(fragment)
        }
    }
    return rebuilt
}

private fun ensurePlexDownloadQuery(pathOrUrl: String): String {
    val path = plexAssetPath(pathOrUrl) ?: return pathOrUrl
    if (path.contains("download=", ignoreCase = true)) return path
    return if ('?' in path) "$path&download=1" else "$path?download=1"
}
