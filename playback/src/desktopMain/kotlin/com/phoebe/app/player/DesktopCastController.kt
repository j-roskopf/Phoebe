package com.phoebe.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.PlatformStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import su.litvak.chromecast.api.v2.ChromeCast
import su.litvak.chromecast.api.v2.ChromeCastException
import su.litvak.chromecast.api.v2.Media as CastV2Media
import su.litvak.chromecast.api.v2.MediaStatus as CastV2MediaStatus
import java.awt.Dialog
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Window
import javax.swing.JDialog
import kotlin.math.roundToInt

actual fun createCastController(audioPlayer: AudioPlayer): CastController =
    DesktopCastController(audioPlayer = audioPlayer)

private const val DefaultMediaReceiverAppId = "CC1AD845"
private const val AppearanceThemeFile = "appearance_theme"
private const val AppearanceTintFile = "appearance_tint"

private data class PendingDesktopCastHandoff(
    val queue: List<Track>,
    val index: Int,
    val positionMs: Long,
    val wasLocalPlaying: Boolean,
    val restoreLocalOnFailure: Boolean,
    val requestId: Long,
)

private data class DesktopCastQueueSnapshot(
    val queue: List<Track>,
    val currentIndex: Int,
)

private data class DesktopTrackEndWatchdogKey(
    val trackId: String,
    val index: Int,
)

