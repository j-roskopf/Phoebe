package com.phoebe.app.ui

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun defaultArtworkDiskCacheBackend(): ArtworkDiskCacheBackend =
    NoopArtworkDiskCacheBackend

// `Dispatchers.IO` is internal on Kotlin/Native. Default is a multi-threaded worker pool here,
// not the single shared compute pool the JVM has, so a short file read on it is acceptable.
internal actual fun artworkIoDispatcher(): CoroutineDispatcher = Dispatchers.Default
