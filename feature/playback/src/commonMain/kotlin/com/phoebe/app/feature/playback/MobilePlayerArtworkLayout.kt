package com.phoebe.app.feature.playback

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class MobilePlayerArtworkLayout(
    val displaySize: Dp,
    val sideInset: Dp,
    val topInset: Dp,
    val layoutHeight: Dp,
    val heightTrim: Dp,
    val metadataGap: Dp,
)

internal fun mobilePlayerArtworkLayout(
    artworkWidth: Dp,
    compactPlayerHeight: Dp,
    availableHeight: Dp,
    maxHeightTrim: Dp,
    isArtworkImage: Boolean,
    metadataTopGap: Dp,
    minimumMetadataGap: Dp,
): MobilePlayerArtworkLayout {
    val compactHeightDeficit = (compactPlayerHeight - availableHeight).coerceAtLeast(0.dp)
    val baseHeightTrim = compactHeightDeficit.coerceIn(0.dp, maxHeightTrim)
    val baseArtworkHeight = artworkWidth - baseHeightTrim
    val gapBudget = (metadataTopGap - minimumMetadataGap).coerceAtLeast(0.dp)
    // Largest equal-inset square whose top-inset + square + minimum gap still fits the
    // original artwork row plus the metadata top-gap budget (does not steal metadata content).
    val maximumArtworkEnvelope = baseArtworkHeight + gapBudget
    val sideInset = if (isArtworkImage) {
        (artworkWidth - maximumArtworkEnvelope)
            .coerceAtLeast(0.dp)
            .coerceAtMost(maxHeightTrim / 2f)
    } else {
        0.dp
    }
    val displaySize = if (isArtworkImage) {
        (artworkWidth - sideInset * 2f).coerceAtLeast(0.dp)
    } else {
        baseArtworkHeight
    }
    // When side inset is capped by max trim, keep top inset inside the gap budget so the
    // metadata column retains its content reserve.
    val topInset = if (isArtworkImage) {
        sideInset.coerceAtMost((maximumArtworkEnvelope - displaySize).coerceAtLeast(0.dp))
    } else {
        0.dp
    }
    val heightTrim = artworkWidth - displaySize
    val layoutHeight = displaySize + topInset
    val metadataGap = (baseArtworkHeight + metadataTopGap - layoutHeight)
        .coerceAtLeast(minimumMetadataGap)

    return MobilePlayerArtworkLayout(
        displaySize = displaySize,
        sideInset = sideInset,
        topInset = topInset,
        layoutHeight = layoutHeight,
        heightTrim = heightTrim,
        metadataGap = metadataGap,
    )
}
