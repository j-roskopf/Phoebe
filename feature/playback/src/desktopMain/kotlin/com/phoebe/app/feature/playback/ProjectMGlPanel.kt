package com.phoebe.app.feature.playback

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.BorderLayout
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.awt.AWTGLCanvas
import org.lwjgl.opengl.awt.GLData

/**
 * LWJGL AWT canvas that drives a libprojectM instance.
 *
 * Under `compose.interop.blending` on macOS retina, a heavyweight [AWTGLCanvas]
 * is composited at the wrong scale (bottom-left quadrant + chrome overhang).
 * We keep the canvas only as an OpenGL context host and present frames to Compose
 * via an FBO readback → [ImageBitmap].
 *
 * Linux does not use this class. A visible GL canvas there punches a black hole
 * through the Skia layer and steals focus from Compose popups; see
 * [ProjectMOffscreenSession].
 */
class ProjectMGlPanel(
    libraryDir: File,
    onFrame: (ImageBitmap) -> Unit = {},
) : JPanel(BorderLayout()) {
    private val core = ProjectMGlCore(onFrame)
    private val disposed = AtomicBoolean(false)
    private val canvas: ContextOwningCanvas
    private val animator: Timer

    init {
        ProjectMNative.ensureLoaded(libraryDir.absolutePath)
        val data = GLData().apply {
            // projectM needs glGenSamplers (OpenGL 3.3); core profile matches its
            // Windows GLEW build. Requesting 3.2 left sampler entry points missing.
            majorVersion = 3
            minorVersion = 3
            profile = GLData.Profile.CORE
            doubleBuffer = true
            swapInterval = 1
        }
        canvas = object : ContextOwningCanvas(data) {
            override fun initGL() {
                if (disposed.get()) return
                val ready = runCatching {
                    GL.createCapabilities()
                    core.initGl()
                }.getOrElse { error ->
                    System.err.println("projectM initGL failed: ${error.message}")
                    false
                }
                if (!ready) {
                    ProjectMHostGate.markFailed()
                }
            }

            override fun paintGL() {
                if (disposed.get() || core.isDisposed()) {
                    core.releaseGl()
                    return
                }
                if (!core.render()) return
                // Keep the on-screen canvas black; Compose Image presents the frame.
                glViewport(0, 0, framebufferWidth.coerceAtLeast(1), framebufferHeight.coerceAtLeast(1))
                glClearColor(0f, 0f, 0f, 1f)
                glClear(GL_COLOR_BUFFER_BIT)
                swapBuffers()
            }

            override fun releaseGlResources() {
                core.releaseGl()
            }
        }.apply {
            isEnabled = false
            isFocusable = false
        }
        background = java.awt.Color.BLACK
        isEnabled = false
        isFocusable = false
        add(canvas, BorderLayout.CENTER)
        animator = Timer(16) {
            if (disposed.get()) return@Timer
            if (!canvas.isDisplayable || !canvas.isShowing) return@Timer
            runCatching { canvas.render() }
                .onFailure { error ->
                    System.err.println("projectM render failed: ${error.message}")
                    animator.stop()
                    ProjectMHostGate.markFailed()
                }
        }
        animator.isRepeats = true
        addHierarchyListener(object : HierarchyListener {
            override fun hierarchyChanged(event: HierarchyEvent) {
                val showingChanged =
                    event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L
                if (!showingChanged) return
                if (isShowing) start() else stop()
            }
        })
    }

    fun setTargetPixelSize(widthPx: Int, heightPx: Int) {
        core.setTargetPixelSize(widthPx, heightPx)
    }

    fun start() {
        if (!animator.isRunning) {
            SwingUtilities.invokeLater { animator.start() }
        }
    }

    fun stop() {
        if (animator.isRunning) {
            SwingUtilities.invokeLater { animator.stop() }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun setPresetFile(path: String?, smooth: Boolean = false) {
        core.setPresetFile(path)
    }

    @Suppress("UNUSED_PARAMETER")
    fun setPresetData(data: String?, smooth: Boolean = false) {
        core.setPresetData(data)
    }

    fun setLocked(value: Boolean) {
        core.setLocked(value)
    }

    fun setPlaying(value: Boolean) {
        core.setPlaying(value)
    }

    fun addPcm(samples: FloatArray, channels: Int = 2) {
        core.onPcm(samples, channels, 44_100f)
    }

    fun successfulFrameCount(): Long = core.successfulFrameCount()

    fun disposeNative() {
        if (!disposed.compareAndSet(false, true)) return
        core.markDisposed()
        stop()
        val destroy = Runnable {
            // Release projectM and delete the context now; removeNotify covers a
            // SwingPanel that detaches the canvas before this runs.
            if (canvas.isDisplayable) canvas.releaseContext()
        }
        if (SwingUtilities.isEventDispatchThread()) destroy.run()
        else SwingUtilities.invokeAndWait(destroy)
    }
}

/**
 * lwjgl3-awt's [AWTGLCanvas.removeNotify] zeroes `context` without deleting it, and
 * [AWTGLCanvas.disposeCanvas] only frees the JAWT surface. Every detach therefore
 * leaked the NSOpenGLContext/WGL context along with the projectM textures and FBO
 * living in it. This deletes the context while the peer can still be locked.
 */
private abstract class ContextOwningCanvas(data: GLData) : AWTGLCanvas(data) {
    /** Free GL objects owned by this context. Called with the context current. */
    abstract fun releaseGlResources()

    override fun removeNotify() {
        releaseContext()
        super.removeNotify()
    }

    fun releaseContext() {
        if (context == 0L) return
        runCatching { runInContext { releaseGlResources() } }
            .onFailure { error -> System.err.println("projectM releaseGl failed: ${error.message}") }
        runCatching { platformCanvas.deleteContext(context) }
        context = 0L
        initCalled = false
    }
}
