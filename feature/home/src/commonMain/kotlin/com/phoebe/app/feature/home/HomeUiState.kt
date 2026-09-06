package com.phoebe.app.feature.home

import androidx.compose.runtime.Immutable
import com.phoebe.app.data.HomePlayedTrack
import com.phoebe.app.data.PlayHistorySnapshot
import com.phoebe.app.data.catalogAlbumsForArtist
import com.phoebe.app.data.catalogTracksForAlbum
import com.phoebe.app.data.catalogTracksForArtist
import com.phoebe.app.data.lookupTracksByIds
import com.phoebe.app.data.playHistoryRows
import com.phoebe.app.data.trackIndexKey
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.PersonalMixPreferences
import com.phoebe.app.domain.PlayHistoryKind
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.playHistoryIdentityKey
import kotlin.random.Random

internal const val HeavyRotationWindowMs = 14L * 24L * 60L * 60L * 1000L
private const val HeavyRotationMinimumRecentPlays = 2L

@Immutable
data class HomeUiState(
    val recentlyAddedTracks: List<Track> = emptyList(),
    val recentlyAddedArtists: List<Artist> = emptyList(),
    val recentlyAddedAlbums: List<Album> = emptyList(),
    val heavyRotationTracks: List<HomePlayedTrack> = emptyList(),
    val recentlyPlayedTracks: List<HomePlayedTrack> = emptyList(),
    val mostPlayedTracks: List<HomePlayedTrack> = emptyList(),
    val favoriteArtists: List<Artist> = emptyList(),
    val favoriteAlbums: List<Album> = emptyList(),
    val favoritePlaylists: List<Playlist> = emptyList(),
    val favoriteArtistCount: Int = 0,
    val favoriteAlbumCount: Int = 0,
    val favoritePlaylistCount: Int = 0,
    val randomArtists: List<Artist> = emptyList(),
    val randomAlbums: List<Album> = emptyList(),
    val artistThumbs: Map<String, String> = emptyMap(),
    val albumArtworkFallbacks: Map<String, String> = emptyMap(),
    val randomArtistStats: HomeFeaturedArtistStats? = null,
    val randomAlbumStats: HomeFeaturedAlbumStats? = null,
)

@Immutable
data class HomeFeaturedArtistStats(
    val artistId: String,
    val artworkUrl: String?,
    val albumCount: Int,
    val trackCount: Int,
    val totalDurationMs: Long,
    val genre: String?,
    val lastPlayedMs: Long?,
    val hasAlbums: Boolean,
    val hasTracks: Boolean,
    val hasPendingTrackStats: Boolean,
)

@Immutable
data class HomeFeaturedAlbumStats(
    val albumId: String,
    val trackCount: Int,
    val totalDurationMs: Long,
    val genre: String?,
    val tracksLoaded: Boolean,
)

data class HomeTrackIndex(
    val tracksById: Map<String, Track>,
    val recentlyAddedTracks: List<Track>,
)

/** Cheap revision for Compose keys — O(1) counts only, safe to call on the main thread during sync. */
fun CatalogSnapshot.homeMetadataRevisionKey(): Long {
    var hash = artists.size.toLong()
    hash = hash * 31L + albums.size
    hash = hash * 31L + playlists.size
    hash = hash * 31L + tracksByParent.size
    return hash
}

/** Cheap track-batch revision without hashing every parent id during sync. */
fun CatalogSnapshot.trackBatchRevisionKey(): Long {
    var hash = tracksByParent.size.toLong()
    var trackCount = 0
    tracksByParent.forEach { (_, tracks) ->
        trackCount += tracks.size
    }
    return hash * 1_000_000L + trackCount
}

fun CatalogSnapshot.homeMetadataKey(): Long {
    var hash = 17L
    hash = hash * 31 + artists.size
    hash = hash * 31 + albums.size
    hash = hash * 31 + playlists.size
    artists.forEach { artist ->
        hash = hash * 31 + artist.id.hashCode()
        hash = hash * 31 + artist.favorite.hashCode()
        hash = hash * 31 + (artist.dateAddedMs ?: 0L).hashCode()
    }
    albums.forEach { album ->
        hash = hash * 31 + album.id.hashCode()
        hash = hash * 31 + album.favorite.hashCode()
        hash = hash * 31 + (album.dateAddedMs ?: 0L).hashCode()
    }
    playlists.forEach { playlist ->
        hash = hash * 31 + playlist.id.hashCode()
        hash = hash * 31 + playlist.favorite.hashCode()
        hash = hash * 31 + playlist.trackCount
    }
    return hash
}

class HomeCatalogIndexCache {
    private var tracksById: LinkedHashMap<String, Track> = linkedMapOf()
    private var recentlyAdded: MutableList<Pair<Track, Long>> = mutableListOf()
    private var parentTrackCounts: Map<String, Int> = emptyMap()
    private var trackIndexKey: Long = 0L

