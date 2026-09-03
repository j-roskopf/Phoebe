package com.phoebe.app

import com.phoebe.app.data.ListenBrainzClient
import com.phoebe.app.data.ListenBrainzPlaybackReporter
import com.phoebe.app.domain.AppSettings
import com.phoebe.app.domain.ListenBrainzSettings
import com.phoebe.app.domain.Track
import com.phoebe.app.player.DesktopAudioPlayer
import com.phoebe.app.player.DesktopSandboxPlayback
import com.phoebe.app.player.PlaybackDiagnostics
import com.phoebe.app.player.PlaybackEnginePath
import com.phoebe.app.platform.SecureCredentialKey
import com.phoebe.app.testing.FakeListenBrainzAccountActions
import com.phoebe.app.testing.FakeSecureCredentialStore
import com.phoebe.app.testing.assumeLinux
import com.phoebe.app.testing.testHttpClient
import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

class RealAudioPlaybackDesktopTest {
    @org.junit.Before
    fun skipFlakyMacOsCiAudioEnvironment() {
        // Enabling -Pphoebe.realAudioTests for :playback surfaces this suite on CI. Linux
        // runners with a Pulse null sink are reliable; GitHub macOS runners frequently select
        // no engine at all (engines=[]), which is an environment limitation rather than a
        // regression in routing policy. Local macOS + Android device verification covers that.
        val onGitHubMac =
            System.getenv("GITHUB_ACTIONS") == "true" &&
                System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        assumeTrue("Skip playback RealAudio suite on GitHub macOS runners", !onGitHubMac)
    }

    @Test
    fun m4aStartsThroughJavaFxAndAdvancePlaybackState() {
        assumeRealAudioTestsEnabled()

        listOf("wikimedia-example.m4a").forEach { fixture ->
            val diagnostics = RecordingPlaybackDiagnostics()
            val player = DesktopAudioPlayer(diagnostics)
            try {
                val track = fixtureTrack(fixture, durationMs = 10_000)
                val expectedEngine = expectedLocalJavaFxFriendlyEngine()

                player.play(listOf(track), 0)

                assertTrue(
                    waitUntil {
                        diagnostics.hasEngine(expectedEngine) &&
                            player.state.value.isPlaying
                    },
                    "Local M4A should start via $expectedEngine for $fixture; engines=${diagnostics.engineEvents()} errors=${diagnostics.errorEvents()}",
                )
                assertTrue(
                    waitUntil { player.state.value.positionMs > 0L },
                    "Local M4A playback did not advance for $fixture; state=${player.state.value} " +
                        "progress=${diagnostics.progressEvents(expectedEngine)} " +
                        "errors=${diagnostics.errorEvents()}",
                )
                assertTrue(diagnostics.hasPlayingEvent(expectedEngine))
            } finally {
                player.releaseForTests()
            }
        }
    }

    @Test
    fun localMp3UsesJavaFxInsteadOfSampledClipPlayback() {
        assumeRealAudioTestsEnabled()

        val diagnostics = RecordingPlaybackDiagnostics()
        val player = DesktopAudioPlayer(diagnostics)
        try {
            val track = fixtureTrack("wikimedia-example.mp3", durationMs = 10_000)

            player.play(listOf(track), 0)

            val expectedEngine = expectedLocalJavaFxFriendlyEngine()
            assertTrue(
                waitUntil { diagnostics.hasEngine(expectedEngine) },
                "Local MP3 should route to $expectedEngine, not Java Sound; engines=${diagnostics.engineEvents()} " +
                    "errors=${diagnostics.errorEvents()}",
            )
            assertFalse(
                diagnostics.hasEngine(PlaybackEnginePath.SampledClip),
                "Local MP3 must not use SampledClip because Java Sound MP3 decoding can produce static",
            )
        } finally {
            player.releaseForTests()
        }
    }

    @Test
    fun localShortMp3UsesJavaFxInsteadOfSampledClipPlayback() {
        assumeRealAudioTestsEnabled()

        val diagnostics = RecordingPlaybackDiagnostics()
        val player = DesktopAudioPlayer(diagnostics)
        try {
            val track = fixtureTrack("mdn-t-rex-roar-cc0.mp3", durationMs = 2_500, id = "complete-local-mp3")

            player.play(listOf(track), 0)

            val expectedEngine = expectedLocalJavaFxFriendlyEngine()
            assertTrue(
                waitUntil { diagnostics.hasEngine(expectedEngine) },
                "Local short MP3 should route to $expectedEngine, not Java Sound; engines=${diagnostics.engineEvents()} " +
                    "errors=${diagnostics.errorEvents()}",
            )
            assertFalse(
                diagnostics.hasEngine(PlaybackEnginePath.SampledClip),
                "Local short MP3 must not use SampledClip because Java Sound MP3 decoding can produce static",
            )
        } finally {
            player.releaseForTests()
        }
    }

    @Test
    fun flatpakLocalMp3UsesSampledStreamInsteadOfJavaFx() {
        assumeFlatpakRealAudioTestsEnabled()
        withFlatpakSandbox {
            val diagnostics = RecordingPlaybackDiagnostics()
            val player = DesktopAudioPlayer(diagnostics)
            try {
                val track = fixtureTrack("wikimedia-example.mp3", durationMs = 10_000)

                player.play(listOf(track), 0)

                assertFlatpakSampledStreamPlaybackStarted(
                    diagnostics = diagnostics,
                    player = player,
                    label = "Flatpak local MP3",
                )
                assertFalse(
                    diagnostics.hasEngine(PlaybackEnginePath.JavaFxMediaPlayer),
                    "Flatpak local MP3 must not fall through to JavaFX media; engines=${diagnostics.engineEvents()}",
                )
            } finally {
                player.releaseForTests()
            }
        }
    }

