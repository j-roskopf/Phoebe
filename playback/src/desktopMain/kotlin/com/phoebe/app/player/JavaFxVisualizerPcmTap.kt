package com.phoebe.app.player

import com.phoebe.app.platform.PhoebeLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live PCM for the visualizer on Linux. JavaFX/GStreamer spectrum only updates a
 * couple of times a second; Mac AVFoundation is already fast enough that we leave
 * it on the spectrum listener. Prefer in-process Pulse (`pa_simple`) of the
 * default sink monitor; parecord/ffmpeg are fallbacks with a cleaned environment.
 */
internal class JavaFxVisualizerPcmTap(
    private val ffmpeg: String?,
    private val publishPcm: (FloatArray, Float) -> Unit,
    private val onReady: () -> Unit,
) {
    private var cancelled = AtomicBoolean(true)
    private var process: Process? = null
    private var worker: Thread? = null
    @Volatile private var readySignaled = false

    val isReceiving: Boolean
        get() = readySignaled

    fun start() {
        val session = AtomicBoolean(false)
        cancelled.set(true)
        runCatching { process?.destroyForcibly() }
        process = null
        worker?.join(500L)
        cancelled = session
        readySignaled = false
        worker = Thread(
            {
                if (readPulseSimple(session)) return@Thread
                decodeLinuxMonitor(session)
            },
            "Phoebe-javafx-visualizer-pcm",
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun restart() {
        // Monitor capture follows the speakers; seeks do not need a restart.
    }

    fun stop() {
        cancelled.set(true)
        runCatching { process?.destroyForcibly() }
        process = null
        worker?.join(500L)
        worker = null
    }

    private fun readPulseSimple(session: AtomicBoolean): Boolean {
        val devices = buildList {
            val named = linuxPulseMonitorSource()
            add(named)
            if (named != DefaultMonitor) add(DefaultMonitor)
        }.distinct()
        for (device in devices) {
            if (session.get()) return false
            val connection = LinuxPulseSimple.openRecord(
                device = device,
                sampleRateHz = SampleRateHz,
                channels = 1,
                fragmentBytes = ChunkBytes,
            ) ?: continue
            try {
                if (pumpPcm(session, "pulse-simple $device") { connection.read(it) }) {
                    return true
                }
            } finally {
                connection.close()
            }
        }
        return false
    }

    private fun decodeLinuxMonitor(session: AtomicBoolean) {
        val source = linuxPulseMonitorSource()
        val attempts = buildList {
            executable(ParecordNames, ParecordFallbackPaths)?.let { parecord ->
                add(CaptureAttempt(parecordCommand(parecord, source), "parecord $source"))
            }
            val ffmpegPath = ffmpeg
                ?: executable(listOf("ffmpeg"), FfmpegFallbackPaths)
            if (ffmpegPath != null) {
                add(CaptureAttempt(ffmpegPulseCommand(ffmpegPath, source), "ffmpeg pulse $source"))
            }
        }
        if (attempts.isEmpty()) {
            PhoebeLog.d("JavaFxVisualizerPcmTap") {
                "no Pulse simple/parecord/ffmpeg; Linux visualizer stays on JavaFX spectrum"
            }
            return
        }
        for (attempt in attempts) {
            if (session.get()) return
            if (decodeProcess(session, attempt)) return
        }
        PhoebeLog.d("JavaFxVisualizerPcmTap") {
            "Pulse monitor capture failed; Linux visualizer stays on JavaFX spectrum"
        }
    }

    private fun decodeProcess(session: AtomicBoolean, attempt: CaptureAttempt): Boolean {
        val started = runCatching { startHostProcess(attempt.command) }.onFailure { error ->
            PhoebeLog.d("JavaFxVisualizerPcmTap") {
                "failed to start ${attempt.label}: ${error.message}"
            }
        }.getOrNull() ?: return false
        process = started
        drainStderr(started, attempt.label)
        try {
            return pumpPcm(session, attempt.label) { bytes ->
                val read = started.inputStream.read(bytes)
                if (read <= 0) false else {
                    if (read < bytes.size) bytes.fill(0, read, bytes.size)
                    true
                }
            }
        } finally {
            runCatching { started.destroyForcibly() }
            if (process === started) process = null
        }
    }

    private fun pumpPcm(
        session: AtomicBoolean,
        label: String,
        readChunk: (ByteArray) -> Boolean,
    ): Boolean {
        val bytes = ByteArray(ChunkBytes)
        val samples = FloatArray(ChunkSamples)
        var gotPcm = false
        try {
            while (!session.get()) {
                if (!readChunk(bytes)) break
                var byteIndex = 0
                for (index in 0 until ChunkSamples) {
                    val lo = bytes[byteIndex].toInt() and 0xFF
                    val hi = bytes[byteIndex + 1].toInt()
                    samples[index] = ((hi shl 8) or lo).toShort() / 32768f
                    byteIndex += 2
                }
                if (!readySignaled) {
                    readySignaled = true
                    gotPcm = true
                    PhoebeLog.d("JavaFxVisualizerPcmTap") { "visualizer PCM from $label" }
                    onReady()
                }
                publishPcm(samples.copyOf(), SampleRateHz.toFloat())
            }
        } catch (error: Throwable) {
            PhoebeLog.d("JavaFxVisualizerPcmTap") {
                "$label read failed: ${error.message}"
            }
        }
        return gotPcm
    }

    private data class CaptureAttempt(
        val command: List<String>,
        val label: String,
    )

    companion object {
        internal const val SampleRateHz = 22_050
        private const val ChunkSamples = 256
        private const val ChunkBytes = ChunkSamples * 2
        internal const val DefaultMonitor = "@DEFAULT_MONITOR@"
        private val ParecordNames = listOf("parecord")
        private val ParecordFallbackPaths = listOf("/usr/bin/parecord", "/usr/local/bin/parecord")
        private val PactlNames = listOf("pactl")
        private val PactlFallbackPaths = listOf("/usr/bin/pactl", "/usr/local/bin/pactl")
        private val FfmpegFallbackPaths = listOf(
            "/usr/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
            "/opt/homebrew/bin/ffmpeg",
        )

        internal fun isLinuxOs(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("linux")

        internal fun linuxPulseMonitorSource(): String {
            val pactl = executable(PactlNames, PactlFallbackPaths) ?: return DefaultMonitor
            val sink = runHostCommand(pactl, "get-default-sink")
            if (!sink.isNullOrBlank()) {
                return if (sink.endsWith(".monitor")) sink else "$sink.monitor"
            }
            val sources = runHostCommand(pactl, "list", "short", "sources").orEmpty()
            val monitor = sources.lineSequence()
                .map { line -> line.split('\t', ' ').firstOrNull { it.endsWith(".monitor") } }
                .firstOrNull { !it.isNullOrBlank() }
            return monitor ?: DefaultMonitor
        }

        internal fun parecordCommand(parecord: String, source: String): List<String> = listOf(
            parecord,
            "--raw",
            "--record",
            "--rate=$SampleRateHz",
            "--channels=1",
            "--format=s16le",
            "--latency-msec=20",
            "-d",
            source,
        )

        internal fun ffmpegPulseCommand(ffmpeg: String, source: String): List<String> = listOf(
            ffmpeg,
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-fflags",
            "nobuffer",
            "-flags",
            "low_delay",
            "-f",
            "pulse",
            "-fragment_size",
            "512",
            "-i",
            source,
            "-vn",
            "-ac",
            "1",
            "-ar",
            SampleRateHz.toString(),
            "-f",
            "s16le",
            "-acodec",
            "pcm_s16le",
            "-flush_packets",
            "1",
            "pipe:1",
        )

        internal fun executable(names: List<String>, extraPaths: List<String>): String? {
            val pathCandidates = System.getenv("PATH").orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .flatMap { directory -> names.map { name -> File(directory, name) } }
            val extras = extraPaths.map(::File)
            return (extras + pathCandidates)
                .firstOrNull { it.isFile && it.canExecute() }
                ?.absolutePath
        }

        internal fun startHostProcess(
            command: List<String>,
            discardError: Boolean = false,
        ): Process {
            val builder = ProcessBuilder(command)
            builder.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            if (discardError) {
                builder.redirectError(ProcessBuilder.Redirect.DISCARD)
            }
            builder.environment().remove("LD_LIBRARY_PATH")
            builder.environment().remove("LD_PRELOAD")
            return builder.start()
        }

        private fun runHostCommand(vararg command: String): String? = runCatching {
            val process = startHostProcess(command.toList())
            val text = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            text.takeIf { it.isNotBlank() }
        }.getOrNull()

        private fun drainStderr(process: Process, label: String) {
            Thread(
                {
                    val text = runCatching { process.errorStream.bufferedReader().readText() }.getOrNull()
                    if (!text.isNullOrBlank()) {
                        PhoebeLog.d("JavaFxVisualizerPcmTap") { "$label stderr: ${text.take(400)}" }
                    }
                },
                "Phoebe-javafx-visualizer-pcm-err",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }
}
