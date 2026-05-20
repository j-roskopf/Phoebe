package com.phoebe.app.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import com.phoebe.app.AndroidContextHolder
import com.phoebe.app.BuildConfig
import com.phoebe.app.MainActivity
import com.phoebe.app.R
import com.phoebe.app.data.PlexClient
import com.phoebe.app.domain.PlexServer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

actual fun createPlatformHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 60_000
    }
    install(ContentNegotiation) {
        json(PlexClient.PlexJson)
    }
}

actual fun isDesktopPlatform(): Boolean = false

actual class PlatformStorage actual constructor() {
    private val root: File
        get() {
            System.getProperty("phoebe.storage.root")?.let { return File(it).also { f -> f.mkdirs() } }
            return AndroidContextHolder.application.filesDir
                .resolve(com.phoebe.app.data.db.localStorageDirectoryName())
                .also { it.mkdirs() }
        }

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
        val parsed = Uri.parse(uri)
        when (parsed.scheme) {
            "content" -> {
                val context = AndroidContextHolder.application
                val rootTreeUri = readDownloadDirectory()?.let(Uri::parse)
                val authority = parsed.authority
                val documentId = runCatching { DocumentsContract.getDocumentId(parsed) }.getOrNull()
                DocumentFile.fromSingleUri(context, parsed)?.delete()
                    ?: runCatching { context.contentResolver.delete(parsed, null, null) }.getOrNull()
                if (rootTreeUri != null && authority != null && documentId != null) {
                    pruneEmptyDocumentParents(context, rootTreeUri, authority, documentId)
                }
            }
            "file" -> runCatching {
                val file = File(parsed.path.orEmpty())
                val parent = file.parentFile
                file.takeIf { it.exists() }?.delete()
                pruneEmptyDownloadParents(parent)
            }
            else -> null
        }
        Unit
    }

    actual suspend fun readUriBytes(uri: String): ByteArray? = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        when (parsed.scheme) {
            "content" -> AndroidContextHolder.application.contentResolver
                .openInputStream(parsed)
                ?.use { it.readBytes() }
            "file" -> File(parsed.path.orEmpty()).takeIf { it.exists() }?.readBytes()
            else -> null
        }
    }

    actual suspend fun readBytes(name: String): ByteArray? = withContext(Dispatchers.IO) {
        val downloadTree = readDownloadDirectory()
        if (downloadTree != null) {
            readBytesFromTree(downloadTree, name)?.let { return@withContext it }
        }
        defaultDownloadDirectory()
            .resolve(name.removePrefix("downloads/"))
            .takeIf { it.exists() }
            ?.readBytes()
    }

    actual suspend fun writeBytes(name: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val downloadTree = readDownloadDirectory()
        if (downloadTree != null) {
            writeBytesToTree(downloadTree, name, bytes)?.let { return@withContext it }
        }
        defaultDownloadDirectory().resolve(name.removePrefix("downloads/")).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }.toURI().toString()
    }

    actual suspend fun readDownloadDirectory(): String? =
        readText(DownloadDirectoryFile)?.takeIf { it.isNotBlank() }

    actual suspend fun writeDownloadDirectory(uri: String?) {
        if (uri.isNullOrBlank()) delete(DownloadDirectoryFile) else writeText(DownloadDirectoryFile, uri)
    }

    actual fun defaultDownloadDirectoryLabel(): String = defaultDownloadDirectory().absolutePath

    private fun defaultDownloadDirectory(): File {
        val context = AndroidContextHolder.application
        return (context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: root)
            .resolve("Phoebe")
            .also { it.mkdirs() }
    }

    private fun pruneEmptyDownloadParents(start: File?) {
        val stops = listOfNotNull(root.canonicalOrNull(), defaultDownloadDirectory().canonicalOrNull())
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

    private fun pruneEmptyDocumentParents(
        context: Context,
        rootTreeUri: Uri,
        authority: String,
        documentId: String,
    ) {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(rootTreeUri) }.getOrNull() ?: return
        var currentId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
        while (currentId.isNotBlank() && currentId != rootId && currentId.startsWith(rootId)) {
            if (documentChildCount(context, rootTreeUri, currentId) != 0) return
            val currentUri = DocumentsContract.buildDocumentUriUsingTree(
                DocumentsContract.buildTreeDocumentUri(authority, rootId),
                currentId,
            )
            if (!runCatching { DocumentsContract.deleteDocument(context.contentResolver, currentUri) }.getOrDefault(false)) return
            currentId = currentId.substringBeforeLast('/', missingDelimiterValue = "")
        }
    }

    private fun documentChildCount(context: Context, rootTreeUri: Uri, documentId: String): Int {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootTreeUri, documentId)
        return runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { cursor -> cursor.count } ?: 0
        }.getOrDefault(1)
    }

    private fun readBytesFromTree(rootUri: String, name: String): ByteArray? {
        val context = AndroidContextHolder.application
        val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(rootUri)) ?: return null
        val relative = name.removePrefix("downloads/").trim('/')
        val segments = relative.split('/').mapNotNull { it.trim().takeIf(String::isNotBlank) }
        val file = segments.fold(rootDoc) { document, segment ->
            document.findFile(segment) ?: return null
        }.takeIf { it.isFile } ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
    }

    private fun writeBytesToTree(rootUri: String, name: String, bytes: ByteArray): String? {
        val context = AndroidContextHolder.application
        val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(rootUri)) ?: return null
        val relative = name.removePrefix("downloads/").trim('/')
        val segments = relative.split('/').mapNotNull { it.trim().takeIf(String::isNotBlank) }
        val fileName = segments.lastOrNull() ?: "download.audio"
        val parent = segments.dropLast(1).fold(rootDoc) { directory, segment ->
            directory.findFile(segment)?.takeIf { it.isDirectory }
                ?: directory.createDirectory(segment)
                ?: return null
        }
        val existing = parent.findFile(fileName)
        existing?.delete()
        val mimeType = when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            else -> "application/octet-stream"
        }
        val doc = parent.createFile(mimeType, fileName) ?: return null
        context.contentResolver.openOutputStream(doc.uri)?.use { stream ->
            stream.write(bytes)
        } ?: return null
        return doc.uri.toString()
    }
}

