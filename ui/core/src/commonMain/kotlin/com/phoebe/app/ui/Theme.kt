package com.phoebe.app.ui

import androidx.compose.foundation.background
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF8B3DFF),
    onPrimary = Color.White,
    secondary = Color(0xFF7C2CF2),
    tertiary = Color(0xFF277A65),
    background = Color(0xFFF3F4F7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF7F8FA),
    onBackground = Color(0xFF181B22),
    onSurface = Color(0xFF181B22),
    onSurfaceVariant = Color(0xFF4D5563),
    outline = Color(0x29181B22),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9B4DFF),
    secondary = Color(0xFFA855F7),
    tertiary = Color(0xFF6D7EDB),
    background = Color(0xFF080B12),
    surface = Color(0xFF10151F),
    surfaceVariant = Color(0xFF121722),
    onBackground = Color(0xFFF4F5F7),
    onSurface = Color(0xFFF4F5F7),
)

@Composable
fun PhoebeTheme(
    useLightAppearance: Boolean = false,
    tintId: String = PhoebeTintOption.Purple.id,
    content: @Composable () -> Unit,
) {
    val tint = PhoebeTintOption.fromId(tintId)
    val palette = (if (useLightAppearance) PhoebePaletteLight else PhoebePaletteDark)
        .withTint(tint, useLightAppearance)
    val colors: ColorScheme = (if (useLightAppearance) LightColors else DarkColors).copy(
        primary = palette.accent,
        secondary = palette.accentLight,
    )
    ApplySystemBarAppearance(
        statusBarColor = palette.shellTop,
        navigationBarColor = palette.navBar,
        useLightIcons = useLightAppearance,
    )
    CompositionLocalProvider(LocalPhoebePalette provides palette) {
        MaterialTheme(
            colorScheme = colors,
            typography = MaterialTheme.typography,
            content = content,
        )
    }
}

@Composable
fun Modifier.phoebeShellBackground(
    tintedGradient: Boolean,
    center: Offset = Offset(420f, 40f),
    radius: Float = 960f,
): Modifier =
    if (tintedGradient) {
        background(Brush.verticalGradient(listOf(PhoebeUi.shellTop, PhoebeUi.shellBottom)))
            .background(
                Brush.radialGradient(
                    colors = listOf(PhoebeUi.shellRadialTint, Color.Transparent),
                    center = center,
                    radius = radius,
                ),
            )
    } else {
        background(Brush.verticalGradient(listOf(PhoebeUi.shellTop, PhoebeUi.shellBottom)))
    }
