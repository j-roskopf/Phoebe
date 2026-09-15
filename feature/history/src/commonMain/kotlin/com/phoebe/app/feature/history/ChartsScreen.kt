package com.phoebe.app.feature.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoebe.app.ui.ArtworkImage
import com.phoebe.app.ui.LocalMobileChromePadding
import com.phoebe.app.ui.PhoebeIcon
import com.phoebe.app.ui.PhoebeIconView
import com.phoebe.app.ui.PhoebeUi
import com.phoebe.app.ui.SectionLabel

private const val ChartsWideLayoutBreakpointDp = 700
private const val ChartsInitialVisibleCount = 10
private const val ChartsLoadMorePageSize = 10

@Composable
fun ChartsScreen(
    state: ChartsUiState,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
    topBar: (@Composable () -> Unit)? = null,
    onArtistClick: (ChartsArtistRank) -> Unit = {},
    onSongClick: (ChartsSongRank) -> Unit = {},
    onPlaySong: (ChartsSongRank) -> Unit = {},
) {
    val chromePadding = LocalMobileChromePadding.current
    val listBottomPadding = bottomContentPadding.takeIf { it > 0.dp } ?: (chromePadding.bottom + 10.dp)
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        val wideLayout = maxWidth >= ChartsWideLayoutBreakpointDp.dp
        when {
            state.isLoading -> ChartsScaffold(
                topBar = topBar,
                bottomPadding = listBottomPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                ChartsLoading(Modifier.fillMaxSize())
            }
            state.isEmpty -> ChartsScaffold(
                topBar = topBar,
                bottomPadding = listBottomPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                ChartsEmpty(Modifier.fillMaxSize())
            }
            else -> ChartsContent(
                state = state,
                wideLayout = wideLayout,
                topBar = topBar,
                bottomPadding = listBottomPadding,
                onArtistClick = onArtistClick,
                onSongClick = onSongClick,
                onPlaySong = onPlaySong,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ChartsScaffold(
    topBar: (@Composable () -> Unit)?,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 10.dp,
            bottom = bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        topBar?.let { header ->
            item(key = "top-bar", contentType = "top-bar") { header() }
        }
        item(key = "body", contentType = "body") { content() }
    }
}

@Composable
private fun ChartsContent(
    state: ChartsUiState,
    wideLayout: Boolean,
    topBar: (@Composable () -> Unit)?,
    bottomPadding: Dp,
    onArtistClick: (ChartsArtistRank) -> Unit,
    onSongClick: (ChartsSongRank) -> Unit,
    onPlaySong: (ChartsSongRank) -> Unit,
    modifier: Modifier = Modifier,
) {
    val artists = state.topArtists.orEmpty()
    val songs = state.topSongs.orEmpty()
    var artistVisibleCount by remember(artists.size) { mutableIntStateOf(ChartsInitialVisibleCount) }
    var songVisibleCount by remember(songs.size) { mutableIntStateOf(ChartsInitialVisibleCount) }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 10.dp,
            bottom = bottomPadding,
        ),
    ) {
        topBar?.let { header ->
            item(key = "top-bar", contentType = "top-bar") { header() }
        }
        if (wideLayout) {
            item(contentType = "charts-wide-row") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ChartsSectionHeader("Top Artists", "Ranked by total plays")
                        ArtistsSectionBody(
                            artists = artists,
                            visibleCount = artistVisibleCount,
                            onArtistClick = onArtistClick,
                            onLoadMore = {
                                artistVisibleCount = (artistVisibleCount + ChartsLoadMorePageSize)
                                    .coerceAtMost(artists.size)
                            },
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ChartsSectionHeader("Top Songs", "Your most replayed tracks")
                        SongsSectionBody(
                            songs = songs,
                            visibleCount = songVisibleCount,
                            onSongClick = onSongClick,
                            onPlaySong = onPlaySong,
                            onLoadMore = {
                                songVisibleCount = (songVisibleCount + ChartsLoadMorePageSize)
                                    .coerceAtMost(songs.size)
                            },
                        )
                    }
                }
            }
        } else {
            item(contentType = "artists-header") { ChartsSectionHeader("Top Artists", "Ranked by total plays") }
            item(contentType = "artists-body") {
                ArtistsSectionBody(
                    artists = artists,
                    visibleCount = artistVisibleCount,
                    onArtistClick = onArtistClick,
                    onLoadMore = {
                        artistVisibleCount = (artistVisibleCount + ChartsLoadMorePageSize)
                            .coerceAtMost(artists.size)
                    },
                )
            }
            item(contentType = "songs-header") { ChartsSectionHeader("Top Songs", "Your most replayed tracks") }
            item(contentType = "songs-body") {
                SongsSectionBody(
                    songs = songs,
                    visibleCount = songVisibleCount,
                    onSongClick = onSongClick,
                    onPlaySong = onPlaySong,
                    onLoadMore = {
                        songVisibleCount = (songVisibleCount + ChartsLoadMorePageSize)
                            .coerceAtMost(songs.size)
                    },
                )
            }
        }
    }
}

