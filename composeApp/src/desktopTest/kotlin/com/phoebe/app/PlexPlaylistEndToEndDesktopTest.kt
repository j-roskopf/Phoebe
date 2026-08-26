package com.phoebe.app

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.SqlDriver
import com.phoebe.app.data.CatalogRepository
import com.phoebe.app.data.MediaSourcesRepository
import com.phoebe.app.data.PlexClient
import com.phoebe.app.domain.DownloadState
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.platform.downloadParallelism
import com.phoebe.app.testing.albumTracksJson
import com.phoebe.app.testing.albumsJson
import com.phoebe.app.testing.artistsJson
import com.phoebe.app.testing.identityJson
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import com.phoebe.app.testing.playlistAddResponseJson
import com.phoebe.app.testing.plexCatalogMockEngine
import com.phoebe.app.testing.playlistsJson
import com.phoebe.app.testing.playlistTracksJson
import com.phoebe.app.testing.testHttpClient
import com.phoebe.app.testing.testPlexSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlexPlaylistEndToEndDesktopTest {
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
    fun createPlaylistSeedsMockPlexAndUpdatesCatalog() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val engine = plexCatalogMockEngine()
        val http = testHttpClient(engine)
        val repo = catalogRepository(db, http)

        repo.refreshAggregated(testPlexSession())
        val seed = repo.catalog.value.tracksByParent["plex:a1"].orEmpty().single()
        val created = repo.createPlaylist(testPlexSession(), "New Mix", listOf(seed))

        assertNotNull(created)
        assertEquals("plex:p99", created.id)
        assertEquals("New Mix", created.title)
        assertTrue(repo.catalog.value.playlists.any { it.id == "plex:p99" })
    }

    @Test
    fun addTracksToPlaylistRefetchesAndSyncsToMockPlex() = runTest {
        var plexAddCalled = false
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val engine = plexCatalogMockEngine(onPlaylistAdd = { plexAddCalled = true })
        val http = testHttpClient(engine)
        val repo = catalogRepository(db, http)

        repo.refreshAggregated(testPlexSession())
        val playlist = repo.catalog.value.playlists.single()
        val newTrack = Track(
            id = "plex:t3",
            title = "Added Song",
            artist = "Artist One",
            album = "Album One",
            durationMs = 2_000,
            streamUrl = "https://plex.example/t3?X-Plex-Token=token",
            downloadUrl = "https://plex.example/t3?X-Plex-Token=token&download=1",
        )
        repo.addTracksToPlaylist(testPlexSession(), playlist, listOf(newTrack))

        assertTrue(plexAddCalled)
        assertEquals(3, repo.catalog.value.playlists.single { it.id == playlist.id }.trackCount)
        assertEquals(
            listOf("plex:t3", "plex:t1", "plex:t2"),
            repo.catalog.value.tracksByParent[playlist.id].orEmpty().map { it.id },
        )
    }

    @Test
    fun tracksForPlaylistLoadsFromMockPlexWhenCacheEmpty() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(plexCatalogMockEngine(playlistThumb = "/playlists/p1/art"))
        val repo = catalogRepository(db, http)

        repo.refreshAggregated(testPlexSession())
        val playlist = repo.catalog.value.playlists.single()
        assertTrue(repo.catalog.value.tracksByParent[playlist.id].isNullOrEmpty())

        val tracks = repo.tracksForPlaylist(testPlexSession(), playlist)

        val expected = listOf("plex:t1", "plex:t2")
        assertEquals(
            expected,
            tracks.map { it.id }.ifEmpty { waitForPlaylistTrackIds(repo, playlist.id, expected) },
        )
    }

    @Test
    fun movePlaylistTrackSyncsPlexMoveEndpoint() = runTest {
        var movedItemId: Long? = null
        var movedAfterItemId: Long? = -1L
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(
            plexCatalogMockEngine(
                onPlaylistMove = { itemId, afterItemId ->
                    movedItemId = itemId
                    movedAfterItemId = afterItemId
                },
            ),
        )
        val repo = catalogRepository(db, http)

        repo.refreshAggregated(testPlexSession())
        val playlist = repo.catalog.value.playlists.single()
        repo.tracksForPlaylist(testPlexSession(), playlist)
        val moved = repo.movePlaylistTrack(testPlexSession(), playlist, fromIndex = 1, toIndex = 0)

        assertTrue(moved)
        assertEquals(102L, movedItemId)
        assertEquals(null, movedAfterItemId)
        assertEquals(
            listOf("plex:t2", "plex:t1"),
            waitForPlaylistTrackIds(repo, playlist.id, listOf("plex:t2", "plex:t1")),
        )
    }

    @Test
    fun movePlaylistTrackRollsBackWhenPlexMoveFails() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(plexCatalogMockEngine())
        val repo = catalogRepository(db, http)

        repo.refreshAggregated(testPlexSession())
        val playlist = repo.catalog.value.playlists.single()
        repo.tracksForPlaylist(testPlexSession(), playlist)
        val moved = repo.movePlaylistTrack(testPlexSession(), playlist, fromIndex = 1, toIndex = 0)

        assertFalse(moved)
        assertEquals(
            listOf("plex:t1", "plex:t2"),
            repo.catalog.value.tracksByParent[playlist.id].orEmpty().map { it.id },
        )
    }

    @Test
    fun movePlaylistTrackPersistsWhenPlexMoveSucceedsButImmediateReadbackIsStale() = runTest {
        var movedItemId: Long? = null
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all" -> if (request.url.parameters["type"] == "10") {
                    respondJson(albumTracksJson())
                } else {
                    respondJson(artistsJson())
                }
                "/library/sections/1/albums" -> respondJson(albumsJson())
                "/playlists" -> respondJson(playlistsJson(trackCount = 2))
                "/library/metadata/a1/children" -> respondJson(albumTracksJson())
                "/playlists/p1/items" -> respondJson(playlistTracksJson(listOf(101L, 102L)))
                "/playlists/p1/items/102/move" -> {
                    movedItemId = 102L
                    respondJson(playlistAddResponseJson(leafCount = 2))
                }
                "/identity" -> respondJson(identityJson())
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val repo = catalogRepository(db, testHttpClient(engine))

        repo.refreshAggregated(testPlexSession())
        val playlist = repo.catalog.value.playlists.single()
        repo.tracksForPlaylist(testPlexSession(), playlist)
        val moved = repo.movePlaylistTrack(testPlexSession(), playlist, fromIndex = 1, toIndex = 0)

        assertTrue(moved)
        assertEquals(102L, movedItemId)
        assertEquals(
            listOf("plex:t2", "plex:t1"),
            repo.catalog.value.tracksByParent[playlist.id].orEmpty().map { it.id },
        )
        repo.awaitDatabaseIdle()
        assertEquals(
            listOf("plex:t2", "plex:t1"),
            db.catalogQueries.selectTrackParents().awaitAsList()
                .filter { it.parentId == playlist.id }
                .map { it.trackId },
        )
    }

    @Test
    fun movePlaylistTrackUsesPlaylistItemIdForDuplicatePlexTracks() = runTest {
        var movedItemId: Long? = null
        var movedAfterItemId: Long? = null
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        var playlistItemIds = listOf(101L, 102L, 103L)
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all" -> if (request.url.parameters["type"] == "10") {
                    respondJson(albumTracksJson())
                } else {
                    respondJson(artistsJson())
                }
                "/library/sections/1/albums" -> respondJson(albumsJson())
                "/playlists" -> respondJson(playlistsJson(trackCount = 3))
                "/library/metadata/a1/children" -> respondJson(albumTracksJson())
                "/playlists/p1/items" -> respondJson(duplicatePlaylistTracksJson(playlistItemIds))
                "/playlists/p1/items/101/move" -> {
                    movedItemId = 101L
                    movedAfterItemId = request.url.parameters["after"]?.toLongOrNull()
                    playlistItemIds = listOf(102L, 103L, 101L)
                    respondJson(playlistAddResponseJson(leafCount = 3))
                }
                "/identity" -> respondJson(identityJson())
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val repo = catalogRepository(db, testHttpClient(engine))

        repo.refreshAggregated(testPlexSession())
        val playlist = repo.catalog.value.playlists.single()
        repo.tracksForPlaylist(testPlexSession(), playlist)
        val moved = repo.movePlaylistTrack(testPlexSession(), playlist, fromIndex = 0, toIndex = 2)

        assertTrue(moved)
        assertEquals(101L, movedItemId)
        assertEquals(103L, movedAfterItemId)
        assertEquals(
            listOf(102L, 103L, 101L),
            repo.catalog.value.tracksByParent[playlist.id].orEmpty().map { it.playlistItemId },
        )
    }

    @Test
    fun cachedPlexPlaylistTracksKeepPlaylistItemIdsAcrossRestore() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(plexCatalogMockEngine())
        val repo = catalogRepository(db, http)

        repo.refreshAggregated(testPlexSession())
        val playlist = repo.catalog.value.playlists.single()
        repo.tracksForPlaylist(testPlexSession(), playlist)
        repo.awaitDatabaseIdle()

        assertEquals(
            listOf(101L, 102L),
            db.catalogQueries.selectTrackParents().awaitAsList()
                .filter { it.parentId == playlist.id }
                .map { it.playlistItemId },
        )

        val restoredRepo = catalogRepository(db, http)
        restoredRepo.restoreCachedCatalog()

        assertEquals(
            listOf(101L, 102L),
            restoredRepo.catalog.value.tracksByParent[playlist.id].orEmpty().map { it.playlistItemId },
        )
    }

    @Test
    fun cachedPlexPlaylistTracksKeepDuplicateEntriesAcrossRestore() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all" -> if (request.url.parameters["type"] == "10") {
                    respondJson(albumTracksJson())
                } else {
                    respondJson(artistsJson())
                }
                "/library/sections/1/albums" -> respondJson(albumsJson())
                "/playlists" -> respondJson(playlistsJson(trackCount = 3))
                "/library/metadata/a1/children" -> respondJson(albumTracksJson())
                "/playlists/p1/items" -> respondJson(duplicatePlaylistTracksJson(listOf(101L, 102L, 103L)))
                "/identity" -> respondJson(identityJson())
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val http = testHttpClient(engine)
        val repo = catalogRepository(db, http)

        repo.refreshAggregated(testPlexSession())
        val playlist = repo.catalog.value.playlists.single()
        repo.tracksForPlaylist(testPlexSession(), playlist)
        repo.awaitDatabaseIdle()

        val persistedEntries = db.catalogQueries.selectTrackParents().awaitAsList()
            .filter { it.parentId == playlist.id }
        assertEquals(listOf("plex:t1", "plex:t2", "plex:t1"), persistedEntries.map { it.trackId })
        assertEquals(listOf(101L, 102L, 103L), persistedEntries.map { it.playlistItemId })

        val restoredRepo = catalogRepository(db, http)
        restoredRepo.restoreCachedCatalog()
        val restoredTracks = restoredRepo.catalog.value.tracksByParent[playlist.id].orEmpty()

        assertEquals(listOf("plex:t1", "plex:t2", "plex:t1"), restoredTracks.map { it.id })
        assertEquals(listOf(101L, 102L, 103L), restoredTracks.map { it.playlistItemId })
    }

    @Test
    fun warmPlaylistTracksLoadsMissingTracksWithoutForegroundRefresh() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(plexCatalogMockEngine(playlistThumb = "/playlists/p1/art"))
        val repo = catalogRepository(db, http)

        repo.refreshAggregated(testPlexSession())
        val playlist = repo.catalog.value.playlists.single()
        assertTrue(repo.catalog.value.tracksByParent[playlist.id].isNullOrEmpty())

        repo.warmPlaylistTracks(testPlexSession())

        assertFalse(repo.catalogRefreshing.value)
        assertEquals(
            listOf("plex:t1", "plex:t2"),
            waitForPlaylistTrackIds(repo, playlist.id, listOf("plex:t1", "plex:t2")),
        )
    }

    @Test
    fun refreshAggregatedLoadsLikedSongsForGlobalLikeState() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(plexCatalogMockEngine(includeLikedPlaylist = true))
        val repo = catalogRepository(db, http)

        repo.refreshAggregated(testPlexSession())

        val liked = repo.catalog.value.playlists.single { it.title == "Liked Songs" }
        assertEquals(listOf("plex:t1"), repo.catalog.value.tracksByParent[liked.id].orEmpty().map { it.id })
        assertTrue(repo.isTrackLiked("plex:t1"))
    }

    @Test
    fun toggleLikedTrackFindsExistingLikedPlaylistAndRemovesItem() = runTest {
        var plexRemoveCalled = false
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(plexCatalogMockEngine(includeLikedPlaylist = true, onPlaylistRemove = { plexRemoveCalled = true }))
        val repo = catalogRepository(db, http)

        repo.refreshAggregated(testPlexSession())
        val track = repo.tracksForAlbum(testPlexSession(), repo.catalog.value.albums.single()).single()
        assertTrue(repo.toggleLikedTrack(testPlexSession(), track).not())

        assertTrue(plexRemoveCalled)
        val liked = repo.catalog.value.playlists.single { it.title == "Liked Songs" }
        assertTrue(repo.catalog.value.tracksByParent[liked.id].orEmpty().none { it.id == track.id })
    }

    @Test
    fun toggleLikedTrackCreatesLikedPlaylistWhenMissing() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(plexCatalogMockEngine())
        val repo = catalogRepository(db, http)

        repo.refreshAggregated(testPlexSession())
        val track = repo.tracksForAlbum(testPlexSession(), repo.catalog.value.albums.single()).single()
        val liked = repo.toggleLikedTrack(testPlexSession(), track)

        assertTrue(liked)
        assertTrue(repo.catalog.value.playlists.any { it.title == "Liked Songs" })
    }

    @Test
    fun copyPlexPlaylistIntoPlaylistSkipsSelfDrop() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(plexCatalogMockEngine())
        val repo = catalogRepository(db, http)

        repo.refreshAggregated(testPlexSession())
        val playlist = repo.catalog.value.playlists.single()

        assertEquals(0, repo.copyPlexPlaylistIntoPlaylist(testPlexSession(), playlist, playlist))
    }

    @Test
    fun tracksForDecadeFetchesMatchingAlbumTracks() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(plexCatalogMockEngine())
        val repo = catalogRepository(db, http)

        repo.refreshAggregated(testPlexSession())
        val tracks = repo.tracksForDecade(testPlexSession(), 1990)

        assertEquals(listOf("plex:t1"), tracks.map { it.id })
        assertEquals(1995, tracks.single().year)
    }

    @Test
    fun largeDownloadPreflightSkipsTracksWithoutDownloadUrls() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(plexCatalogMockEngine())
        val repo = catalogRepository(db, http)
        val tracks = (0 until 1_000).map { index ->
            Track(
                id = "plex:missing-$index",
                title = "Missing $index",
                artist = "Artist One",
                album = "Album One",
                durationMs = 1_000,
                streamUrl = "",
                downloadUrl = "",
            )
        }
        tracks.take(3).forEach { track ->
            db.downloadsQueries.upsert(
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                dlState = DownloadState.Failed.name,
                progress = 0.0,
                localUri = null,
                downloadUrl = "",
                targetPath = "",
                downloadedBytes = 0L,
                totalBytes = null,
                updatedAtMs = 0L,
                batchId = null,
                error = null,
            )
        }

        val result = repo.downloadTracks(tracks)

        assertEquals(1_000, result.total)
        assertEquals(0, result.completed)
        assertEquals(0, result.failed)
        assertEquals(1_000, result.skipped)
        assertTrue(repo.catalog.value.downloads.isEmpty())
        val persisted = db.downloadsQueries.selectAll().awaitAsList()
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun downloadTracksStreamsAudioToStorage() = runTest {
        val payload = ByteArray(160 * 1024) { index -> (index % 251).toByte() }
        var downloadRequests = 0
        var artworkRequests = 0
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/downloads/t1.mp3" -> {
                    downloadRequests++
                    respond(
                        content = payload,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentLength to listOf(payload.size.toString()),
                            HttpHeaders.ContentType to listOf("audio/mpeg"),
                        ),
                    )
                }
                "/art/t1.jpg" -> {
                    artworkRequests++
                    respond(
                        content = ByteArray(1024) { 7 },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType to listOf("image/jpeg")),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val http = testHttpClient(engine)
        val repo = catalogRepository(db, http)
        PlatformStorage().writeDownloadDirectory(temp.newFolder("downloads").toURI().toString())
        val track = Track(
            id = "plex:t-stream",
            title = "Streamed Song",
            artist = "Artist One",
            album = "Album One",
            durationMs = 1_000,
            streamUrl = "https://plex.example/stream/t1.mp3",
            downloadUrl = "https://plex.example/downloads/t1.mp3",
            thumbUrl = "https://plex.example/art/t1.jpg",
        )

        val result = repo.downloadTracks(listOf(track))

        assertEquals(1, result.total)
        assertEquals(1, result.completed)
        assertEquals(0, result.failed)
        val downloaded = repo.catalog.value.downloads.single()
        assertEquals(DownloadState.Complete, downloaded.state)
        assertEquals(1f, downloaded.progress)
        val localUri = requireNotNull(downloaded.localUri)
        val stored = PlatformStorage().readUriBytes(localUri)
        assertNotNull(stored)
        assertTrue(payload.contentEquals(stored))
        val persisted = db.downloadsQueries.selectAll().awaitAsList().single()
        assertEquals(DownloadState.Complete.name, persisted.dlState)
        assertEquals(localUri, persisted.localUri)
        assertEquals(1, downloadRequests)
        assertEquals(0, artworkRequests)

        val retryResult = repo.downloadTracks(listOf(track))

        assertEquals(1, retryResult.total)
        assertEquals(1, retryResult.completed)
        assertEquals(0, retryResult.failed)
        assertEquals(1, downloadRequests)
        assertEquals(0, artworkRequests)
    }

    @Test
    fun downloadTracksRunsBatchDownloadsInParallel() = runTest {
        val payload = ByteArray(32 * 1024) { index -> (index % 251).toByte() }
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.startsWith("/downloads/") -> {
                    val current = inFlight.incrementAndGet()
                    maxInFlight.updateAndGet { previous -> maxOf(previous, current) }
                    delay(100)
                    inFlight.decrementAndGet()
                    respond(
                        content = payload,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentLength to listOf(payload.size.toString()),
                            HttpHeaders.ContentType to listOf("audio/mpeg"),
                        ),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val repo = catalogRepository(db, testHttpClient(engine))
        PlatformStorage().writeDownloadDirectory(temp.newFolder("parallel-downloads").toURI().toString())
        val tracks = (1..12).map { index ->
            Track(
                id = "plex:t-parallel-$index",
                title = "Parallel Song $index",
                artist = "Artist One",
                album = "Album One",
                durationMs = 1_000,
                streamUrl = "https://plex.example/stream/t$index.mp3",
                downloadUrl = "https://plex.example/downloads/t$index.mp3",
            )
        }

        val result = repo.downloadTracks(tracks)

        assertEquals(12, result.total)
        assertEquals(12, result.completed)
        assertEquals(0, result.failed)
        assertTrue(maxInFlight.get() > 4)
        assertTrue(maxInFlight.get() <= downloadParallelism())
    }

    @Test
    fun downloadTracksHandlesThousandSongQueueWithBoundedParallelism() = runTest {
        val payload = byteArrayOf(42)
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.startsWith("/downloads/") -> {
                    val current = inFlight.incrementAndGet()
                    maxInFlight.updateAndGet { previous -> maxOf(previous, current) }
                    inFlight.decrementAndGet()
                    respond(
                        content = payload,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentLength to listOf(payload.size.toString()),
                            HttpHeaders.ContentType to listOf("audio/mpeg"),
                        ),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val repo = catalogRepository(db, testHttpClient(engine))
        PlatformStorage().writeDownloadDirectory(temp.newFolder("thousand-downloads").toURI().toString())
        val tracks = (1..1_000).map { index ->
            Track(
                id = "plex:t-large-$index",
                title = "Large Queue Song $index",
                artist = "Artist One",
                album = "Album One",
                durationMs = 1_000,
                streamUrl = "https://plex.example/stream/t$index.mp3",
                downloadUrl = "https://plex.example/downloads/t$index.mp3",
            )
        }

        val result = repo.downloadTracks(tracks)

        assertEquals(1_000, result.total)
        assertEquals(1_000, result.completed)
        assertEquals(0, result.failed)
        assertEquals(1_000, repo.downloads.value.size)
        assertTrue(maxInFlight.get() <= downloadParallelism())
    }

    @Test
    fun downloadTracksKeepsWorkersBusyWhenOneTrackIsSlow() = runTest {
        val payload = ByteArray(32 * 1024) { index -> (index % 251).toByte() }
        val slowRequestStarted = CountDownLatch(1)
        val laterRequestStarted = CountDownLatch(1)
        val slowResponseFinished = AtomicBoolean(false)
        val parallelism = downloadParallelism()
        val laterTrackIndex = parallelism + 1
        val trackCount = parallelism + 2
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.startsWith("/downloads/") -> {
                    val index = request.url.encodedPath
                        .substringAfterLast("/t")
                        .substringBefore(".mp3")
                        .toInt()
                    when (index) {
                        1 -> {
                            slowRequestStarted.countDown()
                            Thread.sleep(1_500)
                            slowResponseFinished.set(true)
                        }
                        laterTrackIndex -> {
                            if (!slowResponseFinished.get()) {
                                laterRequestStarted.countDown()
                            }
                        }
                    }
                    respond(
                        content = payload,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentLength to listOf(payload.size.toString()),
                            HttpHeaders.ContentType to listOf("audio/mpeg"),
                        ),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val repo = catalogRepository(db, testHttpClient(engine))
        PlatformStorage().writeDownloadDirectory(temp.newFolder("continuous-downloads").toURI().toString())
        val tracks = (1..trackCount).map { index ->
            Track(
                id = "plex:t-continuous-$index",
                title = "Continuous Song $index",
                artist = "Artist One",
                album = "Album One",
                durationMs = 1_000,
                streamUrl = "https://plex.example/stream/t$index.mp3",
                downloadUrl = "https://plex.example/downloads/t$index.mp3",
            )
        }

        val result = async { repo.downloadTracks(tracks) }
        yield()
        assertTrue(slowRequestStarted.await(5, TimeUnit.SECONDS))

        assertTrue(laterRequestStarted.await(1, TimeUnit.SECONDS))
        assertFalse(slowResponseFinished.get())

        assertEquals(trackCount, result.await().completed)
    }

    @Test
    fun downloadProgressDoesNotRewriteCatalogDownloadsUntilBatchSettles() = runTest {
        val payload = ByteArray(32 * 1024) { index -> (index % 251).toByte() }
        val requestStarted = CompletableDeferred<Unit>()
        val allowResponse = CompletableDeferred<Unit>()
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/downloads/t1.mp3" -> {
                    requestStarted.complete(Unit)
                    allowResponse.await()
                    respond(
                        content = payload,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentLength to listOf(payload.size.toString()),
                            HttpHeaders.ContentType to listOf("audio/mpeg"),
                        ),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val repo = catalogRepository(db, testHttpClient(engine))
        PlatformStorage().writeDownloadDirectory(temp.newFolder("progress-downloads").toURI().toString())
        val track = Track(
            id = "plex:t-progress",
            title = "Progress Song",
            artist = "Artist One",
            album = "Album One",
            durationMs = 1_000,
            streamUrl = "https://plex.example/stream/t1.mp3",
            downloadUrl = "https://plex.example/downloads/t1.mp3",
        )

        val result = async { repo.downloadTracks(listOf(track)) }
        requestStarted.await()

        assertTrue(repo.downloads.value.any { it.trackId == track.id && it.state == DownloadState.Downloading })
        assertTrue(repo.catalog.value.downloads.isEmpty())

        allowResponse.complete(Unit)
        assertEquals(1, result.await().completed)
        assertEquals(DownloadState.Complete, repo.downloads.value.single().state)
        assertEquals(DownloadState.Complete, repo.catalog.value.downloads.single().state)
    }

    @Test
    fun persistedLocalUriReconcilesStaleFailedDownloadRow() = runTest {
        var downloadRequests = 0
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val localUri = temp.newFile("reconciled-download.mp3").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }.toURI().toString()
        val track = Track(
            id = "plex:t-reconciled-download",
            title = "Reconciled Download",
            artist = "Artist One",
            album = "Album One",
            durationMs = 1_000,
            streamUrl = "https://plex.example/stream/reconciled.mp3",
            downloadUrl = "https://plex.example/downloads/reconciled.mp3",
            localUri = localUri,
        )
        db.catalogQueries.upsertTrack(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            streamUrl = track.streamUrl,
            downloadUrl = track.downloadUrl,
            thumbUrl = null,
            localArtworkUri = null,
            localUri = track.localUri,
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
        db.downloadsQueries.upsert(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            dlState = DownloadState.Failed.name,
            progress = 0.0,
            localUri = null,
            downloadUrl = track.downloadUrl,
            targetPath = "downloads/${track.id}.mp3",
            downloadedBytes = 0L,
            totalBytes = null,
            updatedAtMs = 0L,
            batchId = null,
            error = "previous stale failure",
        )
        val repo = catalogRepository(
            db,
            testHttpClient(
                MockEngine {
                    downloadRequests++
                    respond("", HttpStatusCode.NotFound)
                },
            ),
        )
        repo.restoreCachedCatalog()

        val result = repo.downloadTracks(listOf(track.copy(localUri = null)))

        assertEquals(1, result.total)
        assertEquals(1, result.completed)
        assertEquals(0, result.failed)
        assertEquals(0, downloadRequests)
        val download = repo.downloads.value.single()
        assertEquals(DownloadState.Complete, download.state)
        assertEquals(localUri, download.localUri)
        val persisted = db.downloadsQueries.selectAll().awaitAsList().single()
        assertEquals(DownloadState.Complete.name, persisted.dlState)
        assertEquals(localUri, persisted.localUri)
    }

    @Test
    fun deleteDownloadsForTracksRemovesRequestedOfflineDownloadUriWithoutDownloadRow() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val repo = catalogRepository(db, testHttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
        val downloadedFile = temp.newFile("orphan-downloaded-song.mp3").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val track = Track(
            id = "plex:t-orphan-download",
            title = "Orphan Download",
            artist = "Artist One",
            album = "Album One",
            durationMs = 1_000,
            streamUrl = "https://plex.example/stream/orphan.mp3",
            downloadUrl = "https://plex.example/downloads/orphan.mp3",
            localUri = downloadedFile.toURI().toString(),
        )

        val deleted = repo.deleteDownloadsForTracks(listOf(track))

        assertEquals(1, deleted)
        assertFalse(downloadedFile.exists())
        assertTrue(repo.downloads.value.isEmpty())
    }

    @Test
    fun deleteDownloadsForTracksDoesNotDeleteLocalSourceFilesWithoutDownloadUrl() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val repo = catalogRepository(db, testHttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
        val localFile = temp.newFile("local-source-song.mp3").apply {
            writeBytes(byteArrayOf(4, 5, 6))
        }
        val track = Track(
            id = "local:t-source",
            title = "Local Source",
            artist = "Artist One",
            album = "Album One",
            durationMs = 1_000,
            streamUrl = localFile.toURI().toString(),
            downloadUrl = "",
            localUri = localFile.toURI().toString(),
        )

        val deleted = repo.deleteDownloadsForTracks(listOf(track))

        assertEquals(0, deleted)
        assertTrue(localFile.exists())
        assertTrue(repo.downloads.value.isEmpty())
    }

    @Test
    fun deleteDownloadsForTracksRemovesRemoteOfflineUriWithoutDownloadUrl() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val repo = catalogRepository(db, testHttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
        val downloadedFile = temp.newFile("remote-stale-download-url.mp3").apply {
            writeBytes(byteArrayOf(10, 11, 12))
        }
        val track = Track(
            id = "plex:t-stale-url",
            title = "Stale URL Download",
            artist = "Artist One",
            album = "Album One",
            durationMs = 1_000,
            streamUrl = "https://plex.example/stream/stale.mp3",
            downloadUrl = "",
            localUri = downloadedFile.toURI().toString(),
        )

        val deleted = repo.deleteDownloadsForTracks(listOf(track))

        assertEquals(1, deleted)
        assertFalse(downloadedFile.exists())
        assertTrue(repo.downloads.value.isEmpty())
    }

    @Test
    fun deleteDownloadsForTracksRemovesPersistedOfflineUriWhenMemoryTrackIsStale() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val repo = catalogRepository(db, testHttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
        val downloadedFile = temp.newFile("persisted-downloaded-song.mp3").apply {
            writeBytes(byteArrayOf(7, 8, 9))
        }
        db.catalogQueries.upsertTrack(
            id = "plex:t-persisted-download",
            title = "Persisted Download",
            artist = "Artist One",
            album = "Album One",
            durationMs = 1_000,
            streamUrl = "https://plex.example/stream/persisted.mp3",
            downloadUrl = "https://plex.example/downloads/persisted.mp3",
            thumbUrl = null,
            localArtworkUri = null,
            localUri = downloadedFile.toURI().toString(),
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
        val staleTrack = Track(
            id = "plex:t-persisted-download",
            title = "Persisted Download",
            artist = "Artist One",
            album = "Album One",
            durationMs = 1_000,
            streamUrl = "https://plex.example/stream/persisted.mp3",
            downloadUrl = "https://plex.example/downloads/persisted.mp3",
        )

        val deleted = repo.deleteDownloadsForTracks(listOf(staleTrack))

        assertEquals(1, deleted)
        assertFalse(downloadedFile.exists())
        assertEquals(null, db.catalogQueries.selectTrackById(staleTrack.id).awaitAsOneOrNull()?.localUri)
        assertTrue(repo.downloads.value.isEmpty())
    }

    @Test
    fun downloadFailsWhenResponseEndsBeforeDeclaredContentLength() = runTest {
        val payload = ByteArray(96 * 1024) { index -> (index % 199).toByte() }
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/downloads/t1.mp3" -> respond(
                    content = payload,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentLength to listOf((payload.size + 1).toString()),
                        HttpHeaders.ContentType to listOf("audio/mpeg"),
                    ),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val http = testHttpClient(engine)
        val repo = catalogRepository(db, http)
        PlatformStorage().writeDownloadDirectory(temp.newFolder("downloads-open-body").toURI().toString())
        val track = Track(
            id = "plex:t-open-body",
            title = "Short Body Song",
            artist = "Artist One",
            album = "Album One",
            durationMs = 1_000,
            streamUrl = "https://plex.example/stream/t1.mp3",
            downloadUrl = "https://plex.example/downloads/t1.mp3",
        )

        val result = repo.downloadTracks(listOf(track))

        assertEquals(1, result.total)
        assertEquals(0, result.completed)
        assertEquals(1, result.failed)
        val failureReason = "Content-Length mismatch: expected 98305 bytes, but received 98304 bytes"
        assertEquals(failureReason, result.failureReasons.single().reason)
        assertEquals(1, result.failureReasons.single().count)
        assertEquals(track.id, result.failedSamples.single().trackId)
        assertEquals(track.title, result.failedSamples.single().title)
        assertEquals("https://plex.example/downloads/t1.mp3", result.failedSamples.single().sourceUrl)
        val downloaded = repo.catalog.value.downloads.single()
        assertEquals(DownloadState.Failed, downloaded.state)
        assertEquals(failureReason, downloaded.error)
        assertEquals(null, downloaded.localUri)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.waitForPlaylistTrackIds(
        repo: CatalogRepository,
        playlistId: String,
        expected: List<String>,
    ): List<String> {
        val deadline = System.nanoTime() + 2_000_000_000L
        var actual = repo.catalog.value.tracksByParent[playlistId].orEmpty().map { it.id }
        while (actual != expected) {
            runCurrent()
            if (System.nanoTime() >= deadline) return actual
            Thread.sleep(1)
            actual = repo.catalog.value.tracksByParent[playlistId].orEmpty().map { it.id }
        }
        return actual
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun duplicatePlaylistTracksJson(playlistItemIds: List<Long>): String {
        val byItemId = mapOf(
            101L to """
              {
                "ratingKey": "t1",
                "playlistItemID": 101,
                "title": "Playlist Song One",
                "grandparentTitle": "Artist One",
                "parentTitle": "Album One",
                "duration": 1000,
                "Media": [
                  { "Part": [ { "key": "/library/parts/t1/file.mp3", "file": "one.mp3" } ] }
                ]
              }
            """.trimIndent(),
            102L to """
              {
                "ratingKey": "t2",
                "playlistItemID": 102,
                "title": "Playlist Song Two",
                "grandparentTitle": "Artist One",
                "parentTitle": "Album One",
                "duration": 2000,
                "Media": [
                  { "Part": [ { "key": "/library/parts/t2/file.mp3", "file": "two.mp3" } ] }
                ]
              }
            """.trimIndent(),
            103L to """
              {
                "ratingKey": "t1",
                "playlistItemID": 103,
                "title": "Playlist Song One",
                "grandparentTitle": "Artist One",
                "parentTitle": "Album One",
                "duration": 1000,
                "Media": [
                  { "Part": [ { "key": "/library/parts/t1/file.mp3", "file": "one.mp3" } ] }
                ]
              }
            """.trimIndent(),
        )
        val items = playlistItemIds.mapNotNull { byItemId[it] }.joinToString(",\n")
        return """
        {
          "MediaContainer": {
            "Metadata": [
              $items
            ]
          }
        }
    """.trimIndent()
    }

    private fun catalogRepository(db: com.phoebe.app.db.PhoebeDatabase, http: io.ktor.client.HttpClient): CatalogRepository {
        val mediaSources = MediaSourcesRepository(db, PlatformStorage())
        return testCatalogRepository(
            plexClient = PlexClient.withoutResolver(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = mediaSources,
        )
    }
}
