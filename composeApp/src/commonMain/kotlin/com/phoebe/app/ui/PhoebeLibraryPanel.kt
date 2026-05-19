package com.phoebe.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.Image
import phoebe.composeapp.generated.resources.Res
import phoebe.composeapp.generated.resources.phoebe_bird
import phoebe.composeapp.generated.resources.phoebe_icon_rounded
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.phoebe.app.AppState
import com.phoebe.app.data.catalogAlbumsForArtist
import com.phoebe.app.data.catalogTracksForArtist
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.AppScreen
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.JellyfinLibraryPageKind
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.LocalFolderMediaSourceConfig
import com.phoebe.app.domain.MediaSourcesState
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.isLocalMediaPlayback
import com.phoebe.app.domain.isRemoteLibraryTrack
import com.phoebe.app.domain.supportsPlexPlaylists
import com.phoebe.app.platform.createPlatformHttpClient
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.platform.prefersReducedArtworkEffects
import kotlinx.coroutines.delay
import com.phoebe.app.sources.rememberPickLocalFolder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.yield
import kotlin.math.max

@Composable
internal fun LibraryColumnDropdownRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = false,
            colors = CheckboxDefaults.colors(
                checkedColor = PhoebeUi.accentLight,
                uncheckedColor = PhoebeUi.mutedText,
                disabledCheckedColor = PhoebeUi.accentLight,
                disabledUncheckedColor = PhoebeUi.mutedText,
            ),
        )
        Text(label, color = PhoebeUi.primaryText, fontSize = 14.sp)
    }
}

@Composable
internal fun LibrarySortAndDisplayBar(
    prefs: LibraryUiPreferences,
    onSortBy: (LibrarySortBy) -> Unit,
    onAscending: (Boolean) -> Unit,
    onColumns: (LibraryColumnVisibility) -> Unit,
    showSortControls: Boolean = true,
    showColumns: Boolean = true,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    var columnsExpanded by remember { mutableStateOf(false) }
    if (!showSortControls && !showColumns) return
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showSortControls) {
            Box {
                TextButton(onClick = { sortExpanded = true }) {
                    Text(
                        buildString {
                            append("Sort: ")
                            append(when (prefs.sortBy) {
                                LibrarySortBy.AlbumOrder -> "Album order"
                                LibrarySortBy.Name -> "Name"
                                LibrarySortBy.Artist -> "Artist"
                                LibrarySortBy.Album -> "Album"
                                LibrarySortBy.Year -> "Year"
                                LibrarySortBy.PlaylistOrder -> "Playlist order"
                                LibrarySortBy.DateAdded -> "Date added"
                            })
                            append(" ")
                            append(if (prefs.ascending) "↑" else "↓")
                        },
                        color = PhoebeUi.secondaryText,
                        fontSize = 13.sp,
                    )
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Name") },
                        onClick = {
                            onSortBy(LibrarySortBy.Name)
                            sortExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Year") },
                        onClick = {
                            onSortBy(LibrarySortBy.Year)
                            sortExpanded = false
                        },
                    )
                }
            }
            TextButton(onClick = { onAscending(!prefs.ascending) }) {
                Text(
                    if (prefs.ascending) "Ascending" else "Descending",
                    color = PhoebeUi.mutedText,
                    fontSize = 13.sp,
                )
            }
        }
        if (showSortControls || showColumns) {
            Spacer(Modifier.weight(1f))
        }
        if (showColumns) {
            Box {
                TextButton(onClick = { columnsExpanded = true }) {
                    Text("Columns", color = PhoebeUi.accentLight, fontSize = 13.sp)
                }
                DropdownMenu(expanded = columnsExpanded, onDismissRequest = { columnsExpanded = false }) {
                    LibraryColumnDropdownRow("Duration", prefs.columns.duration) {
                        onColumns(prefs.columns.copy(duration = !prefs.columns.duration))
                    }
                    LibraryColumnDropdownRow("Audio codec", prefs.columns.audioCodec) {
                        onColumns(prefs.columns.copy(audioCodec = !prefs.columns.audioCodec))
                    }
                    LibraryColumnDropdownRow("Bitrate", prefs.columns.bitrate) {
                        onColumns(prefs.columns.copy(bitrate = !prefs.columns.bitrate))
                    }
                    LibraryColumnDropdownRow("Sample rate", prefs.columns.sampleRate) {
                        onColumns(prefs.columns.copy(sampleRate = !prefs.columns.sampleRate))
                    }
                    LibraryColumnDropdownRow("File type", prefs.columns.fileType) {
                        onColumns(prefs.columns.copy(fileType = !prefs.columns.fileType))
                    }
                    LibraryColumnDropdownRow("Date added", prefs.columns.dateAdded) {
                        onColumns(prefs.columns.copy(dateAdded = !prefs.columns.dateAdded))
                    }
                    LibraryColumnDropdownRow("Rating", prefs.columns.rating) {
                        onColumns(prefs.columns.copy(rating = !prefs.columns.rating))
                    }
                    LibraryColumnDropdownRow("Favorite", prefs.columns.favorite) {
                        onColumns(prefs.columns.copy(favorite = !prefs.columns.favorite))
                    }
                    LibraryColumnDropdownRow("File path", prefs.columns.filepath) {
                        onColumns(prefs.columns.copy(filepath = !prefs.columns.filepath))
                    }
                    LibraryColumnDropdownRow("Year", prefs.columns.year) {
                        onColumns(prefs.columns.copy(year = !prefs.columns.year))
                    }
                    LibraryColumnDropdownRow("Genre", prefs.columns.genre) {
                        onColumns(prefs.columns.copy(genre = !prefs.columns.genre))
                    }
                }
            }
        }
    }
}

