package com.phoebe.app.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.phoebe.app.data.PlayHistorySnapshot
import com.phoebe.app.data.derivationKey
import com.phoebe.app.data.mostPlayedPendingResolution
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

@Immutable
data class HomeFeatureState(
    val uiState: HomeUiState,
    val mostPlayedResolving: Boolean,
    val catalogHomeMetadataKey: Long,
    val catalogTrackIndexKey: Long,
    val resolvedTracksById: Map<String, Track>,
    val onRefreshRandomArtists: () -> Unit,
    val onRefreshRandomAlbums: () -> Unit,
)

@Composable
fun rememberHomeFeatureState(
    catalog: CatalogSnapshot,
    playHistory: PlayHistorySnapshot,
    catalogSyncInProgress: Boolean,
    trackHeavySectionsEnabled: Boolean,
    nowMs: Long,
    resolveTracksByIds: suspend (List<String>) -> Map<String, Track>,
    mostPlayedVisibleLimit: Int = 4,
): HomeFeatureState {
    var randomArtistSeed by remember { mutableStateOf(Random.nextInt()) }
    var randomAlbumSeed by remember { mutableStateOf(Random.nextInt()) }
    val trackIndexCache = remember { HomeCatalogIndexCache() }
    // Default-bound derive is non-cancellable mid-work; serialize shared cache access.
    val homeDeriveMutex = remember { Mutex() }
    val catalogHomeMetadataKey = if (catalogSyncInProgress) {
        catalog.homeMetadataRevisionKey()
    } else {
        catalog.homeMetadataKey()
    }
    val catalogTrackIndexKey = if (trackHeavySectionsEnabled) catalog.trackBatchRevisionKey() else 0L
    val mostPlayedPrefetchIds = remember(playHistory.topMostPlayed, playHistory.topRecentlyPlayed) {
        (playHistory.topMostPlayed.take(30).map { it.trackId } +
            playHistory.topRecentlyPlayed.take(15).map { it.trackId })
            .distinct()
    }
    val resolvedTracksById by produceState(emptyMap<String, Track>(), mostPlayedPrefetchIds, catalogTrackIndexKey) {
        value = if (mostPlayedPrefetchIds.isEmpty()) {
            emptyMap()
        } else {
            withTimeoutOrNull(HomePlayedTrackResolveTimeoutMs) {
                resolveTracksByIds(mostPlayedPrefetchIds)
            } ?: emptyMap()
        }
    }
    val resolvedTracksKey = resolvedTracksById.keys.fold(0L) { acc, id -> acc * 31L + id.hashCode() }
    val playHistoryDerivationKey = playHistory.derivationKey()
    val hasRankedPlayHistory = playHistory.topMostPlayed.isNotEmpty() || playHistory.topRecentlyPlayed.isNotEmpty()
    val homeUiState by produceState(
        initialValue = HomeUiState(),
        catalogTrackIndexKey,
        catalogHomeMetadataKey,
        playHistoryDerivationKey,
        randomArtistSeed,
        randomAlbumSeed,
        nowMs,
        trackHeavySectionsEnabled,
        resolvedTracksKey,
    ) {
        val delayMs = homeUiStateDeriveDelayMs(
            catalogSyncInProgress = catalogSyncInProgress,
            trackHeavySectionsEnabled = trackHeavySectionsEnabled,
            hasRankedPlayHistory = hasRankedPlayHistory,
            hasRenderedPlayHistory = value.mostPlayedTracks.isNotEmpty() || value.recentlyPlayedTracks.isNotEmpty(),
        )
        if (delayMs > 0L) delay(delayMs)
        value = withContext(Dispatchers.Default) {
            homeDeriveMutex.withLock {
                deriveHomeUiState(
                    catalog = catalog,
                    playHistory = playHistory,
                    randomArtistSeed = randomArtistSeed,
                    randomAlbumSeed = randomAlbumSeed,
                    nowMs = nowMs,
                    trackIndexCache = trackIndexCache,
                    includeTrackDerivedSections = trackHeavySectionsEnabled,
                    resolvedTracksById = resolvedTracksById,
                )
            }
        }
    }
    val mostPlayedResolving = playHistory.mostPlayedPendingResolution(
        resolvedCount = homeUiState.mostPlayedTracks.size,
        limit = mostPlayedVisibleLimit,
    )
    val refreshRandomArtists = remember {
        { randomArtistSeed = Random.nextInt() }
    }
    val refreshRandomAlbums = remember {
        { randomAlbumSeed = Random.nextInt() }
    }
    return HomeFeatureState(
        uiState = homeUiState,
        mostPlayedResolving = mostPlayedResolving,
        catalogHomeMetadataKey = catalogHomeMetadataKey,
        catalogTrackIndexKey = catalogTrackIndexKey,
        resolvedTracksById = resolvedTracksById,
        onRefreshRandomArtists = refreshRandomArtists,
        onRefreshRandomAlbums = refreshRandomAlbums,
    )
}

internal fun homeUiStateDeriveDelayMs(
    catalogSyncInProgress: Boolean,
    trackHeavySectionsEnabled: Boolean,
    hasRankedPlayHistory: Boolean,
    hasRenderedPlayHistory: Boolean,
): Long {
    if (hasRankedPlayHistory && !hasRenderedPlayHistory) return 0L
    return when {
        catalogSyncInProgress -> 250L
        trackHeavySectionsEnabled -> 80L
        else -> 50L
    }
}

private const val HomePlayedTrackResolveTimeoutMs = 2_000L
