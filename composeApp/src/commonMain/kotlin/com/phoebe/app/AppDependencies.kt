package com.phoebe.app

import com.phoebe.app.data.AppSettingsRepository
import com.phoebe.app.data.ArtistEventsRepository
import com.phoebe.app.data.CatalogItemMutationService
import com.phoebe.app.data.CatalogRepository
import com.phoebe.app.data.CatalogSyncService
import com.phoebe.app.data.DownloadService
import com.phoebe.app.data.ImportExportService
import com.phoebe.app.data.LibraryPreferencesService
import com.phoebe.app.data.LibraryUiRepository
import com.phoebe.app.data.JellyfinPlayHistorySyncer
import com.phoebe.app.data.LastFmAccountRepository
import com.phoebe.app.data.LastFmPlaybackReporter
import com.phoebe.app.data.LastFmService
import com.phoebe.app.data.ListenBrainzAccountRepository
import com.phoebe.app.data.ListenBrainzPlaybackReporter
import com.phoebe.app.data.ListenBrainzService
import com.phoebe.app.data.LyricsRepository
import com.phoebe.app.data.MediaSourcesRepository
import com.phoebe.app.data.MusicBrainzRepository
import com.phoebe.app.data.MusicProviderRegistry
import com.phoebe.app.data.NavidromePlayHistorySyncer
import com.phoebe.app.data.PlayHistoryRepository
import com.phoebe.app.data.PlaylistService
import com.phoebe.app.data.PlexPlayHistorySyncer
import com.phoebe.app.data.PlexPlaybackReporter
import com.phoebe.app.data.RadioNowPlayingRepository
import com.phoebe.app.data.RadioRepository
import com.phoebe.app.data.SearchHistoryRepository
import com.phoebe.app.data.SettingsService
import com.phoebe.app.data.SessionRepository
import com.phoebe.app.data.UserArtifactsRepository
import com.phoebe.app.data.db.DatabaseWriteGate
import com.phoebe.app.data.db.clearAllAppData
import com.phoebe.app.data.db.createPhoebeDatabase
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.di.AppGraph
import com.phoebe.app.di.RouteViewModelFactory
import com.phoebe.app.di.createPhoebeAppGraph
import com.phoebe.app.data.ArtworkAuthHolder
import com.phoebe.app.data.ArtworkOriginHolder
import com.phoebe.app.domain.isPlex
import com.phoebe.app.domain.serverAuthToken
import com.phoebe.app.platform.PhoebeAppDataRevision
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.platform.appDataRevisionStorageKey
import com.phoebe.app.platform.DownloadNotifier
import com.phoebe.app.platform.SecureCredentialStore
import com.phoebe.app.player.AudioPlayer
import com.phoebe.app.player.CastController
import com.phoebe.app.player.PlaybackTransportService
import com.phoebe.app.player.PlaybackRuntimeDependencies
import com.phoebe.app.player.SystemVolumeController
import com.phoebe.app.telemetry.Telemetry
import com.phoebe.app.ui.AppNavigationService
import com.phoebe.app.updates.AppUpdateService

