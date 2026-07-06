package com.phoebe.app.data

import com.phoebe.app.domain.GeniusReferentsResponse
import com.phoebe.app.domain.Track
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

@SingleIn(AppScope::class)
@Inject
class GeniusBackendClient(
    private val httpClient: HttpClient,
) {
    suspend fun referents(baseUrl: String, track: Track): GeniusReferentsResponse {
        val response = httpClient.get("${baseUrl.trimEnd('/')}/v1/genius/referents") {
            parameter("artist", track.artist)
            parameter("title", track.title)
            track.album.takeIf { it.isNotBlank() }?.let { parameter("album", it) }
            if (track.durationMs > 0L) {
                parameter("durationMs", track.durationMs)
            }
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText().take(240)
            error("Genius annotations backend returned HTTP ${response.status.value}: $body")
        }
        return response.body()
    }
}
