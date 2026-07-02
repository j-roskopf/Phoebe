package com.phoebe.app

import com.phoebe.app.domain.Track
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PopularMixQueueTest {
    @Test
    fun popularMixQueueKeepsFullDedupedPool() {
        val tracks = (1..60).map { index ->
            Track(
                id = "plex:t$index",
                title = "Track $index",
                artist = "Artist $index",
                album = "Album $index",
                durationMs = 1_000L,
                streamUrl = "",
                downloadUrl = "",
            )
        } + Track(
            id = "plex:t1",
            title = "Duplicate Track",
            artist = "Duplicate Artist",
            album = "Duplicate Album",
            durationMs = 1_000L,
            streamUrl = "",
            downloadUrl = "",
        )

        val queue = tracks.popularMixQueue(Random(1))

        assertEquals(60, queue.size)
        assertEquals((1..60).map { "plex:t$it" }.toSet(), queue.map { it.id }.toSet())
        assertEquals((1..50).map { "plex:t$it" }.toSet(), queue.take(50).map { it.id }.toSet())
        assertEquals((51..60).map { "plex:t$it" }.toSet(), queue.drop(50).map { it.id }.toSet())
    }

    @Test
    fun mixAppendKeepsSeedQueueFirstAndAddsOnlyMissingFullMixTracks() {
        val seedQueue = (1..3).map { index -> track("plex:seed$index") }
        val fullMix = listOf(
            track("plex:seed2"),
            track("plex:extra1"),
            track("plex:seed1"),
            track("plex:extra2"),
            track("plex:extra1"),
        )

        assertTrue(mixQueueStillActiveForAppend(seedQueue, seedQueue, currentIndex = 0))

        val additions = mixAppendCandidates(fullMix, seedQueue)
        val nextQueue = seedQueue + additions

        assertEquals(seedQueue.map { it.id }, nextQueue.take(seedQueue.size).map { it.id })
        assertEquals(listOf("plex:extra1", "plex:extra2"), additions.map { it.id })
    }

    @Test
    fun mixAppendGuardRejectsDifferentOrInactiveQueues() {
        val seedQueue = listOf(track("plex:seed1"), track("plex:seed2"))

        assertFalse(mixQueueStillActiveForAppend(emptyList(), seedQueue, currentIndex = -1))
        assertFalse(mixQueueStillActiveForAppend(listOf(track("plex:other")), seedQueue, currentIndex = 0))
        assertFalse(
            mixQueueStillActiveForAppend(
                listOf(track("plex:seed2"), track("plex:seed1")),
                seedQueue,
                currentIndex = 0,
            ),
        )
    }

    private fun track(id: String): Track =
        Track(
            id = id,
            title = id,
            artist = "Artist",
            album = "Album",
            durationMs = 1_000L,
            streamUrl = "",
            downloadUrl = "",
        )
}