    fun trackIndex(
        catalog: CatalogSnapshot,
        albumAddedByTitle: Map<String, Long>,
        limit: Int,
    ): HomeTrackIndex {
        val key = catalog.trackIndexKey()
        if (key == trackIndexKey && tracksById.isNotEmpty()) {
            return HomeTrackIndex(tracksById, recentlyAdded.map { it.first })
        }
        val newCounts = catalog.tracksByParent.mapValues { it.value.size }
        val canMergeIncrementally =
            trackIndexKey != 0L &&
                parentTrackCounts.isNotEmpty() &&
                newCounts.keys.containsAll(parentTrackCounts.keys) &&
                newCounts.any { (parentId, count) -> (parentTrackCounts[parentId] ?: 0) < count }
        if (canMergeIncrementally) {
            val changedParents = newCounts.keys.filter { parentId ->
                (parentTrackCounts[parentId] ?: 0) != newCounts[parentId]
            }.toSet()
            mergeParents(catalog, albumAddedByTitle, limit, changedParents)
        } else {
            rebuild(catalog, albumAddedByTitle, limit)
        }
        parentTrackCounts = newCounts
        trackIndexKey = key
        return HomeTrackIndex(tracksById, recentlyAdded.map { it.first })
    }

    private fun rebuild(
        catalog: CatalogSnapshot,
        albumAddedByTitle: Map<String, Long>,
        limit: Int,
    ) {
        tracksById = linkedMapOf()
        recentlyAdded = mutableListOf()
        mergeParents(catalog, albumAddedByTitle, limit, catalog.tracksByParent.keys.toSet())
    }

    private fun mergeParents(
        catalog: CatalogSnapshot,
        albumAddedByTitle: Map<String, Long>,
        limit: Int,
        parentIds: Set<String>,
    ) {
        parentIds.forEach { parentId ->
            catalog.tracksByParent[parentId]?.forEach { track ->
                ingestTrack(track, albumAddedByTitle, limit)
            }
        }
    }

    private fun ingestTrack(
        track: Track,
        albumAddedByTitle: Map<String, Long>,
        limit: Int,
    ) {
        if (track.id in tracksById) return
        tracksById[track.id] = track
        val addedAt = effectiveTrackDateAdded(track, albumAddedByTitle)
        insertBounded(recentlyAdded, track, addedAt, limit, descending = true)
    }
}

/** Resolves track ids from [prefetched] then [catalog] (and optional [trackIndex] map). */
fun deriveHomeUiState(
    catalog: CatalogSnapshot,
    playHistory: PlayHistorySnapshot,
    randomArtistSeed: Int,
    randomAlbumSeed: Int,
    nowMs: Long,
    limit: Int = 10,
    trackIndexCache: HomeCatalogIndexCache? = null,
    includeTrackDerivedSections: Boolean = true,
    resolvedTracksById: Map<String, Track> = emptyMap(),
): HomeUiState {
    val albumAddedByTitle = albumAddedByTitle(catalog)
    val artistAddedByTitle = artistAddedByTitle(catalog)
    val trackIndex = if (includeTrackDerivedSections) {
        trackIndexCache?.trackIndex(catalog, albumAddedByTitle, limit)
            ?: homeTrackIndex(catalog, albumAddedByTitle, limit)
    } else {
        HomeTrackIndex(emptyMap(), emptyList())
    }
    val recentArtists = topBy(
        catalog.artists.asSequence(),
        limit = limit,
        descending = true,
    ) { artist ->
        recentlyAddedAt(artist, artistAddedByTitle)
    }
    val recentAlbums = topBy(
        catalog.albums.asSequence(),
        limit = limit,
        descending = true,
    ) { album ->
        album.dateAddedMs ?: 0L
    }
    val catalogTracksById = trackIndex.tracksById.takeIf { includeTrackDerivedSections }
    val recentlyPlayed = playHistoryRows(
        kind = PlayHistoryKind.RecentlyPlayed,
        playHistory = playHistory,
        catalog = catalog,
        queryLimit = limit,
        resolvedTracksById = resolvedTracksById,
        trackIndex = catalogTracksById,
    )
    val mostPlayed = playHistoryRows(
        kind = PlayHistoryKind.MostPlayed,
        playHistory = playHistory,
        catalog = catalog,
        queryLimit = limit,
        resolvedTracksById = resolvedTracksById,
        trackIndex = catalogTracksById,
    )
    val heavyRotation = heavyRotationTracks(
        playHistory = playHistory,
        catalog = catalog,
        nowMs = nowMs,
        limit = limit,
    )
    val favoriteArtists = topBy(catalog.artists.asSequence().filter { it.favorite }, limit = limit) { it.title.lowercase() }
    val favoriteAlbums = topBy(catalog.albums.asSequence().filter { it.favorite }, limit = limit) { it.title.lowercase() }
    val favoritePlaylists = topBy(
        catalog.playlists.asSequence().filter { it.favorite }.distinctBy { it.id },
        limit = limit,
    ) { it.title.lowercase() }
    val randomArtists = deterministicSample(catalog.artists, randomArtistSeed, limit)
    val randomAlbums = deterministicSample(catalog.albums, randomAlbumSeed, limit)
    val randomArtistStats = randomArtists.firstOrNull()?.let { artist ->
        if (includeTrackDerivedSections) {
            homeFeaturedArtistStats(artist, catalog, playHistory)
        } else {
            homeFeaturedArtistStatsFromMetadata(artist, catalog, playHistory)
        }
    }
    val randomAlbumStats = randomAlbums.firstOrNull()?.let { album ->
        homeFeaturedAlbumStats(album, catalog)
    }

    return HomeUiState(
        recentlyAddedTracks = trackIndex.recentlyAddedTracks,
        recentlyAddedArtists = recentArtists,
        recentlyAddedAlbums = recentAlbums,
        heavyRotationTracks = heavyRotation,
        recentlyPlayedTracks = recentlyPlayed,
        mostPlayedTracks = mostPlayed,
        favoriteArtists = favoriteArtists,
        favoriteAlbums = favoriteAlbums,
        favoritePlaylists = favoritePlaylists,
        favoriteArtistCount = catalog.artists.count { it.favorite },
        favoriteAlbumCount = catalog.albums.count { it.favorite },
        favoritePlaylistCount = catalog.playlists.count { it.favorite },
        randomArtists = randomArtists,
        randomAlbums = randomAlbums,
        artistThumbs = artistThumbsForHome(
            artists = (recentArtists + favoriteArtists + randomArtists).distinctBy { it.id },
            albums = catalog.albums,
        ),
        albumArtworkFallbacks = albumArtworkFallbacksForHome(
            albums = (recentAlbums + favoriteAlbums + randomAlbums).distinctBy { it.id },
            catalog = catalog,
        ),
        randomArtistStats = randomArtistStats,
        randomAlbumStats = randomAlbumStats,
    )
}

