package com.phoebe.app.player

import com.phoebe.app.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlexTranscodeUrlsTest {
    @Test
    fun jellyfinFamilyMp3TranscodeUrlRewritesAudioStreamEndpoint() {
        val track = Track(
            id = "550e8400-e29b-41d4-a716-446655440000",
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000,
            streamUrl = "https://jellyfin.example/Audio/550e8400-e29b-41d4-a716-446655440000/stream?static=true&api_key=token",
            downloadUrl = "",
            audioCodec = "AAC",
        )
        assertEquals(
            "https://jellyfin.example/Audio/550e8400-e29b-41d4-a716-446655440000/stream.mp3?static=true&audioCodec=mp3&api_key=token",
            track.jellyfinFamilyMp3TranscodeUrl(),
        )
    }

    @Test
    fun jellyfinFamilyMp3TranscodeUrlRequiresApiKey() {
        val track = Track(
            id = "1",
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000,
            streamUrl = "https://jellyfin.example/Audio/1/stream?static=true",
            downloadUrl = "",
        )
        assertNull(track.jellyfinFamilyMp3TranscodeUrl())
    }

    @Test
    fun flatpakSandboxTranscodeUrlPrefersPlexBeforeJellyfin() {
        val plexTrack = Track(
            id = "plex:124",
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000,
            streamUrl = "https://plex.example:32400/library/parts/2.m4a?X-Plex-Token=token",
            downloadUrl = "",
            audioCodec = "aac",
        )
        assertEquals(
            "https://plex.example:32400/music/:/transcode/universal/start.mp3?path=%2Flibrary%2Fmetadata%2F124&mediaIndex=0&partIndex=0&protocol=https&format=mp3&audioCodec=mp3&directPlay=0&directStream=0&X-Plex-Token=token",
            plexTrack.flatpakSandboxTranscodeUrl(),
        )
    }

    @Test
    fun webPlaybackStreamUrlTranscodesLosslessPlexTracksForBrowser() {
        val plexFlacTrack = Track(
            id = "plex:456",
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 240_000,
            streamUrl = "https://plex.example:32400/library/parts/9.flac?X-Plex-Token=token",
            downloadUrl = "",
            audioCodec = "flac",
        )
        val playbackUrl = plexFlacTrack.webPlaybackStreamUrl()
        assertEquals(
            "https://plex.example:32400/music/:/transcode/universal/start.mp3",
            playbackUrl.substringBefore('?'),
        )
        assertTrue(
            playbackUrl.contains("path=https%3A%2F%2Fplex.example%3A32400%2Flibrary%2Fmetadata%2F456"),
        )
        assertTrue(playbackUrl.contains("protocol=http"))
        assertTrue(playbackUrl.contains("X-Plex-Client-Identifier=phoebe-compose-multiplatform"))
        assertTrue(playbackUrl.contains("session="))
    }

    @Test
    fun webPlaybackStreamUrlDirectPlaysPlexMp3() {
        val plexMp3Track = Track(
            id = "plex:124",
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000,
            streamUrl = "https://plex.example:32400/library/parts/2.mp3?X-Plex-Token=token",
            downloadUrl = "",
            audioCodec = "mp3",
        )
        assertEquals(plexMp3Track.streamUrl, plexMp3Track.webPlaybackStreamUrl())
    }

    @Test
    fun plexBitrateLimitedTranscodeUrlUsesRelativePathAndHttpProtocol() {
        val plexFlacTrack = Track(
            id = "plex:456",
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 240_000,
            streamUrl = "https://45-79-202-250.example.plex.direct:8443/library/parts/9.flac?X-Plex-Token=token",
            downloadUrl = "",
            audioCodec = "flac",
        )
        val transcodeUrl = plexFlacTrack.plexBitrateLimitedMp3TranscodeUrl(128).orEmpty()
        assertTrue(transcodeUrl.contains("/music/:/transcode/universal/start.mp3"))
        assertTrue(transcodeUrl.contains("path=%2Flibrary%2Fmetadata%2F456"))
        assertTrue(transcodeUrl.contains("protocol=http"))
        assertTrue(transcodeUrl.contains("maxAudioBitrate=128"))
        assertTrue(transcodeUrl.contains("musicBitrate=128"))
        assertTrue(transcodeUrl.contains("X-Plex-Platform=Chrome"))
        assertTrue(transcodeUrl.contains("hasMDE=1"))
        assertTrue(!transcodeUrl.contains("path=https%3A"))
        assertTrue(!transcodeUrl.contains("directPlay=0"))
        assertTrue(!transcodeUrl.contains("format=mp3"))
    }

    @Test
    fun plexWebUniversalMp3TranscodeUrlUsesAbsoluteMetadataPath() {
        val plexFlacTrack = Track(
            id = "plex:456",
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 240_000,
            streamUrl = "https://plex.example:32400/library/parts/9.flac?X-Plex-Token=token",
            downloadUrl = "",
            audioCodec = "flac",
        )
        val transcodeUrl = plexFlacTrack.plexWebUniversalMp3TranscodeUrl().orEmpty()
        assertTrue(
            transcodeUrl.contains(
                "path=https%3A%2F%2Fplex.example%3A32400%2Flibrary%2Fmetadata%2F456",
            ),
        )
        assertTrue(transcodeUrl.contains("session="))
        assertTrue(transcodeUrl.contains("X-Plex-Client-Identifier=phoebe-compose-multiplatform"))
        assertTrue(!transcodeUrl.contains("Client-Profile-Extra"))
        assertTrue(!transcodeUrl.contains("directPlay=0"))
    }

    @Test
    fun webPlaybackStreamUrlFallsBackToDirectStreamForUnsupportedSources() {
        val track = Track(
            id = "remote-1",
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000,
            streamUrl = "https://music.example.test/track.mp3",
            downloadUrl = "",
        )
        assertEquals(track.streamUrl, track.webPlaybackStreamUrl())
    }
}
