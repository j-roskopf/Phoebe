package com.phoebe.app.testing

import com.phoebe.app.player.PlaybackDiagnostics
import com.phoebe.app.player.PlaybackEnginePath
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal data class PlaybackStartupSnapshot(
    val label: String? = null,
    val engines: List<PlaybackEnginePath> = emptyList(),
    val firstPlatformPlayingMs: Long? = null,
    val firstDecodedEnergyMs: Long? = null,
    val startupEvents: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
) {
    val firstAudioMs: Long?
        get() = firstDecodedEnergyMs ?: firstPlatformPlayingMs
}

internal class PlaybackStartupProbe : PlaybackDiagnostics {
    private val timeSource = TimeSource.Monotonic
    private var playRequestedAt = timeSource.markNow()
    private val mutableSnapshot = MutableStateFlow(PlaybackStartupSnapshot())

    val snapshot: StateFlow<PlaybackStartupSnapshot> = mutableSnapshot

    fun markPlayRequested(label: String? = null) {
        playRequestedAt = timeSource.markNow()
        mutableSnapshot.value = PlaybackStartupSnapshot(label = label)
    }

    override fun engineSelected(engine: PlaybackEnginePath) {
        mutableSnapshot.update { current ->
            if (engine in current.engines) current else current.copy(engines = current.engines + engine)
        }
    }

    override fun playbackStartupEvent(engine: PlaybackEnginePath, event: String) {
        engineSelected(engine)
        mutableSnapshot.update { current ->
            current.copy(startupEvents = current.startupEvents + "${elapsedMs()}ms:${engine.name}:$event")
        }
    }

    override fun platformPlaying(engine: PlaybackEnginePath, positionMs: Long, durationMs: Long) {
        engineSelected(engine)
        mutableSnapshot.update { current ->
            current.copy(firstPlatformPlayingMs = current.firstPlatformPlayingMs ?: elapsedMs())
        }
    }

    override fun decodedAudioEnergy(engine: PlaybackEnginePath, rms: Double) {
        if (rms <= 0.000001 || !rms.isFinite()) return
        engineSelected(engine)
        mutableSnapshot.update { current ->
            current.copy(firstDecodedEnergyMs = current.firstDecodedEnergyMs ?: elapsedMs())
        }
    }

    override fun playbackError(engine: PlaybackEnginePath, message: String?) {
        engineSelected(engine)
        mutableSnapshot.update { current ->
            current.copy(errors = current.errors + "${engine.name}: ${message ?: "unknown playback error"}")
        }
    }

    private fun elapsedMs(): Long = playRequestedAt.elapsedNow().inWholeMilliseconds.coerceAtLeast(0L)
}

internal object PlaybackStartupThresholds {
    const val WebMs: Long = 5_000L
    const val DesktopMs: Long = 8_000L
    const val AndroidMs: Long = 12_000L

    fun fromBaseline(samplesMs: List<Long>, minimumMs: Long, hardCapMs: Long): Long {
        val sorted = samplesMs.filter { it >= 0L }.sorted()
        if (sorted.isEmpty()) return hardCapMs
        val p95Index = (kotlin.math.ceil(sorted.size * 0.95).toInt() - 1).coerceIn(sorted.indices)
        return (sorted[p95Index] * 2L).coerceAtLeast(minimumMs).coerceAtMost(hardCapMs)
    }
}
