package com.phoebe.app.player

import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.db.SelectTrackSearchIndex
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.Track

/**
 * Reads the cached SQLDelight catalog for in-car / remote browse UIs.
 */
class CatalogBrowseTree(
    private val database: PhoebeDatabase,
) {
    fun rootChildren(hasCachedCatalog: Boolean): List<BrowseNode> {
        if (!hasCachedCatalog) {
            return listOf(
                browseFolder(
                    mediaId = BrowseMediaIds.SIGN_IN,
                    title = "Open Phoebe and sign in to Plex",
                ),
            )
        }
        return listOf(
            browseFolder(BrowseMediaIds.ARTISTS, "Artists"),
            browseFolder(BrowseMediaIds.ALBUMS, "Albums"),
            browseFolder(BrowseMediaIds.PLAYLISTS, "Playlists"),
        )
    }

    fun getChildren(parentId: String): List<BrowseNode> =
        when (parentId) {
            BrowseMediaIds.ROOT -> rootChildren(hasCachedCatalog())
            BrowseMediaIds.ARTISTS -> artists().map { it.toBrowseNode() }
            BrowseMediaIds.ALBUMS -> albums().map { it.toBrowseNode() }
            BrowseMediaIds.PLAYLISTS -> playlists().map { it.toBrowseNode() }
            BrowseMediaIds.SIGN_IN -> emptyList()
            else -> {
                BrowseMediaIds.parseArtistId(parentId)?.let { artistId ->
                    val artist = artists().find { it.id == artistId } ?: return emptyList()
                    return albumsForArtist(artist.title).map { it.toBrowseNode() }
                }
                BrowseMediaIds.parseAlbumId(parentId)?.let { albumId ->
                    return listOf(playAlbumNode(albumId)) +
                        tracksForParent(albumId).map { it.toBrowseNode(parentId) }
                }
                BrowseMediaIds.parsePlaylistId(parentId)?.let { playlistId ->
                    return listOf(playPlaylistNode(playlistId), shufflePlaylistNode(playlistId)) +
                        tracksForParent(playlistId).map { it.toBrowseNode(parentId) }
                }
                emptyList()
            }
        }

    fun getAlbum(albumId: String): Album? =
        albums().find { it.id == albumId }

    fun getPlaylist(playlistId: String): Playlist? =
        playlists().find { it.id == playlistId }

    fun getItem(mediaId: String): BrowseNode? {
        getChildren(BrowseMediaIds.ROOT).find { it.mediaId == mediaId }?.let { return it }
        artists().find { BrowseMediaIds.artist(it.id) == mediaId }?.toBrowseNode()?.let { return it }
        albums().find { BrowseMediaIds.album(it.id) == mediaId }?.toBrowseNode()?.let { return it }
        playlists().find { BrowseMediaIds.playlist(it.id) == mediaId }?.toBrowseNode()?.let { return it }
        BrowseMediaIds.parseAlbumPlayId(mediaId)?.let { albumId ->
            albums().find { it.id == albumId }?.let { return playAlbumNode(albumId) }
        }
        BrowseMediaIds.parsePlaylistPlayId(mediaId)?.let { playlistId ->
            playlists().find { it.id == playlistId }?.let { return playPlaylistNode(playlistId) }
        }
        BrowseMediaIds.parsePlaylistShuffleId(mediaId)?.let { playlistId ->
            playlists().find { it.id == playlistId }?.let { return shufflePlaylistNode(playlistId) }
        }
        BrowseMediaIds.parseTrackId(mediaId)?.let { browseTrack ->
            trackById(browseTrack.trackId)?.toBrowseNode(browseTrack.parentMediaId)?.let { return it }
        }
        trackById(mediaId)?.toBrowseNode()?.let { return it }
        return null
    }

    fun trackById(trackId: String): Track? {
        val resolvedId = BrowseMediaIds.parseTrackId(trackId)?.trackId ?: trackId
        if (resolvedId.isBlank()) return null
        val row = database.catalogQueries.selectTrackById(resolvedId).executeAsOneOrNull()
            ?: return null
        return row.toTrack()
    }

    fun tracksForPlayableMediaId(mediaId: String): List<Track> {
        BrowseMediaIds.parseAlbumPlayId(mediaId)?.let { albumId ->
            return tracksForParent(albumId)
        }
        BrowseMediaIds.parsePlaylistPlayId(mediaId)?.let { playlistId ->
            return tracksForParent(playlistId)
        }
        BrowseMediaIds.parsePlaylistShuffleId(mediaId)?.let { playlistId ->
            return tracksForParent(playlistId).shuffled()
        }

        BrowseMediaIds.parseTrackId(mediaId)?.let { browseTrack ->
            val parentTracks = tracksForParentMediaId(browseTrack.parentMediaId)
            if (parentTracks.any { it.id == browseTrack.trackId }) return parentTracks
            return trackById(browseTrack.trackId)?.let { listOf(it) }.orEmpty()
        }

        BrowseMediaIds.parseAlbumId(mediaId)?.let { return tracksForParent(it) }
        BrowseMediaIds.parsePlaylistId(mediaId)?.let { return tracksForParent(it) }
        return trackById(mediaId)?.let { listOf(it) }.orEmpty()
    }

    fun startIndexForMediaId(mediaId: String, tracks: List<Track>, fallback: Int): Int {
        val selectedTrackId = BrowseMediaIds.parseTrackId(mediaId)?.trackId ?: mediaId
        return tracks.indexOfFirst { it.id == selectedTrackId }
            .takeIf { it >= 0 }
            ?: fallback.takeIf { it in tracks.indices }
            ?: 0
    }

    fun searchTracks(
        query: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        playlist: String? = null,
        genre: String? = null,
    ): List<Track> {
        val playlistTracks = playlist?.takeIf { it.isNotBlank() }?.let { playlistQuery ->
            playlists()
                .asSequence()
                .filter { it.title.matchesVoiceQuery(playlistQuery) }
                .flatMap { tracksForParent(it.id).asSequence() }
                .toList()
        }.orEmpty()
        if (playlistTracks.isNotEmpty()) return playlistTracks.distinctBy { it.id }

        // Rank over the narrow projection, then hydrate only the ids that matched. Reading
        // every TrackRow column per branch is what pushed voice searches past the Assistant's
        // timeout on large libraries.
        val index by lazy { database.catalogQueries.selectTrackSearchIndex().executeAsList() }

        val albumTracks = album?.takeIf { it.isNotBlank() }?.let { albumQuery ->
            val matchedAlbums = albums()
                .filter { candidate ->
                    candidate.title.matchesVoiceQuery(albumQuery) &&
                        artist?.takeIf { it.isNotBlank() }?.let { candidate.artist.matchesVoiceQuery(it) } != false
                }
            matchedAlbums.flatMap { tracksForParent(it.id) }.ifEmpty {
                hydrate(
                    index.filter { row ->
                        row.album.matchesVoiceQuery(albumQuery) &&
                            artist?.takeIf { it.isNotBlank() }?.let { row.artist.matchesVoiceQuery(it) } != false
                    }.map { it.id },
                )
            }
        }.orEmpty()
        if (albumTracks.isNotEmpty()) return albumTracks.distinctBy { it.id }

        val titleAndArtistTracks = if (!title.isNullOrBlank() && !artist.isNullOrBlank()) {
            hydrate(
                index.filter { it.title.matchesVoiceQuery(title) && it.artist.matchesVoiceQuery(artist) }
                    .map { it.id },
            )
        } else {
            emptyList()
        }
        if (titleAndArtistTracks.isNotEmpty()) return titleAndArtistTracks.distinctBy { it.id }

        val artistTracks = artist?.takeIf { it.isNotBlank() }?.let { artistQuery ->
            hydrate(index.filter { it.artist.matchesVoiceQuery(artistQuery) }.map { it.id })
        }.orEmpty()
        if (artistTracks.isNotEmpty()) return artistTracks.distinctBy { it.id }

        val titleTracks = title?.takeIf { it.isNotBlank() }?.let { titleQuery ->
            hydrate(
                index.rankedByVoiceQuery(titleQuery) {
                    listOf(
                        VoiceSearchField(it.title, FieldWeightTitle),
                        VoiceSearchField(it.artist, FieldWeightArtist),
                        VoiceSearchField(it.album, FieldWeightAlbum),
                    )
                },
            )
        }.orEmpty()
        if (titleTracks.isNotEmpty()) return titleTracks

        val genreTracks = genre?.takeIf { it.isNotBlank() }?.let { genreQuery ->
            hydrate(index.filter { it.genre?.matchesVoiceQuery(genreQuery) == true }.map { it.id })
        }.orEmpty()
        if (genreTracks.isNotEmpty()) return genreTracks.distinctBy { it.id }

        val freeformQuery = query.trim()
        return if (freeformQuery.isBlank()) {
            hydrate(
                index
                    .sortedWith(
                        compareByDescending<SelectTrackSearchIndex> { it.dateAddedMs ?: Long.MIN_VALUE }
                            .thenBy { it.title.lowercase() },
                    )
                    .take(25)
                    .map { it.id },
            )
        } else {
            hydrate(
                index.rankedByVoiceQuery(freeformQuery) {
                    listOf(
                        VoiceSearchField(it.title, FieldWeightTitle),
                        VoiceSearchField(it.artist, FieldWeightArtist),
                        VoiceSearchField(it.album, FieldWeightAlbum),
                        VoiceSearchField(it.genre.orEmpty(), FieldWeightGenre),
                    )
                },
            )
        }
    }

    /** Loads full rows for [ids], preserving the ranked order they were matched in. */
    private fun hydrate(ids: List<String>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val tracksById = database.catalogQueries.selectTracksByIds(ids)
            .executeAsList()
            .associate { it.id to it.toTrack() }
        return ids.mapNotNull { tracksById[it] }
    }

    private fun hasCachedCatalog(): Boolean =
        database.catalogQueries.selectArtists().executeAsList().isNotEmpty() ||
            database.catalogQueries.selectAlbums().executeAsList().isNotEmpty() ||
            database.catalogQueries.selectPlaylists().executeAsList().isNotEmpty()

    private fun artists(): List<Artist> =
        database.catalogQueries.selectArtists().executeAsList().map {
            Artist(
                id = it.id,
                title = it.title,
                thumbUrl = it.thumbUrl,
                albumCount = it.albumCount.toInt(),
                songCount = it.songCount.toInt(),
            )
        }

    private fun albums(): List<Album> =
        database.catalogQueries.selectAlbums().executeAsList().map {
            Album(
                id = it.id,
                title = it.title,
                artist = it.artist,
                year = it.year?.toInt(),
                thumbUrl = it.thumbUrl,
            )
        }

    private fun playlists(): List<Playlist> =
        database.catalogQueries.selectPlaylists().executeAsList().map {
            Playlist(
                id = it.id,
                title = it.title,
                trackCount = it.trackCount.toInt(),
                key = it.plKey,
                thumbUrl = it.thumbUrl,
            )
        }

    private fun albumsForArtist(artistTitle: String): List<Album> =
        albums().filter { it.artist.equals(artistTitle, ignoreCase = true) }

    private fun tracksForParent(parentId: String): List<Track> =
        database.catalogQueries.selectTracksForParent(parentId).executeAsList().map { it.toTrack() }

    private fun com.phoebe.app.db.TrackRow.toTrack(): Track =
        Track(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            streamUrl = streamUrl,
            downloadUrl = downloadUrl,
            thumbUrl = thumbUrl,
            localArtworkUri = localArtworkUri,
            localUri = localUri,
            year = year?.toInt(),
            genre = genre,
            filepath = filepath,
            audioCodec = audioCodec,
            bitrateKbps = bitrateKbps?.toInt(),
            dateAddedMs = dateAddedMs,
            parentAlbumId = parentAlbumId,
        )

    private fun browseFolder(
        mediaId: String,
        title: String,
        thumbUrl: String? = null,
    ): BrowseNode = BrowseNode(
        mediaId = mediaId,
        title = title,
        isBrowsable = true,
        isPlayable = false,
        thumbUrl = thumbUrl,
    )

    private fun Artist.toBrowseNode(): BrowseNode = BrowseNode(
        mediaId = BrowseMediaIds.artist(id),
        title = title,
        isBrowsable = true,
        isPlayable = false,
        thumbUrl = thumbUrl,
    )

    private fun Album.toBrowseNode(): BrowseNode = BrowseNode(
        mediaId = BrowseMediaIds.album(id),
        title = title,
        subtitle = artist,
        isBrowsable = true,
        isPlayable = false,
        thumbUrl = thumbUrl,
    )

    private fun Playlist.toBrowseNode(): BrowseNode = BrowseNode(
        mediaId = BrowseMediaIds.playlist(id),
        title = title,
        isBrowsable = true,
        isPlayable = false,
        thumbUrl = thumbUrl,
    )

    private fun playAlbumNode(albumId: String): BrowseNode = BrowseNode(
        mediaId = BrowseMediaIds.albumPlay(albumId),
        title = "Play album",
        isBrowsable = false,
        isPlayable = true,
    )

    private fun playPlaylistNode(playlistId: String): BrowseNode = BrowseNode(
        mediaId = BrowseMediaIds.playlistPlay(playlistId),
        title = "Play playlist",
        isBrowsable = false,
        isPlayable = true,
    )

    private fun shufflePlaylistNode(playlistId: String): BrowseNode = BrowseNode(
        mediaId = BrowseMediaIds.playlistShuffle(playlistId),
        title = "Shuffle",
        subtitle = "Play this playlist in random order",
        isBrowsable = false,
        isPlayable = true,
    )

    private fun Track.toBrowseNode(parentMediaId: String? = null): BrowseNode = BrowseNode(
        mediaId = parentMediaId?.let { BrowseMediaIds.track(it, id) } ?: id,
        title = title,
        subtitle = listOf(artist, album).filter { it.isNotBlank() }.distinct().joinToString(" • "),
        isBrowsable = false,
        isPlayable = true,
        thumbUrl = thumbUrl,
        track = this,
    )

    private fun tracksForParentMediaId(parentMediaId: String): List<Track> {
        BrowseMediaIds.parseAlbumId(parentMediaId)?.let { return tracksForParent(it) }
        BrowseMediaIds.parsePlaylistId(parentMediaId)?.let { return tracksForParent(it) }
        return emptyList()
    }

    private fun String.matchesVoiceQuery(query: String): Boolean {
        val value = normalizedForVoiceSearch()
        val target = query.normalizedForVoiceSearch()
        return target.isNotBlank() && (value == target || value.contains(target))
    }

    /** Returns matching track ids, best match first. */
    private fun List<SelectTrackSearchIndex>.rankedByVoiceQuery(
        query: String,
        fields: (SelectTrackSearchIndex) -> List<VoiceSearchField>,
    ): List<String> {
        val normalizedQuery = query.normalizedForVoiceSearch()
        val tokens = normalizedQuery.split(' ').filter { it.isNotBlank() }
        if (normalizedQuery.isBlank()) return emptyList()
        return asSequence()
            .mapNotNull { track ->
                val score = fields(track).maxOfOrNull { field ->
                    val normalizedField = field.value.normalizedForVoiceSearch()
                    when {
                        normalizedField == normalizedQuery -> 400 + field.weight
                        normalizedField.startsWith(normalizedQuery) -> 300 + field.weight
                        normalizedField.contains(normalizedQuery) -> 200 + field.weight
                        tokens.all { token -> normalizedField.contains(token) } -> 100 + field.weight
                        else -> 0
                    }
                } ?: 0
                if (score > 0) track to score else null
            }
            .sortedWith(
                compareByDescending<Pair<SelectTrackSearchIndex, Int>> { it.second }
                    .thenBy { it.first.title.lowercase() }
                    .thenBy { it.first.artist.lowercase() },
            )
            .map { it.first.id }
            .distinct()
            .toList()
    }

    private fun String.normalizedForVoiceSearch(): String =
        lowercase()
            .replace(NonAlphanumericRegex, " ")
            .trim()
            .replace(WhitespaceRegex, " ")

    private data class VoiceSearchField(val value: String, val weight: Int)

    private companion object {
        private const val FieldWeightTitle = 40
        private const val FieldWeightArtist = 30
        private const val FieldWeightAlbum = 20
        private const val FieldWeightGenre = 10
        private val NonAlphanumericRegex = Regex("[^a-z0-9]+")
        private val WhitespaceRegex = Regex("\\s+")
    }
}
