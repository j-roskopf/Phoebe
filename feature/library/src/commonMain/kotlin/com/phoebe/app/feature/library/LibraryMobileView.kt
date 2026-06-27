package com.phoebe.app.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.phoebe.app.data.catalogTracksForAlbum
import com.phoebe.app.data.filterAlbumsByQuery
import com.phoebe.app.data.filterArtistsByQuery
import com.phoebe.app.data.filterPlaylistsByQuery
import com.phoebe.app.data.filterTracksByQuery
import com.phoebe.app.data.sortAlbumsForLibrary
import com.phoebe.app.data.sortArtistsForLibrary
import com.phoebe.app.data.sortTracksForLibrary
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.JellyfinLibraryPageKind
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.canTogglePlexLike
import com.phoebe.app.domain.isLocalMediaPlayback
import com.phoebe.app.domain.isLikedSongsPlaylist
import com.phoebe.app.domain.isRemoteLibraryTrack
import com.phoebe.app.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val MobileLibraryChromeTopPadding = 8.dp
private val MobileLibraryTabsHeight = 50.dp
private val MobileLibraryTabsToSearchGap = 10.dp
private val MobileLibrarySearchHeight = 44.dp
private val MobileLibrarySearchToContentGap = 10.dp
private val MobileLibraryContentGap = 8.dp
private val MobileLibrarySectionIndexBottomPadding = 22.dp

private fun LazyListState.libraryScrollbarState(): LibraryScrollbarState =
    LibraryScrollbarState(
        firstVisibleItemIndex = firstVisibleItemIndex,
        visibleItemsCount = layoutInfo.visibleItemsInfo.size,
        totalItemsCount = layoutInfo.totalItemsCount,
    )

private fun LazyGridState.libraryScrollbarState(): LibraryScrollbarState =
    LibraryScrollbarState(
        firstVisibleItemIndex = firstVisibleItemIndex,
        visibleItemsCount = layoutInfo.visibleItemsInfo.size,
        totalItemsCount = layoutInfo.totalItemsCount,
    )

