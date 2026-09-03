package com.phoebe.app.player

import com.phoebe.app.domain.Track
import io.ktor.http.Url

data class CastMediaDescriptor(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val streamUrl: String,
    val castUrl: String,
    val contentType: String,
    val downloadUrl: String,
    val thumbUrl: String?,
    val filepath: String?,
    val audioCodec: String?,
    /** Radio and other endless streams: the receiver must not be told to buffer a fixed duration. */
    val isLiveStream: Boolean = false,
) {
    val transcodesOriginal: Boolean get() = castUrl != streamUrl
}

object CastMediaCustomDataKeys {
    const val TrackId = "phoebeTrackId"
    const val Title = "title"
    const val Artist = "artist"
    const val Album = "album"
    const val DurationMs = "durationMs"
    const val StreamUrl = "streamUrl"
    const val CastUrl = "castUrl"
    const val DownloadUrl = "downloadUrl"
    const val ThumbUrl = "thumbUrl"
    const val Filepath = "filepath"
    const val AudioCodec = "audioCodec"
}

fun Track.toCastMediaDescriptor(): CastMediaDescriptor {
    // Plex queues intentionally retain host-free paths so a network change cannot leave stale
    // origins behind. Cast receivers need an absolute URL, so bind the path only for this
    // request using the same live origin and token as local playback.
    val boundTrack = boundToLivePlaybackOrigin()
    val castUrl = boundTrack.chromecastMediaUrl()
    return CastMediaDescriptor(
        trackId = id,
        title = title.ifBlank { "Chromecast audio" },
        artist = artist,
        album = album,
        durationMs = durationMs,
        streamUrl = boundTrack.streamUrl,
        castUrl = castUrl,
        contentType = boundTrack.chromecastContentType(castUrl),
        downloadUrl = boundTrack.downloadUrl,
        thumbUrl = thumbUrl,
        filepath = filepath,
        audioCodec = audioCodec,
        isLiveStream = isLiveCastStream(castUrl),
    )
}

/** Radio stations have no duration to seek within, and HLS playlists are live by construction. */
private fun Track.isLiveCastStream(castUrl: String): Boolean =
    id.startsWith("radio:") || castUrl.isHlsPlaylistUrl()

private fun String.isHlsPlaylistUrl(): Boolean =
    substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true) ||
        substringBefore('?').substringBefore('#').endsWith(".m3u", ignoreCase = true)

fun castTrackFromMediaFields(
    trackId: String?,
    title: String?,
    artist: String?,
    album: String?,
    durationMs: Long,
    streamUrl: String?,
    castUrl: String?,
    downloadUrl: String?,
    thumbUrl: String?,
    filepath: String?,
    audioCodec: String?,
): Track {
    val resolvedStreamUrl = streamUrl?.takeIf { it.isNotBlank() }
        ?: castUrl?.takeIf { it.isNotBlank() }
        ?: ""
    return Track(
        id = trackId?.takeIf { it.isNotBlank() } ?: "cast:${resolvedStreamUrl.hashCode()}",
        title = title?.takeIf { it.isNotBlank() } ?: "Chromecast audio",
        artist = artist.orEmpty(),
        album = album.orEmpty(),
        durationMs = durationMs.coerceAtLeast(0L),
        streamUrl = resolvedStreamUrl,
        downloadUrl = downloadUrl.orEmpty(),
        thumbUrl = thumbUrl?.takeIf { it.isNotBlank() },
        filepath = filepath?.takeIf { it.isNotBlank() },
        audioCodec = audioCodec?.takeIf { it.isNotBlank() },
    )
}

fun Track.matchesCastMedia(remoteTrack: Track, remoteCastUrl: String? = null): Boolean {
    if (id.isNotBlank() && id == remoteTrack.id) return true
    if (streamUrl.isNotBlank() && streamUrl == remoteTrack.streamUrl) return true
    val descriptor = toCastMediaDescriptor()
    if (descriptor.castUrl.isNotBlank() && descriptor.castUrl == remoteTrack.streamUrl) return true
    val castUrl = remoteCastUrl?.takeIf { it.isNotBlank() } ?: return false
    return streamUrl == castUrl || descriptor.castUrl == castUrl
}

private fun Track.chromecastMediaUrl(): String {
    if (hasChromecastDirectPlayableCodec()) return streamUrl
    return plexUniversalMp3TranscodeUrl() ?: streamUrl
}

private fun Track.chromecastContentType(mediaUrl: String): String =
    if (mediaUrl != streamUrl) {
        "audio/mpeg"
    } else {
        chromecastDirectContentType()
    }

private fun Track.chromecastDirectContentType(): String =
    when (audioCodec?.lowercase()) {
        "aac" -> "audio/aac"
        "mp3" -> "audio/mpeg"
        "alac", "m4a", "mp4" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "opus", "vorbis" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> {
            val path = filepath ?: streamUrl.substringBefore('?').substringBefore('#')
            when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
                "aac" -> "audio/aac"
                "m4a", "mp4" -> "audio/mp4"
                "flac" -> "audio/flac"
                "ogg", "oga", "opus" -> "audio/ogg"
                "wav" -> "audio/wav"
                // An HLS playlist announced as audio/mpeg is loaded as a single MP3 and fails.
                "m3u8", "m3u" -> "application/x-mpegurl"
                else -> "audio/mpeg"
            }
        }
    }
