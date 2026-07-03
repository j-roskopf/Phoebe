package com.phoebe.app

import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.PlaybackQueueOrigin
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.belongsToProvider
import com.phoebe.app.domain.hasPlayableSource
import com.phoebe.app.domain.playHistoryIdentityKey

internal data class QueueContinuationPlan(
    val tracks: List<Track>,
    val sourceLabel: String,
)

internal fun planQueueContinuation(
    catalog: CatalogSnapshot,
    origin: PlaybackQueueOrigin?,
    currentQueue: List<Track>,
    nativeCandidates: List<Track> = emptyList(),
    recentTrackIds: Set<String> = emptySet(),
    allowWeakFallback: Boolean = false,
    limitTracks: Int = KeepPlayingTrackLimit,
    limitDurationMs: Long = KeepPlayingDurationLimitMs,
): QueueContinuationPlan? {
    if (currentQueue.isEmpty() || limitTracks <= 0) return null
    val catalogTracks = loadedPlayableTracks(catalog)
    val excluded = ExcludedTracks.from(currentQueue, catalogTracks, recentTrackIds)
    val seeds = seedTracks(catalog, origin, currentQueue)
    val selected = mutableListOf<Track>()
    var selectedDurationMs = 0L

    fun tryAdd(track: Track): Boolean {
        if (selected.size >= limitTracks) return false
        if (!track.hasPlayableSource() || excluded.contains(track)) return false
        if (selected.any { it.playHistoryIdentityKey() == track.playHistoryIdentityKey() || it.id == track.id }) return false
        val durationMs = track.durationMs.coerceAtLeast(0L)
        if (selected.isNotEmpty() && durationMs > 0L && selectedDurationMs + durationMs > limitDurationMs) return false
        selected += track
        selectedDurationMs += durationMs
        excluded.record(track)
        return true
    }

    filteredNativeCandidates(
        origin = origin,
        nativeCandidates = nativeCandidates,
        seeds = seeds,
        excluded = excluded,
    ).forEach(::tryAdd)
    if (selected.size < limitTracks && selectedDurationMs < limitDurationMs) {
        val fallback = scoredFallbackCandidates(
            origin = origin,
            seeds = seeds,
            catalogTracks = catalogTracks,
            excluded = excluded,
        )
        fallback.forEach { scored ->
            if (selected.size < limitTracks) {
                tryAdd(scored.track)
            }
        }
    }
    if (allowWeakFallback && selected.size < limitTracks && selectedDurationMs < limitDurationMs) {
        val weakFallback = weakFallbackCandidates(
            origin = origin,
            seeds = seeds,
            catalogTracks = catalogTracks,
            excluded = excluded,
        )
        weakFallback.forEach { scored ->
            if (selected.size < limitTracks) {
                tryAdd(scored.track)
            }
        }
    }

    if (selected.isEmpty()) return null
    return QueueContinuationPlan(
        tracks = selected,
        sourceLabel = origin.keepPlayingSourceLabel(),
    )
}

private fun filteredNativeCandidates(
    origin: PlaybackQueueOrigin?,
    nativeCandidates: List<Track>,
    seeds: List<Track>,
    excluded: ExcludedTracks,
): List<Track> {
    if (nativeCandidates.isEmpty()) return emptyList()
    if (origin.trustsNativeCandidates()) return nativeCandidates
    if (seeds.isEmpty()) return emptyList()
    return nativeCandidates
        .asSequence()
        .filterNot { excluded.contains(it) }
        .filter { candidate ->
            candidate.relatedScore(seeds).meaningful
        }
        .toList()
}

private fun PlaybackQueueOrigin?.trustsNativeCandidates(): Boolean =
    this is PlaybackQueueOrigin.Artist || this is PlaybackQueueOrigin.Radio

private fun loadedPlayableTracks(catalog: CatalogSnapshot): List<Track> =
    catalog.tracksByParent.values
        .asSequence()
        .flatten()
        .filter { it.hasPlayableSource() }
        .distinctBy { it.playHistoryIdentityKey() }
        .toList()

