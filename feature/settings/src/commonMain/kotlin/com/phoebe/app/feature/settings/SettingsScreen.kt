package com.phoebe.app.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.phoebe.app.domain.AppSettings
import com.phoebe.app.domain.AudioProcessingCapabilities
import com.phoebe.app.domain.AudioProcessingSettings
import com.phoebe.app.domain.DownloadItem
import com.phoebe.app.domain.DownloadPolicySettings
import com.phoebe.app.domain.DownloadState
import com.phoebe.app.domain.FeatureCapability
import com.phoebe.app.domain.HomeSection
import com.phoebe.app.domain.ListenBrainzCredentialStorageStatus
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.MobileBottomTab
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.domain.PersonalMixPreferences
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.providerLabel
import com.phoebe.app.platform.PhoebeBuildInfo
import com.phoebe.app.platform.SecureCredentialAvailability
import com.phoebe.app.platform.isDesktopPlatform
import com.phoebe.app.platform.openExternalUrl
import com.phoebe.app.platform.rememberPickDownloadDirectory
import com.phoebe.app.ui.HomeScreenLayoutMode
import com.phoebe.app.ui.LocalNowMs
import com.phoebe.app.ui.PhoebeIcon
import com.phoebe.app.ui.PhoebeIconView
import com.phoebe.app.ui.PhoebeTintOption
import com.phoebe.app.ui.PhoebeUi
import com.phoebe.app.ui.SectionLabel
import com.phoebe.app.ui.formatLastPlayed
import com.phoebe.app.updates.AppUpdateState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

enum class SettingsCategory(
    val label: String,
    val subtitle: String,
    val icon: PhoebeIcon,
) {
    Account("Account", "Profile and plans", PhoebeIcon.Music),
    Personalization("Personalization", "Mixes and recommendations", PhoebeIcon.Person),
    AudioPlayback("Audio Playback", "Transitions and EQ", PhoebeIcon.Equalizer),
    Library("Library", "Organize your library", PhoebeIcon.Library),
    Downloads("Downloads", "Manage downloads", PhoebeIcon.Download),
    Appearance("Appearance", "Theme and visuals", PhoebeIcon.Grid),
    Notifications("Notifications", "Manage alerts", PhoebeIcon.Bell),
    About("About", "Version and links", PhoebeIcon.Settings),
    Advanced("Advanced", "Developer and advanced", PhoebeIcon.More),
}

@Composable
fun SettingsDesktopView(
    isLightMode: Boolean,
    onLightModeChange: (Boolean) -> Unit,
    tintId: String,
    onTintChange: (String) -> Unit,
    downloadDirectory: String?,
    downloadCount: Int,
    downloadItems: List<DownloadItem> = emptyList(),
    downloadManager: DownloadManagerUiSummary = DownloadManagerUiSummary(total = downloadCount, complete = downloadCount),
    appSettings: AppSettings,
    audioProcessingCapabilities: AudioProcessingCapabilities = AudioProcessingCapabilities(),
    libraryUi: LibraryUiPreferences,
    defaultDownloadDirectoryLabel: String,
    onDownloadDirectory: (String?) -> Unit,
    onDeleteAllDownloads: () -> Unit,
    onDeleteCompletedDownloads: () -> Unit = {},
    onClearFailedDownloads: () -> Unit = {},
    onRetryFailedDownloads: () -> Unit = {},
    onRetryDownloads: (Set<String>) -> Unit = {},
    onCancelDownloads: (Set<String>) -> Unit = {},
    onDeleteDownloads: (Set<String>) -> Unit = {},
    onDownloadPolicySettings: (DownloadPolicySettings) -> Unit = {},
    onCrossfadeSeconds: (Int) -> Unit,
    onScanLibraryOnLaunch: (Boolean) -> Unit,
    onNotifyWhenDownloadFinishes: (Boolean) -> Unit,
    onKeepPlayingEnabled: (Boolean) -> Unit = {},
    onPersistEqualizerSettings: (Boolean) -> Unit = {},
    onPersistVolumeSettings: (Boolean) -> Unit = {},
    onAudioProcessingSettings: (AudioProcessingSettings) -> Unit = {},
    onVisualizerPreset: (NowPlayingVisualizerPreset) -> Unit = {},
    onShowVisualizerInTvFrame: (Boolean) -> Unit = {},
    onBlurredArtworkAppearance: (Boolean) -> Unit = {},
    onFullBleedDetailArtwork: (Boolean) -> Unit = {},
    onTintedBackgroundGradient: (Boolean) -> Unit = {},
    onHomeSections: (List<HomeSection>) -> Unit,
    onMobileBottomTabs: (List<MobileBottomTab>) -> Unit = {},
    onPersonalMix: (PersonalMixPreferences) -> Unit,
    onAlbumGridItemSize: (Int) -> Unit,
    onArtistGridItemSize: (Int) -> Unit,
    onExportFavoritePlaylists: () -> Unit,
    onImportFavoritePlaylists: () -> Unit,
    onExportRadioStations: () -> Unit,
    onImportRadioStations: () -> Unit,
    onExportBackupPackage: () -> Unit = {},
    onImportBackupPackage: () -> Unit = {},
    onReplaceFromBackupPackage: () -> Unit = {},
    homeScreenLayoutMode: HomeScreenLayoutMode = HomeScreenLayoutMode.Default,
    onHomeScreenLayoutModeChange: (HomeScreenLayoutMode) -> Unit = {},
    session: PlexSession? = null,
    listenBrainzCredentialAvailability: SecureCredentialAvailability = SecureCredentialAvailability.Unavailable,
    onConnectListenBrainz: (String) -> Unit = {},
    onDisconnectListenBrainz: () -> Unit = {},
    onListenBrainzSubmitNowPlaying: (Boolean) -> Unit = {},
    onListenBrainzSubmitListens: (Boolean) -> Unit = {},
    onListenBrainzSubmitCurrentTrackFeedback: (Boolean) -> Unit = {},
    onStartLastFmAuthorization: (String, String) -> Unit = { _, _ -> },
    onFinishLastFmAuthorization: () -> Unit = {},
    onDisconnectLastFm: () -> Unit = {},
    onLastFmSubmitNowPlaying: (Boolean) -> Unit = {},
    onLastFmSubmitScrobbles: (Boolean) -> Unit = {},
    appUpdateState: AppUpdateState = AppUpdateState.Idle,
    onCheckForUpdates: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    modifier: Modifier = Modifier,
    initialCategory: SettingsCategory = SettingsCategory.AudioPlayback,
) {
    var category by remember(initialCategory) { mutableStateOf(initialCategory) }
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 36.dp, vertical = 28.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(Modifier.width(232.dp)) {
                Text("Settings", color = PhoebeUi.primaryText, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text(
                    "Customize your listening experience",
                    color = PhoebeUi.secondaryText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 22.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsCategory.entries.forEach { cat ->
                        SettingsCategoryRow(
                            cat = cat,
                            selected = category == cat,
                            onClick = { category = cat },
                        )
                    }
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (category) {
                    SettingsCategory.Appearance -> AppearanceSettingsCard(
                        isLightMode,
                        onLightModeChange,
                        tintId,
                        onTintChange,
                        homeScreenLayoutMode,
                        onHomeScreenLayoutModeChange,
                        appSettings.nowPlayingVisualizerPreset,
                        onVisualizerPreset,
                        appSettings.nowPlayingVisualizerInTvFrame,
                        onShowVisualizerInTvFrame,
                        appSettings.blurredArtworkAppearance,
                        onBlurredArtworkAppearance,
                        appSettings.fullBleedDetailArtwork,
                        onFullBleedDetailArtwork,
                        appSettings.tintedBackgroundGradient,
                        onTintedBackgroundGradient,
                        showFullBleedDetailArtwork = true,
                    )
                    SettingsCategory.AudioPlayback -> AudioPlaybackSettingsCard(
                        settings = appSettings,
                        capabilities = audioProcessingCapabilities,
                        onCrossfadeSeconds = onCrossfadeSeconds,
                        onScanLibraryOnLaunch = onScanLibraryOnLaunch,
                        onKeepPlayingEnabled = onKeepPlayingEnabled,
                        onPersistEqualizerSettings = onPersistEqualizerSettings,
                        onPersistVolumeSettings = onPersistVolumeSettings,
                        onAudioProcessingSettings = onAudioProcessingSettings,
                    )
                    SettingsCategory.Account -> AccountSettingsCard(
                        session = session,
                        appSettings = appSettings,
                        listenBrainzCredentialAvailability = listenBrainzCredentialAvailability,
                        onConnectListenBrainz = onConnectListenBrainz,
                        onDisconnectListenBrainz = onDisconnectListenBrainz,
                        onListenBrainzSubmitNowPlaying = onListenBrainzSubmitNowPlaying,
                        onListenBrainzSubmitListens = onListenBrainzSubmitListens,
                        onListenBrainzSubmitCurrentTrackFeedback = onListenBrainzSubmitCurrentTrackFeedback,
                        onStartLastFmAuthorization = onStartLastFmAuthorization,
                        onFinishLastFmAuthorization = onFinishLastFmAuthorization,
                        onDisconnectLastFm = onDisconnectLastFm,
                        onLastFmSubmitNowPlaying = onLastFmSubmitNowPlaying,
                        onLastFmSubmitScrobbles = onLastFmSubmitScrobbles,
                    )
                    SettingsCategory.Library -> {
                        LibraryGridSizeSettingsCard(
                            albumGridItemSizeDp = libraryUi.albumGridItemSizeDp,
                            artistGridItemSizeDp = libraryUi.artistGridItemSizeDp,
                            onAlbumGridItemSize = onAlbumGridItemSize,
                            onArtistGridItemSize = onArtistGridItemSize,
                        )
                        HomeSettingsCard(libraryUi.homeSections, onHomeSections)
                        BottomTabSettingsCard(libraryUi.mobileBottomTabs, onMobileBottomTabs)
                        FavoritePlaylistSettingsCard(onExportFavoritePlaylists, onImportFavoritePlaylists)
                        RadioStationsSettingsCard(onExportRadioStations, onImportRadioStations)
                        BackupSettingsCard(
                            onExportBackupPackage = onExportBackupPackage,
                            onImportBackupPackage = onImportBackupPackage,
                            onReplaceFromBackupPackage = onReplaceFromBackupPackage,
                        )
                    }
                    SettingsCategory.Downloads -> DownloadsSettingsCard(
                        downloadDirectory = downloadDirectory,
                        downloadCount = downloadCount,
                        downloadItems = downloadItems,
                        downloadManager = downloadManager,
                        appSettings = appSettings,
                        defaultDownloadDirectoryLabel = defaultDownloadDirectoryLabel,
                        onDownloadDirectory = onDownloadDirectory,
                        onDeleteAllDownloads = onDeleteAllDownloads,
                        onDeleteCompletedDownloads = onDeleteCompletedDownloads,
                        onClearFailedDownloads = onClearFailedDownloads,
                        onRetryFailedDownloads = onRetryFailedDownloads,
                        onRetryDownloads = onRetryDownloads,
                        onCancelDownloads = onCancelDownloads,
                        onDeleteDownloads = onDeleteDownloads,
                        onDownloadPolicySettings = onDownloadPolicySettings,
                    )
                    SettingsCategory.Personalization -> PersonalMixSettingsCard(libraryUi.personalMix, onPersonalMix)
                    SettingsCategory.Notifications -> NotificationsSettingsCard(
                        settings = appSettings,
                        onNotifyWhenDownloadFinishes = onNotifyWhenDownloadFinishes,
                    )
                    SettingsCategory.About -> AboutSettingsCard(
                        updateState = appUpdateState,
                        onCheckForUpdates = onCheckForUpdates,
                        onInstallUpdate = onInstallUpdate,
                    )
                    SettingsCategory.Advanced -> GenericPlaceholderCard(category.label)
                }
            }
        }
    }
}

