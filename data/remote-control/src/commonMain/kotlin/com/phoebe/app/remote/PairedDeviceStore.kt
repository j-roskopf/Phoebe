package com.phoebe.app.remote

import com.phoebe.app.platform.SecureCredentialKey
import com.phoebe.app.platform.SecureCredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class PairedRemoteDevice(
    val deviceId: String,
    val deviceName: String,
    val pairedAtMs: Long,
    val secret: String,
)

class PairedDeviceStore(
    private val secureCredentialStore: SecureCredentialStore,
) {
    private val mutex = Mutex()
    private val mutablePairedDevices = MutableStateFlow<List<PairedRemoteDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedRemoteDevice>> = mutablePairedDevices.asStateFlow()

    suspend fun restore() {
        mutex.withLock {
            val json = secureCredentialStore.read(SecureCredentialKey.RemoteControlPairedDevices)
            val list = if (!json.isNullOrBlank()) {
                runCatching {
                    RemoteJson.decodeFromString<List<PairedRemoteDevice>>(json)
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            mutablePairedDevices.value = list
        }
    }

    suspend fun findDevice(deviceId: String): PairedRemoteDevice? {
        return mutex.withLock {
            mutablePairedDevices.value.firstOrNull { it.deviceId == deviceId }
        }
    }

    suspend fun addOrUpdateDevice(device: PairedRemoteDevice) {
        mutex.withLock {
            val current = mutablePairedDevices.value.filterNot { it.deviceId == device.deviceId }
            val updated = current + device
            mutablePairedDevices.value = updated
            saveLocked(updated)
        }
    }

    suspend fun revokeDevice(deviceId: String) {
        mutex.withLock {
            val updated = mutablePairedDevices.value.filterNot { it.deviceId == deviceId }
            mutablePairedDevices.value = updated
            saveLocked(updated)
        }
    }

    suspend fun clearAll() {
        mutex.withLock {
            mutablePairedDevices.value = emptyList()
            secureCredentialStore.delete(SecureCredentialKey.RemoteControlPairedDevices)
        }
    }

    private suspend fun saveLocked(devices: List<PairedRemoteDevice>) {
        val json = RemoteJson.encodeToString<List<PairedRemoteDevice>>(devices)
        secureCredentialStore.write(SecureCredentialKey.RemoteControlPairedDevices, json)
    }

    // Client secrets for storing pairing secrets when connecting to hosts as a controller
    suspend fun getClientSecret(hostDeviceId: String): String? {
        val json = secureCredentialStore.read(SecureCredentialKey.RemoteControlClientSecrets) ?: return null
        val map = runCatching {
            RemoteJson.decodeFromString<Map<String, String>>(json)
        }.getOrNull() ?: return null
        return map[hostDeviceId]
    }

    suspend fun saveClientSecret(hostDeviceId: String, secret: String) {
        val json = secureCredentialStore.read(SecureCredentialKey.RemoteControlClientSecrets)
        val current = if (!json.isNullOrBlank()) {
            runCatching {
                RemoteJson.decodeFromString<Map<String, String>>(json)
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        val updated = current + (hostDeviceId to secret)
        secureCredentialStore.write(
            SecureCredentialKey.RemoteControlClientSecrets,
            RemoteJson.encodeToString<Map<String, String>>(updated),
        )
    }

    suspend fun removeClientSecret(hostDeviceId: String) {
        val json = secureCredentialStore.read(SecureCredentialKey.RemoteControlClientSecrets) ?: return
        val current = runCatching {
            RemoteJson.decodeFromString<Map<String, String>>(json)
        }.getOrNull() ?: return
        val updated = current - hostDeviceId
        secureCredentialStore.write(
            SecureCredentialKey.RemoteControlClientSecrets,
            RemoteJson.encodeToString<Map<String, String>>(updated),
        )
    }
}
