package com.phoebe.app

import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.PlaybackQueueOrigin
import com.phoebe.app.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QueueContinuationPlannerTest {
    @Test
    fun fallbackRejectsDecadeOnlyMatches() {
        val seed = track(
            "plex:seed",
            title = "Seed Song",
            artist = "Seed Artist",
            album = "Seed Album",
            year = 1994,
        )
        val decadeOnly = track(
            "plex:decade",
            title = "Same Decade",
            artist = "Other Artist",
            album = "Other Album",
            year = 1996,
        )

        val plan = planQueueContinuation(
            catalog = CatalogSnapshot(tracksByParent = mapOf("library" to listOf(decadeOnly))),
            origin = PlaybackQueueOrigin.TrackList(seedTrackIds = listOf(seed.id)),
            currentQueue = listOf(seed),
        )

        assertNull(plan)
    }

    @Test
    fun fallbackAcceptsSharedTagsAndExcludesCurrentQueueDuplicates() {
        val seed = track(
            id = "plex:seed",
            title = "Blue Hour",
            artist = "Seed Artist",
            genre = "Dream Pop",
            mood = "Nocturnal",
        )
        val duplicateSeed = track(
            id = "jellyfin:seed-copy",
            title = "Blue Hour",
            artist = "Seed Artist",
            genre = "Dream Pop",
            mood = "Nocturnal",
        )
        val related = track(
            id = "plex:related",
            title = "Velvet Sky",
            artist = "Other Artist",
            genre = "Shoegaze; Dream Pop",
            mood = "Nocturnal",
        )

        val plan = planQueueContinuation(
            catalog = CatalogSnapshot(tracksByParent = mapOf("library" to listOf(duplicateSeed, related))),
            origin = PlaybackQueueOrigin.TrackList(seedTrackIds = listOf(seed.id)),
            currentQueue = listOf(seed),
        )

        assertNotNull(plan)
        assertEquals(listOf("plex:related"), plan.tracks.map { it.id })
    }

    @Test
    fun fallbackRejectsBroadSharedTags() {
        val seed = track(
            id = "plex:seed",
            title = "Seed Pop",
            artist = "Seed Artist",
            album = "Seed Album",
            genre = "Pop",
        )
        val broadMatch = track(
            id = "plex:broad",
            title = "Broad Match",
            artist = "Other Artist",
            album = "Other Album",
            genre = "Pop",
        )

        val plan = planQueueContinuation(
            catalog = CatalogSnapshot(tracksByParent = mapOf("library" to listOf(broadMatch))),
            origin = PlaybackQueueOrigin.TrackList(seedTrackIds = listOf(seed.id)),
            currentQueue = listOf(seed),
        )

        assertNull(plan)
    }

    @Test
    fun fallbackRejectsPlexPopRockOnlyMatches() {
        val seed = track(
            id = "plex:seed",
            title = "Seed Pop Rock",
            artist = "Seed Artist",
            album = "Seed Album",
            genre = "Pop/Rock",
        )
        val broadMatch = track(
            id = "plex:broad",
            title = "Broad Match",
            artist = "Other Artist",
            album = "Other Album",
            genre = "Pop/Rock",
        )

        val plan = planQueueContinuation(
            catalog = CatalogSnapshot(tracksByParent = mapOf("library" to listOf(broadMatch))),
            origin = PlaybackQueueOrigin.Playlist(
                id = "plex:playlist",
                title = "Euro",
                seedTrackIds = listOf(seed.id),
            ),
            currentQueue = listOf(seed),
        )

        assertNull(plan)
    }

    @Test
    fun weakFallbackCanUseBroadTagsWhenProviderIsSilent() {
        val seed = track(
            id = "plex:seed",
            title = "Seed Pop Rock",
            artist = "Seed Artist",
            album = "Seed Album",
            genre = "Pop/Rock",
        )
        val broadMatch = track(
            id = "plex:broad",
            title = "Broad Match",
            artist = "Other Artist",
            album = "Other Album",
            genre = "Pop/Rock",
        )

        val plan = planQueueContinuation(
            catalog = CatalogSnapshot(tracksByParent = mapOf("library" to listOf(broadMatch))),
            origin = PlaybackQueueOrigin.Playlist(
                id = "plex:playlist",
                title = "Euro",
                seedTrackIds = listOf(seed.id),
            ),
            currentQueue = listOf(seed),
            allowWeakFallback = true,
        )

        assertNotNull(plan)
        assertEquals(listOf("plex:broad"), plan.tracks.map { it.id })
    }

    @Test
    fun weakFallbackCanAddBestAvailableTrackWithoutSignals() {
        val seed = track(
            id = "plex:seed",
            title = "Seed Song",
            artist = "Seed Artist",
            album = "Seed Album",
        )
        val available = track(
            id = "plex:available",
            title = "Available Song",
            artist = "Other Artist",
            album = "Other Album",
            rating = 4.5f,
        )

        val plan = planQueueContinuation(
            catalog = CatalogSnapshot(tracksByParent = mapOf("library" to listOf(available))),
            origin = PlaybackQueueOrigin.Playlist(
                id = "plex:playlist",
                title = "Sparse Playlist",
                seedTrackIds = listOf(seed.id),
            ),
            currentQueue = listOf(seed),
            allowWeakFallback = true,
        )

        assertNotNull(plan)
        assertEquals(listOf("plex:available"), plan.tracks.map { it.id })
    }

    @Test
    fun fallbackRejectsGenericAlbumTitleOnlyMatches() {
        val seed = track(
            id = "plex:seed",
            title = "Seed Hit",
            artist = "Seed Artist",
            album = "The Greatest Hits",
        )
        val genericAlbumMatch = track(
            id = "plex:generic-album",
            title = "Other Hit",
            artist = "Other Artist",
            album = "The Greatest Hits",
        )

        val plan = planQueueContinuation(
            catalog = CatalogSnapshot(tracksByParent = mapOf("library" to listOf(genericAlbumMatch))),
            origin = PlaybackQueueOrigin.Playlist(
                id = "plex:playlist",
                title = "Euro",
                seedTrackIds = listOf(seed.id),
            ),
            currentQueue = listOf(seed),
        )

        assertNull(plan)
    }

    @Test
    fun fallbackAcceptsGenericAlbumTitleWhenArtistAlsoMatches() {
        val seed = track(
            id = "plex:seed",
            title = "Seed Hit",
            artist = "Seed Artist",
            album = "The Greatest Hits",
        )
        val sameArtistAlbumMatch = track(
            id = "plex:same-artist-album",
            title = "Other Hit",
            artist = "Seed Artist",
            album = "The Greatest Hits",
        )

        val plan = planQueueContinuation(
            catalog = CatalogSnapshot(tracksByParent = mapOf("library" to listOf(sameArtistAlbumMatch))),
            origin = PlaybackQueueOrigin.Playlist(
                id = "plex:playlist",
                title = "Euro",
                seedTrackIds = listOf(seed.id),
            ),
            currentQueue = listOf(seed),
        )

        assertNotNull(plan)
        assertEquals(listOf("plex:same-artist-album"), plan.tracks.map { it.id })
    }

    @Test
    fun playlistNativeCandidatesMustBeMeaningfullyRelated() {
        val seed = track(
            id = "plex:seed",
            title = "Rhythm Seed",
            artist = "Euro Seed",
            album = "Euro Starter",
            genre = "Eurodance",
            year = 1995,
        )
        val weakNative = track(
            id = "plex:weak-native",
            title = "Weak Native",
            artist = "Other Artist",
            album = "General Pop Album",
            genre = "Pop",
            year = 1995,
        )
        val relatedFallback = track(
            id = "plex:related-fallback",
            title = "Related Fallback",
            artist = "Another Artist",
            album = "Euro Follow Up",
            genre = "Eurodance",
            year = 1998,
        )

        val plan = planQueueContinuation(
            catalog = CatalogSnapshot(tracksByParent = mapOf("library" to listOf(relatedFallback))),
            origin = PlaybackQueueOrigin.Playlist(
                id = "plex:playlist",
                title = "Euro Playlist",
                seedTrackIds = listOf(seed.id),
            ),
            currentQueue = listOf(seed),
            nativeCandidates = listOf(weakNative),
        )

        assertNotNull(plan)
        assertEquals(listOf("plex:related-fallback"), plan.tracks.map { it.id })
    }

    @Test
    fun playlistNativeCandidatesAcceptSpecificSharedTags() {
        val seed = track(
            id = "plex:seed",
            title = "Rhythm Seed",
            artist = "Euro Seed",
            album = "Euro Starter",
            genre = "Eurodance",
        )
        val relatedNative = track(
            id = "plex:related-native",
            title = "Related Native",
            artist = "Other Artist",
            album = "Different Album",
            genre = "Eurodance",
        )

        val plan = planQueueContinuation(
            catalog = CatalogSnapshot(),
            origin = PlaybackQueueOrigin.Playlist(
                id = "plex:playlist",
                title = "Euro Playlist",
                seedTrackIds = listOf(seed.id),
            ),
            currentQueue = listOf(seed),
            nativeCandidates = listOf(relatedNative),
        )

        assertNotNull(plan)
        assertEquals(listOf("plex:related-native"), plan.tracks.map { it.id })
    }

    @Test
    fun nativeCandidatesAreDedupedExcludedAndCapped() {
        val seed = track("plex:seed", title = "Seed Song", artist = "Seed Artist")
        val duplicateOfSeed = track("plex:seed-copy", title = "Seed Song", artist = "Seed Artist")
        val first = track("plex:first", title = "First Native", artist = "Native Artist")
        val second = track("plex:second", title = "Second Native", artist = "Native Artist")
        val third = track("plex:third", title = "Third Native", artist = "Native Artist")

        val plan = planQueueContinuation(
            catalog = CatalogSnapshot(),
            origin = PlaybackQueueOrigin.Radio(
                id = "plex:radio",
                title = "Library Radio",
                key = "/library/radio",
            ),
            currentQueue = listOf(seed),
            nativeCandidates = listOf(duplicateOfSeed, first, first, second, third),
            limitTracks = 2,
        )

        assertNotNull(plan)
        assertEquals(listOf("plex:first", "plex:second"), plan.tracks.map { it.id })
    }

    private fun track(
        id: String,
        title: String = id,
        artist: String = "Artist",
        album: String = "Album",
        durationMs: Long = 180_000L,
        year: Int? = null,
        genre: String? = null,
        mood: String? = null,
        style: String? = null,
        rating: Float? = null,
    ): Track =
        Track(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            streamUrl = "https://example.test/$id.mp3",
            downloadUrl = "",
            year = year,
            genre = genre,
            mood = mood,
            style = style,
            rating = rating,
        )
}