class AppDependencies(
    val appGraph: AppGraph,
    override val database: PhoebeDatabase,
    /** Ungated handle used only by [deleteDatabaseDataForSignOut] to delete under the seal. */
    private val privilegedDatabase: PhoebeDatabase,
    val databaseWriteGate: DatabaseWriteGate,
    override val sessionRepository: SessionRepository,
    val mediaSourcesRepository: MediaSourcesRepository,
    override val catalogRepository: CatalogRepository,
    val catalogItemMutationService: CatalogItemMutationService,
    val catalogSyncService: CatalogSyncService,
    val downloadService: DownloadService,
    val libraryUiRepository: LibraryUiRepository,
    val libraryPreferencesService: LibraryPreferencesService,
    val lyricsRepository: LyricsRepository,
    val musicBrainzRepository: MusicBrainzRepository,
    val playHistoryRepository: PlayHistoryRepository,
    val playlistService: PlaylistService,
    val radioRepository: RadioRepository,
    val radioNowPlayingRepository: RadioNowPlayingRepository,
    val appSettingsRepository: AppSettingsRepository,
    val artistEventsRepository: ArtistEventsRepository,
    val searchHistoryRepository: SearchHistoryRepository,
    val userArtifactsRepository: UserArtifactsRepository,
    val importExportService: ImportExportService,
    val settingsService: SettingsService,
    val providerRegistry: MusicProviderRegistry,
    val plexPlayHistorySyncer: PlexPlayHistorySyncer,
    val jellyfinPlayHistorySyncer: JellyfinPlayHistorySyncer,
    val navidromePlayHistorySyncer: NavidromePlayHistorySyncer,
    val plexPlaybackReporter: PlexPlaybackReporter,
    val listenBrainzAccountRepository: ListenBrainzAccountRepository,
    val listenBrainzPlaybackReporter: ListenBrainzPlaybackReporter,
    val listenBrainzService: ListenBrainzService,
    val lastFmAccountRepository: LastFmAccountRepository,
    val lastFmPlaybackReporter: LastFmPlaybackReporter,
    val lastFmService: LastFmService,
    val secureCredentialStore: SecureCredentialStore,
    val audioPlayer: AudioPlayer,
    val castController: CastController,
    val playbackTransportService: PlaybackTransportService,
    val systemVolume: SystemVolumeController,
    val downloadNotifier: DownloadNotifier,
    val navigationService: AppNavigationService,
    val appUpdateService: AppUpdateService,
    val routeViewModelFactory: RouteViewModelFactory,
    /** File-backed on desktop; NSUserDefaults keys on iOS; etc. Used for lightweight UI prefs. */
    val platformStorage: PlatformStorage,
    val pairedDeviceStore: com.phoebe.app.remote.PairedDeviceStore,
    val playbackRemoteHostBridge: com.phoebe.app.player.PlaybackRemoteHostBridge,
    val remoteHostServer: com.phoebe.app.remote.RemoteHostServer,
    val remoteDiscoveryServer: com.phoebe.app.remote.RemoteDiscoveryServer,
    val remoteDiscoveryClient: com.phoebe.app.remote.RemoteDiscoveryClient,
    val remoteControlClient: com.phoebe.app.remote.RemoteControlClient,
) : PlaybackRuntimeDependencies {
    /**
     * Bind an origin for a process that came up without Compose — Android Auto binds
     * `PlaybackService` directly, so `AppState.wirePlaybackOriginResolver` never runs and
     * [ArtworkOriginHolder] stays empty. `resolveFresh` publishes the holders itself once
     * `/identity` answers (`PlexConnectionResolver.markProbed`).
     */
    override suspend fun ensureLivePlaybackOrigin(): String? {
        ArtworkOriginHolder.liveOrigin?.let { return it }
        val current = sessionRepository.session.value.takeIf { it.isPlex() } ?: return null
        val server = current.selectedServer ?: return null
        val token = current.serverAuthToken() ?: return null
        // Artwork binding needs the token even if the origin race ends up missing.
        ArtworkAuthHolder.update(token)
        val resolver = runCatching { appGraph.plexConnectionResolver }.getOrNull() ?: return null
        return runCatching { resolver.resolveFresh(server, token) }.getOrNull()
    }

    /**
     * Sign out and wipe once when the stored-data revision moves.
     *
     * Runs before [SessionRepository.restore] so the app comes up signed out rather than briefly
     * showing a catalog it is about to delete.
     */
    suspend fun resetStoredDataIfRevisionChanged() {
        runAppDataRevisionReset(
            readStoredRevision = { runCatching { platformStorage.readText(appDataRevisionStorageKey()) }.getOrNull() },
            writeStoredRevision = { value -> platformStorage.writeText(appDataRevisionStorageKey(), value) },
            signOutAndWipe = {
                sessionRepository.signOut()
                deleteDatabaseDataForSignOut()
                ArtworkOriginHolder.clear()
                ArtworkAuthHolder.clear()
            },
        )
    }

    /**
     * Wipe the previous account's data, with writes sealed for the whole drain.
     *
     * The seal goes up before anything else so that a sync coroutine cancelled moments ago cannot
     * land a write between the delete and the end of sign-out. That actually happened: a cancelled
     * but un-joined Plex play-count refresh re-inserted 23 `plex-stats` rows into
     * `PlayCountAggregateRow` seconds after the tables were cleared, and the home screen then
     * rendered them as unplayable placeholder rows against a Navidrome library.
     */
    suspend fun deleteDatabaseDataForSignOut() {
        databaseWriteGate.seal()
        try {
            catalogRepository.awaitDatabaseIdle()
            // These persist "disconnected" back into AppSettingsRow, so the seal drops that write.
            // Harmless — clearAllAppData deletes the row outright. The credential-store deletes
            // they also perform do not go through SQLite and still take effect.
            listenBrainzAccountRepository.disconnect()
            lastFmAccountRepository.disconnect()
            // withWrite still orders this against the repositories that do take the lock; the
            // privileged handle is what lets it write at all while sealed.
            databaseWriteGate.withWrite {
                privilegedDatabase.clearAllAppData(clearPlayHistory = true)
            }
            listOf("session.json", "catalog.json", "media_sources.json", "library_ui_prefs.json", "library_view_mode.txt").forEach {
                platformStorage.delete(it)
            }
            catalogRepository.clearInMemoryCatalog()
            mediaSourcesRepository.clearInMemoryState()
            libraryUiRepository.resetInMemoryState()
            radioRepository.resetInMemoryState()
            appSettingsRepository.resetInMemoryState()
            userArtifactsRepository.resetInMemoryState()
            searchHistoryRepository.clear()
            lyricsRepository.clearMemoryCache()
        } finally {
            // The welcome screen can add local folders and save radio stations, so the app has to
            // be writable again once the drain finishes.
            databaseWriteGate.unseal()
        }
    }

    fun close() {
        runCatching { audioPlayer.close() }
        runCatching { catalogRepository.close() }
        runCatching { playHistoryRepository.close() }
        runCatching { Telemetry.close() }
    }

    companion object {
        suspend fun create(): AppDependencies {
            // The gate is built first: the driver has to be wrapped with it at creation, so the
            // seal sits underneath every repository the graph is about to construct.
            val databaseWriteGate = DatabaseWriteGate()
            val databaseHandle = createPhoebeDatabase(databaseWriteGate)
            val database = databaseHandle.database
            val appGraph = createPhoebeAppGraph(
                database = database,
                databaseWriteGate = databaseWriteGate,
            )
            val services = appGraph.appServices
            val sessionRepository = services.sessionRepository
            val mediaSourcesRepository = services.mediaSourcesRepository
            val searchHistoryRepository = services.searchHistoryRepository
            val userArtifactsRepository = services.userArtifactsRepository
            val radioRepository = services.radioRepository

            val dependencies = AppDependencies(
                appGraph = appGraph,
                database = services.database,
                privilegedDatabase = databaseHandle.privileged,
                databaseWriteGate = services.databaseWriteGate,
                sessionRepository = sessionRepository,
                mediaSourcesRepository = mediaSourcesRepository,
                catalogRepository = services.catalogRepository,
                catalogItemMutationService = services.catalogItemMutationService,
                catalogSyncService = services.catalogSyncService,
                downloadService = services.downloadService,
                libraryUiRepository = services.libraryUiRepository,
                libraryPreferencesService = services.libraryPreferencesService,
                lyricsRepository = services.lyricsRepository,
                musicBrainzRepository = services.musicBrainzRepository,
                playHistoryRepository = services.playHistoryRepository,
                playlistService = services.playlistService,
                radioRepository = radioRepository,
                radioNowPlayingRepository = services.radioNowPlayingRepository,
                appSettingsRepository = services.appSettingsRepository,
                artistEventsRepository = services.artistEventsRepository,
                searchHistoryRepository = searchHistoryRepository,
                userArtifactsRepository = userArtifactsRepository,
                importExportService = services.importExportService,
                settingsService = services.settingsService,
                providerRegistry = services.providerRegistry,
                plexPlayHistorySyncer = services.plexPlayHistorySyncer,
                jellyfinPlayHistorySyncer = services.jellyfinPlayHistorySyncer,
                navidromePlayHistorySyncer = services.navidromePlayHistorySyncer,
                plexPlaybackReporter = services.plexPlaybackReporter,
                listenBrainzAccountRepository = services.listenBrainzAccountRepository,
                listenBrainzPlaybackReporter = services.listenBrainzPlaybackReporter,
                listenBrainzService = services.listenBrainzService,
                lastFmAccountRepository = services.lastFmAccountRepository,
                lastFmPlaybackReporter = services.lastFmPlaybackReporter,
                lastFmService = services.lastFmService,
                secureCredentialStore = services.secureCredentialStore,
                audioPlayer = services.audioPlayer,
                castController = services.castController,
                playbackTransportService = services.playbackTransportService,
                systemVolume = services.systemVolumeController,
                downloadNotifier = services.downloadNotifier,
                navigationService = services.navigationService,
                appUpdateService = services.appUpdateService,
                routeViewModelFactory = services.routeViewModelFactory,
                platformStorage = services.platformStorage,
                pairedDeviceStore = services.pairedDeviceStore,
                playbackRemoteHostBridge = services.playbackRemoteHostBridge,
                remoteHostServer = services.remoteHostServer,
                remoteDiscoveryServer = services.remoteDiscoveryServer,
                remoteDiscoveryClient = services.remoteDiscoveryClient,
                remoteControlClient = services.remoteControlClient,
            )
            dependencies.resetStoredDataIfRevisionChanged()
            sessionRepository.restore(refreshConnections = false)
            mediaSourcesRepository.restore()
            searchHistoryRepository.restore()
            userArtifactsRepository.restore()
            radioRepository.restore()
            services.pairedDeviceStore.restore()
            return dependencies
        }
    }
}

