package com.phoebe.app.feature.playback

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.phoebe.app.data.ListenBrainzFeedbackScore
import com.phoebe.app.data.ListenBrainzFeedbackTarget
import com.phoebe.app.domain.AudioAnalysisFrame
import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.player.CastState

@Immutable
data class MobilePlaybackRouteState(
    val track: Track?,
    val upNext: List<Track>,
    val previousTrack: Track? = null,
    val isPlaying: Boolean,
    val isBuffering: Boolean = false,
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val positionMs: Long,
    val bufferedPositionMs: Long,
    val currentIndex: Int,
    val castState: CastState = CastState(),
    val remotePlaybackTarget: String? = null,
    val listenBrainzFeedbackTarget: ListenBrainzFeedbackTarget = ListenBrainzFeedbackTarget(),
    val equalizerProfile: EqualizerProfile = EqualizerProfile.Default,
    val persistEqualizerSettings: Boolean = false,
    val equalizerRemoteUnavailable: Boolean = false,
    val visualizerPreset: NowPlayingVisualizerPreset = NowPlayingVisualizerPreset.Default,
    val showVisualizerInTvFrame: Boolean = false,
    val blurredArtworkAppearance: Boolean = true,
    val tintedBackgroundGradient: Boolean = false,
    val audioAnalysis: AudioAnalysisFrame = AudioAnalysisFrame.Empty,
    val useFilamentVisualizers: Boolean = true,
    val handleSystemBack: Boolean = true,
    val initialUpNextExpanded: Boolean = false,
    val expansionFraction: Float = 0f,
)

class MobilePlaybackRouteActions(
    val onToggle: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onShuffle: () -> Unit,
    val onRepeat: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onPlayQueue: (Int) -> Unit,
    val onMoveUpNext: (Int, Int) -> Unit,
    val onRemoveUpNext: (Int) -> Unit,
    val onBack: () -> Unit,
    val onSwipeDismiss: () -> Unit,
    val onSkipQueueBy: (Int) -> Unit = {},
    val onOpenSongDetail: (Track) -> Unit = {},
    val onCast: () -> Unit = {},
    val onLyrics: () -> Unit = {},
    val onEqualizerEnabled: (Boolean) -> Unit = {},
    val onEqualizerBandCount: (Int) -> Unit = {},
    val onEqualizerGain: (Int, Float) -> Unit = { _, _ -> },
    val onEqualizerReset: () -> Unit = {},
    val onPersistEqualizerSettings: (Boolean) -> Unit = {},
    val onVisualizerPreset: (NowPlayingVisualizerPreset) -> Unit = {},
    val onShowVisualizerInTvFrame: (Boolean) -> Unit = {},
    val onListenBrainzFeedback: (ListenBrainzFeedbackScore) -> Unit = {},
    val onDragStart: () -> Unit = {},
    val onDrag: (Float) -> Unit = {},
    val onDragEnd: (Float) -> Unit = {},
    val onClick: () -> Unit = {},
)

@Immutable
data class QueueRouteState(
    val upNext: List<Track>,
    val currentTrack: Track?,
    val repeat: RepeatMode,
    val currentTrackClickOpensDetail: Boolean = false,
)

class QueueRouteActions(
    val onPlayQueue: (Int) -> Unit,
    val onClearQueue: () -> Unit,
    val onMoveUpNext: (Int, Int) -> Unit,
    val onRemoveUpNext: (Int) -> Unit,
    val onOpenTrackDetail: (Track) -> Unit = {},
)

@Immutable
data class DesktopVisualizerRouteState(
    val track: Track?,
    val preset: NowPlayingVisualizerPreset,
    val showInTvFrame: Boolean = false,
    val audioAnalysis: AudioAnalysisFrame,
    val isPlaying: Boolean,
    val positionMs: Long,
    val useFilamentVisualizers: Boolean = true,
)

