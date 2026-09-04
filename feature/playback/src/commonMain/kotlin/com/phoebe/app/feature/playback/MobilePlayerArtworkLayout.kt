package com.phoebe.app.feature.playback

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class MobilePlayerArtworkLayout(
    val displaySize: Dp,
    val edgeInset: Dp,
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
    val edgeInset = if (isArtworkImage) {
        // Use the existing gap above the metadata before shrinking the cover. For a centered
        // square, its envelope is (artworkWidth - edgeInset), so this is the largest equal-
        // inset square that still leaves the minimum gap below it.
        val maximumArtworkEnvelope = baseArtworkHeight + metadataTopGap - minimumMetadataGap
        (artworkWidth - maximumArtworkEnvelope)
            .coerceAtLeast(0.dp)
            .coerceAtMost(maxHeightTrim / 2f)
    } else {
        0.dp
    }
    val displaySize = if (isArtworkImage) {
        (artworkWidth - edgeInset * 2f).coerceAtLeast(0.dp)
    } else {
        baseArtworkHeight
    }
    val heightTrim = artworkWidth - displaySize
    val layoutHeight = displaySize + edgeInset
    val metadataGap = (baseArtworkHeight + metadataTopGap - layoutHeight)
        .coerceAtLeast(minimumMetadataGap)

    return MobilePlayerArtworkLayout(
        displaySize = displaySize,
        edgeInset = edgeInset,
        layoutHeight = layoutHeight,
        heightTrim = heightTrim,
        metadataGap = metadataGap,
    )
}