private fun homeFeaturedArtistStats(
    artist: Artist,
    catalog: CatalogSnapshot,
    playHistory: PlayHistorySnapshot,
): HomeFeaturedArtistStats {
    val albums = catalogAlbumsForArtist(catalog, artist.title)
    val tracks = catalogTracksForArtist(catalog, artist.title)
    return HomeFeaturedArtistStats(
        artistId = artist.id,
        artworkUrl = artist.thumbUrl ?: albums.firstNotNullOfOrNull { it.thumbUrl },
        albumCount = albums.size.coerceAtLeast(artist.albumCount),
        trackCount = tracks.size.coerceAtLeast(artist.songCount),
        totalDurationMs = tracks.sumOf { it.durationMs },
        genre = mostFrequentGenre(tracks),
        lastPlayedMs = resolveArtistLastPlayed(artist.title, tracks, playHistory),
        hasAlbums = albums.isNotEmpty(),
        hasTracks = artist.songCount > 0 || tracks.isNotEmpty(),
        hasPendingTrackStats = albums.any { !catalog.tracksByParent.containsKey(it.id) },
    )
}

/** Avoids scanning every track in the catalog — used while the track index is still loading. */
private fun homeFeaturedArtistStatsFromMetadata(
    artist: Artist,
    catalog: CatalogSnapshot,
    playHistory: PlayHistorySnapshot,
): HomeFeaturedArtistStats {
    val albums = catalogAlbumsForArtist(catalog, artist.title)
    val loadedTracks = albums.asSequence()
        .flatMap { album -> catalog.tracksByParent[album.id].orEmpty().asSequence() }
        .distinctBy { it.id }
        .toList()
    return HomeFeaturedArtistStats(
        artistId = artist.id,
        artworkUrl = artist.thumbUrl ?: albums.firstNotNullOfOrNull { it.thumbUrl },
        albumCount = albums.size.coerceAtLeast(artist.albumCount),
        trackCount = artist.songCount.coerceAtLeast(loadedTracks.size),
        totalDurationMs = loadedTracks.sumOf { it.durationMs },
        genre = mostFrequentGenre(loadedTracks),
        lastPlayedMs = resolveArtistLastPlayed(artist.title, loadedTracks, playHistory)
            ?: playHistory.byArtist[artist.title],
        hasAlbums = albums.isNotEmpty(),
        hasTracks = artist.songCount > 0 || loadedTracks.isNotEmpty(),
        hasPendingTrackStats = albums.any { !catalog.tracksByParent.containsKey(it.id) },
    )
}

private fun homeFeaturedAlbumStats(
    album: Album,
    catalog: CatalogSnapshot,
): HomeFeaturedAlbumStats {
    val tracks = catalogTracksForAlbum(catalog, album.id)
    return HomeFeaturedAlbumStats(
        albumId = album.id,
        trackCount = tracks.size,
        totalDurationMs = tracks.sumOf { it.durationMs },
        genre = mostFrequentGenre(tracks),
        tracksLoaded = catalog.tracksByParent.containsKey(album.id),
    )
}

