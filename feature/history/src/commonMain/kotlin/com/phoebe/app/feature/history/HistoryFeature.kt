package com.phoebe.app.feature.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phoebe.app.data.HomePlayedTrack
import com.phoebe.app.data.PlayHistorySnapshot
import com.phoebe.app.data.derivationKey
import com.phoebe.app.data.playHistoryRows
import com.phoebe.app.data.trackIndexKey
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.PlayHistoryKind
import com.phoebe.app.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class PlayHistoryUiState(
    val rows: List<HomePlayedTrack>? = null,
    val rankedTotal: Int = 0,
) {
    val showResolving: Boolean
        get() = rows != null && rankedTotal > 0 && rows.size < rankedTotal
}

@Composable
fun PlayHistoryRoute(
    viewModel: PlayHistoryViewModel,
    state: PlayHistoryRouteState,
    libraryUi: LibraryUiPreferences,
    onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    modifier: Modifier = Modifier,
    preferTableLayout: Boolean = false,
    bottomContentPadding: Dp = 0.dp,
    onBack: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onAddToEndOfQueue: (Track) -> Unit = {},
) {
    LaunchedEffect(state) {
        viewModel.update(state)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    PlayHistoryScreen(
        kind = state.kind,
        state = uiState,
        libraryUi = libraryUi,
        onLibraryColumns = onLibraryColumns,
        modifier = modifier,
        preferTableLayout = preferTableLayout,
        nowPlaying = state.nowPlaying,
        bottomContentPadding = bottomContentPadding,
        onBack = onBack,
        onPlayTracks = onPlayTracks,
        onAddToUpNext = onAddToUpNext,
        onDownload = onDownload,
        onAddToEndOfQueue = onAddToEndOfQueue,
    )
}

@Composable
fun PlayHistoryRoute(
    kind: PlayHistoryKind,
    catalog: CatalogSnapshot,
    playHistory: PlayHistorySnapshot,
    resolvedTracksById: Map<String, Track>,
    nowPlaying: HistoryNowPlayingState,
    libraryUi: LibraryUiPreferences,
    onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    modifier: Modifier = Modifier,
    preferTableLayout: Boolean = false,
    bottomContentPadding: Dp = 0.dp,
    onBack: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onAddToEndOfQueue: (Track) -> Unit = {},
) {
    val catalogTrackIndexKey = catalog.trackIndexKey()
    val playHistoryKey = playHistory.derivationKey()
    val resolvedTracksKey = resolvedTracksById.keys.fold(0L) { acc, id -> acc * 31L + id.hashCode() }
    val rows by produceState<List<HomePlayedTrack>?>(null, kind, catalogTrackIndexKey, playHistoryKey, resolvedTracksKey) {
        value = withContext(Dispatchers.Default) {
            playHistoryRows(
                kind = kind,
                catalog = catalog,
                playHistory = playHistory,
                resolvedTracksById = resolvedTracksById,
            )
        }
    }
    val rankedTotal = when (kind) {
        PlayHistoryKind.MostPlayed -> playHistory.topMostPlayed.size
        PlayHistoryKind.RecentlyPlayed -> playHistory.topRecentlyPlayed.size
    }
    PlayHistoryScreen(
        kind = kind,
        state = PlayHistoryUiState(
            rows = rows,
            rankedTotal = rankedTotal,
        ),
        libraryUi = libraryUi,
        onLibraryColumns = onLibraryColumns,
        modifier = modifier,
        preferTableLayout = preferTableLayout,
        nowPlaying = nowPlaying,
        bottomContentPadding = bottomContentPadding,
        onBack = onBack,
        onPlayTracks = onPlayTracks,
        onAddToUpNext = onAddToUpNext,
        onDownload = onDownload,
        onAddToEndOfQueue = onAddToEndOfQueue,
    )
}