internal class DesktopCastController(
    private val audioPlayer: AudioPlayer,
    private val transport: DesktopCastTransport = CastV2DesktopCastTransport(),
    private val devicePicker: DesktopCastDevicePicker = ComposeDesktopCastDevicePicker,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pickerRefreshMs: Long = 1_200L,
    private val loadTimeoutMs: Long = 30_000L,
) : CastController {
    private val mutableState = MutableStateFlow(
        CastState(
            isAvailable = true,
        ),
    )
    override val state: StateFlow<CastState> = mutableState

    private var currentConnection: DesktopCastConnection? = null
    private var positionJob: Job? = null
    private var loadTimeoutJob: Job? = null
    private var statusSyncJob: Job? = null
    private var trackEndWatchdogJob: Job? = null
    private var trackEndWatchdogKey: DesktopTrackEndWatchdogKey? = null
    private var pendingHandoff: PendingDesktopCastHandoff? = null
    private var appQueueSnapshot: DesktopCastQueueSnapshot? = null
    private var loadRequestId = 0L
    private val castRequestMutex = Mutex()

    init {
        scope.launch {
            runTransport("start Chromecast discovery") {
                transport.startDiscovery()
            }.onFailure { error ->
                PhoebeLog.d("DesktopCastController") { "Chromecast discovery failed: ${error.message}" }
                mutableState.update {
                    it.copy(message = "Chromecast discovery couldn't start. Check local network access.")
                }
            }
        }
    }

    override fun canLoadQueue(queue: List<Track>): CastQueueSupport =
        queue.remoteChromecastQueueSupport()

    override fun showDevicePicker() {
        val connection = currentConnection
        val current = mutableState.value
        if (connection != null && current.isConnected) {
            showConnectedDeviceDialog(connection, current)
            return
        }
        showDeviceSelection()
    }

    private fun showDeviceSelection() {
        mutableState.update { it.copy(isAvailable = true, isBuffering = true, message = null) }
        scope.launch {
            runTransport("refresh Chromecast discovery") {
                transport.refreshDiscovery()
            }.onFailure { error ->
                PhoebeLog.d("DesktopCastController") { "Chromecast discovery refresh failed: ${error.message}" }
            }
            delay(pickerRefreshMs)
            val devices = runTransport("read Chromecast devices") {
                transport.devices()
            }.getOrElse { error ->
                mutableState.update {
                    it.copy(
                        isBuffering = false,
                        message = error.message ?: "Couldn't read Chromecast devices.",
                    )
                }
                return@launch
            }
            if (devices.isEmpty()) {
                mutableState.update {
                    it.copy(
                        isBuffering = false,
                        message = "No Chromecast devices found on this network.",
                    )
                }
                return@launch
            }
            devicePicker.show(
                devices = devices,
                onSelected = { device -> connectToDevice(device) },
                onDismiss = {
                    mutableState.update { it.copy(isBuffering = false) }
                },
            )
        }
    }

    private fun showConnectedDeviceDialog(connection: DesktopCastConnection, castState: CastState) {
        mutableState.update { it.copy(isBuffering = false, message = null) }
        val dialogState = DesktopConnectedCastDialogState(
            deviceName = castState.deviceName ?: connection.device.displayName,
            model = connection.device.model,
            trackTitle = castState.currentTrack?.title,
            trackArtist = castState.currentTrack?.artist,
            isPlaying = castState.isPlaying,
            volume = (castState.volume ?: 0.7f).coerceIn(0f, 1f),
        )
        devicePicker.showConnected(
            state = dialogState,
            onVolume = { volume -> setVolume(volume) },
            onDisconnect = { disconnect() },
            onSwitchDevice = { showDeviceSelection() },
            onDismiss = {
                mutableState.update { it.copy(isBuffering = false) }
            },
        )
    }

    override fun disconnect() {
        scope.launch {
            runCastRequest("disconnect Chromecast") {
                currentConnection?.stopApp()
                currentConnection?.disconnect()
            }
            currentConnection = null
            disconnectState(restoreLocalPlayback = true)
        }
    }

    override fun loadQueue(queue: List<Track>, startIndex: Int, startPositionMs: Long) {
        loadQueueInternal(queue, startIndex, startPositionMs = startPositionMs, restoreLocalOnFailure = true)
    }

    override fun togglePlayPause() {
        val connection = currentConnection ?: return
        scope.launch {
            runCastRequest("toggle Chromecast playback") {
                if (mutableState.value.isPlaying) {
                    connection.pause()
                } else {
                    connection.play()
                }
            }.onSuccess {
                syncRemotePlaybackNow()
            }.onFailure { error ->
                mutableState.update {
                    it.copy(message = error.message ?: "Couldn't control Chromecast playback.")
                }
            }
        }
    }

    override fun next() {
        val current = mutableState.value
        val target = current.currentIndex + 1
        if (target in current.queue.indices) {
            loadQueueInternal(current.queue, target, startPositionMs = 0L, restoreLocalOnFailure = true)
        }
    }

    override fun previous() {
        val current = mutableState.value
        val target = (current.currentIndex - 1).coerceAtLeast(0)
        if (target in current.queue.indices && target != current.currentIndex) {
            loadQueueInternal(current.queue, target, startPositionMs = 0L, restoreLocalOnFailure = true)
        }
    }

    override fun seekTo(positionMs: Long) {
        val connection = currentConnection ?: return
        val boundedPositionMs = positionMs.coerceAtLeast(0L)
        mutableState.update { it.copy(positionMs = boundedPositionMs) }
        cancelTrackEndWatchdog()
        refreshTrackEndWatchdog()
        scope.launch {
            runCastRequest("seek Chromecast playback") {
                connection.seekTo(boundedPositionMs)
            }.onSuccess {
                syncRemotePlaybackNow()
            }.onFailure { error ->
                mutableState.update {
                    it.copy(message = error.message ?: "Couldn't seek Chromecast playback.")
                }
            }
        }
    }

    override fun readVolume(): Float? =
        mutableState.value.volume

    override fun setVolume(volume: Float): Boolean {
        val connection = currentConnection ?: return false
        val clamped = volume.coerceIn(0f, 1f)
        mutableState.update { it.copy(volume = clamped) }
        scope.launch {
            runCastRequest("set Chromecast volume") {
                connection.setVolume(clamped)
                connection.readVolume()
            }.onSuccess { remoteVolume ->
                mutableState.update { it.copy(volume = remoteVolume ?: clamped) }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(message = error.message ?: "Couldn't change Chromecast volume.")
                }
            }
        }
        return true
    }

    private fun connectToDevice(device: DesktopCastDevice) {
        scope.launch {
            mutableState.update {
                it.copy(
                    isAvailable = true,
                    isBuffering = true,
                    message = "Connecting to ${device.displayName}...",
                )
            }
            val (connection, volume) = runCastRequest("connect to Chromecast") {
                currentConnection?.disconnect()
                transport.connect(device).also { nextConnection ->
                    nextConnection.connect()
                    nextConnection.ensureDefaultMediaReceiver()
                }.let { nextConnection ->
                    nextConnection to nextConnection.readVolume()
                }
            }.getOrElse { error ->
                PhoebeLog.d("DesktopCastController") { "Chromecast connect failed: ${error.message}" }
                mutableState.update {
                    it.copy(
                        isBuffering = false,
                        message = error.message ?: "Couldn't connect to ${device.displayName}.",
                    )
                }
                return@launch
            }
            currentConnection = connection
            mutableState.update {
                it.copy(
                    isAvailable = true,
                    isConnected = true,
                    deviceName = connection.device.displayName,
                    isBuffering = false,
                    volume = volume ?: it.volume,
                    message = null,
                )
            }
            startPositionSync()
            castCurrentQueueIfPossible()
        }
    }

    private fun castCurrentQueueIfPossible() {
        val castState = mutableState.value
        val queue = castState.queue.takeIf { it.isNotEmpty() } ?: audioPlayer.state.value.queue
        val index = if (castState.queue.isNotEmpty()) castState.currentIndex else audioPlayer.state.value.currentIndex
        val positionMs = if (castState.queue.isNotEmpty()) castState.positionMs else audioPlayer.state.value.positionMs
        if (queue.isEmpty() || index !in queue.indices) {
            mutableState.update {
                it.copy(message = "Start a remote streaming song before casting to Chromecast.")
            }
            return
        }
        val support = canLoadQueue(queue)
        if (!support.isSupported) {
            mutableState.update { it.copy(message = support.message) }
            return
        }
        loadQueueInternal(queue, index, positionMs, restoreLocalOnFailure = false)
    }

    private fun loadQueueInternal(
        queue: List<Track>,
        startIndex: Int,
        startPositionMs: Long,
        restoreLocalOnFailure: Boolean,
    ) {
        val support = canLoadQueue(queue)
        if (!support.isSupported) {
            mutableState.update { it.copy(isBuffering = false, message = support.message) }
            return
        }
        val connection = currentConnection
        if (connection == null) {
            mutableState.update { it.copy(message = "Choose a Chromecast before casting.") }
            showDevicePicker()
            return
        }
        val index = startIndex.coerceIn(queue.indices)
        val track = queue[index]
        val positionMs = startPositionMs.coerceAtLeast(0L)
        val localState = audioPlayer.state.value
        val previousCastState = mutableState.value
        val wasLocalPlaying = localState.isPlaying ||
            localState.isBuffering ||
            previousCastState.isPlaying ||
            previousCastState.isBuffering
        cancelTrackEndWatchdog()
        loadRequestId++
        val requestId = loadRequestId
        rememberAppQueue(queue, index)
        pendingHandoff = PendingDesktopCastHandoff(
            queue = queue,
            index = index,
            positionMs = positionMs,
            wasLocalPlaying = wasLocalPlaying,
            restoreLocalOnFailure = restoreLocalOnFailure,
            requestId = requestId,
        )
        mutableState.update {
            it.copy(
                isAvailable = true,
                isConnected = true,
                deviceName = connection.device.displayName,
                queue = queue,
                currentIndex = index,
                isPlaying = false,
                isBuffering = true,
                positionMs = positionMs,
                durationMs = track.durationMs,
                message = null,
            )
        }
        val media = track.toDesktopCastMedia()
        PhoebeLog.d("DesktopCastController") {
            "loading desktop cast media id=${media.trackId} type=${media.contentType}"
        }
        scheduleLoadTimeout(requestId)
        scope.launch {
            val result = runCastRequest("load Chromecast media") {
                withTimeout(loadTimeoutMs) {
                    connection.load(media, positionMs)
                }
            }
            if (pendingHandoff?.requestId != requestId) return@launch
            loadTimeoutJob?.cancel()
            loadTimeoutJob = null
            result
                .onSuccess { status ->
                    onCastLoadSucceeded(requestId, status)
                }
                .onFailure { error ->
                    PhoebeLog.d("DesktopCastController") { "Chromecast load failed: ${error.message}" }
                    verifyCastLoadAfterFailure(
                        requestId = requestId,
                        message = error.message ?: "Couldn't load on Chromecast. Playing on this device.",
                    )
                }
        }
        startPositionSync()
    }

    private fun onCastLoadSucceeded(requestId: Long, status: DesktopCastStatus?) {
        if (pendingHandoff?.requestId != requestId) return
        pendingHandoff = null
        suspendLocalPlayback()
        if (status != null) {
            applyRemoteStatus(status)
        }
        mutableState.update { it.copy(isPlaying = true, isBuffering = false, message = null) }
        refreshTrackEndWatchdog()
    }

    private fun onCastLoadFailed(requestId: Long, message: String) {
        val handoff = pendingHandoff?.takeIf { it.requestId == requestId } ?: return
        pendingHandoff = null
        appQueueSnapshot = null
        restoreLocalPlayback(handoff)
        mutableState.update {
            it.copy(
                queue = emptyList(),
                currentIndex = -1,
                isPlaying = false,
                isBuffering = false,
                positionMs = 0L,
                durationMs = 0L,
                message = message,
            )
        }
    }

    private fun disconnectState(restoreLocalPlayback: Boolean) {
        loadTimeoutJob?.cancel()
        loadTimeoutJob = null
        statusSyncJob?.cancel()
        statusSyncJob = null
        cancelTrackEndWatchdog()
        val pending = pendingHandoff
        pendingHandoff = null
        appQueueSnapshot = null
        val previous = mutableState.value
        if (restoreLocalPlayback) {
            if (pending != null) {
                restoreLocalPlayback(pending)
            } else if (previous.queue.isNotEmpty() && previous.currentIndex in previous.queue.indices) {
                restoreLocalPlayback(
                    queue = previous.queue,
                    index = previous.currentIndex,
                    positionMs = previous.positionMs,
                    shouldPlay = previous.isPlaying,
                )
            }
        }
        positionJob?.cancel()
        positionJob = null
        mutableState.update {
            it.copy(
                isConnected = false,
                deviceName = null,
                queue = emptyList(),
                currentIndex = -1,
                isPlaying = false,
                isBuffering = false,
                positionMs = 0L,
                durationMs = 0L,
                message = null,
            )
        }
    }

    private suspend fun syncRemotePlaybackNow() {
        val connection = currentConnection ?: return
        if (pendingHandoff != null) return
        val result = tryRunCastRequest("sync Chromecast playback") {
            connection.readStatus()
        } ?: return
        result.onSuccess { status ->
            if (status != null) {
                applyRemoteStatus(status)
            }
        }.onFailure { error ->
            PhoebeLog.d("DesktopCastController") { "Chromecast status sync failed: ${error.message}" }
            mutableState.update {
                it.copy(isBuffering = false, message = error.message ?: it.message)
            }
        }
    }

    private suspend fun verifyCastLoadAfterFailure(requestId: Long, message: String) {
        val handoff = pendingHandoff?.takeIf { it.requestId == requestId } ?: return
        val attempts = if (message.isLikelyCastTimeout()) {
            LoadFailureVerificationAttempts
        } else {
            1
        }
        repeat(attempts) { attempt ->
            if (attempt > 0) {
                delay(LoadFailureVerificationDelayMs)
            }
            val connection = currentConnection ?: return@repeat
            val status = tryRunCastRequest("verify Chromecast media after load failure") {
                connection.readStatus()
            }?.getOrNull()
            if (pendingHandoff?.requestId != requestId) return
            if (status?.matchesHandoff(handoff) == true) {
                loadTimeoutJob?.cancel()
                loadTimeoutJob = null
                onCastLoadSucceeded(requestId, status)
                return
            }
        }
        onCastLoadFailed(requestId, message)
    }

    private fun String.isLikelyCastTimeout(): Boolean {
        val normalized = lowercase()
        return "timeout" in normalized ||
            "timed out" in normalized ||
            "didn't respond" in normalized
    }

    private fun applyRemoteStatus(status: DesktopCastStatus) {
        val previous = mutableState.value
        val knownQueue = appQueueSnapshot?.queue?.takeIf { it.isNotEmpty() } ?: previous.queue
        val remoteTrack = status.media?.toTrack()
        val remoteCastUrl = status.media?.contentId
        val knownIndex = remoteTrack?.let { track ->
            knownQueue.indexOfFirst { it.matchesCastMedia(track, remoteCastUrl) }.takeIf { index -> index >= 0 }
        }
        val queue = when {
            knownQueue.isNotEmpty() -> knownQueue
            remoteTrack != null -> listOf(remoteTrack)
            else -> previous.queue
        }
        val currentIndex = knownIndex
            ?: previous.currentIndex.takeIf { it in queue.indices }
            ?: queue.takeIf { it.isNotEmpty() }?.indices?.first
            ?: -1
        if (shouldAdvanceToNext(status, previous, queue, currentIndex)) {
            loadQueueInternal(queue, currentIndex + 1, startPositionMs = 0L, restoreLocalOnFailure = true)
            return
        }
        if (queue.isNotEmpty() && currentIndex in queue.indices) {
            rememberAppQueue(queue, currentIndex)
        }
        val currentTrack = queue.getOrNull(currentIndex)
        mutableState.update {
            it.copy(
                isAvailable = true,
                isConnected = true,
                deviceName = status.deviceName ?: it.deviceName,
                queue = queue,
                currentIndex = currentIndex,
                isPlaying = status.isPlaying,
                isBuffering = status.isBuffering,
                positionMs = status.positionMs,
                durationMs = status.durationMs.takeIf { value -> value > 0L }
                    ?: currentTrack?.durationMs
                    ?: it.durationMs,
                volume = status.volume ?: it.volume,
                message = null,
            )
        }
        if (status.isPlaying) {
            suspendLocalPlayback()
        }
        refreshTrackEndWatchdog()
    }

    private fun shouldAdvanceToNext(
        status: DesktopCastStatus,
        previous: CastState,
        queue: List<Track>,
        currentIndex: Int,
    ): Boolean {
        if (pendingHandoff != null) return false
        if (currentIndex !in queue.indices || currentIndex >= queue.lastIndex) return false
        if (status.isFinished) return true
        if (status.isPlaying || status.isBuffering) return false
        if (status.durationMs > 0L && status.positionMs >= status.durationMs - EndOfTrackToleranceMs) {
            return true
        }
        return status.media == null && previous.wasNearEndOfCastTrack()
    }

    private fun CastState.wasNearEndOfCastTrack(): Boolean {
        val resolvedDurationMs = durationMs.takeIf { it > 0L } ?: currentTrack?.durationMs ?: 0L
        if (resolvedDurationMs <= 0L) return false
        return positionMs >= resolvedDurationMs - ReceiverDroppedMediaToleranceMs
    }

    private fun DesktopCastStatus.matchesHandoff(handoff: PendingDesktopCastHandoff): Boolean {
        val expectedTrack = handoff.queue.getOrNull(handoff.index) ?: return false
        val remoteTrack = media?.toTrack() ?: return false
        return expectedTrack.matchesCastMedia(remoteTrack, media.contentId)
    }

    private fun startPositionSync() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (isActive && mutableState.value.isConnected) {
                delay(1_000L)
                advanceOptimisticPosition(1_000L)
                if (statusSyncJob?.isActive != true) {
                    statusSyncJob = scope.launch {
                        syncRemotePlaybackNow()
                    }
                }
            }
        }
    }

    private fun refreshTrackEndWatchdog(state: CastState = mutableState.value) {
        if (pendingHandoff != null) {
            cancelTrackEndWatchdog()
            return
        }
        val track = state.currentTrack
        val index = state.currentIndex
        if (track == null) {
            cancelTrackEndWatchdog()
            return
        }
        val shouldWatch = state.isConnected &&
            state.isPlaying &&
            !state.isBuffering &&
            index in state.queue.indices &&
            index < state.queue.lastIndex
        if (!shouldWatch) {
            cancelTrackEndWatchdog()
            return
        }
        val durationMs = state.durationMs.takeIf { it > 0L } ?: track.durationMs
        if (durationMs <= 0L) {
            cancelTrackEndWatchdog()
            return
        }
        val key = DesktopTrackEndWatchdogKey(track.id, index)
        if (trackEndWatchdogKey == key && trackEndWatchdogJob?.isActive == true) return
        trackEndWatchdogJob?.cancel()
        trackEndWatchdogKey = key
        val remainingMs = (durationMs - state.positionMs).coerceAtLeast(0L)
        val queue = state.queue
        trackEndWatchdogJob = scope.launch {
            delay(remainingMs + TrackEndWatchdogGraceMs)
            val latest = mutableState.value
            val latestTrack = latest.currentTrack
            if (
                pendingHandoff == null &&
                latest.isConnected &&
                latest.isPlaying &&
                !latest.isBuffering &&
                latest.currentIndex == index &&
                latestTrack?.id == track.id &&
                latest.currentIndex < latest.queue.lastIndex
            ) {
                val nextQueue = latest.queue.takeIf { it.isNotEmpty() } ?: queue
                loadQueueInternal(nextQueue, latest.currentIndex + 1, startPositionMs = 0L, restoreLocalOnFailure = true)
            }
        }
    }

    private fun cancelTrackEndWatchdog() {
        trackEndWatchdogJob?.cancel()
        trackEndWatchdogJob = null
        trackEndWatchdogKey = null
    }

    private fun advanceOptimisticPosition(deltaMs: Long) {
        mutableState.update { current ->
            val track = current.currentTrack
            if (!current.isConnected || !current.isPlaying || current.isBuffering || track == null) {
                return@update current
            }
            val durationMs = current.durationMs.takeIf { it > 0L } ?: track.durationMs
            if (durationMs <= 0L) {
                current.copy(positionMs = current.positionMs + deltaMs)
            } else {
                current.copy(positionMs = (current.positionMs + deltaMs).coerceAtMost(durationMs))
            }
        }
    }

    private fun scheduleLoadTimeout(requestId: Long) {
        loadTimeoutJob?.cancel()
        loadTimeoutJob = scope.launch {
            delay(loadTimeoutMs)
            if (pendingHandoff?.requestId == requestId) {
                verifyCastLoadAfterFailure(requestId, "Chromecast didn't respond in time. Playing on this device.")
            }
        }
    }

    private fun suspendLocalPlayback() {
        val current = mutableState.value
        if (current.queue.isNotEmpty() && current.currentIndex in current.queue.indices) {
            audioPlayer.suspendPlayback(current.queue, current.currentIndex, current.positionMs)
            return
        }
        val local = audioPlayer.state.value
        if (local.isPlaying || local.isBuffering) {
            audioPlayer.togglePlayPause()
        }
    }

    private fun restoreLocalPlayback(handoff: PendingDesktopCastHandoff) {
        if (!handoff.restoreLocalOnFailure) return
        restoreLocalPlayback(
            queue = handoff.queue,
            index = handoff.index,
            positionMs = handoff.positionMs,
            shouldPlay = handoff.wasLocalPlaying,
        )
    }

    private fun restoreLocalPlayback(
        queue: List<Track>,
        index: Int,
        positionMs: Long,
        shouldPlay: Boolean,
    ) {
        if (queue.isEmpty() || index !in queue.indices) return
        if (shouldPlay) {
            audioPlayer.play(queue, index)
            if (positionMs > 0L) {
                audioPlayer.seekTo(positionMs)
            }
        } else {
            audioPlayer.prepare(queue, index, positionMs)
        }
    }

    private fun rememberAppQueue(queue: List<Track>, currentIndex: Int) {
        appQueueSnapshot = DesktopCastQueueSnapshot(queue, currentIndex)
    }

    private suspend fun <T> runTransport(label: String, block: suspend () -> T): Result<T> =
        withContext(ioDispatcher) {
            runCatching {
                block()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                PhoebeLog.d("DesktopCastController") { "$label failed: ${error.message}" }
            }
        }

    private suspend fun <T> runCastRequest(label: String, block: suspend () -> T): Result<T> =
        castRequestMutex.withLock {
            runTransport(label, block)
        }

    private suspend fun <T> tryRunCastRequest(label: String, block: suspend () -> T): Result<T>? {
        if (!castRequestMutex.tryLock()) return null
        return try {
            runTransport(label, block)
        } finally {
            castRequestMutex.unlock()
        }
    }

    private companion object {
        const val EndOfTrackToleranceMs = 1_500L
        const val ReceiverDroppedMediaToleranceMs = 6_000L
        const val LoadFailureVerificationAttempts = 5
        const val LoadFailureVerificationDelayMs = 1_000L
        const val TrackEndWatchdogGraceMs = 750L
    }
}

