package com.phoebe.app.di

import com.phoebe.app.data.EmbyClient
import com.phoebe.app.data.EmbyProviderAdapter
import com.phoebe.app.data.JellyfinClient
import com.phoebe.app.data.JellyfinProviderAdapter
import com.phoebe.app.data.JellyfinPlayHistorySyncer
import com.phoebe.app.data.AppSettingsRepository
import com.phoebe.app.data.ArtistEventsRepository
import com.phoebe.app.data.CatalogItemMutationService
import com.phoebe.app.data.CatalogRepository
import com.phoebe.app.data.CatalogSyncService
import com.phoebe.app.data.DownloadService
import com.phoebe.app.data.ImportExportService
import com.phoebe.app.data.LibraryPreferencesService
import com.phoebe.app.data.LibraryUiRepository
import com.phoebe.app.data.LastFmAccountRepository
import com.phoebe.app.data.LastFmClient
import com.phoebe.app.data.LastFmPlaybackReporter
import com.phoebe.app.data.LastFmService
import com.phoebe.app.data.ListenBrainzClient
import com.phoebe.app.data.ListenBrainzAccountRepository
import com.phoebe.app.data.ListenBrainzPlaybackReporter
import com.phoebe.app.data.ListenBrainzService
import com.phoebe.app.data.LyricsRepository
import com.phoebe.app.data.MediaSourcesRepository
import com.phoebe.app.data.MusicBrainzRepository
import com.phoebe.app.data.MusicAssistantClient
import com.phoebe.app.data.MusicAssistantProviderAdapter
import com.phoebe.app.data.MusicProviderRegistry
import com.phoebe.app.data.NavidromeProviderAdapter
import com.phoebe.app.data.NavidromePlayHistorySyncer
import com.phoebe.app.data.PlayHistoryRepository
import com.phoebe.app.data.PlaylistService
import com.phoebe.app.data.PlexClient
import com.phoebe.app.data.PlexPlayHistorySyncer
import com.phoebe.app.data.PlexPlaybackReporter
import com.phoebe.app.data.RadioNowPlayingRepository
import com.phoebe.app.data.RadioRepository
import com.phoebe.app.data.SearchHistoryRepository
import com.phoebe.app.data.SettingsService
import com.phoebe.app.data.SessionRepository
import com.phoebe.app.data.SubsonicClient
import com.phoebe.app.data.UserArtifactsRepository
import com.phoebe.app.data.db.DatabaseWriteGate
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.platform.DownloadNotifier
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.platform.SecureCredentialStore
import com.phoebe.app.platform.createPlatformHttpClient
import com.phoebe.app.platform.createSecureCredentialStore
import com.phoebe.app.player.AudioPlayer
import com.phoebe.app.player.CastController
import com.phoebe.app.player.PlaybackTransportService
import com.phoebe.app.player.SystemVolumeController
import com.phoebe.app.player.createAudioPlayer
import com.phoebe.app.player.createCastController
import com.phoebe.app.player.createSystemVolumeController
import com.phoebe.app.ui.AppNavigationCoordinator
import com.phoebe.app.ui.AppNavigationService
import com.phoebe.app.updates.AppUpdateCoordinator
import com.phoebe.app.updates.AppUpdateService
import com.phoebe.app.updates.GitHubReleaseUpdateRepository
import com.phoebe.app.updates.PlatformUpdateInstaller
import com.phoebe.app.updates.createPlatformUpdateInstaller
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory
import io.ktor.client.HttpClient

