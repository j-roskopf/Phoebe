package com.phoebe.app.player

/**
 * Cast protocol messages are capped at 64 KiB. Yesterday's Pixel 10 Pro session loaded
 * 80 queue items at ~122 KiB (`cast load failed status=13`) and then started local
 * playback while the receiver kept going.
 */
const val CAST_PROTOCOL_MAX_MESSAGE_BYTES = 64 * 1024
const val CAST_LOAD_MESSAGE_BYTE_BUDGET = 56 * 1024
const val CAST_MAX_RECEIVER_QUEUE_ITEMS = 80
const val CAST_MEDIA_ERROR_MAX_RETRIES = 2
const val CAST_UNEXPECTED_DISCONNECT_RECONNECT_MS = 15_000L

/** [com.google.android.gms.cast.MediaStatus] player states, mirrored for common tests. */
const val CAST_PLAYER_STATE_UNKNOWN = 0
const val CAST_PLAYER_STATE_IDLE = 1
const val CAST_PLAYER_STATE_PLAYING = 2
const val CAST_PLAYER_STATE_PAUSED = 3
const val CAST_PLAYER_STATE_BUFFERING = 4

/** [com.google.android.gms.cast.MediaStatus] idle reasons, mirrored for common tests. */
const val CAST_IDLE_REASON_NONE = 0
const val CAST_IDLE_REASON_FINISHED = 1
const val CAST_IDLE_REASON_CANCELED = 2
const val CAST_IDLE_REASON_INTERRUPTED = 3
const val CAST_IDLE_REASON_ERROR = 4

enum class CastSessionDisconnectReason {
    UserRequested,
    Unexpected,
}

fun shouldRestoreLocalAfterCastSessionEnd(reason: CastSessionDisconnectReason): Boolean =
    reason == CastSessionDisconnectReason.UserRequested

enum class CastLoadFailureAction {
    RetrySmallerQueue,
    HoldOnReceiver,
    RestoreLocal,
}

fun decideCastLoadFailureAction(
    sessionConnected: Boolean,
    failedReceiverItemCount: Int,
): CastLoadFailureAction = when {
    sessionConnected && failedReceiverItemCount > 1 -> CastLoadFailureAction.RetrySmallerQueue
    sessionConnected -> CastLoadFailureAction.HoldOnReceiver
    else -> CastLoadFailureAction.RestoreLocal
}

fun nextCastLoadRetryItemCount(failedReceiverItemCount: Int): Int? {
    if (failedReceiverItemCount <= 1) return null
    return (failedReceiverItemCount / 2).coerceAtLeast(1)
}

fun mediaErrorRetryCountFor(
    currentTrackId: String?,
    previousTrackId: String?,
    previousCount: Int,
): Int = if (currentTrackId != null && currentTrackId == previousTrackId) previousCount else 0

fun shrinkCastReceiverQueueItemCount(
    tailSize: Int,
    maxItems: Int = CAST_MAX_RECEIVER_QUEUE_ITEMS,
    maxBytes: Int = CAST_LOAD_MESSAGE_BYTE_BUDGET,
    estimatedBytesForCount: (Int) -> Int,
): Int {
    if (tailSize <= 0) return 0
    var itemCount = tailSize.coerceAtMost(maxItems).coerceAtLeast(1)
    while (itemCount > 1) {
        if (estimatedBytesForCount(itemCount) <= maxBytes) return itemCount
        itemCount = (itemCount / 2).coerceAtLeast(1)
    }
    return 1
}

sealed interface CastIdleDecision {
    data object Ignore : CastIdleDecision
    data object AdvanceReceiverQueue : CastIdleDecision
    data class LoadNextWindow(val startIndex: Int) : CastIdleDecision
    data object RetryCurrent : CastIdleDecision
    data class SkipFailedTrack(val nextIndex: Int) : CastIdleDecision
}

fun decideCastIdleAction(
    playerState: Int,
    idleReason: Int,
    hasPendingHandoff: Boolean,
    currentIndex: Int,
    lastAppIndex: Int,
    nextItemAlreadyOnReceiver: Boolean,
    currentErrorRetryCount: Int,
    maxErrorRetries: Int = CAST_MEDIA_ERROR_MAX_RETRIES,
): CastIdleDecision {
    if (hasPendingHandoff || playerState != CAST_PLAYER_STATE_IDLE) return CastIdleDecision.Ignore
    if (currentIndex < 0 || lastAppIndex < 0) return CastIdleDecision.Ignore
    return when (idleReason) {
        CAST_IDLE_REASON_FINISHED -> when {
            currentIndex >= lastAppIndex -> CastIdleDecision.Ignore
            nextItemAlreadyOnReceiver -> CastIdleDecision.AdvanceReceiverQueue
            else -> CastIdleDecision.LoadNextWindow(currentIndex + 1)
        }
        CAST_IDLE_REASON_ERROR -> when {
            currentErrorRetryCount < maxErrorRetries -> CastIdleDecision.RetryCurrent
            currentIndex < lastAppIndex -> CastIdleDecision.SkipFailedTrack(currentIndex + 1)
            else -> CastIdleDecision.Ignore
        }
        else -> CastIdleDecision.Ignore
    }
}

sealed interface CastSkipDecision {
    data object None : CastSkipDecision
    data object AdvanceReceiverQueue : CastSkipDecision
    data class LoadWindow(val startIndex: Int) : CastSkipDecision
}

fun decideCastSkipAction(
    targetIndex: Int,
    queueSize: Int,
    targetAlreadyOnReceiver: Boolean,
): CastSkipDecision {
    if (queueSize <= 0 || targetIndex !in 0 until queueSize) return CastSkipDecision.None
    return if (targetAlreadyOnReceiver) {
        CastSkipDecision.AdvanceReceiverQueue
    } else {
        CastSkipDecision.LoadWindow(targetIndex)
    }
}

fun castIdleReasonName(idleReason: Int): String = when (idleReason) {
    CAST_IDLE_REASON_NONE -> "NONE"
    CAST_IDLE_REASON_FINISHED -> "FINISHED"
    CAST_IDLE_REASON_CANCELED -> "CANCELED"
    CAST_IDLE_REASON_INTERRUPTED -> "INTERRUPTED"
    CAST_IDLE_REASON_ERROR -> "ERROR"
    else -> "UNKNOWN($idleReason)"
}

fun castPlayerStateName(playerState: Int): String = when (playerState) {
    CAST_PLAYER_STATE_UNKNOWN -> "UNKNOWN"
    CAST_PLAYER_STATE_IDLE -> "IDLE"
    CAST_PLAYER_STATE_PLAYING -> "PLAYING"
    CAST_PLAYER_STATE_PAUSED -> "PAUSED"
    CAST_PLAYER_STATE_BUFFERING -> "BUFFERING"
    else -> "UNKNOWN($playerState)"
}