private fun scoredFallbackCandidates(
    origin: PlaybackQueueOrigin?,
    seeds: List<Track>,
    catalogTracks: List<Track>,
    excluded: ExcludedTracks,
): List<ScoredContinuationTrack> {
    if (seeds.isEmpty()) return emptyList()
    val providerPreferred = origin?.providerType
    val providerPool = catalogTracks.filterByProviderPreference(providerPreferred)
    return providerPool
        .asSequence()
        .filterNot { excluded.contains(it) }
        .mapNotNull { candidate ->
            val score = candidate.relatedScore(seeds)
            if (score.meaningful) ScoredContinuationTrack(candidate, score.value) else null
        }
        .sortedWith(
            compareByDescending<ScoredContinuationTrack> { it.score }
                .thenByDescending { it.track.rating ?: 0f }
                .thenBy { it.track.artist.lowercase() }
                .thenBy { it.track.album.lowercase() }
                .thenBy { it.track.title.lowercase() },
        )
        .toList()
}

private fun weakFallbackCandidates(
    origin: PlaybackQueueOrigin?,
    seeds: List<Track>,
    catalogTracks: List<Track>,
    excluded: ExcludedTracks,
): List<ScoredContinuationTrack> {
    val providerPreferred = origin?.providerType
    val providerPool = catalogTracks.filterByProviderPreference(providerPreferred)
    return providerPool
        .asSequence()
        .filterNot { excluded.contains(it) }
        .map { candidate ->
            ScoredContinuationTrack(candidate, candidate.weakRelatedScore(seeds, origin))
        }
        .sortedWith(
            compareByDescending<ScoredContinuationTrack> { it.score }
                .thenByDescending { it.track.rating ?: 0f }
                .thenBy { it.track.artist.lowercase() }
                .thenBy { it.track.album.lowercase() }
                .thenBy { it.track.title.lowercase() },
        )
        .toList()
}

private fun List<Track>.filterByProviderPreference(providerType: MediaProviderType?): List<Track> {
    if (providerType == null) return this
    val preferred = filter { it.belongsToProvider(providerType) }
    return preferred.takeIf { it.size >= KeepPlayingMinimumPreferredProviderPool } ?: this
}

private fun seedTracks(
    catalog: CatalogSnapshot,
    origin: PlaybackQueueOrigin?,
    currentQueue: List<Track>,
): List<Track> {
    val originSeeds = when (origin) {
        is PlaybackQueueOrigin.Album -> catalog.tracksByParent[origin.id].orEmpty()
        is PlaybackQueueOrigin.Artist -> catalog.tracksForArtistTitle(origin.title)
        is PlaybackQueueOrigin.Playlist -> catalog.tracksByParent[origin.id].orEmpty()
        is PlaybackQueueOrigin.Radio,
        is PlaybackQueueOrigin.Mix,
        is PlaybackQueueOrigin.TrackList,
        null -> emptyList()
    }
    val seedIds = origin?.seedTrackIds.orEmpty().toSet()
    val idSeeds = if (seedIds.isEmpty()) {
        emptyList()
    } else {
        catalog.tracksByParent.values.asSequence().flatten().filter { it.id in seedIds }.toList()
    }
    return (currentQueue.takeLast(KeepPlayingQueueSeedLimit) + originSeeds + idSeeds)
        .filter { it.title.isNotBlank() || it.artist.isNotBlank() }
        .distinctBy { it.playHistoryIdentityKey() }
}

private fun CatalogSnapshot.tracksForArtistTitle(title: String): List<Track> {
    val normalized = title.normalizedRelationToken()
    if (normalized.isBlank()) return emptyList()
    return tracksByParent.values
        .asSequence()
        .flatten()
        .filter { track ->
            track.artist.normalizedRelationToken() == normalized ||
                track.albumArtist?.normalizedRelationToken() == normalized
        }
        .toList()
}

