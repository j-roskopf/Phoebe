package com.phoebe.app.ui

import com.phoebe.app.data.JellyfinQuickConnectResult
import com.phoebe.app.data.ListenBrainzFeedbackScore
import com.phoebe.app.data.ListenBrainzFeedbackTarget
import com.phoebe.app.data.PlayHistoryRankedEntries
import com.phoebe.app.data.PlayHistorySnapshot
import com.phoebe.app.data.rankedEntries
import com.phoebe.app.feature.home.HomeUiState
import com.phoebe.app.feature.library.LibraryFilterTab
import com.phoebe.app.feature.radio.RadioRouteMode
import com.phoebe.app.feature.settings.SettingsCategory
import com.phoebe.app.feature.settings.DownloadManagerUiSummary
import com.phoebe.app.di.RouteViewModelFactory
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.AppSettings
import com.phoebe.app.domain.AppScreen
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.ArtistRadioAvailability
import com.phoebe.app.domain.AudioAnalysisFrame
import com.phoebe.app.domain.AudioProcessingCapabilities
import com.phoebe.app.domain.AudioProcessingSettings
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.DownloadItem
import com.phoebe.app.domain.DownloadPolicySettings
import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.domain.HomeSection
import com.phoebe.app.domain.JellyfinLibraryPageKind
import com.phoebe.app.domain.JellyfinSyncMode
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.LyricsLoadState
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.MediaSourcesState
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.domain.PersonalMixPreferences
import com.phoebe.app.domain.PlexRadioStation
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.PlayerState
import com.phoebe.app.domain.PlayerTransportState
import com.phoebe.app.domain.PlayHistoryKind
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.RadioDirectoryState
import com.phoebe.app.domain.RadioNowPlayingMetadata
import com.phoebe.app.domain.RadioStation
import com.phoebe.app.domain.RadioStationSearchQuery
import com.phoebe.app.domain.ShellPlaybackState
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.defaultCollectionEntries
import com.phoebe.app.player.CastState
import com.phoebe.app.platform.SecureCredentialAvailability
import com.phoebe.app.updates.AppUpdateState

internal data class DesktopShellState(
    val screen: AppScreen,
    val routes: List<PhoebeRoute> = emptyList(),
    val catalog: CatalogSnapshot,
    val catalogRefreshing: Boolean,
    val session: PlexSession?,
    val mediaSources: MediaSourcesState,
    val section: BrowseSection,
    val selectedPlaylistId: String?,
    val showQueue: Boolean,
    val compact: Boolean,
    val busy: Boolean,
    val updateState: AppUpdateState = AppUpdateState.Idle,
    val routeViewModelFactory: RouteViewModelFactory,
)

internal data class PlaybackUiState(
    val shellPlayback: ShellPlaybackState,
    val playerTransport: PlayerTransportState = PlayerTransportState(),
    val player: PlayerState = PlayerState(),
    val track: Track?,
    val radioNowPlaying: RadioNowPlayingMetadata? = null,
    val upNext: List<Track>,
    val currentIndex: Int,
    val lyricsTrack: Track? = null,
    val lyricsState: LyricsLoadState = LyricsLoadState.Idle,
    val castState: CastState = CastState(),
    val remotePlaybackTarget: String? = null,
    val listenBrainzFeedbackTarget: ListenBrainzFeedbackTarget = ListenBrainzFeedbackTarget(),
    val equalizerProfile: EqualizerProfile = EqualizerProfile.Default,
    val persistEqualizerSettings: Boolean = false,
    val equalizerRemoteUnavailable: Boolean = false,
    val visualizerPreset: NowPlayingVisualizerPreset = NowPlayingVisualizerPreset.Default,
    val audioAnalysis: AudioAnalysisFrame = AudioAnalysisFrame.Empty,
    val useFilamentVisualizers: Boolean = true,
)

