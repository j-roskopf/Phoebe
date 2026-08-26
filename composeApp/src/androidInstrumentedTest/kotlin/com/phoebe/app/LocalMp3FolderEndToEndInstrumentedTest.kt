package com.phoebe.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phoebe.app.data.MediaSourcesRepository
import com.phoebe.app.data.PlexClient
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.player.SimpleAudioPlayer
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
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LocalMp3FolderEndToEndInstrumentedTest {

    private lateinit var app: Application
    private lateinit var storageOverride: File
    private var dbName: String? = null
    private var musicRoot: File? = null
    private var driver: app.cash.sqldelight.driver.android.AndroidSqliteDriver? = null

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        AndroidContextHolder.application = app
        storageOverride = File(app.cacheDir, "phoebe-test-storage-${System.nanoTime()}").apply { mkdirs() }
        musicRoot = File(app.cacheDir, "phoebe-test-mp3s-${System.nanoTime()}").apply { mkdirs() }
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
    fun addingFolderWithMp3FilesRefreshesLocalCatalog() = runBlocking {
        val music = checkNotNull(musicRoot)
        File(music, "alpha.mp3").writeMinimalMp3Bytes()
        File(music, "beta.mp3").writeMinimalMp3Bytes()
        File(music, "notes.txt").writeText("not audio")

        val testDb = newAndroidTestPhoebeDatabase(app)
        driver = testDb.driver
        dbName = testDb.sqliteName
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        val mediaSources = MediaSourcesRepository(testDb.database, PlatformStorage())
        val catalog = testCatalogRepository(
            plexClient = PlexClient.withoutResolver(http),
            database = testDb.database,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = mediaSources,
        )

        mediaSources.addLocalFolder(music.toURI().toString(), "Android MP3s")
        catalog.refreshAggregated(session = null)

        val tracks = catalog.catalog.value.tracksByParent.values.flatten()
        assertEquals(listOf("alpha", "beta"), tracks.map { it.title }.sorted())
        assertTrue(tracks.all { it.localUri?.endsWith(".mp3") == true })
        assertEquals(listOf("Android MP3s"), mediaSources.state.value.localFolders.map { it.label })
    }

    @Test
    fun localTrackFromCatalogPlaysUsingFileUri() = runBlocking {
        val music = checkNotNull(musicRoot)
        val mp3 = File(music, "alpha.mp3")
        mp3.writeBytes(minimalMp3Bytes())

        val testDb = newAndroidTestPhoebeDatabase(app)
        driver = testDb.driver
        dbName = testDb.sqliteName
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        val mediaSources = MediaSourcesRepository(testDb.database, PlatformStorage())
        val catalog = testCatalogRepository(
            plexClient = PlexClient.withoutResolver(http),
            database = testDb.database,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = mediaSources,
        )

        mediaSources.addLocalFolder(music.toURI().toString(), "Android playback")
        catalog.refreshAggregated(session = null)

        val track = catalog.catalog.value.tracksByParent.values.flatten().single { it.title == "alpha" }
        val player = RecordingAudioPlayer()
        player.play(listOf(track), 0)

        assertEquals(track.localUri, player.lastUri)
        assertTrue(player.state.value.isPlaying)
    }
}

private fun File.writeMinimalMp3Bytes() {
    writeBytes(minimalMp3Bytes())
}

private class RecordingAudioPlayer : SimpleAudioPlayer() {
    var lastUri: String? = null

    override fun playUri(uri: String) {
        lastUri = uri
        markPlaybackReady()
    }
}
