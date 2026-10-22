package com.phoebe.app.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.Track
import com.phoebe.app.ui.ArtworkImage
import com.phoebe.app.ui.PhoebeUi
import com.phoebe.app.ui.libraryGridDecodeDimension
import com.phoebe.app.ui.phoebeClickable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Tiles past the viewport edge kept composed so panning never reveals an empty cell. */
private const val ImmersiveOverscanCells = 1
/** Smallest scale applied to tiles at the viewport corners. */
private const val ImmersiveEdgeScale = 0.62f
private const val ImmersiveEdgeAlpha = 0.35f
/** Mouse wheel / trackpad delta multiplier, as a fraction of one cell per scroll unit. */
private const val ImmersiveWheelCellFraction = 0.4f

/**
 * Survives LibraryImmersiveGrid leaving composition (e.g. open album detail and back).
 * Stores the camera as a fraction of the pannable bounds so window resizes keep the
 * same region in view. Null means "start in the middle".
 */
internal object ImmersiveScrollStore {
    private val positions = arrayOfNulls<Offset>(CoverFlowKind.entries.size)

    fun get(kind: CoverFlowKind): Offset? = positions[kind.ordinal]

    fun set(kind: CoverFlowKind, value: Offset) {
        positions[kind.ordinal] = value
    }

    fun reset() {
        positions.fill(null)
    }
}

/** Clears in-memory immersive pan so screenshot runs start centered. */
fun resetImmersiveScrollStore() {
    ImmersiveScrollStore.reset()
}

@Immutable
private data class ImmersiveWindow(val rowFrom: Int, val rowTo: Int, val colFrom: Int, val colTo: Int)

/**
 * Free-panning 2D artwork wall. Items are laid out row-major in a roughly square,
 * brick-staggered grid and the camera opens on the middle of it; drag, fling, wheel,
 * trackpad and arrow keys pan in any direction.
 *
 * Only tiles near the viewport are composed. The camera is read in layout/draw
 * ([Layout] placement and [graphicsLayer]) so panning frames do not recompose tiles;
 * composition only changes when the visible row/column window shifts.
 */
