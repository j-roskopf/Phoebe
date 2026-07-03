package com.phoebe.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventPass
import com.phoebe.app.platform.isIosPlatform
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val PhoebeRouteBackStackSaver = Saver<NavBackStack<PhoebeRoute>, String>(
    save = { backStack -> encodePhoebeRouteBackStack(backStack) },
    restore = ::decodePhoebeRouteBackStack,
)

@Composable
fun MissingRouteFallback(
    title: String,
    message: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .widthIn(max = 380.dp)
                .padding(24.dp),
        ) {
            Text(
                text = title,
                color = PhoebeUi.primaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                color = PhoebeUi.secondaryText,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )
            FilledTonalButton(onClick = onBack) {
                Text("Back")
            }
        }
    }
}

@Composable
fun PhoebeNavDisplay(
    backStack: List<PhoebeRoute>,
    modifier: Modifier = Modifier,
    animateTransitions: Boolean = true,
    opaqueSceneBackgrounds: Boolean = false,
    onBack: () -> Unit,
    content: @Composable (PhoebeRoute) -> Unit,
) {
    if (isIosPlatform()) {
        SwipeBackNavDisplay(
            backStack = backStack,
            modifier = modifier,
            opaqueSceneBackgrounds = opaqueSceneBackgrounds,
            onBack = onBack,
            content = content
        )
    } else {
        val transitionSpec: AnimatedContentTransitionScope<Scene<PhoebeRoute>>.() -> ContentTransform =
            if (animateTransitions) defaultTransitionSpec() else noPhoebeRouteTransition()
        val popTransitionSpec: AnimatedContentTransitionScope<Scene<PhoebeRoute>>.() -> ContentTransform =
            if (animateTransitions) defaultPopTransitionSpec() else noPhoebeRouteTransition()
        PredictiveBackNavDisplay(
            backStack = backStack.ifEmpty { listOf(PhoebeRoute.SignIn) },
            modifier = modifier,
            transitionSpec = transitionSpec,
            popTransitionSpec = popTransitionSpec,
            opaqueSceneBackgrounds = opaqueSceneBackgrounds,
            onBack = onBack,
            content = content,
        )
    }
}

@Composable
private fun PredictiveBackNavDisplay(
    backStack: List<PhoebeRoute>,
    modifier: Modifier = Modifier,
    transitionSpec: AnimatedContentTransitionScope<Scene<PhoebeRoute>>.() -> ContentTransform,
    popTransitionSpec: AnimatedContentTransitionScope<Scene<PhoebeRoute>>.() -> ContentTransform,
    opaqueSceneBackgrounds: Boolean,
    onBack: () -> Unit,
    content: @Composable (PhoebeRoute) -> Unit,
) {
    var predictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(backStack) {
        predictiveBackActive = false
        predictiveBackProgress = 0f
    }

    Box(modifier.fillMaxSize()) {
        if (predictiveBackActive && backStack.size > 1) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val progress = predictiveBackProgress.coerceIn(0f, 1f)
                        alpha = (0.78f + progress * 0.22f).coerceIn(0f, 1f)
                        scaleX = 0.98f + progress * 0.02f
                        scaleY = 0.98f + progress * 0.02f
                    },
            ) {
                SwipeBackNavEntryContent(backStack[backStack.lastIndex - 1], opaqueSceneBackgrounds, content)
            }
        }

        NavDisplay(
            backStack = backStack,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (predictiveBackActive) {
                        val progress = predictiveBackProgress.coerceIn(0f, 1f)
                        val scale = 1f - progress * 0.08f
                        translationX = size.width * 0.08f * progress
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - progress * 0.05f
                    }
                },
            transitionSpec = transitionSpec,
            popTransitionSpec = popTransitionSpec,
            predictivePopTransitionSpec = { _ -> noPhoebeRouteContentTransform() },
            onBack = onBack,
            entryProvider = entryProvider {
                entry<PhoebeRoute.SignIn> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.ServerPicker> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.LibraryPicker> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.Browse>(clazzContentKey = { "browse" }) { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.Collections> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.CollectionItems> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.ArtistDetail> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.ArtistSlugDetail> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.AlbumDetail> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.ArtistAlbumSlugDetail> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.SongDetail> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.Lyrics> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.RecentlyAdded> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.PlayHistory> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.ArtistMixBuilder> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.AlbumMixBuilder> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.FavoritePlaylists> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.FavoriteArtists> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.FavoriteAlbums> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.PlaylistDetail> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.PlaylistSlugDetail> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                entry<PhoebeRoute.Player> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
            },
        )

        PlatformBackHandler(
            enabled = backStack.size > 1,
            onBack = {
                predictiveBackActive = false
                predictiveBackProgress = 0f
                onBack()
            },
            onBackProgress = { progress ->
                predictiveBackActive = true
                predictiveBackProgress = progress
            },
            onBackCancel = {
                predictiveBackActive = false
                predictiveBackProgress = 0f
            },
        )
    }
}