internal data class PlaybackActions(
    val onToggle: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onShuffle: () -> Unit,
    val onRepeat: () -> Unit,
    val onVolume: (Float) -> Unit,
    val onSeek: (Long) -> Unit,
    val onCast: () -> Unit = {},
    val onLyrics: () -> Unit = {},
    val onEqualizerEnabled: (Boolean) -> Unit = {},
    val onEqualizerBandCount: (Int) -> Unit = {},
    val onEqualizerGain: (Int, Float) -> Unit = { _, _ -> },
    val onEqualizerReset: () -> Unit = {},
    val onPersistEqualizerSettings: (Boolean) -> Unit = {},
    val onAudioProcessingSettings: (AudioProcessingSettings) -> Unit = {},
    val onVisualizerPreset: (NowPlayingVisualizerPreset) -> Unit = {},
    val onListenBrainzFeedback: (ListenBrainzFeedbackScore) -> Unit = {},
    val onPlayQueue: (Int) -> Unit,
    val onClearQueue: () -> Unit,
    val onMoveUpNext: (Int, Int) -> Unit,
    val onRemoveUpNext: (Int) -> Unit,
    val onRetryLyrics: () -> Unit = {},
)

internal data class BrowseUiState(
    val homeUiState: HomeUiState,
    val playHistory: PlayHistorySnapshot,
    val loadPlayHistoryEntries: suspend (PlayHistoryKind, Int) -> PlayHistoryRankedEntries = { kind, limit ->
        playHistory.rankedEntries(kind, limit)
    },
    val resolvedTracksById: Map<String, Track> = emptyMap(),
    val searchQuery: String,
    val libraryFilter: LibraryFilterTab,
    val libraryUi: LibraryUiPreferences,
    val supportedCollectionEntries: Set<CollectionEntry> = defaultCollectionEntries.toSet(),
    val decadeMixNotice: String? = null,
    val radioStations: List<PlexRadioStation> = emptyList(),
    val radioDirectory: RadioDirectoryState = RadioDirectoryState(),
    val radioRouteMode: RadioRouteMode = RadioRouteMode.Home,
    val artistRadioAvailability: Map<String, ArtistRadioAvailability> = emptyMap(),
    val radioStartingIds: Set<String> = emptySet(),
    val internetRadioStartingIds: Set<String> = emptySet(),
)

