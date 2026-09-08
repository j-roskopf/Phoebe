package com.phoebe.app.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.phoebe.app.db.ManualRadioStationRow
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.domain.RadioDirectoryState
import com.phoebe.app.domain.RadioMapScope
import com.phoebe.app.domain.RadioMapScopeKind
import com.phoebe.app.domain.RadioStation
import com.phoebe.app.domain.RadioStationSearchQuery
import com.phoebe.app.domain.RadioStationSource
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.currentTimeMs
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import com.phoebe.app.domain.isNavidrome
import com.phoebe.app.platform.PhoebeLog

@SingleIn(AppScope::class)
@Inject
class RadioRepository(
    private val database: PhoebeDatabase,
    private val radioBrowserClient: RadioBrowserClient,
    private val subsonicClient: SubsonicClient,
    private val sessionRepository: SessionRepository,
) {
    private val mutableState = MutableStateFlow(RadioDirectoryState(recommendedStations = RecommendedRadioStations))
    val state: StateFlow<RadioDirectoryState> = mutableState.asStateFlow()

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        repositoryScope.launch {
            sessionRepository.session.collect { session ->
                if (session?.isNavidrome() == true) {
                    runCatching {
                        syncNavidromeStations()
                        mutableState.value = mutableState.value.copy(
                            manualStations = loadManualStations()
                        )
                    }.onFailure { error ->
                        PhoebeLog.d("RadioRepository") { "Failed to sync Navidrome stations: ${error.message}" }
                    }
                }
            }
        }
    }

    suspend fun restore() {
        val manual = loadManualStations()
        mutableState.value = mutableState.value.copy(
            manualStations = manual,
            recommendedStations = RecommendedRadioStations,
        )
    }

    suspend fun refreshPopular() {
        if (sessionRepository.session.value?.isNavidrome() == true) {
            runCatching { syncNavidromeStations() }
            mutableState.value = mutableState.value.copy(
                manualStations = loadManualStations()
            )
        }
        loadCountries()
    }

    suspend fun search(query: RadioStationSearchQuery) {
        val normalized = query.normalized()
        if (normalized.isBlank) {
            loadBrowseMetadata()
        } else {
            loadDirectory(normalized)
        }
    }

    suspend fun browseCountry(countryCode: String) {
        search(RadioStationSearchQuery(countryCode = countryCode))
    }

    suspend fun loadGlobe(
        query: RadioStationSearchQuery = mutableState.value.globeSearchQuery,
        page: Int = 0,
        countryCode: String? = null,
    ) {
        val normalized = query.normalized()
        val explicitCountryCode = countryCode?.trim()?.uppercase().orEmpty()
        val scopedCountryCode = explicitCountryCode.ifBlank { normalized.countryCode }
        val requestedScope = if (scopedCountryCode.isNotBlank()) {
            RadioMapScope(countryCode = scopedCountryCode, kind = RadioMapScopeKind.Country)
        } else {
            RadioMapScope()
        }
        val scopedQuery = requestedScope.normalizedCountryCode
            .takeIf { it.isNotBlank() }
            ?.let { normalized.copy(countryCode = it).normalized() }
            ?: normalized

        val previous = mutableState.value
        mutableState.value = previous.copy(
            globeLoading = true,
            globeAutoPrefetching = false,
            globeErrorMessage = null,
            globeSearchQuery = scopedQuery,
            globePageIndex = 0,
            globePageSize = GlobeGeoStationLimit,
            globeMapScope = requestedScope,
            globeViewport = null,
            globeStations = emptyList(),
            globeLoadedStationCount = 0,
            globeMapLoaded = false,
            canLoadPreviousGlobePage = false,
            canLoadNextGlobePage = false,
        )

        val result = runCatching {
            loadAllGlobeStations(scopedQuery)
        }.onFailure { if (it is CancellationException) throw it }

        val current = mutableState.value
        if (current.globeSearchQuery != scopedQuery || current.globeMapScope != requestedScope) return

        val stations = result.getOrDefault(emptyList())
        mutableState.value = current.copy(
            globeStations = stations,
            globeLoadedStationCount = stations.size,
            globeMapLoaded = result.isSuccess,
            globeLoading = false,
            globeAutoPrefetching = false,
            globeErrorMessage = result.exceptionOrNull()?.message
                ?: if (result.isFailure) "Could not load radio map stations." else null,
            globePageIndex = 0,
            globePageSize = GlobeGeoStationLimit,
            globeViewport = null,
            canLoadPreviousGlobePage = false,
            canLoadNextGlobePage = false,
        )
    }

    suspend fun showStation(stationId: String) {
        val normalizedId = stationId.trim()
        if (normalizedId.isBlank()) {
            search(RadioStationSearchQuery())
            return
        }
        mutableState.value = mutableState.value.copy(
            loading = true,
            loadingMore = false,
            canLoadMore = false,
            errorMessage = null,
            searchQuery = RadioStationSearchQuery(text = normalizedId),
            directoryStations = emptyList(),
        )
        val result = runCatching { findStationById(normalizedId) }
            .onFailure { if (it is CancellationException) throw it }
        val station = result.getOrNull()
        mutableState.value = mutableState.value.copy(
            loading = false,
            loadingMore = false,
            canLoadMore = false,
            errorMessage = result.exceptionOrNull()?.message
                ?: if (station == null) "Radio station not found." else null,
            searchQuery = RadioStationSearchQuery(text = station?.name ?: normalizedId),
            directoryStations = station?.let(::listOf).orEmpty(),
        )
    }

    suspend fun loadMore() {
        val state = mutableState.value
        val query = state.searchQuery.normalized()
        if (query.isBlank || state.loading || state.loadingMore || !state.canLoadMore) return
        val offset = state.directoryStations.size
        mutableState.value = state.copy(loadingMore = true, errorMessage = null)
        val result = runCatching {
            radioBrowserClient.search(query, limit = DirectoryPageSize, offset = offset)
        }
        val current = mutableState.value
        if (current.searchQuery.normalized() != query) return
        val page = result.getOrDefault(emptyList())
        mutableState.value = current.copy(
            directoryStations = (current.directoryStations + page).distinctBy { it.id },
            loadingMore = false,
            canLoadMore = page.size >= DirectoryPageSize,
            errorMessage = result.exceptionOrNull()?.message,
        )
    }

    suspend fun addManualStation(name: String, streamUrl: String): Result<RadioStation> {
        val session = sessionRepository.session.value
        if (session?.isNavidrome() == true) {
            val server = session.selectedServer
            if (server != null) {
                val username = session.userName
                val password = session.token
                val result = runCatching {
                    subsonicClient.createInternetRadioStation(server, username, password, name, streamUrl)
                }
                if (result.isFailure) {
                    return Result.failure(result.exceptionOrNull() ?: Exception("Failed to create radio station on server."))
                }
                val syncResult = runCatching { syncNavidromeStations() }
                mutableState.value = mutableState.value.copy(manualStations = loadManualStations())
                if (syncResult.isFailure) {
                    return Result.failure(syncResult.exceptionOrNull() ?: Exception("Sync failed."))
                }
                val syncedStation = mutableState.value.manualStations.find { it.name == name && it.streamUrl == streamUrl }
                return Result.success(syncedStation ?: RadioStation(id = "temp", name = name, streamUrl = streamUrl, source = RadioStationSource.Manual))
            }
        }
        return saveManualStation(id = null, name = name, streamUrl = streamUrl)
    }

    suspend fun updateManualStation(id: String, name: String, streamUrl: String): Result<RadioStation> {
        val session = sessionRepository.session.value
        val server = session?.takeIf { it.isNavidrome() }?.selectedServer
        val prefix = server?.let { "subsonic:${it.id}:" }
        if (prefix != null && id.startsWith(prefix)) {
            val username = session.userName
            val password = session.token
            val serverId = id.removePrefix(prefix)
            val result = runCatching {
                subsonicClient.updateInternetRadioStation(server, username, password, serverId, name, streamUrl)
            }
            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: Exception("Failed to update radio station on server."))
            }
            val syncResult = runCatching { syncNavidromeStations() }
            mutableState.value = mutableState.value.copy(manualStations = loadManualStations())
            if (syncResult.isFailure) {
                return Result.failure(syncResult.exceptionOrNull() ?: Exception("Sync failed."))
            }
            val syncedStation = mutableState.value.manualStations.find { it.id == id }
            return Result.success(syncedStation ?: RadioStation(id = id, name = name, streamUrl = streamUrl, source = RadioStationSource.Manual))
        }
        return saveManualStation(id = id, name = name, streamUrl = streamUrl)
    }

    suspend fun deleteManualStation(id: String) {
        val session = sessionRepository.session.value
        val server = session?.takeIf { it.isNavidrome() }?.selectedServer
        val prefix = server?.let { "subsonic:${it.id}:" }
        if (prefix != null && id.startsWith(prefix)) {
            val username = session.userName
            val password = session.token
            val serverId = id.removePrefix(prefix)
            val result = runCatching {
                subsonicClient.deleteInternetRadioStation(server, username, password, serverId)
            }
            if (result.isSuccess) {
                withContext(Dispatchers.Default) {
                    database.radioStationsQueries.deleteManualStation(id)
                }
                runCatching { syncNavidromeStations() }
            }
        } else {
            withContext(Dispatchers.Default) {
                database.radioStationsQueries.deleteManualStation(id)
            }
        }
        mutableState.value = mutableState.value.copy(manualStations = loadManualStations())
    }

    suspend fun findStationById(id: String): RadioStation? {
        RecommendedRadioStations.find { it.id == id }?.let { return it }
        existingManualStation(id)?.toRadioStation()?.let { return it }
        return radioBrowserClient.stationByUuid(id)
    }

    suspend fun stationTrack(station: RadioStation): Track {
        val streamUrl = radioBrowserClient.resolvePlaybackUrl(station)
        return station.toTrack(streamUrl)
    }

    fun resetInMemoryState() {
        mutableState.value = RadioDirectoryState(recommendedStations = RecommendedRadioStations)
    }

    private suspend fun loadCountries() {
        loadBrowseMetadata()
    }

    private suspend fun loadBrowseMetadata() {
        mutableState.value = mutableState.value.copy(
            loading = true,
            errorMessage = null,
            searchQuery = RadioStationSearchQuery(),
            directoryStations = emptyList(),
            loadingMore = false,
            canLoadMore = false,
        )
        val result = runCatching { radioBrowserClient.countries() }
        val options = result.getOrNull()
        mutableState.value = mutableState.value.copy(
            countries = options ?: mutableState.value.countries,
            directoryStations = emptyList(),
            loading = false,
            loadingMore = false,
            canLoadMore = false,
            errorMessage = result.exceptionOrNull()?.message,
            searchQuery = RadioStationSearchQuery(),
        )
    }

    private suspend fun loadAllGlobeStations(query: RadioStationSearchQuery): List<RadioStation> {
        val stationsById = LinkedHashMap<String, RadioStation>()
        var offset = 0
        while (offset < GlobeGeoStationSafetyCap) {
            val limit = minOf(GlobeGeoStationLimit, GlobeGeoStationSafetyCap - offset)
            val page = radioBrowserClient.search(
                query,
                limit = limit,
                offset = offset,
                requireGeoInfo = true,
            )
            page.forEach { station ->
                if (station.id !in stationsById) {
                    stationsById[station.id] = station
                }
            }
            if (page.size < limit) break
            offset += limit
        }
        return stationsById.values.toList()
    }

    private suspend fun loadDirectory(query: RadioStationSearchQuery) {
        mutableState.value = mutableState.value.copy(
            loading = true,
            loadingMore = false,
            canLoadMore = false,
            errorMessage = null,
            searchQuery = query,
        )
        val result = runCatching {
            if (query.isBlank) radioBrowserClient.popularStations() else radioBrowserClient.search(query, limit = DirectoryPageSize)
        }
        val stations = result.getOrDefault(emptyList())
        mutableState.value = mutableState.value.copy(
            directoryStations = stations,
            loading = false,
            loadingMore = false,
            canLoadMore = !query.isBlank && stations.size >= DirectoryPageSize,
            errorMessage = result.exceptionOrNull()?.message,
            searchQuery = query,
        )
    }

    private suspend fun saveManualStation(
        id: String?,
        name: String,
        streamUrl: String,
    ): Result<RadioStation> = runCatching {
        val normalizedName = name.trim()
        require(normalizedName.isNotBlank()) { "Station name is required." }
        val normalizedUrl = streamUrl.trim()
        require(normalizedUrl.isNotBlank()) { "Stream URL is required." }
        val parsed = Url(normalizedUrl)
        require(parsed.protocol.name == "http" || parsed.protocol.name == "https") {
            "Stream URL must start with http:// or https://."
        }
        val now = currentTimeMs()
        val stationId = id?.takeIf { it.isNotBlank() } ?: "manual:$now"
        val existing = id?.let { existingManualStation(it) }
        val createdAt = existing?.createdAtMs ?: now
        withContext(Dispatchers.Default) {
            database.radioStationsQueries.upsertManualStation(
                id = stationId,
                name = normalizedName,
                streamUrl = normalizedUrl,
                homepageUrl = null,
                faviconUrl = null,
                createdAtMs = createdAt,
                updatedAtMs = now,
            )
        }
        val station = RadioStation(
            id = stationId,
            name = normalizedName,
            streamUrl = normalizedUrl,
            source = RadioStationSource.Manual,
        )
        mutableState.value = mutableState.value.copy(manualStations = loadManualStations())
        station
    }

    private suspend fun loadManualStations(): List<RadioStation> =
        withContext(Dispatchers.Default) {
            database.radioStationsQueries.selectManualStations().awaitAsList()
        }.map { it.toRadioStation() }

    private suspend fun syncNavidromeStations() {
        val session = sessionRepository.session.value ?: return
        if (!session.isNavidrome()) return
        val server = session.selectedServer ?: return
        val username = session.userName
        val password = session.token
        val synced = subsonicClient.getInternetRadioStations(server, username, password)
        withContext(Dispatchers.Default) {
            val prefix = "subsonic:${server.id}:"
            val current = database.radioStationsQueries.selectManualStations().awaitAsList()
            val existingSynced = current.filter { it.id.startsWith(prefix) }
            val syncedIds = synced.map { it.id }.toSet()
            
            database.transaction {
                existingSynced.forEach { existing ->
                    if (existing.id !in syncedIds) {
                        database.radioStationsQueries.deleteManualStation(existing.id)
                    }
                }
                synced.forEach { station ->
                    database.radioStationsQueries.upsertManualStation(
                        id = station.id,
                        name = station.name,
                        streamUrl = station.streamUrl,
                        homepageUrl = station.homepageUrl,
                        faviconUrl = station.faviconUrl,
                        createdAtMs = currentTimeMs(),
                        updatedAtMs = currentTimeMs(),
                    )
                }
            }
        }
    }

    fun exportRadioStations(): RadioStationsExport {
        return RadioStationsExport(
            stations = mutableState.value.manualStations
                .filterNot { it.id.startsWith("subsonic:") }
                .map {
                    RadioStationExportEntry(
                        name = it.name,
                        streamUrl = it.streamUrl,
                        homepageUrl = it.homepageUrl,
                        faviconUrl = it.faviconUrl,
                    )
                }
        )
    }

    suspend fun importRadioStations(export: RadioStationsExport): Int {
        val entries = export.stations
        if (entries.isEmpty()) return 0
        val current = mutableState.value.manualStations
        val currentUrls = current.map { it.streamUrl.trim().lowercase() }.toSet()
        var importedCount = 0
        val now = currentTimeMs()
        withContext(Dispatchers.Default) {
            database.transaction {
                entries.forEachIndexed { index, entry ->
                    val url = entry.streamUrl.trim()
                    if (url.lowercase() !in currentUrls) {
                        val stationId = "manual:${now + index}"
                        database.radioStationsQueries.upsertManualStation(
                            id = stationId,
                            name = entry.name.trim(),
                            streamUrl = url,
                            homepageUrl = entry.homepageUrl?.trim(),
                            faviconUrl = entry.faviconUrl?.trim(),
                            createdAtMs = now,
                            updatedAtMs = now,
                        )
                        importedCount++
                    }
                }
            }
        }
        if (importedCount > 0) {
            mutableState.value = mutableState.value.copy(manualStations = loadManualStations())
        }
        return importedCount
    }

    private suspend fun existingManualStation(id: String): ManualRadioStationRow? =
        withContext(Dispatchers.Default) {
            database.radioStationsQueries.selectManualStation(id).awaitAsOneOrNull()
        }
}

