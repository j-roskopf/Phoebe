package com.phoebe.app.feature.playback

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.phoebe.app.player.SyntheticVisualizerPcm
import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import org.junit.Assume.assumeTrue

/**
 * The Linux visualizer used to come back as a black rectangle because frames were
 * read into an FBO and the on-screen GL canvas (cleared black) was what Compose showed.
 */
class LinuxProjectMOffscreenDesktopTest {
    @Test
    fun offscreenPresetFrameIsNotBlack() {
        assumeTrue(
            "Linux-only",
            System.getProperty("os.name").orEmpty().lowercase().contains("linux"),
        )
        assumeTrue("needs a display", !GraphicsEnvironment.isHeadless())
        val libDir = resolveProjectMLibraryDirOrNull()
        assumeTrue("projectM libs missing", libDir != null)
        val milk = presetFile()
        assumeTrue("preset missing: looked for phoebe-pulse.milk", milk != null)

        ProjectMHostGate.reset()
        val latest = AtomicReference<ImageBitmap?>(null)
        val session = ProjectMOffscreenSession(libDir!!) { bitmap ->
            latest.set(bitmap)
        }
        val synth = SyntheticVisualizerPcm(seed = 7L)
        try {
            session.setTargetPixelSize(320, 180)
            session.setPresetFile(milk!!.absolutePath)
            session.setLocked(true)
            session.setPlaying(true)
            session.start()
            val deadline = System.currentTimeMillis() + 8_000
            var colorful = 0
            while (System.currentTimeMillis() < deadline && colorful < 12) {
                synth.publishBlock()
                Thread.sleep(30)
                colorful = latest.get()?.nonBlackSampleCount() ?: 0
            }
            assertTrue(
                colorful >= 12,
                "expected a visible projectM frame, nonBlackSamples=$colorful frames=${session.successfulFrameCount()} " +
                    "error=${session.startupError()} DISPLAY=${System.getenv("DISPLAY")}",
            )
        } finally {
            session.dispose()
        }
    }

    @Test
    fun offscreenFrameSurvivesSkikoOpenGlWindow() {
        assumeTrue(
            "Linux-only",
            System.getProperty("os.name").orEmpty().lowercase().contains("linux"),
        )
        assumeTrue("needs a display", !GraphicsEnvironment.isHeadless())
        val libDir = resolveProjectMLibraryDirOrNull()
        assumeTrue("projectM libs missing", libDir != null)
        val milk = presetFile()
        assumeTrue("preset missing: looked for phoebe-pulse.milk", milk != null)

        LinuxProjectMGl.installAndWait()
        val frame = JFrame("projectM-skiko")
        SwingUtilities.invokeAndWait {
            frame.layout = BorderLayout()
            val layer = SkiaLayer(renderApi = GraphicsApi.OPENGL)
            layer.attachTo(frame.contentPane as javax.swing.JComponent)
            frame.setSize(480, 320)
            frame.isVisible = true
        }
        Thread.sleep(400)
        ProjectMHostGate.reset()
        val latest = AtomicReference<ImageBitmap?>(null)
        val session = ProjectMOffscreenSession(libDir!!) { bitmap ->
            latest.set(bitmap)
        }
        val synth = SyntheticVisualizerPcm(seed = 11L)
        try {
            session.setTargetPixelSize(320, 180)
            session.setPresetFile(milk!!.absolutePath)
            session.setLocked(true)
            session.setPlaying(true)
            session.start()
            val deadline = System.currentTimeMillis() + 8_000
            var colorful = 0
            while (System.currentTimeMillis() < deadline && colorful < 12) {
                synth.publishBlock()
                Thread.sleep(30)
                colorful = latest.get()?.nonBlackSampleCount() ?: 0
            }
            assertTrue(
                colorful >= 12,
                "expected a frame after the Skiko window, nonBlackSamples=$colorful " +
                    "frames=${session.successfulFrameCount()} error=${session.startupError()}",
            )
        } finally {
            session.dispose()
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    private fun presetFile(): File? {
        val candidates = listOf(
            File("src/commonMain/composeResources/files/projectm-presets/phoebe-pulse.milk"),
            File("feature/playback/src/commonMain/composeResources/files/projectm-presets/phoebe-pulse.milk"),
            File("composeApp/src/commonMain/resources/projectm-presets/phoebe-pulse.milk"),
        )
        return candidates.firstOrNull { it.isFile }
    }
}

private fun ImageBitmap.nonBlackSampleCount(): Int {
    val pixels = toPixelMap()
    if (pixels.width <= 1 || pixels.height <= 1) return 0
    var count = 0
    val stepX = (pixels.width / 32).coerceAtLeast(1)
    val stepY = (pixels.height / 32).coerceAtLeast(1)
    var y = 0
    while (y < pixels.height) {
        var x = 0
        while (x < pixels.width) {
            val color = pixels[x, y]
            if (color.red > 0.05f || color.green > 0.05f || color.blue > 0.05f) {
                count++
            }
            x += stepX
        }
        y += stepY
    }
    return count
}
