package com.phoebe.app

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.phoebe.app.data.db.clearAllAppData
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseWiperDesktopTest {
    @Test
    fun clearAllAppDataDeletesEveryPersistedTable() = runBlocking {
        val (database, driver) = newInMemoryPhoebeDatabase()
        try {
            database.transaction {
                database.sessionQueries.upsert(
                    token = "token",
                    userName = "Listener",
                    providerType = "Plex",
                    userId = null,
                    selectedServerId = null,
                    selectedServerName = null,
                    selectedServerUri = null,
                    selectedServerOwned = null,
                    selectedServerConnectionUris = null,
                    selectedServerAdvertisedConnectionUris = null,
                    selectedServerLocalConnectionUris = null,
                    selectedServerAccessToken = null,
                    selectedServerHttpsRequired = null,
                    selectedLibraryKey = null,
                    selectedLibraryTitle = null,
                    jellyfinSyncMode = "Quick",
                )
                database.mediaSourcesQueries.insertOrReplace("lf1", "file:///music", "Music", 1L)
                database.libraryPrefsQueries.upsert(
                    sortBy = "Name",
                    ascending = 1L,
                    colYear = 1L,
                    colGenre = 1L,
                    colFilepath = 1L,
                    colAudioCodec = 1L,
                    colBitrate = 1L,
                    colDuration = 1L,
                    colSampleRate = 1L,
                    colFileType = 1L,
                    colDateAdded = 1L,
                    colRating = 1L,
                    colFavorite = 1L,
                    homeSections = "Mixes,Played,FavoritePlaylists,FavoriteArtists,FavoriteAlbums,RecentSongs,RecentArtists,RecentAlbums,Collections,Random",
                    mobileBottomTabs = "Home,Search,Library,Playlists,Radio",
                    personalMix = "{\"limit\":50,\"heavyRotationWeight\":25,\"recentWeight\":30,\"mostPlayedWeight\":25,\"similarWeight\":15,\"discoveryWeight\":5}",
                    gridColumns = 3L,
                    albumGridItemSizeDp = 160L,
                    artistGridItemSizeDp = 112L,
                )
                database.lyricsQueries.upsertLyrics("track", "Cache", null, "lyrics", 0L)
                database.downloadsQueries.upsert("track", "Song", "Artist", "Complete", 1.0, "file:///song.mp3", "", "", 0L, null, 0L, null, null)
                database.playHistoryQueries.recordPlay("track", "Artist", "Album", 123L)

                database.catalogQueries.upsertArtist("artist", "Artist", null, 1L, 1L, 0L, null, null, null, null, null, 0L)
                database.catalogQueries.upsertAlbum("album", "Album", "Artist", 2024L, null, 0L, null, null, null, null, null, 0L)
                database.catalogQueries.upsertPlaylist("playlist", "Playlist", 1L, "/playlists/1/items", null, 0L, null, 0L)
                database.catalogQueries.upsertTrack(
                    id = "track",
                    title = "Song",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 1_000L,
                    streamUrl = "https://example.test/stream",
                    downloadUrl = "https://example.test/download",
                    thumbUrl = null,
                    localArtworkUri = null,
                    localUri = null,
                    year = 2024L,
                    genre = null,
                    mood = null,
                    style = null,
                    filepath = null,
                    audioCodec = null,
                    bitrateKbps = null,
                    dateAddedMs = null,
                    rating = null,
                    parentAlbumId = "album",
                )
                database.catalogQueries.upsertTrackParent("album", "track", 0L, null)
                database.catalogQueries.upsertLibraryPopularTrack("plex:server:library", "track", 0L, 1L)
                database.catalogQueries.upsertCollectionValue("Albums", "Genre", "Rock", "genre=1", null, null, 1L)
                database.catalogQueries.upsertCollectionTag("Albums", "Genre", "album", "Rock")
                database.catalogQueries.upsertCollectionValueLoad("Albums", "Genre")
                database.catalogQueries.upsertLocalFileMetadataCache(
                    folderId = "lf1",
                    uri = "file:///music/song.mp3",
                    sizeBytes = 1L,
                    modifiedAtMs = 2L,
                    trackId = "track",
                    albumId = "album",
                    title = "Song",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 1_000L,
                    year = null,
                    genre = null,
                    mood = null,
                    style = null,
                    bitrateKbps = null,
                    audioCodec = null,
                    filepath = null,
                    localArtworkUri = null,
                    dateAddedMs = 3L,
                )
            }

            database.clearAllAppData()

            assertNull(database.sessionQueries.selectCurrent().awaitAsOneOrNull())
            assertTrue(database.mediaSourcesQueries.selectAll().awaitAsList().isEmpty())
            assertNull(database.libraryPrefsQueries.selectCurrent().awaitAsOneOrNull())
            assertNull(database.lyricsQueries.selectLyrics("track").awaitAsOneOrNull())
            assertTrue(database.downloadsQueries.selectAll().awaitAsList().isEmpty())
            assertTrue(database.playHistoryQueries.selectPlayCountsByTrack().awaitAsList().isEmpty())
            assertTrue(database.catalogQueries.selectArtists().awaitAsList().isEmpty())
            assertTrue(database.catalogQueries.selectAlbums().awaitAsList().isEmpty())
            assertTrue(database.catalogQueries.selectPlaylists().awaitAsList().isEmpty())
            assertTrue(database.catalogQueries.selectAllTracks().awaitAsList().isEmpty())
            assertTrue(database.catalogQueries.selectTrackParents().awaitAsList().isEmpty())
            assertTrue(database.catalogQueries.selectLibraryPopularTracks().awaitAsList().isEmpty())
            assertTrue(database.catalogQueries.selectCollectionValues().awaitAsList().isEmpty())
            assertTrue(database.catalogQueries.selectCollectionTags().awaitAsList().isEmpty())
            assertTrue(database.catalogQueries.selectCollectionValueLoads().awaitAsList().isEmpty())
            assertEquals(
                emptyList(),
                database.catalogQueries.selectLocalFileMetadataCacheForFolder("lf1").awaitAsList(),
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun clearAllAppDataCanPreservePlayHistoryForSignOut() = runBlocking {
        val (database, driver) = newInMemoryPhoebeDatabase()
        try {
            database.transaction {
                database.sessionQueries.upsert(
                    token = "token",
                    userName = "Listener",
                    providerType = "Navidrome",
                    userId = null,
                    selectedServerId = null,
                    selectedServerName = null,
                    selectedServerUri = null,
                    selectedServerOwned = null,
                    selectedServerConnectionUris = null,
                    selectedServerAdvertisedConnectionUris = null,
                    selectedServerLocalConnectionUris = null,
                    selectedServerAccessToken = null,
                    selectedServerHttpsRequired = null,
                    selectedLibraryKey = null,
                    selectedLibraryTitle = null,
                    jellyfinSyncMode = "Quick",
                )
                database.playHistoryQueries.recordPlay("navidrome:track", "Artist", "Album", 123L)
            }

            database.clearAllAppData(clearPlayHistory = false)

            assertNull(database.sessionQueries.selectCurrent().awaitAsOneOrNull())
            assertEquals(1L, database.playHistoryQueries.selectPlayCountsByTrack().awaitAsList().single().playCount)
        } finally {
            driver.close()
        }
    }
}
