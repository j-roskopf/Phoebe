package com.phoebe.app.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.Track
import com.phoebe.app.ui.ArtworkImage
import com.phoebe.app.ui.ListArtworkMaxDecodeDimension
import com.phoebe.app.ui.PhoebeUi
import com.phoebe.app.ui.sharedBoundsTransition
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val CoverFlowVisibleSideCount = 5
private const val CoverFlowMaxRotationDegrees = 58f
private const val CoverFlowDecodeDimension = ListArtworkMaxDecodeDimension
/** Desktop mouse drags often stay under touchSlop; treat small intentional motion as a drag. */
private const val CoverFlowDragSlopPx = 6f

enum class CoverFlowKind { Artists, Albums, Songs }

/**
 * Survives LibraryCoverFlow leaving composition (e.g. open artist detail and back).
 * In-memory only — good enough for browse navigation within a session.
 */
internal object CoverFlowScrollStore {
    private val positions = FloatArray(CoverFlowKind.entries.size) { 0f }

    fun get(kind: CoverFlowKind): Float = positions[kind.ordinal]

    fun set(kind: CoverFlowKind, value: Float) {
        positions[kind.ordinal] = value
    }

    fun reset() {
        positions.fill(0f)
    }
}

/** Clears in-memory cover-flow scroll so screenshot runs start at index 0. */
fun resetCoverFlowScrollStore() {
    CoverFlowScrollStore.reset()
}

@Immutable
data class CoverFlowItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbUrl: String?,
    val sharedKey: String,
)

/**
 * iPod-classic-style Cover Flow.
 *
 * Scroll position is a fractional index; transforms are applied in [graphicsLayer]
 * so drag/wheel frames stay in the draw phase instead of recomposing every cover.
 * Visible covers stay in a stable ascending [key] order so artwork is not remounted
 * when an item crosses center.
 */