interface AppEnvironment {
    val appName: String
    val appId: String
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultAppEnvironment : AppEnvironment {
    override val appName: String = "Phoebe"
    override val appId: String = "com.phoebe.app"
}

data class AppGraphInfo(
    val appName: String,
    val appId: String,
    val modularized: Boolean,
)

data class AppGraphServices(
    val database: PhoebeDatabase,
    val databaseWriteGate: DatabaseWriteGate,
    val sessionRepository: SessionRepository,
    val mediaSourcesRepository: MediaSourcesRepository,
    val catalogRepository: CatalogRepository,
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
    val systemVolumeController: SystemVolumeController,
    val downloadNotifier: DownloadNotifier,
    val navigationService: AppNavigationService,
    val appUpdateService: AppUpdateService,
    val routeViewModelFactory: RouteViewModelFactory,
    val platformStorage: PlatformStorage,
)

@ContributesTo(AppScope::class)
interface AppGraphContributions {
    val environment: AppEnvironment
    val graphInfo: AppGraphInfo
    val appServices: AppGraphServices
    val database: PhoebeDatabase
    val databaseWriteGate: DatabaseWriteGate
    val httpClient: HttpClient
    val platformStorage: PlatformStorage
    val secureCredentialStore: SecureCredentialStore
    val plexClient: PlexClient
    val jellyfinClient: JellyfinClient
    val embyClient: EmbyClient
    val subsonicClient: SubsonicClient
    val musicAssistantClient: MusicAssistantClient
    val listenBrainzClient: ListenBrainzClient
    val providerRegistry: MusicProviderRegistry
    val sessionRepository: SessionRepository
    val mediaSourcesRepository: MediaSourcesRepository
    val catalogRepository: CatalogRepository
    val catalogItemMutationService: CatalogItemMutationService
    val catalogSyncService: CatalogSyncService
    val downloadService: DownloadService
    val libraryUiRepository: LibraryUiRepository
    val libraryPreferencesService: LibraryPreferencesService
    val lyricsRepository: LyricsRepository
    val musicBrainzRepository: MusicBrainzRepository
    val playHistoryRepository: PlayHistoryRepository
    val playlistService: PlaylistService
    val radioRepository: RadioRepository
    val radioNowPlayingRepository: RadioNowPlayingRepository
    val appSettingsRepository: AppSettingsRepository
    val artistEventsRepository: ArtistEventsRepository
    val searchHistoryRepository: SearchHistoryRepository
    val userArtifactsRepository: UserArtifactsRepository
    val importExportService: ImportExportService
    val settingsService: SettingsService
    val plexPlayHistorySyncer: PlexPlayHistorySyncer
    val jellyfinPlayHistorySyncer: JellyfinPlayHistorySyncer
    val navidromePlayHistorySyncer: NavidromePlayHistorySyncer
    val plexPlaybackReporter: PlexPlaybackReporter
    val listenBrainzAccountRepository: ListenBrainzAccountRepository
    val listenBrainzPlaybackReporter: ListenBrainzPlaybackReporter
    val listenBrainzService: ListenBrainzService
    val lastFmAccountRepository: LastFmAccountRepository
    val lastFmPlaybackReporter: LastFmPlaybackReporter
    val lastFmService: LastFmService
    val audioPlayer: AudioPlayer
    val castController: CastController
    val playbackTransportService: PlaybackTransportService
    val systemVolumeController: SystemVolumeController
    val downloadNotifier: DownloadNotifier
    val updateInstaller: PlatformUpdateInstaller
    val updateRepository: GitHubReleaseUpdateRepository
    val appUpdateCoordinator: AppUpdateCoordinator
    val appUpdateService: AppUpdateService
    val navigationCoordinator: AppNavigationCoordinator
    val navigationService: AppNavigationService
    val routeViewModelFactory: RouteViewModelFactory
}

@ContributesTo(AppScope::class)
interface AppGraphProviders {
    @Provides
    fun provideAppGraphInfo(environment: AppEnvironment): AppGraphInfo =
        AppGraphInfo(
            appName = environment.appName,
            appId = environment.appId,
            modularized = true,
        )

