package com.phoebe.app.backend.musicbrainz

import com.phoebe.app.backend.BackendSingleFlight
import com.phoebe.app.backend.PhoebeBackendEnvironment
import com.phoebe.app.backend.PhoebeBackendFeature
import com.phoebe.app.backend.ProviderApiException
import com.phoebe.app.backend.normalizedBackendCacheKey
import com.phoebe.app.backend.requireProviderSuccess
import com.phoebe.app.backend.requiredBackendQueryParameter
import com.phoebe.app.backend.tryAcquire
import com.phoebe.app.domain.MusicBrainzAlbumMetadataQuery
import com.phoebe.app.domain.MusicBrainzAlbumMetadataResponse
import com.phoebe.app.domain.MusicBrainzArtistArtworkResponse
import com.phoebe.app.domain.MusicBrainzArtwork
import com.phoebe.app.domain.MusicBrainzCreditSection
import com.phoebe.app.domain.MusicBrainzMetadataMatch
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class MusicBrainzBackendFeature(
    private val rateGate: MusicBrainzRequestGate = TimedMusicBrainzRequestGate(),
) : PhoebeBackendFeature {
    override fun install(application: Application, environment: PhoebeBackendEnvironment) {
        val service = MusicBrainzService(
            adapter = MusicBrainzApiAdapter(
                httpClient = environment.httpClient,
                userAgent = environment.config.musicBrainzUserAgent,
                rateGate = rateGate,
            ),
            albumCache = MusicBrainzCache(environment.config.cacheTtlMinutes * 60_000L, environment.clockMs),
            artistCache = MusicBrainzCache(environment.config.cacheTtlMinutes * 60_000L, environment.clockMs),
        )

        application.routing {
            get("/v1/musicbrainz/album") {
                if (!call.tryAcquire(environment, "musicbrainz-album")) return@get
                val album = call.requiredBackendQueryParameter("album")
                val artist = call.requiredBackendQueryParameter("artist")
                val year = call.request.queryParameters["year"]?.toIntOrNull()
                val releaseMbids = call.request.queryParameters.getAll("releaseMbid")
                    .orEmpty()
                    .flatMap { it.split(',') }
                    .map { it.trim() }
                    .filter { it.isLikelyMbid() }
                    .distinct()
                call.respond(
                    service.albumMetadata(
                        album = album,
                        artist = artist,
                        year = year,
                        releaseMbids = releaseMbids,
                    ),
                )
            }
            get("/v1/musicbrainz/artist-artwork") {
                if (!call.tryAcquire(environment, "musicbrainz-artist-artwork")) return@get
                val artist = call.requiredBackendQueryParameter("artist")
                val hasLimit = call.request.queryParameters.contains("limit")
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 24) ?: DefaultArtistArtworkLimit
                val fast = call.request.queryParameters["fast"]?.toBooleanStrictOrNull() ?: !hasLimit
                val excludedArtworkUrls = call.request.queryParameters.getAll("excludeImageUrl")
                    .orEmpty()
                    .flatMap { it.split(',') }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                call.respond(
                    service.artistArtwork(
                        artist = artist,
                        limit = limit,
                        fast = fast,
                        excludedArtworkUrls = excludedArtworkUrls,
                    ),
                )
            }
        }
    }
}

