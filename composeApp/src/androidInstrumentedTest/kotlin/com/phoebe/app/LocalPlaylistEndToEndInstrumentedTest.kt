package com.phoebe.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phoebe.app.data.MediaSourcesRepository
import com.phoebe.app.data.PlexClient
import com.phoebe.app.domain.isLocalPlaylist
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.playlists.PlaylistExportFormat
import com.phoebe.app.playlists.PlaylistExporter
import com.phoebe.app.testing.minimalMp3Bytes
import com.phoebe.app.testing.newAndroidTestPhoebeDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LocalPlaylistEndToEndInstrumentedTest {

    private lateinit var app: Application
    private lateinit var storageOverride: File
    private var dbName: String? = null
    private var musicRoot: File? = null
    private var driver: app.cash.sqldelight.driver.android.AndroidSqliteDriver? = null

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        AndroidContextHolder.application = app
        storageOverride = File(app.cacheDir, "phoebe-playlist-storage-${System.nanoTime()}").apply { mkdirs() }
        musicRoot = File(app.cacheDir, "phoebe-playlist-mp3s-${System.nanoTime()}").apply { mkdirs() }
        System.setProperty("phoebe.storage.root", storageOverride.absolutePath)
    }

    @After
    fun tearDown() {
        driver?.close()
        driver = null
        dbName?.let { app.deleteDatabase(it) }
        dbName = null
        musicRoot?.deleteRecursively()
        storageOverride.deleteRecursively()
        System.clearProperty("phoebe.storage.root")
    }

    @Test
    fun createAddPersistAndExportLocalPlaylist() = runBlocking {
        val music = checkNotNull(musicRoot)
        File(music, "alpha.mp3").writeBytes(minimalMp3Bytes())
        File(music, "beta.mp3").writeBytes(minimalMp3Bytes())

        val testDb = newAndroidTestPhoebeDatabase(app)
        driver = testDb.driver
        dbName = testDb.sqliteName
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        val storage = PlatformStorage()
        val mediaSources = MediaSourcesRepository(testDb.database, storage)
        val catalog = testCatalogRepository(
            plexClient = PlexClient.withoutResolver(http),
            database = testDb.database,
            storage = storage,
            httpClient = http,
            mediaSourcesRepository = mediaSources,
        )

        mediaSources.addLocalFolder(music.toURI().toString(), "Android Playlist MP3s")
        catalog.refreshAggregated(session = null)
        val tracks = catalog.catalog.value.tracksByParent.values.flatten().sortedBy { it.title }
        val alpha = tracks.first { it.title == "alpha" }

        val created = catalog.createLocalPlaylist("Android Mix", listOf(alpha))
        assertNotNull(created)
        assertTrue(created.isLocalPlaylist())

        val beta = tracks.first { it.title == "beta" }
        catalog.addTracksToPlaylist(null, created, listOf(beta))
        catalog.refreshAggregated(session = null)

        val playlist = catalog.catalog.value.playlists.single { it.title == "Android Mix" }
        assertEquals(2, playlist.trackCount)
        val playlistTracks = catalog.tracksForPlaylist(null, playlist)
        assertEquals(listOf("beta", "alpha"), playlistTracks.map { it.title })

        val m3u8 = PlaylistExporter.export(playlistTracks, PlaylistExportFormat.M3U8)
        assertTrue(m3u8.startsWith("#EXTM3U"))
        assertTrue(m3u8.contains("alpha.mp3"))

        val text = PlaylistExporter.export(playlistTracks, PlaylistExportFormat.Text)
        assertEquals(2, text.lines().size)

        val csv = PlaylistExporter.export(playlistTracks, PlaylistExportFormat.Csv)
        assertTrue(csv.startsWith("title,artist,album,duration_ms,path"))
        assertTrue(csv.contains("beta"))
    }
}
