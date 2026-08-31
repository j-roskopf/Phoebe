package com.phoebe.app.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.phoebe.app.platform.PhoebeLog

/**
 * Idempotent repairs for schema drift that SQLDelight migrations can miss when
 * `user_version` was bumped without applying the matching ALTER (e.g. a debug DB
 * already at v45 without [SessionRow.selectedServerRelayConnectionUris]).
 */
internal suspend fun repairPhoebeSchema(driver: SqlDriver) {
    ensureColumn(
        driver = driver,
        table = "SessionRow",
        column = "selectedServerRelayConnectionUris",
        typeSql = "TEXT",
    )
}

private suspend fun ensureColumn(
    driver: SqlDriver,
    table: String,
    column: String,
    typeSql: String,
) {
    if (tableHasColumn(driver, table, column)) return
    PhoebeLog.d("Database") { "repair: adding $table.$column" }
    driver.execute(null, "ALTER TABLE $table ADD COLUMN $column $typeSql", 0).awaitUnit()
}

private suspend fun tableHasColumn(driver: SqlDriver, table: String, column: String): Boolean {
    // PRAGMA table_info cannot bind the table name; keep it identifier-safe.
    require(table.all { it.isLetterOrDigit() || it == '_' }) { "unsafe table name: $table" }
    val columns = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA table_info($table)",
        mapper = { cursor ->
            val names = buildList {
                while (cursor.next().value) {
                    // cid, name, type, notnull, dflt_value, pk
                    cursor.getString(1)?.let(::add)
                }
            }
            QueryResult.Value(names)
        },
        parameters = 0,
    ).awaitValue()
    return column in columns
}

private suspend fun QueryResult<Long>.awaitUnit() {
    when (this) {
        is QueryResult.Value -> Unit
        is QueryResult.AsyncValue -> await()
    }
}

private suspend fun <T> QueryResult<T>.awaitValue(): T = when (this) {
    is QueryResult.Value -> value
    is QueryResult.AsyncValue -> await()
}
