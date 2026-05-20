package com.phoebe.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
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
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.CollectionTarget
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.LyricsLoadState
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.LocalFolderMediaSourceConfig
import com.phoebe.app.domain.MediaSourcesState
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.PlayHistoryKind
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
import com.phoebe.app.telemetry.Telemetry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.phoebe.app.sources.rememberPickLocalFolder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhoebeRoot(
    state: AppState,
    useLightAppearance: Boolean,
    onUseLightAppearanceChange: (Boolean) -> Unit,
    appearanceTintId: String,
    onAppearanceTintChange: (String) -> Unit,
) {
    val screen by state.screen.collectAsState()
    val catalog by state.catalog.collectAsState()
    val catalogWorkActive by state.catalogRefreshing.collectAsState()
    val catalogSyncState by state.catalogSyncState.collectAsState()
    val session by state.session.collectAsState()
    val supportedCollectionEntries = remember(session) { session.supportedCollectionEntries().toSet() }
    val mediaSources by state.mediaSources.collectAsState()
    val player by state.player.collectAsState()
    val musicAssistantRemotePlayback by state.musicAssistantRemotePlayback.collectAsState()
    val cast by state.cast.collectAsState()
    val busy by state.busy.collectAsState()
    val serversLoading by state.serversLoading.collectAsState()
    val librariesLoading by state.librariesLoading.collectAsState()
    val message by state.message.collectAsState()
    val playbackSnackbar by state.playbackSnackbar.collectAsState()
    val decadeMixNotice by state.decadeMixNotice.collectAsState()
    val radioStations by state.radioStations.collectAsState()
    val radioStartingIds by state.radioStartingIds.collectAsState()
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
    val lastPlayedByArtist by state.lastPlayedByArtist.collectAsState()
    val lastPlayedByAlbum by state.lastPlayedByAlbum.collectAsState()
    val lastPlayedByTrack by state.lastPlayedByTrack.collectAsState()
    val playCountsByTrack by state.playCountsByTrack.collectAsState()
    val playEventsByTrack by state.playEventsByTrack.collectAsState()
    var browseSection by remember { mutableStateOf(DesktopSection.Home) }
    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    val exitPlaylistDetail: () -> Unit = {
        selectedPlaylistId = null
        state.popDetail()
    }
    val canHandleBrowseBack = screen == AppScreen.Home &&
        (selectedPlaylistId != null || browseSection != DesktopSection.Home)
    PlatformBackHandler(
        enabled = state.canHandleBack(screen) || canHandleBrowseBack,
        onBack = {
            when {
                screen is AppScreen.PlaylistDetail -> {
                    selectedPlaylistId = null
                    state.handleBack()
                }
                screen == AppScreen.Home && selectedPlaylistId != null -> {
                    selectedPlaylistId = null
                }
                screen == AppScreen.Home && browseSection != DesktopSection.Home -> {
                    browseSection = DesktopSection.Home
                }
                else -> state.handleBack()
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
    var playerSwipeDismiss by remember { mutableStateOf(false) }
    LaunchedEffect(screen) {
        if (screen != AppScreen.Player) {
            playerSwipeDismiss = false
        }
    }
    var recentSearches by remember { mutableStateOf(emptyList<String>()) }
    var libraryFilter by remember { mutableStateOf(LibraryFilterTab.Artists) }

    LaunchedEffect(screen) {
        Telemetry.trackScreen(screen.telemetryName)
    }

    val upNext = player.upNext
    val currentTrack = player.currentTrack
    val currentIndex = player.currentIndex.takeIf { it >= 0 } ?: 0
    var lyricsRefreshNonce by remember { mutableStateOf(0) }
    var lyricsRefreshTrackId by remember { mutableStateOf<String?>(null) }
    val lyricsTrack = when (val currentScreen = screen) {
        is AppScreen.Lyrics -> currentScreen.track ?: currentTrack
        AppScreen.Home -> if (browseSection == DesktopSection.Lyrics) currentTrack else null
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
    val catalogRefreshing = catalogWorkActive || catalogSyncState.isActive

    val nowPlaying = remember(currentTrack?.id, player.isPlaying, player.isBuffering) {
        NowPlayingIndicatorState(
            trackId = currentTrack?.id,
            isPlaying = player.isPlaying,
            isBuffering = player.isBuffering,
        )
    }
    val downloadStatus = remember(catalog.downloads) {
        DownloadStatusSnapshot(catalog.downloads.associateBy { it.trackId })
    }
    val playHistory = remember(lastPlayedByArtist, lastPlayedByAlbum, lastPlayedByTrack, playCountsByTrack, playEventsByTrack) {
        PlayHistorySnapshot(
            byArtist = lastPlayedByArtist,
            byAlbum = lastPlayedByAlbum,
            byTrack = lastPlayedByTrack,
            playCountByTrack = playCountsByTrack,
            playEventsByTrack = playEventsByTrack,
        )
    }
    var randomArtistSeed by remember { mutableStateOf(Random.nextInt()) }
    var randomAlbumSeed by remember { mutableStateOf(Random.nextInt()) }
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
    val homeUiState by produceState(
        initialValue = HomeUiState(),
        catalog,
        playHistory,
        randomArtistSeed,
        randomAlbumSeed,
        nowMs,
    ) {
        delay(50L)
        value = withContext(Dispatchers.Default) {
            deriveHomeUiState(
                catalog = catalog,
                playHistory = playHistory,
                randomArtistSeed = randomArtistSeed,
                randomAlbumSeed = randomAlbumSeed,
                nowMs = nowMs,
            )
        }
    }
    val personalMixCatalog = rememberUpdatedState(catalog)
    val personalMixHomeUiState = rememberUpdatedState(homeUiState)
    val personalMixPreferences = rememberUpdatedState(libraryUi.personalMix)
    val playPersonalMix = remember(state) {
        {
            val tracks = personalMix(
                catalog = personalMixCatalog.value,
                state = personalMixHomeUiState.value,
                preferences = personalMixPreferences.value,
            )
            if (tracks.isNotEmpty()) {
                state.playTracks(tracks, 0)
                state.open(AppScreen.Player)
            }
        }
    }
    LaunchedEffect(screen, browseSection, catalog.albums, catalog.tracksByParent.keys, session?.selectedServer, nowMs) {
        if (screen == AppScreen.Home && browseSection == DesktopSection.Home) {
            state.warmRecentAlbumTracks(cutoffMs = nowMs - RecentlyAddedWindowMs, maxAlbums = 10)
            }
    }
    LaunchedEffect(screen, browseSection, playHistory.byAlbum, catalog.albums, catalog.tracksByParent.keys, session?.selectedServer) {
        if (screen == AppScreen.Home && browseSection == DesktopSection.Home) {
            val playedAlbumTitles = playHistory.byAlbum.entries
                .sortedByDescending { it.value }
                .map { it.key }
            state.warmPlayedAlbumTracks(playedAlbumTitles, maxAlbums = 10)
        }
    }
    LaunchedEffect(session?.selectedServer?.id, session?.selectedLibrary?.key) {
        state.refreshRadioStations()
    }
    val openRecentSongs: () -> Unit = {
        selectedPlaylistId = null
        browseSection = DesktopSection.Home
        state.open(AppScreen.RecentlyAdded(RecentlyAddedKind.Songs))
    }
    val openRecentArtists: () -> Unit = {
        selectedPlaylistId = null
        browseSection = DesktopSection.Home
        state.open(AppScreen.RecentlyAdded(RecentlyAddedKind.Artists))
    }
    val openRecentAlbums: () -> Unit = {
        selectedPlaylistId = null
        browseSection = DesktopSection.Home
        state.open(AppScreen.RecentlyAdded(RecentlyAddedKind.Albums))
    }
    val openRecentlyPlayed: () -> Unit = {
        selectedPlaylistId = null
        browseSection = DesktopSection.Home
        state.open(AppScreen.PlayHistory(PlayHistoryKind.RecentlyPlayed))
    }
    val openMostPlayed: () -> Unit = {
        selectedPlaylistId = null
        browseSection = DesktopSection.Home
        state.open(AppScreen.PlayHistory(PlayHistoryKind.MostPlayed))
    }
    val openFavoritePlaylists: () -> Unit = {
        selectedPlaylistId = null
        browseSection = DesktopSection.Home
        state.open(AppScreen.FavoritePlaylists)
    }
    val openFavoriteArtists: () -> Unit = {
        selectedPlaylistId = null
        browseSection = DesktopSection.Home
        state.open(AppScreen.FavoriteArtists)
    }
    val openFavoriteAlbums: () -> Unit = {
        selectedPlaylistId = null
        browseSection = DesktopSection.Home
        state.open(AppScreen.FavoriteAlbums)
    }
    val openCollections: (CollectionEntry) -> Unit = { entry ->
        if (session.supportsCollectionEntry(entry)) {
            selectedPlaylistId = null
            browseSection = DesktopSection.Home
            libraryFilter = when (entry.target) {
                CollectionTarget.Artists -> LibraryFilterTab.Artists
                CollectionTarget.Albums -> LibraryFilterTab.Albums
            }
            state.open(AppScreen.Collections(entry))
        }
    }
    val openCollectionValue: (CollectionEntry, String) -> Unit = { entry, value ->
        if (session.supportsCollectionEntry(entry)) {
            selectedPlaylistId = null
            browseSection = DesktopSection.Home
            state.open(AppScreen.CollectionItems(entry, value))
        }
    }
    val commitSearch: (String) -> Unit = { rawQuery ->
        val trimmed = rawQuery.trim()
        if (trimmed.isNotBlank()) {
            recentSearches = listOf(trimmed) + recentSearches.filterNot { it.equals(trimmed, ignoreCase = true) }
            recentSearches = recentSearches.take(6)
        }
    }
    val searchHistory = remember(recentSearches) {
        SearchHistoryState(
            recentSearches = recentSearches,
            commitSearch = commitSearch,
            removeSearch = { search ->
                recentSearches = recentSearches.filterNot { it.equals(search, ignoreCase = true) }
            },
            clearSearches = { recentSearches = emptyList() },
        )
    }
    LaunchedEffect(browseSection, selectedPlaylistId, searchQuery) {
        if (browseSection == DesktopSection.Search && selectedPlaylistId == null && searchQuery.isNotBlank()) {
            delay(900L)
            if (searchQuery.isNotBlank()) {
                commitSearch(searchQuery)
            }
        }
    }
    var createPlaylistFor by remember { mutableStateOf<List<Track>?>(null) }
    var metadataEditorTrack by remember { mutableStateOf<Track?>(null) }
    val playlistActions = remember(catalog.playlists, session, mediaSources.localFolders) {
        val plexReady = session.supportsRemotePlaylists()
        val localReady = mediaSources.localFolders.any { it.enabled }
        val providerType = session?.providerType
        val list = catalog.playlists.filter { playlist ->
            playlist.isLocalPlaylist() ||
                (plexReady && providerType != null && playlist.isRemoteProviderPlaylist() && playlist.belongsToProvider(providerType))
        }
        PlaylistActions(
            playlists = list,
            playlistsEnabled = plexReady || localReady,
            onAddTrackToPlaylist = { playlist, track -> state.addToPlaylist(playlist, track) },
            onCopyPlaylistToPlaylist = { source, target -> state.copyPlaylistIntoPlaylist(source, target) },
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
        )
    }
    val likeActions = remember(catalog.playlists, catalog.tracksByParent, session) {
        val likedPlaylist = catalog.playlists.firstOrNull { it.isLikedSongsPlaylist() }
        LikeActions(
            likedTrackIds = likedPlaylist?.let { playlist ->
                catalog.tracksByParent[playlist.id].orEmpty().map { it.id }.toSet()
            }.orEmpty(),
            likesEnabled = session.supportsRemotePlaylists(),
            onToggleLiked = { track -> state.toggleLikedTrack(track) },
        )
    }
    val ratingActions = remember(catalog, session) {
        RatingActions(
            ratingsEnabled = session.supportsRemoteRatings(),
            catalog = catalog,
            onRateTrack = { track, rating -> state.rateTrack(track, rating) },
            onRateArtist = { artist, rating -> state.rateArtist(artist, rating) },
            onRateAlbum = { album, rating -> state.rateAlbum(album, rating) },
            onRatePlaylist = { playlist, rating -> state.ratePlaylist(playlist, rating) },
        )
    }
    val favoriteActions = remember(catalog, state) {
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
                    state.open(AppScreen.ArtistDetail(artist))
                    true
                } ?: false
            },
            onOpenAlbumForTrack = { track ->
                resolveAlbumForTrack(catalog, track)?.let { album ->
                    state.open(AppScreen.AlbumDetail(album))
                    true
                } ?: false
            },
            onOpenSongDetail = { track ->
                state.open(AppScreen.SongDetail(track))
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
            val desktopCompactTopPadding = if (compact && isDesktopPlatform()) 22.dp else 0.dp
            val shellModifier = if (compact) {
                Modifier
                    .fillMaxSize()
                    .background(PhoebeUi.shellTop)
                    .statusBarsPadding()
                    .padding(top = desktopCompactTopPadding)
            } else {
                val shellInsets = if (mergesTitleBar) {
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Start + WindowInsetsSides.End + WindowInsetsSides.Bottom,
                    )
                } else {
                    WindowInsets.safeDrawing
                }
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(PhoebeUi.shellRadialTint, PhoebeUi.canvasBackground),
                            center = Offset(420f, 40f),
                            radius = 960f,
                        ),
                    )
                    .windowInsetsPadding(shellInsets)
            }
            Box(modifier = shellModifier) {
            if (compact) {
                SharedTransitionLayout(Modifier.fillMaxSize()) {
                val sharedTransitionScope = this
                CompositionLocalProvider(LocalSharedTransitionScope provides sharedTransitionScope) {
                AnimatedContent(
                    targetState = screen,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        val openingPlayer = targetState == AppScreen.Player && initialState != AppScreen.Player
                        val closingPlayer = initialState == AppScreen.Player && targetState != AppScreen.Player
                        val motion = tween<IntOffset>(durationMillis = 340, easing = FastOutSlowInEasing)
                        val fade = tween<Float>(durationMillis = 220, easing = FastOutSlowInEasing)
                        when {
                            openingPlayer -> {
                                slideInVertically(animationSpec = motion) { it } + fadeIn(fade) togetherWith
                                    slideOutVertically(animationSpec = motion) { -it / 6 } + fadeOut(fade)
                            }
                            closingPlayer -> {
                                if (playerSwipeDismiss) {
                                    fadeIn(tween(120)) togetherWith ExitTransition.None
                                } else {
                                    slideInVertically(animationSpec = motion) { it / 6 } + fadeIn(fade) togetherWith
                                        slideOutVertically(animationSpec = motion) { it } + fadeOut(fade)
                                }
                            }
                            else -> fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                        }
                    },
                    label = "mobile-screen",
                ) { scr ->
                CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@AnimatedContent) {
                when (scr) {
                    is AppScreen.ServerPicker -> PlexServerPickerPanel(
                        servers = servers,
                        busy = busy,
                        serversLoading = serversLoading,
                        onSelectServer = state::selectServer,
                        onCancel = state::signOut,
                        onRetry = state::loadServers,
                        modifier = Modifier.fillMaxSize(),
                    )
                    is AppScreen.LibraryPicker -> PlexLibraryPickerPanel(
                        libraries = libraries,
                        serverName = session?.selectedServer?.name,
                        providerType = session?.providerType ?: com.phoebe.app.domain.MediaProviderType.Plex,
                        busy = busy,
                        librariesLoading = librariesLoading,
                        isJellyfin = session.isEmbyFamily(),
                        onSelectLibrary = { library, mode -> state.selectLibrary(library, mode) },
                        onBack = state::returnToServerPicker,
                        onCancel = state::signOut,
                        modifier = Modifier.fillMaxSize(),
                    )
                    is AppScreen.SignIn -> MobileSignInWelcomeScreen(
                        message = message,
                        pinCode = pin?.code,
                        jellyfinServers = jellyfinServers,
                        jellyfinDiscoveryLoading = jellyfinDiscoveryLoading,
                        jellyfinQuickConnect = jellyfinQuickConnect,
                        onStartSignIn = state::startPlexSignIn,
                        onFinishSignIn = state::finishPlexSignIn,
                        onSignInJellyfin = state::signInJellyfin,
                        onSignInProvider = state::signInProvider,
                        onDiscoverJellyfinServers = state::discoverJellyfinServers,
                        onStartJellyfinQuickConnect = state::startJellyfinQuickConnect,
                        onFinishJellyfinQuickConnect = state::finishJellyfinQuickConnect,
                        onAddLocalFolder = state::addLocalFolderFromUri,
                        modifier = Modifier.fillMaxSize(),
                    )
                    is AppScreen.ArtistDetail -> ArtistDetailPanel(
                        artist = scr.artist,
                        catalog = catalog,
                        libraryUi = libraryUi,
                        catalogRefreshing = catalogRefreshing,
                        modifier = Modifier.fillMaxSize(),
                        searchQuery = searchQuery,
                        onBack = state::popDetail,
                        onAlbum = { state.open(AppScreen.AlbumDetail(it)) },
                        onPlayTracks = { tracks, index ->
                            state.playTracks(tracks, index)
                            state.open(AppScreen.Player)
                        },
                        onAddToUpNext = state::addToUpNext,
                        onDownload = state::download,
                        onDownloadArtist = state::download,
                        artistRadioAvailability = artistRadioAvailability[scr.artist.id],
                        artistRadioStarting = scr.artist.id in radioStartingIds,
                        onProbeArtistRadio = state::probeArtistRadio,
                        onPlayArtistRadio = state::playArtistRadio,
                        onArtist = { state.open(AppScreen.ArtistDetail(it)) },
                        onLibraryColumns = state::setLibraryColumns,
                    )
                    is AppScreen.AlbumDetail -> AlbumDetailPanel(
                        album = scr.album,
                        catalog = catalog,
                        libraryUi = libraryUi,
                        catalogRefreshing = catalogRefreshing,
                        modifier = Modifier.fillMaxSize(),
                        searchQuery = searchQuery,
                        onBack = state::popDetail,
                        onPlayTracks = { tracks, index ->
                            state.playTracks(tracks, index)
                            state.open(AppScreen.Player)
                        },
                        onAddToUpNext = state::addToUpNext,
                        onDownload = state::download,
                        onDownloadAlbum = state::download,
                        onLibraryColumns = state::setLibraryColumns,
                    )
                    is AppScreen.SongDetail -> SongDetailPanel(
                        track = scr.track,
                        modifier = Modifier.fillMaxSize(),
                        onBack = state::popDetail,
                        onPlay = {
                            state.playTracks(listOf(scr.track), 0)
                            state.open(AppScreen.Player)
                        },
                        onAddToUpNext = state::addToUpNext,
                        onDownload = state::download,
                        onOpenLyrics = { state.open(AppScreen.Lyrics(it)) },
                    )
                    is AppScreen.Lyrics -> LyricsView(
                        track = lyricsTrack,
                        currentTrackId = currentTrack?.id,
                        positionMs = player.positionMs,
                        state = lyricsState,
                        modifier = Modifier.fillMaxSize(),
                        onBack = state::popDetail,
                        onRetry = retryLyrics,
                    )
                    is AppScreen.RecentlyAdded -> RecentlyAddedScreen(
                        kind = scr.kind,
                        catalog = catalog,
                        nowMs = nowMs,
                        modifier = Modifier.fillMaxSize(),
                        onBack = state::popDetail,
                        onArtist = { state.open(AppScreen.ArtistDetail(it)) },
                        onAlbum = { state.open(AppScreen.AlbumDetail(it)) },
                        onPlayTracks = { tracks, index ->
                            state.playTracks(tracks, index)
                            state.open(AppScreen.Player)
                        },
                        onAddToUpNext = state::addToUpNext,
                        onDownload = state::download,
                    )
                    is AppScreen.Collections -> CollectionsScreen(
                        entry = scr.entry,
                        catalog = catalog,
                        modifier = Modifier.fillMaxSize(),
                        supportedCollectionEntries = supportedCollectionEntries,
                        onBack = state::popDetail,
                        onCollectionValue = openCollectionValue,
                    )
                    is AppScreen.CollectionItems -> CollectionItemsScreen(
                        entry = scr.entry,
                        value = scr.value,
                        catalog = catalog,
                        modifier = Modifier.fillMaxSize(),
                        supportedCollectionEntries = supportedCollectionEntries,
                        onBack = state::popDetail,
                        onArtist = { state.open(AppScreen.ArtistDetail(it)) },
                        onAlbum = { state.open(AppScreen.AlbumDetail(it)) },
                    )
                    is AppScreen.PlayHistory -> PlayHistoryScreen(
                        kind = scr.kind,
                        catalog = catalog,
                        playHistory = playHistory,
                        modifier = Modifier.fillMaxSize(),
                        onBack = state::popDetail,
                        onPlayTracks = { tracks, index ->
                            state.playTracks(tracks, index)
                            state.open(AppScreen.Player)
                        },
                        onAddToUpNext = state::addToUpNext,
                        onDownload = state::download,
                    )
                    AppScreen.FavoritePlaylists -> FavoritePlaylistsMobileView(
                        searchQuery = searchQuery,
                        onSearchQuery = { searchQuery = it },
                        modifier = Modifier.fillMaxSize(),
                        onBack = state::popDetail,
                        onPlaylist = { playlist ->
                            selectedPlaylistId = playlist.id
                            state.open(AppScreen.PlaylistDetail(playlist))
                        },
                    )
                    AppScreen.FavoriteArtists -> FavoriteArtistsMobileView(
                        catalog = catalog,
                        libraryUi = libraryUi,
                        modifier = Modifier.fillMaxSize(),
                        onBack = state::popDetail,
                        onLibrarySortBy = state::setLibrarySortBy,
                        onLibraryAscending = state::setLibrarySortAscending,
                        onLibraryColumns = state::setLibraryColumns,
                        onArtist = { state.open(AppScreen.ArtistDetail(it)) },
                    )
                    AppScreen.FavoriteAlbums -> FavoriteAlbumsMobileView(
                        catalog = catalog,
                        libraryUi = libraryUi,
                        modifier = Modifier.fillMaxSize(),
                        onBack = state::popDetail,
                        onLibrarySortBy = state::setLibrarySortBy,
                        onLibraryAscending = state::setLibrarySortAscending,
                        onLibraryColumns = state::setLibraryColumns,
                        onAlbum = { state.open(AppScreen.AlbumDetail(it)) },
                    )
                    is AppScreen.PlaylistDetail -> PlaylistDetailPanel(
                        playlist = scr.playlist,
                        catalog = catalog,
                        catalogRefreshing = catalogRefreshing,
                        libraryUi = libraryUi,
                        modifier = Modifier.fillMaxSize(),
                        searchQuery = searchQuery,
                        onSearchQuery = { searchQuery = it },
                        onBack = exitPlaylistDetail,
                        onPlayTracks = { tracks, index ->
                            state.playTracks(tracks, index)
                            state.open(AppScreen.Player)
                        },
                        onAddToUpNext = state::addToUpNext,
                        onDownload = state::download,
                        onDownloadPlaylist = state::download,
                        onLibraryColumns = state::setLibraryColumns,
                    )
                    AppScreen.Player -> MobilePlayer(
                        track = currentTrack,
                        upNext = upNext,
                        previousTrack = player.queue.getOrNull(currentIndex - 1),
                        isPlaying = player.isPlaying,
                        isBuffering = player.isBuffering,
                        shuffle = player.shuffle,
                        repeat = player.repeat,
                        positionMs = player.positionMs,
                        bufferedPositionMs = player.bufferedPositionMs,
                        currentIndex = currentIndex,
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
                        onOpenSongDetail = { state.open(AppScreen.SongDetail(it)) },
                        onCast = state::showCastPicker,
                        onLyrics = {
                            currentTrack?.let { state.open(AppScreen.Lyrics(it)) }
                        },
                        onBack = state::handleBack,
                        onSwipeDismiss = {
                            playerSwipeDismiss = true
                            state.handleBack()
                        },
                    )
                    AppScreen.Home -> MobileBrowseShell(
                        catalog = catalog,
                        catalogRefreshing = catalogRefreshing,
                        session = session,
                        section = browseSection,
                        selectedPlaylistId = selectedPlaylistId,
                        searchQuery = searchQuery,
                        libraryFilter = libraryFilter,
                        libraryUi = libraryUi,
                        currentTrack = currentTrack,
                        homeUiState = homeUiState,
                        isPlaying = player.isPlaying,
                        isBuffering = player.isBuffering,
                        onNavigate = {
                            state.dismissDetailsToHome()
                            browseSection = it
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
                                browseSection == DesktopSection.Library ||
                                browseSection == DesktopSection.Playlists ||
                                browseSection == DesktopSection.Settings
                            if (!scoped && newQuery.isNotBlank()) {
                                browseSection = DesktopSection.Search
                            }
                        },
                        onLibraryFilter = { libraryFilter = it },
                        onPlaylist = { playlist ->
                            selectedPlaylistId = playlist.id
                            state.open(AppScreen.PlaylistDetail(playlist))
                        },
                        onArtist = { state.open(AppScreen.ArtistDetail(it)) },
                        onAlbum = { state.open(AppScreen.AlbumDetail(it)) },
                        onSong = { state.open(AppScreen.SongDetail(it)) },
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
                        onRefreshRandomArtists = { randomArtistSeed = Random.nextInt() },
                        onRefreshRandomAlbums = { randomAlbumSeed = Random.nextInt() },
                        onPrefetchHomeArtist = state::prefetchHomeArtistStats,
                        onPrefetchHomeAlbum = state::prefetchHomeAlbumStats,
                        onPlayDecadeMix = state::playDecadeMix,
                        decadeMixNotice = decadeMixNotice,
                        onClearDecadeMixNotice = state::clearDecadeMixNotice,
                        radioStations = radioStations,
                        radioStartingIds = radioStartingIds,
                        onPlayRadioStation = state::playRadioStation,
                        onPlayPersonalMix = playPersonalMix,
                        onPlayTracks = { tracks, index ->
                            state.playTracks(tracks, index)
                            state.open(AppScreen.Player)
                        },
                        onAddToUpNext = state::addToUpNext,
                        onDownload = state::download,
                        onOpenNowPlaying = { state.open(AppScreen.Player) },
                        onTogglePlayPause = state::togglePlayPause,
                        onSignOut = state::signOut,
                        onAddLocalFolder = state::addLocalFolderFromUri,
                        onRefreshLibrary = state::refreshCatalog,
                        onJellyfinPage = state::loadJellyfinLibraryPage,
                        onLibrarySortBy = state::setLibrarySortBy,
                        onLibraryAscending = state::setLibrarySortAscending,
                        onLibraryColumns = state::setLibraryColumns,
                        onHomeSections = state::setHomeSections,
                        onPersonalMix = state::setPersonalMixPreferences,
                        onExportFavoritePlaylists = state::exportFavoritePlaylists,
                        onImportFavoritePlaylists = state::importFavoritePlaylists,
                        appSettings = appSettings,
                        onCrossfadeSeconds = state::setCrossfadeSeconds,
                        onScanLibraryOnLaunch = state::setScanLibraryOnLaunch,
                        onNotifyWhenDownloadFinishes = state::setNotifyWhenDownloadFinishes,
                        downloadDirectory = downloadDirectory,
                        downloadCount = catalog.downloads.size,
                        defaultDownloadDirectoryLabel = state.defaultDownloadDirectoryLabel,
                        onDownloadDirectory = state::setDownloadDirectory,
                        onDeleteAllDownloads = state::deleteAllDownloads,
                        useLightAppearance = useLightAppearance,
                        onUseLightAppearanceChange = onUseLightAppearanceChange,
                        appearanceTintId = appearanceTintId,
                        onAppearanceTintChange = onAppearanceTintChange,
                    )
                }
                }
                }
                }
                }
            } else {
                DesktopPlayer(
                    screen = screen,
                    catalog = catalog,
                    catalogRefreshing = catalogRefreshing,
                    session = session,
                    mediaSources = mediaSources,
                    track = currentTrack,
                    homeUiState = homeUiState,
                    playHistory = playHistory,
                    upNext = upNext,
                    isPlaying = player.isPlaying,
                    isBuffering = player.isBuffering,
                    positionMs = player.positionMs,
                    bufferedPositionMs = player.bufferedPositionMs,
                    currentIndex = currentIndex,
                    lyricsTrack = lyricsTrack,
                    lyricsState = lyricsState,
                    section = browseSection,
                    selectedPlaylistId = selectedPlaylistId,
                    searchQuery = searchQuery,
                    libraryFilter = libraryFilter,
                    libraryUi = libraryUi,
                    appMessage = message,
                    pinCode = pin?.code,
                    shuffle = player.shuffle,
                    repeat = player.repeat,
                    volume = player.volume,
                    castState = cast,
                    remotePlaybackTarget = musicAssistantRemotePlayback?.target,
                    showQueue = wideDesktop,
                    compact = !wideDesktop,
                    busy = busy,
                    serversLoading = serversLoading,
                    onNavigate = {
                        state.dismissDetailsToHome()
                        browseSection = it
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
                                browseSection == DesktopSection.Library ||
                                browseSection == DesktopSection.Playlists ||
                                browseSection == DesktopSection.Settings
                        if (!scoped && newQuery.isNotBlank()) {
                            browseSection = DesktopSection.Search
                        }
                    },
                    onLibraryFilter = { libraryFilter = it },
                    onPlaylist = { playlist ->
                        selectedPlaylistId = playlist.id
                        state.open(AppScreen.PlaylistDetail(playlist))
                    },
                    onArtist = { state.open(AppScreen.ArtistDetail(it)) },
                    onAlbum = { state.open(AppScreen.AlbumDetail(it)) },
                    onSong = { state.open(AppScreen.SongDetail(it)) },
                    onOpenLyrics = { state.open(AppScreen.Lyrics(it)) },
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
                    supportedCollectionEntries = supportedCollectionEntries,
                    onRefreshRandomArtists = { randomArtistSeed = Random.nextInt() },
                    onRefreshRandomAlbums = { randomAlbumSeed = Random.nextInt() },
                    onPrefetchHomeArtist = state::prefetchHomeArtistStats,
                    onPrefetchHomeAlbum = state::prefetchHomeAlbumStats,
                    onPlayDecadeMix = state::playDecadeMix,
                    decadeMixNotice = decadeMixNotice,
                    onClearDecadeMixNotice = state::clearDecadeMixNotice,
                    radioStations = radioStations,
                    radioStartingIds = radioStartingIds,
                    onPlayRadioStation = state::playRadioStation,
                    onPlayPersonalMix = playPersonalMix,
                    onPopDetail = state::popDetail,
                    onToggle = state::togglePlayPause,
                    onPrevious = state::previous,
                    onNext = state::next,
                    onShuffle = state::toggleShuffle,
                    onRepeat = state::cycleRepeat,
                    onVolume = state::setVolume,
                    onSeek = state::seekTo,
                    onCast = state::showCastPicker,
                    onLyrics = {
                        state.dismissDetailsToHome()
                        selectedPlaylistId = null
                        browseSection = if (browseSection == DesktopSection.Lyrics) DesktopSection.Home else DesktopSection.Lyrics
                    },
                    onPlayQueue = state::playUpNext,
                    onClearQueue = state::clearQueue,
                    onMoveUpNext = state::moveUpNext,
                    onRemoveUpNext = state::removeUpNext,
                    onPlayTracks = state::playTracks,
                    onAddToUpNext = state::addToUpNext,
                    onDownload = state::download,
                    onDownloadArtist = state::download,
                    artistRadioAvailability = artistRadioAvailability,
                    onProbeArtistRadio = state::probeArtistRadio,
                    onPlayArtistRadio = state::playArtistRadio,
                    onDownloadAlbum = state::download,
                    onDownloadPlaylist = state::download,
                    onStartSignIn = state::startPlexSignIn,
                    onFinishSignIn = state::finishPlexSignIn,
                    onSignInJellyfin = state::signInJellyfin,
                    onSignInProvider = state::signInProvider,
                    jellyfinServers = jellyfinServers,
                    jellyfinDiscoveryLoading = jellyfinDiscoveryLoading,
                    jellyfinQuickConnect = jellyfinQuickConnect,
                    onDiscoverJellyfinServers = state::discoverJellyfinServers,
                    onStartJellyfinQuickConnect = state::startJellyfinQuickConnect,
                    onFinishJellyfinQuickConnect = state::finishJellyfinQuickConnect,
                    onSignOut = state::signOut,
                    onAddLocalFolder = state::addLocalFolderFromUri,
                    onRemoveLocalFolder = state::removeLocalFolder,
                    onToggleLocalFolder = state::setLocalFolderEnabled,
                    onRefreshLibrary = state::refreshCatalog,
                    onJellyfinPage = state::loadJellyfinLibraryPage,
                    servers = servers,
                    libraries = libraries,
                    librariesLoading = librariesLoading,
                    onSelectServer = { state.selectServer(it) },
                    onSelectLibrary = { library, mode -> state.selectLibrary(library, mode) },
                    onCancelPlexSetup = { state.signOut() },
                    onBackToServerPicker = { state.returnToServerPicker() },
                    onRetryServers = { state.loadServers() },
                    onLibrarySortBy = state::setLibrarySortBy,
                    onLibraryAscending = state::setLibrarySortAscending,
                    onLibraryColumns = state::setLibraryColumns,
                    onHomeSections = state::setHomeSections,
                    onPersonalMix = state::setPersonalMixPreferences,
                    onExportFavoritePlaylists = state::exportFavoritePlaylists,
                    onImportFavoritePlaylists = state::importFavoritePlaylists,
                    appSettings = appSettings,
                    onCrossfadeSeconds = state::setCrossfadeSeconds,
                    onScanLibraryOnLaunch = state::setScanLibraryOnLaunch,
                    onNotifyWhenDownloadFinishes = state::setNotifyWhenDownloadFinishes,
                    downloadDirectory = downloadDirectory,
                    downloadCount = catalog.downloads.size,
                    defaultDownloadDirectoryLabel = state.defaultDownloadDirectoryLabel,
                    onDownloadDirectory = state::setDownloadDirectory,
                    onDeleteAllDownloads = state::deleteAllDownloads,
                    useLightAppearance = useLightAppearance,
                    onUseLightAppearanceChange = onUseLightAppearanceChange,
                    appearanceTintId = appearanceTintId,
                    onAppearanceTintChange = onAppearanceTintChange,
                    onRetryLyrics = retryLyrics,
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
            visible = busy,
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
            message = playbackSnackbar,
            onDismiss = state::dismissPlaybackSnackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
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
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(message) {
        if (message != null) {
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
                    text = message.orEmpty(),
                    color = PhoebeUi.primaryText,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
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
