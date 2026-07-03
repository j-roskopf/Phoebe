package com.phoebe.app.ui

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.AppScreen
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.PlayHistoryKind
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.RecentlyAddedKind
import com.phoebe.app.domain.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Serializable
enum class BrowseSection {
    Home,
    Search,
    Library,
    Radio,
    Lyrics,
    Playlists,
    Downloads,
    Settings,
}

fun BrowseSection.isMainBrowseSection(): Boolean = when (this) {
    BrowseSection.Home,
    BrowseSection.Search,
    BrowseSection.Library,
    -> true

    BrowseSection.Radio,
    BrowseSection.Lyrics,
    BrowseSection.Playlists,
    BrowseSection.Downloads,
    BrowseSection.Settings,
    -> false
}

fun BrowseSection.requiresBrowseSource(): Boolean = when (this) {
    BrowseSection.Home,
    BrowseSection.Search,
    BrowseSection.Library,
    BrowseSection.Playlists,
    -> true

    BrowseSection.Radio,
    BrowseSection.Lyrics,
    BrowseSection.Downloads,
    BrowseSection.Settings,
    -> false
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
    data class Browse(val section: BrowseSection = BrowseSection.Home) : PhoebeRoute

    @Serializable
    data object RadioCountries : PhoebeRoute

    @Serializable
    data object RadioGlobe : PhoebeRoute

    @Serializable
    data object RadioMap : PhoebeRoute

    @Serializable
    data class RadioCountry(val countryCode: String) : PhoebeRoute

    @Serializable
    data class RadioStation(val stationId: String) : PhoebeRoute

    @Serializable
    data class Collections(
        val entry: CollectionEntry,
    ) : PhoebeRoute

    @Serializable
    data class CollectionItems(
        val entry: CollectionEntry,
        val value: String,
    ) : PhoebeRoute

    @Serializable
    data class ArtistDetail(val artistId: String) : PhoebeRoute

    @Serializable
    data class ArtistSlugDetail(val artistSlug: String) : PhoebeRoute

    @Serializable
    data class AlbumDetail(val albumId: String) : PhoebeRoute

    @Serializable
    data class ArtistAlbumSlugDetail(
        val artistSlug: String,
        val albumSlug: String,
    ) : PhoebeRoute

    @Serializable
    data class SongDetail(val trackId: String) : PhoebeRoute

    @Serializable
    data class Lyrics(val trackId: String? = null) : PhoebeRoute

    @Serializable
    data class RecentlyAdded(val kind: RecentlyAddedKind) : PhoebeRoute

    @Serializable
    data class PlayHistory(val kind: PlayHistoryKind) : PhoebeRoute

    @Serializable
    data object ArtistMixBuilder : PhoebeRoute

    @Serializable
    data object AlbumMixBuilder : PhoebeRoute

    @Serializable
    data object FavoritePlaylists : PhoebeRoute

    @Serializable
    data object FavoriteArtists : PhoebeRoute

    @Serializable
    data object FavoriteAlbums : PhoebeRoute

    @Serializable
    data class PlaylistDetail(val playlistId: String) : PhoebeRoute

    @Serializable
    data class PlaylistSlugDetail(val playlistSlug: String) : PhoebeRoute

    @Serializable
    data object Player : PhoebeRoute
}

