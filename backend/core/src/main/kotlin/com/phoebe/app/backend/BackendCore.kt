package com.phoebe.app.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface PhoebeBackendFeature {
    fun install(application: Application, environment: PhoebeBackendEnvironment)
}

data class PhoebeBackendEnvironment(
    val config: PhoebeBackendConfig,
    val httpClient: HttpClient,
    val rateLimiter: IpRateLimiter,
    val clockMs: () -> Long,
)

data class PhoebeBackendConfig(
    val ticketmasterApiKey: String?,
    val seatGeekClientId: String?,
    val geniusAccessToken: String?,
    val musicBrainzUserAgent: String,
    val allowedOrigins: List<String>,
    val allowAnyOrigin: Boolean,
    val isProduction: Boolean,
    val rateLimitMaxRequests: Int,
    val rateLimitWindowMs: Long,
    val trustProxyHeaders: Boolean,
    val cacheTtlMinutes: Long,
) {
    fun validateForStartup() {
        if (isProduction && !allowAnyOrigin && allowedOrigins.isEmpty()) {
            error("ALLOWED_ORIGINS is required when VERCEL_ENV=production. Set BACKEND_ALLOW_ANY_ORIGIN=true only when permissive browser origins are intentional.")
        }
    }

    companion object {
        fun fromEnv(): PhoebeBackendConfig {
            val vercelEnvironment = System.getenv("VERCEL_ENV")?.trim().orEmpty()
            val isProduction = vercelEnvironment.equals("production", ignoreCase = true)
            return PhoebeBackendConfig(
                ticketmasterApiKey = System.getenv("TICKETMASTER_API_KEY")?.takeIf { it.isNotBlank() },
                seatGeekClientId = System.getenv("SEATGEEK_CLIENT_ID")?.takeIf { it.isNotBlank() },
                geniusAccessToken = System.getenv("GENIUS_ACCESS_TOKEN")?.takeIf { it.isNotBlank() },
                musicBrainzUserAgent = System.getenv("MUSICBRAINZ_USER_AGENT")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: DefaultMusicBrainzUserAgent,
                allowedOrigins = System.getenv("ALLOWED_ORIGINS")
                    .orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotBlank() },
                allowAnyOrigin = System.getenv("BACKEND_ALLOW_ANY_ORIGIN").backendBoolean(default = false),
                isProduction = isProduction,
                rateLimitMaxRequests = System.getenv("BACKEND_RATE_LIMIT_MAX_REQUESTS")
                    ?.toIntOrNull()
                    ?.coerceIn(1, 10_000)
                    ?: DefaultBackendRateLimitMaxRequests,
                rateLimitWindowMs = (
                    System.getenv("BACKEND_RATE_LIMIT_WINDOW_SECONDS")
                        ?.toLongOrNull()
                        ?.coerceIn(1, 3_600)
                        ?: DefaultBackendRateLimitWindowSeconds
                    ) * 1_000L,
                trustProxyHeaders = System.getenv("BACKEND_TRUST_PROXY_HEADERS")
                    .backendBoolean(default = vercelEnvironment.isNotBlank()),
                cacheTtlMinutes = System.getenv("BACKEND_CACHE_TTL_MINUTES")
                    ?.toLongOrNull()
                    ?.coerceIn(1, 24 * 60)
                    ?: System.getenv("EVENTS_CACHE_TTL_MINUTES")?.toLongOrNull()?.coerceIn(1, 24 * 60)
                    ?: 240,
            )
        }
    }
}

const val DefaultMusicBrainzUserAgent = "Phoebe/0.1.0 (https://github.com/j-roskopf/Phoebe)"
const val DefaultBackendRateLimitMaxRequests = 60
const val DefaultBackendRateLimitWindowSeconds = 60L
const val MaxBackendQueryParameterLength = 200
const val MaxBackendDurationMs = 24 * 60 * 60 * 1_000L

fun createPhoebeBackendHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 20_000
        connectTimeoutMillis = 8_000
        socketTimeoutMillis = 20_000
    }
    install(ClientContentNegotiation) {
        json(phoebeBackendJson)
    }
}

class MissingProviderCredentialException(message: String) : RuntimeException(message)

class ProviderApiException(message: String) : RuntimeException(message)

private val BackendSecurityLog = LoggerFactory.getLogger("com.phoebe.app.backend.Security")

