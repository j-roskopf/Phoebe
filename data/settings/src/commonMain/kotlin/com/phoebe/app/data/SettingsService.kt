package com.phoebe.app.data

import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.domain.RecentSearchItem
import com.phoebe.app.domain.EventSettings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@Inject
class SettingsService(
    private val appSettingsRepository: AppSettingsRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val userArtifactsRepository: UserArtifactsRepository,
) {
    suspend fun prependRecentSearch(item: RecentSearchItem) {
        searchHistoryRepository.prepend(item)
    }

    suspend fun removeRecentSearch(item: RecentSearchItem) {
        searchHistoryRepository.remove(item)
    }

    suspend fun clearRecentSearches() {
        searchHistoryRepository.clear()
    }

    suspend fun clearUserArtifacts() {
        userArtifactsRepository.clearUserArtifacts()
    }

    suspend fun setCrossfadeSeconds(seconds: Int) {
        appSettingsRepository.setCrossfadeSeconds(seconds)
    }

    suspend fun setScanLibraryOnLaunch(enabled: Boolean) {
        appSettingsRepository.setScanLibraryOnLaunch(enabled)
    }

    suspend fun setNotifyWhenDownloadFinishes(enabled: Boolean) {
        appSettingsRepository.setNotifyWhenDownloadFinishes(enabled)
    }

    suspend fun setKeepPlayingEnabled(enabled: Boolean) {
        appSettingsRepository.setKeepPlayingEnabled(enabled)
    }

    suspend fun setNowPlayingVisualizerPreset(preset: NowPlayingVisualizerPreset) {
        appSettingsRepository.setNowPlayingVisualizerPreset(preset)
    }

    suspend fun setNowPlayingVisualizerInTvFrame(enabled: Boolean) {
        appSettingsRepository.setNowPlayingVisualizerInTvFrame(enabled)
    }

    suspend fun setShowUltimateGuitarButton(enabled: Boolean) {
        appSettingsRepository.setShowUltimateGuitarButton(enabled)
    }

    suspend fun setBlurredArtworkAppearance(enabled: Boolean) {
        appSettingsRepository.setBlurredArtworkAppearance(enabled)
    }

    suspend fun setFullBleedDetailArtwork(enabled: Boolean) {
        appSettingsRepository.setFullBleedDetailArtwork(enabled)
    }

    suspend fun setTintedBackgroundGradient(enabled: Boolean) {
        appSettingsRepository.setTintedBackgroundGradient(enabled)
    }

    suspend fun setEventSettings(settings: EventSettings) {
        appSettingsRepository.setEventSettings(settings)
    }
}
