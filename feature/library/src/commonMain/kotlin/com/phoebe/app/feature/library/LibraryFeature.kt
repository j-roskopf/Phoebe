package com.phoebe.app.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.JellyfinLibraryPageKind
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.Track

@Immutable
data class LibraryRouteState(
    val catalog: CatalogSnapshot,
    val catalogRefreshing: Boolean,
    val filter: LibraryFilterTab,
    val libraryUi: LibraryUiPreferences,
    val jellyfinPagination: Boolean = false,
    val searchQuery: String = "",
)

@Immutable
class LibraryRouteActions(
    val onFilter: (LibraryFilterTab) -> Unit,
    val onLibrarySortBy: (LibrarySortBy) -> Unit,
    val onLibraryAscending: (Boolean) -> Unit,
    val onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    val onArtist: (Artist) -> Unit,
    val onAlbum: (Album) -> Unit,
    val onPlayTracks: (List<Track>, Int) -> Unit,
    val onAddToUpNext: (Track) -> Unit,
    val onDownload: (Track) -> Unit,
    val onSearchQuery: (String) -> Unit = {},
    val onJellyfinPage: (JellyfinLibraryPageKind, Int) -> Unit = { _, _ -> },
)

@Immutable
data class PlaylistsRouteState(
    val catalog: CatalogSnapshot = CatalogSnapshot(),
    val catalogRefreshing: Boolean,
    val searchQuery: String,
)

@Immutable
class PlaylistsRouteActions(
    val onSearchQuery: (String) -> Unit,
    val onPlaylist: (Playlist) -> Unit,
)

@Immutable
data class PlaylistDetailDesktopRouteState(
    val playlist: Playlist,
    val tracks: List<Track>,
    val catalogRefreshing: Boolean,
    val searchQuery: String,
    val libraryUi: LibraryUiPreferences,
)

@Immutable
class PlaylistDetailDesktopRouteActions(
    val onSearchQuery: (String) -> Unit,
    val onPlayTracks: (List<Track>, Int) -> Unit,
    val onAddToUpNext: (Track) -> Unit,
    val onDownload: (Track) -> Unit,
    val onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    val onDownloadPlaylist: (Playlist) -> Unit = {},
)

@Composable
fun LibraryDesktopRoute(
    state: LibraryRouteState,
    actions: LibraryRouteActions,
    modifier: Modifier = Modifier,
) {
    LibraryDesktopView(
        catalog = state.catalog,
        catalogRefreshing = state.catalogRefreshing,
        filter = state.filter,
        libraryUi = state.libraryUi,
        jellyfinPagination = state.jellyfinPagination,
        onJellyfinPage = actions.onJellyfinPage,
        onFilter = actions.onFilter,
        onLibrarySortBy = actions.onLibrarySortBy,
        onLibraryAscending = actions.onLibraryAscending,
        onLibraryColumns = actions.onLibraryColumns,
        onArtist = actions.onArtist,
        onAlbum = actions.onAlbum,
        onPlayTracks = actions.onPlayTracks,
        searchQuery = state.searchQuery,
        onSearchQuery = actions.onSearchQuery,
        onAddToUpNext = actions.onAddToUpNext,
        onDownload = actions.onDownload,
        modifier = modifier,
    )
}

@Composable
fun LibraryMobileRoute(
    state: LibraryRouteState,
    actions: LibraryRouteActions,
    modifier: Modifier = Modifier,
    libraryViewMode: LibraryViewMode = LibraryViewMode.Grid,
    topBar: (@Composable () -> Unit)? = null,
) {
    LibraryMobileView(
        catalog = state.catalog,
        catalogRefreshing = state.catalogRefreshing,
        filter = state.filter,
        libraryUi = state.libraryUi,
        jellyfinPagination = state.jellyfinPagination,
        searchQuery = state.searchQuery,
        onSearchQuery = actions.onSearchQuery,
        libraryViewMode = libraryViewMode,
        onJellyfinPage = actions.onJellyfinPage,
        onFilter = actions.onFilter,
        onLibrarySortBy = actions.onLibrarySortBy,
        onLibraryAscending = actions.onLibraryAscending,
        onLibraryColumns = actions.onLibraryColumns,
        onArtist = actions.onArtist,
        onAlbum = actions.onAlbum,
        onPlayTracks = actions.onPlayTracks,
        onAddToUpNext = actions.onAddToUpNext,
        onDownload = actions.onDownload,
        modifier = modifier,
        topBar = topBar,
    )
}

@Composable
fun PlaylistDetailDesktopRoute(
    state: PlaylistDetailDesktopRouteState,
    actions: PlaylistDetailDesktopRouteActions,
    modifier: Modifier = Modifier,
    edgePadding: Dp = 36.dp,
    headlineFontSize: TextUnit = 30.sp,
    headlineLineHeight: TextUnit = 35.sp,
    searchPillModifier: Modifier = Modifier.width(270.dp),
) {
    PlaylistDetailDesktopView(
        playlist = state.playlist,
        tracks = state.tracks,
        catalogRefreshing = state.catalogRefreshing,
        searchQuery = state.searchQuery,
        libraryUi = state.libraryUi,
        onSearchQuery = actions.onSearchQuery,
        onPlayTracks = actions.onPlayTracks,
        onAddToUpNext = actions.onAddToUpNext,
        onDownload = actions.onDownload,
        onLibraryColumns = actions.onLibraryColumns,
        modifier = modifier,
        edgePadding = edgePadding,
        headlineFontSize = headlineFontSize,
        headlineLineHeight = headlineLineHeight,
        searchPillModifier = searchPillModifier,
        onDownloadPlaylist = actions.onDownloadPlaylist,
    )
}

@Composable
fun PlaylistsDesktopRoute(
    state: PlaylistsRouteState,
    actions: PlaylistsRouteActions,
    modifier: Modifier = Modifier,
    edgePadding: Dp = 36.dp,
    headlineFontSize: TextUnit = 30.sp,
    headlineLineHeight: TextUnit = 35.sp,
    searchPillModifier: Modifier = Modifier.width(270.dp),
) {
    PlaylistsDesktopView(
        catalog = state.catalog,
        searchQuery = state.searchQuery,
        onSearchQuery = actions.onSearchQuery,
        onPlaylist = actions.onPlaylist,
        modifier = modifier,
        edgePadding = edgePadding,
        headlineFontSize = headlineFontSize,
        headlineLineHeight = headlineLineHeight,
        searchPillModifier = searchPillModifier,
    )
}

@Composable
fun PlaylistsMobileRoute(
    state: PlaylistsRouteState,
    actions: PlaylistsRouteActions,
    modifier: Modifier = Modifier,
    topBar: (@Composable () -> Unit)? = null,
) {
    PlaylistsMobileView(
        catalog = state.catalog,
        catalogRefreshing = state.catalogRefreshing,
        searchQuery = state.searchQuery,
        onSearchQuery = actions.onSearchQuery,
        onPlaylist = actions.onPlaylist,
        modifier = modifier,
        topBar = topBar,
    )
}