internal data class DesktopCastDevice(
    val id: String,
    val displayName: String,
    val model: String? = null,
    val host: String? = null,
    val port: Int = DefaultChromecastPort,
    internal val native: ChromeCast? = null,
) {
    override fun toString(): String =
        model?.takeIf { it.isNotBlank() }?.let { "$displayName ($it)" } ?: displayName
}

internal data class DesktopCastMedia(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val streamUrl: String,
    val castUrl: String,
    val contentType: String,
    val downloadUrl: String,
    val thumbUrl: String?,
    val filepath: String?,
    val audioCodec: String?,
)

internal data class DesktopCastRemoteMedia(
    val trackId: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val streamUrl: String?,
    val castUrl: String?,
    val downloadUrl: String?,
    val thumbUrl: String?,
    val filepath: String?,
    val audioCodec: String?,
    val contentId: String?,
) {
    fun toTrack(): Track =
        castTrackFromMediaFields(
            trackId = trackId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            streamUrl = streamUrl,
            castUrl = castUrl ?: contentId,
            downloadUrl = downloadUrl,
            thumbUrl = thumbUrl,
            filepath = filepath,
            audioCodec = audioCodec,
        )
}

internal data class DesktopCastStatus(
    val deviceName: String?,
    val media: DesktopCastRemoteMedia?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val volume: Float?,
    val isFinished: Boolean,
)

