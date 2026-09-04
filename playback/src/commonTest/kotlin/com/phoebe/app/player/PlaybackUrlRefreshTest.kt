package com.phoebe.app.player

import com.phoebe.app.data.ArtworkAuthHolder
import com.phoebe.app.data.ArtworkOriginHolder
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackUrlRefreshTest {
    @Test
    fun plexQueueEntriesKeepRelativePartKeysWithNoHostOrToken() {
        val track = playbackTrack(
            streamUrl = "https://plex.example/library/parts/1/file.mp3?X-Plex-Token=old&download=0",
            downloadUrl = "https://plex.example/library/parts/1/file.mp3?download=1&X-Plex-Token=old",
        )

        val refreshed = listOf(track).withFreshPlaybackUrls(
            PlexSession(token = "fresh", providerType = MediaProviderType.Plex),
        ).single()

        // An address in the queue is an address that can go stale. Non-token query survives.
        assertEquals("/library/parts/1/file.mp3?download=0", refreshed.streamUrl)
        assertEquals("/library/parts/1/file.mp3?download=1", refreshed.downloadUrl)
        assertEquals(emptyList(), refreshed.playbackFallbackUrls)
    }

    @Test
    fun plexPlaybackUriBindsRelativePartKeyOntoTheLiveOriginAtPlayTime() {
        val live = "https://45-79-210-225.abc.plex.direct:8443"
        ArtworkOriginHolder.update(live)
        ArtworkAuthHolder.update("fresh")
        try {
            val track = playbackTrack(
                streamUrl = "/library/parts/36576/file.mp3",
                downloadUrl = "/library/parts/36576/file.mp3",
            )
            assertEquals(
                "$live/library/parts/36576/file.mp3?X-Plex-Token=fresh",
                StreamingPlaybackPolicyHolder.resolvePlaybackUri(track),
            )
        } finally {
            ArtworkOriginHolder.clear()
            ArtworkAuthHolder.clear()
        }
    }

    @Test
    fun plexPlaybackUriRehomesAStaleRelayHostOntoTheLiveOrigin() {
        val live = "https://45-79-210-225.abc.plex.direct:8443"
        ArtworkOriginHolder.update(live)
        ArtworkAuthHolder.update("fresh")
        try {
            // A queue persisted before this change, or restored from an old session.
            val track = playbackTrack(
                streamUrl =
                    "https://23-239-17-63.abc.plex.direct:8443/library/parts/36576/file.mp3?X-Plex-Token=old",
                downloadUrl =
                    "https://23-239-17-63.abc.plex.direct:8443/library/parts/36576/file.mp3?X-Plex-Token=old",
            )
            assertEquals(
                "$live/library/parts/36576/file.mp3?X-Plex-Token=fresh",
                StreamingPlaybackPolicyHolder.resolvePlaybackUri(track),
            )
        } finally {
            ArtworkOriginHolder.clear()
            ArtworkAuthHolder.clear()
        }
    }

    @Test
    fun plexPlaybackUriFollowsTheOriginAcrossANetworkChange() {
        val wifi = "http://192.168.1.9:32400"
        val cellular = "https://45-79-210-225.abc.plex.direct:8443"
        ArtworkAuthHolder.update("fresh")
        try {
            val track = playbackTrack(
                streamUrl = "/library/parts/36576/file.mp3",
                downloadUrl = "/library/parts/36576/file.mp3",
            )
            ArtworkOriginHolder.update(wifi)
            assertEquals(
                "$wifi/library/parts/36576/file.mp3?X-Plex-Token=fresh",
                StreamingPlaybackPolicyHolder.resolvePlaybackUri(track),
            )
            // Same unmodified Track: the handoff changes the origin, not the queue.
            ArtworkOriginHolder.update(cellular)
            assertEquals(
                "$cellular/library/parts/36576/file.mp3?X-Plex-Token=fresh",
                StreamingPlaybackPolicyHolder.resolvePlaybackUri(track),
            )
        } finally {
            ArtworkOriginHolder.clear()
            ArtworkAuthHolder.clear()
        }
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

    /**
     * The queue overload resolves the origin ranking once and reuses it, instead of probing the
     * network per entry on the UI thread. Every entry must still come out exactly as the
     * single-track overload would produce it.
     */
    @Test
    fun queueRefreshMatchesPerTrackRefreshForEveryEntry() {
        val session = PlexSession(
            token = "fresh-password",
            userName = "fresh-user",
            providerType = MediaProviderType.Navidrome,
        )
        val tracks = listOf(
            playbackTrack(
                streamUrl = "https://navidrome.example/rest/stream.view?id=1&u=old&p=old",
                downloadUrl = "https://navidrome.example/rest/download.view?id=1",
            ),
            playbackTrack(
                streamUrl = "https://navidrome.example/rest/stream.view?id=2",
                downloadUrl = "https://navidrome.example/rest/download.view?id=2&u=old&p=old",
            ),
            playbackTrack(
                streamUrl = "file:///music/local.mp3",
                downloadUrl = "",
            ),
        )

        assertEquals(
            tracks.map { it.withFreshPlaybackUrls(session) },
            tracks.withFreshPlaybackUrls(session),
        )
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
