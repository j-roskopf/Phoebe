package com.phoebe.app.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoebe.app.data.filterPlaylistsByQuery
import com.phoebe.app.data.filterTracksByQuery
import com.phoebe.app.data.sortTracksForLibrary
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.isLikedSongsPlaylist
import com.phoebe.app.domain.supportsTrackRemoval
import com.phoebe.app.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PlaylistDetailDesktopView(
    playlist: Playlist,
    tracks: List<Track>,
    catalogRefreshing: Boolean,
    searchQuery: String,
    libraryUi: LibraryUiPreferences,
    onSearchQuery: (String) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    modifier: Modifier = Modifier,
    edgePadding: Dp = 36.dp,
    headlineFontSize: TextUnit = 30.sp,
    headlineLineHeight: TextUnit = 35.sp,
    searchPillModifier: Modifier = Modifier.width(270.dp),
    onDownloadPlaylist: (Playlist) -> Unit = {},
) {
    val favoriteActions = LocalFavoriteActions.current
    val playlistActions = LocalPlaylistActions.current
    Column(
        modifier.padding(
            start = edgePadding,
            end = edgePadding,
            top = edgePadding,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        DesktopPlaylistHeader(
            sectionLabel = "Playlist",
            headline = playlist.title,
            searchQuery = searchQuery,
            onSearchQuery = onSearchQuery,
            searchPlaceholder = "Search songs and artists",
            headlineFontSize = headlineFontSize,
            headlineLineHeight = headlineLineHeight,
            searchPillModifier = searchPillModifier,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LikeButton(
                    liked = favoriteActions.isFavorite(playlist),
                    enabled = true,
                    onClick = { favoriteActions.onTogglePlaylist(playlist) },
                )
                PlaylistManagementMenuButton(playlist)
            }
        }

        PlaylistDetailDesktopContent(
            playlist = playlist,
            tracks = tracks,
            catalogRefreshing = catalogRefreshing,
            searchQuery = searchQuery,
            libraryColumns = libraryUi.columns,
            onPlayTracks = onPlayTracks,
            onAddToUpNext = onAddToUpNext,
            onDownload = onDownload,
            onLibraryColumns = onLibraryColumns,
            onMoveTrack = playlistActions.onMovePlaylistTrack,
            onRemoveTracks = playlistActions.onRemovePlaylistTracks,
            onDownloadPlaylist = onDownloadPlaylist,
        )
    }
}