internal data class DesktopConnectedCastDialogState(
    val deviceName: String,
    val model: String?,
    val trackTitle: String?,
    val trackArtist: String?,
    val isPlaying: Boolean,
    val volume: Float,
)

internal interface DesktopCastTransport {
    suspend fun startDiscovery()
    suspend fun refreshDiscovery()
    suspend fun devices(): List<DesktopCastDevice>
    suspend fun connect(device: DesktopCastDevice): DesktopCastConnection
}

internal interface DesktopCastConnection {
    val device: DesktopCastDevice
    fun connect()
    fun ensureDefaultMediaReceiver()
    suspend fun load(media: DesktopCastMedia, startPositionMs: Long): DesktopCastStatus?
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun readStatus(): DesktopCastStatus?
    fun readVolume(): Float?
    fun setVolume(volume: Float)
    fun stopApp()
    fun disconnect()
}

internal interface DesktopCastDevicePicker {
    fun show(
        devices: List<DesktopCastDevice>,
        onSelected: (DesktopCastDevice) -> Unit,
        onDismiss: () -> Unit,
    )

    fun showConnected(
        state: DesktopConnectedCastDialogState,
        onVolume: (Float) -> Unit,
        onDisconnect: () -> Unit,
        onSwitchDevice: () -> Unit,
        onDismiss: () -> Unit,
    )
}

