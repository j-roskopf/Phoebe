package com.phoebe.app.player

import com.phoebe.app.data.PlexClient
import com.phoebe.app.domain.Track
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import kotlin.random.Random

/** Plex rating key for universal transcode URLs, with or without a `plex:` id prefix. */
internal fun Track.plexRatingKey(): String? {
    if (id.startsWith("plex:")) {
        val raw = id.removePrefix("plex:")
        return raw.substringBefore(':').takeIf { it.isNotBlank() }
    }
    return id.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
}

/**
 * Jellyfin and Emby expose `/Audio/{itemId}/stream`; request `/stream.mp3` when Java Sound cannot
 * decode the source container (Flatpak sandboxes, Chromecast, etc.).
 */
internal fun Track.jellyfinFamilyMp3TranscodeUrl(): String? {
    val parsed = runCatching { Url(streamUrl) }.getOrNull() ?: return null
    val token = parsed.parameters["api_key"]?.takeIf { it.isNotBlank() } ?: return null
    if (parsed.protocol.name.isBlank() || parsed.host.isBlank()) return null
    val itemId = jellyfinFamilyAudioItemId(parsed.encodedPath) ?: return null
    return runCatching {
        URLBuilder()
            .takeFrom(parsed)
            .apply {
                encodedPath = "/Audio/$itemId/stream.mp3"
                parameters.clear()
                parameters.append("static", "true")
                parameters.append("audioCodec", "mp3")
                parameters.append("api_key", token)
            }
            .buildString()
    }.getOrNull()
}

internal fun jellyfinFamilyAudioItemId(encodedPath: String): String? =
    Regex("""/Audio/([^/]+)/stream(?:\.[^/]+)?""").find(encodedPath)?.groupValues?.getOrNull(1)

internal fun Track.plexUniversalMp3TranscodeUrl(): String? =
    buildPlexUniversalMp3TranscodeUrl()

/**
 * Plex Web uses a slimmer query than Chromecast/Flatpak. Extra transcode knobs such as
 * `directPlay=0` or `X-Plex-Client-Profile-Extra` can make current PMS builds return 400.
 */
internal fun Track.plexWebUniversalMp3TranscodeUrl(): String? {
    val ratingKey = plexRatingKey() ?: return null
    val parsed = runCatching { Url(streamUrl) }.getOrNull() ?: return null
    val token = parsed.parameters["X-Plex-Token"].orEmpty()
    if (parsed.protocol.name.isBlank() || parsed.host.isBlank() || token.isBlank()) return null
    val metadataPath = URLBuilder()
        .takeFrom(parsed)
        .apply {
            encodedPath = "/library/metadata/$ratingKey"
            parameters.clear()
        }
        .buildString()
    return runCatching {
        URLBuilder()
            .takeFrom(parsed)
            .apply {
                encodedPath = "/music/:/transcode/universal/start.mp3"
                parameters.clear()
                parameters.append("path", metadataPath)
                parameters.append("mediaIndex", "0")
                parameters.append("partIndex", "0")
                parameters.append("maxAudioBitrate", "320")
                parameters.append("protocol", parsed.protocol.name)
                parameters.append("session", randomPlexTranscodeSession())
                parameters.append("offset", "0")
                parameters.append("X-Plex-Token", token)
                parameters.append("X-Plex-Client-Identifier", PlexClient.ClientIdentifier)
                parameters.append("X-Plex-Product", "Phoebe")
                parameters.append("X-Plex-Version", "0.1.0")
                parameters.append("X-Plex-Platform", "Chrome")
                parameters.append("X-Plex-Device", "Web")
                parameters.append("X-Plex-Device-Name", "Phoebe Web")
            }
            .buildString()
    }.getOrNull()
}

