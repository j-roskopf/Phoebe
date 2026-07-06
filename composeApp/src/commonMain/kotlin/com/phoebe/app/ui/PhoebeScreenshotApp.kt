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
import com.phoebe.app.feature.auth.AuthWelcomeMobileRoute
import com.phoebe.app.feature.auth.AuthWelcomeRouteActions
import com.phoebe.app.feature.auth.AuthWelcomeRouteState
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
import com.phoebe.app.feature.details.ArtistEventsRoute
import com.phoebe.app.feature.details.ArtistEventsRouteActions
import com.phoebe.app.feature.details.ArtistEventsRouteState
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
import com.phoebe.app.feature.home.*
import com.phoebe.app.feature.home.deriveHomeUiState
import com.phoebe.app.feature.library.*
import com.phoebe.app.feature.playback.MobilePlaybackRoute
import com.phoebe.app.feature.playback.MobilePlaybackRouteActions
import com.phoebe.app.feature.playback.MobilePlaybackRouteState
import com.phoebe.app.feature.playback.MobilePlayer
import com.phoebe.app.feature.search.LocalSearchHistory
import com.phoebe.app.feature.search.SearchResultsFactory
import com.phoebe.app.feature.search.SearchHistoryState
import com.phoebe.app.feature.settings.SettingsCategory
import com.phoebe.app.feature.settings.SettingsDesktopView
import com.phoebe.app.di.RouteViewModelFactory
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
import com.phoebe.app.data.PlayHistorySnapshot
import com.phoebe.app.data.RecommendedRadioStations
import com.phoebe.app.domain.AudioAnalysisFrame
import com.phoebe.app.domain.AudioAnalysisSource
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.AppSettings
import com.phoebe.app.domain.AppScreen
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.ArtistEvent
import com.phoebe.app.domain.ArtistEventDate
import com.phoebe.app.domain.ArtistEventPrice
import com.phoebe.app.domain.ArtistEventVenue
import com.phoebe.app.domain.ArtistEventsLoadState
import com.phoebe.app.domain.ArtistRadioAvailability
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.CollectionFacet
import com.phoebe.app.domain.CollectionTarget
import com.phoebe.app.domain.DownloadItem
import com.phoebe.app.domain.DownloadState
import com.phoebe.app.domain.EventDataProvider
import com.phoebe.app.domain.HomeSection
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.LocalFolderMediaSourceConfig
import com.phoebe.app.domain.MediaSourcesState
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.domain.PlexRadioStation
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.PlayerState
import com.phoebe.app.domain.PlayerTransportState
import com.phoebe.app.domain.RecentSearchItem
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.RadioCountry
import com.phoebe.app.domain.RadioDirectoryState
import com.phoebe.app.domain.RadioStation
import com.phoebe.app.domain.RadioStationSource
import com.phoebe.app.domain.ShellPlaybackState
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
    HomeExpanded,
    HomeAccordionsCollapsed,
    HomeAccordionsExpanded,
    HomePlayedRows,
    FavoritePlaylists,
    FavoriteArtists,
    FavoriteAlbums,
    Library,
    LibraryScrollbar,
    LibraryFiveColumnGrid,
    Radio,
    Playlist,
    Artist,
    ArtistWithEvents,
    ArtistEvents,
    ArtistOldLayout,
    ArtistRadio,
    Album,
    AlbumOldLayout,
    CollectionValues,
    CollectionItems,
    Song,
    Search,
    Player,
    PlayerBlurredArtworkOn,
    PlayerBlurredArtworkOff,
    PlayerVisualizer,
    PlayerVisualizerAlchemy,
    PlayerVisualizerBattery,
    PlayerVisualizerBarsAndWaves,
    PlayerVisualizerBlazingColors,
    PlayerVisualizerPlenoptic,
    PlayerVisualizerVortexSpectrum,
    PlayerVisualizerClassicEQ,
    PlayerVisualizerHaloSpectrum,
    PlayerVisualizerWireframeSpectrum3D,
    PlayerVisualizerTvFrame,
    PlayerUpNextExpanded,
    Settings,
    SignIn,
    SignInProviders,
}

private val ScreenshotAudioAnalysisFrame = AudioAnalysisFrame(
    amplitude = 0.68f,
    bands = List(128) { index -> (0.14f + ((index * 37) % 100) / 125f).coerceIn(0f, 1f) },
    timestampMs = 1_800_000_000_000L,
    source = AudioAnalysisSource.Pcm,
)

