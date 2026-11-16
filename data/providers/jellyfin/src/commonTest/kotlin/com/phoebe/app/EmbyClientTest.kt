package com.phoebe.app

import com.phoebe.app.data.artistImageUrl
import com.phoebe.app.data.EmbyClient
import com.phoebe.app.data.JellyfinClient
import com.phoebe.app.data.JellyfinPlaybackEvent
import com.phoebe.app.data.itemImageUrl
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
    fun keepsLegacyEmbyAuthorizationHeaders() = runTest {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/emby/Users/AuthenticateByName" -> respondJson(
                    """{ "User": { "Id": "user-1", "Name": "Ada" }, "AccessToken": "emby-token" }""",
                )
                "/emby/Users/user-1/Views" -> respondJson("""{ "Items": [] }""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = EmbyClient(testHttpClient(engine))

        val auth = client.authenticate("https://emby.example", "ada", "secret")
        client.libraries(auth.server, auth.token, auth.userId)

        val (signIn, libraries) = requests
        assertTrue(signIn.headers["X-Emby-Authorization"].orEmpty().startsWith("MediaBrowser Client=\"Phoebe\""))
        assertEquals(null, signIn.headers["X-Emby-Token"])
        assertEquals("emby-token", libraries.headers["X-Emby-Token"])
        requests.forEach { request -> assertEquals(null, request.headers[HttpHeaders.Authorization]) }
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
        client.markPlayed(server, "token", "user-1", "item-1")

        assertEquals(
            listOf(
                "/emby/Users/user-1/FavoriteItems/item-1",
                "/emby/Users/user-1/Items/item-1/Rating",
                "/emby/Sessions/Playing/Progress",
                "/emby/Users/user-1/PlayedItems/item-1",
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
    fun embyPagedCatalogRequestsUseOneThousandItemLimit() = runTest {
        val starts = mutableListOf<String?>()
        val limits = mutableListOf<String?>()
        val engine = MockEngine { request ->
            starts += request.url.parameters["startIndex"]
            limits += request.url.parameters["limit"]
            when {
                request.url.encodedPath.endsWith("/Items") -> respondJson(
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

        assertEquals(JellyfinClient.EmbyPageSize, page.pageSize)
        assertEquals(listOf<String?>(JellyfinClient.EmbyPageSize.toString()), starts)
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
                "/emby/Items", "/emby/Users/user-1/Items" -> respondJson(
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
        assertEquals("https://emby.example/emby/Artists/North%20Lake/Images/Primary?tag=artist-tag&api_key=token", artist.thumbUrl)
        assertEquals("https://emby.example/emby/Items/album-1/Images/Primary?tag=album-tag&api_key=token", track.thumbUrl)
        assertTrue(seenFields.filterNotNull().any { it.contains("PrimaryImageTag") })
        assertTrue(seenFields.filterNotNull().any { it.contains("AlbumPrimaryImageTag") })
    }

    @Test
    fun embyPlaybackStatsUseUserScopedItemsPath() = runTest {
        val paths = mutableListOf<String>()
        val queryKeys = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            queryKeys += request.url.parameters.names()
            respondJson(
                """
                {
                  "Items": [
                    {
                      "Id": "track-1",
                      "Type": "Audio",
                      "Name": "Clean Up Song",
                      "Album": "Album",
                      "AlbumArtist": "Tessa Violet",
                      "UserData": {
                        "PlayCount": 3,
                        "LastPlayedDate": "2026-05-17T20:30:00.0000000Z"
                      },
                      "RunTimeTicks": 2450000000
                    }
                  ],
                  "TotalRecordCount": 1
                }
                """.trimIndent(),
            )
        }
        val client = EmbyClient(testHttpClient(engine))
        val server = com.phoebe.app.domain.PlexServer("emby:test", "Emby", "https://emby.example/emby", owned = true)

        val stats = client.playbackStatsPage(
            server = server,
            library = MusicLibrary("music", "Music"),
            token = "token",
            userId = "user-1",
            start = 0,
            size = 100,
        )

        assertEquals(1, stats.size)
        assertEquals("track-1", stats.single().itemId)
        assertEquals(listOf("/emby/Users/user-1/Items"), paths)
        assertTrue("EnableUserData" in queryKeys)
        assertTrue("IsPlayed" in queryKeys)
        assertTrue("IncludeItemTypes" in queryKeys)
    }

    @Test
    fun embyPlaybackLatestReturnsBareJsonArray() = runTest {
        val engine = MockEngine { request ->
            respondJson(
                """
                [
                  {
                    "Id": "track-1",
                    "Type": "Audio",
                    "Name": "A Night at the Opera",
                    "UserData": {
                      "PlayCount": 1,
                      "LastPlayedDate": "2026-05-17T20:30:00.0000000Z"
                    },
                    "RunTimeTicks": 2450000000
                  }
                ]
                """.trimIndent(),
            )
        }
        val client = EmbyClient(testHttpClient(engine))
        val server = com.phoebe.app.domain.PlexServer("emby:test", "Emby", "https://emby.example/emby", owned = true)

        val stats = client.playbackLatestPlayedPage(
            server = server,
            library = MusicLibrary("music", "Music"),
            token = "token",
            userId = "user-1",
            size = 10,
        )

        assertEquals(1, stats.size)
        assertEquals("track-1", stats.single().itemId)
    }

    @Test
    fun embyArtistImageUrlUsesArtistsByNamePath() {
        assertEquals(
            "https://emby.example/emby/Artists/Tessa%20Violet/Images/Primary?tag=artist-tag&api_key=token",
            artistImageUrl(
                server = com.phoebe.app.domain.PlexServer("emby:test", "Emby", "https://emby.example/emby", owned = true),
                artistName = "Tessa Violet",
                token = "token",
                tag = "artist-tag",
            ),
        )
    }

    @Test
    fun embyArtworkUrlGeneratedForAllItemsWithId() {
        assertEquals(
            "https://emby.example/emby/Items/album-2/Images/Primary?api_key=token",
            itemImageUrl(
                server = com.phoebe.app.domain.PlexServer("emby:test", "Emby", "https://emby.example/emby", owned = true),
                itemId = "album-2",
                token = "token",
                tag = null,
                hasPrimaryImage = true,
            ),
        )
    }

    @Test
    fun embyArtworkUrlWorksWithoutImageTagWhenPrimaryImageExists() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/emby/Items", "/emby/Users/user-1/Items" -> respondJson(
                    """
                    {
                      "Items": [
                        {
                          "Id": "album-2",
                          "Type": "MusicAlbum",
                          "Name": "Halo",
                          "AlbumArtist": "Tessa Violet",
                          "HasPrimaryImage": true,
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

        val album = client.albumPage(server, library, "token", "user-1", pageIndex = 0).items.single()

        assertEquals(
            "https://emby.example/emby/Items/album-2/Images/Primary?api_key=token",
            album.thumbUrl,
        )
    }
}

private fun MockRequestHandleScope.respondJson(content: String) = respond(
    content = content,
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)
