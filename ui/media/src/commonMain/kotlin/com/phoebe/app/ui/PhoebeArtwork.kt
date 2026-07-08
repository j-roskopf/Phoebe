package com.phoebe.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.network.ConcurrentRequestStrategy
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import com.phoebe.app.data.applyEmbyFamilyArtworkAuth
import com.phoebe.app.data.cachedArtworkPathForUrl
import com.phoebe.app.data.embyFamilyArtworkAuthHeaders
import com.phoebe.app.data.isEmbyFamilyArtworkUrl
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.platform.createPlatformHttpClient
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.platform.prefersReducedArtworkEffects
import com.phoebe.app.platform.remoteArtworkCacheMaxEstimatedBytes
import com.phoebe.app.platform.remoteArtworkLoadParallelism
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private const val RemoteArtworkRetryDelayMs = 15_000L
private const val RemoteArtworkPreviewLoadGraceMs = 700L

@Composable
fun ArtworkImage(
    seed: String,
    thumbUrl: String?,
    modifier: Modifier = Modifier,
    radius: Dp = 10.dp,
    shape: Shape = RoundedCornerShape(radius),
    elevated: Boolean = true,
    maxDecodeDimension: Int = ListArtworkMaxDecodeDimension,
    fallbackThumbUrl: String? = null,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Crop,
) {
    CoilArtworkImage(
        seed = seed,
        thumbUrl = thumbUrl,
        fallbackThumbUrl = fallbackThumbUrl,
        modifier = modifier,
        radius = radius,
        shape = shape,
        elevated = elevated,
        maxDecodeDimension = maxDecodeDimension,
        alignment = alignment,
        contentScale = contentScale,
    )
}

@Composable
private fun CoilArtworkImage(
    seed: String,
    thumbUrl: String?,
    fallbackThumbUrl: String?,
    modifier: Modifier,
    radius: Dp,
    shape: Shape,
    elevated: Boolean,
    maxDecodeDimension: Int,
    alignment: Alignment,
    contentScale: ContentScale,
) {
    val candidates = remember(thumbUrl, fallbackThumbUrl, maxDecodeDimension) {
        artworkImageCandidates(thumbUrl, maxDecodeDimension, fallbackThumbUrl)
    }
    var candidateIndex by remember(candidates) { mutableIntStateOf(0) }
    val candidate = candidates.getOrNull(candidateIndex)
    val platformContext = LocalPlatformContext.current
    val request = remember(platformContext, candidate) {
        candidate?.let {
            ImageRequest.Builder(platformContext)
                .data(it.fetchUrl)
                .applyArtworkHeaders(it.fetchUrl)
                .build()
        }
    }
    val imageLoader = remember(platformContext) { phoebeArtworkImageLoader(platformContext) }
    val painter = rememberAsyncImagePainter(model = request, imageLoader = imageLoader)
    val painterState by painter.state.collectAsState()

    LaunchedEffect(painterState) {
        val state = painterState
        if (
            state is AsyncImagePainter.State.Error &&
            state.result.throwable !is CancellationException &&
            candidateIndex < candidates.lastIndex
        ) {
            candidateIndex += 1
        }
    }

    val visualState = when {
        candidate == null -> RemoteArtworkVisualState.Missing
        painterState is AsyncImagePainter.State.Success -> RemoteArtworkVisualState.Image
        painterState is AsyncImagePainter.State.Error && candidateIndex >= candidates.lastIndex ->
            RemoteArtworkVisualState.Missing
        else -> RemoteArtworkVisualState.Loading
    }

    Box(modifier) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = contentScale,
            alignment = alignment,
            modifier = artworkSurfaceModifier(Modifier.matchParentSize(), shape, elevated),
        )
        Crossfade(
            targetState = visualState,
            modifier = Modifier.matchParentSize(),
            label = "artwork-load-state",
        ) { state ->
            when (state) {
                RemoteArtworkVisualState.Image -> Unit
                RemoteArtworkVisualState.Loading -> {
                    ArtworkLoadingSlot(Modifier.fillMaxSize(), radius, shape = shape, elevated = elevated)
                }
                RemoteArtworkVisualState.Missing -> {
                    AlbumArtwork(seed, Modifier.fillMaxSize(), radius, shape = shape, elevated = elevated)
                }
            }
        }
    }
}

@OptIn(ExperimentalCoilApi::class)
private fun phoebeArtworkImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(KtorNetworkFetcherFactory(createPlatformHttpClient(), ConcurrentRequestStrategy.UNCOORDINATED))
        }
        .build()

private data class ArtworkImageCandidate(
    val sourceUrl: String,
    val fetchUrl: String,
)

private fun artworkImageCandidates(
    url: String?,
    maxDecodeDimension: Int,
    fallbackUrl: String? = null,
): List<ArtworkImageCandidate> {
    val primary = url?.takeIf { it.isNotBlank() }
    val fallback = fallbackUrl?.takeIf { it.isNotBlank() && it != primary }
    return listOfNotNull(primary, fallback)
        .flatMap { sourceUrl ->
            val fetchUrls = if (sourceUrl.isRemoteArtworkUrl()) {
                remoteArtworkRequestUrls(sourceUrl, maxDecodeDimension)
            } else {
                listOf(sourceUrl)
            }
            fetchUrls.map { fetchUrl -> ArtworkImageCandidate(sourceUrl, fetchUrl) }
        }
        .distinctBy { it.fetchUrl }
}

