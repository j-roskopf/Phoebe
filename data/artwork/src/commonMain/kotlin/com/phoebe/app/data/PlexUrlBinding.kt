package com.phoebe.app.data

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Build an absolute Plex media URL from a relative PMS path (or a legacy absolute URL)
 * and the current live server base — Shuttle2 / Jellyfin-provider bind-at-request.
 *
 * Catalog should store relative paths only; [liveBase] comes from plex.tv `/resources`
 * after [PlexConnectionResolver] picks a probed winner.
 */
fun bindPlexUrl(pathOrUrl: String, liveBase: String, token: String): String {
    val base = liveBase.trimEnd('/').takeIf { it.isNotBlank() } ?: return pathOrUrl
    val path = plexAssetPath(pathOrUrl) ?: return pathOrUrl
    val builder = URLBuilder(base)
    val pathOnly = path.substringBefore('?').trimStart('/')
    if (pathOnly.isNotBlank()) {
        builder.appendPathSegments(pathOnly.split('/').filter { it.isNotBlank() })
    }
    // Non-token query comes from the stripped path; token may only exist on the original URL.
    val pathQuery = if ('?' in path) path.substringAfter('?') else ""
    if (pathQuery.isNotBlank()) {
        pathQuery.split('&').forEach { param ->
            if (param.isBlank()) return@forEach
            val key = param.substringBefore('=')
            val value = param.substringAfter('=', missingDelimiterValue = "")
            if (key.isNotBlank() && !key.equals("X-Plex-Token", ignoreCase = true)) {
                builder.parameters.append(key, value)
            }
        }
    }
    val preservedToken = pathOrUrl.substringAfter('?', "").substringBefore('#')
        .split('&')
        .firstOrNull { it.substringBefore('=').equals("X-Plex-Token", ignoreCase = true) }
        ?.substringAfter('=', missingDelimiterValue = "")
        ?.takeIf { it.isNotBlank() }
    val auth = token.takeIf { it.isNotBlank() } ?: preservedToken
    if (!auth.isNullOrBlank()) {
        builder.parameters.append("X-Plex-Token", auth)
    }
    return builder.buildString()
}

private const val PlexPhotoTranscodePath = "/photo/:/transcode"

/**
 * Linthra / python-plexapi cover URL: full-size thumb, or PMS `/photo/:/transcode`
 * when [size] is set (list tiles). Token is woven at request time only.
 */
fun bindPlexCoverArt(
    pathOrUrl: String,
    liveBase: String,
    token: String,
    size: Int? = null,
): String {
    val path = plexAssetPath(pathOrUrl) ?: return bindPlexUrl(pathOrUrl, liveBase, token)
    val pathOnly = path.substringBefore('?').let { if (it.startsWith('/')) it else "/$it" }
    if (size == null || size <= 0 || pathOnly == PlexPhotoTranscodePath) {
        return bindPlexUrl(pathOrUrl, liveBase, token)
    }
    val base = liveBase.trimEnd('/').takeIf { it.isNotBlank() } ?: return pathOrUrl
    val builder = URLBuilder("$base$PlexPhotoTranscodePath")
    builder.parameters.append("width", size.toString())
    builder.parameters.append("height", size.toString())
    builder.parameters.append("minSize", "1")
    builder.parameters.append("upscale", "1")
    builder.parameters.append("url", pathOnly)
    val auth = token.takeIf { it.isNotBlank() }
    if (!auth.isNullOrBlank()) {
        builder.parameters.append("X-Plex-Token", auth)
    }
    return builder.buildString()
}

/**
 * Relative PMS asset path suitable for catalog storage (no host, no token).
 * Accepts a raw Plex `thumb` / `part.key` or a legacy absolute URL.
 */
fun plexAssetPath(pathOrUrl: String): String? {
    val raw = pathOrUrl.trim()
    if (raw.isBlank()) return null
    if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
        val path = raw.substringBefore('?').let { if (it.startsWith('/')) it else "/$it" }
        val keptQuery = raw.substringAfter('?', "")
            .substringBefore('#')
            .split('&')
            .filter { it.isNotBlank() && !it.substringBefore('=').equals("X-Plex-Token", ignoreCase = true) }
            .joinToString("&")
        return if (keptQuery.isBlank()) path else "$path?$keptQuery"
    }
    if (!raw.isPlexMediaPathOrUrl()) return null
    val parsed = runCatching { Url(raw) }.getOrNull() ?: return null
    val path = parsed.encodedPath.ifBlank { "/" }
    // Keep non-token query params (rare on thumbs); token is re-applied at bind.
    val keptQuery = raw.substringAfter('?', "")
        .substringBefore('#')
        .split('&')
        .filter { it.isNotBlank() && !it.substringBefore('=').equals("X-Plex-Token", ignoreCase = true) }
        .joinToString("&")
    return if (keptQuery.isBlank()) path else "$path?$keptQuery"
}

fun String.isPlexMediaPathOrUrl(): Boolean {
    if (startsWith("/library/") || startsWith("/playlists/") || startsWith("/photo/") || startsWith("/:/")) {
        return true
    }
    if (!startsWith("http://") && !startsWith("https://")) {
        return contains("/library/") ||
            contains("/playlists/") ||
            contains("/photo/") ||
            contains("/music/:/transcode/")
    }
    return contains(".plex.direct", ignoreCase = true) ||
        contains("X-Plex-Token", ignoreCase = true) ||
        contains("/library/", ignoreCase = true) ||
        contains("/playlists/", ignoreCase = true) ||
        contains("/photo/", ignoreCase = true) ||
        contains("/music/:/transcode/", ignoreCase = true)
}

/** Session token for binding relative Plex paths at artwork request time. */
object ArtworkAuthHolder {
    private val mutable = MutableStateFlow<String?>(null)
    val tokenFlow: StateFlow<String?> = mutable.asStateFlow()

    val plexToken: String?
        get() = mutable.value

    fun update(token: String?) {
        mutable.value = token?.takeIf { it.isNotBlank() }
    }

    fun clear() {
        mutable.value = null
    }
}
