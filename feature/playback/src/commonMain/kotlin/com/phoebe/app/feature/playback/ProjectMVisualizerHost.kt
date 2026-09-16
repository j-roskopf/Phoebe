package com.phoebe.app.feature.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Native projectM / Butterchurn surface host.
 * Desktop/Android/iOS: libprojectM GL surface.
 * wasmJs: Butterchurn WebGL2 canvas.
 */
@Composable
expect fun ProjectMVisualizerHost(
    presetPath: String?,
    presetData: String?,
    locked: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    /** When true, pause GL work (e.g. sheet drag) without tearing down the surface. */
    suspendRendering: Boolean = false,
)

@Composable
fun ProjectMVisualizerHostOrArtwork(
    showArtwork: Boolean,
    artwork: @Composable () -> Unit,
    presetPath: String?,
    presetData: String?,
    locked: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    suspendRendering: Boolean = false,
) {
    if (showArtwork) {
        Box(modifier.fillMaxSize()) { artwork() }
    } else {
        ProjectMVisualizerHost(
            presetPath = presetPath,
            presetData = presetData,
            locked = locked,
            isPlaying = isPlaying,
            modifier = modifier,
            suspendRendering = suspendRendering,
        )
    }
}
