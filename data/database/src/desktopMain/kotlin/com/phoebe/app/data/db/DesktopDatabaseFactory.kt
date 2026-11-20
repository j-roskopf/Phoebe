package com.phoebe.app.data.db

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.phoebe.app.platform.desktopDataDirectoryName
import com.phoebe.app.platform.localDatabaseFileName
import com.phoebe.app.platform.localStorageDirectoryName
import java.io.File
import java.sql.DriverManager
import java.util.Properties

actual suspend fun createSqlDriver(schema: SqlSchema<QueryResult.AsyncValue<Unit>>): SqlDriver {
    val root = desktopDatabaseRoot()
    migrateLegacyDesktopStorageIfNeeded(root)
    val dbFileName = localDatabaseFileName()
    val dbFile = File(root, dbFileName)
    checkpointDatabaseIfPresent(dbFile)

    val properties = Properties().apply {
        setProperty("busy_timeout", "10000")
        setProperty("synchronous", "NORMAL")
    }

    val driver = openDriver(dbFile, properties, schema)
    // `journal_mode` is deliberately not a connection property: sqlite-jdbc re-applies
    // every property pragma when it opens each new thread's connection, and
    // `PRAGMA journal_mode=WAL` can fail with SQLITE_BUSY while another connection holds
    // the database. WAL persists in the file header, so set it once here instead.
    driver.execPragma("PRAGMA journal_mode=WAL")
    driver.execPragma("PRAGMA busy_timeout=30000")
    registerDesktopDatabaseShutdownHook(dbFile)
    return driver
}

private fun registerDesktopDatabaseShutdownHook(dbFile: File) {
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching {
                DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                    }
                }
            }
        },
    )
}

private fun openDriver(
    dbFile: File,
    properties: Properties,
    schema: SqlSchema<QueryResult.AsyncValue<Unit>>,
): JdbcSqliteDriver =
    JdbcSqliteDriver(
        url = "jdbc:sqlite:${dbFile.absolutePath}",
        properties = properties,
        schema = schema.synchronous(),
    )

internal fun desktopDatabaseRoot(): File =
    System.getProperty("phoebe.storage.root")?.let(::File)?.also { it.mkdirs() }
        ?: flatpakDesktopDatabaseRoot()
        ?: File(System.getProperty("user.home"), desktopDataDirectoryName()).also { it.mkdirs() }

/**
 * Flatpak sets [HOME] to the host path while mounting it read-only via `--filesystem=home:ro`.
 * Use the per-app [XDG_DATA_HOME] directory for writable SQLite and prefs instead.
 */
internal var flatpakXdgDataHomeOverride: (() -> String?)? = null

internal var flatpakSandboxOverride: (() -> Boolean)? = null

internal fun flatpakDesktopDatabaseRoot(): File? {
    if (!isFlatpakSandbox()) return null
    val dataHome = flatpakXdgDataHomeOverride?.invoke()
        ?: System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        ?: return null
    return File(dataHome, localStorageDirectoryName()).also { it.mkdirs() }
}

private fun checkpointDatabaseIfPresent(dbFile: File) {
    if (!dbFile.isFile) return
    runCatching {
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
        }
    }
}

/**
 * Flatpak sandboxes store data under `~/.var/app/...`, but native/deb/dev builds use
 * `~/.phoebe` on the host. Copy the legacy tree once when the sandbox store is still empty.
 */
private fun migrateLegacyDesktopStorageIfNeeded(targetRoot: File) {
    if (!isFlatpakSandbox()) return
    if (targetRoot.resolve(localDatabaseFileName()).isFile) return
    val legacyRoots = buildList {
        flatpakSandboxHomeLegacyDirectory()?.let(::add)
        hostLegacyDesktopDirectory()?.let(::add)
    }.distinctBy { it.canonicalPath }
    if (legacyRoots.isEmpty()) return
    targetRoot.mkdirs()
    legacyRoots.forEach { legacyRoot ->
        legacyRoot.listFiles()?.forEach { source ->
            val destination = targetRoot.resolve(source.name)
            if (!destination.exists()) {
                runCatching {
                    if (source.isDirectory) {
                        source.copyRecursively(destination, overwrite = false)
                    } else {
                        source.copyTo(destination, overwrite = false)
                    }
                }
            }
        }
    }
}

private fun hostLegacyDesktopDirectory(): File? {
    val userHome = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: return null
    return File(userHome, desktopDataDirectoryName()).takeIf { it.isDirectory }
}

private fun flatpakSandboxHomeLegacyDirectory(): File? {
    val appId = System.getenv("FLATPAK_ID")?.takeIf { it.isNotBlank() } ?: return null
    val userHome = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: return null
    return File(userHome, ".var/app/$appId/${desktopDataDirectoryName()}").takeIf { it.isDirectory }
}

private fun isFlatpakSandbox(): Boolean =
    flatpakSandboxOverride?.invoke() ?: File("/.flatpak-info").exists()
