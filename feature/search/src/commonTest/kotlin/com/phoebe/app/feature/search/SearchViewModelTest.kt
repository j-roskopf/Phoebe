package com.phoebe.app.feature.search

import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.DownloadItem
import com.phoebe.app.domain.DownloadState
import com.phoebe.app.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class SearchViewModelTest {
    @Test
    fun emptyQueryKeepsResultsEmpty() = runTest {
        val viewModel = SearchViewModel(SearchResultsFactory())

        viewModel.updateCatalog(testCatalog(), catalogRefreshing = true)

        val state = viewModel.state.value
        assertEquals("", state.query)
        assertEquals(true, state.catalogRefreshing)
        assertEquals(0, state.results.tracks.size)
        assertNull(state.results.topTrack)
    }

    @Test
    fun queryUpdatesResultsFromCatalog() = runTest {
        val viewModel = SearchViewModel(SearchResultsFactory())
        viewModel.updateCatalog(testCatalog(), catalogRefreshing = false)

        viewModel.onQuery("moon")

        val state = viewModel.state.value
        assertEquals("moon", state.query)
        assertEquals(listOf("plex:track-moon"), state.results.tracks.map { it.id })
        assertEquals("album-moon", state.results.topAlbum?.id)
        assertEquals("artist-moon", state.results.topArtist?.id)
    }

    @Test
    fun advancedQueryFiltersTracks() = runTest {
        val viewModel = SearchViewModel(SearchResultsFactory())
        viewModel.updateCatalog(testCatalog(), catalogRefreshing = false)

        viewModel.onQuery("song artist:Moon year:2024..2024 downloaded:true codec:flac")

        assertEquals(listOf("plex:track-moon"), viewModel.state.value.results.tracks.map { it.id })
    }

    @Test
    fun filterOnlyQueryReturnsTracksWithoutEntityTextMatches() = runTest {
        val viewModel = SearchViewModel(SearchResultsFactory())
        viewModel.updateCatalog(testCatalog(), catalogRefreshing = false)

        viewModel.onQuery("downloaded:true")

        val state = viewModel.state.value
        assertEquals(listOf("plex:track-moon"), state.results.tracks.map { it.id })
        assertEquals(emptyList(), state.results.albums)
        assertEquals(emptyList(), state.results.artists)
    }

    @Test
    fun broadQueryCapsMaterializedResults() = runTest {
        val viewModel = SearchViewModel(SearchResultsFactory())
        viewModel.updateCatalog(largeMatchingCatalog(), catalogRefreshing = false)

        viewModel.onQuery("shared")

        val state = viewModel.state.value
        assertEquals(500, state.results.tracks.size)
        assertEquals(500, state.results.albums.size)
        assertEquals(500, state.results.artists.size)
    }

    @Test
    fun routeStateReflectsViewModelState() = runTest {
        val viewModel = SearchViewModel(SearchResultsFactory())
        val catalog = testCatalog()

        viewModel.updateCatalog(catalog, catalogRefreshing = true)
        viewModel.onQuery("moon")

        assertEquals(SearchDesktopRouteState(catalog, catalogRefreshing = true, query = "moon"), viewModel.routeState())
    }

    private fun testCatalog(): CatalogSnapshot {
        val moonArtist = Artist(
            id = "artist-moon",
            title = "Moon Unit",
            albumCount = 1,
            songCount = 1,
        )
        val otherArtist = Artist(
            id = "artist-sun",
            title = "Sun Room",
            albumCount = 1,
            songCount = 1,
        )
        val moonAlbum = Album(
            id = "album-moon",
            title = "Moon Phase",
            artist = moonArtist.title,
            year = 2024,
        )
        val otherAlbum = Album(
            id = "album-sun",
            title = "Solar Phase",
            artist = otherArtist.title,
            year = 2024,
        )
        val moonTrack = Track(
            id = "plex:track-moon",
            title = "Moon Song",
            artist = moonArtist.title,
            album = moonAlbum.title,
            durationMs = 180_000L,
            streamUrl = "https://example.com/moon",
            downloadUrl = "https://example.com/moon.mp3",
            year = 2024,
            audioCodec = "flac",
        )
        val otherTrack = Track(
            id = "track-sun",
            title = "Solar Song",
            artist = otherArtist.title,
            album = otherAlbum.title,
            durationMs = 180_000L,
            streamUrl = "https://example.com/sun",
            downloadUrl = "https://example.com/sun.mp3",
        )
        return CatalogSnapshot(
            artists = listOf(moonArtist, otherArtist),
            albums = listOf(moonAlbum, otherAlbum),
            tracksByParent = mapOf(
                moonAlbum.id to listOf(moonTrack),
                otherAlbum.id to listOf(otherTrack),
            ),
            downloads = listOf(
                DownloadItem(
                    trackId = moonTrack.id,
                    title = moonTrack.title,
                    artist = moonTrack.artist,
                    state = DownloadState.Complete,
                ),
            ),
        )
    }

    private fun largeMatchingCatalog(): CatalogSnapshot {
        val artists = List(600) { index ->
            Artist(
                id = "artist-$index",
                title = "Shared Artist $index",
                albumCount = 1,
                songCount = 1,
            )
        }
        val albums = List(600) { index ->
            Album(
                id = "album-$index",
                title = "Shared Album $index",
                artist = artists[index].title,
            )
        }
        val tracks = List(600) { index ->
            Track(
                id = "track-$index",
                title = "Shared Track $index",
                artist = artists[index].title,
                album = albums[index].title,
                durationMs = 180_000L,
                streamUrl = "https://example.com/shared-$index",
                downloadUrl = "https://example.com/shared-$index.mp3",
            )
        }
        return CatalogSnapshot(
            artists = artists,
            albums = albums,
            tracksByParent = albums.zip(tracks).associate { (album, track) -> album.id to listOf(track) },
        )
    }
}
