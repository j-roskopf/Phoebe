package com.phoebe.app.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.mapToList
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.domain.MostPlayedEntry
import com.phoebe.app.domain.PlayHistoryKind
import com.phoebe.app.domain.RecentlyPlayedEntry
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.PhoebeDispatchers
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Max rows loaded eagerly for home derivation and catalog warm hints. */
const val PlayHistoryTopListCapacity = 200
private const val RemotePlayMergeWindowMs = 10L * 60L * 1000L

/**
 * Tracks per-track play timestamps. Surfaces "last played" aggregates per
 * artist / album / track so the library UI can show how recently each entry
 * was heard.
 *
 * Backed by SQLDelight's `asFlow()`, so each insert into `PlayHistoryRow`
 * automatically re-runs the aggregate queries while the exposed [StateFlow]s
 * are observed — no manual refresh required.
 */
@SingleIn(AppScope::class)
@Inject
class PlayHistoryRepository(
    private val database: PhoebeDatabase,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val databaseDispatcher = PhoebeDispatchers.io
    private val sharingStarted = SharingStarted.WhileSubscribed(5_000L)

    val lastPlayedByArtist: StateFlow<Map<String, Long>> = database.playHistoryQueries
        .selectLastPlayedByArtist()
        .asFlow()
        .mapToList(databaseDispatcher)
        .map { rows ->
            buildMap(rows.size) {
                for (row in rows) row.lastPlayed?.let { put(row.artist, it) }
            }
        }
        .stateIn(scope, sharingStarted, emptyMap())

    val lastPlayedByAlbum: StateFlow<Map<String, Long>> = database.playHistoryQueries
        .selectLastPlayedByAlbum()
        .asFlow()
        .mapToList(databaseDispatcher)
        .map { rows ->
            buildMap(rows.size) {
                for (row in rows) row.lastPlayed?.let { put(row.album, it) }
            }
        }
        .stateIn(scope, sharingStarted, emptyMap())

    val lastPlayedByTrack: StateFlow<Map<String, Long>> = database.playHistoryQueries
        .selectLastPlayedByTrack()
        .asFlow()
        .mapToList(databaseDispatcher)
        .map { rows ->
            buildMap(rows.size) {
                for (row in rows) row.lastPlayed?.let { put(row.track_id, it) }
            }
        }
        .stateIn(scope, sharingStarted, emptyMap())

    val playCountsByTrack: StateFlow<Map<String, Long>> = database.playHistoryQueries
        .selectPlayCountsByTrack()
        .asFlow()
        .mapToList(databaseDispatcher)
        .map { rows ->
            buildMap(rows.size) {
                for (row in rows) put(row.track_id, row.playCount ?: 0L)
            }
        }
        .stateIn(scope, sharingStarted, emptyMap())

    val topMostPlayed: StateFlow<List<MostPlayedEntry>> = database.playHistoryQueries
        .selectTopMostPlayedByTrack(PlayHistoryTopListCapacity.toLong())
        .asFlow()
        .mapToList(databaseDispatcher)
        .map { rows ->
            rows.map { row ->
                MostPlayedEntry(
                    trackId = row.track_id,
                    playCount = row.playCount ?: 0L,
                    lastPlayedMs = row.lastPlayedMs,
                    artist = row.artist,
                    album = row.album,
                )
            }
        }
        .stateIn(scope, sharingStarted, emptyList())

    val topRecentlyPlayed: StateFlow<List<RecentlyPlayedEntry>> = database.playHistoryQueries
        .selectTopRecentlyPlayedByTrack(PlayHistoryTopListCapacity.toLong())
        .asFlow()
        .mapToList(databaseDispatcher)
        .map { rows ->
            rows.map { row ->
                RecentlyPlayedEntry(
                    trackId = row.track_id,
                    lastPlayedMs = row.lastPlayedMs ?: 0L,
                    artist = row.artist,
                    album = row.album,
                )
            }
        }
        .stateIn(scope, sharingStarted, emptyList())

    val playEventsByTrack: StateFlow<Map<String, List<Long>>> = database.playHistoryQueries
        .selectLatestPlayEventsByTrack()
        .asFlow()
        .mapToList(databaseDispatcher)
        .map { rows ->
            buildMap {
                for (row in rows) {
                    val plays = getOrPut(row.track_id) { mutableListOf() }
                    plays.add(row.played_at_ms)
                }
            }
        }
        .stateIn(scope, sharingStarted, emptyMap())

    /**
     * Kept for the common restore sequence. Aggregate flows start collecting
     * when their UI consumers observe them and stop after the subscription
     * timeout when the UI leaves.
     */
    suspend fun restore() {
        // No-op — see class docs.
    }

    suspend fun queryTopMostPlayed(limit: Int): List<MostPlayedEntry> =
        withContext(databaseDispatcher) {
            database.playHistoryQueries
                .selectTopMostPlayedByTrack(limit.coerceAtLeast(0).toLong())
                .awaitAsList()
                .map { row ->
                    MostPlayedEntry(
                        trackId = row.track_id,
                        playCount = row.playCount ?: 0L,
                        lastPlayedMs = row.lastPlayedMs,
                        artist = row.artist,
                        album = row.album,
                    )
                }
        }

    suspend fun queryTopRecentlyPlayed(limit: Int): List<RecentlyPlayedEntry> =
        withContext(databaseDispatcher) {
            database.playHistoryQueries
                .selectTopRecentlyPlayedByTrack(limit.coerceAtLeast(0).toLong())
                .awaitAsList()
                .map { row ->
                    RecentlyPlayedEntry(
                        trackId = row.track_id,
                        lastPlayedMs = row.lastPlayedMs ?: 0L,
                        artist = row.artist,
                        album = row.album,
                    )
                }
        }

    suspend fun queryRankedEntries(
        kind: PlayHistoryKind,
        limit: Int,
    ): PlayHistoryRankedEntries =
        when (kind) {
            PlayHistoryKind.MostPlayed -> withContext(databaseDispatcher) {
                PlayHistoryRankedEntries(
                    kind = kind,
                    totalCount = database.playHistoryQueries
                        .selectMostPlayedTrackCount()
                        .awaitAsOne()
                        .toInt(),
                    mostPlayed = queryTopMostPlayed(limit),
                )
            }
            PlayHistoryKind.RecentlyPlayed -> withContext(databaseDispatcher) {
                PlayHistoryRankedEntries(
                    kind = kind,
                    totalCount = database.playHistoryQueries
                        .selectRecentlyPlayedTrackCount()
                        .awaitAsOne()
                        .toInt(),
                    recentlyPlayed = queryTopRecentlyPlayed(limit),
                )
            }
        }

    /**
     * Persist a fresh play event for [track]. The eagerly-subscribed [StateFlow]s
     * react automatically when SQLDelight notifies that the table changed.
     */
    suspend fun recordPlay(track: Track, atMs: Long) {
        if (track.id.isBlank()) return
        val cleanArtist = track.artist.ifBlank { "Unknown Artist" }
        val cleanAlbum = track.album.ifBlank { "Unknown Album" }
        withContext(databaseDispatcher) {
            database.playHistoryQueries.recordPlay(
                track_id = track.id,
                artist = cleanArtist,
                album = cleanAlbum,
                played_at_ms = atMs,
            )
        }
    }

    suspend fun maxImportedPlexPlayedAt(serverId: String): Long? =
        withContext(databaseDispatcher) {
            database.playHistoryQueries.selectMaxImportedPlexPlayedAt(serverId).awaitAsOneOrNull()?.lastPlayed
        }

    suspend fun maxImportedRemotePlayedAt(source: String, serverId: String): Long? =
        withContext(databaseDispatcher) {
            database.playHistoryQueries.selectMaxImportedRemotePlayedAt(source, serverId).awaitAsOneOrNull()?.lastPlayed
        }

    suspend fun maxImportedRemoteStatsLastPlayedAt(source: String, serverId: String): Long? =
        withContext(databaseDispatcher) {
            database.playHistoryQueries.selectMaxImportedRemoteStatsLastPlayedAt(source, serverId).awaitAsOneOrNull()?.lastPlayed
        }

    suspend fun maxPlexStatsLastPlayedAt(serverId: String): Long? =
        withContext(databaseDispatcher) {
            database.playHistoryQueries.selectMaxPlexStatsLastPlayedAt(serverId).awaitAsOneOrNull()?.lastPlayed
        }

    suspend fun importPlexPlay(
        track: Track,
        serverId: String,
        historyKey: String,
        playedAtMs: Long,
        importedAtMs: Long,
        mergeWindowMs: Long,
    ): Boolean {
        if (track.id.isBlank() || historyKey.isBlank()) return false
        val cleanArtist = track.artist.ifBlank { "Unknown Artist" }
        val cleanAlbum = track.album.ifBlank { "Unknown Album" }
        return withContext(databaseDispatcher) {
            val alreadyImported = database.playHistoryQueries
                .selectImportedPlexHistoryKey(historyKey)
                .awaitAsOneOrNull() != null
            if (alreadyImported) return@withContext false

            val candidatePlayedAtMs = database.playHistoryQueries
                .selectLocalMergeCandidate(
                    track_id = track.id,
                    played_at_ms = playedAtMs - mergeWindowMs,
                    played_at_ms_ = playedAtMs + mergeWindowMs,
                )
                .awaitAsOneOrNull()

            if (candidatePlayedAtMs != null) {
                database.playHistoryQueries.markLocalPlayAsImportedPlex(
                    played_at_ms = playedAtMs,
                    plex_server_id = serverId,
                    plex_history_key = historyKey,
                    plex_imported_at_ms = importedAtMs,
                    track_id = track.id,
                    played_at_ms_ = candidatePlayedAtMs,
                )
            } else {
                database.playHistoryQueries.insertImportedPlexPlay(
                    track_id = track.id,
                    artist = cleanArtist,
                    album = cleanAlbum,
                    played_at_ms = playedAtMs,
                    plex_server_id = serverId,
                    plex_history_key = historyKey,
                    plex_imported_at_ms = importedAtMs,
                )
            }
            true
        }
    }

    suspend fun importPlexPlayCountFallback(
        track: Track,
        serverId: String,
        lastPlayedAtMs: Long,
        playCount: Long,
        importedAtMs: Long,
    ): Int = upsertRemotePlayCountAggregate(
        track = track,
        source = "plex-stats",
        serverId = serverId,
        lastPlayedAtMs = lastPlayedAtMs,
        playCount = playCount,
        importedAtMs = importedAtMs,
    )

    suspend fun importPlexPlayCountFallbackBatch(
        stats: List<PlexTrackPlaybackStat>,
        serverId: String,
        tracksById: Map<String, Track> = emptyMap(),
        importedAtMs: Long,
    ): Int {
        if (stats.isEmpty() || serverId.isBlank()) return 0
        return withContext(databaseDispatcher) {
            var imported = 0
            for (stat in stats) {
                val track = stat.toPlayHistoryTrack(tracksById["plex:${stat.ratingKey}"])
                imported += upsertRemotePlayCountAggregateInTransaction(
                    track = track,
                    source = "plex-stats",
                    serverId = serverId,
                    lastPlayedAtMs = stat.lastViewedAtMs ?: 0L,
                    playCount = stat.viewCount,
                    importedAtMs = importedAtMs,
                )
            }
            imported
        }
    }

    suspend fun importRemotePlayCountFallback(
        track: Track,
        source: String,
        serverId: String,
        lastPlayedAtMs: Long,
        playCount: Long,
        importedAtMs: Long,
    ): Int = upsertRemotePlayCountAggregate(
        track = track,
        source = "$source-stats",
        serverId = serverId,
        lastPlayedAtMs = lastPlayedAtMs,
        playCount = playCount,
        importedAtMs = importedAtMs,
    )

    suspend fun importRemotePlay(
        track: Track,
        source: String,
        serverId: String,
        historyKey: String,
        playedAtMs: Long,
        importedAtMs: Long,
        mergeWindowMs: Long = RemotePlayMergeWindowMs,
    ): Boolean {
        if (track.id.isBlank() || historyKey.isBlank()) return false
        val cleanArtist = track.artist.ifBlank { "Unknown Artist" }
        val cleanAlbum = track.album.ifBlank { "Unknown Album" }
        return withContext(databaseDispatcher) {
            val alreadyImported = database.playHistoryQueries
                .selectImportedPlexHistoryKey(historyKey)
                .awaitAsOneOrNull() != null
            if (alreadyImported) return@withContext false

            val candidatePlayedAtMs = database.playHistoryQueries
                .selectLocalMergeCandidate(
                    track_id = track.id,
                    played_at_ms = playedAtMs - mergeWindowMs,
                    played_at_ms_ = playedAtMs + mergeWindowMs,
                )
                .awaitAsOneOrNull()

            if (candidatePlayedAtMs != null) {
                database.playHistoryQueries.markLocalPlayAsImportedRemote(
                    played_at_ms = playedAtMs,
                    source = source,
                    plex_server_id = serverId,
                    plex_history_key = historyKey,
                    plex_imported_at_ms = importedAtMs,
                    track_id = track.id,
                    played_at_ms_ = candidatePlayedAtMs,
                )
            } else {
                database.playHistoryQueries.insertImportedRemotePlay(
                    track_id = track.id,
                    artist = cleanArtist,
                    album = cleanAlbum,
                    played_at_ms = playedAtMs,
                    source = source,
                    plex_server_id = serverId,
                    plex_history_key = historyKey,
                    plex_imported_at_ms = importedAtMs,
                )
            }
            true
        }
    }

    private suspend fun upsertRemotePlayCountAggregate(
        track: Track,
        source: String,
        serverId: String,
        lastPlayedAtMs: Long,
        playCount: Long,
        importedAtMs: Long,
    ): Int = withContext(databaseDispatcher) {
        upsertRemotePlayCountAggregateInTransaction(
            track = track,
            source = source,
            serverId = serverId,
            lastPlayedAtMs = lastPlayedAtMs,
            playCount = playCount,
            importedAtMs = importedAtMs,
        )
    }

    private suspend fun upsertRemotePlayCountAggregateInTransaction(
        track: Track,
        source: String,
        serverId: String,
        lastPlayedAtMs: Long,
        playCount: Long,
        importedAtMs: Long,
    ): Int {
        if (track.id.isBlank() || source.isBlank() || serverId.isBlank() || playCount <= 0L) return 0
        val cleanArtist = track.artist.ifBlank { "Unknown Artist" }
        val cleanAlbum = track.album.ifBlank { "Unknown Album" }
        val cappedCount = playCount.coerceAtLeast(1L)
        val existing = database.playHistoryQueries
            .selectPlayCountAggregateByTrack(track.id)
            .awaitAsOneOrNull()
        val mergedCount = maxOf(existing?.play_count ?: 0L, cappedCount)
        val mergedLastPlayed = maxOf(existing?.last_played_at_ms ?: 0L, lastPlayedAtMs.coerceAtLeast(0L))
        database.playHistoryQueries.insertPlayCountAggregate(
            track_id = track.id,
            artist = cleanArtist,
            album = cleanAlbum,
            play_count = mergedCount,
            last_played_at_ms = mergedLastPlayed,
            source = source,
            server_id = serverId,
            imported_at_ms = importedAtMs,
        )
        return 1
    }

    /** Cancel background aggregate collectors. Call before closing the backing [SqlDriver] in tests. */
    fun close() {
        job.cancel()
    }

    /** Like [close], but waits until eager collectors have stopped. */
    suspend fun closeAndJoin() {
        job.cancelAndJoin()
    }
}
