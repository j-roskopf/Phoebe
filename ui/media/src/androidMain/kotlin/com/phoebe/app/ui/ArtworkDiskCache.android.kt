package com.phoebe.app.ui

import com.phoebe.app.AndroidContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal actual fun defaultArtworkDiskCacheBackend(): ArtworkDiskCacheBackend =
    AndroidArtworkDiskCacheBackend()

private class AndroidArtworkDiskCacheBackend : ArtworkDiskCacheBackend {
    @Volatile
    private var lastTrimTimeMs = 0L

    private val root: File
        get() = AndroidContextHolder.application.cacheDir
            .resolve(ArtworkDiskCacheDirectory)
            .also { it.mkdirs() }

    override suspend fun read(fetchUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = root.resolve(cacheFileName(fetchUrl))
        if (!file.isFile) return@withContext null
        runCatching {
            file.setLastModified(System.currentTimeMillis())
            file.readBytes().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    override suspend fun write(fetchUrl: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        withContext(Dispatchers.IO) {
            val directory = root
            val target = directory.resolve(cacheFileName(fetchUrl))
            val temp = directory.resolve("${target.name}.tmp-${System.nanoTime()}")
            try {
                temp.writeBytes(bytes)
                if (target.exists()) {
                    target.delete()
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                target.setLastModified(System.currentTimeMillis())
                val now = System.currentTimeMillis()
                if (now - lastTrimTimeMs > TrimIntervalMs) {
                    lastTrimTimeMs = now
                    trimToMaxBytes(directory)
                }
            } catch (error: Throwable) {
                temp.delete()
                throw error
            }
        }
    }

    private fun trimToMaxBytes(directory: File) {
        val files = directory.listFiles()
            ?.filter { it.isFile && !it.name.contains(".tmp-") }
            .orEmpty()
        var totalBytes = files.sumOf { it.length() }
        if (totalBytes <= MaxArtworkDiskCacheBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (totalBytes <= MaxArtworkDiskCacheBytes) return@forEach
            val size = file.length()
            if (file.delete()) {
                totalBytes -= size
            }
        }
    }

    private fun cacheFileName(fetchUrl: String): String {
        val extension = fetchUrl.substringBefore('?')
            .substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
            ?: "jpg"
        return "art-${fetchUrl.stableArtworkDiskHash()}.$extension"
    }

    private fun String.stableArtworkDiskHash(): String {
        var hash = 1125899906842597L
        forEach { c -> hash = (hash * 31) + c.code }
        return hash.toULong().toString(16)
    }
}

private const val ArtworkDiskCacheDirectory = "phoebe-artwork"
private const val MaxArtworkDiskCacheBytes = 128L * 1024L * 1024L
private const val TrimIntervalMs = 5L * 60L * 1000L
