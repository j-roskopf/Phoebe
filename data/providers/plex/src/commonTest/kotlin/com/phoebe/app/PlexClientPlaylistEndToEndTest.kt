package com.phoebe.app

import com.phoebe.app.data.PlexClient
import com.phoebe.app.data.plexLibraryStationKeysToTry
import com.phoebe.app.data.plexLibraryStationSlug
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.testing.createdPlaylistJson
import com.phoebe.app.testing.identityJson
import com.phoebe.app.testing.playlistAddResponseJson
import com.phoebe.app.testing.playlistTracksJson
import com.phoebe.app.testing.playlistsJson
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlexClientPlaylistEndToEndTest {
    @Test
    fun createPlaylistPostsToMockPlex() = runTest {
        var capturedMethod: String? = null
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/playlists" -> {
                    capturedMethod = request.method.value
                    respond(
                        content = createdPlaylistJson(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                "/identity" -> respond(
                    content = identityJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)
        val playlist = client.createPlaylist(
            server = server,
            token = "token",
            library = MusicLibrary("1", "Music"),
            machineIdentifier = "server",
            title = "New Mix",
            ratingKeys = listOf("t1"),
        )

        assertEquals("POST", capturedMethod)
        assertEquals("p99", playlist.id)
        assertEquals("New Mix", playlist.title)
    }

    @Test
    fun addTracksToPlaylistUsesPutWithRatingKeys() = runTest {
        var capturedMethod: String? = null
        var capturedUri: String? = null
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/playlists/p1/items" -> {
                    capturedMethod = request.method.value
                    capturedUri = request.url.parameters["uri"]
                    respond(
                        content = playlistAddResponseJson(leafCount = 3),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)
        val added = client.addTracksToPlaylist(
            server = server,
            token = "token",
            machineIdentifier = "server",
            playlistRatingKey = "p1",
            ratingKeys = listOf("t3"),
        )

        assertEquals(HttpMethod.Put.value, capturedMethod)
        assertNotNull(capturedUri)
        assertTrue(capturedUri!!.contains("t3"))
        assertEquals(1, added)
    }

    @Test
    fun movePlaylistItemToTopUsesMoveEndpointWithoutAfter() = runTest {
        var capturedMethod: String? = null
        var capturedAfter: String? = "unset"
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/playlists/p1/items/103/move" -> {
                    capturedMethod = request.method.value
                    capturedAfter = request.url.parameters["after"]
                    respond(
                        content = playlistAddResponseJson(leafCount = 3),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)

        client.movePlaylistItemToTop(
            server = server,
            token = "token",
            playlistRatingKey = "p1",
            playlistItemId = 103,
        )

        assertEquals(HttpMethod.Put.value, capturedMethod)
        assertEquals(null, capturedAfter)
    }

    @Test
    fun movePlaylistItemCanMoveAfterAnotherItem() = runTest {
        var capturedMethod: String? = null
        var capturedAfter: String? = null
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/playlists/p1/items/103/move" -> {
                    capturedMethod = request.method.value
                    capturedAfter = request.url.parameters["after"]
                    respond(
                        content = playlistAddResponseJson(leafCount = 3),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)

        client.movePlaylistItem(
            server = server,
            token = "token",
            playlistRatingKey = "p1",
            playlistItemId = 103,
            afterPlaylistItemId = 101,
        )

        assertEquals(HttpMethod.Put.value, capturedMethod)
        assertEquals("101", capturedAfter)
    }

    @Test
    fun playlistTracksParsesItemsFromMockPlex() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/playlists" -> respond(
                    content = playlistsJson(trackCount = 2),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/playlists/p1/items" -> respond(
                    content = playlistTracksJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)
        val playlists = client.playlists(server, "token")
        val tracks = client.playlistTracks(server, playlists.single(), "token")

        assertEquals(listOf("t1", "t2"), tracks.map { it.id })
        assertEquals(listOf("Playlist Song One", "Playlist Song Two"), tracks.map { it.title })
    }

    @Test
    fun playlistsRequestsAudioPlaylistsOnly() = runTest {
        var capturedPlaylistType: String? = null
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/playlists" -> {
                    capturedPlaylistType = request.url.parameters["playlistType"]
                    respond(
                        content = playlistsJson(trackCount = 2),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)
        client.playlists(server, "token")

        assertEquals("audio", capturedPlaylistType)
    }

    @Test
    fun rateItemUsesPutWithDoubledRating() = runTest {
        var capturedMethod: String? = null
        var capturedIdentifier: String? = null
        var capturedKey: String? = null
        var capturedRating: String? = null
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/:/rate" -> {
                    capturedMethod = request.method.value
                    capturedIdentifier = request.url.parameters["identifier"]
                    capturedKey = request.url.parameters["key"]
                    capturedRating = request.url.parameters["rating"]
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/html"),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)

        client.rateItem(server, "token", "t1", 3.5f)

        assertEquals(HttpMethod.Put.value, capturedMethod)
        assertEquals("com.plexapp.plugins.library", capturedIdentifier)
        assertEquals("t1", capturedKey)
        assertEquals("7.0", capturedRating)
    }

    @Test
    fun setFavoriteArtistCollectionAddsManagedCollectionTag() = runTest {
        var capturedMethod: String? = null
        var capturedType: String? = null
        var capturedId: String? = null
        var capturedLocked: String? = null
        var capturedCollection: String? = null
        var capturedExistingCollection: String? = null
        var metadataReads = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/metadata/a1" -> {
                    metadataReads++
                    val collectionJson = if (metadataReads == 1) {
                        """[ { "tag": "Road Trips" } ]"""
                    } else {
                        """[ { "tag": "Road Trips" }, { "tag": "Favorite Artists" } ]"""
                    }
                    respond(
                        """{ "MediaContainer": { "Metadata": [ { "ratingKey": "a1", "title": "Artist", "Collection": $collectionJson } ] } }""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                "/library/sections/1/all" -> {
                    capturedMethod = request.method.value
                    capturedType = request.url.parameters["type"]
                    capturedId = request.url.parameters["id"]
                    capturedLocked = request.url.parameters["collection.locked"]
                    capturedExistingCollection = request.url.parameters["collection[0].tag.tag"]
                    capturedCollection = request.url.parameters["collection[1].tag.tag"]
                    respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)

        client.setFavoriteArtistCollection(server, "token", MusicLibrary("1", "Music"), "a1", favorite = true)

        assertEquals(HttpMethod.Put.value, capturedMethod)
        assertEquals("8", capturedType)
        assertEquals("a1", capturedId)
        assertEquals("1", capturedLocked)
        assertEquals("Road Trips", capturedExistingCollection)
        assertEquals("Favorite Artists", capturedCollection)
    }

    @Test
    fun setFavoriteAlbumCollectionRemovesManagedCollectionTag() = runTest {
        var capturedType: String? = null
        var capturedId: String? = null
        var capturedRemovedCollection: String? = null
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/metadata/b1" -> respond(
                    """{ "MediaContainer": { "Metadata": [ { "ratingKey": "b1", "title": "Album", "Collection": [] } ] } }""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/sections/1/all" -> {
                    capturedType = request.url.parameters["type"]
                    capturedId = request.url.parameters["id"]
                    capturedRemovedCollection = request.url.parameters["collection[].tag.tag-"]
                    respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)

        client.setFavoriteAlbumCollection(server, "token", MusicLibrary("1", "Music"), "b1", favorite = false)

        assertEquals("9", capturedType)
        assertEquals("b1", capturedId)
        assertEquals("Favorite Albums,Phoebe Favorite Albums", capturedRemovedCollection)
    }

    @Test
    fun setFavoriteAlbumCollectionAddsParentArtistId() = runTest {
        var capturedArtistId: String? = null
        var capturedCollection: String? = null
        var capturedCreatedUri: String? = null
        var capturedCreatedType: String? = null
        var capturedCreatedTitle: String? = null
        var capturedCreatedSection: String? = null
        var capturedAddedUri: String? = null
        var metadataReads = 0
        var collectionItemAdded = false
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/identity" -> respond(
                    """{ "MediaContainer": { "machineIdentifier": "machine1" } }""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/sections/1/collections" -> respond(
                    """{ "MediaContainer": { "Directory": [] } }""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/metadata/b1" -> {
                    metadataReads++
                    val collectionJson = if (metadataReads == 1) {
                        """[]"""
                    } else {
                        """[ { "tag": "Favorite Albums" } ]"""
                    }
                    respond(
                        """{ "MediaContainer": { "Metadata": [ { "ratingKey": "b1", "title": "Album", "parentRatingKey": "ar1", "Collection": $collectionJson } ] } }""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                "/library/sections/1/all" -> {
                    capturedArtistId = request.url.parameters["artist.id.value"]
                    capturedCollection = request.url.parameters["collection[0].tag.tag"]
                    respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                }
                "/library/collections" -> {
                    capturedCreatedUri = request.url.parameters["uri"]
                    capturedCreatedType = request.url.parameters["type"]
                    capturedCreatedTitle = request.url.parameters["title"]
                    capturedCreatedSection = request.url.parameters["sectionId"]
                    respond(
                        """{ "MediaContainer": { "Metadata": [ { "ratingKey": "c1", "key": "/library/collections/c1/children", "type": "collection", "title": "Favorite Albums" } ] } }""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                "/library/collections/c1/children" -> respond(
                    if (collectionItemAdded) {
                        """{ "MediaContainer": { "Metadata": [ { "ratingKey": "b1", "title": "Album", "type": "album" } ] } }"""
                    } else {
                        """{ "MediaContainer": { "Metadata": [] } }"""
                    },
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/collections/c1/items" -> {
                    capturedAddedUri = request.url.parameters["uri"]
                    collectionItemAdded = true
                    respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)

        client.setFavoriteAlbumCollection(server, "token", MusicLibrary("1", "Music"), "b1", favorite = true)

        assertEquals("ar1", capturedArtistId)
        assertEquals("Favorite Albums", capturedCollection)
        assertEquals("server://machine1/com.plexapp.plugins.library/library/metadata/b1", capturedCreatedUri)
        assertEquals("9", capturedCreatedType)
        assertEquals("Favorite Albums", capturedCreatedTitle)
        assertEquals("1", capturedCreatedSection)
        assertEquals("server://machine1/com.plexapp.plugins.library/library/metadata/b1", capturedAddedUri)
    }

    @Test
    fun musicStationsReadsPlexStationHub() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/hubs/sections/1" -> respond(
                    """{
                        "MediaContainer": {
                          "Hub": [
                            {
                              "title": "Stations",
                              "context": "hub.music.stations",
                              "Metadata": [
                                {
                                  "ratingKey": "library-radio",
                                  "key": "/library/sections/1/stations/library",
                                  "title": "Library Radio",
                                  "summary": "Music from this library",
                                  "thumb": "/library/sections/1/thumb"
                                },
                                {
                                  "ratingKey": "deep-cuts",
                                  "key": "/library/sections/1/stations/deepCuts",
                                  "title": "Deep Cuts Radio"
                                }
                              ]
                            }
                          ]
                        }
                    }""".trimIndent(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)

        val stations = client.musicStations(server, MusicLibrary("1", "Music"), "token")

        assertEquals(listOf("Library Radio", "Deep Cuts Radio"), stations.map { it.title })
        assertEquals("/library/sections/1/stations/library", stations.first().key)
        assertEquals("Music from this library", stations.first().subtitle)
        assertNotNull(stations.first().thumbUrl)
    }

    @Test
    fun musicStationsIgnoresArtistRadioInOtherHubs() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/hubs/sections/1" -> respond(
                    """{
                        "MediaContainer": {
                          "Hub": [
                            {
                              "title": "Recommended Artists",
                              "context": "hub.music.recommended",
                              "Metadata": [
                                {
                                  "ratingKey": "radiohead",
                                  "key": "/library/metadata/123/station/abc-uuid",
                                  "title": "Radiohead",
                                  "type": "artist"
                                }
                              ]
                            },
                            {
                              "title": "Stations",
                              "context": "hub.music.stations",
                              "Metadata": [
                                {
                                  "ratingKey": "library-radio",
                                  "key": "/library/sections/1/stations/library",
                                  "title": "Library Radio"
                                },
                                {
                                  "ratingKey": "deep-cuts",
                                  "key": "/library/sections/1/stations/deepCuts",
                                  "title": "Deep Cuts Radio"
                                },
                                {
                                  "ratingKey": "time-travel",
                                  "key": "/library/sections/1/stations/timeTravel",
                                  "title": "Time Travel Radio"
                                }
                              ]
                            }
                          ]
                        }
                    }""".trimIndent(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)

        val stations = client.musicStations(server, MusicLibrary("1", "Music"), "token")

        assertEquals(
            listOf("Library Radio", "Deep Cuts Radio", "Time Travel Radio"),
            stations.map { it.title },
        )
    }

    @Test
    fun artistStationReadsIncludeStationsResponse() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/metadata/a1" -> {
                    assertEquals("1", request.url.parameters["includeStations"])
                    respond(
                        """{
                            "MediaContainer": {
                              "Metadata": [
                                {
                                  "ratingKey": "a1",
                                  "title": "Artist",
                                  "Stations": [
                                    {
                                      "ratingKey": "artist-radio",
                                      "key": "/library/metadata/a1/station/uuid?type=10",
                                      "title": "Artist Radio"
                                    }
                                  ]
                                }
                              ]
                            }
                        }""".trimIndent(),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)

        val station = client.artistStation(server, "a1", "token")

        assertNotNull(station)
        assertEquals("Artist Radio", station.title)
        assertEquals("/library/metadata/a1/station/uuid?type=10", station.key)
    }

    @Test
    fun similarArtistsForArtistReadsPlexSimilarEndpoint() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/metadata/a1/similar" -> {
                    assertEquals("10", request.url.parameters["count"])
                    respond(
                        """{
                            "MediaContainer": {
                              "Metadata": [
                                {
                                  "ratingKey": "a2",
                                  "key": "/library/metadata/a2",
                                  "type": "artist",
                                  "title": "The Front Bottoms",
                                  "thumb": "/library/metadata/a2/thumb/1",
                                  "leafCount": 4
                                },
                                {
                                  "ratingKey": "album-1",
                                  "type": "album",
                                  "title": "Ignored Album"
                                }
                              ]
                            }
                        }""".trimIndent(),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)

        val artists = client.similarArtistsForArtist(server, "a1", "token", limit = 10)

        assertEquals(1, artists.size)
        assertEquals("a2", artists.single().id)
        assertEquals("The Front Bottoms", artists.single().title)
        assertTrue(artists.single().thumbUrl?.contains("X-Plex-Token=token") == true)
    }

    @Test
    fun artistStationReadsTopLevelStationsResponse() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/metadata/a1" -> respond(
                    """{
                        "MediaContainer": {
                          "Directory": [
                            {
                              "ratingKey": "a1",
                              "key": "/library/metadata/a1",
                              "title": "Artist",
                              "Stations": [
                                {
                                  "ratingKey": "artist-radio",
                                  "key": "/library/metadata/a1/station/uuid?type=10",
                                  "title": "Artist Radio"
                                }
                              ]
                            }
                          ]
                        }
                    }""".trimIndent(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)

        val station = client.artistStation(server, "a1", "token")

        assertNotNull(station)
        assertEquals("Artist Radio", station.title)
        assertEquals("/library/metadata/a1/station/uuid?type=10", station.key)
    }

    @Test
    fun artistStationReadsWrappedPlaylistStationResponse() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/metadata/a1" -> respond(
                    """{
                        "MediaContainer": {
                          "Metadata": [
                            {
                              "ratingKey": "a1",
                              "key": "/library/metadata/a1",
                              "title": "Guns N' Roses",
                              "Stations": {
                                "Playlist": [
                                  {
                                    "ratingKey": "artist-radio",
                                    "key": "/library/metadata/a1/station/uuid?type=10",
                                    "title": "Guns N' Roses Radio",
                                    "summary": "Radio based on Guns N' Roses"
                                  }
                                ]
                              }
                            }
                          ]
                        }
                    }""".trimIndent(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)

        val station = client.artistStation(server, "a1", "token")

        assertNotNull(station)
        assertEquals("Guns N' Roses Radio", station.title)
        assertEquals("Radio based on Guns N' Roses", station.subtitle)
        assertEquals("/library/metadata/a1/station/uuid?type=10", station.key)
    }

    @Test
    fun artistStationTrustsPlaylistInsideStationsWrapper() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/metadata/a1" -> respond(
                    """{
                        "MediaContainer": {
                          "Metadata": [
                            {
                              "ratingKey": "a1",
                              "key": "/library/metadata/a1",
                              "title": "Guns N' Roses",
                              "Stations": {
                                "Playlist": [
                                  {
                                    "ratingKey": "artist-radio",
                                    "key": "/playlists/artist-radio/items",
                                    "title": "Guns N' Roses"
                                  }
                                ]
                              }
                            }
                          ]
                        }
                    }""".trimIndent(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)

        val station = client.artistStation(server, "a1", "token")

        assertNotNull(station)
        assertEquals("Guns N' Roses", station.title)
        assertEquals("/playlists/artist-radio/items", station.key)
    }

    @Test
    fun createStationPlayQueuePostsStationUriAndReturnsTracks() = runTest {
        var capturedType: String? = null
        var capturedUri: String? = null
        var capturedContinuous: String? = null
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/playQueues" -> {
                    capturedType = request.url.parameters["type"]
                    capturedUri = request.url.parameters["uri"]
                    capturedContinuous = request.url.parameters["continuous"]
                    respond(
                        """{
                            "MediaContainer": {
                              "playQueueID": 42,
                              "Metadata": [
                                {
                                  "ratingKey": "t1",
                                  "key": "/library/metadata/t1",
                                  "title": "Radio Song",
                                  "type": "track",
                                  "parentTitle": "Radio Album",
                                  "grandparentTitle": "Radio Artist",
                                  "duration": 123000,
                                  "Media": [
                                    {
                                      "audioCodec": "flac",
                                      "Part": [ { "key": "/library/parts/t1/file.flac", "file": "/music/t1.flac" } ]
                                    }
                                  ]
                                }
                              ]
                            }
                        }""".trimIndent(),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)

        val tracks = client.createStationPlayQueue(server, "token", "machine1", "/library/sections/1/stations/library")

        assertEquals("audio", capturedType)
        assertEquals("1", capturedContinuous)
        assertEquals("server://machine1/com.plexapp.plugins.library/library/sections/1/stations/library", capturedUri)
        assertEquals(1, tracks.size)
        assertEquals("t1", tracks.single().id)
        assertEquals("Radio Song", tracks.single().title)
    }

    @Test
    fun createStationPlayQueueFetchesExpandedPlayQueueWindow() = runTest {
        var capturedWindow: String? = null
        var capturedCenter: String? = null
        var capturedIncludeBefore: String? = null
        var capturedIncludeAfter: String? = null
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/playQueues" -> respond(
                    """{
                        "MediaContainer": {
                          "playQueueID": 42,
                          "playQueueSelectedItemID": 123,
                          "playQueueTotalCount": 3,
                          "size": 1,
                          "Metadata": [
                            {
                              "ratingKey": "t1",
                              "playQueueItemID": 123,
                              "key": "/library/metadata/t1",
                              "title": "One",
                              "type": "track",
                              "parentTitle": "Radio Album",
                              "grandparentTitle": "Radio Artist",
                              "duration": 1000,
                              "Media": [
                                { "Part": [ { "key": "/library/parts/t1/file.flac" } ] }
                              ]
                            }
                          ]
                        }
                    }""".trimIndent(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/playQueues/42" -> {
                    capturedWindow = request.url.parameters["window"]
                    capturedCenter = request.url.parameters["center"]
                    capturedIncludeBefore = request.url.parameters["includeBefore"]
                    capturedIncludeAfter = request.url.parameters["includeAfter"]
                    respond(
                        """{
                            "MediaContainer": {
                              "playQueueID": 42,
                              "playQueueTotalCount": 3,
                              "size": 3,
                              "Metadata": [
                                {
                                  "ratingKey": "t1",
                                  "playQueueItemID": 123,
                                  "key": "/library/metadata/t1",
                                  "title": "One",
                                  "type": "track",
                                  "parentTitle": "Radio Album",
                                  "grandparentTitle": "Radio Artist",
                                  "duration": 1000,
                                  "Media": [
                                    { "Part": [ { "key": "/library/parts/t1/file.flac" } ] }
                                  ]
                                },
                                {
                                  "ratingKey": "t2",
                                  "playQueueItemID": 124,
                                  "key": "/library/metadata/t2",
                                  "title": "Two",
                                  "type": "track",
                                  "parentTitle": "Radio Album",
                                  "grandparentTitle": "Radio Artist",
                                  "duration": 1000,
                                  "Media": [
                                    { "Part": [ { "key": "/library/parts/t2/file.flac" } ] }
                                  ]
                                },
                                {
                                  "ratingKey": "t3",
                                  "playQueueItemID": 125,
                                  "key": "/library/metadata/t3",
                                  "title": "Three",
                                  "type": "track",
                                  "parentTitle": "Radio Album",
                                  "grandparentTitle": "Radio Artist",
                                  "duration": 1000,
                                  "Media": [
                                    { "Part": [ { "key": "/library/parts/t3/file.flac" } ] }
                                  ]
                                }
                              ]
                            }
                        }""".trimIndent(),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)

        val tracks = client.createStationPlayQueue(server, "token", "machine1", "/library/sections/1/stations/library")

        assertEquals(listOf("t1", "t2", "t3"), tracks.map { it.id })
        assertEquals("200", capturedWindow)
        assertEquals("123", capturedCenter)
        assertEquals("1", capturedIncludeBefore)
        assertEquals("1", capturedIncludeAfter)
    }

    @Test
    fun createStationPlayQueueBatchHydratesMissingMedia() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/playQueues" -> respond(
                    """{
                        "MediaContainer": {
                          "playQueueID": 42,
                          "Metadata": [
                            { "ratingKey": "t1", "title": "One", "type": "track", "duration": 1000 },
                            { "ratingKey": "t2", "title": "Two", "type": "track", "duration": 1000 }
                          ]
                        }
                    }""".trimIndent(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.url.encodedPath == "/library/metadata/t1,t2" -> respond(
                    """{
                        "MediaContainer": {
                          "Metadata": [
                            {
                              "ratingKey": "t1",
                              "title": "One",
                              "type": "track",
                              "duration": 1000,
                              "Media": [
                                { "Part": [ { "key": "/library/parts/t1/file.flac" } ] }
                              ]
                            },
                            {
                              "ratingKey": "t2",
                              "title": "Two",
                              "type": "track",
                              "duration": 1000,
                              "Media": [
                                { "Part": [ { "key": "/library/parts/t2/file.flac" } ] }
                              ]
                            }
                          ]
                        }
                    }""".trimIndent(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)

        val tracks = client.createStationPlayQueue(server, "token", "machine1", "/library/sections/1/stations/deepCuts")

        assertEquals(listOf("t1", "t2"), tracks.map { it.id })
    }

    @Test
    fun createStationPlayQueueHydratesTracksMissingMedia() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/playQueues" -> respond(
                    """{
                        "MediaContainer": {
                          "playQueueID": 42,
                          "Metadata": [
                            {
                              "ratingKey": "t1",
                              "key": "/library/metadata/t1",
                              "title": "Radio Song",
                              "type": "track",
                              "parentTitle": "Radio Album",
                              "grandparentTitle": "Radio Artist",
                              "duration": 123000
                            }
                          ]
                        }
                    }""".trimIndent(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/library/metadata/t1" -> respond(
                    """{
                        "MediaContainer": {
                          "Metadata": [
                            {
                              "ratingKey": "t1",
                              "key": "/library/metadata/t1",
                              "title": "Radio Song",
                              "type": "track",
                              "parentTitle": "Radio Album",
                              "grandparentTitle": "Radio Artist",
                              "duration": 123000,
                              "Media": [
                                {
                                  "audioCodec": "flac",
                                  "Part": [ { "key": "/library/parts/t1/file.flac", "file": "/music/t1.flac" } ]
                                }
                              ]
                            }
                          ]
                        }
                    }""".trimIndent(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)

        val tracks = client.createStationPlayQueue(server, "token", "machine1", "/library/sections/1/stations/library")

        assertEquals(1, tracks.size)
        assertEquals("t1", tracks.single().id)
        assertTrue(tracks.single().streamUrl.contains("/library/parts/t1/file.flac"))
    }

    @Test
    fun plexLibraryStationKeysToTryIncludesNumericFallback() {
        assertEquals(
            listOf(
                "/library/sections/3/stations/deepCuts",
                "/library/sections/3/stations/8",
            ),
            plexLibraryStationKeysToTry("/library/sections/3/stations/deepCuts"),
        )
    }

    @Test
    fun playlistsParsesCompositeThumbFromMockPlex() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/playlists" -> respond(
                    content = """
                    {
                      "MediaContainer": {
                        "Metadata": [
                          {
                            "ratingKey": "p1",
                            "title": "Playlist One",
                            "leafCount": 5,
                            "key": "/playlists/p1/items",
                            "composite": "/playlists/p1/composite/12345"
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
        val server = PlexServer("server", "Plex", "https://plex.example:32400", owned = true)
        val playlists = client.playlists(server, "token")

        assertEquals(1, playlists.size)
        val playlist = playlists.single()
        assertEquals("p1", playlist.id)
        assertNotNull(playlist.thumbUrl)
        assertTrue(playlist.thumbUrl!!.contains("/playlists/p1/composite/12345"))
    }
}
