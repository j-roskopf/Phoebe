package com.phoebe.app.feature.playback

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.phoebe.app.domain.NowPlayingVisualizerPreset

@Composable
internal expect fun FilamentVisualizerHost(
    preset: NowPlayingVisualizerPreset,
    isPlaying: Boolean,
    motionEnabled: Boolean,
    positionMs: Long,
    trackSeed: String,
    modifier: Modifier = Modifier,
    fallback: @Composable (Modifier) -> Unit,
)