internal data class BrowseActions(
    val onNavigate: (BrowseSection) -> Unit,
    val onSearchQuery: (String) -> Unit,
    val onLibraryFilter: (LibraryFilterTab) -> Unit,
    val onPlaylist: (Playlist) -> Unit,
    val onArtist: (Artist) -> Unit,
    val onAlbum: (Album) -> Unit,
    val onSong: (Track) -> Unit,
    val onOpenLyrics: (Track) -> Unit = {},
    val onRecentSongs: () -> Unit,
    val onRecentArtists: () -> Unit,
    val onRecentAlbums: () -> Unit,
    val onFavoritePlaylists: () -> Unit = {},
    val onFavoriteArtists: () -> Unit = {},
    val onFavoriteAlbums: () -> Unit = {},
    val onRecentlyPlayed: () -> Unit,
    val onMostPlayed: () -> Unit,
    val onCollections: (CollectionEntry) -> Unit,
    val onCollectionValue: (CollectionEntry, String) -> Unit,
    val onEnsureCollectionValuesLoaded: (CollectionEntry) -> Unit = {},
    val onEnsureCollectionItemsLoaded: (CollectionEntry, String) -> Unit = { _, _ -> },
    val onRefreshRandomArtists: () -> Unit,
    val onRefreshRandomAlbums: () -> Unit,
    val onPrefetchHomeArtist: (Artist) -> Unit = {},
    val onPrefetchHomeAlbum: (Album) -> Unit = {},
    val onPlayDecadeMix: (Int) -> Unit = {},
    val onClearDecadeMixNotice: () -> Unit = {},
    val onPlayRadioStation: (PlexRadioStation) -> Unit = {},
    val onRadioSearch: (RadioStationSearchQuery) -> Unit = {},
    val onRadioLoadMore: () -> Unit = {},
    val onRadioRefreshPopular: () -> Unit = {},
    val onRadioPlay: (RadioStation) -> Unit = {},
    val onRadioCountries: () -> Unit = {},
    val onRadioCountry: (String) -> Unit = {},
    val onRadioStation: (RadioStation) -> Unit = onRadioPlay,
    val onRadioRoot: () -> Unit = {},
    val onRadioAddManualStation: (String, String) -> Unit = { _, _ -> },
    val onRadioUpdateManualStation: (RadioStation, String, String) -> Unit = { _, _, _ -> },
    val onRadioDeleteManualStation: (RadioStation) -> Unit = {},
    val onPlayPersonalMix: () -> Unit = {},
    val onPopDetail: () -> Unit,
    val onPlayTracks: (List<Track>, Int) -> Unit,
    val onPlayAllTracks: (List<Track>) -> Unit = { tracks -> onPlayTracks(tracks, 0) },
    val onShuffleAllTracks: (List<Track>) -> Unit = { tracks -> onPlayTracks(tracks.shuffled(), 0) },
    val onAddToUpNext: (Track) -> Unit,
    val onDownload: (Track) -> Unit,
    val onDownloadArtist: (Artist) -> Unit,
    val onProbeArtistRadio: (Artist) -> Unit = {},
    val onPlayArtistRadio: (Artist) -> Unit,
    val onDownloadAlbum: (Album) -> Unit,
    val onDownloadPlaylist: (Playlist) -> Unit,
    val onLibrarySortBy: (LibrarySortBy) -> Unit,
    val onLibraryAscending: (Boolean) -> Unit,
    val onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    val onInstallUpdate: () -> Unit = {},
)

internal data class AuthSetupState(
    val appMessage: String,
    val pinCode: String?,
    val authInProgress: Boolean = false,
    val serversLoading: Boolean = false,
    val jellyfinServers: List<PlexServer> = emptyList(),
    val jellyfinDiscoveryLoading: Boolean = false,
    val jellyfinQuickConnect: JellyfinQuickConnectResult? = null,
    val servers: List<PlexServer>,
    val libraries: List<MusicLibrary>,
    val librariesLoading: Boolean = false,
)

internal data class AuthSetupActions(
    val onStartSignIn: () -> Unit,
    val onFinishSignIn: () -> Unit,
    val onSignInJellyfin: (String, String, String) -> Unit,
    val onSignInProvider: (MediaProviderType, String, String, String, JellyfinSyncMode?) -> Unit = { _, _, _, _, _ -> },
    val onDiscoverJellyfinServers: () -> Unit = {},
    val onStartJellyfinQuickConnect: (String) -> Unit = {},
    val onFinishJellyfinQuickConnect: () -> Unit = {},
    val onOpenSignIn: () -> Unit = {},
    val onSignOut: () -> Unit,
    val onAddLocalFolder: (String?) -> Unit,
    val onRemoveLocalFolder: (String) -> Unit,
    val onToggleLocalFolder: (String, Boolean) -> Unit,
    val onRefreshLibrary: () -> Unit,
    val onJellyfinPage: (JellyfinLibraryPageKind, Int) -> Unit = { _, _ -> },
    val onSelectServer: (PlexServer) -> Unit,
    val onSelectLibrary: (MusicLibrary, JellyfinSyncMode?) -> Unit,
    val onCancelPlexSetup: () -> Unit,
    val onBackToServerPicker: () -> Unit,
    val onRetryServers: () -> Unit,
)

