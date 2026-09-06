package com.phoebe.app

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.phoebe.app.domain.Track
import com.phoebe.app.player.AndroidAudioPlayer
import com.phoebe.app.player.PlaybackDiagnostics
import com.phoebe.app.player.PlaybackEnginePath
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AndroidPlaybackSmokeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: AndroidAudioPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidContextHolder.application = application

        val path = intent.getStringExtra(ExtraPath).orEmpty()
        val timeoutMs = intent.getLongExtra(ExtraTimeoutMs, DefaultTimeoutMs).takeIf { it > 0L } ?: DefaultTimeoutMs
        val mode = intent.getStringExtra(ExtraMode).orEmpty().ifBlank { ModePlayback }
        val file = runCatching { resolveSmokeFile(path) }
            .getOrElse { error ->
                logSmoke(
                    "PHOEBE_PLAYBACK_SMOKE_FAILED reason=fixture-error " +
                        "message=${(error.message ?: error::class.simpleName.orEmpty()).asSmokeValue()} timeoutMs=$timeoutMs",
                )
                finishAndRemoveTask()
                return
            }
        if (!file.isFile) {
            logSmoke("PHOEBE_PLAYBACK_SMOKE_FAILED reason=missing-file file=${path.asSmokeValue()} timeoutMs=$timeoutMs")
            finishAndRemoveTask()
            return
        }

        val diagnostics = AndroidSmokeDiagnostics()
        val smokePlayer = AndroidAudioPlayer(diagnostics)
        player = smokePlayer
        scope.launch {
            try {
                when (mode) {
                    ModeShuffleLastTrack -> runShuffleLastTrackSmoke(smokePlayer, diagnostics, file, timeoutMs)
                    else -> runPlaybackSmoke(smokePlayer, diagnostics, file, timeoutMs)
                }
            } finally {
                smokePlayer.releaseForTests()
                finishAndRemoveTask()
            }
        }
    }

    private suspend fun runPlaybackSmoke(
        smokePlayer: AndroidAudioPlayer,
        diagnostics: AndroidSmokeDiagnostics,
        file: File,
        timeoutMs: Long,
    ) {
        val track = file.toSmokeTrack(id = "android-playback-smoke")
        diagnostics.markPlayRequested()
        smokePlayer.play(listOf(track), 0)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() <= deadline) {
            val snapshot = diagnostics.snapshot()
            val firstAudioMs = snapshot.firstAudioMs
            if (firstAudioMs != null) {
                logSmoke(
                    "PHOEBE_PLAYBACK_SMOKE_OK firstAudioMs=$firstAudioMs " +
                        "engines=${snapshot.engines.asSmokeValue()} errors=${snapshot.errors.asSmokeValue()} " +
                        "file=${file.absolutePath.asSmokeValue()}",
                )
                return
            }
            delay(100L)
        }

        val snapshot = diagnostics.snapshot()
        val state = smokePlayer.state.value
        logSmoke(
            "PHOEBE_PLAYBACK_SMOKE_FAILED reason=timeout timeoutMs=$timeoutMs " +
                "engines=${snapshot.engines.asSmokeValue()} errors=${snapshot.errors.asSmokeValue()} " +
                "buffering=${state.isBuffering} playing=${state.isPlaying} errorSerial=${state.playbackErrorSerial} " +
                "file=${file.absolutePath.asSmokeValue()}",
        )
    }

    private suspend fun runShuffleLastTrackSmoke(
        smokePlayer: AndroidAudioPlayer,
        diagnostics: AndroidSmokeDiagnostics,
        file: File,
        timeoutMs: Long,
    ) {
        val tracks = (1..5).map { index ->
            file.toSmokeTrack(id = "android-shuffle-smoke-$index", title = "Smoke $index")
        }
        diagnostics.markPlayRequested()
        smokePlayer.play(tracks, tracks.lastIndex)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() <= deadline) {
            val ready = diagnostics.snapshot().firstAudioMs != null &&
                smokePlayer.state.value.currentTrack?.id == tracks.last().id
            if (ready) break
            delay(50L)
        }
        val before = smokePlayer.state.value
        if (diagnostics.snapshot().firstAudioMs == null) {
            logSmoke(
                "PHOEBE_PLAYBACK_SMOKE_FAILED reason=shuffle-last-track-no-audio " +
                    "current=${before.currentTrack?.id.orEmpty().asSmokeValue()} timeoutMs=$timeoutMs",
            )
            return
        }
        if (before.currentTrack?.id != tracks.last().id) {
            logSmoke(
                "PHOEBE_PLAYBACK_SMOKE_FAILED reason=shuffle-last-track-not-current " +
                    "current=${before.currentTrack?.id.orEmpty().asSmokeValue()} timeoutMs=$timeoutMs",
            )
            return
        }
        if (before.upNext.isNotEmpty()) {
            logSmoke(
                "PHOEBE_PLAYBACK_SMOKE_FAILED reason=shuffle-last-track-unexpected-up-next " +
                    "upNext=${before.upNext.size} timeoutMs=$timeoutMs",
            )
            return
        }

        smokePlayer.setShuffle(true)
        val expectedIds = tracks.dropLast(1).map { it.id }.toSet()
        var after = smokePlayer.state.value
        val shuffleDeadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() <= shuffleDeadline) {
            after = smokePlayer.state.value
            val reshuffled = after.shuffle &&
                after.currentTrack?.id == tracks.last().id &&
                after.currentIndex == 0 &&
                after.upNext.size == expectedIds.size &&
                after.upNext.map { it.id }.toSet() == expectedIds
            if (reshuffled) break
            delay(50L)
        }
        val reshuffled = after.shuffle &&
            after.currentTrack?.id == tracks.last().id &&
            after.currentIndex == 0 &&
            after.upNext.size == expectedIds.size &&
            after.upNext.map { it.id }.toSet() == expectedIds
        if (!reshuffled) {
            logSmoke(
                "PHOEBE_PLAYBACK_SMOKE_FAILED reason=shuffle-last-track-order " +
                    "shuffle=${after.shuffle} current=${after.currentTrack?.id.orEmpty().asSmokeValue()} " +
                    "currentIndex=${after.currentIndex} upNext=${after.upNext.map { it.id }.joinToString(",").asSmokeValue()} " +
                    "timeoutMs=$timeoutMs",
            )
            return
        }

        // Advance into the reshuffled Up Next so we exercise the Media3 queue rebase,
        // not only the synchronous PlayerState update from setShuffle.
        val upNextBeforeSkip = after.upNext.map { it.id }
        smokePlayer.next()
        var advanced = smokePlayer.state.value
        val advanceDeadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() <= advanceDeadline) {
            advanced = smokePlayer.state.value
            if (advanced.currentTrack?.id in expectedIds) break
            delay(50L)
        }
        if (advanced.currentTrack?.id !in expectedIds) {
            logSmoke(
                "PHOEBE_PLAYBACK_SMOKE_FAILED reason=shuffle-last-track-next-failed " +
                    "current=${advanced.currentTrack?.id.orEmpty().asSmokeValue()} " +
                    "expectedUpNext=${upNextBeforeSkip.joinToString(",").asSmokeValue()} timeoutMs=$timeoutMs",
            )
            return
        }

        logSmoke(
            "PHOEBE_PLAYBACK_SMOKE_OK mode=shuffle-last-track " +
                "current=${after.currentTrack?.id.orEmpty().asSmokeValue()} upNext=${after.upNext.size} " +
                "advanced=${advanced.currentTrack?.id.orEmpty().asSmokeValue()} " +
                "file=${file.absolutePath.asSmokeValue()}",
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun File.toSmokeTrack(
        id: String,
        title: String = name,
    ): Track {
        val uri = toURI().toString()
        return Track(
            id = id,
            title = title,
            artist = "Phoebe Smoke",
            album = "Android Playback Smoke",
            durationMs = 60_000L,
            streamUrl = uri,
            downloadUrl = "",
            localUri = uri,
            filepath = absolutePath,
            audioCodec = extension,
        )
    }

    private fun logSmoke(message: String) {
        Log.i(LogTag, message)
    }

    private fun resolveSmokeFile(path: String): File {
        if (path.isNotBlank()) return File(path)
        return File(cacheDir, "phoebe-playback-smoke.wav").also { file ->
            file.parentFile?.mkdirs()
            file.writeSmokeWavFixture()
        }
    }

    private companion object {
        const val ExtraPath = "phoebe.playbackSmoke.path"
        const val ExtraTimeoutMs = "phoebe.playbackSmoke.timeoutMs"
        const val ExtraMode = "phoebe.playbackSmoke.mode"
        const val ModePlayback = "playback"
        const val ModeShuffleLastTrack = "shuffle-last-track"
        const val DefaultTimeoutMs = 30_000L
        const val LogTag = "PhoebePlaybackSmoke"
    }
}

