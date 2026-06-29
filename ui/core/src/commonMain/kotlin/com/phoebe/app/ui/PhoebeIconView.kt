package com.phoebe.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import phoebe.ui.core.generated.resources.Res
import phoebe.ui.core.generated.resources.drama_masks
import phoebe.ui.core.generated.resources.mood_very_good
import phoebe.ui.core.generated.resources.phoebe_icon_back
import phoebe.ui.core.generated.resources.phoebe_icon_bell
import phoebe.ui.core.generated.resources.phoebe_icon_book
import phoebe.ui.core.generated.resources.phoebe_icon_calendar
import phoebe.ui.core.generated.resources.phoebe_icon_cast
import phoebe.ui.core.generated.resources.phoebe_icon_chevron_down
import phoebe.ui.core.generated.resources.phoebe_icon_chevron_right
import phoebe.ui.core.generated.resources.phoebe_icon_chevron_up
import phoebe.ui.core.generated.resources.phoebe_icon_download
import phoebe.ui.core.generated.resources.phoebe_icon_drag
import phoebe.ui.core.generated.resources.phoebe_icon_equalizer
import phoebe.ui.core.generated.resources.phoebe_icon_forward
import phoebe.ui.core.generated.resources.phoebe_icon_heart_filled
import phoebe.ui.core.generated.resources.phoebe_icon_heart_outline
import phoebe.ui.core.generated.resources.phoebe_icon_home
import phoebe.ui.core.generated.resources.phoebe_icon_interwoven_arrows
import phoebe.ui.core.generated.resources.phoebe_icon_knife
import phoebe.ui.core.generated.resources.phoebe_icon_library
import phoebe.ui.core.generated.resources.phoebe_icon_lyrics
import phoebe.ui.core.generated.resources.phoebe_icon_music
import phoebe.ui.core.generated.resources.phoebe_icon_next
import phoebe.ui.core.generated.resources.phoebe_icon_person
import phoebe.ui.core.generated.resources.phoebe_icon_plus
import phoebe.ui.core.generated.resources.phoebe_icon_previous
import phoebe.ui.core.generated.resources.phoebe_icon_queue
import phoebe.ui.core.generated.resources.phoebe_icon_radio
import phoebe.ui.core.generated.resources.phoebe_icon_repeat
import phoebe.ui.core.generated.resources.phoebe_icon_search
import phoebe.ui.core.generated.resources.phoebe_icon_settings
import phoebe.ui.core.generated.resources.phoebe_icon_sunglasses_face
import phoebe.ui.core.generated.resources.phoebe_icon_thumbs_down
import phoebe.ui.core.generated.resources.phoebe_icon_thumbs_up
import phoebe.ui.core.generated.resources.phoebe_icon_volume