class IpRateLimiter(
    private val maxRequests: Int = DefaultBackendRateLimitMaxRequests,
    private val windowMs: Long = DefaultBackendRateLimitWindowSeconds * 1_000L,
    private val clockMs: () -> Long,
) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val lastCleanupMs = AtomicLong(0L)

    fun tryAcquire(key: String): RateLimitDecision {
        val now = clockMs()
        cleanupExpiredBucketsIfNeeded(now)
        while (true) {
            val bucket = hits.getOrPut(key) { ArrayDeque() }
            val acquired = synchronized(bucket) {
                if (hits[key] !== bucket) {
                    null
                } else {
                    bucket.removeExpired(now)
                    if (bucket.size >= maxRequests) {
                        RateLimitDecision(
                            allowed = false,
                            retryAfterSeconds = bucket.retryAfterSeconds(now),
                        )
                    } else {
                        bucket.addLast(now)
                        RateLimitDecision(allowed = true)
                    }
                }
            }
            if (acquired != null) return acquired
        }
    }

    private fun cleanupExpiredBucketsIfNeeded(now: Long) {
        val intervalMs = windowMs.coerceAtMost(60_000L).coerceAtLeast(1_000L)
        val last = lastCleanupMs.get()
        if (now - last < intervalMs || !lastCleanupMs.compareAndSet(last, now)) return
        hits.forEach { (key, bucket) ->
            synchronized(bucket) {
                bucket.removeExpired(now)
                if (bucket.isEmpty()) {
                    hits.remove(key, bucket)
                }
            }
        }
    }

    private fun ArrayDeque<Long>.removeExpired(now: Long) {
        while (isNotEmpty() && now - first() >= windowMs) {
            removeFirst()
        }
    }

    private fun ArrayDeque<Long>.retryAfterSeconds(now: Long): Long {
        val oldest = firstOrNull() ?: return 1L
        val retryAfterMs = (windowMs - (now - oldest)).coerceAtLeast(1L)
        return ((retryAfterMs + 999L) / 1_000L).coerceAtLeast(1L)
    }
}

data class RateLimitDecision(
    val allowed: Boolean,
    val retryAfterSeconds: Long = 0L,
)

class BackendSingleFlight {
    private val active = ConcurrentHashMap<String, CompletableDeferred<Any?>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> run(key: String, block: suspend () -> T): T {
        val pending = CompletableDeferred<Any?>()
        val existing = active.putIfAbsent(key, pending)
        if (existing != null) return existing.await() as T
        try {
            val result = block()
            pending.complete(result)
            return result
        } catch (error: Throwable) {
            pending.completeExceptionally(error)
            throw error
        } finally {
            active.remove(key, pending)
        }
    }
}

suspend fun ApplicationCall.tryAcquire(environment: PhoebeBackendEnvironment, bucket: String): Boolean {
    val clientKey = rateLimitClientKey(environment.config)
    val decision = environment.rateLimiter.tryAcquire("$bucket:$clientKey")
    if (decision.allowed) return true
    response.headers.append("Retry-After", decision.retryAfterSeconds.toString())
    BackendSecurityLog.warn("Rate limited Phoebe backend request bucket=$bucket client=${clientKey.shortHash()}")
    respond(HttpStatusCode.TooManyRequests, ErrorResponse("Too many requests."))
    return false
}

fun ApplicationCall.requiredBackendQueryParameter(
    name: String,
    maxLength: Int = MaxBackendQueryParameterLength,
): String =
    optionalBackendQueryParameter(name, maxLength)
        ?: throw BadRequestException("$name is required.")

fun ApplicationCall.optionalBackendQueryParameter(
    name: String,
    maxLength: Int = MaxBackendQueryParameterLength,
): String? =
    request.queryParameters[name].validatedBackendQueryParameterOrNull(name, maxLength)

fun ApplicationCall.positiveDurationMsQueryParameter(name: String): Long? {
    val raw = optionalBackendQueryParameter(name) ?: return null
    return raw.toLongOrNull()
        ?.takeIf { it in 1..MaxBackendDurationMs }
        ?: throw BadRequestException("$name must be a positive integer no greater than $MaxBackendDurationMs.")
}

fun HttpResponse.requireProviderSuccess(providerName: String) {
    if (status.value !in 200..299) {
        throw ProviderApiException("$providerName API returned HTTP ${status.value}.")
    }
}

fun String.normalizedBackendCacheKey(): String =
    trim()
        .lowercase(Locale.US)
        .replace(Regex("""\s+"""), " ")

private fun String?.backendBoolean(default: Boolean): Boolean =
    when (this?.trim()?.lowercase(Locale.US)) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        null, "" -> default
        else -> default
    }

private fun ApplicationCall.rateLimitClientKey(config: PhoebeBackendConfig): String {
    if (config.trustProxyHeaders) {
        request.headers["X-Real-IP"]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it.take(200) }
        request.headers["X-Forwarded-For"]
            ?.split(',')
            ?.firstNotNullOfOrNull { value -> value.trim().takeIf { it.isNotBlank() } }
            ?.let { return it.take(200) }
    }
    return request.origin.remoteHost.ifBlank { "unknown" }.take(200)
}

private fun String?.validatedBackendQueryParameterOrNull(name: String, maxLength: Int): String? {
    val raw = this ?: return null
    if (raw.any { it.isISOControl() }) {
        throw BadRequestException("$name must not contain control characters.")
    }
    val normalized = raw.trim().replace(Regex("""\s+"""), " ")
    if (normalized.isBlank()) return null
    if (normalized.length > maxLength) {
        throw BadRequestException("$name must be $maxLength characters or fewer.")
    }
    return normalized
}

private fun String.shortHash(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    val hex = "0123456789abcdef"
    return digest.take(6).joinToString("") { byte ->
        val value = byte.toInt() and 0xff
        "${hex[value ushr 4]}${hex[value and 0xf]}"
    }
}

@Serializable
data class HealthResponse(
    val ok: Boolean,
    val service: String,
    val cacheTtlMinutes: Long,
)

@Serializable
data class ErrorResponse(
    val error: String,
)

val phoebeBackendJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

const val PhoebeBackendServiceName = "phoebe-backend"
