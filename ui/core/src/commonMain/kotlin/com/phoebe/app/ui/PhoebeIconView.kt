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
import phoebe.ui.core.generated.resources.phoebe_icon_chevron_down
import phoebe.ui.core.generated.resources.phoebe_icon_chevron_right
import phoebe.ui.core.generated.resources.phoebe_icon_chevron_up
import phoebe.ui.core.generated.resources.phoebe_icon_download
import phoebe.ui.core.generated.resources.phoebe_icon_drag
import phoebe.ui.core.generated.resources.phoebe_icon_forward
import phoebe.ui.core.generated.resources.phoebe_icon_guitar
import phoebe.ui.core.generated.resources.phoebe_icon_heart_filled
import phoebe.ui.core.generated.resources.phoebe_icon_heart_outline
import phoebe.ui.core.generated.resources.phoebe_icon_home
import phoebe.ui.core.generated.resources.phoebe_icon_knife
import phoebe.ui.core.generated.resources.phoebe_icon_library
import phoebe.ui.core.generated.resources.phoebe_icon_music
import phoebe.ui.core.generated.resources.phoebe_icon_person
import phoebe.ui.core.generated.resources.phoebe_icon_plus
import phoebe.ui.core.generated.resources.phoebe_icon_radio
import phoebe.ui.core.generated.resources.phoebe_icon_remote_device
import phoebe.ui.core.generated.resources.phoebe_icon_search
import phoebe.ui.core.generated.resources.phoebe_icon_settings
import phoebe.ui.core.generated.resources.phoebe_icon_sunglasses_face
import phoebe.ui.core.generated.resources.phoebe_icon_thumbs_down
import phoebe.ui.core.generated.resources.phoebe_icon_thumbs_up

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
            colorFilter = if (icon.preservesOriginalColors()) null else ColorFilter.tint(tint),
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
            PhoebeIcon.InterwovenArrows -> {
                val first = Path().apply {
                    moveTo(s * 0.18f, s * 0.30f)
                    lineTo(s * 0.34f, s * 0.30f)
                    lineTo(s * 0.66f, s * 0.70f)
                    lineTo(s * 0.82f, s * 0.70f)
                }
                drawPath(first, tint, style = stroke)
                line(0.66f, 0.57f, 0.82f, 0.70f)
                line(0.66f, 0.83f, 0.82f, 0.70f)

                val second = Path().apply {
                    moveTo(s * 0.18f, s * 0.70f)
                    lineTo(s * 0.34f, s * 0.70f)
                    lineTo(s * 0.47f, s * 0.54f)
                    lineTo(s * 0.66f, s * 0.30f)
                    lineTo(s * 0.82f, s * 0.30f)
                }
                drawPath(second, tint, style = stroke)
                line(0.66f, 0.17f, 0.82f, 0.30f)
                line(0.66f, 0.43f, 0.82f, 0.30f)
            }
            PhoebeIcon.Lyrics -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(s * 0.22f, s * 0.18f),
                    size = Size(s * 0.56f, s * 0.64f),
                    cornerRadius = CornerRadius(s * 0.05f, s * 0.05f),
                    style = stroke,
                )
                line(0.34f, 0.38f, 0.66f, 0.38f)
                line(0.34f, 0.52f, 0.66f, 0.52f)
                line(0.34f, 0.66f, 0.56f, 0.66f)
            }
            PhoebeIcon.Previous -> {
                drawRect(tint, topLeft = Offset(s * 0.22f, s * 0.24f), size = Size(s * 0.10f, s * 0.52f))
                val path = Path().apply {
                    moveTo(s * 0.76f, s * 0.24f)
                    lineTo(s * 0.40f, s * 0.50f)
                    lineTo(s * 0.76f, s * 0.76f)
                    close()
                }
                drawPath(path, tint)
            }
            PhoebeIcon.Next -> {
                val path = Path().apply {
                    moveTo(s * 0.24f, s * 0.24f)
                    lineTo(s * 0.60f, s * 0.50f)
                    lineTo(s * 0.24f, s * 0.76f)
                    close()
                }
                drawPath(path, tint)
                drawRect(tint, topLeft = Offset(s * 0.68f, s * 0.24f), size = Size(s * 0.10f, s * 0.52f))
            }
            PhoebeIcon.Volume -> {
                val speaker = Path().apply {
                    moveTo(s * 0.16f, s * 0.42f)
                    lineTo(s * 0.34f, s * 0.42f)
                    lineTo(s * 0.56f, s * 0.25f)
                    lineTo(s * 0.56f, s * 0.75f)
                    lineTo(s * 0.34f, s * 0.58f)
                    lineTo(s * 0.16f, s * 0.58f)
                    close()
                }
                drawPath(speaker, tint)
                val wave = Path().apply {
                    moveTo(s * 0.67f, s * 0.36f)
                    quadraticTo(s * 0.82f, s * 0.50f, s * 0.67f, s * 0.64f)
                }
                drawPath(wave, tint, style = stroke)
                val outerWave = Path().apply {
                    moveTo(s * 0.76f, s * 0.26f)
                    quadraticTo(s * 0.98f, s * 0.50f, s * 0.76f, s * 0.74f)
                }
                drawPath(outerWave, tint, style = stroke)
            }
            PhoebeIcon.Equalizer -> {
                line(0.27f, 0.72f, 0.27f, 0.36f)
                line(0.50f, 0.72f, 0.50f, 0.22f)
                line(0.73f, 0.72f, 0.73f, 0.48f)
            }
            PhoebeIcon.Queue -> {
                line(0.22f, 0.28f, 0.78f, 0.28f)
                line(0.22f, 0.50f, 0.78f, 0.50f)
                line(0.22f, 0.72f, 0.56f, 0.72f)
                line(0.67f, 0.64f, 0.67f, 0.80f)
                line(0.59f, 0.72f, 0.75f, 0.72f)
            }
            PhoebeIcon.Cast -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(s * 0.24f, s * 0.20f),
                    size = Size(s * 0.56f, s * 0.42f),
                    cornerRadius = CornerRadius(s * 0.04f, s * 0.04f),
                    style = stroke,
                )
                drawCircle(tint, radius = s * 0.055f, center = p(0.25f, 0.76f))
                val innerArc = Path().apply {
                    moveTo(s * 0.25f, s * 0.62f)
                    quadraticTo(s * 0.39f, s * 0.62f, s * 0.39f, s * 0.76f)
                }
                drawPath(innerArc, tint, style = stroke)
                val outerArc = Path().apply {
                    moveTo(s * 0.25f, s * 0.50f)
                    quadraticTo(s * 0.51f, s * 0.50f, s * 0.51f, s * 0.76f)
                }
                drawPath(outerArc, tint, style = stroke)
            }
            PhoebeIcon.Repeat -> {
                drawArc(
                    color = tint,
                    startAngle = 200f,
                    sweepAngle = 205f,
                    useCenter = false,
                    topLeft = Offset(s * 0.22f, s * 0.22f),
                    size = Size(s * 0.56f, s * 0.56f),
                    style = stroke,
                )
                line(0.22f, 0.39f, 0.22f, 0.22f)
                line(0.22f, 0.22f, 0.39f, 0.22f)
                line(0.78f, 0.61f, 0.78f, 0.78f)
                line(0.78f, 0.78f, 0.61f, 0.78f)
            }
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
        PhoebeIcon.Guitar -> Res.drawable.phoebe_icon_guitar
        PhoebeIcon.Knife -> Res.drawable.phoebe_icon_knife
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
        PhoebeIcon.RemoteDevice -> Res.drawable.phoebe_icon_remote_device
        PhoebeIcon.Download -> Res.drawable.phoebe_icon_download
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
        PhoebeIcon.InterwovenArrows,
        PhoebeIcon.Lyrics,
        PhoebeIcon.Previous,
        PhoebeIcon.Next,
        PhoebeIcon.Volume,
        PhoebeIcon.Equalizer,
        PhoebeIcon.Queue,
        PhoebeIcon.Cast,
        PhoebeIcon.Repeat,
        -> null
    }

private fun PhoebeIcon.preservesOriginalColors(): Boolean =
    this == PhoebeIcon.Guitar
