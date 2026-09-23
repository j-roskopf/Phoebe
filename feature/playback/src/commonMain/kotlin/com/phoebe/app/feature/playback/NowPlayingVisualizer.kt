package com.phoebe.app.feature.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.phoebe.app.domain.BundledVisualizerPacks
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.domain.Track
import com.phoebe.app.ui.EmptyNowPlayingArtworkSlot
import com.phoebe.app.ui.PhoebeIcon
import com.phoebe.app.ui.PhoebeIconView
import com.phoebe.app.ui.PhoebeUi
import com.phoebe.app.ui.TrackArtworkImage

private data class ResolvedVisualizerPayload(
    val path: String? = null,
    val data: String? = null,
)

@Composable
private fun rememberResolvedVisualizerPayload(
    preset: NowPlayingVisualizerPreset,
    shuffleKey: Any? = null,
): ResolvedVisualizerPayload {
    var payload by remember(preset, shuffleKey) { mutableStateOf(ResolvedVisualizerPayload()) }
    LaunchedEffect(preset, shuffleKey) {
        when (preset) {
            is NowPlayingVisualizerPreset.Artwork -> {
                payload = ResolvedVisualizerPayload()
            }
            is NowPlayingVisualizerPreset.Shuffle -> {
                val pool = VisualizerPacks.store.allPresets()
                    .ifEmpty { BundledVisualizerPacks.allPresets }
                    .filter { preset.packId == null || it.packId == preset.packId }
                val ref = pool.randomOrNull()
                if (ref == null) {
                    payload = ResolvedVisualizerPayload()
                } else {
                    val userData = VisualizerPacks.loadUserPayload(ref.packId, ref.presetId)
                    payload = ResolvedVisualizerPayload(
                        path = if (userData == null) {
                            BundledPresetLoader.relativeFetchPath(ref.presetId)
                        } else {
                            null
                        },
                        data = userData ?: BundledPresetLoader.loadPresetPayload(ref.presetId),
                    )
                }
            }
            is NowPlayingVisualizerPreset.Pinned -> {
                val userData = VisualizerPacks.loadUserPayload(preset.packId, preset.presetId)
                payload = ResolvedVisualizerPayload(
                    path = if (userData == null) {
                        BundledPresetLoader.relativeFetchPath(preset.presetId)
                    } else {
                        null
                    },
                    data = userData ?: BundledPresetLoader.loadPresetPayload(preset.presetId),
                )
            }
        }
    }
    return payload
}

@Composable
fun NowPlayingVisualizerSurface(
    preset: NowPlayingVisualizerPreset,
    track: Track?,
    isPlaying: Boolean,
    positionMs: Long,
    modifier: Modifier = Modifier,
    desktopArtworkConstrained: Boolean = false,
    showFullscreenButton: Boolean = true,
    fullscreenButtonAlpha: Float = 1f,
    presetMilkPath: String? = null,
    presetMilkData: String? = null,
    /** Pause live GL while the mobile sheet is dragging/animating. */
    suspendRendering: Boolean = false,
) {
    var fullscreen by remember(preset) { mutableStateOf(false) }
    val clampedFullscreenButtonAlpha = fullscreenButtonAlpha.coerceIn(0f, 1f)
    @Suppress("UNUSED_VARIABLE")
    val unusedPosition = positionMs
    val resolved = rememberResolvedVisualizerPayload(
        preset = preset,
        shuffleKey = if (preset is NowPlayingVisualizerPreset.Shuffle) track?.id else null,
    )
    val effectivePath = presetMilkPath ?: resolved.path
    val effectiveData = presetMilkData ?: resolved.data
    val liveSuspended = suspendRendering && !fullscreen

    Box(modifier.clipToBounds()) {
        if (fullscreen) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        } else {
            NowPlayingVisualizerDisplay(
                preset = preset,
                track = track,
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxSize(),
                desktopArtworkConstrained = desktopArtworkConstrained,
                presetMilkPath = effectivePath,
                presetMilkData = effectiveData,
                locked = preset is NowPlayingVisualizerPreset.Pinned,
                suspendRendering = liveSuspended,
            )
        }

        if (!fullscreen &&
            showFullscreenButton &&
            preset.isVisualizer &&
            ProjectMRenderer.isNativeAvailable() &&
            clampedFullscreenButtonAlpha > 0f
        ) {
            VisualizerIconButton(
                description = "Open visualizer full screen",
                onClick = { fullscreen = true },
                active = true,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .graphicsLayer { alpha = clampedFullscreenButtonAlpha }
                    .zIndex(2f),
                icon = PhoebeIcon.Fullscreen,
            )
        }
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                NowPlayingVisualizerDisplay(
                    preset = preset,
                    track = track,
                    isPlaying = isPlaying,
                    modifier = Modifier.fillMaxSize(),
                    desktopArtworkConstrained = false,
                    presetMilkPath = effectivePath,
                    presetMilkData = effectiveData,
                    locked = true,
                    suspendRendering = false,
                )
                VisualizerIconButton(
                    description = "Close full screen visualizer",
                    onClick = { fullscreen = false },
                    active = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(18.dp)
                        .zIndex(2f),
                    icon = PhoebeIcon.Close,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingVisualizerDisplay(
    preset: NowPlayingVisualizerPreset,
    track: Track?,
    isPlaying: Boolean,
    modifier: Modifier,
    desktopArtworkConstrained: Boolean,
    presetMilkPath: String?,
    presetMilkData: String?,
    locked: Boolean,
    suspendRendering: Boolean = false,
) {
    if (!preset.isVisualizer ||
        !ProjectMRenderer.isNativeAvailable() ||
        !ProjectMHostGate.healthy
    ) {
        // Targets without a live host (or a failed native create) fall back to
        // artwork instead of a black stub surface.
        if (desktopArtworkConstrained) {
            // ArtworkImage only sizes via matchParentSize children, so wrapContentSize
            // collapses to 0×0 and the Now Playing slot looks like an empty rectangle.
            BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight)
                val artModifier = Modifier.size(side).aspectRatio(1f)
                if (track != null) {
                    TrackArtworkImage(track, artModifier, elevated = true)
                } else {
                    EmptyNowPlayingArtworkSlot(artModifier, glyphSp = 52.sp)
                }
            }
        } else if (track != null) {
            TrackArtworkImage(track, modifier.fillMaxSize(), elevated = true)
        } else {
            EmptyNowPlayingArtworkSlot(modifier.fillMaxSize(), glyphSp = 52.sp)
        }
        return
    }

    ProjectMVisualizerHost(
        presetPath = presetMilkPath,
        presetData = presetMilkData,
        locked = locked,
        isPlaying = isPlaying,
        modifier = modifier.fillMaxSize(),
        suspendRendering = suspendRendering,
    )
}

