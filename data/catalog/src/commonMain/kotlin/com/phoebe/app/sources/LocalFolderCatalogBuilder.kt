package com.phoebe.app.sources

import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.LocalFolderMediaSourceConfig
import com.phoebe.app.domain.Track
import com.phoebe.app.data.LocalFileMetadataCache
import com.phoebe.app.data.LocalFileMetadataCacheEntry
import com.phoebe.app.data.enrichArtistAlbumCountsOnly
import com.phoebe.app.data.enrichArtistArtwork
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.catalogTrackIndexParallelism
import com.phoebe.app.platform.currentTimeMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield

object LocalFolderCatalogBuilder {

    suspend fun build(
        config: LocalFolderMediaSourceConfig,
        cache: LocalFileMetadataCache? = null,
        reader: LocalAudioLibraryReader = PlatformLocalAudioLibraryReader,
    ): CatalogSnapshot {
        val indexedAtMs = currentTimeMs()
        val root = config.rootUri
        val files = runCatching { reader.listAudioFiles(root) }.getOrElse { emptyList() }
        if (files.isEmpty()) {
            PhoebeLog.d("LocalFolderCatalogBuilder") { "build folder='${config.label}' → empty, clearing cache" }
            cache?.clearFolder(config.id)
            return CatalogSnapshot()
        }

        val prefix = "local_${config.id}"
        val cachedByUri = cache?.rowsForFolder(config.id).orEmpty()
        PhoebeLog.d("LocalFolderCatalogBuilder") {
            "build folder='${config.label}' files=${files.size} cached=${cachedByUri.size}"
        }
        val result = buildEntries(
            folderId = config.id,
            prefix = prefix,
            files = files,
            cachedByUri = cachedByUri,
            indexedAtMs = indexedAtMs,
            reader = reader,
        )
        val currentUris = files.mapTo(mutableSetOf()) { it.uri }
        cache?.applyFolderDelta(
            folderId = config.id,
            changedEntries = result.changedEntries,
            removedUris = cachedByUri.keys - currentUris,
        )
        val entries = result.entries

        val tracksByAlbum = linkedMapOf<String, MutableList<Pair<String, Track>>>()
        for (entry in entries) {
            tracksByAlbum.getOrPut(entry.albumId) { mutableListOf() }
                .add(entry.track.album to entry.track)
        }

        val albums = mutableListOf<Album>()
        val tracksByParent = mutableMapOf<String, List<Track>>()

        for ((albumId, pairs) in tracksByAlbum) {
            val albumTitle = pairs.first().first
            val tracks = pairs.map { it.second }
            val artistGuess = tracks.firstOrNull()?.artist ?: "Local files"
            val artworkUri = tracks.firstNotNullOfOrNull { track ->
                track.localArtworkUri?.takeIf { it.isNotBlank() }
                    ?: track.thumbUrl?.takeIf { it.isNotBlank() }
            }
            albums.add(
                Album(
                    id = albumId,
                    title = albumTitle,
                    artist = artistGuess,
                    year = null,
                    thumbUrl = artworkUri,
                    dateAddedMs = tracks.mapNotNull { it.dateAddedMs }.maxOrNull(),
                    genre = dominantTrackTag(tracks) { it.genre },
                    mood = dominantTrackTag(tracks) { it.mood },
                    style = dominantTrackTag(tracks) { it.style },
                ),
            )
            tracksByParent[albumId] = tracks
        }

        val rawArtists = albums.map { it.artist }.distinct().map { name ->
            val artistAdded = albums
                .filter { it.artist.equals(name, ignoreCase = true) }
                .mapNotNull { it.dateAddedMs }
                .maxOrNull()
            val artistTracks = tracksByParent.values.flatten().filter { it.artist.equals(name, ignoreCase = true) }
            Artist(
                id = "$prefix:artist:${name.hashCode()}",
                title = name,
                thumbUrl = null,
                albumCount = 0,
                dateAddedMs = artistAdded,
                genre = dominantTrackTag(artistTracks) { it.genre },
                mood = dominantTrackTag(artistTracks) { it.mood },
                style = dominantTrackTag(artistTracks) { it.style },
            )
        }
        val artists = enrichArtistAlbumCountsOnly(enrichArtistArtwork(rawArtists, albums), albums)

        PhoebeLog.d("LocalFolderCatalogBuilder") {
            "build complete folder='${config.label}' → ${artists.size} artists, ${albums.size} albums, ${tracksByParent.values.sumOf { it.size }} tracks"
        }
        return CatalogSnapshot(
            artists = artists,
            albums = albums,
            playlists = emptyList(),
            tracksByParent = tracksByParent,
            downloads = emptyList(),
        )
    }

