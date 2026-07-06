package com.phoebe.app.ui

import com.phoebe.app.feature.details.AlbumDetailPanel
import com.phoebe.app.feature.details.ArtistDetailPanel
import com.phoebe.app.feature.details.PlaylistDetailPanel
import com.phoebe.app.feature.details.SongDetailPanel
import com.phoebe.app.feature.library.LibraryDesktopView
import com.phoebe.app.feature.library.LibraryFilterTab
import com.phoebe.app.feature.library.TrackList
import com.phoebe.app.feature.playback.DesktopTransport
import com.phoebe.app.feature.playback.MobilePlayer
import com.phoebe.app.feature.playback.UpNextList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.MusicBrainzAlbumMetadataLoadState
import com.phoebe.app.domain.MusicBrainzAlbumMetadataQuery
import com.phoebe.app.domain.MusicBrainzAlbumMetadataResponse
import com.phoebe.app.domain.MusicBrainzArtwork
import com.phoebe.app.domain.MusicBrainzCreditSection
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlaybackClickTargetDesktopTest {
    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopTransportUltimateGuitarButtonInvokesRequestForCurrentTrack() = runDesktopComposeUiTest(width = 1120, height = 160) {
        val track = playbackTracks().first()
        var requestedTrack: Track? = null

        setContent {
            PhoebeTheme {
                Box(Modifier.size(1120.dp, 160.dp)) {
                    DesktopTransport(
                        track = track,
                        isPlaying = true,
                        positionMs = 0L,
                        bufferedPositionMs = 0L,
                        shuffle = false,
                        repeat = RepeatMode.Off,
                        volume = 0.7f,
                        compact = false,
                        upNextVisible = false,
                        upNextToggleEnabled = true,
                        onToggle = {},
                        onPrevious = {},
                        onNext = {},
                        onShuffle = {},
                        onRepeat = {},
                        onVolume = {},
                        onSeek = {},
                        onLyrics = {},
                        onUltimateGuitar = { requestedTrack = it },
                        onToggleUpNext = {},
                        onCast = {},
                    )
                }
            }
        }

        onNodeWithContentDescription("Open Ultimate Guitar").performClick()

        assertEquals(track.id, assertNotNull(requestedTrack).id)
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopTransportOverflowUltimateGuitarItemIsDisplayedForCurrentTrack() = runDesktopComposeUiTest(width = 860, height = 520) {
        val track = playbackTracks().first()

        setContent {
            PhoebeTheme {
                Box(Modifier.size(860.dp, 520.dp)) {
                    DesktopTransport(
                        track = track,
                        isPlaying = true,
                        positionMs = 0L,
                        bufferedPositionMs = 0L,
                        shuffle = false,
                        repeat = RepeatMode.Off,
                        volume = 0.7f,
                        compact = false,
                        upNextVisible = false,
                        upNextToggleEnabled = true,
                        onToggle = {},
                        onPrevious = {},
                        onNext = {},
                        onShuffle = {},
                        onRepeat = {},
                        onVolume = {},
                        onSeek = {},
                        onLyrics = {},
                        onUltimateGuitar = {},
                        onToggleUpNext = {},
                        onCast = {},
                    )
                }
            }
        }

        onNodeWithContentDescription("More playback options").performClick()
        waitForIdle()

        onNodeWithText("Open Ultimate Guitar").assertExists()
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopTransportOverflowUltimateGuitarButtonStaysVisibleForCurrentTrack() = runDesktopComposeUiTest(width = 860, height = 160) {
        val track = playbackTracks().first()
        var requestedTrack: Track? = null

        setContent {
            PhoebeTheme {
                Box(Modifier.size(860.dp, 160.dp)) {
                    DesktopTransport(
                        track = track,
                        isPlaying = true,
                        positionMs = 0L,
                        bufferedPositionMs = 0L,
                        shuffle = false,
                        repeat = RepeatMode.Off,
                        volume = 0.7f,
                        compact = false,
                        upNextVisible = false,
                        upNextToggleEnabled = true,
                        onToggle = {},
                        onPrevious = {},
                        onNext = {},
                        onShuffle = {},
                        onRepeat = {},
                        onVolume = {},
                        onSeek = {},
                        onLyrics = {},
                        onUltimateGuitar = { requestedTrack = it },
                        onToggleUpNext = {},
                        onCast = {},
                    )
                }
            }
        }

        onNodeWithContentDescription("Open Ultimate Guitar").assertIsDisplayed().performClick()

        assertEquals(track.id, assertNotNull(requestedTrack).id)
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopTransportUltimateGuitarButtonCanBeHiddenBySetting() = runDesktopComposeUiTest(width = 1120, height = 160) {
        val track = playbackTracks().first()

        setContent {
            PhoebeTheme {
                Box(Modifier.size(1120.dp, 160.dp)) {
                    DesktopTransport(
                        track = track,
                        isPlaying = true,
                        positionMs = 0L,
                        bufferedPositionMs = 0L,
                        shuffle = false,
                        repeat = RepeatMode.Off,
                        volume = 0.7f,
                        compact = false,
                        upNextVisible = false,
                        upNextToggleEnabled = true,
                        showUltimateGuitarButton = false,
                        onToggle = {},
                        onPrevious = {},
                        onNext = {},
                        onShuffle = {},
                        onRepeat = {},
                        onVolume = {},
                        onSeek = {},
                        onLyrics = {},
                        onUltimateGuitar = {},
                        onToggleUpNext = {},
                        onCast = {},
                    )
                }
            }
        }

        onAllNodesWithContentDescription("Open Ultimate Guitar").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun mobilePlayerUltimateGuitarButtonInvokesRequestForCurrentTrack() = runDesktopComposeUiTest(width = 430, height = 932) {
        val track = playbackTracks().first()
        var requestedTrack: Track? = null

        setContent {
            PhoebeTheme {
                Box(Modifier.size(430.dp, 932.dp)) {
                    MobilePlayer(
                        track = track,
                        upNext = emptyList(),
                        isPlaying = true,
                        shuffle = false,
                        repeat = RepeatMode.Off,
                        positionMs = 0L,
                        bufferedPositionMs = 0L,
                        currentIndex = 0,
                        onToggle = {},
                        onPrevious = {},
                        onNext = {},
                        onShuffle = {},
                        onRepeat = {},
                        onSeek = {},
                        onPlayQueue = {},
                        onMoveUpNext = { _, _ -> },
                        onRemoveUpNext = {},
                        onUltimateGuitar = { requestedTrack = it },
                        onBack = {},
                        onSwipeDismiss = {},
                        expansionFraction = 1f,
                    )
                }
            }
        }

        onNodeWithContentDescription("Open Ultimate Guitar").performClick()

        assertEquals(track.id, assertNotNull(requestedTrack).id)
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun mobilePlayerUltimateGuitarButtonCanBeHiddenBySetting() = runDesktopComposeUiTest(width = 430, height = 932) {
        val track = playbackTracks().first()

        setContent {
            PhoebeTheme {
                Box(Modifier.size(430.dp, 932.dp)) {
                    MobilePlayer(
                        track = track,
                        upNext = emptyList(),
                        isPlaying = true,
                        shuffle = false,
                        repeat = RepeatMode.Off,
                        positionMs = 0L,
                        bufferedPositionMs = 0L,
                        currentIndex = 0,
                        showUltimateGuitarButton = false,
                        onToggle = {},
                        onPrevious = {},
                        onNext = {},
                        onShuffle = {},
                        onRepeat = {},
                        onSeek = {},
                        onPlayQueue = {},
                        onMoveUpNext = { _, _ -> },
                        onRemoveUpNext = {},
                        onUltimateGuitar = {},
                        onBack = {},
                        onSwipeDismiss = {},
                        expansionFraction = 1f,
                    )
                }
            }
        }

        onAllNodesWithContentDescription("Open Ultimate Guitar").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun trackListSongRowInvokesPlaybackRequestForTappedTrack() = runDesktopComposeUiTest(width = 800, height = 520) {
        val tracks = playbackTracks()
        var request: PlaybackRequest? = null

        setContent {
            PhoebeTheme {
                Box(Modifier.size(800.dp, 520.dp)) {
                    TrackList(
                        tracks = tracks,
                        empty = "No songs",
                        catalogRefreshing = false,
                        onPlayTracks = { queue, index -> request = PlaybackRequest(queue, index) },
                        onAddToUpNext = {},
                        onDownload = {},
                    )
                }
            }
        }

        onNodeWithTag(PlaybackTestTags.playTrack(tracks[1].id)).performClick()

        val captured = assertNotNull(request)
        assertEquals(1, captured.index)
        assertEquals(tracks, captured.tracks)
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopLibrarySongRowInvokesPlaybackRequestForTappedTrack() = runDesktopComposeUiTest(width = 1100, height = 720) {
        val tracks = playbackTracks()
        var request: PlaybackRequest? = null

        setContent {
            PhoebeTheme {
                Box(Modifier.size(1100.dp, 720.dp)) {
                    LibraryDesktopView(
                        catalog = CatalogSnapshot(tracksByParent = mapOf("all" to tracks)),
                        catalogRefreshing = false,
                        filter = LibraryFilterTab.Songs,
                        libraryUi = LibraryUiPreferences(),
                        onFilter = {},
                        onLibrarySortBy = {},
                        onLibraryAscending = {},
                        onLibraryColumns = {},
                        onArtist = {},
                        onAlbum = {},
                        onPlayTracks = { queue, index -> request = PlaybackRequest(queue, index) },
                        onAddToUpNext = {},
                        onDownload = {},
                    )
                }
            }
        }

        onNodeWithTag(PlaybackTestTags.playTrack(tracks[1].id)).performClick()

        val captured = assertNotNull(request)
        assertEquals(1, captured.index)
        assertEquals(tracks, captured.tracks)
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun albumDetailTrackRowInvokesPlaybackRequestForTappedTrack() = runDesktopComposeUiTest(width = 800, height = 900) {
        val tracks = playbackTracks()
        val album = Album(id = "album-1", title = "Regression Album", artist = "Fixture Artist")
        var request: PlaybackRequest? = null

        setContent {
            PhoebeTheme {
                Box(Modifier.size(800.dp, 900.dp)) {
                    AlbumDetailPanel(
                        album = album,
                        catalog = CatalogSnapshot(albums = listOf(album), tracksByParent = mapOf(album.id to tracks)),
                        libraryUi = LibraryUiPreferences(),
                        onBack = {},
                        onPlayTracks = { queue, index -> request = PlaybackRequest(queue, index) },
                        onAddToUpNext = {},
                        onDownload = {},
                        onDownloadAlbum = {},
                        onArtist = {},
                        onLibraryColumns = {},
                    )
                }
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(PlaybackTestTags.playTrack(tracks[2].id)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(PlaybackTestTags.playTrack(tracks[2].id)).performClick()

        val captured = assertNotNull(request)
        assertEquals(0, captured.index)
        assertEquals(listOf(tracks[2], tracks[0], tracks[1]), captured.tracks)
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun playlistDetailTrackRowInvokesPlaybackRequestForTappedVisibleTrack() = runDesktopComposeUiTest(width = 800, height = 620) {
        val tracks = playbackTracks()
        val playlist = Playlist(id = "playlist-1", title = "Regression Playlist", trackCount = tracks.size)
        var request: PlaybackRequest? = null

        setContent {
            PhoebeTheme {
                Box(Modifier.size(800.dp, 620.dp)) {
                    PlaylistDetailPanel(
                        playlist = playlist,
                        catalog = CatalogSnapshot(playlists = listOf(playlist), tracksByParent = mapOf(playlist.id to tracks)),
                        catalogRefreshing = false,
                        libraryUi = LibraryUiPreferences(),
                        onBack = {},
                        onPlayTracks = { queue, index -> request = PlaybackRequest(queue, index) },
                        onAddToUpNext = {},
                        onDownload = {},
                        onDownloadPlaylist = {},
                        onLibraryColumns = {},
                    )
                }
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(PlaybackTestTags.playTrack(tracks[1].id)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(PlaybackTestTags.playTrack(tracks[1].id)).performClick()

        val captured = assertNotNull(request)
        assertEquals(0, captured.index)
        assertEquals(listOf(tracks[1], tracks[2], tracks[0]), captured.tracks)
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun albumDetailRendersMusicBrainzCreditsInAboutSection() = runDesktopComposeUiTest(width = 800, height = 900) {
        val album = Album(id = "album-credits", title = "Credits Album", artist = "Fixture Artist")

        setContent {
            PhoebeTheme {
                Box(Modifier.size(800.dp, 900.dp)) {
                    AlbumDetailPanel(
                        album = album,
                        catalog = CatalogSnapshot(albums = listOf(album), tracksByParent = emptyMap()),
                        libraryUi = LibraryUiPreferences(),
                        musicBrainzMetadata = albumMusicBrainzMetadata(album),
                        onBack = {},
                        onPlayTracks = { _, _ -> },
                        onAddToUpNext = {},
                        onDownload = {},
                        onDownloadAlbum = {},
                        onArtist = {},
                        onLibraryColumns = {},
                    )
                }
            }
        }

        onNodeWithText("Credits").assertIsDisplayed()
        onNodeWithText("Dead Oceans").assertIsDisplayed()
        onNodeWithText("Tony Berg, Ethan Gruska").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopAlbumArtworkClickOpensGallery() = runDesktopComposeUiTest(width = 800, height = 900) {
        val album = Album(id = "album-gallery", title = "Gallery Album", artist = "Fixture Artist")

        setContent {
            PhoebeTheme {
                Box(Modifier.size(800.dp, 900.dp)) {
                    AlbumDetailPanel(
                        album = album,
                        catalog = CatalogSnapshot(albums = listOf(album), tracksByParent = emptyMap()),
                        libraryUi = LibraryUiPreferences(),
                        musicBrainzMetadata = albumMusicBrainzMetadata(album),
                        onBack = {},
                        onPlayTracks = { _, _ -> },
                        onAddToUpNext = {},
                        onDownload = {},
                        onDownloadAlbum = {},
                        onArtist = {},
                        onLibraryColumns = {},
                    )
                }
            }
        }

        onNodeWithContentDescription("Open Gallery Album artwork", useUnmergedTree = true).performClick()
        onNodeWithContentDescription("Close artwork gallery").assertIsDisplayed()
        onNodeWithContentDescription("Show artwork 2").performClick()
        onNodeWithText("MusicBrainz front").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun artworkGalleryClampsCurrentPageWhenItemsShrink() = runDesktopComposeUiTest(width = 800, height = 900) {
        val album = Album(id = "album-gallery-shrink", title = "Gallery Album", artist = "Fixture Artist")
        var metadata by mutableStateOf(albumMusicBrainzMetadata(album))

        setContent {
            PhoebeTheme {
                Box(Modifier.size(800.dp, 900.dp)) {
                    AlbumDetailPanel(
                        album = album,
                        catalog = CatalogSnapshot(albums = listOf(album), tracksByParent = emptyMap()),
                        libraryUi = LibraryUiPreferences(),
                        musicBrainzMetadata = metadata,
                        onBack = {},
                        onPlayTracks = { _, _ -> },
                        onAddToUpNext = {},
                        onDownload = {},
                        onDownloadAlbum = {},
                        onArtist = {},
                        onLibraryColumns = {},
                    )
                }
            }
        }

        onNodeWithContentDescription("Open Gallery Album artwork", useUnmergedTree = true).performClick()
        onNodeWithContentDescription("Show artwork 2").performClick()
        onNodeWithText("MusicBrainz front").assertIsDisplayed()

        runOnIdle {
            metadata = MusicBrainzAlbumMetadataLoadState(loading = true)
        }

        onNodeWithText("1 of 1").assertIsDisplayed()
        onNodeWithContentDescription("Close artwork gallery").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun artistPlayAllClickAndLongClickUseSeparatePlaybackRequests() = runDesktopComposeUiTest(width = 800, height = 620) {
        val artist = Artist(id = "artist-1", title = "Fixture Artist")
        val album = Album(id = "album-1", title = "Regression Album", artist = artist.title)
        val tracks = playbackTracks()
        var playAllRequest: List<Track>? = null
        var shuffleAllRequest: List<Track>? = null

        setContent {
            PhoebeTheme {
                Box(Modifier.size(800.dp, 620.dp)) {
                    ArtistDetailPanel(
                        artist = artist,
                        catalog = CatalogSnapshot(
                            artists = listOf(artist),
                            albums = listOf(album),
                            tracksByParent = mapOf(album.id to tracks),
                        ),
                        libraryUi = LibraryUiPreferences(),
                        onBack = {},
                        onAlbum = {},
                        onPlayTracks = { _, _ -> },
                        onPlayAllTracks = { playAllRequest = it },
                        onShuffleAllTracks = { shuffleAllRequest = it },
                        onAddToUpNext = {},
                        onDownload = {},
                        onDownloadArtist = {},
                        onPlayArtistRadio = {},
                        onArtist = {},
                        onLibraryColumns = {},
                    )
                }
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(PlaybackTestTags.PlayAll).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(PlaybackTestTags.PlayAll).performClick()
        assertEquals(tracks, playAllRequest)
        assertEquals(null, shuffleAllRequest)

        onNodeWithTag(PlaybackTestTags.PlayAll).performTouchInput { longClick() }
        assertEquals(tracks, shuffleAllRequest)
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopArtistStatsActionKeepsStatsReachable() = runDesktopComposeUiTest(width = 800, height = 620) {
        val artist = Artist(id = "artist-stats", title = "Fixture Artist")
        val album = Album(id = "album-stats", title = "Regression Album", artist = artist.title)

        setContent {
            PhoebeTheme {
                Box(Modifier.size(800.dp, 620.dp)) {
                    ArtistDetailPanel(
                        artist = artist,
                        catalog = CatalogSnapshot(
                            artists = listOf(artist),
                            albums = listOf(album),
                            tracksByParent = mapOf(album.id to playbackTracks()),
                        ),
                        libraryUi = LibraryUiPreferences(),
                        onBack = {},
                        onAlbum = {},
                        onPlayTracks = { _, _ -> },
                        onAddToUpNext = {},
                        onDownload = {},
                        onDownloadArtist = {},
                        onPlayArtistRadio = {},
                        onArtist = {},
                        onLibraryColumns = {},
                    )
                }
            }
        }

        onNodeWithText("Stats").performClick()
        onNodeWithText("Last Played").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun songDetailPlayButtonInvokesPlaybackRequest() = runDesktopComposeUiTest(width = 700, height = 520) {
        val track = playbackTracks().first()
        var played = false

        setContent {
            PhoebeTheme {
                Box(Modifier.size(700.dp, 520.dp)) {
                    SongDetailPanel(
                        track = track,
                        onBack = {},
                        onPlay = { played = true },
                        onAddToUpNext = {},
                        onDownload = {},
                    )
                }
            }
        }

        onNodeWithTag(PlaybackTestTags.playTrack(track.id)).performClick()

        assertTrue(played)
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun phoneSongDetailEditMetadataButtonInvokesEditorRequest() = runDesktopComposeUiTest(width = 430, height = 760) {
        val track = playbackTracks().first()
        var requestedTrack: Track? = null

        setContent {
            PhoebeTheme {
                Box(Modifier.size(430.dp, 760.dp)) {
                    CompositionLocalProvider(
                        LocalMetadataEditorActions provides MetadataEditorActions(
                            onRequestEdit = { requestedTrack = it },
                        ),
                    ) {
                        SongDetailPanel(
                            track = track,
                            onBack = {},
                            onPlay = {},
                            onAddToUpNext = {},
                            onDownload = {},
                        )
                    }
                }
            }
        }

        onNodeWithText("Edit Metadata").performClick()

        assertEquals(track.id, assertNotNull(requestedTrack).id)
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun upNextRowInvokesQueuePlaybackRequestForTappedUpcomingTrack() = runDesktopComposeUiTest(width = 520, height = 360) {
        val tracks = playbackTracks()
        var playedIndex: Int? = null

        setContent {
            PhoebeTheme {
                Box(Modifier.size(520.dp, 360.dp)) {
                    UpNextList(
                        currentTrack = tracks[0],
                        upNext = tracks.drop(1),
                        repeat = RepeatMode.Off,
                        onPlayQueue = { playedIndex = it },
                        onMoveUpNext = { _, _ -> },
                        onRemoveUpNext = {},
                    )
                }
            }
        }

        onNodeWithTag(PlaybackTestTags.playTrack(tracks[2].id)).performClick()

        assertEquals(1, playedIndex)
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopProgressLineClickSeeksToTappedPosition() = runDesktopComposeUiTest(width = 420, height = 80) {
        var seekPositionMs: Long? = null

        setContent {
            PhoebeTheme {
                Box(Modifier.size(420.dp, 80.dp)) {
                    ProgressLine(
                        positionMs = 0L,
                        bufferedPositionMs = 0L,
                        durationMs = 60_000L,
                        waveformSeed = "desktop-progress-click",
                        modifier = Modifier.size(width = 400.dp, height = 48.dp),
                        onSeek = { seekPositionMs = it },
                        barHeight = 20.dp,
                    )
                }
            }
        }

        onNodeWithContentDescription("Playback progress, 0:00 of 1:00").performTouchInput {
            val target = Offset(width * 0.75f, 10f)
            down(target)
            up()
        }

        assertEquals(45_000L, seekPositionMs)
    }
}

private data class PlaybackRequest(
    val tracks: List<Track>,
    val index: Int,
)

private fun playbackTracks(): List<Track> =
    listOf(
        playbackTrack("track-1", "First Song"),
        playbackTrack("track-2", "Second Song"),
        playbackTrack("track-3", "Third Song"),
    )

private fun playbackTrack(id: String, title: String): Track =
    Track(
        id = id,
        title = title,
        artist = "Fixture Artist",
        album = "Regression Album",
        durationMs = 60_000L,
        streamUrl = "https://stream.example/$id.mp3",
        downloadUrl = "",
    )

private fun albumMusicBrainzMetadata(album: Album): MusicBrainzAlbumMetadataLoadState =
    MusicBrainzAlbumMetadataLoadState(
        metadata = MusicBrainzAlbumMetadataResponse(
            query = MusicBrainzAlbumMetadataQuery(
                album = album.title,
                artist = album.artist,
                year = album.year,
            ),
            credits = listOf(
                MusicBrainzCreditSection("Label", listOf("Dead Oceans")),
                MusicBrainzCreditSection("Production", listOf("Tony Berg", "Ethan Gruska")),
            ),
            artwork = listOf(
                MusicBrainzArtwork(
                    id = "musicbrainz-front",
                    imageUrl = "https://img.example/front.jpg",
                    title = "MusicBrainz front",
                    types = listOf("Front"),
                    front = true,
                ),
            ),
        ),
    )