@Composable
fun LibraryMobileView(
    catalog: CatalogSnapshot,
    catalogRefreshing: Boolean,
    filter: LibraryFilterTab,
    libraryUi: LibraryUiPreferences,
    onFilter: (LibraryFilterTab) -> Unit,
    onLibrarySortBy: (LibrarySortBy) -> Unit,
    onLibraryAscending: (Boolean) -> Unit,
    onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    modifier: Modifier = Modifier,
    searchQuery: String = "",
    onSearchQuery: (String) -> Unit = {},
    libraryViewMode: LibraryViewMode = LibraryViewMode.Grid,
    jellyfinPagination: Boolean = false,
    onJellyfinPage: (JellyfinLibraryPageKind, Int) -> Unit = { _, _ -> },
    topBar: (@Composable () -> Unit)? = null,
) {
    var pageIndex by remember(filter) { mutableStateOf(0) }

    val ascending = libraryUi.ascending
    val sortBy = libraryUi.sortBy

    val sortedArtists = remember(catalog.artists, sortBy, ascending) {
        sortArtistsForLibrary(catalog, sortBy, ascending)
    }
    val sortedAlbums = remember(catalog.albums, sortBy, ascending) {
        sortAlbumsForLibrary(catalog.albums, sortBy, ascending)
    }
    val allTracks = remember(catalog.tracksByParent) {
        catalog.tracksByParent.values.asSequence().flatten().distinctBy { it.id }.toList()
    }
    val sortedTracks = remember(allTracks, sortBy, ascending) {
        sortTracksForLibrary(allTracks, sortBy, ascending)
    }
    var visibleArtistsResult by remember { mutableStateOf<List<Artist>?>(null) }
    var visibleAlbumsResult by remember { mutableStateOf<List<Album>?>(null) }
    var visibleTracksResult by remember { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(sortedArtists, searchQuery) {
        visibleArtistsResult = withContext(Dispatchers.Default) {
            filterArtistsByQuery(sortedArtists, searchQuery)
        }
    }
    LaunchedEffect(sortedAlbums, searchQuery) {
        visibleAlbumsResult = withContext(Dispatchers.Default) {
            filterAlbumsByQuery(sortedAlbums, searchQuery)
        }
    }
    LaunchedEffect(sortedTracks, searchQuery) {
        visibleTracksResult = withContext(Dispatchers.Default) {
            filterTracksByQuery(sortedTracks, searchQuery)
        }
    }
    val visibleArtists = visibleArtistsResult ?: if (searchQuery.isBlank()) sortedArtists else emptyList()
    val visibleAlbums = visibleAlbumsResult ?: if (searchQuery.isBlank()) sortedAlbums else emptyList()
    val visibleTracks = visibleTracksResult ?: if (searchQuery.isBlank()) sortedTracks else emptyList()
    val artistTotal = if (searchQuery.isBlank()) catalog.remotePageInfo.artistTotal else null
    val albumTotal = if (searchQuery.isBlank()) catalog.remotePageInfo.albumTotal else null
    val trackTotal = if (searchQuery.isBlank()) catalog.remotePageInfo.trackTotal else null
    val artistPage = remember(visibleArtists, jellyfinPagination, pageIndex, artistTotal) { libraryPage(visibleArtists, jellyfinPagination, pageIndex, artistTotal) }
    val albumPage = remember(visibleAlbums, jellyfinPagination, pageIndex, albumTotal) { libraryPage(visibleAlbums, jellyfinPagination, pageIndex, albumTotal) }
    val trackPage = remember(visibleTracks, jellyfinPagination, pageIndex, trackTotal) { libraryPage(visibleTracks, jellyfinPagination, pageIndex, trackTotal) }
    LaunchedEffect(filter, searchQuery, visibleArtists.size, visibleAlbums.size, visibleTracks.size) {
        val pageCount = when (filter) {
            LibraryFilterTab.Artists -> artistPage.pageCount
            LibraryFilterTab.Albums -> albumPage.pageCount
            LibraryFilterTab.Songs -> trackPage.pageCount
        }
        if (pageIndex > pageCount - 1) pageIndex = (pageCount - 1).coerceAtLeast(0)
    }
    val chromePadding = LocalMobileChromePadding.current
    val listContentPadding = PaddingValues(
        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + MobileLibraryChromeTopPadding,
        bottom = chromePadding.bottom + 8.dp,
    )
    val libraryHeader: @Composable () -> Unit = {
        MobileLibraryHeader(
            topBar = topBar,
            filter = filter,
            searchQuery = searchQuery,
            catalogRefreshing = catalogRefreshing,
            artistPage = artistPage,
            albumPage = albumPage,
            trackPage = trackPage,
            jellyfinPagination = jellyfinPagination,
            onFilter = onFilter,
            onSearchQuery = onSearchQuery,
            onJellyfinPage = onJellyfinPage,
            onPageIndex = { pageIndex = it },
        )
    }

    Box(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Box(Modifier.fillMaxSize()) {
            when (filter) {
                LibraryFilterTab.Artists -> MobileArtistsContent(
                    artists = artistPage.items,
                    viewMode = libraryViewMode,
                    artistGridItemSizeDp = libraryUi.artistGridItemSizeDp,
                    sortBy = sortBy,
                    ascending = ascending,
                    onArtist = onArtist,
                    contentPadding = listContentPadding,
                    contentHeader = libraryHeader,
                )
                LibraryFilterTab.Albums -> MobileAlbumsContent(
                    catalog = catalog,
                    albums = albumPage.items,
                    viewMode = libraryViewMode,
                    albumGridItemSizeDp = libraryUi.albumGridItemSizeDp,
                    sortBy = sortBy,
                    ascending = ascending,
                    onAlbum = onAlbum,
                    contentPadding = listContentPadding,
                    contentHeader = libraryHeader,
                )
                LibraryFilterTab.Songs -> MobileSongsList(
                    tracks = trackPage.items,
                    columns = libraryUi.columns,
                    sortBy = sortBy,
                    ascending = ascending,
                    onPlay = { index -> onPlayTracks(trackPage.items, index) },
                    onAddToUpNext = onAddToUpNext,
                    onDownload = onDownload,
                    contentPadding = listContentPadding,
                    contentHeader = libraryHeader,
                )
            }
        }
    }
}

@Composable
private fun MobileLibraryHeader(
    topBar: (@Composable () -> Unit)?,
    filter: LibraryFilterTab,
    searchQuery: String,
    catalogRefreshing: Boolean,
    artistPage: LibraryPage<Artist>,
    albumPage: LibraryPage<Album>,
    trackPage: LibraryPage<Track>,
    jellyfinPagination: Boolean,
    onFilter: (LibraryFilterTab) -> Unit,
    onSearchQuery: (String) -> Unit,
    onJellyfinPage: (JellyfinLibraryPageKind, Int) -> Unit,
    onPageIndex: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        topBar?.invoke()
        MobileLibraryTabs(
            filter = filter,
            onFilter = onFilter,
        )
        Spacer(Modifier.height(MobileLibraryTabsToSearchGap))
        SearchPill(
            query = searchQuery,
            onQueryChange = onSearchQuery,
            modifier = Modifier.fillMaxWidth().height(MobileLibrarySearchHeight),
            placeholder = when (filter) {
                LibraryFilterTab.Artists -> "Search artists"
                LibraryFilterTab.Albums -> "Search albums"
                LibraryFilterTab.Songs -> "Search songs"
            },
        )
        Spacer(Modifier.height(MobileLibrarySearchToContentGap))
        if (catalogRefreshing) {
            LibraryLoadingStrip(Modifier.padding(bottom = 6.dp))
        }
        when (filter) {
            LibraryFilterTab.Artists -> LibraryPaginationControls(artistPage, onPage = {
                if (jellyfinPagination && searchQuery.isBlank()) onJellyfinPage(filter.toJellyfinPageKind(), it)
                onPageIndex(it)
            })
            LibraryFilterTab.Albums -> LibraryPaginationControls(albumPage, onPage = {
                if (jellyfinPagination && searchQuery.isBlank()) onJellyfinPage(filter.toJellyfinPageKind(), it)
                onPageIndex(it)
            })
            LibraryFilterTab.Songs -> LibraryPaginationControls(trackPage, onPage = {
                if (jellyfinPagination && searchQuery.isBlank()) onJellyfinPage(filter.toJellyfinPageKind(), it)
                onPageIndex(it)
            })
        }
        Spacer(Modifier.height(MobileLibraryContentGap))
    }
}

