package com.phoebe.app.data

import com.phoebe.app.domain.PlexServer
import com.phoebe.app.platform.NetworkIdentity
import com.phoebe.app.platform.ipv4Slash24Prefix
import io.ktor.http.Url

/**
 * Ordered Plex server base URLs — local → direct remote → relay (python-plexapi / plexnet order).
 *
 * Plex often advertises `https://172-105-8-66.<token>.plex.direct:8443` alongside
 * `http://192.168.x.x:32400`. We only synthesize `http://<ip>:32400` when the plex.direct
 * host encodes a *private* address; a WAN IP on :32400 is usually closed and burns failover
 * budget. Servers requiring HTTPS get no synthesized entries at all — see [expandConnectionUris].
 *
 * When [demoteLocalOrigins] is true (cellular / wrong Wi-Fi), LAN-only hosts sort after
 * relays so playback does not burn the probe budget on unreachable private addresses.
 * Relays still sort after any direct remote.
 */
fun PlexServer.reachableBaseUris(
    preferredFirst: String? = null,
    demoteLocalOrigins: Boolean = false,
): List<String> {
    val primary = uri.trimEnd('/').takeIf { it.isNotBlank() }
    val advertised = advertisedConnectionUris.ifEmpty { connectionUris }
    // Relays live in their own column; a stale advertised list (LAN + :32400 only)
    // must still race plex.tv's 8443 hop. python-plexapi unions every Connection.
    val seed = (advertised + connectionUris + localConnectionUris + relayConnectionUris)
        .map { it.trimEnd('/') }
        .filter { it.isNotBlank() }
        .distinct()
    val expanded = when {
        seed.isNotEmpty() ->
            listOfNotNull(primary) + expandConnectionUris(seed, httpsRequired = httpsRequired)
        primary != null -> listOf(primary)
        else -> emptyList()
    }.distinct()
    val advertisedSet = advertised.toSet()
    val localSet = localConnectionUris.toSet()
    val ordered = expanded.sortedWith(
        compareBy(
            { uri ->
                val keepPrimaryFirst = uri == primary &&
                    !(demoteLocalOrigins && isLocalOnlyServerOrigin(uri)) &&
                    !isPlexRelayOrigin(uri, this)
                !keepPrimaryFirst
            },
            // Location class: local (0) → direct remote (1) → relay (2); LAN bumped when demoted.
            { locationRank(it, this, demoteLocalOrigins) },
            { if (demoteLocalOrigins) isLocalOnlyServerOrigin(it) else it !in localSet },
            { it !in advertisedSet },
            { connectionPriority(it, demoteLocalOrigins, this) },
        ),
    )
    val preferred = preferredFirst?.trimEnd('/')?.takeIf { it.isNotBlank() }
    // Keep an explicit preferred origin first even when it is a relay — callers pass a
    // known-good live base (playback/timeline). Never prepend a host plex.tv no longer
    // advertises: rotated relay IPs would otherwise steal first place from the live one.
    val withPreferred = preferred
        ?.takeUnless { demoteLocalOrigins && isLocalOnlyServerOrigin(it) }
        ?.takeIf { pref -> ordered.any { playbackOriginEquals(it, pref) } }
        ?.let { pref -> listOf(pref) + ordered.filter { it != pref } }
        ?: ordered
    return if (httpsRequired) {
        withPreferred.sortedBy { if (it.startsWith("https://")) 0 else 1 }
    } else {
        withPreferred
    }
}

/**
 * Bases for Plex command paths (`/:/timeline`, play queues). Library media often works on a
 * public relay while LAN IPs are dead; walking those first with a long connect timeout makes
 * scrobble pings (and anything awaiting them) look hung.
 *
 * Once a non-LAN origin is known to work, try remotes first and keep private addresses as a
 * last resort — relays can 401 the command path while LAN still accepts it.
 */
fun PlexServer.timelineBaseUris(
    preferredFirst: String? = null,
    demoteLocalOrigins: Boolean = false,
): List<String> {
    val bases = reachableBaseUris(preferredFirst, demoteLocalOrigins = demoteLocalOrigins)
    val preferred = preferredFirst?.trimEnd('/')?.takeIf { it.isNotBlank() }
    val preferRemote = preferred != null && !isLocalOnlyServerOrigin(preferred)
    if (!preferRemote && !demoteLocalOrigins) return bases
    val remote = bases.filterNot(::isLocalOnlyServerOrigin)
    if (remote.isEmpty()) return bases
    return remote + bases.filter(::isLocalOnlyServerOrigin)
}

