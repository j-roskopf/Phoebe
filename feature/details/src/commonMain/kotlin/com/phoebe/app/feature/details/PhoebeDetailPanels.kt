package com.phoebe.app.feature.details

import com.phoebe.app.feature.library.*
import com.phoebe.app.ui.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import com.phoebe.app.data.catalogAlbumsForArtist
import com.phoebe.app.data.catalogArtistForAlbum
import com.phoebe.app.data.catalogArtistGenre
import com.phoebe.app.data.catalogTracksForArtist
import com.phoebe.app.data.artistAlbumCountSubtitle
import com.phoebe.app.data.filterAlbumsByQuery
import com.phoebe.app.data.filterTracksByQuery
import com.phoebe.app.data.PlayHistorySnapshot
import com.phoebe.app.data.songCountLabel
import com.phoebe.app.data.sortAlbumsForLibrary
import com.phoebe.app.data.sortTracksForLibrary
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.ArtistRadioAvailability
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.CollectionFacet
import com.phoebe.app.domain.CollectionTarget
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.DownloadState
import com.phoebe.app.domain.LocalFolderMediaSourceConfig
import com.phoebe.app.domain.MediaSourcesState
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.isLocalMediaPlayback
import com.phoebe.app.domain.isLocalPlaylist
import com.phoebe.app.domain.isRemoteLibraryTrack
import com.phoebe.app.domain.isLikedSongsPlaylist
import com.phoebe.app.domain.remoteProviderPrefix
import com.phoebe.app.domain.supportsPlexPlaylists
import com.phoebe.app.domain.supportsTrackRemoval
import com.phoebe.app.platform.currentTimeMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.max

private const val ArtistSimilarArtistDisplayLimit = 20

private data class PlaylistTrackListState(
    val sortedTracks: List<Track>,
    val visibleTracks: List<Track>,
)

@Composable
fun DetailSectionIntro(
    onBack: () -> Unit,
    label: String,
    labelColor: Color = PhoebeUi.accentLight,
    alignBackIconToContentStart: Boolean = false,
    enabled: Boolean = true,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DetailBackButton(
            onBack = onBack,
            modifier = if (alignBackIconToContentStart) Modifier.offset(x = (-10).dp) else Modifier,
            enabled = enabled,
        )
        SectionLabel(label, labelColor)
    }
}

@Composable
fun SongDetailPanel(
    track: Track,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onOpenLyrics: (Track) -> Unit = {},
) {
    val nowMs = LocalNowMs.current
    val playHistory = LocalPlayHistory.current
    val lastPlayed = playHistory.byTrack[track.id]
    val mobileChromeBottom = LocalMobileChromePadding.current.bottom
    val metadataEditorActions = LocalMetadataEditorActions.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxWidth < 520.dp
        val horizontalPadding = if (compact) 20.dp else 28.dp
        val compactBottomPadding = mobileChromeBottom + 24.dp
        val bottomContentPadding = if (compact) {
            if (compactBottomPadding > 88.dp) compactBottomPadding else 88.dp
        } else {
            mobileChromeBottom
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding),
            contentPadding = PaddingValues(
                top = mobileContentTopPadding(24.dp),
                bottom = bottomContentPadding
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item("intro") {
                if (compact) {
                    SongDetailMobileTopBar(
                        onBack = onBack,
                        onMoreClick = { metadataEditorActions.onRequestEdit(track) },
                    )
                } else {
                    DetailSectionIntro(
                        onBack = onBack,
                        label = "Song",
                    )
                }
            }
            item("hero") {
                SongDetailHero(
                    track = track,
                    compact = compact,
                    onPlay = onPlay,
                    onAddToUpNext = onAddToUpNext,
                    onDownload = onDownload,
                    onOpenLyrics = onOpenLyrics,
                )
            }
            item("metadata") {
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 14.dp)) {
                    HomePanelLike { SongDetailMetadataRows(track, nowMs, lastPlayed, playHistory.playCountByTrack[track.id] ?: 0L) }
                }
            }
        }
    }
}

@Composable
private fun SongDetailMobileTopBar(
    onBack: () -> Unit,
    onMoreClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .offset(x = (-10).dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            PhoebeIconView(PhoebeIcon.Back, tint = PhoebeUi.primaryText, modifier = Modifier.size(21.dp))
        }
        Text(
            "Song",
            color = PhoebeUi.primaryText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(44.dp)
                .offset(x = 10.dp)
                .clip(CircleShape)
                .clickable(onClick = onMoreClick),
            contentAlignment = Alignment.Center,
        ) {
            PhoebeIconView(PhoebeIcon.More, tint = PhoebeUi.secondaryText, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
private fun SongDetailHero(
    track: Track,
    compact: Boolean,
    onPlay: () -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onOpenLyrics: (Track) -> Unit,
) {
    if (compact) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.78f)
                    .widthIn(max = 320.dp)
                    .aspectRatio(1f)
                    .align(Alignment.CenterHorizontally),
            ) {
                FlippableSongArtwork(
                    track = track,
                    Modifier.fillMaxSize(),
                    artworkModifier = Modifier.sharedArtworkTransition("song:${track.id}"),
                    radius = 14.dp,
                    maxDecodeDimension = HeroArtworkMaxDecodeDimension,
                )
                AudioQualityBadge(
                    track = track,
                    onArtwork = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                )
            }
            SongDetailText(
                track = track,
                titleSize = 30.sp,
                titleLineHeight = 34.sp,
                titleMaxLines = 3,
                autoScroll = true,
                horizontalAlignment = Alignment.CenterHorizontally,
                textAlign = TextAlign.Center,
            )
            SongMobileActionRows(track, onPlay, onAddToUpNext, onDownload, onOpenLyrics)
        }
    } else {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            FlippableSongArtwork(
                track = track,
                Modifier
                    .size(180.dp)
                    .sharedArtworkTransition("song:${track.id}"),
                radius = 14.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SongDetailText(track, titleSize = 30.sp, titleLineHeight = 34.sp, titleMaxLines = 2)
                SongActionRow(
                    track = track,
                    scrollable = false,
                    onPlay = onPlay,
                    onAddToUpNext = onAddToUpNext,
                    onDownload = onDownload,
                    onOpenLyrics = onOpenLyrics,
                )
            }
        }
    }
}

@Composable
private fun SongDetailText(
    track: Track,
    titleSize: TextUnit,
    titleLineHeight: TextUnit,
    titleMaxLines: Int,
    autoScroll: Boolean = false,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    textAlign: TextAlign = TextAlign.Start,
) {
    val ratingActions = LocalRatingActions.current
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (autoScroll) {
            AutoScrollingText(
                track.title,
                color = PhoebeUi.primaryText,
                fontSize = titleSize,
                lineHeight = titleLineHeight,
                fontWeight = FontWeight.Black,
                textAlign = textAlign,
                modifier = Modifier
                    .fillMaxWidth()
                    .sharedBoundsTransition("song:${track.id}:title"),
            )
            AutoScrollingText(
                track.artist,
                color = PhoebeUi.secondaryText,
                fontSize = 17.sp,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
            )
            AutoScrollingText(
                track.album,
                color = PhoebeUi.mutedText,
                fontSize = 14.sp,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                track.title,
                color = PhoebeUi.primaryText,
                fontSize = titleSize,
                lineHeight = titleLineHeight,
                fontWeight = FontWeight.Black,
                textAlign = textAlign,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .sharedBoundsTransition("song:${track.id}:title"),
            )
            Text(
                track.artist,
                color = PhoebeUi.secondaryText,
                fontSize = 17.sp,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                track.album,
                color = PhoebeUi.mutedText,
                fontSize = 14.sp,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (ratingActions.ratingsEnabled && track.isRemoteLibraryTrack()) {
            SongRatingRow(track = track, ratingActions = ratingActions)
        }
    }
}

@Composable
private fun SongRatingRow(
    track: Track,
    ratingActions: RatingActions,
) {
    val rating = ratingActions.ratingFor(track)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RatingStars(
            rating = rating,
            enabled = true,
            onRating = { ratingActions.onRateTrack(track, it) },
            starSize = 16.dp,
            showClear = true,
        )
        Text(
            rating?.let { formatSongRatingValue(it) } ?: "Unrated",
            color = PhoebeUi.secondaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun formatSongRatingValue(rating: Float): String {
    val rounded = (rating * 10f).roundToInt() / 10f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
}

@Composable
private fun SongMobileActionRows(
    track: Track,
    onPlay: () -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onOpenLyrics: (Track) -> Unit,
) {
    val metadataEditorActions = LocalMetadataEditorActions.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SongActionButton(
                icon = PhoebeIcon.Play,
                label = "Play",
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .playTrackTarget(track),
                prominent = true,
                onClick = onPlay,
            )
            SongActionButton(
                icon = PhoebeIcon.Queue,
                label = "Up Next",
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) { onAddToUpNext(track) }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SongActionButton(
                icon = PhoebeIcon.Lyrics,
                label = "Lyrics",
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                compact = true,
            ) { onOpenLyrics(track) }
            SongActionButton(
                icon = PhoebeIcon.Edit,
                label = "Edit Metadata",
                modifier = Modifier
                    .weight(1.35f)
                    .height(40.dp),
                compact = true,
            ) {
                metadataEditorActions.onRequestEdit(track)
            }
            SongDownloadActionButton(
                track = track,
                onDownload = onDownload,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                compact = true,
            )
        }
    }
}

@Composable
private fun SongActionRow(
    track: Track,
    scrollable: Boolean,
    onPlay: () -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onOpenLyrics: (Track) -> Unit,
) {
    val metadataEditorActions = LocalMetadataEditorActions.current
    if (scrollable) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 4.dp),
        ) {
            item("play") { SongActionButton(PhoebeIcon.Play, "Play", Modifier.playTrackTarget(track), onClick = onPlay) }
            item("up-next") { SongActionButton(PhoebeIcon.Queue, "Up Next", onClick = { onAddToUpNext(track) }) }
            item("lyrics") { SongActionButton(PhoebeIcon.Lyrics, "Lyrics", onClick = { onOpenLyrics(track) }) }
            item("edit-metadata") {
                SongActionButton(PhoebeIcon.Edit, "Edit Metadata", onClick = {
                    metadataEditorActions.onRequestEdit(track)
                })
            }
            item("download") { SongDownloadActionButton(track = track, onDownload = onDownload) }
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SongActionButton(PhoebeIcon.Play, "Play", Modifier.playTrackTarget(track), onClick = onPlay)
            SongActionButton(PhoebeIcon.Queue, "Up Next", onClick = { onAddToUpNext(track) })
            SongActionButton(PhoebeIcon.Lyrics, "Lyrics", onClick = { onOpenLyrics(track) })
            SongActionButton(PhoebeIcon.Edit, "Edit Metadata", onClick = {
                metadataEditorActions.onRequestEdit(track)
            })
            SongDownloadActionButton(track = track, onDownload = onDownload)
        }
    }
}

