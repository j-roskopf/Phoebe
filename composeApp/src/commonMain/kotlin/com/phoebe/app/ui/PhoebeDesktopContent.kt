package com.phoebe.app.ui

import com.phoebe.app.feature.library.*
import com.phoebe.app.feature.radio.RadioRoute
import com.phoebe.app.feature.radio.RadioRouteActions
import com.phoebe.app.feature.radio.RadioRouteMode
import com.phoebe.app.feature.radio.RadioRouteState
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
import com.phoebe.app.data.filterPlaylistsByQuery
import com.phoebe.app.data.filterTracksByQuery
import com.phoebe.app.data.sortTracksForLibrary
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
import com.phoebe.app.domain.RadioDirectoryState
import com.phoebe.app.domain.RadioStation
import com.phoebe.app.domain.RadioStationSearchQuery
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.isLocalMediaPlayback
import com.phoebe.app.domain.isLikedSongsPlaylist
import com.phoebe.app.domain.isRemoteLibraryTrack
import com.phoebe.app.domain.supportsPlexPlaylists
import com.phoebe.app.domain.supportsTrackRemoval
import com.phoebe.app.platform.createPlatformHttpClient
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.platform.prefersReducedArtworkEffects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import com.phoebe.app.sources.rememberPickLocalFolder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.max

