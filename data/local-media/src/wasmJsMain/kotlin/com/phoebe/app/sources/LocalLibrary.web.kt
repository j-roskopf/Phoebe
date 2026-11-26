@file:OptIn(ExperimentalWasmJsInterop::class)

package com.phoebe.app.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

actual object LocalLibraryIO {
    actual val metadataVersion: Int = 0

    actual suspend fun listAudioFiles(rootUri: String): List<LocalAudioFile> {
        if (rootUri.startsWith(WebRootPrefix)) {
            return parseWebAudioFiles(webListLocalAudioFiles(rootUri))
        }
        if (!rootUri.startsWith(TestRootPrefix)) return emptyList()
        val files = rootUri.substringAfter("?files=", missingDelimiterValue = "")
            .split('|')
            .map { it.trim('/') }
            .filter { it.isNotBlank() }
            .filter { it.substringAfterLast('.', "").lowercase() in audioExt }
        val root = rootUri.substringBefore('?').trimEnd('/')
        return files.mapIndexed { index, file ->
            LocalAudioFile(
                uri = "$root/$file",
                sizeBytes = file.length.toLong(),
                modifiedAtMs = index.toLong(),
                filepath = file.substringAfterLast('/'),
            )
        }.sortedBy { it.uri }
    }

    actual suspend fun listAudioUris(rootUri: String): List<String> {
        return listAudioFiles(rootUri).map { it.uri }
    }

    actual suspend fun fileExists(uri: String): Boolean =
        when {
            uri.startsWith(WebFilePrefix) -> webLocalFileExists(uri)
            uri.startsWith(TestRootPrefix) -> uri.substringAfterLast('/').contains('.')
            else -> false
        }

    actual suspend fun readAudioMetadata(uri: String): AudioMetadata {
        val name = uri.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        if (uri.startsWith(WebFilePrefix)) {
            return readWebAudioMetadata(uri) ?: AudioMetadata(
                title = name.ifBlank { null },
                artist = null,
                album = null,
                durationMs = 0L,
                audioCodec = uri.substringBefore('?').substringAfterLast('.', "").takeIf { it.isNotBlank() },
            )
        }
        if (uri.startsWith(TestRootPrefix)) {
            return AudioMetadata(
                title = name.ifBlank { null },
                artist = "Web test files",
                album = "Web MP3 folder",
                durationMs = 0L,
                audioCodec = "mp3",
            )
        }
        return AudioMetadata(
            title = null,
            artist = null,
            album = null,
            durationMs = 0L,
        )
    }

    actual suspend fun readLyrics(uri: String): String? =
        if (uri.startsWith(WebFilePrefix)) {
            readWebTextSidecar(uri)?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }

    private const val TestRootPrefix = "phoebe-test://"
}

@Composable
actual fun rememberPickLocalFolder(onPicked: (String?) -> Unit): () -> Unit =
    remember(onPicked) {
        {
            webPickLocalFolder { rootUri ->
                onPicked(rootUri.takeIf { it.isNotBlank() })
            }
        }
    }

fun resolveWebLocalAudioUri(uri: String): String =
    if (uri.startsWith(WebFilePrefix)) webResolveLocalFileUri(uri).takeIf { it.isNotBlank() } ?: uri else uri

private const val WebRootPrefix = "phoebe-web-folder://"
private const val WebFilePrefix = "phoebe-web-file://"
private val audioExt = setOf("mp3", "m4a", "flac", "wav", "aac", "ogg", "opus")

private val WebJson = Json { ignoreUnknownKeys = true }

