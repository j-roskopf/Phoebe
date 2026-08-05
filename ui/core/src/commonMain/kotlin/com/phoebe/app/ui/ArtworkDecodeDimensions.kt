package com.phoebe.app.ui

import com.phoebe.app.platform.prefersReducedArtworkEffects

const val ThumbnailArtworkMaxDecodeDimension = 160

/** Default decode cap for list/grid tiles (~48-92dp). Hero art should pass a larger value explicitly. */
const val ListArtworkMaxDecodeDimension = 256

/** Decode cap for single, large artwork surfaces such as the mobile full-screen player. */
const val HeroArtworkMaxDecodeDimension = 1024

/** Smaller hero decode cap for memory-constrained web playback. */
const val WebHeroArtworkMaxDecodeDimension = 256

/** Decode cap for the now-playing hero surface on the current platform. */
fun playerHeroArtworkMaxDecodeDimension(): Int =
    if (prefersReducedArtworkEffects()) {
        WebHeroArtworkMaxDecodeDimension
    } else {
        HeroArtworkMaxDecodeDimension
    }

/** Upper decode cap for library grid tiles at the largest supported item size. */
const val GridArtworkMaxDecodeDimension = 512

/** Decode cap for a library grid tile from its configured artwork size in dp. */
fun libraryGridDecodeDimension(itemSizeDp: Int): Int =
    (itemSizeDp * 2).coerceIn(ThumbnailArtworkMaxDecodeDimension, GridArtworkMaxDecodeDimension)
