package com.phoebe.app.backend.lyrics

import com.phoebe.app.backend.MissingProviderCredentialException
import com.phoebe.app.backend.BackendSingleFlight
import com.phoebe.app.backend.PhoebeBackendEnvironment
import com.phoebe.app.backend.PhoebeBackendFeature
import com.phoebe.app.backend.normalizedBackendCacheKey
import com.phoebe.app.backend.optionalBackendQueryParameter
import com.phoebe.app.backend.positiveDurationMsQueryParameter
import com.phoebe.app.backend.requireProviderSuccess
import com.phoebe.app.backend.requiredBackendQueryParameter
import com.phoebe.app.backend.tryAcquire
import com.phoebe.app.domain.GeniusReferent
import com.phoebe.app.domain.GeniusReferentAnnotation
import com.phoebe.app.domain.GeniusReferentsResponse
import com.phoebe.app.domain.GeniusSongReference
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class LyricsBackendFeature : PhoebeBackendFeature {
    override fun install(application: Application, environment: PhoebeBackendEnvironment) {
        val service = GeniusReferentsService(
            adapter = GeniusApiAdapter(environment.httpClient, environment.config.geniusAccessToken),
            cache = GeniusReferentsCache(environment.config.cacheTtlMinutes * 60_000L, environment.clockMs),
        )

        application.routing {
            get("/v1/genius/referents") {
                if (!call.tryAcquire(environment, "genius-referents")) return@get
                val artist = call.requiredBackendQueryParameter("artist")
                val title = call.requiredBackendQueryParameter("title")
                val album = call.optionalBackendQueryParameter("album")
                val durationMs = call.positiveDurationMsQueryParameter("durationMs")
                call.respond(
                    service.referents(
                        artist = artist,
                        title = title,
                        album = album,
                        durationMs = durationMs,
                    ),
                )
            }
        }
    }
}

class GeniusReferentsService(
    private val adapter: GeniusReferentsAdapter,
    private val cache: GeniusReferentsCache,
    private val singleFlight: BackendSingleFlight = BackendSingleFlight(),
) {
    suspend fun referents(
        artist: String,
        title: String,
        album: String?,
        durationMs: Long?,
    ): GeniusReferentsResponse {
        val key = listOf(
            artist.normalizedBackendCacheKey(),
            title.normalizedBackendCacheKey(),
            album.orEmpty().normalizedBackendCacheKey(),
            durationMs?.toString().orEmpty(),
        ).joinToString("|")
        cache.get(key)?.let { return it }
        return singleFlight.run(key) {
            cache.get(key)?.let { return@run it }
            val response = adapter.referents(artist = artist, title = title, album = album, durationMs = durationMs)
            cache.put(key, response)
            response
        }
    }
}

interface GeniusReferentsAdapter {
    suspend fun referents(
        artist: String,
        title: String,
        album: String?,
        durationMs: Long?,
    ): GeniusReferentsResponse
}

