package com.phoebe.app.player

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidPlaybackSeekTest {
    @Test
    fun retryPreservesPendingSeekWhenMedia3ReportsStartOfStream() {
        assertEquals(
            45_000L,
            playbackRetryPositionMs(
                platformPositionMs = 0L,
                pendingSeekPositionMs = 45_000L,
            ),
        )
    }

    @Test
    fun retryUsesPlatformPositionWhenThereIsNoPendingSeek() {
        assertEquals(
            12_000L,
            playbackRetryPositionMs(
                platformPositionMs = 12_000L,
                pendingSeekPositionMs = null,
            ),
        )
    }
}
