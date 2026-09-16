package com.phoebe.app.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoebe.app.domain.BundledVisualizerPacks
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.feature.playback.VisualizerPacks
import com.phoebe.app.feature.playback.VisualizerUserPackRepository
import com.phoebe.app.platform.rememberPickVisualizerPresetFiles
import com.phoebe.app.ui.PhoebeIcon
import com.phoebe.app.ui.PhoebeIconView
import com.phoebe.app.ui.PhoebeUi
import kotlinx.coroutines.launch

/**
 * Settings: external/user preset import only.
 * Artwork / Shuffle / bundled picks live in the player overflow menu.
 */
@Composable
internal fun VisualizerPresetSelector(
    selected: NowPlayingVisualizerPreset,
    onSelected: (NowPlayingVisualizerPreset) -> Unit,
    compact: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val installed by VisualizerPacks.store.installed.collectAsState()
    val externalPresets = installed
        .filter { it.id != BundledVisualizerPacks.BundledPackId }
        .flatMap { it.presets }
    val pickFiles = rememberPickVisualizerPresetFiles { files ->
        if (files.isEmpty()) return@rememberPickVisualizerPresetFiles
        scope.launch {
            VisualizerPacks.importUserPresets(files)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Bundled presets are in the player menu. Add your own .milk or Butterchurn .json files here.",
            color = PhoebeUi.secondaryText,
            fontSize = 12.sp,
        )

        Text(
            "Add preset files…",
            color = PhoebeUi.accentLight,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = pickFiles),
        )

        if (externalPresets.isNotEmpty()) {
            Text(
                "My presets",
                color = PhoebeUi.secondaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (compact) 180.dp else 240.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                externalPresets.forEach { preset ->
                    val pinned = NowPlayingVisualizerPreset.Pinned(
                        packId = preset.packId,
                        presetId = preset.presetId,
                        displayName = preset.displayName,
                    )
                    val active = selected is NowPlayingVisualizerPreset.Pinned &&
                        selected.packId == preset.packId &&
                        selected.presetId == preset.presetId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (compact) 38.dp else 42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) PhoebeUi.accent.copy(alpha = 0.16f) else PhoebeUi.subtleFill)
                            .border(
                                BorderStroke(
                                    1.dp,
                                    if (active) PhoebeUi.accent.copy(alpha = 0.36f) else PhoebeUi.border,
                                ),
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { onSelected(pinned) }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PhoebeIconView(
                            PhoebeIcon.Visualizer,
                            tint = if (active) PhoebeUi.accentLight else PhoebeUi.secondaryText,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            preset.displayName,
                            color = if (active) PhoebeUi.accentLight else PhoebeUi.secondaryText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text(
                "Clear my presets",
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable {
                    scope.launch {
                        VisualizerPacks.clearUserPack()
                        if (selected is NowPlayingVisualizerPreset.Pinned &&
                            selected.packId == VisualizerUserPackRepository.UserPackId
                        ) {
                            onSelected(NowPlayingVisualizerPreset.Shuffle())
                        }
                    }
                },
            )
        }
    }
}
