package com.phoebe.app.feature.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.HomeSection
import com.phoebe.app.domain.PlexRadioStation
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.RecentlyAddedKind
import com.phoebe.app.domain.Track

@Immutable
data class DesktopHomeRouteState(
    val home: HomeUiState,
    val catalogRefreshing: Boolean,
    val homeSections: List<HomeSection>,
    val supportedCollectionEntries: Set<CollectionEntry>,
    val useBarePanels: Boolean = false,
    val decadeMixNotice: String? = null,
    val radioStations: List<PlexRadioStation> = emptyList(),
    val radioStartingIds: Set<String> = emptySet(),
)

data class DesktopHomeRouteActions(
    val onTrack: (Track) -> Unit,
    val onArtist: (Artist) -> Unit,
    val onAlbum: (Album) -> Unit,
    val onPlaylist: (Playlist) -> Unit,
    val onRecentSongs: () -> Unit,
    val onRecentArtists: () -> Unit,
    val onRecentAlbums: () -> Unit,
    val onFavoritePlaylists: () -> Unit,
    val onFavoriteArtists: () -> Unit,
    val onFavoriteAlbums: () -> Unit,
    val onRecentlyPlayed: () -> Unit,
    val onMostPlayed: () -> Unit,
    val onCollections: (CollectionEntry) -> Unit,
    val onRefreshArtists: () -> Unit,
    val onRefreshAlbums: () -> Unit,
    val onPrefetchArtist: (Artist) -> Unit,
    val onPrefetchAlbum: (Album) -> Unit,
    val onPlayDecadeMix: (Int) -> Unit,
    val onClearDecadeMixNotice: () -> Unit,
    val onPlayRadioStation: (PlexRadioStation) -> Unit,
    val onPlayPersonalMix: () -> Unit,
    val onPlayTracks: (List<Track>, Int) -> Unit,
    val onAddToUpNext: (Track) -> Unit,
    val onDownload: (Track) -> Unit,
)

@Composable
fun DesktopHomeRoute(
    state: DesktopHomeRouteState,
    actions: DesktopHomeRouteActions,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    DesktopHomeScreen(
        state = state.home,
        catalogRefreshing = state.catalogRefreshing,
        listState = listState,
        modifier = modifier,
        onTrack = actions.onTrack,
        onArtist = actions.onArtist,
        onAlbum = actions.onAlbum,
        onPlaylist = actions.onPlaylist,
        onRecentSongs = actions.onRecentSongs,
        onRecentArtists = actions.onRecentArtists,
        onRecentAlbums = actions.onRecentAlbums,
        onFavoritePlaylists = actions.onFavoritePlaylists,
        onFavoriteArtists = actions.onFavoriteArtists,
        onFavoriteAlbums = actions.onFavoriteAlbums,
        onRecentlyPlayed = actions.onRecentlyPlayed,
        onMostPlayed = actions.onMostPlayed,
        onCollections = actions.onCollections,
        onRefreshArtists = actions.onRefreshArtists,
        onRefreshAlbums = actions.onRefreshAlbums,
        onPrefetchArtist = actions.onPrefetchArtist,
        onPrefetchAlbum = actions.onPrefetchAlbum,
        onPlayDecadeMix = actions.onPlayDecadeMix,
        decadeMixNotice = state.decadeMixNotice,
        onClearDecadeMixNotice = actions.onClearDecadeMixNotice,
        radioStations = state.radioStations,
        radioStartingIds = state.radioStartingIds,
        onPlayRadioStation = actions.onPlayRadioStation,
        onPlayPersonalMix = actions.onPlayPersonalMix,
        onPlayTracks = actions.onPlayTracks,
        onAddToUpNext = actions.onAddToUpNext,
        onDownload = actions.onDownload,
        homeSections = state.homeSections,
        supportedCollectionEntries = state.supportedCollectionEntries,
        useBarePanels = state.useBarePanels,
    )
}

@Immutable
data class RecentlyAddedRouteState(
    val kind: RecentlyAddedKind,
    val catalog: CatalogSnapshot,
    val nowMs: Long,
    val nowPlaying: RecentlyAddedNowPlayingState = RecentlyAddedNowPlayingState(),
    val bottomContentPadding: Dp = 0.dp,
)

data class RecentlyAddedRouteActions(
    val onBack: () -> Unit,
    val onArtist: (Artist) -> Unit,
    val onAlbum: (Album) -> Unit,
    val onPlayTracks: (List<Track>, Int) -> Unit,
    val onAddToUpNext: (Track) -> Unit,
    val onDownload: (Track) -> Unit,
)

@Composable
fun RecentlyAddedRoute(
    state: RecentlyAddedRouteState,
    actions: RecentlyAddedRouteActions,
    modifier: Modifier = Modifier,
) {
    RecentlyAddedScreen(
        kind = state.kind,
        catalog = state.catalog,
        nowMs = state.nowMs,
        modifier = modifier,
        nowPlaying = state.nowPlaying,
        bottomContentPadding = state.bottomContentPadding,
        onBack = actions.onBack,
        onArtist = actions.onArtist,
        onAlbum = actions.onAlbum,
        onPlayTracks = actions.onPlayTracks,
        onAddToUpNext = actions.onAddToUpNext,
        onDownload = actions.onDownload,
    )
}
