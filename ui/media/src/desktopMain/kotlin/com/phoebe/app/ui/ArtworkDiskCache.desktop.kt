package com.phoebe.app.ui

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun defaultArtworkDiskCacheBackend(): ArtworkDiskCacheBackend =
    NoopArtworkDiskCacheBackend

internal actual fun artworkIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
