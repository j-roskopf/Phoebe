package com.phoebe.app.media

import com.phoebe.app.platform.PhoebeLog
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import kotlin.math.abs

private const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
private const val ROOT_IFACE = "org.mpris.MediaPlayer2"
private const val PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"

@DBusInterfaceName(ROOT_IFACE)
internal interface MediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName(PLAYER_IFACE)
internal interface MediaPlayer2Player : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offsetMicros: Long)
    fun SetPosition(trackId: DBusPath, positionMicros: Long)

    /**
     * org.mpris.MediaPlayer2.Player.Seeked, emitted only on a position discontinuity.
     *
     * Nested inside the interface deliberately: dbus-java derives a signal's D-Bus
     * interface name from its enclosing type, so a top-level class would be advertised
     * under the wrong interface and clients would never see it.
     */
    class Seeked(path: String, val positionMicros: Long) : DBusSignal(path, positionMicros)
}

/**
 * Linux media session over MPRIS.
 *
 * Compositors route XF86Audio* keys to the MPRIS bus name rather than delivering them
 * to the focused window, which is why a raw X11 key hook cannot work on Wayland.
 * Mirrors [MacMediaSession]'s handler-property shape.
 */
internal object MprisMediaSession : MediaPlayer2, MediaPlayer2Player, Properties {

    @Volatile var onToggle: () -> Unit = {}
    @Volatile var onPlay: () -> Unit = {}
    @Volatile var onPause: () -> Unit = {}
    @Volatile var onNext: () -> Unit = {}
    @Volatile var onPrevious: () -> Unit = {}
    @Volatile var onStop: () -> Unit = {}
    @Volatile var onSeek: (Long) -> Unit = {}
    @Volatile var onRaise: () -> Unit = {}
    @Volatile var onQuit: () -> Unit = {}

    private var connection: DBusConnection? = null

    @Volatile private var snapshot: NowPlayingSnapshot? = null

    /**
     * Reported through the Volume property. Kept separate from [NowPlayingSnapshot] so
     * the macOS session, which shares that type and has no notion of volume, does not
     * start emitting updates every time the volume changes.
     */
    @Volatile private var volume: Double = 1.0

    override fun getObjectPath(): String = OBJECT_PATH

    override fun isRemote(): Boolean = false

    /**
     * Attempts to own the MPRIS bus name. Returns false when there is no session bus to
     * connect to, which is expected on headless machines and inside sandboxes; the
     * caller falls back to the global key hook in that case.
     */
    fun connect(): Boolean {
        if (connection != null) return true
        return runCatching {
            val conn = DBusConnectionBuilder.forSessionBus().build()
            conn.exportObject(OBJECT_PATH, this)

            runCatching {
                conn.requestBusName("org.mpris.MediaPlayer2.phoebe")
            }.onFailure {
                // Another Phoebe already owns the well-known name. The spec allows a
                // per-instance name, so a second window stays controllable.
                conn.requestBusName("org.mpris.MediaPlayer2.phoebe.instance${ProcessHandle.current().pid()}")
            }

            connection = conn
            true
        }.getOrElse { e ->
            PhoebeLog.d("Phoebe") { "MPRIS unavailable, falling back to key hook: ${e.message}" }
            false
        }
    }

    fun update(newSnapshot: NowPlayingSnapshot, newVolume: Float) {
        val previous = snapshot
        snapshot = newSnapshot
        volume = newVolume.toDouble().coerceIn(0.0, 1.0)
        val conn = connection ?: return

        // Everything here runs inside the guard, including building the Variants:
        // constructing one can throw, and this is called from a flow collector, so an
        // escaping exception would surface to the user as a failed playback update.
        runCatching {
            val changed: Map<String, Variant<*>> = mapOf(
                "Metadata" to metadataVariant(newSnapshot),
                "PlaybackStatus" to Variant(MprisMetadata.playbackStatus(newSnapshot.playing)),
                "Volume" to Variant(volume),
            )
            conn.sendMessage(Properties.PropertiesChanged(OBJECT_PATH, PLAYER_IFACE, changed, emptyList()))
        }.onFailure { e ->
            PhoebeLog.d("Phoebe") { "MPRIS property signal failed: ${e.message}" }
        }

        // Seeked announces a discontinuity, not smooth progress, so only emit when the
        // position jumps within the same track.
        val jumped = previous != null &&
            previous.trackId == newSnapshot.trackId &&
            abs(newSnapshot.positionBucketMs - previous.positionBucketMs) > 2L
        if (jumped) {
            runCatching {
                conn.sendMessage(
                    MediaPlayer2Player.Seeked(
                        OBJECT_PATH,
                        MprisMetadata.positionMicros(newSnapshot.positionBucketMs),
                    ),
                )
            }
        }
    }

