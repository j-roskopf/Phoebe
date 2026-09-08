package com.phoebe.app.player

import com.phoebe.app.domain.RadioStation
import com.phoebe.app.domain.RadioStationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioStationVoiceMatchTest {
    @Test
    fun exactNormalizedNameMatches() {
        val station = station("kexp-90-3", "KEXP 90.3")
        assertTrue(station.matchesStrongRadioVoiceQuery("kexp 90 3"))
        assertEquals(station, findStrongRadioStationMatch("KEXP 90.3", emptyList(), listOf(station)))
    }

    @Test
    fun leadingCallSignMatchesFullStationName() {
        val station = station("kexp-90-3", "KEXP 90.3")
        assertEquals(station, findStrongRadioStationMatch("KEXP", emptyList(), listOf(station)))
    }

    @Test
    fun bareRadioDoesNotMatchStationsContainingRadio() {
        val station = station("bbc-6", "BBC Radio 6 Music")
        assertNull(findStrongRadioStationMatch("radio", emptyList(), listOf(station)))
    }

    @Test
    fun savedBeatsRecommendedOnTie() {
        val saved = station("saved-kexp", "KEXP 90.3")
        val recommended = station("recommended:kexp-90-3", "KEXP 90.3")
        assertEquals(
            saved,
            findStrongRadioStationMatch("KEXP", listOf(saved), listOf(recommended)),
        )
    }

    @Test
    fun substringArtistNameDoesNotStrongMatch() {
        val station = station("nightwave", "Nightwave Plaza")
        assertNull(findStrongRadioStationMatch("wave", emptyList(), listOf(station)))
    }

    @Test
    fun genericLeadingWordDoesNotHijackBareQuery() {
        val station = station("radio-paradise", "Radio Paradise")
        assertNull(findStrongRadioStationMatch("radio", emptyList(), listOf(station)))
    }

    @Test
    fun leadingCallSignWithDigitsMatches() {
        val station = station("wfmu-91-1", "WFMU 91.1")
        assertEquals(station, findStrongRadioStationMatch("WFMU", emptyList(), listOf(station)))
    }

    private fun station(id: String, name: String): RadioStation =
        RadioStation(
            id = id,
            name = name,
            streamUrl = "https://example.test/$id",
            source = RadioStationSource.Recommended,
        )
}
