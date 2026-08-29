package com.phoebe.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.phoebe.app.domain.PlexServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.browser.window
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.w3c.dom.events.Event
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

actual fun createPlatformHttpClient(): HttpClient = HttpClient(Js) {
    install(HttpTimeout) {
        requestTimeoutMillis = WebDownloadTimeoutMillis
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = WebDownloadTimeoutMillis
    }
    install(ContentNegotiation) {
        json(platformNetworkJson)
    }
}

actual suspend fun platformStreamHttpDownloadToStorage(
    url: String,
    targetPath: String,
    storage: PlatformStorage,
    bufferSize: Int,
    onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): String? = null

actual fun isDesktopPlatform(): Boolean = false

actual fun isIosPlatform(): Boolean = false

actual fun supportsPredictiveBack(): Boolean = false

actual fun currentNetworkMeteringStatus(): NetworkMeteringStatus =
    currentNetworkIdentity().metering

actual fun currentNetworkIdentity(): NetworkIdentity = webNetworkIdentity()

actual fun observeNetworkIdentity(): Flow<NetworkIdentity> = callbackFlow {
    val emit = {
        trySend(webNetworkIdentity())
        Unit
    }
    emit()
    val onlineListener: (Event) -> Unit = { emit() }
    val offlineListener: (Event) -> Unit = { emit() }
    window.addEventListener("online", onlineListener)
    window.addEventListener("offline", offlineListener)
    val connectionListener = { emit() }
    webNetworkConnectionAddChangeListener(connectionListener)
    awaitClose {
        window.removeEventListener("online", onlineListener)
        window.removeEventListener("offline", offlineListener)
        webNetworkConnectionRemoveChangeListener(connectionListener)
    }
}.distinctUntilChanged()

actual fun defaultDownloadWifiOnly(): Boolean = false

private fun webNetworkIdentity(): NetworkIdentity {
    if (!window.navigator.onLine) {
        return NetworkIdentity(
            transport = NetworkTransport.None,
            fingerprint = networkFingerprint(NetworkTransport.None, ""),
            metering = NetworkMeteringStatus(isMetered = true, isCellular = false),
        )
    }
    val type = webNetworkConnectionType().lowercase()
    val effectiveType = webNetworkConnectionEffectiveType().lowercase()
    val saveData = webNetworkConnectionSaveData()
    val transport = when {
        type == "cellular" -> NetworkTransport.Cellular
        type == "wifi" -> NetworkTransport.Wifi
        type == "ethernet" -> NetworkTransport.Ethernet
        type == "none" -> NetworkTransport.None
        else -> NetworkTransport.Other
    }
    val isCellular = transport == NetworkTransport.Cellular
    val isMetered = saveData || isCellular ||
        effectiveType == "slow-2g" || effectiveType == "2g" || effectiveType == "3g"
    val material = listOfNotNull(
        type.takeIf { it.isNotBlank() },
        effectiveType.takeIf { it.isNotBlank() },
        if (saveData) "save" else null,
    ).joinToString("|").ifBlank { transport.name.lowercase() }
    return NetworkIdentity(
        transport = transport,
        fingerprint = networkFingerprint(transport, material),
        metering = NetworkMeteringStatus(isMetered = isMetered, isCellular = isCellular),
    )
}

@JsFun(
    """
    () => {
      const connection = (typeof navigator !== 'undefined' && navigator.connection) ? navigator.connection : null;
      return connection && typeof connection.type === 'string' ? String(connection.type) : '';
    }
    """,
)
private external fun webNetworkConnectionType(): String

@JsFun(
    """
    () => {
      const connection = (typeof navigator !== 'undefined' && navigator.connection) ? navigator.connection : null;
      return connection && typeof connection.effectiveType === 'string' ? String(connection.effectiveType) : '';
    }
    """,
)
private external fun webNetworkConnectionEffectiveType(): String

@JsFun(
    """
    () => {
      const connection = (typeof navigator !== 'undefined' && navigator.connection) ? navigator.connection : null;
      return !!(connection && connection.saveData);
    }
    """,
)
private external fun webNetworkConnectionSaveData(): Boolean

@JsFun(
    """
    (callback) => {
      const connection = (typeof navigator !== 'undefined' && navigator.connection) ? navigator.connection : null;
      if (!connection || typeof connection.addEventListener !== 'function') return;
      connection.__phoebeNetworkChange = connection.__phoebeNetworkChange || new Map();
      const handler = () => callback();
      connection.__phoebeNetworkChange.set(callback, handler);
      connection.addEventListener('change', handler);
    }
    """,
)
private external fun webNetworkConnectionAddChangeListener(callback: () -> Unit)

