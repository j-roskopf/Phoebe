package com.phoebe.app.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoebe.app.data.filterAlbumsByQuery
import com.phoebe.app.data.filterArtistsByQuery
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.hasPlayableSource
import com.phoebe.app.ui.ArtworkImage
import com.phoebe.app.ui.PhoebeDesktopLayout
import com.phoebe.app.ui.PhoebeIcon
import com.phoebe.app.ui.PhoebeIconView
import com.phoebe.app.ui.PhoebeUi
import com.phoebe.app.ui.SearchPill
import com.phoebe.app.ui.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

@Immutable
data class MixBuilderRouteState(
    val catalog: CatalogSnapshot,
    val bottomContentPadding: Dp = 0.dp,
)

@Immutable
class MixBuilderRouteActions(
    val onBack: () -> Unit,
    val onBuildQueue: (List<Track>) -> Unit,
    val onEnsureArtistSuggestions: (List<Artist>) -> Unit = {},
)

@Composable
fun ArtistMixBuilderRoute(
    state: MixBuilderRouteState,
    actions: MixBuilderRouteActions,
    modifier: Modifier = Modifier,
    topBar: (@Composable () -> Unit)? = null,
) {
    MixBuilderScreen(
        mode = MixBuilderMode.Artists,
        state = state,
        actions = actions,
        modifier = modifier,
        topBar = topBar,
    )
}

@Composable
fun AlbumMixBuilderRoute(
    state: MixBuilderRouteState,
    actions: MixBuilderRouteActions,
    modifier: Modifier = Modifier,
    topBar: (@Composable () -> Unit)? = null,
) {
    MixBuilderScreen(
        mode = MixBuilderMode.Albums,
        state = state,
        actions = actions,
        modifier = modifier,
        topBar = topBar,
    )
}

private enum class MixBuilderMode(
    val title: String,
    val subtitle: String,
    val searchPlaceholder: String,
    val selectedTitle: String,
    val resultsTitle: String,
    val suggestionsTitle: String,
    val emptySearchMessage: String,
    val emptySelectedMessage: String,
    val buildLabel: String,
) {
    Artists(
        title = "Artist Mix Builder",
        subtitle = "Best tracks first",
        searchPlaceholder = "Search artists",
        selectedTitle = "Selected artists",
        resultsTitle = "Artists",
        suggestionsTitle = "Suggested artists",
        emptySearchMessage = "No artists found.",
        emptySelectedMessage = "No artists selected.",
        buildLabel = "Build artist mix",
    ),
    Albums(
        title = "Album Mix Builder",
        subtitle = "Popular tracks, then the albums",
        searchPlaceholder = "Search albums",
        selectedTitle = "Selected albums",
        resultsTitle = "Albums",
        suggestionsTitle = "Suggested albums",
        emptySearchMessage = "No albums found.",
        emptySelectedMessage = "No albums selected.",
        buildLabel = "Build album mix",
    ),
}

