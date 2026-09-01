package com.phoebe.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.canAddToLocalPlaylist
import com.phoebe.app.domain.canAddToPlexPlaylist
import com.phoebe.app.domain.canTogglePlexLike
import com.phoebe.app.domain.isLocalPlaylist
import com.phoebe.app.domain.isRemoteLibraryTrack
import com.phoebe.app.domain.isRemoteProviderPlaylist
import kotlin.math.roundToInt

@Composable
fun TrackDownloadIndicator(
    track: Track,
    modifier: Modifier = Modifier,
    onDownload: (() -> Unit)? = null,
    showIdle: Boolean = true,
    touchTargetSize: Dp = 28.dp,
    showComplete: Boolean = true,
    showFailed: Boolean = true,
) {
    val downloads = LocalDownloadStatus.current
    val downloadActions = LocalDownloadActions.current
    val item = downloads.itemFor(track)
    var confirmDelete by remember(track.id) { mutableStateOf(false) }
    var confirmCancel by remember(track.id) { mutableStateOf(false) }
    val isActive = downloads.isActive(track)
    val isComplete = downloads.isComplete(track)
    val isFailed = downloads.isFailed(track)
    val showCompleteState = showComplete && isComplete
    val showFailedState = showFailed && isFailed
    val hasVisibleState = isActive || showCompleteState || showFailedState
    val showIdleAction = showIdle && onDownload != null
    if (!hasVisibleState && !showIdleAction) return
    val clickModifier = if (onDownload != null && (showIdleAction || hasVisibleState)) {
        Modifier
            .clip(CircleShape)
            .clickable {
                if (isActive) {
                    confirmCancel = true
                } else if (isComplete) {
                    confirmDelete = true
                } else {
                    onDownload()
                }
            }
    } else {
        Modifier
    }
    Box(modifier.size(touchTargetSize).then(clickModifier), contentAlignment = Alignment.Center) {
        when {
            isActive && item?.state?.name == "Queued" -> CircularProgressIndicator(
                color = PhoebeUi.accentLight,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
            isActive -> CircularProgressIndicator(
                progress = { item?.progress?.coerceIn(0f, 1f) ?: 0f },
                color = PhoebeUi.accentLight,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
            showCompleteState -> PhoebeIconView(
                PhoebeIcon.Check,
                tint = PhoebeUi.accentLight,
                modifier = Modifier.size(15.dp),
            )
            showFailedState -> PhoebeIconView(
                PhoebeIcon.Close,
                tint = PhoebeUi.accentLight,
                modifier = Modifier.size(14.dp),
            )
            showIdleAction -> PhoebeIconView(
                PhoebeIcon.Download,
                tint = PhoebeUi.mutedText.copy(alpha = 0.42f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
    if (confirmDelete) {
        ConfirmDeleteDownloadsDialog(
            title = "Delete Download?",
            body = "Remove the downloaded file for \"${track.title}\" from this device?",
            confirmLabel = "Delete",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                downloadActions.onDeleteDownloadedTracks(listOf(track))
                confirmDelete = false
            },
        )
    }
    if (confirmCancel) {
        ConfirmDeleteDownloadsDialog(
            title = "Cancel Download?",
            body = "Stop the current download and remove anything already downloaded for \"${track.title}\" from this device?",
            confirmLabel = "Cancel Download",
            onDismiss = { confirmCancel = false },
            onConfirm = {
                downloadActions.onCancelDownloadedTracks(listOf(track))
                confirmCancel = false
            },
        )
    }
}

@Composable
fun TrackActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAddToUpNext: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    track: Track? = null,
    onAddToEndOfQueue: (() -> Unit)? = null,
) {
    val actions = LocalPlaylistActions.current
    val likeActions = LocalLikeActions.current
    val ratingActions = LocalRatingActions.current
    val metadataEditorActions = LocalMetadataEditorActions.current
    val navigationActions = LocalTrackNavigationActions.current
    val downloads = LocalDownloadStatus.current
    val downloadActions = LocalDownloadActions.current
    val downloadItem = track?.let { downloads.itemFor(it) }
    val downloadActive = track?.let { downloads.isActive(it) } == true
    val downloadComplete = track?.let { downloads.isComplete(it) } == true
    val downloadFailed = track?.let { downloads.isFailed(it) } == true
    val downloadProgress = downloadItem?.progress?.coerceIn(0f, 1f) ?: 0f
    var confirmDeleteDownload by remember(track?.id) { mutableStateOf(false) }
    var confirmCancelDownload by remember(track?.id) { mutableStateOf(false) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (track != null) {
            DropdownMenuItem(
                text = { Text("Edit Metadata") },
                onClick = {
                    metadataEditorActions.onRequestEdit(track)
                    onDismiss()
                },
            )
        }
        if (onAddToUpNext != null) {
            DropdownMenuItem(
                text = { Text("Add to Up Next") },
                onClick = {
                    onAddToUpNext()
                    onDismiss()
                },
            )
        }
        if (onAddToEndOfQueue != null) {
            DropdownMenuItem(
                text = { Text("Add to End of Queue") },
                onClick = {
                    onAddToEndOfQueue()
                    onDismiss()
                },
            )
        }
        if (track != null) {
            DropdownMenuItem(
                text = { Text("Go to Song Detail") },
                onClick = {
                    navigationActions.onOpenSongDetail(track)
                    onDismiss()
                },
            )
            DropdownMenuItem(
                text = { Text("Go to Artist") },
                onClick = {
                    navigationActions.onOpenArtistForTrack(track)
                    onDismiss()
                },
            )
            DropdownMenuItem(
                text = { Text("Go to Album") },
                onClick = {
                    navigationActions.onOpenAlbumForTrack(track)
                    onDismiss()
                },
            )
            if (likeActions.likesEnabled && track.canTogglePlexLike()) {
                val liked = likeActions.isLiked(track)
                DropdownMenuItem(
                    text = { Text(if (liked) "Unlike Song" else "Like Song") },
                    onClick = {
                        likeActions.onToggleLiked(track)
                        onDismiss()
                    },
                )
            }
            if (ratingActions.ratingsEnabled && track.isRemoteLibraryTrack()) {
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Rate")
                            RatingStars(
                                rating = ratingActions.ratingFor(track),
                                enabled = true,
                                onRating = {
                                    ratingActions.onRateTrack(track, it)
                                    onDismiss()
                                },
                                starSize = 16.dp,
                                showClear = true,
                            )
                        }
                    },
                    onClick = {},
                )
            }
            AddToPlaylistMenuItems(
                track = track,
                actions = actions,
                onAfter = onDismiss,
            )
        }
        if (onDownload != null && track != null) {
            DropdownMenuItem(
                text = {
                    if (downloadActive) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.size(16.dp),
                                color = PhoebeUi.accentLight,
                                strokeWidth = 2.dp,
                            )
                            Text(if (downloadItem?.state?.name == "Queued") "Queued" else "Downloading")
                            Text(downloadPercentLabel(downloadProgress), color = PhoebeUi.mutedText)
                        }
                    } else {
                        Text(
                            when {
                                downloadComplete -> "Delete Download"
                                downloadFailed -> "Retry Download"
                                else -> "Download Song"
                            },
                        )
                    }
                },
                onClick = {
                    when {
                        downloadActive -> confirmCancelDownload = true
                        downloadComplete -> confirmDeleteDownload = true
                        else -> {
                            onDownload()
                            onDismiss()
                        }
                    }
                },
            )
        }
    }
    if (confirmCancelDownload && track != null) {
        ConfirmDeleteDownloadsDialog(
            title = "Cancel Download?",
            body = "Stop the current download and remove anything already downloaded for \"${track.title}\" from this device?",
            confirmLabel = "Cancel Download",
            onDismiss = {
                confirmCancelDownload = false
                onDismiss()
            },
            onConfirm = {
                downloadActions.onCancelDownloadedTracks(listOf(track))
                confirmCancelDownload = false
                onDismiss()
            },
        )
    }
    if (confirmDeleteDownload && track != null) {
        ConfirmDeleteDownloadsDialog(
            title = "Delete Download?",
            body = "Remove the downloaded file for \"${track.title}\" from this device?",
            confirmLabel = "Delete",
            onDismiss = {
                confirmDeleteDownload = false
                onDismiss()
            },
            onConfirm = {
                downloadActions.onDeleteDownloadedTracks(listOf(track))
                confirmDeleteDownload = false
                onDismiss()
            },
        )
    }
}

