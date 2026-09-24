package com.phoebe.app.ui

import coil3.PlatformContext

internal actual fun stableArtworkImageLoaderContext(platformContext: PlatformContext): PlatformContext =
    platformContext

internal actual val artworkMemoryCacheMaxBytes: Long? = null
