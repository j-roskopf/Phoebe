@file:OptIn(ExperimentalWasmJsInterop::class)

package com.phoebe.app.player

import com.phoebe.app.domain.Track
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

actual fun createCastController(audioPlayer: AudioPlayer): CastController = WebCastController(audioPlayer)

private class WebCastController(private val audioPlayer: AudioPlayer) : CastController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var positionJob: Job? = null
    private var pendingLoad: PendingWebCastLoad? = null
    private var loadRequestSerial = 0L
    private var rawSessionConnected = false
    private var rawSessionHasMedia = false
    private var suppressEmptySessionHandoff = false

    private val mutableState = MutableStateFlow(
        CastState(
            isAvailable = webCastAvailable(),
            message = if (webCastAvailable()) null else WebCastUnavailableMessage,
        ),
    )
    override val state: StateFlow<CastState> = mutableState

    init {
        webCastInstallStatusListener { payload -> applyStatusPayload(payload) }
        syncSession()
    }

    override fun canLoadQueue(queue: List<Track>): CastQueueSupport =
        webCastQueueSupport(queue)

    override fun showDevicePicker() {
        syncSession()
        if (!webCastAvailable()) {
            mutableState.update { it.copy(isAvailable = false, isBuffering = false, message = WebCastUnavailableMessage) }
            return
        }
        mutableState.update { it.copy(isAvailable = true, isBuffering = true, message = null) }
        suppressEmptySessionHandoff = true
        webCastRequestSession(
            success = {
                suppressEmptySessionHandoff = false
                rawSessionConnected = true
                rawSessionHasMedia = false
                castCurrentLocalQueueIfPossible()
            },
            failure = { message ->
                suppressEmptySessionHandoff = false
                syncSession()
                mutableState.update {
                    it.copy(
                        isBuffering = false,
                        message = message.takeIf(String::isNotBlank) ?: "Couldn't start Chromecast session.",
                    )
                }
            },
        )
    }

    override fun disconnect() {
        prepareLocalPlaybackFromCast()
        pendingLoad = null
        rawSessionConnected = false
        rawSessionHasMedia = false
        suppressEmptySessionHandoff = false
        webCastDisconnect()
        stopPositionSync()
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

    private fun prepareLocalPlaybackFromCast() {
        val current = mutableState.value
        if (!current.isConnected || current.queue.isEmpty() || current.currentIndex !in current.queue.indices) return
        audioPlayer.prepare(
            queue = current.queue,
            startIndex = current.currentIndex,
            positionMs = current.positionMs,
        )
    }

    override fun loadQueue(queue: List<Track>, startIndex: Int, startPositionMs: Long) {
        loadQueueInternal(queue, startIndex, startPositionMs = startPositionMs, restoreLocalOnFailure = true)
    }

    private fun loadQueueInternal(
        queue: List<Track>,
        startIndex: Int,
        startPositionMs: Long,
        restoreLocalOnFailure: Boolean,
    ) {
        val support = canLoadQueue(queue)
        if (!support.isSupported) {
            disconnectInactiveRawSession()
            mutableState.update { it.copy(isBuffering = false, message = support.message) }
            return
        }
        if (!webCastConnected()) {
            mutableState.update { it.copy(message = "Choose a Chromecast before casting.") }
            requestSessionThenLoad(queue, startIndex, startPositionMs, restoreLocalOnFailure)
            return
        }
        val request = webCastLoadRequest(queue, startIndex, startPositionMs)
        if (request == null) {
            disconnectInactiveRawSession()
            mutableState.update { it.copy(message = WebCastRemoteQueueMessage) }
            return
        }
        val index = request.startIndex
        val track = queue[index]
        val localState = audioPlayer.state.value
        val loadRequestId = ++loadRequestSerial
        pendingLoad = PendingWebCastLoad(
            queue = queue,
            index = index,
            positionMs = startPositionMs,
            wasLocalPlaying = localState.isPlaying || localState.isBuffering,
            restoreLocalOnFailure = restoreLocalOnFailure,
            requestId = loadRequestId,
        )
        mutableState.update {
            it.copy(
                isAvailable = true,
                isConnected = true,
                queue = queue,
                currentIndex = index,
                isPlaying = false,
                isBuffering = true,
                positionMs = 0L,
                durationMs = track.durationMs,
                message = null,
            )
        }
        webCastLoadMedia(
            payload = WebCastJson.encodeToString(request),
            success = {
                val completed = pendingLoad.takeIf { it?.requestId == loadRequestId }
                if (completed != null) {
                    pendingLoad = null
                    suspendLocalPlayback(completed)
                }
                mutableState.update { it.copy(isConnected = true, isPlaying = true, isBuffering = false, message = null) }
                startPositionSync()
            },
            failure = { message ->
                val failed = pendingLoad.takeIf { it?.requestId == loadRequestId }
                if (failed != null) {
                    pendingLoad = null
                    restoreLocalPlayback(failed)
                }
                webCastDisconnect()
                rawSessionConnected = false
                rawSessionHasMedia = false
                syncSession()
                val notice = message.takeIf(String::isNotBlank)
                    ?: "Chromecast couldn't load this stream. Make sure the stream URL is reachable by the device."
                mutableState.update {
                    it.copy(
                        isConnected = false,
                        deviceName = null,
                        queue = emptyList(),
                        currentIndex = -1,
                        isPlaying = false,
                        isBuffering = false,
                        message = notice,
                    )
                }
            },
        )
        startPositionSync()
    }

    private fun requestSessionThenLoad(
        queue: List<Track>,
        startIndex: Int,
        startPositionMs: Long,
        restoreLocalOnFailure: Boolean,
    ) {
        if (!webCastAvailable()) {
            mutableState.update { it.copy(isAvailable = false, isBuffering = false, message = WebCastUnavailableMessage) }
            return
        }
        mutableState.update { it.copy(isAvailable = true, isBuffering = true, message = "Choose a Chromecast to continue.") }
        suppressEmptySessionHandoff = true
        webCastRequestSession(
            success = {
                rawSessionConnected = true
                rawSessionHasMedia = false
                loadQueueInternal(queue, startIndex, startPositionMs, restoreLocalOnFailure)
            },
            failure = { message ->
                suppressEmptySessionHandoff = false
                syncSession()
                mutableState.update {
                    it.copy(
                        isBuffering = false,
                        message = message.takeIf(String::isNotBlank) ?: "Couldn't start Chromecast session.",
                    )
                }
            },
        )
    }

    override fun togglePlayPause() {
        webCastTogglePlayPause(
            success = { syncSession() },
            failure = { message -> handleControlFailure(message, "Couldn't control Chromecast playback.") },
        )
    }

    override fun next() {
        val current = mutableState.value
        val target = current.currentIndex + 1
        if (target !in current.queue.indices) return
        suspendLocalPlayback(current.queue, target, positionMs = 0L)
        webCastQueueNext(
            success = {
                suspendLocalPlayback(current.queue, target, positionMs = 0L)
                syncSession()
            },
            failure = { loadQueue(current.queue, target) },
        )
    }

    override fun previous() {
        val current = mutableState.value
        val target = (current.currentIndex - 1).coerceAtLeast(0)
        if (target !in current.queue.indices || target == current.currentIndex) return
        suspendLocalPlayback(current.queue, target, positionMs = 0L)
        webCastQueuePrevious(
            success = {
                suspendLocalPlayback(current.queue, target, positionMs = 0L)
                syncSession()
            },
            failure = { loadQueue(current.queue, target) },
        )
    }

    override fun seekTo(positionMs: Long) {
        val boundedPositionMs = positionMs.coerceAtLeast(0L)
        webCastSeek(
            seconds = boundedPositionMs.toDouble() / 1000.0,
            success = { syncSession() },
            failure = { message -> handleControlFailure(message, "Couldn't seek Chromecast playback.") },
        )
        mutableState.update { it.copy(positionMs = boundedPositionMs) }
    }

    private fun syncSession() {
        applyStatusPayload(webCastStatus())
    }

    private fun handleControlFailure(message: String, fallback: String) {
        syncSession()
        val notice = message.takeIf(String::isNotBlank) ?: fallback
        mutableState.update {
            if (it.isConnected) {
                it.copy(message = notice)
            } else {
                it.copy(isBuffering = false, message = null)
            }
        }
    }

    private fun applyStatusPayload(payload: String) {
        val status = runCatching { WebCastJson.decodeFromString<WebCastStatus>(payload) }.getOrNull() ?: return
        val wasRawSessionConnected = rawSessionConnected
        rawSessionConnected = status.isConnected
        rawSessionHasMedia = status.hasMedia
        val previousState = mutableState.value
        val shouldHandoffLocalQueue =
            status.isConnected &&
                !status.hasMedia &&
                !wasRawSessionConnected &&
                pendingLoad == null &&
                !suppressEmptySessionHandoff
        if (status.isConnected && !status.hasMedia && suppressEmptySessionHandoff) {
            suppressEmptySessionHandoff = false
        }
        val completedPendingLoad = pendingLoad.takeIf { status.isConnected && status.hasMedia }
        if (status.shouldAdvanceFromEnded(previousState)) {
            loadQueueInternal(
                queue = previousState.queue,
                startIndex = previousState.currentIndex + 1,
                startPositionMs = 0L,
                restoreLocalOnFailure = false,
            )
            return
        }
        mutableState.update { previous ->
            val active = status.isConnected &&
                (pendingLoad != null || previous.queue.isNotEmpty() && status.hasMedia)
            val currentIndex = if (active) status.currentIndexIn(previous.queue, previous.currentIndex) else -1
            val currentTrack = previous.queue.getOrNull(currentIndex)
            val message = when {
                status.isAvailable && previous.message == WebCastUnavailableMessage -> null
                !status.isAvailable && previous.message.isNullOrBlank() -> WebCastUnavailableMessage
                else -> previous.message
            }
            previous.copy(
                isAvailable = status.isAvailable,
                isConnected = active,
                deviceName = status.deviceName?.takeIf(String::isNotBlank),
                queue = if (active) previous.queue else emptyList(),
                currentIndex = currentIndex,
                isPlaying = active && status.isPlaying,
                isBuffering = active && status.isBuffering,
                positionMs = if (active) status.positionMs.coerceAtLeast(0L) else 0L,
                durationMs = if (active) {
                    status.durationMs.takeIf { it > 0L } ?: currentTrack?.durationMs ?: previous.durationMs
                } else {
                    0L
                },
                message = message,
            )
        }
        if (mutableState.value.isConnected) {
            startPositionSync()
        } else {
            stopPositionSync()
        }
        if (completedPendingLoad != null) {
            pendingLoad = null
            suspendLocalPlayback(completedPendingLoad)
        }
        if (shouldHandoffLocalQueue) {
            scope.launch { castCurrentLocalQueueIfPossible() }
        }
    }

    private fun castCurrentLocalQueueIfPossible() {
        val local = audioPlayer.state.value
        val queue = local.queue
        val index = local.currentIndex
        if (queue.isEmpty() || index !in queue.indices) {
            disconnectInactiveRawSession()
            mutableState.update {
                it.copy(
                    isBuffering = false,
                    message = "Start a song before casting to Chromecast.",
                )
            }
            return
        }
        val support = canLoadQueue(queue)
        if (!support.isSupported) {
            disconnectInactiveRawSession()
            mutableState.update { it.copy(message = support.message) }
            return
        }
        loadQueueInternal(
            queue = queue,
            startIndex = index,
            startPositionMs = local.positionMs,
            restoreLocalOnFailure = false,
        )
    }

    private fun disconnectInactiveRawSession() {
        if (rawSessionConnected && !rawSessionHasMedia) {
            webCastDisconnect()
            rawSessionConnected = false
            rawSessionHasMedia = false
        }
    }

    private fun suspendLocalPlayback(load: PendingWebCastLoad? = null) {
        val local = audioPlayer.state.value
        val queue = load?.queue ?: local.queue
        val index = load?.index ?: local.currentIndex
        val positionMs = load?.positionMs ?: local.positionMs
        suspendLocalPlayback(queue, index, positionMs)
    }

    private fun suspendLocalPlayback(queue: List<Track>, index: Int, positionMs: Long) {
        if (queue.isNotEmpty() && index in queue.indices) {
            audioPlayer.suspendPlayback(
                queue = queue,
                startIndex = index,
                positionMs = positionMs,
            )
            return
        }
        val local = audioPlayer.state.value
        if (local.isPlaying || local.isBuffering) {
            audioPlayer.togglePlayPause()
        }
    }

    private fun restoreLocalPlayback(load: PendingWebCastLoad) {
        if (!load.restoreLocalOnFailure) return
        audioPlayer.play(load.queue, load.index)
        if (load.positionMs > 0L) {
            audioPlayer.seekTo(load.positionMs)
        }
        val local = audioPlayer.state.value
        if (!load.wasLocalPlaying && (local.isPlaying || local.isBuffering)) {
            audioPlayer.togglePlayPause()
        }
    }

    private fun WebCastStatus.currentIndexIn(queue: List<Track>, fallback: Int): Int {
        if (queue.isEmpty()) return -1
        val trackId = trackId?.takeIf(String::isNotBlank)
        if (trackId != null) {
            val byId = queue.indexOfFirst { it.id == trackId }
            if (byId >= 0) return byId
        }
        val contentId = contentId?.takeIf(String::isNotBlank)
        if (contentId != null) {
            val byUrl = queue.indexOfFirst { track ->
                val descriptor = runCatching { track.toCastMediaDescriptor() }.getOrNull()
                track.streamUrl == contentId || descriptor?.castUrl == contentId
            }
            if (byUrl >= 0) return byUrl
        }
        return fallback.takeIf { it in queue.indices } ?: 0
    }

    private fun WebCastStatus.shouldAdvanceFromEnded(previous: CastState): Boolean =
        isConnected &&
            isEnded &&
            pendingLoad == null &&
            previous.isConnected &&
            previous.queue.isNotEmpty() &&
            previous.currentIndex in previous.queue.indices &&
            previous.currentIndex < previous.queue.lastIndex

    private fun startPositionSync() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (isActive && mutableState.value.isConnected) {
                syncSession()
                delay(1_000L)
            }
        }
    }

    private fun stopPositionSync() {
        positionJob?.cancel()
        positionJob = null
    }
}

