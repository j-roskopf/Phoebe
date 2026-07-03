package com.phoebe.app.sources

import androidx.compose.runtime.Composable
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.platform.rememberPickDesktopDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.Artwork
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicReference

private val audioExt = setOf("mp3", "m4a", "flac", "wav", "aac", "ogg", "opus")
private val artworkExt = setOf("jpg", "jpeg", "png", "webp")
private val sidecarArtworkNames = listOf("cover", "folder", "front", "album", "artwork")
private val lastSidecarParentCache = AtomicReference<Pair<Path, Map<String, Path>>?>()
private const val MaxEmbeddedArtworkBytes = 12 * 1024 * 1024

actual object LocalLibraryIO {
    actual suspend fun listAudioFiles(rootUri: String): List<LocalAudioFile> = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(rootUri) }.getOrNull() ?: return@withContext emptyList()
        val path = runCatching { Paths.get(uri) }.getOrNull() ?: return@withContext emptyList()
        if (!Files.exists(path) || !Files.isDirectory(path)) return@withContext emptyList()
        val files = mutableListOf<LocalAudioFile>()
        Files.walkFileTree(
            path,
            setOf(FileVisitOption.FOLLOW_LINKS),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(candidate: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = candidate.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (!audioExt.contains(name.substringAfterLast('.', "").lowercase())) return FileVisitResult.CONTINUE
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    files += LocalAudioFile(
                        uri = candidate.toUri().toString(),
                        sizeBytes = attrs.size(),
                        modifiedAtMs = attrs.lastModifiedTime().toMillis(),
                        filepath = name,
                    )
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            },
        )
        files.sortedBy { it.uri }
    }

    actual suspend fun listAudioUris(rootUri: String): List<String> = withContext(Dispatchers.IO) {
        listAudioFiles(rootUri).map { it.uri }
    }

    actual suspend fun fileExists(uri: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val p = Paths.get(URI(uri))
            Files.isRegularFile(p)
        }.getOrDefault(false)
    }

    actual suspend fun readAudioMetadata(uri: String): AudioMetadata = withContext(Dispatchers.IO) {
        val path = runCatching { Paths.get(URI(uri)) }.getOrNull()
        if (path == null || !Files.isRegularFile(path)) {
            return@withContext AudioMetadata(title = null, artist = null, album = null, durationMs = 0L, year = null, genre = null, mood = null, style = null, bitrateKbps = null, audioCodec = null)
        }
        runCatching {
            val audioFile = AudioFileIO.read(path.toFile())
            val tag = audioFile.tag
            fun first(key: FieldKey) = tag?.getFirst(key)?.trim()?.takeIf { it.isNotEmpty() }
            val title = first(FieldKey.TITLE)
            val artist = first(FieldKey.ARTIST) ?: first(FieldKey.ALBUM_ARTIST)
            val album = first(FieldKey.ALBUM)
            val header = audioFile.audioHeader
            val precise = header.preciseTrackLength
            val durationMs = when {
                precise.isFinite() && precise > 0.0 -> (precise * 1000.0).toLong()
                header.trackLength > 0 -> header.trackLength * 1000L
                else -> 0L
            }
            val year = first(FieldKey.YEAR)?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
            val genre = first(FieldKey.GENRE)
            val mood = first(FieldKey.MOOD)
            val bitrateStr = header.bitRate
            val bitrateKbps = bitrateStr?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it > 0 }
            val audioCodec = header.format?.substringBefore(' ')?.takeIf { it.isNotBlank() }
            val artworkUri = embeddedArtworkUri(path.toUri().toString(), tag?.getFirstArtwork())
                ?: sidecarArtworkUri(path)
            AudioMetadata(
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs.coerceAtLeast(0L),
                year = year,
                genre = genre,
                mood = mood,
                style = null,
                bitrateKbps = bitrateKbps,
                audioCodec = audioCodec,
                artworkUri = artworkUri,
            )
        }.getOrElse {
            AudioMetadata(
                title = null,
                artist = null,
                album = null,
                durationMs = 0L,
                year = null,
                genre = null,
                mood = null,
                style = null,
                bitrateKbps = null,
                audioCodec = null,
                artworkUri = sidecarArtworkUri(path),
            )
        }
    }

    actual suspend fun readLyrics(uri: String): String? = withContext(Dispatchers.IO) {
        val path = runCatching { Paths.get(URI(uri)) }.getOrNull() ?: return@withContext null
        if (!Files.isRegularFile(path)) return@withContext null
        val baseName = path.fileName.toString().substringBeforeLast('.', path.fileName.toString())
        val parent = path.parent
        if (parent != null) {
            listOf("$baseName.lrc", "$baseName.txt").forEach { sidecarName ->
                val sidecar = parent.resolve(sidecarName)
                if (Files.isRegularFile(sidecar)) {
                    val text = runCatching { Files.readString(sidecar) }.getOrNull()
                    if (!text.isNullOrBlank()) return@withContext text
                }
            }
        }
        runCatching {
            val tag = AudioFileIO.read(path.toFile()).tag ?: return@runCatching null
            tag.getFirst(FieldKey.LYRICS)?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}

@Composable
actual fun rememberPickLocalFolder(onPicked: (String?) -> Unit): () -> Unit =
    rememberPickDesktopDirectory(
        title = "Choose music folder",
        initialDirectory = File(System.getProperty("user.home")),
        onPicked = onPicked,
    )

private suspend fun embeddedArtworkUri(sourceUri: String, artwork: Artwork?): String? {
    val bytes = artwork?.binaryData?.takeIf { it.isNotEmpty() && it.size <= MaxEmbeddedArtworkBytes } ?: return null
    val extension = artwork.mimeType.artworkExtension() ?: bytes.sniffedArtworkExtension()
    val target = "artwork/local-${sourceUri.stableArtworkHash()}.$extension"
    return runCatching { PlatformStorage().writeBytes(target, bytes) }.getOrNull()
}

private fun sidecarArtworkUri(path: Path): String? {
    val parent = path.parent ?: return null
    val cached = lastSidecarParentCache.get()
    val filesByName = if (cached != null && cached.first == parent) {
        cached.second
    } else {
        runCatching {
            Files.list(parent).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .toList()
                    .associateBy { it.fileName.toString().lowercase() }
            }
        }.getOrDefault(emptyMap())
            .also { files -> lastSidecarParentCache.set(parent to files) }
    }
    for (name in sidecarArtworkNames) {
        for (extension in artworkExt) {
            filesByName["$name.$extension"]?.let { return it.toUri().toString() }
        }
    }
    return null
}

private fun String?.artworkExtension(): String? =
    when (this?.substringAfterLast('/', "")?.lowercase()) {
        "jpeg", "jpg" -> "jpg"
        "png" -> "png"
        "webp" -> "webp"
        else -> null
    }

private fun ByteArray.sniffedArtworkExtension(): String =
    when {
        size >= 8 &&
            this[0] == 0x89.toByte() &&
            this[1] == 0x50.toByte() &&
            this[2] == 0x4E.toByte() &&
            this[3] == 0x47.toByte() -> "png"
        size >= 12 &&
            this[0] == 0x52.toByte() &&
            this[1] == 0x49.toByte() &&
            this[2] == 0x46.toByte() &&
            this[8] == 0x57.toByte() &&
            this[9] == 0x45.toByte() &&
            this[10] == 0x42.toByte() &&
            this[11] == 0x50.toByte() -> "webp"
        else -> "jpg"
    }

private fun String.stableArtworkHash(): String {
    var hash = 1125899906842597L
    forEach { c -> hash = (hash * 31) + c.code }
    return hash.toString()
}
