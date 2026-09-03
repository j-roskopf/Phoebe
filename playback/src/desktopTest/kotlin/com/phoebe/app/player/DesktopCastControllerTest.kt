package com.phoebe.app.player

import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.domain.PlayerState
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopCastControllerTest {
    @Test
    fun completedIdleReasonCountsAsFinishedPlayback() {
        assertTrue(desktopCastIsFinishedIdleReason("FINISHED"))
        assertTrue(desktopCastIsFinishedIdleReason("COMPLETED"))
        assertTrue(desktopCastIsFinishedIdleReason(null))
        assertFalse(desktopCastIsFinishedIdleReason("INTERRUPTED"))
    }

    @Test
    fun selectingDeviceCastsCurrentLocalQueueAndSuspendsLocalPlayback() = runTest {
        val queue = listOf(testTrack("jellyfin:1"), testTrack("plex:2"))
        val player = FakeAudioPlayer().also { it.play(queue, 0) }
        val connection = FakeDesktopCastConnection(volume = 0.44f)
        val controller = newController(player, connection)

        controller.showDevicePicker()
        runCurrent()

        assertTrue(connection.connected)
        assertTrue(connection.receiverLaunched)
        assertEquals("jellyfin:1", connection.loadRequests.single().media.trackId)
        assertTrue(player.suspendCalls >= 1)
        assertTrue(controller.state.value.isConnected)
        assertTrue(controller.state.value.isPlaying)
        assertEquals(0.44f, controller.state.value.volume)
    }

    @Test
    fun selectingDeviceCastsSubsonicAndRadioQueues() = runTest {
        val queue = listOf(
            testTrack("navidrome:1", streamUrl = "http://navidrome.example/rest/stream.view?id=1"),
            radioTrack(),
        )
        val player = FakeAudioPlayer().also { it.play(queue, 0) }
        val connection = FakeDesktopCastConnection()
        val controller = newController(player, connection)

        controller.showDevicePicker()
        runCurrent()

        assertEquals("navidrome:1", connection.loadRequests.single().media.trackId)
        assertTrue(controller.state.value.isConnected)
        assertEquals(null, controller.state.value.message)
    }

    @Test
    fun radioStreamsCastAsLiveMediaWithHlsContentType() = runTest {
        val player = FakeAudioPlayer()
        val connection = FakeDesktopCastConnection()
        val controller = newController(player, connection)
        controller.showDevicePicker()
        runCurrent()

        controller.loadQueue(listOf(radioTrack()), 0)
        runCurrent()

        val media = connection.loadRequests.single().media
        assertEquals("application/x-mpegurl", media.contentType)
        assertTrue(media.isLiveStream)
    }

    @Test
    fun localOnlySongNamesItselfWhenItBlocksTheQueue() = runTest {
        val localOnly = Track(
            id = "local_1:track:1",
            title = "On This Laptop",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000L,
            streamUrl = "",
            downloadUrl = "",
            localUri = "file:///music/local.mp3",
        )
        val player = FakeAudioPlayer()
        val connection = FakeDesktopCastConnection()
        val controller = newController(player, connection)
        controller.showDevicePicker()
        runCurrent()

        controller.loadQueue(listOf(testTrack("navidrome:1"), localOnly), 0)
        runCurrent()

        assertTrue(connection.loadRequests.isEmpty())
        assertEquals(
            "“On This Laptop” plays from this device, so it can't be cast.",
            controller.state.value.message,
        )
    }

    @Test
    fun loadFailureRestoresLocalPlayback() = runTest {
        val queue = listOf(testTrack("jellyfin:1"))
        val player = FakeAudioPlayer()
        val connection = FakeDesktopCastConnection()
        val controller = newController(player, connection)
        controller.showDevicePicker()
        runCurrent()
        player.play(queue, 0)
        connection.failLoads = true

        controller.loadQueue(queue, 0)
        runCurrent()

        assertEquals(2, player.playCalls)
        assertEquals(queue, player.state.value.queue)
        assertEquals(0, player.state.value.currentIndex)
        assertTrue(player.state.value.isPlaying)
        assertFalse(controller.state.value.isPlaying)
        assertTrue(controller.state.value.queue.isEmpty())
    }

    @Test
    fun loadTimeoutRestoresLocalPlayback() = runTest {
        val queue = listOf(testTrack("jellyfin:1"))
        val player = FakeAudioPlayer()
        val connection = FakeDesktopCastConnection(loadDelayMs = 1_000L)
        val controller = newController(
            player = player,
            connection = connection,
            loadTimeoutMs = 50L,
        )
        controller.showDevicePicker()
        runCurrent()
        player.play(queue, 0)

        controller.loadQueue(queue, 0)
        runCurrent()
        advanceTimeBy(51L)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(2, player.playCalls)
        assertEquals(queue, player.state.value.queue)
        assertTrue(player.state.value.isPlaying)
        assertFalse(controller.state.value.isPlaying)
        assertTrue(controller.state.value.queue.isEmpty())
    }

    @Test
    fun timedOutLoadThatActuallyReachedReceiverKeepsCastingRequestedTrack() = runTest {
        val queue = listOf(testTrack("jellyfin:1"), testTrack("jellyfin:2"))
        val player = FakeAudioPlayer().also { it.play(queue, 0) }
        val connection = FakeDesktopCastConnection()
        val controller = newController(player, connection)
        controller.showDevicePicker()
        runCurrent()
        connection.timeoutAfterUpdatingStatus = true

        controller.loadQueue(queue, 1)
        runCurrent()

        assertEquals(listOf("jellyfin:1", "jellyfin:2"), connection.loadRequests.map { it.media.trackId })
        assertEquals("jellyfin:2", controller.state.value.currentTrack?.id)
        assertTrue(controller.state.value.isPlaying)
        assertFalse(controller.state.value.isBuffering)
        assertFalse(player.state.value.isPlaying)
    }

    @Test
    fun statusPollingDoesNotOverwritePendingSongSwitchWithOldMedia() = runTest {
        val queue = listOf(testTrack("jellyfin:1"), testTrack("jellyfin:2"))
        val player = FakeAudioPlayer().also { it.play(queue, 0) }
        val connection = FakeDesktopCastConnection()
        val controller = newController(player, connection)
        controller.showDevicePicker()
        runCurrent()
        val statusReadsBeforeSwitch = connection.readStatusCalls
        connection.loadDelayMs = 2_000L

        controller.loadQueue(queue, 1)
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(statusReadsBeforeSwitch, connection.readStatusCalls)
        assertEquals("jellyfin:2", controller.state.value.currentTrack?.id)
        assertTrue(controller.state.value.isBuffering)

        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals("jellyfin:2", controller.state.value.currentTrack?.id)
        assertTrue(controller.state.value.isPlaying)
    }

    @Test
    fun timelineAdvancesOptimisticallyWhenStatusPollIsUnavailable() = runTest {
        val queue = listOf(testTrack("jellyfin:1"))
        val player = FakeAudioPlayer().also { it.play(queue, 0) }
        val connection = FakeDesktopCastConnection()
        val controller = newController(player, connection)
        controller.showDevicePicker()
        runCurrent()
        controller.seekTo(10_000L)
        runCurrent()
        connection.returnNullStatus = true

        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(11_000L, controller.state.value.positionMs)
        assertTrue(controller.state.value.isPlaying)
    }

    @Test
    fun estimatedTrackEndAdvancesWhenReceiverNeverReportsFinished() = runTest {
        val queue = listOf(testTrack("jellyfin:1"), testTrack("jellyfin:2"))
        val player = FakeAudioPlayer().also { it.play(queue, 0) }
        val connection = FakeDesktopCastConnection()
        val controller = newController(player, connection)
        controller.showDevicePicker()
        runCurrent()
        connection.status = connection.status?.copy(
            isPlaying = true,
            isBuffering = false,
            positionMs = 0L,
            durationMs = 60_000L,
            isFinished = false,
        )

        advanceTimeBy(60_750L)
        runCurrent()

        assertEquals(listOf("jellyfin:1", "jellyfin:2"), connection.loadRequests.map { it.media.trackId })
        assertEquals("jellyfin:2", controller.state.value.currentTrack?.id)
        assertTrue(controller.state.value.isPlaying)
    }

    @Test
    fun finishedRemotePlaybackAdvancesThroughPhoebeQueue() = runTest {
        val queue = listOf(testTrack("jellyfin:1"), testTrack("jellyfin:2"))
        val player = FakeAudioPlayer().also { it.play(queue, 0) }
        val connection = FakeDesktopCastConnection()
        val controller = newController(player, connection)
        controller.showDevicePicker()
        runCurrent()
        connection.status = connection.status?.copy(isFinished = true, isPlaying = false)

        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf("jellyfin:1", "jellyfin:2"), connection.loadRequests.map { it.media.trackId })
        assertEquals("jellyfin:2", controller.state.value.currentTrack?.id)
    }

    @Test
    fun receiverDroppingMediaNearEndAdvancesThroughPhoebeQueue() = runTest {
        val queue = listOf(testTrack("jellyfin:1"), testTrack("jellyfin:2"))
        val player = FakeAudioPlayer().also { it.play(queue, 0) }
        val connection = FakeDesktopCastConnection()
        val controller = newController(player, connection)
        controller.showDevicePicker()
        runCurrent()
        connection.status = connection.status?.copy(
            media = null,
            isPlaying = false,
            isBuffering = false,
            positionMs = 0L,
            durationMs = 0L,
        )
        controller.seekTo(58_500L)
        runCurrent()

        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf("jellyfin:1", "jellyfin:2"), connection.loadRequests.map { it.media.trackId })
        assertEquals("jellyfin:2", controller.state.value.currentTrack?.id)
    }

    @Test
    fun castButtonWhileConnectedShowsConnectedDialogControls() = runTest {
        val queue = listOf(testTrack("jellyfin:1"))
        val player = FakeAudioPlayer().also { it.play(queue, 0) }
        val connection = FakeDesktopCastConnection(volume = 0.5f)
        val picker = RecordingDevicePicker()
        val controller = newController(player, connection, picker = picker)
        controller.showDevicePicker()
        runCurrent()

        controller.showDevicePicker()
        runCurrent()

        val connectedDialog = picker.connectedDialog ?: error("Expected connected Cast dialog.")
        assertEquals("Living Room", connectedDialog.state.deviceName)
        connectedDialog.onVolume(0.25f)
        runCurrent()
        assertEquals(0.25f, connection.currentVolume)
        connectedDialog.onDisconnect()
        runCurrent()
        assertTrue(connection.stopped)
        assertFalse(controller.state.value.isConnected)
    }

    @Test
    fun previousLoadsPreviousQueueItem() = runTest {
        val queue = listOf(testTrack("jellyfin:1"), testTrack("jellyfin:2"))
        val player = FakeAudioPlayer().also { it.play(queue, 1) }
        val connection = FakeDesktopCastConnection()
        val controller = newController(player, connection)
        controller.showDevicePicker()
        runCurrent()

        controller.previous()
        runCurrent()

        assertEquals(listOf("jellyfin:2", "jellyfin:1"), connection.loadRequests.map { it.media.trackId })
        assertEquals("jellyfin:1", controller.state.value.currentTrack?.id)
    }

    @Test
    fun disconnectRestoresLocalQueueAndPosition() = runTest {
        val queue = listOf(testTrack("jellyfin:1"))
        val player = FakeAudioPlayer().also { it.play(queue, 0) }
        val connection = FakeDesktopCastConnection()
        val controller = newController(player, connection)
        controller.showDevicePicker()
        runCurrent()
        controller.seekTo(12_000L)
        runCurrent()

        controller.disconnect()
        runCurrent()

        assertTrue(connection.stopped)
        assertFalse(controller.state.value.isConnected)
        assertEquals(queue, player.state.value.queue)
        assertEquals(0, player.state.value.currentIndex)
        assertEquals(12_000L, player.state.value.positionMs)
        assertTrue(player.state.value.isPlaying)
    }

    @Test
    fun setVolumeRoutesToConnectedCastDevice() = runTest {
        val player = FakeAudioPlayer()
        val connection = FakeDesktopCastConnection(volume = 0.7f)
        val controller = newController(player, connection)
        controller.showDevicePicker()
        runCurrent()

        assertTrue(controller.setVolume(0.32f))
        runCurrent()

        assertEquals(0.32f, connection.currentVolume)
        assertEquals(0.32f, controller.state.value.volume)
    }

    @Test
    fun castHandoffDuringCrossfadeLoadsOutgoingTrackAndSuspendsLocalPlayback() = runTest {
        val queue = listOf(testTrack("jellyfin:1"), testTrack("jellyfin:2"))
        val player = CrossfadeCapableAudioPlayer()
        player.setCrossfadeDurationMs(6_000)
        player.play(queue, 0)
        player.platformPlayback(positionMs = 55_000, durationMs = 60_000, bufferedPositionMs = 60_000)

        assertEquals(1, player.crossfadeStarts)
        assertEquals(queue[0], player.state.value.currentTrack)

        val connection = FakeDesktopCastConnection()
        val controller = newController(player, connection)
        controller.showDevicePicker()
        runCurrent()

        assertEquals("jellyfin:1", connection.loadRequests.single().media.trackId)
        assertFalse(player.state.value.isPlaying)
        assertEquals(queue[0], player.state.value.currentTrack)
        assertTrue(player.stopCalls >= 1)
        assertTrue(controller.state.value.isConnected)
    }

    @Test
    fun castHandoffAfterCrossfadeCommitLoadsIncomingTrack() = runTest {
        val queue = listOf(testTrack("jellyfin:1"), testTrack("jellyfin:2"))
        val player = CrossfadeCapableAudioPlayer()
        player.setCrossfadeDurationMs(6_000)
        player.play(queue, 0)
        player.platformPlayback(positionMs = 55_000, durationMs = 60_000, bufferedPositionMs = 60_000)
        player.commitCrossfade(positionMs = 6_000)

        assertEquals(queue[1], player.state.value.currentTrack)

        val connection = FakeDesktopCastConnection()
        val controller = newController(player, connection)
        controller.showDevicePicker()
        runCurrent()

        assertEquals("jellyfin:2", connection.loadRequests.single().media.trackId)
        assertFalse(player.state.value.isPlaying)
        assertTrue(controller.state.value.isConnected)
    }

    @Test
    fun disconnectAfterCastRestoresLocalQueueWithCrossfadeStillEnabled() = runTest {
        val queue = listOf(testTrack("jellyfin:1"), testTrack("jellyfin:2"))
        val player = CrossfadeCapableAudioPlayer()
        player.setCrossfadeDurationMs(6_000)
        player.play(queue, 0)
        val connection = FakeDesktopCastConnection()
        val controller = newController(player, connection)
        controller.showDevicePicker()
        runCurrent()
        controller.seekTo(12_000L)
        runCurrent()

        controller.disconnect()
        runCurrent()

        assertFalse(controller.state.value.isConnected)
        assertEquals(queue, player.state.value.queue)
        assertEquals(0, player.state.value.currentIndex)
        assertEquals(12_000L, player.state.value.positionMs)
        assertTrue(player.state.value.isPlaying)

        player.platformPlayback(positionMs = 55_000, durationMs = 60_000, bufferedPositionMs = 60_000)
        assertEquals(1, player.crossfadeStarts)
    }

    @Test
    fun receiverTrackEndDoesNotStartLocalCrossfade() = runTest {
        val queue = listOf(testTrack("jellyfin:1"), testTrack("jellyfin:2"))
        val player = CrossfadeCapableAudioPlayer()
        player.setCrossfadeDurationMs(6_000)
        player.play(queue, 0)
        val connection = FakeDesktopCastConnection()
        val controller = newController(player, connection)
        controller.showDevicePicker()
        runCurrent()
        val crossfadeStartsBeforeEnd = player.crossfadeStarts

        connection.status = connection.status?.copy(isFinished = true, isPlaying = false)
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals("jellyfin:2", controller.state.value.currentTrack?.id)
        assertEquals(crossfadeStartsBeforeEnd, player.crossfadeStarts)
        assertFalse(player.state.value.isPlaying)
    }

    private fun TestScope.newController(
        player: AudioPlayer,
        connection: FakeDesktopCastConnection,
        loadTimeoutMs: Long = 30_000L,
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        picker: DesktopCastDevicePicker = AutoSelectingDevicePicker,
    ): DesktopCastController =
        DesktopCastController(
            audioPlayer = player,
            transport = FakeDesktopCastTransport(connection),
            devicePicker = picker,
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            pickerRefreshMs = 0L,
            loadTimeoutMs = loadTimeoutMs,
        )

    private fun testTrack(id: String, streamUrl: String = "https://music.example/$id.mp3"): Track =
        Track(
            id = id,
            title = id,
            artist = "Artist",
            album = "Album",
            durationMs = 60_000L,
            streamUrl = streamUrl,
            downloadUrl = "",
            audioCodec = "mp3",
        )

    private fun radioTrack(): Track =
        Track(
            id = "radio:station-1",
            title = "Some Station",
            artist = "Radio",
            album = "Radio",
            durationMs = 0L,
            streamUrl = "https://stream.example/zc1201/hls.m3u8",
            downloadUrl = "https://stream.example/zc1201/hls.m3u8",
        )
}

