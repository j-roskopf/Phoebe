package com.phoebe.app.feature.playback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.ui.PhoebeIcon
import com.phoebe.app.ui.PhoebeIconView
import com.phoebe.app.ui.PhoebeUi
import kotlin.math.roundToInt

@Composable
fun EqualizerDialog(
    profile: EqualizerProfile,
    persistEnabled: Boolean,
    remoteUnavailable: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onBandCountChange: (Int) -> Unit,
    onGainChange: (Int, Float) -> Unit,
    onReset: () -> Unit,
    onPersistChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Pause / hide window-level visualizer overlays (iOS GL / web Butterchurn)
    // so EQ gestures are not fighting main-thread GL present.
    SuppressVisualizerHtmlOverlay(active = true)
    val normalized = profile.normalized()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val maxPanelHeight = maxHeight - 32.dp
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 620.dp)
                    .heightIn(max = maxPanelHeight),
                shape = RoundedCornerShape(8.dp),
                color = PhoebeUi.panel,
                border = BorderStroke(1.dp, PhoebeUi.border),
                shadowElevation = 10.dp,
            ) {
                LazyColumn(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    item {
                        EqualizerHeader(
                            enabled = normalized.enabled,
                            onEnabledChange = onEnabledChange,
                            onReset = onReset,
                            onDismiss = onDismiss,
                        )
                    }
                    item {
                        EqualizerBandCountSelector(
                            selected = normalized.bandCount,
                            onBandCountChange = onBandCountChange,
                        )
                    }
                    item {
                        EqualizerCurve(
                            profile = normalized,
                            onGainChange = onGainChange,
                        )
                    }
                    if (remoteUnavailable) {
                        item {
                            Text(
                                "Chromecast and remote players use their own audio path, so these settings affect local playback on this device.",
                                color = PhoebeUi.mutedText,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                    item {
                        EqualizerPersistRow(
                            checked = persistEnabled,
                            onCheckedChange = onPersistChange,
                        )
                    }
                    itemsIndexed(normalized.bands, key = { index, band -> "${band.frequencyHz}-$index" }) { index, band ->
                        EqualizerBandSlider(
                            label = band.label,
                            gainDb = normalized.gainsDb.getOrElse(index) { 0f },
                            onGainChange = { onGainChange(index, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EqualizerHeader(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhoebeIconView(PhoebeIcon.Equalizer, tint = PhoebeUi.accentLight, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                "Graphic equalizer",
                color = PhoebeUi.primaryText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onEnabledChange(!enabled) }) {
                Text(
                    if (enabled) "Disable EQ" else "Enable EQ",
                    color = PhoebeUi.secondaryText,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                colors = equalizerSwitchColors(),
                modifier = Modifier.semantics { contentDescription = "Enable equalizer" },
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(PhoebeUi.border),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onReset) {
                Text("Reset", color = PhoebeUi.secondaryText)
            }
            TextButton(onClick = onDismiss) {
                Text("Close", color = PhoebeUi.secondaryText)
            }
        }
    }
}

@Composable
private fun EqualizerBandCountSelector(
    selected: Int,
    onBandCountChange: (Int) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EqualizerProfile.SupportedBandCounts.forEach { count ->
            val active = count == selected
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .width(72.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (active) PhoebeUi.accentLight else Color.Transparent)
                    .border(
                        BorderStroke(1.dp, if (active) Color.Transparent else PhoebeUi.border),
                        RoundedCornerShape(22.dp),
                    )
                    .clickable { onBandCountChange(count) }
                    .semantics { contentDescription = "$count equalizer bands" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    count.toString(),
                    color = if (active) PhoebeUi.shellTop else PhoebeUi.secondaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun EqualizerCurve(
    profile: EqualizerProfile,
    onGainChange: (Int, Float) -> Unit,
) {
    val normalized = profile.normalized()
    val borderColor = PhoebeUi.border
    val accentColor = PhoebeUi.accentLight
    val latestOnGainChange = rememberUpdatedState(onGainChange)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(PhoebeUi.canvasBackground.copy(alpha = 0.76f))
            .border(BorderStroke(1.dp, borderColor.copy(alpha = 0.45f)), RoundedCornerShape(8.dp))
            .pointerInput(normalized.bandCount) {
                fun updateBandFromOffset(offset: Offset) {
                    val bounds = equalizerCurveBounds(size.width.toFloat(), size.height.toFloat())
                    val index = equalizerCurveBandIndex(
                        x = offset.x,
                        bounds = bounds,
                        bandCount = normalized.bandCount,
                    )
                    latestOnGainChange.value(index, equalizerCurveGain(offset.y, bounds))
                }
                detectDragGestures(
                    onDragStart = { offset -> updateBandFromOffset(offset) },
                    onDrag = { change, _ ->
                        change.consume()
                        updateBandFromOffset(change.position)
                    },
                )
            }
            .semantics { contentDescription = "Drag equalizer curve to adjust bands" },
    ) {
        val gains = normalized.gainsDb.ifEmpty { List(normalized.bandCount) { 0f } }
        val bounds = equalizerCurveBounds(size.width, size.height)
        drawLine(
            borderColor.copy(alpha = 0.8f),
            Offset(bounds.left, bounds.zeroY),
            Offset(bounds.right, bounds.zeroY),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )
        val points = gains.mapIndexed { index, gain ->
            val x = if (gains.size == 1) {
                (bounds.left + bounds.right) / 2f
            } else {
                bounds.left + ((bounds.width) * index / (gains.lastIndex).toFloat())
            }
            val y = bounds.zeroY - (gain / EqualizerProfile.MaxGainDb) * bounds.gainRange
            Offset(x, y.coerceIn(bounds.top, bounds.bottom))
        }
        if (points.isEmpty()) return@Canvas
        val curve = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.zipWithNext().forEach { (start, end) ->
                val controlX = (start.x + end.x) / 2f
                cubicTo(controlX, start.y, controlX, end.y, end.x, end.y)
            }
        }
        val fill = Path().apply {
            addPath(curve)
            lineTo(points.last().x, bounds.bottom)
            lineTo(points.first().x, bounds.bottom)
            close()
        }
        drawPath(
            fill,
            Brush.verticalGradient(
                0f to accentColor.copy(alpha = 0.34f),
                1f to accentColor.copy(alpha = 0.04f),
            ),
        )
        drawPath(
            curve,
            accentColor,
            style = Stroke(width = 4f, cap = StrokeCap.Round),
        )
        points.forEach { point ->
            drawCircle(accentColor, radius = 5.5f, center = point)
        }
    }
}

@Composable
private fun EqualizerPersistRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "Persist EQ after restart",
                color = PhoebeUi.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Apply this profile to every song when Phoebe opens again.",
                color = PhoebeUi.mutedText,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = equalizerSwitchColors(),
        )
    }
}

@Composable
private fun EqualizerBandSlider(
    label: String,
    gainDb: Float,
    onGainChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.width(64.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                label,
                color = PhoebeUi.primaryText,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatEqualizerGain(gainDb),
                color = PhoebeUi.secondaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Slider(
            value = gainDb.coerceIn(EqualizerProfile.MinGainDb, EqualizerProfile.MaxGainDb),
            onValueChange = onGainChange,
            valueRange = EqualizerProfile.MinGainDb..EqualizerProfile.MaxGainDb,
            steps = ((EqualizerProfile.MaxGainDb - EqualizerProfile.MinGainDb) / EqualizerProfile.GainStepDb).roundToInt() - 1,
            colors = SliderDefaults.colors(
                thumbColor = PhoebeUi.accentLight,
                activeTrackColor = PhoebeUi.accentLight,
                inactiveTrackColor = PhoebeUi.border,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun equalizerSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = PhoebeUi.shellTop,
    checkedTrackColor = PhoebeUi.accentLight,
    uncheckedThumbColor = PhoebeUi.secondaryText,
    uncheckedTrackColor = PhoebeUi.border,
)

private fun formatEqualizerGain(gainDb: Float): String {
    val rounded = (gainDb * 10f).roundToInt() / 10f
    val absolute = kotlin.math.abs(rounded)
    val value = if (absolute == absolute.roundToInt().toFloat()) {
        "${absolute.roundToInt()}.0"
    } else {
        absolute.toString()
    }
    return when {
        rounded > 0f -> "+$value"
        rounded < 0f -> "-$value"
        else -> "+0.0"
    }
}

private data class EqualizerCurveBounds(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
) {
    val width: Float = (right - left).coerceAtLeast(1f)
    val height: Float = (bottom - top).coerceAtLeast(1f)
    val zeroY: Float = top + height * 0.5f
    val gainRange: Float = height * EqualizerCurveGainScale
}

private fun equalizerCurveBounds(width: Float, height: Float): EqualizerCurveBounds {
    val left = EqualizerCurveHorizontalInsetPx
    val right = (width - EqualizerCurveHorizontalInsetPx).coerceAtLeast(left + 1f)
    val top = EqualizerCurveTopInsetPx
    val bottom = (height - EqualizerCurveBottomInsetPx).coerceAtLeast(top + 1f)
    return EqualizerCurveBounds(left, right, top, bottom)
}

private fun equalizerCurveBandIndex(
    x: Float,
    bounds: EqualizerCurveBounds,
    bandCount: Int,
): Int {
    if (bandCount <= 1) return 0
    val fraction = ((x.coerceIn(bounds.left, bounds.right) - bounds.left) / bounds.width)
        .coerceIn(0f, 1f)
    return (fraction * (bandCount - 1)).roundToInt().coerceIn(0, bandCount - 1)
}

private fun equalizerCurveGain(y: Float, bounds: EqualizerCurveBounds): Float {
    val gain = ((bounds.zeroY - y.coerceIn(bounds.top, bounds.bottom)) / bounds.gainRange) *
        EqualizerProfile.MaxGainDb
    return ((gain / EqualizerProfile.GainStepDb).roundToInt() * EqualizerProfile.GainStepDb)
        .coerceIn(EqualizerProfile.MinGainDb, EqualizerProfile.MaxGainDb)
}

private const val EqualizerCurveHorizontalInsetPx = 12f
private const val EqualizerCurveTopInsetPx = 16f
private const val EqualizerCurveBottomInsetPx = 18f
private const val EqualizerCurveGainScale = 0.46f
