package com.phoebe.app.feature.radio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.phoebe.app.domain.RadioCountry
import com.phoebe.app.domain.RadioDirectoryState
import com.phoebe.app.domain.RadioMapViewport
import com.phoebe.app.domain.RadioStation
import com.phoebe.app.domain.RadioStationSearchQuery
import com.phoebe.app.domain.RadioStationSource
import com.phoebe.app.feature.library.LibraryScrollIndexEntry
import com.phoebe.app.feature.library.LibraryScrollbarState
import com.phoebe.app.feature.library.LibrarySectionIndex
import com.phoebe.app.feature.library.LibrarySectionIndexMode
import com.phoebe.app.feature.library.rememberLibrarySectionIndexSelectionDispatcher
import com.phoebe.app.ui.ArtworkImage
import com.phoebe.app.ui.PhoebeIcon
import com.phoebe.app.ui.PhoebeIconView
import com.phoebe.app.ui.PhoebeUi
import com.phoebe.app.ui.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val RadioSearchDebounceMillis = 450L

enum class RadioRouteMode {
    Home,
    CountryIndex,
    CountryStations,
    Map,
}

@Immutable
data class RadioRouteState(
    val directory: RadioDirectoryState,
    val startingStationIds: Set<String> = emptySet(),
)

@Immutable
class RadioRouteActions(
    val onSearch: (RadioStationSearchQuery) -> Unit,
    val onLoadMore: () -> Unit,
    val onRefreshPopular: () -> Unit,
    val onPlay: (RadioStation) -> Unit,
    val onAddManualStation: (String, String) -> Unit,
    val onUpdateManualStation: (RadioStation, String, String) -> Unit,
    val onDeleteManualStation: (RadioStation) -> Unit,
    val onCountry: (RadioCountry) -> Unit = { country -> onSearch(RadioStationSearchQuery(countryCode = country.code)) },
    val onStation: (RadioStation) -> Unit = onPlay,
    val onClearCountry: () -> Unit = { onSearch(RadioStationSearchQuery()) },
    val onBrowseCountries: () -> Unit = {},
    val onBrowseGlobe: () -> Unit = {},
    val onGlobeSearch: (RadioStationSearchQuery, Int) -> Unit = { query, _ -> onSearch(query) },
    val onGlobeCountry: (String) -> Unit = { countryCode ->
        onGlobeSearch(RadioStationSearchQuery(countryCode = countryCode), 0)
    },
    val onGlobeViewport: (RadioMapViewport) -> Unit = {},
)

