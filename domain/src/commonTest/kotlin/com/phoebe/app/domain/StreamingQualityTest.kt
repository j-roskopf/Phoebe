package com.phoebe.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingQualityTest {
    @Test
    fun cellularToggleForcesDataSaver() {
        val policy = StreamingPolicySettings(
            quality = StreamingQuality.Original,
            useDataSaverOnCellular = true,
        )
        assertEquals(StreamingQuality.DataSaver, policy.effectiveQuality(networkConstrained = true))
        assertEquals(StreamingQuality.Original, policy.effectiveQuality(networkConstrained = false))
    }

    @Test
    fun preferLocalNetworkOffAlwaysDemotesLan() {
        val policy = StreamingPolicySettings(preferLocalNetwork = false)
        assertTrue(policy.shouldDemoteLocalOrigins(networkDemotesLocalOrigins = false))
        assertTrue(policy.shouldDemoteLocalOrigins(networkDemotesLocalOrigins = true))
    }

    @Test
    fun preferLocalNetworkOnOnlyDemotesWhenNetworkRequires() {
        val policy = StreamingPolicySettings(preferLocalNetwork = true)
        assertFalse(policy.shouldDemoteLocalOrigins(networkDemotesLocalOrigins = false))
        assertTrue(policy.shouldDemoteLocalOrigins(networkDemotesLocalOrigins = true))
    }

    @Test
    fun losslessTracksTranscodeWhenQualityIsCapped() {
        val flac = track(audioCodec = "flac", bitrateKbps = 921, filepath = "song.flac")
        assertTrue(flac.keepsOriginalStreamFor(StreamingQuality.Original))
        assertFalse(flac.keepsOriginalStreamFor(StreamingQuality.High))
        assertFalse(flac.keepsOriginalStreamFor(StreamingQuality.DataSaver))
    }

    @Test
    fun mp3WithinBudgetKeepsOriginal() {
        val mp3 = track(audioCodec = "mp3", bitrateKbps = 128, filepath = "song.mp3")
        assertTrue(mp3.keepsOriginalStreamFor(StreamingQuality.DataSaver))
        assertTrue(mp3.keepsOriginalStreamFor(StreamingQuality.High))
    }

    @Test
    fun localAndRadioTracksAlwaysKeepOriginal() {
        val local = track(audioCodec = "flac", bitrateKbps = 921, localUri = "file:///music/song.flac")
        val radio = track(id = "radio:kexp", audioCodec = "mp3", bitrateKbps = 128)
        assertTrue(local.keepsOriginalStreamFor(StreamingQuality.DataSaver))
        assertTrue(radio.keepsOriginalStreamFor(StreamingQuality.DataSaver))
    }

    private fun track(
        id: String = "plex:1",
        audioCodec: String? = null,
        bitrateKbps: Int? = null,
        filepath: String? = null,
        localUri: String? = null,
    ): Track = Track(
        id = id,
        title = "Track",
        artist = "Artist",
        album = "Album",
        durationMs = 180_000,
        streamUrl = "https://plex.example/library/parts/1",
        downloadUrl = "",
        audioCodec = audioCodec,
        bitrateKbps = bitrateKbps,
        filepath = filepath,
        localUri = localUri,
    )
}
