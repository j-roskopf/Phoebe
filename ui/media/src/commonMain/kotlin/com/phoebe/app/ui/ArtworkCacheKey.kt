package com.phoebe.app.ui

import com.phoebe.app.data.bindPlexCoverArt
import com.phoebe.app.data.isPlexMediaPathOrUrl

/**
 * A host that never resolves, used only to build a fetch URL whose *shape* is right when no
 * live origin is known yet. [stableArtworkCacheKey] strips the origin back off, so a URL built
 * on this placeholder keys identically to the same asset fetched from a real relay or LAN hop.
 */
private const val PlaceholderArtworkOrigin = "https://origin.invalid"

/**
 * Query parameters that identify the *session* rather than the *image*.
 *
 * Plex reissues `X-Plex-Token`, and Subsonic's `u`/`t`/`s`/`p` rotate per request. Leaving them
 * in a cache key means every token refresh silently orphans the entire artwork cache.
 */
private val VolatileArtworkQueryParams = setOf(
    "x-plex-token",
    "api_key",
    "apikey",
    "u",
    "p",
    "t",
    "s",
    "c",
    "v",
)

/**
 * Origin- and token-independent Coil cache key for an artwork fetch URL.
 *
 * Coil keys its memory and disk caches by request URL (`options.diskCacheKey ?: url`). Ours embed
 * the live Plex origin and the session token, which has two costs: the cache is orphaned wholesale
 * every time the relay rotates, and — the expensive one — it cannot be consulted at all until an
 * origin has been probed. That is exactly the window where a cold start most needs it: a phone that
 * spent 45s failing to reach any Plex hop still had every thumbnail on disk and could not show one.
 *
 * Keying on path + non-volatile query keeps entries valid across every hop to the same server.
 *
 * Returns null for local `file://` / `content://` artwork, where Coil's own key is already stable
 * and no origin is involved.
 */
internal fun stableArtworkCacheKey(fetchUrl: String): String? {
    val raw = fetchUrl.trim()
    if (raw.isBlank()) return null
    val remote = raw.startsWith("http://", ignoreCase = true) ||
        raw.startsWith("https://", ignoreCase = true)
    val identity = when {
        remote -> originIndependentRemotePath(raw)
        // A host-less Plex path is already origin-independent.
        raw.isPlexMediaPathOrUrl() -> normalizeArtworkPathAndQuery(raw)
        else -> null
    } ?: return null
    return "phoebe-art:$identity"
}

/** Strip `scheme://host:port` and session query parameters, keeping a stable parameter order. */
private fun originIndependentRemotePath(url: String): String? {
    val withoutFragment = url.substringBefore('#')
    val afterScheme = withoutFragment.substringBefore('?').substringAfter("://", "")
    if (afterScheme.isBlank()) return null
    val slash = afterScheme.indexOf('/')
    val path = if (slash < 0) "/" else afterScheme.substring(slash)
    return normalizeArtworkPathAndQuery(path + queryOf(withoutFragment))
}

private fun queryOf(withoutFragment: String): String =
    withoutFragment.substringAfter('?', "").let { if (it.isBlank()) "" else "?$it" }

private fun normalizeArtworkPathAndQuery(pathAndQuery: String): String {
    val path = pathAndQuery.substringBefore('?').ifBlank { "/" }
    val params = pathAndQuery.substringAfter('?', "")
        .split('&')
        .filter { it.isNotBlank() }
        .filterNot { it.substringBefore('=').lowercase() in VolatileArtworkQueryParams }
        // Sorted so a parameter reordering upstream cannot split one image across two entries.
        .sorted()
    return if (params.isEmpty()) path else "$path?${params.joinToString("&")}"
}

/**
 * The fetch URLs this artwork *would* use, built against [PlaceholderArtworkOrigin].
 *
 * Used to look an image up in Coil's disk cache before any origin is known. Because
 * [stableArtworkCacheKey] discards the origin, these key identically to the real requests.
 */
internal fun placeholderArtworkFetchUrls(sourceUrl: String, maxDecodeDimension: Int): List<String> {
    val raw = sourceUrl.trim()
    if (raw.isBlank()) return emptyList()
    if (raw.isPlexMediaPathOrUrl()) {
        return listOf(
            bindPlexCoverArt(raw, PlaceholderArtworkOrigin, token = "", size = maxDecodeDimension),
            bindPlexCoverArt(raw, PlaceholderArtworkOrigin, token = "", size = null),
        ).distinct()
    }
    if (!raw.startsWith("http://", ignoreCase = true) &&
        !raw.startsWith("https://", ignoreCase = true)
    ) {
        return emptyList()
    }
    return remoteArtworkRequestUrls(raw, maxDecodeDimension)
}