@Composable
fun FavoriteArtistsMobileView(
    catalog: CatalogSnapshot,
    libraryUi: LibraryUiPreferences,
    onLibrarySortBy: (LibrarySortBy) -> Unit,
    onLibraryAscending: (Boolean) -> Unit,
    onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    onArtist: (Artist) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewMode by remember { mutableStateOf(LibraryViewMode.Grid) }
    val favoriteArtists = remember(catalog, libraryUi.sortBy, libraryUi.ascending) {
        sortArtistsForLibrary(catalog, libraryUi.sortBy, libraryUi.ascending).filter { it.favorite }
    }
    val chromePadding = LocalMobileChromePadding.current
    Column(
        modifier
            .fillMaxSize()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = mobileContentTopPadding(18.dp),
                bottom = 18.dp + chromePadding.bottom
            ),
    ) {
        FavoriteLibraryHeader(
            title = "Favorite Artists",
            count = favoriteArtists.size,
            itemLabel = "artists",
            onBack = onBack,
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LibraryFilterOptionsMenu(
                filter = LibraryFilterTab.Artists,
                prefs = libraryUi,
                onSortBy = onLibrarySortBy,
                onAscending = onLibraryAscending,
                libraryViewMode = viewMode,
                onLibraryViewMode = { viewMode = it },
                onColumns = onLibraryColumns,
            )
        }
        Spacer(Modifier.height(10.dp))
        MobileArtistsContent(
            artists = favoriteArtists,
            viewMode = viewMode,
            artistGridItemSizeDp = libraryUi.artistGridItemSizeDp,
            sortBy = libraryUi.sortBy,
            ascending = libraryUi.ascending,
            onArtist = onArtist,
        )
    }
}

@Composable
fun FavoriteAlbumsMobileView(
    catalog: CatalogSnapshot,
    libraryUi: LibraryUiPreferences,
    onLibrarySortBy: (LibrarySortBy) -> Unit,
    onLibraryAscending: (Boolean) -> Unit,
    onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    onAlbum: (Album) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewMode by remember { mutableStateOf(LibraryViewMode.Grid) }
    val favoriteAlbums = remember(catalog.albums, libraryUi.sortBy, libraryUi.ascending) {
        sortAlbumsForLibrary(catalog.albums, libraryUi.sortBy, libraryUi.ascending).filter { it.favorite }
    }
    val chromePadding = LocalMobileChromePadding.current
    Column(
        modifier
            .fillMaxSize()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = mobileContentTopPadding(18.dp),
                bottom = 18.dp + chromePadding.bottom
            ),
    ) {
        FavoriteLibraryHeader(
            title = "Favorite Albums",
            count = favoriteAlbums.size,
            itemLabel = "albums",
            onBack = onBack,
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LibraryFilterOptionsMenu(
                filter = LibraryFilterTab.Albums,
                prefs = libraryUi,
                onSortBy = onLibrarySortBy,
                onAscending = onLibraryAscending,
                libraryViewMode = viewMode,
                onLibraryViewMode = { viewMode = it },
                onColumns = onLibraryColumns,
            )
        }
        Spacer(Modifier.height(10.dp))
        MobileAlbumsContent(
            catalog = catalog,
            albums = favoriteAlbums,
            viewMode = viewMode,
            albumGridItemSizeDp = libraryUi.albumGridItemSizeDp,
            sortBy = libraryUi.sortBy,
            ascending = libraryUi.ascending,
            onAlbum = onAlbum,
        )
    }
}

