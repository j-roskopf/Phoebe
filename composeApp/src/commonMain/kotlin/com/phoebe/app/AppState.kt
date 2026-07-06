package com.phoebe.app

import com.phoebe.app.domain.Album
import com.phoebe.app.domain.AudioAnalysisFrame
import com.phoebe.app.domain.AppSettings
import com.phoebe.app.domain.ArtistEventsLoadState
import com.phoebe.app.domain.AudioProcessingCapabilities
import com.phoebe.app.domain.AudioProcessingSettings
import com.phoebe.app.domain.DownloadPolicySettings
import com.phoebe.app.domain.EventSettings
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.CollectionFacet
import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.domain.ArtistRadioAvailability
import com.phoebe.app.domain.HomeSection
import com.phoebe.app.domain.JellyfinSyncMode
import com.phoebe.app.domain.JellyfinLibraryPageKind
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryTab
import com.phoebe.app.domain.LyricsLoadState
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.MusicBrainzAlbumMetadataLoadState
import com.phoebe.app.domain.MusicBrainzArtistArtworkLoadState
import com.phoebe.app.domain.MobileBottomTab
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.PlayHistoryKind
import com.phoebe.app.domain.PlaybackQueueOrigin
import com.phoebe.app.domain.PlayerQueueSnapshot
import com.phoebe.app.domain.PlayerState
import com.phoebe.app.domain.PlayerTransportState
import com.phoebe.app.domain.ShellPlaybackState
import com.phoebe.app.domain.PlexPin
import com.phoebe.app.domain.PlexRadioStation
import com.phoebe.app.domain.PlexRadioStationCategory
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.PersonalMixPreferences
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.RadioDirectoryState
import com.phoebe.app.domain.RadioNowPlayingMetadata
import com.phoebe.app.domain.RadioStation
import com.phoebe.app.domain.RadioStationSearchQuery
import com.phoebe.app.domain.SavedSearch
import com.phoebe.app.domain.SmartPlaylist
import com.phoebe.app.domain.SmartPlaylistTemplate
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.TrackMetadataUpdate
import com.phoebe.app.domain.UpNextDividerMarker
import com.phoebe.app.domain.canTogglePlexLike
import com.phoebe.app.domain.catalogPrefix
import com.phoebe.app.domain.hasPlayableSource
import com.phoebe.app.domain.isEmbyFamily
import com.phoebe.app.domain.isFromLocalFolder
import com.phoebe.app.domain.isMusicAssistant
import com.phoebe.app.domain.isNavidrome
import com.phoebe.app.domain.isPlex
import com.phoebe.app.domain.playlistEntryKey
import com.phoebe.app.domain.serverAuthToken
import com.phoebe.app.domain.supportsCollectionEntry
import com.phoebe.app.domain.displayName
import com.phoebe.app.domain.supportsRemotePlaylists
import com.phoebe.app.domain.isJellyfin
import com.phoebe.app.domain.providerLabel
import com.phoebe.app.domain.providerTypeFromCatalogId
import com.phoebe.app.domain.parseAdvancedSearchQuery
import com.phoebe.app.data.DownloadServiceResult
import com.phoebe.app.data.BackupRestoreMode
import com.phoebe.app.data.JellyfinQuickConnectResult
import com.phoebe.app.data.JellyfinPlayHistorySyncResult
import com.phoebe.app.data.ListenBrainzFeedbackScore
import com.phoebe.app.data.NavidromePlayHistorySyncResult
import com.phoebe.app.data.PlayHistoryRankedEntries
import com.phoebe.app.data.PlexPlayHistorySyncResult
import com.phoebe.app.data.PlexClient
import com.phoebe.app.data.defaultPlexRadioStations
import com.phoebe.app.playlists.PlaylistExportFormat
import com.phoebe.app.player.MusicAssistantRemotePlayback
import com.phoebe.app.player.asPlayerState
import com.phoebe.app.player.isPlaybackActive
import com.phoebe.app.domain.RecentSearchItem
import com.phoebe.app.platform.MemoryPressureLevel
import com.phoebe.app.platform.PhoebeAppLifecycle
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.currentNetworkMeteringStatus
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.platform.defaultDownloadWifiOnly
import com.phoebe.app.platform.isDesktopPlatform
import com.phoebe.app.platform.discoverJellyfinServers as discoverJellyfinServersOnNetwork
import com.phoebe.app.platform.openExternalUrl
import com.phoebe.app.platform.requestNotificationPermission
import com.phoebe.app.ui.AppNavigationRequest
import com.phoebe.app.ui.CollectionMixSeed
import com.phoebe.app.updates.AppUpdateState
import io.ktor.http.Url
import kotlin.random.Random
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

data class PendingDuplicatePlaylistAdd(
    val playlist: Playlist,
    val track: Track,
    val message: String,
)

data class PlaybackSnackbarNotice(
    val message: String,
    val streamUrl: String? = null,
)

data class EventsBackendHealthState(
    val checking: Boolean = false,
    val message: String? = null,
    val success: Boolean? = null,
)

