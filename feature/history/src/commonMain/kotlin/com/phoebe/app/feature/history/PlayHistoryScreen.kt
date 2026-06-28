package com.phoebe.app.feature.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.phoebe.app.data.HomePlayedTrack
import com.phoebe.app.data.sortTracksForLibrary
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.PlayHistoryKind
import com.phoebe.app.domain.Track
import com.phoebe.app.feature.library.DetailSectionHeader
import com.phoebe.app.feature.library.MobileSongRow
import com.phoebe.app.feature.library.SongRow
import com.phoebe.app.feature.library.SongsTableHeader
import com.phoebe.app.ui.PhoebeIcon
import com.phoebe.app.ui.PhoebeIconView
import com.phoebe.app.ui.PhoebeUi
import com.phoebe.app.ui.mobileContentTopPadding

@Immutable
data class HistoryNowPlayingState(
    val trackId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
)

@Composable
fun PlayHistoryScreen(
    kind: PlayHistoryKind,
    state: PlayHistoryUiState,
    libraryUi: LibraryUiPreferences,
    onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    modifier: Modifier = Modifier,
    preferTableLayout: Boolean = false,
    nowPlaying: HistoryNowPlayingState = HistoryNowPlayingState(),
    bottomContentPadding: Dp = 0.dp,
    onBack: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
) {
    var sortBy by remember(kind) { mutableStateOf(LibrarySortBy.PlaylistOrder) }
    var ascending by remember(kind) { mutableStateOf(false) }

    val sortedRows = remember(state.rows, sortBy, ascending) {
        val rows = state.rows ?: return@remember null
        if (sortBy == LibrarySortBy.PlaylistOrder) {
            if (ascending) rows.reversed() else rows
        } else {
            val tracks = rows.map { it.track }
            val sortedTracks = sortTracksForLibrary(tracks, sortBy, ascending)
            val rowsByTrackId = rows.associateBy { it.track.id }
            sortedTracks.mapNotNull { rowsByTrackId[it.id] }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = mobileContentTopPadding(12.dp),
                bottom = 12.dp + bottomContentPadding
            ),
    ) {
        val scrollPageHeader = !preferTableLayout && maxWidth < 640.dp

        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val headerContent: @Composable () -> Unit = {
                PlayHistoryHeader(
                    kind = kind,
                    count = state.rows?.size,
                    rankedTotal = state.rankedTotal.takeIf { state.showResolving },
                    onBack = onBack,
                )
            }
            val sectionHeaderContent: @Composable () -> Unit = {
                DetailSectionHeader(
                    title = "Tracks",
                    sortBy = sortBy,
                    sortKeys = listOf(
                        LibrarySortBy.PlaylistOrder,
                        LibrarySortBy.Name,
                        LibrarySortBy.Artist,
                        LibrarySortBy.Album,
                        LibrarySortBy.Year,
                        LibrarySortBy.DateAdded
                    ),
                    sortLabel = { key ->
                        when (key) {
                            LibrarySortBy.PlaylistOrder -> when (kind) {
                                PlayHistoryKind.RecentlyPlayed -> "Last played"
                                PlayHistoryKind.MostPlayed -> "Play count"
                            }
                            LibrarySortBy.Artist -> "Artist"
                            LibrarySortBy.Album -> "Album name"
                            LibrarySortBy.Year -> "Release date"
                            LibrarySortBy.DateAdded -> "Date added"
                            else -> "Song name"
                        }
                    },
                    onSortBy = { sortBy = it },
                    ascending = ascending,
                    onAscending = { ascending = it },
                    columns = libraryUi.columns,
                    onColumns = onLibraryColumns,
                )
            }

            if (!scrollPageHeader) {
                headerContent()
                sectionHeaderContent()
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (sortedRows) {
                    null -> if (scrollPageHeader) {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(bottom = 12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            item(contentType = "history-page-header") { headerContent() }
                            item(contentType = "history-section-header") { sectionHeaderContent() }
                            item(contentType = "loading") {
                                PlayHistoryLoading(Modifier.fillMaxWidth().height(240.dp))
                            }
                        }
                    } else {
                        PlayHistoryLoading(Modifier.fillMaxSize())
                    }
                    else -> PlayHistoryTracks(
                        rows = sortedRows,
                        showPlayCount = kind == PlayHistoryKind.MostPlayed,
                        libraryUi = libraryUi,
                        preferTableLayout = preferTableLayout,
                        pageHeader = headerContent.takeIf { scrollPageHeader },
                        sectionHeader = sectionHeaderContent.takeIf { scrollPageHeader },
                        nowPlaying = nowPlaying,
                        onPlayTracks = onPlayTracks,
                        onAddToUpNext = onAddToUpNext,
                        onDownload = onDownload,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayHistoryLoading(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = PhoebeUi.accentLight,
                strokeWidth = 3.dp,
                trackColor = PhoebeUi.progressTrack,
            )
            Text("Loading listening history...", color = PhoebeUi.secondaryText, fontSize = 14.sp)
        }
    }
}

@Composable
private fun PlayHistoryHeader(
    kind: PlayHistoryKind,
    count: Int?,
    rankedTotal: Int?,
    onBack: () -> Unit,
) {
    val title = when (kind) {
        PlayHistoryKind.RecentlyPlayed -> "Recently Played"
        PlayHistoryKind.MostPlayed -> "Most Played"
    }
    val subtitle = when (count) {
        null -> "Loading..."
        0 -> "No songs yet"
        1 -> if (rankedTotal != null && rankedTotal > 1) "1 of $rankedTotal songs" else "1 song"
        else -> if (rankedTotal != null && count < rankedTotal) "$count of $rankedTotal songs" else "$count songs"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .background(PhoebeUi.elevatedFill)
                .border(BorderStroke(1.dp, PhoebeUi.border), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            PhoebeIconView(PhoebeIcon.Back, tint = PhoebeUi.primaryText, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Listening History".uppercase(),
                color = PhoebeUi.mutedText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.08.em,
            )
            Text(title, color = PhoebeUi.primaryText, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = PhoebeUi.secondaryText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PlayHistoryTracks(
    rows: List<HomePlayedTrack>,
    showPlayCount: Boolean,
    libraryUi: LibraryUiPreferences,
    preferTableLayout: Boolean,
    pageHeader: (@Composable () -> Unit)? = null,
    sectionHeader: (@Composable () -> Unit)? = null,
    nowPlaying: HistoryNowPlayingState,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) {
        if (pageHeader == null && sectionHeader == null) {
            PlayHistoryEmpty("Play songs and your listening history will appear here.", modifier)
            return
        }
    }
    val hasScrollablePageHeader = pageHeader != null || sectionHeader != null
    if (rows.isEmpty() && !hasScrollablePageHeader) {
        return
    }
    val tracks = rows.map { it.track }
    val listState = rememberLazyListState()

    BoxWithConstraints(modifier) {
        val useTable = preferTableLayout || maxWidth >= 640.dp
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(if (useTable) 2.dp else 10.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            if (useTable) {
                item(contentType = "history-header") {
                    SongsTableHeader(
                        columns = libraryUi.columns,
                        showLeadingHandle = false,
                        showSelectionColumn = false,
                        showPlayCount = showPlayCount,
                        showLastPlayed = !showPlayCount,
                    )
                }
                itemsIndexed(rows, key = { _, row -> row.track.id }, contentType = { _, _ -> "track" }) { index, row ->
                    SongRow(
                        track = row.track,
                        selected = false,
                        columns = libraryUi.columns,
                        onSelect = { onPlayTracks(tracks, index) },
                        onPlay = { onPlayTracks(tracks, index) },
                        onAddToUpNext = { onAddToUpNext(row.track) },
                        onDownload = { onDownload(row.track) },
                        playCount = row.playCount.takeIf { showPlayCount },
                        lastPlayedMs = row.lastPlayedMs.takeIf { !showPlayCount },
                    )
                }
            } else {
                pageHeader?.let { header ->
                    item(contentType = "history-page-header") { header() }
                }
                sectionHeader?.let { header ->
                    item(contentType = "history-section-header") { header() }
                }
                if (rows.isEmpty()) {
                    item(contentType = "empty") {
                        PlayHistoryEmpty(
                            "Play songs and your listening history will appear here.",
                            Modifier.fillMaxWidth().height(220.dp),
                        )
                    }
                } else {
                    itemsIndexed(rows, key = { _, row -> row.track.id }, contentType = { _, _ -> "track" }) { index, row ->
                        MobileSongRow(
                            track = row.track,
                            columns = libraryUi.columns,
                            isNowPlaying = row.track.id == nowPlaying.trackId,
                            nowPlayingIsPlaying = nowPlaying.isPlaying,
                            nowPlayingIsBuffering = nowPlaying.isBuffering,
                            onPlay = { onPlayTracks(tracks, index) },
                            onAddToUpNext = { onAddToUpNext(row.track) },
                            onDownload = { onDownload(row.track) },
                            playCount = row.playCount.takeIf { showPlayCount },
                            lastPlayedMs = row.lastPlayedMs.takeIf { !showPlayCount },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayHistoryEmpty(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(PhoebeUi.elevatedFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = PhoebeUi.secondaryText, fontSize = 14.sp)
    }
}
