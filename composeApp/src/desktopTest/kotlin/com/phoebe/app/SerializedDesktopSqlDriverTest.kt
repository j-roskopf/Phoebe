package com.phoebe.app

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.phoebe.app.data.db.SerializedDesktopSqlDriver
import com.phoebe.app.data.db.phoebeDatabaseFromDriver
import com.phoebe.app.db.PhoebeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SerializedDesktopSqlDriverTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var driver: SqlDriver? = null

    @After
    fun cleanup() {
        driver?.close()
        driver = null
    }

    @Test
    fun transactionBlocksConcurrentWorkFromOtherThreads() = runBlocking {
        val schema: SqlSchema<QueryResult.AsyncValue<Unit>> = PhoebeDatabase.Schema
        val dbFile = temp.newFile("phoebe.db")
        val serializedDriver = SerializedDesktopSqlDriver(
            JdbcSqliteDriver(
                url = "jdbc:sqlite:${dbFile.absolutePath}",
                properties = Properties(),
                schema = schema.synchronous(),
            ),
        )
        driver = serializedDriver
        val database = phoebeDatabaseFromDriver(serializedDriver)

        val transactionStarted = CountDownLatch(1)
        val releaseTransaction = CountDownLatch(1)
        val secondWriteFinished = AtomicBoolean(false)

        val transactionJob = async(Dispatchers.Default) {
            database.transaction {
                database.appSettingsQueries.upsert(
                    crossfadeSeconds = 3L,
                    scanLibraryOnLaunch = 0L,
                    notifyWhenDownloadFinishes = 0L,
                )
                transactionStarted.countDown()
                assertFalse(secondWriteFinished.get())
                assertTrue(releaseTransaction.await(2, TimeUnit.SECONDS))
            }
        }

        assertTrue(transactionStarted.await(2, TimeUnit.SECONDS))

        val secondWriteJob = async(Dispatchers.Default) {
            database.mediaSourcesQueries.insertOrReplace("local", "file:///music", "Music", 1L)
            secondWriteFinished.set(true)
        }

        Thread.sleep(100)
        assertFalse(secondWriteFinished.get())

        releaseTransaction.countDown()
        transactionJob.await()
        secondWriteJob.await()

        val folders = database.mediaSourcesQueries.selectAll().awaitAsList()
        assertEquals(1, folders.size)
    }
}
