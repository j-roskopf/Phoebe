package com.phoebe.app.ui

import com.phoebe.app.domain.AppScreen
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.CollectionFacet
import com.phoebe.app.domain.CollectionTarget
import com.phoebe.app.domain.PlayHistoryKind
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.RecentlyAddedKind
import kotlin.test.Test
import kotlin.test.assertEquals

class PhoebeWebRoutesTest {
    @Test
    fun parsesTopLevelBrowsePaths() {
        assertEquals(listOf(PhoebeRoute.Browse(BrowseSection.Home)), phoebeWebRoutesForPath("/"))
        assertEquals(listOf(PhoebeRoute.Browse(BrowseSection.Search)), phoebeWebRoutesForPath("/search"))
        assertEquals(listOf(PhoebeRoute.Browse(BrowseSection.Library)), phoebeWebRoutesForPath("/library"))
        assertEquals(listOf(PhoebeRoute.Browse(BrowseSection.Radio)), phoebeWebRoutesForPath("/radio"))
        assertEquals(listOf(PhoebeRoute.Browse(BrowseSection.Lyrics)), phoebeWebRoutesForPath("/lyrics"))
        assertEquals(listOf(PhoebeRoute.Browse(BrowseSection.Playlists)), phoebeWebRoutesForPath("/playlists"))
        assertEquals(listOf(PhoebeRoute.Browse(BrowseSection.Settings)), phoebeWebRoutesForPath("/settings"))
        assertEquals("/radio", PhoebeRoute.Browse(BrowseSection.Radio).toPhoebeWebPath())
        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Radio), PhoebeRoute.RadioCountries),
            phoebeWebRoutesForPath("/radio/countries"),
        )
        assertEquals("/radio/countries", PhoebeRoute.RadioCountries.toPhoebeWebPath())
        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Radio), PhoebeRoute.RadioMap),
            phoebeWebRoutesForPath("/radio/map"),
        )
        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Radio), PhoebeRoute.RadioMap),
            phoebeWebRoutesForPath("/radio/globe"),
        )
        assertEquals("/radio/map", PhoebeRoute.RadioMap.toPhoebeWebPath())
        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.ArtistMixBuilder),
            phoebeWebRoutesForPath("/mix/artists"),
        )
        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.AlbumMixBuilder),
            phoebeWebRoutesForPath("/mix/albums"),
        )
        assertEquals("/mix/artists", PhoebeRoute.ArtistMixBuilder.toPhoebeWebPath())
        assertEquals("/mix/albums", PhoebeRoute.AlbumMixBuilder.toPhoebeWebPath())
    }

    @Test
    fun browsePathsFallBackWhenNoBrowseSourceIsAvailable() {
        val fallback = listOf(PhoebeRoute.SignIn)

        assertEquals(fallback, phoebeWebRoutesForPath("/").withUnavailableBrowseFallback(fallback))
        assertEquals(fallback, phoebeWebRoutesForPath("/library").withUnavailableBrowseFallback(fallback))
        assertEquals(
            fallback,
            phoebeWebRoutesForPath("/artist/modern-baseball")
                .withUnavailableBrowseFallback(fallback),
        )
    }

    @Test
    fun radioPathDoesNotRequireBrowseSource() {
        val fallback = listOf(PhoebeRoute.SignIn)
        val route = listOf(PhoebeRoute.Browse(BrowseSection.Radio))

        assertEquals(route, route.withUnavailableBrowseFallback(fallback))
    }

    @Test
    fun sourceBackedBrowsePathsUseRadioFallbackWhenNoBrowseSourceIsAvailable() {
        val fallback = listOf(PhoebeRoute.Browse(BrowseSection.Radio))
        val route = listOf(PhoebeRoute.Browse(BrowseSection.Library))

        assertEquals(fallback, route.withUnavailableBrowseFallback(fallback))
    }

    @Test
    fun sourceBackedBrowsePathsRemainAvailableWhenFallbackCanBrowse() {
        val fallback = listOf(PhoebeRoute.Browse(BrowseSection.Home))
        val route = listOf(PhoebeRoute.Browse(BrowseSection.Library))

        assertEquals(route, route.withUnavailableBrowseFallback(fallback))
    }

    @Test
    fun setupPathsDoNotRequireBrowseSource() {
        val fallback = listOf(PhoebeRoute.SignIn)
        val setupRoutes = listOf(PhoebeRoute.SignIn, PhoebeRoute.ServerPicker)

        assertEquals(setupRoutes, setupRoutes.withUnavailableBrowseFallback(fallback))
    }

    @Test
    fun parsesPrettyArtistAlbumAndPlaylistPaths() {
        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.ArtistSlugDetail("modern-baseball")),
            phoebeWebRoutesForPath("/artist/modern-baseball"),
        )
        assertEquals(
            listOf(
                PhoebeRoute.Browse(BrowseSection.Home),
                PhoebeRoute.ArtistAlbumSlugDetail(
                    artistSlug = "modern-baseball",
                    albumSlug = "youre-gonna-miss-it-all",
                ),
            ),
            phoebeWebRoutesForPath("/artist/modern-baseball/album/youre-gonna-miss-it-all"),
        )
        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Playlists), PhoebeRoute.PlaylistSlugDetail("road-trip")),
            phoebeWebRoutesForPath("/playlists/road-trip"),
        )
    }

    @Test
    fun parsesHybridDetailPathsWithEncodedIds() {
        val artistId = "plex:artist/modern baseball 42"
        val albumId = "jellyfin:album/youre gonna miss it all"
        val trackId = "local:track/Mass Re-Done.mp3"

        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.ArtistDetail(artistId)),
            phoebeWebRoutesForPath("/artist/modern-baseball/${encodePhoebePathSegment(artistId)}"),
        )
        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.AlbumDetail(albumId)),
            phoebeWebRoutesForPath("/album/youre-gonna-miss-it-all/${encodePhoebePathSegment(albumId)}"),
        )
        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.SongDetail(trackId)),
            phoebeWebRoutesForPath("/track/mass-re-done/${encodePhoebePathSegment(trackId)}"),
        )
    }

    @Test
    fun parsesRawPlaylistIdsAndCanonicalizesToPrettyName() {
        val playlistId = "plex:playlist/road trip:2026"
        val route = PhoebeRoute.PlaylistDetail(playlistId)

        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Playlists), route),
            phoebeWebRoutesForPath("/playlists/${encodePhoebePathSegment(playlistId)}"),
        )
        assertEquals(
            "/playlists/road-trip",
            route.toPhoebeWebPath(
                PhoebeRouteResolution.Resolved(
                    route = route,
                    screen = AppScreen.PlaylistDetail(
                        Playlist(
                            id = playlistId,
                            title = "Road Trip",
                            trackCount = 12,
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun parsesCollectionsRecentsHistoryAndFavorites() {
        val entry = CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre)
        val value = "Midwest emo / indie: rock"

        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.Collections(entry)),
            phoebeWebRoutesForPath("/collections/artists/genre"),
        )
        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.CollectionItems(entry, value)),
            phoebeWebRoutesForPath("/collections/artists/genre/${encodePhoebePathSegment(value)}"),
        )
        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.RecentlyAdded(RecentlyAddedKind.Albums)),
            phoebeWebRoutesForPath("/recently-added/albums"),
        )
        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.PlayHistory(PlayHistoryKind.MostPlayed)),
            phoebeWebRoutesForPath("/history/most-played"),
        )
        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.FavoriteArtists),
            phoebeWebRoutesForPath("/favorites/artists"),
        )
    }

    @Test
    fun canonicalizesResolvedDetailRoutesToPrettyNames() {
        val route = PhoebeRoute.ArtistDetail("plex:artist/123")

        assertEquals(
            "/artist/modern-baseball",
            route.toPhoebeWebPath(
                PhoebeRouteResolution.Resolved(
                    route = route,
                    screen = AppScreen.ArtistDetail(
                        Artist(
                            id = "plex:artist/123",
                            title = "Modern Baseball",
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun resolvesPrettyRoutesAgainstCatalog() {
        val artist = Artist(id = "plex:artist/123", title = "Modern Baseball")
        val album = Album(id = "plex:album/456", title = "You're Gonna Miss It All", artist = "Modern Baseball")
        val playlist = Playlist(id = "plex:playlist/789", title = "Road Trip", trackCount = 12)
        val catalog = CatalogSnapshot(
            artists = listOf(artist),
            albums = listOf(album),
            playlists = listOf(playlist),
        )

        assertEquals(
            PhoebeRouteResolution.Resolved(
                route = PhoebeRoute.ArtistSlugDetail("modern-baseball"),
                screen = AppScreen.ArtistDetail(artist),
            ),
            resolvePhoebeRoute(PhoebeRoute.ArtistSlugDetail("modern-baseball"), catalog, null),
        )
        assertEquals(
            PhoebeRouteResolution.Resolved(
                route = PhoebeRoute.ArtistAlbumSlugDetail("modern-baseball", "youre-gonna-miss-it-all"),
                screen = AppScreen.AlbumDetail(album),
            ),
            resolvePhoebeRoute(
                PhoebeRoute.ArtistAlbumSlugDetail("modern-baseball", "youre-gonna-miss-it-all"),
                catalog,
                null,
            ),
        )
        assertEquals(
            PhoebeRouteResolution.Resolved(
                route = PhoebeRoute.PlaylistSlugDetail("road-trip"),
                screen = AppScreen.PlaylistDetail(playlist),
            ),
            resolvePhoebeRoute(PhoebeRoute.PlaylistSlugDetail("road-trip"), catalog, null),
        )
    }

    @Test
    fun fallsBackUnknownPathsToHome() {
        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Home)),
            phoebeWebRoutesForPath("/not-a-real-place"),
        )
    }
}
