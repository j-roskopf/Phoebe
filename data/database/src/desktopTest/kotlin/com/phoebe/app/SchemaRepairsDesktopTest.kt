package com.phoebe.app

import app.cash.sqldelight.db.SqlDriver
import com.phoebe.app.data.db.repairPhoebeSchema
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class SchemaRepairsDesktopTest {
    private var driver: SqlDriver? = null

    @AfterTest
    fun tearDown() {
        driver?.close()
        driver = null
    }

    @Test
    fun repairIsIdempotentWhenRelayColumnAlreadyExists() = runBlocking {
        val (_, d) = newInMemoryPhoebeDatabase()
        driver = d
        // Fresh schema already includes the column from CREATE.
        repairPhoebeSchema(d)
        repairPhoebeSchema(d)
        val names = columnNames(d, "SessionRow")
        assertTrue("selectedServerRelayConnectionUris" in names)
    }

    @Test
    fun repairAddsMissingRelayColumnOnDriftedSchema() = runBlocking {
        val d = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            url = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        driver = d
        d.execute(
            null,
            """
            CREATE TABLE SessionRow (
                id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1) DEFAULT 1,
                providerType TEXT NOT NULL DEFAULT 'Plex',
                token TEXT NOT NULL,
                userName TEXT NOT NULL
            )
            """.trimIndent(),
            0,
        )
        assertTrue("selectedServerRelayConnectionUris" !in columnNames(d, "SessionRow"))
        repairPhoebeSchema(d)
        assertTrue("selectedServerRelayConnectionUris" in columnNames(d, "SessionRow"))
    }

    private fun columnNames(driver: SqlDriver, table: String): List<String> {
        val names = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($table)",
            mapper = { cursor ->
                while (cursor.next().value) {
                    cursor.getString(1)?.let(names::add)
                }
                app.cash.sqldelight.db.QueryResult.Unit
            },
            parameters = 0,
        )
        return names
    }
}