@JsFun(
    """
    (callback) => {
      const connection = (typeof navigator !== 'undefined' && navigator.connection) ? navigator.connection : null;
      if (!connection || typeof connection.removeEventListener !== 'function') return;
      const map = connection.__phoebeNetworkChange;
      if (!map) return;
      const handler = map.get(callback);
      if (!handler) return;
      connection.removeEventListener('change', handler);
      map.delete(callback);
    }
    """,
)
private external fun webNetworkConnectionRemoveChangeListener(callback: () -> Unit)

actual suspend fun discoverJellyfinServers(): List<PlexServer> = emptyList()

actual class PlatformStorage actual constructor() {
    actual suspend fun readText(name: String): String? =
        window.localStorage.getItem(storageKey(name))

    actual suspend fun writeText(name: String, value: String) {
        window.localStorage.setItem(storageKey(name), value)
    }

    actual suspend fun delete(name: String) {
        window.localStorage.removeItem(storageKey(name))
    }

    actual suspend fun deleteUri(uri: String) {
        when {
            uri.startsWith("web-storage://") ->
                window.localStorage.removeItem(storageKey(decodeURIComponent(uri.removePrefix("web-storage://"))))
            uri.startsWith("web-download://") -> {
                webDownloadEnsureApi()
                suspendWebDownloadResult { callback ->
                    webDownloadDelete(decodeURIComponent(uri.removePrefix("web-download://")), callback)
                }
            }
        }
    }

    actual suspend fun readUriBytes(uri: String): ByteArray? {
        return when {
            uri.startsWith("web-storage://") -> {
                val encoded = window.localStorage.getItem(storageKey(decodeURIComponent(uri.removePrefix("web-storage://")))) ?: return null
                window.atob(encoded).toByteArrayFromBinaryString()
            }
            uri.startsWith("web-download://") -> {
                webDownloadEnsureApi()
                val encoded = suspendWebDownloadResult { callback ->
                    webDownloadReadBase64(decodeURIComponent(uri.removePrefix("web-download://"))) { result ->
                        callback(result)
                    }
                }.takeIf { it.isNotBlank() } ?: return null
                window.atob(encoded).toByteArrayFromBinaryString()
            }
            else -> null
        }
    }

    actual suspend fun readBytes(name: String): ByteArray? {
        val encoded = window.localStorage.getItem(storageKey(name)) ?: return null
        return window.atob(encoded).toByteArrayFromBinaryString()
    }

    actual suspend fun writeBytes(name: String, bytes: ByteArray): String {
        val encoded = window.btoa(bytes.toBinaryString())
        window.localStorage.setItem(storageKey(name), encoded)
        return "web-storage://${encodeURIComponent(name)}"
    }

    actual suspend fun writeByteStream(name: String, write: suspend (PlatformByteSink) -> Unit): String {
        webDownloadEnsureApi()
        suspendWebDownloadResult { callback -> webDownloadStart(name, callback) }
        return try {
            write(
                object : PlatformByteSink {
                    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
                        if (length <= 0) return
                        val binary = buffer.toBinaryString(offset, length)
                        suspendWebDownloadResult { callback -> webDownloadAppend(name, binary, callback) }
                    }
                },
            )
            suspendWebDownloadResult { callback -> webDownloadFinish(name, callback) }
            "web-download://${encodeURIComponent(name)}"
        } catch (error: Throwable) {
            suspendWebDownloadResult { callback -> webDownloadDelete(name, callback) }
            throw error
        }
    }

    actual suspend fun readDownloadDirectory(): String? =
        window.localStorage.getItem(storageKey(DownloadDirectoryKey))

    actual suspend fun writeDownloadDirectory(uri: String?) {
        if (uri.isNullOrBlank()) window.localStorage.removeItem(storageKey(DownloadDirectoryKey))
        else window.localStorage.setItem(storageKey(DownloadDirectoryKey), uri)
    }

    actual fun defaultDownloadDirectoryLabel(): String = "Browser storage"

    private fun storageKey(name: String): String = "phoebe:$name"

    private suspend fun suspendWebDownloadResult(
        block: (callback: (String) -> Unit) -> Unit,
    ): String {
        val result = suspendCoroutine<String> { continuation ->
            block { value -> continuation.resume(value) }
        }
        if (result.startsWith(WebDownloadErrorPrefix)) {
            error(result.removePrefix(WebDownloadErrorPrefix).ifBlank { "Browser download storage failed." })
        }
        return result
    }
}

