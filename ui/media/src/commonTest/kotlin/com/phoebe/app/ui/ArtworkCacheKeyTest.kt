package com.phoebe.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtworkCacheKeyTest {
    private val thumbPath = "/library/metadata/1234/thumb/1699999999"

    /**
     * The whole point: a relay rotation must not orphan the cache. Both of these hops serve the
     * same bytes, and before this key they were two unrelated disk entries.
     */
    @Test
    fun sameAssetOnDifferentOriginsSharesOneKey() {
        val lan = stableArtworkCacheKey("http://192.168.1.20:32400$thumbPath?X-Plex-Token=abc")
        val relay = stableArtworkCacheKey(
            "https://45-79-210-125.hash.plex.direct:8443$thumbPath?X-Plex-Token=abc",
        )

        assertNotNull(lan)
        assertEquals(lan, relay)
    }

    @Test
    fun tokenRotationDoesNotChangeTheKey() {
        val old = stableArtworkCacheKey("https://relay.plex.direct:8443$thumbPath?X-Plex-Token=old")
        val new = stableArtworkCacheKey("https://relay.plex.direct:8443$thumbPath?X-Plex-Token=new")

        assertNotNull(old)
        assertEquals(old, new)
        assertTrue("old" !in old && "new" !in old, "session token leaked into cache key: $old")
    }

    @Test
    fun hostlessPlexPathKeysTheSameAsABoundUrl() {
        val unbound = stableArtworkCacheKey(thumbPath)
        val bound = stableArtworkCacheKey("https://relay.plex.direct:8443$thumbPath?X-Plex-Token=abc")

        assertNotNull(unbound)
        assertEquals(unbound, bound)
    }

    /** Different images must not collide, and different sizes are different bytes on disk. */
    @Test
    fun differentAssetsAndSizesKeepDistinctKeys() {
        val a = stableArtworkCacheKey("https://relay.plex.direct:8443/library/metadata/1/thumb/1")
        val b = stableArtworkCacheKey("https://relay.plex.direct:8443/library/metadata/2/thumb/1")
        assertTrue(a != b)

        val small = stableArtworkCacheKey(
            "https://relay.plex.direct:8443/photo/:/transcode?width=160&height=160&url=%2Fthumb",
        )
        val large = stableArtworkCacheKey(
            "https://relay.plex.direct:8443/photo/:/transcode?width=512&height=512&url=%2Fthumb",
        )
        assertTrue(small != large)
    }

    @Test
    fun parameterOrderDoesNotSplitOneImageAcrossTwoEntries() {
        val one = stableArtworkCacheKey(
            "https://s/photo/:/transcode?width=512&height=512&url=%2Fthumb&X-Plex-Token=t",
        )
        val two = stableArtworkCacheKey(
            "https://s/photo/:/transcode?url=%2Fthumb&height=512&X-Plex-Token=t&width=512",
        )

        assertNotNull(one)
        assertEquals(one, two)
    }

    @Test
    fun subsonicRotatingAuthIsStrippedButItemIdIsKept() {
        val first = stableArtworkCacheKey(
            "https://nav.example/rest/getCoverArt?id=al-42&size=256&u=joe&t=aaa&s=bbb&c=phoebe&v=1.16",
        )
        val second = stableArtworkCacheKey(
            "https://nav.example/rest/getCoverArt?id=al-42&size=256&u=joe&t=ccc&s=ddd&c=phoebe&v=1.16",
        )

        assertNotNull(first)
        assertEquals(first, second)
        assertTrue("id=al-42" in first, "item id must stay in the key: $first")
        assertTrue("size=256" in first, "size must stay in the key: $first")
    }

    /** Local artwork has no origin to strip; Coil's own key is already stable there. */
    @Test
    fun localAndBlankSourcesHaveNoCustomKey() {
        assertNull(stableArtworkCacheKey("file:///data/user/0/com.phoebe.app/files/art/1.jpg"))
        assertNull(stableArtworkCacheKey("content://media/external/audio/albumart/12"))
        assertNull(stableArtworkCacheKey(""))
        assertNull(stableArtworkCacheKey("   "))
    }

    /**
     * The pre-pass builds fetch URLs on a placeholder host so it can look an image up before any
     * origin is probed. Those must key identically to the real request or the lookup always misses.
     */
    @Test
    fun placeholderFetchUrlsKeyLikeRealRequests() {
        val placeholderKeys = placeholderArtworkFetchUrls(thumbPath, GridArtworkMaxDecodeDimension)
            .mapNotNull { stableArtworkCacheKey(it) }
        assertTrue(placeholderKeys.isNotEmpty(), "expected placeholder fetch urls for a Plex path")

        val realKeys = artworkOriginFetchCandidates(
            sourceUrl = thumbPath,
            maxDecodeDimension = GridArtworkMaxDecodeDimension,
            liveOrigin = "https://45-79-210-125.hash.plex.direct:8443",
            plexToken = "a-real-token",
        ).mapNotNull { stableArtworkCacheKey(it.fetchUrl) }
        assertTrue(realKeys.isNotEmpty(), "expected real fetch candidates for a Plex path")

        assertTrue(
            realKeys.all { it in placeholderKeys },
            "real request keys $realKeys are not covered by placeholder keys $placeholderKeys",
        )
    }
}
