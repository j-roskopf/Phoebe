package com.phoebe.app.backend

import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.ServiceLoader

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8088
    embeddedServer(Netty, host = "0.0.0.0", port = port) {
        phoebeBackendModule()
    }.start(wait = true)
}

fun Application.phoebeBackendModule(
    config: PhoebeBackendConfig = PhoebeBackendConfig.fromEnv(),
    httpClient: HttpClient = createPhoebeBackendHttpClient(),
    clockMs: () -> Long = System::currentTimeMillis,
    features: List<PhoebeBackendFeature> = loadPhoebeBackendFeatures(),
) {
    config.validateForStartup()
    val limiter = IpRateLimiter(
        maxRequests = config.rateLimitMaxRequests,
        windowMs = config.rateLimitWindowMs,
        clockMs = clockMs,
    )

    install(ContentNegotiation) {
        json(phoebeBackendJson)
    }
    install(CORS) {
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        allowMethod(io.ktor.http.HttpMethod.Get)
        if (config.allowAnyOrigin || config.allowedOrigins.isEmpty()) {
            anyHost()
        } else {
            val allowedOrigins = config.allowedOrigins.mapNotNull(AllowedCorsOrigin::parse)
            allowOrigins { origin ->
                val requestOrigin = AllowedCorsOrigin.parse(origin)
                requestOrigin != null && allowedOrigins.any { allowed -> allowed.matches(requestOrigin) }
            }
            allowedOrigins
                .filter { it.port == null }
                .forEach { allowed ->
                    allowHost(allowed.host, schemes = allowed.schemes)
                }
        }
    }
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Bad request."))
        }
        exception<MissingProviderCredentialException> { call, cause ->
            call.respond(
                io.ktor.http.HttpStatusCode.ServiceUnavailable,
                ErrorResponse(cause.message ?: "Provider credentials are not configured."),
            )
        }
        exception<ProviderApiException> { call, cause ->
            call.respond(
                io.ktor.http.HttpStatusCode.BadGateway,
                ErrorResponse(cause.message ?: "Provider API request failed."),
            )
        }
        exception<Throwable> { call, cause ->
            call.respond(
                io.ktor.http.HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: "Phoebe backend failed."),
            )
        }
    }

    routing {
        get("/health") {
            call.respond(
                HealthResponse(
                    ok = true,
                    service = PhoebeBackendServiceName,
                    cacheTtlMinutes = config.cacheTtlMinutes,
                ),
            )
        }
    }

    val environment = PhoebeBackendEnvironment(
        config = config,
        httpClient = httpClient,
        rateLimiter = limiter,
        clockMs = clockMs,
    )
    features.forEach { feature -> feature.install(this, environment) }
}

fun loadPhoebeBackendFeatures(): List<PhoebeBackendFeature> =
    ServiceLoader.load(PhoebeBackendFeature::class.java)
        .toList()
        .sortedBy { feature -> feature.javaClass.name }

private data class AllowedCorsOrigin(
    val host: String,
    val schemes: List<String>,
    val port: Int?,
) {
    fun matches(origin: AllowedCorsOrigin): Boolean =
        host.equals(origin.host, ignoreCase = true) &&
            (port == null || port == origin.port) &&
            schemes.any { scheme -> origin.schemes.any { it.equals(scheme, ignoreCase = true) } }

    companion object {
        fun parse(origin: String): AllowedCorsOrigin? {
            val trimmed = origin.trim().trimEnd('/')
            if (trimmed.isBlank()) return null
            val explicitScheme = trimmed.substringBefore("://", missingDelimiterValue = "")
                .takeIf { it == "http" || it == "https" }
            val withoutScheme = trimmed.removePrefix("https://").removePrefix("http://")
            val hostPort = withoutScheme.substringBefore('/')
            val host = hostPort.substringBefore(':').takeIf { it.isNotBlank() } ?: return null
            val port = hostPort.substringAfter(':', missingDelimiterValue = "").toIntOrNull()
            return AllowedCorsOrigin(
                host = host,
                schemes = explicitScheme?.let(::listOf) ?: listOf("https", "http"),
                port = port,
            )
        }
    }
}
