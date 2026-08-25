package com.phoebe.app.player

import com.phoebe.app.domain.Track
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlaybackStartupPolicyTest {
    @Test
    fun javaFxMediaWarmupAssetIsTinyPcmWav() {
        val bytes = javaFxMediaWarmupWavBytes()

        assertEquals("RIFF", bytes.ascii(0, 4))
        assertEquals("WAVE", bytes.ascii(8, 4))
        assertEquals("fmt ", bytes.ascii(12, 4))
        assertEquals("data", bytes.ascii(36, 4))
        assertEquals(1, bytes.shortLe(20))
        assertEquals(1, bytes.shortLe(22))
        assertEquals(8_000, bytes.intLe(24))
        assertEquals(16, bytes.shortLe(34))
        assertEquals(bytes.size - 44, bytes.intLe(40))
        assertTrue(bytes.size < 4_096)
    }

    @Test
    fun remoteJavaFxHttpFormatsStreamDirectly() {
        assertFalse(
            DesktopPlaybackStartupPolicy.shouldEagerlyBufferRemotePlayback(
                uri = "https://music.example.test/library/track.m4a",
                preferredSampledExtension = null,
            ),
        )
    }

    @Test
    fun sampledOnlyRemoteStreamsStillBufferBeforePlayback() {
        assertTrue(
            DesktopPlaybackStartupPolicy.shouldEagerlyBufferRemotePlayback(
                uri = "https://music.example.test/library/track.flac?token=abc",
                preferredSampledExtension = null,
            ),
        )
        assertTrue(
            DesktopPlaybackStartupPolicy.shouldEagerlyBufferRemotePlayback(
                uri = "https://music.example.test/stream",
                preferredSampledExtension = "ogg",
            ),
        )
    }

    @Test
    fun remoteMp3UsesJavaFxInsteadOfSampledPlayback() {
        assertFalse(
            DesktopPlaybackStartupPolicy.shouldEagerlyBufferRemotePlayback(
                uri = "https://music.example.test/library/track.mp3?token=abc",
                preferredSampledExtension = null,
            ),
        )
        assertEquals(null, DesktopPlaybackStartupPolicy.sampledPlaybackExtensionFromSuffix("mp3"))
        assertEquals(
            null,
            DesktopPlaybackStartupPolicy.streamingSampledExtensionFromUri(
                "https://music.example.test/library/track.mp3?token=abc",
            ),
        )
        assertFalse(
            DesktopPlaybackStartupPolicy.shouldUsePcmStreamBeforeJavaFx(
                uri = "https://music.example.test/library/track.mp3?token=abc",
                isKnownLiveStream = false,
                preferredJavaFxExtension = "mp3",
            ),
        )
    }

    @Test
    fun extensionlessRemoteMp3UsesJavaFxWhenCodecIsKnown() {
        assertEquals(
            "mp3",
            DesktopPlaybackStartupPolicy.javaFxPlaybackExtensionFromMetadata(
                audioCodec = "MP3",
                filepath = "/music/Artist/Album/Track.mp3",
                uri = "https://music.example.test/rest/stream.view?id=abc",
            ),
        )
        assertFalse(
            DesktopPlaybackStartupPolicy.shouldUsePcmStreamBeforeJavaFx(
                uri = "https://music.example.test/rest/stream.view?id=abc",
                isKnownLiveStream = false,
                preferredJavaFxExtension = "mp3",
            ),
        )
    }

    @Test
    fun knownLiveStreamsUsePcmBeforeJavaFxEvenWhenUrlHasJavaFxSuffix() {
        assertTrue(
            DesktopPlaybackStartupPolicy.shouldUsePcmStreamBeforeJavaFx(
                uri = "https://radio.example/live.mp3",
                isKnownLiveStream = true,
                preferredJavaFxExtension = "mp3",
            ),
        )
    }

    @Test
    fun javaFxReadyTimeoutStaysShortOnLanAndLocalFiles() {
        assertEquals(
            DesktopPlaybackStartupPolicy.JavaFxFailureFallbackDelayMs,
            DesktopPlaybackStartupPolicy.javaFxMediaReadyTimeoutMs(
                "https://172-16-1-2.abc.plex.direct:32400/library/parts/1/file.mp3",
            ),
        )
        assertEquals(
            DesktopPlaybackStartupPolicy.JavaFxFailureFallbackDelayMs,
            DesktopPlaybackStartupPolicy.javaFxMediaReadyTimeoutMs("file:///music/song.mp3"),
        )
        assertEquals(
            DesktopPlaybackStartupPolicy.JavaFxRemoteReadyTimeoutMs,
            DesktopPlaybackStartupPolicy.javaFxMediaReadyTimeoutMs(
                "https://173-230-133-75.abc.plex.direct:8443/library/parts/1/file.mp3",
            ),
        )
    }

    @Test
    fun knownRemoteFormatsHaveDeterministicInstantStartupPlans() {
        val cases = listOf(
            RemoteStartupCase(
                label = "remote mp3",
                uri = "https://music.example.test/library/track.mp3?token=abc",
                audioCodec = "mp3",
                expectedPath = DesktopPlaybackStartupPath.JavaFxMediaPlayer,
            ),
            RemoteStartupCase(
                label = "extensionless remote mp3",
                uri = "https://music.example.test/rest/stream.view?id=abc",
                audioCodec = "mp3",
                filepath = "/music/Artist/Album/Track.mp3",
                expectedPath = DesktopPlaybackStartupPath.JavaFxMediaPlayer,
            ),
            RemoteStartupCase(
                label = "remote aac",
                uri = "https://music.example.test/library/track.m4a?token=abc",
                audioCodec = "aac",
                expectedPath = DesktopPlaybackStartupPath.JavaFxMediaPlayer,
            ),
            RemoteStartupCase(
                label = "extensionless remote aac",
                uri = "https://music.example.test/Audio/1/stream?static=true",
                audioCodec = "aac",
                filepath = "/music/Artist/Album/Track.m4a",
                expectedPath = DesktopPlaybackStartupPath.JavaFxMediaPlayer,
            ),
            RemoteStartupCase(
                label = "remote wav",
                uri = "https://music.example.test/library/track.wav?token=abc",
                audioCodec = "wav",
                expectedPath = DesktopPlaybackStartupPath.SampledStream,
            ),
            RemoteStartupCase(
                label = "extensionless remote wav",
                uri = "https://music.example.test/stream",
                audioCodec = "wav",
                filepath = "/music/Artist/Album/Track.wav",
                expectedPath = DesktopPlaybackStartupPath.SampledStream,
            ),
            RemoteStartupCase(
                label = "remote flac",
                uri = "https://music.example.test/library/track.flac?token=abc",
                audioCodec = "flac",
                expectedPath = DesktopPlaybackStartupPath.SampledStream,
            ),
            RemoteStartupCase(
                label = "extensionless remote flac",
                uri = "https://music.example.test/stream",
                audioCodec = "flac",
                filepath = "/music/Artist/Album/Track.flac",
                expectedPath = DesktopPlaybackStartupPath.SampledStream,
            ),
            RemoteStartupCase(
                label = "mp3 radio",
                uri = "https://radio.example/live.mp3",
                audioCodec = "mp3",
                isKnownLiveStream = true,
                durationMs = 0L,
                expectedPath = DesktopPlaybackStartupPath.FfmpegPcmStream,
            ),
            RemoteStartupCase(
                label = "extensionless radio",
                uri = "https://radio.example/live",
                isKnownLiveStream = true,
                durationMs = 0L,
                expectedPath = DesktopPlaybackStartupPath.FfmpegPcmStream,
            ),
        )

        cases.forEach { case ->
            val plan = startupPlan(case)
            assertEquals(case.expectedPath, plan.path, case.label)
            assertEquals(0L, plan.deterministicDelayBeforeFirstEngineMs, case.label)
            assertFalse(plan.waitsForJavaFxFailureBeforeFallback, case.label)
        }
    }

    @Test
    fun unknownExtensionlessRemoteStreamKeepsJavaFxFallbackDelayVisible() {
        val plan = startupPlan(
            RemoteStartupCase(
                label = "unknown extensionless stream",
                uri = "https://music.example.test/stream",
                durationMs = 0L,
                expectedPath = DesktopPlaybackStartupPath.JavaFxThenFallback,
            ),
        )

        assertEquals(DesktopPlaybackStartupPath.JavaFxThenFallback, plan.path)
        assertEquals(
            DesktopPlaybackStartupPolicy.JavaFxFailureFallbackDelayMs,
            plan.deterministicDelayBeforeFirstEngineMs,
        )
        assertTrue(plan.waitsForJavaFxFailureBeforeFallback)
    }

    @Test
    fun desktopPlaylistDetectionSkipsHlsPlaylists() {
        assertTrue(isLikelyDesktopPlaylistUri("https://radio.example/station.pls"))
        assertTrue(isLikelyDesktopPlaylistUri("https://radio.example/station.m3u"))
        assertFalse(isLikelyDesktopPlaylistUri("https://radio.example/live.m3u8"))
    }

    @Test
    fun desktopPlaylistParsingFindsFirstStreamUrl() {
        assertEquals(
            "https://stream.example/live.mp3",
            parseDesktopPlaylistStreamUri(
                """
                [playlist]
                NumberOfEntries=1
                File1=https://stream.example/live.mp3
                Title1=Example
                Length1=-1
                Version=2
                """.trimIndent(),
                "https://radio.example/listen.pls",
            ),
        )
        assertEquals(
            "https://radio.example/listen/live.mp3",
            parseDesktopPlaylistStreamUri(
                """
                #EXTM3U
                #EXTINF:-1,Example
                live.mp3
                """.trimIndent(),
                "https://radio.example/listen/station.m3u",
            ),
        )
    }

    @Test
    fun flatpakSandboxUsesPlexMp3TranscodeForAacStreams() {
        DesktopSandboxPlayback.flatpakSandboxOverride = { true }
        try {
            val track = playbackTrack(
                streamUrl = "https://plex.example:32400/library/parts/2.m4a?X-Plex-Token=token",
                localUri = null,
            ).copy(
                id = "plex:124",
                audioCodec = "aac",
                filepath = "/music/Artist/Album/02 Track.m4a",
            )
            assertEquals(
                "https://plex.example:32400/music/:/transcode/universal/start.mp3?path=%2Flibrary%2Fmetadata%2F124&mediaIndex=0&partIndex=0&protocol=https&format=mp3&audioCodec=mp3&directPlay=0&directStream=0&X-Plex-Token=token",
                DesktopSandboxPlayback.playbackStreamUrlForTrack(track),
            )
        } finally {
            DesktopSandboxPlayback.flatpakSandboxOverride = null
        }
    }

    @Test
    fun flatpakSandboxUsesJellyfinMp3TranscodeForAacStreams() {
        DesktopSandboxPlayback.flatpakSandboxOverride = { true }
        try {
            val track = playbackTrack(
                streamUrl = "https://jellyfin.example/Audio/550e8400-e29b-41d4-a716-446655440000/stream?static=true&api_key=token",
                localUri = null,
            ).copy(
                audioCodec = "M4A",
                filepath = "/music/Artist/Album/02 Track.m4a",
            )
            assertEquals(
                "https://jellyfin.example/Audio/550e8400-e29b-41d4-a716-446655440000/stream.mp3?static=true&audioCodec=mp3&api_key=token",
                DesktopSandboxPlayback.playbackStreamUrlForTrack(track),
            )
        } finally {
            DesktopSandboxPlayback.flatpakSandboxOverride = null
        }
    }

    @Test
    fun desktopUsesJellyfinMp3StreamSuffixForExtensionlessMp3Streams() {
        DesktopSandboxPlayback.flatpakSandboxOverride = { false }
        try {
            val track = playbackTrack(
                streamUrl = "https://jellyfin.example/Audio/550e8400-e29b-41d4-a716-446655440000/stream?static=true&api_key=token",
                localUri = null,
            ).copy(
                audioCodec = "MP3",
                filepath = "/music/Artist/Album/02 Track.mp3",
            )
            assertEquals(
                "https://jellyfin.example/Audio/550e8400-e29b-41d4-a716-446655440000/stream.mp3?static=true&audioCodec=mp3&api_key=token",
                DesktopSandboxPlayback.playbackStreamUrlForTrack(track),
            )
        } finally {
            DesktopSandboxPlayback.flatpakSandboxOverride = null
        }
    }

    @Test
    fun flatpakSandboxStreamsRemoteHttpBeforeBufferedFallback() {
        DesktopSandboxPlayback.flatpakSandboxOverride = { true }
        try {
            assertTrue(
                DesktopSandboxPlayback.shouldStreamRemoteSampledPlayback(
                    "https://plex.example:32400/library/parts/2.mp3?X-Plex-Token=token",
                ),
            )
            assertTrue(
                DesktopSandboxPlayback.shouldEagerlyBufferRemotePlayback(
                    "https://plex.example:32400/library/parts/2.mp3?X-Plex-Token=token",
                    preferredSampledExtension = null,
                ),
            )
        } finally {
            DesktopSandboxPlayback.flatpakSandboxOverride = null
        }
    }

    @Test
    fun flatpakSandboxBuffersTranscodeUrlInsteadOfDirectDownload() {
        DesktopSandboxPlayback.flatpakSandboxOverride = { true }
        try {
            val transcodeUrl =
                "https://plex.example:32400/music/:/transcode/universal/start.mp3?path=%2Flibrary%2Fmetadata%2F124&X-Plex-Token=token"
            val directDownload =
                "https://plex.example:32400/library/parts/2.m4a?X-Plex-Token=token&download=1"
            assertEquals(
                transcodeUrl,
                DesktopSandboxPlayback.bufferedRemotePlaybackUri(transcodeUrl, directDownload),
            )
        } finally {
            DesktopSandboxPlayback.flatpakSandboxOverride = null
        }
    }

    @Test
    fun flatpakSandboxBuffersRemoteMp3WithSampledPlayback() {
        DesktopSandboxPlayback.flatpakSandboxOverride = { true }
        try {
            assertTrue(
                DesktopSandboxPlayback.shouldEagerlyBufferRemotePlayback(
                    uri = "https://music.example.test/library/track.mp3?token=abc",
                    preferredSampledExtension = null,
                ),
            )
            assertEquals("mp3", DesktopSandboxPlayback.sampledPlaybackExtensionFromSuffix("mp3"))
            assertEquals("mp3", DesktopSandboxPlayback.streamingSampledExtensionFromSuffix("mp3"))
        } finally {
            DesktopSandboxPlayback.flatpakSandboxOverride = null
        }
    }

    @Test
    fun desktopMp3UsesJavaFxInsteadOfJavaSound() {
        DesktopSandboxPlayback.flatpakSandboxOverride = { false }
        try {
            assertEquals(null, DesktopSandboxPlayback.sampledPlaybackExtensionFromSuffix("mp3"))
            assertEquals(null, DesktopSandboxPlayback.streamingSampledExtensionFromSuffix("mp3"))
            assertFalse(
                DesktopSandboxPlayback.shouldStreamRemoteSampledPlayback(
                    "https://music.example.test/library/track.mp3?token=abc",
                ),
            )
        } finally {
            DesktopSandboxPlayback.flatpakSandboxOverride = null
        }
    }

    @Test
    fun remoteNonJavaFxFormatsStreamBeforeBufferedFallback() {
        assertEquals("flac", DesktopPlaybackStartupPolicy.streamingSampledExtensionFromSuffix("flac"))
        assertTrue(
            DesktopSandboxPlayback.shouldStreamRemoteSampledPlayback(
                "https://music.example.test/library/track.flac?token=abc",
            ),
        )
        assertTrue(
            DesktopSandboxPlayback.shouldEagerlyBufferRemotePlayback(
                uri = "https://music.example.test/library/track.flac?token=abc",
                preferredSampledExtension = null,
            ),
        )
    }

    @Test
    fun remoteJavaFxFormatsCanStillPrefetchForCrossfade() {
        assertTrue(
            DesktopPlaybackStartupPolicy.shouldPrefetchRemoteForCrossfade(
                "https://music.example.test/library/next-track.mp3",
            ),
        )
    }

    @Test
    fun desktopPlaybackFallsBackToStreamWhenOfflineFileIsMissing() {
        val streamUrl = "https://music.example.test/library/track.mp3?token=abc"
        val missingOfflineUri = File("build/missing-offline-track.mp3").absoluteFile.toURI().toString()

        assertEquals(
            streamUrl,
            desktopPlaybackUriForTrack(
                playbackTrack(
                    streamUrl = streamUrl,
                    localUri = missingOfflineUri,
                ),
            ),
        )
    }

    @Test
    fun desktopPlaybackStillPrefersExistingOfflineFile() {
        val offline = File.createTempFile("phoebe-offline-playback", ".mp3")
        try {
            assertEquals(
                offline.toURI().toString(),
                desktopPlaybackUriForTrack(
                    playbackTrack(
                        streamUrl = "https://music.example.test/library/track.mp3?token=abc",
                        localUri = offline.toURI().toString(),
                    ),
                ),
            )
        } finally {
            offline.delete()
        }
    }

    private fun playbackTrack(
        streamUrl: String,
        localUri: String?,
    ): Track =
        Track(
            id = "track-1",
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000,
            streamUrl = streamUrl,
            downloadUrl = "",
            localUri = localUri,
        )

    private data class RemoteStartupCase(
        val label: String,
        val uri: String,
        val audioCodec: String? = null,
        val filepath: String? = null,
        val isKnownLiveStream: Boolean = false,
        val durationMs: Long = 180_000L,
        val expectedPath: DesktopPlaybackStartupPath,
    )

    private fun startupPlan(case: RemoteStartupCase): DesktopPlaybackStartupPlan =
        DesktopPlaybackStartupPolicy.startupPlanForRemotePlayback(
            uri = case.uri,
            isKnownLiveStream = case.isKnownLiveStream,
            preferredJavaFxExtension = DesktopPlaybackStartupPolicy.javaFxPlaybackExtensionFromMetadata(
                audioCodec = case.audioCodec,
                filepath = case.filepath,
                uri = case.uri,
            ),
            preferredSampledExtension = sampledExtension(case),
            preferredStreamingExtension = streamingExtension(case),
            durationMs = case.durationMs,
            isFlatpakSandbox = false,
        )

    private fun sampledExtension(case: RemoteStartupCase): String? =
        DesktopPlaybackStartupPolicy.sampledPlaybackExtensionFromSuffix(case.audioCodec.orEmpty())
            ?: DesktopPlaybackStartupPolicy.sampledPlaybackExtensionFromUri(case.uri)
            ?: DesktopPlaybackStartupPolicy.sampledPlaybackExtensionFromSuffix(
                case.filepath.orEmpty().substringAfterLast('.', missingDelimiterValue = ""),
            )

    private fun streamingExtension(case: RemoteStartupCase): String? =
        DesktopPlaybackStartupPolicy.streamingSampledExtensionFromSuffix(case.audioCodec.orEmpty())
            ?: DesktopPlaybackStartupPolicy.streamingSampledExtensionFromUri(case.uri)
            ?: DesktopPlaybackStartupPolicy.streamingSampledExtensionFromSuffix(
                case.filepath.orEmpty().substringAfterLast('.', missingDelimiterValue = ""),
            )
}

private fun ByteArray.ascii(offset: Int, length: Int): String =
    copyOfRange(offset, offset + length).decodeToString()

private fun ByteArray.shortLe(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.intLe(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