    @Test
    fun flatpakLocalWavUsesSampledClipInsteadOfJavaFx() {
        assumeFlatpakRealAudioTestsEnabled()
        withFlatpakSandbox {
            val diagnostics = RecordingPlaybackDiagnostics()
            val player = DesktopAudioPlayer(diagnostics)
            try {
                val track = fixtureTrack("wikimedia-example.wav", durationMs = 10_000)

                player.play(listOf(track), 0)

                assertFlatpakSampledClipPlaybackStarted(
                    diagnostics = diagnostics,
                    player = player,
                    label = "Flatpak local WAV",
                )
                assertFalse(
                    diagnostics.hasEngine(PlaybackEnginePath.JavaFxMediaPlayer),
                    "Flatpak local WAV must not fall through to JavaFX media; engines=${diagnostics.engineEvents()}",
                )
            } finally {
                player.releaseForTests()
            }
        }
    }

    @Test
    fun flatpakRemoteMp3UsesSampledPlaybackInsteadOfJavaFx() {
        assumeFlatpakRealAudioTestsEnabled()
        val fixture = fixtureBytes("wikimedia-example.mp3")
        val requestEvents = Collections.synchronizedList(mutableListOf<String>())
        val server = createRemoteMp3HttpServer(fixture, requestEvents)
        server.start()
        withFlatpakSandbox {
            val diagnostics = RecordingPlaybackDiagnostics()
            val player = DesktopAudioPlayer(diagnostics)
            try {
                val track = remoteTrack(
                    id = "flatpak-remote-mp3",
                    uri = "http://127.0.0.1:${server.address.port}/library/track.mp3?X-Plex-Token=test",
                    durationMs = 10_000,
                    audioCodec = "mp3",
                )

                player.play(listOf(track), 0)

                assertFlatpakSampledPlaybackStarted(
                    diagnostics = diagnostics,
                    player = player,
                    label = "Flatpak remote MP3",
                    requestEvents = requestEvents,
                )
                assertFalse(
                    diagnostics.hasEngine(PlaybackEnginePath.JavaFxMediaPlayer),
                    "Flatpak remote MP3 must not fall through to JavaFX media; engines=${diagnostics.engineEvents()} " +
                        "requests=${requestEvents.toList()} errors=${diagnostics.errorEvents()}",
                )
            } finally {
                player.releaseForTests()
            }
        }
        server.stop(0)
    }

    @Test
    fun flatpakRemoteAacUsesPlexTranscodeAndSampledPlayback() {
        assumeFlatpakRealAudioTestsEnabled()
        val fixture = fixtureBytes("wikimedia-example.mp3")
        val requestEvents = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requestEvents += "${exchange.requestMethod}:${exchange.requestURI.path}"
            if (exchange.requestURI.path.endsWith(".m4a")) {
                val body = "direct AAC stream should not be used on Flatpak".toByteArray()
                exchange.responseHeaders.add("Content-Type", "audio/mp4")
                exchange.sendResponseHeaders(503, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                return@createContext
            }
            serveRemoteMp3(exchange, fixture)
        }
        server.start()
        withFlatpakSandbox {
            val diagnostics = RecordingPlaybackDiagnostics()
            val player = DesktopAudioPlayer(diagnostics)
            try {
                val directStreamUrl =
                    "http://127.0.0.1:${server.address.port}/library/parts/2.m4a?X-Plex-Token=test"
                val track = Track(
                    id = "plex:124",
                    title = "Flatpak Remote AAC",
                    artist = "Fixture",
                    album = "Real Audio Tests",
                    durationMs = 10_000,
                    streamUrl = directStreamUrl,
                    downloadUrl = "$directStreamUrl&download=1",
                    filepath = "/music/Artist/Album/02 Track.m4a",
                    audioCodec = "aac",
                )
                val transcodeUrl = DesktopSandboxPlayback.playbackStreamUrlForTrack(track)
                assertEquals(
                    "http://127.0.0.1:${server.address.port}/music/:/transcode/universal/start.mp3" +
                        "?path=%2Flibrary%2Fmetadata%2F124&mediaIndex=0&partIndex=0&protocol=http&format=mp3" +
                        "&audioCodec=mp3&directPlay=0&directStream=0&X-Plex-Token=test",
                    transcodeUrl,
                )

                player.play(listOf(track), 0)

                assertFlatpakSampledPlaybackStarted(
                    diagnostics = diagnostics,
                    player = player,
                    label = "Flatpak remote AAC via Plex transcode",
                    requestEvents = requestEvents,
                )
                assertTrue(
                    waitUntil(timeoutMs = 25_000L) {
                        requestEvents.any { it.endsWith("/music/:/transcode/universal/start.mp3") }
                    },
                    "Flatpak AAC playback should request the Plex MP3 transcode URL; requests=${requestEvents.toList()}",
                )
                assertFalse(
                    requestEvents.any { it.endsWith(".m4a") },
                    "Flatpak AAC playback must not request the direct AAC stream; requests=${requestEvents.toList()}",
                )
                assertFalse(
                    diagnostics.hasEngine(PlaybackEnginePath.JavaFxMediaPlayer),
                    "Flatpak remote AAC must not fall through to JavaFX media; engines=${diagnostics.engineEvents()} " +
                        "requests=${requestEvents.toList()} errors=${diagnostics.errorEvents()}",
                )
            } finally {
                player.releaseForTests()
            }
        }
        server.stop(0)
    }

