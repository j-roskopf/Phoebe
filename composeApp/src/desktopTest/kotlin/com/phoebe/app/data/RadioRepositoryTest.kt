package com.phoebe.app

import com.phoebe.app.data.PlexClient
import com.phoebe.app.data.RadioBrowserClient
import com.phoebe.app.data.RadioRepository
import com.phoebe.app.data.SubsonicClient
import com.phoebe.app.domain.RadioMapScopeKind
import com.phoebe.app.domain.RadioStationSearchQuery
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioRepositoryTest {
    @Test
    fun loadGlobeRequestsAllGeocodedStationsWithoutViewportPaging() = runBlocking {
        val captures = mutableListOf<RadioSearchCapture>()
        val engine = MockEngine { request ->
            if (request.url.encodedPath.contains("/json/stations/search")) {
                captures += request.url.parameters.toRadioSearchCapture()
            }
            respondJson("[]")
        }
        val fixture = radioRepositoryFixture(engine)

        try {
            fixture.repository.loadGlobe(RadioStationSearchQuery(text = "jazz"))

            assertEquals(1, captures.size)
            captures.single().let { capture ->
                assertEquals("20000", capture.limit)
                assertNull(capture.offset)
                assertEquals("jazz", capture.name)
                assertEquals("true", capture.hasGeoInfo)
                assertEquals("true", capture.hideBroken)
                assertEquals("clickcount", capture.order)
                assertEquals("true", capture.reverse)
            }
            fixture.repository.state.value.let { state ->
                assertEquals(0, state.globePageIndex)
                assertEquals(20_000, state.globePageSize)
                assertEquals(0, state.globeLoadedStationCount)
                assertTrue(state.globeMapLoaded)
                assertFalse(state.globeLoading)
                assertFalse(state.globeAutoPrefetching)
                assertFalse(state.canLoadNextGlobePage)
                assertFalse(state.canLoadPreviousGlobePage)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun loadGlobeContinuesFromNextOffsetWhenFirstPageFillsLimit() = runBlocking {
        val captures = mutableListOf<RadioSearchCapture>()
        val engine = MockEngine { request ->
            if (request.url.encodedPath.contains("/json/stations/search")) {
                captures += request.url.parameters.toRadioSearchCapture()
            }
            when (request.url.parameters["offset"]?.toIntOrNull() ?: 0) {
                0 -> respondJson(
                    (0 until 20_000).joinToString(prefix = "[", postfix = "]") { index ->
                        stationJson(
                            id = "full-$index",
                            latitude = 10.0 + (index % 80) / 10.0,
                            longitude = 20.0 + (index % 120) / 10.0,
                        )
                    },
                )
                20_000 -> respondJson(
                    listOf(
                        stationJson("full-10", latitude = 11.0, longitude = 21.0),
                        stationJson("tail-1", latitude = 12.0, longitude = 22.0),
                        stationJson("tail-2", latitude = 13.0, longitude = 23.0),
                    ).joinToString(prefix = "[", postfix = "]"),
                )
                else -> respondJson("[]")
            }
        }
        val fixture = radioRepositoryFixture(engine)

        try {
            fixture.repository.loadGlobe()

            assertEquals(listOf(null, "20000"), captures.map { it.offset })
            assertEquals(listOf("20000", "20000"), captures.map { it.limit })
            fixture.repository.state.value.let { state ->
                assertEquals(20_002, state.globeLoadedStationCount)
                assertEquals(20_002, state.globeStations.size)
                assertTrue(state.globeMapLoaded)
                assertEquals("full-0", state.globeStations.first().id)
                assertEquals("tail-2", state.globeStations.last().id)
                assertEquals(0, state.globePageIndex)
                assertFalse(state.globeLoading)
                assertFalse(state.globeAutoPrefetching)
                assertFalse(state.canLoadNextGlobePage)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun loadGlobeCountryDrilldownScopesAndReplacesResults() = runBlocking {
        val captures = mutableListOf<RadioSearchCapture>()
        val engine = MockEngine { request ->
            if (request.url.encodedPath.contains("/json/stations/search")) {
                captures += request.url.parameters.toRadioSearchCapture()
            }
            val country = request.url.parameters["countrycode"].orEmpty()
            val content = if (country == "DE") {
                listOf(
                    stationJson("country-de", latitude = 52.0, longitude = 11.0, countryCode = "DE"),
                    stationJson("country-de-2", latitude = 53.0, longitude = 12.0, countryCode = "DE"),
                ).joinToString(prefix = "[", postfix = "]")
            } else {
                "[${stationJson("global-us", latitude = 40.0, longitude = -100.0)}]"
            }
            respondJson(content)
        }
        val fixture = radioRepositoryFixture(engine)

        try {
            fixture.repository.loadGlobe()
            assertEquals(listOf("global-us"), fixture.repository.state.value.globeStations.map { it.id })

            fixture.repository.loadGlobe(countryCode = "DE")

            assertEquals(listOf(null, "DE"), captures.map { it.countryCode })
            fixture.repository.state.value.let { state ->
                assertEquals(listOf("country-de", "country-de-2"), state.globeStations.map { it.id })
                assertEquals("DE", state.globeSearchQuery.countryCode)
                assertEquals("DE", state.globeMapScope.countryCode)
                assertEquals(RadioMapScopeKind.Country, state.globeMapScope.kind)
                assertEquals(2, state.globeLoadedStationCount)
                assertTrue(state.globeMapLoaded)
                assertFalse(state.canLoadNextGlobePage)
                assertFalse(state.globeAutoPrefetching)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun loadGlobeNewTextQueryReplacesPreviousScopedResults() = runBlocking {
        val captures = mutableListOf<RadioSearchCapture>()
        val engine = MockEngine { request ->
            if (request.url.encodedPath.contains("/json/stations/search")) {
                captures += request.url.parameters.toRadioSearchCapture()
            }
            val name = request.url.parameters["name"].orEmpty()
            val content = if (name == "rock") {
                "[${stationJson("rock-one", latitude = 41.0, longitude = -87.0)}]"
            } else {
                "[${stationJson("jazz-one", latitude = 42.0, longitude = -88.0)}]"
            }
            respondJson(content)
        }
        val fixture = radioRepositoryFixture(engine)

        try {
            fixture.repository.loadGlobe(RadioStationSearchQuery(text = "jazz"))
            assertEquals(listOf("jazz-one"), fixture.repository.state.value.globeStations.map { it.id })

            fixture.repository.loadGlobe(RadioStationSearchQuery(text = "rock"), page = 3)

            assertEquals(listOf("jazz", "rock"), captures.map { it.name })
            fixture.repository.state.value.let { state ->
                assertEquals(listOf("rock-one"), state.globeStations.map { it.id })
                assertEquals("rock", state.globeSearchQuery.text)
                assertEquals(0, state.globePageIndex)
                assertEquals(1, state.globeLoadedStationCount)
                assertTrue(state.globeMapLoaded)
                assertFalse(state.canLoadNextGlobePage)
                assertFalse(state.globeAutoPrefetching)
            }
        } finally {
            fixture.close()
        }
    }
}

private data class RadioSearchCapture(
    val limit: String?,
    val offset: String?,
    val name: String?,
    val countryCode: String?,
    val hasGeoInfo: String?,
    val hideBroken: String?,
    val order: String?,
    val reverse: String?,
)

private fun io.ktor.http.Parameters.toRadioSearchCapture(): RadioSearchCapture =
    RadioSearchCapture(
        limit = this["limit"],
        offset = this["offset"],
        name = this["name"],
        countryCode = this["countrycode"],
        hasGeoInfo = this["has_geo_info"],
        hideBroken = this["hidebroken"],
        order = this["order"],
        reverse = this["reverse"],
    )

private fun radioRepositoryFixture(engine: MockEngine): RadioRepositoryFixture {
    val (database, driver) = newInMemoryPhoebeDatabase()
    val httpClient = testHttpClient(engine)
    val sessionRepository = testSessionRepository(
        plexClient = PlexClient.withoutResolver(httpClient),
        database = database,
        storage = PlatformStorage(),
        httpClient = httpClient,
    )
    val repository = testRadioRepository(
        database = database,
        radioBrowserClient = RadioBrowserClient(httpClient),
        subsonicClient = SubsonicClient(httpClient),
        sessionRepository = sessionRepository,
    )
    return RadioRepositoryFixture(repository = repository, closeDriver = { driver.close() })
}

private data class RadioRepositoryFixture(
    val repository: RadioRepository,
    private val closeDriver: () -> Unit,
) : AutoCloseable {
    override fun close() {
        closeDriver()
    }
}

private fun MockRequestHandleScope.respondJson(content: String) =
    respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

private fun stationJson(
    id: String,
    latitude: Double,
    longitude: Double,
    countryCode: String = "US",
): String =
    """
    {
      "stationuuid": "$id",
      "name": "$id",
      "url": "https://example.com/$id",
      "geo_lat": $latitude,
      "geo_long": $longitude,
      "countrycode": "$countryCode"
    }
    """.trimIndent()
