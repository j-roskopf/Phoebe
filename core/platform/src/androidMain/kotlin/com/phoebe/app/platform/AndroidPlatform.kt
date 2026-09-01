package com.phoebe.app.platform

import android.Manifest
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkManager
import com.phoebe.app.AndroidContextHolder
import com.phoebe.app.domain.PlexServer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

private val Ipv4PreferredDns = Dns { hostname ->
    val all = Dns.SYSTEM.lookup(hostname)
    val ipv4 = all.filterIsInstance<Inet4Address>()
    if (ipv4.isEmpty()) all else ipv4 + all.filter { it !is Inet4Address }
}

actual fun createPlatformHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine { applyPhoebeOkHttpEngine() }
    install(HttpTimeout) {
        // Large Plex library metadata responses can exceed 30s on mobile networks.
        requestTimeoutMillis = 90_000
        connectTimeoutMillis = 8_000
        socketTimeoutMillis = 45_000
    }
    install(ContentNegotiation) {
        json(platformNetworkJson)
    }
}

actual fun createPlatformMediaHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine { applyPhoebeOkHttpEngine() }
    install(HttpTimeout) {
        requestTimeoutMillis = 8_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 8_000
    }
    install(DefaultRequest) {
        header(HttpHeaders.Accept, "image/jpeg,image/png,image/webp,image/*,*/*")
    }
}

private fun io.ktor.client.engine.okhttp.OkHttpConfig.applyPhoebeOkHttpEngine() {
    config {
        fastFallback(true)
        dns(Ipv4PreferredDns)
        connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        dispatcher(
            Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            },
        )
    }
}

private val AndroidDownloadHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .fastFallback(true)
        .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}

actual suspend fun platformStreamHttpDownloadToStorage(
    url: String,
    targetPath: String,
    storage: PlatformStorage,
    bufferSize: Int,
    onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): String? = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(url)
        .build()
    AndroidDownloadHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            error("Server returned HTTP ${response.code}")
        }
        val body = response.body
        val totalBytes = body.contentLength().takeIf { it > 0L }
        var downloadedBytes = 0L
        val buffer = ByteArray(bufferSize.coerceAtLeast(8 * 1024))
        storage.writeByteStream(targetPath) { sink ->
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    sink.write(buffer, 0, read)
                    downloadedBytes += read.toLong()
                    onProgress(downloadedBytes, totalBytes)
                }
            }
            if (totalBytes != null && downloadedBytes != totalBytes) {
                error("Content-Length mismatch: expected $totalBytes bytes, but received $downloadedBytes bytes")
            }
        }
    }
}

actual fun isDesktopPlatform(): Boolean = false

actual fun isIosPlatform(): Boolean = false

actual fun supportsPredictiveBack(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

actual fun currentNetworkMeteringStatus(): NetworkMeteringStatus =
    currentNetworkIdentity().metering

actual fun currentNetworkIdentity(): NetworkIdentity {
    val context = AndroidContextHolder.applicationOrNull ?: return NetworkIdentity()
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return NetworkIdentity()
    return androidNetworkIdentity(connectivity, connectivity.activeNetwork)
}

actual fun observeNetworkIdentity(): Flow<NetworkIdentity> = callbackFlow {
    val context = AndroidContextHolder.applicationOrNull
    if (context == null) {
        trySend(NetworkIdentity())
        close()
        return@callbackFlow
    }
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    if (connectivity == null) {
        trySend(NetworkIdentity())
        close()
        return@callbackFlow
    }
    val emit = {
        trySend(androidNetworkIdentity(connectivity, connectivity.activeNetwork))
        Unit
    }
    emit()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = emit()
        override fun onLost(network: Network) = emit()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = emit()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = emit()
    }
    // The default network is the one this app's sockets actually use. A plain
    // registerNetworkCallback with an INTERNET request fires for *every* matching network —
    // including the Wi-Fi that is on its way down during a handoff — while the handler reads
    // `activeNetwork`, so the emitted identity could describe a different network than the
    // callback was about.
    runCatching { connectivity.registerDefaultNetworkCallback(callback) }
        .onFailure {
            trySend(androidNetworkIdentity(connectivity, connectivity.activeNetwork))
        }
    awaitClose {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }
}.distinctUntilChanged()

actual fun defaultDownloadWifiOnly(): Boolean = false

