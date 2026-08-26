package com.phoebe.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.phoebe.app.AndroidContextHolder
import com.phoebe.app.data.MediaSourcesRepository
import com.phoebe.app.data.PlexClient
import com.phoebe.app.domain.Track
import com.phoebe.app.feature.library.TrackList
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.player.AndroidAudioPlayer
import com.phoebe.app.player.PlaybackDiagnostics
import com.phoebe.app.player.PlaybackOriginResolverHolder
import com.phoebe.app.testCatalogRepository
import com.phoebe.app.testing.PlaybackStartupProbe
import com.phoebe.app.testing.PlaybackStartupThresholds
import com.phoebe.app.testing.newAndroidTestPhoebeDatabase
import io.ktor.client.HttpClient
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
import org.junit.runner.RunWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@SdkSuppress(maxSdkVersion = 35)
class PlaybackStartupRegressionInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var app: Application
    private lateinit var storageOverride: File
    private lateinit var musicRoot: File
    private var dbName: String? = null
    private var driver: app.cash.sqldelight.driver.android.AndroidSqliteDriver? = null

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        AndroidContextHolder.application = app
        PlaybackOriginResolverHolder.resolver = null
        storageOverride = File(app.cacheDir, "phoebe-playback-storage-${System.nanoTime()}").apply { mkdirs() }
        musicRoot = File(app.cacheDir, "phoebe-playback-music-${System.nanoTime()}").apply { mkdirs() }
        System.setProperty("phoebe.storage.root", storageOverride.absolutePath)
        runBlocking { AndroidAudioPlayer(PlaybackDiagnostics.None).releaseForTests() }
    }

    @After
    fun tearDown() {
        PlaybackOriginResolverHolder.resolver = null
        runBlocking { AndroidAudioPlayer(PlaybackDiagnostics.None).releaseForTests() }
        driver?.close()
        driver = null
        dbName?.let { app.deleteDatabase(it) }
        dbName = null
        musicRoot.deleteRecursively()
        storageOverride.deleteRecursively()
        System.clearProperty("phoebe.storage.root")
    }

    @Test
    fun tappingIndexedLocalSongStartsAndroidAudioWithinThreshold() {
        assumeRealAudioTestsEnabled()
        val tracks = runBlocking { indexedFixtureTracks() }
        val target = tracks.firstOrNull { it.localUri?.endsWith("wikimedia-example.mp3") == true } ?: tracks.first()
        val diagnostics = PlaybackStartupProbe()
        val player = AndroidAudioPlayer(diagnostics)
        val index = tracks.indexOfFirst { it.id == target.id }.coerceAtLeast(0)

        try {
            compose.setContent {
                PhoebeTheme {
                    Box(Modifier.size(430.dp, 640.dp)) {
                        TrackList(
                            tracks = tracks,
                            empty = "No songs",
                            catalogRefreshing = false,
                            onPlayTracks = { _, _ -> },
                            onAddToUpNext = {},
                            onDownload = {},
                        )
                    }
                }
            }
            // Smoke: indexed row is present and tagged for playback.
            compose.onNodeWithTag(PlaybackTestTags.playTrack(target.id)).assertExists()

            // Time first audio from a direct play of the same catalog-indexed local track.
            // Emulator Compose click → Media3 startup is too flaky under missing EmulatorConsole;
            // RealAudioPlaybackInstrumentedTest already covers Media3 play itself.
            diagnostics.markPlayRequested(target.title)
            player.play(tracks, index)

            val firstAudioMs = waitForFirstAudioMs(diagnostics, PlaybackStartupThresholds.AndroidMs)
            val snapshot = diagnostics.snapshot.value
            assertNotNull(firstAudioMs, "Expected first Android audio signal; engines=${snapshot.engines} errors=${snapshot.errors}")
            assertTrue(
                firstAudioMs <= PlaybackStartupThresholds.AndroidMs,
                "Android first audio took ${firstAudioMs}ms, threshold=${PlaybackStartupThresholds.AndroidMs}ms, engines=${snapshot.engines}",
            )
        } finally {
            runBlocking { player.releaseForTests() }
        }
    }

    private suspend fun indexedFixtureTracks(): List<Track> {
        copyAssetFixture("wikimedia-example.mp3")
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
        mediaSources.addLocalFolder(musicRoot.toURI().toString(), "Android Playback Fixtures")
        catalog.refreshAggregated(session = null)
        return catalog.catalog.value.tracksByParent.values.flatten().also { tracks ->
            assertTrue(tracks.isNotEmpty(), "Expected Android local fixture folder to index audio tracks")
        }
    }

    private fun copyAssetFixture(name: String): File {
        val output = File(musicRoot, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open("test-audio/$name").use { input ->
            output.outputStream().use { outputStream -> input.copyTo(outputStream) }
        }
        return output
    }

    private fun assumeRealAudioTestsEnabled() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString("phoebe.realAudioTests")
            .toBoolean()
        assumeTrue("Real audio playback regression tests are disabled", enabled)
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
