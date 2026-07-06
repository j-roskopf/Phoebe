package com.phoebe.app.feature.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.ArtistEventsLoadState
import com.phoebe.app.domain.ArtistRadioAvailability
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.MusicBrainzAlbumMetadataLoadState
import com.phoebe.app.domain.MusicBrainzArtistArtworkLoadState
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.CollectionEntry

@Immutable
data class ArtistDetailRouteState(
    val artist: Artist,
    val catalog: CatalogSnapshot,
    val libraryUi: LibraryUiPreferences,
    val catalogRefreshing: Boolean = false,
    val searchQuery: String = "",
    val artistRadioAvailability: ArtistRadioAvailability? = null,
    val artistRadioStarting: Boolean = false,
    val artistEventsAvailable: Boolean = false,
    val musicBrainzArtwork: MusicBrainzArtistArtworkLoadState = MusicBrainzArtistArtworkLoadState.Idle,
    val fullBleedArtwork: Boolean = true,
)

class ArtistDetailRouteActions(
    val onBack: () -> Unit,
    val onAlbum: (Album) -> Unit,
    val onPlayTracks: (List<Track>, Int) -> Unit,
    val onAddToUpNext: (Track) -> Unit,
    val onDownload: (Track) -> Unit,
    val onDownloadArtist: (Artist) -> Unit,
    val onPlayArtistRadio: (Artist) -> Unit,
    val onArtistEvents: (Artist) -> Unit = {},
    val onArtist: (Artist) -> Unit,
    val onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    val onPlayAllTracks: (List<Track>) -> Unit = { tracks -> onPlayTracks(tracks, 0) },
    val onShuffleAllTracks: (List<Track>) -> Unit = { tracks -> onPlayTracks(tracks.shuffled(), 0) },
    val onProbeArtistRadio: (Artist) -> Unit = {},
    val onLoadMusicBrainzArtwork: (Artist) -> Unit = {},
    val onCollectionItems: (CollectionEntry, String) -> Unit = { _, _ -> },
    val onSearchQuery: (String) -> Unit = {},
)

@Immutable
data class ArtistEventsRouteState(
    val artist: Artist,
    val events: ArtistEventsLoadState = ArtistEventsLoadState.Idle,
)

class ArtistEventsRouteActions(
    val onBack: () -> Unit,
    val onRetry: (Artist) -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
)

@Immutable
data class AlbumDetailRouteState(
    val album: Album,
    val catalog: CatalogSnapshot,
    val libraryUi: LibraryUiPreferences,
    val catalogRefreshing: Boolean = false,
    val searchQuery: String = "",
    val musicBrainzMetadata: MusicBrainzAlbumMetadataLoadState = MusicBrainzAlbumMetadataLoadState.Idle,
    val fullBleedArtwork: Boolean = true,
)

class AlbumDetailRouteActions(
    val onBack: () -> Unit,
    val onPlayTracks: (List<Track>, Int) -> Unit,
    val onAddToUpNext: (Track) -> Unit,
    val onDownload: (Track) -> Unit,
    val onDownloadAlbum: (Album) -> Unit,
    val onArtist: (Artist) -> Unit,
    val onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    val onCollectionItems: (CollectionEntry, String) -> Unit = { _, _ -> },
    val onSearchQuery: (String) -> Unit = {},
)

@Immutable
data class SongDetailRouteState(
    val track: Track,
)

class SongDetailRouteActions(
    val onBack: () -> Unit,
    val onPlay: () -> Unit,
    val onAddToUpNext: (Track) -> Unit,
    val onDownload: (Track) -> Unit,
    val onOpenLyrics: (Track) -> Unit = {},
)

@Immutable
data class PlaylistDetailRouteState(
    val playlist: Playlist,
    val catalog: CatalogSnapshot,
    val catalogRefreshing: Boolean,
    val libraryUi: LibraryUiPreferences,
    val searchQuery: String = "",
)

class PlaylistDetailRouteActions(
    val onBack: () -> Unit,
    val onPlayTracks: (List<Track>, Int) -> Unit,
    val onAddToUpNext: (Track) -> Unit,
    val onDownload: (Track) -> Unit,
    val onDownloadPlaylist: (Playlist) -> Unit,
    val onLibraryColumns: (LibraryColumnVisibility) -> Unit,
    val onSearchQuery: (String) -> Unit = {},
    val onCancelDownloadPlaylist: (Playlist) -> Unit = {},
    val onDeleteDownloadPlaylist: (Playlist) -> Unit = {},
    val onMovePlaylistTrack: (Playlist, Int, Int) -> Unit = { _, _, _ -> },
)