internal data class SettingsUiState(
    val appSettings: AppSettings,
    val downloadDirectory: String?,
    val downloadCount: Int,
    val downloadItems: List<DownloadItem> = emptyList(),
    val downloadManager: DownloadManagerUiSummary = DownloadManagerUiSummary(total = downloadCount, complete = downloadCount),
    val defaultDownloadDirectoryLabel: String,
    val useLightAppearance: Boolean,
    val appearanceTintId: String,
    val homeScreenLayoutMode: HomeScreenLayoutMode = HomeScreenLayoutMode.Default,
    val settingsInitialCategory: SettingsCategory = SettingsCategory.AudioPlayback,
    val listenBrainzCredentialAvailability: SecureCredentialAvailability = SecureCredentialAvailability.Unavailable,
    val appUpdateState: AppUpdateState = AppUpdateState.Idle,
)

internal data class SettingsActions(
    val onHomeSections: (List<HomeSection>) -> Unit,
    val onMobileBottomTabs: (List<com.phoebe.app.domain.MobileBottomTab>) -> Unit = {},
    val onPersonalMix: (PersonalMixPreferences) -> Unit,
    val onAlbumGridItemSize: (Int) -> Unit,
    val onArtistGridItemSize: (Int) -> Unit,
    val onExportFavoritePlaylists: () -> Unit,
    val onImportFavoritePlaylists: () -> Unit,
    val onExportRadioStations: () -> Unit,
    val onImportRadioStations: () -> Unit,
    val onExportBackupPackage: () -> Unit = {},
    val onImportBackupPackage: () -> Unit = {},
    val onReplaceFromBackupPackage: () -> Unit = {},
    val onCrossfadeSeconds: (Int) -> Unit,
    val onScanLibraryOnLaunch: (Boolean) -> Unit,
    val onNotifyWhenDownloadFinishes: (Boolean) -> Unit,
    val onPersistEqualizerSettings: (Boolean) -> Unit = {},
    val onPersistVolumeSettings: (Boolean) -> Unit = {},
    val onAudioProcessingSettings: (AudioProcessingSettings) -> Unit = {},
    val audioProcessingCapabilities: AudioProcessingCapabilities = AudioProcessingCapabilities(),
    val onVisualizerPreset: (NowPlayingVisualizerPreset) -> Unit = {},
    val onBlurredArtworkAppearance: (Boolean) -> Unit = {},
    val onFullBleedDetailArtwork: (Boolean) -> Unit = {},
    val onTintedBackgroundGradient: (Boolean) -> Unit = {},
    val onDownloadDirectory: (String?) -> Unit,
    val onDeleteAllDownloads: () -> Unit,
    val onDeleteCompletedDownloads: () -> Unit = {},
    val onClearFailedDownloads: () -> Unit = {},
    val onRetryFailedDownloads: () -> Unit = {},
    val onRetryDownloads: (Set<String>) -> Unit = {},
    val onCancelDownloads: (Set<String>) -> Unit = {},
    val onDeleteDownloads: (Set<String>) -> Unit = {},
    val onDownloadPolicySettings: (DownloadPolicySettings) -> Unit = {},
    val onUseLightAppearanceChange: (Boolean) -> Unit,
    val onAppearanceTintChange: (String) -> Unit,
    val onHomeScreenLayoutModeChange: (HomeScreenLayoutMode) -> Unit = {},
    val onConnectListenBrainz: (String) -> Unit = {},
    val onDisconnectListenBrainz: () -> Unit = {},
    val onListenBrainzSubmitNowPlaying: (Boolean) -> Unit = {},
    val onListenBrainzSubmitListens: (Boolean) -> Unit = {},
    val onListenBrainzSubmitCurrentTrackFeedback: (Boolean) -> Unit = {},
    val onStartLastFmAuthorization: (String, String) -> Unit = { _, _ -> },
    val onFinishLastFmAuthorization: () -> Unit = {},
    val onDisconnectLastFm: () -> Unit = {},
    val onLastFmSubmitNowPlaying: (Boolean) -> Unit = {},
    val onLastFmSubmitScrobbles: (Boolean) -> Unit = {},
    val onCheckForUpdates: () -> Unit = {},
    val onInstallUpdate: () -> Unit = {},
)
