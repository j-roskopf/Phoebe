package com.phoebe.app.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.HomeSection
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.MobileBottomTab
import com.phoebe.app.domain.PersonalMixPreferences
import com.phoebe.app.domain.normalizedMobileBottomTabs
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.PlatformStorage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

@SingleIn(AppScope::class)
@Inject
class LibraryUiRepository(
    private val database: PhoebeDatabase,
    private val storage: PlatformStorage,
) {
    private val json = PhoebeDataJson
    private val mutableState = MutableStateFlow(LibraryUiPreferences())
    val preferences: StateFlow<LibraryUiPreferences> = mutableState.asStateFlow()

    /**
     * Loads preferences from SQLite. On first run after the SQLDelight migration, falls back to
     * the legacy JSON file (and imports its values into SQLite before deleting the file).
     */
    suspend fun restore() {
        val row = runCatching {
            withContext(Dispatchers.Default) {
                database.libraryPrefsQueries.selectCurrent().awaitAsOneOrNull()
            }
        }.getOrElse { error ->
            PhoebeLog.d("LibraryUiRepository") { "Could not read library prefs: ${error.message}" }
            null
        }
        if (row != null) {
            mutableState.value = row.toPreferences()
            return
        }
        val legacy = storage.readText(LegacyPrefsFile) ?: return
        val parsed = runCatching {
            json.decodeFromString<LibraryUiPreferences>(legacy)
        }.getOrNull() ?: return
        withContext(Dispatchers.Default) { persist(parsed) }
        mutableState.value = parsed
        storage.delete(LegacyPrefsFile)
    }

    suspend fun setSortBy(sortBy: LibrarySortBy) {
        save(mutableState.value.copy(sortBy = sortBy))
    }

    suspend fun setAscending(ascending: Boolean) {
        save(mutableState.value.copy(ascending = ascending))
    }

    suspend fun setColumns(columns: LibraryColumnVisibility) {
        applyColumns(columns)
        persistCurrentToDisk()
    }

    suspend fun setHomeSections(sections: List<HomeSection>) {
        val normalized = sections.normalizedHomeSections()
        save(mutableState.value.copy(homeSections = normalized))
    }

    suspend fun setMobileBottomTabs(tabs: List<MobileBottomTab>) {
        val normalized = tabs.normalizedMobileBottomTabs()
        save(mutableState.value.copy(mobileBottomTabs = normalized))
    }

    suspend fun setPersonalMix(personalMix: PersonalMixPreferences) {
        save(mutableState.value.copy(personalMix = personalMix.normalized()))
    }

    suspend fun setAlbumGridItemSize(sizeDp: Int) {
        save(mutableState.value.normalized().copy(albumGridItemSizeDp = sizeDp))
    }

    suspend fun setArtistGridItemSize(sizeDp: Int) {
        save(mutableState.value.normalized().copy(artistGridItemSizeDp = sizeDp))
    }

    /** Updates UI state immediately; pair with [persistCurrentToDisk] on a background coroutine. */
    fun applyColumns(columns: LibraryColumnVisibility) {
        mutableState.value = mutableState.value.copy(columns = columns)
    }

    fun resetInMemoryState() {
        mutableState.value = LibraryUiPreferences()
    }

    suspend fun persistCurrentToDisk() {
        withContext(Dispatchers.Default) { persist(mutableState.value) }
    }

    private suspend fun save(prefs: LibraryUiPreferences) {
        mutableState.value = prefs
        withContext(Dispatchers.Default) { persist(prefs) }
    }

    private suspend fun persist(prefs: LibraryUiPreferences) {
        val c = prefs.columns
        database.libraryPrefsQueries.upsert(
            sortBy = prefs.sortBy.name,
            ascending = prefs.ascending.toDb(),
            colYear = c.year.toDb(),
            colGenre = c.genre.toDb(),
            colFilepath = c.filepath.toDb(),
            colAudioCodec = c.audioCodec.toDb(),
            colBitrate = c.bitrate.toDb(),
            colDuration = c.duration.toDb(),
            colSampleRate = c.sampleRate.toDb(),
            colFileType = c.fileType.toDb(),
            colDateAdded = c.dateAdded.toDb(),
            colRating = c.rating.toDb(),
            colFavorite = c.favorite.toDb(),
            homeSections = prefs.homeSections.joinToString(",") { it.name },
            mobileBottomTabs = prefs.mobileBottomTabs.normalizedMobileBottomTabs().joinToString(",") { it.name },
            personalMix = json.encodeToString(PersonalMixPreferences.serializer(), prefs.personalMix.normalized()),
            gridColumns = 3,
            albumGridItemSizeDp = prefs.normalized().albumGridItemSizeDp.toLong(),
            artistGridItemSizeDp = prefs.normalized().artistGridItemSizeDp.toLong(),
        )
    }

    private fun com.phoebe.app.db.LibraryPrefsRow.toPreferences(): LibraryUiPreferences =
        LibraryUiPreferences(
            sortBy = runCatching { LibrarySortBy.valueOf(sortBy) }.getOrDefault(LibrarySortBy.Name),
            ascending = ascending.toBool(),
            columns = LibraryColumnVisibility(
                year = colYear.toBool(),
                genre = colGenre.toBool(),
                filepath = colFilepath.toBool(),
                audioCodec = colAudioCodec.toBool(),
                bitrate = colBitrate.toBool(),
                duration = colDuration.toBool(),
                sampleRate = colSampleRate.toBool(),
                fileType = colFileType.toBool(),
                dateAdded = colDateAdded.toBool(),
                rating = colRating.toBool(),
                favorite = colFavorite.toBool(),
            ),
            homeSections = homeSections.toHomeSections(),
            mobileBottomTabs = mobileBottomTabs.toMobileBottomTabs(),
            personalMix = personalMix.toPersonalMixPreferences(),
            albumGridItemSizeDp = albumGridItemSizeDp.toInt(),
            artistGridItemSizeDp = artistGridItemSizeDp.toInt(),
        )

    private companion object {
        const val LegacyPrefsFile = "library_ui_prefs.json"
    }
}

