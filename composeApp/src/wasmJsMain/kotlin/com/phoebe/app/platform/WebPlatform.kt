package com.phoebe.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.phoebe.app.data.PlexClient
import com.phoebe.app.domain.PlexServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.browser.window
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

actual fun createPlatformHttpClient(): HttpClient = HttpClient(Js) {
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 60_000
    }
    install(ContentNegotiation) {
        json(PlexClient.PlexJson)
    }
}

actual fun isDesktopPlatform(): Boolean = false

actual suspend fun discoverJellyfinServers(): List<PlexServer> = emptyList()

actual class PlatformStorage actual constructor() {
    actual suspend fun readText(name: String): String? =
        window.localStorage.getItem(storageKey(name))

    actual suspend fun writeText(name: String, value: String) {
        window.localStorage.setItem(storageKey(name), value)
    }

    actual suspend fun delete(name: String) {
        window.localStorage.removeItem(storageKey(name))
    }

    actual suspend fun deleteUri(uri: String) {
        if (!uri.startsWith("web-storage://")) return
        window.localStorage.removeItem(storageKey(decodeURIComponent(uri.removePrefix("web-storage://"))))
    }

    actual suspend fun readUriBytes(uri: String): ByteArray? {
        if (!uri.startsWith("web-storage://")) return null
        val encoded = window.localStorage.getItem(storageKey(decodeURIComponent(uri.removePrefix("web-storage://")))) ?: return null
        return window.atob(encoded).toByteArrayFromBinaryString()
    }

    actual suspend fun readBytes(name: String): ByteArray? {
        val encoded = window.localStorage.getItem(storageKey(name)) ?: return null
        return window.atob(encoded).toByteArrayFromBinaryString()
    }

    actual suspend fun writeBytes(name: String, bytes: ByteArray): String {
        val encoded = window.btoa(bytes.toBinaryString())
        window.localStorage.setItem(storageKey(name), encoded)
        return "web-storage://${encodeURIComponent(name)}"
    }

    actual suspend fun readDownloadDirectory(): String? =
        window.localStorage.getItem(storageKey(DownloadDirectoryKey))

    actual suspend fun writeDownloadDirectory(uri: String?) {
        if (uri.isNullOrBlank()) window.localStorage.removeItem(storageKey(DownloadDirectoryKey))
        else window.localStorage.setItem(storageKey(DownloadDirectoryKey), uri)
    }

    actual fun defaultDownloadDirectoryLabel(): String = "Browser storage"

    private fun storageKey(name: String): String = "phoebe:$name"
}

actual class DownloadNotifier actual constructor() {
    actual suspend fun notifyDownloadFinished(title: String, body: String): Boolean {
        if (!browserNotificationsSupported()) return false
        val granted = when (browserNotificationPermission()) {
            "granted" -> true
            "default" -> requestBrowserNotificationPermissionSuspending() == "granted"
            else -> false
        }
        if (!granted) return false
        showBrowserNotification(title, body)
        return true
    }
}

@Composable
actual fun rememberPickDownloadDirectory(onPicked: (String?) -> Unit): () -> Unit =
    remember(onPicked) {
        { onPicked(null) }
    }

private const val DownloadDirectoryKey = "download-location"

actual fun openExternalUrl(url: String) {
    window.open(url, target = "_blank")
}

actual fun currentTimeMs(): Long = jsDateNow().toLong()

actual fun prefersReducedArtworkEffects(): Boolean = true

actual fun catalogTrackPrefetchAlbumCount(): Int = 6

actual fun catalogTrackPrefetchParallelism(): Int = 2

actual fun deferCachedTrackHydrationOnStartup(): Boolean = false

actual fun deferPlexTrackIndexOnRefresh(): Boolean = false

actual fun isDebugBuild(): Boolean = wasmDebugBuildEnabled()

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
    () => {
      if (typeof globalThis.PHOEBE_DEBUG === 'boolean') return globalThis.PHOEBE_DEBUG;
      if (typeof location !== 'undefined') {
        const host = location.hostname;
        if (host === 'localhost' || host === '127.0.0.1') return true;
      }
      return false;
    }
    """,
)
private external fun wasmDebugBuildEnabled(): Boolean

internal actual fun platformLog(tag: String, message: String) {
    println("[$tag] $message")
}

private fun ByteArray.toBinaryString(): String =
    joinToString(separator = "") { (it.toInt() and 0xff).toChar().toString() }

private fun String.toByteArrayFromBinaryString(): ByteArray =
    ByteArray(length) { index -> this[index].code.toByte() }

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => Date.now()")
private external fun jsDateNow(): Double

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(value) => encodeURIComponent(value)")
private external fun encodeURIComponent(value: String): String

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(value) => decodeURIComponent(value)")
private external fun decodeURIComponent(value: String): String

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => typeof Notification !== 'undefined'")
private external fun browserNotificationsSupported(): Boolean

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => Notification.permission")
private external fun browserNotificationPermission(): String

private suspend fun requestBrowserNotificationPermissionSuspending(): String =
    suspendCoroutine { continuation ->
        requestBrowserNotificationPermission { result -> continuation.resume(result) }
    }

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(callback) => { Notification.requestPermission().then(callback); }")
private external fun requestBrowserNotificationPermission(callback: (String) -> Unit)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(title, body) => { new Notification(title, { body }); }")
private external fun showBrowserNotification(title: String, body: String)