private fun PhoebeScreenshotScenario.visualizerPreset(): NowPlayingVisualizerPreset =
    when (this) {
        PhoebeScreenshotScenario.PlayerVisualizer,
        PhoebeScreenshotScenario.PlayerVisualizerBarsAndWaves,
        PhoebeScreenshotScenario.PlayerVisualizerTvFrame,
        -> NowPlayingVisualizerPreset.BarsAndWaves
        PhoebeScreenshotScenario.PlayerVisualizerAlchemy -> NowPlayingVisualizerPreset.Alchemy
        PhoebeScreenshotScenario.PlayerVisualizerBattery -> NowPlayingVisualizerPreset.Battery
        PhoebeScreenshotScenario.PlayerVisualizerBlazingColors -> NowPlayingVisualizerPreset.BlazingColors
        PhoebeScreenshotScenario.PlayerVisualizerPlenoptic -> NowPlayingVisualizerPreset.Plenoptic
        PhoebeScreenshotScenario.PlayerVisualizerVortexSpectrum -> NowPlayingVisualizerPreset.VortexSpectrum
        PhoebeScreenshotScenario.PlayerVisualizerClassicEQ -> NowPlayingVisualizerPreset.ClassicEQ
        PhoebeScreenshotScenario.PlayerVisualizerHaloSpectrum -> NowPlayingVisualizerPreset.HaloSpectrum
        PhoebeScreenshotScenario.PlayerVisualizerWireframeSpectrum3D -> NowPlayingVisualizerPreset.WireframeSpectrum3D
        else -> NowPlayingVisualizerPreset.Default
    }