private fun Boolean.toDb(): Long = if (this) 1L else 0L
private fun Long.toBool(): Boolean = this != 0L
private fun String.toPersonalMixPreferences(): PersonalMixPreferences =
    runCatching {
        PhoebeDataJson.decodeFromString(PersonalMixPreferences.serializer(), this).normalized()
    }.getOrDefault(PersonalMixPreferences.Default)

private fun String.toHomeSections(): List<HomeSection> {
    val parsed = split(',')
        .mapNotNull { raw -> runCatching { HomeSection.valueOf(raw.trim()) }.getOrNull() }
        .distinct()
    return parsed.normalizedHomeSections()
}

private fun List<HomeSection>.normalizedHomeSections(): List<HomeSection> =
    if (this == LegacyDefaultHomeSections) {
        HomeSection.defaultOrder
    } else flatMap { section ->
        when (section) {
            HomeSection.Favorites -> listOf(HomeSection.FavoritePlaylists, HomeSection.FavoriteArtists, HomeSection.FavoriteAlbums)
            HomeSection.Recents -> listOf(HomeSection.RecentSongs, HomeSection.RecentArtists, HomeSection.RecentAlbums)
            else -> listOf(section)
        }
    }
        .filterNot { it == HomeSection.Favorites || it == HomeSection.Recents }
        .let { (it + HomeSection.defaultOrder).distinct() }

private val LegacyDefaultHomeSections = listOf(
    HomeSection.Mixes,
    HomeSection.Collections,
    HomeSection.FavoritePlaylists,
    HomeSection.FavoriteArtists,
    HomeSection.FavoriteAlbums,
    HomeSection.RecentSongs,
    HomeSection.RecentArtists,
    HomeSection.RecentAlbums,
    HomeSection.Played,
    HomeSection.Random,
)

private fun String.toMobileBottomTabs(): List<MobileBottomTab> {
    val parsed = split(',')
        .mapNotNull { raw -> runCatching { MobileBottomTab.valueOf(raw.trim()) }.getOrNull() }
        .distinct()
    return parsed.normalizedMobileBottomTabs()
}
