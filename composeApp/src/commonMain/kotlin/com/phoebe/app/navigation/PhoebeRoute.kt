package com.phoebe.app.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.CollectionFacet
import com.phoebe.app.domain.CollectionTarget
import com.phoebe.app.domain.MediaSourcesState
import com.phoebe.app.domain.PlayHistoryKind
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.RecentlyAddedKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Serializable
enum class BrowseSection {
    Home,
    Search,
    Library,
    Lyrics,
    Playlists,
    Settings,
}

@Serializable
sealed interface PhoebeRoute : NavKey {
    @Serializable
    data object SignIn : PhoebeRoute

    @Serializable
    data object ServerPicker : PhoebeRoute

    @Serializable
    data object LibraryPicker : PhoebeRoute

    @Serializable
    data class Browse(
        val section: BrowseSection = BrowseSection.Home,
        val selectedPlaylistId: String? = null,
    ) : PhoebeRoute

    @Serializable
    data class Collections(
        val entry: CollectionEntry = CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre),
    ) : PhoebeRoute

    @Serializable
    data class CollectionItems(
        val entry: CollectionEntry,
        val value: String,
    ) : PhoebeRoute

    @Serializable
    data class AlbumDetail(val albumId: String) : PhoebeRoute

    @Serializable
    data class ArtistDetail(val artistId: String) : PhoebeRoute

    @Serializable
    data class SongDetail(val trackId: String) : PhoebeRoute

    @Serializable
    data class Lyrics(val trackId: String? = null) : PhoebeRoute

    @Serializable
    data class RecentlyAdded(val kind: RecentlyAddedKind) : PhoebeRoute

    @Serializable
    data class PlayHistory(val kind: PlayHistoryKind) : PhoebeRoute

    @Serializable
    data object FavoritePlaylists : PhoebeRoute

    @Serializable
    data object FavoriteArtists : PhoebeRoute

    @Serializable
    data object FavoriteAlbums : PhoebeRoute

    @Serializable
    data class PlaylistDetail(val playlistId: String) : PhoebeRoute

    @Serializable
    data object Player : PhoebeRoute
}

sealed interface PhoebeNavigationCommand {
    data class Open(val route: PhoebeRoute) : PhoebeNavigationCommand
    data class ReplaceRoot(val route: PhoebeRoute) : PhoebeNavigationCommand
    data class ReplaceAll(val routes: List<PhoebeRoute>) : PhoebeNavigationCommand
    data object Pop : PhoebeNavigationCommand
}

val PhoebeRouteSerializersModule: SerializersModule = SerializersModule {
    polymorphic(NavKey::class) {
        subclass(PhoebeRoute.SignIn::class, PhoebeRoute.SignIn.serializer())
        subclass(PhoebeRoute.ServerPicker::class, PhoebeRoute.ServerPicker.serializer())
        subclass(PhoebeRoute.LibraryPicker::class, PhoebeRoute.LibraryPicker.serializer())
        subclass(PhoebeRoute.Browse::class, PhoebeRoute.Browse.serializer())
        subclass(PhoebeRoute.Collections::class, PhoebeRoute.Collections.serializer())
        subclass(PhoebeRoute.CollectionItems::class, PhoebeRoute.CollectionItems.serializer())
        subclass(PhoebeRoute.AlbumDetail::class, PhoebeRoute.AlbumDetail.serializer())
        subclass(PhoebeRoute.ArtistDetail::class, PhoebeRoute.ArtistDetail.serializer())
        subclass(PhoebeRoute.SongDetail::class, PhoebeRoute.SongDetail.serializer())
        subclass(PhoebeRoute.Lyrics::class, PhoebeRoute.Lyrics.serializer())
        subclass(PhoebeRoute.RecentlyAdded::class, PhoebeRoute.RecentlyAdded.serializer())
        subclass(PhoebeRoute.PlayHistory::class, PhoebeRoute.PlayHistory.serializer())
        subclass(PhoebeRoute.FavoritePlaylists::class, PhoebeRoute.FavoritePlaylists.serializer())
        subclass(PhoebeRoute.FavoriteArtists::class, PhoebeRoute.FavoriteArtists.serializer())
        subclass(PhoebeRoute.FavoriteAlbums::class, PhoebeRoute.FavoriteAlbums.serializer())
        subclass(PhoebeRoute.PlaylistDetail::class, PhoebeRoute.PlaylistDetail.serializer())
        subclass(PhoebeRoute.Player::class, PhoebeRoute.Player.serializer())
    }
}

val PhoebeRouteSavedStateConfiguration: SavedStateConfiguration = SavedStateConfiguration {
    serializersModule = PhoebeRouteSerializersModule
}

val PhoebeRoute.telemetryName: String
    get() = when (this) {
        PhoebeRoute.SignIn -> "sign_in"
        PhoebeRoute.ServerPicker -> "server_picker"
        PhoebeRoute.LibraryPicker -> "library_picker"
        is PhoebeRoute.Browse -> when (section) {
            BrowseSection.Home -> "home"
            BrowseSection.Search -> "search"
            BrowseSection.Library -> "library"
            BrowseSection.Lyrics -> "lyrics"
            BrowseSection.Playlists -> "playlists"
            BrowseSection.Settings -> "settings"
        }
        is PhoebeRoute.Collections -> "collections"
        is PhoebeRoute.CollectionItems -> "collection_items"
        is PhoebeRoute.AlbumDetail -> "album_detail"
        is PhoebeRoute.ArtistDetail -> "artist_detail"
        is PhoebeRoute.SongDetail -> "song_detail"
        is PhoebeRoute.Lyrics -> "lyrics"
        is PhoebeRoute.RecentlyAdded -> "recently_added"
        is PhoebeRoute.PlayHistory -> "play_history"
        PhoebeRoute.FavoritePlaylists -> "favorite_playlists"
        PhoebeRoute.FavoriteArtists -> "favorite_artists"
        PhoebeRoute.FavoriteAlbums -> "favorite_albums"
        is PhoebeRoute.PlaylistDetail -> "playlist_detail"
        PhoebeRoute.Player -> "player"
    }

fun defaultPhoebeRoute(
    session: PlexSession?,
    mediaSources: MediaSourcesState,
): PhoebeRoute =
    when {
        session?.selectedLibrary != null -> PhoebeRoute.Browse()
        session?.selectedServer != null -> PhoebeRoute.LibraryPicker
        session?.token?.isNotBlank() == true -> PhoebeRoute.ServerPicker
        mediaSources.localFolders.any { it.enabled } -> PhoebeRoute.Browse()
        else -> PhoebeRoute.SignIn
    }