private fun androidNetworkIdentity(
    connectivity: ConnectivityManager,
    network: Network?,
): NetworkIdentity {
    if (network == null) {
        return NetworkIdentity(
            transport = NetworkTransport.None,
            fingerprint = networkFingerprint(NetworkTransport.None, ""),
            metering = NetworkMeteringStatus(isMetered = true, isCellular = false),
        )
    }
    val capabilities = connectivity.getNetworkCapabilities(network)
    // A VPN hides the underlying transport, and its tunnel does not reach the server's LAN.
    // Reported as `Other` it used to keep LAN-first ordering on a VPN over cellular.
    val vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    val transport = when {
        capabilities == null -> NetworkTransport.Other
        vpn -> NetworkTransport.Other
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.Wifi
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.Cellular
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.Ethernet
        else -> NetworkTransport.Other
    }
    val metering = NetworkMeteringStatus(
        isMetered = connectivity.isActiveNetworkMetered || vpn,
        isCellular = transport == NetworkTransport.Cellular,
    )
    val linkProperties = connectivity.getLinkProperties(network)
    val prefixes = if (transport == NetworkTransport.Cellular || transport == NetworkTransport.None || vpn) {
        emptyList()
    } else {
        androidLocalIpv4Prefixes(linkProperties)
    }
    val material = when {
        vpn -> "vpn"
        transport == NetworkTransport.Cellular -> "cellular"
        transport == NetworkTransport.None -> ""
        else -> androidNetworkMaterial(linkProperties)
    }
    // A captive portal or a Wi-Fi that has not finished associating reports INTERNET without
    // being usable. Fold that into the fingerprint so leaving it counts as a network change.
    val validated = capabilities
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != false
    return NetworkIdentity(
        transport = transport,
        fingerprint = networkFingerprint(transport, if (validated) material else "$material/unvalidated"),
        metering = metering,
        localIpv4Prefixes = prefixes,
    )
}

private fun androidLocalIpv4Prefixes(linkProperties: LinkProperties?): List<String> {
    if (linkProperties == null) return emptyList()
    return linkProperties.linkAddresses
        .mapNotNull { address ->
            val host = address.address?.hostAddress ?: return@mapNotNull null
            if (':' in host) return@mapNotNull null
            ipv4Slash24Prefix(host)
        }
        .distinct()
        .sorted()
}

private fun androidNetworkMaterial(linkProperties: LinkProperties?): String {
    if (linkProperties == null) return ""
    val gateways = linkProperties.routes
        .mapNotNull { route -> route.gateway?.hostAddress?.takeIf { it.isNotBlank() } }
        .distinct()
        .sorted()
    val subnets = linkProperties.linkAddresses
        .mapNotNull { address ->
            val host = address.address?.hostAddress ?: return@mapNotNull null
            if (':' in host) return@mapNotNull null
            val parts = host.split('.')
            if (parts.size != 4) return@mapNotNull null
            "${parts[0]}.${parts[1]}.${parts[2]}.0/${address.prefixLength}"
        }
        .distinct()
        .sorted()
    return (gateways + subnets).joinToString("|")
}