val phoebeRouteSerializersModule = SerializersModule {
    polymorphic(NavKey::class) {
        subclass(PhoebeRoute.SignIn::class, PhoebeRoute.SignIn.serializer())
        subclass(PhoebeRoute.ServerPicker::class, PhoebeRoute.ServerPicker.serializer())
        subclass(PhoebeRoute.LibraryPicker::class, PhoebeRoute.LibraryPicker.serializer())
        subclass(PhoebeRoute.Browse::class, PhoebeRoute.Browse.serializer())
        subclass(PhoebeRoute.RadioCountries::class, PhoebeRoute.RadioCountries.serializer())
        subclass(PhoebeRoute.RadioGlobe::class, PhoebeRoute.RadioGlobe.serializer())
        subclass(PhoebeRoute.RadioMap::class, PhoebeRoute.RadioMap.serializer())
        subclass(PhoebeRoute.RadioCountry::class, PhoebeRoute.RadioCountry.serializer())
        subclass(PhoebeRoute.RadioStation::class, PhoebeRoute.RadioStation.serializer())
        subclass(PhoebeRoute.Collections::class, PhoebeRoute.Collections.serializer())
        subclass(PhoebeRoute.CollectionItems::class, PhoebeRoute.CollectionItems.serializer())
        subclass(PhoebeRoute.ArtistDetail::class, PhoebeRoute.ArtistDetail.serializer())
        subclass(PhoebeRoute.ArtistSlugDetail::class, PhoebeRoute.ArtistSlugDetail.serializer())
        subclass(PhoebeRoute.AlbumDetail::class, PhoebeRoute.AlbumDetail.serializer())
        subclass(PhoebeRoute.ArtistAlbumSlugDetail::class, PhoebeRoute.ArtistAlbumSlugDetail.serializer())
        subclass(PhoebeRoute.SongDetail::class, PhoebeRoute.SongDetail.serializer())
        subclass(PhoebeRoute.Lyrics::class, PhoebeRoute.Lyrics.serializer())
        subclass(PhoebeRoute.RecentlyAdded::class, PhoebeRoute.RecentlyAdded.serializer())
        subclass(PhoebeRoute.PlayHistory::class, PhoebeRoute.PlayHistory.serializer())
        subclass(PhoebeRoute.ArtistMixBuilder::class, PhoebeRoute.ArtistMixBuilder.serializer())
        subclass(PhoebeRoute.AlbumMixBuilder::class, PhoebeRoute.AlbumMixBuilder.serializer())
        subclass(PhoebeRoute.FavoritePlaylists::class, PhoebeRoute.FavoritePlaylists.serializer())
        subclass(PhoebeRoute.FavoriteArtists::class, PhoebeRoute.FavoriteArtists.serializer())
        subclass(PhoebeRoute.FavoriteAlbums::class, PhoebeRoute.FavoriteAlbums.serializer())
        subclass(PhoebeRoute.PlaylistDetail::class, PhoebeRoute.PlaylistDetail.serializer())
        subclass(PhoebeRoute.PlaylistSlugDetail::class, PhoebeRoute.PlaylistSlugDetail.serializer())
        subclass(PhoebeRoute.Player::class, PhoebeRoute.Player.serializer())
    }
}

private val phoebeRouteJson = Json {
    serializersModule = phoebeRouteSerializersModule
    classDiscriminator = "type"
}

private val phoebeRouteListSerializer = ListSerializer(PhoebeRoute.serializer())

fun encodePhoebeRouteBackStack(routes: List<PhoebeRoute>): String =
    phoebeRouteJson.encodeToString(phoebeRouteListSerializer, routes)

fun decodePhoebeRouteBackStack(routesJson: String): NavBackStack<PhoebeRoute> {
    val routes = phoebeRouteJson
        .decodeFromString(phoebeRouteListSerializer, routesJson)
        .ifEmpty { listOf(PhoebeRoute.SignIn) }
    return NavBackStack<PhoebeRoute>(routes.first()).apply {
        addAll(routes.drop(1))
    }
}

sealed interface PhoebeRouteResolution {
    val route: PhoebeRoute

    data class Resolved(
        override val route: PhoebeRoute,
        val screen: AppScreen,
    ) : PhoebeRouteResolution

    data class Missing(
        override val route: PhoebeRoute,
        val title: String,
        val message: String,
    ) : PhoebeRouteResolution
}