class GeniusReferentsCache(
    private val ttlMs: Long,
    private val clockMs: () -> Long,
    private val maxEntries: Int = 512,
) {
    private val entries = ConcurrentHashMap<String, CacheEntry>()
    private val lastCleanupMs = AtomicLong(0L)

    fun get(key: String): GeniusReferentsResponse? {
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

    fun put(key: String, response: GeniusReferentsResponse) {
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

    private data class CacheEntry(
        val createdAtMs: Long,
        val response: GeniusReferentsResponse,
    )
}

class GeniusApiAdapter(
    private val httpClient: HttpClient,
    private val accessToken: String?,
) : GeniusReferentsAdapter {
    override suspend fun referents(
        artist: String,
        title: String,
        album: String?,
        durationMs: Long?,
    ): GeniusReferentsResponse {
        val token = accessToken ?: throw MissingProviderCredentialException("GENIUS_ACCESS_TOKEN is not configured.")
        val songs = search(artist = artist, title = title, token = token)
        val selected = songs.bestMatch(artist = artist, title = title) ?: return GeniusReferentsResponse()
        return GeniusReferentsResponse(
            song = selected,
            referents = referents(selected.id, token),
        )
    }

    private suspend fun search(artist: String, title: String, token: String): List<GeniusSongReference> {
        val songs = mutableListOf<GeniusSongReference>()
        for (query in geniusSearchQueries(artist = artist, title = title)) {
            val response = geniusRequest("$GeniusApiBaseUrl/search", token) {
                parameter("q", query)
                parameter("per_page", GeniusSearchPerPage)
            }
            val body: GeniusSearchApiResponse = response.body()
            songs += body.response?.hits.orEmpty().mapNotNull { hit ->
                hit.result?.toSong()
            }
            if (songs.maxOfOrNull { song -> song.matchScore(artist = artist, title = title) }?.let { it >= StrongMatchScore } == true) {
                break
            }
        }
        return songs.distinctBy { it.id }
    }

    private suspend fun referents(songId: Long, token: String): List<GeniusReferent> {
        val referents = mutableListOf<GeniusReferent>()
        var page = 1
        var pagesFetched = 0
        while (pagesFetched < MaxReferentsPages) {
            val response = geniusRequestOrNullOnNotFound("$GeniusApiBaseUrl/referents", token) {
                parameter("song_id", songId)
                parameter("text_format", "plain")
                parameter("per_page", GeniusReferentsPerPage)
                parameter("page", page)
            } ?: break
            pagesFetched++
            val body: GeniusReferentsApiResponse = response.body()
            referents += body.response?.referents.orEmpty().mapNotNull { it.toReferent() }
            val nextPage = body.response?.nextPage ?: break
            if (nextPage <= page) break
            page = nextPage
        }
        return referents
    }

    private suspend fun geniusRequest(
        url: String,
        token: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer ${token.trim()}")
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, "Phoebe/1.0")
            block()
        }
        response.requireProviderSuccess("Genius")
        return response
    }

    private suspend fun geniusRequestOrNullOnNotFound(
        url: String,
        token: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse? {
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer ${token.trim()}")
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, "Phoebe/1.0")
            block()
        }
        if (response.status == HttpStatusCode.NotFound) return null
        response.requireProviderSuccess("Genius")
        return response
    }
}

@Serializable
private data class GeniusSearchApiResponse(
    val response: GeniusSearchPayload? = null,
)

@Serializable
private data class GeniusSearchPayload(
    val hits: List<GeniusSearchHit> = emptyList(),
)

@Serializable
private data class GeniusSearchHit(
    val result: GeniusSongPayload? = null,
)

@Serializable
private data class GeniusSongPayload(
    val id: Long? = null,
    val title: String? = null,
    val url: String? = null,
    @SerialName("primary_artist")
    val primaryArtist: GeniusArtistPayload? = null,
) {
    fun toSong(): GeniusSongReference? {
        val songId = id ?: return null
        return GeniusSongReference(
            id = songId,
            title = title.orEmpty(),
            url = url?.takeIf { it.isNotBlank() },
            primaryArtistName = primaryArtist?.name?.takeIf { it.isNotBlank() },
        )
    }
}

@Serializable
private data class GeniusArtistPayload(
    val name: String? = null,
)

@Serializable
private data class GeniusReferentsApiResponse(
    val response: GeniusReferentsPayload? = null,
)

@Serializable
private data class GeniusReferentsPayload(
    val referents: List<GeniusReferentPayload> = emptyList(),
    @SerialName("next_page")
    val nextPage: Int? = null,
)

@Serializable
private data class GeniusReferentPayload(
    val id: Long? = null,
    val fragment: String? = null,
    val annotations: List<GeniusAnnotationPayload> = emptyList(),
) {
    fun toReferent(): GeniusReferent? {
        val referentId = id ?: return null
        val text = fragment?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return GeniusReferent(
            id = referentId,
            fragment = text,
            annotations = annotations.mapNotNull { it.toAnnotation() },
        )
    }
}

@Serializable
private data class GeniusAnnotationPayload(
    val id: Long? = null,
    val body: GeniusAnnotationBodyPayload? = null,
    val authors: List<GeniusAnnotationAuthorPayload> = emptyList(),
    val verified: Boolean = false,
    @SerialName("votes_total")
    val votesTotal: Int? = null,
    val url: String? = null,
    @SerialName("share_url")
    val shareUrl: String? = null,
) {
    fun toAnnotation(): GeniusReferentAnnotation? {
        val annotationId = id ?: return null
        val plainBody = body?.plain?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return GeniusReferentAnnotation(
            id = annotationId,
            body = plainBody,
            authorName = authors.firstOrNull()?.name?.takeIf { it.isNotBlank() },
            verified = verified,
            votesTotal = votesTotal,
            url = (shareUrl ?: url)?.takeIf { it.isNotBlank() },
        )
    }
}

