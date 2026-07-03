package com.phoebe.app.feature.home

import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.Track
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class MixBuilderRoutesTest {
    private val artistOne = Artist("artist-1", "Artist One")
    private val artistTwo = Artist("artist-2", "Artist Two")
    private val artistThree = Artist("artist-3", "Artist Three")
    private val emptyArtist = Artist("artist-empty", "Empty Artist")
    private val albumOne = Album("album-1", "Album One", "Artist One")
    private val albumTwo = Album("album-2", "Album Two", "Artist Two")
    private val albumThree = Album("album-3", "Album Three", "Artist Three")
    private val emptyAlbum = Album("album-empty", "Empty Album", "Empty Artist")

    @Test
    fun artistMixShufflesPopularTracksBeforeRemainingCatalog() {
        val artistOneTracks = listOf(
            track("a1", artistOne, albumOne, trackNumber = 1),
            track("a2", artistOne, albumOne, trackNumber = 2),
            track("a3", artistOne, albumOne, trackNumber = 3),
        )
        val artistTwoTracks = listOf(
            track("b1", artistTwo, albumTwo, trackNumber = 1),
            track("b2", artistTwo, albumTwo, trackNumber = 2),
            track("b3", artistTwo, albumTwo, trackNumber = 3),
        )
        val catalog = CatalogSnapshot(
            artists = listOf(artistOne, emptyArtist, artistTwo),
            albums = listOf(albumOne, albumTwo),
            tracksByParent = mapOf(
                albumOne.id to artistOneTracks,
                albumTwo.id to artistTwoTracks,
            ),
            popularTracksByArtist = mapOf(
                artistOne.id to listOf(artistOneTracks[1], artistOneTracks[0]),
                artistTwo.id to listOf(artistTwoTracks[2]),
            ),
        )

        val queue = artistMixBuilderQueue(catalog, listOf(artistOne, emptyArtist, artistTwo), Random(12))
        val expectedRandom = Random(12)
        val expectedPopular = listOf("a2", "a1", "b3").shuffled(expectedRandom)
        val expectedRest = listOf("a3", "b1", "b2").shuffled(expectedRandom)

        assertEquals(expectedPopular + expectedRest, queue.map { it.id })
    }

    @Test
    fun artistMixFallsBackToRandomWhenNoPopularTracksExist() {
        val tracks = listOf(
            track("a1", artistOne, albumOne, trackNumber = 1),
            track("a2", artistOne, albumOne, trackNumber = 2),
            track("a3", artistOne, albumOne, trackNumber = 3),
        )
        val catalog = CatalogSnapshot(
            artists = listOf(artistOne),
            albums = listOf(albumOne),
            tracksByParent = mapOf(albumOne.id to tracks),
        )

        val queue = artistMixBuilderQueue(catalog, listOf(artistOne), Random(9))

        val expectedRandom = Random(9)
        val expectedTopTracks = tracks.shuffled(expectedRandom)
        assertEquals(expectedTopTracks.shuffled(expectedRandom).map { it.id }, queue.map { it.id })
    }

    @Test
    fun albumMixShufflesPopularAlbumTracksBeforeRestOfAlbums() {
        val artistOneTracks = listOf(
            track("a1", artistOne, albumOne, trackNumber = 1),
            track("a2", artistOne, albumOne, trackNumber = 2),
            track("a3", artistOne, albumOne, trackNumber = 3),
        )
        val artistTwoTracks = listOf(
            track("b1", artistTwo, albumTwo, trackNumber = 1),
            track("b2", artistTwo, albumTwo, trackNumber = 2),
            track("b3", artistTwo, albumTwo, trackNumber = 3),
        )
        val catalog = CatalogSnapshot(
            artists = listOf(artistOne, artistTwo, emptyArtist),
            albums = listOf(albumOne, albumTwo, emptyAlbum),
            tracksByParent = mapOf(
                albumOne.id to artistOneTracks,
                albumTwo.id to artistTwoTracks,
            ),
            popularTracksByArtist = mapOf(
                artistOne.id to listOf(artistOneTracks[1]),
                artistTwo.id to listOf(artistTwoTracks[1]),
            ),
        )

        val queue = albumMixBuilderQueue(catalog, listOf(albumOne, albumTwo, emptyAlbum), Random(14))
        val expectedRandom = Random(14)
        val expectedPopular = listOf("a2", "b2").shuffled(expectedRandom)
        val expectedRest = listOf("a1", "a3", "b1", "b3").shuffled(expectedRandom)

        assertEquals(expectedPopular + expectedRest, queue.map { it.id })
    }

    @Test
    fun suggestionsUseCachedRelatedArtistsForCurrentSelection() {
        val catalog = CatalogSnapshot(
            artists = listOf(artistOne, artistTwo, artistThree),
            albums = listOf(albumOne, albumTwo, albumThree),
            similarArtistsByArtist = mapOf(
                artistOne.id to listOf(artistTwo, artistThree, artistTwo, artistOne),
            ),
        )

        val artistSuggestions = artistMixBuilderSuggestions(catalog, listOf(artistOne))
        val albumSuggestions = albumMixBuilderSuggestions(catalog, listOf(albumOne))

        assertEquals(listOf("artist-2", "artist-3"), artistSuggestions.map { it.id })
        assertEquals(listOf("album-2", "album-3"), albumSuggestions.map { it.id })
    }

    @Test
    fun artistSuggestionsFallbackToRelatedCatalogTracksWhenProviderRecommendationsAreMissing() {
        val selectedTracks = listOf(
            track("a1", artistOne, albumOne, trackNumber = 1, genre = "Rock"),
        )
        val relatedTracks = listOf(
            track("b1", artistTwo, albumTwo, trackNumber = 1, genre = "Rock"),
        )
        val otherTracks = listOf(
            track("c1", artistThree, albumThree, trackNumber = 1, genre = "Jazz"),
        )
        val catalog = CatalogSnapshot(
            artists = listOf(artistOne, artistTwo, artistThree),
            albums = listOf(albumOne, albumTwo, albumThree),
            tracksByParent = mapOf(
                albumOne.id to selectedTracks,
                albumTwo.id to relatedTracks,
                albumThree.id to otherTracks,
            ),
        )

        val suggestions = artistMixBuilderSuggestions(catalog, listOf(artistOne))

        assertEquals(listOf("artist-2", "artist-3"), suggestions.map { it.id })
    }

    private fun track(
        id: String,
        artist: Artist,
        album: Album,
        trackNumber: Int,
        genre: String? = null,
    ): Track =
        Track(
            id = id,
            title = "Track $id",
            artist = artist.title,
            album = album.title,
            durationMs = 180_000L,
            streamUrl = "https://example.com/$id.mp3",
            downloadUrl = "",
            parentAlbumId = album.id,
            albumArtist = album.artist,
            trackNumber = trackNumber,
            genre = genre,
        )
}
