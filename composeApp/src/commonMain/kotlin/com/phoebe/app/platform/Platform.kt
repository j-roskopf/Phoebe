package com.phoebe.app.platform

import androidx.compose.runtime.Composable
import com.phoebe.app.domain.PlexServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

expect fun createPlatformHttpClient(): io.ktor.client.HttpClient

expect fun isDesktopPlatform(): Boolean

expect class PlatformStorage() {
    suspend fun readText(name: String): String?
    suspend fun writeText(name: String, value: String)
    suspend fun delete(name: String)
    suspend fun deleteUri(uri: String)
    suspend fun readBytes(name: String): ByteArray?
    suspend fun readUriBytes(uri: String): ByteArray?
    suspend fun writeBytes(name: String, bytes: ByteArray): String
    suspend fun readDownloadDirectory(): String?
    suspend fun writeDownloadDirectory(uri: String?)
    fun defaultDownloadDirectoryLabel(): String
}

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

/** Number of album track lists to eagerly fetch while building the first catalog snapshot. */
expect fun catalogTrackPrefetchAlbumCount(): Int

/** Maximum number of catalog prefetch requests to transform at once. */
expect fun catalogTrackPrefetchParallelism(): Int

/** Whether startup should publish the cached catalog shell before hydrating every cached track. */
expect fun deferCachedTrackHydrationOnStartup(): Boolean

/** Whether a Plex refresh should finish before the whole-library track index is complete. */
expect fun deferPlexTrackIndexOnRefresh(): Boolean

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
