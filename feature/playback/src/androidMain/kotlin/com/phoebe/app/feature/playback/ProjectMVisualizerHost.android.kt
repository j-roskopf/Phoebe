package com.phoebe.app.feature.playback

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.player.VisualizerPcmBus
import com.phoebe.app.player.VisualizerPcmSink
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

@Composable
actual fun ProjectMVisualizerHost(
    presetPath: String?,
    presetData: String?,
    locked: Boolean,
    isPlaying: Boolean,
    modifier: Modifier,
    suspendRendering: Boolean,
) {
    val nativeReady = remember {
        ensureProjectMLoaded().also { ok ->
            if (!ok) {
                PhoebeLog.d("ProjectM") {
                    "libprojectM / PhoebeProjectM not packaged — visualizer disabled. " +
                        "Build with scripts/build-projectm.sh android-<abi> and sync jniLibs."
                }
            }
        }
    }

    if (!nativeReady) {
        Box(modifier.fillMaxSize().background(Color.Black))
        return
    }

    val renderer = remember { ProjectMAndroidRenderer() }

    DisposableEffect(renderer) {
        onDispose { renderer.dispose() }
    }

    DisposableEffect(presetPath, presetData, locked, isPlaying) {
        when {
            !presetPath.isNullOrBlank() -> renderer.setPresetFile(presetPath)
            !presetData.isNullOrBlank() -> renderer.setPresetData(presetData)
            else -> renderer.setPresetFile("idle://")
        }
        renderer.setLocked(locked)
        renderer.setPlaying(isPlaying && !suspendRendering)
        onDispose { }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            GLSurfaceView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setEGLContextClientVersion(3)
                // Prefer a lighter EGL config when available (16-bit depth is fine).
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                // Keep the last frame when the view pauses instead of tearing down GL.
                preserveEGLContextOnPause = true
                renderer.attach(this)
            }
        },
        update = { view ->
            renderer.attach(view)
            renderer.setSuspended(suspendRendering)
            if (!suspendRendering) {
                renderer.setPlaying(isPlaying)
            }
        },
    )
}

private class ProjectMAndroidRenderer : GLSurfaceView.Renderer, VisualizerPcmSink {
    private val handle = AtomicLong(0L)
    private val presetPath = AtomicReference<String?>(null)
    private val presetData = AtomicReference<String?>(null)
    private val locked = AtomicBoolean(false)
    private val playing = AtomicBoolean(true)
    private val suspended = AtomicBoolean(false)
    private var lastLoadedPath: String? = null
    private var lastLoadedData: String? = null
    private var width = 1
    private var height = 1
    private var surface: GLSurfaceView? = null

    init {
        VisualizerPcmBus.addSink(this)
    }

    fun attach(view: GLSurfaceView) {
        surface = view
        applyRenderMode(view)
    }

    fun setPresetFile(path: String?) {
        presetPath.set(path)
        presetData.set(null)
        // Force applyPreset to notice the change on the next GL frame.
        lastLoadedPath = null
        requestRender()
    }

    fun setPresetData(data: String?) {
        presetData.set(data)
        presetPath.set(null)
        lastLoadedData = null
        requestRender()
    }

    fun setLocked(value: Boolean) {
        locked.set(value)
        val ptr = handle.get()
        if (ptr != 0L) ProjectMNative.nativeSetPresetLocked(ptr, value)
        requestRender()
    }

    fun setPlaying(value: Boolean) {
        playing.set(value)
        surface?.let { applyRenderMode(it) }
        if (value && !suspended.get()) requestRender()
    }

    fun setSuspended(value: Boolean) {
        if (suspended.getAndSet(value) == value) return
        val view = surface ?: return
        if (value) {
            view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            view.onPause()
        } else {
            view.onResume()
            applyRenderMode(view)
            requestRender()
        }
    }

    fun dispose() {
        VisualizerPcmBus.removeSink(this)
        surface?.queueEvent {
            val ptr = handle.getAndSet(0L)
            if (ptr != 0L) runCatching { ProjectMNative.nativeDestroy(ptr) }
        }
    }

    override fun onPcm(samples: FloatArray, channels: Int, sampleRateHz: Float) {
        if (!playing.get()) return
        val ptr = handle.get()
        if (ptr == 0L || samples.isEmpty()) return
        // projectM expects the GL thread for addPCM on some builds; queue it.
        val view = surface
        if (view != null) {
            val copy = samples.copyOf()
            val ch = channels.coerceIn(1, 2)
            view.queueEvent {
                val live = handle.get()
                if (live != 0L) ProjectMNative.nativeAddPcmFloat(live, copy, ch)
            }
        } else {
            ProjectMNative.nativeAddPcmFloat(ptr, samples, channels.coerceIn(1, 2))
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (!ProjectMNative.isAvailable()) return
        val ptr = runCatching { ProjectMNative.nativeCreate() }.getOrDefault(0L)
        if (ptr == 0L) {
            PhoebeLog.d("ProjectM") { "nativeCreate failed on Android GLES" }
            return
        }
        handle.set(ptr)
        // 30fps is enough for milkdrop and roughly halves GPU/CPU vs 60.
        ProjectMNative.nativeSetFps(ptr, 30)
        ProjectMNative.nativeSetPresetDuration(ptr, 20.0)
        applyPreset(ptr, force = true)
        ProjectMNative.nativeSetPresetLocked(ptr, locked.get())
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        val ptr = handle.get()
        if (ptr != 0L) {
            ProjectMNative.nativeSetWindowSize(ptr, this.width, this.height)
        }
        GLES30.glViewport(0, 0, this.width, this.height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val ptr = handle.get()
        if (ptr == 0L) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        val presetChanged = applyPreset(ptr, force = false)
        // Paused: freeze on the last frame, but still paint once after a preset swap.
        if (!playing.get() && !presetChanged) return
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        ProjectMNative.nativeRenderFrame(ptr)
    }

    private fun applyPreset(ptr: Long, force: Boolean): Boolean {
        val path = presetPath.get()
        val body = presetData.get()
        when {
            !path.isNullOrBlank() && (force || path != lastLoadedPath) -> {
                ProjectMNative.nativeLoadPresetFile(ptr, path, false)
                lastLoadedPath = path
                lastLoadedData = null
                return true
            }
            !body.isNullOrBlank() && (force || body != lastLoadedData) -> {
                ProjectMNative.nativeLoadPresetData(ptr, body, false)
                lastLoadedData = body
                lastLoadedPath = null
                return true
            }
            force && path.isNullOrBlank() && body.isNullOrBlank() -> {
                ProjectMNative.nativeLoadPresetFile(ptr, "idle://", false)
                lastLoadedPath = "idle://"
                lastLoadedData = null
                return true
            }
        }
        return false
    }

    private fun applyRenderMode(view: GLSurfaceView) {
        if (suspended.get()) {
            view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            return
        }
        view.renderMode = if (playing.get()) {
            GLSurfaceView.RENDERMODE_CONTINUOUSLY
        } else {
            GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
    }

    private fun requestRender() {
        surface?.requestRender()
    }
}