@Composable
private fun MixBuilderScreen(
    mode: MixBuilderMode,
    state: MixBuilderRouteState,
    actions: MixBuilderRouteActions,
    modifier: Modifier = Modifier,
    topBar: (@Composable () -> Unit)? = null,
) {
    var query by rememberSaveable(mode) { mutableStateOf("") }
    var selectedIds by rememberSaveable(mode) { mutableStateOf(emptyList<String>()) }
    var buildingQueue by remember(mode) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val catalog = state.catalog
    val catalogMetadataRevision = catalog.homeMetadataRevisionKey()
    val trackBatchRevision = remember(catalog.tracksByParent) { catalog.trackBatchRevisionKey() }
    val similarArtistsRevision = catalog.similarArtistsByArtist.size
    val artistsById = remember(catalog.artists) { catalog.artists.associateBy { it.id } }
    val albumsById = remember(catalog.albums) { catalog.albums.associateBy { it.id } }
    var catalogIndexes by remember { mutableStateOf(MixBuilderCatalogIndexes.Empty) }
    LaunchedEffect(trackBatchRevision, catalogMetadataRevision) {
        catalogIndexes = withContext(Dispatchers.Default) {
            catalog.mixBuilderCatalogIndexes()
        }
    }
    val artistTrackIndex = catalogIndexes.artistTrackIndex
    val albumTrackIndex = catalogIndexes.albumTrackIndex
    val hapticFeedback = LocalHapticFeedback.current
    val selectedArtists = remember(selectedIds, artistsById) {
        selectedIds.mapNotNull(artistsById::get)
    }
    val selectedAlbums = remember(selectedIds, albumsById) {
        selectedIds.mapNotNull(albumsById::get)
    }
    val selectedArtistSuggestionKeys = remember(selectedArtists) { selectedArtists.map { it.id } }
    LaunchedEffect(mode, selectedArtistSuggestionKeys) {
        if (mode == MixBuilderMode.Artists && selectedArtists.isNotEmpty()) {
            actions.onEnsureArtistSuggestions(selectedArtists)
        }
    }
    var estimatedTrackCount by remember(mode) { mutableStateOf(0) }
    LaunchedEffect(mode, selectedArtists, selectedAlbums, catalogIndexes) {
        estimatedTrackCount = withContext(Dispatchers.Default) {
            when (mode) {
                MixBuilderMode.Artists -> selectedArtists.sumOf { artist -> artistTrackIndex.tracksForArtist(artist).size }
                MixBuilderMode.Albums -> selectedAlbums.sumOf { album -> albumTrackIndex.tracksForAlbum(album).size }
            }
        }
    }
    var searchResults by remember(mode) { mutableStateOf(emptyList<MixBuilderEntity>()) }
    LaunchedEffect(mode, query, catalogMetadataRevision, selectedIds) {
        searchResults = if (query.isBlank()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                val selectedIdSet = selectedIds.toSet()
                when (mode) {
                    MixBuilderMode.Artists -> filterArtistsByQuery(catalog.artists, query)
                        .filterNot { it.id in selectedIdSet }
                        .take(MixBuilderResultLimit)
                        .map(MixBuilderEntity::ArtistItem)
                    MixBuilderMode.Albums -> filterAlbumsByQuery(catalog.albums, query)
                        .filterNot { it.id in selectedIdSet }
                        .take(MixBuilderResultLimit)
                        .map(MixBuilderEntity::AlbumItem)
                }
            }
        }
    }
    var suggestions by remember(mode) { mutableStateOf(emptyList<MixBuilderEntity>()) }
    LaunchedEffect(
        mode,
        selectedIds,
        catalogMetadataRevision,
        trackBatchRevision,
        similarArtistsRevision,
        catalogIndexes,
    ) {
        suggestions = withContext(Dispatchers.Default) {
            when (mode) {
                MixBuilderMode.Artists -> artistMixBuilderSuggestions(catalog, selectedArtists, artistTrackIndex)
                    .map(MixBuilderEntity::ArtistItem)
                MixBuilderMode.Albums -> albumMixBuilderSuggestions(catalog, selectedAlbums)
                    .map(MixBuilderEntity::AlbumItem)
            }
        }
    }
    val addEntity: (MixBuilderEntity) -> Unit = { entity ->
        if (entity.id !in selectedIds) {
            selectedIds = selectedIds + entity.id
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    val removeEntity: (String) -> Unit = { id ->
        selectedIds = selectedIds.filterNot { it == id }
    }
    val buildQueue = {
        if (!buildingQueue) {
            coroutineScope.launch {
                buildingQueue = true
                val queue = try {
                    withContext(Dispatchers.Default) {
                        when (mode) {
                            MixBuilderMode.Artists -> artistMixBuilderQueue(
                                catalog,
                                selectedArtists,
                                artistTrackIndex.takeIf { it.isNotEmpty() } ?: catalog.mixBuilderArtistTrackIndex(),
                            )
                            MixBuilderMode.Albums -> albumMixBuilderQueue(
                                catalog,
                                selectedAlbums,
                                albumTrackIndex.takeIf { it.isNotEmpty() } ?: catalog.mixBuilderAlbumTrackIndex(),
                            )
                        }
                    }
                } finally {
                    buildingQueue = false
                }
                if (queue.isNotEmpty()) actions.onBuildQueue(queue)
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxWidth < 820.dp
        val contentPadding = if (compact) {
            PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 10.dp,
                bottom = state.bottomContentPadding + 18.dp,
            )
        } else {
            PaddingValues(
                start = PhoebeDesktopLayout.contentStart,
                end = PhoebeDesktopLayout.contentEnd,
                top = PhoebeDesktopLayout.contentTop,
                bottom = PhoebeDesktopLayout.contentBottom,
            )
        }

        if (compact) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                topBar?.let { header ->
                    item(key = "top-bar", contentType = "top-bar") { header() }
                }
                item(key = "header", contentType = "header") {
                    MixBuilderHeader(mode, actions.onBack, showBack = topBar == null)
                }
                item(key = "search", contentType = "search") {
                    MixBuilderSearchPanel(
                        mode = mode,
                        query = query,
                        onQuery = { query = it },
                        searchResults = searchResults,
                        suggestions = suggestions,
                        catalogIndexes = catalogIndexes,
                        onAdd = addEntity,
                    )
                }
                item(key = "selected", contentType = "selected") {
                    MixBuilderSelectedPanel(
                        mode = mode,
                        selectedArtists = selectedArtists,
                        selectedAlbums = selectedAlbums,
                        catalogIndexes = catalogIndexes,
                        estimatedTrackCount = estimatedTrackCount,
                        buildingQueue = buildingQueue,
                        onRemove = removeEntity,
                        onBuild = buildQueue,
                        boundedHeight = false,
                    )
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                MixBuilderHeader(mode, actions.onBack, showBack = true)
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    MixBuilderSearchPanel(
                        mode = mode,
                        query = query,
                        onQuery = { query = it },
                        searchResults = searchResults,
                        suggestions = suggestions,
                        catalogIndexes = catalogIndexes,
                        onAdd = addEntity,
                        modifier = Modifier.weight(1.18f).fillMaxSize(),
                    )
                    MixBuilderSelectedPanel(
                        mode = mode,
                        selectedArtists = selectedArtists,
                        selectedAlbums = selectedAlbums,
                        catalogIndexes = catalogIndexes,
                        estimatedTrackCount = estimatedTrackCount,
                        buildingQueue = buildingQueue,
                        onRemove = removeEntity,
                        onBuild = buildQueue,
                        modifier = Modifier.weight(0.82f).fillMaxSize(),
                        boundedHeight = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun MixBuilderHeader(
    mode: MixBuilderMode,
    onBack: () -> Unit,
    showBack: Boolean,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showBack) {
            MixBuilderIconButton(
                icon = PhoebeIcon.Back,
                contentDescription = "Back",
                onClick = onBack,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(mode.title, color = PhoebeUi.primaryText, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(mode.subtitle, color = PhoebeUi.secondaryText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MixBuilderSearchPanel(
    mode: MixBuilderMode,
    query: String,
    onQuery: (String) -> Unit,
    searchResults: List<MixBuilderEntity>,
    suggestions: List<MixBuilderEntity>,
    catalogIndexes: MixBuilderCatalogIndexes,
    onAdd: (MixBuilderEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    MixBuilderPanel(modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SearchPill(
                query = query,
                onQueryChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = mode.searchPlaceholder,
            )
            AnimatedVisibility(
                visible = query.isNotBlank(),
                enter = expandVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) + fadeIn(tween(120)),
                exit = shrinkVertically(animationSpec = tween(140, easing = FastOutSlowInEasing)) + fadeOut(tween(100)),
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    MixBuilderEntitySection(
                        title = mode.resultsTitle,
                        emptyMessage = mode.emptySearchMessage,
                        entities = searchResults,
                        catalogIndexes = catalogIndexes,
                        onAdd = onAdd,
                    )
                    AnimatedVisibility(
                        visible = suggestions.isNotEmpty(),
                        enter = expandVertically(animationSpec = tween(160, easing = FastOutSlowInEasing)) + fadeIn(tween(120)),
                        exit = shrinkVertically(animationSpec = tween(120, easing = FastOutSlowInEasing)) + fadeOut(tween(90)),
                    ) {
                        MixBuilderEntitySection(
                            title = mode.suggestionsTitle,
                            emptyMessage = "",
                            entities = suggestions,
                            catalogIndexes = catalogIndexes,
                            onAdd = onAdd,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MixBuilderEntitySection(
    title: String,
    emptyMessage: String,
    entities: List<MixBuilderEntity>,
    catalogIndexes: MixBuilderCatalogIndexes,
    onAdd: (MixBuilderEntity) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title, PhoebeUi.primaryText)
        if (entities.isEmpty()) {
            if (emptyMessage.isNotBlank()) MixBuilderEmptyState(emptyMessage)
        } else {
            entities.forEach { entity ->
                MixBuilderEntityRow(
                    entity = entity,
                    subtitle = entity.subtitle(catalogIndexes),
                    fallbackThumbUrl = entity.fallbackThumbUrl(catalogIndexes),
                    trailing = {
                        MixBuilderIconButton(
                            icon = PhoebeIcon.Plus,
                            contentDescription = "Add ${entity.title}",
                            onClick = { onAdd(entity) },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun MixBuilderSelectedPanel(
    mode: MixBuilderMode,
    selectedArtists: List<Artist>,
    selectedAlbums: List<Album>,
    catalogIndexes: MixBuilderCatalogIndexes,
    estimatedTrackCount: Int,
    buildingQueue: Boolean,
    onRemove: (String) -> Unit,
    onBuild: () -> Unit,
    modifier: Modifier = Modifier,
    boundedHeight: Boolean = false,
) {
    val selectedEntities = when (mode) {
        MixBuilderMode.Artists -> selectedArtists.map(MixBuilderEntity::ArtistItem)
        MixBuilderMode.Albums -> selectedAlbums.map(MixBuilderEntity::AlbumItem)
    }
    MixBuilderPanel(modifier) {
        Column(
            if (boundedHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionLabel(mode.selectedTitle, PhoebeUi.primaryText)
                    Text(
                        selectedSummary(selectedEntities.size, estimatedTrackCount, mode),
                        color = PhoebeUi.mutedText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MixBuilderBuildButton(
                    label = mode.buildLabel,
                    enabled = selectedEntities.isNotEmpty() && !buildingQueue,
                    onClick = onBuild,
                )
            }
            if (selectedEntities.isEmpty()) {
                MixBuilderEmptyState(
                    mode.emptySelectedMessage,
                    if (boundedHeight) Modifier.weight(1f, fill = false) else Modifier,
                )
            } else {
                val selectedListModifier = if (boundedHeight) {
                    Modifier.fillMaxWidth().weight(1f, fill = false)
                } else {
                    Modifier.fillMaxWidth()
                }
                val selectedListScrollModifier = if (boundedHeight) {
                    selectedListModifier.verticalScroll(rememberScrollState())
                } else {
                    selectedListModifier
                }
                Column(
                    selectedListScrollModifier,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    selectedEntities.forEach { entity ->
                        MixBuilderEntityRow(
                            entity = entity,
                            subtitle = entity.subtitle(catalogIndexes),
                            fallbackThumbUrl = entity.fallbackThumbUrl(catalogIndexes),
                            trailing = {
                                MixBuilderIconButton(
                                    icon = PhoebeIcon.Close,
                                    contentDescription = "Remove ${entity.title}",
                                    onClick = { onRemove(entity.id) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MixBuilderPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PhoebeUi.panel)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp))
            .padding(14.dp),
    ) {
        content()
    }
}

@Composable
private fun MixBuilderEntityRow(
    entity: MixBuilderEntity,
    subtitle: String,
    fallbackThumbUrl: String?,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.035f))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        ArtworkImage(
            seed = entity.title,
            thumbUrl = entity.thumbUrl,
            modifier = Modifier.size(46.dp).aspectRatio(1f),
            radius = if (entity is MixBuilderEntity.ArtistItem) 999.dp else 8.dp,
            fallbackThumbUrl = fallbackThumbUrl,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entity.title, color = PhoebeUi.primaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = PhoebeUi.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun MixBuilderIconButton(
    icon: PhoebeIcon,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .background(if (enabled) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.025f))
            .border(BorderStroke(1.dp, if (enabled) PhoebeUi.border else PhoebeUi.border.copy(alpha = 0.45f)), CircleShape)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        PhoebeIconView(
            icon,
            tint = if (enabled) PhoebeUi.primaryText else PhoebeUi.mutedText.copy(alpha = 0.55f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun MixBuilderBuildButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (enabled) {
                    Brush.linearGradient(listOf(PhoebeUi.accentLight, PhoebeUi.accent))
                } else {
                    Brush.linearGradient(listOf(PhoebeUi.mutedText.copy(alpha = 0.24f), PhoebeUi.mutedText.copy(alpha = 0.18f)))
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        PhoebeIconView(PhoebeIcon.PlaylistPlay, tint = PhoebeUi.primaryText, modifier = Modifier.size(15.dp))
        Text(label, color = PhoebeUi.primaryText, fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MixBuilderEmptyState(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        color = PhoebeUi.mutedText,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.035f))
            .padding(14.dp),
    )
}

private sealed interface MixBuilderEntity {
    val id: String
    val title: String
    val thumbUrl: String?

    data class ArtistItem(val artist: Artist) : MixBuilderEntity {
        override val id: String = artist.id
        override val title: String = artist.title
        override val thumbUrl: String? = artist.thumbUrl
    }

    data class AlbumItem(val album: Album) : MixBuilderEntity {
        override val id: String = album.id
        override val title: String = album.title
        override val thumbUrl: String? = album.thumbUrl
    }
}

private fun MixBuilderEntity.subtitle(catalogIndexes: MixBuilderCatalogIndexes): String =
    when (this) {
        is MixBuilderEntity.ArtistItem -> {
            val count = catalogIndexes.artistTrackIndex.tracksForArtist(artist).size.takeIf { it > 0 } ?: artist.songCount
            val word = if (count == 1) "song" else "songs"
            "$count $word"
        }
        is MixBuilderEntity.AlbumItem -> {
            val count = catalogIndexes.albumTrackIndex.tracksForAlbum(album).size
            val word = if (count == 1) "track" else "tracks"
            "${album.artist} - $count $word"
        }
    }

private fun MixBuilderEntity.fallbackThumbUrl(catalogIndexes: MixBuilderCatalogIndexes): String? =
    when (this) {
        is MixBuilderEntity.ArtistItem -> catalogIndexes.fallbackThumbUrlForArtist(artist)
        is MixBuilderEntity.AlbumItem -> null
    }

private fun selectedSummary(count: Int, trackCount: Int, mode: MixBuilderMode): String {
    val itemWord = when (mode) {
        MixBuilderMode.Artists -> if (count == 1) "artist" else "artists"
        MixBuilderMode.Albums -> if (count == 1) "album" else "albums"
    }
    val trackWord = if (trackCount == 1) "track" else "tracks"
    return "$count $itemWord - $trackCount $trackWord"
}

private class MixBuilderCatalogIndexes(
    val artistTrackIndex: MixBuilderArtistTrackIndex,
    val albumTrackIndex: MixBuilderAlbumTrackIndex,
    private val artistFallbackThumbUrls: Map<String, String>,
) {
    fun fallbackThumbUrlForArtist(artist: Artist): String? =
        artistFallbackThumbUrls[artist.title.normalizedMixBuilderKey()]

    companion object {
        val Empty = MixBuilderCatalogIndexes(
            artistTrackIndex = MixBuilderArtistTrackIndex.Empty,
            albumTrackIndex = MixBuilderAlbumTrackIndex.Empty,
            artistFallbackThumbUrls = emptyMap(),
        )
    }
}

private fun CatalogSnapshot.mixBuilderCatalogIndexes(): MixBuilderCatalogIndexes =
    MixBuilderCatalogIndexes(
        artistTrackIndex = mixBuilderArtistTrackIndex(),
        albumTrackIndex = mixBuilderAlbumTrackIndex(),
        artistFallbackThumbUrls = albums.mixBuilderArtistFallbackThumbUrls(),
    )

private fun List<Album>.mixBuilderArtistFallbackThumbUrls(): Map<String, String> {
    val urls = mutableMapOf<String, String>()
    forEach { album ->
        val thumbUrl = album.thumbUrl?.takeIf { it.isNotBlank() } ?: return@forEach
        urls.getOrPut(album.artist.normalizedMixBuilderKey()) { thumbUrl }
    }
    return urls
}

private data class MixBuilderArtistTrackIndex(
    val tracksByArtistKey: Map<String, List<Track>>,
    val tagsByArtistKey: Map<String, Set<String>>,
    val maxRatingByArtistKey: Map<String, Float>,
) {
    fun tracksForArtist(artist: Artist): List<Track> =
        tracksByArtistKey[artist.title.normalizedMixBuilderKey()].orEmpty()

    fun tagsForArtist(artist: Artist): Set<String> =
        tagsByArtistKey[artist.title.normalizedMixBuilderKey()].orEmpty()

    fun maxRatingForArtist(artist: Artist): Float? =
        maxRatingByArtistKey[artist.title.normalizedMixBuilderKey()]

    fun isNotEmpty(): Boolean =
        tracksByArtistKey.isNotEmpty()

    companion object {
        val Empty = MixBuilderArtistTrackIndex(
            tracksByArtistKey = emptyMap(),
            tagsByArtistKey = emptyMap(),
            maxRatingByArtistKey = emptyMap(),
        )
    }
}

private fun CatalogSnapshot.mixBuilderArtistTrackIndex(): MixBuilderArtistTrackIndex {
    val mutableTracksByArtistKey = mutableMapOf<String, MutableList<Track>>()
    tracksByParent.values
        .asSequence()
        .flatten()
        .filter { it.hasPlayableSource() }
        .forEach { track ->
            buildList {
                add(track.artist.normalizedMixBuilderKey())
                track.albumArtist?.normalizedMixBuilderKey()?.let(::add)
            }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { artistKey ->
                    mutableTracksByArtistKey.getOrPut(artistKey) { mutableListOf() } += track
                }
        }
    val tracksByArtistKey = mutableTracksByArtistKey.mapValues { (_, tracks) -> tracks.toList() }
    return MixBuilderArtistTrackIndex(
        tracksByArtistKey = tracksByArtistKey,
        tagsByArtistKey = tracksByArtistKey.mapValues { (_, tracks) -> tracks.mixBuilderTagTokens() },
        maxRatingByArtistKey = tracksByArtistKey.mapValues { (_, tracks) ->
            tracks.maxOfOrNull { track -> track.rating ?: 0f } ?: 0f
        },
    )
}

private data class MixBuilderAlbumTrackIndex(
    val tracksByAlbumId: Map<String, List<Track>>,
    val fallbackTracksByAlbumKey: Map<MixBuilderAlbumKey, List<Track>>,
) {
    fun tracksForAlbum(album: Album): List<Track> =
        tracksByAlbumId[album.id]
            ?.takeIf { it.isNotEmpty() }
            ?: fallbackTracksByAlbumKey[MixBuilderAlbumKey.from(album)]
            .orEmpty()

    fun isNotEmpty(): Boolean =
        tracksByAlbumId.isNotEmpty() || fallbackTracksByAlbumKey.isNotEmpty()

    companion object {
        val Empty = MixBuilderAlbumTrackIndex(
            tracksByAlbumId = emptyMap(),
            fallbackTracksByAlbumKey = emptyMap(),
        )
    }
}

private data class MixBuilderAlbumKey(
    val title: String,
    val artist: String,
) {
    companion object {
        fun from(album: Album): MixBuilderAlbumKey =
            MixBuilderAlbumKey(
                title = album.title.normalizedMixBuilderKey(),
                artist = album.artist.normalizedMixBuilderKey(),
            )

        fun from(track: Track, artist: String): MixBuilderAlbumKey =
            MixBuilderAlbumKey(
                title = track.album.normalizedMixBuilderKey(),
                artist = artist.normalizedMixBuilderKey(),
            )
    }
}

private fun CatalogSnapshot.mixBuilderAlbumTrackIndex(): MixBuilderAlbumTrackIndex {
    val tracksByAlbumId = tracksByParent.mapValues { (_, tracks) ->
        tracks.filter { it.hasPlayableSource() }.sortedForMixBuilderAlbum()
    }
    val mutableFallbackTracksByAlbumKey = mutableMapOf<MixBuilderAlbumKey, MutableList<Track>>()
    tracksByParent.values
        .asSequence()
        .flatten()
        .filter { it.hasPlayableSource() }
        .forEach { track ->
            buildList {
                add(track.artist)
                track.albumArtist?.let(::add)
            }
                .filter { it.isNotBlank() }
                .distinctBy { it.normalizedMixBuilderKey() }
                .forEach { artist ->
                    mutableFallbackTracksByAlbumKey.getOrPut(MixBuilderAlbumKey.from(track, artist)) { mutableListOf() } += track
                }
        }
    return MixBuilderAlbumTrackIndex(
        tracksByAlbumId = tracksByAlbumId,
        fallbackTracksByAlbumKey = mutableFallbackTracksByAlbumKey.mapValues { (_, tracks) ->
            tracks.sortedForMixBuilderAlbum()
        },
    )
}

internal fun artistMixBuilderQueue(
    catalog: CatalogSnapshot,
    artists: List<Artist>,
    random: Random = Random.Default,
): List<Track> =
    artistMixBuilderQueue(catalog, artists, catalog.mixBuilderArtistTrackIndex(), random)

private fun artistMixBuilderQueue(
    catalog: CatalogSnapshot,
    artists: List<Artist>,
    artistTrackIndex: MixBuilderArtistTrackIndex,
    random: Random = Random.Default,
): List<Track> {
    val leading = mutableListOf<Track>()
    val rest = mutableListOf<Track>()
    artists.forEach { artist ->
        val artistTracks = artistTrackIndex.tracksForArtist(artist)
        if (artistTracks.isEmpty()) return@forEach
        val popular = catalog.popularTracksByArtist[artist.id]
            .orEmpty()
            .filter { track -> track.hasPlayableSource() }
        val topTracks = popular.takeIf { it.isNotEmpty() } ?: artistTracks.shuffled(random)
        leading += topTracks
        val topTrackIds = topTracks.mapTo(mutableSetOf()) { it.id }
        rest += artistTracks.filterNot { it.id in topTrackIds }
    }
    return leading.shuffled(random) + rest.shuffled(random)
}

internal fun albumMixBuilderQueue(
    catalog: CatalogSnapshot,
    albums: List<Album>,
    random: Random = Random.Default,
): List<Track> =
    albumMixBuilderQueue(catalog, albums, catalog.mixBuilderAlbumTrackIndex(), random)

private fun albumMixBuilderQueue(
    catalog: CatalogSnapshot,
    albums: List<Album>,
    albumTrackIndex: MixBuilderAlbumTrackIndex,
    random: Random = Random.Default,
): List<Track> {
    val leading = mutableListOf<Track>()
    val rest = mutableListOf<Track>()
    albums.forEach { album ->
        val albumTracks = albumTrackIndex.tracksForAlbum(album)
        if (albumTracks.isEmpty()) return@forEach
        val artist = catalog.artists.firstOrNull { it.title.equals(album.artist, ignoreCase = true) }
        val popular = artist
            ?.let { catalog.popularTracksByArtist[it.id].orEmpty() }
            .orEmpty()
            .filter { track -> track.hasPlayableSource() && track.matchesAlbum(album) }
        val topTracks = popular.takeIf { it.isNotEmpty() } ?: albumTracks.shuffled(random)
        leading += topTracks
        val topTrackIds = topTracks.mapTo(mutableSetOf()) { it.id }
        rest += albumTracks.filterNot { it.id in topTrackIds }
    }
    return leading.shuffled(random) + rest.shuffled(random)
}

internal fun artistMixBuilderSuggestions(
    catalog: CatalogSnapshot,
    selectedArtists: List<Artist>,
    limit: Int = MixBuilderSuggestionLimit,
): List<Artist> =
    artistMixBuilderSuggestions(catalog, selectedArtists, catalog.mixBuilderArtistTrackIndex(), limit)

private fun artistMixBuilderSuggestions(
    catalog: CatalogSnapshot,
    selectedArtists: List<Artist>,
    artistTrackIndex: MixBuilderArtistTrackIndex,
    limit: Int = MixBuilderSuggestionLimit,
): List<Artist> {
    if (selectedArtists.isEmpty()) return emptyList()
    val selectedIds = selectedArtists.mapTo(mutableSetOf()) { it.id }
    val providerSuggestions = selectedArtists
        .asSequence()
        .flatMap { artist -> catalog.similarArtistsByArtist[artist.id].orEmpty().asSequence() }
        .filterNot { it.id in selectedIds }
        .distinctBy { it.id }
        .take(limit)
        .toList()
    if (providerSuggestions.isNotEmpty()) return providerSuggestions

    return fallbackArtistMixBuilderSuggestions(catalog, selectedArtists, selectedIds, artistTrackIndex, limit)
}

internal fun albumMixBuilderSuggestions(
    catalog: CatalogSnapshot,
    selectedAlbums: List<Album>,
    limit: Int = MixBuilderSuggestionLimit,
): List<Album> {
    if (selectedAlbums.isEmpty()) return emptyList()
    val selectedIds = selectedAlbums.mapTo(mutableSetOf()) { it.id }
    val selectedArtistTitles = selectedAlbums.mapTo(mutableSetOf()) { it.artist.normalizedMixBuilderKey() }
    val relatedArtistTitles = catalog.artists
        .asSequence()
        .filter { artist -> artist.title.normalizedMixBuilderKey() in selectedArtistTitles }
        .flatMap { artist -> catalog.similarArtistsByArtist[artist.id].orEmpty().asSequence() }
        .mapTo(mutableSetOf()) { it.title.normalizedMixBuilderKey() }
    if (relatedArtistTitles.isEmpty()) return emptyList()
    return catalog.albums
        .asSequence()
        .filterNot { it.id in selectedIds }
        .filter { it.artist.normalizedMixBuilderKey() in relatedArtistTitles }
        .distinctBy { it.id }
        .take(limit)
        .toList()
}

internal fun mixBuilderTracksForArtist(catalog: CatalogSnapshot, artist: Artist): List<Track> =
    catalog.mixBuilderArtistTrackIndex().tracksForArtist(artist)

internal fun mixBuilderTracksForAlbum(catalog: CatalogSnapshot, album: Album): List<Track> =
    catalog.mixBuilderAlbumTrackIndex().tracksForAlbum(album)

private fun List<Track>.sortedForMixBuilderAlbum(): List<Track> =
    sortedWith(
        compareBy(
            { track -> track.discNumber ?: Int.MAX_VALUE },
            { track -> track.trackNumber ?: Int.MAX_VALUE },
            { track -> track.title.lowercase() },
        ),
    )

private fun Track.matchesAlbum(album: Album): Boolean =
    parentAlbumId == album.id ||
        album.title.normalizedMixBuilderKey() == this.album.normalizedMixBuilderKey() &&
        (
            artist.normalizedMixBuilderKey() == album.artist.normalizedMixBuilderKey() ||
                albumArtist?.normalizedMixBuilderKey() == album.artist.normalizedMixBuilderKey()
        )

private fun fallbackArtistMixBuilderSuggestions(
    catalog: CatalogSnapshot,
    selectedArtists: List<Artist>,
    selectedIds: Set<String>,
    artistTrackIndex: MixBuilderArtistTrackIndex,
    limit: Int,
): List<Artist> {
    val selectedTags = selectedArtists.flatMapTo(mutableSetOf()) { artist ->
        artistTrackIndex.tagsForArtist(artist)
    }
    val candidateScores = catalog.artists
        .asSequence()
        .filterNot { it.id in selectedIds }
        .mapNotNull { artist ->
            val tracks = artistTrackIndex.tracksForArtist(artist)
            if (tracks.isEmpty()) return@mapNotNull null
            val sharedTags = artistTrackIndex.tagsForArtist(artist).count { it in selectedTags }
            val popularity = artist.rating ?: artistTrackIndex.maxRatingForArtist(artist) ?: 0f
            val score = sharedTags * 1000 + (popularity * 100).toInt() + tracks.size
            artist to score
        }
        .sortedWith(
            compareByDescending<Pair<Artist, Int>> { it.second }
                .thenByDescending { it.first.rating ?: 0f }
                .thenBy { it.first.title.lowercase() },
        )
        .toList()
    val meaningful = candidateScores.filter { it.second > 0 }
    return (meaningful.takeIf { it.isNotEmpty() } ?: candidateScores)
        .take(limit)
        .map { it.first }
}

private fun List<Track>.mixBuilderTagTokens(): Set<String> =
    flatMapTo(mutableSetOf()) { track ->
        buildList {
            addAll(track.genre.mixBuilderTags())
            addAll(track.mood.mixBuilderTags())
            addAll(track.style.mixBuilderTags())
        }
    }

private fun String?.mixBuilderTags(): List<String> =
    orEmpty()
        .split(',', ';', '/', '|')
        .map { it.normalizedMixBuilderKey() }
        .filter { it.isNotBlank() }

private fun String.normalizedMixBuilderKey(): String =
    trim().lowercase()

private const val MixBuilderResultLimit = 12
private const val MixBuilderSuggestionLimit = 8
