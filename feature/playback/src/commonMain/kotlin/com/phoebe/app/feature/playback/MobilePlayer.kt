package com.phoebe.app.feature.playback

import com.phoebe.app.ui.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoebe.app.data.ListenBrainzFeedbackScore
import com.phoebe.app.data.ListenBrainzFeedbackTarget
import com.phoebe.app.domain.AudioAnalysisFrame
import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.canTogglePlexLike
import com.phoebe.app.feature.playback.EqualizerDialog
import com.phoebe.app.platform.isDesktopPlatform
import com.phoebe.app.player.CastState
import kotlin.math.abs
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MobilePlayerContinuousMotionDelayMs = 240L
private val MobilePlayerMetadataReserveWithAlbum = 104.dp
private val MobilePlayerCompactMetadataReserveWithAlbum = 88.dp
private val MobilePlayerMetadataReserveWithoutAlbum = 84.dp
private val MobilePlayerCompactMetadataReserveWithoutAlbum = 72.dp
private val MobilePlayerRemoteTargetReserve = 18.dp
private val MobilePlayerCompactRemoteTargetReserve = 14.dp
private val MobilePlayerExpandedTopGap = 8.dp
private val MobilePlayerExpandedArtworkBodyGap = 8.dp
private val MobilePlayerExpandedProgressLineHeight = 72.dp
private val MobilePlayerExpandedControlsGap = 10.dp
private val MobilePlayerCompactControlsGap = 4.dp
private val MobilePlayerExpandedUtilityControlsHeight = 44.dp
private val MobilePlayerCompactUtilityControlsHeight = 38.dp
private val MobilePlayerExpandedQueueGap = 22.dp
private val MobilePlayerCompactQueueGap = 18.dp
private val MobilePlayerExpandedPlayButtonSize = 64.dp
private val MobilePlayerCompactPlayButtonSize = 56.dp
private val MobilePlayerExpandedCompactRange = 56.dp
private val CollapsedMobilePlayerMetadataHeight = 34.dp

@Composable
private fun rememberRetainedMobilePlayerUpNextSheetState(
    key: String,
    initiallyExpanded: Boolean,
): MobilePlayerUpNextSheetState =
    remember(key) {
        RetainedMobilePlayerUpNextSheetStates.getOrPut(
            key = key,
            initiallyExpanded = initiallyExpanded,
        )
    }

private object RetainedMobilePlayerUpNextSheetStates {
    private val cache = mutableMapOf<String, MobilePlayerUpNextSheetState>()

    fun getOrPut(key: String, initiallyExpanded: Boolean): MobilePlayerUpNextSheetState =
        cache.getOrPut(key) { MobilePlayerUpNextSheetState(if (initiallyExpanded) 1f else 0f) }
}

private class MobilePlayerUpNextSheetState(initialProgress: Float) {
    var progress by mutableFloatStateOf(initialProgress.coerceIn(0f, 1f))
}

@Composable
private fun MobileExpandedSheetChrome(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .width(38.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.62f)),
        )
    }
}

@Composable
private fun MobileExpandedUtilityControls(
    castState: CastState,
    equalizerActive: Boolean,
    visualizerPreset: NowPlayingVisualizerPreset,
    showVisualizerInTvFrame: Boolean,
    onCast: () -> Unit,
    onEqualizer: () -> Unit,
    onLyrics: () -> Unit,
    onVisualizerPreset: (NowPlayingVisualizerPreset) -> Unit,
    onShowVisualizerInTvFrame: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MobileUtilityControl {
            CastIcon(
                active = castState.isConnected,
                loading = castState.isBuffering,
                enabled = castState.isAvailable || castState.isConnected,
                onClick = onCast,
            )
        }
        MobileUtilityControl {
            TransportIcon(PhoebeIcon.Equalizer, "Equalizer", onEqualizer, active = equalizerActive)
        }
        MobileUtilityControl {
            TransportIcon(PhoebeIcon.Lyrics, "Lyrics", onLyrics)
        }
        MobileUtilityControl {
            VisualizerPresetButton(
                selected = visualizerPreset,
                onSelected = onVisualizerPreset,
                showInTvFrame = showVisualizerInTvFrame,
                onShowInTvFrameChange = onShowVisualizerInTvFrame,
            )
        }
    }
}

