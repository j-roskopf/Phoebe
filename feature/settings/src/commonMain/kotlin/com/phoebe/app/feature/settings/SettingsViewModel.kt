package com.phoebe.app.feature.settings

import androidx.lifecycle.ViewModel
import com.phoebe.app.domain.HomeSection
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.MobileBottomTab
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.domain.PersonalMixPreferences
import com.phoebe.app.ui.HomeScreenLayoutMode
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Inject
class SettingsViewModel : ViewModel() {
    private val mutableState = MutableStateFlow<SettingsRouteState?>(null)
    val state: StateFlow<SettingsRouteState?> = mutableState.asStateFlow()

    fun update(state: SettingsRouteState) {
        mutableState.value = state
    }

    fun onLightModeChange(enabled: Boolean) {
        mutableState.update { it?.copy(isLightMode = enabled) }
    }

    fun onTintChange(tintId: String) {
        mutableState.update { it?.copy(tintId = tintId) }
    }

    fun onDownloadDirectory(uri: String?) {
        mutableState.update { it?.copy(downloadDirectory = uri) }
    }

    fun onCrossfadeSeconds(seconds: Int) {
        mutableState.update { it?.copy(appSettings = it.appSettings.copy(crossfadeSeconds = seconds)) }
    }

    fun onScanLibraryOnLaunch(enabled: Boolean) {
        mutableState.update { it?.copy(appSettings = it.appSettings.copy(scanLibraryOnLaunch = enabled)) }
    }

    fun onNotifyWhenDownloadFinishes(enabled: Boolean) {
        mutableState.update { it?.copy(appSettings = it.appSettings.copy(notifyWhenDownloadFinishes = enabled)) }
    }

    fun onPersistEqualizerSettings(enabled: Boolean) {
        mutableState.update { it?.copy(appSettings = it.appSettings.copy(persistEqualizerSettings = enabled)) }
    }

    fun onPersistVolumeSettings(enabled: Boolean) {
        mutableState.update { it?.copy(appSettings = it.appSettings.copy(persistVolumeSettings = enabled)) }
    }

    fun onVisualizerPreset(preset: NowPlayingVisualizerPreset) {
        mutableState.update { it?.copy(appSettings = it.appSettings.copy(nowPlayingVisualizerPreset = preset)) }
    }

    fun onShowVisualizerInTvFrame(enabled: Boolean) {
        mutableState.update { it?.copy(appSettings = it.appSettings.copy(nowPlayingVisualizerInTvFrame = enabled)) }
    }

    fun onBlurredArtworkAppearance(enabled: Boolean) {
        mutableState.update { it?.copy(appSettings = it.appSettings.copy(blurredArtworkAppearance = enabled)) }
    }

    fun onTintedBackgroundGradient(enabled: Boolean) {
        mutableState.update { it?.copy(appSettings = it.appSettings.copy(tintedBackgroundGradient = enabled)) }
    }

    fun onHomeSections(sections: List<HomeSection>) {
        mutableState.update { it?.copy(libraryUi = it.libraryUi.copy(homeSections = sections)) }
    }

    fun onMobileBottomTabs(tabs: List<MobileBottomTab>) {
        mutableState.update { it?.copy(libraryUi = it.libraryUi.copy(mobileBottomTabs = tabs)) }
    }

    fun onPersonalMix(preferences: PersonalMixPreferences) {
        mutableState.update { it?.copy(libraryUi = it.libraryUi.copy(personalMix = preferences)) }
    }

    fun onAlbumGridItemSize(size: Int) {
        mutableState.update { it?.copy(libraryUi = it.libraryUi.copy(albumGridItemSizeDp = size)) }
    }

    fun onArtistGridItemSize(size: Int) {
        mutableState.update { it?.copy(libraryUi = it.libraryUi.copy(artistGridItemSizeDp = size)) }
    }

    fun onLibraryColumns(columns: LibraryColumnVisibility) {
        mutableState.update { it?.copy(libraryUi = it.libraryUi.copy(columns = columns)) }
    }

    fun onHomeScreenLayoutMode(mode: HomeScreenLayoutMode) {
        mutableState.update { it?.copy(homeScreenLayoutMode = mode) }
    }
}
