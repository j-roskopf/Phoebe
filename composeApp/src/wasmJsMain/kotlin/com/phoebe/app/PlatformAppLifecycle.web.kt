package com.phoebe.app

import com.phoebe.app.ui.RemoteArtworkCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

actual fun bindPlatformAppLifecycle(state: AppState) {
    lifecycleScope.launch {
        state.player
            .map { player -> player.currentTrack?.id }
            .distinctUntilChanged()
            .collect { trackId ->
                if (trackId != null) {
                    RemoteArtworkCache.trimForMemoryPressure(aggressive = false)
                }
            }
    }
}
