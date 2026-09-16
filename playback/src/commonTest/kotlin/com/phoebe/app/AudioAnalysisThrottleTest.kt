package com.phoebe.app

import com.phoebe.app.player.AudioAnalysisThrottle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioAnalysisThrottleTest {
    @Test
    fun throttlesRapidPublishes() {
        val throttle = AudioAnalysisThrottle(minPublishIntervalMs = 50)
        assertTrue(throttle.canPublish(100))
        assertFalse(throttle.canPublish(120))
        assertTrue(throttle.canPublish(160))
    }

    @Test
    fun resetAllowsImmediatePublish() {
        val throttle = AudioAnalysisThrottle(minPublishIntervalMs = 50)
        assertTrue(throttle.canPublish(100))
        throttle.reset()
        assertTrue(throttle.canPublish(101))
    }
}
