package com.phoebe.app.feature.playback

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class MobilePlayerArtworkLayoutTest {
    @Test
    fun imageArtworkUsesEqualInsetsWhenGapBudgetAllows() {
        val layout = mobilePlayerArtworkLayout(
            artworkWidth = 420.dp,
            compactPlayerHeight = 900.dp,
            availableHeight = 840.dp,
            maxHeightTrim = 200.dp,
            isArtworkImage = true,
            metadataTopGap = 22.dp,
            minimumMetadataGap = 8.dp,
        )

        assertEquals(92.dp, layout.heightTrim)
        assertEquals(328.dp, layout.displaySize)
        assertEquals(46.dp, layout.sideInset)
        assertEquals(46.dp, layout.topInset)
        assertEquals(374.dp, layout.layoutHeight)
        assertEquals(8.dp, layout.metadataGap)
        assertEquals(420.dp, layout.displaySize + layout.sideInset * 2f)
        assertEquals(layout.layoutHeight, layout.topInset + layout.displaySize)
        assertEquals(382.dp, layout.layoutHeight + layout.metadataGap)
    }

    @Test
    fun imageArtworkDoesNotAddInsetWhenThereIsNoHeightDeficit() {
        val layout = mobilePlayerArtworkLayout(
            artworkWidth = 420.dp,
            compactPlayerHeight = 900.dp,
            availableHeight = 940.dp,
            maxHeightTrim = 200.dp,
            isArtworkImage = true,
            metadataTopGap = 22.dp,
            minimumMetadataGap = 8.dp,
        )

        assertEquals(0.dp, layout.heightTrim)
        assertEquals(420.dp, layout.displaySize)
        assertEquals(0.dp, layout.sideInset)
        assertEquals(0.dp, layout.topInset)
        assertEquals(420.dp, layout.layoutHeight)
        assertEquals(22.dp, layout.metadataGap)
    }

    @Test
    fun nonImageArtworkKeepsExistingHeightTrimGeometry() {
        val layout = mobilePlayerArtworkLayout(
            artworkWidth = 420.dp,
            compactPlayerHeight = 900.dp,
            availableHeight = 840.dp,
            maxHeightTrim = 200.dp,
            isArtworkImage = false,
            metadataTopGap = 22.dp,
            minimumMetadataGap = 8.dp,
        )

        assertEquals(60.dp, layout.heightTrim)
        assertEquals(360.dp, layout.displaySize)
        assertEquals(0.dp, layout.sideInset)
        assertEquals(0.dp, layout.topInset)
        assertEquals(360.dp, layout.layoutHeight)
        assertEquals(22.dp, layout.metadataGap)
    }

    @Test
    fun imageArtworkCapsTopInsetWhenSideInsetHitsMaxTrim() {
        // 360×640-class: height trim hits the max, so equal top inset would overflow the
        // metadata gap budget. Keep side inset for centering; limit top inset so title→progress
        // still has the full metadata content reserve.
        val layout = mobilePlayerArtworkLayout(
            artworkWidth = 360.dp,
            compactPlayerHeight = 724.dp,
            availableHeight = 640.dp,
            maxHeightTrim = 80.dp,
            isArtworkImage = true,
            metadataTopGap = 22.dp,
            minimumMetadataGap = 8.dp,
        )

        assertEquals(80.dp, layout.heightTrim)
        assertEquals(280.dp, layout.displaySize)
        assertEquals(40.dp, layout.sideInset)
        assertEquals(14.dp, layout.topInset)
        assertEquals(294.dp, layout.layoutHeight)
        assertEquals(8.dp, layout.metadataGap)
        assertEquals(302.dp, layout.layoutHeight + layout.metadataGap)
    }
}
