package com.phoebe.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
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
import com.phoebe.app.data.JellyfinQuickConnectResult
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
import com.phoebe.app.domain.LyricsLoadState
import com.phoebe.app.domain.MediaProviderType
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
import com.phoebe.app.domain.isEmbyFamily
import com.phoebe.app.domain.isLocalMediaPlayback
import com.phoebe.app.domain.isJellyfin
import com.phoebe.app.domain.isNavidrome
import com.phoebe.app.player.CastState
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun DesktopPlayer(
    screen: AppScreen,
    catalog: CatalogSnapshot,
    catalogRefreshing: Boolean,
    session: PlexSession?,
    mediaSources: MediaSourcesState,
    track: Track?,
    homeUiState: HomeUiState,
    playHistory: PlayHistorySnapshot,
    upNext: List<Track>,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    positionMs: Long,
    bufferedPositionMs: Long,
    currentIndex: Int,
    lyricsTrack: Track? = null,
    lyricsState: LyricsLoadState = LyricsLoadState.Idle,
    section: DesktopSection,
    selectedPlaylistId: String?,
    searchQuery: String,
    libraryFilter: LibraryFilterTab,
    libraryUi: LibraryUiPreferences,
    appMessage: String,
    pinCode: String?,
    shuffle: Boolean,
    repeat: RepeatMode,
    volume: Float,
    castState: CastState = CastState(),
    remotePlaybackTarget: String? = null,
    showQueue: Boolean,
    compact: Boolean,
    busy: Boolean,
    serversLoading: Boolean = false,
    onNavigate: (DesktopSection) -> Unit,
    onSearchQuery: (String) -> Unit,
    onLibraryFilter: (LibraryFilterTab) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onSong: (Track) -> Unit,
    onOpenLyrics: (Track) -> Unit = {},
    onRecentSongs: () -> Unit,
    onRecentArtists: () -> Unit,
    onRecentAlbums: () -> Unit,
    onFavoritePlaylists: () -> Unit = {},
    onFavoriteArtists: () -> Unit = {},
    onFavoriteAlbums: () -> Unit = {},
    onRecentlyPlayed: () -> Unit,
    onMostPlayed: () -> Unit,
    onCollections: (CollectionEntry) -> Unit,
    onCollectionValue: (CollectionEntry, String) -> Unit,
    supportedCollectionEntries: Set<CollectionEntry> = defaultCollectionEntries.toSet(),
    onRefreshRandomArtists: () -> Unit,
    onRefreshRandomAlbums: () -> Unit,
    onPrefetchHomeArtist: (Artist) -> Unit = {},
    onPrefetchHomeAlbum: (Album) -> Unit = {},
    onPlayDecadeMix: (Int) -> Unit = {},
    decadeMixNotice: String? = null,
    onClearDecadeMixNotice: () -> Unit = {},
    radioStations: List<PlexRadioStation> = emptyList(),
    onPlayRadioStation: (PlexRadioStation) -> Unit = {},
    onPlayPersonalMix: () -> Unit = {},
    onPopDetail: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onVolume: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onCast: () -> Unit = {},
    onLyrics: () -> Unit = {},
    onPlayQueue: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onMoveUpNext: (Int, Int) -> Unit,
    onRemoveUpNext: (Int) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onDownloadArtist: (Artist) -> Unit,
    artistRadioAvailability: Map<String, ArtistRadioAvailability> = emptyMap(),
    radioStartingIds: Set<String> = emptySet(),
    onProbeArtistRadio: (Artist) -> Unit = {},
    onPlayArtistRadio: (Artist) -> Unit,
    onDownloadAlbum: (Album) -> Unit,
    onDownloadPlaylist: (Playlist) -> Unit,
    onStartSignIn: () -> Unit,
    onFinishSignIn: () -> Unit,
    onSignInJellyfin: (String, String, String) -> Unit,
    onSignInProvider: (MediaProviderType, String, String, String, JellyfinSyncMode?) -> Unit = { _, _, _, _, _ -> },
    jellyfinServers: List<PlexServer> = emptyList(),
    jellyfinDiscoveryLoading: Boolean = false,
    jellyfinQuickConnect: JellyfinQuickConnectResult? = null,
    onDiscoverJellyfinServers: () -> Unit = {},
    onStartJellyfinQuickConnect: (String) -> Unit = {},
    onFinishJellyfinQuickConnect: () -> Unit = {},
    onSignOut: () -> Unit,
    onAddLocalFolder: (String?) -> Unit,
    onRemoveLocalFolder: (String) -> Unit,
    onToggleLocalFolder: (String, Boolean) -> Unit,
    onRefreshLibrary: () -> Unit,
    onJellyfinPage: (JellyfinLibraryPageKind, Int) -> Unit = { _, _ -> },
    servers: List<PlexServer>,
    libraries: List<MusicLibrary>,
    librariesLoading: Boolean = false,
    onSelectServer: (PlexServer) -> Unit,
    onSelectLibrary: (MusicLibrary, JellyfinSyncMode?) -> Unit,
    onCancelPlexSetup: () -> Unit,
    onBackToServerPicker: () -> Unit,
    onRetryServers: () -> Unit,
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
    settingsInitialCategory: SettingsCategory = SettingsCategory.AudioPlayback,
    onRetryLyrics: () -> Unit = {},
) {
    var desktopUpNextExpanded by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier
                .fillMaxSize(),
            shape = RoundedCornerShape(0.dp),
            color = Color.Transparent,
        ) {
            Box(
                Modifier.background(
                    Brush.radialGradient(
                        colors = listOf(PhoebeUi.shellRadialTint, Color.Transparent),
                        center = Offset(500f, 20f),
                        radius = 560f,
                    ),
                ).background(Brush.verticalGradient(listOf(PhoebeUi.shellTop, PhoebeUi.shellBottom)))
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
                        onSignOut = onSignOut,
                        onAddLocalFolder = onAddLocalFolder,
                        onRemoveLocalFolder = onRemoveLocalFolder,
                        onToggleLocalFolder = onToggleLocalFolder,
                        onRefreshLibrary = onRefreshLibrary,
                    )
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            SharedTransitionLayout(Modifier.weight(1f).fillMaxHeight()) {
                                val sharedTransitionScope = this
                                var previousScreen by remember { mutableStateOf<AppScreen?>(null) }
                                val sharedElementsEnabled = LocalSharedElementTransitionsEnabled.current &&
                                    shouldUseDesktopSharedElements(previousScreen, screen)
                                LaunchedEffect(screen) {
                                    previousScreen = screen
                                }
                                CompositionLocalProvider(
                                    LocalSharedTransitionScope provides sharedTransitionScope,
                                    LocalSharedElementTransitionsEnabled provides sharedElementsEnabled,
                                ) {
                                    AnimatedContent(
                                        targetState = screen,
                                        modifier = Modifier.fillMaxSize(),
                                        transitionSpec = {
                                            fadeIn(tween(180, easing = FastOutSlowInEasing)) togetherWith
                                                fadeOut(tween(160, easing = FastOutSlowInEasing))
                                        },
                                        label = "desktop-screen",
                                    ) { targetScreen ->
                                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@AnimatedContent) {
                            when (targetScreen) {
                                is AppScreen.ServerPicker -> PlexServerPickerPanel(
                                    servers = servers,
                                    busy = busy,
                                    serversLoading = serversLoading,
                                    onSelectServer = onSelectServer,
                                    onCancel = onCancelPlexSetup,
                                    onRetry = onRetryServers,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                is AppScreen.LibraryPicker -> PlexLibraryPickerPanel(
                                    libraries = libraries,
                                    serverName = session?.selectedServer?.name,
                                    providerType = session?.providerType ?: MediaProviderType.Plex,
                                    busy = busy,
                                    librariesLoading = librariesLoading,
                                    isJellyfin = session.isEmbyFamily(),
                                    onSelectLibrary = onSelectLibrary,
                                    onBack = onBackToServerPicker,
                                    onCancel = onCancelPlexSetup,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                is AppScreen.SignIn -> SignInWelcomeScreen(
                                    message = appMessage,
                                    pinCode = pinCode,
                                    jellyfinServers = jellyfinServers,
                                    jellyfinDiscoveryLoading = jellyfinDiscoveryLoading,
                                    jellyfinQuickConnect = jellyfinQuickConnect,
                                    onStartSignIn = onStartSignIn,
                                    onFinishSignIn = onFinishSignIn,
                                    onSignInJellyfin = onSignInJellyfin,
                                    onSignInProvider = onSignInProvider,
                                    onDiscoverJellyfinServers = onDiscoverJellyfinServers,
                                    onStartJellyfinQuickConnect = onStartJellyfinQuickConnect,
                                    onFinishJellyfinQuickConnect = onFinishJellyfinQuickConnect,
                                    showLocalFolderHint = true,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                is AppScreen.ArtistDetail -> Column(Modifier.fillMaxSize()) {
                                    LibraryTopBar(searchQuery = searchQuery, onSearchQuery = onSearchQuery)
                                    ArtistDetailPanel(
                                        artist = targetScreen.artist,
                                        catalog = catalog,
                                        libraryUi = libraryUi,
                                        catalogRefreshing = catalogRefreshing,
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        searchQuery = searchQuery,
                                        onBack = onPopDetail,
                                        onAlbum = onAlbum,
                                        onPlayTracks = onPlayTracks,
                                        onAddToUpNext = onAddToUpNext,
                                        onDownload = onDownload,
                                        onDownloadArtist = onDownloadArtist,
                                        artistRadioAvailability = artistRadioAvailability[targetScreen.artist.id],
                                        artistRadioStarting = targetScreen.artist.id in radioStartingIds,
                                        onProbeArtistRadio = onProbeArtistRadio,
                                        onPlayArtistRadio = onPlayArtistRadio,
                                        onArtist = onArtist,
                                        onLibraryColumns = onLibraryColumns,
                                    )
                                }
                                is AppScreen.AlbumDetail -> Column(Modifier.fillMaxSize()) {
                                    LibraryTopBar(searchQuery = searchQuery, onSearchQuery = onSearchQuery)
                                    AlbumDetailPanel(
                                        album = targetScreen.album,
                                        catalog = catalog,
                                        libraryUi = libraryUi,
                                        catalogRefreshing = catalogRefreshing,
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        searchQuery = searchQuery,
                                        onBack = onPopDetail,
                                        onPlayTracks = onPlayTracks,
                                        onAddToUpNext = onAddToUpNext,
                                        onDownload = onDownload,
                                        onDownloadAlbum = onDownloadAlbum,
                                        onLibraryColumns = onLibraryColumns,
                                    )
                                }
                                is AppScreen.SongDetail -> Column(Modifier.fillMaxSize()) {
                                    LibraryTopBar(searchQuery = searchQuery, onSearchQuery = onSearchQuery)
                                    SongDetailPanel(
                                        track = targetScreen.track,
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        onBack = onPopDetail,
                                        onPlay = { onPlayTracks(listOf(targetScreen.track), 0) },
                                        onAddToUpNext = onAddToUpNext,
                                        onDownload = onDownload,
                                        onOpenLyrics = onOpenLyrics,
                                    )
                                }
                                is AppScreen.Lyrics -> LyricsView(
                                    track = lyricsTrack,
                                    currentTrackId = track?.id,
                                    positionMs = positionMs,
                                    state = lyricsState,
                                    modifier = Modifier.fillMaxSize(),
                                    onBack = onPopDetail,
                                    onRetry = onRetryLyrics,
                                )
                                is AppScreen.RecentlyAdded -> Column(Modifier.fillMaxSize()) {
                                    LibraryTopBar(searchQuery = searchQuery, onSearchQuery = onSearchQuery)
                                    RecentlyAddedScreen(
                                        kind = targetScreen.kind,
                                        catalog = catalog,
                                        nowMs = LocalNowMs.current,
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        onBack = onPopDetail,
                                        onArtist = onArtist,
                                        onAlbum = onAlbum,
                                        onPlayTracks = onPlayTracks,
                                        onAddToUpNext = onAddToUpNext,
                                        onDownload = onDownload,
                                    )
                                }
                                is AppScreen.Collections -> Column(Modifier.fillMaxSize()) {
                                    LibraryTopBar(searchQuery = searchQuery, onSearchQuery = onSearchQuery)
                                    CollectionsScreen(
                                        entry = targetScreen.entry,
                                        catalog = catalog,
                                        searchQuery = searchQuery,
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        onBack = onPopDetail,
                                        onCollectionValue = { entry, value -> onCollectionValue(entry, value) },
                                    )
                                }
                                is AppScreen.CollectionItems -> Column(Modifier.fillMaxSize()) {
                                    LibraryTopBar(searchQuery = searchQuery, onSearchQuery = onSearchQuery)
                                    CollectionItemsScreen(
                                        entry = targetScreen.entry,
                                        value = targetScreen.value,
                                        catalog = catalog,
                                        searchQuery = searchQuery,
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        onBack = onPopDetail,
                                        onArtist = onArtist,
                                        onAlbum = onAlbum,
                                    )
                                }
                                is AppScreen.PlayHistory -> Column(Modifier.fillMaxSize()) {
                                    LibraryTopBar(searchQuery = searchQuery, onSearchQuery = onSearchQuery)
                                    PlayHistoryScreen(
                                        kind = targetScreen.kind,
                                        catalog = catalog,
                                        playHistory = playHistory,
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        onBack = onPopDetail,
                                        onPlayTracks = onPlayTracks,
                                        onAddToUpNext = onAddToUpNext,
                                        onDownload = onDownload,
                                    )
                                }
                                AppScreen.FavoritePlaylists -> FavoritePlaylistsDesktopView(
                                    playlists = LocalPlaylistActions.current.playlists,
                                    searchQuery = searchQuery,
                                    onSearchQuery = onSearchQuery,
                                    onPlaylist = onPlaylist,
                                    onBack = onPopDetail,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                AppScreen.FavoriteArtists -> FavoriteArtistsDesktopView(
                                    catalog = catalog,
                                    libraryUi = libraryUi,
                                    searchQuery = searchQuery,
                                    onSearchQuery = onSearchQuery,
                                    onLibrarySortBy = onLibrarySortBy,
                                    onLibraryAscending = onLibraryAscending,
                                    onArtist = onArtist,
                                    onBack = onPopDetail,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                AppScreen.FavoriteAlbums -> FavoriteAlbumsDesktopView(
                                    catalog = catalog,
                                    libraryUi = libraryUi,
                                    searchQuery = searchQuery,
                                    onSearchQuery = onSearchQuery,
                                    onLibrarySortBy = onLibrarySortBy,
                                    onLibraryAscending = onLibraryAscending,
                                    onAlbum = onAlbum,
                                    onBack = onPopDetail,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                else -> when {
                                    section == DesktopSection.Home && selectedPlaylistId == null -> {
                                        val homeListState = RetainedLazyListStates.remember("desktop-home")
                                        DesktopHomeScreen(
                                        state = homeUiState,
                                        catalogRefreshing = catalogRefreshing,
                                        listState = homeListState,
                                        modifier = Modifier.fillMaxSize(),
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
                                    section == DesktopSection.Search && selectedPlaylistId == null -> SearchDesktopView(
                                        catalog = catalog,
                                        catalogRefreshing = catalogRefreshing,
                                        searchQuery = searchQuery,
                                        modifier = Modifier.fillMaxSize(),
                                        onSearchQuery = onSearchQuery,
                                        onArtist = onArtist,
                                        onAlbum = onAlbum,
                                        onPlayTracks = onPlayTracks,
                                        onAddToUpNext = onAddToUpNext,
                                        onDownload = onDownload,
                                    )
                                    section == DesktopSection.Library && selectedPlaylistId == null -> {
                                        LibraryDesktopView(
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
                                            searchQuery = searchQuery,
                                            onSearchQuery = onSearchQuery,
                                            onAddToUpNext = onAddToUpNext,
                                            onDownload = onDownload,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    section == DesktopSection.Lyrics && selectedPlaylistId == null -> LyricsView(
                                        track = lyricsTrack,
                                        currentTrackId = track?.id,
                                        positionMs = positionMs,
                                        state = lyricsState,
                                        modifier = Modifier.fillMaxSize(),
                                        onBack = null,
                                        onRetry = onRetryLyrics,
                                    )
                                    section == DesktopSection.Settings && selectedPlaylistId == null -> SettingsDesktopView(
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
                                        initialCategory = settingsInitialCategory,
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
                                QueuePanel(
                                    upNext = upNext,
                                    currentTrack = track,
                                    repeat = repeat,
                                    modifier = Modifier.width(330.dp).fillMaxHeight().padding(start = 24.dp),
                                    onPlayQueue = onPlayQueue,
                                    onClearQueue = onClearQueue,
                                    onMoveUpNext = onMoveUpNext,
                                    onRemoveUpNext = onRemoveUpNext,
                                    onOpenTrackDetail = onSong,
                                    currentTrackClickOpensDetail = true,
                                )
                            }
                        }
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
                            compact = compact,
                            lyricsVisible = section == DesktopSection.Lyrics && selectedPlaylistId == null,
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
                            onToggleUpNext = { desktopUpNextExpanded = !desktopUpNextExpanded },
                            onCast = onCast,
                        )
                    }
                }
            }
        }
    }
}

private fun shouldUseDesktopSharedElements(initial: AppScreen?, target: AppScreen): Boolean =
    initial != null &&
        initial.hasDesktopSharedElements() &&
        target.hasDesktopSharedElements()

private fun AppScreen.hasDesktopSharedElements(): Boolean = when (this) {
    AppScreen.Home,
    is AppScreen.AlbumDetail,
    is AppScreen.ArtistDetail,
    is AppScreen.CollectionItems,
    is AppScreen.PlayHistory,
    AppScreen.FavoritePlaylists,
    AppScreen.FavoriteArtists,
    AppScreen.FavoriteAlbums,
    is AppScreen.PlaylistDetail,
    is AppScreen.RecentlyAdded,
    is AppScreen.SongDetail,
    is AppScreen.Lyrics,
    -> true

    is AppScreen.Collections,
    AppScreen.LibraryPicker,
    AppScreen.Player,
    AppScreen.ServerPicker,
    AppScreen.SignIn,
    -> false
}