actual class PlatformStorage actual constructor() {
    private val root: File
        get() {
            System.getProperty("phoebe.storage.root")?.let { return File(it).also { f -> f.mkdirs() } }
            return AndroidContextHolder.application.filesDir
                .resolve(localStorageDirectoryName())
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
                val deleted = DocumentFile.fromSingleUri(context, parsed)?.delete() == true ||
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, parsed) }.getOrDefault(false) ||
                    runCatching { context.contentResolver.delete(parsed, null, null) > 0 }.getOrDefault(false)
                if (!deleted) {
                    Log.d("PlatformStorage", "content delete returned false for $uri")
                }
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
            else -> Unit
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

    actual suspend fun writeByteStream(name: String, write: suspend (PlatformByteSink) -> Unit): String = withContext(Dispatchers.IO) {
        val downloadTree = readDownloadDirectory()
        if (downloadTree != null) {
            writeByteStreamToTree(downloadTree, name, write)?.let { return@withContext it }
        }
        val file = defaultDownloadDirectory().resolve(name.removePrefix("downloads/")).apply {
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
        val mimeType = downloadMimeType(fileName)
        val doc = parent.createFile(mimeType, fileName) ?: return null
        val outputStream = context.contentResolver.openOutputStream(doc.uri) ?: run {
            doc.delete()
            return null
        }
        outputStream.use { stream ->
            stream.write(bytes)
        }
        return doc.uri.toString()
    }

    private suspend fun writeByteStreamToTree(
        rootUri: String,
        name: String,
        write: suspend (PlatformByteSink) -> Unit,
    ): String? {
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
        val mimeType = downloadMimeType(fileName)
        val doc = parent.createFile(mimeType, fileName) ?: return null
        val outputStream = context.contentResolver.openOutputStream(doc.uri) ?: run {
            doc.delete()
            return null
        }
        try {
            outputStream.use { stream ->
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
            doc.delete()
            throw error
        }
        return doc.uri.toString()
    }
}

private fun File.canonicalOrNull(): File? =
    runCatching { canonicalFile }.getOrNull()

private fun File.isDescendantOf(parent: File): Boolean =
    runCatching { toPath().startsWith(parent.toPath()) }.getOrDefault(false)

private fun downloadMimeType(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }

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
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, DownloadNotificationChannelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
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
    val activity = checkNotNull(LocalActivity.current) {
        "rememberPickDownloadDirectory must be hosted in an Activity."
    }
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

actual fun remoteArtworkCacheMaxEstimatedBytes(): Long = 24L * 1024L * 1024L

actual fun remoteArtworkLoadParallelism(): Int = 2

actual fun catalogTrackIndexParallelism(): Int = 6

actual fun configurePlaybackMemoryPressure(active: Boolean) = Unit

actual fun shouldDeferCatalogMemoryUpdates(): Boolean = false

actual fun downloadParallelism(): Int = 2

actual fun schedulePlatformDownloadRunner() = Unit

actual fun requestNotificationPermission() {
    val activity = com.phoebe.app.AndroidContextHolder.activity as? android.app.Activity ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }
    }
}

fun cancelPlatformDownloadRunner() {
    val context = AndroidContextHolder.application
    WorkManager.getInstance(context).cancelUniqueWork(PhoebeDownloadWorkerName)
}

actual fun currentDeviceName(): String {
    val context = AndroidContextHolder.applicationOrNull
    val globalName = if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
    } else null
    return globalName?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android Device" }
}

actual fun currentDeviceId(): String {
    val context = AndroidContextHolder.application
    val file = File(context.filesDir, "phoebe-device-id.txt")
    if (file.exists()) {
        val id = file.readText().trim()
        if (id.isNotBlank()) return id
    }
    val newId = "android-" + java.util.UUID.randomUUID().toString()
    runCatching { file.writeText(newId) }
    return newId
}

actual fun acquireMulticastLock(): AutoCloseable? {
    val context = AndroidContextHolder.applicationOrNull ?: return null
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return null
    return runCatching {
        val lock = wifiManager.createMulticastLock("phoebe-multicast-lock")
        lock.setReferenceCounted(true)
        lock.acquire()
        AutoCloseable {
            runCatching {
                if (lock.isHeld) {
                    lock.release()
                }
            }
        }
    }.getOrNull()
}

actual fun getBroadcastAddresses(): List<String> = runCatching {
    val broadcasts = mutableListOf<String>()
    val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return broadcasts
    for (iface in interfaces) {
        if (!iface.isUp || iface.isLoopback || iface.isVirtual || iface.isPointToPoint) continue
        for (addr in iface.interfaceAddresses) {
            val bcast = addr.broadcast
            if (bcast != null) {
                val host = bcast.hostAddress
                if (!host.isNullOrBlank() && !host.contains('%')) {
                    broadcasts.add(host)
                }
            }
        }
    }
    broadcasts.distinct()
}.getOrDefault(emptyList())

actual fun localHostIpAddresses(): List<String> = runCatching {
    val addresses = mutableListOf<String>()
    val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return addresses
    for (iface in interfaces) {
        if (!iface.isUp || iface.isLoopback || iface.isVirtual || iface.isPointToPoint) continue
        for (addr in iface.inetAddresses) {
            if (addr is java.net.Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                addr.hostAddress?.takeIf { it.isNotBlank() }?.let { addresses.add(it) }
            }
        }
    }
    addresses.distinct()
}.getOrDefault(emptyList())

actual fun isDebugBuild(): Boolean =
    AndroidContextHolder.applicationOrNull?.applicationInfo?.flags
        ?.let { flags -> flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
        ?: false

internal actual fun platformLog(tag: String, message: String) {
    Log.d(tag, message)
}

private val platformNetworkJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private const val JellyfinDiscoveryPort = 7359
internal const val PhoebeDownloadWorkerName = "phoebe-download-runner"
