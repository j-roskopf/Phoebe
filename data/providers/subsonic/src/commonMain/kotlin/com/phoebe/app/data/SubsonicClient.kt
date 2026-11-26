package com.phoebe.app.data

import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.LIKED_SONGS_PLAYLIST_BARE_ID
import com.phoebe.app.domain.LIKED_SONGS_PLAYLIST_TITLE
import com.phoebe.app.domain.isLikedSongsPlaylist
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.RadioStation
import com.phoebe.app.domain.RadioStationSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

val NavidromeAllMusicLibrary = MusicLibrary("all", "All Music")

const val SubsonicQuickSyncAlbumTrackBatch = 40

const val SubsonicPlayHistoryAlbumListSize = 50

data class SubsonicSongPlayStat(
    val track: Track,
    val playCount: Long,
    val lastPlayedMs: Long?,
)

@SingleIn(AppScope::class)
@Inject
class SubsonicClient(
    private val httpClient: HttpClient,
) {
    suspend fun signIn(serverUrl: String, username: String, password: String): PlexSession {
        val base = serverUrl.trimEnd('/')
        request<SubsonicRoot>(base, username, password, "ping")
        return PlexSession(
            token = password,
            userName = username,
            userId = username,
            selectedServer = PlexServer(
                id = "navidrome:${base.hashCode().toUInt().toString(16)}",
                name = base.removePrefix("https://").removePrefix("http://"),
                uri = base,
                owned = true,
            ),
            selectedLibrary = NavidromeAllMusicLibrary,
            providerType = com.phoebe.app.domain.MediaProviderType.Navidrome,
        )
    }

    suspend fun libraries(server: PlexServer, username: String, password: String): List<MusicLibrary> {
        runCatching { request<SubsonicMusicFoldersRoot>(server.uri, username, password, "getMusicFolders") }
        return listOf(NavidromeAllMusicLibrary)
    }

    suspend fun buildCatalog(
        server: PlexServer,
        library: MusicLibrary,
        username: String,
        password: String,
        onProgress: suspend (message: String, loadedAlbums: Int, totalAlbums: Int) -> Unit = { _, _, _ -> },
    ): CatalogSnapshot {
        onProgress("Loading Subsonic artists…", 0, 0)
        val artistsFromEndpoint = artists(server, library, username, password)
        onProgress("Loading Subsonic albums…", 0, 0)
        val albums = albums(server, library, username, password)
        onProgress("Loading starred items…", 0, albums.size)
        val starred = starred(server, username, password)
        val albumsById = albums.associateBy { it.id }
        onProgress("Indexing Subsonic songs…", 0, albums.size)
        val tracksByAlbum = albumTracksParallel(
            server = server,
            albums = albums,
            username = username,
            password = password,
            albumsById = albumsById,
        ) { loaded, total ->
            onProgress("Indexing Subsonic songs…", loaded, total)
        }
        val albumsWithArtwork = enrichAlbumsFromTracks(albums, tracksByAlbum)
            .map { album -> if (album.id.removePrefix("navidrome:") in starred.albumIds) album.copy(favorite = true) else album }
        val artists = mergeArtistsFromAlbums(
            artistsFromEndpoint.map { artist ->
                if (artist.id.removePrefix("navidrome:") in starred.artistIds) artist.copy(favorite = true) else artist
            },
            albumsWithArtwork,
        )
        val likedTracks = starred.tracks
        val playlists = playlists(server, username, password).withLikedSongs(likedTracks)
        return CatalogSnapshot(
            artists = artists,
            albums = albumsWithArtwork,
            playlists = playlists,
            tracksByParent = tracksByAlbum.withLikedSongs(likedTracks),
        )
    }

    suspend fun quickCatalog(server: PlexServer, username: String, password: String): CatalogSnapshot {
        val shell = quickCatalogShell(server, username, password)
        val albumsToIndex = shell.albums.take(SubsonicQuickSyncAlbumTrackBatch)
        if (albumsToIndex.isEmpty()) return shell
        val albumsById = shell.albums.associateBy { it.id }
        val tracksByAlbum = albumTracksParallel(
            server = server,
            albums = albumsToIndex,
            username = username,
            password = password,
            albumsById = albumsById,
        )
        val albumsWithArtwork = enrichAlbumsFromTracks(shell.albums, tracksByAlbum)
        val likedTracks = shell.tracksByParent[LIKED_SONGS_PLAYLIST_BARE_ID].orEmpty()
        return shell.copy(
            albums = albumsWithArtwork,
            tracksByParent = tracksByAlbum.withLikedSongs(likedTracks),
        )
    }

    suspend fun quickCatalogShell(server: PlexServer, username: String, password: String): CatalogSnapshot =
        coroutineScope {
            val artistsDeferred = async { artists(server, NavidromeAllMusicLibrary, username, password) }
            val albumPageDeferred = async { albumPage(server, username, password, pageIndex = 0) }
            val starredDeferred = async { starred(server, username, password) }
            val playlistsDeferred = async { playlists(server, username, password) }

            val artistsFromEndpoint = artistsDeferred.await()
            val albumPage = albumPageDeferred.await()
            val starred = starredDeferred.await()
            val playlists = runCatching { playlistsDeferred.await() }.getOrDefault(emptyList())
            val albumsWithArtwork = albumPage.items
                .map { album -> if (album.id.removePrefix("navidrome:") in starred.albumIds) album.copy(favorite = true) else album }
            val artists = mergeArtistsFromAlbums(
                artistsFromEndpoint.map { artist ->
                    if (artist.id.removePrefix("navidrome:") in starred.artistIds) artist.copy(favorite = true) else artist
                },
                albumsWithArtwork,
            )
            val likedTracks = starred.tracks
            CatalogSnapshot(
                artists = artists,
                albums = albumsWithArtwork,
                playlists = playlists.withLikedSongs(likedTracks),
                tracksByParent = emptyMap<String, List<Track>>().withLikedSongs(likedTracks),
                remotePageInfo = com.phoebe.app.domain.CatalogPageInfo(
                    pageSize = albumPage.pageSize,
                    artistTotal = artists.size,
                    loadedArtistPages = setOf(0),
                    albumTotal = albumPage.total,
                    loadedAlbumPages = if (albumPage.items.isNotEmpty()) setOf(0) else emptySet(),
                ),
            )
        }

    suspend fun albumTracksParallel(
        server: PlexServer,
        albums: List<Album>,
        username: String,
        password: String,
        albumsById: Map<String, Album> = albums.associateBy { it.id },
        parallelism: Int = 8,
        onProgress: suspend (loaded: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<String, List<Track>> {
        if (albums.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, List<Track>>(albums.size)
        var loaded = 0
        coroutineScope {
            albums.chunked(parallelism.coerceAtLeast(1)).forEach { chunk ->
                chunk.map { album ->
                    async {
                        album.id to albumTracks(server, album, username, password).map { track ->
                            val knownAlbum = albumsById[album.id]
                            if (knownAlbum == null) {
                                track.copy(parentAlbumId = album.id)
                            } else {
                                track.copy(
                                    album = knownAlbum.title,
                                    artist = track.artist.takeUnless { it == "Unknown artist" } ?: knownAlbum.artist,
                                    thumbUrl = track.thumbUrl ?: knownAlbum.thumbUrl,
                                    parentAlbumId = album.id,
                                )
                            }
                        }
                    }
                }.awaitAll().forEach { (albumId, tracks) ->
                    result[albumId] = tracks
                }
                loaded += chunk.size
                onProgress(loaded, albums.size)
            }
        }
        return result
    }

    suspend fun getSong(server: PlexServer, username: String, password: String, songId: String): Track? {
        val id = songId.removePrefix("navidrome:")
        val response = request<SubsonicSongRoot>(server.uri, username, password, "getSong") {
            parameter("id", id)
        }
        return response.response.song?.toTrack(server.uri, username, password)
    }

    suspend fun albumListByType(
        server: PlexServer,
        username: String,
        password: String,
        type: String,
        size: Int = SubsonicPlayHistoryAlbumListSize,
    ): List<Album> {
        val response = request<SubsonicAlbumListRoot>(server.uri, username, password, "getAlbumList2") {
            parameter("type", type)
            parameter("size", size.coerceIn(1, SubsonicPageSize))
            parameter("offset", 0)
        }
        return response.response.albumList2?.album.orEmpty().map { it.toAlbum(server.uri, username, password) }
    }

    suspend fun playHistoryStats(
        server: PlexServer,
        username: String,
        password: String,
        maxAlbumsPerList: Int = SubsonicPlayHistoryAlbumListSize,
    ): List<SubsonicSongPlayStat> {
        val albums = (
            albumListByType(server, username, password, "frequent", maxAlbumsPerList) +
                albumListByType(server, username, password, "recent", maxAlbumsPerList)
            ).distinctBy { it.id }
        if (albums.isEmpty()) return emptyList()
        val statsBySongId = LinkedHashMap<String, SubsonicSongPlayStat>()
        for (album in albums) {
            val id = album.id.removePrefix("navidrome:")
            val response = request<SubsonicAlbumRoot>(server.uri, username, password, "getAlbum") {
                parameter("id", id)
            }
            val songs = response.response.album?.song.orEmpty()
            for (song in songs) {
                val playCount = song.playCount?.toLong()?.coerceAtLeast(0L) ?: 0L
                val lastPlayedMs = song.played?.parseSubsonicTimestampMillis()
                if (playCount <= 0L && lastPlayedMs == null) continue
                val track = song.toTrack(server.uri, username, password).copy(
                    album = song.album ?: album.title,
                    artist = song.artist ?: album.artist,
                    thumbUrl = song.coverArt?.let { coverArtUrl(server.uri, username, password, it) } ?: album.thumbUrl,
                    parentAlbumId = album.id,
                )
                val existing = statsBySongId[song.id]
                statsBySongId[song.id] = SubsonicSongPlayStat(
                    track = track,
                    playCount = maxOf(existing?.playCount ?: 0L, playCount),
                    lastPlayedMs = listOfNotNull(existing?.lastPlayedMs, lastPlayedMs).maxOrNull(),
                )
            }
        }
        return statsBySongId.values.toList()
    }

    suspend fun artists(server: PlexServer, library: MusicLibrary, username: String, password: String): List<Artist> {
        val response = request<SubsonicArtistsRoot>(server.uri, username, password, "getArtists")
        return response.response.artists?.index.orEmpty()
            .flatMap { it.artist }
            .map {
                Artist(
                    id = it.id,
                    title = it.name,
                    albumCount = it.albumCount ?: 0,
                    thumbUrl = it.coverArt?.let { cover -> coverArtUrl(server.uri, username, password, cover) },
                    favorite = it.starred != null,
                )
            }
    }

    suspend fun albums(server: PlexServer, library: MusicLibrary, username: String, password: String): List<Album> {
        val albums = mutableListOf<Album>()
        var offset = 0
        while (true) {
            val response = request<SubsonicAlbumListRoot>(server.uri, username, password, "getAlbumList2") {
                parameter("type", "alphabeticalByArtist")
                parameter("size", SubsonicPageSize)
                parameter("offset", offset)
            }
            val page = response.response.albumList2?.album.orEmpty()
            if (page.isEmpty()) break
            albums += page.map { it.toAlbum(server.uri, username, password) }
            if (page.size < SubsonicPageSize) break
            offset += page.size
        }
        return albums
    }

    suspend fun albumPage(server: PlexServer, username: String, password: String, pageIndex: Int): ProviderItemPage<Album> {
        val response = request<SubsonicAlbumListRoot>(server.uri, username, password, "getAlbumList2") {
            parameter("type", "alphabeticalByArtist")
            parameter("size", SubsonicPageSize)
            parameter("offset", pageIndex * SubsonicPageSize)
        }
        val albums = response.response.albumList2?.album.orEmpty().map { it.toAlbum(server.uri, username, password) }
        val total = response.response.albumList2?.totalCount ?: response.response.albumList2?.total ?: when {
            albums.size < SubsonicPageSize -> pageIndex * SubsonicPageSize + albums.size
            else -> (pageIndex + 2) * SubsonicPageSize
        }
        return ProviderItemPage(
            items = albums,
            total = total,
            pageIndex = pageIndex,
            pageSize = SubsonicPageSize,
        )
    }

    suspend fun albumTracks(server: PlexServer, album: Album, username: String, password: String): List<Track> {
        val id = album.id.removePrefix("navidrome:")
        val response = request<SubsonicAlbumRoot>(server.uri, username, password, "getAlbum") {
            parameter("id", id)
        }
        return response.response.album?.song.orEmpty().map { it.toTrack(server.uri, username, password) }
    }

    suspend fun likedSongTracks(server: PlexServer, username: String, password: String): List<Track> =
        starred(server, username, password).tracks

    suspend fun playlistTracks(server: PlexServer, playlist: Playlist, username: String, password: String): List<Track> {
        val id = playlist.id.removePrefix("navidrome:")
        if (id == LIKED_SONGS_PLAYLIST_BARE_ID) {
            return likedSongTracks(server, username, password)
        }
        val response = request<SubsonicPlaylistRoot>(server.uri, username, password, "getPlaylist") {
            parameter("id", id)
        }
        return response.response.playlist?.entry.orEmpty().map { it.toTrack(server.uri, username, password) }
    }

    suspend fun playlists(server: PlexServer, username: String, password: String): List<Playlist> {
        val response = request<SubsonicPlaylistsRoot>(server.uri, username, password, "getPlaylists")
        return response.response.playlists?.playlist.orEmpty().map {
            Playlist(
                id = it.id,
                title = it.name,
                trackCount = it.songCount ?: 0,
                thumbUrl = it.coverArt?.let { cover -> coverArtUrl(server.uri, username, password, cover) },
                favorite = false,
            )
        }
    }

    private suspend fun starred(server: PlexServer, username: String, password: String): SubsonicStarredItems =
        runCatching {
            val response = request<SubsonicStarred2Root>(server.uri, username, password, "getStarred2")
            val starred = response.response.starred2 ?: return@runCatching SubsonicStarredItems()
            SubsonicStarredItems(
                artistIds = starred.artist.map { it.id }.toSet(),
                albumIds = starred.album.map { it.id }.toSet(),
                tracks = starred.song.map { it.toTrack(server.uri, username, password) },
            )
        }.getOrDefault(SubsonicStarredItems())

    suspend fun createPlaylist(server: PlexServer, username: String, password: String, title: String, itemIds: List<String>): Playlist {
        val response = request<SubsonicPlaylistRoot>(server.uri, username, password, "createPlaylist") {
            parameter("name", title)
            itemIds.forEach { parameter("songId", it.removePrefix("navidrome:")) }
        }
        val playlist = response.response.playlist ?: return Playlist(id = title, title = title, trackCount = itemIds.size)
        return Playlist(id = playlist.id, title = playlist.name, trackCount = playlist.songCount ?: itemIds.size)
    }

    suspend fun addTracksToPlaylist(server: PlexServer, username: String, password: String, playlistId: String, itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        request<SubsonicRoot>(server.uri, username, password, "updatePlaylist") {
            parameter("playlistId", playlistId.removePrefix("navidrome:"))
            itemIds.forEach { parameter("songIdToAdd", it.removePrefix("navidrome:")) }
        }
    }

    suspend fun replacePlaylistTracks(server: PlexServer, username: String, password: String, playlistId: String, itemIds: List<String>) {
        request<SubsonicRoot>(server.uri, username, password, "updatePlaylist") {
            parameter("playlistId", playlistId.removePrefix("navidrome:"))
            itemIds.forEach { parameter("songId", it.removePrefix("navidrome:")) }
        }
    }

    suspend fun setFavorite(server: PlexServer, username: String, password: String, itemId: String, favorite: Boolean, kind: ProviderItemKind = ProviderItemKind.Unknown) {
        request<SubsonicRoot>(server.uri, username, password, if (favorite) "star" else "unstar") {
            when (kind) {
                ProviderItemKind.Artist -> parameter("artistId", itemId.removePrefix("navidrome:"))
                ProviderItemKind.Album -> parameter("albumId", itemId.removePrefix("navidrome:"))
                else -> parameter("id", itemId.removePrefix("navidrome:"))
            }
        }
    }

    suspend fun rateItem(server: PlexServer, username: String, password: String, itemId: String, rating: Float?) {
        request<SubsonicRoot>(server.uri, username, password, "setRating") {
            parameter("id", itemId.removePrefix("navidrome:"))
            parameter("rating", rating?.toInt()?.coerceIn(0, 5) ?: 0)
        }
    }

    suspend fun getInternetRadioStations(server: PlexServer, username: String, password: String): List<RadioStation> {
        val response = request<SubsonicInternetRadioStationsRoot>(server.uri, username, password, "getInternetRadioStations")
        return response.response.internetRadioStations?.internetRadioStation.orEmpty().map {
            RadioStation(
                id = "subsonic:${server.id}:${it.id}",
                name = it.name,
                streamUrl = it.streamUrl,
                homepageUrl = it.homePageUrl,
                faviconUrl = it.coverArt?.let { cover -> coverArtUrl(server.uri, username, password, cover) },
                source = RadioStationSource.Recommended,
            )
        }
    }
    
    suspend fun createInternetRadioStation(
        server: PlexServer,
        username: String,
        password: String,
        name: String,
        streamUrl: String,
        homepageUrl: String? = null,
    ) {
        request<SubsonicRoot>(server.uri, username, password, "createInternetRadioStation") {
            parameter("name", name)
            parameter("streamUrl", streamUrl)
            homepageUrl?.let { parameter("homepageUrl", it) }
        }
    }

    suspend fun updateInternetRadioStation(
        server: PlexServer,
        username: String,
        password: String,
        id: String,
        name: String,
        streamUrl: String,
        homepageUrl: String? = null,
    ) {
        request<SubsonicRoot>(server.uri, username, password, "updateInternetRadioStation") {
            parameter("id", id)
            parameter("name", name)
            parameter("streamUrl", streamUrl)
            homepageUrl?.let { parameter("homepageUrl", it) }
        }
    }

    suspend fun deleteInternetRadioStation(
        server: PlexServer,
        username: String,
        password: String,
        id: String,
    ) {
        request<SubsonicRoot>(server.uri, username, password, "deleteInternetRadioStation") {
            parameter("id", id)
        }
    }

    suspend fun scrobble(server: PlexServer, username: String, password: String, itemId: String, submission: Boolean, timeMs: Long? = null) {
        request<SubsonicRoot>(server.uri, username, password, "scrobble") {
            parameter("id", itemId.removePrefix("navidrome:"))
            parameter("submission", submission)
            timeMs?.let { parameter("time", it.coerceAtLeast(0L)) }
        }
    }

    suspend fun similarSongs(server: PlexServer, username: String, password: String, itemId: String, count: Int = 50): List<Track> {
        val response = request<SubsonicSimilarSongsRoot>(server.uri, username, password, "getSimilarSongs2") {
            parameter("id", itemId.removePrefix("navidrome:"))
            parameter("count", count)
        }
        return response.response.similarSongs2?.song.orEmpty().map { it.toTrack(server.uri, username, password) }
    }

    /**
     * `getSimilarSongs` (non-ID3 variant) accepts an artist, album, or song ID, which makes it the right
     * call for seeding from whatever item is in the queue. Like [similarSongs] it depends on the server's
     * external metadata agents (Last.fm on Navidrome) and returns an empty list when none are configured.
     */
    suspend fun similarSongsForItem(server: PlexServer, username: String, password: String, itemId: String, count: Int = 50): List<Track> {
        val response = request<SubsonicSimilarSongsRoot>(server.uri, username, password, "getSimilarSongs") {
            parameter("id", itemId.removePrefix("navidrome:"))
            parameter("count", count)
        }
        return response.response.similarSongs?.song.orEmpty().map { it.toTrack(server.uri, username, password) }
    }

    suspend fun randomSongs(server: PlexServer, username: String, password: String, genre: String? = null, count: Int = 50): List<Track> {
        val response = request<SubsonicRandomSongsRoot>(server.uri, username, password, "getRandomSongs") {
            parameter("size", count.coerceIn(1, SubsonicPageSize))
            genre?.takeIf { it.isNotBlank() }?.let { parameter("genre", it) }
        }
        return response.response.randomSongs?.song.orEmpty().map { it.toTrack(server.uri, username, password) }
    }

    /** Resolves a server artist ID from a display name via `search3`; exact (case-insensitive) matches only. */
    suspend fun findArtistIdByName(server: PlexServer, username: String, password: String, name: String): String? {
        val query = name.trim().takeIf { it.isNotBlank() } ?: return null
        val response = request<SubsonicSearchResult3Root>(server.uri, username, password, "search3") {
            parameter("query", query)
            parameter("artistCount", 10)
            parameter("albumCount", 0)
            parameter("songCount", 0)
        }
        return response.response.searchResult3?.artist.orEmpty()
            .firstOrNull { it.name.trim().equals(query, ignoreCase = true) }
            ?.id
    }

    suspend fun artistTracks(
        server: PlexServer,
        username: String,
        password: String,
        artistId: String,
        maxAlbums: Int = ArtistRadioMaxSeedAlbums,
    ): List<Track> {
        val response = request<SubsonicArtistRoot>(server.uri, username, password, "getArtist") {
            parameter("id", artistId.removePrefix("navidrome:"))
        }
        val albums = response.response.artist?.album.orEmpty()
            .map { it.toAlbum(server.uri, username, password) }
            .shuffled()
            .take(maxAlbums.coerceAtLeast(1))
        return albumTracksParallel(server, albums, username, password).values.flatten()
    }

    /**
     * Builds an artist radio queue. Prefers the server's similar-songs list (Last.fm-backed on Navidrome);
     * when that comes back thin — no Last.fm integration, or few similar artists in the library — it falls
     * back to a shuffle of the artist's own tracks interleaved with random songs from the artist's genre.
     *
     * [artistId] may be null when the catalog only knows the artist by name; it is then resolved with
     * `search3`. Returned tracks carry bare (unprefixed) server IDs.
     */
    suspend fun artistRadio(
        server: PlexServer,
        username: String,
        password: String,
        artistId: String?,
        artistName: String,
        genreHint: String? = null,
        count: Int = ArtistRadioTrackCount,
    ): List<Track> {
        val resolvedId = artistId?.removePrefix("navidrome:")?.takeIf { it.isNotBlank() }
            ?: runCatching { findArtistIdByName(server, username, password, artistName) }.getOrNull()
        val similar = resolvedId
            ?.let { id -> runCatching { similarSongs(server, username, password, id, count) }.getOrNull() }
            .orEmpty()
            .distinctBy { it.id }
        if (similar.size >= ArtistRadioMinSimilarTracks) return similar.take(count)

        val own = resolvedId
            ?.let { id -> runCatching { artistTracks(server, username, password, id) }.getOrNull() }
            .orEmpty()
            .distinctBy { it.id }
            .shuffled()
        val genre = genreHint.primarySubsonicGenre()
            ?: own.mapNotNull { it.genre.primarySubsonicGenre() }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
        val ownIds = own.mapTo(HashSet()) { it.id }
        val genreTracks = genre
            ?.let { runCatching { randomSongs(server, username, password, it, count) }.getOrNull() }
            .orEmpty()
            .filterNot { it.id in ownIds }
        return (similar + interleave(own.take(count / 2), genreTracks))
            .distinctBy { it.id }
            .take(count)
    }

    fun streamUrl(baseUrl: String, username: String, password: String, id: String): String =
        subsonicBinaryUrl(baseUrl, username, password, "stream", id)

    fun downloadUrl(baseUrl: String, username: String, password: String, id: String): String =
        subsonicBinaryUrl(baseUrl, username, password, "download", id)

    fun coverArtUrl(baseUrl: String, username: String, password: String, id: String): String =
        subsonicBinaryUrl(baseUrl, username, password, "getCoverArt", id)

    private suspend inline fun <reified T> request(
        baseUrl: String,
        username: String,
        password: String,
        method: String,
        crossinline block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): T {
        val salt = newSalt()
        val token = md5Hex(password + salt)
        return httpClient.get("${baseUrl.trimEnd('/')}/rest/$method.view") {
            parameter("u", username)
            parameter("t", token)
            parameter("s", salt)
            parameter("v", SubsonicApiVersion)
            parameter("c", SubsonicClientName)
            parameter("f", "json")
            block()
        }.body<T>().also { root ->
            val status = (root as? SubsonicStatusCarrier)?.statusForCheck
            if (status == "failed") error("Subsonic $method failed.")
        }
    }

    private fun newSalt(): String =
        "phoebe${saltCounter++}${kotlin.random.Random.nextInt().toUInt().toString(16)}"

    private companion object {
        const val SubsonicApiVersion = "1.16.1"
        const val SubsonicClientName = "phoebe"
        const val SubsonicPageSize = 500
        const val ArtistRadioTrackCount = 50
        const val ArtistRadioMinSimilarTracks = 10
        const val ArtistRadioMaxSeedAlbums = 8
        var saltCounter = 1
    }
}

private fun <T> interleave(first: List<T>, second: List<T>): List<T> {
    val result = ArrayList<T>(first.size + second.size)
    val max = maxOf(first.size, second.size)
    for (index in 0 until max) {
        first.getOrNull(index)?.let(result::add)
        second.getOrNull(index)?.let(result::add)
    }
    return result
}

/** Phoebe joins OpenSubsonic multi-genre arrays with ", "; `getRandomSongs?genre=` needs a single genre. */
private fun String?.primarySubsonicGenre(): String? =
    this?.substringBefore(',')?.trim()?.takeIf { it.isNotBlank() }

private fun subsonicBinaryUrl(baseUrl: String, username: String, password: String, method: String, id: String): String {
    val salt = "phoebe${kotlin.random.Random.nextInt().toUInt().toString(16)}"
    val token = md5Hex(password + salt)
    val params = listOf(
        "u" to username,
        "t" to token,
        "s" to salt,
        "v" to "1.16.1",
        "c" to "phoebe",
        "id" to id.removePrefix("navidrome:"),
    ).joinToString("&") { (k, v) -> "${k.encodeURLParameter()}=${v.encodeURLParameter()}" }
    return "${baseUrl.trimEnd('/')}/rest/$method.view?$params"
}

private interface SubsonicStatusCarrier {
    val statusForCheck: String?
}

@Serializable
private data class SubsonicRoot(
    @SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse(),
) : SubsonicStatusCarrier {
    override val statusForCheck: String? get() = response.status
}

@Serializable
private data class SubsonicMusicFoldersRoot(@SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse())

@Serializable
private data class SubsonicArtistsRoot(@SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse())

@Serializable
private data class SubsonicAlbumListRoot(@SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse())

@Serializable
private data class SubsonicAlbumRoot(@SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse())

@Serializable
private data class SubsonicSongRoot(@SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse())

@Serializable
private data class SubsonicPlaylistsRoot(@SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse())

@Serializable
private data class SubsonicPlaylistRoot(@SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse())

@Serializable
private data class SubsonicSimilarSongsRoot(@SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse())

@Serializable
private data class SubsonicRandomSongsRoot(@SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse())

@Serializable
private data class SubsonicSearchResult3Root(@SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse())

@Serializable
private data class SubsonicArtistRoot(@SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse())

@Serializable
private data class SubsonicStarred2Root(@SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse())

@Serializable
private data class SubsonicInternetRadioStationsRoot(@SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse())

@Serializable
private data class SubsonicResponse(
    val status: String? = null,
    val musicFolders: SubsonicMusicFolders? = null,
    val artists: SubsonicArtists? = null,
    val albumList2: SubsonicAlbumList? = null,
    val album: SubsonicAlbumDto? = null,
    val song: SubsonicSongDto? = null,
    val playlists: SubsonicPlaylists? = null,
    val playlist: SubsonicPlaylistDto? = null,
    val similarSongs: SubsonicSongs? = null,
    val similarSongs2: SubsonicSongs? = null,
    val randomSongs: SubsonicSongs? = null,
    val searchResult3: SubsonicSearchResult3? = null,
    val artist: SubsonicArtistWithAlbumsDto? = null,
    val starred2: SubsonicStarred2? = null,
    val internetRadioStations: SubsonicInternetRadioStations? = null,
)

@Serializable
private data class SubsonicSearchResult3(
    val artist: List<SubsonicArtistDto> = emptyList(),
)

@Serializable
private data class SubsonicArtistWithAlbumsDto(
    val id: String,
    val name: String,
    val album: List<SubsonicAlbumDto> = emptyList(),
)

@Serializable
private data class SubsonicInternetRadioStations(val internetRadioStation: List<SubsonicInternetRadioStationDto> = emptyList())

@Serializable
private data class SubsonicInternetRadioStationDto(
    val id: String,
    val name: String,
    val streamUrl: String,
    val homePageUrl: String? = null,
    val coverArt: String? = null,
)

@Serializable
private data class SubsonicMusicFolders(val musicFolder: List<SubsonicMusicFolder> = emptyList())

@Serializable
private data class SubsonicMusicFolder(val id: String, val name: String)

@Serializable
private data class SubsonicArtists(val index: List<SubsonicArtistIndex> = emptyList())

@Serializable
private data class SubsonicArtistIndex(val artist: List<SubsonicArtistDto> = emptyList())

@Serializable
private data class SubsonicArtistDto(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val coverArt: String? = null,
    val starred: String? = null,
)

@Serializable
private data class SubsonicAlbumList(
    val album: List<SubsonicAlbumDto> = emptyList(),
    @SerialName("totalCount") val totalCount: Int? = null,
    val total: Int? = null,
)

@Serializable
private data class SubsonicNamedTag(val name: String? = null)

@Serializable
private data class SubsonicAlbumDto(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val created: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val genres: List<SubsonicNamedTag>? = null,
    val starred: String? = null,
    val playCount: Int? = null,
    val played: String? = null,
    val song: List<SubsonicSongDto> = emptyList(),
)

@Serializable
private data class SubsonicSongs(val song: List<SubsonicSongDto> = emptyList())

@Serializable
private data class SubsonicStarred2(
    val artist: List<SubsonicArtistDto> = emptyList(),
    val album: List<SubsonicAlbumDto> = emptyList(),
    val song: List<SubsonicSongDto> = emptyList(),
)

private data class SubsonicStarredItems(
    val artistIds: Set<String> = emptySet(),
    val albumIds: Set<String> = emptySet(),
    val tracks: List<Track> = emptyList(),
)

@Serializable
private data class SubsonicSongDto(
    val id: String,
    val title: String,
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val duration: Int? = null,
    val coverArt: String? = null,
    val created: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val genres: List<SubsonicNamedTag>? = null,
    val bitRate: Int? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val path: String? = null,
    val starred: String? = null,
    val userRating: Int? = null,
    val playCount: Int? = null,
    val played: String? = null,
    /** OpenSubsonic extension (Navidrome and others). */
    val replayGain: SubsonicReplayGainDto? = null,
)

@Serializable
private data class SubsonicReplayGainDto(
    val trackGain: Float? = null,
    val albumGain: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
)

@Serializable
private data class SubsonicPlaylists(val playlist: List<SubsonicPlaylistDto> = emptyList())

@Serializable
private data class SubsonicPlaylistDto(
    val id: String,
    val name: String,
    val songCount: Int? = null,
    val coverArt: String? = null,
    val entry: List<SubsonicSongDto> = emptyList(),
)

private fun mergeArtistsFromAlbums(artists: List<Artist>, albums: List<Album>): List<Artist> {
    if (albums.isEmpty()) return artists
    val existing = artists.associateBy { it.title.normalizedArtistKey() }.toMutableMap()
    albums
        .groupBy { it.artist.normalizedArtistKey() }
        .forEach { (key, artistAlbums) ->
            if (key.isBlank() || key == "unknown artist") return@forEach
            val current = existing[key]
            val title = current?.title ?: artistAlbums.first().artist
            val genre = current?.genre ?: dominantCollectionTagLabel(artistAlbums.mapNotNull { it.genre })
            // getArtists has no created date, so an artist counts as added when its newest album was.
            val dateAddedMs = current?.dateAddedMs ?: artistAlbums.mapNotNull { it.dateAddedMs }.maxOrNull()
            existing[key] = current?.copy(
                albumCount = maxOf(current.albumCount, artistAlbums.size),
                thumbUrl = current.thumbUrl ?: artistAlbums.firstNotNullOfOrNull { it.thumbUrl },
                favorite = current.favorite || artistAlbums.any { it.favorite },
                genre = genre,
                dateAddedMs = dateAddedMs,
            ) ?: Artist(
                id = "artist:${key.hashCode().toUInt().toString(16)}",
                title = title,
                albumCount = artistAlbums.size,
                thumbUrl = artistAlbums.firstNotNullOfOrNull { it.thumbUrl },
                favorite = artistAlbums.any { it.favorite },
                genre = genre,
                dateAddedMs = dateAddedMs,
            )
        }
    return existing.values.sortedBy { it.title.lowercase() }
}

private fun String.normalizedArtistKey(): String =
    trim().lowercase()

private fun resolvedSubsonicGenre(single: String?, multi: List<SubsonicNamedTag>?): String? {
    val fromMulti = multi?.mapNotNull { it.name?.trim() }?.filter { it.isNotBlank() }.orEmpty()
    return when {
        fromMulti.isNotEmpty() -> fromMulti.joinToString(", ")
        else -> single?.trim()?.takeIf { it.isNotBlank() }
    }
}

private fun enrichAlbumsFromTracks(albums: List<Album>, tracksByAlbum: Map<String, List<Track>>): List<Album> =
    albums.map { album ->
        if (!album.thumbUrl.isNullOrBlank()) {
            album
        } else {
            album.copy(thumbUrl = tracksByAlbum[album.id].orEmpty().firstNotNullOfOrNull { it.thumbUrl })
        }
    }

private fun List<Playlist>.withLikedSongs(tracks: List<Track>): List<Playlist> {
    val liked = Playlist(
        id = LIKED_SONGS_PLAYLIST_BARE_ID,
        title = LIKED_SONGS_PLAYLIST_TITLE,
        trackCount = tracks.size,
        thumbUrl = tracks.firstNotNullOfOrNull { it.thumbUrl },
    )
    return listOf(liked) + filterNot { it.isLikedSongsPlaylist() }
}

private fun Map<String, List<Track>>.withLikedSongs(tracks: List<Track>): Map<String, List<Track>> {
    val next = this - LIKED_SONGS_PLAYLIST_BARE_ID
    return if (tracks.isEmpty()) next else next + (LIKED_SONGS_PLAYLIST_BARE_ID to tracks)
}

private fun SubsonicAlbumDto.toAlbum(baseUrl: String, username: String, password: String): Album =
    Album(
        id = id,
        title = name,
        artist = artist ?: "Unknown artist",
        year = year,
        genre = resolvedSubsonicGenre(genre, genres),
        dateAddedMs = created?.parseSubsonicTimestampMillis(),
        thumbUrl = coverArt?.let { subsonicBinaryUrl(baseUrl, username, password, "getCoverArt", it) },
        favorite = starred != null,
    )

private fun SubsonicSongDto.toTrack(baseUrl: String, username: String, password: String): Track =
    Track(
        id = id,
        title = title,
        artist = artist ?: "Unknown artist",
        album = album ?: "Unknown album",
        durationMs = (duration ?: 0) * 1000L,
        streamUrl = subsonicBinaryUrl(baseUrl, username, password, "stream", id),
        downloadUrl = subsonicBinaryUrl(baseUrl, username, password, "download", id),
        thumbUrl = coverArt?.let { subsonicBinaryUrl(baseUrl, username, password, "getCoverArt", it) },
        year = year,
        genre = resolvedSubsonicGenre(genre, genres),
        filepath = path,
        audioCodec = suffix?.uppercase() ?: contentType?.substringAfter('/')?.uppercase(),
        bitrateKbps = bitRate,
        dateAddedMs = created?.parseSubsonicTimestampMillis(),
        rating = userRating?.toFloat()?.coerceIn(0f, 5f),
        parentAlbumId = albumId,
        replayGainTrackGainDb = replayGain?.trackGain,
        replayGainTrackPeak = replayGain?.trackPeak?.takeIf { it > 0f },
        replayGainAlbumGainDb = replayGain?.albumGain,
        replayGainAlbumPeak = replayGain?.albumPeak?.takeIf { it > 0f },
    )

private fun String.parseSubsonicTimestampMillis(): Long? {
    val trimmed = trim()
    if (trimmed.length < 19) return null
    val year = trimmed.substringOrNull(0, 4)?.toIntOrNull() ?: return null
    val month = trimmed.substringOrNull(5, 7)?.toIntOrNull() ?: return null
    val day = trimmed.substringOrNull(8, 10)?.toIntOrNull() ?: return null
    val hour = trimmed.substringOrNull(11, 13)?.toIntOrNull() ?: return null
    val minute = trimmed.substringOrNull(14, 16)?.toIntOrNull() ?: return null
    val second = trimmed.substringOrNull(17, 19)?.toIntOrNull() ?: return null
    val offsetMinutes = when {
        trimmed.endsWith("Z") -> 0
        trimmed.length >= 25 && (trimmed[trimmed.length - 6] == '+' || trimmed[trimmed.length - 6] == '-') -> {
            val sign = if (trimmed[trimmed.length - 6] == '-') -1 else 1
            val offsetHour = trimmed.substringOrNull(trimmed.length - 5, trimmed.length - 3)?.toIntOrNull() ?: 0
            val offsetMinute = trimmed.substringOrNull(trimmed.length - 2, trimmed.length)?.toIntOrNull() ?: 0
            sign * (offsetHour * 60 + offsetMinute)
        }
        else -> 0
    }
    val epochDay = daysFromCivil(year, month, day)
    return (((epochDay * 24 + hour) * 60 + minute - offsetMinutes) * 60 + second) * 1000
}

private fun String.substringOrNull(startIndex: Int, endIndex: Int): String? =
    if (startIndex >= 0 && endIndex <= length && startIndex <= endIndex) substring(startIndex, endIndex) else null

private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    var y = year
    val m = month
    y -= if (m <= 2) 1 else 0
    val era = if (y >= 0) y else y - 399
    val eraDiv = era / 400
    val yoe = y - eraDiv * 400
    val mp = m + if (m > 2) -3 else 9
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return eraDiv.toLong() * 146097L + doe - 719468L
}
