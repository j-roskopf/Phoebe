package com.phoebe.app.media

/**
 * Platform-neutral now-playing state, shared by the macOS media session and the Linux
 * MPRIS session.
 *
 * Note that the units are not uniform: [positionBucketMs] holds **seconds**, quantised
 * from milliseconds so repeated position updates do not defeat `distinctUntilChanged`,
 * while [durationMs] holds milliseconds.
 */
internal data class NowPlayingSnapshot(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String,
    val positionBucketMs: Long,
    val durationMs: Long,
    val playing: Boolean,
)
