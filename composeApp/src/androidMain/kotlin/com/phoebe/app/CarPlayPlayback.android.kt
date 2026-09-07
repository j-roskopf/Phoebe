package com.phoebe.app

import com.phoebe.app.domain.Track
import com.phoebe.app.domain.canTogglePlexLike
import com.phoebe.app.domain.isLikedSongsPlaylist
import com.phoebe.app.domain.supportsRemotePlaylists
import com.phoebe.app.player.AndroidPlaybackBridge

actual fun bindCarPlayPlayback(state: AppState) {
    AndroidPlaybackBridge.onToggleLikedTrack = { track ->
        state.toggleLikedTrack(track).join()
    }
    AndroidPlaybackBridge.isLikeAvailable = { track ->
        track.canTogglePlexLike() && state.session.value.supportsRemotePlaylists()
    }
    AndroidPlaybackBridge.isTrackLiked = { track ->
        val catalog = state.catalog.value
        val likedPlaylist = catalog.playlists.firstOrNull { it.isLikedSongsPlaylist() }
        val likedTracks = likedPlaylist?.let { catalog.tracksByParent[it.id] }.orEmpty()
        likedTracks.any { it.hasSameProviderIdentity(track) }
    }
}

private fun Track.hasSameProviderIdentity(other: Track): Boolean =
    equivalentProviderIds(id).any { it in equivalentProviderIds(other.id) }

private fun equivalentProviderIds(id: String): Set<String> {
    if (id.isBlank()) return emptySet()
    if (id.startsWith("plex:")) return setOf(id, id.removePrefix("plex:"))
    if (id.startsWith("jellyfin:")) return setOf(id, id.removePrefix("jellyfin:"))
    if (id.startsWith("emby:")) return setOf(id, id.removePrefix("emby:"))
    if (id.startsWith("navidrome:")) return setOf(id, id.removePrefix("navidrome:"))
    return if (':' in id) setOf(id) else setOf(id, "plex:$id", "jellyfin:$id", "emby:$id", "navidrome:$id")
}
