package com.phoebe.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.phoebe.app.data.catalogTracksForAlbum
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

@Composable
internal fun LibraryMobileView(
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
    jellyfinPagination: Boolean = false,
    onJellyfinPage: (JellyfinLibraryPageKind, Int) -> Unit = { _, _ -> },
) {
    var libraryViewMode by remember { mutableStateOf(LibraryViewMode.Grid) }
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
    val artistTotal = catalog.remotePageInfo.artistTotal
    val albumTotal = catalog.remotePageInfo.albumTotal
    val trackTotal = catalog.remotePageInfo.trackTotal
    val remotePageSize = remoteLibraryPageSize(catalog, jellyfinPagination)
    val artistPage = remember(sortedArtists, jellyfinPagination, pageIndex, artistTotal, remotePageSize) {
        libraryPage(sortedArtists, jellyfinPagination, pageIndex, artistTotal, pageSize = remotePageSize)
    }
    val albumPage = remember(sortedAlbums, jellyfinPagination, pageIndex, albumTotal, remotePageSize) {
        libraryPage(sortedAlbums, jellyfinPagination, pageIndex, albumTotal, pageSize = remotePageSize)
    }
    val trackPage = remember(sortedTracks, jellyfinPagination, pageIndex, trackTotal, remotePageSize) {
        libraryPage(sortedTracks, jellyfinPagination, pageIndex, trackTotal, pageSize = remotePageSize)
    }
    LaunchedEffect(filter, sortedArtists.size, sortedAlbums.size, sortedTracks.size) {
        val pageCount = when (filter) {
            LibraryFilterTab.Artists -> artistPage.pageCount
            LibraryFilterTab.Albums -> albumPage.pageCount
            LibraryFilterTab.Songs -> trackPage.pageCount
        }
        if (pageIndex > pageCount - 1) pageIndex = (pageCount - 1).coerceAtLeast(0)
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        MobileLibraryTabs(filter, onFilter)
        Spacer(Modifier.height(14.dp))
        MobileLibraryToolbar(
            prefs = libraryUi,
            filter = filter,
            onSortBy = onLibrarySortBy,
            onAscending = onLibraryAscending,
            libraryViewMode = libraryViewMode,
            onLibraryViewMode = { libraryViewMode = it },
            onColumns = onLibraryColumns,
        )
        Spacer(Modifier.height(10.dp))
        if (catalogRefreshing) {
            LibraryLoadingStrip(Modifier.padding(bottom = 6.dp))
        }
        when (filter) {
            LibraryFilterTab.Artists -> LibraryPaginationControls(artistPage, onPage = {
                if (jellyfinPagination) onJellyfinPage(filter.toJellyfinPageKind(), it)
                pageIndex = it
            })
            LibraryFilterTab.Albums -> LibraryPaginationControls(albumPage, onPage = {
                if (jellyfinPagination) onJellyfinPage(filter.toJellyfinPageKind(), it)
                pageIndex = it
            })
            LibraryFilterTab.Songs -> LibraryPaginationControls(trackPage, onPage = {
                if (jellyfinPagination) onJellyfinPage(filter.toJellyfinPageKind(), it)
                pageIndex = it
            })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (filter) {
                LibraryFilterTab.Artists -> MobileArtistsContent(
                    artists = artistPage.items,
                    viewMode = libraryViewMode,
                    onArtist = onArtist,
                )
                LibraryFilterTab.Albums -> MobileAlbumsContent(
                    catalog = catalog,
                    albums = albumPage.items,
                    viewMode = libraryViewMode,
                    onAlbum = onAlbum,
                )
                LibraryFilterTab.Songs -> MobileSongsList(
                    tracks = trackPage.items,
                    columns = libraryUi.columns,
                    onPlay = { index -> onPlayTracks(trackPage.items, index) },
                    onAddToUpNext = onAddToUpNext,
                    onDownload = onDownload,
                )
            }
        }
    }
}

