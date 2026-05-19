package com.phoebe.app

import com.phoebe.app.domain.Album
import com.phoebe.app.domain.AppSettings
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.CollectionFacet
import com.phoebe.app.domain.CollectionTarget
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
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.PlayerState
import com.phoebe.app.domain.PlexPin
import com.phoebe.app.domain.PlexRadioStation
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.PersonalMixPreferences
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.TrackMetadataUpdate
import com.phoebe.app.domain.canAddToLocalPlaylist
import com.phoebe.app.domain.canAddToPlexPlaylist
import com.phoebe.app.domain.canTogglePlexLike
import com.phoebe.app.domain.isLocalPlaylist
import com.phoebe.app.domain.isRemoteProviderPlaylist
import com.phoebe.app.domain.isEmbyFamily
import com.phoebe.app.domain.isMusicAssistant
import com.phoebe.app.domain.isPlex
import com.phoebe.app.domain.serverAuthToken
import com.phoebe.app.domain.supportsCollectionEntry
import com.phoebe.app.domain.displayName
import com.phoebe.app.domain.supportsRemotePlaylists
import com.phoebe.app.domain.supportsRemoteRatings
import com.phoebe.app.domain.isJellyfin
import com.phoebe.app.domain.providerLabel
import com.phoebe.app.data.DownloadBatchResult
import com.phoebe.app.data.FavoriteSyncResult
import com.phoebe.app.data.FavoritePlaylistsExport
import com.phoebe.app.data.JellyfinQuickConnectResult
import com.phoebe.app.data.JellyfinPlayHistorySyncResult
import com.phoebe.app.data.PlexPlayHistorySyncResult
import com.phoebe.app.data.PlexClient
import com.phoebe.app.data.defaultPlexRadioStations
import com.phoebe.app.playlists.PlaylistExportFormat
import com.phoebe.app.playlists.PlaylistExporter
import com.phoebe.app.player.asPlayerState
import com.phoebe.app.player.isChromecastPlayableQueue
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.platform.discoverJellyfinServers as discoverJellyfinServersOnNetwork
import com.phoebe.app.platform.openExternalUrl
import com.phoebe.app.sources.LocalLibraryIO
import com.phoebe.app.navigation.PhoebeNavigationCommand
import com.phoebe.app.navigation.PhoebeRoute
import com.phoebe.app.navigation.defaultPhoebeRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow

data class MusicAssistantRemotePlayback(
    val tracks: List<Track>,
    val index: Int,
    val target: String,
)

