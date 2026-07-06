package com.phoebe.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phoebe.app.ui.DesktopKeyboardShortcutsEffect
import com.phoebe.app.feature.playback.GlobalMediaKeysEffect
import com.phoebe.app.ui.HomeScreenLayoutMode
import com.phoebe.app.ui.PhoebePaletteDark
import com.phoebe.app.ui.PhoebeDesignSystem
import com.phoebe.app.ui.PhoebeTheme
import com.phoebe.app.ui.PhoebeTintOption
import com.phoebe.app.ui.PhoebeRoot
import com.phoebe.app.ui.PlatformInteractionLocals
import com.phoebe.app.feature.playback.mediaPlaybackShortcuts
import com.phoebe.app.platform.isDesktopPlatform
import com.phoebe.app.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val AppearanceThemeFile = "appearance_theme"
private const val AppearanceTintFile = "appearance_tint"
private const val AppearanceDesignFile = "appearance_design"
private const val HomeScreenLayoutModeFile = "home_screen_layout_mode"

private object AppDependencyRuntime {
    private val mutex = Mutex()
    private var dependencies: AppDependencies? = null

    suspend fun getOrCreate(): AppDependencies =
        mutex.withLock {
            dependencies ?: withContext(Dispatchers.Default) {
                AppDependencies.create()
            }.also { dependencies = it }
        }
}

private sealed interface AppBootstrapState {
    data object Loading : AppBootstrapState
    data class Ready(
        val dependencies: AppDependencies,
        val closeDependenciesOnDispose: Boolean,
    ) : AppBootstrapState
    data class Failed(val message: String) : AppBootstrapState
}