@Composable
internal fun FavoriteArtistsMobileView(
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
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp)) {
        FavoriteLibraryHeader(
            title = "Favorite Artists",
            count = favoriteArtists.size,
            itemLabel = "artists",
            onBack = onBack,
        )
        Spacer(Modifier.height(14.dp))
        MobileLibraryToolbar(
            prefs = libraryUi,
            filter = LibraryFilterTab.Artists,
            onSortBy = onLibrarySortBy,
            onAscending = onLibraryAscending,
            libraryViewMode = viewMode,
            onLibraryViewMode = { viewMode = it },
            onColumns = onLibraryColumns,
        )
        Spacer(Modifier.height(10.dp))
        MobileArtistsContent(
            artists = favoriteArtists,
            viewMode = viewMode,
            onArtist = onArtist,
        )
    }
}

@Composable
internal fun FavoriteAlbumsMobileView(
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
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp)) {
        FavoriteLibraryHeader(
            title = "Favorite Albums",
            count = favoriteAlbums.size,
            itemLabel = "albums",
            onBack = onBack,
        )
        Spacer(Modifier.height(14.dp))
        MobileLibraryToolbar(
            prefs = libraryUi,
            filter = LibraryFilterTab.Albums,
            onSortBy = onLibrarySortBy,
            onAscending = onLibraryAscending,
            libraryViewMode = viewMode,
            onLibraryViewMode = { viewMode = it },
            onColumns = onLibraryColumns,
        )
        Spacer(Modifier.height(10.dp))
        MobileAlbumsContent(
            catalog = catalog,
            albums = favoriteAlbums,
            viewMode = viewMode,
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
private fun MobileLibraryTabs(filter: LibraryFilterTab, onFilter: (LibraryFilterTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        LibraryFilterTab.entries.forEach { tab ->
            val active = filter == tab
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onFilter(tab) }
                    .background(if (active) PhoebeUi.accent.copy(alpha = 0.22f) else Color.Transparent)
                    .padding(vertical = 10.dp),
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
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun MobileLibraryToolbar(
    prefs: LibraryUiPreferences,
    filter: LibraryFilterTab,
    onSortBy: (LibrarySortBy) -> Unit,
    onAscending: (Boolean) -> Unit,
    libraryViewMode: LibraryViewMode,
    onLibraryViewMode: (LibraryViewMode) -> Unit,
    onColumns: (LibraryColumnVisibility) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        var sortExpanded by remember { mutableStateOf(false) }
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { sortExpanded = true }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Sort: ", color = PhoebeUi.mutedText, fontSize = 12.sp)
                Text(
                    sortLabelFor(filter, prefs.sortBy),
                    color = PhoebeUi.primaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                PhoebeIconView(PhoebeIcon.ChevronDown, tint = PhoebeUi.mutedText, modifier = Modifier.size(12.dp))
            }
            DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                when (filter) {
                    LibraryFilterTab.Artists -> {
                        DropdownMenuItem(
                            text = { Text("Artist name") },
                            onClick = { onSortBy(LibrarySortBy.Name); sortExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Date added") },
                            onClick = { onSortBy(LibrarySortBy.DateAdded); sortExpanded = false },
                        )
                    }
                    LibraryFilterTab.Albums -> {
                        DropdownMenuItem(
                            text = { Text("Album name") },
                            onClick = { onSortBy(LibrarySortBy.Name); sortExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Artist") },
                            onClick = { onSortBy(LibrarySortBy.Artist); sortExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Release date") },
                            onClick = { onSortBy(LibrarySortBy.Year); sortExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Date added") },
                            onClick = { onSortBy(LibrarySortBy.DateAdded); sortExpanded = false },
                        )
                    }
                    LibraryFilterTab.Songs -> {
                        DropdownMenuItem(
                            text = { Text("Song name") },
                            onClick = { onSortBy(LibrarySortBy.Name); sortExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Album name") },
                            onClick = { onSortBy(LibrarySortBy.Album); sortExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Artist") },
                            onClick = { onSortBy(LibrarySortBy.Artist); sortExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Release date") },
                            onClick = { onSortBy(LibrarySortBy.Year); sortExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Date added") },
                            onClick = { onSortBy(LibrarySortBy.DateAdded); sortExpanded = false },
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text(if (prefs.ascending) "Switch to Descending" else "Switch to Ascending") },
                    onClick = { onAscending(!prefs.ascending); sortExpanded = false },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        when (filter) {
            LibraryFilterTab.Artists, LibraryFilterTab.Albums -> Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(PhoebeUi.subtleFill).border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp)).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconToggle(PhoebeIcon.Grid, libraryViewMode == LibraryViewMode.Grid) { onLibraryViewMode(LibraryViewMode.Grid) }
                IconToggle(PhoebeIcon.Library, libraryViewMode == LibraryViewMode.List) { onLibraryViewMode(LibraryViewMode.List) }
            }
            LibraryFilterTab.Songs -> {
                var columnsExpanded by remember { mutableStateOf(false) }
                Box {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { columnsExpanded = true }
                            .background(Color.White.copy(alpha = 0.04f))
                            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        PhoebeIconView(PhoebeIcon.Library, tint = PhoebeUi.secondaryText, modifier = Modifier.size(14.dp))
                    }
                    DropdownMenu(expanded = columnsExpanded, onDismissRequest = { columnsExpanded = false }) {
                        MobileColumnRow("Duration", prefs.columns.duration) { onColumns(prefs.columns.copy(duration = !prefs.columns.duration)) }
                        MobileColumnRow("Audio codec", prefs.columns.audioCodec) { onColumns(prefs.columns.copy(audioCodec = !prefs.columns.audioCodec)) }
                        MobileColumnRow("Bitrate", prefs.columns.bitrate) { onColumns(prefs.columns.copy(bitrate = !prefs.columns.bitrate)) }
                        MobileColumnRow("Sample rate", prefs.columns.sampleRate) { onColumns(prefs.columns.copy(sampleRate = !prefs.columns.sampleRate)) }
                        MobileColumnRow("File type", prefs.columns.fileType) { onColumns(prefs.columns.copy(fileType = !prefs.columns.fileType)) }
                        MobileColumnRow("Date added", prefs.columns.dateAdded) { onColumns(prefs.columns.copy(dateAdded = !prefs.columns.dateAdded)) }
                        MobileColumnRow("Rating", prefs.columns.rating) { onColumns(prefs.columns.copy(rating = !prefs.columns.rating)) }
                        MobileColumnRow("Favorite", prefs.columns.favorite) { onColumns(prefs.columns.copy(favorite = !prefs.columns.favorite)) }
                        MobileColumnRow("File path", prefs.columns.filepath) { onColumns(prefs.columns.copy(filepath = !prefs.columns.filepath)) }
                        MobileColumnRow("Year", prefs.columns.year) { onColumns(prefs.columns.copy(year = !prefs.columns.year)) }
                        MobileColumnRow("Genre", prefs.columns.genre) { onColumns(prefs.columns.copy(genre = !prefs.columns.genre)) }
                    }
                }
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

@Composable
private fun IconToggle(icon: PhoebeIcon, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .background(if (active) PhoebeUi.accent.copy(alpha = 0.22f) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        PhoebeIconView(icon, tint = if (active) PhoebeUi.primaryText else PhoebeUi.secondaryText, modifier = Modifier.size(14.dp))
    }
}

// =====================================================================
// Artists (mobile grid/list)
// =====================================================================

@Composable
private fun MobileArtistsContent(
    artists: List<Artist>,
    viewMode: LibraryViewMode,
    onArtist: (Artist) -> Unit,
) {
    if (artists.isEmpty()) {
        Text("No artists yet.", color = PhoebeUi.mutedText, fontSize = 13.sp)
        return
    }
    when (viewMode) {
        LibraryViewMode.Grid -> {
            val gridState = RetainedLazyGridStates.remember("library-artists-grid")
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(artists, key = { it.id }, contentType = { "artist-card" }) { artist ->
                    MobileArtistCard(artist = artist, onArtist = onArtist)
                }
            }
        }
        LibraryViewMode.List -> {
            val listState = RetainedLazyListStates.remember("library-artists-list")
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(artists, key = { it.id }, contentType = { "artist-row" }) { artist ->
                    MobileArtistRow(artist = artist, onArtist = onArtist)
                }
            }
        }
    }
}

@Composable
private fun MobileArtistCard(
    artist: Artist,
    onArtist: (Artist) -> Unit,
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
            .clickable { onArtist(artist) }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtworkImage(
            artist.title,
            artist.thumbUrl,
            Modifier
                .size(112.dp)
                .sharedArtworkTransition("artist:${artist.id}")
                .clip(CircleShape),
            radius = 56.dp,
            elevated = false,
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
    onArtist: (Artist) -> Unit,
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
            .clickable { onArtist(artist) }
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
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(18.dp))
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
    onAlbum: (Album) -> Unit,
) {
    if (albums.isEmpty()) {
        Text("No albums yet.", color = PhoebeUi.mutedText, fontSize = 13.sp)
        return
    }
    when (viewMode) {
        LibraryViewMode.Grid -> {
            val gridState = RetainedLazyGridStates.remember("library-albums-grid")
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(albums, key = { it.id }, contentType = { "album-card" }) { album ->
                    MobileAlbumCard(catalog = catalog, album = album, onAlbum = onAlbum)
                }
            }
        }
        LibraryViewMode.List -> {
            val listState = RetainedLazyListStates.remember("library-albums-list")
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(albums, key = { it.id }, contentType = { "album-row" }) { album ->
                    MobileAlbumListRow(catalog = catalog, album = album, onAlbum = onAlbum)
                }
            }
        }
    }
}

@Composable
private fun MobileAlbumCard(
    catalog: CatalogSnapshot,
    album: Album,
    onAlbum: (Album) -> Unit,
) {
    val tracks = remember(catalog, album.id) { catalogTracksForAlbum(catalog, album.id) }
    val durationMs = remember(tracks) { tracks.sumOf { it.durationMs } }
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = { onAlbum(album) })
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ArtworkImage(
            album.title,
            album.thumbUrl,
            Modifier.fillMaxWidth().aspectRatio(1f).sharedArtworkTransition("album:${album.id}"),
            radius = 10.dp,
            elevated = false,
        )
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
    onAlbum: (Album) -> Unit,
) {
    val tracks = remember(catalog, album.id) { catalogTracksForAlbum(catalog, album.id) }
    val durationMs = remember(tracks) { tracks.sumOf { it.durationMs } }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = { onAlbum(album) })
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
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(16.dp))
    }
}

// =====================================================================
// Songs list (mobile)
// =====================================================================

@Composable
private fun MobileSongsList(
    tracks: List<Track>,
    columns: LibraryColumnVisibility,
    onPlay: (Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
) {
    if (tracks.isEmpty()) {
        Text("No songs yet.", color = PhoebeUi.mutedText, fontSize = 13.sp)
        return
    }
    val listState = RetainedLazyListStates.remember("library-songs")
    val nowPlaying = LocalNowPlaying.current
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(tracks.size, key = { tracks[it].id }, contentType = { "song-row" }) { index ->
            val track = tracks[index]
            MobileSongRow(
                track = track,
                columns = columns,
                isNowPlaying = track.id == nowPlaying.trackId,
                nowPlayingIsPlaying = nowPlaying.isPlaying,
                nowPlayingIsBuffering = nowPlaying.isBuffering,
                onPlay = { onPlay(index) },
                onAddToUpNext = { onAddToUpNext(track) },
                onDownload = { onDownload(track) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MobileSongRow(
    track: Track,
    columns: LibraryColumnVisibility,
    isNowPlaying: Boolean,
    nowPlayingIsPlaying: Boolean,
    nowPlayingIsBuffering: Boolean,
    onPlay: () -> Unit,
    onAddToUpNext: () -> Unit,
    onDownload: () -> Unit,
) {
    var menuExpanded by remember(track.id) { mutableStateOf(false) }
    val likeActions = LocalLikeActions.current
    val ratingActions = LocalRatingActions.current
    val downloads = LocalDownloadStatus.current
    val canRate = ratingActions.ratingsEnabled && track.isRemoteLibraryTrack()
    val canLike = likeActions.likesEnabled && track.canTogglePlexLike()
    val liked = likeActions.isLiked(track)
    val downloaded = downloads.isComplete(track)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onPlay,
                onLongClick = { menuExpanded = true },
            )
            .background(
                if (isNowPlaying) PhoebeUi.accent.copy(alpha = 0.14f) else Color.Transparent,
            )
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            TrackArtworkImage(
                track,
                Modifier.fillMaxSize().sharedArtworkTransition("song:${track.id}"),
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
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AutoScrollingText(
                track.title,
                color = if (isNowPlaying) PhoebeUi.accentLight else PhoebeUi.primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.sharedBoundsTransition("song:${track.id}:title"),
            )
            AutoScrollingText(
                track.artist,
                color = PhoebeUi.secondaryText,
                fontSize = 11.sp,
            )
            if (track.album.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AutoScrollingText(
                        track.album,
                        color = PhoebeUi.mutedText,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TrackStateBadges(
                        liked = !columns.favorite && canLike && liked,
                        downloaded = downloaded,
                        iconSize = 10.dp,
                    )
                }
            }
            if (columns.rating && canRate) {
                RatingStars(
                    rating = ratingActions.ratingFor(track),
                    enabled = true,
                    onRating = { ratingActions.onRateTrack(track, it) },
                    starSize = 11.dp,
                    gap = 0.dp,
                )
            }
        }
        Text(formatMinutesSeconds(track.durationMs), color = PhoebeUi.mutedText, fontSize = 11.sp)
        if (columns.favorite) {
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

@Composable
internal fun FavoritePlaylistsMobileView(
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playlistActions = LocalPlaylistActions.current
    val favoritePlaylists = remember(playlistActions.playlists) {
        playlistActions.playlists.filter { it.favorite }.sortedBy { it.title.lowercase() }
    }
    val visiblePlaylists = remember(favoritePlaylists, searchQuery) {
        filterPlaylistsByQuery(favoritePlaylists, searchQuery)
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp)) {
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
                Text("${favoritePlaylists.size} playlists", color = PhoebeUi.secondaryText, fontSize = 13.sp)
            }
        }
        SearchPill(
            query = searchQuery,
            onQueryChange = onSearchQuery,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            placeholder = "Search favorite playlists",
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 18.dp),
        ) {
            when {
                favoritePlaylists.isEmpty() -> item(contentType = "empty") {
                    Text(
                        "Favorite playlists will appear here.",
                        color = PhoebeUi.mutedText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                }
                visiblePlaylists.isEmpty() -> item(contentType = "empty-filter") {
                    Text(
                        "No favorite playlists match \"$searchQuery\".",
                        color = PhoebeUi.mutedText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                }
                else -> items(visiblePlaylists, key = { it.id }, contentType = { "favorite-playlist" }) { playlist ->
                    MobilePlaylistRow(
                        icon = PhoebeIcon.Heart,
                        title = playlist.title,
                        subtitle = "${playlist.trackCount} songs",
                        thumbUrl = playlist.thumbUrl,
                        accent = true,
                        onClick = { onPlaylist(playlist) },
                        onLongClick = { playlistActions.onShufflePlaylist(playlist) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlaylistsMobileView(
    catalogRefreshing: Boolean,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playlistActions = LocalPlaylistActions.current
    val playlists = playlistActions.playlists
    val visiblePlaylists = remember(playlists, searchQuery) {
        filterPlaylistsByQuery(playlists, searchQuery)
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SearchPill(
            query = searchQuery,
            onQueryChange = onSearchQuery,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            placeholder = "Search playlists",
        )
        if (catalogRefreshing) {
            LibraryLoadingStrip(Modifier.padding(bottom = 6.dp))
        }
        if (!playlistActions.playlistsEnabled) {
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
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 18.dp),
            ) {
                item(contentType = "create") {
                    MobilePlaylistRow(
                        icon = PhoebeIcon.Plus,
                        title = "Create Playlist",
                        subtitle = null,
                        accent = false,
                        onClick = { playlistActions.onRequestCreatePlaylist(emptyList()) },
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
                } else if (visiblePlaylists.isEmpty()) {
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
                    items(visiblePlaylists, key = { it.id }, contentType = { "playlist" }) { playlist ->
                        val liked = playlist.isLikedSongsPlaylist()
                        MobilePlaylistRow(
                            icon = if (liked) PhoebeIcon.Heart else null,
                            title = playlist.title,
                            subtitle = "${playlist.trackCount} songs",
                            thumbUrl = playlist.thumbUrl,
                            accent = liked,
                            onClick = { onPlaylist(playlist) },
                            onLongClick = { playlistActions.onShufflePlaylist(playlist) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MobilePlaylistRow(
    icon: PhoebeIcon?,
    title: String,
    subtitle: String?,
    thumbUrl: String? = null,
    accent: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
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
            ArtworkImage(title, thumbUrl, Modifier.size(52.dp), radius = 8.dp)
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
            )
            if (subtitle != null) {
                Text(subtitle, color = PhoebeUi.mutedText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(16.dp))
    }
}
