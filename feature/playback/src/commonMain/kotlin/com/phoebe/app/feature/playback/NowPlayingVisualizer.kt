package com.phoebe.app.feature.playback

import com.phoebe.app.ui.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.phoebe.app.domain.AudioAnalysisFrame
import com.phoebe.app.domain.AudioAnalysisSource
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.player.AudioAnalysisAccumulator
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val VisualizerFrameStaleMs = 900L
private const val FullTurn = (PI * 2.0).toFloat()

@Composable
fun NowPlayingVisualizerSurface(
    preset: NowPlayingVisualizerPreset,
    track: Track?,
    audioAnalysis: AudioAnalysisFrame,
    isPlaying: Boolean,
    positionMs: Long,
    modifier: Modifier = Modifier,
    desktopArtworkConstrained: Boolean = false,
    showFullscreenButton: Boolean = true,
    fullscreenButtonAlpha: Float = 1f,
    useFilamentVisualizers: Boolean = true,
) {
    var fullscreen by remember(preset) { mutableStateOf(false) }
    val clampedFullscreenButtonAlpha = fullscreenButtonAlpha.coerceIn(0f, 1f)

    Box(modifier.clipToBounds()) {
        if (fullscreen) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        } else {
            NowPlayingVisualizerContent(
                preset = preset,
                track = track,
                audioAnalysis = audioAnalysis,
                isPlaying = isPlaying,
                positionMs = positionMs,
                modifier = Modifier.fillMaxSize(),
                desktopArtworkConstrained = desktopArtworkConstrained,
                useFilamentVisualizers = useFilamentVisualizers,
            )
        }

        if (!fullscreen && showFullscreenButton && preset.isVisualizer && clampedFullscreenButtonAlpha > 0f) {
            VisualizerIconButton(
                description = "Open visualizer full screen",
                onClick = { fullscreen = true },
                active = true,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .graphicsLayer { alpha = clampedFullscreenButtonAlpha }
                    .zIndex(1f),
                icon = PhoebeIcon.Fullscreen,
            )
        }
    }

    if (fullscreen) {
        FullscreenVisualizerDialog(
            preset = preset,
            track = track,
            audioAnalysis = audioAnalysis,
            isPlaying = isPlaying,
            positionMs = positionMs,
            onDismiss = { fullscreen = false },
            useFilamentVisualizers = useFilamentVisualizers,
        )
    }
}

@Composable
private fun FullscreenVisualizerDialog(
    preset: NowPlayingVisualizerPreset,
    track: Track?,
    audioAnalysis: AudioAnalysisFrame,
    isPlaying: Boolean,
    positionMs: Long,
    onDismiss: () -> Unit,
    useFilamentVisualizers: Boolean,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            NowPlayingVisualizerContent(
                preset = preset,
                track = track,
                audioAnalysis = audioAnalysis,
                isPlaying = isPlaying,
                positionMs = positionMs,
                modifier = Modifier.fillMaxSize(),
                desktopArtworkConstrained = false,
                useFilamentVisualizers = useFilamentVisualizers,
            )
            VisualizerIconButton(
                description = "Close full screen visualizer",
                onClick = onDismiss,
                active = true,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(18.dp)
                    .zIndex(1f),
                icon = PhoebeIcon.Close,
            )
        }
    }
}

