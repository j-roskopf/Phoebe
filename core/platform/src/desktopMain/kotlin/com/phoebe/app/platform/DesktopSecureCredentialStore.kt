package com.phoebe.app.platform

import com.phoebe.app.domain.ListenBrainzCredentialStorageStatus
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

actual fun createSecureCredentialStore(): SecureCredentialStore = DesktopSecureCredentialStore()

private class DesktopSecureCredentialStore : SecureCredentialStore {
    private val backend = DesktopCredentialBackend.current()

    override val availability: SecureCredentialAvailability = backend.availability

    override suspend fun read(key: SecureCredentialKey): String? = backend.read(key)

    override suspend fun write(key: SecureCredentialKey, value: String) {
        if (!availability.canWrite) error(availability.description)
        backend.write(key, value)
    }

    override suspend fun delete(key: SecureCredentialKey) {
        backend.delete(key)
    }
}

private sealed interface DesktopCredentialBackend {
    val availability: SecureCredentialAvailability
    suspend fun read(key: SecureCredentialKey): String?
    suspend fun write(key: SecureCredentialKey, value: String)
    suspend fun delete(key: SecureCredentialKey)

    companion object {
        fun current(): DesktopCredentialBackend {
            val os = System.getProperty("os.name").lowercase(Locale.US)
            return when {
                "mac" in os && commandExists("/usr/bin/security") -> MacOsKeychainBackend
                "win" in os -> WindowsCredentialManagerBackend
                commandExists("secret-tool") -> LinuxSecretToolBackend
                else -> SessionMemoryCredentialBackend(
                    description = "Session-only memory. Install libsecret's secret-tool for persistent storage.",
                )
            }
        }
    }
}

private object MacOsKeychainBackend : DesktopCredentialBackend {
    override val availability = SecureCredentialAvailability(
        status = ListenBrainzCredentialStorageStatus.PersistentSecure,
        description = "macOS Keychain",
    )

    override suspend fun read(key: SecureCredentialKey): String? = withContext(Dispatchers.IO) {
        val result = runProcess(
            "/usr/bin/security",
            "find-generic-password",
            "-s",
            MacListenBrainzService,
            "-a",
            key.account,
            "-w",
        )
        result.takeIf { it.exitCode == 0 }?.stdout?.trimEnd('\n')?.takeIf { it.isNotBlank() }
    }

    override suspend fun write(key: SecureCredentialKey, value: String) = withContext(Dispatchers.IO) {
        val result = runProcess(
            "/usr/bin/security",
            "add-generic-password",
            "-U",
            "-s",
            MacListenBrainzService,
            "-a",
            key.account,
            "-w",
            value,
        )
        if (result.exitCode != 0) error(result.stderr.ifBlank { "Unable to store ListenBrainz token in Keychain." })
        Unit
    }

    override suspend fun delete(key: SecureCredentialKey) = withContext(Dispatchers.IO) {
        runProcess(
            "/usr/bin/security",
            "delete-generic-password",
            "-s",
            MacListenBrainzService,
            "-a",
            key.account,
        )
        Unit
    }
}

private object LinuxSecretToolBackend : DesktopCredentialBackend {
    override val availability = SecureCredentialAvailability(
        status = ListenBrainzCredentialStorageStatus.PersistentSecure,
        description = "Linux Secret Service",
    )

    override suspend fun read(key: SecureCredentialKey): String? = withContext(Dispatchers.IO) {
        val result = runProcess("secret-tool", "lookup", "application", "Phoebe", "account", key.account)
        result.takeIf { it.exitCode == 0 }?.stdout?.trimEnd('\n')?.takeIf { it.isNotBlank() }
    }

    override suspend fun write(key: SecureCredentialKey, value: String) = withContext(Dispatchers.IO) {
        val result = runProcessWithInput(
            input = value,
            "secret-tool",
            "store",
            "--label",
            "Phoebe ListenBrainz token",
            "application",
            "Phoebe",
            "account",
            key.account,
        )
        if (result.exitCode != 0) error(result.stderr.ifBlank { "Unable to store ListenBrainz token in Secret Service." })
        Unit
    }

    override suspend fun delete(key: SecureCredentialKey) = withContext(Dispatchers.IO) {
        runProcess("secret-tool", "clear", "application", "Phoebe", "account", key.account)
        Unit
    }
}

private object WindowsCredentialManagerBackend : DesktopCredentialBackend {
    override val availability = SecureCredentialAvailability(
        status = ListenBrainzCredentialStorageStatus.PersistentSecure,
        description = "Windows Credential Manager",
    )

    override suspend fun read(key: SecureCredentialKey): String? = withContext(Dispatchers.IO) {
        val pointer = PointerByReference()
        val ok = runCatching {
            WindowsCredApi.INSTANCE.CredReadW(WString(key.windowsTargetName), CredTypeGeneric, 0, pointer)
        }.getOrDefault(false)
        if (!ok) return@withContext null
        val credentialPointer = pointer.value ?: return@withContext null
        try {
            val credential = WindowsCredential(credentialPointer)
            credential.CredentialBlob
                ?.takeIf { credential.CredentialBlobSize > 0 }
                ?.getByteArray(0, credential.CredentialBlobSize)
                ?.decodeToString()
                ?.takeIf { it.isNotBlank() }
        } finally {
            WindowsCredApi.INSTANCE.CredFree(credentialPointer)
        }
    }

