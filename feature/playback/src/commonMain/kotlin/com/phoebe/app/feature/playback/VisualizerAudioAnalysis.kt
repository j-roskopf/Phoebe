package com.phoebe.app.feature.playback

import androidx.compose.runtime.staticCompositionLocalOf
import com.phoebe.app.domain.AudioAnalysisFrame
import com.phoebe.app.domain.AudioAnalysisSource
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.player.AudioAnalysisAccumulator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal const val VisualizerFrameStaleMs = 2_000L

val LocalVisualizerAudioAnalysis = staticCompositionLocalOf<StateFlow<AudioAnalysisFrame>> {
    MutableStateFlow(AudioAnalysisFrame.Empty)
}

internal fun resolvedVisualizerFrame(
    analysis: AudioAnalysisFrame,
    trackSeed: String,
    isPlaying: Boolean,
    positionMs: Long,
    nowMs: Long = currentTimeMs(),
): AudioAnalysisFrame {
    val freshRealFrame = analysis.source != AudioAnalysisSource.None &&
        analysis.bands.isNotEmpty() &&
        nowMs - analysis.timestampMs <= VisualizerFrameStaleMs
    return if (freshRealFrame) {
        analysis.normalized()
    } else {
        AudioAnalysisAccumulator.fallbackFrame(
            seed = trackSeed,
            positionMs = nowMs,
            isPlaying = isPlaying,
            timestampMs = nowMs,
        )
    }
}