@Composable
fun SettingsMobileView(
    isLightMode: Boolean,
    onLightModeChange: (Boolean) -> Unit,
    tintId: String,
    onTintChange: (String) -> Unit,
    downloadDirectory: String?,
    downloadCount: Int,
    downloadItems: List<DownloadItem> = emptyList(),
    downloadManager: DownloadManagerUiSummary = DownloadManagerUiSummary(total = downloadCount, complete = downloadCount),
    appSettings: AppSettings,
    audioProcessingCapabilities: AudioProcessingCapabilities = AudioProcessingCapabilities(),
    libraryUi: LibraryUiPreferences,
    defaultDownloadDirectoryLabel: String,
    onDownloadDirectory: (String?) -> Unit,
    onDeleteAllDownloads: () -> Unit,
    onDeleteCompletedDownloads: () -> Unit = {},
    onClearFailedDownloads: () -> Unit = {},
    onRetryFailedDownloads: () -> Unit = {},
    onRetryDownloads: (Set<String>) -> Unit = {},
    onCancelDownloads: (Set<String>) -> Unit = {},
    onDeleteDownloads: (Set<String>) -> Unit = {},
    onDownloadPolicySettings: (DownloadPolicySettings) -> Unit = {},
    onCrossfadeSeconds: (Int) -> Unit,
    onScanLibraryOnLaunch: (Boolean) -> Unit,
    onNotifyWhenDownloadFinishes: (Boolean) -> Unit,
    onKeepPlayingEnabled: (Boolean) -> Unit = {},
    onPersistEqualizerSettings: (Boolean) -> Unit = {},
    onPersistVolumeSettings: (Boolean) -> Unit = {},
    onAudioProcessingSettings: (AudioProcessingSettings) -> Unit = {},
    onVisualizerPreset: (NowPlayingVisualizerPreset) -> Unit = {},
    onShowVisualizerInTvFrame: (Boolean) -> Unit = {},
    onBlurredArtworkAppearance: (Boolean) -> Unit = {},
    onTintedBackgroundGradient: (Boolean) -> Unit = {},
    onHomeSections: (List<HomeSection>) -> Unit,
    onMobileBottomTabs: (List<MobileBottomTab>) -> Unit = {},
    onPersonalMix: (PersonalMixPreferences) -> Unit,
    onAlbumGridItemSize: (Int) -> Unit,
    onArtistGridItemSize: (Int) -> Unit,
    onExportFavoritePlaylists: () -> Unit,
    onImportFavoritePlaylists: () -> Unit,
    onExportRadioStations: () -> Unit,
    onImportRadioStations: () -> Unit,
    onExportBackupPackage: () -> Unit = {},
    onImportBackupPackage: () -> Unit = {},
    onReplaceFromBackupPackage: () -> Unit = {},
    homeScreenLayoutMode: HomeScreenLayoutMode = HomeScreenLayoutMode.Default,
    onHomeScreenLayoutModeChange: (HomeScreenLayoutMode) -> Unit = {},
    session: PlexSession? = null,
    listenBrainzCredentialAvailability: SecureCredentialAvailability = SecureCredentialAvailability.Unavailable,
    onConnectListenBrainz: (String) -> Unit = {},
    onDisconnectListenBrainz: () -> Unit = {},
    onListenBrainzSubmitNowPlaying: (Boolean) -> Unit = {},
    onListenBrainzSubmitListens: (Boolean) -> Unit = {},
    onListenBrainzSubmitCurrentTrackFeedback: (Boolean) -> Unit = {},
    onStartLastFmAuthorization: (String, String) -> Unit = { _, _ -> },
    onFinishLastFmAuthorization: () -> Unit = {},
    onDisconnectLastFm: () -> Unit = {},
    onLastFmSubmitNowPlaying: (Boolean) -> Unit = {},
    onLastFmSubmitScrobbles: (Boolean) -> Unit = {},
    appUpdateState: AppUpdateState = AppUpdateState.Idle,
    onCheckForUpdates: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SectionLabel("ACCOUNT", PhoebeUi.accentLight)
        AccountSettingsCard(
            session = session,
            appSettings = appSettings,
            listenBrainzCredentialAvailability = listenBrainzCredentialAvailability,
            onConnectListenBrainz = onConnectListenBrainz,
            onDisconnectListenBrainz = onDisconnectListenBrainz,
            onListenBrainzSubmitNowPlaying = onListenBrainzSubmitNowPlaying,
            onListenBrainzSubmitListens = onListenBrainzSubmitListens,
            onListenBrainzSubmitCurrentTrackFeedback = onListenBrainzSubmitCurrentTrackFeedback,
            onStartLastFmAuthorization = onStartLastFmAuthorization,
            onFinishLastFmAuthorization = onFinishLastFmAuthorization,
            onDisconnectLastFm = onDisconnectLastFm,
            onLastFmSubmitNowPlaying = onLastFmSubmitNowPlaying,
            onLastFmSubmitScrobbles = onLastFmSubmitScrobbles,
            compact = true,
        )
        SectionLabel("APPEARANCE", PhoebeUi.accentLight)
        AppearanceSettingsCard(
            isLightMode,
            onLightModeChange,
            tintId,
            onTintChange,
            homeScreenLayoutMode,
            onHomeScreenLayoutModeChange,
            appSettings.nowPlayingVisualizerPreset,
            onVisualizerPreset,
            appSettings.nowPlayingVisualizerInTvFrame,
            onShowVisualizerInTvFrame,
            blurredArtworkAppearance = appSettings.blurredArtworkAppearance,
            onBlurredArtworkAppearance = onBlurredArtworkAppearance,
            tintedBackgroundGradient = appSettings.tintedBackgroundGradient,
            onTintedBackgroundGradient = onTintedBackgroundGradient,
            compact = true,
        )
        SectionLabel("LIBRARY", PhoebeUi.accentLight)
        LibraryGridSizeSettingsCard(
            albumGridItemSizeDp = libraryUi.albumGridItemSizeDp,
            artistGridItemSizeDp = libraryUi.artistGridItemSizeDp,
            onAlbumGridItemSize = onAlbumGridItemSize,
            onArtistGridItemSize = onArtistGridItemSize,
            compact = true,
        )
        HomeSettingsCard(libraryUi.homeSections, onHomeSections, compact = true)
        BottomTabSettingsCard(libraryUi.mobileBottomTabs, onMobileBottomTabs, compact = true)
        FavoritePlaylistSettingsCard(onExportFavoritePlaylists, onImportFavoritePlaylists, compact = true)
        RadioStationsSettingsCard(onExportRadioStations, onImportRadioStations, compact = true)
        BackupSettingsCard(
            onExportBackupPackage = onExportBackupPackage,
            onImportBackupPackage = onImportBackupPackage,
            onReplaceFromBackupPackage = onReplaceFromBackupPackage,
            compact = true,
        )
        SectionLabel("AUDIO PLAYBACK", PhoebeUi.accentLight)
        AudioPlaybackSettingsCard(
            settings = appSettings,
            capabilities = audioProcessingCapabilities,
            onCrossfadeSeconds = onCrossfadeSeconds,
            onScanLibraryOnLaunch = onScanLibraryOnLaunch,
            onKeepPlayingEnabled = onKeepPlayingEnabled,
            onPersistEqualizerSettings = onPersistEqualizerSettings,
            onPersistVolumeSettings = onPersistVolumeSettings,
            onAudioProcessingSettings = onAudioProcessingSettings,
            compact = true,
        )
        SectionLabel("DOWNLOADS", PhoebeUi.accentLight)
        DownloadsSettingsCard(
            downloadDirectory = downloadDirectory,
            downloadCount = downloadCount,
            downloadItems = downloadItems,
            downloadManager = downloadManager,
            appSettings = appSettings,
            defaultDownloadDirectoryLabel = defaultDownloadDirectoryLabel,
            onDownloadDirectory = onDownloadDirectory,
            onDeleteAllDownloads = onDeleteAllDownloads,
            onDeleteCompletedDownloads = onDeleteCompletedDownloads,
            onClearFailedDownloads = onClearFailedDownloads,
            onRetryFailedDownloads = onRetryFailedDownloads,
            onRetryDownloads = onRetryDownloads,
            onCancelDownloads = onCancelDownloads,
            onDeleteDownloads = onDeleteDownloads,
            onDownloadPolicySettings = onDownloadPolicySettings,
            compact = true,
        )
        SectionLabel("PERSONALIZATION", PhoebeUi.accentLight)
        PersonalMixSettingsCard(libraryUi.personalMix, onPersonalMix, compact = true)
        SectionLabel("NOTIFICATIONS", PhoebeUi.accentLight)
        NotificationsSettingsCard(
            settings = appSettings,
            onNotifyWhenDownloadFinishes = onNotifyWhenDownloadFinishes,
        )
        SectionLabel("ABOUT", PhoebeUi.accentLight)
        AboutSettingsCard(
            compact = true,
            updateState = appUpdateState,
            onCheckForUpdates = onCheckForUpdates,
            onInstallUpdate = onInstallUpdate,
        )
    }
}

