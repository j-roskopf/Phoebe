package com.phoebe.app.data

import com.phoebe.app.domain.PlexServer
import com.phoebe.app.platform.NetworkIdentity
import com.phoebe.app.platform.ipv4Slash24Prefix

/**
 * Ordered Plex server base URLs — Plex-advertised LAN first, synthesized fallbacks last.
 *
 * Plex often advertises `https://172-105-8-66.<token>.plex.direct:8443` alongside
 * `http://192.168.x.x:32400`. We only synthesize `http://<ip>:32400` when the plex.direct
 * host encodes a *private* address; a WAN IP on :32400 is usually closed and burns failover
 * budget. Servers requiring HTTPS get no synthesized entries at all — see [expandConnectionUris].
 *
 * When [demoteLocalOrigins] is true (cellular / unknown Wi-Fi), LAN-only hosts sort after
 * remote relays so playback and API probes do not burn seconds on dead private addresses.
 */
fun PlexServer.reachableBaseUris(
    preferredFirst: String? = null,
    demoteLocalOrigins: Boolean = false,
): List<String> {
    val primary = uri.trimEnd('/').takeIf { it.isNotBlank() }
    val advertised = advertisedConnectionUris.ifEmpty { connectionUris }
    val expanded = when {
        advertised.isNotEmpty() ->
            listOfNotNull(primary) + expandConnectionUris(advertised, httpsRequired = httpsRequired)
        primary != null -> listOf(primary)
        else -> emptyList()
    }.distinct()
    val advertisedSet = advertised.toSet()
    val localSet = localConnectionUris.toSet()
    val ordered = expanded.sortedWith(
        compareBy(
            { uri ->
                val keepPrimaryFirst = uri == primary &&
                    !(demoteLocalOrigins && isLocalOnlyServerOrigin(uri))
                !keepPrimaryFirst
            },
            { if (demoteLocalOrigins) isLocalOnlyServerOrigin(it) else it !in localSet },
            { it !in advertisedSet },
            { connectionPriority(it, demoteLocalOrigins) },
        ),
    )
    val preferred = preferredFirst?.trimEnd('/')?.takeIf { it.isNotBlank() }
    val preferPreferred = preferred != null &&
        !(demoteLocalOrigins && isLocalOnlyServerOrigin(preferred))
    val withPreferred = if (preferPreferred && preferred != null) {
        listOf(preferred) + ordered.filter { it != preferred }
    } else {
        ordered
    }
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

internal fun connectionPriority(uri: String, demoteLocalOrigins: Boolean = false): Int {
    val port = uri.substringAfter("://").substringAfter(':', "32400").substringBefore('/').toIntOrNull() ?: 32400
    val secure = uri.startsWith("https://")
    val local = isLocalOnlyServerOrigin(uri)
    val plexDirect = uri.contains(".plex.direct", ignoreCase = true)
    if (demoteLocalOrigins && local) {
        return 200 + when {
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

internal fun ipv4FromServerOrigin(uri: String): String? {
    val host = uri.substringAfter("://").substringBefore(':').substringBefore('/').lowercase()
    if (host.isBlank()) return null
    host.split('.').let { parts ->
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) return host
    }
    return decodedIpFromPlexDirect(uri)
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
