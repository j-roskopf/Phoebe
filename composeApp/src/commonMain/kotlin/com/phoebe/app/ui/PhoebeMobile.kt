package com.phoebe.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.Orientation
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
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
import com.phoebe.app.domain.AppSettings
import com.phoebe.app.domain.AppScreen
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.ArtistRadioAvailability
import com.phoebe.app.domain.HomeSection
import com.phoebe.app.domain.JellyfinLibraryPageKind
import com.phoebe.app.domain.JellyfinSyncMode
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.LocalFolderMediaSourceConfig
import com.phoebe.app.domain.MediaSourcesState
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PersonalMixPreferences
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.PlexRadioStation
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.defaultCollectionEntries
import com.phoebe.app.player.CastState
import com.phoebe.app.domain.isEmbyFamily
import com.phoebe.app.domain.isLocalMediaPlayback
import com.phoebe.app.domain.isJellyfin
import com.phoebe.app.domain.isNavidrome
import com.phoebe.app.domain.isRemoteLibraryTrack
import com.phoebe.app.domain.supportsPlexPlaylists
import com.phoebe.app.platform.createPlatformHttpClient
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.platform.prefersReducedArtworkEffects
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.phoebe.app.sources.rememberPickLocalFolder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.yield
import kotlin.math.abs
import kotlin.math.max

private const val MobileBufferFallbackTickMs = 500L
private const val MobileBufferFallbackAdvanceMs = 2_000L

@Composable
internal fun MobileCompactMainFeature(
    track: Track?,
    onOpenFullPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        MobileHomeHero(track, onOpenFullPlayer)
    }
}

