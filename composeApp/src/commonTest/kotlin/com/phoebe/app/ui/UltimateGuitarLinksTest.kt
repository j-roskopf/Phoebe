package com.phoebe.app.ui

import com.phoebe.app.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UltimateGuitarLinksTest {
    @Test
    fun ultimateGuitarSearchUrlIncludesTitleThenArtist() {
        assertEquals(
            "https://www.ultimate-guitar.com/search.php?search_type=title&value=Hotel+California+Eagles",
            ultimateGuitarSearchUrl(track(title = "Hotel California", artist = "Eagles")),
        )
    }

    @Test
    fun ultimateGuitarSearchUrlFallsBackToTitleWhenArtistIsBlank() {
        assertEquals(
            "https://www.ultimate-guitar.com/search.php?search_type=title&value=Blackbird",
            ultimateGuitarSearchUrl(track(title = " Blackbird ", artist = "  ")),
        )
    }

    @Test
    fun ultimateGuitarSearchUrlReturnsNullWhenTitleIsBlank() {
        assertNull(ultimateGuitarSearchUrl(track(title = "  ", artist = "Eagles")))
    }

    @Test
    fun ultimateGuitarSearchUrlEncodesSpecialCharactersAndNormalizesWhitespace() {
        assertEquals(
            "https://www.ultimate-guitar.com/search.php?search_type=title&value=Sweet+Child+O%27+Mine+Guns+%26+Roses",
            ultimateGuitarSearchUrl(track(title = " Sweet   Child O' Mine ", artist = " Guns & Roses ")),
        )
    }

    private fun track(title: String, artist: String): Track =
        Track(
            id = "track",
            title = title,
            artist = artist,
            album = "Album",
            durationMs = 60_000L,
            streamUrl = "https://stream.example/track.mp3",
            downloadUrl = "",
        )
}
