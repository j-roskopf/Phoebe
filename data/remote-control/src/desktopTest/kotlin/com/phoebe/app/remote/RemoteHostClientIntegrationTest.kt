package com.phoebe.app.remote

import com.phoebe.app.domain.ListenBrainzCredentialStorageStatus
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.SecureCredentialAvailability
import com.phoebe.app.platform.SecureCredentialKey
import com.phoebe.app.platform.SecureCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FakeCredentialStore : SecureCredentialStore {
    private val data = mutableMapOf<SecureCredentialKey, String>()

    override val availability: SecureCredentialAvailability =
        SecureCredentialAvailability(ListenBrainzCredentialStorageStatus.PersistentSecure, "Test")

    override suspend fun read(key: SecureCredentialKey): String? = data[key]

    override suspend fun write(key: SecureCredentialKey, value: String) {
        data[key] = value
    }

    override suspend fun delete(key: SecureCredentialKey) {
        data.remove(key)
    }
}

class FakeRemoteHostBridge : RemoteHostBridge {
    val receivedCommands = mutableListOf<RemoteCommand>()
    private val _snapshotFlow = MutableSharedFlow<RemoteSnapshot>(replay = 1)
    override val snapshotFlow: Flow<RemoteSnapshot> = _snapshotFlow.asSharedFlow()

    override var currentSnapshot: RemoteSnapshot = RemoteSnapshot(
        queue = listOf(
            Track(
                id = "track-1",
                title = "Initial Song",
                artist = "Initial Artist",
                album = "Initial Album",
                durationMs = 180000L,
                streamUrl = "",
                downloadUrl = "",
            ),
        ),
        currentIndex = 0,
        isPlaying = false,
        isBuffering = false,
        positionMs = 0L,
        durationMs = 180000L,
        shuffle = false,
        repeat = RepeatMode.Off,
        volume = 1f,
        hostName = "Test Host Mac",
    )
        set(value) {
            field = value
            _snapshotFlow.tryEmit(value)
        }

    override val isPlaying: Boolean
        get() = currentSnapshot.isPlaying

    override val positionMs: Long
        get() = currentSnapshot.positionMs

    override val durationMs: Long
        get() = currentSnapshot.durationMs

    init {
        _snapshotFlow.tryEmit(currentSnapshot)
    }

    override suspend fun execute(command: RemoteCommand) {
        receivedCommands.add(command)
    }
}

class RemoteHostClientIntegrationTest {
    private var testScope: CoroutineScope? = null
    private val hostCredentialStore = FakeCredentialStore()
    private val clientCredentialStore = FakeCredentialStore()
    private val hostPairedDeviceStore = PairedDeviceStore(hostCredentialStore)
    private val clientPairedDeviceStore = PairedDeviceStore(clientCredentialStore)
    private val bridge = FakeRemoteHostBridge()
    private var testPort = 18765

    private lateinit var hostServer: RemoteHostServer
    private lateinit var client: RemoteControlClient

    @BeforeTest
    fun setUp() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        testScope = scope
        testPort = Random.nextInt(20000, 40000)

        hostServer = RemoteHostServer(
            hostNameProvider = { "Test Host Mac" },
            hostDeviceIdProvider = { "host-device-123" },
            pairedDeviceStore = hostPairedDeviceStore,
            bridge = bridge,
            portProvider = { testPort },
        )

        client = RemoteControlClient(
            clientDeviceIdProvider = { "client-device-456" },
            clientDeviceNameProvider = { "User iPhone" },
            pairedDeviceStore = clientPairedDeviceStore,
        )

