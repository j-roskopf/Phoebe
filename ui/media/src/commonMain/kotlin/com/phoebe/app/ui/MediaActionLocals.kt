package com.phoebe.app.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.phoebe.app.data.PlayHistorySnapshot
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.DownloadItem
import com.phoebe.app.domain.DownloadState
import com.phoebe.app.domain.DownloadStatusEvent
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.SmartPlaylist
import com.phoebe.app.domain.SmartPlaylistTemplate
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.isRemoteLibraryTrack
import com.phoebe.app.playlists.PlaylistExportFormat
import kotlin.math.max
import kotlin.math.roundToInt

data class DownloadStatusSummary(
    val total: Int,
    val complete: Int,
    val active: Int,
    val activeProgress: Float,
    val failed: Int,
    val unavailable: Int,
)

class DownloadStatusSnapshot(
    itemsByTrackId: Map<String, DownloadItem> = emptyMap(),
    hasActiveDownloadJobs: Boolean = false,
) {
    private data class DownloadUiItem(
        val state: DownloadState,
        val progress: Float,
        val localUri: String?,
        val updatedAtMs: Long,
    )

    private val itemStateByTrackId = linkedMapOf<String, DownloadUiItem>().apply {
        putAll(itemsByTrackId.mapValues { (_, item) -> item.toDownloadUiItem() })
    }
    private val progressStateByTrackId = linkedMapOf<String, Float>().apply {
        itemsByTrackId.forEach { (trackId, item) ->
            if (item.state == DownloadState.Downloading) {
                put(trackId, item.progress.coerceIn(0f, 1f))
            }
        }
    }
    private var stateVersion by mutableIntStateOf(0)
    var hasActiveDownloadJobs by mutableStateOf(hasActiveDownloadJobs)
        private set

    fun replaceItems(items: List<DownloadItem>) {
        var changed = false
        val nextIds = items.mapTo(mutableSetOf()) { it.trackId }
        val itemIterator = itemStateByTrackId.keys.iterator()
        while (itemIterator.hasNext()) {
            if (itemIterator.next() !in nextIds) {
                itemIterator.remove()
                changed = true
            }
        }
        val progressIterator = progressStateByTrackId.keys.iterator()
        while (progressIterator.hasNext()) {
            if (progressIterator.next() !in nextIds) {
                progressIterator.remove()
                changed = true
            }
        }
        items.forEach { item ->
            changed = updateDownloadItem(item) || changed
        }
        if (changed) bumpStateVersion()
    }

    private fun updateDownloadItem(item: DownloadItem): Boolean {
        val existing = itemStateByTrackId[item.trackId]
        if (existing != null && shouldKeepExistingDownloadItem(existing, item)) return false
        var changed = false
        if (item.state == DownloadState.Downloading) {
            val progress = item.progress.coerceIn(0f, 1f)
            if (progressStateByTrackId[item.trackId] != progress) {
                progressStateByTrackId[item.trackId] = progress
                changed = true
            }
        } else {
            if (progressStateByTrackId.remove(item.trackId) != null) {
                changed = true
            }
        }
        val coarseItem = item.toDownloadUiItem()
        if (itemStateByTrackId[item.trackId] != coarseItem) {
            itemStateByTrackId[item.trackId] = coarseItem
            changed = true
        }
        return changed
    }

    private fun shouldKeepExistingDownloadItem(existing: DownloadUiItem, incoming: DownloadItem): Boolean {
        val existingComplete = existing.state == DownloadState.Complete || !existing.localUri.isNullOrBlank()
        val incomingIncomplete = incoming.state != DownloadState.Complete && incoming.localUri.isNullOrBlank()
        if (existingComplete && incomingIncomplete) return true
        return existing.updatedAtMs > 0L &&
            incoming.updatedAtMs > 0L &&
            incoming.updatedAtMs < existing.updatedAtMs
    }

    fun apply(event: DownloadStatusEvent) {
        var changed = false
        event.removedTrackIds.forEach { trackId ->
            if (itemStateByTrackId.remove(trackId) != null) changed = true
            if (progressStateByTrackId.remove(trackId) != null) changed = true
        }
        event.items.forEach { item ->
            changed = updateDownloadItem(item) || changed
        }
        if (changed) bumpStateVersion()
    }

    fun setActiveDownloadJobs(active: Boolean) {
        hasActiveDownloadJobs = active
    }

    fun itemFor(track: Track): DownloadItem? {
        observeDownloadState()
        val item = itemStateByTrackId[track.id] ?: return null
        val progress = if (item.state == DownloadState.Downloading) {
            progressStateByTrackId[track.id] ?: item.progress
        } else {
            item.progress
        }
        return DownloadItem(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            state = item.state,
            progress = progress,
            localUri = item.localUri,
            downloadUrl = track.downloadUrl,
            updatedAtMs = item.updatedAtMs,
        )
    }

    fun isComplete(track: Track): Boolean {
        observeDownloadState()
        val item = itemStateByTrackId[track.id]
        return isComplete(track, item)
    }

    val count: Int
        get() {
            observeDownloadState()
            return itemStateByTrackId.size
        }

    fun isActive(track: Track): Boolean {
        observeDownloadState()
        val item = itemStateByTrackId[track.id]
        return isActive(item, isComplete(track, item))
    }

    fun isFailed(track: Track): Boolean {
        observeDownloadState()
        val item = itemStateByTrackId[track.id]
        return isFailed(track, item, isComplete(track, item))
    }

    fun summarize(tracks: List<Track>): DownloadStatusSummary {
        observeDownloadState()
        var complete = 0
        var active = 0
        var activeProgress = 0f
        var failed = 0
        var unavailable = 0
        tracks.forEach { track ->
            val item = itemStateByTrackId[track.id]
            val isComplete = isComplete(track, item)
            if (isComplete) {
                complete++
            } else {
                if (isActive(item, isComplete)) {
                    active++
                    activeProgress += item?.progress?.coerceIn(0f, 1f) ?: 0f
                }
                if (isFailed(track, item, isComplete)) {
                    failed++
                }
                if (track.downloadUrl.isBlank()) {
                    unavailable++
                }
            }
        }
        return DownloadStatusSummary(
            total = tracks.size,
            complete = complete,
            active = active,
            activeProgress = activeProgress,
            failed = failed,
            unavailable = unavailable,
        )
    }

    private fun observeDownloadState() {
        stateVersion
    }

    private fun bumpStateVersion() {
        stateVersion++
    }

    private fun isComplete(track: Track, item: DownloadUiItem?): Boolean =
        item?.state == DownloadState.Complete ||
            !item?.localUri.isNullOrBlank() ||
            (!track.localUri.isNullOrBlank() && (track.downloadUrl.isNotBlank() || track.isRemoteLibraryTrack()))

    private fun isActive(item: DownloadUiItem?, complete: Boolean): Boolean {
        if (complete || item == null) return false
        val activeState = item.state == DownloadState.Queued || item.state == DownloadState.Downloading
        return activeState && (hasActiveDownloadJobs || item.updatedAtMs > 0L)
    }

    private fun isFailed(track: Track, item: DownloadUiItem?, complete: Boolean): Boolean =
        track.downloadUrl.isNotBlank() && !complete && item?.state == DownloadState.Failed

    private fun DownloadItem.toDownloadUiItem(): DownloadUiItem {
        val normalizedProgress = progress.coerceIn(0f, 1f)
        val coarseProgress = if (state == DownloadState.Downloading) {
            val coarse = (normalizedProgress * 20f).roundToInt() / 20f
            if (normalizedProgress > 0f) max(0.05f, coarse) else 0f
        } else {
            normalizedProgress
        }
        return DownloadUiItem(
            state = state,
            progress = coarseProgress,
            localUri = localUri,
            updatedAtMs = updatedAtMs,
        )
    }
}

