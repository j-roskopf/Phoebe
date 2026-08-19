package com.phoebe.app.player

import android.app.Application
import com.google.android.gms.cast.MediaStatus
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CastPlaybackPolicyAndroidTest {
    @Test
    fun mirroredCastPlayerStatesMatchGoogleCastSdk() {
        assertEquals(MediaStatus.PLAYER_STATE_UNKNOWN, CAST_PLAYER_STATE_UNKNOWN)
        assertEquals(MediaStatus.PLAYER_STATE_IDLE, CAST_PLAYER_STATE_IDLE)
        assertEquals(MediaStatus.PLAYER_STATE_PLAYING, CAST_PLAYER_STATE_PLAYING)
        assertEquals(MediaStatus.PLAYER_STATE_PAUSED, CAST_PLAYER_STATE_PAUSED)
        assertEquals(MediaStatus.PLAYER_STATE_BUFFERING, CAST_PLAYER_STATE_BUFFERING)
    }

    @Test
    fun mirroredCastIdleReasonsMatchGoogleCastSdk() {
        assertEquals(MediaStatus.IDLE_REASON_NONE, CAST_IDLE_REASON_NONE)
        assertEquals(MediaStatus.IDLE_REASON_FINISHED, CAST_IDLE_REASON_FINISHED)
        assertEquals(MediaStatus.IDLE_REASON_CANCELED, CAST_IDLE_REASON_CANCELED)
        assertEquals(MediaStatus.IDLE_REASON_INTERRUPTED, CAST_IDLE_REASON_INTERRUPTED)
        assertEquals(MediaStatus.IDLE_REASON_ERROR, CAST_IDLE_REASON_ERROR)
    }
}
