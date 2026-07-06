package com.phoebe.app.feature.playback

import com.phoebe.app.ui.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.phoebe.app.data.ListenBrainzFeedbackScore
import com.phoebe.app.data.ListenBrainzFeedbackTarget
import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.canTogglePlexLike
import com.phoebe.app.feature.playback.EqualizerDialog
import com.phoebe.app.player.CastState
import com.phoebe.app.platform.isDesktopPlatform

@Composable
fun DesktopTransport(
    track: Track?,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    positionMs: Long,
    bufferedPositionMs: Long,
    shuffle: Boolean,
    repeat: RepeatMode,
    volume: Float,
    castState: CastState = CastState(),
    remotePlaybackTarget: String? = null,
    listenBrainzFeedbackTarget: ListenBrainzFeedbackTarget = ListenBrainzFeedbackTarget(),
    equalizerProfile: EqualizerProfile = EqualizerProfile.Default,
    persistEqualizerSettings: Boolean = false,
    equalizerRemoteUnavailable: Boolean = false,
    visualizerPreset: NowPlayingVisualizerPreset = NowPlayingVisualizerPreset.Default,
    showVisualizerInTvFrame: Boolean = false,
    compact: Boolean,
    lyricsVisible: Boolean = false,
    upNextVisible: Boolean,
    upNextToggleEnabled: Boolean,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onVolume: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onLyrics: () -> Unit,
    onUltimateGuitar: (Track) -> Unit = {},
    showUltimateGuitarButton: Boolean = true,
    onEqualizerEnabled: (Boolean) -> Unit = {},
    onEqualizerBandCount: (Int) -> Unit = {},
    onEqualizerGain: (Int, Float) -> Unit = { _, _ -> },
    onEqualizerReset: () -> Unit = {},
    onPersistEqualizerSettings: (Boolean) -> Unit = {},
    onVisualizerPreset: (NowPlayingVisualizerPreset) -> Unit = {},
    onShowVisualizerInTvFrame: (Boolean) -> Unit = {},
    onListenBrainzFeedback: (ListenBrainzFeedbackScore) -> Unit = {},
    onToggleUpNext: () -> Unit,
    onCast: () -> Unit,
) {
    val hasTrack = track != null
    val timelineBufferedPositionMs = rememberTimelineBufferedPositionMs(
        track = track,
        positionMs = positionMs,
        bufferedPositionMs = bufferedPositionMs,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
    )
    val likeActions = LocalLikeActions.current
    val trackNavigationActions = LocalTrackNavigationActions.current
    val canLike = track != null && (track.id.startsWith("radio:") || (likeActions.likesEnabled && track.canTogglePlexLike()))
    val liked = track != null && likeActions.isLiked(track)
    val showListenBrainzFeedback = track != null &&
        !track.id.startsWith("radio:") &&
        listenBrainzFeedbackTarget.enabled &&
        listenBrainzFeedbackTarget.trackId == track.id
    val showCastControls = !isDesktopPlatform() || castState.isAvailable || castState.isConnected
    var equalizerOpen by remember { mutableStateOf(false) }
    var transportOptionsOpen by remember { mutableStateOf(false) }
    if (equalizerOpen) {
        EqualizerDialog(
            profile = equalizerProfile,
            persistEnabled = persistEqualizerSettings,
            remoteUnavailable = equalizerRemoteUnavailable,
            onEnabledChange = onEqualizerEnabled,
            onBandCountChange = onEqualizerBandCount,
            onGainChange = onEqualizerGain,
            onReset = onEqualizerReset,
            onPersistChange = onPersistEqualizerSettings,
            onDismiss = { equalizerOpen = false },
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val ultimateGuitarTrack = track?.takeIf { showUltimateGuitarButton && it.title.isNotBlank() }
        val overflowSecondaryControls = maxWidth < 960.dp
        val denseTransport = maxWidth < 760.dp
        val horizontalPadding = when {
            denseTransport -> 16.dp
            overflowSecondaryControls -> 18.dp
            compact -> 20.dp
            else -> 24.dp
        }
        val artworkSize = if (denseTransport) 52.dp else 56.dp
        val titleWidth = when {
            denseTransport -> 122.dp
            overflowSecondaryControls -> 132.dp
            compact -> 156.dp
            else -> 190.dp
        }
        val transportGap = when {
            denseTransport -> 8.dp
            overflowSecondaryControls -> 14.dp
            compact -> 18.dp
            else -> 24.dp
        }
        val transportWidthCap = when {
            overflowSecondaryControls -> 360.dp
            compact -> 320.dp
            else -> 640.dp
        }
        val inlineVolume = !denseTransport
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(124.dp)
                .background(PhoebeUi.panel)
                .border(BorderStroke(1.dp, PhoebeUi.border))
                .padding(horizontal = horizontalPadding, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        if (track != null) {
            BottomTransportArtworkShadow(
                modifier = Modifier
                    .size(artworkSize)
                    .phoebeClickable { trackNavigationActions.onOpenAlbumForTrack(track) },
            ) {
                TrackArtworkImage(
                    track,
                    Modifier.fillMaxSize(),
                    elevated = false,
                )
            }
        } else {
            BottomTransportArtworkShadow(Modifier.size(artworkSize)) {
                EmptyNowPlayingArtworkSlot(
                    Modifier.fillMaxSize(),
                    glyphSp = 20.sp,
                    shadowElevation = 0.dp,
                )
            }
        }
        Spacer(Modifier.width(if (denseTransport) 10.dp else 12.dp))
        Column(Modifier.width(titleWidth)) {
            Text(
                track?.title ?: "Nothing playing",
                color = PhoebeUi.primaryText,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track?.artist?.takeIf { it.isNotBlank() } ?: "Pick a track to begin",
                color = if (hasTrack) PhoebeUi.secondaryText else PhoebeUi.mutedText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.phoebeClickable(
                    enabled = track != null && track.artist.isNotBlank(),
                ) {
                    track?.let { trackNavigationActions.onOpenArtistForTrack(it) }
                },
            )
            Text(
                track?.album?.takeIf { it.isNotBlank() } ?: "",
                color = PhoebeUi.mutedText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.phoebeClickable(
                    enabled = track != null && track.album.isNotBlank(),
                ) {
                    track?.let { trackNavigationActions.onOpenAlbumForTrack(it) }
                },
            )
            if (!overflowSecondaryControls) {
                AudioQualityText(
                    track = track,
                    compact = true,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        LikeButton(
            liked = liked,
            enabled = canLike,
            onClick = { track?.let(likeActions.onToggleLiked) },
            modifier = Modifier.size(34.dp),
        )
        if (showListenBrainzFeedback) {
            Spacer(Modifier.width(8.dp))
            ListenBrainzFeedbackControls(
                target = listenBrainzFeedbackTarget,
                onFeedback = onListenBrainzFeedback,
                stackedVotes = true,
            )
        }
        Spacer(Modifier.width(if (overflowSecondaryControls || compact) 10.dp else 24.dp))
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val transportWidth = maxWidth.coerceAtMost(transportWidthCap)
            Column(
                modifier = Modifier.width(transportWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(transportGap),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(48.dp),
                ) {
                    ShuffleIcon(active = shuffle, onClick = onShuffle)
                    TransportIcon(PhoebeIcon.Previous, "Previous Track", onPrevious, iconSize = 16.dp)
                    PlayButton(isPlaying, isBuffering, 48.dp, onToggle, enabled = hasTrack)
                    TransportIcon(PhoebeIcon.Next, "Next Track", onNext, iconSize = 16.dp)
                    RepeatIcon(mode = repeat, onClick = onRepeat)
                }
                ProgressLine(
                    positionMs = positionMs,
                    bufferedPositionMs = timelineBufferedPositionMs,
                    durationMs = track?.durationMs ?: 0L,
                    waveformSeed = track?.let(::trackWaveformSeed) ?: "",
                    modifier = Modifier.fillMaxWidth(),
                    onSeek = if (hasTrack) onSeek else null,
                    barHeight = 20.dp,
                    labelFontSize = 11.sp,
                    labelSpacing = 2.dp,
                    maxBarSlots = when {
                        denseTransport -> 88
                        overflowSecondaryControls || compact -> 140
                        else -> 220
                    },
                )
            }
        }
        Spacer(Modifier.width(if (overflowSecondaryControls || compact) 10.dp else 24.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (overflowSecondaryControls) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(44.dp),
        ) {
            if (!overflowSecondaryControls || inlineVolume) {
                Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                    PhoebeIconView(PhoebeIcon.Volume, tint = PhoebeUi.secondaryText, modifier = Modifier.size(20.dp))
                }
                Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                    VolumeSlider(
                        volume,
                        onVolume,
                        Modifier.width(
                            when {
                                overflowSecondaryControls -> 72.dp
                                compact -> 84.dp
                                else -> 112.dp
                            },
                        ),
                    )
                }
            }
            if (overflowSecondaryControls) {
                if (ultimateGuitarTrack != null) {
                    TransportIcon(
                        PhoebeIcon.Guitar,
                        "Open Ultimate Guitar",
                        { onUltimateGuitar(ultimateGuitarTrack) },
                    )
                }
                Box {
                    TransportIcon(
                        PhoebeIcon.More,
                        "More playback options",
                        { transportOptionsOpen = true },
                        active = (showCastControls && castState.isConnected) ||
                            equalizerProfile.enabled ||
                            visualizerPreset.isVisualizer ||
                            showVisualizerInTvFrame ||
                            lyricsVisible ||
                            upNextVisible,
                    )
                    PlaybackOptionsMenu(
                        expanded = transportOptionsOpen,
                        includeVolume = !inlineVolume,
                        showCastControls = showCastControls,
                        volume = volume,
                        castState = castState,
                        equalizerEnabled = equalizerProfile.enabled,
                        visualizerPreset = visualizerPreset,
                        showVisualizerInTvFrame = showVisualizerInTvFrame,
                        lyricsVisible = lyricsVisible,
                        upNextVisible = upNextVisible,
                        upNextToggleEnabled = upNextToggleEnabled,
                        ultimateGuitarTrack = ultimateGuitarTrack,
                        onDismiss = { transportOptionsOpen = false },
                        onVolume = onVolume,
                        onCast = onCast,
                        onEqualizer = { equalizerOpen = true },
                        onVisualizerPreset = onVisualizerPreset,
                        onShowVisualizerInTvFrame = onShowVisualizerInTvFrame,
                        onLyrics = onLyrics,
                        onUltimateGuitar = onUltimateGuitar,
                        onToggleUpNext = onToggleUpNext,
                    )
                }
            } else {
                if (showCastControls) {
                    CastIcon(
                        active = castState.isConnected,
                        loading = castState.isBuffering,
                        enabled = castState.isAvailable || castState.isConnected,
                        onClick = onCast,
                    )
                }
                TransportIcon(
                    PhoebeIcon.Equalizer,
                    "Equalizer",
                    { equalizerOpen = true },
                    active = equalizerProfile.enabled,
                )
                VisualizerPresetButton(
                    selected = visualizerPreset,
                    onSelected = onVisualizerPreset,
                    showInTvFrame = showVisualizerInTvFrame,
                    onShowInTvFrameChange = onShowVisualizerInTvFrame,
                )
                TransportIcon(
                    PhoebeIcon.Lyrics,
                    if (lyricsVisible) "Hide Lyrics" else "Show Lyrics",
                    onLyrics,
                    active = lyricsVisible,
                )
                if (ultimateGuitarTrack != null) {
                    TransportIcon(
                        PhoebeIcon.Guitar,
                        "Open Ultimate Guitar",
                        { onUltimateGuitar(ultimateGuitarTrack) },
                    )
                }
                UpNextToggleIcon(
                    visible = upNextVisible,
                    enabled = upNextToggleEnabled,
                    onClick = onToggleUpNext,
                )
            }
        }
        }
    }
}

@Composable
fun ListenBrainzFeedbackControls(
    target: ListenBrainzFeedbackTarget,
    onFeedback: (ListenBrainzFeedbackScore) -> Unit,
    modifier: Modifier = Modifier,
    stackedVotes: Boolean = false,
    horizontalVotes: Boolean = false,
    showVoteBorders: Boolean = true,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(
            when {
                horizontalVotes -> 8.dp
                stackedVotes -> 6.dp
                else -> 4.dp
            },
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val loveActive = target.score == ListenBrainzFeedbackScore.Love
        val hateActive = target.score == ListenBrainzFeedbackScore.Hate
        val resolvingTarget = target.enabled && target.recordingMsid.isNullOrBlank()
        val resolvingFeedback = resolvingTarget || target.loadingScore
        val canSubmitFeedback = target.available && !target.loadingScore && target.submittingScore == null
        if (stackedVotes) {
            ListenBrainzFeedbackVoteStack(
                target = target,
                resolvingFeedback = resolvingFeedback,
                canSubmitFeedback = canSubmitFeedback,
                onFeedback = onFeedback,
            )
        } else if (horizontalVotes) {
            ListenBrainzFeedbackVoteButton(
                icon = PhoebeIcon.ThumbsUp,
                label = "Love on ListenBrainz",
                active = loveActive,
                loading = resolvingFeedback || target.submittingScore == ListenBrainzFeedbackScore.Love,
                enabled = canSubmitFeedback,
                onClick = { onFeedback(if (loveActive) ListenBrainzFeedbackScore.Clear else ListenBrainzFeedbackScore.Love) },
                modifier = Modifier.size(width = 46.dp, height = 40.dp),
                iconSize = 20.dp,
                showBorder = showVoteBorders,
            )
            ListenBrainzFeedbackVoteButton(
                icon = PhoebeIcon.ThumbsDown,
                label = "Hate on ListenBrainz",
                active = hateActive,
                loading = resolvingFeedback || target.submittingScore == ListenBrainzFeedbackScore.Hate,
                enabled = canSubmitFeedback,
                onClick = { onFeedback(if (hateActive) ListenBrainzFeedbackScore.Clear else ListenBrainzFeedbackScore.Hate) },
                modifier = Modifier.size(width = 46.dp, height = 40.dp),
                iconSize = 20.dp,
                showBorder = showVoteBorders,
            )
        } else {
            ListenBrainzFeedbackButton(
                icon = PhoebeIcon.Heart,
                label = "Love on ListenBrainz",
                active = loveActive,
                loading = resolvingFeedback || target.submittingScore == ListenBrainzFeedbackScore.Love,
                enabled = canSubmitFeedback,
                onClick = { onFeedback(if (loveActive) ListenBrainzFeedbackScore.Clear else ListenBrainzFeedbackScore.Love) },
            )
            ListenBrainzFeedbackTextButton(
                label = "Hate",
                active = hateActive,
                loading = resolvingFeedback || target.submittingScore == ListenBrainzFeedbackScore.Hate,
                enabled = canSubmitFeedback,
                onClick = { onFeedback(if (hateActive) ListenBrainzFeedbackScore.Clear else ListenBrainzFeedbackScore.Hate) },
            )
        }
    }
}

@Composable
private fun ListenBrainzFeedbackVoteStack(
    target: ListenBrainzFeedbackTarget,
    resolvingFeedback: Boolean,
    canSubmitFeedback: Boolean,
    onFeedback: (ListenBrainzFeedbackScore) -> Unit,
) {
    val loveActive = target.score == ListenBrainzFeedbackScore.Love
    val hateActive = target.score == ListenBrainzFeedbackScore.Hate
    Column(
        modifier = Modifier
            .width(42.dp)
            .height(60.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ListenBrainzFeedbackVoteButton(
            icon = PhoebeIcon.ThumbsUp,
            label = "Love on ListenBrainz",
            active = loveActive,
            loading = resolvingFeedback || target.submittingScore == ListenBrainzFeedbackScore.Love,
            enabled = canSubmitFeedback,
            onClick = { onFeedback(if (loveActive) ListenBrainzFeedbackScore.Clear else ListenBrainzFeedbackScore.Love) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            iconSize = 20.dp,
            contentPadding = 2.dp,
        )
        ListenBrainzFeedbackVoteButton(
            icon = PhoebeIcon.ThumbsDown,
            label = "Hate on ListenBrainz",
            active = hateActive,
            loading = resolvingFeedback || target.submittingScore == ListenBrainzFeedbackScore.Hate,
            enabled = canSubmitFeedback,
            onClick = { onFeedback(if (hateActive) ListenBrainzFeedbackScore.Clear else ListenBrainzFeedbackScore.Hate) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            iconSize = 20.dp,
            contentPadding = 2.dp,
        )
    }
}

@Composable
private fun ListenBrainzFeedbackVoteButton(
    icon: PhoebeIcon,
    label: String,
    active: Boolean,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
    contentPadding: Dp = 0.dp,
    showBorder: Boolean = true,
) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (active) PhoebeUi.accent.copy(alpha = 0.18f) else Color.Transparent)
            .then(
                if (showBorder) {
                    Modifier.border(
                        BorderStroke(1.dp, if (active) PhoebeUi.accent.copy(alpha = 0.28f) else PhoebeUi.border.copy(alpha = 0.45f)),
                        shape,
                    )
                } else {
                    Modifier
                },
            )
            .phoebeClickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            PhoebeLoadingBorder(
                modifier = Modifier.matchParentSize(),
                radius = 7.dp,
                strokeWidth = 1.5.dp,
                label = "listenbrainz-feedback-loading",
            )
        }
        PhoebeIconView(
            icon,
            tint = if (active) PhoebeUi.accentLight else PhoebeUi.secondaryText,
            modifier = Modifier.size(iconSize).padding(contentPadding),
        )
    }
}

@Composable
private fun ListenBrainzFeedbackButton(
    icon: PhoebeIcon,
    label: String,
    active: Boolean,
    loading: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (active) PhoebeUi.accent.copy(alpha = 0.18f) else Color.Transparent)
            .phoebeClickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            PhoebeLoadingBorder(
                modifier = Modifier.matchParentSize(),
                radius = 999.dp,
                strokeWidth = 1.5.dp,
                label = "listenbrainz-feedback-loading",
            )
        }
        PhoebeIconView(
            icon,
            tint = if (active) PhoebeUi.accentLight else PhoebeUi.secondaryText,
            modifier = Modifier.size(17.dp),
            filled = active,
        )
    }
}

