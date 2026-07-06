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
import com.phoebe.app.platform.PlatformStorage
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
) : PlaybackRuntimeDependencies {
    suspend fun deleteDatabaseDataForSignOut() {
        catalogRepository.awaitDatabaseIdle()
        listenBrainzAccountRepository.disconnect()
        lastFmAccountRepository.disconnect()
        databaseWriteGate.withWrite {
            database.clearAllAppData(clearPlayHistory = true)
        }
        listOf("session.json", "catalog.json", "media_sources.json", "library_ui_prefs.json").forEach {
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
    }

    fun close() {
        runCatching { audioPlayer.close() }
        runCatching { catalogRepository.close() }
        runCatching { playHistoryRepository.close() }
        runCatching { Telemetry.close() }
    }

    companion object {
        suspend fun create(): AppDependencies {
            val database = createPhoebeDatabase()
            val databaseWriteGate = DatabaseWriteGate()
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

            sessionRepository.restore(refreshConnections = false)
            mediaSourcesRepository.restore()
            searchHistoryRepository.restore()
            userArtifactsRepository.restore()
            radioRepository.restore()
            return AppDependencies(
                appGraph = appGraph,
                database = services.database,
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
            )
        }
    }
}
