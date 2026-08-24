package com.phoebe.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoebe.app.domain.StreamingQuality
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.keepsOriginalStreamFor

@Composable
fun AudioQualityBadge(
    track: Track?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onArtwork: Boolean = false,
    playingQuality: StreamingQuality = StreamingQuality.Original,
) {
    val badge = audioQualityBadgeLabel(track, playingQuality) ?: return
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (onArtwork) AudioQualityBackdrop else AudioQualityGold.copy(alpha = 0.13f))
            .border(
                BorderStroke(1.dp, if (onArtwork) AudioQualityGold.copy(alpha = 0.9f) else AudioQualityGold.copy(alpha = 0.55f)),
                shape,
            )
            .padding(horizontal = if (compact) 7.dp else 9.dp, vertical = if (compact) 3.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            badge,
            color = AudioQualityGold,
            fontSize = if (compact) 9.sp else 10.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
    }
}

@Composable
fun AudioQualityText(
    track: Track?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    playingQuality: StreamingQuality = StreamingQuality.Original,
) {
    val badge = audioQualityBadgeLabel(track, playingQuality) ?: return
    Text(
        badge,
        modifier = modifier,
        color = AudioQualityGold,
        fontSize = if (compact) 9.sp else 10.sp,
        fontWeight = FontWeight.Black,
        maxLines = 1,
    )
}

private val AudioQualityGold = Color(0xFFD6A84A)
private val AudioQualityBackdrop = Color(0xD90F0D08)

internal fun audioQualityBadgeLabel(
    track: Track?,
    playingQuality: StreamingQuality = StreamingQuality.Original,
): String? {
    track ?: return null
    val presented = if (track.keepsOriginalStreamFor(playingQuality)) {
        track
    } else {
        val bitrate = playingQuality.maxAudioBitrateKbps ?: return sourceAudioQualityBadgeLabel(track)
        track.copy(audioCodec = "mp3", bitrateKbps = bitrate, filepath = "stream.mp3")
    }
    return sourceAudioQualityBadgeLabel(presented)
}

private fun sourceAudioQualityBadgeLabel(track: Track): String? {
    val bitrate = track.bitrateKbps?.takeIf { it > 0 }
    val codec = track.audioCodec.orEmpty().trim().lowercase()
    val extension = track.filepath
        ?.substringAfterLast('/', missingDelimiterValue = "")
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()
        .orEmpty()
    val isFlac = codec.contains("flac") || codec.contains("alac") || extension == "flac" || extension == "alac"
    val isMp3 = codec.contains("mp3") || codec.contains("mpeg") || extension == "mp3"
    return when {
        isFlac && bitrate == null -> if (extension == "alac" || codec.contains("alac")) "ALAC" else "FLAC"
        isFlac -> when (bitrate ?: return null) {
            in 1500..Int.MAX_VALUE -> "HI-RES QUALITY"
            in 700..Int.MAX_VALUE -> "CD QUALITY"
            in 320..Int.MAX_VALUE -> "LOSSLESS QUALITY"
            else -> "LOSSLESS LOW"
        }
        isMp3 && bitrate == null -> "MP3"
        isMp3 -> when (bitrate ?: return null) {
            in 320..Int.MAX_VALUE -> "EXCELLENT QUALITY"
            in 256..Int.MAX_VALUE -> "HIGH QUALITY"
            in 192..Int.MAX_VALUE -> "STANDARD QUALITY"
            in 128..Int.MAX_VALUE -> "BASIC QUALITY"
            else -> "LOW QUALITY"
        }
        bitrate == null -> null
        bitrate >= 1500 -> "HI-RES QUALITY"
        bitrate >= 700 -> "CD QUALITY"
        bitrate >= 320 -> "EXCELLENT QUALITY"
        bitrate >= 256 -> "HIGH QUALITY"
        bitrate >= 192 -> "STANDARD QUALITY"
        bitrate >= 128 -> "BASIC QUALITY"
        else -> "LOW QUALITY"
    }
}
