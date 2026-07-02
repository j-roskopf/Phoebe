package com.phoebe.app.data

import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.TrackMetadataUpdate
import com.phoebe.app.platform.PhoebeLog
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.phoebe.app.platform.catalogTrackIndexParallelism

@SingleIn(AppScope::class)
@Inject
open class JellyfinClient(
    private val httpClient: HttpClient,
    private val family: EmbyFamily = EmbyFamily.Jellyfin,
) {
    data class JellyfinItemPage<T>(
        val items: List<T>,
        val total: Int,
        val pageIndex: Int,
        val pageSize: Int = JellyfinPageSize,
    )

    private val pageSize: Int
        get() = if (family == EmbyFamily.Emby) EmbyPageSize else JellyfinPageSize

    suspend fun authenticate(serverUrl: String, username: String, password: String): JellyfinAuthResult {
        val base = normalizeBaseUrl(serverUrl)
        val response: JellyfinAuthResponse = httpClient.post("$base/Users/AuthenticateByName") {
            jellyfinHeaders()
            contentType(ContentType.Application.Json)
            setBody(JellyfinAuthRequest(Username = username, Pw = password))
        }.jellyfinBody("sign-in")
        val token = response.AccessToken?.takeIf { it.isNotBlank() } ?: error("${family.displayName} did not return an access token.")
        val user = response.User ?: error("${family.displayName} did not return a user.")
        val prefix = family.catalogPrefix
        return JellyfinAuthResult(
            token = token,
            userId = user.Id,
            userName = user.Name ?: username,
            server = PlexServer(
                id = "$prefix:${base.hashCode().toUInt().toString(16)}",
                name = base.removePrefix("https://").removePrefix("http://"),
                uri = base,
                connectionUris = listOf(base),
                owned = true,
            ),
        )
    }

    suspend fun libraries(server: PlexServer, token: String, userId: String): List<MusicLibrary> {
        val path = if (family == EmbyFamily.Emby) "/Users/$userId/Views" else "/UserViews"
        val response: JellyfinItemsResponse = httpClient.get("${server.uri}$path") {
            jellyfinAuth(token)
            if (family != EmbyFamily.Emby) {
                q("userId", userId)
            }
            q("includeExternalContent", false)
        }.jellyfinBody("library lookup")
        return response.Items
            .filter { it.CollectionType.equals("music", ignoreCase = true) || it.Type == "CollectionFolder" }
            .map { MusicLibrary(key = it.Id, title = it.Name ?: "Music") }
    }

    suspend fun artists(server: PlexServer, library: MusicLibrary, token: String, userId: String): List<Artist> {
        val scoped = pagedItems(server, token, "/Artists/AlbumArtists", "albumArtists scoped:${library.key}") {
            q("userId", userId)
            q("parentId", library.key)
            q("recursive", true)
            q("fields", JellyfinFields)
            q("enableUserData", true)
            q("sortBy", "SortName")
        }.Items.map { it.toArtist(server, token, family) }
        if (scoped.isNotEmpty()) return scoped
        return pagedItems(server, token, "/Artists/AlbumArtists", "albumArtists unscoped") {
            q("userId", userId)
            q("recursive", true)
            q("fields", JellyfinFields)
            q("enableUserData", true)
            q("sortBy", "SortName")
        }.Items.map { it.toArtist(server, token, family) }
    }

    suspend fun artistPage(server: PlexServer, library: MusicLibrary, token: String, userId: String, pageIndex: Int): JellyfinItemPage<Artist> {
        val scoped = pagedItems(
            server = server,
            token = token,
            path = "/Artists/AlbumArtists",
            label = "albumArtists scoped:${library.key} page:$pageIndex",
            startIndex = pageIndex * pageSize,
            maxPages = 1,
        ) {
            q("userId", userId)
            q("parentId", library.key)
            q("recursive", true)
            q("fields", JellyfinFields)
            q("enableUserData", true)
            q("sortBy", "SortName")
        }
        val page = if ((scoped.TotalRecordCount ?: scoped.Items.size) > 0 || pageIndex > 0) {
            scoped
        } else {
            pagedItems(
                server = server,
                token = token,
                path = "/Artists/AlbumArtists",
                label = "albumArtists unscoped page:$pageIndex",
                startIndex = pageIndex * pageSize,
                maxPages = 1,
            ) {
                q("userId", userId)
                q("recursive", true)
                q("fields", JellyfinFields)
                q("enableUserData", true)
                q("sortBy", "SortName")
            }
        }
        return JellyfinItemPage(page.Items.map { it.toArtist(server, token, family) }, page.TotalRecordCount ?: page.Items.size, pageIndex, pageSize)
    }

    suspend fun albums(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        userId: String,
        fastSync: Boolean = true,
        onPage: suspend (List<Album>, Int?) -> Unit = { _, _ -> },
    ): List<Album> {
        val collected = mutableListOf<Album>()
        suspend fun consumePage(page: List<Album>, total: Int?) {
            if (page.isNotEmpty()) collected += page
            onPage(page, total)
        }
        val scoped = albumsForQuery(server, token, userId, fastSync, ::consumePage) {
            q("parentId", library.key)
            q("recursive", true)
        }
        if (scoped.isNotEmpty()) return scoped
        return albumsForQuery(server, token, userId, fastSync, ::consumePage) {
            q("recursive", true)
        }
    }

    private suspend fun albumsForQuery(
        server: PlexServer,
        token: String,
        userId: String,
        fastSync: Boolean,
        onPage: suspend (List<Album>, Int?) -> Unit,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): List<Album> = pagedItemsParallel(
        server = server,
        token = token,
        userId = userId,
        path = "/Items",
        label = "albums",
        fetchImages = true,
        onPage = { items, total -> onPage(items.map { it.toAlbum(server, token) }, total) },
    ) {
        q("userId", userId)
        q("includeItemTypes", "MusicAlbum")
        q("fields", if (fastSync) JellyfinFastAlbumFields else JellyfinFields)
        q("enableUserData", true)
        q("sortBy", "AlbumArtist,SortName")
        block()
    }.Items.map { it.toAlbum(server, token) }

    suspend fun albumsForArtist(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        userId: String,
        artistId: String,
    ): List<Album> = pagedItems(
        server = server,
        token = token,
        userId = userId,
        path = "/Items",
        label = "albums for artist $artistId",
    ) {
        q("userId", userId)
        q("parentId", library.key)
        q("recursive", true)
        q("includeItemTypes", "MusicAlbum")
        q("fields", JellyfinFields)
        q("enableUserData", true)
        q("ArtistIds", artistId)
    }.Items.map { it.toAlbum(server, token) }

    suspend fun albumPage(server: PlexServer, library: MusicLibrary, token: String, userId: String, pageIndex: Int): JellyfinItemPage<Album> {
        val scoped = pagedItems(
            server = server,
            token = token,
            userId = userId,
            path = "/Items",
            label = "albums scoped:${library.key} page:$pageIndex",
            startIndex = pageIndex * pageSize,
            maxPages = 1,
        ) {
            q("userId", userId)
            q("parentId", library.key)
            q("recursive", true)
            q("includeItemTypes", "MusicAlbum")
            q("fields", JellyfinFields)
            q("enableUserData", true)
            q("sortBy", "AlbumArtist,SortName")
        }
        val page = if ((scoped.TotalRecordCount ?: scoped.Items.size) > 0 || pageIndex > 0) {
            scoped
        } else {
            pagedItems(
                server = server,
                token = token,
                userId = userId,
                path = "/Items",
                label = "albums unscoped page:$pageIndex",
                startIndex = pageIndex * pageSize,
                maxPages = 1,
            ) {
                q("userId", userId)
                q("recursive", true)
                q("includeItemTypes", "MusicAlbum")
                q("fields", JellyfinFields)
                q("enableUserData", true)
                q("sortBy", "AlbumArtist,SortName")
            }
        }
        return JellyfinItemPage(page.Items.map { it.toAlbum(server, token) }, page.TotalRecordCount ?: page.Items.size, pageIndex, pageSize)
    }

    suspend fun tracks(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        userId: String,
        includeMediaDetails: Boolean = true,
        onPage: suspend (List<Track>, Int?) -> Unit = { _, _ -> },
    ): List<Track> {
        val collected = mutableListOf<Track>()
        suspend fun consumePage(page: List<Track>, total: Int?) {
            if (page.isNotEmpty()) collected += page
            onPage(page, total)
        }
        val scoped = tracksForQuery(server, token, userId, ::consumePage, includeMediaDetails) {
            q("parentId", library.key)
            q("recursive", true)
            q("includeItemTypes", "Audio")
            q("sortBy", "AlbumArtist,Album,ParentIndexNumber,IndexNumber,SortName")
        }
        if (scoped.isNotEmpty()) return scoped
        return tracksForQuery(server, token, userId, ::consumePage, includeMediaDetails) {
            q("recursive", true)
            q("includeItemTypes", "Audio")
            q("sortBy", "AlbumArtist,Album,ParentIndexNumber,IndexNumber,SortName")
        }
    }

    suspend fun trackPage(server: PlexServer, library: MusicLibrary, token: String, userId: String, pageIndex: Int): JellyfinItemPage<Track> {
        val scoped = pagedItems(
            server = server,
            token = token,
            userId = userId,
            path = "/Items",
            label = "tracks scoped:${library.key} page:$pageIndex",
            startIndex = pageIndex * pageSize,
            maxPages = 1,
        ) {
            q("userId", userId)
            q("parentId", library.key)
            q("recursive", true)
            q("includeItemTypes", "Audio")
            q("fields", JellyfinFields)
            q("enableUserData", true)
            q("sortBy", "AlbumArtist,Album,ParentIndexNumber,IndexNumber,SortName")
        }
        val page = if ((scoped.TotalRecordCount ?: scoped.Items.size) > 0 || pageIndex > 0) {
            scoped
        } else {
            pagedItems(
                server = server,
                token = token,
                userId = userId,
                path = "/Items",
                label = "tracks unscoped page:$pageIndex",
                startIndex = pageIndex * pageSize,
                maxPages = 1,
            ) {
                q("userId", userId)
                q("recursive", true)
                q("includeItemTypes", "Audio")
                q("fields", JellyfinFields)
                q("enableUserData", true)
                q("sortBy", "AlbumArtist,Album,ParentIndexNumber,IndexNumber,SortName")
            }
        }
        return JellyfinItemPage(page.Items.mapNotNull { it.toTrack(server, token) }, page.TotalRecordCount ?: page.Items.size, pageIndex, pageSize)
    }

    suspend fun playbackStatsPage(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        userId: String,
        start: Int,
        size: Int,
    ): List<JellyfinPlaybackStat> {
        val strategies = if (family == EmbyFamily.Emby) {
            buildList {
                add(PlaybackStatsQuery(scopedToLibrary = true, playedFilter = true, sortBy = "PlayCount"))
                add(PlaybackStatsQuery(scopedToLibrary = true, playedFilter = true, sortBy = "DatePlayed"))
                add(PlaybackStatsQuery(scopedToLibrary = true, playedFilter = false, sortBy = "PlayCount"))
                if (start == 0) {
                    add(PlaybackStatsQuery(scopedToLibrary = false, playedFilter = true, sortBy = "PlayCount"))
                }
            }
        } else {
            buildList {
                add(PlaybackStatsQuery(scopedToLibrary = true, playedFilter = true))
                add(PlaybackStatsQuery(scopedToLibrary = true, playedFilter = false))
                add(PlaybackStatsQuery(scopedToLibrary = true, playedFilter = false, sortBy = "PlayCount"))
                if (start == 0) {
                    add(PlaybackStatsQuery(scopedToLibrary = false, playedFilter = true))
                    add(PlaybackStatsQuery(scopedToLibrary = false, playedFilter = false))
                    add(PlaybackStatsQuery(scopedToLibrary = false, playedFilter = false, sortBy = "PlayCount"))
                }
            }
        }
        for (strategy in strategies) {
            val stats = playbackStatsItems(
                server = server,
                token = token,
                userId = userId,
                library = library,
                start = start,
                size = size,
                query = strategy,
            )
            if (stats.isNotEmpty()) return stats
        }
        return emptyList()
    }

    /**
     * Emby: GET /Users/{UserId}/Items/Resume — resumable / in-progress items.
     */
    suspend fun playbackResumePage(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        userId: String,
        start: Int,
        size: Int,
    ): List<JellyfinPlaybackStat> {
        if (userId.isBlank()) return emptyList()
        val path = "/Users/$userId/Items/Resume"
        val response = httpClient.get("${server.uri}$path") {
            jellyfinAuth(token)
            q("enableImages", true)
            q("imageTypeLimit", 1)
            q("enableImageTypes", "Primary")
            q("parentId", library.key)
            q("recursive", true)
            q("includeItemTypes", "Audio")
            q("fields", historyFields())
            q("enableUserData", true)
            q("startIndex", start)
            q("limit", size)
        }.body<JellyfinItemsResponse>()
        val stats = response.Items.mapNotNull { it.toPlaybackStat(server, token, requirePlayCount = false) }
        PhoebeLog.d("JellyfinClient") {
            "playbackResume parent=${library.key} raw=${response.Items.size} kept=${stats.size} total=${response.TotalRecordCount}"
        }
        return stats
    }

    /**
     * Emby: GET /Users/{UserId}/Items/Latest?IsPlayed=true — recently played audio per library.
     */
    suspend fun playbackLatestPlayedPage(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        userId: String,
        size: Int,
    ): List<JellyfinPlaybackStat> {
        if (family != EmbyFamily.Emby || userId.isBlank()) return emptyList()
        val items: List<JellyfinItemDto> = httpClient.get("${server.uri}/Users/$userId/Items/Latest") {
            jellyfinAuth(token)
            q("parentId", library.key)
            q("includeItemTypes", "Audio")
            q("isPlayed", true)
            q("enableUserData", true)
            q("fields", historyFields())
            q("limit", size)
        }.body()
        val stats = items.mapNotNull { it.toPlaybackStat(server, token, requirePlayCount = false) }
        PhoebeLog.d("JellyfinClient") {
            "playbackLatest parent=${library.key} raw=${items.size} kept=${stats.size}"
        }
        return stats
    }

    private data class PlaybackStatsQuery(
        val scopedToLibrary: Boolean,
        val playedFilter: Boolean,
        val sortBy: String = "DatePlayed",
    )

    private suspend fun playbackStatsItems(
        server: PlexServer,
        token: String,
        userId: String,
        library: MusicLibrary,
        start: Int,
        size: Int,
        query: PlaybackStatsQuery,
    ): List<JellyfinPlaybackStat> {
        val path = itemsApiPath(userId)
        val response = items(server, token, path = path, userId = userId, fetchImages = true) {
            if (resolvedItemsPath(path, userId) == "/Items") {
                q("userId", userId)
            }
            if (query.scopedToLibrary) {
                q("parentId", library.key)
                q("recursive", true)
            }
            q("includeItemTypes", "Audio")
            q("fields", historyFields())
            q("enableUserData", true)
            if (query.playedFilter) {
                if (family == EmbyFamily.Emby) {
                    q("isPlayed", true)
                } else {
                    q("filters", "IsPlayed")
                }
            }
            q("sortBy", query.sortBy)
            q("sortOrder", "Descending")
            q("startIndex", start)
            q("limit", size)
        }
        val stats = response.Items.mapNotNull { it.toPlaybackStat(server, token, requirePlayCount = true) }
        PhoebeLog.d("JellyfinClient") {
            "playbackStats scoped=${query.scopedToLibrary} played=${query.playedFilter} sort=${query.sortBy} " +
                "parent=${if (query.scopedToLibrary) library.key else "all"} " +
                "raw=${response.Items.size} kept=${stats.size} total=${response.TotalRecordCount}"
        }
        return stats
    }

    private fun historyFields(): String =
        if (family == EmbyFamily.Emby) EmbyHistoryFields else JellyfinHistoryFields

    private fun itemsApiPath(userId: String): String =
        if (family == EmbyFamily.Emby && userId.isNotBlank()) "/Users/$userId/Items" else "/Items"

    suspend fun trackDetails(server: PlexServer, token: String, itemId: String, userId: String? = null): Track? =
        runCatching {
            val itemUrl = if (family == EmbyFamily.Emby && !userId.isNullOrBlank()) {
                "${server.uri}/Users/$userId/Items/$itemId"
            } else {
                "${server.uri}/Items/$itemId"
            }
            val item: JellyfinItemDto = httpClient.get(itemUrl) {
                jellyfinAuth(token)
                q("fields", JellyfinFields)
                if (family == EmbyFamily.Emby && !userId.isNullOrBlank()) {
                    q("enableUserData", true)
                }
            }.body()
            item.toTrack(server, token)
        }.getOrNull()

    suspend fun albumTracks(server: PlexServer, album: Album, token: String, userId: String): List<Track> =
        tracksForQuery(server, token, userId) {
            q("parentId", album.id)
            q("recursive", true)
            q("includeItemTypes", "Audio")
            q("sortBy", "ParentIndexNumber,IndexNumber,SortName")
        }

    suspend fun playlists(server: PlexServer, library: MusicLibrary, token: String, userId: String): List<Playlist> {
        val scoped = pagedItems(server, token, "/Items", "playlists scoped:${library.key}", userId = userId) {
            q("userId", userId)
            q("parentId", library.key)
            q("recursive", true)
            q("includeItemTypes", "Playlist")
            q("fields", JellyfinFields)
            q("enableUserData", true)
        }.Items.map { it.toPlaylist(server, token) }
        val regular = scoped.ifEmpty {
            pagedItems(server, token, "/Items", "playlists unscoped", userId = userId) {
                q("userId", userId)
                q("recursive", true)
                q("includeItemTypes", "Playlist")
                q("fields", JellyfinFields)
                q("enableUserData", true)
            }.Items.map { it.toPlaylist(server, token) }
        }
        val likedCount = favoriteTrackCount(server, library, token, userId)
        return listOf(Playlist(id = JellyfinLikedSongsPlaylistId, title = "Liked Songs", trackCount = likedCount, favorite = true)) + regular
    }

    suspend fun playlistTracks(server: PlexServer, playlist: Playlist, token: String, userId: String): List<Track> {
        if (playlist.id == JellyfinLikedSongsPlaylistId) {
            return favoriteTracks(server, MusicLibrary("", ""), token, userId)
        }
        return tracksForQuery(server, token, userId) {
            q("parentId", playlist.id)
            q("recursive", true)
            q("includeItemTypes", "Audio")
        }
    }

    suspend fun instantMix(server: PlexServer, token: String, userId: String, itemId: String): List<Track> =
        httpClient.get("${server.uri}/Items/$itemId/InstantMix") {
            jellyfinAuth(token)
            q("userId", userId)
            q("fields", JellyfinFields)
            q("enableUserData", true)
        }.body<JellyfinItemsResponse>().Items.mapNotNull { it.toTrack(server, token) }

    suspend fun initiateQuickConnect(serverUrl: String): JellyfinQuickConnectResult {
        val base = normalizeBaseUrl(serverUrl)
        val response = httpClient.post("$base/QuickConnect/Initiate") {
            jellyfinHeaders()
        }.let { postResponse ->
            if (postResponse.status == HttpStatusCode.NotFound || postResponse.status == HttpStatusCode.MethodNotAllowed) {
                httpClient.get("$base/QuickConnect/Initiate") { jellyfinHeaders() }
            } else {
                postResponse
            }
        }
        val result: JellyfinQuickConnectResult = response.jellyfinBody("Quick Connect start")
        return result.copy(ServerUrl = base)
    }

    suspend fun authenticateQuickConnect(serverUrl: String, secret: String): JellyfinAuthResult {
        val base = normalizeBaseUrl(serverUrl)
        val state = quickConnectState(base, secret)
        if (!state.Authenticated) {
            state.Error?.takeIf { it.isNotBlank() }?.let { error(it) }
            error("That Jellyfin Quick Connect code is not approved yet.")
        }
        val legacyToken = state.Authentication?.takeIf { it.isNotBlank() }
        val response: JellyfinAuthResponse = if (legacyToken != null) {
            httpClient.post("$base/Users/AuthenticateWithQuickConnect") {
                jellyfinHeaders()
                contentType(ContentType.Application.Json)
                setBody(JellyfinLegacyQuickConnectAuthRequest(Token = legacyToken))
            }.jellyfinBody("Quick Connect sign-in")
        } else {
            httpClient.post("$base/Users/AuthenticateWithQuickConnect") {
                jellyfinHeaders()
                contentType(ContentType.Application.Json)
                setBody(JellyfinQuickConnectAuthRequest(Secret = secret))
            }.jellyfinBody("Quick Connect sign-in")
        }
        val token = response.AccessToken?.takeIf { it.isNotBlank() } ?: error("Jellyfin did not return an access token.")
        val user = response.User ?: error("Jellyfin did not return a user.")
        return JellyfinAuthResult(
            token = token,
            userId = user.Id,
            userName = user.Name ?: "Jellyfin listener",
            server = PlexServer(
                id = "jellyfin:${base.hashCode().toUInt().toString(16)}",
                name = base.removePrefix("https://").removePrefix("http://"),
                uri = base,
                owned = true,
            ),
        )
    }

    suspend fun favoriteTracks(server: PlexServer, library: MusicLibrary, token: String, userId: String): List<Track> =
        tracksForQuery(server, token, userId) {
            library.key.takeIf { it.isNotBlank() }?.let { q("parentId", it) }
            q("recursive", true)
            q("includeItemTypes", "Audio")
            q("isFavorite", true)
            q("sortBy", "SortName")
        }

    private suspend fun favoriteTrackCount(server: PlexServer, library: MusicLibrary, token: String, userId: String): Int =
        items(server, token) {
            library.key.takeIf { it.isNotBlank() }?.let { q("parentId", it) }
            q("userId", userId)
            q("recursive", true)
            q("includeItemTypes", "Audio")
            q("isFavorite", true)
            q("limit", 0)
        }.let { it.TotalRecordCount ?: it.Items.size }

    suspend fun createPlaylist(server: PlexServer, token: String, userId: String, title: String, itemIds: List<String>): Playlist {
        val response: JellyfinPlaylistCreateResponse = httpClient.post("${server.uri}/Playlists") {
            jellyfinAuth(token)
            contentType(ContentType.Application.Json)
            setBody(JellyfinCreatePlaylistDto(Name = title, Ids = itemIds, UserId = userId, MediaType = "Audio"))
        }.body()
        val id = response.Id ?: error("Jellyfin did not return a playlist id.")
        return Playlist(id = id, title = title, trackCount = itemIds.size)
    }

    suspend fun addTracksToPlaylist(server: PlexServer, token: String, userId: String, playlistId: String, itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        val response = httpClient.post("${server.uri}/Playlists/$playlistId/Items") {
            jellyfinAuth(token)
            q("userId", userId)
            q("ids", itemIds.joinToString(","))
        }
        if (!response.status.isSuccess()) error("Jellyfin playlist add failed (${response.status.value}): ${response.bodyAsText().take(200)}")
    }

    suspend fun movePlaylistItem(
        server: PlexServer,
        token: String,
        userId: String,
        playlistId: String,
        itemId: String,
        newIndex: Int,
    ) {
        val response = httpClient.post("${server.uri}/Playlists/$playlistId/Items/$itemId/Move/$newIndex") {
            jellyfinAuth(token)
            q("userId", userId)
        }
        if (!response.status.isSuccess()) {
            error("${family.displayName} playlist move failed (${response.status.value}): ${response.bodyAsText().take(200)}")
        }
    }

    suspend fun removePlaylistItems(
        server: PlexServer,
        token: String,
        userId: String,
        playlistId: String,
        entryIds: List<String>,
    ) {
        if (entryIds.isEmpty()) return
        val response = httpClient.delete("${server.uri}/Playlists/$playlistId/Items") {
            jellyfinAuth(token)
            q("userId", userId)
            q("entryIds", entryIds.joinToString(","))
        }
        if (!response.status.isSuccess() && response.status.value != 204) {
            error("${family.displayName} playlist remove failed (${response.status.value}): ${response.bodyAsText().take(200)}")
        }
    }

    suspend fun setFavorite(server: PlexServer, token: String, itemId: String, favorite: Boolean) {
        val path = if (family == EmbyFamily.Emby) {
            error("Emby favorites require a user id.")
        } else {
            "/UserFavoriteItems/$itemId"
        }
        val response = if (favorite) {
            httpClient.post("${server.uri}$path") { jellyfinAuth(token) }
        } else {
            httpClient.delete("${server.uri}$path") { jellyfinAuth(token) }
        }
        if (!response.status.isSuccess()) error("Jellyfin favorite sync failed (${response.status.value}): ${response.bodyAsText().take(200)}")
    }

    suspend fun setFavorite(server: PlexServer, token: String, userId: String, itemId: String, favorite: Boolean) {
        if (family != EmbyFamily.Emby) {
            setFavorite(server, token, itemId, favorite)
            return
        }
        val path = "/Users/$userId/FavoriteItems/$itemId"
        val response = if (favorite) {
            httpClient.post("${server.uri}$path") { jellyfinAuth(token) }
        } else {
            httpClient.delete("${server.uri}$path") { jellyfinAuth(token) }
        }
        if (!response.status.isSuccess()) error("Emby favorite sync failed (${response.status.value}): ${response.bodyAsText().take(200)}")
    }

    suspend fun rateItem(server: PlexServer, token: String, itemId: String, rating: Float?) {
        if (family == EmbyFamily.Emby) error("Emby ratings require a user id.")
        val response = if (rating == null) {
            httpClient.delete("${server.uri}/UserItems/$itemId/Rating") { jellyfinAuth(token) }
        } else {
            httpClient.post("${server.uri}/UserItems/$itemId/Rating") {
                jellyfinAuth(token)
                q("likes", true)
                q("rating", (rating * 2f).coerceIn(0f, 10f))
            }
        }
        if (!response.status.isSuccess()) error("Jellyfin rating sync failed (${response.status.value}): ${response.bodyAsText().take(200)}")
    }

    suspend fun rateItem(server: PlexServer, token: String, userId: String, itemId: String, rating: Float?) {
        if (family != EmbyFamily.Emby) {
            rateItem(server, token, itemId, rating)
            return
        }
        val path = "/Users/$userId/Items/$itemId/Rating"
        val response = if (rating == null) {
            httpClient.delete("${server.uri}$path") { jellyfinAuth(token) }
        } else {
            httpClient.post("${server.uri}$path") {
                jellyfinAuth(token)
                q("likes", true)
                q("rating", (rating * 2f).coerceIn(0f, 10f))
            }
        }
        if (!response.status.isSuccess()) error("Emby rating sync failed (${response.status.value}): ${response.bodyAsText().take(200)}")
    }

    suspend fun editTrackMetadata(server: PlexServer, token: String, itemId: String, original: Track, update: TrackMetadataUpdate) {
        val current: JellyfinItemDto = httpClient.get("${server.uri}/Items/$itemId") {
            jellyfinAuth(token)
        }.body()
        val body = current.copy(
            Name = update.title.takeIf { it.isNotBlank() } ?: original.title,
            Album = update.album.takeIf { it.isNotBlank() } ?: original.album,
            Artists = listOf(update.artist.takeIf { it.isNotBlank() } ?: original.artist),
            ProductionYear = update.year ?: current.ProductionYear,
            Genres = update.genre?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: current.Genres,
        )
        val response = httpClient.post("${server.uri}/Items/$itemId") {
            jellyfinAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) error("Jellyfin metadata sync failed (${response.status.value}): ${response.bodyAsText().take(200)}")
    }

    suspend fun reportPlayback(server: PlexServer, token: String, itemId: String, positionMs: Long, isPaused: Boolean, event: JellyfinPlaybackEvent) {
        val path = when (event) {
            JellyfinPlaybackEvent.Start -> "/Sessions/Playing"
            JellyfinPlaybackEvent.Progress -> "/Sessions/Playing/Progress"
            JellyfinPlaybackEvent.Stop -> "/Sessions/Playing/Stopped"
        }
        httpClient.post("${server.uri}$path") {
            jellyfinAuth(token)
            contentType(ContentType.Application.Json)
            setBody(JellyfinPlaybackProgressInfo(ItemId = itemId, PositionTicks = positionMs * 10_000L, IsPaused = isPaused))
        }
    }

    suspend fun markPlayed(server: PlexServer, token: String, userId: String, itemId: String) {
        val response = httpClient.post("${server.uri}/Users/$userId/PlayedItems/$itemId") {
            jellyfinAuth(token)
        }
        if (!response.status.isSuccess()) {
            val details = response.bodyAsText().trim().take(200)
            val suffix = details.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            error("${family.displayName} mark played failed (${response.status.value})$suffix")
        }
    }

    private suspend fun tracksForQuery(
        server: PlexServer,
        token: String,
        userId: String,
        onPage: suspend (List<Track>, Int?) -> Unit = { _, _ -> },
        includeMediaDetails: Boolean = true,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): List<Track> = pagedItemsParallel(
        server = server,
        token = token,
        userId = userId,
        path = "/Items",
        label = "tracks",
        fetchImages = false,
        onPage = { items, total ->
            onPage(items.mapNotNull { it.toTrack(server, token) }, total)
        },
    ) {
        q("userId", userId)
        q("fields", if (includeMediaDetails) JellyfinFields else JellyfinFastTrackFields)
        q("enableUserData", true)
        block()
    }.Items.mapNotNull { it.toTrack(server, token) }

    private suspend fun pagedItemsParallel(
        server: PlexServer,
        token: String,
        path: String,
        label: String,
        userId: String? = null,
        parallelism: Int = catalogTrackIndexParallelism().coerceAtLeast(1),
        fetchImages: Boolean = true,
        onPage: suspend (List<JellyfinItemDto>, Int?) -> Unit = { _, _ -> },
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): JellyfinItemsResponse = coroutineScope {
        val limit = pageSize
        val firstPage = items(server, token, path = path, userId = userId, fetchImages = fetchImages) {
            q("startIndex", 0)
            q("limit", limit)
            block()
        }
        val total = firstPage.TotalRecordCount ?: firstPage.Items.size
        PhoebeLog.d("JellyfinClient") {
            "pagedItemsParallel $label total=${total} firstPage=${firstPage.Items.size}"
        }
        onPage(firstPage.Items, total)
        if (firstPage.Items.isEmpty()) {
            return@coroutineScope JellyfinItemsResponse(Items = emptyList(), TotalRecordCount = total)
        }
        val pageCount = ((total + limit - 1) / limit).coerceAtLeast(1)
        if (pageCount <= 1) {
            return@coroutineScope JellyfinItemsResponse(Items = firstPage.Items, TotalRecordCount = total)
        }

        val pagesByIndex = mutableMapOf(0 to firstPage.Items)
        val pageResultMutex = Mutex()
        val pageQueue = Channel<Int>(capacity = Channel.UNLIMITED)
        for (pageIndex in 1 until pageCount) {
            pageQueue.send(pageIndex)
        }
        pageQueue.close()

        val workers = List(parallelism.coerceAtMost(pageCount - 1).coerceAtLeast(1)) {
            launch {
                for (pageIndex in pageQueue) {
                    val start = pageIndex * limit
                    PhoebeLog.d("JellyfinClient") { "pagedItemsParallel $label request start=$start limit=$limit" }
                    val page = items(server, token, path = path, userId = userId, fetchImages = fetchImages) {
                        q("startIndex", start)
                        q("limit", limit)
                        block()
                    }
                    pageResultMutex.withLock {
                        pagesByIndex[pageIndex] = page.Items
                        onPage(page.Items, total)
                    }
                }
            }
        }
        workers.joinAll()

        val all = pageResultMutex.withLock {
            (0 until pageCount).flatMap { pagesByIndex[it].orEmpty() }
        }
        PhoebeLog.d("JellyfinClient") { "pagedItemsParallel $label loaded=${all.size}" }
        JellyfinItemsResponse(Items = all, TotalRecordCount = total)
    }

    private suspend fun pagedItems(
        server: PlexServer,
        token: String,
        path: String,
        label: String,
        userId: String? = null,
        startIndex: Int = 0,
        maxPages: Int? = null,
        fetchImages: Boolean = true,
        onPage: suspend (List<JellyfinItemDto>, Int?) -> Unit = { _, _ -> },
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): JellyfinItemsResponse {
        val all = mutableListOf<JellyfinItemDto>()
        var start = startIndex
        var total: Int? = null
        var pagesLoaded = 0
        while (true) {
            val limit = pageSize
            PhoebeLog.d("JellyfinClient") { "pagedItems $label request start=$start limit=$limit" }
            val page = items(server, token, path = path, userId = userId, fetchImages = fetchImages) {
                q("startIndex", start)
                q("limit", limit)
                block()
            }
            if (total == null) {
                total = page.TotalRecordCount
                PhoebeLog.d("JellyfinClient") {
                    "pagedItems $label total=${total ?: "unknown"} firstPage=${page.Items.size}"
                }
            }
            PhoebeLog.d("JellyfinClient") {
                "pagedItems $label page=${pagesLoaded + 1} start=$start loaded=${page.Items.size} total=${total ?: "unknown"}"
            }
            if (page.Items.isEmpty()) break
            all += page.Items
            onPage(page.Items, total)
            start += page.Items.size
            pagesLoaded++
            if (maxPages != null && pagesLoaded >= maxPages) break
            val expectedTotal = total
            if (expectedTotal != null && start >= expectedTotal) break
            if (page.Items.size < limit) break
        }
        PhoebeLog.d("JellyfinClient") { "pagedItems $label loaded=${all.size}" }
        return JellyfinItemsResponse(Items = all, TotalRecordCount = total ?: all.size)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.q(name: String, value: Any?) {
        parameter(embyQueryName(name), value)
    }

    private fun embyQueryName(name: String): String {
        if (family != EmbyFamily.Emby) return name
        return when (name) {
            "userId" -> "UserId"
            "parentId" -> "ParentId"
            "startIndex" -> "StartIndex"
            "limit" -> "Limit"
            "recursive" -> "Recursive"
            "fields" -> "Fields"
            "enableUserData" -> "EnableUserData"
            "enableImages" -> "EnableImages"
            "imageTypeLimit" -> "ImageTypeLimit"
            "enableImageTypes" -> "EnableImageTypes"
            "includeItemTypes" -> "IncludeItemTypes"
            "includeExternalContent" -> "IncludeExternalContent"
            "sortBy" -> "SortBy"
            "sortOrder" -> "SortOrder"
            "artistIds" -> "ArtistIds"
            "filters" -> "Filters"
            "isPlayed" -> "IsPlayed"
            "isFavorite" -> "IsFavorite"
            "mediaTypes" -> "MediaTypes"
            "secret" -> "secret"
            else -> if (name.isNotEmpty() && name[0].isUpperCase()) name else name.replaceFirstChar { it.uppercaseChar() }
        }
    }

    private suspend fun items(
        server: PlexServer,
        token: String,
        path: String = "/Items",
        userId: String? = null,
        fetchImages: Boolean = true,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): JellyfinItemsResponse = httpClient.get("${server.uri}${resolvedItemsPath(path, userId)}") {
        jellyfinAuth(token)
        if (fetchImages) {
            q("enableImages", true)
            q("imageTypeLimit", 1)
            q("enableImageTypes", "Primary")
        }
        block()
    }.body()

    private fun resolvedItemsPath(path: String, userId: String?): String =
        if (path == "/Items") itemsApiPath(userId.orEmpty()) else path

    private fun io.ktor.client.request.HttpRequestBuilder.jellyfinHeaders() {
        header(HttpHeaders.Accept, "application/json")
        header("X-Emby-Authorization", """MediaBrowser Client="Phoebe", Device="Phoebe", DeviceId="phoebe", Version="1.0.0"""")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.jellyfinAuth(token: String) {
        jellyfinHeaders()
        header("X-Emby-Token", token)
    }

    private suspend fun quickConnectState(base: String, secret: String): JellyfinQuickConnectResult =
        httpClient.get("$base/QuickConnect/Connect") {
            jellyfinHeaders()
            q("secret", secret)
        }.jellyfinBody("Quick Connect approval check")

    private suspend inline fun <reified T> HttpResponse.jellyfinBody(action: String): T {
        if (!status.isSuccess()) {
            val provider = family.displayName
            val details = bodyAsText().trim().take(200)
            val suffix = details.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            if (status == HttpStatusCode.Unauthorized) {
                error("$provider $action failed: unauthorized. Check the server URL, username, and password.")
            }
            error("$provider $action failed (${status.value})$suffix")
        }
        return body()
    }

    private fun normalizeBaseUrl(serverUrl: String): String {
        val base = serverUrl.trim().trimEnd('/')
        if (family != EmbyFamily.Emby) return base
        return if (base.endsWith("/emby", ignoreCase = true)) base else "$base/emby"
    }

    companion object {
        const val JellyfinLikedSongsPlaylistId = "jellyfin:liked-songs"
        const val JellyfinPageSize = 500
        const val EmbyPageSize = 1000
        private const val JellyfinFields =
            "Genres,Path,MediaSources,UserData,DateCreated,ProductionYear,AlbumArtist,AlbumArtists,ParentId,PrimaryImageTag,AlbumPrimaryImageTag,ParentThumbItemId,ParentThumbImageTag"
        private const val JellyfinFastTrackFields =
            "Genres,UserData,DateCreated,ProductionYear,AlbumArtist,ParentId,PrimaryImageTag,AlbumPrimaryImageTag,ParentThumbItemId,ParentThumbImageTag"
        private const val JellyfinFastAlbumFields =
            "Genres,UserData,DateCreated,ProductionYear,AlbumArtist,AlbumArtists,ParentId,PrimaryImageTag,AlbumPrimaryImageTag,ParentThumbItemId,ParentThumbImageTag"
        private const val JellyfinHistoryFields =
            "Genres,Path,UserData,DateCreated,ProductionYear,AlbumArtist,AlbumArtists,Artists,ParentId,Album,AlbumId,PrimaryImageTag,AlbumPrimaryImageTag"
        // Emby ItemFields enum — UserData/AlbumArtists/etc. are invalid; use EnableUserData instead.
        private const val EmbyHistoryFields =
            "Genres,Path,DateCreated,ProductionYear,Album,AlbumId,ParentId,UserDataPlayCount,UserDataLastPlayedDate"
    }
}

@SingleIn(AppScope::class)
@Inject
class EmbyClient(httpClient: HttpClient) : JellyfinClient(httpClient, EmbyFamily.Emby)

enum class EmbyFamily(val catalogPrefix: String, val displayName: String) {
    Jellyfin("jellyfin", "Jellyfin"),
    Emby("emby", "Emby"),
}

data class JellyfinAuthResult(
    val token: String,
    val userId: String,
    val userName: String,
    val server: PlexServer,
)

data class JellyfinPlaybackStat(
    val itemId: String,
    val lastPlayedAtMs: Long,
    val playCount: Long,
    val track: Track? = null,
)

@Serializable
private data class JellyfinAuthRequest(val Username: String, val Pw: String)

@Serializable
private data class JellyfinAuthResponse(val User: JellyfinUserDto? = null, val AccessToken: String? = null)

@Serializable
data class JellyfinQuickConnectResult(
    val Authenticated: Boolean = false,
    val Secret: String,
    val Code: String,
    val Authentication: String? = null,
    val Error: String? = null,
    val DeviceId: String? = null,
    val DeviceName: String? = null,
    val AppName: String? = null,
    val AppVersion: String? = null,
    val DateAdded: String? = null,
    val ServerUrl: String? = null,
)

@Serializable
private data class JellyfinQuickConnectAuthRequest(val Secret: String)

@Serializable
private data class JellyfinLegacyQuickConnectAuthRequest(val Token: String)

@Serializable
private data class JellyfinUserDto(val Id: String, val Name: String? = null)

@Serializable
private data class JellyfinItemsResponse(val Items: List<JellyfinItemDto> = emptyList(), val TotalRecordCount: Int? = null)

@Serializable
private data class JellyfinItemDto(
    val Id: String,
    val Name: String? = null,
    val Type: String? = null,
    val CollectionType: String? = null,
    val Album: String? = null,
    val AlbumId: String? = null,
    val AlbumArtist: String? = null,
    val AlbumArtists: List<JellyfinNameIdPair>? = null,
    val ParentId: String? = null,
    val Artists: List<String>? = null,
    val ProductionYear: Int? = null,
    val RunTimeTicks: Long? = null,
    val DateCreated: String? = null,
    val Genres: List<String>? = null,
    val Path: String? = null,
    val MediaSources: List<JellyfinMediaSource>? = null,
    val UserData: JellyfinUserData? = null,
    val ImageTags: JellyfinImageTags? = null,
    val PrimaryImageTag: String? = null,
    val AlbumPrimaryImageTag: String? = null,
    val HasPrimaryImage: Boolean? = null,
    val ParentThumbItemId: String? = null,
    val ParentThumbImageTag: String? = null,
    val ChildCount: Int? = null,
    val AlbumCount: Int? = null,
)

@Serializable
private data class JellyfinNameIdPair(val Name: String? = null, val Id: String? = null)

@Serializable
private data class JellyfinImageTags(val Primary: String? = null)

@Serializable
private data class JellyfinMediaSource(val Bitrate: Int? = null, val Container: String? = null)

@Serializable
private data class JellyfinUserData(
    val Rating: Double? = null,
    val IsFavorite: Boolean? = null,
    val PlayCount: Int? = null,
    val LastPlayedDate: String? = null,
    val Played: Boolean? = null,
)

@Serializable
private data class JellyfinCreatePlaylistDto(
    val Name: String,
    val Ids: List<String> = emptyList(),
    val UserId: String,
    val MediaType: String = "Audio",
)

@Serializable
private data class JellyfinPlaylistCreateResponse(val Id: String? = null)

@Serializable
private data class JellyfinPlaybackProgressInfo(
    val ItemId: String,
    val PositionTicks: Long,
    val IsPaused: Boolean,
)

private fun JellyfinItemDto.toArtist(server: PlexServer, token: String, family: EmbyFamily): Artist {
    val title = Name ?: "Unknown artist"
    val tag = ImageTags?.Primary ?: PrimaryImageTag
    val thumbUrl = when (family) {
        EmbyFamily.Emby -> artistImageUrl(server, title, token, tag, HasPrimaryImage == true)
            ?: primaryImageUrl(server, token)
        EmbyFamily.Jellyfin -> primaryImageUrl(server, token)
    }
    return Artist(
        id = Id,
        title = title,
        thumbUrl = thumbUrl,
        albumCount = AlbumCount ?: 0,
        songCount = ChildCount ?: 0,
        dateAddedMs = DateCreated.toEpochMillisOrNull(),
        genre = Genres?.firstOrNull(),
        rating = UserData?.Rating.toStarRating(),
        favorite = UserData?.IsFavorite == true,
    )
}

private fun JellyfinItemDto.toPlaybackStat(
    server: PlexServer,
    token: String,
    requirePlayCount: Boolean,
): JellyfinPlaybackStat? {
    val markedPlayed = UserData?.Played == true
    val playCount = UserData?.PlayCount?.toLong()?.takeIf { it > 0L }
        ?: if (markedPlayed) 1L
        else if (requirePlayCount) return null
        else 1L
    val lastPlayedAtMs = UserData?.LastPlayedDate.toEpochMillisOrNull()?.takeIf { it > 0L }
        ?: if (markedPlayed || requirePlayCount) 1L
        else return null
    return JellyfinPlaybackStat(
        itemId = Id,
        lastPlayedAtMs = lastPlayedAtMs,
        playCount = playCount,
        track = toTrack(server, token),
    )
}

private fun JellyfinItemDto.toAlbum(server: PlexServer, token: String): Album =
    Album(
        id = Id,
        title = Name ?: "Unknown album",
        artist = AlbumArtist ?: AlbumArtists?.firstOrNull()?.Name ?: artistNameFromPath(Path) ?: "Unknown artist",
        year = ProductionYear,
        thumbUrl = primaryImageUrl(server, token),
        dateAddedMs = DateCreated.toEpochMillisOrNull(),
        genre = Genres?.firstOrNull(),
        rating = UserData?.Rating.toStarRating(),
        favorite = UserData?.IsFavorite == true,
    )

private fun JellyfinItemDto.toTrack(server: PlexServer, token: String): Track? {
    if (Type != null && Type != "Audio") return null
    val durationMs = (RunTimeTicks ?: 0L) / 10_000L
    return Track(
        id = Id,
        title = Name ?: return null,
        artist = Artists?.firstOrNull() ?: AlbumArtist ?: "Unknown artist",
        album = Album ?: "Unknown album",
        durationMs = durationMs,
        streamUrl = "${server.uri}/Audio/$Id/stream?static=true&api_key=$token",
        downloadUrl = "${server.uri}/Items/$Id/Download?api_key=$token",
        thumbUrl = itemArtworkUrl(server, token) ?: albumImageUrl(server, token),
        year = ProductionYear,
        genre = Genres?.firstOrNull(),
        filepath = Path,
        audioCodec = MediaSources?.firstOrNull()?.Container?.uppercase(),
        bitrateKbps = MediaSources?.firstOrNull()?.Bitrate?.div(1000),
        dateAddedMs = DateCreated.toEpochMillisOrNull(),
        rating = UserData?.Rating.toStarRating(),
        parentAlbumId = AlbumId,
    )
}

private fun JellyfinItemDto.toPlaylist(server: PlexServer, token: String): Playlist =
    Playlist(
        id = Id,
        title = Name ?: "Playlist",
        trackCount = ChildCount ?: 0,
        key = Id,
        thumbUrl = primaryImageUrl(server, token),
        rating = UserData?.Rating.toStarRating(),
        favorite = UserData?.IsFavorite == true,
    )

private fun JellyfinItemDto.primaryImageUrl(server: PlexServer, token: String, itemId: String = Id): String? =
    itemImageUrl(
        server = server,
        itemId = itemId,
        token = token,
        tag = ImageTags?.Primary ?: PrimaryImageTag,
        hasPrimaryImage = HasPrimaryImage == true,
    )

private fun JellyfinItemDto.itemArtworkUrl(server: PlexServer, token: String): String? {
    val tag = ImageTags?.Primary ?: PrimaryImageTag
    if (tag.isNullOrBlank() && HasPrimaryImage != true) return null
    return primaryImageUrl(server, token)
}

private fun JellyfinItemDto.albumImageUrl(server: PlexServer, token: String): String? =
    itemImageUrl(server, AlbumId, token, AlbumPrimaryImageTag, hasPrimaryImage = AlbumId != null)
        ?: itemImageUrl(server, ParentThumbItemId, token, ParentThumbImageTag, hasPrimaryImage = ParentThumbItemId != null)

internal fun itemImageUrl(
    server: PlexServer,
    itemId: String?,
    token: String,
    tag: String?,
    hasPrimaryImage: Boolean = false,
): String? {
    if (itemId.isNullOrBlank()) return null
    val resolvedTag = tag?.takeIf { it.isNotBlank() }
    val base = "${server.uri}/Items/$itemId/Images/Primary"
    val auth = "api_key=$token"
    return when {
        resolvedTag != null -> "$base?tag=$resolvedTag&$auth"
        hasPrimaryImage -> "$base?$auth"
        else -> null
    }
}

/** Emby items-by-name artists: GET /Artists/{Name}/Images/Primary */
internal fun artistImageUrl(
    server: PlexServer,
    artistName: String,
    token: String,
    tag: String?,
    hasPrimaryImage: Boolean = false,
): String? {
    val trimmed = artistName.trim()
    if (trimmed.isBlank()) return null
    val resolvedTag = tag?.takeIf { it.isNotBlank() }
    if (resolvedTag == null && !hasPrimaryImage) return null
    val encodedName = embyArtistPathName(trimmed)
    val base = "${server.uri}/Artists/$encodedName/Images/Primary"
    val auth = "api_key=$token"
    return if (resolvedTag != null) "$base?tag=$resolvedTag&$auth" else "$base?$auth"
}

internal fun embyArtistPathName(name: String): String =
    name.replace("?", "-").replace("&", "-").replace("/", "-").replace(" ", "%20")

internal const val EmbyFamilyClientAuthorization =
    """MediaBrowser Client="Phoebe", Device="Phoebe", DeviceId="phoebe", Version="1.0.0""""

internal fun embyFamilyApiKeyFromUrl(url: String): String? =
    url.substringAfter('?', "")
        .split('&')
        .firstOrNull { param -> param.startsWith("api_key=") }
        ?.substringAfter("api_key=")
        ?.takeIf { it.isNotBlank() }

fun String.isEmbyFamilyArtworkUrl(): Boolean =
    (startsWith("http://") || startsWith("https://")) &&
        embyFamilyApiKeyFromUrl(this) != null &&
        (
            (contains("/Items/", ignoreCase = true) && contains("/Images/", ignoreCase = true)) ||
                (contains("/Artists/", ignoreCase = true) && contains("/Images/", ignoreCase = true))
            )

fun embyFamilyArtworkAuthHeaders(url: String): Map<String, String> {
    if (!url.isEmbyFamilyArtworkUrl()) return emptyMap()
    val token = embyFamilyApiKeyFromUrl(url) ?: return emptyMap()
    return mapOf(
        "X-Emby-Authorization" to EmbyFamilyClientAuthorization,
        "X-Emby-Token" to token,
    )
}

fun io.ktor.client.request.HttpRequestBuilder.applyEmbyFamilyArtworkAuth(url: String) {
    embyFamilyArtworkAuthHeaders(url).forEach { (name, value) ->
        header(name, value)
    }
}

private fun artistNameFromPath(path: String?): String? {
    val segments = path
        ?.split('/')
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    val musicIndex = segments.indexOfLast { it.equals("Music", ignoreCase = true) }
    return segments.getOrNull(musicIndex + 1)
        ?.takeIf { it.isNotBlank() && !it.contains('.') }
}

private fun Double?.toStarRating(): Float? =
    this?.div(2.0)?.toFloat()?.coerceIn(0f, 5f)?.takeIf { it > 0f }

private fun String?.toEpochMillisOrNull(): Long? {
    val trimmed = this?.trim().orEmpty()
    if (trimmed.length < 19) return null
    val year = trimmed.substringOrNull(0, 4)?.toIntOrNull() ?: return null
    val month = trimmed.substringOrNull(5, 7)?.toIntOrNull() ?: return null
    val day = trimmed.substringOrNull(8, 10)?.toIntOrNull() ?: return null
    val hour = trimmed.substringOrNull(11, 13)?.toIntOrNull() ?: return null
    val minute = trimmed.substringOrNull(14, 16)?.toIntOrNull() ?: return null
    val second = trimmed.substringOrNull(17, 19)?.toIntOrNull() ?: return null
    val offsetMinutes = when {
        trimmed.endsWith("Z") -> 0
        trimmed.length >= 25 && (trimmed[trimmed.length - 6] == '+' || trimmed[trimmed.length - 6] == '-') -> {
            val sign = if (trimmed[trimmed.length - 6] == '-') -1 else 1
            val offsetHour = trimmed.substringOrNull(trimmed.length - 5, trimmed.length - 3)?.toIntOrNull() ?: 0
            val offsetMinute = trimmed.substringOrNull(trimmed.length - 2, trimmed.length)?.toIntOrNull() ?: 0
            sign * (offsetHour * 60 + offsetMinute)
        }
        else -> 0
    }
    val epochDay = daysFromCivil(year, month, day)
    return (((epochDay * 24 + hour) * 60 + minute - offsetMinutes) * 60 + second) * 1000
}

private fun String.substringOrNull(startIndex: Int, endIndex: Int): String? =
    if (startIndex >= 0 && endIndex <= length && startIndex <= endIndex) substring(startIndex, endIndex) else null

private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    var y = year
    val m = month
    y -= if (m <= 2) 1 else 0
    val era = if (y >= 0) y else y - 399
    val eraDiv = era / 400
    val yoe = y - eraDiv * 400
    val mp = m + if (m > 2) -3 else 9
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return eraDiv.toLong() * 146097L + doe - 719468L
}