@Composable
fun App(
    dependencies: AppDependencies? = null,
    onAppearanceChange: ((Boolean) -> Unit)? = null,
    onAppStateReady: ((AppState?) -> Unit)? = null,
    navigationPath: String? = null,
    onNavigationPathChange: ((path: String, replace: Boolean) -> Unit)? = null,
) {
    LaunchedEffect(Unit) {
        Telemetry.initialize()
    }

    val bootstrap by produceState<AppBootstrapState>(
        initialValue = dependencies?.let {
            AppBootstrapState.Ready(
                dependencies = it,
                closeDependenciesOnDispose = true,
            )
        } ?: AppBootstrapState.Loading,
        dependencies,
    ) {
        if (dependencies != null) {
            value = AppBootstrapState.Ready(
                dependencies = dependencies,
                closeDependenciesOnDispose = true,
            )
            return@produceState
        }
        value = try {
            AppBootstrapState.Ready(
                dependencies = AppDependencyRuntime.getOrCreate(),
                closeDependenciesOnDispose = isDesktopPlatform(),
            )
        } catch (error: Throwable) {
            AppBootstrapState.Failed(error.message ?: error.toString())
        }
    }

    val readyDependencies = when (val bootstrapState = bootstrap) {
        AppBootstrapState.Loading -> {
            AppBootstrapScreen(message = "Loading Phoebe…")
            return
        }
        is AppBootstrapState.Failed -> {
            AppBootstrapScreen(
                message = "Phoebe could not start",
                details = bootstrapState.message,
            )
            return
        }
        is AppBootstrapState.Ready -> bootstrapState.dependencies
    }
    val closeDependenciesOnDispose = (bootstrap as AppBootstrapState.Ready).closeDependenciesOnDispose
    val uiScope = rememberCoroutineScope()
    val desktopAppStateScope = remember(readyDependencies) {
        if (isDesktopPlatform()) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        } else {
            null
        }
    }
    val stateScope = desktopAppStateScope ?: uiScope
    val state = remember(readyDependencies, stateScope, uiScope) {
        AppState(
            dependencies = readyDependencies,
            scope = stateScope,
            playbackScope = uiScope,
            closeDependenciesOnDispose = closeDependenciesOnDispose,
        )
    }
    DisposableEffect(state, desktopAppStateScope) {
        onAppStateReady?.invoke(state)
        onDispose {
            state.dispose()
            desktopAppStateScope?.cancel()
            onAppStateReady?.invoke(null)
        }
    }
    val session by state.session.collectAsState()
    val mediaSources by state.mediaSources.collectAsState()

    // Keep navigation in sync when session / local folders appear after async restore.
    // Catalog refresh is handled by explicit user actions (manual refresh, post sign-in library
    // pick, folder add/remove, sign-out). Startup restores the cached catalog without kicking off
    // a full Plex rebuild.
    LaunchedEffect(session, mediaSources) {
        state.reconcileBrowseScreenIfNeeded()
    }

    var useLightAppearance by remember(readyDependencies) { mutableStateOf(false) }
    var appearanceDesignId by remember(readyDependencies) { mutableStateOf(PhoebeDesignSystem.Default.id) }
    var appearanceTintId by remember(readyDependencies) { mutableStateOf(PhoebeTintOption.Purple.id) }
    var homeScreenLayoutMode by remember(readyDependencies) { mutableStateOf<HomeScreenLayoutMode?>(null) }

    LaunchedEffect(readyDependencies) {
        installPlatformPlayback(readyDependencies)
        val stored = readyDependencies.platformStorage.readText(AppearanceThemeFile)?.trim()?.lowercase()
        useLightAppearance = stored == "light" || stored == "true"
        val storedDesign = PhoebeDesignSystem.fromId(
            readyDependencies.platformStorage.readText(AppearanceDesignFile),
        )
        appearanceDesignId = storedDesign.id
        appearanceTintId = readyDependencies.platformStorage.readText(AppearanceTintFile)
            ?.trim()
            ?.lowercase()
            ?.let { PhoebeTintOption.fromId(it, storedDesign).id }
            ?: PhoebeTintOption.defaultForDesign(storedDesign).id
        homeScreenLayoutMode = HomeScreenLayoutMode.fromStorage(
            readyDependencies.platformStorage.readText(HomeScreenLayoutModeFile)?.trim(),
        )
    }

    LaunchedEffect(state) {
        bindCarPlayPlayback(state)
        bindPlatformAppLifecycle(state)
    }

    LaunchedEffect(useLightAppearance) {
        onAppearanceChange?.invoke(useLightAppearance)
    }

    PhoebeTheme(useLightAppearance = useLightAppearance, tintId = appearanceTintId, designId = appearanceDesignId) {
        PlatformInteractionLocals {
        val resolvedHomeScreenLayoutMode = homeScreenLayoutMode ?: return@PlatformInteractionLocals

        GlobalMediaKeysEffect(
            playerFlow = state.player,
            onTogglePlayPause = { state.mediaKeyTogglePlayPause() },
            onPlay = { state.mediaKeyPlay() },
            onPause = { state.mediaKeyPause() },
            onNext = { state.next() },
            onPrevious = { state.previous() },
            onSeek = state::seekTo,
        )
        DesktopKeyboardShortcutsEffect(onTogglePlayPause = { state.mediaKeyTogglePlayPause() })
        Box(
            Modifier
                .fillMaxSize()
                .mediaPlaybackShortcuts(
                    onTogglePlayPause = { state.mediaKeyTogglePlayPause() },
                    onPlay = { state.mediaKeyPlay() },
                    onPause = { state.mediaKeyPause() },
                    onNext = { state.next() },
                    onPrevious = { state.previous() },
                ),
        ) {
            PhoebeRoot(
                state = state,
                useLightAppearance = useLightAppearance,
                onUseLightAppearanceChange = { value ->
                    useLightAppearance = value
                    uiScope.launch {
                        readyDependencies.platformStorage.writeText(
                            AppearanceThemeFile,
                            if (value) "light" else "dark",
                        )
                    }
                },
                appearanceDesignId = appearanceDesignId,
                onAppearanceDesignChange = { value ->
                    val design = PhoebeDesignSystem.fromId(value)
                    val tint = PhoebeTintOption.fromId(appearanceTintId, design)
                    appearanceDesignId = design.id
                    appearanceTintId = tint.id
                    uiScope.launch {
                        readyDependencies.platformStorage.writeText(
                            AppearanceDesignFile,
                            design.id,
                        )
                        readyDependencies.platformStorage.writeText(
                            AppearanceTintFile,
                            tint.id,
                        )
                    }
                },
                appearanceTintId = appearanceTintId,
                onAppearanceTintChange = { value ->
                    val design = PhoebeDesignSystem.fromId(appearanceDesignId)
                    val tintId = PhoebeTintOption.fromId(value, design).id
                    appearanceTintId = tintId
                    uiScope.launch {
                        readyDependencies.platformStorage.writeText(
                            AppearanceTintFile,
                            tintId,
                        )
                    }
                },
                homeScreenLayoutMode = resolvedHomeScreenLayoutMode,
                onHomeScreenLayoutModeChange = { value ->
                    homeScreenLayoutMode = value
                    uiScope.launch {
                        readyDependencies.platformStorage.writeText(
                            HomeScreenLayoutModeFile,
                            value.storageValue,
                        )
                    }
                },
                navigationPath = navigationPath,
                onNavigationPathChange = onNavigationPathChange,
            )
        }
        }
    }
}

@Composable
private fun AppBootstrapScreen(
    message: String,
    details: String? = null,
) {
    val palette = PhoebePaletteDark
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.canvasBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            if (details == null) {
                CircularProgressIndicator(color = palette.accent)
            }
            Text(message, color = palette.primaryText)
            if (details != null) {
                Text(details, color = palette.secondaryText)
            }
        }
    }
}
