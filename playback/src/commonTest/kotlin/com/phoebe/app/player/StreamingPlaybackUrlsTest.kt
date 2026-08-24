package com.phoebe.app.player

import com.phoebe.app.domain.StreamingPolicySettings
import com.phoebe.app.domain.StreamingQuality
import com.phoebe.app.domain.Track
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingPlaybackUrlsTest {
    @AfterTest
    fun resetPolicyHolder() {
        StreamingPlaybackPolicyHolder.settings = StreamingPolicySettings()
        StreamingPlaybackPolicyHolder.networkIsConstrainedProvider = { false }
        StreamingPlaybackPolicyHolder.clearDirectStreamPreference()
    }

    @Test
    fun originalQualityKeepsDirectStreamUrl() {
        val track = plexFlacTrack()
        assertEquals(track.streamUrl, track.qualityAwareStreamUrl(StreamingQuality.Original))
    }

    @Test
    fun dataSaverTranscodesLosslessPlexTo128Kbps() {
        val url = plexFlacTrack().qualityAwareStreamUrl(StreamingQuality.DataSaver)
        assertTrue(url.contains("/music/:/transcode/universal/start.mp3"))
        assertTrue(url.contains("maxAudioBitrate=128"))
        assertTrue(url.contains("musicBitrate=128"))
        assertTrue(url.contains("path=%2Flibrary%2Fmetadata%2F456"))
        assertTrue(url.contains("protocol=http"))
        assertTrue(url.contains("X-Plex-Platform=Chrome"))
        assertFalse(url.contains("path=https%3A"))
        assertFalse(url.contains("directPlay=0"))
        assertFalse(url.contains("format=mp3"))
    }

    @Test
    fun highQualityTranscodesLosslessPlexTo320Kbps() {
        val url = plexFlacTrack().qualityAwareStreamUrl(StreamingQuality.High)
        assertTrue(url.contains("/music/:/transcode/universal/start.mp3"))
        assertTrue(url.contains("maxAudioBitrate=320"))
    }

    @Test
    fun alreadyCompressedWithinBudgetSkipsTranscode() {
        val track = plexFlacTrack().copy(
            streamUrl = "https://plex.example:32400/library/parts/2.mp3?X-Plex-Token=token",
            audioCodec = "mp3",
            bitrateKbps = 128,
            filepath = "song.mp3",
        )
        assertEquals(track.streamUrl, track.qualityAwareStreamUrl(StreamingQuality.DataSaver))
    }

    @Test
    fun highBitrateMp3IsTranscodedForDataSaver() {
        val track = plexFlacTrack().copy(
            streamUrl = "https://plex.example:32400/library/parts/2.mp3?X-Plex-Token=token",
            audioCodec = "mp3",
            bitrateKbps = 320,
            filepath = "song.mp3",
        )
        val url = track.qualityAwareStreamUrl(StreamingQuality.DataSaver)
        assertTrue(url.contains("maxAudioBitrate=128"))
    }

    @Test
    fun localUriAlwaysWinsOverRemoteQualityPolicy() {
        val track = plexFlacTrack().copy(localUri = "file:///music/song.flac")
        assertEquals(
            "file:///music/song.flac",
            track.resolvedPlaybackUri(StreamingQuality.DataSaver),
        )
    }

    @Test
    fun cellularToggleForcesDataSaverEvenWhenWifiQualityIsOriginal() {
        val policy = StreamingPolicySettings(
            quality = StreamingQuality.Original,
            useDataSaverOnCellular = true,
        )
        assertEquals(StreamingQuality.DataSaver, policy.effectiveQuality(networkConstrained = true))
        assertEquals(StreamingQuality.Original, policy.effectiveQuality(networkConstrained = false))
    }

    @Test
    fun jellyfinDataSaverAddsAudioBitRate() {
        val track = Track(
            id = "550e8400-e29b-41d4-a716-446655440000",
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000,
            streamUrl = "https://jellyfin.example/Audio/550e8400-e29b-41d4-a716-446655440000/stream?static=true&api_key=token",
            downloadUrl = "",
            audioCodec = "flac",
        )
        val url = track.qualityAwareStreamUrl(StreamingQuality.DataSaver)
        assertTrue(url.contains("/stream.mp3"))
        assertTrue(url.contains("audioBitRate=128000"))
        assertTrue(url.contains("static=false"))
    }

    @Test
    fun subsonicDataSaverAddsMaxBitRate() {
        val track = Track(
            id = "navidrome:1",
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000,
            streamUrl = "https://music.example/rest/stream.view?u=user&t=token&s=salt&v=1.16.1&c=phoebe&id=1",
            downloadUrl = "",
            audioCodec = "flac",
        )
        val url = track.qualityAwareStreamUrl(StreamingQuality.High)
        assertTrue(url.contains("maxBitRate=320"))
        assertTrue(url.contains("format=mp3"))
    }

    @Test
    fun policyHolderUsesConstrainedNetworkProvider() {
        StreamingPlaybackPolicyHolder.settings = StreamingPolicySettings(
            quality = StreamingQuality.High,
            useDataSaverOnCellular = true,
        )
        StreamingPlaybackPolicyHolder.networkIsConstrainedProvider = { true }
        assertEquals(StreamingQuality.DataSaver, StreamingPlaybackPolicyHolder.effectiveQuality())
        val url = StreamingPlaybackPolicyHolder.resolvePlaybackUri(plexFlacTrack())
        assertTrue(url.contains("maxAudioBitrate=128"))
    }

    @Test
    fun preferDirectStreamSkipsQualityTranscodeAfterCellularFailure() {
        StreamingPlaybackPolicyHolder.settings = StreamingPolicySettings(
            quality = StreamingQuality.Original,
            useDataSaverOnCellular = true,
        )
        StreamingPlaybackPolicyHolder.networkIsConstrainedProvider = { true }
        val track = plexFlacTrack()
        StreamingPlaybackPolicyHolder.preferDirectStreamFor(track.id)
        assertEquals(track.streamUrl, StreamingPlaybackPolicyHolder.resolvePlaybackUri(track))
        assertEquals(
            StreamingQuality.Original,
            StreamingPlaybackPolicyHolder.artworkQuality(track.id, StreamingQuality.DataSaver),
        )
    }

    @Test
    fun unknownExternalStreamFallsBackToOriginal() {
        val track = Track(
            id = "radio-1",
            title = "Radio",
            artist = "Live",
            album = "Radio",
            durationMs = 0,
            streamUrl = "https://kexp.streamguys1.com/kexp128.mp3",
            downloadUrl = "",
        )
        assertEquals(track.streamUrl, track.qualityAwareStreamUrl(StreamingQuality.DataSaver))
        assertFalse(track.isLosslessAudioCodec())
    }

    private fun plexFlacTrack(): Track =
        Track(
            id = "plex:456",
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 240_000,
            streamUrl = "https://plex.example:32400/library/parts/9.flac?X-Plex-Token=token",
            downloadUrl = "",
            audioCodec = "flac",
            filepath = "song.flac",
        )
}