@Composable
fun LibraryCoverFlow(
    items: List<CoverFlowItem>,
    selectedId: String?,
    onSelect: (CoverFlowItem) -> Unit,
    onOpen: (CoverFlowItem) -> Unit,
    kind: CoverFlowKind,
    modifier: Modifier = Modifier,
    contentDescription: String = "Cover flow",
) {
    if (items.isEmpty()) {
        Box(modifier.padding(top = 24.dp), contentAlignment = Alignment.TopCenter) {
            Text("Nothing here yet.", color = PhoebeUi.mutedText, fontSize = 13.sp)
        }
        return
    }

    val scrollPosition = remember(kind) {
        mutableFloatStateOf(CoverFlowScrollStore.get(kind))
    }
    val spacingPx = remember { mutableFloatStateOf(1f) }
    val centerGapPx = remember { mutableFloatStateOf(1f) }
    val coverSizePxState = remember { mutableFloatStateOf(1f) }
    var interactionActive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val latestItems by rememberUpdatedState(items)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnOpen by rememberUpdatedState(onOpen)

    // Keep index in bounds when the list shrinks. Only jump for an external selection that
    // is meaningfully away from the current center (avoids fighting wheel/drag settle).
    LaunchedEffect(items.size, selectedId, kind) {
        val max = (items.size - 1).coerceAtLeast(0).toFloat()
        scrollPosition.floatValue = scrollPosition.floatValue.coerceIn(0f, max)
        val selectedIndex = selectedId
            ?.let { id -> items.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
        if (selectedIndex != null &&
            (scrollPosition.floatValue - selectedIndex).absoluteValue > 0.55f
        ) {
            scrollPosition.floatValue = selectedIndex.toFloat()
        }
        CoverFlowScrollStore.set(kind, scrollPosition.floatValue)
    }

    DisposableEffect(kind) {
        onDispose {
            CoverFlowScrollStore.set(kind, scrollPosition.floatValue)
        }
    }

    LaunchedEffect(scrollPosition, items, interactionActive) {
        snapshotFlow {
            if (interactionActive) {
                -1
            } else {
                scrollPosition.floatValue.roundToInt().coerceIn(0, latestItems.lastIndex)
            }
        }
            .distinctUntilChanged()
            .collect { index ->
                if (index >= 0) {
                    CoverFlowScrollStore.set(kind, scrollPosition.floatValue)
                    latestItems.getOrNull(index)?.let(latestOnSelect)
                }
            }
    }

    val windowCenter by remember(items.size) {
        derivedStateOf {
            scrollPosition.floatValue.roundToInt().coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }
    }
    val centeredItem = items.getOrNull(windowCenter)

    fun applyScrollDelta(deltaPx: Float): Float {
        val spacing = spacingPx.floatValue.coerceAtLeast(1f)
        val max = (latestItems.size - 1).coerceAtLeast(0).toFloat()
        val previous = scrollPosition.floatValue
        val next = (previous - deltaPx / spacing).coerceIn(0f, max)
        scrollPosition.floatValue = next
        return -(next - previous) * spacing
    }

    val scrollableState = rememberScrollableState { deltaPx -> applyScrollDelta(deltaPx) }

    // Single shared Animatable so a new settle animation cancels any in-flight one instead of
    // racing independent Animatable instances over the shared scrollPosition.
    val coverFlowAnimator = remember(kind) { Animatable(scrollPosition.floatValue) }

    // Wheel/trackpad scrolling goes through the scrollable fling; snap the result to the
    // nearest cover index so the carousel never settles between covers.
    val baseFling = ScrollableDefaults.flingBehavior()
    val snappingFling = remember(scrollPosition) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val final = with(baseFling) { performFling(initialVelocity) }
                scrollPosition.floatValue = final
                val target = scrollPosition.floatValue.roundToInt()
                    .coerceIn(0, latestItems.lastIndex)
                    .toFloat()
                if (target != scrollPosition.floatValue) {
                    animateCoverFlowTo(coverFlowAnimator, scrollPosition, target)
                }
                CoverFlowScrollStore.set(kind, scrollPosition.floatValue)
                latestItems
                    .getOrNull(scrollPosition.floatValue.roundToInt().coerceIn(0, latestItems.lastIndex))
                    ?.let(latestOnSelect)
                return scrollPosition.floatValue
            }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.Spacebar)) {
                    centeredItem?.let(latestOnOpen)
                    true
                } else {
                    false
                }
            }
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
                onClick(label = "Open ${centeredItem?.title.orEmpty()}") {
                    if (centeredItem != null) {
                        latestOnOpen(centeredItem)
                        true
                    } else {
                        false
                    }
                }
            },
    ) {
        // Width-driven cover size; wrap vertically so parents can center this block
        // in the remaining library viewport.
        val coverSize = (maxWidth * 0.42f).coerceIn(120.dp, 220.dp)
        val reflectionHeight = coverSize * 0.42f
        val stageHeight = coverSize + reflectionHeight
        val coverSizePx = with(LocalDensity.current) { coverSize.toPx() }
        val sideSpacing = coverSizePx * 0.34f
        val centerGap = coverSizePx * 0.72f
        SideEffect {
            spacingPx.floatValue = sideSpacing
            centerGapPx.floatValue = centerGap
            coverSizePxState.floatValue = coverSizePx
        }
        val density = LocalDensity.current
        val cameraDistancePx = 14f * density.density
        val from = (windowCenter - CoverFlowVisibleSideCount).coerceAtLeast(0)
        val to = (windowCenter + CoverFlowVisibleSideCount).coerceAtMost(items.lastIndex)

        // Tight wrap-content height so parent spacers can truly center covers + labels.
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(stageHeight)
                    .clipToBounds()
                    .scrollable(
                        state = scrollableState,
                        orientation = Orientation.Horizontal,
                        flingBehavior = snappingFling,
                    )
                    .pointerInput(items.size, kind) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val tracker = VelocityTracker()
                        tracker.addPointerInputChange(down)
                        var totalDragX = 0f
                        var dragging = false
                        var flingStarted = false
                        val dragSlop = maxOf(viewConfiguration.touchSlop, CoverFlowDragSlopPx)
                        interactionActive = true
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (dragging || totalDragX.absoluteValue > dragSlop) {
                                        val velocityX = tracker.calculateVelocity().x
                                        flingStarted = true
                                        scope.launch {
                                            try {
                                                flingCoverFlow(
                                                    animator = coverFlowAnimator,
                                                    scrollPosition = scrollPosition,
                                                    itemCount = latestItems.size,
                                                    spacingPx = spacingPx.floatValue,
                                                    velocityPxPerSec = velocityX,
                                                )
                                                CoverFlowScrollStore.set(kind, scrollPosition.floatValue)
                                                latestItems
                                                    .getOrNull(
                                                        scrollPosition.floatValue.roundToInt()
                                                            .coerceIn(0, latestItems.lastIndex),
                                                    )
                                                    ?.let(latestOnSelect)
                                            } finally {
                                                interactionActive = false
                                            }
                                        }
                                    } else {
                                        val centerX = size.width / 2f
                                        val dx = down.position.x - centerX
                                        val cover = coverSizePxState.floatValue.coerceAtLeast(1f)
                                        val gap = centerGapPx.floatValue.coerceAtLeast(cover * 0.5f)
                                        val side = spacingPx.floatValue.coerceAtLeast(1f)
                                        val indexOffset = when {
                                            dx.absoluteValue < cover * 0.55f -> 0
                                            else -> {
                                                val beyond = (dx.absoluteValue - gap).coerceAtLeast(0f)
                                                (sign(dx) * (1 + (beyond / side).toInt())).toInt()
                                            }
                                        }
                                        val targetIndex = (scrollPosition.floatValue.roundToInt() + indexOffset)
                                            .coerceIn(0, latestItems.lastIndex)
                                        val targetItem = latestItems.getOrNull(targetIndex)
                                        if (targetItem != null) {
                                            if (indexOffset == 0) {
                                                latestOnOpen(targetItem)
                                            } else {
                                                scope.launch {
                                                    animateCoverFlowTo(coverFlowAnimator, scrollPosition, targetIndex.toFloat())
                                                    CoverFlowScrollStore.set(kind, scrollPosition.floatValue)
                                                    latestOnSelect(targetItem)
                                                }
                                            }
                                        }
                                    }
                                    return@awaitEachGesture
                                }

                                val delta = change.positionChange().x
                                tracker.addPointerInputChange(change)
                                totalDragX += delta
                                if (!dragging && totalDragX.absoluteValue > dragSlop) {
                                    dragging = true
                                }
                                if (dragging) {
                                    change.consume()
                                    applyScrollDelta(delta)
                                }
                            }
                        } finally {
                            if (!flingStarted) {
                                interactionActive = false
                                CoverFlowScrollStore.set(kind, scrollPosition.floatValue)
                            }
                        }
                    }
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                for (index in from..to) {
                    val item = items[index]
                    key(item.id) {
                        CoverFlowArtItem(
                            item = item,
                            index = index,
                            scrollPosition = { scrollPosition.floatValue },
                            coverSizePx = coverSizePx,
                            sideSpacing = sideSpacing,
                            centerGap = centerGap,
                            cameraDistancePx = cameraDistancePx,
                            stackZIndex = (CoverFlowVisibleSideCount + 1 - (index - windowCenter).absoluteValue).toFloat(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            AnimatedContent(
                targetState = centeredItem,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "coverFlowMeta",
                contentKey = { it?.id },
            ) { item ->
                Column(
                    Modifier
                        .widthIn(max = 320.dp)
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        item?.title.orEmpty(),
                        color = PhoebeUi.primaryText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (item != null) Modifier.sharedBoundsTransition("${item.sharedKey}:title")
                                else Modifier,
                            ),
                    )
                    Text(
                        item?.subtitle.orEmpty(),
                        color = PhoebeUi.secondaryText,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                            .then(
                                if (item != null) Modifier.sharedBoundsTransition("${item.sharedKey}:subtitle")
                                else Modifier,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumCoverFlow(
    albums: List<Album>,
    selectedAlbumId: String?,
    onSelect: (Album) -> Unit,
    onOpen: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(albums) {
        albums.map { album ->
            CoverFlowItem(
                id = album.id,
                title = album.title,
                subtitle = album.artist,
                thumbUrl = album.thumbUrl,
                sharedKey = "album:${album.id}",
            )
        }
    }
    val albumById = remember(albums) { albums.associateBy { it.id } }
    LibraryCoverFlow(
        items = items,
        selectedId = selectedAlbumId,
        onSelect = { item -> albumById[item.id]?.let(onSelect) },
        onOpen = { item -> albumById[item.id]?.let(onOpen) },
        kind = CoverFlowKind.Albums,
        modifier = modifier,
        contentDescription = "Album flow",
    )
}

@Composable
fun ArtistCoverFlow(
    artists: List<Artist>,
    selectedArtistId: String?,
    onSelect: (Artist) -> Unit,
    onOpen: (Artist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(artists) {
        artists.map { artist ->
            CoverFlowItem(
                id = artist.id,
                title = artist.title,
                subtitle = buildString {
                    if (artist.albumCount > 0) append("${artist.albumCount} albums")
                    if (artist.songCount > 0) {
                        if (isNotEmpty()) append(" • ")
                        append("${artist.songCount} songs")
                    }
                },
                thumbUrl = artist.thumbUrl,
                sharedKey = "artist:${artist.id}",
            )
        }
    }
    val artistById = remember(artists) { artists.associateBy { it.id } }
    LibraryCoverFlow(
        items = items,
        selectedId = selectedArtistId,
        onSelect = { item -> artistById[item.id]?.let(onSelect) },
        onOpen = { item -> artistById[item.id]?.let(onOpen) },
        kind = CoverFlowKind.Artists,
        modifier = modifier,
        contentDescription = "Artist flow",
    )
}

@Composable
fun TrackCoverFlow(
    tracks: List<Track>,
    selectedTrackId: String?,
    onSelect: (Track) -> Unit,
    onOpen: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(tracks) {
        tracks.map { track ->
            CoverFlowItem(
                id = track.id,
                title = track.title,
                subtitle = buildString {
                    append(track.artist)
                    if (track.album.isNotBlank()) {
                        append(" • ")
                        append(track.album)
                    }
                },
                thumbUrl = track.thumbUrl ?: track.localArtworkUri,
                sharedKey = "song:${track.id}",
            )
        }
    }
    val trackById = remember(tracks) { tracks.associateBy { it.id } }
    LibraryCoverFlow(
        items = items,
        selectedId = selectedTrackId,
        onSelect = { item -> trackById[item.id]?.let(onSelect) },
        onOpen = { item -> trackById[item.id]?.let(onOpen) },
        kind = CoverFlowKind.Songs,
        modifier = modifier,
        contentDescription = "Song flow",
    )
}

@Composable
private fun CoverFlowArtItem(
    item: CoverFlowItem,
    index: Int,
    scrollPosition: () -> Float,
    coverSizePx: Float,
    sideSpacing: Float,
    centerGap: Float,
    cameraDistancePx: Float,
    stackZIndex: Float,
) {
    val coverSize = with(LocalDensity.current) { coverSizePx.toDp() }
    val reflectionHeight = coverSize * 0.42f

    Box(
        Modifier
            .zIndex(stackZIndex)
            .graphicsLayer {
                val distance = index - scrollPosition()
                val absDistance = distance.absoluteValue
                val clamped = absDistance.coerceAtMost(1f)
                val sideExtra = (absDistance - 1f).coerceAtLeast(0f)

                rotationY = -sign(distance) * CoverFlowMaxRotationDegrees * clamped
                translationX = when {
                    absDistance <= 1f -> distance * centerGap
                    else -> sign(distance) * (centerGap + sideExtra * sideSpacing)
                }
                scaleX = lerp(1f, 0.92f, clamped)
                scaleY = scaleX
                alpha = lerp(1f, 0.35f, ((absDistance - (CoverFlowVisibleSideCount - 2)) / 2f).coerceIn(0f, 1f))
                cameraDistance = cameraDistancePx
            }
            .size(coverSize),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(4.dp)),
            ) {
                ArtworkImage(
                    item.title,
                    item.thumbUrl,
                    Modifier.fillMaxSize(),
                    radius = 4.dp,
                    elevated = false,
                    maxDecodeDimension = CoverFlowDecodeDimension,
                )
            }
            // Always composed so crossing center does not remount artwork; alpha is draw-phase.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(reflectionHeight)
                    .graphicsLayer {
                        val distance = (index - scrollPosition()).absoluteValue
                        val reflectionVisibility = (1f - distance).coerceIn(0f, 1f)
                        compositingStrategy = CompositingStrategy.Offscreen
                        alpha = 0.45f * reflectionVisibility
                        scaleY = -1f
                        translationY = -coverSizePx * 0.02f
                    }
                    .drawWithCache {
                        val fade = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Black,
                                0.35f to Color.Black.copy(alpha = 0.55f),
                                1.0f to Color.Transparent,
                            ),
                        )
                        onDrawWithContent {
                            drawContent()
                            drawRect(brush = fade, blendMode = BlendMode.DstIn)
                        }
                    }
                    .clip(RoundedCornerShape(4.dp)),
            ) {
                ArtworkImage(
                    item.title,
                    item.thumbUrl,
                    Modifier.fillMaxSize(),
                    radius = 4.dp,
                    elevated = false,
                    maxDecodeDimension = CoverFlowDecodeDimension,
                )
            }
        }
    }
}

private suspend fun flingCoverFlow(
    animator: Animatable<Float, AnimationVector1D>,
    scrollPosition: MutableFloatState,
    itemCount: Int,
    spacingPx: Float,
    velocityPxPerSec: Float,
) {
    if (itemCount <= 0) return
    val spacing = spacingPx.coerceAtLeast(1f)
    val velocityInIndices = -velocityPxPerSec / spacing
    val projected = scrollPosition.floatValue + velocityInIndices * 0.18f
    val target = projected
        .roundToInt()
        .coerceIn(0, itemCount - 1)
        .toFloat()
    animateCoverFlowTo(
        animator = animator,
        scrollPosition = scrollPosition,
        target = target,
        initialVelocity = velocityInIndices,
    )
}

private suspend fun animateCoverFlowTo(
    animator: Animatable<Float, AnimationVector1D>,
    scrollPosition: MutableFloatState,
    target: Float,
    initialVelocity: Float = 0f,
) {
    // snapTo cancels any in-flight animation on the shared animator and syncs to the current
    // drag/wheel position before starting the new settle.
    animator.snapTo(scrollPosition.floatValue)
    animator.animateTo(
        targetValue = target,
        initialVelocity = initialVelocity,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
    ) {
        scrollPosition.floatValue = value
    }
}

fun LibraryViewMode.label(): String = when (this) {
    LibraryViewMode.Grid -> "Grid"
    LibraryViewMode.List -> "List"
    LibraryViewMode.Flow -> "Flow"
}

fun LibraryUiPreferences.resolvedViewMode(): LibraryViewMode =
    runCatching { LibraryViewMode.valueOf(viewMode) }.getOrDefault(LibraryViewMode.Grid)
