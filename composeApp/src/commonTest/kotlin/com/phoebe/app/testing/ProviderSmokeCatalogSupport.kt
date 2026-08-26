package com.phoebe.app.testing

import com.phoebe.app.data.CatalogRepository
import com.phoebe.app.data.MediaSourcesRepository
import com.phoebe.app.data.PlexClient
import com.phoebe.app.platform.PlatformStorage
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.testCatalogRepository
import io.ktor.client.HttpClient
import kotlin.test.assertTrue

fun ProviderSmokeHarness.catalogRepository(
    database: PhoebeDatabase,
    storage: PlatformStorage,
    http: HttpClient,
): CatalogRepository {
    val mediaSources = MediaSourcesRepository(database, storage)
    return testCatalogRepository(
        plexClient = PlexClient.withoutResolver(http),
        jellyfinClient = com.phoebe.app.data.JellyfinClient(http),
        embyClient = com.phoebe.app.data.EmbyClient(http),
        subsonicClient = com.phoebe.app.data.SubsonicClient(http),
        providerRegistry = registry(http),
        database = database,
        storage = storage,
        httpClient = http,
        mediaSourcesRepository = mediaSources,
    )
}

suspend fun ProviderSmokeHarness.runCatalogRefreshSmoke(
    source: SmokeSource,
    database: PhoebeDatabase,
    storage: PlatformStorage,
    http: HttpClient,
) {
    val repo = catalogRepository(database, storage, http)
    val session = when (source) {
        SmokeSource.Plex -> testPlexSession()
        else -> {
            val adapter = adapterFor(source, http) ?: error("missing adapter for $source")
            val config = remoteConfig(source)
            val signedIn = adapter.signIn(config.serverUrl, config.username, config.password)
            val library = adapter.libraries(signedIn, signedIn.selectedServer!!).first()
            signedIn.copy(selectedLibrary = library)
        }
    }
    repo.refreshAggregated(session)
    assertTrue(repo.catalog.value.artists.isNotEmpty(), "$source catalog refresh missing artists")
    assertTrue(repo.catalog.value.albums.isNotEmpty(), "$source catalog refresh missing albums")
}
