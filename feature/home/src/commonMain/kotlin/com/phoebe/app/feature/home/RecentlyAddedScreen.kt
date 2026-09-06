package com.phoebe.app.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.phoebe.app.data.catalogAlbumsForArtist
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.RecentlyAddedKind
import com.phoebe.app.domain.Track
import com.phoebe.app.ui.ArtworkImage
import com.phoebe.app.ui.PhoebeIcon
import com.phoebe.app.ui.PhoebeIconView
import com.phoebe.app.ui.PhoebeUi
import com.phoebe.app.ui.TrackArtworkImage
import com.phoebe.app.ui.formatLastPlayed
import com.phoebe.app.ui.mobileContentTopPadding

@Immutable
data class RecentlyAddedNowPlayingState(
    val trackId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
)

@Composable
fun RecentlyAddedScreen(
    kind: RecentlyAddedKind,
    catalog: CatalogSnapshot,
    nowMs: Long,
    modifier: Modifier = Modifier,
    nowPlaying: RecentlyAddedNowPlayingState = RecentlyAddedNowPlayingState(),
    bottomContentPadding: Dp = 0.dp,
    onBack: () -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
) {
    val page = remember(kind, catalog) {
        RecentlyAddedPage.from(kind, catalog)
    }
    Column(
        modifier
            .fillMaxSize()
            .padding(start = 28.dp, end = 28.dp, top = mobileContentTopPadding(24.dp), bottom = 24.dp + bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        RecentlyAddedHeader(page, onBack)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            when (kind) {
                RecentlyAddedKind.Songs -> RecentlyAddedSongs(
                    tracks = page.tracks,
                    nowPlaying = nowPlaying,
                    onPlayTracks = onPlayTracks,
                    onAddToUpNext = onAddToUpNext,
                    onDownload = onDownload,
                    modifier = Modifier.fillMaxSize(),
                )
                RecentlyAddedKind.Artists -> RecentlyAddedArtists(
                    catalog = catalog,
                    artists = page.artists,
                    nowMs = nowMs,
                    compact = maxWidth < 700.dp,
                    onArtist = onArtist,
                    modifier = Modifier.fillMaxSize(),
                )
                RecentlyAddedKind.Albums -> RecentlyAddedAlbums(
                    albums = page.albums,
                    nowMs = nowMs,
                    compact = maxWidth < 700.dp,
                    onAlbum = onAlbum,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun RecentlyAddedHeader(page: RecentlyAddedPage, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .background(PhoebeUi.elevatedFill)
                .border(BorderStroke(1.dp, PhoebeUi.border), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            PhoebeIconView(PhoebeIcon.Back, tint = PhoebeUi.primaryText, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Recently Added".uppercase(),
                color = PhoebeUi.mutedText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.08.em,
            )
            Text(page.title, color = PhoebeUi.primaryText, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text("${page.count} most recently added", color = PhoebeUi.secondaryText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun RecentlyAddedSongs(
    tracks: List<Track>,
    nowPlaying: RecentlyAddedNowPlayingState,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToUpNext: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) {
        RecentlyAddedEmpty("No songs in your library yet.", modifier)
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            RecentlyAddedTrackRow(
                track = track,
                isNowPlaying = track.id == nowPlaying.trackId,
                nowPlayingIsPlaying = nowPlaying.isPlaying,
                nowPlayingIsBuffering = nowPlaying.isBuffering,
                onPlay = { onPlayTracks(tracks, index) },
                onAddToUpNext = { onAddToUpNext(track) },
                onDownload = { onDownload(track) },
            )
        }
    }
}

@Composable
private fun RecentlyAddedArtists(
    catalog: CatalogSnapshot,
    artists: List<Artist>,
    nowMs: Long,
    compact: Boolean,
    onArtist: (Artist) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (artists.isEmpty()) {
        RecentlyAddedEmpty("No artists in your library yet.", modifier)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (compact) 132.dp else 172.dp),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(artists, key = { it.id }) { artist ->
            val thumb = catalogAlbumsForArtist(catalog, artist.title).firstOrNull { it.thumbUrl != null }?.thumbUrl
            RecentlyAddedMediaCard(
                title = artist.title,
                subtitle = "${artist.albumCount} albums",
                dateAddedMs = artist.dateAddedMs,
                nowMs = nowMs,
                artworkSeed = artist.id,
                thumbUrl = thumb,
                circular = true,
                onClick = { onArtist(artist) },
            )
        }
    }
}

@Composable
private fun RecentlyAddedAlbums(
    albums: List<Album>,
    nowMs: Long,
    compact: Boolean,
    onAlbum: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (albums.isEmpty()) {
        RecentlyAddedEmpty("No albums in your library yet.", modifier)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (compact) 132.dp else 172.dp),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(albums, key = { it.id }) { album ->
            RecentlyAddedMediaCard(
                title = album.title,
                subtitle = album.artist,
                dateAddedMs = album.dateAddedMs,
                nowMs = nowMs,
                artworkSeed = album.id,
                thumbUrl = album.thumbUrl,
                circular = false,
                onClick = { onAlbum(album) },
            )
        }
    }
}

@Composable
private fun RecentlyAddedMediaCard(
    title: String,
    subtitle: String,
    dateAddedMs: Long?,
    nowMs: Long,
    artworkSeed: String,
    thumbUrl: String?,
    circular: Boolean,
    onClick: () -> Unit,
) {
    val shape = if (circular) CircleShape else RoundedCornerShape(10.dp)
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(PhoebeUi.elevatedFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ArtworkImage(
            seed = artworkSeed,
            thumbUrl = thumbUrl,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            radius = if (circular) 90.dp else 10.dp,
            shape = shape,
            elevated = false,
            maxDecodeDimension = 180,
        )
        Text(
            title,
            color = PhoebeUi.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            color = PhoebeUi.secondaryText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(dateAddedMs?.let { formatLastPlayed(it, nowMs) } ?: "Date unknown", color = PhoebeUi.mutedText, fontSize = 10.sp)
    }
}

@Composable
private fun RecentlyAddedTrackRow(
    track: Track,
    isNowPlaying: Boolean,
    nowPlayingIsPlaying: Boolean,
    nowPlayingIsBuffering: Boolean,
    onPlay: () -> Unit,
    onAddToUpNext: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowShape = RoundedCornerShape(PhoebeUi.shapes.controlRadius)
    Row(
        modifier
            .fillMaxWidth()
            .clip(rowShape)
            .clickable(onClick = onPlay)
            .background(if (isNowPlaying) PhoebeUi.librarySelectedRow else PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, if (isNowPlaying) PhoebeUi.accent.copy(alpha = 0.45f) else PhoebeUi.border), rowShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RecentlyAddedTrackArt(track, isNowPlaying, nowPlayingIsPlaying, nowPlayingIsBuffering)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                track.title.ifBlank { "Unknown Title" },
                color = PhoebeUi.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { "Unknown Artist" },
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RecentlyAddedIconButton(PhoebeIcon.Queue, "Add to Up Next", onAddToUpNext)
        RecentlyAddedIconButton(PhoebeIcon.Download, "Download", onDownload)
    }
}

@Composable
private fun RecentlyAddedTrackArt(
    track: Track,
    isNowPlaying: Boolean,
    nowPlayingIsPlaying: Boolean,
    nowPlayingIsBuffering: Boolean,
) {
    Box(
        Modifier
            .size(50.dp)
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        TrackArtworkImage(
            track = track,
            modifier = Modifier.fillMaxSize(),
            radius = 8.dp,
            elevated = false,
            maxDecodeDimension = 128,
        )
        if (isNowPlaying) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                val icon = when {
                    nowPlayingIsBuffering -> PhoebeIcon.ActiveDot
                    nowPlayingIsPlaying -> PhoebeIcon.Pause
                    else -> PhoebeIcon.Play
                }
                PhoebeIconView(icon, tint = Color.White.copy(alpha = 0.84f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun RecentlyAddedIconButton(
    icon: PhoebeIcon,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), CircleShape)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        PhoebeIconView(icon, tint = PhoebeUi.secondaryText, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun RecentlyAddedEmpty(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(PhoebeUi.elevatedFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = PhoebeUi.secondaryText, fontSize = 14.sp)
    }
}

private data class RecentlyAddedPage(
    val title: String,
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
) {
    val count: Int
        get() = tracks.size + artists.size + albums.size

    companion object {
        fun from(kind: RecentlyAddedKind, catalog: CatalogSnapshot): RecentlyAddedPage {
            val albumAddedByTitle = albumAddedByTitle(catalog)
            val artistAddedByTitle = artistAddedByTitle(catalog)
            val tracks = catalog.tracksByParent.values
                .asSequence()
                .flatten()
                .distinctBy { it.id }
                .sortedByDescending { effectiveTrackDateAdded(it, albumAddedByTitle) }
                .toList()
            val albums = catalog.albums
                .sortedByDescending { it.dateAddedMs ?: 0L }
            val artists = catalog.artists
                .sortedByDescending { artist -> recentlyAddedAt(artist, artistAddedByTitle) }
            return when (kind) {
                RecentlyAddedKind.Songs -> RecentlyAddedPage("Songs", tracks = tracks)
                RecentlyAddedKind.Artists -> RecentlyAddedPage("Artists", artists = artists)
                RecentlyAddedKind.Albums -> RecentlyAddedPage("Albums", albums = albums)
            }
        }
    }
}

private fun recentlyAddedArtworkColors(seed: String): List<Color> {
    val hash = seed.hashCode()
    val red = (hash and 0xFF) / 255f
    val green = ((hash shr 8) and 0xFF) / 255f
    val blue = ((hash shr 16) and 0xFF) / 255f
    return listOf(
        Color(0.16f + red * 0.28f, 0.24f + green * 0.18f, 0.36f + blue * 0.24f, 1f),
        Color(0.42f + green * 0.22f, 0.25f + blue * 0.18f, 0.34f + red * 0.26f, 1f),
    )
}