@OptIn(FlowPreview::class)
@Composable
fun RadioRoute(
    state: RadioRouteState,
    actions: RadioRouteActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 36.dp, vertical = 28.dp),
    sectionIndexMode: LibrarySectionIndexMode = LibrarySectionIndexMode.DesktopScrollbar,
    mode: RadioRouteMode = RadioRouteMode.Home,
    topBar: (@Composable () -> Unit)? = null,
) {
    var queryText by remember(state.directory.searchQuery.text) { mutableStateOf(state.directory.searchQuery.text) }
    var editingStation by remember { mutableStateOf<RadioStation?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var mobileAddButtonVisible by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val indexScrollDispatcher = rememberLibrarySectionIndexSelectionDispatcher()
    val layoutDirection = LocalLayoutDirection.current
    val searchText = queryText.trim()
    val hasTextSearch = searchText.isNotBlank()
    var lastSubmittedSearchText by remember { mutableStateOf(state.directory.searchQuery.text.trim()) }
    val submitSearch: (String) -> Unit = { text ->
        val normalizedText = text.trim()
        lastSubmittedSearchText = normalizedText
        actions.onSearch(RadioStationSearchQuery(text = normalizedText))
    }
    val recommendedStations = remember(state.directory.recommendedStations, searchText) {
        state.directory.recommendedStations.filter { station ->
            searchText.isBlank() || station.matchesSearch(searchText)
        }
    }
    val recommendedByCategory = remember(recommendedStations) {
        recommendedStations.groupBy { it.category ?: "Recommended Streams" }
    }
    val activeDirectoryStations = remember(state.directory.directoryStations, searchText) {
        if (searchText.isBlank()) {
            state.directory.directoryStations
        } else {
            state.directory.directoryStations.filter { it.matchesSearch(searchText) }
        }
    }
    val showCountries = mode == RadioRouteMode.CountryIndex &&
        state.directory.searchQuery.isBlank &&
        !hasTextSearch &&
        (state.directory.loading || state.directory.countries.isNotEmpty())
    val showRecommended = mode == RadioRouteMode.Home && (state.directory.searchQuery.isBlank || hasTextSearch)
    val showingDirectoryResults = !state.directory.searchQuery.isBlank
    val showResultsLabel = hasTextSearch || state.directory.searchQuery.text.isNotBlank()
    val showSectionIndex = mode != RadioRouteMode.CountryStations
    val keepSectionIndexLabelsVisible = false
    val scrollbarState by remember(listState) {
        derivedStateOf {
            LibraryScrollbarState(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                visibleItemsCount = listState.layoutInfo.visibleItemsInfo.size,
                totalItemsCount = listState.layoutInfo.totalItemsCount,
            )
        }
    }
    val revealIndex by remember(listState) {
        derivedStateOf {
            listState.isScrollInProgress
        }
    }
    val showMobileAddButton = sectionIndexMode == LibrarySectionIndexMode.MobileScrollbar && mobileAddButtonVisible
    val listContentPadding = if (sectionIndexMode == LibrarySectionIndexMode.MobileScrollbar) {
        PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            top = contentPadding.calculateTopPadding(),
            end = contentPadding.calculateEndPadding(layoutDirection),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        )
    } else {
        contentPadding
    }
    val shouldLoadMore by remember(
        listState,
        state.directory.searchQuery,
        state.directory.loading,
        state.directory.loadingMore,
        state.directory.canLoadMore,
        activeDirectoryStations.size,
    ) {
        derivedStateOf {
            if (state.directory.searchQuery.isBlank ||
                state.directory.loading ||
                state.directory.loadingMore ||
                !state.directory.canLoadMore
            ) {
                false
            } else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val totalItems = listState.layoutInfo.totalItemsCount
                totalItems > 0 && lastVisible >= totalItems - 8
            }
        }
    }

    if (mode == RadioRouteMode.Map) {
        RadioMapRoute(
            directory = state.directory,
            startingStationIds = state.startingStationIds,
            actions = actions,
            modifier = modifier,
            contentPadding = contentPadding,
            topBar = topBar,
        )
        return
    }

    val sectionAnchors = remember(
        state.directory.manualStations,
        state.directory.countries,
        showCountries,
        state.directory.searchQuery,
        hasTextSearch,
        activeDirectoryStations,
        state.directory.loadingMore,
        showRecommended,
        recommendedByCategory,
    ) {
        buildList {
            var anchorItemIndex = if (topBar == null) 0 else 1
            add("Top" to anchorItemIndex)
            anchorItemIndex += 2

            if (mode == RadioRouteMode.Home && state.directory.manualStations.isNotEmpty()) {
                add("My" to anchorItemIndex)
                anchorItemIndex += 1 + state.directory.manualStations.size
            }

            if (showCountries) {
                add("Countries" to anchorItemIndex)
                anchorItemIndex += 1 + state.directory.countries.size
            }

            if (!state.directory.searchQuery.isBlank || hasTextSearch) {
                add("Results" to anchorItemIndex)
                anchorItemIndex += 1
            }
            if (state.directory.errorMessage != null && !state.directory.searchQuery.isBlank) {
                anchorItemIndex += 1
            }
            if ((!state.directory.searchQuery.isBlank || hasTextSearch) && !state.directory.loading && activeDirectoryStations.isEmpty() && (!showRecommended || recommendedStations.isEmpty())) {
                anchorItemIndex += 1
            }
            anchorItemIndex += activeDirectoryStations.size
            if (state.directory.loadingMore) {
                anchorItemIndex += 1
            }

            if (showRecommended && recommendedStations.isNotEmpty()) {
                recommendedByCategory.forEach { (category, stations) ->
                    add(category to anchorItemIndex)
                    anchorItemIndex += 1 + stations.size
                }
            }
        }
    }

    LaunchedEffect(shouldLoadMore, state.directory.searchQuery, activeDirectoryStations.size) {
        if (shouldLoadMore) actions.onLoadMore()
    }

    LaunchedEffect(state.directory.countries.isEmpty()) {
        if (state.directory.countries.isEmpty()) {
            actions.onRefreshPopular()
        }
    }

    LaunchedEffect(state.directory.searchQuery.text) {
        lastSubmittedSearchText = state.directory.searchQuery.text.trim()
    }

    LaunchedEffect(listState, sectionIndexMode) {
        if (sectionIndexMode != LibrarySectionIndexMode.MobileScrollbar) {
            mobileAddButtonVisible = true
            return@LaunchedEffect
        }

        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val scrollingDown = index > previousIndex || (index == previousIndex && offset > previousOffset)
                val scrollingUp = index < previousIndex || (index == previousIndex && offset < previousOffset)
                when {
                    scrollingDown -> mobileAddButtonVisible = false
                    scrollingUp -> mobileAddButtonVisible = true
                }
                previousIndex = index
                previousOffset = offset
            }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { queryText.trim() }
            .distinctUntilChanged()
            .debounce { text -> if (text.isEmpty()) 0L else RadioSearchDebounceMillis }
            .collect { text ->
                if (text != lastSubmittedSearchText) {
                    submitSearch(text)
                }
            }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = listContentPadding,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            topBar?.let { header ->
                item(key = "top-bar", contentType = "top-bar") { header() }
            }
            if (sectionIndexMode != LibrarySectionIndexMode.MobileScrollbar) {
                item(contentType = "header") {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(mode.title, color = PhoebeUi.primaryText, fontSize = 30.sp, fontWeight = FontWeight.Black)
                            Text(mode.subtitle(state.directory.searchQuery.countryCode), color = PhoebeUi.secondaryText, fontSize = 13.sp)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 80.dp),
                        ) {
                            if (mode == RadioRouteMode.Home) {
                                TextButton(
                                    onClick = { showAddDialog = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text("Add")
                                }
                            }
                        }
                    }
                }
            }

            if (mode != RadioRouteMode.CountryIndex) {
                item(contentType = "search") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RadioTextField(
                        value = queryText,
                        onValueChange = { queryText = it },
                        onSubmit = { submitSearch(queryText) },
                        placeholder = "Search stations",
                    )
                    if (state.directory.loading) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = PhoebeUi.accentLight)
                        }
                    }
                }
                }
            }

            if (mode == RadioRouteMode.Home && state.directory.manualStations.isNotEmpty()) {
                item(contentType = "manual-label") { SectionLabel("MY STATIONS", PhoebeUi.accentLight) }
                items(state.directory.manualStations, key = { "manual:${it.id}" }, contentType = { "manual-station" }) { station ->
                    RadioStationRow(
                        station = station,
                        starting = station.id in state.startingStationIds,
                        onPlay = { actions.onPlay(station) },
                        onEdit = { editingStation = station },
                        onDelete = { actions.onDeleteManualStation(station) },
                    )
                }
            }

            if (mode == RadioRouteMode.Home) {
                item(contentType = "country-entry") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        RadioBrowseGlobeRow(onClick = actions.onBrowseGlobe)
                        RadioBrowseCountriesRow(onClick = actions.onBrowseCountries)
                    }
                }
            }

            if (showCountries) {
                item(contentType = "country-label") {
                    Row(
                        modifier = Modifier.clickable(onClick = actions.onClearCountry),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhoebeIconView(PhoebeIcon.Back, tint = PhoebeUi.accentLight, modifier = Modifier.size(14.dp))
                        SectionLabel("BROWSE BY COUNTRY", PhoebeUi.accentLight)
                    }
                }
                items(state.directory.countries, key = { it.code }, contentType = { "country" }) { country ->
                    RadioCountryRow(
                        country = country,
                        onClick = {
                            queryText = ""
                            actions.onCountry(country)
                        },
                    )
                }
            }

            if (!state.directory.searchQuery.isBlank || hasTextSearch) {
                item(contentType = "directory-label") {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showingDirectoryResults) {
                            TextButton(
                                onClick = {
                                    queryText = ""
                                    if (mode == RadioRouteMode.CountryStations) {
                                        actions.onBrowseCountries()
                                    } else {
                                        actions.onClearCountry()
                                    }
                                },
                            ) {
                                PhoebeIconView(PhoebeIcon.Back, tint = PhoebeUi.accentLight, modifier = Modifier.size(14.dp))
                                Text(
                                    if (mode == RadioRouteMode.CountryStations) "Countries" else "Back",
                                    modifier = Modifier.padding(start = 6.dp),
                                    color = PhoebeUi.accentLight,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        if (showResultsLabel) {
                            SectionLabel("RESULTS", PhoebeUi.accentLight)
                        }
                    }
                }
            }
            state.directory.errorMessage?.takeUnless { state.directory.searchQuery.isBlank }?.let { message ->
                item(contentType = "error") {
                    Text(message, color = PhoebeUi.secondaryText, fontSize = 13.sp)
                }
            }
            if ((!state.directory.searchQuery.isBlank || hasTextSearch) && !state.directory.loading && activeDirectoryStations.isEmpty() && (!showRecommended || recommendedStations.isEmpty())) {
                item(contentType = "empty") {
                    Text("No stations found.", color = PhoebeUi.mutedText, fontSize = 13.sp)
                }
            }
            items(activeDirectoryStations, key = { "directory:${it.id}" }, contentType = { "directory-station" }) { station ->
                RadioStationRow(
                    station = station,
                    starting = station.id in state.startingStationIds,
                    onPlay = { actions.onPlay(station) },
                    onEdit = null,
                    onDelete = null,
                )
            }
            if (state.directory.loadingMore && mode != RadioRouteMode.Home) {
                item(contentType = "loading-more") {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = PhoebeUi.accentLight)
                    }
                }
            }

            if (showRecommended && recommendedStations.isNotEmpty()) {
                recommendedByCategory.forEach { (category, stations) ->
                    item(contentType = "recommended-label-$category") {
                        SectionLabel(category.uppercase(), PhoebeUi.accentLight)
                    }
                    items(stations, key = { "recommended:${it.id}" }, contentType = { "recommended-station" }) { station ->
                        RadioStationRow(
                            station = station,
                            starting = station.id in state.startingStationIds,
                            onPlay = { actions.onPlay(station) },
                            onEdit = null,
                            onDelete = null,
                        )
                    }
                }
            }
        }

        if (showSectionIndex && sectionAnchors.size > 1) {
            LibrarySectionIndex(
                entries = sectionAnchors.map { (label, index) ->
                    LibraryScrollIndexEntry(label = label, itemIndex = index)
                },
                onEntrySelected = { entry ->
                    indexScrollDispatcher.launch(scrollScope, key = entry.itemIndex) {
                        listState.scrollToItem(entry.itemIndex)
                    }
                },
                mode = sectionIndexMode,
                revealSignal = revealIndex,
                keepLabelsVisible = keepSectionIndexLabelsVisible,
                scrollbarState = scrollbarState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(
                        top = contentPadding.calculateTopPadding() + 16.dp,
                        bottom = contentPadding.calculateBottomPadding() + 24.dp,
                    ),
            )
        }

        AnimatedVisibility(
            visible = showMobileAddButton && mode == RadioRouteMode.Home,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 24.dp,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                ),
        ) {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = PhoebeUi.accentLight,
                contentColor = PhoebeUi.primaryText,
                modifier = Modifier.semantics { contentDescription = "Add station" },
            ) {
                PhoebeIconView(PhoebeIcon.Plus, tint = PhoebeUi.primaryText, modifier = Modifier.size(20.dp))
            }
        }
    }

    if (showAddDialog) {
        ManualStationDialog(
            title = "Add station",
            station = null,
            onDismiss = { showAddDialog = false },
            onSave = { name, streamUrl ->
                actions.onAddManualStation(name, streamUrl)
                showAddDialog = false
            },
        )
    }
    editingStation?.let { station ->
        ManualStationDialog(
            title = "Edit station",
            station = station,
            onDismiss = { editingStation = null },
            onSave = { name, streamUrl ->
                actions.onUpdateManualStation(station, name, streamUrl)
                editingStation = null
            },
        )
    }
}