private class CastV2DesktopCastTransport : DesktopCastTransport {
    private var discoverySession: DesktopChromecastDiscoverySession? = null

    override suspend fun startDiscovery() {
        ensureDesktopChromecastNetworkingConfigured()
        if (discoverySession == null) {
            discoverySession = DesktopChromecastDiscoverySession()
        }
    }

    override suspend fun refreshDiscovery() {
        discoverySession?.close()
        discoverySession = DesktopChromecastDiscoverySession()
    }

    override suspend fun devices(): List<DesktopCastDevice> =
        discoverySession?.devices().orEmpty()

    override suspend fun connect(device: DesktopCastDevice): DesktopCastConnection {
        val cast = device.native ?: ChromeCast(
            requireNotNull(device.host) { "Missing Chromecast host." },
            device.port,
        )
        return CastV2DesktopCastConnection(device, cast)
    }
}

private class CastV2DesktopCastConnection(
    override val device: DesktopCastDevice,
    private val cast: ChromeCast,
) : DesktopCastConnection {
    override fun connect() {
        cast.connect()
        cast.setRequestTimeout(CastControlRequestTimeoutMs)
    }

    override fun ensureDefaultMediaReceiver() {
        if (!cast.isAppRunning(DefaultMediaReceiverAppId)) {
            cast.launchApp(DefaultMediaReceiverAppId)
        }
    }

    override suspend fun load(media: DesktopCastMedia, startPositionMs: Long): DesktopCastStatus? {
        withCastRequestTimeout(CastLoadRequestTimeoutMs) {
            ensureDefaultMediaReceiver()
            cast.load(media.toCastV2Media())
            if (startPositionMs > 0L) {
                cast.seek(startPositionMs.toDouble() / 1000.0)
            }
        }
        return readStatus()
    }

    override fun play() {
        cast.play()
    }

    override fun pause() {
        cast.pause()
    }

    override fun seekTo(positionMs: Long) {
        cast.seek(positionMs.coerceAtLeast(0L).toDouble() / 1000.0)
    }

    override fun readStatus(): DesktopCastStatus? {
        val receiverStatus = withCastRequestTimeout(CastStatusRequestTimeoutMs) {
            runCatching { cast.status }.getOrNull()
        }
        val mediaStatus = withCastRequestTimeout(CastStatusRequestTimeoutMs) {
            runCatching { cast.mediaStatus }.recoverCatching { error ->
                if (error is ChromeCastException) null else throw error
            }.getOrNull()
        } ?: return null
        return DesktopCastStatus(
            deviceName = device.displayName,
            media = mediaStatus.media?.toDesktopCastRemoteMedia(),
            isPlaying = mediaStatus.playerState == CastV2MediaStatus.PlayerState.PLAYING,
            isBuffering = mediaStatus.playerState == CastV2MediaStatus.PlayerState.BUFFERING ||
                mediaStatus.playerState == CastV2MediaStatus.PlayerState.LOADING,
            positionMs = mediaStatus.currentTime.secondsToMillis(),
            durationMs = mediaStatus.media?.duration?.secondsToMillis() ?: 0L,
            volume = receiverStatus?.volume?.normalizedLevel(),
            isFinished = mediaStatus.playerState == CastV2MediaStatus.PlayerState.IDLE &&
                desktopCastIsFinishedIdleReason(mediaStatus.idleReason?.name),
        )
    }

    override fun readVolume(): Float? =
        withCastRequestTimeout(CastStatusRequestTimeoutMs) {
            runCatching { cast.status.volume.normalizedLevel() }.getOrNull()
        }

    override fun setVolume(volume: Float) {
        cast.setVolume(volume.coerceIn(0f, 1f))
    }

    override fun stopApp() {
        runCatching { cast.stopApp() }
    }

    override fun disconnect() {
        runCatching { cast.disconnect() }
    }

    private fun <T> withCastRequestTimeout(timeoutMs: Long, block: () -> T): T {
        cast.setRequestTimeout(timeoutMs)
        return try {
            block()
        } finally {
            cast.setRequestTimeout(CastControlRequestTimeoutMs)
        }
    }

    private companion object {
        const val CastStatusRequestTimeoutMs = 2_500L
        const val CastControlRequestTimeoutMs = 10_000L
        const val CastLoadRequestTimeoutMs = 10_000L
    }
}