@Composable
private fun ListenBrainzFeedbackTextButton(
    label: String,
    active: Boolean,
    loading: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .height(34.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) PhoebeUi.accent.copy(alpha = 0.18f) else Color.Transparent)
            .border(
                BorderStroke(1.dp, if (active) PhoebeUi.accent.copy(alpha = 0.28f) else PhoebeUi.border.copy(alpha = 0.55f)),
                RoundedCornerShape(999.dp),
            )
            .phoebeClickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "$label on ListenBrainz" }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            PhoebeLoadingBorder(
                modifier = Modifier.matchParentSize(),
                radius = 999.dp,
                strokeWidth = 1.5.dp,
                label = "listenbrainz-feedback-loading",
            )
        }
        Text(
            label,
            color = if (active) PhoebeUi.accentLight else PhoebeUi.secondaryText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun PlaybackOptionsMenu(
    expanded: Boolean,
    includeVolume: Boolean,
    showCastControls: Boolean,
    volume: Float,
    castState: CastState,
    equalizerEnabled: Boolean,
    visualizerPreset: NowPlayingVisualizerPreset,
    showVisualizerInTvFrame: Boolean,
    lyricsVisible: Boolean,
    upNextVisible: Boolean,
    upNextToggleEnabled: Boolean,
    ultimateGuitarTrack: Track?,
    onDismiss: () -> Unit,
    onVolume: (Float) -> Unit,
    onCast: () -> Unit,
    onEqualizer: () -> Unit,
    onVisualizerPreset: (NowPlayingVisualizerPreset) -> Unit,
    onShowVisualizerInTvFrame: (Boolean) -> Unit,
    onLyrics: () -> Unit,
    onUltimateGuitar: (Track) -> Unit,
    onToggleUpNext: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (includeVolume) {
            Row(
                modifier = Modifier
                    .width(224.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PhoebeIconView(PhoebeIcon.Volume, tint = PhoebeUi.secondaryText, modifier = Modifier.size(18.dp))
                VolumeSlider(volume, onVolume, Modifier.weight(1f))
            }
        }
        if (showCastControls) {
            PlaybackOptionsMenuItem(
                icon = PhoebeIcon.Cast,
                text = when {
                    castState.isBuffering -> "Connecting to Chromecast"
                    castState.isConnected -> "Casting"
                    castState.isAvailable -> "Cast"
                    else -> "Cast unavailable"
                },
                active = castState.isConnected || castState.isBuffering,
                enabled = (castState.isAvailable || castState.isConnected) && !castState.isBuffering,
                onClick = {
                    onCast()
                    onDismiss()
                },
            )
        }
        PlaybackOptionsMenuItem(
            icon = PhoebeIcon.Equalizer,
            text = "Equalizer",
            active = equalizerEnabled,
            onClick = {
                onEqualizer()
                onDismiss()
            },
        )
        PlaybackOptionsMenuItem(
            icon = if (showVisualizerInTvFrame) PhoebeIcon.Check else PhoebeIcon.Visualizer,
            text = "Show In TV",
            active = showVisualizerInTvFrame,
            onClick = {
                onShowVisualizerInTvFrame(!showVisualizerInTvFrame)
                onDismiss()
            },
        )
        NowPlayingVisualizerPreset.entries.forEach { preset ->
            PlaybackOptionsMenuItem(
                icon = if (preset == NowPlayingVisualizerPreset.Artwork) PhoebeIcon.Music else PhoebeIcon.Visualizer,
                text = preset.label,
                active = preset == visualizerPreset,
                onClick = {
                    onVisualizerPreset(preset)
                    onDismiss()
                },
            )
        }
        PlaybackOptionsMenuItem(
            icon = PhoebeIcon.Lyrics,
            text = if (lyricsVisible) "Hide Lyrics" else "Show Lyrics",
            active = lyricsVisible,
            onClick = {
                onLyrics()
                onDismiss()
            },
        )
        if (ultimateGuitarTrack != null) {
            PlaybackOptionsMenuItem(
                icon = PhoebeIcon.Guitar,
                text = "Open Ultimate Guitar",
                active = false,
                onClick = {
                    onUltimateGuitar(ultimateGuitarTrack)
                    onDismiss()
                },
            )
        }
        PlaybackOptionsMenuItem(
            icon = PhoebeIcon.Queue,
            text = if (upNextVisible) "Hide Up Next" else "Show Up Next",
            active = upNextVisible,
            enabled = upNextToggleEnabled,
            onClick = {
                onToggleUpNext()
                onDismiss()
            },
        )
    }
}

@Composable
private fun PlaybackOptionsMenuItem(
    icon: PhoebeIcon,
    text: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text,
                color = if (enabled) PhoebeUi.primaryText else PhoebeUi.mutedText,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            )
        },
        leadingIcon = {
            PhoebeIconView(
                icon,
                tint = when {
                    !enabled -> PhoebeUi.mutedText.copy(alpha = 0.45f)
                    active -> PhoebeUi.accentLight
                    else -> PhoebeUi.secondaryText
                },
                modifier = Modifier.size(18.dp),
            )
        },
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun BottomTransportArtworkShadow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val shadowColor = Color.Black.copy(alpha = 0.24f)
    Box(
        modifier.shadow(
            elevation = 12.dp,
            shape = shape,
            clip = false,
            ambientColor = shadowColor,
            spotColor = shadowColor,
        ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun ShuffleIcon(active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) PhoebeUi.accent.copy(alpha = 0.16f) else Color.Transparent)
            .phoebeClickable(onClick = onClick)
            .semantics { contentDescription = if (active) "Shuffle on" else "Shuffle off" },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (active) PhoebeUi.accentLight else PhoebeUi.primaryText
        PhoebeIconView(PhoebeIcon.InterwovenArrows, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun RepeatIcon(mode: RepeatMode, onClick: () -> Unit) {
    val active = mode != RepeatMode.Off
    val label = when (mode) {
        RepeatMode.Off -> "Repeat off"
        RepeatMode.One -> "Repeat one"
        RepeatMode.All -> "Repeat all"
    }
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) PhoebeUi.accent.copy(alpha = 0.16f) else Color.Transparent)
            .phoebeClickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when (mode) {
            RepeatMode.Off -> PhoebeIconView(PhoebeIcon.Repeat, tint = PhoebeUi.primaryText, modifier = Modifier.size(20.dp))
            RepeatMode.One -> Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(PhoebeUi.accentLight)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "1",
                    color = PhoebeUi.canvasBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            RepeatMode.All -> Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(PhoebeUi.accentLight)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "All",
                    color = PhoebeUi.canvasBackground,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.02.em,
                )
            }
        }
    }
}

