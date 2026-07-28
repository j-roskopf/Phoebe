package com.phoebe.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.phoebe.app.data.db.phoebeDatabaseFromDriver
import com.phoebe.app.player.CatalogBrowseTree
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.time.measureTime

/**
 * Times voice search against a real device catalog. Skips unless PHOEBE_BENCH_DB points at a
 * pulled phoebe .db file, so it is inert in CI.
 */
class VoiceSearchBenchmark {

    @Test
    fun timeVoiceSearchAgainstRealCatalog() = runTest {
        val path = System.getenv("PHOEBE_BENCH_DB") ?: return@runTest
        val file = File(path)
        if (!file.exists()) return@runTest

        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", Properties())
        val db = phoebeDatabaseFromDriver(driver)
        val tree = CatalogBrowseTree(db)

        val trackCount = db.catalogQueries.selectAllTrackIds().executeAsList().size
        println("catalog tracks=$trackCount")

        // Warm the JIT and page cache so we time steady-state work, not first-touch I/O.
        repeat(3) { tree.searchTracks(query = "warmup") }

        listOf(
            Triple("artist request", "noah kahan", mapOf("artist" to "Noah Kahan")),
            Triple("title request", "stick season", mapOf("title" to "Stick Season")),
            Triple("freeform request", "love", emptyMap()),
            Triple("no match", "zzzzqqq", mapOf("artist" to "zzzzqqq")),
        ).forEach { (label, query, extras) ->
            val elapsed = measureTime {
                tree.searchTracks(
                    query = query,
                    title = extras["title"],
                    artist = extras["artist"],
                )
            }
            val hits = tree.searchTracks(
                query = query,
                title = extras["title"],
                artist = extras["artist"],
            ).size
            println("$label \"$query\" -> ${elapsed.inWholeMilliseconds}ms, $hits tracks")
        }

        driver.close()
    }
}