    @Test
    fun wavFlacAndOggStartThroughSampledPlaybackAndReportPcmRms() {
        assumeRealAudioTestsEnabled()

        listOf("wikimedia-example.wav", "wikimedia-example.flac", "wikimedia-example.ogg").forEach { fixture ->
            val diagnostics = RecordingPlaybackDiagnostics()
            val player = DesktopAudioPlayer(diagnostics)
            try {
                val track = fixtureTrack(fixture, durationMs = 10_000)

                player.play(listOf(track), 0)

                assertTrue(
                    waitUntil {
                        diagnostics.hasEngine(PlaybackEnginePath.SampledClip) &&
                            player.state.value.isPlaying
                    },
                    "Sampled audio playback did not start for $fixture; engines=${diagnostics.engineEvents()} errors=${diagnostics.errorEvents()}",
                )
                assertTrue(diagnostics.hasEnergy(PlaybackEnginePath.SampledClip))
                assertTrue(waitUntil { player.state.value.positionMs > 0L })
                assertTrue(diagnostics.hasPlayingEvent(PlaybackEnginePath.SampledClip))
            } finally {
                player.releaseForTests()
            }
        }
    }

    @Test
    fun remoteMp3UsesJavaFxInsteadOfSampledClipPlayback() {
        assumeRealAudioTestsEnabled()

        val fixture = File(
            javaClass.classLoader.getResource("test-audio/wikimedia-example.mp3")?.toURI()
                ?: error("Missing test audio fixture"),
        )
        val bytes = fixture.readBytes()
        val requestEvents = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/library/track.mp3") { exchange ->
            requestEvents += "${exchange.requestMethod}:${exchange.requestHeaders.getFirst("Range") ?: "full"}"
            val headers = exchange.responseHeaders
            headers.add("Accept-Ranges", "bytes")
            headers.add("Content-Type", "audio/mpeg")
            val range = exchange.requestHeaders.getFirst("Range")
            if (range != null && range.startsWith("bytes=")) {
                val requested = range.removePrefix("bytes=").substringBefore(",")
                val start = requested.substringBefore("-").toIntOrNull()?.coerceIn(0, bytes.lastIndex) ?: 0
                val requestedEnd = requested.substringAfter("-", missingDelimiterValue = "")
                    .toIntOrNull()
                    ?.coerceIn(start, bytes.lastIndex)
                    ?: bytes.lastIndex
                val length = requestedEnd - start + 1
                headers.add("Content-Range", "bytes $start-$requestedEnd/${bytes.size}")
                headers.add("Content-Length", length.toString())
                exchange.sendResponseHeaders(206, if (exchange.requestMethod == "HEAD") -1L else length.toLong())
                if (exchange.requestMethod != "HEAD") {
                    exchange.responseBody.use { it.write(bytes, start, length) }
                } else {
                    exchange.close()
                }
            } else {
                headers.add("Content-Length", bytes.size.toString())
                exchange.sendResponseHeaders(200, if (exchange.requestMethod == "HEAD") -1L else bytes.size.toLong())
                if (exchange.requestMethod != "HEAD") {
                    exchange.responseBody.use { it.write(bytes) }
                } else {
                    exchange.close()
                }
            }
        }
        server.start()
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = DesktopAudioPlayer(diagnostics)
        try {
            val uri = "http://127.0.0.1:${server.address.port}/library/track.mp3?X-Plex-Token=test"
            val track = Track(
                id = "remote-mp3",
                title = "Remote MP3",
                artist = "Fixture",
                album = "Real Audio Tests",
                durationMs = 10_000,
                streamUrl = uri,
                downloadUrl = "$uri&download=1",
                audioCodec = "mp3",
            )

            player.play(listOf(track), 0)

            assertTrue(
                waitUntil(timeoutMs = 25_000L) {
                    diagnostics.hasEngine(PlaybackEnginePath.SampledStream)
                },
                "Remote MP3 should route to ffmpeg PCM (SampledStream), not JavaFX TLS; " +
                    "engines=${diagnostics.engineEvents()} requests=${requestEvents.toList()} " +
                    "errors=${diagnostics.errorEvents()}",
            )
            assertFalse(
                diagnostics.hasEngine(PlaybackEnginePath.SampledClip),
                "Remote MP3 must not use SampledClip because Java Sound MP3 decoding can produce static",
            )
            assertFalse(
                diagnostics.hasEngine(PlaybackEnginePath.JavaFxMediaPlayer),
                "Remote MP3 must not open via JavaFX; use shared OkHttp/ffmpeg networking instead",
            )
        } finally {
            player.releaseForTests()
            server.stop(0)
        }
    }

    @Test
    fun remoteShortMp3UsesJavaFxInsteadOfSampledClipPlayback() {
        assumeRealAudioTestsEnabled()

        val fixture = File(
            javaClass.classLoader.getResource("test-audio/mdn-t-rex-roar-cc0.mp3")?.toURI()
                ?: error("Missing test audio fixture"),
        )
        val bytes = fixture.readBytes()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/library/complete.mp3") { exchange ->
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.responseHeaders.add("Content-Length", bytes.size.toString())
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = DesktopAudioPlayer(diagnostics)
        try {
            val uri = "http://127.0.0.1:${server.address.port}/library/complete.mp3?X-Plex-Token=test"
            val track = Track(
                id = "complete-remote-mp3",
                title = "Complete Remote MP3",
                artist = "Fixture",
                album = "Real Audio Tests",
                durationMs = 2_500,
                streamUrl = uri,
                downloadUrl = "$uri&download=1",
                audioCodec = "mp3",
            )

            player.play(listOf(track), 0)

            assertTrue(
                waitUntil(timeoutMs = 25_000L) {
                    diagnostics.hasEngine(PlaybackEnginePath.SampledStream)
                },
                "Remote short MP3 should route to ffmpeg PCM (SampledStream), not JavaFX TLS; " +
                    "engines=${diagnostics.engineEvents()} errors=${diagnostics.errorEvents()} " +
                    "state=${player.state.value}",
            )
            assertFalse(
                diagnostics.hasEngine(PlaybackEnginePath.SampledClip),
                "Remote short MP3 must not use SampledClip because Java Sound MP3 decoding can produce static",
            )
        } finally {
            player.releaseForTests()
            server.stop(0)
        }
    }

    @Test
    fun remoteNoExtensionMp3StreamUsesJavaFxInsteadOfSampledPlayback() {
        assumeRealAudioTestsEnabled()

        val fixture = fixtureBytes("wikimedia-example.mp3")
        val requestEvents = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/live") { exchange ->
            requestEvents += "${exchange.requestMethod}:${exchange.requestURI.path}"
            serveRemoteMp3(exchange, fixture)
        }
        server.start()
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = DesktopAudioPlayer(diagnostics)
        try {
            val uri = "http://127.0.0.1:${server.address.port}/live"
            val track = Track(
                id = "no-extension-live-mp3",
                title = "No Extension Live MP3",
                artist = "Fixture",
                album = "Real Audio Tests",
                durationMs = 10_000,
                streamUrl = uri,
                downloadUrl = "",
            )

            player.play(listOf(track), 0)

            assertTrue(
                waitUntil(timeoutMs = 25_000L) {
                    diagnostics.hasEngine(PlaybackEnginePath.SampledStream)
                },
                "No-extension remote MP3 should use ffmpeg PCM instead of JavaFX TLS; " +
                    "engines=${diagnostics.engineEvents()} requests=${requestEvents.toList()} " +
                    "errors=${diagnostics.errorEvents()} state=${player.state.value}",
            )
            assertFalse(
                diagnostics.hasEngine(PlaybackEnginePath.SampledClip),
                "No-extension remote MP3 must not use SampledClip because Java Sound MP3 decoding can produce static",
            )
        } finally {
            player.releaseForTests()
            server.stop(0)
        }
    }

    @Test
    fun remoteExtensionlessStreamCanPauseAndResume() {
        assumeRealAudioTestsEnabled()

        val fixture = fixtureBytes("wikimedia-example.mp3")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rest/stream.view") { exchange ->
            serveRemoteMp3(exchange, fixture)
        }
        server.start()
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = DesktopAudioPlayer(diagnostics)
        try {
            val track = Track(
                id = "subsonic-extensionless-mp3",
                title = "Subsonic MP3",
                artist = "Fixture",
                album = "Real Audio Tests",
                durationMs = 10_000,
                streamUrl = "http://127.0.0.1:${server.address.port}/rest/stream.view?id=track",
                downloadUrl = "",
                filepath = "/music/Fixture/Subsonic MP3.mp3",
            )

            player.play(listOf(track), 0)
            val expectedEngine = if (System.getProperty("os.name").orEmpty().lowercase().contains("linux")) {
                PlaybackEnginePath.SampledStream
            } else {
                PlaybackEnginePath.JavaFxMediaPlayer
            }

            assertTrue(
                waitUntil(timeoutMs = 25_000L) {
                    diagnostics.hasEngine(expectedEngine) &&
                        player.state.value.isPlaying
                },
                "Subsonic-like stream should start through $expectedEngine; " +
                    "state=${player.state.value} engines=${diagnostics.engineEvents()} errors=${diagnostics.errorEvents()}",
            )
            player.togglePlayPause()
            assertTrue(
                waitUntil { !player.state.value.isPlaying && !player.state.value.isBuffering },
                "pause did not settle; state=${player.state.value}",
            )
            val pausedPositionMs = player.state.value.positionMs

            player.togglePlayPause()

            assertTrue(
                waitUntil(timeoutMs = 10_000L) {
                    player.state.value.isPlaying && player.state.value.positionMs > pausedPositionMs + 250L
                },
                "Subsonic-like stream did not resume; state=${player.state.value} " +
                    "errors=${diagnostics.errorEvents()}",
            )
        } finally {
            player.releaseForTests()
            server.stop(0)
        }
    }

    @Test
    fun localMp3CanPauseAndResume() {
        assumeRealAudioTestsEnabled()

        val diagnostics = RecordingPlaybackDiagnostics()
        val player = DesktopAudioPlayer(diagnostics)
        try {
            player.play(listOf(fixtureTrack("wikimedia-example.mp3", durationMs = 10_000)), 0)
            assertTrue(
                waitUntil(timeoutMs = 25_000L) {
                    player.state.value.isPlaying && player.state.value.positionMs > 500L
                },
                "local MP3 should start; state=${player.state.value} errors=${diagnostics.errorEvents()}",
            )
            player.togglePlayPause()
            assertTrue(waitUntil { !player.state.value.isPlaying }, "pause did not settle")
            val pausedPositionMs = player.state.value.positionMs
            player.togglePlayPause()
            assertTrue(
                waitUntil(timeoutMs = 10_000L) {
                    player.state.value.isPlaying && player.state.value.positionMs > pausedPositionMs + 250L
                },
                "local MP3 did not resume; state=${player.state.value}",
            )
        } finally {
            player.releaseForTests()
        }
    }

    @Test
    fun testRealLiveStreamCiut() {
        assumeRealAudioTestsEnabled()
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = DesktopAudioPlayer(diagnostics)
        try {
            val track = Track(
                id = "radio:ciut-live",
                title = "CIUT",
                artist = "Radio",
                album = "Radio",
                durationMs = 0L,
                streamUrl = "https://ice23.securenetsystems.net/CIUT",
                downloadUrl = "https://ice23.securenetsystems.net/CIUT",
            )
            player.play(listOf(track), 0)
            val played = waitUntil(timeoutMs = 20_000L) {
                diagnostics.hasEngine(PlaybackEnginePath.SampledStream) &&
                    player.state.value.isPlaying
            }
            if (!played) {
                val errors = diagnostics.errorEvents().joinToString()
                assumeTrue(
                    "CIUT live stream unreachable from this environment: state=${player.state.value} errors=$errors",
                    false,
                )
            }
            assertTrue(
                played,
                "CIUT stream should play via ffmpeg PCM; state=${player.state.value} errors=${diagnostics.errorEvents()}",
            )
        } finally {
            player.releaseForTests()
        }
    }

    @Test
    fun remoteMp3UsesJavaFxDownloadFallbackWhenStreamEndpointIsNotPlayable() {
        assumeRealAudioTestsEnabled()

        val fixture = File(
            javaClass.classLoader.getResource("test-audio/mdn-t-rex-roar-cc0.mp3")?.toURI()
                ?: error("Missing test audio fixture"),
        )
        val bytes = fixture.readBytes()
        val requestEvents = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/Audio/remote/stream") { exchange ->
            requestEvents += "${exchange.requestMethod}:${exchange.requestURI.path}"
            exchange.responseHeaders.add("Content-Type", "text/plain")
            val body = "stream endpoint is not suitable for desktop buffering".toByteArray()
            exchange.sendResponseHeaders(503, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/Items/remote/Download") { exchange ->
            requestEvents += "${exchange.requestMethod}:${exchange.requestURI.path}"
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.responseHeaders.add("Content-Length", bytes.size.toString())
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = DesktopAudioPlayer(diagnostics)
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val track = Track(
                id = "download-url-remote-mp3",
                title = "Download URL Remote MP3",
                artist = "Fixture",
                album = "Real Audio Tests",
                durationMs = 2_500,
                streamUrl = "$base/Audio/remote/stream?static=true&api_key=test",
                downloadUrl = "$base/Items/remote/Download?api_key=test",
                audioCodec = "MP3",
            )

            player.play(listOf(track), 0)

            assertTrue(
                waitUntil(timeoutMs = 25_000L) {
                    requestEvents.any { it == "GET:/Items/remote/Download" } &&
                        player.state.value.isPlaying
                },
                "Desktop should fall back to downloadUrl when remote streamUrl cannot play; " +
                    "state=${player.state.value} requests=${requestEvents.toList()} " +
                    "engines=${diagnostics.engineEvents()} errors=${diagnostics.errorEvents()}",
            )
            assertFalse(
                diagnostics.hasEngine(PlaybackEnginePath.SampledClip),
                "Remote MP3 fallback must not use SampledClip because Java Sound MP3 decoding can produce static",
            )
        } finally {
            player.releaseForTests()
            server.stop(0)
        }
    }

    @Test
    fun m4aCrossfadeCommitsOrFallsBackToSecondTrack() {
        assumeRealAudioTestsEnabled()
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = DesktopAudioPlayer(diagnostics)
        try {
            val first = fixtureTrack("wikimedia-example.m4a", durationMs = 2_500, id = "first-m4a")
            val second = fixtureTrack("wikimedia-example.m4a", durationMs = 10_000, id = "second-m4a")

            player.setCrossfadeDurationMs(12_000)
            player.play(listOf(first, second), 0)
            val expectedEngine = expectedLocalJavaFxFriendlyEngine()

            assertTrue(
                waitUntil {
                    diagnostics.hasEngine(expectedEngine) &&
                        player.state.value.isPlaying
                },
                "M4A crossfade playback should use $expectedEngine; engines=${diagnostics.engineEvents()} errors=${diagnostics.errorEvents()}",
            )
            assertTrue(
                waitUntil(timeoutMs = 35_000) { player.state.value.currentTrack?.id == second.id },
                "M4A crossfade did not commit or fall back to the second track; " +
                    "state=${player.state.value} errors=${diagnostics.errorEvents()}",
            )

            if (diagnostics.hasCommitted(PlaybackEnginePath.JavaFxMediaPlayer, second.id)) {
                val volumes = diagnostics.volumeSteps(PlaybackEnginePath.JavaFxMediaPlayer)
                assertTrue(volumes.size >= 4, "Expected several crossfade volume samples")
                assertTrue(volumes.zipWithNext().all { (left, right) -> left.outgoingVolume >= right.outgoingVolume })
                assertTrue(volumes.zipWithNext().all { (left, right) -> left.incomingVolume <= right.incomingVolume })
            }
            assertEquals(second, player.state.value.currentTrack)
        } finally {
            player.releaseForTests()
        }
    }

    @Test
    fun localMp3CrossfadeUsesJavaFxAndCommitsToSecondTrack() {
        assumeRealAudioTestsEnabled()
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = DesktopAudioPlayer(diagnostics)
        try {
            val first = fixtureTrack("mdn-t-rex-roar-cc0.mp3", durationMs = 2_500, id = "first-mp3-crossfade")
            val second = fixtureTrack("mdn-t-rex-roar-cc0.mp3", durationMs = 10_000, id = "second-mp3-crossfade")

            player.setCrossfadeDurationMs(12_000)
            player.play(listOf(first, second), 0)
            val expectedEngine = expectedLocalJavaFxFriendlyEngine()

            assertTrue(
                waitUntil {
                    diagnostics.hasEngine(expectedEngine) &&
                        player.state.value.isPlaying
                },
                "Local MP3 crossfade playback should use $expectedEngine, not SampledClip; " +
                    "engines=${diagnostics.engineEvents()} errors=${diagnostics.errorEvents()}",
            )
            assertTrue(
                waitUntil(timeoutMs = 35_000) {
                    player.state.value.currentTrack?.id == second.id
                },
                "Local MP3 crossfade/gapless did not advance to the second track; " +
                    "state=${player.state.value} engines=${diagnostics.engineEvents()} " +
                    "errors=${diagnostics.errorEvents()}",
            )
            assertFalse(
                diagnostics.hasEngine(PlaybackEnginePath.SampledClip),
                "Local MP3 crossfade must not use SampledClip because Java Sound MP3 decoding can produce static",
            )
            if (diagnostics.hasCommitted(PlaybackEnginePath.JavaFxMediaPlayer, second.id)) {
                val volumes = diagnostics.volumeSteps(PlaybackEnginePath.JavaFxMediaPlayer)
                assertTrue(volumes.size >= 4, "Expected several JavaFX crossfade volume samples")
                assertTrue(volumes.zipWithNext().all { (left, right) -> left.outgoingVolume >= right.outgoingVolume })
                assertTrue(volumes.zipWithNext().all { (left, right) -> left.incomingVolume <= right.incomingVolume })
            }
            assertEquals(second, player.state.value.currentTrack)
        } finally {
            player.releaseForTests()
        }
    }

    @Test
    fun remoteMp3CrossfadeUsesJavaFxStreamAndCommitsToSecondTrack() {
        assumeRealAudioTestsEnabled()
        val bytes = fixtureBytes("mdn-t-rex-roar-cc0.mp3")
        val requestEvents = Collections.synchronizedList(mutableListOf<String>())
        val server = createRemoteMp3HttpServer(bytes, requestEvents)
        server.start()
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = DesktopAudioPlayer(diagnostics)
        try {
            val base = "http://127.0.0.1:${server.address.port}/library/track.mp3"
            val first = remoteTrack(
                id = "first-remote-mp3-crossfade",
                uri = "$base?track=first",
                durationMs = 2_500,
                audioCodec = "mp3",
            )
            val second = remoteTrack(
                id = "second-remote-mp3-crossfade",
                uri = "$base?track=second",
                durationMs = 10_000,
                audioCodec = "mp3",
            )

            player.setCrossfadeDurationMs(12_000)
            player.play(listOf(first, second), 0)

            assertTrue(
                waitUntil(timeoutMs = 25_000L) {
                    diagnostics.hasEngine(PlaybackEnginePath.SampledStream)
                },
                "Remote MP3 crossfade playback should use ffmpeg PCM, not JavaFX TLS; " +
                    "engines=${diagnostics.engineEvents()} requests=${requestEvents.toList()} " +
                    "errors=${diagnostics.errorEvents()} state=${player.state.value}",
            )
            assertTrue(
                waitUntil(timeoutMs = 35_000) {
                    player.state.value.currentTrack?.id == second.id
                },
                "Remote MP3 crossfade/gapless did not advance to the second track; " +
                    "state=${player.state.value} engines=${diagnostics.engineEvents()} " +
                    "requests=${requestEvents.toList()} errors=${diagnostics.errorEvents()}",
            )
            assertFalse(
                diagnostics.hasEngine(PlaybackEnginePath.SampledClip),
                "Remote MP3 crossfade must not use SampledClip because Java Sound MP3 decoding can produce static",
            )
            assertEquals(second, player.state.value.currentTrack)
        } finally {
            player.releaseForTests()
            server.stop(0)
        }
    }

    @Test
    fun desktopPlaybackFeedsListenBrainzReporterAfterAudibleThreshold() = runBlocking {
        assumeRealAudioTestsEnabled()
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = DesktopAudioPlayer(diagnostics)
        val reporterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val submittedBodies = Collections.synchronizedList(mutableListOf<String>())
        try {
            val track = fixtureTrack("mdn-t-rex-roar-cc0.mp3", durationMs = 2_500, id = "listenbrainz-real-desktop")
            val credentialStore = FakeSecureCredentialStore()
            credentialStore.write(SecureCredentialKey.ListenBrainzUserToken, "token")
            val settings = MutableStateFlow(
                AppSettings(
                    listenBrainz = ListenBrainzSettings(
                        enabled = true,
                        username = "ada",
                        submitNowPlaying = false,
                        submitCurrentTrackFeedback = false,
                    ),
                ),
            )
            val nowMs = { 1_700_000_000_000L + player.state.value.positionMs.coerceAtLeast(0L) }
            val client = ListenBrainzClient(
                testHttpClient(
                    MockEngine { request ->
                        val body = request.bodyText()
                        submittedBodies += body
                        respond(
                            content = """{"status":"ok"}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ),
                baseUrl = "https://listenbrainz.example",
            )
            val account = FakeListenBrainzAccountActions(
                settings = settings,
                credentialStore = credentialStore,
                nowMs = nowMs,
            )
            ListenBrainzPlaybackReporter(
                client = client,
                credentialStore = credentialStore,
                accountRepository = account,
                audioPlayer = player,
                appSettings = settings,
                nowMs = nowMs,
            ).start(reporterScope)

            player.play(listOf(track), 0)

            assertTrue(
                waitUntil(timeoutMs = 30_000L) {
                    submittedBodies.any { it.contains(""""listen_type":"single"""") } &&
                        account.listenSubmittedCount >= 1
                },
                "Expected real desktop playback to submit a ListenBrainz listen; " +
                    "state=${player.state.value} engines=${diagnostics.engineEvents()} errors=${diagnostics.errorEvents()} " +
                    "bodies=${submittedBodies.toList()} listenSubmittedCount=${account.listenSubmittedCount}",
            )
            assertTrue(submittedBodies.any { it.contains("listenbrainz-real-desktop") })
            assertEquals(1, account.listenSubmittedCount)
        } finally {
            reporterScope.cancel()
            player.releaseForTests()
        }
    }

    private fun assumeRealAudioTestsEnabled() {
        assumeTrue("Real audio playback tests are disabled", System.getProperty("phoebe.realAudioTests").toBoolean())
    }

    private fun expectedLocalJavaFxFriendlyEngine(): PlaybackEnginePath =
        if (System.getProperty("os.name").orEmpty().lowercase().contains("linux")) {
            PlaybackEnginePath.SampledStream
        } else {
            PlaybackEnginePath.JavaFxMediaPlayer
        }

    private fun assumeFlatpakRealAudioTestsEnabled() {
        assumeRealAudioTestsEnabled()
        assumeLinux()
    }

    private inline fun withFlatpakSandbox(block: () -> Unit) {
        DesktopSandboxPlayback.flatpakSandboxOverride = { true }
        try {
            block()
        } finally {
            DesktopSandboxPlayback.flatpakSandboxOverride = null
        }
    }

    private fun assertFlatpakSampledStreamPlaybackStarted(
        diagnostics: RecordingPlaybackDiagnostics,
        player: DesktopAudioPlayer,
        label: String,
        requestEvents: List<String>? = null,
    ) {
        assertTrue(
            waitUntil(timeoutMs = 25_000L) {
                diagnostics.hasEngine(PlaybackEnginePath.SampledStream) &&
                    player.state.value.isPlaying &&
                    diagnostics.hasEnergy(PlaybackEnginePath.SampledStream) &&
                    diagnostics.hasPlayingEvent(PlaybackEnginePath.SampledStream)
            },
            "$label should route to sampled stream; engines=${diagnostics.engineEvents()} " +
                "requests=${requestEvents.orEmpty()} errors=${diagnostics.errorEvents()}",
        )
    }

    private fun assertFlatpakSampledClipPlaybackStarted(
        diagnostics: RecordingPlaybackDiagnostics,
        player: DesktopAudioPlayer,
        label: String,
        requestEvents: List<String>? = null,
    ) {
        assertTrue(
            waitUntil(timeoutMs = 25_000L) {
                diagnostics.hasEngine(PlaybackEnginePath.SampledClip) &&
                    player.state.value.isPlaying &&
                    diagnostics.hasEnergy(PlaybackEnginePath.SampledClip) &&
                    diagnostics.hasPlayingEvent(PlaybackEnginePath.SampledClip)
            },
            "$label should route to sampled clip; engines=${diagnostics.engineEvents()} " +
                "requests=${requestEvents.orEmpty()} errors=${diagnostics.errorEvents()}",
        )
    }

    private fun assertFlatpakSampledPlaybackStarted(
        diagnostics: RecordingPlaybackDiagnostics,
        player: DesktopAudioPlayer,
        label: String,
        requestEvents: List<String>? = null,
    ) {
        assertTrue(
            waitUntil(timeoutMs = 25_000L) {
                player.state.value.isPlaying && (
                    (
                        diagnostics.hasEngine(PlaybackEnginePath.SampledStream) &&
                            diagnostics.hasEnergy(PlaybackEnginePath.SampledStream) &&
                            diagnostics.hasPlayingEvent(PlaybackEnginePath.SampledStream)
                        ) ||
                        (
                            diagnostics.hasEngine(PlaybackEnginePath.SampledClip) &&
                                diagnostics.hasEnergy(PlaybackEnginePath.SampledClip) &&
                                diagnostics.hasPlayingEvent(PlaybackEnginePath.SampledClip)
                            )
                    )
            },
            "$label should route to sampled stream or clip; engines=${diagnostics.engineEvents()} " +
                "requests=${requestEvents.orEmpty()} errors=${diagnostics.errorEvents()}",
        )
    }

    private fun fixtureBytes(name: String): ByteArray {
        val url = javaClass.classLoader.getResource("test-audio/$name")
            ?: error("Missing test audio fixture: $name")
        return File(url.toURI()).readBytes()
    }

    private fun remoteTrack(
        id: String,
        uri: String,
        durationMs: Long,
        audioCodec: String,
    ): Track =
        Track(
            id = id,
            title = "Remote Track",
            artist = "Fixture",
            album = "Real Audio Tests",
            durationMs = durationMs,
            streamUrl = uri,
            downloadUrl = "$uri&download=1",
            audioCodec = audioCodec,
        )

    private fun createRemoteMp3HttpServer(
        bytes: ByteArray,
        requestEvents: MutableList<String>,
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/library/track.mp3") { exchange ->
            requestEvents += "${exchange.requestMethod}:${exchange.requestURI.path}"
            serveRemoteMp3(exchange, bytes)
        }
        return server
    }

    private fun serveRemoteMp3(
        exchange: com.sun.net.httpserver.HttpExchange,
        bytes: ByteArray,
    ) {
        val headers = exchange.responseHeaders
        headers.add("Accept-Ranges", "bytes")
        headers.add("Content-Type", "audio/mpeg")
        val range = exchange.requestHeaders.getFirst("Range")
        if (range != null && range.startsWith("bytes=")) {
            val requested = range.removePrefix("bytes=").substringBefore(",")
            val start = requested.substringBefore("-").toIntOrNull()?.coerceIn(0, bytes.lastIndex) ?: 0
            val requestedEnd = requested.substringAfter("-", missingDelimiterValue = "")
                .toIntOrNull()
                ?.coerceIn(start, bytes.lastIndex)
                ?: bytes.lastIndex
            val length = requestedEnd - start + 1
            headers.add("Content-Range", "bytes $start-$requestedEnd/${bytes.size}")
            headers.add("Content-Length", length.toString())
            exchange.sendResponseHeaders(206, if (exchange.requestMethod == "HEAD") -1L else length.toLong())
            if (exchange.requestMethod != "HEAD") {
                exchange.responseBody.use { it.write(bytes, start, length) }
            } else {
                exchange.close()
            }
        } else {
            headers.add("Content-Length", bytes.size.toString())
            exchange.sendResponseHeaders(200, if (exchange.requestMethod == "HEAD") -1L else bytes.size.toLong())
            if (exchange.requestMethod != "HEAD") {
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.close()
            }
        }
    }

    private fun fixtureTrack(
        name: String,
        durationMs: Long,
        id: String = name,
    ): Track {
        val url = javaClass.classLoader.getResource("test-audio/$name")
            ?: error("Missing test audio fixture: $name")
        val file = File(url.toURI())
        return Track(
            id = id,
            title = name,
            artist = "Fixture",
            album = "Real Audio Tests",
            durationMs = durationMs,
            streamUrl = file.toURI().toString(),
            downloadUrl = "",
            localUri = file.toURI().toString(),
            filepath = file.absolutePath,
            audioCodec = file.extension,
        )
    }

    private fun waitUntil(
        timeoutMs: Long = 15_000L,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(100L)
        }
        return condition()
    }

    private fun HttpRequestData.bodyText(): String =
        when (val content = body) {
            is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
            is OutgoingContent.NoContent -> ""
            else -> content.toString()
        }

    private data class VolumeSample(
        val outgoingVolume: Float,
        val incomingVolume: Float,
    )

    private class RecordingPlaybackDiagnostics : PlaybackDiagnostics {
        private val engines = Collections.synchronizedList(mutableListOf<PlaybackEnginePath>())
        private val energyByEngine = Collections.synchronizedMap(mutableMapOf<PlaybackEnginePath, Double>())
        private val playingEngines = Collections.synchronizedSet(mutableSetOf<PlaybackEnginePath>())
        private val committed = Collections.synchronizedList(mutableListOf<Pair<PlaybackEnginePath, String>>())
        private val volumes = Collections.synchronizedList(mutableListOf<Pair<PlaybackEnginePath, VolumeSample>>())
        private val progress = Collections.synchronizedList(mutableListOf<Pair<PlaybackEnginePath, Long>>())
        private val errors = Collections.synchronizedList(mutableListOf<Pair<PlaybackEnginePath, String?>>())

        override fun engineSelected(engine: PlaybackEnginePath) {
            engines += engine
        }

        override fun platformPlaying(engine: PlaybackEnginePath, positionMs: Long, durationMs: Long) {
            playingEngines += engine
        }

        override fun playbackProgress(engine: PlaybackEnginePath, positionMs: Long, durationMs: Long) {
            progress += engine to positionMs
        }

        override fun decodedAudioEnergy(engine: PlaybackEnginePath, rms: Double) {
            energyByEngine[engine] = maxOf(energyByEngine[engine] ?: 0.0, rms)
        }

        override fun crossfadeVolume(
            engine: PlaybackEnginePath,
            step: Int,
            outgoingVolume: Float,
            incomingVolume: Float,
        ) {
            volumes += engine to VolumeSample(outgoingVolume, incomingVolume)
        }

        override fun crossfadeCommitted(engine: PlaybackEnginePath, incomingTrackId: String) {
            committed += engine to incomingTrackId
        }

        override fun playbackError(engine: PlaybackEnginePath, message: String?) {
            errors += engine to message
        }

        fun hasEngine(engine: PlaybackEnginePath): Boolean = engine in engines

        fun hasEnergy(engine: PlaybackEnginePath): Boolean = (energyByEngine[engine] ?: 0.0) > 0.000001

        fun hasPlayingEvent(engine: PlaybackEnginePath): Boolean = engine in playingEngines

        fun hasCommitted(engine: PlaybackEnginePath, trackId: String): Boolean = engine to trackId in committed

        fun engineEvents(): List<PlaybackEnginePath> = engines.toList()

        fun errorEvents(): List<Pair<PlaybackEnginePath, String?>> = errors.toList()

        fun progressEvents(engine: PlaybackEnginePath): List<Long> =
            progress.filter { it.first == engine }.map { it.second }.takeLast(12)

        fun volumeSteps(engine: PlaybackEnginePath): List<VolumeSample> =
            volumes.filter { it.first == engine }.map { it.second }
    }
}
