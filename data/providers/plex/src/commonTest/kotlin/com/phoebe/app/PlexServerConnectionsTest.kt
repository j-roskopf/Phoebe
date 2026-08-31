package com.phoebe.app

import com.phoebe.app.data.decodedIpFromPlexDirect
import com.phoebe.app.data.expandConnectionUris
import com.phoebe.app.data.isLocalOnlyServerOrigin
import com.phoebe.app.data.isPlexRelayOrigin
import com.phoebe.app.data.isPublicSynthesizedPlexHttpOrigin
import com.phoebe.app.data.reachableBaseUris
import com.phoebe.app.data.shouldSkipAdvertisedLan
import com.phoebe.app.data.timelineBaseUris
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.platform.NetworkIdentity
import com.phoebe.app.platform.NetworkTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlexServerConnectionsTest {
    @Test
    fun decodedIpFromPlexDirectParsesDashedHost() {
        assertEquals(
            "172.105.8.66",
            decodedIpFromPlexDirect("https://172-105-8-66.abc.plex.direct:8443"),
        )
    }

    @Test
    fun expandConnectionUrisAddsOnlyThePlainLanIpPort() {
        val expanded = expandConnectionUris(
            listOf("https://172-16-1-2.abc.plex.direct:32400"),
        )
        assertTrue("http://172.16.1.2:32400" in expanded)
        // Plex's cert covers *.plex.direct, so bare-IP TLS can never complete a handshake.
        assertFalse("https://172.16.1.2:8443" in expanded)
        assertFalse("https://172.16.1.2:32400" in expanded)
    }

    @Test
    fun expandConnectionUrisDoesNotSynthesizePublicWanHttp32400() {
        val advertised = "https://172-105-8-66.abc.plex.direct:8443"
        val expanded = expandConnectionUris(listOf(advertised))
        assertEquals(listOf(advertised), expanded)
        assertFalse("http://172.105.8.66:32400" in expanded)
    }

    @Test
    fun expandConnectionUrisSynthesizesNothingWhenHttpsRequired() {
        val advertised = "https://172-105-8-66.abc.plex.direct:8443"

        val expanded = expandConnectionUris(listOf(advertised), httpsRequired = true)

        assertEquals(listOf(advertised), expanded)
    }

    @Test
    fun reachableBaseUrisPrefersLocalAdvertisedLan() {
        val advertised = listOf(
            "https://172-105-8-66.abc.plex.direct:8443",
            "http://192.168.86.43:32400",
        )
        val server = PlexServer(
            id = "s1",
            name = "plex",
            uri = "http://192.168.86.43:32400",
            owned = true,
            connectionUris = expandConnectionUris(advertised),
            advertisedConnectionUris = advertised,
            localConnectionUris = listOf("http://192.168.86.43:32400"),
        )
        assertEquals("http://192.168.86.43:32400", server.reachableBaseUris().first())
    }

    @Test
    fun reachableBaseUrisAdvertisedBeforeSynthesizedLanIp() {
        val advertised = listOf("https://172-16-1-2.abc.plex.direct:32400")
        val server = PlexServer(
            id = "s1",
            name = "plex",
            uri = advertised.first(),
            owned = true,
            connectionUris = expandConnectionUris(advertised),
            advertisedConnectionUris = advertised,
        )
        val ordered = server.reachableBaseUris()
        assertEquals(advertised.first(), ordered.first())
        assertTrue(ordered.indexOf("http://172.16.1.2:32400") > 0)
    }

    @Test
    fun reachableBaseUrisPrefersPersistedPrimaryUri() {
        val advertised = listOf("https://172-105-8-66.abc.plex.direct:8443")
        val server = PlexServer(
            id = "s1",
            name = "plex",
            uri = "http://reachable.example:32400",
            owned = true,
            connectionUris = listOf("http://reachable.example:32400") + expandConnectionUris(advertised),
            advertisedConnectionUris = advertised,
        )
        assertEquals("http://reachable.example:32400", server.reachableBaseUris().first())
    }

    @Test
    fun localOnlyServerOriginDetectsLanAndPrivatePlexDirect() {
        assertTrue(isLocalOnlyServerOrigin("https://172-16-1-2.abc.plex.direct:32400"))
        assertTrue(isLocalOnlyServerOrigin("http://192.168.1.9:32400"))
        assertFalse(isLocalOnlyServerOrigin("https://45-79-202-250.abc.plex.direct:8443"))
        assertFalse(isLocalOnlyServerOrigin("https://72-58-82-53.abc.plex.direct:32400"))
        assertFalse(isLocalOnlyServerOrigin("file:///music/song.mp3"))
    }

    @Test
    fun reachableBaseUrisRanksLocalThenDirectRemoteThenRelay() {
        val lan = "http://192.168.86.43:32400"
        val directRemote = "https://72-58-82-53.abc.plex.direct:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = PlexServer(
            id = "s1",
            name = "plex",
            uri = lan,
            owned = true,
            connectionUris = expandConnectionUris(listOf(lan, directRemote, relay)),
            advertisedConnectionUris = listOf(lan, directRemote, relay),
            localConnectionUris = listOf(lan),
            relayConnectionUris = listOf(relay),
        )
        val ordered = server.reachableBaseUris()
        assertEquals(lan, ordered.first())
        assertTrue(ordered.indexOf(directRemote) < ordered.indexOf(relay))
    }

    @Test
    fun reachableBaseUrisKeepsRelaysLastEvenWhenLanDemoted() {
        val lan = "http://192.168.86.43:32400"
        val directRemote = "https://72-58-82-53.abc.plex.direct:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = PlexServer(
            id = "s1",
            name = "plex",
            uri = lan,
            owned = true,
            connectionUris = expandConnectionUris(listOf(lan, directRemote, relay)),
            advertisedConnectionUris = listOf(lan, directRemote, relay),
            localConnectionUris = listOf(lan),
            relayConnectionUris = listOf(relay),
        )
        val ordered = server.reachableBaseUris(demoteLocalOrigins = true)
        assertEquals(directRemote, ordered.first())
        assertTrue(ordered.indexOf(directRemote) < ordered.indexOf(relay))
        assertTrue(ordered.indexOf(relay) < ordered.indexOf(lan))
    }

    @Test
    fun reachableBaseUrisPrefersRemoteRelayOverPublicLanPort() {
        val lanDirect = "https://172-16-1-2.abc.plex.direct:32400"
        val remoteRelay = "https://45-79-202-250.abc.plex.direct:8443"
        val closedWan = "https://72-58-82-53.abc.plex.direct:32400"
        val server = PlexServer(
            id = "s1",
            name = "plex",
            uri = lanDirect,
            owned = true,
            connectionUris = expandConnectionUris(listOf(lanDirect, remoteRelay, closedWan)),
            advertisedConnectionUris = listOf(lanDirect, remoteRelay, closedWan),
            localConnectionUris = listOf(lanDirect),
            relayConnectionUris = listOf(remoteRelay),
            httpsRequired = true,
        )
        val ordered = server.reachableBaseUris()
        // Direct remote (:32400 plex.direct) ranks ahead of relay (:8443).
        assertTrue(ordered.indexOf(closedWan) < ordered.indexOf(remoteRelay))
        assertTrue(
            ordered.none { it.contains("72.58.82.53") || it.contains("45.79.202.250") },
            "an https-only server gets no bare-IP fallbacks to waste attempts on",
        )
    }

    @Test
    fun timelineBaseUrisSkipsLanWhenPreferredOriginIsRemote() {
        val lanDirect = "https://172-16-1-2.abc.plex.direct:32400"
        val remoteRelay = "https://45-79-202-250.abc.plex.direct:8443"
        val closedWan = "https://72-58-82-53.abc.plex.direct:32400"
        val server = PlexServer(
            id = "s1",
            name = "plex",
            uri = lanDirect,
            owned = true,
            connectionUris = expandConnectionUris(listOf(lanDirect, remoteRelay, closedWan)),
            advertisedConnectionUris = listOf(lanDirect, remoteRelay, closedWan),
            localConnectionUris = listOf(lanDirect),
            httpsRequired = true,
        )
        val ordered = server.timelineBaseUris(remoteRelay)
        assertEquals(remoteRelay, ordered.first())
        val firstLocal = ordered.indexOfFirst { isLocalOnlyServerOrigin(it) }
        val lastRemote = ordered.indexOfLast { !isLocalOnlyServerOrigin(it) }
        assertTrue(firstLocal >= 0, "LAN bases stay as a last-resort fallback")
        assertTrue(lastRemote < firstLocal, "remote command bases must come before LAN")
        assertTrue(closedWan in ordered)
    }

    @Test
    fun timelineBaseUrisKeepsLanWhenPreferredOriginIsLocal() {
        val lan = "http://192.168.1.9:32400"
        val remoteRelay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = PlexServer(
            id = "s1",
            name = "plex",
            uri = lan,
            owned = true,
            connectionUris = expandConnectionUris(listOf(lan, remoteRelay)),
            advertisedConnectionUris = listOf(lan, remoteRelay),
            localConnectionUris = listOf(lan),
        )
        val ordered = server.timelineBaseUris(lan)
        assertEquals(lan, ordered.first())
        assertTrue(ordered.any { isLocalOnlyServerOrigin(it) })
        assertTrue(remoteRelay in ordered)
    }

    @Test
    fun reachableBaseUrisDemotesLocalOriginsOnCellularHint() {
        val lan = "http://192.168.86.43:32400"
        val remoteRelay = "https://172-105-8-66.abc.plex.direct:8443"
        val server = PlexServer(
            id = "s1",
            name = "plex",
            uri = lan,
            owned = true,
            connectionUris = expandConnectionUris(listOf(lan, remoteRelay)),
            advertisedConnectionUris = listOf(lan, remoteRelay),
            localConnectionUris = listOf(lan),
            relayConnectionUris = listOf(remoteRelay),
        )
        val ordered = server.reachableBaseUris(demoteLocalOrigins = true)
        assertEquals(remoteRelay, ordered.first())
        assertTrue(ordered.indexOf(lan) > ordered.indexOf(remoteRelay))
    }

    @Test
    fun timelineBaseUrisDemotesLocalWhenHintedEvenIfPreferredIsLocal() {
        val lan = "http://192.168.1.9:32400"
        val remoteRelay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = PlexServer(
            id = "s1",
            name = "plex",
            uri = lan,
            owned = true,
            connectionUris = expandConnectionUris(listOf(lan, remoteRelay)),
            advertisedConnectionUris = listOf(lan, remoteRelay),
            localConnectionUris = listOf(lan),
        )
        val ordered = server.timelineBaseUris(preferredFirst = lan, demoteLocalOrigins = true)
        assertEquals(remoteRelay, ordered.first { !isLocalOnlyServerOrigin(it) })
        val firstLocal = ordered.indexOfFirst { isLocalOnlyServerOrigin(it) }
        val lastRemote = ordered.indexOfLast { !isLocalOnlyServerOrigin(it) }
        assertTrue(lastRemote < firstLocal)
    }

    @Test
    fun authTokenPrefersServerAccessToken() {
        val server = PlexServer(
            id = "s1",
            name = "plex",
            uri = "http://localhost:32400",
            owned = true,
            accessToken = "server-specific",
        )
        assertEquals("server-specific", server.authToken("user-token"))
    }

    @Test
    fun publicSynthesizedPlexHttpOriginIsTheClosedWanPort() {
        assertTrue(isPublicSynthesizedPlexHttpOrigin("http://45.33.97.28:32400"))
        assertTrue(isPublicSynthesizedPlexHttpOrigin("http://72.58.82.53:32400/library/parts/1/file.mp3"))
        assertFalse(isPublicSynthesizedPlexHttpOrigin("http://172.16.1.2:32400"))
        assertFalse(isPublicSynthesizedPlexHttpOrigin("http://192.168.4.9:32400"))
        assertFalse(isPublicSynthesizedPlexHttpOrigin("https://45-33-97-28.abc.plex.direct:8443"))
        assertFalse(isPublicSynthesizedPlexHttpOrigin("https://72-58-82-53.abc.plex.direct:32400"))
        assertFalse(
            isPublicSynthesizedPlexHttpOrigin(
                "http://45.33.97.28:32400",
                advertisedUris = listOf("http://45.33.97.28:32400"),
            ),
        )
    }

    @Test
    fun skipAdvertisedLanWhenClientIsOnADifferentPrivateSubnet() {
        val server = PlexServer(
            id = "s1",
            name = "plex",
            uri = "http://172.16.1.2:32400",
            owned = true,
            advertisedConnectionUris = listOf(
                "http://172.16.1.2:32400",
                "https://45-79-210-125.abc.plex.direct:8443",
            ),
            localConnectionUris = listOf("http://172.16.1.2:32400"),
        )
        val windowsHome = NetworkIdentity(
            transport = NetworkTransport.Other,
            fingerprint = "other-home",
            localIpv4Prefixes = listOf("192.168.4.0"),
        )
        assertTrue(windowsHome.shouldSkipAdvertisedLan(server))
        val onServerLan = NetworkIdentity(
            transport = NetworkTransport.Other,
            fingerprint = "other-lan",
            localIpv4Prefixes = listOf("172.16.1.0"),
        )
        assertFalse(onServerLan.shouldSkipAdvertisedLan(server))
        val unknown = NetworkIdentity(transport = NetworkTransport.Other, fingerprint = "other")
        assertFalse(unknown.shouldSkipAdvertisedLan(server))
    }

    @Test
    fun isPlexRelayOriginTreatsRotated8443AsRelayEvenWhenListDiffers() {
        val currentRelay = "https://45-79-202-250.abc.plex.direct:8443"
        val staleRelay = "https://173-230-133-75.abc.plex.direct:8443"
        val server = PlexServer(
            id = "s1",
            name = "plex",
            uri = "https://172-16-1-2.abc.plex.direct:32400",
            owned = true,
            advertisedConnectionUris = listOf(currentRelay),
            relayConnectionUris = listOf(currentRelay),
        )
        assertTrue(isPlexRelayOrigin(currentRelay, server))
        assertTrue(isPlexRelayOrigin(staleRelay, server))
        assertFalse(isPlexRelayOrigin("https://72-58-82-53.abc.plex.direct:32400", server))
        assertFalse(isPlexRelayOrigin("https://172-16-1-2.abc.plex.direct:32400", server))
    }

    @Test
    fun reachableBaseUrisUnionsRelayListWhenAdvertisedOmitsThem() {
        val lan = "http://172.16.1.2:32400"
        val wan = "https://72-58-82-53.abc.plex.direct:32400"
        val relay = "https://45-79-202-250.abc.plex.direct:8443"
        val server = PlexServer(
            id = "s1",
            name = "plex",
            uri = lan,
            owned = true,
            connectionUris = expandConnectionUris(listOf(lan, wan)),
            advertisedConnectionUris = listOf(lan, wan),
            localConnectionUris = listOf(lan),
            relayConnectionUris = listOf(relay),
        )
        val ordered = server.reachableBaseUris()
        assertTrue(relay in ordered, "relay column must be raced even when advertised is LAN/WAN only")
        assertTrue(ordered.indexOf(wan) < ordered.indexOf(relay))
    }

    @Test
    fun reachableBaseUrisDoesNotPrependUnknownPreferredOrigin() {
        val lan = "https://172-16-1-2.abc.plex.direct:32400"
        val remote = "https://72-58-82-53.abc.plex.direct:32400"
        val liveRelay = "https://23-92-30-53.abc.plex.direct:8443"
        val staleRelay = "https://173-230-133-75.abc.plex.direct:8443"
        val server = PlexServer(
            id = "s1",
            name = "plex",
            uri = lan,
            owned = true,
            connectionUris = expandConnectionUris(listOf(lan, remote, liveRelay)),
            advertisedConnectionUris = listOf(lan, remote, liveRelay),
            localConnectionUris = listOf(lan),
            relayConnectionUris = listOf(liveRelay),
        )
        val ordered = server.reachableBaseUris(preferredFirst = staleRelay)
        assertFalse(ordered.any { it == staleRelay })
        assertEquals(lan, ordered.first())
        assertTrue(liveRelay in ordered)
    }
}
