package com.phoebe.app.ui

import com.phoebe.app.feature.library.*
import com.phoebe.app.feature.radio.RadioRouteMode
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
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
import com.phoebe.app.PendingDuplicatePlaylistAdd
import com.phoebe.app.PlaybackSnackbarNotice
import com.phoebe.app.feature.auth.AuthWelcomeMobileRoute
import com.phoebe.app.feature.auth.AuthWelcomeRouteActions
import com.phoebe.app.feature.auth.AuthWelcomeRouteState
import com.phoebe.app.feature.auth.PlexLibraryPickerRoute
import com.phoebe.app.feature.auth.PlexLibraryPickerRouteActions
import com.phoebe.app.feature.auth.PlexLibraryPickerRouteState
import com.phoebe.app.feature.auth.PlexServerPickerRoute
import com.phoebe.app.feature.auth.PlexServerPickerRouteActions
import com.phoebe.app.feature.auth.PlexServerPickerRouteState
import com.phoebe.app.feature.collections.CollectionItemsRoute
import com.phoebe.app.feature.collections.CollectionItemsRouteActions
import com.phoebe.app.feature.collections.CollectionItemsRouteState
import com.phoebe.app.feature.collections.CollectionsRoute
import com.phoebe.app.feature.collections.CollectionsRouteActions
import com.phoebe.app.feature.collections.CollectionsRouteState
import com.phoebe.app.feature.details.AlbumDetailRoute
import com.phoebe.app.feature.details.AlbumDetailRouteActions
import com.phoebe.app.feature.details.AlbumDetailRouteState
import com.phoebe.app.feature.details.ArtistDetailRoute
import com.phoebe.app.feature.details.ArtistDetailRouteActions
import com.phoebe.app.feature.details.ArtistDetailRouteState
import com.phoebe.app.feature.details.PlaylistDetailRoute
import com.phoebe.app.feature.details.PlaylistDetailRouteActions
import com.phoebe.app.feature.details.PlaylistDetailRouteState
import com.phoebe.app.feature.details.SongDetailRoute
import com.phoebe.app.feature.details.SongDetailRouteActions
import com.phoebe.app.feature.details.SongDetailRouteState
import com.phoebe.app.feature.favorites.FavoriteAlbumsMobileRoute
import com.phoebe.app.feature.favorites.FavoriteAlbumsRouteActions
import com.phoebe.app.feature.favorites.FavoriteAlbumsRouteState
import com.phoebe.app.feature.favorites.FavoriteArtistsMobileRoute
import com.phoebe.app.feature.favorites.FavoriteArtistsRouteActions
import com.phoebe.app.feature.favorites.FavoriteArtistsRouteState
import com.phoebe.app.feature.favorites.FavoritePlaylistsMobileRoute
import com.phoebe.app.feature.favorites.FavoritePlaylistsRouteActions
import com.phoebe.app.feature.favorites.FavoritePlaylistsRouteState
import com.phoebe.app.feature.history.HistoryNowPlayingState
import com.phoebe.app.feature.history.PlayHistoryRoute
import com.phoebe.app.feature.history.PlayHistoryRouteState
import com.phoebe.app.feature.home.HomeUiState
import com.phoebe.app.feature.home.HomePosterLoadingState
import com.phoebe.app.feature.home.RecentlyAddedNowPlayingState
import com.phoebe.app.feature.home.RecentlyAddedRoute
import com.phoebe.app.feature.home.RecentlyAddedRouteActions
import com.phoebe.app.feature.home.RecentlyAddedRouteState
import com.phoebe.app.feature.home.RecentlyAddedWindowMs
import com.phoebe.app.feature.home.personalMix
import com.phoebe.app.feature.home.personalMixIdentityKey
import com.phoebe.app.feature.home.rememberHomeFeatureState
import com.phoebe.app.feature.lyrics.LyricsRoute
import com.phoebe.app.feature.lyrics.LyricsRouteState
import com.phoebe.app.feature.playback.MobilePlaybackRoute
import com.phoebe.app.feature.playback.MobilePlaybackRouteActions
import com.phoebe.app.feature.playback.MobilePlaybackRouteState
import com.phoebe.app.feature.search.LocalSearchHistory
import com.phoebe.app.feature.search.LocalSavedSearchActions
import com.phoebe.app.feature.search.SavedSearchActions
import com.phoebe.app.feature.search.rememberSearchHistoryState
import com.phoebe.app.feature.settings.DownloadManagerUiSummary
import com.phoebe.app.feature.settings.SettingsCategory
import com.phoebe.app.domain.ShellPlaybackState
import com.phoebe.app.data.catalogAlbumsForArtist
import com.phoebe.app.data.catalogTracksForArtist
import com.phoebe.app.data.BackupRestoreMode
import com.phoebe.app.data.PlayHistoryRankedEntries
import com.phoebe.app.data.PlayHistorySnapshot
import com.phoebe.app.data.trackIndexKey
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.AppScreen
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.AudioAnalysisFrame
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.CollectionTarget
import com.phoebe.app.domain.DownloadItem
import com.phoebe.app.domain.DownloadState
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.LyricsLoadState
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.LocalFolderMediaSourceConfig
import com.phoebe.app.domain.MediaSourcesState
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.PlayHistoryKind
import com.phoebe.app.domain.RadioNowPlayingMetadata
import com.phoebe.app.domain.RadioStation
import com.phoebe.app.domain.RadioStationSearchQuery
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.RecentlyAddedKind
import com.phoebe.app.domain.supportedCollectionEntries
import com.phoebe.app.domain.supportsCollectionEntry
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.belongsToProvider
import com.phoebe.app.domain.canAddToLocalPlaylist
import com.phoebe.app.domain.canAddToPlexPlaylist
import com.phoebe.app.domain.isEmbyFamily
import com.phoebe.app.domain.isLocalMediaPlayback
import com.phoebe.app.domain.isJellyfin
import com.phoebe.app.domain.isLocalPlaylist
import com.phoebe.app.domain.isLikedSongsPlaylist
import com.phoebe.app.domain.isRemoteProviderPlaylist
import com.phoebe.app.domain.isRemoteLibraryTrack
import com.phoebe.app.domain.supportsPlexPlaylists
import com.phoebe.app.domain.supportsPlexRatings
import com.phoebe.app.domain.supportsRemotePlaylists
import com.phoebe.app.domain.supportsRemoteRatings
import com.phoebe.app.domain.telemetryName
import com.phoebe.app.player.CastState
import com.phoebe.app.platform.createPlatformHttpClient
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.platform.isDesktopPlatform
import com.phoebe.app.platform.prefersReducedArtworkEffects
import com.phoebe.app.platform.supportsPredictiveBack
import com.phoebe.app.telemetry.Telemetry
import com.phoebe.app.updates.AvailableUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import kotlin.math.min

