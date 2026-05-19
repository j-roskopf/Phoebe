package com.phoebe.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.phoebe.app.data.catalogAlbumBitrateKbps
import com.phoebe.app.data.catalogAlbumCodec
import com.phoebe.app.data.catalogAlbumGenre
import com.phoebe.app.data.catalogAlbumTotalDurationMs
import com.phoebe.app.data.catalogAlbumTrackCount
import com.phoebe.app.data.catalogAlbumsForArtist
import com.phoebe.app.data.catalogArtistGenre
import com.phoebe.app.data.catalogArtistTotalDurationMs
import com.phoebe.app.data.catalogTracksForAlbum
import com.phoebe.app.data.catalogTracksForArtist
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

internal enum class LibraryFilterTab { Artists, Albums, Songs }

internal enum class LibraryOrder { Asc, Desc }

internal enum class AlbumSortKey { RecentlyAdded, Name, Year, Artist }

internal enum class SongFileFilter { All, Lossless, Lossy }

internal enum class LibraryViewMode { Grid, List }

private const val DefaultJellyfinLibraryPageSize = 100

internal data class LibraryPage<T>(
    val items: List<T>,
    val pageIndex: Int,
    val pageCount: Int,
    val totalCount: Int,
    val pageSize: Int = DefaultJellyfinLibraryPageSize,
) {
    val firstItemNumber: Int get() = if (totalCount == 0) 0 else pageIndex * pageSize + 1
    val lastItemNumber: Int get() = (pageIndex * pageSize + items.size).coerceAtMost(totalCount)
}

internal fun remoteLibraryPageSize(catalog: CatalogSnapshot, enabled: Boolean): Int =
    if (enabled) catalog.remotePageInfo.pageSize.coerceAtLeast(1) else DefaultJellyfinLibraryPageSize

internal fun <T> libraryPage(
    items: List<T>,
    enabled: Boolean,
    pageIndex: Int,
    totalCountOverride: Int? = null,
    pageSize: Int = DefaultJellyfinLibraryPageSize,
): LibraryPage<T> {
    val activePageSize = pageSize.coerceAtLeast(1)
    val totalCount = totalCountOverride ?: items.size
    if (!enabled) {
        return LibraryPage(items = items, pageIndex = 0, pageCount = 1, totalCount = totalCount, pageSize = activePageSize)
    }
    if (totalCount <= activePageSize) {
        return LibraryPage(items = items.take(activePageSize), pageIndex = 0, pageCount = 1, totalCount = totalCount, pageSize = activePageSize)
    }
    val pageCount = ((totalCount + activePageSize - 1) / activePageSize).coerceAtLeast(1)
    val safeIndex = pageIndex.coerceIn(0, pageCount - 1)
    val start = safeIndex * activePageSize
    return LibraryPage(
        items = items.drop(start).take(activePageSize),
        pageIndex = safeIndex,
        pageCount = pageCount,
        totalCount = totalCount,
        pageSize = activePageSize,
    )
}

internal fun LibraryFilterTab.toJellyfinPageKind(): JellyfinLibraryPageKind = when (this) {
    LibraryFilterTab.Artists -> JellyfinLibraryPageKind.Artists
    LibraryFilterTab.Albums -> JellyfinLibraryPageKind.Albums
    LibraryFilterTab.Songs -> JellyfinLibraryPageKind.Tracks
}

internal fun CatalogSnapshot.remoteTotalFor(filter: LibraryFilterTab): Int? = when (filter) {
    LibraryFilterTab.Artists -> remotePageInfo.artistTotal
    LibraryFilterTab.Albums -> remotePageInfo.albumTotal
    LibraryFilterTab.Songs -> remotePageInfo.trackTotal
}

