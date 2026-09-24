package com.phoebe.app.ui

import coil3.PlatformContext

internal actual fun stableArtworkImageLoaderContext(platformContext: PlatformContext): PlatformContext =
    platformContext

/**
 * Coil's JVM default is 15% of a fixed 512 MB (~77 MB) of decoded Skia bitmaps,
 * all native memory the GC cannot see. Art still on screen stays reachable through
 * the weak cache, so a smaller strong cache only drops off-screen covers.
 */
internal actual val artworkMemoryCacheMaxBytes: Long? = 48L * 1024L * 1024L
