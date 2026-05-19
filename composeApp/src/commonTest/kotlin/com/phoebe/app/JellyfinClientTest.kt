package com.phoebe.app

import com.phoebe.app.data.JellyfinClient
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.platform.parseJellyfinDiscoveryServer
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JellyfinClientTest {
    @Test
    fun authenticatesAndLoadsMusicLibraries() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/Users/AuthenticateByName" -> respondJson(
                    """
                    {
                      "User": { "Id": "user-1", "Name": "Ada" },
                      "AccessToken": "jf-token"
                    }
                    """.trimIndent(),
                )
                "/UserViews" -> respondJson(
                    """
                    {
                      "Items": [
                        { "Id": "movies", "Name": "Movies", "CollectionType": "movies" },
                        { "Id": "music", "Name": "Music", "CollectionType": "music" }
                      ]
                    }
                    """.trimIndent(),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = JellyfinClient(testHttpClient(engine))

        val auth = client.authenticate("https://jellyfin.example/", "ada", "secret")
        val libraries = client.libraries(auth.server, auth.token, auth.userId)

        assertEquals("jf-token", auth.token)
        assertEquals("Ada", auth.userName)
        assertEquals(listOf(MusicLibrary("music", "Music")), libraries)
    }

    @Test
    fun mapsTracksWithArtworkAndUserRating() = runTest {
        val server = PlexServer("jellyfin:test", "Jellyfin", "https://jellyfin.example", owned = true)
        val library = MusicLibrary("music", "Music")
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/Items" -> respondJson(
                    """
                    {
                      "Items": [
                        {
                          "Id": "track-1",
                          "Type": "Audio",
                          "Name": "Night Signals",
                          "Album": "Radio House",
                          "AlbumId": "album-1",
                          "Artists": ["North Lake"],
                          "ProductionYear": 2025,
                          "DateCreated": "2026-05-17T14:30:15.0000000Z",
                          "RunTimeTicks": 2450000000,
                          "Genres": ["Electronic"],
                          "Path": "/music/North Lake/Radio House/01.flac",
                          "ImageTags": { "Primary": "img-tag" },
                          "MediaSources": [{ "Container": "flac", "Bitrate": 920000 }],
                          "UserData": { "Rating": 8.0, "IsFavorite": true }
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = JellyfinClient(testHttpClient(engine))

        val track = client.tracks(server, library, "token", "user-1").single()

        assertEquals("track-1", track.id)
        assertEquals("Night Signals", track.title)
        assertEquals("North Lake", track.artist)
        assertEquals("Radio House", track.album)
        assertEquals(245_000L, track.durationMs)
        assertEquals("FLAC", track.audioCodec)
        assertEquals(920, track.bitrateKbps)
        assertEquals(1_779_028_215_000L, track.dateAddedMs)
        assertEquals(4f, track.rating)
        assertTrue(track.thumbUrl!!.contains("/Items/track-1/Images/Primary"))
    }

    @Test
    fun loadsArtistsFromJellyfinAlbumArtistsEndpoint() = runTest {
        val server = PlexServer("jellyfin:test", "Jellyfin", "https://jellyfin.example", owned = true)
        val library = MusicLibrary("music", "Music")
        val seenPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            seenPaths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/Artists/AlbumArtists" -> respondJson(
                    """
                    {
                      "Items": [
                        { "Id": "artist-1", "Type": "MusicArtist", "Name": "North Lake" }
                      ],
                      "TotalRecordCount": 1
                    }
                    """.trimIndent(),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = JellyfinClient(testHttpClient(engine))

        val artist = client.artists(server, library, "token", "user-1").single()

        assertEquals("artist-1", artist.id)
        assertEquals("North Lake", artist.title)
        assertEquals(listOf("/Artists/AlbumArtists"), seenPaths)
    }

    @Test
    fun fallsBackToUnscopedItemsWhenSelectedJellyfinLibraryReturnsNoTracks() = runTest {
        val server = PlexServer("jellyfin:test", "Jellyfin", "https://jellyfin.example", owned = true)
        val library = MusicLibrary("music", "Music")
        val seenQueries = mutableListOf<String>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/Items" -> {
                    seenQueries += request.url.encodedQuery
                    if (request.url.parameters["parentId"] == "music") {
                        respondJson("""{ "Items": [] }""")
                    } else {
                        respondJson(
                            """
                            {
                              "Items": [
                                {
                                  "Id": "track-1",
                                  "Type": "Audio",
                                  "Name": "Night Signals",
                                  "Album": "Radio House",
                                  "AlbumId": "album-1",
                                  "Artists": ["North Lake"],
                                  "RunTimeTicks": 2450000000
                                }
                              ]
                            }
                            """.trimIndent(),
                        )
                    }
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = JellyfinClient(testHttpClient(engine))

        val track = client.tracks(server, library, "token", "user-1").single()

        assertEquals("track-1", track.id)
        assertTrue(seenQueries.any { it.contains("parentId=music") })
        assertTrue(seenQueries.any { !it.contains("parentId=music") })
    }

    @Test
    fun loadsJellyfinTracksAcrossPages() = runTest {
        val server = PlexServer("jellyfin:test", "Jellyfin", "https://jellyfin.example", owned = true)
        val library = MusicLibrary("music", "Music")
        val starts = mutableListOf<String?>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/Items" -> {
                    starts += request.url.parameters["startIndex"]
                    val start = request.url.parameters["startIndex"]?.toIntOrNull() ?: 0
                    val items = when (start) {
                        0 -> (1..JellyfinClient.JellyfinPageSize).joinToString(",") { index ->
                            """{ "Id": "track-$index", "Type": "Audio", "Name": "Track $index", "Album": "Album", "RunTimeTicks": 10000000 }"""
                        }
                        JellyfinClient.JellyfinPageSize -> """{ "Id": "track-${JellyfinClient.JellyfinPageSize + 1}", "Type": "Audio", "Name": "Track ${JellyfinClient.JellyfinPageSize + 1}", "Album": "Album", "RunTimeTicks": 10000000 }"""
                        else -> ""
                    }
                    respondJson("""{ "Items": [ $items ], "TotalRecordCount": ${JellyfinClient.JellyfinPageSize + 1} }""")
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = JellyfinClient(testHttpClient(engine))

        val tracks = client.tracks(server, library, "token", "user-1")

        assertEquals(JellyfinClient.JellyfinPageSize + 1, tracks.size)
        assertEquals(listOf<String?>("0", JellyfinClient.JellyfinPageSize.toString()), starts)
    }

    @Test
    fun loadsOnlyRequestedJellyfinTrackPage() = runTest {
        val server = PlexServer("jellyfin:test", "Jellyfin", "https://jellyfin.example", owned = true)
        val library = MusicLibrary("music", "Music")
        val starts = mutableListOf<String?>()
        val limits = mutableListOf<String?>()
        val fields = mutableListOf<String?>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/Items" -> {
                    starts += request.url.parameters["startIndex"]
                    limits += request.url.parameters["limit"]
                    fields += request.url.parameters["fields"]
                    respondJson(
                        """
                        {
                          "Items": [
                            { "Id": "track-101", "Type": "Audio", "Name": "Track 101", "Album": "Album", "RunTimeTicks": 10000000 }
                          ],
                          "TotalRecordCount": 250
                        }
                        """.trimIndent(),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = JellyfinClient(testHttpClient(engine))

        val page = client.trackPage(server, library, "token", "user-1", pageIndex = 1)

        assertEquals(250, page.total)
        assertEquals(listOf("track-101"), page.items.map { it.id })
        assertEquals(listOf<String?>(JellyfinClient.QuickCatalogPageSize.toString()), starts)
        assertEquals(listOf<String?>(JellyfinClient.QuickCatalogPageSize.toString()), limits)
        val requestedFields = fields.single().orEmpty()
        assertFalse(requestedFields.contains("Path"))
        assertFalse(requestedFields.contains("MediaSources"))
        assertTrue(requestedFields.contains("UserData"))
        assertTrue(requestedFields.contains("AlbumArtist"))
    }

    @Test
    fun fastJellyfinTrackSyncOmitsHeavyMediaFields() = runTest {
        val server = PlexServer("jellyfin:test", "Jellyfin", "https://jellyfin.example", owned = true)
        val library = MusicLibrary("music", "Music")
        val seenFields = mutableListOf<String?>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/Items" -> {
                    seenFields += request.url.parameters["fields"]
                    respondJson(
                        """
                        {
                          "Items": [
                            { "Id": "track-1", "Type": "Audio", "Name": "Track 1", "Album": "Album", "RunTimeTicks": 10000000 }
                          ],
                          "TotalRecordCount": 1
                        }
                        """.trimIndent(),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = JellyfinClient(testHttpClient(engine))

        val tracks = client.tracks(server, library, "token", "user-1", includeMediaDetails = false)

        assertEquals(listOf("track-1"), tracks.map { it.id })
        val fields = seenFields.single().orEmpty()
        assertFalse(fields.contains("Path"))
        assertFalse(fields.contains("MediaSources"))
        assertTrue(fields.contains("UserData"))
        assertTrue(fields.contains("AlbumArtist"))
    }

    @Test
    fun sendsPlaylistFavoriteAndRatingMutations() = runTest {
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            seen += "${request.method.value} ${request.url.encodedPath}?${request.url.encodedQuery}"
            respondJson("""{ "Id": "playlist-1" }""")
        }
        val server = PlexServer("jellyfin:test", "Jellyfin", "https://jellyfin.example", owned = true)
        val client = JellyfinClient(testHttpClient(engine))

        client.createPlaylist(server, "token", "user-1", "Road", listOf("track-1"))
        client.addTracksToPlaylist(server, "token", "user-1", "playlist-1", listOf("track-2"))
        client.setFavorite(server, "token", "track-1", true)
        client.rateItem(server, "token", "track-1", 4.5f)

        assertTrue(seen.any { it.startsWith("POST /Playlists?") })
        assertTrue(seen.any { it.contains("POST /Playlists/playlist-1/Items?") && it.contains("ids=track-2") })
        assertTrue(seen.any { it.startsWith("POST /UserFavoriteItems/track-1?") })
        assertTrue(seen.any { it.startsWith("POST /UserItems/track-1/Rating?") && it.contains("rating=9") })
    }

    @Test
    fun startsAndCompletesQuickConnect() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/QuickConnect/Initiate" -> respondJson(
                    """
                    {
                      "Authenticated": false,
                      "Secret": "quick-secret",
                      "Code": "ABCD12"
                    }
                    """.trimIndent(),
                )
                "/QuickConnect/Connect" -> respondJson(
                    """
                    {
                      "Authenticated": true,
                      "Secret": "quick-secret",
                      "Code": "ABCD12"
                    }
                    """.trimIndent(),
                )
                "/Users/AuthenticateWithQuickConnect" -> respondJson(
                    """
                    {
                      "User": { "Id": "user-1", "Name": "Ada" },
                      "AccessToken": "jf-token"
                    }
                    """.trimIndent(),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = JellyfinClient(testHttpClient(engine))

        val quickConnect = client.initiateQuickConnect("https://jellyfin.example/")
        val auth = client.authenticateQuickConnect(quickConnect.ServerUrl!!, quickConnect.Secret)

        assertEquals("https://jellyfin.example", quickConnect.ServerUrl)
        assertEquals("ABCD12", quickConnect.Code)
        assertEquals("jf-token", auth.token)
        assertEquals("Ada", auth.userName)
    }

    @Test
    fun completesLegacyQuickConnectWithAuthenticationToken() = runTest {
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            seen += "${request.method.value} ${request.url.encodedPath}"
            when (request.url.encodedPath) {
                "/QuickConnect/Initiate" -> if (request.method == HttpMethod.Post) {
                    respond("", HttpStatusCode.MethodNotAllowed)
                } else {
                    respondJson(
                        """
                        {
                          "Authenticated": false,
                          "Secret": "quick-secret",
                          "Code": "ABCD12"
                        }
                        """.trimIndent(),
                    )
                }
                "/QuickConnect/Connect" -> respondJson(
                    """
                    {
                      "Authenticated": true,
                      "Secret": "quick-secret",
                      "Code": "ABCD12",
                      "Authentication": "legacy-token"
                    }
                    """.trimIndent(),
                )
                "/Users/AuthenticateWithQuickConnect" -> respondJson(
                    """
                    {
                      "User": { "Id": "user-1", "Name": "Ada" },
                      "AccessToken": "jf-token"
                    }
                    """.trimIndent(),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = JellyfinClient(testHttpClient(engine))

        val quickConnect = client.initiateQuickConnect("https://jellyfin.example/")
        val auth = client.authenticateQuickConnect(quickConnect.ServerUrl!!, quickConnect.Secret)

        assertTrue(seen.contains("POST /QuickConnect/Initiate"))
        assertTrue(seen.contains("GET /QuickConnect/Initiate"))
        assertEquals("jf-token", auth.token)
    }

    @Test
    fun mapsDiscoveryResponseToJellyfinServer() {
        val server = parseJellyfinDiscoveryServer(
            """
            {
              "Address": "http://192.168.1.20:8096",
              "Id": "server-id",
              "Name": "Studio Jellyfin"
            }
            """.trimIndent(),
        )

        assertEquals("jellyfin:server-id", server?.id)
        assertEquals("Studio Jellyfin", server?.name)
        assertEquals("http://192.168.1.20:8096", server?.uri)
        assertEquals(listOf("http://192.168.1.20:8096"), server?.localConnectionUris)
    }
}

private fun MockRequestHandleScope.respondJson(content: String) = respond(
    content = content,
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)
