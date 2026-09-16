package com.phoebe.app.feature.playback

/**
 * Thin JNI façade over libprojectM. Every render/create call must run on a thread
 * that already has a current OpenGL context.
 */
object ProjectMNative {
    @Volatile
    private var loaded = false

    @Volatile
    private var unavailable = false

    fun isAvailable(): Boolean = loaded && !unavailable

    /** Load native libs; returns false instead of throwing when unavailable. */
    fun tryEnsureLoaded(libraryDir: String? = null): Boolean {
        if (loaded) return true
        if (unavailable) return false
        synchronized(this) {
            if (loaded) return true
            if (unavailable) return false
            return runCatching {
                if (!libraryDir.isNullOrBlank()) {
                    System.setProperty("java.library.path", buildLibraryPath(libraryDir))
                }
                loadProjectM(libraryDir)
                loadJni(libraryDir)
                loaded = true
                true
            }.getOrElse {
                unavailable = true
                false
            }
        }
    }

    fun ensureLoaded(libraryDir: String? = null) {
        if (!tryEnsureLoaded(libraryDir)) {
            error("projectM native libraries not loaded")
        }
    }

    private fun buildLibraryPath(libraryDir: String): String {
        val existing = System.getProperty("java.library.path").orEmpty()
        return if (existing.isBlank()) libraryDir else "$libraryDir${java.io.File.pathSeparator}$existing"
    }

    private fun loadProjectM(libraryDir: String?) {
        val candidates = buildList {
            if (!libraryDir.isNullOrBlank()) {
                add(java.io.File(libraryDir, System.mapLibraryName("projectM-4")))
                add(java.io.File(libraryDir, "libprojectM-4.dylib"))
                add(java.io.File(libraryDir, "libprojectM-4.so"))
                add(java.io.File(libraryDir, "projectM-4.dll"))
            }
        }
        for (file in candidates) {
            if (file.isFile) {
                System.load(file.absolutePath)
                return
            }
        }
        System.loadLibrary("projectM-4")
    }

    private fun loadJni(libraryDir: String?) {
        val candidates = buildList {
            if (!libraryDir.isNullOrBlank()) {
                add(java.io.File(libraryDir, System.mapLibraryName("PhoebeProjectM")))
                add(java.io.File(libraryDir, "libPhoebeProjectM.dylib"))
                add(java.io.File(libraryDir, "libPhoebeProjectM.so"))
                add(java.io.File(libraryDir, "PhoebeProjectM.dll"))
            }
        }
        for (file in candidates) {
            if (file.isFile) {
                System.load(file.absolutePath)
                return
            }
        }
        System.loadLibrary("PhoebeProjectM")
    }

    external fun nativeCreate(): Long
    external fun nativeDestroy(ptr: Long)
    external fun nativeSetWindowSize(ptr: Long, width: Int, height: Int)
    external fun nativeSetFps(ptr: Long, fps: Int)
    external fun nativeRenderFrame(ptr: Long)
    external fun nativeLoadPresetFile(ptr: Long, path: String, smooth: Boolean)
    external fun nativeLoadPresetData(ptr: Long, data: String, smooth: Boolean)
    external fun nativeAddPcmFloat(ptr: Long, samples: FloatArray, channels: Int)
    external fun nativeSetTextureSearchPaths(ptr: Long, paths: Array<String>?)
    external fun nativeSetPresetDuration(ptr: Long, seconds: Double)
    external fun nativeSetPresetLocked(ptr: Long, locked: Boolean)
}
