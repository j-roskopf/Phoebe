package com.phoebe.app.platform

import androidx.compose.runtime.Composable
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.awt.Desktop
import java.awt.SystemTray
import java.awt.TrayIcon
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.SocketTimeoutException
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

actual fun createPlatformHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            dispatcher(
                Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 16
                },
            )
        }
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 90_000
        connectTimeoutMillis = 8_000
        socketTimeoutMillis = 45_000
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

actual fun isDesktopPlatform(): Boolean = true

actual fun isIosPlatform(): Boolean = false

actual fun supportsPredictiveBack(): Boolean = false

actual fun currentNetworkMeteringStatus(): NetworkMeteringStatus = NetworkMeteringStatus()

actual fun defaultDownloadWifiOnly(): Boolean = true

private val storageRoot: File by lazy {
    desktopStorageRoot()
}

private val resolvedStorageRoot: File by lazy {
    System.getProperty("phoebe.storage.root")?.let(::File)?.also { it.mkdirs() }
        ?: flatpakDesktopStorageRoot()
        ?: File(System.getProperty("user.home"), desktopDataDirectoryName()).also { it.mkdirs() }
}

/**
 * Writable desktop data root for SQLite, prefs, and embedded browser caches.
 * Flatpak mounts the host home read-only, so sandboxed builds store data under [XDG_DATA_HOME].
 */
fun desktopStorageRoot(): File = resolvedStorageRoot

private fun flatpakDesktopStorageRoot(): File? {
    if (!File("/.flatpak-info").exists()) return null
    val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: return null
    return File(dataHome, localStorageDirectoryName()).also { it.mkdirs() }
}

actual class PlatformStorage actual constructor() {
    private val root = storageRoot.also { it.mkdirs() }

    actual suspend fun readText(name: String): String? = withContext(Dispatchers.IO) {
        root.resolve(name).takeIf { it.exists() }?.readText()
    }

    actual suspend fun writeText(name: String, value: String) = withContext(Dispatchers.IO) {
        root.resolve(name).apply {
            parentFile?.mkdirs()
            writeText(value)
        }
        Unit
    }

    actual suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        root.resolve(name).takeIf { it.exists() }?.delete()
        Unit
    }

    actual suspend fun deleteUri(uri: String) = withContext(Dispatchers.IO) {
        val file = runCatching { Paths.get(URI(uri)).toFile() }.getOrNull() ?: return@withContext
        val parent = file.parentFile
        file.takeIf { it.exists() }?.delete()
        pruneEmptyDownloadParents(parent)
        Unit
    }

    actual suspend fun readUriBytes(uri: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { Paths.get(URI(uri)).toFile() }
            .getOrNull()
            ?.takeIf { it.exists() }
            ?.readBytes()
    }

    actual suspend fun readBytes(name: String): ByteArray? = withContext(Dispatchers.IO) {
        val targetRoot = readDownloadDirectory()
            ?.let { runCatching { Paths.get(URI(it)).toFile() }.getOrNull() }
            ?: defaultDownloadDirectory()
        targetRoot.resolve(name.removePrefix("downloads/"))
            .takeIf { it.exists() }
            ?.readBytes()
    }

    actual suspend fun writeBytes(name: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val targetRoot = readDownloadDirectory()
            ?.let { runCatching { Paths.get(URI(it)).toFile() }.getOrNull() }
            ?: defaultDownloadDirectory()
        val relativeName = name.removePrefix("downloads/")
        targetRoot.resolve(relativeName).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }.toURI().toString()
    }

    actual suspend fun writeByteStream(name: String, write: suspend (PlatformByteSink) -> Unit): String = withContext(Dispatchers.IO) {
        val targetRoot = readDownloadDirectory()
            ?.let { runCatching { Paths.get(URI(it)).toFile() }.getOrNull() }
            ?: defaultDownloadDirectory()
        val relativeName = name.removePrefix("downloads/")
        val file = targetRoot.resolve(relativeName).apply {
            parentFile?.mkdirs()
        }
        try {
            file.outputStream().use { stream ->
                write(
                    object : PlatformByteSink {
                        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
                            if (length > 0) stream.write(buffer, offset, length)
                        }
                    },
                )
                stream.flush()
            }
        } catch (error: Throwable) {
            file.takeIf { it.exists() }?.delete()
            throw error
        }
        file.toURI().toString()
    }

    actual suspend fun readDownloadDirectory(): String? =
        readText(DownloadDirectoryFile)?.takeIf { it.isNotBlank() }

    actual suspend fun writeDownloadDirectory(uri: String?) {
        if (uri.isNullOrBlank()) delete(DownloadDirectoryFile) else writeText(DownloadDirectoryFile, uri)
    }

    actual fun defaultDownloadDirectoryLabel(): String =
        defaultDownloadDirectory().absolutePath
}

