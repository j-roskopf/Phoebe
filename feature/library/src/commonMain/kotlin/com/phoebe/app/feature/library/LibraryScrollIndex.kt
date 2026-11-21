package com.phoebe.app.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.Track
import com.phoebe.app.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

val LibrarySectionIndexWidth = 80.dp
val LocalLibrarySectionIndexForceScrub = compositionLocalOf { false }

private val LibrarySectionIndexHitWidth = 40.dp
private val LibrarySectionIndexDesktopHitWidth = 80.dp
private val DesktopSectionIndexPreviewBubbleWidth = 104.dp
private val MobileSectionIndexPreviewBubbleWidth = 176.dp
private const val MaxVisibleSectionIndexLabels = 60
private const val SectionIndexInteractionLingerMs = 3_000L
private const val ActiveSectionIndexLabelMs = 1_100L
private const val ScrollHintVisibleMs = 650L
private const val SectionIndexSelectionCoalesceMs = 32L
private const val MillisPerDay = 86_400_000L
private val AlphaLabels = ('A'..'Z').map { it.toString() }
private val MonthLabels = listOf(
    "Jan",
    "Feb",
    "Mar",
    "Apr",
    "May",
    "Jun",
    "Jul",
    "Aug",
    "Sep",
    "Oct",
    "Nov",
    "Dec",
)

data class LibraryScrollIndexEntry(
    val label: String,
    val itemIndex: Int,
)

enum class LibrarySectionIndexMode {
    DesktopHover,
    DesktopScrollbar,
    MobileScrollbar,
}

data class LibraryScrollbarState(
    val firstVisibleItemIndex: Int,
    val visibleItemsCount: Int,
    val totalItemsCount: Int,
) {
    val hasScrollableContent: Boolean get() = totalItemsCount > visibleItemsCount && visibleItemsCount > 0

    val thumbSizeFraction: Float
        get() = if (!hasScrollableContent) {
            1f
        } else {
            (visibleItemsCount.toFloat() / totalItemsCount.toFloat()).coerceIn(0.08f, 0.72f)
        }

    val thumbTopFraction: Float
        get() {
            if (!hasScrollableContent) return 0f
            val maxFirstVisible = (totalItemsCount - visibleItemsCount).coerceAtLeast(1)
            return (firstVisibleItemIndex.toFloat() / maxFirstVisible.toFloat()).coerceIn(0f, 1f)
    }
}

class LibrarySectionIndexSelectionDispatcher {
    private var job: Job? = null
    private var pendingKey: Int? = null
    private var pendingBlock: (suspend CoroutineScope.() -> Unit)? = null
    private var lastDispatchedKey: Int? = null

    fun launch(scope: CoroutineScope, key: Int? = null, block: suspend CoroutineScope.() -> Unit) {
        if (key != null && (key == pendingKey || key == lastDispatchedKey)) return
        pendingKey = key
        pendingBlock = block
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                while (true) {
                    val next = pendingBlock ?: break
                    val nextKey = pendingKey
                    pendingBlock = null
                    pendingKey = null
                    lastDispatchedKey = nextKey
                    next.invoke(this)
                    delay(SectionIndexSelectionCoalesceMs)
                }
            } finally {
                lastDispatchedKey = null
            }
        }
    }
}

@Composable
fun rememberLibrarySectionIndexSelectionDispatcher(): LibrarySectionIndexSelectionDispatcher =
    remember { LibrarySectionIndexSelectionDispatcher() }