private data class AndroidSmokeSnapshot(
    val engines: List<PlaybackEnginePath>,
    val firstAudioMs: Long?,
    val errors: List<String>,
)

private class AndroidSmokeDiagnostics : PlaybackDiagnostics {
    private val lock = Any()
    private var playRequestedAtMs = SystemClock.elapsedRealtime()
    private val engines = mutableListOf<PlaybackEnginePath>()
    private var firstAudioMs: Long? = null
    private val errors = mutableListOf<String>()

    fun markPlayRequested() {
        synchronized(lock) {
            playRequestedAtMs = SystemClock.elapsedRealtime()
            engines.clear()
            firstAudioMs = null
            errors.clear()
        }
    }

    override fun engineSelected(engine: PlaybackEnginePath) {
        synchronized(lock) {
            if (engine !in engines) engines += engine
        }
    }

    override fun platformPlaying(engine: PlaybackEnginePath, positionMs: Long, durationMs: Long) {
        engineSelected(engine)
        recordFirstAudio()
    }

    override fun decodedAudioEnergy(engine: PlaybackEnginePath, rms: Double) {
        if (rms <= 0.000001 || !rms.isFinite()) return
        engineSelected(engine)
        recordFirstAudio()
    }

    override fun playbackError(engine: PlaybackEnginePath, message: String?) {
        engineSelected(engine)
        synchronized(lock) {
            errors += "${engine.name}:${message ?: "unknown"}"
        }
    }

