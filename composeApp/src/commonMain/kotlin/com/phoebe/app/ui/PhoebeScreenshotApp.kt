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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.phoebe.app.data.defaultPlexRadioStations
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.AppSettings
import com.phoebe.app.domain.AppScreen
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.ArtistRadioAvailability
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.CollectionFacet
import com.phoebe.app.domain.CollectionTarget
import com.phoebe.app.domain.DownloadItem
import com.phoebe.app.domain.DownloadState
import com.phoebe.app.domain.HomeSection
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.LocalFolderMediaSourceConfig
import com.phoebe.app.domain.MediaSourcesState
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexRadioStation
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

internal enum class PhoebeScreenshotScenario {
    Home,
    HomePlayedRows,
    FavoritePlaylists,
    FavoriteArtists,
    FavoriteAlbums,
    Library,
    Playlist,
    Artist,
    ArtistRadio,
    Album,
    CollectionValues,
    CollectionItems,
    Song,
    Search,
    Player,
    PlayerUpNextExpanded,
    Settings,
    SignIn,
    SignInProviders,
}

@Composable
internal fun PhoebeScreenshotApp(
    scenario: PhoebeScreenshotScenario,
    useLightAppearance: Boolean = false,
    tintId: String = PhoebeTintOption.Purple.id,
    forceShowQueue: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val fixture = remember { PhoebeScreenshotFixture }
    val settingsInitialCategory = if (scenario == PhoebeScreenshotScenario.Settings && tintId != PhoebeTintOption.Purple.id) {
        SettingsCategory.Appearance
    } else {
        SettingsCategory.AudioPlayback
    }
    PhoebeTheme(useLightAppearance = useLightAppearance, tintId = tintId) {
        CompositionLocalProvider(
            LocalCatalogHasContent provides true,
            LocalNowPlaying provides NowPlayingIndicatorState(
                trackId = fixture.currentTrack.id,
                isPlaying = false,
            ),
            LocalPlayHistory provides fixture.playHistory,
            LocalNowMs provides fixture.nowMs,
            LocalPlaylistActions provides PlaylistActions(
                playlists = fixture.catalog.playlists,
                playlistsEnabled = true,
            ),
            LocalLikeActions provides LikeActions(
                likedTrackIds = setOf(fixture.currentTrack.id),
                likesEnabled = true,
            ),
            LocalDownloadStatus provides DownloadStatusSnapshot(
                itemsByTrackId = fixture.catalog.downloads.associateBy { it.trackId },
            ),
            LocalMetadataEditorActions provides MetadataEditorActions(),
            LocalSearchHistory provides SearchHistoryState(
                recentSearches = listOf("moon", "quartet", "field recordings"),
                commitSearch = {},
                removeSearch = {},
                clearSearches = {},
            ),
            LocalDragDrop provides DragDropController(),
            LocalSharedElementTransitionsEnabled provides false,
            LocalContinuousMotionEnabled provides false,
        ) {
            BoxWithConstraints(modifier.fillMaxSize()) {
                if (maxWidth < 900.dp) {
                    PhoebeMobileScreenshotScenario(scenario, fixture, Modifier.fillMaxSize())
                } else {
                    val wideDesktop = maxWidth >= 1280.dp
                    PhoebeDesktopScreenshotScenario(
                        scenario = scenario,
                        fixture = fixture,
                        useLightAppearance = useLightAppearance,
                        tintId = tintId,
                        settingsInitialCategory = settingsInitialCategory,
                        showQueue = wideDesktop || forceShowQueue,
                        compact = !wideDesktop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                DragGhost()
            }
        }
    }
}

@Composable
internal fun PhoebeDesktopScreenshotScenario(
    scenario: PhoebeScreenshotScenario,
    fixture: PhoebeScreenshotFixtureData,
    useLightAppearance: Boolean,
    tintId: String = PhoebeTintOption.Purple.id,
    settingsInitialCategory: SettingsCategory = SettingsCategory.AudioPlayback,
    showQueue: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val screen = when (scenario) {
        PhoebeScreenshotScenario.FavoritePlaylists -> AppScreen.FavoritePlaylists
        PhoebeScreenshotScenario.FavoriteArtists -> AppScreen.FavoriteArtists
        PhoebeScreenshotScenario.FavoriteAlbums -> AppScreen.FavoriteAlbums
        PhoebeScreenshotScenario.Artist,
        PhoebeScreenshotScenario.ArtistRadio,
        -> AppScreen.ArtistDetail(fixture.artist)
        PhoebeScreenshotScenario.Album -> AppScreen.AlbumDetail(fixture.album)
        PhoebeScreenshotScenario.CollectionValues -> AppScreen.Collections(CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre))
        PhoebeScreenshotScenario.CollectionItems -> AppScreen.CollectionItems(CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre), "Dream pop")
        PhoebeScreenshotScenario.Song -> AppScreen.SongDetail(fixture.currentTrack)
        PhoebeScreenshotScenario.SignIn,
        PhoebeScreenshotScenario.SignInProviders,
        -> AppScreen.SignIn
        else -> AppScreen.Home
    }
    val section = when (scenario) {
        PhoebeScreenshotScenario.Library -> DesktopSection.Library
        PhoebeScreenshotScenario.Playlist -> DesktopSection.Library
        PhoebeScreenshotScenario.Search -> DesktopSection.Search
        PhoebeScreenshotScenario.Settings -> DesktopSection.Settings
        else -> DesktopSection.Home
    }
    val libraryUi = when (scenario) {
        PhoebeScreenshotScenario.HomePlayedRows -> fixture.libraryUi.copy(
            homeSections = listOf(HomeSection.Played, HomeSection.Random),
        )
        else -> fixture.libraryUi
    }
    DesktopPlayer(
        screen = screen,
        catalog = fixture.catalog,
        catalogRefreshing = false,
        session = fixture.session,
        mediaSources = fixture.mediaSources,
        track = fixture.currentTrack,
        homeUiState = deriveHomeUiState(fixture.catalog, fixture.playHistory, randomArtistSeed = 7, randomAlbumSeed = 11, nowMs = fixture.nowMs),
        playHistory = fixture.playHistory,
        upNext = fixture.upNext,
        isPlaying = true,
        positionMs = 96_000L,
        bufferedPositionMs = 172_000L,
        currentIndex = 0,
        section = section,
        selectedPlaylistId = if (scenario == PhoebeScreenshotScenario.Playlist) fixture.playlist.id else null,
        searchQuery = if (scenario == PhoebeScreenshotScenario.Search) "moon" else "",
        libraryFilter = LibraryFilterTab.Artists,
        libraryUi = libraryUi,
        appMessage = "Sign in to Plex or add a local music folder to get started.",
        pinCode = "PHOEBE",
        shuffle = true,
        repeat = RepeatMode.All,
        volume = 0.72f,
        showQueue = showQueue,
        compact = compact,
        busy = false,
        onNavigate = {},
        onSearchQuery = {},
        onLibraryFilter = {},
        onPlaylist = {},
        onArtist = {},
        onAlbum = {},
        onSong = {},
        onRecentSongs = {},
        onRecentArtists = {},
        onRecentAlbums = {},
        onFavoritePlaylists = {},
        onFavoriteArtists = {},
        onFavoriteAlbums = {},
        onCollections = {},
        onCollectionValue = { _, _ -> },
        onRecentlyPlayed = {},
        onMostPlayed = {},
        onRefreshRandomArtists = {},
        onRefreshRandomAlbums = {},
        onPopDetail = {},
        onToggle = {},
        onPrevious = {},
        onNext = {},
        onShuffle = {},
        onRepeat = {},
        onVolume = {},
        onSeek = {},
        onPlayQueue = {},
        onClearQueue = {},
        onMoveUpNext = { _, _ -> },
        onRemoveUpNext = {},
        onPlayTracks = { _, _ -> },
        onAddToUpNext = {},
        onDownload = {},
        onDownloadArtist = {},
        artistRadioAvailability = if (scenario == PhoebeScreenshotScenario.ArtistRadio) {
            mapOf(fixture.artist.id to ArtistRadioAvailability.Available)
        } else {
            emptyMap()
        },
        onPlayArtistRadio = {},
        radioStations = fixture.radioStations,
        onPlayRadioStation = {},
        onDownloadAlbum = {},
        onDownloadPlaylist = {},
        onStartSignIn = {},
        onFinishSignIn = {},
        onSignInJellyfin = { _, _, _ -> },
        onSignInProvider = { _, _, _, _, _ -> },
        onSignOut = {},
        onAddLocalFolder = {},
        onRemoveLocalFolder = {},
        onToggleLocalFolder = { _, _ -> },
        onRefreshLibrary = {},
        servers = fixture.servers,
        libraries = fixture.libraries,
        onSelectServer = {},
        onSelectLibrary = { _, _ -> },
        onCancelPlexSetup = {},
        onBackToServerPicker = {},
        onRetryServers = {},
        onLibrarySortBy = {},
        onLibraryAscending = {},
        onLibraryColumns = {},
        onHomeSections = {},
        onPersonalMix = {},
        onExportFavoritePlaylists = {},
        onImportFavoritePlaylists = {},
        appSettings = AppSettings.Default,
        onCrossfadeSeconds = {},
        onScanLibraryOnLaunch = {},
        onNotifyWhenDownloadFinishes = {},
        downloadDirectory = null,
        downloadCount = fixture.catalog.downloads.size,
        defaultDownloadDirectoryLabel = "App storage",
        onDownloadDirectory = {},
        onDeleteAllDownloads = {},
        useLightAppearance = useLightAppearance,
        onUseLightAppearanceChange = {},
        appearanceTintId = tintId,
        onAppearanceTintChange = {},
        settingsInitialCategory = settingsInitialCategory,
    )
}