@Composable
fun PhoebeIconView(
    icon: PhoebeIcon,
    tint: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    icon.drawableResource(filled)?.let { resource ->
        Image(
            painter = painterResource(resource),
            contentDescription = null,
            modifier = modifier,
            colorFilter = ColorFilter.tint(tint),
            contentScale = ContentScale.Fit,
        )
        return
    }

    Canvas(modifier) {
        val s = size.minDimension
        val strokeWidth = (s * 0.073f).coerceAtLeast(1.35f)
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(s * x, s * y)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = strokeWidth, cap = StrokeCap.Round)

        when (icon) {
            PhoebeIcon.PlaylistPlay -> {
                line(0.18f, 0.26f, 0.82f, 0.26f)
                line(0.18f, 0.44f, 0.48f, 0.44f)
                line(0.18f, 0.62f, 0.48f, 0.62f)
                line(0.18f, 0.80f, 0.48f, 0.80f)
                val play = Path().apply {
                    moveTo(s * 0.62f, s * 0.45f)
                    lineTo(s * 0.62f, s * 0.80f)
                    lineTo(s * 0.86f, s * 0.63f)
                    close()
                }
                drawPath(play, tint, style = stroke)
            }
            PhoebeIcon.Play -> {
                val path = Path().apply {
                    moveTo(s * 0.34f, s * 0.22f)
                    lineTo(s * 0.76f, s * 0.50f)
                    lineTo(s * 0.34f, s * 0.78f)
                    close()
                }
                drawPath(path, tint)
            }
            PhoebeIcon.Pause -> {
                drawRoundRect(tint, topLeft = Offset(s * 0.32f, s * 0.22f), size = Size(s * 0.12f, s * 0.56f), cornerRadius = CornerRadius(s * 0.04f, s * 0.04f))
                drawRoundRect(tint, topLeft = Offset(s * 0.56f, s * 0.22f), size = Size(s * 0.12f, s * 0.56f), cornerRadius = CornerRadius(s * 0.04f, s * 0.04f))
            }
            PhoebeIcon.Edit -> {
                line(0.24f, 0.76f, 0.36f, 0.64f)
                line(0.36f, 0.64f, 0.70f, 0.30f)
                line(0.70f, 0.30f, 0.78f, 0.38f)
                line(0.78f, 0.38f, 0.44f, 0.72f)
                line(0.44f, 0.72f, 0.24f, 0.76f)
                line(0.63f, 0.37f, 0.71f, 0.45f)
            }
            PhoebeIcon.More -> {
                drawCircle(tint, radius = s * 0.045f, center = p(0.28f, 0.50f))
                drawCircle(tint, radius = s * 0.045f, center = p(0.50f, 0.50f))
                drawCircle(tint, radius = s * 0.045f, center = p(0.72f, 0.50f))
            }
            PhoebeIcon.ActiveDot -> {
                drawCircle(tint, radius = s * 0.22f, center = p(0.50f, 0.50f))
            }
            PhoebeIcon.Grid -> {
                val cell = s * 0.22f
                listOf(0.24f to 0.24f, 0.54f to 0.24f, 0.24f to 0.54f, 0.54f to 0.54f).forEach { (x, y) ->
                    drawRoundRect(
                        tint,
                        topLeft = Offset(s * x, s * y),
                        size = Size(cell, cell),
                        cornerRadius = CornerRadius(s * 0.045f, s * 0.045f),
                        style = stroke,
                    )
                }
            }
            PhoebeIcon.Close -> {
                line(0.30f, 0.30f, 0.70f, 0.70f)
                line(0.70f, 0.30f, 0.30f, 0.70f)
            }
            PhoebeIcon.Check -> {
                line(0.22f, 0.52f, 0.42f, 0.72f)
                line(0.42f, 0.72f, 0.80f, 0.30f)
            }
            PhoebeIcon.Visualizer -> {
                val outerRadius = s * 0.42f
                val strokeW = (s * 0.065f).coerceAtLeast(1.5f)
                val barW = (s * 0.065f).coerceAtLeast(1.5f)
                // Draw outer circle
                drawCircle(
                    color = tint,
                    radius = outerRadius,
                    center = p(0.50f, 0.50f),
                    style = Stroke(width = strokeW),
                )
                // Draw five vertical bars with round caps
                val spacing = 0.11f
                val barHeights = listOf(0.26f, 0.42f, 0.56f, 0.42f, 0.26f)
                repeat(5) { i ->
                    val x = 0.50f + (i - 2) * spacing
                    val h = barHeights[i]
                    drawLine(
                        color = tint,
                        start = p(x, 0.50f - h / 2f),
                        end = p(x, 0.50f + h / 2f),
                        strokeWidth = barW,
                        cap = StrokeCap.Round,
                    )
                }
            }
            PhoebeIcon.Fullscreen -> {
                line(0.24f, 0.42f, 0.24f, 0.24f)
                line(0.24f, 0.24f, 0.42f, 0.24f)
                line(0.58f, 0.24f, 0.76f, 0.24f)
                line(0.76f, 0.24f, 0.76f, 0.42f)
                line(0.76f, 0.58f, 0.76f, 0.76f)
                line(0.76f, 0.76f, 0.58f, 0.76f)
                line(0.42f, 0.76f, 0.24f, 0.76f)
                line(0.24f, 0.76f, 0.24f, 0.58f)
            }
            PhoebeIcon.Update -> {
                line(0.50f, 0.22f, 0.50f, 0.76f)
                line(0.27f, 0.44f, 0.50f, 0.22f)
                line(0.73f, 0.44f, 0.50f, 0.22f)
                line(0.26f, 0.80f, 0.74f, 0.80f)
            }
            else -> Unit
        }
    }
}

