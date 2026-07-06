package com.phoebe.app.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.phoebe.app.data.db.DatabaseWriteGate
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.db.DownloadRow
import com.phoebe.app.db.TrackRow
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogCollectionValue
import com.phoebe.app.domain.CatalogCollectionValueLoad
import com.phoebe.app.domain.CatalogCollectionTag
import com.phoebe.app.domain.CatalogPageInfo
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.CatalogSyncPhase
import com.phoebe.app.domain.CatalogSyncState
import com.phoebe.app.domain.displayName
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.CollectionFacet
import com.phoebe.app.domain.CollectionTarget
import com.phoebe.app.domain.DownloadItem
import com.phoebe.app.domain.DownloadState
import com.phoebe.app.domain.DownloadStatusEvent
import com.phoebe.app.domain.JellyfinLibraryPageKind
import com.phoebe.app.domain.JellyfinSyncMode
import com.phoebe.app.domain.LocalMetadataOverride
import com.phoebe.app.domain.MetadataOverrideSyncStatus
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.LIKED_SONGS_PLAYLIST_BARE_ID
import com.phoebe.app.domain.LIKED_SONGS_PLAYLIST_TITLE
import com.phoebe.app.domain.likedSongsPlaylistId
import com.phoebe.app.domain.LOCAL_PLAYLIST_ID_PREFIX
import com.phoebe.app.domain.PENDING_LIKED_SONGS_PLAYLIST_ID
import com.phoebe.app.domain.PlexRadioStation
import com.phoebe.app.domain.PlexRadioStationCategory
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.SmartPlaylist
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.TrackFilterContext
import com.phoebe.app.domain.TrackMetadataUpdate
import com.phoebe.app.domain.canAddToLocalPlaylist
import com.phoebe.app.domain.canAddToPlexPlaylist
import com.phoebe.app.domain.canTogglePlexLike
import com.phoebe.app.domain.catalogPrefix
import com.phoebe.app.domain.belongsToProvider
import com.phoebe.app.domain.hasPlayableSource
import com.phoebe.app.domain.isLocalMediaPlayback
import com.phoebe.app.domain.isLocalPlaylist
import com.phoebe.app.domain.playlistEntryKey
import com.phoebe.app.domain.supportsTrackRemoval
import com.phoebe.app.domain.isLikedSongsPlaylist
import com.phoebe.app.domain.isEmbyFamily
import com.phoebe.app.domain.isJellyfin
import com.phoebe.app.domain.isJellyfinLibraryTrack
import com.phoebe.app.domain.isNavidrome
import com.phoebe.app.domain.isPlex
import com.phoebe.app.domain.isPlexLibraryTrack
import com.phoebe.app.domain.isRemoteLibraryTrack
import com.phoebe.app.domain.filterWith
import com.phoebe.app.domain.mergeDownloadCopiesById
import com.phoebe.app.domain.remoteProviderPrefix
import com.phoebe.app.domain.sortedWith
import com.phoebe.app.domain.serverAuthToken
import com.phoebe.app.domain.supportsPlexPlaylists
import com.phoebe.app.domain.supportsPlexRatings
import com.phoebe.app.domain.supportsCollectionEntry
import com.phoebe.app.domain.supportsRemotePlaylists
import com.phoebe.app.domain.supportsRemoteRatings
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.data.splitCollectionTagLabels
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.catalogTrackIndexParallelism
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.platform.downloadParallelism
import com.phoebe.app.platform.platformStreamHttpDownloadToStorage
import com.phoebe.app.sources.CatalogMerge
import com.phoebe.app.sources.LocalFolderMusicSourcePlugin
import com.phoebe.app.sources.PlexCatalogBuilder
import com.phoebe.app.sources.SourceBuildContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlin.random.Random

private const val DownloadChunkSize = 64 * 1024
private const val DownloadProgressByteInterval = 2 * 1024 * 1024L
private const val DownloadProgressStep = 0.05f
private const val DownloadReadIdleTimeoutMs = 60_000L
private const val DownloadArtworkTimeoutMs = 15_000L
private const val DownloadUnknownTotalProgressBytes = 16 * 1024 * 1024L
private const val DownloadCompletionPersistBatchSize = 32
private const val DownloadCatalogPublishBatchSize = 128
private const val DownloadImmediateSnapshotLimit = 256
private const val DownloadFailureSampleLimit = 40
private const val DownloadFailureLogLimit = 40
private const val PlexPlayHistoryWarmPageSize = 100

data class DownloadBatchResult(
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val failureReasons: List<DownloadFailureReason> = emptyList(),
    val failedSamples: List<DownloadFailureSample> = emptyList(),
) {
    val skipped: Int
        get() = (total - completed - failed).coerceAtLeast(0)
}

data class DownloadFailureReason(
    val reason: String,
    val count: Int,
)

data class DownloadFailureSample(
    val trackId: String,
    val title: String,
    val artist: String,
    val error: String,
    val sourceUrl: String,
    val targetPath: String,
)

data class DownloadManagerSummary(
    val activeCount: Int = 0,
    val completeCount: Int = 0,
    val failedCount: Int = 0,
    val totalCount: Int = 0,
    val completedBytes: Long = 0L,
    val totalBytes: Long? = null,
)

private data class DownloadDeletePlan(
    val deletedIds: Set<String>,
    val localUris: List<String>,
    val targetPaths: List<String>,
    val artworkUris: List<String>,
    val clearedRows: List<Track>,
)

private data class DownloadFailureDiagnostics(
    val reasons: List<DownloadFailureReason> = emptyList(),
    val samples: List<DownloadFailureSample> = emptyList(),
) {
    val hasFailures: Boolean
        get() = reasons.isNotEmpty() || samples.isNotEmpty()
}

data class RatingSyncResult(
    val savedLocally: Boolean,
    val plexAttempted: Boolean,
    val plexSynced: Boolean,
)

data class FavoriteSyncResult(
    val favorite: Boolean? = null,
    val plexAttempted: Boolean = false,
    val plexSynced: Boolean = false,
)

@Serializable
data class FavoritePlaylistsExport(
    val version: Int = 1,
    val playlists: List<FavoritePlaylistExportEntry> = emptyList(),
)

@Serializable
data class FavoritePlaylistExportEntry(
    val id: String,
    val title: String,
    val key: String? = null,
)

@SingleIn(AppScope::class)
@Inject
class CatalogRepository(
    private val plexClient: PlexClient,
    private val jellyfinClient: JellyfinClient,
    private val embyClient: EmbyClient,
    private val subsonicClient: SubsonicClient,
    private val providerRegistry: MusicProviderRegistry,
    private val database: PhoebeDatabase,
    private val storage: PlatformStorage,
    private val httpClient: HttpClient,
    private val mediaSourcesRepository: MediaSourcesRepository,
    private val userArtifactsRepository: UserArtifactsRepository,
    private val databaseWriteGate: DatabaseWriteGate,
) {
    private val json = PhoebeDataJson
    private val mutableCatalog = MutableStateFlow(CatalogSnapshot())
    private val mutableDownloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    private val downloadItemsByTrackId = linkedMapOf<String, DownloadItem>()
    private val mutableDownloadEvents = MutableSharedFlow<DownloadStatusEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val refreshMutex = Mutex()
    private val catalogMergeMutex = Mutex()
    private val downloadMutex = Mutex()
    private val downloadStatusMutex = Mutex()
    private val downloadCancellationMutex = Mutex()
    private val canceledDownloadTrackIds = mutableSetOf<String>()
    private val pendingPlaylistPrependedTrackIds = mutableMapOf<String, List<String>>()
    private val pendingPlaylistFavoriteOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingCatalogDbWrites = mutableSetOf<Job>()
    private val pendingCatalogDbWritesMutex = Mutex()
    private val smartPlaylistLastPlayedByTrack = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val smartPlaylistPlayCountsByTrack = MutableStateFlow<Map<String, Int>>(emptyMap())
    private var plexTrackIndexPageTimeoutMs = DefaultPlexTrackIndexPageTimeoutMs

    init {
        observeSmartPlaylistPlayHistory()
    }

    suspend fun awaitDatabaseIdle() {
        while (true) {
            awaitPendingCatalogDbWrites()
            databaseWriteGate.withWrite { }
            val queuedDuringGate = pendingCatalogDbWritesMutex.withLock { pendingCatalogDbWrites.isNotEmpty() }
            if (!queuedDuringGate) return
        }
    }

    fun close() {
        persistenceScope.cancel()
    }

    private suspend fun awaitPendingCatalogDbWrites(excluding: Job? = null) {
        while (true) {
            val pending = pendingCatalogDbWritesMutex.withLock {
                pendingCatalogDbWrites.filter { it !== excluding }
            }
            if (pending.isEmpty()) return
            pending.joinAll()
        }
    }

    private fun observeSmartPlaylistPlayHistory() {
        persistenceScope.launch {
            database.playHistoryQueries
                .selectLastPlayedByTrack()
                .asFlow()
                .mapToList(Dispatchers.Default)
                .catch { error ->
                    if (error is CancellationException) throw error
                    PhoebeLog.d("CatalogRepository") {
                        "smart playlist last-played observer stopped: ${error.message}"
                    }
                }
                .collect { rows ->
                    smartPlaylistLastPlayedByTrack.value = buildMap(rows.size) {
                        rows.forEach { row -> row.lastPlayed?.let { put(row.track_id, it) } }
                    }
                    refreshSmartPlaylistSnapshotFromPlayHistory()
                }
        }
        persistenceScope.launch {
            database.playHistoryQueries
                .selectPlayCountsByTrack()
                .asFlow()
                .mapToList(Dispatchers.Default)
                .catch { error ->
                    if (error is CancellationException) throw error
                    PhoebeLog.d("CatalogRepository") {
                        "smart playlist play-count observer stopped: ${error.message}"
                    }
                }
                .collect { rows ->
                    smartPlaylistPlayCountsByTrack.value = buildMap(rows.size) {
                        rows.forEach { row -> put(row.track_id, (row.playCount ?: 0L).toInt()) }
                    }
                    refreshSmartPlaylistSnapshotFromPlayHistory()
                }
        }
    }

    private fun refreshSmartPlaylistSnapshotFromPlayHistory() {
        val snapshot = mutableCatalog.value
        if (snapshot.playlists.none { it.isSmartPlaylist() }) return
        mutableCatalog.value = withSmartPlaylists(snapshot)
    }

    private suspend fun enqueueCatalogDbWrite(block: suspend () -> Unit) {
        var trackedJob: Job? = null
        val job = persistenceScope.launch(start = CoroutineStart.LAZY) {
            try {
                runCatching {
                    databaseWriteGate.withWrite { block() }
                }.onFailure { error ->
                    PhoebeLog.d("CatalogRepository") {
                        "catalog db write failed: ${error.message}"
                    }
                }
            } finally {
                val completed = trackedJob
                if (completed != null) {
                    withContext(NonCancellable) {
                        pendingCatalogDbWritesMutex.withLock {
                            pendingCatalogDbWrites.remove(completed)
                        }
                    }
                }
            }
        }
        trackedJob = job
        pendingCatalogDbWritesMutex.withLock {
            pendingCatalogDbWrites += job
        }
        job.start()
    }

    private suspend fun runCatalogDbWrite(block: suspend () -> Unit) {
        awaitPendingCatalogDbWrites(excluding = currentCoroutineContext()[Job])
        databaseWriteGate.withWrite { block() }
    }

    private suspend fun awaitCatalogDbWrites() {
        awaitDatabaseIdle()
    }

    private fun startDeferredLocalCatalog(ctx: SourceBuildContext): Deferred<CatalogSnapshot>? {
        if (ctx.localFolders.none { it.enabled }) return null
        return persistenceScope.async {
            LocalFolderMusicSourcePlugin.buildCatalog(ctx)
        }
    }

    /** Merges a background local-folder scan into the current catalog without blocking remote sync. */
    private suspend fun mergeDeferredLocalCatalog(deferred: Deferred<CatalogSnapshot>?) {
        val localRaw = deferred?.await() ?: return
        if (localRaw.artists.isEmpty() && localRaw.albums.isEmpty() && localRaw.tracksByParent.isEmpty()) {
            return
        }
        catalogMergeMutex.withLock {
            val merged = CatalogMerge.merge(
                mutableCatalog.value.withoutLocalFolderCatalog(),
                localRaw,
            ).copy(downloads = mutableCatalog.value.downloads)
            mutableCatalog.value = merged
        }
        enqueueCatalogDbWrite { persistCatalogShell(mutableCatalog.value) }
    }

    private fun prefixRemoteTracks(
        remotePrefix: String,
        tracks: List<Track>,
        albums: List<Album>,
    ): List<Track> {
        if (tracks.isEmpty()) return emptyList()
        val grouped = tracks
            .groupBy { track ->
                track.parentAlbumId?.takeIf { id -> id.isNotBlank() }
                    ?: jellyfinAlbumIdByTitle(albums, track)
            }
            .filterKeys { it.isNotBlank() }
        if (grouped.isEmpty()) return emptyList()
        return CatalogMerge.withPrefix(remotePrefix, CatalogSnapshot(tracksByParent = grouped))
            .tracksByParent.values.flatten()
    }

    private suspend fun finalizeRemoteCatalogPersistence(
        snapshot: CatalogSnapshot,
        incremental: Boolean,
        partialPaged: Boolean = snapshot.remotePageInfo.hasUnloadedRemotePages(),
    ) {
        if (incremental) {
            enqueueCatalogDbWrite { persistCatalogShell(snapshot) }
            awaitCatalogDbWrites()
            runCatalogDbWrite { reconcileCatalogPersistence(snapshot, partialPaged = partialPaged) }
        } else {
            persistAsync(snapshot)
        }
    }

    private fun scheduleDeferredLocalCatalogMerge(deferred: Deferred<CatalogSnapshot>?) {
        if (deferred == null) return
        persistenceScope.launch {
            runCatching { mergeDeferredLocalCatalog(deferred) }
                .onFailure { error ->
                    PhoebeLog.d("CatalogRepository") {
                        "deferred local catalog merge failed: ${error.message}"
                    }
                }
        }
    }
    val catalog: StateFlow<CatalogSnapshot> = mutableCatalog
    val downloads: StateFlow<List<DownloadItem>> = mutableDownloads
    val downloadEvents: SharedFlow<DownloadStatusEvent> = mutableDownloadEvents.asSharedFlow()

    private fun replaceDownloadSnapshot(
        items: List<DownloadItem>,
        syncCatalog: Boolean = true,
    ) {
        downloadItemsByTrackId.clear()
        items.forEach { item -> downloadItemsByTrackId[item.trackId] = item }
        mutableDownloads.value = items
        if (syncCatalog) {
            mutableCatalog.value = withSmartPlaylists(mutableCatalog.value.copy(downloads = items))
        }
    }

    private fun currentDownloadItems(): List<DownloadItem> =
        downloadItemsByTrackId.values.toList()

    private val mutableCatalogRefreshing = MutableStateFlow(false)
    val catalogRefreshing: StateFlow<Boolean> = mutableCatalogRefreshing
    private var catalogRefreshingDepth = 0

    private fun pushCatalogRefreshing() {
        catalogRefreshingDepth++
        mutableCatalogRefreshing.value = true
    }

    private fun popCatalogRefreshing() {
        catalogRefreshingDepth = (catalogRefreshingDepth - 1).coerceAtLeast(0)
        if (catalogRefreshingDepth == 0) {
            mutableCatalogRefreshing.value = false
        }
    }

    private suspend inline fun <T> withCatalogRefreshing(crossinline block: suspend () -> T): T {
        pushCatalogRefreshing()
        return try {
            block()
        } finally {
            popCatalogRefreshing()
        }
    }

    private val mutableCatalogSyncState = MutableStateFlow(CatalogSyncState())
    val catalogSyncState: StateFlow<CatalogSyncState> = mutableCatalogSyncState
    private val catalogSyncUiThrottle = CatalogSyncUiThrottle()
    private val mutableTracksLoading = MutableStateFlow<Set<String>>(emptySet())
    val tracksLoading: StateFlow<Set<String>> = mutableTracksLoading
    private val localFileMetadataCache = LocalFileMetadataCache(database)
    private val syncedTrackIdsDuringRefresh = mutableSetOf<String>()
    private val fetchedAlbumDetailsIds = mutableSetOf<String>()

    fun clearInMemoryCatalog() {
        mutableCatalog.value = CatalogSnapshot()
        replaceDownloadSnapshot(emptyList())
        mutableCatalogRefreshing.value = false
        catalogRefreshingDepth = 0
        publishCatalogSyncState(CatalogSyncState(), force = true)
        mutableTracksLoading.value = emptySet()
        syncedTrackIdsDuringRefresh.clear()
        fetchedAlbumDetailsIds.clear()
        pendingPlaylistFavoriteOverrides.value = emptyMap()
    }

    /** Clears in-flight sync UI when a refresh job is cancelled (e.g. sign-out or superseded refresh). */
    fun clearActiveSyncProgress() {
        if (mutableCatalogSyncState.value.isActive) {
            publishCatalogSyncState(CatalogSyncState(), force = true)
        }
        if (catalogRefreshingDepth > 0) {
            catalogRefreshingDepth = 0
            mutableCatalogRefreshing.value = false
        }
    }

    /**
     * Cached canonical machine identifier per session token. Plex's `server://X/…` URIs
     * require the value reported by the server's own `/identity` endpoint, which can differ
     * from the `clientIdentifier` exposed by plex.tv resources (especially for relay servers).
     * We resolve it lazily on first playlist mutation and reuse it for the lifetime of the
     * token.
     */
    private val machineIdentifierMutex = Mutex()
    private var cachedMachineIdentifier: Pair<String, String>? = null

    private suspend fun resolveMachineIdentifier(server: PlexServer, token: String): String =
        machineIdentifierMutex.withLock {
            val cached = cachedMachineIdentifier
            if (cached != null && cached.first == token) return cached.second
            val resolved = runCatching { plexClient.machineIdentifier(server, token) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: server.id
            cachedMachineIdentifier = token to resolved
            resolved
        }

    suspend fun restoreCachedCatalog() {
        val trace = CatalogSyncTrace("restore")
        trace.mark("restoreCachedCatalog start", CatalogSyncStepKind.Other)
        mutableCatalogSyncState.value = CatalogSyncState(
            phase = CatalogSyncPhase.RestoringCache,
            message = "Restoring library from disk…",
            detail = "Reading cached artists, albums, and playlists",
            blocking = false,
        )
        try {
            val cachedShell = trace.disk("readCatalogShellFromDatabase") {
                withContext(Dispatchers.Default) {
                    readCatalogShellFromDatabase().withoutInactiveLocalFolderCatalog(activeLocalFolderIds())
                }
            }
            trace.disk("resetStaleDownloads") {
                withContext(Dispatchers.Default) {
                    runCatching {
                        runCatalogDbWrite { database.downloadsQueries.resetStaleDownloading() }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        PhoebeLog.d("CatalogRepository") { "stale download reset failed: ${error.message}" }
                    }
                }
            }
            val downloads = trace.disk("readDownloadsFromDatabase") {
                withContext(Dispatchers.Default) {
                    database.downloadsQueries.selectAll().awaitAsList().map { row ->
                        row.toDownloadItem()
                    }
                }
            }
            if (cachedShell.hasRestorableCatalogContent() || downloads.isNotEmpty()) {
                val restoredShell = trace.memory("applyCachedShellToMemory") {
                    removeMissingLocalArtworkReferences(cachedShell.copy(downloads = downloads))
                }
                replaceDownloadSnapshot(restoredShell.downloads, syncCatalog = false)
                if (userArtifactsRepository.smartPlaylists.value.any { it.enabled }) {
                    val restoredTracks = trace.disk("hydrateCachedTracksFromDatabase") {
                        runCatching { readTracksFromDatabase() }
                            .onFailure { error ->
                                PhoebeLog.d("CatalogRepository") {
                                    "cached track hydration failed: ${error.message}"
                                }
                            }
                            .getOrNull()
                    }
                    val restoredDownloads = restoredTracks?.downloads?.ifEmpty { restoredShell.downloads } ?: restoredShell.downloads
                    replaceDownloadSnapshot(restoredDownloads, syncCatalog = false)
                    mutableCatalog.value = withSmartPlaylists(
                        restoredShell.copy(
                            tracksByParent = restoredTracks?.tracksByParent.orEmpty(),
                            popularTracksByLibrary = restoredTracks?.popularTracksByLibrary.orEmpty(),
                            downloads = restoredDownloads,
                        ),
                    )
                } else {
                    mutableCatalog.value = restoredShell
                    trace.disk("hydrateCachedTracksFromDatabase") {
                        runCatching { hydrateCachedTracksAfterShellRestore(restoredShell) }
                            .onFailure { error ->
                                PhoebeLog.d("CatalogRepository") {
                                    "cached track hydration failed: ${error.message}"
                                }
                            }
                    }
                }
                trace.mark(
                    "restoreCachedCatalog complete",
                    CatalogSyncStepKind.Other,
                    "${mutableCatalog.value.albums.size} albums, " +
                        "${mutableCatalog.value.playlists.size} playlists, " +
                        "${mutableCatalog.value.tracksByParent.values.sumOf { it.size }} tracks",
                )
                return
            }
            val legacy = storage.readText(LegacyCatalogFile) ?: run {
                trace.mark("restoreCachedCatalog: no cached catalog", CatalogSyncStepKind.Other)
                return
            }
            val parsed = runCatching {
                json.decodeFromString<CatalogSnapshot>(legacy)
            }.getOrNull() ?: run {
                trace.mark("restoreCachedCatalog: legacy file unreadable", CatalogSyncStepKind.Other)
                return
            }
            val repaired = removeMissingLocalArtworkReferences(
                parsed.withoutInactiveLocalFolderCatalog(activeLocalFolderIds()),
            )
            trace.disk("migrateLegacyCatalogToDatabase") {
                withContext(Dispatchers.Default) { runCatalogDbWrite { persist(repaired) } }
            }
            mutableCatalog.value = withSmartPlaylists(repaired)
            storage.delete(LegacyCatalogFile)
            trace.mark(
                "restoreCachedCatalog from legacy file",
                CatalogSyncStepKind.Other,
                "${parsed.albums.size} albums",
            )
        } finally {
            mutableCatalogSyncState.value = CatalogSyncState()
        }
    }

    suspend fun refreshAggregated(session: PlexSession?, backgroundIfCached: Boolean = false) {
        if (session == null && mediaSourcesRepository.state.value.localFolders.any { it.enabled }) {
            refreshLocalFoldersOnly(session)
            return
        }
        if (session.isEmbyFamily()) {
            refreshJellyfinAggregated(session)
            return
        }
        if (session?.isNavidrome() == true) {
            refreshNavidromeAggregated(session)
            return
        }
        if (session != null && !session.isPlex()) {
            refreshAdapterAggregated(session)
            return
        }
        PhoebeLog.d("CatalogRepository") {
            "refreshAggregated start → plex=${session?.selectedServer?.name ?: "none"}, " +
                "localFolders=${mediaSourcesRepository.state.value.localFolders.count { it.enabled }}"
        }
        val syncTrace = CatalogSyncTrace(session?.selectedServer?.name ?: "no-server")
        syncTrace.mark("refreshAggregated start", CatalogSyncStepKind.Other)
        var stalePlaylists: List<Playlist> = emptyList()
        var persistSnapshot = true
        var foregroundRefreshing = false
        syncedTrackIdsDuringRefresh.clear()
        val snapshot = refreshMutex.withLock {
            var previous = mutableCatalog.value
            val preserveTracksFrom = tracksToPreserveDuringRefresh(previous)
            if (previous.tracksByParent.isEmpty() && preserveTracksFrom.isNotEmpty()) {
                previous = previous.copy(tracksByParent = preserveTracksFrom)
                mutableCatalog.value = previous
            }
            val backgroundRefresh = backgroundIfCached && previous.isNotEmpty()
            if (!backgroundRefresh) {
                pushCatalogRefreshing()
                foregroundRefreshing = true
                mutableCatalogSyncState.value = CatalogSyncState(
                    phase = CatalogSyncPhase.LoadingLibrary,
                    message = if (previous.isNotEmpty()) "Refreshing library…" else "Loading your library…",
                    blocking = !previous.isNotEmpty(),
                )
            }
            try {
                val ctx = SourceBuildContext(
                    session = session,
                    plexClient = plexClient,
                    httpClient = httpClient,
                    localFolders = mediaSourcesRepository.state.value.localFolders,
                    localFileMetadataCache = localFileMetadataCache,
                )

                val server = session?.selectedServer
                val library = session?.selectedLibrary
                val token = session.serverAuthToken()
                val plexBuilder = PlexCatalogBuilder(plexClient, httpClient)
                val localDeferred = if (ctx.localFolders.none { it.enabled }) {
                    null
                } else {
                    coroutineScope {
                        async { LocalFolderMusicSourcePlugin.buildCatalog(ctx) }
                    }
                }

                val plexRawMetadata = if (server == null || library == null || token == null) {
                    CatalogSnapshot()
                } else {
                    syncTrace.network("plex.prepareForCatalogRequests") {
                        runCatching { plexClient.prepareForCatalogRequests(server, token) }
                            .onFailure { error ->
                                if (error is CancellationException) throw error
                            }
                    }
                    val metadata = syncTrace.network("plex.buildMetadataCatalog") {
                        plexBuilder.buildMetadataCatalog(
                            server = server,
                            library = library,
                            token = token,
                            onProgress = { message, detail ->
                                if (!backgroundRefresh) {
                                    publishCatalogSyncState(
                                        mutableCatalogSyncState.value.copy(
                                            phase = CatalogSyncPhase.LoadingLibrary,
                                            message = message,
                                            detail = detail,
                                            blocking = false,
                                        ),
                                    )
                                }
                            },
                            trace = syncTrace,
                        )
                    }
                    PhoebeLog.d("PlexCollections") {
                        "refresh metadata skipped collection discovery"
                    }
                    if (metadata.isNotEmpty()) {
                        syncTrace.memory("publishPlexMetadataPartial") {
                            publishPlexMetadataPartial(
                                raw = metadata,
                                previous = previous,
                                session = session,
                                message = "Saving library metadata…",
                                updateSyncState = !backgroundRefresh,
                            )
                        }
                        yield()
                    }
                    metadata
                }

                val metadataMerged = syncTrace.memory("mergePlexMetadata") {
                    CatalogMerge.merge(
                        CatalogSnapshot(),
                        CatalogMerge.withPrefix("plex", plexRawMetadata),
                        CatalogSnapshot(),
                    )
                }
                val reconciled = syncTrace.memory("reconcileMergedSnapshot") {
                    reconcileMergedSnapshot(
                        merged = metadataMerged,
                        previous = previous,
                        session = session,
                    )
                }
                val reconciledSnapshot = reconciled.snapshot.withPlaylistUserStateFrom(mutableCatalog.value)
                stalePlaylists = reconciled.stalePlaylists
                mutableCatalog.value = reconciledSnapshot
                syncTrace.disk("persistCatalogShell") {
                    runCatalogDbWrite { persistCatalogShell(reconciledSnapshot) }
                }
                if (!backgroundRefresh) {
                    popCatalogRefreshing()
                    foregroundRefreshing = false
                }
                val artistCount = reconciledSnapshot.artists.size
                val albumCount = metadataMerged.albums.size
                val playlistCount = reconciledSnapshot.playlists.size
                mutableCatalogSyncState.value = CatalogSyncState(
                    phase = CatalogSyncPhase.LoadingSongs,
                    message = if (backgroundRefresh) "Updating library…" else "Indexing songs…",
                    detail = "$artistCount artists, $albumCount albums, $playlistCount playlists",
                    loadedAlbums = albumCount,
                    loadedTracks = reconciledSnapshot.tracksByParent.values.sumOf { it.size },
                    totalPlaylists = playlistCount,
                    blocking = false,
                )
                warmLikelyClickedContent(session, reconciledSnapshot)
                yield()

                if (server != null && library != null && token != null) {
                    val indexed = runCatching {
                        syncTrace.network("plex.indexTrackPages") {
                            indexPlexTrackPages(
                                server = server,
                                library = library,
                                token = token,
                                preserveTracksFrom = preserveTracksFrom,
                                trace = syncTrace,
                            )
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        PhoebeLog.d("CatalogRepository") { "paged Plex track index failed: ${error.message}" }
                    }.getOrDefault(false)
                    if (!indexed && plexRawMetadata.albums.isNotEmpty()) {
                        var loadedTracks = 0
                        syncTrace.network("plex.prefetchAlbumTracksFallback") {
                            plexBuilder.prefetchAlbumTracks(server, plexRawMetadata.albums.sortedByDescending { it.dateAddedMs ?: 0L }, token) { album, tracks ->
                                val prefixedTracks = tracks.map { it.withPlexPrefix() }
                                runCatalogDbWrite {
                                    persistTrackBatch(
                                        tracks = prefixedTracks,
                                        replaceParents = setOf("plex:${album.id}"),
                                    )
                                }
                                loadedTracks += tracks.size
                                updateTrackIndexSyncProgress(loadedTracks)
                            }
                        }
                        syncTrace.disk("hydrateInMemoryTracksFromDatabase") {
                            hydrateInMemoryTracksFromDatabase(preserveFrom = preserveTracksFrom)
                        }
                    }
                }

                mutableCatalogSyncState.value = mutableCatalogSyncState.value.copy(
                    phase = CatalogSyncPhase.FinishingArtwork,
                    message = "Finalizing artwork…",
                    detail = "Matching album and artist artwork",
                    loadedAlbums = mutableCatalog.value.albums.size,
                    loadedTracks = mutableCatalog.value.tracksByParent.values.sumOf { it.size },
                    blocking = false,
                )
                syncTrace.memory("mergeDeferredLocalCatalog") {
                    mergeDeferredLocalCatalog(localDeferred)
                }
                val finalSnapshot = syncTrace.memory("enrichWithTrackArtwork") {
                    plexBuilder.enrichWithTrackArtwork(mutableCatalog.value)
                        .copy(downloads = previous.downloads)
                }
                persistSnapshot = finalSnapshot.contentChecksum() != previous.contentChecksum() ||
                    stalePlaylists.isNotEmpty()
                yield()
                val finalSnapshotWithUserState = finalSnapshot.withPlaylistUserStateFrom(mutableCatalog.value)
                mutableCatalog.value = finalSnapshotWithUserState
                finalSnapshotWithUserState
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    if (foregroundRefreshing) {
                        popCatalogRefreshing()
                        foregroundRefreshing = false
                    }
                    mutableCatalogSyncState.value = CatalogSyncState()
                    throw error
                }
                mutableCatalogSyncState.value = CatalogSyncState(
                    phase = CatalogSyncPhase.Failed,
                    message = error.message ?: "Sync failed.",
                )
                if (foregroundRefreshing) {
                    popCatalogRefreshing()
                    foregroundRefreshing = false
                }
                throw error
            }
        }
        try {
            if (persistSnapshot) {
                mutableCatalogSyncState.value = CatalogSyncState(
                    phase = CatalogSyncPhase.Persisting,
                    message = "Saving library…",
                    loadedAlbums = mutableCatalog.value.albums.size,
                    loadedTracks = mutableCatalog.value.tracksByParent.values.sumOf { it.size },
                    blocking = false,
                )
                syncTrace.disk("awaitPendingCatalogDbWrites") {
                    awaitCatalogDbWrites()
                }
                syncTrace.disk("reconcileCatalogPersistence") {
                    runCatalogDbWrite { reconcileCatalogPersistence(snapshot) }
                }
            }
            if (stalePlaylists.isNotEmpty()) {
                syncTrace.network("warmStalePlaylistTracks") {
                    warmPlaylistTracksParallel(session, stalePlaylists, updateSyncProgress = true, trace = syncTrace)
                }
            }
            mutableCatalogSyncState.value = CatalogSyncState(
                phase = CatalogSyncPhase.Complete,
                message = "Library refreshed.",
                loadedAlbums = mutableCatalog.value.albums.size,
                loadedTracks = mutableCatalog.value.tracksByParent.values.sumOf { it.size },
                progress = 1f,
            )
            syncTrace.mark(
                "refreshAggregated complete",
                CatalogSyncStepKind.Other,
                "${mutableCatalog.value.albums.size} albums, " +
                    "${mutableCatalog.value.tracksByParent.values.sumOf { it.size }} tracks, persist=$persistSnapshot",
            )
        } catch (error: Throwable) {
            if (error is CancellationException) {
                mutableCatalogSyncState.value = CatalogSyncState()
                throw error
            }
            mutableCatalogSyncState.value = CatalogSyncState(
                phase = CatalogSyncPhase.Failed,
                message = error.message ?: "Sync failed.",
            )
            throw error
        } finally {
            if (foregroundRefreshing) {
                popCatalogRefreshing()
            }
        }
    }

    suspend fun syncLightweightRemoteState(session: PlexSession?) {
        val currentSession = session ?: return
        if (currentSession.selectedServer == null || currentSession.selectedLibrary == null) return
        if (currentSession.token.isBlank()) return
        refreshMutex.withLock {
            val remoteShell = when {
                currentSession.isPlex() -> lightweightPlexShell(currentSession)
                currentSession.isEmbyFamily() -> lightweightJellyfinShell(currentSession)
                currentSession.isNavidrome() -> lightweightNavidromeShell(currentSession)
                else -> CatalogSnapshot()
            }
            if (!remoteShell.isNotEmpty()) return@withLock
            val merged = mergeLightweightRemoteShell(
                previous = mutableCatalog.value,
                remotePrefix = currentSession.providerType.catalogPrefix,
                remoteShell = remoteShell,
                replaceRemoteShell = currentSession.isPlex(),
            )
            if (merged != mutableCatalog.value) {
                mutableCatalog.value = merged
                enqueueCatalogDbWrite { persistCatalogShell(merged) }
            }
        }
    }

    private suspend fun lightweightPlexShell(session: PlexSession): CatalogSnapshot {
        val server = session.selectedServer ?: return CatalogSnapshot()
        val library = session.selectedLibrary ?: return CatalogSnapshot()
        val token = session.serverAuthToken() ?: return CatalogSnapshot()
        PhoebeLog.d("CatalogRepository") { "lightweight sync start → Plex metadata" }
        runCatching { plexClient.prepareForCatalogRequests(server, token) }
        return PlexCatalogBuilder(plexClient, httpClient).buildMetadataCatalog(
            server = server,
            library = library,
            token = token,
            onProgress = null,
            trace = null,
        )
    }

    private suspend fun lightweightJellyfinShell(session: PlexSession): CatalogSnapshot = coroutineScope {
        val server = session.selectedServer ?: return@coroutineScope CatalogSnapshot()
        val library = session.selectedLibrary ?: return@coroutineScope CatalogSnapshot()
        val userId = session.userId?.takeIf { it.isNotBlank() } ?: return@coroutineScope CatalogSnapshot()
        val token = session.token.takeIf { it.isNotBlank() } ?: return@coroutineScope CatalogSnapshot()
        val remoteClient = if (session.providerType.catalogPrefix == "emby") embyClient else jellyfinClient
        val remoteLabel = session.providerType.displayName
        PhoebeLog.d("CatalogRepository") { "lightweight sync start → $remoteLabel shell" }
        val artistPageDeferred = async {
            remoteClient.artistPage(server, library, token, userId, pageIndex = 0)
        }
        val albumPageDeferred = async {
            remoteClient.albumPage(server, library, token, userId, pageIndex = 0)
        }
        val playlistsDeferred = async {
            runCatching { remoteClient.playlists(server, library, token, userId) }.getOrDefault(emptyList())
        }
        val artistPage = artistPageDeferred.await()
        val albumPage = albumPageDeferred.await()
        val albums = albumPage.items
        val artists = enrichArtistAlbumCountsOnly(
            enrichArtistArtwork(artistPage.items, albums),
            albums,
        )
        CatalogSnapshot(
            artists = artists,
            albums = albums,
            playlists = playlistsDeferred.await(),
            remotePageInfo = CatalogPageInfo(
                pageSize = albumPage.pageSize,
                artistTotal = artistPage.total,
                loadedArtistPages = if (artistPage.items.isNotEmpty()) setOf(0) else emptySet(),
                albumTotal = albumPage.total,
                loadedAlbumPages = if (albums.isNotEmpty()) setOf(0) else emptySet(),
            ),
        )
    }

    private suspend fun lightweightNavidromeShell(session: PlexSession): CatalogSnapshot {
        val server = session.selectedServer ?: return CatalogSnapshot()
        if (session.userName.isBlank() || session.token.isBlank()) return CatalogSnapshot()
        PhoebeLog.d("CatalogRepository") { "lightweight sync start → Subsonic shell" }
        return subsonicClient.quickCatalogShell(server, session.userName, session.token)
    }

    private fun mergeLightweightRemoteShell(
        previous: CatalogSnapshot,
        remotePrefix: String,
        remoteShell: CatalogSnapshot,
        replaceRemoteShell: Boolean,
    ): CatalogSnapshot {
        val prefixed = CatalogMerge.withPrefix(remotePrefix, remoteShell)
        val base = if (replaceRemoteShell) {
            previous.withoutProviderShell(remotePrefix)
        } else {
            previous
        }
        val merged = if (replaceRemoteShell) {
            CatalogMerge.merge(base, prefixed).copy(
                downloads = previous.downloads,
                tracksByParent = mergeTrackParents(base.tracksByParent, prefixed.tracksByParent),
            )
        } else {
            base.copy(
                artists = mergeItemsById(base.artists, prefixed.artists) { it.id },
                albums = mergeItemsById(base.albums, prefixed.albums) { it.id },
                playlists = mergeItemsById(base.playlists, prefixed.playlists) { it.id },
                tracksByParent = mergeTrackParents(base.tracksByParent, prefixed.tracksByParent),
                collectionValues = mergeItemsById(base.collectionValues, prefixed.collectionValues) {
                    "${it.target}:${it.facet}:${it.value}"
                },
                collectionValueLoads = mergeItemsById(base.collectionValueLoads, prefixed.collectionValueLoads) {
                    "${it.target}:${it.facet}"
                },
                collectionTags = mergeItemsById(base.collectionTags, prefixed.collectionTags) {
                    "${it.target}:${it.facet}:${it.itemId}:${it.value}"
                },
                downloads = previous.downloads,
                remotePageInfo = prefixed.remotePageInfo.takeIf { it.hasAny } ?: base.remotePageInfo,
            )
        }
        return merged.withPlaylistUserStateFrom(previous)
    }

    private inline fun <T> mergeItemsById(
        existing: List<T>,
        incoming: List<T>,
        id: (T) -> String,
    ): List<T> {
        if (incoming.isEmpty()) return existing
        if (existing.isEmpty()) return incoming.distinctBy(id)
        val merged = LinkedHashMap<String, T>(existing.size + incoming.size)
        existing.forEach { item -> merged[id(item)] = item }
        incoming.forEach { item -> merged[id(item)] = item }
        return merged.values.toList()
    }

    suspend fun refreshLocalFoldersOnly(session: PlexSession?) {
        PhoebeLog.d("CatalogRepository") {
            "refreshLocalFoldersOnly start → localFolders=${mediaSourcesRepository.state.value.localFolders.count { it.enabled }}"
        }
        try {
            refreshMutex.withLock {
                withCatalogRefreshing {
                    mutableCatalogSyncState.value = CatalogSyncState(
                        phase = CatalogSyncPhase.LoadingLibrary,
                        message = "Scanning local folders…",
                        loadedAlbums = mutableCatalog.value.albums.size,
                        loadedTracks = mutableCatalog.value.tracksByParent.values.sumOf { it.size },
                        blocking = true,
                    )
                    val previous = mutableCatalog.value
                    val ctx = SourceBuildContext(
                        session = session,
                        plexClient = plexClient,
                        httpClient = httpClient,
                        localFolders = mediaSourcesRepository.state.value.localFolders,
                        localFileMetadataCache = localFileMetadataCache,
                    )
                    val localRaw = LocalFolderMusicSourcePlugin.buildCatalog(ctx)
                    val remoteAndUserSnapshot = previous.withoutLocalFolderCatalog()
                    val merged = CatalogMerge.merge(remoteAndUserSnapshot, localRaw).copy(downloads = previous.downloads)
                    mutableCatalogSyncState.value = CatalogSyncState(
                        phase = CatalogSyncPhase.Persisting,
                        message = "Saving local library…",
                        loadedAlbums = merged.albums.size,
                        loadedTracks = merged.tracksByParent.values.sumOf { it.size },
                        blocking = false,
                    )
                    publish(merged, persist = true)
                    mutableCatalogSyncState.value = CatalogSyncState(
                        phase = CatalogSyncPhase.Complete,
                        message = "Local folders scanned.",
                        loadedAlbums = mutableCatalog.value.albums.size,
                        loadedTracks = mutableCatalog.value.tracksByParent.values.sumOf { it.size },
                    )
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            mutableCatalogSyncState.value = CatalogSyncState(
                phase = CatalogSyncPhase.Failed,
                message = error.message ?: "Local folder scan failed.",
            )
            throw error
        }
    }

    private suspend fun refreshAdapterAggregated(session: PlexSession) {
        val adapter = providerRegistry.adapterFor(session) ?: return
        if (adapter.capabilities.pagedCatalog && session.jellyfinSyncMode == JellyfinSyncMode.Quick) {
            refreshPagedAdapterAggregated(session, adapter)
            return
        }
        PhoebeLog.d("CatalogRepository") {
            "refreshAggregated start → ${session.providerType.name}=${session.selectedServer?.name ?: "none"}, " +
                "localFolders=${mediaSourcesRepository.state.value.localFolders.count { it.enabled }}"
        }
        val snapshot = try {
            refreshMutex.withLock {
                withCatalogRefreshing {
                    mutableCatalogSyncState.value = CatalogSyncState(
                        phase = CatalogSyncPhase.LoadingLibrary,
                        message = if (mutableCatalog.value.isNotEmpty()) "Refreshing library…" else "Loading your library…",
                        blocking = mutableCatalog.value.isNotEmpty().not(),
                    )
                    val previous = mutableCatalog.value
                    val ctx = SourceBuildContext(
                        session = session,
                        plexClient = plexClient,
                        httpClient = httpClient,
                        localFolders = mediaSourcesRepository.state.value.localFolders,
                        localFileMetadataCache = localFileMetadataCache,
                    )
                    val localDeferred = startDeferredLocalCatalog(ctx)
                    coroutineScope {
                        val remoteRaw = async { adapter.buildCatalog(session) }.await()
                        val remoteMerged = CatalogMerge.merge(
                            CatalogSnapshot(),
                            CatalogMerge.withPrefix(session.providerType.catalogPrefix, remoteRaw),
                            CatalogSnapshot(),
                        ).copy(downloads = previous.downloads)
                            .withPlaylistUserStateFrom(mutableCatalog.value)
                        mutableCatalog.value = remoteMerged
                        mutableCatalogSyncState.value = CatalogSyncState(
                            phase = CatalogSyncPhase.LoadingSongs,
                            message = "Loaded ${session.providerType.displayName} library…",
                            loadedAlbums = mutableCatalog.value.albums.size,
                            loadedTracks = mutableCatalog.value.tracksByParent.values.sumOf { it.size },
                            blocking = false,
                        )
                        mutableCatalogSyncState.value = CatalogSyncState(
                            phase = CatalogSyncPhase.Persisting,
                            message = "Saving library…",
                            loadedAlbums = remoteMerged.albums.size,
                            loadedTracks = remoteMerged.tracksByParent.values.sumOf { it.size },
                            blocking = false,
                        )
                        persistAsync(remoteMerged)
                        scheduleDeferredLocalCatalogMerge(localDeferred)
                        mutableCatalogSyncState.value = CatalogSyncState(
                            phase = CatalogSyncPhase.Complete,
                            message = "Library refreshed.",
                            loadedAlbums = remoteMerged.albums.size,
                            loadedTracks = remoteMerged.tracksByParent.values.sumOf { it.size },
                        )
                        remoteMerged
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            mutableCatalogSyncState.value = CatalogSyncState(
                phase = CatalogSyncPhase.Failed,
                message = error.message ?: "${session.providerType.name} sync failed.",
            )
            throw error
        }
        PhoebeLog.d("CatalogRepository") {
            "refreshAggregated complete → ${snapshot.albums.size} albums, " +
                "${snapshot.tracksByParent.values.sumOf { it.size }} tracks"
        }
    }

    private suspend fun refreshPagedAdapterAggregated(session: PlexSession, adapter: MusicProviderAdapter) {
        PhoebeLog.d("CatalogRepository") {
            "refreshAggregated start → ${session.providerType.name}=${session.selectedServer?.name ?: "none"} mode=quick, " +
                "localFolders=${mediaSourcesRepository.state.value.localFolders.count { it.enabled }}"
        }
        val snapshot = try {
            refreshMutex.withLock {
                withCatalogRefreshing {
                    mutableCatalogSyncState.value = CatalogSyncState(
                        phase = CatalogSyncPhase.LoadingLibrary,
                        message = if (mutableCatalog.value.isNotEmpty()) "Refreshing library…" else "Loading your library…",
                        blocking = mutableCatalog.value.isNotEmpty().not(),
                    )
                    val previous = mutableCatalog.value
                    val ctx = SourceBuildContext(
                        session = session,
                        plexClient = plexClient,
                        httpClient = httpClient,
                        localFolders = mediaSourcesRepository.state.value.localFolders,
                        localFileMetadataCache = localFileMetadataCache,
                    )
                    val localDeferred = startDeferredLocalCatalog(ctx)
                    coroutineScope {
                        val quickRaw = adapter.quickCatalog(session) ?: adapter.buildCatalog(session)
                        val remoteMerged = CatalogMerge.merge(
                            CatalogSnapshot(),
                            CatalogMerge.withPrefix(session.providerType.catalogPrefix, quickRaw),
                            CatalogSnapshot(),
                        ).copy(downloads = previous.downloads)
                            .withPlaylistUserStateFrom(mutableCatalog.value)
                        mutableCatalog.value = remoteMerged
                        mutableCatalogSyncState.value = CatalogSyncState(
                            phase = CatalogSyncPhase.LoadingSongs,
                            message = "Loaded ${session.providerType.displayName} library…",
                            loadedAlbums = mutableCatalog.value.albums.size,
                            loadedTracks = mutableCatalog.value.tracksByParent.values.sumOf { it.size },
                            blocking = false,
                        )
                        mutableCatalogSyncState.value = CatalogSyncState(
                            phase = CatalogSyncPhase.Persisting,
                            message = "Saving library…",
                            loadedAlbums = remoteMerged.albums.size,
                            loadedTracks = remoteMerged.tracksByParent.values.sumOf { it.size },
                            blocking = false,
                        )
                        persistAsync(remoteMerged)
                        scheduleDeferredLocalCatalogMerge(localDeferred)
                        mutableCatalogSyncState.value = CatalogSyncState(
                            phase = CatalogSyncPhase.Complete,
                            message = "Library refreshed.",
                            loadedAlbums = remoteMerged.albums.size,
                            loadedTracks = remoteMerged.tracksByParent.values.sumOf { it.size },
                        )
                        remoteMerged
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            mutableCatalogSyncState.value = CatalogSyncState(
                phase = CatalogSyncPhase.Failed,
                message = error.message ?: "${session.providerType.name} sync failed.",
            )
            throw error
        }
        PhoebeLog.d("CatalogRepository") {
            "refreshAggregated complete → ${snapshot.albums.size} albums, " +
                "${snapshot.tracksByParent.values.sumOf { it.size }} tracks"
        }
    }

    private suspend fun refreshNavidromeAggregated(session: PlexSession) {
        val remotePrefix = session.providerType.catalogPrefix
        val remoteLabel = "Subsonic"
        PhoebeLog.d("CatalogRepository") {
            "refreshAggregated start → $remotePrefix=${session.selectedServer?.name ?: "none"}, " +
                "localFolders=${mediaSourcesRepository.state.value.localFolders.count { it.enabled }}"
        }
        var foregroundRefreshing = false
        val snapshot = try {
            refreshMutex.withLock {
                val previous = mutableCatalog.value
                pushCatalogRefreshing()
                foregroundRefreshing = true
                publishCatalogSyncState(
                    CatalogSyncState(
                        phase = CatalogSyncPhase.LoadingLibrary,
                        message = if (previous.isNotEmpty()) "Refreshing library…" else "Loading Subsonic metadata…",
                        blocking = !previous.isNotEmpty(),
                    ),
                    force = true,
                )
                try {
                    val ctx = SourceBuildContext(
                        session = session,
                        plexClient = plexClient,
                        httpClient = httpClient,
                        localFolders = mediaSourcesRepository.state.value.localFolders,
                        localFileMetadataCache = localFileMetadataCache,
                    )
                    val server = session.selectedServer ?: return@withLock previous
                    val username = session.userName
                    val password = session.token
                    val library = session.selectedLibrary ?: NavidromeAllMusicLibrary
                    val localDeferred = startDeferredLocalCatalog(ctx)
                    coroutineScope {
                        var merged = CatalogSnapshot().copy(downloads = previous.downloads)
                        var incrementalPersist = false
                        var libraryShellPublished = false

                        suspend fun publishNavidromeProgress(
                            raw: CatalogSnapshot,
                            message: String,
                            persistProgress: Boolean = false,
                            publishShell: Boolean = false,
                        ) {
                            val currentMerged = mutableCatalog.value
                            val newMerged = CatalogMerge.merge(
                                CatalogSnapshot(),
                                CatalogMerge.withPrefix(remotePrefix, raw),
                                CatalogSnapshot(),
                            )
                            merged = newMerged.copy(
                                downloads = previous.downloads,
                                artists = newMerged.artists.ifEmpty { currentMerged.artists },
                                albums = newMerged.albums.ifEmpty { currentMerged.albums },
                                playlists = mergeDistinctPlaylists(currentMerged.playlists, newMerged.playlists),
                                tracksByParent = mergeTrackParents(
                                    currentMerged.tracksByParent,
                                    newMerged.tracksByParent,
                                ),
                            ).withPlaylistUserStateFrom(currentMerged)
                            mutableCatalog.value = merged
                            publishCatalogSyncState(
                                CatalogSyncState(
                                    phase = if (!publishShell && merged.tracksByParent.isEmpty()) {
                                        CatalogSyncPhase.LoadingLibrary
                                    } else {
                                        CatalogSyncPhase.LoadingSongs
                                    },
                                    message = message,
                                    loadedAlbums = merged.albums.size,
                                    loadedTracks = merged.tracksByParent.values.sumOf { it.size },
                                    blocking = false,
                                ),
                                force = publishShell,
                            )
                            if (persistProgress) {
                                incrementalPersist = true
                                enqueueCatalogDbWrite { persistCatalogShell(merged) }
                            }
                            if (publishShell && !libraryShellPublished) {
                                libraryShellPublished = true
                                popCatalogRefreshing()
                                foregroundRefreshing = false
                            }
                            yield()
                        }

                        if (session.jellyfinSyncMode == JellyfinSyncMode.Quick) {
                            publishCatalogSyncState(
                                CatalogSyncState(
                                    phase = CatalogSyncPhase.LoadingLibrary,
                                    message = "Loading Subsonic metadata…",
                                    blocking = !previous.isNotEmpty(),
                                ),
                                force = true,
                            )
                            val shell = subsonicClient.quickCatalogShell(server, username, password)
                            publishNavidromeProgress(
                                raw = shell,
                                message = "Indexing Subsonic songs…",
                                persistProgress = true,
                                publishShell = true,
                            )

                            val albumsToIndex = shell.albums.take(SubsonicQuickSyncAlbumTrackBatch)
                            if (albumsToIndex.isNotEmpty()) {
                                val albumsById = shell.albums.associateBy { it.id }
                                var indexedAlbumPages = 0
                                val totalAlbumPages = albumsToIndex.size
                                val progressJob = launch {
                                    while (isActive) {
                                        updateNavidromeAlbumIndexProgress(indexedAlbumPages, totalAlbumPages)
                                        delay(SyncProgressUpdateIntervalMs)
                                    }
                                }
                                val tracksByAlbum = subsonicClient.albumTracksParallel(
                                    server = server,
                                    albums = albumsToIndex,
                                    username = username,
                                    password = password,
                                    albumsById = albumsById,
                                    parallelism = catalogTrackIndexParallelism().coerceAtLeast(1),
                                ) { loaded, _ ->
                                    indexedAlbumPages = loaded
                                }
                                progressJob.cancel()
                                updateNavidromeAlbumIndexProgress(indexedAlbumPages, totalAlbumPages)
                                val likedTracks = shell.tracksByParent[LIKED_SONGS_PLAYLIST_BARE_ID].orEmpty()
                                val tracksWithLiked = if (likedTracks.isEmpty()) {
                                    tracksByAlbum
                                } else {
                                    tracksByAlbum + (LIKED_SONGS_PLAYLIST_BARE_ID to likedTracks)
                                }
                                publishNavidromeProgress(
                                    raw = shell.copy(
                                        tracksByParent = tracksWithLiked,
                                    ),
                                    message = "Indexing Subsonic songs…",
                                )
                            }
                        } else {
                            var indexedAlbumPages = 0
                            var totalAlbumPages = 0
                            val progressJob = launch {
                                while (isActive) {
                                    if (totalAlbumPages > 0) {
                                        updateNavidromeAlbumIndexProgress(indexedAlbumPages, totalAlbumPages)
                                    } else {
                                        publishCatalogSyncState(
                                            mutableCatalogSyncState.value.copy(
                                                phase = CatalogSyncPhase.LoadingLibrary,
                                                message = "Loading Subsonic metadata…",
                                                blocking = !libraryShellPublished && !previous.isNotEmpty(),
                                            ),
                                        )
                                    }
                                    delay(SyncProgressUpdateIntervalMs)
                                }
                            }
                            val remoteRaw = subsonicClient.buildCatalog(
                                server = server,
                                library = library,
                                username = username,
                                password = password,
                            ) { _, loadedAlbums, totalAlbums ->
                                indexedAlbumPages = loadedAlbums
                                totalAlbumPages = totalAlbums
                            }
                            progressJob.cancel()
                            if (totalAlbumPages > 0) {
                                updateNavidromeAlbumIndexProgress(indexedAlbumPages, totalAlbumPages)
                            }
                            publishNavidromeProgress(
                                raw = remoteRaw,
                                message = "Indexing Subsonic songs…",
                                persistProgress = true,
                                publishShell = true,
                            )
                        }

                        if (incrementalPersist) {
                            enqueueCatalogDbWrite { persistCatalogShell(merged) }
                        }
                        merged = merged.withPlaylistUserStateFrom(mutableCatalog.value)
                        persistAsync(merged)
                        val partialPaged = merged.remotePageInfo.hasUnloadedRemotePages()
                        if (incrementalPersist || partialPaged) {
                            persistenceScope.launch {
                                runCatching {
                                    awaitCatalogDbWrites()
                                    runCatalogDbWrite {
                                        reconcileCatalogPersistence(merged, partialPaged = partialPaged)
                                    }
                                }.onFailure { error ->
                                    PhoebeLog.d("CatalogRepository") {
                                        "background catalog reconcile failed: ${error.message}"
                                    }
                                }
                            }
                        }
                        scheduleDeferredLocalCatalogMerge(localDeferred)
                        publishCatalogSyncState(
                            CatalogSyncState(
                                phase = CatalogSyncPhase.Complete,
                                message = "Library ready.",
                                loadedAlbums = merged.albums.size,
                                loadedTracks = merged.tracksByParent.values.sumOf { it.size },
                                progress = 1f,
                            ),
                            force = true,
                        )
                        merged
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) {
                        if (foregroundRefreshing) {
                            popCatalogRefreshing()
                            foregroundRefreshing = false
                        }
                        mutableCatalogSyncState.value = CatalogSyncState()
                        throw error
                    }
                    mutableCatalogSyncState.value = CatalogSyncState(
                        phase = CatalogSyncPhase.Failed,
                        message = error.message ?: "$remoteLabel sync failed.",
                    )
                    if (foregroundRefreshing) {
                        popCatalogRefreshing()
                        foregroundRefreshing = false
                    }
                    throw error
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            mutableCatalogSyncState.value = CatalogSyncState(
                phase = CatalogSyncPhase.Failed,
                message = error.message ?: "$remoteLabel sync failed.",
            )
            throw error
        } finally {
            catalogRefreshingDepth = 0
            mutableCatalogRefreshing.value = false
        }
        PhoebeLog.d("CatalogRepository") {
            "refreshAggregated complete → ${snapshot.albums.size} albums, " +
                "${snapshot.tracksByParent.values.sumOf { it.size }} tracks"
        }
    }

    private suspend fun refreshJellyfinAggregated(session: PlexSession?) {
        val remoteClient = if (session?.providerType?.catalogPrefix == "emby") embyClient else jellyfinClient
        val remotePrefix = session?.providerType?.catalogPrefix ?: "jellyfin"
        val remoteLabel = session?.providerType?.name ?: "Jellyfin"
        PhoebeLog.d("CatalogRepository") {
            "refreshAggregated start → $remotePrefix=${session?.selectedServer?.name ?: "none"}, " +
                "localFolders=${mediaSourcesRepository.state.value.localFolders.count { it.enabled }}"
        }
        var foregroundRefreshing = false
        syncedTrackIdsDuringRefresh.clear()
        val snapshot = try {
            refreshMutex.withLock {
                var previous = mutableCatalog.value
                val preserveTracksFrom = tracksToPreserveDuringRefresh(previous)
                if (previous.tracksByParent.isEmpty() && preserveTracksFrom.isNotEmpty()) {
                    previous = previous.copy(tracksByParent = preserveTracksFrom)
                    mutableCatalog.value = previous
                }
                pushCatalogRefreshing()
                foregroundRefreshing = true
                mutableCatalogSyncState.value = CatalogSyncState(
                    phase = CatalogSyncPhase.LoadingLibrary,
                    message = if (previous.isNotEmpty()) "Refreshing library…" else "Loading your library…",
                    blocking = !previous.isNotEmpty(),
                )
                try {
                    val ctx = SourceBuildContext(
                        session = session,
                        plexClient = plexClient,
                        httpClient = httpClient,
                        localFolders = mediaSourcesRepository.state.value.localFolders,
                        localFileMetadataCache = localFileMetadataCache,
                    )
                    val server = session?.selectedServer
                    val library = session?.selectedLibrary
                    val token = session?.token?.takeIf { it.isNotBlank() }
                    val userId = session?.userId?.takeIf { it.isNotBlank() }
                    val localDeferred = startDeferredLocalCatalog(ctx)
                    coroutineScope {
                        var remoteRaw = CatalogSnapshot()
                        var merged = CatalogSnapshot().copy(downloads = previous.downloads)
                        var incrementalPersist = false
                        var libraryShellPublished = false

                        suspend fun publishJellyfinProgress(
                            raw: CatalogSnapshot,
                            message: String,
                            persistProgress: Boolean = false,
                            publishShell: Boolean = false,
                        ) {
                            remoteRaw = raw
                            val currentMerged = mutableCatalog.value
                            val newMerged = CatalogMerge.merge(
                                CatalogSnapshot(),
                                CatalogMerge.withPrefix(remotePrefix, remoteRaw),
                                CatalogSnapshot(),
                            )
                            merged = newMerged.copy(
                                downloads = previous.downloads,
                                artists = newMerged.artists.ifEmpty { currentMerged.artists },
                                albums = newMerged.albums.ifEmpty { currentMerged.albums },
                                playlists = mergeDistinctPlaylists(currentMerged.playlists, newMerged.playlists),
                                tracksByParent = mergeTrackParents(
                                    currentMerged.tracksByParent,
                                    newMerged.tracksByParent,
                                ),
                            ).withPlaylistUserStateFrom(currentMerged)
                            mutableCatalog.value = merged
                            mutableCatalogSyncState.value = CatalogSyncState(
                                phase = if (!publishShell && merged.tracksByParent.isEmpty()) {
                                    CatalogSyncPhase.LoadingLibrary
                                } else {
                                    CatalogSyncPhase.LoadingSongs
                                },
                                message = message,
                                loadedAlbums = merged.albums.size,
                                loadedTracks = merged.tracksByParent.values.sumOf { it.size },
                                blocking = false,
                            )
                            if (persistProgress) {
                                incrementalPersist = true
                                enqueueCatalogDbWrite { persistCatalogShell(merged) }
                            }
                            if (publishShell && !libraryShellPublished) {
                                libraryShellPublished = true
                                popCatalogRefreshing()
                                foregroundRefreshing = false
                            }
                            yield()
                        }

                        if (server != null && library != null && token != null && userId != null &&
                            session.jellyfinSyncMode == JellyfinSyncMode.Quick
                        ) {
                            mutableCatalogSyncState.value = CatalogSyncState(
                                phase = CatalogSyncPhase.LoadingLibrary,
                                message = "Loading first $remoteLabel pages…",
                                blocking = !previous.isNotEmpty(),
                            )
                            val artistPageDeferred = async {
                                remoteClient.artistPage(server, library, token, userId, pageIndex = 0)
                            }
                            val albumPageDeferred = async {
                                remoteClient.albumPage(server, library, token, userId, pageIndex = 0)
                            }
                            val trackPageDeferred = async {
                                remoteClient.trackPage(server, library, token, userId, pageIndex = 0)
                            }
                            val playlistsDeferred = async {
                                remoteClient.playlists(server, library, token, userId)
                            }

                            val artistPage = artistPageDeferred.await()
                            val pageInfoAfterArtists = CatalogPageInfo(
                                pageSize = artistPage.pageSize,
                                artistTotal = artistPage.total,
                                loadedArtistPages = if (artistPage.items.isNotEmpty()) setOf(0) else emptySet(),
                            )
                            publishJellyfinProgress(
                                CatalogSnapshot(artists = artistPage.items, remotePageInfo = pageInfoAfterArtists),
                                "Loaded first $remoteLabel artist page…",
                            )

                            val albumPage = albumPageDeferred.await()
                            val pageInfoAfterAlbums = pageInfoAfterArtists.copy(
                                albumTotal = albumPage.total,
                                loadedAlbumPages = if (albumPage.items.isNotEmpty()) setOf(0) else emptySet(),
                            )
                            val enrichedArtists = enrichArtistAlbumCountsOnly(
                                enrichArtistArtwork(artistPage.items, albumPage.items),
                                albumPage.items,
                            )
                            publishJellyfinProgress(
                                CatalogSnapshot(
                                    artists = enrichedArtists,
                                    albums = albumPage.items,
                                    remotePageInfo = pageInfoAfterAlbums,
                                ),
                                "Loaded first $remoteLabel album page…",
                                persistProgress = true,
                            )

                            val trackPage = trackPageDeferred.await()
                            val albumsById = albumPage.items.associateBy { it.id }
                            val tracks = trackPage.items.map { track ->
                                val album = track.parentAlbumId?.let(albumsById::get)
                                if (album == null) {
                                    track
                                } else {
                                    track.copy(
                                        album = track.album.takeUnless { it == "Unknown album" } ?: album.title,
                                        artist = track.artist.takeUnless { it == "Unknown artist" } ?: album.artist,
                                        thumbUrl = track.thumbUrl ?: album.thumbUrl,
                                    )
                                }
                            }
                            val tracksByAlbum = tracks
                                .groupBy {
                                    it.parentAlbumId?.takeIf { id -> id.isNotBlank() }
                                        ?: jellyfinAlbumIdByTitle(albumPage.items, it)
                                }
                                .filterKeys { it.isNotBlank() }
                            val pageInfoAfterTracks = pageInfoAfterAlbums.copy(
                                trackTotal = trackPage.total,
                                loadedTrackPages = if (trackPage.items.isNotEmpty()) setOf(0) else emptySet(),
                            )
                            publishJellyfinProgress(
                                CatalogSnapshot(
                                    artists = enrichedArtists,
                                    albums = albumPage.items,
                                    tracksByParent = tracksByAlbum,
                                    remotePageInfo = pageInfoAfterTracks,
                                ),
                                "Loaded first $remoteLabel song page…",
                                publishShell = true,
                            )
                            val quickTrackBatch = prefixRemoteTracks(remotePrefix, tracks, albumPage.items)
                            if (quickTrackBatch.isNotEmpty()) {
                                incrementalPersist = true
                                enqueueCatalogDbWrite { persistTrackBatch(quickTrackBatch) }
                            }
                            publishCatalogSyncState(CatalogSyncState(), force = true)

                            val playlists = withTimeoutOrNull(JellyfinQuickSyncPlaylistsTimeoutMs) {
                                playlistsDeferred.await()
                            }.orEmpty()
                            remoteRaw = CatalogSnapshot(
                                artists = enrichedArtists,
                                albums = albumPage.items,
                                playlists = playlists,
                                tracksByParent = tracksByAlbum,
                                remotePageInfo = pageInfoAfterTracks,
                            )
                        } else if (server != null && library != null && token != null && userId != null) {
                            mutableCatalogSyncState.value = CatalogSyncState(
                                phase = CatalogSyncPhase.LoadingLibrary,
                                message = "Loading $remoteLabel metadata…",
                                blocking = !previous.isNotEmpty(),
                            )
                            val albumsDeferred = async {
                                loadJellyfinFullAlbums(
                                    remoteClient = remoteClient,
                                    server = server,
                                    library = library,
                                    token = token,
                                    userId = userId,
                                    remoteLabel = remoteLabel,
                                )
                            }
                            val artistsDeferred = async {
                                remoteClient.artists(server, library, token, userId)
                            }
                            val albums = albumsDeferred.await()
                            val artists = artistsDeferred.await()
                            val enrichedArtists = enrichArtistAlbumCountsOnly(
                                enrichArtistArtwork(artists, albums),
                                albums,
                            )
                            publishJellyfinProgress(
                                CatalogSnapshot(artists = enrichedArtists, albums = albums),
                                "Loaded $remoteLabel metadata, indexing songs…",
                                persistProgress = true,
                                publishShell = true,
                            )

                            indexJellyfinFullTracks(
                                remoteClient = remoteClient,
                                server = server,
                                library = library,
                                token = token,
                                userId = userId,
                                remotePrefix = remotePrefix,
                                remoteLabel = remoteLabel,
                                albums = albums,
                                preserveTracksFrom = preserveTracksFrom,
                            ) { batch ->
                                if (batch.isNotEmpty()) incrementalPersist = true
                            }

                            val playlists = remoteClient.playlists(server, library, token, userId)
                            remoteRaw = CatalogSnapshot(
                                artists = enrichedArtists,
                                albums = albums,
                                playlists = playlists,
                                tracksByParent = mutableCatalog.value.tracksByParent,
                            )
                        }
                        merged = enrichJellyfinCatalogArtwork(
                            CatalogMerge.merge(
                                CatalogSnapshot(),
                                CatalogMerge.withPrefix(remotePrefix, remoteRaw),
                                CatalogSnapshot(),
                            ).copy(
                                downloads = previous.downloads,
                                tracksByParent = mutableCatalog.value.tracksByParent,
                            ),
                        ).withPlaylistUserStateFrom(mutableCatalog.value)
                        mutableCatalog.value = merged
                        val quickSync = session?.jellyfinSyncMode == JellyfinSyncMode.Quick
                        if (!quickSync) {
                            mutableCatalogSyncState.value = CatalogSyncState(
                                phase = CatalogSyncPhase.Persisting,
                                message = "Saving library…",
                                loadedAlbums = merged.albums.size,
                                loadedTracks = merged.tracksByParent.values.sumOf { it.size },
                                blocking = false,
                            )
                        }
                        if (quickSync) {
                            if (incrementalPersist) {
                                enqueueCatalogDbWrite { persistCatalogShell(merged) }
                            }
                            persistAsync(merged)
                            val partialPaged = merged.remotePageInfo.hasUnloadedRemotePages()
                            if (incrementalPersist || partialPaged) {
                                persistenceScope.launch {
                                    runCatching {
                                        awaitCatalogDbWrites()
                                        runCatalogDbWrite {
                                            reconcileCatalogPersistence(merged, partialPaged = partialPaged)
                                        }
                                    }.onFailure { error ->
                                        PhoebeLog.d("CatalogRepository") {
                                            "background catalog reconcile failed: ${error.message}"
                                        }
                                    }
                                }
                            }
                        } else {
                            finalizeRemoteCatalogPersistence(
                                merged,
                                incrementalPersist,
                                partialPaged = merged.remotePageInfo.hasUnloadedRemotePages(),
                            )
                        }
                        scheduleDeferredLocalCatalogMerge(localDeferred)
                        if (!quickSync) {
                            publishCatalogSyncState(
                                CatalogSyncState(
                                    phase = CatalogSyncPhase.Complete,
                                    message = "Library ready.",
                                    loadedAlbums = merged.albums.size,
                                    loadedTracks = merged.tracksByParent.values.sumOf { it.size },
                                    progress = 1f,
                                ),
                                force = true,
                            )
                        }
                        publishCatalogSyncState(CatalogSyncState(), force = true)
                        merged
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) {
                        if (foregroundRefreshing) {
                            popCatalogRefreshing()
                            foregroundRefreshing = false
                        }
                        mutableCatalogSyncState.value = CatalogSyncState()
                        throw error
                    }
                    mutableCatalogSyncState.value = CatalogSyncState(
                        phase = CatalogSyncPhase.Failed,
                        message = error.message ?: "$remoteLabel sync failed.",
                    )
                    if (foregroundRefreshing) {
                        popCatalogRefreshing()
                        foregroundRefreshing = false
                    }
                    throw error
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            mutableCatalogSyncState.value = CatalogSyncState(
                phase = CatalogSyncPhase.Failed,
                message = error.message ?: "$remoteLabel sync failed.",
            )
            throw error
        } finally {
            catalogRefreshingDepth = 0
            mutableCatalogRefreshing.value = false
        }
        PhoebeLog.d("CatalogRepository") {
            "refreshAggregated complete → ${snapshot.albums.size} albums, " +
                "${snapshot.tracksByParent.values.sumOf { it.size }} tracks"
        }
    }

    suspend fun loadJellyfinLibraryPage(session: PlexSession?, kind: JellyfinLibraryPageKind, pageIndex: Int) {
        if (session == null || session.jellyfinSyncMode != JellyfinSyncMode.Quick || pageIndex < 0) return
        if (!session.isEmbyFamily()) {
            loadAdapterLibraryPage(session, kind, pageIndex)
            return
        }
        val remoteClient = if (session.providerType.catalogPrefix == "emby") embyClient else jellyfinClient
        val remotePrefix = session.providerType.catalogPrefix
        val remoteLabel = session.providerType.name
        val server = session.selectedServer ?: return
        val library = session.selectedLibrary ?: return
        val token = session.token.takeIf { it.isNotBlank() } ?: return
        val userId = session.userId?.takeIf { it.isNotBlank() } ?: return
        refreshMutex.withLock {
            val current = mutableCatalog.value
            val info = current.remotePageInfo
            val alreadyLoaded = when (kind) {
                JellyfinLibraryPageKind.Artists -> pageIndex in info.loadedArtistPages
                JellyfinLibraryPageKind.Albums -> pageIndex in info.loadedAlbumPages
                JellyfinLibraryPageKind.Tracks -> pageIndex in info.loadedTrackPages
            }
            if (alreadyLoaded) return

            mutableCatalogSyncState.value = CatalogSyncState(
                phase = CatalogSyncPhase.LoadingLibrary,
                message = "Loading $remoteLabel ${kind.name.lowercase()} page ${pageIndex + 1}…",
                loadedAlbums = current.albums.size,
                loadedTracks = current.tracksByParent.values.sumOf { it.size },
                blocking = false,
            )

            val updated = when (kind) {
                JellyfinLibraryPageKind.Artists -> {
                    val page = remoteClient.artistPage(server, library, token, userId, pageIndex)
                    val prefixed = CatalogMerge.withPrefix(remotePrefix, CatalogSnapshot(artists = page.items)).artists
                    current.copy(
                        artists = (current.artists + prefixed).distinctBy { it.id },
                        remotePageInfo = info.copy(
                            pageSize = page.pageSize,
                            artistTotal = page.total,
                            loadedArtistPages = info.loadedArtistPages + pageIndex,
                        ),
                    )
                }
                JellyfinLibraryPageKind.Albums -> {
                    val page = remoteClient.albumPage(server, library, token, userId, pageIndex)
                    val prefixed = CatalogMerge.withPrefix(remotePrefix, CatalogSnapshot(albums = page.items)).albums
                    val nextAlbums = (current.albums + prefixed).distinctBy { it.id }
                    current.copy(
                        albums = nextAlbums,
                        artists = enrichArtistAlbumCountsOnly(enrichArtistArtwork(current.artists, nextAlbums), nextAlbums),
                        remotePageInfo = info.copy(
                            pageSize = page.pageSize,
                            albumTotal = page.total,
                            loadedAlbumPages = info.loadedAlbumPages + pageIndex,
                        ),
                    )
                }
                JellyfinLibraryPageKind.Tracks -> {
                    val page = remoteClient.trackPage(server, library, token, userId, pageIndex)
                    val rawTracksByAlbum = page.items
                        .groupBy { it.parentAlbumId?.takeIf { id -> id.isNotBlank() } ?: jellyfinAlbumIdByTitle(emptyList(), it) }
                        .filterKeys { it.isNotBlank() }
                    val prefixedTracks = CatalogMerge.withPrefix(remotePrefix, CatalogSnapshot(tracksByParent = rawTracksByAlbum)).tracksByParent
                    current.copy(
                        tracksByParent = mergeTrackParents(current.tracksByParent, prefixedTracks),
                        remotePageInfo = info.copy(
                            pageSize = page.pageSize,
                            trackTotal = page.total,
                            loadedTrackPages = info.loadedTrackPages + pageIndex,
                        ),
                    )
                }
            }

            val updatedWithUserState = updated.withPlaylistUserStateFrom(mutableCatalog.value)
            mutableCatalog.value = updatedWithUserState
            persistAsync(updatedWithUserState)
            mutableCatalogSyncState.value = CatalogSyncState(
                phase = CatalogSyncPhase.Complete,
                message = "Loaded $remoteLabel ${kind.name.lowercase()} page ${pageIndex + 1}.",
                loadedAlbums = updatedWithUserState.albums.size,
                loadedTracks = updatedWithUserState.tracksByParent.values.sumOf { it.size },
            )
        }
    }

    private suspend fun loadAdapterLibraryPage(session: PlexSession, kind: JellyfinLibraryPageKind, pageIndex: Int) {
        if (kind != JellyfinLibraryPageKind.Albums) return
        val adapter = providerRegistry.adapterFor(session)?.takeIf { it.capabilities.pagedCatalog } ?: return
        refreshMutex.withLock {
            val current = mutableCatalog.value
            val info = current.remotePageInfo
            if (pageIndex in info.loadedAlbumPages) return

            mutableCatalogSyncState.value = CatalogSyncState(
                phase = CatalogSyncPhase.LoadingLibrary,
                message = "Loading ${session.providerType.name} albums page ${pageIndex + 1}…",
                loadedAlbums = current.albums.size,
                loadedTracks = current.tracksByParent.values.sumOf { it.size },
                blocking = false,
            )
            val (rawSnapshot, page) = adapter.albumPageCatalog(session, pageIndex) ?: return
            val prefixed = CatalogMerge.withPrefix(session.providerType.catalogPrefix, rawSnapshot)
            val updated = current.copy(
                albums = (current.albums + prefixed.albums).distinctBy { it.id },
                tracksByParent = mergeTrackParents(current.tracksByParent, prefixed.tracksByParent),
                remotePageInfo = info.copy(
                    pageSize = page.pageSize,
                    albumTotal = page.total,
                    loadedAlbumPages = info.loadedAlbumPages + pageIndex,
                ),
            )
            val updatedWithUserState = updated.withPlaylistUserStateFrom(mutableCatalog.value)
            mutableCatalog.value = updatedWithUserState
            persistAsync(updatedWithUserState)
            mutableCatalogSyncState.value = CatalogSyncState(
                phase = CatalogSyncPhase.Complete,
                message = "Loaded ${session.providerType.name} albums page ${pageIndex + 1}.",
                loadedAlbums = updatedWithUserState.albums.size,
                loadedTracks = updatedWithUserState.tracksByParent.values.sumOf { it.size },
            )
        }
    }

    suspend fun ensureCollectionItems(session: PlexSession?, entry: CollectionEntry, value: String) {
        if (!session.supportsCollectionEntry(entry)) {
            PhoebeLog.d("PlexCollections") { "lazy items skipped target=${entry.target.name} facet=${entry.facet.name}: unsupported provider facet" }
            return
        }
        val normalizedValue = value.trim()
        if (normalizedValue.isBlank()) return
        try {
            val server = session?.selectedServer ?: return
            val library = session.selectedLibrary ?: return
            val token = session.serverAuthToken() ?: return
            ensureCollectionValues(session, entry)
            markCollectionValueItemsLoading(entry, normalizedValue)
            val current = mutableCatalog.value
            val matchingTags = current.collectionTags.filter {
                it.target == entry.target.name &&
                    it.facet == entry.facet.name &&
                    it.value.equals(normalizedValue, ignoreCase = true)
            }
            val existingTargetIds = current.collectionTargetIds(entry.target)
            val alreadyLoaded = matchingTags.any { it.itemId.toPlexCatalogId() in existingTargetIds }
            PhoebeLog.d("PlexCollections") {
                "lazy item state target=${entry.target.name} facet=${entry.facet.name} value='$normalizedValue' tags=${matchingTags.size} usable=$alreadyLoaded targetIds=${existingTargetIds.size} sample=${matchingTags.take(10).map { it.itemId }}"
            }
            if (alreadyLoaded) return
            val collectionValue = resolveCollectionValue(
                server = server,
                library = library,
                token = token,
                entry = entry,
                normalizedValue = normalizedValue,
            )
            val target = entry.target.toPlexCollectionTarget()
            val facet = entry.facet.toPlexCollectionFacet()
            PhoebeLog.d("PlexCollections") {
                "lazy load target=${entry.target.name} facet=${entry.facet.name} value='${collectionValue.value}'"
            }
            val cachedChoice = PlexFilterChoice(
                key = collectionValue.key,
                title = collectionValue.value,
                fastKey = collectionValue.fastKey,
                filterField = collectionValue.filterField,
            )
            var resolvedChoice = cachedChoice
            var plexItemIds = plexClient.collectionFilterItems(
                server = server,
                library = library,
                target = target,
                facet = facet,
                choice = cachedChoice,
                token = token,
            )
            if (plexItemIds.isEmpty()) {
                val refreshedChoice = plexClient.collectionFilterChoices(
                    server = server,
                    library = library,
                    target = target,
                    facet = facet,
                    token = token,
                ).firstOrNull { it.title.equals(collectionValue.value, ignoreCase = true) }
                if (refreshedChoice != null && refreshedChoice != cachedChoice) {
                    PhoebeLog.d("PlexCollections") {
                        "lazy choice refreshed target=${entry.target.name} facet=${entry.facet.name} value='${collectionValue.value}' oldKey='${cachedChoice.key}' newKey='${refreshedChoice.key}' oldField='${cachedChoice.filterField}' newField='${refreshedChoice.filterField}'"
                    }
                    resolvedChoice = refreshedChoice
                    plexItemIds = plexClient.collectionFilterItems(
                        server = server,
                        library = library,
                        target = target,
                        facet = facet,
                        choice = refreshedChoice,
                        token = token,
                    )
                }
            }
            val itemIds = plexItemIds.ifEmpty {
                hydrateTracksFromDatabaseIfEmpty()
                mutableCatalog.value.collectionItemIdsFromIndexedMetadata(entry, collectionValue.value)
            }
            val loadedTags = itemIds.map { itemId ->
                CatalogCollectionTag(
                    target = entry.target.name,
                    facet = entry.facet.name,
                    itemId = itemId.toPlexCatalogId(),
                    value = collectionValue.value,
                )
            }
            catalogMergeMutex.withLock {
                val latest = mutableCatalog.value
                val retained = latest.collectionTags.filterNot {
                    it.target == entry.target.name &&
                        it.facet == entry.facet.name &&
                        it.value.equals(collectionValue.value, ignoreCase = true)
                }
                val values = latest.collectionValues.map {
                    if (it.target == entry.target.name &&
                        it.facet == entry.facet.name &&
                        it.value.equals(collectionValue.value, ignoreCase = true)
                    ) {
                        it.copy(
                            key = resolvedChoice.key,
                            fastKey = resolvedChoice.fastKey,
                            filterField = resolvedChoice.filterField,
                        )
                    } else {
                        it
                    }
                }
                val updated = latest.copy(
                    collectionValues = values,
                    collectionTags = (retained + loadedTags).distinct(),
                )
                mutableCatalog.value = updated
                persistAsync(updated)
            }
            PhoebeLog.d("PlexCollections") {
                "lazy loaded target=${entry.target.name} facet=${entry.facet.name} value='${collectionValue.value}' plexItems=${plexItemIds.size} items=${loadedTags.size}"
            }
        } finally {
            markCollectionValueItemsLoaded(entry, normalizedValue)
        }
    }

    private fun CatalogSnapshot.collectionItemIdsFromIndexedMetadata(
        entry: CollectionEntry,
        value: String,
    ): List<String> {
        val normalized = value.trim()
        if (normalized.isBlank()) return emptyList()
        val ids = when (entry.target) {
            CollectionTarget.Albums -> {
                val directAlbumIds = albums.asSequence()
                    .filter { it.collectionLabel(entry.facet).matchesCollectionValue(normalized) }
                    .map { it.id }
                val trackAlbumIds = tracksByParent.asSequence()
                    .filter { (_, tracks) ->
                        tracks.any { it.collectionLabel(entry.facet).matchesCollectionValue(normalized) }
                    }
                    .map { (albumId, _) -> albumId }
                (directAlbumIds + trackAlbumIds).toList()
            }
            CollectionTarget.Artists -> {
                val directArtistIds = artists.asSequence()
                    .filter { it.collectionLabel(entry.facet).matchesCollectionValue(normalized) }
                    .map { it.id }
                val artistIdsByTitle = artists.associateBy { it.title.lowercase() }
                val trackArtistIds = tracksByParent.values.asSequence()
                    .flatten()
                    .filter { it.collectionLabel(entry.facet).matchesCollectionValue(normalized) }
                    .mapNotNull { artistIdsByTitle[it.artist.lowercase()]?.id }
                (directArtistIds + trackArtistIds).toList()
            }
        }
        val distinct = ids.map { it.removePrefix("plex:") }.filter { it.isNotBlank() }.distinct()
        PhoebeLog.d("PlexCollections") {
            "indexed fallback target=${entry.target.name} facet=${entry.facet.name} value='$value' items=${distinct.size}"
        }
        return distinct
    }

    private fun CatalogSnapshot.collectionTargetIds(target: CollectionTarget): Set<String> =
        when (target) {
            CollectionTarget.Albums -> albums.map { it.id }.toSet()
            CollectionTarget.Artists -> artists.map { it.id }.toSet()
        }

    private fun Album.collectionLabel(facet: CollectionFacet): String? = when (facet) {
        CollectionFacet.Genre -> genre
        CollectionFacet.Mood -> mood
        CollectionFacet.Style -> style
    }

    private fun Artist.collectionLabel(facet: CollectionFacet): String? = when (facet) {
        CollectionFacet.Genre -> genre
        CollectionFacet.Mood -> mood
        CollectionFacet.Style -> style
    }

    private fun Track.collectionLabel(facet: CollectionFacet): String? = when (facet) {
        CollectionFacet.Genre -> genre
        CollectionFacet.Mood -> mood
        CollectionFacet.Style -> style
    }

    private fun String.toPlexCatalogId(): String {
        val raw = removePrefix("plex:").removePrefix("plex:")
        return "plex:$raw"
    }

    private fun String?.matchesCollectionValue(value: String): Boolean {
        val normalized = value.trim()
        if (normalized.isBlank()) return false
        return splitCollectionTagLabels(this).any { it.equals(normalized, ignoreCase = true) }
    }

    private suspend fun markCollectionValueItemsLoading(entry: CollectionEntry, value: String) {
        catalogMergeMutex.withLock {
            val latest = mutableCatalog.value
            val values = latest.collectionValues.map {
                if (it.target == entry.target.name &&
                    it.facet == entry.facet.name &&
                    it.value.equals(value, ignoreCase = true)
                ) {
                    it.copy(itemsLoaded = false)
                } else {
                    it
                }
            }
            if (values != latest.collectionValues) {
                mutableCatalog.value = latest.copy(collectionValues = values)
            }
        }
    }

    private suspend fun markCollectionValueItemsLoaded(entry: CollectionEntry, value: String) {
        catalogMergeMutex.withLock {
            val latest = mutableCatalog.value
            val hasMatch = latest.collectionValues.any {
                it.target == entry.target.name &&
                    it.facet == entry.facet.name &&
                    it.value.equals(value, ignoreCase = true)
            }
            val values = if (hasMatch) {
                latest.collectionValues.map {
                    if (it.target == entry.target.name &&
                        it.facet == entry.facet.name &&
                        it.value.equals(value, ignoreCase = true)
                    ) {
                        it.copy(itemsLoaded = true)
                    } else {
                        it
                    }
                }
            } else {
                latest.collectionValues + CatalogCollectionValue(
                    target = entry.target.name,
                    facet = entry.facet.name,
                    value = value,
                    key = value,
                    itemsLoaded = true,
                )
            }
            if (values != latest.collectionValues) {
                val updated = latest.copy(collectionValues = values)
                mutableCatalog.value = updated
                persistAsync(updated)
            }
        }
    }

    suspend fun ensureCollectionValues(session: PlexSession?, entry: CollectionEntry) {
        if (!session.supportsCollectionEntry(entry)) {
            PhoebeLog.d("PlexCollections") { "lazy values skipped target=${entry.target.name} facet=${entry.facet.name}: unsupported provider facet" }
            return
        }
        try {
            val server = session?.selectedServer
            if (server == null) {
                PhoebeLog.d("PlexCollections") { "lazy values skipped target=${entry.target.name} facet=${entry.facet.name}: no selected server" }
                return
            }
            val library = session.selectedLibrary
            if (library == null) {
                PhoebeLog.d("PlexCollections") { "lazy values skipped target=${entry.target.name} facet=${entry.facet.name}: no selected library" }
                return
            }
            val token = session.serverAuthToken()
            if (token == null) {
                PhoebeLog.d("PlexCollections") { "lazy values skipped target=${entry.target.name} facet=${entry.facet.name}: no auth token" }
                return
            }
            val current = mutableCatalog.value
            val alreadyLoaded = current.collectionValues.any {
                it.target == entry.target.name && it.facet == entry.facet.name
            }
            PhoebeLog.d("PlexCollections") {
                val matchingValues = current.collectionValues.filter {
                    it.target == entry.target.name && it.facet == entry.facet.name
                }
                val loadMarkers = current.collectionValueLoads.count {
                    it.target == entry.target.name && it.facet == entry.facet.name
                }
                "lazy values state target=${entry.target.name} facet=${entry.facet.name} alreadyLoaded=$alreadyLoaded values=${matchingValues.size} loadMarkers=$loadMarkers sample=${matchingValues.take(10).map { "${it.value}:${it.key}:${it.filterField}" }}"
            }
            if (alreadyLoaded) return

            val target = entry.target.toPlexCollectionTarget()
            val facet = entry.facet.toPlexCollectionFacet()
            PhoebeLog.d("PlexCollections") {
                "lazy values target=${entry.target.name} facet=${entry.facet.name}"
            }
            val loadedValues = plexClient.collectionFilterChoices(
                server = server,
                library = library,
                target = target,
                facet = facet,
                token = token,
            ).map { choice ->
                CatalogCollectionValue(
                    target = entry.target.name,
                    facet = entry.facet.name,
                    value = choice.title,
                    key = choice.key,
                    fastKey = choice.fastKey,
                    filterField = choice.filterField,
                    itemsLoaded = false,
                )
            }
            catalogMergeMutex.withLock {
                val latest = mutableCatalog.value
                val retained = latest.collectionValues.filterNot {
                    it.target == entry.target.name && it.facet == entry.facet.name
                }
                val updated = latest.copy(
                    collectionValues = (retained + loadedValues).distinct(),
                    collectionValueLoads = (
                        latest.collectionValueLoads +
                            CatalogCollectionValueLoad(target = entry.target.name, facet = entry.facet.name)
                        ).distinct(),
                )
                mutableCatalog.value = updated
                persistAsync(updated)
            }
            PhoebeLog.d("PlexCollections") {
                "lazy values loaded target=${entry.target.name} facet=${entry.facet.name} count=${loadedValues.size}"
            }
        } finally {
            markCollectionValuesFetchAttempted(entry)
        }
    }

    private suspend fun resolveCollectionValue(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        entry: CollectionEntry,
        normalizedValue: String,
    ): CatalogCollectionValue {
        mutableCatalog.value.collectionValues.firstOrNull {
            it.target == entry.target.name &&
                it.facet == entry.facet.name &&
                it.value.equals(normalizedValue, ignoreCase = true)
        }?.let { return it }

        val target = entry.target.toPlexCollectionTarget()
        val facet = entry.facet.toPlexCollectionFacet()
        val refreshedChoice = plexClient.collectionFilterChoices(
            server = server,
            library = library,
            target = target,
            facet = facet,
            token = token,
        ).firstOrNull { it.title.equals(normalizedValue, ignoreCase = true) }
        if (refreshedChoice != null) {
            val resolved = CatalogCollectionValue(
                target = entry.target.name,
                facet = entry.facet.name,
                value = refreshedChoice.title,
                key = refreshedChoice.key,
                fastKey = refreshedChoice.fastKey,
                filterField = refreshedChoice.filterField,
                itemsLoaded = false,
            )
            return catalogMergeMutex.withLock {
                val latest = mutableCatalog.value
                latest.collectionValues.firstOrNull {
                    it.target == entry.target.name &&
                        it.facet == entry.facet.name &&
                        it.value.equals(resolved.value, ignoreCase = true)
                } ?: resolved.also { value ->
                    val updated = latest.copy(collectionValues = latest.collectionValues + value)
                    mutableCatalog.value = updated
                    persistAsync(updated)
                }
            }
        }

        return CatalogCollectionValue(
            target = entry.target.name,
            facet = entry.facet.name,
            value = normalizedValue,
            key = normalizedValue,
            itemsLoaded = false,
        )
    }

    private suspend fun markCollectionValuesFetchAttempted(entry: CollectionEntry) {
        catalogMergeMutex.withLock {
            val latest = mutableCatalog.value
            if (latest.hasCollectionValueLoad(entry)) return@withLock
            val updated = latest.copy(
                collectionValueLoads = latest.collectionValueLoads +
                    CatalogCollectionValueLoad(target = entry.target.name, facet = entry.facet.name),
            )
            mutableCatalog.value = updated
            persistAsync(updated)
        }
    }

    private data class ReconciledSnapshot(
        val snapshot: CatalogSnapshot,
        val stalePlaylists: List<Playlist>,
    )

    private suspend fun publishPlexMetadataPartial(
        raw: CatalogSnapshot,
        previous: CatalogSnapshot,
        session: PlexSession?,
        message: String,
        updateSyncState: Boolean = true,
    ) {
        val merged = CatalogMerge.merge(
            CatalogSnapshot(),
            CatalogMerge.withPrefix("plex", raw),
        )
        val reconciled = reconcileMergedSnapshot(
            merged = merged,
            previous = previous,
            session = session,
        )
        val snapshot = reconciled.snapshot.withPlaylistUserStateFrom(mutableCatalog.value)
        mutableCatalog.value = snapshot
        enqueueCatalogDbWrite { persistCatalogShell(snapshot) }
        if (!updateSyncState) return
        val artistCount = snapshot.artists.size
        val albumCount = snapshot.albums.size
        val playlistCount = snapshot.playlists.size
        mutableCatalogSyncState.value = CatalogSyncState(
            phase = CatalogSyncPhase.LoadingLibrary,
            message = message,
            detail = "$artistCount artists · $albumCount albums · $playlistCount playlists",
            loadedAlbums = albumCount,
            loadedTracks = snapshot.tracksByParent.values.sumOf { it.size },
            totalPlaylists = playlistCount,
            blocking = false,
        )
    }

    private fun reconcileMergedSnapshot(
        merged: CatalogSnapshot,
        previous: CatalogSnapshot,
        session: PlexSession?,
    ): ReconciledSnapshot {
        val sanitizedPrevious = previous.withoutInactiveLocalFolderCatalog(activeLocalFolderIds())
        // The Plex builder prefetches tracks after the first metadata publish. To avoid wiping
        // lazily-loaded entries that the user has accumulated, keep previous entries for any
        // parent that still exists and let newly-fetched data overlay them later.
        val knownParents =
            (merged.albums.asSequence().map { it.id } +
                merged.playlists.asSequence().map { it.id }).toSet()
        val localPlaylists = sanitizedPrevious.playlists.filter { it.isLocalPlaylist() }
        val localPlaylistIds = localPlaylists.map { it.id }.toSet()
        val currentToken = session.serverAuthToken()
        val preservedTracks = sanitizedPrevious.tracksByParent
            .filterKeys { it in knownParents || it in localPlaylistIds }
            .filterValues { tracks ->
                tracks.all { it.shouldPreserveAcrossPlexRefresh(currentToken) }
            }

        // If Plex reports a playlist count mismatch, keep stale tracks visible and refetch after
        // the main catalog is published so additions and removals update the detail panel in place.
        // Liked Songs is also fetched the first time it appears so global heart state has ids to
        // compare against.
        val staleForRefetch = mutableListOf<Playlist>()
        val reconciledPlaylists = merged.playlists.map { p ->
            val cached = preservedTracks[p.id]
            val cachedSize = cached?.size ?: 0
            when {
                p.trackCount > 0 && cached == null && p.thumbUrl.isNullOrBlank() -> {
                    staleForRefetch += p
                    p
                }
                cached != null && p.trackCount != cachedSize -> {
                    staleForRefetch += p
                    p
                }
                else -> p
            }
        }

        val reconciled = merged.copy(
            playlists = reconciledPlaylists + localPlaylists,
            tracksByParent = preservedTracks + merged.tracksByParent,
            collectionValues = sanitizedPrevious.collectionValues,
            collectionValueLoads = sanitizedPrevious.collectionValueLoads,
            collectionTags = sanitizedPrevious.collectionTags,
            downloads = sanitizedPrevious.downloads,
        )
        return ReconciledSnapshot(
            snapshot = preserveDateAdded(sanitizedPrevious, reconciled),
            stalePlaylists = staleForRefetch,
        )
    }

    private fun preserveDateAdded(previous: CatalogSnapshot, next: CatalogSnapshot): CatalogSnapshot {
        val previousTracks = previous.tracksByParent.values.flatten().associateBy { it.id }
        val tracksByParent = next.tracksByParent.mapValues { (_, tracks) ->
            tracks.map { track ->
                val previousAdded = previousTracks[track.id]?.dateAddedMs
                if (previousAdded != null) track.copy(dateAddedMs = previousAdded) else track
            }
        }
        val maxDateByAlbumKey = buildMap<Pair<String, String>, Long> {
            tracksByParent.values.flatten().forEach { track ->
                val key = track.album.lowercase() to track.artist.lowercase()
                val added = track.dateAddedMs ?: previousTracks[track.id]?.dateAddedMs ?: return@forEach
                put(key, maxOf(this[key] ?: added, added))
            }
        }
        val maxDateByArtistTitle = buildMap<String, Long> {
            next.albums.forEach { album ->
                val key = album.artist.lowercase()
                val added = album.dateAddedMs
                    ?: maxDateByAlbumKey[album.title.lowercase() to key]
                    ?: return@forEach
                put(key, maxOf(this[key] ?: added, added))
            }
        }
        val previousAlbums = previous.albums.associateBy { it.id }
        val albums = next.albums.map { album ->
            val albumKey = album.title.lowercase() to album.artist.lowercase()
            album.copy(
                dateAddedMs = album.dateAddedMs
                    ?: previousAlbums[album.id]?.dateAddedMs
                    ?: maxDateByAlbumKey[albumKey],
                genre = album.genre ?: previousAlbums[album.id]?.genre,
                mood = album.mood ?: previousAlbums[album.id]?.mood,
                style = album.style ?: previousAlbums[album.id]?.style,
                rating = album.rating ?: previousAlbums[album.id]?.rating,
                favorite = if (album.id.startsWith("plex:")) {
                    album.favorite
                } else {
                    album.favorite || previousAlbums[album.id]?.favorite == true
                },
                albumArtist = album.albumArtist ?: previousAlbums[album.id]?.albumArtist,
                description = album.description ?: previousAlbums[album.id]?.description,
                recordLabel = album.recordLabel ?: previousAlbums[album.id]?.recordLabel,
                releaseDate = album.releaseDate ?: previousAlbums[album.id]?.releaseDate,
            )
        }
        val previousArtists = previous.artists.associateBy { it.id }
        val artists = next.artists.map { artist ->
            artist.copy(
                dateAddedMs = artist.dateAddedMs
                    ?: previousArtists[artist.id]?.dateAddedMs
                    ?: maxDateByArtistTitle[artist.title.lowercase()],
                genre = artist.genre ?: previousArtists[artist.id]?.genre,
                mood = artist.mood ?: previousArtists[artist.id]?.mood,
                style = artist.style ?: previousArtists[artist.id]?.style,
                rating = artist.rating ?: previousArtists[artist.id]?.rating,
                favorite = if (artist.id.startsWith("plex:")) {
                    artist.favorite
                } else {
                    artist.favorite || previousArtists[artist.id]?.favorite == true
                },
            )
        }
        val previousPlaylists = previous.playlists.associateBy { it.id }
        val playlists = next.playlists.map { playlist ->
            playlist.copy(
                rating = playlist.rating ?: previousPlaylists[playlist.id]?.rating,
                favorite = playlist.favorite || previousPlaylists[playlist.id]?.favorite == true,
            )
        }
        return next.copy(artists = artists, albums = albums, playlists = playlists, tracksByParent = tracksByParent)
    }

    private fun preserveTrackDateAdded(existing: List<Track>, incoming: List<Track>): List<Track> {
        if (existing.isEmpty()) return incoming
        val existingById = existing.associateBy { it.id }
        return incoming.map { track ->
            val previous = existingById[track.id] ?: return@map track
            track.copy(
                dateAddedMs = previous.dateAddedMs ?: track.dateAddedMs,
                localUri = previous.localUri ?: track.localUri,
                localArtworkUri = previous.localArtworkUri ?: track.localArtworkUri,
                thumbUrl = track.thumbUrl ?: previous.thumbUrl?.takeUnless { it.isLocalArtworkUrl() },
                rating = track.rating ?: previous.rating,
            )
        }
    }

    private fun rememberPrependedPlaylistAdditions(playlistId: String, trackIds: List<String>) {
        val ids = trackIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return
        pendingPlaylistPrependedTrackIds[playlistId] = (ids + pendingPlaylistPrependedTrackIds[playlistId].orEmpty()).distinct()
    }

    private fun preservePrependedPlaylistAdditions(playlistId: String, tracks: List<Track>): List<Track> {
        val pendingIds = pendingPlaylistPrependedTrackIds[playlistId].orEmpty()
        if (pendingIds.isEmpty() || tracks.isEmpty()) return tracks
        return moveTracksToFrontById(tracks, pendingIds)
    }

    private fun moveTracksToFrontById(tracks: List<Track>, trackIds: List<String>): List<Track> {
        val ids = trackIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty() || tracks.isEmpty()) return tracks
        val idsSet = ids.toSet()
        val firstIndices = mutableMapOf<String, Int>()
        tracks.forEachIndexed { index, track ->
            if (track.id in idsSet && track.id !in firstIndices) {
                firstIndices[track.id] = index
            }
        }
        val front = ids.mapNotNull { id -> firstIndices[id]?.let { tracks[it] } }
        if (front.isEmpty()) return tracks
        val indicesToRemove = firstIndices.values.toSet()
        val remaining = tracks.filterIndexed { index, _ -> index !in indicesToRemove }
        return front + remaining
    }

    private fun moveTracksToFrontByPlaylistEntry(tracks: List<Track>, entries: List<Track>): List<Track> {
        val keys = entries.map { it.playlistEntryKey() }.filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty() || tracks.isEmpty()) return tracks
        val keysSet = keys.toSet()
        val firstIndices = mutableMapOf<String, Int>()
        tracks.forEachIndexed { index, track ->
            val key = track.playlistEntryKey()
            if (key in keysSet && key !in firstIndices) {
                firstIndices[key] = index
            }
        }
        val front = keys.mapNotNull { key -> firstIndices[key]?.let { tracks[it] } }
        if (front.isEmpty()) return tracks
        val indicesToRemove = firstIndices.values.toSet()
        val remaining = tracks.filterIndexed { index, _ -> index !in indicesToRemove }
        return front + remaining
    }

    private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
        if (from !in indices || to !in indices) return this
        val copy = toMutableList()
        val item = copy.removeAt(from)
        copy.add(to, item)
        return copy
    }

    private fun List<Track>.indexOfPlaylistEntry(track: Track): Int {
        track.playlistItemId?.let { playlistItemId ->
            val index = indexOfFirst { it.playlistItemId == playlistItemId }
            if (index >= 0) return index
        }
        return indexOfFirst { it.id == track.id }
    }

    private fun mergeTrackLists(existing: List<Track>, incoming: List<Track>): List<Track> {
        if (existing.isEmpty()) return incoming.distinctBy { it.id }
        if (incoming.isEmpty()) return existing
        val mergedById = LinkedHashMap<String, Track>()
        existing.forEach { track -> mergedById[track.id] = track }
        preserveTrackDateAdded(existing, incoming).forEach { track ->
            val previous = mergedById[track.id]
            mergedById[track.id] = when {
                previous == null -> track
                !previous.hasPlayableSource() && track.hasPlayableSource() -> track
                previous.hasPlayableSource() && !track.hasPlayableSource() -> previous
                else -> previous
            }
        }
        return mergedById.values.toList()
    }

    private fun mergeTrackParents(
        existing: Map<String, List<Track>>,
        incoming: Map<String, List<Track>>,
    ): Map<String, List<Track>> =
        incoming.entries.fold(existing) { acc, (parentId, tracks) ->
            acc + (parentId to mergeTrackLists(acc[parentId].orEmpty(), tracks))
        }

    private fun mergeDistinctPlaylists(
        existing: List<Playlist>,
        incoming: List<Playlist>,
    ): List<Playlist> =
        when {
            incoming.isEmpty() -> existing
            existing.isEmpty() -> incoming.distinctBy { it.id }
            else -> (existing + incoming).distinctBy { it.id }
        }

    private fun CatalogSnapshot.withPlaylistUserStateFrom(source: CatalogSnapshot): CatalogSnapshot {
        if (playlists.isEmpty()) return this
        val sourcePlaylists = source.playlists.associateBy { it.id }
        val favoriteOverrides = pendingPlaylistFavoriteOverrides.value
        if (sourcePlaylists.isEmpty() && favoriteOverrides.isEmpty()) return this
        val mergedPlaylists = playlists.map { playlist ->
            val sourcePlaylist = sourcePlaylists[playlist.id]
            playlist.copy(
                rating = playlist.rating ?: sourcePlaylist?.rating,
                favorite = favoriteOverrides[playlist.id] ?: (playlist.favorite || sourcePlaylist?.favorite == true),
            )
        }
        return copy(playlists = mergedPlaylists)
    }

    private suspend fun removeMissingLocalArtworkReferences(snapshot: CatalogSnapshot): CatalogSnapshot {
        val checked = mutableMapOf<String, Boolean>()
        suspend fun available(url: String?): Boolean {
            if (url.isNullOrBlank() || !url.isLocalArtworkUrl()) return true
            checked[url]?.let { return it }
            val exists = storage.readUriBytes(url) != null
            checked[url] = exists
            return exists
        }

        suspend fun clean(url: String?): String? =
            if (available(url)) url else null

        val artists = snapshot.artists.map { artist ->
            artist.copy(thumbUrl = clean(artist.thumbUrl))
        }
        val albums = snapshot.albums.map { album ->
            album.copy(thumbUrl = clean(album.thumbUrl))
        }
        val playlists = snapshot.playlists.map { playlist ->
            playlist.copy(thumbUrl = clean(playlist.thumbUrl))
        }
        val tracksByParent = snapshot.tracksByParent.mapValues { (_, tracks) ->
            tracks.map { track ->
                track.copy(
                    thumbUrl = clean(track.thumbUrl),
                    localArtworkUri = clean(track.localArtworkUri),
                )
            }
        }
        return snapshot.copy(
            artists = artists,
            albums = albums,
            playlists = playlists,
            tracksByParent = tracksByParent,
        )
    }

    private suspend fun loadJellyfinFullAlbums(
        remoteClient: JellyfinClient,
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        userId: String,
        remoteLabel: String,
    ): List<Album> = coroutineScope {
        val collected = mutableListOf<Album>()
        var totalAlbums: Int? = null
        var loadedEstimate = 0
        val progressMutex = Mutex()

        val progressJob = launch {
            while (isActive) {
                val loaded = progressMutex.withLock { loadedEstimate }
                updateAlbumLoadSyncProgress(loaded, totalAlbums, remoteLabel)
                delay(SyncProgressUpdateIntervalMs)
            }
        }

        remoteClient.albums(server, library, token, userId, fastSync = false) { page, total ->
            if (totalAlbums == null && total != null) totalAlbums = total
            if (page.isNotEmpty()) collected += page
            progressMutex.withLock { loadedEstimate += page.size }
        }

        progressJob.cancel()
        updateAlbumLoadSyncProgress(progressMutex.withLock { loadedEstimate }, totalAlbums, remoteLabel)
        collected
    }

    private fun updateAlbumLoadSyncProgress(loadedAlbums: Int, totalAlbums: Int?, remoteLabel: String) {
        val progress = totalAlbums?.takeIf { it > 0 }?.let { (loadedAlbums.toFloat() / it).coerceIn(0f, 1f) }
        val detail = totalAlbums?.let { "${formatSyncCount(loadedAlbums)} / ${formatSyncCount(it)}" }
        publishCatalogSyncState(
            mutableCatalogSyncState.value.copy(
                phase = CatalogSyncPhase.LoadingLibrary,
                message = "Loading $remoteLabel albums…",
                detail = detail,
                loadedAlbums = loadedAlbums,
                loadedTracks = mutableCatalog.value.tracksByParent.values.sumOf { it.size },
                progress = progress,
                blocking = false,
            ),
        )
    }

    private suspend fun indexJellyfinFullTracks(
        remoteClient: JellyfinClient,
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        userId: String,
        remotePrefix: String,
        remoteLabel: String,
        albums: List<Album>,
        preserveTracksFrom: Map<String, List<Track>>,
        onTrackBatchPersisted: (List<Track>) -> Unit = {},
    ) = coroutineScope {
        val albumsById = albums.associateBy { it.id }
        var totalTracks: Int? = null
        var loadedEstimate = 0
        val progressMutex = Mutex()

        val progressJob = launch {
            while (isActive) {
                val loaded = progressMutex.withLock { loadedEstimate }
                updateTrackIndexSyncProgress(loaded, totalTracks)
                delay(SyncProgressUpdateIntervalMs)
            }
        }

        remoteClient.tracks(server, library, token, userId, includeMediaDetails = false) { page, total ->
            if (totalTracks == null && total != null) totalTracks = total
            if (page.isEmpty()) return@tracks
            val enrichedPage = page.map { track ->
                val album = track.parentAlbumId?.let(albumsById::get)
                if (album == null) track else track.copy(
                    album = track.album.takeUnless { it == "Unknown album" } ?: album.title,
                    artist = track.artist.takeUnless { it == "Unknown artist" } ?: album.artist,
                    thumbUrl = track.thumbUrl ?: album.thumbUrl,
                )
            }
            progressMutex.withLock { loadedEstimate += enrichedPage.size }
            val trackBatch = prefixRemoteTracks(remotePrefix, enrichedPage, albums)
            if (trackBatch.isNotEmpty()) {
                enqueueCatalogDbWrite { persistTrackBatch(trackBatch) }
                onTrackBatchPersisted(trackBatch)
            }
        }

        progressJob.cancel()
        updateTrackIndexSyncProgress(progressMutex.withLock { loadedEstimate }, totalTracks)
        awaitCatalogDbWrites()
        hydrateInMemoryTracksFromDatabase(preserveFrom = preserveTracksFrom)
        catalogMergeMutex.withLock {
            mutableCatalog.value = enrichJellyfinCatalogArtwork(mutableCatalog.value)
        }
        PhoebeLog.d("CatalogRepository") {
            "indexJellyfinFullTracks complete → $loadedEstimate songs indexed for $remoteLabel"
        }
    }

    private suspend fun indexPlexTrackPages(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        preserveTracksFrom: Map<String, List<Track>> = emptyMap(),
        trace: CatalogSyncTrace? = null,
    ): Boolean = coroutineScope {
        val firstPage = plexTrackIndexPageOrNull(
            server = server,
            library = library,
            token = token,
            start = 0,
            size = TrackIndexPageSize,
        ) ?: return@coroutineScope false
        if (firstPage.tracks.isEmpty()) return@coroutineScope false

        val totalTracks = firstPage.totalSize
        val pendingBatch = mutableListOf<Track>()
        val batchMutex = Mutex()
        val progressMutex = Mutex()
        val incompleteMutex = Mutex()
        val indexedParentsMutex = Mutex()
        val indexedTrackIdsByParent = linkedMapOf<String, LinkedHashSet<String>>()
        var loadedTrackEstimate = firstPage.tracks.size
        var incomplete = false

        suspend fun rememberIndexedParents(tracks: List<Track>) {
            val snapshot = mutableCatalog.value
            val parentTrackIds = tracks.mapNotNull { track ->
                resolveIndexedTrackParentId(track, snapshot)?.let { parentId -> parentId to track.id }
            }
            if (parentTrackIds.isEmpty()) return
            indexedParentsMutex.withLock {
                parentTrackIds.forEach { (parentId, trackId) ->
                    indexedTrackIdsByParent.getOrPut(parentId) { linkedSetOf() }.add(trackId)
                }
            }
        }

        suspend fun flushBatch(publishToMemory: Boolean) {
            val batch = batchMutex.withLock {
                if (pendingBatch.isEmpty()) return
                pendingBatch.toList().also { pendingBatch.clear() }
            }
            val prefixedBatch = batch.map { it.withPlexPrefix() }
            if (publishToMemory) {
                publishIndexedPlexTracks(batch)
            }
            rememberIndexedParents(prefixedBatch)
            runCatalogDbWrite { persistTrackBatch(prefixedBatch) }
        }

        suspend fun enqueueTracks(tracks: List<Track>) {
            val shouldFlush = batchMutex.withLock {
                pendingBatch.addAll(tracks)
                pendingBatch.size >= TrackIndexPageSize
            }
            if (shouldFlush) flushBatch(publishToMemory = false)
        }

        suspend fun markIncomplete(start: Int, reason: String) {
            incompleteMutex.withLock { incomplete = true }
            PhoebeLog.d("CatalogRepository") {
                "Plex track index incomplete at start=$start: $reason"
            }
        }

        enqueueTracks(firstPage.tracks)
        updateTrackIndexSyncProgress(firstPage.tracks.size, firstPage.totalSize)
        trace?.mark(
            "plex.indexTrackPages firstPage",
            CatalogSyncStepKind.Network,
            "${firstPage.tracks.size} tracks, total=${firstPage.totalSize ?: "unknown"}",
        )

        val pageSize = TrackIndexPageSize
        val maxOffset = when (val total = totalTracks) {
            null -> MaxTrackIndexPages * pageSize
            else -> total.coerceAtMost(MaxTrackIndexPages * pageSize)
        }
        val pageQueue = Channel<Int>(capacity = Channel.UNLIMITED)
        var nextOffset = pageSize
        while (nextOffset < maxOffset) {
            pageQueue.send(nextOffset)
            nextOffset += pageSize
        }
        pageQueue.close()

        val progressJob = launch {
            while (isActive) {
                val loaded = progressMutex.withLock { loadedTrackEstimate }
                updateTrackIndexSyncProgress(loaded, totalTracks)
                delay(SyncProgressUpdateIntervalMs)
            }
        }
        val publisherJob = launch {
            while (isActive) {
                delay(SyncProgressUpdateIntervalMs)
                flushBatch(publishToMemory = false)
            }
        }

        val parallelism = catalogTrackIndexParallelism().coerceAtLeast(1)
        val workers = List(parallelism) {
            launch {
                for (offset in pageQueue) {
                    val page = plexTrackIndexPageOrNull(
                        server = server,
                        library = library,
                        token = token,
                        start = offset,
                        size = pageSize,
                    )
                    if (page == null) {
                        markIncomplete(offset, "page unavailable")
                        pageQueue.cancel()
                        break
                    }
                    if (page.tracks.isEmpty()) {
                        if (totalTracks != null && offset < maxOffset) {
                            markIncomplete(offset, "empty page before expected total")
                        }
                        pageQueue.cancel()
                        break
                    }
                    enqueueTracks(page.tracks)
                    progressMutex.withLock {
                        loadedTrackEstimate += page.tracks.size
                    }
                }
            }
        }
        workers.joinAll()
        publisherJob.cancel()
        progressJob.cancel()
        flushBatch(publishToMemory = true)
        val complete = !incompleteMutex.withLock { incomplete }
        if (complete) {
            val indexedParents = indexedParentsMutex.withLock {
                indexedTrackIdsByParent.mapValues { (_, trackIds) -> trackIds.toList() }
            }
            trace?.disk("replaceIndexedPlexTrackParents") {
                runCatalogDbWrite { replaceIndexedTrackParents(indexedParents) }
            } ?: runCatalogDbWrite { replaceIndexedTrackParents(indexedParents) }
        }
        trace?.disk("hydrateInMemoryTracksFromDatabase") {
            hydrateInMemoryTracksFromDatabase(preserveFrom = preserveTracksFrom)
        } ?: hydrateInMemoryTracksFromDatabase(preserveFrom = preserveTracksFrom)
        complete
    }

    private suspend fun plexTrackIndexPageOrNull(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        start: Int,
        size: Int,
    ): PlexTrackPage? {
        val timeoutMs = plexTrackIndexPageTimeoutMs
        val page = withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeoutMs) {
                runCatching {
                    plexClient.libraryTracksPage(
                        server = server,
                        library = library,
                        token = token,
                        start = start,
                        size = size,
                        timeoutMs = timeoutMs,
                    )
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    PhoebeLog.d("CatalogRepository") {
                        "Plex track page start=$start failed: ${error.message}"
                    }
                }.getOrNull()
            }
        }
        if (page == null) {
            currentCoroutineContext().ensureActive()
            PhoebeLog.d("CatalogRepository") {
                "Plex track page start=$start timed out after ${timeoutMs}ms"
            }
        }
        return page
    }

    private suspend fun tracksToPreserveDuringRefresh(previous: CatalogSnapshot): Map<String, List<Track>> {
        val sanitizedPrevious = previous.withoutInactiveLocalFolderCatalog(activeLocalFolderIds())
        if (sanitizedPrevious.tracksByParent.isNotEmpty()) return sanitizedPrevious.tracksByParent
        return readTracksFromDatabase().tracksByParent
    }

    private suspend fun hydrateCachedTracksAfterShellRestore(restoredShell: CatalogSnapshot) {
        val tracks = readTracksFromDatabase()
        if (tracks.tracksByParent.isEmpty()) return
        catalogMergeMutex.withLock {
            val cur = mutableCatalog.value
            if (!cur.hasSameCatalogShell(restoredShell)) return
            val restoredDownloads = tracks.downloads.ifEmpty { cur.downloads }
            replaceDownloadSnapshot(restoredDownloads, syncCatalog = false)
            mutableCatalog.value = withSmartPlaylists(
                cur.copy(
                    tracksByParent = tracks.tracksByParent,
                    popularTracksByLibrary = tracks.popularTracksByLibrary,
                    downloads = restoredDownloads,
                ),
            )
        }
    }

    private fun CatalogSnapshot.hasRestorableCatalogContent(): Boolean =
        artists.isNotEmpty() ||
            albums.isNotEmpty() ||
            playlists.any { !it.isLikedSongsPlaylist() }

    private suspend fun hydrateInMemoryTracksFromDatabase(
        preserveFrom: Map<String, List<Track>> = emptyMap(),
    ) {
        val tracks = readTracksFromDatabase()
        if (tracks.tracksByParent.isEmpty()) return
        catalogMergeMutex.withLock {
            val cur = mutableCatalog.value
            val hydrated = tracks.tracksByParent.mapValues { (parentId, incoming) ->
                preserveTrackDateAdded(preserveFrom[parentId].orEmpty(), incoming)
            }
            val mergedTracksByParent = buildMap {
                cur.tracksByParent.forEach { (parentId, existing) ->
                    put(parentId, hydrated[parentId] ?: existing)
                }
                hydrated.forEach { (parentId, incoming) ->
                    if (parentId !in cur.tracksByParent) put(parentId, incoming)
                }
            }
            val restoredDownloads = tracks.downloads.ifEmpty { cur.downloads }
            replaceDownloadSnapshot(restoredDownloads, syncCatalog = false)
            mutableCatalog.value = withSmartPlaylists(
                cur.copy(
                    tracksByParent = mergedTracksByParent,
                    popularTracksByLibrary = cur.popularTracksByLibrary + tracks.popularTracksByLibrary,
                    downloads = restoredDownloads,
                ),
            )
        }
    }

    private fun CatalogSnapshot.hasSameCatalogShell(other: CatalogSnapshot): Boolean =
        artists.map { it.id } == other.artists.map { it.id } &&
            albums.map { it.id } == other.albums.map { it.id } &&
            playlists.map { it.id } == other.playlists.map { it.id }

    private fun updateTrackIndexSyncProgress(loadedTracks: Int, totalTracks: Int? = null) {
        val total = totalTracks ?: mutableCatalogSyncState.value.totalTracks
        val progress = total?.takeIf { it > 0 }?.let { (loadedTracks.toFloat() / it).coerceIn(0f, 1f) }
        val detail = total?.let { "${formatSyncCount(loadedTracks)} / ${formatSyncCount(it)}" }
        publishCatalogSyncState(
            mutableCatalogSyncState.value.copy(
                phase = CatalogSyncPhase.LoadingSongs,
                message = "Indexing songs…",
                detail = detail,
                loadedTracks = loadedTracks,
                totalTracks = total,
                loadedAlbums = mutableCatalog.value.albums.size,
                progress = progress,
                blocking = false,
            ),
        )
    }

    private fun updateNavidromeAlbumIndexProgress(loadedAlbumPages: Int, totalAlbumPages: Int) {
        if (totalAlbumPages <= 0) return
        val progress = (loadedAlbumPages.toFloat() / totalAlbumPages).coerceIn(0f, 1f)
        publishCatalogSyncState(
            mutableCatalogSyncState.value.copy(
                phase = CatalogSyncPhase.LoadingSongs,
                message = "Indexing Subsonic songs…",
                detail = "$loadedAlbumPages / $totalAlbumPages albums",
                loadedAlbums = mutableCatalog.value.albums.size,
                loadedTracks = mutableCatalog.value.tracksByParent.values.sumOf { it.size },
                progress = progress,
                blocking = false,
            ),
        )
    }

    private fun publishCatalogSyncState(state: CatalogSyncState, force: Boolean = false) {
        if (force) {
            if (!state.isActive) catalogSyncUiThrottle.reset()
            catalogSyncUiThrottle.markEmitted(state)
            mutableCatalogSyncState.value = state
            return
        }
        if (!catalogSyncUiThrottle.shouldEmit(state)) return
        catalogSyncUiThrottle.markEmitted(state)
        mutableCatalogSyncState.value = state
    }

    private fun formatSyncCount(count: Int): String =
        when {
            count >= 1_000_000 -> "${count / 1_000_000}M"
            count >= 10_000 -> "${count / 1_000}k"
            else -> count.toString()
        }

    private suspend fun publishIndexedPlexTracks(rawTracks: List<Track>) {
        val tracksByAlbum = rawTracks
            .map { it.withPlexPrefix() }
            .groupBy { track -> resolveIndexedTrackParentId(track, mutableCatalog.value) }
            .filterKeys { it != null }
            .mapKeys { it.key!! }
        if (tracksByAlbum.isEmpty()) return
        catalogMergeMutex.withLock {
            val cur = mutableCatalog.value
            var nextParents = cur.tracksByParent
            tracksByAlbum.forEach { (parentId, tracks) ->
                val existing = nextParents[parentId].orEmpty()
                nextParents = nextParents + (parentId to mergeTrackLists(existing, tracks))
            }
            mutableCatalog.value = withSmartPlaylists(cur.copy(tracksByParent = nextParents))
        }
    }

    private fun resolveIndexedTrackParentId(track: Track, snapshot: CatalogSnapshot): String? {
        track.parentAlbumId?.takeIf { it.isNotBlank() }?.let { raw ->
            if (':' in raw) return raw
            val prefix = snapshot.remoteCatalogIdPrefix() ?: "plex"
            return "$prefix:$raw"
        }
        val album = snapshot.albums.firstOrNull {
            it.title.equals(track.album, ignoreCase = true) &&
                it.artist.equals(track.artist, ignoreCase = true)
        } ?: snapshot.albums.firstOrNull {
            it.title.equals(track.album, ignoreCase = true)
        }
        if (album != null) return album.id
        val prefix = track.id.substringBefore(':').takeIf { it.isNotBlank() && ':' in track.id }
            ?: snapshot.remoteCatalogIdPrefix()
            ?: return null
        if (track.album.isNotBlank()) {
            return "$prefix:album:${track.album.lowercase()}"
        }
        return "$prefix:play-history"
    }

    /**
     * Always refetches a playlist's track list from Plex (ignoring any cached entry) and
     * publishes the result. Used by [refreshAggregated] to reconcile playlists that grew
     * externally, and by [tracksForPlaylist] when the cache is empty.
     */
    private suspend fun refetchPlaylistTracksFromPlex(
        session: PlexSession?,
        playlist: Playlist,
        showRefreshing: Boolean = true,
    ) {
        val fetch: suspend () -> Unit = fetch@{
            val providerPrefix = session?.providerType?.catalogPrefix
            if (session != null && providerPrefix != null && playlist.id.startsWith("$providerPrefix:") && !session.isPlex()) {
                val server = session.selectedServer ?: return@fetch
                val token = session.token
                val adapter = providerRegistry.adapterFor(session)
                if (adapter != null && !session.isEmbyFamily()) {
                    val existingTracks = mutableCatalog.value.tracksByParent[playlist.id].orEmpty()
                    val tracks = adapter.playlistTracks(session, playlist.copy(id = playlist.id.removePrefix("$providerPrefix:")))
                        .map { it.withProviderPrefix(providerPrefix) }
                        .let { preserveTrackDateAdded(existingTracks, it) }
                        .let { preservePrependedPlaylistAdditions(playlist.id, it) }
                    val next = mutableCatalog.value.copy(
                        tracksByParent = mutableCatalog.value.tracksByParent + (playlist.id to tracks),
                        playlists = mutableCatalog.value.playlists.map { p ->
                            if (p.id == playlist.id) {
                                p.copy(
                                    trackCount = tracks.size,
                                    thumbUrl = p.thumbUrl ?: tracks.firstNotNullOfOrNull { it.thumbUrl },
                                )
                            } else p
                        },
                    )
                    publish(next, persist = false)
                    persistPlaylistTracksAsync(next, playlist.id)
                    return@fetch
                }
                val userId = session.userId ?: return@fetch
                val remotePlaylist = playlist.copy(
                    id = if (playlist.id == JellyfinClient.JellyfinLikedSongsPlaylistId) {
                        playlist.id
                    } else {
                        playlist.id.removePrefix("$providerPrefix:")
                    },
                )
                val remoteClient = if (session.providerType.catalogPrefix == "emby") embyClient else jellyfinClient
                val existingTracks = mutableCatalog.value.tracksByParent[playlist.id].orEmpty()
                val tracks = remoteClient.playlistTracks(server, remotePlaylist, token, userId)
                    .map { it.withProviderPrefix(providerPrefix) }
                    .let { preserveTrackDateAdded(existingTracks, it) }
                    .let { preservePrependedPlaylistAdditions(playlist.id, it) }
                val next = mutableCatalog.value.copy(
                    tracksByParent = mutableCatalog.value.tracksByParent + (playlist.id to tracks),
                    playlists = mutableCatalog.value.playlists.map { p ->
                        if (p.id == playlist.id) {
                            p.copy(
                                trackCount = tracks.size,
                                thumbUrl = p.thumbUrl ?: tracks.firstNotNullOfOrNull { it.thumbUrl },
                            )
                        } else p
                    },
                )
                publish(next, persist = false)
                persistPlaylistTracksAsync(next, playlist.id)
                return@fetch
            }
            val rating = plexRatingKey(playlist.id) ?: return@fetch
            val server = session?.selectedServer ?: return@fetch
            val token = session.serverAuthToken() ?: return@fetch
            val existingTracks = mutableCatalog.value.tracksByParent[playlist.id].orEmpty()
            val tracks = plexClient.playlistTracks(server, playlist.copy(id = rating), token)
                .map { it.withPlexPrefix() }
                .let { preserveTrackDateAdded(existingTracks, it) }
                .let { preservePrependedPlaylistAdditions(playlist.id, it) }
            val next = mutableCatalog.value.copy(
                tracksByParent = mutableCatalog.value.tracksByParent + (playlist.id to tracks),
                playlists = mutableCatalog.value.playlists.map { p ->
                        if (p.id == playlist.id) {
                            p.copy(
                                trackCount = tracks.size,
                                thumbUrl = p.thumbUrl ?: tracks.firstNotNullOfOrNull { it.thumbUrl },
                            )
                        } else p
                    },
            )
            publish(next, persist = false)
            persistPlaylistTracksAsync(next, playlist.id)
        }
        if (showRefreshing) {
            withCatalogRefreshing { fetch() }
        } else {
            fetch()
        }
    }

    suspend fun warmPlaylistTracks(session: PlexSession?) {
        val playlists = mutableCatalog.value.playlists
            .filter { playlist ->
                playlist.remoteProviderPrefix() != null &&
                    playlist.trackCount > 0 &&
                    mutableCatalog.value.tracksByParent[playlist.id].isNullOrEmpty()
            }
            .sortedByDescending { if (it.favorite) 1 else 0 }
        warmPlaylistTracksParallel(session, playlists, updateSyncProgress = false)
    }

    private suspend fun warmPlaylistTracksParallel(
        session: PlexSession?,
        playlists: List<Playlist>,
        updateSyncProgress: Boolean = false,
        trace: CatalogSyncTrace? = null,
    ) {
        if (playlists.isEmpty()) return
        trace?.mark(
            "warmPlaylistTracksParallel start",
            CatalogSyncStepKind.Network,
            "${playlists.size} playlists",
        )
        PhoebeLog.d("CatalogRepository") { "warming ${playlists.size} playlist track lists" }
        val total = playlists.size
        var warmedCount = 0
        val warmedMutex = Mutex()
        if (updateSyncProgress) {
            mutableCatalogSyncState.value = mutableCatalogSyncState.value.copy(
                phase = CatalogSyncPhase.RefreshingPlaylists,
                message = "Loading playlist tracks…",
                totalPlaylists = total,
                warmedPlaylists = 0,
                detail = if (total == 1) {
                    playlists.first().title
                } else {
                    "Starting with ${playlists.first().title}"
                },
            )
        }
        try {
            coroutineScope {
                val queue = Channel<Playlist>(capacity = Channel.UNLIMITED)
                playlists.forEach { queue.send(it) }
                queue.close()
                List(PlaylistWarmParallelism) {
                    launch {
                        for (playlist in queue) {
                            if (updateSyncProgress) {
                                val nextIndex = warmedMutex.withLock { warmedCount + 1 }
                                publishCatalogSyncState(
                                    mutableCatalogSyncState.value.copy(
                                        message = "Loading playlist tracks…",
                                        detail = "${playlist.title} · $nextIndex of $total",
                                        progress = (nextIndex - 1).toFloat() / total.coerceAtLeast(1),
                                    ),
                                )
                            }
                            val stepStartMs = currentTimeMs()
                            runCatching {
                                refetchPlaylistTracksFromPlex(session, playlist, showRefreshing = false)
                            }.onFailure { error ->
                                if (error is CancellationException) throw error
                                trace?.markFailed(
                                    "plex.playlistTracks/${playlist.title}",
                                    CatalogSyncStepKind.Network,
                                    stepStartMs,
                                    error,
                                )
                                PhoebeLog.d("CatalogRepository") {
                                    "playlist warm failed for '${playlist.title}': ${error.message}"
                                }
                            }.onSuccess {
                                trace?.markDone(
                                    "plex.playlistTracks/${playlist.title}",
                                    CatalogSyncStepKind.Network,
                                    stepStartMs,
                                    "${playlist.trackCount} tracks",
                                )
                            }
                            if (updateSyncProgress) {
                                val warmed = warmedMutex.withLock {
                                    warmedCount++
                                    warmedCount
                                }
                                publishCatalogSyncState(
                                    mutableCatalogSyncState.value.copy(
                                        warmedPlaylists = warmed,
                                        detail = "${playlist.title} · $warmed of $total",
                                        progress = warmed.toFloat() / total.coerceAtLeast(1),
                                    ),
                                )
                            }
                        }
                    }
                }.joinAll()
            }
        } finally {
            if (updateSyncProgress &&
                mutableCatalogSyncState.value.phase == CatalogSyncPhase.RefreshingPlaylists
            ) {
                // Foreground stale-playlist refresh during sync; caller normally sets Complete next.
                // If we are the only updater left, do not leave the UI stuck on this phase.
                if (!mutableCatalogRefreshing.value) {
                    mutableCatalogSyncState.value = CatalogSyncState()
                }
            }
        }
    }

    suspend fun ensureAlbumDetails(session: PlexSession?, album: Album) {
        val server = session?.selectedServer ?: return
        val token = session.serverAuthToken() ?: return
        if (!session.isPlex()) return

        val rating = plexRatingKey(album.id) ?: return

        var shouldFetch = false
        catalogMergeMutex.withLock {
            val currentAlbum = mutableCatalog.value.albums.firstOrNull { it.id == album.id } ?: album
            val needsAlbumDetails =
                currentAlbum.description.isNullOrBlank() ||
                    currentAlbum.mood.isNullOrBlank() ||
                    currentAlbum.style.isNullOrBlank() ||
                    currentAlbum.recordLabel.isNullOrBlank()
            if (!fetchedAlbumDetailsIds.contains(album.id) && needsAlbumDetails) {
                fetchedAlbumDetailsIds.add(album.id)
                shouldFetch = true
            }
        }
        if (!shouldFetch) return

        val details = try {
            plexClient.albumDetails(server, rating, token)
        } catch (error: Throwable) {
            catalogMergeMutex.withLock {
                fetchedAlbumDetailsIds.remove(album.id)
            }
            if (error is CancellationException) throw error
            return
        } ?: return

        catalogMergeMutex.withLock {
            val current = mutableCatalog.value
            val existingAlbumIndex = current.albums.indexOfFirst { it.id == album.id }
            if (existingAlbumIndex >= 0) {
                val prefixedDetail = current.albums[existingAlbumIndex].mergeAlbumDetails(details, album.id)
                val updatedAlbums = current.albums.toMutableList().apply {
                    this[existingAlbumIndex] = prefixedDetail
                }
                mutableCatalog.value = current.copy(
                    albums = updatedAlbums
                )
            }
        }

        runCatalogDbWrite {
            database.catalogQueries.updateAlbumMetadata(
                mood = details.mood,
                style = details.style,
                description = details.description,
                recordLabel = details.recordLabel,
                releaseDate = details.releaseDate,
                id = album.id,
            )
        }
    }

    suspend fun tracksForAlbum(session: PlexSession?, album: Album): List<Track> {
        val existing = mutableCatalog.value.tracksByParent[album.id]
        if (!existing.isNullOrEmpty() && existing.canUseCachedTracksForSession(session)) return existing
        readTracksForParentFromDatabase(album.id)?.let { cached ->
            if (cached.isNotEmpty() && cached.canUseCachedTracksForSession(session)) {
                publish(
                    mutableCatalog.value.copy(
                        tracksByParent = mutableCatalog.value.tracksByParent + (album.id to cached),
                    ),
                    persist = false,
                )
                return cached
            }
        }
        val providerPrefix = session?.providerType?.catalogPrefix
        if (session != null && providerPrefix != null && album.id.startsWith("$providerPrefix:") && !session.isPlex()) {
            val server = session.selectedServer ?: return emptyList()
            val token = session.token
            pushTracksLoading(album.id)
            return try {
                val adapter = providerRegistry.adapterFor(session)
                val tracks = if (adapter != null && !session.isEmbyFamily()) {
                    adapter.albumTracks(session, album.copy(id = album.id.removePrefix("$providerPrefix:")))
                } else {
                    val userId = session.userId ?: return emptyList()
                    val remoteClient = if (session.providerType.catalogPrefix == "emby") embyClient else jellyfinClient
                    remoteClient.albumTracks(server, album.copy(id = album.id.removePrefix("$providerPrefix:")), token, userId)
                }
                    .map { it.withProviderPrefix(providerPrefix) }
                    .let { preserveTrackDateAdded(mutableCatalog.value.tracksByParent[album.id].orEmpty(), it) }
                publish(
                    mutableCatalog.value.copy(
                        tracksByParent = mutableCatalog.value.tracksByParent + (album.id to tracks),
                    ),
                    persist = false,
                )
                persistCurrentTrackParentsWithoutClearingCatalog()
                mutableCatalog.value.tracksByParent[album.id].orEmpty()
            } finally {
                popTracksLoading(album.id)
            }
        }
        val rating = plexRatingKey(album.id) ?: return mutableCatalog.value.tracksByParent[album.id].orEmpty()
        val server = session?.selectedServer ?: return emptyList()
        val token = session.serverAuthToken() ?: return emptyList()
        pushTracksLoading(album.id)
        return try {
            coroutineScope {
                val tracksDeferred = async {
                    plexClient.children(server, rating, token)
                        .map { it.withPlexPrefix() }
                }
                val detailsDeferred = async {
                    try {
                        plexClient.albumDetails(server, rating, token)
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        null
                    }
                }

                val tracks = tracksDeferred.await()
                val details = detailsDeferred.await()

                val tracksResolved = preserveTrackDateAdded(mutableCatalog.value.tracksByParent[album.id].orEmpty(), tracks)

                catalogMergeMutex.withLock {
                    if (details != null) {
                        fetchedAlbumDetailsIds.add(album.id)
                    }
                    val current = mutableCatalog.value
                    val existingAlbumIndex = current.albums.indexOfFirst { it.id == album.id }
                    val updatedAlbums = if (details != null && existingAlbumIndex >= 0) {
                        val prefixedDetail = current.albums[existingAlbumIndex].mergeAlbumDetails(details, album.id)
                        current.albums.toMutableList().apply {
                            this[existingAlbumIndex] = prefixedDetail
                        }
                    } else {
                        current.albums
                    }

                    mutableCatalog.value = current.copy(
                        tracksByParent = current.tracksByParent + (album.id to tracksResolved),
                        albums = updatedAlbums,
                    )
                }

                if (details != null) {
                    runCatalogDbWrite {
                        database.catalogQueries.updateAlbumMetadata(
                            mood = details.mood,
                            style = details.style,
                            description = details.description,
                            recordLabel = details.recordLabel,
                            releaseDate = details.releaseDate,
                            id = album.id,
                        )
                    }
                }

                persistCurrentTrackParentsWithoutClearingCatalog()
                mutableCatalog.value.tracksByParent[album.id].orEmpty()
            }
        } finally {
            popTracksLoading(album.id)
        }
    }

    /**
     * Fetches track listings from Plex for every album by this artist that is not already loaded.
     * Called when opening the artist detail screen.
     */
    suspend fun ensureTracksForArtistAlbums(session: PlexSession?, artistTitle: String) {
        val server = session?.selectedServer ?: return
        val token = session.serverAuthToken() ?: return
        
        if (session.isEmbyFamily()) {
            val userId = session.userId ?: return
            val library = session.selectedLibrary ?: return
            val remoteClient = if (session.providerType.catalogPrefix == "emby") embyClient else jellyfinClient
            val providerPrefix = session.providerType.catalogPrefix

            val artist = mutableCatalog.value.artists.find { it.title.equals(artistTitle, ignoreCase = true) } ?: return
            val bareArtistId = artist.id.removePrefix("$providerPrefix:")
            val albums = if (bareArtistId.startsWith("album-artist-")) {
                catalogAlbumsForArtist(mutableCatalog.value, artistTitle)
            } else {
                runCatching {
                    remoteClient.albumsForArtist(server, library, token, userId, bareArtistId)
                        .map { it.withProviderPrefix(providerPrefix) }
                }.getOrElse { error ->
                    PhoebeLog.d("CatalogRepository") {
                        "albumsForArtist failed for '$bareArtistId': ${error.message}"
                    }
                    emptyList()
                }.ifEmpty {
                    catalogAlbumsForArtist(mutableCatalog.value, artistTitle)
                }
            }
            val albumsToFetch = albums.filter { album ->
                mutableCatalog.value.tracksByParent[album.id].isNullOrEmpty()
            }
            if (albumsToFetch.isEmpty()) return

            val existingAlbums = mutableCatalog.value.albums
            val newAlbums = albums.filter { a -> existingAlbums.none { it.id == a.id } }
            if (newAlbums.isNotEmpty()) {
                catalogMergeMutex.withLock {
                    val cur = mutableCatalog.value
                    publish(cur.copy(albums = cur.albums + newAlbums), persist = false)
                }
            }

            coroutineScope {
                albumsToFetch.map { album ->
                    async {
                        pushTracksLoading(album.id)
                        try {
                            runCatching {
                                val tracks = remoteClient.albumTracks(
                                    server,
                                    album.copy(id = album.id.removePrefix("$providerPrefix:")),
                                    token,
                                    userId,
                                ).map { it.withProviderPrefix(providerPrefix) }
                                catalogMergeMutex.withLock {
                                    val cur = mutableCatalog.value
                                    publish(
                                        cur.copy(tracksByParent = cur.tracksByParent + (album.id to tracks)),
                                        persist = false,
                                    )
                                }
                            }.onFailure { error ->
                                PhoebeLog.d("CatalogRepository") {
                                    "album track fetch failed for '${album.title}': ${error.message}"
                                }
                            }
                        } finally {
                            popTracksLoading(album.id)
                        }
                    }
                }.awaitAll()
            }
            publish(mutableCatalog.value, persist = true)
            return
        }

        val albums = catalogAlbumsForArtist(mutableCatalog.value, artistTitle)
            .filter { plexRatingKey(it.id) != null }
        val albumsToFetch = albums.filter { album ->
            mutableCatalog.value.tracksByParent[album.id].isNullOrEmpty()
        }
        if (albumsToFetch.isEmpty()) return

        coroutineScope {
            albumsToFetch.map { album ->
                async {
                    pushTracksLoading(album.id)
                    try {
                        runCatching {
                            val rating = plexRatingKey(album.id) ?: return@runCatching
                            val snap = mutableCatalog.value
                            val existing = snap.tracksByParent[album.id]
                            if (!existing.isNullOrEmpty()) return@runCatching
                            val tracks = plexClient.children(server, rating, token)
                                .map { it.withPlexPrefix() }
                                .let { preserveTrackDateAdded(mutableCatalog.value.tracksByParent[album.id].orEmpty(), it) }
                            catalogMergeMutex.withLock {
                                val cur = mutableCatalog.value
                                publish(
                                    cur.copy(tracksByParent = cur.tracksByParent + (album.id to tracks)),
                                    persist = false,
                                )
                            }
                        }.onFailure { e ->
                            PhoebeLog.d("CatalogRepository") { "album track fetch failed for '${album.title}': ${e.message}" }
                        }
                    } finally {
                        popTracksLoading(album.id)
                    }
                }
            }.awaitAll()
        }
        persistCurrentTrackParentsWithoutClearingCatalog()
    }

    suspend fun ensurePopularTracksForArtist(session: PlexSession?, artist: Artist): List<Track> {
        mutableCatalog.value.popularTracksByArtist[artist.id]?.let { return it }
        val plexSession = session?.takeIf { it.isPlex() } ?: return emptyList()
        val ratingKey = plexRatingKey(artist.id) ?: return emptyList()
        val server = plexSession.selectedServer ?: return emptyList()
        val library = plexSession.selectedLibrary ?: return emptyList()
        val token = plexSession.serverAuthToken() ?: return emptyList()
        val tracks = plexClient.popularTracksForArtist(
            server = server,
            library = library,
            ratingKey = ratingKey,
            token = token,
            limit = ArtistPopularTrackLimit,
        ).map { it.withPlexPrefix() }
        publishIndexedPlexTracks(tracks)
        catalogMergeMutex.withLock {
            val cur = mutableCatalog.value
            if (cur.popularTracksByArtist[artist.id] == null) {
                mutableCatalog.value = cur.copy(
                    popularTracksByArtist = cur.popularTracksByArtist + (artist.id to tracks),
                )
            }
        }
        if (tracks.isNotEmpty()) {
            runCatalogDbWrite { persistTrackBatch(tracks) }
        }
        return tracks
    }

    suspend fun cachedPopularTracksForLibrary(session: PlexSession?): List<Track> {
        val libraryKey = session.libraryPopularTrackCacheKey() ?: return emptyList()
        mutableCatalog.value.popularTracksByLibrary[libraryKey]
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val tracks = readPopularTracksForLibraryFromDatabase(libraryKey)
        if (tracks.isNotEmpty()) {
            publishLibraryPopularTracks(libraryKey, tracks)
        }
        return tracks
    }

    suspend fun popularSongsForLibrary(
        session: PlexSession?,
        limit: Int = LibraryPopularSongLimit,
    ): List<Track> {
        val plexSession = session?.takeIf { it.isPlex() } ?: return emptyList()
        val server = plexSession.selectedServer ?: return emptyList()
        val library = plexSession.selectedLibrary ?: return emptyList()
        val token = plexSession.serverAuthToken() ?: return emptyList()
        if (limit <= 0) return emptyList()
        val tracks = plexClient.popularTracksForLibrary(
            server = server,
            library = library,
            token = token,
            limit = limit,
        )
            .map { it.withPlexPrefix() }
            .distinctBy { it.id }
        if (tracks.isNotEmpty()) {
            publishIndexedPlexTracks(tracks)
            runCatalogDbWrite { persistTrackBatch(tracks) }
        }
        return tracks
    }

    suspend fun popularTracksForLibrary(
        session: PlexSession?,
        tracksPerArtist: Int = LibraryPopularTracksPerArtist,
    ): List<Track> {
        val plexSession = session?.takeIf { it.isPlex() } ?: return emptyList()
        val server = plexSession.selectedServer ?: return emptyList()
        val library = plexSession.selectedLibrary ?: return emptyList()
        val token = plexSession.serverAuthToken() ?: return emptyList()
        val libraryKey = plexSession.libraryPopularTrackCacheKey() ?: return emptyList()
        if (tracksPerArtist <= 0) return emptyList()
        val artistRatingKeys = mutableCatalog.value.artists
            .asSequence()
            .mapNotNull { artist -> artist.plexPopularMixRatingKey()?.let { key -> key to artist } }
            .distinctBy { it.first }
            .toList()
        if (artistRatingKeys.isEmpty()) {
            publishLibraryPopularTracks(libraryKey, emptyList())
            runCatalogDbWrite { persistLibraryPopularTracks(libraryKey, emptyList()) }
            return emptyList()
        }
        val tracks = coroutineScope {
            val collected = mutableListOf<Track>()
            artistRatingKeys
                .chunked(catalogTrackIndexParallelism().coerceAtLeast(1))
                .forEach { chunk ->
                    collected += chunk.map { (ratingKey, artist) ->
                        async {
                            try {
                                plexClient.popularTracksForArtist(
                                    server = server,
                                    library = library,
                                    ratingKey = ratingKey,
                                    token = token,
                                    limit = tracksPerArtist,
                                ).map { it.withPlexPrefix() }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                PhoebeLog.d("CatalogRepository") {
                                    "library popular tracks warm failed for '${artist.title}': ${error.message}"
                                }
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                    yield()
                }
            collected.distinctBy { it.id }
        }
        if (tracks.isNotEmpty()) {
            publishIndexedPlexTracks(tracks)
        }
        publishLibraryPopularTracks(libraryKey, tracks)
        runCatalogDbWrite {
            if (tracks.isNotEmpty()) {
                persistTrackBatch(tracks)
            }
            persistLibraryPopularTracks(libraryKey, tracks)
        }
        return tracks
    }

    private suspend fun publishLibraryPopularTracks(libraryKey: String, tracks: List<Track>) {
        catalogMergeMutex.withLock {
            val cur = mutableCatalog.value
            val popularTracksByLibrary = if (tracks.isEmpty()) {
                cur.popularTracksByLibrary - libraryKey
            } else {
                cur.popularTracksByLibrary + (libraryKey to tracks.distinctBy { it.id })
            }
            mutableCatalog.value = cur.copy(popularTracksByLibrary = popularTracksByLibrary)
        }
    }

    suspend fun ensureSimilarArtistsForArtist(session: PlexSession?, artist: Artist): List<Artist> {
        mutableCatalog.value.similarArtistsByArtist[artist.id]?.let { return it }
        val plexSession = session?.takeIf { it.isPlex() } ?: return emptyList()
        val ratingKey = plexRatingKey(artist.id) ?: return emptyList()
        val server = plexSession.selectedServer ?: return emptyList()
        val token = plexSession.serverAuthToken() ?: return emptyList()
        val plexSimilar = plexClient.similarArtistsForArtist(
            server = server,
            ratingKey = ratingKey,
            token = token,
            limit = ArtistSimilarArtistLimit,
        )
        val current = mutableCatalog.value
        val byPlexRatingKey = current.artists.mapNotNull { candidate ->
            plexRatingKey(candidate.id)?.let { key -> key to candidate }
        }.toMap()
        val byTitle = current.artists.associateBy { candidate -> candidate.title.normalizedArtistLookupKey() }
        val similar = plexSimilar
            .mapNotNull { candidate ->
                byPlexRatingKey[candidate.id]
                    ?: byPlexRatingKey[candidate.id.ratingKeyFromPlexPath().orEmpty()]
                    ?: byTitle[candidate.title.normalizedArtistLookupKey()]
            }
            .filter { candidate -> candidate.id != artist.id }
            .distinctBy { candidate -> candidate.id }
            .take(ArtistSimilarArtistLimit)
        catalogMergeMutex.withLock {
            val cur = mutableCatalog.value
            if (cur.similarArtistsByArtist[artist.id] == null) {
                mutableCatalog.value = cur.copy(
                    similarArtistsByArtist = cur.similarArtistsByArtist + (artist.id to similar),
                )
            }
        }
        return similar
    }

    suspend fun warmTracksForPersonalMix(session: PlexSession?, minTracks: Int): Int {
        if (minTracks <= 0) return 0
        val startCount = mutableCatalog.value.playableTrackCount()
        if (startCount >= minTracks) return 0
        if (session?.isNavidrome() != true) return 0
        warmNavidromeAlbumTracksForPool(session, minTracks - startCount)
        return mutableCatalog.value.playableTrackCount() - startCount
    }

    suspend fun warmRecentAlbumTracks(session: PlexSession?, cutoffMs: Long, maxAlbums: Int = 10) {
        val navidromeSession = session?.takeIf { it.isNavidrome() }
        if (navidromeSession != null) {
            warmNavidromeRecentAlbumTracks(navidromeSession, cutoffMs, maxAlbums)
            return
        }
        val server = session?.selectedServer ?: return
        val token = session.serverAuthToken() ?: return
        val albumsToFetch = mutableCatalog.value.albums
            .asSequence()
            .filter { (it.dateAddedMs ?: Long.MIN_VALUE) >= cutoffMs }
            .filter { plexRatingKey(it.id) != null }
            .filter { mutableCatalog.value.tracksByParent[it.id].isNullOrEmpty() }
            .sortedByDescending { it.dateAddedMs ?: 0L }
            .take(maxAlbums)
            .toList()
        if (albumsToFetch.isEmpty()) return
        PhoebeLog.v("CatalogRepository") { "warmRecentAlbumTracks → ${albumsToFetch.size} albums" }

        coroutineScope {
            albumsToFetch
                .chunked(catalogTrackIndexParallelism().coerceAtLeast(1))
                .forEach { chunk ->
                    chunk.map { album ->
                        async {
                            runCatching {
                                val rating = plexRatingKey(album.id) ?: return@runCatching
                                val tracks = plexClient.children(server, rating, token)
                                    .map { track ->
                                        track.withPlexPrefix().let { prefixed ->
                                            if (prefixed.dateAddedMs == null && album.dateAddedMs != null) {
                                                prefixed.copy(dateAddedMs = album.dateAddedMs)
                                            } else {
                                                prefixed
                                            }
                                        }
                                    }
                                    .let { preserveTrackDateAdded(mutableCatalog.value.tracksByParent[album.id].orEmpty(), it) }
                                catalogMergeMutex.withLock {
                                    val cur = mutableCatalog.value
                                    if (cur.tracksByParent[album.id].isNullOrEmpty()) {
                                        publish(
                                            cur.copy(tracksByParent = cur.tracksByParent + (album.id to tracks)),
                                            persist = false,
                                        )
                                    }
                                }
                            }.onFailure { e ->
                                PhoebeLog.d("CatalogRepository") { "recent album warm failed for '${album.title}': ${e.message}" }
                            }
                        }
                    }.awaitAll()
                    yield()
                }
        }
        persistCurrentTrackParentsWithoutClearingCatalog()
    }

    suspend fun warmAlbumTracksByTitle(session: PlexSession?, albumTitles: List<String>, maxAlbums: Int = 10) {
        val server = session?.selectedServer ?: return
        val token = session.serverAuthToken() ?: return
        val titleOrder = albumTitles
            .mapIndexedNotNull { index, title ->
                title.trim().lowercase().takeIf { it.isNotBlank() }?.let { it to index }
            }
            .toMap()
        if (titleOrder.isEmpty()) return
        val albumsToFetch = mutableCatalog.value.albums
            .asSequence()
            .filter { it.title.trim().lowercase() in titleOrder }
            .filter { plexRatingKey(it.id) != null }
            .filter { mutableCatalog.value.tracksByParent[it.id].isNullOrEmpty() }
            .sortedBy { titleOrder[it.title.trim().lowercase()] ?: Int.MAX_VALUE }
            .take(maxAlbums)
            .toList()
        if (albumsToFetch.isEmpty()) return
        PhoebeLog.v("CatalogRepository") { "warmAlbumTracksByTitle → ${albumsToFetch.size} albums" }

        coroutineScope {
            albumsToFetch
                .chunked(catalogTrackIndexParallelism().coerceAtLeast(1))
                .forEach { chunk ->
                    chunk.map { album ->
                        async {
                            runCatching {
                                val rating = plexRatingKey(album.id) ?: return@runCatching
                                val tracks = plexClient.children(server, rating, token)
                                    .map { track ->
                                        track.withPlexPrefix().let { prefixed ->
                                            if (prefixed.dateAddedMs == null && album.dateAddedMs != null) {
                                                prefixed.copy(dateAddedMs = album.dateAddedMs)
                                            } else {
                                                prefixed
                                            }
                                        }
                                    }
                                    .let { preserveTrackDateAdded(mutableCatalog.value.tracksByParent[album.id].orEmpty(), it) }
                                catalogMergeMutex.withLock {
                                    val cur = mutableCatalog.value
                                    if (cur.tracksByParent[album.id].isNullOrEmpty()) {
                                        publish(
                                            cur.copy(tracksByParent = cur.tracksByParent + (album.id to tracks)),
                                            persist = false,
                                        )
                                    }
                                }
                            }.onFailure { e ->
                                PhoebeLog.d("CatalogRepository") { "played album warm failed for '${album.title}': ${e.message}" }
                            }
                        }
                    }.awaitAll()
                    yield()
                }
        }
        persistCurrentTrackParentsWithoutClearingCatalog()
    }

    private suspend fun warmNavidromeAlbumTracksByTitle(
        session: PlexSession,
        albumTitles: List<String>,
        maxAlbums: Int = 10,
    ) {
        val titleOrder = albumTitles
            .mapIndexedNotNull { index, title ->
                title.trim().lowercase().takeIf { it.isNotBlank() }?.let { it to index }
            }
            .toMap()
        if (titleOrder.isEmpty()) return
        val albumsToFetch = mutableCatalog.value.albums
            .asSequence()
            .filter { it.title.trim().lowercase() in titleOrder }
            .filter { mutableCatalog.value.tracksByParent[it.id].isNullOrEmpty() }
            .sortedBy { titleOrder[it.title.trim().lowercase()] ?: Int.MAX_VALUE }
            .take(maxAlbums)
            .toList()
        publishNavidromeAlbumTracks(session, albumsToFetch)
    }

    private suspend fun warmNavidromeRecentAlbumTracks(
        session: PlexSession,
        cutoffMs: Long,
        maxAlbums: Int,
    ) {
        val albumsToFetch = mutableCatalog.value.albums
            .asSequence()
            .filter { (it.dateAddedMs ?: Long.MIN_VALUE) >= cutoffMs }
            .filter { mutableCatalog.value.tracksByParent[it.id].isNullOrEmpty() }
            .sortedByDescending { it.dateAddedMs ?: 0L }
            .take(maxAlbums)
            .toList()
        if (albumsToFetch.isEmpty()) return
        PhoebeLog.v("CatalogRepository") { "warmNavidromeRecentAlbumTracks → ${albumsToFetch.size} albums" }
        publishNavidromeAlbumTracks(session, albumsToFetch)
    }

    private suspend fun warmNavidromeAlbumTracksForPool(session: PlexSession, minAdditionalTracks: Int) {
        if (minAdditionalTracks <= 0) return
        if (session.selectedServer == null || session.token.isBlank()) return
        val targetCount = mutableCatalog.value.playableTrackCount() + minAdditionalTracks
        val albumsWithoutTracks = mutableCatalog.value.albums
            .asSequence()
            .filter { mutableCatalog.value.tracksByParent[it.id].isNullOrEmpty() }
            .shuffled()
            .take(PersonalMixWarmMaxAlbums)
            .toList()
        if (albumsWithoutTracks.isEmpty()) return
        PhoebeLog.d("CatalogRepository") {
            "warmNavidromeAlbumTracksForPool → need $minAdditionalTracks more tracks"
        }
        for (batch in albumsWithoutTracks.chunked(PersonalMixWarmBatchAlbums.coerceAtLeast(1))) {
            if (mutableCatalog.value.playableTrackCount() >= targetCount) break
            publishNavidromeAlbumTracks(session, batch)
        }
    }

    private suspend fun publishNavidromeAlbumTracks(session: PlexSession, albums: List<Album>) {
        if (albums.isEmpty()) return
        val server = session.selectedServer ?: return
        val username = session.userName
        val password = session.token.takeIf { it.isNotBlank() } ?: return
        val prefix = session.providerType.catalogPrefix
        val remoteAlbums = albums.map { album -> album.copy(id = album.id.removePrefix("$prefix:")) }
        val tracksByAlbum = subsonicClient.albumTracksParallel(
            server = server,
            albums = remoteAlbums,
            username = username,
            password = password,
            albumsById = remoteAlbums.associateBy { it.id },
            parallelism = catalogTrackIndexParallelism().coerceAtLeast(1),
        )
        if (tracksByAlbum.isEmpty()) return
        val prefixedTracksByAlbum = CatalogMerge.withPrefix(
            prefix,
            CatalogSnapshot(tracksByParent = tracksByAlbum),
        ).tracksByParent
        catalogMergeMutex.withLock {
            val cur = mutableCatalog.value
            publish(
                cur.copy(tracksByParent = mergeTrackParents(cur.tracksByParent, prefixedTracksByAlbum)),
                persist = false,
            )
        }
        persistCurrentTrackParentsWithoutClearingCatalog()
    }

    private fun CatalogSnapshot.playableTrackCount(): Int =
        tracksByParent.values
            .asSequence()
            .flatten()
            .distinctBy { it.id }
            .count { it.streamUrl.isNotBlank() || !it.localUri.isNullOrBlank() }

    private suspend fun publishIndexedProviderTracks(prefix: String, rawTracks: List<Track>) {
        val tracksByAlbum = rawTracks
            .map { it.withProviderPrefix(prefix) }
            .groupBy { track -> resolveIndexedTrackParentId(track, mutableCatalog.value) }
            .filterKeys { it != null }
            .mapKeys { it.key!! }
        if (tracksByAlbum.isEmpty()) return
        catalogMergeMutex.withLock {
            val cur = mutableCatalog.value
            var nextParents = cur.tracksByParent
            tracksByAlbum.forEach { (parentId, tracks) ->
                val existing = nextParents[parentId].orEmpty()
                nextParents = nextParents + (parentId to mergeTrackLists(existing, tracks))
            }
            mutableCatalog.value = cur.copy(tracksByParent = nextParents)
        }
    }

    suspend fun publishNavidromeTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        publishIndexedProviderTracks("navidrome", tracks)
        runCatalogDbWrite { persistTrackBatch(tracks.map { it.withProviderPrefix("navidrome") }) }
    }

    suspend fun publishProviderTracks(prefix: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        publishIndexedProviderTracks(prefix, tracks)
        runCatalogDbWrite { persistTrackBatch(tracks.map { it.withProviderPrefix(prefix) }) }
    }

    suspend fun publishPlexTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        publishIndexedPlexTracks(tracks)
        runCatalogDbWrite { persistTrackBatch(tracks.map { it.withPlexPrefix() }) }
    }

    suspend fun warmPlexHistoryTracks(session: PlexSession?, maxEntries: Int = 200): Int {
        val server = session?.selectedServer ?: return 0
        val library = session.selectedLibrary ?: return 0
        val token = session.serverAuthToken() ?: return 0
        if (maxEntries <= 0) return 0

        val loadedTrackIds = mutableCatalog.value.tracksByParent.values
            .asSequence()
            .flatten()
            .map { it.id }
            .toSet()
        val entries = mutableListOf<PlexPlaybackHistoryEntry>()
        var start = 0
        while (entries.size < maxEntries) {
            val pageSize = (maxEntries - entries.size).coerceAtMost(PlexPlayHistoryWarmPageSize)
            val page = plexClient.playbackHistoryPage(
                server = server,
                token = token,
                library = library,
                minViewedAtMs = null,
                start = start,
                size = pageSize,
            )
            entries += page.entries.filter { entry ->
                (entry.type == null || entry.type == "track") &&
                    (entry.librarySectionId == null || entry.librarySectionId == library.key)
            }
            val total = page.totalSize
            val next = page.offset + page.size
            if (page.size <= 0 || (total != null && next >= total)) break
            start = next
        }

        val missingRatingKeys = entries
            .asSequence()
            .map { it.ratingKey }
            .filter { it.isNotBlank() }
            .distinct()
            .filterNot { "plex:$it" in loadedTrackIds || it in loadedTrackIds }
            .toList()
        if (missingRatingKeys.isEmpty()) return 0

        val fetched = coroutineScope {
            val tracks = mutableListOf<Track>()
            missingRatingKeys
                .chunked(catalogTrackIndexParallelism().coerceAtLeast(1))
                .forEach { chunk ->
                    tracks.addAll(chunk.map { ratingKey ->
                        async {
                            runCatching { plexClient.trackDetails(server, ratingKey, token) }
                                .onFailure { e ->
                                    PhoebeLog.d("CatalogRepository") {
                                        "history track warm failed for '$ratingKey': ${e.message}"
                                    }
                                }
                                .getOrNull()
                        }
                    }.awaitAll().filterNotNull())
                }
            tracks
        }
        if (fetched.isEmpty()) return 0

        publishIndexedPlexTracks(fetched)
        runCatalogDbWrite { persistTrackBatch(fetched.map { it.withPlexPrefix() }) }
        PhoebeLog.d("CatalogRepository") { "warmPlexHistoryTracks → ${fetched.size} tracks" }
        return fetched.size
    }

    suspend fun resolveTracksByIds(ids: Collection<String>): Map<String, Track> {
        if (ids.isEmpty()) return emptyMap()
        val snapshot = mutableCatalog.value
        val activeFolderIds = activeLocalFolderIds()
        val resolved = LinkedHashMap<String, Track>(ids.size)
        val remaining = ids.filterTo(LinkedHashSet()) { it.isNotBlank() }

        for (id in remaining.toList()) {
            snapshot.findTrackByIds(providerTrackLookupIds(id))
                ?.takeUnless { track -> track.id.isInactiveLocalFolderCatalogId(activeFolderIds) }
                ?.let { track ->
                    resolved[id] = track
                    remaining.remove(id)
                }
        }
        val fromDb = withContext(Dispatchers.Default) {
            buildMap(remaining.size) {
                for (id in remaining) {
                    providerTrackLookupIds(id).firstNotNullOfOrNull { lookupId ->
                        database.catalogQueries.selectTrackById(lookupId).awaitAsOneOrNull()
                            ?.takeUnless { row -> row.id.isInactiveLocalFolderCatalogId(activeFolderIds) }
                    }?.let { row ->
                        put(
                            id,
                            Track(
                                id = row.id,
                                title = row.title,
                                artist = row.artist,
                                album = row.album,
                                durationMs = row.durationMs,
                                streamUrl = row.streamUrl,
                                downloadUrl = row.downloadUrl,
                                thumbUrl = row.thumbUrl,
                                localArtworkUri = row.localArtworkUri,
                                localUri = row.localUri,
                                year = row.year?.toInt(),
                                genre = row.genre,
                                mood = row.mood,
                                style = row.style,
                                filepath = row.filepath,
                                audioCodec = row.audioCodec,
                                bitrateKbps = row.bitrateKbps?.toInt(),
                                dateAddedMs = row.dateAddedMs,
                                rating = row.rating?.toFloat(),
                                parentAlbumId = row.parentAlbumId,
                            ),
                        )
                    }
                }
            }
        }
        if (fromDb.isNotEmpty()) {
            val plexTracks = fromDb.values.filter { it.id.startsWith("plex:") }
            val providerTracks = fromDb.values.filterNot { it.id.startsWith("plex:") }
            if (plexTracks.isNotEmpty()) publishIndexedPlexTracks(plexTracks)
            providerTracks.groupBy { it.id.substringBefore(':') }
                .filterKeys { it.isNotBlank() }
                .forEach { (prefix, tracks) -> publishIndexedProviderTracks(prefix, tracks) }
            resolved.putAll(fromDb)
            fromDb.keys.forEach(remaining::remove)
        }
        return resolved
    }

    suspend fun warmTracksForMostPlayed(
        session: PlexSession?,
        entries: List<com.phoebe.app.domain.MostPlayedEntry>,
        maxTracks: Int = 20,
    ): Int {
        if (entries.isEmpty() || maxTracks <= 0) return 0
        val capped = entries.take(maxTracks)
        val ids = capped.map { it.trackId }
        val alreadyResolved = resolveTracksByIds(ids)
        val missing = capped.filter { it.trackId !in alreadyResolved }
        if (missing.isEmpty()) return 0

        val server = session?.selectedServer
        val token = session?.serverAuthToken()
        if (server != null && session.isNavidrome()) {
            val username = session.userName
            val password = session.token
            val fetched = coroutineScope {
                missing.map { entry ->
                    async {
                        runCatching { subsonicClient.getSong(server, username, password, entry.trackId) }
                            .onFailure { e ->
                                PhoebeLog.d("CatalogRepository") {
                                    "most-played track warm failed for '${entry.trackId}': ${e.message}"
                                }
                            }
                            .getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
            if (fetched.isNotEmpty()) {
                publishIndexedProviderTracks("navidrome", fetched)
                runCatalogDbWrite { persistTrackBatch(fetched.map { it.withProviderPrefix("navidrome") }) }
            }
            val alreadyResolvedAfterFetch = resolveTracksByIds(missing.map { it.trackId })
            val stillMissing = missing.filter { it.trackId !in alreadyResolvedAfterFetch }
            val albumTitles = stillMissing
                .map { it.album.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
            if (albumTitles.isNotEmpty()) {
                warmNavidromeAlbumTracksByTitle(session, albumTitles, maxAlbums = albumTitles.size.coerceAtMost(maxTracks))
            }
            return fetched.size
        }
        if (server != null && token != null && session.isPlex()) {
            val ratingKeys = missing.mapNotNull { entry ->
                plexRatingKey(entry.trackId)?.takeIf { it.isNotBlank() }
            }.distinct()
            if (ratingKeys.isEmpty()) return 0
            val fetched = coroutineScope {
                ratingKeys
                    .chunked(catalogTrackIndexParallelism().coerceAtLeast(1))
                    .flatMap { chunk ->
                        chunk.map { ratingKey ->
                            async {
                                runCatching { plexClient.trackDetails(server, ratingKey, token) }
                                    .onFailure { e ->
                                        PhoebeLog.d("CatalogRepository") {
                                            "most-played track warm failed for '$ratingKey': ${e.message}"
                                        }
                                    }
                                    .getOrNull()
                            }
                        }.awaitAll().filterNotNull()
                    }
            }
            if (fetched.isNotEmpty()) {
                publishIndexedPlexTracks(fetched)
                runCatalogDbWrite { persistTrackBatch(fetched.map { it.withPlexPrefix() }) }
            }
            return fetched.size
        }
        if (server != null && token != null && session.isEmbyFamily()) {
            val prefix = session.providerType.catalogPrefix
            val remoteClient = if (session.providerType.catalogPrefix == "emby") embyClient else jellyfinClient
            val itemIds = missing.mapNotNull { entry ->
                entry.trackId.removePrefix("$prefix:").takeIf { it.isNotBlank() && it != entry.trackId }
            }.distinct()
            if (itemIds.isEmpty()) return 0
            val fetched = coroutineScope {
                itemIds
                    .chunked(catalogTrackIndexParallelism().coerceAtLeast(1))
                    .flatMap { chunk ->
                        chunk.map { itemId ->
                            async {
                                runCatching { remoteClient.trackDetails(server, token, itemId, session.userId) }
                                    .onFailure { e ->
                                        PhoebeLog.d("CatalogRepository") {
                                            "play-history track warm failed for '$prefix:$itemId': ${e.message}"
                                        }
                                    }
                                    .getOrNull()
                            }
                        }.awaitAll().filterNotNull()
                    }
            }
            if (fetched.isNotEmpty()) {
                publishProviderTracks(prefix, fetched)
            }
            return fetched.size
        }

        val albumTitles = missing
            .map { it.album.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        if (albumTitles.isNotEmpty()) {
            warmAlbumTracksByTitle(session, albumTitles, maxAlbums = albumTitles.size.coerceAtMost(maxTracks))
        }
        return 0
    }

    suspend fun tracksForDecade(session: PlexSession?, decade: Int): List<Track> {
        val start = decade
        val end = decade + 9
        val server = session?.selectedServer
        val library = session?.selectedLibrary
        val token = session.serverAuthToken()
        if (server != null && library != null && token != null) {
            val directTracks = runCatching {
                val firstPage = plexClient.tracksForYearRangePage(
                    server = server,
                    library = library,
                    token = token,
                    startYear = start,
                    endYear = end,
                    start = 0,
                    size = DecadeTrackPageSize,
                    limit = DecadeTrackLimit,
                )
                val pages = mutableListOf(firstPage)
                var offset = firstPage.nextOffset
                var pageCount = 1
                while (firstPage.hasMore && pageCount < MaxDecadeTrackPages) {
                    val page = plexClient.tracksForYearRangePage(
                        server = server,
                        library = library,
                        token = token,
                        startYear = start,
                        endYear = end,
                        start = offset,
                        size = DecadeTrackPageSize,
                        limit = DecadeTrackLimit,
                    )
                    if (page.tracks.isEmpty()) break
                    pages += page
                    if (!page.hasMore) break
                    offset = page.nextOffset
                    pageCount++
                }
                pages
                    .flatMap { it.tracks }
                    .map { it.withPlexPrefix() }
                    .filter { it.year?.let { year -> year in start..end } == true }
            }.onFailure { e ->
                PhoebeLog.d("CatalogRepository") { "decade track search failed for ${decade}s: ${e.message}" }
            }.getOrDefault(emptyList())
            if (directTracks.isNotEmpty()) {
                publishIndexedPlexTracks(directTracks)
                val loadedTracks = mutableCatalog.value.tracksByParent.values
                    .asSequence()
                    .flatten()
                    .filter { it.year?.let { year -> year in start..end } == true }
                    .toList()
                return (directTracks + loadedTracks).distinctBy { it.id }
            }
        }
        val matchingPlexAlbums = mutableCatalog.value.albums
            .filter { album -> album.year?.let { it in start..end } == true && plexRatingKey(album.id) != null }
        if (server != null && token != null && matchingPlexAlbums.isNotEmpty()) {
            withCatalogRefreshing {
                val normalized = mutableCatalog.value
                val matchingById = matchingPlexAlbums.associateBy { it.id }
                val normalizedParents = normalized.tracksByParent.mapValues { (parentId, tracks) ->
                    val album = matchingById[parentId]
                    if (album?.year == null) {
                        tracks
                    } else {
                        tracks.map { track ->
                            if (track.year == null) track.copy(year = album.year) else track
                        }
                    }
                }
                if (normalizedParents != normalized.tracksByParent) {
                    publish(normalized.copy(tracksByParent = normalizedParents), persist = false)
                }
                coroutineScope {
                    val parallelism = maxOf(catalogTrackIndexParallelism(), 4)
                    matchingPlexAlbums
                        .filter { album -> mutableCatalog.value.tracksByParent[album.id].isNullOrEmpty() }
                        .chunked(parallelism)
                        .forEach { chunk ->
                            chunk.map { album ->
                                async {
                                    runCatching {
                                        val rating = plexRatingKey(album.id) ?: return@runCatching
                                        val rawTracks = withTimeoutOrNull(DecadeAlbumFetchTimeoutMs) {
                                            plexClient.children(server, rating, token)
                                        }
                                        if (rawTracks == null) {
                                            PhoebeLog.d("CatalogRepository") {
                                                "decade album fetch timed out for '${album.title}' after ${DecadeAlbumFetchTimeoutMs}ms"
                                            }
                                            return@runCatching
                                        }
                                        val tracks = rawTracks
                                            .map { track ->
                                                track.withPlexPrefix().let { prefixed ->
                                                    prefixed.copy(
                                                        year = prefixed.year ?: album.year,
                                                        dateAddedMs = prefixed.dateAddedMs ?: album.dateAddedMs,
                                                    )
                                                }
                                            }
                                            .let { preserveTrackDateAdded(mutableCatalog.value.tracksByParent[album.id].orEmpty(), it) }
                                        catalogMergeMutex.withLock {
                                            val cur = mutableCatalog.value
                                            if (cur.tracksByParent[album.id].isNullOrEmpty()) {
                                                publish(
                                                    cur.copy(tracksByParent = cur.tracksByParent + (album.id to tracks)),
                                                    persist = false,
                                                )
                                            }
                                        }
                                    }.onFailure { e ->
                                        PhoebeLog.d("CatalogRepository") { "decade album fetch failed for '${album.title}': ${e.message}" }
                                    }
                                }
                            }.awaitAll()
                            yield()
                        }
                }
                persistCurrentTrackParentsWithoutClearingCatalog()
            }
        }
        return mutableCatalog.value.tracksByParent.values
            .asSequence()
            .flatten()
            .distinctBy { it.id }
            .filter { it.year?.let { year -> year in start..end } == true }
            .toList()
    }

    suspend fun collectionFilterChoiceForValue(
        session: PlexSession?,
        facet: CollectionFacet,
        value: String,
    ): PlexFilterChoice? {
        if (!session.supportsCollectionEntry(CollectionEntry(CollectionTarget.Albums, facet))) return null
        if (facet != CollectionFacet.Mood && facet != CollectionFacet.Style) return null
        val server = session?.selectedServer ?: return null
        val library = session.selectedLibrary ?: return null
        val token = session.serverAuthToken() ?: return null
        val normalizedValue = value.trim()
        if (normalizedValue.isBlank()) return null
        val entry = CollectionEntry(CollectionTarget.Albums, facet)
        ensureCollectionValues(session, entry)
        val cached = mutableCatalog.value.collectionValues.firstOrNull {
            it.facet == facet.name && it.value.equals(normalizedValue, ignoreCase = true)
        }
        if (cached != null) {
            return PlexFilterChoice(
                key = cached.key,
                title = cached.value,
                fastKey = cached.fastKey,
                filterField = cached.filterField,
            )
        }
        val plexFacet = facet.toPlexCollectionFacet()
        return plexClient.collectionFilterChoices(
            server = server,
            library = library,
            target = PlexCollectionTarget.Albums,
            facet = plexFacet,
            token = token,
        ).firstOrNull { it.title.equals(normalizedValue, ignoreCase = true) }
    }

    suspend fun tracksForCollectionFacet(
        session: PlexSession?,
        facet: CollectionFacet,
        value: String,
    ): List<Track> {
        if (!session.supportsCollectionEntry(CollectionEntry(CollectionTarget.Albums, facet))) return emptyList()
        if (facet != CollectionFacet.Mood && facet != CollectionFacet.Style) return emptyList()
        val server = session?.selectedServer
        val library = session?.selectedLibrary
        val token = session.serverAuthToken()
        if (server != null && library != null && token != null) {
            val choice = collectionFilterChoiceForValue(session, facet, value) ?: return emptyList()
            val plexFacet = facet.toPlexCollectionFacet()
            val firstPage = runCatching {
                plexClient.tracksForCollectionFacetPage(
                    server = server,
                    library = library,
                    token = token,
                    facet = plexFacet,
                    choice = choice,
                    start = 0,
                    size = CollectionFacetTrackPageSize,
                    limit = CollectionFacetTrackLimit,
                )
            }.getOrNull()
            if (firstPage != null && firstPage.tracks.isNotEmpty()) {
                val pages = mutableListOf(firstPage)
                var offset = firstPage.nextOffset
                var pageCount = 1
                while (firstPage.hasMore && pageCount < MaxCollectionFacetTrackPages) {
                    val page = runCatching {
                        plexClient.tracksForCollectionFacetPage(
                            server = server,
                            library = library,
                            token = token,
                            facet = plexFacet,
                            choice = choice,
                            start = offset,
                            size = CollectionFacetTrackPageSize,
                            limit = CollectionFacetTrackLimit,
                        )
                    }.getOrNull() ?: break
                    if (page.tracks.isEmpty()) break
                    pages += page
                    if (!page.hasMore) break
                    offset = page.nextOffset
                    pageCount++
                }
                val directTracks = pages
                    .flatMap { it.tracks }
                    .map { it.withPlexPrefix() }
                    .distinctBy { it.id }
                if (directTracks.isNotEmpty()) {
                    publishIndexedPlexTracks(directTracks)
                    return directTracks
                }
            }
        }
        return cachedTracksForCollectionFacet(facet, value)
    }

    suspend fun firstTracksForCollectionFacet(
        session: PlexSession?,
        facet: CollectionFacet,
        value: String,
    ): List<Track> {
        if (!session.supportsCollectionEntry(CollectionEntry(CollectionTarget.Albums, facet))) return emptyList()
        if (facet != CollectionFacet.Mood && facet != CollectionFacet.Style) return emptyList()
        val cached = cachedTracksForCollectionFacet(facet, value)
        if (cached.isNotEmpty()) return cached.shuffled().take(CollectionFacetFirstPageSize)

        val server = session?.selectedServer
        val library = session?.selectedLibrary
        val token = session.serverAuthToken()
        if (server == null || library == null || token == null) return emptyList()
        val choice = collectionFilterChoiceForValue(session, facet, value) ?: return emptyList()
        val directTracks = runCatching {
            plexClient.tracksForCollectionFacetPage(
                server = server,
                library = library,
                token = token,
                facet = facet.toPlexCollectionFacet(),
                choice = choice,
                start = 0,
                size = CollectionFacetFirstPageSize,
                limit = CollectionFacetTrackLimit,
            ).tracks
                .map { it.withPlexPrefix() }
                .distinctBy { it.id }
        }.onFailure { error ->
            PhoebeLog.d("CatalogRepository") {
                "first collection facet page failed facet=${facet.name} value='$value': ${error.message}"
            }
        }.getOrDefault(emptyList())
        if (directTracks.isNotEmpty()) {
            publishIndexedPlexTracks(directTracks)
        }
        return directTracks
    }

    private fun cachedTracksForCollectionFacet(facet: CollectionFacet, value: String): List<Track> {
        val normalized = value.trim()
        if (normalized.isBlank()) return emptyList()
        return mutableCatalog.value.tracksByParent.values
            .asSequence()
            .flatten()
            .distinctBy { it.id }
            .filter { track ->
                track.collectionLabel(facet).matchesCollectionValue(normalized)
            }
            .toList()
    }

    suspend fun firstTracksForDecade(session: PlexSession?, decade: Int): List<Track> {
        val start = decade
        val end = decade + 9
        val cached = cachedTracksForDecade(start, end)
        if (cached.isNotEmpty()) return cached

        val server = session?.selectedServer
        val library = session?.selectedLibrary
        val token = session.serverAuthToken()
        if (server != null && library != null && token != null) {
            val directTracks = runCatching {
                plexClient.tracksForYearRangePage(
                    server = server,
                    library = library,
                    token = token,
                    startYear = start,
                    endYear = end,
                    start = 0,
                    size = DecadeFirstPageSize,
                    limit = DecadeTrackLimit,
                ).tracks
                    .map { it.withPlexPrefix() }
                    .filter { it.year?.let { year -> year in start..end } == true }
            }.onFailure { e ->
                PhoebeLog.d("CatalogRepository") { "first decade page failed for ${decade}s: ${e.message}" }
            }.getOrDefault(emptyList())
            if (directTracks.isNotEmpty()) {
                publishIndexedPlexTracks(directTracks)
                return directTracks
            }
        }
        return cachedTracksForDecade(start, end)
    }

    suspend fun plexRadioStations(session: PlexSession?): List<PlexRadioStation> {
        val server = session?.selectedServer ?: return emptyList()
        val library = session.selectedLibrary ?: return emptyList()
        val token = session.serverAuthToken() ?: return emptyList()
        val defaults = defaultPlexRadioStations(library)
        val stations = plexClient.musicStations(server, library, token)
        return mergePlexLibraryRadioStations(stations, defaults)
    }

    suspend fun playRadioStation(session: PlexSession?, station: PlexRadioStation): List<Track> {
        val server = session?.selectedServer ?: return emptyList()
        session.selectedLibrary ?: return emptyList()
        val token = session.serverAuthToken() ?: return emptyList()
        val machineId = resolveMachineIdentifier(server, token)
        return plexClient.createStationPlayQueue(server, token, machineId, station.key)
            .map { it.withPlexPrefix() }
            .also { tracks -> publishRadioTracksInBackground(tracks) }
    }

    suspend fun playArtistRadio(session: PlexSession?, artist: Artist): List<Track> {
        val providerPrefix = session?.providerType?.catalogPrefix
        if (session.isEmbyFamily() && providerPrefix != null && artist.id.startsWith("$providerPrefix:")) {
            val server = session.selectedServer ?: return emptyList()
            val userId = session.userId ?: return emptyList()
            val remoteClient = if (providerPrefix == "emby") embyClient else jellyfinClient
            return remoteClient.instantMix(server, session.token, userId, artist.id.removePrefix("$providerPrefix:"))
                .map { it.withProviderPrefix(providerPrefix) }
                .also { tracks ->
                    if (tracks.isNotEmpty()) publishIndexedJellyfinTracks(tracks)
                }
        }
        val station = artistRadioStation(session, artist) ?: return emptyList()
        return playRadioStation(session, station)
    }

    suspend fun instantMixForItem(session: PlexSession?, itemId: String): List<Track> {
        val providerPrefix = session?.providerType?.catalogPrefix
        if (!session.isEmbyFamily() || providerPrefix == null || !itemId.startsWith("$providerPrefix:")) {
            return emptyList()
        }
        val server = session.selectedServer ?: return emptyList()
        val userId = session.userId ?: return emptyList()
        val remoteClient = if (providerPrefix == "emby") embyClient else jellyfinClient
        return remoteClient.instantMix(server, session.token, userId, itemId.removePrefix("$providerPrefix:"))
            .map { it.withProviderPrefix(providerPrefix) }
            .also { tracks ->
                if (tracks.isNotEmpty()) publishIndexedJellyfinTracks(tracks)
            }
    }

    suspend fun plexSimilarTracksForItem(session: PlexSession?, itemId: String): List<Track> {
        val plexSession = session?.takeIf { it.isPlex() } ?: return emptyList()
        val ratingKey = plexRatingKey(itemId) ?: return emptyList()
        val server = plexSession.selectedServer ?: return emptyList()
        val token = plexSession.serverAuthToken() ?: return emptyList()
        val tracks = plexClient.similarTracksForMetadata(
            server = server,
            ratingKey = ratingKey,
            token = token,
        ).map { it.withPlexPrefix() }
        publishRadioTracksInBackground(tracks)
        return tracks
    }

    suspend fun artistRadioStation(session: PlexSession?, artist: Artist): PlexRadioStation? {
        val providerPrefix = session?.providerType?.catalogPrefix
        if (session.isEmbyFamily() && providerPrefix != null && artist.id.startsWith("$providerPrefix:")) {
            return PlexRadioStation(
                id = "$providerPrefix-artist-radio-${artist.id}",
                title = "${artist.title} Radio",
                subtitle = "${session.providerType.name} Instant Mix",
                key = artist.id,
                thumbUrl = artist.thumbUrl,
                category = PlexRadioStationCategory.Artist,
            )
        }
        val ratingKey = plexRatingKey(artist.id) ?: return null
        val server = session?.selectedServer ?: return null
        session.selectedLibrary ?: return null
        val token = session.serverAuthToken() ?: return null
        val station = withTimeoutOrNull(ArtistStationLookupTimeoutMs) {
            plexClient.artistStation(server, ratingKey, token)
        }
        if (station == null) {
            PhoebeLog.d("CatalogRepository") { "artist radio station not found for '${artist.title}' ($ratingKey)" }
        }
        return station
    }

    private fun cachedTracksForDecade(start: Int, end: Int): List<Track> =
        mutableCatalog.value.tracksByParent.values
            .asSequence()
            .flatten()
            .distinctBy { it.id }
            .filter { it.year?.let { year -> year in start..end } == true }
            .toList()

    private suspend fun publishIndexedJellyfinTracks(tracks: List<Track>) {
        val tracksByAlbum = tracks
            .groupBy { track -> track.parentAlbumId?.takeIf { it.isNotBlank() } ?: resolveJellyfinTrackParentId(track, mutableCatalog.value) }
            .filterKeys { it != null }
            .mapKeys { it.key!! }
        if (tracksByAlbum.isEmpty()) return
        catalogMergeMutex.withLock {
            val cur = mutableCatalog.value
            mutableCatalog.value = cur.copy(tracksByParent = cur.tracksByParent + tracksByAlbum)
        }
    }

    private fun resolveJellyfinTrackParentId(track: Track, snapshot: CatalogSnapshot): String? =
        snapshot.albums.firstOrNull {
            it.title.equals(track.album, ignoreCase = true) &&
                it.artist.equals(track.artist, ignoreCase = true)
        }?.id

    suspend fun tracksForPlaylist(session: PlexSession?, playlist: Playlist): List<Track> {
        if (playlist.isSmartPlaylist()) {
            return materializedSmartPlaylistTracks(playlist.id, mutableCatalog.value)
        }
        if (playlist.isLocalPlaylist()) {
            return mutableCatalog.value.tracksByParent[playlist.id].orEmpty()
        }
        if (playlist.id == PENDING_LIKED_SONGS_PLAYLIST_ID || playlist.isLikedSongsPlaylist()) {
            val cached = mutableCatalog.value.tracksByParent[playlist.id].orEmpty()
            if (cached.isNotEmpty()) return cached
            readTracksForParentFromDatabase(playlist.id)?.let { fromDb ->
                if (fromDb.isNotEmpty()) {
                    publish(
                        mutableCatalog.value.copy(
                            tracksByParent = mutableCatalog.value.tracksByParent + (playlist.id to fromDb),
                        ),
                        persist = false,
                    )
                    return fromDb
                }
            }
            if (session?.isNavidrome() == true) {
                pushTracksLoading(playlist.id)
                return try {
                    refetchPlaylistTracksFromPlex(session, playlist, showRefreshing = false)
                    mutableCatalog.value.tracksByParent[playlist.id].orEmpty()
                } finally {
                    popTracksLoading(playlist.id)
                }
            }
            return emptyList()
        }
        val snapshot = mutableCatalog.value
        val playlistMeta = snapshot.playlists.find { it.id == playlist.id } ?: playlist
        val existing = snapshot.tracksByParent[playlist.id]
        if (!existing.isNullOrEmpty()) {
            if (existing.size != playlistMeta.trackCount) {
                runCatching { refetchPlaylistTracksFromPlex(session, playlistMeta, showRefreshing = false) }
                    .onFailure { error ->
                        PhoebeLog.d("CatalogRepository") {
                            "playlist refresh failed for '${playlistMeta.title}': ${error.message}"
                        }
                    }
                val refreshed = mutableCatalog.value.tracksByParent[playlist.id]
                if (!refreshed.isNullOrEmpty()) {
                    return refreshed
                }
            }
            return existing
        }
        readTracksForParentFromDatabase(playlist.id)?.let { cached ->
            if (cached.isNotEmpty()) {
                if (cached.size != playlistMeta.trackCount) {
                    runCatching { refetchPlaylistTracksFromPlex(session, playlistMeta, showRefreshing = false) }
                        .onFailure { error ->
                            PhoebeLog.d("CatalogRepository") {
                                "playlist database refresh failed for '${playlistMeta.title}': ${error.message}"
                            }
                        }
                    val refreshed = mutableCatalog.value.tracksByParent[playlist.id]
                    if (!refreshed.isNullOrEmpty()) {
                        return refreshed
                    }
                }
                publish(
                    mutableCatalog.value.copy(
                        tracksByParent = mutableCatalog.value.tracksByParent + (playlist.id to cached),
                    ),
                    persist = false,
                )
                return cached
            }
        }
        pushTracksLoading(playlist.id)
        return try {
            refetchPlaylistTracksFromPlex(session, playlistMeta, showRefreshing = false)
            mutableCatalog.value.tracksByParent[playlist.id].orEmpty()
        } finally {
            popTracksLoading(playlist.id)
        }
    }

    suspend fun refreshSmartPlaylists() {
        val updated = withSmartPlaylists(mutableCatalog.value)
        mutableCatalog.value = updated
    }

    suspend fun findOrCreateLikedSongsPlaylist(session: PlexSession?): Playlist? {
        val existing = mutableCatalog.value.playlists.firstOrNull {
            it.isLikedSongsPlaylist() && it.id != PENDING_LIKED_SONGS_PLAYLIST_ID
        }
        if (existing != null) return existing
        return createPlaylist(session, LIKED_SONGS_PLAYLIST_TITLE)
    }

    suspend fun ensureLocalLikedSongsPlaylist(session: PlexSession? = null): Playlist {
        val providerPlaylistId = session?.providerType?.let { likedSongsPlaylistId(it) }
        providerPlaylistId?.let { id ->
            mutableCatalog.value.playlists.firstOrNull { it.id == id }?.let { return it }
        }
        val pending = mutableCatalog.value.playlists.firstOrNull { it.id == PENDING_LIKED_SONGS_PLAYLIST_ID }
        if (pending != null && providerPlaylistId != null) {
            migratePendingLikedSongs(providerPlaylistId)
            mutableCatalog.value.playlists.firstOrNull { it.id == providerPlaylistId }?.let { return it }
            val snapshot = mutableCatalog.value
            val migrated = pending.copy(id = providerPlaylistId)
            val updated = snapshot.copy(
                playlists = listOf(migrated) + snapshot.playlists.filterNot { it.id == pending.id },
            )
            publish(updated, persist = false)
            if (snapshot.hasRestorableCatalogContent()) {
                runCatalogDbWrite { persistPlaylistTracks(updated, migrated.id) }
            }
            return migrated
        }
        mutableCatalog.value.playlists.firstOrNull { it.isLikedSongsPlaylist() }?.let { return it }
        val playlist = Playlist(
            id = providerPlaylistId ?: PENDING_LIKED_SONGS_PLAYLIST_ID,
            title = LIKED_SONGS_PLAYLIST_TITLE,
            trackCount = 0,
        )
        val snapshot = mutableCatalog.value
        val updated = snapshot.copy(playlists = listOf(playlist) + snapshot.playlists)
        publish(updated, persist = false)
        if (snapshot.hasRestorableCatalogContent()) {
            runCatalogDbWrite { persistPlaylistTracks(updated, playlist.id) }
        }
        return playlist
    }

    private suspend fun migratePendingLikedSongs(targetPlaylistId: String) {
        val snapshot = mutableCatalog.value
        val pendingTracks = snapshot.tracksByParent[PENDING_LIKED_SONGS_PLAYLIST_ID].orEmpty()
        if (pendingTracks.isEmpty()) return
        val targetTracks = snapshot.tracksByParent[targetPlaylistId].orEmpty()
        val mergedTracks = (targetTracks + pendingTracks).distinctBy { it.id }
        val targetPlaylist = snapshot.playlists.firstOrNull { it.id == targetPlaylistId }
            ?: Playlist(id = targetPlaylistId, title = LIKED_SONGS_PLAYLIST_TITLE, trackCount = mergedTracks.size)
        publishLikedSongs(
            targetPlaylist.copy(trackCount = mergedTracks.size),
            mergedTracks,
        )
    }

    fun isTrackLiked(trackId: String): Boolean {
        if (trackId.isBlank()) return false
        val liked = mutableCatalog.value.playlists.firstOrNull { it.isLikedSongsPlaylist() } ?: return false
        return mutableCatalog.value.tracksByParent[liked.id].orEmpty().any { it.hasSamePlexIdentity(trackId) }
    }

    suspend fun toggleLikedTrack(session: PlexSession?, track: Track): Boolean {
        return toggleLikedTrackRemote(session, track)
    }

    suspend fun toggleLikedTrackLocally(session: PlexSession?, track: Track): Boolean {
        if (!track.canTogglePlexLike()) return false
        val playlist = ensureLocalLikedSongsPlaylist(session)
        val snapshot = mutableCatalog.value
        val existing = snapshot.tracksByParent[playlist.id].orEmpty()
        val isLiked = existing.any { it.hasSamePlexIdentity(track.id) }
        val updated = if (isLiked) {
            existing.filterNot { it.hasSamePlexIdentity(track.id) }
        } else {
            listOf(track) + existing
        }
        publishLikedSongs(playlist, updated)
        return !isLiked
    }

    suspend fun syncLikedSongsPlaylist(session: PlexSession?): Boolean {
        if (session.isEmbyFamily()) return true
        if (session?.supportsPlexPlaylists() != true) return false
        val remotePlaylist = mutableCatalog.value.playlists.firstOrNull {
            it.isLikedSongsPlaylist() && it.id != PENDING_LIKED_SONGS_PLAYLIST_ID
        }
        val localPlaylistBeforeFetch = mutableCatalog.value.playlists.firstOrNull { it.isLikedSongsPlaylist() } ?: return false
        val desiredTracksBeforeFetch = mutableCatalog.value.tracksByParent[localPlaylistBeforeFetch.id]
        if (remotePlaylist == null) {
            val desiredTracks = desiredTracksBeforeFetch.orEmpty()
            if (desiredTracks.isEmpty()) return false
            return createPlaylist(session, LIKED_SONGS_PLAYLIST_TITLE, desiredTracks) != null
        }

        val remoteTracks = runCatching {
            refetchPlaylistTracksFromPlex(session, remotePlaylist)
            mutableCatalog.value.tracksByParent[remotePlaylist.id].orEmpty()
        }.getOrElse {
            mutableCatalog.value.tracksByParent[remotePlaylist.id].orEmpty()
        }
        val desiredTracks = desiredTracksBeforeFetch ?: remoteTracks
        val desiredIds = desiredTracks.mapNotNull { it.plexIdentityKey() }.toSet()
        val toAdd = desiredTracks.filter { desired ->
            val desiredKey = desired.plexIdentityKey()
            desiredKey != null && remoteTracks.none { it.plexIdentityKey() == desiredKey }
        }
        val toRemove = remoteTracks.filter { remote ->
            remote.plexIdentityKey() !in desiredIds && remote.playlistItemId != null
        }
        if (toAdd.isNotEmpty()) {
            addTracksToPlaylist(session, remotePlaylist, toAdd)
        }
        toRemove.forEach { track ->
            removeTrackFromPlexPlaylist(session, remotePlaylist, track)
        }
        val mergedDesiredTracks = desiredTracks.map { desired ->
            remoteTracks.firstOrNull { it.id == desired.id }?.let { remote ->
                desired.copy(playlistItemId = remote.playlistItemId ?: desired.playlistItemId)
            } ?: desired
        }
        publishLikedSongs(remotePlaylist, mergedDesiredTracks)
        return true
    }

    suspend fun syncLikedTrackChange(
        session: PlexSession?,
        track: Track,
        liked: Boolean,
    ): Boolean {
        if (session != null && !session.isPlex()) {
            val server = session.selectedServer ?: return false
            val prefix = session.providerType.catalogPrefix
            val itemId = providerItemId(track.id, prefix) ?: return false
            return runCatching {
                val adapter = providerRegistry.adapterFor(session)
                if (adapter != null) {
                    adapter.setFavorite(session, itemId, liked, ProviderItemKind.Track)
                } else {
                    val userId = session.userId ?: return false
                    val remoteClient = if (prefix == "emby") embyClient else jellyfinClient
                    remoteClient.setFavorite(server, session.token, userId, itemId, liked)
                    true
                }
            }.onFailure { error ->
                PhoebeLog.d("CatalogRepository") { "Liked Songs ${session.providerType.name} favorite sync failed: ${error.message}" }
            }.getOrDefault(false)
        }
        if (session?.supportsPlexPlaylists() != true || !track.canTogglePlexLike()) return false
        val remotePlaylist = mutableCatalog.value.playlists.firstOrNull {
            it.isLikedSongsPlaylist() && it.id != PENDING_LIKED_SONGS_PLAYLIST_ID
        } ?: run {
            if (!liked) return false
            return createPlaylist(session, LIKED_SONGS_PLAYLIST_TITLE, listOf(track)) != null
        }
        return if (liked) {
            appendTracksToPlexPlaylistRemoteOnly(session, remotePlaylist, listOf(track))
        } else {
            val localLikedTrack = mutableCatalog.value.tracksByParent[remotePlaylist.id].orEmpty()
                .firstOrNull { it.hasSamePlexIdentity(track.id) }
            val removable = localLikedTrack?.takeIf { it.playlistItemId != null } ?: run {
                runCatching { refetchPlaylistTracksFromPlex(session, remotePlaylist) }
                mutableCatalog.value.tracksByParent[remotePlaylist.id].orEmpty()
                    .firstOrNull { it.hasSamePlexIdentity(track.id) && it.playlistItemId != null }
            }
            removable?.let { removeTrackFromPlexPlaylist(session, remotePlaylist, it) } ?: false
        }
    }

    private suspend fun appendTracksToPlexPlaylistRemoteOnly(
        session: PlexSession,
        playlist: Playlist,
        tracks: List<Track>,
    ): Boolean {
        val server = session.selectedServer ?: return false
        val token = session.serverAuthToken() ?: return false
        val playlistRating = plexRatingKey(playlist.id) ?: return false
        val ratingKeys = tracks.mapNotNull { plexRatingKey(it.id) }.distinct()
        if (ratingKeys.isEmpty()) return false
        val machineId = resolveMachineIdentifier(server, token)
        return runCatching {
            plexClient.addTracksToPlaylist(server, token, machineId, playlistRating, ratingKeys)
        }.onFailure { error ->
            PhoebeLog.d("CatalogRepository") { "Liked Songs delta add failed: ${error.message}" }
        }.isSuccess
    }

    private suspend fun publishLikedSongs(playlist: Playlist, tracks: List<Track>) {
        val snapshot = mutableCatalog.value
        val updatedPlaylist = playlist.copy(
            trackCount = tracks.size,
            thumbUrl = playlist.thumbUrl ?: tracks.firstNotNullOfOrNull { it.thumbUrl },
        )
        val nextPlaylists = listOf(updatedPlaylist) + snapshot.playlists.filterNot { it.isLikedSongsPlaylist() }
        val likedPlaylistIds = snapshot.playlists
            .filter { it.isLikedSongsPlaylist() }
            .map { it.id }
            .toSet() + PENDING_LIKED_SONGS_PLAYLIST_ID + playlist.id
        val nextTracks = snapshot.tracksByParent
            .filterKeys { parentId -> parentId !in likedPlaylistIds }
            .toMutableMap()
            .apply {
                put(playlist.id, tracks)
            }
        val updated = snapshot.copy(playlists = nextPlaylists, tracksByParent = nextTracks)
        publish(updated, persist = false)
        runCatalogDbWrite { persistPlaylistTracks(updated, playlist.id) }
    }

    suspend fun toggleLikedTrackRemote(session: PlexSession?, track: Track): Boolean {
        if (session != null && !session.isPlex()) {
            val liked = toggleLikedTrackLocally(session, track)
            syncLikedTrackChange(session, track, liked)
            return liked
        }
        if (!track.canTogglePlexLike()) return false
        val playlist = findOrCreateLikedSongsPlaylist(session) ?: return false
        val fresh = runCatching {
            refetchPlaylistTracksFromPlex(session, playlist)
            mutableCatalog.value.tracksByParent[playlist.id].orEmpty()
        }.getOrElse {
            mutableCatalog.value.tracksByParent[playlist.id].orEmpty()
        }
        val existing = fresh.firstOrNull { it.hasSamePlexIdentity(track.id) }
        return if (existing == null) {
            addTracksToPlaylist(session, playlist, listOf(track))
            true
        } else {
            !removeTrackFromPlexPlaylist(session, playlist, existing)
        }
    }

    suspend fun copyPlexPlaylistIntoPlaylist(
        session: PlexSession?,
        source: Playlist,
        target: Playlist,
    ): Int {
        if (source.id == target.id) return 0
        if (!source.id.startsWith("plex:") || !target.id.startsWith("plex:")) return 0
        if (session?.supportsPlexPlaylists() != true) return 0
        val sourceTracks = tracksForPlaylist(session, source)
            .filter { it.canAddToPlexPlaylist() }
        if (sourceTracks.isEmpty()) return 0
        val before = tracksForPlaylist(session, target).map { it.id }.toSet()
        val toCopy = sourceTracks.filterNot { it.id in before }
        if (toCopy.isEmpty()) return 0
        addTracksToPlaylist(session, target, toCopy)
        return toCopy.size
    }

    private suspend fun removeTrackFromPlexPlaylist(
        session: PlexSession?,
        playlist: Playlist,
        track: Track,
    ): Boolean {
        if (session?.supportsPlexPlaylists() != true) return false
        val server = session.selectedServer ?: return false
        val token = session.serverAuthToken() ?: return false
        val playlistRating = plexRatingKey(playlist.id) ?: return false
        val itemId = track.playlistItemId ?: return false
        return runCatching {
            plexClient.removePlaylistItems(server, token, playlistRating, listOf(itemId))
        }.onFailure { error ->
            PhoebeLog.d("CatalogRepository") { "removeTrackFromPlexPlaylist failed for '${playlist.title}': ${error.message}" }
        }.onSuccess {
            val snapshot = mutableCatalog.value
            val existing = snapshot.tracksByParent[playlist.id].orEmpty()
            val updated = existing.filterNot { it.id == track.id }
            publish(
                snapshot.copy(
                    tracksByParent = snapshot.tracksByParent + (playlist.id to updated),
                    playlists = snapshot.playlists.map {
                        if (it.id == playlist.id) it.copy(trackCount = updated.size) else it
                    },
                ),
                persist = true,
            )
        }.isSuccess
    }

    /**
     * Create a new **Plex** playlist (requires signed-in Plex with server + music library selected).
     * The playlist appears on other Plex clients; [initialTracks] must be Plex library tracks only.
     *
     * Returns the created [Playlist] (with the same id used in the in-memory snapshot) or
     * `null` if Plex creation failed or the session is not ready.
     */
    suspend fun createPlaylist(
        session: PlexSession?,
        title: String,
        initialTracks: List<Track> = emptyList(),
    ): Playlist? {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return null
        val s = session ?: return null
        if (!s.isPlex()) {
            val prefix = s.providerType.catalogPrefix
            if (!s.supportsRemotePlaylists()) return null
            if (initialTracks.any { it.isLocalMediaPlayback() || !it.belongsToProvider(s.providerType) }) return null
            val created = runCatching {
                val adapter = providerRegistry.adapterFor(s)
                if (adapter != null && !s.isEmbyFamily()) {
                    adapter.createPlaylist(s, cleanTitle, initialTracks)
                } else {
                    val server = s.selectedServer ?: return null
                    val userId = s.userId ?: return null
                    val seedIds = initialTracks.map { it.id.removePrefix("$prefix:") }
                    val remoteClient = if (prefix == "emby") embyClient else jellyfinClient
                    remoteClient.createPlaylist(server, s.token, userId, cleanTitle, seedIds)
                }
            }.onFailure { error ->
                PhoebeLog.d("CatalogRepository") { "create${s.providerType.name}Playlist '$cleanTitle' failed: ${error.message}" }
            }.getOrNull() ?: return null
            val prefixedPlaylist = created.copy(id = if (created.id.startsWith("$prefix:")) created.id else "$prefix:${created.id}")
            val snapshot = mutableCatalog.value
            val updated = snapshot.copy(
                playlists = snapshot.playlists.filterNot { it.id == prefixedPlaylist.id } + prefixedPlaylist,
                tracksByParent = if (initialTracks.isEmpty()) {
                    snapshot.tracksByParent
                } else {
                    snapshot.tracksByParent + (prefixedPlaylist.id to initialTracks)
                },
            )
            if (prefixedPlaylist.isLikedSongsPlaylist()) {
                publish(updated, persist = false)
                runCatalogDbWrite { persistPlaylistTracks(updated, prefixedPlaylist.id) }
            } else {
                publish(updated, persist = true)
            }
            return prefixedPlaylist
        }
        if (!s.supportsPlexPlaylists()) return null
        if (initialTracks.any { it.isLocalMediaPlayback() || !it.isPlexLibraryTrack() }) return null
        val server = s.selectedServer ?: return null
        val library = s.selectedLibrary ?: return null
        val token = s.serverAuthToken() ?: return null
        return createPlexPlaylist(server, library, token, cleanTitle, initialTracks)
    }

    /**
     * Create a playlist stored only in Phoebe. Only local audio files ([Track.isLocalMediaPlayback])
     * may be added as seeds.
     */
    suspend fun createLocalPlaylist(
        title: String,
        initialTracks: List<Track> = emptyList(),
    ): Playlist? {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return null
        if (initialTracks.any { !it.canAddToLocalPlaylist() }) return null
        val id = "$LOCAL_PLAYLIST_ID_PREFIX${(Random.nextLong() and Long.MAX_VALUE).toString(16)}"
        val playlist = Playlist(id = id, title = cleanTitle, trackCount = initialTracks.size)
        val snapshot = mutableCatalog.value
        publish(
            snapshot.copy(
                playlists = snapshot.playlists + playlist,
                tracksByParent = if (initialTracks.isEmpty()) {
                    snapshot.tracksByParent
                } else {
                    snapshot.tracksByParent + (id to initialTracks)
                },
            ),
            persist = true,
        )
        return playlist
    }

    suspend fun deletePlaylist(session: PlexSession?, playlist: Playlist): Boolean {
        if (playlist.isLikedSongsPlaylist()) return false
        if (playlist.isSmartPlaylist()) {
            userArtifactsRepository.deleteSmartPlaylist(playlist.id)
            deletePlaylistLocally(playlist.id)
            return true
        }
        if (playlist.isLocalPlaylist()) {
            deletePlaylistLocally(playlist.id)
            return true
        }
        val remotePrefix = playlist.remoteProviderPrefix() ?: return false
        if (remotePrefix != "plex") return false
        if (session?.supportsPlexPlaylists() != true) return false
        val server = session.selectedServer ?: return false
        val token = session.serverAuthToken() ?: return false
        val playlistRating = plexRatingKey(playlist.id) ?: return false
        val deleted = runCatching {
            plexClient.deletePlaylist(server, token, playlistRating)
        }.onFailure { error ->
            PhoebeLog.d("CatalogRepository") { "deletePlaylist Plex failed for '${playlist.title}': ${error.message}" }
        }.isSuccess
        if (deleted) deletePlaylistLocally(playlist.id)
        return deleted
    }

    suspend fun createProviderPlaylistFromSmartPlaylist(session: PlexSession?, playlist: Playlist): Playlist? {
        if (!playlist.isSmartPlaylist()) return null
        val s = session ?: return null
        if (!s.supportsRemotePlaylists()) return null
        val providerType = s.providerType
        val tracks = tracksForPlaylist(s, playlist)
            .filter { !it.isLocalMediaPlayback() && it.belongsToProvider(providerType) }
        if (tracks.isEmpty()) return null
        return createPlaylist(s, playlist.title, tracks)
    }

    private suspend fun deletePlaylistLocally(playlistId: String) {
        val snapshot = mutableCatalog.value
        val updated = snapshot.copy(
            playlists = snapshot.playlists.filterNot { it.id == playlistId },
            tracksByParent = snapshot.tracksByParent - playlistId,
        )
        publish(updated, persist = false)
        runCatalogDbWrite {
            database.catalogQueries.deleteTrackParentsForParent(playlistId)
            database.catalogQueries.deletePlaylistById(playlistId)
        }
    }

    private suspend fun createPlexPlaylist(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        title: String,
        initialTracks: List<Track>,
    ): Playlist? {
        // Plex only accepts its own (un-prefixed) rating keys; tracks not sourced from this
        // Plex server are silently dropped from the initial seed.
        val seedKeys = initialTracks.mapNotNull { plexRatingKey(it.id) }
        val machineId = resolveMachineIdentifier(server, token)
        val createdResult = runCatching {
            plexClient.createPlaylist(server, token, library, machineId, title, seedKeys)
        }
        val created = createdResult.getOrElse { error ->
            PhoebeLog.d("CatalogRepository") { "createPlexPlaylist '$title' failed: ${error.message}" }
            return null
        }
        val prefixedPlaylist = created.copy(
            id = "plex:${created.id}",
            trackCount = if (initialTracks.isEmpty()) 0 else created.trackCount,
        )
        val prefixedTracks = initialTracks.filter { plexRatingKey(it.id) != null }
        val snapshot = mutableCatalog.value
        val nextPlaylists = if (prefixedPlaylist.isLikedSongsPlaylist()) {
            listOf(prefixedPlaylist) + snapshot.playlists.filterNot { it.isLikedSongsPlaylist() }
        } else {
            snapshot.playlists.filterNot { it.id == prefixedPlaylist.id } + prefixedPlaylist
        }
        val nextTracks = if (prefixedTracks.isEmpty()) {
            if (prefixedPlaylist.isLikedSongsPlaylist()) {
                snapshot.tracksByParent - PENDING_LIKED_SONGS_PLAYLIST_ID
            } else {
                snapshot.tracksByParent
            }
        } else {
            (snapshot.tracksByParent - PENDING_LIKED_SONGS_PLAYLIST_ID) + (prefixedPlaylist.id to prefixedTracks)
        }
        val updated = snapshot.copy(playlists = nextPlaylists, tracksByParent = nextTracks)
        if (prefixedPlaylist.isLikedSongsPlaylist()) {
            publish(updated, persist = false)
            runCatalogDbWrite { persistPlaylistTracks(updated, prefixedPlaylist.id) }
        } else {
            publish(updated, persist = true)
        }
        return prefixedPlaylist
    }

    /**
     * Add [tracks] to an existing playlist, de-duplicating against existing entries unless
     * [allowDuplicates] is true.
     */
    suspend fun addTracksToPlaylist(
        session: PlexSession?,
        playlist: Playlist,
        tracks: List<Track>,
        allowDuplicates: Boolean = false,
    ) {
        PhoebeLog.d("CatalogRepository") { "addTracksToPlaylist entry → playlist='${playlist.title}' (${playlist.id}), tracks=${tracks.map { it.id }}" }
        if (tracks.isEmpty()) return
        if (playlist.isSmartPlaylist()) {
            PhoebeLog.d("CatalogRepository") { "addTracksToPlaylist: '${playlist.title}' is a read-only smart playlist" }
            return
        }
        if (playlist.isLocalPlaylist()) {
            addTracksToLocalPlaylist(playlist, tracks, allowDuplicates)
            return
        }
        val remotePrefix = playlist.remoteProviderPrefix()
        if (remotePrefix != null && remotePrefix != "plex") {
            if (session?.providerType?.catalogPrefix != remotePrefix || session.supportsRemotePlaylists() != true) {
                PhoebeLog.d("CatalogRepository") { "addTracksToPlaylist: ${remotePrefix} session not ready" }
                return
            }
            var snapshot = mutableCatalog.value
            val playlistMeta = snapshot.playlists.find { it.id == playlist.id } ?: playlist
            var existing = snapshot.tracksByParent[playlist.id].orEmpty()
            if (existing.size < playlistMeta.trackCount) {
                runCatching { refetchPlaylistTracksFromPlex(session, playlistMeta) }
                snapshot = mutableCatalog.value
                existing = snapshot.tracksByParent[playlist.id].orEmpty()
            }
            val existingIds = existing.map { it.id }.toHashSet()
            val toAdd = tracks
                .let { candidates -> if (allowDuplicates) candidates else candidates.filterNot { it.id in existingIds } }
                .filter { !it.isLocalMediaPlayback() && it.id.startsWith("$remotePrefix:") }
            if (toAdd.isEmpty()) return
            var remoteAddSucceeded = false
            runCatching {
                val adapter = providerRegistry.adapterFor(session)
                if (adapter != null && !session.isEmbyFamily()) {
                    adapter.addTracksToPlaylist(session, playlist, toAdd)
                } else {
                    val server = session.selectedServer ?: return
                    val userId = session.userId ?: return
                    val remoteClient = if (remotePrefix == "emby") embyClient else jellyfinClient
                    remoteClient.addTracksToPlaylist(
                        server = server,
                        token = session.token,
                        userId = userId,
                        playlistId = playlist.id.removePrefix("$remotePrefix:"),
                        itemIds = toAdd.map { it.id.removePrefix("$remotePrefix:") },
                    )
                }
            }.onSuccess {
                remoteAddSucceeded = true
            }.onFailure { error ->
                PhoebeLog.d("CatalogRepository") { "addTracksToPlaylist $remotePrefix failed for '${playlist.title}': ${error.message}" }
            }
            if (remoteAddSucceeded) {
                rememberPrependedPlaylistAdditions(playlist.id, toAdd.map { it.id })
            }
            val updated = toAdd + existing
            publish(
                snapshot.copy(
                    playlists = snapshot.playlists.map { if (it.id == playlist.id) it.copy(trackCount = updated.size) else it },
                    tracksByParent = snapshot.tracksByParent + (playlist.id to updated),
                ),
                persist = true,
            )
            return
        }
        if (!playlist.id.startsWith("plex:")) {
            PhoebeLog.d("CatalogRepository") { "addTracksToPlaylist: ignoring non-Plex playlist ${playlist.id}" }
            return
        }
        if (session?.supportsPlexPlaylists() != true) {
            PhoebeLog.d("CatalogRepository") { "addTracksToPlaylist: Plex session not ready" }
            return
        }
        val s = session
        var snapshot = mutableCatalog.value
        val playlistMeta = snapshot.playlists.find { it.id == playlist.id } ?: playlist
        var existing = snapshot.tracksByParent[playlist.id].orEmpty()
        // Playlist rows only carry trackCount from Plex metadata until the user opens the
        // playlist (or we refetch). Without this, appending onto an empty cache would
        // replace the whole list locally with just the dragged track.
        if (existing.size < playlistMeta.trackCount) {
            runCatching { refetchPlaylistTracksFromPlex(s, playlistMeta) }
            snapshot = mutableCatalog.value
            existing = snapshot.tracksByParent[playlist.id].orEmpty()
        }

        val existingIds = existing.map { it.id }.toHashSet()
        val toAdd = tracks
            .let { candidates -> if (allowDuplicates) candidates else candidates.filterNot { it.id in existingIds } }
            .filter { !it.isLocalMediaPlayback() && it.isPlexLibraryTrack() && plexRatingKey(it.id) != null }
        if (toAdd.isEmpty()) {
            PhoebeLog.d("CatalogRepository") { "addTracksToPlaylist: nothing to add after Plex filters, skipping" }
            return
        }

        val server = s.selectedServer
        val token = s.serverAuthToken()
        val playlistRating = plexRatingKey(playlist.id)
        val ratingKeys = toAdd.mapNotNull { plexRatingKey(it.id) }
        var remoteAddSucceeded = false
        PhoebeLog.d("CatalogRepository") { "plex branch: hasServer=${server != null}, hasToken=${token != null}, playlistRating=$playlistRating, ratingKeys=$ratingKeys" }
        if (server != null && token != null && playlistRating != null && ratingKeys.isNotEmpty()) {
            val machineId = resolveMachineIdentifier(server, token)
            PhoebeLog.d("CatalogRepository") { "resolved machineIdentifier='$machineId' (server.id was '${server.id}')" }
            runCatching {
                plexClient.addTracksToPlaylist(server, token, machineId, playlistRating, ratingKeys)
            }.onFailure { error ->
                PhoebeLog.d("CatalogRepository") { "addTracksToPlaylist failed for '${playlist.title}': ${error.message}" }
            }.onSuccess { result ->
                remoteAddSucceeded = true
                PhoebeLog.d("CatalogRepository") { "Plex sync OK for '${playlist.title}': leafCountAdded=$result" }
            }
        } else {
            PhoebeLog.d("CatalogRepository") { "skipping Plex sync — missing one of server/token/playlistRating/ratingKeys" }
        }
        if (remoteAddSucceeded) {
            rememberPrependedPlaylistAdditions(playlist.id, toAdd.map { it.id })
        }
        val syncedTracks = if (remoteAddSucceeded) {
            runCatching { syncAddedPlexTracksToTop(s, playlistMeta, toAdd, existing) }
                .onFailure { error ->
                    PhoebeLog.d("CatalogRepository") { "move added Plex playlist tracks failed for '${playlist.title}': ${error.message}" }
                }
                .getOrNull()
        } else {
            null
        }

        val canUpdateTrackList = syncedTracks != null || existing.isNotEmpty() || playlistMeta.trackCount == 0
        val newTrackCount = if (canUpdateTrackList) {
            syncedTracks?.size ?: (existing.size + toAdd.size)
        } else {
            playlistMeta.trackCount + toAdd.size
        }
        val updatedPlaylists = snapshot.playlists.map {
            if (it.id == playlist.id) it.copy(trackCount = newTrackCount) else it
        }
        val nextSnapshot = if (canUpdateTrackList) {
            snapshot.copy(
                tracksByParent = snapshot.tracksByParent + (playlist.id to (syncedTracks ?: (toAdd + existing))),
                playlists = updatedPlaylists,
            )
        } else {
            snapshot.copy(playlists = updatedPlaylists)
        }
        publish(nextSnapshot, persist = true)
    }

    suspend fun movePlaylistTrack(
        session: PlexSession?,
        playlist: Playlist,
        fromIndex: Int,
        toIndex: Int,
    ): Boolean {
        if (fromIndex == toIndex) return true
        if (playlist.isSmartPlaylist()) return false
        var snapshot = mutableCatalog.value
        val playlistId = playlist.id
        var existing = snapshot.tracksByParent[playlistId].orEmpty()
        if (playlistId.startsWith("plex:") && existing.any { it.playlistItemId == null }) {
            runCatching {
                refetchPlaylistTracksFromPlex(session, playlist, showRefreshing = false)
            }.onFailure { error ->
                PhoebeLog.d("CatalogRepository") { "movePlaylistTrack Plex refetch failed for '${playlist.title}': ${error.message}" }
            }
            snapshot = mutableCatalog.value
            existing = snapshot.tracksByParent[playlistId].orEmpty()
        }
        if (fromIndex !in existing.indices || toIndex !in existing.indices) return false

        val movedTrack = existing[fromIndex]
        val updated = existing.moved(fromIndex, toIndex)
        pendingPlaylistPrependedTrackIds.remove(playlistId)
        publish(
            snapshot.copy(
                playlists = snapshot.playlists.map {
                    if (it.id == playlistId) it.copy(trackCount = updated.size) else it
                },
                tracksByParent = snapshot.tracksByParent + (playlistId to updated),
            ),
            persist = false,
        )
        runCatalogDbWrite { persistPlaylistTracks(mutableCatalog.value, playlistId) }
        val synced = syncMovedPlaylistTrack(
            session = session,
            playlist = playlist,
            movedTrack = movedTrack,
            updatedTracks = updated,
        )
        if (!synced && !playlist.isLocalPlaylist()) {
            val current = mutableCatalog.value
            val originalPlaylist = snapshot.playlists.firstOrNull { it.id == playlistId }
            publish(
                current.copy(
                    playlists = current.playlists.map {
                        if (it.id == playlistId) originalPlaylist ?: it.copy(trackCount = existing.size) else it
                    },
                    tracksByParent = current.tracksByParent + (playlistId to existing),
                ),
                persist = false,
            )
            runCatalogDbWrite { persistPlaylistTracks(mutableCatalog.value, playlistId) }
            return false
        }
        return synced
    }

    suspend fun removeTracksFromPlaylist(
        session: PlexSession?,
        playlist: Playlist,
        tracks: List<Track>,
    ): Boolean {
        if (tracks.isEmpty()) return true
        if (playlist.isSmartPlaylist()) return false
        if (!playlist.supportsTrackRemoval()) return false
        val playlistId = playlist.id
        var snapshot = mutableCatalog.value
        var existing = snapshot.tracksByParent[playlistId].orEmpty()
        if (playlistId.startsWith("plex:") && tracks.any { it.playlistItemId == null }) {
            runCatching {
                refetchPlaylistTracksFromPlex(session, playlist, showRefreshing = false)
            }.onFailure { error ->
                PhoebeLog.d("CatalogRepository") {
                    "removeTracksFromPlaylist Plex refetch failed for '${playlist.title}': ${error.message}"
                }
            }
            snapshot = mutableCatalog.value
            existing = snapshot.tracksByParent[playlistId].orEmpty()
        }
        val removeKeys = tracks.map { it.playlistEntryKey() }.toSet()
        val toRemove = existing.filter { it.playlistEntryKey() in removeKeys }
        if (toRemove.isEmpty()) return false
        val updated = existing.filterNot { it.playlistEntryKey() in removeKeys }
        publish(
            snapshot.copy(
                playlists = snapshot.playlists.map {
                    if (it.id == playlistId) it.copy(trackCount = updated.size) else it
                },
                tracksByParent = snapshot.tracksByParent + (playlistId to updated),
            ),
            persist = false,
        )
        runCatalogDbWrite { persistPlaylistTracks(mutableCatalog.value, playlistId) }
        val synced = syncRemovedPlaylistTracks(session, playlist, toRemove, updated)
        if (!synced && !playlist.isLocalPlaylist()) {
            val current = mutableCatalog.value
            publish(
                current.copy(
                    playlists = current.playlists.map {
                        if (it.id == playlistId) snapshot.playlists.firstOrNull { p -> p.id == playlistId }?.copy(trackCount = existing.size)
                            ?: it.copy(trackCount = existing.size)
                        else it
                    },
                    tracksByParent = current.tracksByParent + (playlistId to existing),
                ),
                persist = false,
            )
            runCatalogDbWrite { persistPlaylistTracks(mutableCatalog.value, playlistId) }
            return false
        }
        if (playlist.isLocalPlaylist()) {
            publish(mutableCatalog.value, persist = true)
        }
        return synced
    }

    private suspend fun syncRemovedPlaylistTracks(
        session: PlexSession?,
        playlist: Playlist,
        removedTracks: List<Track>,
        updatedTracks: List<Track>,
    ): Boolean {
        if (playlist.isLocalPlaylist()) return true
        val remotePrefix = playlist.remoteProviderPrefix() ?: return false
        if (remotePrefix == "plex") {
            if (session?.supportsPlexPlaylists() != true) return false
            val server = session.selectedServer ?: return false
            val token = session.serverAuthToken() ?: return false
            val playlistRating = plexRatingKey(playlist.id) ?: return false
            val itemIds = removedTracks.mapNotNull { it.playlistItemId }
            if (itemIds.isEmpty()) return false
            return runCatching {
                plexClient.removePlaylistItems(server, token, playlistRating, itemIds)
            }.onFailure { error ->
                PhoebeLog.d("CatalogRepository") {
                    "removeTracksFromPlaylist Plex sync failed for '${playlist.title}': ${error.message}"
                }
            }.isSuccess
        }
        if (session?.providerType?.catalogPrefix != remotePrefix || session.supportsRemotePlaylists() != true) return false
        if (session.isEmbyFamily()) {
            val server = session.selectedServer ?: return false
            val userId = session.userId ?: return false
            val remoteClient = if (remotePrefix == "emby") embyClient else jellyfinClient
            val entryIds = removedTracks.map { track ->
                track.playlistItemId?.toString()
                    ?: track.id.removePrefix("$remotePrefix:")
            }
            return runCatching {
                remoteClient.removePlaylistItems(
                    server = server,
                    token = session.token,
                    userId = userId,
                    playlistId = playlist.id.removePrefix("$remotePrefix:"),
                    entryIds = entryIds,
                )
            }.onFailure { error ->
                PhoebeLog.d("CatalogRepository") {
                    "removeTracksFromPlaylist $remotePrefix sync failed for '${playlist.title}': ${error.message}"
                }
            }.isSuccess
        }
        return runCatching {
            providerRegistry.adapterFor(session)?.replacePlaylistTracks(session, playlist, updatedTracks) == true
        }.onFailure { error ->
            PhoebeLog.d("CatalogRepository") {
                "removeTracksFromPlaylist $remotePrefix sync failed for '${playlist.title}': ${error.message}"
            }
        }.getOrDefault(false)
    }

    private suspend fun syncMovedPlaylistTrack(
        session: PlexSession?,
        playlist: Playlist,
        movedTrack: Track,
        updatedTracks: List<Track>,
    ): Boolean {
        if (playlist.isLocalPlaylist()) return true
        val remotePrefix = playlist.remoteProviderPrefix() ?: return false
        if (remotePrefix == "plex") {
            if (session?.supportsPlexPlaylists() != true) return false
            val server = session.selectedServer ?: return false
            val token = session.serverAuthToken() ?: return false
            val playlistRating = plexRatingKey(playlist.id) ?: return false
            val movedIndex = updatedTracks.indexOfPlaylistEntry(movedTrack)
            if (movedIndex < 0) return false
            val movedItemId = movedTrack.playlistItemId ?: updatedTracks[movedIndex].playlistItemId ?: return false
            val afterItemId = updatedTracks.getOrNull(movedIndex - 1)?.playlistItemId
            val moved = runCatching {
                plexClient.movePlaylistItem(
                    server = server,
                    token = token,
                    playlistRatingKey = playlistRating,
                    playlistItemId = movedItemId,
                    afterPlaylistItemId = afterItemId,
                )
            }.onFailure { error ->
                PhoebeLog.d("CatalogRepository") { "movePlaylistTrack Plex sync failed for '${playlist.title}': ${error.message}" }
            }.isSuccess
            if (!moved) return false
            return true
        }
        if (session?.providerType?.catalogPrefix != remotePrefix || session.supportsRemotePlaylists() != true) return false
        if (session.isEmbyFamily()) {
            val server = session.selectedServer ?: return false
            val userId = session.userId ?: return false
            val movedIndex = updatedTracks.indexOfPlaylistEntry(movedTrack)
            if (movedIndex < 0) return false
            val remoteClient = if (remotePrefix == "emby") embyClient else jellyfinClient
            return runCatching {
                remoteClient.movePlaylistItem(
                    server = server,
                    token = session.token,
                    userId = userId,
                    playlistId = playlist.id.removePrefix("$remotePrefix:"),
                    itemId = movedTrack.id.removePrefix("$remotePrefix:"),
                    newIndex = movedIndex,
                )
            }.onFailure { error ->
                PhoebeLog.d("CatalogRepository") { "movePlaylistTrack $remotePrefix sync failed for '${playlist.title}': ${error.message}" }
            }.isSuccess
        }
        return runCatching {
            providerRegistry.adapterFor(session)?.replacePlaylistTracks(session, playlist, updatedTracks) == true
        }.onFailure { error ->
            PhoebeLog.d("CatalogRepository") { "movePlaylistTrack $remotePrefix sync failed for '${playlist.title}': ${error.message}" }
        }.getOrDefault(false)
    }

    private suspend fun syncAddedPlexTracksToTop(
        session: PlexSession,
        playlist: Playlist,
        addedTracks: List<Track>,
        existingTracks: List<Track>,
    ): List<Track>? {
        val server = session.selectedServer ?: return null
        val token = session.serverAuthToken() ?: return null
        val playlistRating = plexRatingKey(playlist.id) ?: return null
        val addedIds = addedTracks.map { it.id }.distinct()
        if (addedIds.isEmpty()) return null
        val fetched = plexClient.playlistTracks(server, playlist.copy(id = playlistRating), token)
            .map { it.withPlexPrefix() }
            .let { preserveTrackDateAdded(existingTracks, it) }
        if (addedIds.any { id -> fetched.none { it.id == id } }) return null

        val existingKeys = existingTracks.map { it.playlistEntryKey() }.toSet()
        val consumedItemIds = mutableSetOf<Long>()
        fun isUnconsumed(track: Track): Boolean {
            val playlistItemId = track.playlistItemId ?: return true
            return playlistItemId !in consumedItemIds
        }
        val addedFetchedTracks = addedTracks.mapNotNull { addedTrack ->
            val matched = fetched.lastOrNull { fetchedTrack ->
                fetchedTrack.id == addedTrack.id &&
                    isUnconsumed(fetchedTrack) &&
                    fetchedTrack.playlistEntryKey() !in existingKeys
            } ?: fetched.lastOrNull { fetchedTrack ->
                fetchedTrack.id == addedTrack.id &&
                    isUnconsumed(fetchedTrack)
            }
            matched?.playlistItemId?.let { consumedItemIds.add(it) }
            matched
        }
        addedFetchedTracks.asReversed().forEach { track ->
            val playlistItemId = track.playlistItemId ?: return@forEach
            runCatching {
                plexClient.movePlaylistItemToTop(server, token, playlistRating, playlistItemId)
            }.onFailure { error ->
                PhoebeLog.d("CatalogRepository") {
                    "movePlaylistItemToTop failed for '${playlist.title}' item=$playlistItemId: ${error.message}"
                }
            }
        }
        return moveTracksToFrontByPlaylistEntry(fetched, addedFetchedTracks)
    }

    private suspend fun addTracksToLocalPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        allowDuplicates: Boolean,
    ) {
        val snapshot = mutableCatalog.value
        val existing = snapshot.tracksByParent[playlist.id].orEmpty()
        val existingIds = existing.map { it.id }.toHashSet()
        val toAdd = tracks
            .let { candidates -> if (allowDuplicates) candidates else candidates.filterNot { it.id in existingIds } }
            .filter { it.canAddToLocalPlaylist() }
        if (toAdd.isEmpty()) return
        val updated = toAdd + existing
        publish(
            snapshot.copy(
                playlists = snapshot.playlists.map {
                    if (it.id == playlist.id) it.copy(trackCount = updated.size) else it
                },
                tracksByParent = snapshot.tracksByParent + (playlist.id to updated),
            ),
            persist = true,
        )
    }

    suspend fun updateTrackMetadata(
        session: PlexSession?,
        update: TrackMetadataUpdate,
    ): MetadataUpdateResult {
        val snapshot = mutableCatalog.value
        val existing = snapshot.tracksByParent.values.asSequence().flatten().firstOrNull { it.id == update.trackId }
            ?: return MetadataUpdateResult(savedLocally = false, plexAttempted = false, plexSynced = false)
        val cleanUpdate = update.copy(
            title = update.title.trim().ifBlank { existing.title },
            artist = update.artist.trim().ifBlank { existing.artist },
            album = update.album.trim().ifBlank { existing.album },
            albumArtist = update.albumArtist?.trim()?.takeIf { it.isNotBlank() },
            genre = update.genre?.trim()?.takeIf { it.isNotBlank() },
            mood = update.mood?.trim()?.takeIf { it.isNotBlank() },
            style = update.style?.trim()?.takeIf { it.isNotBlank() },
            composer = update.composer?.trim()?.takeIf { it.isNotBlank() },
            comments = update.comments?.trim()?.takeIf { it.isNotBlank() },
            titleSort = update.titleSort?.trim()?.takeIf { it.isNotBlank() },
            artistSort = update.artistSort?.trim()?.takeIf { it.isNotBlank() },
            albumSort = update.albumSort?.trim()?.takeIf { it.isNotBlank() },
        )

        var plexAttempted = false
        var plexSynced = false
        val rating = plexRatingKey(cleanUpdate.trackId)
        val remotePrefix = session?.providerType?.catalogPrefix
        val remoteItemId = remotePrefix?.let { providerItemId(cleanUpdate.trackId, it) }
        val server = session?.selectedServer
        val library = session?.selectedLibrary
        val token = session?.token?.takeIf { it.isNotBlank() }
        val hasPlexEditableChanges = cleanUpdate.title != existing.title || cleanUpdate.artist != existing.artist
        if (session.isEmbyFamily() && remoteItemId != null && server != null && token != null) {
            plexAttempted = true
            plexSynced = runCatching {
                val remoteClient = if (remotePrefix == "emby") embyClient else jellyfinClient
                remoteClient.editTrackMetadata(server, token, remoteItemId, existing, cleanUpdate)
            }.onFailure { error ->
                PhoebeLog.d("CatalogRepository") { "updateTrackMetadata ${session.providerType.name} sync failed for '${existing.title}': ${error.message}" }
            }.isSuccess
        } else
        if (hasPlexEditableChanges && rating != null && server != null && library != null && token != null) {
            plexAttempted = true
            plexSynced = runCatching {
                plexClient.editTrackMetadata(server, token, library, rating, existing, cleanUpdate)
            }.onFailure { error ->
                PhoebeLog.d("CatalogRepository") { "updateTrackMetadata Plex sync failed for '${existing.title}': ${error.message}" }
            }.isSuccess
        }

        val edited = existing.copy(
            title = cleanUpdate.title,
            artist = cleanUpdate.artist,
            album = cleanUpdate.album,
            year = cleanUpdate.year,
            genre = cleanUpdate.genre,
            albumArtist = cleanUpdate.albumArtist,
            mood = cleanUpdate.mood,
            style = cleanUpdate.style,
            trackNumber = cleanUpdate.trackNumber,
            discNumber = cleanUpdate.discNumber,
            composer = cleanUpdate.composer,
            comments = cleanUpdate.comments,
            explicit = cleanUpdate.explicit,
            titleSort = cleanUpdate.titleSort,
            artistSort = cleanUpdate.artistSort,
            albumSort = cleanUpdate.albumSort,
        )
        val updatedTracks = snapshot.tracksByParent.mapValues { (_, tracks) ->
            tracks.map { if (it.id == edited.id) edited else it }
        }
        publish(snapshot.copy(tracksByParent = updatedTracks), persist = true)
        val providerCanSync = plexSynced || (plexAttempted && session?.isEmbyFamily() == true)
        if (!providerCanSync) {
            userArtifactsRepository.upsertMetadataOverride(
                LocalMetadataOverride(
                    trackId = cleanUpdate.trackId,
                    update = cleanUpdate,
                    providerType = session?.providerType,
                    syncStatus = if (session == null || session.isPlex()) {
                        MetadataOverrideSyncStatus.LocalOnly
                    } else {
                        MetadataOverrideSyncStatus.ProviderUnsupported
                    },
                    updatedAtMs = currentTimeMs(),
                ),
            )
        }
        return MetadataUpdateResult(savedLocally = true, plexAttempted = plexAttempted, plexSynced = plexSynced)
    }

    suspend fun rateTrack(session: PlexSession?, track: Track, rating: Float?): RatingSyncResult {
        val normalized = rating.normalizedRating()
        val snapshot = mutableCatalog.value
        var changed = false
        val updatedTracks = snapshot.tracksByParent.mapValues { (_, tracks) ->
            tracks.map { existing ->
                if (existing.hasSamePlexIdentity(track.id)) {
                    changed = true
                    existing.copy(rating = normalized)
                } else {
                    existing
                }
            }
        }
        if (!changed) return RatingSyncResult(savedLocally = false, plexAttempted = false, plexSynced = false)
        publish(snapshot.copy(tracksByParent = updatedTracks), persist = true)
        return syncPlexRating(session, track.id, normalized, "track '${track.title}'").copy(savedLocally = true)
    }

    suspend fun rateArtist(session: PlexSession?, artist: Artist, rating: Float?): RatingSyncResult {
        val normalized = rating.normalizedRating()
        val snapshot = mutableCatalog.value
        var changed = false
        val artists = snapshot.artists.map {
            if (it.id == artist.id) {
                changed = true
                it.copy(rating = normalized)
            } else {
                it
            }
        }
        if (!changed) return RatingSyncResult(savedLocally = false, plexAttempted = false, plexSynced = false)
        publish(snapshot.copy(artists = artists), persist = true)
        return syncPlexRating(session, artist.id, normalized, "artist '${artist.title}'").copy(savedLocally = true)
    }

    suspend fun rateAlbum(session: PlexSession?, album: Album, rating: Float?): RatingSyncResult {
        val normalized = rating.normalizedRating()
        val snapshot = mutableCatalog.value
        var changed = false
        val albums = snapshot.albums.map {
            if (it.id == album.id) {
                changed = true
                it.copy(rating = normalized)
            } else {
                it
            }
        }
        if (!changed) return RatingSyncResult(savedLocally = false, plexAttempted = false, plexSynced = false)
        publish(snapshot.copy(albums = albums), persist = true)
        return syncPlexRating(session, album.id, normalized, "album '${album.title}'").copy(savedLocally = true)
    }

    suspend fun ratePlaylist(session: PlexSession?, playlist: Playlist, rating: Float?): RatingSyncResult {
        val normalized = rating.normalizedRating()
        val snapshot = mutableCatalog.value
        var changed = false
        val playlists = snapshot.playlists.map {
            if (it.id == playlist.id) {
                changed = true
                it.copy(rating = normalized)
            } else {
                it
            }
        }
        if (!changed) return RatingSyncResult(savedLocally = false, plexAttempted = false, plexSynced = false)
        publish(snapshot.copy(playlists = playlists), persist = true)
        return syncPlexRating(session, playlist.id, normalized, "playlist '${playlist.title}'").copy(savedLocally = true)
    }

    suspend fun toggleFavoriteArtist(session: PlexSession?, artist: Artist): FavoriteSyncResult {
        val snapshot = mutableCatalog.value
        var nextFavorite: Boolean? = null
        val artists = snapshot.artists.map {
            if (it.id == artist.id) {
                val favorite = !it.favorite
                nextFavorite = favorite
                it.copy(favorite = favorite)
            } else {
                it
            }
        }
        val favorite = nextFavorite ?: return FavoriteSyncResult()
        publish(snapshot.copy(artists = artists), persist = true)
        return syncPlexFavoriteCollection(
            session = session,
            id = artist.id,
            favorite = favorite,
            label = "artist '${artist.title}'",
            sync = { server, token, library, ratingKey ->
                plexClient.setFavoriteArtistCollection(server, token, library, ratingKey, favorite)
            },
        ).copy(favorite = favorite)
    }

    suspend fun toggleFavoriteAlbum(session: PlexSession?, album: Album): FavoriteSyncResult {
        val snapshot = mutableCatalog.value
        var nextFavorite: Boolean? = null
        val albums = snapshot.albums.map {
            if (it.id == album.id) {
                val favorite = !it.favorite
                nextFavorite = favorite
                it.copy(favorite = favorite)
            } else {
                it
            }
        }
        val favorite = nextFavorite ?: return FavoriteSyncResult()
        publish(snapshot.copy(albums = albums), persist = true)
        return syncPlexFavoriteCollection(
            session = session,
            id = album.id,
            favorite = favorite,
            label = "album '${album.title}'",
            sync = { server, token, library, ratingKey ->
                plexClient.setFavoriteAlbumCollection(server, token, library, ratingKey, favorite)
            },
        ).copy(favorite = favorite)
    }

    suspend fun toggleFavoritePlaylist(session: PlexSession?, playlist: Playlist): FavoriteSyncResult {
        val snapshot = mutableCatalog.value
        var nextFavorite: Boolean? = null
        val playlists = snapshot.playlists.map {
            if (it.id == playlist.id) {
                val favorite = !it.favorite
                nextFavorite = favorite
                it.copy(favorite = favorite)
            } else {
                it
            }
        }
        val favorite = nextFavorite ?: return FavoriteSyncResult()
        pendingPlaylistFavoriteOverrides.update { overrides -> overrides + (playlist.id to favorite) }
        publish(snapshot.copy(playlists = playlists), persist = true)
        return syncRemoteFavorite(session, playlist.id, favorite, "playlist '${playlist.title}'").copy(favorite = favorite)
    }

    fun favoritePlaylistsExport(): FavoritePlaylistsExport =
        FavoritePlaylistsExport(
            playlists = mutableCatalog.value.playlists
                .filter { it.favorite }
                .sortedBy { it.title.lowercase() }
                .map { playlist ->
                    FavoritePlaylistExportEntry(
                        id = playlist.id,
                        title = playlist.title,
                        key = playlist.key,
                    )
                },
        )

    suspend fun importFavoritePlaylists(export: FavoritePlaylistsExport): Int {
        val entries = export.playlists
        if (entries.isEmpty()) return 0
        val ids = entries.map { it.id }.toSet()
        val keys = entries.mapNotNull { it.key?.takeIf { key -> key.isNotBlank() } }.toSet()
        val titles = entries.map { it.title.normalizedFavoritePlaylistTitle() }.toSet()
        val snapshot = mutableCatalog.value
        var matched = 0
        val playlists = snapshot.playlists.map { playlist ->
            val matches = playlist.id in ids ||
                playlist.key?.let { it in keys } == true ||
                playlist.title.normalizedFavoritePlaylistTitle() in titles
            if (matches) {
                matched++
                playlist.copy(favorite = true)
            } else {
                playlist
            }
        }
        if (matched > 0) {
            publish(snapshot.copy(playlists = playlists), persist = true)
        }
        return matched
    }

    private suspend fun syncPlexRating(
        session: PlexSession?,
        id: String,
        rating: Float?,
        label: String,
    ): RatingSyncResult {
        val providerPrefix = session?.providerType?.catalogPrefix
        val remoteId = providerPrefix?.let { providerItemId(id, it) }
        if (session != null && !session.isPlex() && remoteId != null) {
            val server = session.selectedServer
            val token = session.token
            if (!session.supportsRemoteRatings() || server == null || token.isBlank()) {
                return RatingSyncResult(savedLocally = false, plexAttempted = false, plexSynced = false)
            }
            val synced = runCatching {
                val adapter = providerRegistry.adapterFor(session)
                if (adapter != null && !session.isEmbyFamily()) {
                    adapter.rateItem(session, remoteId, rating)
                } else {
                    val remoteClient = if (providerPrefix == "emby") embyClient else jellyfinClient
                    val userId = session.userId
                    if (userId != null) remoteClient.rateItem(server, token, userId, remoteId, rating)
                    else remoteClient.rateItem(server, token, remoteId, rating)
                    true
                }
            }.onFailure { error ->
                PhoebeLog.d("CatalogRepository") { "rateItem ${session.providerType.name} sync failed for $label: ${error.message}" }
            }.getOrDefault(false)
            return RatingSyncResult(savedLocally = false, plexAttempted = true, plexSynced = synced)
        }
        val ratingKey = plexRatingKey(id)
        val server = session?.selectedServer
        val token = session?.serverAuthToken()
        if (!session.supportsPlexRatings() || ratingKey == null || server == null || token == null) {
            return RatingSyncResult(savedLocally = false, plexAttempted = false, plexSynced = false)
        }
        val synced = runCatching {
            plexClient.rateItem(server, token, ratingKey, rating)
        }.onFailure { error ->
            PhoebeLog.d("CatalogRepository") { "rateItem Plex sync failed for $label: ${error.message}" }
        }.isSuccess
        return RatingSyncResult(savedLocally = false, plexAttempted = true, plexSynced = synced)
    }

    private suspend fun syncPlexFavoriteCollection(
        session: PlexSession?,
        id: String,
        favorite: Boolean,
        label: String,
        sync: suspend (PlexServer, String, MusicLibrary, String) -> Unit,
    ): FavoriteSyncResult {
        if (session != null && !session.isPlex()) {
            return syncRemoteFavorite(session, id, favorite, label)
        }
        val ratingKey = plexRatingKey(id)
        val server = session?.selectedServer
        val library = session?.selectedLibrary
        val token = session?.serverAuthToken()
        if (ratingKey == null || server == null || library == null || token == null) {
            return FavoriteSyncResult(favorite = favorite, plexAttempted = false, plexSynced = false)
        }
        val synced = runCatching {
            sync(server, token, library, ratingKey)
        }.onFailure { error ->
            PhoebeLog.d("CatalogRepository") { "favorite collection Plex sync failed for $label: ${error.message}" }
        }.isSuccess
        return FavoriteSyncResult(favorite = favorite, plexAttempted = true, plexSynced = synced)
    }

    private suspend fun syncRemoteFavorite(
        session: PlexSession?,
        id: String,
        favorite: Boolean,
        label: String,
    ): FavoriteSyncResult {
        val prefix = session?.providerType?.catalogPrefix ?: return FavoriteSyncResult(favorite = favorite)
        val itemId = providerItemId(id, prefix) ?: return FavoriteSyncResult(favorite = favorite)
        val server = session.selectedServer ?: return FavoriteSyncResult(favorite = favorite)
        val token = session.token.takeIf { it.isNotBlank() } ?: return FavoriteSyncResult(favorite = favorite)
        val synced = runCatching {
            val adapter = providerRegistry.adapterFor(session)
            if (adapter != null && !session.isEmbyFamily()) {
                adapter.setFavorite(session, itemId, favorite, providerItemKindFor(id))
            } else {
                val remoteClient = if (prefix == "emby") embyClient else jellyfinClient
                val userId = session.userId
                if (userId != null) remoteClient.setFavorite(server, token, userId, itemId, favorite)
                else remoteClient.setFavorite(server, token, itemId, favorite)
                true
            }
        }.onFailure { error ->
            PhoebeLog.d("CatalogRepository") { "favorite ${session.providerType.name} sync failed for $label: ${error.message}" }
        }.getOrDefault(false)
        return FavoriteSyncResult(favorite = favorite, plexAttempted = true, plexSynced = synced)
    }

    private fun providerItemKindFor(id: String): ProviderItemKind {
        val snapshot = mutableCatalog.value
        return when {
            snapshot.artists.any { it.id == id } -> ProviderItemKind.Artist
            snapshot.albums.any { it.id == id } -> ProviderItemKind.Album
            snapshot.playlists.any { it.id == id } -> ProviderItemKind.Playlist
            snapshot.tracksByParent.values.asSequence().flatten().any { it.id == id } -> ProviderItemKind.Track
            else -> ProviderItemKind.Unknown
        }
    }

    suspend fun download(track: Track): DownloadBatchResult =
        downloadTracks(listOf(track))

    suspend fun downloadAlbum(session: PlexSession?, album: Album): DownloadBatchResult =
        withContext(Dispatchers.Default) {
            val tracks = tracksForAlbum(session, album)
            coroutineScope {
                val artwork = async { downloadArtworkForAlbum(album) }
                val result = downloadTracks(tracks)
                artwork.await()
                result
            }
        }

    suspend fun downloadArtist(session: PlexSession?, artist: Artist): DownloadBatchResult =
        withContext(Dispatchers.Default) {
            ensureTracksForArtistAlbums(session, artist.title)
            val snapshot = mutableCatalog.value
            val albums = catalogAlbumsForArtist(snapshot, artist.title)
            val tracks = catalogTracksForArtist(snapshot, artist.title)
            coroutineScope {
                val artwork = async {
                    downloadArtworkForArtist(artist)
                    albums.forEach { album ->
                        downloadArtworkForAlbum(album)
                    }
                }
                val result = downloadTracks(tracks)
                artwork.await()
                result
            }
        }

    suspend fun downloadPlaylist(session: PlexSession?, playlist: Playlist): DownloadBatchResult =
        withContext(Dispatchers.Default) {
            val tracks = tracksForPlaylist(session, playlist)
            awaitCatalogDbWrites()
            val refreshedPlaylist = mutableCatalog.value.playlists.firstOrNull { it.id == playlist.id } ?: playlist
            coroutineScope {
                val artwork = async { downloadArtworkForPlaylist(refreshedPlaylist) }
                val result = downloadTracks(tracks)
                artwork.await()
                result
            }
        }

    suspend fun previewQueuedDownloadsForPlaylist(playlist: Playlist): Int =
        previewQueuedDownloadsForTracks(mutableCatalog.value.tracksByParent[playlist.id].orEmpty())

    suspend fun previewQueuedDownloadsForTracks(tracks: List<Track>): Int = withContext(Dispatchers.Default) {
        val uniqueTracks = tracks.mergeDownloadCopiesById()
        if (uniqueTracks.isEmpty()) return@withContext 0
        downloadStatusMutex.withLock {
            val existingDownloadsByTrackId = currentDownloadItems().associateBy { it.trackId }
            val items = uniqueTracks
                .filter { track ->
                    val existing = existingDownloadsByTrackId[track.id]
                    existing?.state != DownloadState.Complete &&
                        existing?.state != DownloadState.Downloading &&
                        existing?.state != DownloadState.Queued &&
                        track.localUri.isNullOrBlank()
                }
                .map { track -> track.toDownloadItem(DownloadState.Queued, progress = 0f) }
            if (items.isEmpty()) {
                0
            } else {
                publishDownloadItems(
                    items = items,
                    persist = false,
                    syncCatalog = false,
                )
                items.size
            }
        }
    }

    suspend fun resumeQueuedDownloads(): DownloadBatchResult = withContext(Dispatchers.Default) {
        downloadMutex.withLock {
            val queuedItems = database.downloadsQueries.selectActiveQueue()
                .awaitAsList()
                .map { row ->
                    row.toDownloadItem().copy(
                        state = DownloadState.Queued,
                        progress = 0f,
                        downloadedBytes = 0L,
                        totalBytes = null,
                        error = null,
                        updatedAtMs = currentTimeMs(),
                    )
                }
            if (queuedItems.isEmpty()) return@withLock DownloadBatchResult()
            publishDownloadItems(queuedItems, persist = true, syncCatalog = false)
            val tracks = queuedItems.map { item ->
                Track(
                    id = item.trackId,
                    title = item.title,
                    artist = item.artist,
                    album = "",
                    durationMs = 0L,
                    streamUrl = "",
                    downloadUrl = item.downloadUrl,
                    filepath = item.targetPath.takeIf { it.isNotBlank() },
                )
            }
            val completed = downloadTracksContinuously(
                tracks = tracks,
                parallelism = downloadParallelism().coerceAtLeast(1),
            )
            val reconciledCompleted = reconcileCompletedDownloadRowsForTrackIds(
                tracks.mapTo(mutableSetOf()) { it.id },
            )
            syncCatalogDownloadItems()
            val completedCount = maxOf(completed, reconciledCompleted)
            buildDownloadBatchResult(
                label = "resumeQueuedDownloads",
                total = tracks.size,
                completed = completedCount,
                failed = (tracks.size - completedCount).coerceAtLeast(0),
                failedTrackIds = tracks.mapTo(mutableSetOf()) { it.id },
            )
        }
    }

    suspend fun retryFailedDownloads(trackIds: Set<String> = emptySet()): DownloadBatchResult = withContext(Dispatchers.Default) {
        downloadMutex.withLock {
            val failedItems = currentDownloadItems()
                .filter { it.state == DownloadState.Failed }
                .filter { trackIds.isEmpty() || it.trackId in trackIds }
                .filter { it.downloadUrl.isNotBlank() }
                .map {
                    it.copy(
                        state = DownloadState.Queued,
                        progress = 0f,
                        downloadedBytes = 0L,
                        totalBytes = null,
                        error = null,
                        updatedAtMs = currentTimeMs(),
                    )
                }
            if (failedItems.isEmpty()) return@withLock DownloadBatchResult()
            publishDownloadItems(failedItems, persist = true, syncCatalog = false)
            val tracks = failedItems.map { item ->
                Track(
                    id = item.trackId,
                    title = item.title,
                    artist = item.artist,
                    album = "",
                    durationMs = 0L,
                    streamUrl = "",
                    downloadUrl = item.downloadUrl,
                    filepath = item.targetPath.takeIf { it.isNotBlank() },
                )
            }
            val completed = downloadTracksContinuously(
                tracks = tracks,
                parallelism = downloadParallelism().coerceAtLeast(1),
            )
            syncCatalogDownloadItems()
            buildDownloadBatchResult(
                label = "retryFailedDownloads",
                total = tracks.size,
                completed = completed,
                failed = (tracks.size - completed).coerceAtLeast(0),
                failedTrackIds = tracks.mapTo(mutableSetOf()) { it.id },
            )
        }
    }

    suspend fun cancelDownloadsWithoutDeleting(trackIds: Set<String>): Int {
        if (trackIds.isEmpty()) return 0
        downloadCancellationMutex.withLock {
            canceledDownloadTrackIds += trackIds
        }
        val canceled = currentDownloadItems()
            .filter { it.trackId in trackIds && it.state in setOf(DownloadState.Queued, DownloadState.Downloading) }
            .map {
                it.copy(
                    state = DownloadState.Failed,
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = null,
                    error = "Download cancelled.",
                    updatedAtMs = currentTimeMs(),
                )
            }
        publishDownloadItems(canceled, persist = true, syncCatalog = true)
        return canceled.size
    }

    suspend fun deleteCompletedDownloads(): Int {
        val completedIds = currentDownloadItems()
            .filter { it.state == DownloadState.Complete }
            .mapTo(mutableSetOf()) { it.trackId }
        if (completedIds.isEmpty()) return 0
        return deleteDownloadsForTrackIds(completedIds)
    }

    suspend fun clearFailedDownloads(): Int {
        val failedIds = currentDownloadItems()
            .filter { it.state == DownloadState.Failed }
            .mapTo(mutableSetOf()) { it.trackId }
        clearDownloadItemsForTrackIds(failedIds)
        return failedIds.size
    }

    fun downloadManagerSummary(): DownloadManagerSummary {
        val downloads = currentDownloadItems()
        val totalBytes = downloads
            .mapNotNull { it.totalBytes }
            .takeIf { it.isNotEmpty() }
            ?.sum()
        return DownloadManagerSummary(
            activeCount = downloads.count { it.state == DownloadState.Queued || it.state == DownloadState.Downloading },
            completeCount = downloads.count { it.state == DownloadState.Complete },
            failedCount = downloads.count { it.state == DownloadState.Failed },
            totalCount = downloads.size,
            completedBytes = downloads.filter { it.state == DownloadState.Complete }.sumOf { it.downloadedBytes },
            totalBytes = totalBytes,
        )
    }

    suspend fun deleteAllDownloads(): Int {
        val downloads = currentDownloadItems()
        if (downloads.isEmpty()) return 0
        return deleteDownloadsForTrackIds(downloads.map { it.trackId }.toSet())
    }

    suspend fun deleteDownloadsForTracks(tracks: List<Track>): Int {
        val trackIds = tracks.map { it.id }.toSet()
        if (trackIds.isEmpty()) return 0
        return deleteDownloadsForTrackIds(trackIds, tracks)
    }

    suspend fun deleteDownloadsForTrackIds(trackIds: Set<String>): Int {
        if (trackIds.isEmpty()) return 0
        return deleteDownloadsForTrackIds(trackIds, emptyList())
    }

    suspend fun cancelDownloadsForTracks(tracks: List<Track>) {
        val trackIds = tracks.mapTo(mutableSetOf()) { it.id }
        if (trackIds.isEmpty()) return
        downloadCancellationMutex.withLock {
            canceledDownloadTrackIds += trackIds
        }
        clearTransientDownloadItemsForTrackIds(trackIds)
    }

    private suspend fun deleteDownloadsForTrackIds(
        trackIds: Set<String>,
        requestedTracks: List<Track> = emptyList(),
    ): Int {
        val plan = buildDownloadDeletePlan(trackIds, requestedTracks)
        if (plan.deletedIds.isEmpty()) return 0
        applyDownloadDeletePlanToUi(plan)
        downloadMutex.withLock {
            performDownloadDeletePlanCleanup(plan)
        }
        return plan.deletedIds.size
    }

    private suspend fun buildDownloadDeletePlan(
        trackIds: Set<String>,
        requestedTracks: List<Track>,
    ): DownloadDeletePlan {
        val snapshot = mutableCatalog.value
        val downloads = currentDownloadItems()
        val itemsToDelete = downloads.filter { it.trackId in trackIds }
        val catalogTracksToDelete = snapshot.tracksByParent.values
            .asSequence()
            .flatten()
            .filter { it.id in trackIds }
            .toList()
        val databaseTracksToDelete = trackIds
            .chunked(500)
            .flatMap { ids ->
                database.catalogQueries
                    .selectTracksByIds(ids)
                    .awaitAsList()
                    .map { it.toTrack() }
            }
        val localUrisByTrackId = linkedMapOf<String, MutableSet<String>>()

        fun addLocalUri(trackId: String, uri: String?) {
            val normalized = uri?.takeIf { it.isNotBlank() } ?: return
            localUrisByTrackId.getOrPut(trackId) { linkedSetOf() } += normalized
        }

        fun addTrackDownloadUri(track: Track) {
            if (track.downloadUrl.isNotBlank() || track.isRemoteLibraryTrack()) {
                addLocalUri(track.id, track.localUri)
            }
        }

        itemsToDelete.forEach { item -> addLocalUri(item.trackId, item.localUri) }
        requestedTracks.forEach(::addTrackDownloadUri)
        catalogTracksToDelete.forEach(::addTrackDownloadUri)
        databaseTracksToDelete.forEach(::addTrackDownloadUri)

        val deletedIds = (
            itemsToDelete.map { it.trackId } +
                localUrisByTrackId.keys
            )
            .toSet()
        val clearedRows = (requestedTracks + catalogTracksToDelete + databaseTracksToDelete)
            .distinctBy { it.id }
            .filter { it.id in deletedIds }
            .map { it.copy(localUri = null, localArtworkUri = null) }
        return DownloadDeletePlan(
            deletedIds = deletedIds,
            localUris = localUrisByTrackId.values.flatten().distinct(),
            targetPaths = itemsToDelete
                .mapNotNull { item -> item.targetPath.takeIf { it.isNotBlank() && item.localUri.isNullOrBlank() } }
                .distinct(),
            artworkUris = snapshot.tracksByParent.values
                .asSequence()
                .flatten()
                .distinctBy { it.id }
                .filter { it.id in deletedIds }
                .mapNotNull { it.localArtworkUri }
                .distinct()
                .toList(),
            clearedRows = clearedRows,
        )
    }

    private suspend fun applyDownloadDeletePlanToUi(plan: DownloadDeletePlan) {
        downloadStatusMutex.withLock {
            val snapshot = mutableCatalog.value
            val remainingDownloads = currentDownloadItems().filterNot { it.trackId in plan.deletedIds }
            val updatedTracks = snapshot.tracksByParent.mapValues { (_, tracks) ->
                tracks.map { track ->
                    if (track.id in plan.deletedIds) track.copy(localUri = null, localArtworkUri = null) else track
                }
            }
            replaceDownloadSnapshot(remainingDownloads, syncCatalog = false)
            mutableCatalog.value = snapshot.copy(
                tracksByParent = updatedTracks,
                downloads = remainingDownloads,
            )
            mutableDownloadEvents.tryEmit(DownloadStatusEvent(removedTrackIds = plan.deletedIds))
        }
    }

    private suspend fun performDownloadDeletePlanCleanup(plan: DownloadDeletePlan) {
        plan.localUris.forEach { uri ->
            runCatching { storage.deleteUri(uri) }
                .onFailure { error ->
                    PhoebeLog.d("CatalogRepository") { "download delete failed: ${error.message}" }
                }
        }
        plan.targetPaths.forEach { targetPath ->
            runCatching { storage.delete(targetPath) }
                .onFailure { error ->
                    PhoebeLog.d("CatalogRepository") { "partial download delete failed: ${error.message}" }
                }
        }
        plan.artworkUris.forEach { uri ->
            runCatching { storage.deleteUri(uri) }
                .onFailure { error ->
                    PhoebeLog.d("CatalogRepository") { "artwork delete failed: ${error.message}" }
                }
        }
        persistTrackRows(plan.clearedRows)
        runCatching {
            withContext(Dispatchers.Default) {
                runCatalogDbWrite {
                    database.transaction {
                        plan.deletedIds.forEach { database.downloadsQueries.delete(it) }
                    }
                }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            PhoebeLog.d("CatalogRepository") { "download row delete failed: ${error.message}" }
        }
    }

    suspend fun cacheDownloadedArtwork(): Int = downloadMutex.withLock {
        val snapshot = mutableCatalog.value
        val downloadedIds = currentDownloadItems()
            .asSequence()
            .filter { it.state == DownloadState.Complete && !it.localUri.isNullOrBlank() }
            .map { it.trackId }
            .toSet()
        if (downloadedIds.isEmpty()) return@withLock 0

        val tracks = snapshot.tracksByParent.values
            .asSequence()
            .flatten()
            .distinctBy { it.id }
            .filter { it.id in downloadedIds && it.thumbUrl?.isRemoteArtworkUrl() == true && it.localArtworkUri == null }
            .toList()
        var cached = 0
        tracks.forEach { track ->
            val artworkUri = downloadArtworkForTrack(track) ?: return@forEach
            updateTrackArtworkInfo(track.id, artworkUri)
            cached++
        }
        val trackIds = tracks.map { it.id }.toSet()
        val playlists = snapshot.playlists
            .filter { playlist ->
                playlist.thumbUrl?.isRemoteArtworkUrl() == true &&
                    snapshot.tracksByParent[playlist.id].orEmpty().any { it.id in trackIds }
            }
        playlists.forEach { playlist ->
            downloadArtworkForPlaylist(playlist) ?: return@forEach
            cached++
        }
        cached
    }

    suspend fun downloadTracks(tracks: List<Track>): DownloadBatchResult = withContext(Dispatchers.Default) {
        downloadMutex.withLock {
            val uniqueTracks = tracks.mergeDownloadCopiesById()
            if (uniqueTracks.isEmpty()) return@withLock DownloadBatchResult()
            val uniqueTrackIds = uniqueTracks.mapTo(mutableSetOf()) { it.id }
            downloadCancellationMutex.withLock {
                canceledDownloadTrackIds -= uniqueTrackIds
            }

            val existingDownloadsByTrackId = currentDownloadItems().associateBy { it.trackId }
            val downloadedTracksByTrackId = downloadedRemoteTracksForTrackIds(uniqueTrackIds).associateBy { it.id }
            val offlineTracks = uniqueTracks.mapNotNull { track ->
                val existingDownload = existingDownloadsByTrackId[track.id]
                val downloadedTrack = downloadedTracksByTrackId[track.id]
                val existingLocalUri = track.localUri
                    ?: existingDownload?.localUri?.takeIf { it.isNotBlank() }
                    ?: downloadedTrack?.localUri
                val isAlreadyDownloaded = existingLocalUri != null || existingDownload?.state == DownloadState.Complete
                if (!isAlreadyDownloaded) return@mapNotNull null
                track.copy(
                    localUri = existingLocalUri ?: track.localUri,
                    localArtworkUri = track.localArtworkUri ?: downloadedTrack?.localArtworkUri,
                )
            }
            val offlineTrackIds = offlineTracks.mapTo(mutableSetOf()) { it.id }
            updateTracksOfflineInfo(offlineTracks)
            publishDownloadItems(
                offlineTracks.map { track ->
                    track.toDownloadItem(DownloadState.Complete, progress = 1f)
                },
                syncCatalog = false,
            )

            val pendingTracks = uniqueTracks.filter { it.id !in offlineTrackIds }
            val downloadable = pendingTracks.filter { it.downloadUrl.isNotBlank() }
            val skippedBeforeStart = pendingTracks.filter { it.downloadUrl.isBlank() }
            clearDownloadItemsForTrackIds(skippedBeforeStart.mapTo(mutableSetOf()) { it.id })
            publishDownloadItems(
                downloadable.map { track ->
                    track.toDownloadItem(DownloadState.Queued, progress = 0f)
                },
                persist = true,
                syncCatalog = false,
            )
            if (downloadable.isNotEmpty()) {
                yield()
            }

            var completed = offlineTrackIds.size
            val downloadableTrackIds = downloadable.mapTo(mutableSetOf()) { it.id }
            val downloadedCount = downloadTracksContinuously(
                tracks = downloadable,
                parallelism = downloadParallelism().coerceAtLeast(1),
            )
            val reconciledDownloadedCount = reconcileCompletedDownloadRowsForTrackIds(downloadableTrackIds)
            syncCatalogDownloadItems()
            val completedDownloadableCount = maxOf(downloadedCount, reconciledDownloadedCount)
            completed += completedDownloadableCount
            val failed = (downloadable.size - completedDownloadableCount).coerceAtLeast(0)
            buildDownloadBatchResult(
                label = "downloadTracks",
                total = uniqueTracks.size,
                completed = completed,
                failed = failed,
                failedTrackIds = downloadableTrackIds,
            )
        }
    }

    private suspend fun buildDownloadBatchResult(
        label: String,
        total: Int,
        completed: Int,
        failed: Int,
        failedTrackIds: Set<String>,
    ): DownloadBatchResult {
        val diagnostics = if (failed > 0) {
            downloadStatusMutex.withLock {
                downloadFailureDiagnosticsForTrackIds(failedTrackIds, expectedFailed = failed)
            }
        } else {
            DownloadFailureDiagnostics()
        }
        if (diagnostics.hasFailures) {
            logDownloadFailureDiagnostics(label, diagnostics)
        }
        return DownloadBatchResult(
            total = total,
            completed = completed,
            failed = failed,
            failureReasons = diagnostics.reasons,
            failedSamples = diagnostics.samples,
        )
    }

    private fun downloadFailureDiagnosticsForTrackIds(
        trackIds: Set<String>,
        expectedFailed: Int,
    ): DownloadFailureDiagnostics {
        val failedItems = currentDownloadItems()
            .asSequence()
            .filter { item -> item.trackId in trackIds && item.state == DownloadState.Failed }
            .toList()
        val reasonCounts = failedItems
            .groupingBy { item -> item.error.normalizedDownloadFailureReason() }
            .eachCount()
            .map { (reason, count) -> DownloadFailureReason(reason, count) }
            .sortedWith(compareByDescending<DownloadFailureReason> { it.count }.thenBy { it.reason })
            .toMutableList()
        val missingFailedRows = (expectedFailed - failedItems.size).coerceAtLeast(0)
        if (missingFailedRows > 0) {
            reasonCounts += DownloadFailureReason("No failed row persisted", missingFailedRows)
        }
        val samples = failedItems
            .take(DownloadFailureSampleLimit)
            .map { item ->
                DownloadFailureSample(
                    trackId = item.trackId,
                    title = item.title,
                    artist = item.artist,
                    error = item.error.normalizedDownloadFailureReason(),
                    sourceUrl = item.downloadUrl.redactedDownloadUrlForDiagnostics(),
                    targetPath = item.targetPath,
                )
            }
        return DownloadFailureDiagnostics(
            reasons = reasonCounts,
            samples = samples,
        )
    }

    private fun logDownloadFailureDiagnostics(label: String, diagnostics: DownloadFailureDiagnostics) {
        val reasonSummary = diagnostics.reasons.joinToString("; ") { reason ->
            "${reason.count} x ${reason.reason}"
        }
        PhoebeLog.d("CatalogRepository") { "$label failed downloads: $reasonSummary" }
        diagnostics.samples.take(DownloadFailureLogLimit).forEach { sample ->
            PhoebeLog.d("CatalogRepository") {
                "$label failed track id=${sample.trackId} title='${sample.title}' artist='${sample.artist}' " +
                    "error='${sample.error}' url='${sample.sourceUrl}' target='${sample.targetPath}'"
            }
        }
    }

    private fun String?.normalizedDownloadFailureReason(): String =
        this
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(180)
            ?: "Unknown failure"

    private fun String.redactedDownloadUrlForDiagnostics(): String =
        substringBefore('?')
            .take(240)

    private suspend fun reconcileCompletedDownloadRowsForTrackIds(trackIds: Set<String>): Int {
        if (trackIds.isEmpty()) return 0
        val completedTracks = downloadedRemoteTracksForTrackIds(trackIds)
        if (completedTracks.isEmpty()) return 0
        downloadStatusMutex.withLock {
            publishDownloadItems(
                items = completedTracks.map { track ->
                    track.toDownloadItem(DownloadState.Complete, progress = 1f)
                },
                persist = true,
                syncCatalog = false,
                publishSnapshot = false,
            )
        }
        return completedTracks.size
    }

    private suspend fun downloadedRemoteTracksForTrackIds(trackIds: Set<String>): List<Track> {
        if (trackIds.isEmpty()) return emptyList()
        val snapshotTracks = mutableCatalog.value.tracksByParent.values
            .asSequence()
            .flatten()
            .filter { track -> track.id in trackIds && track.hasDownloadedRemoteMedia() }
            .toList()
        val databaseTracks = trackIds
            .chunked(500)
            .flatMap { ids ->
                database.catalogQueries
                    .selectTracksByIds(ids)
                    .awaitAsList()
                    .map { it.toTrack() }
            }
            .filter { track -> track.hasDownloadedRemoteMedia() }
        return (snapshotTracks + databaseTracks)
            .distinctBy { it.id }
    }

    private fun Track.hasDownloadedRemoteMedia(): Boolean =
        !localUri.isNullOrBlank() && (downloadUrl.isNotBlank() || isRemoteLibraryTrack())

    private suspend fun downloadTracksContinuously(
        tracks: List<Track>,
        parallelism: Int,
    ): Int = coroutineScope {
        if (tracks.isEmpty()) return@coroutineScope 0

        val workerCount = parallelism.coerceAtLeast(1).coerceAtMost(tracks.size)
        val pendingTracks = Channel<Track>(capacity = workerCount * 2)
        val completedDownloads = Channel<Track>(capacity = workerCount)
        val completionCollector = async {
            var completedCount = 0
            val persistBatch = mutableListOf<Track>()
            val catalogBatch = mutableListOf<Track>()

            suspend fun flushPersistBatch() {
                if (persistBatch.isEmpty()) return
                val batch = persistBatch.toList()
                persistTrackRows(batch)
                persistCompletedDownloads(batch, publishSnapshot = false)
                persistBatch.clear()
            }

            suspend fun flushCatalogBatch() {
                if (catalogBatch.isEmpty()) return
                val batch = catalogBatch.toList()
                updateTracksOfflineInfo(batch, persistRows = false)
                catalogBatch.clear()
            }

            for (track in completedDownloads) {
                completedCount++
                persistBatch += track
                catalogBatch += track
                if (persistBatch.size >= DownloadCompletionPersistBatchSize) {
                    flushPersistBatch()
                }
                if (catalogBatch.size >= DownloadCatalogPublishBatchSize) {
                    flushCatalogBatch()
                }
            }
            flushPersistBatch()
            flushCatalogBatch()
            completedCount
        }
        val workers = List(workerCount) {
            launch {
                for (track in pendingTracks) {
                    currentCoroutineContext().ensureActive()
                    downloadTrackToOfflineStorage(track)?.let { downloaded ->
                        completedDownloads.send(downloaded)
                    }
                }
            }
        }

        tracks.forEach { track -> pendingTracks.send(track) }
        pendingTracks.close()
        workers.joinAll()
        completedDownloads.close()
        completionCollector.await()
    }

    private suspend fun downloadTrackToOfflineStorage(track: Track): Track? {
        ensureDownloadNotCancelled(track.id)
        updateDownload(track, DownloadState.Downloading, progress = 0.05f, persist = false)
        return runCatching {
            val localUri = downloadAudioForTrack(track)
            ensureDownloadNotCancelled(track.id)
            val offlineTrack = track.copy(localUri = localUri)
            completeDownload(offlineTrack)
            offlineTrack
        }.fold(
            onSuccess = { it },
            onFailure = { error ->
                if (error is CancellationException) throw error
                val failureMessage = error.downloadFailureMessage()
                PhoebeLog.d("CatalogRepository") {
                    "download failed trackId=${track.id} title='${track.title}' " +
                        "url='${track.downloadUrl.redactedDownloadUrlForDiagnostics()}' " +
                        "target='${downloadPathFor(track)}' error='$failureMessage'"
                }
                updateDownload(track, DownloadState.Failed, progress = 0f, errorMessage = failureMessage)
                null
            },
        )
    }

    private fun Throwable.downloadFailureMessage(): String =
        message
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(180)
            ?: "Unknown failure"

    private suspend fun ensureDownloadNotCancelled(trackId: String) {
        currentCoroutineContext().ensureActive()
        val cancelled = downloadCancellationMutex.withLock { trackId in canceledDownloadTrackIds }
        if (cancelled) throw CancellationException("Download cancelled.")
    }

    private suspend fun downloadAudioForTrack(track: Track): String {
        ensureDownloadNotCancelled(track.id)
        var lastProgress = 0.05f
        var lastProgressBytes = 0L
        var lastBytesAtMs = currentTimeMs()

        suspend fun publishProgress(downloadedBytes: Long, totalBytes: Long?) {
            ensureDownloadNotCancelled(track.id)
            lastBytesAtMs = currentTimeMs()
            val progress = if (totalBytes != null) {
                (downloadedBytes.toDouble() / totalBytes.toDouble())
                    .toFloat()
                    .coerceIn(0.05f, 0.99f)
            } else {
                (downloadedBytes.toDouble() / DownloadUnknownTotalProgressBytes.toDouble())
                    .toFloat()
                    .coerceIn(0.05f, 0.95f)
            }
            val progressChanged = progress - lastProgress >= DownloadProgressStep
            val bytesChanged = downloadedBytes - lastProgressBytes >= DownloadProgressByteInterval
            if (progressChanged || bytesChanged) {
                lastProgress = progress
                lastProgressBytes = downloadedBytes
                updateDownload(
                    track = track,
                    state = DownloadState.Downloading,
                    progress = progress,
                    persist = false,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                )
            }
        }

        platformStreamHttpDownloadToStorage(
            url = track.downloadUrl,
            targetPath = downloadPathFor(track),
            storage = storage,
            bufferSize = DownloadChunkSize,
            onProgress = ::publishProgress,
        )?.let { localUri ->
            ensureDownloadNotCancelled(track.id)
            return localUri
        }

        val response = httpClient.get(track.downloadUrl)
        if (response.status.value !in 200..299) {
            error("Server returned HTTP ${response.status.value}")
        }
        ensureDownloadNotCancelled(track.id)
        val channel = response.bodyAsChannel()
        val totalBytes = response.contentLength()?.takeIf { it > 0L }
        val buffer = ByteArray(DownloadChunkSize)
        var downloadedBytes = 0L
        var reachedDeclaredLength = false
        return try {
            storage.writeByteStream(downloadPathFor(track)) { sink ->
                while (true) {
                    ensureDownloadNotCancelled(track.id)
                    if (reachedDeclaredLength) return@writeByteStream
                    val read = withTimeoutOrNull(DownloadReadIdleTimeoutMs) {
                        channel.readAvailable(buffer, 0, buffer.size)
                    } ?: error("Download stalled.")
                    ensureDownloadNotCancelled(track.id)
                    when {
                        read < 0 -> {
                            if (totalBytes != null && downloadedBytes < totalBytes) {
                                error("Download ended before all bytes were received.")
                            }
                            return@writeByteStream
                        }
                        read == 0 -> {
                            if (currentTimeMs() - lastBytesAtMs >= DownloadReadIdleTimeoutMs) {
                                error("Download stalled.")
                            }
                            delay(50)
                        }
                        else -> {
                            downloadedBytes += read
                            sink.write(buffer, 0, read)
                            publishProgress(downloadedBytes, totalBytes)
                            if (totalBytes != null) {
                                if (downloadedBytes >= totalBytes) {
                                    reachedDeclaredLength = true
                                }
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            channel.cancel(error)
            throw error
        }
    }

    private suspend fun updateDownload(
        track: Track,
        state: DownloadState,
        progress: Float,
        persist: Boolean = true,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = null,
        errorMessage: String? = null,
    ) {
        downloadStatusMutex.withLock {
            val publishSnapshot = state != DownloadState.Downloading ||
                downloadItemsByTrackId.size <= DownloadImmediateSnapshotLimit
            publishDownloadItems(
                listOf(track.toDownloadItem(state, progress, downloadedBytes, totalBytes, errorMessage)),
                persist = persist,
                syncCatalog = false,
                publishSnapshot = publishSnapshot,
            )
        }
    }

    private suspend fun completeDownload(track: Track) {
        downloadStatusMutex.withLock {
            publishDownloadItems(
                listOf(track.toDownloadItem(DownloadState.Complete, progress = 1f)),
                persist = false,
                syncCatalog = false,
                publishSnapshot = false,
            )
        }
    }

    private suspend fun persistCompletedDownloads(
        tracks: List<Track>,
        publishSnapshot: Boolean,
    ) {
        if (tracks.isEmpty()) return
        runCatching {
            downloadStatusMutex.withLock {
                publishDownloadItems(
                    items = tracks.map { track ->
                        track.toDownloadItem(DownloadState.Complete, progress = 1f)
                    },
                    persist = true,
                    syncCatalog = false,
                    publishSnapshot = publishSnapshot,
                )
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            PhoebeLog.d("CatalogRepository") { "completed download persist failed: ${error.message}" }
        }
    }

    private fun Track.toDownloadItem(
        state: DownloadState,
        progress: Float,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = null,
        errorMessage: String? = null,
    ): DownloadItem =
        DownloadItem(
            trackId = id,
            title = title,
            artist = artist,
            state = state,
            progress = progress,
            localUri = localUri,
            downloadUrl = downloadUrl,
            targetPath = downloadPathFor(this),
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            updatedAtMs = currentTimeMs(),
            error = errorMessage,
        )

    private fun DownloadRow.toDownloadItem(): DownloadItem =
        DownloadItem(
            trackId = trackId,
            title = title,
            artist = artist,
            state = runCatching { DownloadState.valueOf(dlState) }.getOrDefault(DownloadState.Failed),
            progress = progress.toFloat(),
            localUri = localUri,
            downloadUrl = downloadUrl,
            targetPath = targetPath,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            updatedAtMs = updatedAtMs,
            batchId = batchId,
            error = error,
        )

    private suspend fun publishDownloadItems(
        items: List<DownloadItem>,
        persist: Boolean = true,
        syncCatalog: Boolean = true,
        publishSnapshot: Boolean = true,
    ) {
        if (items.isEmpty()) return
        val latestItems = if (items.size == 1) {
            items
        } else {
            items.associateBy { it.trackId }.values.toList()
        }
        latestItems.forEach { item ->
            downloadItemsByTrackId[item.trackId] = item
        }
        mutableDownloadEvents.tryEmit(DownloadStatusEvent(items = latestItems.map { it.toUiDownloadEventItem() }))
        if (publishSnapshot) {
            val downloads = currentDownloadItems()
            mutableDownloads.value = downloads
            if (syncCatalog) {
                mutableCatalog.value = mutableCatalog.value.copy(downloads = downloads)
            }
        }
        if (!persist) return
        runCatching {
            persistDownloadItems(latestItems)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            PhoebeLog.d("CatalogRepository") { "download status persist failed: ${error.message}" }
        }
    }

    private fun DownloadItem.toUiDownloadEventItem(): DownloadItem =
        DownloadItem(
            trackId = trackId,
            title = "",
            artist = "",
            state = state,
            progress = progress,
            localUri = localUri,
            updatedAtMs = updatedAtMs,
        )

    private fun syncCatalogDownloadItems() {
        val downloads = currentDownloadItems()
        val snapshot = mutableCatalog.value
        if (snapshot.downloads != downloads) {
            mutableDownloads.value = downloads
            mutableCatalog.value = withSmartPlaylists(snapshot.copy(downloads = downloads))
        }
    }

    private suspend fun clearDownloadItemsForTrackIds(trackIds: Set<String>) {
        if (trackIds.isEmpty()) return
        downloadStatusMutex.withLock {
            val snapshot = mutableCatalog.value
            val previous = currentDownloadItems()
            val downloads = previous.filterNot { it.trackId in trackIds }
            if (downloads.size != previous.size) {
                replaceDownloadSnapshot(downloads, syncCatalog = false)
                mutableCatalog.value = snapshot.copy(downloads = downloads)
                mutableDownloadEvents.tryEmit(DownloadStatusEvent(removedTrackIds = trackIds))
            }
        }
        runCatching {
            withContext(Dispatchers.Default) {
                runCatalogDbWrite {
                    database.transaction {
                        trackIds.forEach { database.downloadsQueries.delete(it) }
                    }
                }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            PhoebeLog.d("CatalogRepository") { "download status clear failed: ${error.message}" }
        }
    }

    private suspend fun clearTransientDownloadItemsForTrackIds(trackIds: Set<String>) {
        if (trackIds.isEmpty()) return
        var removedIds = emptySet<String>()
        downloadStatusMutex.withLock {
            val snapshot = mutableCatalog.value
            val downloads = currentDownloadItems()
            val remaining = downloads.filterNot { item ->
                item.trackId in trackIds &&
                    (item.state != DownloadState.Complete || item.localUri.isNullOrBlank())
            }
            if (remaining.size != downloads.size) {
                removedIds = downloads.mapTo(mutableSetOf()) { it.trackId } -
                    remaining.mapTo(mutableSetOf()) { it.trackId }
                replaceDownloadSnapshot(remaining, syncCatalog = false)
                mutableCatalog.value = snapshot.copy(downloads = remaining)
                mutableDownloadEvents.tryEmit(DownloadStatusEvent(removedTrackIds = removedIds))
            }
        }
        if (removedIds.isEmpty()) return
        runCatching {
            withContext(Dispatchers.Default) {
                runCatalogDbWrite {
                    database.transaction {
                        removedIds.forEach { database.downloadsQueries.delete(it) }
                    }
                }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            PhoebeLog.d("CatalogRepository") { "transient download status clear failed: ${error.message}" }
        }
    }

    private suspend fun downloadArtworkForTrack(track: Track): String? {
        val thumbUrl = track.thumbUrl?.takeIf { it.isNotBlank() && it.isRemoteArtworkUrl() } ?: return null
        return runCatching {
            withTimeoutOrNull(DownloadArtworkTimeoutMs) {
                val bytes = httpClient.get(thumbUrl) {
                    applyEmbyFamilyArtworkAuth(thumbUrl)
                }.body<ByteArray>()
                storage.writeBytes(cachedArtworkPathForUrl(thumbUrl), bytes)
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            PhoebeLog.d("CatalogRepository") { "artwork download failed for '${track.title}': ${error.message}" }
        }.getOrNull() ?: run {
            PhoebeLog.d("CatalogRepository") { "artwork download timed out for '${track.title}'" }
            null
        }
    }

    private suspend fun downloadArtworkForArtist(artist: Artist): String? =
        downloadArtworkForEntity(
            owner = "artist",
            id = artist.id,
            title = artist.title,
            thumbUrl = artist.thumbUrl,
        )

    private suspend fun downloadArtworkForAlbum(album: Album): String? =
        downloadArtworkForEntity(
            owner = "album",
            id = album.id,
            title = album.title,
            thumbUrl = album.thumbUrl,
        )

    private suspend fun downloadArtworkForPlaylist(playlist: Playlist): String? =
        downloadArtworkForEntity(
            owner = "playlist",
            id = playlist.id,
            title = playlist.title,
            thumbUrl = playlist.thumbUrl,
        )

    private suspend fun downloadArtworkForEntity(
        owner: String,
        id: String,
        title: String,
        thumbUrl: String?,
    ): String? {
        val remoteThumbUrl = thumbUrl?.takeIf { it.isNotBlank() && it.isRemoteArtworkUrl() } ?: return null
        return runCatching {
            val bytes = httpClient.get(remoteThumbUrl) {
                applyEmbyFamilyArtworkAuth(remoteThumbUrl)
            }.body<ByteArray>()
            storage.writeBytes(cachedArtworkPathForUrl(remoteThumbUrl), bytes)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            PhoebeLog.d("CatalogRepository") { "$owner artwork download failed for '$title': ${error.message}" }
        }.getOrNull()
    }

    private suspend fun updateTracksOfflineInfo(
        offlineTracks: List<Track>,
        persistRows: Boolean = true,
    ) {
        if (offlineTracks.isEmpty()) return
        val offlineById = offlineTracks.associateBy { it.id }
        val snapshot = mutableCatalog.value
        val updatedTracks = snapshot.tracksByParent.mapValues { (_, tracks) ->
            tracks.map { track ->
                offlineById[track.id] ?: track
            }
        }
        mutableCatalog.value = snapshot.copy(tracksByParent = updatedTracks)
        if (!persistRows) return
        runCatching {
            persistTrackRows(offlineById.values.toList())
        }.onFailure { error ->
            if (error is CancellationException) throw error
            PhoebeLog.d("CatalogRepository") { "offline track persist failed: ${error.message}" }
        }
    }

    private suspend fun updateTrackArtworkInfo(trackId: String, localArtworkUri: String) {
        val snapshot = mutableCatalog.value
        val updatedTracks = snapshot.tracksByParent.mapValues { (_, tracks) ->
            tracks.map { track ->
                if (track.id == trackId) {
                    track.copy(localArtworkUri = localArtworkUri)
                } else {
                    track
                }
            }
        }
        publish(snapshot.copy(tracksByParent = updatedTracks), persist = true)
    }

    private fun downloadPathFor(track: Track): String {
        track.filepath
            ?.takeIf { it.startsWith("downloads/") }
            ?.let { return it }
        val plexRelativePath = track.filepath
            ?.takeIf { it.isNotBlank() }
            ?.libraryRelativeDownloadPath(track)
        if (plexRelativePath != null) return "downloads/$plexRelativePath"

        val extension = track.filepath
            ?.substringAfterLast('/', "")
            ?.substringAfterLast('\\', "")
            ?.substringAfterLast('.', "")
            ?.takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
            ?: track.downloadUrl.substringBefore('?')
                .substringAfterLast('/', "")
                .substringAfterLast('.', "")
                .takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
            ?: track.audioCodec?.lowercase()?.takeIf { it.isNotBlank() }
            ?: "audio"
        return "downloads/${track.id.safePathSegment()}.$extension"
    }

    private fun String.isRemoteArtworkUrl(): Boolean =
        startsWith("http://") || startsWith("https://")

    private fun String.isLocalArtworkUrl(): Boolean =
        startsWith("file:") || startsWith("content:") || startsWith("web-storage://")

    private fun String.safePathSegment(): String =
        map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "track" }

    private fun String.libraryRelativeDownloadPath(track: Track): String? {
        val normalized = replace('\\', '/').substringBefore('?').trim()
        if (normalized.isBlank() || normalized.startsWith("file://")) return null
        val rawSegments = normalized
            .split('/')
            .filter { it.isNotBlank() }
            .map { it.trim() }
        if (rawSegments.isEmpty()) return null
        val fileName = rawSegments.last().safeFileName().takeIf { it.hasLikelyExtension() } ?: return null
        val folders = rawSegments.dropLast(1)
        val artistIndex = folders.indexOfFirst { it.samePathName(track.artist) }
        val albumIndex = folders.indexOfFirst { it.samePathName(track.album) }
        val startIndex = when {
            artistIndex >= 0 -> artistIndex
            albumIndex > 0 -> albumIndex - 1
            folders.size > 1 && rawSegments.first().looksLikeFilesystemRoot() -> 1
            else -> 0
        }
        val relativeFolders = folders.drop(startIndex).mapNotNull { segment ->
            segment.safeFileName().takeIf { it.isNotBlank() && it != "." && it != ".." }
        }
        return (relativeFolders + fileName).joinToString("/").takeIf { it.isNotBlank() }
    }

    private fun String.safeFileName(): String =
        map { c ->
            when {
                c.isLetterOrDigit() || c == ' ' || c == '-' || c == '_' || c == '.' || c == '(' || c == ')' || c == '[' || c == ']' -> c
                else -> '-'
            }
        }
            .joinToString("")
            .trim(' ', '.', '-')
            .ifBlank { "untitled" }

    private fun String.samePathName(other: String): Boolean =
        safeFileName().lowercase() == other.safeFileName().lowercase()

    private fun String.hasLikelyExtension(): Boolean =
        substringAfterLast('.', "")
            .takeIf { it.length in 2..5 }
            ?.all { it.isLetterOrDigit() } == true

    private fun String.looksLikeFilesystemRoot(): Boolean {
        val value = trimEnd(':').lowercase()
        return value.length == 1 ||
            value in setOf("music", "media", "audio", "library", "libraries", "mnt", "mount", "volume", "volumes", "storage")
    }

    private fun pushTracksLoading(parentId: String) {
        mutableTracksLoading.value = mutableTracksLoading.value + parentId
    }

    private fun popTracksLoading(parentId: String) {
        mutableTracksLoading.value = mutableTracksLoading.value - parentId
    }

    private suspend fun hydrateTracksFromDatabaseIfEmpty() {
        if (mutableCatalog.value.tracksByParent.values.any { it.isNotEmpty() }) return
        val albums = mutableCatalog.value.albums
        if (albums.isEmpty()) return
        val hydrated = withContext(Dispatchers.Default) {
            albums.mapNotNull { album ->
                readTracksForParentFromDatabase(album.id)?.takeIf { it.isNotEmpty() }?.let { album.id to it }
            }.toMap()
        }
        if (hydrated.isNotEmpty()) {
            catalogMergeMutex.withLock {
                mutableCatalog.value = mutableCatalog.value.copy(
                    tracksByParent = mutableCatalog.value.tracksByParent + hydrated,
                )
            }
        }
    }

    private suspend fun readTracksForParentFromDatabase(parentId: String): List<Track>? = withContext(Dispatchers.Default) {
        val rows = database.catalogQueries.selectTracksForParent(parentId).awaitAsList()
        if (rows.isEmpty()) return@withContext null
        rows.map { row -> row.toTrack() }
    }

    private suspend fun readPopularTracksForLibraryFromDatabase(libraryKey: String): List<Track> =
        withContext(Dispatchers.Default) {
            database.catalogQueries.selectPopularTracksForLibrary(libraryKey)
                .awaitAsList()
                .map { row -> row.toTrack() }
        }

    private fun TrackRow.toTrack(): Track =
        Track(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            streamUrl = streamUrl,
            downloadUrl = downloadUrl,
            thumbUrl = thumbUrl,
            localArtworkUri = localArtworkUri,
            localUri = localUri,
            year = year?.toInt(),
            genre = genre,
            mood = mood,
            style = style,
            filepath = filepath,
            audioCodec = audioCodec,
            bitrateKbps = bitrateKbps?.toInt(),
            dateAddedMs = dateAddedMs,
            rating = rating?.toFloat(),
            parentAlbumId = parentAlbumId,
        )

    private fun warmLikelyClickedContent(session: PlexSession?, snapshot: CatalogSnapshot) {
        if (session?.isPlex() != true) return
        persistenceScope.launch {
            runCatching {
                val albumIds = buildList {
                    addAll(snapshot.albums.filter { it.favorite }.map { it.id })
                    addAll(
                        snapshot.albums
                            .sortedByDescending { it.dateAddedMs ?: 0L }
                            .take(LikelyWarmAlbumCount)
                            .map { it.id },
                    )
                }.distinct()
                albumIds.forEach { albumId ->
                    if (mutableCatalog.value.tracksByParent[albumId].isNullOrEmpty()) {
                        snapshot.albums.find { it.id == albumId }?.let { album ->
                            runCatching { tracksForAlbum(session, album) }
                                .onFailure { error ->
                                    if (error is CancellationException) throw error
                                }
                        }
                    }
                }
                val playlists = snapshot.playlists
                    .filter { it.favorite && it.trackCount > 0 }
                    .filter { mutableCatalog.value.tracksByParent[it.id].isNullOrEmpty() }
                if (playlists.isNotEmpty()) {
                    warmPlaylistTracksParallel(session, playlists, updateSyncProgress = false)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                PhoebeLog.d("CatalogRepository") {
                    "background warm failed: ${error.message}"
                }
            }
        }
    }

    private suspend fun persistCatalogShell(snapshot: CatalogSnapshot) = withContext(Dispatchers.Default) {
        val snapshot = snapshot.withoutSmartPlaylistArtifacts()
            .withPlaylistUserStateFrom(mutableCatalog.value.withoutSmartPlaylistArtifacts())
        database.transaction {
            snapshot.artists.forEachIndexed { index, artist ->
                database.catalogQueries.upsertArtist(
                    id = artist.id,
                    title = artist.title,
                    thumbUrl = artist.thumbUrl,
                    albumCount = artist.albumCount.toLong(),
                    songCount = artist.songCount.toLong(),
                    sortKey = index.toLong(),
                    dateAddedMs = artist.dateAddedMs,
                    genre = artist.genre,
                    mood = artist.mood,
                    style = artist.style,
                    rating = artist.rating?.toDouble(),
                    favorite = artist.favorite.toDb(),
                )
            }
            snapshot.albums.forEachIndexed { index, album ->
                database.catalogQueries.upsertAlbumFull(
                    id = album.id,
                    title = album.title,
                    artist = album.artist,
                    albumArtist = album.albumArtist,
                    year = album.year?.toLong(),
                    thumbUrl = album.thumbUrl,
                    sortKey = index.toLong(),
                    dateAddedMs = album.dateAddedMs,
                    genre = album.genre,
                    mood = album.mood,
                    style = album.style,
                    rating = album.rating?.toDouble(),
                    favorite = album.favorite.toDb(),
                    description = album.description,
                    recordLabel = album.recordLabel,
                    releaseDate = album.releaseDate,
                )
            }
            snapshot.playlists.forEachIndexed { index, playlist ->
                database.catalogQueries.upsertPlaylist(
                    id = playlist.id,
                    title = playlist.title,
                    trackCount = playlist.trackCount.toLong(),
                    plKey = playlist.key,
                    thumbUrl = playlist.thumbUrl,
                    sortKey = index.toLong(),
                    rating = playlist.rating?.toDouble(),
                    favorite = playlist.favorite.toDb(),
                )
            }
            snapshot.collectionTags.forEach { tag ->
                database.catalogQueries.upsertCollectionTag(
                    target = tag.target,
                    facet = tag.facet,
                    itemId = tag.itemId,
                    value_ = tag.value,
                )
            }
            snapshot.collectionValues.forEach { value ->
                database.catalogQueries.upsertCollectionValue(
                    target = value.target,
                    facet = value.facet,
                    value_ = value.value,
                    key = value.key,
                    fastKey = value.fastKey,
                    filterField = value.filterField,
                    itemsLoaded = if (value.itemsLoaded) 1L else 0L,
                )
            }
            snapshot.collectionValueLoads.forEach { load ->
                database.catalogQueries.upsertCollectionValueLoad(
                    target = load.target,
                    facet = load.facet,
                )
            }
        }
    }

    private suspend fun persistTrackBatch(
        tracks: List<Track>,
        replaceParents: Set<String> = emptySet(),
    ) {
        if (tracks.isEmpty()) return
        val snapshot = mutableCatalog.value
        val tracksByParent = tracks
            .groupBy { track -> resolveIndexedTrackParentId(track, snapshot) }
            .filterKeys { it != null }
            .mapKeys { it.key!! }
        val existingDateAddedById = database.catalogQueries
            .selectTrackDateAddedForIds(tracks.map { it.id })
            .awaitAsList()
            .associate { it.id to it.dateAddedMs }
        val existingTrackIdsByParent = tracksByParent.keys.associateWith { parentId ->
            database.catalogQueries.selectTracksForParent(parentId).awaitAsList().map { it.id }
        }
        database.transaction {
            tracksByParent.forEach { (parentId, parentTracks) ->
                parentTracks.forEach { track ->
                    val dateAddedMs = track.dateAddedMs ?: existingDateAddedById[track.id]
                    database.catalogQueries.upsertTrack(
                        id = track.id,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationMs = track.durationMs,
                        streamUrl = track.streamUrl,
                        downloadUrl = track.downloadUrl,
                        thumbUrl = track.thumbUrl,
                        localArtworkUri = track.localArtworkUri,
                        localUri = track.localUri,
                        year = track.year?.toLong(),
                        genre = track.genre,
                        mood = track.mood,
                        style = track.style,
                        filepath = track.filepath,
                        audioCodec = track.audioCodec,
                        bitrateKbps = track.bitrateKbps?.toLong(),
                        dateAddedMs = dateAddedMs,
                        rating = track.rating?.toDouble(),
                        parentAlbumId = track.parentAlbumId,
                    )
                    syncedTrackIdsDuringRefresh.add(track.id)
                }
                val existingTrackIds = existingTrackIdsByParent[parentId].orEmpty()
                val mergedTrackIds = if (parentId in replaceParents) {
                    parentTracks.map { it.id }
                } else {
                    buildList {
                        addAll(existingTrackIds)
                        parentTracks.forEach { track ->
                            if (track.id !in existingTrackIds) add(track.id)
                        }
                    }
                }
                if (parentId in replaceParents) {
                    existingTrackIds.filter { it !in mergedTrackIds }.forEach { staleId ->
                        database.catalogQueries.deleteTrackById(staleId)
                    }
                }
                database.catalogQueries.deleteTrackParentsForParent(parentId)
                mergedTrackIds.forEachIndexed { index, trackId ->
                    val mergedTrack = parentTracks.firstOrNull { it.id == trackId }
                    database.catalogQueries.upsertTrackParent(
                        parentId = parentId,
                        trackId = trackId,
                        position = index.toLong(),
                        playlistItemId = mergedTrack?.playlistItemId,
                    )
                }
            }
        }
    }

    private suspend fun replaceIndexedTrackParents(indexedTrackIdsByParent: Map<String, List<String>>) {
        if (indexedTrackIdsByParent.isEmpty()) return
        database.transaction {
            indexedTrackIdsByParent.forEach { (parentId, trackIds) ->
                database.catalogQueries.deleteTrackParentsForParent(parentId)
                trackIds.distinct().forEachIndexed { index, trackId ->
                    database.catalogQueries.upsertTrackParent(
                        parentId = parentId,
                        trackId = trackId,
                        position = index.toLong(),
                        playlistItemId = null,
                    )
                }
            }
        }
    }

    private suspend fun persistLibraryPopularTracks(libraryKey: String, tracks: List<Track>) {
        val nowMs = currentTimeMs()
        database.transaction {
            database.catalogQueries.deleteLibraryPopularTracksForLibrary(libraryKey)
            tracks.distinctBy { it.id }.forEachIndexed { index, track ->
                database.catalogQueries.upsertLibraryPopularTrack(
                    libraryKey = libraryKey,
                    trackId = track.id,
                    position = index.toLong(),
                    updatedAtMs = nowMs,
                )
            }
        }
    }

    private suspend fun reconcileCatalogPersistence(
        snapshot: CatalogSnapshot,
        partialPaged: Boolean = false,
    ) = withContext(Dispatchers.Default) {
        if (!snapshot.hasRestorableCatalogContent()) {
            persistPartialSnapshotWithoutClearingCatalog(snapshot)
            return@withContext
        }
        val knownArtistIds = snapshot.artists.map { it.id }.toSet()
        val knownAlbumIds = snapshot.albums.map { it.id }.toSet()
        val knownPlaylistIds = snapshot.playlists.map { it.id }.toSet()
        val knownTrackIds = snapshot.tracksByParent.values.flatten().map { it.id }.toSet() + syncedTrackIdsDuringRefresh
        val knownParentIds = snapshot.tracksByParent.keys.toSet()

        val existingArtists = database.catalogQueries.selectArtists().awaitAsList()
        val existingArtistIds = existingArtists.map { it.id }
        val existingArtistsById = existingArtists.associateBy { it.id }
        val existingAlbumIds = database.catalogQueries.selectAlbums().awaitAsList().map { it.id }
        val existingPlaylistIds = database.catalogQueries.selectPlaylists().awaitAsList().map { it.id }
        val existingTrackIds = database.catalogQueries.selectAllTrackIds().awaitAsList()

        database.transaction {
            existingArtistIds.filter { it !in knownArtistIds }.forEach { database.catalogQueries.deleteArtistById(it) }
        // Drop legacy synthetic artists when a real artist with the same title was synced.
        val knownTitles = snapshot.artists.map { it.title.trim().lowercase() }.toSet()
        existingArtistIds
            .filter { id -> id !in knownArtistIds && id.substringAfter(':').startsWith("album-artist-") }
            .filter { id ->
                val title = existingArtistsById[id]?.title?.trim()?.lowercase()
                title != null && title in knownTitles
            }
            .forEach { database.catalogQueries.deleteArtistById(it) }
            existingAlbumIds.filter { it !in knownAlbumIds }.forEach { database.catalogQueries.deleteAlbumById(it) }
            existingPlaylistIds.filter { it !in knownPlaylistIds }.forEach { database.catalogQueries.deletePlaylistById(it) }
            if (!partialPaged) {
                existingTrackIds.filter { it !in knownTrackIds }.forEach { id ->
                    database.catalogQueries.deleteTrackById(id)
                }
            }
            knownParentIds.forEach { parentId ->
                val tracks = snapshot.tracksByParent[parentId].orEmpty()
                database.catalogQueries.deleteTrackParentsForParent(parentId)
                tracks.forEachIndexed { index, track ->
                    database.catalogQueries.upsertTrackParent(
                        parentId = parentId,
                        trackId = track.id,
                        position = index.toLong(),
                        playlistItemId = track.playlistItemId,
                    )
                }
            }
        }
        database.transaction {
            database.downloadsQueries.clearAll()
            snapshot.downloads.forEach { item ->
                database.downloadsQueries.upsert(
                    trackId = item.trackId,
                    title = item.title,
                    artist = item.artist,
                    dlState = item.state.name,
                    progress = item.progress.toDouble(),
                    localUri = item.localUri,
                    downloadUrl = item.downloadUrl,
                    targetPath = item.targetPath,
                    downloadedBytes = item.downloadedBytes,
                    totalBytes = item.totalBytes,
                    updatedAtMs = item.updatedAtMs,
                    batchId = item.batchId,
                    error = item.error,
                )
            }
        }
    }

    private fun CatalogSnapshot.contentChecksum(): Long {
        var hash = 17L
        hash = hash * 31 + artists.size
        hash = hash * 31 + albums.size
        hash = hash * 31 + playlists.size
        hash = hash * 31 + tracksByParent.values.sumOf { it.size }
        artists.forEach { hash = hash * 31 + it.id.hashCode() }
        albums.forEach { hash = hash * 31 + it.id.hashCode() }
        playlists.forEach { hash = hash * 31 + it.id.hashCode() }
        tracksByParent.values.flatten().forEach { hash = hash * 31 + it.id.hashCode() }
        return hash
    }

    private fun withSmartPlaylists(snapshot: CatalogSnapshot): CatalogSnapshot {
        val base = snapshot.withoutSmartPlaylistArtifacts()
        val smartPlaylists = userArtifactsRepository.smartPlaylists.value.filter { it.enabled }
        if (smartPlaylists.isEmpty()) return base
        val allTracks = base.tracksByParent.values.asSequence().flatten().distinctBy { it.id }.toList()
        val smartTracksByParent = smartPlaylists.associate { smart ->
            smart.id to materializedSmartPlaylistTracks(smart.id, base, smart, allTracks)
        }
        val smartRows = smartPlaylists.map { smart ->
            Playlist(
                id = smart.id,
                title = smart.title,
                trackCount = smartTracksByParent[smart.id].orEmpty().size,
            )
        }
        return base.copy(
            playlists = (smartRows.sortedBy { it.title.lowercase() } + base.playlists.sortedBy { it.title.lowercase() }),
            tracksByParent = base.tracksByParent + smartTracksByParent,
        )
    }

    private fun CatalogSnapshot.withoutSmartPlaylistArtifacts(): CatalogSnapshot =
        copy(
            playlists = playlists.filterNot { it.isSmartPlaylist() },
            tracksByParent = tracksByParent.filterKeys { !it.startsWith(SmartPlaylist.IdPrefix) },
        )

    private fun materializedSmartPlaylistTracks(
        playlistId: String,
        snapshot: CatalogSnapshot,
        smartPlaylist: SmartPlaylist? = userArtifactsRepository.smartPlaylists.value.firstOrNull { it.id == playlistId },
        allTracks: List<Track> = snapshot.withoutSmartPlaylistArtifacts().tracksByParent.values.asSequence().flatten().distinctBy { it.id }.toList(),
    ): List<Track> {
        val smart = smartPlaylist ?: return emptyList()
        val base = snapshot.withoutSmartPlaylistArtifacts()
        val downloadedIds = base.downloads.asSequence()
            .filter { it.state == DownloadState.Complete }
            .mapTo(mutableSetOf()) { it.trackId }
        val providerByTrackId = allTracks
            .mapNotNull { track ->
                val provider = MediaProviderType.entries.firstOrNull { track.id.startsWith("${it.catalogPrefix}:") }
                provider?.let { track.id to it }
            }
            .toMap()
        val context = TrackFilterContext(
            downloadedTrackIds = downloadedIds,
            lastPlayedByTrackId = smartPlaylistLastPlayedByTrack.value,
            playCountByTrackId = smartPlaylistPlayCountsByTrack.value,
            providerByTrackId = providerByTrackId,
        )
        val sorted = allTracks
            .filterWith(smart.filter, context)
            .sortedWith(smart.sort, context)
        return smart.limit?.coerceAtLeast(0)?.let { sorted.take(it) } ?: sorted
    }

    private suspend fun publish(snapshot: CatalogSnapshot, persist: Boolean) {
        val snapshot = withSmartPlaylists(snapshot.withPlaylistUserStateFrom(mutableCatalog.value))
        mutableCatalog.value = snapshot
        if (persist) {
            persistAsync(snapshot.withoutSmartPlaylistArtifacts())
        }
    }

    private suspend fun persistCurrentTrackParentsWithoutClearingCatalog() {
        val tracksByParent = mutableCatalog.value.tracksByParent
        if (tracksByParent.isEmpty()) return
        runCatalogDbWrite { persistTracksByParentWithoutClearingCatalog(tracksByParent) }
    }

    /** Persist the entire snapshot off the UI thread. */
    private suspend fun persistAsync(snapshot: CatalogSnapshot) {
        val snapshot = snapshot.withoutSmartPlaylistArtifacts().withPlaylistUserStateFrom(mutableCatalog.value.withoutSmartPlaylistArtifacts())
        withContext(Dispatchers.Default) {
            runCatalogDbWrite {
                if (snapshot.hasRestorableCatalogContent()) {
                    persist(snapshot)
                } else {
                    persistPartialSnapshotWithoutClearingCatalog(snapshot)
                }
            }
        }
    }

    private suspend fun persistPlaylistTracksAsync(snapshot: CatalogSnapshot, playlistId: String) {
        enqueueCatalogDbWrite { persistPlaylistTracks(snapshot, playlistId) }
    }

    private suspend fun persistPlaylistTracks(snapshot: CatalogSnapshot, playlistId: String) = withContext(Dispatchers.Default) {
        val snapshot = snapshot.withPlaylistUserStateFrom(mutableCatalog.value)
        val playlist = snapshot.playlists.firstOrNull { it.id == playlistId } ?: return@withContext
        val tracks = snapshot.tracksByParent[playlistId].orEmpty()
        val sortKey = snapshot.playlists.indexOfFirst { it.id == playlistId }.takeIf { it >= 0 } ?: 0
        database.transaction {
            if (playlist.isLikedSongsPlaylist()) {
                val staleLikedPlaylistIds = (
                    MediaProviderType.entries.map { likedSongsPlaylistId(it) } +
                        PENDING_LIKED_SONGS_PLAYLIST_ID
                    ).filter { it != playlist.id }
                staleLikedPlaylistIds.forEach { staleId ->
                    database.catalogQueries.deleteTrackParents(staleId)
                    database.catalogQueries.deletePlaylistById(staleId)
                }
            }
            database.catalogQueries.upsertPlaylist(
                id = playlist.id,
                title = playlist.title,
                trackCount = playlist.trackCount.toLong(),
                plKey = playlist.key,
                thumbUrl = playlist.thumbUrl,
                sortKey = sortKey.toLong(),
                rating = playlist.rating?.toDouble(),
                favorite = playlist.favorite.toDb(),
            )
            tracks.forEach { track ->
                database.catalogQueries.upsertTrack(
                    id = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    streamUrl = track.streamUrl,
                    downloadUrl = track.downloadUrl,
                    thumbUrl = track.thumbUrl,
                    localArtworkUri = track.localArtworkUri,
                    localUri = track.localUri,
                    year = track.year?.toLong(),
                    genre = track.genre,
                    mood = track.mood,
                    style = track.style,
                    filepath = track.filepath,
                    audioCodec = track.audioCodec,
                    bitrateKbps = track.bitrateKbps?.toLong(),
                    dateAddedMs = track.dateAddedMs,
                    rating = track.rating?.toDouble(),
                    parentAlbumId = track.parentAlbumId,
                )
            }
            database.catalogQueries.deleteTrackParents(playlistId)
            tracks.forEachIndexed { index, track ->
                database.catalogQueries.upsertTrackParent(
                    parentId = playlistId,
                    trackId = track.id,
                    position = index.toLong(),
                    playlistItemId = track.playlistItemId,
                )
            }
        }
    }

    private suspend fun persistPartialSnapshotWithoutClearingCatalog(snapshot: CatalogSnapshot) {
        if (snapshot.tracksByParent.isNotEmpty()) {
            persistTracksByParentWithoutClearingCatalog(snapshot.tracksByParent)
        }
        if (
            snapshot.collectionValues.isNotEmpty() ||
            snapshot.collectionValueLoads.isNotEmpty() ||
            snapshot.collectionTags.isNotEmpty()
        ) {
            persistCollectionMetadataWithoutClearingCatalog(snapshot)
        }
        if (snapshot.downloads.isNotEmpty()) {
            persistDownloads(snapshot.downloads)
        }
        if (
            snapshot.tracksByParent.isEmpty() &&
            snapshot.collectionValues.isEmpty() &&
            snapshot.collectionValueLoads.isEmpty() &&
            snapshot.collectionTags.isEmpty() &&
            snapshot.downloads.isEmpty()
        ) {
            PhoebeLog.d("CatalogRepository") { "skipped full persist for empty catalog snapshot" }
        }
    }

    private suspend fun persistTracksByParentWithoutClearingCatalog(tracksByParent: Map<String, List<Track>>) {
        val uniqueTracks = tracksByParent.values.flatten().distinctBy { it.id }
        database.transaction {
            uniqueTracks.forEach { track ->
                database.catalogQueries.upsertTrack(
                    id = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    streamUrl = track.streamUrl,
                    downloadUrl = track.downloadUrl,
                    thumbUrl = track.thumbUrl,
                    localArtworkUri = track.localArtworkUri,
                    localUri = track.localUri,
                    year = track.year?.toLong(),
                    genre = track.genre,
                    mood = track.mood,
                    style = track.style,
                    filepath = track.filepath,
                    audioCodec = track.audioCodec,
                    bitrateKbps = track.bitrateKbps?.toLong(),
                    dateAddedMs = track.dateAddedMs,
                    rating = track.rating?.toDouble(),
                    parentAlbumId = track.parentAlbumId,
                )
            }
            tracksByParent.forEach { (parentId, tracks) ->
                database.catalogQueries.deleteTrackParentsForParent(parentId)
                tracks.forEachIndexed { index, track ->
                    database.catalogQueries.upsertTrackParent(
                        parentId = parentId,
                        trackId = track.id,
                        position = index.toLong(),
                        playlistItemId = track.playlistItemId,
                    )
                }
            }
        }
    }

    private suspend fun persistDownloads(downloads: List<DownloadItem>) {
        database.transaction {
            database.downloadsQueries.clearAll()
            downloads.forEach { item ->
                database.downloadsQueries.upsert(
                    trackId = item.trackId,
                    title = item.title,
                    artist = item.artist,
                    dlState = item.state.name,
                    progress = item.progress.toDouble(),
                    localUri = item.localUri,
                    downloadUrl = item.downloadUrl,
                    targetPath = item.targetPath,
                    downloadedBytes = item.downloadedBytes,
                    totalBytes = item.totalBytes,
                    updatedAtMs = item.updatedAtMs,
                    batchId = item.batchId,
                    error = item.error,
                )
            }
        }
    }

    private suspend fun persistDownloadItems(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        withContext(Dispatchers.Default) {
            runCatalogDbWrite {
                database.transaction {
                    items.forEach { item ->
                        database.downloadsQueries.upsert(
                            trackId = item.trackId,
                            title = item.title,
                            artist = item.artist,
                            dlState = item.state.name,
                            progress = item.progress.toDouble(),
                            localUri = item.localUri,
                            downloadUrl = item.downloadUrl,
                            targetPath = item.targetPath,
                            downloadedBytes = item.downloadedBytes,
                            totalBytes = item.totalBytes,
                            updatedAtMs = item.updatedAtMs,
                            batchId = item.batchId,
                            error = item.error,
                        )
                    }
                }
            }
        }
    }

    private suspend fun persistTrackRows(tracks: List<Track>) {
        val uniqueTracks = tracks.distinctBy { it.id }
        if (uniqueTracks.isEmpty()) return
        withContext(Dispatchers.Default) {
            runCatalogDbWrite {
                database.transaction {
                    uniqueTracks.forEach { track ->
                        upsertTrackRow(track)
                    }
                }
            }
        }
    }

    private suspend fun upsertTrackRow(track: Track) {
        database.catalogQueries.upsertTrack(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            streamUrl = track.streamUrl,
            downloadUrl = track.downloadUrl,
            thumbUrl = track.thumbUrl,
            localArtworkUri = track.localArtworkUri,
            localUri = track.localUri,
            year = track.year?.toLong(),
            genre = track.genre,
            mood = track.mood,
            style = track.style,
            filepath = track.filepath,
            audioCodec = track.audioCodec,
            bitrateKbps = track.bitrateKbps?.toLong(),
            dateAddedMs = track.dateAddedMs,
            rating = track.rating?.toDouble(),
            parentAlbumId = track.parentAlbumId,
        )
    }

    private suspend fun persistCollectionMetadataWithoutClearingCatalog(snapshot: CatalogSnapshot) {
        database.transaction {
            snapshot.collectionTags.forEach { tag ->
                database.catalogQueries.upsertCollectionTag(
                    target = tag.target,
                    facet = tag.facet,
                    itemId = tag.itemId,
                    value_ = tag.value,
                )
            }
            snapshot.collectionValues.forEach { value ->
                database.catalogQueries.upsertCollectionValue(
                    target = value.target,
                    facet = value.facet,
                    value_ = value.value,
                    key = value.key,
                    fastKey = value.fastKey,
                    filterField = value.filterField,
                    itemsLoaded = if (value.itemsLoaded) 1L else 0L,
                )
            }
            snapshot.collectionValueLoads.forEach { load ->
                database.catalogQueries.upsertCollectionValueLoad(
                    target = load.target,
                    facet = load.facet,
                )
            }
        }
    }

    private suspend fun persist(snapshot: CatalogSnapshot) {
        val snapshot = snapshot.withoutSmartPlaylistArtifacts()
        database.transaction {
            database.catalogQueries.clearTrackParents()
            database.catalogQueries.clearTracks()
            database.catalogQueries.clearCollectionTags()
            database.catalogQueries.clearCollectionValues()
            database.catalogQueries.clearCollectionValueLoads()
            database.catalogQueries.clearArtists()
            database.catalogQueries.clearAlbums()
            database.catalogQueries.clearPlaylists()
            snapshot.artists.forEachIndexed { index, artist ->
                database.catalogQueries.upsertArtist(
                    id = artist.id,
                    title = artist.title,
                    thumbUrl = artist.thumbUrl,
                    albumCount = artist.albumCount.toLong(),
                    songCount = artist.songCount.toLong(),
                    sortKey = index.toLong(),
                    dateAddedMs = artist.dateAddedMs,
                    genre = artist.genre,
                    mood = artist.mood,
                    style = artist.style,
                    rating = artist.rating?.toDouble(),
                    favorite = artist.favorite.toDb(),
                )
            }
            snapshot.albums.forEachIndexed { index, album ->
                database.catalogQueries.upsertAlbumFull(
                    id = album.id,
                    title = album.title,
                    artist = album.artist,
                    albumArtist = album.albumArtist,
                    year = album.year?.toLong(),
                    thumbUrl = album.thumbUrl,
                    sortKey = index.toLong(),
                    dateAddedMs = album.dateAddedMs,
                    genre = album.genre,
                    mood = album.mood,
                    style = album.style,
                    rating = album.rating?.toDouble(),
                    favorite = album.favorite.toDb(),
                    description = album.description,
                    recordLabel = album.recordLabel,
                    releaseDate = album.releaseDate,
                )
            }
            snapshot.playlists.forEachIndexed { index, playlist ->
                database.catalogQueries.upsertPlaylist(
                    id = playlist.id,
                    title = playlist.title,
                    trackCount = playlist.trackCount.toLong(),
                    plKey = playlist.key,
                    thumbUrl = playlist.thumbUrl,
                    sortKey = index.toLong(),
                    rating = playlist.rating?.toDouble(),
                    favorite = playlist.favorite.toDb(),
                )
            }
            val uniqueTracks = snapshot.tracksByParent.values.flatten().distinctBy { it.id }
            uniqueTracks.forEach { track ->
                database.catalogQueries.upsertTrack(
                    id = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    streamUrl = track.streamUrl,
                    downloadUrl = track.downloadUrl,
                    thumbUrl = track.thumbUrl,
                    localArtworkUri = track.localArtworkUri,
                    localUri = track.localUri,
                    year = track.year?.toLong(),
                    genre = track.genre,
                    mood = track.mood,
                    style = track.style,
                    filepath = track.filepath,
                    audioCodec = track.audioCodec,
                    bitrateKbps = track.bitrateKbps?.toLong(),
                    dateAddedMs = track.dateAddedMs,
                    rating = track.rating?.toDouble(),
                    parentAlbumId = track.parentAlbumId,
                )
            }
            snapshot.tracksByParent.forEach { (parentId, tracks) ->
                tracks.forEachIndexed { index, track ->
                    database.catalogQueries.upsertTrackParent(
                        parentId = parentId,
                        trackId = track.id,
                        position = index.toLong(),
                        playlistItemId = track.playlistItemId,
                    )
                }
            }
            snapshot.collectionTags.forEach { tag ->
                database.catalogQueries.upsertCollectionTag(
                    target = tag.target,
                    facet = tag.facet,
                    itemId = tag.itemId,
                    value_ = tag.value,
                )
            }
            snapshot.collectionValues.forEach { value ->
                database.catalogQueries.upsertCollectionValue(
                    target = value.target,
                    facet = value.facet,
                    value_ = value.value,
                    key = value.key,
                    fastKey = value.fastKey,
                    filterField = value.filterField,
                    itemsLoaded = if (value.itemsLoaded) 1L else 0L,
                )
            }
            snapshot.collectionValueLoads.forEach { load ->
                database.catalogQueries.upsertCollectionValueLoad(
                    target = load.target,
                    facet = load.facet,
                )
            }
            PhoebeLog.d("PlexCollections") {
                "persist collectionValues count=${snapshot.collectionValues.size} collectionTags count=${snapshot.collectionTags.size} tagsByEntry=${snapshot.collectionTags.groupingBy { "${it.target}/${it.facet}" }.eachCount()}"
            }
        }
        yield()
        persistDownloads(snapshot.downloads)
    }

    private suspend fun readFromDatabase(): CatalogSnapshot {
        val shell = readCatalogShellFromDatabase()
        val tracks = readTracksFromDatabase()
        return shell.copy(
            tracksByParent = tracks.tracksByParent,
            popularTracksByLibrary = tracks.popularTracksByLibrary,
            downloads = tracks.downloads,
        )
    }

    private suspend fun readCatalogShellFromDatabase(): CatalogSnapshot {
        val artists = database.catalogQueries.selectArtists().awaitAsList().map {
            Artist(
                id = it.id,
                title = it.title,
                thumbUrl = it.thumbUrl,
                albumCount = it.albumCount.toInt(),
                songCount = it.songCount.toInt(),
                dateAddedMs = it.dateAddedMs,
                genre = it.genre,
                mood = it.mood,
                style = it.style,
                rating = it.rating?.toFloat(),
                favorite = it.favorite.toBool(),
            )
        }
        val albums = database.catalogQueries.selectAlbums().awaitAsList().map {
            Album(
                id = it.id,
                title = it.title,
                artist = it.artist,
                year = it.year?.toInt(),
                thumbUrl = it.thumbUrl,
                dateAddedMs = it.dateAddedMs,
                genre = it.genre,
                mood = it.mood,
                style = it.style,
                rating = it.rating?.toFloat(),
                favorite = it.favorite.toBool(),
                albumArtist = it.albumArtist,
                description = it.description,
                recordLabel = it.recordLabel,
                releaseDate = it.releaseDate,
            )
        }
        val playlists = database.catalogQueries.selectPlaylists().awaitAsList().map {
            Playlist(
                id = it.id,
                title = it.title,
                trackCount = it.trackCount.toInt(),
                key = it.plKey,
                thumbUrl = it.thumbUrl,
                rating = it.rating?.toFloat(),
                favorite = it.favorite.toBool(),
            )
        }
        return CatalogSnapshot(
            artists = artists,
            albums = albums,
            playlists = playlists,
            collectionValueLoads = database.catalogQueries.selectCollectionValueLoads().awaitAsList().map {
                CatalogCollectionValueLoad(
                    target = it.target,
                    facet = it.facet,
                )
            },
            collectionValues = database.catalogQueries.selectCollectionValues().awaitAsList().map {
                CatalogCollectionValue(
                    target = it.target,
                    facet = it.facet,
                    value = it.value_,
                    key = it.key,
                    fastKey = it.fastKey,
                    filterField = it.filterField,
                    itemsLoaded = it.itemsLoaded != 0L,
                )
            },
            collectionTags = database.catalogQueries.selectCollectionTags().awaitAsList().map {
                CatalogCollectionTag(
                    target = it.target,
                    facet = it.facet,
                    itemId = it.itemId,
                    value = it.value_,
                )
            },
        )
    }

    private suspend fun readTracksFromDatabase(): CatalogSnapshot {
        val activeLocalFolderIds = activeLocalFolderIds()
        val trackRows = database.catalogQueries.selectAllTracks()
            .awaitAsList()
            .filterNot { row -> row.id.isInactiveLocalFolderCatalogId(activeLocalFolderIds) }
        yield()
        val tracksById: Map<String, Track> = trackRows.associate { row ->
                row.id to Track(
                    id = row.id,
                    title = row.title,
                    artist = row.artist,
                    album = row.album,
                    durationMs = row.durationMs,
                    streamUrl = row.streamUrl,
                    downloadUrl = row.downloadUrl,
                    thumbUrl = row.thumbUrl,
                    localArtworkUri = row.localArtworkUri,
                    localUri = row.localUri,
                    year = row.year?.toInt(),
                    genre = row.genre,
                    mood = row.mood,
                    style = row.style,
                    filepath = row.filepath,
                    audioCodec = row.audioCodec,
                    bitrateKbps = row.bitrateKbps?.toInt(),
                    dateAddedMs = row.dateAddedMs,
                    rating = row.rating?.toFloat(),
                    parentAlbumId = row.parentAlbumId,
                )
            }
        yield()
        val tracksByParent: Map<String, List<Track>> = database.catalogQueries.selectTrackParents()
            .awaitAsList()
            .filterNot { row -> row.parentId.isInactiveLocalFolderCatalogId(activeLocalFolderIds) }
            .groupBy { it.parentId }
            .mapValues { (_, entries) ->
                entries.sortedBy { it.position }
                    .mapNotNull { entry ->
                        tracksById[entry.trackId]?.copy(playlistItemId = entry.playlistItemId)
                    }
            }
        val popularTracksByLibrary: Map<String, List<Track>> = database.catalogQueries.selectLibraryPopularTracks()
            .awaitAsList()
            .groupBy { it.libraryKey }
            .mapValues { (_, entries) ->
                entries.sortedBy { it.position }
                    .mapNotNull { entry -> tracksById[entry.trackId] }
            }
        val downloads = database.downloadsQueries.selectAll().awaitAsList().map { row -> row.toDownloadItem() }
        return CatalogSnapshot(
            tracksByParent = tracksByParent,
            popularTracksByLibrary = popularTracksByLibrary,
            downloads = downloads,
        )
    }

    private fun activeLocalFolderIds(): Set<String> =
        mediaSourcesRepository.state.value.localFolders
            .asSequence()
            .filter { it.enabled }
            .mapTo(mutableSetOf()) { it.id }

    private fun CatalogSnapshot.isNotEmpty(): Boolean =
        artists.isNotEmpty() ||
            albums.isNotEmpty() ||
            playlists.isNotEmpty() ||
            tracksByParent.isNotEmpty() ||
            collectionValues.isNotEmpty() ||
            collectionValueLoads.isNotEmpty() ||
            collectionTags.isNotEmpty() ||
            downloads.isNotEmpty()

    private fun CatalogSnapshot.hasCollectionValueLoad(entry: CollectionEntry): Boolean =
        collectionValueLoads.any { it.target == entry.target.name && it.facet == entry.facet.name }

    private fun CollectionTarget.toPlexCollectionTarget(): PlexCollectionTarget =
        when (this) {
            CollectionTarget.Artists -> PlexCollectionTarget.Artists
            CollectionTarget.Albums -> PlexCollectionTarget.Albums
        }

    private fun CollectionFacet.toPlexCollectionFacet(): PlexCollectionFacet =
        when (this) {
            CollectionFacet.Mood -> PlexCollectionFacet.Mood
            CollectionFacet.Style -> PlexCollectionFacet.Style
            CollectionFacet.Genre -> PlexCollectionFacet.Genre
        }

    private fun plexRatingKey(id: String): String? =
        if (id.startsWith("plex:")) id.removePrefix("plex:") else null

    private fun Artist.plexPopularMixRatingKey(): String? =
        plexRatingKey(id)
            ?.takeIf { it.isNotBlank() && !it.startsWith("album-artist-") }

    private fun String.normalizedArtistLookupKey(): String =
        trim().lowercase()

    private fun String.ratingKeyFromPlexPath(): String? =
        trim('/')
            .split('/')
            .dropWhile { it != "metadata" }
            .drop(1)
            .firstOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun String.normalizedFavoritePlaylistTitle(): String =
        trim().lowercase()

    private fun Track.plexIdentityKey(): String? =
        plexRatingKey(id) ?: id.takeIf { it.isNotBlank() && ':' !in it }

    private fun Track.hasSamePlexIdentity(otherId: String): Boolean {
        if (id == otherId) return true
        val selfKey = plexIdentityKey() ?: return false
        val otherKey = plexRatingKey(otherId) ?: otherId.takeIf { it.isNotBlank() && ':' !in it } ?: return false
        return selfKey == otherKey
    }

    /**
     * Lazy-loaded Plex tracks come back with raw rating keys (e.g. `46171`), but the rest of
     * the catalog stores them prefixed (`plex:46171`) because [CatalogMerge.withPrefix]
     * touches every id during initial sync. Without this fix, playlist mutations downstream
     * call `plexRatingKey(track.id)` which returns `null` for any lazy-loaded track, so the
     * Plex sync silently no-ops with `ratingKeys=[]`.
     */
    private fun Track.withPlexPrefix(): Track =
        if (id.startsWith("plex:")) this else copy(id = "plex:$id")

    private fun PlexSession?.libraryPopularTrackCacheKey(): String? {
        val serverId = this?.selectedServer?.id?.takeIf { it.isNotBlank() } ?: return null
        val libraryKey = selectedLibrary?.key?.takeIf { it.isNotBlank() } ?: return null
        return "plex:popular-mix-v2:$serverId:$libraryKey"
    }

    private fun publishRadioTracksInBackground(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        persistenceScope.launch {
            runCatching { publishIndexedPlexTracks(tracks) }
                .onFailure { error ->
                    PhoebeLog.d("CatalogRepository") {
                        "radio track publish failed: ${error.message}"
                    }
                }
        }
    }

    private fun Track.withJellyfinPrefix(): Track =
        if (id.startsWith("jellyfin:")) this else copy(
            id = "jellyfin:$id",
            parentAlbumId = parentAlbumId?.let { if (it.startsWith("jellyfin:")) it else "jellyfin:$it" },
        )

    private fun Track.withProviderPrefix(prefix: String): Track =
        if (id.startsWith("$prefix:")) this else copy(
            id = "$prefix:$id",
            parentAlbumId = parentAlbumId?.let { if (it.startsWith("$prefix:")) it else "$prefix:$it" },
        )

    private fun Album.withProviderPrefix(prefix: String): Album =
        if (id.startsWith("$prefix:")) this else copy(id = "$prefix:$id")

    private fun providerItemId(id: String, prefix: String): String? =
        id.removePrefix("$prefix:").takeIf { id.startsWith("$prefix:") && it.isNotBlank() }

    private fun providerTrackLookupIds(id: String): Set<String> {
        if (id.isBlank()) return emptySet()
        for (provider in MediaProviderType.entries) {
            val prefix = "${provider.catalogPrefix}:"
            if (id.startsWith(prefix)) {
                val bare = id.removePrefix(prefix)
                return setOf(id, bare)
            }
        }
        return buildSet {
            add(id)
            for (provider in MediaProviderType.entries) {
                add("${provider.catalogPrefix}:$id")
            }
        }
    }

    private fun CatalogSnapshot.findTrackByIds(ids: Set<String>): Track? {
        if (ids.isEmpty()) return null
        tracksByParent.values.forEach { parentTracks ->
            parentTracks.firstOrNull { track -> track.id in ids }?.let { return it }
        }
        return null
    }

    private fun jellyfinItemId(id: String): String? =
        id.removePrefix("jellyfin:").takeIf { id.startsWith("jellyfin:") && it.isNotBlank() }

    private fun jellyfinAlbumIdByTitle(albums: List<Album>, track: Track): String =
        albums.firstOrNull {
            it.title.equals(track.album, ignoreCase = true) &&
                it.artist.equals(track.artist, ignoreCase = true)
        }?.id.orEmpty()

    private fun CatalogSnapshot.remoteCatalogIdPrefix(): String? =
        artists.firstOrNull()?.id?.substringBefore(':')?.takeIf { it.isNotBlank() }
            ?: albums.firstOrNull()?.id?.substringBefore(':')?.takeIf { it.isNotBlank() }

    private fun jellyfinArtistsFromAlbums(albums: List<Album>): List<Artist> =
        albums
            .groupBy { it.artist.ifBlank { "Unknown artist" } }
            .values
            .map { artistAlbums ->
                val first = artistAlbums.first()
                Artist(
                    id = "album-artist-${first.id}",
                    title = first.artist.ifBlank { "Unknown artist" },
                    thumbUrl = first.thumbUrl,
                    albumCount = artistAlbums.size,
                    genre = artistAlbums.firstNotNullOfOrNull { it.genre },
                    rating = artistAlbums.firstNotNullOfOrNull { it.rating },
                    favorite = artistAlbums.any { it.favorite },
                )
            }

    private fun Track.shouldPreserveAcrossPlexRefresh(currentToken: String?): Boolean {
        if (isLocalMediaPlayback() || (isRemoteLibraryTrack() && !isPlexLibraryTrack()) || !isPlexLibraryTrack()) return true
        return currentToken != null && streamUrl.plexTokenQueryValue() == currentToken
    }

    private fun List<Track>.canUseCachedTracksForSession(session: PlexSession?): Boolean {
        if (!session.isPlex()) return true
        val currentToken = session.serverAuthToken()
        return all { it.shouldPreserveAcrossPlexRefresh(currentToken) }
    }

    private fun String.plexTokenQueryValue(): String? =
        runCatching { Url(this).parameters["X-Plex-Token"] }.getOrNull()

    private companion object {
        const val LegacyCatalogFile = "catalog.json"
        const val DecadeAlbumFetchTimeoutMs = 4_000L
        const val TrackIndexPageSize = 500
        const val MaxTrackIndexPages = 400
        const val DefaultPlexTrackIndexPageTimeoutMs = 30_000L
        const val SyncProgressUpdateIntervalMs = 600L
        const val PlaylistWarmParallelism = 4
        const val ArtistPopularTrackLimit = 12
        const val LibraryPopularSongLimit = 500
        const val LibraryPopularTracksPerArtist = 5
        const val ArtistSimilarArtistLimit = 20
        const val LikelyWarmAlbumCount = 50
        const val DecadeTrackPageSize = 250
        const val DecadeFirstPageSize = 80
        const val DecadeTrackLimit = 500
        const val ArtistStationLookupTimeoutMs = 8_000L
        const val JellyfinQuickSyncPlaylistsTimeoutMs = 30_000L
        const val MaxDecadeTrackPages = 4
        const val CollectionFacetTrackPageSize = 250
        const val CollectionFacetFirstPageSize = 80
        const val CollectionFacetTrackLimit = 500
        const val MaxCollectionFacetTrackPages = 4
        const val PersonalMixWarmBatchAlbums = 12
        const val PersonalMixWarmMaxAlbums = 48
    }
}

private fun Boolean.toDb(): Long = if (this) 1L else 0L
private fun Long.toBool(): Boolean = this != 0L

data class MetadataUpdateResult(
    val savedLocally: Boolean,
    val plexAttempted: Boolean,
    val plexSynced: Boolean,
)

fun defaultPlexRadioStations(library: MusicLibrary): List<PlexRadioStation> =
    listOf(
        PlexRadioStation(
            id = "library-${library.key}-library",
            title = "Library Radio",
            subtitle = "Plex radio from ${library.title}",
            key = "/library/sections/${library.key}/stations/library",
            category = PlexRadioStationCategory.Library,
        ),
        PlexRadioStation(
            id = "library-${library.key}-deep-cuts",
            title = "Deep Cuts Radio",
            subtitle = "Less obvious songs from ${library.title}",
            key = "/library/sections/${library.key}/stations/deepCuts",
            category = PlexRadioStationCategory.Library,
        ),
        PlexRadioStation(
            id = "library-${library.key}-time-travel",
            title = "Time Travel Radio",
            subtitle = "Music through the decades from ${library.title}",
            key = "/library/sections/${library.key}/stations/timeTravel",
            category = PlexRadioStationCategory.Library,
        ),
    )

internal fun mergePlexLibraryRadioStations(
    apiStations: List<PlexRadioStation>,
    defaults: List<PlexRadioStation>,
): List<PlexRadioStation> {
    if (apiStations.isEmpty()) return defaults
    val apiSlugs = apiStations.map { it.key.plexLibraryStationSlug().lowercase() }.toSet()
    val merged = apiStations.toMutableList()
    defaults.forEach { default ->
        if (default.key.plexLibraryStationSlug().lowercase() !in apiSlugs) {
            merged += default
        }
    }
    return merged.sortedBy { station ->
        plexLibraryStationSortOrder(station.key.plexLibraryStationSlug())
    }
}

private fun plexLibraryStationSortOrder(slug: String): Int =
    when (slug.lowercase()) {
        "library", "1" -> 0
        "deepcuts", "8" -> 1
        "timetravel" -> 2
        "randomalbum", "3" -> 3
        else -> 100
    }

private fun Float?.normalizedRating(): Float? =
    this?.coerceIn(0f, 5f)
        ?.let { kotlin.math.round(it * 2f) / 2f }
        ?.takeIf { it > 0f }

private fun Album.mergeAlbumDetails(details: Album, id: String): Album =
    copy(
        id = id,
        title = details.title.takeIf { it.isNotBlank() } ?: title,
        artist = details.artist.takeIf { it.isNotBlank() && it != "Unknown artist" } ?: artist,
        year = details.year ?: year,
        thumbUrl = details.thumbUrl?.takeIf { it.isNotBlank() } ?: thumbUrl,
        dateAddedMs = details.dateAddedMs ?: dateAddedMs,
        genre = details.genre?.takeIf { it.isNotBlank() } ?: genre,
        mood = details.mood?.takeIf { it.isNotBlank() } ?: mood,
        style = details.style?.takeIf { it.isNotBlank() } ?: style,
        rating = details.rating ?: rating,
        favorite = details.favorite,
        albumArtist = details.albumArtist?.takeIf { it.isNotBlank() } ?: albumArtist,
        description = details.description?.takeIf { it.isNotBlank() } ?: description,
        recordLabel = details.recordLabel?.takeIf { it.isNotBlank() } ?: recordLabel,
        releaseDate = details.releaseDate?.takeIf { it.isNotBlank() } ?: releaseDate,
    )

private fun CatalogSnapshot.withoutLocalFolderCatalog(): CatalogSnapshot =
    copy(
        artists = artists.filterNot { it.id.isLocalFolderCatalogId() },
        albums = albums.filterNot { it.id.isLocalFolderCatalogId() },
        tracksByParent = tracksByParent
            .filterKeys { !it.isLocalFolderCatalogId() }
            .mapValues { (parentId, tracks) ->
                if (parentId.isLocalPlaylistId()) {
                    tracks
                } else {
                    tracks.filterNot { it.id.isLocalFolderCatalogId() }
                }
            }
            .filterValues { it.isNotEmpty() },
        collectionTags = collectionTags.filterNot { it.itemId.isLocalFolderCatalogId() },
        collectionValueLoads = collectionValueLoads,
    )

private fun CatalogSnapshot.withoutProviderShell(prefix: String): CatalogSnapshot {
    val providerPrefix = "$prefix:"
    return copy(
        artists = artists.filterNot { it.id.startsWith(providerPrefix) },
        albums = albums.filterNot { it.id.startsWith(providerPrefix) },
        playlists = playlists.filterNot { it.id.startsWith(providerPrefix) },
        popularTracksByArtist = popularTracksByArtist.filterKeys { !it.startsWith(providerPrefix) },
        popularTracksByLibrary = popularTracksByLibrary.filterKeys { !it.startsWith(providerPrefix) },
        collectionTags = collectionTags.filterNot { it.itemId.startsWith(providerPrefix) },
    )
}

private fun String.isLocalPlaylistId(): Boolean = startsWith(LOCAL_PLAYLIST_ID_PREFIX)

private fun String.isLocalFolderCatalogId(): Boolean = startsWith("local_")

private fun CatalogSnapshot.withoutInactiveLocalFolderCatalog(activeFolderIds: Set<String>): CatalogSnapshot =
    copy(
        artists = artists.filterNot { it.id.isInactiveLocalFolderCatalogId(activeFolderIds) },
        albums = albums.filterNot { it.id.isInactiveLocalFolderCatalogId(activeFolderIds) },
        tracksByParent = tracksByParent
            .filterKeys { !it.isInactiveLocalFolderCatalogId(activeFolderIds) }
            .mapValues { (_, tracks) ->
                tracks.filterNot { it.id.isInactiveLocalFolderCatalogId(activeFolderIds) }
            }
            .filterValues { it.isNotEmpty() },
        collectionTags = collectionTags.filterNot { it.itemId.isInactiveLocalFolderCatalogId(activeFolderIds) },
    )

private fun String.isInactiveLocalFolderCatalogId(activeFolderIds: Set<String>): Boolean {
    val folderId = localFolderCatalogId() ?: return false
    return folderId !in activeFolderIds
}

private fun String.localFolderCatalogId(): String? {
    if (!startsWith("local_")) return null
    return removePrefix("local_")
        .substringBefore(':')
        .takeIf { it.isNotBlank() }
}