@Composable
fun UpNextToggleIcon(visible: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = when {
        !enabled -> PhoebeUi.mutedText.copy(alpha = 0.35f)
        visible -> PhoebeUi.accentLight
        else -> PhoebeUi.secondaryText
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (visible) PhoebeUi.accent.copy(alpha = 0.16f) else Color.Transparent)
            .let { if (enabled) it.phoebeClickable(onClick = onClick) else it }
            .semantics { contentDescription = if (visible) "Hide Up Next" else "Show Up Next" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            val h = size.height
            val stroke = h * 0.10f
            val barHeight = stroke
            val y1 = h * 0.28f
            val y2 = h * 0.50f
            val y3 = h * 0.72f
            val barColor = tint
            drawRect(color = barColor, topLeft = Offset(0f, y1), size = androidx.compose.ui.geometry.Size(w, barHeight))
            drawRect(color = barColor, topLeft = Offset(0f, y2), size = androidx.compose.ui.geometry.Size(w * 0.78f, barHeight))
            drawRect(color = barColor, topLeft = Offset(0f, y3), size = androidx.compose.ui.geometry.Size(w * 0.55f, barHeight))
        }
    }
}

@Composable
fun CastIcon(
    active: Boolean,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strokeColor = when {
        loading -> PhoebeUi.accentLight
        active -> PhoebeUi.accentLight
        enabled -> PhoebeUi.secondaryText
        else -> PhoebeUi.mutedText.copy(alpha = 0.45f)
    }
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active || loading) PhoebeUi.accent.copy(alpha = 0.14f) else Color.Transparent)
            .phoebeClickable(enabled = enabled && !loading, onClick = onClick)
            .semantics {
                contentDescription = when {
                    loading -> "Connecting to Chromecast"
                    active -> "Casting"
                    enabled -> "Cast"
                    else -> "Cast unavailable"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = PhoebeUi.accentLight,
                strokeWidth = 2.dp,
            )
            return@Box
        }
        PhoebeIconView(PhoebeIcon.Cast, tint = strokeColor, modifier = Modifier.size(20.dp))
    }
}