private data class ScoredContinuationTrack(
    val track: Track,
    val score: Int,
)

private data class RelationScore(
    val value: Int,
    val meaningful: Boolean,
)

private fun Track.relatedScore(seeds: List<Track>): RelationScore {
    var best = 0
    var meaningful = false
    for (seed in seeds) {
        val score = relationScoreAgainst(seed)
        if (score.meaningful) meaningful = true
        if (score.value > best) best = score.value
    }
    return RelationScore(best, meaningful)
}

private fun Track.relationScoreAgainst(seed: Track): RelationScore {
    var score = 0
    var meaningful = false
    val artistToken = artist.normalizedRelationToken()
    val seedArtistToken = seed.artist.normalizedRelationToken()
    val sameArtist = artistToken.isNotBlank() && artistToken == seedArtistToken
    if (sameArtist) {
        score += 8
        meaningful = true
    }
    val albumArtistToken = albumArtist?.normalizedRelationToken().orEmpty()
    val seedAlbumArtistToken = seed.albumArtist?.normalizedRelationToken().orEmpty()
    val sameAlbumArtist = albumArtistToken.isNotBlank() && albumArtistToken == seedAlbumArtistToken
    if (sameAlbumArtist) {
        score += 5
        meaningful = true
    }
    val albumToken = album.normalizedRelationToken()
    if (albumToken.isNotBlank() &&
        albumToken == seed.album.normalizedRelationToken() &&
        (albumToken.isDistinctiveAlbumToken() || sameArtist || sameAlbumArtist)
    ) {
        score += 3
        meaningful = true
    }
    score += sharedTagScore(genre, seed.genre).also { if (it > 0) meaningful = true }
    score += sharedTagScore(mood, seed.mood).also { if (it > 0) meaningful = true }
    score += sharedTagScore(style, seed.style).also { if (it > 0) meaningful = true }
    val decade = year?.let { (it / 10) * 10 }
    if (decade != null && decade == seed.year?.let { (it / 10) * 10 }) {
        score += 1
    }
    return RelationScore(score, meaningful)
}

private fun Track.weakRelatedScore(seeds: List<Track>, origin: PlaybackQueueOrigin?): Int {
    val seedScore = seeds.maxOfOrNull { seed -> weakRelationScoreAgainst(seed) } ?: 0
    return seedScore + weakOriginTitleScore(origin)
}

private fun Track.weakRelationScoreAgainst(seed: Track): Int =
    relationScoreAgainst(seed).value +
        sharedLooseTagScore(genre, seed.genre) +
        sharedLooseTagScore(mood, seed.mood) +
        sharedLooseTagScore(style, seed.style)

private fun Track.weakOriginTitleScore(origin: PlaybackQueueOrigin?): Int {
    val tokens = origin?.title?.originSearchTokens()
    if (tokens.isNullOrEmpty()) return 0
    val searchable = listOf(title, artist, album, genre, mood, style)
        .joinToString(" ")
        .lowercase()
    return if (tokens.any { token -> searchable.contains(token) }) 4 else 0
}

private fun sharedTagScore(left: String?, right: String?): Int {
    val shared = left.tagTokens().intersect(right.tagTokens())
    return shared.size * 3
}

private fun sharedLooseTagScore(left: String?, right: String?): Int {
    val shared = left.looseTagTokens().intersect(right.looseTagTokens())
    return shared.size
}

private fun String?.tagTokens(): Set<String> =
    orEmpty()
        .split(',', ';', '/', '|')
        .map { it.normalizedRelationToken() }
        .filter { it.isNotBlank() && it !in BroadRelationTags }
        .toSet()

private fun String?.looseTagTokens(): Set<String> =
    orEmpty()
        .split(',', ';', '/', '|')
        .map { it.normalizedRelationToken() }
        .filter { it.isNotBlank() }
        .toSet()

