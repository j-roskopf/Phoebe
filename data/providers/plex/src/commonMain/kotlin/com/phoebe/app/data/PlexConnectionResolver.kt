package com.phoebe.app.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.phoebe.app.data.db.DatabaseWriteGate
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.platform.NetworkIdentity
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.currentNetworkIdentity
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.platform.observeNetworkIdentity
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/**
 * Network-aware Plex origin cache: races candidate bases in parallel, persists the winner
 * per (serverId, networkFingerprint), and invalidates when the physical network changes.
 */
@SingleIn(AppScope::class)
@Inject
class PlexConnectionResolver(
    private val httpClient: HttpClient,
    private val database: PhoebeDatabase,
    private val databaseWriteGate: DatabaseWriteGate,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val resolveMutex = Mutex()
    private val memoryCache = mutableMapOf<CacheKey, String>()
    /** Last origin that worked for a server, kept across fingerprint flaps (VPN/docker ifaces). */
    private val lastGoodByServer = mutableMapOf<String, String>()
    private val mutableIdentity = MutableStateFlow(currentNetworkIdentity())
    val networkIdentity: StateFlow<NetworkIdentity> = mutableIdentity

    @Volatile
    private var lastServer: PlexServer? = null

    @Volatile
    private var lastToken: String? = null

    private var networkWatchJob: Job? = null

    init {
        networkWatchJob = scope.launch {
            observeNetworkIdentity()
                .distinctUntilChanged { a, b ->
                    a.fingerprint == b.fingerprint && a.transport == b.transport
                }
                .collect { identity ->
                    val previous = mutableIdentity.value
                    mutableIdentity.value = identity
                    if (previous.fingerprint == identity.fingerprint) return@collect
                    PhoebeLog.d("PlexConnectionResolver") {
                        "network changed fingerprint=${identity.fingerprint} transport=${identity.transport}"
                    }
                    val server = lastServer
                    val token = lastToken
                    if (server != null && !token.isNullOrBlank()) {
                        // Warm the new network's cached origin (or race if cold).
                        runCatching { resolve(server, token, deadlineMs = RemoteProbeTimeoutMs) }
                    }
                }
        }
    }

    /**
     * When false (default), always demote LAN origins. When true, only demote on
     * cellular / constrained networks — the home-LAN-first strategy.
     */
    @Volatile
    var preferLocalNetworkProvider: () -> Boolean = { false }

    fun demoteLocalOrigins(identity: NetworkIdentity = mutableIdentity.value): Boolean {
        if (!preferLocalNetworkProvider()) return true
        return identity.demotesLocalOrigins
    }

    fun cached(server: PlexServer, identity: NetworkIdentity = mutableIdentity.value): String? {
        val demote = demoteLocalOrigins(identity)
        fun accept(origin: String?): String? {
            val trimmed = origin?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
            if (demote && isLocalOnlyServerOrigin(trimmed)) return null
            return trimmed
        }
        val key = CacheKey(server.id, identity.fingerprint)
        accept(memoryCache[key])?.let { return it }
        // Fingerprint flapped but we still know what worked — use it unless policy forbids LAN.
        return accept(lastGoodByServer[server.id])
    }

    /** Non-suspending warm read used at play time when the DB has already been hydrated. */
    fun cachedOrNull(serverId: String): String? {
        val demote = demoteLocalOrigins()
        fun accept(origin: String?): String? {
            val trimmed = origin?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
            if (demote && isLocalOnlyServerOrigin(trimmed)) return null
            return trimmed
        }
        val fingerprint = mutableIdentity.value.fingerprint
        accept(memoryCache[CacheKey(serverId, fingerprint)])?.let { return it }
        return accept(lastGoodByServer[serverId])
    }

    suspend fun hydrateFromDisk(server: PlexServer) {
        val fingerprint = mutableIdentity.value.fingerprint
        val key = CacheKey(server.id, fingerprint)
        if (memoryCache.containsKey(key)) return
        val origin = withContext(Dispatchers.Default) {
            database.plexResolvedOriginQueries
                .selectOrigin(server.id, fingerprint)
                .awaitAsOneOrNull()
        }?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return
        memoryCache[key] = origin
        lastGoodByServer[server.id] = origin
        PhoebeLog.d("PlexConnectionResolver") {
            "hydrated origin=$origin server=${server.id} fingerprint=$fingerprint"
        }
    }

    /**
     * Parallel `/identity` race. Returns the first reachable base within [deadlineMs],
     * preferring a warm cache hit when present.
     */
    suspend fun resolve(
        server: PlexServer,
        token: String,
        deadlineMs: Long = RemoteProbeTimeoutMs,
    ): String? {
        lastServer = server
        lastToken = token
        val identity = mutableIdentity.value
        hydrateFromDisk(server)
        cached(server, identity)?.let { warm ->
            // Still confirm quickly if demoting local and the warm entry is LAN-only.
            if (!(demoteLocalOrigins(identity) && isLocalOnlyServerOrigin(warm))) {
                scope.launch { warmKeepAlive(warm, token) }
                return warm
            }
        }
        // Do not block callers behind another in-flight race — play must stay instant.
        if (!resolveMutex.tryLock()) {
            return cached(server, identity)
        }
        try {
            cached(server, identity)?.let { return it }
            return raceBases(server, token, deadlineMs, identity)
        } finally {
            resolveMutex.unlock()
        }
    }

    fun remember(serverId: String, origin: String) {
        val trimmed = origin.trimEnd('/').takeIf { it.isNotBlank() } ?: return
        val fingerprint = mutableIdentity.value.fingerprint
        memoryCache[CacheKey(serverId, fingerprint)] = trimmed
        lastGoodByServer[serverId] = trimmed
        scope.launch {
            persist(serverId, fingerprint, trimmed)
        }
    }

    fun forget(serverId: String, origin: String? = null) {
        val fingerprint = mutableIdentity.value.fingerprint
        val key = CacheKey(serverId, fingerprint)
        if (origin == null) {
            memoryCache.remove(key)
            lastGoodByServer.remove(serverId)
        } else {
            val trimmed = origin.trimEnd('/')
            if (memoryCache[key] == trimmed) memoryCache.remove(key)
            if (lastGoodByServer[serverId] == trimmed) lastGoodByServer.remove(serverId)
        }
    }

    fun mediaBaseUrl(server: PlexServer): String =
        cached(server) ?: lastGoodByServer[server.id] ?: server.uri.trimEnd('/')

    private suspend fun raceBases(
        server: PlexServer,
        token: String,
        deadlineMs: Long,
        identity: NetworkIdentity,
    ): String? = coroutineScope {
        val preferred = cached(server, identity)
        val demote = demoteLocalOrigins(identity)
        val candidates = server.reachableBaseUris(
            preferredFirst = preferred,
            demoteLocalOrigins = demote,
        )
        if (candidates.isEmpty()) return@coroutineScope null
        if (candidates.size == 1) {
            val only = candidates.single().trimEnd('/')
            adoptWinner(server.id, only, identity.fingerprint)
            return@coroutineScope only
        }
        val result = CompletableDeferred<String>()
        val jobs = candidates.map { base ->
            launch {
                val ok = withTimeoutOrNull(probeTimeoutMs(base, deadlineMs)) {
                    probeIdentity(base, token)
                } == true
                if (ok) result.complete(base.trimEnd('/'))
            }
        }
        val overall = minOf(
            deadlineMs.coerceAtLeast(LocalProbeTimeoutMs),
            candidates.maxOf { probeTimeoutMs(it, deadlineMs) } + 250L,
        )
        val winner = try {
            withTimeoutOrNull(overall) { result.await() }
        } finally {
            jobs.forEach { it.cancel() }
        }
        if (winner != null) {
            adoptWinner(server.id, winner, identity.fingerprint)
            scope.launch { warmKeepAlive(winner, token) }
        }
        winner
    }

    private suspend fun probeIdentity(base: String, token: String): Boolean =
        runCatching {
            val response = httpClient.get("$base/identity") {
                header("X-Plex-Token", token)
                parameter("X-Plex-Token", token)
                header("X-Plex-Product", "Phoebe")
                header("X-Plex-Version", "0.1.0")
                header("X-Plex-Client-Identifier", PlexClient.ClientIdentifier)
                header("X-Plex-Platform", "Compose Multiplatform")
                header(HttpHeaders.Accept, "application/json")
            }
            response.status.isSuccess()
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            false
        }

    private suspend fun warmKeepAlive(base: String, token: String) {
        runCatching {
            httpClient.get("$base/identity") {
                header("X-Plex-Token", token)
                parameter("X-Plex-Token", token)
                header("X-Plex-Client-Identifier", PlexClient.ClientIdentifier)
                header(HttpHeaders.Accept, "application/json")
            }
        }
    }

    private suspend fun adoptWinner(serverId: String, origin: String, fingerprint: String) {
        memoryCache[CacheKey(serverId, fingerprint)] = origin
        lastGoodByServer[serverId] = origin
        persist(serverId, fingerprint, origin)
        PhoebeLog.d("PlexConnectionResolver") {
            "resolved origin=$origin server=$serverId fingerprint=$fingerprint"
        }
    }

    private suspend fun persist(serverId: String, fingerprint: String, origin: String) {
        databaseWriteGate.withWrite {
            database.plexResolvedOriginQueries.upsertOrigin(
                serverId = serverId,
                networkFingerprint = fingerprint,
                origin = origin,
                updatedAtMs = currentTimeMs(),
            )
        }
    }

    private fun probeTimeoutMs(base: String, remoteTimeoutMs: Long): Long =
        if (isLocalOnlyServerOrigin(base)) {
            minOf(LocalProbeTimeoutMs, remoteTimeoutMs)
        } else {
            remoteTimeoutMs
        }

    private data class CacheKey(
        val serverId: String,
        val fingerprint: String,
    )

    companion object {
        const val LocalProbeTimeoutMs = 700L
        const val RemoteProbeTimeoutMs = 2_500L
        const val PlayResolveDeadlineMs = 700L
    }
}