private fun ImageRequest.Builder.applyArtworkHeaders(fetchUrl: String): ImageRequest.Builder {
    val headers = embyFamilyArtworkAuthHeaders(fetchUrl)
    if (headers.isEmpty()) return this
    val networkHeaders = NetworkHeaders.Builder().apply {
        headers.forEach { (name, value) -> set(name, value) }
    }.build()
    return httpHeaders(networkHeaders)
}

private fun artworkSurfaceModifier(modifier: Modifier, shape: Shape, elevated: Boolean): Modifier =
    when {
        !elevated || prefersReducedArtworkEffects() -> modifier.clip(shape)
        else -> modifier
            .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.38f))
            .clip(shape)
    }

@Composable
fun TrackArtworkImage(
    track: Track,
    modifier: Modifier = Modifier,
    radius: Dp = 10.dp,
    shape: Shape = RoundedCornerShape(radius),
    elevated: Boolean = true,
    maxDecodeDimension: Int = ListArtworkMaxDecodeDimension,
    alignment: Alignment = Alignment.Center,
) {
    ArtworkImage(
        seed = track.album,
        thumbUrl = track.localArtworkUri,
        modifier = modifier,
        radius = radius,
        shape = shape,
        elevated = elevated,
        maxDecodeDimension = maxDecodeDimension,
        fallbackThumbUrl = track.thumbUrl,
        alignment = alignment,
    )
}

@Composable
fun rememberRemoteImage(
    url: String?,
    maxDecodeDimension: Int = ListArtworkMaxDecodeDimension,
    fallbackUrl: String? = null,
): ImageBitmap? = rememberRemoteImageState(url, maxDecodeDimension, fallbackUrl).image

internal sealed interface RemoteImageLoadState {
    val image: ImageBitmap?

    data object Loading : RemoteImageLoadState {
        override val image: ImageBitmap? = null
    }

    data object Unavailable : RemoteImageLoadState {
        override val image: ImageBitmap? = null
    }

    data class Preview(override val image: ImageBitmap) : RemoteImageLoadState
    data class Ready(override val image: ImageBitmap) : RemoteImageLoadState
}

private enum class RemoteArtworkVisualState {
    Image,
    Loading,
    Missing,
}

@Composable
private fun rememberRemoteImageState(
    url: String?,
    maxDecodeDimension: Int = ListArtworkMaxDecodeDimension,
    fallbackUrl: String? = null,
): RemoteImageLoadState {
    val primary = url?.takeIf { it.isNotBlank() }
    val fallbackSource = fallbackUrl?.takeIf { it.isNotBlank() }
    val target = primary ?: fallbackSource ?: return RemoteImageLoadState.Unavailable
    val fallback = fallbackSource?.takeIf { it != target }
    val retryEpoch = RemoteArtworkCache.retryEpoch
    return produceState(
        initialValue = cachedStateForDisplay(target, maxDecodeDimension, fallback),
        target,
        fallback,
        maxDecodeDimension,
        retryEpoch,
    ) {
        value = cachedStateForDisplay(target, maxDecodeDimension, fallback)
        while (true) {
            RemoteArtworkCache.cachedRequested(target, maxDecodeDimension, fallback)?.let {
                value = RemoteImageLoadState.Ready(it)
                return@produceState
            }

            var loadedTarget = false
            val requestedDimension = maxDecodeDimension.normalizedArtworkDecodeDimension()
            for (dim in remoteArtworkFetchDecodeDimensions(maxDecodeDimension)) {
                val image = if (dim == requestedDimension) {
                    RemoteArtworkCache.awaitLoadWithFallback(target, fallback, dim)
                } else {
                    withTimeoutOrNull(RemoteArtworkPreviewLoadGraceMs) {
                        RemoteArtworkCache.awaitLoadWithFallback(target, fallback, dim)
                    }
                }
                image?.let {
                    if (dim == requestedDimension) {
                        value = RemoteImageLoadState.Ready(image)
                        loadedTarget = true
                    } else {
                        value = RemoteImageLoadState.Preview(image)
                    }
                }
                if (loadedTarget) break
                RemoteArtworkCache.cachedRequested(target, maxDecodeDimension, fallback)?.let {
                    value = RemoteImageLoadState.Ready(it)
                    loadedTarget = true
                    break
                }
            }

            if (loadedTarget) {
                return@produceState
            }

            val current = cachedStateForDisplay(target, maxDecodeDimension, fallback)
            value = if (current is RemoteImageLoadState.Preview) current else RemoteImageLoadState.Unavailable
            delay(RemoteArtworkRetryDelayMs)
        }
    }.value
}

internal fun cachedStateForDisplay(url: String, maxDecodeDimension: Int, fallbackUrl: String? = null): RemoteImageLoadState {
    RemoteArtworkCache.cachedRequested(url, maxDecodeDimension, fallbackUrl)?.let {
        return RemoteImageLoadState.Ready(it)
    }
    progressivePreviewDecodeDimensions(maxDecodeDimension).asReversed().forEach { previewDimension ->
        RemoteArtworkCache.cachedRequested(url, previewDimension, fallbackUrl)?.let {
            return RemoteImageLoadState.Preview(it)
        }
    }
    return RemoteImageLoadState.Loading
}

