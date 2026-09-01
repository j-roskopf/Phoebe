package com.phoebe.app.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.phoebe.app.AndroidContextHolder
import com.phoebe.app.domain.ListenBrainzCredentialStorageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual fun createSecureCredentialStore(): SecureCredentialStore = AndroidSecureCredentialStore()

private class AndroidSecureCredentialStore : SecureCredentialStore {
    override val availability: SecureCredentialAvailability = SecureCredentialAvailability(
        status = ListenBrainzCredentialStorageStatus.PersistentSecure,
        description = "Android Keystore",
    )

    override suspend fun read(key: SecureCredentialKey): String? = withContext(Dispatchers.IO) {
        val file = credentialFile(key)
        val encoded = file.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() } ?: return@withContext null
        runCatching {
            val blob = Base64.getDecoder().decode(encoded)
            val ivSize = blob.firstOrNull()?.toInt()?.and(0xff) ?: return@runCatching null
            if (ivSize <= 0 || blob.size <= 1 + ivSize) return@runCatching null
            val iv = blob.copyOfRange(1, 1 + ivSize)
            val ciphertext = blob.copyOfRange(1 + ivSize, blob.size)
            val cipher = Cipher.getInstance(AndroidCipherTransformation)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext).decodeToString()
        }.getOrElse {
            file.delete()
            null
        }
    }

    override suspend fun write(key: SecureCredentialKey, value: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(AndroidCipherTransformation)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.encodeToByteArray())
        val iv = cipher.iv
        val blob = byteArrayOf(iv.size.toByte()) + iv + ciphertext
        credentialFile(key).apply {
            parentFile?.mkdirs()
            writeText(Base64.getEncoder().encodeToString(blob))
        }
        Unit
    }

    override suspend fun delete(key: SecureCredentialKey) = withContext(Dispatchers.IO) {
        credentialFile(key).delete()
        Unit
    }

    private fun credentialFile(key: SecureCredentialKey): File =
        AndroidContextHolder.application.filesDir
            .resolve(localStorageDirectoryName())
            .resolve("credentials/${key.fileName}.bin")

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        (keyStore.getEntry(AndroidListenBrainzKeyAlias, null) as? KeyStore.SecretKeyEntry)
            ?.secretKey
            ?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
        generator.init(
            KeyGenParameterSpec.Builder(
                AndroidListenBrainzKeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}

private val SecureCredentialKey.fileName: String
    get() = when (this) {
        SecureCredentialKey.ListenBrainzUserToken -> "listenbrainz-user-token"
        SecureCredentialKey.LastFmSharedSecret -> "lastfm-shared-secret"
        SecureCredentialKey.LastFmSessionKey -> "lastfm-session-key"
        SecureCredentialKey.RemoteControlPairedDevices -> "remote-control-paired-devices"
        SecureCredentialKey.RemoteControlClientSecrets -> "remote-control-client-secrets"
    }

private const val AndroidKeyStore = "AndroidKeyStore"
private const val AndroidListenBrainzKeyAlias = "Phoebe.ListenBrainzToken"
private const val AndroidCipherTransformation = "AES/GCM/NoPadding"
