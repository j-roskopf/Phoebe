@file:OptIn(ExperimentalWasmJsInterop::class)

package com.phoebe.app.platform

import com.phoebe.app.domain.ListenBrainzCredentialStorageStatus
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

actual fun createSecureCredentialStore(): SecureCredentialStore = WebSecureCredentialStore()

private class WebSecureCredentialStore : SecureCredentialStore {
    init {
        webSecureCredentialEnsureApi()
    }

    private val webAvailability = webSecureCredentialAvailability()

    override val availability: SecureCredentialAvailability = SecureCredentialAvailability(
        status = when (webAvailability) {
            WebCredentialAvailabilityPersistent -> ListenBrainzCredentialStorageStatus.PersistentBrowser
            else -> ListenBrainzCredentialStorageStatus.SessionOnly
        },
        description = when (webAvailability) {
            WebCredentialAvailabilityPersistent -> "Browser-persistent encrypted storage"
            else -> "Session-only browser memory"
        },
    )

    override suspend fun read(key: SecureCredentialKey): String? {
        val result = suspendWebCredentialResult { callback ->
            webSecureCredentialRead(key.webStorageName, callback)
        }
        return when {
            result == WebCredentialMissing -> null
            result.startsWith(WebCredentialValuePrefix) ->
                decodeURIComponent(result.removePrefix(WebCredentialValuePrefix))
            else -> error("Unexpected browser credential storage response.")
        }
    }

    override suspend fun write(key: SecureCredentialKey, value: String) {
        suspendWebCredentialResult { callback ->
            webSecureCredentialWrite(key.webStorageName, value, callback)
        }
    }

    override suspend fun delete(key: SecureCredentialKey) {
        suspendWebCredentialResult { callback ->
            webSecureCredentialDelete(key.webStorageName, callback)
        }
    }

    private suspend fun suspendWebCredentialResult(
        block: (callback: (String) -> Unit) -> Unit,
    ): String {
        val result = suspendCoroutine<String> { continuation ->
            block { value -> continuation.resume(value) }
        }
        if (result.startsWith(WebCredentialErrorPrefix)) {
            error(result.removePrefix(WebCredentialErrorPrefix).ifBlank { "Browser credential storage failed." })
        }
        return result
    }
}

private val SecureCredentialKey.webStorageName: String
    get() = when (this) {
        SecureCredentialKey.ListenBrainzUserToken -> "listenbrainz-user-token"
        SecureCredentialKey.LastFmSharedSecret -> "lastfm-shared-secret"
        SecureCredentialKey.LastFmSessionKey -> "lastfm-session-key"
        SecureCredentialKey.RemoteControlPairedDevices -> "remote-control-paired-devices"
        SecureCredentialKey.RemoteControlClientSecrets -> "remote-control-client-secrets"
    }

private const val WebCredentialAvailabilityPersistent = "persistent"
private const val WebCredentialMissing = "MISSING"
private const val WebCredentialValuePrefix = "VALUE:"
private const val WebCredentialErrorPrefix = "ERROR:"

