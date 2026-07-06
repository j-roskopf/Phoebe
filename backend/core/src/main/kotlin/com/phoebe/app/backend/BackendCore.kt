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
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    val allowedOrigins: List<String>,
    val cacheTtlMinutes: Long,
) {
    companion object {
        fun fromEnv(): PhoebeBackendConfig =
            PhoebeBackendConfig(
                ticketmasterApiKey = System.getenv("TICKETMASTER_API_KEY")?.takeIf { it.isNotBlank() },
                seatGeekClientId = System.getenv("SEATGEEK_CLIENT_ID")?.takeIf { it.isNotBlank() },
                geniusAccessToken = System.getenv("GENIUS_ACCESS_TOKEN")?.takeIf { it.isNotBlank() },
                allowedOrigins = System.getenv("ALLOWED_ORIGINS")
                    .orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotBlank() },
                cacheTtlMinutes = System.getenv("BACKEND_CACHE_TTL_MINUTES")
                    ?.toLongOrNull()
                    ?.coerceIn(1, 24 * 60)
                    ?: System.getenv("EVENTS_CACHE_TTL_MINUTES")?.toLongOrNull()?.coerceIn(1, 24 * 60)
                    ?: 240,
            )
    }
}

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

class IpRateLimiter(
    private val maxRequests: Int = 120,
    private val windowMs: Long = 60_000,
    private val clockMs: () -> Long,
) {
    private val hits = ConcurrentHashMap<String, MutableList<Long>>()
    private val lastCleanupMs = AtomicLong(0L)

    fun tryAcquire(key: String): Boolean {
        val now = clockMs()
        cleanupExpiredBucketsIfNeeded(now)
        while (true) {
            val bucket = hits.getOrPut(key) { mutableListOf() }
            val acquired = synchronized(bucket) {
                if (hits[key] !== bucket) {
                    null
                } else {
                    bucket.removeAll { now - it > windowMs }
                    if (bucket.size >= maxRequests) {
                        false
                    } else {
                        bucket += now
                        true
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
                bucket.removeAll { now - it > windowMs }
                if (bucket.isEmpty()) {
                    hits.remove(key, bucket)
                }
            }
        }
    }
}

suspend fun ApplicationCall.tryAcquire(limiter: IpRateLimiter): Boolean {
    val remote = request.origin.remoteHost.ifBlank { "unknown" }
    if (limiter.tryAcquire(remote)) return true
    respond(HttpStatusCode.TooManyRequests, ErrorResponse("Too many requests."))
    return false
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
