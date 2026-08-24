package com.phoebe.app.player

import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackUrlOriginsTest {
    @Test
    fun rebasesStalePlexDirectPartUrlOntoTheLiveRelay() {
        val stale =
            "https://23-239-17-63.95629f759c4c47eaaf42abf3d9725767.plex.direct:8443/library/parts/36576/1780447465/file.mp3?X-Plex-Token=old"
        val live = "https://45-79-210-225.95629f759c4c47eaaf42abf3d9725767.plex.direct:8443"

        assertEquals(
            "https://45-79-210-225.95629f759c4c47eaaf42abf3d9725767.plex.direct:8443/library/parts/36576/1780447465/file.mp3?X-Plex-Token=old",
            rebaseHttpUrlOrigin(stale, live),
        )
    }

    @Test
    fun plexPartUrlsAreTreatedAsMusicServerStreamsEvenWhenTheRelayRotated() {
        assertTrue(
            isMusicServerStreamUrl(
                "https://23-239-17-63.abc.plex.direct:8443/library/parts/36576/file.mp3?X-Plex-Token=old",
            ),
        )
        assertFalse(isMusicServerStreamUrl("https://kexp.streamguys1.com/kexp128.mp3"))
        assertFalse(isMusicServerStreamUrl("file:///music/song.mp3"))
    }

    @Test
    fun playbackUrlsForOriginsPutTheLiveHostFirstAndKeepOtherRelaysAsFallbacks() {
        val stale =
            "https://23-239-17-63.abc.plex.direct:8443/library/parts/1/file.mp3?X-Plex-Token=token"
        val urls = playbackUrlsForOrigins(
            stale,
            listOf(
                "https://45-79-210-225.abc.plex.direct:8443",
                "https://23-239-17-63.abc.plex.direct:8443",
                "http://192.168.1.9:32400",
            ),
        )

        assertEquals(
            "https://45-79-210-225.abc.plex.direct:8443/library/parts/1/file.mp3?X-Plex-Token=token",
            urls.first(),
        )
        assertTrue(urls.any { it.startsWith("http://192.168.1.9:32400/library/parts/1/file.mp3") })
        assertTrue(urls.any { it.startsWith("https://23-239-17-63.abc.plex.direct:8443/library/parts/1/file.mp3") })
    }

    @Test
    fun trackWithPlaybackOriginsRewritesThePrimaryUrlAndStashesFallbacks() {
        val track = Track(
            id = "54588",
            title = "Souvenir",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000,
            streamUrl = "https://23-239-17-63.abc.plex.direct:8443/library/parts/36576/file.mp3?X-Plex-Token=old",
            downloadUrl = "https://23-239-17-63.abc.plex.direct:8443/library/parts/36576/file.mp3?X-Plex-Token=old&download=1",
        )
        val server = PlexServer(
            id = "plex",
            name = "Plex",
            uri = "https://23-239-17-63.abc.plex.direct:8443",
            owned = true,
            connectionUris = listOf(
                "https://45-79-210-225.abc.plex.direct:8443",
                "https://23-239-17-63.abc.plex.direct:8443",
            ),
            advertisedConnectionUris = listOf(
                "https://45-79-210-225.abc.plex.direct:8443",
                "https://23-239-17-63.abc.plex.direct:8443",
            ),
        )
        val origins = playbackOriginCandidates(
            server = server,
            preferredOrigin = "https://45-79-210-225.abc.plex.direct:8443",
        )
        val refreshed = track.withPlaybackOrigins(origins.first(), origins.drop(1))

        assertEquals(
            "https://45-79-210-225.abc.plex.direct:8443/library/parts/36576/file.mp3?X-Plex-Token=old",
            refreshed.streamUrl,
        )
        assertTrue(refreshed.playbackFallbackUrls.any { it.contains("23-239-17-63") })
        assertEquals(
            listOf(refreshed.streamUrl) + refreshed.playbackFallbackUrls,
            refreshed.playbackUriCandidates(),
        )
    }

    @Test
    fun radioAndLocalTracksAreNotRebasedOntoTheMusicServer() {
        val radio = Track(
            id = "radio:kexp",
            title = "KEXP",
            artist = "Radio",
            album = "Radio",
            durationMs = 0,
            streamUrl = "https://kexp.streamguys1.com/kexp128.mp3",
            downloadUrl = "",
        )
        val local = Track(
            id = "local:1",
            title = "Local",
            artist = "Artist",
            album = "Album",
            durationMs = 1_000,
            streamUrl = "https://23-239-17-63.abc.plex.direct:8443/library/parts/1/file.mp3",
            downloadUrl = "",
            localUri = "file:///music/song.mp3",
        )

        assertEquals(radio, radio.withPlaybackOrigins("https://plex.example:32400"))
        assertEquals(local, local.withPlaybackOrigins("https://plex.example:32400"))
        assertEquals(listOf("file:///music/song.mp3"), local.playbackUriCandidates())
    }

    @Test
    fun plexDirectLanHostsAreLocalOnlyOrigins() {
        assertTrue(
            isLocalOnlyPlaybackOrigin(
                "https://172-16-1-2.abc.plex.direct:32400/library/parts/1/file.mp3",
            ),
        )
        assertTrue(isLocalOnlyPlaybackOrigin("http://192.168.1.9:32400/library/parts/1/file.mp3"))
        assertTrue(isLocalOnlyPlaybackOrigin("http://10.0.0.5:8091/emby/Audio/1/stream"))
        assertFalse(
            isLocalOnlyPlaybackOrigin(
                "https://173-230-133-75.abc.plex.direct:8443/library/parts/1/file.mp3",
            ),
        )
        assertFalse(isLocalOnlyPlaybackOrigin("https://72-58-82-53.abc.plex.direct:32400/library/parts/1/file.mp3"))
    }

    @Test
    fun failoverAfterRemoteTimeoutSkipsLanOriginsAndCapsAttempts() {
        val remoteRelay = "https://173-230-133-75.abc.plex.direct:8443/library/parts/1/file.mp3"
        val lanDirect = "https://172-16-1-2.abc.plex.direct:32400/library/parts/1/file.mp3"
        val lanHttp = "http://172.16.1.2:32400/library/parts/1/file.mp3"
        val remoteAlt = "https://72-58-82-53.abc.plex.direct:32400/library/parts/1/file.mp3"
        val candidates = listOf(remoteRelay, lanDirect, lanHttp, remoteAlt)

        assertEquals(
            remoteAlt,
            nextPlaybackFailoverCandidate(
                candidates = candidates,
                tried = setOf(remoteRelay),
                failedUri = remoteRelay,
            ),
        )
        assertEquals(
            null,
            nextPlaybackFailoverCandidate(
                candidates = candidates,
                tried = setOf(remoteRelay, remoteAlt),
                failedUri = remoteAlt,
                maxTriedUris = 2,
            ),
        )
    }

    @Test
    fun failoverAfterLanFailureStillTriesRemoteRelays() {
        val lan = "http://192.168.1.9:32400/library/parts/1/file.mp3"
        val remote = "https://45-79-210-225.abc.plex.direct:8443/library/parts/1/file.mp3"
        assertEquals(
            remote,
            nextPlaybackFailoverCandidate(
                candidates = listOf(lan, remote),
                tried = setOf(lan),
                failedUri = lan,
            ),
        )
    }
}