actual class DownloadNotifier actual constructor() {
    actual suspend fun notifyDownloadFinished(title: String, body: String): Boolean {
        if (!browserNotificationsSupported()) return false
        val granted = when (browserNotificationPermission()) {
            "granted" -> true
            "default" -> requestBrowserNotificationPermissionSuspending() == "granted"
            else -> false
        }
        if (!granted) return false
        showBrowserNotification(title, body)
        return true
    }
}

@Composable
actual fun rememberPickDownloadDirectory(onPicked: (String?) -> Unit): () -> Unit =
    remember(onPicked) {
        { onPicked(null) }
    }

private const val DownloadDirectoryKey = "download-location"
private const val WebDownloadTimeoutMillis = 15 * 60_000L

actual fun openExternalUrl(url: String) {
    window.open(url, target = "_blank")
}

actual fun currentTimeMs(): Long = jsDateNow().toLong()

actual fun prefersReducedArtworkEffects(): Boolean = true

actual fun remoteArtworkCacheMaxEstimatedBytes(): Long = 8L * 1024L * 1024L

actual fun remoteArtworkLoadParallelism(): Int = 4

actual fun catalogTrackIndexParallelism(): Int = 1

private var webPlaybackMemoryPressureActive = false

actual fun configurePlaybackMemoryPressure(active: Boolean) {
    webPlaybackMemoryPressureActive = active
}

actual fun shouldDeferCatalogMemoryUpdates(): Boolean = webPlaybackMemoryPressureActive

actual fun downloadParallelism(): Int = 3

actual fun schedulePlatformDownloadRunner() = Unit

actual fun requestNotificationPermission() {}

