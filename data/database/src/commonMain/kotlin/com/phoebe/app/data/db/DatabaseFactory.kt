package com.phoebe.app.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.phoebe.app.db.PhoebeDatabase

/**
 * Platform-specific driver creation for the SQLDelight-backed Phoebe database.
 *
 * Each platform creates a [SqlDriver] backed by a SQLite file located alongside the rest of
 * the app's user data so the database survives app restarts.
 */
expect suspend fun createSqlDriver(schema: SqlSchema<QueryResult.AsyncValue<Unit>>): SqlDriver

suspend fun createPhoebeDatabase(): PhoebeDatabase {
    val driver = createSqlDriver(PhoebeDatabase.Schema)
    repairPhoebeSchema(driver)
    return PhoebeDatabase(driver)
}

/** PRAGMA statements may return a result row; native drivers reject them via [SqlDriver.execute]. */
internal fun SqlDriver.execPragma(sql: String) {
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            while (cursor.next().value) { }
            QueryResult.Unit
        },
        parameters = 0,
    )
}

/** Opens [PhoebeDatabase] with an existing driver (in-memory JDBC, Android test context, etc.). */
fun phoebeDatabaseFromDriver(driver: SqlDriver): PhoebeDatabase = PhoebeDatabase(driver)