private object ComposeDesktopCastDevicePicker : DesktopCastDevicePicker {
    override fun show(
        devices: List<DesktopCastDevice>,
        onSelected: (DesktopCastDevice) -> Unit,
        onDismiss: () -> Unit,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val theme = readDesktopCastTheme()
            EventQueue.invokeLater {
                val dialog = JDialog(null as Window?, "Cast to Chromecast", Dialog.ModalityType.APPLICATION_MODAL)
                applyDesktopCastDialogChrome(dialog, theme)
                var closedBySelection = false
                val composePanel = ComposePanel().apply {
                    setContent {
                        DesktopCastTheme(theme) {
                            DesktopCastDevicePickerContent(
                                devices = devices,
                                onCancel = {
                                    dialog.dispose()
                                },
                                onCast = { selected ->
                                    closedBySelection = true
                                    onSelected(selected)
                                    dialog.dispose()
                                },
                            )
                        }
                    }
                }
                dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
                dialog.addWindowListener(
                    object : java.awt.event.WindowAdapter() {
                        override fun windowClosed(event: java.awt.event.WindowEvent) {
                            if (!closedBySelection) {
                                onDismiss()
                            }
                        }
                    },
                )
                dialog.contentPane = composePanel
                dialog.minimumSize = Dimension(420, 360)
                dialog.pack()
                dialog.setLocationRelativeTo(null)
                dialog.isVisible = true
            }
        }
    }

    override fun showConnected(
        state: DesktopConnectedCastDialogState,
        onVolume: (Float) -> Unit,
        onDisconnect: () -> Unit,
        onSwitchDevice: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val theme = readDesktopCastTheme()
            EventQueue.invokeLater {
                val dialog = JDialog(null as Window?, "Chromecast", Dialog.ModalityType.APPLICATION_MODAL)
                applyDesktopCastDialogChrome(dialog, theme)
                var closedByAction = false
                val composePanel = ComposePanel().apply {
                    setContent {
                        DesktopCastTheme(theme) {
                            DesktopConnectedCastContent(
                                state = state,
                                onVolume = onVolume,
                                onClose = {
                                    dialog.dispose()
                                },
                                onDisconnect = {
                                    closedByAction = true
                                    onDisconnect()
                                    dialog.dispose()
                                },
                                onSwitchDevice = {
                                    closedByAction = true
                                    dialog.dispose()
                                    onSwitchDevice()
                                },
                            )
                        }
                    }
                }
                dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
                dialog.addWindowListener(
                    object : java.awt.event.WindowAdapter() {
                        override fun windowClosed(event: java.awt.event.WindowEvent) {
                            if (!closedByAction) {
                                onDismiss()
                            }
                        }
                    },
                )
                dialog.contentPane = composePanel
                dialog.minimumSize = Dimension(420, 320)
                dialog.pack()
                dialog.setLocationRelativeTo(null)
                dialog.isVisible = true
            }
        }
    }
}

@Composable
private fun DesktopCastTheme(
    theme: DesktopCastThemeSnapshot,
    content: @Composable () -> Unit,
) {
    val palette = theme.palette
    MaterialTheme(
        colorScheme = if (theme.useLightAppearance) {
            lightColorScheme(
                primary = palette.accent,
                secondary = palette.accentLight,
                surface = palette.modalSurface,
                onSurface = palette.primaryText,
            )
        } else {
            darkColorScheme(
                primary = palette.accent,
                secondary = palette.accentLight,
                surface = palette.modalSurface,
                onSurface = palette.primaryText,
            )
        },
    ) {
        CompositionLocalProvider(LocalDesktopCastPalette provides palette) {
            content()
        }
    }
}

private data class DesktopCastThemeSnapshot(
    val useLightAppearance: Boolean,
    val palette: DesktopCastPalette,
)

private data class DesktopCastPalette(
    val modalSurface: Color,
    val modalField: Color,
    val border: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val mutedText: Color,
    val accent: Color,
    val accentLight: Color,
    val librarySelectedRow: Color,
    val subtleFill: Color,
    val progressTrack: Color,
)

private val DesktopCastPaletteDark = DesktopCastPalette(
    modalSurface = Color(0xFF161B27),
    modalField = Color(0xFF0F131C),
    border = Color.White.copy(alpha = 0.06f),
    primaryText = Color(0xFFF4F5F7),
    secondaryText = Color(0xFFB6BBC7),
    mutedText = Color(0xFF7D8493),
    accent = Color(0xFF9B4DFF),
    accentLight = Color(0xFFA855F7),
    librarySelectedRow = Color(0xFF9B4DFF).copy(alpha = 0.18f),
    subtleFill = Color.White.copy(alpha = 0.04f),
    progressTrack = Color.White.copy(alpha = 0.14f),
)

private val DesktopCastPaletteLight = DesktopCastPalette(
    modalSurface = Color(0xFFFFFFFF),
    modalField = Color(0xFFF1F2F5),
    border = Color(0x14181B22),
    primaryText = Color(0xFF181B22),
    secondaryText = Color(0xFF4D5563),
    mutedText = Color(0xFF7A8190),
    accent = Color(0xFF8B3DFF),
    accentLight = Color(0xFF8B3DFF),
    librarySelectedRow = Color(0xFF8B3DFF).copy(alpha = 0.10f),
    subtleFill = Color(0x0A101820),
    progressTrack = Color(0x1E101820),
)

private val LocalDesktopCastPalette = staticCompositionLocalOf { DesktopCastPaletteDark }

private object DesktopCastUi {
    val modalSurface: Color @Composable get() = LocalDesktopCastPalette.current.modalSurface
    val modalField: Color @Composable get() = LocalDesktopCastPalette.current.modalField
    val border: Color @Composable get() = LocalDesktopCastPalette.current.border
    val primaryText: Color @Composable get() = LocalDesktopCastPalette.current.primaryText
    val secondaryText: Color @Composable get() = LocalDesktopCastPalette.current.secondaryText
    val mutedText: Color @Composable get() = LocalDesktopCastPalette.current.mutedText
    val accent: Color @Composable get() = LocalDesktopCastPalette.current.accent
    val accentLight: Color @Composable get() = LocalDesktopCastPalette.current.accentLight
    val librarySelectedRow: Color @Composable get() = LocalDesktopCastPalette.current.librarySelectedRow
    val subtleFill: Color @Composable get() = LocalDesktopCastPalette.current.subtleFill
    val progressTrack: Color @Composable get() = LocalDesktopCastPalette.current.progressTrack
}

private suspend fun readDesktopCastTheme(): DesktopCastThemeSnapshot {
    val storage = PlatformStorage()
    val (useLightAppearance, tintId) = withContext(Dispatchers.IO) {
        val theme = storage.readText(AppearanceThemeFile)?.trim()?.lowercase()
        val tint = storage.readText(AppearanceTintFile)?.trim()?.lowercase()
        (theme == "light" || theme == "true") to tint
    }
    val base = if (useLightAppearance) DesktopCastPaletteLight else DesktopCastPaletteDark
    val accent = desktopCastTintColor(tintId, useLightAppearance)
    val palette = if (accent == null) {
        base
    } else {
        base.copy(
            accent = accent,
            accentLight = accent,
            librarySelectedRow = accent.copy(alpha = if (useLightAppearance) 0.10f else 0.18f),
        )
    }
    return DesktopCastThemeSnapshot(useLightAppearance, palette)
}