    override suspend fun write(key: SecureCredentialKey, value: String) = withContext(Dispatchers.IO) {
        val bytes = value.encodeToByteArray()
        val blob = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        val credential = WindowsCredential().apply {
            Type = CredTypeGeneric
            TargetName = WString(key.windowsTargetName)
            CredentialBlobSize = bytes.size
            CredentialBlob = blob
            Persist = CredPersistLocalMachine
            UserName = WString("Phoebe")
        }
        credential.write()
        val ok = runCatching { WindowsCredApi.INSTANCE.CredWriteW(credential, 0) }.getOrDefault(false)
        if (!ok) {
            val errorCode = Native.getLastError()
            error(
                if (errorCode != 0) {
                    "Unable to store ListenBrainz token in Windows Credential Manager (error $errorCode)."
                } else {
                    "Unable to store ListenBrainz token in Windows Credential Manager."
                },
            )
        }
        Unit
    }

    override suspend fun delete(key: SecureCredentialKey) = withContext(Dispatchers.IO) {
        runCatching { WindowsCredApi.INSTANCE.CredDeleteW(WString(key.windowsTargetName), CredTypeGeneric, 0) }
        Unit
    }
}

private class SessionMemoryCredentialBackend(
    description: String,
) : DesktopCredentialBackend {
    override val availability = SecureCredentialAvailability(
        status = ListenBrainzCredentialStorageStatus.SessionOnly,
        description = description,
    )

    override suspend fun read(key: SecureCredentialKey): String? = SessionMemoryCredentials[key]

    override suspend fun write(key: SecureCredentialKey, value: String) {
        SessionMemoryCredentials[key] = value
    }

    override suspend fun delete(key: SecureCredentialKey) {
        SessionMemoryCredentials.remove(key)
    }
}

private object SessionMemoryCredentials {
    private val values = mutableMapOf<SecureCredentialKey, String>()
    operator fun get(key: SecureCredentialKey): String? = values[key]
    operator fun set(key: SecureCredentialKey, value: String) {
        values[key] = value
    }
    fun remove(key: SecureCredentialKey) {
        values.remove(key)
    }
}

private interface WindowsCredApi : StdCallLibrary {
    fun CredReadW(targetName: WString, type: Int, flags: Int, credential: PointerByReference): Boolean
    fun CredWriteW(credential: WindowsCredential, flags: Int): Boolean
    fun CredDeleteW(targetName: WString, type: Int, flags: Int): Boolean
    fun CredFree(buffer: Pointer)

    companion object {
        val INSTANCE: WindowsCredApi =
            Native.load("Advapi32", WindowsCredApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

internal class WindowsCredential : Structure {
    @JvmField var Flags: Int = 0
    @JvmField var Type: Int = CredTypeGeneric
    @JvmField var TargetName: WString? = null
    @JvmField var Comment: WString? = null
    @JvmField var LastWritten: WindowsFileTime = WindowsFileTime()
    @JvmField var CredentialBlobSize: Int = 0
    @JvmField var CredentialBlob: Pointer? = null
    @JvmField var Persist: Int = CredPersistLocalMachine
    @JvmField var AttributeCount: Int = 0
    @JvmField var Attributes: Pointer? = null
    @JvmField var TargetAlias: WString? = null
    @JvmField var UserName: WString? = null

    constructor() : super()

    constructor(pointer: Pointer) : super(pointer) {
        read()
    }

    override fun getFieldOrder(): List<String> = listOf(
        "Flags",
        "Type",
        "TargetName",
        "Comment",
        "LastWritten",
        "CredentialBlobSize",
        "CredentialBlob",
        "Persist",
        "AttributeCount",
        "Attributes",
        "TargetAlias",
        "UserName",
    )
}

internal class WindowsFileTime : Structure() {
    @JvmField var dwLowDateTime: Int = 0
    @JvmField var dwHighDateTime: Int = 0

    override fun getFieldOrder(): List<String> = listOf("dwLowDateTime", "dwHighDateTime")
}

private data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

private fun runProcess(vararg command: String): ProcessResult =
    runProcessWithInput(input = null, *command)

private fun runProcessWithInput(input: String?, vararg command: String): ProcessResult {
    val process = ProcessBuilder(*command).start()
    if (input != null) {
        process.outputStream.bufferedWriter().use { writer ->
            writer.write(input)
        }
    } else {
        process.outputStream.close()
    }
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val finished = process.waitFor(8, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return ProcessResult(-1, stdout, "Credential command timed out.")
    }
    return ProcessResult(process.exitValue(), stdout, stderr)
}

private fun commandExists(command: String): Boolean {
    if (command.contains(File.separatorChar)) return File(command).canExecute()
    val path = System.getenv("PATH").orEmpty()
    return path.split(File.pathSeparator).any { dir ->
        File(dir, command).canExecute()
    }
}

private val SecureCredentialKey.account: String
    get() = when (this) {
        SecureCredentialKey.ListenBrainzUserToken -> "userToken"
        SecureCredentialKey.LastFmSharedSecret -> "lastFmSharedSecret"
        SecureCredentialKey.LastFmSessionKey -> "lastFmSessionKey"
        SecureCredentialKey.RemoteControlPairedDevices -> "remoteControlPairedDevices"
        SecureCredentialKey.RemoteControlClientSecrets -> "remoteControlClientSecrets"
    }

private val SecureCredentialKey.windowsTargetName: String
    get() = when (this) {
        SecureCredentialKey.ListenBrainzUserToken -> "Phoebe.ListenBrainzToken"
        SecureCredentialKey.LastFmSharedSecret -> "Phoebe.LastFmSharedSecret"
        SecureCredentialKey.LastFmSessionKey -> "Phoebe.LastFmSessionKey"
        SecureCredentialKey.RemoteControlPairedDevices -> "Phoebe.RemoteControlPairedDevices"
        SecureCredentialKey.RemoteControlClientSecrets -> "Phoebe.RemoteControlClientSecrets"
    }

private const val MacListenBrainzService = "com.phoebe.listenbrainz"
private const val CredTypeGeneric = 1
private const val CredPersistLocalMachine = 2