private data class PendingWebCastLoad(
    val queue: List<Track>,
    val index: Int,
    val positionMs: Long,
    val wasLocalPlaying: Boolean,
    val restoreLocalOnFailure: Boolean,
    val requestId: Long,
)

@Serializable
internal data class WebCastLoadRequest(
    val items: List<WebCastMedia>,
    val startIndex: Int,
    val startPositionMs: Long = 0L,
)

@Serializable
internal data class WebCastMedia(
    val trackId: String,
    val provider: String?,
    val url: String,
    val contentType: String,
    val title: String,
    val artist: String,
    val album: String,
    val imageUrl: String?,
    val durationMs: Long,
    val streamUrl: String,
    val downloadUrl: String,
    val filepath: String?,
    val audioCodec: String?,
    val isLiveStream: Boolean = false,
)

@Serializable
private data class WebCastStatus(
    val isAvailable: Boolean = false,
    val isConnected: Boolean = false,
    val hasMedia: Boolean = false,
    val deviceName: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val trackId: String? = null,
    val contentId: String? = null,
    val isEnded: Boolean = false,
)

internal fun webCastQueueSupport(queue: List<Track>): CastQueueSupport {
    return queue.chromecastQueueSupport()
}

internal fun webCastLoadRequest(
    queue: List<Track>,
    startIndex: Int,
    startPositionMs: Long = 0L,
): WebCastLoadRequest? {
    if (!webCastQueueSupport(queue).isSupported) return null
    val items = queue.map { track ->
        val descriptor = track.toCastMediaDescriptor()
        if (!descriptor.castUrl.isCastReceiverLoadableUrl()) return null
        descriptor.toWebCastMedia()
    }
    return WebCastLoadRequest(
        items = items,
        startIndex = startIndex.coerceIn(queue.indices),
        startPositionMs = startPositionMs.coerceAtLeast(0L),
    )
}

