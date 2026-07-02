package com.phoebe.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import phoebe.composeapp.generated.resources.Res
import phoebe.composeapp.generated.resources.phoebe_bird
import phoebe.composeapp.generated.resources.phoebe_icon_rounded
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import com.phoebe.app.AppState
import com.phoebe.app.data.catalogAlbumsForArtist
import com.phoebe.app.data.catalogTracksForArtist
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.AppScreen
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.LIKED_SONGS_PLAYLIST_TITLE
import com.phoebe.app.domain.PENDING_LIKED_SONGS_PLAYLIST_ID
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.LocalFolderMediaSourceConfig
import com.phoebe.app.domain.MediaSourcesState
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.displayPath
import com.phoebe.app.domain.isLocalMediaPlayback
import com.phoebe.app.domain.isLikedSongsPlaylist
import com.phoebe.app.domain.isRemoteLibraryTrack
import com.phoebe.app.domain.providerLabel
import com.phoebe.app.domain.supportsPlexPlaylists
import com.phoebe.app.platform.createPlatformHttpClient
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.platform.prefersReducedArtworkEffects
import com.phoebe.app.updates.AppUpdateState
import kotlinx.coroutines.delay
import com.phoebe.app.sources.rememberPickLocalFolder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.yield
import kotlin.math.max

