package com.phoebe.app.feature.playback

actual object ProjectMRenderer {
    /**
     * A resolved dir must contain the Phoebe JNI shim **and** that shim must load.
     * Bare `projectM-4.dll` (without `PhoebeProjectM`) is not enough — treating it
     * as available used to mount the host and paint an empty black rectangle.
     */
    actual fun isNativeAvailable(): Boolean {
        val dir = resolveProjectMLibraryDirOrNull() ?: return false
        return ProjectMNative.tryEnsureLoaded(dir.absolutePath)
    }
}