class MusicBrainzService(
    private val adapter: MusicBrainzAdapter,
    private val albumCache: MusicBrainzCache<MusicBrainzAlbumMetadataResponse>,
    private val artistCache: MusicBrainzCache<MusicBrainzArtistArtworkResponse>,
    private val singleFlight: BackendSingleFlight = BackendSingleFlight(),
) {
    suspend fun albumMetadata(
        album: String,
        artist: String,
        year: Int?,
        releaseMbids: List<String>,
    ): MusicBrainzAlbumMetadataResponse {
        val query = MusicBrainzAlbumMetadataQuery(
            album = album,
            artist = artist,
            year = year,
            releaseMbids = releaseMbids,
        )
        val key = listOf(
            album.normalizedBackendCacheKey(),
            artist.normalizedBackendCacheKey(),
            year?.toString().orEmpty(),
            releaseMbids.joinToString(","),
        ).joinToString("|")
        albumCache.get(key)?.let { return it }
        return singleFlight.run("album:$key") {
            albumCache.get(key)?.let { return@run it }
            val response = adapter.albumMetadata(query)
            albumCache.put(key, response)
            response
        }
    }

    suspend fun artistArtwork(
        artist: String,
        limit: Int,
        fast: Boolean,
        excludedArtworkUrls: List<String>,
    ): MusicBrainzArtistArtworkResponse {
        val excludedKey = excludedArtworkUrls
            .mapNotNull { it.canonicalArtworkUrl() }
            .sorted()
            .joinToString(",")
        val key = "${artist.normalizedBackendCacheKey()}:$limit:${if (fast) "fast" else "full"}:$excludedKey"
        artistCache.get(key)?.let { return it }
        return singleFlight.run("artist:$key") {
            artistCache.get(key)?.let { return@run it }
            val response = adapter.artistArtwork(artist, limit, fast, excludedArtworkUrls)
            artistCache.put(key, response)
            response
        }
    }
}

interface MusicBrainzAdapter {
    suspend fun albumMetadata(query: MusicBrainzAlbumMetadataQuery): MusicBrainzAlbumMetadataResponse
    suspend fun artistArtwork(
        artist: String,
        limit: Int,
        fast: Boolean,
        excludedArtworkUrls: List<String>,
    ): MusicBrainzArtistArtworkResponse
}

