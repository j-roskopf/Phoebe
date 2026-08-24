package com.phoebe.app.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.Track

internal fun browseFolderItem(
    mediaId: String,
    title: String,
    artworkUri: Uri? = null,
): MediaItem =
    MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .apply { artworkUri?.let { setArtworkUri(it) } }
                .build(),
        )
        .build()

internal fun browseTrackItem(track: Track): MediaItem {
    val uriString = StreamingPlaybackPolicyHolder.resolvePlaybackUri(track)
        .takeIf { it.isNotBlank() }
        .orEmpty()
    val isHls = uriString.contains(".m3u8", ignoreCase = true)
    return MediaItem.Builder()
        .setMediaId(track.id)
        .setUri(uriString.toAndroidUri())
        .apply {
            if (isHls) {
                setMimeType("application/x-mpegURL")
            }
        }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setDisplayTitle(track.title)
                .setArtist(track.artist)
                .setAlbumArtist(track.artist)
                .setSubtitle(track.artist)
                .setAlbumTitle(track.album)
                .setDescription(track.descriptionForCarDisplay())
                .setDurationMs(track.durationMs.takeIf { it > 0L })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .apply { track.thumbUrl?.let { setArtworkUri(it.toAndroidUri()) } }
                .build(),
        )
        .build()
}

internal fun browseTrackItem(track: Track, mediaId: String): MediaItem =
    browseTrackItem(track).buildUpon()
        .setMediaId(mediaId)
        .build()

internal fun browsePlayableActionItem(
    mediaId: String,
    title: String,
    subtitle: String? = null,
    artworkUri: Uri? = null,
): MediaItem =
    MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setDisplayTitle(title)
                .apply { subtitle?.let { setSubtitle(it) } }
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .apply { artworkUri?.let { setArtworkUri(it) } }
                .build(),
        )
        .build()

internal fun Artist.toBrowseItem(): MediaItem =
    browseFolderItem(
        mediaId = BrowseMediaIds.artist(id),
        title = title,
        artworkUri = thumbUrl?.toAndroidUri(),
    )

internal fun Album.toBrowseItem(): MediaItem =
    browseFolderItem(
        mediaId = BrowseMediaIds.album(id),
        title = title,
        artworkUri = thumbUrl?.toAndroidUri(),
    )

internal fun Playlist.toBrowseItem(): MediaItem =
    browseFolderItem(
        mediaId = BrowseMediaIds.playlist(id),
        title = title,
        artworkUri = thumbUrl?.toAndroidUri(),
    )

internal const val InAppPlaybackExtra: String = "com.phoebe.app.IN_APP_PLAYBACK"

internal fun playbackMediaItem(track: Track, inAppPlayback: Boolean = false): MediaItem {
    val item = browseTrackItem(track)
    if (!inAppPlayback) return item
    return item.buildUpon()
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setExtras(Bundle().apply { putBoolean(InAppPlaybackExtra, true) })
                .build(),
        )
        .build()
}

private fun Track.descriptionForCarDisplay(): String =
    listOf(artist, album)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" - ")

private fun String.toAndroidUri(): Uri = Uri.parse(this)