@Composable
internal fun PhoebeScreenshotApp(
    scenario: PhoebeScreenshotScenario,
    useLightAppearance: Boolean = false,
    designId: String = PhoebeDesignSystem.Default.id,
    tintId: String = PhoebeTintOption.Purple.id,
    forceShowQueue: Boolean = false,
    forceCustomLibraryScrollIndex: Boolean = scenario == PhoebeScreenshotScenario.LibraryScrollbar,
    modifier: Modifier = Modifier,
) {
    val fixture = remember { PhoebeScreenshotFixture }
    val settingsInitialCategory = if (scenario == PhoebeScreenshotScenario.Settings &&
        (PhoebeDesignSystem.fromId(designId) != PhoebeDesignSystem.Default || tintId != PhoebeTintOption.Purple.id)
    ) {
        SettingsCategory.Appearance
    } else {
        SettingsCategory.AudioPlayback
    }
    PhoebeTheme(useLightAppearance = useLightAppearance, tintId = tintId, designId = designId) {
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
                recentItems = listOf(
                    RecentSearchItem.ArtistHit(Artist(id = "plex:a1", title = "The National")),
                    RecentSearchItem.AlbumHit(Album(id = "plex:al1", title = "Boxer", artist = "The National")),
                ),
                recordArtist = {},
                recordAlbum = {},
                recordTrack = {},
                removeItem = {},
                clearItems = {},
            ),
            LocalDragDrop provides DragDropController(),
            LocalSharedElementTransitionsEnabled provides false,
            LocalContinuousMotionEnabled provides false,
            LocalLibrarySectionIndexForceScrub provides forceCustomLibraryScrollIndex,
        ) {
            BoxWithConstraints(modifier.fillMaxSize()) {
                if (maxWidth < 900.dp) {
                    PhoebeMobileScreenshotScenario(
                        scenario,
                        fixture,
                        useLightAppearance,
                        designId,
                        tintId,
                        Modifier.fillMaxSize(),
                    )
                } else {
                    val wideDesktop = maxWidth >= 1280.dp
                    PhoebeDesktopScreenshotScenario(
                        scenario = scenario,
                        fixture = fixture,
                        useLightAppearance = useLightAppearance,
                        designId = designId,
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
    designId: String = PhoebeDesignSystem.Default.id,
    tintId: String = PhoebeTintOption.Purple.id,
    tintedBackgroundGradient: Boolean = AppSettings.Default.tintedBackgroundGradient,
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
        PhoebeScreenshotScenario.ArtistWithEvents,
        PhoebeScreenshotScenario.ArtistOldLayout,
        PhoebeScreenshotScenario.ArtistRadio,
        -> AppScreen.ArtistDetail(fixture.artist)
        PhoebeScreenshotScenario.ArtistEvents -> AppScreen.ArtistEvents(fixture.artist)
        PhoebeScreenshotScenario.Album,
        PhoebeScreenshotScenario.AlbumOldLayout,
        -> AppScreen.AlbumDetail(fixture.album)
        PhoebeScreenshotScenario.CollectionValues -> AppScreen.Collections(CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre))
        PhoebeScreenshotScenario.CollectionItems -> AppScreen.CollectionItems(CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre), "Dream pop")
        PhoebeScreenshotScenario.Song -> AppScreen.SongDetail(fixture.currentTrack)
        PhoebeScreenshotScenario.SignIn,
        PhoebeScreenshotScenario.SignInProviders,
        -> AppScreen.SignIn
        PhoebeScreenshotScenario.PlayerVisualizer,
        PhoebeScreenshotScenario.PlayerVisualizerAlchemy,
        PhoebeScreenshotScenario.PlayerVisualizerBattery,
        PhoebeScreenshotScenario.PlayerVisualizerBarsAndWaves,
        PhoebeScreenshotScenario.PlayerVisualizerBlazingColors,
        PhoebeScreenshotScenario.PlayerVisualizerPlenoptic,
        PhoebeScreenshotScenario.PlayerVisualizerVortexSpectrum,
        PhoebeScreenshotScenario.PlayerVisualizerClassicEQ,
        PhoebeScreenshotScenario.PlayerVisualizerHaloSpectrum,
        PhoebeScreenshotScenario.PlayerVisualizerWireframeSpectrum3D,
        PhoebeScreenshotScenario.PlayerVisualizerTvFrame,
        -> AppScreen.Player
        else -> AppScreen.Home
    }
    val section = when (scenario) {
        PhoebeScreenshotScenario.Library,
        PhoebeScreenshotScenario.LibraryScrollbar,
        PhoebeScreenshotScenario.LibraryFiveColumnGrid,
        -> BrowseSection.Library
        PhoebeScreenshotScenario.Radio -> BrowseSection.Radio
        PhoebeScreenshotScenario.Playlist -> BrowseSection.Library
        PhoebeScreenshotScenario.Search -> BrowseSection.Search
        PhoebeScreenshotScenario.Settings -> BrowseSection.Settings
        else -> BrowseSection.Home
    }
    val libraryUi = when (scenario) {
        PhoebeScreenshotScenario.LibraryFiveColumnGrid -> fixture.libraryUi.copy(
            albumGridItemSizeDp = LibraryUiPreferences.MinAlbumGridItemSizeDp,
            artistGridItemSizeDp = LibraryUiPreferences.MinArtistGridItemSizeDp,
        )
        PhoebeScreenshotScenario.HomePlayedRows -> fixture.libraryUi.copy(
            homeSections = listOf(HomeSection.Played, HomeSection.Random),
        )
        else -> fixture.libraryUi
    }
    val catalog = when (scenario) {
        PhoebeScreenshotScenario.LibraryFiveColumnGrid -> fixture.catalog.withFiveColumnGridArtists(fixture.nowMs)
        else -> fixture.catalog
    }
    val visualizerPreset = scenario.visualizerPreset()
    val routeViewModelFactory = rememberScreenshotRouteViewModelFactory()
    DesktopPlayer(
        shellState = DesktopShellState(
            screen = screen,
            catalog = catalog,
            catalogRefreshing = false,
            session = fixture.session,
            mediaSources = fixture.mediaSources,
            section = section,
            selectedPlaylistId = if (scenario == PhoebeScreenshotScenario.Playlist) fixture.playlist.id else null,
            showQueue = showQueue,
            compact = compact,
            busy = false,
            routeViewModelFactory = routeViewModelFactory,
        ),
        playbackState = PlaybackUiState(
            shellPlayback = ShellPlaybackState(
                currentTrack = fixture.currentTrack,
                isPlaying = true,
                isBuffering = false,
            ),
            playerTransport = PlayerTransportState(
                shuffle = true,
                repeat = RepeatMode.All,
                volume = 0.72f,
            ),
            player = PlayerState(
                queue = listOfNotNull(fixture.currentTrack) + fixture.upNext,
                currentIndex = 0,
                isPlaying = true,
                positionMs = 96_000L,
                bufferedPositionMs = 172_000L,
                shuffle = true,
                repeat = RepeatMode.All,
                volume = 0.72f,
            ),
            track = fixture.currentTrack,
            upNext = fixture.upNext,
            currentIndex = 0,
            visualizerPreset = visualizerPreset,
            showVisualizerInTvFrame = scenario == PhoebeScreenshotScenario.PlayerVisualizerTvFrame,
            audioAnalysis = if (visualizerPreset.isVisualizer) ScreenshotAudioAnalysisFrame else AudioAnalysisFrame.Empty,
            useFilamentVisualizers = false,
        ),
        playbackActions = PlaybackActions(
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
        ),
        browseState = BrowseUiState(
            homeUiState = deriveHomeUiState(
                catalog,
                fixture.playHistory,
                randomArtistSeed = 7,
                randomAlbumSeed = 11,
                nowMs = fixture.nowMs,
            ),
            playHistory = fixture.playHistory,
            searchQuery = if (scenario == PhoebeScreenshotScenario.Search) "moon" else "",
            libraryFilter = LibraryFilterTab.Artists,
            libraryUi = libraryUi,
            radioStations = fixture.radioStations,
            radioDirectory = fixture.radioDirectory,
            artistRadioAvailability = if (scenario == PhoebeScreenshotScenario.ArtistRadio) {
                mapOf(fixture.artist.id to ArtistRadioAvailability.Available)
            } else {
                emptyMap()
            },
            artistEvents = if (scenario == PhoebeScreenshotScenario.ArtistWithEvents || scenario == PhoebeScreenshotScenario.ArtistEvents) {
                mapOf(fixture.artist.id to fixture.artistEvents)
            } else {
                emptyMap()
            },
        ),
        browseActions = BrowseActions(
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
            onRecentlyPlayed = {},
            onMostPlayed = {},
            onCollections = {},
            onCollectionValue = { _, _ -> },
            onRefreshRandomArtists = {},
            onRefreshRandomAlbums = {},
            onPopDetail = {},
            onPlayTracks = { _, _ -> },
            onAddToUpNext = {},
            onDownload = {},
            onDownloadArtist = {},
            onPlayArtistRadio = {},
            onDownloadAlbum = {},
            onDownloadPlaylist = {},
            onLibrarySortBy = {},
            onLibraryAscending = {},
            onLibraryColumns = {},
        ),
        authSetupState = AuthSetupState(
            appMessage = "Sign in to Plex or add a local music folder to get started.",
            pinCode = "PHOEBE",
            servers = fixture.servers,
            libraries = fixture.libraries,
        ),
        authSetupActions = AuthSetupActions(
            onStartSignIn = {},
            onFinishSignIn = {},
            onSignInJellyfin = { _, _, _ -> },
            onSignInProvider = { _, _, _, _, _ -> },
            onSignOut = {},
            onAddLocalFolder = {},
            onRemoveLocalFolder = {},
            onToggleLocalFolder = { _, _ -> },
            onRefreshLibrary = {},
            onSelectServer = {},
            onSelectLibrary = { _, _ -> },
            onCancelPlexSetup = {},
            onBackToServerPicker = {},
            onRetryServers = {},
        ),
        settingsState = SettingsUiState(
            appSettings = AppSettings.Default.copy(
                fullBleedDetailArtwork = scenario != PhoebeScreenshotScenario.ArtistOldLayout &&
                    scenario != PhoebeScreenshotScenario.AlbumOldLayout,
                tintedBackgroundGradient = tintedBackgroundGradient,
            ),
            downloadDirectory = null,
            downloadCount = fixture.catalog.downloads.size,
            defaultDownloadDirectoryLabel = "App storage",
            useLightAppearance = useLightAppearance,
            appearanceDesignId = designId,
            appearanceTintId = tintId,
            settingsInitialCategory = settingsInitialCategory,
        ),
        settingsActions = SettingsActions(
            onHomeSections = {},
            onPersonalMix = {},
            onAlbumGridItemSize = {},
            onArtistGridItemSize = {},
            onExportFavoritePlaylists = {},
            onImportFavoritePlaylists = {},
            onExportRadioStations = {},
            onImportRadioStations = {},
            onCrossfadeSeconds = {},
            onScanLibraryOnLaunch = {},
            onNotifyWhenDownloadFinishes = {},
            onDownloadDirectory = {},
            onDeleteAllDownloads = {},
            onUseLightAppearanceChange = {},
            onAppearanceDesignChange = {},
            onAppearanceTintChange = {},
        ),
    )
}

