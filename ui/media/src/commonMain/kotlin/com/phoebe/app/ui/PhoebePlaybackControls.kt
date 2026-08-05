package com.phoebe.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.isLocalMediaPlayback
import kotlinx.coroutines.delay
import kotlin.math.max

private const val TimelineBufferFallbackTickMs = 500L
private const val TimelineBufferFallbackAdvanceMs = 2_000L

@Composable
fun GlassIcon(icon: PhoebeIcon, description: String) {
    val shape = RoundedCornerShape(PhoebeUi.shapes.controlRadius)
    Box(
        Modifier
            .size(36.dp)
            .clip(shape)
            .background(if (PhoebeUi.design == PhoebeDesignSystem.Minimalist) PhoebeUi.subtleFill else Color.Black.copy(alpha = 0.16f))
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        PhoebeIconView(icon, tint = PhoebeUi.secondaryText, modifier = Modifier.size(19.dp))
    }
}

@Composable
fun TransportIcon(
    icon: PhoebeIcon,
    description: String,
    onClick: () -> Unit = {},
    active: Boolean = false,
    iconSize: Dp = 20.dp,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) PhoebeUi.accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        PhoebeIconView(icon, tint = if (active) PhoebeUi.accentLight else PhoebeUi.primaryText, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun PlayButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val motionEnabled = LocalContinuousMotionEnabled.current
    val targetScale = if ((isPlaying || isBuffering) && enabled) 1f else 0.98f
    val scaleState = animateFloatAsState(
        targetScale,
        spring(),
        label = "play-button-scale",
    )
    val scale = if (motionEnabled) scaleState.value else targetScale
    val gradient = if (enabled && PhoebeUi.design == PhoebeDesignSystem.Minimalist) {
        Brush.linearGradient(listOf(PhoebeUi.accent, PhoebeUi.accent))
    } else if (enabled) {
        Brush.linearGradient(listOf(PhoebeUi.accentLight, PhoebeUi.accent))
    } else {
        Brush.linearGradient(listOf(PhoebeUi.mutedText.copy(alpha = 0.28f), PhoebeUi.mutedText.copy(alpha = 0.38f)))
    }
    val iconSize = if (size > 52.dp) 24.dp else 21.dp
    val spinnerSize = iconSize + 2.dp
    val contentDescription = when {
        isBuffering -> "Loading"
        isPlaying -> "Pause"
        else -> "Play"
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (enabled && PhoebeUi.design != PhoebeDesignSystem.Minimalist) {
                    Modifier.shadow(18.dp, CircleShape, ambientColor = PhoebeUi.accent.copy(alpha = 0.4f), spotColor = PhoebeUi.accent.copy(alpha = 0.38f))
                } else {
                    Modifier
                },
            )
            .clip(CircleShape)
            .background(gradient)
            .clickable(enabled = enabled && !isBuffering, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (motionEnabled) {
            AnimatedContent(
                targetState = isBuffering,
                transitionSpec = {
                    fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                },
                label = "play-button-icon",
            ) { loading ->
                PlayButtonGlyph(
                    loading = loading,
                    isPlaying = isPlaying,
                    enabled = enabled,
                    spinnerSize = spinnerSize,
                    iconSize = iconSize,
                )
            }
        } else {
            PlayButtonGlyph(
                loading = isBuffering,
                isPlaying = isPlaying,
                enabled = enabled,
                spinnerSize = spinnerSize,
                iconSize = iconSize,
            )
        }
    }
}