@Composable
private fun RadioBrowseCountriesRow(
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PhoebeUi.elevatedFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PhoebeUi.subtleFill),
            contentAlignment = Alignment.Center,
        ) {
            PhoebeIconView(PhoebeIcon.Search, tint = PhoebeUi.accentLight, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("Browse by country", color = PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Explore the full Radio Browser country directory", color = PhoebeUi.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(14.dp))
    }
}

private val RadioRouteMode.title: String
    get() = when (this) {
        RadioRouteMode.Home -> "Radio"
        RadioRouteMode.CountryIndex -> "Browse by country"
        RadioRouteMode.CountryStations -> "Country radio"
        RadioRouteMode.Map -> "Radio map"
    }

private fun RadioRouteMode.subtitle(countryCode: String): String = when (this) {
    RadioRouteMode.Home -> "Recommended streams and saved internet stations"
    RadioRouteMode.CountryIndex -> "Choose a country from the Radio Browser directory"
    RadioRouteMode.CountryStations -> countryCode.takeIf { it.isNotBlank() }
        ?.let { "Stations broadcasting from $it" }
        ?: "Stations broadcasting from the selected country"
    RadioRouteMode.Map -> "Browse stations by location"
}

@Composable
private fun RadioBrowseGlobeRow(
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PhoebeUi.elevatedFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PhoebeUi.subtleFill),
            contentAlignment = Alignment.Center,
        ) {
            PhoebeIconView(PhoebeIcon.Radio, tint = PhoebeUi.accentLight, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("Browse on map", color = PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Explore geocoded Radio Browser stations on a map", color = PhoebeUi.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun RadioMapRoute(
    directory: RadioDirectoryState,
    startingStationIds: Set<String>,
    actions: RadioRouteActions,
    modifier: Modifier,
    contentPadding: PaddingValues,
    topBar: (@Composable () -> Unit)?,
) {
    var queryText by remember(directory.globeSearchQuery.text) { mutableStateOf(directory.globeSearchQuery.text) }
    var selectedItemId by remember { mutableStateOf<String?>(null) }
    var expandedClusterIds by remember { mutableStateOf(emptySet<String>()) }
    var mapZoom by remember { mutableDoubleStateOf(2.0) }
    var mapViewport by remember { mutableStateOf<RadioMapViewport?>(null) }
    var submittedViewport by remember { mutableStateOf<RadioMapViewport?>(null) }
    var mapPresented by remember { mutableStateOf(false) }

    LaunchedEffect(directory.globeStations) {
        expandedClusterIds = emptySet()
    }

    LaunchedEffect(mapZoom) {
        if (mapZoom < 4.0 && expandedClusterIds.isNotEmpty()) {
            expandedClusterIds = emptySet()
        }
    }

    val externalBrowserMap = radioMapUsesExternalBrowser()
    val minimalEmbeddedMap = radioMapUsesMinimalEmbeddedChrome()
    val clusterThresholdDegrees = remember(mapZoom, externalBrowserMap, minimalEmbeddedMap) {
        if (externalBrowserMap || minimalEmbeddedMap) {
            1.5
        } else {
            radioMapClusterThresholdDegrees(mapZoom)
        }
    }
    var items by remember { mutableStateOf(emptyList<RadioMapItem>()) }
    LaunchedEffect(directory.globeStations, expandedClusterIds, clusterThresholdDegrees) {
        items = withContext(Dispatchers.Default) {
            clusterStations(
                stations = directory.globeStations,
                clusterThresholdDegrees = clusterThresholdDegrees,
                expandedClusterIds = expandedClusterIds,
            )
        }
    }
    val selectedItem = remember(items, selectedItemId) {
        selectedItemId?.let { id -> items.findRadioMapItem(id) } ?: items.firstOrNull()
    }
    val selectedSnackbarStation = remember(items, selectedItemId) {
        selectedItemId
            ?.let { id -> items.findRadioMapItem(id) }
            ?.let { it as? RadioMapItem.Station }
    }
    LaunchedEffect(
        items,
        directory.globeLoading,
        directory.globeAutoPrefetching,
        directory.globeLoadedStationCount,
        directory.canLoadNextGlobePage,
    ) {
        val initialDenseBatchReady = directory.globeLoadedStationCount >= RadioMapInitialPresentationStationTarget
        val loadingSettled = !directory.globeAutoPrefetching || !directory.canLoadNextGlobePage
        if (!mapPresented && items.isNotEmpty() && !directory.globeLoading && (initialDenseBatchReady || loadingSettled)) {
            mapPresented = true
        }
    }
    val shouldWaitForMapMarkers = !externalBrowserMap &&
        !mapPresented &&
        (items.isEmpty() || directory.globeLoading || directory.globeAutoPrefetching)
    val submitSearch: () -> Unit = {
        actions.onGlobeSearch(RadioStationSearchQuery(text = queryText.trim()), 0)
    }

    val handleItemSelected: (RadioMapItem) -> Unit = remember(expandedClusterIds) {
        { item ->
            when (item) {
                is RadioMapItem.Cluster -> {
                    selectedItemId = item.id
                    expandedClusterIds = expandedRadioMapClusterIds(item, expandedClusterIds)
                    item.countryCodeForDrilldown()?.let(actions.onGlobeCountry)
                }
                is RadioMapItem.Station -> {
                    selectedItemId = item.id
                }
            }
        }
    }
    val handleItemPlay: (RadioMapItem) -> Unit = remember {
        { item ->
            if (item is RadioMapItem.Station) {
                selectedItemId = item.id
                actions.onPlay(item.station)
            }
        }
    }

    LaunchedEffect(items) {
        if (selectedItemId != null && items.findRadioMapItem(selectedItemId.orEmpty()) == null) {
            selectedItemId = null
        }
    }

    val canSearchArea = mapViewport?.let { viewport ->
        viewport.isValid &&
            mapPresented
    } == true
    val searchArea: () -> Unit = {
        if (!directory.globeLoading) {
            mapViewport
                ?.takeIf { it.isValid }
                ?.let { viewport ->
                    submittedViewport = viewport
                    actions.onGlobeViewport(viewport)
                }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        topBar?.invoke()
        if (externalBrowserMap || minimalEmbeddedMap) {
            val hostModifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .semantics {
                    contentDescription = if (externalBrowserMap) {
                        "Radio map browser launcher"
                    } else {
                        "Radio map"
                    }
                }
            RadioMapSurface(
                directory = directory,
                selectedStation = selectedSnackbarStation,
                starting = selectedSnackbarStation?.station?.id in startingStationIds,
                onSelectedStationPlay = {
                    selectedSnackbarStation?.let(handleItemPlay)
                },
                onSelectedStationDismiss = {
                    selectedItemId = null
                },
                canSearchArea = canSearchArea,
                searchAreaLoading = directory.globeLoading,
                onSearchArea = searchArea,
                showStatus = false,
                modifier = hostModifier,
            ) { mapModifier ->
                if (shouldWaitForMapMarkers) {
                    RadioMapFallback(
                        items = items,
                        loading = directory.globeLoading || directory.globeAutoPrefetching,
                        errorMessage = directory.globeErrorMessage,
                        onItemSelected = handleItemSelected,
                        onItemPlay = handleItemPlay,
                        modifier = mapModifier,
                    )
                } else {
                    RadioMapHost(
                        items = items,
                        selectedItem = if (externalBrowserMap) null else selectedItem,
                        startingStationIds = startingStationIds,
                        mapLoading = directory.globeLoading,
                        markerTintColor = PhoebeUi.accentLight,
                        googleMapsApiKey = radioMapGoogleMapsApiKey(),
                        onItemSelected = handleItemSelected,
                        onItemPlay = handleItemPlay,
                        onMapZoomChanged = { zoom -> mapZoom = zoom },
                        onMapViewportChanged = { viewport ->
                            mapZoom = viewport.zoom
                            mapViewport = viewport
                        },
                        onMapSearchArea = { viewport ->
                            mapViewport = viewport
                            submittedViewport = viewport
                            actions.onGlobeViewport(viewport)
                        },
                        modifier = mapModifier,
                        fallback = { fallbackModifier ->
                            RadioMapFallback(
                                items = items,
                                loading = directory.globeLoading || directory.globeAutoPrefetching,
                                errorMessage = directory.globeErrorMessage,
                                onItemSelected = handleItemSelected,
                                onItemPlay = handleItemPlay,
                                modifier = fallbackModifier,
                            )
                        },
                    )
                }
            }
            return@Column
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Radio map", color = PhoebeUi.primaryText, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text("Only stations with map coordinates are shown.", color = PhoebeUi.secondaryText, fontSize = 13.sp)
            }
            TextButton(onClick = actions.onClearCountry) {
                PhoebeIconView(PhoebeIcon.Back, tint = PhoebeUi.accentLight, modifier = Modifier.size(14.dp))
                Text("Radio", modifier = Modifier.padding(start = 6.dp), color = PhoebeUi.accentLight, fontSize = 12.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioTextField(
                value = queryText,
                onValueChange = { queryText = it },
                placeholder = "Search map stations",
                onSubmit = submitSearch,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = submitSearch) {
                Text("Search")
            }
        }
        RadioMapSurface(
            directory = directory,
            selectedStation = selectedSnackbarStation,
            starting = selectedSnackbarStation?.station?.id in startingStationIds,
            onSelectedStationPlay = {
                selectedSnackbarStation?.let(handleItemPlay)
            },
            onSelectedStationDismiss = {
                selectedItemId = null
            },
            canSearchArea = canSearchArea,
            searchAreaLoading = directory.globeLoading,
            onSearchArea = searchArea,
            showStatus = false,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .semantics { contentDescription = "Radio map" },
        ) { mapModifier ->
            RadioMapHost(
                items = items,
                selectedItem = selectedItem,
                startingStationIds = startingStationIds,
                mapLoading = directory.globeLoading,
                markerTintColor = PhoebeUi.accentLight,
                googleMapsApiKey = radioMapGoogleMapsApiKey(),
                onItemSelected = handleItemSelected,
                onItemPlay = handleItemPlay,
                onMapZoomChanged = { zoom -> mapZoom = zoom },
                onMapViewportChanged = { viewport ->
                    mapZoom = viewport.zoom
                    mapViewport = viewport
                },
                onMapSearchArea = { viewport ->
                    mapViewport = viewport
                    submittedViewport = viewport
                    actions.onGlobeViewport(viewport)
                },
                modifier = mapModifier,
                fallback = { fallbackModifier ->
                    RadioMapFallback(
                        items = items,
                        loading = directory.globeLoading || directory.globeAutoPrefetching,
                        errorMessage = directory.globeErrorMessage,
                        onItemSelected = handleItemSelected,
                        onItemPlay = handleItemPlay,
                        modifier = fallbackModifier,
                    )
                },
            )
        }
        selectedItem?.let { item ->
            when (item) {
                is RadioMapItem.Station -> {
                    RadioMapStationPanel(
                        station = item.station,
                        approximate = item.approximate,
                        locationLabel = item.locationLabel,
                        starting = item.station.id in startingStationIds,
                        onPlay = { actions.onPlay(item.station) },
                    )
                }
                is RadioMapItem.Cluster -> {
                    RadioMapClusterPanel(
                        cluster = item,
                        startingStationIds = startingStationIds,
                        onPlay = { actions.onPlay(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RadioMapSurface(
    directory: RadioDirectoryState,
    selectedStation: RadioMapItem.Station?,
    starting: Boolean,
    onSelectedStationPlay: () -> Unit,
    onSelectedStationDismiss: () -> Unit,
    canSearchArea: Boolean,
    searchAreaLoading: Boolean,
    onSearchArea: () -> Unit,
    showStatus: Boolean,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    val statusText = remember(
        directory.globeLoadedStationCount,
        directory.globeLoading,
        directory.globeAutoPrefetching,
        directory.globeMapScope,
    ) {
        radioMapStatusText(directory)
    }

    Box(modifier = modifier) {
        content(Modifier.fillMaxSize())

        AnimatedVisibility(
            visible = controlsVisible && showStatus && statusText.isNotBlank(),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
        ) {
            Surface(
                color = Color(0xE5121722),
                contentColor = Color(0xFFF4F5F7),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (directory.globeLoading || directory.globeAutoPrefetching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = PhoebeUi.accentLight,
                        )
                    }
                    Text(
                        text = statusText,
                        color = Color(0xFFF4F5F7),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && canSearchArea,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
        ) {
            FloatingActionButton(
                onClick = {
                    if (!searchAreaLoading) {
                        onSearchArea()
                    }
                },
                containerColor = Color(0xF5121722),
                contentColor = Color(0xFFF4F5F7),
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (searchAreaLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFFF4F5F7),
                        )
                    } else {
                        PhoebeIconView(PhoebeIcon.Search, tint = Color(0xFFF4F5F7), modifier = Modifier.size(18.dp))
                    }
                    Text(
                        text = if (searchAreaLoading) "Searching this area" else "Search this area",
                        color = Color(0xFFF4F5F7),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = selectedStation != null,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        ) {
            selectedStation?.let { item ->
                RadioMapStationSnackbar(
                    station = item.station,
                    approximate = item.approximate,
                    locationLabel = item.locationLabel,
                    starting = starting,
                    onPlay = onSelectedStationPlay,
                    onDismiss = onSelectedStationDismiss,
                )
            }
        }
    }
}

private fun radioMapStatusText(directory: RadioDirectoryState): String {
    val country = directory.globeMapScope.normalizedCountryCode
    return when {
        directory.globeLoading && country.isNotBlank() -> "Loading stations in $country"
        directory.globeLoading -> "Loading radio map"
        directory.globeAutoPrefetching && country.isNotBlank() -> "Loading more stations in $country"
        directory.globeAutoPrefetching -> "Loading more pins..."
        directory.globeLoadedStationCount > 0 && country.isNotBlank() ->
            "Showing ${directory.globeLoadedStationCount} mapped stations in $country"
        directory.globeLoadedStationCount > 0 ->
            "Showing ${directory.globeLoadedStationCount} mapped stations"
        else -> ""
    }
}

@Composable
private fun RadioMapStationSnackbar(
    station: RadioStation,
    approximate: Boolean,
    locationLabel: String,
    starting: Boolean,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val snackbarBackground = Color(0xF5121722)
    val snackbarBorder = Color.White.copy(alpha = 0.08f)
    val snackbarPrimaryText = Color(0xFFF4F5F7)
    val snackbarSecondaryText = Color(0xFFB6BBC7)
    val snackbarMutedText = Color(0xFF7D8493)
    val actionContentColor = Color(0xFF07111E)

    Surface(
        color = snackbarBackground,
        contentColor = snackbarPrimaryText,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, snackbarBorder),
        shadowElevation = 12.dp,
        modifier = Modifier
            .widthIn(max = 520.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    station.name,
                    color = snackbarPrimaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (approximate) "Approximate: $locationLabel" else locationLabel,
                    color = snackbarSecondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    station.displaySubtitle,
                    color = snackbarMutedText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    seed = station.name,
                    thumbUrl = station.faviconUrlOrFallback,
                    fallbackThumbUrl = station.fallbackArtworkUrl,
                    modifier = Modifier.size(42.dp),
                    radius = 8.dp,
                    elevated = false,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = onPlay,
                        enabled = !starting,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = PhoebeUi.accentLight,
                            contentColor = actionContentColor,
                            disabledContainerColor = PhoebeUi.accentLight.copy(alpha = 0.28f),
                            disabledContentColor = snackbarSecondaryText,
                        ),
                    ) {
                        if (starting) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = snackbarSecondaryText)
                        } else {
                            PhoebeIconView(PhoebeIcon.Play, tint = actionContentColor, modifier = Modifier.size(14.dp))
                            Text("Play", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = snackbarMutedText),
                    ) {
                        PhoebeIconView(PhoebeIcon.Close, tint = snackbarMutedText, modifier = Modifier.size(13.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioMapFallback(
    items: List<RadioMapItem>,
    loading: Boolean,
    errorMessage: String?,
    onItemSelected: (RadioMapItem) -> Unit,
    onItemPlay: (RadioMapItem) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp)),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(contentType = "map-status") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Google Maps map", color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    val showMessage = !loading || radioMapGoogleMapsApiKey() == null
                    if (showMessage) {
                        Text(
                            if (radioMapGoogleMapsApiKey() == null) {
                                "Set PHOEBE_GOOGLE_MAPS_API_KEY to enable the interactive Google Maps map. Station locations are listed below."
                            } else {
                                "Google Maps map host is unavailable on this build; station locations are listed below."
                            },
                            color = PhoebeUi.secondaryText,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            if (loading) {
                item(contentType = "map-loading") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = PhoebeUi.accentLight)
                        Text("Loading station locations", color = PhoebeUi.secondaryText, fontSize = 12.sp)
                    }
                }
            }
            errorMessage?.let { message ->
                item(contentType = "map-error") {
                    Text(message, color = PhoebeUi.secondaryText, fontSize = 12.sp)
                }
            }
            if (!loading && items.isEmpty()) {
                item(contentType = "map-empty") {
                    Text("No station locations found.", color = PhoebeUi.mutedText, fontSize = 13.sp)
                }
            }
            if (!loading) {
                items(items, key = { "map:${it.id}" }, contentType = { "map-item" }) { item ->
                    RadioMapItemRow(
                        item = item,
                        onClick = {
                            onItemSelected(item)
                            if (item is RadioMapItem.Station) {
                                onItemPlay(item)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RadioMapItemRow(
    item: RadioMapItem,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PhoebeUi.elevatedFill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(if (item.isCluster) 16.dp else 12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (item.approximate) PhoebeUi.accent else PhoebeUi.accentLight),
            contentAlignment = Alignment.Center
        ) {
            if (item.isCluster) {
                Text(
                    text = item.clusterCount.toString(),
                    color = Color(0xFF0F172A),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(item.name, color = PhoebeUi.primaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val label = when (item) {
                is RadioMapItem.Station -> {
                    val loc = item.locationLabel
                    if (item.approximate) "Approximate: $loc" else loc
                }
                is RadioMapItem.Cluster -> {
                    if (item.approximate) "Approximate Country Cluster" else "Station Cluster"
                }
            }
            Text(
                text = label,
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("${item.latitude.toInt()}, ${item.longitude.toInt()}", color = PhoebeUi.mutedText, fontSize = 11.sp)
    }
}

@Composable
private fun RadioMapClusterPanel(
    cluster: RadioMapItem.Cluster,
    startingStationIds: Set<String>,
    onPlay: (RadioStation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PhoebeUi.elevatedFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (cluster.approximate) "Approximate Country Cluster (${cluster.stations.size} stations)" else "Station Cluster (${cluster.stations.size} stations)",
            color = PhoebeUi.primaryText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Box(modifier = Modifier.heightIn(max = 160.dp)) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(cluster.stations, key = { it.id }) { station ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(PhoebeUi.subtleFill)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkImage(
                            seed = station.name,
                            thumbUrl = station.faviconUrlOrFallback,
                            fallbackThumbUrl = station.fallbackArtworkUrl,
                            modifier = Modifier.size(36.dp),
                            radius = 6.dp,
                            elevated = false,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(station.name, color = PhoebeUi.primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(station.displaySubtitle, color = PhoebeUi.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val starting = station.id in startingStationIds
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(enabled = !starting) { onPlay(station) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (starting) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = PhoebeUi.accentLight)
                            } else {
                                PhoebeIconView(PhoebeIcon.Play, tint = PhoebeUi.accentLight, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private val RadioMapItem.Station.locationLabel: String
    get() = listOfNotNull(
        station.state?.takeIf { it.isNotBlank() },
        station.countryCode?.takeIf { it.isNotBlank() },
    ).joinToString(", ").ifBlank {
        if (approximate) "Approximate country location" else "Station location"
    }

@Composable
private fun RadioMapStationPanel(
    station: RadioStation,
    approximate: Boolean,
    locationLabel: String,
    starting: Boolean,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PhoebeUi.elevatedFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            seed = station.name,
            thumbUrl = station.faviconUrlOrFallback,
            fallbackThumbUrl = station.fallbackArtworkUrl,
            modifier = Modifier.size(44.dp),
            radius = 8.dp,
            elevated = false,
        )
        Column(Modifier.weight(1f)) {
            Text(station.name, color = PhoebeUi.primaryText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (approximate) "Approximate: $locationLabel" else locationLabel,
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(station.displaySubtitle, color = PhoebeUi.mutedText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilledTonalButton(onClick = onPlay, enabled = !starting) {
            if (starting) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = PhoebeUi.accentLight)
            } else {
                Text("Play")
            }
        }
    }
}

@Composable
private fun RadioCountryRow(
    country: RadioCountry,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PhoebeUi.elevatedFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(country.name, color = PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(country.code, color = PhoebeUi.secondaryText, fontSize = 12.sp)
        }
        Text("${country.stationCount}", color = PhoebeUi.mutedText, fontSize = 12.sp)
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun RadioStationRow(
    station: RadioStation,
    starting: Boolean,
    onPlay: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PhoebeUi.elevatedFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp))
            .clickable(enabled = !starting, onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PhoebeUi.subtleFill),
            contentAlignment = Alignment.Center,
        ) {
            ArtworkImage(
                seed = station.name,
                thumbUrl = station.faviconUrlOrFallback,
                fallbackThumbUrl = station.fallbackArtworkUrl,
                modifier = Modifier.size(38.dp),
                radius = 8.dp,
                elevated = false,
            )
        }
        Column(Modifier.weight(1f)) {
            val subtitle = if (station.source == RadioStationSource.Recommended) {
                station.description?.takeIf { it.isNotBlank() } ?: station.displaySubtitle
            } else {
                station.displaySubtitle
            }
            Text(station.name, color = PhoebeUi.primaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = PhoebeUi.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (station.source == RadioStationSource.Manual && onEdit != null && onDelete != null) {
            TextButton(onClick = onEdit) { Text("Edit", color = PhoebeUi.accentLight, fontSize = 12.sp) }
            TextButton(onClick = onDelete) { Text("Delete", color = PhoebeUi.mutedText, fontSize = 12.sp) }
        }
        if (starting) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = PhoebeUi.accentLight)
        } else {
            PhoebeIconView(PhoebeIcon.Play, tint = PhoebeUi.primaryText, modifier = Modifier.size(16.dp))
        }
    }
}

private fun RadioStation.matchesSearch(query: String): Boolean {
    val normalized = query.lowercase()
    return listOf(name, description, category, tags, countryCode, language, streamUrl, homepageUrl)
        .filterNotNull()
        .any { normalized in it.lowercase() }
}

@Composable
private fun ManualStationDialog(
    title: String,
    station: RadioStation?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(station?.id) { mutableStateOf(station?.name.orEmpty()) }
    var streamUrl by remember(station?.id) { mutableStateOf(station?.streamUrl.orEmpty()) }
    val isValid = name.isNotBlank() && streamUrl.isNotBlank()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(PhoebeUi.panel)
                .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(12.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = PhoebeUi.primaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            RadioTextField(name, { name = it }, "Name")
            RadioTextField(streamUrl, { streamUrl = it }, "Stream URL")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = PhoebeUi.secondaryText) }
                FilledTonalButton(
                    onClick = { onSave(name, streamUrl) },
                    enabled = isValid,
                ) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun RadioTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onSubmit: () -> Unit = {},
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = PhoebeUi.primaryText, fontSize = 13.sp),
        cursorBrush = SolidColor(PhoebeUi.accentLight),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = modifier
            .height(42.dp)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                    onSubmit()
                    true
                } else {
                    false
                }
            }
            .clip(RoundedCornerShape(8.dp))
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth()) {
                if (value.isBlank()) Text(placeholder, color = PhoebeUi.mutedText, fontSize = 13.sp)
                inner()
            }
        },
    )
}

internal fun expandedRadioMapClusterIds(
    item: RadioMapItem,
    expandedClusterIds: Set<String>,
): Set<String> =
    if (item is RadioMapItem.Cluster) {
        expandedClusterIds + item.id
    } else {
        expandedClusterIds
    }

private const val RadioMapInitialPresentationStationTarget = 2_000