@Composable
internal fun MobileHomeHero(track: Track?, onOpenFullPlayer: () -> Unit) {
    if (track == null) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EmptyNowPlayingArtworkSlot(Modifier.size(168.dp), glyphSp = 48.sp)
            SectionLabel("Now Playing", PhoebeUi.accentLight)
            Text("Nothing playing", color = PhoebeUi.primaryText, fontSize = 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Text(
                "Use Search or Library below to pick a track.",
                color = PhoebeUi.secondaryText,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("Now Playing", PhoebeUi.accentLight)
            TrackArtworkImage(track, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)))
            AutoScrollingText(track.title, color = PhoebeUi.primaryText, fontSize = 22.sp, fontWeight = FontWeight.Black)
            AutoScrollingText(track.artist, color = PhoebeUi.secondaryText, fontSize = 15.sp)
            Text(
                "Open full player",
                color = PhoebeUi.accentLight,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenFullPlayer)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
internal fun MobileBottomNavigation(
    section: DesktopSection,
    onSection: (DesktopSection) -> Unit,
) {
    val tabs = listOf(
        DesktopSection.Home to (PhoebeIcon.Home to "Home"),
        DesktopSection.Search to (PhoebeIcon.Search to "Search"),
        DesktopSection.Library to (PhoebeIcon.Library to "Library"),
        DesktopSection.Playlists to (PhoebeIcon.PlaylistPlay to "Playlists"),
    )
    val topShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(topShape)
            .background(PhoebeUi.navBar, topShape)
            .border(BorderStroke(1.dp, PhoebeUi.border), topShape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { (target, iconLabel) ->
                val (icon, label) = iconLabel
                val active = section == target
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSection(target) }
                        .padding(vertical = 6.dp, horizontal = 2.dp)
                        .semantics { contentDescription = label },
                ) {
                    PhoebeIconView(icon, tint = if (active) PhoebeUi.accentLight else PhoebeUi.secondaryText, modifier = Modifier.size(19.dp))
                    Text(
                        label.uppercase(),
                        color = if (active) PhoebeUi.primaryText else PhoebeUi.mutedText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.06.em,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
internal fun MobileScreenToolbar(
    title: String,
    onBack: (() -> Unit)? = null,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    menuContent: @Composable () -> Unit,
    showMenu: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                PhoebeIconView(
                    PhoebeIcon.Back,
                    tint = PhoebeUi.primaryText,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else {
            Spacer(Modifier.size(44.dp))
        }
        Text(
            title,
            color = PhoebeUi.primaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        if (showMenu) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable { onMenuExpandedChange(true) },
                contentAlignment = Alignment.Center,
            ) {
                PhoebeIconView(
                    PhoebeIcon.More,
                    tint = PhoebeUi.primaryText,
                    modifier = Modifier.size(22.dp),
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { onMenuExpandedChange(false) },
                ) {
                    menuContent()
                }
            }
        } else {
            Spacer(Modifier.size(44.dp))
        }
    }
}

internal fun mobileSectionTitle(section: DesktopSection): String = when (section) {
    DesktopSection.Home -> "Home"
    DesktopSection.Search -> "Search"
    DesktopSection.Library -> "Library"
    DesktopSection.Lyrics -> "Lyrics"
    DesktopSection.Playlists -> "Playlists"
    DesktopSection.Settings -> "Settings"
}

@Composable
internal fun MobileBrowseShell(
    catalog: CatalogSnapshot,
    catalogRefreshing: Boolean,
    session: PlexSession?,
    section: DesktopSection,
    selectedPlaylistId: String?,
    searchQuery: String,
    libraryFilter: LibraryFilterTab,
    libraryUi: LibraryUiPreferences,
    currentTrack: Track?,
    homeUiState: HomeUiState,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    onNavigate: (DesktopSection) -> Unit,
    onSearchQuery: (String) -> Unit,
    onLibraryFilter: (LibraryFilterTab) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onSong: (Track) -> Unit,
    onRecentSongs: () -> Unit,
    onRecentArtists: () -> Unit,
    onRecentAlbums: () -> Unit,
    onFavoritePlaylists: () -> Unit,
    onFavoriteArtists: () -> Unit,
    onFavoriteAlbums: () -> Unit,
    onRecentlyPlayed: () -> Unit,
    onMostPlayed: () -> Unit,
    onCollections: (CollectionEntry) -> Unit,
    supportedCollectionEntries: Set<CollectionEntry> = defaultCollectionEntries.toSet(),
    onRefreshRandomArtists: () -> Unit,
    onRefreshRandomAlbums: () -> Unit,
    onPrefetchHomeArtist: (Artist) -> Unit = {},
    onPrefetchHomeAlbum: (Album) -> Unit = {},
    onPlayDecadeMix: (Int) -> Unit = {},
    decadeMixNotice: String? = null,
    onClearDecadeMixNotice: () -> Unit = {},
    radioStations: List<PlexRadioStation> = emptyList(),
    radioStartingIds: Set<String> = emptySet(),
    onPlayRadioStation: (PlexRadioStation) -> Unit = {},
    onPlayPersonalMix: () -> Unit = {},
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    artistRadioAvailability: Map<String, ArtistRadioAvailability> = emptyMap(),
    onProbeArtistRadio: (Artist) -> Unit = {},
    onPlayArtistRadio: (Artist) -> Unit = {},
    onOpenNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSignOut: () -> Unit,
    onAddLocalFolder: (String?) -> Unit,
    onRefreshLibrary: () -> Unit,
    onJellyfinPage: (JellyfinLibraryPageKind, Int) -> Unit = { _, _ -> },
    onLibrarySortBy: (LibrarySortBy) -> Unit,
    onLibraryAscending: (Boolean) -> Unit,
    onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    onHomeSections: (List<HomeSection>) -> Unit,
    onPersonalMix: (PersonalMixPreferences) -> Unit,
    onExportFavoritePlaylists: () -> Unit,
    onImportFavoritePlaylists: () -> Unit,
    appSettings: AppSettings,
    onCrossfadeSeconds: (Int) -> Unit,
    onScanLibraryOnLaunch: (Boolean) -> Unit,
    onNotifyWhenDownloadFinishes: (Boolean) -> Unit,
    downloadDirectory: String?,
    downloadCount: Int,
    defaultDownloadDirectoryLabel: String,
    onDownloadDirectory: (String?) -> Unit,
    onDeleteAllDownloads: () -> Unit,
    useLightAppearance: Boolean,
    onUseLightAppearanceChange: (Boolean) -> Unit,
    appearanceTintId: String,
    onAppearanceTintChange: (String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val pickLocalFolder = rememberPickLocalFolder(onPicked = onAddLocalFolder)
    val toolbarTitle = when {
        section == DesktopSection.Settings -> "Settings"
        selectedPlaylistId != null -> "Playlist"
        else -> mobileSectionTitle(section)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(PhoebeUi.shellTop),
    ) {
        Column(
            Modifier
                .fillMaxSize(),
        ) {
        MobileScreenToolbar(
            title = toolbarTitle,
            onBack = if (section == DesktopSection.Settings && selectedPlaylistId == null) {
                { onNavigate(DesktopSection.Home) }
            } else {
                null
            },
            menuExpanded = menuExpanded,
            onMenuExpandedChange = { menuExpanded = it },
            showMenu = !(section == DesktopSection.Settings && selectedPlaylistId == null),
            menuContent = {
            val userName = session?.userName
            if (userName != null) {
                DropdownMenuItem(
                    text = { Text(userName, color = PhoebeUi.mutedText, fontSize = 13.sp) },
                    onClick = {},
                    enabled = false,
                )
            }
            if (LocalCatalogSyncState.current.isActive) {
                DropdownMenuItem(
                    text = { CatalogMenuSyncIndicator() },
                    onClick = {},
                    enabled = false,
                )
            }
            DropdownMenuItem(
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        PhoebeIconView(PhoebeIcon.Settings, tint = PhoebeUi.secondaryText, modifier = Modifier.size(18.dp))
                        Text("Settings")
                    }
                },
                onClick = {
                    onNavigate(DesktopSection.Settings)
                    menuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Refresh library") },
                onClick = {
                    onRefreshLibrary()
                    menuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Add music folder") },
                onClick = {
                    pickLocalFolder()
                    menuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Sign out") },
                onClick = {
                    onSignOut()
                    menuExpanded = false
                },
            )
            },
        )

        Column(Modifier.weight(1f).fillMaxWidth()) {
            when {
                section == DesktopSection.Settings && selectedPlaylistId == null -> SettingsMobileView(
                    isLightMode = useLightAppearance,
                    onLightModeChange = onUseLightAppearanceChange,
                    tintId = appearanceTintId,
                    onTintChange = onAppearanceTintChange,
                    downloadDirectory = downloadDirectory,
                    downloadCount = downloadCount,
                    appSettings = appSettings,
                    libraryUi = libraryUi,
                    defaultDownloadDirectoryLabel = defaultDownloadDirectoryLabel,
                    onDownloadDirectory = onDownloadDirectory,
                    onDeleteAllDownloads = onDeleteAllDownloads,
                    onCrossfadeSeconds = onCrossfadeSeconds,
                    onScanLibraryOnLaunch = onScanLibraryOnLaunch,
                    onNotifyWhenDownloadFinishes = onNotifyWhenDownloadFinishes,
                    onHomeSections = onHomeSections,
                    onPersonalMix = onPersonalMix,
                    onExportFavoritePlaylists = onExportFavoritePlaylists,
                    onImportFavoritePlaylists = onImportFavoritePlaylists,
                    modifier = Modifier.fillMaxSize(),
                )
                section == DesktopSection.Home && selectedPlaylistId == null -> {
                    val homeListState = RetainedLazyListStates.remember("mobile-home")
                    MobileHomeScreen(
                    state = homeUiState,
                    catalogRefreshing = catalogRefreshing,
                    listState = homeListState,
                    modifier = Modifier.fillMaxSize(),
                    onArtist = onArtist,
                    onAlbum = onAlbum,
                    onPlaylist = onPlaylist,
                    onRecentSongs = onRecentSongs,
                    onRecentArtists = onRecentArtists,
                    onRecentAlbums = onRecentAlbums,
                    onFavoritePlaylists = onFavoritePlaylists,
                    onFavoriteArtists = onFavoriteArtists,
                    onFavoriteAlbums = onFavoriteAlbums,
                    onCollections = onCollections,
                    onRecentlyPlayed = onRecentlyPlayed,
                    onMostPlayed = onMostPlayed,
                    onRefreshArtists = onRefreshRandomArtists,
                    onRefreshAlbums = onRefreshRandomAlbums,
                    onPrefetchArtist = onPrefetchHomeArtist,
                    onPrefetchAlbum = onPrefetchHomeAlbum,
                    onPlayDecadeMix = onPlayDecadeMix,
                    decadeMixNotice = decadeMixNotice,
                    onClearDecadeMixNotice = onClearDecadeMixNotice,
                    radioStations = radioStations,
                    radioStartingIds = radioStartingIds,
                    onPlayRadioStation = onPlayRadioStation,
                    onPlayPersonalMix = onPlayPersonalMix,
                    onPlayTracks = onPlayTracks,
                    onAddToUpNext = onAddToUpNext,
                    onDownload = onDownload,
                    homeSections = libraryUi.homeSections,
                    supportedCollectionEntries = supportedCollectionEntries,
                )
                }
                section == DesktopSection.Library && selectedPlaylistId == null -> LibraryMobileView(
                    catalog = catalog,
                    catalogRefreshing = catalogRefreshing,
                    filter = libraryFilter,
                    libraryUi = libraryUi,
                    jellyfinPagination = (session.isEmbyFamily() || session.isNavidrome()) && session?.jellyfinSyncMode == JellyfinSyncMode.Quick,
                    onJellyfinPage = onJellyfinPage,
                    onFilter = onLibraryFilter,
                    onLibrarySortBy = onLibrarySortBy,
                    onLibraryAscending = onLibraryAscending,
                    onLibraryColumns = onLibraryColumns,
                    onArtist = onArtist,
                    onAlbum = onAlbum,
                    onPlayTracks = onPlayTracks,
                    onAddToUpNext = onAddToUpNext,
                    onDownload = onDownload,
                    modifier = Modifier.fillMaxSize(),
                )
                section == DesktopSection.Search && selectedPlaylistId == null -> SearchMobileView(
                    catalog = catalog,
                    catalogRefreshing = catalogRefreshing,
                    searchQuery = searchQuery,
                    onSearchQuery = onSearchQuery,
                    onArtist = onArtist,
                    onAlbum = onAlbum,
                    onPlayTracks = onPlayTracks,
                    onAddToUpNext = onAddToUpNext,
                    onDownload = onDownload,
                    modifier = Modifier.fillMaxSize(),
                )
                section == DesktopSection.Playlists && selectedPlaylistId == null -> PlaylistsMobileView(
                    catalogRefreshing = catalogRefreshing,
                    searchQuery = searchQuery,
                    onSearchQuery = onSearchQuery,
                    onPlaylist = onPlaylist,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> DesktopContent(
                    catalog = catalog,
                    catalogRefreshing = catalogRefreshing,
                    jellyfinPagination = (session.isEmbyFamily() || session.isNavidrome()) && session?.jellyfinSyncMode == JellyfinSyncMode.Quick,
                    onJellyfinPage = onJellyfinPage,
                    section = section,
                    selectedPlaylistId = selectedPlaylistId,
                    searchQuery = searchQuery,
                    libraryFilter = libraryFilter,
                    libraryUi = libraryUi,
                    modifier = Modifier.fillMaxSize(),
                    onSearchQuery = onSearchQuery,
                    onLibraryFilter = onLibraryFilter,
                    onPlaylist = onPlaylist,
                    onArtist = onArtist,
                    onAlbum = onAlbum,
                    onPlayTracks = onPlayTracks,
                    onAddToUpNext = onAddToUpNext,
                    onDownload = onDownload,
                    onLibrarySortBy = onLibrarySortBy,
                    onLibraryAscending = onLibraryAscending,
                    onLibraryColumns = onLibraryColumns,
                    edgePadding = 20.dp,
                    headlineFontSize = 22.sp,
                    headlineLineHeight = 26.sp,
                    searchPillModifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (currentTrack != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(PhoebeUi.panel)
                    .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenNowPlaying)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TrackArtworkImage(currentTrack, Modifier.size(44.dp))
                    Column(Modifier.weight(1f)) {
                        AutoScrollingText(
                            currentTrack.title,
                            color = PhoebeUi.primaryText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        AutoScrollingText(
                            currentTrack.artist,
                            color = PhoebeUi.secondaryText,
                            fontSize = 12.sp,
                        )
                    }
                }
                PlayButton(isPlaying, isBuffering, 40.dp, onTogglePlayPause, enabled = true)
            }
        }

        MobileBottomNavigation(section = section, onSection = onNavigate)
        }
    }
}


@Composable
internal fun SwipeableMobileArtwork(
    track: Track,
    nextTrack: Track?,
    previousTrack: Track?,
    onSkipQueueBy: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val settleOffset = remember { Animatable(0f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var isSwipeAnimating by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(track.id) {
        if (isSwipeAnimating) return@LaunchedEffect
        settleJob?.cancel()
        settleOffset.stop()
        settleOffset.snapTo(0f)
        dragOffset = 0f
        isDragging = false
    }

    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .semantics {
                contentDescription = "Album artwork. Swipe left for next track, swipe right for previous track."
            },
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val swipeThresholdPx = with(density) { 56.dp.toPx() }
        val displayOffset = if (isDragging) dragOffset else settleOffset.value
        val dragProgress = (abs(displayOffset) / widthPx).coerceIn(0f, 1f)

        fun settleToCenter(fromOffset: Float) {
            settleJob?.cancel()
            settleJob = scope.launch {
                settleOffset.snapTo(fromOffset)
                settleOffset.animateTo(
                    0f,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessLow,
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                    ),
                )
            }
        }

        fun animateSwipeCommit(releaseOffset: Float) {
            settleJob?.cancel()
            settleJob = scope.launch {
                settleOffset.snapTo(releaseOffset)
                when {
                    releaseOffset < -swipeThresholdPx -> {
                        isSwipeAnimating = true
                        settleOffset.animateTo(
                            targetValue = -widthPx,
                            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                        )
                        val steps = (abs(releaseOffset) / widthPx).toInt().coerceIn(1, 5)
                        onSkipQueueBy(steps)
                        settleOffset.snapTo(0f)
                        isSwipeAnimating = false
                    }
                    releaseOffset > swipeThresholdPx -> {
                        isSwipeAnimating = true
                        settleOffset.animateTo(
                            targetValue = widthPx,
                            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                        )
                        val steps = -(abs(releaseOffset) / widthPx).toInt().coerceIn(1, 5)
                        onSkipQueueBy(steps)
                        settleOffset.snapTo(0f)
                        isSwipeAnimating = false
                    }
                    else -> {
                        settleOffset.animateTo(
                            0f,
                            animationSpec = spring(
                                stiffness = Spring.StiffnessLow,
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                            ),
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(track.id, widthPx, swipeThresholdPx) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            settleJob?.cancel()
                            dragOffset = settleOffset.value
                            isDragging = true
                            scope.launch { settleOffset.stop() }
                        },
                        onDragEnd = {
                            isDragging = false
                            val releaseOffset = dragOffset
                            dragOffset = 0f
                            animateSwipeCommit(releaseOffset)
                        },
                        onDragCancel = {
                            isDragging = false
                            val releaseOffset = dragOffset
                            dragOffset = 0f
                            settleToCenter(releaseOffset)
                        },
                        onHorizontalDrag = { _, dragAmount -> dragOffset += dragAmount },
                    )
                },
        ) {
            if (displayOffset < 0f && nextTrack != null) {
                TrackArtworkImage(
                    nextTrack,
                    Modifier
                        .fillMaxSize()
                        .offset { IntOffset((widthPx + displayOffset).roundToInt(), 0) },
                    radius = 10.dp,
                )
            }
            if (displayOffset > 0f && previousTrack != null) {
                TrackArtworkImage(
                    previousTrack,
                    Modifier
                        .fillMaxSize()
                        .offset { IntOffset((displayOffset - widthPx).roundToInt(), 0) },
                    radius = 10.dp,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(displayOffset.roundToInt(), 0) }
                    .graphicsLayer {
                        val scale = 1f - dragProgress * 0.03f
                        scaleX = scale
                        scaleY = scale
                    },
            ) {
                key(track.id) {
                    FlippableSongArtwork(track = track, modifier = Modifier.fillMaxSize(), radius = 10.dp)
                }
            }
        }
    }
}

@Composable
private fun rememberRetainedMobilePlayerUpNextSheetState(
    key: String,
    initiallyExpanded: Boolean,
): MobilePlayerUpNextSheetState =
    remember(key) {
        RetainedMobilePlayerUpNextSheetStates.getOrPut(
            key = key,
            initiallyExpanded = initiallyExpanded,
        )
    }

private object RetainedMobilePlayerUpNextSheetStates {
    private val cache = mutableMapOf<String, MobilePlayerUpNextSheetState>()

    fun getOrPut(key: String, initiallyExpanded: Boolean): MobilePlayerUpNextSheetState =
        cache.getOrPut(key) { MobilePlayerUpNextSheetState(if (initiallyExpanded) 1f else 0f) }
}

private class MobilePlayerUpNextSheetState(initialProgress: Float) {
    var progress by mutableFloatStateOf(initialProgress.coerceIn(0f, 1f))
}

@Composable
internal fun MobilePlayer(
    track: Track?,
    upNext: List<Track>,
    previousTrack: Track? = null,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    shuffle: Boolean,
    repeat: RepeatMode,
    positionMs: Long,
    bufferedPositionMs: Long,
    @Suppress("UNUSED_PARAMETER") currentIndex: Int,
    castState: CastState = CastState(),
    remotePlaybackTarget: String? = null,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSkipQueueBy: (Int) -> Unit = {},
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayQueue: (Int) -> Unit,
    onMoveUpNext: (Int, Int) -> Unit,
    onRemoveUpNext: (Int) -> Unit,
    onOpenSongDetail: (Track) -> Unit = {},
    onCast: () -> Unit = {},
    onLyrics: () -> Unit = {},
    onBack: () -> Unit,
    onSwipeDismiss: () -> Unit,
    initialUpNextExpanded: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val remoteDurationMs = track
        ?.takeUnless { it.isLocalMediaPlayback() }
        ?.durationMs
        ?.takeIf { it > 0L }
    val latestPositionMs by rememberUpdatedState(positionMs)
    val latestBufferedPositionMs by rememberUpdatedState(bufferedPositionMs)
    var estimatedRemoteBufferedPositionMs by remember(track?.id) {
        mutableStateOf(max(positionMs, bufferedPositionMs))
    }

    LaunchedEffect(track?.id) {
        estimatedRemoteBufferedPositionMs = max(positionMs, bufferedPositionMs)
    }
    LaunchedEffect(remoteDurationMs, bufferedPositionMs, positionMs) {
        val duration = remoteDurationMs
        if (duration == null) {
            estimatedRemoteBufferedPositionMs = bufferedPositionMs
            return@LaunchedEffect
        }
        estimatedRemoteBufferedPositionMs = max(
            estimatedRemoteBufferedPositionMs,
            max(positionMs, bufferedPositionMs),
        ).coerceAtMost(duration)
    }
    LaunchedEffect(track?.id, remoteDurationMs, isPlaying, isBuffering) {
        val duration = remoteDurationMs ?: return@LaunchedEffect
        if (!isPlaying && !isBuffering) return@LaunchedEffect
        while (estimatedRemoteBufferedPositionMs < duration) {
            delay(MobileBufferFallbackTickMs)
            val platformFloor = max(latestPositionMs, latestBufferedPositionMs)
            estimatedRemoteBufferedPositionMs = max(estimatedRemoteBufferedPositionMs, platformFloor)
                .plus(MobileBufferFallbackAdvanceMs)
                .coerceAtMost(duration)
        }
    }
    val timelineBufferedPositionMs = remember(remoteDurationMs, bufferedPositionMs, estimatedRemoteBufferedPositionMs) {
        remoteDurationMs?.let { duration ->
            max(bufferedPositionMs, estimatedRemoteBufferedPositionMs).coerceIn(0L, duration)
        } ?: bufferedPositionMs
    }
    val retainedSheetState = rememberRetainedMobilePlayerUpNextSheetState(
        key = "mobile-player-up-next-sheet",
        initiallyExpanded = initialUpNextExpanded,
    )
    val upNextListState = RetainedLazyListStates.remember("mobile-player-up-next-list")
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDraggingDismiss by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    val dismissThresholdPx = with(density) { 96.dp.toPx() }
    val offScreenPx = with(density) { 1200.dp.toPx() }
    val animatedOffset by animateFloatAsState(
        targetValue = when {
            dismissing -> offScreenPx
            else -> dragOffset.coerceAtLeast(0f)
        },
        animationSpec = if (dismissing) {
            tween(durationMillis = 260, easing = FastOutSlowInEasing)
        } else {
            spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioLowBouncy)
        },
        finishedListener = { value ->
            if (dismissing && value >= offScreenPx * 0.9f) {
                onSwipeDismiss()
            }
        },
        label = "player-swipe-settle",
    )
    val displayOffset = if (isDraggingDismiss) dragOffset.coerceAtLeast(0f) else animatedOffset
    val hasTrack = track != null
    val trackNavigationActions = LocalTrackNavigationActions.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, displayOffset.roundToInt()) }
            .background(
                Brush.radialGradient(
                    listOf(PhoebeUi.shellRadialTint, Color.Transparent),
                    center = Offset(210f, 50f),
                    radius = 380f,
                ),
            )
            .background(Brush.verticalGradient(listOf(PhoebeUi.shellTop, PhoebeUi.canvasBackground)))
            .navigationBarsPadding(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val collapsedSheetHeightPx = with(density) { 84.dp.toPx() }
            val expandedSheetHeightPx = with(density) {
                val controlsPx = 130.dp.toPx()
                val headerPx = 56.dp.toPx()
                (maxHeight.toPx() - controlsPx - headerPx)
                    .coerceAtLeast(collapsedSheetHeightPx + 80.dp.toPx())
            }
            val sheetRangePx = (expandedSheetHeightPx - collapsedSheetHeightPx).coerceAtLeast(1f)
            fun progressForHeight(heightPx: Float): Float =
                ((heightPx - collapsedSheetHeightPx) / sheetRangePx).coerceIn(0f, 1f)

            fun heightForProgress(progress: Float): Float =
                collapsedSheetHeightPx + sheetRangePx * progress.coerceIn(0f, 1f)

            val sheetHeight = remember(expandedSheetHeightPx, collapsedSheetHeightPx) {
                Animatable(heightForProgress(retainedSheetState.progress))
            }
            LaunchedEffect(expandedSheetHeightPx, collapsedSheetHeightPx) {
                sheetHeight.snapTo(heightForProgress(retainedSheetState.progress))
            }
            var isDraggingSheet by remember { mutableStateOf(false) }
            var dragSheetHeightPx by remember { mutableFloatStateOf(collapsedSheetHeightPx) }
            val displayedSheetHeightPx = if (isDraggingSheet) dragSheetHeightPx else sheetHeight.value
            val sheetProgress = progressForHeight(displayedSheetHeightPx)
            val sheetExpanded = sheetProgress > 0.35f

            fun snapSheetHeight(currentPx: Float, velocityPxPerSec: Float) {
                val progress = progressForHeight(currentPx)
                val target = when {
                    velocityPxPerSec < -250f -> expandedSheetHeightPx
                    velocityPxPerSec > 250f -> collapsedSheetHeightPx
                    progress >= 0.35f -> expandedSheetHeightPx
                    else -> collapsedSheetHeightPx
                }
                retainedSheetState.progress = progressForHeight(target)
                scope.launch {
                    sheetHeight.snapTo(currentPx)
                    isDraggingSheet = false
                    sheetHeight.animateTo(
                        target,
                        animationSpec = spring(
                            stiffness = Spring.StiffnessMedium,
                            dampingRatio = Spring.DampingRatioNoBouncy,
                        ),
                    )
                }
            }

            fun snapSheet(expanded: Boolean) {
                val target = if (expanded) expandedSheetHeightPx else collapsedSheetHeightPx
                retainedSheetState.progress = if (expanded) 1f else 0f
                scope.launch {
                    if (isDraggingSheet) {
                        sheetHeight.snapTo(dragSheetHeightPx)
                        isDraggingSheet = false
                    }
                    sheetHeight.animateTo(
                        target,
                        animationSpec = spring(
                            stiffness = Spring.StiffnessMedium,
                            dampingRatio = Spring.DampingRatioNoBouncy,
                        ),
                    )
                }
            }

            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.width(88.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(44.dp).clickable(onClick = onBack).semantics { contentDescription = "Back" },
                            contentAlignment = Alignment.Center,
                        ) {
                            PhoebeIconView(PhoebeIcon.ChevronDown, tint = PhoebeUi.primaryText, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    SectionLabel("Now Playing", PhoebeUi.secondaryText)
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.width(88.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TransportIcon(PhoebeIcon.Lyrics, "Lyrics", onLyrics)
                        CastIcon(
                            active = castState.isConnected,
                            loading = castState.isBuffering,
                            enabled = castState.isAvailable || castState.isConnected,
                            onClick = onCast,
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .pointerInput(onBack, dismissThresholdPx, offScreenPx) {
                            detectVerticalDragGestures(
                                onDragStart = { isDraggingDismiss = true },
                                onDragEnd = {
                                    isDraggingDismiss = false
                                    if (dragOffset > dismissThresholdPx) {
                                        dismissing = true
                                    } else {
                                        dragOffset = 0f
                                    }
                                },
                                onDragCancel = {
                                    isDraggingDismiss = false
                                    dragOffset = 0f
                                },
                                onVerticalDrag = { _, dragAmount ->
                                    if (!dismissing && (dragAmount > 0f || dragOffset > 0f)) {
                                        dragOffset = (dragOffset + dragAmount).coerceAtLeast(0f)
                                    }
                                },
                            )
                        },
                ) {
                    Spacer(Modifier.height(24.dp))
                    if (track != null) {
                        BoxWithConstraints(
                            Modifier
                                .weight(1f, fill = true)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(0.dp)),
                        ) {
                            val artworkSize = minOf(
                                maxWidth,
                                (maxHeight - 64.dp).coerceAtLeast(260.dp),
                            )
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .graphicsLayer {
                                        translationY = -size.height * sheetProgress
                                        alpha = 1f - sheetProgress
                                    },
                            ) {
                                Box(
                                    Modifier
                                        .size(artworkSize)
                                        .align(Alignment.Start),
                                ) {
                                    SwipeableMobileArtwork(
                                        track = track,
                                        nextTrack = upNext.firstOrNull(),
                                        previousTrack = previousTrack,
                                        onSkipQueueBy = onSkipQueueBy,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    AudioQualityBadge(
                                        track = track,
                                        onArtwork = true,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(12.dp),
                                    )
                                }
                                Spacer(Modifier.height(20.dp))
                                AutoScrollingText(track.title, color = PhoebeUi.primaryText, fontSize = 22.sp, fontWeight = FontWeight.Black)
                                AutoScrollingText(
                                    track.artist,
                                    color = PhoebeUi.secondaryText,
                                    fontSize = 15.sp,
                                    modifier = Modifier.clickable(enabled = track.artist.isNotBlank()) {
                                        trackNavigationActions.onOpenArtistForTrack(track)
                                    },
                                )
                                if (track.album.isNotBlank()) {
                                    AutoScrollingText(
                                        track.album,
                                        color = PhoebeUi.mutedText,
                                        fontSize = 13.sp,
                                        modifier = Modifier.clickable {
                                            trackNavigationActions.onOpenAlbumForTrack(track)
                                        },
                                    )
                                }
                                if (remotePlaybackTarget != null) {
                                    Text(
                                        "Music Assistant: $remotePlaybackTarget",
                                        color = PhoebeUi.accentLight,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(1f), contentAlignment = Alignment.Center) {
                            EmptyNowPlayingArtworkSlot(Modifier.fillMaxSize(), glyphSp = 64.sp)
                        }
                        Spacer(Modifier.height(20.dp))
                        Column {
                            Text("Nothing playing", color = PhoebeUi.primaryText, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Choose a song from your library or search.",
                                color = PhoebeUi.secondaryText,
                                fontSize = 15.sp,
                                lineHeight = 21.sp,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                ProgressLine(
                    positionMs = positionMs,
                    bufferedPositionMs = timelineBufferedPositionMs,
                    durationMs = track?.durationMs ?: 0L,
                    waveformSeed = track?.let(::trackWaveformSeed) ?: "",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    onSeek = if (hasTrack) onSeek else null,
                )
                Spacer(Modifier.height(22.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShuffleIcon(active = shuffle, onClick = onShuffle)
                    TransportIcon(PhoebeIcon.Previous, "Previous Track", onPrevious)
                    PlayButton(isPlaying, isBuffering, 58.dp, onToggle, enabled = hasTrack)
                    TransportIcon(PhoebeIcon.Next, "Next Track", onNext)
                    RepeatIcon(mode = repeat, onClick = onRepeat)
                }
                Spacer(Modifier.height(12.dp))

                MobileQueueSheet(
                    currentTrack = track,
                    upNext = upNext,
                    repeat = repeat,
                    sheetProgress = sheetProgress,
                    expanded = sheetExpanded,
                    isDragging = isDraggingSheet,
                    onToggleExpanded = { snapSheet(!sheetExpanded) },
                    onSheetDrag = { dragAmountPx ->
                        dragSheetHeightPx = (dragSheetHeightPx - dragAmountPx)
                            .coerceIn(collapsedSheetHeightPx, expandedSheetHeightPx)
                        retainedSheetState.progress = progressForHeight(dragSheetHeightPx)
                    },
                    onSheetDragStart = {
                        isDraggingSheet = true
                        dragSheetHeightPx = sheetHeight.value
                        scope.launch { sheetHeight.stop() }
                    },
                    onSheetDragEnd = { velocityPxPerSec ->
                        snapSheetHeight(dragSheetHeightPx, velocityPxPerSec)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { displayedSheetHeightPx.toDp() }),
                    onPlayQueue = onPlayQueue,
                    onMoveUpNext = onMoveUpNext,
                    onRemoveUpNext = onRemoveUpNext,
                    onOpenTrackDetail = onOpenSongDetail,
                    listState = upNextListState,
                )
            }
        }
    }
}

@Composable

internal fun MobileQueueSheet(
    currentTrack: Track?,
    upNext: List<Track>,
    repeat: RepeatMode,
    sheetProgress: Float,
    expanded: Boolean,
    isDragging: Boolean,
    onToggleExpanded: () -> Unit,
    onSheetDrag: (Float) -> Unit,
    onSheetDragStart: () -> Unit,
    onSheetDragEnd: (velocityPxPerSec: Float) -> Unit,
    modifier: Modifier,
    onPlayQueue: (Int) -> Unit,
    onMoveUpNext: (Int, Int) -> Unit,
    onRemoveUpNext: (Int) -> Unit,
    onOpenTrackDetail: (Track) -> Unit = {},
    listState: LazyListState = RetainedLazyListStates.remember("mobile-player-up-next-list"),
) {
    val handleWidth by animateFloatAsState(
        targetValue = when {
            isDragging -> 52f
            expanded -> 44f
            else -> 36f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "queue-sheet-handle-width",
    )
    val sheetElevation by animateFloatAsState(
        targetValue = 8f + sheetProgress * 18f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "queue-sheet-elevation",
    )
    val sheetCorner by animateFloatAsState(
        targetValue = 22f + sheetProgress * 4f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "queue-sheet-corner",
    )
    val sheetShape = RoundedCornerShape(topStart = sheetCorner.dp, topEnd = sheetCorner.dp)
    val onSheetDragUpdated = rememberUpdatedState(onSheetDrag)
    val onSheetDragStartUpdated = rememberUpdatedState(onSheetDragStart)
    val onSheetDragEndUpdated = rememberUpdatedState(onSheetDragEnd)
    val draggableState = rememberDraggableState { delta ->
        onSheetDragUpdated.value(delta)
    }

    Column(
        modifier = modifier
            .shadow(sheetElevation.dp, sheetShape, clip = false)
            .clip(sheetShape)
            .background(PhoebeUi.glass.copy(alpha = 0.94f + sheetProgress * 0.04f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Vertical,
                    onDragStarted = { onSheetDragStartUpdated.value() },
                    onDragStopped = { velocity -> onSheetDragEndUpdated.value(velocity) },
                )
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(handleWidth.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f + sheetProgress * 0.12f)),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Up Next", PhoebeUi.primaryText)
                if (repeat != RepeatMode.Off) {
                    Spacer(Modifier.width(8.dp))
                    RepeatBadge(mode = repeat)
                }
                Spacer(Modifier.weight(1f))
                val queueCount = upNext.size + if (currentTrack != null) 1 else 0
                Text(
                    when (queueCount) {
                        0 -> "Empty"
                        1 -> "1 track"
                        else -> "$queueCount tracks"
                    },
                    color = PhoebeUi.mutedText.copy(alpha = 0.75f + sheetProgress * 0.25f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleExpanded)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .semantics {
                            contentDescription = if (expanded) "Collapse Up Next" else "Expand Up Next"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    PhoebeIconView(
                        PhoebeIcon.ChevronUp,
                        tint = PhoebeUi.mutedText.copy(alpha = 0.65f + sheetProgress * 0.35f),
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = sheetProgress * 180f },
                    )
                }
            }
        }

        val showQueueContent = sheetProgress > 0.06f || isDragging
        if (showQueueContent) {
            if (currentTrack == null && upNext.isEmpty()) {
                Text(
                    "Pick a song to start a queue.",
                    color = PhoebeUi.mutedText,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 14.dp)
                        .graphicsLayer {
                            alpha = ((sheetProgress - 0.06f) / 0.2f).coerceIn(0f, 1f)
                        },
                )
            } else {
                UpNextList(
                    currentTrack = currentTrack,
                    upNext = upNext,
                    repeat = repeat,
                    onPlayQueue = onPlayQueue,
                    onMoveUpNext = onMoveUpNext,
                    onRemoveUpNext = onRemoveUpNext,
                    onOpenTrackDetail = onOpenTrackDetail,
                    listState = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 12.dp)
                        .graphicsLayer {
                            alpha = ((sheetProgress - 0.06f) / 0.2f).coerceIn(0f, 1f)
                        },
                    thumbnail = 40.dp,
                    rowHeight = 56.dp,
                )
            }
        }
    }
}
