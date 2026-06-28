package com.phoebe.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.AppSettings
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.HomeSection
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.PlexRadioStation
import com.phoebe.app.domain.Track
import com.phoebe.app.data.HomePlayedTrack
import com.phoebe.app.feature.home.*
import com.phoebe.app.feature.home.HomeUiState
import com.phoebe.app.feature.settings.SettingsMobileView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneHomeAccordionTest {
    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun phoneHomeAccordionsStartCollapsedAndOpenOneAtATime() = runDesktopComposeUiTest(width = 430, height = 932) {
        setContent {
            PhoebeTheme {
                Box(Modifier.size(430.dp, 932.dp)) {
                    MobileHomeScreen(
                        state = HomeUiState(),
                        listState = rememberLazyListState(),
                        layoutMode = HomeScreenLayoutMode.Compact,
                        radioStations = listOf(
                            PlexRadioStation(
                                id = "radio-library",
                                title = "Library Radio",
                                subtitle = "Shuffle the library",
                                key = "radio-library",
                            ),
                        ),
                        homeSections = listOf(
                            HomeSection.Mixes,
                            HomeSection.Collections,
                            HomeSection.Played,
                        ),
                        onArtist = {},
                        onAlbum = {},
                        onPlaylist = {},
                        onRecentSongs = {},
                        onRecentArtists = {},
                        onRecentAlbums = {},
                        onFavoritePlaylists = {},
                        onFavoriteArtists = {},
                        onFavoriteAlbums = {},
                        onCollections = {},
                        onRecentlyPlayed = {},
                        onMostPlayed = {},
                        onRefreshArtists = {},
                        onRefreshAlbums = {},
                        onPlayTracks = { _, _ -> },
                        onAddToUpNext = {},
                        onDownload = {},
                    )
                }
            }
        }

        fun assertTextExists(text: String) {
            assertTrue(
                onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty(),
                "Expected text '$text' to exist.",
            )
        }

        fun assertTextDoesNotExist(text: String) {
            assertFalse(
                onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty(),
                "Expected text '$text' not to exist.",
            )
        }

        waitForIdle()
        assertTextExists("Mixes")
        assertTextExists("Collections")
        assertTextExists("Listening History")
        assertTextDoesNotExist("PERSONAL\nMIX")
        assertTextDoesNotExist("ARTIST\nMOOD")
        assertTextDoesNotExist("Recently Played")

        onNodeWithText("Mixes").performClick()
        mainClock.advanceTimeBy(260)
        waitForIdle()
        assertTextExists("PERSONAL\nMIX")
        assertTextExists("DECADE\nMIX")
        assertTextExists("LIBRARY\nRADIO")

        onNodeWithText("Collections").performClick()
        mainClock.advanceTimeBy(260)
        waitForIdle()
        assertTextDoesNotExist("PERSONAL\nMIX")
        assertTextExists("ARTIST\nMOOD")

        onNodeWithText("Listening History").performClick()
        mainClock.advanceTimeBy(260)
        waitForIdle()
        assertTextDoesNotExist("ARTIST\nMOOD")
        assertTextExists("Recently Played")
        assertTextExists("Most Played")
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun phoneHomeFavoritesAccordionStartsCollapsedAndExpands() = runDesktopComposeUiTest(width = 430, height = 932) {
        setContent {
            PhoebeTheme {
                Box(Modifier.size(430.dp, 932.dp)) {
                    MobileHomeScreen(
                        state = HomeUiState(),
                        listState = rememberLazyListState(),
                        layoutMode = HomeScreenLayoutMode.Compact,
                        homeSections = listOf(
                            HomeSection.FavoritePlaylists,
                            HomeSection.FavoriteArtists,
                            HomeSection.FavoriteAlbums,
                        ),
                        onArtist = {},
                        onAlbum = {},
                        onPlaylist = {},
                        onRecentSongs = {},
                        onRecentArtists = {},
                        onRecentAlbums = {},
                        onFavoritePlaylists = {},
                        onFavoriteArtists = {},
                        onFavoriteAlbums = {},
                        onCollections = {},
                        onRecentlyPlayed = {},
                        onMostPlayed = {},
                        onRefreshArtists = {},
                        onRefreshAlbums = {},
                        onPlayTracks = { _, _ -> },
                        onAddToUpNext = {},
                        onDownload = {},
                    )
                }
            }
        }

        waitForIdle()
        assertTrue(onAllNodesWithText("Favorites").fetchSemanticsNodes().isNotEmpty())
        assertFalse(onAllNodesWithText("Playlists").fetchSemanticsNodes().isNotEmpty())

        onNodeWithText("Favorites").performClick()
        mainClock.advanceTimeBy(260)
        waitForIdle()

        assertTrue(onAllNodesWithText("Playlists").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("Artists").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("Albums").fetchSemanticsNodes().isNotEmpty())
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun expandedPhoneHomeShowsShelvesAndPlayedTables() = runDesktopComposeUiTest(width = 430, height = 1500) {
        val track = testTrack("track-1", "A Moment Apart")
        val recentTrack = testTrack("track-2", "Line Of Sight")
        setContent {
            PhoebeTheme {
                Box(Modifier.size(430.dp, 1500.dp)) {
                    MobileHomeScreen(
                        state = HomeUiState(
                            recentlyAddedTracks = listOf(recentTrack),
                            recentlyAddedArtists = listOf(Artist("artist-1", "ODESZA")),
                            recentlyAddedAlbums = listOf(Album("album-1", "In Return", "ODESZA")),
                            recentlyPlayedTracks = listOf(HomePlayedTrack(track, lastPlayedMs = 10_000L, playCount = 2L)),
                            mostPlayedTracks = listOf(HomePlayedTrack(track, lastPlayedMs = 10_000L, playCount = 12L)),
                            favoritePlaylists = listOf(Playlist("playlist-1", "Late Night", 24, favorite = true)),
                            favoriteArtists = listOf(Artist("artist-2", "Tycho", favorite = true)),
                            favoriteAlbums = listOf(Album("album-2", "Epoch", "Tycho", favorite = true)),
                            randomArtists = listOf(Artist("artist-3", "RUFUS DU SOL")),
                            randomAlbums = listOf(Album("album-3", "Bloom", "RUFUS DU SOL")),
                        ),
                        listState = rememberLazyListState(),
                        layoutMode = HomeScreenLayoutMode.Expanded,
                        homeSections = listOf(
                            HomeSection.Mixes,
                            HomeSection.FavoritePlaylists,
                            HomeSection.RecentSongs,
                            HomeSection.Played,
                        ),
                        onArtist = {},
                        onAlbum = {},
                        onPlaylist = {},
                        onRecentSongs = {},
                        onRecentArtists = {},
                        onRecentAlbums = {},
                        onFavoritePlaylists = {},
                        onFavoriteArtists = {},
                        onFavoriteAlbums = {},
                        onCollections = {},
                        onRecentlyPlayed = {},
                        onMostPlayed = {},
                        onRefreshArtists = {},
                        onRefreshAlbums = {},
                        onPlayTracks = { _, _ -> },
                        onAddToUpNext = {},
                        onDownload = {},
                    )
                }
            }
        }

        waitForIdle()
        assertTrue(onAllNodesWithText("CREATE A MIX").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("FAVORITE PLAYLISTS").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("RECENTLY ADDED SONGS").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("RECENTLY PLAYED").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("MOST PLAYED").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("A Moment Apart").fetchSemanticsNodes().isNotEmpty())
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun settingsHomeLayoutModeInvokesCallback() = runDesktopComposeUiTest(width = 430, height = 932) {
        var selected = HomeScreenLayoutMode.Compact
        setContent {
            PhoebeTheme {
                SettingsMobileView(
                    isLightMode = false,
                    onLightModeChange = {},
                    tintId = PhoebeTintOption.Purple.id,
                    onTintChange = {},
                    downloadDirectory = null,
                    downloadCount = 0,
                    appSettings = AppSettings.Default,
                    libraryUi = LibraryUiPreferences(),
                    defaultDownloadDirectoryLabel = "App storage",
                    onDownloadDirectory = {},
                    onDeleteAllDownloads = {},
                    onCrossfadeSeconds = {},
                    onScanLibraryOnLaunch = {},
                    onNotifyWhenDownloadFinishes = {},
                    onHomeSections = {},
                    onPersonalMix = {},
                    onAlbumGridItemSize = {},
                    onArtistGridItemSize = {},
                    onExportFavoritePlaylists = {},
                    onImportFavoritePlaylists = {},
                    onExportRadioStations = {},
                    onImportRadioStations = {},
                    homeScreenLayoutMode = selected,
                    onHomeScreenLayoutModeChange = { selected = it },
                    modifier = Modifier.size(430.dp, 932.dp),
                )
            }
        }

        onNode(hasText("Expanded") and hasClickAction())
            .performScrollTo()
            .performClick()
        waitForIdle()

        assertEquals(HomeScreenLayoutMode.Expanded, selected)
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun settingsTintedBackgroundSwitchCanBeDisabledOnMobile() = runDesktopComposeUiTest(width = 430, height = 932) {
        var tintedBackground: Boolean? = null
        setContent {
            PhoebeTheme {
                SettingsMobileView(
                    isLightMode = false,
                    onLightModeChange = {},
                    tintId = PhoebeTintOption.Purple.id,
                    onTintChange = {},
                    downloadDirectory = null,
                    downloadCount = 0,
                    appSettings = AppSettings.Default.copy(tintedBackgroundGradient = true),
                    libraryUi = LibraryUiPreferences(),
                    defaultDownloadDirectoryLabel = "App storage",
                    onDownloadDirectory = {},
                    onDeleteAllDownloads = {},
                    onCrossfadeSeconds = {},
                    onScanLibraryOnLaunch = {},
                    onNotifyWhenDownloadFinishes = {},
                    onTintedBackgroundGradient = { tintedBackground = it },
                    onHomeSections = {},
                    onPersonalMix = {},
                    onAlbumGridItemSize = {},
                    onArtistGridItemSize = {},
                    onExportFavoritePlaylists = {},
                    onImportFavoritePlaylists = {},
                    onExportRadioStations = {},
                    onImportRadioStations = {},
                    modifier = Modifier.size(430.dp, 932.dp),
                )
            }
        }

        onNodeWithTag("settings:tinted-background-switch", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        waitForIdle()

        assertEquals(false, tintedBackground)
    }

    private fun testTrack(id: String, title: String): Track =
        Track(
            id = id,
            title = title,
            artist = "ODESZA",
            album = "A Moment Apart",
            durationMs = 180_000L,
            streamUrl = "",
            downloadUrl = "",
        )
}
