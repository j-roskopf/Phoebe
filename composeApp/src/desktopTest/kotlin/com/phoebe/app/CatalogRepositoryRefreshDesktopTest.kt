package com.phoebe.app

import app.cash.sqldelight.db.SqlDriver
import com.phoebe.app.data.CatalogRepository
import com.phoebe.app.data.JellyfinClient
import com.phoebe.app.data.MediaSourcesRepository
import com.phoebe.app.data.PlexClient
import com.phoebe.app.domain.CatalogSyncPhase
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.CollectionFacet
import com.phoebe.app.domain.CollectionTarget
import com.phoebe.app.domain.JellyfinSyncMode
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.testing.minimalMp3Bytes
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogRepositoryRefreshDesktopTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var driver: SqlDriver? = null

    @Before
    fun storageRoot() {
        System.setProperty("phoebe.storage.root", temp.newFolder("kv").absolutePath)
    }

    @After
    fun cleanup() {
        driver?.close()
        driver = null
        System.clearProperty("phoebe.storage.root")
    }

    @Test
    fun refreshAggregatedWithNoSessionAndNoFoldersYieldsEmptyCatalog() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        val http = testHttpClient(engine)
        val media = MediaSourcesRepository(db, PlatformStorage())
        val repo = CatalogRepository(
            plexClient = PlexClient(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = media,
        )
        repo.refreshAggregated(session = null)
        assertFalse(repo.catalogRefreshing.value)
        assertEquals(0, repo.catalog.value.artists.size)
        assertEquals(0, repo.catalog.value.albums.size)
    }

    @Test
    fun refreshLocalFoldersOnlyReportsScanCompletion() = runTest {
        val music = temp.newFolder("local-scan")
        File(music, "alpha.mp3").writeBytes(minimalMp3Bytes())
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        val http = testHttpClient(engine)
        val media = MediaSourcesRepository(db, PlatformStorage())
        val repo = CatalogRepository(
            plexClient = PlexClient(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = media,
        )
        media.addLocalFolder(music.toURI().toString(), "Local Scan")

        repo.refreshLocalFoldersOnly(session = null)

        assertFalse(repo.catalogRefreshing.value)
        assertEquals(CatalogSyncPhase.Complete, repo.catalogSyncState.value.phase)
        assertEquals("Local folders scanned.", repo.catalogSyncState.value.message)
        assertEquals(listOf("alpha"), repo.catalog.value.tracksByParent.values.flatten().map { it.title })
    }

    @Test
    fun refreshPublishesPlexMetadataBeforeAlbumTracksFinish() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val childrenStarted = CompletableDeferred<Unit>()
        val releaseChildren = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all" -> respondJson(artistsJson())
                "/library/sections/1/albums" -> respondJson(albumsJson())
                "/playlists" -> respondJson(playlistsJson(trackCount = 1))
                "/library/metadata/a1/children" -> {
                    childrenStarted.complete(Unit)
                    releaseChildren.await()
                    respondJson(albumTracksJson())
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val media = MediaSourcesRepository(db, PlatformStorage())
        val repo = CatalogRepository(
            plexClient = PlexClient(testHttpClient(engine)),
            database = db,
            storage = PlatformStorage(),
            httpClient = testHttpClient(engine),
            mediaSourcesRepository = media,
        )

        val refresh = async { repo.refreshAggregated(testSession()) }
        childrenStarted.await()

        assertFalse(repo.catalogRefreshing.value)
        assertEquals(CatalogSyncPhase.LoadingSongs, repo.catalogSyncState.value.phase)
        assertFalse(repo.catalogSyncState.value.showGlobalProgress)
        assertEquals(listOf("plex:artist1"), repo.catalog.value.artists.map { it.id })
        assertEquals(listOf("plex:a1"), repo.catalog.value.albums.map { it.id })
        assertEquals(listOf("plex:p1"), repo.catalog.value.playlists.map { it.id })
        assertEquals(emptyMap(), repo.catalog.value.tracksByParent)

        releaseChildren.complete(Unit)
        refresh.await()

        assertFalse(repo.catalogRefreshing.value)
        assertEquals(CatalogSyncPhase.Complete, repo.catalogSyncState.value.phase)
        assertEquals(listOf("plex:t1"), repo.catalog.value.tracksByParent["plex:a1"].orEmpty().map { it.id })
    }

    @Test
    fun jellyfinFullSyncPublishesPagesAsTheyArrive() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val secondAlbumStarted = CompletableDeferred<Unit>()
        val releaseSecondAlbum = CompletableDeferred<Unit>()
        val secondTrackStarted = CompletableDeferred<Unit>()
        val releaseSecondTrack = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/Items" -> when (request.url.parameters["includeItemTypes"]) {
                    "MusicAlbum" -> {
                        val start = request.url.parameters["startIndex"]?.toIntOrNull() ?: 0
                        if (start == 0) {
                            respondJson(jellyfinAlbumsPageJson(start = 1, count = JellyfinClient.JellyfinPageSize, total = JellyfinClient.JellyfinPageSize + 1))
                        } else {
                            secondAlbumStarted.complete(Unit)
                            releaseSecondAlbum.await()
                            respondJson(jellyfinAlbumsPageJson(start = JellyfinClient.JellyfinPageSize + 1, count = 1, total = JellyfinClient.JellyfinPageSize + 1))
                        }
                    }
                    "Audio" -> {
                        if (request.url.parameters["isFavorite"] == "true") {
                            respondJson("""{ "Items": [], "TotalRecordCount": 0 }""")
                        } else {
                            val start = request.url.parameters["startIndex"]?.toIntOrNull() ?: 0
                            if (start == 0) {
                                respondJson(jellyfinTracksPageJson(start = 1, count = JellyfinClient.JellyfinPageSize, total = JellyfinClient.JellyfinPageSize + 1))
                            } else {
                                secondTrackStarted.complete(Unit)
                                releaseSecondTrack.await()
                                respondJson(jellyfinTracksPageJson(start = JellyfinClient.JellyfinPageSize + 1, count = 1, total = JellyfinClient.JellyfinPageSize + 1))
                            }
                        }
                    }
                    "Playlist" -> respondJson("""{ "Items": [], "TotalRecordCount": 0 }""")
                    else -> respond("", HttpStatusCode.NotFound)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val http = testHttpClient(engine)
        val media = MediaSourcesRepository(db, PlatformStorage())
        val repo = CatalogRepository(
            plexClient = PlexClient(http),
            jellyfinClient = JellyfinClient(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = media,
        )

        val refresh = async { repo.refreshAggregated(testJellyfinSession(syncMode = JellyfinSyncMode.Full)) }
        secondAlbumStarted.await()

        assertTrue(repo.catalogRefreshing.value)
        assertEquals(CatalogSyncPhase.LoadingLibrary, repo.catalogSyncState.value.phase)
        assertEquals(JellyfinClient.JellyfinPageSize, repo.catalog.value.albums.size)
        assertEquals("jellyfin:album-1", repo.catalog.value.albums.first().id)

        releaseSecondAlbum.complete(Unit)
        secondTrackStarted.await()

        assertTrue(repo.catalogRefreshing.value)
        assertEquals(CatalogSyncPhase.LoadingSongs, repo.catalogSyncState.value.phase)
        assertEquals(JellyfinClient.JellyfinPageSize + 1, repo.catalog.value.albums.size)
        assertEquals(JellyfinClient.JellyfinPageSize, repo.catalog.value.tracksByParent.values.flatten().size)
        assertEquals("jellyfin:track-1", repo.catalog.value.tracksByParent.values.flatten().first().id)

        releaseSecondTrack.complete(Unit)
        refresh.await()

        assertEquals(CatalogSyncPhase.Complete, repo.catalogSyncState.value.phase)
        assertEquals(JellyfinClient.JellyfinPageSize + 1, repo.catalog.value.tracksByParent.values.flatten().size)
    }

    @Test
    fun cancellingJellyfinFullSyncStopsRefreshingState() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val secondAlbumStarted = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/Items" -> when (request.url.parameters["includeItemTypes"]) {
                    "MusicAlbum" -> {
                        val start = request.url.parameters["startIndex"]?.toIntOrNull() ?: 0
                        if (start == 0) {
                            respondJson(jellyfinAlbumsPageJson(start = 1, count = JellyfinClient.JellyfinPageSize, total = JellyfinClient.JellyfinPageSize + 1))
                        } else {
                            secondAlbumStarted.complete(Unit)
                            awaitCancellation()
                        }
                    }
                    "Audio" -> respondJson("""{ "Items": [], "TotalRecordCount": 0 }""")
                    "Playlist" -> respondJson("""{ "Items": [], "TotalRecordCount": 0 }""")
                    else -> respond("", HttpStatusCode.NotFound)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val http = testHttpClient(engine)
        val media = MediaSourcesRepository(db, PlatformStorage())
        val repo = CatalogRepository(
            plexClient = PlexClient(http),
            jellyfinClient = JellyfinClient(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = media,
        )

        val refresh = async { repo.refreshAggregated(testJellyfinSession(syncMode = JellyfinSyncMode.Full)) }
        secondAlbumStarted.await()

        refresh.cancelAndJoin()

        assertFalse(repo.catalogRefreshing.value)
        assertEquals(CatalogSyncPhase.LoadingLibrary, repo.catalogSyncState.value.phase)
    }

    @Test
    fun refreshDoesNotProbeIdentityBeforeLoadingLibrary() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/identity" -> awaitCancellation()
                "/library/sections/1/all" -> if (request.url.parameters["type"] == "10") {
                    respondJson(trackPageJson())
                } else {
                    respondJson(artistsJson())
                }
                "/library/sections/1/albums" -> respondJson(albumsJson())
                "/library/metadata/a1/children" -> respondJson(albumTracksJson())
                "/playlists" -> respondJson(playlistsJson(trackCount = 0))
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val http = testHttpClient(engine)
        val media = MediaSourcesRepository(db, PlatformStorage())
        val repo = CatalogRepository(
            plexClient = PlexClient(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = media,
        )

        repo.refreshAggregated(testSession())

        assertEquals(listOf("plex:a1"), repo.catalog.value.albums.map { it.id })
    }

    @Test
    fun refreshPublishesPlexMetadataAfterCollectionCapableMetadataCompletes() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val artistsStarted = CompletableDeferred<Unit>()
        val releaseArtists = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all" -> if (request.url.parameters["type"] == "10") {
                    respondJson(trackPageJson())
                } else {
                    artistsStarted.complete(Unit)
                    releaseArtists.await()
                    respondJson(artistsJson())
                }
                "/library/sections/1/albums" -> respondJson(albumsJson())
                "/playlists" -> respondJson(playlistsJson(trackCount = 1))
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val http = testHttpClient(engine)
        val media = MediaSourcesRepository(db, PlatformStorage())
        val repo = CatalogRepository(
            plexClient = PlexClient(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = media,
        )

        val refresh = async { repo.refreshAggregated(testSession()) }
        artistsStarted.await()

        assertTrue(repo.catalogRefreshing.value)
        assertEquals(CatalogSyncPhase.LoadingLibrary, repo.catalogSyncState.value.phase)
        assertEquals(emptyList(), repo.catalog.value.albums.map { it.id })
        assertEquals(emptyList(), repo.catalog.value.playlists.map { it.id })

        releaseArtists.complete(Unit)
        refresh.await()

        assertEquals(listOf("plex:a1"), repo.catalog.value.albums.map { it.id })
        assertEquals(listOf("plex:p1"), repo.catalog.value.playlists.map { it.id })
    }

    @Test
    fun metadataPublishPreservesCachedTracksUntilFreshTracksArrive() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        db.transaction {
            db.catalogQueries.upsertArtist("plex:artist1", "Artist One", null, 1, 0, 0, null, null, null, null, null, 0)
            db.catalogQueries.upsertAlbum("plex:a1", "Album One", "Artist One", null, null, 0, null, null, null, null, null, 0)
            db.catalogQueries.upsertTrack(
                id = "plex:old",
                title = "Cached Song",
                artist = "Artist One",
                album = "Album One",
                durationMs = 10,
                streamUrl = "https://plex.example/old?X-Plex-Token=token",
                downloadUrl = "https://plex.example/old?X-Plex-Token=token&download=1",
                thumbUrl = null,
                localArtworkUri = null,
                localUri = null,
                year = null,
                genre = null,
                mood = null,
                style = null,
                filepath = null,
                audioCodec = null,
                bitrateKbps = null,
                dateAddedMs = null,
                rating = null,
                parentAlbumId = null,
            )
            db.catalogQueries.upsertTrackParent("plex:a1", "plex:old", 0)
        }

        val childrenStarted = CompletableDeferred<Unit>()
        val releaseChildren = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all" -> respondJson(artistsJson())
                "/library/sections/1/albums" -> respondJson(albumsJson())
                "/playlists" -> respondJson(playlistsJson(trackCount = 0))
                "/library/metadata/a1/children" -> {
                    childrenStarted.complete(Unit)
                    releaseChildren.await()
                    respondJson(albumTracksJson())
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val media = MediaSourcesRepository(db, PlatformStorage())
        val repo = CatalogRepository(
            plexClient = PlexClient(testHttpClient(engine)),
            database = db,
            storage = PlatformStorage(),
            httpClient = testHttpClient(engine),
            mediaSourcesRepository = media,
        )
        repo.restoreCachedCatalog()

        val refresh = async { repo.refreshAggregated(testSession()) }
        childrenStarted.await()

        assertEquals(listOf("plex:old"), repo.catalog.value.tracksByParent["plex:a1"].orEmpty().map { it.id })

        releaseChildren.complete(Unit)
        refresh.await()

        assertEquals(listOf("plex:t1"), repo.catalog.value.tracksByParent["plex:a1"].orEmpty().map { it.id })
    }

    @Test
    fun refreshPreservesExistingFirstSeenDateWhenSourceOmitsAddedAt() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        db.transaction {
            db.catalogQueries.upsertArtist("plex:artist1", "Artist One", null, 1, 0, 0, 41L, null, null, null, null, 0)
            db.catalogQueries.upsertAlbum("plex:a1", "Album One", "Artist One", null, null, 0, 41L, null, null, null, null, 0)
            db.catalogQueries.upsertTrack(
                id = "plex:t1",
                title = "Cached Song",
                artist = "Artist One",
                album = "Album One",
                durationMs = 10,
                streamUrl = "https://plex.example/t1?X-Plex-Token=token",
                downloadUrl = "https://plex.example/t1?X-Plex-Token=token&download=1",
                thumbUrl = null,
                localArtworkUri = null,
                localUri = null,
                year = null,
                genre = null,
                mood = null,
                style = null,
                filepath = null,
                audioCodec = null,
                bitrateKbps = null,
                dateAddedMs = 41L,
                rating = null,
                parentAlbumId = null,
            )
            db.catalogQueries.upsertTrackParent("plex:a1", "plex:t1", 0)
        }
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all" -> respondJson(artistsJson())
                "/library/sections/1/albums" -> respondJson(albumsJson())
                "/playlists" -> respondJson(playlistsJson(trackCount = 0))
                "/library/metadata/a1/children" -> respondJson(albumTracksJson())
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val media = MediaSourcesRepository(db, PlatformStorage())
        val repo = CatalogRepository(
            plexClient = PlexClient(testHttpClient(engine)),
            database = db,
            storage = PlatformStorage(),
            httpClient = testHttpClient(engine),
            mediaSourcesRepository = media,
        )
        repo.restoreCachedCatalog()
        repo.refreshAggregated(testSession())

        assertEquals(41L, repo.catalog.value.tracksByParent["plex:a1"].orEmpty().single().dateAddedMs)
    }

    @Test
    fun refreshIndexesPagedTracksIntoAlbumParentsAndRestoresThem() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all" -> if (request.url.parameters["type"] == "10") {
                    respondJson(trackPageJson())
                } else {
                    respondJson(artistsJson())
                }
                "/library/sections/1/albums" -> respondJson(albumsJson())
                "/playlists" -> respondJson(playlistsJson(trackCount = 0))
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val http = testHttpClient(engine)
        val media = MediaSourcesRepository(db, PlatformStorage())
        val repo = CatalogRepository(
            plexClient = PlexClient(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = media,
        )

        repo.refreshAggregated(testSession())

        assertEquals(listOf("plex:t1"), repo.catalog.value.tracksByParent["plex:a1"].orEmpty().map { it.id })
        assertEquals("a1", repo.catalog.value.tracksByParent["plex:a1"].orEmpty().single().parentAlbumId)

        val restored = CatalogRepository(
            plexClient = PlexClient(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = media,
        )
        restored.restoreCachedCatalog()

        assertEquals(listOf("plex:t1"), restored.catalog.value.tracksByParent["plex:a1"].orEmpty().map { it.id })
        assertEquals("a1", restored.catalog.value.tracksByParent["plex:a1"].orEmpty().single().parentAlbumId)
    }

    @Test
    fun albumMoodItemsFallBackToIndexedTrackMoodWhenPlexFilterReturnsEmpty() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        db.transaction {
            db.catalogQueries.upsertAlbum("plex:a1", "Album One", "Artist One", null, null, 0, null, null, null, null, null, 0)
            db.catalogQueries.upsertTrack(
                id = "plex:t1",
                title = "Angry Song",
                artist = "Artist One",
                album = "Album One",
                durationMs = 10,
                streamUrl = "https://plex.example/t1?X-Plex-Token=token",
                downloadUrl = "https://plex.example/t1?X-Plex-Token=token&download=1",
                thumbUrl = null,
                localArtworkUri = null,
                localUri = null,
                year = null,
                genre = null,
                mood = "Angry",
                style = null,
                filepath = null,
                audioCodec = null,
                bitrateKbps = null,
                dateAddedMs = null,
                rating = null,
                parentAlbumId = null,
            )
            db.catalogQueries.upsertTrackParent("plex:a1", "plex:t1", 0)
            db.catalogQueries.upsertCollectionValue("Albums", "Mood", "Angry", "999", null, "album.mood", 0)
        }
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all",
                "/library/sections/1/albums" -> respondJson("""{ "MediaContainer": { "Metadata": [] } }""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val http = testHttpClient(engine)
        val media = MediaSourcesRepository(db, PlatformStorage())
        val repo = CatalogRepository(
            plexClient = PlexClient(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = media,
        )

        repo.restoreCachedCatalog()
        repo.ensureCollectionItems(testSession(), CollectionEntry(CollectionTarget.Albums, CollectionFacet.Mood), "Angry")

        assertEquals(listOf("plex:a1"), repo.catalog.value.collectionTags.map { it.itemId })
    }

    @Test
    fun collectionItemLoadRefreshesStaleCachedFilterChoice() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        db.transaction {
            db.catalogQueries.upsertAlbum("plex:a1", "Album One", "Artist One", null, null, 0, null, null, null, null, null, 0)
            db.catalogQueries.upsertCollectionValue("Albums", "Mood", "Angry", "stale", "/library/sections/1/all?type=9&album.mood=stale", "album.mood", 0)
        }
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/album.mood" -> respondJson(
                    """
                        {
                          "MediaContainer": {
                            "Directory": [
                              { "key": "999", "title": "Angry" }
                            ]
                          }
                        }
                    """.trimIndent(),
                )
                "/library/sections/1/all" -> if (request.url.parameters["album.mood"] == "999") {
                    respondJson(
                        """
                            {
                              "MediaContainer": {
                                "Metadata": [
                                  { "ratingKey": "a1", "title": "Album One", "type": "album" }
                                ]
                              }
                            }
                        """.trimIndent(),
                    )
                } else {
                    respondJson("""{ "MediaContainer": { "Metadata": [] } }""")
                }
                "/library/sections/1/albums" -> respondJson("""{ "MediaContainer": { "Metadata": [] } }""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val http = testHttpClient(engine)
        val media = MediaSourcesRepository(db, PlatformStorage())
        val repo = CatalogRepository(
            plexClient = PlexClient(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = media,
        )

        repo.restoreCachedCatalog()
        repo.ensureCollectionItems(testSession(), CollectionEntry(CollectionTarget.Albums, CollectionFacet.Mood), "Angry")

        assertEquals(listOf("plex:a1"), repo.catalog.value.collectionTags.map { it.itemId })
        assertEquals("999", repo.catalog.value.collectionValues.single().key)
    }

    @Test
    fun collectionValuesReloadWhenOnlyEmptyLoadMarkerWasCached() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        db.transaction {
            db.catalogQueries.upsertCollectionValueLoad("Albums", "Mood")
        }
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/album.mood" -> respondJson(
                    """
                        {
                          "MediaContainer": {
                            "Directory": [
                              { "key": "999", "title": "Angry" }
                            ]
                          }
                        }
                    """.trimIndent(),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val http = testHttpClient(engine)
        val media = MediaSourcesRepository(db, PlatformStorage())
        val repo = CatalogRepository(
            plexClient = PlexClient(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = media,
        )

        repo.restoreCachedCatalog()
        repo.ensureCollectionValues(testSession(), CollectionEntry(CollectionTarget.Albums, CollectionFacet.Mood))

        assertEquals(listOf("Angry"), repo.catalog.value.collectionValues.map { it.value })
    }

    @Test
    fun collectionItemLoadStoresCanonicalPlexAlbumIds() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        db.transaction {
            db.catalogQueries.upsertAlbum("plex:a1", "Album One", "Artist One", null, null, 0, null, null, null, null, null, 0)
            db.catalogQueries.upsertCollectionValue("Albums", "Mood", "Angry", "999", null, "mood", 0)
        }
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all" -> if (request.url.parameters["mood"] == "999") {
                    respondJson(
                        """
                            {
                              "MediaContainer": {
                                "Metadata": [
                                  { "ratingKey": "a1", "title": "Album One", "type": "album" }
                                ]
                              }
                            }
                        """.trimIndent(),
                    )
                } else {
                    respondJson("""{ "MediaContainer": { "Metadata": [] } }""")
                }
                "/library/sections/1/albums" -> respondJson("""{ "MediaContainer": { "Metadata": [] } }""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val http = testHttpClient(engine)
        val media = MediaSourcesRepository(db, PlatformStorage())
        val repo = CatalogRepository(
            plexClient = PlexClient(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = media,
        )

        repo.restoreCachedCatalog()
        repo.ensureCollectionItems(testSession(), CollectionEntry(CollectionTarget.Albums, CollectionFacet.Mood), "Angry")

        assertEquals(listOf("plex:a1"), repo.catalog.value.collectionTags.map { it.itemId })
    }

    @Test
    fun collectionItemLoadRefetchesWhenCachedTagsDoNotMatchCatalogIds() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        db.transaction {
            db.catalogQueries.upsertAlbum("plex:a1", "Album One", "Artist One", null, null, 0, null, null, null, null, null, 0)
            db.catalogQueries.upsertCollectionValue("Albums", "Mood", "Angry", "999", null, "mood", 1)
            db.catalogQueries.upsertCollectionTag("Albums", "Mood", "plex:track1", "Angry")
        }
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all" -> if (request.url.parameters["mood"] == "999") {
                    respondJson(
                        """
                            {
                              "MediaContainer": {
                                "Metadata": [
                                  { "ratingKey": "a1", "title": "Album One", "type": "album" }
                                ]
                              }
                            }
                        """.trimIndent(),
                    )
                } else {
                    respondJson("""{ "MediaContainer": { "Metadata": [] } }""")
                }
                "/library/sections/1/albums" -> respondJson("""{ "MediaContainer": { "Metadata": [] } }""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val http = testHttpClient(engine)
        val media = MediaSourcesRepository(db, PlatformStorage())
        val repo = CatalogRepository(
            plexClient = PlexClient(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = media,
        )

        repo.restoreCachedCatalog()
        repo.ensureCollectionItems(testSession(), CollectionEntry(CollectionTarget.Albums, CollectionFacet.Mood), "Angry")

        assertEquals(listOf("plex:a1"), repo.catalog.value.collectionTags.map { it.itemId })
    }

    @Test
    fun addTracksToPlaylistRefetchesWhenPlaylistTracksAreNotCached() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all" -> respondJson(artistsJson())
                "/library/sections/1/albums" -> respondJson(albumsJson())
                "/playlists" -> respondJson(playlistsJson(trackCount = 2, thumb = "/playlists/p1/art"))
                "/library/metadata/a1/children" -> respondJson(albumTracksJson())
                "/playlists/p1/items" -> when (request.method.value) {
                    "PUT" -> respondJson(playlistAddResponseJson(leafCount = 3))
                    else -> respondJson(playlistTracksJson())
                }
                "/identity" -> respondJson(identityJson())
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val media = MediaSourcesRepository(db, PlatformStorage())
        val repo = CatalogRepository(
            plexClient = PlexClient(testHttpClient(engine)),
            database = db,
            storage = PlatformStorage(),
            httpClient = testHttpClient(engine),
            mediaSourcesRepository = media,
        )
        repo.refreshAggregated(testSession())

        val playlist = repo.catalog.value.playlists.single()
        assertEquals(2, playlist.trackCount)
        assertTrue(repo.catalog.value.tracksByParent[playlist.id].isNullOrEmpty())

        val newTrack = Track(
            id = "plex:t3",
            title = "Added Song",
            artist = "Artist One",
            album = "Album One",
            durationMs = 2_000,
            streamUrl = "https://plex.example/t3?X-Plex-Token=token",
            downloadUrl = "https://plex.example/t3?X-Plex-Token=token&download=1",
        )
        repo.addTracksToPlaylist(testSession(), playlist, listOf(newTrack))

        val updated = repo.catalog.value.playlists.single { it.id == playlist.id }
        assertEquals(3, updated.trackCount)
        assertEquals(
            listOf("plex:t3", "plex:t1", "plex:t2"),
            repo.catalog.value.tracksByParent[playlist.id].orEmpty().map { it.id },
        )
    }

    @Test
    fun refreshRefetchesPlaylistWhenPlexReportsFewerTracksThanCache() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        var playlistTrackCount = 2
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all" -> if (request.url.parameters["type"] == "10") {
                    respondJson(trackPageJson())
                } else {
                    respondJson(artistsJson())
                }
                "/library/sections/1/albums" -> respondJson(albumsJson())
                "/playlists" -> respondJson(playlistsJson(trackCount = playlistTrackCount))
                "/playlists/p1/items" -> respondJson(
                    if (playlistTrackCount == 1) playlistTracksAfterServerDeletionJson() else playlistTracksJson(),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val http = testHttpClient(engine)
        val media = MediaSourcesRepository(db, PlatformStorage())
        val repo = CatalogRepository(
            plexClient = PlexClient(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = media,
        )

        repo.refreshAggregated(testSession())
        val playlist = repo.catalog.value.playlists.single()
        assertEquals(listOf("plex:t1", "plex:t2"), repo.tracksForPlaylist(testSession(), playlist).map { it.id })

        playlistTrackCount = 1
        repo.refreshAggregated(testSession())

        val refreshedPlaylist = repo.catalog.value.playlists.single { it.id == playlist.id }
        assertEquals(1, refreshedPlaylist.trackCount)
        assertEquals(listOf("plex:t1"), repo.catalog.value.tracksByParent[playlist.id].orEmpty().map { it.id })
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun testSession(
        server: PlexServer = PlexServer("server", "Plex", "https://plex.example:32400", owned = true),
    ): PlexSession = PlexSession(
        token = "token",
        selectedServer = server,
        selectedLibrary = MusicLibrary("1", "Music"),
    )

    private fun testJellyfinSession(
        syncMode: JellyfinSyncMode = JellyfinSyncMode.Quick,
    ): PlexSession = PlexSession(
        token = "token",
        selectedServer = PlexServer("jellyfin:server", "Jellyfin", "https://jellyfin.example", owned = true),
        selectedLibrary = MusicLibrary("music", "Music"),
        providerType = MediaProviderType.Jellyfin,
        userId = "user-1",
        jellyfinSyncMode = syncMode,
    )

    private fun artistsJson(): String = """
        {
          "MediaContainer": {
            "Metadata": [
              { "ratingKey": "artist1", "type": "artist", "title": "Artist One", "leafCount": 1 }
            ]
          }
        }
    """.trimIndent()

    private fun albumsJson(): String = """
        {
          "MediaContainer": {
            "Metadata": [
              { "ratingKey": "a1", "title": "Album One", "parentTitle": "Artist One", "librarySectionID": 1 }
            ]
          }
        }
    """.trimIndent()

    private fun playlistsJson(trackCount: Int, thumb: String? = null): String {
        val thumbJson = thumb?.let { """, "thumb": "$it"""" }.orEmpty()
        return """
        {
          "MediaContainer": {
            "Metadata": [
              { "ratingKey": "p1", "title": "Playlist One", "leafCount": $trackCount, "key": "/playlists/p1/items"$thumbJson }
            ]
          }
        }
    """.trimIndent()
    }

    private fun albumTracksJson(): String = """
        {
          "MediaContainer": {
            "Metadata": [
              {
                "ratingKey": "t1",
                "title": "Fresh Song",
                "grandparentTitle": "Artist One",
                "parentTitle": "Album One",
                "duration": 1000,
                "Media": [
                  { "Part": [ { "key": "/library/parts/t1/file.mp3", "file": "file.mp3" } ] }
                ]
              }
            ]
          }
        }
    """.trimIndent()

    private fun trackPageJson(): String = """
        {
          "MediaContainer": {
            "size": 1,
            "offset": 0,
            "totalSize": 1,
            "Metadata": [
              {
                "ratingKey": "t1",
                "parentRatingKey": "a1",
                "title": "Fresh Song",
                "grandparentTitle": "Artist One",
                "parentTitle": "Album One",
                "parentYear": 1995,
                "duration": 1000,
                "addedAt": 1700000200,
                "Media": [
                  { "Part": [ { "key": "/library/parts/t1/file.mp3", "file": "file.mp3" } ] }
                ]
              }
            ]
          }
        }
    """.trimIndent()

    private fun playlistTracksJson(): String = """
        {
          "MediaContainer": {
            "Metadata": [
              {
                "ratingKey": "t1",
                "title": "Playlist Song One",
                "grandparentTitle": "Artist One",
                "parentTitle": "Album One",
                "duration": 1000,
                "Media": [
                  { "Part": [ { "key": "/library/parts/t1/file.mp3", "file": "one.mp3" } ] }
                ]
              },
              {
                "ratingKey": "t2",
                "title": "Playlist Song Two",
                "grandparentTitle": "Artist One",
                "parentTitle": "Album One",
                "duration": 2000,
                "Media": [
                  { "Part": [ { "key": "/library/parts/t2/file.mp3", "file": "two.mp3" } ] }
                ]
              }
            ]
          }
        }
    """.trimIndent()

    private fun playlistTracksAfterServerDeletionJson(): String = """
        {
          "MediaContainer": {
            "Metadata": [
              {
                "ratingKey": "t1",
                "title": "Playlist Song One",
                "grandparentTitle": "Artist One",
                "parentTitle": "Album One",
                "duration": 1000,
                "Media": [
                  { "Part": [ { "key": "/library/parts/t1/file.mp3", "file": "one.mp3" } ] }
                ]
              }
            ]
          }
        }
    """.trimIndent()

    private fun identityJson(): String = """
        {
          "MediaContainer": {
            "machineIdentifier": "server"
          }
        }
    """.trimIndent()

    private fun playlistAddResponseJson(leafCount: Int): String = """
        {
          "MediaContainer": {
            "leafCountAdded": 1,
            "Metadata": [
              { "ratingKey": "p1", "title": "Playlist One", "leafCount": $leafCount, "key": "/playlists/p1/items" }
            ]
          }
        }
    """.trimIndent()

    private fun jellyfinAlbumsPageJson(start: Int, count: Int, total: Int): String {
        val items = (start until start + count).joinToString(",") { index ->
            """
              {
                "Id": "album-$index",
                "Type": "MusicAlbum",
                "Name": "Album $index",
                "AlbumArtist": "Artist $index",
                "RunTimeTicks": 10000000
              }
            """.trimIndent()
        }
        return """{ "Items": [ $items ], "TotalRecordCount": $total }"""
    }

    private fun jellyfinTracksPageJson(start: Int, count: Int, total: Int): String {
        val items = (start until start + count).joinToString(",") { index ->
            """
              {
                "Id": "track-$index",
                "Type": "Audio",
                "Name": "Track $index",
                "Album": "Album $index",
                "AlbumId": "album-$index",
                "AlbumArtist": "Artist $index",
                "Artists": ["Artist $index"],
                "RunTimeTicks": 10000000
              }
            """.trimIndent()
        }
        return """{ "Items": [ $items ], "TotalRecordCount": $total }"""
    }
}
