package com.phoebe.app

import app.cash.sqldelight.db.SqlDriver
import com.phoebe.app.data.CatalogRepository
import com.phoebe.app.data.MediaSourcesRepository
import com.phoebe.app.data.MusicProviderRegistry
import com.phoebe.app.data.NavidromePlayHistorySyncResult
import com.phoebe.app.data.NavidromePlayHistorySyncer
import com.phoebe.app.data.PlayHistoryRepository
import com.phoebe.app.data.PlexClient
import com.phoebe.app.data.SubsonicClient
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.JellyfinSyncMode
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NavidromePlayHistorySyncerDesktopTest {
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
    fun syncImportsSubsonicPlayCountsAndRecentPlays() = runBlocking {
        val albumListTypes = mutableListOf<String>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/rest/getAlbumList2.view" -> {
                    albumListTypes += request.url.parameters["type"].orEmpty()
                    respondJson(
                        """
                        { "subsonic-response": { "status": "ok", "albumList2": { "album": [
                          { "id": "al1", "name": "Radio House", "artist": "North Lake", "songCount": 1 }
                        ] } } }
                        """.trimIndent(),
                    )
                }
                "/rest/getAlbum.view" -> respondJson(
                    """
                    { "subsonic-response": { "status": "ok", "album": {
                      "id": "al1", "name": "Radio House", "artist": "North Lake", "song": [
                        {
                          "id": "tr1",
                          "title": "Night Signals",
                          "album": "Radio House",
                          "albumId": "al1",
                          "artist": "North Lake",
                          "duration": 245,
                          "playCount": 4,
                          "played": "2026-05-17T20:30:00Z"
                        }
                      ]
                    } } }
                    """.trimIndent(),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val storage = PlatformStorage()
        val mediaSources = MediaSourcesRepository(db, storage)
        val catalogRepository = testCatalogRepository(
            plexClient = PlexClient.withoutResolver(testHttpClient(engine)),
            providerRegistry = MusicProviderRegistry(emptyList()),
            database = db,
            storage = storage,
            httpClient = testHttpClient(engine),
            mediaSourcesRepository = mediaSources,
        )
        val syncer = NavidromePlayHistorySyncer(
            subsonicClient = SubsonicClient(testHttpClient(engine)),
            playHistoryRepository = repo,
            catalogRepository = catalogRepository,
        )
        val session = PlexSession(
            token = "secret",
            userName = "ada",
            selectedServer = PlexServer("navidrome:test", "Navidrome", "https://navidrome.example", owned = true),
            selectedLibrary = MusicLibrary("all", "All Music"),
            providerType = MediaProviderType.Navidrome,
            jellyfinSyncMode = JellyfinSyncMode.Quick,
        )

        val result = syncer.sync(session, CatalogSnapshot())

        assertIs<NavidromePlayHistorySyncResult.Synced>(result)
        assertEquals(2, result.imported)
        assertEquals(1, result.seen)
        assertEquals(listOf("frequent", "recent"), albumListTypes)
        val counts = repo.playCountsByTrack.first { it["navidrome:tr1"] == 4L }
        assertEquals(4L, counts["navidrome:tr1"])
        val topMost = repo.topMostPlayed.first { it.any { entry -> entry.trackId == "navidrome:tr1" } }
        assertEquals(1, topMost.size)
        assertEquals("navidrome:tr1", topMost.single().trackId)
        val topRecent = repo.topRecentlyPlayed.first { it.any { entry -> entry.trackId == "navidrome:tr1" } }
        assertEquals(1, topRecent.size)
        assertEquals("navidrome:tr1", topRecent.single().trackId)
    }
}

private fun MockRequestHandleScope.respondJson(content: String) = respond(
    content = content,
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)
