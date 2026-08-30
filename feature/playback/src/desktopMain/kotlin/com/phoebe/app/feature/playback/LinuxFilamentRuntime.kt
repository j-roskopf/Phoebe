package com.phoebe.app.feature.playback

import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.isDebugBuild
import java.io.File

internal const val LinuxFilamentLibcxxResourceDir = "filament-linux-libcxx"
internal const val LinuxLibUnwind = "libunwind.so.1"
internal const val LinuxLibcxxAbi = "libc++abi.so.1"
internal const val LinuxLibcxx = "libc++.so.1"

private val linuxFilamentRuntimeLibs = listOf(LinuxLibUnwind, LinuxLibcxxAbi, LinuxLibcxx)

internal fun loadLinuxFilamentRuntimeDependencies(
    osName: String = System.getProperty("os.name").orEmpty(),
    osArch: String? = System.getProperty("os.arch"),
    appResourcesDir: String? = System.getProperty("compose.application.resources.dir")
        ?.trim()
        ?.takeIf { it.isNotEmpty() },
    bundledExtractDir: File = linuxFilamentLibcxxCacheDir(),
    extraLibDirs: List<File> = emptyList(),
): Boolean {
    if (!osName.lowercase().contains("linux")) return true

    extractBundledLinuxLibcxx(bundledExtractDir)
    val directories = linuxFilamentRuntimeDirectories(
        osArch = osArch,
        appResourcesDir = appResourcesDir,
        bundledExtractDir = bundledExtractDir,
        extraLibDirs = extraLibDirs,
    )
    for (directory in directories) {
        if (loadLinuxFilamentRuntimeFrom(directory)) {
            PhoebeLog.d("FilamentVisualizer") {
                "Loaded Linux Filament C++ runtime from ${directory.absolutePath}"
            }
            return true
        }
    }

    PhoebeLog.d("FilamentVisualizer") {
        "Linux Filament C++ runtime (libc++.so.1) was not found"
    }
    return false
}

internal fun linuxFilamentRuntimeDirectories(
    osArch: String?,
    appResourcesDir: String?,
    bundledExtractDir: File?,
    extraLibDirs: List<File> = emptyList(),
): List<File> {
    val multiarch = linuxMultiarchTriplet(osArch)
    val resourceDirName = linuxFilamentResourceDirName(osArch)
    return buildList {
        extraLibDirs.forEach { add(it) }
        if (bundledExtractDir != null) add(bundledExtractDir)
        if (appResourcesDir != null) {
            add(File(appResourcesDir, resourceDirName))
            add(File(appResourcesDir, LinuxFilamentLibcxxResourceDir))
            add(File(appResourcesDir))
        }
        add(File("/usr/lib/$multiarch"))
        add(File("/usr/lib64"))
        add(File("/usr/lib"))
        add(File("/lib/$multiarch"))
        add(File("/lib64"))
        add(File("/lib"))
        for (llvmVersion in 14..22) {
            add(File("/usr/lib/llvm-$llvmVersion/lib"))
        }
    }.distinctBy { it.absolutePath }
}

internal fun linuxFilamentResourceDirName(osArch: String?): String =
    when (osArch) {
        "aarch64", "arm64" -> "linux-arm64"
        else -> "linux-x64"
    }

internal fun linuxMultiarchTriplet(osArch: String?): String =
    when (osArch) {
        "aarch64", "arm64" -> "aarch64-linux-gnu"
        else -> "x86_64-linux-gnu"
    }

private fun loadLinuxFilamentRuntimeFrom(directory: File): Boolean {
    val cxx = File(directory, LinuxLibcxx)
    if (!cxx.isFile) return false
    linuxFilamentRuntimeLibs
        .map { File(directory, it) }
        .filter { it.isFile }
        .forEach { loadNativeLibrary(it) }
    return loadNativeLibrary(cxx)
}

private fun loadNativeLibrary(file: File): Boolean {
    if (dlopenGlobal(file.absolutePath)) return true
    return try {
        System.load(file.absolutePath)
        true
    } catch (error: UnsatisfiedLinkError) {
        val message = error.message.orEmpty()
        message.contains("already loaded")
    }
}

internal fun linuxFilamentLibcxxCacheDir(
    cacheHome: String? = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("user.home")?.plus("/.cache"),
    debug: Boolean = isDebugBuild(),
): File {
    val cacheFolder = if (debug) "phoebe-debug" else "phoebe"
    return File(cacheHome ?: System.getProperty("java.io.tmpdir"), "$cacheFolder/native/libcxx")
}

internal fun extractBundledLinuxLibcxx(targetDir: File) {
    if (!targetDir.exists() && !targetDir.mkdirs() && !targetDir.isDirectory) return
    for (name in linuxFilamentRuntimeLibs) {
        val dest = File(targetDir, name)
        if (dest.isFile && dest.length() > 0L) continue
        val stream = bundledLinuxLibcxxStream(name) ?: continue
        dest.parentFile?.mkdirs()
        runCatching {
            stream.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        }.onFailure {
            dest.delete()
        }
    }
}

private fun bundledLinuxLibcxxStream(name: String) =
    Thread.currentThread().contextClassLoader
        ?.getResourceAsStream("$LinuxFilamentLibcxxResourceDir/$name")
        ?: LinuxFilamentRuntimeResources::class.java.getResourceAsStream(
            "/$LinuxFilamentLibcxxResourceDir/$name",
        )

private object LinuxFilamentRuntimeResources
