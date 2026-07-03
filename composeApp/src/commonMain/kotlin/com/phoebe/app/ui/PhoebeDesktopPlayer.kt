package com.phoebe.app.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.phoebe.app.domain.AppScreen
import com.phoebe.app.domain.JellyfinSyncMode
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.PlayerState
import com.phoebe.app.domain.RadioNowPlayingMetadata
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.isEmbyFamily
import com.phoebe.app.domain.isNavidrome
import com.phoebe.app.domain.isPlex
import com.phoebe.app.feature.auth.AuthWelcomeDesktopRoute
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
import com.phoebe.app.feature.details.SongDetailRoute
import com.phoebe.app.feature.details.SongDetailRouteActions
import com.phoebe.app.feature.details.SongDetailRouteState
import com.phoebe.app.feature.favorites.FavoriteAlbumsDesktopRoute
import com.phoebe.app.feature.favorites.FavoriteAlbumsRouteActions
import com.phoebe.app.feature.favorites.FavoriteAlbumsRouteState
import com.phoebe.app.feature.favorites.FavoriteArtistsDesktopRoute
import com.phoebe.app.feature.favorites.FavoriteArtistsRouteActions
import com.phoebe.app.feature.favorites.FavoriteArtistsRouteState
import com.phoebe.app.feature.favorites.FavoritePlaylistsDesktopRoute
import com.phoebe.app.feature.favorites.FavoritePlaylistsRouteActions
import com.phoebe.app.feature.favorites.FavoritePlaylistsRouteState
import com.phoebe.app.feature.history.HistoryNowPlayingState
import com.phoebe.app.feature.history.PlayHistoryRoute
import com.phoebe.app.feature.history.PlayHistoryRouteState
import com.phoebe.app.feature.home.AlbumMixBuilderRoute
import com.phoebe.app.feature.home.ArtistMixBuilderRoute
import com.phoebe.app.feature.home.DesktopHomeRoute
import com.phoebe.app.feature.home.DesktopHomeRouteActions
import com.phoebe.app.feature.home.DesktopHomeRouteState
import com.phoebe.app.feature.home.MixBuilderRouteActions
import com.phoebe.app.feature.home.MixBuilderRouteState
import com.phoebe.app.feature.home.RecentlyAddedNowPlayingState
import com.phoebe.app.feature.home.RecentlyAddedRoute
import com.phoebe.app.feature.home.RecentlyAddedRouteActions
import com.phoebe.app.feature.home.RecentlyAddedRouteState
import com.phoebe.app.feature.library.LibraryDesktopRoute
import com.phoebe.app.feature.library.LibraryRouteActions
import com.phoebe.app.feature.library.LibraryRouteState
import com.phoebe.app.feature.lyrics.LyricsRoute
import com.phoebe.app.feature.lyrics.LyricsRouteState
import com.phoebe.app.feature.playback.DesktopVisualizerRoute
import com.phoebe.app.feature.playback.DesktopVisualizerRouteState
import com.phoebe.app.feature.playback.DesktopTransport
import com.phoebe.app.feature.playback.QueueRoute
import com.phoebe.app.feature.playback.QueueRouteActions
import com.phoebe.app.feature.playback.QueueRouteState
import com.phoebe.app.feature.search.SearchDesktopRoute
import com.phoebe.app.feature.search.SearchDesktopRouteActions
import com.phoebe.app.feature.settings.SettingsDesktopRoute
import com.phoebe.app.feature.settings.SettingsCategory
import com.phoebe.app.feature.settings.SettingsRouteActions
import com.phoebe.app.feature.settings.SettingsRouteState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay

private const val DesktopSharedTransitionRetainMs = 400L
private const val DesktopShellRadialTintStrength = 0.72f
private const val DesktopShellRadialTintRadius = 930f

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun DesktopPlayer(
    playerFlow: StateFlow<PlayerState>? = null,
    shellState: DesktopShellState,
    playbackState: PlaybackUiState,
    playbackActions: PlaybackActions,
    browseState: BrowseUiState,
    browseActions: BrowseActions,
    authSetupState: AuthSetupState,
    authSetupActions: AuthSetupActions,
    settingsState: SettingsUiState,
    settingsActions: SettingsActions,
) {
    val screen = shellState.screen
    val routes = shellState.routes
    val catalog = shellState.catalog
    val catalogRefreshing = shellState.catalogRefreshing
    val session = shellState.session
    val mediaSources = shellState.mediaSources
    val section = shellState.section
    val selectedPlaylistId = shellState.selectedPlaylistId
    val showQueue = shellState.showQueue
    val compact = shellState.compact
    val busy = shellState.busy
    val routeViewModelFactory = shellState.routeViewModelFactory
    val showArtistAlbumMixBuilders = canBrowseMainSections(session, mediaSources)
    val shellPlayback = playbackState.shellPlayback
    val playerTransport = playbackState.playerTransport
    val playerChromeFlow = remember(playerFlow) {
        playerFlow
            ?.map { player -> player.withoutProgressTicks() }
            ?.distinctUntilChanged()
    }
    val playerChromeInitial = remember(playerFlow, playbackState.player) {
        (playerFlow?.value ?: playbackState.player).withoutProgressTicks()
    }
    val playerChrome = if (playerChromeFlow != null) {
        val collected by playerChromeFlow.collectAsState(playerChromeInitial)
        collected
    } else {
        playerChromeInitial
    }
    val hasLivePlayerChrome = playerFlow != null
    val baseTrack = if (hasLivePlayerChrome) playerChrome.currentTrack else playbackState.track
    val track = baseTrack.withRadioNowPlaying(playbackState.radioNowPlaying)
    val upNext = if (hasLivePlayerChrome) playerChrome.upNext else playbackState.upNext
    val upNextDivider = playbackState.upNextDivider
    val currentIndex = if (hasLivePlayerChrome) playerChrome.currentIndex else playbackState.currentIndex
    val lyricsTrack = playbackState.lyricsTrack
    val lyricsState = playbackState.lyricsState
    val castState = playbackState.castState
    val remotePlaybackTarget = playbackState.remotePlaybackTarget
    val listenBrainzFeedbackTarget = playbackState.listenBrainzFeedbackTarget
    val equalizerProfile = playbackState.equalizerProfile
    val persistEqualizerSettings = playbackState.persistEqualizerSettings
    val equalizerRemoteUnavailable = playbackState.equalizerRemoteUnavailable
    val visualizerPreset = playbackState.visualizerPreset
    val showVisualizerInTvFrame = playbackState.showVisualizerInTvFrame
    val audioAnalysis = playbackState.audioAnalysis
    val homeUiState = browseState.homeUiState
    val playHistory = browseState.playHistory
    val searchQuery = browseState.searchQuery
    val libraryFilter = browseState.libraryFilter
    val libraryUi = browseState.libraryUi
    val supportedCollectionEntries = browseState.supportedCollectionEntries
    val decadeMixNotice = browseState.decadeMixNotice
    val radioStations = browseState.radioStations
    val radioDirectory = browseState.radioDirectory
    val radioRouteMode = browseState.radioRouteMode
    val artistRadioAvailability = browseState.artistRadioAvailability
    val radioStartingIds = browseState.radioStartingIds
    val internetRadioStartingIds = browseState.internetRadioStartingIds
    val appMessage = authSetupState.appMessage
    val pinCode = authSetupState.pinCode
    val authInProgress = authSetupState.authInProgress
    val serversLoading = authSetupState.serversLoading
    val jellyfinServers = authSetupState.jellyfinServers
    val jellyfinDiscoveryLoading = authSetupState.jellyfinDiscoveryLoading
    val jellyfinQuickConnect = authSetupState.jellyfinQuickConnect
    val servers = authSetupState.servers
    val libraries = authSetupState.libraries
    val librariesLoading = authSetupState.librariesLoading
    val appSettings = settingsState.appSettings
    val downloadDirectory = settingsState.downloadDirectory
    val downloadCount = settingsState.downloadCount
    val downloadManager = settingsState.downloadManager
    val defaultDownloadDirectoryLabel = settingsState.defaultDownloadDirectoryLabel
    val useLightAppearance = settingsState.useLightAppearance
    val appearanceTintId = settingsState.appearanceTintId
    val settingsInitialCategory = settingsState.settingsInitialCategory
    val listenBrainzCredentialAvailability = settingsState.listenBrainzCredentialAvailability
    val onNavigate = browseActions.onNavigate
    val onSearchQuery = browseActions.onSearchQuery
    val onLibraryFilter = browseActions.onLibraryFilter
    val onPlaylist = browseActions.onPlaylist
    val onArtist = browseActions.onArtist
    val onAlbum = browseActions.onAlbum
    val onSong = browseActions.onSong
    val onOpenLyrics = browseActions.onOpenLyrics
    val onRecentSongs = browseActions.onRecentSongs
    val onRecentArtists = browseActions.onRecentArtists
    val onRecentAlbums = browseActions.onRecentAlbums
    val onFavoritePlaylists = browseActions.onFavoritePlaylists
    val onFavoriteArtists = browseActions.onFavoriteArtists
    val onFavoriteAlbums = browseActions.onFavoriteAlbums
    val onRecentlyPlayed = browseActions.onRecentlyPlayed
    val onMostPlayed = browseActions.onMostPlayed
    val onCollections = browseActions.onCollections
    val onCollectionValue = browseActions.onCollectionValue
    val onEnsureCollectionValuesLoaded = browseActions.onEnsureCollectionValuesLoaded
    val onEnsureCollectionItemsLoaded = browseActions.onEnsureCollectionItemsLoaded
    val onRefreshRandomArtists = browseActions.onRefreshRandomArtists
    val onRefreshRandomAlbums = browseActions.onRefreshRandomAlbums
    val onPrefetchHomeArtist = browseActions.onPrefetchHomeArtist
    val onPrefetchHomeAlbum = browseActions.onPrefetchHomeAlbum
    val onEnsureArtistSuggestions = browseActions.onEnsureArtistSuggestions
    val onPlayDecadeMix = browseActions.onPlayDecadeMix
    val onClearDecadeMixNotice = browseActions.onClearDecadeMixNotice
    val onPlayRadioStation = browseActions.onPlayRadioStation
    val onRadioSearch = browseActions.onRadioSearch
    val onRadioLoadMore = browseActions.onRadioLoadMore
    val onRadioRefreshPopular = browseActions.onRadioRefreshPopular
    val onRadioPlay = browseActions.onRadioPlay
    val onRadioCountries = browseActions.onRadioCountries
    val onRadioCountry = browseActions.onRadioCountry
    val onRadioMap = browseActions.onRadioMap
    val onRadioMapSearch = browseActions.onRadioMapSearch
    val onRadioMapCountry = browseActions.onRadioMapCountry
    val onRadioStation = browseActions.onRadioStation
    val onRadioRoot = browseActions.onRadioRoot
    val onRadioAddManualStation = browseActions.onRadioAddManualStation
    val onRadioUpdateManualStation = browseActions.onRadioUpdateManualStation
    val onRadioDeleteManualStation = browseActions.onRadioDeleteManualStation
    val onPlayPersonalMix = browseActions.onPlayPersonalMix
    val onPlayPopularMix = browseActions.onPlayPopularMix
    val onPlayTopTracksMix = browseActions.onPlayTopTracksMix
    val onArtistMixBuilder = browseActions.onArtistMixBuilder
    val onAlbumMixBuilder = browseActions.onAlbumMixBuilder
    val onPopDetail = browseActions.onPopDetail
    val onPlayTracks = browseActions.onPlayTracks
    val onPlayAllTracks = browseActions.onPlayAllTracks
    val onShuffleAllTracks = browseActions.onShuffleAllTracks
    val onAddToUpNext = browseActions.onAddToUpNext
    val onDownload = browseActions.onDownload
    val onDownloadArtist = browseActions.onDownloadArtist
    val onProbeArtistRadio = browseActions.onProbeArtistRadio
    val onPlayArtistRadio = browseActions.onPlayArtistRadio
    val onDownloadAlbum = browseActions.onDownloadAlbum
    val onDownloadPlaylist = browseActions.onDownloadPlaylist
    val onLibrarySortBy = browseActions.onLibrarySortBy
    val onLibraryAscending = browseActions.onLibraryAscending
    val onLibraryColumns = browseActions.onLibraryColumns
    val onToggle = playbackActions.onToggle
    val onPrevious = playbackActions.onPrevious
    val onNext = playbackActions.onNext
    val onShuffle = playbackActions.onShuffle
    val onRepeat = playbackActions.onRepeat
    val onVolume = playbackActions.onVolume
    val onSeek = playbackActions.onSeek
    val onCast = playbackActions.onCast
    val onLyrics = playbackActions.onLyrics
    val onEqualizerEnabled = playbackActions.onEqualizerEnabled
    val onEqualizerBandCount = playbackActions.onEqualizerBandCount
    val onEqualizerGain = playbackActions.onEqualizerGain
    val onEqualizerReset = playbackActions.onEqualizerReset
    val onPersistEqualizerSettings = playbackActions.onPersistEqualizerSettings
    val onVisualizerPreset = playbackActions.onVisualizerPreset
    val onShowVisualizerInTvFrame = playbackActions.onShowVisualizerInTvFrame
    val onListenBrainzFeedback = playbackActions.onListenBrainzFeedback
    val onPlayQueue = playbackActions.onPlayQueue
    val onClearQueue = playbackActions.onClearQueue
    val onMoveUpNext = playbackActions.onMoveUpNext
    val onRemoveUpNext = playbackActions.onRemoveUpNext
    val onRetryLyrics = playbackActions.onRetryLyrics
    val onStartSignIn = authSetupActions.onStartSignIn
    val onFinishSignIn = authSetupActions.onFinishSignIn
    val onSignInJellyfin = authSetupActions.onSignInJellyfin
    val onSignInProvider = authSetupActions.onSignInProvider
    val onDiscoverJellyfinServers = authSetupActions.onDiscoverJellyfinServers
    val onStartJellyfinQuickConnect = authSetupActions.onStartJellyfinQuickConnect
    val onFinishJellyfinQuickConnect = authSetupActions.onFinishJellyfinQuickConnect
    val onOpenSignIn = authSetupActions.onOpenSignIn
    val onSignOut = authSetupActions.onSignOut
    val onAddLocalFolder = authSetupActions.onAddLocalFolder
    val onRemoveLocalFolder = authSetupActions.onRemoveLocalFolder
    val onToggleLocalFolder = authSetupActions.onToggleLocalFolder
    val onRefreshLibrary = authSetupActions.onRefreshLibrary
    val onJellyfinPage = authSetupActions.onJellyfinPage
    val onSelectServer = authSetupActions.onSelectServer
    val onSelectLibrary = authSetupActions.onSelectLibrary
    val onCancelPlexSetup = authSetupActions.onCancelPlexSetup
    val onBackToServerPicker = authSetupActions.onBackToServerPicker
    val onRetryServers = authSetupActions.onRetryServers
    val onHomeSections = settingsActions.onHomeSections
    val onMobileBottomTabs = settingsActions.onMobileBottomTabs
    val onPersonalMix = settingsActions.onPersonalMix
    val onAlbumGridItemSize = settingsActions.onAlbumGridItemSize
    val onArtistGridItemSize = settingsActions.onArtistGridItemSize
    val onExportFavoritePlaylists = settingsActions.onExportFavoritePlaylists
    val onImportFavoritePlaylists = settingsActions.onImportFavoritePlaylists
    val onExportRadioStations = settingsActions.onExportRadioStations
    val onImportRadioStations = settingsActions.onImportRadioStations
    val onExportBackupPackage = settingsActions.onExportBackupPackage
    val onImportBackupPackage = settingsActions.onImportBackupPackage
    val onReplaceFromBackupPackage = settingsActions.onReplaceFromBackupPackage
    val onCrossfadeSeconds = settingsActions.onCrossfadeSeconds
    val onScanLibraryOnLaunch = settingsActions.onScanLibraryOnLaunch
    val onNotifyWhenDownloadFinishes = settingsActions.onNotifyWhenDownloadFinishes
    val onKeepPlayingEnabled = settingsActions.onKeepPlayingEnabled
    val onPersistEqualizerSettingsFromSettings = settingsActions.onPersistEqualizerSettings
    val onPersistVolumeSettingsFromSettings = settingsActions.onPersistVolumeSettings
    val onVisualizerPresetFromSettings = settingsActions.onVisualizerPreset
    val onShowVisualizerInTvFrameFromSettings = settingsActions.onShowVisualizerInTvFrame
    val onBlurredArtworkAppearance = settingsActions.onBlurredArtworkAppearance
    val onFullBleedDetailArtwork = settingsActions.onFullBleedDetailArtwork
    val onDownloadDirectory = settingsActions.onDownloadDirectory
    val onDeleteAllDownloads = settingsActions.onDeleteAllDownloads
    val onDeleteCompletedDownloads = settingsActions.onDeleteCompletedDownloads
    val onClearFailedDownloads = settingsActions.onClearFailedDownloads
    val onRetryFailedDownloads = settingsActions.onRetryFailedDownloads
    val onUseLightAppearanceChange = settingsActions.onUseLightAppearanceChange
    val onAppearanceTintChange = settingsActions.onAppearanceTintChange
    val onConnectListenBrainz = settingsActions.onConnectListenBrainz
    val onDisconnectListenBrainz = settingsActions.onDisconnectListenBrainz
    val onListenBrainzSubmitNowPlaying = settingsActions.onListenBrainzSubmitNowPlaying
    val onListenBrainzSubmitListens = settingsActions.onListenBrainzSubmitListens
    val onListenBrainzSubmitCurrentTrackFeedback = settingsActions.onListenBrainzSubmitCurrentTrackFeedback
    val isPlaying = if (hasLivePlayerChrome) playerChrome.isPlaying else shellPlayback.isPlaying
    val isBuffering = if (hasLivePlayerChrome) playerChrome.isBuffering else shellPlayback.isBuffering
    val shuffle = if (hasLivePlayerChrome) playerChrome.shuffle else playerTransport.shuffle
    val repeat = if (hasLivePlayerChrome) playerChrome.repeat else playerTransport.repeat
    val volume = if (hasLivePlayerChrome) playerChrome.volume else playerTransport.volume
    val displayRoutes = routes.ifEmpty { previewRoutesFor(screen, section) }.renderablePhoebeRoutes()
    var desktopUpNextExpanded by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .phoebeShellBackground(
                    tintedGradient = appSettings.tintedBackgroundGradient,
                    center = Offset(420f, 48f),
                    radius = DesktopShellRadialTintRadius,
                    radialTintStrength = DesktopShellRadialTintStrength,
                ),
        ) {
                Row(Modifier.fillMaxSize()) {
                    Sidebar(
                        catalog = catalog,
                        catalogRefreshing = catalogRefreshing,
                        session = session,
                        mediaSources = mediaSources,
                        activeSection = section,
                        selectedPlaylistId = selectedPlaylistId,
                        onNavigate = onNavigate,
                        onPlaylist = onPlaylist,
                        onOpenSignIn = onOpenSignIn,
                        onSignOut = onSignOut,
                        onAddLocalFolder = onAddLocalFolder,
                        onRemoveLocalFolder = onRemoveLocalFolder,
                        onToggleLocalFolder = onToggleLocalFolder,
                        onRefreshLibrary = onRefreshLibrary,
                        tintedBackgroundGradient = appSettings.tintedBackgroundGradient,
                        appUpdateState = shellState.updateState,
                        onInstallUpdate = browseActions.onInstallUpdate,
                    )
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            SharedTransitionLayout(Modifier.weight(1f).fillMaxHeight()) {
                                val sharedTransitionScope = this
                                var previousDisplayRoutes by remember { mutableStateOf(displayRoutes) }
                                var retainedSharedTransition by remember { mutableStateOf<Pair<PhoebeRoute, PhoebeRoute>?>(null) }
                                val routeTransition = remember(displayRoutes, previousDisplayRoutes) {
                                    val from = if (displayRoutes.size == previousDisplayRoutes.size) {
                                        displayRoutes.dropLast(1).lastOrNull()
                                    } else {
                                        previousDisplayRoutes.lastOrNull()
                                    }
                                    val to = displayRoutes.lastOrNull()
                                    if (from != null && to != null) from to to else null
                                }
                                LaunchedEffect(routeTransition) {
                                    val transition = routeTransition ?: return@LaunchedEffect
                                    if (shouldUseDesktopSharedElements(transition.first, transition.second)) {
                                        retainedSharedTransition = transition
                                    }
                                }
                                LaunchedEffect(retainedSharedTransition) {
                                    val transition = retainedSharedTransition ?: return@LaunchedEffect
                                    delay(DesktopSharedTransitionRetainMs)
                                    if (retainedSharedTransition == transition) {
                                        retainedSharedTransition = null
                                    }
                                }
                                SideEffect {
                                    previousDisplayRoutes = displayRoutes
                                }
                                val activeSharedTransition = routeTransition ?: retainedSharedTransition
                                val sharedElementsEnabled = LocalSharedElementTransitionsEnabled.current &&
                                    activeSharedTransition?.let { (from, to) ->
                                        shouldUseDesktopSharedElements(from, to)
                                    } == true
                                CompositionLocalProvider(
                                    LocalSharedTransitionScope provides sharedTransitionScope,
                                    LocalSharedElementTransitionsEnabled provides sharedElementsEnabled,
                                ) {
                                    PhoebeNavDisplay(
                                        backStack = displayRoutes,
                                        modifier = Modifier.fillMaxSize(),
                                        animateTransitions = sharedElementsEnabled,
                                        onBack = onPopDetail,
                                    ) { targetRoute ->
                                        val targetResolution = resolvePhoebeRoute(
                                            targetRoute,
                                            catalog,
                                            track,
                                            showArtistAlbumMixBuilders = showArtistAlbumMixBuilders,
                                        )
                                        val missingRoute = targetResolution as? PhoebeRouteResolution.Missing
                                        val targetScreenRaw = (targetResolution as? PhoebeRouteResolution.Resolved)?.screen ?: AppScreen.Home
                                        // When Artwork is selected, Player screen should not occupy the panel — show existing content instead
                                        val targetScreen = if (targetScreenRaw == AppScreen.Player && !visualizerPreset.isVisualizer) AppScreen.Home else targetScreenRaw
                                        if (missingRoute != null) {
                                            MissingRouteFallback(
                                                title = missingRoute.title,
                                                message = missingRoute.message,
                                                onBack = onPopDetail,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                            when (targetScreen) {
                                is AppScreen.ServerPicker -> PlexServerPickerRoute(
                                    state = PlexServerPickerRouteState(
                                        servers = servers,
                                        busy = busy,
                                        serversLoading = serversLoading,
                                    ),
                                    actions = PlexServerPickerRouteActions(
                                        onSelectServer = onSelectServer,
                                        onCancel = onCancelPlexSetup,
                                        onRetry = onRetryServers,
                                    ),
                                    modifier = Modifier.fillMaxSize(),
                                )
                                is AppScreen.LibraryPicker -> PlexLibraryPickerRoute(
                                    state = PlexLibraryPickerRouteState(
                                        libraries = libraries,
                                        serverName = session?.selectedServer?.name,
                                        providerType = session?.providerType ?: MediaProviderType.Plex,
                                        busy = busy,
                                        librariesLoading = librariesLoading,
                                        isJellyfin = session.isEmbyFamily(),
                                    ),
                                    actions = PlexLibraryPickerRouteActions(
                                        onSelectLibrary = onSelectLibrary,
                                        onBack = onBackToServerPicker,
                                        onCancel = onCancelPlexSetup,
                                    ),
                                    modifier = Modifier.fillMaxSize(),
                                )
                                is AppScreen.SignIn -> AuthWelcomeDesktopRoute(
                                    state = AuthWelcomeRouteState(
                                        message = appMessage,
                                        pinCode = pinCode,
                                        jellyfinServers = jellyfinServers,
                                        jellyfinDiscoveryLoading = jellyfinDiscoveryLoading,
                                        jellyfinQuickConnect = jellyfinQuickConnect,
                                        authInProgress = authInProgress,
                                        showLocalFolderHint = true,
                                    ),
                                    actions = AuthWelcomeRouteActions(
                                        onStartSignIn = onStartSignIn,
                                        onFinishSignIn = onFinishSignIn,
                                        onSignInJellyfin = onSignInJellyfin,
                                        onSignInProvider = onSignInProvider,
                                        onDiscoverJellyfinServers = onDiscoverJellyfinServers,
                                        onStartJellyfinQuickConnect = onStartJellyfinQuickConnect,
                                        onFinishJellyfinQuickConnect = onFinishJellyfinQuickConnect,
                                        onOpenRadio = { onNavigate(BrowseSection.Radio) },
                                    ),
                                    modifier = Modifier.fillMaxSize(),
                                )
                                is AppScreen.ArtistDetail -> Box(Modifier.fillMaxSize()) {
                                    ArtistDetailRoute(
                                        state = ArtistDetailRouteState(
                                            artist = targetScreen.artist,
                                            catalog = catalog,
                                            libraryUi = libraryUi,
                                            catalogRefreshing = catalogRefreshing,
                                            searchQuery = searchQuery,
                                            artistRadioAvailability = artistRadioAvailability[targetScreen.artist.id],
                                            artistRadioStarting = targetScreen.artist.id in radioStartingIds,
                                            fullBleedArtwork = appSettings.fullBleedDetailArtwork,
                                        ),
                                        actions = ArtistDetailRouteActions(
                                            onBack = onPopDetail,
                                            onAlbum = onAlbum,
                                            onPlayTracks = onPlayTracks,
                                            onPlayAllTracks = onPlayAllTracks,
                                            onShuffleAllTracks = onShuffleAllTracks,
                                            onAddToUpNext = onAddToUpNext,
                                            onDownload = onDownload,
                                            onDownloadArtist = onDownloadArtist,
                                            onProbeArtistRadio = onProbeArtistRadio,
                                            onPlayArtistRadio = onPlayArtistRadio,
                                            onArtist = onArtist,
                                            onLibraryColumns = onLibraryColumns,
                                            onCollectionItems = onCollectionValue,
                                            onSearchQuery = onSearchQuery,
                                        ),
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                is AppScreen.AlbumDetail -> Box(Modifier.fillMaxSize()) {
                                    AlbumDetailRoute(
                                        state = AlbumDetailRouteState(
                                            album = targetScreen.album,
                                            catalog = catalog,
                                            libraryUi = libraryUi,
                                            catalogRefreshing = catalogRefreshing,
                                            searchQuery = searchQuery,
                                            fullBleedArtwork = appSettings.fullBleedDetailArtwork,
                                        ),
                                        actions = AlbumDetailRouteActions(
                                            onBack = onPopDetail,
                                            onPlayTracks = onPlayTracks,
                                            onAddToUpNext = onAddToUpNext,
                                            onDownload = onDownload,
                                            onDownloadAlbum = onDownloadAlbum,
                                            onArtist = onArtist,
                                            onLibraryColumns = onLibraryColumns,
                                            onCollectionItems = onCollectionValue,
                                            onSearchQuery = onSearchQuery,
                                        ),
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                is AppScreen.SongDetail -> Column(Modifier.fillMaxSize()) {
                                    LibraryTopBar(searchQuery = searchQuery, onSearchQuery = onSearchQuery)
                                    SongDetailRoute(
                                        state = SongDetailRouteState(track = targetScreen.track),
                                        actions = SongDetailRouteActions(
                                            onBack = onPopDetail,
                                            onPlay = { onPlayTracks(listOf(targetScreen.track), 0) },
                                            onAddToUpNext = onAddToUpNext,
                                            onDownload = onDownload,
                                            onOpenLyrics = onOpenLyrics,
                                        ),
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                    )
                                }
                                is AppScreen.Lyrics -> DesktopPlayerProgressScope(playerFlow, playbackState.player) { positionMs ->
                                    LyricsRoute(
                                        state = LyricsRouteState(
                                            track = lyricsTrack,
                                            currentTrackId = track?.id,
                                            positionMs = positionMs,
                                            loadState = lyricsState,
                                        ),
                                        modifier = Modifier.fillMaxSize(),
                                        onBack = onPopDetail,
                                        onRetry = onRetryLyrics,
                                    )
                                }
                                is AppScreen.RecentlyAdded -> Column(Modifier.fillMaxSize()) {
                                    LibraryTopBar(searchQuery = searchQuery, onSearchQuery = onSearchQuery)
                                    val nowPlaying = LocalNowPlaying.current
                                    RecentlyAddedRoute(
                                        state = RecentlyAddedRouteState(
                                            kind = targetScreen.kind,
                                            catalog = catalog,
                                            nowMs = LocalNowMs.current,
                                            nowPlaying = RecentlyAddedNowPlayingState(
                                                trackId = nowPlaying.trackId,
                                                isPlaying = nowPlaying.isPlaying,
                                                isBuffering = nowPlaying.isBuffering,
                                            ),
                                        ),
                                        actions = RecentlyAddedRouteActions(
                                            onBack = onPopDetail,
                                            onArtist = onArtist,
                                            onAlbum = onAlbum,
                                            onPlayTracks = onPlayTracks,
                                            onAddToUpNext = onAddToUpNext,
                                            onDownload = onDownload,
                                        ),
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                    )
                                }
                                is AppScreen.Collections -> Column(Modifier.fillMaxSize()) {
                                    LibraryTopBar(searchQuery = searchQuery, onSearchQuery = onSearchQuery)
                                    CollectionsRoute(
                                        state = CollectionsRouteState(
                                            entry = targetScreen.entry,
                                            catalog = catalog,
                                            searchQuery = searchQuery,
                                        ),
                                        actions = CollectionsRouteActions(
                                            onBack = onPopDetail,
                                            onCollectionValue = { entry, value -> onCollectionValue(entry, value) },
                                            onEnsureValuesLoaded = { onEnsureCollectionValuesLoaded(targetScreen.entry) },
                                        ),
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                    )
                                }
                                is AppScreen.CollectionItems -> Column(Modifier.fillMaxSize()) {
                                    LibraryTopBar(searchQuery = searchQuery, onSearchQuery = onSearchQuery)
                                    CollectionItemsRoute(
                                        state = CollectionItemsRouteState(
                                            entry = targetScreen.entry,
                                            value = targetScreen.value,
                                            catalog = catalog,
                                            searchQuery = searchQuery,
                                        ),
                                        actions = CollectionItemsRouteActions(
                                            onBack = onPopDetail,
                                            onArtist = onArtist,
                                            onAlbum = onAlbum,
                                            onEnsureItemsLoaded = {
                                                onEnsureCollectionItemsLoaded(targetScreen.entry, targetScreen.value)
                                            },
                                        ),
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                    )
                                }
                                is AppScreen.PlayHistory -> Column(Modifier.fillMaxSize()) {
                                    LibraryTopBar(searchQuery = searchQuery, onSearchQuery = onSearchQuery)
                                    val nowPlaying = LocalNowPlaying.current
                                    val viewModel = remember(routeViewModelFactory, targetScreen.kind) {
                                        routeViewModelFactory.playHistory()
                                    }
                                    PlayHistoryRoute(
                                        viewModel = viewModel,
                                        state = PlayHistoryRouteState(
                                            kind = targetScreen.kind,
                                            catalog = catalog,
                                            playHistory = playHistory,
                                            resolvedTracksById = browseState.resolvedTracksById,
                                            nowPlaying = HistoryNowPlayingState(
                                                trackId = nowPlaying.trackId,
                                                isPlaying = nowPlaying.isPlaying,
                                                isBuffering = nowPlaying.isBuffering,
                                            ),
                                            loadRankedEntries = browseState.loadPlayHistoryEntries,
                                        ),
                                        libraryUi = libraryUi,
                                        onLibraryColumns = onLibraryColumns,
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        preferTableLayout = true,
                                        onBack = onPopDetail,
                                        onPlayTracks = onPlayTracks,
                                        onAddToUpNext = onAddToUpNext,
                                        onDownload = onDownload,
                                    )
                                }
                                AppScreen.FavoritePlaylists -> FavoritePlaylistsDesktopRoute(
                                    state = FavoritePlaylistsRouteState(
                                        playlists = LocalPlaylistActions.current.playlists,
                                        searchQuery = searchQuery,
                                    ),
                                    actions = FavoritePlaylistsRouteActions(
                                        onSearchQuery = onSearchQuery,
                                        onPlaylist = onPlaylist,
                                        onBack = onPopDetail,
                                    ),
                                    modifier = Modifier.fillMaxSize(),
                                )
                                AppScreen.FavoriteArtists -> FavoriteArtistsDesktopRoute(
                                    state = FavoriteArtistsRouteState(
                                        catalog = catalog,
                                        libraryUi = libraryUi,
                                        searchQuery = searchQuery,
                                    ),
                                    actions = FavoriteArtistsRouteActions(
                                        onSearchQuery = onSearchQuery,
                                        onLibrarySortBy = onLibrarySortBy,
                                        onLibraryAscending = onLibraryAscending,
                                        onArtist = onArtist,
                                        onBack = onPopDetail,
                                    ),
                                    modifier = Modifier.fillMaxSize(),
                                )
                                AppScreen.FavoriteAlbums -> FavoriteAlbumsDesktopRoute(
                                    state = FavoriteAlbumsRouteState(
                                        catalog = catalog,
                                        libraryUi = libraryUi,
                                        searchQuery = searchQuery,
                                    ),
                                    actions = FavoriteAlbumsRouteActions(
                                        onSearchQuery = onSearchQuery,
                                        onLibrarySortBy = onLibrarySortBy,
                                        onLibraryAscending = onLibraryAscending,
                                        onAlbum = onAlbum,
                                        onBack = onPopDetail,
                                    ),
                                    modifier = Modifier.fillMaxSize(),
                                )
                                AppScreen.ArtistMixBuilder -> ArtistMixBuilderRoute(
                                    state = MixBuilderRouteState(catalog = catalog),
                                    actions = MixBuilderRouteActions(
                                        onBack = onPopDetail,
                                        onBuildQueue = { tracks -> onPlayTracks(tracks, 0) },
                                        onEnsureArtistSuggestions = onEnsureArtistSuggestions,
                                    ),
                                    modifier = Modifier.fillMaxSize(),
                                )
                                AppScreen.AlbumMixBuilder -> AlbumMixBuilderRoute(
                                    state = MixBuilderRouteState(catalog = catalog),
                                    actions = MixBuilderRouteActions(
                                        onBack = onPopDetail,
                                        onBuildQueue = { tracks -> onPlayTracks(tracks, 0) },
                                    ),
                                    modifier = Modifier.fillMaxSize(),
                                )
                                AppScreen.Player -> DesktopPlayerProgressScope(playerFlow, playbackState.player) { positionMs ->
                                    DesktopVisualizerRoute(
                                        state = DesktopVisualizerRouteState(
                                            track = track,
                                            preset = visualizerPreset,
                                            showInTvFrame = showVisualizerInTvFrame,
                                            audioAnalysis = audioAnalysis,
                                            isPlaying = isPlaying,
                                            positionMs = positionMs,
                                            useFilamentVisualizers = playbackState.useFilamentVisualizers,
                                        ),
                                        onPreset = onVisualizerPreset,
                                        onShowInTvFrameChange = onShowVisualizerInTvFrame,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                else -> when {
                                    section == BrowseSection.Home && selectedPlaylistId == null -> {
                                        val homeListState = RetainedLazyListStates.remember("desktop-home")
                                        DesktopHomeRoute(
                                            state = DesktopHomeRouteState(
                                                home = homeUiState,
                                                catalogRefreshing = catalogRefreshing,
                                                homeSections = libraryUi.homeSections,
                                                supportedCollectionEntries = supportedCollectionEntries,
                                                useBarePanels = appSettings.tintedBackgroundGradient,
                                                posterLoading = browseState.homePosterLoading,
                                                decadeMixNotice = decadeMixNotice,
                                                radioStations = radioStations,
                                                radioStartingIds = radioStartingIds,
                                                showPopularMix = shellState.session.isPlex(),
                                                showArtistAlbumMixBuilders = showArtistAlbumMixBuilders,
                                            ),
                                            actions = DesktopHomeRouteActions(
                                                onTrack = onSong,
                                                onArtist = onArtist,
                                                onAlbum = onAlbum,
                                                onPlaylist = onPlaylist,
                                                onRecentSongs = onRecentSongs,
                                                onRecentArtists = onRecentArtists,
                                                onRecentAlbums = onRecentAlbums,
                                                onFavoritePlaylists = onFavoritePlaylists,
                                                onFavoriteArtists = onFavoriteArtists,
                                                onFavoriteAlbums = onFavoriteAlbums,
                                                onRecentlyPlayed = onRecentlyPlayed,
                                                onMostPlayed = onMostPlayed,
                                                onCollections = onCollections,
                                                onRefreshArtists = onRefreshRandomArtists,
                                                onRefreshAlbums = onRefreshRandomAlbums,
                                                onPrefetchArtist = onPrefetchHomeArtist,
                                                onPrefetchAlbum = onPrefetchHomeAlbum,
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
                                            ),
                                            listState = homeListState,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    section == BrowseSection.Search && selectedPlaylistId == null -> SearchDesktopRoute(
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
                                    )
                                    section == BrowseSection.Library && selectedPlaylistId == null -> {
                                        LibraryDesktopRoute(
                                            state = LibraryRouteState(
                                                catalog = catalog,
                                                catalogRefreshing = catalogRefreshing,
                                                filter = libraryFilter,
                                                libraryUi = libraryUi,
                                                jellyfinPagination = (session.isEmbyFamily() || session.isNavidrome()) && session?.jellyfinSyncMode == JellyfinSyncMode.Quick,
                                                searchQuery = searchQuery,
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
                                                onSearchQuery = onSearchQuery,
                                                onAddToUpNext = onAddToUpNext,
                                                onDownload = onDownload,
                                            ),
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    section == BrowseSection.Lyrics && selectedPlaylistId == null ->
                                        DesktopPlayerProgressScope(playerFlow, playbackState.player) { positionMs ->
                                            LyricsRoute(
                                                state = LyricsRouteState(
                                                    track = lyricsTrack,
                                                    currentTrackId = track?.id,
                                                    positionMs = positionMs,
                                                    loadState = lyricsState,
                                                ),
                                                modifier = Modifier.fillMaxSize(),
                                                onBack = null,
                                                onRetry = onRetryLyrics,
                                            )
                                        }
                                    (section == BrowseSection.Settings || section == BrowseSection.Downloads) && selectedPlaylistId == null -> SettingsDesktopRoute(
                                        state = SettingsRouteState(
                                            isLightMode = useLightAppearance,
                                            tintId = appearanceTintId,
                                            downloadDirectory = downloadDirectory,
                                            downloadCount = downloadCount,
                                            downloadItems = settingsState.downloadItems,
                                            downloadManager = downloadManager,
                                            appSettings = appSettings,
                                            audioProcessingCapabilities = settingsActions.audioProcessingCapabilities,
                                            libraryUi = libraryUi,
                                            defaultDownloadDirectoryLabel = defaultDownloadDirectoryLabel,
                                            homeScreenLayoutMode = settingsState.homeScreenLayoutMode,
                                            session = session,
                                            listenBrainzCredentialAvailability = listenBrainzCredentialAvailability,
                                            appUpdateState = settingsState.appUpdateState,
                                            initialCategory = if (section == BrowseSection.Downloads) {
                                                SettingsCategory.Downloads
                                            } else {
                                                settingsInitialCategory
                                            },
                                        ),
                                        actions = SettingsRouteActions(
                                            onLightModeChange = onUseLightAppearanceChange,
                                            onTintChange = onAppearanceTintChange,
                                            onDownloadDirectory = onDownloadDirectory,
                                            onDeleteAllDownloads = onDeleteAllDownloads,
                                            onDeleteCompletedDownloads = onDeleteCompletedDownloads,
                                            onClearFailedDownloads = onClearFailedDownloads,
                                            onRetryFailedDownloads = onRetryFailedDownloads,
                                            onRetryDownloads = settingsActions.onRetryDownloads,
                                            onCancelDownloads = settingsActions.onCancelDownloads,
                                            onDeleteDownloads = settingsActions.onDeleteDownloads,
                                            onDownloadPolicySettings = settingsActions.onDownloadPolicySettings,
                                            onCrossfadeSeconds = onCrossfadeSeconds,
                                            onScanLibraryOnLaunch = onScanLibraryOnLaunch,
                                            onNotifyWhenDownloadFinishes = onNotifyWhenDownloadFinishes,
                                            onKeepPlayingEnabled = onKeepPlayingEnabled,
                                            onPersistEqualizerSettings = onPersistEqualizerSettingsFromSettings,
                                            onPersistVolumeSettings = onPersistVolumeSettingsFromSettings,
                                            onAudioProcessingSettings = settingsActions.onAudioProcessingSettings,
                                            onVisualizerPreset = onVisualizerPresetFromSettings,
                                            onShowVisualizerInTvFrame = onShowVisualizerInTvFrameFromSettings,
                                            onBlurredArtworkAppearance = onBlurredArtworkAppearance,
                                            onFullBleedDetailArtwork = onFullBleedDetailArtwork,
                                            onTintedBackgroundGradient = settingsActions.onTintedBackgroundGradient,
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
                                            onHomeScreenLayoutModeChange = settingsActions.onHomeScreenLayoutModeChange,
                                            onConnectListenBrainz = onConnectListenBrainz,
                                            onDisconnectListenBrainz = onDisconnectListenBrainz,
                                            onListenBrainzSubmitNowPlaying = onListenBrainzSubmitNowPlaying,
                                            onListenBrainzSubmitListens = onListenBrainzSubmitListens,
                                            onListenBrainzSubmitCurrentTrackFeedback = onListenBrainzSubmitCurrentTrackFeedback,
                                            onStartLastFmAuthorization = settingsActions.onStartLastFmAuthorization,
                                            onFinishLastFmAuthorization = settingsActions.onFinishLastFmAuthorization,
                                            onDisconnectLastFm = settingsActions.onDisconnectLastFm,
                                            onLastFmSubmitNowPlaying = settingsActions.onLastFmSubmitNowPlaying,
                                            onLastFmSubmitScrobbles = settingsActions.onLastFmSubmitScrobbles,
                                            onCheckForUpdates = settingsActions.onCheckForUpdates,
                                            onInstallUpdate = settingsActions.onInstallUpdate,
                                        ),
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
                                        radioDirectory = radioDirectory,
                                        internetRadioStartingIds = internetRadioStartingIds,
                                        onRadioSearch = onRadioSearch,
                                        onRadioLoadMore = onRadioLoadMore,
                                        onRadioRefreshPopular = onRadioRefreshPopular,
                                        onRadioPlay = onRadioPlay,
                                        onRadioCountries = onRadioCountries,
                                        onRadioCountry = onRadioCountry,
                                        onRadioMap = onRadioMap,
                                        onRadioMapSearch = onRadioMapSearch,
                                        onRadioMapCountry = onRadioMapCountry,
                                        onRadioStation = onRadioStation,
                                        onRadioRoot = onRadioRoot,
                                        onRadioAddManualStation = onRadioAddManualStation,
                                        onRadioUpdateManualStation = onRadioUpdateManualStation,
                                        onRadioDeleteManualStation = onRadioDeleteManualStation,
                                        radioRouteMode = radioRouteMode,
                                        onDownloadPlaylist = onDownloadPlaylist,
                                    )
                                }
                            }
                                }
                                    }
                                }
                            }
                            if (showQueue && desktopUpNextExpanded) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .padding(top = 132.dp, bottom = 24.dp)
                                        .width(1.dp)
                                        .background(PhoebeUi.border),
                                )
                                QueueRoute(
                                    state = QueueRouteState(
                                        upNext = upNext,
                                        currentTrack = track,
                                        upNextDivider = upNextDivider,
                                        keepPlayingEnabled = appSettings.keepPlayingEnabled,
                                        currentIndex = currentIndex,
                                        repeat = repeat,
                                        currentTrackClickOpensDetail = true,
                                    ),
                                    actions = QueueRouteActions(
                                        onPlayQueue = onPlayQueue,
                                        onClearQueue = onClearQueue,
                                        onKeepPlayingEnabled = onKeepPlayingEnabled,
                                        onMoveUpNext = onMoveUpNext,
                                        onRemoveUpNext = onRemoveUpNext,
                                        onOpenTrackDetail = onSong,
                                    ),
                                    modifier = Modifier.width(330.dp).fillMaxHeight().padding(start = 24.dp),
                                    listState = rememberLazyListState(),
                                )
                            }
                        }
                        DesktopPlayerProgressScope(playerFlow, playbackState.player) { positionMs, bufferedPositionMs ->
                            DesktopTransport(
                                track = track,
                                isPlaying = isPlaying,
                                isBuffering = isBuffering,
                                positionMs = positionMs,
                                bufferedPositionMs = bufferedPositionMs,
                                shuffle = shuffle,
                                repeat = repeat,
                                volume = volume,
                                castState = castState,
                                remotePlaybackTarget = remotePlaybackTarget,
                                listenBrainzFeedbackTarget = listenBrainzFeedbackTarget,
                                equalizerProfile = equalizerProfile,
                                persistEqualizerSettings = persistEqualizerSettings,
                                equalizerRemoteUnavailable = equalizerRemoteUnavailable,
                                visualizerPreset = visualizerPreset,
                                showVisualizerInTvFrame = showVisualizerInTvFrame,
                                compact = compact,
                                lyricsVisible = section == BrowseSection.Lyrics && selectedPlaylistId == null,
                                upNextVisible = showQueue && desktopUpNextExpanded,
                                upNextToggleEnabled = showQueue,
                                onToggle = onToggle,
                                onPrevious = onPrevious,
                                onNext = onNext,
                                onShuffle = onShuffle,
                                onRepeat = onRepeat,
                                onVolume = onVolume,
                                onSeek = onSeek,
                                onLyrics = onLyrics,
                                onEqualizerEnabled = onEqualizerEnabled,
                                onEqualizerBandCount = onEqualizerBandCount,
                                onEqualizerGain = onEqualizerGain,
                                onEqualizerReset = onEqualizerReset,
                                onPersistEqualizerSettings = onPersistEqualizerSettings,
                                onVisualizerPreset = onVisualizerPreset,
                                onShowVisualizerInTvFrame = onShowVisualizerInTvFrame,
                                onListenBrainzFeedback = onListenBrainzFeedback,
                                onToggleUpNext = { desktopUpNextExpanded = !desktopUpNextExpanded },
                                onCast = onCast,
                            )
                        }
                    }
                }
            }
        }
}

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

private fun shouldUseDesktopSharedElements(initial: PhoebeRoute?, target: PhoebeRoute?): Boolean =
    initial != null &&
        target != null &&
        initial.hasDesktopSharedElements() &&
        target.hasDesktopSharedElements()

private fun PhoebeRoute.hasDesktopSharedElements(): Boolean = when (this) {
    is PhoebeRoute.Browse,
    is PhoebeRoute.AlbumDetail,
    is PhoebeRoute.ArtistAlbumSlugDetail,
    is PhoebeRoute.ArtistDetail,
    is PhoebeRoute.ArtistSlugDetail,
    is PhoebeRoute.CollectionItems,
    is PhoebeRoute.PlayHistory,
    PhoebeRoute.FavoritePlaylists,
    PhoebeRoute.FavoriteArtists,
    PhoebeRoute.FavoriteAlbums,
    is PhoebeRoute.PlaylistDetail,
    is PhoebeRoute.PlaylistSlugDetail,
    PhoebeRoute.RadioCountries,
    PhoebeRoute.RadioGlobe,
    PhoebeRoute.RadioMap,
    is PhoebeRoute.RadioCountry,
    is PhoebeRoute.RadioStation,
    is PhoebeRoute.RecentlyAdded,
    is PhoebeRoute.SongDetail,
    is PhoebeRoute.Lyrics,
    -> true

    is PhoebeRoute.Collections,
    PhoebeRoute.AlbumMixBuilder,
    PhoebeRoute.ArtistMixBuilder,
    PhoebeRoute.LibraryPicker,
    PhoebeRoute.Player,
    PhoebeRoute.ServerPicker,
    PhoebeRoute.SignIn,
    -> false
}

private fun PlayerState.withoutProgressTicks(): PlayerState =
    copy(positionMs = 0L, bufferedPositionMs = 0L)

private fun previewRoutesFor(screen: AppScreen, section: BrowseSection): List<PhoebeRoute> {
    val root = PhoebeRoute.Browse(section)
    val route = when (screen) {
        AppScreen.SignIn -> return listOf(PhoebeRoute.SignIn)
        AppScreen.ServerPicker -> return listOf(PhoebeRoute.SignIn, PhoebeRoute.ServerPicker)
        AppScreen.LibraryPicker -> return listOf(PhoebeRoute.SignIn, PhoebeRoute.ServerPicker, PhoebeRoute.LibraryPicker)
        AppScreen.Home -> root
        is AppScreen.Collections -> PhoebeRoute.Collections(screen.entry)
        is AppScreen.CollectionItems -> PhoebeRoute.CollectionItems(screen.entry, screen.value)
        is AppScreen.AlbumDetail -> PhoebeRoute.AlbumDetail(screen.album.id)
        is AppScreen.ArtistDetail -> PhoebeRoute.ArtistDetail(screen.artist.id)
        is AppScreen.SongDetail -> PhoebeRoute.SongDetail(screen.track.id)
        is AppScreen.Lyrics -> PhoebeRoute.Lyrics(screen.track?.id)
        is AppScreen.RecentlyAdded -> PhoebeRoute.RecentlyAdded(screen.kind)
        is AppScreen.PlayHistory -> PhoebeRoute.PlayHistory(screen.kind)
        AppScreen.FavoritePlaylists -> PhoebeRoute.FavoritePlaylists
        AppScreen.FavoriteArtists -> PhoebeRoute.FavoriteArtists
        AppScreen.FavoriteAlbums -> PhoebeRoute.FavoriteAlbums
        AppScreen.ArtistMixBuilder -> PhoebeRoute.ArtistMixBuilder
        AppScreen.AlbumMixBuilder -> PhoebeRoute.AlbumMixBuilder
        is AppScreen.PlaylistDetail -> PhoebeRoute.PlaylistDetail(screen.playlist.id)
        AppScreen.Player -> PhoebeRoute.Player
    }
    return if (route == root) listOf(root) else listOf(root, route)
}

@Composable
private fun DesktopPlayerProgressScope(
    playerFlow: StateFlow<PlayerState>?,
    fallback: PlayerState,
    content: @Composable (positionMs: Long, bufferedPositionMs: Long) -> Unit,
) {
    if (playerFlow == null) {
        content(fallback.positionMs, fallback.bufferedPositionMs)
        return
    }
    val player by playerFlow.collectAsState()
    content(player.positionMs, player.bufferedPositionMs)
}

@Composable
private fun DesktopPlayerProgressScope(
    playerFlow: StateFlow<PlayerState>?,
    fallback: PlayerState,
    content: @Composable (positionMs: Long) -> Unit,
) {
    DesktopPlayerProgressScope(playerFlow, fallback) { positionMs, _ ->
        content(positionMs)
    }
}
