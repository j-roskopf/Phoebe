package com.phoebe.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phoebe.app.data.CatalogRepository
import com.phoebe.app.data.MediaSourcesRepository
import com.phoebe.app.data.PlexClient
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.testing.newAndroidTestPhoebeDatabase
import com.phoebe.app.testing.plexCatalogMockEngine
import com.phoebe.app.testing.testHttpClient
import com.phoebe.app.testing.testPlexSession
import io.ktor.client.HttpClient
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
class PlexPlaylistEndToEndInstrumentedTest {

    private lateinit var app: Application
    private lateinit var storageOverride: File
    private var dbName: String? = null
    private var driver: app.cash.sqldelight.driver.android.AndroidSqliteDriver? = null

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        AndroidContextHolder.application = app
        storageOverride = File(app.cacheDir, "phoebe-test-storage-${System.nanoTime()}").apply { mkdirs() }
        System.setProperty("phoebe.storage.root", storageOverride.absolutePath)
    }

    @After
    fun tearDown() {
        driver?.close()
        driver = null
        dbName?.let { app.deleteDatabase(it) }
        dbName = null
        storageOverride.deleteRecursively()
        System.clearProperty("phoebe.storage.root")
    }

    @Test
    fun createPlaylistSeedsMockPlexAndUpdatesCatalog() = runBlocking {
        val session = testPlexSession()
        val testDb = newAndroidTestPhoebeDatabase(app)
        driver = testDb.driver
        dbName = testDb.sqliteName
        val http = testHttpClient(plexCatalogMockEngine())
        val repo = catalogRepository(testDb.database, http)

        repo.refreshAggregated(session)
        val album = repo.catalog.value.albums.single { it.id == "plex:a1" }
        val seed = repo.tracksForAlbum(session, album).single()
        val created = repo.createPlaylist(session, "New Mix", listOf(seed))

        assertNotNull(created)
        assertEquals("plex:p99", created.id)
        assertEquals("New Mix", created.title)
        assertTrue(repo.catalog.value.playlists.any { it.id == "plex:p99" })
    }

    @Test
    fun addTracksToPlaylistRefetchesAndSyncsToMockPlex() = runBlocking {
        var plexAddCalled = false
        val session = testPlexSession()
        val testDb = newAndroidTestPhoebeDatabase(app)
        driver = testDb.driver
        dbName = testDb.sqliteName
        val http = testHttpClient(plexCatalogMockEngine(onPlaylistAdd = { plexAddCalled = true }))
        val repo = catalogRepository(testDb.database, http)

        repo.refreshAggregated(session)
        val playlist = repo.catalog.value.playlists.single { it.id == "plex:p1" }
        val newTrack = Track(
            id = "plex:t3",
            title = "Added Song",
            artist = "Artist One",
            album = "Album One",
            durationMs = 2_000,
            streamUrl = "https://plex.example/t3?X-Plex-Token=token",
            downloadUrl = "https://plex.example/t3?X-Plex-Token=token&download=1",
        )
        repo.addTracksToPlaylist(session, playlist, listOf(newTrack))

        assertTrue(plexAddCalled)
        assertEquals(3, repo.catalog.value.playlists.single { it.id == playlist.id }.trackCount)
        assertEquals(
            listOf("plex:t3", "plex:t1", "plex:t2"),
            repo.catalog.value.tracksByParent[playlist.id].orEmpty().map { it.id },
        )
    }

    private fun catalogRepository(db: com.phoebe.app.db.PhoebeDatabase, http: HttpClient): CatalogRepository {
        val mediaSources = MediaSourcesRepository(db, PlatformStorage())
        return testCatalogRepository(
            plexClient = PlexClient.withoutResolver(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = mediaSources,
        )
    }
}
