package com.phoebe.app.ui

import com.phoebe.app.domain.AppScreen
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.CollectionFacet
import com.phoebe.app.domain.CollectionTarget
import com.phoebe.app.domain.PlayHistoryKind
import com.phoebe.app.domain.RecentlyAddedKind

fun phoebeWebRoutesForPath(path: String?): List<PhoebeRoute> {
    val segments = path
        .orEmpty()
        .substringBefore("?")
        .substringBefore("#")
        .trim('/')
        .takeUnless { it.isBlank() || it == "index.html" || it == "404.html" }
        ?.split("/")
        ?.filter { it.isNotBlank() }
        .orEmpty()

    if (segments.isEmpty()) return listOf(PhoebeRoute.Browse(BrowseSection.Home))

    return when (segments.first()) {
        "search" -> listOf(PhoebeRoute.Browse(BrowseSection.Search))
        "library" -> listOf(PhoebeRoute.Browse(BrowseSection.Library))
        "radio" -> parseRadioPath(segments)
        "lyrics" -> parseLyricsPath(segments)
        "playlists" -> parsePlaylistsPath(segments)
        "downloads" -> listOf(PhoebeRoute.Browse(BrowseSection.Downloads))
        "settings" -> listOf(PhoebeRoute.Browse(BrowseSection.Settings))
        "signin" -> listOf(PhoebeRoute.SignIn)
        "setup" -> parseSetupPath(segments)
        "player" -> listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.Player)
        "artist" -> parseArtistPath(segments)
        "album" -> detailRoute(segments) { PhoebeRoute.AlbumDetail(it) }
        "track" -> detailRoute(segments) { PhoebeRoute.SongDetail(it) }
        "favorites" -> parseFavoritesPath(segments)
        "recently-added" -> parseRecentlyAddedPath(segments)
        "history" -> parseHistoryPath(segments)
        "mix" -> parseMixPath(segments)
        "collections" -> parseCollectionsPath(segments)
        else -> listOf(PhoebeRoute.Browse(BrowseSection.Home))
    }
}

fun List<PhoebeRoute>.withUnavailableBrowseFallback(
    fallbackRoutes: List<PhoebeRoute>,
): List<PhoebeRoute> {
    val safeFallbackRoutes = fallbackRoutes.ifEmpty { listOf(PhoebeRoute.SignIn) }
    val needsBrowseSource = firstOrNull().requiresBrowseSource()
    val sourceAvailable = safeFallbackRoutes.firstOrNull().requiresBrowseSource()
    return if (needsBrowseSource && !sourceAvailable) {
        safeFallbackRoutes
    } else {
        this
    }
}

private fun PhoebeRoute?.requiresBrowseSource(): Boolean = when (this) {
    is PhoebeRoute.Browse -> section.requiresBrowseSource()
    is PhoebeRoute.AlbumDetail,
    is PhoebeRoute.ArtistAlbumSlugDetail,
    is PhoebeRoute.ArtistDetail,
    is PhoebeRoute.ArtistSlugDetail,
    is PhoebeRoute.CollectionItems,
    is PhoebeRoute.Collections,
    is PhoebeRoute.PlayHistory,
    PhoebeRoute.AlbumMixBuilder,
    PhoebeRoute.ArtistMixBuilder,
    is PhoebeRoute.PlaylistDetail,
    is PhoebeRoute.PlaylistSlugDetail,
    is PhoebeRoute.RecentlyAdded,
    is PhoebeRoute.SongDetail,
    PhoebeRoute.FavoriteAlbums,
    PhoebeRoute.FavoriteArtists,
    PhoebeRoute.FavoritePlaylists,
    -> true

    PhoebeRoute.LibraryPicker,
    is PhoebeRoute.Lyrics,
    PhoebeRoute.Player,
    is PhoebeRoute.RadioCountry,
    PhoebeRoute.RadioCountries,
    PhoebeRoute.RadioGlobe,
    PhoebeRoute.RadioMap,
    is PhoebeRoute.RadioStation,
    PhoebeRoute.ServerPicker,
    PhoebeRoute.SignIn,
    null,
    -> false
}