@Composable
fun MobilePlaybackRoute(
    state: MobilePlaybackRouteState,
    actions: MobilePlaybackRouteActions,
    modifier: Modifier = Modifier,
) {
    MobilePlayer(
        track = state.track,
        upNext = state.upNext,
        previousTrack = state.previousTrack,
        isPlaying = state.isPlaying,
        isBuffering = state.isBuffering,
        shuffle = state.shuffle,
        repeat = state.repeat,
        positionMs = state.positionMs,
        bufferedPositionMs = state.bufferedPositionMs,
        currentIndex = state.currentIndex,
        castState = state.castState,
        remotePlaybackTarget = state.remotePlaybackTarget,
        listenBrainzFeedbackTarget = state.listenBrainzFeedbackTarget,
        equalizerProfile = state.equalizerProfile,
        persistEqualizerSettings = state.persistEqualizerSettings,
        equalizerRemoteUnavailable = state.equalizerRemoteUnavailable,
        visualizerPreset = state.visualizerPreset,
        showVisualizerInTvFrame = state.showVisualizerInTvFrame,
        blurredArtworkAppearance = state.blurredArtworkAppearance,
        tintedBackgroundGradient = state.tintedBackgroundGradient,
        audioAnalysis = state.audioAnalysis,
        useFilamentVisualizers = state.useFilamentVisualizers,
        onToggle = actions.onToggle,
        onPrevious = actions.onPrevious,
        onNext = actions.onNext,
        onSkipQueueBy = actions.onSkipQueueBy,
        onShuffle = actions.onShuffle,
        onRepeat = actions.onRepeat,
        onSeek = actions.onSeek,
        onPlayQueue = actions.onPlayQueue,
        onMoveUpNext = actions.onMoveUpNext,
        onRemoveUpNext = actions.onRemoveUpNext,
        onOpenSongDetail = actions.onOpenSongDetail,
        onCast = actions.onCast,
        onLyrics = actions.onLyrics,
        onEqualizerEnabled = actions.onEqualizerEnabled,
        onEqualizerBandCount = actions.onEqualizerBandCount,
        onEqualizerGain = actions.onEqualizerGain,
        onEqualizerReset = actions.onEqualizerReset,
        onPersistEqualizerSettings = actions.onPersistEqualizerSettings,
        onVisualizerPreset = actions.onVisualizerPreset,
        onShowVisualizerInTvFrame = actions.onShowVisualizerInTvFrame,
        onListenBrainzFeedback = actions.onListenBrainzFeedback,
        onBack = actions.onBack,
        onSwipeDismiss = actions.onSwipeDismiss,
        onClick = actions.onClick,
        handleSystemBack = state.handleSystemBack,
        initialUpNextExpanded = state.initialUpNextExpanded,
        expansionFraction = state.expansionFraction,
        onDragStart = actions.onDragStart,
        onDrag = actions.onDrag,
        onDragEnd = actions.onDragEnd,
        modifier = modifier,
    )
}

@Composable
fun QueueRoute(
    state: QueueRouteState,
    actions: QueueRouteActions,
    modifier: Modifier,
    listState: LazyListState,
) {
    QueuePanel(
        upNext = state.upNext,
        currentTrack = state.currentTrack,
        repeat = state.repeat,
        modifier = modifier,
        onPlayQueue = actions.onPlayQueue,
        onClearQueue = actions.onClearQueue,
        onMoveUpNext = actions.onMoveUpNext,
        onRemoveUpNext = actions.onRemoveUpNext,
        onOpenTrackDetail = actions.onOpenTrackDetail,
        currentTrackClickOpensDetail = state.currentTrackClickOpensDetail,
        listState = listState,
    )
}

@Composable
fun DesktopVisualizerRoute(
    state: DesktopVisualizerRouteState,
    onPreset: (NowPlayingVisualizerPreset) -> Unit,
    onShowInTvFrameChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    DesktopNowPlayingVisualizerView(
        track = state.track,
        preset = state.preset,
        audioAnalysis = state.audioAnalysis,
        isPlaying = state.isPlaying,
        positionMs = state.positionMs,
        onPreset = onPreset,
        modifier = modifier,
        useFilamentVisualizers = state.useFilamentVisualizers,
        showInTvFrame = state.showInTvFrame,
        onShowInTvFrameChange = onShowInTvFrameChange,
    )
}
