package com.phoebe.app.player

import com.phoebe.app.domain.Track
import java.io.File

/**
 * Flatpak sandboxes break JavaFX Media ("Could not create player") while Java Sound +
 * SPI decoders still work through the PulseAudio socket.
 */
internal object DesktopSandboxPlayback {
    internal var flatpakSandboxOverride: (() -> Boolean)? = null

    fun isFlatpakSandbox(): Boolean =
        flatpakSandboxOverride?.invoke() ?: File("/.flatpak-info").exists()

    fun sampledPlaybackExtensionFromSuffix(extension: String): String? {
        if (isFlatpakSandbox()) {
            return when (extension.lowercase()) {
                "mp3", "mpeg", "mpga",
                "wav", "wave", "aif", "aiff", "flac", "ogg", "opus",
                -> extension.lowercase().let { if (it == "mpeg" || it == "mpga") "mp3" else it }
                else -> DesktopPlaybackStartupPolicy.sampledPlaybackExtensionFromSuffix(extension)
            }
        }
        return DesktopPlaybackStartupPolicy.sampledPlaybackExtensionFromSuffix(extension)
    }

    fun streamingSampledExtensionFromSuffix(extension: String): String? {
        if (isFlatpakSandbox()) {
            return when (extension.lowercase()) {
                "mp3", "mpeg", "mpga" -> "mp3"
                else -> DesktopPlaybackStartupPolicy.streamingSampledExtensionFromSuffix(extension)
            }
        }
        return DesktopPlaybackStartupPolicy.streamingSampledExtensionFromSuffix(extension)
    }

    /**
     * Prefer progressive Java Sound decoding for sampled-friendly remote streams. MP3 is intentionally
     * limited to Flatpak where JavaFX media is unavailable; the Java Sound MP3 SPI can produce static.
     */
    fun shouldStreamRemoteSampledPlayback(uri: String): Boolean {
        return DesktopPlaybackStartupPolicy.shouldStreamRemoteSampledPlayback(
            uri = uri,
            preferredStreamingExtension = null,
            isFlatpakSandbox = isFlatpakSandbox(),
        )
    }

    fun bufferedRemotePlaybackUri(activeUri: String, downloadUri: String?): String {
        val download = downloadUri?.takeIf { it.isNotBlank() && DesktopPlaybackStartupPolicy.isRemoteUri(it) }
            ?: return activeUri
        if (isFlatpakSandbox() && download != activeUri) {
            // Flatpak may rewrite AAC/M4A streams to MP3 transcode URLs while downloadUrl still
            // points at the direct container, which Java Sound cannot decode in the sandbox.
            return activeUri
        }
        return download
    }

    fun shouldEagerlyBufferRemotePlayback(uri: String, preferredSampledExtension: String?): Boolean {
        if (!DesktopPlaybackStartupPolicy.isRemoteUri(uri)) return false
        if (isFlatpakSandbox()) {
            val extension = preferredSampledExtension
                ?: DesktopPlaybackStartupPolicy.sampledPlaybackExtensionFromUri(uri)
                ?: sampledPlaybackExtensionFromUri(uri)
            return extension != null
        }
        return DesktopPlaybackStartupPolicy.shouldEagerlyBufferRemotePlayback(uri, preferredSampledExtension)
    }

    private fun sampledPlaybackExtensionFromUri(uri: String): String? {
        val path = runCatching { java.net.URI(uri).path }.getOrNull()
            ?: uri.substringBefore('?').substringBefore('#')
        return sampledPlaybackExtensionFromSuffix(path.substringAfterLast('.', missingDelimiterValue = ""))
    }

    fun playbackStreamUrlForTrack(track: Track): String {
        if (track.streamUrl.isBlank()) return track.streamUrl
        if (!isFlatpakSandbox()) {
            return javaFxFriendlyStreamUrlForTrack(track) ?: track.streamUrl
        }
        if (flatpakSandboxSampledPlaybackExtension(track.audioCodec, track.filepath, track.streamUrl) != null) {
            return track.streamUrl
        }
        return track.flatpakSandboxTranscodeUrl() ?: track.streamUrl
    }

    private fun javaFxFriendlyStreamUrlForTrack(track: Track): String? {
        if (!DesktopPlaybackStartupPolicy.isRemoteUri(track.streamUrl)) return null
        if (DesktopPlaybackStartupPolicy.javaFxPlaybackExtensionFromUri(track.streamUrl) != null) return null
        val extension = DesktopPlaybackStartupPolicy.javaFxPlaybackExtensionFromMetadata(
            audioCodec = track.audioCodec,
            filepath = track.filepath,
            uri = track.streamUrl,
        )
        return when (extension) {
            "mp3" -> track.jellyfinFamilyMp3TranscodeUrl()
            else -> null
        }
    }
}