    @Provides
    @SingleIn(AppScope::class)
    fun provideAppGraphServices(
        database: PhoebeDatabase,
        databaseWriteGate: DatabaseWriteGate,
        sessionRepository: SessionRepository,
        mediaSourcesRepository: MediaSourcesRepository,
        catalogRepository: CatalogRepository,
        catalogItemMutationService: CatalogItemMutationService,
        catalogSyncService: CatalogSyncService,
        downloadService: DownloadService,
        libraryUiRepository: LibraryUiRepository,
        libraryPreferencesService: LibraryPreferencesService,
        lyricsRepository: LyricsRepository,
        musicBrainzRepository: MusicBrainzRepository,
        playHistoryRepository: PlayHistoryRepository,
        playlistService: PlaylistService,
        radioRepository: RadioRepository,
        radioNowPlayingRepository: RadioNowPlayingRepository,
        appSettingsRepository: AppSettingsRepository,
        artistEventsRepository: ArtistEventsRepository,
        searchHistoryRepository: SearchHistoryRepository,
        userArtifactsRepository: UserArtifactsRepository,
        importExportService: ImportExportService,
        settingsService: SettingsService,
        providerRegistry: MusicProviderRegistry,
        plexPlayHistorySyncer: PlexPlayHistorySyncer,
        jellyfinPlayHistorySyncer: JellyfinPlayHistorySyncer,
        navidromePlayHistorySyncer: NavidromePlayHistorySyncer,
        plexPlaybackReporter: PlexPlaybackReporter,
        listenBrainzAccountRepository: ListenBrainzAccountRepository,
        listenBrainzPlaybackReporter: ListenBrainzPlaybackReporter,
        listenBrainzService: ListenBrainzService,
        lastFmAccountRepository: LastFmAccountRepository,
        lastFmPlaybackReporter: LastFmPlaybackReporter,
        lastFmService: LastFmService,
        secureCredentialStore: SecureCredentialStore,
        audioPlayer: AudioPlayer,
        castController: CastController,
        playbackTransportService: PlaybackTransportService,
        systemVolumeController: SystemVolumeController,
        downloadNotifier: DownloadNotifier,
        navigationService: AppNavigationService,
        appUpdateService: AppUpdateService,
        routeViewModelFactory: RouteViewModelFactory,
        platformStorage: PlatformStorage,
    ): AppGraphServices =
        AppGraphServices(
            database = database,
            databaseWriteGate = databaseWriteGate,
            sessionRepository = sessionRepository,
            mediaSourcesRepository = mediaSourcesRepository,
            catalogRepository = catalogRepository,
            catalogItemMutationService = catalogItemMutationService,
            catalogSyncService = catalogSyncService,
            downloadService = downloadService,
            libraryUiRepository = libraryUiRepository,
            libraryPreferencesService = libraryPreferencesService,
            lyricsRepository = lyricsRepository,
            musicBrainzRepository = musicBrainzRepository,
            playHistoryRepository = playHistoryRepository,
            playlistService = playlistService,
            radioRepository = radioRepository,
            radioNowPlayingRepository = radioNowPlayingRepository,
            appSettingsRepository = appSettingsRepository,
            artistEventsRepository = artistEventsRepository,
            searchHistoryRepository = searchHistoryRepository,
            userArtifactsRepository = userArtifactsRepository,
            importExportService = importExportService,
            settingsService = settingsService,
            providerRegistry = providerRegistry,
            plexPlayHistorySyncer = plexPlayHistorySyncer,
            jellyfinPlayHistorySyncer = jellyfinPlayHistorySyncer,
            navidromePlayHistorySyncer = navidromePlayHistorySyncer,
            plexPlaybackReporter = plexPlaybackReporter,
            listenBrainzAccountRepository = listenBrainzAccountRepository,
            listenBrainzPlaybackReporter = listenBrainzPlaybackReporter,
            listenBrainzService = listenBrainzService,
            lastFmAccountRepository = lastFmAccountRepository,
            lastFmPlaybackReporter = lastFmPlaybackReporter,
            lastFmService = lastFmService,
            secureCredentialStore = secureCredentialStore,
            audioPlayer = audioPlayer,
            castController = castController,
            playbackTransportService = playbackTransportService,
            systemVolumeController = systemVolumeController,
            downloadNotifier = downloadNotifier,
            navigationService = navigationService,
            appUpdateService = appUpdateService,
            routeViewModelFactory = routeViewModelFactory,
            platformStorage = platformStorage,
        )

    @Provides
    @SingleIn(AppScope::class)
    fun provideHttpClient(): HttpClient = createPlatformHttpClient()