class AppState(
    private val dependencies: AppDependencies,
    private val scope: CoroutineScope,
    private val playbackScope: CoroutineScope = scope,
    private val closeDependenciesOnDispose: Boolean = true,
) {
    val session = dependencies.sessionRepository.session
    val catalog = dependencies.catalogRepository.catalog
    val downloads = dependencies.catalogRepository.downloads
    val downloadEvents = dependencies.catalogRepository.downloadEvents
    val smartPlaylists = dependencies.userArtifactsRepository.smartPlaylists
    val savedSearches = dependencies.userArtifactsRepository.savedSearches
    val catalogRefreshing: StateFlow<Boolean> = dependencies.catalogRepository.catalogRefreshing
    val catalogSyncState = dependencies.catalogRepository.catalogSyncState
    val tracksLoading = dependencies.catalogRepository.tracksLoading
    val mediaSources = dependencies.mediaSourcesRepository.state
    val cast = dependencies.castController.state
    private val mutableMusicAssistantRemotePlayback = MutableStateFlow<MusicAssistantRemotePlayback?>(null)
    val musicAssistantRemotePlayback = mutableMusicAssistantRemotePlayback.asStateFlow()
    private val mutableUpNextDivider = MutableStateFlow<UpNextDividerMarker?>(null)
    val player: StateFlow<PlayerState> = combine(
        dependencies.audioPlayer.state,
        dependencies.castController.state,
        mutableMusicAssistantRemotePlayback,
    ) { audio, castState, musicAssistantRemote ->
        when {
            castState.isPlaybackActive -> castState.asPlayerState(audio)
            musicAssistantRemote != null -> musicAssistantRemote.asPlayerState(audio)
            else -> audio
        }
    }.stateIn(playbackScope, SharingStarted.Eagerly, dependencies.audioPlayer.state.value)
    val audioAnalysis: StateFlow<AudioAnalysisFrame> = dependencies.audioPlayer.audioAnalysis
    val shellPlayback: StateFlow<ShellPlaybackState> = player
        .map { playback ->
            ShellPlaybackState(
                currentTrack = playback.currentTrack,
                isPlaying = playback.isPlaying,
                isBuffering = playback.isBuffering,
            )
        }
        .distinctUntilChanged()
        .stateIn(
            playbackScope,
            SharingStarted.Eagerly,
            ShellPlaybackState(
                currentTrack = player.value.currentTrack,
                isPlaying = player.value.isPlaying,
                isBuffering = player.value.isBuffering,
            ),
        )
    val playerTransport: StateFlow<PlayerTransportState> = player
        .map { playback ->
            PlayerTransportState(
                shuffle = playback.shuffle,
                repeat = playback.repeat,
                volume = playback.volume,
            )
        }
        .distinctUntilChanged()
        .stateIn(
            playbackScope,
            SharingStarted.Eagerly,
            PlayerTransportState(
                shuffle = player.value.shuffle,
                repeat = player.value.repeat,
                volume = player.value.volume,
            ),
        )
    val playerQueue: StateFlow<PlayerQueueSnapshot> = combine(
        player,
        mutableUpNextDivider,
    ) { playback, divider ->
            PlayerQueueSnapshot(
                queue = playback.queue,
                currentIndex = playback.currentIndex,
                upNextDivider = divider.visibleFor(playback),
            )
        }
        .distinctUntilChanged()
        .stateIn(
            playbackScope,
            SharingStarted.Eagerly,
            PlayerQueueSnapshot(
                queue = player.value.queue,
                currentIndex = player.value.currentIndex,
                upNextDivider = mutableUpNextDivider.value.visibleFor(player.value),
            ),
        )
    val libraryUi = dependencies.libraryUiRepository.preferences
    val radioDirectory: StateFlow<RadioDirectoryState> = dependencies.radioRepository.state
    private val mutableRadioNowPlaying = MutableStateFlow<RadioNowPlayingMetadata?>(null)
    val radioNowPlaying: StateFlow<RadioNowPlayingMetadata?> = mutableRadioNowPlaying.asStateFlow()
    val appSettings = dependencies.appSettingsRepository.settings
    val listenBrainzFeedbackTarget = dependencies.listenBrainzPlaybackReporter.feedbackTarget
    val listenBrainzCredentialAvailability = dependencies.listenBrainzAccountRepository.storageAvailability
    private val mutablePersistEqualizerSettings = MutableStateFlow(false)
    private val mutableEqualizerProfile = MutableStateFlow(EqualizerProfile.Default.normalized())
    val equalizerProfile: StateFlow<EqualizerProfile> = mutableEqualizerProfile.asStateFlow()
    val equalizerRemoteUnavailable: StateFlow<Boolean> = combine(
        cast,
        mutableMusicAssistantRemotePlayback,
    ) { castState, musicAssistantRemote ->
        castState.isPlaybackActive || musicAssistantRemote != null
    }.stateIn(playbackScope, SharingStarted.Eagerly, false)
    val lastPlayedByArtist = dependencies.playHistoryRepository.lastPlayedByArtist
    val lastPlayedByAlbum = dependencies.playHistoryRepository.lastPlayedByAlbum
    val lastPlayedByTrack = dependencies.playHistoryRepository.lastPlayedByTrack
    val playCountsByTrack = dependencies.playHistoryRepository.playCountsByTrack
    val playEventsByTrack = dependencies.playHistoryRepository.playEventsByTrack
    val topMostPlayed = dependencies.playHistoryRepository.topMostPlayed
    val topRecentlyPlayed = dependencies.playHistoryRepository.topRecentlyPlayed
    val recentSearchItems = dependencies.searchHistoryRepository.items
    val defaultDownloadDirectoryLabel: String = dependencies.platformStorage.defaultDownloadDirectoryLabel()

    val appUpdateState: StateFlow<AppUpdateState> = dependencies.appUpdateService.state
    val pendingUpdateInstallConfirmation = dependencies.appUpdateService.pendingInstallConfirmation

    val navigationRequests = dependencies.navigationService.requests
    val routeViewModelFactory = dependencies.routeViewModelFactory

    private val mutableTab = MutableStateFlow(LibraryTab.Albums)
    val tab: StateFlow<LibraryTab> = mutableTab

    private val mutablePin = MutableStateFlow<PlexPin?>(null)
    val pin: StateFlow<PlexPin?> = mutablePin

    private val mutableServers = MutableStateFlow<List<PlexServer>>(emptyList())
    val servers: StateFlow<List<PlexServer>> = mutableServers

    private val mutableJellyfinServers = MutableStateFlow<List<PlexServer>>(emptyList())
    val jellyfinServers: StateFlow<List<PlexServer>> = mutableJellyfinServers

    private val mutableJellyfinDiscoveryLoading = MutableStateFlow(false)
    val jellyfinDiscoveryLoading: StateFlow<Boolean> = mutableJellyfinDiscoveryLoading

    private val mutableJellyfinQuickConnect = MutableStateFlow<JellyfinQuickConnectResult?>(null)
    val jellyfinQuickConnect: StateFlow<JellyfinQuickConnectResult?> = mutableJellyfinQuickConnect

    private val mutableLibraries = MutableStateFlow<List<MusicLibrary>>(emptyList())
    val libraries: StateFlow<List<MusicLibrary>> = mutableLibraries

    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy

    private val mutableServersLoading = MutableStateFlow(false)
    val serversLoading: StateFlow<Boolean> = mutableServersLoading

    private val mutableLibrariesLoading = MutableStateFlow(false)
    val librariesLoading: StateFlow<Boolean> = mutableLibrariesLoading

    private val mutableAuthInProgress = MutableStateFlow(false)
    val authInProgress: StateFlow<Boolean> = mutableAuthInProgress

    private val mutableMessage = MutableStateFlow("Sign in to your provider, or add a local music folder to get started.")
    val message: StateFlow<String> = mutableMessage

    private val mutablePendingDuplicatePlaylistAdd = MutableStateFlow<PendingDuplicatePlaylistAdd?>(null)
    val pendingDuplicatePlaylistAdd: StateFlow<PendingDuplicatePlaylistAdd?> = mutablePendingDuplicatePlaylistAdd.asStateFlow()

    private val mutablePlaybackSnackbar = MutableStateFlow<PlaybackSnackbarNotice?>(null)
    val playbackSnackbar: StateFlow<PlaybackSnackbarNotice?> = mutablePlaybackSnackbar.asStateFlow()

    private val mutableDecadeMixNotice = MutableStateFlow<String?>(null)
    val decadeMixNotice: StateFlow<String?> = mutableDecadeMixNotice

    private val mutableRadioStations = MutableStateFlow<List<PlexRadioStation>>(emptyList())
    val radioStations: StateFlow<List<PlexRadioStation>> = mutableRadioStations

    private val mutableRadioStartingIds = MutableStateFlow<Set<String>>(emptySet())
    val radioStartingIds: StateFlow<Set<String>> = mutableRadioStartingIds

    private val mutableInternetRadioStartingIds = MutableStateFlow<Set<String>>(emptySet())
    val internetRadioStartingIds: StateFlow<Set<String>> = mutableInternetRadioStartingIds

    private val mutableArtistRadioAvailability = MutableStateFlow<Map<String, ArtistRadioAvailability>>(emptyMap())
    val artistRadioAvailability: StateFlow<Map<String, ArtistRadioAvailability>> = mutableArtistRadioAvailability
    private val mutableArtistEvents = MutableStateFlow<Map<String, ArtistEventsLoadState>>(emptyMap())
    val artistEvents: StateFlow<Map<String, ArtistEventsLoadState>> = mutableArtistEvents.asStateFlow()
    private val mutableAlbumMusicBrainzMetadata = MutableStateFlow<Map<String, MusicBrainzAlbumMetadataLoadState>>(emptyMap())
    val albumMusicBrainzMetadata: StateFlow<Map<String, MusicBrainzAlbumMetadataLoadState>> =
        mutableAlbumMusicBrainzMetadata.asStateFlow()
    private val mutableArtistMusicBrainzArtwork = MutableStateFlow<Map<String, MusicBrainzArtistArtworkLoadState>>(emptyMap())
    val artistMusicBrainzArtwork: StateFlow<Map<String, MusicBrainzArtistArtworkLoadState>> =
        mutableArtistMusicBrainzArtwork.asStateFlow()
    private val mutableEventsBackendHealth = MutableStateFlow(EventsBackendHealthState())
    val eventsBackendHealth: StateFlow<EventsBackendHealthState> = mutableEventsBackendHealth.asStateFlow()

    private val mutableDownloadDirectory = MutableStateFlow<String?>(null)
    val downloadDirectory: StateFlow<String?> = mutableDownloadDirectory

    private val mutableActiveDownloadJobCount = MutableStateFlow(0)
    val activeDownloadJobCount: StateFlow<Int> = mutableActiveDownloadJobCount
    val audioProcessingCapabilities: AudioProcessingCapabilities
        get() = dependencies.audioPlayer.audioProcessingCapabilities

    private var collectionMixGeneration = 0
    private var keepPlayingQueueGeneration = 0
    private var keepPlayingDisabledGeneration: Int? = null
    private var keepPlayingOrigin: PlaybackQueueOrigin? = null
    private var keepPlayingRequestedSignature: String? = null
    private var keepPlayingAdvanceOnAppendSignature: String? = null
    private var keepPlayingPreviewPendingGeneration: Int? = null
    private var keepPlayingJob: Job? = null
    private var recentAlbumWarmSignature: String? = null
    private var playedAlbumWarmSignature: String? = null
    private var mostPlayedWarmSignature: String? = null
    private var popularMixSeedSignature: String? = null
    private var popularMixSeedTracks: List<Track> = emptyList()
    private var popularMixSeedBuildSignature: String? = null
    private var popularMixSeedBuildDeferred: Deferred<List<Track>>? = null
    private var topTracksMixWarmSignature: String? = null
    private var topTracksMixBuildSignature: String? = null
    private var topTracksMixBuildDeferred: Deferred<List<Track>>? = null
    private val prefetchedArtistIds = mutableSetOf<String>()
    private val prefetchedAlbumIds = mutableSetOf<String>()
    private val prefetchedMixBuilderArtistIds = mutableSetOf<String>()
    private var catalogRefreshJob: Job? = null
    private var playHistorySyncJob: Job? = null
    private var providerPlayHistoryRefreshJob: Job? = null
    private var plexPlayCountRefreshJob: Job? = null
    private var lightweightRemoteSyncJob: Job? = null
    private var downloadedArtworkCacheJob: Job? = null
    private var artistDetailPreloadKey: String? = null
    private var artistDetailPreloadJob: Job? = null
    private val activeDownloadJobs = mutableSetOf<Job>()
    private var lastPlaybackHistoryRecord = PlaybackHistoryRecord()
    private var pendingLastFmAuth: PendingLastFmAuth? = null
    private val artistEventJobs = mutableMapOf<String, Job>()
    private val albumMusicBrainzJobs = mutableMapOf<String, Job>()
    private val artistMusicBrainzArtworkJobs = mutableMapOf<String, Job>()
    private var disposed = false

    fun dispose() {
        if (disposed) return
        disposed = true
        listOfNotNull(
            catalogRefreshJob,
            playHistorySyncJob,
            providerPlayHistoryRefreshJob,
            plexPlayCountRefreshJob,
            lightweightRemoteSyncJob,
            downloadedArtworkCacheJob,
            artistDetailPreloadJob,
            keepPlayingJob,
            popularMixSeedBuildDeferred,
            topTracksMixBuildDeferred,
        ).forEach { it.cancel() }
        albumMusicBrainzJobs.values.toList().forEach { it.cancel() }
        artistMusicBrainzArtworkJobs.values.toList().forEach { it.cancel() }
        artistEventJobs.values.toList().forEach { it.cancel() }
        activeDownloadJobs.toList().forEach { it.cancel() }
        catalogRefreshJob = null
        playHistorySyncJob = null
        providerPlayHistoryRefreshJob = null
        plexPlayCountRefreshJob = null
        lightweightRemoteSyncJob = null
        downloadedArtworkCacheJob = null
        artistDetailPreloadJob = null
        keepPlayingJob = null
        popularMixSeedBuildDeferred = null
        popularMixSeedBuildSignature = null
        popularMixSeedSignature = null
        popularMixSeedTracks = emptyList()
        topTracksMixBuildDeferred = null
        topTracksMixBuildSignature = null
        if (!closeDependenciesOnDispose) return
        if (isDesktopPlatform()) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                dependencies.close()
            }
        } else {
            dependencies.close()
        }
    }

    private fun publishActiveDownloadJobCount() {
        mutableActiveDownloadJobCount.value = activeDownloadJobs.size
    }

    private suspend fun applyPlatformDownloadPolicyDefault() {
        val policy = appSettings.value.downloadPolicy.normalized()
        if (policy.wifiOnlyConfigured) return
        dependencies.appSettingsRepository.setDownloadPolicySettings(
            policy.copy(
                wifiOnly = defaultDownloadWifiOnly(),
                wifiOnlyConfigured = true,
            ),
        )
    }

    init {
        scope.launch {
            player
                .map { it.currentTrack }
                .distinctUntilChangedBy { it?.id }
                .collectLatest { track ->
                    if (track?.id?.startsWith("radio:") != true) {
                        mutableRadioNowPlaying.value = null
                        return@collectLatest
                    }
                    mutableRadioNowPlaying.value = null
                    while (currentCoroutineContext().isActive) {
                        val metadata = runCatching {
                            dependencies.radioNowPlayingRepository.resolve(track)
                        }.getOrNull()
                        mutableRadioNowPlaying.value = metadata
                            ?.takeIf { it.hasTrack }
                            ?.copy(trackId = track.id)
                        delay(RadioNowPlayingRefreshMs)
                    }
                }
        }
        scope.launch {
            PhoebeLog.d("AppState") { "startup restore begin" }
            // Session and local folders are restored in [AppDependencies.create] so the first frame
            // can skip Sign-in; repeat here for callers that inject dependencies without that path.
            dependencies.sessionRepository.restore(refreshConnections = false)
            dependencies.mediaSourcesRepository.restore()
            dependencies.appSettingsRepository.restore()
            applyPlatformDownloadPolicyDefault()
            dependencies.listenBrainzAccountRepository.restore()
            dependencies.lastFmAccountRepository.restore()
            val restoredSettings = appSettings.value
            mutablePersistEqualizerSettings.value = restoredSettings.persistEqualizerSettings
            val startupEqualizer = if (restoredSettings.persistEqualizerSettings) {
                restoredSettings.equalizerProfile.normalized()
            } else {
                EqualizerProfile.Default.normalized()
            }
            mutableEqualizerProfile.value = startupEqualizer
            dependencies.audioPlayer.setEqualizer(startupEqualizer)
            if (isDesktopPlatform() && restoredSettings.persistVolumeSettings) {
                setVolume(restoredSettings.savedVolume)
            }
            dependencies.libraryUiRepository.restore()
            dependencies.radioRepository.restore()
            dependencies.searchHistoryRepository.restore()
            dependencies.audioPlayer.setCrossfadeDurationMs(appSettings.value.crossfadeSeconds * 1_000L)
            dependencies.playHistoryRepository.restore()
            mutableDownloadDirectory.value = dependencies.platformStorage.readDownloadDirectory()
            checkForUpdatesInBackground()
            requestNavigation(defaultBrowseRequest(session.value))
            if (session.value?.token?.isNotBlank() == true && session.value?.selectedServer == null) {
                refreshServers()
            }
            dependencies.catalogRepository.restoreCachedCatalog()
            refreshInternetRadio()
            syncLightweightRemoteStateInBackground()
            val hasRemoteLibrary = session.value?.selectedLibrary != null
            val hasLocalFolders = mediaSources.value.localFolders.any { it.enabled }
            val refreshedMissingLocalOnlyCatalog = !hasRemoteLibrary &&
                hasLocalFolders &&
                !dependencies.catalogRepository.catalog.value.hasBrowseableContent()
            if (refreshedMissingLocalOnlyCatalog) {
                refreshCatalogSuspended(catalogMessage = null)
            }
            if (appSettings.value.scanLibraryOnLaunch &&
                (hasRemoteLibrary || (hasLocalFolders && !refreshedMissingLocalOnlyCatalog))
            ) {
                delay(500)
                cancelLightweightRemoteSync()
                if (session.value.isPlex()) {
                    dependencies.sessionRepository.refreshSelectedServerConnections()
                    dependencies.sessionRepository.warmServerConnection()
                }
                refreshCatalogSuspended(catalogMessage = null, backgroundIfCached = true)
            }
            if (session.value.isEmbyFamily() &&
                session.value?.selectedLibrary != null &&
                !dependencies.catalogRepository.catalog.value.hasBrowseableContent()
            ) {
                refreshCatalogSuspended(catalogMessage = "Library refreshed.")
            }
            cacheDownloadedArtworkInBackground()
            warmPlaylistTracksInBackground()
            ensureLikedSongsPlaylistIfPossible()
            if (session.value?.token?.isNotBlank() == true &&
                session.value?.selectedServer != null &&
                session.value.isPlex() &&
                !appSettings.value.scanLibraryOnLaunch
            ) {
                launch {
                    dependencies.sessionRepository.refreshSelectedServerConnections()
                    dependencies.sessionRepository.warmServerConnection()
                }
            }
            PhoebeLog.d("AppState") {
                "startup restore complete → destination=${defaultBrowseRequest(session.value)}, " +
                    "session=${session.value?.userName ?: "none"}, " +
                    "localFolders=${mediaSources.value.localFolders.size}"
            }
        }
        bindSystemVolume()
        bindAppSettingsToPlayback()
        recordPlaybackHistory()
        surfacePlaybackFailures()
        surfaceCastMessages()
        monitorKeepPlaying()
        dependencies.plexPlaybackReporter.start(scope)
        syncPlayHistoryAfterProviderReports()
        dependencies.listenBrainzPlaybackReporter.start(scope)
        dependencies.lastFmPlaybackReporter.start(scope)
    }

    private fun checkForUpdatesInBackground() {
        scope.launch {
            dependencies.appUpdateService.checkForUpdates { error ->
                PhoebeLog.d("Update") { "release check failed: ${error.message}" }
            }
        }
    }

    private fun bindAppSettingsToPlayback() {
        var lastEventSettings = appSettings.value.events.normalized()
        scope.launch {
            appSettings.collect { settings ->
                mutablePersistEqualizerSettings.value = settings.persistEqualizerSettings
                dependencies.audioPlayer.setCrossfadeDurationMs(settings.crossfadeSeconds * 1_000L)
                dependencies.audioPlayer.setAudioProcessing(settings.audioProcessing)
                val eventSettings = settings.events.normalized()
                if (eventSettings != lastEventSettings) {
                    lastEventSettings = eventSettings
                    clearBackendContentCaches()
                }
                if (settings.persistEqualizerSettings) {
                    val profile = settings.equalizerProfile.normalized()
                    if (mutableEqualizerProfile.value != profile) {
                        mutableEqualizerProfile.value = profile
                        dependencies.audioPlayer.setEqualizer(profile)
                    }
                }
            }
        }
    }

    private fun clearBackendContentCaches() {
        artistEventJobs.values.toList().forEach { it.cancel() }
        albumMusicBrainzJobs.values.toList().forEach { it.cancel() }
        artistMusicBrainzArtworkJobs.values.toList().forEach { it.cancel() }
        artistEventJobs.clear()
        albumMusicBrainzJobs.clear()
        artistMusicBrainzArtworkJobs.clear()
        mutableArtistEvents.value = emptyMap()
        mutableAlbumMusicBrainzMetadata.value = emptyMap()
        mutableArtistMusicBrainzArtwork.value = emptyMap()
    }

    private fun surfacePlaybackFailures() {
        scope.launch {
            var lastSerial = dependencies.audioPlayer.state.value.playbackErrorSerial
            dependencies.audioPlayer.state.collect { state ->
                if (state.playbackErrorSerial == lastSerial) return@collect
                lastSerial = state.playbackErrorSerial
                val title = state.currentTrack?.title?.takeIf { it.isNotBlank() }
                val notice = state.playbackErrorMessage
                    ?: title?.let { "Couldn't play $it." }
                    ?: "Couldn't play that song."
                mutableMessage.value = notice
                surfacePlaybackSnackbar(notice, state.currentTrack?.radioPlaybackStreamUrlOrNull())
            }
        }
        scope.launch {
            var lastSerial = dependencies.audioPlayer.state.value.playbackNoticeSerial
            dependencies.audioPlayer.state.collect { state ->
                if (state.playbackNoticeSerial == lastSerial) return@collect
                lastSerial = state.playbackNoticeSerial
                val notice = state.playbackNoticeMessage ?: return@collect
                mutableMessage.value = notice
                surfacePlaybackSnackbar(notice)
            }
        }
    }

    private fun surfaceCastMessages() {
        scope.launch {
            dependencies.castController.state
                .map { it.message }
                .distinctUntilChanged()
                .collect { notice ->
                    val message = notice?.takeIf { it.isNotBlank() } ?: return@collect
                    if (message == "Chromecast requires Chrome with Cast support.") return@collect
                    if (message.startsWith("Sending ") && message.endsWith(" to Chromecast...")) return@collect
                    mutableMessage.value = message
                    surfacePlaybackSnackbar(message)
                }
        }
    }

    fun dismissPlaybackSnackbar() {
        mutablePlaybackSnackbar.value = null
    }

    private fun surfacePlaybackSnackbar(message: String, streamUrl: String? = null) {
        mutablePlaybackSnackbar.value = PlaybackSnackbarNotice(
            message = message,
            streamUrl = streamUrl?.takeIf { it.isNotBlank() },
        )
    }

    private fun Track.radioPlaybackStreamUrlOrNull(): String? =
        streamUrl.takeIf { id.startsWith("radio:") && it.isNotBlank() }

    private fun surfaceTransientNotice(notice: String) {
        mutableMessage.value = notice
        surfacePlaybackSnackbar(notice)
    }

    private fun monitorKeepPlaying() {
        scope.launch {
            combine(
                dependencies.audioPlayer.state,
                appSettings,
                dependencies.castController.state,
                mutableMusicAssistantRemotePlayback,
            ) { playback, settings, castState, musicAssistantRemote ->
                KeepPlayingSignal(
                    playback = playback,
                    enabled = settings.keepPlayingEnabled,
                    castConnected = castState.isConnected,
                    musicAssistantRemoteActive = musicAssistantRemote != null,
                )
            }.collect { signal ->
                clearKeepPlayingDividerIfReached(signal.playback)
                val previewStarted = if (signal.enabled &&
                    keepPlayingPreviewPendingGeneration == keepPlayingQueueGeneration
                ) {
                    requestKeepPlayingPreview()
                } else {
                    false
                }
                if (previewStarted) {
                    keepPlayingPreviewPendingGeneration = null
                } else {
                    maybeStartKeepPlaying(signal)
                }
            }
        }
    }

    private fun clearKeepPlayingDividerIfReached(playback: PlayerState) {
        val divider = mutableUpNextDivider.value ?: return
        if (playback.currentIndex >= divider.beforeQueueIndex || divider.beforeQueueIndex > playback.queue.size) {
            mutableUpNextDivider.value = null
        }
    }

    private fun maybeStartKeepPlaying(signal: KeepPlayingSignal) {
        startKeepPlayingForSignal(
            signal = signal,
            requireActivePlayback = true,
            advanceWhenAppended = false,
            requireNearEnd = true,
        )
    }

    private fun startKeepPlayingForSignal(
        signal: KeepPlayingSignal,
        requireActivePlayback: Boolean,
        advanceWhenAppended: Boolean,
        requireNearEnd: Boolean,
        forceRequest: Boolean = false,
    ): Boolean {
        val playback = signal.playback
        if (!signal.enabled || signal.castConnected || signal.musicAssistantRemoteActive) return false
        if (requireActivePlayback && !playback.isPlaying) return false
        if (playback.isBuffering || playback.repeat != RepeatMode.Off) return false
        if (playback.currentIndex !in playback.queue.indices) return false
        if (keepPlayingDisabledGeneration == keepPlayingQueueGeneration) return false
        if (requireNearEnd && !playback.shouldTriggerKeepPlaying()) return false
        if (!advanceWhenAppended && mutableUpNextDivider.value.visibleFor(playback) != null) return false
        val signature = keepPlayingSignature(playback.queue, keepPlayingQueueGeneration)
        if (keepPlayingJob?.isActive == true) {
            if (signature == keepPlayingRequestedSignature && advanceWhenAppended) {
                keepPlayingAdvanceOnAppendSignature = signature
                return true
            }
            return false
        }
        if (signature == keepPlayingRequestedSignature && !forceRequest) return false
        if (advanceWhenAppended) {
            keepPlayingAdvanceOnAppendSignature = signature
        } else {
            mutableUpNextDivider.value = UpNextDividerMarker("Extending...", playback.queue.size)
        }
        keepPlayingRequestedSignature = signature
        val origin = keepPlayingOrigin ?: playback.queue.toTrackListOrigin()
        val queue = playback.queue
        val generation = keepPlayingQueueGeneration
        val shuffle = playback.shuffle
        val job = scope.launch {
            try {
                appendKeepPlayingContinuation(
                    origin = origin,
                    capturedQueue = queue,
                    generation = generation,
                    signature = signature,
                    shuffle = shuffle,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                PhoebeLog.d("AppState") { "Keep Playing continuation failed: ${error.message}" }
                clearKeepPlayingPendingDivider(signature)
                clearKeepPlayingRequest(signature)
            }
        }
        keepPlayingJob = job
        job.invokeOnCompletion {
            if (keepPlayingJob === job) {
                keepPlayingJob = null
            }
        }
        return true
    }

    private suspend fun nativeKeepPlayingCandidatesWithinBudget(
        origin: PlaybackQueueOrigin,
        capturedQueue: List<Track>,
    ): List<Track> {
        var timedOut = false
        val candidates = withTimeoutOrNull(KeepPlayingNativeCandidateTimeoutMs) {
            try {
                nativeKeepPlayingCandidates(origin, capturedQueue)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                PhoebeLog.d("AppState") { "Keep Playing native suggestions failed: ${error.message}" }
                emptyList()
            }
        } ?: run {
            timedOut = true
            emptyList()
        }
        if (timedOut) {
            PhoebeLog.d("AppState") { "Keep Playing native suggestions timed out." }
        }
        return candidates
    }

    private fun requestKeepPlayingPreview(forceRequest: Boolean = false): Boolean {
        val playback = dependencies.audioPlayer.state.value
        return startKeepPlayingForSignal(
            signal = KeepPlayingSignal(
                playback = playback,
                enabled = true,
                castConnected = dependencies.castController.state.value.isConnected,
                musicAssistantRemoteActive = mutableMusicAssistantRemotePlayback.value != null,
            ),
            requireActivePlayback = false,
            advanceWhenAppended = false,
            requireNearEnd = false,
            forceRequest = forceRequest,
        )
    }

    private suspend fun appendKeepPlayingContinuation(
        origin: PlaybackQueueOrigin,
        capturedQueue: List<Track>,
        generation: Int,
        signature: String,
        shuffle: Boolean,
    ) {
        val additions = withContext(Dispatchers.Default) {
            val nativeCandidates = nativeKeepPlayingCandidatesWithinBudget(origin, capturedQueue)
            val plan = planQueueContinuation(
                catalog = catalog.value,
                origin = origin,
                currentQueue = capturedQueue,
                nativeCandidates = nativeCandidates,
                recentTrackIds = recentKeepPlayingTrackIds(),
                allowWeakFallback = nativeCandidates.isEmpty(),
            ) ?: return@withContext emptyList()
            plan.tracks
                .withFreshPlaybackUrls(session.value)
                .let { tracks -> if (shuffle) tracks.shuffled() else tracks }
        }
        if (additions.isEmpty()) {
            clearKeepPlayingPendingDivider(signature)
            clearKeepPlayingAdvanceRequest(signature)
            return
        }

        val current = dependencies.audioPlayer.state.value
        if (generation != keepPlayingQueueGeneration) {
            clearKeepPlayingPendingDivider(signature)
            clearKeepPlayingAdvanceRequest(signature)
            return
        }
        if (keepPlayingDisabledGeneration == generation) {
            clearKeepPlayingPendingDivider(signature)
            clearKeepPlayingAdvanceRequest(signature)
            return
        }
        if (signature != keepPlayingSignature(current.queue, generation)) {
            clearKeepPlayingPendingDivider(signature)
            clearKeepPlayingAdvanceRequest(signature)
            return
        }
        val shouldAdvanceAfterAppend = keepPlayingAdvanceOnAppendSignature == signature ||
            current.hasEndedAtQueueTail()
        if (!shouldAdvanceAfterAppend && !current.isPlaying) {
            clearKeepPlayingPendingDivider(signature)
            clearKeepPlayingRequest(signature)
            return
        }
        if (current.repeat != RepeatMode.Off) {
            clearKeepPlayingPendingDivider(signature)
            clearKeepPlayingAdvanceRequest(signature)
            return
        }
        if (dependencies.castController.state.value.isConnected || mutableMusicAssistantRemotePlayback.value != null) {
            clearKeepPlayingPendingDivider(signature)
            clearKeepPlayingAdvanceRequest(signature)
            return
        }

        val firstAppendedIndex = current.queue.size
        dependencies.playbackTransportService.appendToQueue(additions)
        mutableUpNextDivider.value = UpNextDividerMarker("Extended", firstAppendedIndex)
        if (shouldAdvanceAfterAppend) {
            keepPlayingAdvanceOnAppendSignature = null
            val afterAppend = dependencies.audioPlayer.state.value
            if (afterAppend.currentIndex == firstAppendedIndex - 1 &&
                afterAppend.queue.lastIndex >= firstAppendedIndex
            ) {
                dependencies.playbackTransportService.next()
            }
        }
    }

    private fun clearKeepPlayingPendingDivider(signature: String) {
        val divider = mutableUpNextDivider.value ?: return
        if (divider.label == "Extending..." &&
            divider.beforeQueueIndex == signature.keepPlayingSignatureQueueSize()
        ) {
            mutableUpNextDivider.value = null
        }
    }

    private fun clearKeepPlayingRequest(signature: String) {
        if (keepPlayingRequestedSignature == signature) {
            keepPlayingRequestedSignature = null
        }
        clearKeepPlayingAdvanceRequest(signature)
    }

    private fun clearKeepPlayingAdvanceRequest(signature: String) {
        if (keepPlayingAdvanceOnAppendSignature == signature) {
            keepPlayingAdvanceOnAppendSignature = null
        }
    }

    private suspend fun nativeKeepPlayingCandidates(
        origin: PlaybackQueueOrigin,
        currentQueue: List<Track>,
    ): List<Track> =
        when (origin) {
            is PlaybackQueueOrigin.Artist -> {
                if (!origin.id.startsWith("plex:") &&
                    !origin.id.startsWith("jellyfin:") &&
                    !origin.id.startsWith("emby:")
                ) {
                    emptyList()
                } else {
                    dependencies.catalogRepository.playArtistRadio(
                        session.value,
                        Artist(id = origin.id, title = origin.title),
                    )
                }
            }
            is PlaybackQueueOrigin.Album -> {
                nativeKeepPlayingCandidatesForItems(
                    providerType = origin.providerType,
                    primaryItemId = origin.id,
                    seedTrackIds = origin.seedTrackIds,
                    currentQueue = currentQueue,
                )
            }
            is PlaybackQueueOrigin.Playlist -> {
                nativeKeepPlayingCandidatesForItems(
                    providerType = origin.providerType,
                    primaryItemId = origin.id,
                    seedTrackIds = origin.seedTrackIds,
                    currentQueue = currentQueue,
                )
            }
            is PlaybackQueueOrigin.Radio -> {
                if (origin.providerType != MediaProviderType.Plex || origin.key.isBlank()) {
                    emptyList()
                } else {
                    dependencies.catalogRepository.playRadioStation(
                        session.value,
                        PlexRadioStation(
                            id = origin.id,
                            title = origin.title,
                            subtitle = "Plex radio",
                            key = origin.key,
                            category = PlexRadioStationCategory.Library,
                        ),
                    )
                }
            }
            is PlaybackQueueOrigin.Mix,
            is PlaybackQueueOrigin.TrackList -> emptyList()
        }

    private suspend fun nativeKeepPlayingCandidatesForItems(
        providerType: MediaProviderType?,
        primaryItemId: String,
        seedTrackIds: List<String>,
        currentQueue: List<Track>,
    ): List<Track> {
        val itemIds = buildList {
            add(primaryItemId)
            currentQueue.take(KeepPlayingNativeSeedItemLimit).mapTo(this) { it.id }
            currentQueue.takeLast(KeepPlayingNativeSeedItemLimit).mapTo(this) { it.id }
            seedTrackIds.take(KeepPlayingNativeSeedItemLimit).forEach(::add)
            seedTrackIds.takeLast(KeepPlayingNativeSeedItemLimit).forEach(::add)
        }.distinct()

        val startedAtMs = currentTimeMs()
        val candidates = mutableListOf<Track>()
        for (itemId in itemIds) {
            val remainingMs = KeepPlayingNativeItemsBudgetMs - (currentTimeMs() - startedAtMs)
            if (remainingMs <= 0L) {
                PhoebeLog.d("AppState") { "Keep Playing native item suggestions reached time budget." }
                break
            }
            val itemProviderType = providerType ?: itemId.providerTypeFromCatalogId()
            if (!itemId.belongsToProviderType(itemProviderType)) continue
            val itemTimeoutMs = minOf(KeepPlayingNativeItemTimeoutMs, remainingMs)
            val related = try {
                withTimeoutOrNull(itemTimeoutMs) {
                    nativeKeepPlayingCandidatesForItem(itemProviderType, itemId)
                } ?: run {
                    PhoebeLog.d("AppState") { "Keep Playing native suggestions timed out for $itemId." }
                    emptyList()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                PhoebeLog.d("AppState") { "Keep Playing native suggestions failed for $itemId: ${error.message}" }
                emptyList()
            }
            candidates += related
        }
        return candidates.distinctBy { it.id }
    }

    private suspend fun nativeKeepPlayingCandidatesForItem(
        providerType: MediaProviderType?,
        itemId: String,
    ): List<Track> =
        when (providerType) {
            MediaProviderType.Plex -> dependencies.catalogRepository.plexSimilarTracksForItem(session.value, itemId)
            MediaProviderType.Jellyfin,
            MediaProviderType.Emby -> dependencies.catalogRepository.instantMixForItem(session.value, itemId)
            MediaProviderType.Navidrome,
            MediaProviderType.MusicAssistant,
            null -> emptyList()
        }

    private fun String.belongsToProviderType(providerType: MediaProviderType?): Boolean {
        val prefix = providerType?.catalogPrefix ?: return false
        return startsWith("$prefix:")
    }

    private fun recentKeepPlayingTrackIds(): Set<String> =
        lastPlayedByTrack.value.entries
            .sortedByDescending { it.value }
            .take(KeepPlayingRecentTrackLimit)
            .mapTo(mutableSetOf()) { it.key }

    private fun startKeepPlayingQueue(origin: PlaybackQueueOrigin?, queue: List<Track>) {
        keepPlayingQueueGeneration++
        keepPlayingDisabledGeneration = null
        keepPlayingOrigin = origin ?: queue.toTrackListOrigin()
        keepPlayingRequestedSignature = null
        keepPlayingAdvanceOnAppendSignature = null
        keepPlayingPreviewPendingGeneration = null
        keepPlayingJob?.cancel()
        keepPlayingJob = null
        mutableUpNextDivider.value = null
    }

    private fun markKeepPlayingQueueEditedByUser() {
        keepPlayingDisabledGeneration = keepPlayingQueueGeneration
        clearKeepPlayingContinuationState()
    }

    private fun clearKeepPlayingContinuationState() {
        keepPlayingRequestedSignature = null
        keepPlayingAdvanceOnAppendSignature = null
        keepPlayingPreviewPendingGeneration = null
        keepPlayingJob?.cancel()
        keepPlayingJob = null
        mutableUpNextDivider.value = null
    }

    /**
     * Each time the audio player transitions to a new track, record a play event
     * so the Library UI can surface "last played" timestamps per artist / album / song.
     * We watch [Track.id] rather than the [PlayerState] object so toggling pause /
     * seeking doesn't double-record the same play. Remote provider imports later claim
     * nearby local rows, so the UI gets instant feedback without double-counting after sync.
     */
    private fun recordPlaybackHistory() {
        scope.launch {
            var lastObservedPlayingTrackId: String? = null
            player
                .map { state ->
                    PlaybackHistorySignal(
                        track = state.currentTrack,
                        isPlaying = state.isPlaying,
                        isBuffering = state.isBuffering,
                    )
                }
                .distinctUntilChanged()
                .collect { signal ->
                    val track = signal.track ?: run {
                        lastObservedPlayingTrackId = null
                        return@collect
                    }
                    if (!signal.isPlaying || signal.isBuffering) return@collect
                    if (track.id == lastObservedPlayingTrackId) return@collect
                    lastObservedPlayingTrackId = track.id
                    recordPlaybackStarted(track)
                }
        }
    }

    private fun recordPlaybackStarted(track: Track, playedAtMs: Long = currentTimeMs()) {
        val historyTrack = track.canonicalPlayHistoryTrack()
        val trackId = historyTrack.id
        if (trackId.isBlank()) return
        val previous = lastPlaybackHistoryRecord
        if (
            previous.trackId == trackId &&
            playedAtMs >= previous.playedAtMs &&
            playedAtMs - previous.playedAtMs < PlaybackHistoryDedupeWindowMs
        ) {
            return
        }
        lastPlaybackHistoryRecord = PlaybackHistoryRecord(trackId = trackId, playedAtMs = playedAtMs)
        scope.launch {
            val recorded = runCatching {
                dependencies.playHistoryRepository.recordPlay(historyTrack, playedAtMs)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                PhoebeLog.d("AppState") { "play history record failed for '$trackId': ${error.message}" }
            }.isSuccess
            if (recorded) {
                scope.launch {
                    dependencies.plexPlaybackReporter.markPlayed(historyTrack, playedAtMs)
                }
            }
        }
    }

    private fun Track.canonicalPlayHistoryTrack(): Track =
        catalog.value.findTrackById(id) ?: this

    private fun CatalogSnapshot.findTrackById(trackId: String): Track? {
        if (trackId.isBlank()) return null
        tracksByParent.values.forEach { parentTracks ->
            parentTracks.firstOrNull { track -> track.matchesTrackId(trackId) }?.let { return it }
        }
        return null
    }

    private fun Track.matchesTrackId(trackId: String): Boolean {
        if (id == trackId) return true
        val idPrefix = providerPrefixForTrackId(id)
        if (idPrefix != null && id.removePrefix(idPrefix) == trackId) return true
        val trackIdPrefix = providerPrefixForTrackId(trackId)
        return trackIdPrefix != null && trackId.removePrefix(trackIdPrefix) == id
    }

    private fun providerPrefixForTrackId(trackId: String): String? {
        for (provider in MediaProviderType.entries) {
            val prefix = "${provider.catalogPrefix}:"
            if (trackId.startsWith(prefix)) return prefix
        }
        return null
    }

    private fun MutableMap<String, Track>.putFirst(key: String, track: Track) {
        if (key !in this) {
            this[key] = track
        }
    }

    /**
     * When the OS exposes a system volume, the slider mirrors it: the per-player output
     * stays at unity and hardware volume keys / rockers propagate into PlayerState.volume
     * so the UI updates live. On platforms without system volume the slider keeps
     * controlling the per-player output volume directly.
     */
    private fun bindSystemVolume() {
        val controller = dependencies.systemVolume
        controller.start(scope)
        if (controller.controlsPlayerOutput) {
            dependencies.audioPlayer.setUnityOutputVolume()
            dependencies.audioPlayer.updateReportedVolume(controller.volume.value)
            scope.launch {
                controller.volume.collect { v ->
                    dependencies.audioPlayer.updateReportedVolume(v)
                }
            }
        } else if (controller.isSupported) {
            dependencies.audioPlayer.setSystemVolumeScale(controller.volume.value)
            scope.launch {
                controller.volume.collect { scale ->
                    dependencies.audioPlayer.setSystemVolumeScale(scale)
                }
            }
        }
    }

    /**
     * If the saved remote session implies a browse flow, notify the root coordinator.
     * Covers startup races and missed navigation after async restore.
     */
    fun reconcileBrowseScreenIfNeeded() {
        dependencies.navigationService.reconcileBrowseScreenIfNeeded(session.value)
    }

    fun initialNavigationRequest(): AppNavigationRequest = defaultBrowseRequest()

    private fun defaultBrowseRequest(sessionSnapshot: PlexSession? = session.value): AppNavigationRequest {
        return dependencies.navigationService.initialRequest(
            session = sessionSnapshot,
            hasEnabledLocalFolders = hasEnabledLocalFolders(),
        )
    }

    private fun requestNavigation(request: AppNavigationRequest) {
        dependencies.navigationService.request(request)
    }

    private fun CatalogSnapshot.hasBrowseableContent(): Boolean =
        artists.isNotEmpty() ||
            albums.isNotEmpty() ||
            playlists.isNotEmpty() ||
            tracksByParent.values.any { it.isNotEmpty() }

    fun startPlexSignIn() = scope.launch {
        mutableBusy.value = true
        try {
            val newPin = withTimeout(PLEX_SIGN_IN_TIMEOUT_MS) {
                dependencies.sessionRepository.createPin()
            }
            mutablePin.value = newPin
            openExternalUrl(newPin.authUrl)
            mutableMessage.value = "Plex opened in your browser. Approve code ${newPin.code}, then finish sign-in."
        } catch (error: TimeoutCancellationException) {
            mutableMessage.value = "Plex did not respond. Check your connection and try again."
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            mutableMessage.value = error.message ?: "Couldn't start Plex sign-in."
        } finally {
            mutableBusy.value = false
        }
    }

    fun finishPlexSignIn() = scope.launch {
        val currentPin = mutablePin.value ?: return@launch
        mutableBusy.value = true
        mutableMessage.value = "Signing in with Plex…"
        try {
            val servers = withTimeout(PLEX_SIGN_IN_TIMEOUT_MS) {
                dependencies.sessionRepository.completePinAndListServers(currentPin)
            }
            if (servers == null) {
                mutableMessage.value = "That Plex code is not approved yet."
                return@launch
            }
            requestNavigation(AppNavigationRequest.ServerPicker)
            mutableServers.value = servers
            mutableLibraries.value = emptyList()
            mutableLibrariesLoading.value = false
            mutableMessage.value = "Signed in. Pick the Plex server that hosts your music."
        } catch (error: TimeoutCancellationException) {
            mutableMessage.value = "Plex did not finish signing in. Check your connection and try again."
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            mutableMessage.value = error.message ?: "Couldn't sign in to Plex."
        } finally {
            mutableBusy.value = false
        }
    }

    fun signInJellyfin(serverUrl: String, username: String, password: String) = scope.launch {
        mutableAuthInProgress.value = true
        try {
            mutableMessage.value = "Signing in with Jellyfin…"
            val server = runCatching {
                dependencies.sessionRepository.signInJellyfin(serverUrl, username, password)
            }.onFailure { error ->
                mutableMessage.value = error.message ?: "Couldn't sign in to Jellyfin."
            }.getOrNull() ?: return@launch
            mutableServers.value = listOf(server)
            mutableLibraries.value = emptyList()
            mutableLibrariesLoading.value = true
            requestNavigation(AppNavigationRequest.LibraryPicker)
            runCatching {
                mutableLibraries.value = dependencies.sessionRepository.libraries(server)
            }.onFailure { error ->
                mutableMessage.value = error.message ?: "Couldn't load Jellyfin libraries."
            }
            mutableLibrariesLoading.value = false
            mutableMessage.value = "Signed in. Pick the Jellyfin music library to browse."
        } finally {
            mutableAuthInProgress.value = false
        }
    }

    fun signInProvider(
        type: MediaProviderType,
        serverUrl: String,
        username: String,
        password: String,
        syncMode: JellyfinSyncMode? = null,
    ) = scope.launch {
        mutableAuthInProgress.value = true
        try {
            mutableMessage.value = "Signing in with ${type.displayName}…"
            val server = runCatching {
                dependencies.sessionRepository.signInProvider(type, serverUrl, username, password)
            }.onFailure { error ->
                mutableMessage.value = error.message ?: "Couldn't sign in to ${type.displayName}."
            }.getOrNull() ?: return@launch
            mutableServers.value = listOf(server)
            mutableLibraries.value = emptyList()
            if (type.skipsLibraryPicker()) {
                mutableLibrariesLoading.value = false
                runCatching {
                    dependencies.sessionRepository.selectLibrary(type.defaultLibrarySelection(), syncMode ?: JellyfinSyncMode.Quick)
                    requestNavigation(AppNavigationRequest.Home)
                    mutableMessage.value = if ((syncMode ?: JellyfinSyncMode.Quick) == JellyfinSyncMode.Full) {
                        "Starting full ${type.displayName} sync…"
                    } else {
                        "Loading ${type.displayName}…"
                    }
                    refreshCatalogSuspended(catalogMessage = "${type.displayName} ready.")
                }.onFailure { error ->
                    mutableMessage.value = error.message ?: "Couldn't load ${type.displayName}."
                }
                return@launch
            }
            mutableLibrariesLoading.value = true
            requestNavigation(AppNavigationRequest.LibraryPicker)
            val libraries = runCatching {
                dependencies.sessionRepository.libraries(server)
            }.onFailure { error ->
                mutableMessage.value = error.message ?: "Couldn't load ${type.displayName} libraries."
            }.getOrNull().orEmpty()
            mutableLibraries.value = libraries
            mutableLibrariesLoading.value = false
            if (type.autoSelectSingleLibrary() && libraries.size == 1) {
                runCatching {
                    dependencies.sessionRepository.selectLibrary(libraries.single())
                    requestNavigation(AppNavigationRequest.Home)
                    mutableMessage.value = "Loading ${type.displayName}…"
                    refreshCatalogSuspended(catalogMessage = "${type.displayName} ready.")
                }.onFailure { error ->
                    mutableMessage.value = error.message ?: "Couldn't load ${type.displayName}."
                }
                return@launch
            }
            mutableMessage.value = when (type) {
                MediaProviderType.Navidrome -> "Signed in. Pick the Subsonic music folder to browse."
                MediaProviderType.MusicAssistant -> "Signed in. Pick the Music Assistant source to browse."
                else -> "Signed in. Pick the ${type.displayName} music library to browse."
            }
        } finally {
            mutableAuthInProgress.value = false
        }
    }

    private fun MediaProviderType.autoSelectSingleLibrary(): Boolean =
        this == MediaProviderType.MusicAssistant

    private fun MediaProviderType.skipsLibraryPicker(): Boolean =
        this == MediaProviderType.Navidrome

    private fun MediaProviderType.defaultLibrarySelection(): MusicLibrary =
        when (this) {
            MediaProviderType.Navidrome -> MusicLibrary("all", "All Music")
            MediaProviderType.MusicAssistant -> MusicLibrary("music-assistant", "Music Assistant Library")
            else -> MusicLibrary("music", "Music")
        }

    fun discoverJellyfinServers() = scope.launch {
        mutableJellyfinDiscoveryLoading.value = true
        mutableMessage.value = "Searching your local network for Jellyfin servers…"
        val found = runCatching { discoverJellyfinServersOnNetwork() }
            .onFailure { error ->
                mutableMessage.value = error.message ?: "Couldn't search for Jellyfin servers."
            }
            .getOrDefault(emptyList())
        mutableJellyfinServers.value = found
        mutableJellyfinDiscoveryLoading.value = false
        mutableMessage.value = if (found.isEmpty()) {
            "No Jellyfin servers found on this network. Enter the server URL manually to continue."
        } else {
            "Found ${found.size} Jellyfin server${if (found.size == 1) "" else "s"} nearby."
        }
    }

    fun startJellyfinQuickConnect(serverUrl: String) = scope.launch {
        if (serverUrl.isBlank()) {
            mutableMessage.value = "Enter or choose a Jellyfin server URL first."
            return@launch
        }
        mutableMessage.value = "Starting Jellyfin Quick Connect…"
        val quickConnect = runCatching {
            dependencies.sessionRepository.startJellyfinQuickConnect(serverUrl)
        }.onFailure { error ->
            mutableMessage.value = error.message ?: "Couldn't start Jellyfin Quick Connect."
        }.getOrNull() ?: return@launch
        mutableJellyfinQuickConnect.value = quickConnect
        quickConnect.ServerUrl?.let { openExternalUrl(it) }
        mutableMessage.value = "Approve Jellyfin Quick Connect code ${quickConnect.Code}, then finish sign-in."
    }

    fun finishJellyfinQuickConnect() = scope.launch {
        val quickConnect = mutableJellyfinQuickConnect.value ?: run {
            mutableMessage.value = "Start Jellyfin Quick Connect first."
            return@launch
        }
        val serverUrl = quickConnect.ServerUrl ?: run {
            mutableMessage.value = "Jellyfin server URL is missing. Start Quick Connect again."
            return@launch
        }
        mutableAuthInProgress.value = true
        try {
            mutableMessage.value = "Finishing Jellyfin Quick Connect…"
            val server = runCatching {
                dependencies.sessionRepository.completeJellyfinQuickConnect(serverUrl, quickConnect.Secret)
            }.onFailure { error ->
                mutableMessage.value = error.message ?: "That Jellyfin Quick Connect code is not approved yet."
            }.getOrNull() ?: return@launch
            mutableJellyfinQuickConnect.value = null
            mutableServers.value = listOf(server)
            mutableLibraries.value = emptyList()
            mutableLibrariesLoading.value = true
            requestNavigation(AppNavigationRequest.LibraryPicker)
            runCatching {
                mutableLibraries.value = dependencies.sessionRepository.libraries(server)
            }.onFailure { error ->
                mutableMessage.value = error.message ?: "Couldn't load Jellyfin libraries."
            }
            mutableLibrariesLoading.value = false
            mutableMessage.value = "Signed in. Pick the Jellyfin music library to browse."
        } finally {
            mutableAuthInProgress.value = false
        }
    }

    fun loadServers() = scope.launch {
        mutableLibrariesLoading.value = false
        requestNavigation(AppNavigationRequest.ServerPicker)
        refreshServers()
    }

    fun returnToServerPicker() = scope.launch {
        mutableLibrariesLoading.value = false
        requestNavigation(AppNavigationRequest.ServerPicker)
        refreshServers()
    }

    private fun refreshServers() = scope.launch {
        if (session.value?.token.isNullOrBlank()) {
            mutableServers.value = emptyList()
            return@launch
        }
        mutableServersLoading.value = true
        runCatching {
            mutableServers.value = dependencies.sessionRepository.servers()
        }.onFailure { mutableMessage.value = it.message ?: "Couldn't load ${session.value.providerLabel()} servers." }
        mutableServersLoading.value = false
    }

    fun selectServer(server: PlexServer) = scope.launch {
        mutableBusy.value = true
        cancelRemotePlayHistorySync()
        cancelLightweightRemoteSync()
        mutableLibraries.value = emptyList()
        mutableLibrariesLoading.value = true
        val resolved = runCatching {
            dependencies.sessionRepository.selectServer(server, refreshConnections = false)
        }.onFailure {
            mutableBusy.value = false
            mutableLibrariesLoading.value = false
            mutableMessage.value = it.message ?: "Couldn't select ${session.value.providerLabel()} server."
        }.getOrNull()
        if (resolved == null) {
            mutableBusy.value = false
            return@launch
        }
        requestNavigation(AppNavigationRequest.LibraryPicker)
        runCatching {
            mutableLibraries.value = dependencies.sessionRepository.libraries(resolved)
        }.onFailure {
            mutableMessage.value = it.message ?: "Couldn't load ${session.value.providerLabel()} libraries."
        }
        mutableLibrariesLoading.value = false
        mutableBusy.value = false
    }

    fun selectLibrary(library: MusicLibrary, jellyfinSyncMode: JellyfinSyncMode? = null) = scope.launch {
        cancelRemotePlayHistorySync()
        cancelLightweightRemoteSync()
        catalogRefreshJob?.cancel()
        dependencies.catalogRepository.clearActiveSyncProgress()
        if (session.value == null) {
            mutableMessage.value = "Session expired. Sign in again."
            return@launch
        }
        runCatching {
            dependencies.sessionRepository.selectLibrary(library, jellyfinSyncMode)
            requestNavigation(AppNavigationRequest.Home)
            mutableMessage.value = if (session.value.isJellyfin() && (jellyfinSyncMode ?: session.value?.jellyfinSyncMode) == JellyfinSyncMode.Full) {
                "Starting full Jellyfin sync…"
            } else {
                "Loading library…"
            }
        }.onFailure { mutableMessage.value = it.message ?: "Something went sideways." }

        launch(Dispatchers.Default) {
            runCatching { dependencies.catalogRepository.restoreCachedCatalog() }
        }
        runCatching {
            refreshCatalogSuspended(catalogMessage = "Library ready.")
        }.onFailure { error ->
            if (error is CancellationException) {
                dependencies.catalogRepository.clearActiveSyncProgress()
            } else {
                mutableMessage.value = error.message ?: "Something went sideways."
            }
        }
    }

    /**
     * Suspends until the catalog is rebuilt from the current session and media sources.
     * Prefer this from [LaunchedEffect] so in-flight work is cancelled when dependencies change,
     * avoiding stale empty Plex refreshes overwriting a newer library load.
     */
    suspend fun refreshCatalogSuspended(catalogMessage: String? = "Library refreshed.", backgroundIfCached: Boolean = false) {
        val currentJob = currentCoroutineContext()[Job]
        catalogRefreshJob?.takeIf { it != currentJob }?.cancel()
        catalogRefreshJob = currentJob
        try {
            withContext(Dispatchers.Default) {
                dependencies.catalogSyncService.refreshAggregatedCatalog(
                    session = session.value,
                    backgroundIfCached = backgroundIfCached,
                )
            }
            warmPlaylistTracksInBackground()
            val currentSession = session.value
            if (currentSession.isPlex() || currentSession.isEmbyFamily() || currentSession.isNavidrome()) {
                syncRemotePlayHistory(
                    showMessage = false,
                    recentOnly = backgroundIfCached && !currentSession.isPlex(),
                )
            } else {
                syncRemotePlayHistoryInBackground()
            }
            cacheDownloadedArtworkInBackground()
            if (catalogMessage != null) mutableMessage.value = catalogMessage
        } catch (error: CancellationException) {
            PhoebeLog.d("AppState") { "catalog refresh cancelled" }
            dependencies.catalogRepository.clearActiveSyncProgress()
            throw error
        } catch (error: Throwable) {
            mutableMessage.value = error.message ?: "Something went sideways."
        } finally {
            if (catalogRefreshJob == currentJob) {
                catalogRefreshJob = null
            }
        }
    }

    private fun warmPlaylistTracksInBackground() {
        scope.launch {
            runCatching {
                dependencies.catalogSyncService.warmPlaylistTracks(session.value)
            }.onFailure { error ->
                PhoebeLog.d("AppState") { "playlist warm failed: ${error.message}" }
            }
        }
    }

    private fun cacheDownloadedArtworkInBackground() {
        if (activeDownloadJobs.isNotEmpty()) return
        downloadedArtworkCacheJob?.cancel()
        downloadedArtworkCacheJob = scope.launch {
            runCatching {
                if (activeDownloadJobs.isNotEmpty()) return@runCatching 0
                dependencies.catalogSyncService.cacheDownloadedArtwork()
            }.onSuccess { cached ->
                if (cached > 0) {
                    PhoebeLog.d("AppState") { "cached artwork for $cached downloaded tracks" }
                }
            }.onFailure { error ->
                PhoebeLog.d("AppState") { "downloaded artwork cache failed: ${error.message}" }
            }
        }
    }

    private suspend fun ensureLikedSongsPlaylistIfPossible(): Playlist? {
        return dependencies.catalogSyncService.ensureLikedSongsPlaylistIfPossible(session.value)
    }

    fun openLikedSongsPlaylist() = scope.launch {
        val playlist = ensureLikedSongsPlaylistIfPossible()
        if (playlist == null) {
            mutableMessage.value = "Couldn't create Liked Songs yet."
            return@launch
        }
        requestNavigation(AppNavigationRequest.PlaylistDetail(playlist.id))
        syncLikedSongsInBackground()
    }

    fun refreshCatalog() = scope.launch {
        cancelLightweightRemoteSync()
        refreshCatalogSuspended()
    }

    fun loadJellyfinLibraryPage(kind: JellyfinLibraryPageKind, pageIndex: Int) = scope.launch {
        runCatching {
            dependencies.catalogRepository.loadJellyfinLibraryPage(session.value, kind, pageIndex)
        }.onFailure { error ->
            mutableMessage.value = error.message ?: "Couldn't load that Jellyfin page."
        }
    }

    fun refreshPlexPlayHistory() = startRemotePlayHistorySync(showMessage = true)

    fun refreshPlayHistory() = startRemotePlayHistorySync(showMessage = true)

    private fun syncLightweightRemoteStateInBackground() {
        syncStartupPlayHistoryInBackground()
        lightweightRemoteSyncJob?.cancel()
        lightweightRemoteSyncJob = scope.launch(Dispatchers.Default) {
            runCatching {
                dependencies.catalogSyncService.syncLightweightRemoteState(session.value)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                PhoebeLog.d("AppState") { "lightweight remote sync failed: ${error.message}" }
            }
        }
    }

    private fun syncRemotePlayHistoryInBackground() = startRemotePlayHistorySync(showMessage = false)

    private fun syncStartupPlayHistoryInBackground() {
        val currentSession = session.value
        if (!currentSession.isPlex()) {
            syncRemotePlayHistoryInBackground()
            return
        }
        if (shouldDeferBackgroundPlayHistorySync()) {
            PhoebeLog.d("AppState") { "startup play history sync deferred: background playback active" }
            return
        }
        cancelPlexPlayCountRefresh()
        playHistorySyncJob?.cancel()
        playHistorySyncJob = scope.launch {
            PhoebeLog.d("AppState") { "startup Plex play history sync requested: recent pass then full reconcile" }
            syncRemotePlayHistory(showMessage = false, recentOnly = true)
            cancelPlexPlayCountRefresh()
            syncRemotePlayHistory(showMessage = false, recentOnly = false)
        }
    }

    private fun shouldDeferBackgroundPlayHistorySync(): Boolean {
        if (PhoebeAppLifecycle.isUiVisible) return false
        return dependencies.audioPlayer.state.value.isPlaying
    }

    private fun startRemotePlayHistorySync(showMessage: Boolean, recentOnly: Boolean = !showMessage) {
        val currentSession = session.value
        if (!currentSession.isPlex() && !currentSession.isEmbyFamily() && !currentSession.isNavidrome()) {
            PhoebeLog.d("AppState") {
                "play history sync skipped: provider=${currentSession?.providerType?.name ?: "none"}"
            }
            if (showMessage) mutableMessage.value = "${currentSession.providerLabel()} play history sync is handled from playback progress."
            return
        }
        val effectiveRecentOnly = recentOnly && !currentSession.isPlex()
        if (!showMessage && shouldDeferBackgroundPlayHistorySync()) {
            PhoebeLog.d("AppState") { "play history sync deferred: background playback active" }
            return
        }
        if (showMessage) mutableMessage.value = "Syncing ${currentSession.providerLabel()} play history..."
        PhoebeLog.d("AppState") {
            "play history sync requested provider=${currentSession.providerLabel()} " +
                "recentOnly=$effectiveRecentOnly " +
                "showMessage=$showMessage hasServer=${currentSession?.selectedServer != null} " +
                "hasLibrary=${currentSession?.selectedLibrary != null}"
        }
        cancelPlexPlayCountRefresh()
        playHistorySyncJob?.cancel()
        playHistorySyncJob = scope.launch {
            syncRemotePlayHistory(showMessage = showMessage, recentOnly = effectiveRecentOnly)
        }
    }

    private fun syncPlayHistoryAfterProviderReports() {
        scope.launch {
            dependencies.plexPlaybackReporter.playHistoryChanged.collect {
                providerPlayHistoryRefreshJob?.cancel()
                providerPlayHistoryRefreshJob = scope.launch {
                    delay(ProviderPlayHistoryDebounceMs)
                    if (shouldDeferBackgroundPlayHistorySync()) {
                        PhoebeLog.d("AppState") { "play history sync deferred: background playback active" }
                        return@launch
                    }
                    syncRemotePlayHistoryInBackground()
                }
            }
        }
    }

    private fun cancelRemotePlayHistorySync() {
        playHistorySyncJob?.cancel()
        playHistorySyncJob = null
        cancelPlexPlayCountRefresh()
    }

    private fun cancelPlexPlayCountRefresh() {
        plexPlayCountRefreshJob?.cancel()
        plexPlayCountRefreshJob = null
    }

    internal fun onMemoryPressure(level: MemoryPressureLevel) {
        when (level) {
            MemoryPressureLevel.UiHidden -> Unit
            MemoryPressureLevel.Moderate -> {
                cancelPlexPlayCountRefresh()
                providerPlayHistoryRefreshJob?.cancel()
                providerPlayHistoryRefreshJob = null
                lightweightRemoteSyncJob?.cancel()
                lightweightRemoteSyncJob = null
                downloadedArtworkCacheJob?.cancel()
                downloadedArtworkCacheJob = null
            }
            MemoryPressureLevel.Critical -> {
                cancelRemotePlayHistorySync()
                providerPlayHistoryRefreshJob?.cancel()
                providerPlayHistoryRefreshJob = null
                lightweightRemoteSyncJob?.cancel()
                lightweightRemoteSyncJob = null
                downloadedArtworkCacheJob?.cancel()
                downloadedArtworkCacheJob = null
            }
        }
    }

    private fun cancelLightweightRemoteSync() {
        lightweightRemoteSyncJob?.cancel()
        lightweightRemoteSyncJob = null
    }

    private fun cancelCatalogRefresh() {
        catalogRefreshJob?.cancel()
        catalogRefreshJob = null
        dependencies.catalogRepository.clearActiveSyncProgress()
    }

    private suspend fun syncRemotePlayHistory(showMessage: Boolean, recentOnly: Boolean): Any? {
        return runCatching {
            val currentSession = session.value
            PhoebeLog.d("AppState") {
                "play history sync started provider=${currentSession.providerLabel()} " +
                    "recentOnly=$recentOnly catalogTracks=${catalog.value.tracksByParent.values.sumOf { it.size }}"
            }
            val result = dependencies.catalogSyncService.syncRemotePlayHistory(
                session = currentSession,
                catalog = catalog.value,
                recentOnly = recentOnly,
            )
            if (currentSession.isPlex() && recentOnly) {
                launchKnownPlexPlayCountRefresh(currentSession)
            }
            warmTracksForMostPlayed()
            result
        }.onSuccess { result ->
            if (showMessage) {
                mutableMessage.value = when (result) {
                    PlexPlayHistorySyncResult.Skipped -> "Plex play history is not available yet."
                    is PlexPlayHistorySyncResult.Synced -> {
                        if (result.imported > 0) "Synced ${result.imported} Plex plays."
                        else "Plex play history is up to date."
                    }
                    JellyfinPlayHistorySyncResult.Skipped -> "${session.value.providerLabel()} play history is not available yet."
                    is JellyfinPlayHistorySyncResult.Synced -> {
                        val provider = session.value.providerLabel()
                        if (result.imported > 0) "Synced ${result.imported} $provider plays."
                        else "$provider play history is up to date."
                    }
                    NavidromePlayHistorySyncResult.Skipped -> "Subsonic play history is not available yet."
                    is NavidromePlayHistorySyncResult.Synced -> {
                        if (result.imported > 0) "Synced ${result.imported} Subsonic plays."
                        else "Subsonic play history is up to date."
                    }
                    else -> "Play history is up to date."
                }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            PhoebeLog.d("AppState") { "play history sync failed: ${error.message}" }
            if (showMessage) mutableMessage.value = error.message ?: "Couldn't sync play history."
        }.getOrNull()
    }

    private fun launchKnownPlexPlayCountRefresh(currentSession: PlexSession?) {
        if (shouldDeferBackgroundPlayHistorySync()) {
            PhoebeLog.d("AppState") { "Plex play count refresh deferred: background playback active" }
            return
        }
        plexPlayCountRefreshJob?.cancel()
        plexPlayCountRefreshJob = scope.launch {
            val refreshTrackIds = dependencies.catalogSyncService.topPlayHistoryTrackIds(60)
            dependencies.catalogSyncService.refreshPlexViewCountsForTrackIds(
                currentSession,
                refreshTrackIds,
            )
        }
    }

    fun setTab(tab: LibraryTab) {
        mutableTab.value = tab
        requestNavigation(defaultBrowseRequest())
    }

    fun preloadArtistDetail(artist: Artist) {
        val preloadKey = artist.id.ifBlank { artist.title }
        artistDetailPreloadJob?.takeIf { artistDetailPreloadKey == preloadKey && it.isActive }?.let {
            return
        }
        artistDetailPreloadJob?.cancel()
        artistDetailPreloadKey = preloadKey
        artistDetailPreloadJob = scope.launch {
            delay(75)
            withContext(Dispatchers.Default) {
                preloadArtistCatalogDetails(session.value, artist)
            }
        }
    }

    fun preloadArtistMixBuilderData(artists: List<Artist>) {
        val artistsToPreload = artists
            .distinctBy { artist -> artist.mixBuilderPreloadKey() }
            .filter { artist -> prefetchedMixBuilderArtistIds.add(artist.mixBuilderPreloadKey()) }
        if (artistsToPreload.isEmpty()) return
        scope.launch {
            val currentSession = session.value
            withContext(Dispatchers.Default) {
                artistsToPreload.forEach { artist ->
                    preloadArtistCatalogDetails(currentSession, artist)
                }
            }
        }
    }

    private suspend fun preloadArtistCatalogDetails(currentSession: PlexSession?, artist: Artist) {
        runCatching {
            dependencies.catalogRepository.ensurePopularTracksForArtist(currentSession, artist)
        }.onFailure {
            if (it is CancellationException) throw it
            PhoebeLog.d("AppState") { "artist popular tracks preload failed for '${artist.title}': ${it.message}" }
        }
        runCatching {
            dependencies.catalogRepository.ensureSimilarArtistsForArtist(currentSession, artist)
        }.onFailure {
            if (it is CancellationException) throw it
            PhoebeLog.d("AppState") { "artist similar preload failed for '${artist.title}': ${it.message}" }
        }
        runCatching {
            dependencies.catalogRepository.ensureTracksForArtistAlbums(currentSession, artist.title)
        }.onFailure {
            if (it is CancellationException) throw it
            PhoebeLog.d("AppState") { "artist album tracks preload failed for '${artist.title}': ${it.message}" }
        }
    }

    fun preloadAlbumDetail(album: Album) {
        scope.launch {
            runCatching {
                dependencies.catalogRepository.tracksForAlbum(session.value, album)
            }.onFailure {
                mutableMessage.value = it.message ?: "Couldn't load album tracks."
            }
            runCatching {
                dependencies.catalogRepository.ensureAlbumDetails(session.value, album)
            }.onFailure {
                if (it is CancellationException) throw it
                PhoebeLog.d("AppState") { "album details preload failed for '${album.title}': ${it.message}" }
            }
        }
    }

    fun preloadPlaylistDetail(playlist: Playlist) {
        scope.launch {
            runCatching {
                dependencies.catalogRepository.tracksForPlaylist(session.value, playlist)
            }.onFailure {
                mutableMessage.value = it.message ?: "Couldn't load playlist tracks."
            }
        }
    }

    fun preloadCollections(entry: com.phoebe.app.domain.CollectionEntry) {
        scope.launch {
            if (!session.value.supportsCollectionEntry(entry)) return@launch
            runCatching {
                dependencies.catalogRepository.ensureCollectionValues(session.value, entry)
            }.onFailure {
                mutableMessage.value = it.message ?: "Couldn't load collections."
            }
        }
    }

    fun preloadCollectionItems(entry: com.phoebe.app.domain.CollectionEntry, value: String) {
        scope.launch {
            if (!session.value.supportsCollectionEntry(entry)) return@launch
            runCatching {
                dependencies.catalogRepository.ensureCollectionItems(session.value, entry, value)
            }.onFailure {
                mutableMessage.value = it.message ?: "Couldn't load collection."
            }
        }
    }

    fun prefetchHomeArtistStats(artist: Artist) {
        if (!session.value.canUsePlexBackgroundFetches()) return
        if (!prefetchedArtistIds.add(artist.id)) return
        scope.launch {
            runCatching {
                dependencies.catalogRepository.ensureTracksForArtistAlbums(session.value, artist.title)
            }
        }
    }

    fun prefetchHomeAlbumStats(album: Album) {
        if (!session.value.canUsePlexBackgroundFetches()) return
        if (!prefetchedAlbumIds.add(album.id)) return
        scope.launch {
            runCatching {
                dependencies.catalogRepository.tracksForAlbum(session.value, album)
            }
        }
    }

    fun warmRecentAlbumTracks(cutoffMs: Long, maxAlbums: Int = 10) {
        if (!session.value.canUsePlexBackgroundFetches()) return
        val snapshot = catalog.value
        val albumIds = snapshot.albums
            .asSequence()
            .filter { (it.dateAddedMs ?: Long.MIN_VALUE) >= cutoffMs }
            .filter { snapshot.tracksByParent[it.id].isNullOrEmpty() }
            .sortedByDescending { it.dateAddedMs ?: 0L }
            .take(maxAlbums)
            .map { it.id }
            .toList()
        if (albumIds.isEmpty()) return
        val signature = albumIds.joinToString("|")
        if (signature == recentAlbumWarmSignature) return
        recentAlbumWarmSignature = signature
        scope.launch {
            dependencies.catalogRepository.warmRecentAlbumTracks(session.value, cutoffMs, maxAlbums)
        }
    }

    fun warmPlayedAlbumTracks(albumTitles: List<String>, maxAlbums: Int = 10) {
        if (!session.value.canUsePlexBackgroundFetches()) return
        val normalizedTitles = albumTitles
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(maxAlbums)
            .toList()
        if (normalizedTitles.isEmpty()) return
        val signature = normalizedTitles.joinToString("|") { it.lowercase() }
        if (signature == playedAlbumWarmSignature) return
        playedAlbumWarmSignature = signature
        scope.launch {
            dependencies.catalogRepository.warmAlbumTracksByTitle(session.value, normalizedTitles, maxAlbums)
        }
    }

    suspend fun resolveTracksByIds(trackIds: Collection<String>): Map<String, Track> {
        val requestedIds = trackIds.filter { it.isNotBlank() }.distinct()
        if (requestedIds.isEmpty()) return emptyMap()
        val playbackTracks = resolveActivePlaybackTracksByIds(requestedIds)
        val unresolvedIds = requestedIds.filter { it !in playbackTracks }
        val (radioIds, catalogIds) = unresolvedIds.partition { it.startsWith("radio:") }
        val catalogTracks = if (catalogIds.isNotEmpty()) {
            runCatching {
                withTimeout(PlayHistoryCatalogResolveTimeoutMs) {
                    dependencies.catalogRepository.resolveTracksByIds(catalogIds)
                }
            }.getOrElse { error ->
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                PhoebeLog.d("AppState") { "catalog play-history lookup timed out for ${catalogIds.size} tracks" }
                emptyMap()
            }
        } else {
            emptyMap()
        }
        val radioTracks = radioIds.mapNotNull { radioId ->
            val stationId = radioId.removePrefix("radio:")
            val station = dependencies.radioRepository.findStationById(stationId)
            if (station != null) {
                val track = runCatching { dependencies.radioRepository.stationTrack(station) }.getOrNull()
                if (track != null) {
                    radioId to track
                } else {
                    null
                }
            } else {
                null
            }
        }.toMap()
        return catalogTracks + playbackTracks + radioTracks
    }

    private fun resolveActivePlaybackTracksByIds(trackIds: Collection<String>): Map<String, Track> {
        val playbackState = dependencies.audioPlayer.state.value
        val candidates = buildList {
            playbackState.currentTrack?.let(::add)
            addAll(playbackState.queue)
            mutableMusicAssistantRemotePlayback.value?.tracks?.let(::addAll)
        }
        if (candidates.isEmpty()) return emptyMap()

        val prefixedCandidates = mutableMapOf<String, Track>()
        val unprefixedCandidates = mutableMapOf<String, Track>()
        val prefixedByUnprefixedId = mutableMapOf<String, Track>()
        candidates.forEach { track ->
            val prefix = providerPrefixForTrackId(track.id)
            if (prefix == null) {
                unprefixedCandidates.putFirst(track.id, track)
            } else {
                prefixedCandidates.putFirst(track.id, track)
                prefixedByUnprefixedId.putFirst(track.id.removePrefix(prefix), track)
            }
        }

        return buildMap {
            trackIds.forEach { requestedId ->
                val prefix = providerPrefixForTrackId(requestedId)
                val track = if (prefix == null) {
                    unprefixedCandidates[requestedId] ?: prefixedByUnprefixedId[requestedId]
                } else {
                    prefixedCandidates[requestedId] ?: unprefixedCandidates[requestedId.removePrefix(prefix)]
                }
                if (track != null) {
                    put(requestedId, track)
                }
            }
        }
    }

    fun toggleFavoriteRadioStation(track: Track) = scope.launch {
        val streamUrl = track.streamUrl ?: return@launch
        val existing = radioDirectory.value.manualStations.find { it.streamUrl == streamUrl }
        if (existing != null) {
            dependencies.radioRepository.deleteManualStation(existing.id)
        } else {
            dependencies.radioRepository.addManualStation(track.title, streamUrl)
                .onFailure { mutableMessage.value = it.message ?: "Couldn't add radio station." }
        }
    }

    suspend fun queryPlayHistoryEntries(kind: PlayHistoryKind, limit: Int): PlayHistoryRankedEntries =
        dependencies.playHistoryRepository.queryRankedEntries(kind, limit)

    fun warmTracksForMostPlayed(maxTracks: Int = 20) {
        if (!session.value.canUsePlexBackgroundFetches()) return
        val entries = buildList {
            addAll(topMostPlayed.value.take(maxTracks))
            topRecentlyPlayed.value.take(maxTracks).forEach { recent ->
                if (none { it.trackId == recent.trackId }) {
                    add(
                        com.phoebe.app.domain.MostPlayedEntry(
                            trackId = recent.trackId,
                            playCount = playCountsByTrack.value[recent.trackId] ?: 0L,
                            lastPlayedMs = recent.lastPlayedMs,
                            artist = recent.artist,
                            album = recent.album,
                        ),
                    )
                }
            }
        }
        if (entries.isEmpty()) return
        val signature = entries.joinToString("|") { "${it.trackId}:${it.playCount}:${it.lastPlayedMs}" }
        if (signature == mostPlayedWarmSignature) return
        mostPlayedWarmSignature = signature
        scope.launch {
            if (session.value.isPlex()) {
                dependencies.catalogSyncService.refreshPlexViewCountsForTrackIds(
                    session.value,
                    entries.map { it.trackId },
                )
            }
            dependencies.catalogSyncService.warmTracksForMostPlayed(session.value, entries, maxTracks)
        }
    }

    suspend fun ensurePersonalMixTracks(limit: Int) {
        if (!session.value.canUsePlexBackgroundFetches()) return
        runCatching {
            dependencies.catalogRepository.warmTracksForPersonalMix(session.value, limit)
        }.onFailure { error ->
            PhoebeLog.d("AppState") { "personal mix track warm failed: ${error.message}" }
        }
    }

    fun warmTopTracksMixTracks() {
        val currentSession = session.value
        val plexSession = currentSession?.takeIf { it.isPlex() } ?: return
        val signature = currentSession.topTracksMixSessionSignature() ?: return
        if (signature == topTracksMixWarmSignature) return
        topTracksMixWarmSignature = signature
        scope.launch {
            runCatching {
                val tracks = dependencies.catalogRepository.cachedPopularTracksForLibrary(currentSession)
                    .takeIf { it.isNotEmpty() }
                    ?: startTopTracksMixBuild(plexSession, signature).await()
                if (tracks.isEmpty()) {
                    topTracksMixWarmSignature = null
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                topTracksMixWarmSignature = null
                PhoebeLog.d("AppState") { "top tracks mix warm failed: ${error.message}" }
            }
        }
    }

    fun warmHomeMixStartupTracks() {
        val currentSession = session.value
        val plexSession = currentSession?.takeIf { it.isPlex() }
        val signature = currentSession.topTracksMixSessionSignature()
        if (plexSession != null &&
            signature != null &&
            (popularMixSeedSignature != signature || popularMixSeedTracks.isEmpty()) &&
            !(popularMixSeedBuildSignature == signature && popularMixSeedBuildDeferred?.isActive == true)
        ) {
            scope.launch {
                runCatching {
                    startPopularMixSeedBuild(plexSession, signature).await()
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    PhoebeLog.d("AppState") { "popular mix seed warm failed: ${error.message}" }
                }
            }
        }
        warmTopTracksMixTracks()
    }

    fun playDecadeMix(decade: Int) = scope.launch {
        mutableDecadeMixNotice.value = "Searching the ${decade}s…"
        val firstTracks = runCatching {
            dependencies.catalogRepository.firstTracksForDecade(session.value, decade).shuffled()
        }.getOrElse { error ->
            val notice = error.message ?: "Couldn't search the ${decade}s."
            mutableDecadeMixNotice.value = notice
            mutableMessage.value = notice
            return@launch
        }
        if (firstTracks.isEmpty()) {
            val notice = "No songs found for the ${decade}s."
            mutableDecadeMixNotice.value = notice
            mutableMessage.value = notice
            return@launch
        }
        mutableDecadeMixNotice.value = null
        if (!playTracks(
                firstTracks,
                0,
                queueOrigin = PlaybackQueueOrigin.Mix(
                    id = "decade:$decade",
                    title = "${decade}s",
                    seedTrackIds = firstTracks.map { it.id },
                ),
            )
        ) return@launch
        requestNavigation(AppNavigationRequest.Player)
        mutableMessage.value = "Playing ${firstTracks.size} songs from the ${decade}s."
        scope.launch {
            val initialIds = firstTracks.map { it.id }.toSet()
            val moreTracks = runCatching {
                dependencies.catalogRepository.tracksForDecade(session.value, decade)
                    .filterNot { it.id in initialIds }
                    .shuffled()
            }.getOrDefault(emptyList())
            if (moreTracks.isNotEmpty()) {
                appendToQueue(moreTracks)
                mutableMessage.value = "Added ${moreTracks.size} more songs from the ${decade}s."
            }
        }
    }

    fun playPopularMix() = scope.launch {
        val seed = popularMixSeedForSession(session.value)
        val popularPool = seed?.tracks.orEmpty()
        if (seed == null || popularPool.isEmpty()) {
            mutableMessage.value = "No popular songs found yet."
            return@launch
        }
        val mix = popularPool.popularMixQueue()
        if (mix.isEmpty()) {
            mutableMessage.value = "No popular songs found yet."
            return@launch
        }
        if (playTracks(
                mix,
                0,
                queueOrigin = PlaybackQueueOrigin.Mix(
                    id = "popular",
                    title = "Popular Mix",
                    seedTrackIds = mix.map { it.id },
                ),
            )
        ) {
            requestNavigation(AppNavigationRequest.Player)
            mutableMessage.value = "Playing ${mix.size} popular songs."
            appendPopularMixRemainder(seed, mix)
        }
    }

    fun playTopTracksMix() = scope.launch {
        val currentSession = session.value
        val cachedPool = dependencies.catalogRepository.cachedPopularTracksForLibrary(currentSession)
        val seed = if (cachedPool.isEmpty()) popularMixSeedForSession(currentSession) else null
        val topTracksPool = cachedPool.takeIf { it.isNotEmpty() } ?: seed?.tracks.orEmpty()
        if (topTracksPool.isEmpty()) {
            mutableMessage.value = "No top tracks found yet."
            return@launch
        }
        val mix = topTracksPool.topTracksMixQueue()
        if (mix.isEmpty()) {
            mutableMessage.value = "No top tracks found yet."
            return@launch
        }
        if (playTracks(
                mix,
                0,
                queueOrigin = PlaybackQueueOrigin.Mix(
                    id = "top-tracks",
                    title = "Top Tracks",
                    seedTrackIds = mix.map { it.id },
                ),
            )
        ) {
            requestNavigation(AppNavigationRequest.Player)
            mutableMessage.value = "Playing ${mix.size} top tracks."
            if (seed != null) {
                appendTopTracksMixRemainder(seed, mix)
            } else {
                warmTopTracksMixTracks()
            }
        }
    }

    private suspend fun popularMixSeedForSession(currentSession: PlexSession?): PopularMixSeed? {
        val plexSession = currentSession?.takeIf { it.isPlex() } ?: return null
        val signature = currentSession.topTracksMixSessionSignature() ?: return null
        val cachedTracks = popularMixSeedTracks
            .takeIf { popularMixSeedSignature == signature && it.isNotEmpty() }
            ?: startPopularMixSeedBuild(plexSession, signature).await()
        return PopularMixSeed(
            session = plexSession,
            signature = signature,
            tracks = cachedTracks,
        )
    }

    private fun startPopularMixSeedBuild(session: PlexSession, signature: String): Deferred<List<Track>> {
        popularMixSeedBuildDeferred?.let { active ->
            if (popularMixSeedBuildSignature == signature && active.isActive) return active
            if (active.isActive) active.cancel()
        }
        val deferred = scope.async {
            val tracks = runCatching {
                withTimeoutOrNull(MixProviderLoadTimeoutMs) {
                    dependencies.catalogRepository.popularSongsForLibrary(
                        session = session,
                        limit = PopularMixSeedTrackLimit,
                    )
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                PhoebeLog.d("AppState") { "popular mix seed provider load failed: ${error.message}" }
                null
            }.orEmpty()
            if (tracks.isNotEmpty()) {
                popularMixSeedSignature = signature
                popularMixSeedTracks = tracks
            } else if (popularMixSeedSignature == signature) {
                popularMixSeedSignature = null
                popularMixSeedTracks = emptyList()
            }
            tracks
        }
        popularMixSeedBuildSignature = signature
        popularMixSeedBuildDeferred = deferred
        deferred.invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                PhoebeLog.d("AppState") { "popular mix seed provider load failed: ${error.message}" }
            }
            scope.launch {
                if (popularMixSeedBuildDeferred === deferred) {
                    popularMixSeedBuildDeferred = null
                    popularMixSeedBuildSignature = null
                }
            }
        }
        return deferred
    }

    private fun appendPopularMixRemainder(seed: PopularMixSeed, seedQueue: List<Track>) {
        if (dependencies.castController.state.value.isPlaybackActive) return
        scope.launch {
            if (dependencies.castController.state.value.isPlaybackActive) return@launch
            val fullPool = runCatching {
                withTimeoutOrNull(MixProviderLoadTimeoutMs) {
                    dependencies.catalogRepository.popularSongsForLibrary(
                        session = seed.session,
                        limit = PopularMixTrackLimit,
                    )
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                PhoebeLog.d("AppState") { "popular mix provider load failed: ${error.message}" }
                null
            }.orEmpty()
            appendMixRemainderIfActive(
                signature = seed.signature,
                seedQueue = seedQueue,
                fullMix = fullPool.popularMixQueue(),
            )
        }
    }

    private fun appendTopTracksMixRemainder(seed: PopularMixSeed, seedQueue: List<Track>) {
        if (dependencies.castController.state.value.isPlaybackActive) return
        scope.launch {
            if (dependencies.castController.state.value.isPlaybackActive) return@launch
            val fullPool = dependencies.catalogRepository.cachedPopularTracksForLibrary(seed.session)
                .takeIf { it.isNotEmpty() }
                ?: startTopTracksMixBuild(seed.session, seed.signature).await()
            appendMixRemainderIfActive(
                signature = seed.signature,
                seedQueue = seedQueue,
                fullMix = fullPool.topTracksMixQueue(),
            )
        }
    }

    private fun appendMixRemainderIfActive(
        signature: String,
        seedQueue: List<Track>,
        fullMix: List<Track>,
    ) {
        if (fullMix.isEmpty()) return
        if (session.value.topTracksMixSessionSignature() != signature) return
        if (dependencies.castController.state.value.isPlaybackActive) return
        val current = dependencies.audioPlayer.state.value
        if (!mixQueueStillActiveForAppend(current.queue, seedQueue, current.currentIndex)) return
        val additions = mixAppendCandidates(fullMix, current.queue)
        if (additions.isNotEmpty()) {
            appendToQueue(additions)
        }
    }

    private fun startTopTracksMixBuild(session: PlexSession, signature: String): Deferred<List<Track>> {
        topTracksMixBuildDeferred
            ?.takeIf { topTracksMixBuildSignature == signature && it.isActive }
            ?.let { return it }
        val deferred = scope.async {
            runCatching {
                dependencies.catalogRepository.popularTracksForLibrary(session)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                PhoebeLog.d("AppState") { "top tracks mix provider load failed: ${error.message}" }
                emptyList()
            }
        }
        topTracksMixBuildSignature = signature
        topTracksMixBuildDeferred = deferred
        deferred.invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                PhoebeLog.d("AppState") { "top tracks mix provider load failed: ${error.message}" }
            }
            scope.launch {
                if (topTracksMixBuildDeferred === deferred) {
                    topTracksMixBuildDeferred = null
                    topTracksMixBuildSignature = null
                }
            }
        }
        return deferred
    }

    fun refreshRadioStations() = scope.launch {
        val currentSession = session.value
        val selectedLibrary = currentSession?.selectedLibrary
        if (!currentSession.isPlex() || currentSession?.selectedServer == null || selectedLibrary == null || currentSession.serverAuthToken() == null) {
            mutableRadioStations.value = emptyList()
            return@launch
        }
        mutableRadioStations.value = defaultPlexRadioStations(selectedLibrary)
        val stations = runCatching {
            dependencies.catalogRepository.plexRadioStations(currentSession)
        }.onFailure { error ->
            PhoebeLog.d("AppState") { "Plex radio station load failed: ${error.message}" }
        }.getOrDefault(defaultPlexRadioStations(selectedLibrary))
        mutableRadioStations.value = stations
    }

    fun playRadioStation(station: PlexRadioStation) = scope.launch {
        val radioId = station.key
        if (radioId in mutableRadioStartingIds.value) return@launch
        mutableRadioStartingIds.update { it + radioId }
        try {
            val tracks = runCatching {
                dependencies.catalogRepository.playRadioStation(session.value, station)
            }.getOrElse { error ->
                surfaceTransientNotice(error.message ?: "Couldn't start ${station.title}.")
                return@launch
            }
            if (tracks.isEmpty()) {
                surfaceTransientNotice("No songs found for ${station.title}.")
                return@launch
            }
            if (playTracks(
                    tracks,
                    0,
                    queueOrigin = PlaybackQueueOrigin.Radio(
                        id = station.id,
                        title = station.title,
                        key = station.key,
                        providerType = MediaProviderType.Plex,
                        seedTrackIds = tracks.map { it.id },
                    ),
                )
            ) {
                requestNavigation(AppNavigationRequest.Player)
            }
        } finally {
            mutableRadioStartingIds.update { it - radioId }
        }
    }

    fun playArtistRadio(artist: Artist) = scope.launch {
        if (!artist.id.startsWith("plex:") && !artist.id.startsWith("jellyfin:") && !artist.id.startsWith("emby:")) {
            mutableMessage.value = "Artist Radio is available for streaming-library artists."
            return@launch
        }
        if (mutableArtistRadioAvailability.value[artist.id] == ArtistRadioAvailability.Unavailable) {
            mutableMessage.value = "Artist Radio isn't available for ${artist.title}."
            return@launch
        }
        if (artist.id in mutableRadioStartingIds.value) return@launch
        mutableRadioStartingIds.update { it + artist.id }
        try {
            mutableMessage.value = "Starting ${artist.title} Radio..."
            val tracks = runCatching {
                dependencies.catalogRepository.playArtistRadio(session.value, artist)
            }.getOrElse { error ->
                val notice = error.message ?: "Couldn't start radio for ${artist.title}."
                mutableMessage.value = notice
                return@launch
            }
            if (tracks.isEmpty()) {
                mutableArtistRadioAvailability.update { it + (artist.id to ArtistRadioAvailability.Unavailable) }
                mutableMessage.value = "Artist Radio isn't available for ${artist.title}."
                return@launch
            }
            mutableArtistRadioAvailability.update { it + (artist.id to ArtistRadioAvailability.Available) }
            if (playTracks(
                    tracks,
                    0,
                    queueOrigin = PlaybackQueueOrigin.Artist(
                        id = artist.id,
                        title = artist.title,
                        providerType = artist.id.providerTypeFromCatalogId(),
                        seedTrackIds = tracks.map { it.id },
                    ),
                )
            ) {
                requestNavigation(AppNavigationRequest.Player)
                mutableMessage.value = "Playing ${artist.title} Radio."
            }
        } finally {
            mutableRadioStartingIds.update { it - artist.id }
        }
    }

    fun probeArtistRadio(artist: Artist) = scope.launch {
        if (!artist.id.startsWith("plex:") && !artist.id.startsWith("jellyfin:") && !artist.id.startsWith("emby:")) {
            mutableArtistRadioAvailability.update { it + (artist.id to ArtistRadioAvailability.Unavailable) }
            return@launch
        }
        if (mutableArtistRadioAvailability.value[artist.id] == ArtistRadioAvailability.Available) return@launch
        val available = runCatching {
            dependencies.catalogRepository.artistRadioStation(session.value, artist) != null
        }.onFailure { error ->
            PhoebeLog.d("AppState") { "Artist radio probe failed for '${artist.title}': ${error.message}" }
        }.getOrDefault(false)
        mutableArtistRadioAvailability.update {
            it + (artist.id to if (available) ArtistRadioAvailability.Available else ArtistRadioAvailability.Unavailable)
        }
    }

    fun playPlaylistShuffled(playlist: Playlist) = scope.launch {
        val tracks = runCatching {
            dependencies.catalogRepository.tracksForPlaylist(session.value, playlist)
        }.getOrElse { error ->
            mutableMessage.value = error.message ?: "Couldn't load ${playlist.title}."
            return@launch
        }
        if (tracks.isEmpty()) {
            mutableMessage.value = "${playlist.title} has no songs to shuffle."
            return@launch
        }
        val shuffledTracks = tracks.shuffled()
        if (playTracks(
                shuffledTracks,
                0,
                queueOrigin = PlaybackQueueOrigin.Playlist(
                    id = playlist.id,
                    title = playlist.title,
                    providerType = playlist.id.providerTypeFromCatalogId(),
                    seedTrackIds = tracks.map { it.id },
                ),
                shuffleEnabled = true,
            )
        ) {
            requestNavigation(AppNavigationRequest.Player)
            mutableMessage.value = "Shuffling ${playlist.title}."
        }
    }

    fun createSmartPlaylist(template: SmartPlaylistTemplate, title: String = template.title) = scope.launch {
        val now = currentTimeMs()
        val normalizedTitle = title.trim().ifBlank { template.title }
        val playlist = template
            .instantiate(nowMs = now, suffix = uniqueSmartPlaylistSuffix(normalizedTitle, now))
            .copy(title = normalizedTitle)
        dependencies.userArtifactsRepository.upsertSmartPlaylist(playlist)
        dependencies.catalogRepository.refreshSmartPlaylists()
        mutableMessage.value = "Created smart playlist ${playlist.title}."
        requestNavigation(AppNavigationRequest.PlaylistDetail(playlist.id))
    }

    fun updateSmartPlaylist(playlist: SmartPlaylist) = scope.launch {
        val updated = playlist.copy(updatedAtMs = currentTimeMs())
        dependencies.userArtifactsRepository.upsertSmartPlaylist(updated)
        dependencies.catalogRepository.refreshSmartPlaylists()
        mutableMessage.value = "Updated smart playlist ${updated.title}."
    }

    fun renameSmartPlaylist(playlist: SmartPlaylist, title: String) = scope.launch {
        val normalizedTitle = title.trim().ifBlank { playlist.title }
        val updated = dependencies.userArtifactsRepository.updateSmartPlaylist(playlist.id, currentTimeMs()) {
            it.copy(title = normalizedTitle)
        }
        dependencies.catalogRepository.refreshSmartPlaylists()
        mutableMessage.value = if (updated != null) {
            "Renamed smart playlist."
        } else {
            "Couldn't find that smart playlist."
        }
    }

    fun duplicateSmartPlaylist(playlist: SmartPlaylist) = scope.launch {
        val now = currentTimeMs()
        val duplicate = dependencies.userArtifactsRepository.duplicateSmartPlaylist(
            id = playlist.id,
            nowMs = now,
            suffix = uniqueSmartPlaylistSuffix("${playlist.title} Copy", now),
        )
        dependencies.catalogRepository.refreshSmartPlaylists()
        if (duplicate != null) {
            mutableMessage.value = "Duplicated ${playlist.title}."
            requestNavigation(AppNavigationRequest.PlaylistDetail(duplicate.id))
        } else {
            mutableMessage.value = "Couldn't duplicate that smart playlist."
        }
    }

    fun setSmartPlaylistEnabled(playlist: SmartPlaylist, enabled: Boolean) = scope.launch {
        dependencies.userArtifactsRepository.updateSmartPlaylist(playlist.id, currentTimeMs()) {
            it.copy(enabled = enabled)
        }
        dependencies.catalogRepository.refreshSmartPlaylists()
        mutableMessage.value = if (enabled) "Enabled ${playlist.title}." else "Disabled ${playlist.title}."
    }

    fun deleteSmartPlaylist(playlist: SmartPlaylist) = scope.launch {
        dependencies.userArtifactsRepository.deleteSmartPlaylist(playlist.id)
        dependencies.catalogRepository.refreshSmartPlaylists()
        mutableMessage.value = "Deleted smart playlist ${playlist.title}."
        requestNavigation(AppNavigationRequest.Home)
    }

    fun saveSearch(queryText: String, title: String = queryText) = scope.launch {
        val normalizedQuery = queryText.trim()
        if (normalizedQuery.isBlank()) {
            mutableMessage.value = "Type a search before saving it."
            return@launch
        }
        val now = currentTimeMs()
        val savedSearch = SavedSearch(
            id = "saved:search:${uniqueSearchSuffix(normalizedQuery, now)}",
            title = title.trim().ifBlank { normalizedQuery },
            query = parseAdvancedSearchQuery(normalizedQuery),
            createdAtMs = now,
            updatedAtMs = now,
        )
        dependencies.userArtifactsRepository.upsertSavedSearch(savedSearch)
        mutableMessage.value = "Saved search ${savedSearch.title}."
    }

    fun deleteSavedSearch(search: SavedSearch) = scope.launch {
        dependencies.userArtifactsRepository.deleteSavedSearch(search.id)
        mutableMessage.value = "Deleted saved search ${search.title}."
    }

    fun clearDecadeMixNotice() {
        mutableDecadeMixNotice.value = null
    }

    fun playTracks(
        tracks: List<Track>,
        index: Int = 0,
        collectionMixSeed: CollectionMixSeed? = null,
        queueOrigin: PlaybackQueueOrigin? = null,
        shuffleEnabled: Boolean = false,
        clearShuffle: Boolean = false,
        preserveQueueContext: Boolean = false,
    ): Boolean {
        collectionMixGeneration++
        val playbackTracks = tracks.withFreshPlaybackUrls(session.value)
        if (playbackTracks.isEmpty()) return false
        val startIndex = index.coerceIn(playbackTracks.indices)
        val track = playbackTracks[startIndex]
        if (dependencies.castController.state.value.isConnected) {
            mutableMusicAssistantRemotePlayback.value = null
            val support = dependencies.castController.canLoadQueue(playbackTracks)
            if (!support.isSupported) {
                mutableMessage.value = support.message ?: "This queue can't be cast to Chromecast."
                return false
            }
            val currentPlayer = dependencies.audioPlayer.state.value
            val startPositionMs = currentPlayer.positionMs.takeIf {
                currentPlayer.queue.getOrNull(currentPlayer.currentIndex)?.id == track.id
            } ?: 0L
            dependencies.castController.loadQueue(playbackTracks, startIndex, startPositionMs)
            recordPlaybackStarted(track)
            if (shuffleEnabled || clearShuffle) {
                dependencies.audioPlayer.setShuffle(shuffleEnabled)
            }
            if (!preserveQueueContext) {
                startKeepPlayingQueue(queueOrigin, playbackTracks)
            }
            return true
        }
        if (session.value.isMusicAssistant() && track.localUri.isNullOrBlank() && track.streamUrl.isBlank()) {
            val musicAssistantTrack = track
            if (!preserveQueueContext) {
                startKeepPlayingQueue(queueOrigin, playbackTracks)
            }
            scope.launch {
                mutableMessage.value = "Starting ${musicAssistantTrack.title} in Music Assistant..."
                runCatching {
                    dependencies.providerRegistry.adapterFor(session.value)?.playRemote(session.value!!, playbackTracks, startIndex)
                }.onSuccess { target ->
                    if (target.isNullOrBlank()) {
                        mutableMessage.value = "Couldn't find a Music Assistant player for ${musicAssistantTrack.title}."
                        return@onSuccess
                    }
                    mutableMusicAssistantRemotePlayback.value = MusicAssistantRemotePlayback(
                        tracks = playbackTracks,
                        index = startIndex,
                        target = target,
                        shuffle = shuffleEnabled,
                    )
                    recordPlaybackStarted(musicAssistantTrack)
                    mutableMessage.value = "Playing ${musicAssistantTrack.title} on Music Assistant: $target."
                }.onFailure { error ->
                    mutableMessage.value = error.message ?: "Couldn't start Music Assistant playback."
                }
            }
            return true
        }
        if (!track.hasPlayableSource()) {
            surfaceTransientNotice("Couldn't find a playable stream for ${track.title}. Try refreshing the library.")
            return false
        }
        if (!preserveQueueContext) {
            startKeepPlayingQueue(queueOrigin, playbackTracks)
        }
        mutableMusicAssistantRemotePlayback.value = null
        if (shuffleEnabled) {
            dependencies.audioPlayer.playShuffled(playbackTracks, startIndex)
        } else {
            dependencies.audioPlayer.play(playbackTracks, startIndex)
            if (clearShuffle) {
                dependencies.audioPlayer.setShuffle(false)
            }
        }
        recordPlaybackStarted(track)
        if (appSettings.value.keepPlayingEnabled) {
            keepPlayingPreviewPendingGeneration = keepPlayingQueueGeneration
            if (requestKeepPlayingPreview()) {
                keepPlayingPreviewPendingGeneration = null
            }
        }
        collectionMixSeed?.toCollectionMix()?.let { mix ->
            scheduleCollectionMix(mix, playbackTracks.map { it.id }.toSet())
        }
        return true
    }

    private fun scheduleCollectionMix(mix: CollectionMix, queuedTrackIds: Set<String>) {
        if (!session.value.canUsePlexBackgroundFetches()) return
        val mixGeneration = collectionMixGeneration
        scope.launch {
            appendCollectionMix(mix, queuedTrackIds, mixGeneration)
        }
    }

    private fun CollectionMixSeed.toCollectionMix(): CollectionMix? {
        if (facet != CollectionFacet.Mood && facet != CollectionFacet.Style) return null
        val value = value.trim()
        if (value.isBlank()) return null
        return CollectionMix(facet, value)
    }

    private suspend fun appendCollectionMix(
        mix: CollectionMix,
        queuedTrackIds: Set<String>,
        mixGeneration: Int,
    ) {
        if (mixGeneration != collectionMixGeneration) return
        val excludeIds = queuedTrackIds.toMutableSet()
        val firstTracks = runCatching {
            dependencies.catalogRepository.firstTracksForCollectionFacet(session.value, mix.facet, mix.value)
                .filterNot { it.id in excludeIds }
                .shuffled()
        }.getOrDefault(emptyList())
        if (mixGeneration != collectionMixGeneration) return
        if (firstTracks.isNotEmpty()) {
            appendToQueue(firstTracks)
            excludeIds += firstTracks.map { it.id }
        }
        val moreTracks = runCatching {
            dependencies.catalogRepository.tracksForCollectionFacet(session.value, mix.facet, mix.value)
                .filterNot { it.id in excludeIds }
                .shuffled()
        }.getOrDefault(emptyList())
        if (mixGeneration != collectionMixGeneration) return
        if (moreTracks.isNotEmpty()) {
            appendToQueue(moreTracks)
        }
    }

    private data class CollectionMix(val facet: CollectionFacet, val value: String)

    fun togglePlayPause() {
        val remoteTarget = mutableMusicAssistantRemotePlayback.value?.target
        if (remoteTarget != null) {
            mutableMessage.value = "Music Assistant playback is running on $remoteTarget. Use Music Assistant for pause/resume."
        } else {
            dependencies.playbackTransportService.togglePlayPause()
        }
    }

    /** Play / pause / toggle keys: no-op when no track is loaded. */
    fun mediaKeyTogglePlayPause() {
        if (player.value.currentTrack != null) {
            togglePlayPause()
        }
    }

    fun mediaKeyPlay() {
        val s = player.value
        if (s.currentTrack != null && !s.isPlaying) {
            togglePlayPause()
        }
    }

    fun mediaKeyPause() {
        if (player.value.isPlaying) {
            togglePlayPause()
        }
    }

    fun clearQueue() {
        markKeepPlayingQueueEditedByUser()
        if (mutableMusicAssistantRemotePlayback.value != null) {
            mutableMusicAssistantRemotePlayback.value = null
        } else {
            dependencies.playbackTransportService.clearQueue()
        }
    }

    private fun stopPlayback() {
        mutableMusicAssistantRemotePlayback.value = null
        dependencies.playbackTransportService.stopPlayback()
    }
    fun addToUpNext(track: Track) {
        markKeepPlayingQueueEditedByUser()
        dependencies.playbackTransportService.addToUpNext(track)
    }
    fun appendToQueue(tracks: List<Track>) = dependencies.playbackTransportService.appendToQueue(tracks)
    fun moveUpNext(fromIndex: Int, toIndex: Int) {
        markKeepPlayingQueueEditedByUser()
        dependencies.playbackTransportService.moveUpNext(fromIndex, toIndex)
    }
    fun removeUpNext(index: Int) {
        markKeepPlayingQueueEditedByUser()
        dependencies.playbackTransportService.removeUpNext(index)
    }
    fun playUpNext(index: Int) {
        val current = player.value
        val target = current.currentIndex + 1 + index
        if (target in current.queue.indices) {
            playTracks(current.queue, target, preserveQueueContext = true)
        }
    }
    fun next() {
        val remote = mutableMusicAssistantRemotePlayback.value
        if (remote != null) {
            playTracks(remote.tracks, (remote.index + 1).coerceIn(0, remote.tracks.lastIndex), preserveQueueContext = true)
        } else if (requestKeepPlayingForQueueTailNext()) {
            // Keep Playing will advance to the first appended track if related songs are found.
        } else {
            dependencies.playbackTransportService.next()
        }
    }

    private fun requestKeepPlayingForQueueTailNext(): Boolean {
        val playback = player.value
        if (playback.currentIndex !in playback.queue.indices) return false
        if (playback.currentIndex < playback.queue.lastIndex) return false
        return startKeepPlayingForSignal(
            signal = KeepPlayingSignal(
                playback = playback,
                enabled = appSettings.value.keepPlayingEnabled,
                castConnected = dependencies.castController.state.value.isConnected,
                musicAssistantRemoteActive = mutableMusicAssistantRemotePlayback.value != null,
            ),
            requireActivePlayback = false,
            advanceWhenAppended = true,
            requireNearEnd = true,
            forceRequest = true,
        )
    }
    fun previous() {
        val remote = mutableMusicAssistantRemotePlayback.value
        if (remote != null) {
            playTracks(remote.tracks, (remote.index - 1).coerceIn(0, remote.tracks.lastIndex), preserveQueueContext = true)
        } else {
            dependencies.playbackTransportService.previous()
        }
    }
    fun skipQueueBy(delta: Int) {
        if (delta == 0) return
        val current = player.value
        if (current.currentIndex < 0 || current.queue.isEmpty()) return
        val target = (current.currentIndex + delta).coerceIn(0, current.queue.lastIndex)
        if (target == current.currentIndex) return
        playTracks(current.queue, target, preserveQueueContext = true)
    }
    fun seekTo(positionMs: Long) = dependencies.playbackTransportService.seekTo(positionMs)
    suspend fun loadLyrics(
        track: Track,
        forceRefresh: Boolean = false,
        includeRemoteAnnotations: Boolean = true,
        forceRemoteAnnotationsRefresh: Boolean = forceRefresh,
    ): LyricsLoadState =
        dependencies.lyricsRepository.lyricsFor(
            track = track,
            forceRefresh = forceRefresh,
            includeRemoteAnnotations = includeRemoteAnnotations,
            forceRemoteAnnotationsRefresh = forceRemoteAnnotationsRefresh,
        )

    fun toggleShuffle() = dependencies.playbackTransportService.toggleShuffle(player.value.shuffle)
    fun cycleRepeat() {
        val currentRepeat = player.value.repeat
        val nextRepeat = when (currentRepeat) {
            RepeatMode.Off -> RepeatMode.One
            RepeatMode.One -> RepeatMode.All
            RepeatMode.All -> RepeatMode.Off
        }
        dependencies.playbackTransportService.cycleRepeat(currentRepeat)
        if (nextRepeat != RepeatMode.Off && appSettings.value.keepPlayingEnabled) {
            clearKeepPlayingContinuationState()
            scope.launch {
                dependencies.settingsService.setKeepPlayingEnabled(false)
            }
        }
    }
    fun setVolume(volume: Float) {
        dependencies.playbackTransportService.setVolume(volume)
        if (isDesktopPlatform() && appSettings.value.persistVolumeSettings) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                dependencies.appSettingsRepository.setSavedVolume(volume.coerceIn(0f, 1f))
            }
        }
    }

    fun setPersistVolumeSettings(enabled: Boolean) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            dependencies.appSettingsRepository.setPersistVolumeSettings(enabled, player.value.volume)
        }
    }

    fun showCastPicker() {
        if (dependencies.castController.state.value.isAvailable) {
            dependencies.castController.showDevicePicker()
        } else {
            mutableMessage.value = dependencies.castController.state.value.message
                ?: "Chromecast is available on Android, iOS, desktop, and Chrome web."
        }
    }

    fun setLibrarySortBy(sortBy: LibrarySortBy) = scope.launch {
        dependencies.libraryPreferencesService.setSortBy(sortBy)
    }

    fun setLibrarySortAscending(ascending: Boolean) = scope.launch {
        dependencies.libraryPreferencesService.setAscending(ascending)
    }

    fun setLibraryColumns(columns: LibraryColumnVisibility) {
        dependencies.libraryPreferencesService.applyColumns(columns)
        scope.launch(Dispatchers.Default) {
            dependencies.libraryPreferencesService.persistCurrentToDisk()
        }
    }

    fun setHomeSections(sections: List<HomeSection>) = scope.launch {
        dependencies.libraryPreferencesService.setHomeSections(sections)
    }

    fun setMobileBottomTabs(tabs: List<MobileBottomTab>) = scope.launch {
        dependencies.libraryPreferencesService.setMobileBottomTabs(tabs)
    }

    fun setPersonalMixPreferences(preferences: PersonalMixPreferences) = scope.launch {
        dependencies.libraryPreferencesService.setPersonalMix(preferences)
    }

    fun setAlbumGridItemSize(sizeDp: Int) = scope.launch {
        dependencies.libraryPreferencesService.setAlbumGridItemSize(sizeDp)
    }

    fun setArtistGridItemSize(sizeDp: Int) = scope.launch {
        dependencies.libraryPreferencesService.setArtistGridItemSize(sizeDp)
    }

    fun refreshInternetRadio() = scope.launch {
        dependencies.radioRepository.refreshPopular()
    }

    fun searchInternetRadio(query: RadioStationSearchQuery) = scope.launch {
        dependencies.radioRepository.search(query)
    }

    fun browseInternetRadioCountry(countryCode: String) = scope.launch {
        dependencies.radioRepository.browseCountry(countryCode)
    }

    fun loadInternetRadioMap(query: RadioStationSearchQuery = RadioStationSearchQuery(), page: Int = 0) = scope.launch {
        dependencies.radioRepository.loadGlobe(query, page)
    }

    fun loadFocusedInternetRadioMap(
        query: RadioStationSearchQuery = RadioStationSearchQuery(),
        page: Int = 0,
        countryCode: String? = null,
    ) = scope.launch {
        dependencies.radioRepository.loadGlobe(
            query = query,
            page = page,
            countryCode = countryCode,
        )
    }

    fun showInternetRadioStation(stationId: String) = scope.launch {
        dependencies.radioRepository.showStation(stationId)
    }

    fun playInternetRadioStation(stationId: String) = scope.launch {
        val station = runCatching { dependencies.radioRepository.findStationById(stationId) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        if (station == null) {
            val message = "Radio station not found."
            mutableMessage.value = message
            surfacePlaybackSnackbar(message)
            return@launch
        }
        playInternetRadioStation(station)
    }

    fun loadMoreInternetRadio() = scope.launch {
        dependencies.radioRepository.loadMore()
    }

    fun addManualRadioStation(name: String, streamUrl: String) = scope.launch {
        dependencies.radioRepository.addManualStation(name, streamUrl)
            .onFailure { mutableMessage.value = it.message ?: "Couldn't add radio station." }
    }

    fun updateManualRadioStation(station: RadioStation, name: String, streamUrl: String) = scope.launch {
        dependencies.radioRepository.updateManualStation(station.id, name, streamUrl)
            .onFailure { mutableMessage.value = it.message ?: "Couldn't update radio station." }
    }

    fun deleteManualRadioStation(station: RadioStation) = scope.launch {
        dependencies.radioRepository.deleteManualStation(station.id)
    }

    fun playInternetRadioStation(station: RadioStation) = scope.launch {
        mutableInternetRadioStartingIds.value = mutableInternetRadioStartingIds.value + station.id
        val track = runCatching { dependencies.radioRepository.stationTrack(station) }
            .getOrElse { error ->
                mutableInternetRadioStartingIds.value = mutableInternetRadioStartingIds.value - station.id
                val message = error.message ?: "Couldn't start ${station.name}."
                mutableMessage.value = message
                surfacePlaybackSnackbar(message, station.streamUrl)
                return@launch
        }
        try {
            if (playTracks(listOf(track), 0, clearShuffle = true)) {
                val message = "Playing ${station.name}."
                mutableMessage.value = message
                surfaceInternetRadioStartupTimeout(track, station)
            }
        } finally {
            mutableInternetRadioStartingIds.value = mutableInternetRadioStartingIds.value - station.id
        }
    }

    private fun surfaceInternetRadioStartupTimeout(track: Track, station: RadioStation) = scope.launch {
        delay(InternetRadioStartupTimeoutMs)
        val playback = dependencies.audioPlayer.state.value
        if (playback.currentTrack?.id != track.id || !playback.isBuffering) return@launch
        dependencies.audioPlayer.stopPlayback()
        val message = "Couldn't start ${station.name}."
        mutableMessage.value = message
        surfacePlaybackSnackbar(message, track.streamUrl.ifBlank { station.streamUrl })
    }

    fun prependRecentSearch(item: RecentSearchItem) = scope.launch {
        dependencies.settingsService.prependRecentSearch(item)
    }

    fun removeRecentSearch(item: RecentSearchItem) = scope.launch {
        dependencies.settingsService.removeRecentSearch(item)
    }

    fun clearRecentSearches() = scope.launch {
        dependencies.settingsService.clearRecentSearches()
    }

    fun setCrossfadeSeconds(seconds: Int) = scope.launch {
        dependencies.settingsService.setCrossfadeSeconds(seconds)
    }

    fun setScanLibraryOnLaunch(enabled: Boolean) = scope.launch {
        dependencies.settingsService.setScanLibraryOnLaunch(enabled)
    }

    fun setNotifyWhenDownloadFinishes(enabled: Boolean) = scope.launch {
        dependencies.settingsService.setNotifyWhenDownloadFinishes(enabled)
        if (enabled) {
            requestNotificationPermission()
        }
    }

    fun setKeepPlayingEnabled(enabled: Boolean) = scope.launch {
        dependencies.settingsService.setKeepPlayingEnabled(enabled)
        if (enabled) {
            keepPlayingDisabledGeneration = null
            dependencies.audioPlayer.setRepeat(RepeatMode.Off)
            keepPlayingPreviewPendingGeneration = keepPlayingQueueGeneration
            if (requestKeepPlayingPreview(forceRequest = true)) {
                keepPlayingPreviewPendingGeneration = null
            }
        } else {
            clearKeepPlayingContinuationState()
        }
    }

    fun setNowPlayingVisualizerPreset(preset: NowPlayingVisualizerPreset) = scope.launch {
        dependencies.settingsService.setNowPlayingVisualizerPreset(preset)
    }

    fun setNowPlayingVisualizerInTvFrame(enabled: Boolean) = scope.launch {
        dependencies.settingsService.setNowPlayingVisualizerInTvFrame(enabled)
    }

    fun setShowUltimateGuitarButton(enabled: Boolean) = scope.launch {
        dependencies.settingsService.setShowUltimateGuitarButton(enabled)
    }

    fun setBlurredArtworkAppearance(enabled: Boolean) = scope.launch {
        dependencies.settingsService.setBlurredArtworkAppearance(enabled)
    }

    fun setFullBleedDetailArtwork(enabled: Boolean) = scope.launch {
        dependencies.settingsService.setFullBleedDetailArtwork(enabled)
    }

    fun setTintedBackgroundGradient(enabled: Boolean) = scope.launch {
        dependencies.settingsService.setTintedBackgroundGradient(enabled)
    }

    fun setAudioProcessingSettings(settings: AudioProcessingSettings) = scope.launch {
        dependencies.appSettingsRepository.setAudioProcessingSettings(settings)
    }

    fun setDownloadPolicySettings(settings: DownloadPolicySettings) = scope.launch {
        dependencies.appSettingsRepository.setDownloadPolicySettings(settings)
    }

    fun setEventSettings(settings: EventSettings) = scope.launch {
        dependencies.settingsService.setEventSettings(settings)
    }

    fun loadArtistEventAvailability(artist: Artist) {
        val existing = mutableArtistEvents.value[artist.id]
        if (existing != null && !existing.loading) return
        loadArtistEvents(artist = artist, limit = 1, force = false)
    }

    fun loadArtistEvents(artist: Artist, limit: Int = 50, force: Boolean = false) {
        val existing = mutableArtistEvents.value[artist.id]
        if (!force && existing != null && !existing.loading) return
        if (artistEventJobs[artist.id]?.isActive == true) return
        val settings = appSettings.value.events.normalized()
        mutableArtistEvents.update { current ->
            current + (artist.id to ArtistEventsLoadState(loading = true, events = existing?.events.orEmpty()))
        }
        artistEventJobs[artist.id] = scope.launch {
            try {
                val response = dependencies.artistEventsRepository.fetchArtistEvents(
                    artist = artist.title,
                    limit = limit,
                    settings = settings,
                )
                mutableArtistEvents.update { current ->
                    current + (artist.id to ArtistEventsLoadState(events = response.events))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableArtistEvents.update { current ->
                    current + (artist.id to ArtistEventsLoadState(error = error.message ?: "Couldn't load events."))
                }
            } finally {
                artistEventJobs.remove(artist.id)
            }
        }
    }

    fun loadAlbumMusicBrainzMetadata(album: Album, tracks: List<Track>, force: Boolean = false) {
        val existing = mutableAlbumMusicBrainzMetadata.value[album.id]
        if (!force && existing != null && !existing.loading) return
        if (albumMusicBrainzJobs[album.id]?.isActive == true) return
        val settings = appSettings.value.events.normalized()
        mutableAlbumMusicBrainzMetadata.update { current ->
            current + (album.id to MusicBrainzAlbumMetadataLoadState(loading = true, metadata = existing?.metadata))
        }
        albumMusicBrainzJobs[album.id] = scope.launch {
            try {
                val response = dependencies.musicBrainzRepository.albumMetadata(
                    album = album,
                    tracks = tracks,
                    settings = settings,
                )
                mutableAlbumMusicBrainzMetadata.update { current ->
                    current + (album.id to MusicBrainzAlbumMetadataLoadState(metadata = response))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableAlbumMusicBrainzMetadata.update { current ->
                    current + (
                        album.id to MusicBrainzAlbumMetadataLoadState(
                            metadata = existing?.metadata,
                            error = error.message ?: "Couldn't load MusicBrainz album metadata.",
                        )
                    )
                }
            } finally {
                albumMusicBrainzJobs.remove(album.id)
            }
        }
    }

    fun loadArtistMusicBrainzArtwork(artist: Artist, force: Boolean = false) {
        val existing = mutableArtistMusicBrainzArtwork.value[artist.id]
        if (!force && existing != null && !existing.loading) return
        if (artistMusicBrainzArtworkJobs[artist.id]?.isActive == true) return
        val settings = appSettings.value.events.normalized()
        mutableArtistMusicBrainzArtwork.update { current ->
            current + (artist.id to MusicBrainzArtistArtworkLoadState(loading = true, response = existing?.response))
        }
        artistMusicBrainzArtworkJobs[artist.id] = scope.launch {
            try {
                val response = withTimeoutOrNull(ArtistMusicBrainzArtworkTimeoutMs) {
                    dependencies.musicBrainzRepository.artistArtwork(
                        artist = artist.title,
                        excludedArtworkUrls = listOfNotNull(artist.thumbUrl),
                        settings = settings,
                    )
                }
                mutableArtistMusicBrainzArtwork.update { current ->
                    current + (
                        artist.id to if (response == null) {
                            MusicBrainzArtistArtworkLoadState(
                                response = existing?.response,
                                error = "MusicBrainz artwork lookup timed out.",
                            )
                        } else {
                            MusicBrainzArtistArtworkLoadState(response = response)
                        }
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableArtistMusicBrainzArtwork.update { current ->
                    current + (
                        artist.id to MusicBrainzArtistArtworkLoadState(
                            response = existing?.response,
                            error = error.message ?: "Couldn't load MusicBrainz artist artwork.",
                        )
                    )
                }
            } finally {
                artistMusicBrainzArtworkJobs.remove(artist.id)
            }
        }
    }

    fun resolvedEventsBackendBaseUrl(settings: EventSettings = appSettings.value.events): String? =
        dependencies.artistEventsRepository.resolvedBackendBaseUrl(settings)

    fun checkEventsBackendHealth(settings: EventSettings = appSettings.value.events) {
        mutableEventsBackendHealth.value = EventsBackendHealthState(checking = true)
        scope.launch {
            val result = dependencies.artistEventsRepository.checkHealth(settings)
            mutableEventsBackendHealth.value = result.fold(
                onSuccess = { EventsBackendHealthState(message = "Connected to Phoebe backend.", success = true) },
                onFailure = { error ->
                    EventsBackendHealthState(
                        message = error.message ?: "Couldn't reach Phoebe backend.",
                        success = false,
                    )
                },
            )
        }
    }

    fun checkForUpdates() = scope.launch {
        dependencies.appUpdateService.checkForUpdates { error ->
            val message = error.message ?: "Couldn't check for updates."
            mutableMessage.value = message
            surfacePlaybackSnackbar(message)
        }
    }

    fun installAvailableUpdate() = scope.launch {
        dependencies.appUpdateService.installAvailableUpdate { message ->
            mutableMessage.value = message
            surfacePlaybackSnackbar(message)
        }
    }

    fun respondToUpdateInstallConfirmation(install: Boolean) {
        dependencies.appUpdateService.respondToInstallConfirmation(install)
    }

    fun connectListenBrainz(userToken: String) = scope.launch {
        mutableMessage.value = "Connecting ListenBrainz…"
        runCatching {
            dependencies.listenBrainzService.connect(userToken, LISTEN_BRAINZ_CONNECT_TIMEOUT_MS)
        }.onSuccess { message ->
            mutableMessage.value = message
        }.onFailure { error ->
            mutableMessage.value = if (error is TimeoutCancellationException) {
                "ListenBrainz did not respond. Check the token and try again."
            } else {
                error.message ?: "Couldn't connect ListenBrainz."
            }
        }
    }

    fun disconnectListenBrainz() = scope.launch {
        runCatching {
            dependencies.listenBrainzService.disconnect()
        }.onSuccess { message ->
            mutableMessage.value = message
        }.onFailure { error ->
            mutableMessage.value = error.message ?: "Couldn't disconnect ListenBrainz."
        }
    }

    fun setListenBrainzSubmitNowPlaying(enabled: Boolean) = scope.launch {
        dependencies.listenBrainzService.setSubmitNowPlaying(enabled)
    }

    fun setListenBrainzSubmitListens(enabled: Boolean) = scope.launch {
        dependencies.listenBrainzService.setSubmitListens(enabled)
    }

    fun setListenBrainzSubmitCurrentTrackFeedback(enabled: Boolean) = scope.launch {
        dependencies.listenBrainzService.setSubmitCurrentTrackFeedback(enabled)
    }

    fun submitListenBrainzFeedback(score: ListenBrainzFeedbackScore) = scope.launch {
        mutableMessage.value = dependencies.listenBrainzService.submitCurrentTrackFeedback(score)
    }

    fun startLastFmAuthorization(apiKey: String, sharedSecret: String) = scope.launch {
        mutableMessage.value = "Opening Last.fm authorization..."
        runCatching {
            val request = dependencies.lastFmService.createAuthorizationRequest(apiKey, sharedSecret, LAST_FM_CONNECT_TIMEOUT_MS)
            pendingLastFmAuth = PendingLastFmAuth(request.apiKey, request.sharedSecret, request.token)
            openExternalUrl(request.authorizationUrl)
            request
        }.onSuccess {
            mutableMessage.value = "Authorize Phoebe in Last.fm, then return here and click Finish."
        }.onFailure { error ->
            mutableMessage.value = if (error is TimeoutCancellationException) {
                "Last.fm did not respond. Check the API credentials and try again."
            } else {
                error.message ?: "Couldn't start Last.fm authorization."
            }
        }
    }

    fun finishLastFmAuthorization() = scope.launch {
        val pending = pendingLastFmAuth
        if (pending == null) {
            mutableMessage.value = "Start Last.fm authorization first."
            return@launch
        }
        mutableMessage.value = "Finishing Last.fm connection..."
        runCatching {
            dependencies.lastFmService.connectAuthorizedToken(
                pending.apiKey,
                pending.sharedSecret,
                pending.token,
                LAST_FM_CONNECT_TIMEOUT_MS,
            )
        }.onSuccess { message ->
            pendingLastFmAuth = null
            mutableMessage.value = message
        }.onFailure { error ->
            mutableMessage.value = if (error is TimeoutCancellationException) {
                "Last.fm did not respond. Confirm authorization completed and try Finish again."
            } else {
                error.message ?: "Couldn't finish Last.fm authorization."
            }
        }
    }

    fun disconnectLastFm() = scope.launch {
        runCatching {
            dependencies.lastFmService.disconnect()
        }.onSuccess { message ->
            mutableMessage.value = message
        }.onFailure { error ->
            mutableMessage.value = error.message ?: "Couldn't disconnect Last.fm."
        }
    }

    fun setLastFmSubmitNowPlaying(enabled: Boolean) = scope.launch {
        dependencies.lastFmService.setSubmitNowPlaying(enabled)
    }

    fun setLastFmSubmitScrobbles(enabled: Boolean) = scope.launch {
        dependencies.lastFmService.setSubmitScrobbles(enabled)
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        applyEqualizerProfile(mutableEqualizerProfile.value.withEnabled(enabled))
    }

    fun setEqualizerBandCount(count: Int) {
        applyEqualizerProfile(mutableEqualizerProfile.value.withBandCount(count))
    }

    fun setEqualizerGain(index: Int, gainDb: Float) {
        val current = mutableEqualizerProfile.value
        val next = current
            .withEnabled(true)
            .withGain(index, gainDb)
        applyEqualizerProfile(next)
    }

    fun resetEqualizer() {
        val current = mutableEqualizerProfile.value.normalized()
        applyEqualizerProfile(
            EqualizerProfile.Default
                .withBandCount(current.bandCount)
                .withEnabled(current.enabled),
        )
    }

    fun setPersistEqualizerSettings(enabled: Boolean) {
        mutablePersistEqualizerSettings.value = enabled
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            dependencies.appSettingsRepository.setPersistEqualizerSettings(enabled, mutableEqualizerProfile.value)
        }
    }

    private fun applyEqualizerProfile(profile: EqualizerProfile) {
        val normalized = profile.normalized()
        if (mutableEqualizerProfile.value == normalized) return
        mutableEqualizerProfile.value = normalized
        dependencies.audioPlayer.setEqualizer(normalized)
        if (mutablePersistEqualizerSettings.value) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                dependencies.appSettingsRepository.setEqualizerProfile(normalized)
            }
        }
        if (equalizerRemoteUnavailable.value && normalized.enabled) {
            surfaceTransientNotice("Equalizer changes apply on this device. Chromecast and remote players use their own audio path.")
        }
    }

    fun download(track: Track) = launchDownload {
        mutableMessage.value = "Downloading ${track.title}…"
        dependencies.downloadService.download(track)
    }

    fun download(album: Album) = launchDownload {
        mutableMessage.value = "Downloading ${album.title}…"
        dependencies.downloadService.download(session.value, album)
    }

    fun download(artist: Artist) = launchDownload {
        mutableMessage.value = "Downloading ${artist.title}…"
        dependencies.downloadService.download(session.value, artist)
    }

    fun download(playlist: Playlist) = launchDownload {
        mutableMessage.value = "Downloading ${playlist.title}…"
        dependencies.downloadService.download(session.value, playlist)
    }

    private fun launchDownload(block: suspend () -> DownloadServiceResult): Job {
        cellularDownloadNotice()?.let { notice ->
            surfacePlaybackSnackbar(notice)
        }
        lateinit var downloadJob: Job
        downloadJob = scope.launch {
            try {
                downloadedArtworkCacheJob?.cancel()
                val result = block()
                mutableMessage.value = result.message
                if (result.message.startsWith("Downloads are paused")) {
                    surfacePlaybackSnackbar(result.message)
                }
                dependencies.downloadService.notifyDownloadFinishedIfNeeded(result.batch)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                PhoebeLog.d("AppState") { "download failed: ${error.message}" }
                mutableMessage.value = error.message?.takeIf { it.isNotBlank() }
                    ?.let { "Download failed: $it" }
                    ?: "Download failed."
            } finally {
                activeDownloadJobs.remove(downloadJob)
                publishActiveDownloadJobCount()
            }
        }
        activeDownloadJobs += downloadJob
        publishActiveDownloadJobCount()
        return downloadJob
    }

    private fun cellularDownloadNotice(): String? {
        val policy = appSettings.value.downloadPolicy.normalized()
        if (policy.wifiOnly) return null
        val network = currentNetworkMeteringStatus()
        if (!network.isCellular) return null
        return "Downloading over cellular. Turn on Wi-Fi only in Settings to pause mobile-network downloads."
    }

    fun setDownloadDirectory(uri: String?) = scope.launch {
        val result = dependencies.downloadService.setDownloadDirectory(uri)
        mutableDownloadDirectory.value = result.uri
        mutableMessage.value = result.message
    }

    fun resetDownloadDirectory() = setDownloadDirectory(null)

    fun deleteAllDownloads() = scope.launch {
        mutableMessage.value = dependencies.downloadService.deleteAllDownloads()
    }

    fun deleteCompletedDownloads() = scope.launch {
        mutableMessage.value = dependencies.downloadService.deleteCompletedDownloads()
    }

    fun clearFailedDownloads() = scope.launch {
        mutableMessage.value = dependencies.downloadService.clearFailedDownloads()
    }

    fun retryFailedDownloads(trackIds: Set<String> = emptySet()) = launchDownload {
        mutableMessage.value = "Retrying failed downloads…"
        dependencies.downloadService.retryFailedDownloads(trackIds)
    }

    fun cancelDownloadsWithoutDeleting(trackIds: Set<String>) = scope.launch {
        mutableMessage.value = dependencies.downloadService.cancelDownloadsWithoutDeleting(trackIds)
    }

    fun deleteDownloadsByTrackIds(trackIds: Set<String>) = scope.launch {
        mutableMessage.value = dependencies.downloadService.deleteDownloadsForTrackIds(trackIds)
    }

    fun deleteDownloads(tracks: List<Track>) = scope.launch {
        deleteResolvedDownloads(tracks)
    }

    fun deleteDownloads(playlist: Playlist) = scope.launch {
        val tracks = dependencies.downloadService.tracksForPlaylist(session.value, playlist)
        deleteResolvedDownloads(tracks)
    }

    fun cancelDownloads(tracks: List<Track>) = scope.launch {
        cancelResolvedDownloads(tracks)
    }

    fun cancelDownloads(playlist: Playlist) = scope.launch {
        mutableMessage.value = "Preparing to cancel ${playlist.title}…"
        val tracks = dependencies.downloadService.tracksForPlaylist(session.value, playlist)
        cancelResolvedDownloads(tracks)
    }

    private suspend fun deleteResolvedDownloads(tracks: List<Track>) {
        mutableMessage.value = dependencies.downloadService.deleteDownloadsForTracks(tracks)
    }

    private suspend fun cancelResolvedDownloads(tracks: List<Track>) {
        mutableMessage.value = "Cancelling download…"
        val jobs = activeDownloadJobs.toList()
        jobs.forEach { it.cancel() }
        val message = dependencies.downloadService.cancelDownloadsForTracks(tracks)
        jobs.forEach { it.join() }
        mutableMessage.value = message
    }

    /**
     * Create a new playlist. Remote playlists require a signed-in provider session with a music library;
     * local playlists require at least one enabled local folder and only accept local audio files.
     */
    fun createPlaylist(
        title: String,
        initialTracks: List<Track> = emptyList(),
        onCreated: ((com.phoebe.app.domain.Playlist) -> Unit)? = null,
    ) = scope.launch {
        val result = dependencies.playlistService.createPlaylist(
            session = session.value,
            hasEnabledLocalFolders = hasEnabledLocalFolders(),
            title = title,
            initialTracks = initialTracks,
        )
        val createdPlaylist = result.playlist
        if (createdPlaylist != null) {
            onCreated?.invoke(createdPlaylist)
        } else {
            mutableMessage.value = result.message ?: "Couldn't create playlist '$title'."
        }
    }

    /** Append [track] to [playlist] when the session, playlist type, and track are eligible. */
    fun addToPlaylist(playlist: com.phoebe.app.domain.Playlist, track: Track, allowDuplicate: Boolean = false) = scope.launch {
        PhoebeLog.d("AppState") { "addToPlaylist → playlist='${playlist.title}' (${playlist.id}), track='${track.title}' (${track.id})" }
        if (allowDuplicate) {
            mutablePendingDuplicatePlaylistAdd.value = null
        }
        val result = dependencies.playlistService.addToPlaylist(
            session = session.value,
            catalog = catalog.value,
            playlist = playlist,
            track = track,
            allowDuplicate = allowDuplicate,
        )
        if (result.alreadyPresent && !allowDuplicate) {
            mutablePendingDuplicatePlaylistAdd.value = PendingDuplicatePlaylistAdd(
                playlist = playlist,
                track = track,
                message = result.message,
            )
        } else {
            mutablePendingDuplicatePlaylistAdd.value = null
            mutableMessage.value = result.message
        }
    }

    fun dismissDuplicatePlaylistAdd() {
        mutablePendingDuplicatePlaylistAdd.value = null
    }

    fun moveDuplicatePlaylistAddToTop(pending: PendingDuplicatePlaylistAdd) = scope.launch {
        mutablePendingDuplicatePlaylistAdd.value = null
        val tracks = catalog.value.tracksByParent[pending.playlist.id].orEmpty()
        val targetKey = pending.track.playlistEntryKey()
        val fromIndex = tracks.indexOfFirst { it.playlistEntryKey() == targetKey || it.id == pending.track.id }
        if (fromIndex < 0) {
            mutableMessage.value = "Couldn't find that song in ${pending.playlist.title}."
            return@launch
        }
        if (fromIndex == 0) {
            mutableMessage.value = "Song is already at the top of ${pending.playlist.title}."
            return@launch
        }
        dependencies.playlistService.movePlaylistTrack(session.value, pending.playlist, fromIndex, 0)
            ?.let { mutableMessage.value = it }
            ?: run { mutableMessage.value = "Moved to the top of ${pending.playlist.title}." }
    }

    fun movePlaylistTrack(playlist: Playlist, fromIndex: Int, toIndex: Int) = scope.launch {
        dependencies.playlistService.movePlaylistTrack(session.value, playlist, fromIndex, toIndex)
            ?.let { mutableMessage.value = it }
    }

    fun removePlaylistTracks(playlist: Playlist, tracks: List<Track>) = scope.launch {
        dependencies.playlistService.removePlaylistTracks(session.value, playlist, tracks)
            ?.let { mutableMessage.value = it }
    }

    fun deletePlaylist(playlist: Playlist) = scope.launch {
        if (playlist.isSmartPlaylist()) {
            deleteSmartPlaylist(dependencies.userArtifactsRepository.smartPlaylists.value.firstOrNull { it.id == playlist.id } ?: run {
                mutableMessage.value = "Couldn't find that smart playlist."
                return@launch
            })
            return@launch
        }
        mutableMessage.value = dependencies.playlistService.deletePlaylist(session.value, playlist)
    }

    fun saveSmartPlaylistToProvider(playlist: Playlist) = scope.launch {
        val result = dependencies.playlistService.saveSmartPlaylistToProvider(session.value, playlist)
        result.message?.let { mutableMessage.value = it }
    }

    fun toggleLikedTrack(track: Track) = scope.launch {
        if (!track.canTogglePlexLike()) {
            mutableMessage.value = "Liked Songs syncs streaming library songs only."
            return@launch
        }
        if (!session.value.supportsRemotePlaylists()) {
            mutableMessage.value = "Sign in and select a music library to like songs."
            return@launch
        }
        val liked = runCatching {
            dependencies.catalogRepository.toggleLikedTrackLocally(session.value, track)
        }.getOrElse { error ->
            PhoebeLog.d("AppState") { "toggleLikedTrack failed for '${track.title}': ${error.message}" }
            mutableMessage.value = error.message ?: "Couldn't update Liked Songs."
            return@launch
        }
        mutableMessage.value = if (liked) "Song is in Liked Songs." else "Removed from Liked Songs."
        syncLikedSongsInBackground(track, liked)
    }

    private fun syncLikedSongsInBackground(track: Track? = null, liked: Boolean? = null) {
        scope.launch {
            val synced = runCatching {
                if (track != null && liked != null) {
                    dependencies.catalogRepository.syncLikedTrackChange(session.value, track, liked)
                } else {
                    dependencies.catalogRepository.syncLikedSongsPlaylist(session.value)
                }
            }.getOrElse { error ->
                PhoebeLog.d("AppState") { "Liked Songs sync failed: ${error.message}" }
                false
            }
            if (track != null && liked != null && synced != true) {
                val provider = session.value.providerLabel()
                mutableMessage.value = if (session.value.isNavidrome()) {
                    "Liked locally. Subsonic favorites sync failed — check your connection and try again."
                } else {
                    "Liked Songs updated locally. $provider sync will retry later."
                }
            }
        }
    }

    fun copyPlaylistIntoPlaylist(
        source: com.phoebe.app.domain.Playlist,
        target: com.phoebe.app.domain.Playlist,
    ) = scope.launch {
        dependencies.playlistService.copyPlaylistIntoPlaylist(session.value, source, target)
            ?.let { mutableMessage.value = it }
    }

    fun exportLocalPlaylist(
        playlist: com.phoebe.app.domain.Playlist,
        format: PlaylistExportFormat,
    ) = scope.launch {
        mutableMessage.value = dependencies.playlistService.exportLocalPlaylist(session.value, playlist, format)
    }

    private fun hasEnabledLocalFolders(): Boolean =
        mediaSources.value.localFolders.any { it.enabled }

    private fun shouldStopPlaybackForRemovedLocalFolder(folderId: String): Boolean {
        val playback = player.value
        if (playback.queue.any { it.isFromLocalFolder(folderId) }) return true
        val enabledAfterRemoval = mediaSources.value.localFolders.any { it.enabled && it.id != folderId }
        if (enabledAfterRemoval || session.value?.token?.isNotBlank() == true) return false
        return playback.currentTrack != null || playback.upNext.isNotEmpty()
    }

    fun updateTrackMetadata(update: TrackMetadataUpdate) = scope.launch {
        mutableMessage.value = dependencies.catalogItemMutationService.updateTrackMetadata(session.value, update)
    }

    fun rateTrack(track: Track, rating: Float?) = scope.launch {
        mutableMessage.value = dependencies.catalogItemMutationService.rateTrack(session.value, track, rating)
    }

    fun rateArtist(artist: Artist, rating: Float?) = scope.launch {
        mutableMessage.value = dependencies.catalogItemMutationService.rateArtist(session.value, artist, rating)
    }

    fun rateAlbum(album: Album, rating: Float?) = scope.launch {
        mutableMessage.value = dependencies.catalogItemMutationService.rateAlbum(session.value, album, rating)
    }

    fun ratePlaylist(playlist: Playlist, rating: Float?) = scope.launch {
        mutableMessage.value = dependencies.catalogItemMutationService.ratePlaylist(session.value, playlist, rating)
    }

    fun toggleFavoriteArtist(artist: Artist) = scope.launch {
        mutableMessage.value = dependencies.catalogItemMutationService.toggleFavoriteArtist(session.value, artist)
    }

    fun toggleFavoriteAlbum(album: Album) = scope.launch {
        mutableMessage.value = dependencies.catalogItemMutationService.toggleFavoriteAlbum(session.value, album)
    }

    fun toggleFavoritePlaylist(playlist: Playlist) = scope.launch {
        mutableMessage.value = dependencies.catalogItemMutationService.toggleFavoritePlaylist(session.value, playlist)
    }

    fun exportFavoritePlaylists() = scope.launch {
        mutableMessage.value = dependencies.catalogItemMutationService.exportFavoritePlaylists()
    }

    fun importFavoritePlaylists() = scope.launch {
        mutableMessage.value = dependencies.catalogItemMutationService.importFavoritePlaylists()
    }

    fun exportRadioStations() = scope.launch {
        mutableMessage.value = dependencies.catalogItemMutationService.exportRadioStations()
    }

    fun importRadioStations() = scope.launch {
        mutableMessage.value = dependencies.catalogItemMutationService.importRadioStations()
    }

    fun exportBackupPackage() = scope.launch {
        val payload = dependencies.importExportService.exportBackupPackage()
        runCatching {
            dependencies.platformStorage.writeText("exports/phoebe-backup.json", payload)
        }.fold(
            onSuccess = { mutableMessage.value = "Exported Phoebe backup package." },
            onFailure = { error -> mutableMessage.value = error.message ?: "Couldn't export Phoebe backup." },
        )
    }

    fun importBackupPackage(mode: BackupRestoreMode = BackupRestoreMode.Merge) = scope.launch {
        val payload = dependencies.platformStorage.readText("exports/phoebe-backup.json")
        if (payload.isNullOrBlank()) {
            mutableMessage.value = "No Phoebe backup export found."
            return@launch
        }
        val preview = dependencies.importExportService.restoreBackupPackage(payload, mode)
        dependencies.catalogRepository.refreshSmartPlaylists()
        mutableMessage.value = "Restored ${preview.smartPlaylistCount} smart playlists, " +
            "${preview.savedSearchCount} saved searches, and ${preview.metadataOverrideCount} metadata overrides."
    }

    fun addLocalFolderFromUri(rootUri: String?) = scope.launch {
        if (rootUri.isNullOrBlank()) return@launch
        val label = rootUri.trimEnd('/').substringAfterLast('/').substringBefore('?', "Local").ifBlank { "Local" }
        dependencies.mediaSourcesRepository.addLocalFolder(rootUri, label)
        refreshCatalogSuspended(catalogMessage = null)
        mutableMessage.value = "Added local music folder."
        if (defaultBrowseRequest() == AppNavigationRequest.Home) {
            requestNavigation(AppNavigationRequest.Home)
        }
    }

    fun removeLocalFolder(id: String) = scope.launch {
        val shouldStopPlayback = shouldStopPlaybackForRemovedLocalFolder(id)
        dependencies.mediaSourcesRepository.removeLocalFolder(id)
        if (shouldStopPlayback) {
            stopPlayback()
        }
        refreshCatalogSuspended(catalogMessage = null)
        mutableMessage.value = "Removed local folder."
        if (defaultBrowseRequest() == AppNavigationRequest.Radio) {
            requestNavigation(AppNavigationRequest.Radio)
        }
    }

    fun setLocalFolderEnabled(id: String, enabled: Boolean) = scope.launch {
        dependencies.mediaSourcesRepository.setLocalFolderEnabled(id, enabled)
        refreshCatalogSuspended(catalogMessage = null)
        if (defaultBrowseRequest() == AppNavigationRequest.Radio) {
            requestNavigation(AppNavigationRequest.Radio)
        }
    }

    fun signOut() {
        val refreshJob = catalogRefreshJob
        val historyJob = playHistorySyncJob
        val providerHistoryJob = providerPlayHistoryRefreshJob
        val plexCountRefreshJob = plexPlayCountRefreshJob
        val lightweightSyncJob = lightweightRemoteSyncJob
        catalogRefreshJob = null
        playHistorySyncJob = null
        providerPlayHistoryRefreshJob = null
        plexPlayCountRefreshJob = null
        lightweightRemoteSyncJob = null
        refreshJob?.cancel()
        historyJob?.cancel()
        providerHistoryJob?.cancel()
        plexCountRefreshJob?.cancel()
        lightweightSyncJob?.cancel()
        dependencies.catalogRepository.clearActiveSyncProgress()
        dependencies.catalogRepository.clearInMemoryCatalog()
        mostPlayedWarmSignature = null
        recentAlbumWarmSignature = null
        playedAlbumWarmSignature = null
        popularMixSeedSignature = null
        popularMixSeedTracks = emptyList()
        popularMixSeedBuildSignature = null
        popularMixSeedBuildDeferred?.cancel()
        popularMixSeedBuildDeferred = null
        topTracksMixWarmSignature = null
        topTracksMixBuildSignature = null
        topTracksMixBuildDeferred?.cancel()
        topTracksMixBuildDeferred = null
        prefetchedArtistIds.clear()
        prefetchedAlbumIds.clear()
        stopPlayback()
        mutablePin.value = null
        mutableLibraries.value = emptyList()
        mutableLibrariesLoading.value = false
        mutableAuthInProgress.value = false
        requestNavigation(AppNavigationRequest.SignIn)
        mutableMessage.value = "Signing out…"
        scope.launch {
            val signedOut = runCatching {
                refreshJob?.cancelAndJoin()
                historyJob?.cancelAndJoin()
                providerHistoryJob?.cancelAndJoin()
                lightweightSyncJob?.cancelAndJoin()
                dependencies.sessionRepository.signOut()
                dependencies.deleteDatabaseDataForSignOut()
            }.onFailure {
                mutableMessage.value = it.message ?: "Something went sideways."
            }.isSuccess
            if (signedOut) {
                mutableMessage.value = "Signed out."
            }
        }
    }

    private fun CoroutineScope.launchBusy(
        loadingMessage: String? = null,
        block: suspend () -> Unit,
    ) = launch {
        mutableBusy.value = true
        if (loadingMessage != null) {
            mutableMessage.value = loadingMessage
        }
        runCatching { block() }
            .onFailure { mutableMessage.value = it.message ?: "Something went sideways." }
        mutableBusy.value = false
    }

    private fun uniqueSmartPlaylistSuffix(title: String, nowMs: Long): String {
        val base = title
            .lowercase()
            .map { char -> if (char.isLetterOrDigit()) char else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
            .ifBlank { "playlist" }
        val existingIds = dependencies.userArtifactsRepository.smartPlaylists.value.mapTo(mutableSetOf()) { it.id }
        var candidate = "$base-$nowMs"
        var index = 2
        while ("${SmartPlaylist.IdPrefix}$candidate" in existingIds) {
            candidate = "$base-$nowMs-$index"
            index++
        }
        return candidate
    }

    private fun uniqueSearchSuffix(queryText: String, nowMs: Long): String {
        val base = queryText
            .lowercase()
            .map { char -> if (char.isLetterOrDigit()) char else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
            .take(36)
            .ifBlank { "search" }
        val existingIds = dependencies.userArtifactsRepository.savedSearches.value.mapTo(mutableSetOf()) { it.id }
        var candidate = "$base-$nowMs"
        var index = 2
        while ("saved:search:$candidate" in existingIds) {
            candidate = "$base-$nowMs-$index"
            index++
        }
        return candidate
    }
}

private data class PendingLastFmAuth(
    val apiKey: String,
    val sharedSecret: String,
    val token: String,
)

private fun Artist.mixBuilderPreloadKey(): String =
    id.ifBlank { title }

private fun PlexSession?.canUsePlexBackgroundFetches(): Boolean {
    val server = this?.selectedServer ?: return false
    if (isNavidrome() || isEmbyFamily()) return server.uri.isNotBlank()
    return server.uri.isNotBlank() ||
        server.connectionUris.isNotEmpty() ||
        server.advertisedConnectionUris.isNotEmpty() ||
        server.localConnectionUris.isNotEmpty()
}

private fun PlexSession?.topTracksMixSessionSignature(): String? {
    val serverId = this?.selectedServer?.id?.takeIf { it.isNotBlank() } ?: return null
    val libraryKey = selectedLibrary?.key?.takeIf { it.isNotBlank() } ?: return null
    return "$serverId:$libraryKey"
}

internal fun List<Track>.withFreshPlaybackUrls(session: PlexSession?): List<Track> {
    if (session == null || isEmpty()) return this
    var changed = false
    val refreshed = map { track ->
        val next = track.withFreshPlaybackUrls(session)
        if (next !== track) changed = true
        next
    }
    return if (changed) refreshed else this
}

internal fun Track.withFreshPlaybackUrls(session: PlexSession): Track {
    val refreshedStreamUrl = streamUrl.withFreshPlaybackAuth(session)
    val refreshedDownloadUrl = downloadUrl.withFreshPlaybackAuth(session)
    if (refreshedStreamUrl == streamUrl && refreshedDownloadUrl == downloadUrl) return this
    return copy(
        streamUrl = refreshedStreamUrl,
        downloadUrl = refreshedDownloadUrl,
    )
}

internal fun String.withFreshPlaybackAuth(session: PlexSession): String {
    if (isBlank() || session.token.isBlank()) return this
    val parsed = runCatching { Url(this) }.getOrNull() ?: return this
    if (parsed.protocol.name != "http" && parsed.protocol.name != "https") return this
    return when (session.providerType) {
        MediaProviderType.Plex -> withQueryParameter(parsed, "X-Plex-Token", session.token)
        MediaProviderType.Jellyfin,
        MediaProviderType.Emby -> withQueryParameter(parsed, "api_key", session.token)
        MediaProviderType.Navidrome -> withQueryParameters(
            parsed,
            "u" to session.userName,
            "p" to session.token,
        )
        MediaProviderType.MusicAssistant -> this
    }
}

private fun withQueryParameter(url: Url, name: String, value: String): String =
    withQueryParameters(url, name to value)

private fun withQueryParameters(url: Url, vararg replacements: Pair<String, String>): String {
    val original = url.toString()
    val fragment = original.substringAfter('#', missingDelimiterValue = "")
    val withoutFragment = original.substringBefore('#')
    val base = withoutFragment.substringBefore('?')
    val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
    val replacementMap = replacements
        .filter { (_, value) -> value.isNotBlank() }
        .associate { (name, value) -> name to value }
    if (replacementMap.isEmpty()) return original
    val seen = mutableSetOf<String>()
    val pairs = query
        .split('&')
        .filter { it.isNotBlank() }
        .mapNotNull { pair ->
            val name = pair.substringBefore('=')
            val replacement = replacementMap[name] ?: return@mapNotNull pair
            seen += name
            "$name=${replacement.encodeURLParameter()}"
        }
        .toMutableList()
    replacementMap.forEach { (name, value) ->
        if (name !in seen) pairs += "$name=${value.encodeURLParameter()}"
    }
    val rebuilt = buildString {
        append(base)
        if (pairs.isNotEmpty()) {
            append('?')
            append(pairs.joinToString("&"))
        }
        if (fragment.isNotBlank()) {
            append('#')
            append(fragment)
        }
    }
    return rebuilt
}

private data class PlaybackHistorySignal(
    val track: Track?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
)

private data class PlaybackHistoryRecord(
    val trackId: String? = null,
    val playedAtMs: Long = Long.MIN_VALUE,
)

private data class KeepPlayingSignal(
    val playback: PlayerState,
    val enabled: Boolean,
    val castConnected: Boolean,
    val musicAssistantRemoteActive: Boolean,
)

private data class PopularMixSeed(
    val session: PlexSession,
    val signature: String,
    val tracks: List<Track>,
)

internal fun List<Track>.popularMixQueue(random: Random = Random.Default): List<Track> =
    distinctBy { it.id }
        .chunked(PopularMixShuffleChunkSize)
        .flatMap { chunk -> chunk.shuffled(random) }

internal fun List<Track>.topTracksMixQueue(random: Random = Random.Default): List<Track> =
    distinctBy { it.id }.shuffled(random)

internal fun mixQueueStillActiveForAppend(
    currentQueue: List<Track>,
    seedQueue: List<Track>,
    currentIndex: Int,
): Boolean {
    if (seedQueue.isEmpty()) return false
    if (currentIndex !in currentQueue.indices) return false
    if (currentQueue.size < seedQueue.size) return false
    for (index in seedQueue.indices) {
        if (currentQueue[index].id != seedQueue[index].id) return false
    }
    return true
}

internal fun mixAppendCandidates(fullMix: List<Track>, existingQueue: List<Track>): List<Track> {
    val existingIds = existingQueue.mapTo(mutableSetOf()) { it.id }
    return fullMix.filter { existingIds.add(it.id) }
}

private fun PlayerState.shouldTriggerKeepPlaying(): Boolean {
    if (currentIndex !in queue.indices) return false
    val upcomingCount = queue.lastIndex - currentIndex
    if (upcomingCount <= 1) return true
    val currentDuration = durationMs.takeIf { it > 0L } ?: currentTrack?.durationMs.orZero()
    if (currentDuration <= 0L) return false
    val currentRemaining = (currentDuration - positionMs).coerceAtLeast(0L)
    val upcomingDuration = queue
        .drop(currentIndex + 1)
        .sumOf { it.durationMs.coerceAtLeast(0L) }
    return currentRemaining + upcomingDuration <= KeepPlayingRemainingThresholdMs
}

private fun PlayerState.hasEndedAtQueueTail(): Boolean {
    if (isPlaying || isBuffering) return false
    if (currentIndex !in queue.indices || currentIndex != queue.lastIndex) return false
    val currentDuration = durationMs.takeIf { it > 0L } ?: currentTrack?.durationMs.orZero()
    if (currentDuration <= 0L) return false
    return positionMs >= currentDuration - KeepPlayingEndedTailSlackMs
}

private fun UpNextDividerMarker?.visibleFor(playback: PlayerState): UpNextDividerMarker? {
    val divider = this ?: return null
    return divider.takeIf {
        playback.currentIndex in playback.queue.indices &&
            it.beforeQueueIndex > playback.currentIndex &&
            it.beforeQueueIndex <= playback.queue.size
    }
}

private fun keepPlayingSignature(queue: List<Track>, generation: Int): String =
    buildString {
        append(generation)
        queue.forEach { track ->
            append('|')
            append(track.id)
        }
    }

private fun String.keepPlayingSignatureQueueSize(): Int =
    count { it == '|' }

private fun List<Track>.toTrackListOrigin(): PlaybackQueueOrigin.TrackList =
    PlaybackQueueOrigin.TrackList(
        seedTrackIds = map { it.id },
        providerType = singleProviderTypeOrNull(),
    )

private fun List<Track>.singleProviderTypeOrNull(): MediaProviderType? {
    val providers = mapNotNull { track ->
        MediaProviderType.entries.firstOrNull { provider -> track.id.startsWith("${provider.catalogPrefix}:") }
    }.distinct()
    return providers.singleOrNull()
}

private fun Long?.orZero(): Long = this ?: 0L

private const val PlaybackHistoryDedupeWindowMs = 30_000L

private const val KeepPlayingRemainingThresholdMs = 8L * 60L * 1000L
private const val KeepPlayingEndedTailSlackMs = 1_500L
private const val KeepPlayingNativeSeedItemLimit = 4
private const val KeepPlayingNativeCandidateTimeoutMs = 5_000L
private const val KeepPlayingNativeItemsBudgetMs = 4_000L
private const val KeepPlayingNativeItemTimeoutMs = 1_200L
private const val KeepPlayingRecentTrackLimit = 25

private const val PopularMixSeedTrackLimit = 50
private const val PopularMixTrackLimit = 500
private const val PopularMixShuffleChunkSize = 50
private const val MixProviderLoadTimeoutMs = 20_000L
private const val ArtistMusicBrainzArtworkTimeoutMs = 35_000L

private const val PlayHistoryCatalogResolveTimeoutMs = 1_500L

private const val ProviderPlayHistoryDebounceMs = 8_000L

private const val InternetRadioStartupTimeoutMs = 30_000L
private const val RadioNowPlayingRefreshMs = 30_000L

private const val PLEX_SIGN_IN_TIMEOUT_MS = 20_000L

private const val LISTEN_BRAINZ_CONNECT_TIMEOUT_MS = 45_000L
private const val LAST_FM_CONNECT_TIMEOUT_MS = 45_000L
