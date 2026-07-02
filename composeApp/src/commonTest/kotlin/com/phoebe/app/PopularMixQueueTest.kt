package com.phoebe.app

import com.phoebe.app.domain.Track
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
