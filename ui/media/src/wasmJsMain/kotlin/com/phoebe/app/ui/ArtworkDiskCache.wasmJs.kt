package com.phoebe.app.ui

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun defaultArtworkDiskCacheBackend(): ArtworkDiskCacheBackend =
    NoopArtworkDiskCacheBackend

// wasmJs has no IO dispatcher; browser storage access is not blocking in the JVM sense.
internal actual fun artworkIoDispatcher(): CoroutineDispatcher = Dispatchers.Default