actual fun isDebugBuild(): Boolean = wasmDebugBuildEnabled()

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
    () => {
      if (typeof globalThis.PHOEBE_DEBUG === 'boolean') return globalThis.PHOEBE_DEBUG;
      if (typeof location !== 'undefined') {
        const host = location.hostname;
        if (host === 'localhost' || host === '127.0.0.1') return true;
      }
      return false;
    }
    """,
)
private external fun wasmDebugBuildEnabled(): Boolean

internal actual fun platformLog(tag: String, message: String) {
    println("[$tag] $message")
}

private val platformNetworkJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private fun ByteArray.toBinaryString(): String =
    joinToString(separator = "") { (it.toInt() and 0xff).toChar().toString() }

private fun ByteArray.toBinaryString(offset: Int, length: Int): String =
    buildString(length) {
        val end = (offset + length).coerceAtMost(this@toBinaryString.size)
        for (index in offset.coerceAtLeast(0) until end) {
            append((this@toBinaryString[index].toInt() and 0xff).toChar())
        }
    }

private fun String.toByteArrayFromBinaryString(): ByteArray =
    ByteArray(length) { index -> this[index].code.toByte() }

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => Date.now()")
private external fun jsDateNow(): Double

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(value) => encodeURIComponent(value)")
private external fun encodeURIComponent(value: String): String

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(value) => decodeURIComponent(value)")
private external fun decodeURIComponent(value: String): String

private const val WebDownloadErrorPrefix = "ERROR:"

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
    () => {
      if (globalThis.__phoebeDownloadApi) return;
      const dbName = "phoebe-downloads";
      const storeName = "files";
      const chunkStoreName = "chunks";
      const writers = new Map();
      const finish = (callback, value) => callback(String(value || ""));
      const fail = (callback, error) => {
        const message = error && error.message ? error.message : String(error || "storage error");
        finish(callback, "ERROR:" + message);
      };
      const objectUrlCache = () =>
        globalThis.__phoebeDownloadObjectUrls || (globalThis.__phoebeDownloadObjectUrls = new Map());
      const revokeObjectUrl = (key) => {
        const cache = objectUrlCache();
        const url = cache.get(key);
        if (url) {
          try { URL.revokeObjectURL(url); } catch (error) {}
          cache.delete(key);
        }
      };
      const audioMimeForName = (name) => {
        const lower = String(name || "").toLowerCase();
        if (lower.endsWith(".mp3") || lower.endsWith(".mpeg") || lower.endsWith(".mpga")) return "audio/mpeg";
        if (lower.endsWith(".m4a") || lower.endsWith(".aac")) return "audio/mp4";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".opus")) return "audio/ogg; codecs=opus";
        if (lower.endsWith(".wav")) return "audio/wav";
        return "audio/*";
      };
      const openDb = () => new Promise((resolve, reject) => {
        const request = indexedDB.open(dbName, 2);
        request.onupgradeneeded = () => {
          const db = request.result;
          if (!db.objectStoreNames.contains(storeName)) db.createObjectStore(storeName, { keyPath: "name" });
          if (!db.objectStoreNames.contains(chunkStoreName)) db.createObjectStore(chunkStoreName, { keyPath: ["name", "index"] });
        };
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      });
      const chunkRange = (key) => IDBKeyRange.bound([key, 0], [key, Number.MAX_SAFE_INTEGER]);
      const deleteIdbChunks = (chunks, key, reject) => {
        const cursorRequest = chunks.openCursor(chunkRange(key));
        cursorRequest.onsuccess = () => {
          const cursor = cursorRequest.result;
          if (!cursor) return;
          cursor.delete();
          cursor.continue();
        };
        cursorRequest.onerror = () => reject(cursorRequest.error);
      };
      const idbStart = (name) => openDb().then((db) => new Promise((resolve, reject) => {
        const key = String(name || "");
        const tx = db.transaction([storeName, chunkStoreName], "readwrite");
        const files = tx.objectStore(storeName);
        const chunks = tx.objectStore(chunkStoreName);
        files.put({ name: key, size: 0, complete: false, chunkCount: 0 });
        deleteIdbChunks(chunks, key, reject);
        tx.oncomplete = () => { db.close(); resolve(""); };
        tx.onerror = () => { db.close(); reject(tx.error); };
      }));
      const idbAppend = (name, binary) => openDb().then((db) => new Promise((resolve, reject) => {
        const tx = db.transaction([storeName, chunkStoreName], "readwrite");
        const store = tx.objectStore(storeName);
        const chunks = tx.objectStore(chunkStoreName);
        const key = String(name || "");
        const get = store.get(key);
        get.onsuccess = () => {
          const data = String(binary || "");
          const record = get.result || { name: key, size: 0, complete: false, chunkCount: 0 };
          const index = Number(record.chunkCount || 0);
          chunks.put({ name: key, index, binary: data });
          record.chunkCount = index + 1;
          record.size = Number(record.size || 0) + data.length;
          record.complete = false;
          store.put(record);
        };
        get.onerror = () => reject(get.error);
        tx.oncomplete = () => { db.close(); resolve(""); };
        tx.onerror = () => { db.close(); reject(tx.error); };
      }));
      const idbFinish = (name) => openDb().then((db) => new Promise((resolve, reject) => {
        const tx = db.transaction(storeName, "readwrite");
        const store = tx.objectStore(storeName);
        const key = String(name || "");
        const get = store.get(key);
        get.onsuccess = () => {
          const record = get.result || { name: key, chunks: [], size: 0, complete: false };
          record.complete = true;
          store.put(record);
        };
        get.onerror = () => reject(get.error);
        tx.oncomplete = () => { db.close(); resolve(""); };
        tx.onerror = () => { db.close(); reject(tx.error); };
      }));
      const idbDelete = (name) => openDb().then((db) => new Promise((resolve, reject) => {
        const key = String(name || "");
        const tx = db.transaction([storeName, chunkStoreName], "readwrite");
        tx.objectStore(storeName).delete(key);
        const chunks = tx.objectStore(chunkStoreName);
        deleteIdbChunks(chunks, key, reject);
        tx.oncomplete = () => { db.close(); resolve(""); };
        tx.onerror = () => { db.close(); reject(tx.error); };
      }));
      const idbReadBinaryParts = (name) => openDb().then((db) => new Promise((resolve, reject) => {
        const key = String(name || "");
        const tx = db.transaction([storeName, chunkStoreName], "readonly");
        let settled = false;
        const done = (value) => {
          if (settled) return;
          settled = true;
          db.close();
          resolve(value);
        };
        const get = tx.objectStore(storeName).get(key);
        get.onsuccess = () => {
          const record = get.result;
          if (!record || !record.complete) {
            done(null);
            return;
          }
          if (record.chunks) {
            done(record.chunks.map((chunk) => String(chunk || "")));
            return;
          }
          const parts = [];
          const cursorRequest = tx.objectStore(chunkStoreName).openCursor(chunkRange(key));
          cursorRequest.onsuccess = () => {
            const cursor = cursorRequest.result;
            if (!cursor) return;
            parts.push(String(cursor.value && cursor.value.binary || ""));
            cursor.continue();
          };
          cursorRequest.onerror = () => {
            if (!settled) {
              settled = true;
              db.close();
              reject(cursorRequest.error);
            }
          };
          tx.oncomplete = () => done(parts);
        };
        get.onerror = () => {
          if (!settled) {
            settled = true;
            db.close();
            reject(get.error);
          }
        };
        tx.onerror = () => {
          if (!settled) {
            settled = true;
            db.close();
            reject(tx.error);
          }
        };
      }));
      const idbReadBase64 = async (name) => {
        const parts = await idbReadBinaryParts(name);
        return parts ? btoa(parts.join("")) : "";
      };
      const idbReadBlob = async (name) => {
        const parts = await idbReadBinaryParts(name);
        if (!parts || parts.length === 0) return null;
        return new Blob(parts.map(binaryToBytes), { type: audioMimeForName(name) });
      };
      const opfsAvailable = () =>
        typeof navigator !== "undefined" &&
        navigator.storage &&
        typeof navigator.storage.getDirectory === "function";
      const safePart = (part) => {
        const value = String(part || "").replace(/[^A-Za-z0-9._-]+/g, "_").replace(/^_+|_+$/g, "");
        return value || "item";
      };
      const safePathParts = (name) => {
        const parts = String(name || "download").split("/").map(safePart).filter(Boolean);
        return parts.length ? parts : ["download"];
      };
      const opfsParent = async (name, create) => {
        const root = await navigator.storage.getDirectory();
        const parts = safePathParts(name);
        let dir = root;
        for (let index = 0; index < parts.length - 1; index += 1) {
          dir = await dir.getDirectoryHandle(parts[index], { create });
        }
        return { dir, leaf: parts[parts.length - 1] };
      };
      const opfsFileHandle = async (name, create) => {
        const parent = await opfsParent(name, create);
        return parent.dir.getFileHandle(parent.leaf, { create });
      };
      const opfsRemove = async (name) => {
        if (!opfsAvailable()) return;
        try {
          const parent = await opfsParent(name, false);
          await parent.dir.removeEntry(parent.leaf);
        } catch (error) {
          if (!error || error.name !== "NotFoundError") throw error;
        }
      };
      const binaryToBytes = (binary) => {
        const text = String(binary || "");
        const bytes = new Uint8Array(text.length);
        for (let index = 0; index < text.length; index += 1) {
          bytes[index] = text.charCodeAt(index) & 255;
        }
        return bytes;
      };
      const bytesToBase64 = (bytes) => {
        let binary = "";
        const step = 0x8000;
        for (let index = 0; index < bytes.length; index += step) {
          binary += String.fromCharCode.apply(null, bytes.subarray(index, index + step));
        }
        return btoa(binary);
      };
      const opfsStart = async (name) => {
        const key = String(name || "");
        const previous = writers.get(key);
        if (previous) {
          writers.delete(key);
          try {
            await previous.abort();
          } catch (error) {
          }
        }
        await opfsRemove(name);
        const handle = await opfsFileHandle(name, true);
        const writable = await handle.createWritable();
        writers.set(key, writable);
      };
      const opfsAppend = async (name, binary) => {
        const writer = writers.get(String(name || ""));
        if (!writer) throw new Error("Browser file writer is not open.");
        await writer.write(binaryToBytes(binary));
      };
      const opfsFinish = async (name) => {
        const key = String(name || "");
        const writer = writers.get(key);
        if (!writer) return;
        writers.delete(key);
        await writer.close();
      };
      const opfsDelete = async (name) => {
        const key = String(name || "");
        const writer = writers.get(key);
        if (writer) {
          writers.delete(key);
          try {
            await writer.abort();
          } catch (error) {
          }
        }
        await opfsRemove(name);
      };
      const opfsReadBase64 = async (name) => {
        const handle = await opfsFileHandle(name, false);
        const file = await handle.getFile();
        const buffer = await file.arrayBuffer();
        return bytesToBase64(new Uint8Array(buffer));
      };
      const opfsObjectUrl = async (name) => {
        const handle = await opfsFileHandle(name, false);
        const file = await handle.getFile();
        const blob = file.type ? file : new Blob([file], { type: audioMimeForName(name) });
        return URL.createObjectURL(blob);
      };
      const run = (callback, block) => {
        block().then((value) => finish(callback, value)).catch((error) => fail(callback, error));
      };
      globalThis.__phoebeDownloadApi = {
        start: (name, callback) => run(callback, async () => {
          const key = String(name || "");
          revokeObjectUrl(key);
          if (opfsAvailable()) {
            try {
              await opfsStart(key);
              await idbDelete(key).catch(() => "");
              return "";
            } catch (error) {
              writers.delete(key);
              await opfsDelete(key).catch(() => "");
            }
          }
          await idbStart(key);
          return "";
        }),
        append: (name, binary, callback) => run(callback, async () => {
          const key = String(name || "");
          if (writers.has(key)) {
            await opfsAppend(key, binary);
          } else {
            await idbAppend(key, binary);
          }
          return "";
        }),
        finish: (name, callback) => run(callback, async () => {
          const key = String(name || "");
          if (writers.has(key)) {
            await opfsFinish(key);
          } else {
            await idbFinish(key);
          }
          return "";
        }),
        delete: (name, callback) => run(callback, async () => {
          const key = String(name || "");
          revokeObjectUrl(key);
          await opfsDelete(key).catch(() => "");
          await idbDelete(key).catch(() => "");
          return "";
        }),
        readBase64: (name, callback) => {
          const key = String(name || "");
          (async () => {
            if (opfsAvailable()) {
              try {
                const encoded = await opfsReadBase64(key);
                if (encoded) return encoded;
              } catch (error) {
              }
            }
            return idbReadBase64(key).catch(() => "");
          })().then((value) => finish(callback, value)).catch(() => finish(callback, ""));
        },
        objectUrl: (name, callback) => {
          const key = String(name || "");
          const cache = objectUrlCache();
          const cached = cache.get(key);
          if (cached) {
            finish(callback, cached);
            return;
          }
          (async () => {
            if (opfsAvailable()) {
              try {
                const url = await opfsObjectUrl(key);
                if (url) {
                  cache.set(key, url);
                  return url;
                }
              } catch (error) {
              }
            }
            const blob = await idbReadBlob(key).catch(() => null);
            if (!blob) return "";
            const url = URL.createObjectURL(blob);
            cache.set(key, url);
            return url;
          })().then((value) => finish(callback, value)).catch(() => finish(callback, ""));
        },
      };
    }
    """,
)
private external fun webDownloadEnsureApi()

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(name, callback) => { globalThis.__phoebeDownloadApi.start(name, callback); }")
private external fun webDownloadStart(name: String, callback: (String) -> Unit)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(name, binary, callback) => { globalThis.__phoebeDownloadApi.append(name, binary, callback); }")
private external fun webDownloadAppend(name: String, binary: String, callback: (String) -> Unit)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(name, callback) => { globalThis.__phoebeDownloadApi.finish(name, callback); }")
private external fun webDownloadFinish(name: String, callback: (String) -> Unit)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(name, callback) => { globalThis.__phoebeDownloadApi.delete(name, callback); }")
private external fun webDownloadDelete(name: String, callback: (String) -> Unit)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(name, callback) => { globalThis.__phoebeDownloadApi.readBase64(name, callback); }")
private external fun webDownloadReadBase64(name: String, callback: (String) -> Unit)

