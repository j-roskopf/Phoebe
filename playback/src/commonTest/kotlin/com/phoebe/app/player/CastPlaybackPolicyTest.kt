package com.phoebe.app.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastPlaybackPolicyTest {
    @Test
    fun loadMessageBudgetStaysUnderCastProtocolLimit() {
        assertTrue(CAST_LOAD_MESSAGE_BYTE_BUDGET < CAST_PROTOCOL_MAX_MESSAGE_BYTES)
        assertEquals(64 * 1024, CAST_PROTOCOL_MAX_MESSAGE_BYTES)
        assertEquals(56 * 1024, CAST_LOAD_MESSAGE_BYTE_BUDGET)
    }

    @Test
    fun yesterdayOversizedQueueShrinksUnderByteBudget() {
        val itemCount = shrinkCastReceiverQueueItemCount(
            tailSize = 157,
            maxItems = 80,
            maxBytes = CAST_LOAD_MESSAGE_BYTE_BUDGET,
            estimatedBytesForCount = { count -> (121_698 * count) / 80 },
        )

        assertEquals(20, itemCount)
        assertTrue((121_698 * itemCount) / 80 <= CAST_LOAD_MESSAGE_BYTE_BUDGET)
    }

    @Test
    fun singleItemQueueIsKeptEvenWhenOverBudget() {
        assertEquals(
            1,
            shrinkCastReceiverQueueItemCount(
                tailSize = 1,
                estimatedBytesForCount = { 200_000 },
            ),
        )
    }

    @Test
    fun emptyTailProducesNoReceiverItems() {
        assertEquals(
            0,
            shrinkCastReceiverQueueItemCount(
                tailSize = 0,
                estimatedBytesForCount = { 0 },
            ),
        )
    }

    @Test
    fun unexpectedSessionEndDoesNotRestoreLocalPlayback() {
        assertFalse(shouldRestoreLocalAfterCastSessionEnd(CastSessionDisconnectReason.Unexpected))
        assertTrue(shouldRestoreLocalAfterCastSessionEnd(CastSessionDisconnectReason.UserRequested))
    }

    @Test
    fun loadFailureRetriesSmallerQueueWhileSessionStaysConnected() {
        assertEquals(
            CastLoadFailureAction.RetrySmallerQueue,
            decideCastLoadFailureAction(sessionConnected = true, failedReceiverItemCount = 80),
        )
        assertEquals(40, nextCastLoadRetryItemCount(80))
        assertEquals(
            CastLoadFailureAction.HoldOnReceiver,
            decideCastLoadFailureAction(sessionConnected = true, failedReceiverItemCount = 1),
        )
        assertNull(nextCastLoadRetryItemCount(1))
        assertEquals(
            CastLoadFailureAction.RestoreLocal,
            decideCastLoadFailureAction(sessionConnected = false, failedReceiverItemCount = 1),
        )
    }

    @Test
    fun finishedTrackAdvancesReceiverInsteadOfReloadingWholeQueue() {
        assertEquals(
            CastIdleDecision.AdvanceReceiverQueue,
            decideCastIdleAction(
                playerState = CAST_PLAYER_STATE_IDLE,
                idleReason = CAST_IDLE_REASON_FINISHED,
                hasPendingHandoff = false,
                currentIndex = 4,
                lastAppIndex = 156,
                nextItemAlreadyOnReceiver = true,
                currentErrorRetryCount = 0,
            ),
        )
    }

    @Test
    fun finishedTrackLoadsNextWindowWhenReceiverQueueIsExhausted() {
        assertEquals(
            CastIdleDecision.LoadNextWindow(5),
            decideCastIdleAction(
                playerState = CAST_PLAYER_STATE_IDLE,
                idleReason = CAST_IDLE_REASON_FINISHED,
                hasPendingHandoff = false,
                currentIndex = 4,
                lastAppIndex = 156,
                nextItemAlreadyOnReceiver = false,
                currentErrorRetryCount = 0,
            ),
        )
    }

    @Test
    fun mediaErrorRetriesSurviveBufferingAndPlayingOnTheSameTrack() {
        assertEquals(
            1,
            mediaErrorRetryCountFor(
                currentTrackId = "plex:1",
                previousTrackId = "plex:1",
                previousCount = 1,
            ),
        )
        assertEquals(
            0,
            mediaErrorRetryCountFor(
                currentTrackId = "plex:2",
                previousTrackId = "plex:1",
                previousCount = 1,
            ),
        )
    }

    @Test
    fun idleErrorRetriesThenSkipsInsteadOfSilentlyStopping() {
        assertEquals(
            CastIdleDecision.RetryCurrent,
            decideCastIdleAction(
                playerState = CAST_PLAYER_STATE_IDLE,
                idleReason = CAST_IDLE_REASON_ERROR,
                hasPendingHandoff = false,
                currentIndex = 4,
                lastAppIndex = 156,
                nextItemAlreadyOnReceiver = true,
                currentErrorRetryCount = 0,
            ),
        )
        assertEquals(
            CastIdleDecision.SkipFailedTrack(5),
            decideCastIdleAction(
                playerState = CAST_PLAYER_STATE_IDLE,
                idleReason = CAST_IDLE_REASON_ERROR,
                hasPendingHandoff = false,
                currentIndex = 4,
                lastAppIndex = 156,
                nextItemAlreadyOnReceiver = true,
                currentErrorRetryCount = CAST_MEDIA_ERROR_MAX_RETRIES,
            ),
        )
    }

    @Test
    fun pendingHandoffIgnoresIdleFinishedSoAutoplayDoesNotRaceAReload() {
        assertEquals(
            CastIdleDecision.Ignore,
            decideCastIdleAction(
                playerState = CAST_PLAYER_STATE_IDLE,
                idleReason = CAST_IDLE_REASON_FINISHED,
                hasPendingHandoff = true,
                currentIndex = 4,
                lastAppIndex = 156,
                nextItemAlreadyOnReceiver = false,
                currentErrorRetryCount = 0,
            ),
        )
    }

    @Test
    fun skipUsesReceiverQueueWhenTheTargetIsAlreadyLoaded() {
        assertEquals(
            CastSkipDecision.AdvanceReceiverQueue,
            decideCastSkipAction(targetIndex = 5, queueSize = 157, targetAlreadyOnReceiver = true),
        )
        assertEquals(
            CastSkipDecision.LoadWindow(5),
            decideCastSkipAction(targetIndex = 5, queueSize = 157, targetAlreadyOnReceiver = false),
        )
        assertEquals(
            CastSkipDecision.None,
            decideCastSkipAction(targetIndex = 157, queueSize = 157, targetAlreadyOnReceiver = false),
        )
    }
}
