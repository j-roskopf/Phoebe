package com.phoebe.app.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import javax.imageio.ImageIO

private const val NOTIFICATIONS_BUS = "org.freedesktop.Notifications"
private const val NOTIFICATIONS_PATH = "/org/freedesktop/Notifications"

/**
 * Keeps the newest this many cover-art files, deleting oldest-first.
 *
 * Callers are expected to pass a thumbnail-sized URL, so entries run to tens of
 * kilobytes and this bound is a handful of megabytes. Bounding by count rather than
 * bytes is only reasonable under that assumption: passing an unsized Plex URL yields
 * multi-megabyte originals and 200 of those would be hundreds of megabytes.
 */
private const val MaxCoverArtFiles = 200

/**
 * Connect and read timeout for the artwork fetch.
 *
 * Short on purpose. The fetch is inline so that art appears on a track's first play
 * rather than only on a repeat, but a notification that arrives late is worse than one
 * without a picture, so the wait is bounded.
 */
private const val ArtworkFetchTimeoutMs = 3_000

/** Longest-edge bound for a cached cover-art thumbnail. */
private const val CoverArtMaxPixels = 256

@DBusInterfaceName(NOTIFICATIONS_BUS)
internal interface FreedesktopNotifications : DBusInterface {
    fun Notify(
        appName: String,
        replacesId: UInt32,
        appIcon: String,
        summary: String,
        body: String,
        actions: List<String>,
        hints: Map<String, Variant<*>>,
        expireTimeout: Int,
    ): UInt32
}

/**
 * Track-change notifications via org.freedesktop.Notifications.
 *
 * Linux only. macOS already surfaces now-playing through its media session and Windows
 * toasts are a different API, so both no-op rather than pretending.
 */
actual class NowPlayingNotifier actual constructor() {

    private val isLinux: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    private var connection: DBusConnection? = null
    private var notifications: FreedesktopNotifications? = null

    /** Sent as replaces_id so a new track replaces the previous popup rather than stacking. */
    private var lastNotificationId: UInt32 = UInt32(0L)

    actual suspend fun notifyNowPlaying(
        title: String,
        artist: String,
        album: String,
        artworkUrl: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isLinux || title.isBlank()) return@withContext false

        val proxy = connect() ?: return@withContext false

        val hints = buildMap<String, Variant<*>> {
            put("category", Variant("x-gnome.music"))
            // toPath().toUri() rather than File.toURI(): the latter produces the
            // single-slash form "file:/home/..." which notification daemons do not
            // parse, and the artwork silently fails to appear. java.nio yields the
            // authority form "file:///home/..." that the spec calls for.
            cachedArtFile(artworkUrl)?.let { put("image-path", Variant(it.toPath().toUri().toString())) }
        }

        runCatching {
            lastNotificationId = proxy.Notify(
                "Phoebe",
                lastNotificationId,
                "phoebe",
                title,
                listOf(artist, album).filter { it.isNotBlank() }.joinToString("\n"),
                emptyList(),
                hints,
                -1,
            )
            true
        }.getOrElse { e ->
            PhoebeLog.d("Phoebe") { "Now playing notification failed: ${e.message}" }
            false
        }
    }

    private fun connect(): FreedesktopNotifications? {
        notifications?.let { return it }
        return runCatching {
            val conn = DBusConnectionBuilder.forSessionBus().build()
            // Only the four-argument overload takes a Class. The trailing flag is
            // autostart, letting D-Bus activate the notification daemon if it is not
            // already running.
            val proxy = conn.getRemoteObject(
                NOTIFICATIONS_BUS,
                NOTIFICATIONS_PATH,
                FreedesktopNotifications::class.java,
                true,
            )
            connection = conn
            notifications = proxy
            proxy
        }.getOrElse { e ->
            PhoebeLog.d("Phoebe") { "No notification service available: ${e.message}" }
            null
        }
    }

    /**
     * A local file for the artwork, fetched once if not already cached. Returns null on
     * any failure: a notification without art beats no notification at all.
     */
    private fun cachedArtFile(artworkUrl: String): File? {
        if (artworkUrl.isBlank()) return null
        return runCatching {
            if (artworkUrl.startsWith("file:")) return File(URI(artworkUrl))

            val target = storageRoot.resolve(coverArtCachePath(artworkUrl))
            if (target.exists() && target.length() > 0L) return target

            target.parentFile?.mkdirs()
            // Timeouts are essential rather than tidy: this runs on the shared
            // Dispatchers.IO, and URL.openStream() has no timeout by default. A Plex
            // origin that accepts a connection and then stalls -- which happens when a
            // server advertises an unroutable LAN address -- would block an IO thread
            // forever, and enough of them would starve the pool the rest of the app
            // shares.
            val connection = URI(artworkUrl).toURL().openConnection().apply {
                connectTimeout = ArtworkFetchTimeoutMs
                readTimeout = ArtworkFetchTimeoutMs
            }
            val source = connection.getInputStream().use { ImageIO.read(it) } ?: return null
            source.writeThumbnailTo(target)
            pruneCoverArtCache(target.parentFile)
            target.takeIf { it.length() > 0L }
        }.getOrNull()
    }
}

/**
 * Writes a downscaled JPEG copy, at most [CoverArtMaxPixels] on its longest edge.
 *
 * Servers cannot be relied on to honour a requested size — Plex ignores width and
 * height on its thumb endpoint and returns the original, routinely 2400x2400 and
 * several megabytes — so the bound is enforced here instead. Without this the cache
 * holds full-resolution album art to draw it at roughly 64 pixels.
 */
private fun BufferedImage.writeThumbnailTo(target: File) {
    val longestEdge = maxOf(width, height)
    val scale = if (longestEdge > CoverArtMaxPixels) CoverArtMaxPixels.toDouble() / longestEdge else 1.0
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)

    // TYPE_INT_RGB rather than ARGB: JPEG cannot store alpha, and encoding an
    // alpha-bearing raster to JPEG produces colour-shifted output.
    val thumbnail = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
    thumbnail.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        drawImage(this@writeThumbnailTo, 0, 0, targetWidth, targetHeight, null)
        dispose()
    }
    ImageIO.write(thumbnail, "jpg", target)
}

private fun pruneCoverArtCache(dir: File?) {
    val files = dir?.listFiles()?.takeIf { it.size > MaxCoverArtFiles } ?: return
    files.sortedBy { it.lastModified() }
        .take(files.size - MaxCoverArtFiles)
        .forEach { runCatching { it.delete() } }
}
