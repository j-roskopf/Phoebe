package com.phoebe.app.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.phoebe.app.data.HomePlayedTrack
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSyncPhase
import com.phoebe.app.domain.CatalogSyncState
import com.phoebe.app.feature.home.HomeFeaturedAlbumStats
import com.phoebe.app.feature.home.HomeFeaturedArtistStats
import com.phoebe.app.feature.home.HomeUiState
import com.phoebe.app.feature.home.defaultMixDecades
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.CollectionFacet
import com.phoebe.app.domain.CollectionTarget
import com.phoebe.app.domain.HomeSection
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.PlexRadioStation
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.canTogglePlexLike
import com.phoebe.app.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import phoebe.feature.home.generated.resources.Res
import phoebe.feature.home.generated.resources.collection_album_genre
import phoebe.feature.home.generated.resources.collection_album_mood
import phoebe.feature.home.generated.resources.collection_album_style
import phoebe.feature.home.generated.resources.collection_artist_genre
import phoebe.feature.home.generated.resources.collection_artist_mood
import phoebe.feature.home.generated.resources.collection_artist_style
import phoebe.feature.home.generated.resources.mix_album_builder
import phoebe.feature.home.generated.resources.mix_artist_builder
import phoebe.feature.home.generated.resources.mix_decade
import phoebe.feature.home.generated.resources.mix_deep_cuts
import phoebe.feature.home.generated.resources.mix_library
import phoebe.feature.home.generated.resources.mix_personal
import phoebe.feature.home.generated.resources.mix_popular
import phoebe.feature.home.generated.resources.mix_top_tracks
import phoebe.feature.home.generated.resources.mix_time_travel

private val PhoneHomeAccordionBreakpoint: Dp = 600.dp
private val MobileHomePosterCardSize: Dp = 148.dp
private const val HomePlayedPanelMaxRows = 5
private const val HomeMixCardAspectRatio = 1f

@Composable
private fun HomeSectionLabel(label: String) {
    SectionLabel(
        label = label,
        color = PhoebeUi.primaryText,
        fontSize = 12.sp,
        fontWeight = FontWeight.Black,
    )
}

private fun Modifier.homeArtistArtworkBorder(color: Color): Modifier =
    border(BorderStroke(1.5.dp, color.copy(alpha = 0.34f)), CircleShape)

@Immutable
data class MobileHomeRouteState(
    val homeUiState: HomeUiState,
    val catalogRefreshing: Boolean,
    val homeSections: List<HomeSection>,
    val supportedCollectionEntries: Set<CollectionEntry>,
    val posterLoading: HomePosterLoadingState = HomePosterLoadingState(),
    val radioStations: List<PlexRadioStation>,
    val radioStartingIds: Set<String>,
    val decadeMixNotice: String?,
    val homeScreenLayoutMode: HomeScreenLayoutMode = HomeScreenLayoutMode.Default,
    val showPopularMix: Boolean = true,
    val showArtistAlbumMixBuilders: Boolean = true,
)

@Immutable
class MobileHomeCallbacks(
    val onArtist: (Artist) -> Unit,
    val onAlbum: (Album) -> Unit,
    val onPlaylist: (Playlist) -> Unit,
    val onRecentSongs: () -> Unit,
    val onRecentArtists: () -> Unit,
    val onRecentAlbums: () -> Unit,
    val onFavoritePlaylists: () -> Unit,
    val onFavoriteArtists: () -> Unit,
    val onFavoriteAlbums: () -> Unit,
    val onCollections: (CollectionEntry) -> Unit,
    val onRecentlyPlayed: () -> Unit,
    val onMostPlayed: () -> Unit,
    val onRefreshArtists: () -> Unit,
    val onRefreshAlbums: () -> Unit,
    val onPlayDecadeMix: (Int) -> Unit,
    val onClearDecadeMixNotice: () -> Unit,
    val onPlayRadioStation: (PlexRadioStation) -> Unit,
    val onPlayPersonalMix: () -> Unit,
    val onPlayPopularMix: () -> Unit,
    val onPlayTopTracksMix: () -> Unit,
    val onArtistMixBuilder: () -> Unit,
    val onAlbumMixBuilder: () -> Unit,
    val onPlayTracks: (List<Track>, Int) -> Unit,
    val onAddToUpNext: (Track) -> Unit,
    val onDownload: (Track) -> Unit,
)

@Composable
fun MobileHomeRoute(
    routeState: MobileHomeRouteState,
    listState: LazyListState,
    callbacks: MobileHomeCallbacks,
    modifier: Modifier = Modifier,
    initialExpandedPhoneSection: PhoneHomeAccordionSection? = null,
    topBar: (@Composable () -> Unit)? = null,
) {
    MobileHomeScreen(
        state = routeState.homeUiState,
        catalogRefreshing = routeState.catalogRefreshing,
        listState = listState,
        modifier = modifier,
        onArtist = callbacks.onArtist,
        onAlbum = callbacks.onAlbum,
        onPlaylist = callbacks.onPlaylist,
        onRecentSongs = callbacks.onRecentSongs,
        onRecentArtists = callbacks.onRecentArtists,
        onRecentAlbums = callbacks.onRecentAlbums,
        onFavoritePlaylists = callbacks.onFavoritePlaylists,
        onFavoriteArtists = callbacks.onFavoriteArtists,
        onFavoriteAlbums = callbacks.onFavoriteAlbums,
        onCollections = callbacks.onCollections,
        onRecentlyPlayed = callbacks.onRecentlyPlayed,
        onMostPlayed = callbacks.onMostPlayed,
        onRefreshArtists = callbacks.onRefreshArtists,
        onRefreshAlbums = callbacks.onRefreshAlbums,
        onPlayDecadeMix = callbacks.onPlayDecadeMix,
        decadeMixNotice = routeState.decadeMixNotice,
        onClearDecadeMixNotice = callbacks.onClearDecadeMixNotice,
        radioStations = routeState.radioStations,
        radioStartingIds = routeState.radioStartingIds,
        onPlayRadioStation = callbacks.onPlayRadioStation,
        posterLoading = routeState.posterLoading,
        onPlayPersonalMix = callbacks.onPlayPersonalMix,
        onPlayPopularMix = callbacks.onPlayPopularMix,
        onPlayTopTracksMix = callbacks.onPlayTopTracksMix,
        onArtistMixBuilder = callbacks.onArtistMixBuilder,
        onAlbumMixBuilder = callbacks.onAlbumMixBuilder,
        showPopularMix = routeState.showPopularMix,
        showArtistAlbumMixBuilders = routeState.showArtistAlbumMixBuilders,
        onPlayTracks = callbacks.onPlayTracks,
        onAddToUpNext = callbacks.onAddToUpNext,
        onDownload = callbacks.onDownload,
        homeSections = routeState.homeSections,
        supportedCollectionEntries = routeState.supportedCollectionEntries,
        initialExpandedPhoneSection = initialExpandedPhoneSection,
        layoutMode = routeState.homeScreenLayoutMode,
        topBar = topBar,
    )
}

@Composable
private fun HomeHorizontalCarousel(
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 10.dp,
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        content = content,
    )
}

@Composable
fun DesktopHomeScreen(
    state: HomeUiState,
    catalogRefreshing: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
    onTrack: (Track) -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onRecentSongs: () -> Unit,
    onRecentArtists: () -> Unit,
    onRecentAlbums: () -> Unit,
    onFavoritePlaylists: () -> Unit = {},
    onFavoriteArtists: () -> Unit = {},
    onFavoriteAlbums: () -> Unit = {},
    onCollections: (CollectionEntry) -> Unit,
    onRecentlyPlayed: () -> Unit,
    onMostPlayed: () -> Unit,
    onRefreshArtists: () -> Unit,
    onRefreshAlbums: () -> Unit,
    onPrefetchArtist: (Artist) -> Unit = {},
    onPrefetchAlbum: (Album) -> Unit = {},
    onPlayDecadeMix: (Int) -> Unit = {},
    decadeMixNotice: String? = null,
    onClearDecadeMixNotice: () -> Unit = {},
    radioStations: List<PlexRadioStation> = emptyList(),
    radioStartingIds: Set<String> = emptySet(),
    onPlayRadioStation: (PlexRadioStation) -> Unit = {},
    posterLoading: HomePosterLoadingState = HomePosterLoadingState(),
    onPlayPersonalMix: () -> Unit = {},
    onPlayPopularMix: () -> Unit = {},
    onPlayTopTracksMix: () -> Unit = {},
    onArtistMixBuilder: () -> Unit = {},
    onAlbumMixBuilder: () -> Unit = {},
    showPopularMix: Boolean = true,
    showArtistAlbumMixBuilders: Boolean = true,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    homeSections: List<HomeSection> = HomeSection.defaultOrder,
    supportedCollectionEntries: Set<CollectionEntry> = allCollectionEntries().toSet(),
    useBarePanels: Boolean = false,
) {
    var showDecadeMix by remember { mutableStateOf(false) }
    if (showDecadeMix) {
        DecadeMixDialog(
            decades = defaultMixDecades(),
            notice = decadeMixNotice,
            onDismiss = {
                showDecadeMix = false
                onClearDecadeMixNotice()
            },
            onSelect = { decade ->
                onPlayDecadeMix(decade)
            },
        )
    }
    val playedPanelMaxRows = HomePlayedPanelMaxRows
    val panelStyle = if (useBarePanels) HomePanelStyle.Bare else HomePanelStyle.Card
    val sharedPlayedTrackKeys = remember(state.mostPlayedTracks, state.recentlyPlayedTracks) {
        val visibleTrackIds = state.mostPlayedTracks.take(playedPanelMaxRows).map { it.track.id } +
            state.recentlyPlayedTracks.take(playedPanelMaxRows).map { it.track.id }
        visibleTrackIds.groupingBy { it }.eachCount()
    }
    val sharedKeyForPlayedTrack: (Track) -> String? = { track ->
        if (sharedPlayedTrackKeys[track.id] == 1) "song:${track.id}" else null
    }
    val artistThumbs = state.artistThumbs
    val albumArtworkFallbacks = state.albumArtworkFallbacks
    val catalogSyncInProgress = LocalCatalogSyncInProgress.current
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().padding(
            PaddingValues(start = 36.dp, end = 28.dp, top = 32.dp, bottom = 24.dp),
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item("header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Home", color = PhoebeUi.primaryText, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black)
                Text("Pick up where you left off", color = PhoebeUi.secondaryText, fontSize = 13.sp)
            }
        }
        if (catalogSyncInProgress) {
            item(contentType = "loading") { CatalogLoadingStrip() }
        }
        normalizedHomeSections(homeSections).forEach { section ->
            when (section) {
                HomeSection.Mixes -> item("mixes") {
                    DesktopMixesPanel(
                        onPlayPersonalMix = onPlayPersonalMix,
                        onPlayPopularMix = onPlayPopularMix,
                        onPlayTopTracksMix = onPlayTopTracksMix,
                        onArtistMixBuilder = onArtistMixBuilder,
                        onAlbumMixBuilder = onAlbumMixBuilder,
                        showPopularMix = showPopularMix,
                        showArtistAlbumMixBuilders = showArtistAlbumMixBuilders,
                        posterLoading = posterLoading,
                        radioStations = radioStations,
                        radioStartingIds = radioStartingIds,
                        onPlayRadioStation = onPlayRadioStation,
                        onClearDecadeMixNotice = onClearDecadeMixNotice,
                        panelStyle = panelStyle,
                    ) {
                        showDecadeMix = true
                    }
                }
                HomeSection.Collections -> item("collections") {
                    HomePanel(Modifier.fillMaxWidth(), style = panelStyle) {
                        HomeSectionLabel("COLLECTIONS")
                        DesktopCollectionsGrid(supportedCollectionEntries, posterLoading.collectionEntry, onCollections)
                    }
                }
                HomeSection.Favorites -> {
                    item("favorite-playlists") {
                        DesktopFavoritePlaylistsPanel(
                            state.favoritePlaylists,
                            onPlaylist,
                            onFavoritePlaylists,
                            totalCount = state.favoritePlaylistCount,
                            panelStyle = panelStyle,
                        )
                    }
                    item("favorite-artists") { DesktopFavoriteArtistsPanel(state.favoriteArtists, artistThumbs, onArtist, onFavoriteArtists, totalCount = state.favoriteArtistCount, panelStyle = panelStyle) }
                    item("favorite-albums") { DesktopFavoriteAlbumsPanel(state.favoriteAlbums, albumArtworkFallbacks, onAlbum, onFavoriteAlbums, totalCount = state.favoriteAlbumCount, panelStyle = panelStyle) }
                }
                HomeSection.FavoritePlaylists -> item("favorite-playlists") {
                    DesktopFavoritePlaylistsPanel(
                        state.favoritePlaylists,
                        onPlaylist,
                        onFavoritePlaylists,
                        totalCount = state.favoritePlaylistCount,
                        panelStyle = panelStyle,
                    )
                }
                HomeSection.FavoriteArtists -> item("favorite-artists") { DesktopFavoriteArtistsPanel(state.favoriteArtists, artistThumbs, onArtist, onFavoriteArtists, totalCount = state.favoriteArtistCount, panelStyle = panelStyle) }
                HomeSection.FavoriteAlbums -> item("favorite-albums") { DesktopFavoriteAlbumsPanel(state.favoriteAlbums, albumArtworkFallbacks, onAlbum, onFavoriteAlbums, totalCount = state.favoriteAlbumCount, panelStyle = panelStyle) }
                HomeSection.Recents -> {
                    item("recent-songs") { DesktopRecentSongsPanel(state.recentlyAddedTracks, onRecentSongs, onPlayTracks, panelStyle = panelStyle) }
                    item("recent-artists") { DesktopRecentArtistsPanel(state.recentlyAddedArtists, artistThumbs, onRecentArtists, onArtist, panelStyle = panelStyle) }
                    item("recent-albums") { DesktopRecentAlbumsPanel(state.recentlyAddedAlbums, albumArtworkFallbacks, onRecentAlbums, onAlbum, panelStyle = panelStyle) }
                }
                HomeSection.RecentSongs -> item("recent-songs") { DesktopRecentSongsPanel(state.recentlyAddedTracks, onRecentSongs, onPlayTracks, panelStyle = panelStyle) }
                HomeSection.RecentArtists -> item("recent-artists") { DesktopRecentArtistsPanel(state.recentlyAddedArtists, artistThumbs, onRecentArtists, onArtist, panelStyle = panelStyle) }
                HomeSection.RecentAlbums -> item("recent-albums") { DesktopRecentAlbumsPanel(state.recentlyAddedAlbums, albumArtworkFallbacks, onRecentAlbums, onAlbum, panelStyle = panelStyle) }
                HomeSection.Played -> item("played") {
                    DesktopPlayedPanels(state, onPlayTracks, onAddToUpNext, onDownload, onMostPlayed, onRecentlyPlayed, playedPanelMaxRows, sharedKeyForPlayedTrack, panelStyle)
                }
                HomeSection.Random -> item("random") {
                    DesktopRandomPanels(state, catalogRefreshing, albumArtworkFallbacks, onArtist, onAlbum, onRefreshArtists, onRefreshAlbums, onPrefetchArtist, onPrefetchAlbum, panelStyle)
                }
            }
        }
    }
}

