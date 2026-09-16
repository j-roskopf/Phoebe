package com.phoebe.app.feature.playback

import androidx.compose.runtime.staticCompositionLocalOf
import com.phoebe.app.domain.AudioAnalysisFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Composition-local amplitude frames for non-visualizer chrome.
 * Visualizers consume [com.phoebe.app.player.VisualizerPcmBus], not this flow.
 */
val LocalVisualizerAudioAnalysis = staticCompositionLocalOf<StateFlow<AudioAnalysisFrame>> {
    MutableStateFlow(AudioAnalysisFrame.Empty)
}
