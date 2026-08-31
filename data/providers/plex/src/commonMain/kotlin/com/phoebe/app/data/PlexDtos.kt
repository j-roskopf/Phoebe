package com.phoebe.app.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class PlexPinResponse(
    val id: Long,
    val code: String,
    @SerialName("authToken") val authToken: String? = null,
)

@Serializable
data class PlexUserResponse(
    val username: String? = null,
    @SerialName("authToken") val authToken: String? = null,
)

@Serializable
data class PlexDeviceDto(
    val name: String,
    @SerialName("clientIdentifier") val clientIdentifier: String,
    val owned: Boolean = false,
    val provides: String = "",
    @SerialName("accessToken") val accessToken: String? = null,
    @SerialName("httpsRequired") val httpsRequired: Boolean = false,
    val connections: List<PlexConnectionDto> = emptyList(),
)

@Serializable
data class PlexConnectionDto(
    val uri: String,
    val local: Boolean = false,
    /** True when plex.tv marks this URI as Plex Relay (last-resort remote hop). */
    val relay: Boolean = false,
    val protocol: String? = null,
    val address: String? = null,
    val port: Int? = null,
)

@Serializable
data class PlexMediaContainerResponse(
    @SerialName("MediaContainer") val mediaContainer: PlexMediaContainer = PlexMediaContainer(),
)

@Serializable
data class PlexMediaContainer(
    val size: Int = 0,
    val totalSize: Int? = null,
    val offset: Int? = null,
    val machineIdentifier: String? = null,
    val leafCountAdded: Int? = null,
    val leafCountRequested: Int? = null,
    @SerialName("playQueueID") val playQueueId: Long? = null,
    @SerialName("playQueueSelectedItemID") val playQueueSelectedItemId: Long? = null,
    @SerialName("playQueueTotalCount") val playQueueTotalCount: Int? = null,
    @SerialName("Hub") val hubs: List<PlexHubDto> = emptyList(),
    @SerialName("Stations") val stations: List<PlexStationDto> = emptyList(),
    @SerialName("Directory") val directories: List<PlexDirectoryDto> = emptyList(),
    @SerialName("Metadata") val metadata: List<PlexMetadataDto> = emptyList(),
)

@Serializable
data class PlexHubDto(
    val title: String? = null,
    val type: String? = null,
    val context: String? = null,
    val hubIdentifier: String? = null,
    val key: String? = null,
    @SerialName("Directory") val directories: List<PlexStationDto> = emptyList(),
    @SerialName("Metadata") val metadata: List<PlexStationDto> = emptyList(),
)

@Serializable
data class PlexDirectoryDto(
    val key: String,
    val fastKey: String? = null,
    val ratingKey: String? = null,
    val title: String,
    val type: String? = null,
    val thumb: String? = null,
    val leafCount: Int? = null,
    val addedAt: Long? = null,
    @SerialName("parentTitle") val parentTitle: String? = null,
    val year: Int? = null,
    @SerialName("originallyAvailableAt") val originallyAvailableAt: String? = null,
    val userRating: Double? = null,
    @SerialName("Genre") val genreTags: List<PlexGenreTagDto>? = null,
    @SerialName("Mood") val moodTags: List<PlexGenreTagDto>? = null,
    @SerialName("Style") val styleTags: List<PlexGenreTagDto>? = null,
    @SerialName("Collection") val collectionTags: List<PlexGenreTagDto>? = null,
    @SerialName("Stations") val stations: List<PlexStationDto> = emptyList(),
    val summary: String? = null,
    val studio: String? = null,
)

@Serializable
data class PlexGenreTagDto(
    val tag: String? = null,
)

@Serializable
data class PlexUserStateDto(
    val viewCount: Long? = null,
    val lastViewedAt: Long? = null,
    val viewedAt: Long? = null,
)

@Serializable
data class PlexMetadataDto(
    @Serializable(with = FlexibleRatingKeySerializer::class)
    val ratingKey: String = "",
    val historyKey: String? = null,
    @SerialName("playlistItemID") val playlistItemId: Long? = null,
    @SerialName("playQueueItemID") val playQueueItemId: Long? = null,
    val key: String? = null,
    val title: String,
    val type: String? = null,
    val viewedAt: Long? = null,
    val lastViewedAt: Long? = null,
    val viewCount: Long? = null,
    @SerialName("UserState") val userState: PlexUserStateDto? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    val librarySectionID: String? = null,
    @SerialName("parentRatingKey") val parentRatingKey: String? = null,
    @SerialName("grandparentRatingKey") val grandparentRatingKey: String? = null,
    @SerialName("parentTitle") val parentTitle: String? = null,
    @SerialName("grandparentTitle") val grandparentTitle: String? = null,
    @SerialName("originalTitle") val originalTitle: String? = null,
    @SerialName("parentThumb") val parentThumb: String? = null,
    @SerialName("grandparentThumb") val grandparentThumb: String? = null,
    val year: Int? = null,
    @SerialName("originallyAvailableAt") val originallyAvailableAt: String? = null,
    @SerialName("parentYear") val parentYear: Int? = null,
    val duration: Long? = null,
    val leafCount: Int? = null,
    val thumb: String? = null,
    val composite: String? = null,
    val addedAt: Long? = null,
    val updatedAt: Long? = null,
    val userRating: Double? = null,
    @SerialName("Genre") val genreTags: List<PlexGenreTagDto>? = null,
    @SerialName("Mood") val moodTags: List<PlexGenreTagDto>? = null,
    @SerialName("Style") val styleTags: List<PlexGenreTagDto>? = null,
    @SerialName("Collection") val collectionTags: List<PlexGenreTagDto>? = null,
    @SerialName("Stations") val stations: List<PlexStationDto> = emptyList(),
    @SerialName("Media") val media: List<PlexMediaDto> = emptyList(),
    val summary: String? = null,
    val studio: String? = null,
)

@Serializable
data class PlexStationDto(
    val ratingKey: String = "",
    val key: String? = null,
    val title: String,
    val type: String? = null,
    val summary: String? = null,
    val thumb: String? = null,
    val leafCount: Int? = null,
)

@Serializable
data class PlexMediaDto(
    /** Plex often reports bitrate in kbps for audio; large values may be bits/sec. */
    val bitrate: Int? = null,
    @SerialName("audioCodec") val audioCodec: String? = null,
    @SerialName("Part") val parts: List<PlexPartDto> = emptyList(),
)

@Serializable
data class PlexPartDto(
    val key: String,
    val file: String? = null,
    val size: Long? = null,
)

@OptIn(ExperimentalSerializationApi::class)
private object FlexibleStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.contentOrNull
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

@OptIn(ExperimentalSerializationApi::class)
private object FlexibleRatingKeySerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleRatingKey", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = jsonDecoder.decodeJsonElement()
        return when (element) {
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: String) {
        encoder.encodeString(value)
    }
}

@Serializable
data class MusicBrainzReleaseGroupSearchResponse(
    @SerialName("release-groups") val releaseGroups: List<MusicBrainzReleaseGroupDto> = emptyList(),
)

@Serializable
data class MusicBrainzReleaseGroupDto(
    val id: String,
)
