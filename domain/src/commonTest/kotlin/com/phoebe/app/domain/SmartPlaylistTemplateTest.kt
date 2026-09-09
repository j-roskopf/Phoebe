package com.phoebe.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmartPlaylistTemplateTest {
    @Test
    fun defaultTemplatesCoverRequestedStarterSet() {
        val titles = SmartPlaylistTemplate.Defaults.map { it.title }.toSet()

        assertTrue("Highly Rated" in titles)
        assertTrue("Recently Added" in titles)
        assertTrue("Recently Played" in titles)
        assertTrue("Most Played" in titles)
        assertTrue("Not Played Recently" in titles)
        assertTrue("Downloaded Favorites" in titles)
        assertTrue("Lossless" in titles)
        assertTrue("1990s" in titles)
    }

    @Test
    fun templateInstantiatesSmartPlaylistWithStablePrefix() {
        val playlist = SmartPlaylistTemplate.HighlyRated.instantiate(nowMs = 42L, suffix = "custom")

        assertEquals("${SmartPlaylist.IdPrefix}custom", playlist.id)
        assertEquals("Highly Rated", playlist.title)
        assertEquals(42L, playlist.createdAtMs)
        assertEquals(42L, playlist.updatedAtMs)
        assertEquals(FilterField.Rating, playlist.filter.rules.single().field)
    }

    @Test
    fun dynamicTemplatesBuildExpectedRules() {
        val genre = SmartPlaylistTemplate.byGenre("Dream Pop")
        val decade = SmartPlaylistTemplate.byDecade(1984)

        assertEquals("Dream Pop", genre.title)
        assertEquals(FilterField.Genre, genre.filter.rules.single().field)
        assertEquals(FilterOperator.Contains, genre.filter.rules.single().operator)
        assertEquals("Dream Pop", genre.filter.rules.single().value)
        assertEquals("1980s", decade.title)
        assertEquals("1980..1989", decade.filter.rules.single().value)
    }

    @Test
    fun byGenreCollapsesPunctuationVariantsToSameId() {
        val spaced = SmartPlaylistTemplate.byGenre("Hip Hop")
        val hyphenated = SmartPlaylistTemplate.byGenre("Hip-Hop")
        val ampersand = SmartPlaylistTemplate.byGenre("R&B")
        val spacedRb = SmartPlaylistTemplate.byGenre("R B")

        assertEquals("genre-hip-hop", spaced.id)
        assertEquals(spaced.id, hyphenated.id)
        assertEquals("genre-r-b", ampersand.id)
        assertEquals(ampersand.id, spacedRb.id)

        val deduped = listOf(spaced, hyphenated, ampersand, spacedRb).distinctBy { it.id }
        assertEquals(2, deduped.size)
    }
}