@Composable
internal fun PhoebeMobileScreenshotScenario(
    scenario: PhoebeScreenshotScenario,
    fixture: PhoebeScreenshotFixtureData,
    useLightAppearance: Boolean = false,
    designId: String = PhoebeDesignSystem.Default.id,
    tintId: String = PhoebeTintOption.Purple.id,
    modifier: Modifier = Modifier,
) {
    val catalog = if (scenario == PhoebeScreenshotScenario.LibraryFiveColumnGrid) {
        fixture.catalog.withFiveColumnGridArtists(fixture.nowMs)
    } else {
        fixture.catalog
    }
    val libraryUi = if (scenario == PhoebeScreenshotScenario.LibraryFiveColumnGrid) {
        fixture.libraryUi.copy(
            albumGridItemSizeDp = LibraryUiPreferences.MinAlbumGridItemSizeDp,
            artistGridItemSizeDp = LibraryUiPreferences.MinArtistGridItemSizeDp,
        )
    } else {
        fixture.libraryUi
    }

    val isPlayer = scenario.name.startsWith("Player", ignoreCase = true)
    val isSignIn = scenario.name.startsWith("SignIn", ignoreCase = true)
    val showChrome = !isPlayer && !isSignIn
    val showMiniPlayerChrome = showChrome && !scenario.hidesMobilePlayerChrome()
    val routeViewModelFactory = rememberScreenshotRouteViewModelFactory()

    val chromePadding = if (showChrome) {
        MobileChromePadding(
            top = MobileToolbarChromeHeight + MobileChromeScrollGap,
            bottom = MobileBottomNavChromeHeight +
                MobileChromeScrollGap +
                if (showMiniPlayerChrome) MobileMiniPlayerChromeHeight else 0.dp,
        )
    } else {
        LocalMobileChromePadding.current
    }

    Box(
        modifier
            .background(PhoebeUi.shellTop)
            .statusBarsPadding(),
    ) {
        CompositionLocalProvider(LocalMobileChromePadding provides chromePadding) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    when (scenario) {
            PhoebeScreenshotScenario.SignIn -> AuthWelcomeMobileRoute(
                state = AuthWelcomeRouteState(
                    message = "Sign in to Plex or add a local music folder to get started.",
                    pinCode = "PHOEBE",
                    jellyfinServers = emptyList(),
                    jellyfinDiscoveryLoading = false,
                    jellyfinQuickConnect = null,
                    authInProgress = false,
                ),
                actions = screenshotAuthActions(),
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.SignInProviders -> AuthWelcomeMobileRoute(
                state = AuthWelcomeRouteState(
                    message = "Sign in to Plex, Jellyfin, or another media provider—or add a local music folder to get started.",
                    pinCode = null,
                    jellyfinServers = emptyList(),
                    jellyfinDiscoveryLoading = false,
                    jellyfinQuickConnect = null,
                    authInProgress = false,
                    initialProvidersExpanded = true,
                ),
                actions = screenshotAuthActions(),
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.FavoritePlaylists -> FavoritePlaylistsMobileRoute(
                state = FavoritePlaylistsRouteState(searchQuery = ""),
                actions = FavoritePlaylistsRouteActions(
                    onSearchQuery = {},
                    onPlaylist = {},
                    onBack = {},
                ),
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.FavoriteArtists -> FavoriteArtistsMobileRoute(
                state = FavoriteArtistsRouteState(catalog = catalog, libraryUi = libraryUi),
                actions = FavoriteArtistsRouteActions(
                    onLibrarySortBy = {},
                    onLibraryAscending = {},
                    onLibraryColumns = {},
                    onArtist = {},
                    onBack = {},
                ),
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.FavoriteAlbums -> FavoriteAlbumsMobileRoute(
                state = FavoriteAlbumsRouteState(catalog = catalog, libraryUi = libraryUi),
                actions = FavoriteAlbumsRouteActions(
                    onLibrarySortBy = {},
                    onLibraryAscending = {},
                    onLibraryColumns = {},
                    onAlbum = {},
                    onBack = {},
                ),
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.Artist,
            PhoebeScreenshotScenario.ArtistWithEvents,
            PhoebeScreenshotScenario.ArtistOldLayout,
            PhoebeScreenshotScenario.ArtistRadio,
            -> ArtistDetailRoute(
                state = ArtistDetailRouteState(
                    artist = fixture.artist,
                    catalog = catalog,
                    libraryUi = libraryUi,
                    artistRadioAvailability = if (scenario == PhoebeScreenshotScenario.ArtistRadio) {
                        ArtistRadioAvailability.Available
                    } else {
                        null
                    },
                    artistEventsAvailable = scenario == PhoebeScreenshotScenario.ArtistWithEvents,
                    fullBleedArtwork = scenario != PhoebeScreenshotScenario.ArtistWithEvents,
                ),
                actions = ArtistDetailRouteActions(
                    onBack = {},
                    onAlbum = {},
                    onPlayTracks = { _, _ -> },
                    onAddToUpNext = {},
                    onDownload = {},
                    onDownloadArtist = {},
                    onPlayArtistRadio = {},
                    onArtistEvents = {},
                    onArtist = {},
                    onLibraryColumns = {},
                ),
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.ArtistEvents -> ArtistEventsRoute(
                state = ArtistEventsRouteState(
                    artist = fixture.artist,
                    events = fixture.artistEvents,
                ),
                actions = ArtistEventsRouteActions(
                    onBack = {},
                    onRetry = {},
                    onOpenUrl = {},
                ),
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.Album,
            PhoebeScreenshotScenario.AlbumOldLayout,
            -> AlbumDetailRoute(
                state = AlbumDetailRouteState(
                    album = fixture.album,
                    catalog = catalog,
                    libraryUi = libraryUi,
                ),
                actions = AlbumDetailRouteActions(
                    onBack = {},
                    onPlayTracks = { _, _ -> },
                    onAddToUpNext = {},
                    onDownload = {},
                    onDownloadAlbum = {},
                    onArtist = {},
                    onLibraryColumns = {},
                ),
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.Song -> SongDetailRoute(
                state = SongDetailRouteState(track = fixture.currentTrack),
                actions = SongDetailRouteActions(
                    onBack = {},
                    onPlay = {},
                    onAddToUpNext = {},
                    onDownload = {},
                ),
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.Playlist -> PlaylistDetailRoute(
                state = PlaylistDetailRouteState(
                    playlist = fixture.playlist,
                    catalog = catalog,
                    catalogRefreshing = false,
                    libraryUi = libraryUi,
                ),
                actions = PlaylistDetailRouteActions(
                    onBack = {},
                    onPlayTracks = { _, _ -> },
                    onAddToUpNext = {},
                    onDownload = {},
                    onDownloadPlaylist = {},
                    onLibraryColumns = {},
                ),
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.CollectionValues -> CollectionsRoute(
                state = CollectionsRouteState(
                    entry = CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre),
                    catalog = catalog,
                ),
                actions = CollectionsRouteActions(
                    onBack = {},
                    onCollectionValue = { _, _ -> },
                ),
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.CollectionItems -> CollectionItemsRoute(
                state = CollectionItemsRouteState(
                    entry = CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre),
                    value = "Dream pop",
                    catalog = catalog,
                ),
                actions = CollectionItemsRouteActions(
                    onBack = {},
                    onArtist = {},
                    onAlbum = {},
                ),
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.Player,
            PhoebeScreenshotScenario.PlayerBlurredArtworkOn,
            PhoebeScreenshotScenario.PlayerBlurredArtworkOff,
            PhoebeScreenshotScenario.PlayerVisualizer,
            PhoebeScreenshotScenario.PlayerVisualizerAlchemy,
            PhoebeScreenshotScenario.PlayerVisualizerBattery,
            PhoebeScreenshotScenario.PlayerVisualizerBarsAndWaves,
            PhoebeScreenshotScenario.PlayerVisualizerBlazingColors,
            PhoebeScreenshotScenario.PlayerVisualizerPlenoptic,
            PhoebeScreenshotScenario.PlayerVisualizerVortexSpectrum,
            PhoebeScreenshotScenario.PlayerVisualizerClassicEQ,
            PhoebeScreenshotScenario.PlayerVisualizerHaloSpectrum,
            PhoebeScreenshotScenario.PlayerVisualizerWireframeSpectrum3D,
            PhoebeScreenshotScenario.PlayerVisualizerTvFrame,
            PhoebeScreenshotScenario.PlayerUpNextExpanded,
            -> MobilePlaybackRoute(
                state = MobilePlaybackRouteState(
                    track = fixture.currentTrack,
                    upNext = fixture.upNext,
                    isPlaying = true,
                    shuffle = true,
                    repeat = RepeatMode.All,
                    positionMs = 96_000L,
                    bufferedPositionMs = 172_000L,
                    currentIndex = 0,
                    visualizerPreset = scenario.visualizerPreset(),
                    showVisualizerInTvFrame = scenario == PhoebeScreenshotScenario.PlayerVisualizerTvFrame,
                    audioAnalysis = if (scenario.visualizerPreset().isVisualizer) {
                        ScreenshotAudioAnalysisFrame
                    } else {
                        AudioAnalysisFrame.Empty
                    },
                    useFilamentVisualizers = false,
                    blurredArtworkAppearance = scenario != PhoebeScreenshotScenario.PlayerBlurredArtworkOff,
                    initialUpNextExpanded = scenario == PhoebeScreenshotScenario.PlayerUpNextExpanded,
                    expansionFraction = 1f,
                ),
                actions = screenshotPlaybackActions(),
                modifier = Modifier.fillMaxSize(),
            )
            PhoebeScreenshotScenario.HomeAccordionsCollapsed,
            PhoebeScreenshotScenario.HomeAccordionsExpanded,
            -> MobileHomeAccordionScreenshot(
                fixture = fixture,
                expandedSection = when (scenario) {
                    PhoebeScreenshotScenario.HomeAccordionsExpanded -> PhoneHomeAccordionSection.Random
                    else -> null
                },
                useLightAppearance = useLightAppearance,
                designId = designId,
                tintId = tintId,
            )
            PhoebeScreenshotScenario.HomePlayedRows -> MobileHomeRoute(
                routeState = MobileHomeRouteState(
                    homeUiState = deriveHomeUiState(catalog, fixture.playHistory, randomArtistSeed = 7, randomAlbumSeed = 11, nowMs = fixture.nowMs),
                    catalogRefreshing = false,
                    homeSections = listOf(HomeSection.Played, HomeSection.Random),
                    supportedCollectionEntries = setOf(
                        CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre),
                        CollectionEntry(CollectionTarget.Albums, CollectionFacet.Genre),
                    ),
                    radioStations = fixture.radioStations,
                    radioStartingIds = emptySet(),
                    decadeMixNotice = null,
                ),
                listState = rememberLazyListState(),
                callbacks = screenshotMobileHomeCallbacks(),
                modifier = Modifier.fillMaxSize(),
            )
            else -> MobileBrowseShell(
                catalog = catalog,
                catalogRefreshing = false,
                session = fixture.session,
                section = when (scenario) {
                    PhoebeScreenshotScenario.Library,
                    PhoebeScreenshotScenario.LibraryScrollbar,
                    PhoebeScreenshotScenario.LibraryFiveColumnGrid,
                    -> BrowseSection.Library
                    PhoebeScreenshotScenario.Radio -> BrowseSection.Radio
                    PhoebeScreenshotScenario.Search -> BrowseSection.Search
                    PhoebeScreenshotScenario.Settings -> BrowseSection.Settings
                    else -> BrowseSection.Home
                },
                selectedPlaylistId = null,
                searchQuery = if (scenario == PhoebeScreenshotScenario.Search) "moon" else "",
                libraryFilter = LibraryFilterTab.Artists,
                libraryUi = libraryUi,
                currentTrack = fixture.currentTrack.takeUnless { scenario.hidesMobilePlayerChrome() },
                homeUiState = deriveHomeUiState(catalog, fixture.playHistory, randomArtistSeed = 7, randomAlbumSeed = 11, nowMs = fixture.nowMs),
                isPlaying = !scenario.hidesMobilePlayerChrome(),
                routeViewModelFactory = routeViewModelFactory,
                homeScreenLayoutMode = if (scenario == PhoebeScreenshotScenario.HomeExpanded) {
                    HomeScreenLayoutMode.Expanded
                } else {
                    HomeScreenLayoutMode.Default
                },
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
                onPreviousTrack = {},
                onNextTrack = {},
                onSignOut = {},
                onAddLocalFolder = {},
                onRefreshLibrary = {},
                onLibrarySortBy = {},
                onLibraryAscending = {},
                onLibraryColumns = {},
                onHomeSections = {},
                onPersonalMix = {},
                onAlbumGridItemSize = {},
                onArtistGridItemSize = {},
                onExportFavoritePlaylists = {},
                onImportFavoritePlaylists = {},
                onExportRadioStations = {},
                onImportRadioStations = {},
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
                appearanceDesignId = designId,
                onAppearanceDesignChange = {},
                appearanceTintId = tintId,
                onAppearanceTintChange = {},
                radioStations = fixture.radioStations,
                internetRadioDirectory = fixture.radioDirectory,
            )
        }
    }

    if (showChrome) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            if (showMiniPlayerChrome) {
                MobilePlayer(
                    track = fixture.currentTrack,
                    upNext = fixture.upNext,
                    isPlaying = true,
                    shuffle = true,
                    repeat = RepeatMode.All,
                    positionMs = 96_000L,
                    bufferedPositionMs = 172_000L,
                    currentIndex = 0,
                    visualizerPreset = NowPlayingVisualizerPreset.Default,
                    audioAnalysis = AudioAnalysisFrame.Empty,
                    blurredArtworkAppearance = true,
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
                    expansionFraction = 0f,
                )
            }
            MobileBottomNavigation(
                section = when (scenario) {
                    PhoebeScreenshotScenario.Library,
                    PhoebeScreenshotScenario.LibraryScrollbar,
                    PhoebeScreenshotScenario.LibraryFiveColumnGrid,
                    -> BrowseSection.Library
                    PhoebeScreenshotScenario.Radio -> BrowseSection.Radio
                    PhoebeScreenshotScenario.Search -> BrowseSection.Search
                    PhoebeScreenshotScenario.Settings -> BrowseSection.Settings
                    else -> BrowseSection.Home
                },
                onSection = {},
                attachedToMiniPlayer = showMiniPlayerChrome,
            )
        }
    }
}
}
}
}

private fun CatalogSnapshot.withFiveColumnGridArtists(nowMs: Long): CatalogSnapshot =
    copy(
        artists = artists + listOf(
            Artist(id = "artist-night", title = "Night Orchard", albumCount = 1, songCount = 3, dateAddedMs = nowMs - 432_000_000L),
            Artist(id = "artist-silver", title = "Silver Atlas", albumCount = 2, songCount = 5, dateAddedMs = nowMs - 518_400_000L),
        ),
    )

private fun PhoebeScreenshotScenario.hidesMobilePlayerChrome(): Boolean =
    this == PhoebeScreenshotScenario.ArtistWithEvents || this == PhoebeScreenshotScenario.ArtistEvents

private fun screenshotAuthActions(): AuthWelcomeRouteActions =
    AuthWelcomeRouteActions(
        onStartSignIn = {},
        onFinishSignIn = {},
        onSignInJellyfin = { _, _, _ -> },
        onSignInProvider = { _, _, _, _, _ -> },
        onDiscoverJellyfinServers = {},
        onStartJellyfinQuickConnect = {},
        onFinishJellyfinQuickConnect = {},
        onAddLocalFolder = {},
    )

private fun screenshotMobileHomeCallbacks(): MobileHomeCallbacks =
    MobileHomeCallbacks(
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
        onPlayDecadeMix = {},
        onClearDecadeMixNotice = {},
        onPlayRadioStation = {},
        onPlayPersonalMix = {},
        onPlayPopularMix = {},
        onPlayTopTracksMix = {},
        onArtistMixBuilder = {},
        onAlbumMixBuilder = {},
        onPlayTracks = { _, _ -> },
        onAddToUpNext = {},
        onDownload = {},
    )

private fun screenshotPlaybackActions(): MobilePlaybackRouteActions =
    MobilePlaybackRouteActions(
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
    )

@Composable
private fun rememberScreenshotRouteViewModelFactory(): RouteViewModelFactory =
    remember { RouteViewModelFactory(SearchResultsFactory()) }

@Composable
private fun MobileHomeAccordionScreenshot(
    fixture: PhoebeScreenshotFixtureData,
    expandedSection: PhoneHomeAccordionSection?,
    useLightAppearance: Boolean,
    designId: String,
    tintId: String,
) {
    val routeViewModelFactory = rememberScreenshotRouteViewModelFactory()
    val homeSections = HomeAccordionScreenshotSections
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = homeAccordionScreenshotScrollIndex(expandedSection, homeSections),
    )
    MobileBrowseShell(
        catalog = fixture.catalog,
        catalogRefreshing = false,
        session = fixture.session,
        section = BrowseSection.Home,
        selectedPlaylistId = null,
        searchQuery = "",
        libraryFilter = LibraryFilterTab.Artists,
        libraryUi = fixture.libraryUi.copy(homeSections = homeSections),
        currentTrack = fixture.currentTrack,
        homeUiState = deriveHomeUiState(
            fixture.catalog,
            fixture.playHistory,
            randomArtistSeed = 7,
            randomAlbumSeed = 11,
            nowMs = fixture.nowMs,
        ),
        isPlaying = true,
        routeViewModelFactory = routeViewModelFactory,
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
        onPreviousTrack = {},
        onNextTrack = {},
        onSignOut = {},
        onAddLocalFolder = {},
        onRefreshLibrary = {},
        onLibrarySortBy = {},
        onLibraryAscending = {},
        onLibraryColumns = {},
        onHomeSections = {},
        onPersonalMix = {},
                onAlbumGridItemSize = {},
                onArtistGridItemSize = {},
        onExportFavoritePlaylists = {},
        onImportFavoritePlaylists = {},
        onExportRadioStations = {},
        onImportRadioStations = {},
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
        appearanceDesignId = designId,
        onAppearanceDesignChange = {},
        appearanceTintId = tintId,
        onAppearanceTintChange = {},
        homeScreenLayoutMode = HomeScreenLayoutMode.Default,
        radioStations = fixture.radioStations,
        initialExpandedPhoneSection = expandedSection,
        homeListState = listState,
    )
}

internal data class PhoebeScreenshotFixtureData(
    val catalog: CatalogSnapshot,
    val radioStations: List<PlexRadioStation>,
    val radioDirectory: RadioDirectoryState,
    val session: PlexSession,
    val mediaSources: MediaSourcesState,
    val libraryUi: LibraryUiPreferences,
    val playHistory: PlayHistorySnapshot,
    val servers: List<PlexServer>,
    val libraries: List<MusicLibrary>,
    val artist: Artist,
    val artistEvents: ArtistEventsLoadState,
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
    val artistEvents = ArtistEventsLoadState(
        events = listOf(
            ArtistEvent(
                id = "tm-luna-1",
                provider = EventDataProvider.Ticketmaster,
                title = "Luna North with Echo Harbor",
                url = "https://tickets.example/luna-north",
                status = "onsale",
                date = ArtistEventDate(
                    localDate = "2026-08-21",
                    localTime = "20:00:00",
                    dateTimeUtc = "2026-08-22T01:00:00Z",
                    timezone = "America/New_York",
                ),
                venue = ArtistEventVenue(
                    name = "The Meridian Room",
                    city = "Chicago",
                    region = "IL",
                    country = "US",
                    address = "1420 W Lake St",
                ),
                price = ArtistEventPrice(min = 42.0, max = 42.0, currency = "USD", display = "$42"),
            ),
            ArtistEvent(
                id = "sg-luna-2",
                provider = EventDataProvider.SeatGeek,
                title = "Moonlit Signals Tour",
                url = "https://tickets.example/moonlit-signals",
                status = "available",
                date = ArtistEventDate(
                    localDate = "2026-09-05",
                    localTime = "19:30:00",
                    dateTimeUtc = "2026-09-06T00:30:00Z",
                    timezone = "America/Chicago",
                ),
                venue = ArtistEventVenue(
                    name = "North Pier Hall",
                    city = "Milwaukee",
                    region = "WI",
                    country = "US",
                ),
                price = ArtistEventPrice(min = 35.0, max = 68.0, currency = "USD", display = "$35-$68"),
            ),
        ),
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
    val internetRadioDirectory = RadioDirectoryState(
        manualStations = listOf(
            RadioStation(
                id = "manual:morning-static",
                name = "Morning Static",
                streamUrl = "https://radio.example/morning-static.mp3",
                description = "Saved manual stream",
                source = RadioStationSource.Manual,
            ),
        ),
        recommendedStations = RecommendedRadioStations.take(12),
        countries = listOf(
            RadioCountry(name = "The United States Of America", code = "US", stationCount = 7349),
            RadioCountry(name = "Germany", code = "DE", stationCount = 5951),
            RadioCountry(name = "The Russian Federation", code = "RU", stationCount = 3087),
            RadioCountry(name = "France", code = "FR", stationCount = 2640),
            RadioCountry(name = "Brazil", code = "BR", stationCount = 2351),
            RadioCountry(name = "The United Kingdom", code = "GB", stationCount = 2173),
            RadioCountry(name = "Canada", code = "CA", stationCount = 1742),
            RadioCountry(name = "The Netherlands", code = "NL", stationCount = 1456),
            RadioCountry(name = "Japan", code = "JP", stationCount = 1238),
            RadioCountry(name = "Argentina", code = "AR", stationCount = 1124),
            RadioCountry(name = "Italy", code = "IT", stationCount = 1048),
            RadioCountry(name = "Spain", code = "ES", stationCount = 997),
            RadioCountry(name = "Australia", code = "AU", stationCount = 812),
            RadioCountry(name = "Sweden", code = "SE", stationCount = 654),
            RadioCountry(name = "Mexico", code = "MX", stationCount = 602),
            RadioCountry(name = "Norway", code = "NO", stationCount = 431),
        ),
    )
    PhoebeScreenshotFixtureData(
        radioStations = radioStations,
        radioDirectory = internetRadioDirectory,
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
            topMostPlayed = listOf(
                com.phoebe.app.domain.MostPlayedEntry(tracks[1].id, 1284L, nowMs - 60_000L, tracks[1].artist, tracks[1].album),
                com.phoebe.app.domain.MostPlayedEntry(tracks[0].id, 982L, nowMs - 3_600_000L, tracks[0].artist, tracks[0].album),
                com.phoebe.app.domain.MostPlayedEntry(tracks[4].id, 876L, nowMs - 86_400_000L, tracks[4].artist, tracks[4].album),
            ),
            topRecentlyPlayed = listOf(
                com.phoebe.app.domain.RecentlyPlayedEntry(tracks[1].id, nowMs - 60_000L, tracks[1].artist, tracks[1].album),
                com.phoebe.app.domain.RecentlyPlayedEntry(tracks[0].id, nowMs - 3_600_000L, tracks[0].artist, tracks[0].album),
                com.phoebe.app.domain.RecentlyPlayedEntry(tracks[4].id, nowMs - 86_400_000L, tracks[4].artist, tracks[4].album),
            ),
        ),
        servers = listOf(server),
        libraries = listOf(library),
        artist = artist,
        artistEvents = artistEvents,
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