val LocalDownloadStatus = compositionLocalOf { DownloadStatusSnapshot() }

data class DownloadActions(
    val onDeleteDownloadedTracks: (List<Track>) -> Unit = {},
    val onCancelDownloadedTracks: (List<Track>) -> Unit = {},
)

val LocalDownloadActions = compositionLocalOf { DownloadActions() }

/** Current playback state, exposed implicitly so any track row can show a now-playing badge. */
data class NowPlayingIndicatorState(
    val trackId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
)

val LocalNowPlaying = compositionLocalOf { NowPlayingIndicatorState() }

val LocalPlayHistory = compositionLocalOf { PlayHistorySnapshot() }

val LocalTracksLoading = compositionLocalOf { emptySet<String>() }

data class PlaylistActions(
    val playlists: List<Playlist> = emptyList(),
    val smartPlaylists: List<SmartPlaylist> = emptyList(),
    val playlistsEnabled: Boolean = false,
    val onAddTrackToPlaylist: (Playlist, Track, Boolean) -> Unit = { _, _, _ -> },
    val onMovePlaylistTrack: (Playlist, Int, Int) -> Unit = { _, _, _ -> },
    val onRemovePlaylistTracks: (Playlist, List<Track>) -> Unit = { _, _ -> },
    val onCopyPlaylistToPlaylist: (source: Playlist, target: Playlist) -> Unit = { _, _ -> },
    val onDeletePlaylist: (Playlist) -> Unit = {},
    val onSaveSmartPlaylistToProvider: (Playlist) -> Unit = {},
    val onCreatePlaylist: (title: String, initialTracks: List<Track>) -> Unit = { _, _ -> },
    val onRequestCreatePlaylist: (initialTracks: List<Track>) -> Unit = {},
    val onOpenLikedSongs: () -> Unit = {},
    val onExportLocalPlaylist: (Playlist, PlaylistExportFormat) -> Unit = { _, _ -> },
    val onShufflePlaylist: (Playlist) -> Unit = {},
    val smartPlaylistTemplates: List<SmartPlaylistTemplate> = SmartPlaylistTemplate.Defaults,
    val onCreateSmartPlaylist: (SmartPlaylistTemplate, String) -> Unit = { _, _ -> },
    val onUpdateSmartPlaylist: (SmartPlaylist) -> Unit = {},
    val onRenameSmartPlaylist: (SmartPlaylist, String) -> Unit = { _, _ -> },
    val onDuplicateSmartPlaylist: (SmartPlaylist) -> Unit = {},
    val onSetSmartPlaylistEnabled: (SmartPlaylist, Boolean) -> Unit = { _, _ -> },
    val onDeleteSmartPlaylist: (SmartPlaylist) -> Unit = {},
)

