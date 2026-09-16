package com.phoebe.app.platform

import androidx.compose.runtime.Composable

/**
 * Opens a platform file picker for MilkDrop (`.milk`) and Butterchurn (`.json`) presets.
 * Multiple selection when the platform supports it.
 */
@Composable
expect fun rememberPickVisualizerPresetFiles(
    onPicked: (List<PickedVisualizerPresetFile>) -> Unit,
): () -> Unit

data class PickedVisualizerPresetFile(
    val fileName: String,
    val text: String,
)
