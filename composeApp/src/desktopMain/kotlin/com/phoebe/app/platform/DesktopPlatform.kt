package com.phoebe.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.phoebe.app.data.PlexClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

actual fun createPlatformHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 60_000
    }
    install(ContentNegotiation) {
        json(PlexClient.PlexJson)
    }
}

actual fun isDesktopPlatform(): Boolean = true

private val storageRoot: File by lazy {
    com.phoebe.app.data.db.desktopDatabaseRoot()
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
    remember(onPicked) {
        {
            SwingUtilities.invokeLater {
                val chooser = JFileChooser(defaultDownloadDirectory()).apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = "Choose downloads folder"
                    isAcceptAllFileFilterUsed = false
                }
                val ok = chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION
                val file = chooser.selectedFile
                onPicked(if (ok && file != null) file.toURI().toString() else null)
            }
        }
    }

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
        desktop.browse(URI(url))
        return
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

actual fun catalogTrackPrefetchAlbumCount(): Int = 24

actual fun catalogTrackPrefetchParallelism(): Int = 6

actual fun deferCachedTrackHydrationOnStartup(): Boolean = false

actual fun deferPlexTrackIndexOnRefresh(): Boolean = false

actual fun isDebugBuild(): Boolean =
    System.getProperty("phoebe.debug")?.toBooleanStrictOrNull() ?: false

internal actual fun platformLog(tag: String, message: String) {
    println("[$tag] $message")
}

private const val JellyfinDiscoveryPort = 7359
