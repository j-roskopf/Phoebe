package com.phoebe.app.sources

import androidx.compose.runtime.Composable

data class AudioMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val year: Int? = null,
    val genre: String? = null,
    val mood: String? = null,
    val style: String? = null,
    val bitrateKbps: Int? = null,
    val audioCodec: String? = null,
    val artworkUri: String? = null,
)

data class LocalAudioFile(
    val uri: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
    val filepath: String,
)

interface LocalAudioLibraryReader {
    suspend fun listAudioFiles(rootUri: String): List<LocalAudioFile>
    suspend fun readAudioMetadata(uri: String): AudioMetadata
}

object PlatformLocalAudioLibraryReader : LocalAudioLibraryReader {
    override suspend fun listAudioFiles(rootUri: String): List<LocalAudioFile> =
        LocalLibraryIO.listAudioFiles(rootUri)

    override suspend fun readAudioMetadata(uri: String): AudioMetadata =
        LocalLibraryIO.readAudioMetadata(uri)
}

expect object LocalLibraryIO {
    suspend fun listAudioFiles(rootUri: String): List<LocalAudioFile>
    suspend fun listAudioUris(rootUri: String): List<String>
    suspend fun fileExists(uri: String): Boolean
    suspend fun readAudioMetadata(uri: String): AudioMetadata
    suspend fun readLyrics(uri: String): String?
}

/** Returns a lambda to invoke from UI (e.g. button) to pick a folder; calls [onPicked] with a URI string or null. */
@Composable
expect fun rememberPickLocalFolder(onPicked: (String?) -> Unit): () -> Unit