private fun resolveArtistLastPlayed(
    artistTitle: String,
    tracks: List<Track>,
    history: PlayHistorySnapshot,
): Long? {
    val perTrack = tracks.asSequence()
        .mapNotNull { history.byTrack[it.id] }
        .maxOrNull()
    val perArtist = history.byArtist[artistTitle]
    return when {
        perTrack == null -> perArtist
        perArtist == null -> perTrack
        else -> maxOf(perTrack, perArtist)
    }
}

private fun mostFrequentGenre(tracks: List<Track>): String? {
    val tally = LinkedHashMap<String, Int>()
    tracks.asSequence()
        .mapNotNull { it.genre }
        .filter { it.isNotBlank() }
        .forEach { genre ->
            tally[genre] = (tally[genre] ?: 0) + 1
        }
    return tally.maxByOrNull { it.value }?.key
}

private fun homeTrackIndex(
    catalog: CatalogSnapshot,
    albumAddedByTitle: Map<String, Long>,
    limit: Int,
): HomeTrackIndex {
    val tracksById = linkedMapOf<String, Track>()
    val recentlyAdded = mutableListOf<Pair<Track, Long>>()
    catalog.tracksByParent.values.forEach { parentTracks ->
        parentTracks.forEach { track ->
            if (track.id !in tracksById) {
                tracksById[track.id] = track
                val addedAt = effectiveTrackDateAdded(track, albumAddedByTitle)
                insertBounded(recentlyAdded, track, addedAt, limit, descending = true)
            }
        }
    }
    return HomeTrackIndex(
        tracksById = tracksById,
        recentlyAddedTracks = recentlyAdded.map { it.first },
    )
}

private fun <T, S : Comparable<S>> topBy(
    items: Sequence<T>,
    limit: Int,
    descending: Boolean = false,
    selector: (T) -> S,
): List<T> {
    if (limit <= 0) return emptyList()
    val top = mutableListOf<Pair<T, S>>()
    items.forEach { item ->
        insertBounded(top, item, selector(item), limit, descending)
    }
    return top.map { it.first }
}

private fun <T, S : Comparable<S>> insertBounded(
    top: MutableList<Pair<T, S>>,
    item: T,
    score: S,
    limit: Int,
    descending: Boolean,
) {
    if (limit <= 0) return
    if (top.size == limit) {
        val boundary = top.last().second
        val belongs = if (descending) score > boundary else score < boundary
        if (!belongs) return
    }
    val insertAt = top.indexOfFirst { (_, existing) ->
        if (descending) score > existing else score < existing
    }.takeIf { it >= 0 } ?: top.size
    top.add(insertAt, item to score)
    if (top.size > limit) top.removeAt(top.lastIndex)
}

private fun <T> deterministicSample(items: List<T>, seed: Int, limit: Int): List<T> {
    if (limit <= 0 || items.isEmpty()) return emptyList()
    if (items.size <= limit) return items
    val random = Random(seed)
    val sample = ArrayList<T>(limit)
    items.forEachIndexed { index, item ->
        if (index < limit) {
            sample += item
        } else {
            val replacementIndex = random.nextInt(index + 1)
            if (replacementIndex < limit) {
                sample[replacementIndex] = item
            }
        }
    }
    return sample
}

private fun artistThumbsForHome(artists: List<Artist>, albums: List<Album>): Map<String, String> {
    if (artists.isEmpty()) return emptyMap()
    val artistNames = artists.map { it.title.lowercase() }.toSet()
    val albumThumbByArtist = buildMap {
        albums.asSequence()
            .filter { album -> album.thumbUrl != null && album.artist.lowercase() in artistNames }
            .forEach { album ->
                val artistName = album.artist.lowercase()
                if (artistName !in this) put(artistName, album.thumbUrl!!)
            }
    }
    return buildMap {
        artists.forEach { artist ->
            val thumb = artist.thumbUrl ?: albumThumbByArtist[artist.title.lowercase()]
            if (thumb != null) put(artist.id, thumb)
        }
    }
}

private fun albumArtworkFallbacksForHome(albums: List<Album>, catalog: CatalogSnapshot): Map<String, String> {
    if (albums.isEmpty()) return emptyMap()
    return buildMap {
        albums.forEach { album ->
            val fallback = catalog.tracksByParent[album.id]
                ?.firstNotNullOfOrNull { track ->
                    track.localArtworkUri?.takeIf { it.isNotBlank() }
                        ?: track.thumbUrl?.takeIf { it.isNotBlank() }
                }
            if (fallback != null) put(album.id, fallback)
        }
    }
}

