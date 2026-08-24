package com.phoebe.app.player

import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.domain.AudioOutputDevice
import com.phoebe.app.domain.AudioProcessingCapabilities
import com.phoebe.app.domain.AudioProcessingSettings
import com.phoebe.app.domain.AudioAnalysisFrame
import com.phoebe.app.domain.PlayerState
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.StreamingPolicySettings
import com.phoebe.app.domain.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val EmptyAudioAnalysisState = MutableStateFlow(AudioAnalysisFrame.Empty)

interface AudioPlayer {
    val state: StateFlow<PlayerState>
    val audioAnalysis: StateFlow<AudioAnalysisFrame>
        get() = EmptyAudioAnalysisState
    val audioProcessingCapabilities: AudioProcessingCapabilities
        get() = AudioProcessingCapabilities()
    val outputDevices: StateFlow<List<AudioOutputDevice>>
        get() = EmptyAudioOutputDevicesState

    fun play(queue: List<Track>, startIndex: Int = 0)
    fun playShuffled(queue: List<Track>, startIndex: Int = 0) {
        play(queue, startIndex)
        setShuffle(true)
    }

    fun prepare(queue: List<Track>, startIndex: Int = 0, positionMs: Long = 0L) {
        play(queue, startIndex)
        if (positionMs > 0L) seekTo(positionMs)
    }

    /**
     * Stop audible output while keeping the current queue and position available for UI state.
     * Unlike [prepare], implementations may avoid loading the stream.
     */
    fun suspendPlayback(queue: List<Track>, startIndex: Int = 0, positionMs: Long = 0L) {
        prepare(queue, startIndex, positionMs)
    }

    fun togglePlayPause()
    fun clearQueue()
    /** Stop playback and discard the entire queue, including the current track. */
    fun stopPlayback()
    fun addToUpNext(track: Track)
    fun appendToQueue(tracks: List<Track>)
    fun moveUpNext(fromIndex: Int, toIndex: Int)
    fun removeUpNext(index: Int)
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun setShuffle(enabled: Boolean)
    fun setRepeat(mode: RepeatMode)
    fun setVolume(volume: Float)
    fun setCrossfadeDurationMs(durationMs: Long)
    fun setEqualizer(profile: EqualizerProfile)
    fun setAudioProcessing(settings: AudioProcessingSettings) = Unit
    fun setStreamingPolicy(settings: StreamingPolicySettings) = Unit
    fun setOutputDevice(id: String?) = Unit

    /**
     * Keep per-app output at unity while [updateReportedVolume] mirrors the OS level on the slider.
     */
    fun setUnityOutputVolume()

    /**
     * Update the volume value the UI reads from [state] without touching the underlying
     * platform output volume. Used when system volume drives the slider so we don't
     * double-attenuate.
     */
    fun updateReportedVolume(volume: Float)

    /**
     * Scales audible output by the OS mixer level (0..1) while [state.volume] stays the
     * in-app slider value. Used on desktop when hardware keys move PulseAudio/CoreAudio
     * but the slider only stores the app preference.
     */
    fun setSystemVolumeScale(scale: Float) = Unit

    fun close() = Unit

    /** Browser tab visibility; web uses this to resume queue playback after backgrounding. */
    fun onPageVisibilityChanged(visible: Boolean) = Unit
}

private val EmptyAudioOutputDevicesState = MutableStateFlow<List<AudioOutputDevice>>(emptyList())

expect fun createAudioPlayer(): AudioPlayer
