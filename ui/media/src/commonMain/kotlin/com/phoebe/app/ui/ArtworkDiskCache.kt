package com.phoebe.app.ui

internal interface ArtworkDiskCacheBackend {
    suspend fun read(fetchUrl: String): ByteArray?
    suspend fun write(fetchUrl: String, bytes: ByteArray)
}

internal expect fun defaultArtworkDiskCacheBackend(): ArtworkDiskCacheBackend

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