private fun heavyRotationTracks(
    playHistory: PlayHistorySnapshot,
    catalog: CatalogSnapshot,
    nowMs: Long,
    limit: Int,
): List<HomePlayedTrack> {
    val cutoffMs = nowMs - HeavyRotationWindowMs
    val recentPlayCounts = if (playHistory.playEventsByTrack.isNotEmpty()) {
        playHistory.playEventsByTrack.mapValues { (_, playedAt) ->
            playedAt.count { it >= cutoffMs }.toLong()
        }
    } else {
        playHistory.byTrack.mapValues { (trackId, lastPlayedAt) ->
            if (lastPlayedAt >= cutoffMs) playHistory.playCountByTrack[trackId] ?: 1L else 0L
        }
    }
    val ranked = recentPlayCounts.entries
        .filter { it.value >= HeavyRotationMinimumRecentPlays }
        .sortedWith(
            compareByDescending<Map.Entry<String, Long>> { it.value }
                .thenByDescending { playHistory.byTrack[it.key] ?: 0L }
                .thenByDescending { playHistory.playCountByTrack[it.key] ?: 0L },
        )
        .take(limit)
    if (ranked.isEmpty()) return emptyList()
    val tracksById = lookupTracksByIds(catalog, ranked.map { it.key }.toSet())
    return ranked.mapNotNull { (trackId, recentCount) ->
        tracksById[trackId]?.let { track ->
            HomePlayedTrack(
                track = track,
                lastPlayedMs = playHistory.byTrack[trackId],
                playCount = recentCount,
            )
        }
    }
}

internal fun albumAddedByTitle(catalog: CatalogSnapshot): Map<String, Long> =
    catalog.albums
        .asSequence()
        .mapNotNull { album -> album.dateAddedMs?.let { album.title.lowercase() to it } }
        .groupingBy { it.first }
        .aggregate { _, accumulator: Long?, element, _ -> maxOf(accumulator ?: Long.MIN_VALUE, element.second) }

internal fun artistAddedByTitle(catalog: CatalogSnapshot): Map<String, Long> =
    catalog.albums
        .asSequence()
        .mapNotNull { album -> album.dateAddedMs?.let { album.artist.lowercase() to it } }
        .groupingBy { it.first }
        .aggregate { _, accumulator: Long?, element, _ -> maxOf(accumulator ?: Long.MIN_VALUE, element.second) }

internal fun recentlyAddedAt(artist: Artist, artistAddedByTitle: Map<String, Long>): Long =
    artist.dateAddedMs ?: artistAddedByTitle[artist.title.lowercase()] ?: Long.MIN_VALUE

internal fun effectiveTrackDateAdded(track: Track, albumAddedByTitle: Map<String, Long>): Long =
    track.dateAddedMs ?: albumAddedByTitle[track.album.lowercase()] ?: Long.MIN_VALUE

internal fun allLoadedTracks(catalog: CatalogSnapshot): List<Track> =
    catalog.tracksByParent.values
        .asSequence()
        .flatten()
        .distinctBy { it.personalMixIdentityKey() }
        .filter { it.streamUrl.isNotBlank() || !it.localUri.isNullOrBlank() }
        .toList()

internal fun availableDecades(catalog: CatalogSnapshot): List<Int> =
    allLoadedTracks(catalog)
        .mapNotNull { it.year }
        .map { (it / 10) * 10 }
        .distinct()
        .sortedDescending()

fun defaultMixDecades(): List<Int> =
    (1900..2020 step 10).toList().asReversed()

internal fun decadeMix(catalog: CatalogSnapshot, decade: Int): List<Track> =
    allLoadedTracks(catalog)
        .filter { track -> track.year?.let { it >= decade && it <= decade + 9 } == true }
        .shuffled()

internal enum class MixMaturity {
    Sparse,
    Growing,
    Established,
}

internal fun mixMaturity(playHistory: PlayHistorySnapshot): MixMaturity {
    val uniqueTracks = maxOf(playHistory.byTrack.size, playHistory.playCountByTrack.size)
    val totalPlays = playHistory.playCountByTrack.values.sum()
    return when {
        uniqueTracks >= 100 -> MixMaturity.Established
        uniqueTracks >= 25 || totalPlays >= 50 -> MixMaturity.Growing
        else -> MixMaturity.Sparse
    }
}

/** 0.0 = sparse warming-up profile, 1.0 = user-established profile. */
internal fun mixMaturityBlend(playHistory: PlayHistorySnapshot): Double {
    val uniqueTracks = maxOf(playHistory.byTrack.size, playHistory.playCountByTrack.size)
    val totalPlays = playHistory.playCountByTrack.values.sum()
    if (uniqueTracks >= 100) return 1.0
    if (uniqueTracks < 25 && totalPlays < 50) return 0.0
    return ((uniqueTracks - 25).coerceIn(0, 75) / 75.0).coerceIn(0.0, 1.0)
}

private data class EffectiveMixWeights(
    val heavyRotation: Int,
    val recent: Int,
    val mostPlayed: Int,
    val similar: Int,
    val discovery: Int,
    val favorites: Int = 0,
    val recentlyAdded: Int = 0,
    val wildcards: Int = 0,
    val ratedUnplayed: Int = 0,
) {
    fun asList(): List<Int> = listOf(
        heavyRotation,
        recent,
        mostPlayed,
        similar,
        discovery,
        favorites,
        recentlyAdded,
        wildcards,
        ratedUnplayed,
    )
}

