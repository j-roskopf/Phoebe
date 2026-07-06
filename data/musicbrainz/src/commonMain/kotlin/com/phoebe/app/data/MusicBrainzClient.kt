package com.phoebe.app.data

import com.phoebe.app.domain.MusicBrainzAlbumMetadataResponse
import com.phoebe.app.domain.MusicBrainzArtistArtworkResponse
import dev.zacsweers.metro.Inject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

@Inject
class MusicBrainzClient(
    private val httpClient: HttpClient,
) {
    suspend fun albumMetadata(
        baseUrl: String,
        album: String,
        artist: String,
        year: Int?,
        releaseMbids: List<String>,
    ): MusicBrainzAlbumMetadataResponse {
        val response = httpClient.get("${baseUrl.trimEnd('/')}/v1/musicbrainz/album") {
            parameter("album", album)
            parameter("artist", artist)
            year?.let { parameter("year", it) }
            releaseMbids.forEach { parameter("releaseMbid", it) }
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText().take(240)
            error("MusicBrainz backend returned HTTP ${response.status.value}: $body")
        }
        return response.body()
    }

    suspend fun artistArtwork(
        baseUrl: String,
        artist: String,
        limit: Int = DefaultArtistArtworkLimit,
        fast: Boolean = true,
        excludedArtworkUrls: List<String> = emptyList(),
    ): MusicBrainzArtistArtworkResponse {
        val response = httpClient.get("${baseUrl.trimEnd('/')}/v1/musicbrainz/artist-artwork") {
            parameter("artist", artist)
            parameter("limit", limit.coerceIn(1, 24))
            parameter("fast", fast)
            excludedArtworkUrls
                .filter { it.isNotBlank() }
                .forEach { parameter("excludeImageUrl", it) }
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText().take(240)
            error("MusicBrainz backend returned HTTP ${response.status.value}: $body")
        }
        return response.body()
    }

    private companion object {
        const val DefaultArtistArtworkLimit = 12
    }
}
