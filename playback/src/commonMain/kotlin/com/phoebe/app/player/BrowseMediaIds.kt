package com.phoebe.app.player

object BrowseMediaIds {
    const val ROOT = "phoebe:root"
    const val ARTISTS = "phoebe:artists"
    const val ALBUMS = "phoebe:albums"
    const val PLAYLISTS = "phoebe:playlists"
    const val SIGN_IN = "phoebe:sign_in"
    const val RADIO = "phoebe:radio"
    const val RADIO_RECOMMENDED = "phoebe:radio:recommended"
    const val RADIO_SAVED = "phoebe:radio:saved"
    const val RADIO_SAVED_HINT = "phoebe:radio:saved:hint"

    fun artist(id: String): String = "phoebe:artist:$id"
    fun album(id: String): String = "phoebe:album:$id"
    fun playlist(id: String): String = "phoebe:playlist:$id"
    fun track(parentMediaId: String, trackId: String): String =
        "phoebe:track:${parentMediaId.length}:$parentMediaId$trackId"

    fun albumPlay(id: String): String = "phoebe:play:album:$id"
    fun playlistPlay(id: String): String = "phoebe:play:playlist:$id"
    fun playlistShuffle(id: String): String = "phoebe:shuffle:playlist:$id"

    fun radioCategory(category: String): String =
        "phoebe:radio:category:${category.length}:$category"

    fun radioStation(stationId: String): String =
        "phoebe:radio:station:${stationId.length}:$stationId"

    fun parseArtistId(mediaId: String): String? =
        mediaId.removePrefix("phoebe:artist:").takeIf { it != mediaId }

    fun parseAlbumId(mediaId: String): String? =
        mediaId.removePrefix("phoebe:album:").takeIf { it != mediaId }

    fun parsePlaylistId(mediaId: String): String? =
        mediaId.removePrefix("phoebe:playlist:").takeIf { it != mediaId }

    fun parseTrackId(mediaId: String): BrowseTrackId? {
        val payload = mediaId.removePrefix("phoebe:track:").takeIf { it != mediaId } ?: return null
        val separator = payload.indexOf(':')
        if (separator <= 0) return null
        val parentLength = payload.substring(0, separator).toIntOrNull() ?: return null
        val parentStart = separator + 1
        val trackStart = parentStart + parentLength
        if (trackStart > payload.length) return null
        return BrowseTrackId(
            parentMediaId = payload.substring(parentStart, trackStart),
            trackId = payload.substring(trackStart),
        )
    }

    fun parsePlaylistShuffleId(mediaId: String): String? =
        mediaId.removePrefix("phoebe:shuffle:playlist:").takeIf { it != mediaId }

    fun parseAlbumPlayId(mediaId: String): String? =
        mediaId.removePrefix("phoebe:play:album:").takeIf { it != mediaId }

    fun parsePlaylistPlayId(mediaId: String): String? =
        mediaId.removePrefix("phoebe:play:playlist:").takeIf { it != mediaId }

    fun parseRadioCategory(mediaId: String): String? {
        val payload = mediaId.removePrefix("phoebe:radio:category:").takeIf { it != mediaId } ?: return null
        return parseLengthPrefixed(payload)
    }

    fun parseRadioStationId(mediaId: String): String? {
        val payload = mediaId.removePrefix("phoebe:radio:station:").takeIf { it != mediaId } ?: return null
        return parseLengthPrefixed(payload)
    }

    fun isRadioBrowseId(mediaId: String): Boolean =
        mediaId == RADIO ||
            mediaId == RADIO_RECOMMENDED ||
            mediaId == RADIO_SAVED ||
            mediaId == RADIO_SAVED_HINT ||
            parseRadioCategory(mediaId) != null ||
            parseRadioStationId(mediaId) != null

    private fun parseLengthPrefixed(payload: String): String? {
        val separator = payload.indexOf(':')
        if (separator <= 0) return null
        val length = payload.substring(0, separator).toIntOrNull() ?: return null
        val start = separator + 1
        val end = start + length
        if (end > payload.length) return null
        return payload.substring(start, end)
    }
}

data class BrowseTrackId(
    val parentMediaId: String,
    val trackId: String,
)
