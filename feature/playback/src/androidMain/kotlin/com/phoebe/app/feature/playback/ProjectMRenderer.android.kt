package com.phoebe.app.feature.playback

actual object ProjectMRenderer {
    /**
     * Reports whether libprojectM is actually packaged in this APK. Hardcoding `true`
     * here defeats the artwork fallback and leaves a black GL surface when the .so is
     * missing (e.g. scripts/build-projectm.sh was never run for this ABI).
     */
    actual fun isNativeAvailable(): Boolean = ensureProjectMLoaded()
}

/** NDK STL first — libprojectM-4 is linked against it. Cached by [ProjectMNative]. */
internal fun ensureProjectMLoaded(): Boolean {
    runCatching { System.loadLibrary("c++_shared") }
    return ProjectMNative.tryEnsureLoaded(libraryDir = null)
}