private fun CastMediaDescriptor.toWebCastMedia(): WebCastMedia =
    WebCastMedia(
        trackId = trackId,
        provider = trackId.substringBefore(':', missingDelimiterValue = "").takeIf(String::isNotBlank),
        url = castUrl,
        contentType = contentType,
        title = title,
        artist = artist,
        album = album,
        imageUrl = thumbUrl?.takeIf { it.isCastReceiverLoadableUrl() },
        durationMs = durationMs,
        streamUrl = streamUrl,
        downloadUrl = downloadUrl,
        filepath = filepath,
        audioCodec = audioCodec,
        isLiveStream = isLiveStream,
    )

private val WebCastJson = Json {
    encodeDefaults = false
    ignoreUnknownKeys = true
}

private const val WebCastUnavailableMessage = "Chromecast requires Chrome with Cast support."
private const val WebCastRemoteQueueMessage = "This queue can't be cast to Chromecast."

@JsFun(
	    """(callback) => {
	        const getState = () => globalThis.__phoebeCastState ||
	            (globalThis.__phoebeCastState = { context: null, media: null, mediaListener: null, wrapped: false, lastSession: null, forceDisconnected: false });
	        const state = getState();
	        const getContext = () => {
	            try {
	                return globalThis.cast?.framework?.CastContext?.getInstance?.() || null;
	            } catch (_) {
	                return null;
	            }
	        };
	        const getSession = () => {
	            const state = getState();
	            if (state.forceDisconnected) return null;
	            const context = getContext();
	            if (context) {
	                const session = context.getCurrentSession?.() || null;
	                state.lastSession = session;
	                if (!session) {
	                    state.media = null;
	                    state.mediaListener = null;
	                }
	                return session;
	            }
	            const session = state.lastSession || null;
	            return session;
	        };
	        const getMedia = () => {
	            const state = getState();
	            if (state.forceDisconnected) return null;
	            const session = getSession();
	            if (!session) {
	                state.media = null;
	                state.mediaListener = null;
	                return null;
	            }
	            return session?.getMediaSession?.() || state.media || null;
	        };
	        state.callback = callback;
	        const readStatus = () => {
	            try {
	                const context = getContext();
	                const ua = String(globalThis.navigator?.userAgent || "");
	                const chromeLike = /Chrome|Chromium|Edg\//.test(ua) && !/Firefox|Safari\/.*Version\//.test(ua);
	                const available = (!!context && !!globalThis.chrome?.cast) || chromeLike;
	                const session = getSession();
	                const mediaSession = getMedia();
	                const playerState = mediaSession?.playerState || "";
	                const media = mediaSession?.media || {};
	                const currentItem = Array.isArray(mediaSession?.items)
	                    ? mediaSession.items.find((item) => item?.itemId === mediaSession.currentItemId)
	                    : null;
	                const mediaInfo = currentItem?.media || media;
	                const currentTime = Number(mediaSession?.getEstimatedTime?.() ?? mediaSession?.currentTime ?? 0);
	                const duration = Number(mediaInfo?.duration ?? mediaSession?.duration ?? 0);
	                const customData = mediaInfo?.customData || {};
	                const hasMedia = !!mediaInfo?.contentId ||
	                    (Array.isArray(mediaSession?.items) && mediaSession.items.length > 0);
	                const playerStates = globalThis.chrome?.cast?.media?.PlayerState || {};
	                const idleReasons = globalThis.chrome?.cast?.media?.IdleReason || {};
	                const idleReason = mediaSession?.idleReason || "";
	                const isEnded = playerState === playerStates.IDLE &&
	                    (!idleReason || idleReason === idleReasons.FINISHED || String(idleReason).toUpperCase() === "FINISHED");
	                return JSON.stringify({
	                    isAvailable: available,
	                    isConnected: !!session,
	                    hasMedia: hasMedia,
	                    deviceName: session?.getCastDevice?.()?.friendlyName || null,
	                    isPlaying: playerState === playerStates.PLAYING,
	                    isBuffering: playerState === playerStates.BUFFERING,
	                    positionMs: Number.isFinite(currentTime) ? Math.max(0, Math.round(currentTime * 1000)) : 0,
	                    durationMs: Number.isFinite(duration) ? Math.max(0, Math.round(duration * 1000)) : 0,
	                    trackId: customData.phoebeTrackId || null,
	                    contentId: mediaInfo?.contentId || null,
	                    isEnded: isEnded
	                });
	            } catch (error) {
	                console.warn("Phoebe Chromecast status read failed.", error);
	                return JSON.stringify({
	                    isAvailable: false,
	                    isConnected: false,
	                    hasMedia: false
	                });
	            }
	        };
        const notify = () => {
            try {
                state.callback?.(readStatus());
            } catch (error) {
                console.warn("Phoebe Chromecast status callback failed.", error);
            }
        };
	        const errorMessage = (error, fallback) => {
	            const rawCode = error?.code || error?.errorCode || error?.type || error?.reason || "";
	            const code = String(rawCode || error?.description || "").toUpperCase();
	            if (code.includes("CANCEL")) return "Chromecast selection was cancelled.";
	            if (code.includes("TIMEOUT")) return "Chromecast did not respond in time.";
	            const message = error?.message || error?.description || error?.details;
	            const detail = message ? String(message) : "";
	            if (rawCode && detail && !detail.includes(String(rawCode))) {
	                return fallback + " (" + rawCode + ": " + detail + ")";
	            }
	            if (rawCode) return fallback + " (" + rawCode + ")";
	            return detail || fallback;
	        };
	        const attachMediaListener = () => {
	            const state = getState();
	            const media = getMedia();
	            if (state.media === media) return;
	            if (state.media && state.mediaListener && typeof state.media.removeUpdateListener === "function") {
	                try { state.media.removeUpdateListener(state.mediaListener); } catch (_) {}
	            }
            state.media = media || null;
            state.mediaListener = media ? (() => notify()) : null;
            if (media && state.mediaListener && typeof media.addUpdateListener === "function") {
                media.addUpdateListener(state.mediaListener);
            }
	        };
	        const installContextListeners = () => {
	            const context = getContext();
	            if (!context) {
	                notify();
	                return;
	            }
	            if (state.context !== context) {
                state.context = context;
                const eventTypes = globalThis.cast?.framework?.CastContextEventType || {};
                const add = context.addEventListener?.bind(context);
                add?.(eventTypes.CAST_STATE_CHANGED, () => notify());
                add?.(eventTypes.SESSION_STATE_CHANGED, () => {
                    const currentSession = context.getCurrentSession?.() || null;
                    if (!state.forceDisconnected) {
                        state.lastSession = currentSession;
                    }
                    if (!currentSession) {
                        state.media = null;
                        state.mediaListener = null;
                    }
                    attachMediaListener();
                    notify();
                });
            }
            attachMediaListener();
            notify();
        };
        globalThis.__phoebeCastReadStatus = readStatus;
        globalThis.__phoebeCastNotifyStatus = () => {
            attachMediaListener();
            notify();
        };
        globalThis.__phoebeCastErrorMessage = errorMessage;
        if (!state.wrapped) {
            const previous = globalThis.__onGCastApiAvailable;
            globalThis.__onGCastApiAvailable = (isAvailable) => {
	                try {
	                    if (typeof previous === "function") previous(isAvailable);
	                } catch (error) {
	                    console.warn("Phoebe Chromecast previous availability callback failed.", error);
	                }
	                installContextListeners();
	            };
	            state.wrapped = true;
	        }
	        globalThis.__phoebeCastGetState = getState;
	        globalThis.__phoebeCastGetContext = getContext;
	        globalThis.__phoebeCastGetSession = getSession;
	        globalThis.__phoebeCastGetMedia = getMedia;
	        installContextListeners();
	    }""",
)
private external fun webCastInstallStatusListener(callback: (String) -> Unit)

