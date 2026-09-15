package com.phoebe.app.domain

/** Aggregated play history for one artist. */
data class MostPlayedArtist(
    val id: String?,
    val title: String,
    val playCount: Long,
    val thumbUrl: String? = null,
    val lastPlayedMs: Long = 0L,
)
