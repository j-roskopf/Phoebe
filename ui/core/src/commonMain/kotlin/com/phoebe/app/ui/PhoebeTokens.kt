package com.phoebe.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class PhoebeTintOption(
    val id: String,
    val label: String,
    val color: Color,
    val lightColor: Color = color,
) {
    companion object {
        val Purple = PhoebeTintOption("purple", "Purple", Color(0xFF9B4DFF), Color(0xFF8B3DFF))
        val Options = listOf(
            PhoebeTintOption("red", "Red", Color(0xFFEF4444), Color(0xFFDC2626)),
            PhoebeTintOption("scarlet", "Scarlet", Color(0xFFF43F5E), Color(0xFFE11D48)),
            PhoebeTintOption("coral", "Coral", Color(0xFFFF6B5F), Color(0xFFE64B3C)),
            PhoebeTintOption("orange", "Orange", Color(0xFFF97316), Color(0xFFEA580C)),
            PhoebeTintOption("amber", "Amber", Color(0xFFF59E0B), Color(0xFFD97706)),
            PhoebeTintOption("gold", "Gold", Color(0xFFFACC15), Color(0xFFEAB308)),
            PhoebeTintOption("yellow", "Yellow", Color(0xFFEAB308), Color(0xFFCA8A04)),
            PhoebeTintOption("lime", "Lime", Color(0xFF84CC16), Color(0xFF65A30D)),
            PhoebeTintOption("chartreuse", "Chartreuse", Color(0xFFA3E635), Color(0xFF84CC16)),
            PhoebeTintOption("green", "Green", Color(0xFF22C55E), Color(0xFF16A34A)),
            PhoebeTintOption("emerald", "Emerald", Color(0xFF10B981), Color(0xFF059669)),
            PhoebeTintOption("mint", "Mint", Color(0xFF34D399), Color(0xFF10B981)),
            PhoebeTintOption("teal", "Teal", Color(0xFF14B8A6), Color(0xFF0D9488)),
            PhoebeTintOption("aqua", "Aqua", Color(0xFF22D3EE), Color(0xFF06B6D4)),
            PhoebeTintOption("cyan", "Cyan", Color(0xFF06B6D4), Color(0xFF0891B2)),
            PhoebeTintOption("sky", "Sky", Color(0xFF0EA5E9), Color(0xFF0284C7)),
            PhoebeTintOption("blue", "Blue", Color(0xFF3B82F6), Color(0xFF2563EB)),
            PhoebeTintOption("indigo", "Indigo", Color(0xFF6366F1), Color(0xFF4F46E5)),
            PhoebeTintOption("violet", "Violet", Color(0xFF8B5CF6), Color(0xFF7C3AED)),
            Purple,
            PhoebeTintOption("fuchsia", "Fuchsia", Color(0xFFD946EF), Color(0xFFC026D3)),
            PhoebeTintOption("magenta", "Magenta", Color(0xFFC026D3), Color(0xFFA21CAF)),
            PhoebeTintOption("pink", "Pink", Color(0xFFEC4899), Color(0xFFDB2777)),
            PhoebeTintOption("plum", "Plum", Color(0xFFA855F7), Color(0xFF9333EA)),
        )

        fun fromId(id: String?): PhoebeTintOption =
            Options.firstOrNull { it.id == id } ?: Purple
    }
}

@Immutable
data class PhoebeVisualPalette(
    val canvasBackground: Color,
    val shellRadialTint: Color,
    val shellTop: Color,
    val shellBottom: Color,
    /** Opaque bottom chrome (tab bar + system navigation inset). */
    val navBar: Color,
    val sidebar: Color,
    val panel: Color,
    val glass: Color,
    val border: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val mutedText: Color,
    val accent: Color,
    val accentLight: Color,
    val librarySelectedRow: Color,
    val libraryHoverRow: Color,
    val libraryDivider: Color,
    /** Row chips / profile strip / soft fills */
    val subtleFill: Color,
    /** Slightly stronger row hover (e.g. 0.06 white in dark) */
    val elevatedFill: Color,
    /** Progress / slider track idle */
    val progressTrack: Color,
    /** Solid surface for dialogs (Create playlist, etc.) */
    val modalSurface: Color,
    /** Text field / inset control surface inside modals */
    val modalField: Color,
    /** Full-screen blocking overlay (busy / loading) */
    val overlayScrim: Color,
    /** Waveform bars in the “not yet played” region */
    val waveformUnplayed: Color,
    /** Vertical playhead line on waveform */
    val waveformPlayhead: Color,
)