class MusicBrainzApiAdapter(
    private val httpClient: HttpClient,
    private val userAgent: String,
    private val rateGate: MusicBrainzRequestGate,
) : MusicBrainzAdapter {
    private val noRedirectHttpClient: HttpClient by lazy {
        httpClient.config {
            followRedirects = false
        }
    }

    override suspend fun albumMetadata(query: MusicBrainzAlbumMetadataQuery): MusicBrainzAlbumMetadataResponse {
        val candidate = firstReleaseByMbid(query.releaseMbids)
            ?: searchRelease(query.album, query.artist, query.year)
            ?: return MusicBrainzAlbumMetadataResponse(query = query)
        val release = lookupRelease(candidate.id) ?: candidate
        val match = release.toMatch(candidate.score)
        val credits = release.toCreditSections()
        val artwork = artworkForRelease(
            releaseId = release.id,
            releaseGroupId = release.releaseGroup?.id ?: candidate.releaseGroup?.id,
            title = release.title,
            maxItems = 36,
        )
        return MusicBrainzAlbumMetadataResponse(
            query = query,
            match = match,
            credits = credits,
            artwork = artwork,
        )
    }

    override suspend fun artistArtwork(
        artist: String,
        limit: Int,
        fast: Boolean,
        excludedArtworkUrls: List<String>,
    ): MusicBrainzArtistArtworkResponse {
        val match = searchArtist(artist)
            ?: return MusicBrainzArtistArtworkResponse(artist = artist)
        val browseLimit = if (fast) {
            ArtistArtworkReleaseGroupBrowseLimit
        } else {
            (limit * 2).coerceIn(limit, ArtistArtworkReleaseGroupBrowseLimit)
        }
        val scanLimit = if (fast) ArtistArtworkFastReleaseGroupScanLimit else ArtistArtworkReleaseGroupScanLimit
        val groups = browseReleaseGroups(
            artistId = match.id,
            limit = browseLimit,
        ).take(scanLimit)
        val images = artistArtworkForReleaseGroups(groups, limit, fast, excludedArtworkUrls)
        return MusicBrainzArtistArtworkResponse(
            artist = artist,
            match = MusicBrainzMetadataMatch(
                musicBrainzId = match.id,
                title = match.name,
                artist = match.name,
                score = match.score,
            ),
            artwork = images,
        )
    }

    private suspend fun firstReleaseByMbid(ids: List<String>): MbRelease? {
        for (id in ids) {
            val release = lookupRelease(id)
            if (release != null) return release
        }
        return null
    }

    private suspend fun searchRelease(album: String, artist: String, year: Int?): MbRelease? {
        val query = buildString {
            append("release:\"${album.escapeLucenePhrase()}\" AND artist:\"${artist.escapeLucenePhrase()}\"")
            if (year != null) append(" AND date:$year")
        }
        val response: MbReleaseSearchResponse = musicBrainzGet("https://musicbrainz.org/ws/2/release") {
            parameter("fmt", "json")
            parameter("limit", "5")
            parameter("query", query)
        }.body()
        return response.releases
            .maxWithOrNull(
                compareBy<MbRelease> { release -> if (year != null && release.year == year) 1 else 0 }
                    .thenBy { it.score ?: 0 },
            )
    }

    private suspend fun lookupRelease(id: String): MbRelease? {
        val response = musicBrainzGet(
            url = "https://musicbrainz.org/ws/2/release/$id",
            allowNotFound = true,
        ) {
            parameter("fmt", "json")
            parameter(
                "inc",
                listOf(
                    "artist-credits",
                    "labels",
                    "recordings",
                    "artist-rels",
                    "recording-level-rels",
                    "work-level-rels",
                    "work-rels",
                ).joinToString("+"),
            )
        }
        if (response.status == HttpStatusCode.NotFound) return null
        return response.body()
    }

    private suspend fun searchArtist(artist: String): MbArtist? {
        val response: MbArtistSearchResponse = musicBrainzGet("https://musicbrainz.org/ws/2/artist") {
            parameter("fmt", "json")
            parameter("limit", "1")
            parameter("query", "artist:\"${artist.escapeLucenePhrase()}\"")
        }.body()
        return response.artists.firstOrNull()
    }

    private suspend fun browseReleaseGroups(artistId: String, limit: Int): List<MbReleaseGroup> {
        val response: MbReleaseGroupBrowseResponse = musicBrainzGet("https://musicbrainz.org/ws/2/release-group") {
            parameter("fmt", "json")
            parameter("artist", artistId)
            parameter("type", "album|ep|single")
            parameter("limit", limit.coerceIn(1, 50).toString())
            parameter("inc", "artist-credits")
        }.body()
        return response.releaseGroups
            .sortedWith(
                compareByDescending<MbReleaseGroup> { it.primaryType.releaseGroupPriority() }
                    .thenByDescending { it.year ?: 0 },
            )
    }

    private suspend fun artworkForRelease(
        releaseId: String,
        releaseGroupId: String?,
        title: String,
        maxItems: Int,
    ): List<MusicBrainzArtwork> {
        val releaseImages = safeProviderList { coverArtRelease(releaseId, title, maxItems) }
        if (releaseImages.isNotEmpty() || releaseGroupId == null) return releaseImages
        return safeProviderList { coverArtReleaseGroup(releaseGroupId, title, maxItems) }
    }

    private suspend fun artistArtworkForReleaseGroups(
        groups: List<MbReleaseGroup>,
        limit: Int,
        fast: Boolean,
        excludedArtworkUrls: List<String>,
    ): List<MusicBrainzArtwork> = coroutineScope {
        var images = emptyList<MusicBrainzArtwork>()
        val batchSize = if (fast) ArtistArtworkFastCoverArtConcurrency else ArtistArtworkCoverArtConcurrency
        val minimumUsefulItems = if (fast) {
            limit.coerceAtMost(ArtistArtworkFastMinimumUsefulItems)
        } else {
            limit
        }
        for (groupBatch in groups.chunked(batchSize)) {
            val requests = groupBatch.mapIndexed { index, group ->
                async {
                    ArtistArtworkBatchResult(
                        index = index,
                        artwork = withTimeoutOrNull(if (fast) ArtistArtworkFastCoverArtTimeoutMs else ArtistArtworkCoverArtTimeoutMs) {
                            safeProviderList {
                                if (fast) {
                                    coverArtReleaseGroupFront(group.id, group.title)
                                        ?.let(::listOf)
                                        .orEmpty()
                                } else {
                                    coverArtReleaseGroup(
                                        releaseGroupId = group.id,
                                        title = group.title,
                                        maxItems = ArtistArtworkPerGroupImageLimit,
                                    )
                                }
                            }
                        }.orEmpty(),
                    )
                }
            }.toMutableList()
            val completed = mutableListOf<ArtistArtworkBatchResult>()
            while (requests.isNotEmpty()) {
                val (request, result) = select<Pair<Deferred<ArtistArtworkBatchResult>, ArtistArtworkBatchResult>> {
                    requests.forEach { request ->
                        request.onAwait { request to it }
                    }
                }
                requests.remove(request)
                completed += result
                val candidate = (images + completed.orderedArtwork())
                    .withoutExcludedArtwork(excludedArtworkUrls)
                    .deduplicateArtwork()
                if (candidate.size >= limit || (fast && candidate.size >= minimumUsefulItems)) {
                    requests.forEach { it.cancel() }
                    return@coroutineScope candidate.take(limit)
                }
            }
            images = (images + completed.orderedArtwork())
                .withoutExcludedArtwork(excludedArtworkUrls)
                .deduplicateArtwork()
            if (images.size >= limit) break
        }
        images.take(limit)
    }

    private suspend fun coverArtRelease(releaseId: String, title: String, maxItems: Int): List<MusicBrainzArtwork> {
        val response = coverArtGet("https://coverartarchive.org/release/$releaseId", allowNotFound = true)
        if (response.status == HttpStatusCode.NotFound) {
            response.discardBody()
            return emptyList()
        }
        return response.body<CoverArtArchiveResponse>().toArtwork(source = title, maxItems = maxItems)
    }

    private suspend fun coverArtReleaseGroup(releaseGroupId: String, title: String, maxItems: Int): List<MusicBrainzArtwork> {
        val response = coverArtGet("https://coverartarchive.org/release-group/$releaseGroupId", allowNotFound = true)
        if (response.status == HttpStatusCode.NotFound) {
            response.discardBody()
            return emptyList()
        }
        return response.body<CoverArtArchiveResponse>().toArtwork(source = title, maxItems = maxItems)
    }

    private suspend fun coverArtReleaseGroupFront(releaseGroupId: String, title: String): MusicBrainzArtwork? {
        val imageUrl = coverArtRedirectLocation(
            url = "https://coverartarchive.org/release-group/$releaseGroupId/front-$ArtistArtworkFastFrontThumbnailSize",
            allowNotFound = true,
        ) ?: return null
        return MusicBrainzArtwork(
            id = "$releaseGroupId:front",
            imageUrl = imageUrl,
            thumbnailUrl = imageUrl,
            largeThumbnailUrl = imageUrl,
            title = "Front",
            types = listOf("Front"),
            front = true,
            source = title,
        )
    }

    private suspend fun musicBrainzGet(
        url: String,
        allowNotFound: Boolean = false,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): HttpResponse {
        rateGate.awaitTurn()
        val response = httpClient.get(url) {
            header(HttpHeaders.UserAgent, userAgent)
            block()
        }
        if (allowNotFound && response.status == HttpStatusCode.NotFound) return response
        response.requireProviderSuccess("MusicBrainz")
        return response
    }

    private suspend fun coverArtGet(
        url: String,
        allowNotFound: Boolean = false,
        redirectDepth: Int = 0,
    ): HttpResponse {
        val response = httpClient.get(url) {
            header(HttpHeaders.UserAgent, userAgent)
        }
        if (response.status.value in 300..399) {
            if (redirectDepth >= MaxCoverArtRedirects) {
                response.discardBody()
                throw ProviderApiException("Cover Art Archive API redirected too many times.")
            }
            val location = response.headers[HttpHeaders.Location] ?: run {
                response.discardBody()
                throw ProviderApiException("Cover Art Archive API returned HTTP ${response.status.value} without a redirect location.")
            }
            response.discardBody()
            return coverArtGet(
                url = location.coverArtRedirectUrl(),
                allowNotFound = allowNotFound,
                redirectDepth = redirectDepth + 1,
            )
        }
        if (allowNotFound && response.status == HttpStatusCode.NotFound) return response
        if (!response.status.isSuccess()) {
            throw ProviderApiException("Cover Art Archive API returned HTTP ${response.status.value}.")
        }
        return response
    }

    private suspend fun coverArtRedirectLocation(
        url: String,
        allowNotFound: Boolean = false,
    ): String? {
        val response = noRedirectHttpClient.head(url) {
            header(HttpHeaders.UserAgent, userAgent)
        }
        try {
            if (response.status.value in 300..399) {
                val location = response.headers[HttpHeaders.Location]
                    ?: throw ProviderApiException("Cover Art Archive API returned HTTP ${response.status.value} without a redirect location.")
                return location.coverArtRedirectUrl().normalizedRemoteArtworkUrl()
            }
            if (allowNotFound && response.status == HttpStatusCode.NotFound) return null
            if (!response.status.isSuccess()) {
                throw ProviderApiException("Cover Art Archive API returned HTTP ${response.status.value}.")
            }
            return url.normalizedRemoteArtworkUrl()
        } finally {
            response.discardBody()
        }
    }
}

