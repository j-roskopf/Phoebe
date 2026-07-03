package com.phoebe.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

private const val MacDirectoryDialogProperty = "apple.awt.fileDialogForDirectories"

@Composable
fun rememberPickDesktopDirectory(
    title: String,
    initialDirectory: File?,
    onPicked: (String?) -> Unit,
): () -> Unit =
    remember(title, initialDirectory?.absolutePath, onPicked) {
        {
            SwingUtilities.invokeLater {
                val selected = if (isMacOs()) {
                    try {
                        pickMacDirectory(title, initialDirectory)
                    } catch (_: RuntimeException) {
                        pickSwingDirectory(title, initialDirectory)
                    }
                } else {
                    pickSwingDirectory(title, initialDirectory)
                }
                onPicked(selected?.toURI()?.toString())
            }
        }
    }

private fun pickMacDirectory(title: String, initialDirectory: File?): File? {
    val previous = System.getProperty(MacDirectoryDialogProperty)
    var dialog: FileDialog? = null
    System.setProperty(MacDirectoryDialogProperty, "true")
    return try {
        dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
            normalizedInitialDirectory(initialDirectory)?.let { directory = it.absolutePath }
            isVisible = true
        }
        val selectedName = dialog.file ?: return null
        val selectedDirectory = dialog.directory ?: return null
        File(selectedDirectory, selectedName).takeIf { it.isDirectory }
    } finally {
        dialog?.dispose()
        restoreSystemProperty(MacDirectoryDialogProperty, previous)
    }
}

private fun pickSwingDirectory(title: String, initialDirectory: File?): File? {
    val chooser = JFileChooser(normalizedInitialDirectory(initialDirectory)).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = title
        isAcceptAllFileFilterUsed = false
    }
    val ok = chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION
    return chooser.selectedFile.takeIf { ok && it != null && it.isDirectory }
}

private fun normalizedInitialDirectory(file: File?): File? =
    when {
        file == null -> null
        file.isDirectory -> file
        else -> file.parentFile?.takeIf { it.isDirectory }
    }

private fun restoreSystemProperty(key: String, previous: String?) {
    if (previous == null) {
        System.clearProperty(key)
    } else {
        System.setProperty(key, previous)
    }
}

private fun isMacOs(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
