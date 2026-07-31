package com.phoebe.app.ui

import coil3.PlatformContext

/**
 * Context used to key and build the shared Coil [coil3.ImageLoader].
 *
 * On Android the Compose local context is usually an Activity, which changes on
 * configuration updates; callers should use an application-stable context there.
 */
internal expect fun stableArtworkImageLoaderContext(platformContext: PlatformContext): PlatformContext
