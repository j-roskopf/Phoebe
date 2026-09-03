package com.phoebe.app

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.phoebe.app.data.db.DatabaseWriteGate
import com.phoebe.app.data.db.clearAllAppData
import com.phoebe.app.testing.newInMemoryPhoebeDatabaseHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseWriteSealDesktopTest {

    @Test
    fun sealedWritesAreDropped() = runBlocking {
        val gate = DatabaseWriteGate()
        val (handle, driver) = newInMemoryPhoebeDatabaseHandle(gate)
        try {
            gate.seal()
            handle.database.playHistoryQueries.recordPlay("plex:1", "Blur", "Blur", 1_000L)

            assertEquals(
                emptyList(),
                handle.database.playHistoryQueries.selectLatestPlayEventsByTrack().awaitAsList(),
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun readsStillWorkWhileSealed() = runBlocking {
        val gate = DatabaseWriteGate()
        val (handle, driver) = newInMemoryPhoebeDatabaseHandle(gate)
        try {
            handle.database.playHistoryQueries.recordPlay("plex:1", "Blur", "Blur", 1_000L)
            gate.seal()

            // The UI keeps querying while sign-out drains; sealing must not break it.
            val rows = handle.database.playHistoryQueries.selectLatestPlayEventsByTrack().awaitAsList()
            assertEquals(1, rows.size)
            assertEquals("plex:1", rows.single().track_id)
        } finally {
            driver.close()
        }
    }

    @Test
    fun unsealRestoresWrites() = runBlocking {
        val gate = DatabaseWriteGate()
        val (handle, driver) = newInMemoryPhoebeDatabaseHandle(gate)
        try {
            gate.seal()
            handle.database.playHistoryQueries.recordPlay("plex:1", "Blur", "Blur", 1_000L)
            gate.unseal()
            // The welcome screen adds local folders and radio stations while signed out.
            handle.database.mediaSourcesQueries.insertOrReplace("lf1", "file:///music", "Music", 1L)

            assertEquals(
                emptyList(),
                handle.database.playHistoryQueries.selectLatestPlayEventsByTrack().awaitAsList(),
            )
            assertEquals(1, handle.database.mediaSourcesQueries.selectAll().awaitAsList().size)
        } finally {
            driver.close()
        }
    }

    @Test
    fun privilegedHandleWipesWhileSealed() = runBlocking {
        val gate = DatabaseWriteGate()
        val (handle, driver) = newInMemoryPhoebeDatabaseHandle(gate)
        try {
            handle.database.playHistoryQueries.recordPlay("plex:1", "Blur", "Blur", 1_000L)
            gate.seal()

            handle.privileged.clearAllAppData(clearPlayHistory = true)

            assertEquals(
                emptyList(),
                handle.database.playHistoryQueries.selectLatestPlayEventsByTrack().awaitAsList(),
            )
        } finally {
            driver.close()
        }
    }

    /**
     * The wipe runs on a second [PhoebeDatabase] over the same driver. Query listeners live on the
     * driver, so observers registered through the gated handle must still see the delete — if they
     * did not, sign-out would leave the previous account's rows on screen until an app restart.
     */
    @Test
    fun privilegedWipeNotifiesObserversOnTheGatedDatabase() = runBlocking {
        val gate = DatabaseWriteGate()
        val (handle, driver) = newInMemoryPhoebeDatabaseHandle(gate)
        try {
            handle.database.playHistoryQueries.recordPlay("plex:1", "Blur", "Blur", 1_000L)

            val emissions = CopyOnWriteArrayList<Int>()
            val emptied = CompletableDeferred<Unit>()
            val collector = launch(Dispatchers.Default) {
                handle.database.playHistoryQueries.selectLatestPlayEventsByTrack()
                    .asFlow()
                    .mapToList(Dispatchers.Default)
                    .collect { rows ->
                        emissions += rows.size
                        if (rows.isEmpty()) emptied.complete(Unit)
                    }
            }
            withTimeout(5_000) { while (emissions.isEmpty()) delay(10) }
            assertEquals(1, emissions.first(), "observer should see the row before the wipe")

            gate.seal()
            handle.privileged.clearAllAppData(clearPlayHistory = true)
            gate.unseal()

            withTimeout(5_000) { emptied.await() }
            collector.cancel()
        } finally {
            driver.close()
        }
    }

    /**
     * The bug this seal exists for: a cancelled-but-unjoined Plex play-count refresh re-imported
     * `plex-stats` aggregates just after sign-out cleared them, leaving the home screen showing
     * unplayable Plex rows against a freshly signed-in Navidrome library.
     */
    @Test
    fun stragglerAggregateImportCannotSurviveTheWipe() = runBlocking {
        val gate = DatabaseWriteGate()
        val (handle, driver) = newInMemoryPhoebeDatabaseHandle(gate)
        try {
            handle.database.playHistoryQueries.insertPlayCountAggregate(
                track_id = "plex:26340",
                artist = "Third Eye Blind",
                album = "Third Eye Blind",
                play_count = 52L,
                last_played_at_ms = 1_000L,
                source = "plex-stats",
                server_id = "server-a",
                imported_at_ms = 1_000L,
            )
            assertNotNull(
                handle.database.playHistoryQueries
                    .selectPlayCountAggregateByTrack("plex:26340")
                    .awaitAsOneOrNull(),
            )

            // Sign-out: seal, then wipe through the privileged handle.
            gate.seal()
            handle.privileged.clearAllAppData(clearPlayHistory = true)

            // The straggler wakes up after the wipe and re-imports the same aggregate.
            handle.database.playHistoryQueries.insertPlayCountAggregate(
                track_id = "plex:26340",
                artist = "Third Eye Blind",
                album = "Third Eye Blind",
                play_count = 52L,
                last_played_at_ms = 1_000L,
                source = "plex-stats",
                server_id = "server-a",
                imported_at_ms = 2_000L,
            )
            gate.unseal()

            assertNull(
                handle.database.playHistoryQueries
                    .selectPlayCountAggregateByTrack("plex:26340")
                    .awaitAsOneOrNull(),
            )
            assertTrue(
                handle.database.playHistoryQueries.selectTopMostPlayedByTrack(10L).awaitAsList().isEmpty(),
            )
        } finally {
            driver.close()
        }
    }
}
