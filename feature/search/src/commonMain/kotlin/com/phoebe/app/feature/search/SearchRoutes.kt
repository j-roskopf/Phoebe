package com.phoebe.app.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.Track

@Immutable
data class SearchDesktopRouteState(
    val catalog: CatalogSnapshot,
    val catalogRefreshing: Boolean,
    val query: String,
)

data class SearchDesktopRouteActions(
    val onQuery: (String) -> Unit,
    val onArtist: (Artist) -> Unit,
    val onAlbum: (Album) -> Unit,
    val onPlayTracks: (List<Track>, Int) -> Unit,
    val onAddToUpNext: (Track) -> Unit,
    val onDownload: (Track) -> Unit,
)

@Composable
fun SearchDesktopRoute(
    viewModel: SearchViewModel,
    catalog: CatalogSnapshot,
    catalogRefreshing: Boolean,
    searchQuery: String = "",
    actions: SearchDesktopRouteActions,
    modifier: Modifier = Modifier,
    loadingContent: @Composable () -> Unit = {},
    trackMenuContent: @Composable (
        track: Track,
        expanded: Boolean,
        onDismiss: () -> Unit,
        onAddToUpNext: () -> Unit,
        onDownload: () -> Unit,
    ) -> Unit = { track, expanded, onDismiss, onAddToUpNext, onDownload ->
        DefaultSearchTrackMenuContent(track, expanded, onDismiss, onAddToUpNext, onDownload)
    },
) {
    LaunchedEffect(catalog, catalogRefreshing) {
        viewModel.updateCatalog(catalog, catalogRefreshing)
    }
    SearchDesktopRoute(
        state = SearchDesktopRouteState(
            catalog = catalog,
            catalogRefreshing = catalogRefreshing,
            query = searchQuery,
        ),
        actions = actions,
        modifier = modifier,
        loadingContent = loadingContent,
        trackMenuContent = trackMenuContent,
    )
}

@Composable
fun SearchDesktopRoute(
    state: SearchDesktopRouteState,
    actions: SearchDesktopRouteActions,
    modifier: Modifier = Modifier,
    loadingContent: @Composable () -> Unit = {},
    trackMenuContent: @Composable (
        track: Track,
        expanded: Boolean,
        onDismiss: () -> Unit,
        onAddToUpNext: () -> Unit,
        onDownload: () -> Unit,
    ) -> Unit = { track, expanded, onDismiss, onAddToUpNext, onDownload ->
        DefaultSearchTrackMenuContent(track, expanded, onDismiss, onAddToUpNext, onDownload)
    },
) {
    SearchDesktopView(
        catalog = state.catalog,
        catalogRefreshing = state.catalogRefreshing,
        searchQuery = state.query,
        modifier = modifier,
        onSearchQuery = actions.onQuery,
        onArtist = actions.onArtist,
        onAlbum = actions.onAlbum,
        onPlayTracks = actions.onPlayTracks,
        onAddToUpNext = actions.onAddToUpNext,
        onDownload = actions.onDownload,
        loadingContent = loadingContent,
        trackMenuContent = trackMenuContent,
    )
}

@Composable
fun SearchMobileRoute(
    viewModel: SearchViewModel,
    catalog: CatalogSnapshot,
    catalogRefreshing: Boolean,
    searchQuery: String = "",
    actions: SearchDesktopRouteActions,
    modifier: Modifier = Modifier,
    loadingContent: @Composable () -> Unit = {},
    trackMenuContent: @Composable (
        track: Track,
        expanded: Boolean,
        onDismiss: () -> Unit,
        onAddToUpNext: () -> Unit,
        onDownload: () -> Unit,
    ) -> Unit = { track, expanded, onDismiss, onAddToUpNext, onDownload ->
        DefaultSearchTrackMenuContent(track, expanded, onDismiss, onAddToUpNext, onDownload)
    },
    topBar: (@Composable () -> Unit)? = null,
) {
    LaunchedEffect(catalog, catalogRefreshing) {
        viewModel.updateCatalog(catalog, catalogRefreshing)
    }
    SearchMobileRoute(
        state = SearchDesktopRouteState(
            catalog = catalog,
            catalogRefreshing = catalogRefreshing,
            query = searchQuery,
        ),
        actions = actions,
        modifier = modifier,
        loadingContent = loadingContent,
        trackMenuContent = trackMenuContent,
        topBar = topBar,
    )
}

@Composable
fun SearchMobileRoute(
    state: SearchDesktopRouteState,
    actions: SearchDesktopRouteActions,
    modifier: Modifier = Modifier,
    loadingContent: @Composable () -> Unit = {},
    trackMenuContent: @Composable (
        track: Track,
        expanded: Boolean,
        onDismiss: () -> Unit,
        onAddToUpNext: () -> Unit,
        onDownload: () -> Unit,
    ) -> Unit = { track, expanded, onDismiss, onAddToUpNext, onDownload ->
        DefaultSearchTrackMenuContent(track, expanded, onDismiss, onAddToUpNext, onDownload)
    },
    topBar: (@Composable () -> Unit)? = null,
) {
    SearchMobileView(
        catalog = state.catalog,
        catalogRefreshing = state.catalogRefreshing,
        searchQuery = state.query,
        modifier = modifier,
        onSearchQuery = actions.onQuery,
        onArtist = actions.onArtist,
        onAlbum = actions.onAlbum,
        onPlayTracks = actions.onPlayTracks,
        onAddToUpNext = actions.onAddToUpNext,
        onDownload = actions.onDownload,
        loadingContent = loadingContent,
        trackMenuContent = trackMenuContent,
        topBar = topBar,
    )
}
