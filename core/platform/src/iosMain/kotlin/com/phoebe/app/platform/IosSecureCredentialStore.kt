@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package com.phoebe.app.platform

import com.phoebe.app.domain.ListenBrainzCredentialStorageStatus
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

actual fun createSecureCredentialStore(): SecureCredentialStore = IosSecureCredentialStore()

private class IosSecureCredentialStore : SecureCredentialStore {
    override val availability: SecureCredentialAvailability = SecureCredentialAvailability(
        status = ListenBrainzCredentialStorageStatus.PersistentSecure,
        description = "iOS Keychain",
    )

    @Suppress("UNCHECKED_CAST")
    override suspend fun read(key: SecureCredentialKey): String? {
        val query = keychainQuery(key) + mapOf(
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        )
        return memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = query.withCfDictionary { cfQuery ->
                SecItemCopyMatching(cfQuery, result.ptr)
            }
            if (status == errSecItemNotFound) return@memScoped null
            if (status != errSecSuccess) return@memScoped null
            val dataRef = result.value ?: return@memScoped null
            val value = (dataRef as CFDataRef).toByteArray().decodeToString()
            CFRelease(dataRef)
            value
        }
    }

    override suspend fun write(key: SecureCredentialKey, value: String) {
        delete(key)
        val attributes = keychainQuery(key) + mapOf(
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData to value.encodeToByteArray().toNSData(),
        )
        val status = attributes.withCfDictionary { cfAttributes ->
            SecItemAdd(cfAttributes, null)
        }
        if (status != errSecSuccess) {
            error("Unable to store ListenBrainz token in Keychain.")
        }
    }

    override suspend fun delete(key: SecureCredentialKey) {
        keychainQuery(key).withCfDictionary { query ->
            SecItemDelete(query)
        }
    }

    private fun keychainQuery(key: SecureCredentialKey): Map<Any?, Any?> =
        mapOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to IosListenBrainzService,
            kSecAttrAccount to key.account,
        )
}

private val SecureCredentialKey.account: String
    get() = when (this) {
        SecureCredentialKey.ListenBrainzUserToken -> "userToken"
        SecureCredentialKey.LastFmSharedSecret -> "lastFmSharedSecret"
        SecureCredentialKey.LastFmSessionKey -> "lastFmSessionKey"
        SecureCredentialKey.RemoteControlPairedDevices -> "remoteControlPairedDevices"
        SecureCredentialKey.RemoteControlClientSecrets -> "remoteControlClientSecrets"
    }

@Suppress("UNCHECKED_CAST")
private inline fun <T> Map<Any?, Any?>.withCfDictionary(block: (CFDictionaryRef) -> T): T {
    val dictionary = NSDictionary.create(dictionary = this)
    val retained = CFBridgingRetain(dictionary) as CFDictionaryRef
    return try {
        block(retained)
    } finally {
        CFRelease(retained)
    }
}

private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData.create(bytes = null, length = 0u)
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

private fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    if (length <= 0) return ByteArray(0)
    val bytes = CFDataGetBytePtr(this) ?: return ByteArray(0)
    return ByteArray(length) { index ->
        bytes[index].toByte()
    }
}

private const val IosListenBrainzService = "com.phoebe.listenbrainz"
