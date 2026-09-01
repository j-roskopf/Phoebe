package com.phoebe.app.remote

import com.phoebe.app.domain.PlayerState
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class RemoteConnectionStatus {
    Disconnected,
    Connecting,
    AwaitingApproval,
    Connected,
    Error,
}

data class RemoteControlSessionState(
    val status: RemoteConnectionStatus = RemoteConnectionStatus.Disconnected,
    val hostName: String? = null,
    val hostAddress: String? = null,
    val hostPort: Int = DEFAULT_REMOTE_TCP_PORT,
    val hostDeviceId: String? = null,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val volume: Float = 1f,
    val errorMessage: String? = null,
    val syncTimeMark: kotlin.time.TimeMark? = null,
    /** True when the host confirmed this client is signed into the same account. */
    val sameAccount: Boolean = false,
) {
    val isConnected: Boolean get() = status == RemoteConnectionStatus.Connected
    val isConnectedOrReconnecting: Boolean get() = isConnected || (status == RemoteConnectionStatus.Connecting && queue.isNotEmpty())
    val currentTrack: Track? get() = queue.getOrNull(currentIndex)

    fun extrapolatedPositionMs(): Long {
        val mark = syncTimeMark
        if (!isPlaying || isBuffering || mark == null) return positionMs
        val elapsed = mark.elapsedNow().inWholeMilliseconds
        val extrapolated = positionMs + elapsed.coerceAtLeast(0L)
        return if (durationMs > 0L) extrapolated.coerceAtMost(durationMs) else extrapolated
    }

    fun asPlayerState(fallback: PlayerState = PlayerState()): PlayerState =
        fallback.copy(
            queue = queue,
            currentIndex = currentIndex,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            positionMs = extrapolatedPositionMs(),
            durationMs = durationMs.takeIf { it > 0L } ?: currentTrack?.durationMs ?: fallback.durationMs,
            shuffle = shuffle,
            repeat = repeat,
            volume = volume,
        )
}

