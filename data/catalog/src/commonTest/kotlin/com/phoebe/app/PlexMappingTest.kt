package com.phoebe.app

import com.phoebe.app.data.PlexClient
import com.phoebe.app.data.PlexCollectionFacet
import com.phoebe.app.data.PlexCollectionTarget
import com.phoebe.app.data.PlexFilterChoice
import com.phoebe.app.data.PlexDeviceDto
import com.phoebe.app.data.PlexMediaContainerResponse
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.Playlist
import com.phoebe.app.sources.PlexCatalogBuilder
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlexMappingTest {
    @Test
    fun parsesMusicLibrariesFromPlexContainer() {
        val json = """
            {
              "MediaContainer": {
                "Directory": [
                  { "key": "1", "title": "Movies", "type": "movie" },
                  { "key": "2", "title": "Music", "type": "artist" }
                ]
              }
            }
        """.trimIndent()

        val response = PlexClient.PlexJson.decodeFromString<PlexMediaContainerResponse>(json)

        assertEquals("Music", response.mediaContainer.directories.last().title)
        assertEquals("artist", response.mediaContainer.directories.last().type)
    }

    @Test
    fun parsesPlexResourcesArray() {
        val json = """
            [
              {
                "name": "plex",
                "product": "Plex Media Server",
                "clientIdentifier": "server-id",
                "owned": true,
                "provides": "server",
                "connections": [
                  { "uri": "https://example.plex.direct:32400", "local": false }
                ]
              }
            ]
        """.trimIndent()

        val devices = PlexClient.PlexJson.decodeFromString<List<PlexDeviceDto>>(json)

        assertEquals("plex", devices.single().name)
        assertEquals("server-id", devices.single().clientIdentifier)
        assertEquals("https://example.plex.direct:32400", devices.single().connections.single().uri)
    }

    @Test
    fun parsesArtistsFromMetadataWhenDirectoryEmpty() {
        val json = """
            {
              "MediaContainer": {
                "size": 2,
                "Metadata": [
                  {
                    "ratingKey": "101",
                    "title": "North Lake",
                    "type": "artist",
                    "leafCount": 4,
                    "thumb": "/library/metadata/101/thumb"
                  },
                  {
                    "ratingKey": "102",
                    "title": "South Echo",
                    "type": "artist",
                    "leafCount": 1
                  }
                ]
              }
            }
        """.trimIndent()

        val response = PlexClient.PlexJson.decodeFromString<PlexMediaContainerResponse>(json)

        assertEquals(0, response.mediaContainer.directories.size)
        assertEquals(2, response.mediaContainer.metadata.size)
        assertEquals("artist", response.mediaContainer.metadata.first().type)
    }

    @Test
    fun parsesPlaylistTrackCountAndKey() {
        val json = """
            {
              "MediaContainer": {
                "Metadata": [
                  {
                    "ratingKey": "42",
                    "key": "/playlists/42/items",
                    "title": "Favorites",
                    "leafCount": 19
                  }
                ]
              }
            }
        """.trimIndent()

        val response = PlexClient.PlexJson.decodeFromString<PlexMediaContainerResponse>(json)
        val playlist = response.mediaContainer.metadata.single()

        assertEquals("/playlists/42/items", playlist.key)
        assertEquals(19, playlist.leafCount)
    }

    @Test
    fun playlistTracksPreferPlexTrackArtistOverCompilationArtist() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/playlists/42/items" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Metadata": [
                              {
                                "ratingKey": "31615",
                                "title": "Only You",
                                "grandparentTitle": "Various Artists",
                                "originalTitle": "Yazoo",
                                "parentTitle": "80s Pop: 111 Original Hits",
                                "duration": 194208,
                                "Media": [
                                  { "Part": [ { "key": "/library/parts/22742/file.mp3", "file": "Only You.mp3" } ] }
                                ]
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val track = client.playlistTracks(
            server = PlexServer("server", "Plex", "https://plex.example", owned = true),
            playlist = Playlist(id = "42", title = "80s", trackCount = 1),
            token = "token",
        ).single()

        assertEquals("Yazoo", track.artist)
        assertEquals("80s Pop: 111 Original Hits", track.album)
    }

    @Test
    fun mapsPlexAddedAtOntoTracks() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/metadata/a1/children" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Metadata": [
                              {
                                "ratingKey": "t1",
                                "title": "Fresh Song",
                                "grandparentTitle": "Artist One",
                                "parentTitle": "Album One",
                                "duration": 1000,
                                "addedAt": 1700000200,
                                "Media": [
                                  { "Part": [ { "key": "/library/parts/t1/file.mp3", "file": "file.mp3" } ] }
                                ]
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val track = client.children(PlexServer("server", "Plex", "https://plex.example", owned = true), "a1", "token").single()

        assertEquals(1_700_000_200_000L, track.dateAddedMs)
    }

    @Test
    fun mapsPlexUserRatingOntoTrackStars() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/metadata/a1/children" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Metadata": [
                              {
                                "ratingKey": "t1",
                                "title": "Rated Song",
                                "grandparentTitle": "Artist One",
                                "parentTitle": "Album One",
                                "duration": 1000,
                                "userRating": 7.0,
                                "Media": [
                                  { "Part": [ { "key": "/library/parts/t1/file.mp3", "file": "file.mp3" } ] }
                                ]
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val track = client.children(PlexServer("server", "Plex", "https://plex.example", owned = true), "a1", "token").single()

        assertEquals(3.5f, track.rating)
    }

    @Test
    fun popularTracksForArtistUsesPlexPopularTrackFilters() = runTest {
        var capturedPath = ""
        var capturedType: String? = null
        var capturedArtist: String? = null
        var capturedSubformat: String? = null
        var capturedGroup: String? = null
        var capturedRatingCount: String? = null
        var capturedSort: String? = null
        var capturedLimit: String? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            capturedType = request.url.parameters["type"]
            capturedArtist = request.url.parameters["artist.id"]
            capturedSubformat = request.url.parameters["album.subformat!"]
            capturedGroup = request.url.parameters["group"]
            capturedRatingCount = request.url.parameters["ratingCount>>"]
            capturedSort = request.url.parameters["sort"]
            capturedLimit = request.url.parameters["limit"]
            respond(
                content = """
                    {
                      "MediaContainer": {
                        "Metadata": [
                          {
                            "ratingKey": "t1",
                            "title": "Popular Song",
                            "grandparentTitle": "Artist One",
                            "parentTitle": "Album One",
                            "duration": 1000,
                            "Media": [
                              { "Part": [ { "key": "/library/parts/t1/file.mp3", "file": "file.mp3" } ] }
                            ]
                          }
                        ]
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val tracks = client.popularTracksForArtist(
            server = PlexServer("server", "Plex", "https://plex.example", owned = true),
            library = MusicLibrary("1", "Music"),
            ratingKey = "artist-1",
            token = "token",
            limit = 5,
        )

        assertEquals("/library/sections/1/all", capturedPath)
        assertEquals("10", capturedType)
        assertEquals("artist-1", capturedArtist)
        assertEquals("Compilation,Live", capturedSubformat)
        assertEquals("title", capturedGroup)
        assertEquals("0", capturedRatingCount)
        assertEquals("ratingCount:desc", capturedSort)
        assertEquals("5", capturedLimit)
        assertEquals("Popular Song", tracks.single().title)
    }

    @Test
    fun popularTracksForLibraryUsesPlexPopularTrackFilters() = runTest {
        var capturedPath = ""
        var capturedType: String? = null
        var capturedArtist: String? = null
        var capturedSubformat: String? = null
        var capturedGroup: String? = null
        var capturedRatingCount: String? = null
        var capturedSort: String? = null
        var capturedLimit: String? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            capturedType = request.url.parameters["type"]
            capturedArtist = request.url.parameters["artist.id"]
            capturedSubformat = request.url.parameters["album.subformat!"]
            capturedGroup = request.url.parameters["group"]
            capturedRatingCount = request.url.parameters["ratingCount>>"]
            capturedSort = request.url.parameters["sort"]
            capturedLimit = request.url.parameters["limit"]
            respond(
                content = """
                    {
                      "MediaContainer": {
                        "Metadata": [
                          {
                            "ratingKey": "t1",
                            "title": "Library Top Song",
                            "grandparentTitle": "Artist One",
                            "parentTitle": "Album One",
                            "duration": 1000,
                            "Media": [
                              { "Part": [ { "key": "/library/parts/t1/file.mp3", "file": "file.mp3" } ] }
                            ]
                          }
                        ]
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val tracks = client.popularTracksForLibrary(
            server = PlexServer("server", "Plex", "https://plex.example", owned = true),
            library = MusicLibrary("1", "Music"),
            token = "token",
            limit = 25,
        )

        assertEquals("/library/sections/1/all", capturedPath)
        assertEquals("10", capturedType)
        assertEquals(null, capturedArtist)
        assertEquals("Compilation,Live", capturedSubformat)
        assertEquals(null, capturedGroup)
        assertEquals("0", capturedRatingCount)
        assertEquals("ratingCount:desc", capturedSort)
        assertEquals("25", capturedLimit)
        assertEquals("Library Top Song", tracks.single().title)
    }

    @Test
    fun mapsPlexMoodAndStyleOntoTracks() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/metadata/a1/children" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Metadata": [
                              {
                                "ratingKey": "t1",
                                "title": "Tagged Song",
                                "grandparentTitle": "Artist One",
                                "parentTitle": "Album One",
                                "duration": 1000,
                                "Genre": [ { "tag": "Dream pop" } ],
                                "Mood": [ { "tag": "Late night" } ],
                                "Style": [ { "tag": "Shoegaze" } ],
                                "Media": [
                                  { "Part": [ { "key": "/library/parts/t1/file.mp3", "file": "file.mp3" } ] }
                                ]
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val track = client.children(PlexServer("server", "Plex", "https://plex.example", owned = true), "a1", "token").single()

        assertEquals("Dream pop", track.genre)
        assertEquals("Late night", track.mood)
        assertEquals("Shoegaze", track.style)
    }

    @Test
    fun mapsPlexMoodAndStyleOntoAlbums() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/albums" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Directory": [
                              {
                                "ratingKey": "a1",
                                "key": "/library/metadata/a1/children",
                                "title": "Tagged Album",
                                "type": "album",
                                "parentTitle": "Artist One",
                                "Genre": [ { "tag": "Dream pop" } ],
                                "Mood": [ { "tag": "Late night" } ],
                                "Style": [ { "tag": "Shoegaze" } ]
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val album = client.albums(
            PlexServer("server", "Plex", "https://plex.example", owned = true),
            MusicLibrary("1", "Music"),
            "token",
        ).single()

        assertEquals("Dream pop", album.genre)
        assertEquals("Late night", album.mood)
        assertEquals("Shoegaze", album.style)
    }

    @Test
    fun mapsManagedCollectionTagsOntoArtistAndAlbumFavorites() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Directory": [
                              {
                                "ratingKey": "artist1",
                                "key": "/library/metadata/artist1",
                                "title": "Favorite Artist",
                                "leafCount": 1,
                                "Collection": [ { "tag": "Favorite Artists" } ]
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/sections/1/albums" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Metadata": [
                              {
                                "ratingKey": "album1",
                                "title": "Favorite Album",
                                "parentTitle": "Favorite Artist",
                                "Collection": [ { "tag": "Phoebe Favorite Albums" } ]
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example", owned = true)
        val library = MusicLibrary("1", "Music")

        assertEquals(true, client.artists(server, library, "token").single().favorite)
        assertEquals(true, client.albums(server, library, "token").single().favorite)
    }

    @Test
    fun mapsPlexMoodAndStyleOntoArtistsFromMetadata() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Directory": [
                              {
                                "ratingKey": "ar1",
                                "key": "/library/metadata/ar1/children",
                                "title": "Artist One",
                                "type": "artist",
                                "Genre": [ { "tag": "Dream pop" } ],
                                "Mood": [ { "tag": "Late night" } ],
                                "Style": [ { "tag": "Shoegaze" } ]
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val artist = client.artists(
            PlexServer("server", "Plex", "https://plex.example", owned = true),
            MusicLibrary("1", "Music"),
            "token",
        ).single()

        assertEquals("Dream pop", artist.genre)
        assertEquals("Late night", artist.mood)
        assertEquals("Shoegaze", artist.style)
    }

    @Test
    fun mapsPlexMoodAndStyleFromArtistAndAlbumDetails() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/metadata/ar1" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Metadata": [
                              {
                                "ratingKey": "ar1",
                                "title": "Artist One",
                                "type": "artist",
                                "Mood": [ { "tag": "Late night" } ],
                                "Style": [ { "tag": "Shoegaze" } ]
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/metadata/a1" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Metadata": [
                              {
                                "ratingKey": "a1",
                                "title": "Album One",
                                "parentTitle": "Artist One",
                                "type": "album",
                                "Mood": [ { "tag": "Reflective" } ],
                                "Style": [ { "tag": "Acid Jazz" } ]
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example", owned = true)

        val artist = client.artistDetails(server, "ar1", "token")
        val album = client.albumDetails(server, "a1", "token")

        assertEquals("Late night", artist?.mood)
        assertEquals("Shoegaze", artist?.style)
        assertEquals("Reflective", album?.mood)
        assertEquals("Acid Jazz", album?.style)
    }

    @Test
    fun mapsPlexCollectionFilterChoicesToItems() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/style" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Directory": [
                              {
                                "key": "42",
                                "title": "Acid Jazz",
                                "fastKey": "/library/sections/1/all?type=9&style=42"
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/sections/1/all" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Directory": [
                              {
                                "ratingKey": "a1",
                                "key": "/library/metadata/a1/children",
                                "title": "Tagged Album",
                                "type": "album",
                                "parentTitle": "Artist One"
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example", owned = true)
        val library = MusicLibrary("1", "Music")

        val choice = client.collectionFilterChoices(server, library, PlexCollectionTarget.Albums, PlexCollectionFacet.Style, "token").single()
        val items = client.collectionFilterItems(server, library, PlexCollectionTarget.Albums, PlexCollectionFacet.Style, choice, "token")

        assertEquals("Acid Jazz", choice.title)
        assertEquals(listOf("a1"), items)
    }

    @Test
    fun mapsPlexFilterChoicePathKeyToItems() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/style" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Directory": [
                              {
                                "key": "/library/sections/1/all?type=9&style=42",
                                "title": "Acid Jazz"
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/sections/1/all" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Directory": [
                              {
                                "ratingKey": "a1",
                                "key": "/library/metadata/a1/children",
                                "title": "Tagged Album",
                                "type": "album",
                                "parentTitle": "Artist One"
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example", owned = true)
        val library = MusicLibrary("1", "Music")

        val choice = client.collectionFilterChoices(server, library, PlexCollectionTarget.Albums, PlexCollectionFacet.Style, "token").single()
        val items = client.collectionFilterItems(server, library, PlexCollectionTarget.Albums, PlexCollectionFacet.Style, choice, "token")

        assertEquals("42", choice.key)
        assertEquals(listOf("a1"), items)
    }

    @Test
    fun mapsPlexAlbumScopedFilterChoiceToItems() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/album.style" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Directory": [
                              {
                                "key": "42",
                                "title": "Acid Jazz"
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/sections/1/all" -> {
                    if (request.url.parameters["album.style"] == "42") {
                        respond(
                            content = """
                                {
                                  "MediaContainer": {
                                    "Directory": [
                                      {
                                        "ratingKey": "a1",
                                        "key": "/library/metadata/a1/children",
                                        "title": "Tagged Album",
                                        "type": "album",
                                        "parentTitle": "Artist One"
                                      }
                                    ]
                                  }
                                }
                            """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond("", HttpStatusCode.NotFound)
                    }
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example", owned = true)
        val library = MusicLibrary("1", "Music")

        val choice = client.collectionFilterChoices(server, library, PlexCollectionTarget.Albums, PlexCollectionFacet.Style, "token").single()
        val items = client.collectionFilterItems(server, library, PlexCollectionTarget.Albums, PlexCollectionFacet.Style, choice, "token")

        assertEquals("42", choice.key)
        assertEquals("album.style", choice.filterField)
        assertEquals(listOf("a1"), items)
    }

    @Test
    fun mapsPlexAlbumMoodFilterChoiceByTitleWhenKeyDoesNotResolve() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/album.mood" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Directory": [
                              {
                                "key": "999",
                                "title": "Angry"
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/sections/1/all" -> {
                    if (request.url.parameters["album.mood"] == "Angry") {
                        respond(
                            content = """
                                {
                                  "MediaContainer": {
                                    "Metadata": [
                                      {
                                        "ratingKey": "a1",
                                        "title": "Tagged Album",
                                        "type": "album",
                                        "parentTitle": "Artist One"
                                      }
                                    ]
                                  }
                                }
                            """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond("""{ "MediaContainer": { "Metadata": [] } }""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example", owned = true)
        val library = MusicLibrary("1", "Music")

        val choice = client.collectionFilterChoices(server, library, PlexCollectionTarget.Albums, PlexCollectionFacet.Mood, "token").single()
        val items = client.collectionFilterItems(server, library, PlexCollectionTarget.Albums, PlexCollectionFacet.Mood, choice, "token")

        assertEquals("999", choice.key)
        assertEquals("album.mood", choice.filterField)
        assertEquals(listOf("a1"), items)
    }

    @Test
    fun tracksForCollectionFacetPageUsesTrackFilterPaths() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all" -> {
                    val mood = request.url.parameters["album.mood"] ?: request.url.parameters["mood"]
                    if (request.url.parameters["type"] == "10" && mood == "Angry") {
                        respond(
                            content = """
                                {
                                  "MediaContainer": {
                                    "offset": 0,
                                    "size": 1,
                                    "totalSize": 1,
                                    "Metadata": [
                                      {
                                        "ratingKey": "t1",
                                        "title": "Mood Song",
                                        "type": "track",
                                        "parentTitle": "Artist One",
                                        "grandparentTitle": "Artist One",
                                        "parentRatingKey": "a1",
                                        "duration": 180000,
                                        "Media": [
                                          {
                                            "Part": [
                                              {
                                                "key": "/library/parts/t1/file.flac"
                                              }
                                            ]
                                          }
                                        ]
                                      }
                                    ]
                                  }
                                }
                            """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond("""{ "MediaContainer": { "Metadata": [] } }""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example", owned = true)
        val library = MusicLibrary("1", "Music")
        val choice = PlexFilterChoice(
            key = "Angry",
            title = "Angry",
            fastKey = null,
            filterField = "album.mood",
        )

        val page = client.tracksForCollectionFacetPage(
            server = server,
            library = library,
            token = "token",
            facet = PlexCollectionFacet.Mood,
            choice = choice,
            start = 0,
            size = 50,
        )

        assertEquals(1, page.tracks.size)
        assertEquals("Mood Song", page.tracks.single().title)
    }

    @Test
    fun normalizesPlexFilterExpressionChoiceKeys() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/mood" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Directory": [
                              {
                                "key": "mood=999",
                                "title": "Angry"
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/sections/1/all" -> {
                    if (request.url.parameters["mood"] == "999") {
                        respond(
                            content = """
                                {
                                  "MediaContainer": {
                                    "Metadata": [
                                      {
                                        "ratingKey": "a1",
                                        "title": "Tagged Album",
                                        "type": "album"
                                      }
                                    ]
                                  }
                                }
                            """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond("""{ "MediaContainer": { "Metadata": [] } }""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example", owned = true)
        val library = MusicLibrary("1", "Music")

        val choice = client.collectionFilterChoices(server, library, PlexCollectionTarget.Albums, PlexCollectionFacet.Mood, "token").single()
        val items = client.collectionFilterItems(server, library, PlexCollectionTarget.Albums, PlexCollectionFacet.Mood, choice, "token")

        assertEquals("999", choice.key)
        assertEquals(listOf("a1"), items)
    }

    @Test
    fun mapsPlexAlbumMoodTrackResultsToParentAlbums() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/album.mood" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Directory": [
                              {
                                "key": "999",
                                "title": "Angry"
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/sections/1/all" -> {
                    if (request.url.parameters["album.mood"] == "Angry") {
                        respond(
                            content = """
                                {
                                  "MediaContainer": {
                                    "Metadata": [
                                      {
                                        "ratingKey": "t1",
                                        "type": "track",
                                        "title": "Angry Song",
                                        "parentRatingKey": "a1",
                                        "parentTitle": "Tagged Album"
                                      }
                                    ]
                                  }
                                }
                            """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond("""{ "MediaContainer": { "Metadata": [] } }""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example", owned = true)
        val library = MusicLibrary("1", "Music")

        val choice = client.collectionFilterChoices(server, library, PlexCollectionTarget.Albums, PlexCollectionFacet.Mood, "token").single()
        val items = client.collectionFilterItems(server, library, PlexCollectionTarget.Albums, PlexCollectionFacet.Mood, choice, "token")

        assertEquals(listOf("a1"), items)
    }

    @Test
    fun mapsPlexTrackMoodFilterChoiceToAlbums() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/album.mood" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Directory": [
                              {
                                "key": "999",
                                "title": "Angry"
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/sections/1/all" -> {
                    if (request.url.parameters["type"] == "10" && request.url.parameters["track.mood"] == "Angry") {
                        respond(
                            content = """
                                {
                                  "MediaContainer": {
                                    "Metadata": [
                                      {
                                        "ratingKey": "t1",
                                        "type": "track",
                                        "title": "Angry Song",
                                        "parentRatingKey": "a1",
                                        "parentTitle": "Tagged Album"
                                      }
                                    ]
                                  }
                                }
                            """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond("""{ "MediaContainer": { "Metadata": [] } }""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example", owned = true)
        val library = MusicLibrary("1", "Music")

        val choice = client.collectionFilterChoices(server, library, PlexCollectionTarget.Albums, PlexCollectionFacet.Mood, "token").single()
        val items = client.collectionFilterItems(server, library, PlexCollectionTarget.Albums, PlexCollectionFacet.Mood, choice, "token")

        assertEquals(listOf("a1"), items)
    }

    @Test
    fun ignoresUntypedPlexAlbumMoodFastKeyBeforeTypedAlbumQuery() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/album.mood" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Directory": [
                              {
                                "key": "50157",
                                "title": "Acerbic",
                                "fastKey": "/library/sections/1/all?mood=50157"
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/sections/1/all" -> {
                    when {
                        request.url.parameters["type"] == "9" && request.url.parameters["mood"] == "50157" -> respond(
                            content = """
                                {
                                  "MediaContainer": {
                                    "Metadata": [
                                      {
                                        "ratingKey": "a1",
                                        "title": "Album One",
                                        "type": "album"
                                      }
                                    ]
                                  }
                                }
                            """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                        request.url.parameters["mood"] == "50157" -> respond(
                            content = """
                                {
                                  "MediaContainer": {
                                    "Metadata": [
                                      {
                                        "ratingKey": "t1",
                                        "title": "Track One",
                                        "type": "track",
                                        "parentRatingKey": "a1"
                                      }
                                    ]
                                  }
                                }
                            """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                        else -> respond("""{ "MediaContainer": { "Metadata": [] } }""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example", owned = true)
        val library = MusicLibrary("1", "Music")

        val choice = client.collectionFilterChoices(server, library, PlexCollectionTarget.Albums, PlexCollectionFacet.Mood, "token").single()
        val items = client.collectionFilterItems(server, library, PlexCollectionTarget.Albums, PlexCollectionFacet.Mood, choice, "token")

        assertEquals(listOf("a1"), items)
    }

    @Test
    fun plexCatalogSkipsCollectionFilterValuesDuringGeneralSync() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/sections/1/all" -> when {
                    request.url.parameters["includeMeta"] == "1" -> respond(
                        content = """
                            {
                              "MediaContainer": {
                                "Meta": {
                                  "Type": [
                                    {
                                      "type": "album",
                                      "Filter": [
                                        {
                                          "filter": "style",
                                          "key": "/library/sections/1/style?type=9",
                                          "title": "Style",
                                          "type": "filter"
                                        }
                                      ]
                                    }
                                  ]
                                }
                              }
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    request.url.parameters["style"] == "42" -> respond(
                        content = """
                            {
                              "MediaContainer": {
                                "Directory": [
                                  {
                                    "ratingKey": "a1",
                                    "key": "/library/metadata/a1/children",
                                    "title": "Tagged Album",
                                    "type": "album",
                                    "parentTitle": "Artist One",
                                    "thumb": "/library/metadata/a1/thumb"
                                  }
                                ]
                              }
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    else -> respond(
                        content = """
                            {
                              "MediaContainer": {
                                "Metadata": [
                                  {
                                    "ratingKey": "ar1",
                                    "title": "Artist One",
                                    "type": "artist",
                                    "thumb": "/library/metadata/ar1/thumb"
                                  }
                                ]
                              }
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                "/library/sections/1/albums" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Directory": [
                              {
                                "ratingKey": "a1",
                                "key": "/library/metadata/a1/children",
                                "title": "Tagged Album",
                                "type": "album",
                                "parentTitle": "Artist One",
                                "thumb": "/library/metadata/a1/thumb"
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/sections/1/style" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Directory": [
                              {
                                "key": "42",
                                "title": "Acid Jazz",
                                "fastKey": "/library/sections/1/all?type=9&style=42"
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/metadata/ar1" -> respond(
                    content = """{ "MediaContainer": { "Metadata": [ { "ratingKey": "ar1", "title": "Artist One", "type": "artist" } ] } }""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/metadata/a1" -> respond(
                    content = """{ "MediaContainer": { "Metadata": [ { "ratingKey": "a1", "title": "Tagged Album", "parentTitle": "Artist One", "type": "album" } ] } }""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/metadata/a1/children" -> respond(
                    content = """
                        {
                          "MediaContainer": {
                            "Metadata": [
                              {
                                "ratingKey": "t1",
                                "title": "Song",
                                "grandparentTitle": "Artist One",
                                "parentTitle": "Tagged Album",
                                "duration": 1000,
                                "Media": [
                                  { "Part": [ { "key": "/library/parts/t1/file.mp3", "file": "file.mp3" } ] }
                                ]
                              }
                            ]
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/playlists" -> respond(
                    content = """{ "MediaContainer": { "Metadata": [] } }""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val httpClient = testHttpClient(engine)
        val catalog = PlexCatalogBuilder(PlexClient.withoutResolver(httpClient), httpClient).buildCatalog(
            PlexServer("server", "Plex", "https://plex.example", owned = true),
            MusicLibrary("1", "Music"),
            "token",
        )

        assertEquals(emptyList(), catalog.collectionValues)
        assertEquals(emptyList(), catalog.collectionTags)
    }
}