private suspend fun HttpResponse.discardBody() {
    runCatching { bodyAsText() }
}

interface MusicBrainzRequestGate {
    suspend fun awaitTurn()
}

class TimedMusicBrainzRequestGate(
    private val minimumIntervalMs: Long = 1_000L,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
) : MusicBrainzRequestGate {
    private val mutex = Mutex()
    private var lastRequestMs: Long = 0L

    override suspend fun awaitTurn() {
        mutex.withLock {
            val now = clockMs()
            val waitMs = minimumIntervalMs - (now - lastRequestMs)
            if (waitMs > 0) delayMs(waitMs)
            lastRequestMs = clockMs()
        }
    }
}

object NoopMusicBrainzRequestGate : MusicBrainzRequestGate {
    override suspend fun awaitTurn() = Unit
}

class MusicBrainzCache<T : Any>(
    private val ttlMs: Long,
    private val clockMs: () -> Long,
    private val maxEntries: Int = 512,
) {
    private val entries = ConcurrentHashMap<String, CacheEntry<T>>()
    private val lastCleanupMs = AtomicLong(0L)

    fun get(key: String): T? {
        val now = clockMs()
        cleanupExpiredIfNeeded(now)
        val entry = entries[key] ?: return null
        return if (now - entry.createdAtMs <= ttlMs) {
            entry.response
        } else {
            entries.remove(key, entry)
            null
        }
    }

    fun put(key: String, response: T) {
        val now = clockMs()
        cleanupExpiredIfNeeded(now)
        entries[key] = CacheEntry(now, response)
        trimToMaxEntries()
    }

    private fun cleanupExpiredIfNeeded(now: Long) {
        val intervalMs = ttlMs.coerceAtMost(60_000L).coerceAtLeast(1_000L)
        val last = lastCleanupMs.get()
        if (now - last < intervalMs || !lastCleanupMs.compareAndSet(last, now)) return
        entries.forEach { (key, entry) ->
            if (now - entry.createdAtMs > ttlMs) {
                entries.remove(key, entry)
            }
        }
    }

    private fun trimToMaxEntries() {
        val overflow = entries.size - maxEntries
        if (overflow <= 0) return
        entries.entries
            .sortedBy { it.value.createdAtMs }
            .take(overflow)
            .forEach { (key, entry) -> entries.remove(key, entry) }
    }

    private data class CacheEntry<T>(
        val createdAtMs: Long,
        val response: T,
    )
}

