package com.phoebe.app

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.CollectionFacet
import com.phoebe.app.domain.CollectionTarget
import com.phoebe.app.domain.LocalFolderMediaSourceConfig
import com.phoebe.app.domain.MediaSourcesState
import com.phoebe.app.domain.PlayHistoryKind
import com.phoebe.app.domain.RecentlyAddedKind
import com.phoebe.app.ui.AppNavigationRequest
import com.phoebe.app.ui.BrowseSection
import com.phoebe.app.ui.PhoebeNavigator
import com.phoebe.app.ui.PhoebeRoute
import com.phoebe.app.ui.PhoebeRouteResolution
import com.phoebe.app.ui.canBrowseMainSections
import com.phoebe.app.ui.decodePhoebeRouteBackStack
import com.phoebe.app.ui.encodePhoebeRouteBackStack
import com.phoebe.app.ui.phoebeRouteSerializersModule
import com.phoebe.app.ui.phoebeWebRoutesForPath
import com.phoebe.app.ui.resolvePhoebeRoute
import com.phoebe.app.ui.toPhoebeWebPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json

class PhoebeNavigationTest {
    private val json = Json {
        serializersModule = phoebeRouteSerializersModule
        classDiscriminator = "type"
    }

    @Test
    fun serializesAllRouteVariantsThroughNavKeyModule() {
        val entry = CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre)
        val routes = listOf(
            PhoebeRoute.SignIn,
            PhoebeRoute.ServerPicker,
            PhoebeRoute.LibraryPicker,
            PhoebeRoute.Browse(BrowseSection.Home),
            PhoebeRoute.Browse(BrowseSection.Search),
            PhoebeRoute.RadioCountries,
            PhoebeRoute.RadioGlobe,
            PhoebeRoute.RadioMap,
            PhoebeRoute.RadioCountry("US"),
            PhoebeRoute.RadioStation("station-uuid-1"),
            PhoebeRoute.Collections(entry),
            PhoebeRoute.CollectionItems(entry, "Dream pop"),
            PhoebeRoute.ArtistDetail("artist-1"),
            PhoebeRoute.AlbumDetail("album-1"),
            PhoebeRoute.SongDetail("track-1"),
            PhoebeRoute.Lyrics("track-1"),
            PhoebeRoute.Lyrics(),
            PhoebeRoute.RecentlyAdded(RecentlyAddedKind.Songs),
            PhoebeRoute.PlayHistory(PlayHistoryKind.MostPlayed),
            PhoebeRoute.ArtistMixBuilder,
            PhoebeRoute.AlbumMixBuilder,
            PhoebeRoute.FavoritePlaylists,
            PhoebeRoute.FavoriteArtists,
            PhoebeRoute.FavoriteAlbums,
            PhoebeRoute.PlaylistDetail("playlist-1"),
            PhoebeRoute.Player,
        )