@Composable
fun LibraryImmersiveGrid(
    items: List<CoverFlowItem>,
    onOpen: (CoverFlowItem) -> Unit,
    kind: CoverFlowKind,
    modifier: Modifier = Modifier,
    contentDescription: String = "Immersive grid",
) {
    if (items.isEmpty()) {
        Box(modifier.padding(top = 24.dp), contentAlignment = Alignment.TopCenter) {
            Text("Nothing here yet.", color = PhoebeUi.mutedText, fontSize = 13.sp)
        }
        return
    }

    val latestOnOpen by rememberUpdatedState(onOpen)
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .semantics { this.contentDescription = contentDescription },
    ) {
        val density = LocalDensity.current
        val viewportW = constraints.maxWidth.toFloat()
        val viewportH = constraints.maxHeight.toFloat()
        val cellDp = (minOf(maxWidth, maxHeight) / 3.4f).coerceIn(96.dp, 184.dp)
        val gapDp = (cellDp * 0.1f).coerceIn(8.dp, 16.dp)
        val cellPx = with(density) { cellDp.toPx() }
        val pitch = cellPx + with(density) { gapDp.toPx() }
        val decodeDimension = libraryGridDecodeDimension(cellDp.value.roundToInt())

        val count = items.size
        val cols = remember(count) { ceil(sqrt(count.toDouble())).toInt().coerceAtLeast(1) }
        val rows = (count + cols - 1) / cols
        // Odd rows shift half a cell so the wall reads as bricks rather than a spreadsheet.
        fun stagger(row: Int): Float = if (row % 2 == 1) pitch / 2f else 0f
        val maxX = (cols - 1) * pitch + if (rows > 1) pitch / 2f else 0f
        val maxY = (rows - 1) * pitch

        // Camera = content coordinate (tile-center space) shown at the viewport center.
        var camera by remember(kind) {
            val fraction = ImmersiveScrollStore.get(kind) ?: Offset(0.5f, 0.5f)
            mutableStateOf(Offset(fraction.x * maxX, fraction.y * maxY))
        }
        var lastBounds by remember(kind) { mutableStateOf(Offset(maxX, maxY)) }
        var flingJob by remember { mutableStateOf<Job?>(null) }
        fun cameraFraction(bounds: Offset = Offset(maxX, maxY)): Offset = Offset(
            if (bounds.x > 0f) (camera.x / bounds.x).coerceIn(0f, 1f) else 0.5f,
            if (bounds.y > 0f) (camera.y / bounds.y).coerceIn(0f, 1f) else 0.5f,
        )
        // Resizes and catalog changes rescale the camera so the same region stays in view.
        LaunchedEffect(maxX, maxY) {
            // In-flight animations captured the old bounds and would overwrite the rescale.
            flingJob?.cancel()
            val fraction = cameraFraction(lastBounds)
            camera = Offset(fraction.x * maxX, fraction.y * maxY)
            lastBounds = Offset(maxX, maxY)
        }
        DisposableEffect(kind) {
            onDispose { ImmersiveScrollStore.set(kind, cameraFraction(lastBounds)) }
        }

        fun clampCamera(value: Offset) = Offset(value.x.coerceIn(0f, maxX), value.y.coerceIn(0f, maxY))
        fun panBy(delta: Offset) {
            camera = clampCamera(camera + delta)
        }

        fun animateCameraTo(target: Offset) {
            flingJob?.cancel()
            flingJob = scope.launch {
                val from = camera
                val to = clampCamera(target)
                animate(
                    0f,
                    1f,
                    animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                ) { t, _ ->
                    camera = Offset(lerp(from.x, to.x, t), lerp(from.y, to.y, t))
                }
            }
        }
        fun fling(velocity: Offset) {
            flingJob?.cancel()
            flingJob = scope.launch {
                val start = camera
                val decay = FloatExponentialDecaySpec(frictionMultiplier = 1.4f)
                coroutineScope {
                    launch {
                        animateDecay(start.x, velocity.x, decay) { value, _ ->
                            camera = Offset(value.coerceIn(0f, maxX), camera.y)
                        }
                    }
                    launch {
                        animateDecay(start.y, velocity.y, decay) { value, _ ->
                            camera = Offset(camera.x, value.coerceIn(0f, maxY))
                        }
                    }
                }
            }
        }

        val window by remember(rows, cols, pitch, viewportW, viewportH) {
            derivedStateOf {
                val c = camera
                val halfW = viewportW / 2f + pitch * ImmersiveOverscanCells
                val halfH = viewportH / 2f + pitch * ImmersiveOverscanCells
                ImmersiveWindow(
                    rowFrom = floor((c.y - halfH) / pitch).toInt().coerceIn(0, rows - 1),
                    rowTo = ceil((c.y + halfH) / pitch).toInt().coerceIn(0, rows - 1),
                    colFrom = floor((c.x - halfW - pitch / 2f) / pitch).toInt().coerceIn(0, cols - 1),
                    colTo = ceil((c.x + halfW) / pitch).toInt().coerceIn(0, cols - 1),
                )
            }
        }
        val centeredIndex by remember(rows, cols, pitch, count) {
            derivedStateOf {
                val c = camera
                // Staggered rows can put the nearest tile in either adjacent row, so compare both.
                val rowEstimate = c.y / pitch
                val firstRow = floor(rowEstimate).toInt().coerceIn(0, rows - 1)
                var bestIndex = 0
                var bestDistance = Float.MAX_VALUE
                for (row in firstRow..(firstRow + 1).coerceAtMost(rows - 1)) {
                    // Last row may be incomplete; only consider columns that hold an item.
                    val columnsInRow = (count - row * cols).coerceIn(1, cols)
                    val col = ((c.x - stagger(row)) / pitch).roundToInt().coerceIn(0, columnsInRow - 1)
                    val dx = col * pitch + stagger(row) - c.x
                    val dy = row * pitch - c.y
                    val distance = dx * dx + dy * dy
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestIndex = row * cols + col
                    }
                }
                bestIndex
            }
        }
        val centeredItem = items.getOrNull(centeredIndex)

        val visibleIndices = remember(window, cols, count) {
            buildList {
                for (row in window.rowFrom..window.rowTo) {
                    for (col in window.colFrom..window.colTo) {
                        val index = row * cols + col
                        if (index < count) add(index)
                    }
                }
            }
        }

        Layout(
            content = {
                for (index in visibleIndices) {
                    val item = items[index]
                    key(item.id) {
                        val row = index / cols
                        val col = index % cols
                        val centerX = col * pitch + stagger(row)
                        val centerY = row * pitch
                        ImmersiveTile(
                            item = item,
                            decodeDimension = decodeDimension,
                            onClick = { latestOnOpen(item) },
                            modifier = Modifier
                                .layoutId(index)
                                .graphicsLayer {
                                    // Soft fisheye: tiles shrink and fade toward the viewport edges.
                                    val c = camera
                                    val dx = (centerX - c.x) / (viewportW / 2f).coerceAtLeast(1f)
                                    val dy = (centerY - c.y) / (viewportH / 2f).coerceAtLeast(1f)
                                    val distance = (sqrt(dx * dx + dy * dy) / 1.414f).coerceIn(0f, 1f)
                                    val eased = distance * distance
                                    val scale = lerp(1f, ImmersiveEdgeScale, eased)
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = lerp(1f, ImmersiveEdgeAlpha, eased)
                                },
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val step = when (event.key) {
                        Key.DirectionLeft -> Offset(-pitch, 0f)
                        Key.DirectionRight -> Offset(pitch, 0f)
                        Key.DirectionUp -> Offset(0f, -pitch)
                        Key.DirectionDown -> Offset(0f, pitch)
                        Key.Enter, Key.Spacebar -> {
                            centeredItem?.let(latestOnOpen)
                            return@onKeyEvent true
                        }
                        else -> return@onKeyEvent false
                    }
                    animateCameraTo(camera + step)
                    true
                }
                .focusable()
                .pointerInput(pitch, maxX, maxY) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val scroll = change.scrollDelta
                            if (scroll == Offset.Zero) {
                                // Trackpad panning arrives in pixels, not wheel notches.
                                val pan = change.panOffset
                                if (pan != Offset.Zero) {
                                    flingJob?.cancel()
                                    panBy(-pan)
                                    change.consume()
                                }
                                continue
                            }
                            var delta = Offset(
                                if (abs(scroll.x) > 3f) scroll.x / 32f else scroll.x,
                                if (abs(scroll.y) > 3f) scroll.y / 32f else scroll.y,
                            )
                            // Plain mouse wheels only report Y; shift turns it into a sideways pan.
                            if (event.keyboardModifiers.isShiftPressed && delta.x == 0f) {
                                delta = Offset(delta.y, 0f)
                            }
                            // Cap per-event movement so raw pixel deltas cannot jump whole screens.
                            delta = Offset(delta.x.coerceIn(-3f, 3f), delta.y.coerceIn(-3f, 3f))
                            if (delta != Offset.Zero) {
                                flingJob?.cancel()
                                panBy(delta * (pitch * ImmersiveWheelCellFraction))
                                change.consume()
                            }
                        }
                    }
                }
                .pointerInput(pitch, maxX, maxY) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        flingJob?.cancel()
                        val tracker = VelocityTracker()
                        tracker.addPointerInputChange(down)
                        val slopChange = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        } ?: return@awaitEachGesture
                        tracker.addPointerInputChange(slopChange)
                        panBy(-slopChange.positionChange())
                        drag(slopChange.id) { change ->
                            tracker.addPointerInputChange(change)
                            panBy(-change.positionChange())
                            change.consume()
                        }
                        val velocity = tracker.calculateVelocity()
                        fling(Offset(-velocity.x, -velocity.y))
                    }
                },
        ) { measurables, constraints ->
            val cell = cellPx.roundToInt()
            val tileConstraints = Constraints.fixed(cell, cell)
            val placeables = measurables.map { it.layoutId as Int to it.measure(tileConstraints) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                val c = camera
                val originX = constraints.maxWidth / 2f - c.x - cell / 2f
                val originY = constraints.maxHeight / 2f - c.y - cell / 2f
                placeables.forEach { (index, placeable) ->
                    val row = index / cols
                    val col = index % cols
                    placeable.place(
                        IntOffset(
                            (originX + col * pitch + stagger(row)).roundToInt(),
                            (originY + row * pitch).roundToInt(),
                        ),
                    )
                }
            }
        }

        AnimatedContent(
            targetState = centeredItem,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "immersiveMeta",
            contentKey = { it?.id },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp, start = 24.dp, end = 24.dp),
        ) { item ->
            if (item == null) return@AnimatedContent
            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(PhoebeUi.panel.copy(alpha = 0.88f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    item.title,
                    color = PhoebeUi.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (item.subtitle.isNotBlank()) {
                    Text(
                        item.subtitle,
                        color = PhoebeUi.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImmersiveTile(
    item: CoverFlowItem,
    decodeDimension: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .phoebeClickable(onClick = onClick),
    ) {
        ArtworkImage(
            item.title,
            item.thumbUrl,
            Modifier.fillMaxSize(),
            radius = 10.dp,
            elevated = false,
            maxDecodeDimension = decodeDimension,
        )
    }
}

@Composable
fun AlbumImmersiveGrid(
    albums: List<Album>,
    onOpen: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(albums) { albums.map { it.toCoverFlowItem() } }
    val albumById = remember(albums) { albums.associateBy { it.id } }
    LibraryImmersiveGrid(
        items = items,
        onOpen = { item -> albumById[item.id]?.let(onOpen) },
        kind = CoverFlowKind.Albums,
        modifier = modifier,
        contentDescription = "Album immersive grid",
    )
}

@Composable
fun ArtistImmersiveGrid(
    artists: List<Artist>,
    onOpen: (Artist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(artists) { artists.map { it.toCoverFlowItem() } }
    val artistById = remember(artists) { artists.associateBy { it.id } }
    LibraryImmersiveGrid(
        items = items,
        onOpen = { item -> artistById[item.id]?.let(onOpen) },
        kind = CoverFlowKind.Artists,
        modifier = modifier,
        contentDescription = "Artist immersive grid",
    )
}

@Composable
fun TrackImmersiveGrid(
    tracks: List<Track>,
    onOpen: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(tracks) { tracks.map { it.toCoverFlowItem() } }
    val trackById = remember(tracks) { tracks.associateBy { it.id } }
    LibraryImmersiveGrid(
        items = items,
        onOpen = { item -> trackById[item.id]?.let(onOpen) },
        kind = CoverFlowKind.Songs,
        modifier = modifier,
        contentDescription = "Song immersive grid",
    )
}
