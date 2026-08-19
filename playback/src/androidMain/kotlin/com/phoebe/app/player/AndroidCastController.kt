package com.phoebe.app.player

import android.content.Intent
import android.content.Context
import android.media.AudioManager
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueData
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.ResultCallback
import com.phoebe.app.AndroidContextHolder
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.PhoebeLog
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
import org.json.JSONObject

actual fun createCastController(audioPlayer: AudioPlayer): CastController =
    AndroidCastControllerHolder.instance.also { it.bindAudioPlayer(audioPlayer) }

private object AndroidCastControllerHolder {
    val instance: AndroidCastController by lazy { AndroidCastController() }
}

private data class PendingCastHandoff(
    val queue: List<Track>,
    val index: Int,
    val positionMs: Long,
    val wasLocalPlaying: Boolean,
    val requestId: Long,
)

private data class CastLoadRequest(
    val requestData: MediaLoadRequestData,
    val receiverQueueSize: Int,
    val estimatedBytes: Int,
)

private data class AppQueueSnapshot(
    val queue: List<Track>,
    val currentIndex: Int,
)

private data class RemoteQueueEntry(
    val track: Track,
    val castUrl: String?,
)

private class AndroidCastController : CastController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appContext get() = AndroidContextHolder.application
    private val castContext: CastContext? get() = runCatching { CastContext.getSharedInstance(appContext) }.getOrNull()
    private var positionJob: Job? = null
    private var loadTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var audioPlayer: AudioPlayer? = null
    private var sessionListenerRegistered = false
    private var pendingHandoff: PendingCastHandoff? = null
    private var expectedRemoteHandoff: PendingCastHandoff? = null
    private var appQueueSnapshot: AppQueueSnapshot? = null
    private var loadRequestId = 0L
    private var lastLoadReceiverQueueSize = 0
    private var mediaErrorRetryCount = 0
    private var mediaErrorTrackId: String? = null
    private var endingSessionIntentionally = false
    private var lastLoggedRemoteState: String? = null

    private val mutableState = MutableStateFlow(
        CastState(
            isAvailable = true,
        ),
    )
    override val state: StateFlow<CastState> = mutableState

    override fun canLoadQueue(queue: List<Track>): CastQueueSupport =
        queue.plexChromecastQueueSupport()

    private val remoteMediaClientListener = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            syncRemotePlayback()
        }

        override fun onMetadataUpdated() {
            syncRemotePlayback()
        }
        override fun onQueueStatusUpdated() {
            syncRemotePlayback()
        }
        override fun onPreloadStatusUpdated() = Unit
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            PhoebeLog.d(TAG) { "session started id=$sessionId device=${session.castDevice?.friendlyName}" }
            connect(session, castLocalQueueIfReceiverEmpty = true)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            PhoebeLog.d(TAG) { "session resumed wasSuspended=$wasSuspended device=${session.castDevice?.friendlyName}" }
            connect(session, castLocalQueueIfReceiverEmpty = false)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            val reason = if (endingSessionIntentionally) {
                CastSessionDisconnectReason.UserRequested
            } else {
                CastSessionDisconnectReason.Unexpected
            }
            endingSessionIntentionally = false
            PhoebeLog.d(TAG) { "session ended error=$error reason=$reason device=${session.castDevice?.friendlyName}" }
            disconnectState(reason)
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            PhoebeLog.d(TAG) { "session suspended reason=$reason device=${session.castDevice?.friendlyName}" }
            suspendLocalPlayback()
            mutableState.update { it.copy(isBuffering = true, message = "Chromecast connection interrupted...") }
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            PhoebeLog.d(TAG) { "session resume failed error=$error" }
            disconnectState(CastSessionDisconnectReason.Unexpected)
        }

        override fun onSessionStarting(session: CastSession) {
            PhoebeLog.d(TAG) { "session starting" }
            mutableState.update { it.copy(isAvailable = true, isBuffering = true, message = null) }
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            PhoebeLog.d(TAG) { "session start failed error=$error" }
            mutableState.update { it.copy(isBuffering = false, message = "Couldn't start Chromecast session.") }
        }

        override fun onSessionEnding(session: CastSession) {
            PhoebeLog.d(TAG) { "session ending intentional=$endingSessionIntentionally" }
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            PhoebeLog.d(TAG) { "session resuming id=$sessionId" }
        }
    }

    init {
        AndroidPlaybackBridge.isCastActive = { mutableState.value.isConnected }
        AndroidPlaybackBridge.onCastTogglePlayPause = { togglePlayPause() }
        AndroidPlaybackBridge.onCastPlay = { playCast() }
        AndroidPlaybackBridge.onCastPause = { pauseCast() }
        AndroidPlaybackBridge.onCastSkipNext = { next() }
        AndroidPlaybackBridge.onCastSkipPrevious = { previous() }
        AndroidPlaybackBridge.onCastSeekTo = { positionMs -> seekTo(positionMs) }
        AndroidPlaybackBridge.readCastVolume = { readCastVolumeNormalized() }
        AndroidPlaybackBridge.applyCastVolume = { volume -> applyCastVolume(volume) }
        AndroidPlaybackBridge.adjustCastVolumeStep = { up -> adjustCastVolumeStep(up) }
        ensureCastSessionListener()
    }

    fun bindAudioPlayer(audioPlayer: AudioPlayer) {
        this.audioPlayer = audioPlayer
        ensureCastSessionListener()
    }

    override fun showDevicePicker() {
        val activity = AndroidContextHolder.activity
        ensureCastSessionListener()
        if (activity == null) {
            mutableState.update { it.copy(message = "Chromecast is not available right now.") }
            return
        }
        mutableState.update { it.copy(isAvailable = true, message = null) }
        if (!activity.showCastRoutePicker()) {
            mutableState.update { it.copy(message = "Couldn't open Chromecast picker.") }
        }
    }

    override fun disconnect() {
        endingSessionIntentionally = true
        reconnectJob?.cancel()
        reconnectJob = null
        PhoebeLog.d(TAG) { "user requested disconnect device=${mutableState.value.deviceName}" }
        castContext?.sessionManager?.endCurrentSession(true)
        disconnectState(CastSessionDisconnectReason.UserRequested)
    }

    override fun loadQueue(queue: List<Track>, startIndex: Int, startPositionMs: Long) {
        loadQueueInternal(queue, startIndex, startPositionMs = startPositionMs)
    }

    private fun loadQueueInternal(
        queue: List<Track>,
        startIndex: Int,
        startPositionMs: Long = 0L,
        maxReceiverItems: Int = CAST_MAX_RECEIVER_QUEUE_ITEMS,
    ) {
        val support = canLoadQueue(queue)
        if (!support.isSupported) {
            mutableState.update { it.copy(message = support.message) }
            return
        }
        val session = castContext?.sessionManager?.currentCastSession
        val client = session?.remoteMediaClient
        if (session == null || client == null) {
            mutableState.update { it.copy(message = "Choose a Chromecast before casting.") }
            showDevicePicker()
            return
        }
        val index = startIndex.coerceIn(queue.indices)
        val track = queue[index]
        val positionMs = startPositionMs.coerceAtLeast(0L)
        val localState = audioPlayer?.state?.value
        val servicePlaying = AndroidPlaybackBridge.isServicePlaybackActive()
        val wasLocalPlaying = localState?.isPlaying == true || servicePlaying
        suspendLocalPlayback()
        loadRequestId++
        val requestId = loadRequestId
        rememberAppQueue(queue, index)
        val handoff = PendingCastHandoff(
            queue = queue,
            index = index,
            positionMs = positionMs,
            wasLocalPlaying = wasLocalPlaying,
            requestId = requestId,
        )
        pendingHandoff = handoff
        expectedRemoteHandoff = handoff
        mutableState.update {
            it.copy(
                isAvailable = true,
                isConnected = true,
                deviceName = session.castDevice?.friendlyName,
                queue = queue,
                currentIndex = index,
                isPlaying = false,
                isBuffering = true,
                positionMs = positionMs,
                durationMs = track.durationMs,
                message = null,
            )
        }
        val loadRequest = buildCastLoadRequest(queue, index, positionMs, maxItems = maxReceiverItems)
        lastLoadReceiverQueueSize = loadRequest.receiverQueueSize
        PhoebeLog.d(TAG) {
            "loading cast queue startId=${track.id} codec=${track.audioCodec} startIndex=$index receiverItems=${loadRequest.receiverQueueSize}/${queue.size} bytes=${loadRequest.estimatedBytes} budget=$CAST_LOAD_MESSAGE_BYTE_BUDGET requestId=$requestId"
        }
        val pendingResult = runCatching {
            client.load(loadRequest.requestData)
        }.getOrElse { error ->
            PhoebeLog.d(TAG) { "cast load request rejected: ${error.message}" }
            onCastLoadFailed(requestId, "Couldn't load this playlist on Chromecast.")
            return
        }
        pendingResult.setResultCallback(
            ResultCallback { result ->
                scope.launch {
                    handleCastLoadResult(requestId, result)
                }
            },
        )
        scheduleLoadTimeout(requestId)
        startPositionSync()
    }

    override fun togglePlayPause() {
        val client = remoteMediaClient() ?: return
        if (mutableState.value.isPlaying) {
            pauseCast()
        } else {
            playCast()
        }
    }

    private fun playCast() {
        remoteMediaClient()?.play()
        mutableState.update { it.copy(isPlaying = true, isBuffering = false) }
    }

    private fun pauseCast() {
        remoteMediaClient()?.pause()
        mutableState.update { it.copy(isPlaying = false, isBuffering = false) }
    }

    private fun readCastVolumeNormalized(): Float {
        val session = castContext?.sessionManager?.currentCastSession ?: return 0.7f
        return session.volume.toFloat().coerceIn(0f, 1f)
    }

    private fun applyCastVolume(volume: Float) {
        val session = castContext?.sessionManager?.currentCastSession ?: return
        val normalized = volume.toDouble().coerceIn(0.0, 1.0)
        runCatching { session.volume = normalized }
        AndroidPlaybackBridge.onCastVolumeChanged?.invoke(normalized.toFloat())
    }

    private fun adjustCastVolumeStep(up: Boolean): Boolean {
        if (!mutableState.value.isConnected) return false
        val session = castContext?.sessionManager?.currentCastSession ?: return false
        val step = localMusicVolumeStep()
        val next = (session.volume + if (up) step else -step).coerceIn(0.0, 1.0)
        applyCastVolume(next.toFloat())
        return true
    }

    private fun localMusicVolumeStep(): Double {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return 1.0 / max
    }

    override fun next() {
        skipTo(mutableState.value.currentIndex + 1)
    }

    override fun previous() {
        skipTo((mutableState.value.currentIndex - 1).coerceAtLeast(0))
    }

    private fun skipTo(targetIndex: Int) {
        val current = mutableState.value
        val decision = decideCastSkipAction(
            targetIndex = targetIndex,
            queueSize = current.queue.size,
            targetAlreadyOnReceiver = receiverQueueItemForAppIndex(current.queue, targetIndex) != null,
        )
        PhoebeLog.d(TAG) {
            "skip to=$targetIndex from=${current.currentIndex} decision=$decision connected=${current.isConnected}"
        }
        when (decision) {
            CastSkipDecision.None -> Unit
            CastSkipDecision.AdvanceReceiverQueue -> {
                val target = current.queue[targetIndex]
                rememberAppQueue(current.queue, targetIndex)
                mutableState.update {
                    it.copy(
                        currentIndex = targetIndex,
                        isPlaying = true,
                        isBuffering = true,
                        positionMs = 0L,
                        durationMs = target.durationMs,
                        message = null,
                    )
                }
                suspendLocalPlayback()
                if (!jumpToReceiverAppIndex(current.queue, targetIndex)) {
                    loadQueueInternal(current.queue, targetIndex)
                }
            }
            is CastSkipDecision.LoadWindow -> loadQueueInternal(current.queue, decision.startIndex)
        }
    }

    override fun seekTo(positionMs: Long) {
        remoteMediaClient()?.seek(
            MediaSeekOptions.Builder()
                .setPosition(positionMs.coerceAtLeast(0L))
                .build(),
        )
        mutableState.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
    }

    private fun connect(session: CastSession, castLocalQueueIfReceiverEmpty: Boolean) {
        reconnectJob?.cancel()
        reconnectJob = null
        endingSessionIntentionally = false
        ensurePlaybackServiceRunning()
        val client = session.remoteMediaClient
        client?.registerCallback(remoteMediaClientListener)
        client?.requestStatus()
        suspendLocalPlayback()
        mutableState.update {
            it.copy(
                isAvailable = true,
                isConnected = true,
                deviceName = session.castDevice?.friendlyName,
                isBuffering = false,
                message = null,
            )
        }
        syncRemotePlayback()
        val receiverEmpty = client?.mediaInfo == null
        PhoebeLog.d(TAG) {
            "session connected device=${session.castDevice?.friendlyName} receiverEmpty=$receiverEmpty recastLocal=$castLocalQueueIfReceiverEmpty"
        }
        if (castLocalQueueIfReceiverEmpty && receiverEmpty) {
            castCurrentLocalQueueIfPossible()
        }
        startPositionSync()
    }

    private fun disconnectState(reason: CastSessionDisconnectReason) {
        loadTimeoutJob?.cancel()
        loadTimeoutJob = null
        val pending = pendingHandoff
        pendingHandoff = null
        expectedRemoteHandoff = null
        val previous = mutableState.value
        val restoreLocal = shouldRestoreLocalAfterCastSessionEnd(reason)
        PhoebeLog.d(TAG) {
            "disconnectState reason=$reason restoreLocal=$restoreLocal pending=${pending != null} queue=${previous.queue.size} index=${previous.currentIndex} localPlaying=${audioPlayer?.state?.value?.isPlaying == true}"
        }
        if (restoreLocal) {
            reconnectJob?.cancel()
            reconnectJob = null
            appQueueSnapshot = null
            if (pending != null) {
                restoreLocalPlayback(pending)
            }             else if (previous.isConnected && previous.queue.isNotEmpty() && previous.currentIndex in previous.queue.indices) {
                restoreLocalPlayback(
                    queue = previous.queue,
                    index = previous.currentIndex,
                    positionMs = previous.positionMs,
                    shouldPlay = previous.isPlaying || pending?.wasLocalPlaying == true,
                )
            }
            positionJob?.cancel()
            positionJob = null
            mutableState.update {
                it.copy(
                    isConnected = false,
                    deviceName = null,
                    isPlaying = false,
                    isBuffering = false,
                    message = null,
                )
            }
            AndroidPlaybackBridge.onCastMediaSessionState?.invoke(null)
            return
        }
        suspendLocalPlayback()
        positionJob?.cancel()
        positionJob = null
        mutableState.update {
            it.copy(
                isPlaying = false,
                isBuffering = true,
                message = "Reconnecting to Chromecast...",
            )
        }
        scheduleUnexpectedDisconnectTimeout()
    }

    private fun syncRemotePlayback() {
        val client = remoteMediaClient() ?: return
        val previous = mutableState.value
        val isPlaying = client.isPlaying
        val isBuffering = client.isBuffering
        val status = client.mediaStatus
        val queueItems = status?.queueItems.orEmpty()
        val remoteTrack = client.currentItem?.media?.toTrack() ?: client.mediaInfo?.toTrack()
        val remoteCastUrl = client.currentItem?.media?.contentId ?: client.mediaInfo?.contentId
        val knownQueue = appQueueSnapshot?.queue?.takeIf { it.isNotEmpty() }
            ?: pendingHandoff?.queue?.takeIf { it.isNotEmpty() }
            ?: previous.queue
        val remoteQueueEntries = queueItems.mapNotNull { item ->
            val track = item.media?.toTrack() ?: return@mapNotNull null
            RemoteQueueEntry(
                track = knownQueue.firstOrNull { it.matchesCastMedia(track, item.media?.contentId) } ?: track,
                castUrl = item.media?.contentId,
            )
        }
        val remoteQueue = remoteQueueEntries.map { it.track }
        val currentItemId = status?.currentItemId ?: client.currentItem?.itemId ?: MediaQueueItem.INVALID_ITEM_ID
        val remoteQueueIndex = currentItemId.takeIf { it != MediaQueueItem.INVALID_ITEM_ID }?.let { itemId ->
            queueItems.indexOfFirst { it.itemId == itemId }.takeIf { it >= 0 }
        }
        val currentQueueItem = remoteQueueIndex?.let { queueItems.getOrNull(it) }
        val expectedHandoff = pendingHandoff ?: expectedRemoteHandoff
        if (expectedHandoff != null) {
            if (!remotePlaybackMatches(expectedHandoff, remoteTrack, remoteCastUrl, currentQueueItem)) return
            if (expectedRemoteHandoff?.requestId == expectedHandoff.requestId) {
                expectedRemoteHandoff = null
            }
        }
        val currentQueueItemKnownIndex = currentQueueItem?.media?.let { media ->
            val track = media.toTrack()
            knownQueue.indexOfFirst { it.matchesCastMedia(track, media.contentId) }.takeIf { index -> index >= 0 }
        }
        val remoteMediaKnownIndex = remoteTrack?.let { track ->
            knownQueue.indexOfFirst { it.matchesCastMedia(track, remoteCastUrl) }.takeIf { index -> index >= 0 }
        }
        val knownQueueTrackIndex = currentQueueItemKnownIndex ?: remoteMediaKnownIndex
        val remoteQueueMatchesKnown = remoteQueueEntries.isNotEmpty() &&
            knownQueue.isNotEmpty() &&
            remoteQueueEntries.all { entry ->
                knownQueue.any { it.matchesCastMedia(entry.track, entry.castUrl) }
            }
        val preservingKnownQueue = knownQueueTrackIndex != null || remoteQueueMatchesKnown
        val queue = when {
            preservingKnownQueue -> knownQueue
            remoteQueue.isNotEmpty() -> remoteQueue
            remoteTrack != null -> listOf(remoteTrack)
            else -> knownQueue
        }
        val currentIndex = when {
            queue.isEmpty() -> previous.currentIndex
            preservingKnownQueue -> knownQueueTrackIndex
                ?: remoteTrack?.let { track ->
                    queue.indexOfFirst { it.matchesCastMedia(track, remoteCastUrl) }.takeIf { index -> index >= 0 }
                }
                ?: appQueueSnapshot?.currentIndex?.takeIf { it in queue.indices }
                ?: previous.currentIndex.takeIf { it in queue.indices }
                ?: 0
            remoteQueue.isNotEmpty() -> remoteQueueIndex
                ?: remoteTrack?.let { track ->
                    queue.indexOfFirst { it.matchesCastMedia(track, remoteCastUrl) }.takeIf { index -> index >= 0 }
                }
                ?: previous.currentIndex.takeIf { it in queue.indices }
                ?: 0
            else -> 0
        }
        if (queue.isNotEmpty() && currentIndex in queue.indices) {
            rememberAppQueue(queue, currentIndex)
        } else if (remoteQueue.isNotEmpty() || remoteTrack != null) {
            appQueueSnapshot = null
        }
        val playerState = status?.playerState ?: CAST_PLAYER_STATE_UNKNOWN
        val idleReason = status?.idleReason ?: CAST_IDLE_REASON_NONE
        val currentTrackId = queue.getOrNull(currentIndex)?.id
        mediaErrorRetryCount = mediaErrorRetryCountFor(
            currentTrackId = currentTrackId,
            previousTrackId = mediaErrorTrackId,
            previousCount = mediaErrorRetryCount,
        )
        mediaErrorTrackId = currentTrackId
        val idleDecision = decideCastIdleAction(
            playerState = playerState,
            idleReason = idleReason,
            hasPendingHandoff = pendingHandoff != null,
            currentIndex = currentIndex,
            lastAppIndex = queue.lastIndex,
            nextItemAlreadyOnReceiver = receiverQueueItemForAppIndex(queue, currentIndex + 1, queueItems) != null,
            currentErrorRetryCount = mediaErrorRetryCount,
        )
        val remoteState = "player=${castPlayerStateName(playerState)} idle=${castIdleReasonName(idleReason)} index=$currentIndex playing=$isPlaying buffering=$isBuffering localPlaying=${audioPlayer?.state?.value?.isPlaying == true} servicePlaying=${AndroidPlaybackBridge.isServicePlaybackActive()} decision=$idleDecision"
        if (remoteState != lastLoggedRemoteState) {
            lastLoggedRemoteState = remoteState
            PhoebeLog.d(TAG) { "remote $remoteState" }
        }
        if (playerState == CAST_PLAYER_STATE_PLAYING &&
            client.approximateStreamPosition > 2_000L
        ) {
            mediaErrorRetryCount = 0
        }
        when (idleDecision) {
            CastIdleDecision.Ignore -> Unit
            CastIdleDecision.AdvanceReceiverQueue -> {
                if (jumpToReceiverAppIndex(queue, currentIndex + 1, queueItems)) {
                    rememberAppQueue(queue, currentIndex + 1)
                    return
                }
                loadQueueInternal(queue, currentIndex + 1)
                return
            }
            is CastIdleDecision.LoadNextWindow -> {
                loadQueueInternal(queue, idleDecision.startIndex)
                return
            }
            CastIdleDecision.RetryCurrent -> {
                mediaErrorRetryCount++
                val retryIndex = currentIndex.takeIf { it in queue.indices } ?: return
                PhoebeLog.d(TAG) { "retrying failed cast item index=$retryIndex attempt=$mediaErrorRetryCount" }
                loadQueueInternal(queue, retryIndex, maxReceiverItems = 1)
                return
            }
            is CastIdleDecision.SkipFailedTrack -> {
                PhoebeLog.d(TAG) {
                    "skipping failed cast item index=$currentIndex next=${idleDecision.nextIndex} title=${queue.getOrNull(currentIndex)?.title}"
                }
                mediaErrorRetryCount = 0
                loadQueueInternal(queue, idleDecision.nextIndex)
                return
            }
        }
        val positionMs = client.approximateStreamPosition.coerceAtLeast(0L)
        val durationMs = client.streamDuration.coerceAtLeast(0L).takeIf { duration -> duration > 0L }
            ?: remoteTrack?.durationMs?.takeIf { it > 0L }
            ?: previous.durationMs
        if (previous.isPlaying != isPlaying ||
            previous.isBuffering != isBuffering ||
            previous.queue != queue ||
            previous.currentIndex != currentIndex ||
            previous.durationMs != durationMs ||
            kotlin.math.abs(previous.positionMs - positionMs) > 750L
        ) {
            mutableState.update {
                it.copy(
                    queue = queue,
                    currentIndex = currentIndex,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    positionMs = positionMs,
                    durationMs = durationMs,
                )
            }
        }
        publishCastMediaSessionState()
        if (previous.isConnected || mutableState.value.isConnected) {
            suspendLocalPlayback()
        }
    }

    private fun publishCastMediaSessionState() {
        val state = mutableState.value
        val track = state.currentTrack ?: return
        AndroidPlaybackBridge.onCastMediaSessionState?.invoke(
            CastMediaSessionState(
                track = track,
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                positionMs = state.positionMs,
                durationMs = state.durationMs.takeIf { it > 0L } ?: track.durationMs,
            ),
        )
    }

    private fun suspendLocalPlayback() {
        AndroidPlaybackBridge.pauseLocalPlaybackImmediately()
    }

    private fun startPositionSync() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive && mutableState.value.isConnected) {
                syncRemotePlayback()
                delay(1000L)
            }
        }
    }

    private fun remoteMediaClient(): RemoteMediaClient? =
        castContext?.sessionManager?.currentCastSession?.remoteMediaClient

    private fun ensureCastSessionListener() {
        if (sessionListenerRegistered) return
        val context = castContext ?: return
        context.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        sessionListenerRegistered = true
        mutableState.update { it.copy(isAvailable = true, message = null) }
        context.sessionManager.currentCastSession?.let { session ->
            connect(session, castLocalQueueIfReceiverEmpty = false)
        }
    }

    private fun restoreLocalPlayback(handoff: PendingCastHandoff) {
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
        val localPlayer = audioPlayer ?: return
        PhoebeLog.d(TAG) {
            "restoring local playback index=$index positionMs=$positionMs shouldPlay=$shouldPlay id=${queue.getOrNull(index)?.id}"
        }
        localPlayer.play(queue, index)
        if (positionMs > 0L) {
            localPlayer.seekTo(positionMs)
        }
        if (!shouldPlay && (localPlayer.state.value.isPlaying || localPlayer.state.value.isBuffering)) {
            localPlayer.togglePlayPause()
        }
    }

    private fun scheduleUnexpectedDisconnectTimeout() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(CAST_UNEXPECTED_DISCONNECT_RECONNECT_MS)
            if (remoteMediaClient() != null) return@launch
            PhoebeLog.d(TAG) { "cast reconnect timed out; leaving local playback paused" }
            appQueueSnapshot = null
            mutableState.update {
                it.copy(
                    isConnected = false,
                    queue = emptyList(),
                    currentIndex = -1,
                    isPlaying = false,
                    isBuffering = false,
                    positionMs = 0L,
                    durationMs = 0L,
                    message = "Lost Chromecast connection. The speaker may still be playing.",
                )
            }
            AndroidPlaybackBridge.onCastMediaSessionState?.invoke(null)
        }
    }

    private fun scheduleLoadTimeout(requestId: Long) {
        loadTimeoutJob?.cancel()
        loadTimeoutJob = scope.launch {
            delay(LOAD_TIMEOUT_MS)
            if (pendingHandoff?.requestId == requestId) {
                onCastLoadFailed(requestId, "Chromecast didn't respond in time.")
            }
        }
    }

    private fun handleCastLoadResult(requestId: Long, result: RemoteMediaClient.MediaChannelResult) {
        if (pendingHandoff?.requestId != requestId) return
        loadTimeoutJob?.cancel()
        val status = result.status
        val mediaError = result.mediaError
        if (status.isSuccess && mediaError == null) {
            onCastLoadSucceeded(requestId)
        } else {
            PhoebeLog.d(TAG) {
                "cast load failed status=${status.statusCode} statusMessage=${status.statusMessage} mediaError=${mediaError?.detailedErrorCode} items=$lastLoadReceiverQueueSize requestId=$requestId"
            }
            val message = mediaError?.detailedErrorCode?.let { code ->
                "Couldn't load on Chromecast (error $code)."
            } ?: "Couldn't load this playlist on Chromecast."
            onCastLoadFailed(requestId, message)
        }
    }

    private fun onCastLoadSucceeded(requestId: Long) {
        if (pendingHandoff?.requestId != requestId) return
        pendingHandoff = null
        suspendLocalPlayback()
        syncRemotePlayback()
    }

    private fun onCastLoadFailed(requestId: Long, message: String) {
        val handoff = pendingHandoff?.takeIf { it.requestId == requestId } ?: return
        val sessionConnected = remoteMediaClient() != null
        val action = decideCastLoadFailureAction(sessionConnected, lastLoadReceiverQueueSize)
        val retryItemCount = nextCastLoadRetryItemCount(lastLoadReceiverQueueSize)
        PhoebeLog.d(TAG) {
            "cast load failure action=$action retryItems=$retryItemCount sessionConnected=$sessionConnected message=$message"
        }
        when (action) {
            CastLoadFailureAction.RetrySmallerQueue -> {
                if (retryItemCount != null) {
                    loadQueueInternal(
                        queue = handoff.queue,
                        startIndex = handoff.index,
                        startPositionMs = handoff.positionMs,
                        maxReceiverItems = retryItemCount,
                    )
                    return
                }
                holdFailedCastLoad(handoff, message)
            }
            CastLoadFailureAction.HoldOnReceiver -> holdFailedCastLoad(handoff, message)
            CastLoadFailureAction.RestoreLocal -> {
                pendingHandoff = null
                if (expectedRemoteHandoff?.requestId == requestId) {
                    expectedRemoteHandoff = null
                }
                restoreLocalPlayback(handoff)
                mutableState.update {
                    it.copy(
                        isConnected = false,
                        isPlaying = false,
                        isBuffering = false,
                        message = "$message Playing on this device.",
                    )
                }
            }
        }
    }

    private fun holdFailedCastLoad(handoff: PendingCastHandoff, message: String) {
        pendingHandoff = null
        if (expectedRemoteHandoff?.requestId == handoff.requestId) {
            expectedRemoteHandoff = null
        }
        rememberAppQueue(handoff.queue, handoff.index)
        suspendLocalPlayback()
        mutableState.update {
            it.copy(
                queue = handoff.queue,
                currentIndex = handoff.index,
                isPlaying = false,
                isBuffering = false,
                positionMs = handoff.positionMs,
                durationMs = handoff.queue.getOrNull(handoff.index)?.durationMs ?: it.durationMs,
                message = "$message Playback stays on Chromecast.",
            )
        }
        syncRemotePlayback()
    }

    private fun ensurePlaybackServiceRunning() {
        appContext.startService(Intent(appContext, PlaybackService::class.java))
    }

    private fun castCurrentLocalQueueIfPossible() {
        val snapshot = appQueueSnapshot
        val castState = mutableState.value
        val rememberedQueue = snapshot?.queue?.takeIf { it.isNotEmpty() } ?: castState.queue
        val rememberedIndex = snapshot?.currentIndex ?: castState.currentIndex
        val local = audioPlayer?.state?.value
        val queue: List<Track>
        val index: Int
        val positionMs: Long
        if (rememberedQueue.isNotEmpty() && rememberedIndex in rememberedQueue.indices) {
            queue = rememberedQueue
            index = rememberedIndex
            positionMs = castState.positionMs
        } else {
            if (local == null || local.currentIndex !in local.queue.indices) return
            queue = local.queue
            index = local.currentIndex
            positionMs = local.positionMs
        }
        val support = canLoadQueue(queue)
        if (!support.isSupported) {
            mutableState.update { it.copy(message = support.message) }
            return
        }
        loadQueueInternal(queue, index, positionMs)
    }

    private fun receiverQueueItemForAppIndex(
        queue: List<Track>,
        appIndex: Int,
        queueItems: List<MediaQueueItem>? = remoteMediaClient()?.mediaStatus?.queueItems,
    ): MediaQueueItem? {
        val target = queue.getOrNull(appIndex) ?: return null
        val items = queueItems.orEmpty()
        if (items.isEmpty()) return null
        val firstMedia = items.first().media ?: return null
        val windowStart = queue.indexOfFirst { track ->
            track.matchesCastMedia(firstMedia.toTrack(), firstMedia.contentId)
        }
        if (windowStart >= 0) {
            val receiverIndex = appIndex - windowStart
            items.getOrNull(receiverIndex)?.let { item ->
                val media = item.media ?: return@let null
                if (target.matchesCastMedia(media.toTrack(), media.contentId)) return item
            }
        }
        val matchingAppIndexes = queue.indices.filter { index ->
            queue[index].matchesCastMedia(target, target.toCastMediaDescriptor().castUrl)
        }
        if (matchingAppIndexes.size == 1) {
            return items.firstOrNull { item ->
                val media = item.media ?: return@firstOrNull false
                target.matchesCastMedia(media.toTrack(), media.contentId)
            }
        }
        return null
    }

    private fun jumpToReceiverAppIndex(
        queue: List<Track>,
        appIndex: Int,
        queueItems: List<MediaQueueItem>? = remoteMediaClient()?.mediaStatus?.queueItems,
    ): Boolean {
        val item = receiverQueueItemForAppIndex(queue, appIndex, queueItems) ?: return false
        val track = queue.getOrNull(appIndex) ?: return false
        PhoebeLog.d(TAG) { "jumping receiver queue itemId=${item.itemId} appIndex=$appIndex id=${track.id}" }
        loadTimeoutJob?.cancel()
        loadTimeoutJob = null
        pendingHandoff = null
        expectedRemoteHandoff = null
        val client = remoteMediaClient() ?: return false
        return runCatching {
            client.queueJumpToItem(item.itemId, null as JSONObject?)
            true
        }.getOrElse { error ->
            PhoebeLog.d(TAG) { "queue jump failed: ${error.message}" }
            false
        }
    }

    private fun remotePlaybackMatches(
        handoff: PendingCastHandoff,
        remoteTrack: Track?,
        remoteCastUrl: String?,
        currentQueueItem: MediaQueueItem?,
    ): Boolean {
        val expectedTrack = handoff.queue.getOrNull(handoff.index) ?: return true
        val media = currentQueueItem?.media
        if (media != null && expectedTrack.matchesCastMedia(media.toTrack(), media.contentId)) return true
        return remoteTrack?.let { expectedTrack.matchesCastMedia(it, remoteCastUrl) } == true
    }

    private fun rememberAppQueue(queue: List<Track>, currentIndex: Int) {
        appQueueSnapshot = AppQueueSnapshot(
            queue = queue,
            currentIndex = currentIndex,
        )
    }

    private fun Track.toMediaInfo(): MediaInfo {
        val descriptor = toCastMediaDescriptor()
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, descriptor.title)
            putString(MediaMetadata.KEY_ARTIST, descriptor.artist)
            putString(MediaMetadata.KEY_ALBUM_TITLE, descriptor.album)
            descriptor.thumbUrl?.let { addImage(com.google.android.gms.common.images.WebImage(android.net.Uri.parse(it))) }
        }
        return MediaInfo.Builder(descriptor.castUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(descriptor.contentType)
            .setMetadata(metadata)
            .setStreamDuration(descriptor.durationMs)
            .setCustomData(descriptor.toCastCustomData())
            .build()
    }

    private fun Track.toMediaQueueItem(startPositionMs: Long = 0L): MediaQueueItem =
        MediaQueueItem.Builder(toMediaInfo())
            .setAutoplay(true)
            .apply {
                if (startPositionMs > 0L) {
                    setStartTime(startPositionMs / 1_000.0)
                }
            }
            .build()

    private fun buildCastLoadRequest(
        queue: List<Track>,
        startIndex: Int,
        startPositionMs: Long,
        maxItems: Int = CAST_MAX_RECEIVER_QUEUE_ITEMS,
    ): CastLoadRequest {
        val tail = queue.drop(startIndex)
        val itemCount = shrinkCastReceiverQueueItemCount(
            tailSize = tail.size,
            maxItems = maxItems,
            maxBytes = CAST_LOAD_MESSAGE_BYTE_BUDGET,
            estimatedBytesForCount = { count ->
                buildMediaLoadRequest(tail.take(count), startPositionMs).estimatedByteSize()
            },
        ).coerceAtLeast(1)
        val request = buildMediaLoadRequest(tail.take(itemCount), startPositionMs)
        return CastLoadRequest(
            requestData = request,
            receiverQueueSize = itemCount,
            estimatedBytes = request.estimatedByteSize(),
        )
    }

    private fun buildMediaLoadRequest(queue: List<Track>, startPositionMs: Long): MediaLoadRequestData {
        val queueData = MediaQueueData.Builder()
            .setItems(queue.mapIndexed { index, track ->
                track.toMediaQueueItem(startPositionMs.takeIf { index == 0 } ?: 0L)
            })
            .setStartIndex(0)
            .setStartTime(startPositionMs)
            .setRepeatMode(MediaStatus.REPEAT_MODE_REPEAT_OFF)
            .build()
        return MediaLoadRequestData.Builder()
            .setQueueData(queueData)
            .setAutoplay(true)
            .setCurrentTime(startPositionMs)
            .build()
    }

    private fun MediaLoadRequestData.estimatedByteSize(): Int =
        toJson().toString().toByteArray(Charsets.UTF_8).size

    private fun CastMediaDescriptor.toCastCustomData(): JSONObject =
        JSONObject().apply {
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

    private fun MediaInfo.toTrack(): Track {
        val data = customData
        val remoteMetadata = metadata
        val title = data?.optString(CastMediaCustomDataKeys.Title).takeUnless { it.isNullOrBlank() }
            ?: remoteMetadata?.getString(MediaMetadata.KEY_TITLE).orEmpty()
        val artist = data?.optString(CastMediaCustomDataKeys.Artist).takeUnless { it.isNullOrBlank() }
            ?: remoteMetadata?.getString(MediaMetadata.KEY_ARTIST).orEmpty()
        val album = data?.optString(CastMediaCustomDataKeys.Album).takeUnless { it.isNullOrBlank() }
            ?: remoteMetadata?.getString(MediaMetadata.KEY_ALBUM_TITLE).orEmpty()
        val streamUrl = data?.optString(CastMediaCustomDataKeys.StreamUrl).takeUnless { it.isNullOrBlank() }
            ?: contentId.orEmpty()
        val thumbUrl = data?.optString(CastMediaCustomDataKeys.ThumbUrl).takeUnless { it.isNullOrBlank() }
            ?: remoteMetadata?.images?.firstOrNull()?.url?.toString()
        val durationMs = data?.optLong(CastMediaCustomDataKeys.DurationMs, 0L)?.takeIf { it > 0L }
            ?: streamDuration.takeIf { it > 0L }
            ?: 0L
        return castTrackFromMediaFields(
            trackId = data?.optString(CastMediaCustomDataKeys.TrackId),
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            streamUrl = streamUrl,
            castUrl = data?.optString(CastMediaCustomDataKeys.CastUrl) ?: contentId,
            downloadUrl = data?.optString(CastMediaCustomDataKeys.DownloadUrl),
            thumbUrl = thumbUrl,
            filepath = data?.optString(CastMediaCustomDataKeys.Filepath),
            audioCodec = data?.optString(CastMediaCustomDataKeys.AudioCodec),
        )
    }

    private companion object {
        const val TAG = "AndroidCastController"
        const val LOAD_TIMEOUT_MS = 30_000L
    }
}