@Composable
private fun SettingsCategoryRow(
    cat: SettingsCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) PhoebeUi.accent.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent,
            )
            .border(
                BorderStroke(1.dp, if (selected) PhoebeUi.accent.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent),
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PhoebeIconView(
            cat.icon,
            tint = if (selected) PhoebeUi.accentLight else PhoebeUi.secondaryText,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(cat.label, color = if (selected) PhoebeUi.accentLight else PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(cat.subtitle, color = PhoebeUi.mutedText, fontSize = 11.sp, maxLines = 2)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSettingsCard(
    isLightMode: Boolean,
    onLightModeChange: (Boolean) -> Unit,
    tintId: String,
    onTintChange: (String) -> Unit,
    homeScreenLayoutMode: HomeScreenLayoutMode,
    onHomeScreenLayoutModeChange: (HomeScreenLayoutMode) -> Unit,
    nowPlayingVisualizerPreset: NowPlayingVisualizerPreset,
    onVisualizerPreset: (NowPlayingVisualizerPreset) -> Unit,
    nowPlayingVisualizerInTvFrame: Boolean,
    onShowVisualizerInTvFrame: (Boolean) -> Unit,
    blurredArtworkAppearance: Boolean,
    onBlurredArtworkAppearance: (Boolean) -> Unit,
    fullBleedDetailArtwork: Boolean = true,
    onFullBleedDetailArtwork: (Boolean) -> Unit = {},
    tintedBackgroundGradient: Boolean = true,
    onTintedBackgroundGradient: (Boolean) -> Unit = {},
    showFullBleedDetailArtwork: Boolean = false,
    compact: Boolean = false,
) {
    SettingsCard {
        Text("Appearance", color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Theme and visuals", color = PhoebeUi.mutedText, fontSize = 12.sp, modifier = Modifier.padding(bottom = 14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Light mode", color = PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("Use the bright theme across the app", color = PhoebeUi.secondaryText, fontSize = 12.sp)
            }
            Switch(
                checked = isLightMode,
                onCheckedChange = onLightModeChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = PhoebeUi.accentLight,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = PhoebeUi.progressTrack,
                ),
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Blurred artwork appearance", color = PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("Use the soft reflected artwork panel in fullscreen Now Playing", color = PhoebeUi.secondaryText, fontSize = 12.sp)
            }
            Switch(
                checked = blurredArtworkAppearance,
                onCheckedChange = onBlurredArtworkAppearance,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = PhoebeUi.accentLight,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = PhoebeUi.progressTrack,
                ),
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Tinted background", color = PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("Use a soft gradient from the selected tint color", color = PhoebeUi.secondaryText, fontSize = 12.sp)
            }
            Switch(
                checked = tintedBackgroundGradient,
                onCheckedChange = onTintedBackgroundGradient,
                modifier = Modifier.testTag("settings:tinted-background-switch"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = PhoebeUi.accentLight,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = PhoebeUi.progressTrack,
                ),
            )
        }
        Spacer(Modifier.height(18.dp))
        if (showFullBleedDetailArtwork) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Full bleed detail artwork", color = PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Let album and artist pages use artwork as the desktop header background", color = PhoebeUi.secondaryText, fontSize = 12.sp)
                }
                Switch(
                    checked = fullBleedDetailArtwork,
                    onCheckedChange = onFullBleedDetailArtwork,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PhoebeUi.accentLight,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = PhoebeUi.progressTrack,
                    ),
                )
            }
            Spacer(Modifier.height(18.dp))
        }
        Text("Mobile Home layout", color = PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        HomeLayoutModeControl(
            selected = homeScreenLayoutMode,
            onSelected = onHomeScreenLayoutModeChange,
        )
        Spacer(Modifier.height(18.dp))
        Text("Now Playing visualizer", color = PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        VisualizerPresetSelector(
            selected = nowPlayingVisualizerPreset,
            onSelected = onVisualizerPreset,
            showInTvFrame = nowPlayingVisualizerInTvFrame,
            onShowInTvFrameChange = onShowVisualizerInTvFrame,
            compact = compact,
        )
        Spacer(Modifier.height(18.dp))
        Text("Tint", color = PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text("Choose the accent color for controls and active states", color = PhoebeUi.secondaryText, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PhoebeTintOption.Options.forEach { option ->
                TintSwatch(
                    option = option,
                    selected = option.id == tintId,
                    onClick = { onTintChange(option.id) },
                )
            }
        }
    }
}

@Composable
private fun HomeLayoutModeControl(
    selected: HomeScreenLayoutMode,
    onSelected: (HomeScreenLayoutMode) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HomeScreenLayoutMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Row(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelected(mode) }
                    .background(if (isSelected) PhoebeUi.accent.copy(alpha = 0.16f) else Color.Transparent)
                    .border(
                        BorderStroke(
                            1.dp,
                            if (isSelected) PhoebeUi.accent.copy(alpha = 0.32f) else Color.Transparent,
                        ),
                        RoundedCornerShape(8.dp),
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    mode.label,
                    color = if (isSelected) PhoebeUi.accentLight else PhoebeUi.secondaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TintSwatch(
    option: PhoebeTintOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .size(34.dp)
            .clip(shape)
            .clickable(onClick = onClick)
            .background(option.color.copy(alpha = 0.18f))
            .border(
                BorderStroke(1.dp, if (selected) PhoebeUi.primaryText else PhoebeUi.border),
                shape,
            )
            .padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(option.color),
        )
        if (selected) {
            PhoebeIconView(PhoebeIcon.Check, tint = Color.White, modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
private fun AudioPlaybackSettingsCard(
    settings: AppSettings,
    capabilities: AudioProcessingCapabilities,
    onCrossfadeSeconds: (Int) -> Unit,
    onScanLibraryOnLaunch: (Boolean) -> Unit,
    onKeepPlayingEnabled: (Boolean) -> Unit = {},
    onPersistEqualizerSettings: (Boolean) -> Unit,
    onPersistVolumeSettings: (Boolean) -> Unit = {},
    onAudioProcessingSettings: (AudioProcessingSettings) -> Unit,
    compact: Boolean = false,
) {
    var localCrossfade by remember(settings.crossfadeSeconds) { mutableIntStateOf(settings.crossfadeSeconds) }
        val audio = settings.audioProcessing.normalized()
        SettingsCard {
            Text("Audio Playback", color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Transitions and library scan", color = PhoebeUi.mutedText, fontSize = 12.sp, modifier = Modifier.padding(bottom = 14.dp))
        Text("Crossfade", color = PhoebeUi.secondaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Slider(
            value = localCrossfade.toFloat(),
            onValueChange = {
                val seconds = it.roundToInt().coerceIn(AppSettings.MinCrossfadeSeconds, AppSettings.MaxCrossfadeSeconds)
                localCrossfade = seconds
                if (seconds != settings.crossfadeSeconds) {
                    onCrossfadeSeconds(seconds)
                }
            },
            onValueChangeFinished = { onCrossfadeSeconds(localCrossfade) },
            valueRange = AppSettings.MinCrossfadeSeconds.toFloat()..AppSettings.MaxCrossfadeSeconds.toFloat(),
            steps = AppSettings.MaxCrossfadeSeconds - AppSettings.MinCrossfadeSeconds - 1,
            modifier = Modifier.padding(vertical = 4.dp),
            colors = SliderDefaults.colors(
                thumbColor = PhoebeUi.accentLight,
                activeTrackColor = PhoebeUi.accentLight,
                inactiveTrackColor = PhoebeUi.progressTrack,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0s", color = PhoebeUi.mutedText, fontSize = 11.sp)
            Text("${localCrossfade}s", color = PhoebeUi.accentLight, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text("12s", color = PhoebeUi.mutedText, fontSize = 11.sp)
        }
        SettingsSwitchRow(
            title = "Gapless playback",
            subtitle = capabilitySubtitle(capabilities.gapless, "Keep album playback continuous when the engine supports it"),
            checked = audio.gaplessEnabled && capabilities.gapless.isSupported,
            enabled = capabilities.gapless.isSupported,
            onCheckedChange = { checked -> onAudioProcessingSettings(audio.copy(gaplessEnabled = checked)) },
        )
        SettingsSwitchRow(
            title = "Keep Playing",
            subtitle = "Add related songs before the queue ends",
            checked = settings.keepPlayingEnabled,
            onCheckedChange = onKeepPlayingEnabled,
        )
        Spacer(Modifier.height(12.dp))
        SettingsSwitchRow(
            title = "Persist equalizer",
            subtitle = "Apply the current EQ profile after app restart",
            checked = settings.persistEqualizerSettings,
            onCheckedChange = onPersistEqualizerSettings,
        )
        if (isDesktopPlatform()) {
            SettingsSwitchRow(
                title = "Remember volume",
                subtitle = "Restore the transport volume slider after app restart",
                checked = settings.persistVolumeSettings,
                onCheckedChange = onPersistVolumeSettings,
            )
        }
        SettingsSwitchRow(
            title = "Scan library on launch",
            subtitle = "Refresh local folders when Phoebe starts",
            checked = settings.scanLibraryOnLaunch,
            onCheckedChange = onScanLibraryOnLaunch,
        )
    }
}

private val FeatureCapability.isSupported: Boolean
    get() = this is FeatureCapability.Supported

private fun capabilitySubtitle(capability: FeatureCapability, supported: String): String =
    when (capability) {
        FeatureCapability.Supported -> supported
        is FeatureCapability.Unsupported -> capability.reason
    }

@Composable
private fun NotificationsSettingsCard(
    settings: AppSettings,
    onNotifyWhenDownloadFinishes: (Boolean) -> Unit,
) {
    SettingsCard {
        Text("Notifications", color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Download alerts", color = PhoebeUi.mutedText, fontSize = 12.sp, modifier = Modifier.padding(bottom = 14.dp))
        SettingsSwitchRow(
            title = "Download finished",
            subtitle = "Be notified when something finishes downloading",
            checked = settings.notifyWhenDownloadFinishes,
            onCheckedChange = onNotifyWhenDownloadFinishes,
        )
    }
}

@Composable
private fun DownloadsSettingsCard(
    downloadDirectory: String?,
    downloadCount: Int,
    downloadItems: List<DownloadItem>,
    downloadManager: DownloadManagerUiSummary,
    appSettings: AppSettings,
    defaultDownloadDirectoryLabel: String,
    onDownloadDirectory: (String?) -> Unit,
    onDeleteAllDownloads: () -> Unit,
    onDeleteCompletedDownloads: () -> Unit,
    onClearFailedDownloads: () -> Unit,
    onRetryFailedDownloads: () -> Unit,
    onRetryDownloads: (Set<String>) -> Unit,
    onCancelDownloads: (Set<String>) -> Unit,
    onDeleteDownloads: (Set<String>) -> Unit,
    onDownloadPolicySettings: (DownloadPolicySettings) -> Unit,
    compact: Boolean = false,
) {
    val pickDownloadDirectory = rememberPickDownloadDirectory(onPicked = onDownloadDirectory)
    val display = downloadDirectory?.let(::displayDownloadDirectory) ?: defaultDownloadDirectoryLabel
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(DownloadManagerTab.Active) }
    val policy = appSettings.downloadPolicy.normalized()
    val filteredItems = remember(downloadItems, selectedTab) {
        downloadItems
            .filter { item -> selectedTab.includes(item) }
            .sortedWith(compareBy<DownloadItem> { it.state.sortOrder }.thenByDescending { it.updatedAtMs }.thenBy { it.title })
    }
    SettingsCard {
        Text("Downloads", color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Offline songs", color = PhoebeUi.mutedText, fontSize = 12.sp, modifier = Modifier.padding(bottom = 14.dp))
        Text("Download Location", color = PhoebeUi.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PhoebeUi.subtleFill)
                .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
                .clickable(onClick = pickDownloadDirectory)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 0.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PhoebeIconView(PhoebeIcon.Download, tint = PhoebeUi.accentLight, modifier = Modifier.size(15.dp))
                if (!compact) {
                    Text(
                        display,
                        color = PhoebeUi.primaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text("Change", color = PhoebeUi.accentLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            if (compact) {
                Text(
                    display,
                    color = PhoebeUi.primaryText,
                    fontSize = 12.sp,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxWidth().padding(start = 25.dp),
                )
            }
        }
        if (downloadDirectory != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onDownloadDirectory(null) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PhoebeIconView(PhoebeIcon.Close, tint = PhoebeUi.mutedText, modifier = Modifier.size(12.dp))
                Text("Use default location", color = PhoebeUi.secondaryText, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Download manager", color = PhoebeUi.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DownloadMetricChip("Active", downloadManager.active)
            DownloadMetricChip("Complete", downloadManager.complete)
            DownloadMetricChip("Failed", downloadManager.failed)
            DownloadMetricChip("All", downloadManager.total)
        }
        if (downloadManager.estimatedBytes > 0L) {
            Text(
                "Estimated storage ${formatBytes(downloadManager.estimatedBytes)}",
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PhoebeUi.subtleFill)
                .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (downloadManager.total == 1) "1 tracked download" else "${downloadManager.total} tracked downloads",
                    color = PhoebeUi.primaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("Retry failures, clear finished files, or reset download status", color = PhoebeUi.secondaryText, fontSize = 12.sp)
            }
            Text(
                "Delete all",
                color = if (downloadCount > 0) PhoebeUi.accentLight else PhoebeUi.mutedText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = downloadCount > 0) { confirmDeleteAll = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DownloadManagerAction(
                label = "Retry failed",
                enabled = downloadManager.failed > 0,
                onClick = onRetryFailedDownloads,
            )
            DownloadManagerAction(
                label = "Clear failed",
                enabled = downloadManager.failed > 0,
                onClick = onClearFailedDownloads,
            )
            DownloadManagerAction(
                label = "Delete complete",
                enabled = downloadManager.complete > 0,
                onClick = onDeleteCompletedDownloads,
            )
        }
        Spacer(Modifier.height(14.dp))
        DownloadManagerTabs(
            selected = selectedTab,
            summary = downloadManager,
            onSelected = { selectedTab = it },
        )
        Spacer(Modifier.height(8.dp))
        DownloadManagerList(
            items = filteredItems,
            selectedTab = selectedTab,
            compact = compact,
            onRetry = { item -> onRetryDownloads(setOf(item.trackId)) },
            onCancel = { item -> onCancelDownloads(setOf(item.trackId)) },
            onDelete = { item -> onDeleteDownloads(setOf(item.trackId)) },
        )
        Spacer(Modifier.height(14.dp))
        Text("Download policy", color = PhoebeUi.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        MixValueSlider(
            label = "Concurrent downloads",
            value = policy.maxConcurrentDownloads,
            range = DownloadPolicySettings.MinConcurrentDownloads..DownloadPolicySettings.MaxConcurrentDownloads,
            suffix = "",
            compact = compact,
        ) { value ->
            onDownloadPolicySettings(policy.copy(maxConcurrentDownloads = value))
        }
        SettingsSwitchRow(
            title = "Retry failed downloads",
            subtitle = "Automatically requeue failed downloads when the manager runs",
            checked = policy.autoRetryFailedDownloads,
            onCheckedChange = { checked -> onDownloadPolicySettings(policy.copy(autoRetryFailedDownloads = checked)) },
        )
        SettingsSwitchRow(
            title = "Wi-Fi only",
            subtitle = "Avoid starting downloads on metered mobile networks when the platform can detect them",
            checked = policy.wifiOnly,
            onCheckedChange = { checked -> onDownloadPolicySettings(policy.withWifiOnly(checked)) },
        )
        SettingsSwitchRow(
            title = "Completion alerts",
            subtitle = "Use download completion notifications for this device",
            checked = policy.notifyOnCompletion,
            onCheckedChange = { checked -> onDownloadPolicySettings(policy.copy(notifyOnCompletion = checked)) },
        )
        Text(
            "Quality: Original",
            color = PhoebeUi.secondaryText,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (confirmDeleteAll) {
        DeleteDownloadsDialog(
            downloadCount = downloadCount,
            onDismiss = { confirmDeleteAll = false },
            onConfirm = {
                confirmDeleteAll = false
                onDeleteAllDownloads()
            },
        )
    }
}

@Composable
private fun DownloadMetricChip(
    label: String,
    value: Int,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = PhoebeUi.secondaryText, fontSize = 12.sp)
        Text(value.toString(), color = PhoebeUi.primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DownloadManagerAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = if (enabled) PhoebeUi.accentLight else PhoebeUi.mutedText,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

private enum class DownloadManagerTab(val label: String) {
    Active("Active"),
    Complete("Complete"),
    Failed("Failed"),
    All("All"),
    ;

    fun includes(item: DownloadItem): Boolean = when (this) {
        Active -> item.state == DownloadState.Queued || item.state == DownloadState.Downloading
        Complete -> item.state == DownloadState.Complete
        Failed -> item.state == DownloadState.Failed
        All -> true
    }
}

private val DownloadState.sortOrder: Int
    get() = when (this) {
        DownloadState.Downloading -> 0
        DownloadState.Queued -> 1
        DownloadState.Failed -> 2
        DownloadState.Complete -> 3
    }

@Composable
private fun DownloadManagerTabs(
    selected: DownloadManagerTab,
    summary: DownloadManagerUiSummary,
    onSelected: (DownloadManagerTab) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DownloadManagerTab.entries.forEach { tab ->
            val isSelected = tab == selected
            val count = when (tab) {
                DownloadManagerTab.Active -> summary.active
                DownloadManagerTab.Complete -> summary.complete
                DownloadManagerTab.Failed -> summary.failed
                DownloadManagerTab.All -> summary.total
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelected(tab) }
                    .background(if (isSelected) PhoebeUi.accent.copy(alpha = 0.16f) else Color.Transparent)
                    .border(
                        BorderStroke(1.dp, if (isSelected) PhoebeUi.accent.copy(alpha = 0.32f) else Color.Transparent),
                        RoundedCornerShape(8.dp),
                    ),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${tab.label} $count",
                    color = if (isSelected) PhoebeUi.accentLight else PhoebeUi.secondaryText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DownloadManagerList(
    items: List<DownloadItem>,
    selectedTab: DownloadManagerTab,
    compact: Boolean,
    onRetry: (DownloadItem) -> Unit,
    onCancel: (DownloadItem) -> Unit,
    onDelete: (DownloadItem) -> Unit,
) {
    if (items.isEmpty()) {
        Text(
            "No ${selectedTab.label.lowercase()} downloads",
            color = PhoebeUi.mutedText,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(PhoebeUi.subtleFill)
                .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp)),
    ) {
        items.take(if (compact) 6 else 10).forEachIndexed { index, item ->
            DownloadManagerRow(
                item = item,
                onRetry = onRetry,
                onCancel = onCancel,
                onDelete = onDelete,
            )
            if (index != items.lastIndex && index != (if (compact) 5 else 9)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(PhoebeUi.border.copy(alpha = 0.55f)))
            }
        }
        if (items.size > if (compact) 6 else 10) {
            Text(
                "${items.size - if (compact) 6 else 10} more downloads in this tab",
                color = PhoebeUi.mutedText,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun DownloadManagerRow(
    item: DownloadItem,
    onRetry: (DownloadItem) -> Unit,
    onCancel: (DownloadItem) -> Unit,
    onDelete: (DownloadItem) -> Unit,
) {
    val stateTint = downloadStateTint(item.state)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhoebeIconView(PhoebeIcon.Download, tint = stateTint, modifier = Modifier.size(15.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, color = PhoebeUi.primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.artist, color = PhoebeUi.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(item.state.label, color = stateTint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        if (item.state == DownloadState.Downloading || item.state == DownloadState.Queued) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(PhoebeUi.progressTrack),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(item.progress.coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(PhoebeUi.accentLight),
                )
            }
        }
        item.error?.takeIf { it.isNotBlank() }?.let { error ->
            Text(error, color = PhoebeUi.mutedText, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.state == DownloadState.Failed) {
                DownloadManagerAction("Retry", enabled = item.downloadUrl.isNotBlank(), onClick = { onRetry(item) })
            }
            if (item.state == DownloadState.Queued || item.state == DownloadState.Downloading) {
                DownloadManagerAction("Cancel", enabled = true, onClick = { onCancel(item) })
            }
            DownloadManagerAction(
                label = if (item.state == DownloadState.Complete) "Delete file" else "Remove",
                enabled = true,
                onClick = { onDelete(item) },
            )
            item.totalBytes?.takeIf { it > 0L }?.let { total ->
                Text(
                    "${formatBytes(item.downloadedBytes)} / ${formatBytes(total)}",
                    color = PhoebeUi.mutedText,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

private val DownloadState.label: String
    get() = when (this) {
        DownloadState.Queued -> "Queued"
        DownloadState.Downloading -> "Downloading"
        DownloadState.Complete -> "Complete"
        DownloadState.Failed -> "Failed"
    }

@Composable
private fun downloadStateTint(state: DownloadState): Color =
    when (state) {
        DownloadState.Queued -> PhoebeUi.secondaryText
        DownloadState.Downloading -> PhoebeUi.accentLight
        DownloadState.Complete -> PhoebeUi.primaryText
        DownloadState.Failed -> PhoebeUi.mutedText
    }

@Composable
private fun DeleteDownloadsDialog(
    downloadCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .widthIn(min = 300.dp, max = 420.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(PhoebeUi.modalSurface)
                .border(BorderStroke(1.dp, PhoebeUi.accentLight.copy(alpha = 0.18f)), RoundedCornerShape(18.dp))
                .padding(horizontal = 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Delete all downloads?", color = PhoebeUi.primaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "This removes offline files for $downloadCount ${if (downloadCount == 1) "song" else "songs"} and clears download status.",
                color = PhoebeUi.secondaryText,
                fontSize = 13.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = PhoebeUi.secondaryText)
                }
                TextButton(onClick = onConfirm) {
                    Text("Delete", color = PhoebeUi.accentLight, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun displayDownloadDirectory(uri: String): String =
    uri.removePrefix("file:")
        .removePrefix("//")
        .replace("%20", " ")
        .substringAfterLast("tree/", uri)
        .ifBlank { uri }

private fun formatBytes(bytes: Long): String {
    val mib = 1024L * 1024L
    val gib = mib * 1024L
    return when {
        bytes >= gib -> "${((bytes * 10L) / gib) / 10.0} GB"
        bytes >= mib -> "${((bytes * 10L) / mib) / 10.0} MB"
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes B"
    }
}

@Composable
private fun PersonalMixSettingsCard(
    preferences: PersonalMixPreferences,
    onPreferences: (PersonalMixPreferences) -> Unit,
    compact: Boolean = false,
) {
    val normalized = preferences.normalized()
    val weightTotal = normalized.mixWeightTotal()
    fun weightRange(current: Int): IntRange =
        0..(current + (100 - weightTotal)).coerceIn(current, 100)
    SettingsCard {
        Text("Personal Mix", color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(
            Modifier.fillMaxWidth().padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tune the mix created from Home", color = PhoebeUi.mutedText, fontSize = 12.sp)
            Text("$weightTotal%", color = PhoebeUi.accentLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        MixValueSlider(
            label = "Songs",
            value = normalized.limit,
            range = PersonalMixPreferences.MinLimit..PersonalMixPreferences.MaxLimit,
            suffix = "",
            compact = compact,
        ) { onPreferences(normalized.copy(limit = it)) }
        Spacer(Modifier.height(8.dp))
        MixValueSlider("Heavy rotation", normalized.heavyRotationWeight, weightRange(normalized.heavyRotationWeight), "%", compact) {
            onPreferences(normalized.copy(heavyRotationWeight = it))
        }
        MixValueSlider("Recent plays", normalized.recentWeight, weightRange(normalized.recentWeight), "%", compact) {
            onPreferences(normalized.copy(recentWeight = it))
        }
        MixValueSlider("Most played", normalized.mostPlayedWeight, weightRange(normalized.mostPlayedWeight), "%", compact) {
            onPreferences(normalized.copy(mostPlayedWeight = it))
        }
        MixValueSlider("Similar songs", normalized.similarWeight, weightRange(normalized.similarWeight), "%", compact) {
            onPreferences(normalized.copy(similarWeight = it))
        }
        MixValueSlider("Discovery", normalized.discoveryWeight, weightRange(normalized.discoveryWeight), "%", compact) {
            onPreferences(normalized.copy(discoveryWeight = it))
        }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = { onPreferences(PersonalMixPreferences.Default) }) {
            Text("Reset mix", color = PhoebeUi.accentLight)
        }
    }
}

private fun PersonalMixPreferences.mixWeightTotal(): Int =
    heavyRotationWeight + recentWeight + mostPlayedWeight + similarWeight + discoveryWeight

@Composable
private fun MixValueSlider(
    label: String,
    value: Int,
    range: IntRange,
    suffix: String,
    compact: Boolean,
    onValue: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = if (compact) 3.dp else 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = PhoebeUi.secondaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("$value$suffix", color = PhoebeUi.accentLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValue(it.roundToInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = PhoebeUi.accentLight,
                activeTrackColor = PhoebeUi.accentLight,
                inactiveTrackColor = PhoebeUi.progressTrack,
            ),
        )
    }
}

@Composable
private fun LibraryGridSizeSettingsCard(
    albumGridItemSizeDp: Int,
    artistGridItemSizeDp: Int,
    onAlbumGridItemSize: (Int) -> Unit,
    onArtistGridItemSize: (Int) -> Unit,
    compact: Boolean = false,
) {
    SettingsCard {
        Text("Library grid", color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "Artwork size when browsing artists and albums in library grid view",
            color = PhoebeUi.mutedText,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        MixValueSlider(
            label = "Albums",
            value = albumGridItemSizeDp,
            range = LibraryUiPreferences.MinAlbumGridItemSizeDp..LibraryUiPreferences.MaxAlbumGridItemSizeDp,
            suffix = " dp",
            compact = compact,
            onValue = onAlbumGridItemSize,
        )
        MixValueSlider(
            label = "Artists",
            value = artistGridItemSizeDp,
            range = LibraryUiPreferences.MinArtistGridItemSizeDp..LibraryUiPreferences.MaxArtistGridItemSizeDp,
            suffix = " dp",
            compact = compact,
            onValue = onArtistGridItemSize,
        )
    }
}

@Composable
private fun HomeSettingsCard(
    sections: List<HomeSection>,
    onSections: (List<HomeSection>) -> Unit,
    compact: Boolean = false,
) {
    var order by remember { mutableStateOf(normalizedHomeSections(sections)) }
    var draggingSection by remember { mutableStateOf<HomeSection?>(null) }
    var dragStartIndex by remember { mutableStateOf<Int?>(null) }
    var dragTargetIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val onSectionsUpdated = rememberUpdatedState(onSections)
    val density = LocalDensity.current
    val rowHeight = if (compact) 46.dp else 50.dp
    val rowSpacing = 8.dp
    val rowStepPx = with(density) { rowHeight.toPx() + rowSpacing.toPx() }
    LaunchedEffect(sections) {
        order = normalizedHomeSections(sections)
    }
    SettingsCard {
        Text("Home", color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Drag sections into the order you want", color = PhoebeUi.mutedText, fontSize = 12.sp, modifier = Modifier.padding(bottom = 14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
            order.forEachIndexed { index, section ->
                val isDragging = draggingSection == section
                val startIndex = dragStartIndex
                val targetIndex = dragTargetIndex
                val rowOffsetPx = when {
                    draggingSection == null || startIndex == null || targetIndex == null -> 0f
                    isDragging -> dragOffsetPx
                    targetIndex > startIndex && index in (startIndex + 1)..targetIndex -> -rowStepPx
                    targetIndex < startIndex && index in targetIndex until startIndex -> rowStepPx
                    else -> 0f
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .offset { IntOffset(0, rowOffsetPx.roundToInt()) }
                        .zIndex(if (isDragging) 1f else 0f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isDragging) PhoebeUi.accent.copy(alpha = 0.14f) else PhoebeUi.subtleFill)
                        .border(
                            BorderStroke(1.dp, if (isDragging) PhoebeUi.accent.copy(alpha = 0.35f) else PhoebeUi.border),
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .pointerInput(section, rowStepPx) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingSection = section
                                        dragStartIndex = index
                                        dragTargetIndex = index
                                        dragOffsetPx = 0f
                                    },
                                    onDragCancel = {
                                        draggingSection = null
                                        dragStartIndex = null
                                        dragTargetIndex = null
                                        dragOffsetPx = 0f
                                    },
                                    onDragEnd = {
                                        val from = dragStartIndex
                                        val to = dragTargetIndex
                                        draggingSection = null
                                        dragStartIndex = null
                                        dragTargetIndex = null
                                        dragOffsetPx = 0f
                                        if (from != null && to != null && from != to) {
                                            val nextOrder = order.moved(from, to)
                                            order = nextOrder
                                            onSectionsUpdated.value(nextOrder)
                                        }
                                    },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        val start = dragStartIndex ?: return@detectDragGestures
                                        val minOffset = -start * rowStepPx
                                        val maxOffset = (order.lastIndex - start) * rowStepPx
                                        dragOffsetPx = (dragOffsetPx + drag.y).coerceIn(minOffset, maxOffset)
                                        dragTargetIndex = (start + (dragOffsetPx / rowStepPx).roundToInt())
                                            .coerceIn(0, order.lastIndex)
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        PhoebeIconView(PhoebeIcon.Drag, tint = PhoebeUi.mutedText, modifier = Modifier.size(16.dp))
                    }
                    PhoebeIconView(section.icon, tint = PhoebeUi.accentLight, modifier = Modifier.size(15.dp))
                    Text(section.label, color = PhoebeUi.primaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { onSections(HomeSection.defaultOrder) }) {
            Text("Reset order", color = PhoebeUi.accentLight)
        }
    }
}

@Composable
private fun BottomTabSettingsCard(
    tabs: List<MobileBottomTab>,
    onTabs: (List<MobileBottomTab>) -> Unit,
    compact: Boolean = false,
) {
    var order by remember { mutableStateOf(normalizedBottomTabs(tabs)) }
    var draggingTab by remember { mutableStateOf<MobileBottomTab?>(null) }
    var dragStartIndex by remember { mutableStateOf<Int?>(null) }
    var dragTargetIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val onTabsUpdated = rememberUpdatedState(onTabs)
    val density = LocalDensity.current
    val rowHeight = if (compact) 46.dp else 50.dp
    val rowSpacing = 8.dp
    val rowStepPx = with(density) { rowHeight.toPx() + rowSpacing.toPx() }
    LaunchedEffect(tabs) {
        order = normalizedBottomTabs(tabs)
    }
    SettingsCard {
        Text("Bottom tabs", color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Choose which mobile tabs show and drag them into order", color = PhoebeUi.mutedText, fontSize = 12.sp, modifier = Modifier.padding(bottom = 14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
            order.forEachIndexed { index, tab ->
                val isDragging = draggingTab == tab
                val startIndex = dragStartIndex
                val targetIndex = dragTargetIndex
                val checked = tab in tabs
                val rowOffsetPx = when {
                    draggingTab == null || startIndex == null || targetIndex == null -> 0f
                    isDragging -> dragOffsetPx
                    targetIndex > startIndex && index in (startIndex + 1)..targetIndex -> -rowStepPx
                    targetIndex < startIndex && index in targetIndex until startIndex -> rowStepPx
                    else -> 0f
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .offset { IntOffset(0, rowOffsetPx.roundToInt()) }
                        .zIndex(if (isDragging) 1f else 0f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isDragging) PhoebeUi.accent.copy(alpha = 0.14f) else PhoebeUi.subtleFill)
                        .border(
                            BorderStroke(1.dp, if (isDragging) PhoebeUi.accent.copy(alpha = 0.35f) else PhoebeUi.border),
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .pointerInput(tab, rowStepPx) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingTab = tab
                                        dragStartIndex = index
                                        dragTargetIndex = index
                                        dragOffsetPx = 0f
                                    },
                                    onDragCancel = {
                                        draggingTab = null
                                        dragStartIndex = null
                                        dragTargetIndex = null
                                        dragOffsetPx = 0f
                                    },
                                    onDragEnd = {
                                        val from = dragStartIndex
                                        val to = dragTargetIndex
                                        draggingTab = null
                                        dragStartIndex = null
                                        dragTargetIndex = null
                                        dragOffsetPx = 0f
                                        if (from != null && to != null && from != to) {
                                            val nextOrder = order.moved(from, to)
                                            order = nextOrder
                                            onTabsUpdated.value(nextOrder.filter { it in tabs })
                                        }
                                    },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        val start = dragStartIndex ?: return@detectDragGestures
                                        val minOffset = -start * rowStepPx
                                        val maxOffset = (order.lastIndex - start) * rowStepPx
                                        dragOffsetPx = (dragOffsetPx + drag.y).coerceIn(minOffset, maxOffset)
                                        dragTargetIndex = (start + (dragOffsetPx / rowStepPx).roundToInt())
                                            .coerceIn(0, order.lastIndex)
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        PhoebeIconView(PhoebeIcon.Drag, tint = PhoebeUi.mutedText, modifier = Modifier.size(16.dp))
                    }
                    PhoebeIconView(tab.icon, tint = PhoebeUi.accentLight, modifier = Modifier.size(15.dp))
                    Text(tab.label, color = PhoebeUi.primaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Switch(
                        checked = checked,
                        onCheckedChange = { enabled ->
                            val next = when {
                                enabled -> order.filter { it in tabs || it == tab }
                                tabs.size <= MobileBottomTab.MinVisibleTabs -> tabs
                                else -> tabs.filterNot { it == tab }
                            }
                            onTabsUpdated.value(next)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = PhoebeUi.accentLight,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = PhoebeUi.progressTrack,
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { onTabs(MobileBottomTab.defaultOrder) }) {
            Text("Reset tabs", color = PhoebeUi.accentLight)
        }
    }
}

@Composable
private fun FavoritePlaylistSettingsCard(
    onExport: () -> Unit,
    onImport: () -> Unit,
    compact: Boolean = false,
) {
    SettingsCard {
        Text("Favorite playlists", color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "Export or import locally saved favorite playlist flags",
            color = PhoebeUi.mutedText,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = if (compact) 8.dp else 12.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onExport) {
                Text("Export", color = PhoebeUi.accentLight)
            }
            TextButton(onClick = onImport) {
                Text("Import", color = PhoebeUi.accentLight)
            }
        }
    }
}

@Composable
private fun RadioStationsSettingsCard(
    onExport: () -> Unit,
    onImport: () -> Unit,
    compact: Boolean = false,
) {
    SettingsCard {
        Text("Radio stations", color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "Export or import locally saved manual radio stations",
            color = PhoebeUi.mutedText,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = if (compact) 8.dp else 12.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onExport) {
                Text("Export", color = PhoebeUi.accentLight)
            }
            TextButton(onClick = onImport) {
                Text("Import", color = PhoebeUi.accentLight)
            }
        }
    }
}

@Composable
private fun BackupSettingsCard(
    onExportBackupPackage: () -> Unit,
    onImportBackupPackage: () -> Unit,
    onReplaceFromBackupPackage: () -> Unit,
    compact: Boolean = false,
) {
    SettingsCard {
        Text("Phoebe backup", color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "Export or restore settings, smart playlists, saved searches, and local metadata overrides",
            color = PhoebeUi.mutedText,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(bottom = if (compact) 8.dp else 12.dp),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onExportBackupPackage) {
                Text("Export", color = PhoebeUi.accentLight)
            }
            TextButton(onClick = onImportBackupPackage) {
                Text("Merge", color = PhoebeUi.accentLight)
            }
            TextButton(onClick = onReplaceFromBackupPackage) {
                Text("Replace", color = PhoebeUi.accentLight)
            }
        }
    }
}

private val HomeSection.icon: PhoebeIcon
    get() = when (this) {
        HomeSection.Mixes -> PhoebeIcon.Music
        HomeSection.Collections -> PhoebeIcon.Library
        HomeSection.Favorites -> PhoebeIcon.Heart
        HomeSection.FavoritePlaylists -> PhoebeIcon.Heart
        HomeSection.FavoriteArtists -> PhoebeIcon.Library
        HomeSection.FavoriteAlbums -> PhoebeIcon.Grid
        HomeSection.Recents -> PhoebeIcon.Bell
        HomeSection.RecentSongs -> PhoebeIcon.Music
        HomeSection.RecentArtists -> PhoebeIcon.Library
        HomeSection.RecentAlbums -> PhoebeIcon.Grid
        HomeSection.Played -> PhoebeIcon.Play
        HomeSection.Random -> PhoebeIcon.Grid
    }

private val MobileBottomTab.label: String
    get() = when (this) {
        MobileBottomTab.Home -> "Home"
        MobileBottomTab.Search -> "Search"
        MobileBottomTab.Library -> "Library"
        MobileBottomTab.Playlists -> "Playlists"
        MobileBottomTab.Radio -> "Radio"
    }

private val MobileBottomTab.icon: PhoebeIcon
    get() = when (this) {
        MobileBottomTab.Home -> PhoebeIcon.Home
        MobileBottomTab.Search -> PhoebeIcon.Search
        MobileBottomTab.Library -> PhoebeIcon.Library
        MobileBottomTab.Playlists -> PhoebeIcon.PlaylistPlay
        MobileBottomTab.Radio -> PhoebeIcon.Radio
    }

private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices) return this
    val copy = toMutableList()
    val item = copy.removeAt(from)
    copy.add(to, item)
    return copy
}

private fun normalizedHomeSections(sections: List<HomeSection>): List<HomeSection> =
    sections
        .flatMap { section ->
            when (section) {
                HomeSection.Favorites -> listOf(HomeSection.FavoritePlaylists, HomeSection.FavoriteArtists, HomeSection.FavoriteAlbums)
                HomeSection.Recents -> listOf(HomeSection.RecentSongs, HomeSection.RecentArtists, HomeSection.RecentAlbums)
                else -> listOf(section)
            }
        }
        .filterNot { it == HomeSection.Favorites || it == HomeSection.Recents }
        .let { (it + HomeSection.defaultOrder).distinct() }

private fun normalizedBottomTabs(tabs: List<MobileBottomTab>): List<MobileBottomTab> =
    (tabs + MobileBottomTab.defaultOrder).distinct()

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) PhoebeUi.primaryText else PhoebeUi.mutedText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = if (enabled) PhoebeUi.secondaryText else PhoebeUi.mutedText, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = PhoebeUi.accentLight,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = PhoebeUi.progressTrack,
            ),
        )
    }
}

@Composable
private fun AccountSettingsCard(
    session: PlexSession?,
    appSettings: AppSettings,
    listenBrainzCredentialAvailability: SecureCredentialAvailability,
    onConnectListenBrainz: (String) -> Unit,
    onDisconnectListenBrainz: () -> Unit,
    onListenBrainzSubmitNowPlaying: (Boolean) -> Unit,
    onListenBrainzSubmitListens: (Boolean) -> Unit,
    onListenBrainzSubmitCurrentTrackFeedback: (Boolean) -> Unit,
    onStartLastFmAuthorization: (String, String) -> Unit,
    onFinishLastFmAuthorization: () -> Unit,
    onDisconnectLastFm: () -> Unit,
    onLastFmSubmitNowPlaying: (Boolean) -> Unit,
    onLastFmSubmitScrobbles: (Boolean) -> Unit,
    compact: Boolean = false,
) {
    val signedIn = session?.token?.isNotBlank() == true
    val providerName = session.providerLabel()
    SettingsCard {
        Text("Account", color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            if (signedIn) "Your signed-in media provider" else "Connect a media provider to browse your library",
            color = PhoebeUi.mutedText,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = if (compact) 10.dp else 14.dp),
        )
        if (!signedIn) {
            Text("Not signed in", color = PhoebeUi.secondaryText, fontSize = 13.sp)
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (compact) 10.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(if (compact) 40.dp else 48.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFF3876C8), Color(0xFFB87C5C)))),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        session.userName,
                        color = PhoebeUi.primaryText,
                        fontSize = if (compact) 15.sp else 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "$providerName signed in",
                        color = PhoebeUi.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PhoebeUi.subtleFill)
                    .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                AccountDetailRow(label = "Provider", value = providerName)
                AccountDetailRow(label = "Account", value = session.userName)
                session.selectedServer?.name?.takeIf { it.isNotBlank() }?.let { serverName ->
                    AccountDetailRow(label = "Server", value = serverName)
                }
                session.selectedServer?.uri?.takeIf { it.isNotBlank() }?.let { serverUri ->
                    AccountDetailRow(label = "Server URL", value = serverUri)
                }
                session.selectedLibrary?.title?.takeIf { it.isNotBlank() }?.let { libraryTitle ->
                    AccountDetailRow(label = "Library", value = libraryTitle)
                }
            }
        }
        Spacer(Modifier.height(if (compact) 16.dp else 18.dp))
        ListenBrainzSettingsSection(
            appSettings = appSettings,
            credentialAvailability = listenBrainzCredentialAvailability,
            onConnect = onConnectListenBrainz,
            onDisconnect = onDisconnectListenBrainz,
            onSubmitNowPlaying = onListenBrainzSubmitNowPlaying,
            onSubmitListens = onListenBrainzSubmitListens,
            onSubmitCurrentTrackFeedback = onListenBrainzSubmitCurrentTrackFeedback,
            compact = compact,
        )
        Spacer(Modifier.height(12.dp))
        LastFmSettingsSection(
            appSettings = appSettings,
            credentialAvailability = listenBrainzCredentialAvailability,
            onStartAuthorization = onStartLastFmAuthorization,
            onFinishAuthorization = onFinishLastFmAuthorization,
            onDisconnect = onDisconnectLastFm,
            onSubmitNowPlaying = onLastFmSubmitNowPlaying,
            onSubmitScrobbles = onLastFmSubmitScrobbles,
            compact = compact,
        )
    }
}

@Composable
private fun ListenBrainzSettingsSection(
    appSettings: AppSettings,
    credentialAvailability: SecureCredentialAvailability,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onSubmitNowPlaying: (Boolean) -> Unit,
    onSubmitListens: (Boolean) -> Unit,
    onSubmitCurrentTrackFeedback: (Boolean) -> Unit,
    compact: Boolean,
) {
    val settings = appSettings.listenBrainz
    val nowMs = LocalNowMs.current
    var token by remember(settings.connected) { mutableStateOf("") }
    var isConnecting by remember(settings.connected) { mutableStateOf(false) }
    LaunchedEffect(settings.connected, settings.lastValidatedAtMs, settings.lastError) {
        isConnecting = false
        if (settings.connected) token = ""
    }
    LaunchedEffect(isConnecting, token) {
        if (!isConnecting) return@LaunchedEffect
        delay(ListenBrainzConnectUiTimeoutMs)
        isConnecting = false
    }
    val submitConnect = {
        if (token.isNotBlank() && credentialAvailability.canWrite && !isConnecting) {
            isConnecting = true
            onConnect(token.trim())
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Text("ListenBrainz", color = PhoebeUi.primaryText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            if (settings.connected) "Scrobbling as ${settings.username}" else "Connect first-party ListenBrainz scrobbling",
            color = PhoebeUi.secondaryText,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 3.dp, bottom = 12.dp),
        )
        if (settings.connected) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PhoebeUi.panel.copy(alpha = 0.52f))
                    .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                settings.username?.let { AccountDetailRow(label = "Username", value = it) }
                AccountDetailRow(label = "Storage", value = listenBrainzStorageLabel(settings.storageStatus, credentialAvailability))
                settings.lastNowPlayingSubmittedAtMs?.let {
                    AccountDetailRow(label = "Last now playing", value = formatLastPlayed(it, nowMs))
                }
                settings.lastListenSubmittedAtMs?.let {
                    AccountDetailRow(label = "Last listen", value = formatLastPlayed(it, nowMs))
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingsSwitchRow(
                title = "Now playing",
                subtitle = "Show the current track on ListenBrainz",
                checked = settings.submitNowPlaying,
                onCheckedChange = onSubmitNowPlaying,
            )
            SettingsSwitchRow(
                title = "Listen history",
                subtitle = "Submit after half the track or four minutes",
                checked = settings.submitListens,
                onCheckedChange = onSubmitListens,
            )
            SettingsSwitchRow(
                title = "Current-track feedback",
                subtitle = "Enable Love, Hate, and Clear when ListenBrainz returns an MSID",
                checked = settings.submitCurrentTrackFeedback,
                onCheckedChange = onSubmitCurrentTrackFeedback,
            )
            (settings.lastListenError ?: settings.lastError)?.let { error ->
                Text(error, color = PhoebeUi.accentLight, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            TextButton(onClick = onDisconnect, modifier = Modifier.align(Alignment.End)) {
                Text("Disconnect", color = PhoebeUi.accentLight, fontWeight = FontWeight.SemiBold)
            }
        } else {
            ListenBrainzTokenField(
                token = token,
                onTokenChange = { token = it },
                onSubmit = submitConnect,
                enabled = !isConnecting,
                placeholder = "User token",
                compact = compact,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                listenBrainzStorageNote(credentialAvailability),
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
            )
            if (isConnecting) {
                Text(
                    "Connecting…",
                    color = PhoebeUi.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            settings.lastError?.let { error ->
                Text(error, color = PhoebeUi.accentLight, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { openExternalUrl(ListenBrainzSettingsUrl) }) {
                    Text("Get token", color = PhoebeUi.secondaryText)
                }
                if (token.isNotBlank() && !isConnecting) {
                    TextButton(onClick = { token = "" }) {
                        Text("Clear", color = PhoebeUi.secondaryText)
                    }
                }
                TextButton(
                    enabled = token.isNotBlank() && credentialAvailability.canWrite && !isConnecting,
                    onClick = submitConnect,
                ) {
                    Text(
                        if (isConnecting) "Connecting…" else "Connect",
                        color = if (token.isNotBlank() && credentialAvailability.canWrite && !isConnecting) {
                            PhoebeUi.accentLight
                        } else {
                            PhoebeUi.mutedText
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun LastFmSettingsSection(
    appSettings: AppSettings,
    credentialAvailability: SecureCredentialAvailability,
    onStartAuthorization: (String, String) -> Unit,
    onFinishAuthorization: () -> Unit,
    onDisconnect: () -> Unit,
    onSubmitNowPlaying: (Boolean) -> Unit,
    onSubmitScrobbles: (Boolean) -> Unit,
    compact: Boolean,
) {
    val settings = appSettings.lastFm
    val nowMs = LocalNowMs.current
    var apiKey by remember(settings.connected) { mutableStateOf(settings.apiKey.orEmpty()) }
    var sharedSecret by remember(settings.connected) { mutableStateOf("") }
    var isConnecting by remember(settings.connected) { mutableStateOf(false) }
    var awaitingAuthorization by remember(settings.connected) { mutableStateOf(false) }
    var authorizationNotice by remember(settings.connected) { mutableStateOf<String?>(null) }
    LaunchedEffect(settings.connected, settings.lastValidatedAtMs, settings.lastError) {
        isConnecting = false
        if (settings.connected) {
            apiKey = settings.apiKey.orEmpty()
            sharedSecret = ""
            awaitingAuthorization = false
            authorizationNotice = null
        } else if (settings.lastError != null) {
            authorizationNotice = null
        }
    }
    LaunchedEffect(isConnecting, apiKey, sharedSecret, awaitingAuthorization) {
        if (!isConnecting) return@LaunchedEffect
        delay(ListenBrainzConnectUiTimeoutMs)
        isConnecting = false
    }
    val canStartAuthorization = apiKey.isNotBlank() && sharedSecret.isNotBlank()
    val startAuthorization = {
        when {
            apiKey.isBlank() -> authorizationNotice = "Enter a Last.fm API key."
            sharedSecret.isBlank() -> authorizationNotice = "Enter a Last.fm shared secret."
            !isConnecting -> {
                authorizationNotice = "A Last.fm approval page should open shortly. Return here and click Finish."
                awaitingAuthorization = true
                onStartAuthorization(apiKey.trim(), sharedSecret.trim())
            }
        }
    }
    val finishAuthorization = {
        if (awaitingAuthorization && !isConnecting) {
            isConnecting = true
            onFinishAuthorization()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Text("Last.fm", color = PhoebeUi.primaryText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            if (settings.connected) "Scrobbling as ${settings.username}" else "Connect optional Last.fm scrobbling",
            color = PhoebeUi.secondaryText,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 3.dp, bottom = 12.dp),
        )
        if (settings.connected) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PhoebeUi.panel.copy(alpha = 0.52f))
                    .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                settings.username?.let { AccountDetailRow(label = "Username", value = it) }
                AccountDetailRow(label = "Storage", value = listenBrainzStorageLabel(settings.storageStatus, credentialAvailability))
                settings.lastNowPlayingSubmittedAtMs?.let {
                    AccountDetailRow(label = "Last now playing", value = formatLastPlayed(it, nowMs))
                }
                settings.lastScrobbleSubmittedAtMs?.let {
                    AccountDetailRow(label = "Last scrobble", value = formatLastPlayed(it, nowMs))
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingsSwitchRow(
                title = "Now playing",
                subtitle = "Show the current track on Last.fm",
                checked = settings.submitNowPlaying,
                onCheckedChange = onSubmitNowPlaying,
            )
            SettingsSwitchRow(
                title = "Scrobbles",
                subtitle = "Submit after half the track or four minutes",
                checked = settings.submitScrobbles,
                onCheckedChange = onSubmitScrobbles,
            )
            (settings.lastScrobbleError ?: settings.lastError)?.let { error ->
                Text(error, color = PhoebeUi.accentLight, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            TextButton(onClick = onDisconnect, modifier = Modifier.align(Alignment.End)) {
                Text("Disconnect", color = PhoebeUi.accentLight, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ListenBrainzTokenField(apiKey, { apiKey = it }, startAuthorization, !isConnecting, "API key", compact)
                ListenBrainzTokenField(sharedSecret, { sharedSecret = it }, startAuthorization, !isConnecting, "Shared secret", compact)
            }
            Spacer(Modifier.height(8.dp))
            Text(listenBrainzStorageNote(credentialAvailability), color = PhoebeUi.secondaryText, fontSize = 12.sp)
            Text(
                authorizationNotice ?: if (awaitingAuthorization) {
                    "Approve Phoebe in the Last.fm browser page, then finish here."
                } else {
                    "Phoebe will open Last.fm and save the session key after authorization."
                },
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (isConnecting) {
                Text("Connecting...", color = PhoebeUi.secondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            settings.lastError?.let { error ->
                Text(error, color = PhoebeUi.accentLight, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { openExternalUrl(LastFmApiAccountsUrl) }) {
                    Text("API account", color = PhoebeUi.secondaryText)
                }
                TextButton(enabled = awaitingAuthorization && !isConnecting, onClick = finishAuthorization) {
                    Text(
                        "Finish",
                        color = if (awaitingAuthorization && !isConnecting) PhoebeUi.accentLight else PhoebeUi.mutedText,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(enabled = !isConnecting, onClick = startAuthorization) {
                    Text(
                        "Authorize",
                        color = if (!isConnecting && canStartAuthorization) PhoebeUi.accentLight else PhoebeUi.mutedText,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListenBrainzTokenField(
    token: String,
    onTokenChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
    placeholder: String,
    compact: Boolean,
) {
    BasicTextField(
        value = token,
        onValueChange = onTokenChange,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        visualTransformation = PasswordVisualTransformation(),
        textStyle = TextStyle(color = PhoebeUi.primaryText, fontSize = if (compact) 12.sp else 13.sp),
        cursorBrush = SolidColor(PhoebeUi.accentLight),
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PhoebeUi.panel.copy(alpha = 0.58f))
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (token.isBlank()) {
                    Text(placeholder, color = PhoebeUi.mutedText, fontSize = if (compact) 12.sp else 13.sp)
                }
                innerTextField()
            }
        },
    )
}

private fun listenBrainzStorageLabel(
    status: ListenBrainzCredentialStorageStatus,
    availability: SecureCredentialAvailability,
): String = when (status) {
    ListenBrainzCredentialStorageStatus.PersistentSecure -> availability.description
    ListenBrainzCredentialStorageStatus.PersistentBrowser -> availability.description
    ListenBrainzCredentialStorageStatus.SessionOnly -> "Session-only"
    ListenBrainzCredentialStorageStatus.Unavailable -> "Unavailable"
    ListenBrainzCredentialStorageStatus.Unknown -> availability.description
}

private fun listenBrainzStorageNote(availability: SecureCredentialAvailability): String =
    when (availability.status) {
        ListenBrainzCredentialStorageStatus.PersistentSecure ->
            "Token storage: ${availability.description}."
        ListenBrainzCredentialStorageStatus.PersistentBrowser ->
            "Token storage: encrypted browser storage. It survives reloads for this origin, but it is not a system keychain."
        ListenBrainzCredentialStorageStatus.SessionOnly ->
            "Token storage: session-only. Web users reconnect after reload."
        ListenBrainzCredentialStorageStatus.Unavailable ->
            availability.description
        ListenBrainzCredentialStorageStatus.Unknown ->
            "Token storage status will be checked when you connect."
    }

@Composable
private fun AccountDetailRow(
    label: String,
    value: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            color = PhoebeUi.mutedText,
            fontSize = 12.sp,
            modifier = Modifier.width(88.dp),
        )
        Text(
            value,
            color = PhoebeUi.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AboutSettingsCard(
    compact: Boolean = false,
    updateState: AppUpdateState = AppUpdateState.Idle,
    onCheckForUpdates: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
) {
    val checking = updateState == AppUpdateState.Checking
    val installing = updateState is AppUpdateState.Installing
    SettingsCard {
        Text("About Phoebe", color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "Version and project links",
            color = PhoebeUi.mutedText,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = if (compact) 10.dp else 14.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(PhoebeUi.subtleFill)
                .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            AccountDetailRow(label = "Version", value = PhoebeBuildInfo.versionName)
            AccountDetailRow(label = "Repository", value = "${PhoebeBuildInfo.githubOwner}/${PhoebeBuildInfo.githubRepo}")
        }
        Spacer(Modifier.height(if (compact) 12.dp else 14.dp))
        AboutUpdateRow(
            updateState = updateState,
            enabled = !checking && !installing,
            onCheckForUpdates = onCheckForUpdates,
            onInstallUpdate = onInstallUpdate,
        )
        Spacer(Modifier.height(8.dp))
        AboutLinkRow(
            title = "Project on GitHub",
            subtitle = "Source code, releases, and issues",
            linkLabel = "GitHub",
            onClick = { openExternalUrl(ProjectGitHubUrl) },
        )
        Spacer(Modifier.height(8.dp))
        AboutLinkRow(
            title = "Joe Roskopf",
            subtitle = "Creator of Phoebe",
            linkLabel = "joetr.com",
            onClick = { openExternalUrl(CreatorWebsiteUrl) },
        )
        Spacer(Modifier.height(if (compact) 12.dp else 14.dp))
        ImageCreditsSection()
    }
}

@Composable
private fun ImageCreditsSection() {
    Text("Image Credits", color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Text(
        "Mix and collection artwork from Unsplash",
        color = PhoebeUi.mutedText,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        UnsplashImageCredits.forEach { credit ->
            AboutLinkRow(
                title = credit.photographer,
                subtitle = credit.description,
                linkLabel = "Unsplash",
                onClick = { openExternalUrl(credit.url) },
            )
        }
    }
}

@Composable
private fun AboutUpdateRow(
    updateState: AppUpdateState,
    enabled: Boolean,
    onCheckForUpdates: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    val subtitle = when (updateState) {
        AppUpdateState.Idle -> "Check GitHub releases for a newer build"
        AppUpdateState.Checking -> "Checking GitHub releases..."
        AppUpdateState.Current -> "Phoebe is up to date"
        is AppUpdateState.Available -> "Version ${updateState.update.versionName} is available"
        is AppUpdateState.Installing -> updateState.message
        is AppUpdateState.Failed -> updateState.message
    }
    val buttonLabel = when (updateState) {
        AppUpdateState.Checking -> "Checking"
        is AppUpdateState.Available -> "Update"
        is AppUpdateState.Installing -> "Updating"
        else -> "Check"
    }
    val onClick = if (updateState is AppUpdateState.Available) onInstallUpdate else onCheckForUpdates
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Updates", color = PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            buttonLabel,
            color = if (enabled) PhoebeUi.accentLight else PhoebeUi.mutedText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun AboutLinkRow(
    title: String,
    subtitle: String,
    linkLabel: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            linkLabel,
            color = PhoebeUi.accentLight,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GenericPlaceholderCard(title: String, compact: Boolean = false) {
    SettingsCard {
        Text(title, color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
        Text("This section is not implemented yet.", color = PhoebeUi.secondaryText, fontSize = 13.sp)
    }
}

private const val ListenBrainzSettingsUrl = "https://listenbrainz.org/settings/"
private const val LastFmApiAccountsUrl = "https://www.last.fm/api/account/create"
private const val ListenBrainzConnectUiTimeoutMs = 50_000L
private const val ProjectGitHubUrl = "https://github.com/${PhoebeBuildInfo.githubOwner}/${PhoebeBuildInfo.githubRepo}"
private const val CreatorWebsiteUrl = "https://joetr.com"
private const val UnsplashReferral = "?utm_source=Phoebe&utm_medium=referral"

private data class UnsplashImageCredit(
    val photographer: String,
    val description: String,
    val url: String,
)

private val UnsplashImageCredits = listOf(
    UnsplashImageCredit(
        photographer = "Sašo Tušar",
        description = "Shallow focus photography of audio mixer",
        url = "https://unsplash.com/photos/shallow-focus-photography-of-audio-mixer-QtgGYlug6Cw$UnsplashReferral",
    ),
    UnsplashImageCredit(
        photographer = "Aditya Chinchure",
        description = "Group of people in front of stage",
        url = "https://unsplash.com/photos/group-of-people-in-front-of-stage-ZhQCZjr9fHo$UnsplashReferral",
    ),
    UnsplashImageCredit(
        photographer = "CARTIST",
        description = "Pile of cassette tapes",
        url = "https://unsplash.com/photos/pile-of-cassette-tapes-bq_GrIelfxk$UnsplashReferral",
    ),
    UnsplashImageCredit(
        photographer = "Clay Banks",
        description = "Black and white vinyl record",
        url = "https://unsplash.com/photos/black-and-white-vinyl-record-fEVaiLwWvlU$UnsplashReferral",
    ),
    UnsplashImageCredit(
        photographer = "Eric Krull",
        description = "Black vinyl record on black vinyl record",
        url = "https://unsplash.com/photos/black-vinyl-record-on-black-vinyl-record-fi3_lDi3qPE$UnsplashReferral",
    ),
    UnsplashImageCredit(
        photographer = "Chris",
        description = "Man in black t-shirt and hat playing guitar",
        url = "https://unsplash.com/photos/man-in-black-t-shirt-and-hat-playing-guitar-7WfcHibcR3Y$UnsplashReferral",
    ),
    UnsplashImageCredit(
        photographer = "Markus Spiske",
        description = "Closeup photo of green tree",
        url = "https://unsplash.com/photos/closeup-photo-of-green-tree-5Rr6Q48gJds$UnsplashReferral",
    ),
    UnsplashImageCredit(
        photographer = "Lucas Santos",
        description = "Black and gold chronograph watch",
        url = "https://unsplash.com/photos/black-and-gold-chronograph-watch-huRn8ECqADI$UnsplashReferral",
    ),
    UnsplashImageCredit(
        photographer = "Joanne Glaudemans",
        description = "A group of toys on a table",
        url = "https://unsplash.com/photos/a-group-of-toys-on-a-table-6bovWnOmi10$UnsplashReferral",
    ),
    UnsplashImageCredit(
        photographer = "Colin + Meg",
        description = "A collage of photos of people and animals",
        url = "https://unsplash.com/photos/a-collage-of-photos-of-people-and-animals-7CIIfsu6SSI$UnsplashReferral",
    ),
    UnsplashImageCredit(
        photographer = "Zachary Nelson",
        description = "Man holding wireless microphone",
        url = "https://unsplash.com/photos/man-holding-wireless-microphone-HPYk8X9hh34$UnsplashReferral",
    ),
    UnsplashImageCredit(
        photographer = "Chris Hardy",
        description = "A stack of magazines sitting on top of a wooden table",
        url = "https://unsplash.com/photos/a-stack-of-magazines-sitting-on-top-of-a-wooden-table-vjq0m95G16U$UnsplashReferral",
    ),
    UnsplashImageCredit(
        photographer = "Anish Prajapati",
        description = "Woman in white and black plaid shirt playing electric guitar",
        url = "https://unsplash.com/photos/woman-in-white-and-black-plaid-shirt-playing-electric-guitar-5Sxh_zg5Des$UnsplashReferral",
    ),
    UnsplashImageCredit(
        photographer = "Isabelle Farinelli Silva",
        description = "A black shelf with a bunch of CDs on it",
        url = "https://unsplash.com/photos/a-black-shelf-with-a-bunch-of-cds-on-it-IGrl4aw5VQ8$UnsplashReferral",
    ),
)

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PhoebeUi.panel)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(16.dp))
            .padding(20.dp),
        content = content,
    )
}
