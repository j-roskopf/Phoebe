package com.phoebe.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.feature.playback.DesktopNowPlayingVisualizerView
import com.phoebe.app.feature.playback.prewarmLinuxProjectMGl
import com.phoebe.app.feature.playback.ProjectMHostGate
import com.phoebe.app.player.SyntheticVisualizerPcm
import com.phoebe.app.ui.PhoebeTheme
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.junit.Assume.assumeTrue

/**
 * Linux used to show a black visualizer rectangle, and the preset menu strobed
 * because the GL canvas stole focus. This runs against the real offscreen host.
 */
class LinuxVisualizerDesktopTest {
    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun visualizerFrameIsVisibleAndPresetMenuStaysOpen() {
        prewarmLinuxProjectMGl()
        runDesktopComposeUiTest(width = 960, height = 720) {
        assumeTrue(
            "Linux-only",
            System.getProperty("os.name").orEmpty().lowercase(Locale.US).contains("linux"),
        )
        assumeTrue("needs a display", !GraphicsEnvironment.isHeadless())
        val repoRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
        val libDir = repoRoot?.resolve("native/projectm/linux-x64/lib")
        if (libDir != null && libDir.isDirectory) {
            System.setProperty("phoebe.projectm.libraryDir", libDir.absolutePath)
        }
        val milk = listOf(
            File("src/commonMain/resources/projectm-presets/phoebe-pulse.milk"),
            File("composeApp/src/commonMain/resources/projectm-presets/phoebe-pulse.milk"),
        ).firstOrNull { it.isFile }
        assumeTrue("preset missing", milk != null)
        // JavaFX's default GPU pipeline blocks eglMakeCurrent for projectM. The app
        // sets this before starting JavaFX; do the same here, then start JavaFX so
        // this covers the real player process rather than a fresh JVM.
        System.setProperty("prism.order", "sw")
        val javafxReady = CountDownLatch(1)
        runCatching { Platform.startup { javafxReady.countDown() } }
            .onFailure { javafxReady.countDown() }
        assertTrue(javafxReady.await(5, TimeUnit.SECONDS), "JavaFX did not start")
        System.setProperty("compose.interop.blending", "true")
        ProjectMHostGate.reset()

        setContent {
            PhoebeTheme {
                LaunchedEffect(Unit) {
                    val synth = SyntheticVisualizerPcm(seed = 3L)
                    while (isActive) {
                        synth.publishBlock()
                        delay(20)
                    }
                }
                Box(Modifier.size(960.dp, 720.dp)) {
                    DesktopNowPlayingVisualizerView(
                        track = null,
                        preset = NowPlayingVisualizerPreset.Pinned(
                            packId = "bundled",
                            presetId = "phoebe-pulse",
                            displayName = "Pulse",
                        ),
                        isPlaying = true,
                        positionMs = 0L,
                        onPreset = {},
                        modifier = Modifier.fillMaxSize(),
                        presetMilkPath = milk!!.absolutePath,
                    )
                }
            }
        }

        waitUntil(timeoutMillis = 8_000) {
            val image = runCatching {
                onNodeWithContentDescription("Now playing visualizer").captureToImage()
            }.getOrNull() ?: return@waitUntil false
            val pixels = image.toPixelMap()
            var colorful = 0
            val stepX = (pixels.width / 24).coerceAtLeast(1)
            val stepY = (pixels.height / 24).coerceAtLeast(1)
            var y = 0
            while (y < pixels.height) {
                var x = 0
                while (x < pixels.width) {
                    val color = pixels[x, y]
                    if (color.red > 0.05f || color.green > 0.05f || color.blue > 0.05f) {
                        colorful++
                    }
                    x += stepX
                }
                y += stepY
            }
            colorful >= 8
        }

        val before = showingWindows()
        onNodeWithContentDescription("Visualizer").performClick()
        waitUntil(timeoutMillis = 2_000) {
            onNodeWithText("Artwork").isDisplayedOrNull()
        }
        val popups = showingWindows().filter { window -> before.none { it === window } }
        repeat(12) {
            assertTrue(
                onNodeWithText("Artwork").isDisplayedOrNull(),
                "preset menu closed while the visualizer was running; windows=${showingWindows().map { it.javaClass.simpleName }}",
            )
            if (popups.isNotEmpty()) {
                assertTrue(
                    popups.all { it.isShowing },
                    "preset menu window was hidden; popups=${popups.map { "${it.javaClass.simpleName} showing=${it.isShowing}" }}",
                )
            }
            Thread.sleep(50)
        }
        }
    }
}

private fun showingWindows(): List<Window> =
    Window.getWindows().filter { it.isShowing && it.width > 20 && it.height > 20 }

private fun androidx.compose.ui.test.SemanticsNodeInteraction.isDisplayedOrNull(): Boolean =
    runCatching {
        assertIsDisplayed()
        true
    }.getOrDefault(false)