@Composable
fun VisualizerPresetButton(
    selected: NowPlayingVisualizerPreset,
    onSelected: (NowPlayingVisualizerPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    SuppressVisualizerHtmlOverlay(expanded)
    Box(modifier) {
        VisualizerIconButton(
            description = "Visualizer",
            onClick = { expanded = true },
            active = selected.isVisualizer,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            visualizerQuickChoices().forEach { preset ->
                val active = visualizerChoiceActive(preset, selected)
                DropdownMenuItem(
                    text = {
                        Text(
                            preset.label,
                            color = if (active) PhoebeUi.accentLight else PhoebeUi.primaryText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        PhoebeIconView(
                            if (preset is NowPlayingVisualizerPreset.Artwork) {
                                PhoebeIcon.Music
                            } else {
                                PhoebeIcon.Visualizer
                            },
                            tint = if (active) PhoebeUi.accentLight else PhoebeUi.secondaryText,
                            modifier = Modifier.size(17.dp),
                        )
                    },
                    onClick = {
                        onSelected(preset)
                        expanded = false
                    },
                )
            }
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
fun DesktopNowPlayingVisualizerView(
    track: Track?,
    preset: NowPlayingVisualizerPreset,
    isPlaying: Boolean,
    positionMs: Long,
    onPreset: (NowPlayingVisualizerPreset) -> Unit,
    modifier: Modifier = Modifier,
    presetMilkPath: String? = null,
    presetMilkData: String? = null,
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
            VisualizerPresetButton(
                selected = preset,
                onSelected = onPreset,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .border(
                    androidx.compose.foundation.BorderStroke(1.dp, PhoebeUi.border),
                    RoundedCornerShape(8.dp),
                ),
        ) {
            NowPlayingVisualizerSurface(
                preset = preset,
                track = track,
                isPlaying = isPlaying,
                positionMs = positionMs,
                modifier = Modifier.fillMaxSize(),
                desktopArtworkConstrained = true,
                presetMilkPath = presetMilkPath,
                presetMilkData = presetMilkData,
            )
        }
    }
}

internal fun visualizerQuickChoices(): List<NowPlayingVisualizerPreset> =
    buildList {
        add(NowPlayingVisualizerPreset.Artwork)
        add(NowPlayingVisualizerPreset.Shuffle())
        BundledVisualizerPacks.allPresets.forEach { ref ->
            add(
                NowPlayingVisualizerPreset.Pinned(
                    packId = ref.packId,
                    presetId = ref.presetId,
                    displayName = ref.displayName,
                ),
            )
        }
        VisualizerPacks.externalPresets().forEach { ref ->
            add(
                NowPlayingVisualizerPreset.Pinned(
                    packId = ref.packId,
                    presetId = ref.presetId,
                    displayName = ref.displayName,
                ),
            )
        }
    }

internal fun visualizerChoiceActive(
    choice: NowPlayingVisualizerPreset,
    selected: NowPlayingVisualizerPreset,
): Boolean = when {
    choice is NowPlayingVisualizerPreset.Artwork &&
        selected is NowPlayingVisualizerPreset.Artwork -> true
    choice is NowPlayingVisualizerPreset.Shuffle &&
        selected is NowPlayingVisualizerPreset.Shuffle &&
        choice.packId == selected.packId -> true
    choice is NowPlayingVisualizerPreset.Pinned &&
        selected is NowPlayingVisualizerPreset.Pinned &&
        choice.packId == selected.packId &&
        choice.presetId == selected.presetId -> true
    else -> false
}

@Composable
internal fun VisualizerPresetTileRow(
    selected: NowPlayingVisualizerPreset,
    onSelected: (NowPlayingVisualizerPreset) -> Unit,
    choices: List<NowPlayingVisualizerPreset> = visualizerQuickChoices(),
    compact: Boolean = false,
) {
    val rowSize = if (compact) 2 else 4
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.chunked(rowSize).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { preset ->
                    val active = visualizerChoiceActive(preset, selected)
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
                            if (preset is NowPlayingVisualizerPreset.Artwork) {
                                PhoebeIcon.Music
                            } else {
                                PhoebeIcon.Visualizer
                            },
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
