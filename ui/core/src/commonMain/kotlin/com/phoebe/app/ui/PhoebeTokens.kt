package com.phoebe.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class PhoebeDesignSystem(
    val id: String,
    val label: String,
    val description: String,
) {
    Default("default", "Default", "Current Phoebe"),
    Porcelain("porcelain", "Porcelain", "Warm archive"),
    Nocturne("nocturne", "Nocturne", "Night listening"),
    Brutalist("brutalist", "Brutalist", "Tactical hi-fi"),
    Minimalist("minimalist", "Minimalist", "Quiet editorial"),
    ;

    companion object {
        val Options: List<PhoebeDesignSystem> = entries

        fun fromId(id: String?): PhoebeDesignSystem =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: Default
    }
}

@Immutable
data class PhoebeTintOption(
    val id: String,
    val label: String,
    val color: Color,
    val lightColor: Color = color,
) {
    companion object {
        val Purple = PhoebeTintOption("purple", "Purple", Color(0xFF9B4DFF), Color(0xFF8B3DFF))
        val PorcelainAubergine = PhoebeTintOption("porcelain-aubergine", "Aubergine", Color(0xFF7B5775), Color(0xFF4A2D45))
        val PorcelainFig = PhoebeTintOption("porcelain-fig", "Fig", Color(0xFF8C5B73), Color(0xFF603D54))
        val PorcelainInk = PhoebeTintOption("porcelain-ink", "Ink", Color(0xFF5B4B57), Color(0xFF302937))
        val NocturneBrass = PhoebeTintOption("nocturne-brass", "Brass", Color(0xFFD2AE74), Color(0xFF9A7140))
        val NocturneAmber = PhoebeTintOption("nocturne-amber", "Amber", Color(0xFFE0BC7A), Color(0xFFB68143))
        val NocturneCopper = PhoebeTintOption("nocturne-copper", "Copper", Color(0xFFC88E66), Color(0xFF9D5F45))
        val BrutalistRed = PhoebeTintOption("brutalist-red", "Signal Red", Color(0xFFFF2A2A), Color(0xFFD71920))
        val BrutalistCrimson = PhoebeTintOption("brutalist-crimson", "Crimson", Color(0xFFE11D2E), Color(0xFFB40F1C))
        val BrutalistVermilion = PhoebeTintOption("brutalist-vermilion", "Vermilion", Color(0xFFFF4A2E), Color(0xFFD43521))
        val MinimalistArchiveBlue = PhoebeTintOption("minimalist-archive-blue", "Archive Blue", Color(0xFFA8CEE2), Color(0xFF2F6F92))
        val MinimalistSlate = PhoebeTintOption("minimalist-slate", "Slate", Color(0xFF8FA9B8), Color(0xFF425C69))
        val MinimalistGraphite = PhoebeTintOption("minimalist-graphite", "Graphite", Color(0xFFC4C8C4), Color(0xFF454A47))
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
        val PorcelainOptions = listOf(PorcelainAubergine, PorcelainFig, PorcelainInk)
        val NocturneOptions = listOf(NocturneBrass, NocturneAmber, NocturneCopper)
        val BrutalistOptions = listOf(BrutalistRed, BrutalistCrimson, BrutalistVermilion)
        val MinimalistOptions = listOf(MinimalistArchiveBlue, MinimalistSlate, MinimalistGraphite)
        val AllOptions = (
            Options +
                PorcelainOptions +
                NocturneOptions +
                BrutalistOptions +
                MinimalistOptions
            ).distinctBy { it.id }

        fun fromId(id: String?): PhoebeTintOption =
            AllOptions.firstOrNull { it.id == id?.trim()?.lowercase() } ?: Purple

        fun optionsForDesign(design: PhoebeDesignSystem): List<PhoebeTintOption> =
            when (design) {
                PhoebeDesignSystem.Default -> Options
                PhoebeDesignSystem.Porcelain -> PorcelainOptions
                PhoebeDesignSystem.Nocturne -> NocturneOptions
                PhoebeDesignSystem.Brutalist -> BrutalistOptions
                PhoebeDesignSystem.Minimalist -> MinimalistOptions
            }

        fun defaultForDesign(design: PhoebeDesignSystem): PhoebeTintOption =
            when (design) {
                PhoebeDesignSystem.Default -> Purple
                else -> optionsForDesign(design).first()
            }

        fun fromId(id: String?, design: PhoebeDesignSystem): PhoebeTintOption {
            val options = optionsForDesign(design)
            return options.firstOrNull { it.id == id?.trim()?.lowercase() } ?: options.first()
        }
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

@Immutable
data class PhoebeShapeTokens(
    val panelRadius: Dp,
    val controlRadius: Dp,
    val mediaRadius: Dp,
    val sheetTopRadius: Dp,
    val buttonRadius: Dp,
) {
    companion object {
        val Default = PhoebeShapeTokens(
            panelRadius = 14.dp,
            controlRadius = 10.dp,
            mediaRadius = 12.dp,
            sheetTopRadius = 24.dp,
            buttonRadius = 999.dp,
        )
        val Porcelain = PhoebeShapeTokens(
            panelRadius = 8.dp,
            controlRadius = 14.dp,
            mediaRadius = 10.dp,
            sheetTopRadius = 26.dp,
            buttonRadius = 999.dp,
        )
        val Nocturne = PhoebeShapeTokens(
            panelRadius = 8.dp,
            controlRadius = 12.dp,
            mediaRadius = 10.dp,
            sheetTopRadius = 26.dp,
            buttonRadius = 999.dp,
        )
        val Brutalist = PhoebeShapeTokens(
            panelRadius = 0.dp,
            controlRadius = 0.dp,
            mediaRadius = 2.dp,
            sheetTopRadius = 8.dp,
            buttonRadius = 0.dp,
        )
        val Minimalist = PhoebeShapeTokens(
            panelRadius = 8.dp,
            controlRadius = 8.dp,
            mediaRadius = 10.dp,
            sheetTopRadius = 24.dp,
            buttonRadius = 8.dp,
        )
    }
}

@Immutable
data class PhoebeThemePack(
    val design: PhoebeDesignSystem,
    val lightPalette: PhoebeVisualPalette,
    val darkPalette: PhoebeVisualPalette,
    val accentOptions: List<PhoebeTintOption>,
    val shapes: PhoebeShapeTokens,
) {
    fun palette(useLightAppearance: Boolean): PhoebeVisualPalette =
        if (useLightAppearance) lightPalette else darkPalette
}

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
    shellRadialTint = Color(0x338B3DFF),
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

val PhoebePorcelainPaletteLight = PhoebeVisualPalette(
    canvasBackground = Color(0xFFF6F1E8),
    shellRadialTint = Color(0x2E4A2D45),
    shellTop = Color(0xFFFBF7EF),
    shellBottom = Color(0xFFF6F1E8),
    navBar = Color(0xFFFFFDF8),
    sidebar = Color(0xFFFBF7EF),
    panel = Color(0xFFFFFDF8),
    glass = Color(0xF5FFFDF8),
    border = Color(0xFFE4DCD0),
    primaryText = Color(0xFF171A1E),
    secondaryText = Color(0xFF34383D),
    mutedText = Color(0xFF77716A),
    accent = Color(0xFF4A2D45),
    accentLight = Color(0xFF4A2D45),
    librarySelectedRow = Color(0xFFE9DDEA),
    libraryHoverRow = Color(0x26E9DDEA),
    libraryDivider = Color(0xFFE4DCD0),
    subtleFill = Color(0x73FBF7EF),
    elevatedFill = Color(0xB8FFFDF8),
    progressTrack = Color(0x332F3437),
    modalSurface = Color(0xFFFFFDF8),
    modalField = Color(0xFFFBF7EF),
    overlayScrim = Color(0xB8F6F1E8),
    waveformUnplayed = Color(0x4D34383D),
    waveformPlayhead = Color(0x994A2D45),
)

val PhoebePorcelainPaletteDark = PhoebeVisualPalette(
    canvasBackground = Color(0xFF171412),
    shellRadialTint = Color(0x334A2D45),
    shellTop = Color(0xFF211D1A),
    shellBottom = Color(0xFF141210),
    navBar = Color(0xFF1B1715),
    sidebar = Color(0xFF181411),
    panel = Color(0xE6231F1B),
    glass = Color(0xD9211D1A),
    border = Color(0x2EF3E7D7),
    primaryText = Color(0xFFF7EFE3),
    secondaryText = Color(0xFFD6C8B9),
    mutedText = Color(0xFF9A8E82),
    accent = Color(0xFFD6B7D1),
    accentLight = Color(0xFFE6CFE3),
    librarySelectedRow = Color(0x334A2D45),
    libraryHoverRow = Color(0x17F3E7D7),
    libraryDivider = Color(0x1FF3E7D7),
    subtleFill = Color(0x14F3E7D7),
    elevatedFill = Color(0x1FF3E7D7),
    progressTrack = Color(0x2EF3E7D7),
    modalSurface = Color(0xFF241F1B),
    modalField = Color(0xFF191613),
    overlayScrim = Color(0xD9141210),
    waveformUnplayed = Color(0x2EF7EFE3),
    waveformPlayhead = Color(0x80F7EFE3),
)

val PhoebeNocturnePaletteDark = PhoebeVisualPalette(
    canvasBackground = Color(0xFF0C0F12),
    shellRadialTint = Color(0x3DD2AE74),
    shellTop = Color(0xFF12161B),
    shellBottom = Color(0xFF0C0F12),
    navBar = Color(0xFF181C22),
    sidebar = Color(0xFF0E1115),
    panel = Color(0xE612161B),
    glass = Color(0xD9181C22),
    border = Color(0x1AF3EFE6),
    primaryText = Color(0xFFF3EFE6),
    secondaryText = Color(0xFFC9C0B3),
    mutedText = Color(0xFF817A72),
    accent = Color(0xFFD2AE74),
    accentLight = Color(0xFFE0BE82),
    librarySelectedRow = Color(0x29D2AE74),
    libraryHoverRow = Color(0x0EF3EFE6),
    libraryDivider = Color(0x14F3EFE6),
    subtleFill = Color(0x0EF3EFE6),
    elevatedFill = Color(0x17F3EFE6),
    progressTrack = Color(0x24F3EFE6),
    modalSurface = Color(0xFF181C22),
    modalField = Color(0xFF11151A),
    overlayScrim = Color(0xD90C0F12),
    waveformUnplayed = Color(0x33C9C0B3),
    waveformPlayhead = Color(0x99D2AE74),
)

val PhoebeNocturnePaletteLight = PhoebeVisualPalette(
    canvasBackground = Color(0xFFF0ECE4),
    shellRadialTint = Color(0x33B68143),
    shellTop = Color(0xFFFBF8F0),
    shellBottom = Color(0xFFF0ECE4),
    navBar = Color(0xFFFBF8F0),
    sidebar = Color(0xFFF4EFE7),
    panel = Color(0xFFFEFCF7),
    glass = Color(0xF2FEFCF7),
    border = Color(0xFFDCD3C6),
    primaryText = Color(0xFF191613),
    secondaryText = Color(0xFF544C43),
    mutedText = Color(0xFF817A72),
    accent = Color(0xFF9A7140),
    accentLight = Color(0xFF8C6438),
    librarySelectedRow = Color(0x2ED2AE74),
    libraryHoverRow = Color(0x1AD2AE74),
    libraryDivider = Color(0xFFDCD3C6),
    subtleFill = Color(0x80F4EFE7),
    elevatedFill = Color(0xB8FEFCF7),
    progressTrack = Color(0x33544C43),
    modalSurface = Color(0xFFFEFCF7),
    modalField = Color(0xFFF4EFE7),
    overlayScrim = Color(0xB8F0ECE4),
    waveformUnplayed = Color(0x4D544C43),
    waveformPlayhead = Color(0x999A7140),
)

val PhoebeBrutalistPaletteDark = PhoebeVisualPalette(
    canvasBackground = Color(0xFF0A0A0A),
    shellRadialTint = Color(0x26FF2A2A),
    shellTop = Color(0xFF101010),
    shellBottom = Color(0xFF0A0A0A),
    navBar = Color(0xFF151515),
    sidebar = Color(0xFF0A0A0A),
    panel = Color(0xF2101010),
    glass = Color(0xF2151515),
    border = Color(0x24EAEAEA),
    primaryText = Color(0xFFEAEAEA),
    secondaryText = Color(0xFFA8A8A8),
    mutedText = Color(0xFF6F6F6F),
    accent = Color(0xFFFF2A2A),
    accentLight = Color(0xFFFF4A4A),
    librarySelectedRow = Color(0x24FF2A2A),
    libraryHoverRow = Color(0x14EAEAEA),
    libraryDivider = Color(0x13EAEAEA),
    subtleFill = Color(0x0FEAEAEA),
    elevatedFill = Color(0x14EAEAEA),
    progressTrack = Color(0x26EAEAEA),
    modalSurface = Color(0xFF151515),
    modalField = Color(0xFF101010),
    overlayScrim = Color(0xE60A0A0A),
    waveformUnplayed = Color(0x26EAEAEA),
    waveformPlayhead = Color(0x99FF2A2A),
)

val PhoebeBrutalistPaletteLight = PhoebeVisualPalette(
    canvasBackground = Color(0xFFF3F3F0),
    shellRadialTint = Color(0x26D71920),
    shellTop = Color(0xFFFFFFFF),
    shellBottom = Color(0xFFE9E9E4),
    navBar = Color(0xFFFFFFFF),
    sidebar = Color(0xFFEDEDE8),
    panel = Color(0xFFFFFFFF),
    glass = Color(0xF2FFFFFF),
    border = Color(0x3D0A0A0A),
    primaryText = Color(0xFF101010),
    secondaryText = Color(0xFF3F3F3D),
    mutedText = Color(0xFF6F6F6F),
    accent = Color(0xFFD71920),
    accentLight = Color(0xFFD71920),
    librarySelectedRow = Color(0x1FD71920),
    libraryHoverRow = Color(0x120A0A0A),
    libraryDivider = Color(0x240A0A0A),
    subtleFill = Color(0x0F0A0A0A),
    elevatedFill = Color(0x140A0A0A),
    progressTrack = Color(0x260A0A0A),
    modalSurface = Color(0xFFFFFFFF),
    modalField = Color(0xFFEDEDE8),
    overlayScrim = Color(0xB8F3F3F0),
    waveformUnplayed = Color(0x4D101010),
    waveformPlayhead = Color(0x99D71920),
)

val PhoebeMinimalistPaletteLight = PhoebeVisualPalette(
    canvasBackground = Color(0xFFF7F6F3),
    shellRadialTint = Color(0x102F6F92),
    shellTop = Color(0xFFFBFAF7),
    shellBottom = Color(0xFFF7F6F3),
    navBar = Color(0xFFFFFFFF),
    sidebar = Color(0xFFFBFAF7),
    panel = Color(0xFFFFFFFF),
    glass = Color(0xFFFFFFFF),
    border = Color(0xFFEAE6DE),
    primaryText = Color(0xFF111111),
    secondaryText = Color(0xFF2F3437),
    mutedText = Color(0xFF787774),
    accent = Color(0xFF2F6F92),
    accentLight = Color(0xFF2F6F92),
    librarySelectedRow = Color(0xFFE1F3FE),
    libraryHoverRow = Color(0x142F6F92),
    libraryDivider = Color(0xFFEAE6DE),
    subtleFill = Color(0xFFFBFAF7),
    elevatedFill = Color(0xFFFFFFFF),
    progressTrack = Color(0x332F3437),
    modalSurface = Color(0xFFFFFFFF),
    modalField = Color(0xFFFBFAF7),
    overlayScrim = Color(0xB8F7F6F3),
    waveformUnplayed = Color(0x4D2F3437),
    waveformPlayhead = Color(0x992F6F92),
)

val PhoebeMinimalistPaletteDark = PhoebeVisualPalette(
    canvasBackground = Color(0xFF111111),
    shellRadialTint = Color(0x12A8CEE2),
    shellTop = Color(0xFF181818),
    shellBottom = Color(0xFF111111),
    navBar = Color(0xFF181818),
    sidebar = Color(0xFF111111),
    panel = Color(0xFF181818),
    glass = Color(0xFF181818),
    border = Color(0x1AFFFFFF),
    primaryText = Color(0xFFF7F6F3),
    secondaryText = Color(0xFFB8B2A8),
    mutedText = Color(0xFF7E786F),
    accent = Color(0xFFA8CEE2),
    accentLight = Color(0xFFC5E0EE),
    librarySelectedRow = Color(0x1FA8CEE2),
    libraryHoverRow = Color(0x0DFFFFFF),
    libraryDivider = Color(0x14FFFFFF),
    subtleFill = Color(0x0DFFFFFF),
    elevatedFill = Color(0x14FFFFFF),
    progressTrack = Color(0x24FFFFFF),
    modalSurface = Color(0xFF181818),
    modalField = Color(0xFF111111),
    overlayScrim = Color(0xD9111111),
    waveformUnplayed = Color(0x33B8B2A8),
    waveformPlayhead = Color(0x99A8CEE2),
)

val PhoebeThemePacks = listOf(
    PhoebeThemePack(
        design = PhoebeDesignSystem.Default,
        lightPalette = PhoebePaletteLight,
        darkPalette = PhoebePaletteDark,
        accentOptions = PhoebeTintOption.Options,
        shapes = PhoebeShapeTokens.Default,
    ),
    PhoebeThemePack(
        design = PhoebeDesignSystem.Porcelain,
        lightPalette = PhoebePorcelainPaletteLight,
        darkPalette = PhoebePorcelainPaletteDark,
        accentOptions = PhoebeTintOption.PorcelainOptions,
        shapes = PhoebeShapeTokens.Porcelain,
    ),
    PhoebeThemePack(
        design = PhoebeDesignSystem.Nocturne,
        lightPalette = PhoebeNocturnePaletteLight,
        darkPalette = PhoebeNocturnePaletteDark,
        accentOptions = PhoebeTintOption.NocturneOptions,
        shapes = PhoebeShapeTokens.Nocturne,
    ),
    PhoebeThemePack(
        design = PhoebeDesignSystem.Brutalist,
        lightPalette = PhoebeBrutalistPaletteLight,
        darkPalette = PhoebeBrutalistPaletteDark,
        accentOptions = PhoebeTintOption.BrutalistOptions,
        shapes = PhoebeShapeTokens.Brutalist,
    ),
    PhoebeThemePack(
        design = PhoebeDesignSystem.Minimalist,
        lightPalette = PhoebeMinimalistPaletteLight,
        darkPalette = PhoebeMinimalistPaletteDark,
        accentOptions = PhoebeTintOption.MinimalistOptions,
        shapes = PhoebeShapeTokens.Minimalist,
    ),
)

fun PhoebeDesignSystem.themePack(): PhoebeThemePack =
    PhoebeThemePacks.firstOrNull { it.design == this } ?: PhoebeThemePacks.first()

fun PhoebeThemePack.resolveTint(tintId: String?): PhoebeTintOption =
    accentOptions.firstOrNull { it.id == tintId?.trim()?.lowercase() } ?: accentOptions.first()

internal fun PhoebeVisualPalette.withTint(
    tint: PhoebeTintOption,
    useLightAppearance: Boolean,
    design: PhoebeDesignSystem,
): PhoebeVisualPalette {
    if (tint.id == PhoebeTintOption.Purple.id) return this
    val accent = if (useLightAppearance) tint.lightColor else tint.color
    val selectedAlpha = when {
        design == PhoebeDesignSystem.Brutalist -> if (useLightAppearance) 0.12f else 0.14f
        design == PhoebeDesignSystem.Minimalist -> if (useLightAppearance) 0.12f else 0.14f
        useLightAppearance -> 0.10f
        else -> 0.18f
    }
    val shellAlpha = when {
        design == PhoebeDesignSystem.Minimalist && useLightAppearance -> 0.06f
        design == PhoebeDesignSystem.Minimalist -> 0.08f
        useLightAppearance -> 0.20f
        else -> 0.32f
    }
    return copy(
        shellRadialTint = accent.copy(alpha = shellAlpha),
        accent = accent,
        accentLight = accent,
        librarySelectedRow = accent.copy(alpha = selectedAlpha),
        libraryHoverRow = if (design == PhoebeDesignSystem.Minimalist) accent.copy(alpha = 0.08f) else libraryHoverRow,
    )
}

val LocalPhoebePalette = staticCompositionLocalOf { PhoebePaletteDark }
val LocalPhoebeDesignSystem = staticCompositionLocalOf { PhoebeDesignSystem.Default }
val LocalPhoebeShapeTokens = staticCompositionLocalOf { PhoebeShapeTokens.Default }

/** Composable accessors so library/metadata screens track [LocalPhoebePalette]. */
object PhoebeUi {
    val design: PhoebeDesignSystem @Composable get() = LocalPhoebeDesignSystem.current
    val shapes: PhoebeShapeTokens @Composable get() = LocalPhoebeShapeTokens.current
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
