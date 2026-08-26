package com.phoebe.app

import com.phoebe.app.data.decodedIpFromPlexDirect
import com.phoebe.app.data.expandConnectionUris
import com.phoebe.app.data.isLocalOnlyServerOrigin
import com.phoebe.app.data.reachableBaseUris
import com.phoebe.app.data.timelineBaseUris
import com.phoebe.app.domain.PlexServer
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
    fun expandConnectionUrisAddsPlainLanAnd8443() {
        val expanded = expandConnectionUris(
            listOf("https://172-105-8-66.abc.plex.direct:8443"),
        )
        assertTrue("http://172.105.8.66:32400" in expanded)
        assertTrue("https://172.105.8.66:8443" in expanded)
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
    fun reachableBaseUrisAdvertisedBeforeSynthesizedIp() {
        val advertised = listOf("https://172-105-8-66.abc.plex.direct:8443")
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
        assertTrue(ordered.indexOf("http://172.105.8.66:32400") > 0)
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
            httpsRequired = true,
        )
        val ordered = server.reachableBaseUris()
        assertTrue(ordered.indexOf(remoteRelay) < ordered.indexOf(closedWan))
        assertTrue(ordered.indexOf(remoteRelay) < ordered.indexOf("https://72.58.82.53:32400"))
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
}
