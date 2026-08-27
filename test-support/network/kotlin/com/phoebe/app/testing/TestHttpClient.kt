package com.phoebe.app.testing

import com.phoebe.app.data.PhoebeDataJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers

fun testHttpClient(engine: MockEngine): HttpClient =
    HttpClient(engine) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
        }
        install(ContentNegotiation) {
            json(PhoebeDataJson)
        }
    }

fun testMockEngine(handler: MockRequestHandler): MockEngine =
    MockEngine(
        MockEngineConfig().apply {
            dispatcher = Dispatchers.Unconfined
            addHandler(handler)
        },
    )