/**
 * One-time reset when the shape of stored data changes.
 *
 * Returns true when a reset was performed. Split out from [AppDependencies] so the ordering
 * guarantees below can be tested without standing up the whole object graph.
 */
internal suspend fun runAppDataRevisionReset(
    readStoredRevision: suspend () -> String?,
    writeStoredRevision: suspend (String) -> Unit,
    signOutAndWipe: suspend () -> Unit,
    currentRevision: Int = PhoebeAppDataRevision,
): Boolean {
    val stored = readStoredRevision()?.trim()?.toIntOrNull()
    if (stored == currentRevision) return false
    if (stored != null && stored > currentRevision) {
        // Downgrade. The newer data is not ours to interpret, but it is not ours to delete
        // either — the user may simply be running an older build for a moment.
        PhoebeLog.d("AppDependencies") { "stored data revision $stored is newer than $currentRevision" }
        return false
    }
    PhoebeLog.d("AppDependencies") {
        "stored data revision ${stored ?: "none"} -> $currentRevision; signing out and resyncing"
    }
    signOutAndWipe()
    // Written only after the wipe succeeds. Marking the reset done first would strand a user
    // whose wipe failed halfway on data the new build cannot read, with no second attempt.
    writeStoredRevision(currentRevision.toString())
    return true
}
