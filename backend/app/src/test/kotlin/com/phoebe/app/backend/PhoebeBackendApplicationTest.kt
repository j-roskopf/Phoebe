package com.phoebe.app.backend

import com.phoebe.app.backend.events.ArtistEventsBackendFeature
import com.phoebe.app.backend.lyrics.LyricsBackendFeature
import com.phoebe.app.domain.ArtistEventsResponse
import com.phoebe.app.domain.EventDataProvider
import com.phoebe.app.domain.GeniusReferentsResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhoebeBackendApplicationTest {
    private val testFeatures = listOf(ArtistEventsBackendFeature(), LyricsBackendFeature())
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun healthReturnsOk() = testApplication {
        application {
            phoebeBackendModule(config = testConfig(), httpClient = mockProviderClient("{}"), features = testFeatures)
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("phoebe-backend"))
    }

    @Test
    fun artistEventsNormalizesTicketmasterAndKeepsRawPayload() = testApplication {
        var providerUrl = ""
        var providerQuery = ""
        val providerClient = mockProviderClient(ticketmasterPayload()) { url, query ->
            providerUrl = url
            providerQuery = query
        }
        application {
            phoebeBackendModule(
                config = testConfig(ticketmasterApiKey = "tm-key"),
                httpClient = providerClient,
                features = testFeatures,
            )
        }
        val routeClient = createClient {
            install(ClientContentNegotiation) {
                json(json)
            }
        }

        val response = routeClient.get("/v1/artist-events?provider=ticketmaster&artist=Phoebe%20Bridgers&limit=5")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(providerUrl.contains("ticketmaster.com/discovery/v2/events.json"))
        assertTrue(providerQuery.contains("apikey=tm-key"))
        assertTrue(providerQuery.contains("keyword=Phoebe+Bridgers") || providerQuery.contains("keyword=Phoebe%20Bridgers"))
        val body = json.decodeFromString<ArtistEventsResponse>(response.bodyAsText())
        assertEquals(EventDataProvider.Ticketmaster, body.provider)
        assertEquals("Phoebe Bridgers", body.artist)
        assertEquals(1, body.events.size)
        val event = body.events.single()
        assertEquals("tm-1", event.id)
        assertEquals("Phoebe Bridgers", event.title)
        assertEquals("onsale", event.status)
        assertEquals("2026-08-21", event.date.localDate)
        assertEquals("The Anthem", event.venue?.name)
        assertEquals("USD", event.price?.currency)
        assertEquals("\$40", event.price?.display)
        assertNotNull(event.raw)
    }

    @Test
    fun artistEventsReturnsBadGatewayWhenTicketmasterReturnsError() = testApplication {
        application {
            phoebeBackendModule(
                config = testConfig(ticketmasterApiKey = "tm-key"),
                httpClient = mockProviderClient("""{"fault":"nope"}""", status = HttpStatusCode.Unauthorized),
                features = testFeatures,
            )
        }

        val response = client.get("/v1/artist-events?provider=ticketmaster&artist=Phoebe&limit=1")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertTrue(response.bodyAsText().contains("Ticketmaster API returned HTTP 401"))
    }

    @Test
    fun artistEventsReturnsBadGatewayWhenSeatGeekReturnsError() = testApplication {
        application {
            phoebeBackendModule(
                config = testConfig(seatGeekClientId = "sg-id"),
                httpClient = mockProviderClient("""{"error":"rate limited"}""", status = HttpStatusCode.TooManyRequests),
                features = testFeatures,
            )
        }

        val response = client.get("/v1/artist-events?provider=seatgeek&artist=Phoebe&limit=1")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertTrue(response.bodyAsText().contains("SeatGeek API returned HTTP 429"))
    }

    @Test
    fun artistEventsReturnsServiceUnavailableWhenCredentialsAreMissing() = testApplication {
        application {
            phoebeBackendModule(
                config = testConfig(ticketmasterApiKey = null),
                httpClient = mockProviderClient("{}"),
                features = testFeatures,
            )
        }

        val response = client.get("/v1/artist-events?provider=ticketmaster&artist=Phoebe&limit=1")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("TICKETMASTER_API_KEY"))
    }

    @Test
    fun geniusReferentsSearchesSongAndReturnsPlainAnnotations() = testApplication {
        val providerRequests = mutableListOf<String>()
        val providerAuthHeaders = mutableListOf<String?>()
        val providerClient = HttpClient(
            MockEngine { request ->
                providerRequests += "${request.url.encodedPath}?${request.url.encodedQuery}"
                providerAuthHeaders += request.headers[HttpHeaders.Authorization]
                when (request.url.encodedPath) {
                    "/search" -> respondJson(geniusSearchPayload())
                    "/songs/42" -> respondJson(geniusSongPayload())
                    "/referents" -> when (request.url.parameters["page"]) {
                        "1" -> respondJson(geniusReferentsPayload(nextPage = 2))
                        "2" -> respondJson(geniusReferentsSecondPagePayload())
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        ) {
            install(ClientContentNegotiation) {
                json(json)
            }
        }
        application {
            phoebeBackendModule(
                config = testConfig(geniusAccessToken = "genius-token"),
                httpClient = providerClient,
                features = testFeatures,
            )
        }
        val routeClient = createClient {
            install(ClientContentNegotiation) {
                json(json)
            }
        }

        val response = routeClient.get("/v1/genius/referents?artist=Test%20Artist&title=Test%20Song&album=Test%20Album&durationMs=123000")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(providerAuthHeaders.all { it == "Bearer genius-token" })
        assertTrue(providerRequests.any { it.startsWith("/search?") && it.contains("q=Test+Artist+Test+Song") })
        assertTrue(providerRequests.any { it.contains("/referents?") && it.contains("page=1") })
        assertTrue(providerRequests.any { it.contains("/referents?") && it.contains("page=2") })
        val body = json.decodeFromString<GeniusReferentsResponse>(response.bodyAsText())
        assertEquals(42L, body.song?.id)
        assertEquals("https://genius.com/test-song-lyrics", body.song?.url)
        assertEquals(2, body.referents.size)
        assertEquals("A joined-line annotation.", body.referents.first().annotations.single().body)
        assertEquals("An unmatched annotation.", body.referents.last().annotations.single().body)
    }

    @Test
    fun geniusReferentsStripsFeaturedMetadataBeforeSearch() = testApplication {
        val providerQueries = mutableListOf<String?>()
        val providerClient = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/search" -> {
                        providerQueries += request.url.parameters["q"]
                        respondJson(geniusSearchPayload(songId = 5793983, title = "exile", artist = "Taylor Swift"))
                    }
                    "/songs/5793983" -> respondJson(geniusSongPayload(songId = 5793983, title = "exile", artist = "Taylor Swift"))
                    "/referents" -> respondJson(geniusReferentsPayload(nextPage = null))
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        ) {
            install(ClientContentNegotiation) {
                json(json)
            }
        }
        application {
            phoebeBackendModule(
                config = testConfig(geniusAccessToken = "genius-token"),
                httpClient = providerClient,
                features = testFeatures,
            )
        }
        val routeClient = createClient {
            install(ClientContentNegotiation) {
                json(json)
            }
        }

        val response = routeClient.get("/v1/genius/referents?artist=Taylor%20Swift,%20Bon%20Iver&title=exile%20(feat.%20Bon%20Iver)")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Taylor Swift exile", providerQueries.first())
        assertTrue(providerQueries.none { it?.contains("feat", ignoreCase = true) == true })
        val body = json.decodeFromString<GeniusReferentsResponse>(response.bodyAsText())
        assertEquals(5793983L, body.song?.id)
        assertEquals("exile", body.song?.title)
        assertEquals(1, body.referents.size)
    }

    @Test
    fun geniusReferentsReturnsEmptyAnnotationsWhenReferentsEndpointIsNotFound() = testApplication {
        val providerClient = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/search" -> respondJson(geniusSearchPayload())
                    "/songs/42" -> respondJson(geniusSongPayload())
                    "/referents" -> respond("""{"error":"not found"}""", HttpStatusCode.NotFound)
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        ) {
            install(ClientContentNegotiation) {
                json(json)
            }
        }
        application {
            phoebeBackendModule(
                config = testConfig(geniusAccessToken = "genius-token"),
                httpClient = providerClient,
                features = testFeatures,
            )
        }
        val routeClient = createClient {
            install(ClientContentNegotiation) {
                json(json)
            }
        }

        val response = routeClient.get("/v1/genius/referents?artist=Test%20Artist&title=Test%20Song")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<GeniusReferentsResponse>(response.bodyAsText())
        assertEquals(42L, body.song?.id)
        assertEquals(emptyList(), body.referents)
    }

    @Test
    fun geniusReferentsReturnsServiceUnavailableWhenTokenIsMissing() = testApplication {
        application {
            phoebeBackendModule(
                config = testConfig(geniusAccessToken = null),
                httpClient = mockProviderClient("{}"),
                features = testFeatures,
            )
        }

        val response = client.get("/v1/genius/referents?artist=Test&title=Song")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("GENIUS_ACCESS_TOKEN"))
    }

    @Test
    fun geniusReferentsRequiresArtistAndTitle() = testApplication {
        application {
            phoebeBackendModule(
                config = testConfig(geniusAccessToken = "genius-token"),
                httpClient = mockProviderClient("{}"),
                features = testFeatures,
            )
        }

        val response = client.get("/v1/genius/referents?artist=Test")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("title is required"))
    }

    @Test
    fun corsAllowsConfiguredOriginWithPort() = testApplication {
        application {
            phoebeBackendModule(
                config = testConfig(allowedOrigins = listOf("http://localhost:3000")),
                httpClient = mockProviderClient("{}"),
                features = testFeatures,
            )
        }

        val response = client.get("/health") {
            header(HttpHeaders.Origin, "http://localhost:3000")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("http://localhost:3000", response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    private fun mockProviderClient(
        payload: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        onRequest: (url: String, query: String) -> Unit = { _, _ -> },
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                onRequest(request.url.toString(), request.url.encodedQuery)
                respond(
                    content = payload,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ClientContentNegotiation) {
                json(json)
            }
        }

    private fun MockRequestHandleScope.respondJson(content: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(
            content = content,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )

    private fun testConfig(
        ticketmasterApiKey: String? = "tm-key",
        seatGeekClientId: String? = "sg-id",
        geniusAccessToken: String? = "genius-token",
        allowedOrigins: List<String> = emptyList(),
    ): PhoebeBackendConfig =
        PhoebeBackendConfig(
            ticketmasterApiKey = ticketmasterApiKey,
            seatGeekClientId = seatGeekClientId,
            geniusAccessToken = geniusAccessToken,
            allowedOrigins = allowedOrigins,
            cacheTtlMinutes = 240,
        )

    private fun ticketmasterPayload(): String =
        """
        {
          "_embedded": {
            "events": [
              {
                "id": "tm-1",
                "name": "Phoebe Bridgers",
                "url": "https://tickets.example/tm-1",
                "images": [
                  { "url": "https://images.example/tm-1.jpg", "width": 1200, "height": 675, "ratio": "16_9" }
                ],
                "dates": {
                  "start": {
                    "localDate": "2026-08-21",
                    "localTime": "20:00:00",
                    "dateTime": "2026-08-22T01:00:00Z",
                    "timezone": "America/New_York"
                  },
                  "status": { "code": "onsale" }
                },
                "priceRanges": [
                  { "min": 40.0, "max": 40.0, "currency": "USD" }
                ],
                "_embedded": {
                  "venues": [
                    {
                      "name": "The Anthem",
                      "city": { "name": "Washington" },
                      "state": { "stateCode": "DC" },
                      "country": { "countryCode": "US" },
                      "address": { "line1": "901 Wharf St SW" },
                      "location": { "latitude": "38.880", "longitude": "-77.026" }
                    }
                  ]
                }
              }
            ]
          }
        }
        """.trimIndent()

    private fun geniusSearchPayload(
        songId: Long = 42,
        title: String = "Test Song",
        artist: String = "Test Artist",
    ): String =
        """
        {
          "response": {
            "hits": [
              {
                "result": {
                  "id": $songId,
                  "title": "$title",
                  "url": "https://genius.com/test-song-lyrics",
                  "primary_artist": { "name": "$artist" }
                }
              }
            ]
          }
        }
        """.trimIndent()

    private fun geniusSongPayload(
        songId: Long = 42,
        title: String = "Test Song",
        artist: String = "Test Artist",
    ): String =
        """
        {
          "response": {
            "song": {
              "id": $songId,
              "title": "$title",
              "url": "https://genius.com/test-song-lyrics",
              "primary_artist": { "name": "$artist" }
            }
          }
        }
        """.trimIndent()

    private fun geniusReferentsPayload(nextPage: Int?): String =
        """
        {
          "response": {
            "next_page": ${nextPage?.toString() ?: "null"},
            "referents": [
              {
                "id": 100,
                "fragment": "Hello World",
                "annotations": [
                  {
                    "id": 200,
                    "body": { "plain": "A joined-line annotation." },
                    "authors": [{ "name": "Ada" }],
                    "verified": true,
                    "votes_total": 12,
                    "share_url": "https://genius.com/a/200"
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent()

    private fun geniusReferentsSecondPagePayload(): String =
        """
        {
          "response": {
            "next_page": null,
            "referents": [
              {
                "id": 101,
                "fragment": "Outro fragment",
                "annotations": [
                  {
                    "id": 201,
                    "body": { "plain": "An unmatched annotation." },
                    "authors": [{ "name": "Grace" }],
                    "verified": false,
                    "votes_total": 3,
                    "url": "https://genius.com/a/201"
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent()
}
