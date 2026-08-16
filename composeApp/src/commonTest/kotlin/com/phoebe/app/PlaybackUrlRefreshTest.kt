package com.phoebe.app

import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackUrlRefreshTest {
    @Test
    fun plexPlaybackUrlsReplaceStaleTokenAndPreserveOtherQueryParameters() {
        val track = playbackTrack(
            streamUrl = "https://plex.example/library/parts/1/file.mp3?X-Plex-Token=old&download=0",
            downloadUrl = "https://plex.example/library/parts/1/file.mp3?download=1&X-Plex-Token=old",
        )

        val refreshed = listOf(track).withFreshPlaybackUrls(
            PlexSession(token = "fresh", providerType = MediaProviderType.Plex),
        ).single()

        assertEquals(
            "https://plex.example/library/parts/1/file.mp3?X-Plex-Token=fresh&download=0",
            refreshed.streamUrl,
        )
        assertEquals(
            "https://plex.example/library/parts/1/file.mp3?download=1&X-Plex-Token=fresh",
            refreshed.downloadUrl,
        )
    }

    @Test
    fun plexPlaybackUrlsRebaseStaleRelayHostsOntoTheLiveServerOrigin() {
        val track = playbackTrack(
            streamUrl = "https://23-239-17-63.abc.plex.direct:8443/library/parts/36576/file.mp3?X-Plex-Token=old",
            downloadUrl = "https://23-239-17-63.abc.plex.direct:8443/library/parts/36576/file.mp3?download=1&X-Plex-Token=old",
        )
        val live = "https://45-79-210-225.abc.plex.direct:8443"
        val session = PlexSession(
            token = "fresh",
            providerType = MediaProviderType.Plex,
            selectedServer = PlexServer(
                id = "plex",
                name = "Plex",
                uri = live,
                owned = true,
                connectionUris = listOf(live, "https://23-239-17-63.abc.plex.direct:8443"),
                advertisedConnectionUris = listOf(live, "https://23-239-17-63.abc.plex.direct:8443"),
            ),
        )

        val refreshed = listOf(track).withFreshPlaybackUrls(session, live).single()

        assertEquals(
            "$live/library/parts/36576/file.mp3?X-Plex-Token=fresh",
            refreshed.streamUrl,
        )
        assertEquals(
            "$live/library/parts/36576/file.mp3?download=1&X-Plex-Token=fresh",
            refreshed.downloadUrl,
        )
        assertTrue(
            refreshed.playbackFallbackUrls.any { it.contains("23-239-17-63") && it.contains("X-Plex-Token=fresh") },
        )
    }

    @Test
    fun jellyfinPlaybackUrlsAppendOrReplaceApiKey() {
        val track = playbackTrack(
            streamUrl = "https://jellyfin.example/Audio/1/stream.mp3?static=true",
            downloadUrl = "https://jellyfin.example/Audio/1/stream.mp3?api_key=old",
        )

        val refreshed = listOf(track).withFreshPlaybackUrls(
            PlexSession(token = "fresh", providerType = MediaProviderType.Jellyfin),
        ).single()

        assertEquals(
            "https://jellyfin.example/Audio/1/stream.mp3?static=true&api_key=fresh",
            refreshed.streamUrl,
        )
        assertEquals(
            "https://jellyfin.example/Audio/1/stream.mp3?api_key=fresh",
            refreshed.downloadUrl,
        )
    }

    @Test
    fun navidromePlaybackUrlsRefreshUserAndPasswordParameters() {
        val track = playbackTrack(
            streamUrl = "https://navidrome.example/rest/stream.view?id=1&u=old&p=old",
            downloadUrl = "https://navidrome.example/rest/download.view?id=1",
        )

        val refreshed = listOf(track).withFreshPlaybackUrls(
            PlexSession(
                token = "fresh-password",
                userName = "fresh-user",
                providerType = MediaProviderType.Navidrome,
            ),
        ).single()

        assertEquals(
            "https://navidrome.example/rest/stream.view?id=1&u=fresh-user&p=fresh-password",
            refreshed.streamUrl,
        )
        assertEquals(
            "https://navidrome.example/rest/download.view?id=1&u=fresh-user&p=fresh-password",
            refreshed.downloadUrl,
        )
    }

    @Test
    fun nonHttpAndMusicAssistantPlaybackUrlsAreLeftAlone() {
        val track = playbackTrack(
            streamUrl = "file:///music/song.mp3",
            downloadUrl = "https://music-assistant.example/song.mp3?token=old",
        )

        val refreshed = listOf(track).withFreshPlaybackUrls(
            PlexSession(token = "fresh", providerType = MediaProviderType.MusicAssistant),
        ).single()

        assertEquals(track, refreshed)
    }

    private fun playbackTrack(
        streamUrl: String,
        downloadUrl: String,
    ): Track = Track(
        id = "track:1",
        title = "One",
        artist = "Artist",
        album = "Album",
        durationMs = 1_000,
        streamUrl = streamUrl,
        downloadUrl = downloadUrl,
    )
}
