package com.phoebe.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import phoebe.composeapp.generated.resources.Res
import phoebe.composeapp.generated.resources.phoebe_bird
import phoebe.composeapp.generated.resources.phoebe_icon_rounded
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import com.phoebe.app.AppState
import com.phoebe.app.data.catalogAlbumsForArtist
import com.phoebe.app.data.catalogTracksForArtist
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.AppScreen
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.LocalFolderMediaSourceConfig
import com.phoebe.app.domain.MediaSourcesState
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.data.cachedArtworkPathForUrl
import com.phoebe.app.domain.isLocalMediaPlayback
import com.phoebe.app.domain.isRemoteLibraryTrack
import com.phoebe.app.domain.supportsPlexPlaylists
import com.phoebe.app.platform.createPlatformHttpClient
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.platform.prefersReducedArtworkEffects
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.phoebe.app.sources.rememberPickLocalFolder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.yield
import kotlin.math.max

internal const val ThumbnailArtworkMaxDecodeDimension = 160

@Composable
internal fun SectionLabel(label: String, color: Color) {
    Text(label.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.08.em)
}

@Composable
internal fun ArtworkImage(
    seed: String,
    thumbUrl: String?,
    modifier: Modifier = Modifier,
    radius: Dp = 10.dp,
    elevated: Boolean = true,
    maxDecodeDimension: Int = 512,
    fallbackThumbUrl: String? = null,
) {
    val image = rememberRemoteImage(thumbUrl, maxDecodeDimension, fallbackThumbUrl)
    val shape = RoundedCornerShape(radius)
    val imageModifier = when {
        !elevated || prefersReducedArtworkEffects() -> modifier.clip(shape)
        else -> modifier
            .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.38f))
            .clip(shape)
    }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = imageModifier,
        )
    } else {
        AlbumArtwork(seed, modifier, radius, elevated = elevated)
    }
}

@Composable
internal fun TrackArtworkImage(
    track: Track,
    modifier: Modifier = Modifier,
    radius: Dp = 10.dp,
    elevated: Boolean = true,
    maxDecodeDimension: Int = 512,
) {
    ArtworkImage(
        seed = track.album,
        thumbUrl = track.localArtworkUri,
        modifier = modifier,
        radius = radius,
        elevated = elevated,
        maxDecodeDimension = maxDecodeDimension,
        fallbackThumbUrl = track.thumbUrl,
    )
}

@Composable
internal fun rememberRemoteImage(url: String?, maxDecodeDimension: Int = 512, fallbackUrl: String? = null): ImageBitmap? {
    val primary = url?.takeIf { it.isNotBlank() }
    val fallbackSource = fallbackUrl?.takeIf { it.isNotBlank() }
    val target = primary ?: fallbackSource ?: return null
    val fallback = fallbackSource?.takeIf { it != target }
    var image by remember(target, fallback, maxDecodeDimension) {
        mutableStateOf(
            RemoteArtworkCache.cached(target, maxDecodeDimension)
                ?: fallback?.let { RemoteArtworkCache.cached(it, maxDecodeDimension) },
        )
    }
    // Stay subscribed to cache writes from any concurrent loader for this URL.
    val cached = RemoteArtworkCache.cached(target, maxDecodeDimension)
        ?: fallback?.let { RemoteArtworkCache.cached(it, maxDecodeDimension) }
    if (cached != null) {
        image = cached
    }
    LaunchedEffect(target, fallback, maxDecodeDimension) {
        while (isActive && image == null) {
            image = RemoteArtworkCache.awaitLoad(target, maxDecodeDimension)
                ?: fallback?.let { RemoteArtworkCache.awaitLoad(it, maxDecodeDimension) }
                ?: RemoteArtworkCache.cached(target, maxDecodeDimension)
                ?: fallback?.let { RemoteArtworkCache.cached(it, maxDecodeDimension) }
            if (image == null) delay(10_000L)
        }
    }
    return image
}

internal data class ArtworkCacheStats(
    val imageCount: Int,
    val estimatedBytes: Long,
    val inFlightCount: Int,
)

internal object RemoteArtworkCache {
    private const val DefaultMaxEntries = 300
    private const val DefaultMaxEstimatedBytes = 96L * 1024L * 1024L
    private const val FailedLoadRetryMs = 10L * 60L * 1000L

    private data class CacheKey(
        val url: String,
        val maxDecodeDimension: Int,
    )

    private val images = mutableStateMapOf<CacheKey, ImageBitmap>()

