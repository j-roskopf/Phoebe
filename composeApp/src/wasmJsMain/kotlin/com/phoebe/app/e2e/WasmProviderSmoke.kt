package com.phoebe.app.e2e

import com.phoebe.app.data.EmbyClient
import com.phoebe.app.data.EmbyProviderAdapter
import com.phoebe.app.data.JellyfinClient
import com.phoebe.app.data.JellyfinPlaybackEvent
import com.phoebe.app.data.JellyfinProviderAdapter
import com.phoebe.app.data.MusicAssistantClient
import com.phoebe.app.data.MusicAssistantProviderAdapter
import com.phoebe.app.data.MusicProviderAdapter
import com.phoebe.app.data.NavidromeProviderAdapter
import com.phoebe.app.data.PlexClient
import com.phoebe.app.data.ProviderItemKind
import com.phoebe.app.data.SubsonicClient
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.catalogPrefix
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

internal suspend fun runWasmProviderSmoke(rawProvider: String): WasmE2eResult {
    return try {
        when (rawProvider.lowercase()) {
            "all" -> {
                ALL_WASM_PROVIDERS.forEach { provider ->
                    val result = runWasmProviderSmoke(provider)
                    if (!result.passed) return result
                }
                WasmE2eResult(true, "all provider adapter smoke passed")
            }
            in ALL_WASM_PROVIDERS -> {
                val http = wasmSmokeHttpClient(mockEngineFor(rawProvider))
                runProviderSmoke(rawProvider, http)
                WasmE2eResult(true, "$rawProvider provider adapter smoke passed")
            }
            else -> WasmE2eResult(false, "unknown provider smoke target: $rawProvider")
        }
    } catch (error: Throwable) {
        WasmE2eResult(false, error.message ?: error.toString())
    }
}

private val ALL_WASM_PROVIDERS = listOf("plex", "jellyfin", "emby", "navidrome", "musicassistant")

private fun mockEngineFor(rawProvider: String): MockEngine = when (rawProvider.lowercase()) {
    "plex" -> plexCatalogMockEngine()
    "jellyfin" -> jellyfinSmokeMockEngine()
    "emby" -> embySmokeMockEngine()
    "navidrome" -> navidromeSmokeMockEngine()
    "musicassistant" -> musicAssistantSmokeMockEngine()
    else -> error("unknown provider mock: $rawProvider")
}

private fun wasmSmokeHttpClient(engine: MockEngine): HttpClient =
    HttpClient(engine) {
        install(ContentNegotiation) {
            json(PlexClient.PlexJson)
        }
    }

private suspend fun runProviderSmoke(rawProvider: String, http: HttpClient) {
    when (rawProvider.lowercase()) {
        "plex" -> runPlexSmoke(http)
        "jellyfin" -> runRemoteSmoke(
            JellyfinProviderAdapter(JellyfinClient(http)),
            RemoteSmokeConfig(
                serverUrl = "https://jellyfin.example",
                username = "ada",
                password = "secret",
                expectedPrefix = MediaProviderType.Jellyfin.catalogPrefix,
                supportsPlaylistCreate = true,
                supportsAlbumTracks = true,
                supportsRateItem = true,
                supportsStreamUrl = true,
                trackIdForMutations = "track-1",
            ),
        )
        "emby" -> runRemoteSmoke(
            EmbyProviderAdapter(EmbyClient(http)),
            RemoteSmokeConfig(
                serverUrl = "https://emby.example",
                username = "ada",
                password = "secret",
                expectedPrefix = MediaProviderType.Emby.catalogPrefix,
                supportsPlaylistCreate = false,
                supportsAlbumTracks = false,
                supportsRateItem = true,
                supportsStreamUrl = true,
                trackIdForMutations = "track-1",
            ),
        )
        "navidrome" -> runRemoteSmoke(
            NavidromeProviderAdapter(SubsonicClient(http)),
            RemoteSmokeConfig(
                serverUrl = "https://navidrome.example",
                username = "ada",
                password = "secret",
                expectedPrefix = MediaProviderType.Navidrome.catalogPrefix,
                supportsPlaylistCreate = true,
                supportsAlbumTracks = true,
                supportsRateItem = true,
                supportsStreamUrl = true,
                trackIdForMutations = "tr1",
            ),
        )
        "musicassistant" -> runRemoteSmoke(
            MusicAssistantProviderAdapter(MusicAssistantClient(http)),
            RemoteSmokeConfig(
                serverUrl = "https://ma.example",
                username = "ada",
                password = "secret",
                expectedPrefix = MediaProviderType.MusicAssistant.catalogPrefix,
                supportsPlaylistCreate = true,
                supportsAlbumTracks = true,
                supportsRateItem = false,
                supportsStreamUrl = false,
                trackIdForMutations = "tr1",
            ),
        )
        else -> error("unknown provider smoke: $rawProvider")
    }
}