fun resolvePhoebeRoute(
    route: PhoebeRoute,
    catalog: CatalogSnapshot,
    currentTrack: Track?,
    showArtistAlbumMixBuilders: Boolean = true,
): PhoebeRouteResolution = when (route) {
    PhoebeRoute.SignIn -> route.resolved(AppScreen.SignIn)
    PhoebeRoute.ServerPicker -> route.resolved(AppScreen.ServerPicker)
    PhoebeRoute.LibraryPicker -> route.resolved(AppScreen.LibraryPicker)
    is PhoebeRoute.Browse -> route.resolved(AppScreen.Home)
    PhoebeRoute.RadioCountries -> route.resolved(AppScreen.Home)
    PhoebeRoute.RadioGlobe -> route.resolved(AppScreen.Home)
    PhoebeRoute.RadioMap -> route.resolved(AppScreen.Home)
    is PhoebeRoute.RadioCountry -> route.resolved(AppScreen.Home)
    is PhoebeRoute.RadioStation -> route.resolved(AppScreen.Home)
    is PhoebeRoute.Collections -> route.resolved(AppScreen.Collections(route.entry))
    is PhoebeRoute.CollectionItems -> route.resolved(AppScreen.CollectionItems(route.entry, route.value))
    is PhoebeRoute.ArtistDetail -> catalog.findArtist(route.artistId)
        ?.let { route.resolved(AppScreen.ArtistDetail(it)) }
        ?: route.missing("Artist not found", "This artist is no longer available in the current library.")
    is PhoebeRoute.ArtistSlugDetail -> when (val match = catalog.findArtistBySlug(route.artistSlug)) {
        is SlugMatch.Found -> route.resolved(AppScreen.ArtistDetail(match.value))
        SlugMatch.Ambiguous -> route.missing("Artist name is ambiguous", "More than one artist matches this URL.")
        SlugMatch.Missing -> route.missing("Artist not found", "No artist in the current library matches this URL.")
    }
    is PhoebeRoute.AlbumDetail -> catalog.findAlbum(route.albumId)
        ?.let { route.resolved(AppScreen.AlbumDetail(it)) }
        ?: route.missing("Album not found", "This album is no longer available in the current library.")
    is PhoebeRoute.ArtistAlbumSlugDetail -> when (val match = catalog.findAlbumByArtistAndAlbumSlug(route.artistSlug, route.albumSlug)) {
        is SlugMatch.Found -> route.resolved(AppScreen.AlbumDetail(match.value))
        SlugMatch.Ambiguous -> route.missing("Album name is ambiguous", "More than one album matches this URL.")
        SlugMatch.Missing -> route.missing("Album not found", "No album in the current library matches this URL.")
    }
    is PhoebeRoute.SongDetail -> catalog.findTrack(route.trackId, currentTrack)
        ?.let { route.resolved(AppScreen.SongDetail(it)) }
        ?: route.missing("Song not found", "This song is no longer available in the current library.")
    is PhoebeRoute.Lyrics -> {
        val track = route.trackId?.let { catalog.findTrack(it, currentTrack) } ?: currentTrack
        if (route.trackId != null && track == null) {
            route.missing("Lyrics unavailable", "The selected song is no longer available in the current library.")
        } else {
            route.resolved(AppScreen.Lyrics(track))
        }
    }
    is PhoebeRoute.RecentlyAdded -> route.resolved(AppScreen.RecentlyAdded(route.kind))
    is PhoebeRoute.PlayHistory -> route.resolved(AppScreen.PlayHistory(route.kind))
    PhoebeRoute.ArtistMixBuilder -> if (showArtistAlbumMixBuilders) {
        route.resolved(AppScreen.ArtistMixBuilder)
    } else {
        route.missing("Mix unavailable", "Artist mixes require a music library or enabled local folder.")
    }
    PhoebeRoute.AlbumMixBuilder -> if (showArtistAlbumMixBuilders) {
        route.resolved(AppScreen.AlbumMixBuilder)
    } else {
        route.missing("Mix unavailable", "Album mixes require a music library or enabled local folder.")
    }
    PhoebeRoute.FavoritePlaylists -> route.resolved(AppScreen.FavoritePlaylists)
    PhoebeRoute.FavoriteArtists -> route.resolved(AppScreen.FavoriteArtists)
    PhoebeRoute.FavoriteAlbums -> route.resolved(AppScreen.FavoriteAlbums)
    is PhoebeRoute.PlaylistDetail -> catalog.findPlaylist(route.playlistId)
        ?.let { route.resolved(AppScreen.PlaylistDetail(it)) }
        ?: route.missing("Playlist not found", "This playlist is no longer available in the current library.")
    is PhoebeRoute.PlaylistSlugDetail -> when (val match = catalog.findPlaylistBySlug(route.playlistSlug)) {
        is SlugMatch.Found -> route.resolved(AppScreen.PlaylistDetail(match.value))
        SlugMatch.Ambiguous -> route.missing("Playlist name is ambiguous", "More than one playlist matches this URL.")
        SlugMatch.Missing -> route.missing("Playlist not found", "No playlist in the current library matches this URL.")
    }
    PhoebeRoute.Player -> route.resolved(AppScreen.Player)
}

fun Artist.route(): PhoebeRoute = PhoebeRoute.ArtistDetail(id)
fun Album.route(): PhoebeRoute = PhoebeRoute.AlbumDetail(id)
fun Track.route(): PhoebeRoute = PhoebeRoute.SongDetail(id)
fun Playlist.route(): PhoebeRoute = PhoebeRoute.PlaylistDetail(id)

fun List<PhoebeRoute>.renderablePhoebeRoutes(): List<PhoebeRoute> =
    filterNot { it is PhoebeRoute.RadioCountries || it is PhoebeRoute.RadioGlobe || it is PhoebeRoute.RadioMap || it is PhoebeRoute.RadioCountry || it is PhoebeRoute.RadioStation }
        .ifEmpty { listOf(PhoebeRoute.SignIn) }

