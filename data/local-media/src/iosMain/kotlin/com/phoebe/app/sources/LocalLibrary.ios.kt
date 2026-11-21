package com.phoebe.app.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVURLAsset
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSDate
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSURL
import platform.Foundation.NSFileSize
import platform.Foundation.timeIntervalSince1970

private val audioExt = setOf("mp3", "m4a", "flac", "wav", "aac", "ogg", "opus")

actual object LocalLibraryIO {
    actual val metadataVersion: Int = 0

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun listAudioFiles(rootUri: String): List<LocalAudioFile> = withContext(Dispatchers.Default) {
        if (!rootUri.startsWith("file:")) return@withContext emptyList()
        val path = NSURL.URLWithString(rootUri)?.path ?: return@withContext emptyList()
        val fm = platform.Foundation.NSFileManager.defaultManager
        val enumerator = fm.enumeratorAtPath(path) ?: return@withContext emptyList()
        val out = mutableListOf<LocalAudioFile>()
        while (true) {
            val rel = enumerator.nextObject() as? String ?: break
            val ext = rel.substringAfterLast('.', "").lowercase()
            if (ext !in audioExt) continue
            val filePath = "$path/$rel"
            val attrs = fm.attributesOfItemAtPath(filePath, error = null)
            val size = (attrs?.get(NSFileSize) as? Number)?.toLong() ?: 0L
            val modified = (attrs?.get(NSFileModificationDate) as? NSDate)
                ?.timeIntervalSince1970
                ?.let { (it * 1000.0).toLong() }
                ?: 0L
            out.add(
                LocalAudioFile(
                    uri = NSURL.fileURLWithPath(filePath).absoluteString!!,
                    sizeBytes = size.coerceAtLeast(0L),
                    modifiedAtMs = modified.coerceAtLeast(0L),
                    filepath = rel.substringAfterLast('/'),
                ),
            )
        }
        out.sortedBy { it.uri }
    }

    actual suspend fun listAudioUris(rootUri: String): List<String> = withContext(Dispatchers.Default) {
        listAudioFiles(rootUri).map { it.uri }
    }

    actual suspend fun fileExists(uri: String): Boolean = withContext(Dispatchers.Default) {
        val path = NSURL.URLWithString(uri)?.path ?: return@withContext false
        platform.Foundation.NSFileManager.defaultManager.fileExistsAtPath(path)
    }

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun readAudioMetadata(uri: String): AudioMetadata = withContext(Dispatchers.Default) {
        val url = NSURL.URLWithString(uri) ?: return@withContext AudioMetadata(null, null, null, 0L, null, null, null, null)
        val asset = AVURLAsset.URLAssetWithURL(url, options = null)
        val seconds = CMTimeGetSeconds(asset.duration)
        val durationMs = if (seconds.isFinite() && seconds > 0.0) {
            (seconds * 1000.0).toLong()
        } else {
            0L
        }
        // AVFoundation tag fields are not consistently exposed in Kotlin/Native stubs; duration is still useful.
        AudioMetadata(
            title = null,
            artist = null,
            album = null,
            durationMs = durationMs.coerceAtLeast(0L),
            year = null,
            genre = null,
            mood = null,
            style = null,
            bitrateKbps = null,
            audioCodec = null,
        )
    }

    actual suspend fun readLyrics(uri: String): String? = withContext(Dispatchers.Default) {
        null
    }
}

@Composable
actual fun rememberPickLocalFolder(onPicked: (String?) -> Unit): () -> Unit =
    remember(onPicked) {
        {
            // Folder picking on iOS requires a UIViewController bridge; use desktop/Android for agent tests.
            onPicked(null)
        }
    }
