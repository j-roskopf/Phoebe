package com.phoebe.app.feature.playback

import androidx.compose.ui.graphics.ImageBitmap
import com.phoebe.app.platform.PhoebeLog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import java.nio.ByteBuffer
import org.lwjgl.egl.EGL10
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_RENDERER
import org.lwjgl.opengl.GL11.glGetString
import org.lwjgl.system.FunctionProvider
import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.memASCII
import org.lwjgl.system.MemoryUtil.memAddress

/**
 * Renders projectM on a private EGL pbuffer thread and hands frames to Compose.
 *
 * A heavyweight [org.lwjgl.opengl.awt.AWTGLCanvas] inside the Compose window is
 * what Linux (NVIDIA + Wayland/XWayland) shows as a black rectangle: interop
 * blending cuts that canvas out of the Skia layer, and the canvas is cleared
 * black because the picture lives in an FBO. The same native window also takes
 * focus every time it swaps, so the visualizer preset menu is shown and
 * dismissed many times a second. An EGL pbuffer is not a window, so it neither
 * punches a hole nor fights the popup for focus. GLX is not used: LWJGL cannot
 * query a GLX version on this NVIDIA + Wayland setup, while an EGL device
 * pbuffer can. That pbuffer has to be made current before the Compose window's
 * Skiko OpenGL context exists; afterwards eglMakeCurrent fails and the surface
 * stays empty. [prewarmLinuxProjectMGl] does that at process start.
 */