fun PhoebeRoute.toPhoebeWebPath(
    routeResolution: PhoebeRouteResolution? = null,
): String = when (this) {
    PhoebeRoute.SignIn -> "/signin"
    PhoebeRoute.ServerPicker -> "/setup/server"
    PhoebeRoute.LibraryPicker -> "/setup/library"
    is PhoebeRoute.Browse -> when (section) {
        BrowseSection.Home -> "/"
        BrowseSection.Search -> "/search"
        BrowseSection.Library -> "/library"
        BrowseSection.Radio -> "/radio"
        BrowseSection.Lyrics -> "/lyrics"
        BrowseSection.Playlists -> "/playlists"
        BrowseSection.Downloads -> "/downloads"
        BrowseSection.Settings -> "/settings"
    }
    PhoebeRoute.RadioCountries -> "/radio/countries"
    PhoebeRoute.RadioGlobe -> "/radio/map"
    PhoebeRoute.RadioMap -> "/radio/map"
    is PhoebeRoute.RadioCountry -> "/radio/${countryCode.trim().uppercase()}"
    is PhoebeRoute.RadioStation -> "/radio/${encodePhoebePathSegment(stationId)}"
    is PhoebeRoute.Collections -> "/collections/${entry.target.pathSegment()}/${entry.facet.pathSegment()}"
    is PhoebeRoute.CollectionItems -> "/collections/${entry.target.pathSegment()}/${entry.facet.pathSegment()}/${encodePhoebePathSegment(value)}"
    is PhoebeRoute.ArtistDetail -> routeResolution.resolvedScreen<AppScreen.ArtistDetail>()
        ?.let { "/artist/${phoebePathSlug(it.artist.title)}" }
        ?: detailPath(
            prefix = "artist",
            title = null,
            id = artistId,
        )
    is PhoebeRoute.ArtistSlugDetail -> "/artist/$artistSlug"
    is PhoebeRoute.AlbumDetail -> routeResolution.resolvedScreen<AppScreen.AlbumDetail>()
        ?.let { "/artist/${phoebePathSlug(it.album.artist)}/album/${phoebePathSlug(it.album.title)}" }
        ?: detailPath(
            prefix = "album",
            title = null,
            id = albumId,
        )
    is PhoebeRoute.ArtistAlbumSlugDetail -> "/artist/$artistSlug/album/$albumSlug"
    is PhoebeRoute.SongDetail -> detailPath(
        prefix = "track",
        title = routeResolution.resolvedScreen<AppScreen.SongDetail>()?.track?.title,
        id = trackId,
    )
    is PhoebeRoute.Lyrics -> trackId
        ?.let { id ->
            detailPath(
                prefix = "lyrics",
                title = routeResolution.resolvedScreen<AppScreen.Lyrics>()?.track?.title,
                id = id,
            )
        }
        ?: "/lyrics/current"
    is PhoebeRoute.RecentlyAdded -> "/recently-added/${kind.pathSegment()}"
    is PhoebeRoute.PlayHistory -> "/history/${kind.pathSegment()}"
    PhoebeRoute.ArtistMixBuilder -> "/mix/artists"
    PhoebeRoute.AlbumMixBuilder -> "/mix/albums"
    PhoebeRoute.FavoritePlaylists -> "/favorites/playlists"
    PhoebeRoute.FavoriteArtists -> "/favorites/artists"
    PhoebeRoute.FavoriteAlbums -> "/favorites/albums"
    is PhoebeRoute.PlaylistDetail -> routeResolution.resolvedScreen<AppScreen.PlaylistDetail>()
        ?.let { "/playlists/${phoebePathSlug(it.playlist.title)}" }
        ?: detailPath(
            prefix = "playlists",
            title = null,
            id = playlistId,
        )
    is PhoebeRoute.PlaylistSlugDetail -> "/playlists/$playlistSlug"
    PhoebeRoute.Player -> "/player"
}

private inline fun <reified T : AppScreen> PhoebeRouteResolution?.resolvedScreen(): T? =
    ((this as? PhoebeRouteResolution.Resolved)?.screen as? T)

private fun parseRadioPath(segments: List<String>): List<PhoebeRoute> {
    val value = segments.getOrNull(1)?.let(::decodePhoebePathSegment)
        ?: return listOf(PhoebeRoute.Browse(BrowseSection.Radio))
    val route = when {
        value == "countries" -> PhoebeRoute.RadioCountries
        value == "globe" -> PhoebeRoute.RadioMap
        value == "map" -> PhoebeRoute.RadioMap
        value.isCountryCodeSegment() -> PhoebeRoute.RadioCountry(value.uppercase())
        else -> PhoebeRoute.RadioStation(value)
    }
    return listOf(PhoebeRoute.Browse(BrowseSection.Radio), route)
}

private fun parseLyricsPath(segments: List<String>): List<PhoebeRoute> = when {
    segments.size == 1 -> listOf(PhoebeRoute.Browse(BrowseSection.Lyrics))
    segments.size == 2 && segments[1] == "current" -> listOf(
        PhoebeRoute.Browse(BrowseSection.Home),
        PhoebeRoute.Lyrics(),
    )
    segments.size == 2 -> listOf(
        PhoebeRoute.Browse(BrowseSection.Home),
        PhoebeRoute.Lyrics(decodePhoebePathSegment(segments[1])),
    )
    else -> listOf(
        PhoebeRoute.Browse(BrowseSection.Home),
        PhoebeRoute.Lyrics(decodePhoebePathSegment(segments[2])),
    )
}

