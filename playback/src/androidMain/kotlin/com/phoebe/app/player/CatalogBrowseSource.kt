package com.phoebe.app.player

import android.app.SearchManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.phoebe.app.data.CatalogRepository
import com.phoebe.app.data.RecommendedRadioStations
import com.phoebe.app.data.RadioRepository
import com.phoebe.app.data.SessionRepository
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.domain.RadioStation
import com.phoebe.app.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface CatalogBrowseSource {
    suspend fun getLibraryRoot(): MediaItem
    suspend fun getChildren(parentId: String): List<MediaItem>
    suspend fun getItem(mediaId: String): MediaItem?
    suspend fun resolveTracks(mediaItems: List<MediaItem>): List<Track>
    suspend fun expandPlayableItem(mediaItem: MediaItem): List<Track>
    fun startIndexForMediaItem(mediaItem: MediaItem, tracks: List<Track>, fallback: Int): Int
    suspend fun searchTracks(query: String, extras: Bundle?): List<Track>
}

internal class CatalogBrowseSourceImpl(
    database: PhoebeDatabase,
    private val catalogRepository: CatalogRepository,
    private val sessionRepository: SessionRepository,
    private val radioRepository: RadioRepository,
    private val radioFallbackArtworkUri: Uri? = null,
) : CatalogBrowseSource {
    private val tree = AndroidAutoBrowseTree(database)
    @Volatile
    private var radioRestored = false

    override suspend fun getLibraryRoot(): MediaItem =
        browseFolderItem(BrowseMediaIds.ROOT, "Phoebe")

    override suspend fun getChildren(parentId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        if (parentId == BrowseMediaIds.ROOT) {
            return@withContext tree.getChildren(parentId) + radioRootFolder()
        }
        if (BrowseMediaIds.isRadioBrowseId(parentId)) {
            return@withContext radioChildren(parentId)
        }

        val cached = tree.getChildren(parentId)
        if (cached.isNotEmpty() || parentId == BrowseMediaIds.SIGN_IN) {
            return@withContext cached
        }

        val session = sessionRepository.session.value
        BrowseMediaIds.parseAlbumId(parentId)?.let { albumId ->
            val album = tree.getAlbum(albumId) ?: return@withContext emptyList()
            val tracks = catalogRepository.tracksForAlbum(session, album)
            return@withContext listOf(
                browsePlayableActionItem(
                    mediaId = BrowseMediaIds.albumPlay(albumId),
                    title = "Play album",
                ),
            ) + tracks.map { browseTrackItem(it, BrowseMediaIds.track(parentId, it.id)) }
        }
        BrowseMediaIds.parsePlaylistId(parentId)?.let { playlistId ->
            val playlist = tree.getPlaylist(playlistId) ?: return@withContext emptyList()
            val tracks = catalogRepository.tracksForPlaylist(session, playlist)
            return@withContext listOf(
                browsePlayableActionItem(
                    mediaId = BrowseMediaIds.playlistPlay(playlistId),
                    title = "Play playlist",
                ),
                browsePlayableActionItem(
                    mediaId = BrowseMediaIds.playlistShuffle(playlistId),
                    title = "Shuffle",
                    subtitle = "Play this playlist in random order",
                ),
            ) + tracks.map { browseTrackItem(it, BrowseMediaIds.track(parentId, it.id)) }
        }
        emptyList()
    }

    override suspend fun getItem(mediaId: String): MediaItem? = withContext(Dispatchers.IO) {
        if (BrowseMediaIds.isRadioBrowseId(mediaId)) {
            return@withContext radioItem(mediaId)
        }
        tree.getItem(mediaId)
    }

    override suspend fun resolveTracks(mediaItems: List<MediaItem>): List<Track> = withContext(Dispatchers.IO) {
        mediaItems.mapNotNull { item ->
            if (item.mediaId.isBlank()) return@mapNotNull null
            BrowseMediaIds.parseRadioStationId(item.mediaId)?.let { stationId ->
                return@mapNotNull resolveRadioStationTrack(stationId)
            }
            if (item.mediaId.startsWith("radio:")) {
                return@mapNotNull resolveRadioStationTrack(item.mediaId.removePrefix("radio:"))
            }
            tree.trackById(item.mediaId)
        }
    }

    override suspend fun expandPlayableItem(mediaItem: MediaItem): List<Track> = withContext(Dispatchers.IO) {
        BrowseMediaIds.parseRadioStationId(mediaItem.mediaId)?.let { stationId ->
            return@withContext listOfNotNull(resolveRadioStationTrack(stationId))
        }
        if (mediaItem.mediaId.startsWith("radio:")) {
            return@withContext listOfNotNull(
                resolveRadioStationTrack(mediaItem.mediaId.removePrefix("radio:")),
            )
        }
        val metadata = mediaItem.mediaMetadata
        if (metadata.isBrowsable == true) {
            val children = getChildren(mediaItem.mediaId)
            return@withContext children
                .filter { it.mediaMetadata.isPlayable == true }
                .flatMap { child -> tracksForPlayableMediaId(child.mediaId) }
                .distinctBy { it.id }
        }
        tracksForPlayableMediaId(mediaItem.mediaId)
    }

    override fun startIndexForMediaItem(mediaItem: MediaItem, tracks: List<Track>, fallback: Int): Int =
        tree.startIndexForMediaId(mediaItem.mediaId, tracks, fallback)

    private suspend fun tracksForPlayableMediaId(mediaId: String): List<Track> {
        BrowseMediaIds.parseRadioStationId(mediaId)?.let { stationId ->
            return listOfNotNull(resolveRadioStationTrack(stationId))
        }
        BrowseMediaIds.parseAlbumPlayId(mediaId)?.let { albumId ->
            val album = tree.getAlbum(albumId) ?: return emptyList()
            return catalogRepository.tracksForAlbum(sessionRepository.session.value, album)
        }

        BrowseMediaIds.parsePlaylistPlayId(mediaId)?.let { playlistId ->
            val playlist = tree.getPlaylist(playlistId) ?: return emptyList()
            return catalogRepository.tracksForPlaylist(sessionRepository.session.value, playlist)
        }

        BrowseMediaIds.parsePlaylistShuffleId(mediaId)?.let { playlistId ->
            val playlist = tree.getPlaylist(playlistId) ?: return emptyList()
            return catalogRepository.tracksForPlaylist(sessionRepository.session.value, playlist).shuffled()
        }

        BrowseMediaIds.parseTrackId(mediaId)?.let { browseTrack ->
            BrowseMediaIds.parseAlbumId(browseTrack.parentMediaId)?.let { albumId ->
                val album = tree.getAlbum(albumId) ?: return@let null
                val tracks = catalogRepository.tracksForAlbum(sessionRepository.session.value, album)
                if (tracks.any { it.id == browseTrack.trackId }) return tracks
            }
            BrowseMediaIds.parsePlaylistId(browseTrack.parentMediaId)?.let { playlistId ->
                val playlist = tree.getPlaylist(playlistId) ?: return@let null
                val tracks = catalogRepository.tracksForPlaylist(sessionRepository.session.value, playlist)
                if (tracks.any { it.id == browseTrack.trackId }) return tracks
            }
        }

        return tree.tracksForPlayableMediaId(mediaId)
    }

    override suspend fun searchTracks(query: String, extras: Bundle?): List<Track> = withContext(Dispatchers.IO) {
        val searchQuery = query.ifBlank { extras?.getString(SearchManager.QUERY).orEmpty() }
        ensureRadioRestored()
        val radioMatch = findStrongRadioStationMatch(
            query = searchQuery,
            savedStations = radioRepository.state.value.manualStations,
            recommendedStations = RecommendedRadioStations,
        )
        if (radioMatch != null) {
            return@withContext listOf(radioRepository.stationTrack(radioMatch))
        }

        val mediaFocus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)
        tree.searchTracks(
            query = searchQuery,
            title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE)
                ?: searchQuery.takeIf { mediaFocus == MediaStore.Audio.Media.ENTRY_CONTENT_TYPE },
            artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST)
                ?: searchQuery.takeIf { mediaFocus == MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE },
            album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM)
                ?: searchQuery.takeIf { mediaFocus == MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE },
            playlist = extras?.getString(MediaStoreSearchExtras.EXTRA_MEDIA_PLAYLIST)
                ?: searchQuery.takeIf { mediaFocus == MediaStoreSearchExtras.PLAYLIST_ENTRY_CONTENT_TYPE },
            genre = extras?.getString(MediaStore.EXTRA_MEDIA_GENRE)
                ?: searchQuery.takeIf { mediaFocus == MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE },
        )
    }

    private fun radioRootFolder(): MediaItem =
        browseFolderItem(
            mediaId = BrowseMediaIds.RADIO,
            title = "Radio",
            artworkUri = radioFallbackArtworkUri,
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS,
        )

    private suspend fun radioChildren(parentId: String): List<MediaItem> {
        ensureRadioRestored()
        return when (parentId) {
            BrowseMediaIds.RADIO -> listOf(
                browseFolderItem(
                    mediaId = BrowseMediaIds.RADIO_RECOMMENDED,
                    title = "Recommended",
                    artworkUri = radioFallbackArtworkUri,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS,
                ),
                browseFolderItem(
                    mediaId = BrowseMediaIds.RADIO_SAVED,
                    title = "Saved",
                    artworkUri = radioFallbackArtworkUri,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS,
                ),
            )
            BrowseMediaIds.RADIO_RECOMMENDED -> recommendedCategoryFolders()
            BrowseMediaIds.RADIO_SAVED -> savedStationItems()
            else -> {
                BrowseMediaIds.parseRadioCategory(parentId)?.let { category ->
                    return RecommendedRadioStations
                        .filter { (it.category ?: "Recommended Streams") == category }
                        .map { it.toBrowseMediaItem() }
                }
                emptyList()
            }
        }
    }

    private fun recommendedCategoryFolders(): List<MediaItem> {
        val categories = RecommendedRadioStations
            .map { it.category ?: "Recommended Streams" }
            .distinct()
        return categories.map { category ->
            browseFolderItem(
                mediaId = BrowseMediaIds.radioCategory(category),
                title = category,
                artworkUri = radioFallbackArtworkUri,
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS,
            )
        }
    }

    private fun savedStationItems(): List<MediaItem> {
        val saved = radioRepository.state.value.manualStations
        if (saved.isEmpty()) {
            return listOf(
                browseNonPlayableHintItem(
                    mediaId = BrowseMediaIds.RADIO_SAVED_HINT,
                    title = "Save stations in Phoebe",
                    subtitle = "Favorited internet radio shows up here",
                    artworkUri = radioFallbackArtworkUri,
                ),
            )
        }
        return saved.map { it.toBrowseMediaItem() }
    }

    private suspend fun radioItem(mediaId: String): MediaItem? {
        when (mediaId) {
            BrowseMediaIds.RADIO -> return radioRootFolder()
            BrowseMediaIds.RADIO_RECOMMENDED -> return browseFolderItem(
                mediaId = mediaId,
                title = "Recommended",
                artworkUri = radioFallbackArtworkUri,
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS,
            )
            BrowseMediaIds.RADIO_SAVED -> return browseFolderItem(
                mediaId = mediaId,
                title = "Saved",
                artworkUri = radioFallbackArtworkUri,
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS,
            )
            BrowseMediaIds.RADIO_SAVED_HINT -> return browseNonPlayableHintItem(
                mediaId = mediaId,
                title = "Save stations in Phoebe",
                subtitle = "Favorited internet radio shows up here",
                artworkUri = radioFallbackArtworkUri,
            )
        }
        BrowseMediaIds.parseRadioCategory(mediaId)?.let { category ->
            return browseFolderItem(
                mediaId = mediaId,
                title = category,
                artworkUri = radioFallbackArtworkUri,
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS,
            )
        }
        BrowseMediaIds.parseRadioStationId(mediaId)?.let { stationId ->
            ensureRadioRestored()
            findStation(stationId)?.let { return it.toBrowseMediaItem() }
        }
        return null
    }

    private suspend fun resolveRadioStationTrack(stationId: String): Track? {
        ensureRadioRestored()
        val station = findStation(stationId) ?: return null
        return runCatching { radioRepository.stationTrack(station) }.getOrNull()
    }

    private fun findStation(stationId: String): RadioStation? =
        radioRepository.state.value.manualStations.find { it.id == stationId }
            ?: RecommendedRadioStations.find { it.id == stationId }

    private suspend fun ensureRadioRestored() {
        if (radioRestored) return
        runCatching { radioRepository.restore() }
        radioRestored = true
    }

    private fun RadioStation.toBrowseMediaItem(): MediaItem {
        val artwork = faviconUrlOrFallback
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { Uri.parse(it).takeIf { uri -> uri.scheme != null } }
        return browseRadioStationItem(
            stationId = id,
            title = name,
            subtitle = displaySubtitle,
            artworkUri = artwork,
            fallbackArtworkUri = radioFallbackArtworkUri,
        )
    }
}
