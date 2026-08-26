package com.phoebe.app.ui

import app.cash.sqldelight.db.SqlDriver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.phoebe.app.data.CatalogRepository
import com.phoebe.app.data.MediaSourcesRepository
import com.phoebe.app.data.PlexClient
import com.phoebe.app.domain.Track
import com.phoebe.app.feature.library.TrackList
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.player.DesktopAudioPlayer
import com.phoebe.app.testCatalogRepository
import com.phoebe.app.testing.PlaybackStartupProbe
import com.phoebe.app.testing.PlaybackStartupThresholds
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlaybackStartupRegressionDesktopTest {
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

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun tappingIndexedLocalSongStartsDesktopAudioWithinThreshold() {
        assumeRealAudioTestsEnabled()

        runDesktopComposeUiTest(width = 800, height = 520) {
            val tracks = runBlocking { indexedFixtureTracks() }
            val target = tracks.firstOrNull { it.localUri?.endsWith("wikimedia-example.wav") == true } ?: tracks.first()
            val diagnostics = PlaybackStartupProbe()
            val player = DesktopAudioPlayer(diagnostics)
            var playRequested = false

            try {
                setContent {
                    PhoebeTheme {
                        Box(Modifier.size(800.dp, 520.dp)) {
                            TrackList(
                                tracks = tracks,
                                empty = "No songs",
                                catalogRefreshing = false,
                                onPlayTracks = { queue, index ->
                                    playRequested = true
                                    diagnostics.markPlayRequested(queue.getOrNull(index)?.title)
                                    player.play(queue, index)
                                },
                                onAddToUpNext = {},
                                onDownload = {},
                            )
                        }
                    }
                }

                onNodeWithTag(PlaybackTestTags.playTrack(target.id)).performClick()

                val firstAudioMs = waitForFirstAudioMs(diagnostics, PlaybackStartupThresholds.DesktopMs)
                val snapshot = diagnostics.snapshot.value
                assertTrue(playRequested, "Expected tapping ${target.title} to request playback")
                assertNotNull(
                    firstAudioMs,
                    "Expected first desktop audio signal; engines=${snapshot.engines} " +
                        "startup=${snapshot.startupEvents} errors=${snapshot.errors}",
                )
                assertTrue(
                    firstAudioMs <= PlaybackStartupThresholds.DesktopMs,
                    "Desktop first audio took ${firstAudioMs}ms, threshold=${PlaybackStartupThresholds.DesktopMs}ms, " +
                        "engines=${snapshot.engines} startup=${snapshot.startupEvents}",
                )
            } finally {
                player.releaseForTests()
            }
        }
    }

    private suspend fun indexedFixtureTracks(): List<Track> {
        val fixtureFolder = fixtureFolder("wikimedia-example.mp3")
        val (db, sqlDriver) = newInMemoryPhoebeDatabase()
        driver = sqlDriver
        val http = testHttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        val storage = PlatformStorage()
        val mediaSources = MediaSourcesRepository(db, storage)
        val catalog = testCatalogRepository(
            plexClient = PlexClient.withoutResolver(http),
            database = db,
            storage = storage,
            httpClient = http,
            mediaSourcesRepository = mediaSources,
        )
        mediaSources.addLocalFolder(fixtureFolder.toURI().toString(), "Playback Fixtures")
        catalog.refreshAggregated(session = null)
        return catalog.catalog.value.tracksByParent.values.flatten().also { tracks ->
            assertTrue(tracks.isNotEmpty(), "Expected local fixture folder to index audio tracks")
        }
    }

    private fun fixtureFolder(name: String): File {
        val url = javaClass.classLoader.getResource("test-audio/$name")
        assertNotNull(url, "Missing test audio fixture: $name. Run ./scripts/fetch-test-audio.sh")
        return File(url.toURI()).parentFile
    }

    private fun assumeRealAudioTestsEnabled() {
        assumeTrue("Real audio playback regression tests are disabled", System.getProperty("phoebe.realAudioTests").toBoolean())
    }
}

private fun waitForFirstAudioMs(
    diagnostics: PlaybackStartupProbe,
    timeoutMs: Long,
): Long? {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < deadline) {
        diagnostics.snapshot.value.firstAudioMs?.let { return it }
        Thread.sleep(50L)
    }
    return diagnostics.snapshot.value.firstAudioMs
}
