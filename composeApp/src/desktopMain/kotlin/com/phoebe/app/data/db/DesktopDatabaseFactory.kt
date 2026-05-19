package com.phoebe.app.data.db

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

actual suspend fun createSqlDriver(schema: SqlSchema<QueryResult.AsyncValue<Unit>>): SqlDriver {
    val root = desktopDatabaseRoot()
    val dbFileName = localDatabaseFileName()
    val dbFile = File(root, dbFileName)
    val revFile = File(root, "$dbFileName.rev")

    wipeIfRevisionChanged(dbFile, revFile)

    // Passing the schema parameter lets the JDBC driver invoke Schema.create / Schema.migrate
    // automatically based on PRAGMA user_version, so future schema changes "just work".
    val properties = Properties().apply {
        setProperty("busy_timeout", "10000")
        setProperty("journal_mode", "WAL")
        setProperty("synchronous", "NORMAL")
    }
    val driver = JdbcSqliteDriver(
        url = "jdbc:sqlite:${dbFile.absolutePath}",
        properties = properties,
        schema = schema.synchronous(),
    )
    return SerializedDesktopSqlDriver(driver)
}

/**
 * Pre-release shortcut: if the on-disk revision marker doesn't match [LocalDbRevision],
 * delete the database file so SQLDelight can rebuild it from the current schema. Replace
 * with real migrations once we ship.
 */
private fun wipeIfRevisionChanged(dbFile: File, revFile: File) {
    val onDiskRev = revFile.takeIf { it.exists() }?.runCatching { readText().trim().toLong() }?.getOrNull()
    if (dbFile.exists() && onDiskRev != null && onDiskRev < 6L) {
        dbFile.delete()
        // SQLite may leave auxiliary journal/WAL/SHM files alongside the main db; drop
        // them too so the rebuilt schema doesn't pick up half-written pages.
        File(dbFile.parentFile, "${dbFile.name}-journal").delete()
        File(dbFile.parentFile, "${dbFile.name}-wal").delete()
        File(dbFile.parentFile, "${dbFile.name}-shm").delete()
    }
    revFile.writeText(LocalDbRevision.toString())
}

internal fun desktopDatabaseRoot(): File =
    System.getProperty("phoebe.storage.root")?.let(::File)
        ?: File(System.getProperty("user.home"), desktopDataDirectoryName()).also { it.mkdirs() }

/**
 * SQLDelight's JDBC SQLite driver opens one connection per thread for file-backed databases.
 * Desktop app work hops across coroutine threads, so serialize access to keep SQLite from seeing
 * overlapping connections from the same app process and surfacing SQLITE_BUSY dialogs.
 */
internal class SerializedDesktopSqlDriver(
    private val delegate: SqlDriver,
) : SqlDriver {
    private val lock = ReentrantLock()

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = lock.withLock {
        delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = lock.withLock {
        delegate.execute(identifier, sql, parameters, binders)
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        lock.lock()
        var unlockOnFailure = true
        return try {
            val result = delegate.newTransaction()
            val transaction = result.value
            transaction.afterCommit { lock.unlock() }
            transaction.afterRollback { lock.unlock() }
            unlockOnFailure = false
            result
        } finally {
            if (unlockOnFailure) {
                lock.unlock()
            }
        }
    }

    override fun currentTransaction(): Transacter.Transaction? = delegate.currentTransaction()

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        lock.withLock {
            delegate.addListener(*queryKeys, listener = listener)
        }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        lock.withLock {
            delegate.removeListener(*queryKeys, listener = listener)
        }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        lock.withLock {
            delegate.notifyListeners(*queryKeys)
        }
    }

    override fun close() {
        lock.withLock {
            delegate.close()
        }
    }
}
