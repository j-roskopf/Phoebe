package com.phoebe.app.testing

import com.phoebe.app.data.EmbyClient
import com.phoebe.app.data.EmbyProviderAdapter
import com.phoebe.app.data.JellyfinClient
import com.phoebe.app.data.JellyfinPlaybackEvent
import com.phoebe.app.data.JellyfinProviderAdapter
import com.phoebe.app.data.MusicAssistantClient
import com.phoebe.app.data.MusicAssistantProviderAdapter
import com.phoebe.app.data.MusicProviderAdapter
import com.phoebe.app.data.MusicProviderRegistry
import com.phoebe.app.data.NavidromeProviderAdapter
import com.phoebe.app.data.PlexClient
import com.phoebe.app.data.ProviderItemKind
import com.phoebe.app.data.SubsonicClient
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.catalogPrefix
import io.ktor.client.HttpClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

object ProviderSmokeHarness {
    fun registry(http: HttpClient): MusicProviderRegistry = MusicProviderRegistry(
        listOf(
            JellyfinProviderAdapter(JellyfinClient(http)),
            EmbyProviderAdapter(EmbyClient(http)),
            NavidromeProviderAdapter(SubsonicClient(http)),
            MusicAssistantProviderAdapter(MusicAssistantClient(http)),
        ),
    )

    fun adapterFor(source: SmokeSource, http: HttpClient): MusicProviderAdapter? = when (source) {
        SmokeSource.Plex -> null
        SmokeSource.Jellyfin -> JellyfinProviderAdapter(JellyfinClient(http))
        SmokeSource.Emby -> EmbyProviderAdapter(EmbyClient(http))
        SmokeSource.Navidrome -> NavidromeProviderAdapter(SubsonicClient(http))
        SmokeSource.MusicAssistant -> MusicAssistantProviderAdapter(MusicAssistantClient(http))
        SmokeSource.LocalFolders -> null
    }

    fun mockEngineFor(source: SmokeSource) = when (source) {
        SmokeSource.Plex -> plexCatalogMockEngine()
        SmokeSource.Jellyfin -> jellyfinSmokeMockEngine()
        SmokeSource.Emby -> embySmokeMockEngine()
        SmokeSource.Navidrome -> navidromeSmokeMockEngine()
        SmokeSource.MusicAssistant -> musicAssistantSmokeMockEngine()
        SmokeSource.LocalFolders -> error("local folders do not use remote mock engines")
    }

    fun remoteConfig(source: SmokeSource): RemoteSmokeConfig = when (source) {
        SmokeSource.Jellyfin -> RemoteSmokeConfig(
            serverUrl = "https://jellyfin.example",
            username = "ada",
            password = "secret",
            expectedPrefix = MediaProviderType.Jellyfin.catalogPrefix,
            supportsPlaylistCreate = true,
            supportsAlbumTracks = true,
            supportsRateItem = true,
            supportsStreamUrl = true,
            trackIdForMutations = "track-1",
        )
        SmokeSource.Emby -> RemoteSmokeConfig(
            serverUrl = "https://emby.example",
            username = "ada",
            password = "secret",
            expectedPrefix = MediaProviderType.Emby.catalogPrefix,
            supportsPlaylistCreate = false,
            supportsAlbumTracks = false,
            supportsRateItem = true,
            supportsStreamUrl = true,
            trackIdForMutations = "track-1",
        )
        SmokeSource.Navidrome -> RemoteSmokeConfig(
            serverUrl = "https://navidrome.example",
            username = "ada",
            password = "secret",
            expectedPrefix = MediaProviderType.Navidrome.catalogPrefix,
            supportsPlaylistCreate = true,
            supportsAlbumTracks = true,
            supportsRateItem = true,
            supportsStreamUrl = true,
            trackIdForMutations = "tr1",
        )
        SmokeSource.MusicAssistant -> RemoteSmokeConfig(
            serverUrl = "https://ma.example",
            username = "ada",
            password = "secret",
            expectedPrefix = MediaProviderType.MusicAssistant.catalogPrefix,
            supportsPlaylistCreate = true,
            supportsAlbumTracks = true,
            supportsRateItem = false,
            supportsStreamUrl = false,
            trackIdForMutations = "tr1",
        )
        SmokeSource.Plex, SmokeSource.LocalFolders -> error("no remote adapter config for $source")
    }

    suspend fun runPlexClientSmoke(http: HttpClient) {
        val client = PlexClient.withoutResolver(http)
        val session = testPlexSession()
        val server = session.selectedServer!!
        val library = session.selectedLibrary!!

        val album = Album(id = "a1", title = "Album One", artist = "Artist One")
        val albumTracks = client.children(server, album.id, session.token)
        assertTrue(albumTracks.isNotEmpty())

        val seedTrack = albumTracks.first()
        val created = client.createPlaylist(
            server = server,
            token = session.token,
            library = library,
            machineIdentifier = "server",
            title = "Smoke Mix",
            ratingKeys = listOf(seedTrack.id),
        )
        assertEquals("Smoke Mix", created.title)

        client.addTracksToPlaylist(
            server = server,
            token = session.token,
            machineIdentifier = "server",
            playlistRatingKey = "p1",
            ratingKeys = listOf(seedTrack.id),
        )
    }

