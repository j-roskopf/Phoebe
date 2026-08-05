package com.phoebe.app

import com.phoebe.app.platform.PhoebeAppLifecycle
import com.phoebe.app.platform.configurePlaybackMemoryPressure
import com.phoebe.app.ui.RemoteArtworkCache
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => !document.hidden")
private external fun isWebDocumentVisible(): Boolean

actual fun bindPlatformAppLifecycle(state: AppState) {
    document.addEventListener("visibilitychange", {
        val visible = isWebDocumentVisible()
        PhoebeAppLifecycle.setUiVisible(visible)
        if (visible) {
            RemoteArtworkCache.retryFailedLoadsNow()
            state.onPageVisibilityChanged(true)
        }
    })
    lifecycleScope.launch {
        state.shellPlayback
            .map { shell -> shell.isPlaying || shell.isBuffering }
            .distinctUntilChanged()
            .collect { active ->
                configurePlaybackMemoryPressure(active)
                RemoteArtworkCache.configurePlaybackMemoryMode(active)
            }
    }
    lifecycleScope.launch {
        state.shellPlayback
            .map { shell -> shell.currentTrack?.id }
            .distinctUntilChanged()
            .collect { trackId ->
                if (trackId != null) {
                    RemoteArtworkCache.trimForMemoryPressure(aggressive = false)
                }
            }
    }
}
