package com.phoebe.app

import com.phoebe.app.data.CatalogRepository
import com.phoebe.app.data.EmbyClient
import com.phoebe.app.data.JellyfinClient
import com.phoebe.app.data.MediaSourcesRepository
import com.phoebe.app.data.MusicProviderRegistry
import com.phoebe.app.data.PlexClient
import com.phoebe.app.data.SessionRepository
import com.phoebe.app.data.SubsonicClient
import com.phoebe.app.data.UserArtifactsRepository
import com.phoebe.app.data.db.DatabaseWriteGate
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.platform.PlatformStorage
import io.ktor.client.HttpClient

fun testCatalogRepository(
    plexClient: PlexClient,
    database: PhoebeDatabase,
    storage: PlatformStorage,
    httpClient: HttpClient,
    mediaSourcesRepository: MediaSourcesRepository,
    jellyfinClient: JellyfinClient = JellyfinClient(httpClient),
    embyClient: EmbyClient = EmbyClient(httpClient),
    subsonicClient: SubsonicClient = SubsonicClient(httpClient),
    providerRegistry: MusicProviderRegistry = MusicProviderRegistry(emptyList()),
    databaseWriteGate: DatabaseWriteGate = DatabaseWriteGate(),
): CatalogRepository =
    CatalogRepository(
        plexClient = plexClient,
        jellyfinClient = jellyfinClient,
        embyClient = embyClient,
        subsonicClient = subsonicClient,
        providerRegistry = providerRegistry,
        database = database,
        storage = storage,
        httpClient = httpClient,
        mediaSourcesRepository = mediaSourcesRepository,
        userArtifactsRepository = UserArtifactsRepository(database),
        databaseWriteGate = databaseWriteGate,
    )

fun testSessionRepository(
    plexClient: PlexClient,
    database: PhoebeDatabase,
    storage: PlatformStorage,
    httpClient: HttpClient,
    jellyfinClient: JellyfinClient = JellyfinClient(httpClient),
    providerRegistry: MusicProviderRegistry = MusicProviderRegistry(emptyList()),
    databaseWriteGate: DatabaseWriteGate = DatabaseWriteGate(),
    serverUrlResolver: com.phoebe.app.data.ServerUrlResolver = com.phoebe.app.data.ServerUrlResolver(httpClient),
): SessionRepository =
    SessionRepository(
        plexClient = plexClient,
        jellyfinClient = jellyfinClient,
        providerRegistry = providerRegistry,
        database = database,
        storage = storage,
        databaseWriteGate = databaseWriteGate,
        serverUrlResolver = serverUrlResolver,
    )

fun testRadioRepository(
    database: PhoebeDatabase,
    radioBrowserClient: com.phoebe.app.data.RadioBrowserClient,
    subsonicClient: SubsonicClient,
    sessionRepository: SessionRepository,
): com.phoebe.app.data.RadioRepository =
    com.phoebe.app.data.RadioRepository(
        database = database,
        radioBrowserClient = radioBrowserClient,
        subsonicClient = subsonicClient,
        sessionRepository = sessionRepository,
    )