private fun parsePlaylistsPath(segments: List<String>): List<PhoebeRoute> {
    if (segments.size == 2) {
        val value = decodePhoebePathSegment(segments[1])
        val route = if (value.looksLikeProviderId()) {
            PhoebeRoute.PlaylistDetail(value)
        } else {
            PhoebeRoute.PlaylistSlugDetail(normalizedSlugSegment(segments[1]))
        }
        return listOf(PhoebeRoute.Browse(BrowseSection.Playlists), route)
    }
    val playlistId = detailId(segments) ?: return listOf(PhoebeRoute.Browse(BrowseSection.Playlists))
    return listOf(
        PhoebeRoute.Browse(BrowseSection.Playlists),
        PhoebeRoute.PlaylistDetail(playlistId),
    )
}

private fun parseArtistPath(segments: List<String>): List<PhoebeRoute> = when {
    segments.size >= 4 && segments[2] == "album" -> listOf(
        PhoebeRoute.Browse(BrowseSection.Home),
        PhoebeRoute.ArtistAlbumSlugDetail(
            artistSlug = normalizedSlugSegment(segments[1]),
            albumSlug = normalizedSlugSegment(segments[3]),
        ),
    )
    segments.size >= 3 -> listOf(
        PhoebeRoute.Browse(BrowseSection.Home),
        PhoebeRoute.ArtistDetail(decodePhoebePathSegment(segments[2])),
    )
    segments.size == 2 -> listOf(
        PhoebeRoute.Browse(BrowseSection.Home),
        decodePhoebePathSegment(segments[1]).let { value ->
            if (value.looksLikeProviderId()) {
                PhoebeRoute.ArtistDetail(value)
            } else {
                PhoebeRoute.ArtistSlugDetail(normalizedSlugSegment(segments[1]))
            }
        },
    )
    else -> listOf(PhoebeRoute.Browse(BrowseSection.Home))
}

private fun parseSetupPath(segments: List<String>): List<PhoebeRoute> = when (segments.getOrNull(1)) {
    "server" -> listOf(PhoebeRoute.SignIn, PhoebeRoute.ServerPicker)
    "library" -> listOf(PhoebeRoute.SignIn, PhoebeRoute.ServerPicker, PhoebeRoute.LibraryPicker)
    else -> listOf(PhoebeRoute.SignIn)
}

private fun parseFavoritesPath(segments: List<String>): List<PhoebeRoute> = when (segments.getOrNull(1)) {
    "playlists" -> listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.FavoritePlaylists)
    "artists" -> listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.FavoriteArtists)
    "albums" -> listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.FavoriteAlbums)
    else -> listOf(PhoebeRoute.Browse(BrowseSection.Home))
}

private fun parseRecentlyAddedPath(segments: List<String>): List<PhoebeRoute> =
    parseRecentlyAddedKind(segments.getOrNull(1))
        ?.let { listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.RecentlyAdded(it)) }
        ?: listOf(PhoebeRoute.Browse(BrowseSection.Home))

private fun parseHistoryPath(segments: List<String>): List<PhoebeRoute> =
    parsePlayHistoryKind(segments.getOrNull(1))
        ?.let { listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.PlayHistory(it)) }
        ?: listOf(PhoebeRoute.Browse(BrowseSection.Home))

private fun parseMixPath(segments: List<String>): List<PhoebeRoute> = when (segments.getOrNull(1)) {
    "artists" -> listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.ArtistMixBuilder)
    "albums" -> listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.AlbumMixBuilder)
    else -> listOf(PhoebeRoute.Browse(BrowseSection.Home))
}

private fun parseCollectionsPath(segments: List<String>): List<PhoebeRoute> {
    val target = parseCollectionTarget(segments.getOrNull(1))
    val facet = parseCollectionFacet(segments.getOrNull(2))
    if (target == null || facet == null) return listOf(PhoebeRoute.Browse(BrowseSection.Home))
    val entry = CollectionEntry(target, facet)
    val value = segments.getOrNull(3)?.let(::decodePhoebePathSegment)
    return if (value == null) {
        listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.Collections(entry))
    } else {
        listOf(PhoebeRoute.Browse(BrowseSection.Home), PhoebeRoute.CollectionItems(entry, value))
    }
}

private inline fun detailRoute(
    segments: List<String>,
    route: (String) -> PhoebeRoute,
): List<PhoebeRoute> {
    val id = detailId(segments) ?: return listOf(PhoebeRoute.Browse(BrowseSection.Home))
    return listOf(PhoebeRoute.Browse(BrowseSection.Home), route(id))
}

