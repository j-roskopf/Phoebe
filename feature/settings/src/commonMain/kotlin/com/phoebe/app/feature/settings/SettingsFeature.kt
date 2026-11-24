package com.phoebe.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.phoebe.app.domain.AppSettings
import com.phoebe.app.domain.AudioProcessingCapabilities
import com.phoebe.app.domain.AudioProcessingSettings
import com.phoebe.app.domain.DownloadItem
import com.phoebe.app.domain.DownloadPolicySettings
import com.phoebe.app.domain.EventSettings
import com.phoebe.app.domain.HomeSection
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.MobileBottomTab
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.domain.PersonalMixPreferences
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.StreamingPolicySettings
import com.phoebe.app.platform.SecureCredentialAvailability
import com.phoebe.app.ui.PhoebeDesignSystem
import com.phoebe.app.ui.HomeScreenLayoutMode
import com.phoebe.app.updates.AppUpdateState

@Immutable
data class SettingsRouteState(
    val isLightMode: Boolean,
    val designId: String = PhoebeDesignSystem.Default.id,
    val tintId: String,
    val downloadDirectory: String?,
    val downloadCount: Int,
    val downloadItems: List<DownloadItem> = emptyList(),
    val downloadManager: DownloadManagerUiSummary = DownloadManagerUiSummary(total = downloadCount, complete = downloadCount),
    val appSettings: AppSettings,
    val audioProcessingCapabilities: AudioProcessingCapabilities = AudioProcessingCapabilities(),
    val libraryUi: LibraryUiPreferences,
    val defaultDownloadDirectoryLabel: String,
    val homeScreenLayoutMode: HomeScreenLayoutMode = HomeScreenLayoutMode.Default,
    val session: PlexSession? = null,
    val listenBrainzCredentialAvailability: SecureCredentialAvailability = SecureCredentialAvailability.Unavailable,
    /**
     * Category to open. Desktop falls back to Playback when null; on mobile null shows the settings hub
     * and a non-null value shows only that category's page.
     */
    val initialCategory: SettingsCategory? = null,
    val appUpdateState: AppUpdateState = AppUpdateState.Idle,
    val pairedDevices: List<com.phoebe.app.remote.PairedRemoteDevice> = emptyList(),
    val remoteControlClientState: com.phoebe.app.remote.RemoteControlSessionState = com.phoebe.app.remote.RemoteControlSessionState(),
)

@Immutable
data class DownloadManagerUiSummary(
    val total: Int = 0,
    val active: Int = 0,
    val complete: Int = 0,
    val failed: Int = 0,
    val estimatedBytes: Long = 0L,
)

@Immutable
class SettingsRouteActions(
    val onLightModeChange: (Boolean) -> Unit,
    val onDesignChange: (String) -> Unit = {},
    val onTintChange: (String) -> Unit,
    val onDownloadDirectory: (String?) -> Unit,
    val onDeleteAllDownloads: () -> Unit,
    val onDeleteCompletedDownloads: () -> Unit = {},
    val onClearFailedDownloads: () -> Unit = {},
    val onRetryFailedDownloads: () -> Unit = {},
    val onRetryDownloads: (Set<String>) -> Unit = {},
    val onCancelDownloads: (Set<String>) -> Unit = {},
    val onDeleteDownloads: (Set<String>) -> Unit = {},
    val onDownloadPolicySettings: (DownloadPolicySettings) -> Unit = {},
    val onStreamingPolicySettings: (StreamingPolicySettings) -> Unit = {},
    val onCrossfadeSeconds: (Int) -> Unit,
    val onScanLibraryOnLaunch: (Boolean) -> Unit,
    val onNotifyWhenDownloadFinishes: (Boolean) -> Unit,
    val onKeepPlayingEnabled: (Boolean) -> Unit = {},
    val onPersistEqualizerSettings: (Boolean) -> Unit = {},
    val onPersistVolumeSettings: (Boolean) -> Unit = {},
    val onAudioProcessingSettings: (AudioProcessingSettings) -> Unit = {},
    val onVisualizerPreset: (NowPlayingVisualizerPreset) -> Unit = {},
    val onShowUltimateGuitarButton: (Boolean) -> Unit = {},
    val onBlurredArtworkAppearance: (Boolean) -> Unit = {},
    val onFullBleedDetailArtwork: (Boolean) -> Unit = {},
    val onTintedBackgroundGradient: (Boolean) -> Unit = {},
    val onHomeSections: (List<HomeSection>) -> Unit,
    val onMobileBottomTabs: (List<MobileBottomTab>) -> Unit = {},
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
    val onEventSettings: (EventSettings) -> Unit = {},
    val onCheckForUpdates: () -> Unit = {},
    val onInstallUpdate: () -> Unit = {},
    val onRemoteControlHostEnabled: (Boolean) -> Unit = {},
    val onRemoteControlHostName: (String) -> Unit = {},
    val onRevokePairedDevice: (String) -> Unit = {},
    val onConnectRemoteControl: () -> Unit = {},
    val onDisconnectRemoteControl: () -> Unit = {},
)

@Composable
fun SettingsDesktopRoute(
    state: SettingsRouteState,
    actions: SettingsRouteActions,
    modifier: Modifier = Modifier,
) {
    SettingsDesktopScreen(state = state, actions = actions, modifier = modifier)
}

@Composable
fun SettingsMobileRoute(
    state: SettingsRouteState,
    actions: SettingsRouteActions,
    page: SettingsCategory?,
    onPageChange: (SettingsCategory?) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsMobileScreen(
        state = state,
        actions = actions,
        page = page,
        onPageChange = onPageChange,
        modifier = modifier,
    )
}