class AppState(
    private val dependencies: AppDependencies,
    private val scope: CoroutineScope,
) {
    val session = dependencies.sessionRepository.session
    val catalog = dependencies.catalogRepository.catalog
    val catalogRefreshing: StateFlow<Boolean> = dependencies.catalogRepository.catalogRefreshing
    val catalogSyncState = dependencies.catalogRepository.catalogSyncState
    val mediaSources = dependencies.mediaSourcesRepository.state
    val cast = dependencies.castController.state
    private val mutableMusicAssistantRemotePlayback = MutableStateFlow<MusicAssistantRemotePlayback?>(null)
    val musicAssistantRemotePlayback = mutableMusicAssistantRemotePlayback.asStateFlow()
    val player: StateFlow<PlayerState> = combine(
        dependencies.audioPlayer.state,
        dependencies.castController.state,
        mutableMusicAssistantRemotePlayback,
    ) { audio, castState, musicAssistantRemote ->
        when {
            castState.isConnected && castState.queue.isNotEmpty() -> castState.asPlayerState(audio)
            musicAssistantRemote != null -> PlayerState(
                queue = musicAssistantRemote.tracks,
                currentIndex = musicAssistantRemote.index,
                isPlaying = true,
                bufferedPositionMs = musicAssistantRemote.tracks.getOrNull(musicAssistantRemote.index)?.durationMs ?: 0L,
                durationMs = musicAssistantRemote.tracks.getOrNull(musicAssistantRemote.index)?.durationMs ?: 0L,
                volume = audio.volume,
            )
            else -> audio
        }
    }.stateIn(scope, SharingStarted.Eagerly, dependencies.audioPlayer.state.value)
    val libraryUi = dependencies.libraryUiRepository.preferences
    val appSettings = dependencies.appSettingsRepository.settings
    val lastPlayedByArtist = dependencies.playHistoryRepository.lastPlayedByArtist
    val lastPlayedByAlbum = dependencies.playHistoryRepository.lastPlayedByAlbum
    val lastPlayedByTrack = dependencies.playHistoryRepository.lastPlayedByTrack
    val playCountsByTrack = dependencies.playHistoryRepository.playCountsByTrack
    val playEventsByTrack = dependencies.playHistoryRepository.playEventsByTrack
    val defaultDownloadDirectoryLabel: String = dependencies.platformStorage.defaultDownloadDirectoryLabel()

    private val mutableNavigationCommands = MutableSharedFlow<PhoebeNavigationCommand>(extraBufferCapacity = 32)
    val navigationCommands = mutableNavigationCommands.asSharedFlow()

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

    private val mutableMessage = MutableStateFlow("Sign in to Plex or Jellyfin, or add a local music folder to get started.")
    val message: StateFlow<String> = mutableMessage

    private val mutablePlaybackSnackbar = MutableStateFlow<String?>(null)
    val playbackSnackbar: StateFlow<String?> = mutablePlaybackSnackbar.asStateFlow()

    private val mutableDecadeMixNotice = MutableStateFlow<String?>(null)
    val decadeMixNotice: StateFlow<String?> = mutableDecadeMixNotice

    private val mutableRadioStations = MutableStateFlow<List<PlexRadioStation>>(emptyList())
    val radioStations: StateFlow<List<PlexRadioStation>> = mutableRadioStations

    private val mutableRadioStartingIds = MutableStateFlow<Set<String>>(emptySet())
    val radioStartingIds: StateFlow<Set<String>> = mutableRadioStartingIds

    private val mutableArtistRadioAvailability = MutableStateFlow<Map<String, ArtistRadioAvailability>>(emptyMap())
    val artistRadioAvailability: StateFlow<Map<String, ArtistRadioAvailability>> = mutableArtistRadioAvailability

    private val mutableDownloadDirectory = MutableStateFlow<String?>(null)
    val downloadDirectory: StateFlow<String?> = mutableDownloadDirectory

    private var playRequestGeneration = 0
    private var collectionMixGeneration = 0
    private var activeCollectionMix: CollectionMix? = null
    private var recentAlbumWarmSignature: String? = null
    private var playedAlbumWarmSignature: String? = null
    private var catalogRefreshJob: Job? = null
    private var playHistorySyncJob: Job? = null
    private val backgroundSyncJobs = mutableSetOf<Job>()
    private val activeDownloadJobs = mutableSetOf<Job>()

    init {
        scope.launch {
            PhoebeLog.d("AppState") { "startup restore begin" }
            // Session and local folders are restored in [AppDependencies.create] so the first frame
            // can skip Sign-in; repeat here for callers that inject dependencies without that path.
            dependencies.sessionRepository.restore(refreshConnections = false)
            dependencies.mediaSourcesRepository.restore()
            dependencies.appSettingsRepository.restore()
            dependencies.libraryUiRepository.restore()
            dependencies.audioPlayer.setCrossfadeDurationMs(appSettings.value.crossfadeSeconds * 1_000L)
            dependencies.playHistoryRepository.restore()
            mutableDownloadDirectory.value = dependencies.platformStorage.readDownloadDirectory()
            if (session.value?.token?.isNotBlank() == true && session.value?.selectedServer == null) {
                refreshServers()
            }
            dependencies.catalogRepository.restoreCachedCatalog()
            if (appSettings.value.scanLibraryOnLaunch) {
                launch {
                    delay(500)
                    refreshCatalogSuspended()
                }
            }
            if (session.value.isEmbyFamily() &&
                session.value?.selectedLibrary != null &&
                !dependencies.catalogRepository.catalog.value.hasBrowseableContent()
            ) {
                refreshCatalogSuspended(catalogMessage = "Library refreshed.")
            }
            cacheDownloadedArtworkInBackground()
            warmPlaylistTracksInBackground()
            syncRemotePlayHistoryInBackground()
            ensureLikedSongsPlaylistIfPossible()
            if (session.value?.token?.isNotBlank() == true && session.value?.selectedServer != null && session.value.isPlex()) {
                launch { dependencies.sessionRepository.refreshSelectedServerConnections() }
            }
            PhoebeLog.d("AppState") {
                "startup restore complete → route=${defaultRoute()}, " +
                    "session=${session.value?.userName ?: "none"}, " +
                    "localFolders=${mediaSources.value.localFolders.size}"
            }
        }
        bindSystemVolume()
        bindAppSettingsToPlayback()
        recordPlaybackHistory()
        surfacePlaybackFailures()
        dependencies.plexPlaybackReporter.start(scope)
    }

    private fun bindAppSettingsToPlayback() {
        scope.launch {
            appSettings.collect { settings ->
                dependencies.audioPlayer.setCrossfadeDurationMs(settings.crossfadeSeconds * 1_000L)
            }
        }
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
                mutablePlaybackSnackbar.value = notice
            }
        }
    }

    fun dismissPlaybackSnackbar() {
        mutablePlaybackSnackbar.value = null
    }

    /**
     * Each time the audio player transitions to a new track, record a play event
     * so the Library UI can surface "last played" timestamps per artist / album / song.
     * We watch [Track.id] rather than the [PlayerState] object so toggling pause /
     * seeking doesn't double-record the same play.
     */
    private fun recordPlaybackHistory() {
        scope.launch {
            var lastRecordedTrackId: String? = null
            dependencies.audioPlayer.state.collect { state ->
                val track = state.currentTrack ?: run {
                    lastRecordedTrackId = null
                    return@collect
                }
                if (track.id == lastRecordedTrackId) return@collect
                lastRecordedTrackId = track.id
                runCatching {
                    dependencies.playHistoryRepository.recordPlay(track, currentTimeMs())
                }
            }
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

    fun defaultRoute(sessionSnapshot: PlexSession? = session.value): PhoebeRoute =
        defaultPhoebeRoute(sessionSnapshot, mediaSources.value)

    private fun requestNavigation(command: PhoebeNavigationCommand) {
        if (!mutableNavigationCommands.tryEmit(command)) {
            scope.launch { mutableNavigationCommands.emit(command) }
        }
    }

    private fun trackBackgroundSyncJob(job: Job): Job {
        backgroundSyncJobs += job
        job.invokeOnCompletion {
            backgroundSyncJobs -= job
        }
        return job
    }

    private fun CatalogSnapshot.hasBrowseableContent(): Boolean =
        artists.isNotEmpty() ||
            albums.isNotEmpty() ||
            playlists.isNotEmpty() ||
            tracksByParent.values.any { it.isNotEmpty() }

    fun startPlexSignIn() = scope.launchBusy {
        val newPin = dependencies.sessionRepository.createPin()
        mutablePin.value = newPin
        openExternalUrl(newPin.authUrl)
        mutableMessage.value = "Plex opened in your browser. Approve code ${newPin.code}, then finish sign-in."
    }

    fun finishPlexSignIn() = scope.launch {
        val currentPin = mutablePin.value ?: return@launch
        mutableBusy.value = true
        mutableMessage.value = "Signing in with Plex…"
        val servers = runCatching {
            dependencies.sessionRepository.completePinAndListServers(currentPin)
        }.getOrNull()
        mutableBusy.value = false
        if (servers == null) {
            mutableMessage.value = "That Plex code is not approved yet."
            return@launch
        }
        requestNavigation(PhoebeNavigationCommand.ReplaceRoot(PhoebeRoute.ServerPicker))
        mutableServers.value = servers
        mutableLibraries.value = emptyList()
        mutableLibrariesLoading.value = false
        mutableMessage.value = "Signed in. Pick the Plex server that hosts your music."
    }

    fun signInJellyfin(serverUrl: String, username: String, password: String) = scope.launch {
        mutableBusy.value = true
        mutableMessage.value = "Signing in with Jellyfin…"
        val server = runCatching {
            dependencies.sessionRepository.signInJellyfin(serverUrl, username, password)
        }.onFailure { error ->
            mutableMessage.value = error.message ?: "Couldn't sign in to Jellyfin."
        }.getOrNull()
        mutableBusy.value = false
        if (server == null) return@launch
        mutableServers.value = listOf(server)
        mutableLibraries.value = emptyList()
        mutableLibrariesLoading.value = true
        requestNavigation(PhoebeNavigationCommand.ReplaceRoot(PhoebeRoute.LibraryPicker))
        runCatching {
            mutableLibraries.value = dependencies.sessionRepository.libraries(server)
        }.onFailure { error ->
            mutableMessage.value = error.message ?: "Couldn't load Jellyfin libraries."
        }
        mutableLibrariesLoading.value = false
        mutableMessage.value = "Signed in. Pick the Jellyfin music library to browse."
    }

    fun signInProvider(
        type: MediaProviderType,
        serverUrl: String,
        username: String,
        password: String,
        syncMode: JellyfinSyncMode? = null,
    ) = scope.launch {
        mutableBusy.value = true
        mutableMessage.value = "Signing in with ${type.displayName}…"
        val server = runCatching {
            dependencies.sessionRepository.signInProvider(type, serverUrl, username, password)
        }.onFailure { error ->
            mutableMessage.value = error.message ?: "Couldn't sign in to ${type.displayName}."
        }.getOrNull()
        mutableBusy.value = false
        if (server == null) return@launch
        mutableServers.value = listOf(server)
        mutableLibraries.value = emptyList()
        if (type.skipsLibraryPicker()) {
            mutableLibrariesLoading.value = false
            runCatching {
                dependencies.sessionRepository.selectLibrary(type.defaultLibrarySelection(), syncMode ?: JellyfinSyncMode.Quick)
                requestNavigation(PhoebeNavigationCommand.ReplaceRoot(PhoebeRoute.Browse()))
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
        requestNavigation(PhoebeNavigationCommand.ReplaceRoot(PhoebeRoute.LibraryPicker))
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
                requestNavigation(PhoebeNavigationCommand.ReplaceRoot(PhoebeRoute.Browse()))
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
        mutableBusy.value = true
        mutableMessage.value = "Starting Jellyfin Quick Connect…"
        val quickConnect = runCatching {
            dependencies.sessionRepository.startJellyfinQuickConnect(serverUrl)
        }.onFailure { error ->
            mutableMessage.value = error.message ?: "Couldn't start Jellyfin Quick Connect."
        }.getOrNull()
        mutableBusy.value = false
        if (quickConnect == null) return@launch
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
        mutableBusy.value = true
        mutableMessage.value = "Finishing Jellyfin Quick Connect…"
        val server = runCatching {
            dependencies.sessionRepository.completeJellyfinQuickConnect(serverUrl, quickConnect.Secret)
        }.onFailure { error ->
            mutableMessage.value = error.message ?: "That Jellyfin Quick Connect code is not approved yet."
        }.getOrNull()
        mutableBusy.value = false
        if (server == null) return@launch
        mutableJellyfinQuickConnect.value = null
        mutableServers.value = listOf(server)
        mutableLibraries.value = emptyList()
        mutableLibrariesLoading.value = true
        requestNavigation(PhoebeNavigationCommand.ReplaceRoot(PhoebeRoute.LibraryPicker))
        runCatching {
            mutableLibraries.value = dependencies.sessionRepository.libraries(server)
        }.onFailure { error ->
            mutableMessage.value = error.message ?: "Couldn't load Jellyfin libraries."
        }
        mutableLibrariesLoading.value = false
        mutableMessage.value = "Signed in. Pick the Jellyfin music library to browse."
    }

    fun loadServers() = scope.launch {
        mutableLibrariesLoading.value = false
        requestNavigation(PhoebeNavigationCommand.ReplaceRoot(PhoebeRoute.ServerPicker))
        refreshServers()
    }

    fun returnToServerPicker() = scope.launch {
        mutableLibrariesLoading.value = false
        requestNavigation(PhoebeNavigationCommand.ReplaceRoot(PhoebeRoute.ServerPicker))
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
        cancelRemotePlayHistorySync()
        mutableLibraries.value = emptyList()
        mutableLibrariesLoading.value = true
        val resolved = runCatching {
            dependencies.sessionRepository.selectServer(server, refreshConnections = false)
        }.onFailure {
            mutableLibrariesLoading.value = false
            mutableMessage.value = it.message ?: "Couldn't select ${session.value.providerLabel()} server."
        }.getOrNull() ?: return@launch
        requestNavigation(PhoebeNavigationCommand.ReplaceRoot(PhoebeRoute.LibraryPicker))
        runCatching {
            mutableLibraries.value = dependencies.sessionRepository.libraries(resolved)
        }.onFailure {
            mutableMessage.value = it.message ?: "Couldn't load ${session.value.providerLabel()} libraries."
        }
        mutableLibrariesLoading.value = false
    }

    fun selectLibrary(library: MusicLibrary, jellyfinSyncMode: JellyfinSyncMode? = null) = scope.launch {
        cancelRemotePlayHistorySync()
        mutableBusy.value = true
        if (session.value == null) {
            mutableMessage.value = "Session expired. Sign in again."
            mutableBusy.value = false
            return@launch
        }
        runCatching {
            dependencies.sessionRepository.selectLibrary(library, jellyfinSyncMode)
            requestNavigation(PhoebeNavigationCommand.ReplaceRoot(PhoebeRoute.Browse()))
            mutableMessage.value = if (session.value.isJellyfin() && (jellyfinSyncMode ?: session.value?.jellyfinSyncMode) == JellyfinSyncMode.Full) {
                "Starting full Jellyfin sync…"
            } else {
                "Loading library…"
            }
        }.onFailure { mutableMessage.value = it.message ?: "Something went sideways." }
        mutableBusy.value = false

        runCatching {
            refreshCatalogSuspended(catalogMessage = "Library ready.")
        }.onFailure { error ->
            if (error !is CancellationException) {
                mutableMessage.value = error.message ?: "Something went sideways."
            }
        }
    }

    /**
     * Suspends until the catalog is rebuilt from the current session and media sources.
     * Prefer this from [LaunchedEffect] so in-flight work is cancelled when dependencies change,
     * avoiding stale empty Plex refreshes overwriting a newer library load.
     */
    suspend fun refreshCatalogSuspended(catalogMessage: String? = "Library refreshed.") {
        cancelRemotePlayHistorySync()
        val currentJob = currentCoroutineContext()[Job]
        catalogRefreshJob?.takeIf { it != currentJob }?.cancel()
        catalogRefreshJob = currentJob
        try {
            withContext(Dispatchers.Default) {
                dependencies.catalogRepository.refreshAggregated(session.value)
                ensureLikedSongsPlaylistIfPossible()
            }
            warmPlaylistTracksInBackground()
            syncRemotePlayHistoryInBackground()
            cacheDownloadedArtworkInBackground()
            if (catalogMessage != null) mutableMessage.value = catalogMessage
        } catch (error: CancellationException) {
            PhoebeLog.d("AppState") { "catalog refresh cancelled" }
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
        if (!session.value.supportsRemotePlaylists()) return
        trackBackgroundSyncJob(scope.launch {
            runCatching {
                dependencies.catalogRepository.warmPlaylistTracks(session.value)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                PhoebeLog.d("AppState") { "playlist warm failed: ${error.message}" }
            }
        })
    }

    private fun cacheDownloadedArtworkInBackground() {
        trackBackgroundSyncJob(scope.launch {
            runCatching {
                dependencies.catalogRepository.cacheDownloadedArtwork()
            }.onSuccess { cached ->
                if (cached > 0) {
                    PhoebeLog.d("AppState") { "cached artwork for $cached downloaded tracks" }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                PhoebeLog.d("AppState") { "downloaded artwork cache failed: ${error.message}" }
            }
        })
    }

    private suspend fun ensureLikedSongsPlaylistIfPossible(): Playlist? {
        if (!session.value.supportsRemotePlaylists()) return null
        return runCatching {
            dependencies.catalogRepository.ensureLocalLikedSongsPlaylist()
        }.onFailure { error ->
            PhoebeLog.d("AppState") { "Liked Songs setup failed: ${error.message}" }
        }.getOrNull()
    }

    fun openLikedSongsPlaylist() = scope.launch {
        val playlist = ensureLikedSongsPlaylistIfPossible()
        if (playlist == null) {
            mutableMessage.value = "Couldn't create Liked Songs yet."
            return@launch
        }
        requestNavigation(
            PhoebeNavigationCommand.ReplaceAll(
                listOf(PhoebeRoute.Browse(), PhoebeRoute.PlaylistDetail(playlist.id)),
            ),
        )
        syncLikedSongsInBackground()
    }

    fun refreshCatalog() = scope.launch {
        refreshCatalogSuspended()
    }

    fun loadJellyfinLibraryPage(kind: JellyfinLibraryPageKind, pageIndex: Int) {
        trackBackgroundSyncJob(scope.launch {
            runCatching {
                dependencies.catalogRepository.loadJellyfinLibraryPage(session.value, kind, pageIndex)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                mutableMessage.value = error.message ?: "Couldn't load that Jellyfin page."
            }
        })
    }

    fun refreshPlexPlayHistory() = startRemotePlayHistorySync(showMessage = true)

    private fun syncRemotePlayHistoryInBackground() = startRemotePlayHistorySync(showMessage = false)

    private fun startRemotePlayHistorySync(showMessage: Boolean) {
        val currentSession = session.value
        if (!currentSession.isPlex() && !currentSession.isEmbyFamily()) {
            if (showMessage) mutableMessage.value = "${currentSession.providerLabel()} play history sync is handled from playback progress."
            return
        }
        playHistorySyncJob?.cancel()
        val job = scope.launch {
            syncRemotePlayHistory(showMessage = showMessage)
        }
        playHistorySyncJob = job
        job.invokeOnCompletion {
            if (playHistorySyncJob == job) {
                playHistorySyncJob = null
            }
        }
    }

    private fun cancelRemotePlayHistorySync() {
        playHistorySyncJob?.cancel()
        playHistorySyncJob = null
    }

    private fun cancelCatalogRefresh() {
        catalogRefreshJob?.cancel()
        catalogRefreshJob = null
    }

    private fun cancelActiveSyncWork(): List<Job> {
        val jobs = (listOfNotNull(catalogRefreshJob, playHistorySyncJob) + backgroundSyncJobs.toList()).distinct()
        catalogRefreshJob = null
        playHistorySyncJob = null
        backgroundSyncJobs.clear()
        jobs.forEach { it.cancel() }
        return jobs
    }

    private suspend fun syncRemotePlayHistory(showMessage: Boolean): Any? {
        return runCatching {
            val currentSession = session.value
            if (currentSession.isPlex()) {
                runCatching {
                    dependencies.catalogRepository.warmPlexHistoryTracks(currentSession)
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    PhoebeLog.d("AppState") { "Plex history track warm failed: ${error.message}" }
                }
                dependencies.plexPlayHistorySyncer.sync(currentSession, catalog.value)
            } else {
                dependencies.jellyfinPlayHistorySyncer.sync(currentSession, catalog.value)
            }
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
                    else -> "Play history is up to date."
                }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            PhoebeLog.d("AppState") { "play history sync failed: ${error.message}" }
            if (showMessage) mutableMessage.value = error.message ?: "Couldn't sync play history."
        }.getOrNull()
    }

    fun setTab(tab: LibraryTab) {
        mutableTab.value = tab
        requestNavigation(PhoebeNavigationCommand.ReplaceRoot(defaultRoute()))
    }

    fun open(route: PhoebeRoute) {
        requestNavigation(PhoebeNavigationCommand.Open(route))
    }

    fun prefetchHomeArtistStats(artist: Artist) {
        if (!session.value.canUsePlexBackgroundFetches()) return
        scope.launch {
            runCatching {
                dependencies.catalogRepository.ensureTracksForArtistAlbums(session.value, artist.title)
            }
        }
    }

    fun prefetchHomeAlbumStats(album: Album) {
        if (!session.value.canUsePlexBackgroundFetches()) return
        scope.launch {
            runCatching {
                dependencies.catalogRepository.tracksForAlbum(session.value, album)
            }
        }
    }

    fun loadArtistDetail(artist: Artist) {
        scope.launch {
            runCatching {
                dependencies.catalogRepository.ensureTracksForArtistAlbums(session.value, artist.title)
            }
        }
    }

    fun loadAlbumDetail(album: Album) {
        scope.launch {
            runCatching {
                dependencies.catalogRepository.tracksForAlbum(session.value, album)
            }.onFailure {
                mutableMessage.value = it.message ?: "Couldn't load album tracks."
            }
        }
    }

    fun loadPlaylistDetail(playlist: Playlist) {
        scope.launch {
            runCatching {
                dependencies.catalogRepository.tracksForPlaylist(session.value, playlist)
            }.onFailure {
                mutableMessage.value = it.message ?: "Couldn't load playlist tracks."
            }
        }
    }

    fun loadCollectionValues(entry: CollectionEntry) {
        scope.launch {
            if (!session.value.supportsCollectionEntry(entry)) return@launch
            runCatching {
                dependencies.catalogRepository.ensureCollectionValues(session.value, entry)
            }.onFailure {
                mutableMessage.value = it.message ?: "Couldn't load collections."
            }
        }
    }

    fun loadCollectionItems(entry: CollectionEntry, value: String) {
        scope.launch {
            if (!session.value.supportsCollectionEntry(entry)) return@launch
            runCatching {
                dependencies.catalogRepository.ensureCollectionItems(session.value, entry, value)
            }.onFailure {
                mutableMessage.value = it.message ?: "Couldn't load collection."
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
        playTracks(firstTracks, 0)
        open(PhoebeRoute.Player)
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
            mutableMessage.value = "Starting ${station.title}..."
            val tracks = runCatching {
                dependencies.catalogRepository.playRadioStation(session.value, station)
            }.getOrElse { error ->
                val notice = error.message ?: "Couldn't start ${station.title}."
                mutableMessage.value = notice
                return@launch
            }
            if (tracks.isEmpty()) {
                mutableMessage.value = "No songs found for ${station.title}."
                return@launch
            }
            playTracks(tracks, 0)
            open(PhoebeRoute.Player)
            mutableMessage.value = "Playing ${station.title}."
        } finally {
            mutableRadioStartingIds.update { it - radioId }
        }
    }

    fun playArtistRadio(artist: Artist) = scope.launch {
        if (!artist.id.startsWith("plex:") && !artist.id.startsWith("jellyfin:")) {
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
            playTracks(tracks, 0)
            open(PhoebeRoute.Player)
            mutableMessage.value = "Playing ${artist.title} Radio."
        } finally {
            mutableRadioStartingIds.update { it - artist.id }
        }
    }

    fun probeArtistRadio(artist: Artist) = scope.launch {
        if (!artist.id.startsWith("plex:") && !artist.id.startsWith("jellyfin:")) {
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
        playTracks(tracks.shuffled(), 0)
        dependencies.audioPlayer.setShuffle(true)
        open(PhoebeRoute.Player)
        mutableMessage.value = "Shuffling ${playlist.title}."
    }

    fun clearDecadeMixNotice() {
        mutableDecadeMixNotice.value = null
    }

    fun popDetail() {
        requestNavigation(PhoebeNavigationCommand.Pop)
    }

    fun handleBack() {
        requestNavigation(PhoebeNavigationCommand.Pop)
    }

    fun dismissDetailsToHome() {
        requestNavigation(PhoebeNavigationCommand.ReplaceRoot(defaultRoute()))
    }

    fun backHome() {
        dismissDetailsToHome()
    }

    fun playTracks(tracks: List<Track>, index: Int = 0) {
        val requestGeneration = ++playRequestGeneration
        collectionMixGeneration++
        val track = tracks.getOrNull(index)
        if (dependencies.castController.state.value.isConnected) {
            mutableMusicAssistantRemotePlayback.value = null
            if (!tracks.isChromecastPlayableQueue()) {
                mutableMessage.value = "Chromecast can play Plex streaming songs only."
                return
            }
            if (dependencies.audioPlayer.state.value.isPlaying) {
                dependencies.audioPlayer.togglePlayPause()
            }
            dependencies.castController.loadQueue(tracks, index)
            return
        }
        if (session.value.isMusicAssistant() && track?.localUri.isNullOrBlank() && track?.streamUrl.isNullOrBlank()) {
            val musicAssistantTrack = track ?: return
            scope.launch {
                mutableMessage.value = "Starting ${musicAssistantTrack.title} in Music Assistant..."
                runCatching {
                    dependencies.providerRegistry.adapterFor(session.value)?.playRemote(session.value!!, tracks, index)
                }.onSuccess { target ->
                    if (target.isNullOrBlank()) {
                        mutableMessage.value = "Couldn't find a Music Assistant player for ${musicAssistantTrack.title}."
                        return@onSuccess
                    }
                    mutableMusicAssistantRemotePlayback.value = MusicAssistantRemotePlayback(tracks, index, target)
                    mutableMessage.value = "Playing ${musicAssistantTrack.title} on Music Assistant: $target."
                }.onFailure { error ->
                    mutableMessage.value = error.message ?: "Couldn't start Music Assistant playback."
                }
            }
            return
        }
        mutableMusicAssistantRemotePlayback.value = null
        if (track?.localUri.isNullOrBlank()) {
            dependencies.audioPlayer.play(tracks, index)
            collectionMixFromDetailStack()?.let { mix ->
                scheduleCollectionMix(mix, tracks.map { it.id }.toSet())
            }
            return
        }

        scope.launch {
            val ok = runCatching {
                LocalLibraryIO.fileExists(track.localUri)
            }.getOrDefault(false)
            if (requestGeneration != playRequestGeneration) return@launch
            if (ok) {
                dependencies.audioPlayer.play(tracks, index)
            } else {
                mutableMessage.value = "Could not open file (missing or inaccessible): ${track.title}"
            }
        }
    }

    private fun scheduleCollectionMix(mix: CollectionMix, queuedTrackIds: Set<String>) {
        if (!session.value.canUsePlexBackgroundFetches()) return
        val mixGeneration = collectionMixGeneration
        scope.launch {
            appendCollectionMix(mix, queuedTrackIds, mixGeneration)
        }
    }

    fun setCollectionMixContext(entry: CollectionEntry?, value: String?) {
        activeCollectionMix = if (
            entry != null &&
            value != null &&
            session.value.supportsCollectionEntry(entry) &&
            (entry.facet == CollectionFacet.Mood || entry.facet == CollectionFacet.Style) &&
            value.isNotBlank()
        ) {
            CollectionMix(entry.facet, value.trim())
        } else {
            null
        }
    }

    private fun collectionMixFromDetailStack(): CollectionMix? {
        val mix = activeCollectionMix ?: return null
        if (!session.value.supportsCollectionEntry(CollectionEntry(CollectionTarget.Albums, mix.facet)) &&
            !session.value.supportsCollectionEntry(CollectionEntry(CollectionTarget.Artists, mix.facet))
        ) {
            return null
        }
        val value = mix.value.trim()
        if (value.isBlank()) return null
        return mix.copy(value = value)
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
        } else if (dependencies.castController.state.value.isConnected) {
            dependencies.castController.togglePlayPause()
        } else {
            dependencies.audioPlayer.togglePlayPause()
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
        if (mutableMusicAssistantRemotePlayback.value != null) {
            mutableMusicAssistantRemotePlayback.value = null
        } else if (dependencies.castController.state.value.isConnected) {
            dependencies.castController.disconnect()
        } else {
            dependencies.audioPlayer.clearQueue()
        }
    }
    fun addToUpNext(track: Track) = dependencies.audioPlayer.addToUpNext(track)
    fun appendToQueue(tracks: List<Track>) = dependencies.audioPlayer.appendToQueue(tracks)
    fun moveUpNext(fromIndex: Int, toIndex: Int) = dependencies.audioPlayer.moveUpNext(fromIndex, toIndex)
    fun removeUpNext(index: Int) = dependencies.audioPlayer.removeUpNext(index)
    fun playUpNext(index: Int) {
        val current = player.value
        val target = current.currentIndex + 1 + index
        if (target in current.queue.indices) {
            playTracks(current.queue, target)
        }
    }
    fun next() {
        val remote = mutableMusicAssistantRemotePlayback.value
        if (remote != null) {
            playTracks(remote.tracks, (remote.index + 1).coerceIn(0, remote.tracks.lastIndex))
        } else if (dependencies.castController.state.value.isConnected) {
            dependencies.castController.next()
        } else {
            dependencies.audioPlayer.next()
        }
    }
    fun previous() {
        val remote = mutableMusicAssistantRemotePlayback.value
        if (remote != null) {
            playTracks(remote.tracks, (remote.index - 1).coerceIn(0, remote.tracks.lastIndex))
        } else if (dependencies.castController.state.value.isConnected) {
            dependencies.castController.previous()
        } else {
            dependencies.audioPlayer.previous()
        }
    }
    fun skipQueueBy(delta: Int) {
        if (delta == 0) return
        val current = player.value
        if (current.currentIndex < 0 || current.queue.isEmpty()) return
        val target = (current.currentIndex + delta).coerceIn(0, current.queue.lastIndex)
        if (target == current.currentIndex) return
        playTracks(current.queue, target)
    }
    fun seekTo(positionMs: Long) {
        if (dependencies.castController.state.value.isConnected) {
            dependencies.castController.seekTo(positionMs)
        } else {
            dependencies.audioPlayer.seekTo(positionMs)
        }
    }
    suspend fun loadLyrics(track: Track, forceRefresh: Boolean = false): LyricsLoadState =
        dependencies.lyricsRepository.lyricsFor(track, forceRefresh)

    fun toggleShuffle() = dependencies.audioPlayer.setShuffle(!player.value.shuffle)
    fun cycleRepeat() {
        val next = when (player.value.repeat) {
            RepeatMode.Off -> RepeatMode.One
            RepeatMode.One -> RepeatMode.All
            RepeatMode.All -> RepeatMode.Off
        }
        dependencies.audioPlayer.setRepeat(next)
    }
    fun setVolume(volume: Float) {
        val controller = dependencies.systemVolume
        if (controller.controlsPlayerOutput) {
            controller.setVolume(volume)
        } else {
            dependencies.audioPlayer.setVolume(volume)
        }
    }

    fun showCastPicker() {
        if (dependencies.castController.state.value.isAvailable) {
            dependencies.castController.showDevicePicker()
        } else {
            mutableMessage.value = dependencies.castController.state.value.message
                ?: "Chromecast is available on Android, iOS, and Chrome web."
        }
    }

    fun setLibrarySortBy(sortBy: LibrarySortBy) = scope.launch {
        dependencies.libraryUiRepository.setSortBy(sortBy)
    }

    fun setLibrarySortAscending(ascending: Boolean) = scope.launch {
        dependencies.libraryUiRepository.setAscending(ascending)
    }

    fun setLibraryColumns(columns: LibraryColumnVisibility) {
        dependencies.libraryUiRepository.applyColumns(columns)
        scope.launch(Dispatchers.Default) {
            dependencies.libraryUiRepository.persistCurrentToDisk()
        }
    }

    fun setHomeSections(sections: List<HomeSection>) = scope.launch {
        dependencies.libraryUiRepository.setHomeSections(sections)
    }

    fun setPersonalMixPreferences(preferences: PersonalMixPreferences) = scope.launch {
        dependencies.libraryUiRepository.setPersonalMix(preferences)
    }

    fun setCrossfadeSeconds(seconds: Int) = scope.launch {
        dependencies.appSettingsRepository.setCrossfadeSeconds(seconds)
    }

    fun setScanLibraryOnLaunch(enabled: Boolean) = scope.launch {
        dependencies.appSettingsRepository.setScanLibraryOnLaunch(enabled)
    }

    fun setNotifyWhenDownloadFinishes(enabled: Boolean) = scope.launch {
        dependencies.appSettingsRepository.setNotifyWhenDownloadFinishes(enabled)
    }

    fun download(track: Track) = launchDownload {
        val result = dependencies.catalogRepository.download(track)
        mutableMessage.value = downloadMessage(result, singular = "song", plural = "songs")
        result
    }

    fun download(album: Album) = launchDownload {
        mutableMessage.value = "Downloading ${album.title}…"
        val result = dependencies.catalogRepository.downloadAlbum(session.value, album)
        mutableMessage.value = downloadMessage(result, singular = "song from ${album.title}", plural = "songs from ${album.title}")
        result
    }

    fun download(artist: Artist) = launchDownload {
        mutableMessage.value = "Downloading ${artist.title}…"
        val result = dependencies.catalogRepository.downloadArtist(session.value, artist)
        mutableMessage.value = downloadMessage(result, singular = "song by ${artist.title}", plural = "songs by ${artist.title}")
        result
    }

    fun download(playlist: Playlist) = launchDownload {
        mutableMessage.value = "Downloading ${playlist.title}…"
        val result = dependencies.catalogRepository.downloadPlaylist(session.value, playlist)
        mutableMessage.value = downloadMessage(result, singular = "song from ${playlist.title}", plural = "songs from ${playlist.title}")
        result
    }

    private fun launchDownload(block: suspend () -> DownloadBatchResult): Job {
        lateinit var downloadJob: Job
        downloadJob = scope.launch {
            try {
                val result = block()
                notifyDownloadFinishedIfNeeded(result)
            } finally {
                activeDownloadJobs.remove(downloadJob)
            }
        }
        activeDownloadJobs += downloadJob
        return downloadJob
    }

    private suspend fun notifyDownloadFinishedIfNeeded(result: DownloadBatchResult) {
        if (!appSettings.value.notifyWhenDownloadFinishes || result.completed <= 0) return
        val title = "Download complete"
        val body = if (result.completed == 1) {
            "Downloaded 1 song."
        } else {
            "Downloaded ${result.completed} songs."
        }
        dependencies.downloadNotifier.notifyDownloadFinished(title, body)
    }

    fun setDownloadDirectory(uri: String?) = scope.launch {
        dependencies.platformStorage.writeDownloadDirectory(uri)
        mutableDownloadDirectory.value = dependencies.platformStorage.readDownloadDirectory()
        mutableMessage.value = if (mutableDownloadDirectory.value == null) {
            "Downloads will use ${dependencies.platformStorage.defaultDownloadDirectoryLabel()}."
        } else {
            "Download location updated."
        }
    }

    fun resetDownloadDirectory() = setDownloadDirectory(null)

    fun deleteAllDownloads() = scope.launch {
        val deleted = dependencies.catalogRepository.deleteAllDownloads()
        mutableMessage.value = if (deleted == 0) {
            "No downloads to delete."
        } else {
            "Deleted $deleted downloaded ${if (deleted == 1) "song" else "songs"}."
        }
    }

    fun deleteDownloads(tracks: List<Track>) = scope.launch {
        val deleted = dependencies.catalogRepository.deleteDownloadsForTracks(tracks)
        mutableMessage.value = if (deleted == 0) {
            "No downloaded songs to delete."
        } else {
            "Deleted $deleted downloaded ${if (deleted == 1) "song" else "songs"}."
        }
    }

    fun cancelDownloads(tracks: List<Track>) = scope.launch {
        val jobs = activeDownloadJobs.toList()
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
        val deleted = dependencies.catalogRepository.deleteDownloadsForTracks(tracks)
        mutableMessage.value = if (deleted == 0) {
            "Cancelled download."
        } else {
            "Cancelled download and deleted $deleted downloaded ${if (deleted == 1) "song" else "songs"}."
        }
    }

    private fun downloadMessage(result: DownloadBatchResult, singular: String, plural: String): String =
        when {
            result.total == 0 -> "Nothing to download yet."
            result.failed == 0 && result.completed == result.total -> {
                val noun = if (result.completed == 1) singular else plural
                "Downloaded ${result.completed} $noun."
            }
            result.completed > 0 -> "Downloaded ${result.completed} of ${result.total} songs. ${result.failed} failed."
            else -> "Couldn't download those songs."
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
        val allLocalEligible = initialTracks.isNotEmpty() && initialTracks.all { it.canAddToLocalPlaylist() }
        val allPlexEligible = initialTracks.isNotEmpty() && initialTracks.all { it.canAddToPlexPlaylist() }
        val hasLocalOnlyTracks = initialTracks.any { it.canAddToLocalPlaylist() && !it.canAddToPlexPlaylist() }
        val hasPlexTracks = initialTracks.any { it.canAddToPlexPlaylist() }
        if (hasLocalOnlyTracks && hasPlexTracks) {
            mutableMessage.value = "Can't mix local files and streaming songs in one playlist."
            return@launch
        }
        val playlist = when {
            allPlexEligible && session.value.supportsRemotePlaylists() -> {
                if (initialTracks.any { !it.canAddToPlexPlaylist() }) {
                    mutableMessage.value = "Only streaming library songs can be added to streaming playlists."
                    return@launch
                }
                dependencies.catalogRepository.createPlaylist(session.value, title, initialTracks)
            }
            allLocalEligible || (initialTracks.isEmpty() && !session.value.supportsRemotePlaylists() && hasEnabledLocalFolders()) -> {
                if (!hasEnabledLocalFolders()) {
                    mutableMessage.value = "Add a local music folder to create playlists."
                    return@launch
                }
                if (initialTracks.any { !it.canAddToLocalPlaylist() }) {
                    mutableMessage.value = "Only local audio files can be added to local playlists."
                    return@launch
                }
                dependencies.catalogRepository.createLocalPlaylist(title, initialTracks)
            }
            initialTracks.isEmpty() && session.value.supportsRemotePlaylists() -> {
                dependencies.catalogRepository.createPlaylist(session.value, title, initialTracks)
            }
            else -> {
                mutableMessage.value = "Sign in to Plex or Jellyfin, or add a local music folder to use playlists."
                return@launch
            }
        }
        if (playlist != null) {
            onCreated?.invoke(playlist)
        } else {
            mutableMessage.value = "Couldn't create playlist '$title'."
        }
    }

    /** Append [track] to [playlist] when the session, playlist type, and track are eligible. */
    fun addToPlaylist(playlist: com.phoebe.app.domain.Playlist, track: Track) = scope.launch {
        if (playlist.isLocalPlaylist()) {
            if (!track.canAddToLocalPlaylist()) {
                mutableMessage.value = "Only local audio files can be added to local playlists."
                return@launch
            }
            dependencies.catalogRepository.addTracksToPlaylist(session.value, playlist, listOf(track))
            return@launch
        }
        if (!session.value.supportsRemotePlaylists()) {
            mutableMessage.value = "Sign in and select a music library to use streaming playlists."
            return@launch
        }
        if (!playlist.isRemoteProviderPlaylist()) {
            mutableMessage.value = "This playlist can't be edited in Phoebe."
            return@launch
        }
        if (!track.canAddToPlexPlaylist()) {
            mutableMessage.value = "Only streaming library songs can be added to streaming playlists."
            return@launch
        }
        PhoebeLog.d("AppState") { "addToPlaylist → playlist='${playlist.title}' (${playlist.id}), track='${track.title}' (${track.id})" }
        dependencies.catalogRepository.addTracksToPlaylist(session.value, playlist, listOf(track))
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
            dependencies.catalogRepository.toggleLikedTrackLocally(track)
        }.getOrElse { error ->
            PhoebeLog.d("AppState") { "toggleLikedTrack failed for '${track.title}': ${error.message}" }
            mutableMessage.value = error.message ?: "Couldn't update Liked Songs."
            return@launch
        }
        mutableMessage.value = if (liked) "Song is in Liked Songs." else "Removed from Liked Songs."
        syncLikedSongsInBackground(track, liked)
    }

    private fun syncLikedSongsInBackground(track: Track? = null, liked: Boolean? = null) {
        trackBackgroundSyncJob(scope.launch {
            runCatching {
                if (track != null && liked != null) {
                    dependencies.catalogRepository.syncLikedTrackChange(session.value, track, liked)
                } else {
                    dependencies.catalogRepository.syncLikedSongsPlaylist(session.value)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                PhoebeLog.d("AppState") { "Liked Songs Plex sync failed: ${error.message}" }
                mutableMessage.value = "Liked Songs updated locally. Plex sync will retry later."
            }
        })
    }

    fun copyPlaylistIntoPlaylist(
        source: com.phoebe.app.domain.Playlist,
        target: com.phoebe.app.domain.Playlist,
    ) = scope.launch {
        if (source.id == target.id) return@launch
        if (!source.id.startsWith("plex:") || !target.id.startsWith("plex:")) {
            mutableMessage.value = "Playlist copying supports Plex playlists only."
            return@launch
        }
        val copied = dependencies.catalogRepository.copyPlexPlaylistIntoPlaylist(session.value, source, target)
        mutableMessage.value = if (copied > 0) {
            "Copied $copied songs to ${target.title}."
        } else {
            "No new songs to copy."
        }
    }

    fun exportLocalPlaylist(
        playlist: com.phoebe.app.domain.Playlist,
        format: PlaylistExportFormat,
    ) = scope.launch {
        if (!playlist.isLocalPlaylist()) {
            mutableMessage.value = "Only local playlists can be exported."
            return@launch
        }
        val tracks = dependencies.catalogRepository.tracksForPlaylist(session.value, playlist)
        if (tracks.isEmpty()) {
            mutableMessage.value = "Nothing to export — playlist is empty."
            return@launch
        }
        val content = PlaylistExporter.export(tracks, format)
        val fileName = PlaylistExporter.suggestedFileName(playlist.title, format)
        runCatching {
            dependencies.platformStorage.writeText("exports/$fileName", content)
        }.onSuccess {
            mutableMessage.value = "Exported ${tracks.size} songs to $fileName."
        }.onFailure {
            mutableMessage.value = it.message ?: "Couldn't export playlist."
        }
    }

    private fun hasEnabledLocalFolders(): Boolean =
        mediaSources.value.localFolders.any { it.enabled }

    fun updateTrackMetadata(update: TrackMetadataUpdate) = scope.launch {
        val result = dependencies.catalogRepository.updateTrackMetadata(session.value, update)
        val provider = session.value.providerLabel()
        mutableMessage.value = when {
            !result.savedLocally -> "Couldn't find that song in the library."
            result.plexAttempted && result.plexSynced -> "Metadata saved and synced to $provider."
            result.plexAttempted -> "Metadata saved locally, but $provider sync failed."
            else -> "Metadata saved."
        }
    }

    fun rateTrack(track: Track, rating: Float?) = scope.launch {
        val result = dependencies.catalogRepository.rateTrack(session.value, track, rating)
        mutableMessage.value = ratingMessage(result.savedLocally, result.plexAttempted, result.plexSynced)
    }

    fun rateArtist(artist: Artist, rating: Float?) = scope.launch {
        val result = dependencies.catalogRepository.rateArtist(session.value, artist, rating)
        mutableMessage.value = ratingMessage(result.savedLocally, result.plexAttempted, result.plexSynced)
    }

    fun rateAlbum(album: Album, rating: Float?) = scope.launch {
        val result = dependencies.catalogRepository.rateAlbum(session.value, album, rating)
        mutableMessage.value = ratingMessage(result.savedLocally, result.plexAttempted, result.plexSynced)
    }

    fun ratePlaylist(playlist: Playlist, rating: Float?) = scope.launch {
        val result = dependencies.catalogRepository.ratePlaylist(session.value, playlist, rating)
        mutableMessage.value = ratingMessage(result.savedLocally, result.plexAttempted, result.plexSynced)
    }

    fun toggleFavoriteArtist(artist: Artist) = scope.launch {
        mutableMessage.value = favoriteMessage(
            label = "Artist",
            result = dependencies.catalogRepository.toggleFavoriteArtist(session.value, artist),
            plexUnavailableMessage = null,
        )
    }

    fun toggleFavoriteAlbum(album: Album) = scope.launch {
        mutableMessage.value = favoriteMessage(
            label = "Album",
            result = dependencies.catalogRepository.toggleFavoriteAlbum(session.value, album),
            plexUnavailableMessage = null,
        )
    }

    fun toggleFavoritePlaylist(playlist: Playlist) = scope.launch {
        mutableMessage.value = favoriteMessage(
            label = "Playlist",
            result = dependencies.catalogRepository.toggleFavoritePlaylist(session.value, playlist),
            plexUnavailableMessage = null,
        )
    }

    fun exportFavoritePlaylists() = scope.launch {
        val export = dependencies.catalogRepository.favoritePlaylistsExport()
        if (export.playlists.isEmpty()) {
            mutableMessage.value = "No favorite playlists to export."
            return@launch
        }
        runCatching {
            dependencies.platformStorage.writeText(
                FavoritePlaylistsExportPath,
                PlexClient.PlexJson.encodeToString(FavoritePlaylistsExport.serializer(), export),
            )
        }.onSuccess {
            mutableMessage.value = "Exported ${export.playlists.size} favorite playlists."
        }.onFailure { error ->
            mutableMessage.value = error.message ?: "Couldn't export favorite playlists."
        }
    }

    fun importFavoritePlaylists() = scope.launch {
        val content = dependencies.platformStorage.readText(FavoritePlaylistsExportPath)
        if (content.isNullOrBlank()) {
            mutableMessage.value = "No favorite playlist export found."
            return@launch
        }
        runCatching {
            val export = PlexClient.PlexJson.decodeFromString(FavoritePlaylistsExport.serializer(), content)
            dependencies.catalogRepository.importFavoritePlaylists(export)
        }.onSuccess { imported ->
            mutableMessage.value = if (imported > 0) {
                "Imported $imported favorite playlists."
            } else {
                "No matching playlists found to import."
            }
        }.onFailure { error ->
            mutableMessage.value = error.message ?: "Couldn't import favorite playlists."
        }
    }

    private fun favoriteMessage(
        label: String,
        result: FavoriteSyncResult,
        plexUnavailableMessage: String?,
    ): String {
        val provider = session.value.providerLabel()
        return when (result.favorite) {
            null -> "Couldn't find that item in the library."
            true -> when {
                result.plexAttempted && result.plexSynced -> "$label added to favorites and synced to $provider."
                result.plexAttempted -> "$label added to favorites, but $provider sync failed."
                plexUnavailableMessage != null -> "$label added to favorites. $plexUnavailableMessage"
                else -> "$label added to favorites."
            }
            false -> when {
                result.plexAttempted && result.plexSynced -> "$label removed from favorites and synced to $provider."
                result.plexAttempted -> "$label removed from favorites, but $provider sync failed."
                plexUnavailableMessage != null -> "$label removed from favorites. $plexUnavailableMessage"
                else -> "$label removed from favorites."
            }
        }
    }

    private fun ratingMessage(savedLocally: Boolean, plexAttempted: Boolean, plexSynced: Boolean): String =
        when {
            !savedLocally -> "Couldn't find that item in the library."
            plexAttempted && plexSynced -> "Rating saved and synced to ${session.value.providerLabel()}."
            plexAttempted -> "Rating saved locally, but ${session.value.providerLabel()} sync failed."
            session.value.supportsRemoteRatings() -> "Rating saved."
            else -> "Rating saved locally."
        }

    fun addLocalFolderFromUri(rootUri: String?) = scope.launch {
        if (rootUri.isNullOrBlank()) return@launch
        val label = rootUri.trimEnd('/').substringAfterLast('/').substringBefore('?', "Local").ifBlank { "Local" }
        dependencies.mediaSourcesRepository.addLocalFolder(rootUri, label)
        refreshCatalogSuspended(catalogMessage = null)
        mutableMessage.value = "Added local music folder."
        if (defaultRoute() is PhoebeRoute.Browse) {
            requestNavigation(PhoebeNavigationCommand.ReplaceRoot(defaultRoute()))
        }
    }

    fun removeLocalFolder(id: String) = scope.launch {
        dependencies.mediaSourcesRepository.removeLocalFolder(id)
        refreshCatalogSuspended(catalogMessage = null)
        mutableMessage.value = "Removed local folder."
        if (defaultRoute() == PhoebeRoute.SignIn) {
            requestNavigation(PhoebeNavigationCommand.ReplaceRoot(PhoebeRoute.SignIn))
        }
    }

    fun setLocalFolderEnabled(id: String, enabled: Boolean) = scope.launch {
        dependencies.mediaSourcesRepository.setLocalFolderEnabled(id, enabled)
        refreshCatalogSuspended(catalogMessage = null)
        if (defaultRoute() == PhoebeRoute.SignIn) {
            requestNavigation(PhoebeNavigationCommand.ReplaceRoot(PhoebeRoute.SignIn))
        }
    }

    fun signOut() {
        val syncJobs = cancelActiveSyncWork()
        mutableMusicAssistantRemotePlayback.value = null
        dependencies.castController.disconnect()
        dependencies.audioPlayer.clearQueue()
        mutableBusy.value = true
        mutablePin.value = null
        mutableLibraries.value = emptyList()
        mutableLibrariesLoading.value = false
        requestNavigation(PhoebeNavigationCommand.ReplaceRoot(PhoebeRoute.SignIn))
        mutableMessage.value = "Signing out…"
        scope.launch {
            try {
                syncJobs.joinAll()
                dependencies.sessionRepository.signOut()
                dependencies.deleteDatabaseDataForSignOut()
                mutableMessage.value = "Signed out."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableMessage.value = error.message ?: "Something went sideways."
            } finally {
                mutableBusy.value = false
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
}

private fun PlexSession?.canUsePlexBackgroundFetches(): Boolean {
    val server = this?.selectedServer ?: return false
    return server.connectionUris.isNotEmpty() ||
        server.advertisedConnectionUris.isNotEmpty() ||
        server.localConnectionUris.isNotEmpty()
}

private const val FavoritePlaylistsExportPath = "exports/favorite-playlists.json"
