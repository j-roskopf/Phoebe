package com.phoebe.app

import com.phoebe.app.data.PlexClient
import com.phoebe.app.data.PlexTimelineState
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlexClientMockEngineDesktopTest {
    @Test
    fun serversParsesResourcesFromMockEngine() = runBlocking {
        val json = """
            [
              {
                "name": "plex",
                "product": "Plex Media Server",
                "clientIdentifier": "server-id",
                "owned": true,
                "provides": "server",
                "accessToken": "server-token-xyz",
                "connections": [
                  { "uri": "https://example.plex.direct:32400", "local": false }
                ]
              }
            ]
        """.trimIndent()
        val engine = MockEngine { request ->
            if (request.url.host == "plex.tv" && request.url.encodedPath.contains("resources")) {
                respond(
                    content = json,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val servers: List<PlexServer> = client.servers("fake-token")
        assertEquals(1, servers.size)
        assertEquals("server-id", servers.single().id)
        assertEquals("server-token-xyz", servers.single().accessToken)
        assertTrue(servers.single().uri.startsWith("https://"))
    }

    @Test
    fun reportTimelineSendsTokenInQueryAndUsesGet() = runBlocking {
        var capturedMethod: String? = null
        var capturedToken: String? = null
        val engine = MockEngine { request ->
            capturedMethod = request.method.value
            capturedToken = request.url.parameters["X-Plex-Token"]
            respond(
                content = """{"MediaContainer":{"size":0}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        client.reportTimeline(
            server = PlexServer("id", "plex", "https://plex.example:32400", owned = true),
            token = "secret-token",
            sessionIdentifier = "session-1",
            ratingKey = "123",
            timeMs = 5_000L,
            durationMs = 180_000L,
            state = PlexTimelineState.Playing,
        )
        assertEquals("GET", capturedMethod)
        assertEquals("secret-token", capturedToken)
    }

    @Test
    fun markPlayedUsesScrobbleEndpointWithRatingKey() = runBlocking {
        var capturedMethod: String? = null
        var capturedPath: String? = null
        var capturedToken: String? = null
        var capturedIdentifier: String? = null
        var capturedKey: String? = null
        val engine = MockEngine { request ->
            capturedMethod = request.method.value
            capturedPath = request.url.encodedPath
            capturedToken = request.url.parameters["X-Plex-Token"]
            capturedIdentifier = request.url.parameters["identifier"]
            capturedKey = request.url.parameters["key"]
            respond(
                content = """{"MediaContainer":{"size":0}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))

        client.markPlayed(
            server = PlexServer("id", "plex", "https://plex.example:32400", owned = true),
            token = "secret-token",
            ratingKey = "123",
        )

        assertEquals("PUT", capturedMethod)
        assertEquals("/:/scrobble", capturedPath)
        assertEquals("secret-token", capturedToken)
        assertEquals("com.plexapp.plugins.library", capturedIdentifier)
        assertEquals("123", capturedKey)
    }

    @Test
    fun musicLibrariesTimesOutStuckFirstBaseAndRetriesNext() = runBlocking {
        val attemptedHosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            attemptedHosts += request.url.host
            when (request.url.host) {
                "first.example" -> {
                    delay(5_000)
                    respond("", HttpStatusCode.GatewayTimeout)
                }
                "second.example" -> respond(
                    content = """{"MediaContainer":{"Directory":[{"key":"1","title":"Music","type":"artist"}]}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))

        val libraries = client.musicLibraries(
            server = PlexServer(
                id = "id",
                name = "plex",
                uri = "http://first.example:32400",
                owned = true,
                connectionUris = listOf("http://first.example:32400", "http://second.example:32400"),
                advertisedConnectionUris = listOf("http://first.example:32400", "http://second.example:32400"),
            ),
            token = "secret-token",
            baseTimeoutMs = 50,
        )

        assertEquals(listOf("first.example", "second.example"), attemptedHosts)
        assertEquals(listOf("Music"), libraries.map { it.title })
    }

    @Test
    fun reportTimelineRetriesNextBaseAfterConnectionClosed() = runBlocking {
        val attemptedHosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            attemptedHosts += request.url.host
            if (request.url.host == "first.example") {
                throw IOException("connection closed")
            }
            respond(
                content = """{"MediaContainer":{"size":0}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        client.reportTimeline(
            server = PlexServer(
                id = "id",
                name = "plex",
                uri = "http://first.example:32400",
                owned = true,
                connectionUris = listOf("http://first.example:32400", "http://second.example:32400"),
                advertisedConnectionUris = listOf("http://first.example:32400", "http://second.example:32400"),
            ),
            token = "secret-token",
            sessionIdentifier = "session-1",
            ratingKey = "123",
            timeMs = 5_000L,
            durationMs = 180_000L,
            state = PlexTimelineState.Stopped,
        )

        assertEquals(listOf("first.example", "second.example"), attemptedHosts)
    }

    @Test
    fun reportTimelineSkipsRemainingLanAfterLocalTimeout() = runBlocking {
        val attemptedHosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            attemptedHosts += request.url.host
            when (request.url.host) {
                "172.16.1.2", "192.168.1.9" -> {
                    delay(5_000)
                    respond("", HttpStatusCode.GatewayTimeout)
                }
                else -> respond(
                    content = """{"MediaContainer":{"size":0}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        val lanA = "http://172.16.1.2:32400"
        val lanB = "http://192.168.1.9:32400"
        val remote = "https://45-33-97-28.abc.plex.direct:8443"
        client.reportTimeline(
            server = PlexServer(
                id = "id",
                name = "plex",
                uri = lanA,
                owned = true,
                connectionUris = listOf(lanA, lanB, remote),
                advertisedConnectionUris = listOf(lanA, lanB, remote),
                localConnectionUris = listOf(lanA, lanB),
            ),
            token = "secret-token",
            sessionIdentifier = "session-1",
            ratingKey = "123",
            timeMs = 5_000L,
            durationMs = 180_000L,
            state = PlexTimelineState.Playing,
        )

        assertTrue("172.16.1.2" in attemptedHosts || "192.168.1.9" in attemptedHosts)
        assertTrue(
            attemptedHosts.count { it == "172.16.1.2" || it == "192.168.1.9" } == 1,
            "should skip remaining LAN after the first local timeout; hosts=$attemptedHosts",
        )
        assertTrue("45-33-97-28.abc.plex.direct" in attemptedHosts, "hosts=$attemptedHosts")
    }

    @Test
    fun reportTimelineStillTriesNextLanAfterFastLocalFailure() = runBlocking {
        val attemptedHosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            attemptedHosts += request.url.host
            when (request.url.host) {
                "172.16.1.2" -> throw IOException("connection reset")
                else -> respond(
                    content = """{"MediaContainer":{"size":0}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val client = PlexClient.withoutResolver(testHttpClient(engine))
        client.reportTimeline(
            server = PlexServer(
                id = "id",
                name = "plex",
                uri = "http://172.16.1.2:32400",
                owned = true,
                connectionUris = listOf("http://172.16.1.2:32400", "http://192.168.1.9:32400"),
                advertisedConnectionUris = listOf("http://172.16.1.2:32400", "http://192.168.1.9:32400"),
                localConnectionUris = listOf("http://172.16.1.2:32400", "http://192.168.1.9:32400"),
            ),
            token = "secret-token",
            sessionIdentifier = "session-1",
            ratingKey = "123",
            timeMs = 5_000L,
            durationMs = 180_000L,
            state = PlexTimelineState.Playing,
        )

        assertEquals(listOf("172.16.1.2", "192.168.1.9"), attemptedHosts)
    }
}