internal fun progressivePreviewDecodeDimensions(maxDecodeDimension: Int): List<Int> {
    val requested = maxDecodeDimension.takeIf { it > 0 } ?: Int.MAX_VALUE
    if (requested <= ThumbnailArtworkMaxDecodeDimension) return emptyList()
    return listOf(ThumbnailArtworkMaxDecodeDimension, ListArtworkMaxDecodeDimension)
        .filter { it < requested }
        .distinct()
}

internal fun remoteArtworkFetchDecodeDimensions(maxDecodeDimension: Int): List<Int> {
    val requested = maxDecodeDimension.normalizedArtworkDecodeDimension()
    val preview = when {
        requested <= ListArtworkMaxDecodeDimension -> null
        requested <= GridArtworkMaxDecodeDimension -> ThumbnailArtworkMaxDecodeDimension
        else -> ListArtworkMaxDecodeDimension
    }
    return (listOfNotNull(preview?.takeIf { it < requested }) + requested).distinct()
}

data class ArtworkCacheStats(
    val imageCount: Int,
    val estimatedBytes: Long,
    val inFlightCount: Int,
)

object RemoteArtworkCache {
    private const val DefaultMaxEntries = 300
    private const val FailedLoadRetryMs = 60L * 1000L
    private const val RemoteArtworkLoadTimeoutMs = 12_000L
    private const val RemoteArtworkAlternateFetchGraceMs = 700L
    private const val FallbackArtworkGraceMs = 350L
    private const val DefaultLoadPermits = 8
    private const val DownloadModeMaxEntries = 32
    private const val DownloadModeMaxEstimatedBytes = 4L * 1024L * 1024L

    private data class CacheKey(
        val url: String,
        val maxDecodeDimension: Int,
    )

    private class InFlightLoad(
        var waiters: Int,
    ) {
        lateinit var deferred: Deferred<ImageBitmap?>
    }

    private val images = mutableMapOf<CacheKey, ImageBitmap>()
    private val cacheLock = ArtworkCacheLock()

