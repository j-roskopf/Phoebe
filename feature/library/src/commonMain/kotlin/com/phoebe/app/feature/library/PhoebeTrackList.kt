package com.phoebe.app.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.canTogglePlexLike
import com.phoebe.app.domain.isRemoteLibraryTrack
import com.phoebe.app.ui.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackList(
    tracks: List<Track>,
    empty: String,
    catalogRefreshing: Boolean,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onAddToEndOfQueue: (Track) -> Unit = {},
    libraryColumns: LibraryColumnVisibility = FullTrackMetadataColumns,
    showLoadingWhenEmpty: Boolean = true,
    onMoveTrack: ((Int, Int) -> Unit)? = null,
    editModeEnabled: Boolean = false,
    selectedTrackKeys: Set<String> = emptySet(),
    onToggleTrackSelection: ((Track) -> Unit)? = null,
) {
    if (tracks.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (catalogRefreshing && showLoadingWhenEmpty) {
                CatalogLoadingStrip()
            }
            Text(empty, color = PhoebeUi.mutedText, fontSize = 15.sp)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useTable = maxWidth >= 640.dp
        val listState = rememberLazyListState()
        val reorderEnabled = onMoveTrack != null && tracks.size > 1 && !editModeEnabled
        val reorderState = rememberPlaylistTrackReorderState(
            tracks = tracks,
            enabled = reorderEnabled,
            listState = listState,
            rowStep = if (useTable) 56.dp else 72.dp,
            onMove = { from, to -> onMoveTrack?.invoke(from, to) },
        )
        val displayTracks = if (reorderEnabled || reorderState.isDragging) reorderState.tracks else tracks
        LazyColumn(
            state = listState,
            modifier = if (reorderEnabled) reorderState.listModifier() else Modifier,
            verticalArrangement = Arrangement.spacedBy(if (useTable) 2.dp else 10.dp),
        ) {
            if (catalogRefreshing) {
                item(contentType = "loading") { CatalogLoadingStrip(Modifier.padding(bottom = 4.dp)) }
            }
            if (useTable) {
                item(contentType = "track-header") {
                    SongsTableHeader(
                        columns = libraryColumns,
                        showLeadingHandle = reorderEnabled || LocalPlaylistDragEnabled.current,
                        showSelectionColumn = editModeEnabled,
                    )
                }
                itemsIndexed(displayTracks, key = { _, track -> track.reorderKey() }, contentType = { _, _ -> "track" }) { index, track ->
                    val trackKey = track.playlistRemovalKey()
                    SongRow(
                        track = track,
                        selected = trackKey in selectedTrackKeys,
                        columns = libraryColumns,
                        onSelect = {
                            if (editModeEnabled) {
                                onToggleTrackSelection?.invoke(track)
                            } else {
                                onPlayTracks(displayTracks, index)
                            }
                        },
                        onPlay = { onPlayTracks(displayTracks, index) },
                        onAddToUpNext = { onAddToUpNext(track) },
                        onAddToEndOfQueue = { onAddToEndOfQueue(track) },
                        onDownload = { onDownload(track) },
                        modifier = if (reorderEnabled) reorderState.itemModifier(track) else Modifier.animateItem(),
                        leadingHandle = if (reorderEnabled) {
                            { PlaylistTrackReorderHandle(reorderState, track, index) }
                        } else {
                            null
                        },
                        selectionMode = editModeEnabled,
                    )
                }
            } else {
                itemsIndexed(displayTracks, key = { _, track -> track.reorderKey() }, contentType = { _, _ -> "track" }) { index, track ->
                    val trackKey = track.playlistRemovalKey()
                    ContentTrackRow(
                        track = track,
                        libraryColumns = libraryColumns,
                        onPlay = {
                            if (editModeEnabled) {
                                onToggleTrackSelection?.invoke(track)
                            } else {
                                onPlayTracks(displayTracks, index)
                            }
                        },
                        onAddToUpNext = { onAddToUpNext(track) },
                        onAddToEndOfQueue = { onAddToEndOfQueue(track) },
                        onDownload = { onDownload(track) },
                        modifier = if (reorderEnabled) reorderState.itemModifier(track) else Modifier.animateItem(),
                        leadingHandle = if (reorderEnabled) {
                            { PlaylistTrackReorderHandle(reorderState, track, index) }
                        } else {
                            null
                        },
                        selectionMode = editModeEnabled,
                        selected = trackKey in selectedTrackKeys,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContentTrackRow(
    track: Track,
    libraryColumns: LibraryColumnVisibility = FullTrackMetadataColumns,
    onPlay: () -> Unit,
    onAddToUpNext: () -> Unit,
    onDownload: () -> Unit,
    onAddToEndOfQueue: () -> Unit = {},
    modifier: Modifier = Modifier,
    compactLayout: Boolean = false,
    isNowPlaying: Boolean = false,
    nowPlayingIsPlaying: Boolean = false,
    nowPlayingIsBuffering: Boolean = false,
    playCount: Long? = null,
    sharedKey: String? = null,
    leadingHandle: (@Composable () -> Unit)? = null,
    showPlaylistDragHandle: Boolean = true,
    selectionMode: Boolean = false,
    selected: Boolean = false,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val cols = libraryColumns
    val likeActions = LocalLikeActions.current
    val ratingActions = LocalRatingActions.current
    val downloads = LocalDownloadStatus.current
    val canLike = likeActions.likesEnabled && track.canTogglePlexLike()
    val liked = likeActions.isLiked(track)
    val downloaded = downloads.isComplete(track)
    val rating = ratingActions.ratingFor(track)
    val techParts = remember(track.id, cols) {
        buildList {
            if (cols.audioCodec) {
                track.audioCodec?.takeIf { it.isNotBlank() }?.let(::add)
            }
            val bitrateKbps = track.bitrateKbps
            if (cols.bitrate && bitrateKbps != null && bitrateKbps > 0) add("$bitrateKbps kbps")
            if (cols.sampleRate) {
                val rate = displaySampleRateLabel(track)
                if (rate != "—") add(rate)
            }
            if (cols.fileType) {
                val ext = displayFileTypeLabel(track)
                if (ext != "—") add(ext.trimStart('.').uppercase())
            }
        }
    }
    val playlistDragEnabled = LocalPlaylistDragEnabled.current
    val rowDragEnabled = playlistDragEnabled && leadingHandle == null && !selectionMode
    val dragHandleVisible = rowDragEnabled && showPlaylistDragHandle
    val rowShape = RoundedCornerShape(PhoebeUi.shapes.controlRadius)
    Box(if (rowDragEnabled) modifier.draggableSong(track, immediate = !dragHandleVisible) else modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .playTrackTarget(track)
                .clip(rowShape)
                .combinedClickable(onClick = onPlay, onLongClick = if (selectionMode) null else ({ menuExpanded = true }))
                .background(
                    when {
                        selectionMode && selected -> PhoebeUi.accent.copy(alpha = 0.12f)
                        isNowPlaying -> PhoebeUi.accent.copy(alpha = 0.12f)
                        else -> PhoebeUi.subtleFill
                    },
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selectionMode) {
                LibraryCheckbox(checked = selected, size = 18)
            }
            if (leadingHandle != null) {
                leadingHandle()
            } else if (dragHandleVisible) {
                Box(
                    Modifier
                        .draggableSong(track, immediate = true)
                        .padding(horizontal = 2.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PhoebeIconView(PhoebeIcon.Drag, tint = PhoebeUi.mutedText, modifier = Modifier.size(15.dp))
                }
            }
            Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
                TrackArtworkImage(
                    track,
                    Modifier.fillMaxSize().sharedArtworkTransition(sharedKey),
                    elevated = !compactLayout,
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
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.sharedBoundsTransition(sharedKey?.let { "$it:title" }),
                )
                AutoScrollingText(track.artist, color = PhoebeUi.secondaryText, fontSize = 12.sp, lineHeight = 15.sp)
                if (track.album.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        AutoScrollingText(
                            track.album,
                            color = PhoebeUi.mutedText,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        TrackStateBadges(
                            liked = !cols.favorite && canLike && liked,
                            downloaded = downloaded,
                            iconSize = 10.dp,
                        )
                    }
                }
                if (cols.year) {
                    AutoScrollingText(track.year?.toString() ?: "Year —", color = PhoebeUi.mutedText, fontSize = 11.sp)
                }
                if (cols.genre) {
                    AutoScrollingText(track.genre?.takeIf { it.isNotBlank() } ?: "Genre —", color = PhoebeUi.mutedText, fontSize = 11.sp)
                }
                if (cols.filepath) {
                    track.filepath?.takeIf { it.isNotBlank() }?.let { filepath ->
                        Text(filepath, color = PhoebeUi.mutedText, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (techParts.isNotEmpty()) {
                    AutoScrollingText(techParts.joinToString(" · "), color = PhoebeUi.mutedText, fontSize = 11.sp)
                }
            }
            if (playCount != null) {
                Text(
                    formatPlayCount(playCount),
                    color = PhoebeUi.secondaryText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 58.dp, max = 82.dp),
                )
            }
            if (cols.duration) {
                if (compactLayout) {
                    Text(
                        formatDuration(track.durationMs),
                        color = PhoebeUi.mutedText,
                        fontSize = 11.sp,
                    )
                } else {
                    WaveformDurationBar(
                        seed = trackWaveformSeed(track),
                        durationMs = track.durationMs,
                        progress = null,
                        bufferedProgress = null,
                        contentDescription = "Duration ${formatDuration(track.durationMs)}",
                        modifier = Modifier.width(64.dp).height(16.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            if (cols.rating && ratingActions.ratingsEnabled && track.isRemoteLibraryTrack()) {
                RatingStars(
                    rating = rating,
                    enabled = true,
                    onRating = { ratingActions.onRateTrack(track, it) },
                    starSize = 11.dp,
                )
            }
            if (cols.favorite) {
                LikeButton(
                    liked = liked,
                    enabled = canLike,
                    onClick = { likeActions.onToggleLiked(track) },
                )
            }
            TrackDownloadIndicator(
                track = track,
                onDownload = null,
                showIdle = false,
                showComplete = false,
                showFailed = false,
                touchTargetSize = 40.dp,
            )
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable { menuExpanded = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("···", color = PhoebeUi.secondaryText, fontSize = 17.sp)
            }
        }
        TrackActionMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            onAddToUpNext = onAddToUpNext,
            onDownload = onDownload,
            track = track,
            onAddToEndOfQueue = onAddToEndOfQueue,
        )
    }
}

private fun formatPlayCount(playCount: Long): String {
    val playWord = if (playCount == 1L) "play" else "plays"
    return "$playCount $playWord"
}

/**
 * Minimal modal to capture the title for a new playlist. Driven from anywhere that
 * pushes onto [PlaylistActions.onRequestCreatePlaylist]; the caller owns persistence.
 */
@Composable
fun CreatePlaylistDialog(
    initialTracks: List<Track>,
    onDismiss: () -> Unit,
    onConfirm: (title: String) -> Unit,
) {
    var title by remember { mutableStateOf(defaultPlaylistName(initialTracks)) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogShape = RoundedCornerShape(PhoebeUi.shapes.sheetTopRadius)
        val dialogElevation = if (PhoebeUi.design == PhoebeDesignSystem.Minimalist) 8.dp else 30.dp
        Box(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .widthIn(min = 320.dp, max = 440.dp)
                .shadow(elevation = dialogElevation, shape = dialogShape, clip = false)
                .clip(dialogShape)
                .background(PhoebeUi.modalSurface)
                .border(BorderStroke(1.dp, PhoebeUi.accentLight.copy(alpha = 0.18f)), dialogShape)
                .padding(horizontal = 22.dp, vertical = 22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "New Playlist",
                    color = PhoebeUi.primaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                when {
                    initialTracks.size == 1 -> Text(
                        "Adding \"${initialTracks.first().title}\" to a new playlist.",
                        color = PhoebeUi.secondaryText,
                        fontSize = 13.sp,
                    )
                    initialTracks.size > 1 -> Text(
                        "Adding ${initialTracks.size} songs to a new playlist.",
                        color = PhoebeUi.secondaryText,
                        fontSize = 13.sp,
                    )
                    else -> Text(
                        "Pick a name for your new playlist.",
                        color = PhoebeUi.secondaryText,
                        fontSize = 13.sp,
                    )
                }
                PillTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Playlist name",
                    contentDescription = "Playlist name",
                    leadingIcon = PhoebeIcon.Plus,
                    showClearButton = true,
                    clearButtonContentDescription = "Clear playlist name",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = PhoebeUi.secondaryText)
                    }
                    TextButton(
                        onClick = { if (title.isNotBlank()) onConfirm(title.trim()) },
                        enabled = title.isNotBlank(),
                    ) {
                        Text(
                            "Create",
                            color = if (title.isNotBlank()) PhoebeUi.accentLight else PhoebeUi.mutedText,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

private fun defaultPlaylistName(initialTracks: List<Track>): String =
    when {
        initialTracks.isEmpty() -> "New Playlist"
        initialTracks.size == 1 -> initialTracks.first().title.take(40)
        else -> "New Playlist"
    }