actual class DownloadNotifier actual constructor() {
    actual suspend fun notifyDownloadFinished(title: String, body: String): Boolean = withContext(Dispatchers.IO) {
        if (!SystemTray.isSupported()) return@withContext false
        val tray = SystemTray.getSystemTray()
        val icon = TrayIcon(java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB), "Phoebe").apply {
            isImageAutoSize = true
        }
        runCatching {
            tray.add(icon)
            icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
            Thread {
                Thread.sleep(8_000)
                runCatching { tray.remove(icon) }
            }.apply { isDaemon = true }.start()
            true
        }.getOrDefault(false)
    }
}

@Composable
actual fun rememberPickDownloadDirectory(onPicked: (String?) -> Unit): () -> Unit =
    rememberPickDesktopDirectory(
        title = "Choose downloads folder",
        initialDirectory = defaultDownloadDirectory(),
        onPicked = onPicked,
    )

private const val DownloadDirectoryFile = "download-location.txt"

private fun defaultDownloadDirectory(): File =
    File(System.getProperty("user.home"), "Music/Phoebe").also { it.mkdirs() }

private suspend fun pruneEmptyDownloadParents(start: File?) {
    val stops = listOfNotNull(
        storageRoot.canonicalOrNull(),
        defaultDownloadDirectory().canonicalOrNull(),
        PlatformStorage().readDownloadDirectory()
            ?.let { runCatching { Paths.get(URI(it)).toFile() }.getOrNull() }
            ?.canonicalOrNull(),
    )
    var current = start?.canonicalOrNull()
    while (current != null && stops.none { current == it }) {
        if (stops.none { current.isDescendantOf(it) }) return
        val children = current.list()
        if (children == null || children.isNotEmpty()) return
        val parent = current.parentFile
        if (!current.delete()) return
        current = parent?.canonicalOrNull()
    }
}

private fun File.canonicalOrNull(): File? =
    runCatching { canonicalFile }.getOrNull()

private fun File.isDescendantOf(parent: File): Boolean =
    runCatching { toPath().startsWith(parent.toPath()) }.getOrDefault(false)

actual fun openExternalUrl(url: String) {
    val desktop = runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null }.getOrNull()
    if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
        val opened = runCatching {
            desktop.browse(URI(url))
        }.isSuccess
        if (opened) return
    }
    openExternalUrlWithSystemHandler(url)
}

actual suspend fun discoverJellyfinServers() = withContext(Dispatchers.IO) {
    val found = linkedMapOf<String, com.phoebe.app.domain.PlexServer>()
    runCatching {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 350
            val query = "Who is JellyfinServer?".toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(query, query.size, InetAddress.getByName("255.255.255.255"), JellyfinDiscoveryPort)
            socket.send(packet)
            val deadline = System.currentTimeMillis() + 1_200L
            while (System.currentTimeMillis() < deadline) {
                val buffer = ByteArray(4096)
                val response = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(response)
                    val payload = response.data.decodeToString(0, response.length)
                    val server = parseJellyfinDiscoveryServer(payload) ?: continue
                    found[server.id] = server
                } catch (_: SocketTimeoutException) {
                    // Keep listening until the short discovery window closes.
                }
            }
        }
    }
    found.values.toList()
}

private fun openExternalUrlWithSystemHandler(url: String) {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val command = when {
        "mac" in os -> arrayOf("open", url)
        "win" in os -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
        else -> arrayOf("xdg-open", url)
    }
    ProcessBuilder(*command).start()
}

actual fun currentTimeMs(): Long = System.currentTimeMillis()

actual fun prefersReducedArtworkEffects(): Boolean = false

actual fun remoteArtworkCacheMaxEstimatedBytes(): Long = 96L * 1024L * 1024L

actual fun remoteArtworkLoadParallelism(): Int = 8

actual fun catalogTrackIndexParallelism(): Int = 6

actual fun downloadParallelism(): Int = 6

actual fun schedulePlatformDownloadRunner() = Unit

actual fun requestNotificationPermission() {}

actual fun isDebugBuild(): Boolean =
    System.getProperty("phoebe.debug")?.toBooleanStrictOrNull() ?: false

internal actual fun platformLog(tag: String, message: String) {
    println("[$tag] $message")
}

private const val JellyfinDiscoveryPort = 7359

private val platformNetworkJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
