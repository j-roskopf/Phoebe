package com.phoebe.app.player

import android.net.Uri
import com.phoebe.app.AndroidContextHolder
import com.phoebe.app.data.ArtworkOriginHolder
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.canTogglePlexLike
import com.phoebe.app.domain.isLikedSongsPlaylist
import com.phoebe.app.domain.supportsRemotePlaylists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        val context = AndroidContextHolder.applicationOrNull
        val fallbackArt = context?.resources?.getIdentifier("ic_aa_tab_radio", "drawable", context.packageName)
            ?.takeIf { it != 0 }
            ?.let { Uri.parse("android.resource://${context.packageName}/$it") }
            ?: Uri.parse("android.resource://android/${android.R.drawable.ic_menu_compass}")
        catalogBrowseSource = CatalogBrowseSourceImpl(
            database = dependencies.database,
            catalogRepository = dependencies.catalogRepository,
            sessionRepository = dependencies.sessionRepository,
            radioRepository = dependencies.radioRepository,
            radioFallbackArtworkUri = fallbackArt,
        )
        installScope.launch {
            runCatching { dependencies.radioRepository.restore() }
        }
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

    /**
     * Like controls must work when Android Auto starts [PlaybackService] without Compose.
     * Prefer live AppState bridges when present; otherwise use the headless catalog/session.
     */
    fun isLikeAvailable(track: Track): Boolean {
        AndroidPlaybackBridge.isLikeAvailable?.let { return it(track) }
        if (!track.canTogglePlexLike()) return false
        val session = dependencies?.sessionRepository?.session?.value
        return session.supportsRemotePlaylists()
    }

    fun isTrackLiked(track: Track): Boolean {
        AndroidPlaybackBridge.isTrackLiked?.let { bridgeLiked ->
            if (bridgeLiked(track)) return true
            // Bridge can be bound to AppState while Liked Songs members are still warming via
            // the headless catalog path — fall through so ensureLikedSongsLoaded can win.
        }
        return dependencies?.catalogRepository?.isTrackLiked(track.id) == true
    }

    /**
     * Load Liked Songs members (DB, then remote) so [isTrackLiked] is accurate after a cold start
     * before playlist detail or catalog warm-up has filled tracksByParent.
     */
    suspend fun ensureLikedSongsLoaded() {
        ensureInstalledNow()
        val deps = dependencies ?: return
        deps.catalogRepository.ensureLikedSongsTracksLoaded(deps.sessionRepository.session.value)
    }

    /** Emit Liked Songs membership signatures so Android Auto can refresh the heart when loaded. */
    fun likedSongsMembershipFlow(): Flow<Set<String>>? {
        val catalog = dependencies?.catalogRepository?.catalog ?: return null
        return catalog.map { snapshot ->
            val liked = snapshot.playlists.firstOrNull { it.isLikedSongsPlaylist() } ?: return@map emptySet()
            snapshot.tracksByParent[liked.id].orEmpty().map { it.id }.toSet()
        }.distinctUntilChanged()
    }

    suspend fun toggleLikedTrack(track: Track) {
        AndroidPlaybackBridge.onToggleLikedTrack?.let { bridge ->
            bridge(track)
            return
        }
        ensureInstalledNow()
        val deps = dependencies ?: return
        if (!isLikeAvailable(track)) return
        val session = deps.sessionRepository.session.value
        val liked = deps.catalogRepository.toggleLikedTrackLocally(session, track)
        installScope.launch {
            runCatching { deps.catalogRepository.syncLikedTrackChange(session, track, liked) }
        }
    }

    fun radioNowPlayingRepository() = dependencies?.radioNowPlayingRepository
}
