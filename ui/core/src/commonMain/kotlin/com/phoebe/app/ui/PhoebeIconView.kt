package com.phoebe.app.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import org.jetbrains.compose.resources.ExperimentalResourceApi
import phoebe.ui.core.generated.resources.Res

private val iconSvgBytesCache = mutableMapOf<String, ByteArray>()

@Composable
fun PhoebeIconView(
    icon: PhoebeIcon,
    tint: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    val resourcePath = icon.svgResourcePath(filled) ?: return
    val bytes = rememberIconSvgBytes(resourcePath) ?: return
    val context = LocalPlatformContext.current
    val imageLoader = rememberIconImageLoader()
    val request = remember(context, resourcePath, bytes) {
        ImageRequest.Builder(context)
            .data(bytes)
            .memoryCacheKey(resourcePath)
            .diskCachePolicy(CachePolicy.DISABLED)
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    val painter = rememberAsyncImagePainter(model = request, imageLoader = imageLoader)
    Image(
        painter = painter,
        contentDescription = null,
        modifier = modifier,
        colorFilter = ColorFilter.tint(tint),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun rememberIconImageLoader(): ImageLoader {
    val context = LocalPlatformContext.current
    return remember(context) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun rememberIconSvgBytes(path: String): ByteArray? {
    iconSvgBytesCache[path]?.let { return it }
    val bytes by produceState<ByteArray?>(initialValue = null, path) {
        val loaded = Res.readBytes(path)
        iconSvgBytesCache[path] = loaded
        value = loaded
    }
    return bytes
}

private fun PhoebeIcon.svgResourcePath(filled: Boolean): String? =
    when (this) {
        PhoebeIcon.Home -> "files/icons/phoebe_icon_home.svg"
        PhoebeIcon.Search -> "files/icons/phoebe_icon_search.svg"
        PhoebeIcon.Library -> "files/icons/phoebe_icon_library.svg"
        PhoebeIcon.Radio -> "files/icons/phoebe_icon_radio.svg"
        PhoebeIcon.Person -> "files/icons/phoebe_icon_person.svg"
        PhoebeIcon.Calendar -> "files/icons/phoebe_icon_calendar.svg"
        PhoebeIcon.Book -> "files/icons/phoebe_icon_book.svg"
        PhoebeIcon.Guitar -> "files/icons/phoebe_icon_guitar.svg"
        PhoebeIcon.Knife -> "files/icons/phoebe_icon_knife.svg"
        PhoebeIcon.InterwovenArrows -> "files/icons/phoebe_icon_interwoven_arrows.svg"
        PhoebeIcon.MoodFace -> "files/icons/phoebe_icon_mood_face.svg"
        PhoebeIcon.SunglassesFace -> "files/icons/phoebe_icon_sunglasses_face.svg"
        PhoebeIcon.GenreMasks -> "files/icons/phoebe_icon_genre_masks.svg"
        PhoebeIcon.PlaylistPlay -> "files/icons/phoebe_icon_playlist_play.svg"
        PhoebeIcon.Settings -> "files/icons/phoebe_icon_settings.svg"
        PhoebeIcon.Plus -> "files/icons/phoebe_icon_plus.svg"
        PhoebeIcon.Heart ->
            if (filled) {
                "files/icons/phoebe_icon_heart_filled.svg"
            } else {
                "files/icons/phoebe_icon_heart_outline.svg"
            }
        PhoebeIcon.ThumbsUp -> "files/icons/phoebe_icon_thumbs_up.svg"
        PhoebeIcon.ThumbsDown -> "files/icons/phoebe_icon_thumbs_down.svg"
        PhoebeIcon.ChevronUp -> "files/icons/phoebe_icon_chevron_up.svg"
        PhoebeIcon.ChevronDown -> "files/icons/phoebe_icon_chevron_down.svg"
        PhoebeIcon.ChevronRight -> "files/icons/phoebe_icon_chevron_right.svg"
        PhoebeIcon.Bell -> "files/icons/phoebe_icon_bell.svg"
        PhoebeIcon.Back -> "files/icons/phoebe_icon_back.svg"
        PhoebeIcon.Forward -> "files/icons/phoebe_icon_forward.svg"
        PhoebeIcon.Music -> "files/icons/phoebe_icon_music.svg"
        PhoebeIcon.Lyrics -> "files/icons/phoebe_icon_lyrics.svg"
        PhoebeIcon.Previous -> "files/icons/phoebe_icon_previous.svg"
        PhoebeIcon.Next -> "files/icons/phoebe_icon_next.svg"
        PhoebeIcon.Play -> "files/icons/phoebe_icon_play.svg"
        PhoebeIcon.Pause -> "files/icons/phoebe_icon_pause.svg"
        PhoebeIcon.Volume -> "files/icons/phoebe_icon_volume.svg"
        PhoebeIcon.Equalizer -> "files/icons/phoebe_icon_equalizer.svg"
        PhoebeIcon.Queue -> "files/icons/phoebe_icon_queue.svg"
        PhoebeIcon.Cast -> "files/icons/phoebe_icon_cast.svg"
        PhoebeIcon.RemoteDevice -> "files/icons/phoebe_icon_remote_device.svg"
        PhoebeIcon.Download -> "files/icons/phoebe_icon_download.svg"
        PhoebeIcon.Repeat -> "files/icons/phoebe_icon_repeat.svg"
        PhoebeIcon.Drag -> "files/icons/phoebe_icon_drag.svg"
        PhoebeIcon.Edit -> "files/icons/phoebe_icon_edit.svg"
        PhoebeIcon.More -> "files/icons/phoebe_icon_more.svg"
        PhoebeIcon.ActiveDot -> "files/icons/phoebe_icon_active_dot.svg"
        PhoebeIcon.Grid -> "files/icons/phoebe_icon_grid.svg"
        PhoebeIcon.Close -> "files/icons/phoebe_icon_close.svg"
        PhoebeIcon.Check -> "files/icons/phoebe_icon_check.svg"
        PhoebeIcon.Visualizer -> "files/icons/phoebe_icon_visualizer.svg"
        PhoebeIcon.Fullscreen -> "files/icons/phoebe_icon_fullscreen.svg"
        PhoebeIcon.Update -> "files/icons/phoebe_icon_update.svg"
    }