@Composable
internal fun PhoebeMobileScreenshotScenario(
    scenario: PhoebeScreenshotScenario,
    fixture: PhoebeScreenshotFixtureData,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(PhoebeUi.shellTop)
            .statusBarsPadding(),
    ) {
        when (scenario) {
            PhoebeScreenshotScenario.SignIn -> MobileSignInWelcomeScreen(
                message = "Sign in to Plex or add a local music folder to get started.",
                pinCode = "PHOEBE",
                jellyfinServers = emptyList(),
                jellyfinDiscoveryLoading = false,
                jellyfinQuickConnect = null,
                onStartSignIn = {},
                onFinishSignIn = {},
                onSignInJellyfin = { _, _, _ -> },
                onSignInProvider = { _, _, _, _, _ -> },
                onDiscoverJellyfinServers = {},
                onStartJellyfinQuickConnect = {},
                onFinishJellyfinQuickConnect = {},
                onAddLocalFolder = {},
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.SignInProviders -> MobileSignInWelcomeScreen(
                message = "Sign in to Plex, Jellyfin, or another media provider—or add a local music folder to get started.",
                pinCode = null,
                jellyfinServers = emptyList(),
                jellyfinDiscoveryLoading = false,
                jellyfinQuickConnect = null,
                onStartSignIn = {},
                onFinishSignIn = {},
                onSignInJellyfin = { _, _, _ -> },
                onSignInProvider = { _, _, _, _, _ -> },
                onDiscoverJellyfinServers = {},
                onStartJellyfinQuickConnect = {},
                onFinishJellyfinQuickConnect = {},
                onAddLocalFolder = {},
                initialProvidersExpanded = true,
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.FavoritePlaylists -> FavoritePlaylistsMobileView(
                searchQuery = "",
                onSearchQuery = {},
                onPlaylist = {},
                onBack = {},
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.FavoriteArtists -> FavoriteArtistsMobileView(
                catalog = fixture.catalog,
                libraryUi = fixture.libraryUi,
                onLibrarySortBy = {},
                onLibraryAscending = {},
                onLibraryColumns = {},
                onArtist = {},
                onBack = {},
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.FavoriteAlbums -> FavoriteAlbumsMobileView(
                catalog = fixture.catalog,
                libraryUi = fixture.libraryUi,
                onLibrarySortBy = {},
                onLibraryAscending = {},
                onLibraryColumns = {},
                onAlbum = {},
                onBack = {},
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.Artist,
            PhoebeScreenshotScenario.ArtistRadio,
            -> ArtistDetailPanel(
                artist = fixture.artist,
                catalog = fixture.catalog,
                libraryUi = fixture.libraryUi,
                modifier = Modifier.fillMaxSize(),
                onBack = {},
                onAlbum = {},
                onPlayTracks = { _, _ -> },
                onAddToUpNext = {},
                onDownload = {},
                onDownloadArtist = {},
                artistRadioAvailability = if (scenario == PhoebeScreenshotScenario.ArtistRadio) {
                    ArtistRadioAvailability.Available
                } else {
                    null
                },
                onPlayArtistRadio = {},
                onArtist = {},
                onLibraryColumns = {},
            )
            PhoebeScreenshotScenario.Album -> AlbumDetailPanel(
                album = fixture.album,
                catalog = fixture.catalog,
                libraryUi = fixture.libraryUi,
                modifier = Modifier.fillMaxSize(),
                onBack = {},
                onPlayTracks = { _, _ -> },
                onAddToUpNext = {},
                onDownload = {},
                onDownloadAlbum = {},
                onLibraryColumns = {},
            )
            PhoebeScreenshotScenario.Song -> SongDetailPanel(
                track = fixture.currentTrack,
                modifier = Modifier.fillMaxSize(),
                onBack = {},
                onPlay = {},
                onAddToUpNext = {},
                onDownload = {},
            )
            PhoebeScreenshotScenario.Playlist -> PlaylistDetailPanel(
                playlist = fixture.playlist,
                catalog = fixture.catalog,
                catalogRefreshing = false,
                libraryUi = fixture.libraryUi,
                modifier = Modifier.fillMaxSize(),
                onBack = {},
                onPlayTracks = { _, _ -> },
                onAddToUpNext = {},
                onDownload = {},
                onDownloadPlaylist = {},
                onLibraryColumns = {},
            )
            PhoebeScreenshotScenario.CollectionValues -> CollectionsScreen(
                entry = CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre),
                catalog = fixture.catalog,
                modifier = Modifier.fillMaxSize(),
                onBack = {},
                onCollectionValue = { _, _ -> },
            )
            PhoebeScreenshotScenario.CollectionItems -> CollectionItemsScreen(
                entry = CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre),
                value = "Dream pop",
                catalog = fixture.catalog,
                modifier = Modifier.fillMaxSize(),
                onBack = {},
                onArtist = {},
                onAlbum = {},
            )
            PhoebeScreenshotScenario.Player,
            PhoebeScreenshotScenario.PlayerUpNextExpanded,
            -> MobilePlayer(
                track = fixture.currentTrack,
                upNext = fixture.upNext,
                isPlaying = true,
                shuffle = true,
                repeat = RepeatMode.All,
                positionMs = 96_000L,
                bufferedPositionMs = 172_000L,
                currentIndex = 0,
                onToggle = {},
                onPrevious = {},
                onNext = {},
                onShuffle = {},
                onRepeat = {},
                onSeek = {},
                onPlayQueue = {},
                onMoveUpNext = { _, _ -> },
                onRemoveUpNext = {},
                onBack = {},
                onSwipeDismiss = {},
                initialUpNextExpanded = scenario == PhoebeScreenshotScenario.PlayerUpNextExpanded,
            )
            PhoebeScreenshotScenario.HomePlayedRows -> MobileHomeScreen(
                state = deriveHomeUiState(fixture.catalog, fixture.playHistory, randomArtistSeed = 7, randomAlbumSeed = 11, nowMs = fixture.nowMs),
                radioStations = fixture.radioStations,
                homeSections = listOf(HomeSection.Played, HomeSection.Random),
                listState = rememberLazyListState(),
                modifier = Modifier.fillMaxSize(),
                onArtist = {},
                onAlbum = {},
                onPlaylist = {},
                onRecentSongs = {},
                onRecentArtists = {},
                onRecentAlbums = {},
                onFavoritePlaylists = {},
                onFavoriteArtists = {},
                onFavoriteAlbums = {},
                onCollections = {},
                onRecentlyPlayed = {},
                onMostPlayed = {},
                onRefreshArtists = {},
                onRefreshAlbums = {},
                onPlayTracks = { _, _ -> },
                onAddToUpNext = {},
                onDownload = {},
            )
            else -> MobileBrowseShell(
                catalog = fixture.catalog,
                catalogRefreshing = false,
                session = fixture.session,
                section = when (scenario) {
                    PhoebeScreenshotScenario.Library -> DesktopSection.Library
                    PhoebeScreenshotScenario.Search -> DesktopSection.Search
                    PhoebeScreenshotScenario.Settings -> DesktopSection.Settings
                    else -> DesktopSection.Home
                },
                selectedPlaylistId = null,
                searchQuery = if (scenario == PhoebeScreenshotScenario.Search) "moon" else "",
                libraryFilter = LibraryFilterTab.Artists,
                libraryUi = fixture.libraryUi,
                currentTrack = fixture.currentTrack,
                homeUiState = deriveHomeUiState(fixture.catalog, fixture.playHistory, randomArtistSeed = 7, randomAlbumSeed = 11, nowMs = fixture.nowMs),
                isPlaying = true,
                onNavigate = {},
                onSearchQuery = {},
                onLibraryFilter = {},
                onPlaylist = {},
                onArtist = {},
                onAlbum = {},
                onSong = {},
                onRecentSongs = {},
                onRecentArtists = {},
                onRecentAlbums = {},
                onFavoritePlaylists = {},
                onFavoriteArtists = {},
                onFavoriteAlbums = {},
                onCollections = {},
                onRecentlyPlayed = {},
                onMostPlayed = {},
                onRefreshRandomArtists = {},
                onRefreshRandomAlbums = {},
                onPlayTracks = { _, _ -> },
                onAddToUpNext = {},
                onDownload = {},
                onOpenNowPlaying = {},
                onTogglePlayPause = {},
                onSignOut = {},
                onAddLocalFolder = {},
                onRefreshLibrary = {},
                onLibrarySortBy = {},
                onLibraryAscending = {},
                onLibraryColumns = {},
                onHomeSections = {},
                onPersonalMix = {},
                onExportFavoritePlaylists = {},
                onImportFavoritePlaylists = {},
                appSettings = AppSettings.Default,
                onCrossfadeSeconds = {},
                onScanLibraryOnLaunch = {},
                onNotifyWhenDownloadFinishes = {},
                downloadDirectory = null,
                downloadCount = fixture.catalog.downloads.size,
                defaultDownloadDirectoryLabel = "App storage",
                onDownloadDirectory = {},
                onDeleteAllDownloads = {},
                useLightAppearance = false,
                onUseLightAppearanceChange = {},
                appearanceTintId = PhoebeTintOption.Purple.id,
                onAppearanceTintChange = {},
                radioStations = fixture.radioStations,
            )
        }
    }
}

internal data class PhoebeScreenshotFixtureData(
    val catalog: CatalogSnapshot,
    val radioStations: List<PlexRadioStation>,
    val session: PlexSession,
    val mediaSources: MediaSourcesState,
    val libraryUi: LibraryUiPreferences,
    val playHistory: PlayHistorySnapshot,
    val servers: List<PlexServer>,
    val libraries: List<MusicLibrary>,
    val artist: Artist,
    val album: Album,
    val playlist: Playlist,
    val currentTrack: Track,
    val upNext: List<Track>,
    val nowMs: Long,
)

internal val PhoebeScreenshotFixture = run {
    val nowMs = 1_800_000_000_000L
    val artist = Artist(id = "plex:artist-luna", title = "Luna North", albumCount = 3, songCount = 8, dateAddedMs = nowMs - 86_400_000L, rating = 4.5f, favorite = true)
    val secondArtist = Artist(id = "artist-echo", title = "Echo Harbor", albumCount = 2, songCount = 6, dateAddedMs = nowMs - 172_800_000L, favorite = true)
    val thirdArtist = Artist(id = "artist-marrow", title = "Marrow & Pines", albumCount = 1, songCount = 4, dateAddedMs = nowMs - 259_200_000L)
    val album = Album(id = "plex:album-moonlit", title = "Moonlit Signals", artist = artist.title, year = 2026, dateAddedMs = nowMs - 86_400_000L, rating = 3.5f, favorite = true)
    val secondAlbum = Album(id = "album-velvet", title = "Velvet Transit", artist = artist.title, year = 2024, dateAddedMs = nowMs - 172_800_000L, favorite = true)
    val thirdAlbum = Album(id = "album-harbor", title = "Harbor Static", artist = secondArtist.title, year = 2025, dateAddedMs = nowMs - 259_200_000L)
    val fourthAlbum = Album(id = "album-field", title = "Field Notes", artist = thirdArtist.title, year = 2023, dateAddedMs = nowMs - 345_600_000L)
    val tracks = listOf(
        Track("plex:track-aurora", "Aurora Wake", artist.title, album.title, 244_000L, "https://stream.example/aurora", "https://download.example/aurora", year = 2026, genre = "Dream pop", filepath = "/music/Luna North/Moonlit Signals/01 Aurora Wake.flac", audioCodec = "FLAC", bitrateKbps = 921, dateAddedMs = nowMs - 86_400_000L, rating = 4.5f),
        Track("plex:track-moon", "Moon Over Meridian", artist.title, album.title, 272_000L, "https://stream.example/moon", "https://download.example/moon", year = 2026, genre = "Dream pop", filepath = "/music/Luna North/Moonlit Signals/02 Moon Over Meridian.flac", audioCodec = "FLAC", bitrateKbps = 1014, dateAddedMs = nowMs - 90_000_000L, rating = 3.5f),
        Track("plex:track-static", "Soft Static Bloom", artist.title, album.title, 218_000L, "https://stream.example/static", "https://download.example/static", year = 2026, genre = "Dream pop", filepath = "/music/Luna North/Moonlit Signals/03 Soft Static Bloom.flac", audioCodec = "FLAC", bitrateKbps = 884, dateAddedMs = nowMs - 96_000_000L),
        Track("plex:track-window", "Window Seat Reverie", artist.title, secondAlbum.title, 236_000L, "https://stream.example/window", "https://download.example/window", year = 2024, genre = "Synth pop", filepath = "/music/Luna North/Velvet Transit/01 Window Seat Reverie.m4a", audioCodec = "AAC", bitrateKbps = 256, dateAddedMs = nowMs - 172_800_000L),
        Track("plex:track-harbor", "Harbor Lights", secondArtist.title, thirdAlbum.title, 208_000L, "https://stream.example/harbor", "https://download.example/harbor", year = 2025, genre = "Indie", filepath = "/music/Echo Harbor/Harbor Static/01 Harbor Lights.mp3", audioCodec = "MP3", bitrateKbps = 320, dateAddedMs = nowMs - 259_200_000L),
        Track("plex:track-quartet", "Quartz Quartet", secondArtist.title, thirdAlbum.title, 198_000L, "https://stream.example/quartet", "https://download.example/quartet", year = 2025, genre = "Indie", filepath = "/music/Echo Harbor/Harbor Static/02 Quartz Quartet.mp3", audioCodec = "MP3", bitrateKbps = 320, dateAddedMs = nowMs - 266_200_000L),
        Track("local:track-field", "Field Recording No. 7", thirdArtist.title, fourthAlbum.title, 314_000L, "file:///field", "", localUri = "file:///Users/music/Field Notes/field-7.flac", year = 2023, genre = "Ambient", filepath = "/Users/music/Field Notes/field-7.flac", audioCodec = "FLAC", bitrateKbps = 773, dateAddedMs = nowMs - 345_600_000L),
    )
    val playlist = Playlist(id = "plex:playlist-night", title = "Night Drive Mix", trackCount = 5, rating = 4f, favorite = true)
    val secondPlaylist = Playlist(id = "plex:playlist-focus", title = "Focus Room", trackCount = 4, favorite = true)
    val likedPlaylist = Playlist(id = "plex:playlist-liked", title = "Liked Songs", trackCount = 1)
    val server = PlexServer(
        id = "server-atlas",
        name = "Atlas",
        uri = "https://plex.example",
        owned = true,
        accessToken = "server-token",
    )
    val library = MusicLibrary(key = "42", title = "Music")
    val radioStations = defaultPlexRadioStations(library)
    PhoebeScreenshotFixtureData(
        radioStations = radioStations,
        catalog = CatalogSnapshot(
            artists = listOf(artist, secondArtist, thirdArtist),
            albums = listOf(album, secondAlbum, thirdAlbum, fourthAlbum),
            playlists = listOf(playlist, secondPlaylist, likedPlaylist),
            tracksByParent = mapOf(
                album.id to tracks.take(3),
                secondAlbum.id to listOf(tracks[3]),
                thirdAlbum.id to tracks.slice(4..5),
                fourthAlbum.id to listOf(tracks[6]),
                playlist.id to listOf(tracks[1], tracks[4], tracks[0], tracks[5], tracks[3]),
                secondPlaylist.id to listOf(tracks[6], tracks[2], tracks[3], tracks[5]),
                likedPlaylist.id to listOf(tracks[1]),
            ),
            downloads = listOf(
                DownloadItem(
                    trackId = tracks[1].id,
                    title = tracks[1].title,
                    artist = tracks[1].artist,
                    state = DownloadState.Complete,
                    progress = 1f,
                    localUri = "downloads/Luna North/Moonlit Signals/02 Moon Over Meridian.flac",
                ),
            ),
        ),
        session = PlexSession(
            token = "fixture-token",
            userName = "Phoebe Listener",
            selectedServer = server,
            selectedLibrary = library,
        ),
        mediaSources = MediaSourcesState(
            localFolders = listOf(
                LocalFolderMediaSourceConfig(
                    id = "local-fixture",
                    rootUri = "file:///Users/music",
                    label = "Local Music",
                    enabled = true,
                ),
            ),
        ),
        libraryUi = LibraryUiPreferences(
            sortBy = LibrarySortBy.Name,
            ascending = true,
            columns = LibraryColumnVisibility(
                year = true,
                genre = true,
                filepath = false,
                audioCodec = true,
                bitrate = true,
                duration = true,
            ),
        ),
        playHistory = PlayHistorySnapshot(
            byArtist = mapOf(artist.title to nowMs - 3_600_000L, secondArtist.title to nowMs - 86_400_000L),
            byAlbum = mapOf(album.title to nowMs - 3_600_000L, thirdAlbum.title to nowMs - 172_800_000L),
            byTrack = mapOf(tracks[1].id to nowMs - 60_000L, tracks[0].id to nowMs - 3_600_000L, tracks[4].id to nowMs - 86_400_000L, tracks[2].id to nowMs - 92_000_000L),
            playCountByTrack = mapOf(tracks[1].id to 1284L, tracks[0].id to 982L, tracks[4].id to 876L, tracks[2].id to 741L, tracks[3].id to 695L),
            playEventsByTrack = mapOf(
                tracks[1].id to listOf(nowMs - 60_000L, nowMs - 120_000L, nowMs - 180_000L),
                tracks[0].id to listOf(nowMs - 3_600_000L, nowMs - 3_660_000L),
                tracks[2].id to listOf(nowMs - 92_000_000L, nowMs - 92_060_000L),
            ),
        ),
        servers = listOf(server),
        libraries = listOf(library),
        artist = artist,
        album = album,
        playlist = playlist,
        currentTrack = tracks[1],
        upNext = listOf(tracks[4], tracks[0], tracks[5], tracks[3]),
        nowMs = nowMs,
    )
}

/**
 * Floating ghost rendered above all other UI while a drag is in flight.
 *
 * Implementation notes:
 *  - Lives inside a `fillMaxSize` Box at the very TOP of [PhoebeRoot]'s overlay layer so it
 *    paints above every other panel.
 *  - Has no [pointerInput] of its own. Pointer events therefore pass straight through to the
 *    originating song row whose [pointerInput] is still driving the drag — if we swallowed
 *    them here the row would stop receiving `onDrag` updates and the gesture would silently
 *    abort.
 *  - Reads [DragDropController.hoveringPlaylistTitle] so it can morph into an
 *    "Add to {playlist}" pill the moment the pointer enters a sidebar drop target, giving
 *    the user a clear "yes, this will work" affordance before they release.
 */
