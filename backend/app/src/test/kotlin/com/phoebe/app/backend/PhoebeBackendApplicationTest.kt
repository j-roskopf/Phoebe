package com.phoebe.app.backend

import com.phoebe.app.backend.events.ArtistEventsBackendFeature
import com.phoebe.app.backend.lyrics.LyricsBackendFeature
import com.phoebe.app.backend.musicbrainz.MusicBrainzBackendFeature
import com.phoebe.app.backend.musicbrainz.NoopMusicBrainzRequestGate
import com.phoebe.app.domain.ArtistEventsResponse
import com.phoebe.app.domain.EventDataProvider
import com.phoebe.app.domain.GeniusReferentsResponse
import com.phoebe.app.domain.MusicBrainzAlbumMetadataResponse
import com.phoebe.app.domain.MusicBrainzArtistArtworkResponse
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoebeBackendApplicationTest {
    private val testFeatures = listOf(
        ArtistEventsBackendFeature(),
        LyricsBackendFeature(),
        MusicBrainzBackendFeature(NoopMusicBrainzRequestGate),
    )
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

    @Test
    fun corsDoesNotAllowUnconfiguredOrigin() = testApplication {
        application {
            phoebeBackendModule(
                config = testConfig(allowedOrigins = listOf("https://phoebe.example")),
                httpClient = mockProviderClient("{}"),
                features = testFeatures,
            )
        }

        val response = client.get("/health") {
            header(HttpHeaders.Origin, "https://evil.example")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun corsRequiresAllowedOriginsInProduction() {
        val error = assertFailsWith<IllegalStateException> {
            testApplication {
                application {
                    phoebeBackendModule(
                        config = testConfig(isProduction = true),
                        httpClient = mockProviderClient("{}"),
                        features = testFeatures,
                    )
                }

                client.get("/health")
            }
        }

        assertTrue(error.message.orEmpty().contains("ALLOWED_ORIGINS"))
    }

    @Test
    fun corsAllowsExplicitAnyOriginEscapeHatchInProduction() = testApplication {
        application {
            phoebeBackendModule(
                config = testConfig(isProduction = true, allowAnyOrigin = true),
                httpClient = mockProviderClient("{}"),
                features = testFeatures,
            )
        }

        val response = client.get("/health") {
            header(HttpHeaders.Origin, "https://evil.example")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertNotNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun artistEventsRateLimitReturnsRetryAfter() = testApplication {
        application {
            phoebeBackendModule(
                config = testConfig(rateLimitMaxRequests = 2, rateLimitWindowMs = 60_000L),
                httpClient = mockProviderClient(ticketmasterPayload()),
                clockMs = { 0L },
                features = testFeatures,
            )
        }

        val path = "/v1/artist-events?provider=ticketmaster&artist=Phoebe&limit=1"

        assertEquals(HttpStatusCode.OK, client.get(path).status)
        assertEquals(HttpStatusCode.OK, client.get(path).status)
        val limited = client.get(path)

        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("60", limited.headers["Retry-After"])
        assertTrue(limited.bodyAsText().contains("Too many requests"))
    }

    @Test
    fun healthIsNotRateLimited() = testApplication {
        application {
            phoebeBackendModule(
                config = testConfig(rateLimitMaxRequests = 1, rateLimitWindowMs = 60_000L),
                httpClient = mockProviderClient("{}"),
                clockMs = { 0L },
                features = testFeatures,
            )
        }

        repeat(3) {
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
        }
    }

    @Test
    fun rateLimitTrustsForwardedHeadersWhenConfigured() = testApplication {
        application {
            phoebeBackendModule(
                config = testConfig(rateLimitMaxRequests = 1, rateLimitWindowMs = 60_000L, trustProxyHeaders = true),
                httpClient = mockProviderClient(ticketmasterPayload()),
                clockMs = { 0L },
                features = testFeatures,
            )
        }

        val path = "/v1/artist-events?provider=ticketmaster&artist=Phoebe&limit=1"

        assertEquals(
            HttpStatusCode.OK,
            client.get(path) {
                header("X-Forwarded-For", "203.0.113.10, 10.0.0.1")
            }.status,
        )
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.get(path) {
                header("X-Forwarded-For", "203.0.113.10, 10.0.0.1")
            }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get(path) {
                header("X-Forwarded-For", "203.0.113.11, 10.0.0.1")
            }.status,
        )
    }

    @Test
    fun rateLimitPrefersRealIpOverForwardedForWhenTrusted() = testApplication {
        application {
            phoebeBackendModule(
                config = testConfig(rateLimitMaxRequests = 1, rateLimitWindowMs = 60_000L, trustProxyHeaders = true),
                httpClient = mockProviderClient(ticketmasterPayload()),
                clockMs = { 0L },
                features = testFeatures,
            )
        }

        val path = "/v1/artist-events?provider=ticketmaster&artist=Phoebe&limit=1"

        assertEquals(
            HttpStatusCode.OK,
            client.get(path) {
                header("X-Real-IP", "198.51.100.20")
                header("X-Forwarded-For", "203.0.113.10, 10.0.0.1")
            }.status,
        )
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.get(path) {
                header("X-Real-IP", "198.51.100.20")
                header("X-Forwarded-For", "203.0.113.11, 10.0.0.1")
            }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get(path) {
                header("X-Real-IP", "198.51.100.21")
                header("X-Forwarded-For", "203.0.113.11, 10.0.0.1")
            }.status,
        )
    }

    @Test
    fun rateLimitIgnoresForwardedHeadersWhenNotTrusted() = testApplication {
        application {
            phoebeBackendModule(
                config = testConfig(rateLimitMaxRequests = 1, rateLimitWindowMs = 60_000L, trustProxyHeaders = false),
                httpClient = mockProviderClient(ticketmasterPayload()),
                clockMs = { 0L },
                features = testFeatures,
            )
        }

        val path = "/v1/artist-events?provider=ticketmaster&artist=Phoebe&limit=1"

        assertEquals(
            HttpStatusCode.OK,
            client.get(path) {
                header("X-Forwarded-For", "203.0.113.10")
            }.status,
        )
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.get(path) {
                header("X-Forwarded-For", "203.0.113.11")
            }.status,
        )
    }

    @Test
    fun queryValidationRejectsTooLongArtist() = testApplication {
        application {
            phoebeBackendModule(config = testConfig(), httpClient = mockProviderClient("{}"), features = testFeatures)
        }

        val tooLongArtist = "a".repeat(MaxBackendQueryParameterLength + 1)
        val response = client.get("/v1/artist-events?provider=ticketmaster&artist=$tooLongArtist&limit=1")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("artist must be $MaxBackendQueryParameterLength characters or fewer"))
    }

    @Test
    fun queryValidationRejectsControlCharacters() = testApplication {
        application {
            phoebeBackendModule(config = testConfig(), httpClient = mockProviderClient("{}"), features = testFeatures)
        }

        val response = client.get("/v1/genius/referents?artist=Test&title=Bad%0ATitle")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("title must not contain control characters"))
    }

    @Test
    fun queryValidationRejectsInvalidDuration() = testApplication {
        application {
            phoebeBackendModule(config = testConfig(), httpClient = mockProviderClient("{}"), features = testFeatures)
        }

        val response = client.get("/v1/genius/referents?artist=Test&title=Song&durationMs=${MaxBackendDurationMs + 1}")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("durationMs must be a positive integer"))
    }

    @Test
    fun queryValidationAcceptsBoundaryLengthArtist() = testApplication {
        application {
            phoebeBackendModule(
                config = testConfig(ticketmasterApiKey = "tm-key"),
                httpClient = mockProviderClient(ticketmasterPayload()),
                features = testFeatures,
            )
        }

        val boundaryArtist = "a".repeat(MaxBackendQueryParameterLength)
        val response = client.get("/v1/artist-events?provider=ticketmaster&artist=$boundaryArtist&limit=1")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun artistEventsConcurrentCacheMissesShareProviderRequest() = testApplication {
        val providerCalls = AtomicInteger(0)
        val providerClient = HttpClient(
            MockEngine {
                providerCalls.incrementAndGet()
                delay(100)
                respondJson(ticketmasterPayload())
            },
        ) {
            install(ClientContentNegotiation) {
                json(json)
            }
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

        val responses = coroutineScope {
            (1..5).map {
                async {
                    routeClient.get("/v1/artist-events?provider=ticketmaster&artist=Phoebe&limit=1")
                }
            }.awaitAll()
        }

        responses.forEach { response ->
            assertEquals(HttpStatusCode.OK, response.status)
        }
        assertEquals(1, providerCalls.get())
    }

    @Test
    fun musicBrainzAlbumUsesExactMbidAndReturnsCreditsAndArtwork() = testApplication {
        val userAgents = mutableListOf<String?>()
        val releaseMbid = "11111111-2222-3333-4444-555555555555"
        val providerClient = HttpClient(
            MockEngine { request ->
                userAgents += request.headers[HttpHeaders.UserAgent]
                when ("${request.url.host}${request.url.encodedPath}") {
                    "musicbrainz.org/ws/2/release/$releaseMbid" -> respondJson(musicBrainzReleasePayload(releaseMbid))
                    "coverartarchive.org/release/$releaseMbid" -> respondJson(coverArtPayload())
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        ) {
            install(ClientContentNegotiation) {
                json(json)
            }
        }
        application {
            phoebeBackendModule(config = testConfig(), httpClient = providerClient, features = testFeatures)
        }

        val response = client.get("/v1/musicbrainz/album?album=Punisher&artist=Phoebe%20Bridgers&releaseMbid=$releaseMbid")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(userAgents.isNotEmpty())
        assertTrue(userAgents.all { it == DefaultMusicBrainzUserAgent })
        val body = json.decodeFromString<MusicBrainzAlbumMetadataResponse>(response.bodyAsText())
        assertEquals(releaseMbid, body.match?.musicBrainzId)
        assertEquals("Punisher", body.match?.title)
        assertTrue(body.credits.any { it.role == "Album artist" && "Phoebe Bridgers" in it.names })
        assertTrue(body.credits.any { it.role == "Label" && "Dead Oceans" in it.names })
        assertTrue(body.credits.any { it.role == "Production" && "Tony Berg" in it.names })
        assertTrue(body.credits.any { it.role == "Visuals" && "Olof Grind" in it.names })
        assertEquals("https://img.example/front-1200.jpg", body.artwork.single().largeThumbnailUrl)
    }

    @Test
    fun musicBrainzAlbumSearchFallbackIsCached() = testApplication {
        val releaseMbid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        var searchCount = 0
        var lookupCount = 0
        val providerClient = HttpClient(
            MockEngine { request ->
                when {
                    request.url.host == "musicbrainz.org" && request.url.encodedPath == "/ws/2/release" -> {
                        searchCount += 1
                        assertTrue(request.url.parameters["query"].orEmpty().contains("release:\"Stranger in the Alps\""))
                        respondJson(musicBrainzReleaseSearchPayload(releaseMbid))
                    }
                    request.url.host == "musicbrainz.org" && request.url.encodedPath == "/ws/2/release/$releaseMbid" -> {
                        lookupCount += 1
                        respondJson(musicBrainzReleasePayload(releaseMbid))
                    }
                    request.url.host == "coverartarchive.org" && request.url.encodedPath == "/release/$releaseMbid" -> {
                        respondJson(coverArtPayload())
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
            phoebeBackendModule(config = testConfig(), httpClient = providerClient, features = testFeatures)
        }

        repeat(2) {
            val response = client.get("/v1/musicbrainz/album?album=Stranger%20in%20the%20Alps&artist=Phoebe%20Bridgers&year=2017")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<MusicBrainzAlbumMetadataResponse>(response.bodyAsText())
            assertEquals(releaseMbid, body.match?.musicBrainzId)
        }
        assertEquals(1, searchCount)
        assertEquals(1, lookupCount)
    }

    @Test
    fun musicBrainzAlbumNoMatchReturnsEmptyMetadata() = testApplication {
        application {
            phoebeBackendModule(
                config = testConfig(),
                httpClient = mockProviderClient("""{"releases":[]}"""),
                features = testFeatures,
            )
        }

        val response = client.get("/v1/musicbrainz/album?album=Missing&artist=Nobody")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<MusicBrainzAlbumMetadataResponse>(response.bodyAsText())
        assertEquals("Missing", body.query.album)
        assertEquals(null, body.match)
        assertEquals(emptyList(), body.credits)
        assertEquals(emptyList(), body.artwork)
    }

    @Test
    fun musicBrainzArtistArtworkBrowsesReleaseGroupsAndDeduplicatesImages() = testApplication {
        val providerClient = HttpClient(
            MockEngine { request ->
                when ("${request.url.host}${request.url.encodedPath}") {
                    "musicbrainz.org/ws/2/artist" -> respondJson(musicBrainzArtistSearchPayload())
                    "musicbrainz.org/ws/2/release-group" -> respondJson(musicBrainzReleaseGroupsPayload())
                    "coverartarchive.org/release-group/rg-1" -> respond(
                        content = "",
                        status = HttpStatusCode.TemporaryRedirect,
                        headers = headersOf(HttpHeaders.Location, "/release/release-1"),
                    )
                    "coverartarchive.org/release-group/rg-2" -> respond(
                        content = "",
                        status = HttpStatusCode.TemporaryRedirect,
                        headers = headersOf(HttpHeaders.Location, "https://coverartarchive.org/release/release-2"),
                    )
                    "coverartarchive.org/release/release-1" -> respondJson(
                        coverArtPayload(imageUrl = "https://img.example/shared.jpg", title = "Front", rawId = "8423939044"),
                    )
                    "coverartarchive.org/release/release-2" -> respondJson(coverArtPayload(imageUrl = "https://img.example/shared.jpg", title = "Front"))
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        ) {
            install(ClientContentNegotiation) {
                json(json)
            }
        }
        application {
            phoebeBackendModule(config = testConfig(), httpClient = providerClient, features = testFeatures)
        }

        val response = client.get("/v1/musicbrainz/artist-artwork?artist=Phoebe%20Bridgers&limit=4")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<MusicBrainzArtistArtworkResponse>(response.bodyAsText())
        assertEquals("artist-1", body.match?.musicBrainzId)
        assertEquals(1, body.artwork.size)
        assertEquals("https://img.example/shared.jpg", body.artwork.single().imageUrl)
    }

    @Test
    fun musicBrainzArtistArtworkCapsReleaseGroupScan() = testApplication {
        val requestedGroups = mutableListOf<String>()
        val providerClient = HttpClient(
            MockEngine { request ->
                when {
                    request.url.host == "musicbrainz.org" && request.url.encodedPath == "/ws/2/artist" -> {
                        respondJson(musicBrainzArtistSearchPayload())
                    }
                    request.url.host == "musicbrainz.org" && request.url.encodedPath == "/ws/2/release-group" -> {
                        respondJson(musicBrainzReleaseGroupsPayload(count = 20))
                    }
                    request.url.host == "coverartarchive.org" && request.url.encodedPath.startsWith("/release-group/") -> {
                        synchronized(requestedGroups) {
                            requestedGroups += request.url.encodedPath
                                .removePrefix("/release-group/")
                                .substringBefore("/")
                        }
                        respond("", HttpStatusCode.NotFound)
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
            phoebeBackendModule(config = testConfig(), httpClient = providerClient, features = testFeatures)
        }

        val response = client.get("/v1/musicbrainz/artist-artwork?artist=Phoebe%20Bridgers&limit=18")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<MusicBrainzArtistArtworkResponse>(response.bodyAsText())
        assertEquals(emptyList(), body.artwork)
        synchronized(requestedGroups) {
            assertEquals(12, requestedGroups.distinct().size)
        }
    }

    @Test
    fun musicBrainzArtistArtworkFastModeCapsReleaseGroupScan() = testApplication {
        val requestedGroups = mutableListOf<String>()
        val providerClient = HttpClient(
            MockEngine { request ->
                when {
                    request.url.host == "musicbrainz.org" && request.url.encodedPath == "/ws/2/artist" -> {
                        respondJson(musicBrainzArtistSearchPayload())
                    }
                    request.url.host == "musicbrainz.org" && request.url.encodedPath == "/ws/2/release-group" -> {
                        respondJson(musicBrainzReleaseGroupsPayload(count = 20))
                    }
                    request.url.host == "coverartarchive.org" && request.url.encodedPath.startsWith("/release-group/") -> {
                        synchronized(requestedGroups) {
                            requestedGroups += request.url.encodedPath
                                .removePrefix("/release-group/")
                                .substringBefore("/")
                        }
                        respond("", HttpStatusCode.NotFound)
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
            phoebeBackendModule(config = testConfig(), httpClient = providerClient, features = testFeatures)
        }

        val response = client.get("/v1/musicbrainz/artist-artwork?artist=Phoebe%20Bridgers&limit=12&fast=true")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<MusicBrainzArtistArtworkResponse>(response.bodyAsText())
        assertEquals(emptyList(), body.artwork)
        synchronized(requestedGroups) {
            assertEquals(12, requestedGroups.distinct().size)
        }
    }

    @Test
    fun musicBrainzArtistArtworkFastModeReturnsUsefulSet() = testApplication {
        val providerClient = HttpClient(
            MockEngine { request ->
                when {
                    request.url.host == "musicbrainz.org" && request.url.encodedPath == "/ws/2/artist" -> {
                        respondJson(musicBrainzArtistSearchPayload())
                    }
                    request.url.host == "musicbrainz.org" && request.url.encodedPath == "/ws/2/release-group" -> {
                        respondJson(musicBrainzReleaseGroupsPayload(count = 20))
                    }
                    request.url.host == "coverartarchive.org" && request.url.encodedPath.startsWith("/release-group/rg-") -> {
                        val group = request.url.encodedPath.removePrefix("/release-group/").substringBefore("/")
                        val index = group.removePrefix("rg-").toIntOrNull() ?: 0
                        if (index in 1..8) {
                            respond(
                                content = "",
                                status = HttpStatusCode.TemporaryRedirect,
                                headers = headersOf(HttpHeaders.Location, "http://img.example/rg-$index-500.jpg"),
                            )
                        } else {
                            respond("", HttpStatusCode.NotFound)
                        }
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
            phoebeBackendModule(config = testConfig(), httpClient = providerClient, features = testFeatures)
        }

        val response = client.get("/v1/musicbrainz/artist-artwork?artist=Phoebe%20Bridgers&limit=12&fast=true")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<MusicBrainzArtistArtworkResponse>(response.bodyAsText())
        assertTrue(body.artwork.size >= 4)
        assertEquals("https://img.example/rg-1-500.jpg", body.artwork.first().imageUrl)
    }

    @Test
    fun musicBrainzArtistArtworkFastModeBrowsesBeyondScanLimitBeforeSorting() = testApplication {
        val providerClient = HttpClient(
            MockEngine { request ->
                when {
                    request.url.host == "musicbrainz.org" && request.url.encodedPath == "/ws/2/artist" -> {
                        respondJson(musicBrainzArtistSearchPayload())
                    }
                    request.url.host == "musicbrainz.org" && request.url.encodedPath == "/ws/2/release-group" -> {
                        respondJson(musicBrainzReleaseGroupsPayload(count = 20))
                    }
                    request.url.host == "coverartarchive.org" && request.url.encodedPath == "/release-group/rg-13/front-500" -> {
                        respond(
                            content = "",
                            status = HttpStatusCode.TemporaryRedirect,
                            headers = headersOf(HttpHeaders.Location, "http://img.example/rg-13-500.jpg"),
                        )
                    }
                    request.url.host == "coverartarchive.org" && request.url.encodedPath.startsWith("/release-group/") -> {
                        respond("", HttpStatusCode.NotFound)
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
            phoebeBackendModule(config = testConfig(), httpClient = providerClient, features = testFeatures)
        }

        val response = client.get("/v1/musicbrainz/artist-artwork?artist=Phoebe%20Bridgers&limit=12&fast=true")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<MusicBrainzArtistArtworkResponse>(response.bodyAsText())
        assertEquals(listOf("https://img.example/rg-13-500.jpg"), body.artwork.map { it.imageUrl })
    }

    @Test
    fun musicBrainzArtistArtworkExcludesCurrentArtwork() = testApplication {
        val providerClient = HttpClient(
            MockEngine { request ->
                when {
                    request.url.host == "musicbrainz.org" && request.url.encodedPath == "/ws/2/artist" -> {
                        respondJson(musicBrainzArtistSearchPayload())
                    }
                    request.url.host == "musicbrainz.org" && request.url.encodedPath == "/ws/2/release-group" -> {
                        respondJson(musicBrainzReleaseGroupsPayload(count = 4))
                    }
                    request.url.host == "coverartarchive.org" && request.url.encodedPath == "/release-group/rg-1/front-500" -> {
                        respond(
                            content = "",
                            status = HttpStatusCode.TemporaryRedirect,
                            headers = headersOf(HttpHeaders.Location, "https://img.example/current-500.jpg"),
                        )
                    }
                    request.url.host == "coverartarchive.org" && request.url.encodedPath == "/release-group/rg-2/front-500" -> {
                        respond(
                            content = "",
                            status = HttpStatusCode.TemporaryRedirect,
                            headers = headersOf(HttpHeaders.Location, "https://img.example/new-500.jpg"),
                        )
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
            phoebeBackendModule(config = testConfig(), httpClient = providerClient, features = testFeatures)
        }

        val response = client.get(
            "/v1/musicbrainz/artist-artwork?artist=Phoebe%20Bridgers&limit=12&fast=true&excludeImageUrl=https%3A%2F%2Fimg.example%2Fcurrent.jpg",
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<MusicBrainzArtistArtworkResponse>(response.bodyAsText())
        assertEquals(listOf("https://img.example/new-500.jpg"), body.artwork.map { it.imageUrl })
    }

    @Test
    fun musicBrainzArtistArtworkStopsScanningAfterEnoughArtwork() = testApplication {
        val requestedGroups = mutableListOf<String>()
        val providerClient = HttpClient(
            MockEngine { request ->
                when {
                    request.url.host == "musicbrainz.org" && request.url.encodedPath == "/ws/2/artist" -> {
                        respondJson(musicBrainzArtistSearchPayload())
                    }
                    request.url.host == "musicbrainz.org" && request.url.encodedPath == "/ws/2/release-group" -> {
                        respondJson(musicBrainzReleaseGroupsPayload(count = 20))
                    }
                    request.url.host == "coverartarchive.org" && request.url.encodedPath.startsWith("/release-group/") -> {
                        val group = request.url.encodedPath.substringAfterLast("/")
                        synchronized(requestedGroups) {
                            requestedGroups += group
                        }
                        respondJson(coverArtPayload(imageUrl = "http://img.example/$group.jpg", title = "Front"))
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
            phoebeBackendModule(config = testConfig(), httpClient = providerClient, features = testFeatures)
        }

        val response = client.get("/v1/musicbrainz/artist-artwork?artist=Phoebe%20Bridgers&limit=4")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<MusicBrainzArtistArtworkResponse>(response.bodyAsText())
        assertEquals(4, body.artwork.size)
        assertTrue(body.artwork.all { it.imageUrl.startsWith("https://") })
        synchronized(requestedGroups) {
            assertEquals(4, requestedGroups.distinct().size)
        }
    }

    @Test
    fun musicBrainzAlbumRequiresAlbumAndArtist() = testApplication {
        application {
            phoebeBackendModule(config = testConfig(), httpClient = mockProviderClient("{}"), features = testFeatures)
        }

        val response = client.get("/v1/musicbrainz/album?album=Punisher")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("artist is required"))
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
        allowAnyOrigin: Boolean = false,
        isProduction: Boolean = false,
        rateLimitMaxRequests: Int = DefaultBackendRateLimitMaxRequests,
        rateLimitWindowMs: Long = DefaultBackendRateLimitWindowSeconds * 1_000L,
        trustProxyHeaders: Boolean = false,
        musicBrainzUserAgent: String = DefaultMusicBrainzUserAgent,
    ): PhoebeBackendConfig =
        PhoebeBackendConfig(
            ticketmasterApiKey = ticketmasterApiKey,
            seatGeekClientId = seatGeekClientId,
            geniusAccessToken = geniusAccessToken,
            musicBrainzUserAgent = musicBrainzUserAgent,
            allowedOrigins = allowedOrigins,
            allowAnyOrigin = allowAnyOrigin,
            isProduction = isProduction,
            rateLimitMaxRequests = rateLimitMaxRequests,
            rateLimitWindowMs = rateLimitWindowMs,
            trustProxyHeaders = trustProxyHeaders,
            cacheTtlMinutes = 240,
        )

    private fun musicBrainzReleaseSearchPayload(releaseMbid: String): String =
        """
        {
          "releases": [
            {
              "id": "$releaseMbid",
              "title": "Stranger in the Alps",
              "score": 98,
              "date": "2017-09-22",
              "artist-credit": [
                { "name": "Phoebe Bridgers", "artist": { "id": "artist-1", "name": "Phoebe Bridgers" } }
              ],
              "release-group": { "id": "rg-search", "title": "Stranger in the Alps" }
            }
          ]
        }
        """.trimIndent()

    private fun musicBrainzReleasePayload(releaseMbid: String): String =
        """
        {
          "id": "$releaseMbid",
          "title": "Punisher",
          "date": "2020-06-18",
          "artist-credit": [
            { "name": "Phoebe Bridgers", "artist": { "id": "artist-1", "name": "Phoebe Bridgers" } }
          ],
          "release-group": { "id": "rg-1", "title": "Punisher" },
          "label-info": [
            { "label": { "id": "label-1", "name": "Dead Oceans" } }
          ],
          "relations": [
            { "type": "producer", "artist": { "id": "artist-2", "name": "Tony Berg" } },
            { "type": "engineer", "artist": { "id": "artist-3", "name": "Ethan Gruska" } },
            { "type": "photography", "artist": { "id": "artist-4", "name": "Olof Grind" } }
          ],
          "media": [
            {
              "tracks": [
                {
                  "recording": {
                    "title": "DVD Menu",
                    "relations": [
                      { "type": "vocal", "artist": { "id": "artist-1", "name": "Phoebe Bridgers" }, "attributes": ["vocals"] },
                      { "type": "composer", "artist": { "id": "artist-1", "name": "Phoebe Bridgers" } }
                    ]
                  }
                }
              ]
            }
          ]
        }
        """.trimIndent()

    private fun musicBrainzArtistSearchPayload(): String =
        """
        {
          "artists": [
            { "id": "artist-1", "name": "Phoebe Bridgers", "score": 100 }
          ]
        }
        """.trimIndent()

    private fun musicBrainzReleaseGroupsPayload(count: Int = 2): String {
        val groups = (1..count).joinToString(",") { index ->
            val title = when (index) {
                1 -> "Punisher"
                2 -> "Copycat Killer"
                else -> "Release Group $index"
            }
            val primaryType = if (index == 2) "EP" else "Album"
            val year = 2025 - index
            """
            { "id": "rg-$index", "title": "$title", "primary-type": "$primaryType", "first-release-date": "$year-01-01" }
            """.trimIndent()
        }
        return """
        {
          "release-groups": [
            $groups
          ]
        }
        """.trimIndent()
    }

    private fun coverArtPayload(
        imageUrl: String = "https://img.example/front.jpg",
        title: String = "Front",
        rawId: String = """"art-1"""",
    ): String =
        """
        {
          "images": [
            {
              "id": $rawId,
              "image": "$imageUrl",
              "thumbnails": {
                "250": "https://img.example/front-250.jpg",
                "500": "https://img.example/front-500.jpg",
                "1200": "https://img.example/front-1200.jpg",
                "small": "https://img.example/front-small.jpg",
                "large": "https://img.example/front-large.jpg"
              },
              "types": ["$title"],
              "front": true,
              "approved": true
            }
          ]
        }
        """.trimIndent()

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