    internal val httpClient: HttpClient by lazy { createPlatformHttpClient() }
    private val storage: PlatformStorage by lazy { PlatformStorage() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val platformLoadPermits = remoteArtworkLoadParallelism().coerceIn(1, DefaultLoadPermits)
    private var gate = Semaphore(permits = platformLoadPermits)
    private val inFlight = mutableMapOf<CacheKey, InFlightLoad>()
    private val estimatedBytesByKey = mutableMapOf<CacheKey, Long>()
    private val accessOrder = LinkedHashMap<CacheKey, Unit>()
    private val recentFailures = mutableMapOf<CacheKey, Long>()
    private val platformMaxEstimatedBytes = remoteArtworkCacheMaxEstimatedBytes().coerceAtLeast(4L * 1024L * 1024L)
    private var maxEntries = DefaultMaxEntries
    private var maxEstimatedBytes = platformMaxEstimatedBytes
    private var estimatedBytes = 0L
    internal var loadArtworkForTest: (suspend (url: String, maxDecodeDimension: Int) -> ImageBitmap?)? = null
    internal var fetchRemoteArtworkBytesForTest: (suspend (sourceUrl: String, fetchUrl: String) -> ByteArray?)? = null
    internal var decodeImageBitmapForTest: ((bytes: ByteArray, maxDecodeDimension: Int) -> ImageBitmap?)? = null
    internal var retryEpoch by mutableLongStateOf(0L)
        private set

    fun cached(url: String, maxDecodeDimension: Int = ListArtworkMaxDecodeDimension): ImageBitmap? =
        withCacheLock {
            val key = CacheKey(url, maxDecodeDimension.normalizedDecodeDimension())
            cachedLocked(key)
        }

    fun cachedRequested(url: String, maxDecodeDimension: Int, fallbackUrl: String? = null): ImageBitmap? =
        withCacheLock { cachedRequestedLocked(url, maxDecodeDimension, fallbackUrl) }

    fun cachedForDisplay(url: String, maxDecodeDimension: Int, fallbackUrl: String? = null): ImageBitmap? =
        withCacheLock {
            cachedRequestedLocked(url, maxDecodeDimension, fallbackUrl)
                ?: progressivePreviewDecodeDimensions(maxDecodeDimension).firstNotNullOfOrNull { previewDimension ->
                    cachedRequestedLocked(url, previewDimension, fallbackUrl)
                }
        }

    fun configureDownloadMemoryMode(enabled: Boolean) {
        withCacheLock {
            if (enabled) {
                maxEntries = DownloadModeMaxEntries
                maxEstimatedBytes = minOf(platformMaxEstimatedBytes, DownloadModeMaxEstimatedBytes)
            } else {
                maxEntries = DefaultMaxEntries
                maxEstimatedBytes = platformMaxEstimatedBytes
            }
            trimToLimitsLocked()
        }
    }

    /** Drop least-recent artwork when the process is under memory pressure. */
    fun trimForMemoryPressure(aggressive: Boolean) {
        withCacheLock {
            val targetEntries = if (aggressive) {
                max(8, maxEntries / 4)
            } else {
                max(16, maxEntries / 2)
            }
            val targetBytes = if (aggressive) {
                max(1L * 1024L * 1024L, maxEstimatedBytes / 4)
            } else {
                max(2L * 1024L * 1024L, maxEstimatedBytes / 2)
            }
            trimToTargetsLocked(
                targetEntries = targetEntries,
                targetBytes = minOf(platformMaxEstimatedBytes, targetBytes),
            )
        }
    }

    /** Evict all decoded artwork when the runtime reports critical heap pressure. */
    fun clearUnderMemoryPressure() {
        withCacheLock {
            images.clear()
            estimatedBytesByKey.clear()
            accessOrder.clear()
            recentFailures.clear()
            estimatedBytes = 0L
            inFlight.values.forEach { it.deferred.cancel() }
            inFlight.clear()
        }
    }

    fun retryFailedLoadsNow() {
        withCacheLock {
            recentFailures.clear()
        }
        retryEpoch += 1
    }

    suspend fun awaitLoad(url: String, maxDecodeDimension: Int = ListArtworkMaxDecodeDimension): ImageBitmap? {
        val key = CacheKey(url, maxDecodeDimension.normalizedDecodeDimension())
        withCacheLock { cachedLocked(key) }?.let { return it }
        if (!withCacheLock { shouldRetryFailedLoadLocked(key) }) return null

        val load = withCacheLock {
            cachedLocked(key)?.let { return it }
            if (!shouldRetryFailedLoadLocked(key)) return null
            inFlight[key]?.also { it.waiters += 1 }
                ?: InFlightLoad(waiters = 1).also { load ->
                    load.deferred = scope.async(start = CoroutineStart.LAZY) {
                        try {
                            fetchAndDecode(key)
                        } finally {
                            withCacheLock {
                                if (inFlight[key] === load) {
                                    inFlight.remove(key)
                                }
                            }
                        }
                    }
                    inFlight[key] = load
                    load.deferred.start()
                }
        }
        return try {
            load.deferred.await()
        } finally {
            releaseWaiter(key, load)
        }
    }

    suspend fun awaitLoadWithFallback(
        url: String,
        fallbackUrl: String?,
        maxDecodeDimension: Int = ListArtworkMaxDecodeDimension,
    ): ImageBitmap? {
        cachedRequested(url, maxDecodeDimension, fallbackUrl)?.let { return it }
        if (fallbackUrl == null || fallbackUrl == url) {
            return awaitLoad(url, maxDecodeDimension)
        }

        withTimeoutOrNull(FallbackArtworkGraceMs) {
            awaitLoad(url, maxDecodeDimension)
        }?.let { return it }

        awaitLoad(fallbackUrl, maxDecodeDimension)?.let { return it }
        cachedRequested(url, maxDecodeDimension, fallbackUrl)?.let { return it }
        return awaitLoad(url, maxDecodeDimension)
    }

    internal fun hasRecentFailure(url: String, maxDecodeDimension: Int = ListArtworkMaxDecodeDimension): Boolean {
        val key = CacheKey(url, maxDecodeDimension.normalizedDecodeDimension())
        return withCacheLock { !shouldRetryFailedLoadLocked(key) }
    }

    private suspend fun fetchAndDecode(key: CacheKey): ImageBitmap? {
        withCacheLock { cachedLocked(key) }?.let { return it }
        return gate.withPermit {
            withCacheLock { cachedLocked(key) }?.let { return@withPermit it }
            val url = key.url
            val remote = url.startsWith("http://") || url.startsWith("https://")
            val testLoader = loadArtworkForTest
            val decoded = if (testLoader != null) {
                testLoader(url, key.maxDecodeDimension)
            } else {
                try {
                    if (remote) {
                        loadRemoteArtwork(url, key.maxDecodeDimension)
                    } else {
                        storage.readUriBytes(url)?.let { bytes ->
                            yield()
                            decodeArtworkBytes(bytes, key.maxDecodeDimension)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    null
                }
            }
            if (decoded != null) {
                withCacheLock { putLocked(key, decoded) }
                decoded
            } else {
                withCacheLock { recentFailures[key] = currentTimeMs() }
                null
            }
        }
    }

    private suspend fun loadRemoteArtwork(url: String, maxDecodeDimension: Int): ImageBitmap? {
        val fetchUrls = remoteArtworkRequestUrls(url, maxDecodeDimension)
        decodeDiskCachedArtwork(fetchUrls, maxDecodeDimension)?.let { return it }
        decodeLegacyCachedArtwork(url, maxDecodeDimension)?.let { return it }
        return withTimeoutOrNull(RemoteArtworkLoadTimeoutMs) {
            fetchAndDecodeRemoteArtwork(url, fetchUrls, maxDecodeDimension)
        } ?: decodeLegacyCachedArtwork(url, maxDecodeDimension)
    }

    private suspend fun fetchAndDecodeRemoteArtwork(
        url: String,
        fetchUrls: List<String>,
        maxDecodeDimension: Int,
    ): ImageBitmap? {
        if (fetchUrls.size == 1) {
            return fetchDecodeAndPersistRemoteArtwork(url, fetchUrls.first(), maxDecodeDimension)
        }
        val primary = fetchUrls.first()
        fetchRemoteArtworkBytesWithGrace(url, primary)?.let { bytes ->
            return decodeAndPersistRemoteArtwork(primary, bytes, maxDecodeDimension)
        }
        fetchUrls.drop(1).firstNotNullOfOrNull { fetchUrl ->
            fetchDecodeAndPersistRemoteArtwork(url, fetchUrl, maxDecodeDimension)
        }?.let { return it }
        return fetchDecodeAndPersistRemoteArtwork(url, primary, maxDecodeDimension)
    }

    private suspend fun fetchRemoteArtworkBytesWithGrace(sourceUrl: String, fetchUrl: String): ByteArray? =
        withTimeoutOrNull(RemoteArtworkAlternateFetchGraceMs) {
            fetchRemoteArtworkBytes(sourceUrl, fetchUrl)
        }

    private suspend fun fetchDecodeAndPersistRemoteArtwork(
        sourceUrl: String,
        fetchUrl: String,
        maxDecodeDimension: Int,
    ): ImageBitmap? =
        fetchRemoteArtworkBytes(sourceUrl, fetchUrl)?.let { bytes ->
            decodeAndPersistRemoteArtwork(fetchUrl, bytes, maxDecodeDimension)
        }

    private suspend fun decodeAndPersistRemoteArtwork(
        fetchUrl: String,
        bytes: ByteArray,
        maxDecodeDimension: Int,
    ): ImageBitmap? {
        yield()
        return decodeArtworkBytes(bytes, maxDecodeDimension)?.also {
            runCatching { ArtworkDiskCache.write(fetchUrl, bytes) }
        }
    }

    private suspend fun decodeDiskCachedArtwork(fetchUrls: List<String>, maxDecodeDimension: Int): ImageBitmap? =
        fetchUrls.firstNotNullOfOrNull { fetchUrl ->
            try {
                ArtworkDiskCache.read(fetchUrl)?.let { bytes ->
                    yield()
                    decodeArtworkBytes(bytes, maxDecodeDimension)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
        }

    private suspend fun decodeLegacyCachedArtwork(url: String, maxDecodeDimension: Int): ImageBitmap? =
        try {
            storage.readBytes(cachedArtworkPathForUrl(url))?.let { bytes ->
                yield()
                decodeArtworkBytes(bytes, maxDecodeDimension)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }

    private suspend fun fetchRemoteArtworkBytes(sourceUrl: String, fetchUrl: String): ByteArray? =
        fetchRemoteArtworkBytesForTest?.invoke(sourceUrl, fetchUrl)
            ?: fetchRemoteArtworkBytesFromNetwork(sourceUrl, fetchUrl)

    private suspend fun fetchRemoteArtworkBytesFromNetwork(sourceUrl: String, fetchUrl: String): ByteArray? =
        try {
            val response = httpClient.get(fetchUrl) {
                applyEmbyFamilyArtworkAuth(sourceUrl)
            }
            if (!response.status.isSuccess()) return null
            val bytes = response.body<ByteArray>()
            bytes.takeIf { it.isNotEmpty() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }

    private fun decodeArtworkBytes(bytes: ByteArray, maxDecodeDimension: Int): ImageBitmap? =
        decodeImageBitmapForTest?.invoke(bytes, maxDecodeDimension)
            ?: decodeImageBitmap(bytes, maxDecodeDimension)

    private fun cachedLocked(key: CacheKey): ImageBitmap? {
        val image = images[key] ?: return null
        touchLocked(key)
        return image
    }

    private fun putLocked(key: CacheKey, image: ImageBitmap) {
        val newBytes = image.estimatedBytes()
        estimatedBytes -= estimatedBytesByKey[key] ?: 0L
        images[key] = image
        estimatedBytesByKey[key] = newBytes
        estimatedBytes += newBytes
        clearFailuresForUrlLocked(key.url)
        touchLocked(key)
        trimToLimitsLocked()
    }

    private fun cachedRequestedLocked(url: String, maxDecodeDimension: Int, fallbackUrl: String? = null): ImageBitmap? =
        cachedLocked(CacheKey(url, maxDecodeDimension.normalizedDecodeDimension()))
            ?: fallbackUrl?.let { cachedLocked(CacheKey(it, maxDecodeDimension.normalizedDecodeDimension())) }
            ?: cachedListArtworkFromAnyDimensionLocked(url, maxDecodeDimension)
            ?: fallbackUrl?.let { cachedListArtworkFromAnyDimensionLocked(it, maxDecodeDimension) }

    private fun cachedListArtworkFromAnyDimensionLocked(url: String, maxDecodeDimension: Int): ImageBitmap? {
        val requested = maxDecodeDimension.normalizedDecodeDimension()
        if (requested > ListArtworkMaxDecodeDimension) return null
        val candidate = images.keys
            .asSequence()
            .filter { it.url == url }
            .sortedWith(
                compareBy<CacheKey> { if (it.maxDecodeDimension >= requested) 0 else 1 }
                    .thenBy { kotlin.math.abs(it.maxDecodeDimension - requested) },
            )
            .firstOrNull()
            ?: return null
        return cachedLocked(candidate)
    }

    private fun clearFailuresForUrlLocked(url: String) {
        recentFailures.keys.removeAll { it.url == url }
    }

    private fun touchLocked(key: CacheKey) {
        accessOrder.remove(key)
        accessOrder[key] = Unit
    }

    private fun trimToLimitsLocked() {
        trimToTargetsLocked(maxEntries, maxEstimatedBytes)
    }

    private fun trimToTargetsLocked(targetEntries: Int, targetBytes: Long) {
        while (images.size > targetEntries || estimatedBytes > targetBytes) {
            val eldest = accessOrder.keys.firstOrNull() ?: return
            accessOrder.remove(eldest)
            images.remove(eldest)
            estimatedBytes -= estimatedBytesByKey.remove(eldest) ?: 0L
            recentFailures.remove(eldest)
        }
    }

    private fun shouldRetryFailedLoadLocked(key: CacheKey): Boolean {
        val failedAt = recentFailures[key] ?: return true
        val retry = currentTimeMs() - failedAt >= FailedLoadRetryMs
        if (retry) recentFailures.remove(key)
        return retry
    }

    private fun releaseWaiter(key: CacheKey, load: InFlightLoad) {
        withCacheLock {
            val current = inFlight[key] ?: return
            if (current !== load) return
            current.waiters -= 1
            if (current.waiters <= 0 && !current.deferred.isCompleted) {
                inFlight.remove(key)
                current.deferred.cancel()
            }
        }
    }

    private inline fun <T> withCacheLock(block: () -> T): T = cacheLock.withCacheLock(block)

    fun stats(): ArtworkCacheStats =
        withCacheLock {
            ArtworkCacheStats(
                imageCount = images.size,
                estimatedBytes = estimatedBytes,
                inFlightCount = inFlight.size,
            )
        }

    internal fun putForTest(url: String, maxDecodeDimension: Int, image: ImageBitmap) {
        withCacheLock {
            putLocked(CacheKey(url, maxDecodeDimension.normalizedDecodeDimension()), image)
        }
    }

    internal fun clearForTest() {
        withCacheLock {
            images.clear()
            estimatedBytesByKey.clear()
            accessOrder.clear()
            recentFailures.clear()
            estimatedBytes = 0L
            maxEntries = DefaultMaxEntries
            maxEstimatedBytes = platformMaxEstimatedBytes
            loadArtworkForTest = null
            fetchRemoteArtworkBytesForTest = null
            decodeImageBitmapForTest = null
            gate = Semaphore(permits = platformLoadPermits)
            inFlight.clear()
        }
        ArtworkDiskCache.resetBackendForTest()
        retryEpoch = 0L
    }

    internal fun markFailedForTest(url: String, maxDecodeDimension: Int = ListArtworkMaxDecodeDimension) {
        withCacheLock {
            recentFailures[CacheKey(url, maxDecodeDimension.normalizedDecodeDimension())] = currentTimeMs()
        }
    }

    internal fun configureLimitsForTest(maxEntries: Int = DefaultMaxEntries, maxEstimatedBytes: Long = platformMaxEstimatedBytes) {
        withCacheLock {
            this.maxEntries = max(1, maxEntries)
            this.maxEstimatedBytes = max(1L, maxEstimatedBytes)
            trimToLimitsLocked()
        }
    }

    private fun Int.normalizedDecodeDimension(): Int =
        normalizedArtworkDecodeDimension()

    private fun ImageBitmap.estimatedBytes(): Long =
        width.toLong() * height.toLong() * bytesPerPixel()

    private fun ImageBitmap.bytesPerPixel(): Long =
        when (config) {
            ImageBitmapConfig.Alpha8 -> 1L
            ImageBitmapConfig.Rgb565 -> 2L
            ImageBitmapConfig.F16 -> 8L
            else -> 4L
        }
}

private fun Int.normalizedArtworkDecodeDimension(): Int =
    takeIf { it > 0 } ?: Int.MAX_VALUE

private fun String.isRemoteArtworkUrl(): Boolean =
    startsWith("http://") || startsWith("https://")

/** Ask remote servers for a smaller JPEG when the URL supports sizing query params. */
internal fun remoteArtworkRequestUrls(url: String, maxDecodeDimension: Int): List<String> {
    val sized = url.withRequestImageSize(maxDecodeDimension)
    return if (sized == url) listOf(url) else listOf(sized, url)
}

private fun String.withRequestImageSize(maxDecodeDimension: Int): String {
    if (!startsWith("http://") && !startsWith("https://")) return this
    val pixels = maxDecodeDimension.coerceIn(64, HeroArtworkMaxDecodeDimension)
    return when {
        contains("maxWidth=", ignoreCase = true) || contains("maxHeight=", ignoreCase = true) -> this
        contains("width=", ignoreCase = true) || contains("height=", ignoreCase = true) -> this
        isEmbyFamilyArtworkUrl() -> appendQueryParameters(
            "maxWidth" to pixels.toString(),
            "maxHeight" to pixels.toString(),
        )
        isSubsonicCoverArtUrl() || isMusicAssistantImageProxyUrl() -> withQueryParameter("size", pixels.toString())
        isPlexArtworkUrl() -> appendQueryParameters("width" to pixels.toString(), "height" to pixels.toString())
        else -> this
    }
}

private fun String.isPlexArtworkUrl(): Boolean =
    hasQueryParameter("X-Plex-Token")

private fun String.isSubsonicCoverArtUrl(): Boolean =
    contains("/rest/getCoverArt", ignoreCase = true) || contains("getCoverArt.view", ignoreCase = true)

private fun String.isMusicAssistantImageProxyUrl(): Boolean =
    contains("/imageproxy", ignoreCase = true)

private fun String.hasQueryParameter(name: String): Boolean {
    val query = substringAfter('?', "").substringBefore('#')
    if (query.isBlank()) return false
    return query.split('&').any { parameter ->
        parameter.substringBefore('=').equals(name, ignoreCase = true)
    }
}

private fun String.withQueryParameter(name: String, value: String): String {
    val fragmentStart = indexOf('#')
    val beforeFragment = if (fragmentStart >= 0) substring(0, fragmentStart) else this
    val fragment = if (fragmentStart >= 0) substring(fragmentStart) else ""
    val queryStart = beforeFragment.indexOf('?')
    if (queryStart < 0) return appendQueryParameters(name to value)

    val base = beforeFragment.substring(0, queryStart)
    val query = beforeFragment.substring(queryStart + 1)
    var replaced = false
    val updatedQuery = query
        .split('&')
        .joinToString("&") { parameter ->
            val key = parameter.substringBefore('=')
            if (key.equals(name, ignoreCase = true)) {
                replaced = true
                "$key=$value"
            } else {
                parameter
            }
        }
    return if (replaced) {
        "$base?$updatedQuery$fragment"
    } else {
        appendQueryParameters(name to value)
    }
}

private fun String.appendQueryParameters(vararg parameters: Pair<String, String>): String {
    val fragmentStart = indexOf('#')
    val beforeFragment = if (fragmentStart >= 0) substring(0, fragmentStart) else this
    val fragment = if (fragmentStart >= 0) substring(fragmentStart) else ""
    val separator = when {
        beforeFragment.endsWith('?') || beforeFragment.endsWith('&') -> ""
        beforeFragment.contains('?') -> "&"
        else -> "?"
    }
    return buildString {
        append(beforeFragment)
        append(separator)
        append(parameters.joinToString("&") { (name, value) -> "$name=$value" })
        append(fragment)
    }
}

@Composable
fun AlbumArtwork(
    seed: String,
    modifier: Modifier = Modifier,
    radius: Dp = 10.dp,
    shape: Shape = RoundedCornerShape(radius),
    elevated: Boolean = true,
) {
    if (!elevated || prefersReducedArtworkEffects()) {
        Box(
            modifier
                .clip(shape)
                .background(ArtworkBrush(seed)),
        )
        return
    }
    Box(
        modifier
            .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.38f))
            .clip(shape)
            .background(ArtworkBrush(seed)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF17345E), Color(0xFF7F5C91), Color(0xFF162033))), alpha = 0.94f)
            drawCircle(Color.White.copy(alpha = 0.08f), radius = size.minDimension * 0.52f, center = Offset(size.width * 0.58f, size.height * 0.34f))
            drawRect(Color(0x33200630), topLeft = Offset(0f, size.height * 0.58f), size = Size(size.width, size.height * 0.42f))
            drawLine(
                color = Color.White.copy(alpha = 0.18f),
                start = Offset(0f, size.height * 0.61f),
                end = Offset(size.width, size.height * 0.61f),
                strokeWidth = 1.dp.toPx(),
            )
            repeat(28) { star ->
                val x = ((star * 47) % 100) / 100f * size.width
                val y = ((star * 29) % 48) / 100f * size.height
                drawCircle(Color.White.copy(alpha = 0.35f), radius = 0.8.dp.toPx(), center = Offset(x, y))
            }
            val figureX = size.width * 0.5f
            val groundY = size.height * 0.69f
            drawCircle(Color(0xFF050710), radius = size.width * 0.018f, center = Offset(figureX, groundY - size.height * 0.12f))
            drawRoundRect(
                color = Color(0xFF050710),
                topLeft = Offset(figureX - size.width * 0.018f, groundY - size.height * 0.105f),
                size = Size(size.width * 0.036f, size.height * 0.13f),
            )
            val reflection = Path().apply {
                moveTo(figureX, groundY + size.height * 0.02f)
                lineTo(figureX - size.width * 0.025f, size.height * 0.84f)
                lineTo(figureX + size.width * 0.012f, size.height * 0.84f)
                close()
            }
            drawPath(reflection, Color.Black.copy(alpha = 0.26f))
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.26f))),
                topLeft = Offset.Zero,
                size = size,
            )
        }
    }
}