@JsFun(
	    """() => {
	        try {
	            return globalThis.__phoebeCastReadStatus?.() || "{}";
	        } catch (_) {
	            return "{}";
	        }
	    }""",
)
private external fun webCastStatus(): String

@JsFun(
	    """() => {
	        try {
	            const context = globalThis.__phoebeCastGetContext?.() ||
	                globalThis.cast?.framework?.CastContext?.getInstance?.() || null;
	            if (context && globalThis.chrome?.cast) return true;
	            const ua = String(globalThis.navigator?.userAgent || "");
	            return /Chrome|Chromium|Edg\//.test(ua) && !/Firefox|Safari\/.*Version\//.test(ua);
	        } catch (_) {
	            return false;
	        }
	    }""",
)
private external fun webCastAvailable(): Boolean

@JsFun(
	    """(success, failure) => {
	        try {
	            const context = globalThis.__phoebeCastGetContext?.() ||
	                globalThis.cast?.framework?.CastContext?.getInstance?.() || null;
	            if (!context) {
	                failure("Chromecast requires Chrome with Cast support.");
	                return;
	            }
	            const complete = (session) => {
	                    const state = globalThis.__phoebeCastGetState?.() ||
	                        (globalThis.__phoebeCastState = { context: null, media: null, mediaListener: null, wrapped: false, lastSession: null, forceDisconnected: false });
	                    state.forceDisconnected = false;
	                    state.lastSession = session || context.getCurrentSession?.() || state.lastSession || null;
	                    globalThis.__phoebeCastNotifyStatus?.();
	                    success();
                };
	            const requested = context.requestSession?.();
	            if (requested && typeof requested.then === "function") {
	                requested.then(complete).catch((error) => {
                    globalThis.__phoebeCastNotifyStatus?.();
                    failure(globalThis.__phoebeCastErrorMessage?.(error, "Couldn't start Chromecast session.") ||
                        "Couldn't start Chromecast session.");
                });
	            } else {
	                complete(requested || context.getCurrentSession?.() || null);
	            }
        } catch (error) {
            failure(globalThis.__phoebeCastErrorMessage?.(error, "Couldn't start Chromecast session.") ||
                "Couldn't start Chromecast session.");
        }
    }""",
)
private external fun webCastRequestSession(success: () -> Unit, failure: (String) -> Unit)

	@JsFun(
	    """() => {
	        try {
	            return !!globalThis.__phoebeCastGetSession?.();
	        } catch (_) {
	            return false;
	        }
	    }""",
)
private external fun webCastConnected(): Boolean