val LocalPlaylistActions = compositionLocalOf { PlaylistActions() }

data class LikeActions(
    val likedTrackIds: Set<String> = emptySet(),
    val likesEnabled: Boolean = false,
    val onToggleLiked: (Track) -> Unit = {},
    val likedRadioStreamUrls: Set<String> = emptySet(),
) {
    fun isLiked(track: Track): Boolean {
        if (track.id.startsWith("radio:")) {
            val streamUrl = track.streamUrl.trim().removeSuffix("/")
            return likedRadioStreamUrls.any { it.trim().removeSuffix("/") == streamUrl }
        }
        return equivalentTrackIds(track.id).any { it in likedTrackIds }
    }
}

val LocalLikeActions = compositionLocalOf { LikeActions() }

data class FavoriteActions(
    val catalog: CatalogSnapshot = CatalogSnapshot(),
    val onToggleArtist: (Artist) -> Unit = {},
    val onToggleAlbum: (Album) -> Unit = {},
    val onTogglePlaylist: (Playlist) -> Unit = {},
) {
    fun isFavorite(artist: Artist): Boolean =
        catalog.artists.firstOrNull { it.id == artist.id }?.favorite ?: artist.favorite

    fun isFavorite(album: Album): Boolean =
        catalog.albums.firstOrNull { it.id == album.id }?.favorite ?: album.favorite

    fun isFavorite(playlist: Playlist): Boolean =
        catalog.playlists.firstOrNull { it.id == playlist.id }?.favorite ?: playlist.favorite
}

val LocalFavoriteActions = compositionLocalOf { FavoriteActions() }

data class RatingActions(
    val ratingsEnabled: Boolean = false,
    val catalog: CatalogSnapshot = CatalogSnapshot(),
    val trackRatingsById: Map<String, Float?> = emptyMap(),
    val onRateTrack: (Track, Float?) -> Unit = { _, _ -> },
    val onRateArtist: (Artist, Float?) -> Unit = { _, _ -> },
    val onRateAlbum: (Album, Float?) -> Unit = { _, _ -> },
    val onRatePlaylist: (Playlist, Float?) -> Unit = { _, _ -> },
) {
    fun ratingFor(track: Track): Float? =
        equivalentTrackIds(track.id)
            .firstNotNullOfOrNull { trackRatingsById[it] }
            ?: track.rating

    fun ratingFor(artist: Artist): Float? =
        catalog.artists.firstOrNull { it.id == artist.id }?.rating ?: artist.rating

    fun ratingFor(album: Album): Float? =
        catalog.albums.firstOrNull { it.id == album.id }?.rating ?: album.rating

    fun ratingFor(playlist: Playlist): Float? =
        catalog.playlists.firstOrNull { it.id == playlist.id }?.rating ?: playlist.rating
}

val LocalRatingActions = compositionLocalOf { RatingActions() }

data class TrackNavigationActions(
    val onOpenArtistForTrack: (Track) -> Boolean = { false },
    val onOpenAlbumForTrack: (Track) -> Boolean = { false },
    val onOpenSongDetail: (Track) -> Unit = {},
)

val LocalTrackNavigationActions = compositionLocalOf { TrackNavigationActions() }

data class MetadataEditorActions(
    val onRequestEdit: (Track) -> Unit = {},
)

val LocalMetadataEditorActions = compositionLocalOf { MetadataEditorActions() }

fun buildTrackRatingIndex(catalog: CatalogSnapshot): Map<String, Float?> {
    val ratings = hashMapOf<String, Float?>()
    catalog.tracksByParent.values.forEach { tracks ->
        tracks.forEach { track ->
            equivalentTrackIds(track.id).forEach { id -> ratings[id] = track.rating }
        }
    }
    return ratings
}

private fun equivalentTrackIds(id: String): Set<String> =
    com.phoebe.app.domain.equivalentProviderTrackIds(id)