internal class ProjectMOffscreenSession(
    private val libraryDir: File,
    private val onFrame: (ImageBitmap) -> Unit,
) {
    private val disposed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val startupErrorRef = AtomicReference<String?>(null)
    private val latestFrame = AtomicReference<ImageBitmap?>(null)
    private val publishPosted = AtomicBoolean(false)
    private val core = ProjectMGlCore { bitmap ->
        latestFrame.set(bitmap)
        if (publishPosted.compareAndSet(false, true)) {
            SwingUtilities.invokeLater {
                publishPosted.set(false)
                if (disposed.get()) return@invokeLater
                latestFrame.getAndSet(null)?.let(onFrame)
            }
        }
    }
    private var finished: CountDownLatch? = null

    fun setTargetPixelSize(widthPx: Int, heightPx: Int) {
        core.setTargetPixelSize(widthPx, heightPx)
    }

    fun setPresetFile(path: String?) {
        core.setPresetFile(path)
    }

    fun setPresetData(data: String?) {
        core.setPresetData(data)
    }

    fun setLocked(value: Boolean) {
        core.setLocked(value)
    }

    fun setPlaying(value: Boolean) {
        core.setPlaying(value)
    }

    fun successfulFrameCount(): Long = core.successfulFrameCount()

    fun startupError(): String? = startupErrorRef.get()

    fun start() {
        if (finished != null || disposed.get()) return
        ProjectMNative.ensureLoaded(libraryDir.absolutePath)
        val done = CountDownLatch(1)
        finished = done
        runCatching {
            LinuxProjectMGl.submit {
                try {
                    renderLoop()
                } finally {
                    done.countDown()
                }
            }
        }.onFailure { error ->
            done.countDown()
            fail(error.stackTraceToString())
        }
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        core.markDisposed()
        finished?.await(2, TimeUnit.SECONDS)
    }

    private fun renderLoop() {
        PhoebeLog.d("ProjectM") { "offscreen GL ${LinuxProjectMGl.renderer}" }
        if (!core.initGl()) {
            core.releaseGl()
            fail("projectM initGl failed")
            return
        }
        while (!disposed.get() && !core.isDisposed()) {
            val started = System.nanoTime()
            val rendered = runCatching { core.render() }.getOrElse { error ->
                core.releaseGl()
                fail("render failed: ${error.message}")
                return
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val sleepMs = if (rendered) (16L - elapsedMs).coerceAtLeast(1L) else 16L
            Thread.sleep(sleepMs)
        }
        core.releaseGl()
    }

    private fun fail(reason: String) {
        startupErrorRef.compareAndSet(null, reason)
        System.err.println("ProjectM Linux offscreen GL failed: $reason")
        PhoebeLog.d("ProjectM") { "Linux offscreen GL failed: $reason" }
        if (failed.compareAndSet(false, true)) {
            SwingUtilities.invokeLater { ProjectMHostGate.markFailed() }
        }
    }
}

/**
 * One EGL context for the process, created before the Skiko window.
 * [eglMakeCurrent] on a fresh thread fails once that window's OpenGL context exists.
 */
internal object LinuxProjectMGl {
    private val ready = CountDownLatch(1)
    private val jobs = LinkedBlockingQueue<() -> Unit>()
    private val startupError = AtomicReference<Throwable?>(null)
    private var thread: Thread? = null
    private var pbuffer: LinuxEglPbuffer? = null

    val renderer: String get() = pbuffer?.renderer.orEmpty()

    fun installAndWait() {
        startThread()
        check(ready.await(8, TimeUnit.SECONDS)) { "projectM EGL prewarm timed out" }
        startupError.get()?.let { throw it }
    }

    fun submit(job: () -> Unit) {
        installAndWait()
        jobs.offer(job)
    }

    private fun startThread() {
        synchronized(this) {
            if (thread != null) return
            val worker = Thread({
                val gl = runCatching { LinuxEglPbuffer.open() }.getOrElse { error ->
                    startupError.set(error)
                    ready.countDown()
                    return@Thread
                }
                pbuffer = gl
                System.err.println("ProjectM Linux offscreen GL ready: ${gl.renderer}")
                ready.countDown()
                while (true) {
                    val job = jobs.take()
                    runCatching {
                        gl.makeCurrent()
                        job()
                    }.onFailure { error ->
                        System.err.println("ProjectM Linux offscreen GL job failed: ${error.message}")
                    }
                }
            }, "Phoebe-projectm-offscreen")
            worker.isDaemon = true
            thread = worker
            worker.start()
        }
    }
}

/** Create the Linux projectM GL context before the Compose window's Skiko context. */
fun prewarmLinuxProjectMGl() {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    if (!os.contains("linux")) return
    runCatching { LinuxProjectMGl.installAndWait() }
        .onFailure { error ->
            System.err.println("ProjectM Linux offscreen GL prewarm failed: ${error.message}")
        }
}

/**
 * OpenGL 3.3 core context on a 16×16 EGL pbuffer. Device displays are preferred
 * over [EGL10.eglGetDisplay] because the default display on hybrid NVIDIA/Wayland
 * machines comes back as llvmpipe. Nothing here is an X11 window.
 */
private class LinuxEglPbuffer private constructor(
    private val display: Long,
    private val context: Long,
    private val surface: Long,
    private val terminateDisplay: Boolean,
    var renderer: String,
) : AutoCloseable {
    fun makeCurrent() {
        check(EGL10.eglMakeCurrent(display, surface, surface, context)) {
            "eglMakeCurrent failed ${eglError()}"
        }
        bindGlFunctions()
    }

    override fun close() {
        runCatching { EGL10.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT) }
        if (surface != 0L) runCatching { EGL10.eglDestroySurface(display, surface) }
        if (context != 0L) runCatching { EGL10.eglDestroyContext(display, context) }
        if (terminateDisplay && display != 0L) runCatching { EGL10.eglTerminate(display) }
    }

    companion object {
        private const val EGL_PLATFORM_DEVICE_EXT = 0x313F
        private const val EGL_OPENGL_API = 0x30A2
        private const val EGL_OPENGL_BIT = 0x0008
        private const val EGL_SURFACE_TYPE = 0x3033
        private const val EGL_PBUFFER_BIT = 0x0001
        private const val EGL_RENDERABLE_TYPE = 0x3040
        private const val EGL_RED_SIZE = 0x3024
        private const val EGL_GREEN_SIZE = 0x3023
        private const val EGL_BLUE_SIZE = 0x3022
        private const val EGL_ALPHA_SIZE = 0x3021
        private const val EGL_NONE = 0x3038
        private const val EGL_WIDTH = 0x3057
        private const val EGL_HEIGHT = 0x3056
        private const val EGL_CONTEXT_MAJOR_VERSION = 0x3098
        private const val EGL_CONTEXT_MINOR_VERSION = 0x30FB
        private const val EGL_CONTEXT_OPENGL_PROFILE_MASK = 0x30FD
        private const val EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT = 0x00000001

        fun open(): LinuxEglPbuffer {
            runCatching { org.lwjgl.egl.EGL.create() }.onFailure { error ->
                if (error.message?.contains("already") != true) throw error
            }
            var software: LinuxEglPbuffer? = null
            var lastError = "no EGL device accepted an OpenGL 3.3 pbuffer"
            for (device in queryDevices()) {
                val created = runCatching { createForDevice(device) }.getOrElse { error ->
                    lastError = error.message ?: error.toString()
                    null
                } ?: continue
                if (isSoftwareRenderer(created.renderer)) {
                    if (software == null) software = created else created.close()
                } else {
                    software?.close()
                    created.makeCurrent()
                    return created
                }
            }
            software?.let {
                it.makeCurrent()
                return it
            }
            return runCatching { createDefaultDisplay() }.getOrElse { error ->
                error("EGL pbuffer failed: ${error.message ?: lastError}")
            }
        }

        private fun queryDevices(): LongArray {
            val query = EGL10.eglGetProcAddress("eglQueryDevicesEXT")
            if (query == 0L) return longArrayOf()
            MemoryStack.stackPush().use { stack ->
                val devices = stack.mallocPointer(8)
                val count = stack.mallocInt(1)
                if (JNI.callPPI(8, memAddress(devices), memAddress(count), query) == 0) {
                    return longArrayOf()
                }
                val n = count.get(0).coerceIn(0, 8)
                return LongArray(n) { devices.get(it) }
            }
        }

        private fun createForDevice(device: Long): LinuxEglPbuffer {
            val getPlatform = EGL10.eglGetProcAddress("eglGetPlatformDisplayEXT")
            check(getPlatform != 0L) { "eglGetPlatformDisplayEXT missing" }
            val display = JNI.callPPP(EGL_PLATFORM_DEVICE_EXT, device, 0L, getPlatform)
            check(display != 0L) { "eglGetPlatformDisplay(device) failed ${eglError()}" }
            return createPbuffer(display, terminateDisplay = true)
        }

        private fun createDefaultDisplay(): LinuxEglPbuffer {
            val display = EGL10.eglGetDisplay(0L)
            check(display != 0L) { "eglGetDisplay failed ${eglError()}" }
            return createPbuffer(display, terminateDisplay = false)
        }

        private fun createPbuffer(display: Long, terminateDisplay: Boolean): LinuxEglPbuffer {
            try {
                MemoryStack.stackPush().use { stack ->
                    val major = stack.mallocInt(1)
                    val minor = stack.mallocInt(1)
                    check(EGL10.eglInitialize(display, major, minor)) {
                        "eglInitialize failed ${eglError()}"
                    }
                    bindOpenGlApi()
                    val configAttribs = stack.ints(
                        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
                        EGL_RED_SIZE, 8,
                        EGL_GREEN_SIZE, 8,
                        EGL_BLUE_SIZE, 8,
                        EGL_ALPHA_SIZE, 8,
                        EGL_NONE,
                    )
                    val configs = stack.mallocPointer(1)
                    val numConfigs = stack.mallocInt(1)
                    check(EGL10.eglChooseConfig(display, configAttribs, configs, numConfigs) && numConfigs.get(0) > 0) {
                        "eglChooseConfig failed ${eglError()}"
                    }
                    val config = configs.get(0)
                    val contextAttribs = stack.ints(
                        EGL_CONTEXT_MAJOR_VERSION, 3,
                        EGL_CONTEXT_MINOR_VERSION, 3,
                        EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
                        EGL_NONE,
                    )
                    val context = EGL10.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, contextAttribs)
                    check(context != 0L) { "eglCreateContext failed ${eglError()}" }
                    val surfaceAttribs = stack.ints(EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE)
                    val surface = EGL10.eglCreatePbufferSurface(display, config, surfaceAttribs)
                    check(surface != 0L) {
                        EGL10.eglDestroyContext(display, context)
                        "eglCreatePbufferSurface failed ${eglError()}"
                    }
                    val created = LinuxEglPbuffer(display, context, surface, terminateDisplay, renderer = "")
                    created.makeCurrent()
                    created.renderer = glString(GL_RENDERER)
                    return created
                }
            } catch (error: Throwable) {
                if (terminateDisplay) runCatching { EGL10.eglTerminate(display) }
                throw error
            }
        }

        private fun bindOpenGlApi() {
            val bind = EGL10.eglGetProcAddress("eglBindAPI")
            check(bind != 0L) { "eglBindAPI missing" }
            check(JNI.callI(EGL_OPENGL_API, bind) == 1) { "eglBindAPI(OPENGL) failed ${eglError()}" }
        }

        private fun bindGlFunctions() {
            if (GL.getFunctionProvider() == null) {
                GL.create(EglFunctionProvider)
            }
            GL.createCapabilities()
        }

        private fun glString(name: Int): String = glGetString(name).orEmpty()

        private fun isSoftwareRenderer(name: String): Boolean {
            val normalized = name.lowercase()
            return "llvmpipe" in normalized || "softpipe" in normalized || "swrast" in normalized
        }

        private fun eglError(): String = "0x${EGL10.eglGetError().toString(16)}"
    }
}

private object EglFunctionProvider : FunctionProvider {
    override fun getFunctionAddress(functionName: ByteBuffer): Long =
        EGL10.eglGetProcAddress(memASCII(functionName))
}
