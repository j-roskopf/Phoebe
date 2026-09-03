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

/**
 * The app's two views onto one SQLite connection.
 *
 * [database] is what everything is injected with; its writes stop when [DatabaseWriteGate.seal]
 * is in effect. [privileged] bypasses the seal and exists for exactly one caller — the sign-out
 * wipe, which has to be able to delete while every other writer is locked out. Handing the wipe a
 * separate ungated handle keeps that exemption impossible to obtain by accident: there is no flag
 * a background job could observe at the wrong moment and slip through.
 *
 * Both wrap the same driver, so transactions serialize and query listeners still fire across the
 * pair — the UI observes the wipe normally.
 */
class PhoebeDatabaseHandle(
    val database: PhoebeDatabase,
    val privileged: PhoebeDatabase,
)

suspend fun createPhoebeDatabase(writeGate: DatabaseWriteGate): PhoebeDatabaseHandle {
    val driver = createSqlDriver(PhoebeDatabase.Schema)
    repairPhoebeSchema(driver)
    return PhoebeDatabaseHandle(
        database = PhoebeDatabase(GatedSqlDriver(driver, writeGate)),
        privileged = PhoebeDatabase(driver),
    )
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

/** [PhoebeDatabaseHandle] over an existing driver, for tests that supply their own connection. */
fun phoebeDatabaseHandleFromDriver(
    driver: SqlDriver,
    writeGate: DatabaseWriteGate,
): PhoebeDatabaseHandle = PhoebeDatabaseHandle(
    database = PhoebeDatabase(GatedSqlDriver(driver, writeGate)),
    privileged = PhoebeDatabase(driver),
)
