package com.phoebe.app.feature.playback

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.phoebe.app.player.VisualizerPcmBus
import com.phoebe.app.player.VisualizerPcmSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
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
import org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8
import org.lwjgl.opengl.GL30.GL_DEPTH_STENCIL_ATTACHMENT
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE
import org.lwjgl.opengl.GL30.GL_RENDERBUFFER
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

/**
 * projectM instance plus the FBO readback that turns a frame into an [ImageBitmap].
 *
 * Every GL method here requires the caller's OpenGL context to already be current
 * on this thread. PCM and preset setters are safe from other threads.
 */
internal class ProjectMGlCore(
    private val onFrame: (ImageBitmap) -> Unit,
) : VisualizerPcmSink {
    private val handle = AtomicLong(0L)
    private val presetPath = AtomicReference<String?>(null)
    private val presetData = AtomicReference<String?>(null)
    private val locked = AtomicBoolean(false)
    private val playing = AtomicBoolean(true)
    private val frameCount = AtomicLong(0L)
    private val disposed = AtomicBoolean(false)
    private val targetWidth = AtomicInteger(0)
    private val targetHeight = AtomicInteger(0)
    private var lastLoadedPath: String? = null
    private var lastLoadedData: String? = null

    private var fbo = 0
    private var fboTex = 0
    private var fboDepth = 0
    private var fboW = 0
    private var fboH = 0
    private var pixelBuffer: ByteBuffer? = null

    init {
        VisualizerPcmBus.addSink(this)
    }

    fun isDisposed(): Boolean = disposed.get()

    fun successfulFrameCount(): Long = frameCount.get()

    fun markDisposed() {
        if (!disposed.compareAndSet(false, true)) return
        VisualizerPcmBus.removeSink(this)
    }

    fun setTargetPixelSize(widthPx: Int, heightPx: Int) {
        targetWidth.set(widthPx.coerceAtLeast(0))
        targetHeight.set(heightPx.coerceAtLeast(0))
    }

    fun setPresetFile(path: String?) {
        presetPath.set(path)
        presetData.set(null)
    }

    fun setPresetData(data: String?) {
        presetData.set(data)
        presetPath.set(null)
    }

    fun setLocked(value: Boolean) {
        locked.set(value)
        val ptr = handle.get()
        if (ptr != 0L) {
            ProjectMNative.nativeSetPresetLocked(ptr, value)
        }
    }

    fun setPlaying(value: Boolean) {
        playing.set(value)
    }

    override fun onPcm(samples: FloatArray, channels: Int, sampleRateHz: Float) {
        if (!playing.get() || disposed.get()) return
        val ptr = handle.get()
        if (ptr == 0L || samples.isEmpty()) return
        ProjectMNative.nativeAddPcmFloat(ptr, samples, channels.coerceIn(1, 2))
    }

    /** Call with a current OpenGL 3.3+ context. Returns false when projectM cannot start. */
    fun initGl(): Boolean {
        val ready = runCatching {
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
        return ready
    }

    /**
     * Draw one frame into an FBO and publish it. No-ops until [initGl] succeeds.
     * When playback is paused, publishes once after a preset change and then holds.
     *
     * @return true when a frame was read back (the AWT host still needs to clear its
     * on-screen canvas). False when this tick was skipped.
     */
    fun render(): Boolean {
        if (disposed.get()) return false
        val ptr = handle.get()
        if (ptr == 0L) return false
        val presetChanged = applyPreset(ptr, force = false)
        if (!playing.get() && !presetChanged) return false

        val w = targetWidth.get()
        val h = targetHeight.get()
        if (w <= 1 || h <= 1) return false

        ensureFbo(w, h)
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        ProjectMNative.nativeSetWindowSize(ptr, w, h)
        glViewport(0, 0, w, h)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        ProjectMNative.nativeRenderFrame(ptr)

        val buffer = pixelBuffer ?: return false
        buffer.clear()
        // projectM only rebinds the draw target; make sure the read target is
        // still our FBO before pulling the composited frame back out.
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)

        if (!disposed.get()) {
            onFrame(rgbaToImageBitmap(buffer, w, h))
        }

        val frames = frameCount.incrementAndGet()
        if (frames % 3L == 0L) {
            VisualizerSentinelBridge.onSuccessfulFrame()
        }
        glGetError() // clear sticky errors from incomplete FBOs during resize
        return true
    }

    /** Destroy the FBO and projectM handle. The GL context must be current. */
    fun releaseGl() {
        destroyFbo()
        val ptr = handle.getAndSet(0L)
        if (ptr != 0L) {
            runCatching { ProjectMNative.nativeDestroy(ptr) }
        }
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
