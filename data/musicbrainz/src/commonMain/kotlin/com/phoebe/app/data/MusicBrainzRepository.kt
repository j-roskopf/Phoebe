package com.phoebe.app.data

import com.phoebe.app.domain.Album
import com.phoebe.app.domain.EventSettings
import com.phoebe.app.domain.MusicBrainzAlbumMetadataResponse
import com.phoebe.app.domain.MusicBrainzArtistArtworkResponse
import com.phoebe.app.domain.Track
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@Inject
class MusicBrainzRepository(
    private val client: MusicBrainzClient,
    private val appSettingsRepository: AppSettingsRepository,
) {
    suspend fun albumMetadata(
        album: Album,
        tracks: List<Track>,
        settings: EventSettings = appSettingsRepository.settings.value.events,
    ): MusicBrainzAlbumMetadataResponse {
        val baseUrl = resolveEventsBackendBaseUrl(settings)
            ?: error("Phoebe backend URL is not configured.")
        return client.albumMetadata(
            baseUrl = baseUrl,
            album = album.title,
            artist = album.albumArtist?.takeIf { it.isNotBlank() } ?: album.artist,
            year = album.year ?: album.releaseDate?.take(4)?.toIntOrNull(),
            releaseMbids = tracks
                .mapNotNull { it.musicBrainzReleaseId?.takeIf(String::isNotBlank) }
                .distinct()
                .take(MaxReleaseMbids),
        )
    }

    suspend fun artistArtwork(
        artist: String,
        limit: Int = DefaultArtistArtworkLimit,
        fast: Boolean = true,
        excludedArtworkUrls: List<String> = emptyList(),
        settings: EventSettings = appSettingsRepository.settings.value.events,
    ): MusicBrainzArtistArtworkResponse {
        val baseUrl = resolveEventsBackendBaseUrl(settings)
            ?: error("Phoebe backend URL is not configured.")
        return client.artistArtwork(
            baseUrl = baseUrl,
            artist = artist,
            limit = limit,
            fast = fast,
            excludedArtworkUrls = excludedArtworkUrls,
        )
    }

    fun resolvedBackendBaseUrl(settings: EventSettings = appSettingsRepository.settings.value.events): String? =
        resolveEventsBackendBaseUrl(settings)

    private companion object {
        const val MaxReleaseMbids = 8
        const val DefaultArtistArtworkLimit = 12
    }
}
