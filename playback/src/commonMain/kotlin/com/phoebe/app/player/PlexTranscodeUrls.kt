package com.phoebe.app.player

import com.phoebe.app.data.PlexClient
import com.phoebe.app.domain.StreamingQuality
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
internal fun Track.jellyfinFamilyMp3TranscodeUrl(maxAudioBitrateKbps: Int? = null): String? {
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
                val bitrateKbps = maxAudioBitrateKbps?.takeIf { it > 0 }
                // Jellyfin `audioBitRate` is bits/sec. `static=true` skips transcoding.
                parameters.append("static", if (bitrateKbps != null) "false" else "true")
                parameters.append("audioCodec", "mp3")
                bitrateKbps?.let { parameters.append("audioBitRate", (it * 1_000).toString()) }
                parameters.append("api_key", token)
            }
            .buildString()
    }.getOrNull()
}

internal fun jellyfinFamilyAudioItemId(encodedPath: String): String? =
    Regex("""/Audio/([^/]+)/stream(?:\.[^/]+)?""").find(encodedPath)?.groupValues?.getOrNull(1)

internal fun Track.plexUniversalMp3TranscodeUrl(maxAudioBitrateKbps: Int? = null): String? =
    buildPlexUniversalMp3TranscodeUrl(
        extraParameters = buildMap {
            maxAudioBitrateKbps?.takeIf { it > 0 }?.let { put("maxAudioBitrate", it.toString()) }
        },
    )

/**
 * Bitrate-capped Plex transcode for Android/iOS/desktop data saver.
 *
 * Plex Web's absolute `path=` plus `protocol=https` makes current PMS builds return 400 on
 * `*.plex.direct` relays. Progressive `start.mp3` wants a relative metadata path and `protocol=http`
 * even when the request itself is HTTPS. Skip `directPlay=0`; that knob also 400s on some PMS builds.
 */
internal fun Track.plexBitrateLimitedMp3TranscodeUrl(maxAudioBitrateKbps: Int): String? {
    val bitrate = maxAudioBitrateKbps.coerceAtLeast(32).toString()
    return buildPlexUniversalMp3TranscodeUrl(
        extraParameters = buildMap {
            put("maxAudioBitrate", bitrate)
            put("musicBitrate", bitrate)
            put("hasMDE", "1")
            put("fastSeek", "1")
            put("session", randomPlexTranscodeSession())
            put("offset", "0")
            putAll(plexTranscodeClientProfileParams())
        },
        includeDirectPlayFlags = false,
        includeFormatParams = false,
        transcodeProtocol = "http",
    )
}

/**
 * Plex Web uses a slimmer query than Chromecast/Flatpak. Extra transcode knobs such as
 * `directPlay=0` or `X-Plex-Client-Profile-Extra` can make current PMS builds return 400.
 */
internal fun Track.plexWebUniversalMp3TranscodeUrl(maxAudioBitrateKbps: Int = 320): String? {
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
                parameters.append("maxAudioBitrate", maxAudioBitrateKbps.coerceAtLeast(32).toString())
                parameters.append("protocol", "http")
                parameters.append("session", randomPlexTranscodeSession())
                parameters.append("offset", "0")
                parameters.append("X-Plex-Token", token)
                plexTranscodeClientProfileParams().forEach { (key, value) ->
                    parameters.append(key, value)
                }
            }
            .buildString()
    }.getOrNull()
}

private fun Track.buildPlexUniversalMp3TranscodeUrl(
    extraParameters: Map<String, String> = emptyMap(),
    includeDirectPlayFlags: Boolean = true,
    includeFormatParams: Boolean = true,
    transcodeProtocol: String? = null,
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
                parameters.append("protocol", transcodeProtocol ?: parsed.protocol.name)
                if (includeFormatParams) {
                    parameters.append("format", "mp3")
                    parameters.append("audioCodec", "mp3")
                }
                if (includeDirectPlayFlags) {
                    parameters.append("directPlay", "0")
                    parameters.append("directStream", "0")
                }
                parameters.append("X-Plex-Token", token)
                extraParameters.forEach { (key, value) -> parameters.append(key, value) }
            }
            .buildString()
    }.getOrNull()
}

/**
 * PMS universal transcode 400s when it cannot match a client profile (`platform=` empty).
 * Chrome's built-in music profile is what Plex Web uses for `start.mp3`.
 */
private fun plexTranscodeClientProfileParams(): Map<String, String> = mapOf(
    "X-Plex-Client-Identifier" to PlexClient.ClientIdentifier,
    "X-Plex-Product" to "Phoebe",
    "X-Plex-Version" to "0.1.0",
    "X-Plex-Platform" to "Chrome",
    "X-Plex-Device" to "Web",
    "X-Plex-Device-Name" to "Phoebe",
)

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
 * When [quality] is capped, prefer a bitrate-limited transcode even for playable lossy sources.
 */
internal fun Track.webPlaybackStreamUrl(
    quality: StreamingQuality = StreamingQuality.Original,
): String {
    if (streamUrl.isBlank()) return streamUrl
    if (quality != StreamingQuality.Original) {
        return qualityAwareStreamUrl(quality)
    }
    if (hasChromecastDirectPlayableCodec()) return streamUrl
    return plexWebUniversalMp3TranscodeUrl() ?: jellyfinFamilyMp3TranscodeUrl() ?: streamUrl
}

internal fun String.isPlexUniversalTranscodeUrl(): Boolean =
    contains("/music/:/transcode/universal/start.mp3", ignoreCase = true)

internal fun String.isPlexWebTranscodeUrl(): Boolean = isPlexUniversalTranscodeUrl()
