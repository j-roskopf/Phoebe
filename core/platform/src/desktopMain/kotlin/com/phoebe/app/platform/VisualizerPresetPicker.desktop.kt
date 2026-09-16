package com.phoebe.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberPickVisualizerPresetFiles(
    onPicked: (List<PickedVisualizerPresetFile>) -> Unit,
): () -> Unit =
    remember(onPicked) {
        {
            SwingUtilities.invokeLater {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Add visualizer presets"
                    isMultiSelectionEnabled = true
                    fileSelectionMode = JFileChooser.FILES_ONLY
                    fileFilter = FileNameExtensionFilter(
                        "MilkDrop / Butterchurn presets (*.milk, *.json)",
                        "milk",
                        "json",
                    )
                }
                val ok = chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION
                if (!ok) {
                    onPicked(emptyList())
                    return@invokeLater
                }
                val files = chooser.selectedFiles
                    .orEmpty()
                    .filter { it.isFile }
                    .mapNotNull { file ->
                        val lower = file.name.lowercase()
                        if (!lower.endsWith(".milk") && !lower.endsWith(".json")) return@mapNotNull null
                        val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
                        PickedVisualizerPresetFile(fileName = file.name, text = text)
                    }
                onPicked(files)
            }
        }
    }