@Composable
internal fun LibraryPanel(
    catalog: CatalogSnapshot,
    catalogRefreshing: Boolean,
    jellyfinPagination: Boolean,
    filter: LibraryFilterTab,
    libraryUi: LibraryUiPreferences,
    onFilter: (LibraryFilterTab) -> Unit,
    onLibrarySortBy: (LibrarySortBy) -> Unit,
    onLibraryAscending: (Boolean) -> Unit,
    onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onJellyfinPage: (JellyfinLibraryPageKind, Int) -> Unit = { _, _ -> },
) {
    var pageIndex by remember(filter) { mutableStateOf(0) }
    val allTracksRaw = remember(catalog.tracksByParent) {
        catalog.tracksByParent.values.asSequence().flatten().distinctBy { it.id }.toList()
    }
    val sortBy = libraryUi.sortBy
    val ascending = libraryUi.ascending
    val sortedArtists = remember(catalog.artists, sortBy, ascending) {
        sortArtistsForLibrary(catalog, sortBy, ascending)
    }
    val sortedAlbums = remember(catalog.albums, sortBy, ascending) {
        sortAlbumsForLibrary(catalog.albums, sortBy, ascending)
    }
    val sortedTracks = remember(allTracksRaw, sortBy, ascending) {
        sortTracksForLibrary(allTracksRaw, sortBy, ascending)
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
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(contentType = "filter") {
            LibraryFilterToggle(filter, onFilter)
        }
        item(contentType = "library-sort") {
            LibrarySortAndDisplayBar(
                prefs = libraryUi,
                onSortBy = onLibrarySortBy,
                onAscending = onLibraryAscending,
                onColumns = onLibraryColumns,
                showColumns = filter == LibraryFilterTab.Songs,
            )
        }
        if (catalogRefreshing) {
            item(contentType = "loading") { CatalogLoadingStrip() }
        }
        item(contentType = "pagination") {
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
        }
        when (filter) {
            LibraryFilterTab.Artists -> {
                items(artistPage.items, key = { it.id }, contentType = { "artist" }) { artist ->
                    LibraryRow(artist.title, artistAlbumCountSubtitle(artist), artist.title, artist.thumbUrl) {
                        onArtist(artist)
                    }
                }
            }
            LibraryFilterTab.Albums -> {
                items(albumPage.items, key = { it.id }, contentType = { "album" }) { album ->
                    LibraryRow(album.title, "${album.artist} • ${album.year ?: "Album"}", album.title, album.thumbUrl) {
                        onAlbum(album)
                    }
                }
            }
            LibraryFilterTab.Songs -> {
                itemsIndexed(trackPage.items, key = { _, track -> track.id }, contentType = { _, _ -> "song" }) { index, track ->
                    ContentTrackRow(
                        track = track,
                        libraryColumns = libraryUi.columns,
                        onPlay = { onPlayTracks(trackPage.items, index) },
                        onAddToUpNext = { onAddToUpNext(track) },
                        onDownload = { onDownload(track) },
                    )
                }
            }
        }
        if (filter != LibraryFilterTab.Songs && filter != LibraryFilterTab.Artists) {
            item(contentType = "playlist-header") {
                Spacer(Modifier.height(8.dp))
                SectionLabel("Playlists", PhoebeUi.primaryText)
            }
            items(catalog.playlists, key = { it.id }, contentType = { "playlist" }) { playlist ->
                LibraryRow(
                    title = playlist.title,
                    subtitle = "${playlist.trackCount} songs",
                    seed = playlist.title,
                    thumbUrl = playlist.thumbUrl,
                    onClick = { onPlaylist(playlist) },
                )
            }
        }
    }
}

@Composable
internal fun LibraryFilterToggle(selected: LibraryFilterTab, onSelected: (LibraryFilterTab) -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(PhoebeUi.elevatedFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), CircleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LibraryFilterTab.entries.forEach { filter ->
            val active = filter == selected
            Text(
                text = when (filter) {
                    LibraryFilterTab.Artists -> "Artists"
                    LibraryFilterTab.Albums -> "Albums"
                    LibraryFilterTab.Songs -> "All Songs"
                },
                color = if (active) PhoebeUi.primaryText else PhoebeUi.secondaryText,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onSelected(filter) }
                    .background(if (active) PhoebeUi.accent.copy(alpha = 0.42f) else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** Phoebe brand mark — the foreground bird from the app icon shown beside the wordmark. */
@Composable
internal fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.phoebe_bird),
        contentDescription = "Phoebe",
        modifier = modifier.size(size),
    )
}

@Composable
internal fun LibraryRow(
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
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(18.dp))
    }
}