private fun detailId(segments: List<String>): String? = when {
    segments.size >= 3 -> decodePhoebePathSegment(segments[2])
    segments.size >= 2 -> decodePhoebePathSegment(segments[1])
    else -> null
}

private fun detailPath(prefix: String, title: String?, id: String): String {
    val slug = phoebePathSlug(title?.takeIf { it.isNotBlank() } ?: id)
    return "/$prefix/$slug/${encodePhoebePathSegment(id)}"
}

private fun normalizedSlugSegment(value: String): String =
    phoebePathSlug(decodePhoebePathSegment(value))

private fun String.looksLikeProviderId(): Boolean =
    ':' in this || '/' in this

private fun String.isCountryCodeSegment(): Boolean =
    length == 2 && all { it in 'a'..'z' || it in 'A'..'Z' }

fun phoebePathSlug(value: String): String {
    val slug = buildString {
        var pendingDash = false
        value.trim().forEach { char ->
            val lower = when (char) {
                in 'A'..'Z' -> char.lowercaseChar()
                else -> char
            }
            when (lower) {
                '\'',
                '\u2018',
                '\u2019',
                -> Unit
                in 'a'..'z',
                in '0'..'9',
                -> {
                    if (pendingDash && isNotEmpty()) append('-')
                    append(lower)
                    pendingDash = false
                }
                else -> pendingDash = isNotEmpty()
            }
        }
    }
    return slug.ifBlank { "item" }
}

fun encodePhoebePathSegment(value: String): String = buildString {
    val hex = "0123456789ABCDEF"
    value.encodeToByteArray().forEach { byte ->
        val intValue = byte.toInt() and 0xFF
        val char = intValue.toChar()
        if (
            char in 'A'..'Z' ||
            char in 'a'..'z' ||
            char in '0'..'9' ||
            char == '-' ||
            char == '.' ||
            char == '_' ||
            char == '~'
        ) {
            append(char)
        } else {
            append('%')
            append(hex[intValue shr 4])
            append(hex[intValue and 0x0F])
        }
    }
}

fun decodePhoebePathSegment(value: String): String {
    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val high = value[index + 1].hexValue()
            val low = value[index + 2].hexValue()
            if (high >= 0 && low >= 0) {
                bytes.add(((high shl 4) or low).toByte())
                index += 3
                continue
            }
        }
        char.toString().encodeToByteArray().forEach(bytes::add)
        index += 1
    }
    return ByteArray(bytes.size) { bytes[it] }.decodeToString()
}

private fun Char.hexValue(): Int = when (this) {
    in '0'..'9' -> this - '0'
    in 'a'..'f' -> this - 'a' + 10
    in 'A'..'F' -> this - 'A' + 10
    else -> -1
}

private fun CollectionTarget.pathSegment(): String = when (this) {
    CollectionTarget.Artists -> "artists"
    CollectionTarget.Albums -> "albums"
}

private fun parseCollectionTarget(value: String?): CollectionTarget? = when (value) {
    "artists" -> CollectionTarget.Artists
    "albums" -> CollectionTarget.Albums
    else -> null
}

private fun CollectionFacet.pathSegment(): String = when (this) {
    CollectionFacet.Genre -> "genre"
    CollectionFacet.Mood -> "mood"
    CollectionFacet.Style -> "style"
}

private fun parseCollectionFacet(value: String?): CollectionFacet? = when (value) {
    "genre" -> CollectionFacet.Genre
    "mood" -> CollectionFacet.Mood
    "style" -> CollectionFacet.Style
    else -> null
}

private fun RecentlyAddedKind.pathSegment(): String = when (this) {
    RecentlyAddedKind.Songs -> "songs"
    RecentlyAddedKind.Artists -> "artists"
    RecentlyAddedKind.Albums -> "albums"
}

private fun parseRecentlyAddedKind(value: String?): RecentlyAddedKind? = when (value) {
    "songs" -> RecentlyAddedKind.Songs
    "artists" -> RecentlyAddedKind.Artists
    "albums" -> RecentlyAddedKind.Albums
    else -> null
}

private fun PlayHistoryKind.pathSegment(): String = when (this) {
    PlayHistoryKind.RecentlyPlayed -> "recently-played"
    PlayHistoryKind.MostPlayed -> "most-played"
}

private fun parsePlayHistoryKind(value: String?): PlayHistoryKind? = when (value) {
    "recently-played" -> PlayHistoryKind.RecentlyPlayed
    "most-played" -> PlayHistoryKind.MostPlayed
    else -> null
}
