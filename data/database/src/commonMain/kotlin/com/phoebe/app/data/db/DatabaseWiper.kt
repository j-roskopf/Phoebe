package com.phoebe.app.data.db

import com.phoebe.app.db.PhoebeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun PhoebeDatabase.clearAllAppData(clearPlayHistory: Boolean = true) = withContext(Dispatchers.Default) {
    transaction {
        downloadsQueries.clearAll()
        if (clearPlayHistory) {
            playHistoryQueries.clearAll()
            playHistoryQueries.clearAllAggregates()
        }
        lyricsQueries.clearGeniusAnnotations()
        lyricsQueries.clear()
        appSettingsQueries.clear()
        libraryPrefsQueries.clear()
        radioStationsQueries.clearManualStations()
        mediaSourcesQueries.clear()
        sessionQueries.clear()
        plexResolvedOriginQueries.clearAll()
        userArtifactsQueries.clearSmartPlaylists()
        userArtifactsQueries.clearSavedSearches()
        userArtifactsQueries.clearLocalMetadataOverrides()

        catalogQueries.clearLibraryPopularTracks()
        catalogQueries.clearTrackParents()
        catalogQueries.clearTracks()
        catalogQueries.clearCollectionTags()
        catalogQueries.clearCollectionValues()
        catalogQueries.clearCollectionValueLoads()
        catalogQueries.clearArtists()
        catalogQueries.clearAlbums()
        catalogQueries.clearPlaylists()
        catalogQueries.clearLocalFileMetadataCache()
    }
}