@Composable
private fun NowPlayingVisualizerContent(
    preset: NowPlayingVisualizerPreset,
    track: Track?,
    audioAnalysis: AudioAnalysisFrame,
    isPlaying: Boolean,
    positionMs: Long,
    modifier: Modifier = Modifier,
    desktopArtworkConstrained: Boolean = false,
    useFilamentVisualizers: Boolean = true,
) {
    if (preset == NowPlayingVisualizerPreset.Artwork) {
        if (desktopArtworkConstrained) {
            // On desktop, don't fill the whole panel — center at natural size
            Box(modifier, contentAlignment = Alignment.Center) {
                if (track != null) {
                    TrackArtworkImage(track, Modifier.wrapContentSize(), elevated = true)
                } else {
                    EmptyNowPlayingArtworkSlot(Modifier.wrapContentSize(), glyphSp = 52.sp)
                }
            }
        } else {
            if (track != null) {
                TrackArtworkImage(track, modifier.fillMaxSize(), elevated = true)
            } else {
                EmptyNowPlayingArtworkSlot(modifier.fillMaxSize(), glyphSp = 52.sp)
            }
        }
        return
    }

    val motionEnabled = LocalContinuousMotionEnabled.current && isPlaying
    val phase = if (motionEnabled) {
        val transition = rememberInfiniteTransition(label = "visualizer-motion")
        val cycle by transition.animateFloat(
            initialValue = 0f,
            targetValue = FullTurn,
            animationSpec = infiniteRepeatable(tween(7_200, easing = LinearEasing)),
            label = "visualizer-phase",
        )
        cycle
    } else {
        (positionMs.coerceAtLeast(0L).toFloat() / 900f) % FullTurn
    }
    val frame = remember(audioAnalysis, track?.id, isPlaying, positionMs) {
        val freshRealFrame = audioAnalysis.source != AudioAnalysisSource.None &&
            audioAnalysis.bands.isNotEmpty() &&
            currentTimeMs() - audioAnalysis.timestampMs <= VisualizerFrameStaleMs
        if (freshRealFrame) {
            audioAnalysis.normalized()
        } else {
            AudioAnalysisAccumulator.fallbackFrame(
                seed = track?.id ?: track?.title.orEmpty(),
                positionMs = positionMs,
                isPlaying = isPlaying,
                timestampMs = currentTimeMs(),
            )
        }
    }
    if (preset.isFilament3DVisualizer()) {
        var wireframeYaw by remember { mutableFloatStateOf(0f) }
        var wireframePitch by remember { mutableFloatStateOf(0f) }
        val renderState = remember(frame, positionMs, isPlaying) {
            AudioVisualizerRenderState.from(frame, positionMs, isPlaying)
        }
        val fallbackInteraction = Modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                wireframeYaw = wrapFullTurn(wireframeYaw + dragAmount.x * 0.012f)
                wireframePitch = wrapFullTurn(wireframePitch + dragAmount.y * 0.010f)
            }
        }
        val visualizerModifier = modifier
            .clipToBounds()
            .background(Brush.radialGradient(preset.backgroundColors(), radius = 900f))
            .semantics { contentDescription = "${preset.label} visualizer" }
        val fallbackContent: @Composable (Modifier) -> Unit = { fallbackModifier ->
            Canvas(fallbackModifier.then(fallbackInteraction)) {
                drawRect(Color.Black.copy(alpha = 0.18f))
                drawWireframeSpectrum3D(
                    state = renderState,
                    yaw = wireframeYaw,
                    pitch = wireframePitch,
                )
            }
        }
        if (useFilamentVisualizers) {
            FilamentVisualizerHost(
                preset = preset,
                renderState = renderState,
                isPlaying = isPlaying,
                motionEnabled = motionEnabled,
                modifier = visualizerModifier,
                fallback = fallbackContent,
            )
        } else {
            fallbackContent(visualizerModifier)
        }
        return
    }

    Canvas(
        modifier
            .clipToBounds()
            .background(Brush.radialGradient(preset.backgroundColors(), radius = 900f))
            .semantics { contentDescription = "${preset.label} visualizer" },
    ) {
        drawRect(Color.Black.copy(alpha = 0.18f))
        when (preset) {
            NowPlayingVisualizerPreset.Alchemy -> drawAlchemy(frame, phase)
            NowPlayingVisualizerPreset.Battery -> drawBattery(frame, phase)
            NowPlayingVisualizerPreset.BarsAndWaves -> drawBarsAndWaves(frame, phase)
            NowPlayingVisualizerPreset.BlazingColors -> drawBlazingColors(frame, phase)
            NowPlayingVisualizerPreset.Plenoptic -> drawPlenoptic(frame, phase)
            NowPlayingVisualizerPreset.VortexSpectrum -> drawVortexSpectrum(frame, phase)
            NowPlayingVisualizerPreset.ClassicEQ -> drawClassicEQ(frame, phase)
            NowPlayingVisualizerPreset.HaloSpectrum -> drawHaloSpectrum(frame, phase)
            NowPlayingVisualizerPreset.WireframeSpectrum3D,
            NowPlayingVisualizerPreset.CanyonWire3D,
            NowPlayingVisualizerPreset.PulseTunnel3D,
            NowPlayingVisualizerPreset.OrbitalHalo3D,
            NowPlayingVisualizerPreset.SpiralGalaxy3D,
            NowPlayingVisualizerPreset.AuroraRibbon3D,
            NowPlayingVisualizerPreset.CrystalPeaks3D,
            NowPlayingVisualizerPreset.PrismFan3D,
            NowPlayingVisualizerPreset.WaveRibbon3D,
            NowPlayingVisualizerPreset.KaleidoscopeWeb3D,
            NowPlayingVisualizerPreset.StarfieldWeb3D,
            NowPlayingVisualizerPreset.Artwork,
            -> Unit
        }
    }
}

@Composable
fun VisualizerPresetButton(
    selected: NowPlayingVisualizerPreset,
    onSelected: (NowPlayingVisualizerPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        VisualizerIconButton(
            description = "Visualizer",
            onClick = { expanded = true },
            active = selected.isVisualizer,
        )
        VisualizerPresetDropdown(
            expanded = expanded,
            selected = selected,
            onSelected = {
                onSelected(it)
                expanded = false
            },
            onDismiss = { expanded = false },
        )
    }
}

@Composable
internal fun VisualizerPresetDropdown(
    expanded: Boolean,
    selected: NowPlayingVisualizerPreset,
    onSelected: (NowPlayingVisualizerPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        NowPlayingVisualizerPreset.entries.forEach { preset ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            preset.label,
                            color = if (preset == selected) PhoebeUi.accentLight else PhoebeUi.primaryText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (preset.familyLabel != preset.label) {
                            Text(preset.familyLabel, color = PhoebeUi.mutedText, fontSize = 11.sp)
                        }
                    }
                },
                leadingIcon = {
                    PhoebeIconView(
                        if (preset == NowPlayingVisualizerPreset.Artwork) PhoebeIcon.Music else PhoebeIcon.Visualizer,
                        tint = if (preset == selected) PhoebeUi.accentLight else PhoebeUi.secondaryText,
                        modifier = Modifier.size(17.dp),
                    )
                },
                onClick = { onSelected(preset) },
            )
        }
    }
}

