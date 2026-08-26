package com.phoebe.app.data

import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexPin
import com.phoebe.app.domain.PlexRadioStation
import com.phoebe.app.domain.PlexRadioStationCategory
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.TrackMetadataUpdate
import com.phoebe.app.platform.PhoebeLog
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

@SingleIn(AppScope::class)
@Inject
class PlexClient(
    private val httpClient: HttpClient,
) {
    /** Last base URL that accepted API calls for this server — usually plain LAN HTTP. */
    private val apiBaseCache = mutableMapOf<String, String>()
    private val baseResolveMutex = Mutex()

    /** Probes server URLs and caches the fastest reachable base before catalog API calls. */
    suspend fun prepareForCatalogRequests(server: PlexServer, token: String, timeoutMs: Long = 5_000L) {
        val candidates = server.reachableBaseUris(apiBaseCache[server.id])
        if (candidates.size == 1 && candidates.single().trimEnd('/') == server.uri.trimEnd('/')) {
            apiBaseCache[server.id] = candidates.single()
            return
        }
        resolveFastestBase(server, token, timeoutMs)
    }
    suspend fun createPin(): PlexPin {
        val response: PlexPinResponse = httpClient.post("https://plex.tv/api/v2/pins") {
            plexHeaders()
            parameter("strong", true)
        }.body()
        return PlexPin(
            id = response.id,
            code = response.code,
            authUrl = "https://app.plex.tv/auth#?clientID=$ClientIdentifier&code=${response.code}&context%5Bdevice%5D%5Bproduct%5D=Phoebe",
        )
    }

    suspend fun pollPin(pinId: Long): String? {
        val response: PlexPinResponse = httpClient.get("https://plex.tv/api/v2/pins/$pinId") {
            plexHeaders()
        }.body()
        return response.authToken
    }

    suspend fun userName(token: String): String {
        val response: PlexUserResponse = httpClient.get("https://plex.tv/api/v2/user") {
            plexHeaders(token)
        }.body()
        return response.username ?: "Plex listener"
    }

    suspend fun servers(token: String): List<PlexServer> {
        val devices: List<PlexDeviceDto> = httpClient.get("https://plex.tv/api/v2/resources") {
            plexHeaders(token)
            parameter("includeHttps", 1)
            parameter("includeRelay", 1)
        }.body()

        return devices
            .filter { "server" in it.provides }
            .mapNotNull { device ->
                val connections = device.connections
                if (connections.isEmpty()) return@mapNotNull null
                val advertised = connections.map { it.uri.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
                val local = connections.filter { it.local }.map { it.uri.trimEnd('/') }.distinct()
                val allUris = expandConnectionUris(advertised)
                val bestUri = bestReachableBaseUri(
                    advertisedUris = advertised,
                    localUris = local,
                    httpsRequired = device.httpsRequired,
                ) ?: return@mapNotNull null
                PlexServer(
                    id = device.clientIdentifier,
                    name = device.name,
                    uri = bestUri,
                    owned = device.owned,
                    connectionUris = allUris,
                    advertisedConnectionUris = advertised,
                    localConnectionUris = local,
                    accessToken = device.accessToken?.takeIf { it.isNotBlank() },
                    httpsRequired = device.httpsRequired,
                )
            }
    }

    suspend fun musicLibraries(
        server: PlexServer,
        token: String,
        baseTimeoutMs: Long = PlexRemoteSetupRequestTimeoutMs,
    ): List<MusicLibrary> {
        val response = plexGetSetup<PlexMediaContainerResponse>(
            server = server,
            token = token,
            path = "/library/sections",
            baseTimeoutMs = baseTimeoutMs,
        )
        return response.mediaContainer.directories
            .filter { it.type == "artist" }
            .map { MusicLibrary(key = it.key, title = it.title) }
    }

    suspend fun resolveApiServer(
        server: PlexServer,
        token: String,
        timeoutMs: Long = PlexRemoteSetupRequestTimeoutMs,
    ): PlexServer {
        val winner = resolveFastestBase(server, token, timeoutMs)?.trimEnd('/') ?: return server
        val current = server.uri.trimEnd('/')
        if (winner == current) return server
        return server.copy(
            uri = winner,
            connectionUris = (listOf(winner) + server.connectionUris).distinct(),
        )
    }

    suspend fun resolveFastestBase(
        server: PlexServer,
        token: String,
        timeoutMs: Long = PlexRemoteSetupRequestTimeoutMs,
    ): String? = coroutineScope {
        val candidates = server.reachableBaseUris(apiBaseCache[server.id])
        if (candidates.isEmpty()) return@coroutineScope null
        val result = CompletableDeferred<String>()
        val jobs = candidates.map { base ->
            launch {
                val ok = withTimeoutOrNull(plexBaseProbeTimeoutMs(base, timeoutMs)) {
                    runCatching {
                        val response = httpClient.get("$base/identity") {
                            plexServerAuth(token)
                            header(HttpHeaders.Accept, "application/json")
                        }
                        response.status.isSuccess()
                    }.getOrElse { error ->
                        if (error is CancellationException) throw error
                        false
                    }
                } == true
                if (ok) result.complete(base)
            }
        }
        val overall = candidates.maxOf { plexBaseProbeTimeoutMs(it, timeoutMs) } + 250L
        val winner = try {
            withTimeoutOrNull(overall) { result.await() }
        } finally {
            jobs.forEach { it.cancel() }
        }
        if (winner != null) apiBaseCache[server.id] = winner
        winner
    }

    suspend fun artists(server: PlexServer, library: MusicLibrary, token: String): List<Artist> {
        val response = plexGet<PlexMediaContainerResponse>(server, token, "/library/sections/${library.key}/all?includeCollections=1")
        val fromDirectories = response.mediaContainer.directories.map {
            Artist(
                id = it.ratingKey ?: it.key.ratingKeyFromMetadataPath() ?: it.key,
                title = it.title,
                thumbUrl = it.thumb?.let { thumb -> server.assetUrl(thumb, token) },
                albumCount = it.leafCount ?: 0,
                dateAddedMs = it.addedAt?.times(1000L),
                genre = it.primaryGenreTag(),
                mood = it.primaryMoodTag(),
                style = it.primaryStyleTag(),
                rating = it.userRating.toStarRating(),
                favorite = it.isFavoriteArtistCollection(),
                biography = it.summary,
            )
        }
        val meta = response.mediaContainer.metadata
        val typesPresent = meta.any { !it.type.isNullOrBlank() }
        val fromMetadata = meta.mapNotNull { item ->
            if (typesPresent && item.type != null && item.type != "artist") {
                return@mapNotNull null
            }
            Artist(
                id = item.ratingKey,
                title = item.title,
                thumbUrl = item.thumb?.let { thumb -> server.assetUrl(thumb, token) },
                albumCount = item.leafCount ?: 0,
                dateAddedMs = item.addedAt?.times(1000L),
                genre = item.primaryGenreTag(),
                mood = item.primaryMoodTag(),
                style = item.primaryStyleTag(),
                rating = item.userRating.toStarRating(),
                favorite = item.isFavoriteArtistCollection(),
                biography = item.summary,
            )
        }
        return (fromDirectories + fromMetadata).distinctBy { it.id }
    }

    suspend fun albums(server: PlexServer, library: MusicLibrary, token: String): List<Album> {
        val response = plexGet<PlexMediaContainerResponse>(server, token, "/library/sections/${library.key}/albums?includeCollections=1")
        val fromDirectories = response.mediaContainer.directories.mapNotNull {
            val id = it.ratingKey ?: it.key.ratingKeyFromMetadataPath()
            id?.let { albumId ->
                Album(
                    id = albumId,
                    title = it.title,
                    artist = it.parentTitle ?: "Unknown artist",
                    year = it.year,
                    thumbUrl = it.thumb?.let { thumb -> server.assetUrl(thumb, token) },
                    dateAddedMs = it.addedAt?.times(1000L),
                    genre = it.primaryGenreTag(),
                    mood = it.allMoodsJoined(),
                    style = it.allStylesJoined(),
                    rating = it.userRating.toStarRating(),
                    favorite = it.isFavoriteAlbumCollection(),
                    description = it.summary,
                    recordLabel = it.studio,
                    releaseDate = it.originallyAvailableAt,
                )
            }
        }
        val fromMetadata = response.mediaContainer.metadata.map {
            Album(
                id = it.ratingKey,
                title = it.title,
                artist = it.parentTitle ?: "Unknown artist",
                year = it.year,
                thumbUrl = it.thumb?.let { thumb -> server.assetUrl(thumb, token) },
                dateAddedMs = it.addedAt?.times(1000L),
                genre = it.primaryGenreTag(),
                mood = it.allMoodsJoined(),
                style = it.allStylesJoined(),
                rating = it.userRating.toStarRating(),
                favorite = it.isFavoriteAlbumCollection(),
                description = it.summary,
                recordLabel = it.studio,
                releaseDate = it.originallyAvailableAt,
            )
        }
        return (fromDirectories + fromMetadata).distinctBy { it.id }
    }

    suspend fun playlists(server: PlexServer, token: String): List<Playlist> {
        val response = plexGet<PlexMediaContainerResponse>(server, token, "/playlists")
        return response.mediaContainer.metadata.map {
            val thumb = it.thumb ?: it.composite
            Playlist(
                id = it.ratingKey,
                title = it.title,
                trackCount = it.leafCount ?: 0,
                key = it.key,
                thumbUrl = thumb?.let { t -> server.assetUrl(t, token) },
                rating = it.userRating.toStarRating(),
            )
        }
    }

    suspend fun playlistTracks(server: PlexServer, playlist: Playlist, token: String): List<Track> {
        val path = playlist.key?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("/")) it else "/$it"
        } ?: "/playlists/${playlist.id}/items"
        val response = plexGet<PlexMediaContainerResponse>(server, token, path)
        return response.mediaContainer.metadata.mapNotNull { it.toTrack(server, token) }
    }

    suspend fun children(server: PlexServer, parentKey: String, token: String): List<Track> {
        val ratingKey = parentKey.removePrefix("plex:")
        val response = plexGet<PlexMediaContainerResponse>(server, token, "/library/metadata/$ratingKey/children")
        return response.mediaContainer.metadata.mapNotNull { it.toTrack(server, token) }
    }

    suspend fun artistDetails(server: PlexServer, ratingKey: String, token: String): Artist? {
        val item = metadataDetails(server, ratingKey, token).firstOrNull() ?: return null
        return Artist(
            id = item.ratingKey,
            title = item.title,
            thumbUrl = item.thumb?.let { thumb -> server.assetUrl(thumb, token) },
            albumCount = item.leafCount ?: 0,
            dateAddedMs = item.addedAt?.times(1000L),
            genre = item.primaryGenreTag(),
            mood = item.primaryMoodTag(),
            style = item.primaryStyleTag(),
            rating = item.userRating.toStarRating(),
            favorite = item.isFavoriteArtistCollection(),
        )
    }

    suspend fun albumDetails(server: PlexServer, ratingKey: String, token: String): Album? {
        val item = metadataDetails(server, ratingKey, token).firstOrNull() ?: return null
        return Album(
            id = item.ratingKey,
            title = item.title,
            artist = item.parentTitle ?: "Unknown artist",
            year = item.year,
            thumbUrl = item.thumb?.let { thumb -> server.assetUrl(thumb, token) },
            dateAddedMs = item.addedAt?.times(1000L),
            genre = item.primaryGenreTag(),
            mood = item.allMoodsJoined(),
            style = item.allStylesJoined(),
            rating = item.userRating.toStarRating(),
            favorite = item.isFavoriteAlbumCollection(),
            description = item.summary,
            recordLabel = item.studio,
            releaseDate = item.originallyAvailableAt,
        )
    }

    suspend fun trackDetails(server: PlexServer, ratingKey: String, token: String): Track? =
        metadataDetails(server, ratingKey, token).firstOrNull()?.toTrack(server, token)

    suspend fun popularTracksForArtist(
        server: PlexServer,
        library: MusicLibrary,
        ratingKey: String,
        token: String,
        limit: Int = 12,
    ): List<Track> {
        if (ratingKey.isBlank() || limit <= 0) return emptyList()
        val response: PlexMediaContainerResponse = withReachableBase(server) { base ->
            val response = httpClient.get("$base/library/sections/${library.key}/all") {
                timeout { requestTimeoutMillis = PlexPopularTracksRequestTimeoutMs }
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
                header("X-Plex-Container-Start", "0")
                header("X-Plex-Container-Size", limit.toString())
                parameter("X-Plex-Container-Start", 0)
                parameter("X-Plex-Container-Size", limit)
                parameter("type", PlexTrackType)
                parameter("artist.id", ratingKey)
                parameter("album.subformat!", "Compilation,Live")
                parameter("group", "title")
                parameter("ratingCount>>", 0)
                parameter("sort", "ratingCount:desc")
                parameter("limit", limit)
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                error("Plex artist popular tracks failed (${response.status.value}) via $base: ${body.take(200)}")
            }
            response.body()
        }
        return response.mediaContainer.metadata.mapNotNull { it.toTrack(server, token) }
    }

    suspend fun popularTracksForLibrary(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        limit: Int = 50,
    ): List<Track> {
        if (limit <= 0) return emptyList()
        val response: PlexMediaContainerResponse = withReachableBase(server) { base ->
            val response = httpClient.get("$base/library/sections/${library.key}/all") {
                timeout { requestTimeoutMillis = PlexPopularTracksRequestTimeoutMs }
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
                header("X-Plex-Container-Start", "0")
                header("X-Plex-Container-Size", limit.toString())
                parameter("X-Plex-Container-Start", 0)
                parameter("X-Plex-Container-Size", limit)
                parameter("type", PlexTrackType)
                parameter("album.subformat!", "Compilation,Live")
                parameter("ratingCount>>", 0)
                parameter("sort", "ratingCount:desc")
                parameter("limit", limit)
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                error("Plex library popular tracks failed (${response.status.value}) via $base: ${body.take(200)}")
            }
            response.body()
        }
        return response.mediaContainer.metadata.mapNotNull { it.toTrack(server, token) }
    }

    suspend fun similarArtistsForArtist(
        server: PlexServer,
        ratingKey: String,
        token: String,
        limit: Int = 20,
    ): List<Artist> {
        if (ratingKey.isBlank() || limit <= 0) return emptyList()
        val body = plexGetRaw(server, token, "/library/metadata/$ratingKey/similar?count=$limit")
        val root = runCatching { PlexJson.decodeFromString<JsonElement>(body) }
            .onFailure { error ->
                PhoebeLog.d("PlexClient") { "similarArtistsForArtist failed to parse response for $ratingKey: ${error.message}" }
            }
            .getOrNull()
            ?: return emptyList()
        return root
            .similarArtistObjects()
            .mapNotNull { artistJson ->
                artistJson.toRawArtist { thumb -> server.assetUrl(thumb, token) }
            }
            .filter { artist -> artist.id != ratingKey }
            .distinctBy { artist -> artist.id.takeIf { it.isNotBlank() } ?: artist.title.normalizedArtistLookupKey() }
            .take(limit)
            .toList()
            .also { artists ->
                if (artists.isEmpty()) {
                    PhoebeLog.d("PlexClient") { "similarArtistsForArtist found no similar artists for $ratingKey bodyPrefix=${body.take(360)}" }
                }
            }
    }

    suspend fun similarTracksForMetadata(
        server: PlexServer,
        ratingKey: String,
        token: String,
        limit: Int = 100,
    ): List<Track> {
        if (ratingKey.isBlank() || limit <= 0) return emptyList()
        val body = runCatching {
            plexGetRaw(server, token, "/library/metadata/$ratingKey/similar?count=$limit")
        }.getOrElse { error ->
            PhoebeLog.d("PlexClient") { "similarTracksForMetadata failed for $ratingKey: ${error.message}" }
            return emptyList()
        }
        val response = runCatching {
            PlexJson.decodeFromString(PlexMediaContainerResponse.serializer(), body)
        }.onFailure { error ->
            PhoebeLog.d("PlexClient") {
                "similarTracksForMetadata decode failed for $ratingKey: ${error.message}; body=${body.take(400)}"
            }
        }.getOrNull() ?: return emptyList()
        return hydratePlayQueueTracks(server, token, response.mediaContainer.metadata).take(limit)
    }

    suspend fun trackPlaybackStat(server: PlexServer, ratingKey: String, token: String): PlexTrackPlaybackStat? {
        val body = runCatching {
            plexGetRaw(server, token, "/library/metadata/$ratingKey?includeUserState=1")
        }.getOrElse { error ->
            PhoebeLog.d("PlexClient") { "track playback stat fetch failed for '$ratingKey': ${error.message}" }
            return null
        }
        return parseTrackPlaybackStatsFromBody(body).firstOrNull()
    }

    suspend fun musicStations(server: PlexServer, library: MusicLibrary, token: String): List<PlexRadioStation> {
        val path = "/hubs/sections/${library.key}"
        val body = plexGetRaw(server, token, path)
        val dtoStations = runCatching {
            val response = PlexJson.decodeFromString(PlexMediaContainerResponse.serializer(), body)
            val stationHub = response.mediaContainer.hubs.firstOrNull { hub ->
                hub.context == MusicStationsHubContext ||
                    hub.hubIdentifier == MusicStationsHubIdentifier
            }
            (stationHub?.metadata.orEmpty() + stationHub?.directories.orEmpty())
                .mapNotNull { station ->
                    station.toRadioStation(PlexRadioStationCategory.Library) { thumb -> server.assetUrl(thumb, token) }
                }
                .filter { station -> station.key.isPlexLibrarySectionStationKey(library.key) }
        }.onFailure { error ->
            PhoebeLog.d("PlexClient") { "musicStations DTO parse failed for library ${library.key}: ${error.message}" }
        }.getOrDefault(emptyList())
        val rawStations = runCatching {
            PlexJson.decodeFromString<JsonElement>(body)
                .libraryStationObjects(library.key)
                .mapNotNull { stationJson ->
                    stationJson.toRadioStation(
                        category = PlexRadioStationCategory.Library,
                        defaultTitle = "Plex Radio",
                        assetUrl = { thumb -> server.assetUrl(thumb, token) },
                    )
                }
                .toList()
        }.onFailure { error ->
            PhoebeLog.d("PlexClient") { "musicStations raw parse failed for library ${library.key}: ${error.message}" }
        }.getOrDefault(emptyList())
        val stations = (dtoStations + rawStations)
            .distinctBy { it.key }
        if (stations.isEmpty()) {
            PhoebeLog.d("PlexClient") { "musicStations found no station hub for library ${library.key} bodyPrefix=${body.take(360)}" }
        }
        return stations
    }

    suspend fun artistStation(server: PlexServer, ratingKey: String, token: String): PlexRadioStation? {
        val path = "/library/metadata/$ratingKey?includeStations=1"
        val body = plexGetRaw(server, token, path)
        val root = runCatching { PlexJson.decodeFromString<JsonElement>(body) }
            .onFailure { error ->
                PhoebeLog.d("PlexClient") { "artistStation failed to parse response for $ratingKey: ${error.message}" }
            }
            .getOrNull()
            ?: return null
        val station = root
            .artistStationObjects()
            .mapNotNull { stationJson ->
                stationJson.toRadioStation(
                    category = PlexRadioStationCategory.Artist,
                    defaultTitle = "Artist Radio",
                    assetUrl = { thumb -> server.assetUrl(thumb, token) },
                )
            }
            .distinctBy { it.key }
            .firstOrNull()
        if (station == null) {
            PhoebeLog.d("PlexClient") { "artistStation found no station metadata for $ratingKey bodyPrefix=${body.take(360)}" }
        }
        return station
    }

    suspend fun createStationPlayQueue(
        server: PlexServer,
        token: String,
        machineIdentifier: String,
        stationKey: String,
    ): List<Track> {
        var lastError: Throwable? = null
        for (key in plexLibraryStationKeysToTry(stationKey)) {
            val metadata = runCatching {
                postStationPlayQueue(server, token, machineIdentifier, key)
            }.getOrElse { error ->
                lastError = error
                PhoebeLog.d("PlexClient") { "createStationPlayQueue failed for $key: ${error.message}" }
                null
            } ?: continue
            if (metadata.isEmpty()) continue
            val tracks = hydratePlayQueueTracks(server, token, metadata)
            if (tracks.isNotEmpty()) return tracks
            throw IllegalStateException("Plex radio returned no playable songs.")
        }
        throw lastError ?: IllegalStateException("Plex radio returned no playable songs.")
    }

    private suspend fun postStationPlayQueue(
        server: PlexServer,
        token: String,
        machineIdentifier: String,
        stationKey: String,
    ): List<PlexMetadataDto> {
        val uri = "server://$machineIdentifier/$LibraryIdentifier${stationKey.normalizedStationKey()}"
        var lastError: Throwable? = null
        for (base in server.reachableBaseUris(apiBaseCache[server.id])) {
            val response = runCatching {
                val httpResponse = httpClient.post("$base/playQueues") {
                    plexTimelineAuth(token)
                    header(HttpHeaders.Accept, "application/json")
                    parameter("type", "audio")
                    parameter("uri", uri)
                    parameter("continuous", 1)
                }
                val body = httpResponse.bodyAsText()
                if (!httpResponse.status.isSuccess()) {
                    PhoebeLog.d("PlexClient") { "createStationPlayQueue failed -> HTTP ${httpResponse.status.value}: ${body.take(300)}" }
                    error("Plex radio failed (${httpResponse.status.value}): ${body.take(200)}")
                }
                runCatching {
                    PlexJson.decodeFromString(PlexMediaContainerResponse.serializer(), body)
                }.getOrElse { error ->
                    PhoebeLog.d("PlexClient") { "createStationPlayQueue decode failed: ${error.message}; body=${body.take(400)}" }
                    throw IllegalStateException("Plex radio response was unreadable: ${error.message}", error)
                }
            }.getOrElse { error ->
                lastError = error
                if (error.message?.contains("401") == true) return@getOrElse null
                null
            } ?: continue
            apiBaseCache[server.id] = base
            return expandedPlayQueueMetadata(base, token, response.mediaContainer)
        }
        throw lastError ?: IllegalStateException("Plex radio returned no playable songs.")
    }

    private suspend fun expandedPlayQueueMetadata(
        base: String,
        token: String,
        initial: PlexMediaContainer,
    ): List<PlexMetadataDto> {
        val playQueueId = initial.playQueueId ?: return initial.metadata.cappedPlayQueueMetadata()
        val totalCount = initial.playQueueTotalCount ?: initial.totalSize
        val requestedWindow = (totalCount ?: PlexPlayQueueDefaultWindowSize)
            .coerceAtLeast(initial.metadata.size)
            .coerceAtLeast(PlexPlayQueueDefaultWindowSize)
            .coerceAtMost(PlexPlayQueueMaxWindowSize)
        if (requestedWindow <= initial.metadata.size) return initial.metadata.cappedPlayQueueMetadata()
        val expanded = runCatching {
            fetchPlayQueueWindow(
                base = base,
                token = token,
                playQueueId = playQueueId,
                centerItemId = initial.playQueueSelectedItemId,
                window = requestedWindow,
                includeBefore = true,
                includeAfter = true,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            PhoebeLog.d("PlexClient") {
                "expanded playQueue fetch failed for $playQueueId: ${error.message}"
            }
        }.getOrNull()
        val expandedMetadata = expanded?.mediaContainer?.metadata.orEmpty()
        return when {
            expandedMetadata.size > initial.metadata.size -> expandedMetadata.cappedPlayQueueMetadata()
            expandedMetadata.isNotEmpty() && initial.metadata.isEmpty() -> expandedMetadata.cappedPlayQueueMetadata()
            else -> initial.metadata.cappedPlayQueueMetadata()
        }
    }

    private fun List<PlexMetadataDto>.cappedPlayQueueMetadata(): List<PlexMetadataDto> =
        take(PlexPlayQueueMaxWindowSize)

    private suspend fun fetchPlayQueueWindow(
        base: String,
        token: String,
        playQueueId: Long,
        centerItemId: Long?,
        window: Int,
        includeBefore: Boolean,
        includeAfter: Boolean,
    ): PlexMediaContainerResponse {
        val response = httpClient.get("$base/playQueues/$playQueueId") {
            plexTimelineAuth(token)
            header(HttpHeaders.Accept, "application/json")
            parameter("window", window)
            parameter("includeBefore", if (includeBefore) 1 else 0)
            parameter("includeAfter", if (includeAfter) 1 else 0)
            centerItemId?.let { parameter("center", it) }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            error("Plex play queue window failed (${response.status.value}): ${body.take(200)}")
        }
        return runCatching {
            PlexJson.decodeFromString(PlexMediaContainerResponse.serializer(), body)
        }.getOrElse { error ->
            PhoebeLog.d("PlexClient") {
                "playQueue window decode failed for $playQueueId: ${error.message}; body=${body.take(400)}"
            }
            throw IllegalStateException("Plex play queue response was unreadable: ${error.message}", error)
        }
    }

    private suspend fun hydratePlayQueueTracks(
        server: PlexServer,
        token: String,
        metadata: List<PlexMetadataDto>,
    ): List<Track> {
        if (metadata.isEmpty()) return emptyList()
        val inline = metadata.map { it.toTrack(server, token) }
        val missingKeys = metadata.mapIndexedNotNull { index, item ->
            if (inline[index] != null) null else item.ratingKey.takeIf { it.isNotBlank() }
        }
        if (missingKeys.isEmpty()) return inline.filterNotNull()
        val fetchedByKey = fetchTrackDetailsBatch(server, token, missingKeys)
        return metadata.mapIndexed { index, item ->
            inline[index] ?: item.ratingKey.takeIf { it.isNotBlank() }?.let { fetchedByKey[it] }
        }.filterNotNull()
    }

    private suspend fun fetchTrackDetailsBatch(
        server: PlexServer,
        token: String,
        ratingKeys: List<String>,
    ): Map<String, Track> = coroutineScope {
        if (ratingKeys.isEmpty()) return@coroutineScope emptyMap()
        ratingKeys.distinct()
            .chunked(PlayQueueMetadataBatchSize)
            .map { chunk ->
                async {
                    val joined = chunk.joinToString(",")
                    val batchItems = runCatching { metadataDetails(server, joined, token) }.getOrDefault(emptyList())
                    if (batchItems.isNotEmpty()) {
                        batchItems.mapNotNull { dto ->
                            dto.toTrack(server, token)?.let { dto.ratingKey to it }
                        }
                    } else {
                        chunk.mapNotNull { ratingKey ->
                            runCatching { trackDetails(server, ratingKey, token) }.getOrNull()
                                ?.let { ratingKey to it }
                        }
                    }
                }
            }
            .awaitAll()
            .flatten()
            .toMap()
    }

    suspend fun setFavoriteArtistCollection(
        server: PlexServer,
        token: String,
        library: MusicLibrary,
        ratingKey: String,
        favorite: Boolean,
    ) {
        setMetadataCollectionTag(
            server = server,
            token = token,
            library = library,
            ratingKey = ratingKey,
            typeId = PlexArtistType,
            collectionName = FavoriteArtistsCollection,
            legacyCollectionName = LegacyFavoriteArtistsCollection,
            present = favorite,
        )
    }

    suspend fun setFavoriteAlbumCollection(
        server: PlexServer,
        token: String,
        library: MusicLibrary,
        ratingKey: String,
        favorite: Boolean,
    ) {
        setMetadataCollectionTag(
            server = server,
            token = token,
            library = library,
            ratingKey = ratingKey,
            typeId = PlexAlbumType,
            collectionName = FavoriteAlbumsCollection,
            legacyCollectionName = LegacyFavoriteAlbumsCollection,
            present = favorite,
        )
    }

    suspend fun collectionFilterChoices(
        server: PlexServer,
        library: MusicLibrary,
        target: PlexCollectionTarget,
        facet: PlexCollectionFacet,
        token: String,
    ): List<PlexFilterChoice> {
        val field = facet.filterField
        val type = target.typeId
        val discoveredPath = discoverCollectionFilterPath(server, library, target, facet, token)
        val paths = listOfNotNull(
            discoveredPath,
            "/library/sections/${library.key}/${target.libtype}.$field?type=$type",
            "/library/sections/${library.key}/$field?type=$type",
            "/library/sections/${library.key}/$field",
        ).distinct()
        PhoebeLog.d("PlexCollections") {
            "filter paths target=${target.name} facet=${facet.name} discovered=$discoveredPath candidates=$paths"
        }
        for (path in paths) {
            val choiceField = path.filterFieldFromCollectionPath() ?: field
            val choices = runCatching {
                val body = plexGetRaw(server, token, path)
                val response = PlexJson.decodeFromString(PlexMediaContainerResponse.serializer(), body)
                PhoebeLog.d("PlexCollections") {
                    "filter choices raw path=$path dirs=${response.mediaContainer.directories.size} meta=${response.mediaContainer.metadata.size} bodyPrefix=${body.take(360)}"
                }
                response.mediaContainer.directories.mapNotNull { it.toFilterChoice(choiceField, library.key, type) }
            }.onFailure { error ->
                PhoebeLog.d("PlexCollections") {
                    "filter choices failed target=${target.name} facet=${facet.name} path=$path error=${error.message}"
                }
            }.getOrDefault(emptyList())
            PhoebeLog.d("PlexCollections") {
                "filter choices path=$path count=${choices.size} sample=${choices.take(5).map { "${it.title}:${it.key}:${it.fastKey}" }}"
            }
            if (choices.isNotEmpty()) return choices.distinctBy { it.key to it.title }
        }
        return emptyList()
    }

    suspend fun collectionFilterItems(
        server: PlexServer,
        library: MusicLibrary,
        target: PlexCollectionTarget,
        facet: PlexCollectionFacet,
        choice: PlexFilterChoice,
        token: String,
    ): List<String> {
        val field = choice.filterField?.takeIf { it.isNotBlank() } ?: facet.filterField
        val scopedFields = when (target) {
            PlexCollectionTarget.Artists -> listOf("artist.${facet.filterField}", "album.${facet.filterField}", "track.${facet.filterField}")
            PlexCollectionTarget.Albums -> listOf("album.${facet.filterField}", "track.${facet.filterField}")
        }
        val fields = (listOf(field) + scopedFields + facet.filterField).distinct()
        val normalizedKey = choice.key.filterChoiceValue()
        val values = listOf(normalizedKey, choice.key, choice.title)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val typedPaths = fields.flatMap { candidateField ->
            values.flatMap { candidateValue ->
                val encoded = candidateValue.encodeURLParameter()
                buildList {
                    add("/library/sections/${library.key}/all?type=${target.typeId}&$candidateField=$encoded")
                    add("/library/sections/${library.key}/${target.libtype}s?$candidateField=$encoded")
                    if (target == PlexCollectionTarget.Albums && candidateField.isTrackCollectionField(facet)) {
                        add("/library/sections/${library.key}/all?type=10&$candidateField=$encoded")
                        if (candidateField.startsWith("track.")) {
                            add("/library/sections/${library.key}/all?type=10&${facet.filterField}=$encoded")
                        }
                    }
                }
            }
        }
        val paths = (
            typedPaths +
                listOfNotNull(choice.fastKey?.takeIf { it.isNotBlank() && it.isTypedCollectionFastKey(target) })
            ).distinct()
        for (path in paths) {
            val items = collectionFilterItemsAtPath(server, target, facet, choice, token, path)
            if (items.isNotEmpty()) return items
        }
        PhoebeLog.d("PlexCollections") {
            "filter items exhausted target=${target.name} facet=${facet.name} choice='${choice.title}' paths=$paths"
        }
        return emptyList()
    }

    private suspend fun collectionFilterItemsAtPath(
        server: PlexServer,
        target: PlexCollectionTarget,
        facet: PlexCollectionFacet,
        choice: PlexFilterChoice,
        token: String,
        path: String,
    ): List<String> {
        val response = runCatching {
            plexGet<PlexMediaContainerResponse>(server, token, path)
        }.onFailure { error ->
            PhoebeLog.d("PlexCollections") {
                "filter items failed target=${target.name} facet=${facet.name} choice='${choice.title}' path=$path error=${error.message}"
            }
        }.getOrNull() ?: return emptyList()
        val items = when (target) {
            PlexCollectionTarget.Artists -> response.mediaContainer.directories.mapNotNull { it.ratingKey ?: it.key.ratingKeyFromMetadataPath() ?: it.key.takeIf { key -> key.isNotBlank() } } +
                response.mediaContainer.metadata.mapNotNull { it.ratingKey.takeIf { key -> key.isNotBlank() } }
            PlexCollectionTarget.Albums -> response.mediaContainer.directories.mapNotNull { it.ratingKey ?: it.key.ratingKeyFromMetadataPath() } +
                response.mediaContainer.metadata.mapNotNull { item ->
                    when (item.type) {
                        "track" -> item.parentRatingKey
                        else -> item.ratingKey
                    }?.takeIf { key -> key.isNotBlank() }
                }
        }.distinct()
        PhoebeLog.d("PlexCollections") {
            "filter items path=$path target=${target.name} facet=${facet.name} choice='${choice.title}' dirs=${response.mediaContainer.directories.size} meta=${response.mediaContainer.metadata.size} items=${items.size} sample=$items"
        }
        return items
    }

    suspend fun playbackHistoryPage(
        server: PlexServer,
        token: String,
        library: MusicLibrary,
        minViewedAtMs: Long?,
        start: Int,
        size: Int,
    ): PlexPlaybackHistoryPage {
        val response: PlexMediaContainerResponse = withReachableBase(server) { base ->
            val response = httpClient.get("$base/status/sessions/history/all") {
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
                header("X-Plex-Container-Start", start.toString())
                header("X-Plex-Container-Size", size.toString())
                parameter("X-Plex-Container-Start", start)
                parameter("X-Plex-Container-Size", size)
                parameter("librarySectionID", library.key)
                parameter("sort", "viewedAt:desc")
                minViewedAtMs?.let { parameter("viewedAt", "viewedAt>=${it / 1000L}") }
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                error("Plex playback history failed (${response.status.value}) via $base: ${body.take(200)}")
            }
            val body = runCatching { response.bodyAsText() }.getOrElse { error ->
                throw IllegalStateException("Plex playback history body could not be read via $base: ${error.message}", error)
            }
            if (body.isBlank()) {
                error("Plex playback history returned an empty response via $base")
            }
            runCatching {
                PlexJson.decodeFromString(PlexMediaContainerResponse.serializer(), body)
            }.getOrElse { error ->
                PhoebeLog.d("PlexClient") {
                    "playback history decode failed via $base: ${error.message}; body=${body.take(400)}"
                }
                throw IllegalStateException("Plex playback history response was unreadable via $base: ${error.message}", error)
            }
        }
        val container = response.mediaContainer
        return PlexPlaybackHistoryPage(
            entries = container.metadata.mapNotNull { it.toPlaybackHistoryEntry() },
            offset = container.offset ?: start,
            size = container.size,
            totalSize = container.totalSize,
        )
    }

    suspend fun libraryTracksPage(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        start: Int,
        size: Int,
        timeoutMs: Long? = null,
    ): PlexTrackPage {
        val response: PlexMediaContainerResponse = withReachableBase(server) { base ->
            val response = httpClient.get("$base/library/sections/${library.key}/all") {
                timeoutMs?.let { timeout { requestTimeoutMillis = it } }
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
                header("X-Plex-Container-Start", start.toString())
                header("X-Plex-Container-Size", size.toString())
                parameter("X-Plex-Container-Start", start)
                parameter("X-Plex-Container-Size", size)
                parameter("type", PlexTrackType)
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                error("Plex track page failed (${response.status.value}) via $base: ${body.take(200)}")
            }
            response.body()
        }
        return response.toTrackPage(server, token, requestedStart = start, requestedSize = size)
    }

    suspend fun trackPlaybackStatsPage(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        start: Int,
        size: Int,
    ): List<PlexTrackPlaybackStat> {
        val strategies = listOf(
            TrackStatsQuery(includeUserState = true, sort = "viewCount:desc", playedOnly = true),
            TrackStatsQuery(includeUserState = true, sort = "viewCount:desc", playedOnly = false),
            TrackStatsQuery(includeUserState = true, sort = null, playedOnly = false),
        )
        for (strategy in strategies) {
            val stats = try {
                fetchTrackPlaybackStatsPage(
                    server = server,
                    library = library,
                    token = token,
                    start = start,
                    size = size,
                    query = strategy,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                PhoebeLog.d("PlexClient") {
                    "track playback stats query failed (start=$start sort=${strategy.sort} playedOnly=${strategy.playedOnly}): ${error.message}"
                }
                emptyList()
            }
            if (stats.isNotEmpty()) return stats
        }
        return emptyList()
    }

    suspend fun recentTrackPlaybackStatsPage(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        start: Int,
        size: Int,
    ): List<PlexTrackPlaybackStat> = fetchTrackPlaybackStatsPage(
        server = server,
        library = library,
        token = token,
        start = start,
        size = size,
        query = TrackStatsQuery(
            includeUserState = false,
            sort = "lastViewedAt:desc",
            playedOnly = true,
            contentDirectoryId = library.key,
        ),
    )

    private data class TrackStatsQuery(
        val includeUserState: Boolean,
        val sort: String?,
        val playedOnly: Boolean,
        val contentDirectoryId: String? = null,
    )

    private suspend fun fetchTrackPlaybackStatsPage(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        start: Int,
        size: Int,
        query: TrackStatsQuery,
    ): List<PlexTrackPlaybackStat> {
        val body = withReachableBase(server) { base ->
            if (query.contentDirectoryId != null) {
                PhoebeLog.d("PlexClient") {
                    "recent track playback stats request base=$base library=${library.key} start=$start size=$size"
                }
            }
            val response = httpClient.get("$base/library/sections/${library.key}/all") {
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
                header("X-Plex-Container-Start", start.toString())
                header("X-Plex-Container-Size", size.toString())
                parameter("X-Plex-Container-Start", start)
                parameter("X-Plex-Container-Size", size)
                parameter("type", PlexTrackType)
                if (query.includeUserState) {
                    parameter("includeUserState", "1")
                }
                query.sort?.let { parameter("sort", it) }
                if (query.playedOnly) {
                    parameter("viewCount>=", 1)
                }
                query.contentDirectoryId?.let { parameter("contentDirectoryID", it) }
            }
            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                error("Plex track playback stats failed (${response.status.value}) via $base: ${errorBody.take(200)}")
            }
            val responseBody = runCatching { response.bodyAsText() }.getOrElse { error ->
                throw IllegalStateException("Plex track playback stats body could not be read via $base: ${error.message}", error)
            }
            if (responseBody.isBlank()) {
                error("Plex track playback stats returned an empty response via $base")
            }
            if (query.contentDirectoryId != null) {
                PhoebeLog.d("PlexClient") {
                    "recent track playback stats response base=$base bytes=${responseBody.length}"
                }
            }
            responseBody
        }
        return parseTrackPlaybackStatsFromBody(body)
    }

    suspend fun tracksForYearRange(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        startYear: Int,
        endYear: Int,
        start: Int = 0,
        size: Int = 500,
        limit: Int? = null,
    ): List<Track> {
        val response: PlexMediaContainerResponse = withReachableBase(server) { base ->
            val response = httpClient.get("$base/library/sections/${library.key}/all") {
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
                header("X-Plex-Container-Start", start.toString())
                header("X-Plex-Container-Size", size.toString())
                parameter("X-Plex-Container-Start", start)
                parameter("X-Plex-Container-Size", size)
                parameter("type", PlexTrackType)
                parameter("year>=", startYear)
                parameter("year<=", endYear)
                limit?.let { parameter("limit", it) }
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                error("Plex track year search failed (${response.status.value}) via $base: ${body.take(200)}")
            }
            response.body()
        }
        return response.mediaContainer.metadata.mapNotNull { it.toTrack(server, token) }
    }

    suspend fun tracksForYearRangePage(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        startYear: Int,
        endYear: Int,
        start: Int,
        size: Int,
        limit: Int? = null,
    ): PlexTrackPage {
        val response: PlexMediaContainerResponse = withReachableBase(server) { base ->
            val response = httpClient.get("$base/library/sections/${library.key}/all") {
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
                header("X-Plex-Container-Start", start.toString())
                header("X-Plex-Container-Size", size.toString())
                parameter("X-Plex-Container-Start", start)
                parameter("X-Plex-Container-Size", size)
                parameter("type", PlexTrackType)
                parameter("year>=", startYear)
                parameter("year<=", endYear)
                limit?.let { parameter("limit", it) }
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                error("Plex track year page failed (${response.status.value}) via $base: ${body.take(200)}")
            }
            response.body()
        }
        return response.toTrackPage(server, token, requestedStart = start, requestedSize = size)
    }

    /**
     * Paginated tracks matching a Plex collection facet value (mood, style, etc.) via filter API paths.
     * Tries track-scoped filter fields until a path returns results.
     */
    suspend fun tracksForCollectionFacetPage(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        facet: PlexCollectionFacet,
        choice: PlexFilterChoice,
        start: Int,
        size: Int,
        limit: Int? = null,
    ): PlexTrackPage {
        val paths = collectionFacetTrackFilterPaths(library, facet, choice)
        for (path in paths) {
            val page = runCatching {
                tracksForFilterPathPage(server, token, path, start, size, limit)
            }.onFailure { error ->
                PhoebeLog.d("PlexCollections") {
                    "collection track page failed facet=${facet.name} choice='${choice.title}' path=$path error=${error.message}"
                }
            }.getOrNull() ?: continue
            if (page.tracks.isNotEmpty() || page.hasMore) {
                PhoebeLog.d("PlexCollections") {
                    "collection track page facet=${facet.name} choice='${choice.title}' path=$path tracks=${page.tracks.size} offset=${page.offset}"
                }
                return page
            }
        }
        return PlexTrackPage(tracks = emptyList(), offset = start, size = 0, totalSize = start)
    }

    private suspend fun tracksForFilterPathPage(
        server: PlexServer,
        token: String,
        path: String,
        start: Int,
        size: Int,
        limit: Int? = null,
    ): PlexTrackPage {
        val response: PlexMediaContainerResponse = withReachableBase(server) { base ->
            val response = httpClient.get("$base$path") {
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
                header("X-Plex-Container-Start", start.toString())
                header("X-Plex-Container-Size", size.toString())
                parameter("X-Plex-Container-Start", start)
                parameter("X-Plex-Container-Size", size)
                limit?.let { parameter("limit", it) }
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                error("Plex collection track page failed (${response.status.value}) via $base: ${body.take(200)}")
            }
            response.body()
        }
        return response.toTrackPage(server, token, requestedStart = start, requestedSize = size)
    }

    private fun collectionFacetTrackFilterPaths(
        library: MusicLibrary,
        facet: PlexCollectionFacet,
        choice: PlexFilterChoice,
    ): List<String> {
        val field = choice.filterField?.takeIf { it.isNotBlank() } ?: facet.filterField
        val scopedFields = listOf(
            field,
            "track.${facet.filterField}",
            "album.${facet.filterField}",
            facet.filterField,
        ).distinct()
        val normalizedKey = choice.key.filterChoiceValue()
        val values = listOf(normalizedKey, choice.key, choice.title)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val typedPaths = scopedFields.flatMap { candidateField ->
            values.flatMap { candidateValue ->
                val encoded = candidateValue.encodeURLParameter()
                buildList {
                    add("/library/sections/${library.key}/all?type=$PlexTrackType&$candidateField=$encoded")
                    if (candidateField.startsWith("track.") || candidateField == facet.filterField) {
                        add("/library/sections/${library.key}/all?type=$PlexTrackType&${facet.filterField}=$encoded")
                    }
                }
            }
        }
        val fastKeyPaths = listOfNotNull(
            choice.fastKey
                ?.takeIf { it.isNotBlank() }
                ?.takeIf { key ->
                    key.contains("type=$PlexTrackType") ||
                        key.contains("type=10") ||
                        key.contains("all?")
                },
        )
        return (typedPaths + fastKeyPaths).distinct()
    }

    /**
     * Fetches the server's *canonical* machine identifier via `/identity`. This is the value
     * Plex expects inside playlist URIs of the form `server://{X}/com.plexapp.plugins.library/…`.
     *
     * For owned servers `clientIdentifier` from `plex.tv/api/v2/resources` is usually the same
     * thing, but for relay / shared connections those two ids can diverge, in which case
     * playlist mutations silently no-op. Calling `/identity` is the only reliable way to be
     * sure we have the right id.
     */
    suspend fun machineIdentifier(server: PlexServer, token: String): String {
        val response: PlexMediaContainerResponse = plexGet(server, token, "/identity")
        return response.mediaContainer.machineIdentifier?.takeIf { it.isNotBlank() } ?: server.id
    }

    /**
     * Create a brand-new audio playlist on the Plex server. When [ratingKeys] is non-empty
     * the playlist is seeded with those items; otherwise an empty smart=0 playlist is created
     * scoped to [library] (Plex requires *some* `uri` parameter even for empty playlists, so
     * we point at the library section to satisfy that).
     *
     * [machineIdentifier] must be the value returned by [machineIdentifier]; we accept it as a
     * parameter so callers can cache it across multiple playlist mutations rather than hitting
     * `/identity` every time.
     *
     * Returns the freshly-created [Playlist] parsed from the server's response.
     */
    suspend fun createPlaylist(
        server: PlexServer,
        token: String,
        library: MusicLibrary,
        machineIdentifier: String,
        title: String,
        ratingKeys: List<String>,
    ): Playlist {
        val uri = if (ratingKeys.isNotEmpty()) {
            metadataUri(machineIdentifier, ratingKeys)
        } else {
            "server://$machineIdentifier/com.plexapp.plugins.library/library/sections/${library.key}"
        }
        val response = withReachableBase(server) { base ->
            httpClient.post("$base/playlists") {
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
                parameter("type", "audio")
                parameter("title", title)
                parameter("smart", 0)
                parameter("uri", uri)
            }
        }
        val parsed = parsePlaylistResponse(response, "createPlaylist", title)
        val meta = parsed.mediaContainer.metadata.firstOrNull()
            ?: error("Plex returned an empty container when creating playlist '$title'")
        val thumb = meta.thumb ?: meta.composite
        return Playlist(
            id = meta.ratingKey,
            title = meta.title,
            trackCount = meta.leafCount ?: ratingKeys.size,
            key = meta.key,
            thumbUrl = thumb?.let { server.assetUrl(it, token) },
        )
    }

    /**
     * Append [ratingKeys] (Plex rating keys, i.e. the un-prefixed track ids) to an existing
     * Plex playlist. Returns the updated leaf count if the server reports one, otherwise
     * `null`.
     *
     * Throws if the server responds with a non-2xx, including the response body so it's
     * obvious why the sync failed. Callers can `runCatching` if they want best-effort.
     */
    suspend fun addTracksToPlaylist(
        server: PlexServer,
        token: String,
        machineIdentifier: String,
        playlistRatingKey: String,
        ratingKeys: List<String>,
    ): Int? {
        if (ratingKeys.isEmpty()) return null
        val response = withReachableBase(server) { base ->
            httpClient.put("$base/playlists/$playlistRatingKey/items") {
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
                parameter("uri", metadataUri(machineIdentifier, ratingKeys))
            }
        }
        val parsed = parsePlaylistResponse(response, "addTracksToPlaylist", "playlist/$playlistRatingKey")
        return parsed.mediaContainer.leafCountAdded
            ?: parsed.mediaContainer.metadata.firstOrNull()?.leafCount
            ?: parsed.mediaContainer.size
    }

    suspend fun removePlaylistItems(
        server: PlexServer,
        token: String,
        playlistRatingKey: String,
        playlistItemIds: List<Long>,
    ) {
        if (playlistItemIds.isEmpty()) return
        withReachableBase(server) { base ->
            playlistItemIds.forEach { itemId ->
                val response = httpClient.delete("$base/playlists/$playlistRatingKey/items/$itemId") {
                    plexServerAuth(token)
                    header(HttpHeaders.Accept, "application/json")
                }
                if (!response.status.isSuccess()) {
                    val body = response.bodyAsText()
                    error("Plex remove playlist item failed (${response.status.value}) via $base: ${body.take(200)}")
                }
            }
        }
    }

    suspend fun deletePlaylist(server: PlexServer, token: String, playlistRatingKey: String) {
        withReachableBase(server) { base ->
            val response = httpClient.delete("$base/playlists/$playlistRatingKey") {
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                error("Plex delete playlist failed (${response.status.value}) via $base: ${body.take(200)}")
            }
        }
    }

    suspend fun movePlaylistItemToTop(
        server: PlexServer,
        token: String,
        playlistRatingKey: String,
        playlistItemId: Long,
    ) {
        movePlaylistItem(
            server = server,
            token = token,
            playlistRatingKey = playlistRatingKey,
            playlistItemId = playlistItemId,
            afterPlaylistItemId = null,
        )
    }

    suspend fun movePlaylistItem(
        server: PlexServer,
        token: String,
        playlistRatingKey: String,
        playlistItemId: Long,
        afterPlaylistItemId: Long?,
    ) {
        withReachableBase(server) { base ->
            val response = httpClient.put("$base/playlists/$playlistRatingKey/items/$playlistItemId/move") {
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
                if (afterPlaylistItemId != null) {
                    parameter("after", afterPlaylistItemId)
                }
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                PhoebeLog.d("PlexClient") {
                    "movePlaylistItem failed via $base for 'playlist/$playlistRatingKey/item/$playlistItemId' -> HTTP ${response.status.value}: $body"
                }
                error("Plex movePlaylistItem failed (${response.status.value}) via $base: $body")
            }
            PhoebeLog.v("PlexClient") {
                "movePlaylistItem ok via $base for 'playlist/$playlistRatingKey/item/$playlistItemId' (${response.status.value}): ${body.take(400)}"
            }
        }
    }

    suspend fun rateItem(
        server: PlexServer,
        token: String,
        ratingKey: String,
        rating: Float?,
    ) {
        val plexRating = rating.toPlexRating()
        val response = withReachableBase(server) { base ->
            httpClient.put("$base/:/rate") {
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
                parameter("identifier", LibraryIdentifier)
                parameter("key", ratingKey)
                parameter("rating", plexRating)
            }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            PhoebeLog.d("PlexClient") { "rateItem failed for '$ratingKey' → HTTP ${response.status.value}: $body" }
            error("Plex rating sync failed (${response.status.value}): $body")
        }
        PhoebeLog.v("PlexClient") { "rateItem ok for '$ratingKey' (${response.status.value}): ${body.take(200)}" }
    }

    /**
     * Report playback position to Plex so the server can mark items played and scrobble to
     * linked services (e.g. ListenBrainz). Clients must hit this on state changes and
     * periodically (~10s) while playing.
     *
     * Tries known connections until one accepts the command. plex.direct relays often
     * stream media but 401 the timeline path, so a 401 still moves to the next base.
     * Dead LAN origins are skipped after the first local timeout so one ping cannot
     * spend a minute on private IPs that playback already abandoned.
     */
    suspend fun reportTimeline(
        server: PlexServer,
        token: String,
        sessionIdentifier: String,
        ratingKey: String,
        timeMs: Long,
        durationMs: Long,
        state: PlexTimelineState,
        continuing: Boolean? = null,
        playQueueItemId: Long? = null,
    ) {
        val bases = server.timelineBaseUris(apiBaseCache[server.id])
        var lastStatus = 0
        var lastBody = ""
        var lastBase = server.uri
        var lastError: Throwable? = null
        var skipRemainingLocal = false
        for (base in bases) {
            if (skipRemainingLocal && isLocalOnlyServerOrigin(base)) continue
            lastBase = base
            val probeTimeoutMs = plexTimelineProbeTimeoutMs(base)
            val outcome = withTimeoutOrNull(probeTimeoutMs) {
                runCatching {
                    timelineHttpRequest(base, token, sessionIdentifier, probeTimeoutMs) {
                        parameter("ratingKey", ratingKey)
                        parameter("key", "/library/metadata/$ratingKey")
                        parameter("identifier", LibraryIdentifier)
                        parameter("time", timeMs.coerceAtLeast(0L))
                        parameter("duration", durationMs.coerceAtLeast(0L))
                        parameter("state", state.wireValue)
                        continuing?.let { parameter("continuing", if (it) 1 else 0) }
                        playQueueItemId?.let { parameter("playQueueItemID", it) }
                    }
                }
            }
            if (outcome == null) {
                lastError = IllegalStateException("reportTimeline timed out after ${probeTimeoutMs}ms via $base")
                PhoebeLog.d("PlexClient") {
                    "reportTimeline ${state.wireValue} timed out after ${probeTimeoutMs}ms on $base for '$ratingKey'"
                }
                if (isLocalOnlyServerOrigin(base)) skipRemainingLocal = true
                continue
            }
            if (outcome.isFailure) {
                val error = outcome.exceptionOrNull() ?: continue
                if (error is CancellationException) throw error
                lastError = error
                PhoebeLog.d("PlexClient") {
                    "reportTimeline ${state.wireValue} failed on $base for '$ratingKey': ${error.message}"
                }
                continue
            }
            val response = outcome.getOrThrow()
            if (response.status.isSuccess()) {
                apiBaseCache[server.id] = base
                PhoebeLog.v("PlexClient") {
                    "reportTimeline ${state.wireValue} ok for '$ratingKey' on $base"
                }
                return
            }
            lastStatus = response.status.value
            lastBody = response.bodyAsText()
            if (response.status.value != 401) break
        }
        val statusDetail = if (lastStatus != 0) {
            "HTTP $lastStatus from $lastBase: ${lastBody.take(300)}"
        } else {
            "network error from $lastBase: ${lastError?.message.orEmpty()}"
        }
        PhoebeLog.d("PlexClient") {
            "reportTimeline ${state.wireValue} failed for '$ratingKey' after ${bases.size} base(s): $statusDetail"
        }
        throw IllegalStateException(
            "Plex timeline ${state.wireValue} failed for '$ratingKey': $statusDetail",
            lastError,
        )
    }

    suspend fun markPlayed(
        server: PlexServer,
        token: String,
        ratingKey: String,
    ) {
        val response = withReachableBase(server) { base ->
            httpClient.put("$base/:/scrobble") {
                plexTimelineAuth(token)
                parameter("identifier", LibraryIdentifier)
                parameter("key", ratingKey)
            }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            PhoebeLog.d("PlexClient") {
                "markPlayed failed for '$ratingKey' -> HTTP ${response.status.value}: ${body.take(300)}"
            }
            error("Plex mark played failed (${response.status.value}): ${body.take(200)}")
        }
        PhoebeLog.v("PlexClient") { "markPlayed ok for '$ratingKey' (${response.status.value}): ${body.take(200)}" }
    }

    /**
     * Register an audio play queue with Plex — first-party clients do this before timeline
     * updates and many servers expect a playQueueItemID on each ping.
     */
    suspend fun createAudioPlayQueue(
        server: PlexServer,
        token: String,
        machineIdentifier: String,
        ratingKeys: List<String>,
        startRatingKey: String,
    ): PlexPlayQueue? {
        if (ratingKeys.isEmpty()) return null
        val uri = metadataUri(machineIdentifier, ratingKeys)
        val bases = server.timelineBaseUris(apiBaseCache[server.id])
        var lastError: Throwable? = null
        var skipRemainingLocal = false
        for (base in bases) {
            if (skipRemainingLocal && isLocalOnlyServerOrigin(base)) continue
            val probeTimeoutMs = plexBaseProbeTimeoutMs(base)
            val outcome = withTimeoutOrNull(probeTimeoutMs) {
                runCatching {
                    httpClient.post("$base/playQueues") {
                        timeout {
                            requestTimeoutMillis = probeTimeoutMs
                            connectTimeoutMillis = probeTimeoutMs
                        }
                        plexTimelineAuth(token)
                        parameter("type", "audio")
                        parameter("uri", uri)
                        parameter("key", startRatingKey)
                        parameter("continuous", 1)
                    }
                }
            }
            if (outcome == null) {
                lastError = IllegalStateException("createAudioPlayQueue timed out after ${probeTimeoutMs}ms via $base")
                PhoebeLog.d("PlexClient") { "createAudioPlayQueue timed out after ${probeTimeoutMs}ms on $base" }
                if (isLocalOnlyServerOrigin(base)) skipRemainingLocal = true
                continue
            }
            if (outcome.isFailure) {
                val error = outcome.exceptionOrNull() ?: continue
                if (error is CancellationException) throw error
                lastError = error
                PhoebeLog.d("PlexClient") { "createAudioPlayQueue failed on $base: ${error.message}" }
                continue
            }
            val response = outcome.getOrThrow()
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                if (response.status.value == 401) continue
                PhoebeLog.d("PlexClient") { "createAudioPlayQueue failed → HTTP ${response.status.value}: ${body.take(300)}" }
                return null
            }
            apiBaseCache[server.id] = base
            val parsed = runCatching {
                PlexJson.decodeFromString(PlexMediaContainerResponse.serializer(), body)
            }.getOrNull()
            val container = parsed?.mediaContainer
            val playQueueId = container?.playQueueId ?: return null
            val itemIds = buildMap {
                for (item in container.metadata) {
                    val id = item.playQueueItemId ?: continue
                    put(item.ratingKey, id)
                }
            }
            return PlexPlayQueue(playQueueId = playQueueId, itemIdByRatingKey = itemIds)
        }
        lastError?.let { PhoebeLog.d("PlexClient") { "createAudioPlayQueue failed on all bases: ${it.message}" } }
        return null
    }

    private suspend fun timelineHttpRequest(
        base: String,
        token: String,
        sessionIdentifier: String,
        probeTimeoutMs: Long,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): HttpResponse {
        val getResponse = httpClient.get("$base/:/timeline") {
            timeout {
                requestTimeoutMillis = probeTimeoutMs
                connectTimeoutMillis = probeTimeoutMs
            }
            plexTimelineAuth(token)
            header("X-Plex-Session-Identifier", sessionIdentifier)
            block()
        }
        if (getResponse.status.isSuccess() || getResponse.status.value != 401) {
            return getResponse
        }
        return httpClient.post("$base/:/timeline") {
            timeout {
                requestTimeoutMillis = probeTimeoutMs
                connectTimeoutMillis = probeTimeoutMs
            }
            plexTimelineAuth(token)
            header("X-Plex-Session-Identifier", sessionIdentifier)
            block()
        }
    }

    suspend fun editTrackMetadata(
        server: PlexServer,
        token: String,
        library: MusicLibrary,
        ratingKey: String,
        original: Track,
        update: TrackMetadataUpdate,
    ) {
        val response = withReachableBase(server) { base ->
            httpClient.put("$base/library/sections/${library.key}/all") {
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
                parameter("type", 10)
                parameter("id", ratingKey)
                if (update.title != original.title) {
                    parameter("title.value", update.title)
                    parameter("title.locked", 1)
                }
                if (update.artist != original.artist) {
                    parameter("originalTitle.value", update.artist)
                    parameter("originalTitle.locked", 1)
                }
            }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            PhoebeLog.d("PlexClient") { "editTrackMetadata failed for '$ratingKey' → HTTP ${response.status.value}: $body" }
            error("Plex metadata sync failed (${response.status.value}): $body")
        }
        PhoebeLog.v("PlexClient") { "editTrackMetadata ok for '$ratingKey' (${response.status.value}): ${body.take(400)}" }
    }

    private suspend fun setMetadataCollectionTag(
        server: PlexServer,
        token: String,
        library: MusicLibrary,
        ratingKey: String,
        typeId: Int,
        collectionName: String,
        legacyCollectionName: String,
        present: Boolean,
    ) {
        val currentMetadata = metadataDetails(server, ratingKey, token).firstOrNull()
        val collectionTags = if (present) {
            val existing = currentMetadata
                .collectionTagNames()
                .filterNot { tag ->
                    tag.equals(collectionName, ignoreCase = true) ||
                        tag.equals(legacyCollectionName, ignoreCase = true)
                }
            existing + collectionName
        } else {
            emptyList()
        }
        val response = withReachableBase(server) { base ->
            httpClient.put("$base/library/sections/${library.key}/all") {
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
                parameter("type", typeId)
                parameter("id", ratingKey)
                parameter("collection.locked", 1)
                if (typeId == PlexAlbumType) {
                    currentMetadata?.parentRatingKey?.takeIf { it.isNotBlank() }?.let { artistRatingKey ->
                        parameter("artist.id.value", artistRatingKey)
                    }
                }
                if (present) {
                    collectionTags.forEachIndexed { index, tag ->
                        parameter("collection[$index].tag.tag", tag)
                    }
                } else {
                    parameter("collection[].tag.tag-", "$collectionName,$legacyCollectionName")
                }
            }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            PhoebeLog.d("PlexClient") {
                "setMetadataCollectionTag failed for '$ratingKey' collection='$collectionName' present=$present → HTTP ${response.status.value}: $body"
            }
            error("Plex collection sync failed (${response.status.value}): $body")
        }
        val verifiedTags = metadataDetails(server, ratingKey, token)
            .firstOrNull()
            ?.collectionTags
        val verified = if (present) {
            verifiedTags.hasAnyTag(collectionName)
        } else {
            !verifiedTags.hasAnyTag(collectionName, legacyCollectionName)
        }
        if (!verified) {
            error("Plex collection sync did not persist for '$ratingKey'.")
        }
        if (present && typeId == PlexAlbumType) {
            ensureRegularCollectionObject(
                server = server,
                token = token,
                library = library,
                ratingKey = ratingKey,
                typeId = typeId,
                collectionName = collectionName,
            )
        }
        PhoebeLog.v("PlexClient") {
            "setMetadataCollectionTag verified for '$ratingKey' collection='$collectionName' present=$present (${response.status.value}): ${body.take(200)}"
        }
    }

    private suspend fun ensureRegularCollectionObject(
        server: PlexServer,
        token: String,
        library: MusicLibrary,
        ratingKey: String,
        typeId: Int,
        collectionName: String,
    ) {
        val existing = collectionObject(server, token, library, typeId, collectionName)
        if (existing != null && collectionContainsItem(server, token, library, existing, ratingKey)) {
            PhoebeLog.d("PlexClient") {
                "regular collection already contains '$ratingKey' for '$collectionName' key=${existing.ratingKey}"
            }
            return
        }
        val machineIdentifier = machineIdentifier(server, token)
        val response = withReachableBase(server) { base ->
            httpClient.post("$base/library/collections") {
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
                parameter("uri", metadataUri(machineIdentifier, listOf(ratingKey)))
                parameter("type", typeId)
                parameter("title", collectionName)
                parameter("smart", 0)
                parameter("sectionId", library.key)
            }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            PhoebeLog.d("PlexClient") {
                "regular collection create failed for '$collectionName' item='$ratingKey' (${response.status.value}): ${body.take(400)}"
            }
            if (response.status.value != 400 && response.status.value != 409) {
                error("Plex collection create failed (${response.status.value}): $body")
            }
            return
        }
        PhoebeLog.d("PlexClient") {
            "regular collection create ok for '$collectionName' item='$ratingKey' (${response.status.value}): ${body.take(200)}"
        }
        val created = runCatching {
            PlexJson.decodeFromString(PlexMediaContainerResponse.serializer(), body)
        }.getOrNull()?.let { responseBody ->
            responseBody.mediaContainer.collectionObjects().firstOrNull { collection ->
                collection.title.equals(collectionName, ignoreCase = true)
            }
        }
        val collection = created ?: collectionObject(server, token, library, typeId, collectionName)
        if (collection == null) {
            PhoebeLog.d("PlexClient") {
                "regular collection create could not resolve collection '$collectionName' after create"
            }
            return
        }
        if (!collectionContainsItem(server, token, library, collection, ratingKey)) {
            addItemToRegularCollection(server, token, machineIdentifier, collection, ratingKey)
        }
        if (!collectionContainsItem(server, token, library, collection, ratingKey)) {
            error("Plex collection '$collectionName' exists but does not contain '$ratingKey'.")
        }
    }

    private suspend fun collectionObject(
        server: PlexServer,
        token: String,
        library: MusicLibrary,
        typeId: Int,
        collectionName: String,
    ): PlexCollectionObject? {
        val paths = listOf(
            "/library/sections/${library.key}/collections?type=$typeId",
            "/library/sections/${library.key}/collections",
        )
        for (path in paths) {
            val response = runCatching {
                plexGet<PlexMediaContainerResponse>(server, token, path)
            }.getOrNull() ?: continue
            val match = response.mediaContainer.collectionObjects().firstOrNull { collection ->
                collection.title.equals(collectionName, ignoreCase = true)
            }
            if (match != null) return match
        }
        return null
    }

    private suspend fun collectionContainsItem(
        server: PlexServer,
        token: String,
        library: MusicLibrary,
        collection: PlexCollectionObject,
        ratingKey: String,
    ): Boolean {
        val collectionId = collection.ratingKey ?: collection.key.ratingKeyFromMetadataPath()
        val paths = listOfNotNull(
            collection.key.takeIf { it.startsWith("/") },
            collectionId?.let { "/library/collections/$it/children" },
            collectionId?.let { "/library/sections/${library.key}/collection/$it" },
        ).distinct()
        for (path in paths) {
            val response = runCatching {
                plexGet<PlexMediaContainerResponse>(server, token, path)
            }.getOrNull() ?: continue
            val itemIds = response.mediaContainer.directories.mapNotNull { directory ->
                directory.ratingKey ?: directory.key.ratingKeyFromMetadataPath()
            } + response.mediaContainer.metadata.mapNotNull { item ->
                when (item.type) {
                    "track" -> item.parentRatingKey
                    else -> item.ratingKey
                }?.takeIf { it.isNotBlank() }
            }
            PhoebeLog.d("PlexClient") {
                "regular collection verify key=${collection.ratingKey} path=$path item='$ratingKey' items=${itemIds.distinct()}"
            }
            if (ratingKey in itemIds) return true
        }
        return false
    }

    private suspend fun addItemToRegularCollection(
        server: PlexServer,
        token: String,
        machineIdentifier: String,
        collection: PlexCollectionObject,
        ratingKey: String,
    ) {
        val collectionId = collection.ratingKey ?: collection.key.ratingKeyFromMetadataPath()
        val paths = listOfNotNull(
            collectionId?.let { "/library/collections/$it/items" },
            collection.key.takeIf { it.startsWith("/") }?.substringBefore("/children")?.let { "$it/items" },
        ).distinct()
        val uri = metadataUri(machineIdentifier, listOf(ratingKey))
        var lastFailure: String? = null
        for (path in paths) {
            val result = runCatching {
                withReachableBase(server) { base ->
                    httpClient.put("$base$path") {
                        plexServerAuth(token)
                        header(HttpHeaders.Accept, "application/json")
                        parameter("uri", uri)
                    }
                }
            }
            val response = result.getOrNull()
            if (response != null) {
                val body = response.bodyAsText()
                if (response.status.isSuccess()) {
                    PhoebeLog.d("PlexClient") {
                        "regular collection add item ok key=${collection.ratingKey} path=$path item='$ratingKey' (${response.status.value}): ${body.take(200)}"
                    }
                    return
                }
                lastFailure = "HTTP ${response.status.value}: ${body.take(200)}"
            } else {
                lastFailure = result.exceptionOrNull()?.message
            }
        }
        error("Plex collection add item failed for '$ratingKey': ${lastFailure ?: "unknown error"}")
    }

    /**
     * Read the body once, log it, and only then deserialize. Plex returns somewhat ambiguous
     * shapes for playlist mutations (sometimes a full `Metadata` array, sometimes just stats
     * on the `MediaContainer`), and silent JSON failures had been hiding 4xx/5xx errors from
     * the user — printing the raw response gives us a chance to spot misformatted URIs,
     * wrong machine ids, etc.
     */
    private suspend fun parsePlaylistResponse(
        response: HttpResponse,
        op: String,
        context: String,
    ): PlexMediaContainerResponse {
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            PhoebeLog.d("PlexClient") { "$op failed for '$context' → HTTP ${response.status.value}: $body" }
            error("Plex $op failed (${response.status.value}): $body")
        }
        // Helpful for diagnosing "request succeeded but nothing happened" cases where Plex
        // returns 200 + leafCountAdded=0.
        PhoebeLog.v("PlexClient") { "$op ok for '$context' (${response.status.value}): ${body.take(400)}" }
        return PlexJson.decodeFromString(PlexMediaContainerResponse.serializer(), body)
    }

    /** Build a comma-joined `server://.../library/metadata/{key1,key2}` URI used by playlist mutations. */
    private fun metadataUri(machineIdentifier: String, ratingKeys: List<String>): String {
        val joined = ratingKeys.joinToString(",")
        return "server://$machineIdentifier/com.plexapp.plugins.library/library/metadata/$joined"
    }

    private suspend fun <T> withReachableBase(
        server: PlexServer,
        block: suspend (base: String) -> T,
    ): T {
        val cached = baseResolveMutex.withLock { apiBaseCache[server.id] }
        var lastError: Throwable? = null
        if (cached != null) {
            try {
                return block(cached)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastError = error
                baseResolveMutex.withLock {
                    if (apiBaseCache[server.id] == cached) {
                        apiBaseCache.remove(server.id)
                    }
                }
            }
        }
        for (base in server.reachableBaseUris(cached)) {
            if (base == cached) continue
            try {
                val result = block(base)
                baseResolveMutex.withLock { apiBaseCache[server.id] = base }
                return result
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("Could not reach Plex server '${server.name}'")
    }

    private suspend inline fun <reified T> plexGet(server: PlexServer, token: String, path: String): T =
        withReachableBase(server) { base ->
            val response = httpClient.get("$base$path") {
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                error("Plex GET $path failed (${response.status.value}) via $base: ${body.take(200)}")
            }
            response.body()
        }

    private suspend inline fun <reified T> plexGetSetup(
        server: PlexServer,
        token: String,
        path: String,
        baseTimeoutMs: Long,
    ): T {
        var lastError: Throwable? = null
        val bases = server.reachableBaseUris(apiBaseCache[server.id])
        for (base in bases) {
            val probeTimeoutMs = minOf(baseTimeoutMs, plexBaseProbeTimeoutMs(base))
            val outcome = withTimeoutOrNull(probeTimeoutMs) {
                runCatching {
                    val response = httpClient.get("$base$path") {
                        plexServerAuth(token)
                        header(HttpHeaders.Accept, "application/json")
                    }
                    if (!response.status.isSuccess()) {
                        val body = response.bodyAsText()
                        error("Plex GET $path failed (${response.status.value}) via $base: ${body.take(200)}")
                    }
                    response.body<T>()
                }
            }
            if (outcome == null) {
                lastError = IllegalStateException("Plex GET $path timed out after ${probeTimeoutMs}ms via $base")
                PhoebeLog.d("PlexClient") {
                    "setup GET $path timed out after ${probeTimeoutMs}ms via $base; trying next base"
                }
                continue
            }
            if (outcome.isSuccess) {
                apiBaseCache[server.id] = base
                return outcome.getOrThrow()
            }
            val error = outcome.exceptionOrNull()
            if (error is CancellationException) throw error
            lastError = error
            PhoebeLog.d("PlexClient") {
                "setup GET $path failed via $base: ${error?.message.orEmpty()}; trying next base"
            }
        }
        throw lastError ?: IllegalStateException("Could not reach Plex server '${server.name}' for $path")
    }

    private fun plexBaseProbeTimeoutMs(
        base: String,
        remoteTimeoutMs: Long = PlexRemoteSetupRequestTimeoutMs,
    ): Long = if (isLocalOnlyServerOrigin(base)) {
        minOf(PlexLocalSetupRequestTimeoutMs, remoteTimeoutMs)
    } else {
        remoteTimeoutMs
    }

    private fun plexTimelineProbeTimeoutMs(base: String): Long =
        plexBaseProbeTimeoutMs(base, PlexTimelineRemoteRequestTimeoutMs)

    private suspend fun metadataDetails(server: PlexServer, ratingKey: String, token: String): List<PlexMetadataDto> {
        val response = plexGet<PlexMediaContainerResponse>(
            server,
            token,
            "/library/metadata/$ratingKey?includeUserState=1",
        )
        return response.mediaContainer.metadata
    }

    private suspend fun discoverCollectionFilterPath(
        server: PlexServer,
        library: MusicLibrary,
        target: PlexCollectionTarget,
        facet: PlexCollectionFacet,
        token: String,
    ): String? {
        val scopedField = "${target.libtype}.${facet.filterField}"
        val wanted = setOf(scopedField, facet.filterField)
        val paths = listOf(
            "/library/sections/${library.key}/filters?type=${target.typeId}",
            "/library/sections/${library.key}?includeDetails=1",
            "/library/sections/${library.key}/all?includeMeta=1&includeAdvanced=1&X-Plex-Container-Start=0&X-Plex-Container-Size=0",
        )
        for (path in paths) {
            val body = runCatching { plexGetRaw(server, token, path) }.getOrNull()
            if (body == null) {
                PhoebeLog.d("PlexCollections") {
                    "discover target=${target.name} facet=${facet.name} path=$path failed"
                }
                continue
            }
            val root = runCatching { PlexJson.parseToJsonElement(body) }.getOrNull() ?: continue
            val mediaContainer = root.jsonObjectOrNull()?.get("MediaContainer") ?: root
            val typeObjects = mediaContainer.walkObjects().filter { obj ->
                obj.stringValue("type") == target.libtype && obj.containsKey("Filter")
            }.toList()
            val candidates = typeObjects.ifEmpty { mediaContainer.walkObjects().toList() }
            val filters = candidates
                .asSequence()
                .flatMap { it.walkObjects() }
                .filter { obj ->
                    obj.stringValue("filter") in wanted &&
                        obj.stringValue("key")?.startsWith("/library/sections/${library.key}/") == true
                }
                .toList()
            val discovered = (
                filters.firstOrNull { it.stringValue("filter") == scopedField } ?: filters.firstOrNull()
                )?.stringValue("key")
            PhoebeLog.d("PlexCollections") {
                val filters = candidates
                    .asSequence()
                    .flatMap { it.walkObjects() }
                    .mapNotNull { obj -> obj.stringValue("filter")?.let { filter -> "$filter -> ${obj.stringValue("key")}" } }
                    .take(24)
                    .toList()
                "discover target=${target.name} facet=${facet.name} path=$path typeObjects=${typeObjects.size} wanted=$wanted discovered=$discovered filters=$filters bodyPrefix=${body.take(240)}"
            }
            if (discovered != null) return discovered
        }
        return null
    }

    private suspend fun plexGetRaw(server: PlexServer, token: String, path: String): String =
        withReachableBase(server) { base ->
            val response = httpClient.get("$base$path") {
                plexServerAuth(token)
                header(HttpHeaders.Accept, "application/json")
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                error("Plex GET $path failed (${response.status.value}) via $base: ${body.take(200)}")
            }
            body
        }

    private fun PlexMetadataDto.toTrack(server: PlexServer, token: String): Track? {
        if (ratingKey.isBlank()) return null
        val mediaItem = media.firstOrNull() ?: return null
        val part = mediaItem.parts.firstOrNull() ?: return null
        val streamUrl = server.assetUrl(part.key, token)
        val genre = primaryGenreTag()
        val mood = primaryMoodTag()
        val style = primaryStyleTag()
        val bitrateRaw = mediaItem.bitrate
        val bitrateKbps = bitrateRaw?.let { raw ->
            when {
                raw <= 0 -> null
                raw >= 500_000 -> raw / 1000
                raw >= 8000 -> raw / 1000
                else -> raw
            }
        }
        return Track(
            id = ratingKey,
            title = title,
            artist = resolvedTrackArtist(default = "Unknown artist"),
            album = parentTitle ?: "Unknown album",
            durationMs = duration ?: 0L,
            streamUrl = streamUrl,
            downloadUrl = "$streamUrl&download=1",
            thumbUrl = (thumb ?: parentThumb ?: grandparentThumb)?.let { server.assetUrl(it, token) },
            year = year ?: parentYear,
            genre = genre,
            mood = mood,
            style = style,
            filepath = part.file,
            audioCodec = mediaItem.audioCodec?.takeIf { it.isNotBlank() },
            bitrateKbps = bitrateKbps,
            dateAddedMs = addedAt?.times(1000L),
            rating = userRating.toStarRating(),
            playlistItemId = playlistItemId,
            parentAlbumId = parentRatingKey,
        )
    }

    private fun PlexMetadataDto.toPlaybackHistoryEntry(): PlexPlaybackHistoryEntry? {
        if (ratingKey.isBlank()) return null
        val viewed = viewedAt ?: lastViewedAt ?: return null
        val key = historyKey?.takeIf { it.isNotBlank() }
            ?: key?.takeIf { it.isNotBlank() }
            ?: "plex:${ratingKey}:$viewed"
        return PlexPlaybackHistoryEntry(
            ratingKey = ratingKey,
            historyKey = key,
            viewedAtMs = viewed * 1000L,
            type = type,
            librarySectionId = librarySectionID,
            title = title,
            artist = resolvedTrackArtist(default = "Unknown Artist"),
            album = parentTitle ?: "Unknown Album",
        )
    }

    private fun PlexMetadataDto.toTrackPlaybackStat(): PlexTrackPlaybackStat? {
        if (ratingKey.isBlank()) return null
        val count = resolvedViewCount() ?: return null
        return PlexTrackPlaybackStat(
            ratingKey = ratingKey,
            viewCount = count,
            lastViewedAtMs = resolvedLastViewedAtMs(),
            title = title.ifBlank { "Unknown Song" },
            artist = resolvedTrackArtist(default = "Unknown Artist"),
            album = parentTitle ?: "Unknown Album",
        )
    }

    private fun PlexMetadataDto.resolvedViewCount(): Long? =
        viewCount?.takeIf { it > 0L }
            ?: userState?.viewCount?.takeIf { it > 0L }

    private fun PlexMetadataDto.resolvedLastViewedAtMs(): Long? =
        (lastViewedAt ?: viewedAt ?: userState?.lastViewedAt ?: userState?.viewedAt)?.times(1000L)

    private fun PlexMediaContainerResponse.toTrackPage(
        server: PlexServer,
        token: String,
        requestedStart: Int,
        requestedSize: Int,
    ): PlexTrackPage {
        val container = mediaContainer
        val tracks = container.metadata.mapNotNull { it.toTrack(server, token) }
        val offset = container.offset ?: requestedStart
        val totalSize = container.totalSize ?: responseHeaderTotalSizeFallback(offset, container.size, tracks.size, requestedSize)
        return PlexTrackPage(
            tracks = tracks,
            offset = offset,
            size = container.size.takeIf { it > 0 } ?: tracks.size,
            totalSize = totalSize,
        )
    }

    private fun responseHeaderTotalSizeFallback(offset: Int, containerSize: Int, trackSize: Int, requestedSize: Int): Int? {
        val actual = containerSize.takeIf { it > 0 } ?: trackSize
        return if (actual < requestedSize) offset + actual else null
    }

    private fun PlexServer.assetUrl(path: String, token: String): String {
        val base = apiBaseCache[id] ?: uri
        val builder = URLBuilder(base)
        builder.appendPathSegments(path.trimStart('/').split('/'))
        builder.parameters.append("X-Plex-Token", token)
        return builder.buildString()
    }

    /** Base URL that succeeded for API calls, used for media/thumbnail URLs. */
    fun mediaBaseUrl(server: PlexServer): String = apiBaseCache[server.id] ?: server.uri

    private fun parseTrackPlaybackStatsFromBody(body: String): List<PlexTrackPlaybackStat> {
        val root = runCatching { PlexJson.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val mediaContainer = root.jsonObjectOrNull()?.get("MediaContainer")?.jsonObjectOrNull() ?: root.jsonObjectOrNull()
        val metadata = mediaContainer?.get("Metadata") ?: return emptyList()
        val objects = when (metadata) {
            is JsonArray -> metadata.mapNotNull { it.jsonObjectOrNull() }
            is JsonObject -> listOf(metadata)
            else -> emptyList()
        }
        return objects.mapNotNull { it.toRawTrackPlaybackStat() }
    }

    private fun JsonObject.toRawTrackPlaybackStat(): PlexTrackPlaybackStat? {
        val ratingKey = stringValue("ratingKey") ?: longValue("ratingKey")?.toString() ?: return null
        val viewCount = resolveRawViewCount() ?: return null
        return PlexTrackPlaybackStat(
            ratingKey = ratingKey,
            viewCount = viewCount,
            lastViewedAtMs = resolveRawLastViewedAt(),
            title = stringValue("title") ?: "Unknown Song",
            artist = rawTrackArtist(default = "Unknown Artist"),
            album = stringValue("parentTitle") ?: "Unknown Album",
        )
    }

    private fun JsonObject.resolveRawViewCount(): Long? {
        longValue("viewCount")?.takeIf { it > 0L }?.let { return it }
        resolveNestedUserStateViewCount(this["UserState"] ?: this["userState"])?.let { return it }
        val meta = this["Meta"]?.jsonObjectOrNull() ?: return null
        return resolveNestedUserStateViewCount(meta["UserState"] ?: meta["userState"])
    }

    private fun JsonObject.resolveRawLastViewedAt(): Long? {
        (longValue("lastViewedAt") ?: longValue("viewedAt"))?.let { return it * 1000L }
        resolveNestedUserStateLastViewedAt(this["UserState"] ?: this["userState"])?.let { return it }
        val meta = this["Meta"]?.jsonObjectOrNull() ?: return null
        return resolveNestedUserStateLastViewedAt(meta["UserState"] ?: meta["userState"])
    }

    private fun resolveNestedUserStateViewCount(userState: JsonElement?): Long? = when (userState) {
        is JsonObject -> userState.longValue("viewCount")?.takeIf { it > 0L }
        is JsonArray -> userState.firstNotNullOfOrNull { (it as? JsonObject)?.longValue("viewCount")?.takeIf { count -> count > 0L } }
        else -> null
    }

    private fun resolveNestedUserStateLastViewedAt(userState: JsonElement?): Long? {
        val stateObject = when (userState) {
            is JsonObject -> userState
            is JsonArray -> userState.firstNotNullOfOrNull { it as? JsonObject }
            else -> null
        } ?: return null
        return (stateObject.longValue("lastViewedAt") ?: stateObject.longValue("viewedAt"))?.times(1000L)
    }

    private fun JsonObject.longValue(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.toLongOrNull()
            ?: primitive.contentOrNull?.toDoubleOrNull()?.toLong()
    }

    companion object {
        const val LibraryIdentifier = "com.plexapp.plugins.library"
        const val PlayQueueMetadataBatchSize = 25
        private const val PlexPlayQueueDefaultWindowSize = 200
        private const val PlexPlayQueueMaxWindowSize = 200
        const val ClientIdentifier = "phoebe-compose-multiplatform"
        const val FavoriteArtistsCollection = "Favorite Artists"
        const val FavoriteAlbumsCollection = "Favorite Albums"
        const val LegacyFavoriteArtistsCollection = "Phoebe Favorite Artists"
        const val LegacyFavoriteAlbumsCollection = "Phoebe Favorite Albums"
        private const val PlexArtistType = 8
        private const val PlexAlbumType = 9
        private const val PlexTrackType = 10
        private const val PlexLocalSetupRequestTimeoutMs = 800L
        private const val PlexRemoteSetupRequestTimeoutMs = 8_000L
        private const val PlexTimelineRemoteRequestTimeoutMs = 3_000L
        private const val PlexPopularTracksRequestTimeoutMs = 15_000L
        private const val MusicStationsHubContext = "hub.music.stations"
        private const val MusicStationsHubIdentifier = "music.stations"
        val PlexJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

private fun Double?.toStarRating(): Float? =
    this?.div(2.0)
        ?.toFloat()
        ?.coerceIn(0f, 5f)
        ?.let { (kotlin.math.round(it * 2f) / 2f).takeIf { rounded -> rounded > 0f } }

private fun PlexMetadataDto.primaryGenreTag(): String? = genreTags.primaryTag()

private fun PlexMetadataDto.primaryMoodTag(): String? = moodTags.primaryTag()

private fun PlexMetadataDto.primaryStyleTag(): String? = styleTags.primaryTag()

private fun PlexMetadataDto.resolvedTrackArtist(default: String): String =
    originalTitle?.takeIf { it.isNotBlank() }
        ?: grandparentTitle?.takeIf { it.isNotBlank() }
        ?: parentTitle?.takeIf { it.isNotBlank() }
        ?: default

private fun PlexMetadataDto.isFavoriteArtistCollection(): Boolean =
    collectionTags.hasAnyTag(PlexClient.FavoriteArtistsCollection, PlexClient.LegacyFavoriteArtistsCollection)

private fun PlexMetadataDto.isFavoriteAlbumCollection(): Boolean =
    collectionTags.hasAnyTag(PlexClient.FavoriteAlbumsCollection, PlexClient.LegacyFavoriteAlbumsCollection)

private fun PlexDirectoryDto.primaryGenreTag(): String? = genreTags.primaryTag()

private fun PlexDirectoryDto.primaryMoodTag(): String? = moodTags.primaryTag()

private fun PlexDirectoryDto.primaryStyleTag(): String? = styleTags.primaryTag()

private fun PlexDirectoryDto.isFavoriteArtistCollection(): Boolean =
    collectionTags.hasAnyTag(PlexClient.FavoriteArtistsCollection, PlexClient.LegacyFavoriteArtistsCollection)

private fun PlexDirectoryDto.isFavoriteAlbumCollection(): Boolean =
    collectionTags.hasAnyTag(PlexClient.FavoriteAlbumsCollection, PlexClient.LegacyFavoriteAlbumsCollection)

private fun List<PlexGenreTagDto>?.primaryTag(): String? =
    this?.firstNotNullOfOrNull { tag ->
        tag.tag?.trim()?.takeIf { it.isNotBlank() }
    }

private fun List<PlexGenreTagDto>?.allTagsJoined(): String? =
    this?.mapNotNull { tag -> tag.tag?.trim()?.takeIf { it.isNotBlank() } }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(", ")

private fun PlexMetadataDto.allMoodsJoined(): String? = moodTags.allTagsJoined()
private fun PlexMetadataDto.allStylesJoined(): String? = styleTags.allTagsJoined()

private fun PlexDirectoryDto.allMoodsJoined(): String? = moodTags.allTagsJoined()
private fun PlexDirectoryDto.allStylesJoined(): String? = styleTags.allTagsJoined()

private fun PlexMetadataDto?.collectionTagNames(): List<String> =
    this?.collectionTags
        ?.mapNotNull { tag -> tag.tag?.trim()?.takeIf { it.isNotBlank() } }
        .orEmpty()

private fun List<PlexGenreTagDto>?.hasAnyTag(vararg values: String): Boolean =
    this?.any { tag ->
        val actual = tag.tag?.trim() ?: return@any false
        values.any { value -> actual.equals(value, ignoreCase = true) }
    } == true

private fun PlexMediaContainer.collectionObjects(): List<PlexCollectionObject> =
    directories.mapNotNull { directory ->
        val key = directory.key.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        PlexCollectionObject(
            ratingKey = directory.ratingKey ?: key.ratingKeyFromMetadataPath(),
            key = key,
            title = directory.title,
        )
    } + metadata.mapNotNull { item ->
        val key = item.key?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        PlexCollectionObject(
            ratingKey = item.ratingKey.takeIf { it.isNotBlank() } ?: key.ratingKeyFromMetadataPath(),
            key = key,
            title = item.title,
        )
    }

private data class PlexCollectionObject(
    val ratingKey: String?,
    val key: String,
    val title: String,
)

private fun PlexStationDto.toRadioStation(
    category: PlexRadioStationCategory,
    assetUrl: (String) -> String,
): PlexRadioStation? {
    val stationKey = key?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val cleanTitle = title.trim().takeIf { it.isNotBlank() } ?: return null
    val subtitle = summary?.trim()?.takeIf { it.isNotBlank() }
        ?: when (category) {
            PlexRadioStationCategory.Library -> "Plex radio"
            PlexRadioStationCategory.Artist -> "Artist radio"
        }
    val id = ratingKey.takeIf { it.isNotBlank() }
        ?: stationKey.substringBefore('?').substringAfterLast('/').takeIf { it.isNotBlank() }
        ?: stationKey
    return PlexRadioStation(
        id = id,
        title = cleanTitle,
        subtitle = subtitle,
        key = stationKey.normalizedStationKey(),
        thumbUrl = thumb?.let(assetUrl),
        category = category,
    )
}

private fun JsonElement.libraryStationObjects(libraryKey: String): Sequence<JsonObject> = sequence {
    val root = this@libraryStationObjects
    val stationHubs = root.walkObjects().filter { obj ->
        obj.stringValue("context") == "hub.music.stations" ||
            obj.stringValue("hubIdentifier") == "music.stations"
    }
    val explicitHubStations = stationHubs.flatMap { hub ->
        hub.entries
            .asSequence()
            .filter { (name, _) -> name.equals("Metadata", ignoreCase = true) || name.equals("Directory", ignoreCase = true) }
            .flatMap { (_, value) -> value.walkObjects() }
            .filter { station -> station.looksLikeLibraryStationObject(libraryKey) }
    }
    yieldAll(explicitHubStations.distinctBy { station ->
        station.stringValue("key") ?: station.stringValue("ratingKey") ?: station.toString()
    })
}

private fun JsonObject.looksLikeLibraryStationObject(libraryKey: String): Boolean {
    val key = stringValue("key") ?: stringValue("uri") ?: return false
    return key.isPlexLibrarySectionStationKey(libraryKey)
}

internal fun String.isPlexLibrarySectionStationKey(libraryKey: String): Boolean {
    val normalized = trim().substringBefore('?')
    return normalized.startsWith("/library/sections/$libraryKey/stations/", ignoreCase = true)
}

fun String.plexLibraryStationSlug(): String =
    substringBefore('?').substringAfterLast('/')

internal fun plexLibraryStationKeysToTry(stationKey: String): List<String> {
    val normalized = stationKey.normalizedStationKey()
    val libraryKey = Regex("""/library/sections/([^/]+)/stations/""", RegexOption.IGNORE_CASE)
        .find(normalized)
        ?.groupValues
        ?.get(1)
    val slug = normalized.plexLibraryStationSlug().lowercase()
    return buildList {
        add(normalized)
        if (libraryKey != null) {
            plexLibraryStationNumericSlug(slug)?.let { numeric ->
                add("/library/sections/$libraryKey/stations/$numeric")
            }
            if (slug.all(Char::isDigit)) {
                plexLibraryStationNamedSlug(slug)?.let { named ->
                    add("/library/sections/$libraryKey/stations/$named")
                }
            }
        }
    }.distinct()
}

private fun plexLibraryStationNumericSlug(namedSlug: String): String? =
    when (namedSlug.lowercase()) {
        "library" -> "1"
        "deepcuts" -> "8"
        "timetravel" -> "2"
        "randomalbum" -> "3"
        else -> null
    }

private fun plexLibraryStationNamedSlug(numericSlug: String): String? =
    when (numericSlug) {
        "1" -> "library"
        "8" -> "deepCuts"
        "2" -> "timeTravel"
        "3" -> "randomAlbum"
        else -> null
    }

private fun JsonElement.artistStationObjects(): Sequence<JsonObject> = sequence {
    val root = this@artistStationObjects
    val explicitStations = root.walkObjects().flatMap { obj ->
        obj.entries
            .asSequence()
            .filter { (name, _) -> name.equals("Stations", ignoreCase = true) || name.equals("Station", ignoreCase = true) }
            .flatMap { (_, value) -> value.walkObjects() }
            .filter { station -> station.looksLikeStationChildObject() }
    }
    val fallbackStations = root.walkObjects().filter { obj -> obj.looksLikeRadioStationObject() }
    yieldAll((explicitStations + fallbackStations).distinctBy { station ->
        station.stringValue("key") ?: station.stringValue("ratingKey") ?: station.toString()
    })
}

private fun JsonObject.looksLikeStationChildObject(): Boolean {
    val key = stringValue("key") ?: stringValue("uri") ?: return false
    val title = stringValue("title") ?: stringValue("name")
    return key.isNotBlank() && !key.endsWith("/children") && title?.isNotBlank() == true
}

private fun JsonObject.looksLikeRadioStationObject(): Boolean {
    val key = stringValue("key") ?: stringValue("uri") ?: return false
    val title = stringValue("title") ?: stringValue("name")
    return key.contains("/station/", ignoreCase = true) ||
        key.contains("station", ignoreCase = true) ||
        title?.contains("radio", ignoreCase = true) == true
}

private fun JsonObject.toRadioStation(
    category: PlexRadioStationCategory,
    defaultTitle: String,
    assetUrl: (String) -> String,
): PlexRadioStation? {
    val stationKey = (stringValue("key") ?: stringValue("uri"))
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val cleanTitle = (stringValue("title") ?: stringValue("name") ?: defaultTitle)
        .trim()
        .takeIf { it.isNotBlank() }
        ?: return null
    val subtitle = (stringValue("summary") ?: stringValue("subtitle"))
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: when (category) {
            PlexRadioStationCategory.Library -> "Plex radio"
            PlexRadioStationCategory.Artist -> "Artist radio"
        }
    val id = (stringValue("ratingKey") ?: stringValue("id"))
        ?.takeIf { it.isNotBlank() }
        ?: stationKey.substringBefore('?').substringAfterLast('/').takeIf { it.isNotBlank() }
        ?: stationKey
    return PlexRadioStation(
        id = id,
        title = cleanTitle,
        subtitle = subtitle,
        key = stationKey.normalizedStationKey(),
        thumbUrl = stringValue("thumb")?.let(assetUrl),
        category = category,
    )
}

private fun JsonElement.similarArtistObjects(): Sequence<JsonObject> = sequence {
    val container = jsonObjectOrNull()?.get("MediaContainer") ?: this@similarArtistObjects
    val directArtists = container.childObjectsNamed("Metadata", "Directory")
        .filter { artist -> artist.looksLikeArtistObject() }
        .toList()
    val nestedSimilarArtists = container.walkObjects()
        .flatMap { item -> item.childObjectsNamed("Similar") }
        .filter { artist -> artist.looksLikeArtistObject() }
        .toList()
    val artists = (directArtists + nestedSimilarArtists).distinctBy { artist ->
        artist.stringValue("ratingKey")
            ?: artist.stringValue("id")
            ?: artist.stringValue("key")
            ?: artist.stringValue("guid")
            ?: artist.stringValue("title")
            ?: artist.stringValue("tag")
            ?: artist.toString()
    }
    yieldAll(artists)
    if (artists.isEmpty()) {
        yieldAll(container.walkObjects().filter { artist -> artist.looksLikeArtistObject() })
    }
}

private fun JsonElement.childObjectsNamed(vararg names: String): Sequence<JsonObject> = sequence {
    val wanted = names.toSet()
    val container = this@childObjectsNamed.jsonObjectOrNull() ?: return@sequence
    container.entries
        .asSequence()
        .filter { (name, _) -> wanted.any { wantedName -> name.equals(wantedName, ignoreCase = true) } }
        .flatMap { (_, value) ->
            when (value) {
                is JsonObject -> sequenceOf(value)
                is JsonArray -> value.asSequence().mapNotNull { child -> child.jsonObjectOrNull() }
                else -> emptySequence()
            }
        }
        .forEach { child -> yield(child) }
}

private fun JsonObject.looksLikeArtistObject(): Boolean {
    val title = stringValue("title") ?: stringValue("tag") ?: stringValue("name")
    val type = stringValue("type")
    val key = stringValue("ratingKey")
        ?: stringValue("id")
        ?: stringValue("key")?.ratingKeyFromMetadataPath()
        ?: stringValue("guid")
    return title?.isNotBlank() == true &&
        key?.isNotBlank() == true &&
        (type == null || type.equals("artist", ignoreCase = true))
}

private fun JsonObject.toRawArtist(assetUrl: (String) -> String): Artist? {
    val title = (stringValue("title") ?: stringValue("tag") ?: stringValue("name"))
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val id = (stringValue("ratingKey")
        ?: stringValue("id")
        ?: stringValue("key")?.ratingKeyFromMetadataPath()
        ?: stringValue("guid")
        ?: title)
        .trim()
        .takeIf { it.isNotBlank() }
        ?: return null
    return Artist(
        id = id,
        title = title,
        thumbUrl = stringValue("thumb")?.let(assetUrl),
        albumCount = stringValue("leafCount")?.toIntOrNull() ?: 0,
        dateAddedMs = stringValue("addedAt")?.toLongOrNull()?.times(1000L),
    )
}

private fun String.normalizedStationKey(): String =
    trim().let { if (it.startsWith("/")) it else "/$it" }

private fun PlexDirectoryDto.toFilterChoice(field: String, libraryKey: String, typeId: Int): PlexFilterChoice? {
    val cleanTitle = title.trim().takeIf { it.isNotBlank() } ?: return null
    val cleanKey = key.trim().takeIf { it.isNotBlank() } ?: cleanTitle
    val pathKey = cleanKey.takeIf { it.startsWith("/library/sections/") }
    val choiceKey = (pathKey?.queryValueFor(field) ?: cleanKey).filterChoiceValue()
    return PlexFilterChoice(
        key = choiceKey,
        title = cleanTitle,
        fastKey = fastKey ?: pathKey ?: "/library/sections/$libraryKey/all?type=$typeId&$field=${choiceKey.encodeURLParameter()}",
        filterField = field,
    )
}

private fun String.queryValueFor(field: String): String? {
    val query = substringAfter('?', missingDelimiterValue = "")
    if (query.isBlank()) return null
    val fieldSuffix = field.substringAfterLast('.')
    return query
        .split('&')
        .firstNotNullOfOrNull { part ->
            val name = part.substringBefore('=')
            val value = part.substringAfter('=', missingDelimiterValue = "")
            value.takeIf { it.isNotBlank() && (name == field || name == fieldSuffix) }
        }
}

private fun String.filterChoiceValue(): String =
    trim()
        .substringAfterLast('=')
        .substringBefore('&')
        .trim()

private fun String.filterFieldFromCollectionPath(): String? =
    substringAfterLast('/')
        .substringBefore('?')
        .takeIf { it.isNotBlank() && it != "all" }

private fun String.ratingKeyFromMetadataPath(): String? =
    trim('/')
        .split('/')
        .dropWhile { it != "metadata" }
        .drop(1)
        .firstOrNull()
        ?.takeIf { it.isNotBlank() }

private fun JsonElement.walkObjects(): Sequence<JsonObject> = sequence {
    when (val element = this@walkObjects) {
        is JsonObject -> {
            yield(element)
            element.values.forEach { child ->
                yieldAll(child.walkObjects())
            }
        }
        is JsonArray -> element.forEach { child ->
            yieldAll(child.walkObjects())
        }
        else -> Unit
    }
}

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.rawTrackArtist(default: String): String =
    stringValue("originalTitle")
        ?: stringValue("grandparentTitle")
        ?: stringValue("parentTitle")
        ?: default

private fun String.normalizedArtistLookupKey(): String =
    trim().lowercase()

private fun String.isTrackCollectionField(facet: PlexCollectionFacet): Boolean =
    this == facet.filterField || this == "track.${facet.filterField}"

private fun String.isTypedCollectionFastKey(target: PlexCollectionTarget): Boolean =
    contains("type=${target.typeId}") || contains("/${target.libtype}s?")

enum class PlexCollectionTarget(val typeId: Int, val libtype: String) {
    Artists(8, "artist"),
    Albums(9, "album"),
}

enum class PlexCollectionFacet(val filterField: String) {
    Mood("mood"),
    Style("style"),
    Genre("genre"),
}

data class PlexFilterChoice(
    val key: String,
    val title: String,
    val fastKey: String?,
    val filterField: String? = null,
)

private fun Float?.toPlexRating(): Double =
    this?.coerceIn(0f, 5f)
        ?.let { kotlin.math.round(it * 2f) / 2f }
        ?.times(2f)
        ?.toDouble()
        ?: 0.0

data class PlexTrackPage(
    val tracks: List<Track>,
    val offset: Int,
    val size: Int,
    val totalSize: Int?,
) {
    val nextOffset: Int get() = offset + size
    val hasMore: Boolean
        get() = when {
            tracks.isEmpty() -> false
            totalSize != null -> nextOffset < totalSize
            else -> size > 0
        }
}

enum class PlexTimelineState(val wireValue: String) {
    Playing("playing"),
    Paused("paused"),
    Stopped("stopped"),
    Buffering("buffering"),
}

/** Plex accepts the token in a header and/or query param; relays reliably forward the query form. */
private fun io.ktor.client.request.HttpRequestBuilder.plexServerAuth(token: String) {
    header("X-Plex-Token", token)
    parameter("X-Plex-Token", token)
    plexHeaders()
}

private fun io.ktor.client.request.HttpRequestBuilder.plexTimelineAuth(token: String) {
    plexServerAuth(token)
    header("X-Plex-Device", "Phoebe")
    header("X-Plex-Device-Name", "Phoebe")
    header("X-Plex-Provides", "player")
}

private fun io.ktor.client.request.HttpRequestBuilder.plexHeaders(token: String? = null) {
    header("X-Plex-Product", "Phoebe")
    header("X-Plex-Version", "0.1.0")
    header("X-Plex-Client-Identifier", PlexClient.ClientIdentifier)
    header("X-Plex-Platform", "Compose Multiplatform")
    header(HttpHeaders.Accept, "application/json")
    token?.let { header("X-Plex-Token", it) }
}
