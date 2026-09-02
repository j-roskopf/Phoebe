package com.phoebe.app.ui

import kotlinx.coroutines.CoroutineDispatcher

internal interface ArtworkDiskCacheBackend {
    suspend fun read(fetchUrl: String): ByteArray?
    suspend fun write(fetchUrl: String, bytes: ByteArray)
}

internal expect fun defaultArtworkDiskCacheBackend(): ArtworkDiskCacheBackend

/**
 * Dispatcher for blocking artwork file reads.
 *
 * Reading a cache snapshot is blocking I/O, and parking it on [kotlinx.coroutines.Dispatchers.Default]
 * starves the shared compute pool — the same failure mode that made tapping a song freeze the UI.
 */
internal expect fun artworkIoDispatcher(): CoroutineDispatcher

internal object NoopArtworkDiskCacheBackend : ArtworkDiskCacheBackend {
    override suspend fun read(fetchUrl: String): ByteArray? = null
    override suspend fun write(fetchUrl: String, bytes: ByteArray) = Unit
}

internal object ArtworkDiskCache {
    private var backend: ArtworkDiskCacheBackend = defaultArtworkDiskCacheBackend()

    suspend fun read(fetchUrl: String): ByteArray? =
        backend.read(fetchUrl)

    suspend fun write(fetchUrl: String, bytes: ByteArray) {
        backend.write(fetchUrl, bytes)
    }

    internal fun useBackendForTest(backend: ArtworkDiskCacheBackend) {
        this.backend = backend
    }

    internal fun resetBackendForTest() {
        backend = defaultArtworkDiskCacheBackend()
    }
}