    @Provides
    @SingleIn(AppScope::class)
    fun providePlatformStorage(): PlatformStorage = PlatformStorage()

    @Provides
    @SingleIn(AppScope::class)
    fun provideSecureCredentialStore(): SecureCredentialStore = createSecureCredentialStore()

    @Provides
    @SingleIn(AppScope::class)
    fun provideProviderRegistry(
        jellyfinClient: JellyfinClient,
        embyClient: EmbyClient,
        subsonicClient: SubsonicClient,
        musicAssistantClient: MusicAssistantClient,
    ): MusicProviderRegistry =
        MusicProviderRegistry(
            listOf(
                JellyfinProviderAdapter(jellyfinClient),
                EmbyProviderAdapter(embyClient),
                NavidromeProviderAdapter(subsonicClient),
                MusicAssistantProviderAdapter(musicAssistantClient),
            ),
        )

    @Provides
    @SingleIn(AppScope::class)
    fun provideAudioPlayer(): AudioPlayer = createAudioPlayer()

    @Provides
    @SingleIn(AppScope::class)
    fun provideCastController(audioPlayer: AudioPlayer): CastController = createCastController(audioPlayer)

    @Provides
    @SingleIn(AppScope::class)
    fun provideSystemVolumeController(): SystemVolumeController = createSystemVolumeController()

    @Provides
    @SingleIn(AppScope::class)
    fun provideDownloadNotifier(): DownloadNotifier = DownloadNotifier()

    @Provides
    @SingleIn(AppScope::class)
    fun provideUpdateInstaller(): PlatformUpdateInstaller = createPlatformUpdateInstaller()

    @Provides
    @SingleIn(AppScope::class)
    fun provideUpdateRepository(
        httpClient: HttpClient,
        installer: PlatformUpdateInstaller,
    ): GitHubReleaseUpdateRepository =
        GitHubReleaseUpdateRepository(
            httpClient = httpClient,
            installer = installer,
        )

    @Provides
    @SingleIn(AppScope::class)
    fun providePlexPlaybackReporter(
        plexClient: PlexClient,
        jellyfinClient: JellyfinClient,
        providerRegistry: MusicProviderRegistry,
        audioPlayer: AudioPlayer,
        sessionRepository: SessionRepository,
    ): PlexPlaybackReporter =
        PlexPlaybackReporter(
            plexClient = plexClient,
            jellyfinClient = jellyfinClient,
            providerRegistry = providerRegistry,
            audioPlayer = audioPlayer,
            session = sessionRepository.session,
        )

    @Provides
    @SingleIn(AppScope::class)
    fun provideListenBrainzPlaybackReporter(
        client: ListenBrainzClient,
        credentialStore: SecureCredentialStore,
        accountRepository: ListenBrainzAccountRepository,
        audioPlayer: AudioPlayer,
        appSettingsRepository: AppSettingsRepository,
    ): ListenBrainzPlaybackReporter =
        ListenBrainzPlaybackReporter(
            client = client,
            credentialStore = credentialStore,
            accountRepository = accountRepository,
            audioPlayer = audioPlayer,
            appSettings = appSettingsRepository.settings,
        )

    @Provides
    @SingleIn(AppScope::class)
    fun provideLastFmPlaybackReporter(
        client: LastFmClient,
        accountRepository: LastFmAccountRepository,
        audioPlayer: AudioPlayer,
        appSettingsRepository: AppSettingsRepository,
    ): LastFmPlaybackReporter =
        LastFmPlaybackReporter(
            client = client,
            accountRepository = accountRepository,
            audioPlayer = audioPlayer,
            appSettings = appSettingsRepository.settings,
        )

}

@DependencyGraph(AppScope::class)
interface AppGraph : AppGraphContributions, AppGraphProviders {
    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides database: PhoebeDatabase,
            @Provides databaseWriteGate: DatabaseWriteGate,
        ): AppGraph
    }
}

fun createPhoebeAppGraph(
    database: PhoebeDatabase,
    databaseWriteGate: DatabaseWriteGate,
): AppGraph = createGraphFactory<AppGraph.Factory>().create(database, databaseWriteGate)
