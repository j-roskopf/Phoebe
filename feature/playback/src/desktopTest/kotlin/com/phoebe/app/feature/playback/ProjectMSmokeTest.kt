package com.phoebe.app.feature.playback

import com.phoebe.app.player.SyntheticVisualizerPcm
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import java.awt.GraphicsEnvironment
import java.io.File

/**
 * Headless-ish projectM smoke: create instance, feed synthetic PCM, render frames.
 * Requires native libs from scripts/build-projectm.sh and a GL-capable environment.
 */
class ProjectMSmokeTest {
    @Test
    fun bundledPresetsRenderWithoutCrashing() {
        assumeTrue("Headless CI without display", !GraphicsEnvironment.isHeadless())
        val libDir = resolveProjectMLibraryDirOrNull() ?: run {
            assumeTrue("projectM libs missing", false)
            return
        }
        ProjectMNative.ensureLoaded(libDir.absolutePath)
        val panel = ProjectMGlPanel(libDir)
        try {
            panel.start()
            val synth = SyntheticVisualizerPcm(seed = 42L)
            val milkDir = File("composeApp/src/commonMain/resources/projectm-presets")
            val milks = milkDir.listFiles()?.filter { it.extension.equals("milk", true) }.orEmpty()
            assertTrue(milks.isNotEmpty(), "expected bundled milk presets")
            for (milk in milks) {
                panel.setPresetFile(milk.absolutePath)
                panel.setPlaying(true)
                repeat(12) {
                    synth.publishBlock(512)
                    Thread.sleep(16)
                }
            }
        } finally {
            panel.disposeNative()
        }
    }
}