@Composable
internal fun DesktopContent(
    catalog: CatalogSnapshot,
    catalogRefreshing: Boolean,
    jellyfinPagination: Boolean = false,
    section: BrowseSection,
    selectedPlaylistId: String?,
    searchQuery: String,
    libraryFilter: LibraryFilterTab,
    libraryUi: LibraryUiPreferences,
    modifier: Modifier,
    onSearchQuery: (String) -> Unit,
    onLibraryFilter: (LibraryFilterTab) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onAddToEndOfQueue: (Track) -> Unit = {},
    onLibrarySortBy: (LibrarySortBy) -> Unit,
    onLibraryAscending: (Boolean) -> Unit,
    onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    radioDirectory: RadioDirectoryState = RadioDirectoryState(),
    radioRouteMode: RadioRouteMode = RadioRouteMode.Home,
    internetRadioStartingIds: Set<String> = emptySet(),
    onRadioSearch: (RadioStationSearchQuery) -> Unit = {},
    onRadioLoadMore: () -> Unit = {},
    onRadioRefreshPopular: () -> Unit = {},
    onRadioPlay: (RadioStation) -> Unit = {},
    onRadioCountries: () -> Unit = {},
    onRadioCountry: (String) -> Unit = {},
    onRadioMap: () -> Unit = {},
    onRadioMapSearch: (RadioStationSearchQuery, Int) -> Unit = { _, _ -> },
    onRadioMapCountry: (String) -> Unit = {},
    onRadioStation: (RadioStation) -> Unit = onRadioPlay,
    onRadioRoot: () -> Unit = {},
    onRadioAddManualStation: (String, String) -> Unit = { _, _ -> },
    onRadioUpdateManualStation: (RadioStation, String, String) -> Unit = { _, _, _ -> },
    onRadioDeleteManualStation: (RadioStation) -> Unit = {},
    edgePadding: Dp = 36.dp,
    headlineFontSize: TextUnit = 30.sp,
    headlineLineHeight: TextUnit = 35.sp,
    searchPillModifier: Modifier = Modifier.width(270.dp),
    onDownloadPlaylist: (Playlist) -> Unit = {},
    onJellyfinPage: (JellyfinLibraryPageKind, Int) -> Unit = { _, _ -> },
) {
    val selectedPlaylist = catalog.playlists.firstOrNull { it.id == selectedPlaylistId }
    val playlistTracks = selectedPlaylistId?.let { catalog.tracksByParent[it].orEmpty() }.orEmpty()
    if (selectedPlaylist != null) {
        PlaylistDetailDesktopRoute(
            state = PlaylistDetailDesktopRouteState(
                playlist = selectedPlaylist,
                tracks = playlistTracks,
                catalogRefreshing = catalogRefreshing,
                searchQuery = searchQuery,
                libraryUi = libraryUi,
            ),
            actions = PlaylistDetailDesktopRouteActions(
                onSearchQuery = onSearchQuery,
                onPlayTracks = onPlayTracks,
                onAddToUpNext = onAddToUpNext,
                onDownload = onDownload,
                onLibraryColumns = onLibraryColumns,
                onDownloadPlaylist = onDownloadPlaylist,
                onAddToEndOfQueue = onAddToEndOfQueue,
            ),
            modifier = modifier,
            edgePadding = edgePadding,
            headlineFontSize = headlineFontSize,
            headlineLineHeight = headlineLineHeight,
            searchPillModifier = searchPillModifier,
        )
        return
    }

    if (section == BrowseSection.Playlists) {
        PlaylistsDesktopRoute(
            state = PlaylistsRouteState(
                catalog = catalog,
                catalogRefreshing = catalogRefreshing,
                searchQuery = searchQuery,
            ),
            actions = PlaylistsRouteActions(
                onSearchQuery = onSearchQuery,
                onPlaylist = onPlaylist,
            ),
            modifier = modifier,
            edgePadding = edgePadding,
            headlineFontSize = headlineFontSize,
            headlineLineHeight = headlineLineHeight,
            searchPillModifier = searchPillModifier,
        )
        return
    }

    if (section == BrowseSection.Radio) {
        RadioRoute(
            state = RadioRouteState(radioDirectory, internetRadioStartingIds),
            actions = RadioRouteActions(
                onSearch = onRadioSearch,
                onLoadMore = onRadioLoadMore,
                onRefreshPopular = onRadioRefreshPopular,
                onPlay = onRadioPlay,
                onAddManualStation = onRadioAddManualStation,
                onUpdateManualStation = onRadioUpdateManualStation,
                onDeleteManualStation = onRadioDeleteManualStation,
                onCountry = { country -> onRadioCountry(country.code) },
                onBrowseGlobe = onRadioMap,
                onGlobeSearch = onRadioMapSearch,
                onGlobeCountry = onRadioMapCountry,
                onStation = onRadioStation,
                onClearCountry = onRadioRoot,
                onBrowseCountries = onRadioCountries,
            ),
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = edgePadding, vertical = edgePadding),
            mode = radioRouteMode,
        )
        return
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
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sectionLabel = when (section) {
                BrowseSection.Search -> "Search"
                BrowseSection.Library -> "Your Library"
                BrowseSection.Lyrics -> "Lyrics"
                BrowseSection.Downloads -> "Downloads"
                BrowseSection.Settings -> "Settings"
                BrowseSection.Home -> "Home"
                BrowseSection.Playlists -> "Playlists"
            }
            val headline = when (section) {
                BrowseSection.Search -> "Find your sound"
                BrowseSection.Library -> "Albums, artists, and songs"
                BrowseSection.Lyrics -> "Follow along"
                BrowseSection.Downloads -> "Offline songs"
                BrowseSection.Settings -> "Customize your listening experience"
                BrowseSection.Home -> "Now playing"
                BrowseSection.Playlists -> "Your playlists"
            }
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
                }
            }
            if (maxWidth < 640.dp) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.fillMaxWidth()) { titleBlock() }
                    SearchPill(searchQuery, onSearchQuery, searchPillModifier)
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { titleBlock() }
                    SearchPill(searchQuery, onSearchQuery, searchPillModifier)
                }
            }
        }

        val firstTracks = catalog.tracksByParent.values.firstOrNull().orEmpty()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DetailSectionToolbar(
                sortBy = null,
                sortKeys = emptyList(),
                sortLabel = { "" },
                onSortBy = null,
                ascending = null,
                onAscending = null,
                columns = libraryUi.columns,
                onColumns = onLibraryColumns,
            )
            TrackList(
                firstTracks,
                "Your library is empty.",
                catalogRefreshing,
                onPlayTracks,
                onAddToUpNext,
                onDownload,
                onAddToEndOfQueue = onAddToEndOfQueue,
                libraryColumns = libraryUi.columns,
            )
        }
    }
}
