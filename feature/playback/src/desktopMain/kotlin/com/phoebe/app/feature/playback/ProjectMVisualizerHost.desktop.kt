package com.phoebe.app.feature.playback

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.phoebe.app.platform.PhoebeLog
import java.io.File

@Composable
actual fun ProjectMVisualizerHost(
    presetPath: String?,
    presetData: String?,
    locked: Boolean,
    isPlaying: Boolean,
    modifier: Modifier,
    suspendRendering: Boolean,
) {
    val libraryDir = remember { resolveProjectMLibraryDir() }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val panel = remember(libraryDir) {
        ProjectMGlPanel(libraryDir) { bitmap ->
            frame = bitmap
        }.also { it.start() }
    }

    DisposableEffect(panel) {
        onDispose { panel.disposeNative() }
    }

    DisposableEffect(presetPath, presetData, locked, isPlaying, suspendRendering) {
        when {
            !presetPath.isNullOrBlank() -> panel.setPresetFile(presetPath)
            !presetData.isNullOrBlank() -> panel.setPresetData(presetData)
            else -> panel.setPresetFile("idle://")
        }
        panel.setLocked(locked)
        panel.setPlaying(isPlaying && !suspendRendering)
        if (suspendRendering) panel.stop() else panel.start()
        onDispose { }
    }

    // Interop blending cuts the SwingPanel rect out of the Skia layer, so a full-size
    // SwingPanel would also erase any Compose Image drawn in the same slot. Keep a 1.dp
    // context host and present projectM via FBO → ImageBitmap instead.
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size ->
                panel.setTargetPixelSize(size.width, size.height)
            },
    ) {
        SwingPanel(
            modifier = Modifier
                .size(1.dp)
                .align(Alignment.TopStart),
            factory = {
                panel.background = java.awt.Color.BLACK
                panel
            },
        )
        val current = frame
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Low,
            )
        }
    }
}

fun resolveProjectMLibraryDir(): File {
    val override = System.getProperty("phoebe.projectm.libraryDir")
        ?: System.getenv("PHOEBE_PROJECTM_LIBRARY_DIR")
    if (!override.isNullOrBlank()) {
        return File(override)
    }
    val arch = when (System.getProperty("os.arch")) {
        "aarch64", "arm64" -> "arm64"
        else -> "x64"
    }
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val target = when {
        os.contains("mac") -> "macos-$arch"
        os.contains("win") -> "windows-x64"
        else -> "linux-$arch"
    }
    val candidates = buildList {
        // Packaged apps bundle the libraries flat in the jpackage resources dir.
        System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let { add(File(it)) }
        add(File("native/projectm/$target/lib"))
        add(File("../native/projectm/$target/lib"))
        add(File(System.getProperty("user.dir"), "native/projectm/$target/lib"))
    }
    val found = candidates.firstOrNull { dir ->
        dir.isDirectory && dir.listFiles()?.any { it.name.contains("PhoebeProjectM") } == true
    }
    if (found != null) {
        PhoebeLog.d("ProjectM") { "Using projectM libraries from ${found.absolutePath}" }
    }
    return found
        ?: error("projectM native libraries not found for $target. Run scripts/build-projectm.sh")
}