fun bestReachableBaseUri(
    advertisedUris: List<String>,
    localUris: List<String> = emptyList(),
    relayUris: List<String> = emptyList(),
    httpsRequired: Boolean = false,
): String? {
    val server = PlexServer(
        id = "",
        name = "",
        uri = advertisedUris.firstOrNull().orEmpty(),
        owned = false,
        connectionUris = expandConnectionUris(advertisedUris, httpsRequired = httpsRequired),
        advertisedConnectionUris = advertisedUris,
        localConnectionUris = localUris,
        relayConnectionUris = relayUris,
        httpsRequired = httpsRequired,
    )
    return server.reachableBaseUris().firstOrNull()
}

/**
 * Advertised URLs first, then synthesized plain-IP fallbacks derived from plex.direct hosts.
 *
 * `http://<private-ip>:32400` is the only variant worth synthesizing. Plex's certificate covers
 * `*.<hash>.plex.direct`, so an `https://` URL built from the bare IP can never finish a TLS
 * handshake. A public `http://<wan-ip>:32400` is usually closed (remote access is the 8443
 * relay). A server with [httpsRequired] refuses the plain-HTTP port outright.
 */
fun expandConnectionUris(
    advertisedUris: List<String>,
    httpsRequired: Boolean = false,
): List<String> =
    buildList {
        val advertised = advertisedUris.map { it.trimEnd('/') }.filter { it.isNotBlank() }
        addAll(advertised)
        if (httpsRequired) return@buildList
        for (uri in advertised) {
            val ip = decodedIpFromPlexDirect(uri) ?: continue
            if (isPrivateOrLoopbackIpv4(ip)) add("http://$ip:32400")
        }
    }.distinct()

/** e.g. `172-105-8-66.<hash>.plex.direct` → `172.105.8.66` (Plex's encoded WAN address). */
internal fun decodedIpFromPlexDirect(uri: String): String? {
    val host = uri.substringAfter("://").substringBefore(':').substringBefore('/').lowercase()
    if (!host.endsWith(".plex.direct")) return null
    val dashed = host.substringBefore('.')
    val parts = dashed.split('-')
    if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return null
    return parts.joinToString(".")
}

/**
 * Plex Relay hop (last resort). Prefer [PlexServer.relayConnectionUris] from plex.tv;
 * when that list is empty (legacy session), treat public secure plex.direct as a relay.
 */
fun isPlexRelayOrigin(uri: String, server: PlexServer? = null): Boolean {
    if (uri.isBlank()) return false
    val trimmed = uri.trimEnd('/')
    val relays = server?.relayConnectionUris.orEmpty().map { it.trimEnd('/') }.toSet()
    if (relays.isNotEmpty() &&
        (trimmed in relays || relays.any { relay -> playbackOriginEquals(trimmed, relay) })
    ) {
        return true
    }
    // A non-empty relay list must not *narrow* this: plex.tv rotates 8443 IPs, and a
    // stale disk/session host still has to be treated as a relay so we never hydrate it
    // as a "direct remote" and bind every Coil thumb to a dead address.
    if (isLocalOnlyServerOrigin(trimmed)) return false
    if (!trimmed.contains(".plex.direct", ignoreCase = true)) return false
    if (!trimmed.startsWith("https://", ignoreCase = true)) return false
    val port = trimmed.substringAfter("://").substringAfter(':', "443")
        .substringBefore('/')
        .toIntOrNull() ?: 443
    return port == 443 || port == 8443
}

/** True when [uri] is one of this server's current plex.tv-advertised (or expanded) bases. */
fun PlexServer.containsConnectionOrigin(uri: String): Boolean {
    val want = httpUrlOriginOf(uri) ?: uri.trimEnd('/').takeIf { it.isNotBlank() } ?: return false
    return (sequenceOf(this.uri) +
        connectionUris.asSequence() +
        advertisedConnectionUris.asSequence() +
        localConnectionUris.asSequence() +
        relayConnectionUris.asSequence())
        .map { it.trimEnd('/') }
        .filter { it.isNotBlank() }
        .any { candidate ->
            (httpUrlOriginOf(candidate) ?: candidate).equals(want, ignoreCase = true)
        }
}

/** 0 = local, 1 = direct remote, 2 = relay. Demoted LAN sorts after relays (3). */
internal fun locationRank(
    uri: String,
    server: PlexServer?,
    demoteLocalOrigins: Boolean,
): Int {
    if (isLocalOnlyServerOrigin(uri)) return if (demoteLocalOrigins) 3 else 0
    if (isPlexRelayOrigin(uri, server)) return 2
    return 1
}

