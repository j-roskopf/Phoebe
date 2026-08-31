package com.phoebe.app.platform

/** SQLite file name for the on-device catalog database. */
fun localDatabaseFileName(): String =
    if (isDebugBuild()) "phoebe-debug.db" else "phoebe.db"

/** Stable key prefix for web schema-version metadata and legacy web database lookup. */
fun localDatabaseRevisionKey(): String =
    if (isDebugBuild()) "phoebe-debug.db.revision" else "phoebe.db.revision"

/** Relative directory under the platform storage root (filesDir, Documents, ~/.phoebe). */
fun localStorageDirectoryName(): String =
    if (isDebugBuild()) "phoebe-debug" else "phoebe"

/** Desktop data directory under the user home folder when no override is set. */
fun desktopDataDirectoryName(): String =
    if (isDebugBuild()) ".phoebe-debug" else ".phoebe"

/**
 * Bumped when a release changes the shape of stored data so much that the old rows cannot be
 * migrated meaningfully. On a mismatch the app signs out once and resyncs from the server.
 *
 * 2 — Plex media URLs became relative part keys bound to the live origin at request time.
 *     Catalogs written before this hold absolute URLs stamped with whatever address happened to
 *     be reachable when they were fetched, plus per-network origin rows keyed the old way.
 */
const val PhoebeAppDataRevision = 2

/** Storage key holding the [PhoebeAppDataRevision] this install's data was written with. */
fun appDataRevisionStorageKey(): String =
    if (isDebugBuild()) "phoebe-debug.app_data_revision" else "phoebe.app_data_revision"
