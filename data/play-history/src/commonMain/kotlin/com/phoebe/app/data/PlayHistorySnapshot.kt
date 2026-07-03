package com.phoebe.app.data

import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.MostPlayedEntry
import com.phoebe.app.domain.PlayHistoryKind
import com.phoebe.app.domain.RecentlyPlayedEntry
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.playHistoryIdentityKey
import com.phoebe.app.domain.catalogPrefix

data class PlayHistorySnapshot(
    val byArtist: Map<String, Long> = emptyMap(),
    val byAlbum: Map<String, Long> = emptyMap(),
    val byTrack: Map<String, Long> = emptyMap(),
    val playCountByTrack: Map<String, Long> = emptyMap(),
    val playEventsByTrack: Map<String, List<Long>> = emptyMap(),
    val topMostPlayed: List<MostPlayedEntry> = emptyList(),
    val topRecentlyPlayed: List<RecentlyPlayedEntry> = emptyList(),
)

data class PlayHistoryRankedEntries(
    val kind: PlayHistoryKind,
    val totalCount: Int,
    val mostPlayed: List<MostPlayedEntry> = emptyList(),
    val recentlyPlayed: List<RecentlyPlayedEntry> = emptyList(),
) {
    val entryCount: Int
        get() = when (kind) {
            PlayHistoryKind.MostPlayed -> mostPlayed.size
            PlayHistoryKind.RecentlyPlayed -> recentlyPlayed.size
        }
}

data class HomePlayedTrack(
    val track: Track,
    val lastPlayedMs: Long? = null,
    val playCount: Long = 0L,
)

private data class MostPlayedScore(
    val playCount: Long,
    val lastPlayedMs: Long,
) : Comparable<MostPlayedScore> {
    override fun compareTo(other: MostPlayedScore): Int =
        playCount.compareTo(other.playCount).takeIf { it != 0 }
            ?: lastPlayedMs.compareTo(other.lastPlayedMs)
}

fun CatalogSnapshot.trackIndexKey(): Long {
    var hash = tracksByParent.size.toLong()
    tracksByParent.forEach { (parentId, tracks) ->
        hash = hash * 31L + parentId.hashCode()
        hash = hash * 31L + tracks.size
    }
    return hash
}

fun PlayHistorySnapshot.derivationKey(): Long {
    var hash = 17L
    hash = hash * 31 + byTrack.size
    hash = hash * 31 + byAlbum.size
    hash = hash * 31 + byArtist.size
    hash = hash * 31 + playCountByTrack.size
    hash = hash * 31 + playEventsByTrack.size
    hash = hash * 31 + topMostPlayed.size
    hash = hash * 31 + topRecentlyPlayed.size
    topMostPlayed.take(5).forEach { entry ->
        hash = hash * 31 + entry.trackId.hashCode()
        hash = hash * 31 + entry.playCount
        hash = hash * 31 + entry.lastPlayedMs
    }
    topRecentlyPlayed.take(5).forEach { entry ->
        hash = hash * 31 + entry.trackId.hashCode()
        hash = hash * 31 + entry.lastPlayedMs
    }
    return hash
}

fun PlayHistorySnapshot.mostPlayedPendingResolution(
    resolvedCount: Int,
    limit: Int,
): Boolean =
    topMostPlayed
        .take(limit.coerceAtLeast(0))
        .drop(resolvedCount.coerceAtLeast(0))
        .any { it.artist.isNotBlank() || it.album.isNotBlank() }

fun PlayHistorySnapshot.rankedEntries(
    kind: PlayHistoryKind,
    limit: Int = PlayHistoryTopListCapacity,
): PlayHistoryRankedEntries {
    val boundedLimit = limit.coerceAtLeast(0)
    return when (kind) {
        PlayHistoryKind.MostPlayed -> PlayHistoryRankedEntries(
            kind = kind,
            totalCount = topMostPlayed.size,
            mostPlayed = topMostPlayed.take(boundedLimit),
        )
        PlayHistoryKind.RecentlyPlayed -> PlayHistoryRankedEntries(
            kind = kind,
            totalCount = topRecentlyPlayed.size,
            recentlyPlayed = topRecentlyPlayed.take(boundedLimit),
        )
    }
}

