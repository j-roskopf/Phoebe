package com.phoebe.app.feature.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.phoebe.app.data.artistAlbumCountSubtitle
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.DownloadState
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.RecentSearchItem
import com.phoebe.app.domain.SavedSearch
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.TrackFilterContext
import com.phoebe.app.domain.catalogPrefix
import com.phoebe.app.domain.filterWith
import com.phoebe.app.domain.parseAdvancedSearchQuery
import com.phoebe.app.ui.ArtworkImage
import com.phoebe.app.ui.AutoScrollingText
import com.phoebe.app.ui.LocalMobileChromePadding
import com.phoebe.app.ui.PhoebeIcon
import com.phoebe.app.ui.PhoebeIconView
import com.phoebe.app.ui.PhoebeDesktopLayout
import com.phoebe.app.ui.PhoebeTheme
import com.phoebe.app.ui.PhoebeUi
import com.phoebe.app.ui.SearchPill
import com.phoebe.app.ui.SectionLabel
import com.phoebe.app.ui.TrackArtworkImage
import com.phoebe.app.ui.formatDuration
import com.phoebe.app.ui.openContextMenuOnSecondaryClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SearchUiResults(
    val tracks: List<Track>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val topArtist: Artist?,
    val topAlbum: Album?,
    val topTrack: Track?,
)

internal enum class SearchResultScope {
    Overview,
    Songs,
    Albums,
    Artists,
}

private const val SearchResultLimit = 500

@Composable
internal fun rememberSearchUiResults(catalog: CatalogSnapshot, searchQuery: String): SearchUiResults {
    val query = searchQuery.trim()
    return produceState(
        initialValue = SearchResultsFactory.EmptyResults,
        catalog.albums,
        catalog.artists,
        catalog.tracksByParent,
        catalog.downloads,
        query,
    ) {
        value = if (query.isBlank()) {
            SearchResultsFactory.EmptyResults
        } else {
            withContext(Dispatchers.Default) {
                val allTracks = catalog.tracksByParent.values.asSequence().flatten().distinctBy { it.id }.toList()
                deriveSearchUiResults(catalog, query, allTracks)
            }
        }
    }.value
}

fun deriveSearchUiResults(
    catalog: CatalogSnapshot,
    searchQuery: String,
    tracks: List<Track> = catalog.tracksByParent.values.asSequence().flatten().distinctBy { it.id }.toList(),
): SearchUiResults {
    val query = searchQuery.trim()
    if (query.isBlank()) {
        return SearchUiResults(
            tracks = emptyList(),
            albums = emptyList(),
            artists = emptyList(),
            topArtist = null,
            topAlbum = null,
            topTrack = null,
        )
    }
    val advancedQuery = parseAdvancedSearchQuery(query)
    val filterContext = catalog.toTrackFilterContext()
    val filteredTracks = tracks.filterWith(advancedQuery.filter, filterContext)
    val textQuery = advancedQuery.text.ifBlank { query.takeIf { advancedQuery.filter.rules.isEmpty() }.orEmpty() }
    val matchingTracks = if (textQuery.isBlank()) {
        filteredTracks.take(SearchResultLimit)
    } else {
        filteredTracks.matchingSearch(textQuery) {
            it.title.contains(textQuery, ignoreCase = true) ||
                it.artist.contains(textQuery, ignoreCase = true) ||
                it.album.contains(textQuery, ignoreCase = true)
        }
    }
    val albums = if (textQuery.isBlank()) {
        emptyList()
    } else {
        catalog.albums.matchingSearch(textQuery) {
            it.title.contains(textQuery, ignoreCase = true) ||
                it.artist.contains(textQuery, ignoreCase = true)
        }
    }
    val artists = if (textQuery.isBlank()) {
        emptyList()
    } else {
        catalog.artists.matchingSearch(textQuery) { it.title.contains(textQuery, ignoreCase = true) }
    }
    return SearchUiResults(
        tracks = matchingTracks,
        albums = albums,
        artists = artists,
        topArtist = bestSearchMatch(artists, textQuery) { it.title },
        topAlbum = bestSearchMatch(albums, textQuery) { it.title },
        topTrack = bestSearchMatch(matchingTracks, textQuery) { it.title },
    )
}

private inline fun <T> Iterable<T>.matchingSearch(query: String, matches: (T) -> Boolean): List<T> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return emptyList()
    val results = ArrayList<T>(SearchResultLimit)
    for (item in this) {
        if (matches(item)) {
            results += item
            if (results.size == SearchResultLimit) break
        }
    }
    return results
}

private fun CatalogSnapshot.toTrackFilterContext(): TrackFilterContext {
    val downloadedIds = downloads
        .asSequence()
        .filter { it.state == DownloadState.Complete }
        .mapTo(mutableSetOf()) { it.trackId }
    val providers = tracksByParent.values
        .asSequence()
        .flatten()
        .associate { track -> track.id to track.providerTypeFromId() }
        .filterValues { it != null }
        .mapValues { (_, value) -> value ?: MediaProviderType.Plex }
    return TrackFilterContext(
        downloadedTrackIds = downloadedIds,
        providerByTrackId = providers,
    )
}

private fun Track.providerTypeFromId(): MediaProviderType? =
    MediaProviderType.entries.firstOrNull { provider -> id.startsWith("${provider.catalogPrefix}:") }