@Composable
private fun SwipeBackNavDisplay(
    backStack: List<PhoebeRoute>,
    modifier: Modifier = Modifier,
    opaqueSceneBackgrounds: Boolean = false,
    onBack: () -> Unit,
    content: @Composable (PhoebeRoute) -> Unit,
) {
    var swipePopInProgress by remember { mutableStateOf(false) }

    LaunchedEffect(backStack) {
        swipePopInProgress = false
    }

    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }

        val edgeDragModifier = Modifier.pointerInput(backStack) {
            if (backStack.size <= 1) return@pointerInput
            val edgeWidthPx = 32.dp.toPx()
            val touchSlopPx = 8.dp.toPx()

            coroutineScope {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val startX = down.position.x
                        if (startX <= edgeWidthPx) {
                            var dragOffsetValue = 0f
                            var isDragGestureStarted = false
                            val velocityTracker = VelocityTracker()
                            val dragPointerId = down.id
                            velocityTracker.addPosition(down.uptimeMillis, down.position)

                            var totalDeltaX = 0f
                            var totalDeltaY = 0f
                            var dragCompleted = false

                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == dragPointerId }
                                if (change == null) {
                                    break
                                }
                                if (!change.pressed) {
                                    dragCompleted = true
                                    break
                                }

                                val horizontalDelta = change.positionChange().x
                                val verticalDelta = change.positionChange().y

                                if (!isDragGestureStarted) {
                                    totalDeltaX += horizontalDelta
                                    totalDeltaY += verticalDelta

                                    val absX = kotlin.math.abs(totalDeltaX)
                                    val absY = kotlin.math.abs(totalDeltaY)

                                    if (absX > touchSlopPx || absY > touchSlopPx) {
                                        // Lock in if drag is to the right and primarily horizontal
                                        if (totalDeltaX > 0 && absX > absY) {
                                            isDragGestureStarted = true
                                            isDragging = true
                                            dragOffsetValue = totalDeltaX - touchSlopPx
                                            dragOffset = dragOffsetValue
                                            change.consume()
                                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        } else {
                                            // Diagonal/vertical drag first, cancel tracking this touch
                                            break
                                        }
                                    }
                                } else {
                                    dragOffsetValue = (dragOffsetValue + horizontalDelta).coerceAtLeast(0f)
                                    isDragging = true
                                    dragOffset = dragOffsetValue
                                    change.consume()
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                }
                            }

                            if (isDragGestureStarted) {
                                val velocity = velocityTracker.calculateVelocity().x
                                val minDragDistancePx = with(density) { 56.dp.toPx() }
                                val velocityThresholdPx = with(density) { 600.dp.toPx() }

                                if (dragCompleted) {
                                    if (dragOffsetValue > screenWidthPx / 3f || (dragOffsetValue > minDragDistancePx && velocity > velocityThresholdPx)) {
                                        launch {
                                            animate(
                                                initialValue = dragOffsetValue,
                                                targetValue = screenWidthPx
                                            ) { value, _ ->
                                                dragOffset = value
                                            }
                                            swipePopInProgress = true
                                            onBack()
                                            isDragging = false
                                            dragOffset = 0f
                                        }
                                    } else {
                                        launch {
                                            animate(
                                                initialValue = dragOffsetValue,
                                                targetValue = 0f
                                            ) { value, _ ->
                                                dragOffset = value
                                            }
                                            isDragging = false
                                            dragOffset = 0f
                                        }
                                    }
                                } else {
                                    launch {
                                        animate(
                                            initialValue = dragOffsetValue,
                                            targetValue = 0f
                                        ) { value, _ ->
                                            dragOffset = value
                                        }
                                        isDragging = false
                                        dragOffset = 0f
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Box(Modifier.fillMaxSize().then(edgeDragModifier)) {
            val progress = if (screenWidthPx > 0f) (dragOffset / screenWidthPx).coerceIn(0f, 1f) else 0f
            val parallaxOffset = (-screenWidthPx / 3f) * (1f - progress)

            // Previous screen underneath
            if (isDragging && backStack.size > 1) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = parallaxOffset
                        }
                ) {
                    val prevRoute = backStack[backStack.size - 2]
                    SwipeBackNavEntryContent(prevRoute, opaqueSceneBackgrounds, content)

                    // Dimming overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f * (1f - progress)))
                    )
                }
            }

            // Active top screen (sliding wrapper)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (isDragging) {
                            translationX = dragOffset
                        }
                    }
                    .drawBehind {
                        if (isDragging && dragOffset > 0f) {
                            val shadowWidth = 16.dp.toPx()
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.25f)),
                                    startX = -shadowWidth,
                                    endX = 0f
                                ),
                                topLeft = Offset(-shadowWidth, 0f),
                                size = Size(shadowWidth, size.height)
                            )
                        }
                    }
            ) {
                val animate = !swipePopInProgress
                val transitionSpec: AnimatedContentTransitionScope<Scene<PhoebeRoute>>.() -> ContentTransform =
                    if (animate) defaultTransitionSpec() else noPhoebeRouteTransition()
                val popTransitionSpec: AnimatedContentTransitionScope<Scene<PhoebeRoute>>.() -> ContentTransform =
                    if (animate) defaultPopTransitionSpec() else noPhoebeRouteTransition()
                val predictivePopTransitionSpec: AnimatedContentTransitionScope<Scene<PhoebeRoute>>.(Int) -> ContentTransform =
                    { _ -> noPhoebeRouteContentTransform() }

                NavDisplay(
                    backStack = backStack.ifEmpty { listOf(PhoebeRoute.SignIn) },
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = transitionSpec,
                    popTransitionSpec = popTransitionSpec,
                    predictivePopTransitionSpec = predictivePopTransitionSpec,
                    onBack = onBack,
                    entryProvider = entryProvider {
                        entry<PhoebeRoute.SignIn> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.ServerPicker> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.LibraryPicker> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.Browse>(clazzContentKey = { "browse" }) { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.Collections> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.CollectionItems> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.ArtistDetail> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.ArtistSlugDetail> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.AlbumDetail> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.ArtistAlbumSlugDetail> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.SongDetail> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.Lyrics> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.RecentlyAdded> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.PlayHistory> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.ArtistMixBuilder> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.AlbumMixBuilder> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.FavoritePlaylists> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.FavoriteArtists> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.FavoriteAlbums> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.PlaylistDetail> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.PlaylistSlugDetail> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                        entry<PhoebeRoute.Player> { route -> PhoebeNavEntryContent(route, opaqueSceneBackgrounds, content) }
                    },
                )
            }
        }
    }
}

