package com.phoebe.app.feature.playback

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.logDetail
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
    val libraryDir = remember { resolveProjectMLibraryDirOrNull() }
    val nativeReady = remember(libraryDir) {
        libraryDir != null && ProjectMNative.tryEnsureLoaded(libraryDir.absolutePath)
    }
    if (!nativeReady || libraryDir == null) {
        PhoebeLog.d("ProjectM") {
            "desktop projectM unavailable " +
                "(dir=$libraryDir, failure=${ProjectMNative.loadFailure}) — artwork fallback"
        }
        LaunchedEffect(Unit) { ProjectMHostGate.markFailed() }
        Box(modifier.fillMaxSize().background(Color.Black))
        return
    }

    if (isLinuxDesktop()) {
        LinuxOffscreenProjectMHost(
            libraryDir = libraryDir,
            presetPath = presetPath,
            presetData = presetData,
            locked = locked,
            isPlaying = isPlaying,
            modifier = modifier,
            suspendRendering = suspendRendering,
        )
        return
    }

    SwingProjectMHost(
        libraryDir = libraryDir,
        presetPath = presetPath,
        presetData = presetData,
        locked = locked,
        isPlaying = isPlaying,
        modifier = modifier,
        suspendRendering = suspendRendering,
    )
}

@Composable
private fun LinuxOffscreenProjectMHost(
    libraryDir: File,
    presetPath: String?,
    presetData: String?,
    locked: Boolean,
    isPlaying: Boolean,
    modifier: Modifier,
    suspendRendering: Boolean,
) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val session = remember(libraryDir) {
        ProjectMOffscreenSession(libraryDir) { bitmap ->
            frame = bitmap
        }
    }
    DisposableEffect(session) {
        session.start()
        onDispose { session.dispose() }
    }
    DisposableEffect(presetPath, presetData, locked, isPlaying, suspendRendering) {
        when {
            !presetPath.isNullOrBlank() -> session.setPresetFile(presetPath)
            !presetData.isNullOrBlank() -> session.setPresetData(presetData)
            else -> session.setPresetFile("idle://")
        }
        session.setLocked(locked)
        session.setPlaying(isPlaying && !suspendRendering)
        onDispose { }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .semantics { contentDescription = "Now playing visualizer" }
            .onSizeChanged { size ->
                session.setTargetPixelSize(size.width, size.height)
            },
    ) {
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

@Composable
private fun SwingProjectMHost(
    libraryDir: File,
    presetPath: String?,
    presetData: String?,
    locked: Boolean,
    isPlaying: Boolean,
    modifier: Modifier,
    suspendRendering: Boolean,
) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val panel = remember(libraryDir) {
        runCatching {
            ProjectMGlPanel(libraryDir) { bitmap ->
                frame = bitmap
            }.also { it.start() }
        }.onFailure { error ->
            PhoebeLog.d("ProjectM") { "ProjectMGlPanel init failed: ${error.logDetail()}" }
        }.getOrNull()
    }
    if (panel == null) {
        LaunchedEffect(Unit) { ProjectMHostGate.markFailed() }
        Box(modifier.fillMaxSize().background(Color.Black))
        return
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

private fun isLinuxDesktop(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("linux")

fun resolveProjectMLibraryDir(): File =
    resolveProjectMLibraryDirOrNull()
        ?: error(
            "projectM native libraries not found for ${projectMDesktopTarget()}. " +
                "Run scripts/build-projectm.sh",
        )

fun resolveProjectMLibraryDirOrNull(): File? {
    val override = System.getProperty("phoebe.projectm.libraryDir")
        ?: System.getenv("PHOEBE_PROJECTM_LIBRARY_DIR")
    if (!override.isNullOrBlank()) {
        return File(override).takeIf { dirLooksLikeProjectM(it) }
    }
    val target = projectMDesktopTarget()
    val resourcesDir = System.getProperty("compose.application.resources.dir")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
    val candidates = buildList {
        // Packaged apps: Compose Sync flattens windows-x64/ contents into resources.dir,
        // but also accept the os-arch subdirectory (same pattern as MacMediaDylib).
        if (resourcesDir != null) {
            add(resourcesDir)
            add(File(resourcesDir, target))
        }
        add(File("native/projectm/$target/lib"))
        add(File("../native/projectm/$target/lib"))
        add(File(System.getProperty("user.dir"), "native/projectm/$target/lib"))
    }
    val found = candidates.firstOrNull(::dirLooksLikeProjectM)
    if (found != null) {
        PhoebeLog.d("ProjectM") { "Using projectM libraries from ${found.absolutePath}" }
    }
    return found
}

private fun projectMDesktopTarget(): String {
    val arch = when (System.getProperty("os.arch")) {
        "aarch64", "arm64" -> "arm64"
        else -> "x64"
    }
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("mac") -> "macos-$arch"
        os.contains("win") -> "windows-x64"
        else -> "linux-$arch"
    }
}

private fun dirLooksLikeProjectM(dir: File): Boolean =
    dir.isDirectory && dir.listFiles()?.any { it.name.contains("PhoebeProjectM") } == true
