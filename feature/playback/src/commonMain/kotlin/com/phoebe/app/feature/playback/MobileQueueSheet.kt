package com.phoebe.app.feature.playback

import com.phoebe.app.ui.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.UpNextDividerMarker

@Composable
fun MobileQueueSheet(
    currentTrack: Track?,
    upNext: List<Track>,
    upNextDivider: UpNextDividerMarker? = null,
    keepPlayingEnabled: Boolean = false,
    currentIndex: Int = -1,
    repeat: RepeatMode,
    sheetProgress: Float,
    expanded: Boolean,
    isDragging: Boolean,
    onToggleExpanded: () -> Unit,
    onSheetDrag: (Float) -> Unit,
    onSheetDragStart: () -> Unit,
    onSheetDragEnd: (velocityPxPerSec: Float) -> Unit,
    modifier: Modifier,
    onPlayQueue: (Int) -> Unit,
    onKeepPlayingEnabled: (Boolean) -> Unit = {},
    onMoveUpNext: (Int, Int) -> Unit,
    onRemoveUpNext: (Int) -> Unit,
    onOpenTrackDetail: (Track) -> Unit = {},
    listState: LazyListState = RetainedLazyListStates.remember("mobile-player-up-next-list"),
) {
    val handleWidth by animateFloatAsState(
        targetValue = when {
            isDragging -> 52f
            expanded -> 44f
            else -> 36f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "queue-sheet-handle-width",
    )
    val sheetElevation by animateFloatAsState(
        targetValue = 8f + sheetProgress * 18f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "queue-sheet-elevation",
    )
    val sheetCorner by animateFloatAsState(
        targetValue = 22f + sheetProgress * 4f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "queue-sheet-corner",
    )
    val sheetShape = RoundedCornerShape(topStart = sheetCorner.dp, topEnd = sheetCorner.dp)
    val onSheetDragUpdated = rememberUpdatedState(onSheetDrag)
    val onSheetDragStartUpdated = rememberUpdatedState(onSheetDragStart)
    val onSheetDragEndUpdated = rememberUpdatedState(onSheetDragEnd)
    val draggableState = rememberDraggableState { delta ->
        onSheetDragUpdated.value(delta)
    }

    Column(
        modifier = modifier
            .shadow(sheetElevation.dp, sheetShape, clip = false)
            .clip(sheetShape)
            .background(PhoebeUi.glass.copy(alpha = 0.94f + sheetProgress * 0.04f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Vertical,
                    onDragStarted = { onSheetDragStartUpdated.value() },
                    onDragStopped = { velocity -> onSheetDragEndUpdated.value(velocity) },
                )
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(handleWidth.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(PhoebeUi.primaryText.copy(alpha = 0.14f + sheetProgress * 0.12f)),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Up Next", PhoebeUi.primaryText)
                if (repeat != RepeatMode.Off) {
                    Spacer(Modifier.width(8.dp))
                    RepeatBadge(mode = repeat)
                }
                Spacer(Modifier.width(8.dp))
                KeepPlayingQueueToggle(
                    enabled = keepPlayingEnabled,
                    onEnabledChange = onKeepPlayingEnabled,
                )
                Spacer(Modifier.weight(1f))
                val queueCount = upNext.size + if (currentTrack != null) 1 else 0
                Text(
                    when (queueCount) {
                        0 -> "Empty"
                        1 -> "1 track"
                        else -> "$queueCount tracks"
                    },
                    color = PhoebeUi.mutedText.copy(alpha = 0.75f + sheetProgress * 0.25f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleExpanded)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .semantics {
                            contentDescription = if (expanded) "Collapse Up Next" else "Expand Up Next"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    PhoebeIconView(
                        PhoebeIcon.ChevronUp,
                        tint = PhoebeUi.mutedText.copy(alpha = 0.65f + sheetProgress * 0.35f),
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = sheetProgress * 180f },
                    )
                }
            }
        }

        val showQueueContent = sheetProgress > 0.06f || isDragging
        if (showQueueContent) {
            if (currentTrack == null && upNext.isEmpty()) {
                Text(
                    "Pick a song to start a queue.",
                    color = PhoebeUi.mutedText,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 14.dp)
                        .graphicsLayer {
                            alpha = ((sheetProgress - 0.06f) / 0.2f).coerceIn(0f, 1f)
                        },
                )
            } else {
                UpNextList(
                    currentTrack = currentTrack,
                    upNext = upNext,
                    upNextDivider = upNextDivider,
                    currentIndex = currentIndex,
                    repeat = repeat,
                    onPlayQueue = onPlayQueue,
                    onMoveUpNext = onMoveUpNext,
                    onRemoveUpNext = onRemoveUpNext,
                    onOpenTrackDetail = onOpenTrackDetail,
                    listState = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 12.dp)
                        .graphicsLayer {
                            alpha = ((sheetProgress - 0.06f) / 0.2f).coerceIn(0f, 1f)
                        },
                    thumbnail = 40.dp,
                    rowHeight = 56.dp,
                )
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}
