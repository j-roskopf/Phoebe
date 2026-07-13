package com.phoebe.app.feature.playback

import com.phoebe.app.ui.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.UpNextDividerMarker
import kotlin.math.roundToInt

@Composable
fun QueuePanel(
    upNext: List<Track>,
    currentTrack: Track?,
    upNextDivider: UpNextDividerMarker? = null,
    keepPlayingEnabled: Boolean = false,
    currentIndex: Int = -1,
    repeat: RepeatMode,
    modifier: Modifier,
    onPlayQueue: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onKeepPlayingEnabled: (Boolean) -> Unit = {},
    onMoveUpNext: (Int, Int) -> Unit,
    onRemoveUpNext: (Int) -> Unit,
    onOpenTrackDetail: (Track) -> Unit = {},
    currentTrackClickOpensDetail: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
) {
    Column(modifier.padding(top = 132.dp, end = 36.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
            if (upNext.isNotEmpty()) {
                Text(
                    "Clear",
                    color = PhoebeUi.mutedText,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onClearQueue)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
        if (currentTrack == null && upNext.isEmpty()) {
            Text("Pick a song to start a queue.", color = PhoebeUi.mutedText, fontSize = 13.sp, lineHeight = 18.sp)
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
                currentTrackClickOpensDetail = currentTrackClickOpensDetail,
                listState = listState,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun KeepPlayingQueueToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val label = if (compact) "Keep" else "Keep Playing"
    val activeColor = PhoebeUi.accentLight
    val inactiveText = PhoebeUi.mutedText.copy(alpha = 0.9f)
    val containerColor = if (enabled) {
        PhoebeUi.accent.copy(alpha = 0.18f)
    } else {
        PhoebeUi.subtleFill
    }
    val trackColor = if (enabled) {
        activeColor.copy(alpha = 0.48f)
    } else {
        PhoebeUi.progressTrack
    }
    val thumbColor = if (enabled) Color.White else PhoebeUi.mutedText.copy(alpha = 0.82f)
    val containerShape = RoundedCornerShape(PhoebeUi.shapes.controlRadius)
    Row(
        modifier = modifier
            .heightIn(min = 28.dp)
            .clip(containerShape)
            .background(containerColor)
            .clickable(
                onClickLabel = if (enabled) "Disable Keep Playing" else "Enable Keep Playing",
                role = Role.Switch,
                onClick = { onEnabledChange(!enabled) },
            )
            .semantics {
                contentDescription = "Keep Playing"
                stateDescription = if (enabled) "On" else "Off"
            }
            .padding(start = 8.dp, end = 7.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            color = if (enabled) activeColor else inactiveText,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .width(24.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(trackColor)
                .padding(horizontal = 2.dp),
            contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(thumbColor),
            )
        }
    }
}

@Composable
fun RepeatBadge(mode: RepeatMode) {
    val (label, description) = when (mode) {
        RepeatMode.One -> "1" to "Repeating current track"
        RepeatMode.All -> "All" to "Repeating queue"
        RepeatMode.Off -> return
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(PhoebeUi.accent.copy(alpha = 0.18f))
            .heightIn(min = 20.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PhoebeIconView(PhoebeIcon.Repeat, tint = PhoebeUi.accentLight, modifier = Modifier.size(10.dp))
        Text(
            label,
            color = PhoebeUi.accentLight,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.04.em,
        )
    }
}

/**
 * Vertical Up Next list with a non-draggable "currently playing" header row followed
 * by reorderable upcoming tracks. Used on both desktop and mobile expanded panels.
 */
@Composable
fun UpNextList(
    currentTrack: Track?,
    upNext: List<Track>,
    upNextDivider: UpNextDividerMarker? = null,
    currentIndex: Int = -1,
    repeat: RepeatMode = RepeatMode.Off,
    onPlayQueue: (Int) -> Unit,
    onMoveUpNext: (Int, Int) -> Unit,
    onRemoveUpNext: (Int) -> Unit,
    onOpenTrackDetail: (Track) -> Unit = {},
    currentTrackClickOpensDetail: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
    thumbnail: Dp = 44.dp,
    rowHeight: Dp = 60.dp,
) {
    val density = LocalDensity.current
    val rowSpacing = 2.dp
    val rowStepPx = with(density) { rowHeight.toPx() + rowSpacing.toPx() }
    var draggingTrackId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableStateOf<Int?>(null) }
    var dragTargetIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetPx by remember { mutableStateOf(0f) }
    val dividerUpNextIndex = upNextDivider.upNextIndex(currentIndex, upNext)

    LazyColumn(state = listState, modifier = modifier, verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
        if (currentTrack != null) {
            item(key = "now-playing-${currentTrack.id}", contentType = "now-playing") {
                UpNextRow(
                    track = currentTrack,
                    active = true,
                    repeatBadge = if (repeat == RepeatMode.One) "1" else null,
                    thumbnail = thumbnail,
                    rowHeight = rowHeight,
                    dragHandle = null,
                    onClick = if (currentTrackClickOpensDetail) {
                        { onOpenTrackDetail(currentTrack) }
                    } else {
                        { /* no-op, already playing */ }
                    },
                    detailAction = { onOpenTrackDetail(currentTrack) },
                )
            }
        }
        val dividerItemIndex = dividerUpNextIndex ?: -1
        val dividerMarker = upNextDivider
        val hasDivider = dividerItemIndex in 0 until upNext.size && dividerMarker != null
        val totalCount = upNext.size + if (hasDivider) 1 else 0
        items(
            count = totalCount,
            key = { itemIndex ->
                if (hasDivider) {
                    when {
                        itemIndex < dividerItemIndex -> upNext[itemIndex].id
                        itemIndex == dividerItemIndex ->
                            "keep-playing-divider-${requireNotNull(dividerMarker).beforeQueueIndex}"
                        else -> upNext[itemIndex - 1].id
                    }
                } else {
                    upNext[itemIndex].id
                }
            },
            contentType = { itemIndex ->
                if (hasDivider && itemIndex == dividerItemIndex) {
                    "keep-playing-divider"
                } else {
                    "up-next"
                }
            },
        ) { itemIndex ->
            if (hasDivider && itemIndex == dividerItemIndex) {
                KeepPlayingDivider(requireNotNull(dividerMarker).label)
            } else {
                val index = if (hasDivider && itemIndex > dividerItemIndex) {
                    itemIndex - 1
                } else {
                    itemIndex
                }
                val track = upNext[index]
                val isDragging = draggingTrackId == track.id
                val draggingId = draggingTrackId
                val startIndex = dragStartIndex
                val targetIndex = dragTargetIndex
                val rowOffsetPx = when {
                    draggingId == null || startIndex == null || targetIndex == null -> 0f
                    isDragging -> dragOffsetPx
                    targetIndex > startIndex && index in (startIndex + 1)..targetIndex -> -rowStepPx
                    targetIndex < startIndex && index in targetIndex until startIndex -> rowStepPx
                    else -> 0f
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(0, rowOffsetPx.roundToInt()) }
                        .zIndex(if (isDragging) 1f else 0f),
                ) {
                    UpNextRow(
                        track = track,
                        active = false,
                        thumbnail = thumbnail,
                        rowHeight = rowHeight,
                        backgroundAlpha = if (isDragging) 0.22f else 0f,
                        dragHandle = {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .pointerInput(track.id, index, upNext.lastIndex, rowStepPx) {
                                        detectDragGestures(
                                            onDragStart = {
                                                draggingTrackId = track.id
                                                dragStartIndex = index
                                                dragTargetIndex = index
                                                dragOffsetPx = 0f
                                            },
                                            onDragEnd = {
                                                val from = dragStartIndex
                                                val to = dragTargetIndex
                                                draggingTrackId = null
                                                dragStartIndex = null
                                                dragTargetIndex = null
                                                dragOffsetPx = 0f
                                                if (from != null && to != null && from != to) {
                                                    onMoveUpNext(from, to)
                                                }
                                            },
                                            onDragCancel = {
                                                draggingTrackId = null
                                                dragStartIndex = null
                                                dragTargetIndex = null
                                                dragOffsetPx = 0f
                                            },
                                            onDrag = { change, drag ->
                                                change.consume()
                                                val startIndex = dragStartIndex
                                                    ?: return@detectDragGestures
                                                val minOffset = -startIndex * rowStepPx
                                                val maxOffset = (upNext.lastIndex - startIndex) * rowStepPx
                                                dragOffsetPx = (dragOffsetPx + drag.y).coerceIn(minOffset, maxOffset)
                                                dragTargetIndex = (startIndex + (dragOffsetPx / rowStepPx).roundToInt())
                                                    .coerceIn(0, upNext.lastIndex)
                                            },
                                        )
                                    }
                                    .semantics { contentDescription = "Reorder ${track.title}" },
                                contentAlignment = Alignment.Center,
                            ) {
                                PhoebeIconView(PhoebeIcon.Drag, tint = PhoebeUi.mutedText, modifier = Modifier.size(18.dp))
                            }
                        },
                        onClick = { onPlayQueue(index) },
                        onLongPress = { onOpenTrackDetail(track) },
                    )
                }
            }
        }
        if (dividerUpNextIndex == upNext.size && upNextDivider != null) {
            item(
                key = "keep-playing-divider-${upNextDivider.beforeQueueIndex}",
                contentType = "keep-playing-divider",
            ) {
                KeepPlayingDivider(upNextDivider.label)
            }
        }
        if (repeat == RepeatMode.All && (currentTrack != null || upNext.isNotEmpty())) {
            item(contentType = "repeat-all-divider") {
                RepeatAllDivider()
            }
        }
    }
}

private fun UpNextDividerMarker?.upNextIndex(currentIndex: Int, upNext: List<Track>): Int? {
    val marker = this ?: return null
    if (currentIndex < 0) return null
    val index = marker.beforeQueueIndex - currentIndex - 1
    return index.takeIf { it in 0..upNext.size }
}

@Composable
internal fun KeepPlayingDivider(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(PhoebeUi.accent.copy(alpha = 0.24f)))
        Text(
            label,
            color = PhoebeUi.accentLight,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.10.em,
        )
        Box(Modifier.weight(1f).height(1.dp).background(PhoebeUi.accent.copy(alpha = 0.24f)))
    }
}

