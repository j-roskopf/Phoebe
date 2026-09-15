package com.phoebe.app.feature.history

import androidx.compose.runtime.Immutable

/**
 * Local UI model for a ranked artist on the Charts screen. Deliberately independent of any
 * data-layer ranking type so this screen compiles and is reviewable before those APIs land;
 * the shell maps its own ranking data into this shape.
 */
@Immutable
data class ChartsArtistRank(
    val id: String,
    val name: String,
    val thumbUrl: String? = null,
    val playCount: Long,
    val trackCount: Int = 0,
)

/** Local UI model for a ranked song on the Charts screen. See [ChartsArtistRank]. */
@Immutable
data class ChartsSongRank(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val thumbUrl: String? = null,
    val localArtworkUri: String? = null,
    val playCount: Long,
)

@Immutable
data class ChartsUiState(
    /** Null while ranked artists are still loading; empty once loaded with no history. */
    val topArtists: List<ChartsArtistRank>? = null,
    /** Null while ranked songs are still loading; empty once loaded with no history. */
    val topSongs: List<ChartsSongRank>? = null,
    val periodLabel: String = "All time",
) {
    val isLoading: Boolean
        get() = topArtists == null || topSongs == null

    val isEmpty: Boolean
        get() = !isLoading && topArtists.orEmpty().isEmpty() && topSongs.orEmpty().isEmpty()

    companion object {
        val Loading = ChartsUiState()
    }
}

/**
 * Fake data so [ChartsScreen] is reviewable end-to-end before artist/song ranking APIs are
 * wired up by the shell.
 */
fun sampleChartsUiState(): ChartsUiState = ChartsUiState(
    topArtists = listOf(
        ChartsArtistRank(id = "artist-1", name = "Radiant Echo", playCount = 482, trackCount = 34),
        ChartsArtistRank(id = "artist-2", name = "Midnight Parade", playCount = 391, trackCount = 21),
        ChartsArtistRank(id = "artist-3", name = "Glass Horizon", playCount = 337, trackCount = 18),
        ChartsArtistRank(id = "artist-4", name = "Copper Wire", playCount = 298, trackCount = 27),
        ChartsArtistRank(id = "artist-5", name = "Static Bloom", playCount = 260, trackCount = 15),
        ChartsArtistRank(id = "artist-6", name = "Paper Satellites", playCount = 214, trackCount = 12),
    ),
    topSongs = listOf(
        ChartsSongRank(id = "song-1", title = "Afterglow", artist = "Radiant Echo", album = "Halflight", playCount = 96),
        ChartsSongRank(id = "song-2", title = "Neon Static", artist = "Midnight Parade", album = "Parade", playCount = 81),
        ChartsSongRank(id = "song-3", title = "Low Orbit", artist = "Glass Horizon", album = "Horizon Line", playCount = 74),
        ChartsSongRank(id = "song-4", title = "Wire & Rust", artist = "Copper Wire", album = "Copper Wire", playCount = 63),
        ChartsSongRank(id = "song-5", title = "Bloomfield", artist = "Static Bloom", album = "Static Bloom", playCount = 58),
        ChartsSongRank(id = "song-6", title = "Drift Codes", artist = "Paper Satellites", album = "Satellites", playCount = 47),
        ChartsSongRank(id = "song-7", title = "Afterglow (Reprise)", artist = "Radiant Echo", album = "Halflight", playCount = 41),
    ),
)

/** Empty-history variant for previewing the empty state. */
fun emptyChartsUiState(): ChartsUiState = ChartsUiState(
    topArtists = emptyList(),
    topSongs = emptyList(),
)