@Serializable
private data class GeniusAnnotationBodyPayload(
    val plain: String? = null,
)

@Serializable
private data class GeniusAnnotationAuthorPayload(
    val name: String? = null,
)

private fun geniusSearchQueries(artist: String, title: String): List<String> =
    listOf(
        listOf(artist.geniusPrimaryArtist(), title.geniusTitleWithoutFeaturing()),
        listOf(artist, title.geniusTitleWithoutFeaturing()),
        listOf(artist.geniusPrimaryArtist(), title),
        listOf(artist, title),
    )
        .map { parts -> parts.filter { it.isNotBlank() }.joinToString(" ") }
        .map { it.trim().replace(Regex("""\s+"""), " ") }
        .filter { it.isNotBlank() }
        .distinct()

private fun List<GeniusSongReference>.bestMatch(artist: String, title: String): GeniusSongReference? =
    map { song -> song to song.matchScore(artist = artist, title = title) }
        .filter { (_, score) -> score > 0 }
        .maxWithOrNull(compareBy<Pair<GeniusSongReference, Int>> { it.second }.thenBy { -indexOf(it.first) })
        ?.first

private fun GeniusSongReference.matchScore(artist: String, title: String): Int {
    val targetTitle = title.geniusTitleWithoutFeaturing().geniusComparableText()
    val targetArtist = artist.geniusPrimaryArtist().geniusComparableText()
    val songTitle = this.title.geniusTitleWithoutFeaturing().geniusComparableText()
    val songArtist = primaryArtistName.orEmpty().geniusComparableText()

    var score = 0
    when {
        songTitle == targetTitle -> score += 100
        songTitle.startsWith("$targetTitle ") -> score += 45
        songTitle.contains(" $targetTitle ") -> score += 25
    }
    if (targetArtist.isNotBlank() && songArtist.isNotBlank()) {
        when {
            songArtist == targetArtist -> score += 40
            songArtist.contains(targetArtist) || targetArtist.contains(songArtist) -> score += 25
        }
    }
    if (songTitle.looksLikeTranslationPage() || songArtist.startsWith("genius ")) score -= 50
    if (songTitle.looksLikePlaylistPage()) score -= 100
    return score
}

private fun String.geniusPrimaryArtist(): String =
    replace(FeaturingPattern, " ")
        .substringBefore(',')
        .trim()
        .replace(Regex("""\s+"""), " ")

private fun String.geniusTitleWithoutFeaturing(): String =
    replace(FeaturingSegmentPattern, " ")
        .replace(FeaturingPattern, " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

private fun String.geniusComparableText(): String =
    lowercase(Locale.US)
        .replace(Regex("""[^\p{L}\p{Nd}]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

private fun String.looksLikeTranslationPage(): Boolean =
    listOf(
        "traduccion",
        "traducción",
        "traducao",
        "tradução",
        "traduction",
        "traduzione",
        "ubersetzung",
        "übersetzung",
        "ceviri",
        "çeviri",
        "translation",
        "vertaling",
    ).any { marker -> contains(marker) }

private fun String.looksLikePlaylistPage(): Boolean =
    startsWith("new music friday") ||
        contains("essentials") ||
        contains("top songs") ||
        contains("playlist")

private const val GeniusApiBaseUrl = "https://api.genius.com"
private const val GeniusSearchPerPage = 10
private const val GeniusReferentsPerPage = 50
private const val MaxReferentsPages = 10
private const val StrongMatchScore = 100
private val FeaturingSegmentPattern = Regex(
    pattern = """(?i)[\[(][^\])]*(?:feat\.?|ft\.?|featuring|with)\s+[^)\]]*[\])]""",
)
private val FeaturingPattern = Regex(
    pattern = """(?i)\s+(?:-|–|—)?\s*(?:feat\.?|ft\.?|featuring|with)\s+.+$""",
)