@Composable
private fun ChartsSectionHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = PhoebeUi.mutedText, fontSize = 11.sp)
    }
}

@Composable
private fun ArtistsSectionBody(
    artists: List<ChartsArtistRank>,
    visibleCount: Int,
    onArtistClick: (ChartsArtistRank) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (artists.isEmpty()) {
        ChartsSectionEmpty("Play some artists and they'll show up here.", modifier)
        return
    }
    val visible = artists.take(visibleCount.coerceAtLeast(1))
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val hero = visible.first()
        ChartsArtistHero(hero, onClick = { onArtistClick(hero) })
        if (visible.size > 1) Spacer(Modifier.height(6.dp))
        visible.drop(1).forEachIndexed { index, artist ->
            ChartsArtistRow(rank = index + 2, artist = artist, onClick = { onArtistClick(artist) })
        }
        if (visibleCount < artists.size) {
            ChartsLoadMoreButton(remaining = artists.size - visibleCount, onClick = onLoadMore)
        }
    }
}

@Composable
private fun SongsSectionBody(
    songs: List<ChartsSongRank>,
    visibleCount: Int,
    onSongClick: (ChartsSongRank) -> Unit,
    onPlaySong: (ChartsSongRank) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (songs.isEmpty()) {
        ChartsSectionEmpty("Play some songs and they'll show up here.", modifier)
        return
    }
    val visible = songs.take(visibleCount.coerceAtLeast(1))
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val hero = visible.first()
        ChartsSongHero(hero, onClick = { onSongClick(hero) }, onPlay = { onPlaySong(hero) })
        if (visible.size > 1) Spacer(Modifier.height(6.dp))
        visible.drop(1).forEachIndexed { index, song ->
            ChartsSongRow(
                rank = index + 2,
                song = song,
                onClick = { onSongClick(song) },
                onPlay = { onPlaySong(song) },
            )
        }
        if (visibleCount < songs.size) {
            ChartsLoadMoreButton(remaining = songs.size - visibleCount, onClick = onLoadMore)
        }
    }
}