private suspend fun runPlexSmoke(http: HttpClient) {
    val client = PlexClient.withoutResolver(http)
    val session = PlexSession(
        token = "token",
        selectedServer = PlexServer("server", "Plex", "https://plex.example:32400", owned = true),
        selectedLibrary = MusicLibrary("1", "Music"),
    )
    val server = session.selectedServer!!
    val library = session.selectedLibrary!!
    val album = Album(id = "a1", title = "Album One", artist = "Artist One")
    val seedTrack = client.children(server, album.id, session.token).firstOrNull()
        ?: error("plex smoke missing album tracks")
    client.createPlaylist(
        server = server,
        token = session.token,
        library = library,
        machineIdentifier = "server",
        title = "Smoke Mix",
        ratingKeys = listOf(seedTrack.id),
    )
    client.addTracksToPlaylist(
        server = server,
        token = session.token,
        machineIdentifier = "server",
        playlistRatingKey = "p1",
        ratingKeys = listOf(seedTrack.id),
    )
}

private suspend fun runRemoteSmoke(adapter: MusicProviderAdapter, config: RemoteSmokeConfig) {
    val signedIn = adapter.signIn(config.serverUrl, config.username, config.password)
    check(adapter.providerType == signedIn.providerType) { "unexpected provider type for ${adapter.providerType}" }

    val server = signedIn.selectedServer ?: error("missing server")
    val libraries = adapter.libraries(signedIn, server)
    check(libraries.isNotEmpty()) { "missing libraries" }

    val session = signedIn.copy(selectedLibrary = libraries.first())
    val catalog = adapter.buildCatalog(session)
    check(catalog.artists.isNotEmpty()) { "missing artists" }
    check(catalog.albums.isNotEmpty()) { "missing albums" }
    check(catalog.playlists.isNotEmpty()) { "missing playlists" }

    val seedTrack = if (config.supportsAlbumTracks) {
        adapter.albumTracks(session, catalog.albums.first()).firstOrNull()
    } else {
        catalog.tracksByParent.values.flatten().firstOrNull()
    } ?: error("missing seed track")

    if (config.supportsPlaylistCreate) {
        val created = adapter.createPlaylist(session, "Smoke Mix", listOf(seedTrack))
            ?: error("playlist create failed")
        adapter.addTracksToPlaylist(session, created, listOf(seedTrack))
    } else {
        check(adapter.createPlaylist(session, "Smoke Mix", listOf(seedTrack)) == null)
    }

    if (adapter.providerType != MediaProviderType.MusicAssistant) {
        val mutationTrackId = "${config.expectedPrefix}:${config.trackIdForMutations}"
        check(adapter.setFavorite(session, mutationTrackId, favorite = true, kind = ProviderItemKind.Unknown))
        if (config.supportsRateItem) {
            check(adapter.rateItem(session, mutationTrackId, 4f))
        }
        adapter.reportPlayback(session, seedTrack, 30_000, false, JellyfinPlaybackEvent.Progress)
    } else {
        check(adapter.setFavorite(session, seedTrack.id, favorite = true, kind = ProviderItemKind.Unknown))
        check(adapter.playlistTracks(session, catalog.playlists.first()).isNotEmpty())
    }

    if (config.supportsStreamUrl) {
        val streamUrl = adapter.streamUrl(session, seedTrack)
        if (streamUrl != null) {
            check(streamUrl.isNotBlank())
        } else {
            check(seedTrack.streamUrl.isNotBlank() || seedTrack.downloadUrl.isNotBlank())
        }
    }
}

private data class RemoteSmokeConfig(
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