private object AutoSelectingDevicePicker : DesktopCastDevicePicker {
    override fun show(
        devices: List<DesktopCastDevice>,
        onSelected: (DesktopCastDevice) -> Unit,
        onDismiss: () -> Unit,
    ) {
        onSelected(devices.first())
        onDismiss()
    }

    override fun showConnected(
        state: DesktopConnectedCastDialogState,
        onVolume: (Float) -> Unit,
        onDisconnect: () -> Unit,
        onSwitchDevice: () -> Unit,
        onDismiss: () -> Unit,
    ) = Unit
}

private class RecordingDevicePicker : DesktopCastDevicePicker {
    var connectedDialog: ConnectedDialog? = null

    override fun show(
        devices: List<DesktopCastDevice>,
        onSelected: (DesktopCastDevice) -> Unit,
        onDismiss: () -> Unit,
    ) {
        onSelected(devices.first())
        onDismiss()
    }

    override fun showConnected(
        state: DesktopConnectedCastDialogState,
        onVolume: (Float) -> Unit,
        onDisconnect: () -> Unit,
        onSwitchDevice: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        connectedDialog = ConnectedDialog(state, onVolume, onDisconnect, onSwitchDevice, onDismiss)
    }
}

private data class ConnectedDialog(
    val state: DesktopConnectedCastDialogState,
    val onVolume: (Float) -> Unit,
    val onDisconnect: () -> Unit,
    val onSwitchDevice: () -> Unit,
    val onDismiss: () -> Unit,
)