class RemoteControlClient(
    private val clientDeviceIdProvider: () -> String,
    private val clientDeviceNameProvider: () -> String,
    private val pairedDeviceStore: PairedDeviceStore,
    private val clientAccountIdProvider: () -> String? = { null },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectionJob: Job? = null
    private var extrapolationJob: Job? = null
    private var connection: RemoteConnection? = null
    private val sendMutex = Mutex()

    private var targetHost: String? = null
    private var targetPort: Int = DEFAULT_REMOTE_TCP_PORT
    private var intentionalDisconnect = false

    private val mutableState = MutableStateFlow(RemoteControlSessionState())
    val state: StateFlow<RemoteControlSessionState> = mutableState.asStateFlow()

    fun connect(host: String, port: Int = DEFAULT_REMOTE_TCP_PORT, onConnected: (() -> Unit)? = null) {
        disconnect()
        intentionalDisconnect = false
        targetHost = host
        targetPort = port

        connectionJob = scope.launch {
            var attempt = 0
            while (isActive && !intentionalDisconnect) {
                attempt++
                val success = runConnection(host, port, onConnected)
                if (intentionalDisconnect || success == ConnectionExitReason.UserDisconnected || success == ConnectionExitReason.AuthRejected) {
                    break
                }
                // Backoff before retry
                val backoffMs = (attempt * 1000L).coerceAtMost(10_000L)
                delay(backoffMs)
            }
        }
    }

    fun disconnect() {
        intentionalDisconnect = true
        connectionJob?.cancel()
        extrapolationJob?.cancel()
        connectionJob = null
        extrapolationJob = null

        connection?.close()
        connection = null

        mutableState.value = RemoteControlSessionState(
            status = RemoteConnectionStatus.Disconnected,
        )
    }

    suspend fun sendCommand(command: RemoteCommand) {
        val conn = connection
        if (conn == null) {
            com.phoebe.app.platform.PhoebeLog.d("RemoteClient", "sendCommand $command ignored: connection is null (status=${mutableState.value.status})")
            return
        }
        try {
            com.phoebe.app.platform.PhoebeLog.d("RemoteClient", "sendCommand $command sending frame...")
            conn.send(RemoteFrame.Command(command))
            com.phoebe.app.platform.PhoebeLog.d("RemoteClient", "sendCommand $command sent successfully")
        } catch (e: Throwable) {
            com.phoebe.app.platform.PhoebeLog.d("RemoteClient", "sendCommand $command failed: ${e.message}")
        }
    }

    suspend fun togglePlayPause() = sendCommand(RemoteCommand.TogglePlayPause)
    suspend fun next() = sendCommand(RemoteCommand.Next)
    suspend fun previous() = sendCommand(RemoteCommand.Previous)
    suspend fun seekTo(positionMs: Long) {
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        mutableState.update { it.copy(positionMs = positionMs, syncTimeMark = mark) }
        sendCommand(RemoteCommand.SeekTo(positionMs))
    }
    suspend fun setVolume(volume: Float) = sendCommand(RemoteCommand.SetVolume(volume))
    suspend fun jumpToIndex(index: Int) = sendCommand(RemoteCommand.JumpToIndex(index))
    suspend fun setShuffle(shuffle: Boolean) = sendCommand(RemoteCommand.SetShuffle(shuffle))
    suspend fun setRepeat(repeat: RepeatMode) = sendCommand(RemoteCommand.SetRepeat(repeat))
    suspend fun replaceQueue(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false) =
        sendCommand(RemoteCommand.ReplaceQueue(tracks.sanitizeForRemote(), startIndex, shuffle))
    suspend fun appendToQueue(tracks: List<Track>) = sendCommand(RemoteCommand.AppendToQueue(tracks.sanitizeForRemote()))
    suspend fun insertNext(track: Track) = sendCommand(RemoteCommand.InsertNext(track.sanitizeForRemote()))

    private enum class ConnectionExitReason {
        UserDisconnected,
        AuthRejected,
        ConnectionLost,
    }

    private suspend fun runConnection(
        host: String,
        port: Int,
        onConnected: (() -> Unit)?,
    ): ConnectionExitReason = withContext(Dispatchers.Default) {
        mutableState.update {
            it.copy(
                status = RemoteConnectionStatus.Connecting,
                hostAddress = host,
                hostPort = port,
                errorMessage = null,
            )
        }

        val selector = SelectorManager(Dispatchers.Default)
        try {
            val socket = try {
                aSocket(selector).tcp().connect(InetSocketAddress(host, port))
            } catch (e: Throwable) {
                mutableState.update {
                    it.copy(
                        status = RemoteConnectionStatus.Error,
                        errorMessage = "Could not connect to $host:$port (${e.message ?: "Connection failed"})",
                    )
                }
                return@withContext ConnectionExitReason.ConnectionLost
            }

            val conn = RemoteConnection(socket)
            connection = conn

            // 1. Send Hello
            conn.send(
                RemoteFrame.Hello(
                    deviceId = clientDeviceIdProvider(),
                    deviceName = clientDeviceNameProvider(),
                    protocolVersion = REMOTE_PROTOCOL_VERSION,
                    accountId = clientAccountIdProvider(),
                ),
            )

            // 2. Await Challenge or AwaitingApproval
            var currentHostDeviceId: String? = null
            var currentHostName: String? = null
            var sameAccount = false

            val firstFrame = conn.receive() ?: run {
                conn.close()
                return@withContext ConnectionExitReason.ConnectionLost
            }

            when (firstFrame) {
                is RemoteFrame.Challenge -> {
                    currentHostDeviceId = firstFrame.hostDeviceId
                    currentHostName = firstFrame.hostName
                    mutableState.update {
                        it.copy(
                            hostName = firstFrame.hostName,
                            hostDeviceId = firstFrame.hostDeviceId,
                        )
                    }
                    val secret = pairedDeviceStore.getClientSecret(firstFrame.hostDeviceId)
                    if (secret != null) {
                        val mac = HmacSha256.hmacHex(secret, firstFrame.nonce)
                        conn.send(RemoteFrame.AuthResponse(mac))
                    } else {
                        // Secret missing, send empty
                        conn.send(RemoteFrame.AuthResponse(""))
                    }

                    val authResult = conn.receive() as? RemoteFrame.AuthResult
                    if (authResult?.success != true) {
                        mutableState.update {
                            it.copy(
                                status = RemoteConnectionStatus.Error,
                                errorMessage = authResult?.message ?: "Authentication failed",
                            )
                        }
                        conn.close()
                        return@withContext ConnectionExitReason.AuthRejected
                    }
                    sameAccount = authResult.sameAccount
                }
                is RemoteFrame.AwaitingApproval -> {
                    currentHostDeviceId = firstFrame.hostDeviceId
                    currentHostName = firstFrame.hostName
                    mutableState.update {
                        it.copy(
                            status = RemoteConnectionStatus.AwaitingApproval,
                            hostName = firstFrame.hostName,
                            hostDeviceId = firstFrame.hostDeviceId,
                        )
                    }

                    val authResult = conn.receive() as? RemoteFrame.AuthResult
                    if (authResult?.success != true) {
                        mutableState.update {
                            it.copy(
                                status = RemoteConnectionStatus.Error,
                                errorMessage = authResult?.message ?: "Pairing request was declined",
                            )
                        }
                        conn.close()
                        return@withContext ConnectionExitReason.AuthRejected
                    }

                    // Save pairing secret if provided
                    val secret = authResult.pairingSecret
                    if (secret != null && currentHostDeviceId != null) {
                        pairedDeviceStore.saveClientSecret(currentHostDeviceId, secret)
                    }
                    sameAccount = authResult.sameAccount
                }
                else -> {
                    conn.close()
                    return@withContext ConnectionExitReason.ConnectionLost
                }
            }

            // Successfully authenticated
            mutableState.update {
                it.copy(
                    status = RemoteConnectionStatus.Connected,
                    hostName = currentHostName ?: it.hostName,
                    errorMessage = null,
                    sameAccount = sameAccount,
                )
            }
            onConnected?.invoke()
            startExtrapolation()

            // Main message processing loop
            while (!conn.isClosed && isActive) {
                val frame = conn.receive() ?: break
                val mark = kotlin.time.TimeSource.Monotonic.markNow()
                when (frame) {
                    is RemoteFrame.Snapshot -> {
                        val snap = frame.snapshot
                        mutableState.update { current ->
                            current.copy(
                                queue = snap.queue,
                                currentIndex = snap.currentIndex,
                                isPlaying = snap.isPlaying,
                                isBuffering = snap.isBuffering,
                                positionMs = snap.positionMs,
                                durationMs = snap.durationMs,
                                shuffle = snap.shuffle,
                                repeat = snap.repeat,
                                volume = snap.volume,
                                hostName = snap.hostName.ifBlank { current.hostName.orEmpty() },
                                syncTimeMark = mark,
                            )
                        }
                    }
                    is RemoteFrame.PositionTick -> {
                        mutableState.update { current ->
                            current.copy(
                                positionMs = frame.positionMs,
                                durationMs = frame.durationMs,
                                isPlaying = frame.isPlaying,
                                syncTimeMark = mark,
                            )
                        }
                    }
                    is RemoteFrame.Pong -> Unit
                    is RemoteFrame.Bye -> {
                        mutableState.update {
                            it.copy(
                                status = RemoteConnectionStatus.Disconnected,
                                errorMessage = frame.reason,
                            )
                        }
                        break
                    }
                    else -> Unit
                }
            }

            if (intentionalDisconnect) {
                ConnectionExitReason.UserDisconnected
            } else {
                ConnectionExitReason.ConnectionLost
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            ConnectionExitReason.ConnectionLost
        } finally {
            extrapolationJob?.cancel()
            selector.close()
        }
    }

    private fun startExtrapolation() {
        extrapolationJob?.cancel()
        extrapolationJob = scope.launch {
            while (isActive) {
                delay(250L)
                val current = mutableState.value
                if (current.isConnected && current.isPlaying && !current.isBuffering && current.syncTimeMark != null) {
                    mutableState.update { it.copy() }
                }
            }
        }
    }
}