@Composable
internal fun LibraryPaginationControls(
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
internal fun FavoriteArtistsDesktopView(
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
    val visibleArtists = remember(sortedArtists, searchQuery) {
        val q = searchQuery.trim()
        if (q.isBlank()) sortedArtists else sortedArtists.filter { it.title.contains(q, ignoreCase = true) }
    }
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
            onArtist = onArtist,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun FavoriteAlbumsDesktopView(
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
    val visibleAlbums = remember(sortedAlbums, searchQuery) {
        val q = searchQuery.trim()
        if (q.isBlank()) sortedAlbums else sortedAlbums.filter {
            it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
        }
    }
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
            onSelect = { selectedAlbumId = it.id },
            onOpen = onAlbum,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun FavoritePlaylistsDesktopView(
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
    val visiblePlaylists = remember(favoritePlaylists, searchQuery) {
        filterPlaylistsByQuery(favoritePlaylists, searchQuery)
    }
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
        modifier.fillMaxSize().padding(start = 36.dp, top = 32.dp, end = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DetailBackButton(onBack = onBack)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = PhoebeUi.primaryText, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text(countLabel, color = PhoebeUi.mutedText, fontSize = 13.sp)
            }
            SearchPill(searchQuery, onSearchQuery, Modifier.width(380.dp))
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
internal fun LibraryDesktopView(
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
    // Scoped search: filter the currently-visible tab's collection by the query.
    val visibleArtists = remember(sortedArtists, searchQuery) {
        val q = searchQuery.trim()
        if (q.isBlank()) sortedArtists else sortedArtists.filter { it.title.contains(q, ignoreCase = true) }
    }
    val visibleAlbums = remember(sortedAlbums, searchQuery) {
        val q = searchQuery.trim()
        if (q.isBlank()) sortedAlbums else sortedAlbums.filter {
            it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
        }
    }
    val visibleTracks = remember(sortedTracks, searchQuery) {
        val q = searchQuery.trim()
        if (q.isBlank()) sortedTracks else sortedTracks.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.artist.contains(q, ignoreCase = true) ||
                it.album.contains(q, ignoreCase = true)
        }
    }
    val artistTotal = if (searchQuery.isBlank()) catalog.remotePageInfo.artistTotal else null
    val albumTotal = if (searchQuery.isBlank()) catalog.remotePageInfo.albumTotal else null
    val trackTotal = if (searchQuery.isBlank()) catalog.remotePageInfo.trackTotal else null
    val remotePageSize = remoteLibraryPageSize(catalog, jellyfinPagination)
    val artistPage = remember(visibleArtists, jellyfinPagination, pageIndex, artistTotal, remotePageSize) {
        libraryPage(visibleArtists, jellyfinPagination, pageIndex, artistTotal, pageSize = remotePageSize)
    }
    val albumPage = remember(visibleAlbums, jellyfinPagination, pageIndex, albumTotal, remotePageSize) {
        libraryPage(visibleAlbums, jellyfinPagination, pageIndex, albumTotal, pageSize = remotePageSize)
    }
    val trackPage = remember(visibleTracks, jellyfinPagination, pageIndex, trackTotal, remotePageSize) {
        libraryPage(visibleTracks, jellyfinPagination, pageIndex, trackTotal, pageSize = remotePageSize)
    }
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
                    start = 36.dp,
                    top = 32.dp,
                    end = 28.dp,
                    bottom = 24.dp,
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
            if (narrowPane || filter == LibraryFilterTab.Artists) {
                when (filter) {
                    LibraryFilterTab.Artists -> ArtistsContent(
                        catalog = catalog,
                        artists = artistPage.items,
                        viewMode = libraryViewMode,
                        onArtist = onArtist,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    LibraryFilterTab.Albums -> AlbumsGrid(
                        catalog = catalog,
                        albums = albumPage.items,
                        selectedAlbumId = selectedAlbumId,
                        viewMode = libraryViewMode,
                        onSelect = { selectedAlbumId = it.id },
                        onOpen = onAlbum,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    LibraryFilterTab.Songs -> SongsTable(
                        tracks = trackPage.items,
                        selectedTrackId = selectedTrackId,
                        columns = libraryUi.columns,
                        onSelect = { selectedTrackId = it.id },
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
                    when (filter) {
                        LibraryFilterTab.Albums -> AlbumsGrid(
                            catalog = catalog,
                            albums = albumPage.items,
                            selectedAlbumId = selectedAlbumId,
                            viewMode = libraryViewMode,
                            onSelect = { selectedAlbumId = it.id },
                            onOpen = onAlbum,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        LibraryFilterTab.Songs -> SongsTable(
                            tracks = trackPage.items,
                            selectedTrackId = selectedTrackId,
                            columns = libraryUi.columns,
                            onSelect = { selectedTrackId = it.id },
                            onPlay = { index -> onPlayTracks(trackPage.items, index) },
                            onAddToUpNext = onAddToUpNext,
                            onDownload = onDownload,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        LibraryFilterTab.Artists -> Unit
                    }
                    Column(
                        modifier = Modifier
                            .width(detailWidth)
                            .fillMaxHeight(),
                    ) {
                        when (filter) {
                            LibraryFilterTab.Albums -> {
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
                            LibraryFilterTab.Songs -> {
                                val selected = trackPage.items.firstOrNull { it.id == selectedTrackId }
                                    ?: sortedTracks.firstOrNull { it.id == selectedTrackId }
                                if (selected != null) {
                                    SongDetailSidebar(
                                        track = selected,
                                        columns = libraryUi.columns,
                                        onPlay = {
                                            val idx = trackPage.items.indexOfFirst { it.id == selected.id }
                                            if (idx >= 0) {
                                                onPlayTracks(trackPage.items, idx)
                                            } else {
                                                val fallback = sortedTracks.indexOfFirst { it.id == selected.id }
                                                if (fallback >= 0) onPlayTracks(sortedTracks, fallback)
                                            }
                                        },
                                        onAddToPlaylist = { onAddToUpNext(selected) },
                                        onDownload = { onDownload(selected) },
                                    )
                                } else {
                                    LibraryEmptyDetail("Select a song to see details.")
                                }
                            }
                            LibraryFilterTab.Artists -> Unit
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
            SearchPill(searchQuery, onSearchQuery, Modifier.width(380.dp))
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
        Spacer(Modifier.width(14.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
        ) {
            LibraryDropdown(
                label = "Sort by",
                value = sortLabelFor(filter, prefs.sortBy),
            ) { close ->
                when (filter) {
                    LibraryFilterTab.Artists -> {
                        DropdownMenuItem(text = { Text("Artist name") }, onClick = { onSortBy(LibrarySortBy.Name); close() })
                    }
                    LibraryFilterTab.Albums -> {
                        DropdownMenuItem(text = { Text("Album name") }, onClick = { onSortBy(LibrarySortBy.Name); close() })
                        DropdownMenuItem(text = { Text("Artist") }, onClick = { onSortBy(LibrarySortBy.Artist); close() })
                        DropdownMenuItem(text = { Text("Release date") }, onClick = { onSortBy(LibrarySortBy.Year); close() })
                    }
                    LibraryFilterTab.Songs -> {
                        DropdownMenuItem(text = { Text("Song name") }, onClick = { onSortBy(LibrarySortBy.Name); close() })
                        DropdownMenuItem(text = { Text("Album name") }, onClick = { onSortBy(LibrarySortBy.Album); close() })
                        DropdownMenuItem(text = { Text("Artist") }, onClick = { onSortBy(LibrarySortBy.Artist); close() })
                        DropdownMenuItem(text = { Text("Release date") }, onClick = { onSortBy(LibrarySortBy.Year); close() })
                    }
                }
            }
            LibraryDropdown(
                label = "Order",
                value = if (prefs.ascending) "A–Z" else "Desc",
            ) {
                DropdownMenuItem(text = { Text("A–Z") }, onClick = { onAscending(true); it() })
                DropdownMenuItem(text = { Text("Z–A / Desc") }, onClick = { onAscending(false); it() })
            }
            when (filter) {
                LibraryFilterTab.Artists, LibraryFilterTab.Albums -> LibraryDropdown(
                    label = "View",
                    value = if (libraryViewMode == LibraryViewMode.Grid) "Grid" else "List",
                ) {
                    DropdownMenuItem(text = { Text("Grid") }, onClick = { onLibraryViewMode(LibraryViewMode.Grid); it() })
                    DropdownMenuItem(text = { Text("List") }, onClick = { onLibraryViewMode(LibraryViewMode.List); it() })
                }
                LibraryFilterTab.Songs -> LibraryDropdown(
                    label = "Filter",
                    value = when (songFilter) {
                        SongFileFilter.All -> "All Files"
                        SongFileFilter.Lossless -> "Lossless"
                        SongFileFilter.Lossy -> "Lossy"
                    },
                ) {
                    DropdownMenuItem(text = { Text("All Files") }, onClick = { onSongFilter(SongFileFilter.All); it() })
                    DropdownMenuItem(text = { Text("Lossless") }, onClick = { onSongFilter(SongFileFilter.Lossless); it() })
                    DropdownMenuItem(text = { Text("Lossy") }, onClick = { onSongFilter(SongFileFilter.Lossy); it() })
                }
            }
            if (filter != LibraryFilterTab.Artists) {
                ColumnsPickerButton(prefs.columns, onColumns)
            }
        }
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
                    .clickable { onFilter(tab) }
                    .background(if (active) PhoebeUi.accent.copy(alpha = 0.22f) else Color.Transparent)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
internal fun LibraryDropdown(
    label: String,
    value: String,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
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
internal fun DetailSectionToolbar(
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
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
    ) {
        if (sortBy != null && onSortBy != null && sortKeys.isNotEmpty()) {
            LibraryDropdown(label = "Sort by", value = sortLabel(sortBy)) { close ->
                sortKeys.forEach { key ->
                    DropdownMenuItem(text = { Text(sortLabel(key)) }, onClick = { onSortBy(key); close() })
                }
            }
        }
        if (ascending != null && onAscending != null) {
            LibraryDropdown(label = "Order", value = if (ascending) "A–Z" else "Desc") {
                DropdownMenuItem(text = { Text("A–Z") }, onClick = { onAscending(true); it() })
                DropdownMenuItem(text = { Text("Z–A / Desc") }, onClick = { onAscending(false); it() })
            }
        }
        if (viewMode != null && onViewMode != null) {
            LibraryDropdown(
                label = "View",
                value = if (viewMode == LibraryViewMode.Grid) "Grid" else "List",
            ) {
                DropdownMenuItem(text = { Text("Grid") }, onClick = { onViewMode(LibraryViewMode.Grid); it() })
                DropdownMenuItem(text = { Text("List") }, onClick = { onViewMode(LibraryViewMode.List); it() })
            }
        }
        if (columns != null && onColumns != null) {
            ColumnsPickerButton(columns, onColumns)
        }
        actions()
    }
}

@Composable
internal fun LibraryToolbarButton(
    icon: PhoebeIcon,
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    iconTint: Color = PhoebeUi.secondaryText,
    enabled: Boolean = true,
    onClick: () -> Unit,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .background(Color.White.copy(alpha = 0.04f))
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (leadingContent != null) {
            leadingContent()
        } else {
            PhoebeIconView(icon, tint = iconTint, modifier = Modifier.size(13.dp))
        }
        Text(label, color = PhoebeUi.primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        value?.let {
            Text(it, color = PhoebeUi.mutedText, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun ColumnsPickerButton(columns: LibraryColumnVisibility, onColumns: (LibraryColumnVisibility) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
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
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LibraryCheckbox(checked = checked)
        Text(label, color = PhoebeUi.primaryText, fontSize = 13.sp)
    }
}

@Composable
internal fun LibraryCheckbox(checked: Boolean, size: Int = 16) {
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
private fun LibraryEmptyDetail(message: String) {
    Box(Modifier.fillMaxSize().padding(top = 36.dp), contentAlignment = Alignment.TopCenter) {
        Text(message, color = PhoebeUi.mutedText, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun LibraryLoadingStrip(modifier: Modifier = Modifier) {
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
    onArtist: (Artist) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (artists.isEmpty()) {
        Box(modifier.padding(top = 24.dp)) {
            Text("No artists yet.", color = PhoebeUi.mutedText, fontSize = 13.sp)
        }
        return
    }
    when (viewMode) {
        LibraryViewMode.Grid -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = modifier,
        ) {
            items(artists, key = { it.id }, contentType = { "artist-card" }) { artist ->
                ArtistCard(
                    catalog = catalog,
                    artist = artist,
                    onArtist = { onArtist(artist) },
                )
            }
        }
        LibraryViewMode.List -> Column(modifier) {
            ArtistsTableHeader()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                items(artists, key = { it.id }, contentType = { "artist" }) { artist ->
                    ArtistRow(
                        catalog = catalog,
                        artist = artist,
                        onArtist = { onArtist(artist) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistCard(
    catalog: CatalogSnapshot,
    artist: Artist,
    onArtist: () -> Unit,
) {
    val genre = remember(catalog, artist.title) { catalogArtistGenre(catalog, artist.title) }
    val albumCount = remember(catalog, artist.title) { catalogAlbumsForArtist(catalog, artist.title).size }
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onArtist)
            .padding(horizontal = 10.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(112.dp)
                .sharedArtworkTransition("artist:${artist.id}")
                .clip(CircleShape),
        ) {
            ArtworkImage(artist.title, artist.thumbUrl, Modifier.fillMaxSize(), radius = 56.dp)
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
            buildString {
                genre?.let { append(it) }
                if (albumCount > 0) {
                    if (length > 0) append(" • ")
                    append("$albumCount ${if (albumCount == 1) "album" else "albums"}")
                }
            }.ifBlank { "Artist" },
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

@Composable
private fun ArtistRow(
    catalog: CatalogSnapshot,
    artist: Artist,
    onArtist: () -> Unit,
) {
    val genre = remember(catalog, artist.title) { catalogArtistGenre(catalog, artist.title) ?: "—" }
    val artistTracks = remember(catalog, artist.title) { catalogTracksForArtist(catalog, artist.title) }
    val albumCount = remember(catalog, artist.title) { catalogAlbumsForArtist(catalog, artist.title).size }
    val songCount = artistTracks.size
    val durationMs = remember(artistTracks) { artistTracks.sumOf { it.durationMs } }
    val playHistory = LocalPlayHistory.current
    val nowMs = LocalNowMs.current
    val lastPlayed = remember(artistTracks, playHistory.byTrack, playHistory.byArtist, artist.title) {
        resolveArtistLastPlayed(artist.title, artistTracks, playHistory)
    }
    val lastPlayedLabel = remember(lastPlayed, nowMs) { formatLastPlayed(lastPlayed, nowMs) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onArtist)
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
    when (viewMode) {
        LibraryViewMode.Grid -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 168.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = modifier,
        ) {
            items(albums, key = { it.id }, contentType = { "album-card" }) { album ->
                AlbumCard(
                    catalog = catalog,
                    album = album,
                    selected = album.id == selectedAlbumId,
                    onSelect = { onSelect(album) },
                    onOpen = { onOpen(album) },
                )
            }
        }
        LibraryViewMode.List -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
            items(albums, key = { it.id }, contentType = { "album-list" }) { album ->
                AlbumListRow(
                    catalog = catalog,
                    album = album,
                    selected = album.id == selectedAlbumId,
                    onSelect = { onSelect(album) },
                    onOpen = { onOpen(album) },
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
            .clickable(onClick = onSelect)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).sharedArtworkTransition("album:${album.id}").clickable(onClick = onOpen)) {
            ArtworkImage(album.title, album.thumbUrl, Modifier.fillMaxSize(), radius = 10.dp)
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
            .clickable(onClick = onSelect)
            .background(if (selected) PhoebeUi.librarySelectedRow else PhoebeUi.libraryHoverRow)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(46.dp).sharedArtworkTransition("album:${album.id}").clickable(onClick = onOpen)) {
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
    onSelect: (Track) -> Unit,
    onPlay: (Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SongsTableHeader(columns)
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(top = 24.dp)) {
                Text("No songs yet.", color = PhoebeUi.mutedText, fontSize = 13.sp)
            }
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "song" }) { index, track ->
                SongRow(
                    track = track,
                    selected = track.id == selectedTrackId,
                    columns = columns,
                    onSelect = { onSelect(track) },
                    onPlay = { onPlay(index) },
                    onAddToUpNext = { onAddToUpNext(track) },
                    onDownload = { onDownload(track) },
                )
            }
        }
    }
}

@Composable
internal fun SongsTableHeader(columns: LibraryColumnVisibility) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TableHeaderCell("# Title", modifier = Modifier.weight(2.2f).padding(start = 92.dp))
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
        Spacer(Modifier.width(78.dp))
    }
}

@Composable
internal fun SongRow(
    track: Track,
    selected: Boolean,
    columns: LibraryColumnVisibility,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onAddToUpNext: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
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
    Row(
        modifier
            .fillMaxWidth()
            .then(if (playlistDragEnabled) Modifier.draggableSong(track) else Modifier)
            .openContextMenuOnSecondaryClick(enabled = hasMenu) { menuExpanded = true }
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .background(
                when {
                    isCurrent -> PhoebeUi.accent.copy(alpha = 0.14f)
                    selected -> PhoebeUi.librarySelectedRow
                    else -> PhoebeUi.libraryHoverRow
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(2.2f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (playlistDragEnabled) {
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
            Box(Modifier.size(42.dp).sharedArtworkTransition("song:${track.id}").clickable(onClick = onPlay), contentAlignment = Alignment.Center) {
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
                modifier = Modifier.sharedBoundsTransition("song:${track.id}:title"),
            )
        }
        TableCellText(track.artist, modifier = Modifier.weight(1.4f), color = PhoebeUi.secondaryText)
        TableAlbumCell(
            album = track.album,
            liked = !columns.favorite && canLike && liked,
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
        TrackDownloadIndicator(
            track = track,
            modifier = Modifier.width(34.dp),
            onDownload = onDownload,
        )
        if (columns.favorite) {
            LikeButton(
                liked = liked,
                enabled = canLike,
                onClick = { likeActions.onToggleLiked(track) },
                modifier = Modifier.width(44.dp),
            )
        }
        Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = { if (hasMenu) menuExpanded = true else onPlay() }),
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
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onPlayTrack(tracks, index) }.padding(vertical = 3.dp),
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
                    .clickable(onClick = onPlay),
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
            .clickable(onClick = onClick)
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
internal fun TableHeaderCell(label: String, modifier: Modifier = Modifier) {
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
internal fun TableCellText(text: String, modifier: Modifier = Modifier, color: Color = PhoebeUi.secondaryText) {
    Text(text, color = color, fontSize = 12.sp, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun TableAlbumCell(
    album: String,
    liked: Boolean,
    downloaded: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
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
            liked = liked,
            downloaded = downloaded,
            iconSize = 11.dp,
        )
    }
}

internal fun formatHoursMinutes(ms: Long): String {
    if (ms <= 0L) return "—"
    val totalMinutes = ms / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/**
 * Returns the most recent play timestamp for an artist's tracks. We aggregate over
 * each track's last-played value (looked up by `track.id` in [PlayHistorySnapshot.byTrack])
 * and fall back to the artist-keyed map. Going through track ids dodges any artist-name
 * normalization mismatch between the catalog's `Artist.title` and what Plex stamps onto
 * individual `Track.artist` strings.
 */
internal fun resolveArtistLastPlayed(
    artistTitle: String,
    tracks: List<Track>,
    history: PlayHistorySnapshot,
): Long? {
    val perTrack = tracks.asSequence()
        .mapNotNull { history.byTrack[it.id] }
        .maxOrNull()
    val perArtist = history.byArtist[artistTitle]
    return when {
        perTrack == null -> perArtist
        perArtist == null -> perTrack
        else -> maxOf(perTrack, perArtist)
    }
}

/**
 * Render a "last played" timestamp as a short human-friendly label
 * ("Just now" / "5m ago" / "3h ago" / "Yesterday" / "3d ago" / "5w ago" /
 * "8mo ago" / "2y ago"). Returns "Never" for null timestamps.
 *
 * A timestamp ahead of [nowMs] (clock skew, or just our 60-second-cached now
 * lagging behind a fresh play) is treated as "Just now" rather than rendered
 * as a placeholder — otherwise you'd see "—" appear briefly every time you
 * play a song. Kept tab-aligned to the existing 110-dp "Last Played" column.
 */
internal fun formatLastPlayed(playedAtMs: Long?, nowMs: Long): String {
    if (playedAtMs == null || playedAtMs <= 0L) return "Never"
    val delta = (nowMs - playedAtMs).coerceAtLeast(0L)
    val minute = 60_000L
    val hour = 60L * minute
    val day = 24L * hour
    val week = 7L * day
    val month = 30L * day
    val year = 365L * day
    return when {
        delta < minute -> "Just now"
        delta < hour -> "${delta / minute}m ago"
        delta < day -> "${delta / hour}h ago"
        delta < 2 * day -> "Yesterday"
        delta < week -> "${delta / day}d ago"
        delta < month -> "${delta / week}w ago"
        delta < year -> "${delta / month}mo ago"
        else -> "${delta / year}y ago"
    }
}

internal fun formatMinutesLabel(ms: Long): String {
    if (ms <= 0L) return "—"
    val totalMinutes = ms / 60_000L
    return "${totalMinutes} min"
}

internal fun formatMinutesSeconds(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0)
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

internal fun displayFileTypeLabel(track: Track): String {
    val path = track.filepath?.lowercase()
    if (!path.isNullOrBlank()) {
        val dot = path.lastIndexOf('.')
        if (dot > 0) {
            val ext = path.substring(dot)
            if (ext.length in 2..6) return ext
        }
    }
    return when (track.audioCodec?.uppercase()) {
        "FLAC" -> ".flac"
        "AAC", "ALAC" -> ".m4a"
        "MP3" -> ".mp3"
        "OGG", "VORBIS" -> ".ogg"
        else -> "—"
    }
}

internal fun displayBitrateLabel(track: Track): String {
    val codec = track.audioCodec?.uppercase()
    if (codec == "FLAC" || codec == "ALAC") return "Lossless"
    val k = track.bitrateKbps
    if (k != null && k > 0) return "$k kbps"
    return "—"
}

internal fun displaySampleRateLabel(track: Track): String {
    return if (isLossless(track)) "44.1 kHz" else "—"
}

internal fun isLossless(track: Track): Boolean {
    val c = track.audioCodec?.uppercase() ?: return false
    return c == "FLAC" || c == "ALAC" || c == "WAV" || c == "APE"
}

/** Sort keys that apply to each tab. The first entry is the tab default. */
internal fun sortKeysFor(filter: LibraryFilterTab): List<LibrarySortBy> = when (filter) {
    LibraryFilterTab.Artists -> listOf(LibrarySortBy.Name, LibrarySortBy.DateAdded)
    LibraryFilterTab.Albums -> listOf(LibrarySortBy.Name, LibrarySortBy.Artist, LibrarySortBy.Year, LibrarySortBy.DateAdded)
    LibraryFilterTab.Songs -> listOf(LibrarySortBy.Name, LibrarySortBy.Album, LibrarySortBy.Artist, LibrarySortBy.Year, LibrarySortBy.DateAdded)
}

internal fun normalizeSortKey(filter: LibraryFilterTab, sortBy: LibrarySortBy): LibrarySortBy {
    val supported = sortKeysFor(filter)
    return if (sortBy in supported) sortBy else supported.first()
}

internal fun sortLabelFor(filter: LibraryFilterTab, sortBy: LibrarySortBy): String {
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

internal fun shortenFilepath(path: String): String {
    val trimmed = path.removePrefix("/").take(48)
    return if (trimmed.length < path.length) "$trimmed…" else trimmed
}

// =====================================================================
// Sort helpers (moved out of PhoebeRoot for reuse)
// =====================================================================

internal fun sortArtistsForLibrary(catalog: CatalogSnapshot, sortBy: LibrarySortBy, ascending: Boolean): List<Artist> {
    val artists = catalog.artists
    if (sortBy == LibrarySortBy.DateAdded) {
        return artists.sortedWith(
            if (ascending) compareBy<Artist>({ it.dateAddedMs ?: Long.MAX_VALUE }, { it.title.lowercase() })
            else compareByDescending<Artist> { it.dateAddedMs ?: Long.MIN_VALUE }.thenBy { it.title.lowercase() },
        )
    }
    return artists.sortedWith(
        if (ascending) compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        else compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title },
    )
}

internal fun sortAlbumsForLibrary(albums: List<Album>, sortBy: LibrarySortBy, ascending: Boolean): List<Album> =
    when (sortBy) {
        LibrarySortBy.Artist -> albums.sortedWith(
            if (ascending) compareBy<Album>({ it.artist.lowercase() }, { it.title.lowercase() })
            else compareByDescending<Album> { it.artist.lowercase() }.thenBy { it.title.lowercase() },
        )
        LibrarySortBy.Year -> {
            val (known, unknown) = albums.partition { it.year != null }
            val sortedKnown = known.sortedWith(
                if (ascending) compareBy<Album>({ it.year!! }, { it.title.lowercase() })
                else compareByDescending<Album> { it.year!! }.thenBy { it.title.lowercase() },
            )
            sortedKnown + unknown.sortedBy { it.title.lowercase() }
        }
        LibrarySortBy.DateAdded -> albums.sortedWith(
            if (ascending) compareBy<Album>({ it.dateAddedMs ?: Long.MAX_VALUE }, { it.title.lowercase() })
            else compareByDescending<Album> { it.dateAddedMs ?: Long.MIN_VALUE }.thenBy { it.title.lowercase() },
        )
        // Name and Album both mean "Album title" here.
        else -> albums.sortedWith(
            if (ascending) compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            else compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
    }

internal fun sortTracksForLibrary(tracks: List<Track>, sortBy: LibrarySortBy, ascending: Boolean): List<Track> =
    when (sortBy) {
        LibrarySortBy.AlbumOrder -> if (ascending) tracks else tracks.asReversed()
        LibrarySortBy.PlaylistOrder -> if (ascending) tracks else tracks.asReversed()
        LibrarySortBy.Album -> tracks.sortedWith(
            if (ascending) compareBy<Track>({ it.album.lowercase() }, { it.title.lowercase() })
            else compareByDescending<Track> { it.album.lowercase() }.thenBy { it.title.lowercase() },
        )
        LibrarySortBy.Artist -> tracks.sortedWith(
            if (ascending) compareBy<Track>({ it.artist.lowercase() }, { it.album.lowercase() }, { it.title.lowercase() })
            else compareByDescending<Track> { it.artist.lowercase() }
                .thenBy { it.album.lowercase() }
                .thenBy { it.title.lowercase() },
        )
        LibrarySortBy.Year -> {
            val (known, unknown) = tracks.partition { it.year != null }
            val sortedKnown = known.sortedWith(
                if (ascending) compareBy<Track>({ it.year!! }, { it.title.lowercase() })
                else compareByDescending<Track> { it.year!! }.thenBy { it.title.lowercase() },
            )
            sortedKnown + unknown.sortedBy { it.title.lowercase() }
        }
        LibrarySortBy.DateAdded -> tracks.sortedWith(
            if (ascending) compareBy<Track>({ it.dateAddedMs ?: Long.MAX_VALUE }, { it.title.lowercase() })
            else compareByDescending<Track> { it.dateAddedMs ?: Long.MIN_VALUE }.thenBy { it.title.lowercase() },
        )
        // Name means "Song title".
        else -> tracks.sortedWith(
            if (ascending) compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            else compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
    }