private data class PendingMobilePlaybackPreview(
    val tracks: List<Track>,
    val index: Int,
) {
    val currentTrack: Track?
        get() = tracks.getOrNull(index)

    val previousTrack: Track?
        get() = tracks.getOrNull(index - 1)

    val upNext: List<Track>
        get() = if (index + 1 <= tracks.lastIndex) tracks.drop(index + 1) else emptyList()
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhoebeRoot(
    state: AppState,
    useLightAppearance: Boolean,
    onUseLightAppearanceChange: (Boolean) -> Unit,
    appearanceTintId: String,
    onAppearanceTintChange: (String) -> Unit,
    homeScreenLayoutMode: HomeScreenLayoutMode = HomeScreenLayoutMode.Default,
    onHomeScreenLayoutModeChange: (HomeScreenLayoutMode) -> Unit = {},
    navigationPath: String? = null,
    onNavigationPathChange: ((path: String, replace: Boolean) -> Unit)? = null,
) {
    PhoebeRootStateHolder(
        state = state,
        useLightAppearance = useLightAppearance,
        onUseLightAppearanceChange = onUseLightAppearanceChange,
        appearanceTintId = appearanceTintId,
        onAppearanceTintChange = onAppearanceTintChange,
        homeScreenLayoutMode = homeScreenLayoutMode,
        onHomeScreenLayoutModeChange = onHomeScreenLayoutModeChange,
        navigationPath = navigationPath,
        onNavigationPathChange = onNavigationPathChange,
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PhoebeRootStateHolder(
    state: AppState,
    useLightAppearance: Boolean,
    onUseLightAppearanceChange: (Boolean) -> Unit,
    appearanceTintId: String,
    onAppearanceTintChange: (String) -> Unit,
    homeScreenLayoutMode: HomeScreenLayoutMode,
    onHomeScreenLayoutModeChange: (HomeScreenLayoutMode) -> Unit,
    navigationPath: String?,
    onNavigationPathChange: ((path: String, replace: Boolean) -> Unit)?,
) {
    val session by state.session.collectAsState()
    val mediaSources by state.mediaSources.collectAsState()
    val initialNavigationRequest = remember(state, session, mediaSources) {
        state.initialNavigationRequest()
    }
    val browseFallbackRoutes = remember(initialNavigationRequest) {
        listOf(initialNavigationRequest.toPhoebeRoute())
    }
    val suppressedInitialNavigationRequest = remember(initialNavigationRequest, navigationPath) {
        initialNavigationRequest.takeIf { navigationPath != null }
    }
    val initialRoutes = remember(navigationPath, browseFallbackRoutes) {
        navigationPath
            ?.let(::phoebeWebRoutesForPath)
            ?.withUnavailableBrowseFallback(browseFallbackRoutes)
            ?: browseFallbackRoutes
    }
    val navigator = rememberPhoebeNavigator(initialRoutes)
    val catalog by state.catalog.collectAsState()
    val catalogWorkActive by state.catalogRefreshing.collectAsState()
    val catalogSyncState by state.catalogSyncState.collectAsState()
    val tracksLoading by state.tracksLoading.collectAsState()
    val supportedCollectionEntries = remember(session) { session.supportedCollectionEntries().toSet() }
    val shellPlayback by state.shellPlayback.collectAsState()
    val playerTransport by state.playerTransport.collectAsState()
    val playerQueue by state.playerQueue.collectAsState()
    val musicAssistantRemotePlayback by state.musicAssistantRemotePlayback.collectAsState()
    val cast by state.cast.collectAsState()
    val busy by state.busy.collectAsState()
    val authInProgress by state.authInProgress.collectAsState()
    val serversLoading by state.serversLoading.collectAsState()
    val librariesLoading by state.librariesLoading.collectAsState()
    val message by state.message.collectAsState()
    val pendingDuplicatePlaylistAdd by state.pendingDuplicatePlaylistAdd.collectAsState()
    val playbackSnackbar by state.playbackSnackbar.collectAsState()
    val appUpdateState by state.appUpdateState.collectAsState()
    val pendingUpdateInstallConfirmation by state.pendingUpdateInstallConfirmation.collectAsState()
    val decadeMixNotice by state.decadeMixNotice.collectAsState()
    val radioStations by state.radioStations.collectAsState()
    val radioDirectory by state.radioDirectory.collectAsState()
    val radioStartingIds by state.radioStartingIds.collectAsState()
    val internetRadioStartingIds by state.internetRadioStartingIds.collectAsState()
    val artistRadioAvailability by state.artistRadioAvailability.collectAsState()
    val downloadDirectory by state.downloadDirectory.collectAsState()
    val pin by state.pin.collectAsState()
    val servers by state.servers.collectAsState()
    val jellyfinServers by state.jellyfinServers.collectAsState()
    val jellyfinDiscoveryLoading by state.jellyfinDiscoveryLoading.collectAsState()
    val jellyfinQuickConnect by state.jellyfinQuickConnect.collectAsState()
    val libraries by state.libraries.collectAsState()
    val libraryUi by state.libraryUi.collectAsState()
    val appSettings by state.appSettings.collectAsState()
    val smartPlaylists by state.smartPlaylists.collectAsState()
    val savedSearches by state.savedSearches.collectAsState()
    val listenBrainzFeedbackTarget by state.listenBrainzFeedbackTarget.collectAsState()
    val radioNowPlaying by state.radioNowPlaying.collectAsState()
    val equalizerProfile by state.equalizerProfile.collectAsState()
    val equalizerRemoteUnavailable by state.equalizerRemoteUnavailable.collectAsState()
    val lastPlayedByArtist by state.lastPlayedByArtist.collectAsState()
    val lastPlayedByAlbum by state.lastPlayedByAlbum.collectAsState()
    val lastPlayedByTrack by state.lastPlayedByTrack.collectAsState()
    val playCountsByTrack by state.playCountsByTrack.collectAsState()
    val playEventsByTrack by state.playEventsByTrack.collectAsState()
    val topMostPlayed by state.topMostPlayed.collectAsState()
    val topRecentlyPlayed by state.topRecentlyPlayed.collectAsState()
    val upNext = playerQueue.upNext
    val currentTrack = remember(shellPlayback.currentTrack, radioNowPlaying) {
        shellPlayback.currentTrack.withRadioNowPlaying(radioNowPlaying)
    }
    val effectiveInternetRadioStartingIds = remember(internetRadioStartingIds, shellPlayback.currentTrack?.id, shellPlayback.isBuffering) {
        val bufferingRadioId = shellPlayback.currentTrack
            ?.id
            ?.takeIf { shellPlayback.isBuffering && it.startsWith("radio:") }
            ?.removePrefix("radio:")
        if (bufferingRadioId == null) {
            internetRadioStartingIds
        } else {
            internetRadioStartingIds + bufferingRadioId
        }
    }
    val currentIndex = playerQueue.currentIndex.takeIf { it >= 0 } ?: 0
    var suppressInitialNavigationRequest by remember(state, navigationPath) {
        mutableStateOf(navigationPath != null)
    }
    LaunchedEffect(state, navigator, suppressedInitialNavigationRequest) {
        state.navigationRequests.collect { request ->
            if (suppressInitialNavigationRequest) {
                suppressInitialNavigationRequest = false
                if (request == suppressedInitialNavigationRequest) return@collect
            }
            navigator.handle(request)
        }
    }
    val currentRoute = navigator.currentRoute
    val routeResolution = remember(currentRoute, catalog, currentTrack) {
        resolvePhoebeRoute(currentRoute, catalog, currentTrack)
    }
    val missingRoute = routeResolution as? PhoebeRouteResolution.Missing
    val screen = (routeResolution as? PhoebeRouteResolution.Resolved)?.screen ?: AppScreen.Home
    val currentRoutes = navigator.routes
    val browseSection = currentRoutes.filterIsInstance<PhoebeRoute.Browse>().lastOrNull()?.section ?: BrowseSection.Home
    val radioPlayingFromSignIn = currentTrack?.id?.startsWith("radio:") == true &&
        currentRoutes.firstOrNull() == PhoebeRoute.SignIn
    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    var replaceNextNavigationPath by remember { mutableStateOf(navigationPath != null) }
    var lastPublishedNavigationRoute by remember { mutableStateOf<PhoebeRoute?>(null) }
    var pendingPublishedNavigationPath by remember { mutableStateOf<String?>(null) }
    var suppressNextRadioStationRouteEffect by remember { mutableStateOf(false) }
    fun ensureRadioPlaybackBackStack() {
        if (!radioPlayingFromSignIn) return
        selectedPlaylistId = null
        replaceNextNavigationPath = true
        navigator.replaceAll(
            listOf(PhoebeRoute.Browse(BrowseSection.Radio)),
        )
    }
    LaunchedEffect(currentRoutes, browseFallbackRoutes) {
        val guardedRoutes = currentRoutes.withUnavailableBrowseFallback(browseFallbackRoutes)
        if (currentRoutes != guardedRoutes) {
            selectedPlaylistId = null
            replaceNextNavigationPath = true
            navigator.replaceAll(guardedRoutes)
        }
    }
    val canonicalNavigationPath = remember(currentRoute, routeResolution) {
        currentRoute.toPhoebeWebPath(routeResolution)
    }
    val currentOnNavigationPathChange by rememberUpdatedState(onNavigationPathChange)
    LaunchedEffect(canonicalNavigationPath, currentRoute) {
        val onPathChange = currentOnNavigationPathChange ?: return@LaunchedEffect
        val routeChanged = lastPublishedNavigationRoute != null &&
            lastPublishedNavigationRoute != currentRoute
        val replace = replaceNextNavigationPath || !routeChanged
        replaceNextNavigationPath = false
        lastPublishedNavigationRoute = currentRoute
        if (navigationPath != null && navigationPath != canonicalNavigationPath) {
            pendingPublishedNavigationPath = canonicalNavigationPath
        } else if (pendingPublishedNavigationPath == canonicalNavigationPath) {
            pendingPublishedNavigationPath = null
        }
        onPathChange(canonicalNavigationPath, replace)
    }
    LaunchedEffect(navigationPath, browseFallbackRoutes) {
        val path = navigationPath ?: return@LaunchedEffect
        val pendingPath = pendingPublishedNavigationPath
        if (pendingPath != null) {
            if (path == pendingPath) {
                pendingPublishedNavigationPath = null
            }
            return@LaunchedEffect
        }
        val parsedRoutes = phoebeWebRoutesForPath(path)
            .withUnavailableBrowseFallback(browseFallbackRoutes)
        if (currentTrack?.id?.startsWith("radio:") == true &&
            parsedRoutes == listOf(PhoebeRoute.SignIn) &&
            navigator.routes.firstOrNull() is PhoebeRoute.Browse
        ) {
            return@LaunchedEffect
        }
        if (navigator.routes != parsedRoutes) {
            selectedPlaylistId = null
            replaceNextNavigationPath = true
            navigator.replaceAll(parsedRoutes)
        }
    }
    LaunchedEffect(currentRoute, screen) {
        when {
            currentRoute is PhoebeRoute.PlaylistDetail -> selectedPlaylistId = currentRoute.playlistId
            screen is AppScreen.PlaylistDetail -> selectedPlaylistId = screen.playlist.id
        }
    }
    val collapseMobilePlayer: () -> Unit = {
        if (!navigator.pop()) {
            navigator.replaceRoot(PhoebeRoute.Browse(BrowseSection.Home))
        }
    }
    val contentRoute = currentRoutes.lastOrNull { route -> route != PhoebeRoute.Player } ?: currentRoute
    val radioRouteMode = when (contentRoute) {
        PhoebeRoute.RadioCountries -> RadioRouteMode.CountryIndex
        is PhoebeRoute.RadioCountry -> RadioRouteMode.CountryStations
        PhoebeRoute.RadioMap -> RadioRouteMode.Map
        else -> RadioRouteMode.Home
    }
    val openRadioRoot: () -> Unit = {
        state.searchInternetRadio(RadioStationSearchQuery())
        if (navigator.currentRoute is PhoebeRoute.RadioCountries ||
            navigator.currentRoute is PhoebeRoute.RadioCountry ||
            navigator.currentRoute is PhoebeRoute.RadioStation ||
            navigator.currentRoute is PhoebeRoute.RadioMap
        ) {
            navigator.pop()
        } else {
            navigator.replaceAll(listOf(PhoebeRoute.Browse(BrowseSection.Radio)))
        }
    }
    val openInternetRadioCountries: () -> Unit = {
        state.searchInternetRadio(RadioStationSearchQuery())
        navigator.replaceAll(
            listOf(
                PhoebeRoute.Browse(BrowseSection.Radio),
                PhoebeRoute.RadioCountries,
            ),
        )
    }
    val openInternetRadioCountry: (String) -> Unit = { countryCode ->
        navigator.replaceAll(
            listOf(
                PhoebeRoute.Browse(BrowseSection.Radio),
                PhoebeRoute.RadioCountry(countryCode),
            ),
        )
    }
    val openInternetRadioMap: () -> Unit = {
        navigator.replaceAll(
            listOf(
                PhoebeRoute.Browse(BrowseSection.Radio),
                PhoebeRoute.RadioMap,
            ),
        )
    }
    val openInternetRadioStation: (RadioStation) -> Unit = { station ->
        suppressNextRadioStationRouteEffect = true
        state.playInternetRadioStation(station)
        navigator.replaceAll(
            listOf(
                PhoebeRoute.Browse(BrowseSection.Radio),
                PhoebeRoute.RadioStation(station.id),
            ),
        )
    }
    val exitPlaylistDetail: () -> Unit = {
        selectedPlaylistId = null
        navigator.pop()
    }
    val radioCountryRouteVisible = screen == AppScreen.Home &&
        browseSection == BrowseSection.Radio &&
        selectedPlaylistId == null &&
        (currentRoute is PhoebeRoute.RadioCountries || currentRoute is PhoebeRoute.RadioCountry || currentRoute is PhoebeRoute.RadioMap)
    PlatformBackHandler(
        enabled = radioCountryRouteVisible,
        onBack = openRadioRoot,
    )
    val canHandleBrowseBack = screen == AppScreen.Home &&
        (selectedPlaylistId != null || browseSection != BrowseSection.Home)
    PlatformBackHandler(
        enabled = canHandleBrowseBack && !radioCountryRouteVisible,
        onBack = {
            when {
                screen == AppScreen.Home && selectedPlaylistId != null -> {
                    selectedPlaylistId = null
                }
                screen == AppScreen.Home && browseSection != BrowseSection.Home -> {
                    navigator.openBrowse(BrowseSection.Home)
                }
            }
        },
    )
    var searchQuery by remember { mutableStateOf("") }
    val searchScopeKey = when (val currentScreen = screen) {
        is AppScreen.ArtistDetail -> "artist:${currentScreen.artist.id}"
        is AppScreen.AlbumDetail -> "album:${currentScreen.album.id}"
        is AppScreen.SongDetail -> "song:${currentScreen.track.id}"
        is AppScreen.Lyrics -> "lyrics:${currentScreen.track?.id.orEmpty()}"
        is AppScreen.Collections -> "collections:${currentScreen.entry}"
        is AppScreen.CollectionItems -> "collection-items:${currentScreen.entry}:${currentScreen.value}"
        is AppScreen.RecentlyAdded -> "recently-added:${currentScreen.kind}"
        is AppScreen.PlayHistory -> "play-history:${currentScreen.kind}"
        AppScreen.FavoritePlaylists -> "favorite-playlists"
        AppScreen.FavoriteArtists -> "favorite-artists"
        AppScreen.FavoriteAlbums -> "favorite-albums"
        is AppScreen.PlaylistDetail -> "playlist:${currentScreen.playlist.id}"
        else -> "browse:$browseSection:${selectedPlaylistId.orEmpty()}"
    }
    LaunchedEffect(searchScopeKey) {
        searchQuery = ""
    }
    val recentSearchItems by state.recentSearchItems.collectAsState()
    val downloads by state.downloads.collectAsState()
    val activeDownloadJobCount by state.activeDownloadJobCount.collectAsState()
    var libraryFilter by remember { mutableStateOf(LibraryFilterTab.Artists) }

    LaunchedEffect(currentRoute) {
        Telemetry.trackScreen(currentRoute.telemetryName)
    }
    LaunchedEffect(currentRoute, screen) {
        when (screen) {
            is AppScreen.ArtistDetail -> state.preloadArtistDetail(screen.artist)
            is AppScreen.AlbumDetail -> state.preloadAlbumDetail(screen.album)
            is AppScreen.PlaylistDetail -> state.preloadPlaylistDetail(screen.playlist)
            is AppScreen.Collections -> state.preloadCollections(screen.entry)
            is AppScreen.CollectionItems -> state.preloadCollectionItems(screen.entry, screen.value)
            else -> Unit
        }
    }
    LaunchedEffect(currentRoute) {
        if (currentRoute !is PhoebeRoute.RadioStation) {
            suppressNextRadioStationRouteEffect = false
        }
        when (currentRoute) {
            is PhoebeRoute.Browse -> {
                if (currentRoute.section == BrowseSection.Radio && radioDirectory.searchQuery.countryCode.isNotBlank()) {
                    state.searchInternetRadio(RadioStationSearchQuery())
                }
            }
            PhoebeRoute.RadioCountries -> {
                if (!radioDirectory.searchQuery.isBlank) {
                    state.searchInternetRadio(RadioStationSearchQuery())
                } else {
                    state.refreshInternetRadio()
                }
            }
            PhoebeRoute.RadioMap -> state.loadInternetRadioMap()
            is PhoebeRoute.RadioCountry -> state.browseInternetRadioCountry(currentRoute.countryCode)
            is PhoebeRoute.RadioStation -> {
                if (suppressNextRadioStationRouteEffect) {
                    suppressNextRadioStationRouteEffect = false
                } else {
                    state.showInternetRadioStation(currentRoute.stationId)
                    state.playInternetRadioStation(currentRoute.stationId)
                }
            }
            else -> Unit
        }
    }
    var lyricsRefreshNonce by remember { mutableStateOf(0) }
    var lyricsRefreshTrackId by remember { mutableStateOf<String?>(null) }
    val lyricsTrack = when (val currentScreen = screen) {
        is AppScreen.Lyrics -> currentScreen.track ?: currentTrack
        AppScreen.Home -> if (browseSection == BrowseSection.Lyrics) currentTrack else null
        else -> null
    }
    val lyricsState by produceState<LyricsLoadState>(
        initialValue = if (lyricsTrack == null) LyricsLoadState.Idle else LyricsLoadState.Loading,
        lyricsTrack?.id,
        lyricsRefreshNonce,
    ) {
        val target = lyricsTrack
        if (target == null) {
            value = LyricsLoadState.Idle
        } else {
            value = LyricsLoadState.Loading
            value = state.loadLyrics(
                target,
                forceRefresh = lyricsRefreshNonce > 0 && lyricsRefreshTrackId == target.id,
            )
        }
    }
    val retryLyrics = {
        lyricsRefreshTrackId = lyricsTrack?.id
        lyricsRefreshNonce++
        Unit
    }
    val catalogHasContent = catalog.artists.isNotEmpty() ||
        catalog.albums.isNotEmpty() ||
        catalog.playlists.isNotEmpty()
    val activeCatalogSurfaceHasContent = remember(catalog, screen, browseSection, selectedPlaylistId, libraryFilter) {
        catalogHasContentForSurface(
            catalog = catalog,
            screen = screen,
            browseSection = browseSection,
            selectedPlaylistId = selectedPlaylistId,
            libraryFilter = libraryFilter,
        )
    }
    val catalogSyncInProgress = catalogWorkActive || catalogSyncState.isActive
    val catalogRefreshing = catalogSyncState.showGlobalProgress ||
        (catalogSyncInProgress && !activeCatalogSurfaceHasContent)
    val downloadManagerSummary = remember(downloads, activeDownloadJobCount) {
        downloads.toDownloadManagerUiSummary(activeDownloadJobCount)
    }
    val trackHeavySectionsEnabled by produceState(false, catalogSyncInProgress) {
        if (catalogSyncInProgress) {
            value = false
        } else {
            delay(120L)
            value = true
        }
    }
    val nowPlaying = remember(currentTrack?.id, shellPlayback.isPlaying, shellPlayback.isBuffering) {
        NowPlayingIndicatorState(
            trackId = currentTrack?.id,
            isPlaying = shellPlayback.isPlaying,
            isBuffering = shellPlayback.isBuffering,
        )
    }
    val downloadStatus = remember { DownloadStatusSnapshot() }
    LaunchedEffect(state, downloadStatus) {
        launch {
            state.downloads.collect { downloads ->
                downloadStatus.replaceItems(downloads)
            }
        }
        launch {
            state.downloadEvents.collect { event ->
                downloadStatus.apply(event)
            }
        }
        launch {
            state.activeDownloadJobCount.collect { activeDownloadJobCount ->
                val active = activeDownloadJobCount > 0
                downloadStatus.setActiveDownloadJobs(active)
                RemoteArtworkCache.configureDownloadMemoryMode(active)
            }
        }
    }
    val playHistory = remember(
        lastPlayedByArtist,
        lastPlayedByAlbum,
        lastPlayedByTrack,
        playCountsByTrack,
        playEventsByTrack,
        topMostPlayed,
        topRecentlyPlayed,
    ) {
        PlayHistorySnapshot(
            byArtist = lastPlayedByArtist,
            byAlbum = lastPlayedByAlbum,
            byTrack = lastPlayedByTrack,
            playCountByTrack = playCountsByTrack,
            playEventsByTrack = playEventsByTrack,
            topMostPlayed = topMostPlayed,
            topRecentlyPlayed = topRecentlyPlayed,
        )
    }
    // Re-tick "now" every minute so relative timestamps in the library refresh
    // without requiring an unrelated recomposition. We also re-read the clock
    // immediately whenever the play history changes — without that nudge, a
    // brand-new play whose timestamp is newer than our cached `nowMs` would
    // briefly render as "Just now"… but only after the next 60s tick caught up.
    var nowMs by remember { mutableStateOf(currentTimeMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowMs = currentTimeMs()
        }
    }
    LaunchedEffect(lastPlayedByTrack) {
        nowMs = currentTimeMs()
    }
    val homeFeatureState = rememberHomeFeatureState(
        catalog = catalog,
        playHistory = playHistory,
        catalogSyncInProgress = catalogSyncInProgress,
        trackHeavySectionsEnabled = trackHeavySectionsEnabled,
        nowMs = nowMs,
        resolveTracksByIds = state::resolveTracksByIds,
    )
    val homeUiState = homeFeatureState.uiState
    val mostPlayedResolving = homeFeatureState.mostPlayedResolving
    val catalogHomeMetadataKey = homeFeatureState.catalogHomeMetadataKey
    val catalogTrackIndexKey = homeFeatureState.catalogTrackIndexKey
    val resolvedTracksById = homeFeatureState.resolvedTracksById
    val loadPlayHistoryEntries: suspend (PlayHistoryKind, Int) -> PlayHistoryRankedEntries = remember(state) {
        { kind: PlayHistoryKind, limit: Int -> state.queryPlayHistoryEntries(kind, limit) }
    }
    val playTracks: (List<Track>, Int) -> Unit = { tracks, index ->
        state.playTracks(
            tracks = tracks,
            index = index,
            collectionMixSeed = navigator.routes.collectionMixSeed(),
        )
    }
    val playAllTracks: (List<Track>) -> Unit = { tracks ->
        state.playTracks(
            tracks = tracks,
            index = 0,
            collectionMixSeed = navigator.routes.collectionMixSeed(),
            clearShuffle = true,
        )
    }
    val shuffleAllTracks: (List<Track>) -> Unit = { tracks ->
        state.playTracks(
            tracks = tracks.shuffled(),
            index = 0,
            collectionMixSeed = navigator.routes.collectionMixSeed(),
            shuffleEnabled = true,
        )
    }
    val mobilePlaybackScope = rememberCoroutineScope()
    var pendingMobilePlaybackJob by remember { mutableStateOf<Job?>(null) }
    var pendingMobilePlaybackPreview by remember { mutableStateOf<PendingMobilePlaybackPreview?>(null) }
    LaunchedEffect(currentTrack?.id, pendingMobilePlaybackPreview?.currentTrack?.id) {
        val preview = pendingMobilePlaybackPreview ?: return@LaunchedEffect
        if (preview.currentTrack?.id == currentTrack?.id) {
            pendingMobilePlaybackPreview = null
        }
    }
    fun requestMobilePlayback(
        tracks: List<Track>,
        index: Int,
        shuffleEnabled: Boolean = false,
        clearShuffle: Boolean = false,
    ) {
        if (tracks.isEmpty()) return
        val collectionMixSeed = navigator.routes.collectionMixSeed()
        val previewIndex = index.coerceIn(0, tracks.lastIndex)
        pendingMobilePlaybackJob?.cancel()
        pendingMobilePlaybackJob = null
        val playbackAccepted = state.playTracks(
            tracks = tracks,
            index = index,
            collectionMixSeed = collectionMixSeed,
            shuffleEnabled = shuffleEnabled,
            clearShuffle = clearShuffle,
        )
        if (!playbackAccepted) {
            pendingMobilePlaybackPreview = null
            return
        }
        pendingMobilePlaybackPreview = PendingMobilePlaybackPreview(tracks, previewIndex)
        navigator.openPlayer()
        pendingMobilePlaybackJob = mobilePlaybackScope.launch {
            delay(1_500L)
            if (pendingMobilePlaybackPreview?.currentTrack?.id == tracks.getOrNull(previewIndex)?.id) {
                pendingMobilePlaybackPreview = null
            }
        }
    }
    val playTracksFromMobile: (List<Track>, Int) -> Unit = { tracks, index ->
        requestMobilePlayback(tracks, index)
    }
    val playAllTracksFromMobile: (List<Track>) -> Unit = { tracks ->
        requestMobilePlayback(tracks, 0, clearShuffle = true)
    }
    val shuffleAllTracksFromMobile: (List<Track>) -> Unit = { tracks ->
        requestMobilePlayback(tracks.shuffled(), 0, shuffleEnabled = true)
    }
    fun openMobilePlayer() {
        ensureRadioPlaybackBackStack()
        navigator.openPlayer()
    }
    val mobilePlayerTrack = pendingMobilePlaybackPreview?.currentTrack ?: currentTrack
    val mobilePlayerUpNext = pendingMobilePlaybackPreview?.upNext ?: upNext
    val mobilePlayerPreviousTrack = pendingMobilePlaybackPreview?.previousTrack
        ?: playerQueue.queue.getOrNull(currentIndex - 1)
    val mobilePlayerCurrentIndex = pendingMobilePlaybackPreview?.index ?: currentIndex
    val pendingMobilePlaybackTrackId = pendingMobilePlaybackPreview?.currentTrack?.id
    val mobilePlaybackStarting = pendingMobilePlaybackTrackId != null &&
        pendingMobilePlaybackTrackId != currentTrack?.id
    var homePosterLoading by remember { mutableStateOf(HomePosterLoadingState()) }
    val personalMixCatalog = rememberUpdatedState(catalog)
    val personalMixHomeUiState = rememberUpdatedState(homeUiState)
    val personalMixPreferences = rememberUpdatedState(libraryUi.personalMix)
    val personalMixPlayHistory = rememberUpdatedState(playHistory)
    var recentPersonalMixKeys by remember { mutableStateOf(emptySet<String>()) }
    val homePosterActionScope = rememberCoroutineScope()
    val playPersonalMix = remember(state, homePosterActionScope) {
        {
            homePosterActionScope.launch {
                homePosterLoading = homePosterLoading.copy(personalMix = true)
                val loadingStartedAtMs = currentTimeMs()
                try {
                    val preferences = personalMixPreferences.value.normalized()
                    state.ensurePersonalMixTracks(preferences.limit)
                    val tracks = personalMix(
                        catalog = personalMixCatalog.value,
                        state = personalMixHomeUiState.value,
                        preferences = preferences,
                        playHistory = personalMixPlayHistory.value,
                        recentMixTrackKeys = recentPersonalMixKeys,
                    )
                    if (tracks.isNotEmpty()) {
                        recentPersonalMixKeys = (recentPersonalMixKeys + tracks.map { it.personalMixIdentityKey() })
                            .let { keys -> if (keys.size > 100) keys.drop(keys.size - 100).toSet() else keys.toSet() }
                        playTracksFromMobile(tracks, 0)
                    }
                } finally {
                    val remainingLoadingMs = HomePosterLoadingMinDurationMs - (currentTimeMs() - loadingStartedAtMs)
                    if (remainingLoadingMs > 0L) {
                        delay(remainingLoadingMs)
                    }
                    homePosterLoading = homePosterLoading.copy(personalMix = false)
                }
            }
            Unit
        }
    }
    val playPopularMix = remember(state, homePosterActionScope) {
        {
            homePosterActionScope.launch {
                homePosterLoading = homePosterLoading.copy(popularMix = true)
                val loadingStartedAtMs = currentTimeMs()
                try {
                    state.playPopularMix().join()
                } finally {
                    val remainingLoadingMs = HomePosterLoadingMinDurationMs - (currentTimeMs() - loadingStartedAtMs)
                    if (remainingLoadingMs > 0L) {
                        delay(remainingLoadingMs)
                    }
                    homePosterLoading = homePosterLoading.copy(popularMix = false)
                }
            }
            Unit
        }
    }
    val playTopTracksMix = remember(state, homePosterActionScope) {
        {
            homePosterActionScope.launch {
                homePosterLoading = homePosterLoading.copy(topTracksMix = true)
                val loadingStartedAtMs = currentTimeMs()
                try {
                    state.playTopTracksMix().join()
                } finally {
                    val remainingLoadingMs = HomePosterLoadingMinDurationMs - (currentTimeMs() - loadingStartedAtMs)
                    if (remainingLoadingMs > 0L) {
                        delay(remainingLoadingMs)
                    }
                    homePosterLoading = homePosterLoading.copy(topTracksMix = false)
                }
            }
            Unit
        }
    }
    LaunchedEffect(screen, browseSection, catalog.albums, catalog.tracksByParent.keys, session?.selectedServer, nowMs, trackHeavySectionsEnabled) {
        if (!trackHeavySectionsEnabled) return@LaunchedEffect
        if (screen == AppScreen.Home && browseSection == BrowseSection.Home) {
            delay(1_500L)
            state.warmRecentAlbumTracks(cutoffMs = nowMs - RecentlyAddedWindowMs, maxAlbums = 10)
        }
    }
    LaunchedEffect(screen, browseSection, session?.selectedServer?.id, session?.selectedLibrary?.key) {
        if (screen == AppScreen.Home && browseSection == BrowseSection.Home) {
            delay(1_200L)
            state.warmTopTracksMixTracks()
        }
    }
    LaunchedEffect(screen, browseSection, topMostPlayed, topRecentlyPlayed, session?.selectedServer) {
        if (screen == AppScreen.Home && browseSection == BrowseSection.Home &&
            (topMostPlayed.isNotEmpty() || topRecentlyPlayed.isNotEmpty())
        ) {
            state.warmTracksForMostPlayed()
        }
    }
    LaunchedEffect(screen, topMostPlayed, topRecentlyPlayed, session?.selectedServer) {
        if (screen is AppScreen.PlayHistory &&
            (topMostPlayed.isNotEmpty() || topRecentlyPlayed.isNotEmpty())
        ) {
            state.warmTracksForMostPlayed(maxTracks = 50)
        }
    }
    LaunchedEffect(session?.selectedServer?.id, session?.selectedLibrary?.key) {
        state.refreshRadioStations()
    }
    LaunchedEffect(browseSection, libraryUi.mobileBottomTabs) {
        val currentTab = browseSection.mobileBottomTab()
        if (currentTab != null && currentTab !in libraryUi.mobileBottomTabs) {
            navigator.openBrowse(libraryUi.mobileBottomTabs.first().browseSection())
            selectedPlaylistId = null
        }
    }
    val openRecentSongs: () -> Unit = {
        selectedPlaylistId = null
        navigator.openBrowse(BrowseSection.Home)
        navigator.open(PhoebeRoute.RecentlyAdded(RecentlyAddedKind.Songs))
    }
    val openRecentArtists: () -> Unit = {
        selectedPlaylistId = null
        navigator.openBrowse(BrowseSection.Home)
        navigator.open(PhoebeRoute.RecentlyAdded(RecentlyAddedKind.Artists))
    }
    val openRecentAlbums: () -> Unit = {
        selectedPlaylistId = null
        navigator.openBrowse(BrowseSection.Home)
        navigator.open(PhoebeRoute.RecentlyAdded(RecentlyAddedKind.Albums))
    }
    val openRecentlyPlayed: () -> Unit = {
        selectedPlaylistId = null
        navigator.openBrowse(BrowseSection.Home)
        navigator.open(PhoebeRoute.PlayHistory(PlayHistoryKind.RecentlyPlayed))
    }
    val openMostPlayed: () -> Unit = {
        selectedPlaylistId = null
        navigator.openBrowse(BrowseSection.Home)
        navigator.open(PhoebeRoute.PlayHistory(PlayHistoryKind.MostPlayed))
    }
    val openFavoritePlaylists: () -> Unit = {
        selectedPlaylistId = null
        navigator.openBrowse(BrowseSection.Home)
        navigator.open(PhoebeRoute.FavoritePlaylists)
    }
    val openFavoriteArtists: () -> Unit = {
        selectedPlaylistId = null
        navigator.openBrowse(BrowseSection.Home)
        navigator.open(PhoebeRoute.FavoriteArtists)
    }
    val openFavoriteAlbums: () -> Unit = {
        selectedPlaylistId = null
        navigator.openBrowse(BrowseSection.Home)
        navigator.open(PhoebeRoute.FavoriteAlbums)
    }
    val openCollections: (CollectionEntry) -> Unit = { entry ->
        if (session.supportsCollectionEntry(entry)) {
            homePosterLoading = homePosterLoading.copy(collectionEntry = entry)
            homePosterActionScope.launch {
                delay(700L)
                if (homePosterLoading.collectionEntry == entry) {
                    homePosterLoading = homePosterLoading.copy(collectionEntry = null)
                }
            }
            selectedPlaylistId = null
            navigator.openBrowse(BrowseSection.Home)
            libraryFilter = when (entry.target) {
                CollectionTarget.Artists -> LibraryFilterTab.Artists
                CollectionTarget.Albums -> LibraryFilterTab.Albums
            }
            navigator.open(PhoebeRoute.Collections(entry))
        }
    }
    val openCollectionValue: (CollectionEntry, String) -> Unit = { entry, value ->
        if (session.supportsCollectionEntry(entry)) {
            selectedPlaylistId = null
            navigator.open(PhoebeRoute.CollectionItems(entry, value))
        }
    }
    val searchHistory = rememberSearchHistoryState(
        recentItems = recentSearchItems,
        onPrependRecentSearch = state::prependRecentSearch,
        onRemoveRecentSearch = state::removeRecentSearch,
        onClearRecentSearches = state::clearRecentSearches,
    )
    val savedSearchActions = remember(savedSearches, state) {
        SavedSearchActions(
            savedSearches = savedSearches,
            saveSearch = { query, title -> state.saveSearch(query, title) },
            deleteSearch = { search -> state.deleteSavedSearch(search) },
        )
    }
    var createPlaylistFor by remember { mutableStateOf<List<Track>?>(null) }
    var metadataEditorTrack by remember { mutableStateOf<Track?>(null) }
    val catalogActionsKey = catalogHomeMetadataKey to catalog.playlists.size
    val sessionKey = session?.selectedServer?.id to session?.selectedLibrary?.key
    val playlistActions = remember(catalogActionsKey, sessionKey, mediaSources.localFolders, smartPlaylists) {
        val plexReady = session.supportsRemotePlaylists()
        val localReady = mediaSources.localFolders.any { it.enabled }
        val providerType = session?.providerType
        val list = catalog.playlists.filter { playlist ->
            playlist.isLocalPlaylist() ||
                playlist.isSmartPlaylist() ||
                playlist.isLikedSongsPlaylist() ||
                (plexReady && providerType != null && playlist.isRemoteProviderPlaylist() && playlist.belongsToProvider(providerType))
        }.sortedWith(compareByDescending<Playlist> { it.isSmartPlaylist() }.thenBy { it.title.lowercase() })
        PlaylistActions(
            playlists = list,
            smartPlaylists = smartPlaylists,
            playlistsEnabled = plexReady || localReady,
            onAddTrackToPlaylist = { playlist, track, allowDuplicate -> state.addToPlaylist(playlist, track, allowDuplicate) },
            onMovePlaylistTrack = { playlist, from, to -> state.movePlaylistTrack(playlist, from, to) },
            onRemovePlaylistTracks = { playlist, tracks -> state.removePlaylistTracks(playlist, tracks) },
            onCopyPlaylistToPlaylist = { source, target -> state.copyPlaylistIntoPlaylist(source, target) },
            onDeletePlaylist = { playlist -> state.deletePlaylist(playlist) },
            onSaveSmartPlaylistToProvider = { playlist -> state.saveSmartPlaylistToProvider(playlist) },
            onCreatePlaylist = { title, initialTracks -> state.createPlaylist(title, initialTracks) },
            onRequestCreatePlaylist = { initialTracks ->
                val canCreate = when {
                    initialTracks.any { it.canAddToPlexPlaylist() } -> plexReady
                    initialTracks.any { it.canAddToLocalPlaylist() } -> localReady
                    else -> plexReady || localReady
                }
                if (canCreate) {
                    createPlaylistFor = initialTracks
                }
            },
            onOpenLikedSongs = { state.openLikedSongsPlaylist() },
            onExportLocalPlaylist = { playlist, format -> state.exportLocalPlaylist(playlist, format) },
            onShufflePlaylist = { playlist -> state.playPlaylistShuffled(playlist) },
            onCreateSmartPlaylist = { template, title -> state.createSmartPlaylist(template, title) },
            onUpdateSmartPlaylist = { playlist -> state.updateSmartPlaylist(playlist) },
            onRenameSmartPlaylist = { playlist, title -> state.renameSmartPlaylist(playlist, title) },
            onDuplicateSmartPlaylist = { playlist -> state.duplicateSmartPlaylist(playlist) },
            onSetSmartPlaylistEnabled = { playlist, enabled -> state.setSmartPlaylistEnabled(playlist, enabled) },
            onDeleteSmartPlaylist = { playlist -> state.deleteSmartPlaylist(playlist) },
        )
    }
    val likedTracksKey = if (trackHeavySectionsEnabled) catalogTrackIndexKey else -1L
    val likeActions = remember(catalogActionsKey, likedTracksKey, sessionKey, radioDirectory.manualStations) {
        val likedPlaylist = catalog.playlists.firstOrNull { it.isLikedSongsPlaylist() }
        LikeActions(
            likedTrackIds = likedPlaylist?.let { playlist ->
                catalog.tracksByParent[playlist.id].orEmpty().map { it.id }.toSet()
            }.orEmpty(),
            likesEnabled = session.supportsRemotePlaylists(),
            onToggleLiked = { track ->
                if (track.id.startsWith("radio:")) {
                    state.toggleFavoriteRadioStation(track)
                } else {
                    state.toggleLikedTrack(track)
                }
            },
            likedRadioStreamUrls = radioDirectory.manualStations.map { it.streamUrl }.toSet(),
        )
    }
    val trackRatingIndex = remember(catalogTrackIndexKey) {
        if (trackHeavySectionsEnabled) buildTrackRatingIndex(catalog) else emptyMap()
    }
    val ratingActions = remember(catalogHomeMetadataKey, catalogTrackIndexKey, sessionKey) {
        RatingActions(
            ratingsEnabled = session.supportsRemoteRatings(),
            catalog = catalog,
            trackRatingsById = trackRatingIndex,
            onRateTrack = { track, rating -> state.rateTrack(track, rating) },
            onRateArtist = { artist, rating -> state.rateArtist(artist, rating) },
            onRateAlbum = { album, rating -> state.rateAlbum(album, rating) },
            onRatePlaylist = { playlist, rating -> state.ratePlaylist(playlist, rating) },
        )
    }
    val favoriteActions = remember(catalogHomeMetadataKey, state) {
        FavoriteActions(
            catalog = catalog,
            onToggleArtist = { artist -> state.toggleFavoriteArtist(artist) },
            onToggleAlbum = { album -> state.toggleFavoriteAlbum(album) },
            onTogglePlaylist = { playlist -> state.toggleFavoritePlaylist(playlist) },
        )
    }
    val trackNavigationActions = remember(catalog, state) {
        TrackNavigationActions(
            onOpenArtistForTrack = { track ->
                resolveArtistForTrack(catalog, track)?.let { artist ->
                    navigator.open(artist.route())
                    true
                } ?: false
            },
            onOpenAlbumForTrack = { track ->
                resolveAlbumForTrack(catalog, track)?.let { album ->
                    navigator.open(album.route())
                    true
                } ?: false
            },
            onOpenSongDetail = { track ->
                navigator.open(track.route())
            },
        )
    }
    val metadataEditorActions = remember {
        MetadataEditorActions(onRequestEdit = { track -> metadataEditorTrack = track })
    }
    val downloadActions = remember(state) {
        DownloadActions(
            onDeleteDownloadedTracks = { tracks -> state.deleteDownloads(tracks) },
            onCancelDownloadedTracks = { tracks -> state.cancelDownloads(tracks) },
        )
    }
    val dragDrop = remember { DragDropController() }

    CompositionLocalProvider(
        LocalCatalogHasContent provides catalogHasContent,
        LocalCatalogSyncState provides catalogSyncState,
        LocalCatalogSyncInProgress provides catalogSyncInProgress,
        LocalHomeTrackSectionsReady provides trackHeavySectionsEnabled,
        LocalMostPlayedResolving provides mostPlayedResolving,
        LocalSharedElementTransitionsEnabled provides true,
        LocalTracksLoading provides tracksLoading,
        LocalDownloadStatus provides downloadStatus,
        LocalDownloadActions provides downloadActions,
        LocalNowPlaying provides nowPlaying,
        LocalPlayHistory provides playHistory,
        LocalNowMs provides nowMs,
        LocalPlaylistActions provides playlistActions,
        LocalLikeActions provides likeActions,
        LocalFavoriteActions provides favoriteActions,
        LocalRatingActions provides ratingActions,
        LocalTrackNavigationActions provides trackNavigationActions,
        LocalMetadataEditorActions provides metadataEditorActions,
        LocalDragDrop provides dragDrop,
        LocalSearchHistory provides searchHistory,
        LocalSavedSearchActions provides savedSearchActions,
    ) {
    createPlaylistFor?.let { seedTracks ->
        CreatePlaylistDialog(
            initialTracks = seedTracks,
            onDismiss = { createPlaylistFor = null },
            onConfirm = { title ->
                state.createPlaylist(title, seedTracks)
                createPlaylistFor = null
            },
        )
    }
    // Wrap everything in a single Box so the drag-ghost overlay actually sits ON TOP of the
    // app rather than under it (CompositionLocalProvider isn't a layout, so emitting siblings
    // here results in painter order = source order, with the last one rendered last/highest).
    Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 1200.dp
            val wideDesktop = maxWidth >= 1280.dp
            CompositionLocalProvider(LocalPlaylistDragEnabled provides !compact) {
            val mergesTitleBar = LocalDesktopMergesTitleBar.current
            val shellTintStrength = if (isDesktopPlatform()) 0.72f else 1f
            val shellTintRadius = if (isDesktopPlatform()) 930f else 960f
            val contentTopPadding = when {
                mergesTitleBar -> desktopTitleBarHeight()
                compact && isDesktopPlatform() -> desktopWindowTopPadding()
                else -> 0.dp
            }
            val contentInsetModifier = if (compact) {
                Modifier
            } else if (mergesTitleBar) {
                Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Start + WindowInsetsSides.End + WindowInsetsSides.Bottom,
                    ),
                )
            } else {
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .phoebeShellBackground(
                        tintedGradient = appSettings.tintedBackgroundGradient,
                        radius = shellTintRadius,
                        radialTintStrength = shellTintStrength,
                    )
                    .padding(top = contentTopPadding)
                    .then(contentInsetModifier),
            ) {
            if (compact) {
                SharedTransitionLayout(Modifier.fillMaxSize()) {
                val sharedTransitionScope = this
                val canBrowseSourceBackedSections = canBrowseMainSections(session, mediaSources)
                val mobileChromeVisible = (canBrowseSourceBackedSections || browseSection == BrowseSection.Radio) &&
                    screen != AppScreen.SignIn &&
                    screen != AppScreen.ServerPicker &&
                    screen != AppScreen.LibraryPicker
                val mobileChromePadding = if (mobileChromeVisible) {
                    val mobileDensity = LocalDensity.current
                    val navigationBottomPadding = with(mobileDensity) {
                        WindowInsets.navigationBars.getBottom(this).toDp()
                    }
                    MobileChromePadding(
                        bottom = MobileBottomNavChromeHeight +
                            navigationBottomPadding +
                            MobileChromeScrollGap +
                            if (currentTrack != null) MobileMiniPlayerChromeHeight else 0.dp,
                    )
                } else {
                    MobileChromePadding()
                }

                val mobileRoutes = navigator.routes
                val mobilePlayerAsSheet = mobileRoutes.lastOrNull() == PhoebeRoute.Player && mobileRoutes.size > 1
                val playerExpansionFraction = remember {
                    Animatable(if (mobilePlayerAsSheet) 1f else 0f).apply {
                        updateBounds(lowerBound = 0f, upperBound = 1f)
                    }
                }
                var bottomBarHeightPx by remember { mutableFloatStateOf(0f) }
                LaunchedEffect(mobilePlayerAsSheet) {
                    if (mobilePlayerAsSheet) {
                        playerExpansionFraction.animateTo(1f, spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy))
                    } else {
                        playerExpansionFraction.animateTo(0f, spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy))
                    }
                }

                CompositionLocalProvider(
                    LocalSharedTransitionScope provides sharedTransitionScope,
                    LocalSharedElementTransitionsEnabled provides true,
                    LocalMobileChromePadding provides mobileChromePadding,
                ) {
                val mobileContentRoutes = if (mobilePlayerAsSheet) mobileRoutes.dropLast(1) else mobileRoutes
                val renderableMobileContentRoutes = mobileContentRoutes.renderablePhoebeRoutes()
                PhoebeNavDisplay(
                    backStack = renderableMobileContentRoutes,
                    modifier = Modifier.fillMaxSize(),
                    animateTransitions = true,
                    opaqueSceneBackgrounds = !isDesktopPlatform(),
                    onBack = {
                        when (navigator.currentRoute) {
                            is PhoebeRoute.PlaylistDetail -> exitPlaylistDetail()
                            is PhoebeRoute.PlaylistSlugDetail -> exitPlaylistDetail()
                            else -> navigator.pop()
                        }
                    },
                ) { targetRoute ->
                val targetResolution = resolvePhoebeRoute(targetRoute, catalog, currentTrack)
                val targetMissingRoute = targetResolution as? PhoebeRouteResolution.Missing
                val scr = (targetResolution as? PhoebeRouteResolution.Resolved)?.screen ?: AppScreen.Home
                if (targetMissingRoute != null) {
                    MissingRouteFallback(
                        title = targetMissingRoute.title,
                        message = targetMissingRoute.message,
                        onBack = { navigator.pop() },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                when (scr) {
                    is AppScreen.ServerPicker -> PlexServerPickerRoute(
                        state = PlexServerPickerRouteState(
                            servers = servers,
                            busy = busy,
                            serversLoading = serversLoading,
                        ),
                        actions = PlexServerPickerRouteActions(
                            onSelectServer = state::selectServer,
                            onCancel = state::signOut,
                            onRetry = state::loadServers,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                    is AppScreen.LibraryPicker -> PlexLibraryPickerRoute(
                        state = PlexLibraryPickerRouteState(
                            libraries = libraries,
                            serverName = session?.selectedServer?.name,
                            providerType = session?.providerType ?: com.phoebe.app.domain.MediaProviderType.Plex,
                            busy = busy,
                            librariesLoading = librariesLoading,
                            isJellyfin = session.isEmbyFamily(),
                        ),
                        actions = PlexLibraryPickerRouteActions(
                            onSelectLibrary = { library, mode -> state.selectLibrary(library, mode) },
                            onBack = state::returnToServerPicker,
                            onCancel = state::signOut,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                    is AppScreen.SignIn -> AuthWelcomeMobileRoute(
                        state = AuthWelcomeRouteState(
                            message = message,
                            pinCode = pin?.code,
                            jellyfinServers = jellyfinServers,
                            jellyfinDiscoveryLoading = jellyfinDiscoveryLoading,
                            jellyfinQuickConnect = jellyfinQuickConnect,
                            authInProgress = authInProgress,
                        ),
                        actions = AuthWelcomeRouteActions(
                            onStartSignIn = state::startPlexSignIn,
                            onFinishSignIn = state::finishPlexSignIn,
                            onSignInJellyfin = state::signInJellyfin,
                            onSignInProvider = state::signInProvider,
                            onDiscoverJellyfinServers = state::discoverJellyfinServers,
                            onStartJellyfinQuickConnect = state::startJellyfinQuickConnect,
                            onFinishJellyfinQuickConnect = state::finishJellyfinQuickConnect,
                            onAddLocalFolder = state::addLocalFolderFromUri,
                            onOpenRadio = {
                                selectedPlaylistId = null
                                navigator.openBrowse(BrowseSection.Radio)
                            },
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                    is AppScreen.ArtistDetail -> ArtistDetailRoute(
                        state = ArtistDetailRouteState(
                            artist = scr.artist,
                            catalog = catalog,
                            libraryUi = libraryUi,
                            catalogRefreshing = catalogRefreshing,
                            searchQuery = searchQuery,
                            artistRadioAvailability = artistRadioAvailability[scr.artist.id],
                            artistRadioStarting = scr.artist.id in radioStartingIds,
                            fullBleedArtwork = appSettings.fullBleedDetailArtwork,
                        ),
                        actions = ArtistDetailRouteActions(
                            onBack = { navigator.pop() },
                            onAlbum = { navigator.open(it.route()) },
                            onPlayTracks = playTracksFromMobile,
                            onPlayAllTracks = playAllTracksFromMobile,
                            onShuffleAllTracks = shuffleAllTracksFromMobile,
                            onAddToUpNext = state::addToUpNext,
                            onDownload = state::download,
                            onDownloadArtist = state::download,
                            onProbeArtistRadio = state::probeArtistRadio,
                            onPlayArtistRadio = state::playArtistRadio,
                            onArtist = { navigator.open(it.route()) },
                            onLibraryColumns = state::setLibraryColumns,
                            onCollectionItems = openCollectionValue,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                    is AppScreen.AlbumDetail -> AlbumDetailRoute(
                        state = AlbumDetailRouteState(
                            album = scr.album,
                            catalog = catalog,
                            libraryUi = libraryUi,
                            catalogRefreshing = catalogRefreshing,
                            searchQuery = searchQuery,
                            fullBleedArtwork = appSettings.fullBleedDetailArtwork,
                        ),
                        actions = AlbumDetailRouteActions(
                            onBack = { navigator.pop() },
                            onPlayTracks = playTracksFromMobile,
                            onAddToUpNext = state::addToUpNext,
                            onDownload = state::download,
                            onDownloadAlbum = state::download,
                            onArtist = { navigator.open(it.route()) },
                            onLibraryColumns = state::setLibraryColumns,
                            onCollectionItems = openCollectionValue,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                    is AppScreen.SongDetail -> SongDetailRoute(
                        state = SongDetailRouteState(track = scr.track),
                        actions = SongDetailRouteActions(
                            onBack = { navigator.pop() },
                            onPlay = { playTracksFromMobile(listOf(scr.track), 0) },
                            onAddToUpNext = state::addToUpNext,
                            onDownload = state::download,
                            onOpenLyrics = { navigator.open(PhoebeRoute.Lyrics(it.id)) },
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                    is AppScreen.Lyrics -> LyricsScreenHost(
                        appState = state,
                        track = lyricsTrack,
                        currentTrackId = currentTrack?.id,
                        lyricsState = lyricsState,
                        onBack = { navigator.pop() },
                        onRetry = retryLyrics,
                        modifier = Modifier.fillMaxSize(),
                    )
                    is AppScreen.RecentlyAdded -> {
                        val nowPlaying = LocalNowPlaying.current
                        RecentlyAddedRoute(
                            state = RecentlyAddedRouteState(
                                kind = scr.kind,
                                catalog = catalog,
                                nowMs = nowMs,
                                nowPlaying = RecentlyAddedNowPlayingState(
                                    trackId = nowPlaying.trackId,
                                    isPlaying = nowPlaying.isPlaying,
                                    isBuffering = nowPlaying.isBuffering,
                                ),
                                bottomContentPadding = LocalMobileChromePadding.current.bottom,
                            ),
                            actions = RecentlyAddedRouteActions(
                                onBack = { navigator.pop() },
                                onArtist = { navigator.open(it.route()) },
                                onAlbum = { navigator.open(it.route()) },
                                onPlayTracks = playTracksFromMobile,
                                onAddToUpNext = state::addToUpNext,
                                onDownload = state::download,
                            ),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    is AppScreen.Collections -> CollectionsRoute(
                        state = CollectionsRouteState(
                            entry = scr.entry,
                            catalog = catalog,
                            supportedCollectionEntries = supportedCollectionEntries,
                            bottomContentPadding = LocalMobileChromePadding.current.bottom,
                        ),
                        actions = CollectionsRouteActions(
                            onBack = { navigator.pop() },
                            onCollectionValue = openCollectionValue,
                            onEnsureValuesLoaded = { state.preloadCollections(scr.entry) },
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                    is AppScreen.CollectionItems -> CollectionItemsRoute(
                        state = CollectionItemsRouteState(
                            entry = scr.entry,
                            value = scr.value,
                            catalog = catalog,
                            supportedCollectionEntries = supportedCollectionEntries,
                            bottomContentPadding = LocalMobileChromePadding.current.bottom,
                        ),
                        actions = CollectionItemsRouteActions(
                            onBack = { navigator.pop() },
                            onArtist = { navigator.open(it.route()) },
                            onAlbum = { navigator.open(it.route()) },
                            onEnsureItemsLoaded = { state.preloadCollectionItems(scr.entry, scr.value) },
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                    is AppScreen.PlayHistory -> {
                        val nowPlaying = LocalNowPlaying.current
                        val viewModel = remember(state.routeViewModelFactory, scr.kind) {
                            state.routeViewModelFactory.playHistory()
                        }
                        PlayHistoryRoute(
                            viewModel = viewModel,
                            state = PlayHistoryRouteState(
                                kind = scr.kind,
                                catalog = catalog,
                                playHistory = playHistory,
                                resolvedTracksById = resolvedTracksById,
                                nowPlaying = HistoryNowPlayingState(
                                    trackId = nowPlaying.trackId,
                                    isPlaying = nowPlaying.isPlaying,
                                    isBuffering = nowPlaying.isBuffering,
                                ),
                                loadRankedEntries = loadPlayHistoryEntries,
                            ),
                            libraryUi = libraryUi,
                            onLibraryColumns = state::setLibraryColumns,
                            modifier = Modifier.fillMaxSize(),
                            bottomContentPadding = LocalMobileChromePadding.current.bottom,
                            onBack = { navigator.pop() },
                            onPlayTracks = playTracksFromMobile,
                            onAddToUpNext = state::addToUpNext,
                            onDownload = state::download,
                        )
                    }
                    AppScreen.FavoritePlaylists -> FavoritePlaylistsMobileRoute(
                        state = FavoritePlaylistsRouteState(searchQuery = searchQuery),
                        actions = FavoritePlaylistsRouteActions(
                            onSearchQuery = { searchQuery = it },
                            onBack = { navigator.pop() },
                            onPlaylist = { playlist ->
                                navigator.open(playlist.route())
                            },
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                    AppScreen.FavoriteArtists -> FavoriteArtistsMobileRoute(
                        state = FavoriteArtistsRouteState(
                            catalog = catalog,
                            libraryUi = libraryUi,
                        ),
                        actions = FavoriteArtistsRouteActions(
                            onBack = { navigator.pop() },
                            onLibrarySortBy = state::setLibrarySortBy,
                            onLibraryAscending = state::setLibrarySortAscending,
                            onLibraryColumns = state::setLibraryColumns,
                            onArtist = { navigator.open(it.route()) },
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                    AppScreen.FavoriteAlbums -> FavoriteAlbumsMobileRoute(
                        state = FavoriteAlbumsRouteState(
                            catalog = catalog,
                            libraryUi = libraryUi,
                        ),
                        actions = FavoriteAlbumsRouteActions(
                            onBack = { navigator.pop() },
                            onLibrarySortBy = state::setLibrarySortBy,
                            onLibraryAscending = state::setLibrarySortAscending,
                            onLibraryColumns = state::setLibraryColumns,
                            onAlbum = { navigator.open(it.route()) },
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                    is AppScreen.PlaylistDetail -> PlaylistDetailRoute(
                        state = PlaylistDetailRouteState(
                            playlist = scr.playlist,
                            catalog = catalog,
                            catalogRefreshing = catalogRefreshing,
                            libraryUi = libraryUi,
                            searchQuery = searchQuery,
                        ),
                        actions = PlaylistDetailRouteActions(
                            onSearchQuery = { searchQuery = it },
                            onBack = exitPlaylistDetail,
                            onPlayTracks = playTracksFromMobile,
                            onAddToUpNext = state::addToUpNext,
                            onDownload = state::download,
                            onDownloadPlaylist = state::download,
                            onCancelDownloadPlaylist = state::cancelDownloads,
                            onDeleteDownloadPlaylist = state::deleteDownloads,
                            onMovePlaylistTrack = state::movePlaylistTrack,
                            onLibraryColumns = state::setLibraryColumns,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                    AppScreen.Player -> MobilePlayerHost(
                        appState = state,
                        track = mobilePlayerTrack,
                        upNext = mobilePlayerUpNext,
                        previousTrack = mobilePlayerPreviousTrack,
                        currentIndex = mobilePlayerCurrentIndex,
                        playbackStarting = mobilePlaybackStarting,
                        castState = cast,
                        remotePlaybackTarget = musicAssistantRemotePlayback?.target,
                        onToggle = state::togglePlayPause,
                        onPrevious = state::previous,
                        onNext = state::next,
                        onSkipQueueBy = state::skipQueueBy,
                        onShuffle = state::toggleShuffle,
                        onRepeat = state::cycleRepeat,
                        onSeek = state::seekTo,
                        onPlayQueue = state::playUpNext,
                        onMoveUpNext = state::moveUpNext,
                        onRemoveUpNext = state::removeUpNext,
                        onOpenSongDetail = { navigator.open(it.route()) },
                        onCast = state::showCastPicker,
                        onLyrics = {
                            if (currentTrack != null) navigator.open(PhoebeRoute.Lyrics())
                        },
                        onBack = collapseMobilePlayer,
                        onSwipeDismiss = collapseMobilePlayer,
                        handleSystemBack = navigator.routes.size > 1,
                    )
                    AppScreen.Home -> {
                    val onHomeBrowse = browseSection == BrowseSection.Home && selectedPlaylistId == null
                    val catalogForMobileBrowse = if (onHomeBrowse) {
                        remember(catalogHomeMetadataKey, catalogTrackIndexKey) { catalog }
                    } else {
                        catalog
                    }
                    MobileBrowseShell(
                        catalog = catalogForMobileBrowse,
                        catalogRefreshing = catalogRefreshing,
                        session = session,
                        section = browseSection,
                        selectedPlaylistId = null,
                        searchQuery = searchQuery,
                        libraryFilter = libraryFilter,
                        libraryUi = libraryUi,
                        currentTrack = currentTrack,
                        homeUiState = homeUiState,
                        isPlaying = shellPlayback.isPlaying,
                        isBuffering = shellPlayback.isBuffering,
                        onNavigate = {
                            navigator.openBrowse(it)
                            selectedPlaylistId = null
                        },
                        onSearchQuery = { newQuery ->
                            searchQuery = newQuery
                            val scopedScreen = screen
                            val scoped = scopedScreen is AppScreen.ArtistDetail ||
                                scopedScreen is AppScreen.AlbumDetail ||
                                scopedScreen is AppScreen.SongDetail ||
                                scopedScreen is AppScreen.Lyrics ||
                                scopedScreen is AppScreen.Collections ||
                                scopedScreen is AppScreen.CollectionItems ||
                                scopedScreen is AppScreen.RecentlyAdded ||
                                scopedScreen is AppScreen.PlayHistory ||
                                scopedScreen is AppScreen.FavoritePlaylists ||
                                scopedScreen is AppScreen.FavoriteArtists ||
                                scopedScreen is AppScreen.FavoriteAlbums ||
                                scopedScreen is AppScreen.PlaylistDetail ||
                                selectedPlaylistId != null ||
                                browseSection == BrowseSection.Library ||
                                browseSection == BrowseSection.Radio ||
                                browseSection == BrowseSection.Playlists ||
                                browseSection == BrowseSection.Downloads ||
                                browseSection == BrowseSection.Settings
                            if (!scoped && newQuery.isNotBlank()) {
                                navigator.openBrowse(BrowseSection.Search)
                            }
                        },
                        onLibraryFilter = { libraryFilter = it },
                        onPlaylist = { playlist ->
                            navigator.open(playlist.route())
                        },
                        onArtist = { navigator.open(it.route()) },
                        onAlbum = { navigator.open(it.route()) },
                        onSong = { navigator.open(it.route()) },
                        onRecentSongs = openRecentSongs,
                        onRecentArtists = openRecentArtists,
                        onRecentAlbums = openRecentAlbums,
                        onFavoritePlaylists = openFavoritePlaylists,
                        onFavoriteArtists = openFavoriteArtists,
                        onFavoriteAlbums = openFavoriteAlbums,
                        onRecentlyPlayed = openRecentlyPlayed,
                        onMostPlayed = openMostPlayed,
                        onCollections = openCollections,
                        supportedCollectionEntries = supportedCollectionEntries,
                        homePosterLoading = homePosterLoading,
                        onRefreshRandomArtists = homeFeatureState.onRefreshRandomArtists,
                        onRefreshRandomAlbums = homeFeatureState.onRefreshRandomAlbums,
                        onPrefetchHomeArtist = state::prefetchHomeArtistStats,
                        onPrefetchHomeAlbum = state::prefetchHomeAlbumStats,
                        onPlayDecadeMix = state::playDecadeMix,
                        decadeMixNotice = decadeMixNotice,
                        onClearDecadeMixNotice = state::clearDecadeMixNotice,
                        radioStations = radioStations,
                        radioStartingIds = radioStartingIds,
                        onPlayRadioStation = state::playRadioStation,
                        internetRadioDirectory = radioDirectory,
                        internetRadioRouteMode = radioRouteMode,
                        internetRadioStartingIds = effectiveInternetRadioStartingIds,
                        onInternetRadioSearch = state::searchInternetRadio,
                        onInternetRadioLoadMore = state::loadMoreInternetRadio,
                        onInternetRadioRefreshPopular = state::refreshInternetRadio,
                        onPlayInternetRadioStation = state::playInternetRadioStation,
                        onInternetRadioCountries = openInternetRadioCountries,
                        onInternetRadioCountry = openInternetRadioCountry,
                        onInternetRadioMap = openInternetRadioMap,
                        onInternetRadioMapSearch = state::loadInternetRadioMap,
                        onInternetRadioMapCountry = { countryCode ->
                            state.loadFocusedInternetRadioMap(countryCode = countryCode)
                        },
                        onOpenInternetRadioStation = openInternetRadioStation,
                        onInternetRadioRoot = openRadioRoot,
                        onAddManualRadioStation = state::addManualRadioStation,
                        onUpdateManualRadioStation = state::updateManualRadioStation,
                        onDeleteManualRadioStation = state::deleteManualRadioStation,
                        onPlayPersonalMix = playPersonalMix,
                        onPlayPopularMix = playPopularMix,
                        onPlayTopTracksMix = playTopTracksMix,
                        onPlayTracks = playTracksFromMobile,
                        onAddToUpNext = state::addToUpNext,
                        onDownload = state::download,
                        onOpenNowPlaying = { openMobilePlayer() },
                        onTogglePlayPause = state::togglePlayPause,
                        onPreviousTrack = state::previous,
                        onNextTrack = state::next,
                        onOpenSignIn = { navigator.open(PhoebeRoute.SignIn) },
                        onSignOut = state::signOut,
                        onAddLocalFolder = state::addLocalFolderFromUri,
                        onRefreshLibrary = state::refreshCatalog,
                        onJellyfinPage = state::loadJellyfinLibraryPage,
                        onLibrarySortBy = state::setLibrarySortBy,
                        onLibraryAscending = state::setLibrarySortAscending,
                        onLibraryColumns = state::setLibraryColumns,
                        onHomeSections = state::setHomeSections,
                        onMobileBottomTabs = state::setMobileBottomTabs,
                        onPersonalMix = state::setPersonalMixPreferences,
                        onAlbumGridItemSize = state::setAlbumGridItemSize,
                        onArtistGridItemSize = state::setArtistGridItemSize,
                        onExportFavoritePlaylists = state::exportFavoritePlaylists,
                        onImportFavoritePlaylists = state::importFavoritePlaylists,
                        onExportRadioStations = state::exportRadioStations,
                        onImportRadioStations = state::importRadioStations,
                        onExportBackupPackage = state::exportBackupPackage,
                        onImportBackupPackage = { state.importBackupPackage() },
                        onReplaceFromBackupPackage = { state.importBackupPackage(BackupRestoreMode.Replace) },
                        appSettings = appSettings,
                        audioProcessingCapabilities = state.audioProcessingCapabilities,
                        homeScreenLayoutMode = homeScreenLayoutMode,
                        onCrossfadeSeconds = state::setCrossfadeSeconds,
                        onScanLibraryOnLaunch = state::setScanLibraryOnLaunch,
                        onNotifyWhenDownloadFinishes = state::setNotifyWhenDownloadFinishes,
                        onPersistEqualizerSettings = state::setPersistEqualizerSettings,
                        onAudioProcessingSettings = state::setAudioProcessingSettings,
                        onVisualizerPreset = state::setNowPlayingVisualizerPreset,
                        onBlurredArtworkAppearance = state::setBlurredArtworkAppearance,
                        onTintedBackgroundGradient = state::setTintedBackgroundGradient,
                        downloadDirectory = downloadDirectory,
                        downloadCount = downloads.size,
                        downloadItems = downloads,
                        downloadManager = downloadManagerSummary,
                        defaultDownloadDirectoryLabel = state.defaultDownloadDirectoryLabel,
                        onDownloadDirectory = state::setDownloadDirectory,
                        onDeleteAllDownloads = state::deleteAllDownloads,
                        onDeleteCompletedDownloads = state::deleteCompletedDownloads,
                        onClearFailedDownloads = state::clearFailedDownloads,
                        onRetryFailedDownloads = { state.retryFailedDownloads() },
                        onRetryDownloads = state::retryFailedDownloads,
                        onCancelDownloads = state::cancelDownloadsWithoutDeleting,
                        onDeleteDownloads = state::deleteDownloadsByTrackIds,
                        onDownloadPolicySettings = state::setDownloadPolicySettings,
                        useLightAppearance = useLightAppearance,
                        onUseLightAppearanceChange = onUseLightAppearanceChange,
                        appearanceTintId = appearanceTintId,
                        onAppearanceTintChange = onAppearanceTintChange,
                        onHomeScreenLayoutModeChange = onHomeScreenLayoutModeChange,
                        listenBrainzCredentialAvailability = state.listenBrainzCredentialAvailability,
                        onConnectListenBrainz = state::connectListenBrainz,
                        onDisconnectListenBrainz = state::disconnectListenBrainz,
                        onListenBrainzSubmitNowPlaying = state::setListenBrainzSubmitNowPlaying,
                        onListenBrainzSubmitListens = state::setListenBrainzSubmitListens,
                        onListenBrainzSubmitCurrentTrackFeedback = state::setListenBrainzSubmitCurrentTrackFeedback,
                        onStartLastFmAuthorization = state::startLastFmAuthorization,
                        onFinishLastFmAuthorization = state::finishLastFmAuthorization,
                        onDisconnectLastFm = state::disconnectLastFm,
                        onLastFmSubmitNowPlaying = state::setLastFmSubmitNowPlaying,
                        onLastFmSubmitScrobbles = state::setLastFmSubmitScrobbles,
                        appUpdateState = appUpdateState,
                        onCheckForUpdates = state::checkForUpdates,
                        routeViewModelFactory = state.routeViewModelFactory,
                        onInstallUpdate = state::installAvailableUpdate,
                        showBottomChrome = false,
                    )
                    }
                }
                }
                }
                val density = LocalDensity.current
                val navigationBars = WindowInsets.navigationBars
                val calculatedFallbackPx = remember(mobileChromeVisible, density, navigationBars) {
                    if (mobileChromeVisible) {
                        with(density) {
                            (MobileBottomNavChromeHeight + navigationBars.getBottom(this).toDp()).toPx()
                        }
                    } else {
                        0f
                    }
                }
                val actualBottomBarHeightPx = if (mobileChromeVisible) {
                    if (bottomBarHeightPx > 0f) bottomBarHeightPx else calculatedFallbackPx
                } else {
                    0f
                }
                val miniPlayerHeightPx = with(density) { MobileMiniPlayerChromeHeight.toPx() }

                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val screenHeightPx = constraints.maxHeight.toFloat()
                    val dragRangePx = (screenHeightPx - actualBottomBarHeightPx - miniPlayerHeightPx).coerceAtLeast(1f)
                    val radioMapOcclusionTopPx = if (contentRoute == PhoebeRoute.RadioMap && currentTrack != null) {
                        dragRangePx * (1f - playerExpansionFraction.value)
                    } else {
                        null
                    }
                    RadioMapOverlayOcclusionEffect(radioMapOcclusionTopPx)

                    if (mobileChromeVisible) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .graphicsLayer {
                                    translationY = actualBottomBarHeightPx * playerExpansionFraction.value
                                }
                                .zIndex(5f)
                                .onGloballyPositioned { coordinates ->
                                    bottomBarHeightPx = coordinates.size.height.toFloat()
                                }
                        ) {
                            MobileBottomNavigation(
                                section = browseSection,
                                onSection = { section ->
                                    if (!canBrowseSourceBackedSections && section.requiresBrowseSource()) return@MobileBottomNavigation
                                    navigator.openBrowse(section)
                                    selectedPlaylistId = null
                                },
                                attachedToMiniPlayer = currentTrack != null,
                                tabs = libraryUi.mobileBottomTabs,
                            )
                        }
                    }

                    if (currentTrack != null) {
                        if (contentRoute != PhoebeRoute.RadioMap) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = 0.86f * playerExpansionFraction.value
                                    }
                                    .background(Color.Black)
                                    .zIndex(3f)
                            )
                        }

                        val scope = rememberCoroutineScope()
                        val onDragStart = {
                            scope.launch { playerExpansionFraction.stop() }
                            Unit
                        }
                        val onDrag = { deltaY: Float ->
                            val current = playerExpansionFraction.value
                            val delta = -deltaY / dragRangePx
                            scope.launch {
                                playerExpansionFraction.snapTo((current + delta).coerceIn(0f, 1f))
                            }
                            Unit
                        }
                        val onDragEnd = { velocityY: Float ->
                            val current = playerExpansionFraction.value
                            val shouldExpand = when {
                                velocityY < -350f -> true
                                velocityY > 350f -> false
                                current > 0.35f -> true
                                else -> false
                            }
                            val initialFractionVelocity = (-velocityY / dragRangePx).coerceIn(-30f, 30f)
                            if (shouldExpand) {
                                ensureRadioPlaybackBackStack()
                                navigator.openPlayer()
                                scope.launch {
                                    playerExpansionFraction.animateTo(
                                        targetValue = 1f,
                                        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy),
                                        initialVelocity = initialFractionVelocity,
                                    )
                                }
                            } else {
                                if (mobilePlayerAsSheet) {
                                    navigator.pop()
                                }
                                scope.launch {
                                    playerExpansionFraction.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy),
                                        initialVelocity = initialFractionVelocity,
                                    )
                                }
                            }
                            Unit
                        }

                        PlatformBackHandler(
                            enabled = mobilePlayerAsSheet,
                            onBack = {
                                if (mobilePlayerAsSheet) {
                                    navigator.pop()
                                }
                            },
                            onBackProgress = { progress ->
                                scope.launch {
                                    playerExpansionFraction.snapTo(1f - progress)
                                }
                            },
                            onBackCancel = {
                                scope.launch {
                                    playerExpansionFraction.animateTo(1f, spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy))
                                }
                            }
                        )

                        MobilePlayerHost(
                            appState = state,
                            track = mobilePlayerTrack,
                            upNext = mobilePlayerUpNext,
                            previousTrack = mobilePlayerPreviousTrack,
                            currentIndex = mobilePlayerCurrentIndex,
                            playbackStarting = mobilePlaybackStarting,
                            castState = cast,
                            remotePlaybackTarget = musicAssistantRemotePlayback?.target,
                            onToggle = state::togglePlayPause,
                            onPrevious = state::previous,
                            onNext = state::next,
                            onSkipQueueBy = state::skipQueueBy,
                            onShuffle = state::toggleShuffle,
                            onRepeat = state::cycleRepeat,
                            onSeek = state::seekTo,
                            onPlayQueue = state::playUpNext,
                            onMoveUpNext = state::moveUpNext,
                            onRemoveUpNext = state::removeUpNext,
                            onOpenSongDetail = { navigator.open(it.route()) },
                            onCast = state::showCastPicker,
                            onLyrics = {
                                navigator.open(PhoebeRoute.Lyrics())
                            },
                            onBack = collapseMobilePlayer,
                            onSwipeDismiss = collapseMobilePlayer,
                            onClick = { openMobilePlayer() },
                            handleSystemBack = false,
                            expansionFraction = playerExpansionFraction.value,
                            onDragStart = onDragStart,
                            onDrag = onDrag,
                            onDragEnd = onDragEnd,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationY = dragRangePx * (1f - playerExpansionFraction.value)
                                }
                                .zIndex(4f)
                        )
                    }
                }
                }
                }
            } else {
                val audioAnalysis by state.audioAnalysis.collectAsState()
                DesktopPlayer(
                    playerFlow = state.player,
                    shellState = DesktopShellState(
                        screen = screen,
                        routes = navigator.routes,
                        catalog = catalog,
                        catalogRefreshing = catalogRefreshing,
                        session = session,
                        mediaSources = mediaSources,
                        section = browseSection,
                        selectedPlaylistId = selectedPlaylistId,
                        showQueue = wideDesktop,
                        compact = !wideDesktop,
                        busy = busy,
                        updateState = appUpdateState,
                        routeViewModelFactory = state.routeViewModelFactory,
                    ),
                    playbackState = PlaybackUiState(
                        shellPlayback = shellPlayback,
                        playerTransport = playerTransport,
                        track = currentTrack,
                        radioNowPlaying = radioNowPlaying,
                        upNext = upNext,
                        currentIndex = currentIndex,
                        lyricsTrack = lyricsTrack,
                        lyricsState = lyricsState,
                        castState = cast,
                        remotePlaybackTarget = musicAssistantRemotePlayback?.target,
                        listenBrainzFeedbackTarget = listenBrainzFeedbackTarget,
                        equalizerProfile = equalizerProfile,
                        persistEqualizerSettings = appSettings.persistEqualizerSettings,
                        equalizerRemoteUnavailable = equalizerRemoteUnavailable,
                        visualizerPreset = appSettings.nowPlayingVisualizerPreset,
                        showVisualizerInTvFrame = appSettings.nowPlayingVisualizerInTvFrame,
                        audioAnalysis = audioAnalysis,
                    ),
                    playbackActions = PlaybackActions(
                        onToggle = state::togglePlayPause,
                        onPrevious = state::previous,
                        onNext = state::next,
                        onShuffle = state::toggleShuffle,
                        onRepeat = state::cycleRepeat,
                        onVolume = state::setVolume,
                        onSeek = state::seekTo,
                        onCast = state::showCastPicker,
                        onEqualizerEnabled = state::setEqualizerEnabled,
                        onEqualizerBandCount = state::setEqualizerBandCount,
                        onEqualizerGain = state::setEqualizerGain,
                        onEqualizerReset = state::resetEqualizer,
                        onPersistEqualizerSettings = state::setPersistEqualizerSettings,
                        onVisualizerPreset = { preset ->
                            state.setNowPlayingVisualizerPreset(preset)
                            if (preset.isVisualizer) {
                                if (navigator.currentRoute != PhoebeRoute.Player) {
                                    navigator.openPlayer()
                                }
                            } else {
                                if (navigator.currentRoute == PhoebeRoute.Player) {
                                    navigator.pop()
                                }
                            }
                        },
                        onShowVisualizerInTvFrame = state::setNowPlayingVisualizerInTvFrame,
                        onListenBrainzFeedback = state::submitListenBrainzFeedback,
                        onLyrics = {
                            selectedPlaylistId = null
                            navigator.openBrowse(
                                if (browseSection == BrowseSection.Lyrics) BrowseSection.Home else BrowseSection.Lyrics,
                            )
                        },
                        onPlayQueue = state::playUpNext,
                        onClearQueue = state::clearQueue,
                        onMoveUpNext = state::moveUpNext,
                        onRemoveUpNext = state::removeUpNext,
                        onRetryLyrics = retryLyrics,
                    ),
                    browseState = BrowseUiState(
                        homeUiState = homeUiState,
                        playHistory = playHistory,
                        loadPlayHistoryEntries = loadPlayHistoryEntries,
                        resolvedTracksById = resolvedTracksById,
                        searchQuery = searchQuery,
                        libraryFilter = libraryFilter,
                        libraryUi = libraryUi,
                        supportedCollectionEntries = supportedCollectionEntries,
                        homePosterLoading = homePosterLoading,
                        decadeMixNotice = decadeMixNotice,
                        radioStations = radioStations,
                        radioDirectory = radioDirectory,
                        radioRouteMode = radioRouteMode,
                        artistRadioAvailability = artistRadioAvailability,
                        radioStartingIds = radioStartingIds,
                        internetRadioStartingIds = effectiveInternetRadioStartingIds,
                    ),
                    browseActions = BrowseActions(
                        onNavigate = { section ->
                            if (!canBrowseMainSections(session, mediaSources) && section.isMainBrowseSection()) return@BrowseActions
                            navigator.openBrowse(section)
                            selectedPlaylistId = null
                        },
                        onSearchQuery = { newQuery ->
                            searchQuery = newQuery
                            // Stay in any scoped context (playlist, detail, or library tab)
                            // and let that view filter its own contents by the query.
                            val scoped = screen is AppScreen.ArtistDetail ||
                                screen is AppScreen.AlbumDetail ||
                                screen is AppScreen.SongDetail ||
                                screen is AppScreen.Lyrics ||
                                screen is AppScreen.Collections ||
                                screen is AppScreen.CollectionItems ||
                                screen is AppScreen.RecentlyAdded ||
                                screen is AppScreen.PlayHistory ||
                                screen is AppScreen.FavoritePlaylists ||
                                screen is AppScreen.FavoriteArtists ||
                                screen is AppScreen.FavoriteAlbums ||
                                screen is AppScreen.PlaylistDetail ||
                                selectedPlaylistId != null ||
                                browseSection == BrowseSection.Library ||
                                browseSection == BrowseSection.Radio ||
                                browseSection == BrowseSection.Playlists ||
                                browseSection == BrowseSection.Downloads ||
                                browseSection == BrowseSection.Settings
                            if (
                                !scoped &&
                                newQuery.isNotBlank() &&
                                canBrowseMainSections(session, mediaSources)
                            ) {
                                navigator.openBrowse(BrowseSection.Search)
                            }
                        },
                        onLibraryFilter = { libraryFilter = it },
                        onPlaylist = { playlist ->
                            selectedPlaylistId = playlist.id
                            navigator.open(playlist.route())
                        },
                        onArtist = { navigator.open(it.route()) },
                        onAlbum = { navigator.open(it.route()) },
                        onSong = { navigator.open(it.route()) },
                        onOpenLyrics = { navigator.open(PhoebeRoute.Lyrics(it.id)) },
                        onRecentSongs = openRecentSongs,
                        onRecentArtists = openRecentArtists,
                        onRecentAlbums = openRecentAlbums,
                        onFavoritePlaylists = openFavoritePlaylists,
                        onFavoriteArtists = openFavoriteArtists,
                        onFavoriteAlbums = openFavoriteAlbums,
                        onRecentlyPlayed = openRecentlyPlayed,
                        onMostPlayed = openMostPlayed,
                        onCollections = openCollections,
                        onCollectionValue = openCollectionValue,
                        onEnsureCollectionValuesLoaded = state::preloadCollections,
                        onEnsureCollectionItemsLoaded = state::preloadCollectionItems,
                        onRefreshRandomArtists = homeFeatureState.onRefreshRandomArtists,
                        onRefreshRandomAlbums = homeFeatureState.onRefreshRandomAlbums,
                        onPrefetchHomeArtist = state::prefetchHomeArtistStats,
                        onPrefetchHomeAlbum = state::prefetchHomeAlbumStats,
                        onPlayDecadeMix = state::playDecadeMix,
                        onClearDecadeMixNotice = state::clearDecadeMixNotice,
                        onPlayRadioStation = state::playRadioStation,
                        onRadioSearch = state::searchInternetRadio,
                        onRadioLoadMore = state::loadMoreInternetRadio,
                        onRadioRefreshPopular = state::refreshInternetRadio,
                        onRadioPlay = state::playInternetRadioStation,
                        onRadioCountries = openInternetRadioCountries,
                        onRadioCountry = openInternetRadioCountry,
                        onRadioMap = openInternetRadioMap,
                        onRadioMapSearch = state::loadInternetRadioMap,
                        onRadioMapCountry = { countryCode ->
                            state.loadFocusedInternetRadioMap(countryCode = countryCode)
                        },
                        onRadioStation = openInternetRadioStation,
                        onRadioRoot = openRadioRoot,
                        onRadioAddManualStation = state::addManualRadioStation,
                        onRadioUpdateManualStation = state::updateManualRadioStation,
                        onRadioDeleteManualStation = state::deleteManualRadioStation,
                        onPlayPersonalMix = playPersonalMix,
                        onPlayPopularMix = playPopularMix,
                        onPlayTopTracksMix = playTopTracksMix,
                        onPopDetail = { navigator.pop() },
                        onPlayTracks = playTracks,
                        onPlayAllTracks = playAllTracks,
                        onShuffleAllTracks = shuffleAllTracks,
                        onAddToUpNext = state::addToUpNext,
                        onDownload = state::download,
                        onDownloadArtist = state::download,
                        onProbeArtistRadio = state::probeArtistRadio,
                        onPlayArtistRadio = state::playArtistRadio,
                        onDownloadAlbum = state::download,
                        onDownloadPlaylist = state::download,
                        onLibrarySortBy = state::setLibrarySortBy,
                        onLibraryAscending = state::setLibrarySortAscending,
                        onLibraryColumns = state::setLibraryColumns,
                        onInstallUpdate = state::installAvailableUpdate,
                    ),
                    authSetupState = AuthSetupState(
                        appMessage = message,
                        pinCode = pin?.code,
                        authInProgress = authInProgress,
                        serversLoading = serversLoading,
                        jellyfinServers = jellyfinServers,
                        jellyfinDiscoveryLoading = jellyfinDiscoveryLoading,
                        jellyfinQuickConnect = jellyfinQuickConnect,
                        servers = servers,
                        libraries = libraries,
                        librariesLoading = librariesLoading,
                    ),
                    authSetupActions = AuthSetupActions(
                        onStartSignIn = state::startPlexSignIn,
                        onFinishSignIn = state::finishPlexSignIn,
                        onSignInJellyfin = state::signInJellyfin,
                        onSignInProvider = state::signInProvider,
                        onDiscoverJellyfinServers = state::discoverJellyfinServers,
                        onStartJellyfinQuickConnect = state::startJellyfinQuickConnect,
                        onFinishJellyfinQuickConnect = state::finishJellyfinQuickConnect,
                        onOpenSignIn = { navigator.open(PhoebeRoute.SignIn) },
                        onSignOut = state::signOut,
                        onAddLocalFolder = state::addLocalFolderFromUri,
                        onRemoveLocalFolder = state::removeLocalFolder,
                        onToggleLocalFolder = state::setLocalFolderEnabled,
                        onRefreshLibrary = state::refreshCatalog,
                        onJellyfinPage = state::loadJellyfinLibraryPage,
                        onSelectServer = { state.selectServer(it) },
                        onSelectLibrary = { library, mode -> state.selectLibrary(library, mode) },
                        onCancelPlexSetup = { state.signOut() },
                        onBackToServerPicker = { state.returnToServerPicker() },
                        onRetryServers = { state.loadServers() },
                    ),
                    settingsState = SettingsUiState(
                        appSettings = appSettings,
                        downloadDirectory = downloadDirectory,
                        downloadCount = downloads.size,
                        downloadItems = downloads,
                        downloadManager = downloadManagerSummary,
                        defaultDownloadDirectoryLabel = state.defaultDownloadDirectoryLabel,
                        useLightAppearance = useLightAppearance,
                        appearanceTintId = appearanceTintId,
                        homeScreenLayoutMode = homeScreenLayoutMode,
                        settingsInitialCategory = if (browseSection == BrowseSection.Downloads) {
                            SettingsCategory.Downloads
                        } else {
                            SettingsCategory.AudioPlayback
                        },
                        listenBrainzCredentialAvailability = state.listenBrainzCredentialAvailability,
                        appUpdateState = appUpdateState,
                    ),
                    settingsActions = SettingsActions(
                        onHomeSections = state::setHomeSections,
                        onMobileBottomTabs = state::setMobileBottomTabs,
                        onPersonalMix = state::setPersonalMixPreferences,
                        onAlbumGridItemSize = state::setAlbumGridItemSize,
                        onArtistGridItemSize = state::setArtistGridItemSize,
                        onExportFavoritePlaylists = state::exportFavoritePlaylists,
                        onImportFavoritePlaylists = state::importFavoritePlaylists,
                        onExportRadioStations = state::exportRadioStations,
                        onImportRadioStations = state::importRadioStations,
                        onExportBackupPackage = state::exportBackupPackage,
                        onImportBackupPackage = { state.importBackupPackage() },
                        onReplaceFromBackupPackage = { state.importBackupPackage(BackupRestoreMode.Replace) },
                        onCrossfadeSeconds = state::setCrossfadeSeconds,
                        onScanLibraryOnLaunch = state::setScanLibraryOnLaunch,
                        onNotifyWhenDownloadFinishes = state::setNotifyWhenDownloadFinishes,
                        onPersistEqualizerSettings = state::setPersistEqualizerSettings,
                        onPersistVolumeSettings = state::setPersistVolumeSettings,
                        onAudioProcessingSettings = state::setAudioProcessingSettings,
                        audioProcessingCapabilities = state.audioProcessingCapabilities,
                        onVisualizerPreset = state::setNowPlayingVisualizerPreset,
                        onShowVisualizerInTvFrame = state::setNowPlayingVisualizerInTvFrame,
                        onBlurredArtworkAppearance = state::setBlurredArtworkAppearance,
                        onFullBleedDetailArtwork = state::setFullBleedDetailArtwork,
                        onTintedBackgroundGradient = state::setTintedBackgroundGradient,
                        onDownloadDirectory = state::setDownloadDirectory,
                        onDeleteAllDownloads = state::deleteAllDownloads,
                        onDeleteCompletedDownloads = state::deleteCompletedDownloads,
                        onClearFailedDownloads = state::clearFailedDownloads,
                        onRetryFailedDownloads = { state.retryFailedDownloads() },
                        onRetryDownloads = state::retryFailedDownloads,
                        onCancelDownloads = state::cancelDownloadsWithoutDeleting,
                        onDeleteDownloads = state::deleteDownloadsByTrackIds,
                        onDownloadPolicySettings = state::setDownloadPolicySettings,
                        onUseLightAppearanceChange = onUseLightAppearanceChange,
                        onAppearanceTintChange = onAppearanceTintChange,
                        onHomeScreenLayoutModeChange = onHomeScreenLayoutModeChange,
                        onConnectListenBrainz = state::connectListenBrainz,
                        onDisconnectListenBrainz = state::disconnectListenBrainz,
                        onListenBrainzSubmitNowPlaying = state::setListenBrainzSubmitNowPlaying,
                        onListenBrainzSubmitListens = state::setListenBrainzSubmitListens,
                        onListenBrainzSubmitCurrentTrackFeedback = state::setListenBrainzSubmitCurrentTrackFeedback,
                        onStartLastFmAuthorization = state::startLastFmAuthorization,
                        onFinishLastFmAuthorization = state::finishLastFmAuthorization,
                        onDisconnectLastFm = state::disconnectLastFm,
                        onLastFmSubmitNowPlaying = state::setLastFmSubmitNowPlaying,
                        onLastFmSubmitScrobbles = state::setLastFmSubmitScrobbles,
                        onCheckForUpdates = state::checkForUpdates,
                        onInstallUpdate = state::installAvailableUpdate,
                    ),
                )
            }
            metadataEditorTrack?.let { editing ->
                val latest = catalog.tracksByParent.values
                    .asSequence()
                    .flatten()
                    .firstOrNull { it.id == editing.id } ?: editing
                MetadataEditorOverlay(
                    track = latest,
                    compact = compact,
                    onDismiss = { metadataEditorTrack = null },
                    onSave = { update ->
                        state.updateTrackMetadata(update)
                        metadataEditorTrack = null
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = busy && screen != AppScreen.SignIn,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize().align(Alignment.Center),
        ) {
            val overlayInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(PhoebeUi.overlayScrim)
                    .clickable(indication = null, interactionSource = overlayInteraction) {},
            ) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = PhoebeUi.accentLight,
                        strokeWidth = 3.dp,
                        trackColor = PhoebeUi.progressTrack,
                    )
                    Text("Please wait", color = PhoebeUi.primaryText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = message.ifBlank { "Finishing up…" },
                        color = PhoebeUi.secondaryText,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 320.dp),
                    )
                }
            }
        }
        PlaybackFailureSnackbar(
            notice = playbackSnackbar,
            onDismiss = state::dismissPlaybackSnackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        PlaylistDuplicateSnackbar(
            pendingAdd = pendingDuplicatePlaylistAdd,
            onDismiss = state::dismissDuplicatePlaylistAdd,
            onMoveToTop = state::moveDuplicatePlaylistAddToTop,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        pendingUpdateInstallConfirmation?.let { update ->
            UpdateInstallConfirmationDialog(
                update = update,
                onDismiss = { state.respondToUpdateInstallConfirmation(false) },
                onConfirm = { state.respondToUpdateInstallConfirmation(true) },
            )
        }
            }
    }
    // Drag-ghost overlay — must be the LAST child of the wrapper Box so it draws above the
    // rest of the UI. Renders nothing until a drag is in flight.
    DragGhost()
    }
    }
}

@Composable
private fun PlaybackFailureSnackbar(
    notice: PlaybackSnackbarNotice?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = notice?.message
    val streamUrl = notice?.streamUrl
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(notice) { mutableStateOf(false) }
    val snackbarMessage = if (streamUrl == null) {
        message.orEmpty()
    } else {
        "${message.orEmpty()}\n$streamUrl"
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5_000L)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically(tween(180, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(180)),
        exit = slideOutVertically(tween(160, easing = FastOutSlowInEasing)) { it / 2 } + fadeOut(tween(160)),
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 18.dp)
            .zIndex(20f),
    ) {
        PhoebeActionSnackbar(
            message = snackbarMessage,
            actionLabel = when {
                streamUrl != null && copied -> "Copied"
                streamUrl != null -> "Copy URL"
                else -> "Dismiss"
            },
            onAction = {
                if (streamUrl == null) {
                    onDismiss()
                } else {
                    clipboardManager.setText(AnnotatedString(streamUrl))
                    copied = true
                }
            },
        )
    }
}

@Composable
private fun PlaylistDuplicateSnackbar(
    pendingAdd: PendingDuplicatePlaylistAdd?,
    onDismiss: () -> Unit,
    onMoveToTop: (PendingDuplicatePlaylistAdd) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(pendingAdd) {
        if (pendingAdd != null) {
            delay(8_000L)
            onDismiss()
        }
    }
    var lastNonNullPending by remember { mutableStateOf<PendingDuplicatePlaylistAdd?>(null) }
    if (pendingAdd != null) {
        lastNonNullPending = pendingAdd
    }
    AnimatedVisibility(
        visible = pendingAdd != null,
        enter = slideInVertically(tween(180, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(180)),
        exit = slideOutVertically(tween(160, easing = FastOutSlowInEasing)) { it / 2 } + fadeOut(tween(160)),
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 18.dp)
            .zIndex(21f),
    ) {
        val currentPending = lastNonNullPending
        if (currentPending != null) {
            PhoebeActionSnackbar(
                message = currentPending.message,
                actionLabel = "Move to top",
                onAction = {
                    onMoveToTop(currentPending)
                },
            )
        }
    }
}

@Composable
private fun PhoebeActionSnackbar(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        color = PhoebeUi.panel.copy(alpha = 0.96f),
        contentColor = PhoebeUi.primaryText,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, PhoebeUi.border),
        shadowElevation = 12.dp,
        modifier = Modifier.widthIn(max = 520.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = message,
                color = PhoebeUi.primaryText,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun UpdateInstallConfirmationDialog(
    update: AvailableUpdate,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .widthIn(min = 320.dp, max = 460.dp)
                .shadow(elevation = 30.dp, shape = RoundedCornerShape(18.dp), clip = false)
                .clip(RoundedCornerShape(18.dp))
                .background(PhoebeUi.modalSurface)
                .border(BorderStroke(1.dp, PhoebeUi.accentLight.copy(alpha = 0.20f)), RoundedCornerShape(18.dp))
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(Res.drawable.phoebe_icon_rounded),
                    contentDescription = null,
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Install Phoebe ${update.versionName}?",
                        color = PhoebeUi.primaryText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "The update is downloaded and verified.",
                        color = PhoebeUi.secondaryText,
                        fontSize = 13.sp,
                    )
                }
            }
            Text(
                "Phoebe will close and open the installer. After the installer finishes, Phoebe will relaunch when the platform allows it.",
                color = PhoebeUi.secondaryText,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Not now", color = PhoebeUi.secondaryText)
                }
                TextButton(onClick = onConfirm) {
                    Text("Close and install", color = PhoebeUi.accentLight, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun catalogHasContentForSurface(
    catalog: CatalogSnapshot,
    screen: AppScreen,
    browseSection: BrowseSection,
    selectedPlaylistId: String?,
    libraryFilter: LibraryFilterTab,
): Boolean {
    selectedPlaylistId?.let { return catalog.tracksByParent[it].orEmpty().isNotEmpty() }
    return when (screen) {
        is AppScreen.AlbumDetail -> catalog.tracksByParent[screen.album.id].orEmpty().isNotEmpty()
        is AppScreen.ArtistDetail -> catalogAlbumsForArtist(catalog, screen.artist.title).isNotEmpty() ||
            catalogTracksForArtist(catalog, screen.artist.title).isNotEmpty()
        is AppScreen.PlaylistDetail -> catalog.tracksByParent[screen.playlist.id].orEmpty().isNotEmpty()
        is AppScreen.SongDetail -> true
        is AppScreen.Lyrics -> true
        is AppScreen.RecentlyAdded -> catalog.tracksByParent.values.any { it.isNotEmpty() } ||
            catalog.albums.isNotEmpty() ||
            catalog.artists.isNotEmpty()
        is AppScreen.PlayHistory -> true
        AppScreen.FavoritePlaylists -> catalog.playlists.any { it.favorite }
        AppScreen.FavoriteArtists -> catalog.artists.any { it.favorite }
        AppScreen.FavoriteAlbums -> catalog.albums.any { it.favorite }
        is AppScreen.Collections -> when (screen.entry.target) {
            CollectionTarget.Artists -> catalog.artists.isNotEmpty()
            CollectionTarget.Albums -> catalog.albums.isNotEmpty()
        }
        is AppScreen.CollectionItems -> when (screen.entry.target) {
            CollectionTarget.Artists -> catalog.artists.isNotEmpty()
            CollectionTarget.Albums -> catalog.albums.isNotEmpty()
        }
        AppScreen.Home -> when (browseSection) {
            BrowseSection.Home -> catalog.artists.isNotEmpty() ||
                catalog.albums.isNotEmpty() ||
                catalog.playlists.isNotEmpty() ||
                catalog.tracksByParent.values.any { it.isNotEmpty() }
            BrowseSection.Search -> catalog.tracksByParent.values.any { it.isNotEmpty() } ||
                catalog.artists.isNotEmpty() ||
                catalog.albums.isNotEmpty()
            BrowseSection.Library -> when (libraryFilter) {
                LibraryFilterTab.Artists -> catalog.artists.isNotEmpty()
                LibraryFilterTab.Albums -> catalog.albums.isNotEmpty()
                LibraryFilterTab.Songs -> catalog.tracksByParent.values.any { it.isNotEmpty() }
            }
            BrowseSection.Radio -> true
            BrowseSection.Lyrics -> true
            BrowseSection.Playlists -> catalog.playlists.isNotEmpty()
            BrowseSection.Downloads -> true
            BrowseSection.Settings -> true
        }
        AppScreen.SignIn,
        AppScreen.ServerPicker,
        AppScreen.LibraryPicker,
        AppScreen.Player,
        -> true
    }
}

@Composable
private fun LyricsScreenHost(
    appState: AppState,
    track: Track?,
    currentTrackId: String?,
    lyricsState: LyricsLoadState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val player by appState.player.collectAsState()
    val chromePadding = LocalMobileChromePadding.current
    LyricsRoute(
        state = LyricsRouteState(
            track = track,
            currentTrackId = currentTrackId,
            positionMs = player.positionMs,
            loadState = lyricsState,
        ),
        modifier = modifier.padding(bottom = chromePadding.bottom),
        onBack = onBack,
        onRetry = onRetry,
    )
}

@Composable
private fun MobilePlayerHost(
    appState: AppState,
    track: Track?,
    upNext: List<Track>,
    previousTrack: Track?,
    currentIndex: Int,
    playbackStarting: Boolean = false,
    castState: CastState,
    remotePlaybackTarget: String?,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSkipQueueBy: (Int) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayQueue: (Int) -> Unit,
    onMoveUpNext: (Int, Int) -> Unit,
    onRemoveUpNext: (Int) -> Unit,
    onOpenSongDetail: (Track) -> Unit,
    onCast: () -> Unit,
    onLyrics: () -> Unit,
    onBack: () -> Unit,
    onSwipeDismiss: () -> Unit,
    onClick: () -> Unit = {},
    handleSystemBack: Boolean = true,
    expansionFraction: Float = 0f,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val player by appState.player.collectAsState()
    val appSettings by appState.appSettings.collectAsState()
    val collectAudioAnalysis = expansionFraction > 0.6f &&
        appSettings.nowPlayingVisualizerPreset != NowPlayingVisualizerPreset.Artwork
    val audioAnalysis by produceState(AudioAnalysisFrame.Empty, collectAudioAnalysis) {
        if (collectAudioAnalysis) {
            appState.audioAnalysis.collect { value = it }
        } else {
            value = AudioAnalysisFrame.Empty
        }
    }
    val equalizerProfile by appState.equalizerProfile.collectAsState()
    val equalizerRemoteUnavailable by appState.equalizerRemoteUnavailable.collectAsState()
    val listenBrainzFeedbackTarget by appState.listenBrainzFeedbackTarget.collectAsState()
    val showStartingState = playbackStarting && track?.id != player.currentTrack?.id
    MobilePlaybackRoute(
        state = MobilePlaybackRouteState(
            track = track,
            upNext = upNext,
            previousTrack = previousTrack,
            isPlaying = if (showStartingState) false else player.isPlaying,
            isBuffering = player.isBuffering || showStartingState,
            shuffle = player.shuffle,
            repeat = player.repeat,
            positionMs = if (showStartingState) 0L else player.positionMs,
            bufferedPositionMs = if (showStartingState) 0L else player.bufferedPositionMs,
            currentIndex = currentIndex,
            castState = castState,
            remotePlaybackTarget = remotePlaybackTarget,
            listenBrainzFeedbackTarget = listenBrainzFeedbackTarget,
            equalizerProfile = equalizerProfile,
            persistEqualizerSettings = appSettings.persistEqualizerSettings,
            equalizerRemoteUnavailable = equalizerRemoteUnavailable,
            visualizerPreset = appSettings.nowPlayingVisualizerPreset,
            showVisualizerInTvFrame = appSettings.nowPlayingVisualizerInTvFrame,
            blurredArtworkAppearance = appSettings.blurredArtworkAppearance,
            tintedBackgroundGradient = appSettings.tintedBackgroundGradient,
            audioAnalysis = audioAnalysis,
            handleSystemBack = handleSystemBack,
            expansionFraction = expansionFraction,
        ),
        actions = MobilePlaybackRouteActions(
            onToggle = onToggle,
            onPrevious = onPrevious,
            onNext = onNext,
            onSkipQueueBy = onSkipQueueBy,
            onShuffle = onShuffle,
            onRepeat = onRepeat,
            onSeek = onSeek,
            onPlayQueue = onPlayQueue,
            onMoveUpNext = onMoveUpNext,
            onRemoveUpNext = onRemoveUpNext,
            onOpenSongDetail = onOpenSongDetail,
            onCast = onCast,
            onLyrics = onLyrics,
            onEqualizerEnabled = appState::setEqualizerEnabled,
            onEqualizerBandCount = appState::setEqualizerBandCount,
            onEqualizerGain = appState::setEqualizerGain,
            onEqualizerReset = appState::resetEqualizer,
            onPersistEqualizerSettings = appState::setPersistEqualizerSettings,
            onVisualizerPreset = appState::setNowPlayingVisualizerPreset,
            onShowVisualizerInTvFrame = appState::setNowPlayingVisualizerInTvFrame,
            onListenBrainzFeedback = appState::submitListenBrainzFeedback,
            onBack = onBack,
            onSwipeDismiss = onSwipeDismiss,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            onClick = onClick,
        ),
        modifier = modifier,
    )
}

private const val HomePosterLoadingMinDurationMs = 700L

private fun Track?.withRadioNowPlaying(metadata: RadioNowPlayingMetadata?): Track? {
    val track = this ?: return null
    val live = metadata?.takeIf { it.hasTrack } ?: return track
    if (!track.id.startsWith("radio:")) return track
    if (live.trackId != null && live.trackId != track.id) return track
    return track.copy(
        title = live.title.ifBlank { live.rawTitle ?: track.title },
        artist = live.artist.ifBlank { track.artist },
    )
}

private fun resolveArtistForTrack(catalog: CatalogSnapshot, track: Track): Artist? {
    val title = track.artist.trim()
    if (title.isBlank()) return null
    return catalog.artists.firstOrNull { it.title.equals(title, ignoreCase = true) }
        ?: resolveAlbumForTrack(catalog, track)?.let { album ->
            catalog.artists.firstOrNull { it.title.equals(album.artist, ignoreCase = true) }
        }
        ?: catalog.artists.firstOrNull { artist ->
            catalogAlbumsForArtist(catalog, artist.title).any { album ->
                album.title.equals(track.album, ignoreCase = true)
            }
        }
}

private fun resolveAlbumForTrack(catalog: CatalogSnapshot, track: Track): Album? {
    track.parentAlbumId?.let { parentAlbumId ->
        catalog.albums.firstOrNull { it.id == parentAlbumId }?.let { return it }
    }
    val albumTitle = track.album.trim()
    if (albumTitle.isBlank()) return null
    return catalog.albums.firstOrNull { album ->
        album.title.equals(albumTitle, ignoreCase = true) &&
            album.artist.equals(track.artist, ignoreCase = true)
    } ?: catalog.albums.firstOrNull { album ->
        album.title.equals(albumTitle, ignoreCase = true)
    }
}

private fun List<DownloadItem>.toDownloadManagerUiSummary(activeDownloadJobCount: Int): DownloadManagerUiSummary =
    DownloadManagerUiSummary(
        total = size,
        active = maxOf(
            count { it.state == DownloadState.Queued || it.state == DownloadState.Downloading },
            activeDownloadJobCount,
        ),
        complete = count { it.state == DownloadState.Complete },
        failed = count { it.state == DownloadState.Failed },
        estimatedBytes = filter { it.state == DownloadState.Complete }.sumOf { it.downloadedBytes },
    )