internal fun connectionPriority(
    uri: String,
    demoteLocalOrigins: Boolean = false,
    server: PlexServer? = null,
): Int {
    val port = uri.substringAfter("://").substringAfter(':', "32400").substringBefore('/').toIntOrNull() ?: 32400
    val secure = uri.startsWith("https://")
    val local = isLocalOnlyServerOrigin(uri)
    val plexDirect = uri.contains(".plex.direct", ignoreCase = true)
    val relay = isPlexRelayOrigin(uri, server)
    if (relay) {
        return 100 + when {
            secure && (port == 443 || port == 8443) -> 0
            else -> 10
        }
    }
    if (demoteLocalOrigins && local) {
        return 50 + when {
            !secure && port == 32400 -> 0
            else -> 25
        }
    }
    return when {
        local && !secure && port == 32400 -> 0
        plexDirect && secure && (port == 443 || port == 8443) && !local -> 5
        local -> 25
        secure && port == 8443 -> 20
        !secure && port == 32400 -> 30
        secure && port == 32400 -> 80
        plexDirect -> 90
        !secure -> 40
        else -> 50
    }
}

/**
 * Origins that only work on the server's LAN. Plex advertises these next to public relays;
 * probing them from cellular (or the wrong Wi-Fi) burns seconds per URL.
 */
fun isLocalOnlyServerOrigin(uri: String): Boolean {
    if (uri.isBlank()) return false
    val scheme = uri.substringBefore("://", missingDelimiterValue = "").lowercase()
    // Only HTTP(S) music-server hosts — file:// and other schemes are not LAN origins.
    if (scheme != "http" && scheme != "https") return false
    val host = uri.substringAfter("://").substringBefore(':').substringBefore('/').lowercase()
    if (host.isBlank() || host == "localhost" || host.endsWith(".local")) return true
    val ip = ipv4FromServerOrigin(uri) ?: return false
    return isPrivateOrLoopbackIpv4(ip)
}

/**
 * `http://<public-ipv4>:32400` that was not advertised by Plex. Remote access uses the 8443
 * relay; this port is usually a synthesized WAN fallback and burns playback failover budget.
 *
 * When [advertisedUris] contains this origin, it is kept — some servers do expose :32400
 * on a public address on purpose.
 */
fun isPublicSynthesizedPlexHttpOrigin(
    uri: String,
    advertisedUris: Collection<String> = emptyList(),
): Boolean {
    if (!uri.startsWith("http://", ignoreCase = true)) return false
    if (isLocalOnlyServerOrigin(uri)) return false
    val rest = uri.substringAfter("://")
    val host = rest.substringBefore(':').substringBefore('/')
    val port = rest.substringAfter(':', missingDelimiterValue = "80")
        .substringBefore('/')
        .toIntOrNull() ?: 80
    if (port != 32400) return false
    val parts = host.split('.')
    if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return false
    val origin = "http://$host:$port"
    return advertisedUris.none { it.trimEnd('/') == origin }
}

/**
 * True when this device has IPv4 prefixes and none of them match the server's advertised
 * LAN addresses — e.g. Windows on `192.168.4.0` vs Plex's `172.16.1.2`.
 */
fun NetworkIdentity.shouldSkipAdvertisedLan(server: PlexServer): Boolean {
    if (localIpv4Prefixes.isEmpty()) return false
    val locals = (listOf(server.uri) + server.localConnectionUris + server.advertisedConnectionUris)
        .distinct()
        .filter { isLocalOnlyServerOrigin(it) }
    if (locals.isEmpty()) return false
    return locals.none { origin ->
        val ip = ipv4FromServerOrigin(origin) ?: return@none false
        ipv4Slash24Prefix(ip) in localIpv4Prefixes
    }
}

/** Swap scheme/host/port of an absolute HTTP(S) URL onto [origin]; keep path/query/fragment. */
fun rebaseHttpUrlOrigin(url: String, origin: String): String? {
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

/** Origin form `scheme://host:port` for an absolute HTTP(S) URL. */
fun httpUrlOriginOf(url: String): String? {
    if (url.isBlank()) return null
    val parsed = runCatching { Url(url) }.getOrNull() ?: return null
    if (parsed.protocol.name != "http" && parsed.protocol.name != "https") return null
    if (parsed.host.isBlank()) return null
    return "${parsed.protocol.name}://${parsed.host}:${parsed.port}"
}

internal fun ipv4FromServerOrigin(uri: String): String? {
    val host = uri.substringAfter("://").substringBefore(':').substringBefore('/').lowercase()
    if (host.isBlank()) return null
    host.split('.').let { parts ->
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) return host
    }
    return decodedIpFromPlexDirect(uri)
}

private fun playbackOriginEquals(a: String, b: String): Boolean {
    val left = httpUrlOriginOf(a) ?: a.trimEnd('/')
    val right = httpUrlOriginOf(b) ?: b.trimEnd('/')
    return left.equals(right, ignoreCase = true)
}

private fun isPrivateOrLoopbackIpv4(ip: String): Boolean {
    val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return false
    val a = parts[0]
    val b = parts[1]
    return a == 10 ||
        a == 127 ||
        (a == 192 && b == 168) ||
        (a == 172 && b in 16..31) ||
        (a == 169 && b == 254)
}
