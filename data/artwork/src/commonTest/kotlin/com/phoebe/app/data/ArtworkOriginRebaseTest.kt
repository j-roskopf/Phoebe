package com.phoebe.app.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtworkOriginRebaseTest {
    @AfterTest
    fun tearDown() {
        ArtworkOriginHolder.clear()
    }

    @Test
    fun artworkCacheKeyPathIgnoresHostSoRelayRotationSharesDiskCache() {
        val stale =
            "https://23-239-17-63.abc.plex.direct:8443/library/metadata/1/thumb/123?X-Plex-Token=t"
        val live =
            "https://45-79-210-225.abc.plex.direct:8443/library/metadata/1/thumb/123?X-Plex-Token=t"
        assertEquals(artworkCacheKeyPath(stale), artworkCacheKeyPath(live))
        assertEquals(cachedArtworkPathForUrl(stale), cachedArtworkPathForUrl(live))
    }

    @Test
    fun rebaseArtworkUrlOriginSwapsHostKeepsPathAndToken() {
        val stale =
            "https://23-239-17-63.abc.plex.direct:8443/library/metadata/1/thumb/123?X-Plex-Token=old"
        val live = "http://192.168.1.9:32400"
        assertEquals(
            "http://192.168.1.9:32400/library/metadata/1/thumb/123?X-Plex-Token=old",
            rebaseArtworkUrlOrigin(stale, live),
        )
    }

    @Test
    fun artworkOriginHolderExposesLiveAndFallbackCandidates() {
        ArtworkOriginHolder.update(
            live = "http://192.168.1.9:32400",
            fallbacks = listOf(
                "https://45-79-210-225.abc.plex.direct:8443",
                "http://192.168.1.9:32400",
            ),
        )
        assertEquals(
            listOf(
                "http://192.168.1.9:32400",
                "https://45-79-210-225.abc.plex.direct:8443",
            ),
            ArtworkOriginHolder.candidateOrigins(),
        )
        assertTrue("/library/metadata/1/thumb".isRebaseableServerArtworkUrl())
        assertTrue(
            "https://host/library/metadata/1/thumb?X-Plex-Token=t".isRebaseableServerArtworkUrl(),
        )
    }
}
