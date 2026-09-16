package com.phoebe.app.player

/**
 * Light throttle for non-visualizer chrome amplitude frames.
 * Replaces the deleted band-FFT [AudioAnalysisAccumulator] (Decision 10).
 */
class AudioAnalysisThrottle(
    private val minPublishIntervalMs: Long = DefaultPublishIntervalMs,
) {
    private var lastPublishedAtMs = Long.MIN_VALUE

    fun reset() {
        lastPublishedAtMs = Long.MIN_VALUE
    }

    fun canPublish(timestampMs: Long): Boolean {
        if (lastPublishedAtMs != Long.MIN_VALUE &&
            timestampMs - lastPublishedAtMs < minPublishIntervalMs
        ) {
            return false
        }
        lastPublishedAtMs = timestampMs
        return true
    }

    companion object {
        const val DefaultPublishIntervalMs = 32L
    }
}
