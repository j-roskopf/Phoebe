package com.phoebe.app.data

import com.phoebe.app.domain.PlexServer

/**
 * Ordered Plex server base URLs — Plex-advertised LAN first, synthesized fallbacks last.
 *
 * Plex often advertises `https://172-105-8-66.<token>.plex.direct:8443` alongside
 * `http://192.168.x.x:32400`. We synthesize `http://172.105.8.66:32400` from the plex.direct
 * hostname, but that address is usually the server's *public* IP and is often unreachable on
 * LAN; real local URLs from Plex must win.
 */
fun PlexServer.reachableBaseUris(preferredFirst: String? = null): List<String> {
    val primary = uri.trimEnd('/').takeIf { it.isNotBlank() }
    val advertised = advertisedConnectionUris.ifEmpty { connectionUris }
    val expanded = when {
        advertised.isNotEmpty() -> listOfNotNull(primary) + expandConnectionUris(advertised)
        primary != null -> listOf(primary)
        else -> emptyList()
    }.distinct()
    val advertisedSet = advertised.toSet()
    val localSet = localConnectionUris.toSet()
    val ordered = expanded.sortedWith(
        compareBy(
            { it != primary },
            { it !in localSet },
            { it !in advertisedSet },
            { connectionPriority(it) },
        ),
    )
    val withPreferred = listOfNotNull(preferredFirst?.trimEnd('/')) +
        ordered.filter { it != preferredFirst?.trimEnd('/') }
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
fun PlexServer.timelineBaseUris(preferredFirst: String? = null): List<String> {
    val bases = reachableBaseUris(preferredFirst)
    val preferred = preferredFirst?.trimEnd('/')?.takeIf { it.isNotBlank() }
    val preferRemote = preferred != null && !isLocalOnlyServerOrigin(preferred)
    if (!preferRemote) return bases
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
        connectionUris = expandConnectionUris(advertisedUris),
        advertisedConnectionUris = advertisedUris,
        localConnectionUris = localUris,
        httpsRequired = httpsRequired,
    )
    return server.reachableBaseUris().firstOrNull()
}

/** Advertised URLs first, then synthesized plain-IP fallbacks derived from plex.direct hosts. */
fun expandConnectionUris(advertisedUris: List<String>): List<String> =
    buildList {
        val advertised = advertisedUris.map { it.trimEnd('/') }.filter { it.isNotBlank() }
        addAll(advertised)
        for (uri in advertised) {
            val port = uri.substringAfter("://").substringAfter(':', "").substringBefore('/').toIntOrNull()
            decodedIpFromPlexDirect(uri)?.let { ip ->
                add("http://$ip:32400")
                add("https://$ip:32400")
                if (port == 8443) add("https://$ip:8443")
            }
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

internal fun connectionPriority(uri: String): Int {
    val port = uri.substringAfter("://").substringAfter(':', "32400").substringBefore('/').toIntOrNull() ?: 32400
    val secure = uri.startsWith("https://")
    val local = isLocalOnlyServerOrigin(uri)
    val plexDirect = uri.contains(".plex.direct", ignoreCase = true)
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
    val host = uri.substringAfter("://").substringBefore(':').substringBefore('/').lowercase()
    if (host.isBlank() || host == "localhost" || host.endsWith(".local")) return true
    val ip = when {
        host.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } } -> host
        else -> decodedIpFromPlexDirect(uri)
    } ?: return false
    return isPrivateOrLoopbackIpv4(ip)
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