    suspend fun runRemoteAdapterSmoke(adapter: MusicProviderAdapter, config: RemoteSmokeConfig) {
        val signedIn = adapter.signIn(config.serverUrl, config.username, config.password)
        assertEquals(adapter.providerType, signedIn.providerType)

        val server = signedIn.selectedServer!!
        val libraries = adapter.libraries(signedIn, server)
        assertTrue(libraries.isNotEmpty())

        val session = signedIn.copy(selectedLibrary = libraries.firstOrNull() ?: signedIn.selectedLibrary)
        val catalog = adapter.buildCatalog(session)
        assertTrue(catalog.artists.isNotEmpty(), "${adapter.providerType} smoke catalog missing artists")
        assertTrue(catalog.albums.isNotEmpty(), "${adapter.providerType} smoke catalog missing albums")
        assertTrue(catalog.playlists.isNotEmpty(), "${adapter.providerType} smoke catalog missing playlists")

        val album = catalog.albums.first()
        val seedTrack = if (config.supportsAlbumTracks) {
            val albumTracks = adapter.albumTracks(session, album)
            assertTrue(albumTracks.isNotEmpty())
            albumTracks.first()
        } else {
            catalog.tracksByParent.values.flatten().firstOrNull()
                ?: error("${adapter.providerType} smoke catalog did not include any tracks")
        }

        if (config.supportsPlaylistCreate) {
            val created = adapter.createPlaylist(session, "Smoke Mix", listOf(seedTrack))
            assertNotNull(created)
            adapter.addTracksToPlaylist(session, created, listOf(seedTrack))
        } else {
            assertEquals(null, adapter.createPlaylist(session, "Smoke Mix", listOf(seedTrack)))
        }

        if (adapter.providerType != MediaProviderType.MusicAssistant) {
            val mutationTrackId = "${config.expectedPrefix}:${config.trackIdForMutations}"
            assertTrue(
                adapter.setFavorite(session, mutationTrackId, favorite = true, kind = ProviderItemKind.Unknown),
            )
            if (config.supportsRateItem) {
                assertTrue(adapter.rateItem(session, mutationTrackId, 4f))
            }
            adapter.reportPlayback(
                session,
                seedTrack,
                positionMs = 30_000,
                isPaused = false,
                event = JellyfinPlaybackEvent.Progress,
            )
        } else {
            assertTrue(adapter.setFavorite(session, seedTrack.id, favorite = true, kind = ProviderItemKind.Unknown))
            assertFalse(adapter.capabilities.ratings)
            assertFalse(adapter.rateItem(session, seedTrack.id, 4f))
            val playlistTracks = adapter.playlistTracks(session, catalog.playlists.first())
            assertTrue(playlistTracks.isNotEmpty())
        }

        if (config.supportsStreamUrl) {
            val streamUrl = adapter.streamUrl(session, seedTrack)
            if (streamUrl != null) {
                assertTrue(streamUrl.isNotBlank())
            } else {
                assertTrue(seedTrack.streamUrl.isNotBlank() || seedTrack.downloadUrl.isNotBlank())
            }
        }
    }

    suspend fun runSourceSmoke(source: SmokeSource, http: HttpClient) {
        when (source) {
            SmokeSource.Plex -> runPlexClientSmoke(http)
            SmokeSource.LocalFolders -> error("local folder smoke requires platform IO")
            else -> {
                val adapter = adapterFor(source, http) ?: error("missing adapter for $source")
                runRemoteAdapterSmoke(adapter, remoteConfig(source))
            }
        }
    }

    fun jellyfinSession(): PlexSession = PlexSession(
        token = "jf-token",
        userName = "Ada",
        userId = "user-1",
        providerType = MediaProviderType.Jellyfin,
        selectedServer = com.phoebe.app.domain.PlexServer(
            "jellyfin:test",
            "Jellyfin",
            "https://jellyfin.example",
            owned = true,
        ),
        selectedLibrary = com.phoebe.app.domain.MusicLibrary("music", "Music"),
    )

    fun navidromeSession(): PlexSession = PlexSession(
        token = "secret",
        userName = "ada",
        providerType = MediaProviderType.Navidrome,
        selectedServer = com.phoebe.app.domain.PlexServer(
            "navidrome:test",
            "Navidrome",
            "https://navidrome.example",
            owned = true,
        ),
        selectedLibrary = com.phoebe.app.domain.MusicLibrary("all", "All Music"),
    )

    fun musicAssistantSession(): PlexSession = PlexSession(
        token = "ma-token",
        userName = "ada",
        providerType = MediaProviderType.MusicAssistant,
        selectedServer = com.phoebe.app.domain.PlexServer(
            "music-assistant:test",
            "ma.example",
            "https://ma.example",
            owned = true,
        ),
        selectedLibrary = com.phoebe.app.domain.MusicLibrary("music-assistant", "Music Assistant Library"),
    )
}

data class RemoteSmokeConfig(
    val serverUrl: String,
    val username: String,
    val password: String,
    val expectedPrefix: String,
    val supportsPlaylistCreate: Boolean,
    val supportsAlbumTracks: Boolean,
    val supportsRateItem: Boolean,
    val supportsStreamUrl: Boolean,
    val trackIdForMutations: String,
)
