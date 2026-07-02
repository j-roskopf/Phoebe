package com.phoebe.app.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.domain.AppSettings
import com.phoebe.app.domain.AudioProcessingSettings
import com.phoebe.app.domain.DownloadPolicySettings
import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.domain.LastFmSettings
import com.phoebe.app.domain.ListenBrainzSettings
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@SingleIn(AppScope::class)
@Inject
class AppSettingsRepository(
    private val database: PhoebeDatabase,
) {
    private val mutableState = MutableStateFlow(AppSettings.Default)
    val settings: StateFlow<AppSettings> = mutableState.asStateFlow()
    private val saveMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun restore() {
        val row = withContext(Dispatchers.Default) {
            database.appSettingsQueries.selectCurrent().awaitAsOneOrNull()
        }
        mutableState.value = row?.toSettings() ?: AppSettings.Default
    }

    suspend fun setCrossfadeSeconds(seconds: Int) {
        updateAndSave { current ->
            current.copy(crossfadeSeconds = seconds)
        }
    }

    suspend fun setScanLibraryOnLaunch(enabled: Boolean) {
        updateAndSave { current ->
            current.copy(scanLibraryOnLaunch = enabled)
        }
    }

    suspend fun setNotifyWhenDownloadFinishes(enabled: Boolean) {
        updateAndSave { current ->
            current.copy(notifyWhenDownloadFinishes = enabled)
        }
    }

    suspend fun setPersistEqualizerSettings(enabled: Boolean, currentProfile: EqualizerProfile? = null) {
        updateAndSave { current ->
            val profile = (currentProfile ?: current.equalizerProfile).normalized()
            current.copy(
                persistEqualizerSettings = enabled,
                equalizerProfile = profile,
            )
        }
    }

    suspend fun setPersistVolumeSettings(enabled: Boolean, currentVolume: Float? = null) {
        updateAndSave { current ->
            val volume = (currentVolume ?: current.savedVolume).coerceIn(
                AppSettings.MinSavedVolume,
                AppSettings.MaxSavedVolume,
            )
            current.copy(
                persistVolumeSettings = enabled,
                savedVolume = volume,
            )
        }
    }

    suspend fun setSavedVolume(volume: Float) {
        updateAndSave { current ->
            current.copy(savedVolume = volume)
        }
    }

    suspend fun setEqualizerProfile(profile: EqualizerProfile) {
        updateAndSave { current ->
            current.copy(equalizerProfile = profile.normalized())
        }
    }

    suspend fun setNowPlayingVisualizerPreset(preset: NowPlayingVisualizerPreset) {
        updateAndSave { current ->
            current.copy(nowPlayingVisualizerPreset = preset)
        }
    }

    suspend fun setNowPlayingVisualizerInTvFrame(enabled: Boolean) {
        updateAndSave { current ->
            current.copy(nowPlayingVisualizerInTvFrame = enabled)
        }
    }

    suspend fun setBlurredArtworkAppearance(enabled: Boolean) {
        updateAndSave { current ->
            current.copy(blurredArtworkAppearance = enabled)
        }
    }

    suspend fun setFullBleedDetailArtwork(enabled: Boolean) {
        updateAndSave { current ->
            current.copy(fullBleedDetailArtwork = enabled)
        }
    }

    suspend fun setTintedBackgroundGradient(enabled: Boolean) {
        updateAndSave { current ->
            current.copy(tintedBackgroundGradient = enabled)
        }
    }

    suspend fun setListenBrainzSettings(settings: ListenBrainzSettings) {
        updateAndSave { current ->
            current.copy(listenBrainz = settings.normalized())
        }
    }

    suspend fun setLastFmSettings(settings: LastFmSettings) {
        updateAndSave { current ->
            current.copy(lastFm = settings.normalized())
        }
    }

    suspend fun setDownloadPolicySettings(settings: DownloadPolicySettings) {
        updateAndSave { current ->
            current.copy(downloadPolicy = settings.normalized())
        }
    }

    suspend fun setAudioProcessingSettings(settings: AudioProcessingSettings) {
        updateAndSave { current ->
            current.copy(audioProcessing = settings.normalized())
        }
    }

    suspend fun updateListenBrainzSettings(transform: (ListenBrainzSettings) -> ListenBrainzSettings) {
        updateAndSave { current ->
            current.copy(listenBrainz = transform(current.listenBrainz).normalized())
        }
    }

    suspend fun updateLastFmSettings(transform: (LastFmSettings) -> LastFmSettings) {
        updateAndSave { current ->
            current.copy(lastFm = transform(current.lastFm).normalized())
        }
    }

    fun resetInMemoryState() {
        mutableState.value = AppSettings.Default
    }

    private suspend fun updateAndSave(transform: (AppSettings) -> AppSettings) {
        withContext(NonCancellable + Dispatchers.Default) {
            saveMutex.withLock {
                val normalized = transform(mutableState.value).normalized()
                database.appSettingsQueries.upsert(
                    crossfadeSeconds = normalized.crossfadeSeconds.toLong(),
                    scanLibraryOnLaunch = normalized.scanLibraryOnLaunch.toDb(),
                    notifyWhenDownloadFinishes = normalized.notifyWhenDownloadFinishes.toDb(),
                    persistEqualizerSettings = normalized.persistEqualizerSettings.toDb(),
                    persistVolumeSettings = normalized.persistVolumeSettings.toDb(),
                    savedVolume = normalized.savedVolume.toDouble(),
                    equalizerProfile = json.encodeToString(normalized.equalizerProfile),
                    nowPlayingVisualizerPreset = normalized.nowPlayingVisualizerPreset.name,
                    nowPlayingVisualizerInTvFrame = normalized.nowPlayingVisualizerInTvFrame.toDb(),
                    blurredArtworkAppearance = normalized.blurredArtworkAppearance.toDb(),
                    fullBleedDetailArtwork = normalized.fullBleedDetailArtwork.toDb(),
                    tintedBackgroundGradient = normalized.tintedBackgroundGradient.toDb(),
                    listenBrainzSettings = json.encodeToString(normalized.listenBrainz),
                    lastFmSettings = json.encodeToString(normalized.lastFm),
                    downloadPolicySettings = json.encodeToString(normalized.downloadPolicy),
                    audioProcessingSettings = json.encodeToString(normalized.audioProcessing),
                )
                mutableState.value = normalized
            }
        }
    }

    private fun com.phoebe.app.db.AppSettingsRow.toSettings(): AppSettings =
        AppSettings(
            crossfadeSeconds = crossfadeSeconds.toInt(),
            scanLibraryOnLaunch = scanLibraryOnLaunch.toBool(),
            notifyWhenDownloadFinishes = notifyWhenDownloadFinishes.toBool(),
            persistEqualizerSettings = persistEqualizerSettings.toBool(),
            persistVolumeSettings = persistVolumeSettings.toBool(),
            savedVolume = savedVolume.toFloat(),
            equalizerProfile = decodeEqualizerProfile(equalizerProfile),
            nowPlayingVisualizerPreset = NowPlayingVisualizerPreset.fromStoredName(nowPlayingVisualizerPreset),
            nowPlayingVisualizerInTvFrame = nowPlayingVisualizerInTvFrame.toBool(),
            blurredArtworkAppearance = blurredArtworkAppearance.toBool(),
            fullBleedDetailArtwork = fullBleedDetailArtwork.toBool(),
            tintedBackgroundGradient = tintedBackgroundGradient.toBool(),
            listenBrainz = decodeListenBrainzSettings(listenBrainzSettings),
            lastFm = decodeLastFmSettings(lastFmSettings),
            downloadPolicy = decodeDownloadPolicySettings(downloadPolicySettings),
            audioProcessing = decodeAudioProcessingSettings(audioProcessingSettings),
        ).normalized()

    private fun decodeEqualizerProfile(value: String): EqualizerProfile =
        try {
            json.decodeFromString<EqualizerProfile>(value).normalized()
        } catch (_: SerializationException) {
            EqualizerProfile.Default
        } catch (_: IllegalArgumentException) {
            EqualizerProfile.Default
        }

    private fun decodeListenBrainzSettings(value: String): ListenBrainzSettings =
        try {
            json.decodeFromString<ListenBrainzSettings>(value).normalized()
        } catch (_: SerializationException) {
            ListenBrainzSettings.Disconnected
        } catch (_: IllegalArgumentException) {
            ListenBrainzSettings.Disconnected
        }

    private fun decodeLastFmSettings(value: String): LastFmSettings =
        try {
            json.decodeFromString<LastFmSettings>(value).normalized()
        } catch (_: SerializationException) {
            LastFmSettings.Disconnected
        } catch (_: IllegalArgumentException) {
            LastFmSettings.Disconnected
        }

    private fun decodeDownloadPolicySettings(value: String): DownloadPolicySettings =
        try {
            json.decodeFromString<DownloadPolicySettings>(value).normalized()
        } catch (_: SerializationException) {
            DownloadPolicySettings()
        } catch (_: IllegalArgumentException) {
            DownloadPolicySettings()
        }

    private fun decodeAudioProcessingSettings(value: String): AudioProcessingSettings =
        try {
            json.decodeFromString<AudioProcessingSettings>(value).normalized()
        } catch (_: SerializationException) {
            AudioProcessingSettings()
        } catch (_: IllegalArgumentException) {
            AudioProcessingSettings()
        }
}

private fun Boolean.toDb(): Long = if (this) 1L else 0L
private fun Long.toBool(): Boolean = this != 0L