    internal val httpClient: HttpClient by lazy { createPlatformHttpClient() }
    private val storage: PlatformStorage by lazy { PlatformStorage() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Semaphore(permits = 6)
    private val lock = SynchronizedObject()
    private val inFlight = mutableMapOf<CacheKey, Deferred<ImageBitmap?>>()
    private val estimatedBytesByKey = mutableMapOf<CacheKey, Long>()
    private val accessOrder = LinkedHashMap<CacheKey, Unit>()
    private val recentFailures = mutableMapOf<CacheKey, Long>()
    private var maxEntries = DefaultMaxEntries
    private var maxEstimatedBytes = DefaultMaxEstimatedBytes
    private var estimatedBytes = 0L

    fun cached(url: String, maxDecodeDimension: Int = 512): ImageBitmap? {
        val key = CacheKey(url, maxDecodeDimension.normalizedDecodeDimension())
        return synchronized(lock) { cachedLocked(key) }
    }

    suspend fun awaitLoad(url: String, maxDecodeDimension: Int = 512): ImageBitmap? {
        val key = CacheKey(url, maxDecodeDimension.normalizedDecodeDimension())
        cached(key)?.let { return it }
        if (!shouldRetryFailedLoad(key)) return null

        var existing: ImageBitmap? = null
        val job = synchronized(lock) {
            existing = cachedLocked(key)
            if (existing != null || !shouldRetryFailedLoadLocked(key)) {
                null
            } else {
                inFlight[key] ?: scope.async {
                    try {
                        fetchAndDecode(key)
                    } finally {
                        synchronized(lock) { inFlight.remove(key) }
                    }
                }.also { inFlight[key] = it }
            }
        }
        existing?.let { return it }
        return job?.await()
    }

    private fun shouldRetryFailedLoad(key: CacheKey): Boolean =
        synchronized(lock) { shouldRetryFailedLoadLocked(key) }

    private fun cachedLocked(key: CacheKey): ImageBitmap? {
        val image = images[key] ?: return null
        touchLocked(key)
        return image
    }

    private suspend fun fetchAndDecode(key: CacheKey): ImageBitmap? {
        cached(key)?.let { return it }
        return gate.withPermit {
            cached(key)?.let { return@withPermit it }
            val url = key.url
            val remote = url.startsWith("http://") || url.startsWith("https://")
            val decoded = runCatching {
                val bytes: ByteArray = if (remote) {
                    runCatching { httpClient.get(url).body<ByteArray>() }
                        .getOrElse {
                            storage.readBytes(cachedArtworkPathForUrl(url)) ?: return@runCatching null
                        }
                } else {
                    storage.readUriBytes(url) ?: return@runCatching null
                }
                yield()
                decodeImageBitmap(bytes, key.maxDecodeDimension)
            }.getOrNull()
            if (decoded != null) {
                put(key, decoded)
                decoded
            } else {
                synchronized(lock) { recentFailures[key] = currentTimeMs() }
                null
            }
        }
    }

    private fun cached(key: CacheKey): ImageBitmap? {
        return synchronized(lock) { cachedLocked(key) }
    }

    private fun put(key: CacheKey, image: ImageBitmap) {
        synchronized(lock) {
            val newBytes = image.estimatedBytes()
            estimatedBytes -= estimatedBytesByKey[key] ?: 0L
            images[key] = image
            estimatedBytesByKey[key] = newBytes
            estimatedBytes += newBytes
            recentFailures.remove(key)
            touchLocked(key)
            trimToLimitsLocked()
        }
    }

    private fun touchLocked(key: CacheKey) {
        accessOrder.remove(key)
        accessOrder[key] = Unit
    }

    private fun trimToLimitsLocked() {
        while (images.size > maxEntries || estimatedBytes > maxEstimatedBytes) {
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

    fun stats(): ArtworkCacheStats =
        synchronized(lock) {
            ArtworkCacheStats(
                imageCount = images.size,
                estimatedBytes = estimatedBytes,
                inFlightCount = inFlight.size,
            )
        }

    internal fun putForTest(url: String, maxDecodeDimension: Int, image: ImageBitmap) {
        put(CacheKey(url, maxDecodeDimension.normalizedDecodeDimension()), image)
    }

    internal fun clearForTest() {
        synchronized(lock) {
            images.clear()
            inFlight.clear()
            estimatedBytesByKey.clear()
            accessOrder.clear()
            recentFailures.clear()
            estimatedBytes = 0L
            maxEntries = DefaultMaxEntries
            maxEstimatedBytes = DefaultMaxEstimatedBytes
        }
    }

    internal fun configureLimitsForTest(maxEntries: Int = DefaultMaxEntries, maxEstimatedBytes: Long = DefaultMaxEstimatedBytes) {
        synchronized(lock) {
            this.maxEntries = max(1, maxEntries)
            this.maxEstimatedBytes = max(1L, maxEstimatedBytes)
            trimToLimitsLocked()
        }
    }

    private fun Int.normalizedDecodeDimension(): Int =
        takeIf { it > 0 } ?: Int.MAX_VALUE

    private fun ImageBitmap.estimatedBytes(): Long =
        width.toLong() * height.toLong() * 4L
}

@Composable
internal fun AlbumArtwork(
    seed: String,
    modifier: Modifier = Modifier,
    radius: Dp = 10.dp,
    elevated: Boolean = true,
) {
    val shape = RoundedCornerShape(radius)
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


internal fun ArtworkBrush(seed: String): Brush {
    val hash = seed.fold(0) { acc, char -> acc * 31 + char.code }
    val palettes = listOf(
        listOf(Color(0xFF123969), Color(0xFFB97596), Color(0xFF061323)),
        listOf(Color(0xFF1B234F), Color(0xFFED704C), Color(0xFF111827)),
        listOf(Color(0xFF14395B), Color(0xFF5C8F55), Color(0xFF10151F)),
        listOf(Color(0xFF11243A), Color(0xFF9B4DFF), Color(0xFF0A0D14)),
    )
    return Brush.linearGradient(palettes[kotlin.math.abs(hash) % palettes.size])
}

internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
