package com.phoebe.app.sources

import com.phoebe.app.data.PlexClient
import com.phoebe.app.data.MusicBrainzReleaseGroupSearchResponse
import com.phoebe.app.data.CatalogSyncTrace
import com.phoebe.app.data.enrichArtistAlbumCountsOnly
import com.phoebe.app.data.enrichArtistArtwork
import com.phoebe.app.data.metadataFetchProgress
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.catalogTrackIndexParallelism
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/** Plex metadata list endpoints can be slow on large libraries; fail open after this. */
private const val PlexMetadataFetchTimeoutMs = 120_000L
private const val PlexAlbumTrackFetchTimeoutMs = 30_000L

/**
 * Builds a Plex-only catalog snapshot (IDs are raw Plex keys; wrap with [CatalogMerge.withPrefix] before merging).
 */
class PlexCatalogBuilder(
    private val plexClient: PlexClient,
    private val httpClient: HttpClient,
) {
    suspend fun buildCatalog(server: PlexServer, library: MusicLibrary, token: String): CatalogSnapshot = coroutineScope {
        val metadata = buildMetadataCatalog(server, library, token)

        val tracksByParent = prefetchAlbumTracks(server, metadata.albums, token)

        yield()
        val albumsEnriched = enrichAlbumArtwork(metadata.albums, tracksByParent)
        yield()
        val playlistsEnriched = enrichPlaylistArtwork(metadata.playlists, tracksByParent)
        yield()
        val artistsFinal = enrichArtistAlbumCountsOnly(
            enrichArtistArtwork(metadata.artists, albumsEnriched).ifEmpty {
                albumsEnriched.groupBy { it.artist }.values.map { list ->
                    val first = list.first()
                    Artist(
                        id = "album-artist-${first.id}",
                        title = first.artist,
                        thumbUrl = first.thumbUrl,
                        albumCount = list.size,
                        genre = first.genre,
                        mood = first.mood,
                        style = first.style,
                        rating = first.rating,
                    )
                }
            },
            albumsEnriched,
        )

        CatalogSnapshot(
            artists = artistsFinal,
            albums = albumsEnriched,
            playlists = playlistsEnriched,
            tracksByParent = tracksByParent,
            collectionValues = metadata.collectionValues,
            collectionTags = metadata.collectionTags,
            downloads = emptyList(),
        )
    }

    suspend fun buildMetadataCatalog(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        onProgress: ((message: String, detail: String?) -> Unit)? = null,
    ): CatalogSnapshot = buildMetadataCatalog(
        server = server,
        library = library,
        token = token,
        onProgress = onProgress,
        trace = null,
    )

    internal suspend fun buildMetadataCatalog(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        onProgress: ((message: String, detail: String?) -> Unit)?,
        trace: CatalogSyncTrace?,
    ): CatalogSnapshot = coroutineScope {
        var albumsDone = false
        var albumCount = 0
        var artistsDone = false
        var artistCount = 0
        var playlistsDone = false
        var playlistCount = 0
        fun report() {
            val progress = metadataFetchProgress(
                albumsDone = albumsDone,
                albumCount = albumCount,
                artistsDone = artistsDone,
                artistCount = artistCount,
                playlistsDone = playlistsDone,
                playlistCount = playlistCount,
            )
            onProgress?.invoke(progress.message, progress.detail)
        }

        report()
        val artistsDeferred = async {
            trace?.network("plex.artists", detail = { "${it.size} artists" }) {
                plexClient.artists(server, library, token)
            } ?: plexClient.artists(server, library, token)
        }
        val albumsDeferred = async {
            trace?.network("plex.albums", detail = { "${it.size} albums" }) {
                plexClient.albums(server, library, token)
            } ?: plexClient.albums(server, library, token)
        }
        val playlistsDeferred = async {
            trace?.network("plex.playlists", detail = { "${it.size} playlists" }) {
                plexClient.playlists(server, token)
            } ?: plexClient.playlists(server, token)
        }

        val rawAlbums = awaitPlexMetadata("albums", albumsDeferred)
        albumsDone = true
        albumCount = rawAlbums.size
        report()
        yield()
        val artists = awaitPlexMetadata("artists", artistsDeferred)
        artistsDone = true
        artistCount = artists.size
        report()
        yield()
        val playlistsRaw = awaitPlexMetadata("playlists", playlistsDeferred)
        playlistsDone = true
        playlistCount = playlistsRaw.size
        report()
        onProgress?.invoke(
            "Organizing library metadata…",
            "$artistCount artists · $albumCount albums · $playlistCount playlists",
        )
        yield()

        onProgress?.invoke("Organizing library metadata…", "Matching artist artwork…")
        val artistsWithArtwork = trace?.memory("enrichArtistArtwork", detail = { "${it.size} artists" }) {
            enrichArtistArtwork(artists, rawAlbums)
        } ?: enrichArtistArtwork(artists, rawAlbums)
        yield()
        onProgress?.invoke("Organizing library metadata…", "Counting albums per artist…")
        val artistsResolved = trace?.memory("enrichArtistAlbumCountsOnly", detail = { "${it.size} artists" }) {
            enrichArtistAlbumCountsOnly(
                artistsWithArtwork.ifEmpty {
                    rawAlbums.groupBy { it.artist }.values.map { list ->
                        val first = list.first()
                        Artist(
                            id = "album-artist-${first.id}",
                            title = first.artist,
                            thumbUrl = first.thumbUrl,
                            albumCount = list.size,
                            genre = first.genre,
                            mood = first.mood,
                            style = first.style,
                            rating = first.rating,
                        )
                    }
                },
                rawAlbums,
            )
        } ?: enrichArtistAlbumCountsOnly(
            artistsWithArtwork.ifEmpty {
                rawAlbums.groupBy { it.artist }.values.map { list ->
                    val first = list.first()
                    Artist(
                        id = "album-artist-${first.id}",
                        title = first.artist,
                        thumbUrl = first.thumbUrl,
                        albumCount = list.size,
                        genre = first.genre,
                        mood = first.mood,
                        style = first.style,
                        rating = first.rating,
                    )
                }
            },
            rawAlbums,
        )

        CatalogSnapshot(
            artists = artistsResolved,
            albums = rawAlbums,
            playlists = playlistsRaw,
            tracksByParent = emptyMap(),
            downloads = emptyList(),
        )
    }

    private suspend fun <T> awaitPlexMetadata(
        label: String,
        deferred: kotlinx.coroutines.Deferred<List<T>>,
    ): List<T> {
        val result = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeoutOrNull(PlexMetadataFetchTimeoutMs) {
                runCatching { deferred.await() }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    PhoebeLog.d("PlexCatalogBuilder") { "failed fetching $label: ${error.message}" }
                    emptyList()
                }
            }
        }
        if (result == null) {
            PhoebeLog.d("PlexCatalogBuilder") { "timed out fetching $label after ${PlexMetadataFetchTimeoutMs}ms" }
            return emptyList()
        }
        return result
    }

    suspend fun prefetchAlbumTracks(
        server: PlexServer,
        albums: List<Album>,
        token: String,
        onAlbumTracks: suspend (Album, List<Track>) -> Unit = { _, _ -> },
    ): Map<String, List<Track>> = coroutineScope {
        val mutex = Mutex()
        val tracksAccum = mutableMapOf<String, List<Track>>()

        albums
            .chunked(catalogTrackIndexParallelism().coerceAtLeast(1))
            .forEach { albumChunk ->
                albumChunk.map { album ->
                    async {
                        val tracks = withContext(Dispatchers.Default) {
                            withTimeoutOrNull(PlexAlbumTrackFetchTimeoutMs) {
                                plexClient.children(server, album.id, token)
                            }
                        } ?: run {
                            currentCoroutineContext().ensureActive()
                            PhoebeLog.d("PlexCatalogBuilder") {
                                "timed out fetching tracks for album '${album.title}' after ${PlexAlbumTrackFetchTimeoutMs}ms"
                            }
                            emptyList()
                        }
                        mutex.withLock {
                            tracksAccum[album.id] = tracks
                        }
                        onAlbumTracks(album, tracks)
                    }
                }.awaitAll()
                yield()
            }

        tracksAccum.toMap()
    }

    fun enrichWithTrackArtwork(snapshot: CatalogSnapshot): CatalogSnapshot {
        val tracksByParent = snapshot.tracksByParent
        val albumsEnriched = snapshot.albums.map { album ->
            if (!album.thumbUrl.isNullOrBlank()) {
                album
            } else {
                album.copy(thumbUrl = tracksByParent[album.id].orEmpty().firstNotNullOfOrNull { it.thumbUrl })
            }
        }
        val playlistsEnriched = enrichPlaylistArtwork(snapshot.playlists, tracksByParent)
        val artistsFinal = enrichArtistAlbumCountsOnly(
            enrichArtistArtwork(snapshot.artists, albumsEnriched).ifEmpty {
                albumsEnriched.groupBy { it.artist }.values.map { list ->
                    val first = list.first()
                    Artist(
                        id = "album-artist-${first.id}",
                        title = first.artist,
                        thumbUrl = first.thumbUrl,
                        albumCount = list.size,
                        genre = first.genre,
                        mood = first.mood,
                        style = first.style,
                    )
                }
            },
            albumsEnriched,
        )
        return snapshot.copy(
            artists = artistsFinal,
            albums = albumsEnriched,
            playlists = playlistsEnriched,
        )
    }

    private suspend fun enrichAlbumArtwork(albums: List<Album>, tracksByParent: Map<String, List<Track>>): List<Album> = coroutineScope {
        val budget = LookupBudget(6)
        albums.map { album ->
            async {
                if (!album.thumbUrl.isNullOrBlank()) {
                    album
                } else {
                    val trackThumb = tracksByParent[album.id].orEmpty().firstNotNullOfOrNull { it.thumbUrl }
                    val lookedUp = if (trackThumb == null && budget.tryAcquire()) {
                        withTimeoutOrNull(1_500L) { lookupCoverArt(album) }
                    } else {
                        null
                    }
                    album.copy(thumbUrl = trackThumb ?: lookedUp)
                }
            }
        }.awaitAll()
    }

    private class LookupBudget(private var remaining: Int) {
        private val mtx = Mutex()
        suspend fun tryAcquire(): Boolean = mtx.withLock {
            if (remaining > 0) {
                remaining--
                true
            } else {
                false
            }
        }
    }

    private fun enrichPlaylistArtwork(playlists: List<Playlist>, tracksByParent: Map<String, List<Track>>): List<Playlist> =
        playlists.map { playlist ->
            if (!playlist.thumbUrl.isNullOrBlank()) {
                playlist
            } else {
                playlist.copy(thumbUrl = tracksByParent[playlist.id].orEmpty().firstNotNullOfOrNull { it.thumbUrl })
            }
        }

    private suspend fun lookupCoverArt(album: Album): String? = runCatching {
        val response: MusicBrainzReleaseGroupSearchResponse = httpClient.get("https://musicbrainz.org/ws/2/release-group/") {
            header("User-Agent", "Phoebe/0.1.0 (https://github.com/phoebe)")
            parameter("fmt", "json")
            parameter("limit", "1")
            parameter("query", """releasegroup:"${album.title}" AND artist:"${album.artist}"""")
        }.body()
        response.releaseGroups.firstOrNull()?.id?.let { releaseGroupId ->
            "https://coverartarchive.org/release-group/$releaseGroupId/front-250"
        }
    }.getOrNull()
}