private class FakeDesktopCastTransport(
    private val connection: FakeDesktopCastConnection,
) : DesktopCastTransport {
    private val device = DesktopCastDevice(id = "living-room", displayName = "Living Room")
    override suspend fun startDiscovery() = Unit
    override suspend fun refreshDiscovery() = Unit
    override suspend fun devices(): List<DesktopCastDevice> = listOf(device)
    override suspend fun connect(device: DesktopCastDevice): DesktopCastConnection {
        connection.deviceOverride = device
        return connection
    }
}

private data class FakeLoadRequest(
    val media: DesktopCastMedia,
    val startPositionMs: Long,
)

private class FakeDesktopCastConnection(
    volume: Float = 0.7f,
    var loadDelayMs: Long = 0L,
) : DesktopCastConnection {
    var deviceOverride: DesktopCastDevice? = null
    override val device: DesktopCastDevice
        get() = deviceOverride ?: DesktopCastDevice(id = "living-room", displayName = "Living Room")
    var connected = false
    var receiverLaunched = false
    var stopped = false
    var failLoads = false
    var timeoutAfterUpdatingStatus = false
    var returnNullStatus = false
    var currentVolume = volume
    var readStatusCalls = 0
    val loadRequests = mutableListOf<FakeLoadRequest>()
    var status: DesktopCastStatus? = null

    override fun connect() {
        connected = true
    }

    override fun ensureDefaultMediaReceiver() {
        receiverLaunched = true
    }

    override suspend fun load(media: DesktopCastMedia, startPositionMs: Long): DesktopCastStatus? {
        if (loadDelayMs > 0L) delay(loadDelayMs)
        if (failLoads) error("Couldn't load on Chromecast. Playing on this device.")
        loadRequests += FakeLoadRequest(media, startPositionMs)
        status = DesktopCastStatus(
            deviceName = device.displayName,
            media = media.toRemoteMedia(),
            isPlaying = true,
            isBuffering = false,
            positionMs = startPositionMs,
            durationMs = media.durationMs,
            volume = currentVolume,
            isFinished = false,
        )
        if (timeoutAfterUpdatingStatus) {
            timeoutAfterUpdatingStatus = false
            error("request timed out")
        }
        return status
    }

    override fun play() {
        status = status?.copy(isPlaying = true, isBuffering = false)
    }

    override fun pause() {
        status = status?.copy(isPlaying = false, isBuffering = false)
    }

    override fun seekTo(positionMs: Long) {
        status = status?.copy(positionMs = positionMs)
    }

    override fun readStatus(): DesktopCastStatus? {
        readStatusCalls++
        if (returnNullStatus) return null
        return status
    }

    override fun readVolume(): Float? = currentVolume

    override fun setVolume(volume: Float) {
        currentVolume = volume
        status = status?.copy(volume = volume)
    }

    override fun stopApp() {
        stopped = true
    }

    override fun disconnect() {
        connected = false
    }
}

