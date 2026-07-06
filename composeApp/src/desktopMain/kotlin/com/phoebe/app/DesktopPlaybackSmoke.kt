package com.phoebe.app

import com.phoebe.app.domain.Track
import com.phoebe.app.player.DesktopAudioPlayer
import com.phoebe.app.player.PlaybackDiagnostics
import com.phoebe.app.player.PlaybackEnginePath
import java.io.File
import java.net.URI
import kotlin.system.exitProcess

private const val PlaybackSmokeArgPrefix = "--phoebe-playback-smoke="
private const val PlaybackSmokeUrlArgPrefix = "--phoebe-playback-smoke-url="
private const val PlaybackSmokeTimeoutArgPrefix = "--phoebe-playback-smoke-timeout-ms="
private const val DefaultPlaybackSmokeTimeoutMs = 15_000L

internal fun runDesktopPlaybackSmokeIfRequested(args: Array<String>): Boolean {
    val urlRaw = args.firstOrNull { it.startsWith(PlaybackSmokeUrlArgPrefix) }
        ?.removePrefix(PlaybackSmokeUrlArgPrefix)
    val fixtureRaw = args.firstOrNull { it.startsWith(PlaybackSmokeArgPrefix) }
        ?.removePrefix(PlaybackSmokeArgPrefix)
    if (urlRaw == null && fixtureRaw == null) return false
    val timeoutMs = args.firstOrNull { it.startsWith(PlaybackSmokeTimeoutArgPrefix) }
        ?.removePrefix(PlaybackSmokeTimeoutArgPrefix)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
        ?: DefaultPlaybackSmokeTimeoutMs
    val status = if (urlRaw != null) {
        runDesktopPlaybackSmokeUrl(urlRaw, timeoutMs)
    } else {
        runDesktopPlaybackSmoke(fixtureRaw.orEmpty(), timeoutMs)
    }
    exitProcess(status)
}

private fun runDesktopPlaybackSmokeUrl(
    url: String,
    timeoutMs: Long,
): Int {
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
        println("PHOEBE_PLAYBACK_SMOKE_FAILED reason=invalid-url url=${singleLine(url)} timeoutMs=$timeoutMs")
        return 2
    }
    return runDesktopPlaybackSmokeTrack(
        track = Track(
            id = "desktop-playback-smoke-url",
            title = url.substringAfterLast('/').ifBlank { "Remote stream" },
            artist = "Phoebe Smoke",
            album = "Desktop Playback Smoke",
            durationMs = 0L,
            streamUrl = url,
            downloadUrl = url,
            audioCodec = "",
        ),
        label = "url=${singleLine(url)}",
        timeoutMs = timeoutMs,
    )
}

private fun runDesktopPlaybackSmoke(
    fixtureRaw: String,
    timeoutMs: Long,
): Int {
    val fixture = playbackSmokeFile(fixtureRaw)
    if (fixture == null || !fixture.isFile) {
        println("PHOEBE_PLAYBACK_SMOKE_FAILED reason=missing-file file=${singleLine(fixtureRaw)} timeoutMs=$timeoutMs")
        return 2
    }

    val diagnostics = PlaybackSmokeDiagnostics()
    val player = DesktopAudioPlayer(diagnostics)
    val track = fixture.toSmokeTrack()
    return runDesktopPlaybackSmokeTrack(
        track = track,
        label = "file=${singleLine(fixture.absolutePath)}",
        timeoutMs = timeoutMs,
        diagnostics = diagnostics,
        player = player,
    )
}

private fun runDesktopPlaybackSmokeTrack(
    track: Track,
    label: String,
    timeoutMs: Long,
    diagnostics: PlaybackSmokeDiagnostics = PlaybackSmokeDiagnostics(),
    player: DesktopAudioPlayer = DesktopAudioPlayer(diagnostics),
): Int {
    try {
        diagnostics.markPlayRequested()
        player.play(listOf(track), 0)
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadlineNs) {
            val snapshot = diagnostics.snapshot()
            val firstAudioMs = snapshot.firstAudioMs
            if (firstAudioMs != null) {
                println(
                    "PHOEBE_PLAYBACK_SMOKE_OK firstAudioMs=$firstAudioMs " +
                        "engines=${snapshot.engines.asSmokeValue()} startup=${snapshot.startupEvents.asSmokeValue()} " +
                        "errors=${snapshot.errors.asSmokeValue()} " +
                        label,
                )
                return 0
            }
            Thread.sleep(100L)
        }

        val snapshot = diagnostics.snapshot()
        val state = player.state.value
        println(
            "PHOEBE_PLAYBACK_SMOKE_FAILED reason=timeout timeoutMs=$timeoutMs " +
                "engines=${snapshot.engines.asSmokeValue()} startup=${snapshot.startupEvents.asSmokeValue()} " +
                "errors=${snapshot.errors.asSmokeValue()} " +
                "buffering=${state.isBuffering} playing=${state.isPlaying} errorSerial=${state.playbackErrorSerial} " +
                label,
        )
        return 3
    } finally {
        player.releaseForTests()
    }
}