@Composable
private fun VisualizerIconButton(
    description: String,
    onClick: () -> Unit,
    active: Boolean,
    modifier: Modifier = Modifier,
    icon: PhoebeIcon = PhoebeIcon.Visualizer,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) PhoebeUi.accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        PhoebeIconView(
            icon,
            tint = if (active) PhoebeUi.accentLight else PhoebeUi.primaryText,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun VisualizerPresetSelector(
    selected: NowPlayingVisualizerPreset,
    onSelected: (NowPlayingVisualizerPreset) -> Unit,
    compact: Boolean = false,
) {
    val rowSize = if (compact) 2 else 4
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NowPlayingVisualizerPreset.entries.chunked(rowSize).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { preset ->
                    val active = preset == selected
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(if (compact) 42.dp else 46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) PhoebeUi.accent.copy(alpha = 0.16f) else PhoebeUi.subtleFill)
                            .border(
                                androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (active) PhoebeUi.accent.copy(alpha = 0.36f) else PhoebeUi.border,
                                ),
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { onSelected(preset) }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PhoebeIconView(
                            if (preset == NowPlayingVisualizerPreset.Artwork) PhoebeIcon.Music else PhoebeIcon.Visualizer,
                            tint = if (active) PhoebeUi.accentLight else PhoebeUi.secondaryText,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            preset.label,
                            color = if (active) PhoebeUi.accentLight else PhoebeUi.secondaryText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(rowSize - row.size) {
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun DesktopNowPlayingVisualizerView(
    track: Track?,
    preset: NowPlayingVisualizerPreset,
    audioAnalysis: AudioAnalysisFrame,
    isPlaying: Boolean,
    positionMs: Long,
    onPreset: (NowPlayingVisualizerPreset) -> Unit,
    modifier: Modifier = Modifier,
    useFilamentVisualizers: Boolean = true,
) {
    Column(
        modifier = modifier.padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Now Playing", color = PhoebeUi.primaryText, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text(
                    track?.let { "${it.title} • ${it.artist}" } ?: "Choose a song to begin",
                    color = PhoebeUi.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            VisualizerPresetButton(selected = preset, onSelected = onPreset)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .border(androidx.compose.foundation.BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp)),
        ) {
            NowPlayingVisualizerSurface(
                preset = preset,
                track = track,
                audioAnalysis = audioAnalysis,
                isPlaying = isPlaying,
                positionMs = positionMs,
                modifier = Modifier.fillMaxSize(),
                desktopArtworkConstrained = true,
                useFilamentVisualizers = useFilamentVisualizers,
            )
        }
    }
}

// ─── Alchemy ─────────────────────────────────────────────────────────────────
// Organic flowing orbs + sinusoidal energy lines — each distinct colour family
private fun DrawScope.drawAlchemy(frame: AudioAnalysisFrame, phase: Float) {
    val colors = NowPlayingVisualizerPreset.Alchemy.visualColors()
    val amp = frame.amplitude.coerceIn(0f, 1f)

    // Large swirling orbs driven by individual FFT bands
    repeat(12) { index ->
        val band = frame.band(index * 2)
        val angle = phase * (0.18f + index * 0.022f) + index * 0.524f
        val orbitX = 0.42f - 0.30f * index.toFloat() / 12f
        val radius = size.minDimension * (0.14f + band * 0.32f + amp * 0.06f)
        val center = Offset(
            size.width * (0.5f + cosF(angle) * (0.18f + orbitX * 0.06f)),
            size.height * (0.5f + sinF(angle * 1.3f) * (0.14f + amp * 0.10f)),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors[index % colors.size].copy(alpha = 0.55f + band * 0.30f),
                    colors[(index + 2) % colors.size].copy(alpha = 0.0f),
                ),
                center = center,
                radius = radius.coerceAtLeast(1f),
            ),
            radius = radius,
            center = center,
        )
    }

    // Flowing energy lines across the canvas
    repeat(6) { lineIndex ->
        val yBase = size.height * (0.12f + lineIndex * 0.155f)
        val path = Path()
        path.moveTo(0f, yBase)
        val steps = 32
        for (step in 0..steps) {
            val x = size.width * step / steps
            val band = frame.band(step + lineIndex * 2)
            val wave1 = sinF(phase * 1.1f + step * 0.52f + lineIndex * 0.8f)
            val wave2 = cosF(phase * 0.65f + step * 0.28f)
            val y = yBase + (wave1 * 0.7f + wave2 * 0.3f) * size.height * (0.04f + band * 0.09f)
            path.lineTo(x, y)
        }
        drawPath(
            path,
            Brush.horizontalGradient(
                listOf(
                    colors[(lineIndex) % colors.size].copy(alpha = 0.0f),
                    colors[(lineIndex + 1) % colors.size].copy(alpha = 0.70f + frame.band(lineIndex) * 0.20f),
                    colors[(lineIndex + 2) % colors.size].copy(alpha = 0.0f),
                ),
            ),
            style = Stroke(width = (1.8f + frame.band(lineIndex * 3) * 3.5f).dp.toPx()),
        )
    }

    // Pulsing core
    val coreRadius = size.minDimension * (0.04f + amp * 0.06f)
    val coreCenter = Offset(size.width * 0.5f, size.height * 0.5f)
    drawCircle(
        color = colors[0].copy(alpha = 0.55f + amp * 0.40f),
        radius = coreRadius,
        center = coreCenter,
    )
}