val PhoebePaletteDark = PhoebeVisualPalette(
    canvasBackground = Color(0xFF080B12),
    shellRadialTint = Color(0x332B174E),
    shellTop = Color(0xFF151A27),
    shellBottom = Color(0xFF0B0F17),
    navBar = Color(0xFF0B0F17),
    sidebar = Color(0xFF0A0D14),
    panel = Color(0xCC121722),
    glass = Color(0xB8121722),
    border = Color.White.copy(alpha = 0.06f),
    primaryText = Color(0xFFF4F5F7),
    secondaryText = Color(0xFFB6BBC7),
    mutedText = Color(0xFF7D8493),
    accent = Color(0xFF9B4DFF),
    accentLight = Color(0xFFA855F7),
    librarySelectedRow = Color(0xFF9B4DFF).copy(alpha = 0.18f),
    libraryHoverRow = Color.White.copy(alpha = 0.04f),
    libraryDivider = Color.White.copy(alpha = 0.045f),
    subtleFill = Color.White.copy(alpha = 0.04f),
    elevatedFill = Color.White.copy(alpha = 0.06f),
    progressTrack = Color.White.copy(alpha = 0.14f),
    modalSurface = Color(0xFF161B27),
    modalField = Color(0xFF0F131C),
    overlayScrim = Color(0xE6080F17),
    waveformUnplayed = Color.White.copy(alpha = 0.14f),
    waveformPlayhead = Color.White.copy(alpha = 0.35f),
)

/** Tokens from docs/light_mode_design.md */
val PhoebePaletteLight = PhoebeVisualPalette(
    canvasBackground = Color(0xFFF3F4F7),
    shellRadialTint = Color(0x0F8B3DFF),
    shellTop = Color(0xFFFFFFFF),
    shellBottom = Color(0xFFFAFAFC),
    navBar = Color(0xFFFFFFFF),
    sidebar = Color(0xFFF7F8FA),
    panel = Color(0xFFFFFFFF),
    glass = Color(0xEBFFFFFF),
    border = Color(0x14181B22),
    primaryText = Color(0xFF181B22),
    secondaryText = Color(0xFF4D5563),
    mutedText = Color(0xFF7A8190),
    accent = Color(0xFF8B3DFF),
    accentLight = Color(0xFF8B3DFF),
    librarySelectedRow = Color(0xFF8B3DFF).copy(alpha = 0.10f),
    libraryHoverRow = Color(0x0A101820),
    libraryDivider = Color(0x0F101820),
    subtleFill = Color(0x0A101820),
    elevatedFill = Color(0x0F101820),
    progressTrack = Color(0x1E101820),
    modalSurface = Color(0xFFFFFFFF),
    modalField = Color(0xFFF1F2F5),
    overlayScrim = Color(0xA3F3F4F7),
    waveformUnplayed = Color(0x59181B22),
    waveformPlayhead = Color(0x99181B22),
)

internal fun PhoebeVisualPalette.withTint(tint: PhoebeTintOption, useLightAppearance: Boolean): PhoebeVisualPalette {
    if (tint.id == PhoebeTintOption.Purple.id) return this
    val accent = if (useLightAppearance) tint.lightColor else tint.color
    return copy(
        shellRadialTint = accent.copy(alpha = if (useLightAppearance) 0.12f else 0.32f),
        accent = accent,
        accentLight = accent,
        librarySelectedRow = accent.copy(alpha = if (useLightAppearance) 0.10f else 0.18f),
    )
}

val LocalPhoebePalette = staticCompositionLocalOf { PhoebePaletteDark }

/** Composable accessors so library/metadata screens track [LocalPhoebePalette]. */
object PhoebeUi {
    val canvasBackground: Color @Composable get() = LocalPhoebePalette.current.canvasBackground
    val shellRadialTint: Color @Composable get() = LocalPhoebePalette.current.shellRadialTint
    val shellTop: Color @Composable get() = LocalPhoebePalette.current.shellTop
    val shellBottom: Color @Composable get() = LocalPhoebePalette.current.shellBottom
    val navBar: Color @Composable get() = LocalPhoebePalette.current.navBar
    val sidebar: Color @Composable get() = LocalPhoebePalette.current.sidebar
    val panel: Color @Composable get() = LocalPhoebePalette.current.panel
    val glass: Color @Composable get() = LocalPhoebePalette.current.glass
    val border: Color @Composable get() = LocalPhoebePalette.current.border
    val primaryText: Color @Composable get() = LocalPhoebePalette.current.primaryText
    val secondaryText: Color @Composable get() = LocalPhoebePalette.current.secondaryText
    val mutedText: Color @Composable get() = LocalPhoebePalette.current.mutedText
    val accent: Color @Composable get() = LocalPhoebePalette.current.accent
    val accentLight: Color @Composable get() = LocalPhoebePalette.current.accentLight
    val librarySelectedRow: Color @Composable get() = LocalPhoebePalette.current.librarySelectedRow
    val libraryHoverRow: Color @Composable get() = LocalPhoebePalette.current.libraryHoverRow
    val libraryDivider: Color @Composable get() = LocalPhoebePalette.current.libraryDivider
    val subtleFill: Color @Composable get() = LocalPhoebePalette.current.subtleFill
    val elevatedFill: Color @Composable get() = LocalPhoebePalette.current.elevatedFill
    val progressTrack: Color @Composable get() = LocalPhoebePalette.current.progressTrack
    val modalSurface: Color @Composable get() = LocalPhoebePalette.current.modalSurface
    val modalField: Color @Composable get() = LocalPhoebePalette.current.modalField
    val overlayScrim: Color @Composable get() = LocalPhoebePalette.current.overlayScrim
    val waveformUnplayed: Color @Composable get() = LocalPhoebePalette.current.waveformUnplayed
    val waveformPlayhead: Color @Composable get() = LocalPhoebePalette.current.waveformPlayhead
}
