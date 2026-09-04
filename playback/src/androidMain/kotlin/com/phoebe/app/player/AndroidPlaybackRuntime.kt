package com.phoebe.app.player

import com.phoebe.app.data.ArtworkOriginHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object AndroidPlaybackRuntime {
    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val installMutex = Mutex()
    private val originMutex = Mutex()

    @Volatile
    var catalogBrowseSource: CatalogBrowseSource? = null
        private set

    @Volatile
    private var dependencies: PlaybackRuntimeDependencies? = null

    private var dependenciesFactory: (suspend () -> PlaybackRuntimeDependencies)? = null

    fun installFactory(factory: suspend () -> PlaybackRuntimeDependencies) {
        dependenciesFactory = factory
    }

    fun install(dependencies: PlaybackRuntimeDependencies) {
        this.dependencies = dependencies
        catalogBrowseSource = CatalogBrowseSourceImpl(
            database = dependencies.database,
            catalogRepository = dependencies.catalogRepository,
            sessionRepository = dependencies.sessionRepository,
        )
    }

    /** Warm the browse tree before Compose starts (Android Auto can connect first). */
    fun ensureInstalled() {
        if (catalogBrowseSource != null) return
        installScope.launch {
            ensureInstalledNow()
        }
    }

    suspend fun ensureInstalledNow(): CatalogBrowseSource {
        catalogBrowseSource?.let { return it }
        return installMutex.withLock {
            catalogBrowseSource?.let { return@withLock it }
            val factory = dependenciesFactory ?: error("Android playback runtime has not been installed.")
            install(factory())
            checkNotNull(catalogBrowseSource)
        }
    }

    /**
     * Bind a live media-server base for this process, or null if none could be probed.
     *
     * Android Auto browses and plays without ever starting Compose, so `AppState` — the only
     * thing that normally publishes [ArtworkOriginHolder] — has not run. Without this, browse
     * items carry relative `/library/...` URIs: no artwork, and "Source error" on play.
     */
    suspend fun ensureLiveOriginNow(): String? {
        ArtworkOriginHolder.liveOrigin?.let { return it }
        val deps = dependencies ?: run {
            ensureInstalledNow()
            dependencies
        } ?: return null
        // One race at a time; PlexConnectionResolver coalesces, but this also keeps a run of
        // browse callbacks from each paying the probe deadline.
        return originMutex.withLock {
            ArtworkOriginHolder.liveOrigin
                ?: runCatching { deps.ensureLivePlaybackOrigin() }.getOrNull()
        }
    }

    /** Fire-and-forget origin warm-up, with [onBound] invoked only when this call binds one. */
    fun warmLiveOrigin(onBound: () -> Unit = {}) {
        if (ArtworkOriginHolder.liveOrigin != null) return
        installScope.launch {
            if (ensureLiveOriginNow() != null) onBound()
        }
    }
}