    private suspend fun buildEntries(
        folderId: String,
        prefix: String,
        files: List<LocalAudioFile>,
        cachedByUri: Map<String, LocalFileMetadataCacheEntry>,
        indexedAtMs: Long,
        reader: LocalAudioLibraryReader,
    ): LocalFolderBuildEntries = coroutineScope {
        val parallelism = minOf(catalogTrackIndexParallelism().coerceAtLeast(1), 4)
        val entries = MutableList<LocalFileMetadataCacheEntry?>(files.size) { null }
        val changedFiles = mutableListOf<Pair<Int, LocalAudioFile>>()
        for ((index, file) in files.withIndex()) {
            val cached = cachedByUri[file.uri]
            if (cached?.fingerprintMatches(file.sizeBytes, file.modifiedAtMs) == true && cached.artworkScanned) {
                entries[index] = cached.copy(
                    sizeBytes = file.sizeBytes,
                    modifiedAtMs = file.modifiedAtMs,
                    track = cached.track.copy(filepath = file.filepath.ifBlank { cached.track.filepath }),
                )
            } else {
                changedFiles.add(index to file)
            }
        }

        PhoebeLog.v("LocalFolderCatalogBuilder") {
            "metadata scan folder=$folderId → ${changedFiles.size} changed, ${files.size - changedFiles.size} cache hits"
        }
        val changedEntries = mutableListOf<LocalFileMetadataCacheEntry>()
        for (chunk in changedFiles.chunked(parallelism)) {
            val built = chunk.map { (index, file) ->
                async {
                    index to buildEntry(folderId, prefix, file, cachedByUri[file.uri], indexedAtMs, reader)
                }
            }.awaitAll()
            for ((index, entry) in built) {
                entries[index] = entry
                changedEntries += entry
            }
            yield()
        }
        LocalFolderBuildEntries(
            entries = entries.mapNotNull { it },
            changedEntries = changedEntries,
        )
    }

    private suspend fun buildEntry(
        folderId: String,
        prefix: String,
        file: LocalAudioFile,
        previous: LocalFileMetadataCacheEntry?,
        indexedAtMs: Long,
        reader: LocalAudioLibraryReader,
    ): LocalFileMetadataCacheEntry {
        val meta = try {
            reader.readAudioMetadata(file.uri)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            AudioMetadata(title = null, artist = null, album = null, durationMs = 0L)
        }
        val parent = parentFolderLabel(file.uri)
        val albumTitle = meta.album?.takeIf { it.isNotBlank() } ?: parent
        val artistName = meta.artist?.takeIf { it.isNotBlank() } ?: "Local files"
        val trackTitle = meta.title?.takeIf { it.isNotBlank() }
            ?: file.filepath.substringBeforeLast('.', file.filepath).ifBlank {
                file.uri.substringAfterLast('/').substringBeforeLast('.')
            }
        val albumId = "$prefix:album:${albumTitle.hashCode().toUInt()}"
        val trackId = previous?.track?.id ?: "$prefix:track:${file.uri.hashCode()}"
        val track = Track(
            id = trackId,
            title = trackTitle,
            artist = artistName,
            album = albumTitle,
            durationMs = meta.durationMs,
            streamUrl = "",
            downloadUrl = "",
            thumbUrl = null,
            localArtworkUri = meta.artworkUri?.takeIf { it.isNotBlank() },
            localUri = file.uri,
            year = meta.year,
            genre = meta.genre,
            mood = meta.mood,
            style = meta.style,
            filepath = file.filepath.ifBlank { filepathDisplay(file.uri) },
            audioCodec = meta.audioCodec,
            bitrateKbps = meta.bitrateKbps,
            dateAddedMs = previous?.track?.dateAddedMs ?: indexedAtMs,
        )
        return LocalFileMetadataCacheEntry(
            folderId = folderId,
            uri = file.uri,
            sizeBytes = file.sizeBytes,
            modifiedAtMs = file.modifiedAtMs,
            albumId = albumId,
            track = track,
        )
    }

    private fun filepathDisplay(uri: String): String {
        val noQuery = uri.substringBefore('?').trimEnd('/')
        val slash = noQuery.lastIndexOf('/')
        return if (slash >= 0) noQuery.substring(slash + 1).ifBlank { noQuery } else noQuery
    }

    private fun parentFolderLabel(uri: String): String {
        val path = uri.substringBefore('?').trimEnd('/')
        val last = path.lastIndexOf('/')
        if (last <= 0) return "Library"
        val second = path.lastIndexOf('/', last - 1)
        return if (second >= 0) {
            path.substring(second + 1, last).ifBlank { "Library" }
        } else {
            "Library"
        }
    }

    private fun dominantTrackTag(tracks: List<Track>, tag: (Track) -> String?): String? =
        tracks.asSequence()
            .mapNotNull { tag(it)?.trim()?.takeIf { value -> value.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
}

private data class LocalFolderBuildEntries(
    val entries: List<LocalFileMetadataCacheEntry>,
    val changedEntries: List<LocalFileMetadataCacheEntry>,
)
