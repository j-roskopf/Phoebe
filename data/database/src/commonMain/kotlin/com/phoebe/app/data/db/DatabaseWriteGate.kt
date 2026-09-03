package com.phoebe.app.data.db

import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes SQLite writes across repositories that share one `PhoebeDatabase` connection, and can
 * seal the connection so no write reaches disk at all.
 *
 * [withWrite] is advisory: it only orders callers that remember to ask for it, and most
 * repositories never do. [seal] is not advisory — it is enforced under every repository by
 * [GatedSqlDriver], so a straggler from a job that was cancelled but not yet joined cannot write
 * behind a sign-out wipe and resurrect the previous account's rows.
 */
class DatabaseWriteGate {
    private val mutex = Mutex()

    @Volatile
    private var sealed = false

    /** True while writes are being dropped. Reads are unaffected. */
    val writesSealed: Boolean get() = sealed

    suspend fun <T> withWrite(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Drop every write issued through the gated driver until [unseal].
     *
     * Held for the whole sign-out drain rather than just the delete itself: cancellation is
     * cooperative, so a sync coroutine can still be sitting between two DB calls when the wipe
     * runs. Sealing first makes that harmless instead of a race.
     */
    fun seal() {
        sealed = true
    }

    /**
     * Allow writes again once sign-out has drained. The app stays usable while signed out — the
     * welcome screen can add local folders and save radio stations — so the seal is scoped to the
     * sign-out window, not to the whole signed-out state.
     */
    fun unseal() {
        sealed = false
    }
}