private fun File.canonicalOrNull(): File? =
    runCatching { canonicalFile }.getOrNull()

private fun File.isDescendantOf(parent: File): Boolean =
    runCatching { toPath().startsWith(parent.toPath()) }.getOrDefault(false)

actual class DownloadNotifier actual constructor() {
    actual suspend fun notifyDownloadFinished(title: String, body: String): Boolean = withContext(Dispatchers.IO) {
        val context = AndroidContextHolder.application
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext false
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    DownloadNotificationChannelId,
                    "Downloads",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, DownloadNotificationChannelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(DownloadNotificationId, notification)
        true
    }
}

private const val DownloadNotificationChannelId = "phoebe_downloads"
private const val DownloadNotificationId = 2001

@Composable
actual fun rememberPickDownloadDirectory(onPicked: (String?) -> Unit): () -> Unit {
    val activity = LocalContext.current as ComponentActivity
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
            }
        }
        onPicked(uri?.toString())
    }
    return remember(launcher) {
        { launcher.launch(null) }
    }
}

private const val DownloadDirectoryFile = "download-location.txt"

actual fun openExternalUrl(url: String) {
    val context: Context = AndroidContextHolder.application
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

actual suspend fun discoverJellyfinServers(): List<PlexServer> = withContext(Dispatchers.IO) {
    val found = linkedMapOf<String, PlexServer>()
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

actual fun currentTimeMs(): Long = System.currentTimeMillis()

actual fun prefersReducedArtworkEffects(): Boolean = false

actual fun catalogTrackPrefetchAlbumCount(): Int = 0

actual fun catalogTrackPrefetchParallelism(): Int = 1

actual fun deferCachedTrackHydrationOnStartup(): Boolean = true

actual fun deferPlexTrackIndexOnRefresh(): Boolean = true

actual fun isDebugBuild(): Boolean = BuildConfig.DEBUG

internal actual fun platformLog(tag: String, message: String) {
    Log.d(tag, message)
}

private const val JellyfinDiscoveryPort = 7359