fun PlayHistorySnapshot.withRankedEntries(entries: PlayHistoryRankedEntries): PlayHistorySnapshot =
    when (entries.kind) {
        PlayHistoryKind.MostPlayed -> copy(topMostPlayed = entries.mostPlayed)
        PlayHistoryKind.RecentlyPlayed -> copy(topRecentlyPlayed = entries.recentlyPlayed)
    }

fun playHistoryRows(
    kind: PlayHistoryKind,
    catalog: CatalogSnapshot,
    playHistory: PlayHistorySnapshot,
    resolvedTracksById: Map<String, Track> = emptyMap(),
    trackIndex: Map<String, Track>? = null,
    queryLimit: Int = PlayHistoryTopListCapacity,
): List<HomePlayedTrack> =
    when (kind) {
        PlayHistoryKind.RecentlyPlayed -> recentlyPlayedTracks(
            playHistory = playHistory,
            catalog = catalog,
            limit = queryLimit,
            resolvedTracksById = resolvedTracksById,
            trackIndex = trackIndex,
        )
        PlayHistoryKind.MostPlayed -> mostPlayedTracks(
            playHistory = playHistory,
            catalog = catalog,
            limit = queryLimit,
            resolvedTracksById = resolvedTracksById,
            trackIndex = trackIndex,
        )
    }

private fun recentlyPlayedTracks(
    playHistory: PlayHistorySnapshot,
    catalog: CatalogSnapshot,
    limit: Int,
    resolvedTracksById: Map<String, Track> = emptyMap(),
    trackIndex: Map<String, Track>? = null,
): List<HomePlayedTrack> {
    if (limit <= 0) return emptyList()
    val ranked = playHistory.topRecentlyPlayed.ifEmpty {
        playHistory.byTrack.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .map { (trackId, playedAt) ->
                RecentlyPlayedEntry(trackId, playedAt, "", "")
            }
    }
    return collectResolvedPlayedRows(
        ranked = ranked,
        limit = limit,
        catalog = catalog,
        resolvedTracksById = resolvedTracksById,
        trackIndex = trackIndex,
        trackId = RecentlyPlayedEntry::trackId,
        dedupeByIdentity = true,
    ) { entry, track ->
        HomePlayedTrack(
            track = track,
            lastPlayedMs = entry.lastPlayedMs,
            playCount = playHistory.playCountByTrack[entry.trackId] ?: 0L,
        )
    }
}

private fun mostPlayedTracks(
    playHistory: PlayHistorySnapshot,
    catalog: CatalogSnapshot,
    limit: Int,
    resolvedTracksById: Map<String, Track> = emptyMap(),
    trackIndex: Map<String, Track>? = null,
): List<HomePlayedTrack> {
    if (limit <= 0) return emptyList()
    val ranked = playHistory.topMostPlayed.ifEmpty {
        playHistory.playCountByTrack.entries
            .asSequence()
            .filter { it.value > 0L }
            .sortedWith(
                compareByDescending<Map.Entry<String, Long>> { entry ->
                    MostPlayedScore(
                        playCount = entry.value,
                        lastPlayedMs = playHistory.byTrack[entry.key] ?: 0L,
                    )
                }.thenBy { it.key },
            )
            .map { (trackId, count) ->
                MostPlayedEntry(
                    trackId = trackId,
                    playCount = count,
                    lastPlayedMs = playHistory.byTrack[trackId] ?: 0L,
                    artist = "",
                    album = "",
                )
            }
            .toList()
    }
    return collectResolvedPlayedRows(
        ranked = ranked,
        limit = limit,
        catalog = catalog,
        resolvedTracksById = resolvedTracksById,
        trackIndex = trackIndex,
        trackId = MostPlayedEntry::trackId,
        dedupeByIdentity = true,
    ) { entry, track ->
        HomePlayedTrack(
            track = track,
            lastPlayedMs = entry.lastPlayedMs.takeIf { it > 0L },
            playCount = entry.playCount,
        )
    }
}

