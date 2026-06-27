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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

private val PhoneHomeAccordionBreakpoint: Dp = 600.dp

@Immutable
data class MobileHomeRouteState(
    val homeUiState: HomeUiState,
    val catalogRefreshing: Boolean,
    val homeSections: List<HomeSection>,
    val supportedCollectionEntries: Set<CollectionEntry>,
    val radioStations: List<PlexRadioStation>,
    val radioStartingIds: Set<String>,
    val decadeMixNotice: String?,
    val homeScreenLayoutMode: HomeScreenLayoutMode = HomeScreenLayoutMode.Default,
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
        onPlayPersonalMix = callbacks.onPlayPersonalMix,
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
    onPlayPersonalMix: () -> Unit = {},
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    homeSections: List<HomeSection> = HomeSection.defaultOrder,
    supportedCollectionEntries: Set<CollectionEntry> = allCollectionEntries().toSet(),
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
    val playedPanelMaxRows = 4
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
                    DesktopMixesPanel(onPlayPersonalMix, radioStations, radioStartingIds, onPlayRadioStation, onClearDecadeMixNotice) {
                        showDecadeMix = true
                    }
                }
                HomeSection.Collections -> item("collections") {
                    HomePanel(Modifier.fillMaxWidth()) {
                        SectionLabel("COLLECTIONS", PhoebeUi.mutedText)
                        DesktopCollectionsGrid(supportedCollectionEntries, onCollections)
                    }
                }
                HomeSection.Favorites -> {
                    item("favorite-playlists") {
                        DesktopFavoritePlaylistsPanel(
                            state.favoritePlaylists,
                            onPlaylist,
                            onFavoritePlaylists,
                            totalCount = state.favoritePlaylistCount,
                        )
                    }
                    item("favorite-artists") { DesktopFavoriteArtistsPanel(state.favoriteArtists, artistThumbs, onArtist, onFavoriteArtists, totalCount = state.favoriteArtistCount) }
                    item("favorite-albums") { DesktopFavoriteAlbumsPanel(state.favoriteAlbums, albumArtworkFallbacks, onAlbum, onFavoriteAlbums, totalCount = state.favoriteAlbumCount) }
                }
                HomeSection.FavoritePlaylists -> item("favorite-playlists") {
                    DesktopFavoritePlaylistsPanel(
                        state.favoritePlaylists,
                        onPlaylist,
                        onFavoritePlaylists,
                        totalCount = state.favoritePlaylistCount,
                    )
                }
                HomeSection.FavoriteArtists -> item("favorite-artists") { DesktopFavoriteArtistsPanel(state.favoriteArtists, artistThumbs, onArtist, onFavoriteArtists, totalCount = state.favoriteArtistCount) }
                HomeSection.FavoriteAlbums -> item("favorite-albums") { DesktopFavoriteAlbumsPanel(state.favoriteAlbums, albumArtworkFallbacks, onAlbum, onFavoriteAlbums, totalCount = state.favoriteAlbumCount) }
                HomeSection.Recents -> {
                    item("recent-songs") { DesktopRecentSongsPanel(state.recentlyAddedTracks, onRecentSongs, onPlayTracks) }
                    item("recent-artists") { DesktopRecentArtistsPanel(state.recentlyAddedArtists, artistThumbs, onRecentArtists, onArtist) }
                    item("recent-albums") { DesktopRecentAlbumsPanel(state.recentlyAddedAlbums, albumArtworkFallbacks, onRecentAlbums, onAlbum) }
                }
                HomeSection.RecentSongs -> item("recent-songs") { DesktopRecentSongsPanel(state.recentlyAddedTracks, onRecentSongs, onPlayTracks) }
                HomeSection.RecentArtists -> item("recent-artists") { DesktopRecentArtistsPanel(state.recentlyAddedArtists, artistThumbs, onRecentArtists, onArtist) }
                HomeSection.RecentAlbums -> item("recent-albums") { DesktopRecentAlbumsPanel(state.recentlyAddedAlbums, albumArtworkFallbacks, onRecentAlbums, onAlbum) }
                HomeSection.Played -> item("played") {
                    DesktopPlayedPanels(state, onPlayTracks, onAddToUpNext, onDownload, onMostPlayed, onRecentlyPlayed, playedPanelMaxRows, sharedKeyForPlayedTrack)
                }
                HomeSection.Random -> item("random") {
                    DesktopRandomPanels(state, catalogRefreshing, albumArtworkFallbacks, onArtist, onAlbum, onRefreshArtists, onRefreshAlbums, onPrefetchArtist, onPrefetchAlbum)
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
    onPlayPersonalMix: () -> Unit = {},
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
                onPlayPersonalMix = onPlayPersonalMix,
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
                onPlayPersonalMix = onPlayPersonalMix,
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
    onPlayPersonalMix: () -> Unit,
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
                            radioStations = radioStations,
                            radioStartingIds = radioStartingIds,
                            onPlayRadioStation = onPlayRadioStation,
                            onClearDecadeMixNotice = onClearDecadeMixNotice,
                            onShowDecadeMix = onShowDecadeMix,
                        )
                    } else {
                        MobileMixesSection(onPlayPersonalMix, radioStations, radioStartingIds, onPlayRadioStation, onClearDecadeMixNotice, onShowDecadeMix)
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
                            onCollections = onCollections,
                        )
                    } else {
                        MobileCollectionsSection(collectionRows, onCollections)
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
                    val randomArtists = remember(state.randomArtists) { state.randomArtists.take(10) }
                    val randomAlbums = remember(state.randomAlbums) { state.randomAlbums.take(10) }
                    if (usePhoneAccordions) {
                        PhoneRandomAccordionSection(
                            randomArtists = randomArtists,
                            randomAlbums = randomAlbums,
                            artistThumbs = artistThumbs,
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
                        MobileRandomSection(
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
    onPlayPersonalMix: () -> Unit,
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
                            radioStations = radioStations,
                            radioStartingIds = radioStartingIds,
                            onPlayRadioStation = onPlayRadioStation,
                            onClearDecadeMixNotice = onClearDecadeMixNotice,
                            onShowDecadeMix = onShowDecadeMix,
                        )
                    }
                    HomeSection.Collections -> item(key = "expanded-collections", contentType = "expanded-collections") {
                        ExpandedCollectionsShelf(collectionEntries, onCollections)
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
                        item(key = "expanded-random-artists", contentType = "expanded-random-artists") {
                            ExpandedRandomArtistsShelf(
                                randomArtists = remember(state.randomArtists) { state.randomArtists.take(10) },
                                artistThumbs = artistThumbs,
                                onArtist = onArtist,
                                onRefreshArtists = onRefreshArtists,
                            )
                        }
                        item(key = "expanded-random-albums", contentType = "expanded-random-albums") {
                            ExpandedRandomAlbumsShelf(
                                randomAlbums = remember(state.randomAlbums) { state.randomAlbums.take(10) },
                                albumArtworkFallbacks = albumArtworkFallbacks,
                                onAlbum = onAlbum,
                                onRefreshAlbums = onRefreshAlbums,
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
    radioStations: List<PlexRadioStation>,
    radioStartingIds: Set<String>,
    onPlayRadioStation: (PlexRadioStation) -> Unit,
    onClearDecadeMixNotice: () -> Unit,
    onShowDecadeMix: () -> Unit,
) {
    ExpandedHomeShelf("CREATE A MIX", horizontalSpacing = 9.dp) {
        item(key = "personal-mix", contentType = "expanded-mix-action") {
            MobileActionCard("Personal", PhoebeIcon.Person, Modifier.width(126.dp), onClick = onPlayPersonalMix)
        }
        item(key = "decade-mix", contentType = "expanded-mix-action") {
            MobileActionCard("Decade", PhoebeIcon.Calendar, Modifier.width(126.dp)) {
                onClearDecadeMixNotice()
                onShowDecadeMix()
            }
        }
        items(radioStations, key = { "radio:${it.key}" }, contentType = { "expanded-radio-station" }) { station ->
            val starting = station.key in radioStartingIds
            MobileActionCard(
                if (starting) "Starting..." else station.title,
                station.homeRadioIcon(),
                Modifier.width(156.dp),
                enabled = !starting,
            ) {
                onPlayRadioStation(station)
            }
        }
    }
}

@Composable
private fun ExpandedCollectionsShelf(
    collectionEntries: List<HomeCollectionEntry>,
    onCollections: (CollectionEntry) -> Unit,
) {
    if (collectionEntries.isEmpty()) {
        SectionLabel("COLLECTIONS", PhoebeUi.mutedText)
        HomeEmptyState("No collections are available.")
    } else {
        ExpandedHomeShelf("COLLECTIONS", horizontalSpacing = 9.dp) {
            items(collectionEntries, key = { it.collectionEntry.toString() }, contentType = { "expanded-collection" }) { entry ->
                MobileActionCard(entry.mobileTitle, entry.icon, Modifier.width(152.dp)) {
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
        ExpandedHomeEmptyShelf("RECENT SONGS", "Recently added songs will appear here.", action = "View all", onAction = onViewAll)
    } else {
        ExpandedHomeShelf("RECENT SONGS", action = "View all", onAction = onViewAll, horizontalSpacing = 10.dp) {
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
                    rows.take(4).forEachIndexed { index, row ->
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

@Composable
private fun PhoneMixesAccordionSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    onPlayPersonalMix: () -> Unit,
    radioStations: List<PlexRadioStation>,
    radioStartingIds: Set<String>,
    onPlayRadioStation: (PlexRadioStation) -> Unit,
    onClearDecadeMixNotice: () -> Unit,
    onShowDecadeMix: () -> Unit,
) {
    val actions = listOf(
        PhoneHomeAction("Personal", PhoebeIcon.Person, onClick = onPlayPersonalMix),
        PhoneHomeAction(
            label = "Decade",
            icon = PhoebeIcon.Calendar,
            onClick = {
                onClearDecadeMixNotice()
                onShowDecadeMix()
            },
        ),
    ) + radioStations.map { station ->
        val starting = station.key in radioStartingIds
        PhoneHomeAction(
            label = if (starting) "Starting..." else station.title,
            icon = station.homeRadioIcon(),
            enabled = !starting,
            onClick = { onPlayRadioStation(station) },
        )
    }
    PhoneHomeAccordionGroup(
        title = "Mixes",
        subtitle = optionCountLabel(actions.size),
        icon = PhoebeIcon.InterwovenArrows,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        PhoneHomeActionGrid(actions)
    }
}

@Composable
private fun PhoneCollectionsAccordionSection(
    collectionEntries: List<HomeCollectionEntry>,
    expanded: Boolean,
    onToggle: () -> Unit,
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
            PhoneHomeActionGrid(
                collectionEntries.map { entry ->
                    PhoneHomeAction(
                        label = entry.mobileTitle,
                        icon = entry.icon,
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
    randomArtists: List<Artist>,
    randomAlbums: List<Album>,
    artistThumbs: Map<String, String>,
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
private fun MobileRecentsShortcutsSection(
    subsections: List<HomeSection>,
    onRecentSongs: () -> Unit,
    onRecentArtists: () -> Unit,
    onRecentAlbums: () -> Unit,
) {
    val actions = recentActionsFor(subsections, onRecentSongs, onRecentArtists, onRecentAlbums)
    HomePanel(Modifier.fillMaxWidth()) {
        SectionLabel("RECENTLY ADDED", PhoebeUi.mutedText)
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
        SectionLabel("RANDOM", PhoebeUi.mutedText)
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
    onCollections: (CollectionEntry) -> Unit,
) {
    SectionLabel("COLLECTIONS", PhoebeUi.mutedText)
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        collectionRows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                row.forEach { entry ->
                    MobileActionCard(entry.mobileTitle, entry.icon, Modifier.weight(1f)) {
                        onCollections(entry.collectionEntry)
                    }
                }
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
        SectionLabel("FAVORITE PLAYLISTS", PhoebeUi.mutedText)
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
        SectionLabel("FAVORITE ARTISTS", PhoebeUi.mutedText)
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
        SectionLabel("FAVORITE ALBUMS", PhoebeUi.mutedText)
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
        SectionLabel("LISTENING HISTORY", PhoebeUi.mutedText)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MobileActionCard("Recently Played", PhoebeIcon.Music, Modifier.weight(1f), onClick = onRecentlyPlayed)
            MobileActionCard("Most Played", PhoebeIcon.PlaylistPlay, Modifier.weight(1f), onClick = onMostPlayed)
        }
    }
}

@Composable
private fun DesktopMixesPanel(
    onPlayPersonalMix: () -> Unit,
    radioStations: List<PlexRadioStation>,
    radioStartingIds: Set<String>,
    onPlayRadioStation: (PlexRadioStation) -> Unit,
    onClearDecadeMixNotice: () -> Unit,
    onShowDecadeMix: () -> Unit,
) {
    HomePanel(Modifier.fillMaxWidth()) {
        SectionLabel("CREATE A MIX", PhoebeUi.mutedText)
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(94.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("personal-mix", contentType = "mix-action") {
                HomeActionCard(
                    "Personal Mix",
                    "Recent favorites, familiar anchors, and a little discovery",
                    PhoebeIcon.Person,
                    Modifier.width(260.dp),
                    onClick = onPlayPersonalMix,
                )
            }
            item("decade-mix", contentType = "mix-action") {
                HomeActionCard(
                    "Decade Mix",
                    "Queue a shuffled era from your library",
                    PhoebeIcon.Calendar,
                    Modifier.width(260.dp),
                ) {
                    onClearDecadeMixNotice()
                    onShowDecadeMix()
                }
            }
            items(radioStations, key = { "radio:${it.id}:${it.key}" }, contentType = { "plex-radio-station" }) { station ->
                val starting = station.key in radioStartingIds
                HomeActionCard(
                    title = station.title,
                    subtitle = if (starting) "Starting radio..." else station.subtitle,
                    icon = station.homeRadioIcon(),
                    modifier = Modifier.width(220.dp),
                    enabled = !starting,
                ) {
                    onPlayRadioStation(station)
                }
            }
        }
    }
}

@Composable
private fun MobileMixesSection(
    onPlayPersonalMix: () -> Unit,
    radioStations: List<PlexRadioStation>,
    radioStartingIds: Set<String>,
    onPlayRadioStation: (PlexRadioStation) -> Unit,
    onClearDecadeMixNotice: () -> Unit,
    onShowDecadeMix: () -> Unit,
) {
    SectionLabel("CREATE A MIX", PhoebeUi.mutedText)
    HomeHorizontalCarousel(Modifier.fillMaxWidth(), horizontalSpacing = 9.dp) {
        item(key = "personal-mix", contentType = "mobile-mix-action") {
            MobileActionCard("Personal", PhoebeIcon.Person, Modifier.width(126.dp), onClick = onPlayPersonalMix)
        }
        item(key = "decade-mix", contentType = "mobile-mix-action") {
            MobileActionCard("Decade", PhoebeIcon.Calendar, Modifier.width(126.dp)) {
                onClearDecadeMixNotice()
                onShowDecadeMix()
            }
        }
        items(radioStations, key = { it.key }, contentType = { "mobile-radio-station" }) { station ->
            val starting = station.key in radioStartingIds
            MobileActionCard(
                if (starting) "Starting..." else station.title,
                station.homeRadioIcon(),
                Modifier.width(156.dp),
                enabled = !starting,
            ) {
                onPlayRadioStation(station)
            }
        }
    }
}

@Composable
private fun DesktopRecentSongsPanel(
    tracks: List<Track>,
    onRecentSongs: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
) {
    HomePanel(Modifier.fillMaxWidth()) {
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
) {
    HomePanel(Modifier.fillMaxWidth()) {
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
) {
    HomePanel(Modifier.fillMaxWidth()) {
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
) {
    val playlistActions = LocalPlaylistActions.current
    HomePanel(Modifier.fillMaxWidth()) {
        if (playlists.isEmpty()) {
            SectionLabel("FAVORITE PLAYLISTS", PhoebeUi.mutedText)
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
) {
    HomePanel(Modifier.fillMaxWidth()) {
        if (artists.isEmpty()) {
            SectionLabel("FAVORITE ARTISTS", PhoebeUi.mutedText)
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
) {
    HomePanel(Modifier.fillMaxWidth()) {
        if (albums.isEmpty()) {
            SectionLabel("FAVORITE ALBUMS", PhoebeUi.mutedText)
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
) {
    val panelHeight = 320.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 820.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MostPlayedPanel(state.mostPlayedTracks, onPlayTracks, onAddToUpNext, onDownload, onMostPlayed, Modifier.fillMaxWidth(), maxRows = playedPanelMaxRows, sharedKeyForTrack = sharedKeyForPlayedTrack)
                RecentPlayedPanel(state, onPlayTracks, onAddToUpNext, onDownload, onRecentlyPlayed, Modifier.fillMaxWidth(), maxRows = playedPanelMaxRows, sharedKeyForTrack = sharedKeyForPlayedTrack)
            }
        } else {
            Row(Modifier.fillMaxWidth().height(panelHeight), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MostPlayedPanel(state.mostPlayedTracks, onPlayTracks, onAddToUpNext, onDownload, onMostPlayed, Modifier.weight(1f).fillMaxHeight(), maxRows = playedPanelMaxRows, sharedKeyForTrack = sharedKeyForPlayedTrack, rowHeight = 78.dp)
                RecentPlayedPanel(state, onPlayTracks, onAddToUpNext, onDownload, onRecentlyPlayed, Modifier.weight(1f).fillMaxHeight(), maxRows = playedPanelMaxRows, sharedKeyForTrack = sharedKeyForPlayedTrack, rowHeight = 78.dp)
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
) {
    val randomPanelHeight = 304.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 820.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                RandomArtistPanel(state.randomArtists.firstOrNull(), state.randomArtistStats, catalogRefreshing, onArtist, onRefreshArtists, onPrefetchArtist, Modifier.fillMaxWidth().height(randomPanelHeight))
                RandomAlbumPanel(state.randomAlbums.firstOrNull(), state.randomAlbumStats, catalogRefreshing, albumArtworkFallbacks, onAlbum, onRefreshAlbums, onPrefetchAlbum, Modifier.fillMaxWidth().height(randomPanelHeight))
            }
        } else {
            Row(Modifier.fillMaxWidth().height(randomPanelHeight), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RandomArtistPanel(state.randomArtists.firstOrNull(), state.randomArtistStats, catalogRefreshing, onArtist, onRefreshArtists, onPrefetchArtist, Modifier.weight(1f).fillMaxHeight())
                RandomAlbumPanel(state.randomAlbums.firstOrNull(), state.randomAlbumStats, catalogRefreshing, albumArtworkFallbacks, onAlbum, onRefreshAlbums, onPrefetchAlbum, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun HomePanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PhoebeUi.panel)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp))
            .padding(14.dp),
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
        ),
        HomeCollectionEntry(
            collectionEntry = CollectionEntry(CollectionTarget.Albums, CollectionFacet.Mood),
            homeTitle = "Album Mood",
            homeSubtitle = "Browse album mood tags",
            mobileTitle = "Album Mood",
            icon = PhoebeIcon.MoodFace,
        ),
        HomeCollectionEntry(
            collectionEntry = CollectionEntry(CollectionTarget.Artists, CollectionFacet.Style),
            homeTitle = "Artist Style",
            homeSubtitle = "Browse artist style tags",
            mobileTitle = "Artist Style",
            icon = PhoebeIcon.SunglassesFace,
        ),
        HomeCollectionEntry(
            collectionEntry = CollectionEntry(CollectionTarget.Albums, CollectionFacet.Style),
            homeTitle = "Album Style",
            homeSubtitle = "Browse album style tags",
            mobileTitle = "Album Style",
            icon = PhoebeIcon.SunglassesFace,
        ),
        HomeCollectionEntry(
            collectionEntry = CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre),
            homeTitle = "Artist Genre",
            homeSubtitle = "Browse artist genres",
            mobileTitle = "Artist Genre",
            icon = PhoebeIcon.GenreMasks,
        ),
        HomeCollectionEntry(
            collectionEntry = CollectionEntry(CollectionTarget.Albums, CollectionFacet.Genre),
            homeTitle = "Album Genre",
            homeSubtitle = "Browse album genres",
            mobileTitle = "Album Genre",
            icon = PhoebeIcon.GenreMasks,
        ),
    )

@Composable
private fun DesktopCollectionsGrid(
    supportedCollectionEntries: Set<CollectionEntry>,
    onCollections: (CollectionEntry) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 760.dp) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            collectionEntries(supportedCollectionEntries).chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(82.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { entry ->
                        HomeActionCard(
                            title = entry.homeTitle,
                            subtitle = entry.homeSubtitle,
                            icon = entry.icon,
                            modifier = Modifier.weight(1f),
                        ) {
                            onCollections(entry.collectionEntry)
                        }
                    }
                    repeat(columns - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeActionCard(
    title: String,
    subtitle: String,
    icon: PhoebeIcon,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeActionIcon(icon, 46.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = PhoebeUi.primaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = PhoebeUi.secondaryText, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.secondaryText, modifier = Modifier.size(14.dp))
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
    Column(
        modifier
            .height(84.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HomeActionIcon(icon, 38.dp)
        Spacer(Modifier.height(7.dp))
        Text(label, color = PhoebeUi.primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
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

@Composable
private fun HomeActionIcon(icon: PhoebeIcon, size: androidx.compose.ui.unit.Dp) {
    val lightMode = LocalPhoebePalette.current.canvasBackground.luminance() > 0.5f
    val palette = remember(icon, lightMode) { homeIconPalette(icon, lightMode) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        palette.first.copy(alpha = 0.34f),
                        palette.second.copy(alpha = 0.20f),
                    ),
                ),
            )
            .border(BorderStroke(1.dp, palette.first.copy(alpha = 0.30f)), shape),
        contentAlignment = Alignment.Center,
    ) {
        PhoebeIconView(icon, tint = palette.foreground, modifier = Modifier.size(size * 0.48f))
    }
}

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
) {
    HomePanel(modifier) {
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
                .clip(CircleShape),
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
) {
    val homeTracksReady = LocalHomeTrackSectionsReady.current
    LaunchedEffect(artist?.id, homeTracksReady) {
        if (!homeTracksReady) return@LaunchedEffect
        artist?.let(onPrefetch)
    }
    HomePanel(modifier) {
        SectionHeader("RANDOM ARTIST", "Refresh", onRefresh)
        if (artist == null) {
            HomeEmptyState("Add music to your library to discover artists here.")
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                FeaturedArtistCard(
                    artist = artist,
                    stats = stats?.takeIf { it.artistId == artist.id },
                    catalogRefreshing = catalogRefreshing,
                    modifier = Modifier.fillMaxWidth(0.92f),
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
) {
    val homeTracksReady = LocalHomeTrackSectionsReady.current
    LaunchedEffect(album?.id, homeTracksReady) {
        if (!homeTracksReady) return@LaunchedEffect
        album?.let(onPrefetch)
    }
    HomePanel(modifier) {
        SectionHeader("RANDOM ALBUM", "Refresh", onRefresh)
        if (album == null) {
            HomeEmptyState("Add music to your library to discover albums here.")
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                FeaturedAlbumCard(
                    album = album,
                    stats = stats?.takeIf { it.albumId == album.id },
                    catalogRefreshing = catalogRefreshing,
                    fallbackThumbUrl = albumArtworkFallbacks[album.id],
                    modifier = Modifier.fillMaxWidth(0.92f),
                    onClick = { onAlbum(album) },
                )
            }
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
    val totalDuration = stats?.totalDurationMs ?: 0L
    val lastPlayedLabel = remember(stats?.lastPlayedMs, nowMs) { formatLastPlayed(stats?.lastPlayedMs, nowMs) }
    val albumWord = if (albumCount == 1) "album" else "albums"
    val songWord = if (trackCount == 1) "song" else "songs"
    val artworkSize = 124.dp

    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(
            Modifier.width(136.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ArtworkImage(
                artist.title,
                artistThumbUrl,
                Modifier
                    .size(artworkSize)
                    .sharedArtworkTransition("artist:${artist.id}")
                    .clip(CircleShape),
                radius = artworkSize / 2f,
                elevated = false,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    artist.title,
                    color = PhoebeUi.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.sharedBoundsTransition("artist:${artist.id}:title"),
                )
                if (trackStatsLoading) {
                    HomeStatLoadingBar(Modifier.width(96.dp))
                } else if (!genre.isNullOrBlank()) {
                    Text(genre, color = PhoebeUi.mutedText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeArtistStat(
                value = "$albumCount $albumWord",
                label = "Albums",
                icon = PhoebeIcon.Library,
                loading = albumsLoading,
            )
            HomeArtistStat(
                value = "$trackCount $songWord",
                label = "Songs",
                icon = PhoebeIcon.Music,
                loading = trackStatsLoading,
            )
            HomeArtistStat(
                value = formatHoursMinutes(totalDuration),
                label = "Total duration",
                icon = PhoebeIcon.ActiveDot,
                loading = trackStatsLoading,
            )
            HomeArtistStat(lastPlayedLabel, "Last played", PhoebeIcon.Bell)
        }
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

    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            Modifier.width(136.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ArtworkImage(
                album.title,
                album.thumbUrl,
                Modifier.size(112.dp).sharedArtworkTransition("album:${album.id}"),
                radius = 10.dp,
                elevated = false,
                fallbackThumbUrl = fallbackThumbUrl,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    album.title,
                    color = PhoebeUi.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.sharedBoundsTransition("album:${album.id}:title"),
                )
                Text(
                    album.artist,
                    color = PhoebeUi.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.sharedBoundsTransition("album:${album.id}:subtitle"),
                )
                if (trackStatsLoading) {
                    HomeStatLoadingBar(Modifier.width(96.dp))
                } else if (!genre.isNullOrBlank()) {
                    Text(genre, color = PhoebeUi.mutedText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            album.year?.let { year ->
                HomeArtistStat(year.toString(), "Release year", PhoebeIcon.Grid)
            }
            HomeArtistStat(
                value = "$trackCount $songWord",
                label = "Tracks",
                icon = PhoebeIcon.Music,
                loading = trackStatsLoading,
            )
            HomeArtistStat(
                value = formatHoursMinutes(duration),
                label = "Total duration",
                icon = PhoebeIcon.ActiveDot,
                loading = trackStatsLoading,
            )
        }
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
) {
    val mostPlayedResolving = LocalMostPlayedResolving.current
    HomePanel(modifier) {
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
        SectionLabel(title, PhoebeUi.mutedText)
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
        SectionLabel(title, PhoebeUi.mutedText)
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
        SectionLabel(title, PhoebeUi.mutedText)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onPlay, onLongClick = { menuExpanded = true })
            .background(
                if (isNowPlaying) PhoebeUi.accent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.045f),
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
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