@JsFun(
	    """(payload, success, failure) => {
	        try {
	            const mediaApi = globalThis.chrome?.cast?.media;
	            const session = globalThis.__phoebeCastGetSession?.();
	            if (!session || !mediaApi) {
	                failure("Choose a Chromecast before casting.");
	                return;
	            }
	            const requestPayload = JSON.parse(payload);
            const items = Array.isArray(requestPayload.items) ? requestPayload.items : [];
            const startIndex = Math.min(Math.max(Number(requestPayload.startIndex || 0), 0), Math.max(items.length - 1, 0));
            const startTime = Math.max(0, Number(requestPayload.startPositionMs || 0) / 1000);
            if (!items.length) {
                failure("Choose songs before casting to Chromecast.");
                return;
            }
	            const toMediaInfo = (item) => {
	                const mediaInfo = new mediaApi.MediaInfo(item.url, item.contentType || "audio/mpeg");
	                mediaInfo.contentUrl = item.url;
	                mediaInfo.streamType = item.isLiveStream
	                    ? (mediaApi.StreamType?.LIVE || "LIVE")
	                    : (mediaApi.StreamType?.BUFFERED || "BUFFERED");
	                mediaInfo.duration = item.isLiveStream ? null : Math.max(0, Number(item.durationMs || 0)) / 1000;
	                mediaInfo.customData = {
                    phoebeTrackId: item.trackId,
                    provider: item.provider,
                    title: item.title,
                    artist: item.artist,
                    album: item.album,
                    durationMs: item.durationMs,
                    streamUrl: item.streamUrl,
                    castUrl: item.url,
                    downloadUrl: item.downloadUrl,
                    thumbUrl: item.imageUrl,
                    filepath: item.filepath,
                    audioCodec: item.audioCodec
                };
                const metadata = new mediaApi.MusicTrackMediaMetadata();
	                metadata.title = item.title || "Chromecast audio";
	                metadata.artist = item.artist || "";
	                metadata.albumName = item.album || "";
	                if (item.imageUrl && (item.imageUrl.startsWith("http://") || item.imageUrl.startsWith("https://"))) {
	                    metadata.images = typeof globalThis.chrome?.cast?.Image === "function"
	                        ? [new globalThis.chrome.cast.Image(item.imageUrl)]
	                        : [{ url: item.imageUrl }];
	                }
	                mediaInfo.metadata = metadata;
	                return mediaInfo;
	            };
            const queueItems = items.map((item, index) => {
                const mediaInfo = toMediaInfo(item);
                const queueItem = typeof mediaApi.QueueItem === "function"
                    ? new mediaApi.QueueItem(mediaInfo)
                    : { media: mediaInfo };
                queueItem.media = queueItem.media || mediaInfo;
                queueItem.autoplay = true;
                queueItem.startTime = index === startIndex ? startTime : 0;
                queueItem.customData = mediaInfo.customData;
                return queueItem;
            });
	            const makeLoadRequest = (includeQueue) => {
	                const loadRequest = new mediaApi.LoadRequest(queueItems[startIndex].media);
	                loadRequest.autoplay = true;
	                loadRequest.currentTime = startTime;
	                loadRequest.customData = queueItems[startIndex].media.customData;
	                if (includeQueue && queueItems.length > 1 && typeof mediaApi.QueueData === "function") {
	                    const queueData = new mediaApi.QueueData(
	                        undefined,
	                        "Phoebe Queue",
	                        undefined,
	                        mediaApi.RepeatMode?.REPEAT_OFF,
	                        queueItems,
	                        startIndex,
	                        startTime
	                    );
	                    loadRequest.queueData = queueData;
	                }
	                return loadRequest;
	            };
	            const sendLoad = (includeQueue) => {
	                const loadRequest = makeLoadRequest(includeQueue);
	                globalThis.__phoebeLastCastLoadRequest = loadRequest;
	                const loadResult = session.loadMedia(loadRequest);
	                const loadPromise = loadResult && typeof loadResult.then === "function"
	                    ? loadResult
	                    : loadResult
	                        ? Promise.resolve(loadResult)
	                    : new Promise((resolve, reject) => {
	                        setTimeout(() => {
	                            const loaded = session.getMediaSession?.() || globalThis.__phoebeCastGetMedia?.() || null;
	                            if (loaded) resolve(loaded);
	                            else reject({ message: "Chromecast did not start this stream in time." });
	                        }, 1500);
	                    });
	                const timeout = new Promise((_, reject) => {
	                    setTimeout(() => reject({ message: "Chromecast did not start this stream in time." }), 15000);
	                });
	                return Promise.race([loadPromise, timeout]);
	            };
	            const loadWithFallback = sendLoad(queueItems.length > 1)
	                .catch((queueError) => {
	                    if (queueItems.length <= 1) throw queueError;
	                    console.warn("Phoebe Chromecast queue load failed, retrying current item only.", queueError);
	                    return sendLoad(false);
	                });
	            loadWithFallback.then(
	                (mediaSession) => {
	                    const state = globalThis.__phoebeCastGetState?.();
	                    if (mediaSession && state) {
	                        state.media = mediaSession;
	                    }
	                    globalThis.__phoebeCastNotifyStatus?.();
	                    success();
	                },
	                (error) => {
	                    globalThis.__phoebeCastNotifyStatus?.();
	                    failure(globalThis.__phoebeCastErrorMessage?.(error, "Couldn't load on Chromecast.") ||
	                        "Couldn't load on Chromecast.");
	                }
	            );
        } catch (error) {
            failure(globalThis.__phoebeCastErrorMessage?.(error, "Couldn't load on Chromecast.") ||
                "Couldn't load on Chromecast.");
        }
    }""",
)
private external fun webCastLoadMedia(payload: String, success: () -> Unit, failure: (String) -> Unit)