@Composable
fun ConfirmDeleteDownloadsDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .widthIn(min = 300.dp, max = 420.dp)
                .shadow(elevation = 28.dp, shape = RoundedCornerShape(18.dp), clip = false)
                .clip(RoundedCornerShape(18.dp))
                .background(PhoebeUi.modalSurface)
                .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(18.dp))
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, color = PhoebeUi.primaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(body, color = PhoebeUi.secondaryText, fontSize = 13.sp, lineHeight = 18.sp)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = PhoebeUi.secondaryText)
                }
                TextButton(onClick = onConfirm) {
                    Text(confirmLabel, color = PhoebeUi.accentLight, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun LikeButton(
    liked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PhoebeIconView(
            PhoebeIcon.Heart,
            tint = when {
                liked -> PhoebeUi.accentLight
                enabled -> PhoebeUi.secondaryText
                else -> PhoebeUi.mutedText.copy(alpha = 0.35f)
            },
            modifier = Modifier.size(17.dp),
            filled = liked,
        )
    }
}

@Composable
fun TrackStateBadges(
    liked: Boolean,
    downloaded: Boolean,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    if (!liked && !downloaded) return
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (liked) {
            PhoebeIconView(
                PhoebeIcon.Heart,
                tint = PhoebeUi.accentLight,
                modifier = Modifier.size(iconSize),
                filled = true,
            )
        }
        if (downloaded) {
            PhoebeIconView(
                PhoebeIcon.Check,
                tint = PhoebeUi.mutedText,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
fun AddToPlaylistMenuItems(
    track: Track,
    actions: PlaylistActions = LocalPlaylistActions.current,
    onAfter: () -> Unit = {},
    startExpanded: Boolean = false,
) {
    if (!actions.playlistsEnabled) return
    val isLocal = track.canAddToLocalPlaylist()
    val isPlex = track.canAddToPlexPlaylist()
    if (!isLocal && !isPlex) return
    val eligiblePlaylists = actions.playlists.filter { playlist ->
        when {
            playlist.isLocalPlaylist() -> isLocal
            playlist.isRemoteProviderPlaylist() -> isPlex
            else -> false
        }
    }
    var showPlaylistPicker by remember(track.id, startExpanded) { mutableStateOf(startExpanded) }
    if (!showPlaylistPicker) {
        DropdownMenuItem(
            text = {
                Row(
                    modifier = Modifier.widthIn(min = 220.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Add to Playlist", modifier = Modifier.weight(1f))
                    PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(14.dp))
                }
            },
            onClick = { showPlaylistPicker = true },
        )
    } else {
        AddToPlaylistBackMenuItem(onClick = { showPlaylistPicker = false })
        DropdownMenuItem(
            text = { Text("New playlist...", color = PhoebeUi.accentLight, fontWeight = FontWeight.SemiBold) },
            onClick = {
                actions.onRequestCreatePlaylist(listOf(track))
                onAfter()
            },
        )
        if (eligiblePlaylists.isNotEmpty()) {
            eligiblePlaylists.forEach { playlist: Playlist ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${playlist.trackCount} songs",
                                color = PhoebeUi.mutedText,
                                fontSize = 11.sp,
                            )
                        }
                    },
                    onClick = {
                        actions.onAddTrackToPlaylist(playlist, track, false)
                        onAfter()
                    },
                )
            }
        } else {
            DropdownMenuItem(
                text = { Text("No playlists yet", color = PhoebeUi.mutedText) },
                onClick = {},
                enabled = false,
            )
        }
    }
}

@Composable
private fun AddToPlaylistBackMenuItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.widthIn(min = 220.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhoebeIconView(PhoebeIcon.Back, tint = PhoebeUi.mutedText, modifier = Modifier.size(14.dp))
                Text("Add to Playlist", color = PhoebeUi.primaryText, fontWeight = FontWeight.SemiBold)
            }
        },
        onClick = onClick,
    )
}

private fun downloadPercentLabel(progress: Float): String =
    "${(progress.coerceIn(0f, 1f) * 100f).roundToInt()}%"
