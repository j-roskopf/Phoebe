package com.phoebe.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun handlesConcurrentTouchesAndEvictions() = runTest {
        RemoteArtworkCache.configureLimitsForTest(maxEntries = 8, maxEstimatedBytes = Long.MAX_VALUE)
        val image = testImageBitmap(10, 10)

        coroutineScope {
            repeat(24) { worker ->
                launch(Dispatchers.Default) {
                    repeat(200) { index ->
                        val url = "art-${(worker + index) % 32}"
                        RemoteArtworkCache.putForTest(url, 128, image)
                        RemoteArtworkCache.cached(url, 128)
                        RemoteArtworkCache.stats()
                    }
                }
            }
        }

        assertTrue(RemoteArtworkCache.stats().imageCount <= 8)
    }
}

private fun testImageBitmap(width: Int, height: Int): ImageBitmap = TestImageBitmap(width, height)

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
