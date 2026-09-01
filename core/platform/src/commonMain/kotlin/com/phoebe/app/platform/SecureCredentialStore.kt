package com.phoebe.app.platform

import com.phoebe.app.domain.ListenBrainzCredentialStorageStatus

enum class SecureCredentialKey {
    ListenBrainzUserToken,
    LastFmSharedSecret,
    LastFmSessionKey,
    RemoteControlPairedDevices,
    RemoteControlClientSecrets,
}

data class SecureCredentialAvailability(
    val status: ListenBrainzCredentialStorageStatus,
    val description: String,
) {
    val canWrite: Boolean
        get() = status != ListenBrainzCredentialStorageStatus.Unavailable

    companion object {
        val Unavailable = SecureCredentialAvailability(
            status = ListenBrainzCredentialStorageStatus.Unavailable,
            description = "Secure credential storage is unavailable.",
        )
    }
}

interface SecureCredentialStore {
    val availability: SecureCredentialAvailability
    suspend fun read(key: SecureCredentialKey): String?
    suspend fun write(key: SecureCredentialKey, value: String)
    suspend fun delete(key: SecureCredentialKey)
}

expect fun createSecureCredentialStore(): SecureCredentialStore
