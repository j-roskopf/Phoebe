package com.phoebe.app.feature.playback

import io.github.erkko68.filament.ffm.FilamentLoader
import io.github.erkko68.filament.filamat.Filamat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxFilamentRuntimeTest {
    @Test
    fun directoriesPreferBundledThenPackagedThenSystemPaths() {
        val directories = linuxFilamentRuntimeDirectories(
            osArch = "amd64",
            appResourcesDir = "/app/resources",
            bundledExtractDir = File("/cache/libcxx"),
            extraLibDirs = listOf(File("/extra")),
        )

        assertEquals(File("/extra"), directories.first())
        assertEquals(File("/cache/libcxx"), directories[1])
        assertEquals(File("/app/resources/linux-x64"), directories[2])
        assertEquals(File("/app/resources/filament-linux-libcxx"), directories[3])
        assertEquals(File("/usr/lib/x86_64-linux-gnu"), directories[5])
        assertTrue(directories.contains(File("/usr/lib/llvm-18/lib")))
    }

    @Test
    fun arm64UsesAarch64MultiarchAndResourceDir() {
        assertEquals("linux-arm64", linuxFilamentResourceDirName("aarch64"))
        assertEquals("aarch64-linux-gnu", linuxMultiarchTriplet("arm64"))
        val directories = linuxFilamentRuntimeDirectories(
            osArch = "aarch64",
            appResourcesDir = "/app/resources",
            bundledExtractDir = null,
        )
        assertEquals(File("/app/resources/linux-arm64"), directories.first())
        assertTrue(directories.contains(File("/usr/lib/aarch64-linux-gnu")))
        assertFalse(directories.contains(File("/usr/lib/x86_64-linux-gnu")))
    }

    @Test
    fun cacheDirUsesDebugFolderWhenDebugging() {
        assertEquals(
            File("/home/joe/.cache/phoebe-debug/native/libcxx"),
            linuxFilamentLibcxxCacheDir(cacheHome = "/home/joe/.cache", debug = true),
        )
        assertEquals(
            File("/home/joe/.cache/phoebe/native/libcxx"),
            linuxFilamentLibcxxCacheDir(cacheHome = "/home/joe/.cache", debug = false),
        )
    }

    @Test
    fun nonLinuxRuntimeIsTreatedAsReady() {
        assertTrue(
            loadLinuxFilamentRuntimeDependencies(
                osName = "Mac OS X",
                extraLibDirs = emptyList(),
            ),
        )
    }

    @Test
    fun linuxLoadsBundledLibcxxRuntime() {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        assertTrue(loadLinuxFilamentRuntimeDependencies())
        FilamentLoader.load()
        Filamat.init()
    }
}