@Composable
fun MobileHomeScreen(
    state: HomeUiState,
    catalogRefreshing: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onRecentSongs: () -> Unit,
    onRecentArtists: () -> Unit,
    onRecentAlbums: () -> Unit,
    onFavoritePlaylists: () -> Unit = {},
    onFavoriteArtists: () -> Unit = {},
    onFavoriteAlbums: () -> Unit = {},
    onCollections: (CollectionEntry) -> Unit,
    onRecentlyPlayed: () -> Unit,
    onMostPlayed: () -> Unit,
    onRefreshArtists: () -> Unit,
    onRefreshAlbums: () -> Unit,
    onPrefetchArtist: (Artist) -> Unit = {},
    onPrefetchAlbum: (Album) -> Unit = {},
    onPlayDecadeMix: (Int) -> Unit = {},
    decadeMixNotice: String? = null,
    onClearDecadeMixNotice: () -> Unit = {},
    radioStations: List<PlexRadioStation> = emptyList(),
    radioStartingIds: Set<String> = emptySet(),
    onPlayRadioStation: (PlexRadioStation) -> Unit = {},
    posterLoading: HomePosterLoadingState = HomePosterLoadingState(),
    onPlayPersonalMix: () -> Unit = {},
    onPlayPopularMix: () -> Unit = {},
    onPlayTopTracksMix: () -> Unit = {},
    onArtistMixBuilder: () -> Unit = {},
    onAlbumMixBuilder: () -> Unit = {},
    showPopularMix: Boolean = true,
    showArtistAlbumMixBuilders: Boolean = true,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    homeSections: List<HomeSection> = HomeSection.defaultOrder,
    supportedCollectionEntries: Set<CollectionEntry> = allCollectionEntries().toSet(),
    initialExpandedPhoneSection: PhoneHomeAccordionSection? = null,
    layoutMode: HomeScreenLayoutMode = HomeScreenLayoutMode.Default,
    topBar: (@Composable () -> Unit)? = null,
) {
    var showDecadeMix by remember { mutableStateOf(false) }
    if (showDecadeMix) {
        DecadeMixDialog(
            decades = defaultMixDecades(),
            notice = decadeMixNotice,
            onDismiss = {
                showDecadeMix = false
                onClearDecadeMixNotice()
            },
            onSelect = { decade ->
                onPlayDecadeMix(decade)
            },
        )
    }
    val sectionOrder = remember(homeSections) { normalizedHomeSections(homeSections) }
    val collectionRows = remember(supportedCollectionEntries) { collectionEntryRows(supportedCollectionEntries) }
    val artistThumbs = state.artistThumbs
    val albumArtworkFallbacks = state.albumArtworkFallbacks
    val catalogSyncInProgress = LocalCatalogSyncInProgress.current
    val chromePadding = LocalMobileChromePadding.current
    val supportedCollections = remember(supportedCollectionEntries) { collectionEntries(supportedCollectionEntries) }
    var expandedPhoneSection by remember(initialExpandedPhoneSection) {
        mutableStateOf(initialExpandedPhoneSection)
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val usePhoneAccordions = maxWidth < PhoneHomeAccordionBreakpoint
        if (usePhoneAccordions && layoutMode == HomeScreenLayoutMode.Expanded) {
            MobileExpandedHomeContent(
                state = state,
                listState = listState,
                modifier = Modifier.fillMaxSize(),
                chromePadding = chromePadding,
                catalogRefreshing = catalogRefreshing,
                catalogSyncInProgress = catalogSyncInProgress,
                sectionOrder = sectionOrder,
                collectionEntries = supportedCollections,
                artistThumbs = artistThumbs,
                albumArtworkFallbacks = albumArtworkFallbacks,
                onArtist = onArtist,
                onAlbum = onAlbum,
                onPlaylist = onPlaylist,
                onRecentSongs = onRecentSongs,
                onRecentArtists = onRecentArtists,
                onRecentAlbums = onRecentAlbums,
                onFavoritePlaylists = onFavoritePlaylists,
                onFavoriteArtists = onFavoriteArtists,
                onFavoriteAlbums = onFavoriteAlbums,
                onCollections = onCollections,
                onRecentlyPlayed = onRecentlyPlayed,
                onMostPlayed = onMostPlayed,
                onRefreshArtists = onRefreshArtists,
                onRefreshAlbums = onRefreshAlbums,
                posterLoading = posterLoading,
                onPlayPersonalMix = onPlayPersonalMix,
                onPlayPopularMix = onPlayPopularMix,
                onPlayTopTracksMix = onPlayTopTracksMix,
                onArtistMixBuilder = onArtistMixBuilder,
                onAlbumMixBuilder = onAlbumMixBuilder,
                showPopularMix = showPopularMix,
                showArtistAlbumMixBuilders = showArtistAlbumMixBuilders,
                radioStations = radioStations,
                radioStartingIds = radioStartingIds,
                onPlayRadioStation = onPlayRadioStation,
                onShowDecadeMix = { showDecadeMix = true },
                onClearDecadeMixNotice = onClearDecadeMixNotice,
                onPlayTracks = onPlayTracks,
                onAddToUpNext = onAddToUpNext,
                onDownload = onDownload,
                topBar = topBar,
            )
        } else {
            MobileHomeContent(
                state = state,
                listState = listState,
                modifier = Modifier.fillMaxSize(),
                chromePadding = chromePadding,
                catalogRefreshing = catalogRefreshing,
                catalogSyncInProgress = catalogSyncInProgress,
                sectionOrder = sectionOrder,
                collectionRows = collectionRows,
                collectionEntries = supportedCollections,
                artistThumbs = artistThumbs,
                albumArtworkFallbacks = albumArtworkFallbacks,
                usePhoneAccordions = usePhoneAccordions,
                expandedPhoneSection = if (usePhoneAccordions) expandedPhoneSection else null,
                onExpandedPhoneSection = { expandedPhoneSection = it },
                onArtist = onArtist,
                onAlbum = onAlbum,
                onPlaylist = onPlaylist,
                onRecentSongs = onRecentSongs,
                onRecentArtists = onRecentArtists,
                onRecentAlbums = onRecentAlbums,
                onFavoritePlaylists = onFavoritePlaylists,
                onFavoriteArtists = onFavoriteArtists,
                onFavoriteAlbums = onFavoriteAlbums,
                onCollections = onCollections,
                onRecentlyPlayed = onRecentlyPlayed,
                onMostPlayed = onMostPlayed,
                onRefreshArtists = onRefreshArtists,
                onRefreshAlbums = onRefreshAlbums,
                posterLoading = posterLoading,
                onPlayPersonalMix = onPlayPersonalMix,
                onPlayPopularMix = onPlayPopularMix,
                onPlayTopTracksMix = onPlayTopTracksMix,
                onArtistMixBuilder = onArtistMixBuilder,
                onAlbumMixBuilder = onAlbumMixBuilder,
                showPopularMix = showPopularMix,
                showArtistAlbumMixBuilders = showArtistAlbumMixBuilders,
                radioStations = radioStations,
                radioStartingIds = radioStartingIds,
                onPlayRadioStation = onPlayRadioStation,
                onShowDecadeMix = { showDecadeMix = true },
                onClearDecadeMixNotice = onClearDecadeMixNotice,
                onPlayTracks = onPlayTracks,
                topBar = topBar,
            )
        }
    }
}

enum class PhoneHomeAccordionSection {
    Mixes,
    Collections,
    Favorites,
    Played,
    Recents,
    Random,
}

private sealed interface MobileHomeSectionItem {
    data class Standard(val section: HomeSection) : MobileHomeSectionItem
    data class FavoritesGroup(val subsections: List<HomeSection>) : MobileHomeSectionItem
    data class RecentsGroup(val subsections: List<HomeSection>) : MobileHomeSectionItem
}

private fun mobileHomeSectionItems(
    sectionOrder: List<HomeSection>,
    groupFavorites: Boolean = false,
): List<MobileHomeSectionItem> {
    val favoriteSections = setOf(HomeSection.FavoritePlaylists, HomeSection.FavoriteArtists, HomeSection.FavoriteAlbums)
    val recentSections = setOf(HomeSection.RecentSongs, HomeSection.RecentArtists, HomeSection.RecentAlbums)
    val items = mutableListOf<MobileHomeSectionItem>()
    val pendingRecents = mutableListOf<HomeSection>()
    var favoritesAdded = false
    fun flushRecents() {
        if (pendingRecents.isNotEmpty()) {
            items.add(MobileHomeSectionItem.RecentsGroup(pendingRecents.toList()))
            pendingRecents.clear()
        }
    }
    sectionOrder.forEach { section ->
        when {
            groupFavorites && section in favoriteSections -> {
                flushRecents()
                if (!favoritesAdded) {
                    val enabledFavorites = sectionOrder.filter { it in favoriteSections }
                    items.add(MobileHomeSectionItem.FavoritesGroup(enabledFavorites))
                    favoritesAdded = true
                }
            }
            section in recentSections -> {
                pendingRecents.add(section)
            }
            else -> {
                flushRecents()
                items.add(MobileHomeSectionItem.Standard(section))
            }
        }
    }
    flushRecents()
    return items
}

private fun recentActionsFor(
    subsections: List<HomeSection>,
    onRecentSongs: () -> Unit,
    onRecentArtists: () -> Unit,
    onRecentAlbums: () -> Unit,
): List<PhoneHomeAction> = buildList {
    if (HomeSection.RecentSongs in subsections) {
        add(PhoneHomeAction("Songs", PhoebeIcon.Music, onClick = onRecentSongs))
    }
    if (HomeSection.RecentArtists in subsections) {
        add(PhoneHomeAction("Artists", PhoebeIcon.Person, onClick = onRecentArtists))
    }
    if (HomeSection.RecentAlbums in subsections) {
        add(PhoneHomeAction("Albums", PhoebeIcon.Grid, onClick = onRecentAlbums))
    }
}

private fun favoriteActionsFor(
    subsections: List<HomeSection>,
    onFavoritePlaylists: () -> Unit,
    onFavoriteArtists: () -> Unit,
    onFavoriteAlbums: () -> Unit,
): List<PhoneHomeAction> = buildList {
    if (HomeSection.FavoritePlaylists in subsections) {
        add(PhoneHomeAction("Playlists", PhoebeIcon.Heart, onClick = onFavoritePlaylists))
    }
    if (HomeSection.FavoriteArtists in subsections) {
        add(PhoneHomeAction("Artists", PhoebeIcon.Library, onClick = onFavoriteArtists))
    }
    if (HomeSection.FavoriteAlbums in subsections) {
        add(PhoneHomeAction("Albums", PhoebeIcon.Grid, onClick = onFavoriteAlbums))
    }
}

val HomeAccordionScreenshotSections = listOf(
    HomeSection.Mixes,
    HomeSection.Collections,
    HomeSection.FavoritePlaylists,
    HomeSection.FavoriteArtists,
    HomeSection.FavoriteAlbums,
    HomeSection.Recents,
    HomeSection.Played,
    HomeSection.Random,
)

fun homeAccordionScreenshotScrollIndex(
    expandedSection: PhoneHomeAccordionSection?,
    homeSections: List<HomeSection> = HomeAccordionScreenshotSections,
): Int =
    expandedSection?.let {
        phoneAccordionLazyItemIndex(it, normalizedHomeSections(homeSections), catalogSyncInProgress = false)
    } ?: 0

private fun phoneAccordionLazyItemIndex(
    target: PhoneHomeAccordionSection,
    sectionOrder: List<HomeSection>,
    catalogSyncInProgress: Boolean,
): Int? {
    var index = 0
    if (catalogSyncInProgress) index++
    mobileHomeSectionItems(sectionOrder, groupFavorites = true).forEach { item ->
        when (item) {
            is MobileHomeSectionItem.FavoritesGroup -> {
                if (target == PhoneHomeAccordionSection.Favorites) return index
                index++
            }
            is MobileHomeSectionItem.RecentsGroup -> {
                if (target == PhoneHomeAccordionSection.Recents) return index
                index++
            }
            is MobileHomeSectionItem.Standard -> when (item.section) {
                HomeSection.Mixes -> {
                    if (target == PhoneHomeAccordionSection.Mixes) return index
                    index++
                }
                HomeSection.Collections -> {
                    if (target == PhoneHomeAccordionSection.Collections) return index
                    index++
                }
                HomeSection.Favorites -> index += 3
                HomeSection.FavoritePlaylists,
                HomeSection.FavoriteArtists,
                HomeSection.FavoriteAlbums -> index++
                HomeSection.Played -> {
                    if (target == PhoneHomeAccordionSection.Played) return index
                    index++
                }
                HomeSection.Random -> {
                    if (target == PhoneHomeAccordionSection.Random) return index
                    index++
                }
                else -> index++
            }
        }
    }
    return null
}

private fun phoneAccordionNeedsScrollIntoView(listState: LazyListState, index: Int): Boolean {
    val layoutInfo = listState.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) return true

    val itemInfo = visibleItems.find { it.index == index } ?: run {
        return index > visibleItems.last().index
    }

    val itemBottom = itemInfo.offset + itemInfo.size
    return itemBottom > layoutInfo.viewportEndOffset
}

