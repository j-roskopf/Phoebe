package com.phoebe.app.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.domain.GeniusReferent
import com.phoebe.app.domain.GeniusSongReference
import com.phoebe.app.domain.LyricsAnnotation
import com.phoebe.app.domain.LyricsAnnotations
import com.phoebe.app.domain.LyricsAnnotationTarget
import com.phoebe.app.domain.LyricsDocument
import com.phoebe.app.domain.LyricsLoadState
import com.phoebe.app.domain.LyricsSource
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.sources.LocalLibraryIO
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.math.roundToInt

@SingleIn(AppScope::class)
@Inject
class LyricsRepository(
    private val database: PhoebeDatabase,
    private val httpClient: HttpClient,
    private val appSettingsRepository: AppSettingsRepository,
    private val geniusBackendClient: GeniusBackendClient,
) {
    private val memoryCache = mutableMapOf<String, LyricsLoadState>()
    private val activeLoads = mutableMapOf<ActiveLyricsLoadKey, CompletableDeferred<LyricsLoadState>>()
    private val lookupMutex = Mutex()

    suspend fun clearMemoryCache() {
        lookupMutex.withLock {
            memoryCache.clear()
        }
    }

    suspend fun lyricsFor(
        track: Track,
        forceRefresh: Boolean = false,
        includeRemoteAnnotations: Boolean = true,
        forceRemoteAnnotationsRefresh: Boolean = forceRefresh,
    ): LyricsLoadState = withContext(Dispatchers.Default) {
        val fingerprint = track.lyricsFingerprint()
        val key = ActiveLyricsLoadKey(
            fingerprint = fingerprint,
            forceRefresh = forceRefresh,
            includeRemoteAnnotations = includeRemoteAnnotations,
            forceRemoteAnnotationsRefresh = forceRemoteAnnotationsRefresh,
        )
        val activeLoad = CompletableDeferred<LyricsLoadState>()
        val existingLoad = lookupMutex.withLock {
            activeLoads[key]?.let { return@withLock it }
            activeLoads[key] = activeLoad
            null
        }
        if (existingLoad != null) return@withContext existingLoad.await()
        try {
            val loaded = loadLyricsState(
                track = track,
                fingerprint = fingerprint,
                forceRefresh = forceRefresh,
                includeRemoteAnnotations = includeRemoteAnnotations,
                forceRemoteAnnotationsRefresh = forceRemoteAnnotationsRefresh,
            )
            val state = lookupMutex.withLock {
                val merged = mergeLyricsMemoryCacheState(
                    current = memoryCache[fingerprint],
                    loaded = loaded,
                    forceRefresh = forceRefresh,
                )
                memoryCache[fingerprint] = merged
                merged
            }
            activeLoad.complete(state)
            state
        } catch (error: Throwable) {
            activeLoad.completeExceptionally(error)
            throw error
        } finally {
            lookupMutex.withLock {
                if (activeLoads[key] === activeLoad) {
                    activeLoads.remove(key)
                }
            }
        }
    }

    private suspend fun loadLyricsState(
        track: Track,
        fingerprint: String,
        forceRefresh: Boolean,
        includeRemoteAnnotations: Boolean,
        forceRemoteAnnotationsRefresh: Boolean,
    ): LyricsLoadState {
        val cached = if (!forceRefresh) {
            lookupMutex.withLock { memoryCache[fingerprint] }
        } else {
            null
        }
        return if (cached != null) {
            if (includeRemoteAnnotations) {
                enrichCachedLyricsState(track, cached, forceRemoteAnnotationsRefresh)
            } else {
                cachedLyricsStateWithFreshCachedAnnotations(cached)
            }
        } else {
            loadLyrics(
                track = track,
                fingerprint = fingerprint,
                forceRefresh = forceRefresh,
                includeRemoteAnnotations = includeRemoteAnnotations,
                forceRemoteAnnotationsRefresh = forceRemoteAnnotationsRefresh,
            )
        }
    }

    private suspend fun loadLyrics(
        track: Track,
        fingerprint: String,
        forceRefresh: Boolean,
        includeRemoteAnnotations: Boolean,
        forceRemoteAnnotationsRefresh: Boolean,
    ): LyricsLoadState {
        val document = loadBaseLyrics(track, fingerprint, forceRefresh) ?: return LyricsLoadState.NotFound
        return LyricsLoadState.Loaded(
            if (includeRemoteAnnotations) {
                enrichWithGeniusAnnotations(track, document, forceRemoteAnnotationsRefresh)
            } else {
                document.withFreshCachedGeniusAnnotations(forceRefresh)
            },
        )
    }

    private suspend fun enrichCachedLyricsState(
        track: Track,
        state: LyricsLoadState,
        forceRemoteAnnotationsRefresh: Boolean,
    ): LyricsLoadState =
        when (state) {
            is LyricsLoadState.Loaded -> LyricsLoadState.Loaded(
                enrichWithGeniusAnnotations(track, state.document, forceRemoteAnnotationsRefresh),
            )
            LyricsLoadState.NotFound -> state
            else -> state
        }

    private suspend fun cachedLyricsStateWithFreshCachedAnnotations(state: LyricsLoadState): LyricsLoadState =
        when (state) {
            is LyricsLoadState.Loaded -> LyricsLoadState.Loaded(
                state.document.withFreshCachedGeniusAnnotations(forceRefresh = false),
            )
            else -> state
        }

    private fun mergeLyricsMemoryCacheState(
        current: LyricsLoadState?,
        loaded: LyricsLoadState,
        forceRefresh: Boolean,
    ): LyricsLoadState {
        if (forceRefresh || current !is LyricsLoadState.Loaded) return loaded
        if (loaded !is LyricsLoadState.Loaded) return current
        val existingAnnotations = current.document.annotations ?: return loaded
        if (loaded.document.annotations != null || current.document.trackFingerprint != loaded.document.trackFingerprint) return loaded
        return loaded.copy(document = loaded.document.copy(annotations = existingAnnotations))
    }

    private data class ActiveLyricsLoadKey(
        val fingerprint: String,
        val forceRefresh: Boolean,
        val includeRemoteAnnotations: Boolean,
        val forceRemoteAnnotationsRefresh: Boolean,
    )

    private suspend fun loadBaseLyrics(track: Track, fingerprint: String, forceRefresh: Boolean): LyricsDocument? {
        if (!forceRefresh) {
            cachedLyrics(fingerprint)?.let { return it }
        }
        localLyrics(track, fingerprint)?.let { document ->
            cache(document, rawSynced = if (document.synced) document.lines.toLrcText() else null, rawPlain = document.lines.toPlainText())
            return document
        }
        fetchLrclib(track, fingerprint)?.let { document ->
            cache(
                document = document,
                rawSynced = if (document.synced) document.lines.toLrcText() else null,
                rawPlain = document.lines.toPlainText(),
            )
            return document
        }
        return null
    }

    private suspend fun cachedLyrics(fingerprint: String): LyricsDocument? {
        val row = runCatching {
            database.lyricsQueries.selectLyrics(fingerprint).awaitAsOneOrNull()
        }.onFailure { error ->
            PhoebeLog.d("LyricsRepository") { "lyrics cache read failed: ${error.message}" }
        }.getOrNull() ?: return null
        val raw = row.syncedLyrics ?: row.plainLyrics ?: return null
        val lines = parseLyricsLines(raw)
        return LyricsDocument(
            trackFingerprint = fingerprint,
            lines = lines,
            source = LyricsSource.Cache,
            synced = lyricsAreSynced(lines),
            instrumental = row.instrumental != 0L,
        )
    }

    private suspend fun localLyrics(track: Track, fingerprint: String): LyricsDocument? {
        val raw = LocalLibraryIO.readLyrics(track.localUri ?: return null)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val lines = parseLyricsLines(raw)
        if (lines.isEmpty()) return null
        return LyricsDocument(
            trackFingerprint = fingerprint,
            lines = lines,
            source = if (lyricsAreSynced(lines)) LyricsSource.LocalSidecar else LyricsSource.LocalEmbedded,
            synced = lyricsAreSynced(lines),
        )
    }

    private suspend fun fetchLrclib(track: Track, fingerprint: String): LyricsDocument? {
        val response = runCatching {
            httpClient.get("https://lrclib.net/api/get") {
                header("User-Agent", "Phoebe/1.0 (https://github.com)")
                parameter("track_name", track.title)
                parameter("artist_name", track.artist)
                parameter("album_name", track.album)
                if (track.durationMs > 0L) {
                    parameter("duration", (track.durationMs / 1000.0).roundToInt())
                }
            }
        }.getOrElse { error ->
            PhoebeLog.d("LyricsRepository") { "LRCLIB request failed: ${error.message}" }
            return null
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (response.status.value !in 200..299) {
            PhoebeLog.d("LyricsRepository") { "LRCLIB returned HTTP ${response.status.value}" }
            return null
        }
        val dto = runCatching { response.body<LrclibLyricsResponse>() }.getOrNull() ?: return null
        if (dto.instrumental) {
            return LyricsDocument(
                trackFingerprint = fingerprint,
                lines = emptyList(),
                source = LyricsSource.Lrclib,
                synced = false,
                instrumental = true,
            )
        }
        val raw = dto.syncedLyrics?.takeIf { it.isNotBlank() }
            ?: dto.plainLyrics?.takeIf { it.isNotBlank() }
            ?: return null
        val lines = parseLyricsLines(raw)
        if (lines.isEmpty()) return null
        return LyricsDocument(
            trackFingerprint = fingerprint,
            lines = lines,
            source = LyricsSource.Lrclib,
            synced = lyricsAreSynced(lines),
        )
    }

    private suspend fun cache(document: LyricsDocument, rawSynced: String?, rawPlain: String?) {
        runCatching {
            database.lyricsQueries.upsertLyrics(
                trackFingerprint = document.trackFingerprint,
                source = document.source.name,
                syncedLyrics = rawSynced,
                plainLyrics = rawPlain,
                instrumental = if (document.instrumental) 1L else 0L,
            )
        }.onFailure { error ->
            PhoebeLog.d("LyricsRepository") { "lyrics cache write failed: ${error.message}" }
        }
    }

    private suspend fun enrichWithGeniusAnnotations(
        track: Track,
        document: LyricsDocument,
        forceRefresh: Boolean,
    ): LyricsDocument {
        if (!document.hasText || document.instrumental) return document

        if (!forceRefresh) {
            document.annotations?.takeIf { annotations -> annotations.isFresh() }?.let {
                return document
            }
        }
        val cached = cachedGeniusAnnotations(document.trackFingerprint)
        if (!forceRefresh && cached != null && cached.isFresh()) {
            return document.copy(annotations = cached)
        }

        val baseUrl = resolveEventsBackendBaseUrl(appSettingsRepository.settings.value.events)
            ?: return document.copy(annotations = cached)
        val annotations = runCatching {
            val response = geniusBackendClient.referents(baseUrl, track)
            val song = response.song ?: return@runCatching null
            response.referents.toLyricsAnnotations(song, document)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            PhoebeLog.d("LyricsRepository") { "Genius annotation enrichment failed: ${error.message}" }
        }.getOrNull() ?: return document.copy(annotations = cached)

        cacheGeniusAnnotations(document.trackFingerprint, annotations)
        return document.copy(annotations = annotations)
    }

    private suspend fun LyricsDocument.withFreshCachedGeniusAnnotations(forceRefresh: Boolean): LyricsDocument {
        if (!hasText || instrumental) return this
        annotations?.takeIf { annotationSet -> annotationSet.isFresh() }?.let {
            return this
        }
        if (forceRefresh) return copy(annotations = null)
        val cached = cachedGeniusAnnotations(trackFingerprint)?.takeIf { annotationSet -> annotationSet.isFresh() }
        return copy(annotations = cached)
    }

    private fun LyricsAnnotations.isFresh(): Boolean =
        matchingVersion == GeniusAnnotationMatchingVersion &&
            currentTimeMs() - fetchedAtMs < GeniusAnnotationCacheTtlMs

    private suspend fun cachedGeniusAnnotations(fingerprint: String): LyricsAnnotations? {
        val row = runCatching {
            database.lyricsQueries.selectGeniusAnnotations(fingerprint).awaitAsOneOrNull()
        }.onFailure { error ->
            PhoebeLog.d("LyricsRepository") { "Genius annotation cache read failed: ${error.message}" }
        }.getOrNull() ?: return null
        return runCatching {
            PhoebeDataJson.decodeFromString<LyricsAnnotations>(row.annotationsJson)
        }.onFailure { error ->
            PhoebeLog.d("LyricsRepository") { "Genius annotation cache decode failed: ${error.message}" }
        }.getOrNull()
    }

    private suspend fun cacheGeniusAnnotations(fingerprint: String, annotations: LyricsAnnotations) {
        runCatching {
            database.lyricsQueries.upsertGeniusAnnotations(
                trackFingerprint = fingerprint,
                geniusSongId = annotations.songId,
                geniusSongUrl = annotations.songUrl,
                annotationsJson = PhoebeDataJson.encodeToString(annotations),
                fetchedAtMs = annotations.fetchedAtMs,
            )
        }.onFailure { error ->
            PhoebeLog.d("LyricsRepository") { "Genius annotation cache write failed: ${error.message}" }
        }
    }

    private fun List<GeniusReferent>.toLyricsAnnotations(song: GeniusSongReference, document: LyricsDocument): LyricsAnnotations {
        val matched = mutableListOf<LyricsAnnotation>()
        val unmatched = mutableListOf<LyricsAnnotation>()
        forEach { referent ->
            val target = matchAnnotationTarget(referent.fragment, document.lines)
            referent.annotations.forEach { annotation ->
                val lyricsAnnotation = LyricsAnnotation(
                    id = annotation.id,
                    referentId = referent.id,
                    fragment = referent.fragment,
                    body = annotation.body,
                    target = target,
                    authorName = annotation.authorName,
                    verified = annotation.verified,
                    votesTotal = annotation.votesTotal,
                    url = annotation.url,
                )
                if (target == null) {
                    unmatched += lyricsAnnotation
                } else {
                    matched += lyricsAnnotation
                }
            }
        }
        return LyricsAnnotations(
            songId = song.id,
            songUrl = song.url,
            songTitle = song.title.takeIf { it.isNotBlank() },
            artistName = song.primaryArtistName,
            fetchedAtMs = currentTimeMs(),
            annotations = matched,
            unmatched = unmatched,
            matchingVersion = GeniusAnnotationMatchingVersion,
        )
    }
}

fun Track.lyricsFingerprint(): String =
    listOf(
        id.takeIf { it.isNotBlank() },
        title.normalizedLyricsKey(),
        artist.normalizedLyricsKey(),
        album.normalizedLyricsKey(),
        durationMs.takeIf { it > 0L }?.toString(),
    ).filterNotNull().joinToString("|")

private fun String.normalizedLyricsKey(): String =
    trim().lowercase().replace(Regex("""\s+"""), " ")

private fun matchAnnotationTarget(
    fragment: String,
    lines: List<com.phoebe.app.domain.LyricsLine>,
): LyricsAnnotationTarget? {
    val target = fragment.normalizedAnnotationText()
    if (target.isBlank()) return null
    val targetTokens = target.annotationTokens()
    val targetLineCount = fragment
        .lineSequence()
        .count { line -> line.normalizedAnnotationText().isNotBlank() }
        .coerceAtLeast(1)
    val normalizedLines = lines.map { it.text.normalizedAnnotationText() }
    val maxSpanSize = minOf(
        normalizedLines.size,
        maxOf(
            targetLineCount + GeniusAnnotationExtraSpanLineAllowance,
            minOf(targetTokens.size + GeniusAnnotationExtraSpanLineAllowance, GeniusAnnotationDefaultMaxSpanLines),
        ),
    )
    for (spanSize in 1..maxSpanSize) {
        for (startIndex in 0..normalizedLines.size - spanSize) {
            val endIndex = startIndex + spanSize - 1
            val indexes = mutableListOf<Int>()
            val parts = mutableListOf<String>()
            for (lineIndex in startIndex..endIndex) {
                val line = normalizedLines[lineIndex]
                if (line.isNotBlank()) {
                    indexes += lineIndex
                    parts += line
                }
            }
            val candidate = parts.joinToString(" ")
            if (candidate.matchesAnnotationTarget(target, targetTokens) && indexes.isNotEmpty()) {
                return LyricsAnnotationTarget(indexes)
            }
        }
    }
    return null
}

private fun String.matchesAnnotationTarget(target: String, targetTokens: List<String>): Boolean {
    if (this == target || startsWith("$target ") || endsWith(" $target") || contains(" $target ")) {
        return true
    }
    if (compactAnnotationText() == target.compactAnnotationText()) {
        return true
    }
    val candidateTokens = annotationTokens()
    if (
        targetTokens.size < 4 ||
        candidateTokens.isEmpty() ||
        candidateTokens.size > targetTokens.size + GeniusAnnotationFuzzyTokenSlack
    ) {
        return false
    }
    if (
        candidateTokens.size < targetTokens.size &&
        targetTokens.take(candidateTokens.size) == candidateTokens
    ) {
        return false
    }
    if (targetTokens.first() !in candidateTokens || targetTokens.last() !in candidateTokens) {
        return false
    }
    val lcs = longestCommonSubsequenceLength(targetTokens, candidateTokens)
    return lcs.toDouble() / targetTokens.size >= 0.80 &&
        lcs.toDouble() / candidateTokens.size >= 0.55
}

private fun String.annotationTokens(): List<String> =
    split(' ')
        .filter { it.isNotBlank() }
        .map { token ->
            if (token.length > 3 && token.endsWith("s")) token.dropLast(1) else token
        }

private fun longestCommonSubsequenceLength(left: List<String>, right: List<String>): Int {
    val previous = IntArray(right.size + 1)
    val current = IntArray(right.size + 1)
    for (leftIndex in left.indices) {
        for (rightIndex in right.indices) {
            current[rightIndex + 1] = if (left[leftIndex] == right[rightIndex]) {
                previous[rightIndex] + 1
            } else {
                maxOf(previous[rightIndex + 1], current[rightIndex])
            }
        }
        for (index in current.indices) {
            previous[index] = current[index]
            current[index] = 0
        }
    }
    return previous[right.size]
}

private fun String.normalizedAnnotationText(): String =
    lowercase()
        .replace(Regex("""['\u2019\u2018`\u00B4\u02BC\u02B9]"""), "")
        .replace(Regex("""[^\p{L}\p{Nd}]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

private fun String.compactAnnotationText(): String =
    replace(" ", "")

private fun List<com.phoebe.app.domain.LyricsLine>.toPlainText(): String =
    joinToString("\n") { it.text }

private fun List<com.phoebe.app.domain.LyricsLine>.toLrcText(): String =
    joinToString("\n") { line ->
        val startMs = line.startMs ?: 0L
        val minutes = startMs / 60_000L
        val seconds = (startMs % 60_000L) / 1_000L
        val centiseconds = (startMs % 1_000L) / 10L
        "[${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}.${centiseconds.toString().padStart(2, '0')}] ${line.text}"
    }

@Serializable
private data class LrclibLyricsResponse(
    val id: Long? = null,
    val name: String? = null,
    val instrumental: Boolean = false,
    @SerialName("plainLyrics") val plainLyrics: String? = null,
    @SerialName("syncedLyrics") val syncedLyrics: String? = null,
)

private const val GeniusAnnotationCacheTtlMs = 7L * 24L * 60L * 60L * 1_000L
private const val GeniusAnnotationMatchingVersion = 9
private const val GeniusAnnotationDefaultMaxSpanLines = 12
private const val GeniusAnnotationExtraSpanLineAllowance = 3
private const val GeniusAnnotationFuzzyTokenSlack = 8
