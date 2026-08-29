package com.phoebe.app.data

import com.phoebe.app.domain.AppSettings
import com.phoebe.app.domain.LocalMetadataOverride
import com.phoebe.app.domain.SavedSearch
import com.phoebe.app.domain.SmartPlaylist
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class PhoebeBackupPackage(
    val version: Int = CurrentVersion,
    val settings: AppSettings = AppSettings.Default,
    val smartPlaylists: List<SmartPlaylist> = emptyList(),
    val savedSearches: List<SavedSearch> = emptyList(),
    val localMetadataOverrides: List<LocalMetadataOverride> = emptyList(),
) {
    companion object {
        const val CurrentVersion = 1
    }
}

data class BackupRestorePreview(
    val smartPlaylistCount: Int = 0,
    val savedSearchCount: Int = 0,
    val metadataOverrideCount: Int = 0,
)

enum class BackupRestoreMode {
    Merge,
    Replace,
}

@SingleIn(AppScope::class)
@Inject
class ImportExportService(
    private val appSettingsRepository: AppSettingsRepository,
    private val userArtifactsRepository: UserArtifactsRepository,
) {
    fun exportBackupPackage(): String =
        PhoebeDataJson.encodeToString(
            PhoebeBackupPackage(
                settings = appSettingsRepository.settings.value.copy(keepPlayingEnabled = false),
                smartPlaylists = userArtifactsRepository.smartPlaylists.value,
                savedSearches = userArtifactsRepository.savedSearches.value,
                localMetadataOverrides = userArtifactsRepository.metadataOverrides.value,
            ),
        )

    fun previewBackupPackage(payload: String): BackupRestorePreview {
        val backup = decodeBackup(payload) ?: return BackupRestorePreview()
        return BackupRestorePreview(
            smartPlaylistCount = backup.smartPlaylists.size,
            savedSearchCount = backup.savedSearches.size,
            metadataOverrideCount = backup.localMetadataOverrides.size,
        )
    }

    suspend fun restoreBackupPackage(payload: String, mode: BackupRestoreMode): BackupRestorePreview {
        val backup = decodeBackup(payload) ?: return BackupRestorePreview()
        if (mode == BackupRestoreMode.Replace) {
            userArtifactsRepository.clearUserArtifacts()
        }
        backup.smartPlaylists.forEach { userArtifactsRepository.upsertSmartPlaylist(it) }
        backup.savedSearches.forEach { userArtifactsRepository.upsertSavedSearch(it) }
        backup.localMetadataOverrides.forEach { userArtifactsRepository.upsertMetadataOverride(it) }
        appSettingsRepository.setDownloadPolicySettings(backup.settings.downloadPolicy)
        appSettingsRepository.setStreamingPolicySettings(backup.settings.streamingPolicy)
        appSettingsRepository.setAudioProcessingSettings(backup.settings.audioProcessing)
        appSettingsRepository.setListenBrainzSettings(backup.settings.listenBrainz)
        appSettingsRepository.setLastFmSettings(backup.settings.lastFm)
        appSettingsRepository.setCrossfadeSeconds(backup.settings.crossfadeSeconds)
        appSettingsRepository.setScanLibraryOnLaunch(backup.settings.scanLibraryOnLaunch)
        appSettingsRepository.setNotifyWhenDownloadFinishes(backup.settings.notifyWhenDownloadFinishes)
        appSettingsRepository.setNotifyOnTrackChange(backup.settings.notifyOnTrackChange)
        appSettingsRepository.setNowPlayingVisualizerPreset(backup.settings.nowPlayingVisualizerPreset)
        appSettingsRepository.setNowPlayingVisualizerInTvFrame(backup.settings.nowPlayingVisualizerInTvFrame)
        appSettingsRepository.setBlurredArtworkAppearance(backup.settings.blurredArtworkAppearance)
        appSettingsRepository.setFullBleedDetailArtwork(backup.settings.fullBleedDetailArtwork)
        appSettingsRepository.setTintedBackgroundGradient(backup.settings.tintedBackgroundGradient)
        return previewBackupPackage(payload)
    }

    private fun decodeBackup(payload: String): PhoebeBackupPackage? =
        try {
            PhoebeDataJson.decodeFromString<PhoebeBackupPackage>(payload)
                .takeIf { it.version <= PhoebeBackupPackage.CurrentVersion }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}
