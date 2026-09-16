package com.phoebe.app.feature.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.player.IosProjectMNativeBridge
import com.phoebe.app.player.VisualizerPcmBus
import com.phoebe.app.player.VisualizerPcmSink
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIView

/**
 * iOS projectM host — native [PhoebeProjectMHostView] mounted through [UIKitView].
 *
 * The view renders projectM into an offscreen texture FBO and blits that to a
 * CAEAGLLayer drawable, so the frame never round-trips through Skia.
 *
 * Note that libprojectM needs the Phoebe `0004-ios-target-framebuffer` patch to
 * render here at all: upstream composites into FBO 0, which does not exist on
 * iOS. See native/projectm/patches/ios/README.md.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun ProjectMVisualizerHost(
    presetPath: String?,
    presetData: String?,
    locked: Boolean,
    isPlaying: Boolean,
    modifier: Modifier,
    suspendRendering: Boolean,
) {
    val factory = IosProjectMNativeBridge.factory
    if (factory == null) {
        // Mark the gate so NowPlayingVisualizerDisplay swaps in artwork on the next
        // pass — drawing a black box here would just look like a broken visualizer.
        PhoebeLog.d("ProjectM") { "IosProjectMNativeBridge.factory missing — artwork fallback" }
        LaunchedEffect(Unit) { ProjectMHostGate.markFailed() }
        Box(modifier.fillMaxSize().background(Color.Black))
        return
    }

    val presetPathState = rememberUpdatedState(presetPath)
    val presetDataState = rememberUpdatedState(presetData)
    val lockedState = rememberUpdatedState(locked)
    val playingState = rememberUpdatedState(isPlaying)
    var hostView by remember { mutableStateOf<UIView?>(null) }
    var initFailed by remember { mutableStateOf(false) }

    val pcmSink = remember {
        VisualizerPcmSink { samples, channels, _ ->
            val view = hostView ?: return@VisualizerPcmSink
            if (VisualizerHtmlOverlayGate.isSuppressed) return@VisualizerPcmSink
            factory.addPcm(view, samples, channels.coerceIn(1, 2))
        }
    }

    // UIKitView uses the *layout* size, not the graphicsLayer-scaled visual size.
    // While the mobile sheet is collapsed/animating, that layout is still full-bleed
    // artwork bounds — mounting a native host there paints a black UIView over the
    // mini-player chrome. Keep a Compose placeholder until rendering is live.
    val suspended = suspendRendering || VisualizerHtmlOverlayGate.isSuppressed

    DisposableEffect(Unit) {
        ProjectMHostGate.reset()
        VisualizerPcmBus.addSink(pcmSink)
        onDispose {
            VisualizerPcmBus.removeSink(pcmSink)
            hostView?.let { ProjectMIosOverlayGate.unregister(it) }
            hostView = null
        }
    }

    DisposableEffect(suspended) {
        if (suspended) {
            hostView?.let { ProjectMIosOverlayGate.unregister(it) }
            hostView = null
        }
        onDispose { }
    }

    if (initFailed || !ProjectMHostGate.healthy || suspended) {
        Box(modifier.fillMaxSize().background(Color.Black))
        return
    }

    UIKitView(
        modifier = modifier.fillMaxSize(),
        factory = {
            val view = factory.create(
                presetPath = presetPathState.value,
                presetData = presetDataState.value,
                locked = lockedState.value,
                playing = playingState.value,
                suspended = false,
            )
            hostView = view
            ProjectMIosOverlayGate.register(view)
            if (!factory.isNativeReady(view)) {
                // A real GLES failure (no EAGL context). Fail the gate so the
                // surface degrades to artwork rather than a black rectangle.
                initFailed = true
                ProjectMHostGate.markFailed()
                PhoebeLog.d("ProjectM") { "PhoebeProjectMHostView native init failed / unavailable" }
            }
            view
        },
        update = { view ->
            hostView = view
            ProjectMIosOverlayGate.register(view)
            factory.update(
                view = view,
                presetPath = presetPathState.value,
                presetData = presetDataState.value,
                locked = lockedState.value,
                playing = playingState.value,
                suspended = false,
            )
        },
    )
}

internal object ProjectMIosOverlayGate {
    private val views = mutableListOf<UIView>()

    fun register(view: UIView) {
        if (views.none { it === view }) views += view
    }

    fun unregister(view: UIView) {
        views.removeAll { it === view }
    }

    fun setSuppressed(suppressed: Boolean) {
        val factory = IosProjectMNativeBridge.factory ?: return
        views.toList().forEach { factory.setSuspended(it, suppressed) }
    }
}
