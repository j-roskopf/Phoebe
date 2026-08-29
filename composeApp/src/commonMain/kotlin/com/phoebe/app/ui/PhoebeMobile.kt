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
import com.phoebe.app.feature.home.*
import com.phoebe.app.feature.library.LibraryFilterTab
import com.phoebe.app.feature.library.LibraryFilterOptionsMenuItems
import com.phoebe.app.feature.library.LibraryMobileRoute
import com.phoebe.app.feature.library.LibraryRouteActions
import com.phoebe.app.feature.library.LibraryRouteState
import com.phoebe.app.feature.library.LibraryViewMode
import com.phoebe.app.feature.library.PlaylistsMobileRoute
import com.phoebe.app.feature.library.PlaylistsRouteActions
import com.phoebe.app.feature.library.PlaylistsRouteState
import com.phoebe.app.feature.library.LibrarySectionIndexMode
import com.phoebe.app.feature.radio.RadioRoute
import com.phoebe.app.feature.radio.RadioRouteActions
import com.phoebe.app.feature.radio.RadioRouteMode
import com.phoebe.app.feature.radio.RadioRouteState
import com.phoebe.app.feature.search.SearchDesktopRouteActions
import com.phoebe.app.feature.search.SearchMobileRoute
import com.phoebe.app.feature.settings.DownloadManagerUiSummary
import com.phoebe.app.feature.settings.SettingsCategory
import com.phoebe.app.feature.settings.SettingsMobileRoute
import com.phoebe.app.feature.settings.SettingsRouteActions
import com.phoebe.app.feature.settings.SettingsRouteState
import com.phoebe.app.di.RouteViewModelFactory
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
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
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.blur.HazeProgressive
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.roundToInt
import com.phoebe.app.AppState
import com.phoebe.app.data.ListenBrainzFeedbackScore
import com.phoebe.app.data.ListenBrainzFeedbackTarget
import com.phoebe.app.data.catalogAlbumsForArtist
import com.phoebe.app.data.catalogTracksForArtist
import com.phoebe.app.feature.home.HomeUiState
import com.phoebe.app.feature.home.HomePosterLoadingState
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.AppSettings
import com.phoebe.app.domain.AppScreen
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.ArtistRadioAvailability
import com.phoebe.app.domain.AudioAnalysisFrame
import com.phoebe.app.domain.AudioProcessingCapabilities
import com.phoebe.app.domain.AudioProcessingSettings
import com.phoebe.app.domain.HomeSection
import com.phoebe.app.domain.JellyfinLibraryPageKind
import com.phoebe.app.domain.JellyfinSyncMode
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.DownloadItem
import com.phoebe.app.domain.DownloadPolicySettings
import com.phoebe.app.domain.StreamingPolicySettings
import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.domain.EventSettings
import com.phoebe.app.domain.LocalFolderMediaSourceConfig
import com.phoebe.app.domain.MediaSourcesState
import com.phoebe.app.domain.MobileBottomTab
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.domain.PersonalMixPreferences
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.PlexRadioStation
import com.phoebe.app.domain.RadioDirectoryState
import com.phoebe.app.domain.RadioStation
import com.phoebe.app.domain.RadioStationSearchQuery
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.defaultCollectionEntries
import com.phoebe.app.domain.canTogglePlexLike
import com.phoebe.app.feature.playback.EqualizerDialog
import com.phoebe.app.player.CastState
import com.phoebe.app.domain.isEmbyFamily
import com.phoebe.app.domain.isJellyfin
import com.phoebe.app.domain.isNavidrome
import com.phoebe.app.domain.isPlex
import com.phoebe.app.domain.isRemoteLibraryTrack
import com.phoebe.app.domain.supportsPlexPlaylists
import com.phoebe.app.platform.createPlatformHttpClient
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.platform.isDesktopPlatform
import com.phoebe.app.platform.isDebugBuild
import com.phoebe.app.platform.prefersReducedArtworkEffects
import com.phoebe.app.platform.SecureCredentialAvailability
import com.phoebe.app.updates.AppUpdateState
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
import kotlin.math.abs
import kotlin.math.max