// ─── Battery ─────────────────────────────────────────────────────────────────
private fun DrawScope.drawBattery(frame: AudioAnalysisFrame, phase: Float) {
    val colors = NowPlayingVisualizerPreset.Battery.visualColors()
    val amp = frame.amplitude.coerceIn(0f, 1f)
    repeat(7) { index ->
        val band = frame.band(index * 2)
        val scale = 0.22f + index * 0.055f + band * 0.08f + amp * 0.08f
        val rotation = phase * (0.25f + index * 0.035f) + index
        val cx = size.width * (0.5f + cosF(rotation) * 0.18f)
        val cy = size.height * (0.52f + sinF(rotation * 0.82f) * 0.14f)
        val w = size.minDimension * scale
        val h = w * (0.38f + band * 0.28f)
        val skew = w * 0.22f * sinF(rotation)
        val path = Path().apply {
            moveTo(cx - w / 2f + skew, cy - h / 2f)
            lineTo(cx + w / 2f + skew, cy - h / 2f)
            lineTo(cx + w / 2f - skew, cy + h / 2f)
            lineTo(cx - w / 2f - skew, cy + h / 2f)
            close()
        }
        drawPath(
            path,
            colors[index % colors.size].copy(alpha = 0.18f + band * 0.24f),
            style = Stroke(width = (1.4f + band * 3f).dp.toPx()),
        )
    }
    repeat(4) { index ->
        val y = size.height * (0.24f + index * 0.16f)
        drawLine(
            colors[(index + 2) % colors.size].copy(alpha = 0.28f + amp * 0.25f),
            Offset(size.width * 0.15f, y + sinF(phase + index) * 24f),
            Offset(size.width * 0.85f, y + cosF(phase * 0.8f + index) * 24f),
            strokeWidth = (1.5f + frame.band(index) * 3f).dp.toPx(),
        )
    }
}

// ─── Bars & Waves ────────────────────────────────────────────────────────────
// Bold vertical bars from the bottom with a centred waveform overlay
private fun DrawScope.drawBarsAndWaves(frame: AudioAnalysisFrame, phase: Float) {
    val colors = NowPlayingVisualizerPreset.BarsAndWaves.visualColors()
    val bands = frame.bands.ifEmpty { List(128) { 0.12f } }
    val slots = min(128, bands.size.coerceAtLeast(32))
    val slotWidth = size.width / slots
    val barW = slotWidth * 0.62f
    val baseline = size.height * 0.78f

    // Draw vertical bars from the baseline up
    for (index in 0 until slots) {
        val band = frame.band(index)
        val barHeight = baseline * (0.06f + band * 0.88f).coerceAtLeast(0.04f)
        val x = index * slotWidth + slotWidth * 0.19f
        val topY = baseline - barHeight

        // Gradient fill from a warm accent at top to a cool accent lower
        val barColor1 = colors[index % colors.size]
        val barColor2 = colors[(index + 1) % colors.size]
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    barColor1.copy(alpha = 0.85f + band * 0.15f),
                    barColor2.copy(alpha = 0.40f + band * 0.35f),
                    barColor2.copy(alpha = 0.12f),
                ),
                startY = topY,
                endY = baseline,
            ),
            topLeft = Offset(x, topY),
            size = Size(barW, barHeight),
            cornerRadius = CornerRadius(barW * 0.40f, barW * 0.40f),
        )

        // Bright tip highlight
        val tipH = (barW * 0.55f).coerceAtMost(barHeight * 0.15f).coerceAtLeast(2f)
        drawRoundRect(
            color = barColor1.copy(alpha = 0.90f + band * 0.10f),
            topLeft = Offset(x, topY),
            size = Size(barW, tipH),
            cornerRadius = CornerRadius(barW * 0.40f, barW * 0.40f),
        )
    }

    // Reflective mirror bars (faded, inverted) below baseline
    for (index in 0 until slots) {
        val band = frame.band(index)
        val mirrorH = baseline * (0.06f + band * 0.88f) * 0.28f
        val x = index * slotWidth + slotWidth * 0.19f
        drawRoundRect(
            color = colors[index % colors.size].copy(alpha = 0.12f + band * 0.10f),
            topLeft = Offset(x, baseline),
            size = Size(barW, mirrorH),
            cornerRadius = CornerRadius(barW * 0.40f, barW * 0.40f),
        )
    }

    // Centred waveform ribbon over the bars
    val mid = size.height * 0.40f
    repeat(2) { mirror ->
        val path = Path()
        path.moveTo(0f, mid)
        for (step in 0..slots) {
            val x = size.width * step / slots
            val band = frame.band(step)
            val wave = sinF(phase * 1.6f + step * 0.38f)
            val y = mid + wave * size.height * (0.04f + band * 0.10f) * if (mirror == 0) 1f else -1f
            path.lineTo(x, y)
        }
        drawPath(
            path,
            Brush.horizontalGradient(
                listOf(
                    colors[0].copy(alpha = 0.0f),
                    colors[(mirror + 1) % colors.size].copy(alpha = 0.88f),
                    colors[(mirror + 2) % colors.size].copy(alpha = 0.88f),
                    colors[0].copy(alpha = 0.0f),
                ),
            ),
            style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

// ─── Blazing Colors ──────────────────────────────────────────────────────────
// Upward-rising fire columns with ember particles and heat shimmer
private fun DrawScope.drawBlazingColors(frame: AudioAnalysisFrame, phase: Float) {
    val colors = NowPlayingVisualizerPreset.BlazingColors.visualColors()
    val amp = frame.amplitude.coerceIn(0f, 1f)
    val bands = frame.bands.ifEmpty { List(128) { 0.2f } }
    val columns = min(128, bands.size.coerceAtLeast(24))
    val colW = size.width / columns

    // Flame columns — tall tapered vertical brushes
    for (col in 0 until columns) {
        val band = frame.band(col)
        val flameH = size.height * (0.25f + band * 0.72f + amp * 0.03f)
        val x = col * colW + colW * 0.1f
        val w = colW * 0.80f
        val flickerOffset = sinF(phase * 2.4f + col * 0.43f) * size.width * 0.008f
        drawRoundRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.00f to colors[0].copy(alpha = 0.0f),
                    0.30f to colors[1].copy(alpha = 0.20f + band * 0.20f),
                    0.60f to colors[2].copy(alpha = 0.55f + band * 0.30f),
                    0.85f to colors[3].copy(alpha = 0.80f + band * 0.18f),
                    1.00f to colors[4].copy(alpha = 0.92f),
                ),
                startY = size.height - flameH,
                endY = size.height,
            ),
            topLeft = Offset(x + flickerOffset, size.height - flameH),
            size = Size(w, flameH),
            cornerRadius = CornerRadius(w * 0.50f, w * 0.50f),
        )
    }

    // Ember particles floating upward — deterministic from band + phase
    val particleCount = 80
    repeat(particleCount) { p ->
        val seed = (p * 137 + 31) % columns
        val band = frame.band(seed)
        val t = ((phase * 0.6f + p * 0.073f) % FullTurn) / FullTurn  // 0..1 loop
        val rising = 1f - t  // 1 at bottom, 0 at top
        val flameTopFraction = (0.25f + band * 0.72f)
        val px = (seed + 0.5f) * colW + sinF(phase + p * 0.41f) * colW * 1.2f
        val py = size.height * (1f - flameTopFraction * (1f - rising) - (1f - rising) * 0.3f)
        val radius = (1.5f + band * 3.5f + amp * 2f) * (0.3f + rising * 0.7f)
        val alpha = (0.55f + band * 0.40f) * rising.coerceIn(0f, 1f)
        val colorIdx = (p * 3) % colors.size
        drawCircle(
            color = colors[colorIdx].copy(alpha = alpha),
            radius = radius.dp.toPx(),
            center = Offset(px, py),
        )
    }

    // Base glow at the bottom
    val glowRadius = size.width * (0.3f + amp * 0.2f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                colors[4].copy(alpha = 0.55f + amp * 0.35f),
                colors[3].copy(alpha = 0.25f),
                Color.Transparent,
            ),
            center = Offset(size.width * 0.5f, size.height),
            radius = glowRadius,
        ),
        radius = glowRadius,
        center = Offset(size.width * 0.5f, size.height),
    )

    // Heat shimmer lines near mid-canvas
    repeat(3) { lineIdx ->
        val yLine = size.height * (0.25f + lineIdx * 0.12f + amp * 0.08f)
        val path = Path()
        path.moveTo(0f, yLine)
        val steps = 40
        for (step in 0..steps) {
            val x = size.width * step / steps
            val shim = sinF(phase * 3.5f + step * 0.55f + lineIdx * 1.1f)
            path.lineTo(x, yLine + shim * 6f * amp)
        }
        drawPath(
            path,
            colors[(lineIdx + 2) % colors.size].copy(alpha = 0.18f + amp * 0.20f),
            style = Stroke(width = 1.4.dp.toPx()),
        )
    }
}

