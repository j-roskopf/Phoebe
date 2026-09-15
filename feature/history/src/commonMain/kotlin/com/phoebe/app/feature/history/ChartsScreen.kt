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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.phoebe.app.ui.PhoebeIcon
import com.phoebe.app.ui.PhoebeIconView
import com.phoebe.app.ui.PhoebeUi
import com.phoebe.app.ui.SectionLabel
import com.phoebe.app.ui.mobileContentTopPadding

private const val ChartsWideLayoutBreakpointDp = 700
private const val ChartsMaxRowsPerSection = 9

@Composable
fun ChartsScreen(
    state: ChartsUiState,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
    onArtistClick: (ChartsArtistRank) -> Unit = {},
    onSongClick: (ChartsSongRank) -> Unit = {},
    onPlaySong: (ChartsSongRank) -> Unit = {},
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = mobileContentTopPadding(12.dp),
                bottom = 12.dp + bottomContentPadding,
            ),
    ) {
        val wideLayout = maxWidth >= ChartsWideLayoutBreakpointDp.dp
        when {
            state.isLoading -> ChartsLoading(Modifier.fillMaxSize())
            state.isEmpty -> ChartsEmpty(Modifier.fillMaxSize())
            else -> ChartsContent(
                state = state,
                wideLayout = wideLayout,
                onArtistClick = onArtistClick,
                onSongClick = onSongClick,
                onPlaySong = onPlaySong,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ChartsContent(
    state: ChartsUiState,
    wideLayout: Boolean,
    onArtistClick: (ChartsArtistRank) -> Unit,
    onSongClick: (ChartsSongRank) -> Unit,
    onPlaySong: (ChartsSongRank) -> Unit,
    modifier: Modifier = Modifier,
) {
    val artists = state.topArtists.orEmpty()
    val songs = state.topSongs.orEmpty()
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item(contentType = "charts-header") { ChartsHeader(state.periodLabel) }
        if (wideLayout) {
            item(contentType = "charts-wide-row") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ChartsSectionHeader("Top Artists", "Ranked by total plays")
                        ArtistsSectionBody(artists, onArtistClick)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ChartsSectionHeader("Top Songs", "Your most replayed tracks")
                        SongsSectionBody(songs, onSongClick, onPlaySong)
                    }
                }
            }
        } else {
            item(contentType = "artists-header") { ChartsSectionHeader("Top Artists", "Ranked by total plays") }
            item(contentType = "artists-body") { ArtistsSectionBody(artists, onArtistClick) }
            item(contentType = "songs-header") { ChartsSectionHeader("Top Songs", "Your most replayed tracks") }
            item(contentType = "songs-body") { SongsSectionBody(songs, onSongClick, onPlaySong) }
        }
    }
}

@Composable
private fun ChartsHeader(periodLabel: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("Charts", PhoebeUi.mutedText)
            Text("Your Top Sounds", color = PhoebeUi.primaryText, fontSize = 26.sp, fontWeight = FontWeight.Black)
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(PhoebeUi.shapes.buttonRadius))
                .background(PhoebeUi.subtleFill)
                .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(PhoebeUi.shapes.buttonRadius))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(periodLabel, color = PhoebeUi.secondaryText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
    onArtistClick: (ChartsArtistRank) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (artists.isEmpty()) {
        ChartsSectionEmpty("Play some artists and they'll show up here.", modifier)
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val hero = artists.first()
        ChartsArtistHero(hero, onClick = { onArtistClick(hero) })
        if (artists.size > 1) Spacer(Modifier.height(6.dp))
        artists.drop(1).take(ChartsMaxRowsPerSection).forEachIndexed { index, artist ->
            ChartsArtistRow(rank = index + 2, artist = artist, onClick = { onArtistClick(artist) })
        }
    }
}

@Composable
private fun SongsSectionBody(
    songs: List<ChartsSongRank>,
    onSongClick: (ChartsSongRank) -> Unit,
    onPlaySong: (ChartsSongRank) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (songs.isEmpty()) {
        ChartsSectionEmpty("Play some songs and they'll show up here.", modifier)
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val hero = songs.first()
        ChartsSongHero(hero, onClick = { onSongClick(hero) }, onPlay = { onPlaySong(hero) })
        if (songs.size > 1) Spacer(Modifier.height(6.dp))
        songs.drop(1).take(ChartsMaxRowsPerSection).forEachIndexed { index, song ->
            ChartsSongRow(
                rank = index + 2,
                song = song,
                onClick = { onSongClick(song) },
                onPlay = { onPlaySong(song) },
            )
        }
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
                "${formatPlayCount(artist.playCount)} · ${pluralize(artist.trackCount, "track")}",
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
            Text(pluralize(artist.trackCount, "track"), color = PhoebeUi.mutedText, fontSize = 11.sp)
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
            elevated = false,
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
            Text(song.artist, color = PhoebeUi.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(PhoebeUi.accent.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(formatPlayCount(song.playCount), color = PhoebeUi.accentLight, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
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

private fun formatPlayCount(playCount: Long): String = "$playCount ${if (playCount == 1L) "play" else "plays"}"

private fun pluralize(count: Int, noun: String): String = "$count $noun${if (count == 1) "" else "s"}"
