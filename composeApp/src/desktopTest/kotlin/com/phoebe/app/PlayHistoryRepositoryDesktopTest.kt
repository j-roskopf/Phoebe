package com.phoebe.app

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.db.SqlDriver
import com.phoebe.app.data.PlayHistoryTopListCapacity
import com.phoebe.app.data.PlayHistoryRepository
import com.phoebe.app.domain.PlayHistoryKind
import com.phoebe.app.domain.Track
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayHistoryRepositoryDesktopTest {
    private var driver: SqlDriver? = null
    private var repository: PlayHistoryRepository? = null

    @After
    fun tearDown() = runBlocking {
        repository?.closeAndJoin()
        repository = null
        driver?.close()
        driver = null
    }

    @Test
    fun recordPlayPersistsRow() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val track = Track("tid", "Song", "Art", "Alb", 30_000L, "", "")
        repo.recordPlay(track, 12345L)
        val rows = db.playHistoryQueries.selectLastPlayedByTrack().awaitAsList()
        assertEquals(1, rows.size)
        assertEquals("tid", rows.single().track_id)
        assertEquals(12345L, rows.single().lastPlayed)
    }

    @Test
    fun playCountsAggregateByTrack() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val track = Track("tid", "Song", "Art", "Alb", 30_000L, "", "")
        repo.recordPlay(track, 1L)
        repo.recordPlay(track, 2L)

        val counts = repo.playCountsByTrack.first { it["tid"] == 2L }
        assertEquals(2L, counts["tid"])
    }

    @Test
    fun importedPlexPlayUpdatesAggregates() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val track = Track("plex:t1", "Song", "Art", "Alb", 30_000L, "", "")

        assertTrue(repo.importPlexPlay(track, "server", "history-1", 12345L, 20000L, 600_000L))

        val counts = repo.playCountsByTrack.first { it["plex:t1"] == 1L }
        val lastPlayed = repo.lastPlayedByTrack.first { it["plex:t1"] == 12345L }
        assertEquals(1L, counts["plex:t1"])
        assertEquals(12345L, lastPlayed["plex:t1"])
        assertEquals(12345L, repo.maxImportedPlexPlayedAt("server"))
    }

    @Test
    fun reimportingSamePlexHistoryKeyDoesNotIncrementCount() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val track = Track("plex:t1", "Song", "Art", "Alb", 30_000L, "", "")

        assertTrue(repo.importPlexPlay(track, "server", "history-1", 12345L, 20000L, 600_000L))
        assertFalse(repo.importPlexPlay(track, "server", "history-1", 12345L, 21000L, 600_000L))

        val counts = repo.playCountsByTrack.first { it["plex:t1"] == 1L }
        assertEquals(1L, counts["plex:t1"])
    }

    @Test
    fun plexImportWithinMergeWindowClaimsLocalPlay() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val track = Track("plex:t1", "Song", "Art", "Alb", 30_000L, "", "")
        repo.recordPlay(track, 1_000L)

        assertTrue(repo.importPlexPlay(track, "server", "history-1", 1_100L, 2_000L, 600_000L))

        val counts = repo.playCountsByTrack.first { it["plex:t1"] == 1L }
        val lastPlayed = repo.lastPlayedByTrack.first { it["plex:t1"] == 1_100L }
        assertEquals(1L, counts["plex:t1"])
        assertEquals(1_100L, lastPlayed["plex:t1"])
    }

    @Test
    fun plexImportOutsideMergeWindowInsertsSeparatePlay() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val track = Track("plex:t1", "Song", "Art", "Alb", 30_000L, "", "")
        repo.recordPlay(track, 1_000L)

        assertTrue(repo.importPlexPlay(track, "server", "history-1", 1_000_000L, 2_000_000L, 600_000L))

        val counts = repo.playCountsByTrack.first { it["plex:t1"] == 2L }
        assertEquals(2L, counts["plex:t1"])
    }

    @Test
    fun remoteImportWithinMergeWindowClaimsLocalPlay() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val track = Track("navidrome:t1", "Song", "Art", "Alb", 30_000L, "", "")
        repo.recordPlay(track, 1_000L)

        assertTrue(
            repo.importRemotePlay(
                track = track,
                source = "navidrome",
                serverId = "server",
                historyKey = "navidrome:server:t1:1100",
                playedAtMs = 1_100L,
                importedAtMs = 2_000L,
            ),
        )

        val counts = repo.playCountsByTrack.first { it["navidrome:t1"] == 1L }
        val lastPlayed = repo.lastPlayedByTrack.first { it["navidrome:t1"] == 1_100L }
        assertEquals(1L, counts["navidrome:t1"])
        assertEquals(1_100L, lastPlayed["navidrome:t1"])
    }

    @Test
    fun remotePlayCountFallbackUsesAggregateInsteadOfSyntheticRows() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val track = Track("plex:t1", "Song", "Art", "Alb", 30_000L, "", "")

        val imported = repo.importRemotePlayCountFallback(
            track = track,
            source = "plex",
            serverId = "server",
            lastPlayedAtMs = 9_000L,
            playCount = 12L,
            importedAtMs = 10_000L,
        )

        assertEquals(1, imported)
        val eventRows = db.playHistoryQueries.selectPlayCountsByTrack().awaitAsList()
        assertEquals(12L, eventRows.single { it.track_id == "plex:t1" }.playCount)
        assertEquals(0, db.playHistoryQueries.selectLatestPlayEventsByTrack().awaitAsList().size)
        val top = repo.topMostPlayed.first { list -> list.any { it.trackId == "plex:t1" } }
        assertEquals(12L, top.first { it.trackId == "plex:t1" }.playCount)
        val recent = repo.topRecentlyPlayed.first { list -> list.any { it.trackId == "plex:t1" } }
        assertEquals("plex:t1", recent.first { it.trackId == "plex:t1" }.trackId)
        assertEquals(9_000L, recent.first { it.trackId == "plex:t1" }.lastPlayedMs)
        val lastPlayed = repo.lastPlayedByTrack.first { it["plex:t1"] == 9_000L }
        assertEquals(9_000L, lastPlayed["plex:t1"])
    }

    @Test
    fun localPlayAfterRemoteAggregateIncrementsDisplayedCount() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val track = Track("plex:t1", "Song", "Art", "Alb", 30_000L, "", "")

        repo.importRemotePlayCountFallback(
            track = track,
            source = "plex",
            serverId = "server",
            lastPlayedAtMs = 9_000L,
            playCount = 12L,
            importedAtMs = 10_000L,
        )
        repo.recordPlay(track, 12_000L)

        val counts = repo.playCountsByTrack.first { it["plex:t1"] == 13L }
        assertEquals(13L, counts["plex:t1"])
        val top = repo.queryRankedEntries(PlayHistoryKind.MostPlayed, limit = 10)
        assertEquals(1, top.totalCount)
        assertEquals(13L, top.mostPlayed.single { it.trackId == "plex:t1" }.playCount)
    }

    @Test
    fun providerClaimedPlayAfterRemoteAggregateStillIncrementsDisplayedCount() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo
        val track = Track("plex:t1", "Song", "Art", "Alb", 30_000L, "", "")

        repo.importRemotePlayCountFallback(
            track = track,
            source = "plex",
            serverId = "server",
            lastPlayedAtMs = 9_000L,
            playCount = 12L,
            importedAtMs = 10_000L,
        )
        repo.recordPlay(track, 12_000L)
        assertTrue(
            repo.importRemotePlay(
                track = track,
                source = "plex",
                serverId = "server",
                historyKey = "history-12",
                playedAtMs = 12_100L,
                importedAtMs = 13_000L,
            ),
        )

        val counts = repo.playCountsByTrack.first { it["plex:t1"] == 13L }
        assertEquals(13L, counts["plex:t1"])
        val top = repo.queryRankedEntries(PlayHistoryKind.MostPlayed, limit = 10)
        assertEquals(13L, top.mostPlayed.single { it.trackId == "plex:t1" }.playCount)
        val recent = repo.queryRankedEntries(PlayHistoryKind.RecentlyPlayed, limit = 10)
        assertEquals(12_100L, recent.recentlyPlayed.single { it.trackId == "plex:t1" }.lastPlayedMs)
    }

    @Test
    fun rankedHistoryQueriesCanReturnMoreThanHomeCapacity() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo

        repeat(PlayHistoryTopListCapacity + 25) { index ->
            repo.recordPlay(
                Track("track-$index", "Song $index", "Art", "Alb", 30_000L, "", ""),
                index.toLong(),
            )
        }

        val mostPlayed = repo.queryRankedEntries(
            PlayHistoryKind.MostPlayed,
            limit = PlayHistoryTopListCapacity + 25,
        )
        val recentlyPlayed = repo.queryRankedEntries(
            PlayHistoryKind.RecentlyPlayed,
            limit = PlayHistoryTopListCapacity + 25,
        )

        assertEquals(PlayHistoryTopListCapacity + 25, mostPlayed.totalCount)
        assertEquals(PlayHistoryTopListCapacity + 25, mostPlayed.mostPlayed.size)
        assertEquals(PlayHistoryTopListCapacity + 25, recentlyPlayed.totalCount)
        assertEquals(PlayHistoryTopListCapacity + 25, recentlyPlayed.recentlyPlayed.size)
    }

    @Test
    fun topArtistsAggregatesBeyondTheTopSongCapacity() = runBlocking {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repo = PlayHistoryRepository(db)
        repository = repo

        val trackCount = PlayHistoryTopListCapacity + 25
        repeat(trackCount) { index ->
            repo.recordPlay(
                Track("deep-$index", "Song $index", "Deep Artist", "Alb", 30_000L, "", ""),
                index.toLong(),
            )
        }

        val artist = repo.topArtists
            .first { rows -> rows.any { it.title == "Deep Artist" } }
            .single { it.title == "Deep Artist" }

        assertEquals(trackCount.toLong(), artist.playCount)
        assertEquals(trackCount, artist.trackCount)
    }
}
