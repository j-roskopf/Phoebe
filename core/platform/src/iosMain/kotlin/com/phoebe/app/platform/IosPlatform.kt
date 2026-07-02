package com.phoebe.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.phoebe.app.domain.PlexServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.SafariServices.SFSafariViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

@OptIn(ExperimentalNativeApi::class)
actual fun isDebugBuild(): Boolean = Platform.isDebugBinary

actual fun createPlatformHttpClient(): HttpClient = HttpClient(Darwin) {
    engine {
        configureRequest {
            setAllowsCellularAccess(true)
        }
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 60_000
    }
    install(ContentNegotiation) {
        json(platformNetworkJson)
    }
}

actual suspend fun platformStreamHttpDownloadToStorage(
    url: String,
    targetPath: String,
    storage: PlatformStorage,
    bufferSize: Int,
    onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): String? = null

actual fun isDesktopPlatform(): Boolean = false

actual fun isIosPlatform(): Boolean = true

actual fun supportsPredictiveBack(): Boolean = false

actual fun currentNetworkMeteringStatus(): NetworkMeteringStatus = NetworkMeteringStatus()

actual fun defaultDownloadWifiOnly(): Boolean = false

actual suspend fun discoverJellyfinServers(): List<PlexServer> = emptyList()

actual class PlatformStorage actual constructor() {
    private val defaults = NSUserDefaults.standardUserDefaults

    actual suspend fun readText(name: String): String? = defaults.stringForKey(name)

    actual suspend fun writeText(name: String, value: String) {
        defaults.setObject(value, forKey = name)
    }

    actual suspend fun delete(name: String) {
        defaults.removeObjectForKey(name)
    }

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun deleteUri(uri: String) {
        val url = NSURL.URLWithString(uri) ?: return
        NSFileManager.defaultManager.removeItemAtURL(url, error = null)
    }

    @OptIn(BetaInteropApi::class)
    actual suspend fun readUriBytes(uri: String): ByteArray? {
        val url = NSURL.URLWithString(uri) ?: return null
        return NSData.create(contentsOfURL = url)?.toByteArray()
    }

    @OptIn(BetaInteropApi::class)
    actual suspend fun readBytes(name: String): ByteArray? {
        val path = "${storageRootPath()}/$name"
        return NSData.create(contentsOfFile = path)?.toByteArray()
    }

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun writeBytes(name: String, bytes: ByteArray): String {
        val root = storageRootPath()
        val path = "$root/$name"
        val directory = path.substringBeforeLast('/', root)
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        NSFileManager.defaultManager.createFileAtPath(
            path = path,
            contents = bytes.toNSData(),
            attributes = null,
        )
        return NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
    }

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun writeByteStream(name: String, write: suspend (PlatformByteSink) -> Unit): String {
        val root = storageRootPath()
        val path = "$root/$name"
        val directory = path.substringBeforeLast('/', root)
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        val file = fopen(path, "wb") ?: error("Unable to open download file.")
        try {
            write(
                object : PlatformByteSink {
                    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
                        if (length <= 0) return
                        val written = buffer.usePinned { pinned ->
                            fwrite(pinned.addressOf(offset), 1uL, length.toULong(), file)
                        }
                        if (written != length.toULong()) error("Unable to write download bytes.")
                    }
                },
            )
        } catch (error: Throwable) {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
            throw error
        } finally {
            fclose(file)
        }
        return NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
    }

    actual suspend fun readDownloadDirectory(): String? =
        defaults.stringForKey(DownloadDirectoryKey)?.takeIf { it.isNotBlank() }

    actual suspend fun writeDownloadDirectory(uri: String?) {
        if (uri.isNullOrBlank()) defaults.removeObjectForKey(DownloadDirectoryKey)
        else defaults.setObject(uri, forKey = DownloadDirectoryKey)
    }

    actual fun defaultDownloadDirectoryLabel(): String = "App Documents/Phoebe"
}

actual class DownloadNotifier actual constructor() {
    actual suspend fun notifyDownloadFinished(title: String, body: String): Boolean {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ -> }
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = "phoebe-download-${currentTimeMs()}",
            content = content,
            trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(1.0, repeats = false),
        )
        center.addNotificationRequest(request, withCompletionHandler = null)
        return true
    }
}

@Composable
actual fun rememberPickDownloadDirectory(onPicked: (String?) -> Unit): () -> Unit =
    remember(onPicked) {
        { onPicked(null) }
    }

private const val DownloadDirectoryKey = "download-location"

@OptIn(ExperimentalForeignApi::class)
private fun storageRootPath(): String {
    val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String
    val folder = localStorageDirectoryName()
    return if (docs != null) "$docs/$folder" else folder
}

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val bytes = ByteArray(length.toInt())
    bytes.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), this.bytes, length)
    }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
actual fun openExternalUrl(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(
        url = nsUrl,
        options = emptyMap<Any?, Any>(),
        completionHandler = null,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun topPresenterViewController(): UIViewController? {
    val application = UIApplication.sharedApplication
    activeWindow(application)?.rootViewController?.let { return topPresentedViewController(it) }

    for (scene in application.connectedScenes) {
        val windowScene = scene as? UIWindowScene ?: continue
        windowScene.keyWindow?.rootViewController?.let { return topPresentedViewController(it) }
        for (window in windowScene.windows) {
            val uiWindow = window as? UIWindow ?: continue
            uiWindow.rootViewController?.let { return topPresentedViewController(it) }
        }
    }

    for (window in application.windows) {
        val uiWindow = window as? UIWindow ?: continue
        uiWindow.rootViewController?.let { return topPresentedViewController(it) }
    }
    return null
}

@OptIn(ExperimentalForeignApi::class)
private fun activeWindow(application: UIApplication): UIWindow? {
    application.keyWindow?.let { return it }
    for (scene in application.connectedScenes) {
        val windowScene = scene as? UIWindowScene ?: continue
        windowScene.keyWindow?.let { return it }
    }
    return application.windows.firstOrNull() as? UIWindow
}

private fun topPresentedViewController(controller: UIViewController): UIViewController {
    var current = controller
    while (true) {
        val presented = current.presentedViewController ?: return current
        current = presented
    }
}

actual fun currentTimeMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

private val platformNetworkJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

actual fun prefersReducedArtworkEffects(): Boolean = false

actual fun remoteArtworkCacheMaxEstimatedBytes(): Long = 32L * 1024L * 1024L

actual fun remoteArtworkLoadParallelism(): Int = 8

actual fun catalogTrackIndexParallelism(): Int = 6

actual fun downloadParallelism(): Int = 3

actual fun schedulePlatformDownloadRunner() = Unit

actual fun requestNotificationPermission() {}

internal actual fun platformLog(tag: String, message: String) {
    println("[$tag] $message")
}
