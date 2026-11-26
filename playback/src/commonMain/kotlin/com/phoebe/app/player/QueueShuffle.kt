package com.phoebe.app.player

import com.phoebe.app.domain.Track

/** A reordered queue and the index of the song that keeps playing in it. */
data class ShuffledQueue(
    val queue: List<Track>,
    val currentIndex: Int,
)

/** The song at [index] first, then every other song in random order. */
fun List<Track>.startingAtWithRestShuffled(index: Int): List<Track> {
    if (index !in indices) return this
    return listOf(this[index]) + filterIndexed { i, _ -> i != index }.shuffled()
}

/**
 * Turning shuffle on keeps the current song playing and reshuffles what comes after it, so Up
 * Next shows the new order. On the last song nothing is left upcoming, so the songs already played
 * move behind it in shuffled order; otherwise turning shuffle on would change nothing.
 *
 * Returns null when there is nothing to reorder.
 */
fun shuffleQueueAroundCurrent(queue: List<Track>, currentIndex: Int): ShuffledQueue? {
    if (currentIndex !in queue.indices || queue.size <= 1) return null
    if (currentIndex >= queue.lastIndex) {
        return ShuffledQueue(queue.startingAtWithRestShuffled(currentIndex), currentIndex = 0)
    }
    val head = queue.subList(0, currentIndex + 1).toList()
    val tail = queue.subList(currentIndex + 1, queue.size).shuffled()
    return ShuffledQueue(head + tail, currentIndex)
}
