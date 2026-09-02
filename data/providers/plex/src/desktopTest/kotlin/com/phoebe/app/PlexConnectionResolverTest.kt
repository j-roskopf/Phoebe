package com.phoebe.app

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.SqlDriver
import com.phoebe.app.data.PlexClient
import com.phoebe.app.data.PlexConnectionResolver
import com.phoebe.app.data.ArtworkAuthHolder
import com.phoebe.app.data.ArtworkOriginHolder
import com.phoebe.app.data.db.DatabaseWriteGate
import com.phoebe.app.data.expandConnectionUris
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.platform.NetworkIdentity
import com.phoebe.app.platform.NetworkTransport
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Mirrors `javax.net.ssl.SSLHandshakeException` by name only — classification is name-based. */
private class SSLHandshakeException(message: String) : IOException(message)

class PlexConnectionResolverTest {
    private var driver: SqlDriver? = null

    private val wifiOnLan = NetworkIdentity(
        transport = NetworkTransport.Wifi,
        fingerprint = "test-wifi-lan",
        localIpv4Prefixes = listOf("192.168.1.0"),
    )

    @AfterTest
    fun tearDown() {
        ArtworkAuthHolder.clear()
        ArtworkOriginHolder.clear()
        // Resolver may still be deleting a skipped relay row on its own scope.
        Thread.sleep(75)
        driver?.close()
        driver = null
    }