@Composable
private fun FavoriteLibraryHeader(
    title: String,
    count: Int,
    itemLabel: String,
    onBack: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DetailBackButton(onBack = onBack)
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "FAVORITES",
                color = PhoebeUi.mutedText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.08.em,
            )
            Text(title, color = PhoebeUi.primaryText, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text("$count $itemLabel", color = PhoebeUi.secondaryText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun MobileLibraryTabs(
    filter: LibraryFilterTab,
    onFilter: (LibraryFilterTab) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(MobileLibraryTabsHeight)
            .clip(RoundedCornerShape(16.dp))
            .background(PhoebeUi.accent.copy(alpha = 0.10f))
            .border(BorderStroke(1.dp, PhoebeUi.accent.copy(alpha = 0.28f)), RoundedCornerShape(16.dp))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LibraryFilterTab.entries.forEach { tab ->
            val active = filter == tab
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(11.dp))
                    .clickable { onFilter(tab) }
                    .background(if (active) PhoebeUi.accent.copy(alpha = 0.32f) else Color.Transparent)
                    .border(
                        BorderStroke(
                            1.dp,
                            if (active) PhoebeUi.accentLight.copy(alpha = 0.36f) else Color.Transparent,
                        ),
                        RoundedCornerShape(11.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (tab) {
                        LibraryFilterTab.Artists -> "Artists"
                        LibraryFilterTab.Albums -> "Albums"
                        LibraryFilterTab.Songs -> "Songs"
                    },
                    color = if (active) PhoebeUi.primaryText else PhoebeUi.secondaryText,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MobileColumnRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LibraryCheckbox(checked = checked)
        Text(label, color = PhoebeUi.primaryText, fontSize = 13.sp)
    }
}

// =====================================================================
// Artists (mobile grid/list)
// =====================================================================

@Composable
private fun MobileArtistsContent(
    artists: List<Artist>,
    viewMode: LibraryViewMode,
    artistGridItemSizeDp: Int,
    sortBy: LibrarySortBy,
    ascending: Boolean,
    onArtist: (Artist) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    contentHeader: (@Composable () -> Unit)? = null,
) {
    if (artists.isEmpty()) {
        LazyColumn(
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            contentHeader?.let { header ->
                item(key = "library-header", contentType = "library-header") { header() }
            }
            item(contentType = "empty-state") {
                Text("No artists yet.", color = PhoebeUi.mutedText, fontSize = 13.sp)
            }
        }
        return
    }
    val headerItemOffset = if (contentHeader == null) 0 else 1
    val indexEntries = remember(artists, sortBy, ascending) {
        libraryArtistScrollIndex(artists, sortBy, ascending)
    }
    val scope = rememberCoroutineScope()
    val indexScrollDispatcher = rememberLibrarySectionIndexSelectionDispatcher()
    var sectionIndexScrubbing by remember { mutableStateOf(false) }
    when (viewMode) {
        LibraryViewMode.Grid -> {
            val gridState = RetainedLazyGridStates.remember("library-artists-grid")
            val scrolling by remember(gridState) { derivedStateOf { gridState.isScrollInProgress } }
            Box(Modifier.fillMaxSize()) {
                LibraryResponsiveGrid(
                    itemSizeDp = artistGridItemSizeDp,
                    horizontalSpacing = 14.dp,
                    verticalSpacing = 16.dp,
                    state = gridState,
                    contentPadding = contentPadding,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    contentHeader?.let { header ->
                        item(
                            key = "library-header",
                            span = { GridItemSpan(maxLineSpan) },
                            contentType = "library-header",
                        ) {
                            header()
                        }
                    }
                    items(artists, key = { it.id }, contentType = { "artist-card" }) { artist ->
                        val onArtistClick = remember(artist, onArtist) { { onArtist(artist) } }
                        MobileArtistCard(
                            artist = artist,
                            artworkDecodeDimension = libraryGridDecodeDimension(artistGridItemSizeDp),
                            onClick = onArtistClick,
                        )
                    }
                }
                LibrarySectionIndex(
                    entries = indexEntries,
                    onEntrySelected = { entry ->
                        indexScrollDispatcher.launch(scope, key = entry.itemIndex) {
                            gridState.scrollToItem(entry.itemIndex + headerItemOffset)
                        }
                    },
                    onScrubbingChanged = { sectionIndexScrubbing = it },
                    mode = LibrarySectionIndexMode.MobileScrollbar,
                    revealSignal = scrolling,
                    scrollbarStateProvider = { gridState.libraryScrollbarState() },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(
                            top = contentPadding.calculateTopPadding(),
                            bottom = contentPadding.calculateBottomPadding() + MobileLibrarySectionIndexBottomPadding,
                        ),
                )
            }
        }
        LibraryViewMode.List -> {
            val listState = RetainedLazyListStates.remember("library-artists-list")
            val scrolling by remember(listState) { derivedStateOf { listState.isScrollInProgress } }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = contentPadding,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    contentHeader?.let { header ->
                        item(key = "library-header", contentType = "library-header") { header() }
                    }
                    items(artists, key = { it.id }, contentType = { "artist-row" }) { artist ->
                        val onArtistClick = remember(artist, onArtist) { { onArtist(artist) } }
                        MobileArtistRow(artist = artist, onClick = onArtistClick)
                    }
                }
                LibrarySectionIndex(
                    entries = indexEntries,
                    onEntrySelected = { entry ->
                        indexScrollDispatcher.launch(scope, key = entry.itemIndex) {
                            listState.scrollToItem(entry.itemIndex + headerItemOffset)
                        }
                    },
                    onScrubbingChanged = { sectionIndexScrubbing = it },
                    mode = LibrarySectionIndexMode.MobileScrollbar,
                    revealSignal = scrolling,
                    scrollbarStateProvider = { listState.libraryScrollbarState() },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(
                            top = contentPadding.calculateTopPadding(),
                            bottom = contentPadding.calculateBottomPadding() + MobileLibrarySectionIndexBottomPadding,
                        ),
                )
            }
        }
    }
}

@Composable
private fun MobileArtistCard(
    artist: Artist,
    artworkDecodeDimension: Int,
    onClick: () -> Unit,
) {
    val subtitle = remember(artist.albumCount) {
        val albumCount = artist.albumCount
        if (albumCount > 0) {
            "$albumCount ${if (albumCount == 1) "album" else "albums"}"
        } else {
            "Artist"
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtworkImage(
            artist.title,
            artist.thumbUrl,
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .sharedArtworkTransition("artist:${artist.id}")
                .clip(CircleShape),
            radius = 999.dp,
            shape = CircleShape,
            elevated = false,
            maxDecodeDimension = artworkDecodeDimension,
        )
        Text(
            artist.title,
            color = PhoebeUi.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.sharedBoundsTransition("artist:${artist.id}:title"),
        )
        Text(
            subtitle,
            color = PhoebeUi.mutedText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MobileArtistRow(
    artist: Artist,
    onClick: () -> Unit,
) {
    val subtitle = remember(artist.albumCount, artist.songCount) {
        buildString {
            if (artist.albumCount > 0) {
                append("${artist.albumCount} ${if (artist.albumCount == 1) "album" else "albums"}")
            }
            if (artist.songCount > 0) {
                if (length > 0) append(" • ")
                append("${artist.songCount} ${if (artist.songCount == 1) "song" else "songs"}")
            }
        }.ifBlank { "Artist" }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtworkImage(
            artist.title,
            artist.thumbUrl,
            Modifier
                .size(44.dp)
                .sharedArtworkTransition("artist:${artist.id}")
                .clip(CircleShape),
            radius = 22.dp,
            elevated = false,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                artist.title,
                color = PhoebeUi.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition("artist:${artist.id}:title"),
            )
            Text(
                subtitle,
                color = PhoebeUi.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(12.dp))
    }
}

// =====================================================================
// Albums (mobile)
// =====================================================================

@Composable
private fun MobileAlbumsContent(
    catalog: CatalogSnapshot,
    albums: List<Album>,
    viewMode: LibraryViewMode,
    albumGridItemSizeDp: Int,
    sortBy: LibrarySortBy,
    ascending: Boolean,
    onAlbum: (Album) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    contentHeader: (@Composable () -> Unit)? = null,
) {
    if (albums.isEmpty()) {
        LazyColumn(
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            contentHeader?.let { header ->
                item(key = "library-header", contentType = "library-header") { header() }
            }
            item(contentType = "empty-state") {
                Text("No albums yet.", color = PhoebeUi.mutedText, fontSize = 13.sp)
            }
        }
        return
    }
    val headerItemOffset = if (contentHeader == null) 0 else 1
    val indexEntries = remember(albums, sortBy, ascending) {
        libraryAlbumScrollIndex(albums, sortBy, ascending)
    }
    val scope = rememberCoroutineScope()
    val indexScrollDispatcher = rememberLibrarySectionIndexSelectionDispatcher()
    var sectionIndexScrubbing by remember { mutableStateOf(false) }
    when (viewMode) {
        LibraryViewMode.Grid -> {
            val gridState = RetainedLazyGridStates.remember("library-albums-grid")
            val scrolling by remember(gridState) { derivedStateOf { gridState.isScrollInProgress } }
            Box(Modifier.fillMaxSize()) {
                LibraryResponsiveGrid(
                    itemSizeDp = albumGridItemSizeDp,
                    horizontalSpacing = 14.dp,
                    verticalSpacing = 16.dp,
                    state = gridState,
                    contentPadding = contentPadding,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    contentHeader?.let { header ->
                        item(
                            key = "library-header",
                            span = { GridItemSpan(maxLineSpan) },
                            contentType = "library-header",
                        ) {
                            header()
                        }
                    }
                    items(albums, key = { it.id }, contentType = { "album-card" }) { album ->
                        val onAlbumClick = remember(album, onAlbum) { { onAlbum(album) } }
                        MobileAlbumCard(
                            catalog = catalog,
                            album = album,
                            artworkDecodeDimension = libraryGridDecodeDimension(albumGridItemSizeDp),
                            onClick = onAlbumClick,
                        )
                    }
                }
                LibrarySectionIndex(
                    entries = indexEntries,
                    onEntrySelected = { entry ->
                        indexScrollDispatcher.launch(scope, key = entry.itemIndex) {
                            gridState.scrollToItem(entry.itemIndex + headerItemOffset)
                        }
                    },
                    onScrubbingChanged = { sectionIndexScrubbing = it },
                    mode = LibrarySectionIndexMode.MobileScrollbar,
                    revealSignal = scrolling,
                    scrollbarStateProvider = { gridState.libraryScrollbarState() },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(
                            top = contentPadding.calculateTopPadding(),
                            bottom = contentPadding.calculateBottomPadding() + MobileLibrarySectionIndexBottomPadding,
                        ),
                )
            }
        }
        LibraryViewMode.List -> {
            val listState = RetainedLazyListStates.remember("library-albums-list")
            val scrolling by remember(listState) { derivedStateOf { listState.isScrollInProgress } }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = contentPadding,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    contentHeader?.let { header ->
                        item(key = "library-header", contentType = "library-header") { header() }
                    }
                    items(albums, key = { it.id }, contentType = { "album-row" }) { album ->
                        val onAlbumClick = remember(album, onAlbum) { { onAlbum(album) } }
                        MobileAlbumListRow(catalog = catalog, album = album, onClick = onAlbumClick)
                    }
                }
                LibrarySectionIndex(
                    entries = indexEntries,
                    onEntrySelected = { entry ->
                        indexScrollDispatcher.launch(scope, key = entry.itemIndex) {
                            listState.scrollToItem(entry.itemIndex + headerItemOffset)
                        }
                    },
                    onScrubbingChanged = { sectionIndexScrubbing = it },
                    mode = LibrarySectionIndexMode.MobileScrollbar,
                    revealSignal = scrolling,
                    scrollbarStateProvider = { listState.libraryScrollbarState() },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(
                            top = contentPadding.calculateTopPadding(),
                            bottom = contentPadding.calculateBottomPadding() + MobileLibrarySectionIndexBottomPadding,
                        ),
                )
            }
        }
    }
}

@Composable
private fun MobileAlbumCard(
    catalog: CatalogSnapshot,
    album: Album,
    artworkDecodeDimension: Int,
    onClick: () -> Unit,
) {
    val tracks = remember(catalog, album.id) { catalogTracksForAlbum(catalog, album.id) }
    val durationMs = remember(tracks) { tracks.sumOf { it.durationMs } }
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .sharedArtworkTransition("album:${album.id}"),
        ) {
            ArtworkImage(
                album.title,
                album.thumbUrl,
                Modifier.fillMaxSize(),
                radius = 10.dp,
                elevated = false,
                maxDecodeDimension = artworkDecodeDimension,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(horizontal = 2.dp)) {
            Text(
                album.title,
                color = PhoebeUi.primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition("album:${album.id}:title"),
            )
            Text(
                album.artist,
                color = PhoebeUi.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition("album:${album.id}:subtitle"),
            )
            Text(
                buildString {
                    album.year?.let { append(it.toString()) }
                    if (length > 0) append(" • ")
                    if (durationMs > 0L) append(formatMinutesLabel(durationMs))
                },
                color = PhoebeUi.mutedText,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MobileAlbumListRow(
    catalog: CatalogSnapshot,
    album: Album,
    onClick: () -> Unit,
) {
    val tracks = remember(catalog, album.id) { catalogTracksForAlbum(catalog, album.id) }
    val durationMs = remember(tracks) { tracks.sumOf { it.durationMs } }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtworkImage(
            album.title,
            album.thumbUrl,
            Modifier.size(48.dp).sharedArtworkTransition("album:${album.id}"),
            radius = 8.dp,
            elevated = false,
        )
        Column(Modifier.weight(1f)) {
            Text(
                album.title,
                color = PhoebeUi.primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition("album:${album.id}:title"),
            )
            Text(
                album.artist,
                color = PhoebeUi.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition("album:${album.id}:subtitle"),
            )
            Text(
                buildString {
                    album.year?.let { append(it.toString()) }
                    if (tracks.isNotEmpty()) {
                        if (length > 0) append(" • ")
                        append("${tracks.size} tracks")
                    }
                    if (durationMs > 0L) {
                        if (length > 0) append(" • ")
                        append(formatMinutesLabel(durationMs))
                    }
                },
                color = PhoebeUi.mutedText,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(12.dp))
    }
}

// =====================================================================
// Songs list (mobile)
// =====================================================================

@Composable
private fun MobileSongsList(
    tracks: List<Track>,
    columns: LibraryColumnVisibility,
    sortBy: LibrarySortBy,
    ascending: Boolean,
    onPlay: (Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    contentHeader: (@Composable () -> Unit)? = null,
) {
    if (tracks.isEmpty()) {
        LazyColumn(
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            contentHeader?.let { header ->
                item(key = "library-header", contentType = "library-header") { header() }
            }
            item(contentType = "empty-state") {
                Text("No songs yet.", color = PhoebeUi.mutedText, fontSize = 13.sp)
            }
        }
        return
    }
    val headerItemOffset = if (contentHeader == null) 0 else 1
    val listState = RetainedLazyListStates.remember("library-songs")
    val indexEntries = remember(tracks, sortBy, ascending) {
        libraryTrackScrollIndex(tracks, sortBy, ascending)
    }
    val scope = rememberCoroutineScope()
    val indexScrollDispatcher = rememberLibrarySectionIndexSelectionDispatcher()
    val nowPlaying = LocalNowPlaying.current
    val scrolling by remember(listState) { derivedStateOf { listState.isScrollInProgress } }
    var sectionIndexScrubbing by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            contentHeader?.let { header ->
                item(key = "library-header", contentType = "library-header") { header() }
            }
            items(tracks.size, key = { tracks[it].id }, contentType = { "song-row" }) { index ->
                val track = tracks[index]
                val onPlayClick = remember(index, onPlay) { { onPlay(index) } }
                val onAddClick = remember(track, onAddToUpNext) { { onAddToUpNext(track) } }
                val onDownloadClick = remember(track, onDownload) { { onDownload(track) } }
                MobileSongRow(
                    track = track,
                    columns = columns,
                    isNowPlaying = track.id == nowPlaying.trackId,
                    nowPlayingIsPlaying = nowPlaying.isPlaying,
                    nowPlayingIsBuffering = nowPlaying.isBuffering,
                    onPlay = onPlayClick,
                    onAddToUpNext = onAddClick,
                    onDownload = onDownloadClick,
                )
            }
        }
        LibrarySectionIndex(
            entries = indexEntries,
            onEntrySelected = { entry ->
                indexScrollDispatcher.launch(scope, key = entry.itemIndex) {
                    listState.scrollToItem(entry.itemIndex + headerItemOffset)
                }
            },
            onScrubbingChanged = { sectionIndexScrubbing = it },
            mode = LibrarySectionIndexMode.MobileScrollbar,
            revealSignal = scrolling,
            scrollbarStateProvider = { listState.libraryScrollbarState() },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + MobileLibrarySectionIndexBottomPadding,
                ),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MobileSongRow(
    track: Track,
    columns: LibraryColumnVisibility,
    isNowPlaying: Boolean,
    nowPlayingIsPlaying: Boolean,
    nowPlayingIsBuffering: Boolean,
    onPlay: () -> Unit,
    onAddToUpNext: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    leadingHandle: (@Composable () -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelection: (() -> Unit)? = null,
    playCount: Long? = null,
    lastPlayedMs: Long? = null,
) {
    var menuExpanded by remember(track.id) { mutableStateOf(false) }
    val likeActions = LocalLikeActions.current
    val ratingActions = LocalRatingActions.current
    val downloads = LocalDownloadStatus.current
    val nowMs = LocalNowMs.current
    val canRate = ratingActions.ratingsEnabled && track.isRemoteLibraryTrack()
    val canLike = likeActions.likesEnabled && track.canTogglePlexLike()
    val liked = likeActions.isLiked(track)
    val downloaded = downloads.isComplete(track)
    val metadataParts = remember(track, columns, nowMs, playCount, lastPlayedMs) {
        buildList {
            addAll(mobileSongMetadataParts(track, columns, nowMs))
            if (playCount != null && playCount > 0L) {
                add(if (playCount == 1L) "1 play" else "$playCount plays")
            }
            if (lastPlayedMs != null && lastPlayedMs > 0L) {
                add("Played ${formatLastPlayed(lastPlayedMs, nowMs)}")
            }
        }
    }
    val filepath = remember(track.filepath, columns.filepath) {
        if (columns.filepath) track.filepath?.takeIf { it.isNotBlank() }?.let(::shortenFilepath) ?: "—" else null
    }
    Row(
        modifier
            .fillMaxWidth()
            .playTrackTarget(track)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onToggleSelection?.invoke()
                    } else {
                        onPlay()
                    }
                },
                onLongClick = if (selectionMode) null else ({ menuExpanded = true }),
            )
            .background(
                when {
                    selectionMode && selected -> PhoebeUi.accent.copy(alpha = 0.12f)
                    isNowPlaying -> PhoebeUi.accent.copy(alpha = 0.14f)
                    else -> Color.Transparent
                },
            )
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selectionMode) {
            LibraryCheckbox(checked = selected, size = 18)
        }
        if (leadingHandle != null) {
            leadingHandle()
        }
        Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
            TrackArtworkImage(
                track,
                Modifier.fillMaxSize(),
                radius = 8.dp,
                elevated = false,
                maxDecodeDimension = ThumbnailArtworkMaxDecodeDimension,
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
                        isPlaying = nowPlayingIsPlaying,
                        isBuffering = nowPlayingIsBuffering,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            MobileSongRowText(
                track.title,
                color = if (isNowPlaying) PhoebeUi.accentLight else PhoebeUi.primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 15.sp,
            )
            MobileSongRowText(
                track.artist,
                color = PhoebeUi.secondaryText,
                fontSize = 11.sp,
                lineHeight = 13.sp,
            )
            if (track.album.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MobileSongRowText(
                        track.album,
                        color = PhoebeUi.mutedText,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TrackStateBadges(
                        liked = !columns.favorite && canLike && liked,
                        downloaded = downloaded,
                        iconSize = 10.dp,
                    )
                }
            }
            if (columns.rating) {
                RatingStars(
                    rating = ratingActions.ratingFor(track),
                    enabled = canRate,
                    onRating = { ratingActions.onRateTrack(track, it) },
                    starSize = 11.dp,
                    gap = 0.dp,
                )
            }
            metadataParts.forEach { metadataPart ->
                MobileSongRowText(
                    metadataPart,
                    color = PhoebeUi.mutedText,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                )
            }
            if (filepath != null) {
                MobileSongRowText(
                    "Path $filepath",
                    color = PhoebeUi.mutedText,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                )
            }
        }
        if (columns.duration) {
            Text(formatMinutesSeconds(track.durationMs), color = PhoebeUi.mutedText, fontSize = 11.sp)
        }
        if (columns.favorite) {
            LikeButton(
                liked = liked,
                enabled = canLike,
                onClick = { likeActions.onToggleLiked(track) },
                modifier = Modifier.size(34.dp),
            )
        }
        TrackDownloadIndicator(
            track = track,
            onDownload = null,
            showIdle = false,
            touchTargetSize = 34.dp,
            showComplete = false,
            showFailed = false,
        )
        Box {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = { menuExpanded = true }),
                contentAlignment = Alignment.Center,
            ) {
                PhoebeIconView(
                    PhoebeIcon.More,
                    tint = PhoebeUi.secondaryText,
                    modifier = Modifier.size(17.dp),
                )
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

private fun mobileSongMetadataParts(
    track: Track,
    columns: LibraryColumnVisibility,
    nowMs: Long,
): List<String> = buildList {
    if (columns.year) add("Year ${track.year?.toString() ?: "—"}")
    if (columns.genre) add("Genre ${track.genre?.takeIf { it.isNotBlank() } ?: "—"}")
    if (columns.audioCodec) add("Codec ${track.audioCodec?.uppercase()?.takeIf { it.isNotBlank() } ?: "—"}")
    if (columns.bitrate) add("Bitrate ${displayBitrateLabel(track)}")
    if (columns.sampleRate) add("Sample ${displaySampleRateLabel(track)}")
    if (columns.fileType) add("Type ${displayFileTypeLabel(track)}")
    if (columns.dateAdded) add("Added ${track.dateAddedMs?.let { formatLastPlayed(it, nowMs) } ?: "—"}")
}

@Composable
private fun MobileSongRowText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = fontSize,
) {
    Text(
        text,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        modifier = modifier,
    )
}

@Composable
fun FavoritePlaylistsMobileView(
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playlistActions = LocalPlaylistActions.current
    val sourcePlaylists = playlistActions.playlists
    var favoritePlaylists by remember { mutableStateOf<List<Playlist>?>(null) }
    var visiblePlaylists by remember { mutableStateOf<List<Playlist>?>(null) }
    LaunchedEffect(sourcePlaylists) {
        favoritePlaylists = withContext(Dispatchers.Default) {
            sourcePlaylists.filter { it.favorite }.sortedBy { it.title.lowercase() }
        }
    }
    LaunchedEffect(favoritePlaylists, searchQuery) {
        val playlists = favoritePlaylists
        if (playlists == null) return@LaunchedEffect
        visiblePlaylists = withContext(Dispatchers.Default) {
            filterPlaylistsByQuery(playlists, searchQuery)
        }
    }
    val preparedFavoritePlaylists = favoritePlaylists.orEmpty()
    val preparedVisiblePlaylists = visiblePlaylists
        ?: if (searchQuery.isBlank()) preparedFavoritePlaylists else emptyList()
    val preparingPlaylists = favoritePlaylists == null ||
        (visiblePlaylists == null && preparedVisiblePlaylists.isEmpty() && preparedFavoritePlaylists.isNotEmpty())
    val chromePadding = LocalMobileChromePadding.current

    Column(
        modifier
            .fillMaxSize()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = mobileContentTopPadding(18.dp),
                bottom = 18.dp + chromePadding.bottom
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DetailBackButton(onBack = onBack)
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "FAVORITES",
                    color = PhoebeUi.mutedText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.08.em,
                )
                Text("Favorite Playlists", color = PhoebeUi.primaryText, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text("${preparedFavoritePlaylists.size} playlists", color = PhoebeUi.secondaryText, fontSize = 13.sp)
            }
        }
        SearchPill(
            query = searchQuery,
            onQueryChange = onSearchQuery,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            placeholder = "Search favorite playlists",
        )
        val listState = RetainedLazyListStates.remember("mobile-favorite-playlists")
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = MobileChromeScrollGap),
        ) {
            when {
                preparingPlaylists -> item(contentType = "loading") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LibraryLoadingStrip()
                        Text(
                            "Loading playlists...",
                            color = PhoebeUi.mutedText,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                        )
                    }
                }
                preparedFavoritePlaylists.isEmpty() -> item(contentType = "empty") {
                    Text(
                        "Favorite playlists will appear here.",
                        color = PhoebeUi.mutedText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                }
                preparedVisiblePlaylists.isEmpty() -> item(contentType = "empty-filter") {
                    Text(
                        "No favorite playlists match \"$searchQuery\".",
                        color = PhoebeUi.mutedText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                }
                else -> items(preparedVisiblePlaylists, key = { it.id }, contentType = { "favorite-playlist" }) { playlist ->
                    MobilePlaylistRow(
                        icon = PhoebeIcon.Heart,
                        title = playlist.title,
                        subtitle = "${playlist.trackCount} songs",
                        thumbUrl = playlist.thumbUrl,
                        accent = true,
                        sharedKey = "playlist:${playlist.id}",
                        onClick = { onPlaylist(playlist) },
                        onLongClick = { playlistActions.onShufflePlaylist(playlist) },
                    )
                }
            }
        }
    }
}

@Composable
fun PlaylistsMobileView(
    catalog: CatalogSnapshot,
    catalogRefreshing: Boolean,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
    topBar: (@Composable () -> Unit)? = null,
) {
    val playlistActions = LocalPlaylistActions.current
    val playlists = playlistActions.playlists
    var showSmartPlaylistDialog by remember { mutableStateOf(false) }
    if (showSmartPlaylistDialog) {
        SmartPlaylistTemplateDialog(catalog = catalog, onDismiss = { showSmartPlaylistDialog = false })
    }
    var visiblePlaylistsResult by remember { mutableStateOf<List<Playlist>?>(null) }
    LaunchedEffect(playlists, searchQuery) {
        visiblePlaylistsResult = withContext(Dispatchers.Default) {
            filterPlaylistsByQuery(playlists, searchQuery)
        }
    }
    val preparedVisiblePlaylists = visiblePlaylistsResult ?: if (searchQuery.isBlank()) playlists else emptyList()
    val catalogSyncInProgress = LocalCatalogSyncInProgress.current
    val chromePadding = LocalMobileChromePadding.current

    val listState = RetainedLazyListStates.remember("mobile-playlists")
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
            bottom = chromePadding.bottom + MobileChromeScrollGap,
        ),
    ) {
        topBar?.let { header ->
            item(key = "top-bar", contentType = "top-bar") { header() }
        }
        item(contentType = "search") {
            SearchPill(
                query = searchQuery,
                onQueryChange = onSearchQuery,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                placeholder = "Search playlists",
            )
        }
        if (catalogSyncInProgress) {
            item(contentType = "loading") {
                LibraryLoadingStrip(Modifier.padding(bottom = 6.dp))
            }
        }
        if (!playlistActions.playlistsEnabled) {
            item(contentType = "disabled") {
                Column(
                    Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PhoebeIconView(PhoebeIcon.Queue, tint = PhoebeUi.mutedText, modifier = Modifier.size(36.dp))
                    Text(
                        "Sign in to Plex or Jellyfin to browse playlists",
                        color = PhoebeUi.secondaryText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        "Playlists sync from your streaming music library.",
                        color = PhoebeUi.mutedText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        } else {
                item(contentType = "create") {
                    MobilePlaylistRow(
                        icon = PhoebeIcon.Plus,
                        title = "Create Playlist",
                        subtitle = null,
                        accent = false,
                        onClick = { playlistActions.onRequestCreatePlaylist(emptyList()) },
                    )
                }
                item(contentType = "create-smart") {
                    MobilePlaylistRow(
                        icon = PhoebeIcon.InterwovenArrows,
                        title = "Create Smart Playlist",
                        subtitle = null,
                        accent = true,
                        onClick = { showSmartPlaylistDialog = true },
                    )
                }
                if (playlists.isEmpty()) {
                    item(contentType = "empty") {
                        Text(
                            "No playlists yet. Create one or add songs from your library.",
                            color = PhoebeUi.mutedText,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                        )
                    }
                } else if (preparedVisiblePlaylists.isEmpty()) {
                    item(contentType = "empty-filter") {
                        Text(
                            "No playlists match \"$searchQuery\".",
                            color = PhoebeUi.mutedText,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                        )
                    }
                } else {
                    items(preparedVisiblePlaylists, key = { it.id }, contentType = { "playlist" }) { playlist ->
                        val liked = playlist.isLikedSongsPlaylist()
                        val smart = playlist.isSmartPlaylist()
                        MobilePlaylistRow(
                            icon = when {
                                liked -> PhoebeIcon.Heart
                                smart -> PhoebeIcon.InterwovenArrows
                                else -> null
                            },
                            title = playlist.title,
                            subtitle = "${playlist.trackCount} songs",
                            thumbUrl = playlist.thumbUrl,
                            accent = liked || smart,
                            sharedKey = "playlist:${playlist.id}",
                            onClick = { onPlaylist(playlist) },
                            onLongClick = { playlistActions.onShufflePlaylist(playlist) },
                            trailingContent = { PlaylistManagementMenuButton(playlist) },
                        )
                    }
                }
        }
    }
}

@Composable
fun MobilePlaylistRow(
    icon: PhoebeIcon?,
    title: String,
    subtitle: String?,
    thumbUrl: String? = null,
    accent: Boolean = false,
    sharedKey: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            ArtworkImage(
                title,
                thumbUrl,
                Modifier.size(52.dp).sharedArtworkTransition(sharedKey),
                radius = 8.dp,
                elevated = false,
                maxDecodeDimension = ThumbnailArtworkMaxDecodeDimension,
            )
            if (accent || icon != null) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (accent) {
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    listOf(PhoebeUi.accentLight.copy(alpha = 0.82f), Color(0xCC6D45E8)),
                                )
                            } else {
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    listOf(Color.Transparent, Color.Transparent),
                                )
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon != null) {
                        PhoebeIconView(icon, tint = PhoebeUi.primaryText, modifier = Modifier.size(22.dp), filled = accent)
                    }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = PhoebeUi.primaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition(sharedKey?.let { "$it:title" }),
            )
            if (subtitle != null) {
                Text(subtitle, color = PhoebeUi.mutedText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailingContent?.invoke()
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(12.dp))
    }
}
