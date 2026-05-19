package com.phoebe.app

import androidx.compose.runtime.mutableStateListOf
import androidx.navigation3.runtime.NavKey
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.CollectionFacet
import com.phoebe.app.domain.CollectionTarget
import com.phoebe.app.domain.PlayHistoryKind
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.RecentlyAddedKind
import com.phoebe.app.domain.Track
import com.phoebe.app.navigation.BrowseSection
import com.phoebe.app.navigation.PhoebeNavigator
import com.phoebe.app.navigation.PhoebeRoute
import com.phoebe.app.navigation.PhoebeRouteSerializersModule
import com.phoebe.app.ui.toLegacyScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PhoebeNavigationTest {
    private val json = Json {
        serializersModule = PhoebeRouteSerializersModule
    }
    private val navKeySerializer = PolymorphicSerializer(NavKey::class)

    @Test
    fun allRoutesRoundTripThroughNavKeyPolymorphicSerialization() {
        val entry = CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre)
        val routes = listOf(
            PhoebeRoute.SignIn,
            PhoebeRoute.ServerPicker,
            PhoebeRoute.LibraryPicker,
            PhoebeRoute.Browse(BrowseSection.Search, selectedPlaylistId = "playlist-1"),
            PhoebeRoute.Collections(entry),
            PhoebeRoute.CollectionItems(entry, "Dream pop"),
            PhoebeRoute.AlbumDetail("album-1"),
            PhoebeRoute.ArtistDetail("artist-1"),
            PhoebeRoute.SongDetail("track-1"),
            PhoebeRoute.Lyrics("track-1"),
            PhoebeRoute.RecentlyAdded(RecentlyAddedKind.Albums),
            PhoebeRoute.PlayHistory(PlayHistoryKind.MostPlayed),
            PhoebeRoute.FavoritePlaylists,
            PhoebeRoute.FavoriteArtists,
            PhoebeRoute.FavoriteAlbums,
            PhoebeRoute.PlaylistDetail("playlist-1"),
            PhoebeRoute.Player,
        )

        routes.forEach { route ->
            val encoded = json.encodeToString(navKeySerializer, route)
            assertEquals(route, json.decodeFromString(navKeySerializer, encoded))
        }
    }

    @Test
    fun navigatorReplacesBrowseRootsAndPushesDetails() {
        val navigator = PhoebeNavigator(mutableStateListOf<NavKey>(PhoebeRoute.Browse()))

        navigator.open(PhoebeRoute.Browse(BrowseSection.Library))
        navigator.open(PhoebeRoute.AlbumDetail("album-1"))

        assertEquals(
            listOf(PhoebeRoute.Browse(BrowseSection.Library), PhoebeRoute.AlbumDetail("album-1")),
            navigator.backStack.toList(),
        )

        navigator.pop()

        assertEquals(listOf(PhoebeRoute.Browse(BrowseSection.Library)), navigator.backStack.toList())
    }

    @Test
    fun navigatorOpensAndClosesPlayerAboveCurrentRoute() {
        val navigator = PhoebeNavigator(mutableStateListOf<NavKey>(PhoebeRoute.Browse()))

        navigator.open(PhoebeRoute.AlbumDetail("album-1"))
        navigator.openPlayer()
        navigator.openPlayer()

        assertEquals(
            listOf(PhoebeRoute.Browse(), PhoebeRoute.AlbumDetail("album-1"), PhoebeRoute.Player),
            navigator.backStack.toList(),
        )

        navigator.handleBack()

        assertEquals(listOf(PhoebeRoute.Browse(), PhoebeRoute.AlbumDetail("album-1")), navigator.backStack.toList())
    }

    @Test
    fun setupBackBehaviorMovesTowardSignIn() {
        val navigator = PhoebeNavigator(mutableStateListOf<NavKey>(PhoebeRoute.LibraryPicker))

        assertEquals(true, navigator.canHandleBack(defaultRoute = PhoebeRoute.LibraryPicker))
        navigator.handleBack()
        assertEquals(listOf(PhoebeRoute.ServerPicker), navigator.backStack.toList())

        assertEquals(true, navigator.canHandleBack(defaultRoute = PhoebeRoute.ServerPicker))
        navigator.handleBack()
        assertEquals(listOf(PhoebeRoute.SignIn), navigator.backStack.toList())
    }

    @Test
    fun missingRouteArgumentResolvesToNullForFallbackUi() {
        val catalog = CatalogSnapshot(
            albums = listOf(Album(id = "album-1", title = "Album", artist = "Artist")),
            playlists = listOf(Playlist(id = "playlist-1", title = "Playlist", trackCount = 0)),
            tracksByParent = mapOf(
                "album-1" to listOf(
                    Track("track-1", "Song", "Artist", "Album", 180_000, "", ""),
                ),
            ),
        )

        assertNull(PhoebeRoute.AlbumDetail("missing").toLegacyScreen(catalog, currentTrack = null))
        assertNull(PhoebeRoute.PlaylistDetail("missing").toLegacyScreen(catalog, currentTrack = null))
        assertNull(PhoebeRoute.SongDetail("missing").toLegacyScreen(catalog, currentTrack = null))
    }
}
