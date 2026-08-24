package com.phoebe.app.player

import com.phoebe.app.domain.StreamingPolicySettings
import com.phoebe.app.domain.StreamingQuality
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.isLosslessAudioSource
import com.phoebe.app.domain.keepsOriginalStreamFor
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

    @Volatile
    private var directStreamTrackId: String? = null

    fun effectiveQuality(): StreamingQuality =
        settings.effectiveQuality(networkIsConstrainedProvider())

    fun preferDirectStreamFor(trackId: String) {
        directStreamTrackId = trackId.takeIf { it.isNotBlank() }
    }

    fun prefersDirectStreamFor(trackId: String): Boolean =
        trackId.isNotBlank() && trackId == directStreamTrackId

    fun artworkQuality(trackId: String, requested: StreamingQuality): StreamingQuality =
        if (prefersDirectStreamFor(trackId)) StreamingQuality.Original else requested

    fun clearDirectStreamPreference() {
        directStreamTrackId = null
    }

    fun resolvePlaybackUri(track: Track): String {
        if (track.id == directStreamTrackId) {
            return track.localUri?.takeIf { it.isNotBlank() } ?: track.streamUrl
        }
        return track.resolvedPlaybackUri(effectiveQuality())
    }
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
    if (streamUrl.isBlank() || keepsOriginalStreamFor(quality)) return streamUrl
    val maxKbps = quality.maxAudioBitrateKbps ?: return streamUrl
    return plexBitrateLimitedMp3TranscodeUrl(maxKbps)
        ?: plexUniversalMp3TranscodeUrl(maxAudioBitrateKbps = maxKbps)
        ?: jellyfinFamilyMp3TranscodeUrl(maxAudioBitrateKbps = maxKbps)
        ?: subsonicBitrateLimitedStreamUrl(maxKbps)
        ?: streamUrl
}

internal fun Track.isLosslessAudioCodec(): Boolean = isLosslessAudioSource()

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
