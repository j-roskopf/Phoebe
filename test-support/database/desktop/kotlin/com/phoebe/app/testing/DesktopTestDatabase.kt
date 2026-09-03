package com.phoebe.app.testing

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.phoebe.app.data.db.DatabaseWriteGate
import com.phoebe.app.data.db.PhoebeDatabaseHandle
import com.phoebe.app.data.db.phoebeDatabaseFromDriver
import com.phoebe.app.data.db.phoebeDatabaseHandleFromDriver
import com.phoebe.app.db.PhoebeDatabase
import java.util.Properties

fun newInMemoryPhoebeDatabase(): Pair<PhoebeDatabase, SqlDriver> =
    newInMemoryDriver().let { phoebeDatabaseFromDriver(it) to it }

/** In-memory database pair wired through [writeGate], so tests can exercise the write seal. */
fun newInMemoryPhoebeDatabaseHandle(
    writeGate: DatabaseWriteGate,
): Pair<PhoebeDatabaseHandle, SqlDriver> =
    newInMemoryDriver().let { phoebeDatabaseHandleFromDriver(it, writeGate) to it }

private fun newInMemoryDriver(): SqlDriver {
    val schema: SqlSchema<QueryResult.AsyncValue<Unit>> = PhoebeDatabase.Schema
    return JdbcSqliteDriver(
        url = "jdbc:sqlite::memory:",
        properties = Properties(),
        schema = schema.synchronous(),
    )
}
