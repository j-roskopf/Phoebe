package com.phoebe.app.feature.playback

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.BorderLayout
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import com.phoebe.app.player.VisualizerPcmBus
import com.phoebe.app.player.VisualizerPcmSink
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glGetError
import org.lwjgl.opengl.GL11.glReadPixels
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE
import org.lwjgl.opengl.GL30.GL_RENDERBUFFER
import org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8
import org.lwjgl.opengl.GL30.GL_DEPTH_STENCIL_ATTACHMENT
import org.lwjgl.opengl.GL30.glBindFramebuffer
import org.lwjgl.opengl.GL30.glBindRenderbuffer
import org.lwjgl.opengl.GL30.glCheckFramebufferStatus
import org.lwjgl.opengl.GL30.glDeleteFramebuffers
import org.lwjgl.opengl.GL30.glDeleteRenderbuffers
import org.lwjgl.opengl.GL30.glFramebufferRenderbuffer
import org.lwjgl.opengl.GL30.glFramebufferTexture2D
import org.lwjgl.opengl.GL30.glGenFramebuffers
import org.lwjgl.opengl.GL30.glGenRenderbuffers
import org.lwjgl.opengl.GL30.glRenderbufferStorage
import org.lwjgl.opengl.awt.AWTGLCanvas
import org.lwjgl.opengl.awt.GLData

/**
 * LWJGL AWT canvas that drives a libprojectM instance.
 *
 * Under `compose.interop.blending` on macOS retina, a heavyweight [AWTGLCanvas]
 * is composited at the wrong scale (bottom-left quadrant + chrome overhang).
 * We keep the canvas only as an OpenGL context host and present frames to Compose
 * via an FBO readback → [ImageBitmap].
 */