fun resolveWebDownloadObjectUrl(uri: String, callback: (String) -> Unit) {
    webDownloadEnsureApi()
    webDownloadObjectUrl(decodeURIComponent(uri.removePrefix("web-download://"))) { result ->
        callback(result.takeUnless { it.startsWith(WebDownloadErrorPrefix) }.orEmpty())
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(name, callback) => { globalThis.__phoebeDownloadApi.objectUrl(name, callback); }")
private external fun webDownloadObjectUrl(name: String, callback: (String) -> Unit)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => typeof Notification !== 'undefined'")
private external fun browserNotificationsSupported(): Boolean

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => Notification.permission")
private external fun browserNotificationPermission(): String

private suspend fun requestBrowserNotificationPermissionSuspending(): String =
    suspendCoroutine { continuation ->
        requestBrowserNotificationPermission { result -> continuation.resume(result) }
    }

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(callback) => { Notification.requestPermission().then(callback); }")
private external fun requestBrowserNotificationPermission(callback: (String) -> Unit)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(title, body) => { new Notification(title, { body }); }")
private external fun showBrowserNotification(title: String, body: String)

/**
 * No-op: this platform surfaces now-playing through its own mechanism, so a
 * notification would duplicate it.
 */
actual class NowPlayingNotifier actual constructor() {
    actual suspend fun notifyNowPlaying(
        title: String,
        artist: String,
        album: String,
        artworkUrl: String,
    ): Boolean = false
}
