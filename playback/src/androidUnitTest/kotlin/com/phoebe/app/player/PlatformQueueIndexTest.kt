package com.phoebe.app.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlatformQueueIndexTest {
    @Test
    fun usesLoadedMappingWhenPlaylistCountsMatch() {
        val queueIds = listOf("a", "b", "c", "d")
        val loaded = LoadedPlatformQueue(queueIds = queueIds, firstAppIndex = 1, itemCount = 3)

        val resolved = resolvePlatformQueueIndex(
            loaded = loaded,
            queueIds = queueIds,
            targetIndex = 2,
            platformMediaIds = listOf("b", "c", "d"),
        )

        assertNotNull(resolved)
        assertEquals(1, resolved.first)
        assertEquals(loaded, resolved.second)
    }

    @Test
    fun recoversMappingFromAndroidAutoAdoptedPlaylistWhenLoadedIsMissing() {
        val queueIds = listOf("a", "b", "c", "d")

        val resolved = resolvePlatformQueueIndex(
            loaded = null,
            queueIds = queueIds,
            targetIndex = 2,
            platformMediaIds = listOf("a", "b", "c", "d"),
        )

        assertNotNull(resolved)
        assertEquals(2, resolved.first)
        assertEquals(0, resolved.second.firstAppIndex)
        assertEquals(4, resolved.second.itemCount)
        assertEquals(queueIds, resolved.second.queueIds)
    }

    @Test
    fun recoversCompactedWindowAfterGaplessPrepare() {
        val queueIds = listOf("a", "b", "c", "d", "e")
        val stale = LoadedPlatformQueue(queueIds = queueIds, firstAppIndex = 0, itemCount = 5)

        val resolved = resolvePlatformQueueIndex(
            loaded = stale,
            queueIds = queueIds,
            targetIndex = 3,
            platformMediaIds = listOf("c", "d", "e"),
        )

        assertNotNull(resolved)
        assertEquals(1, resolved.first)
        assertEquals(2, resolved.second.firstAppIndex)
        assertEquals(3, resolved.second.itemCount)
    }

    @Test
    fun returnsNullWhenTargetIsNotOnThePlatformPlaylist() {
        val queueIds = listOf("a", "b", "c", "d")

        val resolved = resolvePlatformQueueIndex(
            loaded = null,
            queueIds = queueIds,
            targetIndex = 3,
            platformMediaIds = listOf("a", "b"),
        )

        assertNull(resolved)
    }

    @Test
    fun ignoresMisalignedPartialPlaylistMatches() {
        val queueIds = listOf("a", "b", "c", "d")

        val resolved = resolvePlatformQueueIndex(
            loaded = null,
            queueIds = queueIds,
            targetIndex = 1,
            // "b" appears, but neighbors do not match the app queue at that offset.
            platformMediaIds = listOf("x", "b", "y"),
        )

        assertNull(resolved)
    }
}
