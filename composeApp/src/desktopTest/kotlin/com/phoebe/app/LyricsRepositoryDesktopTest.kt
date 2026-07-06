package com.phoebe.app

import app.cash.sqldelight.db.SqlDriver
import com.phoebe.app.data.AppSettingsRepository
import com.phoebe.app.data.GeniusBackendClient
import com.phoebe.app.data.LyricsRepository
import com.phoebe.app.domain.EventSettings
import com.phoebe.app.domain.EventsBackendTarget
import com.phoebe.app.domain.LyricsLoadState
import com.phoebe.app.domain.Track
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodedPath
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsRepositoryDesktopTest {
    private var driver: SqlDriver? = null

    @After
    fun cleanup() {
        driver?.close()
        driver = null
    }

    @Test
    fun fetchesSyncedLyricsFromLrclibAndCachesThem() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        var calls = 0
        val http = testHttpClient(
            MockEngine { request ->
                if (request.url.host == "lrclib.net") {
                    calls++
                }
                respond(
                    content = """{"id":1,"instrumental":false,"plainLyrics":"Hello\nWorld","syncedLyrics":"[00:01.00] Hello\n[00:02.00] World"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val repo = lyricsRepository(db, http).repository

        val first = assertIs<LyricsLoadState.Loaded>(repo.lyricsFor(track()))
        val second = assertIs<LyricsLoadState.Loaded>(repo.lyricsFor(track()))

        assertEquals(1, calls)
        assertTrue(first.document.synced)
        assertEquals(listOf(1_000L, 2_000L), first.document.lines.map { it.startMs })
        assertEquals(first.document.lines.map { it.text }, second.document.lines.map { it.text })
    }

    @Test
    fun returnsNotFoundForLrclib404() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val repo = lyricsRepository(db, testHttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })).repository

        assertIs<LyricsLoadState.NotFound>(repo.lyricsFor(track()))
    }

    @Test
    fun enrichesLyricsWithGeniusAnnotations() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val backendRequests = mutableListOf<HttpRequestData>()
        val http = testHttpClient(
            MockEngine { request ->
                when (request.url.host) {
                    "lrclib.net" -> respondJson(
                        """{"id":1,"instrumental":false,"plainLyrics":"Hello\nWorld","syncedLyrics":"[00:01.00] Hello\n[00:02.00] World"}""",
                    )
                    BackendHost -> {
                        backendRequests += request
                        when (request.url.encodedPath) {
                            "/v1/genius/referents" -> respondJson(geniusBackendReferentsJson())
                            else -> respond("", HttpStatusCode.NotFound)
                        }
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )
        val harness = lyricsRepository(db, http, backendBaseUrl = BackendBaseUrl)

        val loaded = assertIs<LyricsLoadState.Loaded>(harness.repository.lyricsFor(track()))

        val backendRequest = backendRequests.single()
        assertNull(backendRequest.headers[HttpHeaders.Authorization])
        assertEquals("Test Artist", backendRequest.url.parameters["artist"])
        assertEquals("Test Song", backendRequest.url.parameters["title"])
        assertEquals("Test Album", backendRequest.url.parameters["album"])
        assertEquals("123000", backendRequest.url.parameters["durationMs"])
        val annotations = loaded.document.annotations
        assertEquals(42L, annotations?.songId)
        assertEquals("https://genius.com/test-song-lyrics", annotations?.songUrl)
        assertEquals(1, annotations?.annotations?.size)
        assertEquals(listOf(0, 1), annotations?.annotations?.single()?.target?.lineIndexes)
        assertEquals("A joined-line annotation.", annotations?.annotations?.single()?.body)
        assertEquals(1, annotations?.unmatched?.size)
        assertEquals("Outro fragment", annotations?.unmatched?.single()?.fragment)
    }

    @Test
    fun canLoadBaseLyricsWithoutWaitingForRemoteGeniusAnnotations() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        var backendCalls = 0
        val http = testHttpClient(
            MockEngine { request ->
                when (request.url.host) {
                    "lrclib.net" -> respondJson(
                        """{"id":1,"instrumental":false,"plainLyrics":"Hello\nWorld","syncedLyrics":null}""",
                    )
                    BackendHost -> {
                        backendCalls++
                        respondJson(geniusBackendReferentsJson())
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )
        val harness = lyricsRepository(db, http, backendBaseUrl = BackendBaseUrl)

        val loaded = assertIs<LyricsLoadState.Loaded>(
            harness.repository.lyricsFor(track(), includeRemoteAnnotations = false),
        )

        assertEquals(listOf("Hello", "World"), loaded.document.lines.map { it.text })
        assertNull(loaded.document.annotations)
        assertEquals(0, backendCalls)
    }

    @Test
    fun matchesGeniusFragmentsAcrossCurlyAndStraightApostrophes() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(
            MockEngine { request ->
                when (request.url.host) {
                    "lrclib.net" -> respondJson(
                        """{"id":1,"instrumental":false,"plainLyrics":"Holdin' all this love out here in the hall","syncedLyrics":null}""",
                    )
                    BackendHost -> when (request.url.encodedPath) {
                        "/v1/genius/referents" -> respondJson(apostropheGeniusReferentsJson())
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )
        val harness = lyricsRepository(db, http, backendBaseUrl = BackendBaseUrl)

        val loaded = assertIs<LyricsLoadState.Loaded>(harness.repository.lyricsFor(track()))

        val annotation = loaded.document.annotations?.annotations?.single()
        assertEquals("Holdin’ all this love out here in the hall", annotation?.fragment)
        assertEquals(listOf(0), annotation?.target?.lineIndexes)
        assertEquals(9, loaded.document.annotations?.matchingVersion)
    }

    @Test
    fun matchesGeniusFragmentsAcrossCompoundWordSpacing() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(
            MockEngine { request ->
                when (request.url.host) {
                    "lrclib.net" -> respondJson(
                        """{"id":1,"instrumental":false,"plainLyrics":"I just want to rap good and not sell bread sticks","syncedLyrics":null}""",
                    )
                    BackendHost -> when (request.url.encodedPath) {
                        "/v1/genius/referents" -> respondJson(miloGeniusReferentsJson())
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )
        val harness = lyricsRepository(db, http, backendBaseUrl = BackendBaseUrl)

        val loaded = assertIs<LyricsLoadState.Loaded>(harness.repository.lyricsFor(track()))

        val annotation = loaded.document.annotations?.annotations?.single()
        assertEquals("I just want to rap good and not sell breadsticks", annotation?.fragment)
        assertEquals(listOf(0), annotation?.target?.lineIndexes)
    }

    @Test
    fun matchesGeniusFragmentsInsideLongerOrSlightlyDifferentLyricLines() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val busdriverPlainLyrics = listOf(
            "This slack-jaw mealy-mouth",
            "I mumble when I'm 'round the house",
            "I'm trying not to do no chores today",
            "Crying in my underwear",
            "I lost my sense of wonder, there",
            "No, I'm not Milan Kundera",
            "Though this lightness is unbearable",
            "The feeling is indelible",
            "I'm wishing I could teleport somewhere",
            "Transmolecularize through the secular eye",
            "Every laundered dollar's wet",
        ).joinToString("\\n")
        val http = testHttpClient(
            MockEngine { request ->
                when (request.url.host) {
                    "lrclib.net" -> respondJson(
                        """
                        {
                          "id": 1,
                          "instrumental": false,
                          "plainLyrics": "$busdriverPlainLyrics",
                          "syncedLyrics": null
                        }
                        """.trimIndent(),
                    )
                    BackendHost -> when (request.url.encodedPath) {
                        "/v1/genius/referents" -> respondJson(busdriverGeniusReferentsJson())
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )
        val harness = lyricsRepository(db, http, backendBaseUrl = BackendBaseUrl)

        val loaded = assertIs<LyricsLoadState.Loaded>(harness.repository.lyricsFor(track()))

        val annotations = loaded.document.annotations?.annotations.orEmpty().sortedBy { it.id }
        assertEquals(2, annotations.size)
        assertEquals(
            "The feeling is indelible\n I’m wishing I could teleport somewhere\n Transmolecularize",
            annotations.first().fragment,
        )
        assertEquals(listOf(7, 8, 9), annotations.first().target?.lineIndexes)
        assertEquals("Every laundered dollar is wet", annotations.last().fragment)
        assertEquals(listOf(10), annotations.last().target?.lineIndexes)
        assertTrue(annotations.none { annotation -> annotation.target?.lineIndexes.orEmpty().contains(0) })
    }

    @Test
    fun usesFreshGeniusAnnotationCacheAndRefreshesOnForceRefresh() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        var lrclibCalls = 0
        var backendCalls = 0
        val http = testHttpClient(
            MockEngine { request ->
                when (request.url.host) {
                    "lrclib.net" -> {
                        lrclibCalls++
                        respondJson("""{"id":1,"instrumental":false,"plainLyrics":"Hello\nWorld","syncedLyrics":"[00:01.00] Hello\n[00:02.00] World"}""")
                    }
                    BackendHost -> when (request.url.encodedPath) {
                        "/v1/genius/referents" -> {
                            backendCalls++
                            respondJson(geniusBackendReferentsJson(includeUnmatched = false))
                        }
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )
        val harness = lyricsRepository(db, http, backendBaseUrl = BackendBaseUrl)

        assertIs<LyricsLoadState.Loaded>(harness.repository.lyricsFor(track()))
        harness.repository.clearMemoryCache()
        assertIs<LyricsLoadState.Loaded>(harness.repository.lyricsFor(track()))
        harness.repository.clearMemoryCache()
        assertIs<LyricsLoadState.Loaded>(harness.repository.lyricsFor(track(), forceRefresh = true))

        assertEquals(2, lrclibCalls)
        assertEquals(2, backendCalls)
    }

    @Test
    fun twoStepForceRefreshReusesBaseLyricsButRefreshesRemoteAnnotations() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        var lrclibCalls = 0
        var backendCalls = 0
        val http = testHttpClient(
            MockEngine { request ->
                when (request.url.host) {
                    "lrclib.net" -> {
                        lrclibCalls++
                        respondJson("""{"id":1,"instrumental":false,"plainLyrics":"Hello\nWorld","syncedLyrics":"[00:01.00] Hello\n[00:02.00] World"}""")
                    }
                    BackendHost -> when (request.url.encodedPath) {
                        "/v1/genius/referents" -> {
                            backendCalls++
                            respondJson(geniusBackendReferentsJson(includeUnmatched = false))
                        }
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )
        val harness = lyricsRepository(db, http, backendBaseUrl = BackendBaseUrl)

        assertIs<LyricsLoadState.Loaded>(harness.repository.lyricsFor(track()))
        harness.repository.clearMemoryCache()
        assertIs<LyricsLoadState.Loaded>(
            harness.repository.lyricsFor(
                track(),
                forceRefresh = true,
                includeRemoteAnnotations = false,
            ),
        )
        assertIs<LyricsLoadState.Loaded>(
            harness.repository.lyricsFor(
                track(),
                forceRefresh = false,
                includeRemoteAnnotations = true,
                forceRemoteAnnotationsRefresh = true,
            ),
        )

        assertEquals(2, lrclibCalls)
        assertEquals(2, backendCalls)
    }

    @Test
    fun enrichesMemoryCachedLyricsWhenBackendBecomesAvailable() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        var lrclibCalls = 0
        var backendCalls = 0
        val http = testHttpClient(
            MockEngine { request ->
                when (request.url.host) {
                    "lrclib.net" -> {
                        lrclibCalls++
                        respondJson("""{"id":1,"instrumental":false,"plainLyrics":"Hello\nWorld","syncedLyrics":"[00:01.00] Hello\n[00:02.00] World"}""")
                    }
                    BackendHost -> when (request.url.encodedPath) {
                        "/v1/genius/referents" -> {
                            backendCalls++
                            respondJson(geniusBackendReferentsJson(includeUnmatched = false))
                        }
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )
        val harness = lyricsRepository(db, http)

        val first = assertIs<LyricsLoadState.Loaded>(harness.repository.lyricsFor(track()))
        harness.settingsRepository.setEventSettings(
            EventSettings(
                backendTarget = EventsBackendTarget.Localhost,
                localBackendUrl = BackendBaseUrl,
            ),
        )
        val second = assertIs<LyricsLoadState.Loaded>(harness.repository.lyricsFor(track()))

        assertEquals(1, lrclibCalls)
        assertEquals(1, backendCalls)
        assertNull(first.document.annotations)
        assertEquals(42L, second.document.annotations?.songId)
    }

    @Test
    fun geniusFailureDoesNotFailSuccessfulLyricsLoad() = runTest {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(
            MockEngine { request ->
                when (request.url.host) {
                    "lrclib.net" -> respondJson("""{"id":1,"instrumental":false,"plainLyrics":"Hello","syncedLyrics":null}""")
                    BackendHost -> respond("nope", HttpStatusCode.InternalServerError)
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )
        val harness = lyricsRepository(db, http, backendBaseUrl = BackendBaseUrl)

        val loaded = assertIs<LyricsLoadState.Loaded>(harness.repository.lyricsFor(track()))

        assertEquals(listOf("Hello"), loaded.document.lines.map { it.text })
        assertNull(loaded.document.annotations)
    }

    private fun track(): Track = Track(
        id = "local:test",
        title = "Test Song",
        artist = "Test Artist",
        album = "Test Album",
        durationMs = 123_000L,
        streamUrl = "",
        downloadUrl = "",
    )

    private suspend fun lyricsRepository(
        db: com.phoebe.app.db.PhoebeDatabase,
        http: HttpClient,
        backendBaseUrl: String? = null,
    ): LyricsRepositoryHarness {
        val settingsRepository = AppSettingsRepository(db)
        backendBaseUrl?.let { baseUrl ->
            settingsRepository.setEventSettings(
                EventSettings(
                    backendTarget = EventsBackendTarget.Localhost,
                    localBackendUrl = baseUrl,
                ),
            )
        }
        return LyricsRepositoryHarness(
            repository = LyricsRepository(
                database = db,
                httpClient = http,
                appSettingsRepository = settingsRepository,
                geniusBackendClient = GeniusBackendClient(http),
            ),
            settingsRepository = settingsRepository,
        )
    }

    private data class LyricsRepositoryHarness(
        val repository: LyricsRepository,
        val settingsRepository: AppSettingsRepository,
    )

    private fun MockRequestHandleScope.respondJson(content: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(
            content = content,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

    private fun geniusBackendReferentsJson(includeUnmatched: Boolean = true): String =
        """
        {
          "song": {
            "id": 42,
            "title": "Test Song",
            "url": "https://genius.com/test-song-lyrics",
            "primaryArtistName": "Test Artist"
          },
          "referents": [
            {
              "id": 100,
              "fragment": "Hello World",
              "annotations": [
                {
                  "id": 200,
                  "body": "A joined-line annotation.",
                  "authorName": "Ada",
                  "verified": true,
                  "votesTotal": 12,
                  "url": "https://genius.com/a/200"
                }
              ]
            }
            ${if (includeUnmatched) """,
            {
              "id": 101,
              "fragment": "Outro fragment",
              "annotations": [
                {
                  "id": 201,
                  "body": "An unmatched annotation.",
                  "authorName": "Grace",
                  "verified": false,
                  "votesTotal": 3,
                  "url": "https://genius.com/a/201"
                }
              ]
            }
            """ else ""}
          ]
        }
        """.trimIndent()

    private fun apostropheGeniusReferentsJson(): String =
        """
        {
          "song": {
            "id": 5793983,
            "title": "exile",
            "url": "https://genius.com/Taylor-swift-exile-lyrics",
            "primaryArtistName": "Taylor Swift"
          },
          "referents": [
            {
              "id": 28148298,
              "fragment": "Holdin’ all this love out here in the hall",
              "annotations": [
                {
                  "id": 28148298,
                  "body": "Throughout her discography, Swift seems to mention a hallway.",
                  "authorName": "Ada",
                  "verified": true,
                  "votesTotal": 12,
                  "url": "https://genius.com/28148298"
                }
              ]
            }
          ]
        }
        """.trimIndent()

    private fun miloGeniusReferentsJson(): String =
        """
        {
          "song": {
            "id": 107102,
            "title": "Folk-metaphysics",
            "url": "https://genius.com/Milo-folk-metaphysics-lyrics",
            "primaryArtistName": "milo"
          },
          "referents": [
            {
              "id": 1377742,
              "fragment": "I just want to rap good and not sell breadsticks",
              "annotations": [
                {
                  "id": 1377742,
                  "body": "Milo would prefer an authentic life.",
                  "authorName": "Ada",
                  "verified": false,
                  "votesTotal": 6,
                  "url": "https://genius.com/1377742/Milo-folk-metaphysics/I-just-want-to-rap-good-and-not-sell-breadsticks"
                }
              ]
            }
          ]
        }
        """.trimIndent()

    private fun busdriverGeniusReferentsJson(): String =
        """
        {
          "song": {
            "id": 2330630,
            "title": "Worlds to Run",
            "url": "https://genius.com/Busdriver-worlds-to-run-lyrics",
            "primaryArtistName": "Busdriver"
          },
          "referents": [
            {
              "id": 7980540,
              "fragment": "The feeling is indelible\n I’m wishing I could teleport somewhere\n Transmolecularize",
              "annotations": [
                {
                  "id": 7980540,
                  "body": "Teleportation is probably impossible.",
                  "authorName": "Ada",
                  "verified": false,
                  "votesTotal": 4,
                  "url": "https://genius.com/7980540"
                }
              ]
            },
            {
              "id": 7981100,
              "fragment": "Every laundered dollar is wet",
              "annotations": [
                {
                  "id": 7981100,
                  "body": "A lyric-version difference still belongs to this line.",
                  "authorName": "Ada",
                  "verified": false,
                  "votesTotal": 2,
                  "url": "https://genius.com/7981100"
                }
              ]
            }
          ]
        }
        """.trimIndent()

    private companion object {
        const val BackendHost = "phoebe-backend.example"
        const val BackendBaseUrl = "https://phoebe-backend.example"
    }
}