private fun parseWebAudioFiles(payload: String): List<LocalAudioFile> = runCatching {
    WebJson.parseToJsonElement(payload).jsonArray.mapNotNull { element ->
        val item = element.jsonObject
        val uri = item["uri"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val filepath = item["filepath"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: uri.substringAfterLast('/')
        val extension = filepath.substringAfterLast('.', "").lowercase()
        if (extension !in audioExt) return@mapNotNull null
        LocalAudioFile(
            uri = uri,
            sizeBytes = item["sizeBytes"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0L) ?: 0L,
            modifiedAtMs = item["modifiedAtMs"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0L) ?: 0L,
            filepath = filepath.substringAfterLast('/').ifBlank { filepath },
        )
    }.sortedBy { it.uri }
}.getOrDefault(emptyList())

private suspend fun readWebAudioMetadata(uri: String): AudioMetadata? {
    val payload = suspendCoroutine<String> { continuation ->
        webReadLocalAudioMetadata(uri) { result -> continuation.resume(result) }
    }.takeIf { it.isNotBlank() } ?: return null
    return parseWebAudioMetadata(payload)
}

private fun parseWebAudioMetadata(payload: String): AudioMetadata? = runCatching {
    val item = WebJson.parseToJsonElement(payload).jsonObject
    fun text(key: String): String? =
        item[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    AudioMetadata(
        title = text("title"),
        artist = text("artist"),
        album = text("album"),
        durationMs = item["durationMs"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0L) ?: 0L,
        year = text("year")?.filter { it.isDigit() }?.take(4)?.toIntOrNull(),
        genre = text("genre"),
        mood = text("mood"),
        style = text("style"),
        bitrateKbps = item["bitrateKbps"]?.jsonPrimitive?.longOrNull?.toInt()?.takeIf { it > 0 },
        audioCodec = text("audioCodec"),
    )
}.getOrNull()

private suspend fun readWebTextSidecar(uri: String): String? =
    suspendCoroutine<String> { continuation ->
        webReadLocalTextSidecar(uri) { result -> continuation.resume(result) }
    }.takeIf { it.isNotBlank() }

@JsFun(
    """
    (callback) => {
        const ensureStore = () => globalThis.__phoebeLocalFileStore ||
            (globalThis.__phoebeLocalFileStore = { folders: new Map(), files: new Map() });
        const store = ensureStore();
        const audioExt = new Set(["mp3", "m4a", "flac", "wav", "aac", "ogg", "opus"]);
        let input = null;
        let completed = false;
        const finish = (value) => {
            if (completed) return;
            completed = true;
            if (input) {
                input.onchange = null;
                input.oncancel = null;
                try { input.remove(); } catch (_) {}
            }
            callback(value || "");
        };
        const makeEntry = (file, relativePath, folderLabel) => {
            const raw = String(relativePath || file?.name || "track").replace(/\\/g, "/").replace(/^\/+/, "");
            const parts = raw.split("/").filter(Boolean);
            const fallbackName = String(file.name || "track");
            const cleanRelativePath = (parts.join("/") || fallbackName).replace(/^\/+/, "");
            const name = cleanRelativePath.split("/").filter(Boolean).pop() || fallbackName;
            const parentPath = cleanRelativePath.split("/").slice(0, -1).join("/");
            const ext = name.includes(".") ? name.split(".").pop().toLowerCase() : "";
            return { file, folderLabel: folderLabel || "Local files", relativePath: cleanRelativePath, name, parentPath, ext };
        };
        const commitEntries = (allEntries, folderLabelFallback) => {
            const audioEntries = allEntries.filter((entry) =>
                audioExt.has(entry.ext) || String(entry.file.type || "").startsWith("audio/")
            );
            if (!audioEntries.length) {
                return "";
            }
            const folderLabel = folderLabelFallback || allEntries[0]?.folderLabel || audioEntries[0].folderLabel || "Local files";
            const id = "web-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2);
            const rootUri = "phoebe-web-folder://" + id + "/" + encodeURIComponent(folderLabel);
            const textFiles = new Map();
            for (const entry of allEntries) {
                if (entry.ext === "lrc" || entry.ext === "txt") {
                    textFiles.set(entry.relativePath.toLowerCase(), entry);
                }
            }
            const files = audioEntries.map((entry) => {
                const encodedPath = entry.relativePath.split("/").map(encodeURIComponent).join("/");
                const uri = "phoebe-web-file://" + id + "/" + encodedPath;
                const stored = { ...entry, folderId: id, folderLabel, uri, objectUrl: null };
                store.files.set(uri, stored);
                return stored;
            }).sort((left, right) => left.uri.localeCompare(right.uri));
            store.folders.set(id, { id, rootUri, label: folderLabel, files, textFiles });
            return rootUri;
        };
        const chooseWithInput = () => {
            input = document.createElement("input");
            input.type = "file";
            input.multiple = true;
            input.accept = ".mp3,.m4a,.flac,.wav,.aac,.ogg,.opus,audio/*";
            input.style.display = "none";
            input.setAttribute("webkitdirectory", "");
            input.setAttribute("directory", "");
            input.onchange = () => {
                const picked = Array.from(input.files || []);
                if (!picked.length) {
                    finish("");
                    return;
                }
                const allEntries = picked.map((file) => {
                    const raw = String(file.webkitRelativePath || file.name || "track").replace(/\\/g, "/").replace(/^\/+/, "");
                    const parts = raw.split("/").filter(Boolean);
                    const folderLabel = parts.length > 1 ? parts[0] : "Local files";
                    const relativePath = (parts.length > 1 ? parts.slice(1) : parts).join("/") || file.name || "track";
                    return makeEntry(file, relativePath, folderLabel);
                });
                finish(commitEntries(allEntries, null));
            };
            input.oncancel = () => finish("");
            document.body.appendChild(input);
            input.click();
        };
        const chooseWithDirectoryPicker = async () => {
            if (typeof globalThis.showDirectoryPicker !== "function") return false;
            let directory;
            try {
                directory = await globalThis.showDirectoryPicker({ mode: "read" });
            } catch (error) {
                if (error && error.name === "AbortError") {
                    finish("");
                    return true;
                }
                console.warn("Phoebe local folder picker failed; falling back to file input.", error);
                return false;
            }
            const folderLabel = String(directory.name || "Local files");
            const allEntries = [];
            const walk = async (dir, prefix) => {
                for await (const item of dir.entries()) {
                    const name = item[0];
                    const handle = item[1];
                    const path = prefix ? prefix + "/" + name : name;
                    if (handle.kind === "directory") {
                        await walk(handle, path);
                    } else if (handle.kind === "file") {
                        const file = await handle.getFile();
                        allEntries.push(makeEntry(file, path, folderLabel));
                    }
                }
            };
            try {
                await walk(directory, "");
                finish(commitEntries(allEntries, folderLabel));
            } catch (error) {
                console.warn("Phoebe could not read the selected local folder.", error);
                finish("");
            }
            return true;
        };
        chooseWithDirectoryPicker()
            .then((handled) => {
                if (!handled && !completed) chooseWithInput();
            })
            .catch((error) => {
                console.warn("Phoebe local folder picker failed; falling back to file input.", error);
                if (!completed) chooseWithInput();
            });
    }
    """,
)
private external fun webPickLocalFolder(callback: (String) -> Unit)

@JsFun(
    """
    (rootUri) => {
        const store = globalThis.__phoebeLocalFileStore;
        const match = /^phoebe-web-folder:\/\/([^/]+)/.exec(String(rootUri || ""));
        const folder = match && store?.folders?.get(match[1]);
        if (!folder) return "[]";
        return JSON.stringify(folder.files.map((entry) => ({
            uri: entry.uri,
            sizeBytes: Number(entry.file.size || 0),
            modifiedAtMs: Number(entry.file.lastModified || 0),
            filepath: entry.name || entry.relativePath
        })));
    }
    """,
)
private external fun webListLocalAudioFiles(rootUri: String): String

@JsFun(
    """
    (uri) => {
        const store = globalThis.__phoebeLocalFileStore;
        return !!store?.files?.has(String(uri || ""));
    }
    """,
)
private external fun webLocalFileExists(uri: String): Boolean

@JsFun(
    """
    (uri) => {
        const store = globalThis.__phoebeLocalFileStore;
        const entry = store?.files?.get(String(uri || ""));
        if (!entry) return "";
        if (!entry.objectUrl) {
            entry.objectUrl = URL.createObjectURL(entry.file);
        }
        return entry.objectUrl;
    }
    """,
)
private external fun webResolveLocalFileUri(uri: String): String

@JsFun(
    """
    (uri, callback) => {
        const store = globalThis.__phoebeLocalFileStore;
        const entry = store?.files?.get(String(uri || ""));
        if (!entry) {
            callback("");
            return;
        }
        const ext = entry.ext || "";
        const title = String(entry.name || "").replace(/\.[^/.]+$/, "");
        const album = entry.parentPath ? entry.parentPath.split("/").pop() : entry.folderLabel;
        const result = {
            title,
            artist: null,
            album,
            durationMs: 0,
            audioCodec: ext || null
        };
        const readSyncSafe = (bytes, offset) =>
            ((bytes[offset] & 0x7f) << 21) |
            ((bytes[offset + 1] & 0x7f) << 14) |
            ((bytes[offset + 2] & 0x7f) << 7) |
            (bytes[offset + 3] & 0x7f);
        const readUint32 = (bytes, offset) =>
            (bytes[offset] << 24) |
            (bytes[offset + 1] << 16) |
            (bytes[offset + 2] << 8) |
            bytes[offset + 3];
        const decodeText = (bytes) => {
            if (!bytes || !bytes.length) return null;
            const encoding = bytes[0];
            let data = bytes.slice(1);
            let decoder = "iso-8859-1";
            if (encoding === 1) {
                if (data.length >= 2 && data[0] === 0xff && data[1] === 0xfe) {
                    decoder = "utf-16le";
                    data = data.slice(2);
                } else if (data.length >= 2 && data[0] === 0xfe && data[1] === 0xff) {
                    decoder = "utf-16be";
                    data = data.slice(2);
                } else {
                    decoder = "utf-16";
                }
            } else if (encoding === 2) {
                decoder = "utf-16be";
            } else if (encoding === 3) {
                decoder = "utf-8";
            }
            try {
                return new TextDecoder(decoder)
                    .decode(data)
                    .replace(/\u0000+$/g, "")
                    .trim() || null;
            } catch (_) {
                return null;
            }
        };
        const parseId3Tags = async (file) => {
            if (!file || !String(file.name || "").toLowerCase().endsWith(".mp3")) return {};
            const head = new Uint8Array(await file.slice(0, Math.min(file.size, 10)).arrayBuffer());
            if (head.length < 10 || head[0] !== 0x49 || head[1] !== 0x44 || head[2] !== 0x33) return {};
            const major = head[3];
            const tagSize = readSyncSafe(head, 6);
            const tagBytes = new Uint8Array(await file.slice(0, Math.min(file.size, tagSize + 10)).arrayBuffer());
            const tags = {};
            let offset = 10;
            const textFrameMap = {
                TIT2: "title",
                TPE1: "artist",
                TPE2: "artist",
                TALB: "album",
                TYER: "year",
                TDRC: "year",
                TCON: "genre",
                TMOO: "mood"
            };
            while (offset + 10 <= tagBytes.length) {
                const id = String.fromCharCode(tagBytes[offset], tagBytes[offset + 1], tagBytes[offset + 2], tagBytes[offset + 3]);
                if (!/^[A-Z0-9]{4}$/.test(id)) break;
                const frameSize = major === 4 ? readSyncSafe(tagBytes, offset + 4) : readUint32(tagBytes, offset + 4);
                if (frameSize <= 0 || offset + 10 + frameSize > tagBytes.length) break;
                const target = textFrameMap[id];
                if (target && (target !== "artist" || !tags.artist)) {
                    const value = decodeText(tagBytes.slice(offset + 10, offset + 10 + frameSize));
                    if (value) tags[target] = value;
                }
                offset += 10 + frameSize;
            }
            return tags;
        };
        let tagsDone = false;
        let durationDone = false;
        let settled = false;
        const maybeFinish = () => {
            if (tagsDone && durationDone) finish();
        };
        if (!entry.objectUrl) {
            entry.objectUrl = URL.createObjectURL(entry.file);
        }
        const audio = document.createElement("audio");
        const finish = () => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            audio.onloadedmetadata = null;
            audio.onerror = null;
            audio.removeAttribute("src");
            try { audio.load(); } catch (_) {}
            callback(JSON.stringify(result));
        };
        parseId3Tags(entry.file)
            .then((tags) => {
                if (tags.title) result.title = tags.title;
                if (tags.artist) result.artist = tags.artist;
                if (tags.album) result.album = tags.album;
                if (tags.year) result.year = tags.year;
                if (tags.genre) result.genre = tags.genre;
                if (tags.mood) result.mood = tags.mood;
            })
            .catch((error) => console.warn("Phoebe could not read local ID3 tags.", error))
            .finally(() => {
                tagsDone = true;
                maybeFinish();
            });
        const timer = setTimeout(() => {
            durationDone = true;
            maybeFinish();
        }, 2500);
        audio.preload = "metadata";
        audio.onloadedmetadata = () => {
            if (Number.isFinite(audio.duration) && audio.duration > 0) {
                result.durationMs = Math.round(audio.duration * 1000);
            }
            durationDone = true;
            maybeFinish();
        };
        audio.onerror = () => {
            durationDone = true;
            maybeFinish();
        };
        try {
            audio.src = entry.objectUrl;
            audio.load();
        } catch (_) {
            durationDone = true;
            maybeFinish();
        }
    }
    """,
)
private external fun webReadLocalAudioMetadata(uri: String, callback: (String) -> Unit)

@JsFun(
    """
    (uri, callback) => {
        const store = globalThis.__phoebeLocalFileStore;
        const entry = store?.files?.get(String(uri || ""));
        const folder = entry && store?.folders?.get(entry.folderId);
        if (!entry || !folder?.textFiles) {
            callback("");
            return;
        }
        const stem = entry.relativePath.replace(/\.[^/.]+$/, "").toLowerCase();
        const sidecar = folder.textFiles.get(stem + ".lrc") || folder.textFiles.get(stem + ".txt");
        if (!sidecar) {
            callback("");
            return;
        }
        sidecar.file.text()
            .then((text) => callback(String(text || "")))
            .catch(() => callback(""));
    }
    """,
)
private external fun webReadLocalTextSidecar(uri: String, callback: (String) -> Unit)