private fun ManualRadioStationRow.toRadioStation(): RadioStation =
    RadioStation(
        id = id,
        name = name,
        streamUrl = streamUrl,
        homepageUrl = homepageUrl,
        faviconUrl = faviconUrl,
        source = RadioStationSource.Manual,
    )

private fun RadioStation.toTrack(streamUrl: String): Track =
    Track(
        id = "radio:$id",
        title = name,
        artist = displaySubtitle,
        // Keep the station identity in album so live ICY title/artist swaps still show which
        // station is playing (Android Auto Now Playing / phone mini-player album line).
        album = name,
        durationMs = 0L,
        streamUrl = streamUrl,
        downloadUrl = streamUrl,
        thumbUrl = faviconUrl ?: homepageUrl?.radioHomepageFaviconUrl(),
        genre = tags?.substringBefore(',')?.takeIf { it.isNotBlank() },
        audioCodec = codec ?: streamUrl.radioStreamCodecHint(),
        bitrateKbps = bitrateKbps,
        radioNowPlayingSource = nowPlayingSource,
    )

private fun String.radioStreamCodecHint(): String? {
    val normalized = substringBefore('?').substringBefore('#').lowercase()
    val lastSegment = normalized.substringAfterLast('/')
    return when {
        normalized.endsWith(".ogg") || lastSegment == "ogg" -> "ogg"
        normalized.endsWith(".opus") || lastSegment == "opus" -> "opus"
        normalized.endsWith(".flac") || lastSegment == "flac" -> "flac"
        normalized.endsWith(".mp3") || lastSegment == "mp3" -> "mp3"
        normalized.endsWith(".aac") || normalized.endsWith(".m4a") || lastSegment == "aac" -> "aac"
        else -> null
    }
}

private fun String.radioHomepageFaviconUrl(): String? {
    val schemeEnd = indexOf("://").takeIf { it > 0 } ?: return null
    val scheme = take(schemeEnd)
    if (scheme != "http" && scheme != "https") return null
    val hostStart = schemeEnd + 3
    val hostEnd = indexOf('/', startIndex = hostStart).takeIf { it > hostStart } ?: length
    return "${take(hostEnd).trimEnd('/')}/favicon.ico"
}

private const val DirectoryPageSize = 100
private const val GlobeGeoStationLimit = 20_000
private const val GlobeGeoStationSafetyCap = 50_000

@Serializable
data class RadioStationsExport(
    val version: Int = 1,
    val stations: List<RadioStationExportEntry> = emptyList(),
)

@Serializable
data class RadioStationExportEntry(
    val name: String,
    val streamUrl: String,
    val homepageUrl: String? = null,
    val faviconUrl: String? = null,
)
