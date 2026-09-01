package com.phoebe.app.remote

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class PendingPairingRequest(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    private val onDecision: (Boolean) -> Unit,
) {
    fun approve() = onDecision(true)
    fun reject() = onDecision(false)
}

class RemoteHostServer(
    private val hostNameProvider: () -> String,
    private val hostDeviceIdProvider: () -> String,
    private val pairedDeviceStore: PairedDeviceStore,
    private val bridge: RemoteHostBridge,
    private val portProvider: () -> Int = { DEFAULT_REMOTE_TCP_PORT },
    private val hostAccountIdProvider: () -> String? = { null },
) {
    private var serverJob: Job? = null
    private var broadcastJob: Job? = null
    private var positionTickJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val activeClients = mutableSetOf<RemoteConnection>()
    private val clientsMutex = Mutex()

    /**
     * Serializes snapshot and position-tick broadcasts so they leave the host in the same
     * order the underlying state actually changed in. Without this, the snapshotFlow collector
     * and the 250ms tick loop are two independent coroutines racing to write to the same
     * socket: a tick read just before a pause can lose that race and arrive after the paused
     * snapshot, leaving clients stuck showing "playing" since ticks stop once actually paused.
     */
    private val broadcastOrderMutex = Mutex()

    private val mutableConnectedClients = MutableStateFlow(0)
    val connectedClients: StateFlow<Int> = mutableConnectedClients.asStateFlow()

    private val mutablePendingPairings = MutableStateFlow<List<PendingPairingRequest>>(emptyList())
    val pendingPairings: StateFlow<List<PendingPairingRequest>> = mutablePendingPairings.asStateFlow()

    fun start(scope: CoroutineScope) {
        if (serverJob?.isActive == true) return
        val selector = SelectorManager(Dispatchers.Default)
        val port = portProvider()

        serverJob = scope.launch(Dispatchers.Default) {
            try {
                val socket = aSocket(selector).tcp().bind(InetSocketAddress("0.0.0.0", port)) {
                    reuseAddress = true
                }
                serverSocket = socket
                while (isActive) {
                    val clientSocket = try {
                        socket.accept()
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        break
                    }
                    launch {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
            } finally {
                serverSocket?.close()
                selector.close()
            }
        }

        broadcastJob = scope.launch {
            bridge.snapshotFlow.collect { snapshot ->
                broadcastOrderMutex.withLock {
                    broadcastFrame(RemoteFrame.Snapshot(snapshot))
                }
            }
        }

        positionTickJob = scope.launch {
            while (isActive) {
                delay(250L)
                if (bridge.isPlaying && mutableConnectedClients.value > 0) {
                    broadcastOrderMutex.withLock {
                        // Re-read state now that the lock is held, not when the delay woke us,
                        // so a tick can never carry data that's staler than a snapshot it races.
                        if (bridge.isPlaying) {
                            val tick = RemoteFrame.PositionTick(
                                positionMs = bridge.positionMs,
                                durationMs = bridge.durationMs,
                                isPlaying = bridge.isPlaying,
                            )
                            broadcastFrame(tick)
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        serverJob?.cancel()
        broadcastJob?.cancel()
        positionTickJob?.cancel()
        serverJob = null
        broadcastJob = null
        positionTickJob = null

        serverSocket?.close()
        serverSocket = null

        val clients = mutableListOf<RemoteConnection>()
        if (clientsMutex.tryLock()) {
            try {
                clients.addAll(activeClients)
                activeClients.clear()
                mutableConnectedClients.value = 0
            } finally {
                clientsMutex.unlock()
            }
        }
        clients.forEach { it.close() }
        mutablePendingPairings.value = emptyList()
    }

    private suspend fun handleClient(socket: Socket) {
        val connection = RemoteConnection(socket)
        try {
            // 1. Read Hello frame with 10s timeout
            val helloFrame = withTimeoutOrNull(10_000L) {
                connection.receive()
            } as? RemoteFrame.Hello ?: run {
                connection.send(RemoteFrame.Bye("Invalid handshake: expected Hello"))
                connection.close()
                return
            }

            val deviceId = helloFrame.deviceId
            val deviceName = helloFrame.deviceName
            val pairedDevice = pairedDeviceStore.findDevice(deviceId)
            val hostAccountId = hostAccountIdProvider()
            val sameAccount = hostAccountId != null && helloFrame.accountId == hostAccountId

            if (pairedDevice != null) {
                // Known paired device -> HMAC challenge-response
                val nonce = HmacSha256.generateNonce(16)
                connection.send(
                    RemoteFrame.Challenge(
                        nonce = nonce,
                        hostName = hostNameProvider(),
                        hostDeviceId = hostDeviceIdProvider(),
                    ),
                )

                val authResponse = withTimeoutOrNull(10_000L) {
                    connection.receive()
                } as? RemoteFrame.AuthResponse ?: run {
                    connection.send(RemoteFrame.AuthResult(false, message = "Handshake timeout"))
                    connection.send(RemoteFrame.Bye("Auth timeout"))
                    connection.close()
                    return
                }

                val expectedMac = HmacSha256.hmacHex(pairedDevice.secret, nonce)
                if (!authResponse.mac.equals(expectedMac, ignoreCase = true)) {
                    connection.send(RemoteFrame.AuthResult(false, message = "Authentication failed: invalid MAC"))
                    connection.send(RemoteFrame.Bye("Invalid credentials"))
                    connection.close()
                    return
                }

                // Authentication succeeded
                connection.send(RemoteFrame.AuthResult(true, sameAccount = sameAccount))
                connection.send(RemoteFrame.Snapshot(bridge.currentSnapshot))
            } else {
                // Unknown device -> Ask host user for approval
                connection.send(
                    RemoteFrame.AwaitingApproval(
                        hostName = hostNameProvider(),
                        hostDeviceId = hostDeviceIdProvider(),
                    ),
                )
                val decisionDeferred = CompletableDeferred<Boolean>()
                val requestId = HmacSha256.generateNonce(8)
                val request = PendingPairingRequest(
                    id = requestId,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    onDecision = { approved -> decisionDeferred.complete(approved) },
                )

                mutablePendingPairings.update { it + request }
                val approved = try {
                    withTimeoutOrNull(60_000L) {
                        decisionDeferred.await()
                    } ?: false
                } finally {
                    mutablePendingPairings.update { list -> list.filterNot { it.id == requestId } }
                }

                if (!approved) {
                    connection.send(RemoteFrame.AuthResult(false, message = "Pairing request rejected"))
                    connection.send(RemoteFrame.Bye("Pairing rejected"))
                    connection.close()
                    return
                }

                // Pairing approved: generate secret, persist, and send to client
                val secret = HmacSha256.generateSecretHex(32)
                pairedDeviceStore.addOrUpdateDevice(
                    PairedRemoteDevice(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        pairedAtMs = kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds,
                        secret = secret,
                    ),
                )

                connection.send(RemoteFrame.AuthResult(true, pairingSecret = secret, sameAccount = sameAccount))
                connection.send(RemoteFrame.Snapshot(bridge.currentSnapshot))
            }

            // Register client in active clients
            clientsMutex.withLock {
                activeClients.add(connection)
                mutableConnectedClients.value = activeClients.size
            }

            // Session loop: process incoming frames from client
            while (!connection.isClosed) {
                val frame = connection.receive() ?: break
                com.phoebe.app.platform.PhoebeLog.d("RemoteHostServer", "Received frame from client $deviceId ($deviceName): $frame")
                when (frame) {
                    is RemoteFrame.Command -> {
                        val requiresSameAccount = frame.command is RemoteCommand.ReplaceQueue ||
                            frame.command is RemoteCommand.AppendToQueue ||
                            frame.command is RemoteCommand.InsertNext
                        if (requiresSameAccount && !sameAccount) {
                            com.phoebe.app.platform.PhoebeLog.d("RemoteHostServer", "Ignoring queue command from $deviceId: accounts do not match")
                        } else {
                            com.phoebe.app.platform.PhoebeLog.d("RemoteHostServer", "Executing command from $deviceId: ${frame.command}")
                            bridge.execute(frame.command)
                        }
                    }
                    is RemoteFrame.Ping -> {
                        connection.send(RemoteFrame.Pong)
                    }
                    is RemoteFrame.Bye -> {
                        break
                    }
                    else -> Unit
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            com.phoebe.app.platform.PhoebeLog.d("RemoteHostServer", "Error in handleClient for $socket: ${e.message}")
        } finally {
            clientsMutex.withLock {
                activeClients.remove(connection)
                mutableConnectedClients.value = activeClients.size
            }
            connection.close()
        }
    }

    private suspend fun broadcastFrame(frame: RemoteFrame) {
        val targets = clientsMutex.withLock { activeClients.toList() }
        for (client in targets) {
            try {
                client.send(frame)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                // Client write error, will be cleaned up
            }
        }
    }
}