    fun shutdown() {
        runCatching { connection?.disconnect() }
        connection = null
        snapshot = null
    }

    // --- org.mpris.MediaPlayer2 ---

    override fun Raise() = onRaise()

    override fun Quit() = onQuit()

    // --- org.mpris.MediaPlayer2.Player ---

    override fun Next() = onNext()

    override fun Previous() = onPrevious()

    override fun Pause() = onPause()

    override fun PlayPause() = onToggle()

    override fun Stop() = onStop()

    override fun Play() = onPlay()

    override fun Seek(offsetMicros: Long) {
        val current = snapshot ?: return
        val target = MprisMetadata.positionMicros(current.positionBucketMs) + offsetMicros
        onSeek(target.coerceAtLeast(0L) / 1_000L)
    }

    override fun SetPosition(trackId: DBusPath, positionMicros: Long) {
        onSeek(positionMicros.coerceAtLeast(0L) / 1_000L)
    }

    // --- org.freedesktop.DBus.Properties ---

    /**
     * Returns the Variant itself rather than its value. Unwrapping here looks harmless
     * but breaks Metadata: dbus-java would have to re-wrap a plain Map for the reply,
     * cannot infer a signature for one, and fails with "Can't wrap class
     * java.util.LinkedHashMap in an unqualified Variant". The Variants built in
     * [GetAll] already carry their signatures, so passing them through preserves it.
     *
     * Clients differ in which they call, so this is easy to miss: playerctl reads
     * GetAll and works either way, while swayosd reads Get and silently falls back to
     * an icon with no track information.
     */
    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
        GetAll(interfaceName)[propertyName] as A

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        // Volume is the only writable property MPRIS defines on Player, and it is
        // reported accurately above but not settable: routing a write back into
        // playback would mean threading a volume callback through
        // GlobalMediaKeysEffect, whose signature is expect/actual across four
        // platforms. In practice compositors bind the volume keys to the system mixer
        // rather than to a player, so this path is rarely exercised. Accepted and
        // ignored rather than raising an error; see the PR discussion for whether it
        // is worth wiring.
    }

    override fun GetAll(interfaceName: String): MutableMap<String, Variant<*>> {
        val current = snapshot
        return when (interfaceName) {
            ROOT_IFACE -> mutableMapOf(
                "Identity" to Variant("Phoebe"),
                "DesktopEntry" to Variant("phoebe"),
                "CanRaise" to Variant(true),
                "CanQuit" to Variant(true),
                "HasTrackList" to Variant(false),
                "SupportedUriSchemes" to Variant(arrayOf<String>(), "as"),
                "SupportedMimeTypes" to Variant(arrayOf<String>(), "as"),
            )

            PLAYER_IFACE -> mutableMapOf(
                "PlaybackStatus" to Variant(MprisMetadata.playbackStatus(current?.playing == true)),
                "Metadata" to metadataVariant(current),
                "Position" to Variant(MprisMetadata.positionMicros(current?.positionBucketMs ?: 0L)),
                "Volume" to Variant(volume),
                "Rate" to Variant(1.0),
                "MinimumRate" to Variant(1.0),
                "MaximumRate" to Variant(1.0),
                "CanGoNext" to Variant(true),
                "CanGoPrevious" to Variant(true),
                "CanPlay" to Variant(true),
                "CanPause" to Variant(true),
                "CanSeek" to Variant(true),
                "CanControl" to Variant(true),
            )

            else -> mutableMapOf()
        }
    }
}

/**
 * A Map carries no signature dbus-java can infer, so it has to be spelled out. An
 * unqualified `Variant(map)` throws "Can't wrap class java.util.LinkedHashMap in an
 * unqualified Variant".
 */
private fun metadataVariant(snapshot: NowPlayingSnapshot?): Variant<*> =
    Variant((snapshot?.let { MprisMetadata.metadata(it) } ?: emptyMap()).toVariantMap(), "a{sv}")

/**
 * Wraps metadata values as Variants, spelling out the signature wherever dbus-java
 * cannot infer one. Plain strings, longs and booleans infer fine; collections do not,
 * and mpris:trackid is an object path rather than a string per the MPRIS spec.
 */
private fun Map<String, Any>.toVariantMap(): MutableMap<String, Variant<*>> =
    entries.associateTo(mutableMapOf()) { (key, value) ->
        key to when {
            key == "mpris:trackid" -> Variant(DBusPath(value as String), "o")
            value is List<*> -> Variant(value.map(Any?::toString).toTypedArray(), "as")
            else -> Variant(value)
        }
    }
