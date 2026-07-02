package com.phoebe.app.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.ui.PhoebeIcon
import com.phoebe.app.ui.PhoebeIconView
import com.phoebe.app.ui.PhoebeUi

@Composable
internal fun VisualizerPresetSelector(
    selected: NowPlayingVisualizerPreset,
    onSelected: (NowPlayingVisualizerPreset) -> Unit,
    compact: Boolean = false,
    showInTvFrame: Boolean = false,
    onShowInTvFrameChange: (Boolean) -> Unit = {},
) {
    val rowSize = if (compact) 2 else 4
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 42.dp else 46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (showInTvFrame) PhoebeUi.accent.copy(alpha = 0.16f) else PhoebeUi.subtleFill)
                .border(
                    BorderStroke(
                        1.dp,
                        if (showInTvFrame) PhoebeUi.accent.copy(alpha = 0.36f) else PhoebeUi.border,
                    ),
                    RoundedCornerShape(8.dp),
                )
                .clickable { onShowInTvFrameChange(!showInTvFrame) }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhoebeIconView(
                if (showInTvFrame) PhoebeIcon.Check else PhoebeIcon.Visualizer,
                tint = if (showInTvFrame) PhoebeUi.accentLight else PhoebeUi.secondaryText,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "Show In TV",
                color = if (showInTvFrame) PhoebeUi.accentLight else PhoebeUi.secondaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
                                BorderStroke(
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
