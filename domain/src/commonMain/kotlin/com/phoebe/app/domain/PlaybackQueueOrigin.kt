package com.phoebe.app.domain

data class UpNextDividerMarker(
    val label: String,
    val beforeQueueIndex: Int,
)

sealed interface PlaybackQueueOrigin {
    val title: String
    val providerType: MediaProviderType?
    val seedTrackIds: List<String>

    data class Album(
        val id: String,
        override val title: String,
        val artist: String,
        override val providerType: MediaProviderType? = id.providerTypeFromCatalogId(),
        override val seedTrackIds: List<String> = emptyList(),
    ) : PlaybackQueueOrigin

    data class Artist(
        val id: String,
        override val title: String,
        override val providerType: MediaProviderType? = id.providerTypeFromCatalogId(),
        override val seedTrackIds: List<String> = emptyList(),
    ) : PlaybackQueueOrigin

    data class Playlist(
        val id: String,
        override val title: String,
        override val providerType: MediaProviderType? = id.providerTypeFromCatalogId(),
        override val seedTrackIds: List<String> = emptyList(),
    ) : PlaybackQueueOrigin

    data class Radio(
        val id: String,
        override val title: String,
        val key: String,
        override val providerType: MediaProviderType? = id.providerTypeFromCatalogId(),
        override val seedTrackIds: List<String> = emptyList(),
    ) : PlaybackQueueOrigin

    data class Mix(
        val id: String,
        override val title: String,
        override val providerType: MediaProviderType? = null,
        override val seedTrackIds: List<String> = emptyList(),
    ) : PlaybackQueueOrigin

    data class TrackList(
        override val title: String = "Current queue",
        override val providerType: MediaProviderType? = null,
        override val seedTrackIds: List<String> = emptyList(),
    ) : PlaybackQueueOrigin
}

fun String.providerTypeFromCatalogId(): MediaProviderType? {
    val prefix = substringBefore(':', missingDelimiterValue = "")
    return MediaProviderType.entries.firstOrNull { it.catalogPrefix == prefix }
}