private fun playbackSmokeFile(raw: String): File? {
    if (raw.isBlank()) return null
    return runCatching {
        if (raw.startsWith("file:", ignoreCase = true)) {
            File(URI(raw))
        } else {
            File(raw)
        }.absoluteFile
    }.getOrNull()
}

private fun File.toSmokeTrack(): Track {
    val uri = toURI().toString()
    return Track(
        id = "desktop-playback-smoke",
        title = name,
        artist = "Phoebe Smoke",
        album = "Desktop Playback Smoke",
        durationMs = 60_000L,
        streamUrl = uri,
        downloadUrl = "",
        localUri = uri,
        filepath = absolutePath,
        audioCodec = extension,
    )
}

private data class PlaybackSmokeSnapshot(
    val engines: List<PlaybackEnginePath>,
    val firstAudioMs: Long?,
    val startupEvents: List<String>,
    val errors: List<String>,
)

private class PlaybackSmokeDiagnostics : PlaybackDiagnostics {
    private val lock = Any()
    private var playRequestedAtNs = System.nanoTime()
    private val engines = mutableListOf<PlaybackEnginePath>()
    private var firstAudioMs: Long? = null
    private val startupEvents = mutableListOf<String>()
    private val errors = mutableListOf<String>()

    fun markPlayRequested() {
        synchronized(lock) {
            playRequestedAtNs = System.nanoTime()
            engines.clear()
            firstAudioMs = null
            startupEvents.clear()
            errors.clear()
        }
    }

    override fun engineSelected(engine: PlaybackEnginePath) {
        synchronized(lock) {
            if (engine !in engines) engines += engine
        }
    }

    override fun playbackStartupEvent(engine: PlaybackEnginePath, event: String) {
        engineSelected(engine)
        synchronized(lock) {
            startupEvents += "${elapsedMs()}ms:${engine.name}:${singleLine(event)}"
        }
    }

    override fun platformPlaying(
        engine: PlaybackEnginePath,
        positionMs: Long,
        durationMs: Long,
    ) {
        engineSelected(engine)
        recordFirstAudio()
    }

    override fun decodedAudioEnergy(
        engine: PlaybackEnginePath,
        rms: Double,
    ) {
        if (rms <= 0.000001 || !rms.isFinite()) return
        engineSelected(engine)
        recordFirstAudio()
    }

    override fun playbackError(
        engine: PlaybackEnginePath,
        message: String?,
    ) {
        engineSelected(engine)
        synchronized(lock) {
            errors += "${engine.name}:${message ?: "unknown"}"
        }
    }

    fun snapshot(): PlaybackSmokeSnapshot = synchronized(lock) {
        PlaybackSmokeSnapshot(
            engines = engines.toList(),
            firstAudioMs = firstAudioMs,
            startupEvents = startupEvents.toList(),
            errors = errors.toList(),
        )
    }

    private fun recordFirstAudio() {
        synchronized(lock) {
            if (firstAudioMs == null) {
                firstAudioMs = ((System.nanoTime() - playRequestedAtNs) / 1_000_000L).coerceAtLeast(0L)
            }
        }
    }

    private fun elapsedMs(): Long =
        ((System.nanoTime() - playRequestedAtNs) / 1_000_000L).coerceAtLeast(0L)
}

private fun List<Any>.asSmokeValue(): String =
    takeIf { it.isNotEmpty() }
        ?.joinToString(",") { singleLine(it.toString()) }
        ?: "none"

private fun singleLine(value: String): String =
    value.replace(Regex("\\s+"), "_")