@Composable
private fun SearchInputWithSyntaxHelp(
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSyntax by remember { mutableStateOf(false) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SearchPill(searchQuery, onSearchQuery, Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(PhoebeUi.sidebar)
                .border(BorderStroke(1.dp, PhoebeUi.border), CircleShape)
                .clickable { showSyntax = true }
                .semantics { contentDescription = "Search syntax" },
            contentAlignment = Alignment.Center,
        ) {
            Text("?", color = PhoebeUi.accentLight, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
    if (showSyntax) {
        SearchSyntaxDialog(onDismiss = { showSyntax = false })
    }
}

@Composable
private fun SearchSyntaxDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .widthIn(min = 300.dp, max = 460.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(PhoebeUi.modalSurface)
                .border(BorderStroke(1.dp, PhoebeUi.accentLight.copy(alpha = 0.18f)), RoundedCornerShape(18.dp))
                .padding(horizontal = 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Search syntax", color = PhoebeUi.primaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchSyntaxLine("artist:Beach House", "match artist text")
                SearchSyntaxLine("album:\"Once Twice Melody\"", "quote multi-word values")
                SearchSyntaxLine("year:1999", "match one year")
                SearchSyntaxLine("year:1990..1999", "match a year range")
                SearchSyntaxLine("rating:>=4", "compare numeric values")
                SearchSyntaxLine("downloaded:true", "filter downloaded songs")
                SearchSyntaxLine("local:false explicit:false", "combine filters")
                SearchSyntaxLine("codec:flac provider:plex", "match exact source fields")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("Done", color = PhoebeUi.accentLight, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SearchSyntaxLine(example: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(example, color = PhoebeUi.primaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(description, color = PhoebeUi.secondaryText, fontSize = 12.sp)
    }
}

@Composable
private fun SavedSearchStrip(
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    compact: Boolean,
) {
    val actions = LocalSavedSearchActions.current
    val normalizedQuery = searchQuery.trim()
    val canSave = normalizedQuery.isNotBlank() &&
        actions.savedSearches.none { it.title.equals(normalizedQuery, ignoreCase = true) }
    if (!canSave && actions.savedSearches.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Saved searches",
                color = PhoebeUi.mutedText,
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (canSave) {
                SavedSearchActionChip(
                    title = "Save current",
                    accent = true,
                    onClick = { actions.saveSearch(normalizedQuery, normalizedQuery) },
                )
            }
        }
        if (actions.savedSearches.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(actions.savedSearches, key = { it.id }) { savedSearch ->
                    SavedSearchChip(
                        savedSearch = savedSearch,
                        onOpen = { onSearchQuery(savedSearch.title) },
                        onDelete = { actions.deleteSearch(savedSearch) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedSearchChip(
    savedSearch: SavedSearch,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(PhoebeUi.sidebar)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(999.dp))
            .padding(start = 11.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PhoebeIconView(PhoebeIcon.Search, tint = PhoebeUi.accentLight, modifier = Modifier.size(13.dp))
            Text(
                savedSearch.title,
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(150.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            PhoebeIconView(PhoebeIcon.Close, tint = PhoebeUi.mutedText, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun SavedSearchActionChip(
    title: String,
    accent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = if (accent) PhoebeUi.accentLight else PhoebeUi.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

internal fun <T> bestSearchMatch(items: List<T>, query: String, label: (T) -> String): T? {
    if (query.isBlank()) return null
    return items.minByOrNull { item ->
        val text = label(item).trim()
        when {
            text.equals(query, ignoreCase = true) -> 0
            text.startsWith(query, ignoreCase = true) -> 1
            text.contains(query, ignoreCase = true) -> 2
            else -> 3
        }
    }
}

@Composable
fun SearchDesktopView(
    catalog: CatalogSnapshot,
    catalogRefreshing: Boolean,
    searchQuery: String,
    modifier: Modifier = Modifier,
    onSearchQuery: (String) -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    loadingContent: @Composable () -> Unit = {},
    trackMenuContent: @Composable (
        track: Track,
        expanded: Boolean,
        onDismiss: () -> Unit,
        onAddToUpNext: () -> Unit,
        onDownload: () -> Unit,
    ) -> Unit = { track, expanded, onDismiss, onAddToUpNext, onDownload ->
        DefaultSearchTrackMenuContent(track, expanded, onDismiss, onAddToUpNext, onDownload)
    },
    topBar: (@Composable () -> Unit)? = null,
) {
    val results = rememberSearchUiResults(catalog, searchQuery)
    val searchHistory = LocalSearchHistory.current
    val hasQuery = searchQuery.isNotBlank()
    var resultScope by remember { mutableStateOf(SearchResultScope.Overview) }
    LaunchedEffect(searchQuery) {
        resultScope = SearchResultScope.Overview
    }
    val openArtist: (Artist) -> Unit = { artist ->
        searchHistory.recordArtist(artist)
        onArtist(artist)
    }
    val openAlbum: (Album) -> Unit = { album ->
        searchHistory.recordAlbum(album)
        onAlbum(album)
    }
    val playTracks: (List<Track>, Int) -> Unit = { tracks, index ->
        tracks.getOrNull(index)?.let(searchHistory.recordTrack)
        onPlayTracks(tracks, index)
    }
    BoxWithConstraints(modifier) {
        val compactPane = maxWidth < 900.dp
        val contentPadding = PaddingValues(
            start = PhoebeDesktopLayout.contentStart,
            end = PhoebeDesktopLayout.contentEnd,
            top = PhoebeDesktopLayout.contentTop,
            bottom = PhoebeDesktopLayout.contentBottom,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            if (compactPane) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column {
                        Text("Search", color = PhoebeUi.primaryText, fontSize = 30.sp, fontWeight = FontWeight.Black)
                        Text("Find your favorite music", color = PhoebeUi.mutedText, fontSize = 13.sp)
                    }
                    SearchInputWithSyntaxHelp(searchQuery, onSearchQuery, Modifier.fillMaxWidth())
                    SavedSearchStrip(searchQuery, onSearchQuery, compact = true)
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text("Search", color = PhoebeUi.primaryText, fontSize = 30.sp, fontWeight = FontWeight.Black)
                        Text("Find your favorite music", color = PhoebeUi.mutedText, fontSize = 13.sp)
                    }
                    SearchPill(searchQuery, onSearchQuery, Modifier.width(PhoebeDesktopLayout.searchWidth))
                }
                SavedSearchStrip(searchQuery, onSearchQuery, compact = false)
            }
            if (catalogRefreshing) {
                loadingContent()
            }
            if (hasQuery && resultScope != SearchResultScope.Overview) {
                SearchAllResultsPanel(
                    scope = resultScope,
                    results = results,
                    catalog = catalog,
                    compact = compactPane,
                    onBack = { resultScope = SearchResultScope.Overview },
                    onArtist = openArtist,
                    onAlbum = openAlbum,
                    onPlayTracks = playTracks,
                    onAddToUpNext = onAddToUpNext,
                    onDownload = onDownload,
                    trackMenuContent = trackMenuContent,
                )
            } else if (hasQuery) {
                SearchTopResultSection(
                    results = results,
                    catalog = catalog,
                    onAlbum = openAlbum,
                    onArtist = openArtist,
                    onPlayTracks = playTracks,
                    compact = compactPane,
                )
            }
            if (hasQuery && resultScope == SearchResultScope.Overview) {
                SearchSongsSection(
                    tracks = results.tracks.take(5),
                    allTracks = results.tracks,
                    compact = compactPane,
                    onSeeAll = if (results.tracks.size > 5) {
                        { resultScope = SearchResultScope.Songs }
                    } else {
                        null
                    },
                    onPlayTracks = playTracks,
                    onAddToUpNext = onAddToUpNext,
                    onDownload = onDownload,
                    trackMenuContent = trackMenuContent,
                )
                if (compactPane) {
                    SearchAlbumsSection(
                        albums = results.albums.take(6),
                        catalog = catalog,
                        onAlbum = openAlbum,
                        compact = true,
                        onSeeAll = if (results.albums.size > 6) {
                            { resultScope = SearchResultScope.Albums }
                        } else {
                            null
                        },
                    )
                    SearchArtistsSection(
                        artists = results.artists.take(4),
                        catalog = catalog,
                        onArtist = openArtist,
                        compact = true,
                        onSeeAll = if (results.artists.size > 4) {
                            { resultScope = SearchResultScope.Artists }
                        } else {
                            null
                        },
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        SearchAlbumsSection(
                            albums = results.albums.take(5),
                            catalog = catalog,
                            onAlbum = openAlbum,
                            modifier = Modifier.weight(1.15f),
                            compact = false,
                            onSeeAll = if (results.albums.size > 5) {
                                { resultScope = SearchResultScope.Albums }
                            } else {
                                null
                            },
                        )
                        SearchArtistsSection(
                            artists = results.artists.take(3),
                            catalog = catalog,
                            onArtist = openArtist,
                            modifier = Modifier.weight(0.85f),
                            compact = false,
                            onSeeAll = if (results.artists.size > 3) {
                                { resultScope = SearchResultScope.Artists }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            if (!hasQuery && searchHistory.recentItems.isNotEmpty()) {
                SearchRecentPanel(
                    items = searchHistory.recentItems,
                    onArtist = openArtist,
                    onAlbum = openAlbum,
                    onTrack = { track, tracks, index -> playTracks(tracks, index) },
                    onRemoveItem = searchHistory.removeItem,
                    onClearItems = searchHistory.clearItems,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun SearchMobileView(
    catalog: CatalogSnapshot,
    catalogRefreshing: Boolean,
    searchQuery: String,
    modifier: Modifier = Modifier,
    onSearchQuery: (String) -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    loadingContent: @Composable () -> Unit = {},
    trackMenuContent: @Composable (
        track: Track,
        expanded: Boolean,
        onDismiss: () -> Unit,
        onAddToUpNext: () -> Unit,
        onDownload: () -> Unit,
    ) -> Unit = { track, expanded, onDismiss, onAddToUpNext, onDownload ->
        DefaultSearchTrackMenuContent(track, expanded, onDismiss, onAddToUpNext, onDownload)
    },
    topBar: (@Composable () -> Unit)? = null,
) {
    val results = rememberSearchUiResults(catalog, searchQuery)
    val searchHistory = LocalSearchHistory.current
    val hasQuery = searchQuery.isNotBlank()
    var resultScope by remember { mutableStateOf(SearchResultScope.Overview) }
    LaunchedEffect(searchQuery) {
        resultScope = SearchResultScope.Overview
    }
    val openArtist: (Artist) -> Unit = { artist ->
        searchHistory.recordArtist(artist)
        onArtist(artist)
    }
    val openAlbum: (Album) -> Unit = { album ->
        searchHistory.recordAlbum(album)
        onAlbum(album)
    }
    val playTracks: (List<Track>, Int) -> Unit = { tracks, index ->
        tracks.getOrNull(index)?.let(searchHistory.recordTrack)
        onPlayTracks(tracks, index)
    }
    val chromePadding = LocalMobileChromePadding.current
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 10.dp,
            bottom = chromePadding.bottom + 18.dp,
        ),
    ) {
        topBar?.let { header ->
            item(key = "top-bar", contentType = "top-bar") { header() }
        }
        item(contentType = "search-field") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchInputWithSyntaxHelp(searchQuery, onSearchQuery, Modifier.fillMaxWidth())
                SavedSearchStrip(searchQuery, onSearchQuery, compact = true)
            }
        }
        if (catalogRefreshing) {
            item(contentType = "loading") { loadingContent() }
        }
        if (hasQuery && resultScope != SearchResultScope.Overview) {
            item(contentType = "all-results") {
                SearchAllResultsPanel(
                    scope = resultScope,
                    results = results,
                    catalog = catalog,
                    compact = true,
                    onBack = { resultScope = SearchResultScope.Overview },
                    onArtist = openArtist,
                    onAlbum = openAlbum,
                    onPlayTracks = playTracks,
                    onAddToUpNext = onAddToUpNext,
                    onDownload = onDownload,
                    trackMenuContent = trackMenuContent,
                )
            }
        } else if (hasQuery) {
            item(contentType = "top-result") {
                SearchTopResultSection(
                    results = results,
                    catalog = catalog,
                    onAlbum = openAlbum,
                    onArtist = openArtist,
                    onPlayTracks = playTracks,
                    compact = true,
                )
            }
        }
        if (hasQuery && resultScope == SearchResultScope.Overview) {
            item(contentType = "songs") {
                SearchSongsSection(
                    tracks = results.tracks.take(5),
                    allTracks = results.tracks,
                    compact = true,
                    onSeeAll = if (results.tracks.size > 5) {
                        { resultScope = SearchResultScope.Songs }
                    } else {
                        null
                    },
                    onPlayTracks = playTracks,
                    onAddToUpNext = onAddToUpNext,
                    onDownload = onDownload,
                    trackMenuContent = trackMenuContent,
                )
            }
            item(contentType = "albums") {
                SearchAlbumsSection(
                    albums = results.albums.take(6),
                    catalog = catalog,
                    onAlbum = openAlbum,
                    compact = true,
                    onSeeAll = if (results.albums.size > 6) {
                        { resultScope = SearchResultScope.Albums }
                    } else {
                        null
                    },
                )
            }
            item(contentType = "artists") {
                SearchArtistsSection(
                    artists = results.artists.take(4),
                    catalog = catalog,
                    onArtist = openArtist,
                    compact = true,
                    onSeeAll = if (results.artists.size > 4) {
                        { resultScope = SearchResultScope.Artists }
                    } else {
                        null
                    },
                )
            }
        }
        if (!hasQuery && searchHistory.recentItems.isNotEmpty()) {
            item(contentType = "recent") {
                SearchRecentPanel(
                    items = searchHistory.recentItems,
                    onArtist = openArtist,
                    onAlbum = openAlbum,
                    onTrack = { track, tracks, index -> playTracks(tracks, index) },
                    onRemoveItem = searchHistory.removeItem,
                    onClearItems = searchHistory.clearItems,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun SearchTopResultSection(
    results: SearchUiResults,
    catalog: CatalogSnapshot,
    onAlbum: (Album) -> Unit,
    onArtist: (Artist) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    compact: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Top Result", PhoebeUi.primaryText)
        when {
            results.topArtist != null -> SearchTopArtistCard(
                artist = results.topArtist,
                catalog = catalog,
                onArtist = onArtist,
                compact = compact,
            )
            results.topAlbum != null -> SearchTopAlbumCard(
                album = results.topAlbum,
                tracks = catalogTracksForAlbum(catalog, results.topAlbum),
                onAlbum = onAlbum,
                onPlayTracks = onPlayTracks,
                compact = compact,
            )
            results.topTrack != null -> SearchTopTrackCard(results.topTrack, results.tracks, onPlayTracks, compact)
            else -> SearchEmptyCard("Start typing to search songs, albums, and artists.")
        }
    }
}

@Composable
internal fun SearchTopAlbumCard(
    album: Album,
    tracks: List<Track>,
    onAlbum: (Album) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    compact: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.16f))
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(14.dp))
            .clickable { onAlbum(album) }
            .padding(if (compact) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp),
    ) {
        ArtworkImage(album.title, album.thumbUrl, Modifier.size(if (compact) 76.dp else 170.dp), radius = 10.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp)) {
            Text(album.title, color = PhoebeUi.primaryText, fontSize = if (compact) 14.sp else 24.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(album.artist, color = PhoebeUi.secondaryText, fontSize = if (compact) 11.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Album • ${album.year ?: "Unknown year"}", color = PhoebeUi.mutedText, fontSize = if (compact) 11.sp else 12.sp)
            if (!compact) {
                SearchPlayChip(enabled = tracks.isNotEmpty()) {
                    if (tracks.isNotEmpty()) onPlayTracks(tracks, 0)
                }
            }
        }
        if (compact) {
            SearchRoundPlayButton(enabled = tracks.isNotEmpty()) {
                if (tracks.isNotEmpty()) onPlayTracks(tracks, 0)
            }
            PhoebeIconView(PhoebeIcon.More, tint = PhoebeUi.mutedText, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun SearchTopTrackCard(
    track: Track,
    tracks: List<Track>,
    onPlayTracks: (List<Track>, Int) -> Unit,
    compact: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.16f))
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(14.dp))
            .clickable { onPlayTracks(tracks, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)) }
            .padding(if (compact) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp),
    ) {
        TrackArtworkImage(track, Modifier.size(if (compact) 76.dp else 170.dp), radius = 10.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp)) {
            if (compact) {
                AutoScrollingText(track.title, color = PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.Black)
            } else {
                Text(track.title, color = PhoebeUi.primaryText, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            AutoScrollingText(track.artist, color = PhoebeUi.secondaryText, fontSize = if (compact) 11.sp else 14.sp)
            Text("Song • ${formatDuration(track.durationMs)}", color = PhoebeUi.mutedText, fontSize = if (compact) 11.sp else 12.sp)
            if (!compact) {
                SearchPlayChip(enabled = true) { onPlayTracks(tracks, 0) }
            }
        }
        if (compact) {
            SearchRoundPlayButton(enabled = true) { onPlayTracks(tracks, 0) }
            PhoebeIconView(PhoebeIcon.More, tint = PhoebeUi.mutedText, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun SearchTopArtistCard(
    artist: Artist,
    catalog: CatalogSnapshot,
    onArtist: (Artist) -> Unit,
    compact: Boolean,
) {
    val songCount = catalogTrackCountForArtist(catalog, artist)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.16f))
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(14.dp))
            .clickable { onArtist(artist) }
            .padding(if (compact) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp),
    ) {
        ArtworkImage(artist.title, artist.thumbUrl, Modifier.size(if (compact) 76.dp else 148.dp), radius = 999.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(artist.title, color = PhoebeUi.primaryText, fontSize = if (compact) 14.sp else 24.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${artistAlbumCountSubtitle(artist)} • ${songCountLabel(songCount)}", color = PhoebeUi.mutedText, fontSize = if (compact) 11.sp else 12.sp)
        }
    }
}

@Composable
internal fun SearchAllResultsPanel(
    scope: SearchResultScope,
    results: SearchUiResults,
    catalog: CatalogSnapshot,
    compact: Boolean,
    onBack: () -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    trackMenuContent: @Composable (
        track: Track,
        expanded: Boolean,
        onDismiss: () -> Unit,
        onAddToUpNext: () -> Unit,
        onDownload: () -> Unit,
    ) -> Unit,
) {
    val (title, count) = when (scope) {
        SearchResultScope.Songs -> "Songs" to results.tracks.size
        SearchResultScope.Albums -> "Albums" to results.albums.size
        SearchResultScope.Artists -> "Artists" to results.artists.size
        SearchResultScope.Overview -> "Results" to 0
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SearchAllResultsHeader(title = title, count = count, onBack = onBack)
        when (scope) {
            SearchResultScope.Songs -> SearchSongsSection(
                tracks = results.tracks,
                allTracks = results.tracks,
                compact = compact,
                onPlayTracks = onPlayTracks,
                onAddToUpNext = onAddToUpNext,
                onDownload = onDownload,
                trackMenuContent = trackMenuContent,
            )
            SearchResultScope.Albums -> SearchAllAlbumsSection(
                albums = results.albums,
                catalog = catalog,
                compact = compact,
                onAlbum = onAlbum,
            )
            SearchResultScope.Artists -> SearchAllArtistsSection(
                artists = results.artists,
                catalog = catalog,
                onArtist = onArtist,
            )
            SearchResultScope.Overview -> Unit
        }
    }
}

@Composable
internal fun SearchAllResultsHeader(title: String, count: Int, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .semantics { contentDescription = "Back to search overview" },
            contentAlignment = Alignment.Center,
        ) {
            PhoebeIconView(PhoebeIcon.Back, tint = PhoebeUi.primaryText, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            SectionLabel(title, PhoebeUi.primaryText)
            Text("$count results", color = PhoebeUi.mutedText, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun SearchSongsSection(
    tracks: List<Track>,
    allTracks: List<Track>,
    compact: Boolean,
    onSeeAll: (() -> Unit)? = null,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    trackMenuContent: @Composable (
        track: Track,
        expanded: Boolean,
        onDismiss: () -> Unit,
        onAddToUpNext: () -> Unit,
        onDownload: () -> Unit,
    ) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchSectionHeader("Songs", showSeeAll = onSeeAll != null, onSeeAll = onSeeAll)
        if (tracks.isEmpty()) {
            SearchEmptyCard("No songs found.")
        } else {
            if (!compact) {
                SearchSongsHeader()
            }
            tracks.forEachIndexed { index, track ->
                SearchSongResultRow(
                    track = track,
                    index = index,
                    tracks = allTracks,
                    compact = compact,
                    onPlayTracks = onPlayTracks,
                    onAddToUpNext = onAddToUpNext,
                    onDownload = onDownload,
                    trackMenuContent = trackMenuContent,
                )
            }
        }
    }
}

@Composable
internal fun SearchSongsHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("#", color = PhoebeUi.mutedText, fontSize = 10.sp, modifier = Modifier.width(24.dp))
        Text("Title", color = PhoebeUi.mutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.25f))
        Text("Artist", color = PhoebeUi.mutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.95f))
        Text("Album", color = PhoebeUi.mutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.95f))
        Text("Duration", color = PhoebeUi.mutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(76.dp), textAlign = TextAlign.End)
        Spacer(Modifier.width(36.dp))
    }
}

@Composable
internal fun SearchSongResultRow(
    track: Track,
    index: Int,
    tracks: List<Track>,
    compact: Boolean,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    trackMenuContent: @Composable (
        track: Track,
        expanded: Boolean,
        onDismiss: () -> Unit,
        onAddToUpNext: () -> Unit,
        onDownload: () -> Unit,
    ) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val trackIndex = tracks.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: index
    if (compact) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .openContextMenuOnSecondaryClick { menuExpanded = true }
                .combinedClickable(onClick = { onPlayTracks(tracks, trackIndex) }, onLongClick = { menuExpanded = true })
                .background(if (index == 0) PhoebeUi.accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.035f))
                .padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TrackArtworkImage(track, Modifier.size(38.dp), radius = 7.dp)
            Column(Modifier.weight(1f)) {
                AutoScrollingText(track.title, color = if (index == 0) PhoebeUi.accentLight else PhoebeUi.primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                AutoScrollingText(track.artist, color = PhoebeUi.secondaryText, fontSize = 10.sp)
            }
            Text(formatDuration(track.durationMs), color = PhoebeUi.mutedText, fontSize = 10.sp)
            SearchOverflowMenu(
                track = track,
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
                onAddToUpNext = onAddToUpNext,
                onDownload = onDownload,
                trackMenuContent = trackMenuContent,
            )
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .openContextMenuOnSecondaryClick { menuExpanded = true }
                .combinedClickable(onClick = { onPlayTracks(tracks, trackIndex) }, onLongClick = { menuExpanded = true })
                .background(if (index == 0) PhoebeUi.accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.032f))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text((index + 1).toString(), color = PhoebeUi.mutedText, fontSize = 11.sp, modifier = Modifier.width(18.dp))
            TrackArtworkImage(track, Modifier.size(24.dp), radius = 5.dp)
            AutoScrollingText(track.title, color = if (index == 0) PhoebeUi.accentLight else PhoebeUi.primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.25f))
            AutoScrollingText(track.artist, color = PhoebeUi.secondaryText, fontSize = 11.sp, modifier = Modifier.weight(0.95f))
            AutoScrollingText(track.album, color = PhoebeUi.secondaryText, fontSize = 11.sp, modifier = Modifier.weight(0.95f))
            Text(formatDuration(track.durationMs), color = PhoebeUi.mutedText, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(66.dp))
            SearchOverflowMenu(
                track = track,
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
                onAddToUpNext = onAddToUpNext,
                onDownload = onDownload,
                trackMenuContent = trackMenuContent,
            )
        }
    }
}

@Composable
internal fun SearchAlbumsSection(
    albums: List<Album>,
    catalog: CatalogSnapshot,
    onAlbum: (Album) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean,
    onSeeAll: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchSectionHeader("Albums", showSeeAll = onSeeAll != null, onSeeAll = onSeeAll)
        if (albums.isEmpty()) {
            SearchEmptyCard("No albums found.")
        } else if (compact) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(albums, key = { it.id }, contentType = { "search-album" }) { album ->
                    SearchAlbumTile(album, catalogTracksForAlbum(catalog, album).size, onAlbum, Modifier.width(104.dp), compact = true)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                albums.forEach { album ->
                    SearchAlbumTile(album, catalogTracksForAlbum(catalog, album).size, onAlbum, Modifier.weight(1f), compact = false)
                }
            }
        }
    }
}

@Composable
internal fun SearchAlbumTile(
    album: Album,
    trackCount: Int,
    onAlbum: (Album) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onAlbum(album) }
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ArtworkImage(album.title, album.thumbUrl, Modifier.fillMaxWidth().aspectRatio(1f), radius = 10.dp)
        Text(album.title, color = PhoebeUi.primaryText, fontSize = if (compact) 10.sp else 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(album.artist, color = PhoebeUi.mutedText, fontSize = if (compact) 9.sp else 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!compact) {
            Text("${album.year ?: "Album"} • $trackCount tracks", color = PhoebeUi.mutedText, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun SearchAllAlbumsSection(
    albums: List<Album>,
    catalog: CatalogSnapshot,
    compact: Boolean,
    onAlbum: (Album) -> Unit,
) {
    if (albums.isEmpty()) {
        SearchEmptyCard("No albums found.")
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minCardWidth = if (compact) 132.dp else 148.dp
        val gap = 12.dp
        val columns = ((maxWidth + gap) / (minCardWidth + gap)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            albums.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { album ->
                        SearchAlbumTile(
                            album = album,
                            trackCount = catalogTracksForAlbum(catalog, album).size,
                            onAlbum = onAlbum,
                            modifier = Modifier.weight(1f),
                            compact = compact,
                        )
                    }
                    repeat(columns - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchArtistsSection(
    artists: List<Artist>,
    catalog: CatalogSnapshot,
    onArtist: (Artist) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean,
    onSeeAll: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchSectionHeader("Artists", showSeeAll = onSeeAll != null, onSeeAll = onSeeAll)
        if (artists.isEmpty()) {
            SearchEmptyCard("No artists found.")
        } else {
            if (compact) {
                artists.forEach { SearchArtistRow(it, catalog, onArtist, compact = true) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    artists.forEach { artist ->
                        SearchArtistTile(artist, catalog, onArtist, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchAllArtistsSection(
    artists: List<Artist>,
    catalog: CatalogSnapshot,
    onArtist: (Artist) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (artists.isEmpty()) {
            SearchEmptyCard("No artists found.")
        } else {
            artists.forEach { artist ->
                SearchArtistRow(artist, catalog, onArtist, compact = true)
            }
        }
    }
}

@Composable
internal fun SearchArtistTile(artist: Artist, catalog: CatalogSnapshot, onArtist: (Artist) -> Unit, modifier: Modifier = Modifier) {
    val songCount = catalogTrackCountForArtist(catalog, artist)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onArtist(artist) }
            .background(Color.White.copy(alpha = 0.035f))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ArtworkImage(artist.title, artist.thumbUrl, Modifier.size(74.dp), radius = 999.dp)
        Text(artist.title, color = PhoebeUi.primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(songCountLabel(songCount), color = PhoebeUi.mutedText, fontSize = 10.sp)
    }
}

@Composable
internal fun SearchArtistRow(artist: Artist, catalog: CatalogSnapshot, onArtist: (Artist) -> Unit, compact: Boolean) {
    val songCount = catalogTrackCountForArtist(catalog, artist)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onArtist(artist) }
            .background(Color.White.copy(alpha = 0.035f))
            .padding(horizontal = 8.dp, vertical = if (compact) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ArtworkImage(artist.title, artist.thumbUrl, Modifier.size(42.dp), radius = 999.dp)
        Column(Modifier.weight(1f)) {
            Text(artist.title, color = PhoebeUi.primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${artistAlbumCountSubtitle(artist)} • ${songCountLabel(songCount)}", color = PhoebeUi.mutedText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(16.dp))
    }
}

@Composable
internal fun SearchRecentPanel(
    items: List<RecentSearchItem>,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onTrack: (Track, List<Track>, Int) -> Unit,
    onRemoveItem: (RecentSearchItem) -> Unit,
    onClearItems: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entityItems = items.filterNot { it is RecentSearchItem.Query }
    if (entityItems.isEmpty()) return

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Recent", PhoebeUi.primaryText)
            Text(
                "Clear",
                color = PhoebeUi.mutedText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onClearItems)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        entityItems.forEach { item ->
            when (item) {
                is RecentSearchItem.Query -> Unit
                is RecentSearchItem.ArtistHit -> RecentSearchEntityRow(
                    title = item.artist.title,
                    subtitle = "Artist",
                    thumbUrl = item.artist.thumbUrl,
                    seed = item.artist.title,
                    onClick = { onArtist(item.artist) },
                    onRemove = { onRemoveItem(item) },
                )
                is RecentSearchItem.AlbumHit -> RecentSearchEntityRow(
                    title = item.album.title,
                    subtitle = "${item.album.artist} • Album",
                    thumbUrl = item.album.thumbUrl,
                    seed = item.album.title,
                    onClick = { onAlbum(item.album) },
                    onRemove = { onRemoveItem(item) },
                )
                is RecentSearchItem.TrackHit -> RecentSearchEntityRow(
                    title = item.track.title,
                    subtitle = "${item.track.artist} • ${item.track.album}",
                    thumbUrl = item.track.thumbUrl,
                    seed = item.track.album,
                    onClick = { onTrack(item.track, listOf(item.track), 0) },
                    onRemove = { onRemoveItem(item) },
                )
            }
        }
    }
}

@Composable
private fun RecentSearchEntityRow(
    title: String,
    subtitle: String,
    thumbUrl: String?,
    seed: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ArtworkImage(seed, thumbUrl, Modifier.size(28.dp), radius = 6.dp, elevated = false)
        Column(Modifier.weight(1f)) {
            Text(title, color = PhoebeUi.primaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = PhoebeUi.mutedText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RecentSearchRemoveButton(onRemove)
    }
}

@Composable
private fun RecentSearchRemoveButton(onRemove: () -> Unit) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(onClick = onRemove),
        contentAlignment = Alignment.Center,
    ) {
        PhoebeIconView(PhoebeIcon.Close, tint = PhoebeUi.mutedText.copy(alpha = 0.68f), modifier = Modifier.size(11.dp))
    }
}

@Composable
internal fun SearchSectionHeader(label: String, showSeeAll: Boolean, onSeeAll: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(label, PhoebeUi.primaryText)
        Spacer(Modifier.weight(1f))
        if (showSeeAll) {
            Text(
                "See all",
                color = PhoebeUi.mutedText,
                fontSize = 10.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = onSeeAll != null) { onSeeAll?.invoke() }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
internal fun SearchPlayChip(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Brush.linearGradient(listOf(PhoebeUi.accentLight, PhoebeUi.accent)) else Brush.linearGradient(listOf(PhoebeUi.mutedText.copy(alpha = 0.28f), PhoebeUi.mutedText.copy(alpha = 0.22f))))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PhoebeIconView(PhoebeIcon.Play, tint = PhoebeUi.primaryText, modifier = Modifier.size(13.dp))
        Text("Play", color = PhoebeUi.primaryText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun SearchRoundPlayButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (enabled) Brush.linearGradient(listOf(PhoebeUi.accentLight, PhoebeUi.accent)) else Brush.linearGradient(listOf(PhoebeUi.mutedText.copy(alpha = 0.24f), PhoebeUi.mutedText.copy(alpha = 0.18f))))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PhoebeIconView(PhoebeIcon.Play, tint = PhoebeUi.primaryText, modifier = Modifier.size(15.dp))
    }
}

@Composable
internal fun SearchEmptyCard(message: String) {
    Text(
        message,
        color = PhoebeUi.mutedText,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.035f))
            .padding(14.dp),
    )
}

@Composable
internal fun SearchOverflowMenu(
    track: Track,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    trackMenuContent: @Composable (
        track: Track,
        expanded: Boolean,
        onDismiss: () -> Unit,
        onAddToUpNext: () -> Unit,
        onDownload: () -> Unit,
    ) -> Unit,
) {
    Box {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable { onExpandedChange(true) },
            contentAlignment = Alignment.Center,
        ) {
            PhoebeIconView(PhoebeIcon.More, tint = PhoebeUi.mutedText, modifier = Modifier.size(17.dp))
        }
        trackMenuContent(
            track,
            expanded,
            { onExpandedChange(false) },
            { onAddToUpNext(track) },
            { onDownload(track) },
        )
    }
}

@Composable
internal fun DefaultSearchTrackMenuContent(
    track: Track,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAddToUpNext: () -> Unit,
    onDownload: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Add to Up Next") },
            onClick = {
                onAddToUpNext()
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Download") },
            onClick = {
                onDownload()
                onDismiss()
            },
        )
    }
}

internal fun catalogTracksForAlbum(catalog: CatalogSnapshot, album: Album): List<Track> {
    val direct = catalog.tracksByParent[album.id].orEmpty()
    if (direct.isNotEmpty()) return direct
    return catalog.tracksByParent.values
        .asSequence()
        .flatten()
        .filter { it.album.equals(album.title, ignoreCase = true) && it.artist.equals(album.artist, ignoreCase = true) }
        .distinctBy { it.id }
        .toList()
}

internal fun catalogTrackCountForArtist(catalog: CatalogSnapshot, artist: Artist): Int =
    catalog.tracksByParent.values
        .asSequence()
        .flatten()
        .filter { it.artist.equals(artist.title, ignoreCase = true) }
        .distinctBy { it.id }
        .count()
        .takeIf { it > 0 }
        ?: artist.songCount

internal fun songCountLabel(count: Int): String {
    val word = if (count == 1) "song" else "songs"
    return "$count $word"
}

private object SearchScreenPreviewData {
    val artist = Artist(
        id = "artist-the-midnights",
        title = "The Midnights",
        albumCount = 2,
        songCount = 4,
    )
    val album = Album(
        id = "album-midnight-city",
        title = "Midnight City",
        artist = artist.title,
        year = 2024,
    )
    val tracks = listOf(
        Track(
            id = "track-midnight-drive",
            title = "Midnight Drive",
            artist = artist.title,
            album = album.title,
            durationMs = 213_000L,
            streamUrl = "",
            downloadUrl = "",
            parentAlbumId = album.id,
        ),
        Track(
            id = "track-midnight-static",
            title = "Midnight Static",
            artist = artist.title,
            album = album.title,
            durationMs = 188_000L,
            streamUrl = "",
            downloadUrl = "",
            parentAlbumId = album.id,
        ),
        Track(
            id = "track-city-lights",
            title = "City Lights",
            artist = artist.title,
            album = album.title,
            durationMs = 241_000L,
            streamUrl = "",
            downloadUrl = "",
            parentAlbumId = album.id,
        ),
    )
    val catalog = CatalogSnapshot(
        artists = listOf(
            artist,
            Artist(
                id = "artist-luna",
                title = "Luna North",
                albumCount = 1,
                songCount = 1,
            ),
        ),
        albums = listOf(
            album,
            Album(
                id = "album-northern-lines",
                title = "Northern Lines",
                artist = "Luna North",
                year = 2022,
            ),
        ),
        tracksByParent = mapOf(album.id to tracks),
    )
}

@PreviewLightDark
@PreviewScreenSizes
@Composable
private fun SearchDesktopViewPreview() {
    PhoebeTheme {
        SearchDesktopView(
            catalog = SearchScreenPreviewData.catalog,
            catalogRefreshing = false,
            searchQuery = "midnight",
            modifier = Modifier
                .fillMaxSize()
                .background(PhoebeUi.shellTop),
            onSearchQuery = {},
            onArtist = {},
            onAlbum = {},
            onPlayTracks = { _, _ -> },
            onAddToUpNext = {},
            onDownload = {},
        )
    }
}

@PreviewLightDark
@PreviewScreenSizes
@Composable
private fun SearchMobileViewPreview() {
    PhoebeTheme {
        SearchMobileView(
            catalog = SearchScreenPreviewData.catalog,
            catalogRefreshing = false,
            searchQuery = "midnight",
            modifier = Modifier
                .fillMaxSize()
                .background(PhoebeUi.shellTop),
            onSearchQuery = {},
            onArtist = {},
            onAlbum = {},
            onPlayTracks = { _, _ -> },
            onAddToUpNext = {},
            onDownload = {},
        )
    }
}
