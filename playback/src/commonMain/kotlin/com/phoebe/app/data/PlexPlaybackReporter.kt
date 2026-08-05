package com.phoebe.app.data

import com.phoebe.app.domain.PlayerState
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.catalogPrefix
import com.phoebe.app.domain.isEmbyFamily
import com.phoebe.app.domain.isPlex
import com.phoebe.app.domain.isPlexLibraryTrack
import com.phoebe.app.domain.isRemoteLibraryTrack
import com.phoebe.app.domain.serverAuthToken
import com.phoebe.app.player.AudioPlayer
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.sources.CatalogMerge
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reports Plex library playback to the server's timeline API so Plex can mark tracks played
 * and scrobble to linked services (ListenBrainz, Last.fm, etc.).
 */
class PlexPlaybackReporter(
    private val plexClient: PlexClient,
    private val jellyfinClient: JellyfinClient,
    private val providerRegistry: MusicProviderRegistry = MusicProviderRegistry(emptyList()),
    private val audioPlayer: AudioPlayer,
    private val session: StateFlow<PlexSession?>,
) {
    private val playbackSessionId = newPlaybackSessionId()
    private var machineIdentifier: String? = null
    private var playQueueItemByRatingKey: Map<String, Long> = emptyMap()
    private var lastPlayQueueSignature: String? = null
    private var failedPlayQueueSignature: String? = null
    private var failedPlayQueueRetryAtMs: Long = 0L
    private val mutablePlayHistoryChanged = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    val playHistoryChanged: SharedFlow<Unit> = mutablePlayHistoryChanged.asSharedFlow()

    fun start(scope: CoroutineScope) {
        start(scope, includePeriodicTimeline = true)
    }

    internal fun start(scope: CoroutineScope, includePeriodicTimeline: Boolean) {
        scope.launch { watchPlaybackState() }
        if (includePeriodicTimeline) {
            scope.launch { periodicTimelineWhilePlaying() }
        }
    }

    suspend fun markPlayed(track: Track, playedAtMs: Long) {
        if (!track.isRemoteLibraryTrack()) return
        val sess = session.value
        if (sess.isEmbyFamily()) {
            val server = sess?.selectedServer ?: return
            val prefix = sess.providerType.catalogPrefix
            runCatching {
                val adapter = providerRegistry.adapterFor(sess)
                if (adapter != null && prefix != "jellyfin") {
                    adapter.markPlayed(sess, track, playedAtMs)
                } else {
                    jellyfinClient.markPlayed(
                        server = server,
                        token = sess.token,
                        userId = sess.userId ?: return@runCatching,
                        itemId = track.id.removePrefix("$prefix:"),
                    )
                }
                notifyPlayHistoryChanged()
            }.onFailure { e ->
                if (e is CancellationException) throw e
                PhoebeLog.d("PlexPlaybackReporter") { "${sess.providerType.name} mark played failed: ${e.message}" }
            }
            return
        }
        if (sess != null && !sess.isPlex()) {
            runCatching {
                val adapter = providerRegistry.adapterFor(sess) ?: return@runCatching
                adapter.markPlayed(sess, track, playedAtMs)
                notifyPlayHistoryChanged()
            }.onFailure { e ->
                if (e is CancellationException) throw e
                PhoebeLog.d("PlexPlaybackReporter") { "${sess.providerType.name} mark played failed: ${e.message}" }
            }
            return
        }

        val ratingKey = plexRatingKey(track.id) ?: return
        val server = sess?.selectedServer ?: return
        val token = sess.serverAuthToken() ?: return
        runCatching {
            plexClient.markPlayed(server = server, token = token, ratingKey = ratingKey)
            notifyPlayHistoryChanged()
        }.onFailure { e ->
            if (e is CancellationException) throw e
            PhoebeLog.d("PlexPlaybackReporter") { "Plex mark played failed: ${e.message}" }
        }
    }

    private suspend fun watchPlaybackState() {
        var lastTrack: Track? = null
        var lastPositionMs: Long = 0L
        var lastIsPlaying: Boolean? = null
        var lastSession: PlexSession? = null
        var stoppedTrackId: String? = null

        suspend fun reportLastStopped(sess: PlexSession?, continuing: Boolean) {
            val track = lastTrack ?: return
            if (stoppedTrackId == track.id) return
            reportStopped(track, lastPositionMs, sess ?: lastSession, continuing)
            stoppedTrackId = track.id
        }

        try {
            combine(
                audioPlayer.state.distinctUntilChangedBy { player ->
                    PlaybackReporterKey(
                        trackId = player.currentTrack?.id,
                        isPlaying = player.isPlaying,
                        isBuffering = player.isBuffering,
                        queueSize = player.queue.size,
                        currentIndex = player.currentIndex,
                    )
                },
                session,
            ) { player, sess -> player to sess }
                .collect { (player, sess) ->
                    if (sess != null) lastSession = sess
                    val track = player.currentTrack
                    if (track == null || !track.isRemoteLibraryTrack()) {
                        reportLastStopped(sess, continuing = false)
                        clearPlayQueue()
                        lastTrack = null
                        lastPositionMs = 0L
                        lastIsPlaying = null
                        stoppedTrackId = null
                        return@collect
                    }

                    val previousTrack = lastTrack
                    val previousIsPlaying = lastIsPlaying
                    if (previousTrack != null && previousTrack.id != track.id) {
                        reportLastStopped(sess, continuing = true)
                    }

                    val isPlaying = player.isPlaying
                    val stoppedAtEnd = previousTrack?.id == track.id &&
                        previousIsPlaying == true &&
                        !isPlaying &&
                        shouldReportStoppedAtRest(track, player)
                    lastTrack = track
                    lastPositionMs = player.positionMs
                    lastIsPlaying = isPlaying

                    ensurePlayQueue(sess, player)

                    when {
                        stoppedAtEnd -> {
                            reportStopped(track, player.positionMs, sess ?: lastSession, continuing = false)
                            stoppedTrackId = track.id
                        }
                        previousTrack?.id != track.id || previousIsPlaying != isPlaying -> {
                            val state = if (isPlaying) PlexTimelineState.Playing else PlexTimelineState.Paused
                            reportTimeline(sess, track, player, state)
                            if (isPlaying) stoppedTrackId = null
                        }
                        isPlaying -> stoppedTrackId = null
                    }
                }
        } finally {
            withContext(NonCancellable + Dispatchers.Default) {
                withTimeoutOrNull(ShutdownStopReportTimeoutMs) {
                    reportLastStopped(lastSession, continuing = false)
                }
            }
        }
    }

    private suspend fun periodicTimelineWhilePlaying() {
        while (true) {
            delay(TimelineIntervalMs)
            val player = audioPlayer.state.value
            val track = player.currentTrack ?: continue
            if (!player.isPlaying || !track.isRemoteLibraryTrack()) continue
            ensurePlayQueue(session.value, player)
            reportTimeline(session.value, track, player, PlexTimelineState.Playing)
        }
    }

    private suspend fun ensurePlayQueue(sess: PlexSession?, player: PlayerState) {
        if (!sess.isPlex()) return
        runCatching {
            val server = sess?.selectedServer ?: return@runCatching
            val token = sess.serverAuthToken() ?: return@runCatching
            val queueWindow = plexPlayQueueWindow(player)
            val ratingKeys = queueWindow.mapNotNull { plexRatingKey(it.id) }
            if (ratingKeys.isEmpty()) return@runCatching
            val signature = ratingKeys.joinToString(",")
            if (signature == lastPlayQueueSignature && playQueueItemByRatingKey.isNotEmpty()) return@runCatching
            val now = currentTimeMs()
            if (signature == failedPlayQueueSignature && now < failedPlayQueueRetryAtMs) return@runCatching

            val startKey = player.currentTrack?.let { plexRatingKey(it.id) } ?: return@runCatching
            val machineId = machineIdentifier
                ?: plexClient.machineIdentifier(server, token).also { machineIdentifier = it }

            val queue = plexClient.createAudioPlayQueue(server, token, machineId, ratingKeys, startKey)
            if (queue == null) {
                failedPlayQueueSignature = signature
                failedPlayQueueRetryAtMs = now + PlayQueueFailureBackoffMs
                return@runCatching
            }
            playQueueItemByRatingKey = queue.itemIdByRatingKey
            lastPlayQueueSignature = signature
            failedPlayQueueSignature = null
            failedPlayQueueRetryAtMs = 0L
        }.onFailure { e ->
            if (e is CancellationException) throw e
            PhoebeLog.d("PlexPlaybackReporter") { "play queue setup failed: ${e.message}" }
        }
    }

    private fun clearPlayQueue() {
        playQueueItemByRatingKey = emptyMap()
        lastPlayQueueSignature = null
        failedPlayQueueSignature = null
        failedPlayQueueRetryAtMs = 0L
    }

    private fun plexPlayQueueWindow(player: PlayerState): List<Track> {
        if (player.queue.size <= MaxPlayQueueItems) return player.queue
        val currentId = player.currentTrack?.id
        val currentIndex = player.queue.indexOfFirst { it.id == currentId }
            .takeIf { it >= 0 }
            ?: player.currentIndex.coerceIn(0, player.queue.lastIndex)
        val halfWindow = MaxPlayQueueItems / 2
        val start = (currentIndex - halfWindow)
            .coerceIn(0, (player.queue.size - MaxPlayQueueItems).coerceAtLeast(0))
        return player.queue.subList(start, start + MaxPlayQueueItems)
    }

    private suspend fun reportStopped(
        track: Track,
        positionMs: Long,
        sess: PlexSession?,
        continuing: Boolean,
    ) {
        val durationMs = track.durationMs.coerceAtLeast(0L)
        val timeMs = if (durationMs > 0L) positionMs.coerceAtMost(durationMs) else positionMs
        if (sess.isEmbyFamily()) {
            val server = sess?.selectedServer ?: return
            val prefix = sess.providerType.catalogPrefix
            runCatching {
                val adapter = providerRegistry.adapterFor(sess)
                if (adapter != null && prefix != "jellyfin") {
                    adapter.reportPlayback(sess, track, timeMs, isPaused = false, event = JellyfinPlaybackEvent.Stop)
                } else {
                    jellyfinClient.reportPlayback(server, sess.token, track.id.removePrefix("$prefix:"), timeMs, isPaused = false, event = JellyfinPlaybackEvent.Stop)
                }
                notifyPlayHistoryChanged()
            }.onFailure { e ->
                if (e is CancellationException) throw e
            }
            return
        }
        if (sess != null && !sess.isPlex()) {
            runCatching {
                providerRegistry.adapterFor(sess)?.reportPlayback(sess, track, timeMs, isPaused = false, event = JellyfinPlaybackEvent.Stop)
                notifyPlayHistoryChanged()
            }.onFailure { e ->
                if (e is CancellationException) throw e
            }
            return
        }
        val ratingKey = plexRatingKey(track.id) ?: return
        val server = sess?.selectedServer ?: return
        val token = sess.serverAuthToken() ?: return
        runCatching {
            plexClient.reportTimeline(
                server = server,
                token = token,
                sessionIdentifier = playbackSessionId,
                ratingKey = ratingKey,
                timeMs = timeMs,
                durationMs = durationMs,
                state = PlexTimelineState.Stopped,
                continuing = continuing,
                playQueueItemId = playQueueItemByRatingKey[ratingKey],
            )
            notifyPlayHistoryChanged()
        }.onFailure { e ->
            if (e is CancellationException) throw e
            PhoebeLog.d("PlexPlaybackReporter") { "stopped timeline failed: ${e.message}" }
        }
    }

    private fun notifyPlayHistoryChanged() {
        mutablePlayHistoryChanged.tryEmit(Unit)
    }

    private suspend fun reportTimeline(
        sess: PlexSession?,
        track: Track,
        player: PlayerState,
        state: PlexTimelineState,
    ) {
        if (sess.isEmbyFamily()) {
            val server = sess?.selectedServer ?: return
            val prefix = sess.providerType.catalogPrefix
            runCatching {
                val event = if (state == PlexTimelineState.Playing && player.positionMs < 2_000L) JellyfinPlaybackEvent.Start else JellyfinPlaybackEvent.Progress
                val adapter = providerRegistry.adapterFor(sess)
                if (adapter != null && prefix != "jellyfin") {
                    adapter.reportPlayback(sess, track, player.positionMs, state != PlexTimelineState.Playing, event)
                } else {
                    jellyfinClient.reportPlayback(
                        server = server,
                        token = sess.token,
                        itemId = track.id.removePrefix("$prefix:"),
                        positionMs = player.positionMs,
                        isPaused = state != PlexTimelineState.Playing,
                        event = event,
                    )
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                PhoebeLog.d("PlexPlaybackReporter") { "${sess.providerType.name} playback report failed: ${e.message}" }
            }
            return
        }
        if (sess != null && !sess.isPlex()) {
            runCatching {
                val event = if (state == PlexTimelineState.Playing && player.positionMs < 2_000L) JellyfinPlaybackEvent.Start else JellyfinPlaybackEvent.Progress
                providerRegistry.adapterFor(sess)?.reportPlayback(sess, track, player.positionMs, state != PlexTimelineState.Playing, event)
            }.onFailure { e ->
                if (e is CancellationException) throw e
            }
            return
        }
        val ratingKey = plexRatingKey(track.id) ?: return
        val server = sess?.selectedServer ?: return
        val token = sess.serverAuthToken() ?: return
        val durationMs = track.durationMs.takeIf { it > 0L } ?: player.durationMs
        runCatching {
            plexClient.reportTimeline(
                server = server,
                token = token,
                sessionIdentifier = playbackSessionId,
                ratingKey = ratingKey,
                timeMs = player.positionMs,
                durationMs = durationMs,
                state = state,
                playQueueItemId = playQueueItemByRatingKey[ratingKey],
            )
        }.onFailure { e ->
            if (e is CancellationException) throw e
            PhoebeLog.d("PlexPlaybackReporter") { "timeline failed: ${e.message}" }
        }
    }

    internal companion object {
        const val TimelineIntervalMs = 10_000L
        const val PlayQueueFailureBackoffMs = 10L * 60L * 1000L
        const val MaxPlayQueueItems = 200
        const val ShutdownStopReportTimeoutMs = 3_000L
        const val StopNearEndGraceMs = 2_000L
        const val StopPlayedFraction = 0.9

        fun plexRatingKey(trackId: String): String? =
            CatalogMerge.stripPlexId(trackId).takeIf { trackId.startsWith("plex:") }

        fun newPlaybackSessionId(): String =
            "phoebe-${currentTimeMs()}-${Random.nextInt(1_000_000)}"

        fun shouldReportStoppedAtRest(track: Track, player: PlayerState): Boolean {
            val durationMs = track.durationMs
                .takeIf { it > 0L }
                ?: player.durationMs.takeIf { it > 0L }
                ?: return false
            val nearEndThresholdMs = (durationMs - StopNearEndGraceMs)
                .coerceAtLeast((durationMs * StopPlayedFraction).toLong())
            return player.positionMs.coerceAtLeast(0L) >= nearEndThresholdMs
        }
    }
}

private data class PlaybackReporterKey(
    val trackId: String?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val queueSize: Int,
    val currentIndex: Int,
)