private fun noPhoebeRouteTransition():
    AnimatedContentTransitionScope<Scene<PhoebeRoute>>.() -> ContentTransform = {
    noPhoebeRouteContentTransform()
}

private fun noPhoebeRouteContentTransform(): ContentTransform =
    ContentTransform(EnterTransition.None, ExitTransition.None)

@Composable
private fun PhoebeNavEntryContent(
    route: PhoebeRoute,
    opaqueSceneBackground: Boolean,
    content: @Composable (PhoebeRoute) -> Unit,
) {
    CompositionLocalProvider(
        LocalAnimatedVisibilityScope provides LocalNavAnimatedContentScope.current,
    ) {
        if (opaqueSceneBackground) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(PhoebeUi.shellTop),
            ) {
                content(route)
            }
        } else {
            content(route)
        }
    }
}

@Composable
private fun SwipeBackNavEntryContent(
    route: PhoebeRoute,
    opaqueSceneBackground: Boolean,
    content: @Composable (PhoebeRoute) -> Unit,
) {
    if (opaqueSceneBackground) {
        Box(
            Modifier
                .fillMaxSize()
                .background(PhoebeUi.shellTop),
        ) {
            content(route)
        }
    } else {
        content(route)
    }
}

@Composable
fun rememberPhoebeNavigator(initialRoute: PhoebeRoute): PhoebeNavigator =
    rememberPhoebeNavigator(listOf(initialRoute))

