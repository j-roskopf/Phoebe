package com.phoebe.app

import app.cash.sqldelight.db.SqlDriver
import com.phoebe.app.data.PlayHistoryRepository
import com.phoebe.app.data.PlexClient
import com.phoebe.app.data.PlexPlayHistorySyncResult
import com.phoebe.app.data.PlexPlayHistorySyncer
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.Track
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
import kotlin.test.assertIs

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
        var requestCount = 0
        val engine = MockEngine { request ->
            requestCount += 1
            starts += request.url.parameters["X-Plex-Container-Start"]
            viewedAtFilters += request.url.parameters["viewedAt"]
            respond(
                content = when {
                    requestCount == 1 -> historyJson(
                        offset = 0,
                        size = 2,
                        totalSize = 3,
                        metadata = """
                            ${historyTrack("t1", "history-1", 1700000000)},
                            ${historyTrack("missing", "history-missing", 1700000100)}
                        """.trimIndent(),
                    )
                    requestCount == 2 -> historyJson(
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
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val syncer = PlexPlayHistorySyncer(PlexClient(testHttpClient(engine)), repo)
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

        assertEquals(2, first.imported)
        assertEquals(3, first.seen)
        assertEquals(0, second.imported)
        assertEquals(listOf<String?>("0", "2", "0"), starts)
        assertEquals(null, viewedAtFilters[0])
        assertEquals(null, viewedAtFilters[1])
        assertEquals("viewedAt>=1699999500", viewedAtFilters[2])
        val counts = repo.playCountsByTrack.first { it["plex:t1"] == 1L && it["plex:missing"] == 1L }
        assertEquals(1L, counts["plex:t1"])
        assertEquals(1L, counts["plex:missing"])
    }

    @Test
    fun syncStopsAfterMaximumHistoryPages() = runBlocking {
        var requestCount = 0
        val engine = MockEngine { request ->
            requestCount += 1
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
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val result = assertIs<PlexPlayHistorySyncResult.Synced>(
            PlexPlayHistorySyncer(PlexClient(testHttpClient(engine)), repo).sync(testSession(), testCatalog()),
        )

        assertEquals(PlexPlayHistorySyncer.MaxPages, requestCount)
        assertEquals(PlexPlayHistorySyncer.MaxPages, result.seen)
        assertEquals(PlexPlayHistorySyncer.MaxPages, result.imported)
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
            PlexPlayHistorySyncer(PlexClient(testHttpClient(engine)), repo).sync(testSession(), testCatalog())
        }

        requestStarted.await()
        job.cancel()
        withTimeout(1_000L) { job.join() }
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