@Composable
internal fun Sidebar(
    catalog: CatalogSnapshot,
    catalogRefreshing: Boolean,
    session: PlexSession?,
    mediaSources: MediaSourcesState,
    activeSection: BrowseSection,
    selectedPlaylistId: String?,
    onNavigate: (BrowseSection) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onOpenSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onAddLocalFolder: (String?) -> Unit,
    onRemoveLocalFolder: (String) -> Unit,
    onToggleLocalFolder: (String, Boolean) -> Unit,
    onRefreshLibrary: () -> Unit,
    tintedBackgroundGradient: Boolean,
    appUpdateState: AppUpdateState = AppUpdateState.Idle,
    onInstallUpdate: () -> Unit = {},
) {
    val pickLocalFolder = rememberPickLocalFolder(onPicked = onAddLocalFolder)
    val playlistActions = LocalPlaylistActions.current
    val remoteSignedIn = session?.token?.isNotBlank() == true
    var profileExpanded by remember(remoteSignedIn) { mutableStateOf(!remoteSignedIn) }
    val mainNavEnabled = canBrowseMainSections(session, mediaSources)
    val providerName = session.providerLabel()
    val remoteSourceLabel = if (remoteSignedIn) "$providerName — streaming library" else "Streaming provider — Plex or Jellyfin"
    val catalogSyncInProgress = LocalCatalogSyncInProgress.current
    val showPlaylistRefreshBar = remoteSignedIn && catalogSyncInProgress
    val availableUpdate = when (val updateState = appUpdateState) {
        is AppUpdateState.Available -> updateState.update
        is AppUpdateState.Installing -> updateState.update
        is AppUpdateState.Failed -> updateState.lastKnownUpdate
        else -> null
    }
    val installingUpdateState = appUpdateState as? AppUpdateState.Installing
    val updateInstalling = installingUpdateState != null
    val likedSongsPlaylist = playlistActions.playlists.firstOrNull { it.isLikedSongsPlaylist() }
        ?: if (playlistActions.playlistsEnabled) {
            Playlist(id = PENDING_LIKED_SONGS_PLAYLIST_ID, title = LIKED_SONGS_PLAYLIST_TITLE, trackCount = 0)
        } else {
            null
        }
    val regularPlaylists = playlistActions.playlists.filterNot { it.isLikedSongsPlaylist() }

    val sidebarTopPadding = if (LocalDesktopMergesTitleBar.current) 16.dp else 54.dp
    Column(
        modifier = Modifier
            .width(236.dp)
            .fillMaxHeight()
            .then(if (tintedBackgroundGradient) Modifier else Modifier.background(PhoebeUi.sidebar))
            .padding(start = 14.dp, top = sidebarTopPadding, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BrandMark(size = 28.dp)
            Text("Phoebe", color = PhoebeUi.primaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (availableUpdate != null) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(PhoebeUpdateBlue.copy(alpha = if (updateInstalling) 0.10f else 0.16f))
                        .phoebeClickable(enabled = !updateInstalling, onClick = onInstallUpdate),
                    contentAlignment = Alignment.Center,
                ) {
                    if (installingUpdateState != null) {
                        UpdateProgressRing(
                            progress = installingUpdateState.progress,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        PhoebeIconView(PhoebeIcon.Update, tint = PhoebeUpdateBlue, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NavRow(
                PhoebeIcon.Home,
                "Home",
                active = activeSection == BrowseSection.Home && selectedPlaylistId == null,
                enabled = mainNavEnabled,
                onClick = { onNavigate(BrowseSection.Home) },
            )
            NavRow(
                PhoebeIcon.Search,
                "Search",
                active = activeSection == BrowseSection.Search,
                enabled = mainNavEnabled,
                onClick = { onNavigate(BrowseSection.Search) },
            )
            NavRow(
                PhoebeIcon.Library,
                "Your Library",
                active = activeSection == BrowseSection.Library && selectedPlaylistId == null,
                enabled = mainNavEnabled,
                onClick = { onNavigate(BrowseSection.Library) },
            )
            NavRow(
                PhoebeIcon.PlaylistPlay,
                "Playlists",
                active = activeSection == BrowseSection.Playlists && selectedPlaylistId == null,
                enabled = mainNavEnabled,
                onClick = { onNavigate(BrowseSection.Playlists) },
            )
            NavRow(
                PhoebeIcon.Radio,
                "Radio",
                active = activeSection == BrowseSection.Radio && selectedPlaylistId == null,
                enabled = true,
                onClick = { onNavigate(BrowseSection.Radio) },
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(contentType = "label") { SectionLabel("Playlists", PhoebeUi.mutedText) }
            if (showPlaylistRefreshBar) {
                item(contentType = "loading") { CatalogLoadingStrip(Modifier.padding(bottom = 4.dp)) }
            }
            if (playlistActions.playlistsEnabled) {
                item(contentType = "create") {
                    PlaylistRow(
                        icon = PhoebeIcon.Plus,
                        title = "Create Playlist",
                        subtitle = null,
                        onClick = { playlistActions.onRequestCreatePlaylist(emptyList()) },
                    )
                }
            }
            if (likedSongsPlaylist != null) {
                item(key = likedSongsPlaylist.id, contentType = "playlist-nav-liked") {
                    SidebarPlaylistDropRow(
                        playlist = likedSongsPlaylist,
                        selectedPlaylistId = selectedPlaylistId,
                        onPlaylist = if (likedSongsPlaylist.id == PENDING_LIKED_SONGS_PLAYLIST_ID) {
                            { playlistActions.onOpenLikedSongs() }
                        } else {
                            onPlaylist
                        },
                    )
                }
            }
            items(regularPlaylists, key = { it.id }, contentType = { "playlist-nav" }) { playlist ->
                SidebarPlaylistDropRow(
                    playlist = playlist,
                    selectedPlaylistId = selectedPlaylistId,
                    onPlaylist = onPlaylist,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .phoebeClickable { profileExpanded = !profileExpanded }
                    .background(PhoebeUi.subtleFill)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF3876C8), Color(0xFFB87C5C)))))
                Column(Modifier.weight(1f)) {
                    Text(session?.userName ?: "Guest", color = PhoebeUi.secondaryText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (remoteSignedIn) "$providerName signed in" else "Not signed in",
                        color = PhoebeUi.mutedText,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
                PhoebeIconView(
                    if (profileExpanded) PhoebeIcon.ChevronUp else PhoebeIcon.ChevronDown,
                    tint = PhoebeUi.mutedText,
                    modifier = Modifier.size(14.dp),
                )
            }

            AnimatedVisibility(visible = profileExpanded) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .phoebeClickable {
                                profileExpanded = false
                                onNavigate(BrowseSection.Settings)
                            }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PhoebeIconView(PhoebeIcon.Settings, tint = PhoebeUi.secondaryText, modifier = Modifier.size(18.dp))
                        Text("Settings", color = PhoebeUi.primaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    session?.selectedServer?.name?.let { n ->
                        Text(n, color = PhoebeUi.mutedText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    session?.selectedLibrary?.title?.let { t ->
                        Text(t, color = PhoebeUi.mutedText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    CatalogMenuSyncIndicator(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp, bottom = 2.dp),
                    )
                    if (remoteSignedIn) {
                        OutlinedButton(
                            onClick = {
                                profileExpanded = false
                                onSignOut()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) { Text("Sign out", fontSize = 11.sp) }
                    } else {
                        OutlinedButton(
                            onClick = {
                                profileExpanded = false
                                onOpenSignIn()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) { Text("Sign in", fontSize = 11.sp) }
                    }
                    SectionLabel("Media sources", PhoebeUi.primaryText)
                    Text(remoteSourceLabel, color = PhoebeUi.mutedText, fontSize = 11.sp, lineHeight = 15.sp)
                    Text("Local folders — files on this device", color = PhoebeUi.mutedText, fontSize = 11.sp, lineHeight = 15.sp)
                    mediaSources.localFolders.forEach { folder ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(folder.label, color = PhoebeUi.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(folder.displayPath(), color = PhoebeUi.mutedText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (folder.enabled) "Enabled" else "Disabled", color = PhoebeUi.mutedText, fontSize = 10.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (folder.enabled) "Off" else "On",
                                    color = PhoebeUi.accentLight,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .phoebeClickable { onToggleLocalFolder(folder.id, !folder.enabled) }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                                Text(
                                    "Remove",
                                    color = PhoebeUi.mutedText,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .phoebeClickable { onRemoveLocalFolder(folder.id) }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SidebarMediaSourceButton(
                            label = "Add local folder",
                            onClick = { pickLocalFolder() },
                        )
                        SidebarMediaSourceButton(
                            label = if (catalogRefreshing) "Rescanning…" else "Rescan",
                            enabled = !catalogRefreshing,
                            onClick = onRefreshLibrary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarMediaSourceButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .widthIn(min = 112.dp)
            .height(ButtonDefaults.MinHeight),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}