@Composable
fun LibrarySectionIndex(
    entries: List<LibraryScrollIndexEntry>,
    onEntrySelected: (LibraryScrollIndexEntry) -> Unit,
    modifier: Modifier = Modifier,
    mode: LibrarySectionIndexMode = LibrarySectionIndexMode.DesktopHover,
    revealSignal: Boolean = false,
    keepLabelsVisible: Boolean = false,
    scrollbarState: LibraryScrollbarState? = null,
    scrollbarStateProvider: (() -> LibraryScrollbarState?)? = null,
    onScrubbingChanged: (Boolean) -> Unit = {},
    // Mobile only: touches that start above this y (px, in the index's own coordinates) are ignored,
    // so the rail never steals gestures from a header (toolbar, overflow menu) scrolled into that area.
    touchExclusionTopPx: () -> Float = { 0f },
) {
    val currentOnScrubbingChanged by rememberUpdatedState(onScrubbingChanged)
    val currentTouchExclusionTopPx by rememberUpdatedState(touchExclusionTopPx)

    DisposableEffect(Unit) {
        onDispose { currentOnScrubbingChanged(false) }
    }

    val hasMultipleIndices = remember(entries) {
        val firstIndex = entries.firstOrNull()?.itemIndex
        entries.any { it.itemIndex != firstIndex }
    }
    if (!hasMultipleIndices) {
        LaunchedEffect(entries) {
            currentOnScrubbingChanged(false)
        }
        return
    }

    var activeEntry by remember(entries) { mutableStateOf<LibraryScrollIndexEntry?>(null) }
    var scrubbing by remember(entries) { mutableStateOf(false) }
    var hovering by remember(entries) { mutableStateOf(false) }
    var interactionLingerVisible by remember(entries) { mutableStateOf(false) }
    var hintVisible by remember(entries) { mutableStateOf(false) }
    var lastSelectedItemIndex by remember(entries) { mutableStateOf<Int?>(null) }
    var suppressNextExitLinger by remember(entries) { mutableStateOf(false) }
    val scrollbarMode = mode != LibrarySectionIndexMode.DesktopHover
    val mobileMode = mode == LibrarySectionIndexMode.MobileScrollbar
    val forceScrub = LocalLibrarySectionIndexForceScrub.current
    val shouldShowScrollHint = revealSignal && !forceScrub

    LaunchedEffect(scrubbing) {
        currentOnScrubbingChanged(scrubbing)
    }

    LaunchedEffect(shouldShowScrollHint, forceScrub) {
        if (scrollbarMode && shouldShowScrollHint) {
            hintVisible = true
        } else if (scrollbarMode && !scrubbing && !hovering && !forceScrub) {
            delay(ScrollHintVisibleMs)
            hintVisible = false
        }
    }

    LaunchedEffect(hovering, scrubbing, interactionLingerVisible, mode) {
        if (!mobileMode && interactionLingerVisible && !hovering && !scrubbing) {
            delay(SectionIndexInteractionLingerMs)
            interactionLingerVisible = false
            activeEntry = null
            if (scrollbarMode && shouldShowScrollHint) hintVisible = true
        }
    }

    LaunchedEffect(activeEntry, scrubbing, hovering, interactionLingerVisible) {
        if (activeEntry != null && !scrubbing && !hovering && !interactionLingerVisible) {
            delay(ActiveSectionIndexLabelMs)
            activeEntry = null
        }
    }

    fun entryAt(y: Float, height: Int): LibraryScrollIndexEntry? {
        if (height <= 0) return null
        val index = ((y.coerceIn(0f, height.toFloat()) / height) * entries.size)
            .toInt()
            .coerceIn(0, entries.lastIndex)
        return entries[index]
    }

    fun previewAt(y: Float, height: Int) {
        val entry = entryAt(y, height)
        if (entry != activeEntry) activeEntry = entry
    }

    fun selectEntry(entry: LibraryScrollIndexEntry) {
        activeEntry = entry
        if (lastSelectedItemIndex != entry.itemIndex) {
            lastSelectedItemIndex = entry.itemIndex
            onEntrySelected(entry)
        }
    }

    fun selectAt(y: Float, height: Int) {
        entryAt(y, height)?.let(::selectEntry)
    }

    val containerWidth = LibrarySectionIndexWidth
    BoxWithConstraints(
        modifier
            .width(containerWidth)
            .fillMaxHeight()
            .then(
                if (mobileMode) {
                    Modifier.mobileSectionIndexScrub(
                        hitWidth = LibrarySectionIndexHitWidth,
                        touchExclusionTopPx = { currentTouchExclusionTopPx() },
                        onScrubStart = { y, height ->
                            scrubbing = true
                            suppressNextExitLinger = false
                            interactionLingerVisible = false
                            hintVisible = false
                            lastSelectedItemIndex = null
                            selectAt(y, height)
                        },
                        onScrubMove = { y, height -> selectAt(y, height) },
                        onScrubEnd = {
                            scrubbing = false
                            interactionLingerVisible = false
                            hintVisible = false
                        },
                    )
                } else {
                    Modifier
                },
            )
    ) {
        val compactDateLabels = entries.any { it.label.length > 4 }
        val minLabelStep = if (compactDateLabels) 26.dp else 16.dp
        val maxVisibleLabels = remember(maxHeight, minLabelStep, entries.size) {
            val estimated = if (maxHeight.value.isFinite()) {
                (maxHeight / minLabelStep).toInt()
            } else {
                MaxVisibleSectionIndexLabels
            }
            estimated.coerceIn(2, MaxVisibleSectionIndexLabels).coerceAtMost(entries.size)
        }
        val visibleEntries = remember(entries, maxVisibleLabels) {
            entries.sampleForRailLabels(maxVisibleLabels)
        }
        val entryIndexes = remember(entries) {
            entries.withIndex().associate { it.value to it.index }
        }
        val forcedActiveEntry = if (forceScrub) {
            remember(entries) { entries.getOrNull(entries.size / 2) ?: entries.firstOrNull() }
        } else {
            null
        }
        val displayedActiveEntry = activeEntry ?: forcedActiveEntry
        val activeLabel = displayedActiveEntry?.label
        val activeEntryIndex = displayedActiveEntry?.let { entryIndexes[it] } ?: -1
        val previewBubbleWidth = if (mobileMode) MobileSectionIndexPreviewBubbleWidth else DesktopSectionIndexPreviewBubbleWidth
        val previewBubbleOffset = -(previewBubbleWidth + 8.dp)
        val labelsVisible = when (mode) {
            LibrarySectionIndexMode.DesktopHover -> hovering || scrubbing || interactionLingerVisible || forceScrub || keepLabelsVisible
            LibrarySectionIndexMode.DesktopScrollbar -> hovering || scrubbing || interactionLingerVisible || forceScrub || keepLabelsVisible
            LibrarySectionIndexMode.MobileScrollbar -> scrubbing || forceScrub
        }
        val previewBubbleVisible = displayedActiveEntry != null && (scrubbing || forceScrub)
        val railHeight = if (maxHeight.value.isFinite()) maxHeight else 160.dp

        AnimatedVisibility(
            visible = scrollbarMode && hintVisible && !labelsVisible,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(animationSpec = tween(120)) + slideInHorizontally(initialOffsetX = { it / 2 }),
            exit = fadeOut(animationSpec = tween(180)) + slideOutHorizontally(targetOffsetX = { it / 2 }),
        ) {
            val thumbColor = PhoebeUi.secondaryText.copy(alpha = 0.46f)
            val provider = scrollbarStateProvider
            if (provider != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val state = provider()
                    val thumbWidth = 3.dp.toPx()
                    val minThumbHeight = 28.dp.toPx()
                    val fallbackThumbHeight = 74.dp.toPx()
                    val railHeight = size.height
                    val thumbHeight = if (state?.hasScrollableContent == true) {
                        (railHeight * state.thumbSizeFraction).coerceIn(minThumbHeight, railHeight)
                    } else {
                        fallbackThumbHeight.coerceAtMost(railHeight)
                    }
                    val thumbTop = if (state?.hasScrollableContent == true) {
                        (railHeight - thumbHeight) * state.thumbTopFraction
                    } else {
                        (railHeight - thumbHeight) / 2f
                    }
                    drawRoundRect(
                        color = thumbColor,
                        topLeft = Offset(size.width - thumbWidth, thumbTop),
                        size = Size(thumbWidth, thumbHeight),
                        cornerRadius = CornerRadius(thumbWidth / 2f, thumbWidth / 2f),
                    )
                }
            } else {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    val state = scrollbarState
                    val thumbHeight = if (state?.hasScrollableContent == true) {
                        (railHeight * state.thumbSizeFraction).coerceIn(28.dp, railHeight)
                    } else {
                        74.dp.coerceAtMost(railHeight)
                    }
                    val thumbTop = if (state?.hasScrollableContent == true) {
                        (railHeight - thumbHeight) * state.thumbTopFraction
                    } else {
                        (railHeight - thumbHeight) / 2f
                    }
                    Box(
                        Modifier
                            .offset(y = thumbTop)
                            .width(3.dp)
                            .height(thumbHeight)
                            .clip(RoundedCornerShape(999.dp))
                            .background(thumbColor),
                    )
                }
            }
        }

        if (forceScrub && labelsVisible) {
            SectionIndexLabels(
                visibleEntries = visibleEntries,
                activeLabel = activeLabel,
                activeEntryIndex = activeEntryIndex,
                entryIndexes = entryIndexes,
                mobileMode = mobileMode,
                onEntryClick = { entry ->
                    suppressNextExitLinger = true
                    interactionLingerVisible = false
                    selectEntry(entry)
                },
            )
        } else {
            AnimatedVisibility(
                visible = labelsVisible,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(animationSpec = tween(120)) + slideInHorizontally(initialOffsetX = { it / 2 }),
                exit = fadeOut(animationSpec = tween(180)) + slideOutHorizontally(targetOffsetX = { it / 2 }),
            ) {
                SectionIndexLabels(
                    visibleEntries = visibleEntries,
                    activeLabel = activeLabel,
                    activeEntryIndex = activeEntryIndex,
                    entryIndexes = entryIndexes,
                    mobileMode = mobileMode,
                    onEntryClick = { entry ->
                        suppressNextExitLinger = true
                        interactionLingerVisible = false
                        selectEntry(entry)
                    },
                )
            }
        }

        if (forceScrub && previewBubbleVisible) {
            displayedActiveEntry.let { entry ->
                SectionIndexPreviewBubble(
                    entry = entry,
                    width = previewBubbleWidth,
                    maxLines = if (mobileMode) 2 else 1,
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = previewBubbleOffset),
                )
            }
        } else {
            AnimatedVisibility(
                visible = previewBubbleVisible,
                modifier = Modifier.align(Alignment.CenterStart).offset(x = previewBubbleOffset),
                enter = fadeIn(animationSpec = tween(110)) +
                    scaleIn(initialScale = 0.86f, animationSpec = tween(160)) +
                    slideInHorizontally(initialOffsetX = { it / 3 }, animationSpec = tween(160)),
                exit = fadeOut(animationSpec = tween(160)) +
                    scaleOut(targetScale = 0.92f, animationSpec = tween(160)) +
                    slideOutHorizontally(targetOffsetX = { it / 4 }, animationSpec = tween(160)),
            ) {
                displayedActiveEntry?.let { entry ->
                    SectionIndexPreviewBubble(
                        entry = entry,
                        width = previewBubbleWidth,
                        maxLines = if (mobileMode) 2 else 1,
                    )
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(
                    if (mode == LibrarySectionIndexMode.MobileScrollbar) {
                        LibrarySectionIndexHitWidth
                    } else {
                        LibrarySectionIndexDesktopHitWidth
                    },
                )
                .fillMaxHeight()
                .semantics { contentDescription = "Library section index" }
                .then(
                    if (mode != LibrarySectionIndexMode.MobileScrollbar) {
                        Modifier.pointerInput(entries) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    when (event.type) {
                                        PointerEventType.Enter, PointerEventType.Move -> {
                                            hovering = true
                                            interactionLingerVisible = false
                                            hintVisible = false
                                            event.changes.firstOrNull()?.let { change ->
                                                previewAt(change.position.y, size.height)
                                            }
                                        }
                                        PointerEventType.Exit -> {
                                            hovering = false
                                            if (!scrubbing) {
                                                if (suppressNextExitLinger) {
                                                    suppressNextExitLinger = false
                                                    interactionLingerVisible = false
                                                    activeEntry = null
                                                } else {
                                                    interactionLingerVisible = true
                                                }
                                                if (scrollbarMode && shouldShowScrollHint) hintVisible = true
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (mobileMode) {
                        // Mobile scrubbing is handled by mobileSectionIndexScrub on the container.
                        Modifier
                    } else {
                        Modifier
                            .pointerInput(entries) {
                                detectTapGestures { offset ->
                                    suppressNextExitLinger = true
                                    interactionLingerVisible = false
                                    selectAt(offset.y, size.height)
                                }
                            }
                            .pointerInput(entries) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        scrubbing = true
                                        suppressNextExitLinger = false
                                        interactionLingerVisible = false
                                        hintVisible = true
                                        lastSelectedItemIndex = null
                                        selectAt(offset.y, size.height)
                                    },
                                    onDragEnd = {
                                        scrubbing = false
                                        interactionLingerVisible = true
                                        hintVisible = false
                                    },
                                    onDragCancel = {
                                        scrubbing = false
                                        interactionLingerVisible = true
                                        hintVisible = false
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        selectAt(change.position.y, size.height)
                                    },
                                )
                            }
                    },
                ),
        )
    }
}

/**
 * Mobile scrub gesture for the section index rail.
 *
 * Unlike a plain pointerInput, this:
 *  - shares pointer input with siblings, so taps and swipes that aren't a scrub still reach the list
 *    underneath (row taps, header buttons such as the overflow menu);
 *  - waits for touch slop and only claims mostly-vertical drags, so horizontal edge swipes (system
 *    back / swipe-back navigation) never jump the list;
 *  - ignores touches outside the right-hand [hitWidth] strip or above [touchExclusionTopPx].
 *
 * It runs on the Initial pass so that, once it claims a drag, the consumed change stops the list's
 * own scroll gesture before it starts.
 */
private fun Modifier.mobileSectionIndexScrub(
    hitWidth: Dp,
    touchExclusionTopPx: () -> Float,
    onScrubStart: (y: Float, height: Int) -> Unit,
    onScrubMove: (y: Float, height: Int) -> Unit,
    onScrubEnd: () -> Unit,
): Modifier = this then MobileSectionIndexScrubElement(hitWidth, touchExclusionTopPx, onScrubStart, onScrubMove, onScrubEnd)

private data class MobileSectionIndexScrubElement(
    val hitWidth: Dp,
    val touchExclusionTopPx: () -> Float,
    val onScrubStart: (y: Float, height: Int) -> Unit,
    val onScrubMove: (y: Float, height: Int) -> Unit,
    val onScrubEnd: () -> Unit,
) : ModifierNodeElement<MobileSectionIndexScrubNode>() {
    override fun create() = MobileSectionIndexScrubNode(this)

    override fun update(node: MobileSectionIndexScrubNode) {
        node.element = this
    }
}

private class MobileSectionIndexScrubNode(
    var element: MobileSectionIndexScrubElement,
) : Modifier.Node(), PointerInputModifierNode, CompositionLocalConsumerModifierNode {
    private var trackedPointer: PointerId? = null
    private var downPosition = Offset.Zero
    private var scrubbing = false

    override fun sharePointerInputWithSiblings(): Boolean = true

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial) return
        val tracked = trackedPointer
        if (tracked == null) {
            val down = pointerEvent.changes.firstOrNull { it.changedToDown() } ?: return
            val hitWidthPx = with(currentValueOf(LocalDensity)) { element.hitWidth.toPx() }
            if (down.isConsumed) return
            if (down.position.x < bounds.width - hitWidthPx) return
            if (down.position.y < element.touchExclusionTopPx()) return
            trackedPointer = down.id
            downPosition = down.position
            return
        }

        val change = pointerEvent.changes.firstOrNull { it.id == tracked }
        if (change == null || !change.pressed) {
            // A tap that never crossed slop is left unconsumed so it reaches whatever is underneath.
            finish()
            return
        }
        if (scrubbing) {
            element.onScrubMove(change.position.y, bounds.height)
            change.consume()
            return
        }
        if (change.isConsumed) {
            reset()
            return
        }
        val dx = abs(change.position.x - downPosition.x)
        val dy = abs(change.position.y - downPosition.y)
        val touchSlop = currentValueOf(LocalViewConfiguration).touchSlop
        when {
            dy > touchSlop && dy > dx -> {
                scrubbing = true
                element.onScrubStart(change.position.y, bounds.height)
                change.consume()
            }
            dx > touchSlop -> reset()
        }
    }

    override fun onCancelPointerInput() {
        finish()
    }

    override fun onDetach() {
        reset()
    }

    private fun finish() {
        if (scrubbing) element.onScrubEnd()
        reset()
    }

    private fun reset() {
        trackedPointer = null
        scrubbing = false
    }
}