internal fun effectivePersonalMixPreferences(
    base: PersonalMixPreferences,
    playHistory: PlayHistorySnapshot,
): PersonalMixPreferences {
    val blend = mixMaturityBlend(playHistory)
    if (blend >= 1.0) return base.normalized()
    val normalized = base.normalized()
    val sparse = EffectiveMixWeights(
        heavyRotation = 15,
        recent = 15,
        mostPlayed = 15,
        similar = 20,
        discovery = 25,
        favorites = 5,
        recentlyAdded = 5,
        wildcards = 5,
        ratedUnplayed = 5,
    )
    val established = EffectiveMixWeights(
        heavyRotation = normalized.heavyRotationWeight,
        recent = normalized.recentWeight,
        mostPlayed = normalized.mostPlayedWeight,
        similar = normalized.similarWeight,
        discovery = normalized.discoveryWeight,
    )
    fun lerp(sparseWeight: Int, establishedWeight: Int): Int =
        (sparseWeight + (establishedWeight - sparseWeight) * blend).roundToInt().coerceAtLeast(0)
    return normalized.copy(
        heavyRotationWeight = lerp(sparse.heavyRotation, established.heavyRotation),
        recentWeight = lerp(sparse.recent, established.recent),
        mostPlayedWeight = lerp(sparse.mostPlayed, established.mostPlayed),
        similarWeight = lerp(sparse.similar, established.similar),
        discoveryWeight = lerp(sparse.discovery, established.discovery),
    )
}

private fun sparseOnlyMixWeights(blend: Double): EffectiveMixWeights {
    if (blend >= 1.0) return EffectiveMixWeights(0, 0, 0, 0, 0)
    val sparseOnlyScale = (1.0 - blend).coerceIn(0.0, 1.0)
    fun scaled(weight: Int): Int = (weight * sparseOnlyScale).roundToInt()
    return EffectiveMixWeights(
        heavyRotation = 0,
        recent = 0,
        mostPlayed = 0,
        similar = 0,
        discovery = 0,
        favorites = scaled(5),
        recentlyAdded = scaled(5),
        wildcards = scaled(5),
        ratedUnplayed = scaled(5),
    )
}

fun personalMix(
    catalog: CatalogSnapshot,
    state: HomeUiState,
    preferences: PersonalMixPreferences = PersonalMixPreferences.Default,
    limit: Int = preferences.normalized().limit,
    playHistory: PlayHistorySnapshot = PlayHistorySnapshot(),
    recentMixTrackKeys: Set<String> = emptySet(),
): List<Track> {
    val mixPrefs = effectivePersonalMixPreferences(preferences, playHistory).copy(limit = limit)
    val maturityBlend = mixMaturityBlend(playHistory)
    val sparseOnlyWeights = sparseOnlyMixWeights(maturityBlend)
    val tracks = allLoadedTracks(catalog)
    if (tracks.isEmpty()) return emptyList()
    val tracksByIdentity = tracks.associateBy { it.personalMixIdentityKey() }
    val heavyRotation = state.heavyRotationTracks.mapNotNull { tracksByIdentity[it.track.personalMixIdentityKey()] }
    val recent = state.recentlyPlayedTracks.mapNotNull { tracksByIdentity[it.track.personalMixIdentityKey()] }
    val most = state.mostPlayedTracks.mapNotNull { tracksByIdentity[it.track.personalMixIdentityKey()] }
    val seeds = (heavyRotation + recent + most).distinctBy { it.personalMixIdentityKey() }
    if (seeds.isEmpty()) return tracks.shuffled().take(mixPrefs.limit)

    val seedKeys = seeds.map { it.personalMixIdentityKey() }.toSet()
    val seedArtists = seeds.map { it.artist.lowercase() }.toSet()
    val sparseSimilar = maturityBlend < 0.5
    val similar = similarTracks(tracks, seeds, seedKeys, sparseSimilar)
    val playedKeys = (state.recentlyPlayedTracks + state.mostPlayedTracks + state.heavyRotationTracks)
        .map { it.track.personalMixIdentityKey() }
        .toSet()
    val unplayed = tracks.filter { it.personalMixIdentityKey() !in playedKeys }
    val discovery = discoveryTracks(unplayed, maturityBlend)
    val favorites = favoriteMixTracks(catalog, tracks).filter { it.personalMixIdentityKey() !in seedKeys }
    val recentlyAdded = state.recentlyAddedTracks
        .mapNotNull { tracksByIdentity[it.personalMixIdentityKey()] }
        .filter { it.personalMixIdentityKey() !in seedKeys }
    val wildcards = tracks.filter { it.personalMixIdentityKey() !in seedKeys }
    val ratedUnplayed = unplayed.filter { track ->
        val rating = track.rating
        rating != null && rating >= RatedUnplayedMinimumStars
    }

    val target = mixPrefs.limit.coerceAtLeast(1)
    val coreWeights = EffectiveMixWeights(
        heavyRotation = mixPrefs.heavyRotationWeight,
        recent = mixPrefs.recentWeight,
        mostPlayed = mixPrefs.mostPlayedWeight,
        similar = mixPrefs.similarWeight,
        discovery = mixPrefs.discoveryWeight,
    )
    val allWeights = coreWeights.asList() + sparseOnlyWeights.asList().drop(5)
    val slices = mixSliceCounts(target, allWeights)
    val diversity = MixDiversityLimits.forTarget(target)
    val decadeCapEnabled = tracks.mapNotNull { track -> track.year?.let { (it / 10) * 10 } }.distinct().size >= 2
    return buildPersonalMixList(
        target = target,
        slices = slices,
        sliceCandidates = listOf(
            heavyRotation,
            recent,
            most,
            similar,
            discovery,
            favorites,
            recentlyAdded,
            wildcards,
            ratedUnplayed,
        ),
        filler = tracks,
        diversity = diversity,
        decadeCapEnabled = decadeCapEnabled,
        recentMixTrackKeys = recentMixTrackKeys,
    ).let { mix -> deprioritizeRecentMixTracks(mix, recentMixTrackKeys) }
}