        hostServer.start(scope)
    }

    @AfterTest
    fun tearDown() {
        client.disconnect()
        hostServer.stop()
        testScope?.cancel()
        testScope = null
    }

    @Test
    fun testFirstTimePairingApprovalAndReconnection() = kotlinx.coroutines.runBlocking(Dispatchers.Default) {
        // Give server a moment to bind
        delay(100L)
        // 1. First connection triggers approval request
        client.connect("127.0.0.1", testPort)

        val pairingRequest = withTimeout(5000L) {
            hostServer.pendingPairings.first { it.isNotEmpty() }.first()
        }

        assertEquals("client-device-456", pairingRequest.deviceId)
        assertEquals("User iPhone", pairingRequest.deviceName)

        // Approve pairing
        pairingRequest.approve()

        // Client should become connected and receive initial snapshot
        val state = withTimeout(5000L) {
            client.state.first { it.isConnected && it.queue.isNotEmpty() }
        }

        assertEquals(RemoteConnectionStatus.Connected, state.status)
        assertEquals(1, state.queue.size)
        assertEquals("Initial Song", state.queue[0].title)
        assertEquals("Test Host Mac", state.hostName)

        // 2. Send commands from client to host
        client.togglePlayPause()
        client.next()
        client.previous()
        client.seekTo(45000L)
        client.setVolume(0.5f)
        client.jumpToIndex(2)
        client.setShuffle(true)
        client.setRepeat(RepeatMode.All)

        withTimeout(5000L) {
            while (bridge.receivedCommands.size < 8) {
                delay(50)
            }
        }

        assertEquals(
            listOf(
                RemoteCommand.TogglePlayPause,
                RemoteCommand.Next,
                RemoteCommand.Previous,
                RemoteCommand.SeekTo(45000L),
                RemoteCommand.SetVolume(0.5f),
                RemoteCommand.JumpToIndex(2),
                RemoteCommand.SetShuffle(true),
                RemoteCommand.SetRepeat(RepeatMode.All),
            ),
            bridge.receivedCommands,
        )

        // 3. Disconnect client and reconnect (should auto-authenticate with challenge-response)
        client.disconnect()
        withTimeout(5000L) {
            client.state.first { it.status == RemoteConnectionStatus.Disconnected }
        }

        // Connect again with saved secret
        client.connect("127.0.0.1", testPort)

        withTimeout(5000L) {
            client.state.first { it.isConnected }
        }

        // Verify it reconnected without creating any new pending pairing requests
        assertTrue(hostServer.pendingPairings.value.isEmpty())
        assertTrue(client.state.value.isConnected)
    }

    @Test
    fun testPairingRejection() = kotlinx.coroutines.runBlocking(Dispatchers.Default) {
        delay(100L)
        client.connect("127.0.0.1", testPort)

        val pairingRequest = withTimeout(5000L) {
            hostServer.pendingPairings.first { it.isNotEmpty() }.first()
        }

        pairingRequest.reject()

        withTimeout(5000L) {
            client.state.first { it.status == RemoteConnectionStatus.Error }
        }

        val state = client.state.value
        assertEquals(RemoteConnectionStatus.Error, state.status)
        assertNotNull(state.errorMessage)
        assertFalse(state.isConnected)
    }

    @Test
    fun testQueueCommandsForwardedWhenSameAccount() = kotlinx.coroutines.runBlocking(Dispatchers.Default) {
        val port = Random.nextInt(20000, 40000)
        val sameAccountBridge = FakeRemoteHostBridge()
        val sameAccountHost = RemoteHostServer(
            hostNameProvider = { "Same Account Host" },
            hostDeviceIdProvider = { "host-device-same" },
            pairedDeviceStore = PairedDeviceStore(FakeCredentialStore()),
            bridge = sameAccountBridge,
            portProvider = { port },
            hostAccountIdProvider = { "Plex:acct-1:server-1" },
        )
        val sameAccountClient = RemoteControlClient(
            clientDeviceIdProvider = { "client-device-same" },
            clientDeviceNameProvider = { "Same Account iPhone" },
            pairedDeviceStore = PairedDeviceStore(FakeCredentialStore()),
            clientAccountIdProvider = { "Plex:acct-1:server-1" },
        )
        sameAccountHost.start(testScope!!)
        try {
            delay(100L)
            sameAccountClient.connect("127.0.0.1", port)

            val pairingRequest = withTimeout(5000L) {
                sameAccountHost.pendingPairings.first { it.isNotEmpty() }.first()
            }
            pairingRequest.approve()

            val state = withTimeout(5000L) {
                sameAccountClient.state.first { it.isConnected }
            }
            assertTrue(state.sameAccount)

            val track = Track(
                id = "track-remote",
                title = "Remote Song",
                artist = "Artist",
                album = "Album",
                durationMs = 200000L,
                streamUrl = "",
                downloadUrl = "",
            )
            sameAccountClient.replaceQueue(listOf(track), 0, shuffle = false)
            sameAccountClient.appendToQueue(listOf(track))
            sameAccountClient.insertNext(track)

            withTimeout(5000L) {
                while (sameAccountBridge.receivedCommands.size < 3) {
                    delay(50)
                }
            }

            assertEquals(
                listOf(
                    RemoteCommand.ReplaceQueue(listOf(track), 0, shuffle = false),
                    RemoteCommand.AppendToQueue(listOf(track)),
                    RemoteCommand.InsertNext(track),
                ),
                sameAccountBridge.receivedCommands,
            )
        } finally {
            sameAccountClient.disconnect()
            sameAccountHost.stop()
        }
    }

    @Test
    fun testQueueCommandsRejectedWhenDifferentAccount() = kotlinx.coroutines.runBlocking(Dispatchers.Default) {
        val port = Random.nextInt(20000, 40000)
        val differentAccountBridge = FakeRemoteHostBridge()
        val differentAccountHost = RemoteHostServer(
            hostNameProvider = { "Different Account Host" },
            hostDeviceIdProvider = { "host-device-diff" },
            pairedDeviceStore = PairedDeviceStore(FakeCredentialStore()),
            bridge = differentAccountBridge,
            portProvider = { port },
            hostAccountIdProvider = { "Plex:acct-1:server-1" },
        )
        val differentAccountClient = RemoteControlClient(
            clientDeviceIdProvider = { "client-device-diff" },
            clientDeviceNameProvider = { "Guest iPhone" },
            pairedDeviceStore = PairedDeviceStore(FakeCredentialStore()),
            clientAccountIdProvider = { "Plex:acct-2:server-1" },
        )
        differentAccountHost.start(testScope!!)
        try {
            delay(100L)
            differentAccountClient.connect("127.0.0.1", port)

            val pairingRequest = withTimeout(5000L) {
                differentAccountHost.pendingPairings.first { it.isNotEmpty() }.first()
            }
            pairingRequest.approve()

            val state = withTimeout(5000L) {
                differentAccountClient.state.first { it.isConnected }
            }
            assertFalse(state.sameAccount)

            val track = Track(
                id = "track-remote",
                title = "Remote Song",
                artist = "Artist",
                album = "Album",
                durationMs = 200000L,
                streamUrl = "",
                downloadUrl = "",
            )
            differentAccountClient.replaceQueue(listOf(track), 0, shuffle = false)
            // Existing transport controls remain unaffected regardless of account match.
            differentAccountClient.togglePlayPause()

            withTimeout(5000L) {
                while (differentAccountBridge.receivedCommands.isEmpty()) {
                    delay(50)
                }
            }

            assertEquals(listOf<RemoteCommand>(RemoteCommand.TogglePlayPause), differentAccountBridge.receivedCommands)
        } finally {
            differentAccountClient.disconnect()
            differentAccountHost.stop()
        }
    }

    @Test
    fun testUdpDiscovery() = kotlinx.coroutines.runBlocking(Dispatchers.Default) {
        val discoveryPort = Random.nextInt(20000, 40000)
        val discoveryServer = RemoteDiscoveryServer(
            hostNameProvider = { "Test Desktop Host" },
            hostDeviceIdProvider = { "host-device-42" },
            tcpPortProvider = { testPort },
            discoveryPort = discoveryPort,
        )
        discoveryServer.start(testScope!!)
        delay(100L)

        val discoveryClient = RemoteDiscoveryClient(discoveryPort = discoveryPort, intervalMs = 300L)
        val discovered = withTimeout(5000L) {
            discoveryClient.discover().first { it.deviceId == "host-device-42" }
        }

        assertEquals("Test Desktop Host", discovered.name)
        assertEquals("host-device-42", discovered.deviceId)
        assertEquals(testPort, discovered.tcpPort)

        discoveryServer.stop()
    }
}