@Composable
internal fun MobileBrowseShell(
    catalog: CatalogSnapshot,
    catalogRefreshing: Boolean,
    session: PlexSession?,
    section: BrowseSection,
    selectedPlaylistId: String?,
    searchQuery: String,
    libraryFilter: LibraryFilterTab,
    libraryUi: LibraryUiPreferences,
    currentTrack: Track?,
    homeUiState: HomeUiState,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    onNavigate: (BrowseSection) -> Unit,
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
    homePosterLoading: HomePosterLoadingState = HomePosterLoadingState(),
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
    internetRadioDirectory: RadioDirectoryState = RadioDirectoryState(),
    internetRadioRouteMode: RadioRouteMode = RadioRouteMode.Home,
    internetRadioStartingIds: Set<String> = emptySet(),
    onInternetRadioSearch: (RadioStationSearchQuery) -> Unit = {},
    onInternetRadioLoadMore: () -> Unit = {},
    onInternetRadioRefreshPopular: () -> Unit = {},
    onPlayInternetRadioStation: (RadioStation) -> Unit = {},
    onInternetRadioCountries: () -> Unit = {},
    onInternetRadioCountry: (String) -> Unit = {},
    onInternetRadioMap: () -> Unit = {},
    onInternetRadioMapSearch: (RadioStationSearchQuery, Int) -> Unit = { _, _ -> },
    onInternetRadioMapCountry: (String) -> Unit = {},
    onOpenInternetRadioStation: (RadioStation) -> Unit = onPlayInternetRadioStation,
    onInternetRadioRoot: () -> Unit = {},
    onAddManualRadioStation: (String, String) -> Unit = { _, _ -> },
    onUpdateManualRadioStation: (RadioStation, String, String) -> Unit = { _, _, _ -> },
    onDeleteManualRadioStation: (RadioStation) -> Unit = {},
    onPlayPersonalMix: () -> Unit = {},
    onPlayPopularMix: () -> Unit = {},
    onPlayTopTracksMix: () -> Unit = {},
    onArtistMixBuilder: () -> Unit = {},
    onAlbumMixBuilder: () -> Unit = {},
    showArtistAlbumMixBuilders: Boolean = session?.selectedLibrary != null ||
        catalog.artists.isNotEmpty() ||
        catalog.albums.isNotEmpty(),
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    artistRadioAvailability: Map<String, ArtistRadioAvailability> = emptyMap(),
    onProbeArtistRadio: (Artist) -> Unit = {},
    onPlayArtistRadio: (Artist) -> Unit = {},
    onOpenNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onOpenSignIn: () -> Unit = {},
    onSignOut: () -> Unit,
    onAddLocalFolder: (String?) -> Unit,
    onRefreshLibrary: () -> Unit,
    onJellyfinPage: (JellyfinLibraryPageKind, Int) -> Unit = { _, _ -> },
    onLibrarySortBy: (LibrarySortBy) -> Unit,
    onLibraryAscending: (Boolean) -> Unit,
    onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    onHomeSections: (List<HomeSection>) -> Unit,
    onMobileBottomTabs: (List<MobileBottomTab>) -> Unit = {},
    onPersonalMix: (PersonalMixPreferences) -> Unit,
    onAlbumGridItemSize: (Int) -> Unit,
    onArtistGridItemSize: (Int) -> Unit,
    onExportFavoritePlaylists: () -> Unit,
    onImportFavoritePlaylists: () -> Unit,
    onExportRadioStations: () -> Unit,
    onImportRadioStations: () -> Unit,
    onExportBackupPackage: () -> Unit = {},
    onImportBackupPackage: () -> Unit = {},
    onReplaceFromBackupPackage: () -> Unit = {},
    appSettings: AppSettings,
    homeScreenLayoutMode: HomeScreenLayoutMode = HomeScreenLayoutMode.Default,
    onCrossfadeSeconds: (Int) -> Unit,
    onScanLibraryOnLaunch: (Boolean) -> Unit,
    onNotifyWhenDownloadFinishes: (Boolean) -> Unit,
    onNotifyOnTrackChange: (Boolean) -> Unit,
    onKeepPlayingEnabled: (Boolean) -> Unit = {},
    onPersistEqualizerSettings: (Boolean) -> Unit = {},
    onAudioProcessingSettings: (AudioProcessingSettings) -> Unit = {},
    audioProcessingCapabilities: AudioProcessingCapabilities = AudioProcessingCapabilities(),
    onVisualizerPreset: (NowPlayingVisualizerPreset) -> Unit = {},
    onShowUltimateGuitarButton: (Boolean) -> Unit = {},
    onBlurredArtworkAppearance: (Boolean) -> Unit = {},
    onTintedBackgroundGradient: (Boolean) -> Unit = {},
    downloadDirectory: String?,
    downloadCount: Int,
    downloadItems: List<DownloadItem> = emptyList(),
    downloadManager: DownloadManagerUiSummary = DownloadManagerUiSummary(total = downloadCount, complete = downloadCount),
    defaultDownloadDirectoryLabel: String,
    onDownloadDirectory: (String?) -> Unit,
    onDeleteAllDownloads: () -> Unit,
    onDeleteCompletedDownloads: () -> Unit = {},
    onClearFailedDownloads: () -> Unit = {},
    onRetryFailedDownloads: () -> Unit = {},
    onRetryDownloads: (Set<String>) -> Unit = {},
    onCancelDownloads: (Set<String>) -> Unit = {},
    onDeleteDownloads: (Set<String>) -> Unit = {},
    onDownloadPolicySettings: (DownloadPolicySettings) -> Unit = {},
    onStreamingPolicySettings: (StreamingPolicySettings) -> Unit = {},
    useLightAppearance: Boolean,
    onUseLightAppearanceChange: (Boolean) -> Unit,
    appearanceDesignId: String = PhoebeDesignSystem.Default.id,
    onAppearanceDesignChange: (String) -> Unit = {},
    appearanceTintId: String,
    onAppearanceTintChange: (String) -> Unit,
    onHomeScreenLayoutModeChange: (HomeScreenLayoutMode) -> Unit = {},
    listenBrainzCredentialAvailability: SecureCredentialAvailability = SecureCredentialAvailability.Unavailable,
    onConnectListenBrainz: (String) -> Unit = {},
    onDisconnectListenBrainz: () -> Unit = {},
    onListenBrainzSubmitNowPlaying: (Boolean) -> Unit = {},
    onListenBrainzSubmitListens: (Boolean) -> Unit = {},
    onListenBrainzSubmitCurrentTrackFeedback: (Boolean) -> Unit = {},
    onStartLastFmAuthorization: (String, String) -> Unit = { _, _ -> },
    onFinishLastFmAuthorization: () -> Unit = {},
    onDisconnectLastFm: () -> Unit = {},
    onLastFmSubmitNowPlaying: (Boolean) -> Unit = {},
    onLastFmSubmitScrobbles: (Boolean) -> Unit = {},
    onEventSettings: (EventSettings) -> Unit = {},
    onOpenEventsDebugMenu: (() -> Unit)? = null,
    appUpdateState: AppUpdateState = AppUpdateState.Idle,
    onCheckForUpdates: () -> Unit = {},
    routeViewModelFactory: RouteViewModelFactory,
    onInstallUpdate: () -> Unit = {},
    initialExpandedPhoneSection: PhoneHomeAccordionSection? = null,
    homeListState: LazyListState? = null,
    showBottomChrome: Boolean = true,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var mobileLibraryViewMode by rememberSaveable { mutableStateOf(LibraryViewMode.Grid) }
    val pickLocalFolder = rememberPickLocalFolder(onPicked = onAddLocalFolder)
    val availableUpdate = when (val updateState = appUpdateState) {
        is AppUpdateState.Available -> updateState.update
        is AppUpdateState.Installing -> updateState.update
        is AppUpdateState.Failed -> updateState.lastKnownUpdate
        else -> null
    }
    val installingUpdateState = appUpdateState as? AppUpdateState.Installing
    val updateInstalling = installingUpdateState != null
    val toolbarTitle = when {
        section == BrowseSection.Settings -> "Settings"
        section == BrowseSection.Downloads -> "Downloads"
        selectedPlaylistId != null -> "Playlist"
        section == BrowseSection.Radio -> when (internetRadioRouteMode) {
            RadioRouteMode.Home -> mobileSectionTitle(section)
            RadioRouteMode.CountryIndex -> "Browse by country"
            RadioRouteMode.CountryStations -> "Country radio"
            RadioRouteMode.Map -> "Radio map"
        }
        else -> mobileSectionTitle(section)
    }
    val density = LocalDensity.current
    val navigationBottomPadding = with(density) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    val statusBarTopPadding = with(density) {
        WindowInsets.statusBars.getTop(this).toDp()
    }
    val inheritedChromePadding = LocalMobileChromePadding.current
    val chromePadding = MobileChromePadding(
        top = MobileToolbarChromeHeight + MobileChromeScrollGap + statusBarTopPadding,
        bottom = if (showBottomChrome) {
            MobileBottomNavChromeHeight +
                navigationBottomPadding +
                MobileChromeScrollGap +
                if (currentTrack != null) MobileMiniPlayerChromeHeight else 0.dp
        } else {
            inheritedChromePadding.bottom
        },
    )
    val topBarScrollsWithContent = selectedPlaylistId == null &&
        (section == BrowseSection.Home ||
            section == BrowseSection.Search ||
            section == BrowseSection.Library ||
            section == BrowseSection.Playlists ||
            section == BrowseSection.Radio)
    val browseTopBar: @Composable () -> Unit = {
        val debugEventsMenu = onOpenEventsDebugMenu.takeIf {
            isDebugBuild() && section == BrowseSection.Settings && selectedPlaylistId == null
        }
        MobileScreenToolbar(
            title = toolbarTitle,
            onBack = if (section == BrowseSection.Settings && selectedPlaylistId == null) {
                { onNavigate(BrowseSection.Home) }
            } else if (section == BrowseSection.Radio && internetRadioRouteMode != RadioRouteMode.Home) {
                onInternetRadioRoot
            } else {
                null
            },
            menuExpanded = menuExpanded,
            onMenuExpandedChange = { menuExpanded = it },
            showMenu = availableUpdate != null ||
                debugEventsMenu != null ||
                !(section == BrowseSection.Settings && selectedPlaylistId == null),
            menuTint = if (availableUpdate != null) PhoebeUpdateBlue else PhoebeUi.primaryText,
            onMenuLongClick = debugEventsMenu,
            menuContent = {
                if (section == BrowseSection.Library && selectedPlaylistId == null) {
                    DropdownMenuItem(
                        text = { Text("Library", color = PhoebeUi.mutedText, fontWeight = FontWeight.SemiBold) },
                        onClick = {},
                        enabled = false,
                    )
                    LibraryFilterOptionsMenuItems(
                        filter = libraryFilter,
                        prefs = libraryUi,
                        onSortBy = onLibrarySortBy,
                        onAscending = onLibraryAscending,
                        libraryViewMode = mobileLibraryViewMode,
                        onLibraryViewMode = { mobileLibraryViewMode = it },
                        onColumns = onLibraryColumns,
                        onDismiss = { menuExpanded = false },
                    )
                }
                if (availableUpdate != null) {
                    DropdownMenuItem(
                        text = {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (installingUpdateState != null) {
                                    UpdateProgressRing(
                                        progress = installingUpdateState.progress,
                                        modifier = Modifier.size(18.dp),
                                    )
                                } else {
                                    PhoebeIconView(PhoebeIcon.Update, tint = PhoebeUpdateBlue, modifier = Modifier.size(18.dp))
                                }
                                Text(
                                    if (installingUpdateState != null) {
                                        updateMenuProgressLabel(installingUpdateState)
                                    } else {
                                        "Update to ${availableUpdate.versionName}"
                                    },
                                )
                            }
                        },
                        onClick = {
                            if (!updateInstalling) onInstallUpdate()
                            menuExpanded = false
                        },
                        enabled = !updateInstalling,
                    )
                }
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
                            PhoebeIconView(PhoebeIcon.Download, tint = PhoebeUi.secondaryText, modifier = Modifier.size(18.dp))
                            Text("Downloads")
                        }
                    },
                    onClick = {
                        onNavigate(BrowseSection.Downloads)
                        menuExpanded = false
                    },
                )
                DropdownMenuItem(
                    text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            PhoebeIconView(PhoebeIcon.Settings, tint = PhoebeUi.secondaryText, modifier = Modifier.size(18.dp))
                            Text("Settings")
                        }
                    },
                    onClick = {
                        onNavigate(BrowseSection.Settings)
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
                if (session?.token?.isNotBlank() == true) {
                    DropdownMenuItem(
                        text = { Text("Sign out") },
                        onClick = {
                            onSignOut()
                            menuExpanded = false
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Sign in") },
                        onClick = {
                            onOpenSignIn()
                            menuExpanded = false
                        },
                    )
                }
            },
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .phoebeShellBackground(appSettings.tintedBackgroundGradient),
    ) {
        CompositionLocalProvider(LocalMobileChromePadding provides chromePadding) {
            Box(Modifier.fillMaxSize()) {
            when {
                (section == BrowseSection.Settings || section == BrowseSection.Downloads) && selectedPlaylistId == null -> SettingsMobileRoute(
                    state = SettingsRouteState(
                        isLightMode = useLightAppearance,
                        designId = appearanceDesignId,
                        tintId = appearanceTintId,
                        downloadDirectory = downloadDirectory,
                        downloadCount = downloadCount,
                        downloadItems = downloadItems,
                        downloadManager = downloadManager,
                        appSettings = appSettings,
                        audioProcessingCapabilities = audioProcessingCapabilities,
                        libraryUi = libraryUi,
                        defaultDownloadDirectoryLabel = defaultDownloadDirectoryLabel,
                        homeScreenLayoutMode = homeScreenLayoutMode,
                        session = session,
                        listenBrainzCredentialAvailability = listenBrainzCredentialAvailability,
                        appUpdateState = appUpdateState,
                        initialCategory = if (section == BrowseSection.Downloads) {
                            SettingsCategory.Downloads
                        } else {
                            SettingsCategory.Account
                        },
                    ),
                    actions = SettingsRouteActions(
                        onLightModeChange = onUseLightAppearanceChange,
                        onDesignChange = onAppearanceDesignChange,
                        onTintChange = onAppearanceTintChange,
                        onDownloadDirectory = onDownloadDirectory,
                        onDeleteAllDownloads = onDeleteAllDownloads,
                        onDeleteCompletedDownloads = onDeleteCompletedDownloads,
                        onClearFailedDownloads = onClearFailedDownloads,
                        onRetryFailedDownloads = onRetryFailedDownloads,
                        onRetryDownloads = onRetryDownloads,
                        onCancelDownloads = onCancelDownloads,
                        onDeleteDownloads = onDeleteDownloads,
                        onDownloadPolicySettings = onDownloadPolicySettings,
                        onStreamingPolicySettings = onStreamingPolicySettings,
                        onCrossfadeSeconds = onCrossfadeSeconds,
                        onScanLibraryOnLaunch = onScanLibraryOnLaunch,
                        onNotifyWhenDownloadFinishes = onNotifyWhenDownloadFinishes,
                        onNotifyOnTrackChange = onNotifyOnTrackChange,
                        onKeepPlayingEnabled = onKeepPlayingEnabled,
                        onPersistEqualizerSettings = onPersistEqualizerSettings,
                        onAudioProcessingSettings = onAudioProcessingSettings,
                        onVisualizerPreset = onVisualizerPreset,
                        onShowUltimateGuitarButton = onShowUltimateGuitarButton,
                        onBlurredArtworkAppearance = onBlurredArtworkAppearance,
                        onTintedBackgroundGradient = onTintedBackgroundGradient,
                        onHomeSections = onHomeSections,
                        onMobileBottomTabs = onMobileBottomTabs,
                        onPersonalMix = onPersonalMix,
                        onAlbumGridItemSize = onAlbumGridItemSize,
                        onArtistGridItemSize = onArtistGridItemSize,
                        onExportFavoritePlaylists = onExportFavoritePlaylists,
                        onImportFavoritePlaylists = onImportFavoritePlaylists,
                        onExportRadioStations = onExportRadioStations,
                        onImportRadioStations = onImportRadioStations,
                        onExportBackupPackage = onExportBackupPackage,
                        onImportBackupPackage = onImportBackupPackage,
                        onReplaceFromBackupPackage = onReplaceFromBackupPackage,
                        onHomeScreenLayoutModeChange = onHomeScreenLayoutModeChange,
                        onConnectListenBrainz = onConnectListenBrainz,
                        onDisconnectListenBrainz = onDisconnectListenBrainz,
                        onListenBrainzSubmitNowPlaying = onListenBrainzSubmitNowPlaying,
                        onListenBrainzSubmitListens = onListenBrainzSubmitListens,
                        onListenBrainzSubmitCurrentTrackFeedback = onListenBrainzSubmitCurrentTrackFeedback,
                        onStartLastFmAuthorization = onStartLastFmAuthorization,
                        onFinishLastFmAuthorization = onFinishLastFmAuthorization,
                        onDisconnectLastFm = onDisconnectLastFm,
                        onLastFmSubmitNowPlaying = onLastFmSubmitNowPlaying,
                        onLastFmSubmitScrobbles = onLastFmSubmitScrobbles,
                        onEventSettings = onEventSettings,
                        onCheckForUpdates = onCheckForUpdates,
                        onInstallUpdate = onInstallUpdate,
                    ),
                    modifier = Modifier.fillMaxSize().padding(top = chromePadding.top, bottom = chromePadding.bottom),
                )
                section == BrowseSection.Home && selectedPlaylistId == null -> {
                    val homeListState = homeListState ?: RetainedLazyListStates.remember("mobile-home")
                    val mobileHomeRouteState = remember(
                        homeUiState,
                        homeScreenLayoutMode,
                        catalogRefreshing,
                        libraryUi.homeSections,
                        supportedCollectionEntries,
                        homePosterLoading,
                        radioStations,
                        radioStartingIds,
                        decadeMixNotice,
                        session?.selectedLibrary,
                        session?.providerType,
                        showArtistAlbumMixBuilders,
                    ) {
                        MobileHomeRouteState(
                            homeUiState = homeUiState,
                            catalogRefreshing = catalogRefreshing,
                            homeSections = libraryUi.homeSections,
                            supportedCollectionEntries = supportedCollectionEntries,
                            posterLoading = homePosterLoading,
                            radioStations = radioStations,
                            radioStartingIds = radioStartingIds,
                            decadeMixNotice = decadeMixNotice,
                            homeScreenLayoutMode = homeScreenLayoutMode,
                            showPopularMix = session.isPlex(),
                            showArtistAlbumMixBuilders = showArtistAlbumMixBuilders,
                        )
                    }
                    val mobileHomeCallbacks = remember(
                        onArtist,
                        onAlbum,
                        onPlaylist,
                        onRecentSongs,
                        onRecentArtists,
                        onRecentAlbums,
                        onFavoritePlaylists,
                        onFavoriteArtists,
                        onFavoriteAlbums,
                        onCollections,
                        onRecentlyPlayed,
                        onMostPlayed,
                        onRefreshRandomArtists,
                        onRefreshRandomAlbums,
                        onPlayDecadeMix,
                        onClearDecadeMixNotice,
                        onPlayRadioStation,
                        onPlayPersonalMix,
                        onPlayPopularMix,
                        onPlayTopTracksMix,
                        onArtistMixBuilder,
                        onAlbumMixBuilder,
                        onPlayTracks,
                        onAddToUpNext,
                        onDownload,
                    ) {
                        MobileHomeCallbacks(
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
                            onPlayDecadeMix = onPlayDecadeMix,
                            onClearDecadeMixNotice = onClearDecadeMixNotice,
                            onPlayRadioStation = onPlayRadioStation,
                            onPlayPersonalMix = onPlayPersonalMix,
                            onPlayPopularMix = onPlayPopularMix,
                            onPlayTopTracksMix = onPlayTopTracksMix,
                            onArtistMixBuilder = onArtistMixBuilder,
                            onAlbumMixBuilder = onAlbumMixBuilder,
                            onPlayTracks = onPlayTracks,
                            onAddToUpNext = onAddToUpNext,
                            onDownload = onDownload,
                        )
                    }
                    MobileHomeRoute(
                        routeState = mobileHomeRouteState,
                        listState = homeListState,
                        callbacks = mobileHomeCallbacks,
                        modifier = Modifier.fillMaxSize(),
                        initialExpandedPhoneSection = initialExpandedPhoneSection,
                        topBar = browseTopBar,
                    )
                }
                section == BrowseSection.Library && selectedPlaylistId == null -> LibraryMobileRoute(
                    state = LibraryRouteState(
                        catalog = catalog,
                        catalogRefreshing = catalogRefreshing,
                        filter = libraryFilter,
                        libraryUi = libraryUi,
                        searchQuery = searchQuery,
                        jellyfinPagination = (session.isEmbyFamily() || session.isNavidrome()) && session?.jellyfinSyncMode == JellyfinSyncMode.Quick,
                    ),
                    actions = LibraryRouteActions(
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
                        onSearchQuery = onSearchQuery,
                    ),
                    modifier = Modifier.fillMaxSize(),
                    libraryViewMode = mobileLibraryViewMode,
                    topBar = browseTopBar,
                )
                section == BrowseSection.Search && selectedPlaylistId == null -> SearchMobileRoute(
                    viewModel = remember(routeViewModelFactory) { routeViewModelFactory.search() },
                    catalog = catalog,
                    catalogRefreshing = catalogRefreshing,
                    searchQuery = searchQuery,
                    actions = SearchDesktopRouteActions(
                        onQuery = onSearchQuery,
                        onArtist = onArtist,
                        onAlbum = onAlbum,
                        onPlayTracks = onPlayTracks,
                        onAddToUpNext = onAddToUpNext,
                        onDownload = onDownload,
                    ),
                    modifier = Modifier.fillMaxSize(),
                    loadingContent = { CatalogLoadingStrip() },
                    trackMenuContent = { track, expanded, onDismiss, onAddToUpNext, onDownload ->
                        TrackActionMenu(
                            expanded = expanded,
                            onDismiss = onDismiss,
                            onAddToUpNext = onAddToUpNext,
                            onDownload = onDownload,
                            track = track,
                        )
                    },
                    topBar = browseTopBar,
                )
                section == BrowseSection.Playlists && selectedPlaylistId == null -> PlaylistsMobileRoute(
                    state = PlaylistsRouteState(
                        catalog = catalog,
                        catalogRefreshing = catalogRefreshing,
                        searchQuery = searchQuery,
                    ),
                    actions = PlaylistsRouteActions(
                        onSearchQuery = onSearchQuery,
                        onPlaylist = onPlaylist,
                    ),
                    modifier = Modifier.fillMaxSize(),
                    topBar = browseTopBar,
                )
                section == BrowseSection.Radio && selectedPlaylistId == null -> RadioRoute(
                    state = RadioRouteState(internetRadioDirectory, internetRadioStartingIds),
                    actions = RadioRouteActions(
                        onSearch = onInternetRadioSearch,
                        onLoadMore = onInternetRadioLoadMore,
                        onRefreshPopular = onInternetRadioRefreshPopular,
                        onPlay = onPlayInternetRadioStation,
                        onAddManualStation = onAddManualRadioStation,
                        onUpdateManualStation = onUpdateManualRadioStation,
                        onDeleteManualStation = onDeleteManualRadioStation,
                        onBrowseCountries = onInternetRadioCountries,
                        onBrowseGlobe = onInternetRadioMap,
                        onGlobeSearch = onInternetRadioMapSearch,
                        onGlobeCountry = onInternetRadioMapCountry,
                        onCountry = { country -> onInternetRadioCountry(country.code) },
                        onStation = onOpenInternetRadioStation,
                        onClearCountry = onInternetRadioRoot,
                    ),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        top = statusBarTopPadding + 10.dp,
                        end = 20.dp,
                        bottom = chromePadding.bottom,
                    ),
                    sectionIndexMode = LibrarySectionIndexMode.MobileScrollbar,
                    mode = internetRadioRouteMode,
                    topBar = browseTopBar,
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
                    modifier = Modifier.fillMaxSize().padding(top = chromePadding.top, bottom = chromePadding.bottom),
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
                    radioDirectory = internetRadioDirectory,
                    internetRadioStartingIds = internetRadioStartingIds,
                    onRadioSearch = onInternetRadioSearch,
                    onRadioLoadMore = onInternetRadioLoadMore,
                    onRadioRefreshPopular = onInternetRadioRefreshPopular,
                    onRadioPlay = onPlayInternetRadioStation,
                    onRadioCountry = onInternetRadioCountry,
                    onRadioStation = onOpenInternetRadioStation,
                    onRadioRoot = onInternetRadioRoot,
                    onRadioAddManualStation = onAddManualRadioStation,
                    onRadioUpdateManualStation = onUpdateManualRadioStation,
                    onRadioDeleteManualStation = onDeleteManualRadioStation,
                    edgePadding = 20.dp,
                    headlineFontSize = 22.sp,
                    headlineLineHeight = 26.sp,
                    searchPillModifier = Modifier.fillMaxWidth(),
                )
            }
        }
        }
        if (!topBarScrollsWithContent) {
            Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .then(if (isDesktopPlatform()) Modifier else Modifier.mobileWindowTopPadding())
                .zIndex(2f),
            ) {
                browseTopBar()
            }
        }

    }
}

private fun updateMenuProgressLabel(state: AppUpdateState.Installing): String {
    val progress = state.progress
    return if (progress != null && state.message.contains("Downloading", ignoreCase = true)) {
        "Downloading ${(progress.coerceIn(0f, 1f) * 100).toInt()}%"
    } else {
        state.message
    }
}
