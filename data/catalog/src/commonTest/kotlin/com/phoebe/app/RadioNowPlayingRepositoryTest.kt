package com.phoebe.app

import com.phoebe.app.data.PhoebeDataJson
import com.phoebe.app.data.RadioNowPlayingRepository
import com.phoebe.app.domain.RadioNowPlayingSource
import com.phoebe.app.domain.RadioNowPlayingSourceType
import com.phoebe.app.domain.Track
import com.phoebe.app.testing.testMockEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RadioNowPlayingRepositoryTest {
    @Test
    fun resolvesBbcRmsSegmentsMetadata() = runTest {
        val repository = RadioNowPlayingRepository(
            HttpClient(testMockEngine {
                respondOk(
                    """
                    {
                      "data": [
                        {
                          "offset": { "now_playing": true },
                          "titles": { "primary": "Stereolab", "secondary": "French Disko" }
                        }
                      ]
                    }
                    """.trimIndent(),
                )
            }) {
                install(ContentNegotiation) { json(PhoebeDataJson) }
            },
        )

        val metadata = repository.resolve(
            radioTrack(
                RadioNowPlayingSource(
                    type = RadioNowPlayingSourceType.BbcRmsSegments,
                    url = "https://example.test/bbc",
                ),
            ),
        )

        assertEquals("Stereolab", metadata?.artist)
        assertEquals("French Disko", metadata?.title)
        assertEquals(RadioNowPlayingSourceType.BbcRmsSegments, metadata?.sourceType)
    }

    @Test
    fun resolvesKexpPlaysMetadata() = runTest {
        val repository = RadioNowPlayingRepository(
            HttpClient(testMockEngine {
                respondOk(
                    """
                    {
                      "results": [
                        { "artist": "Yo La Tengo", "song": "Autumn Sweater" }
                      ]
                    }
                    """.trimIndent(),
                )
            }) {
                install(ContentNegotiation) { json(PhoebeDataJson) }
            },
        )

        val metadata = repository.resolve(
            radioTrack(
                RadioNowPlayingSource(
                    type = RadioNowPlayingSourceType.KexpPlays,
                    url = "https://example.test/kexp",
                ),
            ),
        )

        assertEquals("Yo La Tengo", metadata?.artist)
        assertEquals("Autumn Sweater", metadata?.title)
        assertEquals(RadioNowPlayingSourceType.KexpPlays, metadata?.sourceType)
    }

    @Test
    fun parsesIcyStreamTitle() {
        val metadata = RadioNowPlayingRepository.parseIcyMetadata("StreamTitle='Asian Mirage - C) Sabah Safari';")

        assertEquals("Asian Mirage", metadata?.artist)
        assertEquals("C) Sabah Safari", metadata?.title)
        assertEquals(RadioNowPlayingSourceType.Icy, metadata?.sourceType)
    }

    @Test
    fun resolvesIcyMetadataBlockFromStream() = runTest {
        val metaint = 8
        val icyMetadata = "StreamTitle='Durand Jones - Morning in America';"
        val blockLength = ((icyMetadata.encodeToByteArray().size + 15) / 16).coerceAtLeast(1)
        val metadataBlock = ByteArray(blockLength * 16)
        icyMetadata.encodeToByteArray().copyInto(metadataBlock)
        val body = ByteArray(metaint) { 0 } + byteArrayOf(blockLength.toByte()) + metadataBlock
        val repository = RadioNowPlayingRepository(
            HttpClient(testMockEngine {
                respond(
                    content = body,
                    headers = headersOf(
                        "icy-metaint" to listOf(metaint.toString()),
                        "Content-Type" to listOf("audio/mpeg"),
                    ),
                )
            }),
        )

        val metadata = repository.resolve(radioTrack(RadioNowPlayingSource(type = RadioNowPlayingSourceType.Icy)))

        assertEquals("Durand Jones", metadata?.artist)
        assertEquals("Morning in America", metadata?.title)
    }

    @Test
    fun parsesHlsPlaylistMetadata() {
        val metadata = RadioNowPlayingRepository.parseHlsPlaylistMetadata(
            """
            #EXTM3U
            #EXT-X-DATERANGE:ID="now",ARTIST="Broadcast",TITLE="Come On Let's Go"
            #EXTINF:6.000,no desc
            segment.ts
            """.trimIndent(),
        )

        assertEquals("Broadcast", metadata?.artist)
        assertEquals("Come On Let's Go", metadata?.title)
    }

    private fun radioTrack(source: RadioNowPlayingSource): Track =
        Track(
            id = "radio:test",
            title = "Test Station",
            artist = "Internet Radio",
            album = "Internet Radio",
            durationMs = 0L,
            streamUrl = "https://example.test/stream",
            downloadUrl = "https://example.test/stream",
            radioNowPlayingSource = source,
        )
}
