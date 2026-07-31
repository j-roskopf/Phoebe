package com.phoebe.app.ui

import coil3.PlatformContext
import com.phoebe.app.AndroidContextHolder

internal actual fun stableArtworkImageLoaderContext(platformContext: PlatformContext): PlatformContext =
    AndroidContextHolder.application
