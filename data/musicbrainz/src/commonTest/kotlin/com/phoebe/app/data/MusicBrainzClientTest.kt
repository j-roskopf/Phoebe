package com.phoebe.app.data

import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MusicBrainzClientTest {
    @Test
    fun albumMetadataSendsRequestParametersAndParsesResponse() = runTest {
        var path = ""
        var queryAlbum: String? = null
        var queryArtist: String? = null
        var queryYear: String? = null
        var releaseMbids: List<String>? = null
        val client = clientFor { request ->
            path = request.url.encodedPath
            queryAlbum = request.url.parameters["album"]
            queryArtist = request.url.parameters["artist"]
            queryYear = request.url.parameters["year"]
            releaseMbids = request.url.parameters.getAll("releaseMbid")
            respondJson(
                """
                {
                  "query": {
                    "album": "Punisher",
                    "artist": "Phoebe Bridgers",
                    "year": 2020,
                    "releaseMbids": ["release-1", "release-2"]
                  },
                  "match": {
                    "musicBrainzId": "release-1",
                    "releaseGroupId": "group-1",
                    "title": "Punisher",
                    "artist": "Phoebe Bridgers",
                    "year": 2020,
                    "score": 100
                  },
                  "credits": [
                    { "role": "Label", "names": ["Dead Oceans"] }
                  ],
                  "artwork": [
                    { "id": "art-1", "imageUrl": "https://img.example/front.jpg", "types": ["Front"], "front": true }
                  ]
                }
                """.trimIndent(),
            )
        }

        val response = client.albumMetadata(
            baseUrl = "https://backend.example/",
            album = "Punisher",
            artist = "Phoebe Bridgers",
            year = 2020,
            releaseMbids = listOf("release-1", "release-2"),
        )

        assertEquals("/v1/musicbrainz/album", path)
        assertEquals("Punisher", queryAlbum)
        assertEquals("Phoebe Bridgers", queryArtist)
        assertEquals("2020", queryYear)
        assertEquals(listOf("release-1", "release-2"), releaseMbids)
        assertEquals("release-1", response.match?.musicBrainzId)
        assertEquals("Label", response.credits.single().role)
        assertEquals("https://img.example/front.jpg", response.artwork.single().imageUrl)
    }

    @Test
    fun artistArtworkCoercesLimitAndParsesResponse() = runTest {
        var limit: String? = null
        var fast: String? = null
        var excludedArtworkUrls: List<String>? = null
        val client = clientFor { request ->
            limit = request.url.parameters["limit"]
            fast = request.url.parameters["fast"]
            excludedArtworkUrls = request.url.parameters.getAll("excludeImageUrl")
            respondJson(
                """
                {
                  "artist": "Phoebe Bridgers",
                  "match": {
                    "musicBrainzId": "artist-1",
                    "title": "Phoebe Bridgers",
                    "artist": "Phoebe Bridgers",
                    "score": 100
                  },
                  "artwork": [
                    { "id": "art-1", "imageUrl": "https://img.example/front.jpg", "types": ["Front"], "source": "Punisher" }
                  ]
                }
                """.trimIndent(),
            )
        }

        val response = client.artistArtwork(
            baseUrl = "https://backend.example",
            artist = "Phoebe Bridgers",
            limit = 99,
            excludedArtworkUrls = listOf("https://img.example/current.jpg"),
        )

        assertEquals("24", limit)
        assertEquals("true", fast)
        assertEquals(listOf("https://img.example/current.jpg"), excludedArtworkUrls)
        assertEquals("artist-1", response.match?.musicBrainzId)
        assertEquals("Punisher", response.artwork.single().source)
    }

    @Test
    fun artistArtworkUsesFastDefaultLimit() = runTest {
        var limit: String? = null
        var fast: String? = null
        val client = clientFor { request ->
            limit = request.url.parameters["limit"]
            fast = request.url.parameters["fast"]
            respondJson("""{"artist":"Phoebe Bridgers","artwork":[]}""")
        }

        client.artistArtwork(
            baseUrl = "https://backend.example",
            artist = "Phoebe Bridgers",
        )

        assertEquals("12", limit)
        assertEquals("true", fast)
    }

    @Test
    fun backendErrorIncludesStatusAndBodySnippet() = runTest {
        val client = clientFor {
            respond(
                content = """{"error":"upstream failed"}""",
                status = HttpStatusCode.BadGateway,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val error = assertFailsWith<IllegalStateException> {
            client.artistArtwork(
                baseUrl = "https://backend.example",
                artist = "Phoebe Bridgers",
            )
        }

        assertTrue(error.message.orEmpty().contains("HTTP 502"))
        assertTrue(error.message.orEmpty().contains("upstream failed"))
    }

    private fun clientFor(handler: MockEngine) =
        MusicBrainzClient(testHttpClient(handler))

    private fun clientFor(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        clientFor(MockEngine(handler))

    private fun MockRequestHandleScope.respondJson(content: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(
            content = content,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
}
