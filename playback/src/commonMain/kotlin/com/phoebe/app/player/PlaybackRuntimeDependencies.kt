package com.phoebe.app.player

import com.phoebe.app.data.CatalogRepository
import com.phoebe.app.data.SessionRepository
import com.phoebe.app.db.PhoebeDatabase

interface PlaybackRuntimeDependencies {
    val database: PhoebeDatabase
    val catalogRepository: CatalogRepository
    val sessionRepository: SessionRepository

    /**
     * Probe and publish the live media-server base for a headless process.
     *
     * The catalog stores host-less Plex paths and the origin is bound at request time from
     * [com.phoebe.app.data.ArtworkOriginHolder]. That holder is normally filled by `AppState`,
     * which only exists once Compose starts — but Android Auto binds [PlaybackService] without
     * ever launching the Activity. Nothing then binds an origin, so every stream URI stays a
     * relative `/library/parts/...` path (ExoPlayer: "Source error") and every thumb stays an
     * unloadable relative URI.
     *
     * Returns the live base, or null when there is no Plex session to resolve one for.
     */
    suspend fun ensureLivePlaybackOrigin(): String? = null
}
