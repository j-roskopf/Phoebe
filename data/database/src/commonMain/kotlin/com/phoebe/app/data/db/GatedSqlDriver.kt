package com.phoebe.app.data.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.phoebe.app.platform.PhoebeLog

/**
 * Delegating [SqlDriver] that honours [DatabaseWriteGate.seal].
 *
 * The seal lives here, below every repository, rather than in the repositories themselves: only
 * four of the dozen classes that write to this database take the write gate's lock, and a fix that
 * depends on each of them remembering to opt in is a fix that silently rots the next time someone
 * adds a repository.
 *
 * Reads are always delegated — the UI keeps rendering while a sign-out drains, and failing its
 * queries would trade a data bug for a crash.
 */
internal class GatedSqlDriver(
    private val delegate: SqlDriver,
    private val gate: DatabaseWriteGate,
) : SqlDriver {

    /**
     * Dropped writes report "0 rows changed" instead of throwing. Every caller the seal is meant
     * to stop is a cancelled background job whose exceptions nobody is waiting to handle, so
     * throwing here would surface as an unhandled failure in an unrelated coroutine.
     */
    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (gate.writesSealed) {
            PhoebeLog.d("GatedSqlDriver") { "dropped write while sealed: ${sql.summarizeSql()}" }
            return QueryResult.Value(0L)
        }
        return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = delegate.executeQuery(identifier, sql, mapper, parameters, binders)

    override fun newTransaction(): QueryResult<Transacter.Transaction> = delegate.newTransaction()

    override fun currentTransaction(): Transacter.Transaction? = delegate.currentTransaction()

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) =
        delegate.addListener(queryKeys = queryKeys, listener = listener)

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) =
        delegate.removeListener(queryKeys = queryKeys, listener = listener)

    override fun notifyListeners(vararg queryKeys: String) =
        delegate.notifyListeners(queryKeys = queryKeys)

    override fun close() = delegate.close()
}

/** First line only — statement text is logged for diagnosis, not for replay. */
private fun String.summarizeSql(): String =
    trim().lineSequence().firstOrNull()?.take(120).orEmpty()