        routes.forEach { route ->
            val encoded = json.encodeToString(PolymorphicSerializer(NavKey::class), route)
            val decoded = json.decodeFromString(PolymorphicSerializer(NavKey::class), encoded)

            assertEquals(route, decoded)
        }
    }

    @Test
    fun recentlyAddedRouteRoundTripsThroughSaveableBackStackJson() {
        val backStack = NavBackStack<PhoebeRoute>(PhoebeRoute.Browse(BrowseSection.Home)).apply {
            add(PhoebeRoute.RecentlyAdded(RecentlyAddedKind.Songs))
        }
        val decoded = decodePhoebeRouteBackStack(encodePhoebeRouteBackStack(backStack))

        assertEquals(
            listOf(
                PhoebeRoute.Browse(BrowseSection.Home),
                PhoebeRoute.RecentlyAdded(RecentlyAddedKind.Songs),
            ),
            decoded.toList(),
        )
    }

    @Test
    fun radioCountryWebRouteRoundTrips() {
        val routes = phoebeWebRoutesForPath("/radio/us")

        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Radio), PhoebeRoute.RadioCountry("US")),
            routes,
        )
        assertEquals("/radio/US", routes.last().toPhoebeWebPath())
    }

    @Test
    fun radioCountriesWebRouteRoundTrips() {
        val routes = phoebeWebRoutesForPath("/radio/countries")

        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Radio), PhoebeRoute.RadioCountries),
            routes,
        )
        assertEquals("/radio/countries", routes.last().toPhoebeWebPath())
    }

    @Test
    fun radioMapWebRouteRoundTrips() {
        val routes = phoebeWebRoutesForPath("/radio/map")

        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Radio), PhoebeRoute.RadioMap),
            routes,
        )
        assertEquals("/radio/map", routes.last().toPhoebeWebPath())
        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Radio), PhoebeRoute.RadioMap),
            phoebeWebRoutesForPath("/radio/globe"),
        )
    }

    @Test
    fun radioStationWebRouteRoundTrips() {
        val route = PhoebeRoute.RadioStation("station/with spaces")
        val routes = phoebeWebRoutesForPath(route.toPhoebeWebPath())

        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Radio), route),
            routes,
        )
    }

    @Test
    fun recommendedRadioStationWebRouteRoundTrips() {
        val route = PhoebeRoute.RadioStation("recommended:bbc-radio-6-music")
        val routes = phoebeWebRoutesForPath("/radio/recommended%3Abbc-radio-6-music")

        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Radio), route),
            routes,
        )
        assertEquals("/radio/recommended%3Abbc-radio-6-music", route.toPhoebeWebPath())
    }

    @Test
    fun browseRootReplacementKeepsSingleRootRoute() {
        val navigator = PhoebeNavigator(PhoebeRoute.SignIn)

        navigator.handle(AppNavigationRequest.Home)
        navigator.openBrowse(BrowseSection.Library)

        assertEquals(listOf(PhoebeRoute.Browse(BrowseSection.Library)), navigator.routes)
    }

    @Test
    fun homeRequestDoesNotResetActiveBrowseSection() {
        val navigator = PhoebeNavigator(PhoebeRoute.Browse())

        navigator.openBrowse(BrowseSection.Playlists)
        navigator.handle(AppNavigationRequest.Home)

        assertEquals(listOf(PhoebeRoute.Browse(BrowseSection.Playlists)), navigator.routes)
    }

    @Test
    fun homeRequestDoesNotClearActiveBrowseDetailStack() {
        val navigator = PhoebeNavigator(PhoebeRoute.Browse())

        navigator.openBrowse(BrowseSection.Playlists)
        navigator.open(PhoebeRoute.PlaylistDetail("playlist-1"))
        navigator.handle(AppNavigationRequest.Home)

        assertEquals(
            listOf(
                PhoebeRoute.Browse(BrowseSection.Playlists),
                PhoebeRoute.PlaylistDetail("playlist-1"),
            ),
            navigator.routes,
        )
    }

    @Test
    fun homeRequestStillLeavesSetupFlow() {
        val navigator = PhoebeNavigator(PhoebeRoute.SignIn)

        navigator.handle(AppNavigationRequest.ServerPicker)
        navigator.handle(AppNavigationRequest.LibraryPicker)
        navigator.handle(AppNavigationRequest.Home)

        assertEquals(listOf(PhoebeRoute.Browse()), navigator.routes)
    }

    @Test
    fun collectionDrillDownPopReturnsToCollectionsRoute() {
        val navigator = PhoebeNavigator(PhoebeRoute.Browse())
        val entry = CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre)

        navigator.open(PhoebeRoute.Collections(entry))
        navigator.open(PhoebeRoute.CollectionItems(entry, "Rock"))
        navigator.pop()

        assertEquals(PhoebeRoute.Collections(entry), navigator.currentRoute)
        assertEquals(
            listOf(PhoebeRoute.Browse(), PhoebeRoute.Collections(entry)),
            navigator.routes,
        )
    }

    @Test
    fun detailPushAndPopReturnsToBrowseRoot() {
        val navigator = PhoebeNavigator(PhoebeRoute.Browse())

        navigator.open(PhoebeRoute.ArtistDetail("artist-1"))
        assertEquals(PhoebeRoute.ArtistDetail("artist-1"), navigator.currentRoute)

        navigator.pop()

        assertEquals(listOf(PhoebeRoute.Browse()), navigator.routes)
    }

    @Test
    fun playerOpenAndClosePreservesPreviousRoute() {
        val navigator = PhoebeNavigator(PhoebeRoute.Browse())

        navigator.open(PhoebeRoute.SongDetail("track-1"))
        navigator.openPlayer()
        navigator.openPlayer()

        assertEquals(
            listOf(PhoebeRoute.Browse(), PhoebeRoute.SongDetail("track-1"), PhoebeRoute.Player),
            navigator.routes,
        )

        navigator.pop()

        assertEquals(PhoebeRoute.SongDetail("track-1"), navigator.currentRoute)
    }

    @Test
    fun setupFlowBackBehaviorUsesOwnedBackStack() {
        val navigator = PhoebeNavigator(PhoebeRoute.SignIn)

        navigator.handle(AppNavigationRequest.ServerPicker)
        navigator.handle(AppNavigationRequest.LibraryPicker)
        navigator.pop()

        assertEquals(listOf(PhoebeRoute.SignIn, PhoebeRoute.ServerPicker), navigator.routes)
    }

    @Test
    fun missingDomainObjectResolvesToFallback() {
        val resolution = resolvePhoebeRoute(
            route = PhoebeRoute.ArtistDetail("missing-artist"),
            catalog = CatalogSnapshot(),
            currentTrack = null,
        )

        assertIs<PhoebeRouteResolution.Missing>(resolution)
    }

    @Test
    fun mixBuildersResolveToFallbackWhenUnavailable() {
        val artistResolution = resolvePhoebeRoute(
            route = PhoebeRoute.ArtistMixBuilder,
            catalog = CatalogSnapshot(),
            currentTrack = null,
            showArtistAlbumMixBuilders = false,
        )
        val albumResolution = resolvePhoebeRoute(
            route = PhoebeRoute.AlbumMixBuilder,
            catalog = CatalogSnapshot(),
            currentTrack = null,
            showArtistAlbumMixBuilders = false,
        )

        assertIs<PhoebeRouteResolution.Missing>(artistResolution)
        assertIs<PhoebeRouteResolution.Missing>(albumResolution)
    }

    @Test
    fun mixBuildersResolveWhenLibraryOrLocalSourceAvailable() {
        val artistResolution = resolvePhoebeRoute(
            route = PhoebeRoute.ArtistMixBuilder,
            catalog = CatalogSnapshot(),
            currentTrack = null,
            showArtistAlbumMixBuilders = true,
        )
        val albumResolution = resolvePhoebeRoute(
            route = PhoebeRoute.AlbumMixBuilder,
            catalog = CatalogSnapshot(),
            currentTrack = null,
            showArtistAlbumMixBuilders = true,
        )

        assertIs<PhoebeRouteResolution.Resolved>(artistResolution)
        assertIs<PhoebeRouteResolution.Resolved>(albumResolution)
    }

    @Test
    fun enabledLocalFoldersAllowSourceBackedBrowseSections() {
        val mediaSources = MediaSourcesState(
            localFolders = listOf(
                LocalFolderMediaSourceConfig(
                    id = "local-folder-1",
                    rootUri = "file:///Music",
                    label = "Music",
                ),
            ),
        )

        assertTrue(canBrowseMainSections(session = null, mediaSources = mediaSources))
    }
}