@Composable
fun rememberPhoebeNavigator(initialRoutes: List<PhoebeRoute>): PhoebeNavigator {
    val safeInitialRoutes = initialRoutes.ifEmpty { listOf(PhoebeRoute.SignIn) }
    val backStack = rememberSaveable(saver = PhoebeRouteBackStackSaver) {
        NavBackStack<PhoebeRoute>(safeInitialRoutes.first()).apply {
            addAll(safeInitialRoutes.drop(1))
        }
    }
    return remember(backStack) { PhoebeNavigator(backStack) }
}

class PhoebeNavigator(
    private val backStack: MutableList<PhoebeRoute>,
) {
    constructor(initialRoute: PhoebeRoute) : this(mutableStateListOf(initialRoute))
    constructor(backStack: SnapshotStateList<PhoebeRoute>) : this(backStack as MutableList<PhoebeRoute>)

    val routes: List<PhoebeRoute>
        get() = backStack.toList()

    val currentRoute: PhoebeRoute
        get() = routes.lastOrNull() ?: PhoebeRoute.SignIn

    fun open(route: PhoebeRoute) {
        if (currentRoute != route) {
            backStack.add(route)
        }
    }

    fun replaceRoot(route: PhoebeRoute) {
        if (backStack.size == 1 && backStack.firstOrNull() == route) return
        if (backStack.isEmpty()) {
            backStack.add(route)
            return
        }
        backStack[0] = route
        while (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    fun replaceAll(routes: List<PhoebeRoute>) {
        val safeRoutes = routes.ifEmpty { listOf(PhoebeRoute.SignIn) }
        if (this.routes == safeRoutes) return
        backStack.clear()
        backStack.addAll(safeRoutes)
    }

    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun openPlayer() {
        open(PhoebeRoute.Player)
    }

    fun handle(request: AppNavigationRequest) {
        when (request) {
            AppNavigationRequest.SignIn -> replaceRoot(PhoebeRoute.SignIn)
            AppNavigationRequest.ServerPicker -> openSetupRoute(PhoebeRoute.ServerPicker)
            AppNavigationRequest.LibraryPicker -> openSetupRoute(PhoebeRoute.LibraryPicker)
            AppNavigationRequest.Home -> openHomeFromAppRequest()
            AppNavigationRequest.Radio -> openBrowse(BrowseSection.Radio)
            AppNavigationRequest.Player -> openPlayer()
            is AppNavigationRequest.PlaylistDetail -> {
                replaceRoot(PhoebeRoute.Browse())
                open(PhoebeRoute.PlaylistDetail(request.playlistId))
            }
        }
    }

    fun openBrowse(section: BrowseSection) {
        replaceRoot(PhoebeRoute.Browse(section))
    }

    private fun openHomeFromAppRequest() {
        // Startup/session restore can emit Home after the user has already entered browse.
        if (routes.firstOrNull() is PhoebeRoute.Browse) return
        replaceRoot(PhoebeRoute.Browse())
    }

    private fun openSetupRoute(route: PhoebeRoute) {
        if (routes.firstOrNull() != PhoebeRoute.SignIn) {
            replaceRoot(PhoebeRoute.SignIn)
        }
        when (route) {
            PhoebeRoute.ServerPicker -> {
                while (backStack.size > 1) {
                    backStack.removeAt(backStack.lastIndex)
                }
                open(route)
            }
            PhoebeRoute.LibraryPicker -> {
                openSetupRoute(PhoebeRoute.ServerPicker)
                open(route)
            }
            else -> open(route)
        }
    }
}

fun AppNavigationRequest.toPhoebeRoute(): PhoebeRoute = when (this) {
    AppNavigationRequest.SignIn -> PhoebeRoute.SignIn
    AppNavigationRequest.ServerPicker -> PhoebeRoute.ServerPicker
    AppNavigationRequest.LibraryPicker -> PhoebeRoute.LibraryPicker
    AppNavigationRequest.Home -> PhoebeRoute.Browse()
    AppNavigationRequest.Radio -> PhoebeRoute.Browse(BrowseSection.Radio)
    AppNavigationRequest.Player -> PhoebeRoute.Player
    is AppNavigationRequest.PlaylistDetail -> PhoebeRoute.PlaylistDetail(playlistId)
}

fun Collection<PhoebeRoute>.collectionMixSeed(): CollectionMixSeed? {
    val route = filterIsInstance<PhoebeRoute.CollectionItems>().lastOrNull() ?: return null
    return CollectionMixSeed(route.entry.facet, route.value)
}