@JsFun(
    """
    () => {
      if (globalThis.__phoebeSecureCredentialStore) return;

      const dbName = "phoebe-secure-credentials";
      const storeName = "keys";
      const keyName = "listenbrainz-token-aes-gcm";
      const storagePrefix = "phoebe.secureCredential.";
      const memory = new Map();
      let cachedKeyPromise = null;

      const finish = (callback, value) => callback(String(value || ""));
      const fail = (callback, error) => {
        const message = error && error.message ? error.message : String(error || "storage error");
        finish(callback, "ERROR:" + message);
      };
      const hasPersistentSupport = () => {
        try {
          if (!globalThis.isSecureContext) return false;
          if (!globalThis.crypto || !crypto.subtle || !crypto.getRandomValues) return false;
          if (!globalThis.indexedDB || !globalThis.localStorage) return false;
          if (!globalThis.TextEncoder || !globalThis.TextDecoder) return false;
          const probeKey = storagePrefix + "__probe";
          localStorage.setItem(probeKey, "1");
          localStorage.removeItem(probeKey);
          return true;
        } catch (error) {
          return false;
        }
      };
      const storageKey = (name) => storagePrefix + String(name || "");
      const openDb = () => new Promise((resolve, reject) => {
        const request = indexedDB.open(dbName, 1);
        request.onupgradeneeded = () => {
          const db = request.result;
          if (!db.objectStoreNames.contains(storeName)) db.createObjectStore(storeName);
        };
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      });
      const idbGet = (name) => openDb().then((db) => new Promise((resolve, reject) => {
        const tx = db.transaction(storeName, "readonly");
        const request = tx.objectStore(storeName).get(name);
        request.onsuccess = () => resolve(request.result || null);
        request.onerror = () => reject(request.error);
        tx.oncomplete = () => db.close();
        tx.onerror = () => { db.close(); reject(tx.error); };
      }));
      const idbPut = (name, value) => openDb().then((db) => new Promise((resolve, reject) => {
        const tx = db.transaction(storeName, "readwrite");
        tx.objectStore(storeName).put(value, name);
        tx.oncomplete = () => { db.close(); resolve(""); };
        tx.onerror = () => { db.close(); reject(tx.error); };
      }));
      const persistentKey = () => {
        if (cachedKeyPromise) return cachedKeyPromise;
        cachedKeyPromise = (async () => {
          const existing = await idbGet(keyName);
          if (existing) return existing;
          const key = await crypto.subtle.generateKey(
            { name: "AES-GCM", length: 256 },
            false,
            ["encrypt", "decrypt"],
          );
          await idbPut(keyName, key);
          return key;
        })().catch((error) => {
          cachedKeyPromise = null;
          throw error;
        });
        return cachedKeyPromise;
      };
      const toBase64 = (bytes) => {
        let binary = "";
        for (let i = 0; i < bytes.length; i += 0x8000) {
          binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
        }
        return btoa(binary);
      };
      const fromBase64 = (value) => {
        const binary = atob(String(value || ""));
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
        return bytes;
      };
      const encrypt = async (value) => {
        const key = await persistentKey();
        const iv = crypto.getRandomValues(new Uint8Array(12));
        const data = new TextEncoder().encode(String(value || ""));
        const encrypted = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, data));
        return JSON.stringify({ version: 1, iv: toBase64(iv), ciphertext: toBase64(encrypted) });
      };
      const decrypt = async (payload) => {
        const item = JSON.parse(String(payload || ""));
        if (!item || item.version !== 1) throw new Error("Unsupported credential payload.");
        const key = await persistentKey();
        const iv = fromBase64(item.iv);
        const ciphertext = fromBase64(item.ciphertext);
        const decrypted = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, key, ciphertext);
        return new TextDecoder().decode(decrypted);
      };

      globalThis.__phoebeSecureCredentialStore = {
        available: () => hasPersistentSupport() ? "persistent" : "session",
        read: (name, callback) => {
          if (!hasPersistentSupport()) {
            finish(callback, memory.has(name) ? "VALUE:" + encodeURIComponent(memory.get(name)) : "MISSING");
            return;
          }
          const key = storageKey(name);
          let payload = null;
          try {
            payload = localStorage.getItem(key);
          } catch (error) {
            fail(callback, error);
            return;
          }
          if (!payload) {
            finish(callback, "MISSING");
            return;
          }
          decrypt(payload)
            .then((value) => finish(callback, "VALUE:" + encodeURIComponent(value)))
            .catch(() => {
              try { localStorage.removeItem(key); } catch (error) {}
              finish(callback, "MISSING");
            });
        },
        write: (name, value, callback) => {
          if (!hasPersistentSupport()) {
            memory.set(name, String(value || ""));
            finish(callback, "OK");
            return;
          }
          encrypt(value)
            .then((payload) => {
              localStorage.setItem(storageKey(name), payload);
              finish(callback, "OK");
            })
            .catch((error) => fail(callback, error));
        },
        delete: (name, callback) => {
          memory.delete(name);
          if (hasPersistentSupport()) {
            try {
              localStorage.removeItem(storageKey(name));
            } catch (error) {
              fail(callback, error);
              return;
            }
          }
          finish(callback, "OK");
        },
      };
    }
    """,
)
private external fun webSecureCredentialEnsureApi()

@JsFun("() => globalThis.__phoebeSecureCredentialStore.available()")
private external fun webSecureCredentialAvailability(): String

@JsFun("(name, callback) => { globalThis.__phoebeSecureCredentialStore.read(name, callback); }")
private external fun webSecureCredentialRead(name: String, callback: (String) -> Unit)

@JsFun("(name, value, callback) => { globalThis.__phoebeSecureCredentialStore.write(name, value, callback); }")
private external fun webSecureCredentialWrite(name: String, value: String, callback: (String) -> Unit)

@JsFun("(name, callback) => { globalThis.__phoebeSecureCredentialStore.delete(name, callback); }")
private external fun webSecureCredentialDelete(name: String, callback: (String) -> Unit)

@JsFun("(value) => decodeURIComponent(value)")
private external fun decodeURIComponent(value: String): String
