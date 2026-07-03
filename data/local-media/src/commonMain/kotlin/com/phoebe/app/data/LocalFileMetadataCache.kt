package com.phoebe.app.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.phoebe.app.db.LocalFileMetadataCacheRow
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalFileMetadataCache(
    private val database: PhoebeDatabase,
) {
    suspend fun rowsForFolder(folderId: String): Map<String, LocalFileMetadataCacheEntry> =
        withContext(Dispatchers.Default) {
            database.catalogQueries
                .selectLocalFileMetadataCacheForFolder(folderId)
                .awaitAsList()
                .associate { row -> row.uri to row.toEntry() }
        }

    suspend fun replaceFolderRows(
        folderId: String,
        entries: Collection<LocalFileMetadataCacheEntry>,
        currentUris: Set<String>,
    ) {
        withContext(Dispatchers.Default) {
            val existingRows = database.catalogQueries
                .selectLocalFileMetadataCacheForFolder(folderId)
                .awaitAsList()
            val existingByUri = existingRows.associate { row -> row.uri to row.toEntry() }
            val staleRows = existingRows
                .filter { it.uri !in currentUris }
            val changedEntries = entries.filter { entry -> existingByUri[entry.uri] != entry }
            database.transaction {
                staleRows.forEach { row ->
                    database.catalogQueries.deleteLocalFileMetadataCache(folderId, row.uri)
                }
                changedEntries.forEach { entry ->
                    database.catalogQueries.upsertLocalFileMetadataCache(
                        folderId = folderId,
                        uri = entry.uri,
                        sizeBytes = entry.sizeBytes,
                        modifiedAtMs = entry.modifiedAtMs,
                        trackId = entry.track.id,
                        albumId = entry.albumId,
                        title = entry.track.title,
                        artist = entry.track.artist,
                        album = entry.track.album,
                        durationMs = entry.track.durationMs,
                        year = entry.track.year?.toLong(),
                        genre = entry.track.genre,
                        mood = entry.track.mood,
                        style = entry.track.style,
                        bitrateKbps = entry.track.bitrateKbps?.toLong(),
                        audioCodec = entry.track.audioCodec,
                        filepath = entry.track.filepath,
                        localArtworkUri = entry.track.localArtworkUri.orEmpty(),
                        dateAddedMs = entry.track.dateAddedMs ?: 0L,
                    )
                }
            }
        }
    }

    suspend fun applyFolderDelta(
        folderId: String,
        changedEntries: Collection<LocalFileMetadataCacheEntry>,
        removedUris: Collection<String>,
    ) {
        if (changedEntries.isEmpty() && removedUris.isEmpty()) return
        withContext(Dispatchers.Default) {
            database.transaction {
                removedUris.forEach { uri ->
                    database.catalogQueries.deleteLocalFileMetadataCache(folderId, uri)
                }
                changedEntries.forEach { entry ->
                    database.catalogQueries.upsertLocalFileMetadataCache(
                        folderId = folderId,
                        uri = entry.uri,
                        sizeBytes = entry.sizeBytes,
                        modifiedAtMs = entry.modifiedAtMs,
                        trackId = entry.track.id,
                        albumId = entry.albumId,
                        title = entry.track.title,
                        artist = entry.track.artist,
                        album = entry.track.album,
                        durationMs = entry.track.durationMs,
                        year = entry.track.year?.toLong(),
                        genre = entry.track.genre,
                        mood = entry.track.mood,
                        style = entry.track.style,
                        bitrateKbps = entry.track.bitrateKbps?.toLong(),
                        audioCodec = entry.track.audioCodec,
                        filepath = entry.track.filepath,
                        localArtworkUri = entry.track.localArtworkUri.orEmpty(),
                        dateAddedMs = entry.track.dateAddedMs ?: 0L,
                    )
                }
            }
        }
    }

    suspend fun clearFolder(folderId: String) {
        withContext(Dispatchers.Default) {
            database.catalogQueries.clearLocalFileMetadataCacheForFolder(folderId)
        }
    }

    private fun LocalFileMetadataCacheRow.toEntry(): LocalFileMetadataCacheEntry =
        LocalFileMetadataCacheEntry(
            folderId = folderId,
            uri = uri,
            sizeBytes = sizeBytes,
            modifiedAtMs = modifiedAtMs,
            albumId = albumId,
            track = Track(
                id = trackId,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                streamUrl = "",
                downloadUrl = "",
                thumbUrl = null,
                localArtworkUri = localArtworkUri?.takeIf { it.isNotBlank() },
                localUri = uri,
                year = year?.toInt(),
                genre = genre,
                mood = mood,
                style = style,
                filepath = filepath,
                audioCodec = audioCodec,
                bitrateKbps = bitrateKbps?.toInt(),
                dateAddedMs = dateAddedMs,
            ),
            artworkScanned = localArtworkUri != null,
        )
}

data class LocalFileMetadataCacheEntry(
    val folderId: String,
    val uri: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
    val albumId: String,
    val track: Track,
    val artworkScanned: Boolean = true,
) {
    fun fingerprintMatches(sizeBytes: Long, modifiedAtMs: Long): Boolean =
        this.sizeBytes == sizeBytes && this.modifiedAtMs == modifiedAtMs
}