private fun desktopCastTintColor(tintId: String?, useLightAppearance: Boolean): Color? =
    when (tintId) {
        "red" -> if (useLightAppearance) Color(0xFFDC2626) else Color(0xFFEF4444)
        "scarlet" -> if (useLightAppearance) Color(0xFFE11D48) else Color(0xFFF43F5E)
        "coral" -> if (useLightAppearance) Color(0xFFE64B3C) else Color(0xFFFF6B5F)
        "orange" -> if (useLightAppearance) Color(0xFFEA580C) else Color(0xFFF97316)
        "amber" -> if (useLightAppearance) Color(0xFFD97706) else Color(0xFFF59E0B)
        "gold" -> if (useLightAppearance) Color(0xFFEAB308) else Color(0xFFFACC15)
        "yellow" -> if (useLightAppearance) Color(0xFFCA8A04) else Color(0xFFEAB308)
        "lime" -> if (useLightAppearance) Color(0xFF65A30D) else Color(0xFF84CC16)
        "chartreuse" -> if (useLightAppearance) Color(0xFF84CC16) else Color(0xFFA3E635)
        "green" -> if (useLightAppearance) Color(0xFF16A34A) else Color(0xFF22C55E)
        "emerald" -> if (useLightAppearance) Color(0xFF059669) else Color(0xFF10B981)
        "mint" -> if (useLightAppearance) Color(0xFF10B981) else Color(0xFF34D399)
        "teal" -> if (useLightAppearance) Color(0xFF0D9488) else Color(0xFF14B8A6)
        "aqua" -> if (useLightAppearance) Color(0xFF06B6D4) else Color(0xFF22D3EE)
        "cyan" -> if (useLightAppearance) Color(0xFF0891B2) else Color(0xFF06B6D4)
        "sky" -> if (useLightAppearance) Color(0xFF0284C7) else Color(0xFF0EA5E9)
        "blue" -> if (useLightAppearance) Color(0xFF2563EB) else Color(0xFF3B82F6)
        "indigo" -> if (useLightAppearance) Color(0xFF4F46E5) else Color(0xFF6366F1)
        "violet" -> if (useLightAppearance) Color(0xFF7C3AED) else Color(0xFF8B5CF6)
        "fuchsia" -> if (useLightAppearance) Color(0xFFC026D3) else Color(0xFFD946EF)
        "magenta" -> if (useLightAppearance) Color(0xFFA21CAF) else Color(0xFFC026D3)
        "pink" -> if (useLightAppearance) Color(0xFFDB2777) else Color(0xFFEC4899)
        "plum" -> if (useLightAppearance) Color(0xFF9333EA) else Color(0xFFA855F7)
        else -> null
    }

private fun applyDesktopCastDialogChrome(dialog: JDialog, theme: DesktopCastThemeSnapshot) {
    val background = theme.palette.modalSurface
    dialog.background = java.awt.Color(
        (background.red * 255).roundToInt().coerceIn(0, 255),
        (background.green * 255).roundToInt().coerceIn(0, 255),
        (background.blue * 255).roundToInt().coerceIn(0, 255),
    )
    dialog.rootPane.putClientProperty(
        "apple.awt.windowAppearance",
        if (theme.useLightAppearance) "NSAppearanceNameAqua" else "NSAppearanceNameDarkAqua",
    )
}

@Composable
private fun DesktopCastDevicePickerContent(
    devices: List<DesktopCastDevice>,
    onCancel: () -> Unit,
    onCast: (DesktopCastDevice) -> Unit,
) {
    var selectedIndex by remember(devices) { mutableIntStateOf(0) }
    val selectedDevice = devices.getOrNull(selectedIndex) ?: devices.first()
    Column(
        modifier = Modifier
            .background(DesktopCastUi.modalSurface)
            .padding(20.dp)
            .width(420.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CastDeviceGlyph()
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Cast to Chromecast",
                    color = DesktopCastUi.primaryText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${devices.size} device${if (devices.size == 1) "" else "s"} found",
                    color = DesktopCastUi.secondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 168.dp, max = 292.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DesktopCastUi.modalField)
                .border(1.dp, DesktopCastUi.border, RoundedCornerShape(8.dp)),
        ) {
            itemsIndexed(
                items = devices,
                key = { _, device -> device.id },
            ) { index, device ->
                val selected = index == selectedIndex
                CastDeviceRow(
                    device = device,
                    selected = selected,
                    onClick = { selectedIndex = index },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = DesktopCastUi.secondaryText,
                ),
            ) {
                Text("Cancel")
            }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { onCast(selectedDevice) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = DesktopCastUi.accent,
                    contentColor = Color.White,
                ),
            ) {
                Text("Cast")
            }
        }
    }
}

