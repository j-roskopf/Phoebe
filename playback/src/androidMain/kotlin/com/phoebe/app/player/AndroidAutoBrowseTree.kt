package com.phoebe.app.player

import android.net.Uri
import com.phoebe.app.AndroidContextHolder
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.Track
import androidx.media3.common.MediaItem

/**
 * Android Auto / MediaLibrary adapter over [CatalogBrowseTree].
 */
internal class AndroidAutoBrowseTree(
    database: PhoebeDatabase,
) {
    private val tree = CatalogBrowseTree(database)

    fun rootChildren(hasCachedCatalog: Boolean): List<MediaItem> =
        tree.rootChildren(hasCachedCatalog).map { it.toMediaItem() }

    fun getChildren(parentId: String): List<MediaItem> =
        tree.getChildren(parentId).map { it.toMediaItem() }

    fun getAlbum(albumId: String): Album? = tree.getAlbum(albumId)

    fun getPlaylist(playlistId: String): Playlist? = tree.getPlaylist(playlistId)

    fun getItem(mediaId: String): MediaItem? = tree.getItem(mediaId)?.toMediaItem()

    fun trackById(trackId: String): Track? = tree.trackById(trackId)

    fun tracksForPlayableMediaId(mediaId: String): List<Track> = tree.tracksForPlayableMediaId(mediaId)

    fun startIndexForMediaId(mediaId: String, tracks: List<Track>, fallback: Int): Int =
        tree.startIndexForMediaId(mediaId, tracks, fallback)

    fun searchTracks(
        query: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        playlist: String? = null,
        genre: String? = null,
    ): List<Track> = tree.searchTracks(
        query = query,
        title = title,
        artist = artist,
        album = album,
        playlist = playlist,
        genre = genre,
    )

    private fun BrowseNode.toMediaItem(): MediaItem =
        when {
            isPlayable && track != null -> browseTrackItem(track, mediaId)
            isPlayable -> browsePlayableActionItem(
                mediaId = mediaId,
                title = title,
                subtitle = subtitle,
                artworkUri = drawableArtUri(android.R.drawable.ic_menu_agenda),
            )
            mediaId == BrowseMediaIds.ARTISTS -> browseFolderItem(
                mediaId = mediaId,
                title = title,
                artworkUri = drawableArtUri(android.R.drawable.ic_menu_myplaces),
            )
            mediaId == BrowseMediaIds.ALBUMS -> browseFolderItem(
                mediaId = mediaId,
                title = title,
                artworkUri = drawableArtUri(android.R.drawable.ic_menu_gallery),
            )
            mediaId == BrowseMediaIds.PLAYLISTS -> browseFolderItem(
                mediaId = mediaId,
                title = title,
                artworkUri = drawableArtUri(android.R.drawable.ic_menu_agenda),
            )
            else -> browseFolderItem(
                mediaId = mediaId,
                title = title,
                artworkUri = thumbUrl?.toBrowseArtworkUri(),
            )
        }

    private fun drawableArtUri(drawableRes: Int): Uri {
        return Uri.parse("android.resource://android/$drawableRes")
    }
}