@Composable
private fun PlayButtonGlyph(
    loading: Boolean,
    isPlaying: Boolean,
    enabled: Boolean,
    spinnerSize: Dp,
    iconSize: Dp,
) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(spinnerSize),
            color = PhoebeUi.primaryText,
            strokeWidth = 2.dp,
            trackColor = PhoebeUi.primaryText.copy(alpha = 0.22f),
        )
    } else {
        MorphingPlayPauseIcon(
            isPlaying = isPlaying,
            tint = if (enabled) PhoebeUi.primaryText else PhoebeUi.mutedText.copy(alpha = 0.55f),
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun MorphingPlayPauseIcon(
    isPlaying: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val motionEnabled = LocalContinuousMotionEnabled.current
    val targetMorph = if (isPlaying) 1f else 0f
    val morphState = animateFloatAsState(
        targetValue = targetMorph,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "play-pause-morph",
    )
    val morph = if (motionEnabled) morphState.value else targetMorph

    Canvas(modifier) {
        val s = size.minDimension
        fun x(value: Float) = s * value
        fun y(value: Float) = s * value
        fun m(start: Float, end: Float) = start + (end - start) * morph

        fun morphPath(play: List<Offset>, pause: List<Offset>) {
            val path = Path().apply {
                val first = play.first()
                moveTo(x(m(first.x, pause.first().x)), y(m(first.y, pause.first().y)))
                for (index in 1 until play.size) {
                    val p = play[index]
                    val target = pause[index]
                    lineTo(x(m(p.x, target.x)), y(m(p.y, target.y)))
                }
                close()
            }
            drawPath(path, tint)
        }

        morphPath(
            play = PlayButtonLeftPlayShape,
            pause = PlayButtonLeftPauseShape,
        )
        morphPath(
            play = PlayButtonRightPlayShape,
            pause = PlayButtonRightPauseShape,
        )
    }
}

private val PlayButtonLeftPlayShape = listOf(
    Offset(0.34f, 0.22f),
    Offset(0.55f, 0.36f),
    Offset(0.55f, 0.64f),
    Offset(0.34f, 0.78f),
)

private val PlayButtonLeftPauseShape = listOf(
    Offset(0.32f, 0.22f),
    Offset(0.44f, 0.22f),
    Offset(0.44f, 0.78f),
    Offset(0.32f, 0.78f),
)

private val PlayButtonRightPlayShape = listOf(
    Offset(0.55f, 0.36f),
    Offset(0.76f, 0.50f),
    Offset(0.76f, 0.50f),
    Offset(0.55f, 0.64f),
)

private val PlayButtonRightPauseShape = listOf(
    Offset(0.56f, 0.22f),
    Offset(0.68f, 0.22f),
    Offset(0.68f, 0.78f),
    Offset(0.56f, 0.78f),
)

@Composable
fun rememberTimelineBufferedPositionMs(
    track: Track?,
    positionMs: Long,
    bufferedPositionMs: Long,
    isPlaying: Boolean,
    isBuffering: Boolean,
): Long {
    val remoteDurationMs = track
        ?.takeUnless { it.isLocalMediaPlayback() }
        ?.durationMs
        ?.takeIf { it > 0L }
    val latestPositionMs by rememberUpdatedState(positionMs)
    val latestBufferedPositionMs by rememberUpdatedState(bufferedPositionMs)
    var estimatedRemoteBufferedPositionMs by remember(track?.id) {
        mutableStateOf(max(positionMs, bufferedPositionMs))
    }

    LaunchedEffect(track?.id) {
        estimatedRemoteBufferedPositionMs = max(positionMs, bufferedPositionMs)
    }
    LaunchedEffect(remoteDurationMs, bufferedPositionMs, positionMs) {
        val duration = remoteDurationMs
        if (duration == null) {
            estimatedRemoteBufferedPositionMs = bufferedPositionMs
            return@LaunchedEffect
        }
        estimatedRemoteBufferedPositionMs = max(
            estimatedRemoteBufferedPositionMs,
            max(positionMs, bufferedPositionMs),
        ).coerceAtMost(duration)
    }
    LaunchedEffect(track?.id, remoteDurationMs, isPlaying, isBuffering) {
        val duration = remoteDurationMs ?: return@LaunchedEffect
        if (!isPlaying) {
            estimatedRemoteBufferedPositionMs = max(positionMs, bufferedPositionMs)
            return@LaunchedEffect
        }
        if (!isBuffering) {
            estimatedRemoteBufferedPositionMs = max(positionMs, bufferedPositionMs).coerceAtMost(duration)
            return@LaunchedEffect
        }
        while (estimatedRemoteBufferedPositionMs < duration) {
            delay(TimelineBufferFallbackTickMs)
            val platformFloor = max(latestPositionMs, latestBufferedPositionMs)
            estimatedRemoteBufferedPositionMs = max(estimatedRemoteBufferedPositionMs, platformFloor)
                .plus(TimelineBufferFallbackAdvanceMs)
                .coerceAtMost(duration)
            if (latestBufferedPositionMs > latestPositionMs + TimelineBufferFallbackAdvanceMs) break
        }
    }
    return remember(remoteDurationMs, bufferedPositionMs, estimatedRemoteBufferedPositionMs) {
        remoteDurationMs?.let { duration ->
            max(bufferedPositionMs, estimatedRemoteBufferedPositionMs).coerceIn(0L, duration)
        } ?: bufferedPositionMs
    }
}

@Composable
fun ProgressLine(
    positionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    waveformSeed: String,
    modifier: Modifier,
    onSeek: ((Long) -> Unit)? = null,
    barHeight: Dp = 28.dp,
    labelFontSize: TextUnit = 12.sp,
    labelSpacing: Dp = 6.dp,
    maxBarSlots: Int = 140,
) {
    val safeDuration = max(durationMs, 1L)
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    val isScrubbing = scrubPositionMs != null
    val displayPositionMs = scrubPositionMs ?: positionMs
    val progressFrac = (displayPositionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    val bufferedFrac = (bufferedPositionMs.toFloat() / safeDuration).coerceIn(progressFrac, 1f)

    LaunchedEffect(waveformSeed, durationMs) {
        scrubPositionMs = null
    }

    val seekModifier = if (onSeek != null && durationMs > 0L) {
        Modifier.pointerInput(durationMs, onSeek) {
            fun offsetToMs(x: Float): Long {
                val frac = (x / size.width).coerceIn(0f, 1f)
                return (durationMs * frac).toLong()
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                var scrubMs = offsetToMs(down.position.x)
                val committedMs = scrubMs
                scrubPositionMs = scrubMs
                onSeek(scrubMs)
                val pointerId = down.id
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                    if (!change.pressed) {
                        if (scrubMs != committedMs) {
                            onSeek(scrubMs)
                        }
                        scrubPositionMs = null
                        break
                    }
                    scrubMs = offsetToMs(change.position.x)
                    scrubPositionMs = scrubMs
                    change.consume()
                }
            }
        }
    } else {
        Modifier
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(labelSpacing)) {
        WaveformDurationBar(
            seed = waveformSeed,
            durationMs = durationMs,
            progress = if (durationMs > 0L) progressFrac else null,
            bufferedProgress = if (durationMs > 0L) bufferedFrac else null,
            isScrubbing = isScrubbing,
            contentDescription = if (durationMs > 0L) {
                "Playback progress, ${formatDuration(displayPositionMs)} of ${formatDuration(durationMs)}"
            } else {
                "Playback progress, no duration"
            },
            modifier = seekModifier
                .fillMaxWidth()
                .height(barHeight),
            maxBarSlots = maxBarSlots,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatDuration(displayPositionMs),
                color = if (isScrubbing) PhoebeUi.primaryText else PhoebeUi.mutedText,
                fontSize = labelFontSize,
            )
            Text(formatDuration(durationMs), color = PhoebeUi.mutedText, fontSize = labelFontSize)
        }
    }
}

@Composable
fun VolumeSlider(volume: Float, onVolume: (Float) -> Unit, modifier: Modifier) {
    Slider(
        value = volume,
        onValueChange = onVolume,
        modifier = modifier.semantics { contentDescription = "Volume" },
        colors = SliderDefaults.colors(
            thumbColor = PhoebeUi.accentLight,
            activeTrackColor = PhoebeUi.accentLight,
            inactiveTrackColor = PhoebeUi.progressTrack,
        ),
    )
}
