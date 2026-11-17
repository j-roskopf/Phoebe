package com.phoebe.app

import app.cash.sqldelight.db.SqlDriver
import com.phoebe.app.data.CatalogRepository
import com.phoebe.app.data.MediaSourcesRepository
import com.phoebe.app.data.MusicProviderRegistry
import com.phoebe.app.data.PlexClient
import com.phoebe.app.domain.Artist
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavidromeArtistRadioDesktopTest {
    private var driver: SqlDriver? = null

    @After
    fun tearDown() {
        driver?.close()
        driver = null
    }

    private val session = PlexSession(
        token = "secret",
        userName = "ada",
        selectedServer = PlexServer("navidrome:test", "Navidrome", "https://navidrome.example", owned = true),
        selectedLibrary = MusicLibrary("all", "All Music"),
        providerType = MediaProviderType.Navidrome,
    )

    @Test
    fun navidromeArtistsAdvertiseRadioStation() = runBlocking {
        val repository = repository(MockEngine { respond("", HttpStatusCode.NotFound) })

        assertNotNull(repository.artistRadioStation(session, Artist(id = "navidrome:ar1", title = "North Lake")))
        assertNull(
            repository.artistRadioStation(
                session.copy(providerType = MediaProviderType.MusicAssistant),
                Artist(id = "navidrome:ar1", title = "North Lake"),
            ),
        )
    }

    @Test
    fun playArtistRadioReturnsPrefixedSimilarSongs() = runBlocking {
        val requestedIds = mutableListOf<String?>()
        val repository = repository(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/rest/getSimilarSongs2.view" -> {
                        requestedIds += request.url.parameters["id"]
                        respondJson(songsResponse("similarSongs2", (1..10).map { "sim$it" }))
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )

        val tracks = repository.playArtistRadio(session, Artist(id = "navidrome:ar1", title = "North Lake"))

        assertEquals(listOf<String?>("ar1"), requestedIds)
        assertEquals(10, tracks.size)
        assertTrue(tracks.all { it.id.startsWith("navidrome:") })
        assertTrue(tracks.all { it.parentAlbumId?.startsWith("navidrome:") == true })
    }

    @Test
    fun syntheticArtistIdsAreResolvedByNameBeforeAskingForSimilarSongs() = runBlocking {
        val requestedIds = mutableListOf<String?>()
        val repository = repository(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/rest/search3.view" -> respondJson(
                        """
                        { "subsonic-response": { "status": "ok", "searchResult3": { "artist": [
                          { "id": "ar-real", "name": "Missing Artist" }
                        ] } } }
                        """.trimIndent(),
                    )
                    "/rest/getSimilarSongs2.view" -> {
                        requestedIds += request.url.parameters["id"]
                        respondJson(songsResponse("similarSongs2", (1..10).map { "sim$it" }))
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )

        val tracks = repository.playArtistRadio(
            session,
            Artist(id = "navidrome:artist:1a2b3c", title = "Missing Artist"),
        )

        assertEquals(listOf<String?>("ar-real"), requestedIds)
        assertEquals(10, tracks.size)
    }

    @Test
    fun keepPlayingSimilarTracksUseItemEndpointAndSkipOtherProviders() = runBlocking {
        val repository = repository(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/rest/getSimilarSongs.view" -> respondJson(songsResponse("similarSongs", listOf("sim1")))
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )

        assertEquals(
            listOf("navidrome:sim1"),
            repository.navidromeSimilarTracksForItem(session, "navidrome:tr1").map { it.id },
        )
        assertTrue(repository.navidromeSimilarTracksForItem(session, "plex:tr1").isEmpty())
    }

    private fun repository(engine: MockEngine): CatalogRepository {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val storage = PlatformStorage()
        return testCatalogRepository(
            plexClient = PlexClient.withoutResolver(testHttpClient(engine)),
            providerRegistry = MusicProviderRegistry(emptyList()),
            database = db,
            storage = storage,
            httpClient = testHttpClient(engine),
            mediaSourcesRepository = MediaSourcesRepository(db, storage),
        )
    }
}

private fun songsResponse(key: String, ids: List<String>): String {
    val songs = ids.joinToString(",") { id ->
        """{ "id": "$id", "title": "Song $id", "artist": "Artist", "album": "Album", "albumId": "al-$id", "duration": 200 }"""
    }
    return """{ "subsonic-response": { "status": "ok", "$key": { "song": [$songs] } } }"""
}

private fun MockRequestHandleScope.respondJson(content: String) = respond(
    content = content,
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)
