package com.phoebe.app

import com.phoebe.app.data.EmbyClient
import com.phoebe.app.data.JellyfinClient
import com.phoebe.app.data.JellyfinPlaybackEvent
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmbyClientTest {
    @Test
    fun authenticateUsesEmbyBasePathAndCatalogPrefix() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/emby/Users/AuthenticateByName" -> respondJson(
                    """{ "User": { "Id": "user-1", "Name": "Ada" }, "AccessToken": "emby-token" }""",
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = EmbyClient(testHttpClient(engine))

        val auth = client.authenticate("https://emby.example", "ada", "secret")

        assertEquals("emby-token", auth.token)
        assertTrue(auth.server.id.startsWith("emby:"))
        assertEquals("https://emby.example/emby", auth.server.uri)
        assertEquals(listOf("/emby/Users/AuthenticateByName"), paths)
    }

    @Test
    fun favoritesAndRatingsUseUserScopedEmbyEndpoints() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            respondJson("{}")
        }
        val client = EmbyClient(testHttpClient(engine))
        val server = com.phoebe.app.domain.PlexServer("emby:test", "Emby", "https://emby.example/emby", owned = true)

        client.setFavorite(server, "token", "user-1", "item-1", favorite = true)
        client.rateItem(server, "token", "user-1", "item-1", 4f)
        client.reportPlayback(server, "token", "item-1", positionMs = 1_000, isPaused = false, event = JellyfinPlaybackEvent.Progress)

        assertEquals(
            listOf(
                "/emby/Users/user-1/FavoriteItems/item-1",
                "/emby/Users/user-1/Items/item-1/Rating",
                "/emby/Sessions/Playing/Progress",
            ),
            paths,
        )
    }

    @Test
    fun loadsLibrariesFromEmbyUserViewsEndpoint() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/emby/Users/user-1/Views" -> respondJson(
                    """
                    {
                      "Items": [
                        { "Id": "movies", "Name": "Movies", "CollectionType": "movies" },
                        { "Id": "music", "Name": "Music", "CollectionType": "Music" }
                      ]
                    }
                    """.trimIndent(),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = EmbyClient(testHttpClient(engine))
        val server = com.phoebe.app.domain.PlexServer("emby:test", "Emby", "https://emby.example/emby", owned = true)

        val libraries = client.libraries(server, "token", "user-1")

        assertEquals(listOf(MusicLibrary("music", "Music")), libraries)
        assertEquals(listOf("/emby/Users/user-1/Views"), paths)
    }

    @Test
    fun unauthorizedEmbySignInReturnsReadableError() = runTest {
        val engine = MockEngine {
            respond(
                content = "Invalid username or password",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val client = EmbyClient(testHttpClient(engine))

        val error = assertFailsWith<IllegalStateException> {
            client.authenticate("https://emby.example", "ada", "wrong")
        }

        assertEquals(
            "Emby sign-in failed: unauthorized. Check the server URL, username, and password.",
            error.message,
        )
    }

    @Test
    fun embyQuickCatalogPagesUseOneHundredItemLimit() = runTest {
        val starts = mutableListOf<String?>()
        val limits = mutableListOf<String?>()
        val engine = MockEngine { request ->
            starts += request.url.parameters["startIndex"]
            limits += request.url.parameters["limit"]
            when (request.url.encodedPath) {
                "/emby/Items" -> respondJson(
                    """
                    {
                      "Items": [
                        { "Id": "track-1001", "Type": "Audio", "Name": "Track 1001", "Album": "Album", "RunTimeTicks": 10000000 }
                      ],
                      "TotalRecordCount": 1500
                    }
                    """.trimIndent(),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = EmbyClient(testHttpClient(engine))
        val server = com.phoebe.app.domain.PlexServer("emby:test", "Emby", "https://emby.example/emby", owned = true)

        val page = client.trackPage(server, MusicLibrary("music", "Music"), "token", "user-1", pageIndex = 1)

        assertEquals(JellyfinClient.QuickCatalogPageSize, page.pageSize)
        assertEquals(listOf<String?>(JellyfinClient.QuickCatalogPageSize.toString()), starts)
        assertEquals(listOf<String?>(JellyfinClient.QuickCatalogPageSize.toString()), limits)
    }

    @Test
    fun embyFullCatalogSyncUsesOneThousandItemLimit() = runTest {
        val starts = mutableListOf<String?>()
        val limits = mutableListOf<String?>()
        val engine = MockEngine { request ->
            starts += request.url.parameters["startIndex"]
            limits += request.url.parameters["limit"]
            when (request.url.encodedPath) {
                "/emby/Items" -> respondJson(
                    """
                    {
                      "Items": [
                        { "Id": "track-1", "Type": "Audio", "Name": "Track 1", "Album": "Album", "RunTimeTicks": 10000000 }
                      ],
                      "TotalRecordCount": 1
                    }
                    """.trimIndent(),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = EmbyClient(testHttpClient(engine))
        val server = com.phoebe.app.domain.PlexServer("emby:test", "Emby", "https://emby.example/emby", owned = true)

        val tracks = client.tracks(server, MusicLibrary("music", "Music"), "token", "user-1")

        assertEquals(listOf("track-1"), tracks.map { it.id })
        assertEquals(listOf<String?>("0"), starts)
        assertEquals(listOf<String?>(JellyfinClient.EmbyPageSize.toString()), limits)
    }

    @Test
    fun embyPrimaryImageTagsAreMappedToArtworkUrls() = runTest {
        val seenFields = mutableListOf<String?>()
        val engine = MockEngine { request ->
            seenFields += request.url.parameters["fields"]
            when (request.url.encodedPath) {
                "/emby/Artists/AlbumArtists" -> respondJson(
                    """
                    {
                      "Items": [
                        {
                          "Id": "artist-1",
                          "Type": "MusicArtist",
                          "Name": "North Lake",
                          "PrimaryImageTag": "artist-tag",
                          "UserData": { "IsFavorite": true }
                        }
                      ],
                      "TotalRecordCount": 1
                    }
                    """.trimIndent(),
                )
                "/emby/Items" -> respondJson(
                    """
                    {
                      "Items": [
                        {
                          "Id": "track-1",
                          "Type": "Audio",
                          "Name": "Night Signals",
                          "Album": "Radio House",
                          "AlbumId": "album-1",
                          "AlbumPrimaryImageTag": "album-tag",
                          "Artists": ["North Lake"],
                          "RunTimeTicks": 2450000000
                        }
                      ],
                      "TotalRecordCount": 1
                    }
                    """.trimIndent(),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = EmbyClient(testHttpClient(engine))
        val server = com.phoebe.app.domain.PlexServer("emby:test", "Emby", "https://emby.example/emby", owned = true)
        val library = MusicLibrary("music", "Music")

        val artist = client.artists(server, library, "token", "user-1").single()
        val track = client.tracks(server, library, "token", "user-1").single()

        assertTrue(artist.favorite)
        assertEquals("https://emby.example/emby/Items/artist-1/Images/Primary?tag=artist-tag&api_key=token", artist.thumbUrl)
        assertEquals("https://emby.example/emby/Items/album-1/Images/Primary?tag=album-tag&api_key=token", track.thumbUrl)
        assertTrue(seenFields.filterNotNull().any { it.contains("PrimaryImageTag") })
        assertTrue(seenFields.filterNotNull().any { it.contains("AlbumPrimaryImageTag") })
    }
}

private fun MockRequestHandleScope.respondJson(content: String) = respond(
    content = content,
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)