private fun <T> collectResolvedPlayedRows(
    ranked: List<T>,
    limit: Int,
    catalog: CatalogSnapshot,
    resolvedTracksById: Map<String, Track>,
    trackIndex: Map<String, Track>?,
    trackId: (T) -> String,
    dedupeByIdentity: Boolean = false,
    buildRow: (T, Track) -> HomePlayedTrack,
): List<HomePlayedTrack> {
    if (ranked.isEmpty() || limit <= 0) return emptyList()
    if (!dedupeByIdentity) {
        val candidates = ranked.take(limit)
        val resolved = lookupTracksByIds(catalog, candidates.map(trackId).toSet(), resolvedTracksById, trackIndex)
        return candidates.mapNotNull { entry ->
            val id = trackId(entry)
            val track = resolved[id] ?: placeholderTrackForPlayHistoryEntry(entry, id) ?: return@mapNotNull null
            buildRow(entry, track)
        }
    }
    val seenIdentityKeys = LinkedHashSet<String>()
    val rows = ArrayList<HomePlayedTrack>(limit.coerceAtMost(ranked.size))
    val chunkSize = (limit * 2).coerceAtLeast(10)
    var offset = 0
    while (rows.size < limit && offset < ranked.size) {
        val chunk = ranked.subList(offset, (offset + chunkSize).coerceAtMost(ranked.size))
        offset += chunkSize
        val resolved = lookupTracksByIds(catalog, chunk.map(trackId).toSet(), resolvedTracksById, trackIndex)
        for (entry in chunk) {
            if (rows.size >= limit) break
            val id = trackId(entry)
            val track = resolved[id] ?: placeholderTrackForPlayHistoryEntry(entry, id) ?: continue
            val identityKey = track.playHistoryIdentityKey()
            if (!seenIdentityKeys.add(identityKey)) continue
            rows += buildRow(entry, track)
        }
    }
    return rows
}

fun lookupTracksByIds(
    catalog: CatalogSnapshot,
    trackIds: Set<String>,
    prefetched: Map<String, Track> = emptyMap(),
    trackIndex: Map<String, Track>? = null,
): Map<String, Track> {
    if (trackIds.isEmpty()) return emptyMap()
    val resolved = LinkedHashMap<String, Track>(trackIds.size)
    val remaining = trackIds.toMutableSet()
    for (id in trackIds) {
        prefetched[id]?.let {
            resolved[id] = it
            remaining.remove(id)
        }
    }
    if (remaining.isEmpty()) return resolved

    for (id in remaining.toList()) {
        val lookupIds = playHistoryLookupIds(id)
        val track = if (trackIndex != null) {
            lookupIds.firstNotNullOfOrNull { lookupId -> trackIndex[lookupId] }
        } else {
            catalog.findTrackByIds(lookupIds)
        }
        track?.let {
            resolved[id] = track
            remaining.remove(id)
        }
    }
    return resolved
}

private fun playHistoryLookupIds(id: String): Set<String> {
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

private fun <T> placeholderTrackForPlayHistoryEntry(entry: T, trackId: String): Track? {
    if (trackId.startsWith("local_")) return null
    val (artist, album) = when (entry) {
        is RecentlyPlayedEntry -> entry.artist to entry.album
        is MostPlayedEntry -> entry.artist to entry.album
        else -> return null
    }
    if (artist.isBlank() && album.isBlank()) return null
    val title = album.ifBlank { artist }.ifBlank { return null }
    return Track(
        id = trackId,
        title = title,
        artist = artist.ifBlank { "Unknown Artist" },
        album = album.ifBlank { "Unknown Album" },
        durationMs = 0L,
        streamUrl = "",
        downloadUrl = "",
    )
}
