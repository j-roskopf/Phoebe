package com.phoebe.app.feature.playback

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class MobilePlayerArtworkLayoutTest {
    @Test
    fun imageArtworkUsesEqualTopAndSideInsetsWithinReservedEnvelope() {
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
        assertEquals(46.dp, layout.edgeInset)
        assertEquals(374.dp, layout.layoutHeight)
        assertEquals(8.dp, layout.metadataGap)
        assertEquals(420.dp, layout.displaySize + layout.edgeInset * 2f)
        assertEquals(layout.layoutHeight, layout.edgeInset + layout.displaySize)
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
        assertEquals(0.dp, layout.edgeInset)
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
        assertEquals(0.dp, layout.edgeInset)
        assertEquals(360.dp, layout.layoutHeight)
        assertEquals(22.dp, layout.metadataGap)
    }
}