@Composable
private fun SectionIndexLabels(
    visibleEntries: List<LibraryScrollIndexEntry>,
    activeLabel: String?,
    activeEntryIndex: Int,
    entryIndexes: Map<LibraryScrollIndexEntry, Int>,
    mobileMode: Boolean,
    onEntryClick: (LibraryScrollIndexEntry) -> Unit,
) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        visibleEntries.forEach { entry ->
            val active = entry.label == activeLabel
            val distance = if (activeEntryIndex >= 0) {
                abs((entryIndexes[entry] ?: activeEntryIndex) - activeEntryIndex)
            } else {
                Int.MAX_VALUE
            }
            val scale = when (distance) {
                0 -> if (mobileMode) 1f else 2.55f
                1 -> if (mobileMode) 1f else 1.55f
                2 -> if (mobileMode) 1f else 1.22f
                3 -> if (mobileMode) 1f else 1.08f
                else -> 1f
            }
            Text(
                text = entry.label.railIndexLabel(),
                color = if (active) PhoebeUi.accentLight else PhoebeUi.secondaryText,
                fontSize = if (entry.label.length <= 4) 10.sp else 8.sp,
                lineHeight = if (entry.label.length <= 4) 10.sp else 8.sp,
                fontWeight = if (active) FontWeight.Black else FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = if (entry.label.length <= 4) 1 else 2,
                overflow = TextOverflow.Visible,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = -(scale - 1f) * 10f
                    }
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onEntryClick(entry) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun SectionIndexPreviewBubble(
    entry: LibraryScrollIndexEntry,
    width: Dp,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .width(width)
            .clip(RoundedCornerShape(18.dp))
            .background(PhoebeUi.accent.copy(alpha = 0.94f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = entry.label,
            color = Color.White,
            fontSize = 16.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

fun libraryArtistScrollIndex(
    artists: List<Artist>,
    sortBy: LibrarySortBy,
    ascending: Boolean,
): List<LibraryScrollIndexEntry> =
    when (sortBy) {
        LibrarySortBy.DateAdded -> buildGroupedScrollIndex(artists) { monthYearLabelFromEpochMs(it.dateAddedMs) ?: "Unknown" }
        else -> buildAlphaScrollIndex(artists, ascending) { it.title }
    }

fun libraryAlbumScrollIndex(
    albums: List<Album>,
    sortBy: LibrarySortBy,
    ascending: Boolean,
): List<LibraryScrollIndexEntry> =
    when (sortBy) {
        LibrarySortBy.Artist -> buildAlphaScrollIndex(albums, ascending) { it.artist }
        LibrarySortBy.Year -> buildGroupedScrollIndex(albums) { it.year?.toString() ?: "Unknown" }
        LibrarySortBy.DateAdded -> buildGroupedScrollIndex(albums) { monthYearLabelFromEpochMs(it.dateAddedMs) ?: "Unknown" }
        else -> buildAlphaScrollIndex(albums, ascending) { it.title }
    }

fun libraryTrackScrollIndex(
    tracks: List<Track>,
    sortBy: LibrarySortBy,
    ascending: Boolean,
): List<LibraryScrollIndexEntry> =
    when (sortBy) {
        LibrarySortBy.Album -> buildAlphaScrollIndex(tracks, ascending) { it.album }
        LibrarySortBy.Artist -> buildAlphaScrollIndex(tracks, ascending) { it.artist }
        LibrarySortBy.Year -> buildGroupedScrollIndex(tracks) { it.year?.toString() ?: "Unknown" }
        LibrarySortBy.DateAdded -> buildGroupedScrollIndex(tracks) { monthYearLabelFromEpochMs(it.dateAddedMs) ?: "Unknown" }
        else -> buildAlphaScrollIndex(tracks, ascending) { it.title }
    }

fun List<LibraryScrollIndexEntry>.sampleForRailLabels(maxVisibleLabels: Int): List<LibraryScrollIndexEntry> {
    if (size <= maxVisibleLabels) return this
    val every = ((size + maxVisibleLabels - 1) / maxVisibleLabels).coerceAtLeast(1)
    return filterIndexed { index, _ -> index == 0 || index == lastIndex || index % every == 0 }
}

fun String.railIndexLabel(): String {
    val parts = split(' ')
    if (parts.size == 2 && parts[0].length == 3 && parts[1].length == 4) {
        return "${parts[0]}\n${parts[1].takeLast(2)}"
    }
    return this
}

private fun <T> buildGroupedScrollIndex(
    items: List<T>,
    labelFor: (T) -> String,
): List<LibraryScrollIndexEntry> {
    val entries = mutableListOf<LibraryScrollIndexEntry>()
    var previousLabel: String? = null
    items.forEachIndexed { index, item ->
        val label = labelFor(item)
        if (label != previousLabel) {
            entries += LibraryScrollIndexEntry(label = label, itemIndex = index)
            previousLabel = label
        }
    }
    return entries
}

private fun <T> buildAlphaScrollIndex(
    items: List<T>,
    ascending: Boolean,
    labelFor: (T) -> String,
): List<LibraryScrollIndexEntry> {
    if (items.isEmpty()) return emptyList()
    val labelledItems = items.mapIndexed { index, item ->
        val label = alphaLabel(labelFor(item))
        AlphaLabelledItem(label = label, rank = alphaRank(label), itemIndex = index)
    }
    val labels = buildList {
        if (labelledItems.any { it.label == "#" }) add("#")
        addAll(AlphaLabels)
    }.let { if (ascending) it else it.asReversed() }

    return labels.map { label ->
        val rank = alphaRank(label)
        val target = if (ascending) {
            labelledItems.firstOrNull { it.rank >= rank } ?: labelledItems.last()
        } else {
            labelledItems.firstOrNull { it.rank <= rank } ?: labelledItems.last()
        }
        LibraryScrollIndexEntry(label = label, itemIndex = target.itemIndex)
    }
}

private data class AlphaLabelledItem(
    val label: String,
    val rank: Int,
    val itemIndex: Int,
)

private fun alphaLabel(value: String): String {
    val first = value.trim().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (first in 'A'..'Z') first.toString() else "#"
}

private fun alphaRank(label: String): Int =
    if (label == "#") 0 else (label.firstOrNull()?.uppercaseChar()?.code ?: 'A'.code) - 'A'.code + 1

fun monthYearLabelFromEpochMs(epochMs: Long?): String? {
    if (epochMs == null || epochMs <= 0L) return null
    val days = floorDiv(epochMs, MillisPerDay)
    val (year, month) = yearMonthFromEpochDay(days)
    return "${MonthLabels[month - 1]} $year"
}

private fun floorDiv(value: Long, divisor: Long): Long =
    if (value >= 0L) value / divisor else -((-value + divisor - 1L) / divisor)

private fun yearMonthFromEpochDay(epochDay: Long): Pair<Int, Int> {
    val z = epochDay + 719_468L
    val era = if (z >= 0L) z / 146_097L else (z - 146_096L) / 146_097L
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val month = (monthPrime + if (monthPrime < 10L) 3L else -9L).toInt()
    val year = (yearOfEra + era * 400L + if (month <= 2) 1L else 0L).toInt()
    return year to month
}
