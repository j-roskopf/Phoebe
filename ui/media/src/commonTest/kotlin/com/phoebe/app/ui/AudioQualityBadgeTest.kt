package com.phoebe.app.ui

import com.phoebe.app.domain.StreamingQuality
import com.phoebe.app.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioQualityBadgeTest {
    @Test
    fun originalQualityUsesSourceFile() {
        assertEquals("CD QUALITY", audioQualityBadgeLabel(flacTrack(), StreamingQuality.Original))
    }

    @Test
    fun dataSaverShowsTranscodedMp3Quality() {
        assertEquals("BASIC QUALITY", audioQualityBadgeLabel(flacTrack(), StreamingQuality.DataSaver))
    }

    @Test
    fun highQualityShowsTranscodedMp3Cap() {
        assertEquals("EXCELLENT QUALITY", audioQualityBadgeLabel(flacTrack(), StreamingQuality.High))
    }

    @Test
    fun alreadyCompressedMp3KeepsSourceBadge() {
        val mp3 = flacTrack().copy(
            audioCodec = "mp3",
            bitrateKbps = 128,
            filepath = "song.mp3",
        )
        assertEquals("BASIC QUALITY", audioQualityBadgeLabel(mp3, StreamingQuality.DataSaver))
        assertEquals("BASIC QUALITY", audioQualityBadgeLabel(mp3, StreamingQuality.Original))
    }

    @Test
    fun localFileIgnoresStreamingCap() {
        val local = flacTrack().copy(localUri = "file:///music/song.flac")
        assertEquals("CD QUALITY", audioQualityBadgeLabel(local, StreamingQuality.DataSaver))
    }

    private fun flacTrack(): Track = Track(
        id = "plex:456",
        title = "Track",
        artist = "Artist",
        album = "Album",
        durationMs = 240_000,
        streamUrl = "https://plex.example:32400/library/parts/9.flac?X-Plex-Token=token",
        downloadUrl = "",
        audioCodec = "flac",
        bitrateKbps = 921,
        filepath = "song.flac",
    )
}
