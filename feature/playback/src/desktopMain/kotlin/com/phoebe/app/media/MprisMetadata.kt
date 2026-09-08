package com.phoebe.app.media

/**
 * Pure mapping from [NowPlayingSnapshot] to MPRIS metadata values.
 *
 * Deliberately free of dbus-java types so it can be unit tested without a session bus;
 * [MprisMediaSession] wraps these values in Variants at the boundary.
 */
internal object MprisMetadata {
    private const val TRACK_PATH_PREFIX = "/com/phoebe/app/track/"

    /** MPRIS PlaybackStatus. The spec defines exactly "Playing", "Paused", "Stopped". */
    fun playbackStatus(playing: Boolean): String = if (playing) "Playing" else "Paused"

    /** mpris:length is microseconds; [durationMs] is milliseconds. */
    fun lengthMicros(durationMs: Long): Long = durationMs * 1_000L

    /** Position is microseconds; [positionBucketMs] is seconds despite its name. */
    fun positionMicros(positionBucketMs: Long): Long = positionBucketMs * 1_000_000L

    /**
     * D-Bus object paths accept only [A-Za-z0-9_] between slashes, so provider ids such
     * as "plex://library/metadata/1234" cannot be used verbatim.
     */
    fun trackIdPath(trackId: String): String {
        if (trackId.isBlank()) return "${TRACK_PATH_PREFIX}NoTrack"
        val sanitized = trackId.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return "$TRACK_PATH_PREFIX$sanitized"
    }

    fun metadata(snapshot: NowPlayingSnapshot): Map<String, Any> = buildMap {
        put("mpris:trackid", trackIdPath(snapshot.trackId))
        put("xesam:title", snapshot.title)
        if (snapshot.durationMs > 0L) put("mpris:length", lengthMicros(snapshot.durationMs))
        if (snapshot.artworkUrl.isNotBlank()) put("mpris:artUrl", snapshot.artworkUrl)
        if (snapshot.artist.isNotBlank()) put("xesam:artist", listOf(snapshot.artist))
        if (snapshot.album.isNotBlank()) put("xesam:album", snapshot.album)
    }
}