@Composable
private fun SongDownloadActionButton(
    track: Track,
    onDownload: (Track) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val downloads = LocalDownloadStatus.current
    val downloadActions = LocalDownloadActions.current
    val summary = downloads.summarize(listOf(track))
    val isActive = summary.active > 0
    val isComplete = summary.total > 0 &&
        (summary.complete == summary.total || (summary.complete > 0 && summary.complete + summary.unavailable == summary.total))
    val hasFailed = summary.failed > 0
    var confirmCancel by remember(track.id) { mutableStateOf(false) }
    var confirmDelete by remember(track.id) { mutableStateOf(false) }

    SongActionButton(
        icon = if (isComplete) PhoebeIcon.Check else PhoebeIcon.Download,
        label = when {
            isActive -> activeDownloadActionLabel()
            isComplete -> "Downloaded"
            hasFailed -> "Download Failed"
            else -> "Download"
        },
        modifier = modifier,
        compact = compact,
    ) {
        when {
            isActive -> confirmCancel = true
            isComplete -> confirmDelete = true
            else -> onDownload(track)
        }
    }

    if (confirmCancel) {
        ConfirmDeleteDownloadsDialog(
            title = "Cancel Download?",
            body = "Stop downloading this song?",
            confirmLabel = "Cancel Download",
            onDismiss = { confirmCancel = false },
            onConfirm = {
                downloadActions.onCancelDownloadedTracks(listOf(track))
                confirmCancel = false
            },
        )
    }
    if (confirmDelete) {
        ConfirmDeleteDownloadsDialog(
            title = "Delete Download?",
            body = "Remove the downloaded copy of this song from this device?",
            confirmLabel = "Delete Download",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                downloadActions.onDeleteDownloadedTracks(listOf(track))
                confirmDelete = false
            },
        )
    }
}

@Composable
fun SongActionButton(
    icon: PhoebeIcon,
    label: String,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val background = if (prominent) PhoebeUi.primaryText else PhoebeUi.elevatedFill
    val contentColor = if (prominent) PhoebeUi.shellTop else PhoebeUi.primaryText
    val iconTint = if (prominent) PhoebeUi.shellTop else PhoebeUi.accentLight
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .background(background)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(999.dp))
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 8.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        PhoebeIconView(icon, tint = iconTint, modifier = Modifier.size(if (compact) 14.dp else 15.dp))
        Spacer(Modifier.width(if (compact) 5.dp else 7.dp))
        Text(
            label,
            color = contentColor,
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HomePanelLike(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PhoebeUi.panel)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        content = content,
    )
}

@Composable
private fun PlaylistTrackSummaryLine(
    totalCount: Int,
    visibleCount: Int,
    searchQuery: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$totalCount ${if (totalCount == 1) "song" else "songs"}",
            color = PhoebeUi.secondaryText,
            fontSize = 14.sp,
        )
        if (searchQuery.isNotBlank()) {
            Text(" · ", color = PhoebeUi.mutedText, fontSize = 14.sp)
            Text(
                "$visibleCount ${if (visibleCount == 1) "result" else "results"}",
                color = PhoebeUi.secondaryText,
                fontSize = 14.sp,
            )
        }
    }
}

private fun playbackQueueForVisibleTrack(
    sourceTracks: List<Track>,
    visibleTracks: List<Track>,
    visibleIndex: Int,
): Pair<List<Track>, Int> {
    val visibleTrack = visibleTracks.getOrNull(visibleIndex) ?: return visibleTracks to visibleIndex
    val sourceIndex = sourceTracks.indexOfFirst { it.reorderKey() == visibleTrack.reorderKey() }
    return if (sourceIndex >= 0) sourceTracks.rotatedForPlayback(sourceIndex) to 0 else visibleTracks to visibleIndex
}

private fun List<Track>.rotatedForPlayback(startIndex: Int): List<Track> =
    if (startIndex > 0 && startIndex in indices) drop(startIndex) + take(startIndex) else this

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    seed: String,
    thumbUrl: String? = null,
    modifier: Modifier = Modifier,
    elevatedArtwork: Boolean = true,
    sharedKey: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(Color.White.copy(alpha = 0.045f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtworkImage(seed, thumbUrl, Modifier.size(46.dp).sharedArtworkTransition(sharedKey), elevated = elevatedArtwork)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = PhoebeUi.primaryText,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition(sharedKey?.let { "$it:title" }),
            )
            Text(
                subtitle,
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition(sharedKey?.let { "$it:subtitle" }),
            )
        }
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun PlayRadioActionButton(
    starting: Boolean,
    modifier: Modifier = Modifier,
    label: String = "Play Radio",
    onClick: () -> Unit,
) {
    LibraryToolbarButton(
        icon = PhoebeIcon.Play,
        label = when {
            starting && label == "Radio" -> "Starting..."
            starting -> "Starting Radio..."
            else -> label
        },
        modifier = modifier,
        enabled = !starting,
        iconTint = if (starting) PhoebeUi.mutedText else PhoebeUi.accentLight,
        onClick = onClick,
    )
}

