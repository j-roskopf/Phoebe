package com.phoebe.app.platform

import androidx.compose.runtime.Composable
import com.phoebe.app.domain.PlexServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

expect fun createPlatformHttpClient(): io.ktor.client.HttpClient

expect fun isDesktopPlatform(): Boolean

expect fun isIosPlatform(): Boolean

expect fun supportsPredictiveBack(): Boolean

data class NetworkMeteringStatus(
    val isMetered: Boolean = false,
    val isCellular: Boolean = false,
)

expect fun currentNetworkMeteringStatus(): NetworkMeteringStatus

expect fun defaultDownloadWifiOnly(): Boolean

expect class PlatformStorage() {
    suspend fun readText(name: String): String?
    suspend fun writeText(name: String, value: String)
    suspend fun delete(name: String)
    suspend fun deleteUri(uri: String)
    suspend fun readBytes(name: String): ByteArray?
    suspend fun readUriBytes(uri: String): ByteArray?
    suspend fun writeBytes(name: String, bytes: ByteArray): String
    suspend fun writeByteStream(name: String, write: suspend (PlatformByteSink) -> Unit): String
    suspend fun readDownloadDirectory(): String?
    suspend fun writeDownloadDirectory(uri: String?)
    fun defaultDownloadDirectoryLabel(): String
}

interface PlatformByteSink {
    suspend fun write(buffer: ByteArray, offset: Int, length: Int)
}

/**
 * Platform-specific direct HTTP streaming for large audio downloads. Return null to use
 * the common Ktor fallback.
 */
expect suspend fun platformStreamHttpDownloadToStorage(
    url: String,
    targetPath: String,
    storage: PlatformStorage,
    bufferSize: Int,
    onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): String?

@Composable
expect fun rememberPickDownloadDirectory(onPicked: (String?) -> Unit): () -> Unit

expect fun openExternalUrl(url: String)

expect suspend fun discoverJellyfinServers(): List<PlexServer>

/** Current wall-clock time, expressed as Unix millis. Platform-specific because
 * `System.currentTimeMillis()` is JVM-only and `kotlinx-datetime` isn't on the
 * classpath. */
expect fun currentTimeMs(): Long

/** Browser canvas rendering is much more sensitive to repeated shadows / custom draws. */
expect fun prefersReducedArtworkEffects(): Boolean

/** Platform-specific decoded artwork cache budget. Android heaps are much smaller than desktop. */
expect fun remoteArtworkCacheMaxEstimatedBytes(): Long

/** Platform-specific cap for concurrent artwork loads. Android image decode can starve input. */
expect fun remoteArtworkLoadParallelism(): Int

/** Concurrent Plex library track-index page fetches during catalog sync. */
expect fun catalogTrackIndexParallelism(): Int

/** Web playback: skip publishing large in-memory catalog mutations while audio is active. */
expect fun configurePlaybackMemoryPressure(active: Boolean)

expect fun shouldDeferCatalogMemoryUpdates(): Boolean

/** Concurrent audio downloads during offline download batches. */
expect fun downloadParallelism(): Int

/** Ask the platform to continue queued downloads outside the foreground Compose UI when supported. */
expect fun schedulePlatformDownloadRunner()

expect fun requestNotificationPermission()

expect class DownloadNotifier() {
    suspend fun notifyDownloadFinished(title: String, body: String): Boolean
}

@Serializable
internal data class JellyfinDiscoveryResponse(
    val Address: String? = null,
    val Id: String? = null,
    val Name: String? = null,
    val EndpointAddress: String? = null,
)

private val jellyfinDiscoveryJson = Json { ignoreUnknownKeys = true }

internal fun parseJellyfinDiscoveryServer(payload: String): PlexServer? {
    val response = runCatching {
        jellyfinDiscoveryJson.decodeFromString<JellyfinDiscoveryResponse>(payload)
    }.getOrNull() ?: return null
    val address = response.Address?.trimEnd('/')?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
    val rawId = response.Id?.takeIf { it.isNotBlank() } ?: address.hashCode().toUInt().toString(16)
    return PlexServer(
        id = if (rawId.startsWith("jellyfin:")) rawId else "jellyfin:$rawId",
        name = response.Name?.takeIf { it.isNotBlank() } ?: address.removePrefix("https://").removePrefix("http://"),
        uri = address,
        owned = true,
        connectionUris = listOf(address),
        localConnectionUris = listOf(address),
    )
}