// ─── Plenoptic ───────────────────────────────────────────────────────────────
private fun DrawScope.drawPlenoptic(frame: AudioAnalysisFrame, phase: Float) {
    val colors = NowPlayingVisualizerPreset.Plenoptic.visualColors()
    val center = Offset(size.width / 2f, size.height / 2f)
    val amp = frame.amplitude.coerceIn(0f, 1f)
    repeat(6) { ring ->
        val ringRadius = size.minDimension * (0.11f + ring * 0.065f + amp * 0.05f)
        val count = 8 + ring * 4
        repeat(count) { index ->
            val band = frame.band(index + ring * 3)
            val angle = phase * (0.16f + ring * 0.018f) + index * FullTurn / count
            val dot = Offset(
                center.x + cosF(angle) * ringRadius,
                center.y + sinF(angle) * ringRadius,
            )
            val r = size.minDimension * (0.006f + band * 0.018f)
            drawCircle(
                color = colors[(index + ring) % colors.size].copy(alpha = 0.32f + band * 0.44f),
                radius = r,
                center = dot,
            )
            drawLine(
                color = colors[ring % colors.size].copy(alpha = 0.08f + band * 0.16f),
                start = center,
                end = dot,
                strokeWidth = 1.dp.toPx(),
            )
        }
        drawCircle(
            color = colors[ring % colors.size].copy(alpha = 0.08f + amp * 0.10f),
            radius = ringRadius,
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

// ─── Vortex Spectrum ─────────────────────────────────────────────────────────
// 3D-angled circular spectrum analyzer with rainbow colors
private fun DrawScope.drawVortexSpectrum(frame: AudioAnalysisFrame, phase: Float) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val baseRadiusX = size.minDimension * 0.25f
    val baseRadiusY = baseRadiusX * 0.35f // Flatten to create 3D perspective
    
    val bands = frame.bands.ifEmpty { List(128) { 0.1f } }
    val count = bands.size
    
    // Draw central black hole/disc
    drawOval(
        color = Color.Black,
        topLeft = Offset(center.x - baseRadiusX, center.y - baseRadiusY),
        size = Size(baseRadiusX * 2, baseRadiusY * 2)
    )

    for (i in 0 until count) {
        val band = bands[i].coerceIn(0f, 1f)
        val angle = (i.toFloat() / count) * FullTurn
        
        val hue = (i.toFloat() / count) * 360f
        val color = Color.hsv(hue, 1f, 1f)
        
        val startX = center.x + cosF(angle) * baseRadiusX
        val startY = center.y + sinF(angle) * baseRadiusY
        
        // Project length outwards
        val length = size.minDimension * 0.3f * band
        val endX = startX + cosF(angle) * length
        val endY = startY + sinF(angle) * length * 0.35f // Apply same perspective scale
        
        drawLine(
            color = color,
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

// ─── Classic EQ ──────────────────────────────────────────────────────────────
// Segmented LED graphic equalizer with peak holds
private fun DrawScope.drawClassicEQ(frame: AudioAnalysisFrame, phase: Float) {
    val bands = frame.bands.ifEmpty { List(128) { 0.1f } }
    val count = min(128, bands.size.coerceAtLeast(16))
    val spacing = 2.dp.toPx()
    val barWidth = (size.width - spacing * (count + 1)) / count
    val segmentHeight = 4.dp.toPx()
    val segmentSpacing = 1.dp.toPx()
    
    val maxSegments = (size.height / (segmentHeight + segmentSpacing)).toInt().coerceAtLeast(1)
    
    for (i in 0 until count) {
        val band = frame.band(i)
        val activeSegments = (band * maxSegments).toInt().coerceIn(0, maxSegments)
        
        val x = spacing + i * (barWidth + spacing)
        
        for (s in 0 until maxSegments) {
            val y = size.height - (s + 1) * (segmentHeight + segmentSpacing)
            
            val color = when {
                s > maxSegments * 0.8f -> Color.Red
                s > maxSegments * 0.5f -> Color.Yellow
                else -> Color.Green
            }
            
            if (s < activeSegments) {
                drawRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, segmentHeight)
                )
            } else if (s == activeSegments && activeSegments > 0) {
                // Peak hold simulation (simplified flicker)
                val peakHoldDecay = (phase * 5f).toInt() % 3
                if (peakHoldDecay > 0 && s + 1 < maxSegments) {
                     drawRect(
                        color = Color.Red,
                        topLeft = Offset(x, y - (segmentHeight + segmentSpacing)),
                        size = Size(barWidth, segmentHeight)
                    )
                }
            } else {
                drawRect(
                    color = color.copy(alpha = 0.1f),
                    topLeft = Offset(x, y),
                    size = Size(barWidth, segmentHeight)
                )
            }
        }
    }
}

// ─── Halo Spectrum ───────────────────────────────────────────────────────────
// 2D circular spectrum analyzer with rainbow gradients
private fun DrawScope.drawHaloSpectrum(frame: AudioAnalysisFrame, phase: Float) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val baseRadius = size.minDimension * 0.25f
    val amp = frame.amplitude.coerceIn(0f, 1f)
    val dynamicRadius = baseRadius + amp * size.minDimension * 0.05f
    
    val bands = frame.bands.ifEmpty { List(128) { 0.1f } }
    val count = bands.size
    
    // Draw pulsating center circle
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color(0x33FFFFFF)),
            center = center,
            radius = dynamicRadius.coerceAtLeast(1f)
        ),
        radius = dynamicRadius,
        center = center
    )
    
    for (i in 0 until count) {
        val band = bands[i].coerceIn(0f, 1f)
        val angle = (i.toFloat() / count) * FullTurn + phase * 0.5f
        
        val hue = ((i.toFloat() / count) * 360f + phase * 30f) % 360f
        val color = Color.hsv(hue, 0.9f, 0.9f)
        
        val startX = center.x + cosF(angle) * dynamicRadius
        val startY = center.y + sinF(angle) * dynamicRadius
        
        val length = size.minDimension * 0.2f * band
        val endX = startX + cosF(angle) * length
        val endY = startY + sinF(angle) * length
        
        drawLine(
            color = color,
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawWireframeSpectrum3D(
    state: AudioVisualizerRenderState,
    yaw: Float,
    pitch: Float,
) {
    drawRect(Color.Black)

    val mesh = state.mesh
    val centerY = size.height * 0.52f
    val centerGlow = (0.16f + state.envelope * 0.36f).coerceIn(0f, 0.62f)
    val centerStroke = (1.1f + state.envelope * 2.4f).dp.toPx()
    drawLine(
        color = Color(0xFF7D8CFF).copy(alpha = 0.16f + centerGlow),
        start = Offset(0f, centerY),
        end = Offset(size.width, centerY),
        strokeWidth = centerStroke * 3.2f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = Color(0xFFE8E8FF).copy(alpha = 0.48f + state.envelope * 0.28f),
        start = Offset(0f, centerY),
        end = Offset(size.width, centerY),
        strokeWidth = centerStroke,
        cap = StrokeCap.Round,
    )

    fun point(vertex: WireframeVertex): Offset {
        val centeredX = (vertex.x - 0.5f) * 2f
        val recency = 1f - vertex.z
        val depth = (recency - 0.5f) * 2f
        val yawX = centeredX * cosF(yaw) + depth * sinF(yaw)
        val yawDepth = depth * cosF(yaw) - centeredX * sinF(yaw)
        val rotatedY = vertex.y * cosF(pitch) - yawDepth * sinF(pitch) * 0.46f
        val rotatedDepth = yawDepth * cosF(pitch) + vertex.y * sinF(pitch) * 0.46f
        val perspective = 0.64f + (rotatedDepth + 1f) * 0.20f + recency * 0.22f
        val backSideFade = 0.72f + 0.28f * ((rotatedDepth + 1f) * 0.5f).coerceIn(0f, 1f)
        val x = size.width * (0.5f + yawX * 0.50f * perspective)
        val yScale = size.height * (0.11f + recency * 0.31f)
        val y = centerY - rotatedY * yScale * backSideFade
        return Offset(x, y)
    }

    fun colorFor(vertex: WireframeVertex): Color {
        val upper = vertex.y >= 0f
        val height = abs(vertex.y).coerceIn(0f, 1f)
        val base = when {
            upper && height > 0.46f -> lerp(Color(0xFF48F28B), Color(0xFF00FF2F), vertex.intensity)
            upper -> lerp(Color(0xFF4DABF7), Color(0xFF8CEBFF), height)
            height > 0.46f -> lerp(Color(0xFFFF8E8E), Color(0xFFFFFFD2), vertex.intensity)
            else -> lerp(Color(0xFFA889FF), Color(0xFFFFB6A8), height)
        }
        val alpha = (0.18f + (1f - vertex.z) * 0.44f + state.envelope * 0.2f).coerceIn(0.14f, 0.92f)
        return base.copy(alpha = alpha)
    }

    fun drawSegments(segments: List<WireframeSegment>, strokeWidth: Float, alphaScale: Float) {
        segments.forEach { segment ->
            val from = mesh.vertices[segment.from]
            val to = mesh.vertices[segment.to]
            val color = lerp(colorFor(from), colorFor(to), 0.5f)
            drawLine(
                color = color.copy(alpha = (color.alpha * alphaScale).coerceIn(0f, 1f)),
                start = point(from),
                end = point(to),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }

    drawSegments(mesh.diagonalSegments, strokeWidth = 0.7.dp.toPx(), alphaScale = 0.54f)
    drawSegments(mesh.horizontalSegments, strokeWidth = 0.9.dp.toPx(), alphaScale = 0.86f)

    mesh.centerSegments.forEach { segment ->
        val from = mesh.vertices[segment.from].copy(y = 0f)
        val to = mesh.vertices[segment.to].copy(y = 0f)
        drawLine(
            color = Color(0xFFC8D4FF).copy(alpha = 0.32f + state.envelope * 0.36f),
            start = point(from),
            end = point(to),
            strokeWidth = 1.2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }

    val glowRadius = size.minDimension * (0.16f + state.envelope * 0.22f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x447D8CFF), Color.Transparent),
            center = Offset(size.width * 0.5f, centerY),
            radius = glowRadius,
        ),
        center = Offset(size.width * 0.5f, centerY),
        radius = glowRadius,
    )
}

// ─── Color palettes ──────────────────────────────────────────────────────────
private fun NowPlayingVisualizerPreset.backgroundColors(): List<Color> =
    when (this) {
        NowPlayingVisualizerPreset.Alchemy -> listOf(Color(0xFF0E2B42), Color(0xFF1A0A30), Color(0xFF05070D))
        NowPlayingVisualizerPreset.Battery -> listOf(Color(0xFF122520), Color(0xFF121827), Color(0xFF05070D))
        NowPlayingVisualizerPreset.BarsAndWaves -> listOf(Color(0xFF0D1A2E), Color(0xFF111827), Color(0xFF05070D))
        NowPlayingVisualizerPreset.BlazingColors -> listOf(Color(0xFF280A00), Color(0xFF1A0500), Color(0xFF05070D))
        NowPlayingVisualizerPreset.Plenoptic -> listOf(Color(0xFF1A1233), Color(0xFF092B34), Color(0xFF05070D))
        NowPlayingVisualizerPreset.VortexSpectrum -> listOf(Color(0xFF0A0A0A), Color(0xFF000000), Color(0xFF000000))
        NowPlayingVisualizerPreset.ClassicEQ -> listOf(Color(0xFF0F0F0F), Color(0xFF050505), Color(0xFF000000))
        NowPlayingVisualizerPreset.HaloSpectrum -> listOf(Color(0xFF1A1A24), Color(0xFF08080C), Color(0xFF05050A))
        NowPlayingVisualizerPreset.WireframeSpectrum3D -> listOf(Color.Black, Color.Black)
        NowPlayingVisualizerPreset.CanyonWire3D,
        NowPlayingVisualizerPreset.PulseTunnel3D,
        NowPlayingVisualizerPreset.OrbitalHalo3D,
        NowPlayingVisualizerPreset.SpiralGalaxy3D,
        NowPlayingVisualizerPreset.AuroraRibbon3D,
        NowPlayingVisualizerPreset.CrystalPeaks3D,
        NowPlayingVisualizerPreset.PrismFan3D,
        NowPlayingVisualizerPreset.WaveRibbon3D,
        NowPlayingVisualizerPreset.KaleidoscopeWeb3D,
        NowPlayingVisualizerPreset.StarfieldWeb3D,
        -> listOf(Color.Black, Color.Black)
        NowPlayingVisualizerPreset.Artwork -> listOf(Color.Transparent, Color.Transparent)
    }

private fun NowPlayingVisualizerPreset.visualColors(): List<Color> =
    when (this) {
        NowPlayingVisualizerPreset.Alchemy -> listOf(
            Color(0xFF38D9A9), Color(0xFF74C0FC), Color(0xFFE599F7),
            Color(0xFFFFD43B), Color(0xFFFF6B6B), Color(0xFF4DABF7),
        )
        NowPlayingVisualizerPreset.Battery -> listOf(
            Color(0xFF94D82D), Color(0xFF15AABF), Color(0xFFFFD43B), Color(0xFFCED4DA),
        )
        NowPlayingVisualizerPreset.BarsAndWaves -> listOf(
            Color(0xFF4DABF7), Color(0xFF9775FA), Color(0xFF63E6BE), Color(0xFFFFD43B),
        )
        NowPlayingVisualizerPreset.BlazingColors -> listOf(
            // tip → mid → base (cool to white-hot)
            Color(0xFFFF2200), // deep red tip
            Color(0xFFFF6600), // orange
            Color(0xFFFF9900), // amber
            Color(0xFFFFCC00), // gold
            Color(0xFFFFFFAA), // near-white core
        )
        NowPlayingVisualizerPreset.Plenoptic -> listOf(
            Color(0xFFE599F7), Color(0xFF66D9E8), Color(0xFFFFD43B), Color(0xFFFF8787),
        )
        NowPlayingVisualizerPreset.VortexSpectrum -> listOf(Color.White)
        NowPlayingVisualizerPreset.ClassicEQ -> listOf(Color(0xFF00FF00), Color(0xFFFFFF00), Color(0xFFFF0000))
        NowPlayingVisualizerPreset.HaloSpectrum -> listOf(Color.White)
        NowPlayingVisualizerPreset.WireframeSpectrum3D -> listOf(
            Color(0xFF00FF2F),
            Color(0xFF4DABF7),
            Color(0xFFA889FF),
            Color(0xFFFF8E8E),
        )
        NowPlayingVisualizerPreset.CanyonWire3D -> listOf(Color(0xFF8CE99A), Color(0xFF74C0FC), Color(0xFFFF8787))
        NowPlayingVisualizerPreset.PulseTunnel3D -> listOf(Color(0xFF66D9E8), Color(0xFFA889FF), Color(0xFFFFD43B))
        NowPlayingVisualizerPreset.OrbitalHalo3D -> listOf(Color(0xFFFFFFFF), Color(0xFF91A7FF), Color(0xFFFFA8A8))
        NowPlayingVisualizerPreset.SpiralGalaxy3D -> listOf(Color(0xFFB197FC), Color(0xFF63E6BE), Color(0xFFFFD43B))
        NowPlayingVisualizerPreset.AuroraRibbon3D -> listOf(Color(0xFF69DB7C), Color(0xFF66D9E8), Color(0xFFE599F7))
        NowPlayingVisualizerPreset.CrystalPeaks3D -> listOf(Color(0xFFA5D8FF), Color(0xFFFFFFFF), Color(0xFFB2F2BB))
        NowPlayingVisualizerPreset.PrismFan3D -> listOf(Color(0xFFFFD43B), Color(0xFFFF8787), Color(0xFF74C0FC))
        NowPlayingVisualizerPreset.WaveRibbon3D -> listOf(Color(0xFF74C0FC), Color(0xFF63E6BE), Color(0xFFA889FF))
        NowPlayingVisualizerPreset.KaleidoscopeWeb3D -> listOf(Color(0xFF00FF2F), Color(0xFFFFD43B), Color(0xFFFF8E8E))
        NowPlayingVisualizerPreset.StarfieldWeb3D -> listOf(Color(0xFFFFFFFF), Color(0xFF91A7FF), Color(0xFF66D9E8))
        NowPlayingVisualizerPreset.Artwork -> listOf(Color(0xFF74C0FC))
    }

private fun NowPlayingVisualizerPreset.isFilament3DVisualizer(): Boolean =
    when (this) {
        NowPlayingVisualizerPreset.WireframeSpectrum3D,
        NowPlayingVisualizerPreset.CanyonWire3D,
        NowPlayingVisualizerPreset.PulseTunnel3D,
        NowPlayingVisualizerPreset.OrbitalHalo3D,
        NowPlayingVisualizerPreset.SpiralGalaxy3D,
        NowPlayingVisualizerPreset.AuroraRibbon3D,
        NowPlayingVisualizerPreset.CrystalPeaks3D,
        NowPlayingVisualizerPreset.PrismFan3D,
        NowPlayingVisualizerPreset.WaveRibbon3D,
        NowPlayingVisualizerPreset.KaleidoscopeWeb3D,
        NowPlayingVisualizerPreset.StarfieldWeb3D,
        -> true
        else -> false
    }

// ─── Helpers ─────────────────────────────────────────────────────────────────
private fun AudioAnalysisFrame.band(index: Int): Float {
    if (bands.isEmpty()) return amplitude.coerceIn(0f, 1f)
    return bands[index.floorMod(bands.size)].coerceIn(0f, 1f)
}

private fun sinF(value: Float): Float = sin(value.toDouble()).toFloat()

private fun cosF(value: Float): Float = cos(value.toDouble()).toFloat()

private fun wrapFullTurn(value: Float): Float {
    val wrapped = value % FullTurn
    return if (wrapped < 0f) wrapped + FullTurn else wrapped
}

private fun Int.floorMod(divisor: Int): Int =
    ((this % divisor) + divisor) % divisor
