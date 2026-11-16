package com.phoebe.app

import com.phoebe.app.data.JellyfinClient
import com.phoebe.app.data.embyFamilyArtworkAuthHeaders
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    fun sendsAuthorizationHeaderInsteadOfLegacyEmbyHeaders() = runTest {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/Users/AuthenticateByName" -> respondJson(
                    """{ "User": { "Id": "user-1", "Name": "Ada" }, "AccessToken": "jf-token" }""",
                )
                "/UserViews" -> respondJson("""{ "Items": [] }""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = JellyfinClient(testHttpClient(engine))

        val auth = client.authenticate("https://jellyfin.example/", "ada", "secret")
        client.libraries(auth.server, auth.token, auth.userId)

        val (signIn, libraries) = requests
        assertTrue(signIn.headers[HttpHeaders.Authorization].orEmpty().startsWith("MediaBrowser Client=\"Phoebe\""))
        assertFalse(signIn.headers[HttpHeaders.Authorization].orEmpty().contains("Token="))
        assertTrue(libraries.headers[HttpHeaders.Authorization].orEmpty().endsWith(", Token=\"jf-token\""))
        requests.forEach { request ->
            assertEquals(null, request.headers["X-Emby-Authorization"])
            assertEquals(null, request.headers["X-Emby-Token"])
        }
    }

    @Test
    fun artworkAuthHeadersUseAuthorizationAndToken() {
        val headers = embyFamilyArtworkAuthHeaders("https://jellyfin.example/Items/item-1/Images/Primary?api_key=jf-token")

        assertEquals(setOf(HttpHeaders.Authorization, "X-Emby-Token"), headers.keys)
        assertTrue(headers.getValue(HttpHeaders.Authorization).endsWith(", Token=\"jf-token\""))
        assertEquals("jf-token", headers["X-Emby-Token"])
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
    fun markPlayedUsesUserPlayedItemsEndpoint() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedPath: String? = null
        val engine = MockEngine { request ->
            capturedMethod = request.method
            capturedPath = request.url.encodedPath
            respondJson("""{ "ItemId": "track-1", "Played": true, "PlayCount": 1 }""")
        }
        val client = JellyfinClient(testHttpClient(engine))
        val server = PlexServer("jellyfin:test", "Jellyfin", "https://jellyfin.example", owned = true)

        client.markPlayed(server, "token", "user-1", "track-1")

        assertEquals(HttpMethod.Post, capturedMethod)
        assertEquals("/Users/user-1/PlayedItems/track-1", capturedPath)
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
    fun parallelAlbumPagesFetchConcurrentlyWithFastFields() = runTest {
        val server = PlexServer("jellyfin:test", "Jellyfin", "https://jellyfin.example", owned = true)
        val library = MusicLibrary("music", "Music")
        val pageSize = JellyfinClient.JellyfinPageSize
        val total = pageSize * 2
        val starts = mutableListOf<Int>()
        val seenFields = mutableListOf<String?>()
        val seenEnableImages = mutableListOf<String?>()
        val seenRequestsMutex = Mutex()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/Items" -> {
                    val start = request.url.parameters["startIndex"]?.toIntOrNull() ?: 0
                    seenRequestsMutex.withLock {
                        starts += start
                        seenFields += request.url.parameters["fields"]
                        seenEnableImages += request.url.parameters["enableImages"]
                    }
                    val items = (start until (start + pageSize).coerceAtMost(total)).joinToString(",") { index ->
                        """{ "Id": "album-$index", "Type": "MusicAlbum", "Name": "Album $index", "AlbumArtist": "Artist" }"""
                    }
                    respondJson("""{ "Items": [ $items ], "TotalRecordCount": $total }""")
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = JellyfinClient(testHttpClient(engine))

        val albums = client.albums(server, library, "token", "user-1", fastSync = true)

        assertEquals(total, albums.size)
        assertEquals(listOf(0, pageSize), starts.sorted())
        val fields = seenFields.first().orEmpty()
        assertFalse(fields.contains("Path"))
        assertFalse(fields.contains("MediaSources"))
        assertTrue(seenEnableImages.all { it == "true" })
    }

    @Test
    fun parallelTrackPagesInvokeOnPagePerPageNotOnlyAfterAllPagesFinish() = runTest {
        val server = PlexServer("jellyfin:test", "Jellyfin", "https://jellyfin.example", owned = true)
        val library = MusicLibrary("music", "Music")
        val pageSize = JellyfinClient.JellyfinPageSize
        val total = pageSize * 2 + 1
        val loadedPerCallback = mutableListOf<Int>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/Items" -> {
                    val start = request.url.parameters["startIndex"]?.toIntOrNull() ?: 0
                    val items = (start until (start + pageSize).coerceAtMost(total)).joinToString(",") { index ->
                        """{ "Id": "track-$index", "Type": "Audio", "Name": "Track $index", "Album": "Album", "RunTimeTicks": 10000000 }"""
                    }
                    respondJson("""{ "Items": [ $items ], "TotalRecordCount": $total }""")
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = JellyfinClient(testHttpClient(engine))

        client.tracks(server, library, "token", "user-1", includeMediaDetails = false) { page, _ ->
            loadedPerCallback += page.size
        }

        assertEquals(3, loadedPerCallback.size)
        assertEquals(total, loadedPerCallback.sum())
        assertEquals(pageSize, loadedPerCallback.first())
    }

    @Test
    fun parallelTrackPagesRequestExpectedOffsetsAndPreserveOrder() = runTest {
        val server = PlexServer("jellyfin:test", "Jellyfin", "https://jellyfin.example", owned = true)
        val library = MusicLibrary("music", "Music")
        val starts = mutableListOf<Int>()
        val startsMutex = Mutex()
        val pageSize = JellyfinClient.JellyfinPageSize
        val total = pageSize * 2 + 1
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/Items" -> {
                    val start = request.url.parameters["startIndex"]?.toIntOrNull() ?: 0
                    startsMutex.withLock {
                        starts += start
                    }
                    val items = (start until (start + pageSize).coerceAtMost(total)).joinToString(",") { index ->
                        val trackNum = index + 1
                        """{ "Id": "track-$trackNum", "Type": "Audio", "Name": "Track $trackNum", "Album": "Album", "RunTimeTicks": 10000000 }"""
                    }
                    respondJson("""{ "Items": [ $items ], "TotalRecordCount": $total }""")
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = JellyfinClient(testHttpClient(engine))

        val tracks = client.tracks(server, library, "token", "user-1", includeMediaDetails = false)

        assertEquals(total, tracks.size)
        assertEquals((1..total).map { "track-$it" }, tracks.map { it.id })
        assertEquals(listOf(0, pageSize, pageSize * 2), starts.sorted())
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
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/Items" -> {
                    starts += request.url.parameters["startIndex"]
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
        assertEquals(listOf<String?>(JellyfinClient.JellyfinPageSize.toString()), starts)
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

}

private fun MockRequestHandleScope.respondJson(content: String) = respond(
    content = content,
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)