class ProjectMGlPanel(
    libraryDir: File,
    private val onFrame: (ImageBitmap) -> Unit = {},
) : JPanel(BorderLayout()), VisualizerPcmSink {
    private val handle = AtomicLong(0L)
    private val presetPath = AtomicReference<String?>(null)
    private val presetData = AtomicReference<String?>(null)
    private val locked = AtomicBoolean(false)
    private val playing = AtomicBoolean(true)
    private val frameCount = AtomicLong(0L)
    private val disposed = AtomicBoolean(false)
    private val glReady = AtomicBoolean(false)
    private val targetWidth = AtomicInteger(0)
    private val targetHeight = AtomicInteger(0)
    private var lastLoadedPath: String? = null
    private var lastLoadedData: String? = null
    private val canvas: AWTGLCanvas
    private val animator: Timer

    private var fbo = 0
    private var fboTex = 0
    private var fboDepth = 0
    private var fboW = 0
    private var fboH = 0
    private var pixelBuffer: ByteBuffer? = null

    init {
        ProjectMNative.ensureLoaded(libraryDir.absolutePath)
        VisualizerPcmBus.addSink(this)
        val data = GLData().apply {
            // projectM needs glGenSamplers (OpenGL 3.3); core profile matches its
            // Windows GLEW build. Requesting 3.2 left sampler entry points missing.
            majorVersion = 3
            minorVersion = 3
            profile = GLData.Profile.CORE
            doubleBuffer = true
            swapInterval = 1
        }
        canvas = object : AWTGLCanvas(data) {
            override fun initGL() {
                val ready = runCatching {
                    GL.createCapabilities()
                    // Windows projectM is built against GLEW; LWJGL does not init it.
                    check(ProjectMNative.nativeInitGlLoader()) {
                        "glewInit failed — projectM cannot resolve OpenGL entry points"
                    }
                    val ptr = ProjectMNative.nativeCreate()
                    check(ptr != 0L) { "projectm_create failed — is an OpenGL 3.3+ context current?" }
                    handle.set(ptr)
                    ProjectMNative.nativeSetFps(ptr, 60)
                    ProjectMNative.nativeSetPresetDuration(ptr, 20.0)
                    applyPreset(ptr, force = true)
                    ProjectMNative.nativeSetPresetLocked(ptr, locked.get())
                    true
                }.getOrElse { error ->
                    System.err.println("projectM initGL failed: ${error.message}")
                    ProjectMHostGate.markFailed()
                    false
                }
                glReady.set(ready)
            }

            override fun paintGL() {
                if (disposed.get()) {
                    destroyFbo()
                    return
                }
                val ptr = handle.get()
                if (ptr == 0L) return
                val presetChanged = applyPreset(ptr, force = false)
                // Paused: freeze the last Compose frame; still paint once after a preset swap.
                if (!playing.get() && !presetChanged) return

                val (w, h) = renderPixelSize()
                ensureFbo(w, h)
                glBindFramebuffer(GL_FRAMEBUFFER, fbo)
                ProjectMNative.nativeSetWindowSize(ptr, w, h)
                glViewport(0, 0, w, h)
                glClearColor(0f, 0f, 0f, 1f)
                glClear(GL_COLOR_BUFFER_BIT)
                ProjectMNative.nativeRenderFrame(ptr)

                val buffer = pixelBuffer ?: return
                buffer.clear()
                // projectM only rebinds the draw target; make sure the read target is
                // still our FBO before pulling the composited frame back out.
                glBindFramebuffer(GL_FRAMEBUFFER, fbo)
                glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
                glBindFramebuffer(GL_FRAMEBUFFER, 0)
                // Keep the on-screen canvas black; Compose Image presents the frame.
                glViewport(0, 0, framebufferWidth.coerceAtLeast(1), framebufferHeight.coerceAtLeast(1))
                glClearColor(0f, 0f, 0f, 1f)
                glClear(GL_COLOR_BUFFER_BIT)
                swapBuffers()

                if (!disposed.get()) {
                    onFrame(rgbaToImageBitmap(buffer, w, h))
                }

                val frames = frameCount.incrementAndGet()
                if (frames % 3L == 0L) {
                    VisualizerSentinelBridge.onSuccessfulFrame()
                }
                glGetError() // clear sticky errors from incomplete FBOs during resize
            }

            private fun renderPixelSize(): Pair<Int, Int> {
                val tw = targetWidth.get()
                val th = targetHeight.get()
                if (tw > 1 && th > 1) return tw to th
                val w = framebufferWidth.takeIf { it > 0 } ?: width.coerceAtLeast(1)
                val h = framebufferHeight.takeIf { it > 0 } ?: height.coerceAtLeast(1)
                return w.coerceAtLeast(1) to h.coerceAtLeast(1)
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
        targetWidth.set(widthPx.coerceAtLeast(0))
        targetHeight.set(heightPx.coerceAtLeast(0))
    }

    override fun onPcm(samples: FloatArray, channels: Int, sampleRateHz: Float) {
        if (!playing.get()) return
        val ptr = handle.get()
        if (ptr == 0L || samples.isEmpty()) return
        ProjectMNative.nativeAddPcmFloat(ptr, samples, channels.coerceIn(1, 2))
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

    fun setPresetFile(path: String?, smooth: Boolean = false) {
        presetPath.set(path)
        presetData.set(null)
        @Suppress("UNUSED_PARAMETER")
        smooth
    }

    fun setPresetData(data: String?, smooth: Boolean = false) {
        presetData.set(data)
        presetPath.set(null)
        @Suppress("UNUSED_PARAMETER")
        smooth
    }

    fun setLocked(value: Boolean) {
        locked.set(value)
        val ptr = handle.get()
        if (ptr != 0L && glReady.get()) {
            ProjectMNative.nativeSetPresetLocked(ptr, value)
        }
    }

    fun setPlaying(value: Boolean) {
        playing.set(value)
    }

    fun addPcm(samples: FloatArray, channels: Int = 2) {
        onPcm(samples, channels, 44_100f)
    }

    fun successfulFrameCount(): Long = frameCount.get()

    fun disposeNative() {
        if (!disposed.compareAndSet(false, true)) return
        VisualizerPcmBus.removeSink(this)
        stop()
        val destroy = Runnable {
            // One more render while disposed=true so paintGL can destroy the FBO
            // with a current GL context.
            runCatching {
                if (canvas.isDisplayable) {
                    canvas.render()
                }
            }
            val ptr = handle.getAndSet(0L)
            if (ptr != 0L) {
                runCatching { ProjectMNative.nativeDestroy(ptr) }
            }
            runCatching { canvas.disposeCanvas() }
        }
        if (SwingUtilities.isEventDispatchThread()) destroy.run()
        else SwingUtilities.invokeAndWait(destroy)
    }

    private fun ensureFbo(w: Int, h: Int) {
        if (fbo != 0 && fboW == w && fboH == h) return
        destroyFbo()
        fboTex = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, fboTex)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null as ByteBuffer?)
        fboDepth = glGenRenderbuffers()
        glBindRenderbuffer(GL_RENDERBUFFER, fboDepth)
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, w, h)
        fbo = glGenFramebuffers()
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboTex, 0)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, fboDepth)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
            "projectM FBO incomplete"
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        fboW = w
        fboH = h
        pixelBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
    }

    private fun destroyFbo() {
        if (fbo != 0) {
            glDeleteFramebuffers(fbo)
            fbo = 0
        }
        if (fboTex != 0) {
            glDeleteTextures(fboTex)
            fboTex = 0
        }
        if (fboDepth != 0) {
            glDeleteRenderbuffers(fboDepth)
            fboDepth = 0
        }
        fboW = 0
        fboH = 0
        pixelBuffer = null
    }

    private fun rgbaToImageBitmap(buffer: ByteBuffer, w: Int, h: Int): ImageBitmap {
        // OpenGL reads bottom-up; flip vertically while copying into Skia-friendly bytes.
        val src = ByteArray(w * h * 4)
        buffer.rewind()
        buffer.get(src)
        val flipped = ByteArray(src.size)
        val stride = w * 4
        for (y in 0 until h) {
            val srcRow = (h - 1 - y) * stride
            val dstRow = y * stride
            System.arraycopy(src, srcRow, flipped, dstRow, stride)
        }
        // projectM's FBO leaves alpha undefined (often 0); force opaque so the
        // Compose Image is visible instead of blending to transparent.
        var i = 3
        while (i < flipped.size) {
            flipped[i] = 0xFF.toByte()
            i += 4
        }
        val image = Image.makeRaster(
            imageInfo = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.OPAQUE),
            bytes = flipped,
            rowBytes = stride,
        )
        return try {
            image.toComposeImageBitmap()
        } finally {
            image.close()
        }
    }

    private fun applyPreset(ptr: Long, force: Boolean): Boolean {
        val path = presetPath.get()
        val body = presetData.get()
        when {
            !path.isNullOrBlank() && (force || path != lastLoadedPath) -> {
                VisualizerSentinelBridge.beginLoadFromPath(path)
                ProjectMNative.nativeLoadPresetFile(ptr, path, false)
                lastLoadedPath = path
                lastLoadedData = null
                return true
            }
            !body.isNullOrBlank() && (force || body != lastLoadedData) -> {
                VisualizerSentinelBridge.beginLoadRaw()
                ProjectMNative.nativeLoadPresetData(ptr, body, false)
                lastLoadedData = body
                lastLoadedPath = null
                return true
            }
            force && path.isNullOrBlank() && body.isNullOrBlank() -> {
                ProjectMNative.nativeLoadPresetFile(ptr, "idle://", false)
                return true
            }
        }
        return false
    }
}