private fun DesktopCastMedia.toRemoteMedia(): DesktopCastRemoteMedia =
    DesktopCastRemoteMedia(
        trackId = trackId,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        streamUrl = streamUrl,
        castUrl = castUrl,
        downloadUrl = downloadUrl,
        thumbUrl = thumbUrl,
        filepath = filepath,
        audioCodec = audioCodec,
        contentId = castUrl,
    )

private class FakeAudioPlayer : AudioPlayer {
    private val mutableState = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = mutableState
    var playCalls = 0
    var prepareCalls = 0
    var suspendCalls = 0

    override fun play(queue: List<Track>, startIndex: Int) {
        playCalls++
        val index = startIndex.coerceIn(queue.indices)
        mutableState.value = mutableState.value.copy(
            queue = queue,
            currentIndex = index,
            isPlaying = true,
            isBuffering = false,
            positionMs = 0L,
            durationMs = queue[index].durationMs,
        )
    }

    override fun prepare(queue: List<Track>, startIndex: Int, positionMs: Long) {
        prepareCalls++
        val index = startIndex.coerceIn(queue.indices)
        mutableState.value = mutableState.value.copy(
            queue = queue,
            currentIndex = index,
            isPlaying = false,
            isBuffering = false,
            positionMs = positionMs,
            durationMs = queue[index].durationMs,
        )
    }

