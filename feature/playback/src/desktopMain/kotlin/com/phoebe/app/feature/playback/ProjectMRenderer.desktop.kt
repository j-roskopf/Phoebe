package com.phoebe.app.feature.playback

actual object ProjectMRenderer {
    /**
     * Only `resolveProjectMLibraryDir` proves the libs are present. A bare
     * `native/projectm` existence check is not a signal — a fresh checkout ships
     * `native/projectm/patches/`, so it reported available with nothing built and
     * the host then threw out of composition instead of falling back to artwork.
     */
    actual fun isNativeAvailable(): Boolean =
        resolveProjectMLibraryDirOrNull()?.let { dir ->
            dir.listFiles()?.any { it.name.contains("PhoebeProjectM") || it.name.contains("projectM") } == true
        } == true
}
