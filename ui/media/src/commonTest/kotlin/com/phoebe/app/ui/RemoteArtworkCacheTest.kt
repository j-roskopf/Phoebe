package com.phoebe.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteArtworkCacheTest {
    @AfterTest
    fun tearDown() {
        RemoteArtworkCache.clearForTest()
    }

    @Test
    fun evictsLeastRecentlyUsedImageWhenEntryLimitIsReached() {
        RemoteArtworkCache.configureLimitsForTest(maxEntries = 2, maxEstimatedBytes = Long.MAX_VALUE)

        RemoteArtworkCache.putForTest("a", 128, testImageBitmap(10, 10))
        RemoteArtworkCache.putForTest("b", 128, testImageBitmap(10, 10))
        assertNotNull(RemoteArtworkCache.cached("a", 128))
        RemoteArtworkCache.putForTest("c", 128, testImageBitmap(10, 10))

        assertNotNull(RemoteArtworkCache.cached("a", 128))
        assertNull(RemoteArtworkCache.cached("b", 128))
        assertNotNull(RemoteArtworkCache.cached("c", 128))
        assertEquals(2, RemoteArtworkCache.stats().imageCount)
    }

    @Test
    fun evictsImagesWhenEstimatedByteLimitIsReached() {
        val large = testImageBitmap(20, 20)
        RemoteArtworkCache.configureLimitsForTest(
            maxEntries = 10,
            maxEstimatedBytes = large.width.toLong() * large.height.toLong() * 4L,
        )

        RemoteArtworkCache.putForTest("small", 128, testImageBitmap(10, 10))
        RemoteArtworkCache.putForTest("large", 128, large)

        assertNull(RemoteArtworkCache.cached("small", 128))
        assertNotNull(RemoteArtworkCache.cached("large", 128))
        assertEquals(1, RemoteArtworkCache.stats().imageCount)
    }

    @Test
    fun keepsSeparateEntriesForDifferentDecodeSizes() {
        RemoteArtworkCache.configureLimitsForTest(maxEntries = 10, maxEstimatedBytes = Long.MAX_VALUE)

        RemoteArtworkCache.putForTest("art", 64, testImageBitmap(64, 64))
        RemoteArtworkCache.putForTest("art", 256, testImageBitmap(256, 256))

        assertEquals(64, RemoteArtworkCache.cached("art", 64)?.width)
        assertEquals(256, RemoteArtworkCache.cached("art", 256)?.width)
        assertEquals(2, RemoteArtworkCache.stats().imageCount)
    }

    @Test
    fun listArtworkCanReuseLargerCachedImageForSameUrl() {
        RemoteArtworkCache.configureLimitsForTest(maxEntries = 10, maxEstimatedBytes = Long.MAX_VALUE)

        RemoteArtworkCache.putForTest("art", HeroArtworkMaxDecodeDimension, testImageBitmap(1024, 1024))

        assertEquals(
            1024,
            RemoteArtworkCache.cachedRequested("art", ThumbnailArtworkMaxDecodeDimension)?.width,
        )
    }

    @Test
    fun displayCacheFallsBackToLowerResolutionPreviewForHeroRequests() {
        RemoteArtworkCache.configureLimitsForTest(maxEntries = 10, maxEstimatedBytes = Long.MAX_VALUE)

        RemoteArtworkCache.putForTest("art", ThumbnailArtworkMaxDecodeDimension, testImageBitmap(160, 160))

        assertEquals(
            160,
            RemoteArtworkCache.cachedForDisplay("art", HeroArtworkMaxDecodeDimension)?.width,
        )

        RemoteArtworkCache.putForTest("art", HeroArtworkMaxDecodeDimension, testImageBitmap(1024, 1024))

        assertEquals(
            1024,
            RemoteArtworkCache.cachedForDisplay("art", HeroArtworkMaxDecodeDimension)?.width,
        )
    }

    @Test
    fun displayStateUsesPreviewWhileHeroArtworkIsPending() {
        RemoteArtworkCache.configureLimitsForTest(maxEntries = 10, maxEstimatedBytes = Long.MAX_VALUE)
        RemoteArtworkCache.putForTest("art", ThumbnailArtworkMaxDecodeDimension, testImageBitmap(160, 160))

        val state = cachedStateForDisplay("art", HeroArtworkMaxDecodeDimension)

        assertIs<RemoteImageLoadState.Preview>(state)
        assertEquals(160, state.image.width)
    }

    @Test
    fun remoteArtworkFetchDecodeDimensionsAvoidsExtraPreviewForListArtwork() {
        assertEquals(
            listOf(ListArtworkMaxDecodeDimension),
            remoteArtworkFetchDecodeDimensions(ListArtworkMaxDecodeDimension),
        )
    }

    @Test
    fun remoteArtworkFetchDecodeDimensionsUsesSinglePreviewForLargeArtwork() {
        assertEquals(
            listOf(ThumbnailArtworkMaxDecodeDimension, GridArtworkMaxDecodeDimension),
            remoteArtworkFetchDecodeDimensions(GridArtworkMaxDecodeDimension),
        )
        assertEquals(
            listOf(ListArtworkMaxDecodeDimension, HeroArtworkMaxDecodeDimension),
            remoteArtworkFetchDecodeDimensions(HeroArtworkMaxDecodeDimension),
        )
    }

    @Test
    fun diskHitReturnsImageWithoutNetwork() = runTest {
        val fetchUrl = sizedPlexArtworkUrl(ListArtworkMaxDecodeDimension)
        val disk = FakeArtworkDiskCacheBackend(
            mapOf(fetchUrl to byteArrayOf(DiskHitByte)),
        )
        ArtworkDiskCache.useBackendForTest(disk)
        var networkRequests = 0
        RemoteArtworkCache.fetchRemoteArtworkBytesForTest = { _, _ ->
            networkRequests++
            byteArrayOf(NetworkHitByte)
        }
        RemoteArtworkCache.decodeImageBitmapForTest = { bytes, maxDecodeDimension ->
            if (bytes.singleOrNull() == DiskHitByte) testImageBitmap(maxDecodeDimension, maxDecodeDimension) else null
        }

        val image = RemoteArtworkCache.awaitLoad(PlexArtworkUrl, ListArtworkMaxDecodeDimension)

        assertNotNull(image)
        assertEquals(ListArtworkMaxDecodeDimension, image.width)
        assertEquals(0, networkRequests)
        assertEquals(listOf(fetchUrl), disk.reads)
    }

    @Test
    fun successfulNetworkFetchWritesBytesBySizedFetchUrl() = runTest {
        val fetchUrl = sizedPlexArtworkUrl(ListArtworkMaxDecodeDimension)
        val disk = FakeArtworkDiskCacheBackend()
        val networkBytes = byteArrayOf(NetworkHitByte)
        ArtworkDiskCache.useBackendForTest(disk)
        RemoteArtworkCache.fetchRemoteArtworkBytesForTest = { _, requestedFetchUrl ->
            assertEquals(fetchUrl, requestedFetchUrl)
            networkBytes
        }
        RemoteArtworkCache.decodeImageBitmapForTest = { bytes, maxDecodeDimension ->
            if (bytes.contentEquals(networkBytes)) testImageBitmap(maxDecodeDimension, maxDecodeDimension) else null
        }

        val image = RemoteArtworkCache.awaitLoad(PlexArtworkUrl, ListArtworkMaxDecodeDimension)

        assertNotNull(image)
        assertEquals(ListArtworkMaxDecodeDimension, image.width)
        assertEquals(networkBytes.toList(), disk.writes[fetchUrl]?.toList())
        assertFalse(PlexArtworkUrl in disk.writes)
    }

    @Test
    fun heroRequestDoesNotReuseLowerResolutionDiskBytesAsFinalArtwork() = runTest {
        val listFetchUrl = sizedPlexArtworkUrl(ListArtworkMaxDecodeDimension)
        val heroFetchUrl = sizedPlexArtworkUrl(HeroArtworkMaxDecodeDimension)
        val disk = FakeArtworkDiskCacheBackend(
            mapOf(listFetchUrl to byteArrayOf(DiskHitByte)),
        )
        ArtworkDiskCache.useBackendForTest(disk)
        RemoteArtworkCache.fetchRemoteArtworkBytesForTest = { _, requestedFetchUrl ->
            assertEquals(heroFetchUrl, requestedFetchUrl)
            byteArrayOf(NetworkHitByte)
        }
        RemoteArtworkCache.decodeImageBitmapForTest = { bytes, maxDecodeDimension ->
            when (bytes.singleOrNull()) {
                DiskHitByte -> testImageBitmap(ListArtworkMaxDecodeDimension, ListArtworkMaxDecodeDimension)
                NetworkHitByte -> testImageBitmap(maxDecodeDimension, maxDecodeDimension)
                else -> null
            }
        }

        val image = RemoteArtworkCache.awaitLoad(PlexArtworkUrl, HeroArtworkMaxDecodeDimension)

        assertNotNull(image)
        assertEquals(HeroArtworkMaxDecodeDimension, image.width)
        assertFalse(listFetchUrl in disk.reads)
        assertEquals(byteArrayOf(NetworkHitByte).toList(), disk.writes[heroFetchUrl]?.toList())
    }

    @Test
    fun invalidDiskBytesAreIgnoredAndNetworkIsAttempted() = runTest {
        val fetchUrl = sizedPlexArtworkUrl(ListArtworkMaxDecodeDimension)
        val disk = FakeArtworkDiskCacheBackend(
            mapOf(fetchUrl to byteArrayOf(InvalidDiskByte)),
        )
        var networkRequests = 0
        ArtworkDiskCache.useBackendForTest(disk)
        RemoteArtworkCache.fetchRemoteArtworkBytesForTest = { _, _ ->
            networkRequests++
            byteArrayOf(NetworkHitByte)
        }
        RemoteArtworkCache.decodeImageBitmapForTest = { bytes, maxDecodeDimension ->
            if (bytes.singleOrNull() == NetworkHitByte) testImageBitmap(maxDecodeDimension, maxDecodeDimension) else null
        }

        val image = RemoteArtworkCache.awaitLoad(PlexArtworkUrl, ListArtworkMaxDecodeDimension)

        assertNotNull(image)
        assertEquals(ListArtworkMaxDecodeDimension, image.width)
        assertEquals(1, networkRequests)
        assertEquals(byteArrayOf(NetworkHitByte).toList(), disk.writes[fetchUrl]?.toList())
    }

    @Test
    fun slowSizedArtworkRequestFallsBackToOriginalUrl() = runTest {
        val sizedFetchUrl = sizedPlexArtworkUrl(ListArtworkMaxDecodeDimension)
        val disk = FakeArtworkDiskCacheBackend()
        val requests = mutableListOf<String>()
        ArtworkDiskCache.useBackendForTest(disk)
        RemoteArtworkCache.fetchRemoteArtworkBytesForTest = { _, requestedFetchUrl ->
            requests += requestedFetchUrl
            if (requestedFetchUrl == sizedFetchUrl) {
                delay(2_000L)
                byteArrayOf(DiskHitByte)
            } else {
                byteArrayOf(NetworkHitByte)
            }
        }
        RemoteArtworkCache.decodeImageBitmapForTest = { bytes, maxDecodeDimension ->
            if (bytes.singleOrNull() == NetworkHitByte) testImageBitmap(maxDecodeDimension, maxDecodeDimension) else null
        }

        val image = RemoteArtworkCache.awaitLoad(PlexArtworkUrl, ListArtworkMaxDecodeDimension)

        assertNotNull(image)
        assertEquals(ListArtworkMaxDecodeDimension, image.width)
        assertEquals(listOf(sizedFetchUrl, PlexArtworkUrl), requests)
        assertEquals(byteArrayOf(NetworkHitByte).toList(), disk.writes[PlexArtworkUrl]?.toList())
    }

    @Test
    fun sizedArtworkBytesInsideGraceDoNotFallBackWhenDecodeCrossesGraceDeadline() = runTest {
        val sizedFetchUrl = sizedPlexArtworkUrl(ListArtworkMaxDecodeDimension)
        val disk = FakeArtworkDiskCacheBackend()
        val requests = mutableListOf<String>()
        ArtworkDiskCache.useBackendForTest(disk)
        RemoteArtworkCache.fetchRemoteArtworkBytesForTest = { _, requestedFetchUrl ->
            requests += requestedFetchUrl
            if (requestedFetchUrl == sizedFetchUrl) {
                delay(650L)
                byteArrayOf(DiskHitByte)
            } else {
                byteArrayOf(NetworkHitByte)
            }
        }
        RemoteArtworkCache.decodeImageBitmapForTest = { bytes, maxDecodeDimension ->
            when (bytes.singleOrNull()) {
                DiskHitByte -> {
                    val started = TimeSource.Monotonic.markNow()
                    while (started.elapsedNow() < 200.milliseconds) {
                        // Keep this synchronous to mirror BitmapFactory.decodeByteArray on Android.
                    }
                    testImageBitmap(maxDecodeDimension, maxDecodeDimension)
                }
                NetworkHitByte -> testImageBitmap(maxDecodeDimension, maxDecodeDimension)
                else -> null
            }
        }

        val image = RemoteArtworkCache.awaitLoad(PlexArtworkUrl, ListArtworkMaxDecodeDimension)

        assertNotNull(image)
        assertEquals(ListArtworkMaxDecodeDimension, image.width)
        assertEquals(listOf(sizedFetchUrl), requests)
        assertEquals(byteArrayOf(DiskHitByte).toList(), disk.writes[sizedFetchUrl]?.toList())
        assertFalse(PlexArtworkUrl in disk.writes)
    }

    @Test
    fun recentFailureDoesNotBlockCachedFallbackArtwork() {
        RemoteArtworkCache.configureLimitsForTest(maxEntries = 10, maxEstimatedBytes = Long.MAX_VALUE)
        RemoteArtworkCache.markFailedForTest("primary", ListArtworkMaxDecodeDimension)
        RemoteArtworkCache.putForTest("fallback", ListArtworkMaxDecodeDimension, testImageBitmap(256, 256))

        val state = cachedStateForDisplay("primary", ListArtworkMaxDecodeDimension, fallbackUrl = "fallback")

        assertTrue(RemoteArtworkCache.hasRecentFailure("primary", ListArtworkMaxDecodeDimension))
        assertFalse(RemoteArtworkCache.hasRecentFailure("fallback", ListArtworkMaxDecodeDimension))
        assertIs<RemoteImageLoadState.Ready>(state)
        assertEquals(256, state.image.width)
    }

    @Test
    fun unresolvedArtworkRemainsLoadingUntilARealFailureIsRecorded() {
        val pending = cachedStateForDisplay("pending", ListArtworkMaxDecodeDimension)

        assertIs<RemoteImageLoadState.Loading>(pending)
        assertFalse(RemoteArtworkCache.hasRecentFailure("pending", ListArtworkMaxDecodeDimension))

        RemoteArtworkCache.markFailedForTest("pending", ListArtworkMaxDecodeDimension)

        assertTrue(RemoteArtworkCache.hasRecentFailure("pending", ListArtworkMaxDecodeDimension))
    }

    @Test
    fun retryFailedLoadsNowClearsTransientFailuresAndBumpsRetryEpoch() {
        RemoteArtworkCache.markFailedForTest("resume-art", ListArtworkMaxDecodeDimension)
        val before = RemoteArtworkCache.retryEpoch

        RemoteArtworkCache.retryFailedLoadsNow()

        assertFalse(RemoteArtworkCache.hasRecentFailure("resume-art", ListArtworkMaxDecodeDimension))
        assertEquals(before + 1, RemoteArtworkCache.retryEpoch)
    }

    @Test
    fun trimForMemoryPressureEvictsCachedImages() {
        RemoteArtworkCache.configureLimitsForTest(maxEntries = 40, maxEstimatedBytes = Long.MAX_VALUE)
        repeat(24) { index ->
            RemoteArtworkCache.putForTest("art-$index", 128, testImageBitmap(32, 32))
        }
        assertEquals(24, RemoteArtworkCache.stats().imageCount)

        RemoteArtworkCache.trimForMemoryPressure(aggressive = true)

        assertTrue(RemoteArtworkCache.stats().imageCount <= 10)
    }

    @Test
    fun trimForMemoryPressureDoesNotPermanentlyLowerCacheLimits() {
        RemoteArtworkCache.configureLimitsForTest(maxEntries = 40, maxEstimatedBytes = Long.MAX_VALUE)
        repeat(24) { index ->
            RemoteArtworkCache.putForTest("before-$index", 128, testImageBitmap(32, 32))
        }

        RemoteArtworkCache.trimForMemoryPressure(aggressive = true)
        repeat(24) { index ->
            RemoteArtworkCache.putForTest("after-$index", 128, testImageBitmap(32, 32))
        }

        assertTrue(RemoteArtworkCache.stats().imageCount > 10)
    }

    @Test
    fun clearUnderMemoryPressureDropsAllDecodedImages() {
        RemoteArtworkCache.configureLimitsForTest(maxEntries = 20, maxEstimatedBytes = Long.MAX_VALUE)
        RemoteArtworkCache.putForTest("art", 128, testImageBitmap(32, 32))
        assertEquals(1, RemoteArtworkCache.stats().imageCount)

        RemoteArtworkCache.clearUnderMemoryPressure()

        assertEquals(0, RemoteArtworkCache.stats().imageCount)
        assertNull(RemoteArtworkCache.cached("art", 128))
    }

    @Test
    fun clearUnderMemoryPressureDoesNotPermanentlyLowerCacheLimits() {
        RemoteArtworkCache.configureLimitsForTest(maxEntries = 20, maxEstimatedBytes = Long.MAX_VALUE)

        RemoteArtworkCache.clearUnderMemoryPressure()
        repeat(12) { index ->
            RemoteArtworkCache.putForTest("after-clear-$index", 128, testImageBitmap(32, 32))
        }

        assertEquals(12, RemoteArtworkCache.stats().imageCount)
    }

    @Test
    fun canceledWaiterCancelsStaleInFlightLoad() = runTest {
        val loadStarted = CompletableDeferred<Unit>()
        val loadCanceled = CompletableDeferred<Unit>()
        RemoteArtworkCache.loadArtworkForTest = { _, _ ->
            loadStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                loadCanceled.complete(Unit)
            }
        }

        val job = async {
            RemoteArtworkCache.awaitLoad("slow-art", ListArtworkMaxDecodeDimension)
        }
        loadStarted.await()
        assertEquals(1, RemoteArtworkCache.stats().inFlightCount)

        job.cancelAndJoin()
        loadCanceled.await()

        assertEquals(0, RemoteArtworkCache.stats().inFlightCount)
        assertNull(RemoteArtworkCache.cached("slow-art", ListArtworkMaxDecodeDimension))
    }

    @Test
    fun concurrentReadsAndWritesDoNotThrow() {
        runTest {
            RemoteArtworkCache.configureLimitsForTest(maxEntries = 50, maxEstimatedBytes = Long.MAX_VALUE)

            coroutineScope {
                val jobs = List(24) { worker ->
                    async(Dispatchers.Default) {
                        repeat(40) { index ->
                            val url = "art-$worker-$index"
                            RemoteArtworkCache.putForTest(url, 128, testImageBitmap(64, 64))
                            RemoteArtworkCache.cachedRequested(url, ThumbnailArtworkMaxDecodeDimension)
                            RemoteArtworkCache.cachedForDisplay(url, HeroArtworkMaxDecodeDimension)
                        }
                    }
                }
                jobs.awaitAll()
            }
        }
    }

    @Test
    fun downloadMemoryModeTrimsDecodedArtworkCache() {
        RemoteArtworkCache.configureLimitsForTest(maxEntries = 100, maxEstimatedBytes = Long.MAX_VALUE)

        repeat(80) { index ->
            RemoteArtworkCache.putForTest("art-$index", 256, testImageBitmap(256, 256))
        }

        RemoteArtworkCache.configureDownloadMemoryMode(true)

        assertTrue(RemoteArtworkCache.stats().imageCount <= 32)
        assertTrue(RemoteArtworkCache.stats().estimatedBytes <= 4L * 1024L * 1024L)
    }

    @Test
    fun remoteArtworkRequestUrlsLeaveGenericUrlsAlone() {
        val urls = remoteArtworkRequestUrls(
            "https://images.example/cover.jpg?token=abc",
            maxDecodeDimension = 160,
        )

        assertEquals(
            listOf("https://images.example/cover.jpg?token=abc"),
            urls,
        )
    }

    @Test
    fun remoteArtworkRequestUrlsFallBackToOriginalPlexUrlAfterSizedUrl() {
        val urls = remoteArtworkRequestUrls(
            "https://plex.example/library/metadata/1/thumb/2?X-Plex-Token=token",
            maxDecodeDimension = 160,
        )

        assertEquals(
            listOf(
                "https://plex.example/library/metadata/1/thumb/2?X-Plex-Token=token&width=160&height=160",
                "https://plex.example/library/metadata/1/thumb/2?X-Plex-Token=token",
            ),
            urls,
        )
    }

    @Test
    fun remoteArtworkRequestUrlsDoNotAddExtraSeparators() {
        assertEquals(
            listOf(
                "https://plex.example/library/metadata/1/thumb/2?X-Plex-Token=token&width=160&height=160",
                "https://plex.example/library/metadata/1/thumb/2?X-Plex-Token=token&",
            ),
            remoteArtworkRequestUrls(
                "https://plex.example/library/metadata/1/thumb/2?X-Plex-Token=token&",
                maxDecodeDimension = 160,
            ),
        )
        assertEquals(
            listOf(
                "https://jellyfin.example/Items/album/Images/Primary?api_key=token&maxWidth=160&maxHeight=160",
                "https://jellyfin.example/Items/album/Images/Primary?api_key=token&",
            ),
            remoteArtworkRequestUrls(
                "https://jellyfin.example/Items/album/Images/Primary?api_key=token&",
                maxDecodeDimension = 160,
            ),
        )
    }

    @Test
    fun remoteArtworkRequestUrlsUseEmbyFamilyMaxDimensions() {
        val urls = remoteArtworkRequestUrls(
            "https://jellyfin.example/Items/album/Images/Primary?tag=abc&api_key=token",
            maxDecodeDimension = 256,
        )

        assertEquals(
            listOf(
                "https://jellyfin.example/Items/album/Images/Primary?tag=abc&api_key=token&maxWidth=256&maxHeight=256",
                "https://jellyfin.example/Items/album/Images/Primary?tag=abc&api_key=token",
            ),
            urls,
        )
    }

    @Test
    fun remoteArtworkRequestUrlsUseSizeParameterForCoverArtProxyUrls() {
        assertEquals(
            listOf(
                "https://subsonic.example/rest/getCoverArt.view?id=cover&size=256",
                "https://subsonic.example/rest/getCoverArt.view?id=cover",
            ),
            remoteArtworkRequestUrls(
                "https://subsonic.example/rest/getCoverArt.view?id=cover",
                maxDecodeDimension = 256,
            ),
        )
        assertEquals(
            listOf(
                "https://ma.example/imageproxy?provider=filesystem_local&size=256&path=album%252Fart.jpg",
                "https://ma.example/imageproxy?provider=filesystem_local&size=0&path=album%252Fart.jpg",
            ),
            remoteArtworkRequestUrls(
                "https://ma.example/imageproxy?provider=filesystem_local&size=0&path=album%252Fart.jpg",
                maxDecodeDimension = 256,
            ),
        )
    }
}