    override fun suspendPlayback(queue: List<Track>, startIndex: Int, positionMs: Long) {
        suspendCalls++
        val index = startIndex.coerceIn(queue.indices)
        mutableState.value = mutableState.value.copy(
            queue = queue,
            currentIndex = index,
            isPlaying = false,
            isBuffering = false,
            positionMs = positionMs,
            durationMs = queue[index].durationMs,
        )
    }

    override fun togglePlayPause() {
        mutableState.value = mutableState.value.let { it.copy(isPlaying = !it.isPlaying) }
    }

    override fun clearQueue() {
        mutableState.value = mutableState.value.copy(queue = emptyList(), currentIndex = -1)
    }

    override fun stopPlayback() {
        mutableState.value = PlayerState(volume = mutableState.value.volume)
    }

    override fun addToUpNext(track: Track) = Unit
    override fun appendToQueue(tracks: List<Track>) = Unit
    override fun moveUpNext(fromIndex: Int, toIndex: Int) = Unit
    override fun removeUpNext(index: Int) = Unit
    override fun next() = Unit
    override fun previous() = Unit
    override fun seekTo(positionMs: Long) {
        mutableState.value = mutableState.value.copy(positionMs = positionMs)
    }

    override fun setShuffle(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(shuffle = enabled)
    }

