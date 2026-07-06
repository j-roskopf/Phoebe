package com.phoebe.app.data

import com.phoebe.app.domain.ArtistEvent
import com.phoebe.app.domain.ArtistEventsResponse
import com.phoebe.app.domain.EventSettings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException

@SingleIn(AppScope::class)
@Inject
class ArtistEventsRepository(
    private val client: ArtistEventsClient,
    private val appSettingsRepository: AppSettingsRepository,
) {
    suspend fun fetchArtistEvents(
        artist: String,
        limit: Int = 50,
        settings: EventSettings = appSettingsRepository.settings.value.events,
    ): ArtistEventsResponse {
        val baseUrl = resolveEventsBackendBaseUrl(settings)
            ?: error("Phoebe backend URL is not configured.")
        return client.fetchArtistEvents(
            baseUrl = baseUrl,
            provider = settings.provider,
            artist = artist,
            limit = limit,
        )
    }

    suspend fun fetchUpcomingEventsOrEmpty(
        artist: String,
        limit: Int = 50,
        settings: EventSettings = appSettingsRepository.settings.value.events,
    ): List<ArtistEvent> =
        try {
            fetchArtistEvents(artist, limit, settings).events
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }

    suspend fun checkHealth(settings: EventSettings = appSettingsRepository.settings.value.events): Result<String> {
        val baseUrl = resolveEventsBackendBaseUrl(settings)
            ?: return Result.failure(IllegalStateException("Phoebe backend URL is not configured."))
        return try {
            Result.success(client.health(baseUrl))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    fun resolvedBackendBaseUrl(settings: EventSettings = appSettingsRepository.settings.value.events): String? =
        resolveEventsBackendBaseUrl(settings)
}