@Composable
private fun MobileHomeContent(
    state: HomeUiState,
    listState: LazyListState,
    modifier: Modifier,
    chromePadding: MobileChromePadding,
    catalogRefreshing: Boolean,
    catalogSyncInProgress: Boolean,
    sectionOrder: List<HomeSection>,
    collectionRows: List<List<HomeCollectionEntry>>,
    collectionEntries: List<HomeCollectionEntry>,
    artistThumbs: Map<String, String>,
    albumArtworkFallbacks: Map<String, String>,
    usePhoneAccordions: Boolean,
    expandedPhoneSection: PhoneHomeAccordionSection?,
    onExpandedPhoneSection: (PhoneHomeAccordionSection?) -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onRecentSongs: () -> Unit,
    onRecentArtists: () -> Unit,
    onRecentAlbums: () -> Unit,
    onFavoritePlaylists: () -> Unit,
    onFavoriteArtists: () -> Unit,
    onFavoriteAlbums: () -> Unit,
    onCollections: (CollectionEntry) -> Unit,
    onRecentlyPlayed: () -> Unit,
    onMostPlayed: () -> Unit,
    onRefreshArtists: () -> Unit,
    onRefreshAlbums: () -> Unit,
    posterLoading: HomePosterLoadingState,
    onPlayPersonalMix: () -> Unit,
    onPlayPopularMix: () -> Unit,
    onPlayTopTracksMix: () -> Unit,
    onArtistMixBuilder: () -> Unit,
    onAlbumMixBuilder: () -> Unit,
    showPopularMix: Boolean,
    showArtistAlbumMixBuilders: Boolean,
    radioStations: List<PlexRadioStation>,
    radioStartingIds: Set<String>,
    onPlayRadioStation: (PlexRadioStation) -> Unit,
    onShowDecadeMix: () -> Unit,
    onClearDecadeMixNotice: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    topBar: (@Composable () -> Unit)?,
) {
    LaunchedEffect(expandedPhoneSection, sectionOrder, catalogSyncInProgress, usePhoneAccordions) {
        val target = expandedPhoneSection ?: return@LaunchedEffect
        if (!usePhoneAccordions) return@LaunchedEffect
        delay(240)
        val index = phoneAccordionLazyItemIndex(target, sectionOrder, catalogSyncInProgress) ?: return@LaunchedEffect
        if (phoneAccordionNeedsScrollIntoView(listState, index)) {
            listState.animateScrollToItem(index)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 16.dp),
        contentPadding = PaddingValues(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 10.dp,
            bottom = chromePadding.bottom + 10.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        topBar?.let { header ->
            item(key = "top-bar", contentType = "top-bar") { header() }
        }
        if (catalogSyncInProgress) {
            item(key = "loading", contentType = "loading") { CatalogLoadingStrip() }
        }
        mobileHomeSectionItems(sectionOrder, groupFavorites = usePhoneAccordions).forEach { item ->
            when (item) {
                is MobileHomeSectionItem.FavoritesGroup -> item(key = "favorites", contentType = "favorites-section") {
                    PhoneFavoritesAccordionSection(
                        subsections = item.subsections,
                        expanded = expandedPhoneSection == PhoneHomeAccordionSection.Favorites,
                        onToggle = {
                            onExpandedPhoneSection(
                                toggledPhoneHomeAccordion(expandedPhoneSection, PhoneHomeAccordionSection.Favorites),
                            )
                        },
                        onFavoritePlaylists = onFavoritePlaylists,
                        onFavoriteArtists = onFavoriteArtists,
                        onFavoriteAlbums = onFavoriteAlbums,
                    )
                }
                is MobileHomeSectionItem.RecentsGroup -> item(key = "recents", contentType = "recents-section") {
                    if (usePhoneAccordions) {
                        PhoneRecentsAccordionSection(
                            subsections = item.subsections,
                            expanded = expandedPhoneSection == PhoneHomeAccordionSection.Recents,
                            onToggle = {
                                onExpandedPhoneSection(
                                    toggledPhoneHomeAccordion(expandedPhoneSection, PhoneHomeAccordionSection.Recents),
                                )
                            },
                            onRecentSongs = onRecentSongs,
                            onRecentArtists = onRecentArtists,
                            onRecentAlbums = onRecentAlbums,
                        )
                    } else {
                        MobileRecentsShortcutsSection(
                            subsections = item.subsections,
                            onRecentSongs = onRecentSongs,
                            onRecentArtists = onRecentArtists,
                            onRecentAlbums = onRecentAlbums,
                        )
                    }
                }
                is MobileHomeSectionItem.Standard -> when (item.section) {
                    HomeSection.Mixes -> item(key = "mix", contentType = "mix-section") {
                        if (usePhoneAccordions) {
                            PhoneMixesAccordionSection(
                                expanded = expandedPhoneSection == PhoneHomeAccordionSection.Mixes,
                                onToggle = {
                                    onExpandedPhoneSection(
                                        toggledPhoneHomeAccordion(expandedPhoneSection, PhoneHomeAccordionSection.Mixes),
                                    )
                                },
                                onPlayPersonalMix = onPlayPersonalMix,
                                onPlayPopularMix = onPlayPopularMix,
                                onPlayTopTracksMix = onPlayTopTracksMix,
                                onArtistMixBuilder = onArtistMixBuilder,
                                onAlbumMixBuilder = onAlbumMixBuilder,
                                posterLoading = posterLoading,
                                radioStations = radioStations,
                                radioStartingIds = radioStartingIds,
                                showPopularMix = showPopularMix,
                                showArtistAlbumMixBuilders = showArtistAlbumMixBuilders,
                                onPlayRadioStation = onPlayRadioStation,
                                onClearDecadeMixNotice = onClearDecadeMixNotice,
                                onShowDecadeMix = onShowDecadeMix,
                            )
                        } else {
                            MobileMixesSection(
                                onPlayPersonalMix = onPlayPersonalMix,
                                onPlayPopularMix = onPlayPopularMix,
                                onPlayTopTracksMix = onPlayTopTracksMix,
                                onArtistMixBuilder = onArtistMixBuilder,
                                onAlbumMixBuilder = onAlbumMixBuilder,
                                showPopularMix = showPopularMix,
                                showArtistAlbumMixBuilders = showArtistAlbumMixBuilders,
                                posterLoading = posterLoading,
                                radioStations = radioStations,
                                radioStartingIds = radioStartingIds,
                                onPlayRadioStation = onPlayRadioStation,
                                onClearDecadeMixNotice = onClearDecadeMixNotice,
                                onShowDecadeMix = onShowDecadeMix,
                            )
                        }
                    }
                    HomeSection.Collections -> item(key = "collections", contentType = "collections-section") {
                    if (usePhoneAccordions) {
                        PhoneCollectionsAccordionSection(
                            collectionEntries = collectionEntries,
                            expanded = expandedPhoneSection == PhoneHomeAccordionSection.Collections,
                            onToggle = {
                                onExpandedPhoneSection(
                                    toggledPhoneHomeAccordion(expandedPhoneSection, PhoneHomeAccordionSection.Collections),
                                )
                            },
                            loadingCollectionEntry = posterLoading.collectionEntry,
                            onCollections = onCollections,
                        )
                    } else {
                        MobileCollectionsSection(collectionRows, posterLoading.collectionEntry, onCollections)
                    }
                }
                HomeSection.Favorites -> {
                    item(key = "favorite-playlists", contentType = "favorite-playlists-section") {
                        MobileFavoritePlaylistsSection(
                            state.favoritePlaylists,
                            onPlaylist,
                            onFavoritePlaylists,
                            totalCount = state.favoritePlaylistCount,
                        )
                    }
                    item(key = "favorite-artists", contentType = "favorite-artists-section") { MobileFavoriteArtistsSection(state.favoriteArtists, artistThumbs, onArtist, onFavoriteArtists, totalCount = state.favoriteArtistCount) }
                    item(key = "favorite-albums", contentType = "favorite-albums-section") { MobileFavoriteAlbumsSection(state.favoriteAlbums, albumArtworkFallbacks, onAlbum, onFavoriteAlbums, totalCount = state.favoriteAlbumCount) }
                }
                HomeSection.FavoritePlaylists -> item(key = "favorite-playlists", contentType = "favorite-playlists-section") {
                    MobileFavoritePlaylistsSection(
                        state.favoritePlaylists,
                        onPlaylist,
                        onFavoritePlaylists,
                        totalCount = state.favoritePlaylistCount,
                    )
                }
                HomeSection.FavoriteArtists -> item(key = "favorite-artists", contentType = "favorite-artists-section") { MobileFavoriteArtistsSection(state.favoriteArtists, artistThumbs, onArtist, onFavoriteArtists, totalCount = state.favoriteArtistCount) }
                HomeSection.FavoriteAlbums -> item(key = "favorite-albums", contentType = "favorite-albums-section") { MobileFavoriteAlbumsSection(state.favoriteAlbums, albumArtworkFallbacks, onAlbum, onFavoriteAlbums, totalCount = state.favoriteAlbumCount) }
                HomeSection.Played -> item(key = "played-shortcuts", contentType = "played-shortcuts-section") {
                    if (usePhoneAccordions) {
                        PhonePlayedAccordionSection(
                            expanded = expandedPhoneSection == PhoneHomeAccordionSection.Played,
                            onToggle = {
                                onExpandedPhoneSection(
                                    toggledPhoneHomeAccordion(expandedPhoneSection, PhoneHomeAccordionSection.Played),
                                )
                            },
                            onRecentlyPlayed = onRecentlyPlayed,
                            onMostPlayed = onMostPlayed,
                        )
                    } else {
                        MobilePlayedHistoryShortcuts(
                            onRecentlyPlayed = onRecentlyPlayed,
                            onMostPlayed = onMostPlayed,
                        )
                    }
                }
                HomeSection.Random -> item(key = "random", contentType = "random-section") {
                    if (usePhoneAccordions) {
                        PhoneRandomAccordionSection(
                            state = state,
                            catalogRefreshing = catalogRefreshing,
                            albumArtworkFallbacks = albumArtworkFallbacks,
                            expanded = expandedPhoneSection == PhoneHomeAccordionSection.Random,
                            onToggle = {
                                onExpandedPhoneSection(
                                    toggledPhoneHomeAccordion(expandedPhoneSection, PhoneHomeAccordionSection.Random),
                                )
                            },
                            onArtist = onArtist,
                            onAlbum = onAlbum,
                            onRefreshArtists = onRefreshArtists,
                            onRefreshAlbums = onRefreshAlbums,
                        )
                    } else {
                        DesktopRandomPanels(
                            state = state,
                            catalogRefreshing = catalogRefreshing,
                            albumArtworkFallbacks = albumArtworkFallbacks,
                            onArtist = onArtist,
                            onAlbum = onAlbum,
                            onRefreshArtists = onRefreshArtists,
                            onRefreshAlbums = onRefreshAlbums,
                            onPrefetchArtist = {},
                            onPrefetchAlbum = {},
                        )
                    }
                }
                else -> Unit
                }
            }
        }
    }
}

@Composable
private fun MobileExpandedHomeContent(
    state: HomeUiState,
    listState: LazyListState,
    modifier: Modifier,
    chromePadding: MobileChromePadding,
    catalogRefreshing: Boolean,
    catalogSyncInProgress: Boolean,
    sectionOrder: List<HomeSection>,
    collectionEntries: List<HomeCollectionEntry>,
    artistThumbs: Map<String, String>,
    albumArtworkFallbacks: Map<String, String>,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onRecentSongs: () -> Unit,
    onRecentArtists: () -> Unit,
    onRecentAlbums: () -> Unit,
    onFavoritePlaylists: () -> Unit,
    onFavoriteArtists: () -> Unit,
    onFavoriteAlbums: () -> Unit,
    onCollections: (CollectionEntry) -> Unit,
    onRecentlyPlayed: () -> Unit,
    onMostPlayed: () -> Unit,
    onRefreshArtists: () -> Unit,
    onRefreshAlbums: () -> Unit,
    posterLoading: HomePosterLoadingState,
    onPlayPersonalMix: () -> Unit,
    onPlayPopularMix: () -> Unit,
    onPlayTopTracksMix: () -> Unit,
    onArtistMixBuilder: () -> Unit,
    onAlbumMixBuilder: () -> Unit,
    showPopularMix: Boolean,
    showArtistAlbumMixBuilders: Boolean,
    radioStations: List<PlexRadioStation>,
    radioStartingIds: Set<String>,
    onPlayRadioStation: (PlexRadioStation) -> Unit,
    onShowDecadeMix: () -> Unit,
    onClearDecadeMixNotice: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    topBar: (@Composable () -> Unit)?,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 16.dp),
        contentPadding = PaddingValues(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 10.dp,
            bottom = chromePadding.bottom + 10.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        topBar?.let { header ->
            item(key = "top-bar", contentType = "top-bar") { header() }
        }
        if (catalogSyncInProgress) {
            item(key = "loading", contentType = "loading") { CatalogLoadingStrip() }
        }
        mobileHomeSectionItems(sectionOrder, groupFavorites = true).forEach { item ->
            when (item) {
                is MobileHomeSectionItem.FavoritesGroup -> {
                    if (HomeSection.FavoritePlaylists in item.subsections) {
                        item(key = "expanded-favorite-playlists", contentType = "expanded-favorite-playlists") {
                            ExpandedFavoritePlaylistsShelf(
                                playlists = state.favoritePlaylists,
                                totalCount = state.favoritePlaylistCount,
                                onPlaylist = onPlaylist,
                                onViewAll = onFavoritePlaylists,
                            )
                        }
                    }
                    if (HomeSection.FavoriteArtists in item.subsections) {
                        item(key = "expanded-favorite-artists", contentType = "expanded-favorite-artists") {
                            ExpandedFavoriteArtistsShelf(
                                artists = state.favoriteArtists,
                                artistThumbs = artistThumbs,
                                totalCount = state.favoriteArtistCount,
                                onArtist = onArtist,
                                onViewAll = onFavoriteArtists,
                            )
                        }
                    }
                    if (HomeSection.FavoriteAlbums in item.subsections) {
                        item(key = "expanded-favorite-albums", contentType = "expanded-favorite-albums") {
                            ExpandedFavoriteAlbumsShelf(
                                albums = state.favoriteAlbums,
                                albumArtworkFallbacks = albumArtworkFallbacks,
                                totalCount = state.favoriteAlbumCount,
                                onAlbum = onAlbum,
                                onViewAll = onFavoriteAlbums,
                            )
                        }
                    }
                }
                is MobileHomeSectionItem.RecentsGroup -> {
                    if (HomeSection.RecentSongs in item.subsections) {
                        item(key = "expanded-recent-songs", contentType = "expanded-recent-songs") {
                            ExpandedRecentSongsShelf(
                                tracks = state.recentlyAddedTracks,
                                onViewAll = onRecentSongs,
                                onPlayTracks = onPlayTracks,
                            )
                        }
                    }
                    if (HomeSection.RecentArtists in item.subsections) {
                        item(key = "expanded-recent-artists", contentType = "expanded-recent-artists") {
                            ExpandedRecentArtistsShelf(
                                artists = state.recentlyAddedArtists,
                                artistThumbs = artistThumbs,
                                onArtist = onArtist,
                                onViewAll = onRecentArtists,
                            )
                        }
                    }
                    if (HomeSection.RecentAlbums in item.subsections) {
                        item(key = "expanded-recent-albums", contentType = "expanded-recent-albums") {
                            ExpandedRecentAlbumsShelf(
                                albums = state.recentlyAddedAlbums,
                                albumArtworkFallbacks = albumArtworkFallbacks,
                                onAlbum = onAlbum,
                                onViewAll = onRecentAlbums,
                            )
                        }
                    }
                }
                is MobileHomeSectionItem.Standard -> when (item.section) {
                    HomeSection.Mixes -> item(key = "expanded-mixes", contentType = "expanded-mixes") {
                        ExpandedMixesShelf(
                            onPlayPersonalMix = onPlayPersonalMix,
                            onPlayPopularMix = onPlayPopularMix,
                            onPlayTopTracksMix = onPlayTopTracksMix,
                            onArtistMixBuilder = onArtistMixBuilder,
                            onAlbumMixBuilder = onAlbumMixBuilder,
                            showPopularMix = showPopularMix,
                            showArtistAlbumMixBuilders = showArtistAlbumMixBuilders,
                            posterLoading = posterLoading,
                            radioStations = radioStations,
                            radioStartingIds = radioStartingIds,
                            onPlayRadioStation = onPlayRadioStation,
                            onClearDecadeMixNotice = onClearDecadeMixNotice,
                            onShowDecadeMix = onShowDecadeMix,
                        )
                    }
                    HomeSection.Collections -> item(key = "expanded-collections", contentType = "expanded-collections") {
                        ExpandedCollectionsShelf(collectionEntries, posterLoading.collectionEntry, onCollections)
                    }
                    HomeSection.Played -> item(key = "expanded-played", contentType = "expanded-played") {
                        ExpandedPlayedTables(
                            state = state,
                            onPlayTracks = onPlayTracks,
                            onAddToUpNext = onAddToUpNext,
                            onDownload = onDownload,
                            onRecentlyPlayed = onRecentlyPlayed,
                            onMostPlayed = onMostPlayed,
                        )
                    }
                    HomeSection.Random -> {
                        item(key = "expanded-random", contentType = "expanded-random") {
                            DesktopRandomPanels(
                                state = state,
                                catalogRefreshing = catalogRefreshing,
                                albumArtworkFallbacks = albumArtworkFallbacks,
                                onArtist = onArtist,
                                onAlbum = onAlbum,
                                onRefreshArtists = onRefreshArtists,
                                onRefreshAlbums = onRefreshAlbums,
                                onPrefetchArtist = {},
                                onPrefetchAlbum = {},
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun ExpandedHomeShelf(
    title: String,
    action: String? = null,
    onAction: () -> Unit = {},
    horizontalSpacing: Dp = 10.dp,
    content: LazyListScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title, action, onAction)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            content = content,
        )
    }
}

@Composable
private fun ExpandedMixesShelf(
    onPlayPersonalMix: () -> Unit,
    onPlayPopularMix: () -> Unit,
    onPlayTopTracksMix: () -> Unit,
    onArtistMixBuilder: () -> Unit,
    onAlbumMixBuilder: () -> Unit,
    showPopularMix: Boolean,
    showArtistAlbumMixBuilders: Boolean,
    posterLoading: HomePosterLoadingState,
    radioStations: List<PlexRadioStation>,
    radioStartingIds: Set<String>,
    onPlayRadioStation: (PlexRadioStation) -> Unit,
    onClearDecadeMixNotice: () -> Unit,
    onShowDecadeMix: () -> Unit,
) {
    ExpandedHomeShelf("CREATE A MIX", horizontalSpacing = 9.dp) {
        item(key = "personal-mix", contentType = "expanded-mix-action") {
            HomeMixPosterCard(
                "Personal Mix",
                PhoebeIcon.Person,
                Res.drawable.mix_personal,
                Modifier.width(MobileHomePosterCardSize),
                loading = posterLoading.personalMix,
                onClick = onPlayPersonalMix,
            )
        }
        if (showPopularMix) {
            item(key = "popular-mix", contentType = "expanded-mix-action") {
                HomeMixPosterCard(
                    "Popular",
                    PhoebeIcon.PlaylistPlay,
                    Res.drawable.mix_popular,
                    Modifier.width(MobileHomePosterCardSize),
                    loading = posterLoading.popularMix,
                    onClick = onPlayPopularMix,
                )
            }
            item(key = "top-tracks-mix", contentType = "expanded-mix-action") {
                HomeMixPosterCard(
                    "Top Tracks",
                    PhoebeIcon.PlaylistPlay,
                    Res.drawable.mix_top_tracks,
                    Modifier.width(MobileHomePosterCardSize),
                    loading = posterLoading.topTracksMix,
                    onClick = onPlayTopTracksMix,
                )
            }
        }
        item(key = "decade-mix", contentType = "expanded-mix-action") {
            HomeMixPosterCard("Decade Mix", PhoebeIcon.Calendar, Res.drawable.mix_decade, Modifier.width(MobileHomePosterCardSize)) {
                onClearDecadeMixNotice()
                onShowDecadeMix()
            }
        }
        items(radioStations, key = { "radio:${it.key}" }, contentType = { "expanded-radio-station" }) { station ->
            val starting = station.key in radioStartingIds
            val title = if (starting) "Starting..." else station.mixTitle()
            val icon = station.homeRadioIcon()
            val artwork = station.mixArtworkResource()
            if (artwork != null) {
                HomeMixPosterCard(
                    title = title,
                    icon = icon,
                    artwork = artwork,
                    modifier = Modifier.width(MobileHomePosterCardSize),
                    enabled = !starting,
                ) {
                    onPlayRadioStation(station)
                }
            } else {
                MobileActionCard(
                    title,
                    icon,
                    Modifier.width(116.dp),
                    enabled = !starting,
                ) {
                    onPlayRadioStation(station)
                }
            }
        }
        if (showArtistAlbumMixBuilders) {
            item(key = "artist-mix-builder", contentType = "expanded-mix-action") {
                HomeMixPosterCard(
                    "Artist Mix",
                    PhoebeIcon.Person,
                    Res.drawable.mix_artist_builder,
                    Modifier.width(MobileHomePosterCardSize),
                    onClick = onArtistMixBuilder,
                )
            }
            item(key = "album-mix-builder", contentType = "expanded-mix-action") {
                HomeMixPosterCard(
                    "Album Mix",
                    PhoebeIcon.Grid,
                    Res.drawable.mix_album_builder,
                    Modifier.width(MobileHomePosterCardSize),
                    onClick = onAlbumMixBuilder,
                )
            }
        }
    }
}

@Composable
private fun ExpandedCollectionsShelf(
    collectionEntries: List<HomeCollectionEntry>,
    loadingCollectionEntry: CollectionEntry?,
    onCollections: (CollectionEntry) -> Unit,
) {
    if (collectionEntries.isEmpty()) {
        HomeSectionLabel("COLLECTIONS")
        HomeEmptyState("No collections are available.")
    } else {
        ExpandedHomeShelf("COLLECTIONS", horizontalSpacing = 9.dp) {
            items(collectionEntries, key = { it.collectionEntry.toString() }, contentType = { "expanded-collection" }) { entry ->
                HomeMixPosterCard(
                    title = entry.mobileTitle,
                    icon = entry.icon,
                    artwork = entry.artwork,
                    modifier = Modifier.width(MobileHomePosterCardSize),
                    enabled = entry.collectionEntry != loadingCollectionEntry,
                    loading = entry.collectionEntry == loadingCollectionEntry,
                    titleFormatter = ::collectionPosterTitle,
                ) {
                    onCollections(entry.collectionEntry)
                }
            }
        }
    }
}

@Composable
private fun ExpandedHomeEmptyShelf(
    title: String,
    message: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title, action, onAction)
        HomeEmptyState(message)
    }
}

@Composable
private fun ExpandedFavoritePlaylistsShelf(
    playlists: List<Playlist>,
    totalCount: Int,
    onPlaylist: (Playlist) -> Unit,
    onViewAll: () -> Unit,
) {
    val playlistActions = LocalPlaylistActions.current
    val displayPlaylists = remember(playlists) {
        playlists.distinctBy { it.id }.filter { it.id.isNotBlank() }.take(10)
    }
    if (displayPlaylists.isEmpty()) {
        ExpandedHomeEmptyShelf(
            title = "FAVORITE PLAYLISTS",
            message = "Favorite playlists will appear here.",
            action = if (totalCount > 0) "View all" else null,
            onAction = onViewAll,
        )
    } else {
        ExpandedHomeShelf("FAVORITE PLAYLISTS", action = "View all", onAction = onViewAll, horizontalSpacing = 10.dp) {
            items(displayPlaylists, key = { "favorite-playlist:${it.id}" }, contentType = { "expanded-favorite-playlist" }) { playlist ->
                FavoritePlaylistTile(
                    playlist = playlist,
                    modifier = Modifier.width(214.dp),
                    onLongClick = { playlistActions.onShufflePlaylist(playlist) },
                ) {
                    onPlaylist(playlist)
                }
            }
        }
    }
}

@Composable
private fun ExpandedFavoriteArtistsShelf(
    artists: List<Artist>,
    artistThumbs: Map<String, String>,
    totalCount: Int,
    onArtist: (Artist) -> Unit,
    onViewAll: () -> Unit,
) {
    val displayArtists = remember(artists) { artists.take(10) }
    if (displayArtists.isEmpty()) {
        ExpandedHomeEmptyShelf(
            title = "FAVORITE ARTISTS",
            message = "Favorite artists will appear here.",
            action = if (totalCount > 0) "View all" else null,
            onAction = onViewAll,
        )
    } else {
        ExpandedHomeShelf("FAVORITE ARTISTS", action = "View all", onAction = onViewAll, horizontalSpacing = 12.dp) {
            items(displayArtists, key = { "favorite-artist:${it.id}" }, contentType = { "expanded-favorite-artist" }) { artist ->
                MobileArtistTile(artist, artistThumbs[artist.id], "artist:${artist.id}") {
                    onArtist(artist)
                }
            }
        }
    }
}

@Composable
private fun ExpandedFavoriteAlbumsShelf(
    albums: List<Album>,
    albumArtworkFallbacks: Map<String, String>,
    totalCount: Int,
    onAlbum: (Album) -> Unit,
    onViewAll: () -> Unit,
) {
    val displayAlbums = remember(albums) { albums.take(10) }
    if (displayAlbums.isEmpty()) {
        ExpandedHomeEmptyShelf(
            title = "FAVORITE ALBUMS",
            message = "Favorite albums will appear here.",
            action = if (totalCount > 0) "View all" else null,
            onAction = onViewAll,
        )
    } else {
        ExpandedHomeShelf("FAVORITE ALBUMS", action = "View all", onAction = onViewAll, horizontalSpacing = 10.dp) {
            items(displayAlbums, key = { "favorite-album:${it.id}" }, contentType = { "expanded-favorite-album" }) { album ->
                HomeArtworkTile(album.title, album.artist, album.thumbUrl, fallbackThumbUrl = albumArtworkFallbacks[album.id], modifier = Modifier.width(92.dp), maxDecodeDimension = 180, sharedKey = "album:${album.id}") {
                    onAlbum(album)
                }
            }
        }
    }
}

@Composable
private fun ExpandedRecentSongsShelf(
    tracks: List<Track>,
    onViewAll: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
) {
    val displayTracks = remember(tracks) { tracks.take(10) }
    if (displayTracks.isEmpty()) {
        ExpandedHomeEmptyShelf("RECENTLY ADDED SONGS", "Recently added songs will appear here.", action = "View all", onAction = onViewAll)
    } else {
        ExpandedHomeShelf("RECENTLY ADDED SONGS", action = "View all", onAction = onViewAll, horizontalSpacing = 10.dp) {
            itemsIndexed(displayTracks, key = { _, track -> "recent-song:${track.id}" }, contentType = { _, _ -> "expanded-recent-song" }) { index, track ->
                HomeArtworkTile(
                    track.title,
                    track.artist,
                    track.localArtworkUri,
                    fallbackThumbUrl = track.thumbUrl,
                    modifier = Modifier.width(92.dp),
                    maxDecodeDimension = 180,
                    sharedKey = "song:${track.id}",
                ) {
                    onPlayTracks(displayTracks, index)
                }
            }
        }
    }
}

@Composable
private fun ExpandedRecentArtistsShelf(
    artists: List<Artist>,
    artistThumbs: Map<String, String>,
    onArtist: (Artist) -> Unit,
    onViewAll: () -> Unit,
) {
    val displayArtists = remember(artists) { artists.take(10) }
    if (displayArtists.isEmpty()) {
        ExpandedHomeEmptyShelf("RECENT ARTISTS", "Recently added artists will appear here.", action = "View all", onAction = onViewAll)
    } else {
        ExpandedHomeShelf("RECENT ARTISTS", action = "View all", onAction = onViewAll, horizontalSpacing = 12.dp) {
            items(displayArtists, key = { "recent-artist:${it.id}" }, contentType = { "expanded-recent-artist" }) { artist ->
                MobileArtistTile(artist, artistThumbs[artist.id], "artist:${artist.id}") {
                    onArtist(artist)
                }
            }
        }
    }
}

@Composable
private fun ExpandedRecentAlbumsShelf(
    albums: List<Album>,
    albumArtworkFallbacks: Map<String, String>,
    onAlbum: (Album) -> Unit,
    onViewAll: () -> Unit,
) {
    val displayAlbums = remember(albums) { albums.take(10) }
    if (displayAlbums.isEmpty()) {
        ExpandedHomeEmptyShelf("RECENT ALBUMS", "Recently added albums will appear here.", action = "View all", onAction = onViewAll)
    } else {
        ExpandedHomeShelf("RECENT ALBUMS", action = "View all", onAction = onViewAll, horizontalSpacing = 10.dp) {
            items(displayAlbums, key = { "recent-album:${it.id}" }, contentType = { "expanded-recent-album" }) { album ->
                HomeArtworkTile(album.title, album.artist, album.thumbUrl, fallbackThumbUrl = albumArtworkFallbacks[album.id], modifier = Modifier.width(92.dp), maxDecodeDimension = 180, sharedKey = "album:${album.id}") {
                    onAlbum(album)
                }
            }
        }
    }
}

@Composable
private fun ExpandedRandomArtistsShelf(
    randomArtists: List<Artist>,
    artistThumbs: Map<String, String>,
    onArtist: (Artist) -> Unit,
    onRefreshArtists: () -> Unit,
) {
    if (randomArtists.isEmpty()) {
        ExpandedHomeEmptyShelf("RANDOM ARTISTS", "Add music to your library to discover artists here.", action = "Refresh", onAction = onRefreshArtists)
    } else {
        ExpandedHomeShelf(
            title = "RANDOM ARTISTS",
            action = "Refresh",
            onAction = onRefreshArtists,
            horizontalSpacing = 12.dp,
        ) {
            items(randomArtists, key = { "random-artist:${it.id}" }, contentType = { "expanded-random-artist" }) { artist ->
                MobileArtistTile(artist, artistThumbs[artist.id], "artist:${artist.id}") {
                    onArtist(artist)
                }
            }
        }
    }
}

@Composable
private fun ExpandedRandomAlbumsShelf(
    randomAlbums: List<Album>,
    albumArtworkFallbacks: Map<String, String>,
    onAlbum: (Album) -> Unit,
    onRefreshAlbums: () -> Unit,
) {
    if (randomAlbums.isEmpty()) {
        ExpandedHomeEmptyShelf("RANDOM ALBUMS", "Add music to your library to discover albums here.", action = "Refresh", onAction = onRefreshAlbums)
    } else {
        ExpandedHomeShelf(
            title = "RANDOM ALBUMS",
            action = "Refresh",
            onAction = onRefreshAlbums,
            horizontalSpacing = 10.dp,
        ) {
            items(randomAlbums, key = { "random-album:${it.id}" }, contentType = { "expanded-random-album" }) { album ->
                HomeArtworkTile(album.title, album.artist, album.thumbUrl, fallbackThumbUrl = albumArtworkFallbacks[album.id], modifier = Modifier.width(92.dp), maxDecodeDimension = 180, sharedKey = "album:${album.id}") {
                    onAlbum(album)
                }
            }
        }
    }
}

@Composable
private fun ExpandedPlayedTables(
    state: HomeUiState,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onRecentlyPlayed: () -> Unit,
    onMostPlayed: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HomePlayedTablePanel(
            title = "RECENTLY PLAYED",
            rows = state.recentlyPlayedTracks,
            onViewAll = onRecentlyPlayed,
            metricForRow = { row -> formatLastPlayed(row.lastPlayedMs, LocalNowMs.current) },
            onPlayTracks = onPlayTracks,
            onAddToUpNext = onAddToUpNext,
            onDownload = onDownload,
        )
        HomePlayedTablePanel(
            title = "MOST PLAYED",
            rows = state.mostPlayedTracks,
            onViewAll = onMostPlayed,
            metricForRow = { row -> formatHomePlayCount(row.playCount) },
            onPlayTracks = onPlayTracks,
            onAddToUpNext = onAddToUpNext,
            onDownload = onDownload,
            showResolving = LocalMostPlayedResolving.current,
        )
    }
}

@Composable
private fun HomePlayedTablePanel(
    title: String,
    rows: List<HomePlayedTrack>,
    onViewAll: () -> Unit,
    metricForRow: @Composable (HomePlayedTrack) -> String,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    showResolving: Boolean = false,
) {
    HomePanel(Modifier.fillMaxWidth()) {
        SectionHeader(title, "View all", onViewAll)
        when {
            rows.isEmpty() && showResolving -> Box(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = PhoebeUi.accentLight,
                    strokeWidth = 2.dp,
                    trackColor = PhoebeUi.progressTrack,
                )
            }
            rows.isEmpty() -> HomeEmptyState("Play history will appear here.")
            else -> {
                val tracks = rows.map { it.track }
                Column(Modifier.fillMaxWidth()) {
                    rows.take(HomePlayedPanelMaxRows).forEachIndexed { index, row ->
                        if (index > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(PhoebeUi.border),
                            )
                        }
                        HomePlayedTableRow(
                            rank = index + 1,
                            row = row,
                            metric = metricForRow(row),
                            onPlay = { onPlayTracks(tracks, index) },
                            onAddToUpNext = { onAddToUpNext(row.track) },
                            onDownload = { onDownload(row.track) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomePlayedTableRow(
    rank: Int,
    row: HomePlayedTrack,
    metric: String,
    onPlay: () -> Unit,
    onAddToUpNext: () -> Unit,
    onDownload: () -> Unit,
) {
    val track = row.track
    var menuExpanded by remember(track.id) { mutableStateOf(false) }
    val nowPlaying = LocalNowPlaying.current
    val isNowPlaying = track.id == nowPlaying.trackId
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onPlay, onLongClick = { menuExpanded = true })
            .background(if (isNowPlaying) PhoebeUi.accent.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            rank.toString(),
            color = PhoebeUi.mutedText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(20.dp),
        )
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            TrackArtworkImage(
                track,
                Modifier.fillMaxSize().sharedArtworkTransition("song:${track.id}"),
                elevated = false,
                maxDecodeDimension = 128,
            )
            if (isNowPlaying) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    NowPlayingIndicator(
                        isPlaying = nowPlaying.isPlaying,
                        isBuffering = nowPlaying.isBuffering,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                track.title,
                color = if (isNowPlaying) PhoebeUi.accentLight else PhoebeUi.primaryText,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition("song:${track.id}:title"),
            )
            Text(
                track.artist,
                color = PhoebeUi.secondaryText,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            metric,
            color = PhoebeUi.secondaryText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(66.dp),
        )
        Box {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { menuExpanded = true },
                contentAlignment = Alignment.Center,
            ) {
                PhoebeIconView(PhoebeIcon.More, tint = PhoebeUi.secondaryText, modifier = Modifier.size(16.dp))
            }
            TrackActionMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                onAddToUpNext = onAddToUpNext,
                onDownload = onDownload,
                track = track,
            )
        }
    }
}

private fun toggledPhoneHomeAccordion(
    expandedSection: PhoneHomeAccordionSection?,
    target: PhoneHomeAccordionSection,
): PhoneHomeAccordionSection? =
    if (expandedSection == target) null else target

private data class PhoneHomeAction(
    val label: String,
    val icon: PhoebeIcon,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

private data class PhoneHomePosterAction(
    val label: String,
    val icon: PhoebeIcon,
    val artwork: DrawableResource?,
    val enabled: Boolean = true,
    val loading: Boolean = false,
    val titleFormatter: (String) -> String = ::mixPosterTitle,
    val onClick: () -> Unit,
)

@Composable
private fun PhoneMixesAccordionSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    onPlayPersonalMix: () -> Unit,
    onPlayPopularMix: () -> Unit,
    onPlayTopTracksMix: () -> Unit,
    onArtistMixBuilder: () -> Unit,
    onAlbumMixBuilder: () -> Unit,
    showPopularMix: Boolean,
    showArtistAlbumMixBuilders: Boolean,
    posterLoading: HomePosterLoadingState,
    radioStations: List<PlexRadioStation>,
    radioStartingIds: Set<String>,
    onPlayRadioStation: (PlexRadioStation) -> Unit,
    onClearDecadeMixNotice: () -> Unit,
    onShowDecadeMix: () -> Unit,
) {
    val builderActions = if (showArtistAlbumMixBuilders) {
        listOf(
            PhoneHomePosterAction(
                "Artist Mix",
                PhoebeIcon.Person,
                Res.drawable.mix_artist_builder,
                onClick = onArtistMixBuilder,
            ),
            PhoneHomePosterAction(
                "Album Mix",
                PhoebeIcon.Grid,
                Res.drawable.mix_album_builder,
                onClick = onAlbumMixBuilder,
            ),
        )
    } else {
        emptyList()
    }
    val actions = buildList {
        add(PhoneHomePosterAction(
            "Personal Mix",
            PhoebeIcon.Person,
            Res.drawable.mix_personal,
            enabled = !posterLoading.personalMix,
            loading = posterLoading.personalMix,
            onClick = onPlayPersonalMix,
        ))
        if (showPopularMix) {
            add(PhoneHomePosterAction(
                "Popular",
                PhoebeIcon.PlaylistPlay,
                Res.drawable.mix_popular,
                enabled = !posterLoading.popularMix,
                loading = posterLoading.popularMix,
                onClick = onPlayPopularMix,
            ))
            add(PhoneHomePosterAction(
                "Top Tracks",
                PhoebeIcon.PlaylistPlay,
                Res.drawable.mix_top_tracks,
                enabled = !posterLoading.topTracksMix,
                loading = posterLoading.topTracksMix,
                onClick = onPlayTopTracksMix,
            ))
        }
        add(PhoneHomePosterAction(
            label = "Decade Mix",
            icon = PhoebeIcon.Calendar,
            artwork = Res.drawable.mix_decade,
            onClick = {
                onClearDecadeMixNotice()
                onShowDecadeMix()
            },
        ))
    } + radioStations.map { station ->
        val starting = station.key in radioStartingIds
        PhoneHomePosterAction(
            label = if (starting) "Starting..." else station.mixTitle(),
            icon = station.homeRadioIcon(),
            artwork = station.mixArtworkResource(),
            enabled = !starting,
            onClick = { onPlayRadioStation(station) },
        )
    } + builderActions
    PhoneHomeAccordionGroup(
        title = "Mixes",
        subtitle = optionCountLabel(actions.size),
        icon = PhoebeIcon.InterwovenArrows,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        PhoneHomePosterActionGrid(actions)
    }
}

@Composable
private fun PhoneCollectionsAccordionSection(
    collectionEntries: List<HomeCollectionEntry>,
    expanded: Boolean,
    onToggle: () -> Unit,
    loadingCollectionEntry: CollectionEntry?,
    onCollections: (CollectionEntry) -> Unit,
) {
    PhoneHomeAccordionGroup(
        title = "Collections",
        subtitle = optionCountLabel(collectionEntries.size),
        icon = PhoebeIcon.Book,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        if (collectionEntries.isEmpty()) {
            HomeEmptyState("No collections are available.")
        } else {
            PhoneHomePosterActionGrid(
                collectionEntries.map { entry ->
                    PhoneHomePosterAction(
                        label = entry.mobileTitle,
                        icon = entry.icon,
                        artwork = entry.artwork,
                        enabled = entry.collectionEntry != loadingCollectionEntry,
                        loading = entry.collectionEntry == loadingCollectionEntry,
                        titleFormatter = ::collectionPosterTitle,
                        onClick = { onCollections(entry.collectionEntry) },
                    )
                },
            )
        }
    }
}

@Composable
private fun PhoneFavoritesAccordionSection(
    subsections: List<HomeSection>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onFavoritePlaylists: () -> Unit,
    onFavoriteArtists: () -> Unit,
    onFavoriteAlbums: () -> Unit,
) {
    val actions = favoriteActionsFor(subsections, onFavoritePlaylists, onFavoriteArtists, onFavoriteAlbums)
    PhoneHomeAccordionGroup(
        title = "Favorites",
        subtitle = optionCountLabel(actions.size),
        icon = PhoebeIcon.Heart,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        if (actions.isEmpty()) {
            HomeEmptyState("No favorite sections are enabled.")
        } else {
            PhoneHomeActionGrid(actions)
        }
    }
}

@Composable
private fun PhonePlayedAccordionSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    onRecentlyPlayed: () -> Unit,
    onMostPlayed: () -> Unit,
) {
    PhoneHomeAccordionGroup(
        title = "Listening History",
        subtitle = "2 options",
        icon = PhoebeIcon.Music,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        PhoneHomeActionGrid(
            listOf(
                PhoneHomeAction("Recently Played", PhoebeIcon.Music, onClick = onRecentlyPlayed),
                PhoneHomeAction("Most Played", PhoebeIcon.PlaylistPlay, onClick = onMostPlayed),
            ),
        )
    }
}

@Composable
private fun PhoneRecentsAccordionSection(
    subsections: List<HomeSection>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRecentSongs: () -> Unit,
    onRecentArtists: () -> Unit,
    onRecentAlbums: () -> Unit,
) {
    val actions = recentActionsFor(subsections, onRecentSongs, onRecentArtists, onRecentAlbums)
    PhoneHomeAccordionGroup(
        title = "Recently Added",
        subtitle = optionCountLabel(actions.size),
        icon = PhoebeIcon.Bell,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        if (actions.isEmpty()) {
            HomeEmptyState("No recently added sections are enabled.")
        } else {
            PhoneHomeActionGrid(actions)
        }
    }
}

@Composable
private fun PhoneRandomAccordionSection(
    state: HomeUiState,
    catalogRefreshing: Boolean,
    albumArtworkFallbacks: Map<String, String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onRefreshArtists: () -> Unit,
    onRefreshAlbums: () -> Unit,
) {
    PhoneHomeAccordionGroup(
        title = "Random",
        subtitle = "Artists and albums",
        icon = PhoebeIcon.InterwovenArrows,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        DesktopRandomPanels(
            state = state,
            catalogRefreshing = catalogRefreshing,
            albumArtworkFallbacks = albumArtworkFallbacks,
            onArtist = onArtist,
            onAlbum = onAlbum,
            onRefreshArtists = onRefreshArtists,
            onRefreshAlbums = onRefreshAlbums,
            onPrefetchArtist = {},
            onPrefetchAlbum = {},
        )
    }
}

@Composable
private fun MobileRecentsShortcutsSection(
    subsections: List<HomeSection>,
    onRecentSongs: () -> Unit,
    onRecentArtists: () -> Unit,
    onRecentAlbums: () -> Unit,
) {
    val actions = recentActionsFor(subsections, onRecentSongs, onRecentArtists, onRecentAlbums)
    HomePanel(Modifier.fillMaxWidth()) {
        HomeSectionLabel("RECENTLY ADDED")
        if (actions.isEmpty()) {
            HomeEmptyState("No recently added sections are enabled.")
        } else {
            PhoneHomeActionGrid(actions)
        }
    }
}

@Composable
private fun MobileRandomSection(
    randomArtists: List<Artist>,
    randomAlbums: List<Album>,
    artistThumbs: Map<String, String>,
    albumArtworkFallbacks: Map<String, String>,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onRefreshArtists: () -> Unit,
    onRefreshAlbums: () -> Unit,
) {
    HomePanel(Modifier.fillMaxWidth()) {
        HomeSectionLabel("RANDOM")
        MobileRandomExpandedContent(
            randomArtists = randomArtists,
            randomAlbums = randomAlbums,
            artistThumbs = artistThumbs,
            albumArtworkFallbacks = albumArtworkFallbacks,
            onArtist = onArtist,
            onAlbum = onAlbum,
            onRefreshArtists = onRefreshArtists,
            onRefreshAlbums = onRefreshAlbums,
        )
    }
}

@Composable
private fun MobileRandomExpandedContent(
    randomArtists: List<Artist>,
    randomAlbums: List<Album>,
    artistThumbs: Map<String, String>,
    albumArtworkFallbacks: Map<String, String>,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onRefreshArtists: () -> Unit,
    onRefreshAlbums: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        MobileRandomArtistsList(randomArtists, artistThumbs, onArtist, onRefreshArtists)
        MobileRandomAlbumsList(randomAlbums, albumArtworkFallbacks, onAlbum, onRefreshAlbums)
    }
}

@Composable
private fun PhoneHomeAccordionGroup(
    title: String,
    subtitle: String,
    icon: PhoebeIcon,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PhoneHomeAccordionHeader(
            title = title,
            subtitle = subtitle,
            icon = icon,
            expanded = expanded,
            onClick = onToggle,
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) + fadeIn(tween(160)),
            exit = shrinkVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) + fadeOut(tween(120)),
        ) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun PhoneHomeAccordionHeader(
    title: String,
    subtitle: String,
    icon: PhoebeIcon,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeActionIcon(icon, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = PhoebeUi.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PhoebeIconView(
            if (expanded) PhoebeIcon.ChevronUp else PhoebeIcon.ChevronDown,
            tint = PhoebeUi.secondaryText,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun PhoneHomeActionGrid(actions: List<PhoneHomeAction>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        actions.chunked(2).forEach { rowActions ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                rowActions.forEach { action ->
                    MobileActionCard(
                        label = action.label,
                        icon = action.icon,
                        modifier = Modifier.weight(1f),
                        enabled = action.enabled,
                        onClick = action.onClick,
                    )
                }
                if (rowActions.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PhoneHomePosterActionGrid(actions: List<PhoneHomePosterAction>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        actions.chunked(2).forEach { rowActions ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                rowActions.forEach { action ->
                    if (action.artwork != null) {
                        HomeMixPosterCard(
                            title = action.label,
                            icon = action.icon,
                            artwork = action.artwork,
                            modifier = Modifier.weight(1f),
                            enabled = action.enabled,
                            loading = action.loading,
                            titleFormatter = action.titleFormatter,
                            onClick = action.onClick,
                        )
                    } else {
                        MobileActionCard(
                            label = action.label,
                            icon = action.icon,
                            modifier = Modifier.weight(1f),
                            enabled = action.enabled,
                            onClick = action.onClick,
                        )
                    }
                }
                if (rowActions.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private fun optionCountLabel(count: Int): String =
    if (count == 1) "1 option" else "$count options"

@Composable
private fun MobileRandomArtistsList(
    artists: List<Artist>,
    artistThumbs: Map<String, String>,
    onArtist: (Artist) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("RANDOM ARTISTS", "Refresh", onRefresh)
        if (artists.isEmpty()) {
            HomeEmptyState("Add music to your library to discover artists here.")
        } else {
            ShuffleAnimatedRow(targetKey = artists.joinToString("|") { it.id }) {
                HomeHorizontalCarousel(Modifier.fillMaxWidth(), horizontalSpacing = 12.dp) {
                    items(artists, key = { it.id }, contentType = { "mobile-random-artist" }) { artist ->
                        MobileArtistTile(
                            artist = artist,
                            thumbUrl = artistThumbs[artist.id],
                            sharedKey = "artist:${artist.id}",
                            onClick = { onArtist(artist) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileRandomAlbumsList(
    albums: List<Album>,
    albumArtworkFallbacks: Map<String, String>,
    onAlbum: (Album) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("RANDOM ALBUMS", "Refresh", onRefresh)
        if (albums.isEmpty()) {
            HomeEmptyState("Add music to your library to discover albums here.")
        } else {
            ShuffleAnimatedRow(targetKey = albums.joinToString("|") { it.id }) {
                HomeHorizontalCarousel(Modifier.fillMaxWidth()) {
                    items(albums, key = { it.id }, contentType = { "mobile-random-album" }) { album ->
                        HomeArtworkTile(
                            title = album.title,
                            subtitle = album.artist,
                            thumbUrl = album.thumbUrl,
                            fallbackThumbUrl = albumArtworkFallbacks[album.id],
                            modifier = Modifier.width(92.dp),
                            maxDecodeDimension = 180,
                            sharedKey = "album:${album.id}",
                            onClick = { onAlbum(album) },
                        )
                    }
                }
            }
        }
    }
}

private fun normalizedHomeSections(sections: List<HomeSection>): List<HomeSection> =
    sections
        .flatMap { section ->
            when (section) {
                HomeSection.Favorites -> listOf(HomeSection.FavoritePlaylists, HomeSection.FavoriteArtists, HomeSection.FavoriteAlbums)
                HomeSection.Recents -> listOf(HomeSection.RecentSongs, HomeSection.RecentArtists, HomeSection.RecentAlbums)
                else -> listOf(section)
            }
        }
        .filterNot { it == HomeSection.Favorites || it == HomeSection.Recents }
        .let { (it + HomeSection.defaultOrder).distinct() }

@Composable
private fun MobileCollectionsSection(
    collectionRows: List<List<HomeCollectionEntry>>,
    loadingCollectionEntry: CollectionEntry?,
    onCollections: (CollectionEntry) -> Unit,
) {
    HomeSectionLabel("COLLECTIONS")
    HomeHorizontalCarousel(Modifier.fillMaxWidth(), horizontalSpacing = 9.dp) {
        items(collectionRows.flatten(), key = { it.collectionEntry.toString() }, contentType = { "mobile-collection" }) { entry ->
            HomeMixPosterCard(
                title = entry.mobileTitle,
                icon = entry.icon,
                artwork = entry.artwork,
                modifier = Modifier.width(MobileHomePosterCardSize),
                enabled = entry.collectionEntry != loadingCollectionEntry,
                loading = entry.collectionEntry == loadingCollectionEntry,
                titleFormatter = ::collectionPosterTitle,
            ) {
                onCollections(entry.collectionEntry)
            }
        }
    }
}

@Composable
private fun MobileFavoritePlaylistsSection(
    playlists: List<Playlist>,
    onPlaylist: (Playlist) -> Unit,
    onViewAll: () -> Unit,
    totalCount: Int = playlists.size,
) {
    val playlistActions = LocalPlaylistActions.current
    if (playlists.isEmpty()) {
        HomeSectionLabel("FAVORITE PLAYLISTS")
        HomeEmptyState("Favorite playlists will appear here.")
    } else {
        val visiblePlaylists = remember(playlists) {
            playlists.distinctBy { it.id }.filter { it.id.isNotBlank() }.take(10)
        }
        SectionHeader("FAVORITE PLAYLISTS", if (totalCount > 10) "View all" else null, onViewAll)
        HomeHorizontalCarousel(Modifier.fillMaxWidth()) {
            items(visiblePlaylists, key = { it.id }, contentType = { "mobile-favorite-playlist" }) { playlist ->
                FavoritePlaylistTile(
                    playlist = playlist,
                    modifier = Modifier.width(240.dp),
                    onLongClick = { playlistActions.onShufflePlaylist(playlist) },
                ) {
                    onPlaylist(playlist)
                }
            }
        }
    }
}

@Composable
private fun MobileFavoriteArtistsSection(
    artists: List<Artist>,
    artistThumbs: Map<String, String>,
    onArtist: (Artist) -> Unit,
    onViewAll: () -> Unit,
    totalCount: Int = artists.size,
) {
    if (artists.isEmpty()) {
        HomeSectionLabel("FAVORITE ARTISTS")
        HomeEmptyState("Favorite artists will appear here.")
    } else {
        val visibleArtists = remember(artists) { artists.take(10) }
        SectionHeader("FAVORITE ARTISTS", if (totalCount > 10) "View all" else null, onViewAll)
        HomeHorizontalCarousel(Modifier.fillMaxWidth(), horizontalSpacing = 12.dp) {
            items(visibleArtists, key = { it.id }, contentType = { "mobile-favorite-artist" }) { artist ->
                MobileArtistTile(artist, artistThumbs[artist.id], "artist:${artist.id}") {
                    onArtist(artist)
                }
            }
        }
    }
}

@Composable
private fun MobileFavoriteAlbumsSection(
    albums: List<Album>,
    albumArtworkFallbacks: Map<String, String>,
    onAlbum: (Album) -> Unit,
    onViewAll: () -> Unit,
    totalCount: Int = albums.size,
) {
    if (albums.isEmpty()) {
        HomeSectionLabel("FAVORITE ALBUMS")
        HomeEmptyState("Favorite albums will appear here.")
    } else {
        val visibleAlbums = remember(albums) { albums.take(10) }
        SectionHeader("FAVORITE ALBUMS", if (totalCount > 10) "View all" else null, onViewAll)
        HomeHorizontalCarousel(Modifier.fillMaxWidth()) {
            items(visibleAlbums, key = { it.id }, contentType = { "mobile-favorite-album" }) { album ->
                HomeArtworkTile(album.title, album.artist, album.thumbUrl, fallbackThumbUrl = albumArtworkFallbacks[album.id], modifier = Modifier.width(92.dp), maxDecodeDimension = 180, sharedKey = "album:${album.id}") {
                    onAlbum(album)
                }
            }
        }
    }
}

@Composable
private fun MobilePlayedHistoryShortcuts(
    onRecentlyPlayed: () -> Unit,
    onMostPlayed: () -> Unit,
) {
    HomePanel(Modifier.fillMaxWidth()) {
        HomeSectionLabel("LISTENING HISTORY")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MobileActionCard("Recently Played", PhoebeIcon.Music, Modifier.weight(1f), onClick = onRecentlyPlayed)
            MobileActionCard("Most Played", PhoebeIcon.PlaylistPlay, Modifier.weight(1f), onClick = onMostPlayed)
        }
    }
}

@Composable
private fun DesktopMixesPanel(
    onPlayPersonalMix: () -> Unit,
    onPlayPopularMix: () -> Unit,
    onPlayTopTracksMix: () -> Unit,
    onArtistMixBuilder: () -> Unit,
    onAlbumMixBuilder: () -> Unit,
    showPopularMix: Boolean,
    showArtistAlbumMixBuilders: Boolean,
    posterLoading: HomePosterLoadingState,
    radioStations: List<PlexRadioStation>,
    radioStartingIds: Set<String>,
    onPlayRadioStation: (PlexRadioStation) -> Unit,
    onClearDecadeMixNotice: () -> Unit,
    panelStyle: HomePanelStyle = HomePanelStyle.Card,
    onShowDecadeMix: () -> Unit,
) {
    HomePanel(Modifier.fillMaxWidth(), style = panelStyle) {
        HomeSectionLabel("CREATE A MIX")
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(148.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("personal-mix", contentType = "mix-action") {
                HomeMixPosterCard(
                    "Personal Mix",
                    PhoebeIcon.Person,
                    Res.drawable.mix_personal,
                    Modifier.width(148.dp),
                    loading = posterLoading.personalMix,
                    onClick = onPlayPersonalMix,
                )
            }
            if (showPopularMix) {
                item("popular-mix", contentType = "mix-action") {
                    HomeMixPosterCard(
                        "Popular",
                        PhoebeIcon.PlaylistPlay,
                        Res.drawable.mix_popular,
                        Modifier.width(148.dp),
                        loading = posterLoading.popularMix,
                        onClick = onPlayPopularMix,
                    )
                }
                item("top-tracks-mix", contentType = "mix-action") {
                    HomeMixPosterCard(
                        "Top Tracks",
                        PhoebeIcon.PlaylistPlay,
                        Res.drawable.mix_top_tracks,
                        Modifier.width(148.dp),
                        loading = posterLoading.topTracksMix,
                        onClick = onPlayTopTracksMix,
                    )
                }
            }
            item("decade-mix", contentType = "mix-action") {
                HomeMixPosterCard(
                    "Decade Mix",
                    PhoebeIcon.Calendar,
                    Res.drawable.mix_decade,
                    Modifier.width(148.dp),
                ) {
                    onClearDecadeMixNotice()
                    onShowDecadeMix()
                }
            }
            items(radioStations, key = { "radio:${it.id}:${it.key}" }, contentType = { "plex-radio-station" }) { station ->
                val starting = station.key in radioStartingIds
                val title = if (starting) "Starting..." else station.mixTitle()
                val icon = station.homeRadioIcon()
                val artwork = station.mixArtworkResource()
                if (artwork != null) {
                    HomeMixPosterCard(
                        title = title,
                        icon = icon,
                        artwork = artwork,
                        modifier = Modifier.width(148.dp),
                        enabled = !starting,
                    ) {
                        onPlayRadioStation(station)
                    }
                } else {
                    HomeActionCard(
                        title = title,
                        icon = icon,
                        modifier = Modifier.width(MobileHomePosterCardSize),
                        enabled = !starting,
                    ) {
                        onPlayRadioStation(station)
                    }
                }
            }
            if (showArtistAlbumMixBuilders) {
                item("artist-mix-builder", contentType = "mix-action") {
                    HomeMixPosterCard(
                        "Artist Mix",
                        PhoebeIcon.Person,
                        Res.drawable.mix_artist_builder,
                        Modifier.width(148.dp),
                        onClick = onArtistMixBuilder,
                    )
                }
                item("album-mix-builder", contentType = "mix-action") {
                    HomeMixPosterCard(
                        "Album Mix",
                        PhoebeIcon.Grid,
                        Res.drawable.mix_album_builder,
                        Modifier.width(148.dp),
                        onClick = onAlbumMixBuilder,
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileMixesSection(
    onPlayPersonalMix: () -> Unit,
    onPlayPopularMix: () -> Unit,
    onPlayTopTracksMix: () -> Unit,
    onArtistMixBuilder: () -> Unit,
    onAlbumMixBuilder: () -> Unit,
    showPopularMix: Boolean,
    showArtistAlbumMixBuilders: Boolean,
    posterLoading: HomePosterLoadingState,
    radioStations: List<PlexRadioStation>,
    radioStartingIds: Set<String>,
    onPlayRadioStation: (PlexRadioStation) -> Unit,
    onClearDecadeMixNotice: () -> Unit,
    onShowDecadeMix: () -> Unit,
) {
    HomeSectionLabel("CREATE A MIX")
    HomeHorizontalCarousel(Modifier.fillMaxWidth(), horizontalSpacing = 9.dp) {
        item(key = "personal-mix", contentType = "mobile-mix-action") {
            HomeMixPosterCard(
                "Personal Mix",
                PhoebeIcon.Person,
                Res.drawable.mix_personal,
                Modifier.width(MobileHomePosterCardSize),
                loading = posterLoading.personalMix,
                onClick = onPlayPersonalMix,
            )
        }
        if (showPopularMix) {
            item(key = "popular-mix", contentType = "mobile-mix-action") {
                HomeMixPosterCard(
                    "Popular",
                    PhoebeIcon.PlaylistPlay,
                    Res.drawable.mix_popular,
                    Modifier.width(MobileHomePosterCardSize),
                    loading = posterLoading.popularMix,
                    onClick = onPlayPopularMix,
                )
            }
            item(key = "top-tracks-mix", contentType = "mobile-mix-action") {
                HomeMixPosterCard(
                    "Top Tracks",
                    PhoebeIcon.PlaylistPlay,
                    Res.drawable.mix_top_tracks,
                    Modifier.width(MobileHomePosterCardSize),
                    loading = posterLoading.topTracksMix,
                    onClick = onPlayTopTracksMix,
                )
            }
        }
        item(key = "decade-mix", contentType = "mobile-mix-action") {
            HomeMixPosterCard("Decade Mix", PhoebeIcon.Calendar, Res.drawable.mix_decade, Modifier.width(MobileHomePosterCardSize)) {
                onClearDecadeMixNotice()
                onShowDecadeMix()
            }
        }
        items(radioStations, key = { it.key }, contentType = { "mobile-radio-station" }) { station ->
            val starting = station.key in radioStartingIds
            val title = if (starting) "Starting..." else station.mixTitle()
            val icon = station.homeRadioIcon()
            val artwork = station.mixArtworkResource()
            if (artwork != null) {
                HomeMixPosterCard(
                    title = title,
                    icon = icon,
                    artwork = artwork,
                    modifier = Modifier.width(MobileHomePosterCardSize),
                    enabled = !starting,
                ) {
                    onPlayRadioStation(station)
                }
            } else {
                MobileActionCard(
                    title,
                    icon,
                    Modifier.width(116.dp),
                    enabled = !starting,
                ) {
                    onPlayRadioStation(station)
                }
            }
        }
        if (showArtistAlbumMixBuilders) {
            item(key = "artist-mix-builder", contentType = "mobile-mix-action") {
                HomeMixPosterCard(
                    "Artist Mix",
                    PhoebeIcon.Person,
                    Res.drawable.mix_artist_builder,
                    Modifier.width(MobileHomePosterCardSize),
                    onClick = onArtistMixBuilder,
                )
            }
            item(key = "album-mix-builder", contentType = "mobile-mix-action") {
                HomeMixPosterCard(
                    "Album Mix",
                    PhoebeIcon.Grid,
                    Res.drawable.mix_album_builder,
                    Modifier.width(MobileHomePosterCardSize),
                    onClick = onAlbumMixBuilder,
                )
            }
        }
    }
}

@Composable
private fun DesktopRecentSongsPanel(
    tracks: List<Track>,
    onRecentSongs: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    panelStyle: HomePanelStyle = HomePanelStyle.Card,
) {
    HomePanel(Modifier.fillMaxWidth(), style = panelStyle) {
        SectionHeader("RECENTLY ADDED SONGS", "See all", onRecentSongs)
        if (tracks.isEmpty()) {
            HomeEmptyState("New songs from Plex and local folders will appear here.")
        } else {
            val visibleTracks = tracks.take(10)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(visibleTracks, key = { _, track -> track.id }, contentType = { _, _ -> "desktop-recent-song" }) { index, track ->
                    HomeArtworkTile(track.title, track.artist, track.localArtworkUri, fallbackThumbUrl = track.thumbUrl, modifier = Modifier.width(112.dp), sharedKey = "song:${track.id}") {
                        onPlayTracks(visibleTracks, index)
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopRecentArtistsPanel(
    artists: List<Artist>,
    artistThumbs: Map<String, String>,
    onRecentArtists: () -> Unit,
    onArtist: (Artist) -> Unit,
    panelStyle: HomePanelStyle = HomePanelStyle.Card,
) {
    HomePanel(Modifier.fillMaxWidth(), style = panelStyle) {
        SectionHeader("RECENTLY ADDED ARTISTS", "See all", onRecentArtists)
        if (artists.isEmpty()) {
            HomeEmptyState("New artists from Plex and local folders will appear here.")
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(artists.take(10), key = { it.id }, contentType = { "desktop-recent-artist" }) { artist ->
                    MobileArtistTile(artist, artistThumbs[artist.id], "artist:${artist.id}", width = 112.dp) { onArtist(artist) }
                }
            }
        }
    }
}

@Composable
private fun DesktopRecentAlbumsPanel(
    albums: List<Album>,
    albumArtworkFallbacks: Map<String, String>,
    onRecentAlbums: () -> Unit,
    onAlbum: (Album) -> Unit,
    panelStyle: HomePanelStyle = HomePanelStyle.Card,
) {
    HomePanel(Modifier.fillMaxWidth(), style = panelStyle) {
        SectionHeader("RECENTLY ADDED ALBUMS", "See all", onRecentAlbums)
        if (albums.isEmpty()) {
            HomeEmptyState("New albums from Plex and local folders will appear here.")
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(albums.take(10), key = { it.id }, contentType = { "desktop-recent-album" }) { album ->
                    HomeArtworkTile(album.title, album.artist, album.thumbUrl, fallbackThumbUrl = albumArtworkFallbacks[album.id], modifier = Modifier.width(112.dp), sharedKey = "album:${album.id}") { onAlbum(album) }
                }
            }
        }
    }
}

@Composable
private fun DesktopFavoritePlaylistsPanel(
    playlists: List<Playlist>,
    onPlaylist: (Playlist) -> Unit,
    onViewAll: () -> Unit,
    totalCount: Int = playlists.size,
    panelStyle: HomePanelStyle = HomePanelStyle.Card,
) {
    val playlistActions = LocalPlaylistActions.current
    HomePanel(Modifier.fillMaxWidth(), style = panelStyle) {
        if (playlists.isEmpty()) {
            HomeSectionLabel("FAVORITE PLAYLISTS")
            HomeEmptyState("Favorite playlists will appear here.")
        } else {
            val displayPlaylists = remember(playlists) {
                playlists.distinctBy { it.id }.filter { it.id.isNotBlank() }.take(10)
            }
            DesktopFavoriteScrollableRow(
                title = "FAVORITE PLAYLISTS",
                showViewAll = totalCount > 10,
                onViewAll = onViewAll,
            ) {
                items(displayPlaylists, key = { it.id }, contentType = { "favorite-playlist" }) { playlist ->
                    FavoritePlaylistTile(
                        playlist = playlist,
                        modifier = Modifier.width(260.dp),
                        onLongClick = { playlistActions.onShufflePlaylist(playlist) },
                    ) {
                        onPlaylist(playlist)
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopFavoriteArtistsPanel(
    artists: List<Artist>,
    artistThumbs: Map<String, String>,
    onArtist: (Artist) -> Unit,
    onViewAll: () -> Unit,
    totalCount: Int = artists.size,
    panelStyle: HomePanelStyle = HomePanelStyle.Card,
) {
    HomePanel(Modifier.fillMaxWidth(), style = panelStyle) {
        if (artists.isEmpty()) {
            HomeSectionLabel("FAVORITE ARTISTS")
            HomeEmptyState("Favorite artists will appear here.")
        } else {
            val displayArtists = remember(artists) {
                artists.distinctBy { it.id }.filter { it.id.isNotBlank() }.take(10)
            }
            DesktopFavoriteScrollableRow(
                title = "FAVORITE ARTISTS",
                showViewAll = totalCount > 10,
                onViewAll = onViewAll,
                horizontalGap = 12.dp,
            ) {
                items(displayArtists, key = { it.id }, contentType = { "favorite-artist" }) { artist ->
                    MobileArtistTile(artist, artistThumbs[artist.id], "artist:${artist.id}", width = 112.dp) { onArtist(artist) }
                }
            }
        }
    }
}

@Composable
private fun DesktopFavoriteAlbumsPanel(
    albums: List<Album>,
    albumArtworkFallbacks: Map<String, String>,
    onAlbum: (Album) -> Unit,
    onViewAll: () -> Unit,
    totalCount: Int = albums.size,
    panelStyle: HomePanelStyle = HomePanelStyle.Card,
) {
    HomePanel(Modifier.fillMaxWidth(), style = panelStyle) {
        if (albums.isEmpty()) {
            HomeSectionLabel("FAVORITE ALBUMS")
            HomeEmptyState("Favorite albums will appear here.")
        } else {
            val displayAlbums = remember(albums) {
                albums.distinctBy { it.id }.filter { it.id.isNotBlank() }.take(10)
            }
            DesktopFavoriteScrollableRow(
                title = "FAVORITE ALBUMS",
                showViewAll = totalCount > 10,
                onViewAll = onViewAll,
            ) {
                items(displayAlbums, key = { it.id }, contentType = { "favorite-album" }) { album ->
                    HomeArtworkTile(album.title, album.artist, album.thumbUrl, fallbackThumbUrl = albumArtworkFallbacks[album.id], modifier = Modifier.width(112.dp), sharedKey = "album:${album.id}") { onAlbum(album) }
                }
            }
        }
    }
}

@Composable
private fun DesktopFavoriteScrollableRow(
    title: String,
    showViewAll: Boolean,
    onViewAll: () -> Unit,
    horizontalGap: Dp = 10.dp,
    content: LazyListScope.() -> Unit,
) {
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val canScrollPrevious by remember(rowState) {
        derivedStateOf { rowState.canScrollBackward }
    }
    val canScrollNext by remember(rowState) {
        derivedStateOf { rowState.canScrollForward }
    }
    val scrollDistance = {
        (rowState.layoutInfo.viewportSize.width * 0.86f).coerceAtLeast(240f)
    }

    FavoriteScrollHeader(
        title = title,
        action = if (showViewAll) "View all" else null,
        onAction = onViewAll,
        canScrollPrevious = canScrollPrevious,
        canScrollNext = canScrollNext,
        onPrevious = {
            scope.launch { rowState.animateScrollBy(-scrollDistance()) }
        },
        onNext = {
            scope.launch { rowState.animateScrollBy(scrollDistance()) }
        },
    )
    LazyRow(
        state = rowState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(horizontalGap),
        content = content,
    )
}

@Composable
private fun FavoritePlaylistTile(
    playlist: Playlist,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(112.dp)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtworkImage(
            playlist.title,
            playlist.thumbUrl,
            Modifier.size(56.dp),
            radius = 8.dp,
            elevated = false,
            maxDecodeDimension = ThumbnailArtworkMaxDecodeDimension,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                playlist.title,
                color = PhoebeUi.primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${playlist.trackCount} songs",
                color = PhoebeUi.secondaryText,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.secondaryText, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun DesktopPlayedPanels(
    state: HomeUiState,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onMostPlayed: () -> Unit,
    onRecentlyPlayed: () -> Unit,
    playedPanelMaxRows: Int,
    sharedKeyForPlayedTrack: (Track) -> String?,
    panelStyle: HomePanelStyle = HomePanelStyle.Card,
) {
    val panelHeight = 480.dp
    val desktopRowHeight = 72.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 820.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MostPlayedPanel(state.mostPlayedTracks, onPlayTracks, onAddToUpNext, onDownload, onMostPlayed, Modifier.fillMaxWidth(), maxRows = playedPanelMaxRows, sharedKeyForTrack = sharedKeyForPlayedTrack, panelStyle = panelStyle)
                RecentPlayedPanel(state, onPlayTracks, onAddToUpNext, onDownload, onRecentlyPlayed, Modifier.fillMaxWidth(), maxRows = playedPanelMaxRows, sharedKeyForTrack = sharedKeyForPlayedTrack, panelStyle = panelStyle)
            }
        } else {
            Row(Modifier.fillMaxWidth().height(panelHeight), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MostPlayedPanel(state.mostPlayedTracks, onPlayTracks, onAddToUpNext, onDownload, onMostPlayed, Modifier.weight(1f).fillMaxHeight(), maxRows = playedPanelMaxRows, sharedKeyForTrack = sharedKeyForPlayedTrack, rowHeight = desktopRowHeight, panelStyle = panelStyle)
                RecentPlayedPanel(state, onPlayTracks, onAddToUpNext, onDownload, onRecentlyPlayed, Modifier.weight(1f).fillMaxHeight(), maxRows = playedPanelMaxRows, sharedKeyForTrack = sharedKeyForPlayedTrack, rowHeight = desktopRowHeight, panelStyle = panelStyle)
            }
        }
    }
}

@Composable
private fun DesktopRandomPanels(
    state: HomeUiState,
    catalogRefreshing: Boolean,
    albumArtworkFallbacks: Map<String, String>,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onRefreshArtists: () -> Unit,
    onRefreshAlbums: () -> Unit,
    onPrefetchArtist: (Artist) -> Unit,
    onPrefetchAlbum: (Album) -> Unit,
    panelStyle: HomePanelStyle = HomePanelStyle.Card,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 820.dp) {
            val randomPanelHeight = 336.dp
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                RandomArtistPanel(state.randomArtists.firstOrNull(), state.randomArtistStats, catalogRefreshing, onArtist, onRefreshArtists, onPrefetchArtist, Modifier.fillMaxWidth().height(randomPanelHeight), panelStyle = panelStyle)
                RandomAlbumPanel(state.randomAlbums.firstOrNull(), state.randomAlbumStats, catalogRefreshing, albumArtworkFallbacks, onAlbum, onRefreshAlbums, onPrefetchAlbum, Modifier.fillMaxWidth().height(randomPanelHeight), panelStyle = panelStyle)
            }
        } else {
            val randomPanelHeight = 304.dp
            Row(Modifier.fillMaxWidth().height(randomPanelHeight), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RandomArtistPanel(state.randomArtists.firstOrNull(), state.randomArtistStats, catalogRefreshing, onArtist, onRefreshArtists, onPrefetchArtist, Modifier.weight(1f).fillMaxHeight(), panelStyle = panelStyle)
                RandomAlbumPanel(state.randomAlbums.firstOrNull(), state.randomAlbumStats, catalogRefreshing, albumArtworkFallbacks, onAlbum, onRefreshAlbums, onPrefetchAlbum, Modifier.weight(1f).fillMaxHeight(), panelStyle = panelStyle)
            }
        }
    }
}

private enum class HomePanelStyle {
    Card,
    Bare,
}

@Composable
private fun HomePanel(
    modifier: Modifier = Modifier,
    style: HomePanelStyle = HomePanelStyle.Card,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val panelModifier = when (style) {
        HomePanelStyle.Card -> modifier
            .clip(shape)
            .background(PhoebeUi.panel)
            .border(BorderStroke(1.dp, PhoebeUi.border), shape)
            .padding(14.dp)
        HomePanelStyle.Bare -> modifier
    }
    Column(
        panelModifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun HomeEmptyState(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            color = PhoebeUi.mutedText,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.92f),
        )
    }
}

private data class HomeCollectionEntry(
    val collectionEntry: CollectionEntry,
    val homeTitle: String,
    val homeSubtitle: String,
    val mobileTitle: String,
    val icon: PhoebeIcon,
    val artwork: DrawableResource,
)

private fun collectionEntryRows(supportedCollectionEntries: Set<CollectionEntry>): List<List<HomeCollectionEntry>> =
    collectionEntries(supportedCollectionEntries).chunked(2)

private fun collectionEntries(supportedCollectionEntries: Set<CollectionEntry>): List<HomeCollectionEntry> =
    allHomeCollectionEntries().filter { it.collectionEntry in supportedCollectionEntries }

private fun allCollectionEntries(): List<CollectionEntry> =
    allHomeCollectionEntries().map { it.collectionEntry }

private fun allHomeCollectionEntries(): List<HomeCollectionEntry> =
    listOf(
        HomeCollectionEntry(
            collectionEntry = CollectionEntry(CollectionTarget.Artists, CollectionFacet.Mood),
            homeTitle = "Artist Mood",
            homeSubtitle = "Browse artist mood tags",
            mobileTitle = "Artist Mood",
            icon = PhoebeIcon.MoodFace,
            artwork = Res.drawable.collection_artist_mood,
        ),
        HomeCollectionEntry(
            collectionEntry = CollectionEntry(CollectionTarget.Albums, CollectionFacet.Mood),
            homeTitle = "Album Mood",
            homeSubtitle = "Browse album mood tags",
            mobileTitle = "Album Mood",
            icon = PhoebeIcon.MoodFace,
            artwork = Res.drawable.collection_album_mood,
        ),
        HomeCollectionEntry(
            collectionEntry = CollectionEntry(CollectionTarget.Artists, CollectionFacet.Style),
            homeTitle = "Artist Style",
            homeSubtitle = "Browse artist style tags",
            mobileTitle = "Artist Style",
            icon = PhoebeIcon.SunglassesFace,
            artwork = Res.drawable.collection_artist_style,
        ),
        HomeCollectionEntry(
            collectionEntry = CollectionEntry(CollectionTarget.Albums, CollectionFacet.Style),
            homeTitle = "Album Style",
            homeSubtitle = "Browse album style tags",
            mobileTitle = "Album Style",
            icon = PhoebeIcon.SunglassesFace,
            artwork = Res.drawable.collection_album_style,
        ),
        HomeCollectionEntry(
            collectionEntry = CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre),
            homeTitle = "Artist Genre",
            homeSubtitle = "Browse artist genres",
            mobileTitle = "Artist Genre",
            icon = PhoebeIcon.GenreMasks,
            artwork = Res.drawable.collection_artist_genre,
        ),
        HomeCollectionEntry(
            collectionEntry = CollectionEntry(CollectionTarget.Albums, CollectionFacet.Genre),
            homeTitle = "Album Genre",
            homeSubtitle = "Browse album genres",
            mobileTitle = "Album Genre",
            icon = PhoebeIcon.GenreMasks,
            artwork = Res.drawable.collection_album_genre,
        ),
    )

@Composable
private fun DesktopCollectionsGrid(
    supportedCollectionEntries: Set<CollectionEntry>,
    loadingCollectionEntry: CollectionEntry?,
    onCollections: (CollectionEntry) -> Unit,
) {
    HomeHorizontalCarousel(Modifier.fillMaxWidth(), horizontalSpacing = 12.dp) {
        items(collectionEntries(supportedCollectionEntries), key = { it.collectionEntry.toString() }, contentType = { "desktop-collection" }) { entry ->
            HomeMixPosterCard(
                title = entry.homeTitle,
                icon = entry.icon,
                artwork = entry.artwork,
                modifier = Modifier.width(148.dp),
                enabled = entry.collectionEntry != loadingCollectionEntry,
                loading = entry.collectionEntry == loadingCollectionEntry,
                titleFormatter = ::collectionPosterTitle,
            ) {
                onCollections(entry.collectionEntry)
            }
        }
    }
}

@Composable
private fun CollectionActionTile(
    label: String,
    icon: PhoebeIcon,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = rememberHomeActionColors(icon)
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .clickable(onClick = onClick)
            .homeActionTileSurface(colors, shape)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HomeActionIcon(icon, 34.dp, tint = colors.icon)
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            color = PhoebeUi.primaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HomeMixPosterCard(
    title: String,
    icon: PhoebeIcon,
    artwork: DrawableResource,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    titleFormatter: (String) -> String = ::mixPosterTitle,
    onClick: () -> Unit,
) {
    val colors = rememberHomeActionColors(icon)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .aspectRatio(HomeMixCardAspectRatio)
            .clip(shape)
            .combinedClickable(enabled = enabled && !loading, onClick = onClick)
            .background(Color.Black)
            .border(BorderStroke(1.dp, colors.border.copy(alpha = 0.68f)), shape),
    ) {
        Image(
            painter = painterResource(artwork),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.18f),
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.86f),
                    ),
                ),
        )
        Text(
            text = if (loading) "LOADING" else titleFormatter(title),
            color = Color.White,
            fontSize = 20.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Black,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, end = 10.dp, bottom = 14.dp),
        )
    }
}

@Composable
private fun HomeActionCard(
    title: String,
    icon: PhoebeIcon,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = rememberHomeActionColors(icon)
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .homeActionTileSurface(colors, shape)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HomeActionIcon(icon, 42.dp, tint = colors.icon)
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            color = PhoebeUi.primaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MobileActionCard(
    label: String,
    icon: PhoebeIcon,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = rememberHomeActionColors(icon)
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .height(92.dp)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .homeActionTileSurface(colors, shape)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HomeActionIcon(icon, 38.dp, tint = colors.icon)
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            color = PhoebeUi.primaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            lineHeight = 14.sp,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun PlexRadioStation.mixTitle(): String {
    val normalized = title.lowercase()
    return when {
        "deep" in normalized && "cut" in normalized -> "Deep Cut Mix"
        "time" in normalized && "travel" in normalized -> "Time Travel Mix"
        else -> title
    }
}

private fun PlexRadioStation.mixArtworkResource(): DrawableResource? {
    val normalized = title.lowercase()
    return when {
        "deep" in normalized && "cut" in normalized -> Res.drawable.mix_deep_cuts
        "time" in normalized && "travel" in normalized -> Res.drawable.mix_time_travel
        "library" in normalized -> Res.drawable.mix_library
        else -> null
    }
}

private fun mixPosterTitle(title: String): String =
    when (title.lowercase()) {
        "personal mix" -> "PERSONAL\nMIX"
        "artist mix" -> "ARTIST\nMIX"
        "album mix" -> "ALBUM\nMIX"
        "popular" -> "POPULAR"
        "top tracks" -> "TOP\nTRACKS"
        "decade mix" -> "DECADE\nMIX"
        "library radio" -> "LIBRARY\nRADIO"
        "deep cut mix" -> "DEEP CUT\nMIX"
        "time travel mix" -> "TIME\nTRAVEL"
        else -> title.uppercase()
    }

private fun collectionPosterTitle(title: String): String =
    when (title.lowercase()) {
        "artist mood" -> "ARTIST\nMOOD"
        "album mood" -> "ALBUM\nMOOD"
        "artist style" -> "ARTIST\nSTYLE"
        "album style" -> "ALBUM\nSTYLE"
        "artist genre" -> "ARTIST\nGENRE"
        "album genre" -> "ALBUM\nGENRE"
        else -> title.uppercase()
    }

@Composable
private fun DecadeMixDialog(
    decades: List<Int>,
    notice: String?,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            Modifier
                .padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(min = 300.dp, max = 420.dp)
                    .heightIn(max = maxHeight)
                    .clip(RoundedCornerShape(18.dp))
                    .background(PhoebeUi.modalSurface)
                    .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(18.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Decade Mix", color = PhoebeUi.primaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (!notice.isNullOrBlank()) {
                    Text(notice, color = PhoebeUi.accentLight, fontSize = 13.sp, lineHeight = 18.sp)
                }
                if (decades.isEmpty()) {
                    Text("No decade choices are available.", color = PhoebeUi.secondaryText, fontSize = 13.sp)
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(decades, key = { it }) { decade ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSelect(decade) }
                                    .background(PhoebeUi.elevatedFill)
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${decade}s", color = PhoebeUi.primaryText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.secondaryText, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.homeActionTileSurface(
    colors: HomeActionColors,
    shape: RoundedCornerShape,
): Modifier =
    background(colors.background)
        .drawWithCache {
            val flareWidth = size.width * 0.82f
            val flareLeft = (size.width - flareWidth) / 2f
            val waveHeight = 8.dp.toPx().coerceAtMost(size.height * 0.16f)
            val bottom = size.height
            val waveTop = bottom - waveHeight
            val wavePath = Path().apply {
                moveTo(flareLeft, bottom)
                lineTo(flareLeft, waveTop + waveHeight * 0.48f)
                cubicTo(
                    flareLeft + flareWidth * 0.20f,
                    waveTop - waveHeight * 0.18f,
                    flareLeft + flareWidth * 0.34f,
                    waveTop + waveHeight * 0.88f,
                    flareLeft + flareWidth * 0.50f,
                    waveTop + waveHeight * 0.42f,
                )
                cubicTo(
                    flareLeft + flareWidth * 0.66f,
                    waveTop - waveHeight * 0.04f,
                    flareLeft + flareWidth * 0.78f,
                    waveTop + waveHeight * 0.92f,
                    flareLeft + flareWidth,
                    waveTop + waveHeight * 0.36f,
                )
                lineTo(flareLeft + flareWidth, bottom)
                close()
            }
            onDrawBehind {
                val glowHeight = 16.dp.toPx().coerceAtMost(size.height * 0.25f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, colors.flare.copy(alpha = 0.08f)),
                        startY = size.height - glowHeight,
                        endY = size.height,
                    ),
                    topLeft = Offset(flareLeft, size.height - glowHeight),
                    size = Size(flareWidth, glowHeight),
                )
                drawPath(
                    path = wavePath,
                    color = colors.flare.copy(alpha = 0.16f),
                )
            }
        }
        .border(BorderStroke(1.dp, colors.border), shape)

@Composable
private fun HomeActionIcon(
    icon: PhoebeIcon,
    size: androidx.compose.ui.unit.Dp,
    tint: Color? = null,
    modifier: Modifier = Modifier,
) {
    val lightMode = LocalPhoebePalette.current.canvasBackground.luminance() > 0.5f
    val palette = remember(icon, lightMode) { homeIconPalette(icon, lightMode) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        PhoebeIconView(icon, tint = tint ?: palette.foreground, modifier = Modifier.size(size * 0.84f))
    }
}

@Composable
private fun rememberHomeActionColors(icon: PhoebeIcon): HomeActionColors {
    val lightMode = LocalPhoebePalette.current.canvasBackground.luminance() > 0.5f
    val palette = remember(icon, lightMode) { homeIconPalette(icon, lightMode) }
    return remember(palette, lightMode) {
        HomeActionColors(
            background = palette.first.copy(alpha = if (lightMode) 0.010f else 0.018f),
            border = palette.first.copy(alpha = if (lightMode) 0.040f else 0.055f),
            flare = palette.first,
            icon = if (lightMode) {
                palette.first.copy(alpha = 0.58f)
            } else {
                palette.foreground.copy(alpha = 0.76f)
            },
        )
    }
}

private data class HomeActionColors(
    val background: Color,
    val border: Color,
    val flare: Color,
    val icon: Color,
)

private data class HomeIconPalette(
    val first: Color,
    val second: Color,
    val foreground: Color,
)

private fun homeIconPalette(icon: PhoebeIcon, lightMode: Boolean): HomeIconPalette = when (icon) {
    PhoebeIcon.Person -> HomeIconPalette(Color(0xFF8B5CF6), Color(0xFF22D3EE), if (lightMode) Color(0xFF5B21B6) else Color(0xFFDDE7FF))
    PhoebeIcon.Calendar -> HomeIconPalette(Color(0xFFFF4D7D), Color(0xFFFFC857), if (lightMode) Color(0xFFBE123C) else Color(0xFFFFE4ED))
    PhoebeIcon.Book -> HomeIconPalette(Color(0xFF14B8A6), Color(0xFF7C3AED), if (lightMode) Color(0xFF0F766E) else Color(0xFFD8FFF8))
    PhoebeIcon.Knife -> HomeIconPalette(Color(0xFFFF6B35), Color(0xFFEF4444), if (lightMode) Color(0xFFC2410C) else Color(0xFFFFECE3))
    PhoebeIcon.InterwovenArrows -> HomeIconPalette(Color(0xFF3B82F6), Color(0xFFA3E635), if (lightMode) Color(0xFF1D4ED8) else Color(0xFFE5F0FF))
    PhoebeIcon.MoodFace -> HomeIconPalette(Color(0xFFF97316), Color(0xFFFACC15), if (lightMode) Color(0xFFC2410C) else Color(0xFFFFF2D6))
    PhoebeIcon.SunglassesFace -> HomeIconPalette(Color(0xFF06B6D4), Color(0xFFA855F7), if (lightMode) Color(0xFF0E7490) else Color(0xFFE3FAFF))
    PhoebeIcon.GenreMasks -> HomeIconPalette(Color(0xFFFF3D6E), Color(0xFFFACC15), if (lightMode) Color(0xFFBE123C) else Color(0xFFFFE1EA))
    PhoebeIcon.Heart -> HomeIconPalette(Color(0xFFFB7185), Color(0xFFBE123C), if (lightMode) Color(0xFFBE123C) else Color(0xFFFFDCE5))
    PhoebeIcon.Music -> HomeIconPalette(Color(0xFF2563EB), Color(0xFF06B6D4), if (lightMode) Color(0xFF1D4ED8) else Color.White)
    PhoebeIcon.PlaylistPlay -> HomeIconPalette(Color(0xFF0F766E), Color(0xFF22C55E), if (lightMode) Color(0xFF115E59) else Color.White)
    PhoebeIcon.Bell -> HomeIconPalette(Color(0xFFF97316), Color(0xFFFACC15), if (lightMode) Color(0xFFC2410C) else Color.White)
    PhoebeIcon.Grid -> HomeIconPalette(Color(0xFF14B8A6), Color(0xFF7C3AED), if (lightMode) Color(0xFF0F766E) else Color.White)
    else -> HomeIconPalette(PhoebePaletteDark.accentLight, Color(0xFF5EEAD4), if (lightMode) PhoebePaletteLight.accent else PhoebePaletteDark.accentLight)
}

private fun PlexRadioStation.homeRadioIcon(): PhoebeIcon {
    val normalized = title.lowercase()
    return when {
        "deep" in normalized && "cut" in normalized -> PhoebeIcon.Knife
        "time" in normalized && "travel" in normalized -> PhoebeIcon.Calendar
        "random" in normalized && "album" in normalized -> PhoebeIcon.InterwovenArrows
        "library" in normalized -> PhoebeIcon.Book
        else -> PhoebeIcon.Play
    }
}

@Composable
private fun RecentPlayedPanel(
    state: HomeUiState,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
    maxRows: Int = 4,
    sharedKeyForTrack: (Track) -> String? = { track -> "song:${track.id}" },
    showFavoriteAction: Boolean = true,
    rowHeight: Dp = 88.dp,
    panelStyle: HomePanelStyle = HomePanelStyle.Card,
) {
    HomePanel(modifier, style = panelStyle) {
        SectionHeader("RECENTLY PLAYED", "View all", onViewAll)
        if (state.recentlyPlayedTracks.isEmpty()) {
            HomeEmptyState("Nothing here yet. Play something and your recent listening history will appear.")
        } else {
            val tracks = state.recentlyPlayedTracks.map { it.track }
            state.recentlyPlayedTracks.take(maxRows).forEachIndexed { index, row ->
                HomePlayedTrackRow(
                    track = row.track,
                    onPlay = { onPlayTracks(tracks, index) },
                    onAddToUpNext = { onAddToUpNext(row.track) },
                    onDownload = { onDownload(row.track) },
                    sharedKey = sharedKeyForTrack(row.track),
                    showFavoriteAction = showFavoriteAction,
                    rowHeight = rowHeight,
                )
            }
        }
    }
}

@Composable
private fun ShuffleAnimatedRow(targetKey: String, content: @Composable () -> Unit) {
    AnimatedContent(
        targetState = targetKey,
        transitionSpec = {
            (slideInHorizontally(tween(220)) { it / 5 } + fadeIn(tween(160))) togetherWith
                (slideOutHorizontally(tween(180)) { -it / 6 } + fadeOut(tween(120)))
        },
        label = "shuffle-row",
    ) {
        content()
    }
}

@Composable
private fun MobileArtistTile(
    artist: Artist,
    thumbUrl: String?,
    sharedKey: String? = null,
    modifier: Modifier = Modifier,
    width: Dp = 92.dp,
    artworkSize: Dp = width,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .width(width)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ArtworkImage(
            artist.title,
            thumbUrl,
            Modifier
                .size(artworkSize)
                .sharedArtworkTransition(sharedKey)
                .clip(CircleShape)
                .homeArtistArtworkBorder(PhoebeUi.primaryText),
            radius = artworkSize / 2f,
            elevated = false,
            maxDecodeDimension = 160,
        )
        Text(
            artist.title,
            color = PhoebeUi.primaryText,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().sharedBoundsTransition(sharedKey?.let { "$it:title" }),
        )
    }
}

@Composable
private fun RandomArtistPanel(
    artist: Artist?,
    stats: HomeFeaturedArtistStats?,
    catalogRefreshing: Boolean,
    onArtist: (Artist) -> Unit,
    onRefresh: () -> Unit,
    onPrefetch: (Artist) -> Unit,
    modifier: Modifier = Modifier,
    panelStyle: HomePanelStyle = HomePanelStyle.Card,
) {
    val homeTracksReady = LocalHomeTrackSectionsReady.current
    LaunchedEffect(artist?.id, homeTracksReady) {
        if (!homeTracksReady) return@LaunchedEffect
        artist?.let(onPrefetch)
    }
    HomePanel(modifier, style = panelStyle) {
        RandomPanelHeader("Random artist", onRefresh)
        if (artist == null) {
            HomeEmptyState("Add music to your library to discover artists here.")
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                FeaturedArtistCard(
                    artist = artist,
                    stats = stats?.takeIf { it.artistId == artist.id },
                    catalogRefreshing = catalogRefreshing,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onArtist(artist) },
                )
            }
        }
    }
}

@Composable
private fun RandomAlbumPanel(
    album: Album?,
    stats: HomeFeaturedAlbumStats?,
    catalogRefreshing: Boolean,
    albumArtworkFallbacks: Map<String, String>,
    onAlbum: (Album) -> Unit,
    onRefresh: () -> Unit,
    onPrefetch: (Album) -> Unit,
    modifier: Modifier = Modifier,
    panelStyle: HomePanelStyle = HomePanelStyle.Card,
) {
    val homeTracksReady = LocalHomeTrackSectionsReady.current
    LaunchedEffect(album?.id, homeTracksReady) {
        if (!homeTracksReady) return@LaunchedEffect
        album?.let(onPrefetch)
    }
    HomePanel(modifier, style = panelStyle) {
        RandomPanelHeader("Random album", onRefresh)
        if (album == null) {
            HomeEmptyState("Add music to your library to discover albums here.")
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                FeaturedAlbumCard(
                    album = album,
                    stats = stats?.takeIf { it.albumId == album.id },
                    catalogRefreshing = catalogRefreshing,
                    fallbackThumbUrl = albumArtworkFallbacks[album.id],
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onAlbum(album) },
                )
            }
        }
    }
}

@Composable
private fun RandomPanelHeader(
    title: String,
    onRefresh: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PhoebeUi.elevatedFill),
                contentAlignment = Alignment.Center,
            ) {
                PhoebeIconView(PhoebeIcon.InterwovenArrows, tint = PhoebeUi.accentLight, modifier = Modifier.size(14.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, color = PhoebeUi.primaryText, fontSize = 13.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("A fresh pick from your library", color = PhoebeUi.mutedText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(onClick = onRefresh)
                .background(PhoebeUi.subtleFill)
                .border(BorderStroke(1.dp, PhoebeUi.border), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            PhoebeIconView(PhoebeIcon.Repeat, tint = PhoebeUi.secondaryText, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun FeaturedArtistCard(
    artist: Artist,
    stats: HomeFeaturedArtistStats?,
    catalogRefreshing: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val syncState = LocalCatalogSyncState.current
    val nowMs = LocalNowMs.current
    val albumsLoading = homeArtistAlbumCountLoading(stats, syncState)
    val trackStatsLoading = homeArtistTrackStatsLoading(stats, syncState, catalogRefreshing)
    val artistThumbUrl = stats?.artworkUrl ?: artist.thumbUrl
    val albumCount = stats?.albumCount ?: artist.albumCount
    val trackCount = stats?.trackCount ?: artist.songCount
    val genre = stats?.genre.takeUnless { trackStatsLoading }
    val listenersLabel = remember(stats?.lastPlayedMs, nowMs) {
        stats?.lastPlayedMs?.let { formatLastPlayed(it, nowMs) } ?: "New"
    }
    val artworkSize = 96.dp
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ArtworkImage(
                artist.title,
                artistThumbUrl,
                Modifier
                    .size(artworkSize)
                    .sharedArtworkTransition("artist:${artist.id}")
                    .clip(CircleShape)
                    .homeArtistArtworkBorder(PhoebeUi.primaryText),
                radius = artworkSize / 2f,
                elevated = false,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                AutoScrollingText(
                    text = artist.title,
                    color = PhoebeUi.primaryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.sharedBoundsTransition("artist:${artist.id}:title"),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RandomMetric("Last played", listenersLabel, Modifier.weight(1f))
            RandomMetric("Albums", albumCount.toString(), Modifier.weight(1f), loading = albumsLoading)
            RandomMetric("Tracks", trackCount.toString(), Modifier.weight(1f), loading = trackStatsLoading)
            RandomMetric("Genre", genre ?: "Mixed", Modifier.weight(1.35f), loading = trackStatsLoading)
        }
        RandomExploreButton("Explore Artist", onClick)
    }
}

@Composable
private fun RandomMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = PhoebeUi.mutedText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (loading) {
            HomeStatLoadingBar(Modifier.fillMaxWidth().height(11.dp))
        } else {
            AutoScrollingText(
                text = value,
                color = PhoebeUi.primaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun RandomExploreButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(PhoebeUi.elevatedFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = PhoebeUi.accentLight, fontSize = 13.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

@Composable
private fun HomeArtistStat(
    value: String,
    label: String,
    icon: PhoebeIcon,
    loading: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(PhoebeUi.elevatedFill),
            contentAlignment = Alignment.Center,
        ) {
            PhoebeIconView(icon, tint = PhoebeUi.accentLight, modifier = Modifier.size(12.dp))
        }
        Column(Modifier.weight(1f)) {
            if (loading) {
                HomeStatLoadingBar(Modifier.width(88.dp))
            } else {
                Text(value, color = PhoebeUi.primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(label, color = PhoebeUi.mutedText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HomeStatLoadingBar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "home-stat-loading")
    val alpha by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.62f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "home-stat-loading-alpha",
    )
    Box(
        modifier
            .height(10.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(PhoebeUi.elevatedFill.copy(alpha = alpha)),
    )
}

private fun homeArtistAlbumCountLoading(
    stats: HomeFeaturedArtistStats?,
    sync: CatalogSyncState,
): Boolean =
    stats?.hasAlbums != true && sync.isActive && sync.phase <= CatalogSyncPhase.LoadingLibrary

private fun homeArtistTrackStatsLoading(
    stats: HomeFeaturedArtistStats?,
    sync: CatalogSyncState,
    catalogRefreshing: Boolean,
): Boolean {
    if (stats?.hasTracks == true) return false
    if (stats?.hasAlbums != true) {
        return sync.isActive &&
            (sync.phase == CatalogSyncPhase.LoadingSongs || sync.phase == CatalogSyncPhase.LoadingLibrary)
    }
    if (!stats.hasPendingTrackStats) return false
    return (sync.isActive && sync.phase == CatalogSyncPhase.LoadingSongs) || catalogRefreshing
}

private fun homeAlbumTrackStatsLoading(
    stats: HomeFeaturedAlbumStats?,
    sync: CatalogSyncState,
    catalogRefreshing: Boolean,
): Boolean =
    stats?.tracksLoaded != true &&
        ((sync.isActive && sync.phase == CatalogSyncPhase.LoadingSongs) || catalogRefreshing)

@Composable
private fun FeaturedAlbumCard(
    album: Album,
    stats: HomeFeaturedAlbumStats?,
    catalogRefreshing: Boolean,
    fallbackThumbUrl: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val syncState = LocalCatalogSyncState.current
    val trackStatsLoading = homeAlbumTrackStatsLoading(stats, syncState, catalogRefreshing)
    val genre = stats?.genre.takeUnless { trackStatsLoading }
    val duration = stats?.totalDurationMs ?: 0L
    val trackCount = stats?.trackCount ?: 0
    val songWord = if (trackCount == 1) "song" else "songs"

    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ArtworkImage(
                album.title,
                album.thumbUrl,
                Modifier.size(96.dp).sharedArtworkTransition("album:${album.id}"),
                radius = 10.dp,
                elevated = false,
                fallbackThumbUrl = fallbackThumbUrl,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                AutoScrollingText(
                    text = album.title,
                    color = PhoebeUi.primaryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.sharedBoundsTransition("album:${album.id}:title"),
                )
                AutoScrollingText(
                    text = album.artist,
                    color = PhoebeUi.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.sharedBoundsTransition("album:${album.id}:subtitle"),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RandomMetric("Released", album.year?.toString() ?: "Unknown", Modifier.weight(1f))
            RandomMetric("Tracks", "$trackCount $songWord", Modifier.weight(1f), loading = trackStatsLoading)
            RandomMetric("Length", formatHoursMinutes(duration), Modifier.weight(1f), loading = trackStatsLoading)
            RandomMetric("Genre", genre ?: "Mixed", Modifier.weight(1.2f), loading = trackStatsLoading)
        }
        RandomExploreButton("Explore Album", onClick)
    }
}

@Composable
private fun MostPlayedPanel(
    rows: List<HomePlayedTrack>,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
    maxRows: Int = 4,
    sharedKeyForTrack: (Track) -> String? = { track -> "song:${track.id}" },
    showFavoriteAction: Boolean = true,
    rowHeight: Dp = 88.dp,
    maxDecodeDimension: Int = ListArtworkMaxDecodeDimension,
    panelStyle: HomePanelStyle = HomePanelStyle.Card,
) {
    val mostPlayedResolving = LocalMostPlayedResolving.current
    HomePanel(modifier, style = panelStyle) {
        SectionHeader("MOST PLAYED", "View all", onViewAll)
        if (rows.isEmpty()) {
            if (mostPlayedResolving) {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = PhoebeUi.accentLight,
                        strokeWidth = 2.dp,
                        trackColor = PhoebeUi.progressTrack,
                    )
                }
            } else {
                HomeEmptyState("Your most-played tracks will appear here after you've listened for a while.")
            }
        } else {
            val tracks = rows.map { it.track }
            rows.take(maxRows).forEachIndexed { index, row ->
                HomePlayedTrackRow(
                    track = row.track,
                    playCount = row.playCount,
                    onPlay = { onPlayTracks(tracks, index) },
                    onAddToUpNext = { onAddToUpNext(row.track) },
                    onDownload = { onDownload(row.track) },
                    sharedKey = sharedKeyForTrack(row.track),
                    showFavoriteAction = showFavoriteAction,
                    rowHeight = rowHeight,
                    maxDecodeDimension = maxDecodeDimension,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HomeSectionLabel(title)
        Spacer(Modifier.weight(1f))
        if (action != null) {
            Text(
                action,
                color = PhoebeUi.secondaryText,
                fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onAction).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun FavoritePagerHeader(
    title: String,
    page: Int,
    pageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HomeSectionLabel(title)
        Spacer(Modifier.weight(1f))
        if (action != null) {
            Text(
                action,
                color = PhoebeUi.secondaryText,
                fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            "${page + 1}/$pageCount",
            color = PhoebeUi.mutedText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        PageIconButton(PhoebeIcon.Previous, enabled = page > 0, onClick = onPrevious)
        PageIconButton(PhoebeIcon.Next, enabled = page < pageCount - 1, onClick = onNext)
    }
}

@Composable
private fun FavoriteScrollHeader(
    title: String,
    action: String?,
    onAction: () -> Unit,
    canScrollPrevious: Boolean,
    canScrollNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HomeSectionLabel(title)
        Spacer(Modifier.weight(1f))
        if (action != null) {
            Text(
                action,
                color = PhoebeUi.secondaryText,
                fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        PageIconButton(PhoebeIcon.Previous, enabled = canScrollPrevious, onClick = onPrevious)
        PageIconButton(PhoebeIcon.Next, enabled = canScrollNext, onClick = onNext)
    }
}

@Composable
private fun FavoritePageContent(
    page: Int,
    content: @Composable (Int) -> Unit,
) {
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState > initialState
            val enter = slideInHorizontally(animationSpec = tween(240)) { width ->
                if (forward) width else -width
            } + fadeIn(animationSpec = tween(160))
            val exit = slideOutHorizontally(animationSpec = tween(220)) { width ->
                if (forward) -width else width
            } + fadeOut(animationSpec = tween(120))
            enter togetherWith exit
        },
        label = "favorite-page",
    ) { targetPage ->
        content(targetPage)
    }
}

@Composable
private fun PageIconButton(icon: PhoebeIcon, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        PhoebeIconView(
            icon,
            tint = if (enabled) PhoebeUi.secondaryText else PhoebeUi.mutedText.copy(alpha = 0.35f),
            modifier = Modifier.size(15.dp),
        )
    }
}

private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
    if (pageSize <= 0 || isEmpty()) return emptyList()
    val start = (page.coerceAtLeast(0) * pageSize).coerceAtMost(size)
    val end = (start + pageSize).coerceAtMost(size)
    return subList(start, end)
}

private fun List<*>.pageCount(pageSize: Int): Int =
    if (isEmpty() || pageSize <= 0) 0 else (size + pageSize - 1) / pageSize

@Composable
private fun HomePlayedTrackRow(
    track: Track,
    playCount: Long? = null,
    sharedKey: String? = "song:${track.id}",
    onPlay: () -> Unit,
    onAddToUpNext: () -> Unit,
    onDownload: () -> Unit,
    showFavoriteAction: Boolean = true,
    rowHeight: Dp = 88.dp,
    maxDecodeDimension: Int = ListArtworkMaxDecodeDimension,
) {
    var menuExpanded by remember(track.id) { mutableStateOf(false) }
    val nowPlaying = LocalNowPlaying.current
    val likeActions = LocalLikeActions.current
    val downloads = LocalDownloadStatus.current
    val isNowPlaying = track.id == nowPlaying.trackId
    val canLike = likeActions.likesEnabled && track.canTogglePlexLike()
    val liked = likeActions.isLiked(track)
    val downloaded = downloads.isComplete(track)
    val verticalPadding = if (rowHeight < 78.dp) 8.dp else 11.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onPlay, onLongClick = { menuExpanded = true })
            .background(
                if (isNowPlaying) PhoebeUi.accent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.045f),
            )
            .padding(horizontal = 12.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val artworkSize = if (rowHeight < 84.dp) 44.dp else 48.dp
        Box(Modifier.size(artworkSize), contentAlignment = Alignment.Center) {
            TrackArtworkImage(
                track,
                Modifier.fillMaxSize().sharedArtworkTransition(sharedKey),
                elevated = false,
                maxDecodeDimension = maxDecodeDimension,
            )
            if (isNowPlaying) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    NowPlayingIndicator(
                        isPlaying = nowPlaying.isPlaying,
                        isBuffering = nowPlaying.isBuffering,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                track.title,
                color = if (isNowPlaying) PhoebeUi.accentLight else PhoebeUi.primaryText,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition(sharedKey?.let { "$it:title" }),
            )
            Text(
                track.artist,
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val showInlineLiked = !showFavoriteAction && liked
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    track.album.takeIf { it.isNotBlank() } ?: "Unknown album",
                    color = PhoebeUi.mutedText,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TrackStateBadges(
                    liked = showInlineLiked,
                    downloaded = downloaded,
                    iconSize = 10.dp,
                )
            }
        }
        playCount?.let { count ->
            Text(
                formatHomePlayCount(count),
                color = PhoebeUi.secondaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.width(56.dp),
            )
        }
        if (showFavoriteAction) {
            LikeButton(
                liked = liked,
                enabled = canLike,
                onClick = { likeActions.onToggleLiked(track) },
                modifier = Modifier.size(34.dp),
            )
        }
        Box {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable { menuExpanded = true },
                contentAlignment = Alignment.Center,
            ) {
                PhoebeIconView(PhoebeIcon.More, tint = PhoebeUi.secondaryText, modifier = Modifier.size(17.dp))
            }
            TrackActionMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                onAddToUpNext = onAddToUpNext,
                onDownload = onDownload,
                track = track,
            )
        }
    }
}

private fun formatHomePlayCount(playCount: Long): String {
    val playWord = if (playCount == 1L) "play" else "plays"
    return "$playCount $playWord"
}

@Composable
private fun HomeArtworkTile(
    title: String,
    subtitle: String,
    thumbUrl: String?,
    fallbackThumbUrl: String? = null,
    modifier: Modifier = Modifier,
    maxDecodeDimension: Int = 256,
    sharedKey: String? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = {}),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ArtworkImage(
            title,
            thumbUrl,
            Modifier.fillMaxWidth().aspectRatio(1f).sharedArtworkTransition(sharedKey),
            radius = 7.dp,
            elevated = false,
            maxDecodeDimension = maxDecodeDimension,
            fallbackThumbUrl = fallbackThumbUrl,
        )
        Text(
            title,
            color = PhoebeUi.primaryText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.sharedBoundsTransition(sharedKey?.let { "$it:title" }),
        )
        Text(
            subtitle,
            color = PhoebeUi.secondaryText,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.sharedBoundsTransition(sharedKey?.let { "$it:subtitle" }),
        )
    }
}
