package com.phoebe.app.data

import com.phoebe.app.domain.RadioNowPlayingMetadata
import com.phoebe.app.domain.RadioNowPlayingSource
import com.phoebe.app.domain.RadioNowPlayingSourceType
import com.phoebe.app.domain.Track
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@SingleIn(AppScope::class)
@Inject
class RadioNowPlayingRepository(
    private val httpClient: HttpClient,
) {
    suspend fun resolve(track: Track): RadioNowPlayingMetadata? {
        if (!track.id.startsWith("radio:")) return null
        val source = track.radioNowPlayingSource ?: RadioNowPlayingSource()
        val sourceUrl = source.url?.takeIf { it.isNotBlank() } ?: track.streamUrl
        return when (source.type) {
            RadioNowPlayingSourceType.BbcRmsSegments -> resolveBbcRmsSegments(sourceUrl)
            RadioNowPlayingSourceType.KexpPlays -> resolveKexpPlays(sourceUrl)
            RadioNowPlayingSourceType.Icy -> resolveGenericStream(sourceUrl)
        }
    }

    private suspend fun resolveBbcRmsSegments(url: String): RadioNowPlayingMetadata? {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) return null
        val root = runCatching { PhoebeDataJson.parseToJsonElement(response.bodyAsText()) }.getOrNull() ?: return null
        val data = root.jsonObjectOrNull()
            ?.get("data")
            ?.jsonArrayOrNull()
            .orEmpty()
        val segment = data.firstOrNull { segment ->
            segment.jsonObjectOrNull()
                ?.get("offset")
                ?.jsonObjectOrNull()
                ?.get("now_playing")
                ?.jsonPrimitiveOrNull()
                ?.booleanOrNull == true
        } ?: data.firstOrNull()
        val titles = segment?.jsonObjectOrNull()?.get("titles")?.jsonObjectOrNull() ?: return null
        return radioNowPlayingMetadata(
            artist = titles.string("primary"),
            title = titles.string("secondary"),
            rawTitle = listOfNotNull(titles.string("primary"), titles.string("secondary")).joinToString(" - "),
            sourceType = RadioNowPlayingSourceType.BbcRmsSegments,
        )
    }

    private suspend fun resolveKexpPlays(url: String): RadioNowPlayingMetadata? {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) return null
        val root = runCatching { PhoebeDataJson.parseToJsonElement(response.bodyAsText()) }.getOrNull() ?: return null
        val play = root.jsonObjectOrNull()
            ?.get("results")
            ?.jsonArrayOrNull()
            ?.firstOrNull()
            ?.jsonObjectOrNull()
            ?: return null
        return radioNowPlayingMetadata(
            artist = play.string("artist"),
            title = play.string("song"),
            rawTitle = listOfNotNull(play.string("artist"), play.string("song")).joinToString(" - "),
            sourceType = RadioNowPlayingSourceType.KexpPlays,
        )
    }

    private suspend fun resolveGenericStream(url: String): RadioNowPlayingMetadata? =
        if (url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
            resolveHls(url)
        } else {
            resolveIcy(url)
        }

    private suspend fun resolveHls(url: String): RadioNowPlayingMetadata? {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) return null
        return parseHlsPlaylistMetadata(response.bodyAsText())
    }

    private suspend fun resolveIcy(url: String): RadioNowPlayingMetadata? {
        val response = httpClient.get(url) {
            header("Icy-MetaData", "1")
            header(HttpHeaders.UserAgent, "Phoebe Radio Metadata")
        }
        if (!response.status.isSuccess()) return null
        val metaint = response.headers["icy-metaint"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val channel = response.bodyAsChannel()
        return withTimeoutOrNull(IcyReadTimeoutMs) {
            repeat(IcyMetadataBlocksToScan) {
                if (!channel.discardBytes(metaint)) return@withTimeoutOrNull null
                val lengthByte = channel.readOneByte() ?: return@withTimeoutOrNull null
                val metadataLength = lengthByte * IcyMetadataBlockSize
                if (metadataLength > 0) {
                    val bytes = channel.readExactly(metadataLength) ?: return@withTimeoutOrNull null
                    val metadataText = bytes.decodeToString().trimEnd('\u0000', ' ', '\r', '\n')
                    parseIcyMetadata(metadataText)?.let { return@withTimeoutOrNull it }
                }
            }
            null
        }
    }

    companion object {
        private const val IcyReadTimeoutMs = 10_000L
        private const val IcyMetadataBlockSize = 16
        private const val IcyMetadataBlocksToScan = 4

        fun parseIcyMetadata(metadataText: String): RadioNowPlayingMetadata? {
            val streamTitle = Regex("""StreamTitle='([^']*)'""")
                .find(metadataText)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return streamTitle.asStreamTitleMetadata(RadioNowPlayingSourceType.Icy)
        }

        fun parseHlsPlaylistMetadata(playlistText: String): RadioNowPlayingMetadata? {
            playlistText.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.startsWith("#EXT-X-DATERANGE")) {
                    val title = quotedAttribute(line, "TITLE") ?: quotedAttribute(line, "X-TITLE")
                    val artist = quotedAttribute(line, "ARTIST") ?: quotedAttribute(line, "X-ARTIST")
                    radioNowPlayingMetadata(
                        artist = artist,
                        title = title,
                        rawTitle = listOfNotNull(artist, title).joinToString(" - "),
                        sourceType = RadioNowPlayingSourceType.Icy,
                    )?.let { return it }
                }
                if (line.startsWith("#EXTINF:")) {
                    val description = line.substringAfter(',', missingDelimiterValue = "")
                        .trim()
                        .takeIf { it.isNotBlank() && !it.equals("no desc", ignoreCase = true) }
                    description?.asStreamTitleMetadata(RadioNowPlayingSourceType.Icy)?.let { return it }
                }
            }
            return null
        }

        private fun quotedAttribute(line: String, name: String): String? =
            Regex("(?:^|,)${Regex.escape(name)}=\"([^\"]*)\"")
                .find(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        private fun String.asStreamTitleMetadata(sourceType: RadioNowPlayingSourceType): RadioNowPlayingMetadata? {
            val value = trim().takeIf { it.isNotBlank() } ?: return null
            val split = Regex("""\s+-\s+""").split(value, limit = 2)
            return if (split.size == 2 && split[0].isNotBlank() && split[1].isNotBlank()) {
                radioNowPlayingMetadata(
                    artist = split[0].trim(),
                    title = split[1].trim(),
                    rawTitle = value,
                    sourceType = sourceType,
                )
            } else {
                radioNowPlayingMetadata(rawTitle = value, title = value, sourceType = sourceType)
            }
        }
    }
}

