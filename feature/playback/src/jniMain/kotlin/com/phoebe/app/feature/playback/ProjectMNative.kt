package com.phoebe.app.feature.playback

import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.logDetail
import java.io.File

/**
 * Thin JNI façade over libprojectM. Every render/create call must run on a thread
 * that already has a current OpenGL context.
 */
object ProjectMNative {
    @Volatile
    private var loaded = false

    @Volatile
    private var unavailable = false

    /** Why the last load attempt failed, for the caller to surface instead of a bare throw. */
    @Volatile
    var loadFailure: String? = null
        private set

    fun isAvailable(): Boolean = loaded && !unavailable

    /** Load native libs; returns false instead of throwing when unavailable. */
    fun tryEnsureLoaded(libraryDir: String? = null): Boolean {
        if (loaded) return true
        if (unavailable) return false
        synchronized(this) {
            if (loaded) return true
            if (unavailable) return false
            return runCatching {
                val searchDirs = searchDirs(libraryDir)
                loadCompanionLibraries(searchDirs)
                loadNamed("projectM-4", searchDirs)
                loadNamed("PhoebeProjectM", searchDirs)
                loaded = true
                true
            }.getOrElse { error ->
                unavailable = true
                loadFailure = error.logDetail()
                PhoebeLog.d("ProjectM") {
                    "projectM native load failed (libraryDir=$libraryDir): ${error.logDetail()}"
                }
                false
            }
        }
    }

    fun ensureLoaded(libraryDir: String? = null) {
        if (!tryEnsureLoaded(libraryDir)) {
            error("projectM native libraries not loaded: ${loadFailure ?: "no library directory resolved"}")
        }
    }

    /**
     * Windows CMake installs the import library into `lib/` but the runtime DLL into `bin/`
     * (projectM's `PROJECTM_RUNTIME_DIR`), so a lib-only search finds `PhoebeProjectM.dll`
     * and then fails to find `projectM-4.dll` beside it. Search the sibling `bin/` too.
     */
    private fun searchDirs(libraryDir: String?): List<File> {
        if (libraryDir.isNullOrBlank()) return emptyList()
        val dir = File(libraryDir)
        return listOfNotNull(
            dir,
            dir.parentFile?.resolve("bin"),
        ).filter { it.isDirectory }
    }

    private fun loadNamed(name: String, searchDirs: List<File>) {
        val fileNames = listOf(
            System.mapLibraryName(name),
            "lib$name.dylib",
            "lib$name.so",
            "$name.dll",
        )
        for (dir in searchDirs) {
            for (fileName in fileNames) {
                val file = File(dir, fileName)
                if (file.isFile) {
                    System.load(file.absolutePath)
                    return
                }
            }
        }
        // Android resolves both libs out of the APK's jniLibs, where there is no directory
        // to hand us; the platform loader already knows where to look.
        System.loadLibrary(name)
    }

    /**
     * Preload third-party runtime libraries shipped beside projectM (GLEW on Windows).
     *
     * Windows does not add a DLL's own directory to the search path used for *its* imports,
     * so `System.load("…/projectM-4.dll")` cannot find `glew32.dll` next to it. Loading each
     * one by absolute path first puts it in the process module list, where the subsequent
     * import resolves by base name. Failures are ignored: on macOS/Linux this is a no-op, and
     * an unrelated DLL that will not load on its own must not block projectM.
     */
    private fun loadCompanionLibraries(searchDirs: List<File>) {
        val ours = setOf("projectM-4", "PhoebeProjectM")
        for (dir in searchDirs) {
            val companions = dir.listFiles()?.filter { candidate ->
                candidate.isFile &&
                    candidate.name.endsWith(".dll", ignoreCase = true) &&
                    ours.none { candidate.name.contains(it, ignoreCase = true) }
            }.orEmpty()
            for (companion in companions) {
                runCatching { System.load(companion.absolutePath) }
                    .onFailure {
                        PhoebeLog.d("ProjectM") { "Skipped companion ${companion.name}: ${it.logDetail()}" }
                    }
            }
        }
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
