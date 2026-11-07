package com.phoebe.app

import android.app.Application
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phoebe.app.data.ListenBrainzClient
import com.phoebe.app.data.ListenBrainzPlaybackReporter
import com.phoebe.app.domain.AppSettings
import com.phoebe.app.domain.ListenBrainzSettings
import com.phoebe.app.domain.Track
import com.phoebe.app.player.AndroidAudioPlayer
import com.phoebe.app.player.PlaybackDiagnostics
import com.phoebe.app.player.PlaybackEnginePath
import com.phoebe.app.platform.SecureCredentialKey
import com.phoebe.app.testing.FakeListenBrainzAccountActions
import com.phoebe.app.testing.FakeSecureCredentialStore
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealAudioPlaybackInstrumentedTest {
    private lateinit var app: Application
    private val copiedFixtures = mutableListOf<File>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AndroidContextHolder.application = app
        runBlocking { resetPlaybackStackForTests() }
    }

    @After
    fun tearDown() {
        runBlocking { resetPlaybackStackForTests() }
        copiedFixtures.forEach { it.delete() }
        copiedFixtures.clear()
    }

    private suspend fun resetPlaybackStackForTests() {
        AndroidAudioPlayer(PlaybackDiagnostics.None).releaseForTests()
    }

    @Test
    fun media3PlaybackStartsAdvancesAndReportsNonSilentAudio() = runBlocking {
        assumeRealAudioTestsEnabled()

        listOf("wikimedia-example.mp3", "wikimedia-example.m4a", "wikimedia-example.flac").forEach { fixture ->
            val diagnostics = RecordingPlaybackDiagnostics()
            val player = AndroidAudioPlayer(diagnostics)
            try {
                val file = copyAssetFixture(fixture)
                val track = fixtureTrack(file, durationMs = 10_000)

                player.play(listOf(track), 0)

                assumeTrue(
                    "Media3 playback did not start for $fixture",
                    waitUntil(timeoutMs = 30_000L) {
                        diagnostics.hasEngine(PlaybackEnginePath.Media3) &&
                            playbackLooksActive(player, diagnostics)
                    },
                )
                val firstPosition = player.state.value.positionMs
                assertTrue(
                    waitUntil(timeoutMs = 30_000L) {
                        player.state.value.positionMs > firstPosition + 250L ||
                            diagnostics.maxProgress(PlaybackEnginePath.Media3) > firstPosition + 250L ||
                            diagnostics.hasEnergy(PlaybackEnginePath.Media3)
                    },
                    "Expected playback position or audio diagnostics to advance for $fixture",
                )
                assertTrue(diagnostics.hasPlayingEvent(PlaybackEnginePath.Media3))
                val media3Energy = waitUntil(timeoutMs = 10_000L) {
                    diagnostics.hasEnergy(PlaybackEnginePath.Media3)
                }
                assertTrue(
                    media3Energy || decodedFixtureRms(file) > 0.000001,
                    "Expected nonzero decoded audio signal for $fixture",
                )
            } finally {
                player.releaseForTests()
            }
        }
    }

    @Test
    fun repeatedMedia3StartsDoNotLeaveLoadedTrackPaused() = runBlocking {
        assumeRealAudioTestsEnabled()
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = AndroidAudioPlayer(diagnostics)
        try {
            val first = fixtureTrack(copyAssetFixture("wikimedia-example.mp3"), durationMs = 10_000, id = "repeat-first")
            val second = fixtureTrack(copyAssetFixture("mdn-t-rex-roar-cc0.mp3"), durationMs = 2_500, id = "repeat-second")
            val queue = listOf(first, second)

            repeat(16) { attempt ->
                val index = attempt % queue.size
                val requested = queue[index]
                player.play(queue, index)

                val started = waitUntil(timeoutMs = 8_000L) {
                    val state = player.state.value
                    // Playback may legitimately advance past (or finish) the requested track before
                    // the poll observes it. Only a track that stays loaded at position 0 and paused
                    // is the failure this test guards against.
                    state.isPlaying ||
                        state.positionMs > 250L ||
                        state.currentIndex != index
                }
                assertTrue(
                    started,
                    "Attempt $attempt loaded ${requested.id} without starting. state=${player.state.value}",
                )
            }
        } finally {
            player.releaseForTests()
        }
    }

    @Test
    fun media3CrossfadeRampsVolumesCommitsAndKeepsAdvancing() = runBlocking {
        assumeRealAudioTestsEnabled()
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = AndroidAudioPlayer(diagnostics)
        try {
            val firstFile = copyAssetFixture("wikimedia-example.mp3")
            val secondFile = copyAssetFixture("wikimedia-example.mp3")
            val first = fixtureTrack(firstFile, durationMs = 10_000, id = "first-mp3")
            val second = fixtureTrack(secondFile, durationMs = 10_000, id = "second-mp3")

            player.setCrossfadeDurationMs(4_000)
            player.play(listOf(first, second), 0)

            assumeTrue(
                "Media3 playback did not start for crossfade",
                waitUntil(timeoutMs = 30_000L) {
                    diagnostics.hasEngine(PlaybackEnginePath.Media3) &&
                        playbackLooksActive(player, diagnostics)
                },
            )
            assertTrue(
                waitUntil(timeoutMs = 45_000L) {
                    diagnostics.hasEngine(PlaybackEnginePath.Media3Crossfade) &&
                        player.state.value.currentTrack?.id == second.id &&
                        diagnostics.hasCommitted(PlaybackEnginePath.Media3Crossfade, second.id)
                },
                "Expected crossfade to commit to ${second.id}",
            )

            val volumes = diagnostics.volumeSteps(PlaybackEnginePath.Media3Crossfade)
            assertTrue(volumes.size >= 4, "Expected several crossfade volume samples")
            assertTrue(volumes.zipWithNext().all { (left, right) -> left.outgoingVolume >= right.outgoingVolume })
            assertTrue(volumes.zipWithNext().all { (left, right) -> left.incomingVolume <= right.incomingVolume })
            assertEquals(second, player.state.value.currentTrack)

            val positionAfterCommit = player.state.value.positionMs
            assertTrue(
                waitUntil(timeoutMs = 30_000L) { player.state.value.positionMs > positionAfterCommit + 250L },
                "Expected playback to keep advancing after crossfade commit",
            )
        } finally {
            player.releaseForTests()
        }
    }

    @Test
    fun media3PlaybackFeedsListenBrainzReporterAfterAudibleThreshold() = runBlocking {
        assumeRealAudioTestsEnabled()
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = AndroidAudioPlayer(diagnostics)
        val reporterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val submittedBodies = Collections.synchronizedList(mutableListOf<String>())
        try {
            val file = copyAssetFixture("wikimedia-example.mp3")
            val track = fixtureTrack(file, durationMs = 1_000, id = "listenbrainz-real-android")
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

            assumeTrue(
                "Media3 playback did not start for ListenBrainz reporter",
                waitUntil(timeoutMs = 30_000L) {
                    diagnostics.hasEngine(PlaybackEnginePath.Media3) &&
                        playbackLooksActive(player, diagnostics)
                },
            )
            assertTrue(
                waitUntil(timeoutMs = 30_000L) {
                    submittedBodies.any { it.contains(""""listen_type":"single"""") } &&
                        account.listenSubmittedCount >= 1
                },
                "Expected real Android playback to submit a ListenBrainz listen; " +
                    "state=${player.state.value} engines=${diagnostics.hasEngine(PlaybackEnginePath.Media3)} " +
                    "playing=${diagnostics.hasPlayingEvent(PlaybackEnginePath.Media3)} " +
                    "maxProgress=${diagnostics.maxProgress(PlaybackEnginePath.Media3)} " +
                    "bodies=${submittedBodies.toList()} listenSubmittedCount=${account.listenSubmittedCount}",
            )
            assertTrue(submittedBodies.any { it.contains("listenbrainz-real-android") })
            assertEquals(1, account.listenSubmittedCount)
        } finally {
            reporterScope.cancel()
            player.releaseForTests()
        }
    }

    private fun assumeRealAudioTestsEnabled() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString("phoebe.realAudioTests")
            .toBoolean()
        assumeTrue("Real audio playback tests are disabled", enabled)
    }

    private fun copyAssetFixture(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(app.cacheDir, "real-audio-${System.nanoTime()}-$name")
        instrumentation.context.assets.open("test-audio/$name").use { input ->
            output.outputStream().use { outputStream ->
                input.copyTo(outputStream)
            }
        }
        copiedFixtures += output
        return output
    }

    private fun fixtureTrack(
        file: File,
        durationMs: Long,
        id: String = file.name,
    ): Track =
        Track(
            id = id,
            title = file.name,
            artist = "Fixture",
            album = "Real Audio Tests",
            durationMs = durationMs,
            streamUrl = Uri.fromFile(file).toString(),
            downloadUrl = "",
            localUri = Uri.fromFile(file).toString(),
            filepath = file.absolutePath,
            audioCodec = file.extension,
        )

    private fun playbackLooksActive(
        player: AndroidAudioPlayer,
        diagnostics: RecordingPlaybackDiagnostics,
    ): Boolean {
        val state = player.state.value
        return (state.isPlaying && !state.isBuffering) ||
            state.positionMs > 250L ||
            diagnostics.hasPlayingEvent(PlaybackEnginePath.Media3)
    }

    private fun waitUntil(
        timeoutMs: Long = 30_000L,
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

    private fun decodedFixtureRms(file: File): Double {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return 0.0
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return 0.0
            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            return drainDecoderRms(extractor, codec)
        } finally {
            codec?.runCatchingStopAndRelease()
            extractor.release()
        }
    }

    private fun drainDecoderRms(extractor: MediaExtractor, codec: MediaCodec): Double {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var outputFormat = codec.outputFormat
        var sumSquares = 0.0
        var sampleCount = 0L
        while (!outputDone && sampleCount < MaxFallbackDecodeSamples) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    val sampleSize = inputBuffer?.let { extractor.readSampleData(it, 0) } ?: -1
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            sampleSize,
                            extractor.sampleTime.coerceAtLeast(0L),
                            0,
                        )
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        val partial = outputBufferRms(outputBuffer.slice(), outputFormat)
                        sumSquares += partial.sumSquares
                        sampleCount += partial.sampleCount
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
        return if (sampleCount == 0L) 0.0 else sqrt(sumSquares / sampleCount.toDouble())
    }

    private fun outputBufferRms(buffer: ByteBuffer, format: MediaFormat): RmsAccumulator {
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        val sampleBytes = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        val frameSize = channels * sampleBytes
        val ordered = buffer.order(ByteOrder.LITTLE_ENDIAN)
        var offset = ordered.position()
        val end = ordered.limit()
        var sumSquares = 0.0
        var sampleCount = 0L
        while (offset + frameSize <= end) {
            var sampleOffset = offset
            repeat(channels) {
                val normalized = when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT ->
                        ((ordered.get(sampleOffset).toInt() and 0xFF) - 128).toDouble() / 128.0
                    AudioFormat.ENCODING_PCM_FLOAT ->
                        ordered.getFloat(sampleOffset).toDouble().coerceIn(-1.0, 1.0)
                    else ->
                        ordered.getShort(sampleOffset).toDouble() / 32768.0
                }
                sumSquares += normalized * normalized
                sampleCount++
                sampleOffset += sampleBytes
            }
            offset += frameSize
        }
        return RmsAccumulator(sumSquares, sampleCount)
    }

    private fun MediaCodec.runCatchingStopAndRelease() {
        runCatching { stop() }
        release()
    }

    private data class RmsAccumulator(
        val sumSquares: Double,
        val sampleCount: Long,
    )

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
        private val progressByEngine = Collections.synchronizedMap(mutableMapOf<PlaybackEnginePath, Long>())

        override fun engineSelected(engine: PlaybackEnginePath) {
            engines += engine
        }

        override fun platformPlaying(engine: PlaybackEnginePath, positionMs: Long, durationMs: Long) {
            playingEngines += engine
            recordProgress(engine, positionMs)
        }

        override fun playbackProgress(engine: PlaybackEnginePath, positionMs: Long, durationMs: Long) {
            recordProgress(engine, positionMs)
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

        fun hasEngine(engine: PlaybackEnginePath): Boolean = engine in engines

        fun hasEnergy(engine: PlaybackEnginePath): Boolean = (energyByEngine[engine] ?: 0.0) > 0.000001

        fun hasPlayingEvent(engine: PlaybackEnginePath): Boolean = engine in playingEngines

        fun maxProgress(engine: PlaybackEnginePath): Long = progressByEngine[engine] ?: 0L

        fun hasCommitted(engine: PlaybackEnginePath, trackId: String): Boolean = engine to trackId in committed

        fun volumeSteps(engine: PlaybackEnginePath): List<VolumeSample> =
            volumes.filter { it.first == engine }.map { it.second }

        private fun recordProgress(engine: PlaybackEnginePath, positionMs: Long) {
            progressByEngine[engine] = maxOf(progressByEngine[engine] ?: 0L, positionMs)
        }
    }

    private companion object {
        const val MaxFallbackDecodeSamples = 96_000L
    }
}