private fun testImageBitmap(width: Int, height: Int): ImageBitmap = TestImageBitmap(width, height)

private const val PlexArtworkUrl = "https://plex.example/library/metadata/1/thumb/2?X-Plex-Token=token"
private const val DiskHitByte: Byte = 7
private const val NetworkHitByte: Byte = 11
private const val InvalidDiskByte: Byte = 0

private fun sizedPlexArtworkUrl(maxDecodeDimension: Int): String =
    remoteArtworkRequestUrls(PlexArtworkUrl, maxDecodeDimension).first()

private class FakeArtworkDiskCacheBackend(
    initialBytes: Map<String, ByteArray> = emptyMap(),
) : ArtworkDiskCacheBackend {
    private val bytesByFetchUrl = initialBytes.toMutableMap()
    val reads = mutableListOf<String>()
    val writes = mutableMapOf<String, ByteArray>()

    override suspend fun read(fetchUrl: String): ByteArray? {
        reads += fetchUrl
        return bytesByFetchUrl[fetchUrl]
    }

    override suspend fun write(fetchUrl: String, bytes: ByteArray) {
        writes[fetchUrl] = bytes
        bytesByFetchUrl[fetchUrl] = bytes
    }
}

private class TestImageBitmap(
    override val width: Int,
    override val height: Int,
) : ImageBitmap {
    override val colorSpace: ColorSpace = ColorSpaces.Srgb
    override val hasAlpha: Boolean = true
    override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888

    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) = Unit

    override fun prepareToDraw() = Unit
}
