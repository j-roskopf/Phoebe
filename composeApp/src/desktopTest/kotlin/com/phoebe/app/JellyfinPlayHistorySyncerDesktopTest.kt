package com.phoebe.app

import app.cash.sqldelight.db.SqlDriver
import com.phoebe.app.data.CatalogRepository
import com.phoebe.app.data.EmbyClient
import com.phoebe.app.data.JellyfinClient
import com.phoebe.app.data.JellyfinPlayHistorySyncResult
import com.phoebe.app.data.JellyfinPlayHistorySyncer
import com.phoebe.app.data.MediaSourcesRepository
import com.phoebe.app.data.MusicProviderRegistry
import com.phoebe.app.data.PlayHistoryRepository
import com.phoebe.app.data.PlexClient
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
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
import kotlin.test.assertTrue

class JellyfinPlayHistorySyncerDesktopTest {
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
    fun syncImportsEmbyUserDataPlayCounts() = runBlocking {
        val starts = mutableListOf<String?>()
        val limits = mutableListOf<String?>()
        val filters = mutableListOf<String?>()
        val isPlayed = mutableListOf<String?>()
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            starts += request.url.parameters["startIndex"]
            limits += request.url.parameters["limit"]
            filters += request.url.parameters["Filters"]
            isPlayed += request.url.parameters["IsPlayed"]
            val trackJson = """
                {
                  "Id": "track-1",
                  "Type": "Audio",
                  "Name": "Night Signals",
                  "UserData": {
                    "PlayCount": 2,
                    "LastPlayedDate": "2026-05-17T20:30:00.0000000Z"
                  }
                }
            """.trimIndent()
            val missingJson = """
                {
                  "Id": "missing",
                  "Type": "Audio",
                  "Name": "Missing",
                  "UserData": {
                    "PlayCount": 4,
                    "LastPlayedDate": "2026-05-17T20:31:00.0000000Z"
                  }
                }
            """.trimIndent()
            val content = if (request.url.encodedPath.endsWith("/Items/Latest")) {
                "[$trackJson, $missingJson]"
            } else {
                """
                    {
                      "Items": [
                        $trackJson,
                        $missingJson
                      ],
                      "TotalRecordCount": 2
                    }
                """.trimIndent()
            }
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
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
        val syncer = JellyfinPlayHistorySyncer(
            jellyfinClient = JellyfinClient(testHttpClient(engine)),
            embyClient = EmbyClient(testHttpClient(engine)),
            playHistoryRepository = repo,
            catalogRepository = catalogRepository,
        )

        val first = assertIs<JellyfinPlayHistorySyncResult.Synced>(syncer.sync(embySession(), embyCatalog()))
        val second = assertIs<JellyfinPlayHistorySyncResult.Synced>(syncer.sync(embySession(), embyCatalog()))

        assertEquals(6, first.seen)
        assertEquals(8, first.imported)
        assertEquals(6, second.imported)
        assertTrue(paths.any { it.endsWith("/Items/Latest") })
        assertTrue(paths.any { it.endsWith("/Items/Resume") })
        assertTrue(paths.any { it.endsWith("/Items") })
        assertTrue(isPlayed.any { it == "true" })
        assertTrue(filters.filterNotNull().isEmpty())
        val counts = repo.playCountsByTrack.first { it["emby:track-1"] == 2L }
        assertEquals(2L, counts["emby:track-1"])
        val topMost = repo.topMostPlayed.first { it.any { entry -> entry.trackId == "emby:track-1" } }
        assertEquals("emby:track-1", topMost.first { it.trackId == "emby:track-1" }.trackId)
        val topRecent = repo.topRecentlyPlayed.first { it.any { entry -> entry.trackId == "emby:track-1" } }
        assertEquals("emby:track-1", topRecent.first { it.trackId == "emby:track-1" }.trackId)
    }

    private fun embySession(): PlexSession = PlexSession(
        token = "token",
        selectedServer = PlexServer("emby-server", "Emby", "https://emby.example/emby", owned = true),
        selectedLibrary = MusicLibrary("music", "Music"),
        providerType = MediaProviderType.Emby,
        userId = "user-1",
    )

    private fun embyCatalog(): CatalogSnapshot = CatalogSnapshot(
        tracksByParent = mapOf(
            "emby:album-1" to listOf(Track("emby:track-1", "Night Signals", "North Lake", "Radio House", 1_000L, "stream", "")),
        ),
    )
}