private const val RatedUnplayedMinimumStars = 3f
private const val MaxDecadeFraction = 0.4

private data class MixDiversityLimits(
    val maxPerArtist: Int,
    val maxPerAlbum: Int,
) {
    companion object {
        fun forTarget(target: Int): MixDiversityLimits =
            MixDiversityLimits(
                maxPerArtist = if (target <= 30) 1 else 2,
                maxPerAlbum = 1,
            )
    }
}

private class MixDiversityState(
    private val target: Int,
    private val limits: MixDiversityLimits,
    private val decadeCapEnabled: Boolean,
) {
    private val artistCounts = mutableMapOf<String, Int>()
    private val albumCounts = mutableMapOf<String, Int>()
    private val decadeCounts = mutableMapOf<Int, Int>()
    private val identityKeys = mutableSetOf<String>()

    fun canAdd(track: Track): Boolean {
        val identityKey = track.personalMixIdentityKey()
        if (identityKey in identityKeys) return false
        val artist = track.artist.lowercase()
        if ((artistCounts[artist] ?: 0) >= limits.maxPerArtist) return false
        val album = track.album.lowercase()
        if ((albumCounts[album] ?: 0) >= limits.maxPerAlbum) return false
        if (decadeCapEnabled) {
            val decade = track.year?.let { (it / 10) * 10 }
            if (decade != null) {
                val maxFromDecade = (target * MaxDecadeFraction).toInt().coerceAtLeast(1)
                if ((decadeCounts[decade] ?: 0) >= maxFromDecade) return false
            }
        }
        return true
    }

    fun record(track: Track) {
        identityKeys += track.personalMixIdentityKey()
        val artist = track.artist.lowercase()
        artistCounts[artist] = (artistCounts[artist] ?: 0) + 1
        val album = track.album.lowercase()
        albumCounts[album] = (albumCounts[album] ?: 0) + 1
        track.year?.let { (it / 10) * 10 }?.let { decade ->
            decadeCounts[decade] = (decadeCounts[decade] ?: 0) + 1
        }
    }
}

private fun deprioritizeRecentMixTracks(mix: List<Track>, recentMixTrackKeys: Set<String>): List<Track> {
    if (recentMixTrackKeys.isEmpty()) return mix
    val (recent, fresh) = mix.partition { it.personalMixIdentityKey() in recentMixTrackKeys }
    return fresh + recent
}

private fun buildPersonalMixList(
    target: Int,
    slices: List<Int>,
    sliceCandidates: List<List<Track>>,
    filler: List<Track>,
    diversity: MixDiversityLimits,
    decadeCapEnabled: Boolean,
    recentMixTrackKeys: Set<String>,
): List<Track> {
    val diversityState = MixDiversityState(target, diversity, decadeCapEnabled)
    val sliceAdded = IntArray(sliceCandidates.size)
    return buildList {
        fun tryAdd(track: Track): Boolean {
            if (size >= target || !diversityState.canAdd(track)) return false
            add(track)
            diversityState.record(track)
            return true
        }
        fun addFromCandidates(candidates: List<Track>, maxCount: Int, freshOnly: Boolean): Int {
            if (maxCount <= 0) return 0
            var added = 0
            val pool = candidates.shuffled().filter { track ->
                val isRecent = track.personalMixIdentityKey() in recentMixTrackKeys
                if (freshOnly) !isRecent else isRecent
            }
            pool.forEach { track ->
                if (added < maxCount && size < target && tryAdd(track)) added++
            }
            return added
        }
        sliceCandidates.forEachIndexed { index, candidates ->
            val maxCount = slices.getOrElse(index) { 0 }
            sliceAdded[index] = addFromCandidates(candidates, maxCount, freshOnly = true)
        }
        // Aggressive Deprioritization: Instead of falling back to recent tracks for this specific slice,
        // we let the slice fall short and make up the difference with fresh filler tracks.
        addFromCandidates(filler, target - size, freshOnly = true)
        
        // Absolute last resort if the entire library is exhausted of fresh tracks
        addFromCandidates(filler, target - size, freshOnly = false)
    }
}

