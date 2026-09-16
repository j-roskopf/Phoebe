package com.phoebe.app.feature.playback

import androidx.lifecycle.ViewModel
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Inject
class MobilePlaybackViewModel : ViewModel() {
    private val mutableState = MutableStateFlow<MobilePlaybackRouteState?>(null)
    val state: StateFlow<MobilePlaybackRouteState?> = mutableState.asStateFlow()

    fun update(state: MobilePlaybackRouteState) {
        mutableState.value = state
    }

    fun onDragStart() {
        mutableState.update { it?.copy(expansionFraction = it.expansionFraction.coerceIn(0f, 1f)) }
    }

    fun onDrag(delta: Float) {
        mutableState.update { it?.copy(expansionFraction = (it.expansionFraction + delta).coerceIn(0f, 1f)) }
    }

    fun onDragEnd(targetFraction: Float) {
        mutableState.update { it?.copy(expansionFraction = targetFraction.coerceIn(0f, 1f)) }
    }
}

@Inject
class QueueViewModel : ViewModel() {
    private val mutableState = MutableStateFlow<QueueRouteState?>(null)
    val state: StateFlow<QueueRouteState?> = mutableState.asStateFlow()

    fun update(state: QueueRouteState) {
        mutableState.value = state
    }
}

@Inject
class DesktopVisualizerViewModel : ViewModel() {
    private val mutableState = MutableStateFlow<DesktopVisualizerRouteState?>(null)
    val state: StateFlow<DesktopVisualizerRouteState?> = mutableState.asStateFlow()

    fun update(state: DesktopVisualizerRouteState) {
        mutableState.value = state
    }

    fun onPreset(preset: NowPlayingVisualizerPreset) {
        mutableState.update { it?.copy(preset = preset) }
    }
}
