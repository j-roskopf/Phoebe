package com.phoebe.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.phoebe.app.feature.playback.ProjectMGlPanel
import com.phoebe.app.feature.playback.resolveProjectMLibraryDir
import com.phoebe.app.platform.PhoebeLog
import java.awt.Robot
import java.awt.event.InputEvent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Phase 0 gate: libprojectM + SwingPanel GL + compose.interop.blending.
 *
 * Passes when:
 * 1. projectM renders at least [MinSuccessfulFrames] frames
 * 2. A Compose button drawn *above* the GL surface receives a real mouse click
 * 3. Control: a Compose button *outside* the GL surface also receives a click
 *    (proves Robot/accessibility works; isolates the Metal event-ordering bug)
 */
internal fun runPhase0InteropGateIfRequested(args: Array<String>): Boolean {
    if (!args.contains("--phase0-interop-gate")) return false
    System.setProperty("compose.interop.blending", "true")
    if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
        System.setProperty("skiko.renderApi", "DIRECT3D")
        System.clearProperty("skiko.rendering.angle.enabled")
    }
    runPhase0InteropGate()
    return true
}

private const val MinSuccessfulFrames = 30L
private const val GateTimeoutMs = 25_000L

private fun runPhase0InteropGate() {
    val overlayClicked = AtomicBoolean(false)
    val controlClicked = AtomicBoolean(false)
    val overlayCenter = AtomicReference<Offset?>(null)
    val controlCenter = AtomicReference<Offset?>(null)
    val windowRef = AtomicReference<ComposeWindow?>(null)
    val panelRef = AtomicReference<ProjectMGlPanel?>(null)
    val finished = AtomicBoolean(false)
    val preset = resolveBundledPresetPath()

    PhoebeLog.d("Phase0Gate") { "starting interop blending gate; preset=$preset" }

    thread(name = "phase0-gate-watchdog", isDaemon = true) {
        val deadline = System.currentTimeMillis() + GateTimeoutMs
        var controlAttempts = 0
        var overlayAttempts = 0
        while (System.currentTimeMillis() < deadline && !finished.get()) {
            val frames = panelRef.get()?.successfulFrameCount() ?: 0L
            val window = windowRef.get()
            if (frames >= MinSuccessfulFrames && window != null) {
                val control = controlCenter.get()
                if (!controlClicked.get() && control != null && controlAttempts < 4) {
                    controlAttempts++
                    tryClick(window, control, "control")
                }
                val overlay = overlayCenter.get()
                if (controlClicked.get() && !overlayClicked.get() && overlay != null && overlayAttempts < 6) {
                    overlayAttempts++
                    tryClick(window, overlay, "overlay")
                }
            }
            if (frames >= MinSuccessfulFrames && controlClicked.get() && overlayClicked.get()) {
                finished.set(true)
                println(
                    "PHASE0 GATE PASSED: projectM rendered $frames frames; " +
                        "control + overlay Compose clicks OK under compose.interop.blending.",
                )
                exitProcess(0)
            }
            Thread.sleep(250L)
        }
        val frames = panelRef.get()?.successfulFrameCount() ?: 0L
        System.err.println(
            "PHASE0 GATE FAILED: frames=$frames controlClicked=${controlClicked.get()} " +
                "overlayClicked=${overlayClicked.get()}. " +
                if (!controlClicked.get()) {
                    "Robot/accessibility control click failed — cannot judge interop blending."
                } else {
                    "Control click worked but overlay-on-GL click failed — Metal event-ordering " +
                        "bug makes Compose chrome over the GL surface unusable. " +
                        "Stopping per plan — Decision 3/4 needs a user call."
                },
        )
        exitProcess(2)
    }

    application {
        val state = rememberWindowState(width = 960.dp, height = 640.dp)
        Window(
            onCloseRequest = {
                exitApplication()
                exitProcess(2)
            },
            title = "Phoebe Phase 0 Interop Gate",
            state = state,
        ) {
            windowRef.set(window)
            var overlayLabel by remember { mutableStateOf("Overlay") }
            var controlLabel by remember { mutableStateOf("Control") }
            val panel = remember {
                ProjectMGlPanel(resolveProjectMLibraryDir()).also {
                    it.setPresetFile(preset)
                    it.start()
                    panelRef.set(it)
                }
            }
            DisposableEffect(panel) {
                onDispose { panel.disposeNative() }
            }
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(520.dp)) {
                    SwingPanel(
                        modifier = Modifier.fillMaxSize(),
                        factory = { panel },
                    )
                    Button(
                        onClick = {
                            overlayClicked.set(true)
                            overlayLabel = "OverlayOK"
                            PhoebeLog.d("Phase0Gate") { "overlay button received click" }
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(18.dp)
                            .onGloballyPositioned { coords ->
                                val bounds = coords.boundsInWindow()
                                overlayCenter.set(
                                    Offset(
                                        (bounds.left + bounds.right) / 2f,
                                        (bounds.top + bounds.bottom) / 2f,
                                    ),
                                )
                            },
                    ) {
                        Text(overlayLabel)
                    }
                }
                Button(
                    onClick = {
                        controlClicked.set(true)
                        controlLabel = "ControlOK"
                        PhoebeLog.d("Phase0Gate") { "control button received click" }
                    },
                    modifier = Modifier
                        .padding(16.dp)
                        .onGloballyPositioned { coords ->
                            val bounds = coords.boundsInWindow()
                            controlCenter.set(
                                Offset(
                                    (bounds.left + bounds.right) / 2f,
                                    (bounds.top + bounds.bottom) / 2f,
                                ),
                            )
                        },
                ) {
                    Text(controlLabel)
                }
            }
        }
    }
}

private fun tryClick(window: ComposeWindow, centerInWindow: Offset, label: String) {
    val contentOrigin = window.contentPane.locationOnScreen
    val x = contentOrigin.x + centerInWindow.x.toInt()
    val y = contentOrigin.y + centerInWindow.y.toInt()
    PhoebeLog.d("Phase0Gate") { "Robot click ($label) at ($x,$y) center=$centerInWindow" }
    val robot = Robot()
    robot.autoDelay = 30
    robot.mouseMove(x, y)
    Thread.sleep(40L)
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
}

private fun resolveBundledPresetPath(): String {
    val resource = Thread.currentThread().contextClassLoader
        .getResource("projectm-presets/geiss-swirlie-1.milk")
    if (resource != null && resource.protocol == "file") {
        return File(resource.toURI()).absolutePath
    }
    val candidates = listOf(
        File("composeApp/src/desktopMain/resources/projectm-presets/geiss-swirlie-1.milk"),
        File("src/desktopMain/resources/projectm-presets/geiss-swirlie-1.milk"),
    )
    return candidates.firstOrNull { it.isFile }?.absolutePath ?: "idle://"
}
