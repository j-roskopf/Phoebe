package com.phoebe.app.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.phoebe.app.data.db.DatabaseWriteGate
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.JellyfinSyncMode
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.PlexPin
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.isEmbyFamily
import com.phoebe.app.domain.isJellyfin
import com.phoebe.app.domain.isPlex
import com.phoebe.app.domain.serverAuthToken
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.PlatformStorage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

@SingleIn(AppScope::class)
@Inject
class SessionRepository(
    private val plexClient: PlexClient,
    private val jellyfinClient: JellyfinClient,
    private val providerRegistry: MusicProviderRegistry,
    private val database: PhoebeDatabase,
    private val storage: PlatformStorage,
    private val databaseWriteGate: DatabaseWriteGate,
) {
    private val json = PhoebeDataJson
    private val mutableSession = MutableStateFlow<PlexSession?>(null)
    val session: StateFlow<PlexSession?> = mutableSession
    /**
     * Bumped on [signOut] so in-flight connection refreshes / saves started before sign-out
     * cannot re-persist a cleared session.
     */
    private var sessionGeneration: Int = 0

    suspend fun restore(refreshConnections: Boolean = true) {
        PhoebeLog.d("SessionRepository") { "restore(refreshConnections=$refreshConnections)" }
        val row = withContext(Dispatchers.Default) {
            database.sessionQueries.selectCurrent().awaitAsOneOrNull()
        }
        if (row != null) {
            mutableSession.value = row.toSession()
        } else {
            val legacy = storage.readText(LegacySessionFile) ?: return
            val parsed = runCatching {
                json.decodeFromString<PlexSession>(legacy)
            }.getOrNull() ?: return
            save(parsed)
            storage.delete(LegacySessionFile)
        }
        if (refreshConnections) refreshSelectedServerConnections()
        PhoebeLog.d("SessionRepository") {
            val s = mutableSession.value
            "restore complete → user=${s?.userName ?: "none"}, server=${s?.selectedServer?.name ?: "none"}, library=${s?.selectedLibrary?.title ?: "none"}"
        }
    }

    /** Refresh server URLs from plex.tv so we pick up LAN addresses for timeline API calls. */
    suspend fun refreshSelectedServerConnections() {
        val generation = sessionGeneration
        val current = mutableSession.value ?: return
        val selected = current.selectedServer ?: return
        if (current.token.isBlank()) return
        if (!current.isPlex()) return
        PhoebeLog.v("SessionRepository") { "refreshSelectedServerConnections for '${selected.name}'" }
        val fresh = runCatching { plexClient.servers(current.token) }
            .onFailure { error ->
                PhoebeLog.d("SessionRepository") {
                    "refreshSelectedServerConnections failed: ${error.message}"
                }
            }
            .getOrNull()
            ?.find { it.id == selected.id }
        if (fresh == null) {
            PhoebeLog.d("SessionRepository") {
                "refreshSelectedServerConnections: plex.tv did not return server ${selected.id}"
            }
            return
        }
        // plex.tv resources are a connection *list*, not a live base. Linthra/python-plexapi
        // only set baseUrl after /identity succeeds. Keep the probed uri; update advertised relays.
        val merged = fresh.copy(
            uri = selected.uri.trimEnd('/').ifBlank { fresh.uri },
            accessToken = selected.accessToken ?: fresh.accessToken,
        )
        if (merged != selected) {
            PhoebeLog.d("SessionRepository") { "updated server connections for '${merged.name}'" }
            save(current.copy(selectedServer = merged), expectedGeneration = generation)
        }
    }

    /** Persist the `/identity`-confirmed base (Linthra `PlexSession.baseUrl`). */
    suspend fun adoptProbedServerOrigin(origin: String) {
        val generation = sessionGeneration
        val current = mutableSession.value ?: return
        val server = current.selectedServer ?: return
        if (!current.isPlex()) return
        val trimmed = origin.trimEnd('/').takeIf { it.isNotBlank() } ?: return
        if (server.uri.trimEnd('/') == trimmed) return
        save(current.copy(selectedServer = server.copy(uri = trimmed)), expectedGeneration = generation)
        PhoebeLog.d("SessionRepository") { "adopted probed Plex base $trimmed" }
    }

    /** Probes server connections and caches the fastest reachable base URL for subsequent API calls. */
    suspend fun warmServerConnection() {
        val current = mutableSession.value ?: return
        val server = current.selectedServer ?: return
        if (!current.isPlex()) return
        val token = current.serverAuthToken() ?: return
        runCatching { plexClient.prepareForCatalogRequests(server, token) }
    }

    suspend fun createPin(): PlexPin = plexClient.createPin()

    suspend fun completePin(pin: PlexPin): Boolean {
        val generation = sessionGeneration
        val token = plexClient.pollPin(pin.id) ?: return false
        val session = PlexSession(token = token, userName = plexClient.userName(token), providerType = MediaProviderType.Plex)
        save(session, expectedGeneration = generation)
        return sessionGeneration == generation && mutableSession.value != null
    }

    /**
     * Exchanges an approved Plex pin for a session token, then loads the account's servers in
     * parallel with resolving the Plex username so sign-in does not wait on three serial calls.
     */
    suspend fun completePinAndListServers(pin: PlexPin): List<PlexServer>? {
        val generation = sessionGeneration
        val token = plexClient.pollPin(pin.id) ?: return null
        PhoebeLog.d("SessionRepository") { "pin complete, loading servers" }
        return coroutineScope {
            val userNameDeferred = async {
                runCatching { plexClient.userName(token) }.getOrNull() ?: "Plex listener"
            }
            val serversDeferred = async { plexClient.servers(token) }
            save(
                PlexSession(token = token, userName = userNameDeferred.await(), providerType = MediaProviderType.Plex),
                expectedGeneration = generation,
            )
            if (sessionGeneration != generation || mutableSession.value == null) return@coroutineScope null
            serversDeferred.await()
        }
    }

    suspend fun signInJellyfin(serverUrl: String, username: String, password: String): PlexServer {
        val generation = sessionGeneration
        val session = providerRegistry.adapterFor(MediaProviderType.Jellyfin)
            ?.signIn(serverUrl, username, password)
            ?: run {
                val auth = jellyfinClient.authenticate(serverUrl, username, password)
                PlexSession(
                    token = auth.token,
                    userName = auth.userName,
                    selectedServer = auth.server,
                    providerType = MediaProviderType.Jellyfin,
                    userId = auth.userId,
                )
            }
        save(session, expectedGeneration = generation)
        check(sessionGeneration == generation && mutableSession.value != null) {
            "Signed out while signing in to Jellyfin."
        }
        return session.selectedServer ?: error("Jellyfin did not return a server.")
    }

    suspend fun signInProvider(type: MediaProviderType, serverUrl: String, username: String, password: String): PlexServer {
        val generation = sessionGeneration
        val adapter = providerRegistry.adapterFor(type) ?: error("${type.name} is not available.")
        val session = adapter.signIn(serverUrl, username, password)
        save(session, expectedGeneration = generation)
        check(sessionGeneration == generation && mutableSession.value != null) {
            "Signed out while signing in to ${type.name}."
        }
        return session.selectedServer ?: error("${type.name} did not return a server.")
    }

    suspend fun startJellyfinQuickConnect(serverUrl: String): JellyfinQuickConnectResult =
        jellyfinClient.initiateQuickConnect(serverUrl)

    suspend fun completeJellyfinQuickConnect(serverUrl: String, secret: String): PlexServer {
        val generation = sessionGeneration
        val auth = jellyfinClient.authenticateQuickConnect(serverUrl, secret)
        save(
            PlexSession(
                token = auth.token,
                userName = auth.userName,
                selectedServer = auth.server,
                providerType = MediaProviderType.Jellyfin,
                userId = auth.userId,
            ),
            expectedGeneration = generation,
        )
        check(sessionGeneration == generation && mutableSession.value != null) {
            "Signed out while completing Jellyfin Quick Connect."
        }
        return auth.server
    }

    suspend fun servers(): List<PlexServer> {
        val session = mutableSession.value ?: return emptyList()
        if (!session.isPlex()) return providerRegistry.adapterFor(session)?.servers(session) ?: listOfNotNull(session.selectedServer)
        val token = session.token
        return plexClient.servers(token)
    }

    suspend fun libraries(server: PlexServer): List<MusicLibrary> {
        val generation = sessionGeneration
        val current = mutableSession.value ?: return emptyList()
        if (!current.isPlex()) {
            providerRegistry.adapterFor(current)?.let { return it.libraries(current, server) }
            if (current.isEmbyFamily()) {
                val userId = current.userId ?: return emptyList()
                return jellyfinClient.libraries(server, current.token, userId)
            }
            return emptyList()
        }
        val token = current.token
        val resolved = mutableSession.value?.selectedServer?.takeIf { it.id == server.id } ?: server
        val apiServer = runCatching { plexClient.resolveApiServer(resolved, resolved.authToken(token)) }
            .onFailure { error ->
                PhoebeLog.d("SessionRepository") { "Plex server base probe failed for '${resolved.name}': ${error.message}" }
            }
            .getOrDefault(resolved)
        persistSelectedServerIfChanged(current, apiServer, expectedGeneration = generation)
        return plexClient.musicLibraries(apiServer, apiServer.authToken(token))
    }

    suspend fun selectServer(server: PlexServer, refreshConnections: Boolean = true): PlexServer {
        PhoebeLog.d("SessionRepository") { "selectServer '${server.name}' (refreshConnections=$refreshConnections)" }
        val generation = sessionGeneration
        mutableSession.value?.let { session ->
            val resolved = if (session.isPlex()) {
                runCatching { plexClient.resolveApiServer(server, server.authToken(session.token)) }
                    .onFailure { error ->
                        PhoebeLog.d("SessionRepository") { "Plex server base probe failed for '${server.name}': ${error.message}" }
                    }
                    .getOrDefault(server)
            } else {
                server
            }
            save(session.copy(selectedServer = resolved, selectedLibrary = null), expectedGeneration = generation)
        }
        if (refreshConnections) refreshSelectedServerConnections()
        return mutableSession.value?.selectedServer ?: server
    }

    suspend fun selectLibrary(library: MusicLibrary, jellyfinSyncMode: JellyfinSyncMode? = null) {
        PhoebeLog.d("SessionRepository") { "selectLibrary '${library.title}'" }
        val generation = sessionGeneration
        mutableSession.value?.let { session ->
            save(
                session.copy(
                    selectedLibrary = library,
                    jellyfinSyncMode = jellyfinSyncMode ?: session.jellyfinSyncMode,
                ),
                expectedGeneration = generation,
            )
        }
    }

    suspend fun signOut() {
        PhoebeLog.d("SessionRepository") { "signOut" }
        sessionGeneration += 1
        val generation = sessionGeneration
        mutableSession.value = null
        // Durable clear first: legacy-file I/O can cancel/throw on app close and must not
        // leave a DB row that restore() would revive.
        databaseWriteGate.withWrite {
            if (sessionGeneration != generation) return@withWrite
            database.sessionQueries.clear()
        }
        runCatching { storage.delete(LegacySessionFile) }
    }

    private suspend fun save(
        session: PlexSession,
        expectedGeneration: Int = sessionGeneration,
    ) {
        databaseWriteGate.withWrite {
            if (sessionGeneration != expectedGeneration) return@withWrite
            if (session.token.isBlank()) {
                mutableSession.value = null
                database.sessionQueries.clear()
            } else {
                mutableSession.value = session
                persist(session)
            }
        }
    }

    private suspend fun persistSelectedServerIfChanged(
        current: PlexSession,
        server: PlexServer,
        expectedGeneration: Int = sessionGeneration,
    ) {
        val selected = current.selectedServer ?: return
        if (selected.id != server.id || selected == server) return
        save(current.copy(selectedServer = server), expectedGeneration = expectedGeneration)
    }

    private suspend fun persist(session: PlexSession) {
        val server = session.selectedServer
        val library = session.selectedLibrary
            database.sessionQueries.upsert(
                providerType = session.providerType.name,
                token = session.token,
                userName = session.userName,
                userId = session.userId,
                selectedServerId = server?.id,
                selectedServerName = server?.name,
                selectedServerUri = server?.uri,
                selectedServerOwned = server?.owned?.toDb(),
                selectedServerConnectionUris = server?.connectionUris?.toDbList(),
                selectedServerAdvertisedConnectionUris = server?.advertisedConnectionUris?.toDbList(),
                selectedServerLocalConnectionUris = server?.localConnectionUris?.toDbList(),
                selectedServerRelayConnectionUris = server?.relayConnectionUris?.toDbList(),
                selectedServerAccessToken = server?.accessToken,
                selectedServerHttpsRequired = server?.httpsRequired?.toDb(),
                selectedLibraryKey = library?.key,
                selectedLibraryTitle = library?.title,
                jellyfinSyncMode = session.jellyfinSyncMode.name,
            )
    }

    private fun com.phoebe.app.db.SessionRow.toSession(): PlexSession {
        val provider = runCatching { MediaProviderType.valueOf(providerType) }.getOrDefault(MediaProviderType.Plex)
        val serverId = selectedServerId
        val serverName = selectedServerName
        val serverUri = selectedServerUri
        val server = if (serverId != null && serverName != null && serverUri != null) {
            PlexServer(
                id = serverId,
                name = serverName,
                uri = serverUri,
                owned = (selectedServerOwned ?: 0L).toBool(),
                connectionUris = selectedServerConnectionUris.fromDbList(),
                advertisedConnectionUris = selectedServerAdvertisedConnectionUris.fromDbList(),
                localConnectionUris = selectedServerLocalConnectionUris.fromDbList(),
                relayConnectionUris = selectedServerRelayConnectionUris.fromDbList(),
                accessToken = selectedServerAccessToken,
                httpsRequired = (selectedServerHttpsRequired ?: 0L).toBool(),
            )
        } else {
            null
        }
        val libraryKey = selectedLibraryKey
        val libraryTitle = selectedLibraryTitle
        val library = if (libraryKey != null && libraryTitle != null) {
            MusicLibrary(key = libraryKey, title = libraryTitle)
        } else {
            null
        }
        return PlexSession(
            token = token,
            userName = userName,
            selectedServer = server,
            selectedLibrary = library,
            providerType = provider,
            userId = userId,
            jellyfinSyncMode = runCatching { JellyfinSyncMode.valueOf(jellyfinSyncMode) }.getOrDefault(JellyfinSyncMode.Quick),
        )
    }

    private companion object {
        const val LegacySessionFile = "session.json"
    }
}

private fun Boolean.toDb(): Long = if (this) 1L else 0L
private fun Long.toBool(): Boolean = this != 0L

private const val DbListSeparator = "\u001F"

private fun List<String>.toDbList(): String =
    filter { it.isNotBlank() }.joinToString(DbListSeparator)

private fun String?.fromDbList(): List<String> =
    this?.takeIf { it.isNotBlank() }?.split(DbListSeparator).orEmpty()
