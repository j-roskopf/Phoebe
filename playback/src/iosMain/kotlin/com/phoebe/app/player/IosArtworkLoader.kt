package com.phoebe.app.player

import com.phoebe.app.platform.createPlatformMediaHttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext

@OptIn(ExperimentalForeignApi::class)
internal object IosArtworkLoader {
    private val httpClient by lazy { createPlatformMediaHttpClient() }
    private val cache = LinkedHashMap<String, UIImage>()

    suspend fun load(url: String): UIImage? {
        cached(url)?.let { return it }
        val image = withContext(Dispatchers.Default) {
            runCatching {
                val bytes: ByteArray = httpClient.get(url).body()
                val data = bytes.toNSData()
                UIImage.imageWithData(data)?.downscaled(MaxArtworkDimension)
            }.getOrNull()
        }
        return image?.let { loaded ->
            cached(url) ?: loaded.also { put(url, it) }
        }
    }

    fun clear() {
        cache.clear()
    }

    private fun cached(url: String): UIImage? {
        val image = cache.remove(url) ?: return null
        cache[url] = image
        return image
    }

    private fun put(url: String, image: UIImage) {
        cache[url] = image
        while (cache.size > MaxCachedImages) {
            cache.remove(cache.keys.first())
        }
    }

    private const val MaxCachedImages = 8
    private const val MaxArtworkDimension = 600.0
}

@OptIn(ExperimentalForeignApi::class)
private fun UIImage.downscaled(maxDimension: Double): UIImage {
    val (width, height) = size.useContents { width to height }
    val largest = maxOf(width, height)
    if (largest <= maxDimension || largest <= 0.0) return this

    val scale = maxDimension / largest
    val targetWidth = width * scale
    val targetHeight = height * scale
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(targetWidth, targetHeight), false, 1.0)
    drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight))
    val image = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return image ?: this
}

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}
