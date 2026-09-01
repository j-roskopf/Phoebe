package com.phoebe.app.remote

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.core.ByteReadPacket
import io.ktor.utils.io.core.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable
data class DiscoveryQuery(
    val protocolVersion: Int = REMOTE_PROTOCOL_VERSION,
)

@Serializable
data class DiscoveredHost(
    val name: String,
    val deviceId: String,
    val hostAddress: String,
    val tcpPort: Int = DEFAULT_REMOTE_TCP_PORT,
    val protocolVersion: Int = REMOTE_PROTOCOL_VERSION,
)

@Serializable
internal data class DiscoveryResponsePayload(
    val name: String,
    val deviceId: String,
    val tcpPort: Int = DEFAULT_REMOTE_TCP_PORT,
    val protocolVersion: Int = REMOTE_PROTOCOL_VERSION,
)

class RemoteDiscoveryServer(
    private val hostNameProvider: () -> String,
    private val hostDeviceIdProvider: () -> String,
    private val tcpPortProvider: () -> Int = { DEFAULT_REMOTE_TCP_PORT },
    private val discoveryPort: Int = DEFAULT_REMOTE_DISCOVERY_PORT,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        val selector = SelectorManager(Dispatchers.Default)
        job = scope.launch(Dispatchers.Default) {
            val multicastLock = com.phoebe.app.platform.acquireMulticastLock()
            com.phoebe.app.platform.PhoebeLog.d("RemoteDiscovery", "Server starting on port $discoveryPort")
            try {
                val serverSocket = aSocket(selector).udp().bind(
                    localAddress = InetSocketAddress("0.0.0.0", discoveryPort),
                    configure = {
                        reuseAddress = true
                    },
                )
                com.phoebe.app.platform.PhoebeLog.d("RemoteDiscovery", "Server bound successfully to 0.0.0.0:$discoveryPort")
                try {
                    while (isActive) {
                        val datagram = serverSocket.receive()
                        val text = runCatching { datagram.packet.readText() }.getOrNull()
                        com.phoebe.app.platform.PhoebeLog.d("RemoteDiscovery", "Server received packet from ${datagram.address}: $text")
                        if (text == null) continue
                        val query = runCatching {
                            RemoteJson.decodeFromString(DiscoveryQuery.serializer(), text)
                        }.getOrNull()
                        if (query == null) {
                            com.phoebe.app.platform.PhoebeLog.d("RemoteDiscovery", "Server could not parse query: $text")
                            continue
                        }

                        if (query.protocolVersion <= REMOTE_PROTOCOL_VERSION) {
                            val payload = DiscoveryResponsePayload(
                                name = hostNameProvider(),
                                deviceId = hostDeviceIdProvider(),
                                tcpPort = tcpPortProvider(),
                                protocolVersion = REMOTE_PROTOCOL_VERSION,
                            )
                            val responseJson = RemoteJson.encodeToString(
                                DiscoveryResponsePayload.serializer(),
                                payload,
                            )
                            val bytes = responseJson.encodeToByteArray()
                            com.phoebe.app.platform.PhoebeLog.d("RemoteDiscovery", "Server sending response to ${datagram.address}: $responseJson")
                            serverSocket.send(
                                Datagram(
                                    packet = ByteReadPacket(bytes),
                                    address = datagram.address,
                                ),
                            )
                        }
                    }
                } finally {
                    serverSocket.close()
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                com.phoebe.app.platform.PhoebeLog.d("RemoteDiscovery", "Server error: ${e.message}")
            } finally {
                multicastLock?.close()
                selector.close()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

private fun extractSenderIp(address: io.ktor.network.sockets.SocketAddress): String {
    val fromHostname = (address as? InetSocketAddress)?.let {
        runCatching { it.hostname }.getOrNull()
    }?.takeIf { it.isNotBlank() }

    if (fromHostname != null && !fromHostname.startsWith("/")) {
        return fromHostname
    }

    val raw = address.toString().trim()
    val clean = raw.removePrefix("/").substringBefore(":")
    return clean.ifBlank { "127.0.0.1" }
}

class RemoteDiscoveryClient(
    private val discoveryPort: Int = DEFAULT_REMOTE_DISCOVERY_PORT,
    private val intervalMs: Long = 1500L,
    private val ignoreDeviceId: String? = null,
) {
    fun discover(): Flow<DiscoveredHost> = flow {
        val selector = SelectorManager(Dispatchers.Default)
        val multicastLock = com.phoebe.app.platform.acquireMulticastLock()
        val discovered = mutableSetOf<String>()
        com.phoebe.app.platform.PhoebeLog.d("RemoteDiscovery", "Client starting discovery on port $discoveryPort")
        try {
            val clientSocket = aSocket(selector).udp().bind(
                configure = {
                    broadcast = true
                    reuseAddress = true
                },
            )
            com.phoebe.app.platform.PhoebeLog.d("RemoteDiscovery", "Client bound socket to ${clientSocket.localAddress}")
            try {
                val queryJson = RemoteJson.encodeToString(
                    DiscoveryQuery.serializer(),
                    DiscoveryQuery(protocolVersion = REMOTE_PROTOCOL_VERSION),
                )
                val queryBytes = queryJson.encodeToByteArray()

                while (currentCoroutineContext().isActive) {
                    val broadcastTargets = (com.phoebe.app.platform.getBroadcastAddresses() + listOf("255.255.255.255", "127.0.0.1", "10.0.2.2")).distinct()
                    val subnetTargets = com.phoebe.app.platform.localHostIpAddresses().flatMap { ip ->
                        val lastDot = ip.lastIndexOf('.')
                        if (lastDot > 0) {
                            val prefix = ip.substring(0, lastDot + 1)
                            (1..254).map { "$prefix$it" }
                        } else {
                            emptyList()
                        }
                    }.distinct()

                    val allTargets = (broadcastTargets + subnetTargets).distinct()
                    com.phoebe.app.platform.PhoebeLog.d("RemoteDiscovery", "Client probing ${broadcastTargets.size} broadcast and ${subnetTargets.size} subnet targets")

                    for (target in allTargets) {
                        val result = runCatching {
                            clientSocket.send(
                                Datagram(
                                    packet = ByteReadPacket(queryBytes),
                                    address = InetSocketAddress(target, discoveryPort),
                                ),
                            )
                        }
                        if (result.isFailure && target in broadcastTargets) {
                            com.phoebe.app.platform.PhoebeLog.d("RemoteDiscovery", "Client failed sending to $target: ${result.exceptionOrNull()?.message}")
                        }
                    }

                    val deadline = currentTimeProvider() + intervalMs
                    while (currentTimeProvider() < deadline && currentCoroutineContext().isActive) {
                        val remaining = (deadline - currentTimeProvider()).coerceAtLeast(1L)
                        val datagram = runCatching {
                            withTimeoutOrNull(remaining) {
                                clientSocket.receive()
                            }
                        }.getOrNull() ?: break

                        val parsedHost = runCatching {
                            val text = datagram.packet.readText()
                            com.phoebe.app.platform.PhoebeLog.d("RemoteDiscovery", "Client received datagram from ${datagram.address}: $text")
                            val response = RemoteJson.decodeFromString(DiscoveryResponsePayload.serializer(), text)

                            if (ignoreDeviceId != null && response.deviceId == ignoreDeviceId) {
                                null
                            } else {
                                val senderAddress = extractSenderIp(datagram.address)
                                DiscoveredHost(
                                    name = response.name,
                                    deviceId = response.deviceId,
                                    hostAddress = senderAddress,
                                    tcpPort = response.tcpPort,
                                    protocolVersion = response.protocolVersion,
                                )
                            }
                        }.onFailure { err ->
                            com.phoebe.app.platform.PhoebeLog.d("RemoteDiscovery", "Client failed processing datagram: ${err::class.simpleName}: ${err.message}")
                        }.getOrNull()

                        if (parsedHost != null) {
                            val key = "${parsedHost.deviceId}@${parsedHost.hostAddress}:${parsedHost.tcpPort}"
                            if (discovered.add(key)) {
                                com.phoebe.app.platform.PhoebeLog.d("RemoteDiscovery", "Client discovered host: ${parsedHost.name} at ${parsedHost.hostAddress}:${parsedHost.tcpPort}")
                                emit(parsedHost)
                            }
                        }
                    }
                }
            } finally {
                clientSocket.close()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            com.phoebe.app.platform.PhoebeLog.d("RemoteDiscovery", "Client discover error: ${e::class.simpleName}: ${e.message}\n${e.stackTraceToString()}")
        } finally {
            multicastLock?.close()
            selector.close()
        }
    }

    companion object {
        var currentTimeProvider: () -> Long = {
            kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds
        }
    }
}
