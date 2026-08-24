package com.phoebe.app.player

import com.phoebe.app.domain.StreamingPolicySettings
import com.phoebe.app.domain.StreamingQuality
import com.phoebe.app.domain.Track
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import kotlin.concurrent.Volatile

/**
 * Process-wide streaming quality policy used when building playback URIs.
 * [AppState] keeps [settings] in sync with user preferences.
 */
object StreamingPlaybackPolicyHolder {
    @Volatile
    var settings: StreamingPolicySettings = StreamingPolicySettings()

    @Volatile
    var networkIsConstrainedProvider: () -> Boolean = { false }

    fun effectiveQuality(): StreamingQuality =
        settings.effectiveQuality(networkIsConstrainedProvider())

    fun resolvePlaybackUri(track: Track): String =
        track.resolvedPlaybackUri(effectiveQuality())
}

fun Track.resolvedPlaybackUri(
    quality: StreamingQuality,
    policy: StreamingPolicySettings? = null,
    networkConstrained: Boolean = false,
): String {
    localUri?.takeIf { it.isNotBlank() }?.let { return it }
    if (streamUrl.isBlank()) return streamUrl
    val effective = policy?.effectiveQuality(networkConstrained) ?: quality
    return qualityAwareStreamUrl(effective)
}

fun Track.qualityAwareStreamUrl(quality: StreamingQuality): String {
    if (streamUrl.isBlank() || quality == StreamingQuality.Original) return streamUrl
    val maxKbps = quality.maxAudioBitrateKbps ?: return streamUrl
    if (alreadyWithinBitrateBudget(maxKbps)) return streamUrl
    return plexWebUniversalMp3TranscodeUrl(maxAudioBitrateKbps = maxKbps)
        ?: plexUniversalMp3TranscodeUrl(maxAudioBitrateKbps = maxKbps)
        ?: jellyfinFamilyMp3TranscodeUrl(maxAudioBitrateKbps = maxKbps)
        ?: subsonicBitrateLimitedStreamUrl(maxKbps)
        ?: streamUrl
}

internal fun Track.alreadyWithinBitrateBudget(maxKbps: Int): Boolean {
    if (isLosslessAudioCodec()) return false
    val bitrate = bitrateKbps
    if (bitrate != null && bitrate > 0) return bitrate <= maxKbps
    // Unknown lossy bitrate: keep the direct stream for High, but try to transcode for Data saver.
    return maxKbps >= StreamingQuality.High.maxAudioBitrateKbps!!
}

internal fun Track.isLosslessAudioCodec(): Boolean {
    val codec = audioCodec?.lowercase()
    if (codec != null) {
        when (codec) {
            "flac", "alac", "wav", "aiff", "aif", "pcm", "dsd" -> return true
        }
    }
    val path = filepath ?: streamUrl.substringBefore('?').substringBefore('#')
    return when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "flac", "alac", "wav", "aiff", "aif" -> true
        else -> false
    }
}

internal fun Track.subsonicBitrateLimitedStreamUrl(maxKbps: Int): String? {
    val parsed = runCatching { Url(streamUrl) }.getOrNull() ?: return null
    if (!parsed.encodedPath.contains("/rest/stream", ignoreCase = true)) return null
    if (parsed.parameters["u"].isNullOrBlank() || parsed.parameters["id"].isNullOrBlank()) return null
    return runCatching {
        URLBuilder()
            .takeFrom(parsed)
            .apply {
                parameters.remove("maxBitRate")
                parameters.remove("format")
                parameters.append("maxBitRate", maxKbps.toString())
                parameters.append("format", "mp3")
            }
            .buildString()
    }.getOrNull()
}
