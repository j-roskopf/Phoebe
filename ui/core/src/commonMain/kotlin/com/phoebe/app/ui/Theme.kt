package com.phoebe.app.ui

import androidx.compose.foundation.background
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import phoebe.ui.core.generated.resources.Res
import phoebe.ui.core.generated.resources.archivo_black_regular
import phoebe.ui.core.generated.resources.geist_mono_variable
import phoebe.ui.core.generated.resources.geist_variable
import phoebe.ui.core.generated.resources.instrument_serif_italic
import phoebe.ui.core.generated.resources.instrument_serif_regular

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
    designId: String = PhoebeDesignSystem.Default.id,
    content: @Composable () -> Unit,
) {
    val design = PhoebeDesignSystem.fromId(designId)
    val pack = design.themePack()
    val tint = pack.resolveTint(tintId)
    val palette = pack.palette(useLightAppearance)
        .withTint(tint, useLightAppearance, design)
    val colors: ColorScheme = baseColorScheme(design, useLightAppearance, palette).copy(
        primary = palette.accent,
        secondary = palette.accentLight,
    )
    val typography = if (design == PhoebeDesignSystem.Default) {
        MaterialTheme.typography
    } else {
        phoebeTypography(design)
    }
    val shapes = if (design == PhoebeDesignSystem.Default) {
        MaterialTheme.shapes
    } else {
        pack.shapes.toMaterialShapes()
    }
    ApplySystemBarAppearance(
        statusBarColor = palette.shellTop,
        navigationBarColor = palette.navBar,
        useLightIcons = useLightAppearance,
    )
    CompositionLocalProvider(
        LocalPhoebePalette provides palette,
        LocalPhoebeDesignSystem provides design,
        LocalPhoebeShapeTokens provides pack.shapes,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            shapes = shapes,
        ) {
            val appTextStyle = if (design == PhoebeDesignSystem.Default) {
                LocalTextStyle.current
            } else {
                MaterialTheme.typography.bodyMedium
            }
            ProvideTextStyle(appTextStyle, content)
        }
    }
}

private fun baseColorScheme(
    design: PhoebeDesignSystem,
    useLightAppearance: Boolean,
    palette: PhoebeVisualPalette,
): ColorScheme =
    if (design == PhoebeDesignSystem.Default) {
        if (useLightAppearance) LightColors else DarkColors
    } else if (useLightAppearance) {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            secondary = palette.accentLight,
            tertiary = palette.accent,
            background = palette.canvasBackground,
            surface = palette.panel,
            surfaceVariant = palette.subtleFill,
            onBackground = palette.primaryText,
            onSurface = palette.primaryText,
            onSurfaceVariant = palette.secondaryText,
            outline = palette.border,
        )
    } else {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.canvasBackground,
            secondary = palette.accentLight,
            tertiary = palette.accent,
            background = palette.canvasBackground,
            surface = palette.panel,
            surfaceVariant = palette.subtleFill,
            onBackground = palette.primaryText,
            onSurface = palette.primaryText,
            onSurfaceVariant = palette.secondaryText,
            outline = palette.border,
        )
    }

@Composable
private fun phoebeTypography(design: PhoebeDesignSystem): Typography {
    val editorial = FontFamily(
        Font(Res.font.instrument_serif_regular, weight = FontWeight.Normal),
        Font(Res.font.instrument_serif_italic, weight = FontWeight.Normal, style = FontStyle.Italic),
    )
    val sans = FontFamily(Font(Res.font.geist_variable))
    val mono = FontFamily(Font(Res.font.geist_mono_variable))
    val brutalistDisplay = FontFamily(Font(Res.font.archivo_black_regular, weight = FontWeight.Black))
    val display = if (design == PhoebeDesignSystem.Brutalist) brutalistDisplay else editorial
    val labelFamily = if (design == PhoebeDesignSystem.Brutalist) mono else sans

    return remember(design) {
        Typography(
            displayLarge = TextStyle(
                fontFamily = display,
                fontSize = if (design == PhoebeDesignSystem.Brutalist) 56.sp else 54.sp,
                lineHeight = if (design == PhoebeDesignSystem.Brutalist) 58.sp else 56.sp,
                fontWeight = if (design == PhoebeDesignSystem.Brutalist) FontWeight.Black else FontWeight.Normal,
            ),
            displayMedium = TextStyle(
                fontFamily = display,
                fontSize = if (design == PhoebeDesignSystem.Brutalist) 44.sp else 42.sp,
                lineHeight = if (design == PhoebeDesignSystem.Brutalist) 46.sp else 44.sp,
                fontWeight = if (design == PhoebeDesignSystem.Brutalist) FontWeight.Black else FontWeight.Normal,
            ),
            headlineLarge = TextStyle(
                fontFamily = display,
                fontSize = if (design == PhoebeDesignSystem.Brutalist) 34.sp else 36.sp,
                lineHeight = if (design == PhoebeDesignSystem.Brutalist) 36.sp else 38.sp,
                fontWeight = if (design == PhoebeDesignSystem.Brutalist) FontWeight.ExtraBold else FontWeight.Normal,
            ),
            headlineMedium = TextStyle(
                fontFamily = display,
                fontSize = if (design == PhoebeDesignSystem.Brutalist) 28.sp else 30.sp,
                lineHeight = if (design == PhoebeDesignSystem.Brutalist) 31.sp else 32.sp,
                fontWeight = if (design == PhoebeDesignSystem.Brutalist) FontWeight.ExtraBold else FontWeight.Normal,
            ),
            titleLarge = TextStyle(
                fontFamily = sans,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            titleMedium = TextStyle(
                fontFamily = sans,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            titleSmall = TextStyle(
                fontFamily = sans,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            bodyLarge = TextStyle(
                fontFamily = sans,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
            bodyMedium = TextStyle(
                fontFamily = sans,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
            bodySmall = TextStyle(
                fontFamily = sans,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            ),
            labelLarge = TextStyle(
                fontFamily = labelFamily,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            labelMedium = TextStyle(
                fontFamily = labelFamily,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            labelSmall = TextStyle(
                fontFamily = labelFamily,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

private fun PhoebeShapeTokens.toMaterialShapes(): Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(controlRadius),
        small = RoundedCornerShape(controlRadius),
        medium = RoundedCornerShape(panelRadius),
        large = RoundedCornerShape(sheetTopRadius),
        extraLarge = RoundedCornerShape(sheetTopRadius),
    )

@Composable
fun Modifier.phoebeShellBackground(
    tintedGradient: Boolean,
    center: Offset = Offset(420f, 40f),
    radius: Float = 960f,
    radialTintStrength: Float = 1f,
): Modifier =
    if (tintedGradient && PhoebeUi.design != PhoebeDesignSystem.Brutalist) {
        val shellTint = PhoebeUi.shellRadialTint
        val softenedShellTint = shellTint.copy(alpha = shellTint.alpha * radialTintStrength.coerceIn(0f, 1f))
        background(Brush.verticalGradient(listOf(PhoebeUi.shellTop, PhoebeUi.shellBottom)))
            .background(
                Brush.radialGradient(
                    colors = listOf(softenedShellTint, Color.Transparent),
                    center = center,
                    radius = radius,
                ),
            )
    } else {
        background(Brush.verticalGradient(listOf(PhoebeUi.shellTop, PhoebeUi.shellBottom)))
    }
