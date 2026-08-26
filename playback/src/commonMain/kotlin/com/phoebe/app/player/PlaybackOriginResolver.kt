package com.phoebe.app.player

import kotlin.concurrent.Volatile

/**
 * Resolves the best media-server origin before the platform player opens a stream.
 * Implemented by [com.phoebe.app.data.PlexConnectionResolver] and wired through
 * [PlaybackOriginResolverHolder] because [AudioPlayer] is factory-constructed.
 */
interface PlaybackOriginResolver {
    /** Warm in-memory origin for the current network, or null if a race is needed. */
    fun cachedOrigin(): String?

    /**
     * Parallel origin race capped at [deadlineMs]. Returns the winning base URL,
     * or null if nothing responded in time (caller should still attempt primary URLs).
     */
    suspend fun resolveOrigin(deadlineMs: Long = DefaultPlayResolveDeadlineMs): String?

    /** True when LAN-only hosts should be demoted (always remote-first, or cellular). */
    fun demoteLocalOrigins(): Boolean

    companion object {
        const val DefaultPlayResolveDeadlineMs = 700L
    }
}

object PlaybackOriginResolverHolder {
    @Volatile
    var resolver: PlaybackOriginResolver? = null
}
