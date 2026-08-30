package com.phoebe.app.player

import com.phoebe.app.domain.AudioAnalysisSource
import com.phoebe.app.domain.Track
import com.phoebe.app.testing.assumeLinux
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Plays audio through [DesktopAudioPlayer] the same way the app does and asserts
 * the visualizer analysis feed updates many times a second. This is the Linux
 * regression that JavaFX/GStreamer spectrum cannot satisfy (~1–2 Hz).
 */
class LinuxVisualizerLiveAnalysisDesktopTest {
    @Test
    fun playingAudioPublishesVisualizerFramesFasterThanJavaFxSpectrum() {
        assumeLinux()

        val work = Files.createTempDirectory("phoebe-visualizer-live-").toFile()
        val wav = File(work, "pulse.wav")
        writePulsingSineWav(wav)
        val playbackFile = encodeMp3OrWav(wav)
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = DesktopAudioPlayer(diagnostics)
        try {
            player.setVolume(0.02f)
            player.play(listOf(playbackTrack(playbackFile)), 0)
            assertTrue(
                waitUntil { player.state.value.isPlaying },
                "playback did not start; engines=${diagnostics.engineEvents()} " +
                    "errors=${diagnostics.errorEvents()} source=${player.audioAnalysis.value.source}",
            )
            assertTrue(
                diagnostics.engineEvents().contains(PlaybackEnginePath.SampledStream),
                "Linux visualizer path must decode PCM, not JavaFX spectrum; engines=${diagnostics.engineEvents()}",
            )

            val frames = ArrayList<com.phoebe.app.domain.AudioAnalysisFrame>(256)
            val collectUntil = System.nanoTime() + CollectMs * 1_000_000L
            while (System.nanoTime() < collectUntil) {
                frames += player.audioAnalysis.value
                Thread.sleep(1L)
            }

            val live = frames.filter { frame ->
                frame.source != AudioAnalysisSource.None && frame.bands.isNotEmpty()
            }
            val uniqueTimestamps = live.map { it.timestampMs }.distinct()
            val peaks = live.map { frame -> frame.bands.maxOrNull() ?: frame.amplitude }
            val peakRange = (peaks.maxOrNull() ?: 0f) - (peaks.minOrNull() ?: 0f)
            val sources = live.map { it.source }.distinct()

            assertTrue(
                uniqueTimestamps.size >= MinDistinctFrames,
                "visualizer analysis only updated ${uniqueTimestamps.size} times in ${CollectMs}ms " +
                    "(need >= $MinDistinctFrames). engines=${diagnostics.engineEvents()} " +
                    "sources=$sources errors=${diagnostics.errorEvents()} " +
                    "last=${player.audioAnalysis.value}",
            )
            assertTrue(
                AudioAnalysisSource.Pcm in sources,
                "expected PCM analysis from the decoder, got $sources; engines=${diagnostics.engineEvents()}",
            )
            assertTrue(
                peakRange > 0.12f,
                "bands did not track the pulsing fixture (range=$peakRange); engines=${diagnostics.engineEvents()}",
            )
        } finally {
            player.releaseForTests()
            work.deleteRecursively()
        }
    }

    private fun playbackTrack(file: File): Track = Track(
        id = "linux-visualizer-live",
        title = file.name,
        artist = "Fixture",
        album = "Visualizer Live Analysis",
        durationMs = 3_000L,
        streamUrl = file.toURI().toString(),
        downloadUrl = "",
        localUri = file.toURI().toString(),
        filepath = file.absolutePath,
        audioCodec = file.extension,
    )

    private fun encodeMp3OrWav(wav: File): File {
        val ffmpeg = listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "ffmpeg")
            .firstOrNull { name ->
                val file = File(name)
                file.isFile && file.canExecute() || !name.startsWith("/") && File("/usr/bin/$name").canExecute()
            }
            ?.let { name -> if (name.startsWith("/")) name else "/usr/bin/$name" }
            ?: return wav
        val mp3 = File(wav.parentFile, "pulse.mp3")
        val process = ProcessBuilder(
            ffmpeg, "-y", "-i", wav.absolutePath, "-codec:a", "libmp3lame", "-q:a", "4", mp3.absolutePath,
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val finished = process.waitFor() == 0 && mp3.isFile && mp3.length() > 0L
        return if (finished) mp3 else wav
    }

    private fun waitUntil(timeoutMs: Long = 12_000L, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(50L)
        }
        return condition()
    }

    private class RecordingPlaybackDiagnostics : PlaybackDiagnostics {
        private val engines = Collections.synchronizedList(mutableListOf<PlaybackEnginePath>())
        private val errors = Collections.synchronizedList(mutableListOf<Pair<PlaybackEnginePath, String?>>())

        override fun engineSelected(engine: PlaybackEnginePath) {
            engines += engine
        }

        override fun playbackError(engine: PlaybackEnginePath, message: String?) {
            errors += engine to message
        }

        fun engineEvents(): List<PlaybackEnginePath> = engines.toList()

        fun errorEvents(): List<Pair<PlaybackEnginePath, String?>> = errors.toList()
    }

    companion object {
        private const val CollectMs = 1_000L
        private const val MinDistinctFrames = 20
    }
}

private fun writePulsingSineWav(file: File) {
    val sampleRate = 44_100
    val durationMs = 3_000
    val frames = sampleRate * durationMs / 1_000
    val data = ByteArray(frames * 2)
    val gateFrames = sampleRate / 10
    for (index in 0 until frames) {
        val gate = if ((index / gateFrames) % 2 == 0) 0.85 else 0.04
        val sample = (sin(2.0 * PI * 440.0 * index / sampleRate) * gate * 32767.0).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        data[index * 2] = (sample and 0xFF).toByte()
        data[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
    }
    val header = ByteArray(44)
    fun putAscii(offset: Int, text: String) {
        text.encodeToByteArray().copyInto(header, offset)
    }
    fun putInt(offset: Int, value: Int) {
        header[offset] = (value and 0xFF).toByte()
        header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        header[offset + 2] = ((value shr 16) and 0xFF).toByte()
        header[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
    fun putShort(offset: Int, value: Int) {
        header[offset] = (value and 0xFF).toByte()
        header[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
    putAscii(0, "RIFF")
    putInt(4, 36 + data.size)
    putAscii(8, "WAVE")
    putAscii(12, "fmt ")
    putInt(16, 16)
    putShort(20, 1)
    putShort(22, 1)
    putInt(24, sampleRate)
    putInt(28, sampleRate * 2)
    putShort(32, 2)
    putShort(34, 16)
    putAscii(36, "data")
    putInt(40, data.size)
    file.outputStream().use { output ->
        output.write(header)
        output.write(data)
    }
}
