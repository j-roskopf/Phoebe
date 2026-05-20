package com.phoebe.app.ui

import androidx.compose.runtime.Immutable
import com.phoebe.app.data.catalogAlbumsForArtist
import com.phoebe.app.data.catalogTracksForAlbum
import com.phoebe.app.data.catalogTracksForArtist
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.PersonalMixPreferences
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.Track
import kotlin.random.Random

internal const val RecentlyAddedWindowMs = 7L * 24L * 60L * 60L * 1000L
internal const val HeavyRotationWindowMs = 14L * 24L * 60L * 60L * 1000L
private const val HeavyRotationMinimumRecentPlays = 2L

@Immutable
internal data class HomeUiState(
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
    val randomArtistStats: HomeFeaturedArtistStats? = null,
    val randomAlbumStats: HomeFeaturedAlbumStats? = null,
)

@Immutable
internal data class HomePlayedTrack(
    val track: Track,
    val lastPlayedMs: Long? = null,
    val playCount: Long = 0L,
)

@Immutable
internal data class HomeFeaturedArtistStats(
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
internal data class HomeFeaturedAlbumStats(
    val albumId: String,
    val trackCount: Int,
    val totalDurationMs: Long,
    val genre: String?,
    val tracksLoaded: Boolean,
)

private data class HomeTrackIndex(
    val tracksById: Map<String, Track>,
    val recentlyAddedTracks: List<Track>,
)

private data class MostPlayedScore(
    val playCount: Long,
    val lastPlayedMs: Long,
) : Comparable<MostPlayedScore> {
    override fun compareTo(other: MostPlayedScore): Int =
        playCount.compareTo(other.playCount).takeIf { it != 0 }
            ?: lastPlayedMs.compareTo(other.lastPlayedMs)
}

internal fun deriveHomeUiState(
    catalog: CatalogSnapshot,
    playHistory: PlayHistorySnapshot,
    randomArtistSeed: Int,
    randomAlbumSeed: Int,
    nowMs: Long,
    limit: Int = 10,
): HomeUiState {
    val cutoffMs = nowMs - RecentlyAddedWindowMs
    val albumAddedByTitle = albumAddedByTitle(catalog)
    val artistAddedByTitle = artistAddedByTitle(catalog)
    val trackIndex = homeTrackIndex(catalog, albumAddedByTitle, cutoffMs, limit)
    val tracksById = trackIndex.tracksById
    val recentArtists = topBy(
        catalog.artists.asSequence().filter { artist -> recentlyAddedAt(artist, artistAddedByTitle) >= cutoffMs },
        limit = limit,
        descending = true,
    ) { artist ->
        recentlyAddedAt(artist, artistAddedByTitle)
    }
    val recentAlbums = topBy(
        catalog.albums.asSequence().filter { (it.dateAddedMs ?: Long.MIN_VALUE) >= cutoffMs },
        limit = limit,
        descending = true,
    ) { album ->
        album.dateAddedMs ?: 0L
    }
    val recentlyPlayed = topBy(
        playHistory.byTrack.entries.asSequence(),
        limit = limit,
        descending = true,
    ) { it.value }
        .mapNotNull { (trackId, playedAt) ->
            tracksById[trackId]?.let { HomePlayedTrack(it, lastPlayedMs = playedAt, playCount = playHistory.playCountByTrack[trackId] ?: 0L) }
        }
    val mostPlayed = topBy(
        playHistory.playCountByTrack.entries.asSequence().filter { it.value > 0L },
        limit = limit,
        descending = true,
    ) { entry ->
        MostPlayedScore(
            playCount = entry.value,
            lastPlayedMs = playHistory.byTrack[entry.key] ?: 0L,
        )
    }
        .mapNotNull { (trackId, count) ->
            tracksById[trackId]?.let { HomePlayedTrack(it, lastPlayedMs = playHistory.byTrack[trackId], playCount = count) }
        }
    val heavyRotation = heavyRotationTracks(
        playHistory = playHistory,
        tracksById = tracksById,
        nowMs = nowMs,
        limit = limit,
    )
    val favoriteArtists = topBy(catalog.artists.asSequence().filter { it.favorite }, limit = limit) { it.title.lowercase() }
    val favoriteAlbums = topBy(catalog.albums.asSequence().filter { it.favorite }, limit = limit) { it.title.lowercase() }
    val favoritePlaylists = topBy(catalog.playlists.asSequence().filter { it.favorite }, limit = limit) { it.title.lowercase() }
    val randomArtists = deterministicSample(catalog.artists, randomArtistSeed, limit)
    val randomAlbums = deterministicSample(catalog.albums, randomAlbumSeed, limit)
    val randomArtistStats = randomArtists.firstOrNull()?.let { artist ->
        homeFeaturedArtistStats(artist, catalog, playHistory)
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
        albumCount = albums.size,
        trackCount = tracks.size,
        totalDurationMs = tracks.sumOf { it.durationMs },
        genre = mostFrequentGenre(tracks),
        lastPlayedMs = resolveArtistLastPlayed(artist.title, tracks, playHistory),
        hasAlbums = albums.isNotEmpty(),
        hasTracks = tracks.isNotEmpty(),
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
    cutoffMs: Long,
    limit: Int,
): HomeTrackIndex {
    val tracksById = linkedMapOf<String, Track>()
    val recentlyAdded = mutableListOf<Pair<Track, Long>>()
    catalog.tracksByParent.values.forEach { parentTracks ->
        parentTracks.forEach { track ->
            if (track.id !in tracksById) {
                tracksById[track.id] = track
                val addedAt = effectiveTrackDateAdded(track, albumAddedByTitle)
                if (addedAt >= cutoffMs) {
                    insertBounded(recentlyAdded, track, addedAt, limit, descending = true)
                }
            }
        }
    }
    return HomeTrackIndex(
        tracksById = tracksById,
        recentlyAddedTracks = recentlyAdded.map { it.first },
    )
}

private inline fun <T, S : Comparable<S>> topBy(
    items: Sequence<T>,
    limit: Int,
    descending: Boolean = false,
    crossinline selector: (T) -> S,
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

private fun heavyRotationTracks(
    playHistory: PlayHistorySnapshot,
    tracksById: Map<String, Track>,
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
    return recentPlayCounts.entries
        .filter { it.value >= HeavyRotationMinimumRecentPlays }
        .sortedWith(
            compareByDescending<Map.Entry<String, Long>> { it.value }
                .thenByDescending { playHistory.byTrack[it.key] ?: 0L }
                .thenByDescending { playHistory.playCountByTrack[it.key] ?: 0L },
        )
        .mapNotNull { (trackId, recentCount) ->
            tracksById[trackId]?.let { track ->
                HomePlayedTrack(
                    track = track,
                    lastPlayedMs = playHistory.byTrack[trackId],
                    playCount = recentCount,
                )
            }
        }
        .take(limit)
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

internal fun defaultMixDecades(): List<Int> =
    (1900..2020 step 10).toList().asReversed()

internal fun decadeMix(catalog: CatalogSnapshot, decade: Int): List<Track> =
    allLoadedTracks(catalog)
        .filter { track -> track.year?.let { it >= decade && it <= decade + 9 } == true }
        .shuffled()

internal fun personalMix(
    catalog: CatalogSnapshot,
    state: HomeUiState,
    preferences: PersonalMixPreferences = PersonalMixPreferences.Default,
    limit: Int = preferences.normalized().limit,
): List<Track> {
    val mixPrefs = preferences.normalized().copy(limit = limit)
    val tracks = allLoadedTracks(catalog)
    if (tracks.isEmpty()) return emptyList()
    val tracksByIdentity = tracks.associateBy { it.personalMixIdentityKey() }
    val heavyRotation = state.heavyRotationTracks.mapNotNull { tracksByIdentity[it.track.personalMixIdentityKey()] }
    val recent = state.recentlyPlayedTracks.mapNotNull { tracksByIdentity[it.track.personalMixIdentityKey()] }
    val most = state.mostPlayedTracks.mapNotNull { tracksByIdentity[it.track.personalMixIdentityKey()] }
    val seeds = (heavyRotation + recent + most).distinctBy { it.personalMixIdentityKey() }
    if (seeds.isEmpty()) return tracks.shuffled().take(mixPrefs.limit)

    val seedArtists = seeds.map { it.artist.lowercase() }.toSet()
    val seedGenres = seeds.mapNotNull { it.genre?.lowercase() }.toSet()
    val seedDecades = seeds.mapNotNull { it.year?.let { year -> (year / 10) * 10 } }.toSet()
    val seedKeys = seeds.map { it.personalMixIdentityKey() }.toSet()
    val similar = tracks.filter { track ->
        track.personalMixIdentityKey() !in seedKeys &&
            (track.artist.lowercase() in seedArtists ||
                track.genre?.lowercase() in seedGenres ||
                track.year?.let { (it / 10) * 10 }?.let { it in seedDecades } == true)
    }
    val playedKeys = (state.recentlyPlayedTracks + state.mostPlayedTracks)
        .map { it.track.personalMixIdentityKey() }
        .toSet()
    val discovery = tracks.filter { it.personalMixIdentityKey() !in playedKeys }
        .sortedByDescending { it.dateAddedMs ?: 0L }

    val target = mixPrefs.limit.coerceAtLeast(1)
    val slices = mixSliceCounts(target, mixPrefs)
    return buildList<Track> {
        fun addSlice(candidates: List<Track>, maxCount: Int) {
            var added = 0
            candidates.shuffled().forEach { track ->
                if (size < target && added < maxCount && none { existing -> existing.personalMixIdentityKey() == track.personalMixIdentityKey() }) {
                    add(track)
                    added++
                }
            }
        }
        addSlice(heavyRotation, slices[0])
        addSlice(recent, slices[1])
        addSlice(most, slices[2])
        addSlice(similar, slices[3])
        addSlice(discovery, slices[4])
        tracks.shuffled().forEach {
            if (size < target && none { existing -> existing.personalMixIdentityKey() == it.personalMixIdentityKey() }) add(it)
        }
    }
}

private fun mixSliceCounts(target: Int, preferences: PersonalMixPreferences): List<Int> {
    val weights = listOf(
        preferences.heavyRotationWeight,
        preferences.recentWeight,
        preferences.mostPlayedWeight,
        preferences.similarWeight,
        preferences.discoveryWeight,
    ).map { it.coerceAtLeast(0) }
    val totalWeight = weights.sum()
    if (target <= 0 || totalWeight <= 0) return listOf(0, 0, 0, 0, target.coerceAtLeast(0))
    val raw = weights.map { weight -> (target.toDouble() * weight.toDouble()) / totalWeight.toDouble() }
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

internal fun Track.personalMixIdentityKey(): String {
    val metadataKey = listOf(title, artist, album)
        .map { it.trim().lowercase() }
        .takeIf { parts -> parts.any { it.isNotBlank() } }
        ?.joinToString("|", prefix = "meta:", postfix = "|${durationMs.coerceAtLeast(0L)}")
    return metadataKey ?: providerEquivalentId()
}

private fun Track.providerEquivalentId(): String {
    val normalized = id.trim()
    val prefix = normalized.substringBefore(':', missingDelimiterValue = "")
    return when (prefix) {
        "plex", "jellyfin", "emby", "navidrome", "musicassistant" -> normalized.substringAfter(':')
        else -> normalized
    }
}