@Composable
private fun PlayAllActionButton(
    tracks: List<Track>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    LibraryToolbarButton(
        icon = PhoebeIcon.Play,
        label = "Play All",
        value = tracks.takeIf { it.isNotEmpty() }?.size?.let { "$it" },
        modifier = modifier.playAllTarget(),
        enabled = tracks.isNotEmpty(),
        iconTint = if (tracks.isEmpty()) PhoebeUi.mutedText else PhoebeUi.accentLight,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MobileDetailPrimaryPlayButton(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    label: String = "Play All",
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .height(48.dp)
            .clip(shape)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .background(Color.White.copy(alpha = if (enabled) 0.08f else 0.04f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = if (enabled) 0.10f else 0.05f)), shape)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        PhoebeIconView(
            PhoebeIcon.Play,
            tint = if (enabled) PhoebeUi.accentLight else PhoebeUi.mutedText,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = if (enabled) PhoebeUi.primaryText else PhoebeUi.mutedText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun MobileDetailSecondaryButton(
    icon: PhoebeIcon,
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .height(48.dp)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .background(Color.White.copy(alpha = 0.08f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        PhoebeIconView(
            icon,
            tint = if (enabled) PhoebeUi.accentLight else PhoebeUi.mutedText,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = if (enabled) PhoebeUi.primaryText else PhoebeUi.mutedText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun MobileDetailIconSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun MobileDetailTopBar(
    label: String,
    onBack: () -> Unit,
    showOverflow: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            DetailBackButton(onBack = onBack)
        }
        Text(
            label,
            color = PhoebeUi.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        if (showOverflow) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                PhoebeIconView(PhoebeIcon.More, tint = PhoebeUi.primaryText, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MobileArtistDetailHeader(
    artist: Artist,
    artistThumbUrl: String?,
    albumsCount: Int,
    tracks: List<Track>,
    visibleTracks: List<Track>,
    albumWord: String,
    songWord: String,
    artistRadioAvailability: ArtistRadioAvailability?,
    artistRadioStarting: Boolean,
    favoriteActions: FavoriteActions,
    onBack: () -> Unit,
    onArtworkClick: () -> Unit,
    onPlayAllTracks: (List<Track>) -> Unit,
    onShuffleAllTracks: (List<Track>) -> Unit,
    onPlayArtistRadio: (Artist) -> Unit,
    onDownloadArtist: (Artist) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        MobileDetailTopBar(label = "Artist", onBack = onBack, showOverflow = false)
        Box(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(28.dp))
                .clickable(onClick = onArtworkClick),
        ) {
            ArtworkImage(
                artist.title,
                artistThumbUrl,
                Modifier
                    .fillMaxSize()
                    .sharedArtworkTransition("artist:${artist.id}"),
                radius = 28.dp,
                elevated = false,
                maxDecodeDimension = HeroArtworkMaxDecodeDimension,
                alignment = Alignment.TopCenter,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                artist.title,
                color = PhoebeUi.primaryText,
                fontSize = 40.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition("artist:${artist.id}:title"),
            )
            Text(
                "${albumsCount} $albumWord · ${tracks.size} $songWord",
                color = PhoebeUi.secondaryText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MobileDetailPrimaryPlayButton(
                enabled = visibleTracks.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = { onPlayAllTracks(visibleTracks) },
                onLongClick = { onShuffleAllTracks(visibleTracks) },
            )
            if (artistRadioAvailability == ArtistRadioAvailability.Available) {
                MobileDetailSecondaryButton(
                    icon = PhoebeIcon.Radio,
                    label = if (artistRadioStarting) "Starting" else "Radio",
                    enabled = !artistRadioStarting,
                    onClick = { onPlayArtistRadio(artist) },
                )
            }
            MobileDetailIconSurface {
                DownloadActionButton("Download", tracks, iconOnly = true) { onDownloadArtist(artist) }
            }
            MobileDetailIconSurface {
                LikeButton(
                    liked = favoriteActions.isFavorite(artist),
                    enabled = true,
                    onClick = { favoriteActions.onToggleArtist(artist) },
                )
            }
        }
    }
}

@Composable
private fun MobileAlbumDetailHeader(
    album: Album,
    tracks: List<Track>,
    visibleTracks: List<Track>,
    artist: Artist?,
    favoriteActions: FavoriteActions,
    onBack: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onDownloadAlbum: (Album) -> Unit,
    onArtist: (Artist) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MobileDetailTopBar(label = "Album", onBack = onBack, showOverflow = false)
        ArtworkImage(
            album.title,
            album.thumbUrl,
            Modifier
                .fillMaxWidth()
                .widthIn(max = 320.dp)
                .aspectRatio(1f)
                .sharedArtworkTransition("album:${album.id}"),
            radius = 24.dp,
            elevated = true,
            maxDecodeDimension = HeroArtworkMaxDecodeDimension,
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                album.title,
                color = PhoebeUi.primaryText,
                fontSize = 32.sp,
                lineHeight = 35.sp,
                fontWeight = FontWeight.Black,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition("album:${album.id}:title"),
            )
            Text(
                album.artist,
                color = PhoebeUi.secondaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .sharedBoundsTransition("album:${album.id}:subtitle")
                    .clickable(enabled = artist != null) {
                        artist?.let(onArtist)
                    },
            )
            album.year?.let { year ->
                Text(year.toString(), color = PhoebeUi.mutedText, fontSize = 13.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MobileDetailPrimaryPlayButton(
                enabled = visibleTracks.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = { onPlayTracks(visibleTracks, 0) },
            )
            MobileDetailIconSurface {
                DownloadActionButton("Download", tracks, iconOnly = true) { onDownloadAlbum(album) }
            }
            MobileDetailIconSurface {
                LikeButton(
                    liked = favoriteActions.isFavorite(album),
                    enabled = true,
                    onClick = { favoriteActions.onToggleAlbum(album) },
                )
            }
        }
    }
}

@Composable
private fun MobilePlaylistDetailHeader(
    playlist: Playlist,
    tracks: List<Track>,
    visibleTracks: List<Track>,
    searchQuery: String,
    favoriteActions: FavoriteActions,
    ratingActions: RatingActions,
    onBack: () -> Unit,
    onSearchQuery: (String) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onDownloadPlaylist: (Playlist) -> Unit,
    onCancelDownloadPlaylist: (Playlist) -> Unit,
    onDeleteDownloadPlaylist: (Playlist) -> Unit,
) {
    val rating = ratingActions.ratingFor(playlist)?.coerceIn(0f, 5f)?.takeIf { it > 0f }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MobilePlaylistDetailTopBar(onBack = onBack, playlist = playlist)
        ArtworkImage(
            playlist.title,
            playlist.thumbUrl,
            Modifier
                .fillMaxWidth()
                .widthIn(max = 320.dp)
                .aspectRatio(1f)
                .sharedArtworkTransition("playlist:${playlist.id}"),
            radius = 24.dp,
            elevated = true,
            maxDecodeDimension = HeroArtworkMaxDecodeDimension,
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                playlist.title,
                color = PhoebeUi.primaryText,
                fontSize = 34.sp,
                lineHeight = 37.sp,
                fontWeight = FontWeight.Black,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsTransition("playlist:${playlist.id}:title"),
            )
            Text(
                playlistCuratorLine(playlist),
                color = PhoebeUi.secondaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaylistTrackSummaryLine(
                    totalCount = tracks.size.takeIf { it > 0 } ?: playlist.trackCount,
                    visibleCount = visibleTracks.size,
                    searchQuery = searchQuery,
                )
                if (ratingActions.ratingsEnabled && rating != null) {
                    Text("·", color = PhoebeUi.mutedText, fontSize = 14.sp)
                    RatingStars(
                        rating = rating,
                        enabled = true,
                        onRating = { ratingActions.onRatePlaylist(playlist, it) },
                        starSize = 14.dp,
                        showClear = false,
                    )
                    Text(
                        playlistRatingLabel(rating),
                        color = PhoebeUi.secondaryText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MobileDetailIconSurface {
                DownloadActionButton(
                    label = "Download",
                    tracks = tracks,
                    iconOnly = true,
                    onCancel = { onCancelDownloadPlaylist(playlist) },
                    onDelete = { onDeleteDownloadPlaylist(playlist) },
                ) { onDownloadPlaylist(playlist) }
            }
            MobileDetailIconSurface {
                LikeButton(
                    liked = favoriteActions.isFavorite(playlist),
                    enabled = true,
                    onClick = { favoriteActions.onTogglePlaylist(playlist) },
                )
            }
            SearchPill(
                query = searchQuery,
                onQueryChange = onSearchQuery,
                modifier = Modifier.weight(1f),
                placeholder = "Search songs and artists",
            )
        }
        MobileDetailPrimaryPlayButton(
            enabled = visibleTracks.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            onClick = { onPlayTracks(visibleTracks, 0) },
        )
    }
}

@Composable
private fun MobilePlaylistDetailTopBar(
    onBack: () -> Unit,
    playlist: Playlist,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            DetailBackButton(onBack = onBack)
        }
        Text(
            "Playlist",
            color = PhoebeUi.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        if (playlistShowsManagementMenu(playlist)) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                PlaylistManagementMenuButton(playlist)
            }
        }
    }
}

private fun playlistCuratorLine(playlist: Playlist): String {
    val source = playlist.id.substringBefore(':', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
        ?.replaceFirstChar { char -> char.uppercaseChar() }
    return source?.let { "Curated by $it" } ?: "Curated playlist"
}

private fun playlistRatingLabel(rating: Float): String {
    val tenths = (rating.coerceIn(0f, 5f) * 10f).roundToInt()
    return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}

private fun playlistShowsManagementMenu(playlist: Playlist): Boolean =
    !playlist.isLikedSongsPlaylist() && (
        playlist.isSmartPlaylist() ||
            playlist.isLocalPlaylist() ||
            playlist.remoteProviderPrefix() == "plex"
    )

@Composable
private fun DesktopArtistDetailHeader(
    artist: Artist,
    artistThumbUrl: String?,
    albumsCount: Int,
    tracks: List<Track>,
    visibleTracks: List<Track>,
    albumWord: String,
    songWord: String,
    artistRadioAvailability: ArtistRadioAvailability?,
    artistRadioStarting: Boolean,
    favoriteActions: FavoriteActions,
    onBack: () -> Unit,
    onArtworkClick: () -> Unit,
    onPlayAllTracks: (List<Track>) -> Unit,
    onShuffleAllTracks: (List<Track>) -> Unit,
    onPlayArtistRadio: (Artist) -> Unit,
    onDownloadArtist: (Artist) -> Unit,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    immersive: Boolean,
    modifier: Modifier = Modifier,
) {
    DesktopDetailHero(
        label = "Artist",
        title = artist.title,
        eyebrow = "Artist",
        meta = listOf("${albumsCount} $albumWord", "${tracks.size} $songWord"),
        onBack = onBack,
        immersive = immersive,
        modifier = modifier,
        searchQuery = searchQuery,
        onSearchQuery = onSearchQuery,
        backgroundArtwork = { revealFullArtwork ->
            ArtworkImage(
                artist.title,
                artistThumbUrl,
                Modifier.fillMaxSize(),
                radius = 0.dp,
                shape = RoundedCornerShape(0.dp),
                elevated = false,
                maxDecodeDimension = HeroArtworkMaxDecodeDimension,
                alignment = Alignment.Center,
                contentScale = if (revealFullArtwork) ContentScale.Fit else ContentScale.Crop,
            )
        },
        artwork = {
            Box(
                Modifier
                    .fillMaxSize()
                    .sharedArtworkTransition("artist:${artist.id}")
                    .clip(RoundedCornerShape(28.dp))
                    .clickable(onClick = onArtworkClick),
            ) {
                ArtworkImage(
                    artist.title,
                    artistThumbUrl,
                    Modifier.fillMaxSize(),
                    radius = 28.dp,
                    elevated = true,
                    maxDecodeDimension = HeroArtworkMaxDecodeDimension,
                    alignment = Alignment.TopCenter,
                )
            }
        },
        titleModifier = Modifier.sharedBoundsTransition("artist:${artist.id}:title"),
        actions = {
            PlayAllActionButton(
                tracks = visibleTracks,
                onClick = { onPlayAllTracks(visibleTracks) },
                onLongClick = { onShuffleAllTracks(visibleTracks) },
            )
            if (artistRadioAvailability == ArtistRadioAvailability.Available) {
                PlayRadioActionButton(
                    starting = artistRadioStarting,
                    label = "Radio",
                    onClick = { onPlayArtistRadio(artist) },
                )
            }
            DownloadActionButton("Download", tracks) { onDownloadArtist(artist) }
            DesktopDetailIconSurface {
                LikeButton(
                    liked = favoriteActions.isFavorite(artist),
                    enabled = true,
                    onClick = { favoriteActions.onToggleArtist(artist) },
                )
            }
        },
    )
}

@Composable
private fun DesktopAlbumDetailHeader(
    album: Album,
    tracks: List<Track>,
    visibleTracks: List<Track>,
    artist: Artist?,
    favoriteActions: FavoriteActions,
    onBack: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onDownloadAlbum: (Album) -> Unit,
    onArtist: (Artist) -> Unit,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    immersive: Boolean,
    modifier: Modifier = Modifier,
) {
    val meta = buildList {
        album.year?.let { add(it.toString()) }
        add(songCountLabel(tracks.size))
    }
    DesktopDetailHero(
        label = "Album",
        title = album.title,
        eyebrow = "Album",
        subtitle = album.artist,
        meta = meta,
        onBack = onBack,
        immersive = immersive,
        modifier = modifier,
        searchQuery = searchQuery,
        onSearchQuery = onSearchQuery,
        backgroundArtwork = { revealFullArtwork ->
            ArtworkImage(
                album.title,
                album.thumbUrl,
                Modifier.fillMaxSize(),
                radius = 0.dp,
                shape = RoundedCornerShape(0.dp),
                elevated = false,
                maxDecodeDimension = HeroArtworkMaxDecodeDimension,
                alignment = Alignment.Center,
                contentScale = if (revealFullArtwork) ContentScale.Fit else ContentScale.Crop,
            )
        },
        artwork = {
            ArtworkImage(
                album.title,
                album.thumbUrl,
                Modifier
                    .fillMaxSize()
                    .sharedArtworkTransition("album:${album.id}"),
                radius = 18.dp,
                elevated = true,
                maxDecodeDimension = HeroArtworkMaxDecodeDimension,
            )
        },
        titleModifier = Modifier.sharedBoundsTransition("album:${album.id}:title"),
        subtitleModifier = Modifier
            .sharedBoundsTransition("album:${album.id}:subtitle")
            .clickable(enabled = artist != null) {
                artist?.let(onArtist)
            },
        actions = {
            PlayAllActionButton(
                tracks = visibleTracks,
                onClick = { onPlayTracks(visibleTracks, 0) },
            )
            DownloadActionButton("Download", tracks) { onDownloadAlbum(album) }
            DesktopDetailIconSurface {
                LikeButton(
                    liked = favoriteActions.isFavorite(album),
                    enabled = true,
                    onClick = { favoriteActions.onToggleAlbum(album) },
                )
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesktopDetailHero(
    label: String,
    title: String,
    eyebrow: String,
    meta: List<String>,
    onBack: () -> Unit,
    artwork: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    immersive: Boolean = true,
    backgroundArtwork: (@Composable (Boolean) -> Unit)? = null,
    searchQuery: String? = null,
    onSearchQuery: ((String) -> Unit)? = null,
    titleModifier: Modifier = Modifier,
    subtitleModifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    val heroHeight = if (immersive) 340.dp else 300.dp
    val heroTitleSize = if (immersive) 52.sp else 44.sp
    val heroTitleLineHeight = if (immersive) 54.sp else 46.sp
    var revealFullArtwork by remember(title) { mutableStateOf(false) }
    val artworkRevealProgress by animateFloatAsState(
        targetValue = if (revealFullArtwork) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "detail-artwork-reveal",
    )
    val immersiveHeroHeight by animateDpAsState(
        targetValue = if (revealFullArtwork) 760.dp else 540.dp,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "detail-hero-height",
    )
    val panelColor = PhoebeUi.panel
    val horizontalGradientBrush = remember(panelColor) {
        Brush.horizontalGradient(
            0f to panelColor.copy(alpha = 0.98f),
            0.36f to panelColor.copy(alpha = 0.78f),
            0.72f to panelColor.copy(alpha = 0.18f),
            1f to Color.Black.copy(alpha = 0.12f),
        )
    }
    val verticalGradientBrush = remember(panelColor) {
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.12f),
            0.62f to Color.Transparent,
            1f to panelColor.copy(alpha = 0.82f),
        )
    }
    if (immersive && backgroundArtwork != null) {
        Box(
            modifier
                .fillMaxWidth()
                .height(immersiveHeroHeight)
                .background(panelColor),
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { revealFullArtwork = !revealFullArtwork },
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            alpha = 0.74f * (1f - artworkRevealProgress)
                            scaleX = 1f + (0.035f * artworkRevealProgress)
                            scaleY = 1f + (0.035f * artworkRevealProgress)
                        },
                ) {
                    backgroundArtwork(false)
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            alpha = artworkRevealProgress
                            scaleX = 0.88f + (0.12f * artworkRevealProgress)
                            scaleY = 0.88f + (0.12f * artworkRevealProgress)
                        },
                ) {
                    backgroundArtwork(true)
                }
            }
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = 0.18f * artworkRevealProgress
                    },
            ) {
                Box(Modifier.matchParentSize().background(Color.Black))
            }
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = 1f - artworkRevealProgress }
                    .background(horizontalGradientBrush),
            )
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = 1f - artworkRevealProgress }
                    .background(verticalGradientBrush),
            )
            if (searchQuery != null && onSearchQuery != null) {
                SearchPill(
                    query = searchQuery,
                    onQueryChange = onSearchQuery,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 32.dp)
                        .width(PhoebeDesktopLayout.searchWidth)
                        .graphicsLayer { alpha = 1f - artworkRevealProgress },
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 28.dp, top = 24.dp)
                    .graphicsLayer { alpha = 1f - artworkRevealProgress },
            ) {
                DetailSectionIntro(
                    onBack = onBack,
                    label = label,
                    alignBackIconToContentStart = true,
                )
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 28.dp, end = 150.dp, bottom = 34.dp)
                    .widthIn(max = 680.dp)
                    .graphicsLayer { alpha = 1f - artworkRevealProgress },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionLabel(eyebrow, PhoebeUi.accentLight)
                Text(
                    title,
                    color = PhoebeUi.primaryText,
                    fontSize = 54.sp,
                    lineHeight = 56.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = titleModifier,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        color = PhoebeUi.secondaryText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = subtitleModifier,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    meta.filter { it.isNotBlank() }.forEach { item ->
                        DesktopDetailMetaChip(item)
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    actions()
                }
            }
        }
        return
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(heroHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                DetailSectionIntro(
                    onBack = onBack,
                    label = label,
                    alignBackIconToContentStart = true,
                )
                Spacer(Modifier.height(if (immersive) 54.dp else 42.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionLabel(eyebrow, PhoebeUi.accentLight)
                    Text(
                        title,
                        color = PhoebeUi.primaryText,
                        fontSize = heroTitleSize,
                        lineHeight = heroTitleLineHeight,
                        fontWeight = FontWeight.Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = titleModifier,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            color = PhoebeUi.secondaryText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = subtitleModifier,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        meta.filter { it.isNotBlank() }.forEach { item ->
                            DesktopDetailMetaChip(item)
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actions()
                }
                Spacer(Modifier.weight(1f))
            }
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            shadowElevation = 34f
                            ambientShadowColor = Color.Black.copy(alpha = 0.42f)
                            spotShadowColor = Color.Black.copy(alpha = 0.58f)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    artwork()
                }
            }
    }
}

