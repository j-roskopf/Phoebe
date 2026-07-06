package com.phoebe.app.data

import com.phoebe.app.domain.ArtistEventsResponse
import com.phoebe.app.domain.EventDataProvider
import dev.zacsweers.metro.Inject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

@Inject
class ArtistEventsClient(
    private val httpClient: HttpClient,
) {
    suspend fun fetchArtistEvents(
        baseUrl: String,
        provider: EventDataProvider,
        artist: String,
        limit: Int = 50,
    ): ArtistEventsResponse {
        val response = httpClient.get("${baseUrl.trimEnd('/')}/v1/artist-events") {
            url {
                parameters.append("provider", provider.queryValue)
                parameters.append("artist", artist)
                parameters.append("limit", limit.coerceIn(1, 50).toString())
            }
        }
        if (!response.status.isSuccess()) {
            error(response.bodyAsText().ifBlank { "Phoebe backend returned HTTP ${response.status.value}." })
        }
        return response.body()
    }

    suspend fun health(baseUrl: String): String {
        val response = httpClient.get("${baseUrl.trimEnd('/')}/health")
        if (!response.status.isSuccess()) {
            error(response.bodyAsText().ifBlank { "Phoebe backend returned HTTP ${response.status.value}." })
        }
        return response.bodyAsText()
    }
}

internal val EventDataProvider.queryValue: String
    get() = when (this) {
        EventDataProvider.Ticketmaster -> "ticketmaster"
        EventDataProvider.SeatGeek -> "seatgeek"
    }