private fun String.originSearchTokens(): Set<String> =
    lowercase()
        .replace(NonAlphanumericRelationRegex, " ")
        .replace(RelationWhitespaceRegex, " ")
        .split(' ')
        .map { it.trim() }
        .filter { it.length >= 3 && it !in GenericOriginTitleWords }
        .toSet()

private fun String.normalizedRelationToken(): String =
    trim()
        .lowercase()
        .replace(RelationWhitespaceRegex, " ")
        .removePrefix("the ")

private fun String.isDistinctiveAlbumToken(): Boolean {
    val simplified = replace(NonAlphanumericRelationRegex, " ")
        .replace(RelationWhitespaceRegex, " ")
        .trim()
    if (simplified.isBlank() || simplified in GenericAlbumTitles) return false
    val meaningfulWords = simplified
        .split(' ')
        .filter { it.length > 2 && it !in GenericAlbumTitleWords }
    return meaningfulWords.size >= 2
}

private class ExcludedTracks private constructor(
    private val ids: MutableSet<String>,
    private val identityKeys: MutableSet<String>,
) {
    fun contains(track: Track): Boolean =
        track.id in ids || track.playHistoryIdentityKey() in identityKeys

    fun record(track: Track) {
        ids += track.id
        identityKeys += track.playHistoryIdentityKey()
    }

    companion object {
        fun from(currentQueue: List<Track>, catalogTracks: List<Track>, recentTrackIds: Set<String>): ExcludedTracks {
            val ids = currentQueue.mapTo(mutableSetOf()) { it.id }
            val remainingRecentTrackIds = recentTrackIds.toMutableSet()
            remainingRecentTrackIds.removeAll(ids)
            ids += recentTrackIds
            val identities = currentQueue.mapTo(mutableSetOf()) { it.playHistoryIdentityKey() }
            if (remainingRecentTrackIds.isNotEmpty()) {
                for (track in catalogTracks) {
                    if (track.id in remainingRecentTrackIds) {
                        identities += track.playHistoryIdentityKey()
                        remainingRecentTrackIds.remove(track.id)
                        if (remainingRecentTrackIds.isEmpty()) break
                    }
                }
            }
            return ExcludedTracks(ids, identities)
        }
    }
}

private fun PlaybackQueueOrigin?.keepPlayingSourceLabel(): String =
    when (this) {
        is PlaybackQueueOrigin.Album -> title
        is PlaybackQueueOrigin.Artist -> title
        is PlaybackQueueOrigin.Playlist -> title
        is PlaybackQueueOrigin.Radio -> title
        is PlaybackQueueOrigin.Mix -> title
        is PlaybackQueueOrigin.TrackList,
        null -> "Keep Playing"
    }

private val RelationWhitespaceRegex = Regex("""\s+""")
private val NonAlphanumericRelationRegex = Regex("""[^\p{L}\p{N}]+""")
private val BroadRelationTags = setOf(
    "alternative",
    "dance",
    "electronic",
    "electronica",
    "indie",
    "pop",
    "r&b",
    "rock",
    "soul",
)
private val GenericAlbumTitles = setOf(
    "best of",
    "collection",
    "essential",
    "forever",
    "gold",
    "greatest hits",
    "hits",
    "single",
    "singles",
    "the best of",
    "the collection",
    "the essential",
    "the greatest hits",
    "the singles",
    "ultimate collection",
)
private val GenericAlbumTitleWords = setOf(
    "album",
    "best",
    "collection",
    "deluxe",
    "edition",
    "essential",
    "greatest",
    "hits",
    "remaster",
    "remastered",
    "single",
    "singles",
    "special",
    "ultimate",
    "version",
)
private val GenericOriginTitleWords = setOf(
    "mix",
    "music",
    "playlist",
    "queue",
    "radio",
    "songs",
    "tracks",
)
private const val KeepPlayingTrackLimit = 25
private const val KeepPlayingDurationLimitMs = 90L * 60L * 1000L
private const val KeepPlayingQueueSeedLimit = 25
private const val KeepPlayingMinimumPreferredProviderPool = 10