@Composable
private fun DesktopDetailMetaChip(label: String) {
    Text(
        label,
        color = PhoebeUi.secondaryText,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(999.dp))
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

@Composable
private fun DesktopDetailIconSurface(content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ArtistDetailStatRow(icon: PhoebeIcon, value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.04f)),
            contentAlignment = Alignment.Center,
        ) {
            PhoebeIconView(icon, tint = PhoebeUi.accentLight, modifier = Modifier.size(15.dp))
        }
        Column {
            Text(value, color = PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(label, color = PhoebeUi.mutedText, fontSize = 11.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistStatsPanel(
    artist: Artist,
    catalog: CatalogSnapshot,
    artistThumbUrl: String?,
    albums: List<Album>,
    tracks: List<Track>,
    artistRadioAvailability: ArtistRadioAvailability?,
    artistRadioStarting: Boolean,
    onPlayArtistRadio: () -> Unit,
    onAlbum: (Album) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    edgePadding: Dp = 36.dp,
    topPadding: Dp = 36.dp,
    alignBackIconToContentStart: Boolean = false,
) {
    val genre = remember(catalog, artist.title) { catalogArtistGenre(catalog, artist.title) ?: "—" }
    val totalDuration = remember(tracks) { tracks.sumOf { it.durationMs } }
    val playHistory = LocalPlayHistory.current
    val nowMs = LocalNowMs.current
    val lastPlayed = remember(tracks, playHistory.byTrack, playHistory.byArtist, artist.title) {
        resolveArtistLastPlayed(artist.title, tracks, playHistory)
    }
    val lastPlayedLabel = remember(lastPlayed, nowMs) { formatLastPlayed(lastPlayed, nowMs) }
    val albumWord = if (albums.size == 1) "album" else "albums"
    val songWord = if (tracks.size == 1) "song" else "songs"
    val scrollState = rememberScrollState()
    val chromeBottom = LocalMobileChromePadding.current.bottom

    Column(
        modifier
            .fillMaxSize()
            .padding(
                start = edgePadding,
                end = edgePadding,
                top = mobileContentTopPadding(topPadding),
                bottom = 24.dp + chromeBottom,
            )
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DetailSectionIntro(
            onBack = onBack,
            label = "Artist",
            alignBackIconToContentStart = alignBackIconToContentStart,
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ArtworkImage(
                artist.title,
                artistThumbUrl,
                Modifier
                    .size(116.dp)
                    .sharedArtworkTransition("artist:${artist.id}")
                    .clip(CircleShape),
                radius = 58.dp,
                elevated = false,
            )
        }
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                artist.title.uppercase(),
                color = PhoebeUi.primaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.04.em,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.sharedBoundsTransition("artist:${artist.id}:title"),
            )
            Text(genre, color = PhoebeUi.mutedText, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        if (artistRadioAvailability == ArtistRadioAvailability.Available) {
            PlayRadioActionButton(
                starting = artistRadioStarting,
                label = "Radio",
                modifier = Modifier.fillMaxWidth(),
                onClick = onPlayArtistRadio,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ArtistDetailStatRow(PhoebeIcon.Library, "${albums.size} $albumWord", "Albums")
            ArtistDetailStatRow(PhoebeIcon.Music, "${tracks.size} $songWord", "Songs")
            ArtistDetailStatRow(PhoebeIcon.ActiveDot, formatHoursMinutes(totalDuration), "Total Duration")
            ArtistDetailStatRow(PhoebeIcon.Bell, lastPlayedLabel, "Last Played")
        }
        if (albums.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            SectionLabel("Albums", PhoebeUi.primaryText)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                albums.forEach { album ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onAlbum(album) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.size(32.dp)) {
                            ArtworkImage(
                                album.title,
                                album.thumbUrl,
                                Modifier.fillMaxSize(),
                                radius = 6.dp,
                                maxDecodeDimension = 512,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                album.title,
                                color = PhoebeUi.secondaryText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            album.year?.let { Text(it.toString(), color = PhoebeUi.mutedText, fontSize = 10.sp) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistDetailPanel(
    artist: Artist,
    catalog: CatalogSnapshot,
    libraryUi: LibraryUiPreferences,
    catalogRefreshing: Boolean = false,
    fullBleedArtwork: Boolean = true,
    modifier: Modifier = Modifier,
    searchQuery: String = "",
    onSearchQuery: (String) -> Unit = {},
    onBack: () -> Unit,
    onAlbum: (Album) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onPlayAllTracks: (List<Track>) -> Unit = { tracksToPlay -> onPlayTracks(tracksToPlay, 0) },
    onShuffleAllTracks: (List<Track>) -> Unit = { tracksToShuffle -> onPlayTracks(tracksToShuffle.shuffled(), 0) },
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onDownloadArtist: (Artist) -> Unit,
    artistRadioAvailability: ArtistRadioAvailability? = null,
    artistRadioStarting: Boolean = false,
    onProbeArtistRadio: (Artist) -> Unit = {},
    onPlayArtistRadio: (Artist) -> Unit,
    onArtist: (Artist) -> Unit,
    onCollectionItems: (CollectionEntry, String) -> Unit = { _, _ -> },
    onLibraryColumns: (LibraryColumnVisibility) -> Unit,
) {
    val albums = remember(catalog.albums, artist.title) { catalogAlbumsForArtist(catalog, artist.title) }
    val artistThumbUrl = remember(artist.thumbUrl, albums) {
        artist.thumbUrl ?: albums.firstNotNullOfOrNull { it.thumbUrl }
    }
    val tracks = remember(catalog.tracksByParent, artist.title) { catalogTracksForArtist(catalog, artist.title) }
    val playHistory = LocalPlayHistory.current
    val popularTracks = remember(catalog.popularTracksByArtist, tracks, playHistory.playCountByTrack, playHistory.byTrack, artist.id) {
        catalog.popularTracksByArtist[artist.id]
            ?.takeIf { it.isNotEmpty() }
            ?.take(10)
            ?: popularTracksFromPlayHistory(tracks, playHistory, limit = 10)
    }
    val albumWord = if (albums.size == 1) "album" else "albums"
    val songWord = if (tracks.size == 1) "song" else "songs"

    var albumSortBy by remember(artist.id) { mutableStateOf(LibrarySortBy.Year) }
    var albumAscending by remember(artist.id) { mutableStateOf(true) }
    var albumViewMode by remember(artist.id) { mutableStateOf(LibraryViewMode.List) }

    var songSortBy by remember(artist.id) { mutableStateOf(LibrarySortBy.Album) }
    var songAscending by remember(artist.id) { mutableStateOf(true) }

    val sortedAlbums = remember(albums, albumSortBy, albumAscending) {
        sortAlbumsForLibrary(albums, albumSortBy, albumAscending)
    }
    val sortedTracks = remember(tracks, songSortBy, songAscending) {
        sortTracksForLibrary(tracks, songSortBy, songAscending)
    }
    val visibleAlbums = remember(sortedAlbums, searchQuery) {
        filterAlbumsByQuery(sortedAlbums, searchQuery)
    }
    val visibleTracks = remember(sortedTracks, searchQuery) {
        filterTracksByQuery(sortedTracks, searchQuery)
    }
    val similarArtists = remember(catalog, artist.id, artist.title) {
        if (catalog.similarArtistsByArtist.containsKey(artist.id)) {
            catalog.similarArtistsByArtist[artist.id].orEmpty()
        } else if (artist.id.startsWith("plex:")) {
            emptyList()
        } else {
            similarArtistsFor(catalog, artist).take(ArtistSimilarArtistDisplayLimit)
        }
    }
    val nowPlaying = LocalNowPlaying.current
    val ratingActions = LocalRatingActions.current
    val favoriteActions = LocalFavoriteActions.current
    LaunchedEffect(artist.id) {
        if (artist.id.startsWith("plex:") || artist.id.startsWith("jellyfin:")) onProbeArtistRadio(artist)
    }
    var showStats by remember(artist.id) { mutableStateOf(false) }
    val mobileChromeBottom = LocalMobileChromePadding.current.bottom

    BoxWithConstraints(modifier.fillMaxSize()) {
        val useTable = maxWidth >= 640.dp
        val startPadding = if (maxWidth < 640.dp) 20.dp else PhoebeDesktopLayout.contentStart
        val endPadding = if (maxWidth < 640.dp) 20.dp else PhoebeDesktopLayout.contentEnd
        val topPadding = 16.dp
        val mobileBottomPadding = mobileChromeBottom + 24.dp
        val bottomContentPadding = if (useTable) {
            if (mobileBottomPadding > 24.dp) mobileBottomPadding else 24.dp
        } else {
            if (mobileBottomPadding > 144.dp) mobileBottomPadding else 144.dp
        }
        val immersiveDesktopHeader = fullBleedArtwork && useTable && maxHeight >= 640.dp
        val albumGridItemSizeDp = libraryUi.albumGridItemSizeDp
        val albumGridColumns = rememberLibraryGridColumnCount(
            availableWidth = maxWidth,
            itemSizeDp = albumGridItemSizeDp,
            horizontalSpacing = 14.dp,
        )
        val albumGridRows = remember(visibleAlbums, albumGridColumns) {
            visibleAlbums.chunked(albumGridColumns)
        }
        val listState = RetainedLazyListStates.remember("artist-detail:${artist.id}")
        val listStartPadding = if (useTable) 0.dp else startPadding
        val listEndPadding = if (useTable) 0.dp else endPadding
        val listTopPadding = if (useTable) 0.dp else mobileContentTopPadding(topPadding)
        val contentPaddingModifier = if (useTable) {
            Modifier.padding(start = startPadding, end = endPadding)
        } else {
            Modifier
        }
        val desktopHeaderModifier = if (immersiveDesktopHeader) {
            Modifier
        } else {
            Modifier.padding(
                start = startPadding,
                top = PhoebeDesktopLayout.contentTop,
                end = endPadding,
            )
        }
        if (showStats) {
            ArtistStatsPanel(
                artist = artist,
                catalog = catalog,
                artistThumbUrl = artistThumbUrl,
                albums = albums,
                tracks = tracks,
                artistRadioAvailability = artistRadioAvailability,
                artistRadioStarting = artistRadioStarting,
                onPlayArtistRadio = { onPlayArtistRadio(artist) },
                onAlbum = onAlbum,
                onBack = { showStats = false },
                edgePadding = startPadding,
                topPadding = topPadding,
                alignBackIconToContentStart = !useTable,
            )
        } else {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(start = listStartPadding, end = listEndPadding),
        contentPadding = PaddingValues(
            top = listTopPadding,
            bottom = bottomContentPadding
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(contentType = "artist-header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (useTable) {
                    DesktopArtistDetailHeader(
                        artist = artist,
                        artistThumbUrl = artistThumbUrl,
                        albumsCount = albums.size,
                        tracks = tracks,
                        visibleTracks = visibleTracks,
                        albumWord = albumWord,
                        songWord = songWord,
                        artistRadioAvailability = artistRadioAvailability,
                        artistRadioStarting = artistRadioStarting,
                        favoriteActions = favoriteActions,
                        onBack = onBack,
                        onArtworkClick = { showStats = true },
                        onPlayAllTracks = onPlayAllTracks,
                        onShuffleAllTracks = onShuffleAllTracks,
                        onPlayArtistRadio = onPlayArtistRadio,
                        onDownloadArtist = onDownloadArtist,
                        searchQuery = searchQuery,
                        onSearchQuery = onSearchQuery,
                        immersive = immersiveDesktopHeader,
                        modifier = desktopHeaderModifier,
                    )
                    Spacer(Modifier.height(14.dp))
                    Column(contentPaddingModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (popularTracks.isNotEmpty() && searchQuery.isBlank()) {
                            PopularTracksSection(
                                tracks = popularTracks,
                                useTable = true,
                                columns = libraryUi.columns,
                                onPlayTracks = onPlayTracks,
                                onAddToUpNext = onAddToUpNext,
                                onDownload = onDownload,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        DetailSectionHeader(
                            title = "Albums",
                            sortBy = albumSortBy,
                            sortKeys = listOf(LibrarySortBy.Name, LibrarySortBy.Year, LibrarySortBy.DateAdded),
                            sortLabel = { key ->
                                when (key) {
                                    LibrarySortBy.Year -> "Release date"
                                    LibrarySortBy.DateAdded -> "Date added"
                                    else -> "Album name"
                                }
                            },
                            onSortBy = { albumSortBy = it },
                            ascending = albumAscending,
                            onAscending = { albumAscending = it },
                            viewMode = albumViewMode,
                            onViewMode = { albumViewMode = it },
                        )
                    }
                } else {
                    MobileArtistDetailHeader(
                        artist = artist,
                        artistThumbUrl = artistThumbUrl,
                        albumsCount = albums.size,
                        tracks = tracks,
                        visibleTracks = visibleTracks,
                        albumWord = albumWord,
                        songWord = songWord,
                        artistRadioAvailability = artistRadioAvailability,
                        artistRadioStarting = artistRadioStarting,
                        favoriteActions = favoriteActions,
                        onBack = onBack,
                        onArtworkClick = { showStats = true },
                        onPlayAllTracks = onPlayAllTracks,
                        onShuffleAllTracks = onShuffleAllTracks,
                        onPlayArtistRadio = onPlayArtistRadio,
                        onDownloadArtist = onDownloadArtist,
                    )
                    if (popularTracks.isNotEmpty() && searchQuery.isBlank()) {
                        PopularTracksSection(
                            tracks = popularTracks,
                            useTable = false,
                            columns = libraryUi.columns,
                            onPlayTracks = onPlayTracks,
                            onAddToUpNext = onAddToUpNext,
                            onDownload = onDownload,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    DetailSectionHeader(
                        title = "Albums",
                        sortBy = albumSortBy,
                        sortKeys = listOf(LibrarySortBy.Name, LibrarySortBy.Year, LibrarySortBy.DateAdded),
                        sortLabel = { key ->
                            when (key) {
                                LibrarySortBy.Year -> "Release date"
                                LibrarySortBy.DateAdded -> "Date added"
                                else -> "Album name"
                            }
                        },
                        onSortBy = { albumSortBy = it },
                        ascending = albumAscending,
                        onAscending = { albumAscending = it },
                        viewMode = albumViewMode,
                        onViewMode = { albumViewMode = it },
                    )
                }
            }
        }
        if (albumViewMode == LibraryViewMode.Grid) {
            items(
                albumGridRows.size,
                key = { rowIndex -> "album-grid-row:${albumGridRows[rowIndex].first().id}" },
                contentType = { "artist-album-grid-row" },
            ) { rowIndex ->
                val row = albumGridRows[rowIndex]
                Row(
                    contentPaddingModifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    row.forEach { album ->
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onAlbum(album) }
                                .padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                                ArtworkImage(
                                    album.title,
                                    album.thumbUrl,
                                    Modifier.fillMaxSize().sharedArtworkTransition("album:${album.id}"),
                                    elevated = useTable,
                                )
                            }
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
                                album.year?.toString() ?: "Album",
                                color = PhoebeUi.mutedText,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    repeat(albumGridColumns - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            items(visibleAlbums, key = { it.id }, contentType = { "artist-album" }) { album ->
                LibraryRow(
                    title = album.title,
                    subtitle = "${album.artist} • ${album.year ?: "Album"}",
                    seed = album.title,
                    thumbUrl = album.thumbUrl,
                    modifier = contentPaddingModifier,
                    elevatedArtwork = useTable,
                    sharedKey = "album:${album.id}",
                    onClick = { onAlbum(album) },
                )
            }
        }
        item(contentType = "artist-songs-header") {
            Column(contentPaddingModifier) {
                Spacer(Modifier.height(8.dp))
                DetailSectionHeader(
                    title = "Songs",
                    sortBy = songSortBy,
                    sortKeys = listOf(LibrarySortBy.Name, LibrarySortBy.Album, LibrarySortBy.Year, LibrarySortBy.DateAdded),
                    sortLabel = { key ->
                        when (key) {
                            LibrarySortBy.Album -> "Album name"
                            LibrarySortBy.Year -> "Release date"
                            LibrarySortBy.DateAdded -> "Date added"
                            else -> "Song name"
                        }
                    },
                    onSortBy = { songSortBy = it },
                    ascending = songAscending,
                    onAscending = { songAscending = it },
                    columns = libraryUi.columns,
                    onColumns = onLibraryColumns,
                )
            }
        }
        item(contentType = "artist-song-toolbar") {
            Column(contentPaddingModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val tracksLoadingIds = LocalTracksLoading.current
                val artistAlbumsLoading = catalogAlbumsForArtist(catalog, artist.title).any { it.id in tracksLoadingIds }
                if (artistAlbumsLoading && searchQuery.isBlank()) {
                    CatalogLoadingStrip()
                }
            }
        }
        if (visibleTracks.isEmpty() && searchQuery.isBlank()) {
            item(contentType = "artist-song-empty") {
                val tracksLoadingIds = LocalTracksLoading.current
                val artistAlbumsLoading = catalogAlbumsForArtist(catalog, artist.title).any { it.id in tracksLoadingIds }
                Text(
                    if (artistAlbumsLoading) "Fetching songs…" else "No songs loaded yet.",
                    color = PhoebeUi.mutedText,
                    fontSize = 14.sp,
                    modifier = contentPaddingModifier,
                )
            }
        } else if (visibleTracks.isEmpty() && searchQuery.isNotBlank()) {
            item(contentType = "artist-song-empty") {
                Text(
                    "No songs by ${artist.title} match \"$searchQuery\".",
                    color = PhoebeUi.mutedText,
                    fontSize = 14.sp,
                    modifier = contentPaddingModifier,
                )
            }
        } else if (useTable) {
            item(contentType = "artist-song-header") {
                Box(contentPaddingModifier) {
                    SongsTableHeader(libraryUi.columns, showLeadingHandle = false)
                }
            }
            itemsIndexed(visibleTracks, key = { _, t -> t.id }, contentType = { _, _ -> "artist-song" }) { index, track ->
                SongRow(
                    track = track,
                    selected = false,
                    columns = libraryUi.columns,
                    onSelect = { onPlayTracks(visibleTracks, index) },
                    onPlay = { onPlayTracks(visibleTracks, index) },
                    onAddToUpNext = { onAddToUpNext(track) },
                    onDownload = { onDownload(track) },
                    modifier = contentPaddingModifier,
                    showPlaylistDragHandle = false,
                )
            }
        } else {
            itemsIndexed(visibleTracks, key = { _, t -> t.id }, contentType = { _, _ -> "artist-song" }) { index, track ->
                val isNowPlaying = track.id == nowPlaying.trackId
                MobileSongRow(
                    track = track,
                    columns = libraryUi.columns,
                    isNowPlaying = isNowPlaying,
                    nowPlayingIsPlaying = nowPlaying.isPlaying,
                    nowPlayingIsBuffering = nowPlaying.isBuffering,
                    onPlay = { onPlayTracks(visibleTracks, index) },
                    onAddToUpNext = { onAddToUpNext(track) },
                    onDownload = { onDownload(track) },
                )
            }
        }
        if (similarArtists.isNotEmpty() && searchQuery.isBlank()) {
            item(contentType = "artist-similar-artists") {
                Box(contentPaddingModifier) {
                    SimilarArtistsSection(
                        artists = similarArtists,
                        catalog = catalog,
                        useTable = useTable,
                        onArtist = onArtist,
                    )
                }
            }
        }
        if (!artist.genre.isNullOrBlank() || !artist.mood.isNullOrBlank() || !artist.style.isNullOrBlank() || !artist.biography.isNullOrBlank()) {
            item(contentType = "artist-about") {
                Box(contentPaddingModifier) {
                    AboutArtistPanel(
                        artist = artist,
                        onCollectionItems = onCollectionItems,
                    )
                }
            }
        }
    }
        }
    }
}

@Composable
private fun AboutSectionItem(
    label: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = PhoebeUi.mutedText, fontSize = 15.sp)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutClickableList(
    items: List<String>,
    onItemClick: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEachIndexed { index, item ->
            Text(
                text = item,
                color = PhoebeUi.accentLight,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onItemClick(item) }
            )
            if (index < items.lastIndex) {
                Text("•", color = PhoebeUi.mutedText, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun AboutPlainValue(value: String) {
    Text(
        value,
        color = PhoebeUi.primaryText,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
    )
}

private fun aboutTagItems(value: String): List<String> =
    value.split(",").map { it.trim() }.filter { it.isNotBlank() }

@Composable
private fun ExpandableText(
    text: String,
    modifier: Modifier = Modifier,
    minLines: Int = 5,
) {
    var expanded by remember(text) { mutableStateOf(false) }
    var hasOverflow by remember(text) { mutableStateOf(false) }

    Column(modifier = modifier.animateContentSize()) {
        Text(
            text = text,
            color = PhoebeUi.primaryText,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
            maxLines = if (expanded) Int.MAX_VALUE else minLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { textLayoutResult ->
                if (!expanded) {
                    hasOverflow = textLayoutResult.hasVisualOverflow
                }
            }
        )
        if (hasOverflow || expanded) {
            Text(
                text = if (expanded) "Show less" else "Show more",
                color = PhoebeUi.accentLight,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.End)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { expanded = !expanded }
                    )
                    .padding(top = 4.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun AboutArtistPanel(
    artist: Artist,
    onCollectionItems: (CollectionEntry, String) -> Unit,
) {
    val ratingActions = LocalRatingActions.current
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("About", color = PhoebeUi.primaryText, fontSize = 22.sp, fontWeight = FontWeight.Normal)
        
        if (ratingActions.ratingsEnabled && (artist.id.startsWith("plex:") || artist.id.startsWith("jellyfin:"))) {
            AboutSectionItem("Rating") {
                RatingStars(
                    rating = ratingActions.ratingFor(artist),
                    enabled = true,
                    onRating = { ratingActions.onRateArtist(artist, it) },
                    starSize = 24.dp,
                    showClear = true,
                )
            }
        }

        artist.genre?.let { genre ->
            if (genre.isNotBlank()) {
                AboutSectionItem("Genres") {
                    AboutClickableList(aboutTagItems(genre)) { g ->
                        onCollectionItems(CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre), g)
                    }
                }
            }
        }

        artist.mood?.let { mood ->
            if (mood.isNotBlank()) {
                AboutSectionItem("Moods") {
                    AboutClickableList(aboutTagItems(mood)) { m ->
                        onCollectionItems(CollectionEntry(CollectionTarget.Artists, CollectionFacet.Mood), m)
                    }
                }
            }
        }

        artist.style?.let { style ->
            if (style.isNotBlank()) {
                AboutSectionItem("Styles") {
                    AboutClickableList(aboutTagItems(style)) { s ->
                        onCollectionItems(CollectionEntry(CollectionTarget.Artists, CollectionFacet.Style), s)
                    }
                }
            }
        }
        
        artist.biography?.let { bio ->
            if (bio.isNotBlank()) {
                AboutSectionItem("Biography") {
                    ExpandableText(bio)
                }
            }
        }
    }
}

private fun popularTracksFromPlayHistory(
    tracks: List<Track>,
    history: PlayHistorySnapshot,
    limit: Int = 10,
): List<Track> =
    tracks.asSequence()
        .mapNotNull { track ->
            val count = history.playCountByTrack[track.id]?.takeIf { it > 0L } ?: return@mapNotNull null
            val lastPlayed = history.byTrack[track.id] ?: 0L
            track to MostPlayedSort(count, lastPlayed)
        }
        .sortedWith(
            compareByDescending<Pair<Track, MostPlayedSort>> { it.second }
                .thenBy { it.first.title.lowercase() },
        )
        .map { it.first }
        .take(limit)
        .toList()

private data class MostPlayedSort(
    val playCount: Long,
    val lastPlayedMs: Long,
) : Comparable<MostPlayedSort> {
    override fun compareTo(other: MostPlayedSort): Int =
        playCount.compareTo(other.playCount).takeIf { it != 0 }
            ?: lastPlayedMs.compareTo(other.lastPlayedMs)
}

@Composable
private fun PopularTracksSection(
    tracks: List<Track>,
    useTable: Boolean,
    columns: LibraryColumnVisibility,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
) {
    val nowPlaying = LocalNowPlaying.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Top Songs", PhoebeUi.primaryText)
        tracks.take(if (useTable) 6 else 5).forEachIndexed { index, track ->
            if (useTable) {
                SongRow(
                    track = track,
                    selected = false,
                    columns = columns,
                    onSelect = { onPlayTracks(tracks, index) },
                    onPlay = { onPlayTracks(tracks, index) },
                    onAddToUpNext = { onAddToUpNext(track) },
                    onDownload = { onDownload(track) },
                    showPlaylistDragHandle = false,
                    sharedKey = "popular:${track.id}",
                )
            } else {
                MobileSongRow(
                    track = track,
                    columns = columns,
                    isNowPlaying = track.id == nowPlaying.trackId,
                    nowPlayingIsPlaying = nowPlaying.isPlaying,
                    nowPlayingIsBuffering = nowPlaying.isBuffering,
                    onPlay = { onPlayTracks(tracks, index) },
                    onAddToUpNext = { onAddToUpNext(track) },
                    onDownload = { onDownload(track) },
                )
            }
        }
    }
}

private fun similarArtistsFor(catalog: CatalogSnapshot, artist: Artist): List<Artist> {
    val albumsByArtist = catalog.albums.groupBy { it.artist.trim().lowercase() }
    val tracksByArtist = catalog.tracksByParent.values
        .asSequence()
        .flatten()
        .groupBy { it.artist.trim().lowercase() }
    val targetTags = artistSimilarityTags(
        artist = artist,
        albums = albumsByArtist[artist.title.trim().lowercase()].orEmpty(),
        tracks = tracksByArtist[artist.title.trim().lowercase()].orEmpty(),
    )
    if (targetTags.isEmpty()) return emptyList()
    return catalog.artists
        .asSequence()
        .filter { it.id != artist.id && !it.title.equals(artist.title, ignoreCase = true) }
        .map { candidate ->
            val candidateKey = candidate.title.trim().lowercase()
            val score = artistSimilarityTags(
                artist = candidate,
                albums = albumsByArtist[candidateKey].orEmpty(),
                tracks = tracksByArtist[candidateKey].orEmpty(),
            ).count { it in targetTags }
            candidate to score
        }
        .filter { (_, score) -> score > 0 }
        .sortedWith(compareByDescending<Pair<Artist, Int>> { it.second }.thenBy { it.first.title.lowercase() })
        .map { it.first }
        .toList()
}

private fun artistSimilarityTags(
    artist: Artist,
    albums: List<Album>,
    tracks: List<Track>,
): Set<String> {
    return buildSet {
        addSimilarityTag(artist.genre)
        addSimilarityTag(artist.mood)
        addSimilarityTag(artist.style)
        albums.forEach { album ->
            addSimilarityTag(album.genre)
            addSimilarityTag(album.mood)
            addSimilarityTag(album.style)
        }
        tracks.forEach { track ->
            addSimilarityTag(track.genre)
            addSimilarityTag(track.mood)
            addSimilarityTag(track.style)
        }
    }
}

private fun MutableSet<String>.addSimilarityTag(value: String?) {
    value
        ?.split(',', ';')
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotBlank() }
        ?.forEach(::add)
}

@Composable
private fun SimilarArtistsSection(
    artists: List<Artist>,
    catalog: CatalogSnapshot,
    useTable: Boolean,
    onArtist: (Artist) -> Unit,
) {
    var page by remember(artists) { mutableStateOf(0) }
    val desktopPageSize = 4
    val pageCount = ((artists.size + desktopPageSize - 1) / desktopPageSize).coerceAtLeast(1)
    val currentPage = page.coerceIn(0, pageCount - 1)
    val desktopArtists = remember(artists, currentPage) {
        artists.drop(currentPage * desktopPageSize).take(desktopPageSize)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(8.dp))
        if (useTable) {
            SimilarArtistsPagerHeader(
                page = currentPage,
                pageCount = pageCount,
                onPrevious = { page = (currentPage - 1).coerceAtLeast(0) },
                onNext = { page = (currentPage + 1).coerceAtMost(pageCount - 1) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                desktopArtists.forEach { artist ->
                    SimilarArtistCard(
                        artist = artist,
                        catalog = catalog,
                        modifier = Modifier.weight(1f),
                        onClick = { onArtist(artist) },
                    )
                }
                repeat(desktopPageSize - desktopArtists.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        } else {
            SectionLabel("Similar Artists", PhoebeUi.primaryText)
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(artists, key = { it.id }, contentType = { "similar-artist-card" }) { artist ->
                    SimilarArtistCard(
                        artist = artist,
                        catalog = catalog,
                        modifier = Modifier.widthIn(max = 220.dp),
                        onClick = { onArtist(artist) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SimilarArtistsPagerHeader(
    page: Int,
    pageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("Similar Artists", PhoebeUi.primaryText)
        Spacer(Modifier.weight(1f))
        if (pageCount > 1) {
            Text(
                "${page + 1}/$pageCount",
                color = PhoebeUi.mutedText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
        }
        SimilarArtistsPageButton(PhoebeIcon.Previous, enabled = page > 0, onClick = onPrevious)
        SimilarArtistsPageButton(PhoebeIcon.Next, enabled = page < pageCount - 1, onClick = onNext)
    }
}

@Composable
private fun SimilarArtistsPageButton(icon: PhoebeIcon, enabled: Boolean, onClick: () -> Unit) {
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

@Composable
private fun SimilarArtistCard(
    artist: Artist,
    catalog: CatalogSnapshot,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val albums = remember(catalog.albums, artist.title) { catalogAlbumsForArtist(catalog, artist.title) }
    val tracks = remember(catalog.tracksByParent, artist.title) { catalogTracksForArtist(catalog, artist.title) }
    val thumbUrl = remember(artist.thumbUrl, albums) { artist.thumbUrl ?: albums.firstNotNullOfOrNull { it.thumbUrl } }
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(Color.White.copy(alpha = 0.045f))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ArtworkImage(artist.title, thumbUrl, Modifier.size(74.dp).sharedArtworkTransition("artist:${artist.id}"), radius = 999.dp, elevated = false)
        Text(artist.title, color = PhoebeUi.primaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${artistAlbumCountSubtitle(artist)} • ${songCountLabel(tracks.size)}", color = PhoebeUi.mutedText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SimilarArtistRow(
    artist: Artist,
    catalog: CatalogSnapshot,
    onClick: () -> Unit,
) {
    val albums = remember(catalog.albums, artist.title) { catalogAlbumsForArtist(catalog, artist.title) }
    val tracks = remember(catalog.tracksByParent, artist.title) { catalogTracksForArtist(catalog, artist.title) }
    val thumbUrl = remember(artist.thumbUrl, albums) { artist.thumbUrl ?: albums.firstNotNullOfOrNull { it.thumbUrl } }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(Color.White.copy(alpha = 0.045f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtworkImage(artist.title, thumbUrl, Modifier.size(46.dp).sharedArtworkTransition("artist:${artist.id}"), radius = 999.dp, elevated = false)
        Column(Modifier.weight(1f)) {
            Text(artist.title, color = PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${artistAlbumCountSubtitle(artist)} • ${songCountLabel(tracks.size)}", color = PhoebeUi.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun ArtistAlbumGrid(albums: List<Album>, albumGridItemSizeDp: Int, onAlbum: (Album) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 14.dp
        val columns = rememberLibraryGridColumnCount(
            availableWidth = maxWidth,
            itemSizeDp = albumGridItemSizeDp,
            horizontalSpacing = gap,
        )
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            albums.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { album ->
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onAlbum(album) }
                                .padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f),
                            ) {
                                ArtworkImage(
                                    album.title,
                                    album.thumbUrl,
                                    Modifier.fillMaxSize().sharedArtworkTransition("album:${album.id}"),
                                )
                            }
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
                                album.year?.toString() ?: "Album",
                                color = PhoebeUi.mutedText,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailPanel(
    album: Album,
    catalog: CatalogSnapshot,
    libraryUi: LibraryUiPreferences,
    catalogRefreshing: Boolean = false,
    fullBleedArtwork: Boolean = true,
    modifier: Modifier = Modifier,
    searchQuery: String = "",
    onSearchQuery: (String) -> Unit = {},
    onBack: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onDownloadAlbum: (Album) -> Unit,
    onArtist: (Artist) -> Unit,
    onCollectionItems: (CollectionEntry, String) -> Unit = { _, _ -> },
    onLibraryColumns: (LibraryColumnVisibility) -> Unit,
) {
    val resolvedAlbum = remember(catalog.albums, album.id) {
        catalog.albums.firstOrNull { it.id == album.id } ?: album
    }
    val tracks = remember(catalog.tracksByParent, resolvedAlbum.id) {
        catalog.tracksByParent[resolvedAlbum.id].orEmpty()
    }
    val artist = remember(catalog.artists, resolvedAlbum.id, resolvedAlbum.artist) {
        catalogArtistForAlbum(catalog, resolvedAlbum)
    }

    var sortBy by remember(resolvedAlbum.id) { mutableStateOf(LibrarySortBy.AlbumOrder) }

    val sortedTracks = remember(tracks, sortBy) {
        sortTracksForLibrary(tracks, sortBy, ascending = true)
    }
    val visibleTracks = remember(sortedTracks, searchQuery) {
        filterTracksByQuery(sortedTracks, searchQuery)
    }
    val playVisibleTrack = { visibleIndex: Int ->
        val (queueTracks, queueIndex) = playbackQueueForVisibleTrack(
            sourceTracks = sortedTracks,
            visibleTracks = visibleTracks,
            visibleIndex = visibleIndex,
        )
        onPlayTracks(queueTracks, queueIndex)
    }
    val nowPlaying = LocalNowPlaying.current
    val mobileChromeBottom = LocalMobileChromePadding.current.bottom

    BoxWithConstraints(modifier.fillMaxSize()) {
        val useTable = maxWidth >= 640.dp
        val startPadding = if (maxWidth < 640.dp) 20.dp else PhoebeDesktopLayout.contentStart
        val endPadding = if (maxWidth < 640.dp) 20.dp else PhoebeDesktopLayout.contentEnd
        val topPadding = 16.dp
        val mobileBottomPadding = mobileChromeBottom + 24.dp
        val bottomContentPadding = if (useTable) {
            if (mobileBottomPadding > 24.dp) mobileBottomPadding else 24.dp
        } else {
            if (mobileBottomPadding > 144.dp) mobileBottomPadding else 144.dp
        }
        val immersiveDesktopHeader = fullBleedArtwork && useTable && maxHeight >= 640.dp
        val listState = RetainedLazyListStates.remember("album-detail:${resolvedAlbum.id}")
        val listStartPadding = if (useTable) 0.dp else startPadding
        val listEndPadding = if (useTable) 0.dp else endPadding
        val listTopPadding = if (useTable) 0.dp else mobileContentTopPadding(topPadding)
        val contentPaddingModifier = if (useTable) {
            Modifier.padding(start = startPadding, end = endPadding)
        } else {
            Modifier
        }
        val desktopHeaderModifier = if (immersiveDesktopHeader) {
            Modifier
        } else {
            Modifier.padding(
                start = startPadding,
                top = PhoebeDesktopLayout.contentTop,
                end = endPadding,
            )
        }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(start = listStartPadding, end = listEndPadding),
        contentPadding = PaddingValues(
            top = listTopPadding,
            bottom = bottomContentPadding
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(contentType = "album-header") {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (useTable) {
                    DesktopAlbumDetailHeader(
                        album = resolvedAlbum,
                        tracks = tracks,
                        visibleTracks = visibleTracks,
                        artist = artist,
                        favoriteActions = LocalFavoriteActions.current,
                        onBack = onBack,
                        onPlayTracks = onPlayTracks,
                        onDownloadAlbum = onDownloadAlbum,
                        onArtist = onArtist,
                        searchQuery = searchQuery,
                        onSearchQuery = onSearchQuery,
                        immersive = immersiveDesktopHeader,
                        modifier = desktopHeaderModifier,
                    )
                    Spacer(Modifier.height(18.dp))
                    DetailSectionHeader(
                        title = "Tracks",
                        sortBy = sortBy,
                        sortKeys = listOf(LibrarySortBy.AlbumOrder, LibrarySortBy.Name, LibrarySortBy.Year, LibrarySortBy.DateAdded),
                        sortLabel = { key ->
                            when (key) {
                                LibrarySortBy.AlbumOrder -> "Album order"
                                LibrarySortBy.Year -> "Release date"
                                LibrarySortBy.DateAdded -> "Date added"
                                else -> "Song name"
                            }
                        },
                        onSortBy = { sortBy = it },
                        ascending = null,
                        onAscending = null,
                        columns = libraryUi.columns,
                        onColumns = onLibraryColumns,
                        modifier = contentPaddingModifier,
                    )
                } else {
                    MobileAlbumDetailHeader(
                        album = resolvedAlbum,
                        tracks = tracks,
                        visibleTracks = visibleTracks,
                        artist = artist,
                        favoriteActions = LocalFavoriteActions.current,
                        onBack = onBack,
                        onPlayTracks = onPlayTracks,
                        onDownloadAlbum = onDownloadAlbum,
                        onArtist = onArtist,
                    )
                    DetailSectionHeader(
                        title = "Tracks",
                        sortBy = sortBy,
                        sortKeys = listOf(LibrarySortBy.AlbumOrder, LibrarySortBy.Name, LibrarySortBy.Year, LibrarySortBy.DateAdded),
                        sortLabel = { key ->
                            when (key) {
                                LibrarySortBy.AlbumOrder -> "Album order"
                                LibrarySortBy.Year -> "Release date"
                                LibrarySortBy.DateAdded -> "Date added"
                                else -> "Song name"
                            }
                        },
                        onSortBy = { sortBy = it },
                        ascending = null,
                        onAscending = null,
                        columns = libraryUi.columns,
                        onColumns = onLibraryColumns,
                    )
                }
            }
        }
        item(contentType = "album-track-toolbar") {
            Column(contentPaddingModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (resolvedAlbum.id in LocalTracksLoading.current && searchQuery.isBlank()) {
                    CatalogLoadingStrip()
                }
            }
        }
        if (visibleTracks.isEmpty()) {
            item(contentType = "album-empty") {
                Text(
                    when {
                        searchQuery.isNotBlank() -> "No tracks on ${resolvedAlbum.title} match \"$searchQuery\"."
                        resolvedAlbum.id in LocalTracksLoading.current -> "Fetching songs…"
                        else -> "No tracks loaded yet."
                    },
                    color = PhoebeUi.mutedText,
                    fontSize = 15.sp,
                    modifier = contentPaddingModifier,
                )
            }
        } else if (useTable) {
            item(contentType = "album-track-header") {
                Box(contentPaddingModifier) {
                    SongsTableHeader(libraryUi.columns)
                }
            }
            itemsIndexed(visibleTracks, key = { _, t -> t.id }, contentType = { _, _ -> "album-track" }) { index, track ->
                SongRow(
                    track = track,
                    selected = false,
                    columns = libraryUi.columns,
                    onSelect = { playVisibleTrack(index) },
                    onPlay = { playVisibleTrack(index) },
                    onAddToUpNext = { onAddToUpNext(track) },
                    onDownload = { onDownload(track) },
                    modifier = contentPaddingModifier,
                )
            }
        } else {
            itemsIndexed(visibleTracks, key = { _, t -> t.id }, contentType = { _, _ -> "album-track" }) { index, track ->
                val isNowPlaying = track.id == nowPlaying.trackId
                MobileSongRow(
                    track = track,
                    columns = libraryUi.columns,
                    isNowPlaying = isNowPlaying,
                    nowPlayingIsPlaying = nowPlaying.isPlaying,
                    nowPlayingIsBuffering = nowPlaying.isBuffering,
                    onPlay = { playVisibleTrack(index) },
                    onAddToUpNext = { onAddToUpNext(track) },
                    onDownload = { onDownload(track) },
                )
            }
        }
        if (resolvedAlbum.rating != null || !resolvedAlbum.genre.isNullOrBlank() || !resolvedAlbum.mood.isNullOrBlank() || !resolvedAlbum.style.isNullOrBlank() || !resolvedAlbum.description.isNullOrBlank() || !resolvedAlbum.recordLabel.isNullOrBlank()) {
            item(contentType = "album-about") {
                Box(contentPaddingModifier) {
                    AboutAlbumPanel(
                        album = resolvedAlbum,
                        onCollectionItems = onCollectionItems,
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun AboutAlbumPanel(
    album: Album,
    onCollectionItems: (CollectionEntry, String) -> Unit,
) {
    val ratingActions = LocalRatingActions.current
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("About", color = PhoebeUi.primaryText, fontSize = 22.sp, fontWeight = FontWeight.Normal)
        
        if (ratingActions.ratingsEnabled && (album.id.startsWith("plex:") || album.id.startsWith("jellyfin:"))) {
            AboutSectionItem("Rating") {
                RatingStars(
                    rating = ratingActions.ratingFor(album),
                    enabled = true,
                    onRating = { ratingActions.onRateAlbum(album, it) },
                    starSize = 24.dp,
                    showClear = true,
                )
            }
        }
        
        album.genre?.let { genre ->
            if (genre.isNotBlank()) {
                AboutSectionItem("Genres") {
                    AboutClickableList(aboutTagItems(genre)) { g ->
                        onCollectionItems(CollectionEntry(CollectionTarget.Albums, CollectionFacet.Genre), g)
                    }
                }
            }
        }
        
        album.description?.let { desc ->
            if (desc.isNotBlank()) {
                AboutSectionItem("Description") {
                    ExpandableText(desc)
                }
            }
        }

        album.recordLabel?.let { label ->
            if (label.isNotBlank()) {
                AboutSectionItem("Record label") {
                    AboutPlainValue(label)
                }
            }
        }
        
        album.year?.let { year ->
            AboutSectionItem("Year") {
                AboutPlainValue(year.toString())
            }
        }
        
        album.mood?.let { mood ->
            if (mood.isNotBlank()) {
                AboutSectionItem("Moods") {
                    AboutClickableList(aboutTagItems(mood)) { m ->
                        onCollectionItems(CollectionEntry(CollectionTarget.Albums, CollectionFacet.Mood), m)
                    }
                }
            }
        }
        
        album.style?.let { style ->
            if (style.isNotBlank()) {
                AboutSectionItem("Styles") {
                    AboutClickableList(aboutTagItems(style)) { s ->
                        onCollectionItems(CollectionEntry(CollectionTarget.Albums, CollectionFacet.Style), s)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumDetailHeaderText(
    album: Album,
    tracks: List<Track>,
    artist: Artist?,
    onDownloadAlbum: (Album) -> Unit,
    onArtist: (Artist) -> Unit,
    compact: Boolean = false,
    showDownload: Boolean = true,
) {
    val ratingActions = LocalRatingActions.current
    val favoriteActions = LocalFavoriteActions.current
    Column(
        modifier = if (compact) Modifier.fillMaxWidth() else Modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            album.title,
            color = PhoebeUi.primaryText,
            fontSize = if (compact) 28.sp else 26.sp,
            fontWeight = FontWeight.Black,
            maxLines = if (compact) 3 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.sharedBoundsTransition("album:${album.id}:title"),
        )
        Text(
            album.artist.uppercase(),
            color = PhoebeUi.secondaryText,
            fontSize = 14.sp,
            letterSpacing = 0.05.em,
            modifier = Modifier
                .sharedBoundsTransition("album:${album.id}:subtitle")
                .clickable(enabled = artist != null) {
                    artist?.let(onArtist)
                },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            album.year?.let { y ->
                Text("$y", color = PhoebeUi.mutedText, fontSize = 13.sp)
            }
            LikeButton(
                liked = favoriteActions.isFavorite(album),
                enabled = true,
                onClick = { favoriteActions.onToggleAlbum(album) },
            )
        }
        if (showDownload) {
            DownloadActionButton(
                label = "Download Album",
                tracks = tracks,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                onDownloadAlbum(album)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailPanel(
    playlist: Playlist,
    catalog: CatalogSnapshot,
    catalogRefreshing: Boolean,
    libraryUi: LibraryUiPreferences,
    modifier: Modifier = Modifier,
    searchQuery: String = "",
    onSearchQuery: (String) -> Unit = {},
    onBack: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onDownloadPlaylist: (Playlist) -> Unit,
    onCancelDownloadPlaylist: (Playlist) -> Unit = {},
    onDeleteDownloadPlaylist: (Playlist) -> Unit = {},
    onMovePlaylistTrack: (Playlist, Int, Int) -> Unit = { _, _, _ -> },
    onLibraryColumns: (LibraryColumnVisibility) -> Unit,
) {
    val tracks = remember(catalog.tracksByParent, playlist.id) {
        catalog.tracksByParent[playlist.id].orEmpty()
    }

    var sortBy by remember(playlist.id) { mutableStateOf(LibrarySortBy.PlaylistOrder) }
    var ascending by remember(playlist.id) { mutableStateOf(true) }
    var reorderModeEnabled by remember(playlist.id) { mutableStateOf(false) }
    var editModeEnabled by remember(playlist.id) { mutableStateOf(false) }
    var selectedTrackKeys by remember(playlist.id) { mutableStateOf(setOf<String>()) }
    var confirmRemove by remember(playlist.id) { mutableStateOf(false) }
    val initialTrackListState = remember(tracks, searchQuery) {
        PlaylistTrackListState(
            sortedTracks = tracks,
            visibleTracks = if (searchQuery.isBlank()) tracks else emptyList(),
        )
    }
    val trackListState by produceState(
        initialTrackListState,
        playlist.id,
        tracks,
        sortBy,
        ascending,
        searchQuery,
    ) {
        value = initialTrackListState
        value = withContext(Dispatchers.Default) {
            val sorted = sortTracksForLibrary(tracks, sortBy, ascending)
            PlaylistTrackListState(
                sortedTracks = sorted,
                visibleTracks = filterTracksByQuery(sorted, searchQuery),
            )
        }
    }
    val sortedTracks = trackListState.sortedTracks
    val visibleTracks = trackListState.visibleTracks
    val actionTracks = sortedTracks
    val ratingActions = LocalRatingActions.current
    val favoriteActions = LocalFavoriteActions.current
    val nowPlaying = LocalNowPlaying.current
    val mobileChromeBottom = LocalMobileChromePadding.current.bottom
    val playlistActions = LocalPlaylistActions.current
    LaunchedEffect(playlist.id, searchQuery) {
        if (searchQuery.isNotBlank()) reorderModeEnabled = false
    }
    LaunchedEffect(editModeEnabled) {
        if (!editModeEnabled) selectedTrackKeys = emptySet()
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val useTable = maxWidth >= 640.dp
        val editBarHeight = 68.dp
        val listState = RetainedLazyListStates.remember("playlist-detail:${playlist.id}")
        val reorderModeAvailable = searchQuery.isBlank() && visibleTracks.size > 1
        val editModeAvailable = visibleTracks.isNotEmpty() && playlist.supportsTrackRemoval()
        val reorderEnabled = reorderModeEnabled &&
            sortBy == LibrarySortBy.PlaylistOrder &&
            ascending &&
            reorderModeAvailable &&
            !editModeEnabled
        val editEnabled = editModeEnabled && editModeAvailable && !reorderEnabled
        val bottomContentPadding = mobileChromeBottom + 24.dp + if (editEnabled && !useTable) editBarHeight else 0.dp
        val toggleTrackSelection = { track: Track ->
            val key = track.playlistRemovalKey()
            selectedTrackKeys = if (key in selectedTrackKeys) {
                selectedTrackKeys - key
            } else {
                selectedTrackKeys + key
            }
        }
        val reorderState = rememberPlaylistTrackReorderState(
            tracks = visibleTracks,
            enabled = reorderEnabled,
            listState = listState,
            rowStep = if (useTable) 64.dp else 72.dp,
            onMove = { from, to -> onMovePlaylistTrack(playlist, from, to) },
        )
        val displayTracks = if (reorderEnabled || reorderState.isDragging) reorderState.tracks else visibleTracks
        val selectedTracks = remember(displayTracks, selectedTrackKeys) {
            displayTracks.filter { it.playlistRemovalKey() in selectedTrackKeys }
        }
        val playDisplayTrack = { visibleIndex: Int ->
            val (queueTracks, queueIndex) = if (reorderEnabled || reorderState.isDragging) {
                playbackQueueForVisibleTrack(displayTracks, displayTracks, visibleIndex)
            } else {
                playbackQueueForVisibleTrack(sortedTracks, visibleTracks, visibleIndex)
            }
            onPlayTracks(queueTracks, queueIndex)
        }

        Box(Modifier.fillMaxSize()) {

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 20.dp, end = 20.dp)
                    .then(if (reorderEnabled) reorderState.listModifier() else Modifier),
                contentPadding = PaddingValues(
                    top = mobileContentTopPadding(16.dp),
                    bottom = bottomContentPadding
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(contentType = "playlist-header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!useTable) {
                            MobilePlaylistDetailHeader(
                                playlist = playlist,
                                tracks = actionTracks,
                                visibleTracks = visibleTracks,
                                searchQuery = searchQuery,
                                favoriteActions = favoriteActions,
                                ratingActions = ratingActions,
                                onBack = onBack,
                                onSearchQuery = onSearchQuery,
                                onPlayTracks = onPlayTracks,
                                onDownloadPlaylist = onDownloadPlaylist,
                                onCancelDownloadPlaylist = onCancelDownloadPlaylist,
                                onDeleteDownloadPlaylist = onDeleteDownloadPlaylist,
                            )
                        } else {
                            DetailSectionIntro(
                                onBack = onBack,
                                label = "Playlist",
                                alignBackIconToContentStart = false,
                            )
                            Text(
                                playlist.title,
                                color = PhoebeUi.primaryText,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.sharedBoundsTransition("playlist:${playlist.id}:title"),
                            )
                            PlaylistTrackSummaryLine(
                                totalCount = sortedTracks.size,
                                visibleCount = visibleTracks.size,
                                searchQuery = searchQuery,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LikeButton(
                                    liked = favoriteActions.isFavorite(playlist),
                                    enabled = true,
                                    onClick = { favoriteActions.onTogglePlaylist(playlist) },
                                )
                                if (ratingActions.ratingsEnabled && (playlist.id.startsWith("plex:") || playlist.id.startsWith("jellyfin:"))) {
                                    RatingStars(
                                        rating = ratingActions.ratingFor(playlist),
                                        enabled = true,
                                        onRating = { ratingActions.onRatePlaylist(playlist, it) },
                                        starSize = 16.dp,
                                        showClear = true,
                                    )
                                }
                                PlaylistManagementMenuButton(playlist)
                            }
                            SearchPill(
                                query = searchQuery,
                                onQueryChange = onSearchQuery,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                placeholder = "Search songs and artists",
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        DetailSectionHeader(
                            title = "Tracks",
                            sortBy = sortBy,
                            sortKeys = listOf(LibrarySortBy.PlaylistOrder, LibrarySortBy.Name, LibrarySortBy.Album, LibrarySortBy.Year, LibrarySortBy.DateAdded),
                            sortLabel = { key ->
                                when (key) {
                                    LibrarySortBy.PlaylistOrder -> "Playlist order"
                                    LibrarySortBy.Album -> "Album name"
                                    LibrarySortBy.Year -> "Release date"
                                    LibrarySortBy.DateAdded -> "Date added"
                                    else -> "Song name"
                                }
                            },
                            onSortBy = {
                                sortBy = it
                                if (it != LibrarySortBy.PlaylistOrder) {
                                    reorderModeEnabled = false
                                    editModeEnabled = false
                                }
                            },
                            ascending = ascending,
                            onAscending = {
                                ascending = it
                                if (!it) {
                                    reorderModeEnabled = false
                                    editModeEnabled = false
                                }
                            },
                            columns = libraryUi.columns,
                            onColumns = onLibraryColumns,
                            reorderMode = reorderModeEnabled,
                            reorderModeAvailable = reorderModeAvailable && !editModeEnabled,
                            onReorderMode = { enabled ->
                                reorderModeEnabled = enabled
                                if (enabled) {
                                    editModeEnabled = false
                                    selectedTrackKeys = emptySet()
                                    sortBy = LibrarySortBy.PlaylistOrder
                                    ascending = true
                                }
                            },
                            editMode = editModeEnabled,
                            editModeAvailable = editModeAvailable && !reorderModeEnabled,
                            onEditMode = { enabled ->
                                editModeEnabled = enabled
                                if (enabled) {
                                    reorderModeEnabled = false
                                } else {
                                    selectedTrackKeys = emptySet()
                                }
                            },
                            actions = {
                                if (useTable) {
                                    DownloadActionButton(
                                        label = "Download Playlist",
                                        tracks = actionTracks,
                                        onCancel = { onCancelDownloadPlaylist(playlist) },
                                        onDelete = { onDeleteDownloadPlaylist(playlist) },
                                    ) { onDownloadPlaylist(playlist) }
                                    PlaylistExportMenu(playlist = playlist)
                                }
                            },
                        )
                        if (editEnabled && useTable) {
                            PlaylistEditActionBar(
                                selectedCount = selectedTrackKeys.size,
                                totalCount = displayTracks.size,
                                onRemove = { confirmRemove = true },
                                onSelectAll = {
                                    selectedTrackKeys = displayTracks.map { it.playlistRemovalKey() }.toSet()
                                },
                                onClearSelection = { selectedTrackKeys = emptySet() },
                            )
                        }
                    }
                }
                if (visibleTracks.isEmpty()) {
                    item(contentType = "playlist-empty") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (playlist.id in LocalTracksLoading.current && searchQuery.isBlank()) CatalogLoadingStrip()
                            Text(
                                when {
                                    searchQuery.isNotBlank() -> "No tracks / artists in this playlist match \"$searchQuery\"."
                                    playlist.id in LocalTracksLoading.current -> "Fetching songs…"
                                    else -> "No tracks loaded for this playlist yet."
                                },
                                color = PhoebeUi.mutedText,
                                fontSize = 15.sp,
                            )
                        }
                    }
                } else if (useTable) {
                    item(contentType = "playlist-track-header") {
                        SongsTableHeader(
                            columns = libraryUi.columns,
                            showLeadingHandle = reorderEnabled,
                            showSelectionColumn = editEnabled,
                        )
                    }
                    itemsIndexed(displayTracks, key = { _, t -> t.reorderKey() }, contentType = { _, _ -> "playlist-track" }) { index, track ->
                        val trackKey = track.playlistRemovalKey()
                        SongRow(
                            track = track,
                            selected = trackKey in selectedTrackKeys,
                            columns = libraryUi.columns,
                            onSelect = {
                                if (editEnabled) {
                                    toggleTrackSelection(track)
                                } else {
                                    playDisplayTrack(index)
                                }
                            },
                            onPlay = { playDisplayTrack(index) },
                            onAddToUpNext = { onAddToUpNext(track) },
                            onDownload = { onDownload(track) },
                            modifier = if (reorderEnabled) reorderState.itemModifier(track) else Modifier.animateItem(),
                            leadingHandle = if (reorderEnabled) {
                                { PlaylistTrackReorderHandle(reorderState, track, index) }
                            } else {
                                null
                            },
                            selectionMode = editEnabled,
                        )
                    }
                } else {
                    itemsIndexed(displayTracks, key = { _, t -> t.reorderKey() }, contentType = { _, _ -> "playlist-track" }) { index, track ->
                        val trackKey = track.playlistRemovalKey()
                        MobileSongRow(
                            track = track,
                            columns = libraryUi.columns,
                            isNowPlaying = track.id == nowPlaying.trackId,
                            nowPlayingIsPlaying = nowPlaying.isPlaying,
                            nowPlayingIsBuffering = nowPlaying.isBuffering,
                            onPlay = { playDisplayTrack(index) },
                            onAddToUpNext = { onAddToUpNext(track) },
                            onDownload = { onDownload(track) },
                            modifier = if (reorderEnabled) reorderState.itemModifier(track) else Modifier,
                            leadingHandle = if (reorderEnabled) {
                                { PlaylistTrackReorderHandle(reorderState, track, index) }
                            } else {
                                null
                            },
                            selectionMode = editEnabled,
                            selected = trackKey in selectedTrackKeys,
                            onToggleSelection = { toggleTrackSelection(track) },
                        )
                    }
                }
            }

            if (editEnabled && !useTable) {
                PlaylistEditBottomBar(
                    selectedCount = selectedTrackKeys.size,
                    totalCount = displayTracks.size,
                    onRemove = { confirmRemove = true },
                    onSelectAll = {
                        selectedTrackKeys = displayTracks.map { it.playlistRemovalKey() }.toSet()
                    },
                    onClearSelection = { selectedTrackKeys = emptySet() },
                    onDone = {
                        editModeEnabled = false
                        selectedTrackKeys = emptySet()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = mobileChromeBottom),
                )
            }
        }
        if (confirmRemove) {
            val count = selectedTracks.size
            ConfirmDeleteDownloadsDialog(
                title = "Remove from playlist?",
                body = "Remove $count ${if (count == 1) "song" else "songs"} from ${playlist.title}?",
                confirmLabel = "Remove",
                onDismiss = { confirmRemove = false },
                onConfirm = {
                    playlistActions.onRemovePlaylistTracks(playlist, selectedTracks)
                    confirmRemove = false
                    editModeEnabled = false
                    selectedTrackKeys = emptySet()
                },
            )
        }
    }
}