private fun PhoebeIcon.drawableResource(filled: Boolean): DrawableResource? =
    when (this) {
        PhoebeIcon.Home -> Res.drawable.phoebe_icon_home
        PhoebeIcon.Search -> Res.drawable.phoebe_icon_search
        PhoebeIcon.Library -> Res.drawable.phoebe_icon_library
        PhoebeIcon.Person -> Res.drawable.phoebe_icon_person
        PhoebeIcon.Calendar -> Res.drawable.phoebe_icon_calendar
        PhoebeIcon.Book -> Res.drawable.phoebe_icon_book
        PhoebeIcon.Knife -> Res.drawable.phoebe_icon_knife
        PhoebeIcon.InterwovenArrows -> Res.drawable.phoebe_icon_interwoven_arrows
        PhoebeIcon.MoodFace -> Res.drawable.mood_very_good
        PhoebeIcon.SunglassesFace -> Res.drawable.phoebe_icon_sunglasses_face
        PhoebeIcon.GenreMasks -> Res.drawable.drama_masks
        PhoebeIcon.Settings -> Res.drawable.phoebe_icon_settings
        PhoebeIcon.Plus -> Res.drawable.phoebe_icon_plus
        PhoebeIcon.Heart -> if (filled) Res.drawable.phoebe_icon_heart_filled else Res.drawable.phoebe_icon_heart_outline
        PhoebeIcon.ThumbsUp -> Res.drawable.phoebe_icon_thumbs_up
        PhoebeIcon.ThumbsDown -> Res.drawable.phoebe_icon_thumbs_down
        PhoebeIcon.ChevronUp -> Res.drawable.phoebe_icon_chevron_up
        PhoebeIcon.ChevronDown -> Res.drawable.phoebe_icon_chevron_down
        PhoebeIcon.ChevronRight -> Res.drawable.phoebe_icon_chevron_right
        PhoebeIcon.Bell -> Res.drawable.phoebe_icon_bell
        PhoebeIcon.Back -> Res.drawable.phoebe_icon_back
        PhoebeIcon.Forward -> Res.drawable.phoebe_icon_forward
        PhoebeIcon.Music -> Res.drawable.phoebe_icon_music
        PhoebeIcon.Lyrics -> Res.drawable.phoebe_icon_lyrics
        PhoebeIcon.Previous -> Res.drawable.phoebe_icon_previous
        PhoebeIcon.Next -> Res.drawable.phoebe_icon_next
        PhoebeIcon.Volume -> Res.drawable.phoebe_icon_volume
        PhoebeIcon.Equalizer -> Res.drawable.phoebe_icon_equalizer
        PhoebeIcon.Queue -> Res.drawable.phoebe_icon_queue
        PhoebeIcon.Cast -> Res.drawable.phoebe_icon_cast
        PhoebeIcon.Download -> Res.drawable.phoebe_icon_download
        PhoebeIcon.Repeat -> Res.drawable.phoebe_icon_repeat
        PhoebeIcon.Drag -> Res.drawable.phoebe_icon_drag
        PhoebeIcon.Radio -> Res.drawable.phoebe_icon_radio
        PhoebeIcon.PlaylistPlay,
        PhoebeIcon.Play,
        PhoebeIcon.Pause,
        PhoebeIcon.Edit,
        PhoebeIcon.More,
        PhoebeIcon.ActiveDot,
        PhoebeIcon.Grid,
        PhoebeIcon.Close,
        PhoebeIcon.Check,
        PhoebeIcon.Visualizer,
        PhoebeIcon.Fullscreen,
        PhoebeIcon.Update,
        -> null
    }
