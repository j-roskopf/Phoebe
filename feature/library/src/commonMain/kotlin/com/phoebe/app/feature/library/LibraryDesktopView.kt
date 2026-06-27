package com.phoebe.app.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.phoebe.app.data.catalogAlbumBitrateKbps
import com.phoebe.app.data.catalogAlbumCodec
import com.phoebe.app.data.catalogAlbumGenre
import com.phoebe.app.data.catalogAlbumTotalDurationMs
import com.phoebe.app.data.catalogAlbumTrackCount
import com.phoebe.app.data.catalogTracksForAlbum
import com.phoebe.app.data.PlayHistorySnapshot
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
import com.phoebe.app.domain.isLikedSongsPlaylist
import com.phoebe.app.domain.isRemoteLibraryTrack
import com.phoebe.app.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LibraryFilterTab { Artists, Albums, Songs }

enum class LibraryOrder { Asc, Desc }

enum class AlbumSortKey { RecentlyAdded, Name, Year, Artist }

enum class SongFileFilter { All, Lossless, Lossy }

enum class LibraryViewMode { Grid, List }

private const val JellyfinLibraryPageSize = 100

data class LibraryPage<T>(
    val items: List<T>,
    val pageIndex: Int,
    val pageCount: Int,
    val totalCount: Int,
) {
    val firstItemNumber: Int get() = if (totalCount == 0) 0 else pageIndex * JellyfinLibraryPageSize + 1
    val lastItemNumber: Int get() = (pageIndex * JellyfinLibraryPageSize + items.size).coerceAtMost(totalCount)
}

fun <T> libraryPage(items: List<T>, enabled: Boolean, pageIndex: Int, totalCountOverride: Int? = null): LibraryPage<T> {
    val totalCount = totalCountOverride ?: items.size
    if (!enabled) {
        return LibraryPage(items = items, pageIndex = 0, pageCount = 1, totalCount = totalCount)
    }
    if (totalCount <= JellyfinLibraryPageSize) {
        return LibraryPage(items = items.take(JellyfinLibraryPageSize), pageIndex = 0, pageCount = 1, totalCount = totalCount)
    }
    val pageCount = ((totalCount + JellyfinLibraryPageSize - 1) / JellyfinLibraryPageSize).coerceAtLeast(1)
    val safeIndex = pageIndex.coerceIn(0, pageCount - 1)
    val start = safeIndex * JellyfinLibraryPageSize
    return LibraryPage(
        items = items.drop(start).take(JellyfinLibraryPageSize),
        pageIndex = safeIndex,
        pageCount = pageCount,
        totalCount = totalCount,
    )
}

fun LibraryFilterTab.toJellyfinPageKind(): JellyfinLibraryPageKind = when (this) {
    LibraryFilterTab.Artists -> JellyfinLibraryPageKind.Artists
    LibraryFilterTab.Albums -> JellyfinLibraryPageKind.Albums
    LibraryFilterTab.Songs -> JellyfinLibraryPageKind.Tracks
}

fun CatalogSnapshot.remoteTotalFor(filter: LibraryFilterTab): Int? = when (filter) {
    LibraryFilterTab.Artists -> remotePageInfo.artistTotal
    LibraryFilterTab.Albums -> remotePageInfo.albumTotal
    LibraryFilterTab.Songs -> remotePageInfo.trackTotal
}

@Composable
fun LibraryPaginationControls(
    page: LibraryPage<*>,
    onPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (page.pageCount <= 1) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compact = maxWidth < 430.dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LibraryToolbarButton(
                icon = PhoebeIcon.Back,
                label = if (compact) "Prev" else "Previous",
                enabled = page.pageIndex > 0,
                onClick = { onPage(page.pageIndex - 1) },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    "Page ${page.pageIndex + 1} of ${page.pageCount}",
                    color = PhoebeUi.primaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact) {
                    Text(
                        "${page.firstItemNumber}-${page.lastItemNumber} of ${page.totalCount}",
                        color = PhoebeUi.mutedText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            LibraryToolbarButton(
                icon = PhoebeIcon.Forward,
                label = "Next",
                enabled = page.pageIndex < page.pageCount - 1,
                onClick = { onPage(page.pageIndex + 1) },
            )
        }
    }
}