@Composable
private fun MobileUtilityControl(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.width(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun MobilePlayer(
    track: Track?,
    upNext: List<Track>,
    previousTrack: Track? = null,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    shuffle: Boolean,
    repeat: RepeatMode,
    positionMs: Long,
    bufferedPositionMs: Long,
    @Suppress("UNUSED_PARAMETER") currentIndex: Int,
    castState: CastState = CastState(),
    remotePlaybackTarget: String? = null,
    listenBrainzFeedbackTarget: ListenBrainzFeedbackTarget = ListenBrainzFeedbackTarget(),
    equalizerProfile: EqualizerProfile = EqualizerProfile.Default,
    persistEqualizerSettings: Boolean = false,
    equalizerRemoteUnavailable: Boolean = false,
    visualizerPreset: NowPlayingVisualizerPreset = NowPlayingVisualizerPreset.Default,
    showVisualizerInTvFrame: Boolean = false,
    blurredArtworkAppearance: Boolean = true,
    tintedBackgroundGradient: Boolean = false,
    audioAnalysis: AudioAnalysisFrame = AudioAnalysisFrame.Empty,
    useFilamentVisualizers: Boolean = true,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSkipQueueBy: (Int) -> Unit = {},
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayQueue: (Int) -> Unit,
    onMoveUpNext: (Int, Int) -> Unit,
    onRemoveUpNext: (Int) -> Unit,
    onOpenSongDetail: (Track) -> Unit = {},
    onCast: () -> Unit = {},
    onLyrics: () -> Unit = {},
    onEqualizerEnabled: (Boolean) -> Unit = {},
    onEqualizerBandCount: (Int) -> Unit = {},
    onEqualizerGain: (Int, Float) -> Unit = { _, _ -> },
    onEqualizerReset: () -> Unit = {},
    onPersistEqualizerSettings: (Boolean) -> Unit = {},
    onVisualizerPreset: (NowPlayingVisualizerPreset) -> Unit = {},
    onShowVisualizerInTvFrame: (Boolean) -> Unit = {},
    onListenBrainzFeedback: (ListenBrainzFeedbackScore) -> Unit = {},
    onBack: () -> Unit,
    onSwipeDismiss: () -> Unit,
    onClick: () -> Unit = {},
    handleSystemBack: Boolean = true,
    initialUpNextExpanded: Boolean = false,
    expansionFraction: Float = 0f,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val timelineBufferedPositionMs = rememberTimelineBufferedPositionMs(
        track = track,
        positionMs = positionMs,
        bufferedPositionMs = bufferedPositionMs,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
    )
    val retainedSheetState = rememberRetainedMobilePlayerUpNextSheetState(
        key = "mobile-player-up-next-sheet",
        initiallyExpanded = initialUpNextExpanded,
    )
    val upNextListState = RetainedLazyListStates.remember("mobile-player-up-next-list")
    val horizontalSettleOffset = remember { Animatable(0f) }
    var horizontalDragOffset by remember { mutableFloatStateOf(0f) }
    var horizontalIsDragging by remember { mutableStateOf(false) }
    var horizontalSettleJob by remember { mutableStateOf<Job?>(null) }
    var horizontalSwipePreviewDirection by remember { mutableStateOf(0) }

    val currentTrackId = track?.id
    var lastTrackId by remember { mutableStateOf(currentTrackId) }
    var synchronousSwipeOffsetReset by remember { mutableStateOf(false) }

    if (currentTrackId != lastTrackId) {
        lastTrackId = currentTrackId
        horizontalDragOffset = 0f
        horizontalSwipePreviewDirection = 0
        synchronousSwipeOffsetReset = true
        horizontalSettleJob?.cancel()
    }

    LaunchedEffect(currentTrackId) {
        horizontalSettleOffset.snapTo(0f)
        synchronousSwipeOffsetReset = false
    }

    val inheritedContinuousMotionEnabled = LocalContinuousMotionEnabled.current
    var playerContinuousMotionEnabled by remember(track?.id) { mutableStateOf(false) }
    val playerMotionEnabled = inheritedContinuousMotionEnabled && playerContinuousMotionEnabled
    LaunchedEffect(track?.id, inheritedContinuousMotionEnabled) {
        playerContinuousMotionEnabled = false
        if (track != null && inheritedContinuousMotionEnabled) {
            delay(MobilePlayerContinuousMotionDelayMs)
            playerContinuousMotionEnabled = true
        }
    }
    var equalizerOpen by remember { mutableStateOf(false) }
    val trackNavigationActions = LocalTrackNavigationActions.current
    val likeActions = LocalLikeActions.current
    if (equalizerOpen) {
        EqualizerDialog(
            profile = equalizerProfile,
            persistEnabled = persistEqualizerSettings,
            remoteUnavailable = equalizerRemoteUnavailable,
            onEnabledChange = onEqualizerEnabled,
            onBandCountChange = onEqualizerBandCount,
            onGainChange = onEqualizerGain,
            onReset = onEqualizerReset,
            onPersistChange = onPersistEqualizerSettings,
            onDismiss = { equalizerOpen = false },
        )
    }

    PlatformBackHandler(
        enabled = handleSystemBack,
        onBack = { onBack() }
    )

    val clampedExpansionFraction = expansionFraction.coerceIn(0f, 1f)
    val navBarColor = PhoebeUi.navBar
    val shellRadialTint = PhoebeUi.shellRadialTint
    val shellTop = PhoebeUi.shellTop
    val canvasBackground = PhoebeUi.canvasBackground
    val borderColor = PhoebeUi.border
    val collapsedChromeAlpha = (1f - clampedExpansionFraction * 3f).coerceIn(0f, 1f)
    val expandedTopInset = if (isDesktopPlatform()) {
        0.dp
    } else {
        windowTopPadding() + MobilePlayerExpandedTopGap
    }
    val currentTopInset = lerp(0.dp, expandedTopInset, clampedExpansionFraction)

    val cornerRadius = lerp(14.dp, 26.dp, clampedExpansionFraction)
    val containerShape = RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius)

    BoxWithConstraints(
        modifier = modifier
            .padding(top = currentTopInset)
            .playerDragGestures(
                expansionFraction = clampedExpansionFraction,
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = onDragEnd,
            )
            .clip(containerShape)
            .border(
                width = 1.dp,
                color = borderColor.copy(alpha = borderColor.alpha * (1f - clampedExpansionFraction)),
                shape = containerShape,
            )
            .drawBehind {
                if (clampedExpansionFraction > 0f) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                shellTop,
                                canvasBackground.copy(alpha = 0.94f),
                                canvasBackground,
                            ),
                        )
                    )
                    if (tintedBackgroundGradient) {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(shellRadialTint.copy(alpha = clampedExpansionFraction), Color.Transparent),
                                center = Offset(210f * this.density, 50f * this.density),
                                radius = 520f * this.density,
                            )
                        )
                    }
                }
                if (collapsedChromeAlpha > 0f) {
                    drawRect(color = navBarColor.copy(alpha = collapsedChromeAlpha))
                }
            }
    ) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight

        val collapsedPlayButtonSize = 40.dp

        val expandedBaseMetadataReserve = if (track != null && track.album.isNotBlank()) {
            MobilePlayerMetadataReserveWithAlbum
        } else {
            MobilePlayerMetadataReserveWithoutAlbum
        }
        val compactBaseMetadataReserve = if (track != null && track.album.isNotBlank()) {
            MobilePlayerCompactMetadataReserveWithAlbum
        } else {
            MobilePlayerCompactMetadataReserveWithoutAlbum
        }
        val expandedRemoteTargetReserve = if (remotePlaybackTarget != null) MobilePlayerRemoteTargetReserve else 0.dp
        val compactRemoteTargetReserve = if (remotePlaybackTarget != null) MobilePlayerCompactRemoteTargetReserve else 0.dp

        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val collapsedSheetHeight = 76.dp + navBarBottom

        val fullArtworkSize = screenWidth
        val expandedDefaultHeight = fullArtworkSize +
            expandedBaseMetadataReserve +
            expandedRemoteTargetReserve +
            MobilePlayerExpandedArtworkBodyGap +
            MobilePlayerExpandedProgressLineHeight +
            MobilePlayerExpandedControlsGap +
            MobilePlayerExpandedPlayButtonSize +
            MobilePlayerExpandedControlsGap +
            MobilePlayerExpandedUtilityControlsHeight +
            MobilePlayerExpandedQueueGap +
            collapsedSheetHeight
        val compactness = ((expandedDefaultHeight - screenHeight).value / MobilePlayerExpandedCompactRange.value)
            .coerceIn(0f, 1f)
        val metadataReserve = lerp(expandedBaseMetadataReserve, compactBaseMetadataReserve, compactness) +
            lerp(expandedRemoteTargetReserve, compactRemoteTargetReserve, compactness)
        val expandedProgressLineHeight = MobilePlayerExpandedProgressLineHeight
        val expandedControlsGap = lerp(
            MobilePlayerExpandedControlsGap,
            MobilePlayerCompactControlsGap,
            compactness,
        )
        val expandedUtilityControlsHeight = lerp(
            MobilePlayerExpandedUtilityControlsHeight,
            MobilePlayerCompactUtilityControlsHeight,
            compactness,
        )
        val expandedQueueGap = lerp(
            MobilePlayerExpandedQueueGap,
            MobilePlayerCompactQueueGap,
            compactness,
        )
        val expandedPlayButtonSize = lerp(
            MobilePlayerExpandedPlayButtonSize,
            MobilePlayerCompactPlayButtonSize,
            compactness,
        )

        val currentArtworkSize = lerp(44.dp, fullArtworkSize, clampedExpansionFraction)
        val targetArtworkX = 0.dp
        val currentArtworkX = lerp(12.dp, targetArtworkX, clampedExpansionFraction)
        val currentArtworkY = lerp(14.dp, 0.dp, clampedExpansionFraction)

        val miniPlayerAlpha = collapsedChromeAlpha
        val fullPlayerAlpha = ((clampedExpansionFraction - 0.2f) * 1.25f).coerceIn(0f, 1f)
        val overlayActionsAlpha = ((clampedExpansionFraction - 0.7f) / 0.2f).coerceIn(0f, 1f)
        val fullPlayerElementsAlpha = ((clampedExpansionFraction - 0.8f) / 0.2f).coerceIn(0f, 1f)

        val nextTrack = upNext.firstOrNull()
        val currentSwipeOffset = when {
            synchronousSwipeOffsetReset -> 0f
            horizontalIsDragging -> horizontalDragOffset
            else -> horizontalSettleOffset.value
        }
        val swipeThresholdPx = with(density) { 56.dp.toPx() }

        val artworkContentShape = if (visualizerPreset == NowPlayingVisualizerPreset.Artwork && !blurredArtworkAppearance) {
            RoundedCornerShape(10.dp)
        } else {
            RoundedCornerShape(
                topStart = lerp(10.dp, cornerRadius, clampedExpansionFraction),
                topEnd = lerp(10.dp, cornerRadius, clampedExpansionFraction),
                bottomStart = lerp(10.dp, cornerRadius, clampedExpansionFraction),
                bottomEnd = lerp(10.dp, cornerRadius, clampedExpansionFraction),
            )
        }
        fun previewDirectionFor(offsetPx: Float): Int = when {
            offsetPx < 0f && nextTrack != null -> -1
            offsetPx > 0f && previousTrack != null -> 1
            else -> 0
        }

        fun settleToCenter(fromOffset: Float) {
            horizontalSettleJob?.cancel()
            horizontalSettleJob = scope.launch {
                horizontalSettleOffset.snapTo(fromOffset)
                horizontalSettleOffset.animateTo(
                    0f,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessLow,
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                    ),
                )
                horizontalSwipePreviewDirection = 0
            }
        }

        fun animateSwipeCommit(releaseOffset: Float) {
            horizontalSettleJob?.cancel()
            horizontalSettleJob = scope.launch {
                horizontalSettleOffset.snapTo(releaseOffset)
                val artworkSizePx = with(density) { currentArtworkSize.toPx() }
                val swipeThresholdPx = with(density) { 56.dp.toPx() }
                when {
                    releaseOffset < -swipeThresholdPx && nextTrack != null -> {
                        horizontalSettleOffset.animateTo(
                            targetValue = -artworkSizePx,
                            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                        )
                        val steps = (abs(releaseOffset) / artworkSizePx).toInt().coerceIn(1, 5)
                        onSkipQueueBy(steps)
                        delay(600L)
                        horizontalSettleOffset.animateTo(
                            0f,
                            animationSpec = spring(
                                stiffness = Spring.StiffnessMedium,
                                dampingRatio = Spring.DampingRatioNoBouncy,
                            ),
                        )
                    }
                    releaseOffset > swipeThresholdPx && previousTrack != null -> {
                        horizontalSettleOffset.animateTo(
                            targetValue = artworkSizePx,
                            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                        )
                        val steps = -(abs(releaseOffset) / artworkSizePx).toInt().coerceIn(1, 5)
                        onSkipQueueBy(steps)
                        delay(600L)
                        horizontalSettleOffset.animateTo(
                            0f,
                            animationSpec = spring(
                                stiffness = Spring.StiffnessMedium,
                                dampingRatio = Spring.DampingRatioNoBouncy,
                            ),
                        )
                    }
                    else -> {
                        horizontalSettleOffset.animateTo(
                            0f,
                            animationSpec = spring(
                                stiffness = Spring.StiffnessLow,
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                            ),
                        )
                    }
                }
                horizontalSwipePreviewDirection = 0
            }
        }

        val horizontalDragModifier = if (track != null) {
            val artworkSizePx = with(density) { currentArtworkSize.toPx() }
            Modifier.pointerInput(track.id, artworkSizePx, swipeThresholdPx) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        horizontalSettleJob?.cancel()
                        horizontalDragOffset = horizontalSettleOffset.value
                        horizontalSwipePreviewDirection = previewDirectionFor(horizontalDragOffset)
                        horizontalIsDragging = true
                        scope.launch { horizontalSettleOffset.stop() }
                    },
                    onDragEnd = {
                        val releaseOffset = horizontalDragOffset
                        horizontalSwipePreviewDirection = previewDirectionFor(releaseOffset)
                        horizontalIsDragging = false
                        horizontalDragOffset = 0f
                        animateSwipeCommit(releaseOffset)
                    },
                    onDragCancel = {
                        val releaseOffset = horizontalDragOffset
                        horizontalSwipePreviewDirection = previewDirectionFor(releaseOffset)
                        horizontalIsDragging = false
                        horizontalDragOffset = 0f
                        settleToCenter(releaseOffset)
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        horizontalDragOffset += dragAmount
                        horizontalSwipePreviewDirection = previewDirectionFor(horizontalDragOffset)
                    }
                )
            }
        } else {
            Modifier
        }

        if (miniPlayerAlpha > 0f && track != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MobileMiniPlayerChromeHeight)
                    .graphicsLayer {
                        alpha = miniPlayerAlpha
                        if (clampedExpansionFraction < 0.1f) {
                            translationX = currentSwipeOffset
                            val swipeProgress = (abs(currentSwipeOffset) / swipeThresholdPx).coerceIn(0f, 1f)
                            alpha = miniPlayerAlpha * (1f - swipeProgress * 0.14f)
                            val scale = 1f - swipeProgress * 0.025f
                            scaleX = scale
                            scaleY = scale
                        }
                    }
                    .then(if (clampedExpansionFraction < 0.1f) horizontalDragModifier else Modifier)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.size(44.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.size(40.dp))
            }
        }

        if (fullPlayerAlpha > 0f) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = fullPlayerAlpha }
            ) {
                if (track != null) {
                    Spacer(Modifier.height(fullArtworkSize + metadataReserve + MobilePlayerExpandedArtworkBodyGap))
                } else {
                    Spacer(Modifier.height(28.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .weight(1f, fill = false)
                            .aspectRatio(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyNowPlayingArtworkSlot(Modifier.fillMaxSize(), glyphSp = 64.sp)
                    }
                    Spacer(Modifier.height(20.dp))
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text("Nothing playing", color = PhoebeUi.primaryText, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Choose a song from your library or search.",
                            color = PhoebeUi.secondaryText,
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                        )
                    }
                    Spacer(Modifier.weight(1f).heightIn(min = 20.dp))
                }
                ProgressLine(
                    positionMs = positionMs,
                    bufferedPositionMs = timelineBufferedPositionMs,
                    durationMs = track?.durationMs ?: 0L,
                    waveformSeed = track?.let(::trackWaveformSeed) ?: "",
                    barHeight = 44.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(expandedProgressLineHeight)
                        .padding(horizontal = 20.dp)
                        .graphicsLayer { alpha = fullPlayerElementsAlpha },
                    onSeek = if (track != null) onSeek else null,
                )
                Spacer(Modifier.height(expandedControlsGap))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(expandedPlayButtonSize)
                        .padding(horizontal = 20.dp)
                        .graphicsLayer { alpha = fullPlayerElementsAlpha },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShuffleIcon(active = shuffle, onClick = onShuffle)
                    TransportIcon(PhoebeIcon.Previous, "Previous Track", onPrevious, iconSize = 16.dp)
                    Spacer(Modifier.size(expandedPlayButtonSize))
                    TransportIcon(PhoebeIcon.Next, "Next Track", onNext, iconSize = 16.dp)
                    RepeatIcon(mode = repeat, onClick = onRepeat)
                }
                Spacer(Modifier.weight(1f).heightIn(min = expandedControlsGap))
                MobileExpandedUtilityControls(
                    castState = castState,
                    equalizerActive = equalizerProfile.enabled,
                    visualizerPreset = visualizerPreset,
                    showVisualizerInTvFrame = showVisualizerInTvFrame,
                    onCast = onCast,
                    onEqualizer = { equalizerOpen = true },
                    onLyrics = onLyrics,
                    onVisualizerPreset = onVisualizerPreset,
                    onShowVisualizerInTvFrame = onShowVisualizerInTvFrame,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .height(expandedUtilityControlsHeight)
                        .graphicsLayer { alpha = fullPlayerElementsAlpha },
                )
                Spacer(Modifier.weight(1f).heightIn(min = expandedQueueGap))
                Spacer(modifier = Modifier.height(collapsedSheetHeight))
            }
        }

        if (track != null) {
            Box(
                modifier = Modifier
                    .offset(x = currentArtworkX, y = currentArtworkY)
                    .size(currentArtworkSize)
                    .clip(artworkContentShape)
                    .then(if (clampedExpansionFraction > 0.8f) horizontalDragModifier else Modifier)
            ) {
                SwipeableMobileArtwork(
                    track = track,
                    nextTrack = nextTrack,
                    previousTrack = previousTrack,
                    swipeOffset = currentSwipeOffset,
                    swipePreviewDirection = horizontalSwipePreviewDirection,
                    modifier = Modifier.fillMaxSize(),
                ) { t ->
                    if (visualizerPreset == NowPlayingVisualizerPreset.Artwork) {
                        FlippableSongArtwork(
                            track = t,
                            modifier = Modifier.fillMaxSize(),
                            maxDecodeDimension = HeroArtworkMaxDecodeDimension,
                            shape = artworkContentShape,
                        ) {
                            val isRadio = t.id.startsWith("radio:")
                            val showFeedbackActions = isRadio || (likeActions.likesEnabled && t.canTogglePlexLike()) || (listenBrainzFeedbackTarget.available && listenBrainzFeedbackTarget.trackId == t.id)
                            val showLikeControl = isRadio || (likeActions.likesEnabled && t.canTogglePlexLike())
                            MobileNowPlayingOverlayActions(
                                track = t,
                                showAudioQualityBadge = true,
                                showFeedbackActions = showFeedbackActions,
                                showLikeControl = showLikeControl,
                                likeActions = likeActions,
                                showListenBrainzFeedback = !isRadio && listenBrainzFeedbackTarget.available && listenBrainzFeedbackTarget.trackId == t.id,
                                listenBrainzFeedbackTarget = listenBrainzFeedbackTarget,
                                onListenBrainzFeedback = onListenBrainzFeedback,
                                alpha = overlayActionsAlpha,
                            )
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            NowPlayingVisualizerSurface(
                                preset = visualizerPreset,
                                track = t,
                                audioAnalysis = audioAnalysis,
                                isPlaying = isPlaying,
                                positionMs = positionMs,
                                modifier = Modifier.fillMaxSize(),
                                fullscreenButtonAlpha = fullPlayerElementsAlpha,
                                useFilamentVisualizers = useFilamentVisualizers,
                                showInTvFrame = showVisualizerInTvFrame,
                            )
                        }
                    }
                }
            }

            if (fullPlayerElementsAlpha > 0f) {
                MobileExpandedSheetChrome(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .graphicsLayer { alpha = fullPlayerElementsAlpha },
                )
            }

            val metadataTitleColor = PhoebeUi.primaryText
            val metadataArtistColor = PhoebeUi.secondaryText

            val titleColor = androidx.compose.ui.graphics.lerp(PhoebeUi.primaryText, metadataTitleColor, clampedExpansionFraction)
            val artistColor = androidx.compose.ui.graphics.lerp(PhoebeUi.secondaryText, metadataArtistColor, clampedExpansionFraction)

            val targetTextX = 20.dp
            val currentTextX = lerp(68.dp, targetTextX, clampedExpansionFraction)
            val collapsedTextY = (MobileMiniPlayerChromeHeight - CollapsedMobilePlayerMetadataHeight) / 2f
            val currentTextY = lerp(collapsedTextY, fullArtworkSize + 22.dp, clampedExpansionFraction)
            val collapsedTextWidth = if (castState.isConnected) {
                (screenWidth - 176.dp).coerceAtLeast(96.dp)
            } else {
                screenWidth - 128.dp
            }
            val currentTextWidth = lerp(collapsedTextWidth, screenWidth - 84.dp, clampedExpansionFraction)

            val titleFontSize = (14f + (26f - 14f) * clampedExpansionFraction).sp
            val artistFontSize = (12f + (15f - 12f) * clampedExpansionFraction).sp
            val titleLineHeight = (18f + (31f - 18f) * clampedExpansionFraction).sp
            val artistLineHeight = (16f + (20f - 16f) * clampedExpansionFraction).sp
            val metadataTextStable = clampedExpansionFraction < 0.08f || clampedExpansionFraction > 0.96f
            val titleFontWeight = if (clampedExpansionFraction > 0.96f) FontWeight.Black else FontWeight.Bold

            Column(
                modifier = Modifier
                    .offset(x = currentTextX, y = currentTextY)
                    .width(currentTextWidth)
                    .graphicsLayer {
                        if (clampedExpansionFraction < 0.1f) {
                            translationX = currentSwipeOffset
                            val swipeProgress = (abs(currentSwipeOffset) / swipeThresholdPx).coerceIn(0f, 1f)
                            alpha = miniPlayerAlpha * (1f - swipeProgress * 0.14f)
                            val scale = 1f - swipeProgress * 0.025f
                            scaleX = scale
                            scaleY = scale
                        }
                    }
            ) {
                AutoScrollingText(
                    text = track.title,
                    color = titleColor,
                    fontSize = titleFontSize,
                    fontWeight = titleFontWeight,
                    lineHeight = titleLineHeight,
                    marqueeEnabled = metadataTextStable,
                )
                AutoScrollingText(
                    text = track.artist,
                    color = artistColor,
                    fontSize = artistFontSize,
                    lineHeight = artistLineHeight,
                    modifier = if (clampedExpansionFraction >= 0.85f && track.artist.isNotBlank()) {
                        Modifier.clickable { trackNavigationActions.onOpenArtistForTrack(track) }
                    } else {
                        Modifier
                    },
                    marqueeEnabled = metadataTextStable,
                )
                if (clampedExpansionFraction > 0.5f) {
                    val fadeAlpha = ((clampedExpansionFraction - 0.5f) / 0.5f).coerceIn(0f, 1f)
                    val metadataAlbumColor = PhoebeUi.mutedText
                    if (track.album.isNotBlank()) {
                        AutoScrollingText(
                            text = track.album,
                            color = metadataAlbumColor,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .graphicsLayer { alpha = fadeAlpha * fullPlayerElementsAlpha }
                                .clickable {
                                    trackNavigationActions.onOpenAlbumForTrack(track)
                                },
                            marqueeEnabled = metadataTextStable,
                        )
                    }
                    if (remotePlaybackTarget != null) {
                        Text(
                            text = "Music Assistant: $remotePlaybackTarget",
                            color = PhoebeUi.accentLight.copy(alpha = fadeAlpha * fullPlayerElementsAlpha),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.graphicsLayer { alpha = fadeAlpha * fullPlayerElementsAlpha }
                        )
                    }
                }
            }

            if (fullPlayerElementsAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .offset(x = screenWidth - 64.dp, y = fullArtworkSize + 20.dp)
                        .graphicsLayer { alpha = fullPlayerElementsAlpha },
                ) {
                    TransportIcon(PhoebeIcon.More, "More options", { onOpenSongDetail(track) })
                }
            }
        }

        if (track != null && clampedExpansionFraction < 0.1f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MobileMiniPlayerChromeHeight)
                    .clickable { onClick() }
                    .then(horizontalDragModifier)
            )
        }

        if (track != null) {
            val playButtonSize = lerp(collapsedPlayButtonSize, expandedPlayButtonSize, clampedExpansionFraction)
            val collapsedPlayButtonX = screenWidth - 12.dp - collapsedPlayButtonSize
            val collapsedPlayButtonY = (MobileMiniPlayerChromeHeight - collapsedPlayButtonSize) / 2f
            val expandedPlayButtonX = (screenWidth - expandedPlayButtonSize) / 2f
            val expandedPlayButtonY = fullArtworkSize +
                metadataReserve +
                MobilePlayerExpandedArtworkBodyGap +
                expandedProgressLineHeight +
                expandedControlsGap
            val collapsedCastButtonX = collapsedPlayButtonX - 50.dp
            val collapsedCastButtonY = collapsedPlayButtonY
            val expandedCastButtonX = screenWidth - 20.dp - 40.dp
            val expandedCastButtonY = 8.dp
            val swipeProgress = (abs(currentSwipeOffset) / swipeThresholdPx).coerceIn(0f, 1f)
            val swipeScale = if (clampedExpansionFraction < 0.1f) 1f - swipeProgress * 0.025f else 1f
            val playButtonAlpha = if (clampedExpansionFraction < 0.1f) {
                miniPlayerAlpha * (1f - swipeProgress * 0.14f)
            } else {
                1f
            }
            val castButtonAlpha = playButtonAlpha

            if (castState.isConnected) {
                CastIcon(
                    active = true,
                    loading = castState.isBuffering,
                    enabled = true,
                    onClick = onCast,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = lerp(collapsedCastButtonX, expandedCastButtonX, clampedExpansionFraction).roundToPx(),
                                y = lerp(collapsedCastButtonY, expandedCastButtonY, clampedExpansionFraction).roundToPx(),
                            )
                        }
                        .graphicsLayer {
                            alpha = castButtonAlpha
                            translationX = if (clampedExpansionFraction < 0.1f) currentSwipeOffset else 0f
                            scaleX = swipeScale
                            scaleY = swipeScale
                        }
                        .then(if (clampedExpansionFraction < 0.1f) horizontalDragModifier else Modifier),
                )
            }

            PlayButton(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                size = playButtonSize,
                onClick = onToggle,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = lerp(collapsedPlayButtonX, expandedPlayButtonX, clampedExpansionFraction).roundToPx(),
                            y = lerp(collapsedPlayButtonY, expandedPlayButtonY, clampedExpansionFraction).roundToPx(),
                        )
                    }
                    .graphicsLayer {
                        alpha = playButtonAlpha
                        translationX = if (clampedExpansionFraction < 0.1f) currentSwipeOffset else 0f
                        scaleX = swipeScale
                        scaleY = swipeScale
                    }
                    .then(if (clampedExpansionFraction < 0.1f) horizontalDragModifier else Modifier),
                enabled = true,
            )
        }

            if (fullPlayerAlpha > 0f) {
                val collapsedSheetHeightPx = with(density) {
                    collapsedSheetHeight.toPx()
                }
                val expandedSheetHeightPx = with(density) {
                    val controlsPx = 146.dp.toPx()
                    val headerPx = 56.dp.toPx()
                    (screenHeight.toPx() - controlsPx - headerPx)
                        .coerceAtLeast(collapsedSheetHeightPx + 80.dp.toPx())
                }
                val sheetRangePx = (expandedSheetHeightPx - collapsedSheetHeightPx).coerceAtLeast(1f)
                fun progressForHeight(heightPx: Float): Float =
                    ((heightPx - collapsedSheetHeightPx) / sheetRangePx).coerceIn(0f, 1f)
                fun heightForProgress(progress: Float): Float =
                    collapsedSheetHeightPx + sheetRangePx * progress.coerceIn(0f, 1f)

                val sheetHeight = remember(expandedSheetHeightPx, collapsedSheetHeightPx) {
                    Animatable(heightForProgress(retainedSheetState.progress))
                }
                LaunchedEffect(expandedSheetHeightPx, collapsedSheetHeightPx) {
                    sheetHeight.snapTo(heightForProgress(retainedSheetState.progress))
                }
                var isDraggingSheet by remember { mutableStateOf(false) }
                var dragSheetHeightPx by remember { mutableFloatStateOf(collapsedSheetHeightPx) }
                val displayedSheetHeightPx = if (isDraggingSheet) dragSheetHeightPx else sheetHeight.value
                val sheetProgress = progressForHeight(displayedSheetHeightPx)
                val sheetExpanded = sheetProgress > 0.35f

                fun snapSheetHeight(currentPx: Float, velocityPxPerSec: Float) {
                    val progress = progressForHeight(currentPx)
                    val target = when {
                        velocityPxPerSec < -250f -> expandedSheetHeightPx
                        velocityPxPerSec > 250f -> collapsedSheetHeightPx
                        progress >= 0.35f -> expandedSheetHeightPx
                        else -> collapsedSheetHeightPx
                    }
                    retainedSheetState.progress = progressForHeight(target)
                    scope.launch {
                        sheetHeight.snapTo(currentPx)
                        isDraggingSheet = false
                        sheetHeight.animateTo(
                            target,
                            animationSpec = spring(
                                stiffness = Spring.StiffnessMedium,
                                dampingRatio = Spring.DampingRatioNoBouncy,
                            ),
                        )
                    }
                }

                fun snapSheet(expanded: Boolean) {
                    val target = if (expanded) expandedSheetHeightPx else collapsedSheetHeightPx
                    retainedSheetState.progress = if (expanded) 1f else 0f
                    scope.launch {
                        if (isDraggingSheet) {
                            sheetHeight.snapTo(dragSheetHeightPx)
                            isDraggingSheet = false
                        }
                        sheetHeight.animateTo(
                            target,
                            animationSpec = spring(
                                stiffness = Spring.StiffnessMedium,
                                dampingRatio = Spring.DampingRatioNoBouncy,
                            ),
                        )
                    }
                }

                MobileQueueSheet(
                    currentTrack = track,
                    upNext = upNext,
                    repeat = repeat,
                    sheetProgress = sheetProgress,
                    expanded = sheetExpanded,
                    isDragging = isDraggingSheet,
                    onToggleExpanded = { snapSheet(!sheetExpanded) },
                    onSheetDrag = { dragAmountPx ->
                        dragSheetHeightPx = (dragSheetHeightPx - dragAmountPx)
                            .coerceIn(collapsedSheetHeightPx, expandedSheetHeightPx)
                        retainedSheetState.progress = progressForHeight(dragSheetHeightPx)
                    },
                    onSheetDragStart = {
                        isDraggingSheet = true
                        dragSheetHeightPx = sheetHeight.value
                        scope.launch { sheetHeight.stop() }
                    },
                    onSheetDragEnd = { velocityPxPerSec ->
                        snapSheetHeight(dragSheetHeightPx, velocityPxPerSec)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(with(density) { displayedSheetHeightPx.toDp() })
                        .graphicsLayer { alpha = fullPlayerElementsAlpha },
                    onPlayQueue = onPlayQueue,
                    onMoveUpNext = onMoveUpNext,
                    onRemoveUpNext = onRemoveUpNext,
                    onOpenTrackDetail = onOpenSongDetail,
                    listState = upNextListState,
                )
            }
        }
    }
