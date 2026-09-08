package com.phoebe.app

import com.phoebe.app.domain.Track
import com.phoebe.app.domain.canTogglePlexLike
import com.phoebe.app.domain.hasSameProviderTrackIdentity
import com.phoebe.app.domain.isLikedSongsPlaylist
import com.phoebe.app.domain.supportsRemotePlaylists
import com.phoebe.app.player.AndroidPlaybackBridge

actual fun bindCarPlayPlayback(state: AppState) {
    AndroidPlaybackBridge.onToggleLikedTrack = { track ->
        state.toggleLikedTrack(track).join()
        AndroidPlaybackBridge.onLikeStateMayHaveChanged?.invoke()
    }
    AndroidPlaybackBridge.isLikeAvailable = { track ->
        track.canTogglePlexLike() && state.session.value.supportsRemotePlaylists()
    }
    AndroidPlaybackBridge.isTrackLiked = { track ->
        val catalog = state.catalog.value
        val likedPlaylist = catalog.playlists.firstOrNull { it.isLikedSongsPlaylist() }
        val likedTracks = likedPlaylist?.let { catalog.tracksByParent[it.id] }.orEmpty()
        likedTracks.any { it.hasSameProviderTrackIdentity(track) }
    }
    AndroidPlaybackBridge.currentTrack = {
        state.player.value.currentTrack
    }
}
