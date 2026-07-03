package com.phoebe.app.feature.home

import com.phoebe.app.data.HomePlayedTrack
import com.phoebe.app.data.PlayHistorySnapshot
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.MostPlayedEntry
import com.phoebe.app.domain.PersonalMixPreferences
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.RecentlyPlayedEntry
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.playHistoryIdentityKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeUiStateTest {
    private fun establishedPlayHistory(): PlayHistorySnapshot {
        val byTrack = (1..100).associate { "hist-$it" to it.toLong() }
        val playCountByTrack = (1..100).associate { "hist-$it" to 2L }
        return PlayHistorySnapshot(byTrack = byTrack, playCountByTrack = playCountByTrack)
    }
    @Test
    fun derivesRecentAndMostPlayedHomeSections() {
        val tracks = (1..12).map { index ->
            Track(
                id = "t$index",
                title = "Track $index",
                artist = "Artist ${index % 3}",
                album = "Album ${index % 4}",
                durationMs = 1_000L,
                streamUrl = "",
                downloadUrl = "",
                dateAddedMs = index.toLong(),
            )
        }
        val catalog = CatalogSnapshot(
            artists = (1..12).map { Artist("a$it", "Artist $it", dateAddedMs = it.toLong()) },
            albums = (1..12).map { Album("al$it", "Album $it", "Artist ${it % 3}", dateAddedMs = it.toLong()) },
            tracksByParent = mapOf("all" to tracks),
        )
        val state = deriveHomeUiState(
            catalog = catalog,
            playHistory = PlayHistorySnapshot(
                byTrack = mapOf("t2" to 200L, "t5" to 500L, "t1" to 100L),
                playCountByTrack = mapOf("t2" to 2L, "t5" to 9L, "t1" to 4L),
                topRecentlyPlayed = listOf(
                    RecentlyPlayedEntry("t5", 500L, "", ""),
                    RecentlyPlayedEntry("t2", 200L, "", ""),
                    RecentlyPlayedEntry("t1", 100L, "", ""),
                ),
                topMostPlayed = listOf(
                    MostPlayedEntry("t5", 9L, 500L, "", ""),
                    MostPlayedEntry("t1", 4L, 100L, "", ""),
                    MostPlayedEntry("t2", 2L, 200L, "", ""),
                ),
            ),
            randomArtistSeed = 1,
            randomAlbumSeed = 2,
            nowMs = 12L,
        )

        assertEquals((12 downTo 3).map { "t$it" }, state.recentlyAddedTracks.map { it.id })
        assertEquals((12 downTo 3).map { "a$it" }, state.recentlyAddedArtists.map { it.id })
        assertEquals((12 downTo 3).map { "al$it" }, state.recentlyAddedAlbums.map { it.id })
        assertEquals(listOf("t5", "t2", "t1"), state.recentlyPlayedTracks.map { it.track.id })
        assertEquals(listOf("t5", "t1", "t2"), state.mostPlayedTracks.map { it.track.id })
        assertEquals(10, state.randomArtists.size)
        assertEquals(10, state.randomAlbums.size)
    }

    @Test
    fun recentlyPlayedCollapsesEquivalentMetadataVariants() {
        val plexIris = Track(
            id = "plex:iris",
            title = "Iris",
            artist = "The Goo Goo Dolls",
            album = "Dizzy Up the Girl",
            durationMs = 289_000L,
            streamUrl = "plex-stream",
            downloadUrl = "",
        )
        val localIris = Track(
            id = "local:folder:iris",
            title = "Iris",
            artist = "Goo Goo Dolls",
            album = "Dizzy Up The Girl",
            durationMs = 289_500L,
            streamUrl = "",
            downloadUrl = "",
            localUri = "file:///iris.mp3",
        )
        val other = Track(
            id = "plex:zombie",
            title = "Zombie",
            artist = "The Cranberries",
            album = "No Need to Argue",
            durationMs = 305_000L,
            streamUrl = "plex-stream-2",
            downloadUrl = "",
        )
        val catalog = CatalogSnapshot(
            tracksByParent = mapOf("all" to listOf(plexIris, localIris, other)),
        )
        val state = deriveHomeUiState(
            catalog = catalog,
            playHistory = PlayHistorySnapshot(
                topRecentlyPlayed = listOf(
                    RecentlyPlayedEntry("plex:iris", 500L, plexIris.artist, plexIris.album),
                    RecentlyPlayedEntry("local:folder:iris", 400L, localIris.artist, localIris.album),
                    RecentlyPlayedEntry("plex:zombie", 300L, other.artist, other.album),
                ),
            ),
            randomArtistSeed = 1,
            randomAlbumSeed = 2,
            nowMs = 1_000L,
            limit = 10,
            includeTrackDerivedSections = false,
        )

        assertEquals(listOf("plex:iris", "plex:zombie"), state.recentlyPlayedTracks.map { it.track.id })
        assertEquals(
            plexIris.playHistoryIdentityKey(),
            localIris.playHistoryIdentityKey(),
        )
    }

    @Test
    fun recentlyPlayedCollapsesSameRecordingFromDifferentAlbums() {
        val albumTrack = Track(
            id = "plex:album-beautiful-day",
            title = "Beautiful Day",
            artist = "U2",
            album = "All That You Can't Leave Behind",
            durationMs = 246_000L,
            streamUrl = "plex-stream",
            downloadUrl = "",
        )
        val compilationTrack = albumTrack.copy(
            id = "plex:compilation-beautiful-day",
            album = "The Anthems 09",
        )
        val catalog = CatalogSnapshot(
            tracksByParent = mapOf("all" to listOf(albumTrack, compilationTrack)),
        )
        val state = deriveHomeUiState(
            catalog = catalog,
            playHistory = PlayHistorySnapshot(
                topRecentlyPlayed = listOf(
                    RecentlyPlayedEntry(albumTrack.id, 500L, albumTrack.artist, albumTrack.album),
                    RecentlyPlayedEntry(compilationTrack.id, 400L, compilationTrack.artist, compilationTrack.album),
                ),
            ),
            randomArtistSeed = 1,
            randomAlbumSeed = 2,
            nowMs = 1_000L,
            limit = 10,
            includeTrackDerivedSections = false,
        )

        assertEquals(listOf(albumTrack.id), state.recentlyPlayedTracks.map { it.track.id })
        assertEquals(albumTrack.playHistoryIdentityKey(), compilationTrack.playHistoryIdentityKey())
    }

    @Test
    fun mostPlayedRendersFromRankedMetadataBeforeTrackIsResolved() {
        val state = deriveHomeUiState(
            catalog = CatalogSnapshot(),
            playHistory = PlayHistorySnapshot(
                topMostPlayed = listOf(
                    MostPlayedEntry(
                        trackId = "plex:top",
                        playCount = 29L,
                        lastPlayedMs = 123L,
                        artist = "Zach Bryan",
                        album = "With Heaven on Top",
                    ),
                ),
            ),
            randomArtistSeed = 1,
            randomAlbumSeed = 2,
            nowMs = 1_000L,
            includeTrackDerivedSections = false,
        )

        assertEquals(listOf("plex:top"), state.mostPlayedTracks.map { it.track.id })
        assertEquals("Zach Bryan", state.mostPlayedTracks.single().track.artist)
        assertEquals(29L, state.mostPlayedTracks.single().playCount)
    }

    @Test
    fun playedSectionsDoNotRenderRemovedLocalFolderHistoryPlaceholders() {
        val state = deriveHomeUiState(
            catalog = CatalogSnapshot(),
            playHistory = PlayHistorySnapshot(
                topRecentlyPlayed = listOf(
                    RecentlyPlayedEntry(
                        trackId = "local_lf-old:track:1",
                        lastPlayedMs = 500L,
                        artist = "Animal Collective",
                        album = "Feels",
                    ),
                ),
                topMostPlayed = listOf(
                    MostPlayedEntry(
                        trackId = "local_lf-old:track:1",
                        playCount = 12L,
                        lastPlayedMs = 500L,
                        artist = "Animal Collective",
                        album = "Feels",
                    ),
                ),
            ),
            randomArtistSeed = 1,
            randomAlbumSeed = 2,
            nowMs = 1_000L,
            includeTrackDerivedSections = false,
        )

        assertTrue(state.recentlyPlayedTracks.isEmpty())
        assertTrue(state.mostPlayedTracks.isEmpty())
    }

    @Test
    fun homeDerivationSkipsInitialDelayWhenRankedHistoryIsWaiting() {
        assertEquals(
            0L,
            homeUiStateDeriveDelayMs(
                catalogSyncInProgress = true,
                trackHeavySectionsEnabled = true,
                hasRankedPlayHistory = true,
                hasRenderedPlayHistory = false,
            ),
        )
        assertEquals(
            250L,
            homeUiStateDeriveDelayMs(
                catalogSyncInProgress = true,
                trackHeavySectionsEnabled = true,
                hasRankedPlayHistory = true,
                hasRenderedPlayHistory = true,
            ),
        )
    }

    @Test
    fun recentTracksFallBackToAlbumDateAdded() {
        val track = Track(
            id = "track-without-date",
            title = "Song Without Track Date",
            artist = "Artist",
            album = "Fresh Album",
            durationMs = 1_000L,
            streamUrl = "",
            downloadUrl = "",
            dateAddedMs = null,
        )
        val catalog = CatalogSnapshot(
            albums = listOf(Album("album", "Fresh Album", "Artist", dateAddedMs = 100L)),
            tracksByParent = mapOf("album" to listOf(track)),
        )

        val state = deriveHomeUiState(
            catalog = catalog,
            playHistory = PlayHistorySnapshot(),
            randomArtistSeed = 1,
            randomAlbumSeed = 2,
            nowMs = 100L,
        )

        assertEquals(listOf("track-without-date"), state.recentlyAddedTracks.map { it.id })
    }

    @Test
    fun featuredArtistStatsKeepProviderCountsWhenTracksArePartiallyLoaded() {
        val artist = Artist(
            id = "artist",
            title = "Artist",
            albumCount = 4,
            songCount = 17,
        )
        val album = Album("album", "Album", "Artist")
        val loadedTrack = Track("track", "Track", "Artist", "Album", 1_000L, "stream", "")
        val catalog = CatalogSnapshot(
            artists = listOf(artist),
            albums = listOf(album),
            tracksByParent = mapOf(album.id to listOf(loadedTrack)),
        )

        val state = deriveHomeUiState(
            catalog = catalog,
            playHistory = PlayHistorySnapshot(),
            randomArtistSeed = 1,
            randomAlbumSeed = 2,
            nowMs = 100L,
        )

        assertEquals(4, state.randomArtistStats?.albumCount)
        assertEquals(17, state.randomArtistStats?.trackCount)
    }

    @Test
    fun derivesFavoriteHomeSections() {
        val catalog = CatalogSnapshot(
            artists = listOf(
                Artist("artist-b", "Beta", favorite = true),
                Artist("artist-a", "Alpha", favorite = true),
                Artist("artist-c", "Gamma"),
            ),
            albums = listOf(
                Album("album-b", "Beta Album", "Artist", favorite = true),
                Album("album-a", "Alpha Album", "Artist", favorite = true),
            ),
            playlists = listOf(
                Playlist("playlist-b", "Beta Mix", 2, favorite = true),
                Playlist("playlist-a", "Alpha Mix", 4, favorite = true),
            ),
        )

        val state = deriveHomeUiState(catalog, PlayHistorySnapshot(), 1, 2, nowMs = 100L)

        assertEquals(listOf("artist-a", "artist-b"), state.favoriteArtists.map { it.id })
        assertEquals(listOf("album-a", "album-b"), state.favoriteAlbums.map { it.id })
        assertEquals(listOf("playlist-a", "playlist-b"), state.favoritePlaylists.map { it.id })
    }

    @Test
    fun decadeMixUsesLoadedTrackYears() {
        val catalog = CatalogSnapshot(
            tracksByParent = mapOf(
                "all" to listOf(
                    Track("a", "A", "Artist", "Album", 1_000L, "stream", "", year = 1991),
                    Track("b", "B", "Artist", "Album", 1_000L, "stream", "", year = 1999),
                    Track("c", "C", "Artist", "Album", 1_000L, "stream", "", year = 2001),
                ),
            ),
        )

        assertEquals(listOf(2000, 1990), availableDecades(catalog))
        assertEquals(setOf("a", "b"), decadeMix(catalog, 1990).map { it.id }.toSet())
    }

    @Test
    fun defaultMixDecadesCoverTwentiethCenturyThroughCurrentPickerRange() {
        assertEquals(2020, defaultMixDecades().first())
        assertEquals(1900, defaultMixDecades().last())
    }

    @Test
    fun personalMixFallsBackToLibraryWhenHistoryIsEmpty() {
        val tracks = (1..4).map {
            Track("t$it", "Track $it", "Artist", "Album", 1_000L, "stream", "", year = 2000 + it)
        }
        val catalog = CatalogSnapshot(tracksByParent = mapOf("all" to tracks))
        val state = deriveHomeUiState(catalog, PlayHistorySnapshot(), 1, 2, nowMs = 10L)

        assertEquals(tracks.map { it.id }.toSet(), personalMix(catalog, state, limit = 10).map { it.id }.toSet())
    }

    @Test
    fun heavyRotationTracksUseRecentPlayFrequency() {
        val nowMs = 1_000_000_000L
        val tracks = (1..4).map {
            Track("t$it", "Track $it", "Artist", "Album", 1_000L, "stream", "")
        }
        val catalog = CatalogSnapshot(tracksByParent = mapOf("all" to tracks))

        val state = deriveHomeUiState(
            catalog = catalog,
            playHistory = PlayHistorySnapshot(
                byTrack = mapOf("t1" to nowMs - 1_000L, "t2" to nowMs - 2_000L, "t3" to nowMs - 3_000L),
                playCountByTrack = mapOf("t1" to 20L, "t2" to 50L, "t3" to 2L),
                playEventsByTrack = mapOf(
                    "t1" to listOf(nowMs - 1_000L, nowMs - 2_000L, nowMs - 3_000L),
                    "t2" to listOf(nowMs - 2_000L, nowMs - 20L * 24L * 60L * 60L * 1000L),
                    "t3" to listOf(nowMs - 3_000L, nowMs - 4_000L),
                ),
            ),
            randomArtistSeed = 1,
            randomAlbumSeed = 2,
            nowMs = nowMs,
        )

        assertEquals(listOf("t1", "t3"), state.heavyRotationTracks.map { it.track.id })
        assertEquals(listOf(3L, 2L), state.heavyRotationTracks.map { it.playCount })
    }

    @Test
    fun personalMixStartsWithHeavyRotationAndLimitsDiscovery() {
        val tracks = listOf(
            Track("heavy1", "Heavy 1", "Comfort A", "Album 1", 1_000L, "stream", "", year = 2001, genre = "Rock"),
            Track("heavy2", "Heavy 2", "Comfort B", "Album 2", 1_000L, "stream", "", year = 2002, genre = "Rock"),
            Track("recent1", "Recent 1", "Comfort C", "Album 3", 1_000L, "stream", "", year = 2003, genre = "Rock"),
            Track("recent2", "Recent 2", "Comfort D", "Album 4", 1_000L, "stream", "", year = 2004, genre = "Rock"),
            Track("most1", "Most 1", "Comfort E", "Album 5", 1_000L, "stream", "", year = 2005, genre = "Rock"),
            Track("most2", "Most 2", "Comfort F", "Album 6", 1_000L, "stream", "", year = 2006, genre = "Rock"),
            Track("similar1", "Similar 1", "Comfort A", "Album 7", 1_000L, "stream", "", year = 2007, genre = "Rock"),
            Track("similar2", "Similar 2", "Comfort B", "Album 8", 1_000L, "stream", "", year = 2008, genre = "Rock"),
            Track("new1", "New 1", "Discovery", "Album 9", 1_000L, "stream", "", dateAddedMs = 10L),
            Track("new2", "New 2", "Discovery", "Album 10", 1_000L, "stream", "", dateAddedMs = 9L),
        )
        val catalog = CatalogSnapshot(tracksByParent = mapOf("all" to tracks))
        val state = HomeUiState(
            heavyRotationTracks = tracks.take(2).map { HomePlayedTrack(it, playCount = 3L) },
            recentlyPlayedTracks = tracks.slice(2..3).map { HomePlayedTrack(it, playCount = 1L) },
            mostPlayedTracks = tracks.slice(4..5).map { HomePlayedTrack(it, playCount = 10L) },
        )

        val mix = personalMix(
            catalog,
            state,
            limit = 8,
            playHistory = establishedPlayHistory(),
        ).map { it.id }

        assertEquals(setOf("heavy1", "heavy2"), mix.take(2).toSet())
        assertTrue(mix.count { it.startsWith("new") } <= 1)
    }

    @Test
    fun personalMixDedupesLogicalSongsAcrossParentsAndProviderIds() {
        val original = Track("plex:101", "Same Song", "Artist", "Album", 1_000L, "stream", "")
        val unprefixed = original.copy(id = "101")
        val differentIdSameMetadata = original.copy(id = "local-copy")
        val other = Track("other", "Other Song", "Artist", "Other Album", 1_000L, "stream", "")
        val catalog = CatalogSnapshot(
            tracksByParent = mapOf(
                "album" to listOf(original, other),
                "playlist" to listOf(unprefixed, differentIdSameMetadata),
            ),
        )
        val state = HomeUiState(
            heavyRotationTracks = listOf(HomePlayedTrack(original, playCount = 3L)),
            recentlyPlayedTracks = listOf(HomePlayedTrack(unprefixed, playCount = 1L)),
            mostPlayedTracks = listOf(HomePlayedTrack(differentIdSameMetadata, playCount = 10L)),
        )

        val mix = personalMix(
            catalog = catalog,
            state = state,
            limit = 35,
            playHistory = establishedPlayHistory(),
        )

        assertEquals(listOf("Same Song", "Other Song").toSet(), mix.map { it.title }.toSet())
        assertEquals(mix.size, mix.map { it.title to it.artist to it.album to it.durationMs }.toSet().size)
    }

    @Test
    fun personalMixUsesCustomWeights() {
        val heavy = (1..12).map {
            Track("heavy$it", "Heavy $it", "Artist $it", "Album $it", 1_000L, "stream", "")
        }
        val other = (1..8).map {
            Track("other$it", "Other $it", "Other", "Album", 1_000L, "stream", "", dateAddedMs = it.toLong())
        }
        val catalog = CatalogSnapshot(tracksByParent = mapOf("all" to heavy + other))
        val state = HomeUiState(
            heavyRotationTracks = heavy.map { HomePlayedTrack(it, playCount = 3L) },
        )

        val mix = personalMix(
            catalog = catalog,
            state = state,
            preferences = PersonalMixPreferences(
                limit = 10,
                heavyRotationWeight = 100,
                recentWeight = 0,
                mostPlayedWeight = 0,
                similarWeight = 0,
                discoveryWeight = 0,
            ),
            playHistory = establishedPlayHistory(),
        )

        assertEquals(10, mix.size)
        assertTrue(mix.all { it.id.startsWith("heavy") })
    }

    @Test
    fun playedSectionsResolveTracksWithoutFullCatalogIndex() {
        val tracks = listOf(
            Track("t1", "One", "Artist", "Album", 1_000L, "stream", ""),
            Track("t2", "Two", "Artist", "Album", 1_000L, "stream", ""),
        )
        val catalog = CatalogSnapshot(tracksByParent = mapOf("album" to tracks))
        val playHistory = PlayHistorySnapshot(
            byTrack = mapOf("t1" to 50L, "t2" to 40L),
            playCountByTrack = mapOf("t1" to 3L, "t2" to 9L),
            topRecentlyPlayed = listOf(RecentlyPlayedEntry("t1", 50L, "", "")),
            topMostPlayed = listOf(MostPlayedEntry("t2", 9L, 40L, "", "")),
        )

        val state = deriveHomeUiState(
            catalog = catalog,
            playHistory = playHistory,
            randomArtistSeed = 1,
            randomAlbumSeed = 2,
            nowMs = 100L,
            limit = 1,
            includeTrackDerivedSections = false,
        )

        assertEquals("t1", state.recentlyPlayedTracks.single().track.id)
        assertEquals("t2", state.mostPlayedTracks.single().track.id)
        assertTrue(state.recentlyAddedTracks.isEmpty())
    }

    @Test
    fun playedSectionsSkipUnresolvedTracksAndBackfillResolvableOnes() {
        val tracks = listOf(
            Track("loaded-recent", "Recent", "Artist", "Album", 1_000L, "stream", ""),
            Track("loaded-most", "Most", "Artist", "Album", 1_000L, "stream", ""),
        )
        val catalog = CatalogSnapshot(tracksByParent = mapOf("album" to tracks))
        val playHistory = PlayHistorySnapshot(
            byTrack = mapOf(
                "missing-recent-1" to 500L,
                "missing-recent-2" to 400L,
                "loaded-recent" to 300L,
                "loaded-most" to 200L,
            ),
            playCountByTrack = mapOf(
                "missing-most-1" to 40L,
                "missing-most-2" to 30L,
                "loaded-most" to 20L,
                "loaded-recent" to 10L,
            ),
            topRecentlyPlayed = listOf(
                RecentlyPlayedEntry("missing-recent-1", 500L, "", ""),
                RecentlyPlayedEntry("missing-recent-2", 400L, "", ""),
                RecentlyPlayedEntry("loaded-recent", 300L, "", ""),
            ),
            topMostPlayed = listOf(
                MostPlayedEntry("missing-most-1", 40L, 0L, "", ""),
                MostPlayedEntry("missing-most-2", 30L, 0L, "", ""),
                MostPlayedEntry("loaded-most", 20L, 200L, "", ""),
            ),
        )

        val state = deriveHomeUiState(
            catalog = catalog,
            playHistory = playHistory,
            randomArtistSeed = 1,
            randomAlbumSeed = 2,
            nowMs = 1_000L,
            limit = 1,
            includeTrackDerivedSections = false,
        )

        assertEquals("loaded-recent", state.recentlyPlayedTracks.single().track.id)
        assertEquals("loaded-most", state.mostPlayedTracks.single().track.id)
    }

    @Test
    fun homeCatalogIndexCacheMergesIncrementalTrackBatches() {
        val album = Album("al1", "Album", "Artist", dateAddedMs = 100L)
        val firstBatch = listOf(
            Track("t1", "One", "Artist", "Album", 1_000L, "", "", parentAlbumId = album.id, dateAddedMs = 100L),
        )
        val catalogPass1 = CatalogSnapshot(
            albums = listOf(album),
            tracksByParent = mapOf(album.id to firstBatch),
        )
        val cache = HomeCatalogIndexCache()
        val pass1 = deriveHomeUiState(
            catalog = catalogPass1,
            playHistory = PlayHistorySnapshot(),
            randomArtistSeed = 1,
            randomAlbumSeed = 2,
            nowMs = 200L,
            trackIndexCache = cache,
        )
        assertEquals(listOf("t1"), pass1.recentlyAddedTracks.map { it.id })

        val secondBatch = firstBatch + Track(
            "t2",
            "Two",
            "Artist",
            "Album",
            1_000L,
            "",
            "",
            parentAlbumId = album.id,
            dateAddedMs = 150L,
        )
        val catalogPass2 = catalogPass1.copy(tracksByParent = mapOf(album.id to secondBatch))
        val pass2 = deriveHomeUiState(
            catalog = catalogPass2,
            playHistory = PlayHistorySnapshot(),
            randomArtistSeed = 1,
            randomAlbumSeed = 2,
            nowMs = 200L,
            trackIndexCache = cache,
        )
        assertEquals(listOf("t2", "t1"), pass2.recentlyAddedTracks.map { it.id })
    }

    @Test
    fun mixMaturityDetectsSparseGrowingAndEstablishedHistory() {
        assertEquals(MixMaturity.Sparse, mixMaturity(PlayHistorySnapshot()))
        assertEquals(
            MixMaturity.Sparse,
            mixMaturity(
                PlayHistorySnapshot(
                    byTrack = (1..10).associate { "t$it" to it.toLong() },
                    playCountByTrack = (1..10).associate { "t$it" to 1L },
                ),
            ),
        )
        assertEquals(
            MixMaturity.Growing,
            mixMaturity(
                PlayHistorySnapshot(
                    byTrack = (1..30).associate { "t$it" to it.toLong() },
                    playCountByTrack = (1..30).associate { "t$it" to 2L },
                ),
            ),
        )
        assertEquals(MixMaturity.Established, mixMaturity(establishedPlayHistory()))
    }

    @Test
    fun effectivePersonalMixPreferencesBoostsDiscoveryWhenHistoryIsSparse() {
        val sparse = effectivePersonalMixPreferences(PersonalMixPreferences.Default, PlayHistorySnapshot())
        val established = effectivePersonalMixPreferences(PersonalMixPreferences.Default, establishedPlayHistory())

        assertTrue(sparse.discoveryWeight > established.discoveryWeight)
        assertTrue(sparse.heavyRotationWeight < established.heavyRotationWeight)
        assertEquals(PersonalMixPreferences.Default.normalized(), established)
    }

    @Test
    fun mixMaturityBlendInterpolatesBetweenSparseAndEstablished() {
        assertEquals(0.0, mixMaturityBlend(PlayHistorySnapshot()))
        assertEquals(1.0, mixMaturityBlend(establishedPlayHistory()))
        val growingBlend = mixMaturityBlend(
            PlayHistorySnapshot(
                byTrack = (1..62).associate { "t$it" to it.toLong() },
                playCountByTrack = (1..62).associate { "t$it" to 2L },
            ),
        )
        assertTrue(growingBlend > 0.0)
        assertTrue(growingBlend < 1.0)
    }

    @Test
    fun personalMixEnforcesArtistAndAlbumDiversityCaps() {
        val tracks = (1..16).map { index ->
            Track(
                id = "track-$index",
                title = "Song $index",
                artist = "Artist ${(index - 1) / 4 + 1}",
                album = "Album $index",
                durationMs = 1_000L,
                streamUrl = "stream",
                downloadUrl = "",
                year = 2000 + index,
            )
        }
        val catalog = CatalogSnapshot(tracksByParent = mapOf("all" to tracks))
        val state = HomeUiState(
            heavyRotationTracks = tracks.take(4).map { HomePlayedTrack(it, playCount = 3L) },
            recentlyPlayedTracks = tracks.slice(4..7).map { HomePlayedTrack(it, playCount = 1L) },
            mostPlayedTracks = tracks.slice(8..11).map { HomePlayedTrack(it, playCount = 10L) },
        )

        val mix = personalMix(
            catalog = catalog,
            state = state,
            limit = 35,
            playHistory = establishedPlayHistory(),
        )

        assertEquals(8, mix.size)
        assertEquals(4, mix.map { it.artist }.distinct().size)
        assertTrue(mix.groupingBy { it.artist }.eachCount().values.all { it <= 2 })
        assertEquals(mix.size, mix.map { it.album.lowercase() }.distinct().size)
    }

    @Test
    fun sparsePersonalMixIncludesMoreUnplayedTracksThanEstablishedProfile() {
        val played = (1..6).map { index ->
            Track(
                id = "played-$index",
                title = "Played $index",
                artist = "Artist $index",
                album = "Album $index",
                durationMs = 1_000L,
                streamUrl = "stream",
                downloadUrl = "",
                genre = "Rock",
                year = 2000 + index,
            )
        }
        val unplayed = (1..12).map { index ->
            Track(
                id = "unplayed-$index",
                title = "Unplayed $index",
                artist = "Discovery $index",
                album = "Discovery Album $index",
                durationMs = 1_000L,
                streamUrl = "stream",
                downloadUrl = "",
                dateAddedMs = index.toLong(),
            )
        }
        val catalog = CatalogSnapshot(tracksByParent = mapOf("all" to played + unplayed))
        val state = HomeUiState(
            heavyRotationTracks = played.take(2).map { HomePlayedTrack(it, playCount = 3L) },
            recentlyPlayedTracks = played.slice(2..3).map { HomePlayedTrack(it, playCount = 1L) },
            mostPlayedTracks = played.drop(4).map { HomePlayedTrack(it, playCount = 10L) },
        )

        val sparseMix = personalMix(catalog, state, limit = 12, playHistory = PlayHistorySnapshot())
        val establishedMix = personalMix(catalog, state, limit = 12, playHistory = establishedPlayHistory())

        assertTrue(sparseMix.count { it.id.startsWith("unplayed") } >= establishedMix.count { it.id.startsWith("unplayed") })
    }

    @Test
    fun personalMixDeprioritizesRecentlyQueuedTracks() {
        val tracks = (1..6).map { index ->
            Track(
                id = "track-$index",
                title = "Track $index",
                artist = "Artist $index",
                album = "Album $index",
                durationMs = 1_000L,
                streamUrl = "stream",
                downloadUrl = "",
            )
        }
        val catalog = CatalogSnapshot(tracksByParent = mapOf("all" to tracks))
        val state = HomeUiState(
            heavyRotationTracks = tracks.take(1).map { HomePlayedTrack(it, playCount = 3L) },
            recentlyPlayedTracks = tracks.slice(1..2).map { HomePlayedTrack(it, playCount = 1L) },
            mostPlayedTracks = tracks.drop(3).map { HomePlayedTrack(it, playCount = 10L) },
        )
        val recentKeys = tracks.take(3).map { it.personalMixIdentityKey() }.toSet()

        val mix = personalMix(
            catalog = catalog,
            state = state,
            limit = 6,
            playHistory = establishedPlayHistory(),
            recentMixTrackKeys = recentKeys,
        )

        assertTrue(mix.take(3).none { it.personalMixIdentityKey() in recentKeys })
    }

    @Test
    fun sparsePersonalMixUsesFavoriteAndRatedUnplayedTracks() {
        val played = Track("played", "Played", "Seed Artist", "Seed Album", 1_000L, "stream", "", genre = "Rock")
        val favorite = Track("favorite", "Favorite", "Favorite Artist", "Favorite Album", 1_000L, "stream", "")
        val rated = Track("rated", "Rated", "Rated Artist", "Rated Album", 1_000L, "stream", "", rating = 4f)
        val other = Track("other", "Other", "Other Artist", "Other Album", 1_000L, "stream", "", dateAddedMs = 1L)
        val catalog = CatalogSnapshot(
            artists = listOf(Artist("fav-artist", "Favorite Artist", favorite = true)),
            albums = listOf(Album("fav-album", "Favorite Album", "Favorite Artist", favorite = true)),
            tracksByParent = mapOf("all" to listOf(played, favorite, rated, other)),
        )
        val state = HomeUiState(
            heavyRotationTracks = listOf(HomePlayedTrack(played, playCount = 3L)),
        )

        val mix = personalMix(catalog, state, limit = 4, playHistory = PlayHistorySnapshot())

        assertTrue(mix.map { it.id }.containsAll(listOf("favorite", "rated", "other")))
        assertTrue(mix.size >= 3)
    }
}