@Composable
private fun ArtworkLoadingSlot(
    modifier: Modifier = Modifier,
    radius: Dp = 10.dp,
    shape: Shape = RoundedCornerShape(radius),
    elevated: Boolean = true,
) {
    val borderTrackColor = Color.White.copy(alpha = 0.05f)
    val borderProgressColor = PhoebeUi.accentLight.copy(alpha = 0.86f)
    val slotModifier = when {
        !elevated || prefersReducedArtworkEffects() -> modifier.clip(shape)
        else -> modifier
            .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.24f))
            .clip(shape)
    }
    Box(
        slotModifier
            .background(
                Brush.verticalGradient(
                    listOf(
                        PhoebeUi.panel.copy(alpha = 0.82f),
                        PhoebeUi.canvasBackground.copy(alpha = 0.72f),
                    ),
                ),
            )
            .border(BorderStroke(1.dp, PhoebeUi.border.copy(alpha = 0.42f)), shape),
    ) {
        PhoebeLoadingBorder(
            modifier = Modifier.fillMaxSize(),
            radius = radius,
            trackColor = borderTrackColor,
            progressColor = borderProgressColor,
            label = "artwork-loading-border",
        )
    }
}

@Composable
fun PhoebeLoadingBorder(
    modifier: Modifier = Modifier,
    radius: Dp = 10.dp,
    trackColor: Color = Color.White.copy(alpha = 0.05f),
    progressColor: Color = PhoebeUi.accentLight.copy(alpha = 0.86f),
    strokeWidth: Dp = 2.dp,
    label: String = "loading-border",
) {
    val animatedProgress by rememberInfiniteTransition(label = label)
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500, easing = LinearEasing),
            ),
            label = "$label-progress",
        )
    Canvas(modifier) {
        val strokePx = strokeWidth.toPx()
        val inset = strokePx / 2f
        val left = inset
        val top = inset
        val right = size.width - inset
        val bottom = size.height - inset
        if (right <= left || bottom <= top) return@Canvas

        val pathWidth = right - left
        val pathHeight = bottom - top
        val cornerRadius = radius.toPx().coerceIn(0f, minOf(pathWidth, pathHeight) / 2f)
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokePx, size.height - strokePx),
            cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            style = Stroke(width = strokePx),
        )

        val perimeter = roundedRectPerimeter(pathWidth, pathHeight, cornerRadius)
        if (perimeter <= 0f) return@Canvas

        val segmentLength = perimeter * 0.28f
        val start = animatedProgress * perimeter
        val segmentPath = Path()
        val sampleStep = 2.dp.toPx().coerceAtLeast(1f)
        var traveled = 0f
        val firstPoint = roundedRectPointAt(
            distance = start,
            perimeter = perimeter,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            cornerRadius = cornerRadius,
        )

        segmentPath.moveTo(firstPoint.x, firstPoint.y)
        while (traveled < segmentLength) {
            traveled = minOf(segmentLength, traveled + sampleStep)
            val nextPoint = roundedRectPointAt(
                distance = start + traveled,
                perimeter = perimeter,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                cornerRadius = cornerRadius,
            )
            segmentPath.lineTo(nextPoint.x, nextPoint.y)
        }
        drawPath(
            path = segmentPath,
            color = progressColor,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private fun roundedRectPerimeter(width: Float, height: Float, cornerRadius: Float): Float {
    val horizontal = (width - cornerRadius * 2f).coerceAtLeast(0f)
    val vertical = (height - cornerRadius * 2f).coerceAtLeast(0f)
    return (horizontal + vertical) * 2f + (PI.toFloat() * cornerRadius * 2f)
}

private fun roundedRectPointAt(
    distance: Float,
    perimeter: Float,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    cornerRadius: Float,
): Offset {
    val width = right - left
    val height = bottom - top
    val radius = cornerRadius.coerceIn(0f, minOf(width, height) / 2f)
    val horizontal = (width - radius * 2f).coerceAtLeast(0f)
    val vertical = (height - radius * 2f).coerceAtLeast(0f)
    val arcLength = PI.toFloat() * radius / 2f
    val d = ((distance % perimeter) + perimeter) % perimeter
    var cursor = horizontal

    if (d <= cursor) return Offset(left + radius + d, top)
    if (arcLength > 0f && d <= cursor + arcLength) {
        return roundedRectArcPoint(
            center = Offset(right - radius, top + radius),
            radius = radius,
            startRadians = -PI / 2.0,
            sweepRadians = PI / 2.0,
            distance = d - cursor,
            arcLength = arcLength,
        )
    }
    cursor += arcLength

    if (d <= cursor + vertical) return Offset(right, top + radius + d - cursor)
    cursor += vertical
    if (arcLength > 0f && d <= cursor + arcLength) {
        return roundedRectArcPoint(
            center = Offset(right - radius, bottom - radius),
            radius = radius,
            startRadians = 0.0,
            sweepRadians = PI / 2.0,
            distance = d - cursor,
            arcLength = arcLength,
        )
    }
    cursor += arcLength

    if (d <= cursor + horizontal) return Offset(right - radius - (d - cursor), bottom)
    cursor += horizontal
    if (arcLength > 0f && d <= cursor + arcLength) {
        return roundedRectArcPoint(
            center = Offset(left + radius, bottom - radius),
            radius = radius,
            startRadians = PI / 2.0,
            sweepRadians = PI / 2.0,
            distance = d - cursor,
            arcLength = arcLength,
        )
    }
    cursor += arcLength

    if (d <= cursor + vertical) return Offset(left, bottom - radius - (d - cursor))
    cursor += vertical
    if (arcLength > 0f) {
        return roundedRectArcPoint(
            center = Offset(left + radius, top + radius),
            radius = radius,
            startRadians = PI,
            sweepRadians = PI / 2.0,
            distance = d - cursor,
            arcLength = arcLength,
        )
    }
    return Offset(left, top)
}

private fun roundedRectArcPoint(
    center: Offset,
    radius: Float,
    startRadians: Double,
    sweepRadians: Double,
    distance: Float,
    arcLength: Float,
): Offset {
    val angle = startRadians + sweepRadians * (distance / arcLength).toDouble()
    return Offset(
        x = center.x + cos(angle).toFloat() * radius,
        y = center.y + sin(angle).toFloat() * radius,
    )
}


fun ArtworkBrush(seed: String): Brush {
    val hash = seed.fold(0) { acc, char -> acc * 31 + char.code }
    val palettes = listOf(
        listOf(Color(0xFF123969), Color(0xFFB97596), Color(0xFF061323)),
        listOf(Color(0xFF1B234F), Color(0xFFED704C), Color(0xFF111827)),
        listOf(Color(0xFF14395B), Color(0xFF5C8F55), Color(0xFF10151F)),
        listOf(Color(0xFF11243A), Color(0xFF9B4DFF), Color(0xFF0A0D14)),
    )
    return Brush.linearGradient(palettes[kotlin.math.abs(hash) % palettes.size])
}

fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