@Composable
fun FavoriteArtistsDesktopView(
    catalog: CatalogSnapshot,
    libraryUi: LibraryUiPreferences,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    onLibrarySortBy: (LibrarySortBy) -> Unit,
    onLibraryAscending: (Boolean) -> Unit,
    onArtist: (Artist) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewMode by remember { mutableStateOf(LibraryViewMode.Grid) }
    val sortedArtists = remember(catalog, libraryUi.sortBy, libraryUi.ascending) {
        sortArtistsForLibrary(catalog, libraryUi.sortBy, libraryUi.ascending).filter { it.favorite }
    }
    var visibleArtistsResult by remember { mutableStateOf<List<Artist>?>(null) }
    LaunchedEffect(sortedArtists, searchQuery) {
        visibleArtistsResult = withContext(Dispatchers.Default) {
            filterArtistsByQuery(sortedArtists, searchQuery)
        }
    }
    val visibleArtists = visibleArtistsResult ?: if (searchQuery.isBlank()) sortedArtists else emptyList()
    FavoriteLibraryDesktopScaffold(
        title = "Favorite Artists",
        countLabel = "${sortedArtists.size} artists",
        searchQuery = searchQuery,
        onSearchQuery = onSearchQuery,
        onBack = onBack,
        toolbar = {
            FavoriteLibraryToolbar(
                filter = LibraryFilterTab.Artists,
                prefs = libraryUi,
                viewMode = viewMode,
                onViewMode = { viewMode = it },
                onSortBy = onLibrarySortBy,
                onAscending = onLibraryAscending,
            )
        },
        modifier = modifier,
    ) {
        ArtistsContent(
            catalog = catalog,
            artists = visibleArtists,
            viewMode = viewMode,
            artistGridItemSizeDp = libraryUi.artistGridItemSizeDp,
            sortBy = libraryUi.sortBy,
            ascending = libraryUi.ascending,
            onArtist = onArtist,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun FavoriteAlbumsDesktopView(
    catalog: CatalogSnapshot,
    libraryUi: LibraryUiPreferences,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    onLibrarySortBy: (LibrarySortBy) -> Unit,
    onLibraryAscending: (Boolean) -> Unit,
    onAlbum: (Album) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    var viewMode by remember { mutableStateOf(LibraryViewMode.Grid) }
    val sortedAlbums = remember(catalog.albums, libraryUi.sortBy, libraryUi.ascending) {
        sortAlbumsForLibrary(catalog.albums, libraryUi.sortBy, libraryUi.ascending).filter { it.favorite }
    }
    var visibleAlbumsResult by remember { mutableStateOf<List<Album>?>(null) }
    LaunchedEffect(sortedAlbums, searchQuery) {
        visibleAlbumsResult = withContext(Dispatchers.Default) {
            filterAlbumsByQuery(sortedAlbums, searchQuery)
        }
    }
    val visibleAlbums = visibleAlbumsResult ?: if (searchQuery.isBlank()) sortedAlbums else emptyList()
    LaunchedEffect(visibleAlbums.firstOrNull()?.id) {
        if (selectedAlbumId == null) selectedAlbumId = visibleAlbums.firstOrNull()?.id
    }
    FavoriteLibraryDesktopScaffold(
        title = "Favorite Albums",
        countLabel = "${sortedAlbums.size} albums",
        searchQuery = searchQuery,
        onSearchQuery = onSearchQuery,
        onBack = onBack,
        toolbar = {
            FavoriteLibraryToolbar(
                filter = LibraryFilterTab.Albums,
                prefs = libraryUi,
                viewMode = viewMode,
                onViewMode = { viewMode = it },
                onSortBy = onLibrarySortBy,
                onAscending = onLibraryAscending,
            )
        },
        modifier = modifier,
    ) {
        AlbumsGrid(
            catalog = catalog,
            albums = visibleAlbums,
            selectedAlbumId = selectedAlbumId,
            viewMode = viewMode,
            albumGridItemSizeDp = libraryUi.albumGridItemSizeDp,
            sortBy = libraryUi.sortBy,
            ascending = libraryUi.ascending,
            onSelect = { selectedAlbumId = it.id },
            onOpen = onAlbum,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun FavoritePlaylistsDesktopView(
    playlists: List<Playlist>,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val favoritePlaylists = remember(playlists) {
        playlists.filter { it.favorite }.sortedBy { it.title.lowercase() }
    }
    var visiblePlaylistsResult by remember { mutableStateOf<List<Playlist>?>(null) }
    LaunchedEffect(favoritePlaylists, searchQuery) {
        visiblePlaylistsResult = withContext(Dispatchers.Default) {
            filterPlaylistsByQuery(favoritePlaylists, searchQuery)
        }
    }
    val visiblePlaylists = visiblePlaylistsResult ?: if (searchQuery.isBlank()) favoritePlaylists else emptyList()
    FavoriteLibraryDesktopScaffold(
        title = "Favorite Playlists",
        countLabel = "${favoritePlaylists.size} playlists",
        searchQuery = searchQuery,
        onSearchQuery = onSearchQuery,
        onBack = onBack,
        toolbar = {},
        modifier = modifier,
    ) {
        if (favoritePlaylists.isEmpty()) {
            Text("Favorite playlists will appear here.", color = PhoebeUi.mutedText, fontSize = 14.sp)
        } else if (visiblePlaylists.isEmpty()) {
            Text("No favorite playlists match \"$searchQuery\".", color = PhoebeUi.mutedText, fontSize = 14.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                items(visiblePlaylists, key = { it.id }, contentType = { "favorite-playlist" }) { playlist ->
                    val liked = playlist.isLikedSongsPlaylist()
                    PlaylistRow(
                        icon = if (liked) PhoebeIcon.Heart else null,
                        title = playlist.title,
                        subtitle = "${playlist.trackCount} songs",
                        thumbUrl = playlist.thumbUrl,
                        accent = liked,
                        onClick = { onPlaylist(playlist) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteLibraryDesktopScaffold(
    title: String,
    countLabel: String,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    onBack: () -> Unit,
    toolbar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(
                start = PhoebeDesktopLayout.contentStart,
                top = PhoebeDesktopLayout.contentTop,
                end = PhoebeDesktopLayout.contentEnd,
                bottom = PhoebeDesktopLayout.contentBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            DetailBackButton(onBack = onBack)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = PhoebeUi.primaryText, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text(countLabel, color = PhoebeUi.mutedText, fontSize = 13.sp)
            }
            SearchPill(searchQuery, onSearchQuery, Modifier.width(PhoebeDesktopLayout.searchWidth))
        }
        toolbar()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun FavoriteLibraryToolbar(
    filter: LibraryFilterTab,
    prefs: LibraryUiPreferences,
    viewMode: LibraryViewMode,
    onViewMode: (LibraryViewMode) -> Unit,
    onSortBy: (LibrarySortBy) -> Unit,
    onAscending: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
    ) {
        LibraryDropdown(label = "Sort by", value = sortLabelFor(filter, prefs.sortBy)) { close ->
            when (filter) {
                LibraryFilterTab.Artists -> {
                    DropdownMenuItem(text = { Text("Artist name") }, onClick = { onSortBy(LibrarySortBy.Name); close() })
                    DropdownMenuItem(text = { Text("Date added") }, onClick = { onSortBy(LibrarySortBy.DateAdded); close() })
                }
                LibraryFilterTab.Albums -> {
                    DropdownMenuItem(text = { Text("Album name") }, onClick = { onSortBy(LibrarySortBy.Name); close() })
                    DropdownMenuItem(text = { Text("Artist") }, onClick = { onSortBy(LibrarySortBy.Artist); close() })
                    DropdownMenuItem(text = { Text("Release date") }, onClick = { onSortBy(LibrarySortBy.Year); close() })
                    DropdownMenuItem(text = { Text("Date added") }, onClick = { onSortBy(LibrarySortBy.DateAdded); close() })
                }
                LibraryFilterTab.Songs -> Unit
            }
        }
        LibraryDropdown(label = "Order", value = if (prefs.ascending) "A-Z" else "Desc") { close ->
            DropdownMenuItem(text = { Text("A-Z") }, onClick = { onAscending(true); close() })
            DropdownMenuItem(text = { Text("Z-A / Desc") }, onClick = { onAscending(false); close() })
        }
        LibraryDropdown(label = "View", value = if (viewMode == LibraryViewMode.Grid) "Grid" else "List") { close ->
            DropdownMenuItem(text = { Text("Grid") }, onClick = { onViewMode(LibraryViewMode.Grid); close() })
            DropdownMenuItem(text = { Text("List") }, onClick = { onViewMode(LibraryViewMode.List); close() })
        }
    }
}

/** Top-level desktop Library view: header, tabs, toolbar, content, and right detail sidebar. */
@Composable
fun LibraryDesktopView(
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
    detailWidth: androidx.compose.ui.unit.Dp = 278.dp,
    searchQuery: String = "",
    onSearchQuery: (String) -> Unit = {},
    jellyfinPagination: Boolean = false,
    onJellyfinPage: (JellyfinLibraryPageKind, Int) -> Unit = { _, _ -> },
) {
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    var selectedTrackId by remember { mutableStateOf<String?>(null) }
    var libraryViewMode by remember { mutableStateOf(LibraryViewMode.Grid) }
    var songFilter by remember { mutableStateOf(SongFileFilter.All) }
    var pageIndex by remember(filter) { mutableStateOf(0) }

    val ascending = libraryUi.ascending
    val sortBy = libraryUi.sortBy

    // If the persisted sort key doesn't apply to the current tab, repair it once.
    LaunchedEffect(filter, sortBy) {
        val normalized = normalizeSortKey(filter, sortBy)
        if (normalized != sortBy) onLibrarySortBy(normalized)
    }

    val sortedArtists = remember(catalog.artists, sortBy, ascending) {
        sortArtistsForLibrary(catalog, sortBy, ascending)
    }
    val sortedAlbums = remember(catalog.albums, sortBy, ascending) {
        sortAlbumsForLibrary(catalog.albums, sortBy, ascending)
    }
    val allTracks = remember(catalog.tracksByParent) {
        catalog.tracksByParent.values.asSequence().flatten().distinctBy { it.id }.toList()
    }
    val filteredTracks = remember(allTracks, songFilter) {
        when (songFilter) {
            SongFileFilter.All -> allTracks
            SongFileFilter.Lossless -> allTracks.filter { isLossless(it) }
            SongFileFilter.Lossy -> allTracks.filter { !isLossless(it) }
        }
    }
    val sortedTracks = remember(filteredTracks, sortBy, ascending) {
        sortTracksForLibrary(filteredTracks, sortBy, ascending)
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

    // Default selection when the catalog first arrives or tab changes.
    LaunchedEffect(filter, sortedAlbums.firstOrNull()?.id, sortedTracks.firstOrNull()?.id) {
        when (filter) {
            LibraryFilterTab.Albums -> if (selectedAlbumId == null) {
                selectedAlbumId = sortedAlbums.firstOrNull()?.id
            }
            LibraryFilterTab.Songs -> if (selectedTrackId == null) {
                selectedTrackId = sortedTracks.firstOrNull()?.id
            }
            LibraryFilterTab.Artists -> Unit
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val narrowPane = maxWidth < 760.dp
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    start = PhoebeDesktopLayout.contentStart,
                    top = PhoebeDesktopLayout.contentTop,
                    end = PhoebeDesktopLayout.contentEnd,
                    bottom = PhoebeDesktopLayout.contentBottom,
                ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            LibraryHeader(filter, searchQuery, onSearchQuery, narrowPane)
            LibraryToolbarRow(
                filter = filter,
                onFilter = onFilter,
                prefs = libraryUi,
                onSortBy = onLibrarySortBy,
                onAscending = onLibraryAscending,
                onColumns = onLibraryColumns,
                libraryViewMode = libraryViewMode,
                onLibraryViewMode = { libraryViewMode = it },
                songFilter = songFilter,
                onSongFilter = { songFilter = it },
            )
            if (catalogRefreshing) {
                LibraryLoadingStrip(Modifier.padding(top = 2.dp))
            }
            when (filter) {
                LibraryFilterTab.Artists -> LibraryPaginationControls(artistPage, onPage = {
                    if (jellyfinPagination && searchQuery.isBlank()) onJellyfinPage(filter.toJellyfinPageKind(), it)
                    pageIndex = it
                })
                LibraryFilterTab.Albums -> LibraryPaginationControls(albumPage, onPage = {
                    if (jellyfinPagination && searchQuery.isBlank()) onJellyfinPage(filter.toJellyfinPageKind(), it)
                    pageIndex = it
                })
                LibraryFilterTab.Songs -> LibraryPaginationControls(trackPage, onPage = {
                    if (jellyfinPagination && searchQuery.isBlank()) onJellyfinPage(filter.toJellyfinPageKind(), it)
                    pageIndex = it
                })
            }
            if (narrowPane || filter != LibraryFilterTab.Albums) {
                when (filter) {
                    LibraryFilterTab.Artists -> ArtistsContent(
                        catalog = catalog,
                        artists = artistPage.items,
                        viewMode = libraryViewMode,
                        artistGridItemSizeDp = libraryUi.artistGridItemSizeDp,
                        sortBy = sortBy,
                        ascending = ascending,
                        onArtist = onArtist,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    LibraryFilterTab.Albums -> AlbumsGrid(
                        catalog = catalog,
                        albums = albumPage.items,
                        selectedAlbumId = selectedAlbumId,
                        viewMode = libraryViewMode,
                        albumGridItemSizeDp = libraryUi.albumGridItemSizeDp,
                        sortBy = sortBy,
                        ascending = ascending,
                        onSelect = { selectedAlbumId = it.id },
                        onOpen = onAlbum,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    LibraryFilterTab.Songs -> SongsTable(
                        tracks = trackPage.items,
                        selectedTrackId = selectedTrackId,
                        columns = libraryUi.columns,
                        sortBy = sortBy,
                        ascending = ascending,
                        onSelect = { track ->
                            selectedTrackId = track.id
                            trackPage.items.indexOfFirst { it.id == track.id }
                                .takeIf { it >= 0 }
                                ?.let { index -> onPlayTracks(trackPage.items, index) }
                        },
                        onPlay = { index -> onPlayTracks(trackPage.items, index) },
                        onAddToUpNext = onAddToUpNext,
                        onDownload = onDownload,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            } else {
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    AlbumsGrid(
                        catalog = catalog,
                        albums = albumPage.items,
                        selectedAlbumId = selectedAlbumId,
                        viewMode = libraryViewMode,
                        albumGridItemSizeDp = libraryUi.albumGridItemSizeDp,
                        sortBy = sortBy,
                        ascending = ascending,
                        onSelect = { selectedAlbumId = it.id },
                        onOpen = onAlbum,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    Column(
                        modifier = Modifier
                            .width(detailWidth)
                            .fillMaxHeight(),
                    ) {
                        val selected = albumPage.items.firstOrNull { it.id == selectedAlbumId }
                            ?: sortedAlbums.firstOrNull { it.id == selectedAlbumId }
                        if (selected != null) {
                            AlbumDetailSidebar(
                                album = selected,
                                columns = libraryUi.columns,
                                catalog = catalog,
                                onPlayTrack = { tracks, index -> onPlayTracks(tracks, index) },
                            )
                        } else {
                            LibraryEmptyDetail("Select an album to see details.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(
    filter: LibraryFilterTab,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    compact: Boolean,
) {
    if (compact) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = when (filter) {
                    LibraryFilterTab.Artists -> "Artists"
                    LibraryFilterTab.Albums -> "Albums"
                    LibraryFilterTab.Songs -> "Songs"
                },
                color = PhoebeUi.primaryText,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
            )
            Text("Your Library", color = PhoebeUi.mutedText, fontSize = 13.sp)
            SearchPill(searchQuery, onSearchQuery, Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (filter) {
                        LibraryFilterTab.Artists -> "Artists"
                        LibraryFilterTab.Albums -> "Albums"
                        LibraryFilterTab.Songs -> "Songs"
                    },
                    color = PhoebeUi.primaryText,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                )
                Text("Your Library", color = PhoebeUi.mutedText, fontSize = 13.sp)
            }
            SearchPill(searchQuery, onSearchQuery, Modifier.width(PhoebeDesktopLayout.searchWidth))
        }
    }
}

@Composable
private fun LibraryToolbarRow(
    filter: LibraryFilterTab,
    onFilter: (LibraryFilterTab) -> Unit,
    prefs: LibraryUiPreferences,
    onSortBy: (LibrarySortBy) -> Unit,
    onAscending: (Boolean) -> Unit,
    onColumns: (LibraryColumnVisibility) -> Unit,
    libraryViewMode: LibraryViewMode,
    onLibraryViewMode: (LibraryViewMode) -> Unit,
    songFilter: SongFileFilter,
    onSongFilter: (SongFileFilter) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryTabsPill(filter, onFilter)
        Spacer(Modifier.weight(1f))
        LibraryFilterOptionsMenu(
            filter = filter,
            prefs = prefs,
            onSortBy = onSortBy,
            onAscending = onAscending,
            libraryViewMode = libraryViewMode,
            onLibraryViewMode = onLibraryViewMode,
            onColumns = onColumns,
            songFilter = if (filter == LibraryFilterTab.Songs) songFilter else null,
            onSongFilter = if (filter == LibraryFilterTab.Songs) onSongFilter else null,
        )
    }
}

@Composable
private fun LibraryTabsPill(filter: LibraryFilterTab, onFilter: (LibraryFilterTab) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        LibraryFilterTab.entries.forEach { tab ->
            val active = filter == tab
            Text(
                text = when (tab) {
                    LibraryFilterTab.Artists -> "Artists"
                    LibraryFilterTab.Albums -> "Albums"
                    LibraryFilterTab.Songs -> "Songs"
                },
                color = if (active) PhoebeUi.primaryText else PhoebeUi.secondaryText,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .phoebeClickable { onFilter(tab) }
                    .background(if (active) PhoebeUi.accent.copy(alpha = 0.22f) else Color.Transparent)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun LibraryDropdown(
    label: String,
    value: String,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .phoebeClickable { expanded = true }
                .background(Color.White.copy(alpha = 0.04f))
                .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("$label: ", color = PhoebeUi.mutedText, fontSize = 12.sp)
            Text(value, color = PhoebeUi.primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            PhoebeIconView(PhoebeIcon.ChevronDown, tint = PhoebeUi.mutedText, modifier = Modifier.size(12.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

/**
 * Compact toolbar for use inside library detail screens (artist / album detail).
 * Has the same dropdowns as the root library toolbar but no tab pill.
 *
 * Pass null for any control to hide it.
 */
@Composable
fun DetailSectionToolbar(
    sortBy: LibrarySortBy?,
    sortKeys: List<LibrarySortBy>,
    sortLabel: (LibrarySortBy) -> String,
    onSortBy: ((LibrarySortBy) -> Unit)?,
    ascending: Boolean?,
    onAscending: ((Boolean) -> Unit)?,
    viewMode: LibraryViewMode? = null,
    onViewMode: ((LibraryViewMode) -> Unit)? = null,
    columns: LibraryColumnVisibility? = null,
    onColumns: ((LibraryColumnVisibility) -> Unit)? = null,
    reorderMode: Boolean? = null,
    reorderModeAvailable: Boolean = true,
    onReorderMode: ((Boolean) -> Unit)? = null,
    editMode: Boolean? = null,
    editModeAvailable: Boolean = true,
    onEditMode: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
    ) {
        actions()
        LibrarySectionOptionsMenu(
            sortBy = sortBy,
            sortKeys = sortKeys,
            sortLabel = sortLabel,
            onSortBy = onSortBy,
            ascending = ascending,
            onAscending = onAscending,
            viewMode = viewMode,
            onViewMode = onViewMode,
            columns = columns,
            onColumns = onColumns,
            reorderMode = reorderMode,
            reorderModeAvailable = reorderModeAvailable,
            onReorderMode = onReorderMode,
            editMode = editMode,
            editModeAvailable = editModeAvailable,
            onEditMode = onEditMode,
        )
    }
}

@Composable
fun DetailSectionHeader(
    title: String,
    titleColor: Color = PhoebeUi.primaryText,
    sortBy: LibrarySortBy?,
    sortKeys: List<LibrarySortBy>,
    sortLabel: (LibrarySortBy) -> String,
    onSortBy: ((LibrarySortBy) -> Unit)?,
    ascending: Boolean?,
    onAscending: ((Boolean) -> Unit)?,
    viewMode: LibraryViewMode? = null,
    onViewMode: ((LibraryViewMode) -> Unit)? = null,
    columns: LibraryColumnVisibility? = null,
    onColumns: ((LibraryColumnVisibility) -> Unit)? = null,
    reorderMode: Boolean? = null,
    reorderModeAvailable: Boolean = true,
    onReorderMode: ((Boolean) -> Unit)? = null,
    editMode: Boolean? = null,
    editModeAvailable: Boolean = true,
    onEditMode: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel(title, titleColor)
        Spacer(Modifier.weight(1f))
        actions()
        LibrarySectionOptionsMenu(
            sortBy = sortBy,
            sortKeys = sortKeys,
            sortLabel = sortLabel,
            onSortBy = onSortBy,
            ascending = ascending,
            onAscending = onAscending,
            viewMode = viewMode,
            onViewMode = onViewMode,
            columns = columns,
            onColumns = onColumns,
            reorderMode = reorderMode,
            reorderModeAvailable = reorderModeAvailable,
            onReorderMode = onReorderMode,
            editMode = editMode,
            editModeAvailable = editModeAvailable,
            onEditMode = onEditMode,
        )
    }
}

private enum class LibrarySectionOptionsSubmenu { Sort, Order, View, Columns }

@Composable
fun LibrarySectionOptionsMenu(
    sortBy: LibrarySortBy?,
    sortKeys: List<LibrarySortBy>,
    sortLabel: (LibrarySortBy) -> String,
    onSortBy: ((LibrarySortBy) -> Unit)?,
    ascending: Boolean?,
    onAscending: ((Boolean) -> Unit)?,
    viewMode: LibraryViewMode? = null,
    onViewMode: ((LibraryViewMode) -> Unit)? = null,
    columns: LibraryColumnVisibility? = null,
    onColumns: ((LibraryColumnVisibility) -> Unit)? = null,
    reorderMode: Boolean? = null,
    reorderModeAvailable: Boolean = true,
    onReorderMode: ((Boolean) -> Unit)? = null,
    editMode: Boolean? = null,
    editModeAvailable: Boolean = true,
    onEditMode: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val hasSort = sortBy != null && onSortBy != null && sortKeys.isNotEmpty()
    val hasOrder = ascending != null && onAscending != null
    val hasView = viewMode != null && onViewMode != null
    val hasColumns = columns != null && onColumns != null
    val hasReorderMode = reorderMode != null && onReorderMode != null
    val hasEditMode = editMode != null && onEditMode != null
    if (!hasSort && !hasOrder && !hasView && !hasColumns && !hasReorderMode && !hasEditMode) return

    var expanded by remember { mutableStateOf(false) }
    var submenu by remember { mutableStateOf<LibrarySectionOptionsSubmenu?>(null) }
    Box(modifier) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .phoebeClickable { expanded = true }
                .semantics { contentDescription = "Section options" },
            contentAlignment = Alignment.Center,
        ) {
            PhoebeIconView(PhoebeIcon.More, tint = PhoebeUi.mutedText, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                submenu = null
            },
        ) {
            when (submenu) {
                null -> {
                    if (hasSort) {
                        val selectedSortBy = sortBy
                        OptionsNavigationMenuItem(
                            label = "Sort",
                            value = sortLabel(selectedSortBy),
                            onClick = { submenu = LibrarySectionOptionsSubmenu.Sort },
                        )
                    }
                    if (hasOrder) {
                        OptionsNavigationMenuItem(
                            label = "Order",
                            value = if (ascending == true) "Ascending" else "Descending",
                            onClick = { submenu = LibrarySectionOptionsSubmenu.Order },
                        )
                    }
                    if (hasView) {
                        OptionsNavigationMenuItem(
                            label = "View",
                            value = if (viewMode == LibraryViewMode.Grid) "Grid" else "List",
                            onClick = { submenu = LibrarySectionOptionsSubmenu.View },
                        )
                    }
                    if (hasColumns) {
                        OptionsNavigationMenuItem(
                            label = "Columns",
                            onClick = { submenu = LibrarySectionOptionsSubmenu.Columns },
                        )
                    }
                    if (hasReorderMode) {
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.width(220.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (reorderMode == true) {
                                        PhoebeIconView(PhoebeIcon.Check, tint = PhoebeUi.accentLight, modifier = Modifier.size(14.dp))
                                    } else {
                                        Spacer(Modifier.size(14.dp))
                                    }
                                    Text("Reorder mode")
                                }
                            },
                            enabled = reorderModeAvailable || reorderMode == true,
                            onClick = {
                                onReorderMode(reorderMode != true)
                                expanded = false
                                submenu = null
                            },
                        )
                    }
                    if (hasEditMode) {
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.width(220.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (editMode == true) {
                                        PhoebeIconView(PhoebeIcon.Check, tint = PhoebeUi.accentLight, modifier = Modifier.size(14.dp))
                                    } else {
                                        Spacer(Modifier.size(14.dp))
                                    }
                                    Text("Edit mode")
                                }
                            },
                            enabled = editModeAvailable || editMode == true,
                            onClick = {
                                onEditMode(editMode != true)
                                expanded = false
                                submenu = null
                            },
                        )
                    }
                }
                LibrarySectionOptionsSubmenu.Sort -> {
                    OptionsBackMenuItem("Sort") { submenu = null }
                    if (hasSort) {
                        val selectedSortBy = sortBy
                        val selectSortBy = onSortBy
                        sortKeys.forEach { key ->
                            SelectableOptionsMenuItem(
                                label = sortLabel(key),
                                selected = key == selectedSortBy,
                                onClick = {
                                    selectSortBy(key)
                                    expanded = false
                                    submenu = null
                                },
                            )
                        }
                    }
                }
                LibrarySectionOptionsSubmenu.Order -> {
                    OptionsBackMenuItem("Order") { submenu = null }
                    if (hasOrder) {
                        val selectAscending = onAscending
                        SelectableOptionsMenuItem(
                            label = "Ascending",
                            selected = ascending == true,
                            onClick = {
                                selectAscending(true)
                                expanded = false
                                submenu = null
                            },
                        )
                        SelectableOptionsMenuItem(
                            label = "Descending",
                            selected = ascending == false,
                            onClick = {
                                selectAscending(false)
                                expanded = false
                                submenu = null
                            },
                        )
                    }
                }
                LibrarySectionOptionsSubmenu.View -> {
                    OptionsBackMenuItem("View") { submenu = null }
                    if (hasView) {
                        val selectViewMode = onViewMode
                        SelectableOptionsMenuItem(
                            label = "Grid",
                            selected = viewMode == LibraryViewMode.Grid,
                            onClick = {
                                selectViewMode(LibraryViewMode.Grid)
                                expanded = false
                                submenu = null
                            },
                        )
                        SelectableOptionsMenuItem(
                            label = "List",
                            selected = viewMode == LibraryViewMode.List,
                            onClick = {
                                selectViewMode(LibraryViewMode.List)
                                expanded = false
                                submenu = null
                            },
                        )
                    }
                }
                LibrarySectionOptionsSubmenu.Columns -> {
                    OptionsBackMenuItem("Columns") { submenu = null }
                    if (hasColumns) {
                        val currentColumns = columns
                        val updateColumns = onColumns
                        ColumnsToggleRow("Duration", currentColumns.duration) { updateColumns(currentColumns.copy(duration = !currentColumns.duration)) }
                        ColumnsToggleRow("Audio codec", currentColumns.audioCodec) { updateColumns(currentColumns.copy(audioCodec = !currentColumns.audioCodec)) }
                        ColumnsToggleRow("Bitrate", currentColumns.bitrate) { updateColumns(currentColumns.copy(bitrate = !currentColumns.bitrate)) }
                        ColumnsToggleRow("Sample rate", currentColumns.sampleRate) { updateColumns(currentColumns.copy(sampleRate = !currentColumns.sampleRate)) }
                        ColumnsToggleRow("File type", currentColumns.fileType) { updateColumns(currentColumns.copy(fileType = !currentColumns.fileType)) }
                        ColumnsToggleRow("Date added", currentColumns.dateAdded) { updateColumns(currentColumns.copy(dateAdded = !currentColumns.dateAdded)) }
                        ColumnsToggleRow("Rating", currentColumns.rating) { updateColumns(currentColumns.copy(rating = !currentColumns.rating)) }
                        ColumnsToggleRow("Favorite", currentColumns.favorite) { updateColumns(currentColumns.copy(favorite = !currentColumns.favorite)) }
                        ColumnsToggleRow("File path", currentColumns.filepath) { updateColumns(currentColumns.copy(filepath = !currentColumns.filepath)) }
                        ColumnsToggleRow("Year", currentColumns.year) { updateColumns(currentColumns.copy(year = !currentColumns.year)) }
                        ColumnsToggleRow("Genre", currentColumns.genre) { updateColumns(currentColumns.copy(genre = !currentColumns.genre)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionsNavigationMenuItem(
    label: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.width(220.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = PhoebeUi.primaryText, fontWeight = FontWeight.SemiBold)
                if (value != null) {
                    Text(
                        value,
                        color = PhoebeUi.mutedText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                PhoebeIconView(PhoebeIcon.ChevronRight, tint = PhoebeUi.mutedText, modifier = Modifier.size(14.dp))
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun OptionsBackMenuItem(
    label: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.width(220.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhoebeIconView(PhoebeIcon.Back, tint = PhoebeUi.mutedText, modifier = Modifier.size(14.dp))
                Text(label, color = PhoebeUi.primaryText, fontWeight = FontWeight.SemiBold)
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun SelectableOptionsMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.width(220.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected) {
                    PhoebeIconView(PhoebeIcon.Check, tint = PhoebeUi.accentLight, modifier = Modifier.size(14.dp))
                } else {
                    Spacer(Modifier.size(14.dp))
                }
                Text(label)
            }
        },
        onClick = onClick,
    )
}

@Composable
fun LibraryFilterOptionsMenu(
    filter: LibraryFilterTab,
    prefs: LibraryUiPreferences,
    onSortBy: (LibrarySortBy) -> Unit,
    onAscending: (Boolean) -> Unit,
    libraryViewMode: LibraryViewMode,
    onLibraryViewMode: (LibraryViewMode) -> Unit,
    onColumns: (LibraryColumnVisibility) -> Unit,
    songFilter: SongFileFilter? = null,
    onSongFilter: ((SongFileFilter) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .phoebeClickable { expanded = true }
                .semantics { contentDescription = "Library options" },
            contentAlignment = Alignment.Center,
        ) {
            PhoebeIconView(PhoebeIcon.More, tint = PhoebeUi.mutedText, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LibraryFilterOptionsMenuItems(
                filter = filter,
                prefs = prefs,
                onSortBy = onSortBy,
                onAscending = onAscending,
                libraryViewMode = libraryViewMode,
                onLibraryViewMode = onLibraryViewMode,
                onColumns = onColumns,
                songFilter = songFilter,
                onSongFilter = onSongFilter,
                onDismiss = { expanded = false },
            )
        }
    }
}

@Composable
fun LibraryFilterOptionsMenuItems(
    filter: LibraryFilterTab,
    prefs: LibraryUiPreferences,
    onSortBy: (LibrarySortBy) -> Unit,
    onAscending: (Boolean) -> Unit,
    libraryViewMode: LibraryViewMode,
    onLibraryViewMode: (LibraryViewMode) -> Unit,
    onColumns: (LibraryColumnVisibility) -> Unit,
    songFilter: SongFileFilter? = null,
    onSongFilter: ((SongFileFilter) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    sortKeysFor(filter).forEach { key ->
        DropdownMenuItem(
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (key == prefs.sortBy) {
                        PhoebeIconView(PhoebeIcon.Check, tint = PhoebeUi.accentLight, modifier = Modifier.size(14.dp))
                    } else {
                        Spacer(Modifier.size(14.dp))
                    }
                    Text("Sort: ${sortLabelFor(filter, key)}")
                }
            },
            onClick = {
                onSortBy(key)
                onDismiss()
            },
        )
    }
    OrderMenuItem(
        label = "Ascending",
        selected = prefs.ascending,
        onClick = {
            onAscending(true)
            onDismiss()
        },
    )
    OrderMenuItem(
        label = "Descending",
        selected = !prefs.ascending,
        onClick = {
            onAscending(false)
            onDismiss()
        },
    )
    if (filter != LibraryFilterTab.Songs) {
        DropdownMenuItem(
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (libraryViewMode == LibraryViewMode.Grid) {
                        PhoebeIconView(PhoebeIcon.Check, tint = PhoebeUi.accentLight, modifier = Modifier.size(14.dp))
                    } else {
                        Spacer(Modifier.size(14.dp))
                    }
                    Text("View: Grid")
                }
            },
            onClick = {
                onLibraryViewMode(LibraryViewMode.Grid)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (libraryViewMode == LibraryViewMode.List) {
                        PhoebeIconView(PhoebeIcon.Check, tint = PhoebeUi.accentLight, modifier = Modifier.size(14.dp))
                    } else {
                        Spacer(Modifier.size(14.dp))
                    }
                    Text("View: List")
                }
            },
            onClick = {
                onLibraryViewMode(LibraryViewMode.List)
                onDismiss()
            },
        )
    }
    if (filter == LibraryFilterTab.Songs && songFilter != null && onSongFilter != null) {
        SongFileFilter.entries.forEach { option ->
            DropdownMenuItem(
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (option == songFilter) {
                            PhoebeIconView(PhoebeIcon.Check, tint = PhoebeUi.accentLight, modifier = Modifier.size(14.dp))
                        } else {
                            Spacer(Modifier.size(14.dp))
                        }
                        Text(
                            when (option) {
                                SongFileFilter.All -> "Filter: All files"
                                SongFileFilter.Lossless -> "Filter: Lossless"
                                SongFileFilter.Lossy -> "Filter: Lossy"
                            },
                        )
                    }
                },
                onClick = {
                    onSongFilter(option)
                    onDismiss()
                },
            )
        }
    }
    if (filter == LibraryFilterTab.Songs) {
        DropdownMenuItem(
            text = { Text("Columns", color = PhoebeUi.mutedText, fontWeight = FontWeight.SemiBold) },
            onClick = {},
            enabled = false,
        )
        val columns = prefs.columns
        ColumnsToggleRow("Duration", columns.duration) { onColumns(columns.copy(duration = !columns.duration)) }
        ColumnsToggleRow("Audio codec", columns.audioCodec) { onColumns(columns.copy(audioCodec = !columns.audioCodec)) }
        ColumnsToggleRow("Bitrate", columns.bitrate) { onColumns(columns.copy(bitrate = !columns.bitrate)) }
        ColumnsToggleRow("Sample rate", columns.sampleRate) { onColumns(columns.copy(sampleRate = !columns.sampleRate)) }
        ColumnsToggleRow("File type", columns.fileType) { onColumns(columns.copy(fileType = !columns.fileType)) }
        ColumnsToggleRow("Date added", columns.dateAdded) { onColumns(columns.copy(dateAdded = !columns.dateAdded)) }
        ColumnsToggleRow("Rating", columns.rating) { onColumns(columns.copy(rating = !columns.rating)) }
        ColumnsToggleRow("Favorite", columns.favorite) { onColumns(columns.copy(favorite = !columns.favorite)) }
        ColumnsToggleRow("File path", columns.filepath) { onColumns(columns.copy(filepath = !columns.filepath)) }
        ColumnsToggleRow("Year", columns.year) { onColumns(columns.copy(year = !columns.year)) }
        ColumnsToggleRow("Genre", columns.genre) { onColumns(columns.copy(genre = !columns.genre)) }
    }
}

@Composable
private fun OrderMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    PhoebeIconView(PhoebeIcon.Check, tint = PhoebeUi.accentLight, modifier = Modifier.size(14.dp))
                } else {
                    Spacer(Modifier.size(14.dp))
                }
                Text("Order: $label")
            }
        },
        onClick = onClick,
    )
}

private const val LibraryGridWidthDebounceMs = 200L
private const val LibraryGridWidthBucketDp = 64f

fun libraryAdaptiveColumnCount(
    availableWidth: Dp,
    itemSizeDp: Int,
    gap: Dp = 14.dp,
): Int {
    val itemWidth = itemSizeDp.dp
    if (availableWidth <= 0.dp) return 1
    return ((availableWidth + gap) / (itemWidth + gap)).toInt().coerceAtLeast(1)
}

@Composable
fun rememberLibraryGridColumnCount(
    availableWidth: Dp,
    itemSizeDp: Int,
    horizontalSpacing: Dp,
): Int {
    var settledWidth by remember { mutableStateOf(availableWidth) }
    LaunchedEffect(availableWidth) {
        delay(LibraryGridWidthDebounceMs)
        settledWidth = availableWidth
    }
    val widthBucket = (settledWidth.value / LibraryGridWidthBucketDp).roundToInt().coerceAtLeast(1)
    return remember(widthBucket, itemSizeDp, horizontalSpacing) {
        libraryAdaptiveColumnCount(
            availableWidth = (widthBucket * LibraryGridWidthBucketDp).dp,
            itemSizeDp = itemSizeDp,
            gap = horizontalSpacing,
        )
    }
}

@Composable
fun LibraryResponsiveGrid(
    itemSizeDp: Int,
    horizontalSpacing: Dp,
    verticalSpacing: Dp,
    state: LazyGridState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    content: LazyGridScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val columnCount = rememberLibraryGridColumnCount(
            availableWidth = maxWidth,
            itemSizeDp = itemSizeDp,
            horizontalSpacing = horizontalSpacing,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            state = state,
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
    }
}

@Composable
fun ColumnsPickerButton(columns: LibraryColumnVisibility, onColumns: (LibraryColumnVisibility) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .phoebeClickable { expanded = true }
                .background(Color.White.copy(alpha = 0.04f))
                .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhoebeIconView(PhoebeIcon.Library, tint = PhoebeUi.secondaryText, modifier = Modifier.size(13.dp))
            Text("Columns", color = PhoebeUi.primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ColumnsToggleRow("Duration", columns.duration) { onColumns(columns.copy(duration = !columns.duration)) }
            ColumnsToggleRow("Audio codec", columns.audioCodec) { onColumns(columns.copy(audioCodec = !columns.audioCodec)) }
            ColumnsToggleRow("Bitrate", columns.bitrate) { onColumns(columns.copy(bitrate = !columns.bitrate)) }
            ColumnsToggleRow("Sample rate", columns.sampleRate) { onColumns(columns.copy(sampleRate = !columns.sampleRate)) }
            ColumnsToggleRow("File type", columns.fileType) { onColumns(columns.copy(fileType = !columns.fileType)) }
            ColumnsToggleRow("Date added", columns.dateAdded) { onColumns(columns.copy(dateAdded = !columns.dateAdded)) }
            ColumnsToggleRow("Rating", columns.rating) { onColumns(columns.copy(rating = !columns.rating)) }
            ColumnsToggleRow("Favorite", columns.favorite) { onColumns(columns.copy(favorite = !columns.favorite)) }
            ColumnsToggleRow("File path", columns.filepath) { onColumns(columns.copy(filepath = !columns.filepath)) }
            ColumnsToggleRow("Year", columns.year) { onColumns(columns.copy(year = !columns.year)) }
            ColumnsToggleRow("Genre", columns.genre) { onColumns(columns.copy(genre = !columns.genre)) }
        }
    }
}

@Composable
private fun ColumnsToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .phoebeClickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LibraryCheckbox(checked = checked)
        Text(label, color = PhoebeUi.primaryText, fontSize = 13.sp)
    }
}

@Composable
fun LibraryCheckbox(checked: Boolean, size: Int = 16) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (checked) PhoebeUi.accentLight else Color.Transparent)
            .border(
                BorderStroke(1.dp, if (checked) PhoebeUi.accentLight else PhoebeUi.mutedText),
                RoundedCornerShape(4.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Canvas(Modifier.size((size - 4).dp)) {
                val w = this.size.width
                val h = this.size.height
                val stroke = (w * 0.18f).coerceAtLeast(1.5f)
                val p1 = Offset(w * 0.10f, h * 0.55f)
                val p2 = Offset(w * 0.42f, h * 0.82f)
                val p3 = Offset(w * 0.92f, h * 0.22f)
                drawLine(color = Color.White, start = p1, end = p2, strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(color = Color.White, start = p2, end = p3, strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
fun PlaylistEditActionBar(
    selectedCount: Int,
    totalCount: Int,
    onRemove: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (selectedCount == 0) "Select songs to remove" else "$selectedCount selected",
            color = PhoebeUi.secondaryText,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        if (totalCount > 0 && selectedCount < totalCount) {
            PlaylistEditSecondaryAction("Select all", onSelectAll)
        } else if (selectedCount > 0) {
            PlaylistEditSecondaryAction("Clear", onClearSelection)
        }
        PlaylistEditRemoveButton(
            selectedCount = selectedCount,
            onRemove = onRemove,
        )
    }
}

@Composable
fun PlaylistEditBottomBar(
    selectedCount: Int,
    totalCount: Int,
    onRemove: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(PhoebeUi.shellTop)
            .border(BorderStroke(1.dp, PhoebeUi.border))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (selectedCount == 0) "Select songs to remove" else "$selectedCount selected",
                color = PhoebeUi.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (totalCount > 0) {
                Text(
                    if (selectedCount < totalCount) "Tap Select all or choose songs" else "All visible songs selected",
                    color = PhoebeUi.mutedText,
                    fontSize = 11.sp,
                )
            }
        }
        if (totalCount > 0 && selectedCount < totalCount) {
            PlaylistEditSecondaryAction("Select all", onSelectAll)
        } else if (selectedCount > 0) {
            PlaylistEditSecondaryAction("Clear", onClearSelection)
        }
        PlaylistEditSecondaryAction("Done", onDone)
        PlaylistEditRemoveButton(
            selectedCount = selectedCount,
            onRemove = onRemove,
            compact = false,
        )
    }
}

@Composable
private fun PlaylistEditSecondaryAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = PhoebeUi.accentLight,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .phoebeClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun PlaylistEditRemoveButton(
    selectedCount: Int,
    onRemove: () -> Unit,
    compact: Boolean = true,
) {
    val enabled = selectedCount > 0
    val label = if (selectedCount > 0) "Remove ($selectedCount)" else "Remove"
    Row(
        Modifier
            .clip(RoundedCornerShape(if (compact) 8.dp else 999.dp))
            .background(
                if (enabled) PhoebeUi.accent.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.06f),
            )
            .border(
                BorderStroke(1.dp, if (enabled) PhoebeUi.accentLight.copy(alpha = 0.35f) else PhoebeUi.border),
                RoundedCornerShape(if (compact) 8.dp else 999.dp),
            )
            .phoebeClickable(enabled = enabled, onClick = onRemove)
            .padding(
                horizontal = if (compact) 10.dp else 16.dp,
                vertical = if (compact) 6.dp else 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            color = if (enabled) PhoebeUi.primaryText else PhoebeUi.mutedText,
            fontSize = if (compact) 12.sp else 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun LibraryEmptyDetail(message: String) {
    Box(Modifier.fillMaxSize().padding(top = 36.dp), contentAlignment = Alignment.TopCenter) {
        Text(message, color = PhoebeUi.mutedText, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun LibraryLoadingStrip(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = PhoebeUi.accentLight,
            trackColor = Color.White.copy(alpha = 0.08f),
        )
    }
}

// =====================================================================
// Artists (grid/list)
// =====================================================================

@Composable
private fun ArtistsContent(
    catalog: CatalogSnapshot,
    artists: List<Artist>,
    viewMode: LibraryViewMode,
    artistGridItemSizeDp: Int,
    sortBy: LibrarySortBy,
    ascending: Boolean,
    onArtist: (Artist) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (artists.isEmpty()) {
        Box(modifier.padding(top = 24.dp)) {
            Text("No artists yet.", color = PhoebeUi.mutedText, fontSize = 13.sp)
        }
        return
    }
    val indexEntries = remember(artists, sortBy, ascending) {
        libraryArtistScrollIndex(artists, sortBy, ascending)
    }
    val scope = rememberCoroutineScope()
    val indexScrollDispatcher = rememberLibrarySectionIndexSelectionDispatcher()
    var sectionIndexScrubbing by remember { mutableStateOf(false) }
    when (viewMode) {
        LibraryViewMode.Grid -> {
            val gridState = rememberLazyGridState()
            val revealIndex by remember(gridState) { derivedStateOf { gridState.isScrollInProgress } }
            Box(modifier) {
                LibraryResponsiveGrid(
                    itemSizeDp = artistGridItemSizeDp,
                    horizontalSpacing = 18.dp,
                    verticalSpacing = 20.dp,
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(artists, key = { it.id }, contentType = { "artist-card" }) { artist ->
                        val onArtistClick = remember(artist, onArtist) { { onArtist(artist) } }
                        ArtistCard(
                            artist = artist,
                            artworkDecodeDimension = libraryGridDecodeDimension(artistGridItemSizeDp),
                            onArtist = onArtistClick,
                        )
                    }
                }
                LibrarySectionIndex(
                    entries = indexEntries,
                    onEntrySelected = { entry ->
                        indexScrollDispatcher.launch(scope, key = entry.itemIndex) { gridState.scrollToItem(entry.itemIndex) }
                    },
                    onScrubbingChanged = { sectionIndexScrubbing = it },
                    mode = LibrarySectionIndexMode.DesktopScrollbar,
                    revealSignal = revealIndex,
                    scrollbarStateProvider = {
                        LibraryScrollbarState(
                            firstVisibleItemIndex = gridState.firstVisibleItemIndex,
                            visibleItemsCount = gridState.layoutInfo.visibleItemsInfo.size,
                            totalItemsCount = gridState.layoutInfo.totalItemsCount,
                        )
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        LibraryViewMode.List -> {
            val listState = rememberLazyListState()
            val artistStats = remember(catalog.albums, catalog.tracksByParent) {
                buildArtistListRowStats(catalog)
            }
            val revealIndex by remember(listState) { derivedStateOf { listState.isScrollInProgress } }
            val scrollbarState by remember(listState) {
                derivedStateOf {
                    LibraryScrollbarState(
                        firstVisibleItemIndex = listState.firstVisibleItemIndex,
                        visibleItemsCount = listState.layoutInfo.visibleItemsInfo.size,
                        totalItemsCount = listState.layoutInfo.totalItemsCount,
                    )
                }
            }
            Box(modifier) {
                Column(Modifier.fillMaxSize()) {
                    ArtistsTableHeader()
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(artists, key = { it.id }, contentType = { "artist" }) { artist ->
                            val onArtistClick = remember(artist, onArtist) { { onArtist(artist) } }
                            ArtistRow(
                                artist = artist,
                                stats = artistStats[artist.title.artistStatsKey()],
                                onArtist = onArtistClick,
                            )
                        }
                    }
                }
                LibrarySectionIndex(
                    entries = indexEntries,
                    onEntrySelected = { entry ->
                        indexScrollDispatcher.launch(scope, key = entry.itemIndex) { listState.scrollToItem(entry.itemIndex) }
                    },
                    onScrubbingChanged = { sectionIndexScrubbing = it },
                    mode = LibrarySectionIndexMode.DesktopScrollbar,
                    revealSignal = revealIndex,
                    scrollbarState = scrollbarState,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

@Composable
private fun ArtistCard(
    artist: Artist,
    artworkDecodeDimension: Int,
    onArtist: () -> Unit,
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
            .clip(RoundedCornerShape(14.dp))
            .phoebeClickable(onClick = onArtist)
            .padding(horizontal = 10.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .sharedArtworkTransition("artist:${artist.id}")
                .clip(CircleShape),
        ) {
            ArtworkImage(
                artist.title,
                artist.thumbUrl,
                Modifier.fillMaxSize(),
                radius = 999.dp,
                elevated = false,
                maxDecodeDimension = artworkDecodeDimension,
            )
        }
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
private fun ArtistsTableHeader() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TableHeaderCell("Artist", modifier = Modifier.weight(2.4f).padding(start = 56.dp))
        TableHeaderCell("Genre", modifier = Modifier.weight(1.6f))
        TableHeaderCell("Albums", modifier = Modifier.width(70.dp))
        TableHeaderCell("Songs", modifier = Modifier.width(70.dp))
        TableHeaderCell("Duration", modifier = Modifier.width(90.dp))
        TableHeaderCell("Last Played", modifier = Modifier.width(110.dp))
        Spacer(Modifier.width(64.dp))
    }
}

private data class ArtistListRowStats(
    val genre: String? = null,
    val albumCount: Int = 0,
    val songCount: Int = 0,
    val durationMs: Long = 0L,
    val trackIds: Set<String> = emptySet(),
)

private fun buildArtistListRowStats(catalog: CatalogSnapshot): Map<String, ArtistListRowStats> {
    val albumIdsByArtist = linkedMapOf<String, MutableSet<String>>()
    val genreTallyByArtist = linkedMapOf<String, MutableMap<String, Int>>()
    val albumArtistById = linkedMapOf<String, String>()
    catalog.albums.forEach { album ->
        val artistKey = album.artist.artistStatsKey()
        if (artistKey.isBlank()) return@forEach
        albumIdsByArtist.getOrPut(artistKey) { linkedSetOf() } += album.id
        albumIdAliasesForLibraryStats(album.id).forEach { alias ->
            albumArtistById[alias] = artistKey
        }
        album.genre?.takeIf { it.isNotBlank() }?.let { genre ->
            val tally = genreTallyByArtist.getOrPut(artistKey) { linkedMapOf() }
            tally[genre] = (tally[genre] ?: 0) + 1
        }
    }

    val tracksByArtist = linkedMapOf<String, MutableMap<String, Track>>()
    val durationByArtist = linkedMapOf<String, Long>()
    catalog.tracksByParent.forEach { (parentId, tracks) ->
        tracks.forEach { track ->
            val artistKey = track.artist.artistStatsKey()
                .ifBlank { albumArtistById[parentId].orEmpty() }
                .ifBlank { track.parentAlbumId?.let { albumArtistById[it].orEmpty() }.orEmpty() }
            if (artistKey.isBlank()) return@forEach
            val artistTracks = tracksByArtist.getOrPut(artistKey) { linkedMapOf() }
            if (!artistTracks.containsKey(track.id)) {
                artistTracks[track.id] = track
                durationByArtist[artistKey] = (durationByArtist[artistKey] ?: 0L) + track.durationMs
            }
            track.genre?.takeIf { it.isNotBlank() }?.let { genre ->
                val tally = genreTallyByArtist.getOrPut(artistKey) { linkedMapOf() }
                tally[genre] = (tally[genre] ?: 0) + 1
            }
        }
    }

    return (albumIdsByArtist.keys + tracksByArtist.keys + genreTallyByArtist.keys).associateWith { artistKey ->
        val tracks = tracksByArtist[artistKey].orEmpty()
        ArtistListRowStats(
            genre = genreTallyByArtist[artistKey]?.maxByOrNull { it.value }?.key,
            albumCount = albumIdsByArtist[artistKey].orEmpty().size,
            songCount = tracks.size,
            durationMs = durationByArtist[artistKey] ?: 0L,
            trackIds = tracks.keys,
        )
    }
}

private fun String.artistStatsKey(): String = trim().lowercase()

private fun albumIdAliasesForLibraryStats(id: String): List<String> {
    val trimmed = id.trim()
    if (trimmed.isEmpty()) return emptyList()
    val unprefixed = trimmed.substringAfter(':')
    return if (unprefixed != trimmed && unprefixed.isNotBlank()) listOf(trimmed, unprefixed) else listOf(trimmed)
}

@Composable
private fun ArtistRow(
    artist: Artist,
    stats: ArtistListRowStats?,
    onArtist: () -> Unit,
) {
    val genre = stats?.genre ?: artist.genre ?: "—"
    val albumCount = stats?.albumCount?.takeIf { it > 0 } ?: artist.albumCount
    val songCount = stats?.songCount?.takeIf { it > 0 } ?: artist.songCount
    val durationMs = stats?.durationMs ?: 0L
    val playHistory = LocalPlayHistory.current
    val nowMs = LocalNowMs.current
    val lastPlayed = remember(stats?.trackIds, playHistory.byTrack, playHistory.byArtist, artist.title) {
        resolveArtistLastPlayed(artist.title, stats?.trackIds.orEmpty(), playHistory)
    }
    val lastPlayedLabel = remember(lastPlayed, nowMs) { formatLastPlayed(lastPlayed, nowMs) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .phoebeClickable(onClick = onArtist)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(2.4f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .size(42.dp)
                    .sharedArtworkTransition("artist:${artist.id}")
                    .clip(CircleShape),
            ) {
                ArtworkImage(artist.title, artist.thumbUrl, Modifier.fillMaxSize(), radius = 21.dp)
            }
            Text(
                artist.title,
                color = PhoebeUi.secondaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition("artist:${artist.id}:title"),
            )
        }
        TableCellText(genre, modifier = Modifier.weight(1.6f), color = PhoebeUi.secondaryText)
        TableCellText(albumCount.toString(), modifier = Modifier.width(70.dp), color = PhoebeUi.secondaryText)
        TableCellText(songCount.toString(), modifier = Modifier.width(70.dp), color = PhoebeUi.secondaryText)
        TableCellText(formatHoursMinutes(durationMs), modifier = Modifier.width(90.dp), color = PhoebeUi.secondaryText)
        TableCellText(
            lastPlayedLabel,
            modifier = Modifier.width(110.dp),
            color = if (lastPlayed != null) PhoebeUi.secondaryText else PhoebeUi.mutedText,
        )
        Spacer(Modifier.width(40.dp))
    }
}

// =====================================================================
// Albums grid
// =====================================================================

@Composable
private fun AlbumsGrid(
    catalog: CatalogSnapshot,
    albums: List<Album>,
    selectedAlbumId: String?,
    viewMode: LibraryViewMode,
    albumGridItemSizeDp: Int,
    sortBy: LibrarySortBy,
    ascending: Boolean,
    onSelect: (Album) -> Unit,
    onOpen: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (albums.isEmpty()) {
        Box(modifier.padding(top = 16.dp)) {
            Text("No albums yet.", color = PhoebeUi.mutedText, fontSize = 13.sp)
        }
        return
    }
    val indexEntries = remember(albums, sortBy, ascending) {
        libraryAlbumScrollIndex(albums, sortBy, ascending)
    }
    val scope = rememberCoroutineScope()
    val indexScrollDispatcher = rememberLibrarySectionIndexSelectionDispatcher()
    var sectionIndexScrubbing by remember { mutableStateOf(false) }
    when (viewMode) {
        LibraryViewMode.Grid -> {
            val gridState = rememberLazyGridState()
            val revealIndex by remember(gridState) { derivedStateOf { gridState.isScrollInProgress } }
            Box(modifier) {
                LibraryResponsiveGrid(
                    itemSizeDp = albumGridItemSizeDp,
                    horizontalSpacing = 18.dp,
                    verticalSpacing = 20.dp,
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(albums, key = { it.id }, contentType = { "album-card" }) { album ->
                        val onAlbumSelect = remember(album, onSelect) { { onSelect(album) } }
                        val onAlbumOpen = remember(album, onOpen) { { onOpen(album) } }
                        AlbumCard(
                            catalog = catalog,
                            album = album,
                            selected = album.id == selectedAlbumId,
                            artworkDecodeDimension = libraryGridDecodeDimension(albumGridItemSizeDp),
                            onSelect = onAlbumSelect,
                            onOpen = onAlbumOpen,
                        )
                    }
                }
                LibrarySectionIndex(
                    entries = indexEntries,
                    onEntrySelected = { entry ->
                        indexScrollDispatcher.launch(scope, key = entry.itemIndex) { gridState.scrollToItem(entry.itemIndex) }
                    },
                    onScrubbingChanged = { sectionIndexScrubbing = it },
                    mode = LibrarySectionIndexMode.DesktopScrollbar,
                    revealSignal = revealIndex,
                    scrollbarStateProvider = {
                        LibraryScrollbarState(
                            firstVisibleItemIndex = gridState.firstVisibleItemIndex,
                            visibleItemsCount = gridState.layoutInfo.visibleItemsInfo.size,
                            totalItemsCount = gridState.layoutInfo.totalItemsCount,
                        )
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        LibraryViewMode.List -> {
            val listState = rememberLazyListState()
            val revealIndex by remember(listState) { derivedStateOf { listState.isScrollInProgress } }
            val scrollbarState by remember(listState) {
                derivedStateOf {
                    LibraryScrollbarState(
                        firstVisibleItemIndex = listState.firstVisibleItemIndex,
                        visibleItemsCount = listState.layoutInfo.visibleItemsInfo.size,
                        totalItemsCount = listState.layoutInfo.totalItemsCount,
                    )
                }
            }
            Box(modifier) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(albums, key = { it.id }, contentType = { "album-list" }) { album ->
                        val onAlbumSelect = remember(album, onSelect) { { onSelect(album) } }
                        val onAlbumOpen = remember(album, onOpen) { { onOpen(album) } }
                        AlbumListRow(
                            catalog = catalog,
                            album = album,
                            selected = album.id == selectedAlbumId,
                            onSelect = onAlbumSelect,
                            onOpen = onAlbumOpen,
                        )
                    }
                }
                LibrarySectionIndex(
                    entries = indexEntries,
                    onEntrySelected = { entry ->
                        indexScrollDispatcher.launch(scope, key = entry.itemIndex) { listState.scrollToItem(entry.itemIndex) }
                    },
                    onScrubbingChanged = { sectionIndexScrubbing = it },
                    mode = LibrarySectionIndexMode.DesktopScrollbar,
                    revealSignal = revealIndex,
                    scrollbarState = scrollbarState,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

@Composable
private fun AlbumCard(
    catalog: CatalogSnapshot,
    album: Album,
    selected: Boolean,
    artworkDecodeDimension: Int,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val tracks = remember(catalog, album.id) { catalogTracksForAlbum(catalog, album.id) }
    val durationMs = remember(tracks) { tracks.sumOf { it.durationMs } }
    val codec = remember(catalog, album.id) { catalogAlbumCodec(catalog, album.id) }
    val genre = remember(catalog, album.id) { catalogAlbumGenre(catalog, album.id) }
    val borderColor = if (selected) PhoebeUi.accent else Color.Transparent
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(12.dp))
            .background(if (selected) PhoebeUi.accent.copy(alpha = 0.06f) else Color.Transparent)
            .phoebeClickable(onClick = onSelect)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .sharedArtworkTransition("album:${album.id}")
                .phoebeClickable(onClick = onOpen),
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
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                album.title,
                color = PhoebeUi.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition("album:${album.id}:title"),
            )
            Text(
                album.artist,
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
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
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (genre != null || codec != null) {
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    genre?.let { AlbumChip(it) }
                    codec?.let { AlbumChip(it.uppercase()) }
                }
            }
        }
    }
}

@Composable
private fun AlbumListRow(
    catalog: CatalogSnapshot,
    album: Album,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val tracks = remember(catalog, album.id) { catalogTracksForAlbum(catalog, album.id) }
    val durationMs = remember(tracks) { tracks.sumOf { it.durationMs } }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .phoebeClickable(onClick = onSelect)
            .background(if (selected) PhoebeUi.librarySelectedRow else PhoebeUi.libraryHoverRow)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(46.dp).sharedArtworkTransition("album:${album.id}").phoebeClickable(onClick = onOpen)) {
            ArtworkImage(album.title, album.thumbUrl, Modifier.fillMaxSize(), radius = 8.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                album.title,
                color = PhoebeUi.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition("album:${album.id}:title"),
            )
            Text(
                album.artist,
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition("album:${album.id}:subtitle"),
            )
        }
        Text("${tracks.size} tracks", color = PhoebeUi.mutedText, fontSize = 11.sp)
        Text(formatMinutesLabel(durationMs), color = PhoebeUi.mutedText, fontSize = 11.sp)
    }
}

@Composable
private fun AlbumChip(label: String) {
    Text(
        label,
        color = PhoebeUi.secondaryText,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.06.em,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

// =====================================================================
// Songs table
// =====================================================================

@Composable
private fun SongsTable(
    tracks: List<Track>,
    selectedTrackId: String?,
    columns: LibraryColumnVisibility,
    sortBy: LibrarySortBy,
    ascending: Boolean,
    onSelect: (Track) -> Unit,
    onPlay: (Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val indexEntries = remember(tracks, sortBy, ascending) {
        libraryTrackScrollIndex(tracks, sortBy, ascending)
    }
    val listState = rememberLazyListState()
    val revealIndex by remember(listState) { derivedStateOf { listState.isScrollInProgress } }
    val scrollbarState by remember(listState) {
        derivedStateOf {
            LibraryScrollbarState(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                visibleItemsCount = listState.layoutInfo.visibleItemsInfo.size,
                totalItemsCount = listState.layoutInfo.totalItemsCount,
            )
        }
    }
    val scope = rememberCoroutineScope()
    val indexScrollDispatcher = rememberLibrarySectionIndexSelectionDispatcher()
    var sectionIndexScrubbing by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            SongsTableHeader(columns)
            if (tracks.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(top = 24.dp)) {
                    Text("No songs yet.", color = PhoebeUi.mutedText, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "song" }) { index, track ->
                        val onSelectClick = remember(track, onSelect) { { onSelect(track) } }
                        val onPlayClick = remember(index, onPlay) { { onPlay(index) } }
                        val onAddClick = remember(track, onAddToUpNext) { { onAddToUpNext(track) } }
                        val onDownloadClick = remember(track, onDownload) { { onDownload(track) } }
                        SongRow(
                            track = track,
                            selected = track.id == selectedTrackId,
                            columns = columns,
                            onSelect = onSelectClick,
                            onPlay = onPlayClick,
                            onAddToUpNext = onAddClick,
                            onDownload = onDownloadClick,
                        )
                    }
                }
            }
        }
        LibrarySectionIndex(
            entries = indexEntries,
            onEntrySelected = { entry ->
                indexScrollDispatcher.launch(scope, key = entry.itemIndex) { listState.scrollToItem(entry.itemIndex) }
            },
            onScrubbingChanged = { sectionIndexScrubbing = it },
            mode = LibrarySectionIndexMode.DesktopScrollbar,
            revealSignal = revealIndex,
            scrollbarState = scrollbarState,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
fun SongsTableHeader(
    columns: LibraryColumnVisibility,
    showLeadingHandle: Boolean = LocalPlaylistDragEnabled.current,
    showSelectionColumn: Boolean = false,
    showPlayCount: Boolean = false,
    showLastPlayed: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TableHeaderCell(
            "# Title",
            modifier = Modifier.weight(2.2f).padding(
                start = when {
                    showSelectionColumn -> 36.dp
                    showLeadingHandle -> 92.dp
                    else -> 56.dp
                },
            ),
        )
        TableHeaderCell("Artist", modifier = Modifier.weight(1.4f))
        TableHeaderCell("Album", modifier = Modifier.weight(1.6f))
        if (columns.duration) TableHeaderCell("Duration", modifier = Modifier.width(70.dp))
        if (columns.audioCodec) TableHeaderCell("Codec", modifier = Modifier.width(60.dp))
        if (columns.bitrate) TableHeaderCell("Bitrate", modifier = Modifier.width(70.dp))
        if (columns.sampleRate) TableHeaderCell("Sample Rate", modifier = Modifier.width(86.dp))
        if (columns.fileType) TableHeaderCell("File Type", modifier = Modifier.width(70.dp))
        if (columns.dateAdded) TableHeaderCell("Date Added", modifier = Modifier.width(96.dp))
        if (columns.filepath) TableHeaderCell("File Path", modifier = Modifier.weight(1.4f))
        if (columns.rating) TableHeaderCell("Rating", modifier = Modifier.width(86.dp))
        if (columns.favorite) TableHeaderCell("Fav", modifier = Modifier.width(44.dp))
        if (showPlayCount) TableHeaderCell("Plays", modifier = Modifier.width(80.dp))
        if (showLastPlayed) TableHeaderCell("Last Played", modifier = Modifier.width(100.dp))
        Spacer(Modifier.width(102.dp))
    }
}

@Composable
fun SongRow(
    track: Track,
    selected: Boolean,
    columns: LibraryColumnVisibility,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onAddToUpNext: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    leadingHandle: (@Composable () -> Unit)? = null,
    showPlaylistDragHandle: Boolean = true,
    selectionMode: Boolean = false,
    sharedKey: String? = "song:${track.id}",
    playCount: Long? = null,
    lastPlayedMs: Long? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val hasMenu = true
    val nowPlaying = LocalNowPlaying.current
    val likeActions = LocalLikeActions.current
    val ratingActions = LocalRatingActions.current
    val downloads = LocalDownloadStatus.current
    val isCurrent = nowPlaying.trackId == track.id
    val playlistDragEnabled = LocalPlaylistDragEnabled.current
    val canLike = likeActions.likesEnabled && track.canTogglePlexLike()
    val liked = likeActions.isLiked(track)
    val downloaded = downloads.isComplete(track)
    val rowDragEnabled = playlistDragEnabled && leadingHandle == null && !selectionMode
    val dragHandleVisible = rowDragEnabled && showPlaylistDragHandle
    Row(
        modifier
            .fillMaxWidth()
            .playTrackTarget(track)
            .then(if (rowDragEnabled) Modifier.draggableSong(track, immediate = !dragHandleVisible) else Modifier)
            .openContextMenuOnSecondaryClick(enabled = hasMenu && !selectionMode) { menuExpanded = true }
            .clip(RoundedCornerShape(10.dp))
            .phoebeClickable(onClick = onSelect)
            .background(
                when {
                    isCurrent -> PhoebeUi.accent.copy(alpha = 0.14f)
                    selected -> PhoebeUi.librarySelectedRow
                    else -> PhoebeUi.libraryHoverRow
                },
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.weight(2.2f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (selectionMode) {
                LibraryCheckbox(checked = selected, size = 18)
            }
            if (leadingHandle != null) {
                leadingHandle()
            } else if (dragHandleVisible) {
                // Immediate-drag handle. Visible target so users discover that songs can be
                // dragged onto sidebar playlists without having to long-press the row.
                Box(
                    Modifier
                        .draggableSong(track, immediate = true)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PhoebeIconView(PhoebeIcon.Drag, tint = PhoebeUi.mutedText, modifier = Modifier.size(15.dp))
                }
            }
            Box(
                Modifier
                    .size(42.dp)
                    .sharedArtworkTransition(sharedKey)
                    .phoebeClickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                TrackArtworkImage(
                    track,
                    Modifier.fillMaxSize(),
                    radius = 6.dp,
                    maxDecodeDimension = ThumbnailArtworkMaxDecodeDimension,
                )
                if (isCurrent) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(6.dp))
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
            Text(
                track.title,
                color = when {
                    isCurrent -> PhoebeUi.accentLight
                    selected -> PhoebeUi.primaryText
                    else -> PhoebeUi.secondaryText
                },
                fontSize = 13.sp,
                fontWeight = if (isCurrent || selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition(sharedKey?.let { "$it:title" }),
            )
        }
        TableCellText(track.artist, modifier = Modifier.weight(1.4f), color = PhoebeUi.secondaryText)
        TableAlbumCell(
            album = track.album,
            downloaded = downloaded,
            modifier = Modifier.weight(1.6f),
        )
        if (columns.duration) TableCellText(formatMinutesSeconds(track.durationMs), modifier = Modifier.width(70.dp), color = PhoebeUi.secondaryText)
        if (columns.audioCodec) TableCellText(track.audioCodec?.uppercase() ?: "—", modifier = Modifier.width(60.dp), color = PhoebeUi.secondaryText)
        if (columns.bitrate) TableCellText(displayBitrateLabel(track), modifier = Modifier.width(70.dp), color = PhoebeUi.secondaryText)
        if (columns.sampleRate) TableCellText(displaySampleRateLabel(track), modifier = Modifier.width(86.dp), color = PhoebeUi.secondaryText)
        if (columns.fileType) TableCellText(displayFileTypeLabel(track), modifier = Modifier.width(70.dp), color = PhoebeUi.secondaryText)
        if (columns.dateAdded) {
            TableCellText(
                track.dateAddedMs?.let { formatLastPlayed(it, LocalNowMs.current) } ?: "—",
                modifier = Modifier.width(96.dp),
                color = PhoebeUi.mutedText,
            )
        }
        if (columns.filepath) TableCellText(track.filepath?.let(::shortenFilepath) ?: "—", modifier = Modifier.weight(1.4f), color = PhoebeUi.mutedText)
        if (columns.rating) {
            RatingStars(
                rating = ratingActions.ratingFor(track),
                enabled = ratingActions.ratingsEnabled && track.isRemoteLibraryTrack(),
                onRating = { ratingActions.onRateTrack(track, it) },
                modifier = Modifier.width(86.dp),
                starSize = 13.dp,
            )
        }
        if (columns.favorite) {
            LikeButton(
                liked = liked,
                enabled = canLike,
                onClick = { likeActions.onToggleLiked(track) },
                modifier = Modifier.width(44.dp),
            )
        }
        if (playCount != null) {
            TableCellText(if (playCount == 1L) "1 play" else "$playCount plays", modifier = Modifier.width(80.dp), color = PhoebeUi.secondaryText)
        }
        if (lastPlayedMs != null) {
            TableCellText(formatLastPlayed(lastPlayedMs, LocalNowMs.current), modifier = Modifier.width(100.dp), color = PhoebeUi.mutedText)
        }
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            TrackStateBadges(
                liked = !columns.favorite && canLike && liked,
                downloaded = false,
                iconSize = 11.dp,
            )
        }
        TrackDownloadIndicator(
            track = track,
            onDownload = null,
            showIdle = false,
            showComplete = false,
            showFailed = false,
        )
        Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .phoebeClickable(onClick = { if (hasMenu) menuExpanded = true else onPlay() }),
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

// =====================================================================
// Detail sidebars
// =====================================================================

@Composable
private fun AlbumDetailSidebar(
    album: Album,
    columns: LibraryColumnVisibility,
    catalog: CatalogSnapshot,
    onPlayTrack: (List<Track>, Int) -> Unit,
) {
    val tracks = remember(catalog, album.id) { catalogTracksForAlbum(catalog, album.id) }
    val codec = remember(catalog, album.id) { catalogAlbumCodec(catalog, album.id) }
    val genre = remember(catalog, album.id) { catalogAlbumGenre(catalog, album.id) }
    val bitrate = remember(catalog, album.id) { catalogAlbumBitrateKbps(catalog, album.id) }
    val duration = remember(tracks) { tracks.sumOf { it.durationMs } }
    val sampleRate = remember(tracks) { tracks.firstOrNull { isLossless(it) }?.let { "44.1 kHz" } ?: tracks.firstOrNull()?.let { "—" } ?: "—" }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).widthIn(max = 232.dp)) {
                ArtworkImage(album.title, album.thumbUrl, Modifier.fillMaxSize(), radius = 12.dp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(album.title, color = PhoebeUi.primaryText, fontSize = 18.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(album.artist.uppercase(), color = PhoebeUi.secondaryText, fontSize = 11.sp, letterSpacing = 0.06.em)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            album.year?.let { DetailMetaRow("Released", "Sep 8, $it") }
            if (columns.genre) DetailMetaRow("Genre", genre ?: "—")
            DetailMetaRow("Tracks", tracks.size.toString())
            if (columns.duration) DetailMetaRow("Total Duration", formatMinutesLabel(duration))
            Spacer(Modifier.height(6.dp))
            if (columns.audioCodec) DetailMetaRow("Codec", codec ?: "—")
            if (columns.bitrate) DetailMetaRow(
                "Bitrate",
                if (codec.equals("FLAC", true) || codec.equals("ALAC", true)) "Lossless" else bitrate?.let { "$it kbps" } ?: "—",
            )
            if (columns.sampleRate) DetailMetaRow("Sample Rate", sampleRate)
            if (columns.fileType) DetailMetaRow(
                "File Type",
                tracks.firstOrNull()?.let { displayFileTypeLabel(it) } ?: "—",
            )
            DetailMetaRow("File Size", "—")
            Spacer(Modifier.height(6.dp))
            DetailMetaRow("Location", "Local Library")
            if (columns.filepath) DetailMetaRow(
                "File Path",
                tracks.firstOrNull()?.filepath?.let(::shortenFilepath)?.let { "/$it" } ?: "—",
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Top Tracks".uppercase(), color = PhoebeUi.mutedText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em)
            Spacer(Modifier.weight(1f))
            Text("View All", color = PhoebeUi.accentLight, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tracks.take(5).forEachIndexed { index, t ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).phoebeClickable { onPlayTrack(tracks, index) }.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("${index + 1}", color = PhoebeUi.mutedText, fontSize = 11.sp, modifier = Modifier.width(16.dp))
                    Text(t.title, color = PhoebeUi.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(formatMinutesSeconds(t.durationMs), color = PhoebeUi.mutedText, fontSize = 11.sp)
                }
            }
            if (tracks.isEmpty()) {
                Text("No tracks loaded yet.", color = PhoebeUi.mutedText, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SongDetailSidebar(
    track: Track,
    columns: LibraryColumnVisibility,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
) {
    var playlistMenuExpanded by remember(track.id) { mutableStateOf(false) }
    val nowPlaying = LocalNowPlaying.current
    val likeActions = LocalLikeActions.current
    val isCurrent = nowPlaying.trackId == track.id
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .widthIn(max = 232.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .phoebeClickable(onClick = onPlay),
            ) {
                TrackArtworkImage(track, Modifier.fillMaxSize(), radius = 12.dp)
                if (isCurrent) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        NowPlayingIndicator(
                            isPlaying = nowPlaying.isPlaying,
                            isBuffering = nowPlaying.isBuffering,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.title, color = PhoebeUi.primaryText, fontSize = 17.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist.uppercase(), color = PhoebeUi.secondaryText, fontSize = 11.sp, letterSpacing = 0.06.em)
                if (track.album.isNotBlank()) {
                    Text(track.album, color = PhoebeUi.mutedText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (columns.duration) DetailMetaRow("Duration", formatMinutesSeconds(track.durationMs))
            if (columns.audioCodec) DetailMetaRow("Codec", track.audioCodec?.uppercase() ?: "—")
            if (columns.bitrate) DetailMetaRow("Bitrate", displayBitrateLabel(track))
            if (columns.sampleRate) DetailMetaRow("Sample Rate", displaySampleRateLabel(track))
            if (columns.fileType) DetailMetaRow("File Type", displayFileTypeLabel(track))
            DetailMetaRow("Channels", "2 (Stereo)")
            DetailMetaRow("File Size", "—")
            if (columns.dateAdded) DetailMetaRow("Date Added", "—")
            DetailMetaRow("Play Count", "—")
            if (columns.filepath) DetailMetaRow("File Path", track.filepath?.let(::shortenFilepath)?.let { "/$it" } ?: "—")
        }
        Spacer(Modifier.height(2.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (likeActions.likesEnabled && track.canTogglePlexLike()) {
                SongDetailAction(
                    PhoebeIcon.Heart,
                    if (likeActions.isLiked(track)) "Unlike Song" else "Like Song",
                ) { likeActions.onToggleLiked(track) }
            }
            Box {
                SongDetailAction(PhoebeIcon.Plus, "Add to Playlist") { playlistMenuExpanded = true }
                DropdownMenu(
                    expanded = playlistMenuExpanded,
                    onDismissRequest = { playlistMenuExpanded = false },
                ) {
                    AddToPlaylistMenuItems(
                        track = track,
                        onAfter = { playlistMenuExpanded = false },
                        startExpanded = true,
                    )
                }
            }
            DownloadActionButton("Download Song", listOf(track), onClick = onDownload)
        }
    }
}

@Composable
private fun SongDetailAction(icon: PhoebeIcon, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .phoebeClickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PhoebeIconView(icon, tint = PhoebeUi.mutedText, modifier = Modifier.size(16.dp))
        Text(label, color = PhoebeUi.secondaryText, fontSize = 12.sp, modifier = Modifier.weight(1f))
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun DetailMetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = PhoebeUi.mutedText, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(value, color = PhoebeUi.primaryText, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// =====================================================================
// Helpers and primitives
// =====================================================================

@Composable
fun TableHeaderCell(label: String, modifier: Modifier = Modifier) {
    Text(
        label.uppercase(),
        color = PhoebeUi.mutedText,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.08.em,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun TableCellText(text: String, modifier: Modifier = Modifier, color: Color = PhoebeUi.secondaryText) {
    Text(text, color = color, fontSize = 12.sp, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun TableAlbumCell(
    album: String,
    downloaded: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            album,
            color = PhoebeUi.secondaryText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        TrackStateBadges(
            liked = false,
            downloaded = downloaded,
            iconSize = 11.dp,
        )
    }
}

/**
 * Returns the most recent play timestamp for an artist's tracks. We aggregate over
 * each track's last-played value (looked up by `track.id` in [PlayHistorySnapshot.byTrack])
 * and fall back to the artist-keyed map. Going through track ids dodges any artist-name
 * normalization mismatch between the catalog's `Artist.title` and what Plex stamps onto
 * individual `Track.artist` strings.
 */
fun resolveArtistLastPlayed(
    artistTitle: String,
    tracks: List<Track>,
    history: PlayHistorySnapshot,
): Long? = resolveArtistLastPlayed(
    artistTitle = artistTitle,
    trackIds = tracks.map { it.id }.toSet(),
    history = history,
)

fun resolveArtistLastPlayed(
    artistTitle: String,
    trackIds: Set<String>,
    history: PlayHistorySnapshot,
): Long? {
    val perTrack = trackIds.asSequence()
        .mapNotNull { history.byTrack[it] }
        .maxOrNull()
    val perArtist = history.byArtist[artistTitle]
    return when {
        perTrack == null -> perArtist
        perArtist == null -> perTrack
        else -> maxOf(perTrack, perArtist)
    }
}

fun formatMinutesLabel(ms: Long): String {
    if (ms <= 0L) return "—"
    val totalMinutes = ms / 60_000L
    return "${totalMinutes} min"
}

fun formatMinutesSeconds(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0)
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

/** Sort keys that apply to each tab. The first entry is the tab default. */
fun sortKeysFor(filter: LibraryFilterTab): List<LibrarySortBy> = when (filter) {
    LibraryFilterTab.Artists -> listOf(LibrarySortBy.Name, LibrarySortBy.DateAdded)
    LibraryFilterTab.Albums -> listOf(LibrarySortBy.Year, LibrarySortBy.Name, LibrarySortBy.Artist, LibrarySortBy.DateAdded)
    LibraryFilterTab.Songs -> listOf(LibrarySortBy.Name, LibrarySortBy.Album, LibrarySortBy.Artist, LibrarySortBy.Year, LibrarySortBy.DateAdded)
}

fun normalizeSortKey(filter: LibraryFilterTab, sortBy: LibrarySortBy): LibrarySortBy {
    val supported = sortKeysFor(filter)
    return if (sortBy in supported) sortBy else supported.first()
}

fun sortLabelFor(filter: LibraryFilterTab, sortBy: LibrarySortBy): String {
    val key = normalizeSortKey(filter, sortBy)
    return when (filter) {
        LibraryFilterTab.Artists -> if (key == LibrarySortBy.DateAdded) "Date added" else "Artist name"
        LibraryFilterTab.Albums -> when (key) {
            LibrarySortBy.Artist -> "Artist"
            LibrarySortBy.Year -> "Release date"
            LibrarySortBy.DateAdded -> "Date added"
            else -> "Album name"
        }
        LibraryFilterTab.Songs -> when (key) {
            LibrarySortBy.Album -> "Album name"
            LibrarySortBy.Artist -> "Artist"
            LibrarySortBy.Year -> "Release date"
            LibrarySortBy.DateAdded -> "Date added"
            else -> "Song name"
        }
    }
}

fun shortenFilepath(path: String): String {
    val trimmed = path.removePrefix("/").take(48)
    return if (trimmed.length < path.length) "$trimmed…" else trimmed
}
