package com.phoebe.app.feature.playback

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.phoebe.app.domain.NowPlayingVisualizerPreset

@Composable
internal actual fun FilamentVisualizerHost(
    preset: NowPlayingVisualizerPreset,
    renderState: AudioVisualizerRenderState,
    isPlaying: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier,
    fallback: @Composable (Modifier) -> Unit,
) {
    fallback(modifier)
}