@JsFun(
	    """(success, failure) => {
	        try {
	            const media = globalThis.__phoebeCastGetMedia?.();
	            if (!media || !globalThis.chrome?.cast?.media) {
	                failure("No Chromecast media is loaded.");
	                return;
            }
            const done = () => {
                globalThis.__phoebeCastNotifyStatus?.();
                success();
            };
            const fail = (error) => failure(globalThis.__phoebeCastErrorMessage?.(error, "Couldn't control Chromecast playback.") ||
                "Couldn't control Chromecast playback.");
            if (media.playerState === globalThis.chrome.cast.media.PlayerState.PLAYING) {
                media.pause(null, done, fail);
            } else {
                media.play(null, done, fail);
            }
        } catch (error) {
            failure(globalThis.__phoebeCastErrorMessage?.(error, "Couldn't control Chromecast playback.") ||
                "Couldn't control Chromecast playback.");
        }
    }""",
)
private external fun webCastTogglePlayPause(success: () -> Unit, failure: (String) -> Unit)

@JsFun(
	    """(seconds, success, failure) => {
	        try {
	            const media = globalThis.__phoebeCastGetMedia?.();
	            if (!media || !globalThis.chrome?.cast?.media) {
	                failure("No Chromecast media is loaded.");
	                return;
            }
            const request = new globalThis.chrome.cast.media.SeekRequest();
            request.currentTime = Math.max(0, Number(seconds || 0));
            media.seek(request, () => {
                globalThis.__phoebeCastNotifyStatus?.();
                success();
            }, (error) => failure(globalThis.__phoebeCastErrorMessage?.(error, "Couldn't seek Chromecast playback.") ||
                "Couldn't seek Chromecast playback."));
        } catch (error) {
            failure(globalThis.__phoebeCastErrorMessage?.(error, "Couldn't seek Chromecast playback.") ||
                "Couldn't seek Chromecast playback.");
        }
    }""",
)
private external fun webCastSeek(seconds: Double, success: () -> Unit, failure: (String) -> Unit)