private suspend fun ByteReadChannel.discardBytes(byteCount: Int): Boolean {
    val buffer = ByteArray(minOf(byteCount, 4096))
    var remaining = byteCount
    while (remaining > 0) {
        if (!awaitContent()) return false
        val read = readAvailable(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) return false
        remaining -= read
    }
    return true
}

private suspend fun ByteReadChannel.readExactly(byteCount: Int): ByteArray? {
    val bytes = ByteArray(byteCount)
    var offset = 0
    while (offset < byteCount) {
        if (!awaitContent()) return null
        val read = readAvailable(bytes, offset, byteCount - offset)
        if (read <= 0) return null
        offset += read
    }
    return bytes
}

private suspend fun ByteReadChannel.readOneByte(): Int? =
    readExactly(1)?.firstOrNull()?.toInt()?.and(0xff)

private fun radioNowPlayingMetadata(
    artist: String? = null,
    title: String? = null,
    rawTitle: String? = null,
    sourceType: RadioNowPlayingSourceType,
): RadioNowPlayingMetadata? {
    val normalizedArtist = artist?.trim().orEmpty()
    val normalizedTitle = title?.trim().orEmpty()
    val normalizedRaw = rawTitle?.trim()?.takeIf { it.isNotBlank() }
    if (normalizedArtist.isBlank() && normalizedTitle.isBlank() && normalizedRaw == null) return null
    return RadioNowPlayingMetadata(
        artist = normalizedArtist,
        title = normalizedTitle,
        rawTitle = normalizedRaw,
        sourceType = sourceType,
    )
}

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement.jsonArrayOrNull() = runCatching { jsonArray }.getOrNull()
private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
private fun JsonObject.string(key: String): String? =
    get(key)?.jsonPrimitiveOrNull()?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