    @Test
    fun hydrateFromDiskIgnoresRelayOrigins() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val relay = "https://45-79-222-231.abc.plex.direct:8443"
        val server = sampleServer(relayUris = listOf(relay))
        db.plexResolvedOriginQueries.upsertOrigin(
            serverId = server.id,
            networkFingerprint = wifiOnLan.fingerprint,
            origin = relay,
            updatedAtMs = currentTimeMs(),
        )
        val resolver = resolver(db)
        resolver.hydrateFromDisk(server)
        assertNull(resolver.cached(server))
    }

    @Test
    fun hydrateFromDiskIgnoresStaleRelayNotInCurrentRelayList() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val currentRelay = "https://45-79-222-231.abc.plex.direct:8443"
        val staleRelay = "https://173-230-133-75.abc.plex.direct:8443"
        val server = sampleServer(relayUris = listOf(currentRelay))
        db.plexResolvedOriginQueries.upsertOrigin(
            serverId = server.id,
            networkFingerprint = wifiOnLan.fingerprint,
            origin = staleRelay,
            updatedAtMs = currentTimeMs(),
        )
        val resolver = resolver(db)
        resolver.hydrateFromDisk(server)
        assertNull(resolver.cached(server))
    }

    @Test
    fun hydrateFromDiskAcceptsLanOrigin() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val server = sampleServer(lan = lan)
        db.plexResolvedOriginQueries.upsertOrigin(
            serverId = server.id,
            networkFingerprint = wifiOnLan.fingerprint,
            origin = lan,
            updatedAtMs = currentTimeMs(),
        )
        val resolver = resolver(db)
        resolver.hydrateFromDisk(server)
        assertEquals(lan, resolver.cached(server))
    }

    @Test
    fun racePicksLanOverRelayWhenBothSucceed() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = sampleServer(lan = lan, relayUris = listOf(relay))
        val identityBody = """{"MediaContainer":{"machineIdentifier":"${server.id}"}}"""
        val engine = MockEngine {
            respond(
                content = identityBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val resolver = resolver(db, engine)
        val winner = resolver.resolveFresh(server, token = "token", deadlineMs = 2_500L)
        assertEquals(lan, winner, "ranked race must prefer LAN over relay")
    }

    @Test
    fun racePublishesRelayWithoutWaitingForDeadDirectRemote() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val wan = "https://72-58-82-53.abc.plex.direct:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = sampleServer(
            lan = lan,
            relayUris = listOf(relay),
            extraAdvertised = listOf(wan),
        )
        val identityBody = """{"MediaContainer":{"machineIdentifier":"${server.id}"}}"""
        val engine = MockEngine { request ->
            if (request.url.host.startsWith("45-79-202-250")) {
                respond(
                    content = identityBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                delay(5_000)
                respond(
                    content = identityBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val resolver = resolver(db, engine)
        val started = TimeSource.Monotonic.markNow()
        val winner = resolver.resolveFresh(server, token = "token", deadlineMs = 8_000L)
        val elapsedMs = started.elapsedNow().inWholeMilliseconds
        assertEquals(relay, winner)
        assertEquals(relay, ArtworkOriginHolder.liveOrigin)
        assertTrue(
            elapsedMs < 3_000,
            "identity race waited ${elapsedMs}ms after relay succeeded",
        )
    }

    @Test
    fun rememberDoesNotPersistRelayToDisk() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = sampleServer(relayUris = listOf(relay))
        val resolver = resolver(db)
        resolver.hydrateFromDisk(server)
        resolver.remember(server.id, relay)
        val row = db.plexResolvedOriginQueries
            .selectOrigin(server.id, wifiOnLan.fingerprint)
            .awaitAsOneOrNull()
        assertNull(row)
        assertEquals(relay, resolver.cached(server))
    }

    @Test
    fun forgetClearsOriginAfterIdentitySuccessStyleCache() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val server = sampleServer(lan = lan)
        val resolver = resolver(db)
        resolver.remember(server.id, lan)
        assertEquals(lan, resolver.cached(server))
        resolver.forget(server.id, lan)
        assertNull(resolver.cached(server))
    }

    @Test
    fun hydrateFromDiskDoesNotCountAsLiveProbedOrigin() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val server = sampleServer(lan = lan)
        db.plexResolvedOriginQueries.upsertOrigin(
            serverId = server.id,
            networkFingerprint = wifiOnLan.fingerprint,
            origin = lan,
            updatedAtMs = currentTimeMs(),
        )
        val resolver = resolver(db)
        resolver.hydrateFromDisk(server)
        assertEquals(lan, resolver.cached(server))
        assertNull(resolver.liveProbedOrigin(server))
    }

    @Test
    fun resolveDoesNotReturnUnprobedHydratedLan() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val server = sampleServer(lan = lan)
        db.plexResolvedOriginQueries.upsertOrigin(
            serverId = server.id,
            networkFingerprint = wifiOnLan.fingerprint,
            origin = lan,
            updatedAtMs = currentTimeMs(),
        )
        val resolver = resolver(db) // identity would fail (no network)
        resolver.hydrateFromDisk(server)
        val winner = resolver.resolve(server, token = "token", deadlineMs = 200L)
        assertNull(winner)
        assertNull(resolver.liveProbedOrigin(server))
    }

    @Test
    fun rememberMarksOriginLiveProbed() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = sampleServer(relayUris = listOf(relay))
        val resolver = resolver(db)
        resolver.remember(server.id, relay)
        assertEquals(relay, resolver.liveProbedOrigin(server))
        assertEquals(relay, resolver.probedOrigin.value)
        assertEquals(relay, ArtworkOriginHolder.liveOrigin)
    }

    @Test
    fun aSecondRelayDoesNotDisplaceTheEstablishedOne() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val firstRelay = "https://45-79-202-250.abc.plex.direct:8443"
        val secondRelay = "https://23-92-30-53.abc.plex.direct:8443"
        val server = sampleServer(relayUris = listOf(firstRelay, secondRelay))
        val resolver = resolver(db)
        resolver.hydrateFromDisk(server)
        resolver.remember(server.id, firstRelay)

        // plex.tv rotates relays, so a second in-flight API call finishes on the other one. That
        // is not a reason to move the whole app onto it: each move re-binds artwork mid-load.
        resolver.remember(server.id, secondRelay)

        assertEquals(firstRelay, resolver.probedOrigin.value)
        assertEquals(firstRelay, ArtworkOriginHolder.liveOrigin)
        assertEquals(firstRelay, resolver.cached(server))
    }

    @Test
    fun aBetterHopStillDisplacesAnEstablishedRelay() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = sampleServer(lan = lan, relayUris = listOf(relay))
        val resolver = resolver(db)
        resolver.hydrateFromDisk(server)
        resolver.remember(server.id, relay)

        resolver.remember(server.id, lan)

        assertEquals(lan, resolver.probedOrigin.value, "LAN outranks a relay and must take over")
        assertEquals(lan, ArtworkOriginHolder.liveOrigin)
    }

    @Test
    fun forgettingTheEstablishedRelayLetsTheNextOneTakeOver() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val firstRelay = "https://45-79-202-250.abc.plex.direct:8443"
        val secondRelay = "https://23-92-30-53.abc.plex.direct:8443"
        val server = sampleServer(relayUris = listOf(firstRelay, secondRelay))
        val resolver = resolver(db)
        resolver.hydrateFromDisk(server)
        resolver.remember(server.id, firstRelay)
        resolver.forget(server.id, firstRelay)

        resolver.remember(server.id, secondRelay)

        assertEquals(secondRelay, resolver.probedOrigin.value)
        assertEquals(secondRelay, resolver.cached(server))
    }

    @Test
    fun withReachableBaseHitsOnlyTheProbedRelay() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = sampleServer(lan = lan, relayUris = listOf(relay))
        val attemptedHosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            attemptedHosts += request.url.host
            respond(
                content = """{"MediaContainer":{"size":0,"Metadata":[]}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val resolver = resolver(db, engine)
        resolver.remember(server.id, relay)
        val client = PlexClient(testHttpClient(engine), resolver)
        client.recentTrackPlaybackStatsPage(
            server = server,
            library = com.phoebe.app.domain.MusicLibrary(key = "2", title = "Music"),
            token = "token",
            start = 0,
            size = 50,
        )
        assertEquals(listOf("45-79-202-250.abc.plex.direct"), attemptedHosts)
    }

    @Test
    fun http400DoesNotForgetProbedRelayOrStartANewRace() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = sampleServer(lan = lan, relayUris = listOf(relay))
        val attemptedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            attemptedPaths += request.url.encodedPath
            if (request.url.encodedPath.contains("/history/")) {
                respond(
                    content = "<html><h1>400 Bad Request</h1></html>",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "text/html"),
                )
            } else {
                respond(
                    content = """{"MediaContainer":{"size":0,"Metadata":[]}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val resolver = resolver(db, engine)
        resolver.remember(server.id, relay)
        val client = PlexClient(testHttpClient(engine), resolver)
        val failed = runCatching {
            client.playbackHistoryPage(
                server = server,
                token = "token",
                library = com.phoebe.app.domain.MusicLibrary(key = "2", title = "Music"),
                minViewedAtMs = null,
                start = 0,
                size = 50,
            )
        }
        assertTrue(failed.isFailure)
        assertEquals(relay, resolver.liveProbedOrigin(server))
        assertEquals(relay, ArtworkOriginHolder.liveOrigin)
        assertTrue(
            attemptedPaths.none { it.endsWith("/identity") },
            "HTTP 400 must not start another identity race, got $attemptedPaths",
        )
    }

    @Test
    fun sslHandshakeFailureOnProbedRelayForgetsItAndFailsOverToLan() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = sampleServer(lan = lan, relayUris = listOf(relay))
        val identityBody = """{"MediaContainer":{"machineIdentifier":"${server.id}"}}"""
        var relayDataAttempts = 0
        val engine = MockEngine { request ->
            val isIdentity = request.url.encodedPath.endsWith("/identity")
            when {
                request.url.host.startsWith("45-79-202-250") && isIdentity ->
                    respond("", HttpStatusCode.ServiceUnavailable)
                request.url.host.startsWith("45-79-202-250") -> {
                    relayDataAttempts++
                    throw SSLHandshakeException("handshake failed")
                }
                isIdentity -> respond(
                    content = identityBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond(
                    content = """{"MediaContainer":{"size":0,"Metadata":[]}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val resolver = resolver(db, engine)
        resolver.remember(server.id, relay)
        val client = PlexClient(testHttpClient(engine), resolver)

        client.recentTrackPlaybackStatsPage(
            server = server,
            library = com.phoebe.app.domain.MusicLibrary(key = "2", title = "Music"),
            token = "token",
            start = 0,
            size = 50,
        )

        assertEquals(1, relayDataAttempts, "must not keep hammering the dead relay")
        assertEquals(lan, resolver.liveProbedOrigin(server))
    }

    @Test
    fun resolveFreshReturnsLiveOriginWithoutReprobing() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = sampleServer(relayUris = listOf(relay))
        val attempted = mutableListOf<String>()
        val engine = MockEngine { request ->
            attempted += request.url.host
            respond(
                content = """{"MediaContainer":{"machineIdentifier":"${server.id}"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val resolver = resolver(db, engine)
        resolver.remember(server.id, relay)
        val winner = resolver.resolveFresh(server, token = "token")
        assertEquals(relay, winner)
        assertTrue(attempted.isEmpty(), "live origin must not be re-probed, got $attempted")
    }

    @Test
    fun raceCancelsDirectRemoteAfterRelayWhenLanIsDemoted() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val wan = "https://72-58-82-53.abc.plex.direct:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = sampleServer(
            lan = lan,
            relayUris = listOf(relay),
            extraAdvertised = listOf(wan),
        )
        val identityBody = """{"MediaContainer":{"machineIdentifier":"${server.id}"}}"""
        val engine = MockEngine { request ->
            if (request.url.host.startsWith("45-79-202-250")) {
                respond(
                    content = identityBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                delay(5_000)
                respond(
                    content = identityBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val resolver = resolver(db, engine)
        resolver.useNetworkIdentityForTest(
            NetworkIdentity(
                transport = NetworkTransport.Other,
                fingerprint = "other-off-lan",
                localIpv4Prefixes = listOf("10.0.0.0"),
            ),
        )
        val started = TimeSource.Monotonic.markNow()
        val winner = resolver.resolveFresh(server, token = "token", deadlineMs = 8_000L)
        val elapsedMs = started.elapsedNow().inWholeMilliseconds
        assertEquals(relay, winner)
        assertTrue(
            elapsedMs < 800,
            "relay win must cancel dead WAN, waited ${elapsedMs}ms",
        )
    }

    @Test
    fun withReachableBaseDoesNotWalkLanAfterMissedRace() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = sampleServer(lan = lan, relayUris = listOf(relay))
        val attemptedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            attemptedPaths += request.url.encodedPath
            respond(
                content = """{"MediaContainer":{"size":0}}""",
                status = HttpStatusCode.RequestTimeout,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val resolver = resolver(db, engine)
        val client = PlexClient(testHttpClient(engine), resolver)
        assertFailsWith<IllegalStateException> {
            client.recentTrackPlaybackStatsPage(
                server = server,
                library = com.phoebe.app.domain.MusicLibrary(key = "2", title = "Music"),
                token = "token",
                start = 0,
                size = 50,
            )
        }
        assertTrue(attemptedPaths.isNotEmpty())
        assertTrue(
            attemptedPaths.all { it.endsWith("/identity") },
            "missed identity race must not walk LAN API paths, got $attemptedPaths",
        )
    }

    @Test
    fun shortDeadlineIsHonouredInsteadOfWaitingOutRelayTls() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val wan = "https://72-58-82-53.abc.plex.direct:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = sampleServer(
            lan = lan,
            relayUris = listOf(relay),
            extraAdvertised = listOf(wan),
        )
        val identityBody = """{"MediaContainer":{"machineIdentifier":"${server.id}"}}"""
        val engine = MockEngine { request ->
            if (request.url.host.startsWith("45-79-202-250")) {
                delay(2_500)
                respond(
                    content = identityBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                delay(10_000)
                respond(
                    content = identityBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val resolver = resolver(db, engine)
        val started = TimeSource.Monotonic.markNow()
        val winner = resolver.resolveFresh(server, token = "token", deadlineMs = 1_500L)
        val elapsedMs = started.elapsedNow().inWholeMilliseconds
        // The relay needs 2.5s of TLS, so a 1.5s budget cannot produce a winner. What matters is
        // that the caller gets its answer back inside the budget it asked for: `deadlineMs` used
        // to be ignored for relays, which made every play-time deadline meaningless.
        assertNull(winner)
        assertTrue(elapsedMs < 2_200, "resolve overran its 1.5s deadline by ${elapsedMs}ms")
    }

    @Test
    fun generousDeadlineStillReachesRelayOverTls() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val wan = "https://72-58-82-53.abc.plex.direct:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = sampleServer(
            lan = lan,
            relayUris = listOf(relay),
            extraAdvertised = listOf(wan),
        )
        val identityBody = """{"MediaContainer":{"machineIdentifier":"${server.id}"}}"""
        val engine = MockEngine { request ->
            if (request.url.host.startsWith("45-79-202-250")) {
                delay(2_500)
                respond(
                    content = identityBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                delay(10_000)
                respond(
                    content = identityBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val resolver = resolver(db, engine)
        val winner = resolver.resolveFresh(
            server,
            token = "token",
            deadlineMs = PlexConnectionResolver.RemoteProbeTimeoutMs,
        )
        assertEquals(relay, winner)
    }

    @Test
    fun raceProbesRelayWhenAdvertisedOmitsIt() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val wan = "https://72-58-82-53.abc.plex.direct:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = PlexServer(
            id = "machine-1",
            name = "plex",
            uri = lan,
            owned = true,
            connectionUris = expandConnectionUris(listOf(lan, wan)),
            advertisedConnectionUris = listOf(lan, wan),
            localConnectionUris = listOf(lan),
            relayConnectionUris = listOf(relay),
        )
        val identityBody = """{"MediaContainer":{"machineIdentifier":"${server.id}"}}"""
        val engine = MockEngine { request ->
            if (request.url.host.startsWith("45-79-202-250")) {
                respond(
                    content = identityBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                delay(5_000)
                respond(
                    content = identityBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val resolver = resolver(db, engine)
        val winner = resolver.resolveFresh(server, token = "token", deadlineMs = 8_000L)
        assertEquals(relay, winner)
        assertEquals(relay, ArtworkOriginHolder.liveOrigin)
    }

    @Test
    fun concurrentResolveFreshSharesOneRace() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = sampleServer(lan = lan, relayUris = listOf(relay))
        val identityBody = """{"MediaContainer":{"machineIdentifier":"${server.id}"}}"""
        val attempted = mutableListOf<String>()
        val engine = MockEngine { request ->
            attempted += request.url.host
            delay(200)
            respond(
                content = identityBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val resolver = resolver(db, engine)
        val first = async { resolver.resolveFresh(server, token = "token") }
        val second = async { resolver.resolveFresh(server, token = "token") }
        val winners = listOf(first.await(), second.await())
        assertEquals(setOf(lan), winners.toSet())
        assertTrue(
            attempted.size <= 4,
            "two waiters must share one identity race, got ${attempted.size} probes: $attempted",
        )
    }

    @Test
    fun identityMismatchRejectsCandidate() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val server = sampleServer(lan = lan)
        val engine = MockEngine {
            respond(
                content = """{"MediaContainer":{"machineIdentifier":"other-server"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val resolver = resolver(db, engine)
        val winner = resolver.resolveFresh(server, token = "token", deadlineMs = 1_000L)
        assertNull(winner)
    }

    @Test
    fun resolveFreshRacesAgainAfterMiss() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val server = sampleServer(lan = lan)
        val identityBody = """{"MediaContainer":{"machineIdentifier":"${server.id}"}}"""
        var identityProbes = 0
        val engine = MockEngine {
            identityProbes++
            if (identityProbes == 1) {
                respond(
                    content = """{"MediaContainer":{"machineIdentifier":"other-server"}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(
                    content = identityBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val resolver = resolver(db, engine)
        assertNull(resolver.resolveFresh(server, token = "token", deadlineMs = 1_000L))
        val winner = resolver.resolveFresh(server, token = "token", deadlineMs = 1_000L)
        assertEquals(lan, winner)
        assertEquals(lan, ArtworkOriginHolder.liveOrigin)
    }

    @Test
    fun networkChangeReprobesDirectRemoteOriginFromPreviousNetwork() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        // A public :32400 that answers over the home LAN through NAT hairpin and nowhere else.
        val wan = "https://72-58-82-53.abc.plex.direct:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = sampleServer(
            lan = lan,
            relayUris = listOf(relay),
            extraAdvertised = listOf(wan),
        )
        val identityBody = """{"MediaContainer":{"machineIdentifier":"${server.id}"}}"""
        var onCellular = false
        val engine = MockEngine { request ->
            val host = request.url.host
            val reachable = when {
                host.startsWith("72-58-82-53") -> !onCellular
                host.startsWith("45-79-202-250") -> onCellular
                else -> false
            }
            if (reachable) {
                respond(
                    content = identityBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                delay(10_000)
                respond(content = "", status = HttpStatusCode.ServiceUnavailable)
            }
        }
        val resolver = resolver(db, engine)
        assertEquals(
            wan,
            resolver.resolveFresh(server, token = "token", deadlineMs = 3_000L),
            "direct remote should win on the home network",
        )

        onCellular = true
        resolver.applyNetworkIdentityForTest(
            NetworkIdentity(
                transport = NetworkTransport.Cellular,
                fingerprint = "test-cellular",
                metering = com.phoebe.app.platform.NetworkMeteringStatus(
                    isMetered = true,
                    isCellular = true,
                ),
            ),
        )
        // The wan origin was `/identity`-confirmed, but only on the previous network. Returning
        // it warm here is what left playback stalled on a dead host after a Wi-Fi -> cellular
        // handoff.
        assertEquals(
            relay,
            resolver.resolveFresh(server, token = "token", deadlineMs = 3_000L),
        )
    }

    @Test
    fun raceAdoptsBestRankedWinnerExactlyOnce() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = sampleServer(lan = lan, relayUris = listOf(relay))
        val identityBody = """{"MediaContainer":{"machineIdentifier":"${server.id}"}}"""
        val engine = MockEngine { request ->
            // Relay answers first; LAN is slower but better. Publishing every improvement made
            // artwork and the play queue rebase twice.
            if (!request.url.host.startsWith("45-79-202-250")) delay(300)
            respond(
                content = identityBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val resolver = resolver(db, engine)
        val seen = mutableListOf<String?>()
        val collector = async {
            resolver.probedOrigin.collect { seen += it }
        }
        val winner = resolver.resolveFresh(server, token = "token", deadlineMs = 3_000L)
        delay(200)
        collector.cancel()
        assertEquals(lan, winner)
        assertEquals(
            listOf(lan),
            seen.filterNotNull().distinct(),
            "race must adopt once, not publish each improvement",
        )
    }

    @Test
    fun originThatOnlyWorkedOnThePreviousNetworkIsNotLeftPublished() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val lan = "http://192.168.1.9:32400"
        val server = sampleServer(lan = lan)
        val identityBody = """{"MediaContainer":{"machineIdentifier":"${server.id}"}}"""
        var onCellular = false
        val engine = MockEngine {
            // Reachability is decided when the request goes out — i.e. while still on Wi-Fi —
            // and the answer only arrives long after the handoff. That is the real shape of the
            // problem: a probe that proves nothing about the network we are on by the time it
            // comes back.
            val reachable = !onCellular
            delay(600)
            if (reachable) {
                respond(
                    content = identityBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(content = "", status = HttpStatusCode.ServiceUnavailable)
            }
        }
        val resolver = resolver(db, engine)
        val race = async { resolver.resolveFresh(server, token = "token", deadlineMs = 3_000L) }
        delay(100)
        onCellular = true
        resolver.applyNetworkIdentityForTest(
            NetworkIdentity(
                transport = NetworkTransport.Cellular,
                fingerprint = "test-cellular",
                metering = com.phoebe.app.platform.NetworkMeteringStatus(
                    isMetered = true,
                    isCellular = true,
                ),
            ),
        )
        race.await()
        delay(900)

        // The LAN origin was reachable from the Wi-Fi we have already left. Leaving it published
        // would bind artwork and playback to an address that cannot work on the new network.
        assertNull(resolver.probedOrigin.value)
        assertNull(ArtworkOriginHolder.liveOrigin)
        assertNull(resolver.cached(server))
    }

    private fun resolver(
        db: com.phoebe.app.db.PhoebeDatabase,
        engine: MockEngine = MockEngine { error("no network") },
    ): PlexConnectionResolver {
        val resolver = PlexConnectionResolver(
            httpClient = testHttpClient(engine),
            database = db,
            databaseWriteGate = DatabaseWriteGate(),
        )
        resolver.preferLocalNetworkProvider = { true }
        resolver.useNetworkIdentityForTest(wifiOnLan)
        return resolver
    }

    private fun sampleServer(
        lan: String = "http://192.168.1.9:32400",
        relayUris: List<String> = emptyList(),
        extraAdvertised: List<String> = emptyList(),
    ): PlexServer {
        val advertised = listOf(lan) + extraAdvertised + relayUris
        return PlexServer(
            id = "machine-1",
            name = "plex",
            uri = lan,
            owned = true,
            connectionUris = expandConnectionUris(advertised),
            advertisedConnectionUris = advertised,
            localConnectionUris = listOf(lan),
            relayConnectionUris = relayUris,
        )
    }
}