    override fun setRepeat(mode: RepeatMode) {
        mutableState.value = mutableState.value.copy(repeat = mode)
    }

    override fun setVolume(volume: Float) {
        mutableState.value = mutableState.value.copy(volume = volume)
    }

    override fun setCrossfadeDurationMs(durationMs: Long) = Unit
    override fun setEqualizer(profile: EqualizerProfile) = Unit
    override fun setUnityOutputVolume() = Unit
    override fun updateReportedVolume(volume: Float) {
        setVolume(volume)
    }
}

private class CrossfadeCapableAudioPlayer : SimpleAudioPlayer() {
    var crossfadeStarts = 0
    var stopCalls = 0
    private var pendingQueue: List<Track> = emptyList()
    private var pendingTargetIndex = -1
    private var pendingGeneration = -1

    override fun playUri(uri: String) {
        markPlaybackReady()
    }

    override fun stopCurrentPlaybackImmediately() {
        stopCalls++
    }

    override fun startCrossfadeOnPlatform(
        queue: List<Track>,
        targetIndex: Int,
        track: Track,
        durationMs: Long,
        baseVolume: Float,
        generation: Int,
    ): Boolean {
        crossfadeStarts++
        pendingQueue = queue
        pendingTargetIndex = targetIndex
        pendingGeneration = generation
        return true
    }

    fun platformPlayback(
        positionMs: Long,
        durationMs: Long,
        bufferedPositionMs: Long,
        isPlaying: Boolean = true,
    ) {
        applyPlatformPlayback(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            isBuffering = false,
            bufferedPositionMs = bufferedPositionMs,
        )
    }

    fun commitCrossfade(positionMs: Long) {
        adoptCrossfadeTarget(pendingQueue, pendingTargetIndex, positionMs, pendingGeneration)
    }
}
