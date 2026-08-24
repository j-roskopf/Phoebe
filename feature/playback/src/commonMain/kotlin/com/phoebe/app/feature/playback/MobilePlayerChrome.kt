package com.phoebe.app.feature.playback

import com.phoebe.app.ui.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phoebe.app.data.ListenBrainzFeedbackScore
import com.phoebe.app.data.ListenBrainzFeedbackTarget
import com.phoebe.app.domain.StreamingQuality
import com.phoebe.app.domain.Track

fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return start + fraction * (stop - start)
}

fun lerp(start: Dp, stop: Dp, fraction: Float): Dp {
    return Dp(start.value + fraction * (stop.value - start.value))
}

fun Modifier.playerDragGestures(
    expansionFraction: Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
): Modifier = this.pointerInput(Unit) {
    var velocityTracker = VelocityTracker()
    var accumulatedY = 0f
    detectVerticalDragGestures(
        onDragStart = { _ ->
            velocityTracker = VelocityTracker()
            accumulatedY = 0f
            onDragStart()
        },
        onDragEnd = {
            val velocity = velocityTracker.calculateVelocity().y
            onDragEnd(velocity)
        },
        onDragCancel = {
            onDragEnd(0f)
        },
        onVerticalDrag = { change, dragAmount ->
            change.consume()
            accumulatedY += dragAmount
            velocityTracker.addPosition(change.uptimeMillis, Offset(0f, accumulatedY))
            onDrag(dragAmount)
        },
    )
}

@Composable
fun MobileArtworkMetadataScrim(
    blendOverlap: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.drawBehind {
            val overlapStop = (blendOverlap.toPx() / size.height).coerceIn(0f, 0.72f)
            val brush = if (overlapStop > 0f) {
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to Color.Transparent,
                        (overlapStop * 0.92f) to Color.Transparent,
                        overlapStop to Color.Black.copy(alpha = 0.36f),
                        (overlapStop + 0.18f).coerceAtMost(0.9f) to Color.Black.copy(alpha = 0.68f),
                        1.00f to Color.Black.copy(alpha = 0.78f),
                    ),
                    startY = 0f,
                    endY = size.height,
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.52f),
                        Color.Black.copy(alpha = 0.78f),
                    ),
                    startY = 0f,
                    endY = size.height,
                )
            }
            drawRect(brush)
        },
    )
}

@Composable
fun BoxScope.MobileNowPlayingOverlayActions(
    track: Track,
    showAudioQualityBadge: Boolean,
    showFeedbackActions: Boolean,
    showLikeControl: Boolean,
    likeActions: LikeActions,
    showListenBrainzFeedback: Boolean,
    listenBrainzFeedbackTarget: ListenBrainzFeedbackTarget,
    onListenBrainzFeedback: (ListenBrainzFeedbackScore) -> Unit,
    alpha: Float = 1f,
    playingQuality: StreamingQuality = StreamingQuality.Original,
) {
    if (showAudioQualityBadge && alpha > 0f) {
        AudioQualityBadge(
            track = track,
            onArtwork = true,
            playingQuality = playingQuality,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .graphicsLayer { this.alpha = alpha },
        )
    }
    if (showFeedbackActions && alpha > 0f) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .graphicsLayer { this.alpha = alpha }
                .clip(RoundedCornerShape(999.dp))
                .background(PhoebeUi.canvasBackground.copy(alpha = 0.72f))
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showLikeControl) {
                LikeButton(
                    liked = likeActions.isLiked(track),
                    enabled = true,
                    onClick = { likeActions.onToggleLiked(track) },
                )
            }
            if (showListenBrainzFeedback) {
                ListenBrainzFeedbackControls(
                    target = listenBrainzFeedbackTarget,
                    onFeedback = onListenBrainzFeedback,
                    horizontalVotes = true,
                    showVoteBorders = false,
                )
            }
        }
    }
}