@Composable
fun PlaylistsDesktopView(
    catalog: CatalogSnapshot,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
    edgePadding: Dp = 36.dp,
    headlineFontSize: TextUnit = 30.sp,
    headlineLineHeight: TextUnit = 35.sp,
    searchPillModifier: Modifier = Modifier.width(270.dp),
) {
    val playlistActions = LocalPlaylistActions.current
    var showSmartPlaylistDialog by remember { mutableStateOf(false) }
    if (showSmartPlaylistDialog) {
        SmartPlaylistTemplateDialog(catalog = catalog, onDismiss = { showSmartPlaylistDialog = false })
    }
    Column(
        modifier.padding(
            start = edgePadding,
            end = edgePadding,
            top = edgePadding,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        DesktopPlaylistHeader(
            sectionLabel = "Playlists",
            headline = "Your playlists",
            searchQuery = searchQuery,
            onSearchQuery = onSearchQuery,
            searchPlaceholder = "Search playlists",
            headlineFontSize = headlineFontSize,
            headlineLineHeight = headlineLineHeight,
            searchPillModifier = searchPillModifier,
        ) {
            SmartPlaylistCreateButton(onClick = { showSmartPlaylistDialog = true })
        }
        PlaylistsDesktopContent(
            playlists = playlistActions.playlists,
            playlistsEnabled = playlistActions.playlistsEnabled,
            catalogSyncInProgress = LocalCatalogSyncInProgress.current,
            searchQuery = searchQuery,
            onPlaylist = onPlaylist,
            onShufflePlaylist = playlistActions.onShufflePlaylist,
        )
    }
}

@Composable
private fun PlaylistDetailDesktopContent(
    playlist: Playlist,
    tracks: List<Track>,
    catalogRefreshing: Boolean,
    searchQuery: String,
    libraryColumns: LibraryColumnVisibility,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    onMoveTrack: (Playlist, Int, Int) -> Unit,
    onRemoveTracks: (Playlist, List<Track>) -> Unit,
    onDownloadPlaylist: (Playlist) -> Unit,
) {
    var playlistSortBy by remember(playlist.id) { mutableStateOf(LibrarySortBy.PlaylistOrder) }
    var playlistAscending by remember(playlist.id) { mutableStateOf(true) }
    var editModeEnabled by remember(playlist.id) { mutableStateOf(false) }
    var selectedTrackKeys by remember(playlist.id) { mutableStateOf(setOf<String>()) }
    var confirmRemove by remember(playlist.id) { mutableStateOf(false) }

    LaunchedEffect(editModeEnabled) {
        if (!editModeEnabled) selectedTrackKeys = emptySet()
    }
    val sortedTracks = remember(tracks, playlistSortBy, playlistAscending) {
        sortTracksForLibrary(tracks, playlistSortBy, playlistAscending)
    }
    var filteredTracksResult by remember { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(sortedTracks, searchQuery) {
        filteredTracksResult = withContext(Dispatchers.Default) {
            filterTracksByQuery(sortedTracks, searchQuery)
        }
    }
    val filteredTracks = filteredTracksResult ?: if (searchQuery.isBlank()) sortedTracks else emptyList()
    val editModeAvailable = filteredTracks.isNotEmpty() && playlist.supportsTrackRemoval()
    val editEnabled = editModeEnabled && editModeAvailable
    val selectedTracks = remember(filteredTracks, selectedTrackKeys) {
        filteredTracks.filter { it.playlistRemovalKey() in selectedTrackKeys }
    }
    val toggleTrackSelection = { track: Track ->
        val key = track.playlistRemovalKey()
        selectedTrackKeys = if (key in selectedTrackKeys) {
            selectedTrackKeys - key
        } else {
            selectedTrackKeys + key
        }
    }
    val playFilteredTracks: (List<Track>, Int) -> Unit = { visible, visibleIndex ->
        val sourceTracks = if (
            playlistSortBy == LibrarySortBy.PlaylistOrder &&
            playlistAscending &&
            searchQuery.isBlank()
        ) {
            visible
        } else {
            sortedTracks
        }
        val (queueTracks, queueIndex) = playbackQueueForVisibleTrack(
            sourceTracks,
            visible,
            visibleIndex,
        )
        onPlayTracks(queueTracks, queueIndex)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlaylistTrackSummaryLine(
            totalCount = sortedTracks.size,
            visibleCount = filteredTracks.size,
            searchQuery = searchQuery,
        )
        DetailSectionToolbar(
            sortBy = playlistSortBy,
            sortKeys = listOf(
                LibrarySortBy.PlaylistOrder,
                LibrarySortBy.Name,
                LibrarySortBy.Album,
                LibrarySortBy.Year,
            ),
            sortLabel = { key ->
                when (key) {
                    LibrarySortBy.PlaylistOrder -> "Playlist order"
                    LibrarySortBy.Album -> "Album name"
                    LibrarySortBy.Year -> "Release date"
                    else -> "Song name"
                }
            },
            onSortBy = {
                playlistSortBy = it
                if (it != LibrarySortBy.PlaylistOrder) editModeEnabled = false
            },
            ascending = playlistAscending,
            onAscending = {
                playlistAscending = it
                if (!it) editModeEnabled = false
            },
            columns = libraryColumns,
            onColumns = onLibraryColumns,
            editMode = editModeEnabled,
            editModeAvailable = editModeAvailable,
            onEditMode = { enabled ->
                editModeEnabled = enabled
                if (!enabled) selectedTrackKeys = emptySet()
            },
            actions = {
                DownloadActionButton("Download Playlist", sortedTracks) { onDownloadPlaylist(playlist) }
                PlaylistExportMenu(playlist = playlist)
            },
        )
        if (editEnabled) {
            PlaylistEditActionBar(
                selectedCount = selectedTrackKeys.size,
                totalCount = filteredTracks.size,
                onRemove = { confirmRemove = true },
                onSelectAll = {
                    selectedTrackKeys = filteredTracks.map { it.playlistRemovalKey() }.toSet()
                },
                onClearSelection = { selectedTrackKeys = emptySet() },
            )
        }
        TrackList(
            tracks = filteredTracks,
            empty = if (searchQuery.isNotBlank()) {
                "No tracks / artists in ${playlist.title} match \"$searchQuery\"."
            } else {
                "No tracks loaded for ${playlist.title}."
            },
            catalogRefreshing = catalogRefreshing,
            showLoadingWhenEmpty = searchQuery.isBlank(),
            onPlayTracks = playFilteredTracks,
            onAddToUpNext = onAddToUpNext,
            onDownload = onDownload,
            libraryColumns = libraryColumns,
            onMoveTrack = if (
                playlistSortBy == LibrarySortBy.PlaylistOrder &&
                playlistAscending &&
                searchQuery.isBlank() &&
                !editEnabled
            ) {
                { from, to -> onMoveTrack(playlist, from, to) }
            } else {
                null
            },
            editModeEnabled = editEnabled,
            selectedTrackKeys = selectedTrackKeys,
            onToggleTrackSelection = toggleTrackSelection,
        )
        if (confirmRemove) {
            val count = selectedTracks.size
            ConfirmDeleteDownloadsDialog(
                title = "Remove from playlist?",
                body = "Remove $count ${if (count == 1) "song" else "songs"} from ${playlist.title}?",
                confirmLabel = "Remove",
                onDismiss = { confirmRemove = false },
                onConfirm = {
                    onRemoveTracks(playlist, selectedTracks)
                    confirmRemove = false
                    editModeEnabled = false
                    selectedTrackKeys = emptySet()
                },
            )
        }
    }
}

@Composable
private fun PlaylistsDesktopContent(
    playlists: List<Playlist>,
    playlistsEnabled: Boolean,
    catalogSyncInProgress: Boolean,
    searchQuery: String,
    onPlaylist: (Playlist) -> Unit,
    onShufflePlaylist: (Playlist) -> Unit,
) {
    var visiblePlaylists by remember { mutableStateOf<List<Playlist>?>(null) }
    LaunchedEffect(playlists, searchQuery) {
        visiblePlaylists = withContext(Dispatchers.Default) {
            filterPlaylistsByQuery(playlists, searchQuery)
        }
    }
    val preparedVisiblePlaylists = visiblePlaylists
        ?: if (searchQuery.isBlank()) playlists else emptyList()
    val preparingPlaylists = visiblePlaylists == null &&
        preparedVisiblePlaylists.isEmpty() &&
        playlists.isNotEmpty()
    val showPlaylistSyncProgress = catalogSyncInProgress && searchQuery.isBlank()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!playlistsEnabled) {
            Text(
                "Sign in to your provider, or add a local music folder to use playlists.",
                color = PhoebeUi.mutedText,
                fontSize = 14.sp,
            )
        } else {
            if (showPlaylistSyncProgress) {
                CatalogLoadingStrip()
            }
            if (preparingPlaylists) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryLoadingStrip()
                    Text(
                        "Loading playlists...",
                        color = PhoebeUi.mutedText,
                        fontSize = 14.sp,
                    )
                }
            } else if (preparedVisiblePlaylists.isEmpty()) {
                if (!showPlaylistSyncProgress) {
                    Text(
                        if (searchQuery.isNotBlank()) {
                            "No playlists match \"$searchQuery\"."
                        } else {
                            "No playlists yet. Create one from the sidebar."
                        },
                        color = PhoebeUi.mutedText,
                        fontSize = 14.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(preparedVisiblePlaylists, key = { it.id }, contentType = { "playlist" }) { playlist ->
                        val liked = playlist.isLikedSongsPlaylist()
                        val smart = playlist.isSmartPlaylist()
                        Box(Modifier.draggablePlaylist(playlist).playlistDropTarget(playlist)) {
                            PlaylistRow(
                                icon = when {
                                    liked -> PhoebeIcon.Heart
                                    smart -> PhoebeIcon.InterwovenArrows
                                    else -> null
                                },
                                title = playlist.title,
                                subtitle = "${playlist.trackCount} songs",
                                thumbUrl = playlist.thumbUrl,
                                accent = liked || smart,
                                useContentRowBackground = true,
                                onClick = { onPlaylist(playlist) },
                                onLongClick = { onShufflePlaylist(playlist) },
                                trailingContent = { PlaylistManagementMenuButton(playlist) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopPlaylistHeader(
    sectionLabel: String,
    headline: String,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    searchPlaceholder: String,
    headlineFontSize: TextUnit,
    headlineLineHeight: TextUnit,
    searchPillModifier: Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val titleBlock: @Composable () -> Unit = {
            SectionLabel(sectionLabel, PhoebeUi.accentLight)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    headline,
                    color = PhoebeUi.primaryText,
                    fontSize = headlineFontSize,
                    lineHeight = headlineLineHeight,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                trailingContent?.invoke()
            }
        }
        if (maxWidth < 640.dp) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.fillMaxWidth()) { titleBlock() }
                SearchPill(searchQuery, onSearchQuery, searchPillModifier, searchPlaceholder)
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { titleBlock() }
                SearchPill(searchQuery, onSearchQuery, searchPillModifier, searchPlaceholder)
            }
        }
    }
}