@JsFun(
	    """(success, failure) => {
	        try {
	            const media = globalThis.__phoebeCastGetMedia?.();
	            const hasReceiverQueue = Array.isArray(media?.items) && media.items.length > 1;
	            if (!media || !hasReceiverQueue || typeof media.queueNext !== "function") {
	                failure("Chromecast queue controls are not available.");
	                return;
	            }
            media.queueNext(() => {
                globalThis.__phoebeCastNotifyStatus?.();
                success();
            }, (error) => failure(globalThis.__phoebeCastErrorMessage?.(error, "Couldn't skip Chromecast playback.") ||
                "Couldn't skip Chromecast playback."));
        } catch (error) {
            failure(globalThis.__phoebeCastErrorMessage?.(error, "Couldn't skip Chromecast playback.") ||
                "Couldn't skip Chromecast playback.");
        }
    }""",
)
private external fun webCastQueueNext(success: () -> Unit, failure: (String) -> Unit)

@JsFun(
	    """(success, failure) => {
	        try {
	            const media = globalThis.__phoebeCastGetMedia?.();
	            const hasReceiverQueue = Array.isArray(media?.items) && media.items.length > 1;
	            if (!media || !hasReceiverQueue || typeof media.queuePrev !== "function") {
	                failure("Chromecast queue controls are not available.");
	                return;
	            }
            media.queuePrev(() => {
                globalThis.__phoebeCastNotifyStatus?.();
                success();
            }, (error) => failure(globalThis.__phoebeCastErrorMessage?.(error, "Couldn't skip Chromecast playback.") ||
                "Couldn't skip Chromecast playback."));
        } catch (error) {
            failure(globalThis.__phoebeCastErrorMessage?.(error, "Couldn't skip Chromecast playback.") ||
                "Couldn't skip Chromecast playback.");
        }
    }""",
)
private external fun webCastQueuePrevious(success: () -> Unit, failure: (String) -> Unit)

	@JsFun(
	    """() => {
	        let session = null;
	        try {
	            session = globalThis.__phoebeCastGetSession?.() || null;
	            session?.endSession?.(true);
	        } catch (_) {
	        } finally {
	            if (globalThis.__phoebeCastState) {
	                globalThis.__phoebeCastState.forceDisconnected = true;
	                globalThis.__phoebeCastState.lastSession = null;
	                globalThis.__phoebeCastState.media = null;
	                globalThis.__phoebeCastState.mediaListener = null;
	            }
	        }
	    }""",
	)
private external fun webCastDisconnect()