@Serializable
private data class MbReleaseSearchResponse(
    val releases: List<MbRelease> = emptyList(),
)

@Serializable
private data class MbRelease(
    val id: String,
    val title: String,
    val score: Int? = null,
    val date: String? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    @SerialName("release-group") val releaseGroup: MbReleaseGroupRef? = null,
    @SerialName("label-info") val labelInfo: List<MbLabelInfo> = emptyList(),
    val relations: List<MbRelation> = emptyList(),
    val media: List<MbMedium> = emptyList(),
) {
    val year: Int?
        get() = date?.take(4)?.toIntOrNull()
}

@Serializable
private data class MbArtistCredit(
    val name: String? = null,
    val joinphrase: String? = null,
    val artist: MbArtistRef? = null,
)

@Serializable
private data class MbArtistRef(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
private data class MbReleaseGroupRef(
    val id: String? = null,
    val title: String? = null,
)

@Serializable
private data class MbLabelInfo(
    val label: MbLabel? = null,
)

@Serializable
private data class MbLabel(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
private data class MbMedium(
    val tracks: List<MbTrack> = emptyList(),
)

@Serializable
private data class MbTrack(
    val recording: MbRecording? = null,
)

@Serializable
private data class MbRecording(
    val title: String? = null,
    val relations: List<MbRelation> = emptyList(),
)

@Serializable
private data class MbRelation(
    val type: String? = null,
    val artist: MbArtistRef? = null,
    val work: MbWork? = null,
    val attributes: List<String> = emptyList(),
)

@Serializable
private data class MbWork(
    val relations: List<MbRelation> = emptyList(),
)

@Serializable
private data class MbArtistSearchResponse(
    val artists: List<MbArtist> = emptyList(),
)

@Serializable
private data class MbArtist(
    val id: String,
    val name: String,
    val score: Int? = null,
)

@Serializable
private data class MbReleaseGroupBrowseResponse(
    @SerialName("release-groups") val releaseGroups: List<MbReleaseGroup> = emptyList(),
)

@Serializable
private data class MbReleaseGroup(
    val id: String,
    val title: String,
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
) {
    val year: Int?
        get() = firstReleaseDate?.take(4)?.toIntOrNull()
}

private data class ArtistArtworkBatchResult(
    val index: Int,
    val artwork: List<MusicBrainzArtwork>,
)

@Serializable
private data class CoverArtArchiveResponse(
    val images: List<CoverArtImage> = emptyList(),
)

@Serializable
private data class CoverArtImage(
    val id: JsonElement? = null,
    val image: String? = null,
    val thumbnails: CoverArtThumbnails? = null,
    val types: List<String> = emptyList(),
    val front: Boolean = false,
    val back: Boolean = false,
    val approved: Boolean = true,
    val comment: String? = null,
)

@Serializable
private data class CoverArtThumbnails(
    @SerialName("250") val small250: String? = null,
    @SerialName("500") val medium500: String? = null,
    @SerialName("1200") val large1200: String? = null,
    val small: String? = null,
    val large: String? = null,
)

private fun MbRelease.toMatch(scoreOverride: Int?): MusicBrainzMetadataMatch =
    MusicBrainzMetadataMatch(
        musicBrainzId = id,
        releaseGroupId = releaseGroup?.id,
        title = title,
        artist = artistCredit.displayName(),
        year = year,
        score = scoreOverride ?: score,
    )

private fun MbRelease.toCreditSections(): List<MusicBrainzCreditSection> {
    val groups = linkedMapOf<String, LinkedHashSet<String>>()
    fun add(role: String, name: String?) {
        val normalized = name?.trim()?.takeIf { it.isNotBlank() } ?: return
        groups.getOrPut(role) { linkedSetOf() } += normalized
    }

    add("Album artist", artistCredit.displayName())
    labelInfo.mapNotNull { it.label?.name }.forEach { add("Label", it) }
    val allRelations = buildList {
        addAll(relations)
        media.forEach { medium ->
            medium.tracks.forEach { track ->
                val recordingRelations = track.recording?.relations.orEmpty()
                addAll(recordingRelations)
                recordingRelations.forEach { relation ->
                    addAll(relation.work?.relations.orEmpty())
                }
            }
        }
    }
    allRelations.forEach { relation ->
        val role = relation.type.curatedCreditRole() ?: return@forEach
        val name = relation.artist?.name ?: return@forEach
        val detail = relation.attributes
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString(", ")
            .takeIf { role == "Performance" && it.isNotBlank() }
        add(role, if (detail == null) name else "$name ($detail)")
    }

    val roleOrder = listOf("Album artist", "Label", "Production", "Engineering", "Writing", "Performance", "Visuals", "Other")
    return roleOrder.mapNotNull { role ->
        val names = groups[role]?.toList().orEmpty()
        MusicBrainzCreditSection(role, names).takeIf { it.names.isNotEmpty() }
    }
}

private fun List<MbArtistCredit>.displayName(): String? =
    joinToString("") { credit ->
        (credit.name ?: credit.artist?.name).orEmpty() + credit.joinphrase.orEmpty()
    }.trim().takeIf { it.isNotBlank() }

private fun CoverArtArchiveResponse.toArtwork(source: String, maxItems: Int): List<MusicBrainzArtwork> =
    images
        .asSequence()
        .filter { !it.image.isNullOrBlank() }
        .sortedWith(compareBy<CoverArtImage> { it.coverSortBucket() }.thenBy { it.id.artworkId().orEmpty() })
        .map { image ->
            val imageUrl = image.image.normalizedRemoteArtworkUrl().orEmpty()
            MusicBrainzArtwork(
                id = image.id.artworkId() ?: imageUrl,
                imageUrl = imageUrl,
                thumbnailUrl = image.thumbnails?.thumbnailUrl(),
                largeThumbnailUrl = image.thumbnails?.largeThumbnailUrl(),
                title = image.displayTitle(),
                comment = image.comment?.takeIf { it.isNotBlank() },
                types = image.types,
                front = image.front,
                back = image.back,
                approved = image.approved,
                source = source,
            )
        }
        .distinctBy { it.imageUrl }
        .take(maxItems)
        .toList()

private fun CoverArtImage.coverSortBucket(): Int =
    when {
        front -> 0
        types.any { it.equals("Front", ignoreCase = true) } -> 1
        back -> 2
        types.any { it.equals("Back", ignoreCase = true) } -> 3
        types.any { it.equals("Booklet", ignoreCase = true) } -> 4
        types.any { it.equals("Media", ignoreCase = true) } -> 5
        types.any { it.equals("Tray", ignoreCase = true) } -> 6
        else -> 7
    }

private fun CoverArtImage.displayTitle(): String? =
    when {
        types.isNotEmpty() -> types.joinToString(", ")
        front -> "Front"
        back -> "Back"
        else -> null
    }

private fun List<ArtistArtworkBatchResult>.orderedArtwork(): List<MusicBrainzArtwork> =
    sortedBy { it.index }.flatMap { it.artwork }

private fun CoverArtThumbnails.thumbnailUrl(): String? =
    (medium500 ?: small250 ?: small ?: large ?: large1200).normalizedRemoteArtworkUrl()

private fun CoverArtThumbnails.largeThumbnailUrl(): String? =
    (large1200 ?: large ?: medium500 ?: small250 ?: small).normalizedRemoteArtworkUrl()

private fun JsonElement?.artworkId(): String? =
    when (this) {
        is JsonPrimitive -> contentOrNull
        null -> null
        else -> toString()
    }?.trim()?.takeIf { it.isNotBlank() && it != "null" }

private fun String?.normalizedRemoteArtworkUrl(): String? {
    val trimmed = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return if (trimmed.startsWith("http://")) {
        "https://${trimmed.removePrefix("http://")}"
    } else {
        trimmed
    }
}

private fun List<MusicBrainzArtwork>.deduplicateArtwork(): List<MusicBrainzArtwork> =
    distinctBy { it.canonicalIdentity() }

private fun List<MusicBrainzArtwork>.withoutExcludedArtwork(excludedUrls: List<String>): List<MusicBrainzArtwork> {
    val excluded = excludedUrls.mapNotNullTo(mutableSetOf()) { it.canonicalArtworkUrl() }
    if (excluded.isEmpty()) return this
    return filterNot { artwork ->
        listOf(artwork.imageUrl, artwork.thumbnailUrl, artwork.largeThumbnailUrl)
            .mapNotNull { it.canonicalArtworkUrl() }
            .any { it in excluded }
    }
}

private fun MusicBrainzArtwork.canonicalIdentity(): String =
    listOf(imageUrl, thumbnailUrl, largeThumbnailUrl)
        .mapNotNull { it.canonicalArtworkUrl() }
        .firstOrNull()
        ?: imageUrl

private fun String?.canonicalArtworkUrl(): String? =
    normalizedRemoteArtworkUrl()
        ?.substringBefore('#')
        ?.substringBefore('?')
        ?.replace(Regex("-(250|500|1200|small|large)(?=\\.[^./]+$)"), "")
        ?.lowercase(Locale.US)

private suspend fun safeProviderList(block: suspend () -> List<MusicBrainzArtwork>): List<MusicBrainzArtwork> =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        emptyList()
    }

private fun String?.curatedCreditRole(): String? =
    when (this?.trim()?.lowercase(Locale.US)) {
        "producer", "co-producer", "executive producer", "assistant producer", "associate producer" -> "Production"
        "engineer", "recording engineer", "mix", "mix engineer", "mastering", "mastering engineer" -> "Engineering"
        "composer", "lyricist", "writer", "arranger", "orchestrator" -> "Writing"
        "instrument", "performer", "vocal", "vocal arranger" -> "Performance"
        "design", "art direction", "illustration", "photography", "cover art" -> "Visuals"
        null, "" -> null
        else -> null
    }

private fun String?.releaseGroupPriority(): Int =
    when (this?.lowercase(Locale.US)) {
        "album" -> 3
        "ep" -> 2
        "single" -> 1
        else -> 0
    }

private fun String.escapeLucenePhrase(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

private fun String.isLikelyMbid(): Boolean =
    matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))

private fun String.coverArtRedirectUrl(): String =
    if (startsWith("http://") || startsWith("https://")) this else "https://coverartarchive.org${if (startsWith("/")) this else "/$this"}"

private const val ArtistArtworkReleaseGroupBrowseLimit = 24
private const val ArtistArtworkReleaseGroupScanLimit = 12
private const val ArtistArtworkFastReleaseGroupScanLimit = 12
private const val ArtistArtworkCoverArtConcurrency = 4
private const val ArtistArtworkFastCoverArtConcurrency = 4
private const val ArtistArtworkCoverArtTimeoutMs = 8_000L
private const val ArtistArtworkFastCoverArtTimeoutMs = 1_500L
private const val ArtistArtworkPerGroupImageLimit = 8
private const val ArtistArtworkFastMinimumUsefulItems = 4
private const val ArtistArtworkFastFrontThumbnailSize = 500
private const val DefaultArtistArtworkLimit = 12
private const val MaxCoverArtRedirects = 4
