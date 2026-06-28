package com.phoebe.app.feature.collections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.CollectionEntry
import com.phoebe.app.domain.CollectionFacet
import com.phoebe.app.domain.CollectionTarget
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.ui.ArtworkImage
import com.phoebe.app.ui.DetailBackButton
import com.phoebe.app.ui.PhoebeIcon
import com.phoebe.app.ui.PhoebeIconView
import com.phoebe.app.ui.PhoebeUi
import com.phoebe.app.ui.mobileContentTopPadding

@Composable
fun CollectionsScreen(
    entry: CollectionEntry,
    catalog: CatalogSnapshot,
    modifier: Modifier = Modifier,
    searchQuery: String = "",
    supportedCollectionEntries: Set<CollectionEntry> = allCollectionEntries().toSet(),
    bottomContentPadding: Dp = 0.dp,
    onBack: () -> Unit,
    onCollectionValue: (CollectionEntry, String) -> Unit,
    onEnsureValuesLoaded: () -> Unit = {},
) {
    LaunchedEffect(entry) {
        onEnsureValuesLoaded()
    }
    val index = remember(catalog, supportedCollectionEntries) { CollectionIndex.from(catalog, supportedCollectionEntries) }
    val buckets = remember(index, entry) { index.bucketsFor(entry) }
    val loading = remember(catalog.collectionValues, catalog.collectionValueLoads, buckets, entry) {
        buckets.isEmpty() && !catalog.collectionValuesFetchSettled(entry)
    }
    var sortBy by rememberSaveable(entry.target.name, entry.facet.name) { mutableStateOf(LibrarySortBy.Name) }
    var ascending by rememberSaveable(entry.target.name, entry.facet.name) { mutableStateOf(true) }
    val visibleBuckets = remember(buckets, sortBy, ascending, searchQuery) {
        sortCollectionBuckets(
            filterCollectionBucketsByQuery(buckets, searchQuery),
            sortBy,
            ascending,
        )
    }
    val listState = rememberSaveable(
        entry.target.name,
        entry.facet.name,
        saver = LazyListState.Saver,
    ) { LazyListState() }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        contentPadding = PaddingValues(top = mobileContentTopPadding(24.dp), bottom = 24.dp + bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "collections-header", contentType = "collections-header") {
            CollectionsHeader(
                label = "COLLECTIONS",
                title = entry.title,
                subtitle = if (buckets.any { it.items.isNotEmpty() }) {
                    "${buckets.sumOf { it.items.size }} ${entry.target.itemPlural.lowercase()} across ${buckets.size} ${entry.facet.plural.lowercase()}"
                } else if (loading) {
                    "Loading ${entry.facet.plural.lowercase()}…"
                } else {
                    "${buckets.size} ${entry.facet.plural.lowercase()}"
                },
                onBack = onBack,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        item(key = "collections-sort", contentType = "collections-sort") {
            CollectionSortHeader(
                title = entry.facet.plural,
                sortBy = sortBy,
                sortKeys = listOf(LibrarySortBy.Name, LibrarySortBy.DateAdded),
                sortLabel = { key -> if (key == LibrarySortBy.DateAdded) "Item count" else "${entry.facet.singular} name" },
                onSortBy = { sortBy = it },
                ascending = ascending,
                onAscending = { ascending = it },
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        when {
            loading -> item(key = "collections-loading", contentType = "collections-loading") {
                CollectionLoadingIndicator(
                    message = "Loading ${entry.facet.plural.lowercase()}…",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                )
            }
            visibleBuckets.isEmpty() -> item(key = "collections-empty", contentType = "collections-empty") {
                val query = searchQuery.trim()
                val message = if (query.isNotBlank()) {
                    "No ${entry.facet.plural.lowercase()} match \"$query\"."
                } else {
                    "No ${entry.facet.singular.lowercase()} tags are available for ${entry.target.itemPlural.lowercase()} yet."
                }
                CollectionEmpty(
                    message = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                )
            }
            else -> items(
                items = visibleBuckets,
                key = { it.label },
                contentType = { "collection-value" },
            ) { bucket ->
                CollectionValueRow(entry, bucket) {
                    onCollectionValue(entry, bucket.label)
                }
            }
        }
    }
}

@Composable
fun CollectionItemsScreen(
    entry: CollectionEntry,
    value: String,
    catalog: CatalogSnapshot,
    modifier: Modifier = Modifier,
    searchQuery: String = "",
    supportedCollectionEntries: Set<CollectionEntry> = allCollectionEntries().toSet(),
    bottomContentPadding: Dp = 0.dp,
    onBack: () -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onEnsureItemsLoaded: () -> Unit = {},
) {
    LaunchedEffect(entry, value) {
        onEnsureItemsLoaded()
    }
    val index = remember(catalog, supportedCollectionEntries) { CollectionIndex.from(catalog, supportedCollectionEntries) }
    val bucket = remember(index, entry, value) {
        index.bucketsFor(entry).firstOrNull { it.label.equals(value, ignoreCase = true) }
    }
    val items = bucket?.items.orEmpty()
    val collectionValue = remember(catalog.collectionValues, entry, value) {
        catalog.collectionValues.firstOrNull {
            it.target == entry.target.name &&
                it.facet == entry.facet.name &&
                it.value.equals(value, ignoreCase = true)
        }
    }
    val loading = remember(catalog.collectionValues, catalog.collectionValueLoads, collectionValue, items, entry, value) {
        when {
            items.isNotEmpty() -> false
            collectionValue?.itemsLoaded == true -> false
            collectionValue != null -> true
            !catalog.collectionValuesFetchSettled(entry) -> true
            else -> false
        }
    }
    var sortBy by rememberSaveable(entry.target.name, entry.facet.name, value) {
        mutableStateOf(if (entry.target == CollectionTarget.Albums) LibrarySortBy.Year else LibrarySortBy.Name)
    }
    var ascending by rememberSaveable(entry.target.name, entry.facet.name, value) { mutableStateOf(true) }
    val visibleItems = remember(items, entry, sortBy, ascending, searchQuery) {
        sortCollectionItems(
            filterCollectionItemsByQuery(items, searchQuery),
            entry.target,
            sortBy,
            ascending,
        )
    }
    val listState = rememberSaveable(
        entry.target.name,
        entry.facet.name,
        value,
        saver = LazyListState.Saver,
    ) { LazyListState() }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        contentPadding = PaddingValues(top = mobileContentTopPadding(24.dp), bottom = 24.dp + bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "collection-items-header", contentType = "collection-items-header") {
            CollectionsHeader(
                label = entry.title,
                title = value,
                subtitle = if (loading) {
                    "Loading ${entry.target.itemPlural.lowercase()}…"
                } else {
                    "${items.size} ${if (items.size == 1) entry.target.itemSingular.lowercase() else entry.target.itemPlural.lowercase()}"
                },
                onBack = onBack,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        item(key = "collection-items-sort", contentType = "collection-items-sort") {
            CollectionSortHeader(
                title = entry.target.itemPlural,
                sortBy = sortBy,
                sortKeys = collectionItemSortKeys(entry.target),
                sortLabel = { key -> collectionItemSortLabel(entry.target, key) },
                onSortBy = { sortBy = it },
                ascending = ascending,
                onAscending = { ascending = it },
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        when {
            loading -> item(key = "collection-items-loading", contentType = "collection-items-loading") {
                CollectionLoadingIndicator(
                    message = "Loading ${entry.target.itemPlural.lowercase()}…",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                )
            }
            visibleItems.isEmpty() -> item(key = "collection-items-empty", contentType = "collection-items-empty") {
                val query = searchQuery.trim()
                val message = if (query.isNotBlank()) {
                    "No ${entry.target.itemPlural.lowercase()} in this ${entry.facet.singular.lowercase()} match \"$query\"."
                } else {
                    "Nothing is in this ${entry.facet.singular.lowercase()} yet."
                }
                CollectionEmpty(
                    message = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                )
            }
            else -> items(
                items = visibleItems,
                key = { it.id },
                contentType = { "collection-item" },
            ) { item ->
                when (entry.target) {
                    CollectionTarget.Artists -> {
                        val artist = item.artist ?: return@items
                        CollectionItemRow(
                            title = artist.title,
                            subtitle = item.subtitle,
                            seed = artist.title,
                            thumbUrl = item.thumbUrl,
                            artworkRadius = 999.dp,
                            onClick = { onArtist(artist) },
                        )
                    }
                    CollectionTarget.Albums -> {
                        val album = item.album ?: return@items
                        CollectionItemRow(
                            title = album.title,
                            subtitle = item.subtitle,
                            seed = album.title,
                            thumbUrl = item.thumbUrl,
                            artworkRadius = 10.dp,
                            onClick = { onAlbum(album) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionsHeader(
    label: String,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DetailBackButton(onBack = onBack)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                label.uppercase(),
                color = PhoebeUi.mutedText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.em,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                title,
                color = PhoebeUi.primaryText,
                fontSize = 28.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, color = PhoebeUi.secondaryText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CollectionSortHeader(
    title: String,
    sortBy: LibrarySortBy,
    sortKeys: List<LibrarySortBy>,
    sortLabel: (LibrarySortBy) -> String,
    onSortBy: (LibrarySortBy) -> Unit,
    ascending: Boolean,
    onAscending: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            color = PhoebeUi.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        sortKeys.forEach { key ->
            CollectionSortChip(
                label = sortLabel(key),
                selected = key == sortBy,
                onClick = { onSortBy(key) },
            )
        }
        CollectionSortChip(
            label = if (ascending) "Asc" else "Desc",
            selected = true,
            onClick = { onAscending(!ascending) },
        )
    }
}

@Composable
private fun CollectionSortChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (selected) PhoebeUi.primaryText else PhoebeUi.secondaryText,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) PhoebeUi.accentLight.copy(alpha = 0.18f) else PhoebeUi.elevatedFill)
            .border(
                BorderStroke(1.dp, if (selected) PhoebeUi.accentLight.copy(alpha = 0.34f) else PhoebeUi.border),
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun CollectionLoadingIndicator(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = PhoebeUi.accentLight,
                strokeWidth = 2.dp,
                trackColor = PhoebeUi.progressTrack,
            )
            Text(message, color = PhoebeUi.secondaryText, fontSize = 14.sp)
        }
    }
}

@Composable
private fun CollectionEmpty(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(PhoebeUi.elevatedFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = PhoebeUi.secondaryText, fontSize = 14.sp)
    }
}

@Composable
private fun CollectionValueRow(entry: CollectionEntry, bucket: CollectionBucket, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CollectionIcon(entry, Modifier.size(32.dp))
        Text(
            bucket.label,
            color = PhoebeUi.primaryText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun CollectionItemRow(
    title: String,
    subtitle: String,
    seed: String,
    thumbUrl: String?,
    artworkRadius: Dp,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(PhoebeUi.elevatedFill)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CollectionArtworkTile(
            seed = seed,
            thumbUrl = thumbUrl,
            radius = artworkRadius,
            modifier = Modifier.size(46.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = PhoebeUi.primaryText,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun CollectionArtworkTile(seed: String, modifier: Modifier = Modifier) {
    CollectionArtworkTile(seed = seed, thumbUrl = null, radius = 10.dp, modifier = modifier)
}

@Composable
private fun CollectionArtworkTile(
    seed: String,
    thumbUrl: String?,
    radius: Dp,
    modifier: Modifier = Modifier,
) {
    if (!thumbUrl.isNullOrBlank()) {
        ArtworkImage(
            seed = seed,
            thumbUrl = thumbUrl,
            modifier = modifier,
            radius = radius,
            elevated = false,
        )
        return
    }
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(radius)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = seed.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "M",
            color = PhoebeUi.accentLight,
            fontWeight = FontWeight.Black,
            fontSize = 18.sp,
        )
    }
}

@Composable
private fun CollectionIcon(entry: CollectionEntry, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PhoebeUi.accentLight.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, PhoebeUi.accentLight.copy(alpha = 0.20f)), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val icon = when (entry.facet) {
            CollectionFacet.Mood -> PhoebeIcon.MoodFace
            CollectionFacet.Style -> PhoebeIcon.SunglassesFace
            CollectionFacet.Genre -> PhoebeIcon.GenreMasks
        }
        PhoebeIconView(icon, tint = PhoebeUi.accentLight, modifier = Modifier.size(17.dp))
    }
}

private data class CollectionIndex(
    private val bucketsByEntry: Map<CollectionEntry, List<CollectionBucket>>,
) {
    fun bucketsFor(entry: CollectionEntry): List<CollectionBucket> = bucketsByEntry[entry].orEmpty()

    companion object {
        fun from(
            catalog: CatalogSnapshot,
            supportedCollectionEntries: Set<CollectionEntry> = allCollectionEntries().toSet(),
        ): CollectionIndex {
            val albumThumbByArtist = catalog.albums
                .asSequence()
                .filter { it.thumbUrl != null }
                .groupBy { it.artist.lowercase() }
                .mapValues { (_, albums) -> albums.firstNotNullOfOrNull { it.thumbUrl } }
            val artistGenre = CollectionEntry(CollectionTarget.Artists, CollectionFacet.Genre)
            val artistMood = CollectionEntry(CollectionTarget.Artists, CollectionFacet.Mood)
            val artistStyle = CollectionEntry(CollectionTarget.Artists, CollectionFacet.Style)
            val albumGenre = CollectionEntry(CollectionTarget.Albums, CollectionFacet.Genre)
            val albumMood = CollectionEntry(CollectionTarget.Albums, CollectionFacet.Mood)
            val albumStyle = CollectionEntry(CollectionTarget.Albums, CollectionFacet.Style)
            val bucketsByEntry = buildMap {
                if (artistGenre in supportedCollectionEntries) put(artistGenre, catalog.artistItems(artistGenre, albumThumbByArtist).toBuckets(catalog.collectionValueLabels(artistGenre)))
                if (albumGenre in supportedCollectionEntries) put(albumGenre, catalog.albumItems(albumGenre).toBuckets(catalog.collectionValueLabels(albumGenre)))
                if (artistMood in supportedCollectionEntries) put(artistMood, catalog.artistItems(artistMood, albumThumbByArtist).toBuckets(catalog.collectionValueLabels(artistMood)))
                if (albumMood in supportedCollectionEntries) put(albumMood, catalog.albumItems(albumMood).toBuckets(catalog.collectionValueLabels(albumMood)))
                if (artistStyle in supportedCollectionEntries) put(artistStyle, catalog.artistItems(artistStyle, albumThumbByArtist).toBuckets(catalog.collectionValueLabels(artistStyle)))
                if (albumStyle in supportedCollectionEntries) put(albumStyle, catalog.albumItems(albumStyle).toBuckets(catalog.collectionValueLabels(albumStyle)))
            }
            return CollectionIndex(bucketsByEntry = bucketsByEntry)
        }
    }
}

private fun allCollectionEntries(): List<CollectionEntry> =
    CollectionTarget.entries.flatMap { target ->
        CollectionFacet.entries.map { facet -> CollectionEntry(target, facet) }
    }

private data class CollectionBucket(
    val label: String,
    val items: List<CollectionItem>,
)

private data class CollectionItem(
    val id: String,
    val label: String,
    val subtitle: String,
    val thumbUrl: String?,
    val artist: Artist? = null,
    val album: Album? = null,
)

private val CollectionEntry.title: String
    get() = "${target.itemSingular} ${facet.singular}"

private val CollectionTarget.itemSingular: String
    get() = when (this) {
        CollectionTarget.Artists -> "Artist"
        CollectionTarget.Albums -> "Album"
    }

private val CollectionTarget.itemPlural: String
    get() = when (this) {
        CollectionTarget.Artists -> "Artists"
        CollectionTarget.Albums -> "Albums"
    }

private val CollectionFacet.singular: String
    get() = when (this) {
        CollectionFacet.Mood -> "Mood"
        CollectionFacet.Style -> "Style"
        CollectionFacet.Genre -> "Genre"
    }

private val CollectionFacet.plural: String
    get() = when (this) {
        CollectionFacet.Mood -> "Moods"
        CollectionFacet.Style -> "Styles"
        CollectionFacet.Genre -> "Genres"
    }

private fun List<CollectionItem>.toBuckets(valueLabels: List<String>): List<CollectionBucket> {
    val bucketsByLabel = groupBy { it.label }
        .map { (label, items) ->
            CollectionBucket(
                label = label,
                items = items.sortedBy { item ->
                    (item.artist?.title ?: item.album?.title).orEmpty().lowercase()
                },
            )
        }
        .associateBy { it.label.lowercase() }
    val unloadedBuckets = valueLabels
        .filterNot { it.lowercase() in bucketsByLabel }
        .map { label -> CollectionBucket(label = label, items = emptyList()) }
    return (bucketsByLabel.values + unloadedBuckets)
        .sortedWith(compareByDescending<CollectionBucket> { it.items.size }.thenBy { it.label.lowercase() })
}

private fun filterCollectionBucketsByQuery(buckets: List<CollectionBucket>, query: String): List<CollectionBucket> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return buckets
    return buckets.filter { it.label.contains(trimmed, ignoreCase = true) }
}

private fun filterCollectionItemsByQuery(items: List<CollectionItem>, query: String): List<CollectionItem> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return items
    return items.filter { item ->
        item.artist?.title?.contains(trimmed, ignoreCase = true) == true ||
            item.album?.title?.contains(trimmed, ignoreCase = true) == true ||
            item.album?.artist?.contains(trimmed, ignoreCase = true) == true ||
            item.subtitle.contains(trimmed, ignoreCase = true)
    }
}

private fun sortCollectionBuckets(
    buckets: List<CollectionBucket>,
    sortBy: LibrarySortBy,
    ascending: Boolean,
): List<CollectionBucket> =
    when (sortBy) {
        LibrarySortBy.DateAdded -> buckets.sortedWith(
            if (ascending) compareBy<CollectionBucket>({ it.items.size }, { it.label.lowercase() })
            else compareByDescending<CollectionBucket> { it.items.size }.thenBy { it.label.lowercase() },
        )
        else -> buckets.sortedWith(
            if (ascending) compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }
            else compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.label },
        )
    }

private fun collectionItemSortKeys(target: CollectionTarget): List<LibrarySortBy> =
    when (target) {
        CollectionTarget.Artists -> listOf(LibrarySortBy.Name, LibrarySortBy.DateAdded)
        CollectionTarget.Albums -> listOf(LibrarySortBy.Year, LibrarySortBy.Name, LibrarySortBy.Artist, LibrarySortBy.DateAdded)
    }

private fun collectionItemSortLabel(target: CollectionTarget, sortBy: LibrarySortBy): String =
    when (target) {
        CollectionTarget.Artists -> if (sortBy == LibrarySortBy.DateAdded) "Date added" else "Artist name"
        CollectionTarget.Albums -> when (sortBy) {
            LibrarySortBy.Artist -> "Artist"
            LibrarySortBy.Year -> "Release date"
            LibrarySortBy.DateAdded -> "Date added"
            else -> "Album name"
        }
    }

private fun sortCollectionItems(
    items: List<CollectionItem>,
    target: CollectionTarget,
    sortBy: LibrarySortBy,
    ascending: Boolean,
): List<CollectionItem> =
    when (target) {
        CollectionTarget.Artists -> {
            val artists = items.mapNotNull { it.artist }.associateBy { it.id }
            val sorted = sortCollectionArtists(items.mapNotNull { it.artist }, sortBy, ascending)
            sorted.mapNotNull { artist -> items.firstOrNull { it.artist?.id == artist.id && artists.containsKey(artist.id) } }
        }
        CollectionTarget.Albums -> {
            val sorted = sortCollectionAlbums(items.mapNotNull { it.album }, sortBy, ascending)
            sorted.mapNotNull { album -> items.firstOrNull { it.album?.id == album.id } }
        }
    }

private fun sortCollectionArtists(
    artists: List<Artist>,
    sortBy: LibrarySortBy,
    ascending: Boolean,
): List<Artist> {
    if (sortBy == LibrarySortBy.DateAdded) {
        return artists.sortedWith(
            if (ascending) compareBy<Artist>({ it.dateAddedMs ?: Long.MAX_VALUE }, { it.title.lowercase() })
            else compareByDescending<Artist> { it.dateAddedMs ?: Long.MIN_VALUE }.thenBy { it.title.lowercase() },
        )
    }
    return artists.sortedWith(
        if (ascending) compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        else compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title },
    )
}

private fun sortCollectionAlbums(
    albums: List<Album>,
    sortBy: LibrarySortBy,
    ascending: Boolean,
): List<Album> =
    when (sortBy) {
        LibrarySortBy.Artist -> albums.sortedWith(
            if (ascending) compareBy<Album>({ it.artist.lowercase() }, { it.title.lowercase() })
            else compareByDescending<Album> { it.artist.lowercase() }.thenBy { it.title.lowercase() },
        )
        LibrarySortBy.Year -> {
            val (known, unknown) = albums.partition { it.year != null }
            val sortedKnown = known.sortedWith(
                if (ascending) compareBy<Album>({ it.year ?: Int.MAX_VALUE }, { it.title.lowercase() })
                else compareByDescending<Album> { it.year ?: Int.MIN_VALUE }.thenBy { it.title.lowercase() },
            )
            sortedKnown + unknown.sortedBy { it.title.lowercase() }
        }
        LibrarySortBy.DateAdded -> albums.sortedWith(
            if (ascending) compareBy<Album>({ it.dateAddedMs ?: Long.MAX_VALUE }, { it.title.lowercase() })
            else compareByDescending<Album> { it.dateAddedMs ?: Long.MIN_VALUE }.thenBy { it.title.lowercase() },
        )
        else -> albums.sortedWith(
            if (ascending) compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            else compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
    }

private fun CatalogSnapshot.collectionValueLabels(entry: CollectionEntry): List<String> =
    collectionValues
        .asSequence()
        .filter { it.target == entry.target.name && it.facet == entry.facet.name }
        .mapNotNull { it.value.cleanCollectionLabel() }
        .distinct()
        .toList()

private fun CatalogSnapshot.collectionValuesFetchSettled(entry: CollectionEntry): Boolean =
    collectionValues.any { it.target == entry.target.name && it.facet == entry.facet.name } ||
        collectionValueLoads.any { it.target == entry.target.name && it.facet == entry.facet.name }

private fun String.cleanCollectionLabel(): String? =
    trim()
        .takeIf { it.isNotBlank() }
        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private fun splitCollectionTagLabels(raw: String?): List<String> =
    raw?.trim()?.takeIf { it.isNotBlank() }?.let { value ->
        value.split(',', ';', '/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    } ?: emptyList()

private fun CatalogSnapshot.artistItems(
    entry: CollectionEntry,
    albumThumbByArtist: Map<String, String?>,
): List<CollectionItem> {
    val assigned = assignedTags(entry)
    val fallbackByArtistTitle = tagsByArtistTitle(entry.facet)
    return artists.flatMap { artist ->
        val labels = assigned[artist.id].orEmpty().ifEmpty {
            fallbackByArtistTitle[artist.title.trim().lowercase()].orEmpty()
        }.mapNotNull { it.cleanCollectionLabel() }.distinct()
        labels.map { label ->
            CollectionItem(
                id = "artist:${artist.id}:$label",
                label = label,
                subtitle = "${artist.albumCount} ${if (artist.albumCount == 1) "album" else "albums"}",
                thumbUrl = artist.thumbUrl ?: albumThumbByArtist[artist.title.lowercase()],
                artist = artist,
            )
        }
    }
}

private fun CatalogSnapshot.albumItems(entry: CollectionEntry): List<CollectionItem> {
    val assigned = assignedTags(entry)
    val fallbackByAlbumId = tagsByAlbumId(entry.facet)
    return albums.flatMap { album ->
        val labels = assigned[album.id].orEmpty().ifEmpty {
            fallbackByAlbumId[album.id].orEmpty()
        }.mapNotNull { it.cleanCollectionLabel() }.distinct()
        labels.map { label ->
            CollectionItem(
                id = "album:${album.id}:$label",
                label = label,
                subtitle = album.artist,
                thumbUrl = album.thumbUrl,
                album = album,
            )
        }
    }
}

private fun CatalogSnapshot.assignedTags(entry: CollectionEntry): Map<String, List<String>> =
    collectionTags
        .asSequence()
        .filter { it.target == entry.target.name && it.facet == entry.facet.name }
        .groupBy { it.itemId.toCanonicalCatalogId() }
        .mapValues { (_, tags) -> tags.map { it.value }.distinct() }

private fun String.toCanonicalCatalogId(): String =
    when {
        startsWith("plex:plex:") -> removePrefix("plex:")
        startsWith("plex:") -> this
        ":" in this -> this
        else -> "plex:$this"
    }

private fun CatalogSnapshot.tagsByArtistTitle(facet: CollectionFacet): Map<String, List<String>> {
    val tagsByKey = LinkedHashMap<String, LinkedHashSet<String>>()
    fun add(key: String, raw: String?) {
        if (key.isBlank()) return
        val bucket = tagsByKey.getOrPut(key) { LinkedHashSet() }
        splitCollectionTagLabels(raw).forEach { label ->
            label.cleanCollectionLabel()?.let(bucket::add)
        }
    }
    artists.forEach { artist ->
        add(artist.title.trim().lowercase(), artist.collectionTag(facet))
    }
    tracksByParent.values.asSequence()
        .flatten()
        .distinctBy { it.id }
        .forEach { track ->
            add(track.artist.trim().lowercase(), track.collectionTag(facet))
        }
    return tagsByKey.mapValues { (_, labels) -> labels.toList() }
}

private fun CatalogSnapshot.tagsByAlbumId(facet: CollectionFacet): Map<String, List<String>> {
    val tagsById = LinkedHashMap<String, LinkedHashSet<String>>()
    fun add(albumId: String, raw: String?) {
        if (albumId.isBlank()) return
        val bucket = tagsById.getOrPut(albumId) { LinkedHashSet() }
        splitCollectionTagLabels(raw).forEach { label ->
            label.cleanCollectionLabel()?.let(bucket::add)
        }
    }
    albums.forEach { album -> add(album.id, album.collectionTag(facet)) }
    tracksByParent.forEach { (albumId, tracks) ->
        tracks.asSequence()
            .distinctBy { it.id }
            .forEach { track -> add(albumId, track.collectionTag(facet)) }
    }
    return tagsById.mapValues { (_, labels) -> labels.toList() }
}

private fun Artist.collectionTag(facet: CollectionFacet): String? =
    when (facet) {
        CollectionFacet.Mood -> mood
        CollectionFacet.Style -> style
        CollectionFacet.Genre -> genre
    }?.trim()?.takeIf { it.isNotBlank() }

private fun Album.collectionTag(facet: CollectionFacet): String? =
    when (facet) {
        CollectionFacet.Mood -> mood
        CollectionFacet.Style -> style
        CollectionFacet.Genre -> genre
    }?.trim()?.takeIf { it.isNotBlank() }

private fun com.phoebe.app.domain.Track.collectionTag(facet: CollectionFacet): String? =
    when (facet) {
        CollectionFacet.Mood -> mood
        CollectionFacet.Style -> style
        CollectionFacet.Genre -> genre
    }?.trim()?.takeIf { it.isNotBlank() }
