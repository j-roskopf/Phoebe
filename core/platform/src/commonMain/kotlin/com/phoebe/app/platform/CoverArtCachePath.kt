package com.phoebe.app.platform

/**
 * Path, relative to the storage root, of a cached cover-art file.
 *
 * Always `.jpg`: entries are re-encoded to a thumbnail when cached, so the source
 * extension is irrelevant and keeping it would only misdescribe the contents.
 *
 * Separate from data/artwork's `cachedArtworkPathForUrl`: that one is resolved against
 * the *download* directory by `PlatformStorage.writeBytes`, and is only populated when a
 * track is downloaded for offline use. Notifications need a local file for any played
 * track, so they need their own cache.
 */
fun coverArtCachePath(url: String): String = "coverart/${url.stableCoverArtHash()}.jpg"

private fun String.stableCoverArtHash(): String {
    var hash = 1125899906842597L
    forEach { c -> hash = (hash * 31) + c.code }
    return hash.toString()
}
