package com.phoebe.app.testing

import com.phoebe.app.data.PhoebeDataJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

fun testHttpClient(engine: MockEngine): HttpClient =
    HttpClient(engine) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
        }
        install(ContentNegotiation) {
            json(PhoebeDataJson)
        }
    }
