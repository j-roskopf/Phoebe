package com.phoebe.app.ui

internal actual fun defaultArtworkDiskCacheBackend(): ArtworkDiskCacheBackend =
    NoopArtworkDiskCacheBackend
