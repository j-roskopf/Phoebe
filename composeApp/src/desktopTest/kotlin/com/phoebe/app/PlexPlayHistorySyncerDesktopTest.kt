package com.phoebe.app

import app.cash.sqldelight.db.SqlDriver
import com.phoebe.app.data.CatalogRepository
import com.phoebe.app.data.MediaSourcesRepository
import com.phoebe.app.data.MusicProviderRegistry
import com.phoebe.app.data.PlayHistoryRepository
import com.phoebe.app.data.PlexClient
import com.phoebe.app.data.PlexPlayHistorySyncResult
import com.phoebe.app.data.PlexPlayHistorySyncer
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlexPlayHistorySyncerDesktopTest {
    private var driver: SqlDriver? = null
    private var repository: PlayHistoryRepository? = null

    @After
    fun tearDown() = runBlocking {
        repository?.closeAndJoin()
        repository = null
        driver?.close()
        driver = null
    }

    @Test
    fun syncImportsMatchingPlexTracksAndUsesIncrementalLookback() = runBlocking {
        val starts = mutableListOf<String?>()
        val viewedAtFilters = mutableListOf<String?>()
        var historyRequestCount = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/history/all") -> {
                    historyRequestCount += 1
                    starts += request.url.parameters["X-Plex-Container-Start"]
                    viewedAtFilters += request.url.parameters["viewedAt"]
                    respond(
                        content = when (historyRequestCount) {
                            1 -> historyJson(
                                offset = 0,
                                size = 2,
                                totalSize = 3,
                                metadata = """
                                    ${historyTrack("t1", "history-1", 1700000000)},
                                    ${historyTrack("missing", "history-missing", 1700000100)}
                                """.trimIndent(),
                            )
                            2 -> historyJson(
                                offset = 2,
                                size = 1,
                                totalSize = 3,
                                metadata = historyAlbum("t3", "history-album", 1700000200),
                            )
                            else -> historyJson(
                                offset = 0,
                                size = 1,
                                totalSize = 1,
                                metadata = historyTrack("t1", "history-1", 1700000000),
                            )
                        },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respond(
                    content = statsJson(metadata = ""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val syncer = newSyncer(engine, db, repo)
        val session = PlexSession(
            token = "token",
            selectedServer = PlexServer("server", "Plex", "https://plex.example:32400", owned = true),
            selectedLibrary = MusicLibrary("1", "Music"),
        )
        val catalog = CatalogSnapshot(
            tracksByParent = mapOf(
                "all" to listOf(
                    Track("plex:t1", "Song", "Artist", "Album", 1_000L, "stream", ""),
                    Track("local:t2", "Local", "Artist", "Album", 1_000L, "stream", ""),
                ),
            ),
        )

        val first = assertIs<PlexPlayHistorySyncResult.Synced>(syncer.sync(session, catalog))
        val second = assertIs<PlexPlayHistorySyncResult.Synced>(syncer.sync(session, catalog))

        assertEquals(1, first.imported)
        assertEquals(3, first.seen)
        assertEquals(0, second.imported)
        assertEquals(listOf<String?>("0", "2", "0"), starts)
        assertEquals(null, viewedAtFilters[0])
        assertEquals(null, viewedAtFilters[1])
        assertEquals("viewedAt>=1699999400", viewedAtFilters[2])
        val counts = repo.playCountsByTrack.first { it["plex:t1"] == 1L }
        assertEquals(1L, counts["plex:t1"])
    }

    @Test
    fun syncUsesPlexViewCountsForMostPlayedEvenWhenHistoryImportsFewEvents() = runBlocking {
        val capturedStatsQuery = mutableListOf<String>()
        val capturedPlayedOnlyFilters = mutableListOf<String?>()
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/history/all") -> respond(
                    content = historyJson(
                        offset = 0,
                        size = 1,
                        totalSize = 1,
                        metadata = historyTrack("t1", "history-1", 1700000000),
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> {
                    capturedStatsQuery += request.url.encodedQuery
                    capturedPlayedOnlyFilters += request.url.parameters["viewCount>="]
                    respond(
                        content = statsJson(
                            metadata = statsTrack(
                                ratingKey = "t1",
                                viewCount = 42,
                                lastViewedAt = 1700000000,
                            ),
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val result = assertIs<PlexPlayHistorySyncResult.Synced>(
            newSyncer(engine, db, repo).sync(testSession(), testCatalog()),
        )

        assertEquals(2, result.imported)
        assertTrue(capturedStatsQuery.single().contains("includeUserState=1"))
        assertTrue(capturedStatsQuery.single().contains("sort=viewCount%3Adesc"))
        assertEquals(listOf<String?>("1"), capturedPlayedOnlyFilters)
        val counts = repo.playCountsByTrack.first { it["plex:t1"] == 42L }
        assertEquals(42L, counts["plex:t1"])
        val top = repo.topMostPlayed.first { list -> list.any { it.trackId == "plex:t1" && it.playCount == 42L } }
        assertEquals(42L, top.first { it.trackId == "plex:t1" }.playCount)
    }

    @Test
    fun syncKeepsPlexViewCountsWhenHistoryEndpointFails() = runBlocking {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/history/all") -> respond(
                    content = """{"error":"history unavailable"}""",
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond(
                    content = statsJson(
                        metadata = statsTrack(
                            ratingKey = "t1",
                            viewCount = 42,
                            lastViewedAt = 1700000000,
                        ),
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo

        val result = assertIs<PlexPlayHistorySyncResult.Synced>(
            newSyncer(engine, db, repo).sync(testSession(), testCatalog()),
        )

        assertEquals(1, result.imported)
        val top = repo.topMostPlayed.first { list -> list.any { it.trackId == "plex:t1" } }
        assertEquals(42L, top.first { it.trackId == "plex:t1" }.playCount)
    }

    @Test
    fun syncUsesPlexViewCountsWhenTrackIsNotInCatalogYet() = runBlocking {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/history/all") -> respond(
                    content = historyJson(offset = 0, size = 0, totalSize = 0, metadata = ""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond(
                    content = statsJson(
                        metadata = statsTrack(
                            ratingKey = "t9",
                            viewCount = 24,
                            lastViewedAt = 1700000000,
                        ),
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val catalogRepository = newCatalogRepository(engine, db)
        val syncer = PlexPlayHistorySyncer(PlexClient(testHttpClient(engine)), repo, catalogRepository)

        val result = assertIs<PlexPlayHistorySyncResult.Synced>(
            syncer.sync(testSession(), CatalogSnapshot()),
        )

        assertEquals(1, result.imported)
        val counts = repo.playCountsByTrack.first { it["plex:t9"] == 24L }
        assertEquals(24L, counts["plex:t9"])
        val top = repo.topMostPlayed.first { list -> list.any { it.trackId == "plex:t9" } }
        assertEquals(24L, top.first { it.trackId == "plex:t9" }.playCount)
    }

    @Test
    fun syncResolvesHistoryTracksFromCachedCatalogWhenSnapshotIsEmpty() = runBlocking {
        var historyRequests = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/history/all") -> {
                    historyRequests += 1
                    respond(
                        content = historyJson(
                            offset = 0,
                            size = 1,
                            totalSize = 1,
                            metadata = historyTrack("t9", "history-9", 1700000500),
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respond(
                    content = statsJson(metadata = ""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        db.catalogQueries.upsertTrack(
            id = "plex:t9",
            title = "Cached Song",
            artist = "Cached Artist",
            album = "Cached Album",
            durationMs = 180_000L,
            streamUrl = "https://plex.example/track/t9",
            downloadUrl = "https://plex.example/track/t9/download",
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
        val repo = PlayHistoryRepository(db)
        repository = repo
        val result = assertIs<PlexPlayHistorySyncResult.Synced>(
            newSyncer(engine, db, repo).sync(testSession(), CatalogSnapshot()),
        )

        assertEquals(1, historyRequests)
        assertEquals(1, result.imported)
        assertEquals(1, result.seen)
        val counts = repo.playCountsByTrack.first { it["plex:t9"] == 1L }
        assertEquals(1L, counts["plex:t9"])
        val recent = repo.topRecentlyPlayed.first { list -> list.any { it.trackId == "plex:t9" } }
        assertEquals("Cached Artist", recent.first { it.trackId == "plex:t9" }.artist)
    }

    @Test
    fun syncRecentFetchesFirstStatsPagesAndRecentHistoryPage() = runBlocking {
        val statsStarts = mutableListOf<String?>()
        val statsSizes = mutableListOf<String?>()
        val encodedQueries = mutableListOf<String>()
        val contentDirectoryIds = mutableListOf<String?>()
        var historyRequests = 0
        val historySizes = mutableListOf<String?>()
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/identity") -> respond(
                    content = "{}",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.url.encodedPath.endsWith("/history/all") -> {
                    historyRequests += 1
                    historySizes += request.url.parameters["X-Plex-Container-Size"]
                    respond(
                        content = historyJson(
                            offset = 0,
                            size = 1,
                            totalSize = 1,
                            metadata = historyTrack("history", "recent-history", 1700000200),
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> {
                    val encodedQuery = request.url.encodedQuery
                    statsStarts += request.url.parameters["X-Plex-Container-Start"]
                    statsSizes += request.url.parameters["X-Plex-Container-Size"]
                    encodedQueries += encodedQuery
                    contentDirectoryIds += request.url.parameters["contentDirectoryID"]
                    val isRecentQuery = encodedQuery.contains("sort=lastViewedAt%3Adesc")
                    respond(
                        content = statsJson(
                            metadata = statsTrack(
                                ratingKey = if (isRecentQuery) "t1" else "top",
                                viewCount = if (isRecentQuery) 3 else 15,
                                lastViewedAt = if (isRecentQuery) 1700000000 else 1700000100,
                            ),
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo

        val result = assertIs<PlexPlayHistorySyncResult.Synced>(
            newSyncer(engine, db, repo).syncRecent(testSession(), testCatalog()),
        )

        assertEquals(3, result.seen)
        assertEquals(1, historyRequests)
        assertEquals(listOf<String?>("${PlexPlayHistorySyncer.RecentHistoryPageSize}"), historySizes)
        assertEquals(listOf<String?>("0", "0"), statsStarts)
        assertEquals(
            listOf<String?>(
                "${PlexPlayHistorySyncer.StartupMostPlayedStatsPageSize}",
                "${PlexPlayHistorySyncer.RecentStatsPageSize}",
            ),
            statsSizes,
        )
        val mostPlayedQuery = encodedQueries.first { it.contains("sort=viewCount%3Adesc") }
        val recentQuery = encodedQueries.first { it.contains("sort=lastViewedAt%3Adesc") }
        assertTrue(mostPlayedQuery.contains("viewCount%3E%3D=1"))
        assertTrue(mostPlayedQuery.contains("includeUserState=1"))
        assertTrue(recentQuery.contains("viewCount%3E%3D=1"))
        assertFalse(recentQuery.contains("includeUserState"))
        assertEquals(listOf<String?>(null, "1"), contentDirectoryIds)
        val mostPlayed = repo.topMostPlayed.first { list -> list.any { it.trackId == "plex:top" && it.playCount == 15L } }
        assertEquals(15L, mostPlayed.first { it.trackId == "plex:top" }.playCount)
        val recent = repo.topRecentlyPlayed.first { list -> list.any { it.trackId == "plex:t1" } }
        assertEquals(1700000000L * 1000L, recent.first { it.trackId == "plex:t1" }.lastPlayedMs)
        val history = repo.topRecentlyPlayed.first { list -> list.any { it.trackId == "plex:history" } }
        assertEquals(1700000200L * 1000L, history.first { it.trackId == "plex:history" }.lastPlayedMs)
    }

    @Test
    fun syncRecentDoesNotBlockWhenHistoryEndpointWouldHang() = runBlocking {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/history/all") -> awaitCancellation()
                else -> respond(
                    content = statsJson(
                        metadata = statsTrack(
                            ratingKey = "t1",
                            viewCount = 5,
                            lastViewedAt = 1700000300,
                        ),
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo

        val result = withTimeout(5_000L) {
            newSyncer(engine, db, repo).syncRecent(testSession(), testCatalog())
        }

        assertIs<PlexPlayHistorySyncResult.Synced>(result)
        val recent = repo.topRecentlyPlayed.first { list -> list.any { it.trackId == "plex:t1" } }
        assertEquals(1700000300L * 1000L, recent.first { it.trackId == "plex:t1" }.lastPlayedMs)
    }

    @Test
    fun syncStopsAfterMaximumHistoryPages() = runBlocking {
        var historyRequestCount = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/history/all") -> {
                    historyRequestCount += 1
                    val start = request.url.parameters["X-Plex-Container-Start"]?.toIntOrNull() ?: 0
                    respond(
                        content = historyJson(
                            offset = start,
                            size = PlexPlayHistorySyncer.PageSize,
                            totalSize = Int.MAX_VALUE,
                            metadata = historyTrack(
                                ratingKey = "missing",
                                historyKey = "history-$start",
                                viewedAt = 1700000000L - start,
                            ),
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respond(
                    content = statsJson(metadata = ""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val result = assertIs<PlexPlayHistorySyncResult.Synced>(
            newSyncer(engine, db, repo).sync(testSession(), testCatalog()),
        )

        assertEquals(PlexPlayHistorySyncer.MaxPages, historyRequestCount)
        assertEquals(PlexPlayHistorySyncer.MaxPages, result.seen)
        assertEquals(0, result.imported)
    }

    @Test
    fun syncJobCanBeCancelledWhenHistoryRequestIsStuck() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val engine = MockEngine {
            requestStarted.complete(Unit)
            awaitCancellation()
        }
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val job = launch {
            newSyncer(engine, db, repo).sync(testSession(), testCatalog())
        }

        requestStarted.await()
        job.cancel()
        withTimeout(1_000L) { job.join() }
    }

    private fun newSyncer(engine: MockEngine, db: PhoebeDatabase, repo: PlayHistoryRepository): PlexPlayHistorySyncer =
        PlexPlayHistorySyncer(
            plexClient = PlexClient(testHttpClient(engine)),
            playHistoryRepository = repo,
            catalogRepository = newCatalogRepository(engine, db),
        )

    private fun newCatalogRepository(engine: MockEngine, db: PhoebeDatabase): CatalogRepository {
        val storage = PlatformStorage()
        return testCatalogRepository(
            plexClient = PlexClient(testHttpClient(engine)),
            providerRegistry = MusicProviderRegistry(emptyList()),
            database = db,
            storage = storage,
            httpClient = testHttpClient(engine),
            mediaSourcesRepository = MediaSourcesRepository(db, storage),
        )
    }

    private fun testSession(): PlexSession = PlexSession(
        token = "token",
        selectedServer = PlexServer("server", "Plex", "https://plex.example:32400", owned = true),
        selectedLibrary = MusicLibrary("1", "Music"),
    )

    private fun testCatalog(): CatalogSnapshot = CatalogSnapshot(
        tracksByParent = mapOf(
            "all" to listOf(Track("plex:t1", "Song", "Artist", "Album", 1_000L, "stream", "")),
        ),
    )

    private fun historyJson(offset: Int, size: Int, totalSize: Int, metadata: String): String = """
        {
          "MediaContainer": {
            "offset": $offset,
            "size": $size,
            "totalSize": $totalSize,
            "Metadata": [
              $metadata
            ]
          }
        }
    """.trimIndent()

    private fun statsJson(metadata: String): String = """
        {
          "MediaContainer": {
            "offset": 0,
            "size": 0,
            "totalSize": 0,
            "Metadata": [
              $metadata
            ]
          }
        }
    """.trimIndent()

    private fun historyTrack(ratingKey: String, historyKey: String, viewedAt: Long): String = """
        {
          "ratingKey": "$ratingKey",
          "historyKey": "$historyKey",
          "type": "track",
          "librarySectionID": "1",
          "title": "Song",
          "parentTitle": "Album",
          "grandparentTitle": "Artist",
          "viewedAt": $viewedAt
        }
    """.trimIndent()

    private fun statsTrack(ratingKey: String, viewCount: Int, lastViewedAt: Long): String = """
        {
          "ratingKey": "$ratingKey",
          "type": "track",
          "title": "Night Signals",
          "parentTitle": "Radio House",
          "grandparentTitle": "North Lake",
          "viewCount": $viewCount,
          "lastViewedAt": $lastViewedAt
        }
    """.trimIndent()

    private fun historyAlbum(ratingKey: String, historyKey: String, viewedAt: Long): String = """
        {
          "ratingKey": "$ratingKey",
          "historyKey": "$historyKey",
          "type": "album",
          "librarySectionID": "1",
          "title": "Album",
          "viewedAt": $viewedAt
        }
    """.trimIndent()
}
