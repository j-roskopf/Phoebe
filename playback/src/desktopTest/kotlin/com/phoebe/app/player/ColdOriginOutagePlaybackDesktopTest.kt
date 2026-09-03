package com.phoebe.app.player

import app.cash.sqldelight.db.SqlDriver
import com.phoebe.app.data.ArtworkAuthHolder
import com.phoebe.app.data.ArtworkOriginHolder
import com.phoebe.app.data.PlexConnectionResolver
import com.phoebe.app.data.db.DatabaseWriteGate
import com.phoebe.app.data.expandConnectionUris
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.platform.NetworkIdentity
import com.phoebe.app.platform.NetworkTransport
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Local stand-in for "block plex.direct, tap play, then unblock":
 * [PlexConnectionResolver] `/identity` fails with an SSL-shaped error until [outageEndsAtMs],
 * then succeeds. A relative Plex path must not open unbound during the outage, and must open a
 * bound absolute URL within [RecoverBudgetMs] after the mock recovers.
 *
 * Run:
 * `./gradlew :playback:desktopTest --tests 'com.phoebe.app.player.ColdOriginOutagePlaybackDesktopTest'`
 */
class ColdOriginOutagePlaybackDesktopTest {
    private var driver: SqlDriver? = null

    private val offLan = NetworkIdentity(
        transport = NetworkTransport.Wifi,
        fingerprint = "cold-outage-off-lan",
        localIpv4Prefixes = listOf("192.168.4.0"),
    )

    @AfterTest
    fun tearDown() {
        PlaybackOriginResolverHolder.resolver = null
        ArtworkAuthHolder.clear()
        ArtworkOriginHolder.clear()
        Thread.sleep(50)
        driver?.close()
        driver = null
    }

    @Test
    fun relativePlexPathSurvivesIdentityOutageThenStartsWithinBudget() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val wan = "https://72-58-82-53.abc.plex.direct:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = PlexServer(
            id = "machine-outage",
            name = "plex",
            uri = lan,
            owned = true,
            connectionUris = expandConnectionUris(listOf(lan, wan, relay)),
            advertisedConnectionUris = listOf(lan, wan, relay),
            localConnectionUris = listOf(lan),
            relayConnectionUris = listOf(relay),
        )
        val identityBody = """{"MediaContainer":{"machineIdentifier":"${server.id}"}}"""
        val outageEndsAtMs = AtomicLong(0L)
        val recovered = AtomicBoolean(false)
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                !path.endsWith("/identity") && !path.contains("/identity") ->
                    respond(content = "", status = HttpStatusCode.NotFound)
                System.currentTimeMillis() < outageEndsAtMs.get() ->
                    throw SSLHandshakeException("Remote host terminated the handshake")
                else -> {
                    recovered.set(true)
                    respond(
                        content = identityBody,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }
        val connectionResolver = PlexConnectionResolver(
            httpClient = testHttpClient(engine),
            database = db,
            databaseWriteGate = DatabaseWriteGate(),
        )
        connectionResolver.preferLocalNetworkProvider = { true }
        connectionResolver.useNetworkIdentityForTest(offLan)

        ArtworkAuthHolder.update("token")
        PlaybackOriginResolverHolder.resolver = object : PlaybackOriginResolver {
            override fun cachedOrigin(): String? = connectionResolver.liveProbedOrigin(server)

            override suspend fun resolveOrigin(deadlineMs: Long): String? =
                connectionResolver.resolveFresh(server, token = "token", deadlineMs = deadlineMs)

            override fun demoteLocalOrigins(): Boolean = connectionResolver.demoteLocalOrigins()
        }

        val player = OpenedUriRecordingPlayer()
        outageEndsAtMs.set(System.currentTimeMillis() + OutageMs)
        player.play(
            listOf(
                Track(
                    id = "plex:outage",
                    title = "Outage",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 60_000,
                    streamUrl = "/library/parts/9/file.mp3",
                    downloadUrl = "",
                ),
            ),
            0,
        )

        // During the outage the cold loop may race and miss, but must not open a host-less URI.
        val outageDeadline = outageEndsAtMs.get()
        while (System.currentTimeMillis() < outageDeadline) {
            assertEquals(
                0,
                player.openedUris.size,
                "outage opened unbound URI(s): ${player.openedUris}",
            )
            delay(50)
        }

        val recoverStarted = TimeSource.Monotonic.markNow()
        var opened: String? = null
        while (recoverStarted.elapsedNow().inWholeMilliseconds < RecoverBudgetMs) {
            opened = player.openedUris.firstOrNull()
            if (opened != null) break
            delay(50)
        }

        assertTrue(recovered.get(), "mock should have served a successful /identity after outage")
        assertNull(player.state.value.playbackErrorMessage)
        assertTrue(
            opened != null &&
                opened.startsWith("https://") &&
                opened.contains("/library/parts/9/file.mp3") &&
                !opened.startsWith("/library/"),
            "expected bound absolute stream within ${RecoverBudgetMs}ms after outage, got opened=$opened " +
                "elapsed=${recoverStarted.elapsedNow().inWholeMilliseconds}ms uris=${player.openedUris}",
        )
        assertTrue(
            recoverStarted.elapsedNow().inWholeMilliseconds < RecoverBudgetMs,
            "recovery took ${recoverStarted.elapsedNow().inWholeMilliseconds}ms",
        )
    }

    private class OpenedUriRecordingPlayer(
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    ) : SimpleAudioPlayer(scope) {
        val openedUris = mutableListOf<String>()

        override fun playUri(uri: String) {
            openedUris += uri
        }

        override fun playQueueOnPlatform(
            queue: List<Track>,
            startIndex: Int,
            track: Track,
            generation: Int,
            startPositionMs: Long,
        ) {
            openedUris += StreamingPlaybackPolicyHolder.resolvePlaybackUri(track)
        }

        override fun skipToInQueueOnPlatform(
            queue: List<Track>,
            startIndex: Int,
            track: Track,
            generation: Int,
        ) {
            openedUris += StreamingPlaybackPolicyHolder.resolvePlaybackUri(track)
        }
    }

    /** Name-matched to production SSL failures so probe logging stays realistic. */
    private class SSLHandshakeException(message: String) : IOException(message)

    companion object {
        /** How long `/identity` keeps aborting — stand-in for a plex.direct TLS outage. */
        const val OutageMs = 2_000L
        /**
         * After mocks recover, cold retries + one identity race must bind and open well under
         * the ~5s user-visible bar (includes up to one [ColdOriginResolveSustainedDelayMs]).
         */
        const val RecoverBudgetMs = 5_000L
    }
}
