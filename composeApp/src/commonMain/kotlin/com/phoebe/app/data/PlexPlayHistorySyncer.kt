package com.phoebe.app.data

import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.isPlexLibraryTrack
import com.phoebe.app.domain.serverAuthToken
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.currentTimeMs
import kotlinx.coroutines.CancellationException

class PlexPlayHistorySyncer(
    private val plexClient: PlexClient,
    private val playHistoryRepository: PlayHistoryRepository,
) {
    suspend fun sync(session: PlexSession?, catalog: CatalogSnapshot): PlexPlayHistorySyncResult {
        val server = session?.selectedServer ?: return PlexPlayHistorySyncResult.Skipped
        val library = session.selectedLibrary ?: return PlexPlayHistorySyncResult.Skipped
        val token = session.serverAuthToken() ?: return PlexPlayHistorySyncResult.Skipped
        val tracksById = catalog.tracksByParent.values
            .asSequence()
            .flatten()
            .filter { it.isPlexLibraryTrack() }
            .distinctBy { it.id }
            .associateBy { it.id }

        val latestImported = playHistoryRepository.maxImportedPlexPlayedAt(server.id)
        val minViewedAtMs = latestImported?.minus(IncrementalLookbackMs)?.coerceAtLeast(0L)
        val importedAtMs = currentTimeMs()
        var start = 0
        var imported = 0
        var seen = 0

        runCatching {
            while (start < PageSize * MaxPages) {
                val page = plexClient.playbackHistoryPage(
                    server = server,
                    token = token,
                    library = library,
                    minViewedAtMs = minViewedAtMs,
                    start = start,
                    size = PageSize,
                )
                seen += page.entries.size
                for (entry in page.entries) {
                    if (entry.type != null && entry.type != PlexTrackTypeName) continue
                    if (entry.librarySectionId != null && entry.librarySectionId != library.key) continue
                    val track = tracksById["plex:${entry.ratingKey}"] ?: entry.toHistoryTrack()
                    if (playHistoryRepository.importPlexPlay(
                            track = track.withPlexHistoryFallbacks(entry),
                            serverId = server.id,
                            historyKey = entry.historyKey,
                            playedAtMs = entry.viewedAtMs,
                            importedAtMs = importedAtMs,
                            mergeWindowMs = MergeWindowMs,
                        )
                    ) {
                        imported += 1
                    }
                }

                val total = page.totalSize
                val next = page.offset + page.size
                if (page.size <= 0 || (total != null && next >= total)) break
                start = next
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            PhoebeLog.d("PlexPlayHistorySyncer") {
                "Plex history endpoint failed, falling back to track view counts: ${error.message}"
            }
            return syncTrackPlaybackStats(server, library, token, tracksById, importedAtMs)
        }

        PhoebeLog.d("PlexPlayHistorySyncer") {
            "synced Plex play history → seen=$seen imported=$imported minViewedAtMs=$minViewedAtMs"
        }
        return PlexPlayHistorySyncResult.Synced(imported = imported, seen = seen)
    }

    private suspend fun syncTrackPlaybackStats(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        tracksById: Map<String, Track>,
        importedAtMs: Long,
    ): PlexPlayHistorySyncResult {
        var start = 0
        var seen = 0
        var imported = 0
        while (start < PlaybackStatsPageSize * PlaybackStatsMaxPages) {
            val stats = plexClient.trackPlaybackStatsPage(
                server = server,
                library = library,
                token = token,
                start = start,
                size = PlaybackStatsPageSize,
            )
            if (stats.isEmpty()) break
            seen += stats.size
            for (stat in stats) {
                val track = tracksById["plex:${stat.ratingKey}"] ?: continue
                val lastPlayedAt = stat.lastViewedAtMs ?: continue
                imported += playHistoryRepository.importPlexPlayCountFallback(
                    track = track,
                    serverId = server.id,
                    lastPlayedAtMs = lastPlayedAt,
                    playCount = stat.viewCount,
                    importedAtMs = importedAtMs,
                )
            }
            if (stats.size < PlaybackStatsPageSize) break
            start += PlaybackStatsPageSize
        }
        PhoebeLog.d("PlexPlayHistorySyncer") {
            "synced Plex track playback stats fallback → seen=$seen imported=$imported"
        }
        return PlexPlayHistorySyncResult.Synced(imported = imported, seen = seen)
    }

    private fun Track.withPlexHistoryFallbacks(entry: PlexPlaybackHistoryEntry): Track =
        copy(
            artist = artist.ifBlank { entry.artist },
            album = album.ifBlank { entry.album },
        )

    private fun PlexPlaybackHistoryEntry.toHistoryTrack(): Track =
        Track(
            id = "plex:$ratingKey",
            title = title.ifBlank { "Unknown track" },
            artist = artist.ifBlank { "Unknown Artist" },
            album = album.ifBlank { "Unknown Album" },
            durationMs = 0L,
            streamUrl = "",
            downloadUrl = "",
        )

    companion object {
        const val PageSize = 100
        const val MaxPages = 25
        const val IncrementalLookbackMs = 10L * 60L * 1000L
        const val MergeWindowMs = 10L * 60L * 1000L
        const val PlaybackStatsPageSize = 500
        const val PlaybackStatsMaxPages = 400
        private const val PlexTrackTypeName = "track"
    }
}

sealed interface PlexPlayHistorySyncResult {
    data object Skipped : PlexPlayHistorySyncResult
    data class Synced(val imported: Int, val seen: Int) : PlexPlayHistorySyncResult
}
