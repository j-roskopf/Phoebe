package com.phoebe.app

import app.cash.sqldelight.db.SqlDriver
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.player.BrowseMediaIds
import com.phoebe.app.player.CatalogBrowseTree
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogBrowseTreeSearchTest {
    private var driver: SqlDriver? = null

    @AfterTest
    fun tearDown() {
        driver?.close()
        driver = null
    }

    @Test
    fun searchTracksRanksSongTitleMatchesFirst() = runTest {
        val db = populatedDatabase()
        val tree = CatalogBrowseTree(db)

        val results = tree.searchTracks(query = "moon")

        assertEquals("track-moon", results.first().id)
    }

    @Test
    fun searchTracksReturnsAlbumQueueForAlbumRequest() = runTest {
        val db = populatedDatabase()
        val tree = CatalogBrowseTree(db)

        val results = tree.searchTracks(query = "quiet hours", album = "Quiet Hours")

        assertEquals(listOf("track-moon", "track-river"), results.map { it.id })
    }

    @Test
    fun searchTracksReturnsRecentTracksForEmptyRequest() = runTest {
        val db = populatedDatabase()
        val tree = CatalogBrowseTree(db)

        val results = tree.searchTracks(query = "")

        assertEquals(listOf("track-river", "track-moon", "track-sun"), results.map { it.id })
    }

    @Test
    fun searchTracksReturnsWholeArtistCatalogForArtistRequest() = runTest {
        val db = populatedDatabase()
        val tree = CatalogBrowseTree(db)

        val results = tree.searchTracks(query = "signal garden", artist = "Signal Garden")

        assertEquals(setOf("track-moon", "track-river"), results.map { it.id }.toSet())
    }

    @Test
    fun searchTracksReturnsOnlyMatchingSongForTitleAndArtistRequest() = runTest {
        val db = populatedDatabase()
        val tree = CatalogBrowseTree(db)

        val results = tree.searchTracks(query = "moon song by signal garden", title = "Moon Song", artist = "Signal Garden")

        assertEquals(listOf("track-moon"), results.map { it.id })
    }

    @Test
    fun searchTracksReturnsPlaylistQueueInOrderForPlaylistRequest() = runTest {
        val db = populatedDatabase()
        val tree = CatalogBrowseTree(db)

        val results = tree.searchTracks(query = "night drive", playlist = "Night Drive")

        assertEquals(listOf("track-river", "track-moon"), results.map { it.id })
    }

    @Test
    fun searchTracksReturnsEmptyWhenNothingMatches() = runTest {
        val db = populatedDatabase()
        val tree = CatalogBrowseTree(db)

        val results = tree.searchTracks(query = "noah kahan", artist = "Noah Kahan")

        assertEquals(emptyList(), results.map { it.id })
    }

    @Test
    fun searchTracksHydratesFullTrackRowsNotJustIndexFields() = runTest {
        val db = populatedDatabase()
        val tree = CatalogBrowseTree(db)

        val track = tree.searchTracks(query = "moon").first()

        assertEquals("https://example.test/track-moon.mp3", track.streamUrl)
        assertEquals(180_000, track.durationMs)
    }

    @Test
    fun playlistChildrenIncludeShuffleThenContextualTrackIds() = runTest {
        val db = populatedDatabase()
        val tree = CatalogBrowseTree(db)

        val children = tree.getChildren(BrowseMediaIds.playlist("playlist-night"))

        assertEquals(BrowseMediaIds.playlistPlay("playlist-night"), children[0].mediaId)
        assertEquals("Play playlist", children[0].title)
        assertEquals(BrowseMediaIds.playlistShuffle("playlist-night"), children[1].mediaId)
        assertEquals("Shuffle", children[1].title)
        assertEquals(
            BrowseMediaIds.track(BrowseMediaIds.playlist("playlist-night"), "track-river"),
            children[2].mediaId,
        )
    }

    @Test
    fun contextualPlaylistTrackResolvesPlaylistQueueAndClickedStartIndex() = runTest {
        val db = populatedDatabase()
        val tree = CatalogBrowseTree(db)
        val clickedMediaId = BrowseMediaIds.track(BrowseMediaIds.playlist("playlist-night"), "track-moon")

        val tracks = tree.tracksForPlayableMediaId(clickedMediaId)
        val startIndex = tree.startIndexForMediaId(clickedMediaId, tracks, fallback = 0)

        assertEquals(listOf("track-river", "track-moon"), tracks.map { it.id })
        assertEquals(1, startIndex)
    }

    private suspend fun populatedDatabase(): PhoebeDatabase {
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver

        db.catalogQueries.upsertArtist("artist-1", "Signal Garden", null, 1, 2, 0, null, null, null, null, null, 0)
        db.catalogQueries.upsertArtist("artist-2", "Solar Choir", null, 1, 1, 1, null, null, null, null, null, 0)
        db.catalogQueries.upsertAlbum("album-quiet", "Quiet Hours", "Signal Garden", 2025, null, 0, null, null, null, null, null, 0)
        db.catalogQueries.upsertAlbum("album-bright", "Moonwink", "Solar Choir", 2024, null, 1, null, null, null, null, null, 0)
        db.catalogQueries.upsertPlaylist("playlist-night", "Night Drive", 2, null, null, 0, null, 0)
        upsertTrack(
            db = db,
            id = "track-moon",
            title = "Moon Song",
            artist = "Signal Garden",
            album = "Quiet Hours",
            dateAddedMs = 200,
            parentAlbumId = "album-quiet",
        )
        upsertTrack(
            db = db,
            id = "track-river",
            title = "River Glass",
            artist = "Signal Garden",
            album = "Quiet Hours",
            dateAddedMs = 300,
            parentAlbumId = "album-quiet",
        )
        upsertTrack(
            db = db,
            id = "track-sun",
            title = "Alphabetical Order",
            artist = "Solar Choir",
            album = "Moonwink",
            dateAddedMs = 100,
            parentAlbumId = "album-bright",
        )
        db.catalogQueries.upsertTrackParent("album-quiet", "track-moon", 0, null)
        db.catalogQueries.upsertTrackParent("album-quiet", "track-river", 1, null)
        db.catalogQueries.upsertTrackParent("album-bright", "track-sun", 0, null)
        db.catalogQueries.upsertTrackParent("playlist-night", "track-river", 0, null)
        db.catalogQueries.upsertTrackParent("playlist-night", "track-moon", 1, null)
        return db
    }

    private suspend fun upsertTrack(
        db: PhoebeDatabase,
        id: String,
        title: String,
        artist: String,
        album: String,
        dateAddedMs: Long,
        parentAlbumId: String,
    ) {
        db.catalogQueries.upsertTrack(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = 180_000,
            streamUrl = "https://example.test/$id.mp3",
            downloadUrl = "https://example.test/$id/download.mp3",
            thumbUrl = null,
            localArtworkUri = null,
            localUri = null,
            year = null,
            genre = null,
            mood = null,
            style = null,
            filepath = null,
            audioCodec = null,
            bitrateKbps = null,
            dateAddedMs = dateAddedMs,
            rating = null,
            parentAlbumId = parentAlbumId,
        )
    }
}
