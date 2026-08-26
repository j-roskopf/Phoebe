package com.phoebe.app

import app.cash.sqldelight.db.SqlDriver
import com.phoebe.app.data.CatalogRepository
import com.phoebe.app.data.MediaSourcesRepository
import com.phoebe.app.data.PlexClient
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.catalogPrefix
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderArtistDetailPreloadDesktopTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var driver: SqlDriver? = null

    @After
    fun cleanup() {
        driver?.close()
        driver = null
        System.clearProperty("phoebe.storage.root")
    }

    @Test
    fun plexArtistPreloadHelpersDoNotFetchForOtherProviders() = runTest {
        System.setProperty("phoebe.storage.root", temp.newFolder("kv").absolutePath)
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        var requestCount = 0
        val engine = MockEngine {
            requestCount++
            respond("", HttpStatusCode.InternalServerError)
        }
        val http = testHttpClient(engine)
        val repo = testCatalogRepository(
            plexClient = PlexClient.withoutResolver(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = MediaSourcesRepository(db, PlatformStorage()),
        )

        listOf(MediaProviderType.Jellyfin, MediaProviderType.Emby, MediaProviderType.Navidrome).forEach { provider ->
            val prefix = provider.catalogPrefix
            val session = PlexSession(
                token = "token",
                userName = "$prefix listener",
                userId = "user-1",
                providerType = provider,
                selectedServer = PlexServer("$prefix:test", provider.name, "https://$prefix.example", owned = true),
                selectedLibrary = MusicLibrary("music", "Music"),
            )
            val artist = Artist(id = "$prefix:artist-1", title = "Artist One", albumCount = 1)

            assertTrue(repo.ensurePopularTracksForArtist(session, artist).isEmpty())
            assertTrue(repo.ensureSimilarArtistsForArtist(session, artist).isEmpty())
        }

        assertEquals(0, requestCount)
        assertTrue(repo.catalog.value.popularTracksByArtist.isEmpty())
        assertTrue(repo.catalog.value.similarArtistsByArtist.isEmpty())
    }

    @Test
    fun plexSimilarArtistPreloadRequestsExpandedLimit() = runTest {
        System.setProperty("phoebe.storage.root", temp.newFolder("kv").absolutePath)
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        var requestedCount: String? = null
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/library/metadata/artist-1/similar" -> {
                    requestedCount = request.url.parameters["count"]
                    respond(
                        """{ "MediaContainer": { "Metadata": [] } }""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val http = testHttpClient(engine)
        val repo = testCatalogRepository(
            plexClient = PlexClient.withoutResolver(http),
            database = db,
            storage = PlatformStorage(),
            httpClient = http,
            mediaSourcesRepository = MediaSourcesRepository(db, PlatformStorage()),
        )
        val session = PlexSession(
            token = "token",
            providerType = MediaProviderType.Plex,
            selectedServer = PlexServer("plex:test", "Plex", "https://plex.example", owned = true),
            selectedLibrary = MusicLibrary("music", "Music"),
        )

        val artists = repo.ensureSimilarArtistsForArtist(
            session = session,
            artist = Artist(id = "plex:artist-1", title = "Artist One", albumCount = 1),
        )

        assertTrue(artists.isEmpty())
        assertEquals("20", requestedCount)
    }
}
