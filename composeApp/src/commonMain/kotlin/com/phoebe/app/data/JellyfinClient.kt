package com.phoebe.app.data

import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.TrackMetadataUpdate
import com.phoebe.app.platform.PhoebeLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.TimeSource

open class JellyfinClient(
    private val httpClient: HttpClient,
    private val family: EmbyFamily = EmbyFamily.Jellyfin,
) {
    data class JellyfinItemPage<T>(
        val items: List<T>,
        val total: Int,
        val pageIndex: Int,
        val pageSize: Int = QuickCatalogPageSize,
    )

    private val fullSyncPageSize: Int
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
                owned = true,
            ),
        )
    }

    suspend fun libraries(server: PlexServer, token: String, userId: String): List<MusicLibrary> {
        val path = if (family == EmbyFamily.Emby) "/Users/$userId/Views" else "/UserViews"
        val response: JellyfinItemsResponse = httpClient.get("${server.uri}$path") {
            jellyfinAuth(token)
            if (family != EmbyFamily.Emby) {
                parameter("userId", userId)
            }
            parameter("includeExternalContent", false)
        }.jellyfinBody("library lookup")
        return response.Items
            .filter { it.CollectionType.equals("music", ignoreCase = true) || it.Type == "CollectionFolder" }
            .map { MusicLibrary(key = it.Id, title = it.Name ?: "Music") }
    }

    suspend fun artists(server: PlexServer, library: MusicLibrary, token: String, userId: String): List<Artist> {
        val scoped = pagedItems(server, token, "/Artists/AlbumArtists", "albumArtists scoped:${library.key}") {
            parameter("userId", userId)
            parameter("parentId", library.key)
            parameter("recursive", true)
            parameter("fields", JellyfinFields)
            parameter("enableUserData", true)
            parameter("sortBy", "SortName")
        }.Items.map { it.toArtist(server, token) }
        if (scoped.isNotEmpty()) return scoped
        return pagedItems(server, token, "/Artists/AlbumArtists", "albumArtists unscoped") {
            parameter("userId", userId)
            parameter("recursive", true)
            parameter("fields", JellyfinFields)
            parameter("enableUserData", true)
            parameter("sortBy", "SortName")
        }.Items.map { it.toArtist(server, token) }
    }

    suspend fun artistPage(server: PlexServer, library: MusicLibrary, token: String, userId: String, pageIndex: Int): JellyfinItemPage<Artist> {
        val pageSize = QuickCatalogPageSize
        val scoped = pagedItems(
            server = server,
            token = token,
            path = "/Artists/AlbumArtists",
            label = "albumArtists scoped:${library.key} page:$pageIndex",
            startIndex = pageIndex * pageSize,
            limit = pageSize,
            maxPages = 1,
        ) {
            parameter("userId", userId)
            parameter("parentId", library.key)
            parameter("recursive", true)
            parameter("fields", JellyfinFields)
            parameter("enableUserData", true)
            parameter("sortBy", "SortName")
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
                limit = pageSize,
                maxPages = 1,
            ) {
                parameter("userId", userId)
                parameter("recursive", true)
                parameter("fields", JellyfinFields)
                parameter("enableUserData", true)
                parameter("sortBy", "SortName")
            }
        }
        return JellyfinItemPage(page.Items.map { it.toArtist(server, token) }, page.TotalRecordCount ?: page.Items.size, pageIndex, pageSize)
    }

    suspend fun albums(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        userId: String,
        onPage: suspend (List<Album>) -> Unit = {},
    ): List<Album> {
        val scoped = pagedItems(
            server = server,
            token = token,
            path = "/Items",
            label = "albums scoped:${library.key}",
            onPage = { items, _ -> onPage(items.map { it.toAlbum(server, token) }) },
        ) {
            parameter("userId", userId)
            parameter("parentId", library.key)
            parameter("recursive", true)
            parameter("includeItemTypes", "MusicAlbum")
            parameter("fields", JellyfinFields)
            parameter("enableUserData", true)
            parameter("sortBy", "AlbumArtist,SortName")
        }.Items.map { it.toAlbum(server, token) }
        if (scoped.isNotEmpty()) return scoped
        return pagedItems(
            server = server,
            token = token,
            path = "/Items",
            label = "albums unscoped",
            onPage = { items, _ -> onPage(items.map { it.toAlbum(server, token) }) },
        ) {
            parameter("userId", userId)
            parameter("recursive", true)
            parameter("includeItemTypes", "MusicAlbum")
            parameter("fields", JellyfinFields)
            parameter("enableUserData", true)
            parameter("sortBy", "AlbumArtist,SortName")
        }.Items.map { it.toAlbum(server, token) }
    }

    suspend fun albumsForArtist(
        server: PlexServer,
        library: MusicLibrary,
        token: String,
        userId: String,
        artistId: String,
    ): List<Album> = pagedItems(
        server = server,
        token = token,
        path = "/Items",
        label = "albums for artist $artistId",
    ) {
        parameter("userId", userId)
        parameter("parentId", library.key)
        parameter("recursive", true)
        parameter("includeItemTypes", "MusicAlbum")
        parameter("fields", JellyfinFields)
        parameter("enableUserData", true)
        parameter("ArtistIds", artistId)
    }.Items.map { it.toAlbum(server, token) }

    suspend fun albumPage(server: PlexServer, library: MusicLibrary, token: String, userId: String, pageIndex: Int): JellyfinItemPage<Album> {
        val pageSize = QuickCatalogPageSize
        val scoped = pagedItems(
            server = server,
            token = token,
            path = "/Items",
            label = "albums scoped:${library.key} page:$pageIndex",
            startIndex = pageIndex * pageSize,
            limit = pageSize,
            maxPages = 1,
        ) {
            parameter("userId", userId)
            parameter("parentId", library.key)
            parameter("recursive", true)
            parameter("includeItemTypes", "MusicAlbum")
            parameter("fields", JellyfinFields)
            parameter("enableUserData", true)
            parameter("sortBy", "AlbumArtist,SortName")
        }
        val page = if ((scoped.TotalRecordCount ?: scoped.Items.size) > 0 || pageIndex > 0) {
            scoped
        } else {
            pagedItems(
                server = server,
                token = token,
                path = "/Items",
                label = "albums unscoped page:$pageIndex",
                startIndex = pageIndex * pageSize,
                limit = pageSize,
                maxPages = 1,
            ) {
                parameter("userId", userId)
                parameter("recursive", true)
                parameter("includeItemTypes", "MusicAlbum")
                parameter("fields", JellyfinFields)
                parameter("enableUserData", true)
                parameter("sortBy", "AlbumArtist,SortName")
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
        onPage: suspend (List<Track>) -> Unit = {},
    ): List<Track> =
        tracksForQuery(server, token, userId, onPage, includeMediaDetails) {
            parameter("parentId", library.key)
            parameter("recursive", true)
            parameter("includeItemTypes", "Audio")
            parameter("sortBy", "AlbumArtist,Album,ParentIndexNumber,IndexNumber,SortName")
        }.ifEmpty {
            tracksForQuery(server, token, userId, onPage, includeMediaDetails) {
                parameter("recursive", true)
                parameter("includeItemTypes", "Audio")
                parameter("sortBy", "AlbumArtist,Album,ParentIndexNumber,IndexNumber,SortName")
            }
        }

    suspend fun trackPage(server: PlexServer, library: MusicLibrary, token: String, userId: String, pageIndex: Int): JellyfinItemPage<Track> {
        val pageSize = QuickCatalogPageSize
        val scoped = pagedItems(
            server = server,
            token = token,
            path = "/Items",
            label = "tracks scoped:${library.key} page:$pageIndex",
            startIndex = pageIndex * pageSize,
            limit = pageSize,
            maxPages = 1,
        ) {
            parameter("userId", userId)
            parameter("parentId", library.key)
            parameter("recursive", true)
            parameter("includeItemTypes", "Audio")
            parameter("fields", JellyfinFastTrackFields)
            parameter("enableUserData", true)
            parameter("sortBy", "AlbumArtist,Album,ParentIndexNumber,IndexNumber,SortName")
        }
        val page = if ((scoped.TotalRecordCount ?: scoped.Items.size) > 0 || pageIndex > 0) {
            scoped
        } else {
            pagedItems(
                server = server,
                token = token,
                path = "/Items",
                label = "tracks unscoped page:$pageIndex",
                startIndex = pageIndex * pageSize,
                limit = pageSize,
                maxPages = 1,
            ) {
                parameter("userId", userId)
                parameter("recursive", true)
                parameter("includeItemTypes", "Audio")
                parameter("fields", JellyfinFastTrackFields)
                parameter("enableUserData", true)
                parameter("sortBy", "AlbumArtist,Album,ParentIndexNumber,IndexNumber,SortName")
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
    ): List<JellyfinPlaybackStat> =
        items(server, token) {
            parameter("userId", userId)
            parameter("parentId", library.key)
            parameter("recursive", true)
            parameter("includeItemTypes", "Audio")
            parameter("fields", JellyfinHistoryFields)
            parameter("enableUserData", true)
            parameter("sortBy", "DatePlayed")
            parameter("sortOrder", "Descending")
            parameter("startIndex", start)
            parameter("limit", size)
        }.Items.mapNotNull { item ->
            val lastPlayedAtMs = item.UserData?.LastPlayedDate.toEpochMillisOrNull() ?: return@mapNotNull null
            val playCount = item.UserData?.PlayCount?.toLong()?.takeIf { it > 0L } ?: return@mapNotNull null
            JellyfinPlaybackStat(
                itemId = item.Id,
                lastPlayedAtMs = lastPlayedAtMs,
                playCount = playCount,
            )
        }

    suspend fun albumTracks(server: PlexServer, album: Album, token: String, userId: String): List<Track> =
        tracksForQuery(server, token, userId) {
            parameter("parentId", album.id)
            parameter("recursive", true)
            parameter("includeItemTypes", "Audio")
            parameter("sortBy", "ParentIndexNumber,IndexNumber,SortName")
        }

    suspend fun playlists(server: PlexServer, library: MusicLibrary, token: String, userId: String): List<Playlist> {
        val scoped = pagedItems(server, token, "/Items", "playlists scoped:${library.key}") {
            parameter("userId", userId)
            parameter("parentId", library.key)
            parameter("recursive", true)
            parameter("includeItemTypes", "Playlist")
            parameter("fields", JellyfinFields)
            parameter("enableUserData", true)
        }.Items.map { it.toPlaylist(server, token) }
        val regular = scoped.ifEmpty {
            pagedItems(server, token, "/Items", "playlists unscoped") {
                parameter("userId", userId)
                parameter("recursive", true)
                parameter("includeItemTypes", "Playlist")
                parameter("fields", JellyfinFields)
                parameter("enableUserData", true)
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
            parameter("parentId", playlist.id)
            parameter("recursive", true)
            parameter("includeItemTypes", "Audio")
        }
    }

    suspend fun instantMix(server: PlexServer, token: String, userId: String, itemId: String): List<Track> =
        httpClient.get("${server.uri}/Items/$itemId/InstantMix") {
            jellyfinAuth(token)
            parameter("userId", userId)
            parameter("fields", JellyfinFields)
            parameter("enableUserData", true)
        }.body<JellyfinItemsResponse>().Items.mapNotNull { it.toTrack(server, token) }

    suspend fun initiateQuickConnect(serverUrl: String): JellyfinQuickConnectResult {
        val base = serverUrl.trimEnd('/')
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
        val base = serverUrl.trimEnd('/')
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
            library.key.takeIf { it.isNotBlank() }?.let { parameter("parentId", it) }
            parameter("recursive", true)
            parameter("includeItemTypes", "Audio")
            parameter("isFavorite", true)
            parameter("sortBy", "SortName")
        }

    private suspend fun favoriteTrackCount(server: PlexServer, library: MusicLibrary, token: String, userId: String): Int =
        items(server, token) {
            library.key.takeIf { it.isNotBlank() }?.let { parameter("parentId", it) }
            parameter("userId", userId)
            parameter("recursive", true)
            parameter("includeItemTypes", "Audio")
            parameter("isFavorite", true)
            parameter("limit", 0)
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
            parameter("userId", userId)
            parameter("ids", itemIds.joinToString(","))
        }
        if (!response.status.isSuccess()) error("Jellyfin playlist add failed (${response.status.value}): ${response.bodyAsText().take(200)}")
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
                parameter("likes", true)
                parameter("rating", (rating * 2f).coerceIn(0f, 10f))
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
                parameter("likes", true)
                parameter("rating", (rating * 2f).coerceIn(0f, 10f))
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

    private suspend fun tracksForQuery(
        server: PlexServer,
        token: String,
        userId: String,
        onPage: suspend (List<Track>) -> Unit = {},
        includeMediaDetails: Boolean = true,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): List<Track> = pagedItems(
        server = server,
        token = token,
        path = "/Items",
        label = "tracks",
        onPage = { items, _ -> onPage(items.mapNotNull { it.toTrack(server, token) }) },
    ) {
        parameter("userId", userId)
        parameter("fields", if (includeMediaDetails) JellyfinFields else JellyfinFastTrackFields)
        parameter("enableUserData", true)
        block()
    }.Items.mapNotNull { it.toTrack(server, token) }

    private suspend fun pagedItems(
        server: PlexServer,
        token: String,
        path: String,
        label: String,
        startIndex: Int = 0,
        limit: Int = fullSyncPageSize,
        maxPages: Int? = null,
        onPage: suspend (List<JellyfinItemDto>, Int?) -> Unit = { _, _ -> },
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): JellyfinItemsResponse {
        val all = mutableListOf<JellyfinItemDto>()
        var start = startIndex
        var total: Int? = null
        var pagesLoaded = 0
        while (true) {
            PhoebeLog.d("JellyfinClient") { "pagedItems $label request start=$start limit=$limit" }
            val requestMark = TimeSource.Monotonic.markNow()
            val page = items(server, token, path) {
                parameter("startIndex", start)
                parameter("limit", limit)
                block()
            }
            val requestElapsedMs = requestMark.elapsedNow().inWholeMilliseconds
            if (total == null) {
                total = page.TotalRecordCount
                PhoebeLog.d("JellyfinClient") {
                    "pagedItems $label total=${total ?: "unknown"} firstPage=${page.Items.size}"
                }
            }
            PhoebeLog.d("JellyfinClient") {
                "pagedItems $label page=${pagesLoaded + 1} start=$start limit=$limit loaded=${page.Items.size} total=${total ?: "unknown"} requestDecodeMs=$requestElapsedMs"
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

    private suspend fun items(
        server: PlexServer,
        token: String,
        path: String = "/Items",
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): JellyfinItemsResponse = httpClient.get("${server.uri}$path") {
        jellyfinAuth(token)
        parameter("enableImages", true)
        parameter("imageTypeLimit", 1)
        parameter("enableImageTypes", "Primary")
        block()
    }.body()

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
            parameter("secret", secret)
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
        val base = serverUrl.trimEnd('/')
        if (family != EmbyFamily.Emby) return base
        return if (base.endsWith("/emby", ignoreCase = true)) base else "$base/emby"
    }

    companion object {
        const val JellyfinLikedSongsPlaylistId = "jellyfin:liked-songs"
        const val QuickCatalogPageSize = 100
        const val JellyfinPageSize = 500
        const val EmbyPageSize = 1000
        private const val JellyfinFields =
            "Genres,Path,MediaSources,UserData,DateCreated,ProductionYear,AlbumArtist,AlbumArtists,ParentId,PrimaryImageTag,AlbumPrimaryImageTag,ParentThumbItemId,ParentThumbImageTag"
        private const val JellyfinFastTrackFields =
            "Genres,UserData,DateCreated,ProductionYear,AlbumArtist,ParentId,PrimaryImageTag,AlbumPrimaryImageTag,ParentThumbItemId,ParentThumbImageTag"
        private const val JellyfinHistoryFields =
            "UserData,DateCreated,ProductionYear,AlbumArtist,AlbumArtists,ParentId,Album,AlbumId,PrimaryImageTag,AlbumPrimaryImageTag"
    }
}

class EmbyClient(httpClient: HttpClient) : JellyfinClient(httpClient, EmbyFamily.Emby)

enum class EmbyFamily(val catalogPrefix: String, val displayName: String) {
    Jellyfin("jellyfin", "Jellyfin"),
    Emby("emby", "Emby"),
}

enum class JellyfinPlaybackEvent { Start, Progress, Stop }

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

private fun JellyfinItemDto.toArtist(server: PlexServer, token: String): Artist =
    Artist(
        id = Id,
        title = Name ?: "Unknown artist",
        thumbUrl = itemImageUrl(server, Id, token, ImageTags?.Primary ?: PrimaryImageTag),
        albumCount = AlbumCount ?: 0,
        songCount = ChildCount ?: 0,
        dateAddedMs = DateCreated.toEpochMillisOrNull(),
        genre = Genres?.firstOrNull(),
        rating = UserData?.Rating.toStarRating(),
        favorite = UserData?.IsFavorite == true,
    )

private fun JellyfinItemDto.toAlbum(server: PlexServer, token: String): Album =
    Album(
        id = Id,
        title = Name ?: "Unknown album",
        artist = AlbumArtist ?: AlbumArtists?.firstOrNull()?.Name ?: artistNameFromPath(Path) ?: "Unknown artist",
        year = ProductionYear,
        thumbUrl = itemImageUrl(server, Id, token, ImageTags?.Primary ?: PrimaryImageTag),
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
        thumbUrl = itemImageUrl(server, Id, token, ImageTags?.Primary ?: PrimaryImageTag) ?: albumImageUrl(server, token),
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
        thumbUrl = itemImageUrl(server, Id, token, ImageTags?.Primary ?: PrimaryImageTag),
        rating = UserData?.Rating.toStarRating(),
        favorite = UserData?.IsFavorite == true,
    )

private fun JellyfinItemDto.albumImageUrl(server: PlexServer, token: String): String? =
    itemImageUrl(server, AlbumId, token, AlbumPrimaryImageTag)
        ?: itemImageUrl(server, ParentThumbItemId, token, ParentThumbImageTag)

private fun itemImageUrl(server: PlexServer, itemId: String?, token: String, tag: String?): String? =
    if (itemId.isNullOrBlank() || tag.isNullOrBlank()) null else "${server.uri}/Items/$itemId/Images/Primary?tag=$tag&api_key=$token"

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