private fun Track.buildPlexUniversalMp3TranscodeUrl(
    extraParameters: Map<String, String> = emptyMap(),
): String? {
    val ratingKey = plexRatingKey() ?: return null
    val parsed = runCatching { Url(streamUrl) }.getOrNull() ?: return null
    val token = parsed.parameters["X-Plex-Token"].orEmpty()
    if (parsed.protocol.name.isBlank() || parsed.host.isBlank() || token.isBlank()) return null
    return runCatching {
        URLBuilder()
            .takeFrom(parsed)
            .apply {
                encodedPath = "/music/:/transcode/universal/start.mp3"
                parameters.clear()
                parameters.append("path", "/library/metadata/$ratingKey")
                parameters.append("mediaIndex", "0")
                parameters.append("partIndex", "0")
                parameters.append("protocol", parsed.protocol.name)
                parameters.append("format", "mp3")
                parameters.append("audioCodec", "mp3")
                parameters.append("directPlay", "0")
                parameters.append("directStream", "0")
                parameters.append("X-Plex-Token", token)
                extraParameters.forEach { (key, value) -> parameters.append(key, value) }
            }
            .buildString()
    }.getOrNull()
}

private fun randomPlexTranscodeSession(): String =
    buildString(16) {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        repeat(16) {
            append(chars[Random.nextInt(chars.length)])
        }
    }

internal fun Track.hasChromecastDirectPlayableCodec(): Boolean =
    when (audioCodec?.lowercase()) {
        "aac", "mp3", "mp4", "m4a" -> true
        else -> {
            val path = filepath ?: streamUrl.substringBefore('?').substringBefore('#')
            when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
                "aac", "mp3", "m4a", "mp4" -> true
                else -> false
            }
        }
    }

/**
 * Flatpak sandboxes cannot use JavaFX Media and Java Sound does not decode M4A/AAC/ALAC.
 * Reuse Plex's universal MP3 transcode endpoint (same as Chromecast) for those streams.
 */
internal fun flatpakSandboxSampledPlaybackExtension(
    audioCodec: String?,
    filepath: String?,
    streamUrl: String,
): String? {
    val codec = audioCodec?.lowercase()?.let { normalizeAudioCodecSuffix(it) }
    if (codec != null) {
        flatpakSampledPlaybackExtensionFromSuffix(codec)?.let { return it }
    }
    val path = filepath ?: streamUrl.substringBefore('?').substringBefore('#')
    return flatpakSampledPlaybackExtensionFromSuffix(
        path.substringAfterLast('.', missingDelimiterValue = ""),
    )
}

internal fun flatpakSampledPlaybackExtensionFromSuffix(extension: String): String? =
    when (extension.lowercase()) {
        "mp3", "mpeg", "mpga",
        "wav", "wave", "aif", "aiff", "flac", "ogg", "opus",
        -> extension.lowercase().let { if (it == "mpeg" || it == "mpga") "mp3" else it }
        else -> null
    }

private fun normalizeAudioCodecSuffix(codec: String): String =
    when (codec) {
        "mpeg", "mpga" -> "mp3"
        else -> codec
    }

/** MP3 transcode URL for formats Java Sound cannot decode in Flatpak (Plex, Jellyfin, Emby). */
internal fun Track.flatpakSandboxTranscodeUrl(): String? =
    plexUniversalMp3TranscodeUrl() ?: jellyfinFamilyMp3TranscodeUrl()

/**
 * Browser playback keeps MP3/AAC/M4A on the direct Plex part URL. Lossless sources can opt into
 * Plex Web's slimmer universal MP3 transcode endpoint when the browser cannot decode them safely.
 */
internal fun Track.webPlaybackStreamUrl(): String {
    if (streamUrl.isBlank()) return streamUrl
    if (hasChromecastDirectPlayableCodec()) return streamUrl
    return plexWebUniversalMp3TranscodeUrl() ?: jellyfinFamilyMp3TranscodeUrl() ?: streamUrl
}

internal fun String.isPlexWebTranscodeUrl(): Boolean =
    contains("/music/:/transcode/universal/start.mp3", ignoreCase = true)