private fun similarTracks(
    tracks: List<Track>,
    seeds: List<Track>,
    seedKeys: Set<String>,
    sparseMode: Boolean,
): List<Track> {
    val seedArtists = seeds.map { it.artist.lowercase() }.toSet()
    val seedAlbums = seeds.map { it.album.lowercase() }.toSet()
    return tracks
        .asSequence()
        .filter { it.personalMixIdentityKey() !in seedKeys }
        .map { track -> track to similarTrackScore(track, seeds, seedAlbums) }
        .filter { (track, score) ->
            if (score <= 0) return@filter false
            if (!sparseMode) return@filter true
            val differentArtist = track.artist.lowercase() !in seedArtists
            val genreMatch = track.genre != null &&
                seeds.any { seed -> seed.genre.equals(track.genre, ignoreCase = true) }
            val moodStyleMatch =
                (track.mood != null && seeds.any { seed -> seed.mood.equals(track.mood, ignoreCase = true) }) ||
                    (track.style != null && seeds.any { seed -> seed.style.equals(track.style, ignoreCase = true) })
            differentArtist && (genreMatch || moodStyleMatch)
        }
        .sortedByDescending { it.second }
        .map { it.first }
        .toList()
}

private fun similarTrackScore(track: Track, seeds: List<Track>, seedAlbums: Set<String>): Int {
    var score = 0
    if (seeds.any { it.artist.equals(track.artist, ignoreCase = true) }) score += 3
    if (track.genre != null && seeds.any { it.genre.equals(track.genre, ignoreCase = true) }) score += 2
    if (track.mood != null && seeds.any { it.mood.equals(track.mood, ignoreCase = true) }) score += 2
    if (track.style != null && seeds.any { it.style.equals(track.style, ignoreCase = true) }) score += 2
    val trackDecade = track.year?.let { (it / 10) * 10 }
    if (trackDecade != null && seeds.any { seed -> seed.year?.let { (it / 10) * 10 } == trackDecade }) score += 1
    if (track.album.lowercase() in seedAlbums) score -= 2
    return score
}

private fun discoveryTracks(unplayed: List<Track>, maturityBlend: Double): List<Track> {
    val randomWeight = ((1.0 - maturityBlend) * 0.5).coerceIn(0.0, 0.5)
    val byDateAdded = unplayed.sortedByDescending { it.dateAddedMs ?: 0L }
    if (randomWeight <= 0.0) return byDateAdded
    val randomPool = unplayed.shuffled()
    if (byDateAdded.isEmpty()) return randomPool
    if (randomPool.isEmpty()) return byDateAdded
    return byDateAdded.flatMapIndexed { index, dated ->
        val random = randomPool.getOrNull(index)
        if (random != null && random.personalMixIdentityKey() != dated.personalMixIdentityKey()) {
            listOf(dated, random)
        } else {
            listOf(dated)
        }
    }
}

private fun favoriteMixTracks(catalog: CatalogSnapshot, tracks: List<Track>): List<Track> {
    if (catalog.artists.none { it.favorite } && catalog.albums.none { it.favorite }) return emptyList()
    val favoriteArtistNames = catalog.artists.filter { it.favorite }.map { it.title.lowercase() }.toSet()
    val favoriteAlbumTitles = catalog.albums.filter { it.favorite }.map { it.title.lowercase() }.toSet()
    val favoriteAlbumIds = catalog.albums.filter { it.favorite }.map { it.id }.toSet()
    return tracks.filter { track ->
        track.artist.lowercase() in favoriteArtistNames ||
            track.album.lowercase() in favoriteAlbumTitles ||
            (track.parentAlbumId != null && track.parentAlbumId in favoriteAlbumIds)
    }
}

private fun mixSliceCounts(target: Int, weights: List<Int>): List<Int> {
    val normalizedWeights = weights.map { it.coerceAtLeast(0) }
    val totalWeight = normalizedWeights.sum()
    if (target <= 0 || totalWeight <= 0) return List(normalizedWeights.size) { 0 }
    val raw = normalizedWeights.map { weight -> (target.toDouble() * weight.toDouble()) / totalWeight.toDouble() }
    val base = raw.map { it.toInt() }.toMutableList()
    var remaining = target - base.sum()
    raw.indices
        .sortedWith(compareByDescending<Int> { raw[it] - base[it] }.thenBy { it })
        .forEach { index ->
            if (remaining > 0) {
                base[index]++
                remaining--
            }
        }
    return base
}

private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()

fun Track.personalMixIdentityKey(): String = playHistoryIdentityKey()
