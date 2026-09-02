package com.phoebe.app.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.phoebe.app.data.db.DatabaseWriteGate
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.platform.NetworkIdentity
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.currentNetworkIdentity
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.platform.logDetail
import com.phoebe.app.platform.observeNetworkIdentity
import com.phoebe.app.platform.withNetworkTimeoutOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile

/**
 * Network-aware Plex origin cache: races candidate bases in parallel, picks the
 * best-ranked success (local → direct remote → relay), persists non-relay winners
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
    private val coalesceMutex = Mutex()
    private var inFlightRace: Deferred<String?>? = null
    private val memoryCache = mutableMapOf<CacheKey, String>()
    /**
     * Origins confirmed with `/identity` in this process, per network.
     *
     * The fingerprint is part of the key on purpose. A direct-remote `plex.direct:32400` that
     * answered over the home LAN (NAT hairpin) is dead the moment the phone moves to cellular;
     * a process-wide "already probed" set would hand that origin straight back with no probe.
     */
    private val probedOk = mutableSetOf<ProbedOrigin>()
    /** Last origin that worked for a server on a given network. */
    private val lastGoodByServer = mutableMapOf<CacheKey, String>()
    /** Session-only relay winners — never written to disk. */
    private val sessionRelayByServer = mutableMapOf<String, String>()
    /** Dropped until [remember] / a race adopts them again, so hydrate cannot reload a dead row. */
    private val forgottenOrigins = mutableSetOf<ForgottenOrigin>()
    private val forgottenServers = mutableSetOf<String>()
    private val mutableIdentity = MutableStateFlow(currentNetworkIdentity())
    val networkIdentity: StateFlow<NetworkIdentity> = mutableIdentity

    private val mutableProbedOrigin = MutableStateFlow<String?>(null)
    /** `/identity`-confirmed base. Coil/API bind to this; unprobed disk/LAN never appears here. */
    val probedOrigin: StateFlow<String?> = mutableProbedOrigin.asStateFlow()

    @Volatile
    private var lastServer: PlexServer? = null

    @Volatile
    private var lastToken: String? = null

    private var networkWatchJob: Job? = null

    private val identityJson = Json { ignoreUnknownKeys = true }

    init {
        networkWatchJob = scope.launch {
            observeNetworkIdentity()
                .distinctUntilChanged { a, b ->
                    a.fingerprint == b.fingerprint && a.transport == b.transport
                }
                .collect { identity ->
                    if (networkIdentityPinnedForTest) return@collect
                    val previous = mutableIdentity.value
                    mutableIdentity.value = identity
                    if (previous.fingerprint == identity.fingerprint) return@collect
                    PhoebeLog.d("PlexConnectionResolver") {
                        "network changed fingerprint=${identity.fingerprint} transport=${identity.transport}"
                    }
                    onNetworkChanged(previous.fingerprint, identity)
                }
        }
    }

    /**
     * When true (default), only demote LAN on cellular / constrained networks.
     * When false, always demote LAN (remote-first escape hatch).
     */
    @Volatile
    var preferLocalNetworkProvider: () -> Boolean = { true }

    @Volatile
    private var networkIdentityPinnedForTest = false

    /** Test-only: pin network identity so LAN demotion does not depend on the host machine. */
    fun useNetworkIdentityForTest(identity: NetworkIdentity) {
        networkIdentityPinnedForTest = true
        mutableIdentity.value = identity
    }

    /**
     * Test-only: drive the same transition the platform network watcher would, so a
     * Wi-Fi -> cellular handoff can be exercised without a real radio.
     */
    suspend fun applyNetworkIdentityForTest(identity: NetworkIdentity) {
        networkIdentityPinnedForTest = true
        val previous = mutableIdentity.value
        mutableIdentity.value = identity
        if (previous.fingerprint == identity.fingerprint) return
        onNetworkChanged(previous.fingerprint, identity)
    }

    fun demoteLocalOrigins(identity: NetworkIdentity = mutableIdentity.value): Boolean {
        if (!preferLocalNetworkProvider()) return true
        if (identity.demotesLocalOrigins) return true
        val server = lastServer ?: return false
        return identity.shouldSkipAdvertisedLan(server)
    }

    /**
     * A different physical network invalidates every in-process probe result: the only thing a
     * successful `/identity` proved is that the origin was reachable from where we were then.
     * Drop the old network's state, cancel any race still running against it, then re-resolve.
     */
    private suspend fun onNetworkChanged(previousFingerprint: String, identity: NetworkIdentity) {
        coalesceMutex.withLock {
            inFlightRace?.cancel()
            inFlightRace = null
        }
        probedOk.removeAll { it.fingerprint == previousFingerprint }
        lastGoodByServer.keys.removeAll { it.fingerprint == previousFingerprint }
        memoryCache.keys.removeAll { it.fingerprint == previousFingerprint }
        // A relay reachable from the old network is not evidence about this one.
        sessionRelayByServer.clear()
        // Nothing is confirmed on this network yet; stop artwork/API binding to the old base.
        if (mutableProbedOrigin.value != null) {
            mutableProbedOrigin.value = null
            ArtworkOriginHolder.update(null)
        }
        val server = lastServer
        val token = lastToken
        if (server != null && !token.isNullOrBlank()) {
            runCatching { resolveFresh(server, token, deadlineMs = RemoteProbeTimeoutMs) }
        }
    }

    fun cached(server: PlexServer, identity: NetworkIdentity = mutableIdentity.value): String? {
        val demote = demoteLocalOrigins(identity)
        val fingerprint = identity.fingerprint
        fun accept(origin: String?): String? {
            val trimmed = origin?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
            if (!server.containsConnectionOrigin(trimmed)) return null
            if (demote && isLocalOnlyServerOrigin(trimmed)) return null
            if (ForgottenOrigin(server.id, trimmed) in forgottenOrigins) return null
            // Relays are never treated as warm without a probe on *this* network.
            if (isPlexRelayOrigin(trimmed, server) &&
                ProbedOrigin(server.id, fingerprint, trimmed) !in probedOk
            ) {
                return null
            }
            return trimmed
        }
        val key = CacheKey(server.id, fingerprint)
        accept(memoryCache[key])?.let { return it }
        accept(sessionRelayByServer[server.id])?.let { return it }
        return accept(lastGoodByServer[key])
    }

    /**
     * Linthra/python-plexapi: the base that already answered `/identity` this process.
     * Unprobed disk/LAN hits are not a session `baseUrl`.
     */
    fun liveProbedOrigin(server: PlexServer, identity: NetworkIdentity = mutableIdentity.value): String? {
        val origin = cached(server, identity) ?: return null
        return origin.takeIf { isProbed(server.id, origin) }
    }

    fun isProbed(serverId: String, origin: String): Boolean =
        ProbedOrigin(serverId, mutableIdentity.value.fingerprint, origin.trimEnd('/')) in probedOk

    fun lastAuthToken(): String? = lastToken

    /** Non-suspending warm read used at play time when the DB has already been hydrated. */
    fun cachedOrNull(serverId: String): String? {
        val server = lastServer?.takeIf { it.id == serverId }
        val demote = demoteLocalOrigins()
        val fingerprint = mutableIdentity.value.fingerprint
        fun accept(origin: String?): String? {
            val trimmed = origin?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
            if (server != null && !server.containsConnectionOrigin(trimmed)) return null
            if (demote && isLocalOnlyServerOrigin(trimmed)) return null
            if (ForgottenOrigin(serverId, trimmed) in forgottenOrigins) return null
            if (isPlexRelayOrigin(trimmed, server) &&
                ProbedOrigin(serverId, fingerprint, trimmed) !in probedOk
            ) {
                return null
            }
            return trimmed
        }
        val key = CacheKey(serverId, fingerprint)
        accept(memoryCache[key])?.let { return it }
        accept(sessionRelayByServer[serverId])?.let { return it }
        return accept(lastGoodByServer[key])
    }

    suspend fun hydrateFromDisk(server: PlexServer) {
        lastServer = server
        if (server.id in forgottenServers) return
        val fingerprint = mutableIdentity.value.fingerprint
        val key = CacheKey(server.id, fingerprint)
        if (memoryCache.containsKey(key)) return
        val origin = withContext(Dispatchers.Default) {
            database.plexResolvedOriginQueries
                .selectOrigin(server.id, fingerprint)
                .awaitAsOneOrNull()
        }?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return
        if (ForgottenOrigin(server.id, origin) in forgottenOrigins) return
        // Never hydrate a relay IP from disk — plex.tv rotates those between launches.
        // Also drop hosts that are no longer in the current advertised set (stale 8443
        // IPs used to sneak through when relayConnectionUris listed a *different* relay).
        if (isPlexRelayOrigin(origin, server) || !server.containsConnectionOrigin(origin)) {
            PhoebeLog.d("PlexConnectionResolver") {
                "skip hydrate origin=$origin server=${server.id}"
            }
            scope.launch {
                databaseWriteGate.withWrite {
                    database.plexResolvedOriginQueries.deleteOrigin(
                        serverId = server.id,
                        networkFingerprint = fingerprint,
                        origin = origin,
                    )
                }
            }
            return
        }
        memoryCache[key] = origin
        lastGoodByServer[key] = origin
        PhoebeLog.d("PlexConnectionResolver") {
            "hydrated origin=$origin server=${server.id} fingerprint=$fingerprint"
        }
    }

    /**
     * Parallel `/identity` race. Returns a warm **probed** non-relay cache hit immediately,
     * otherwise races candidates and picks the best-ranked success.
     *
     * Unprobed relay disk/memory hits are ignored — callers must wait for [resolveFresh]
     * or this race rather than stamp a dead 8443 host into artwork/playback.
     */
    suspend fun resolve(
        server: PlexServer,
        token: String,
        deadlineMs: Long = RemoteProbeTimeoutMs,
    ): String? = resolveFresh(server, token, deadlineMs)

    /**
     * Confirm the cached origin with `/identity`, then race if it is dead. Safe to call from
     * a background dispatcher during playback — not from Android AppState on Main.
     *
     * Concurrent callers share one in-flight race. After a miss, the next caller
     * races again so Home is not stuck on placeholders forever.
     */
    suspend fun resolveFresh(
        server: PlexServer,
        token: String,
        deadlineMs: Long = RemoteProbeTimeoutMs,
    ): String? {
        lastServer = server
        lastToken = token
        val identity = mutableIdentity.value
        hydrateFromDisk(server)
        liveProbedOrigin(server, identity)?.let { return it }
        return sharedRace(server, token, deadlineMs, identity)
    }

    fun remember(serverId: String, origin: String) {
        val trimmed = origin.trimEnd('/').takeIf { it.isNotBlank() } ?: return
        val fingerprint = mutableIdentity.value.fingerprint
        if (ProbedOrigin(serverId, fingerprint, trimmed) in probedOk &&
            mutableProbedOrigin.value == trimmed
        ) {
            forgottenOrigins.remove(ForgottenOrigin(serverId, trimmed))
            return
        }
        forgottenServers.remove(serverId)
        forgottenOrigins.remove(ForgottenOrigin(serverId, trimmed))
        val server = lastServer?.takeIf { it.id == serverId }
        if (keepEstablishedOrigin(server, trimmed, mutableIdentity.value) != null) {
            noteWorkingOrigin(serverId, fingerprint, trimmed)
            return
        }
        memoryCache[CacheKey(serverId, fingerprint)] = trimmed
        lastGoodByServer[CacheKey(serverId, fingerprint)] = trimmed
        markProbed(serverId, fingerprint, trimmed)
        if (isPlexRelayOrigin(trimmed, server)) {
            sessionRelayByServer[serverId] = trimmed
            return
        }
        sessionRelayByServer.remove(serverId)
        scope.launch {
            persist(serverId, fingerprint, trimmed)
        }
    }

    fun forget(serverId: String, origin: String? = null) {
        val fingerprint = mutableIdentity.value.fingerprint
        val key = CacheKey(serverId, fingerprint)
        if (origin == null) {
            forgottenServers.add(serverId)
            forgottenOrigins.removeAll { it.serverId == serverId }
            memoryCache.remove(key)
            lastGoodByServer.keys.removeAll { it.serverId == serverId }
            sessionRelayByServer.remove(serverId)
            probedOk.removeAll { it.serverId == serverId }
            if (mutableProbedOrigin.value != null) {
                mutableProbedOrigin.value = null
                ArtworkOriginHolder.update(null)
            }
            scope.launch {
                databaseWriteGate.withWrite {
                    database.plexResolvedOriginQueries.deleteForServer(serverId)
                }
            }
        } else {
            val trimmed = origin.trimEnd('/')
            forgottenOrigins.add(ForgottenOrigin(serverId, trimmed))
            probedOk.remove(ProbedOrigin(serverId, fingerprint, trimmed))
            if (mutableProbedOrigin.value == trimmed) {
                mutableProbedOrigin.value = null
                if (ArtworkOriginHolder.liveOrigin == trimmed) ArtworkOriginHolder.update(null)
            }
            if (memoryCache[key] == trimmed) memoryCache.remove(key)
            if (lastGoodByServer[key] == trimmed) lastGoodByServer.remove(key)
            if (sessionRelayByServer[serverId] == trimmed) sessionRelayByServer.remove(serverId)
            scope.launch {
                databaseWriteGate.withWrite {
                    database.plexResolvedOriginQueries.deleteOrigin(
                        serverId = serverId,
                        networkFingerprint = fingerprint,
                        origin = trimmed,
                    )
                }
            }
        }
    }

    fun mediaBaseUrl(server: PlexServer): String =
        liveProbedOrigin(server) ?: server.uri.trimEnd('/')

    private suspend fun sharedRace(
        server: PlexServer,
        token: String,
        deadlineMs: Long,
        identity: NetworkIdentity,
    ): String? {
        liveProbedOrigin(server, identity)?.let { return it }
        val job = coalesceMutex.withLock {
            inFlightRace?.takeIf { it.isActive }?.let { return@withLock it }
            scope.async {
                resolveMutex.withLock {
                    liveProbedOrigin(server, identity)?.let { return@withLock it }
                    raceBases(server, token, deadlineMs, identity)
                }
            }.also { inFlightRace = it }
        }
        return try {
            job.await()
        } catch (cancelled: CancellationException) {
            // A network change cancels the shared race. That is not this caller's cancellation,
            // so surface it as "no origin yet" rather than tearing down whoever was waiting.
            currentCoroutineContext().ensureActive()
            PhoebeLog.d("PlexConnectionResolver") { "identity race cancelled: ${cancelled.message}" }
            null
        } finally {
            coalesceMutex.withLock {
                if (inFlightRace === job && !job.isActive) inFlightRace = null
            }
        }
    }

    /**
     * Parallel `/identity` race, python-plexapi `_chooseConnection` semantics: probe every
     * candidate, then adopt the **best-ranked** origin that answered — not the first responder.
     *
     * Adoption happens exactly once. Publishing each improvement as it arrived made artwork and
     * the play queue rebase to a relay and then again to LAN a few hundred milliseconds later.
     *
     * Starts are staggered by rank (chromatix-app does the same with 300ms) so a healthy LAN
     * server answers before a relay handshake has even begun, and the race can stop early.
     */
    private suspend fun raceBases(
        server: PlexServer,
        token: String,
        deadlineMs: Long,
        identity: NetworkIdentity,
    ): String? = coroutineScope {
        val preferred = cached(server, identity)
        val demote = demoteLocalOrigins(identity)
        val ranked = server.reachableBaseUris(
            preferredFirst = preferred,
            demoteLocalOrigins = demote,
        )
        val candidates = if (demote) {
            ranked.filterNot(::isLocalOnlyServerOrigin).ifEmpty { ranked }
        } else {
            ranked
        }.map { it.trimEnd('/') }.distinct()
        if (candidates.isEmpty()) {
            PhoebeLog.d("PlexConnectionResolver") {
                "identity race has no candidates server=${server.id}"
            }
            return@coroutineScope null
        }
        val preference = compareBy<String>(
            { locationRank(it, server, demote) },
            { connectionPriority(it, demote, server) },
            { candidates.indexOf(it).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE },
        )
        val ordered = candidates.sortedWith(preference)
        val classified = ordered.joinToString { origin ->
            val kind = when {
                isLocalOnlyServerOrigin(origin) -> "lan"
                isPlexRelayOrigin(origin, server) -> "relay"
                else -> "remote"
            }
            "$kind/${probeTimeoutMs(origin, deadlineMs, server)}ms=$origin"
        }
        PhoebeLog.d("PlexConnectionResolver") {
            "identity race start demote=$demote deadline=${deadlineMs}ms candidates=${ordered.size} $classified"
        }

        data class Probe(val base: String, val rank: Int, val job: Deferred<String?>)
        val probes = ordered.mapIndexed { rank, base ->
            Probe(
                base = base,
                rank = rank,
                job = async {
                    // Stagger by rank so the preferred hop gets a head start. The delay is
                    // inside the probe's own budget window, not added to the overall deadline.
                    if (rank > 0) delay(minOf(rank * ProbeStaggerMs, deadlineMs / 2))
                    val budget = probeTimeoutMs(base, deadlineMs, server)
                    val ok = withNetworkTimeoutOrNull(budget) {
                        probeIdentity(base, token, budgetMs = budget, expectedMachineId = server.id)
                    } == true
                    if (ok) base else null
                },
            )
        }.toMutableList()

        var winner: String? = null
        var winnerRank = Int.MAX_VALUE

        suspend fun collectOne(): Boolean {
            if (probes.isEmpty()) return false
            val (probe, origin) = select {
                probes.forEach { p -> p.job.onAwait { result -> p to result } }
            }
            probes.remove(probe)
            if (origin != null && probe.rank < winnerRank) {
                winner = origin
                winnerRank = probe.rank
            }
            return true
        }

        // Phase 1: wait for the first origin that answers at all.
        while (winner == null && collectOne()) Unit

        // Phase 2: a better-ranked probe may still be in flight, and we prefer it — but only
        // briefly. A LAN server that is actually up answers in tens of milliseconds, whereas a
        // closed public `:32400` burns its whole budget, and letting it do that would hold back
        // a relay that already works. python-plexapi can afford to wait for every thread; a
        // player cannot.
        if (winner != null && probes.any { it.rank < winnerRank }) {
            withNetworkTimeoutOrNull(minOf(WinnerGraceMs, deadlineMs)) {
                while (probes.any { it.rank < winnerRank }) {
                    if (!collectOne()) break
                }
            }
        }
        probes.forEach { it.job.cancel() }

        if (winner == null) {
            PhoebeLog.d("PlexConnectionResolver") {
                "identity race missed all candidates=${ordered.size} $classified"
            }
            return@coroutineScope null
        }
        PhoebeLog.d("PlexConnectionResolver") { "identity race winner=$winner" }
        adoptWinner(server, winner, identity.fingerprint)
    }

    private suspend fun probeIdentity(
        base: String,
        token: String,
        budgetMs: Long,
        expectedMachineId: String? = null,
    ): Boolean =
        runCatching {
            val response = httpClient.get("$base/identity") {
                timeout {
                    requestTimeoutMillis = budgetMs
                    connectTimeoutMillis = budgetMs
                    socketTimeoutMillis = budgetMs
                }
                header("X-Plex-Token", token)
                parameter("X-Plex-Token", token)
                header("X-Plex-Product", "Phoebe")
                header("X-Plex-Version", "0.1.0")
                header("X-Plex-Client-Identifier", PlexClient.ClientIdentifier)
                header("X-Plex-Platform", "Compose Multiplatform")
                header(HttpHeaders.Accept, "application/json")
            }
            if (!response.status.isSuccess()) {
                PhoebeLog.d("PlexConnectionResolver") {
                    "identity probe miss origin=$base http=${response.status.value}"
                }
                return@runCatching false
            }
            if (expectedMachineId.isNullOrBlank()) return@runCatching true
            val body = response.bodyAsText()
            val machineId = parseIdentityMachineId(body)
            val matched = machineId == null || machineId.equals(expectedMachineId, ignoreCase = true)
            if (!matched) {
                PhoebeLog.d("PlexConnectionResolver") {
                    "identity probe miss origin=$base machine mismatch"
                }
            }
            matched
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            PhoebeLog.d("PlexConnectionResolver") {
                "identity probe miss origin=$base detail=${error.logDetail()}"
            }
            false
        }

    private fun parseIdentityMachineId(body: String): String? {
        val root = runCatching { identityJson.parseToJsonElement(body) }.getOrNull()
        if (root is JsonObject) {
            val container = root["MediaContainer"]?.jsonObject ?: root
            container["machineIdentifier"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
            container["MachineIdentifier"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return Regex("""machineIdentifier="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
    }

    /** Returns the base that is in force for [server] afterwards, which may be the established one. */
    private suspend fun adoptWinner(server: PlexServer, origin: String, fingerprint: String): String? {
        if (fingerprint != mutableIdentity.value.fingerprint) {
            // The network moved while this race was in flight. Its winner only proves the origin
            // was reachable from where we no longer are, so publishing it would bind artwork and
            // playback to a base that is already dead.
            PhoebeLog.d("PlexConnectionResolver") {
                "discarding origin=$origin from previous network fingerprint=$fingerprint"
            }
            return null
        }
        keepEstablishedOrigin(server, origin, mutableIdentity.value)?.let { established ->
            PhoebeLog.d("PlexConnectionResolver") {
                "keeping established base=$established over race winner=$origin"
            }
            noteWorkingOrigin(server.id, fingerprint, origin)
            return established
        }
        forgottenServers.remove(server.id)
        forgottenOrigins.remove(ForgottenOrigin(server.id, origin))
        memoryCache[CacheKey(server.id, fingerprint)] = origin
        lastGoodByServer[CacheKey(server.id, fingerprint)] = origin
        markProbed(server.id, fingerprint, origin)
        if (isPlexRelayOrigin(origin, server)) {
            sessionRelayByServer[server.id] = origin
            PhoebeLog.d("PlexConnectionResolver") {
                "resolved relay origin=$origin server=${server.id} fingerprint=$fingerprint (session only)"
            }
            return origin
        }
        sessionRelayByServer.remove(server.id)
        persist(server.id, fingerprint, origin)
        PhoebeLog.d("PlexConnectionResolver") {
            "resolved origin=$origin server=${server.id} fingerprint=$fingerprint"
        }
        return origin
    }

    /**
     * The base already in force for [server], when [candidate] is no reason to leave it.
     *
     * An origin that answered proves *that hop* works — not that the app should move onto it.
     * plex.tv hands back a different relay in nearly every connections refresh, and a startup runs
     * several API calls that each finish on whichever base was live when they began, so "the last
     * answer wins" made the published base alternate between two equally good relays six times in
     * the first seven seconds. Every flip re-bound artwork (cancelling and restarting every
     * in-flight thumbnail fetch, on the slowest hop there is) and looked to playback like the
     * server had moved.
     *
     * So the established base stands while it is still one this resolver would hand out
     * ([cached]) and still `/identity`-confirmed on this network. Only a genuinely better hop —
     * LAN or direct over a relay — takes over. Anything worse or equal is recorded and ignored;
     * when the live base does break, [forget] clears it and the next answer is adopted normally.
     */
    private fun keepEstablishedOrigin(
        server: PlexServer?,
        candidate: String,
        identity: NetworkIdentity,
    ): String? {
        if (server == null) return null
        val current = cached(server, identity)?.takeIf { it == mutableProbedOrigin.value } ?: return null
        if (current == candidate) return null
        if (ProbedOrigin(server.id, identity.fingerprint, current) !in probedOk) return null
        val demote = demoteLocalOrigins(identity)
        val better = locationRank(candidate, server, demote) < locationRank(current, server, demote)
        return if (better) null else current
    }

    /** Record a hop that answered without promoting it, so a later [forget] can fall back to it. */
    private fun noteWorkingOrigin(serverId: String, fingerprint: String, origin: String) {
        probedOk.add(ProbedOrigin(serverId, fingerprint, origin))
        lastGoodByServer[CacheKey(serverId, fingerprint)] = origin
    }

    private fun markProbed(serverId: String, fingerprint: String, origin: String) {
        val trimmed = origin.trimEnd('/')
        probedOk.add(ProbedOrigin(serverId, fingerprint, trimmed))
        if (mutableProbedOrigin.value == trimmed &&
            ArtworkOriginHolder.liveOrigin == trimmed
        ) {
            return
        }
        mutableProbedOrigin.value = trimmed
        lastToken?.takeIf { it.isNotBlank() }?.let(ArtworkAuthHolder::update)
        ArtworkOriginHolder.update(trimmed, emptyList())
        PhoebeLog.d("PlexConnectionResolver") { "plex live base=$trimmed server=$serverId" }
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

    private fun probeTimeoutMs(
        base: String,
        remoteTimeoutMs: Long,
        server: PlexServer? = lastServer,
    ): Long {
        if (isLocalOnlyServerOrigin(base)) {
            return minOf(LocalProbeTimeoutMs, remoteTimeoutMs.coerceAtLeast(MinProbeTimeoutMs))
        }
        if (isPlexRelayOrigin(base, server)) {
            // TLS to a rotating plex.direct relay is the slowest hop, so it gets the largest
            // share — but never more than the caller's deadline. Returning a fixed 8s here made
            // every `deadlineMs` argument a lie and stalled play-time resolution.
            return minOf(RelayProbeTimeoutMs, remoteTimeoutMs.coerceAtLeast(MinProbeTimeoutMs))
        }
        return minOf(DirectRemoteProbeTimeoutMs, remoteTimeoutMs.coerceAtLeast(MinProbeTimeoutMs))
    }

    private data class CacheKey(
        val serverId: String,
        val fingerprint: String,
    )

    private data class ForgottenOrigin(
        val serverId: String,
        val origin: String,
    )

    private data class ProbedOrigin(
        val serverId: String,
        val fingerprint: String,
        val origin: String,
    )

    companion object {
        const val LocalProbeTimeoutMs = 700L
        /** Closed public `:32400` plex.direct hosts. Do not use the relay TLS budget. */
        const val DirectRemoteProbeTimeoutMs = 2_000L
        /** TLS to a rotating plex.direct relay — the slowest hop worth waiting for. */
        const val RelayProbeTimeoutMs = 5_000L
        /** Overall budget for a background resolve. */
        const val RemoteProbeTimeoutMs = 6_000L
        /**
         * Play-time budget. Nothing may hold first audio longer than this; the background race
         * keeps running and upgrades the origin afterwards.
         */
        const val PlayResolveDeadlineMs = 3_000L
        /** Floor so a caller cannot pass a deadline that guarantees every probe fails. */
        const val MinProbeTimeoutMs = 400L
        /** Delay between staggered probe starts, by rank (chromatix-app uses 300ms). */
        const val ProbeStaggerMs = 150L
        /** How long a better-ranked probe may hold back an origin that already answered. */
        const val WinnerGraceMs = 400L
    }
}
