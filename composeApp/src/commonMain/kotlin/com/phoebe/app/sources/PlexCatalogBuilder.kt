package com.phoebe.app.sources

import com.phoebe.app.data.PlexClient
import com.phoebe.app.data.MusicBrainzReleaseGroupSearchResponse
import com.phoebe.app.data.enrichArtistAlbumCountsOnly
import com.phoebe.app.data.enrichArtistArtwork
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.catalogTrackPrefetchAlbumCount
import com.phoebe.app.platform.catalogTrackPrefetchParallelism
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

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

    suspend fun buildMetadataCatalog(server: PlexServer, library: MusicLibrary, token: String): CatalogSnapshot = coroutineScope {
        val artistsDeferred = async { plexClient.artists(server, library, token) }
        val albumsDeferred = async { plexClient.albums(server, library, token) }
        val playlistsDeferred = async { plexClient.playlists(server, token) }

        val rawAlbums = albumsDeferred.await()
        yield()
        val artists = artistsDeferred.await()
        yield()
        val playlistsRaw = playlistsDeferred.await()
        yield()

        val artistsWithArtwork = enrichArtistArtwork(artists, rawAlbums)
        val artistsResolved = enrichArtistAlbumCountsOnly(
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

    suspend fun buildMetadataCatalogProgressively(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        firstAlbumPageSize: Int = 96,
        albumPageSize: Int = 256,
        albumPageParallelism: Int = 2,
        onProgress: suspend (message: String, loadedAlbums: Int, totalAlbums: Int?) -> Unit = { _, _, _ -> },
        onPartial: suspend (snapshot: CatalogSnapshot, message: String, loadedAlbums: Int, totalAlbums: Int?) -> Unit = { _, _, _, _ -> },
    ): CatalogSnapshot = coroutineScope {
        onProgress("Loading Plex playlists…", 0, null)
        val playlistsDeferred = async { fetchPlaylistsOrEmpty(server, token, onProgress) }
        val firstPageDeferred = async {
            plexClient.albumsPage(server, library, token, start = 0, size = firstAlbumPageSize)
        }
        val playlists = playlistsDeferred.await()
        if (playlists.isNotEmpty()) {
            onPartial(CatalogSnapshot(playlists = playlists), "Loaded Plex playlists…", 0, null)
        }

        onProgress("Loading first Plex albums…", 0, null)
        val firstPage = firstPageDeferred.await()
        val albumsById = linkedMapOf<String, Album>()
        firstPage.albums.forEach { albumsById[it.id] = it }
        var totalAlbums = firstPage.totalSize
        if (albumsById.isNotEmpty()) {
            onPartial(
                progressiveAlbumSnapshot(albumsById.values.toList(), playlists),
                totalAlbums?.let { "Loaded first ${albumsById.size} of $it Plex albums…" }
                    ?: "Loaded first ${albumsById.size} Plex albums…",
                albumsById.size,
                totalAlbums,
            )
        }

        val artistsDeferred = async { fetchArtistsOrEmpty(server, library, token) }
        var nextOffset = firstPage.nextOffset
        var hasMore = firstPage.hasMore
        val knownTotal = totalAlbums
        if (knownTotal != null && hasMore) {
            val offsets = generateSequence(nextOffset) { it + albumPageSize }
                .takeWhile { it < knownTotal }
                .toList()
            offsets
                .chunked(albumPageParallelism.coerceAtLeast(1))
                .forEach { chunk ->
                    onProgress(
                        "Loading Plex albums ${albumsById.size} of $knownTotal…",
                        albumsById.size,
                        totalAlbums,
                    )
                    val pages = chunk.map { offset ->
                        async { plexClient.albumsPage(server, library, token, start = offset, size = albumPageSize) }
                    }
                    pages.forEach { deferred ->
                        val page = deferred.await()
                        page.albums.forEach { albumsById[it.id] = it }
                        totalAlbums = page.totalSize ?: totalAlbums
                        nextOffset = page.nextOffset
                        hasMore = page.hasMore
                        onPartial(
                            progressiveAlbumSnapshot(albumsById.values.toList(), playlists),
                            totalAlbums?.let { "Loaded ${albumsById.size} of $it Plex albums…" }
                                ?: "Loaded ${albumsById.size} Plex albums…",
                            albumsById.size,
                            totalAlbums,
                        )
                        yield()
                    }
                }
        } else {
            while (hasMore) {
                onProgress(
                    totalAlbums?.let { "Loading Plex albums ${albumsById.size} of $it…" } ?: "Loading Plex albums…",
                    albumsById.size,
                    totalAlbums,
                )
                val page = plexClient.albumsPage(server, library, token, start = nextOffset, size = albumPageSize)
                page.albums.forEach { albumsById[it.id] = it }
                totalAlbums = page.totalSize ?: totalAlbums
                nextOffset = page.nextOffset
                hasMore = page.hasMore
                onPartial(
                    progressiveAlbumSnapshot(albumsById.values.toList(), playlists),
                    totalAlbums?.let { "Loaded ${albumsById.size} of $it Plex albums…" }
                        ?: "Loaded ${albumsById.size} Plex albums…",
                    albumsById.size,
                    totalAlbums,
                )
                yield()
            }
        }

        val rawAlbums = albumsById.values.toList()
        onProgress("Loading Plex artists…", rawAlbums.size, totalAlbums)
        val artists = artistsDeferred.await()
        val artistsResolved = enrichArtistAlbumCountsOnly(
            enrichArtistArtwork(artists, rawAlbums).ifEmpty { artistShellFromAlbums(rawAlbums) },
            rawAlbums,
        )
        CatalogSnapshot(
            artists = artistsResolved,
            albums = rawAlbums,
            playlists = playlists,
            tracksByParent = emptyMap(),
            downloads = emptyList(),
        )
    }

    private suspend fun fetchPlaylistsOrEmpty(
        server: PlexServer,
        token: String,
        onProgress: suspend (message: String, loadedAlbums: Int, totalAlbums: Int?) -> Unit,
    ): List<Playlist> {
        return try {
            plexClient.playlists(server, token)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            onProgress("Plex playlists unavailable, loading albums…", 0, null)
            emptyList()
        }
    }

    private suspend fun fetchArtistsOrEmpty(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
    ): List<Artist> {
        return try {
            plexClient.artists(server, library, token)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun prefetchAlbumTracks(
        server: PlexServer,
        albums: List<Album>,
        token: String,
        onAlbumTracks: suspend (Album, List<Track>) -> Unit = { _, _ -> },
    ): Map<String, List<Track>> = coroutineScope {
        val albumsSlice = albums.take(catalogTrackPrefetchAlbumCount())
        val mutex = Mutex()
        val tracksAccum = mutableMapOf<String, List<Track>>()

        albumsSlice
            .chunked(catalogTrackPrefetchParallelism().coerceAtLeast(1))
            .forEach { albumChunk ->
                albumChunk.map { album ->
                    async {
                        val tracks = plexClient.children(server, album.id, token)
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

    private fun progressiveAlbumSnapshot(albums: List<Album>, playlists: List<Playlist>): CatalogSnapshot =
        CatalogSnapshot(
            artists = enrichArtistAlbumCountsOnly(artistShellFromAlbums(albums), albums),
            albums = albums,
            playlists = playlists,
            tracksByParent = emptyMap(),
            downloads = emptyList(),
        )

    private fun artistShellFromAlbums(albums: List<Album>): List<Artist> =
        albums.groupBy { it.artist }.values.map { list ->
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
