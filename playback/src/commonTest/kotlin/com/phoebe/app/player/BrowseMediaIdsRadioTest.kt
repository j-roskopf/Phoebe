package com.phoebe.app.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowseMediaIdsRadioTest {
    @Test
    fun radioStationIdsRoundTripWithColons() {
        val id = "recommended:bbc-radio-6-music"
        val mediaId = BrowseMediaIds.radioStation(id)
        assertEquals(id, BrowseMediaIds.parseRadioStationId(mediaId))
    }

    @Test
    fun radioCategoryIdsRoundTrip() {
        val category = "Jazz & Blues"
        val mediaId = BrowseMediaIds.radioCategory(category)
        assertEquals(category, BrowseMediaIds.parseRadioCategory(mediaId))
    }

    @Test
    fun isRadioBrowseIdCoversTree() {
        assertTrue(BrowseMediaIds.isRadioBrowseId(BrowseMediaIds.RADIO))
        assertTrue(BrowseMediaIds.isRadioBrowseId(BrowseMediaIds.RADIO_RECOMMENDED))
        assertTrue(BrowseMediaIds.isRadioBrowseId(BrowseMediaIds.RADIO_SAVED))
        assertTrue(BrowseMediaIds.isRadioBrowseId(BrowseMediaIds.radioStation("x")))
        assertNull(BrowseMediaIds.parseRadioStationId(BrowseMediaIds.ARTISTS))
    }
}