@Composable
fun ArtistDetailRoute(
    state: ArtistDetailRouteState,
    actions: ArtistDetailRouteActions,
    modifier: Modifier = Modifier,
) {
    ArtistDetailPanel(
        artist = state.artist,
        catalog = state.catalog,
        libraryUi = state.libraryUi,
        catalogRefreshing = state.catalogRefreshing,
        fullBleedArtwork = state.fullBleedArtwork,
        modifier = modifier,
        searchQuery = state.searchQuery,
        onBack = actions.onBack,
        onAlbum = actions.onAlbum,
        onPlayTracks = actions.onPlayTracks,
        onPlayAllTracks = actions.onPlayAllTracks,
        onShuffleAllTracks = actions.onShuffleAllTracks,
        onAddToUpNext = actions.onAddToUpNext,
        onDownload = actions.onDownload,
        onDownloadArtist = actions.onDownloadArtist,
        artistRadioAvailability = state.artistRadioAvailability,
        artistRadioStarting = state.artistRadioStarting,
        artistEventsAvailable = state.artistEventsAvailable,
        musicBrainzArtwork = state.musicBrainzArtwork,
        onProbeArtistRadio = actions.onProbeArtistRadio,
        onLoadMusicBrainzArtwork = actions.onLoadMusicBrainzArtwork,
        onPlayArtistRadio = actions.onPlayArtistRadio,
        onArtistEvents = actions.onArtistEvents,
        onArtist = actions.onArtist,
        onLibraryColumns = actions.onLibraryColumns,
        onCollectionItems = actions.onCollectionItems,
        onSearchQuery = actions.onSearchQuery,
    )
}

@Composable
fun ArtistEventsRoute(
    state: ArtistEventsRouteState,
    actions: ArtistEventsRouteActions,
    modifier: Modifier = Modifier,
) {
    ArtistEventsPanel(
        artist = state.artist,
        events = state.events,
        onBack = actions.onBack,
        onRetry = { actions.onRetry(state.artist) },
        onOpenUrl = actions.onOpenUrl,
        modifier = modifier,
    )
}

@Composable
fun AlbumDetailRoute(
    state: AlbumDetailRouteState,
    actions: AlbumDetailRouteActions,
    modifier: Modifier = Modifier,
) {
    AlbumDetailPanel(
        album = state.album,
        catalog = state.catalog,
        libraryUi = state.libraryUi,
        catalogRefreshing = state.catalogRefreshing,
        fullBleedArtwork = state.fullBleedArtwork,
        musicBrainzMetadata = state.musicBrainzMetadata,
        modifier = modifier,
        searchQuery = state.searchQuery,
        onBack = actions.onBack,
        onPlayTracks = actions.onPlayTracks,
        onAddToUpNext = actions.onAddToUpNext,
        onDownload = actions.onDownload,
        onDownloadAlbum = actions.onDownloadAlbum,
        onArtist = actions.onArtist,
        onLibraryColumns = actions.onLibraryColumns,
        onCollectionItems = actions.onCollectionItems,
        onSearchQuery = actions.onSearchQuery,
    )
}

@Composable
fun SongDetailRoute(
    state: SongDetailRouteState,
    actions: SongDetailRouteActions,
    modifier: Modifier = Modifier,
) {
    SongDetailPanel(
        track = state.track,
        modifier = modifier,
        onBack = actions.onBack,
        onPlay = actions.onPlay,
        onAddToUpNext = actions.onAddToUpNext,
        onDownload = actions.onDownload,
        onOpenLyrics = actions.onOpenLyrics,
    )
}

@Composable
fun PlaylistDetailRoute(
    state: PlaylistDetailRouteState,
    actions: PlaylistDetailRouteActions,
    modifier: Modifier = Modifier,
) {
    PlaylistDetailPanel(
        playlist = state.playlist,
        catalog = state.catalog,
        catalogRefreshing = state.catalogRefreshing,
        libraryUi = state.libraryUi,
        modifier = modifier,
        searchQuery = state.searchQuery,
        onSearchQuery = actions.onSearchQuery,
        onBack = actions.onBack,
        onPlayTracks = actions.onPlayTracks,
        onAddToUpNext = actions.onAddToUpNext,
        onDownload = actions.onDownload,
        onDownloadPlaylist = actions.onDownloadPlaylist,
        onCancelDownloadPlaylist = actions.onCancelDownloadPlaylist,
        onDeleteDownloadPlaylist = actions.onDeleteDownloadPlaylist,
        onMovePlaylistTrack = actions.onMovePlaylistTrack,
        onLibraryColumns = actions.onLibraryColumns,
    )
}
