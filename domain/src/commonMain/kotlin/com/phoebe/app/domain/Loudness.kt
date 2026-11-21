package com.phoebe.app.domain

import kotlinx.serialization.Serializable
import kotlin.math.pow

/** Which loudness value playback uses to level tracks. */
@Serializable
enum class NormalizationMode(val label: String) {
    Off("Off"),
    Track("Track"),
    Album("Album"),

    /** Album gain when the queue is playing through an album in order, track gain otherwise. */
    Auto("Auto"),
    ;

    companion object {
        val Options: List<NormalizationMode> = entries
    }
}

/** ReplayGain-style loudness values: gains in dB, peaks as linear amplitude (1.0 = full scale). */
data class ReplayGainInfo(
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
) {
    val isEmpty: Boolean
        get() = trackGainDb == null && trackPeak == null && albumGainDb == null && albumPeak == null

    companion object {
        /**
         * Reads loudness from raw tag key/value pairs (keys are matched case-insensitively).
         *
         * Supports `REPLAYGAIN_TRACK_GAIN` / `_PEAK` / `REPLAYGAIN_ALBUM_GAIN` / `_PEAK` and the Opus
         * `R128_TRACK_GAIN` / `R128_ALBUM_GAIN` tags. R128 values are Q7.8 fixed-point dB relative to
         * -23 LUFS, so they are shifted +5 dB onto ReplayGain's -18 LUFS reference.
         */
        fun fromTags(tags: Map<String, String>): ReplayGainInfo? {
            if (tags.isEmpty()) return null
            val byKey = tags.entries.associate { (key, value) -> key.trim().uppercase() to value }
            val info = ReplayGainInfo(
                trackGainDb = byKey["REPLAYGAIN_TRACK_GAIN"]?.let(::parseGainDb)
                    ?: byKey["R128_TRACK_GAIN"]?.let(::parseR128GainDb),
                trackPeak = byKey["REPLAYGAIN_TRACK_PEAK"]?.let(::parsePeak),
                albumGainDb = byKey["REPLAYGAIN_ALBUM_GAIN"]?.let(::parseGainDb)
                    ?: byKey["R128_ALBUM_GAIN"]?.let(::parseR128GainDb),
                albumPeak = byKey["REPLAYGAIN_ALBUM_PEAK"]?.let(::parsePeak),
            )
            return info.takeUnless { it.isEmpty }
        }

        /** Parses values such as `-6.54 dB`, `+1.2dB` or `-6,54 dB`. */
        fun parseGainDb(raw: String): Float? {
            val cleaned = raw.trim()
                .removeSuffix("dB").removeSuffix("db").removeSuffix("DB")
                .trim()
                .replace(',', '.')
                .removePrefix("+")
            return cleaned.toFloatOrNull()?.takeIf { it.isFinite() && it in PlausibleGainRangeDb }
        }

        fun parsePeak(raw: String): Float? =
            raw.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it > 0f && it <= MaxPlausiblePeak }

        private fun parseR128GainDb(raw: String): Float? {
            val q78 = raw.trim().toIntOrNull() ?: return null
            return (q78 / 256f + R128ToReplayGainOffsetDb).takeIf { it in PlausibleGainRangeDb }
        }

        private val PlausibleGainRangeDb = -60f..60f
        private const val MaxPlausiblePeak = 10f
        private const val R128ToReplayGainOffsetDb = 5f
    }
}

val Track.replayGain: ReplayGainInfo
    get() = ReplayGainInfo(
        trackGainDb = replayGainTrackGainDb,
        trackPeak = replayGainTrackPeak,
        albumGainDb = replayGainAlbumGainDb,
        albumPeak = replayGainAlbumPeak,
    )

fun Track.withReplayGain(info: ReplayGainInfo?): Track =
    if (info == null) {
        this
    } else {
        copy(
            replayGainTrackGainDb = info.trackGainDb,
            replayGainTrackPeak = info.trackPeak,
            replayGainAlbumGainDb = info.albumGainDb,
            replayGainAlbumPeak = info.albumPeak,
        )
    }

object LoudnessNormalization {
    const val MinPreampDb = -15f
    const val MaxPreampDb = 15f
    const val MinFallbackGainDb = -15f
    const val MaxFallbackGainDb = 15f
    const val GainStepDb = 0.5f

    /**
     * Linear gain multiplier for [track]. Returns 1 when [settings] has normalization off.
     *
     * [useAlbumGain] selects album gain (falling back to track gain when the album value is
     * missing, and vice versa). Tracks with no loudness data get the fallback gain instead of the
     * pre-amp. With clipping prevention on, the result is capped so `peak * gain <= 1`.
     */
    fun linearGain(track: Track?, settings: AudioProcessingSettings, useAlbumGain: Boolean): Float {
        if (track == null || settings.normalizationMode == NormalizationMode.Off) return 1f
        val info = track.replayGain
        val (gainDb, peak) = if (useAlbumGain) {
            when {
                info.albumGainDb != null -> info.albumGainDb to (info.albumPeak ?: info.trackPeak)
                else -> info.trackGainDb to info.trackPeak
            }
        } else {
            when {
                info.trackGainDb != null -> info.trackGainDb to (info.trackPeak ?: info.albumPeak)
                else -> info.albumGainDb to info.albumPeak
            }
        }
        val appliedDb = if (gainDb != null) {
            gainDb + settings.normalizationPreampDb
        } else {
            settings.normalizationFallbackGainDb
        }
        var linear = dbToLinear(appliedDb)
        if (settings.normalizationPreventClipping && gainDb != null && peak != null && peak > 0f) {
            linear = linear.coerceAtMost(1f / peak)
        }
        return linear.takeIf { it.isFinite() && it >= 0f } ?: 1f
    }

    /**
     * Whether [index] in [queue] should use album gain under [mode]. Auto picks album gain when a
     * neighbouring queue entry is from the same album, i.e. the album is being played through.
     */
    fun useAlbumGain(mode: NormalizationMode, queue: List<Track>, index: Int): Boolean =
        when (mode) {
            NormalizationMode.Off, NormalizationMode.Track -> false
            NormalizationMode.Album -> true
            NormalizationMode.Auto -> {
                val current = queue.getOrNull(index)
                current != null && (
                    queue.getOrNull(index - 1)?.isSameAlbumAs(current) == true ||
                        queue.getOrNull(index + 1)?.isSameAlbumAs(current) == true
                    )
            }
        }

    fun dbToLinear(db: Float): Float = 10.0.pow(db.toDouble() / 20.0).toFloat()

    private fun Track.isSameAlbumAs(other: Track): Boolean {
        val albumId = parentAlbumId
        val otherAlbumId = other.parentAlbumId
        if (!albumId.isNullOrBlank() && !otherAlbumId.isNullOrBlank()) return albumId == otherAlbumId
        if (album.isBlank() || !album.equals(other.album, ignoreCase = true)) return false
        val artist = albumArtist ?: artist
        val otherArtist = other.albumArtist ?: other.artist
        return artist.equals(otherArtist, ignoreCase = true)
    }
}