@Composable
internal fun RepeatAllDivider() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(PhoebeUi.accent.copy(alpha = 0.32f)))
        Text(
            "Loops",
            color = PhoebeUi.accentLight,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.10.em,
        )
        Box(Modifier.weight(1f).height(1.dp).background(PhoebeUi.accent.copy(alpha = 0.32f)))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UpNextRow(
    track: Track,
    active: Boolean,
    thumbnail: Dp,
    rowHeight: Dp,
    backgroundAlpha: Float = 0f,
    repeatBadge: String? = null,
    dragHandle: (@Composable () -> Unit)?,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    detailAction: (() -> Unit)? = null,
) {
    val rowShape = RoundedCornerShape(PhoebeUi.shapes.controlRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .then(if (active) Modifier else Modifier.playTrackTarget(track))
            .clip(rowShape)
            .background(
                if (active) PhoebeUi.accent.copy(alpha = 0.10f)
                else if (backgroundAlpha > 0f) PhoebeUi.subtleFill
                else Color.Transparent,
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(thumbnail), contentAlignment = Alignment.Center) {
            TrackArtworkImage(track, Modifier.fillMaxSize(), radius = 6.dp)
            if (active) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    PhoebeIconView(PhoebeIcon.ActiveDot, tint = PhoebeUi.accentLight, modifier = Modifier.size(14.dp), filled = true)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    track.title,
                    color = if (active) PhoebeUi.accentLight else PhoebeUi.primaryText,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (repeatBadge != null) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(PhoebeUi.accent.copy(alpha = 0.22f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            repeatBadge,
                            color = PhoebeUi.accentLight,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.04.em,
                        )
                    }
                }
            }
            Text(
                track.artist,
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            formatDuration(track.durationMs),
            color = PhoebeUi.mutedText,
            fontSize = 12.sp,
        )
        if (dragHandle != null) {
            dragHandle()
        } else if (detailAction != null) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = detailAction)
                    .semantics { contentDescription = "Open song details for ${track.title}" },
                contentAlignment = Alignment.Center,
            ) {
                PhoebeIconView(PhoebeIcon.ChevronRight, tint = PhoebeUi.secondaryText, modifier = Modifier.size(18.dp))
            }
        } else {
            Spacer(Modifier.width(36.dp))
        }
    }
}