@Composable
private fun DesktopConnectedCastContent(
    state: DesktopConnectedCastDialogState,
    onVolume: (Float) -> Unit,
    onClose: () -> Unit,
    onDisconnect: () -> Unit,
    onSwitchDevice: () -> Unit,
) {
    var volume by remember(state.volume) { mutableStateOf(state.volume.coerceIn(0f, 1f)) }
    Column(
        modifier = Modifier
            .background(DesktopCastUi.modalSurface)
            .padding(20.dp)
            .width(420.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CastDeviceGlyph()
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Connected to Chromecast",
                    color = DesktopCastUi.primaryText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.deviceName,
                    color = DesktopCastUi.secondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DesktopCastUi.modalField)
                .border(1.dp, DesktopCastUi.border, RoundedCornerShape(8.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(DesktopCastUi.accent.copy(alpha = 0.20f)),
                    contentAlignment = Alignment.Center,
                ) {
                    MiniCastGlyph(tint = DesktopCastUi.accentLight)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (state.isPlaying) "Casting" else "Cast paused",
                        color = DesktopCastUi.primaryText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Text(
                        text = state.trackTitle?.takeIf { it.isNotBlank() }
                            ?: state.model?.takeIf { it.isNotBlank() }
                            ?: "Ready",
                        color = DesktopCastUi.secondaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.trackArtist?.takeIf { it.isNotBlank() }?.let { artist ->
                        Text(
                            text = artist,
                            color = DesktopCastUi.mutedText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Volume",
                        color = DesktopCastUi.secondaryText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${(volume * 100f).roundToInt()}%",
                        color = DesktopCastUi.primaryText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Slider(
                    value = volume,
                    onValueChange = { next ->
                        volume = next.coerceIn(0f, 1f)
                        onVolume(volume)
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = DesktopCastUi.accentLight,
                        activeTrackColor = DesktopCastUi.accent,
                        inactiveTrackColor = DesktopCastUi.progressTrack,
                    ),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onDisconnect,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFFF6B5F),
                ),
            ) {
                Text("Disconnect")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onSwitchDevice,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = DesktopCastUi.secondaryText,
                    ),
                ) {
                    Text("Switch")
                }
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DesktopCastUi.accent,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun CastDeviceRow(
    device: DesktopCastDevice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(if (selected) DesktopCastUi.librarySelectedRow else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (selected) DesktopCastUi.accent.copy(alpha = 0.24f) else DesktopCastUi.subtleFill),
            contentAlignment = Alignment.Center,
        ) {
            MiniCastGlyph(tint = if (selected) DesktopCastUi.accentLight else DesktopCastUi.secondaryText)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = device.displayName,
                color = DesktopCastUi.primaryText,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            device.model?.takeIf { it.isNotBlank() }?.let { model ->
                Text(
                    text = model,
                    color = DesktopCastUi.mutedText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CastDeviceGlyph() {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DesktopCastUi.accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        MiniCastGlyph(tint = DesktopCastUi.accentLight)
    }
}

@Composable
private fun MiniCastGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val strokeWidth = (size.minDimension * 0.075f).coerceAtLeast(1.25f)
        val stroke = Stroke(width = strokeWidth)
        val inset = strokeWidth / 2f
        drawRoundRect(
            color = tint,
            topLeft = Offset(inset, size.height * 0.16f),
            size = Size(size.width - strokeWidth, size.height * 0.58f),
            style = stroke,
        )
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.055f,
            center = Offset(size.width * 0.22f, size.height * 0.84f),
        )
        drawArc(
            color = tint,
            startAngle = -90f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(size.width * 0.08f, size.height * 0.54f),
            size = Size(size.width * 0.36f, size.height * 0.36f),
            style = stroke,
        )
        drawArc(
            color = tint,
            startAngle = -90f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(size.width * -0.02f, size.height * 0.42f),
            size = Size(size.width * 0.58f, size.height * 0.58f),
            style = stroke,
        )
    }
}

internal fun desktopCastIsFinishedIdleReason(idleReasonName: String?): Boolean =
    idleReasonName == null ||
        idleReasonName == CastV2MediaStatus.IdleReason.FINISHED.name ||
        idleReasonName == CastV2MediaStatus.IdleReason.COMPLETED.name

private fun Track.toDesktopCastMedia(): DesktopCastMedia {
    val descriptor = toCastMediaDescriptor()
    return DesktopCastMedia(
        trackId = descriptor.trackId,
        title = descriptor.title,
        artist = descriptor.artist,
        album = descriptor.album,
        durationMs = descriptor.durationMs,
        streamUrl = descriptor.streamUrl,
        castUrl = descriptor.castUrl,
        contentType = descriptor.contentType,
        downloadUrl = descriptor.downloadUrl,
        thumbUrl = descriptor.thumbUrl,
        filepath = descriptor.filepath,
        audioCodec = descriptor.audioCodec,
    )
}

private fun DesktopCastMedia.toCastV2Media(): CastV2Media =
    CastV2Media(
        castUrl,
        contentType,
        durationMs.takeIf { it > 0L }?.toDouble()?.div(1000.0),
        CastV2Media.StreamType.BUFFERED,
        toCustomData(),
        toMetadata(),
        null,
        null,
    )

private fun DesktopCastMedia.toMetadata(): Map<String, Any> =
    buildMap {
        put(CastV2Media.METADATA_TYPE, CastV2Media.MetadataType.MUSIC_TRACK.ordinal)
        put(CastV2Media.METADATA_TITLE, title)
        put(CastV2Media.METADATA_ARTIST, artist)
        put(CastV2Media.METADATA_ALBUM_NAME, album)
        thumbUrl?.let { url ->
            put(CastV2Media.METADATA_IMAGES, listOf(mapOf("url" to url)))
        }
    }

private fun DesktopCastMedia.toCustomData(): Map<String, Any> =
    buildMap {
        put(CastMediaCustomDataKeys.TrackId, trackId)
        put(CastMediaCustomDataKeys.Title, title)
        put(CastMediaCustomDataKeys.Artist, artist)
        put(CastMediaCustomDataKeys.Album, album)
        put(CastMediaCustomDataKeys.DurationMs, durationMs)
        put(CastMediaCustomDataKeys.StreamUrl, streamUrl)
        put(CastMediaCustomDataKeys.CastUrl, castUrl)
        put(CastMediaCustomDataKeys.DownloadUrl, downloadUrl)
        thumbUrl?.let { put(CastMediaCustomDataKeys.ThumbUrl, it) }
        filepath?.let { put(CastMediaCustomDataKeys.Filepath, it) }
        audioCodec?.let { put(CastMediaCustomDataKeys.AudioCodec, it) }
    }

private fun CastV2Media.toDesktopCastRemoteMedia(): DesktopCastRemoteMedia {
    val data = customData.orEmpty()
    val metadata = metadata.orEmpty()
    val title = data.stringValue(CastMediaCustomDataKeys.Title)
        ?: metadata.stringValue(CastV2Media.METADATA_TITLE)
    val artist = data.stringValue(CastMediaCustomDataKeys.Artist)
        ?: metadata.stringValue(CastV2Media.METADATA_ARTIST)
    val album = data.stringValue(CastMediaCustomDataKeys.Album)
        ?: metadata.stringValue(CastV2Media.METADATA_ALBUM_NAME)
    val thumbUrl = data.stringValue(CastMediaCustomDataKeys.ThumbUrl)
        ?: metadata.imageUrl()
    val durationMs = data.longValue(CastMediaCustomDataKeys.DurationMs)
        ?: duration?.secondsToMillis()
        ?: 0L
    return DesktopCastRemoteMedia(
        trackId = data.stringValue(CastMediaCustomDataKeys.TrackId),
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        streamUrl = data.stringValue(CastMediaCustomDataKeys.StreamUrl) ?: url,
        castUrl = data.stringValue(CastMediaCustomDataKeys.CastUrl) ?: url,
        downloadUrl = data.stringValue(CastMediaCustomDataKeys.DownloadUrl),
        thumbUrl = thumbUrl,
        filepath = data.stringValue(CastMediaCustomDataKeys.Filepath),
        audioCodec = data.stringValue(CastMediaCustomDataKeys.AudioCodec),
        contentId = url,
    )
}

private fun Map<String, Any?>.stringValue(key: String): String? =
    this[key]?.toString()?.takeIf { it.isNotBlank() }

private fun Map<String, Any?>.longValue(key: String): Long? =
    when (val value = this[key]) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

private fun Map<String, Any?>.imageUrl(): String? {
    val images = this[CastV2Media.METADATA_IMAGES] as? List<*> ?: return null
    return images.firstNotNullOfOrNull { image ->
        (image as? Map<*, *>)?.get("url")?.toString()?.takeIf { it.isNotBlank() }
    }
}

private fun Float?.normalizedLevel(): Float? =
    this?.takeIf { it >= 0f }?.coerceIn(0f, 1f)

private fun su.litvak.chromecast.api.v2.Volume.normalizedLevel(): Float? =
    if (muted) 0f else level.normalizedLevel()

private fun Double.secondsToMillis(): Long =
    (this * 1000.0).toLong().coerceAtLeast(0L)