@Composable
private fun ChartsLoadMoreButton(remaining: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Text(
            "Load more ($remaining)",
            color = PhoebeUi.accentLight,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ChartsArtistHero(artist: ChartsArtistRank, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhoebeUi.shapes.panelRadius))
            .background(Brush.horizontalGradient(listOf(PhoebeUi.accent.copy(alpha = 0.16f), PhoebeUi.elevatedFill)))
            .border(BorderStroke(1.dp, PhoebeUi.accent.copy(alpha = 0.28f)), RoundedCornerShape(PhoebeUi.shapes.panelRadius))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            ArtworkImage(
                seed = artist.name,
                thumbUrl = artist.thumbUrl,
                modifier = Modifier.size(84.dp).clip(CircleShape),
                radius = 42.dp,
                elevated = false,
            )
            ChartsRankBadge(rank = 1, size = 26.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("#1 Artist", PhoebeUi.accentLight)
            Text(
                artist.name,
                color = PhoebeUi.primaryText,
                fontSize = 19.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                artistHeroSubtitle(artist),
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PhoebeIconView(PhoebeIcon.ChevronRight, tint = PhoebeUi.mutedText, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ChartsArtistRow(
    rank: Int,
    artist: ChartsArtistRank,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhoebeUi.shapes.controlRadius))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChartsRankNumeral(rank)
        ArtworkImage(
            seed = artist.name,
            thumbUrl = artist.thumbUrl,
            modifier = Modifier.size(44.dp).clip(CircleShape),
            radius = 22.dp,
            elevated = false,
            maxDecodeDimension = 128,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                artist.name,
                color = PhoebeUi.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist.trackCount > 0) {
                Text(pluralize(artist.trackCount, "track"), color = PhoebeUi.mutedText, fontSize = 11.sp)
            }
        }
        Text(
            formatPlayCount(artist.playCount),
            color = PhoebeUi.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ChartsSongHero(
    song: ChartsSongRank,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhoebeUi.shapes.panelRadius))
            .background(Brush.horizontalGradient(listOf(PhoebeUi.accent.copy(alpha = 0.16f), PhoebeUi.elevatedFill)))
            .border(BorderStroke(1.dp, PhoebeUi.accent.copy(alpha = 0.28f)), RoundedCornerShape(PhoebeUi.shapes.panelRadius))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            ArtworkImage(
                seed = song.album ?: song.title,
                thumbUrl = song.localArtworkUri ?: song.thumbUrl,
                modifier = Modifier.size(76.dp),
                radius = PhoebeUi.shapes.mediaRadius,
            )
            ChartsRankBadge(rank = 1, size = 24.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("#1 Song", PhoebeUi.accentLight)
            Text(
                song.title,
                color = PhoebeUi.primaryText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(song.artist, color = PhoebeUi.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatPlayCount(song.playCount), color = PhoebeUi.mutedText, fontSize = 11.sp)
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(PhoebeUi.accent)
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            PhoebeIconView(PhoebeIcon.Play, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ChartsSongRow(
    rank: Int,
    song: ChartsSongRank,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhoebeUi.shapes.controlRadius))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChartsRankNumeral(rank)
        ArtworkImage(
            seed = song.album ?: song.title,
            thumbUrl = song.localArtworkUri ?: song.thumbUrl,
            modifier = Modifier.size(44.dp),
            radius = PhoebeUi.shapes.mediaRadius,
            maxDecodeDimension = 128,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                song.title,
                color = PhoebeUi.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(song.artist, color = PhoebeUi.mutedText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            formatPlayCount(song.playCount),
            color = PhoebeUi.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(PhoebeUi.subtleFill)
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            PhoebeIconView(PhoebeIcon.Play, tint = PhoebeUi.secondaryText, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun ChartsRankBadge(rank: Int, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(PhoebeUi.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(rank.toString(), color = Color.White, fontSize = (size.value * 0.46f).sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun ChartsRankNumeral(rank: Int, modifier: Modifier = Modifier) {
    val color = when (rank) {
        2 -> PhoebeUi.accentLight
        3 -> PhoebeUi.secondaryText
        else -> PhoebeUi.mutedText
    }
    val fontSize = if (rank <= 3) 18.sp else 13.sp
    Text(
        rank.toString(),
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.Black,
        textAlign = TextAlign.Center,
        modifier = modifier.width(28.dp),
    )
}

@Composable
private fun ChartsSectionEmpty(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(PhoebeUi.shapes.panelRadius))
            .background(PhoebeUi.elevatedFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(PhoebeUi.shapes.panelRadius)),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = PhoebeUi.secondaryText, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ChartsEmpty(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(PhoebeUi.subtleFill)
                    .border(BorderStroke(1.dp, PhoebeUi.border), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                PhoebeIconView(PhoebeIcon.Visualizer, tint = PhoebeUi.mutedText, modifier = Modifier.size(24.dp))
            }
            Text("No charts yet", color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Black)
            Text(
                "Play some songs and your top artists and tracks will show up here.",
                color = PhoebeUi.secondaryText,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ChartsLoading(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = PhoebeUi.accentLight,
                strokeWidth = 3.dp,
                trackColor = PhoebeUi.progressTrack,
            )
            Text("Crunching your listening history...", color = PhoebeUi.secondaryText, fontSize = 14.sp)
        }
    }
}

private fun artistHeroSubtitle(artist: ChartsArtistRank): String {
    val plays = formatPlayCount(artist.playCount)
    return if (artist.trackCount > 0) {
        "$plays · ${pluralize(artist.trackCount, "track")}"
    } else {
        plays
    }
}

private fun formatPlayCount(playCount: Long): String = "$playCount ${if (playCount == 1L) "play" else "plays"}"

private fun pluralize(count: Int, noun: String): String = "$count $noun${if (count == 1) "" else "s"}"
