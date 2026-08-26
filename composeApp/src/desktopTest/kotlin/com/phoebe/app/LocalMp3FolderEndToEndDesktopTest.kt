package com.phoebe.app

import app.cash.sqldelight.db.SqlDriver
import com.phoebe.app.data.CatalogRepository
import com.phoebe.app.data.MediaSourcesRepository
import com.phoebe.app.data.PlexClient
import com.phoebe.app.player.SimpleAudioPlayer
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.testing.minimalMp3Bytes
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalMp3FolderEndToEndDesktopTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var driver: SqlDriver? = null

    @Before
    fun storageRoot() {
        System.setProperty("phoebe.storage.root", temp.newFolder("kv").absolutePath)
    }

    @After
    fun cleanup() {
        driver?.close()
        driver = null
        System.clearProperty("phoebe.storage.root")
    }

    @Test
    fun addingFolderWithMp3FilesRefreshesLocalCatalog() = runTest {
        val music = temp.newFolder("music")
        File(music, "alpha.mp3").writeMinimalMp3Bytes()
        File(music, "beta.mp3").writeMinimalMp3Bytes()
        File(music, "notes.txt").writeText("not audio")

        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        val mediaSources = MediaSourcesRepository(db, PlatformStorage())
        val catalog = testCatalogRepository(
            plexClient = PlexClient.withoutResolver(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = mediaSources,
        )

        mediaSources.addLocalFolder(music.toURI().toString(), "Test MP3s")
        catalog.refreshAggregated(session = null)

        val tracks = catalog.catalog.value.tracksByParent.values.flatten()
        assertEquals(listOf("alpha", "beta"), tracks.map { it.title }.sorted())
        assertTrue(tracks.all { it.localUri?.endsWith(".mp3") == true })
        assertEquals(listOf("Test MP3s"), mediaSources.state.value.localFolders.map { it.label })
    }

    @Test
    fun localTrackFromCatalogPlaysUsingFileUri() = runTest {
        val music = temp.newFolder("music")
        val mp3 = File(music, "alpha.mp3")
        mp3.writeBytes(minimalMp3Bytes())

        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        val mediaSources = MediaSourcesRepository(db, PlatformStorage())
        val catalog = testCatalogRepository(
            plexClient = PlexClient.withoutResolver(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = mediaSources,
        )
        mediaSources.addLocalFolder(music.toURI().toString(), "Playback MP3s")
        catalog.refreshAggregated(session = null)

        val track = catalog.catalog.value.tracksByParent.values.flatten().single { it.title == "alpha" }
        val player = RecordingAudioPlayer()
        player.play(listOf(track), 0)

        assertEquals(track.localUri, player.lastUri)
        assertTrue(player.state.value.isPlaying)
    }

    @Test
    fun repeatedRefreshKeepsLocalTrackIdentityAndFirstSeenDates() = runTest {
        val music = temp.newFolder("music")
        val alpha = File(music, "alpha.mp3")
        val beta = File(music, "beta.mp3")
        alpha.writeMinimalMp3Bytes()
        beta.writeMinimalMp3Bytes()
        File(music, "notes.txt").writeText("not audio")

        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        val mediaSources = MediaSourcesRepository(db, PlatformStorage())
        val catalog = testCatalogRepository(
            plexClient = PlexClient.withoutResolver(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = mediaSources,
        )
        mediaSources.addLocalFolder(music.toURI().toString(), "Stable MP3s")

        catalog.refreshAggregated(session = null)
        val first = catalog.catalog.value.tracksByParent.values.flatten()
            .associateBy { it.localUri.orEmpty() }

        alpha.setLastModified(alpha.lastModified() + 5_000L)
        catalog.refreshAggregated(session = null)
        val second = catalog.catalog.value.tracksByParent.values.flatten()
            .associateBy { it.localUri.orEmpty() }

        assertEquals(first.keys, second.keys)
        assertEquals(first[alpha.toURI().toString()]?.id, second[alpha.toURI().toString()]?.id)
        assertEquals(first[alpha.toURI().toString()]?.dateAddedMs, second[alpha.toURI().toString()]?.dateAddedMs)
        assertEquals(first[beta.toURI().toString()]?.dateAddedMs, second[beta.toURI().toString()]?.dateAddedMs)
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