    fun snapshot(): AndroidSmokeSnapshot = synchronized(lock) {
        AndroidSmokeSnapshot(
            engines = engines.toList(),
            firstAudioMs = firstAudioMs,
            errors = errors.toList(),
        )
    }

    private fun recordFirstAudio() {
        synchronized(lock) {
            if (firstAudioMs == null) {
                firstAudioMs = (SystemClock.elapsedRealtime() - playRequestedAtMs).coerceAtLeast(0L)
            }
        }
    }
}

private fun List<Any>.asSmokeValue(): String =
    takeIf { it.isNotEmpty() }
        ?.joinToString(",") { it.toString().asSmokeValue() }
        ?: "none"

private fun String.asSmokeValue(): String =
    replace(Regex("\\s+"), "_")

private fun File.writeSmokeWavFixture() {
    val sampleRate = 44_100
    val seconds = 1
    val sampleCount = sampleRate * seconds
    val dataBytes = sampleCount * 2

    outputStream().use { output ->
        fun ascii(value: String) {
            output.write(value.toByteArray(Charsets.US_ASCII))
        }

        fun intLe(value: Int) {
            output.write(value and 0xff)
            output.write(value shr 8 and 0xff)
            output.write(value shr 16 and 0xff)
            output.write(value shr 24 and 0xff)
        }

        fun shortLe(value: Int) {
            output.write(value and 0xff)
            output.write(value shr 8 and 0xff)
        }

        ascii("RIFF")
        intLe(36 + dataBytes)
        ascii("WAVE")
        ascii("fmt ")
        intLe(16)
        shortLe(1)
        shortLe(1)
        intLe(sampleRate)
        intLe(sampleRate * 2)
        shortLe(2)
        shortLe(16)
        ascii("data")
        intLe(dataBytes)

        repeat(sampleCount) { index ->
            val envelope = minOf(1.0, index / 400.0) * minOf(1.0, (sampleCount - index) / 400.0)
            val sample = (sin(2.0 * PI * 440.0 * index / sampleRate) * 0.28 * envelope * Short.MAX_VALUE).toInt()
            shortLe(sample)
        }
    }
}