val PhoebeRoute.telemetryName: String
    get() = when (this) {
        PhoebeRoute.SignIn -> "sign_in"
        PhoebeRoute.ServerPicker -> "server_picker"
        PhoebeRoute.LibraryPicker -> "library_picker"
        is PhoebeRoute.Browse -> when (section) {
            BrowseSection.Home -> "home"
            BrowseSection.Search -> "search"
            BrowseSection.Library -> "library"
            BrowseSection.Radio -> "radio"
            BrowseSection.Lyrics -> "lyrics"
            BrowseSection.Playlists -> "playlists"
            BrowseSection.Downloads -> "downloads"
            BrowseSection.Settings -> "settings"
        }
        PhoebeRoute.RadioCountries -> "radio_countries"
        PhoebeRoute.RadioGlobe -> "radio_globe"
        PhoebeRoute.RadioMap -> "radio_map"
        is PhoebeRoute.RadioCountry -> "radio_country"
        is PhoebeRoute.RadioStation -> "radio_station"
        is PhoebeRoute.Collections -> "collections"
        is PhoebeRoute.CollectionItems -> "collection_items"
        is PhoebeRoute.AlbumDetail -> "album_detail"
        is PhoebeRoute.ArtistAlbumSlugDetail -> "album_detail"
        is PhoebeRoute.ArtistDetail -> "artist_detail"
        is PhoebeRoute.ArtistSlugDetail -> "artist_detail"
        is PhoebeRoute.SongDetail -> "song_detail"
        is PhoebeRoute.Lyrics -> "lyrics"
        is PhoebeRoute.RecentlyAdded -> "recently_added"
        is PhoebeRoute.PlayHistory -> "play_history"
        PhoebeRoute.ArtistMixBuilder -> "artist_mix_builder"
        PhoebeRoute.AlbumMixBuilder -> "album_mix_builder"
        PhoebeRoute.FavoritePlaylists -> "favorite_playlists"
        PhoebeRoute.FavoriteArtists -> "favorite_artists"
        PhoebeRoute.FavoriteAlbums -> "favorite_albums"
        is PhoebeRoute.PlaylistDetail -> "playlist_detail"
        is PhoebeRoute.PlaylistSlugDetail -> "playlist_detail"
        PhoebeRoute.Player -> "player"
    }

private fun PhoebeRoute.resolved(screen: AppScreen) = PhoebeRouteResolution.Resolved(this, screen)

private fun PhoebeRoute.missing(title: String, message: String) =
    PhoebeRouteResolution.Missing(this, title, message)

private fun CatalogSnapshot.findArtist(id: String): Artist? = artists.firstOrNull { it.id == id }

private fun CatalogSnapshot.findAlbum(id: String): Album? = albums.firstOrNull { it.id == id }

private fun CatalogSnapshot.findPlaylist(id: String): Playlist? = playlists.firstOrNull { it.id == id }

private fun CatalogSnapshot.findTrack(id: String, currentTrack: Track?): Track? =
    currentTrack?.takeIf { it.id == id }
        ?: tracksByParent.values.asSequence().flatten().firstOrNull { it.id == id }

private fun CatalogSnapshot.findArtistBySlug(slug: String): SlugMatch<Artist> =
    artists.matchSingleBySlug(slug) { title }

private fun CatalogSnapshot.findAlbumByArtistAndAlbumSlug(
    artistSlug: String,
    albumSlug: String,
): SlugMatch<Album> =
    albums
        .filter { phoebePathSlug(it.artist) == artistSlug }
        .matchSingleBySlug(albumSlug) { title }

private fun CatalogSnapshot.findPlaylistBySlug(slug: String): SlugMatch<Playlist> =
    playlists.matchSingleBySlug(slug) { title }

private sealed interface SlugMatch<out T> {
    data class Found<T>(val value: T) : SlugMatch<T>
    data object Missing : SlugMatch<Nothing>
    data object Ambiguous : SlugMatch<Nothing>
}

private inline fun <T> Iterable<T>.matchSingleBySlug(
    slug: String,
    label: T.() -> String,
): SlugMatch<T> {
    val matches = filter { phoebePathSlug(it.label()) == slug }
    return when (matches.size) {
        0 -> SlugMatch.Missing
        1 -> SlugMatch.Found(matches.single())
        else -> SlugMatch.Ambiguous
    }
}
