package com.phoebe.app.feature.auth

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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import com.phoebe.app.ui.PlatformBackHandler
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import phoebe.feature.auth.generated.resources.Res
import phoebe.feature.auth.generated.resources.emby
import phoebe.feature.auth.generated.resources.jellyfin
import phoebe.feature.auth.generated.resources.musicassistant
import phoebe.feature.auth.generated.resources.navidrome
import phoebe.feature.auth.generated.resources.phoebe_bird
import phoebe.feature.auth.generated.resources.phoebe_icon_rounded
import phoebe.feature.auth.generated.resources.plex
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.MaterialTheme
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
import com.phoebe.app.data.JellyfinQuickConnectResult
import com.phoebe.app.domain.Album
import com.phoebe.app.domain.AppScreen
import com.phoebe.app.domain.Artist
import com.phoebe.app.domain.JellyfinSyncMode
import com.phoebe.app.domain.LibraryColumnVisibility
import com.phoebe.app.domain.LibrarySortBy
import com.phoebe.app.domain.LibraryUiPreferences
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.LocalFolderMediaSourceConfig
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.MediaSourcesState
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.Playlist
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.domain.isLocalMediaPlayback
import com.phoebe.app.domain.isRemoteLibraryTrack
import com.phoebe.app.domain.supportsPlexPlaylists
import com.phoebe.app.ui.DetailBackButton
import com.phoebe.app.ui.LocalPhoebePalette
import com.phoebe.app.ui.PhoebeIcon
import com.phoebe.app.ui.PhoebeIconView
import com.phoebe.app.ui.PhoebePaletteLight
import com.phoebe.app.ui.PhoebeUi
import kotlinx.coroutines.delay
import com.phoebe.app.sources.rememberPickLocalFolder
import kotlin.math.max

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignInWelcomeScreen(
    message: String,
    pinCode: String?,
    jellyfinServers: List<PlexServer>,
    jellyfinDiscoveryLoading: Boolean,
    jellyfinQuickConnect: JellyfinQuickConnectResult?,
    authInProgress: Boolean,
    onStartSignIn: () -> Unit,
    onFinishSignIn: () -> Unit,
    onSignInJellyfin: (String, String, String) -> Unit,
    onSignInProvider: (MediaProviderType, String, String, String, JellyfinSyncMode?) -> Unit,
    onDiscoverJellyfinServers: () -> Unit,
    onStartJellyfinQuickConnect: (String) -> Unit,
    onFinishJellyfinQuickConnect: () -> Unit,
    onOpenRadio: () -> Unit = {},
    showLocalFolderHint: Boolean,
    modifier: Modifier = Modifier,
) {
    var jellyfinExpanded by remember { mutableStateOf(false) }
    var expandedProvider by remember { mutableStateOf<MediaProviderType?>(null) }
    var jellyfinUrl by remember { mutableStateOf("") }
    var jellyfinUser by remember { mutableStateOf("") }
    var jellyfinPassword by remember { mutableStateOf("") }
    var providerUrl by remember { mutableStateOf("") }
    var providerUser by remember { mutableStateOf("") }
    var providerPassword by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    LaunchedEffect(jellyfinExpanded, expandedProvider) {
        if (jellyfinExpanded || expandedProvider != null) {
            delay(180)
            scrollState.scrollTo(scrollState.maxValue)
        }
    }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Welcome", color = PhoebeUi.mutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em)
        Spacer(Modifier.height(8.dp))
        Text("Phoebe", color = PhoebeUi.primaryText, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            message,
            color = PhoebeUi.secondaryText,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(28.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DesktopProviderChip(
                text = "Plex",
                painter = painterResource(Res.drawable.plex),
                painterTint = Color(0xFFE5A00D),
                onClick = onStartSignIn,
                selected = false
            )
            DesktopProviderChip(
                text = "Jellyfin",
                painter = painterResource(Res.drawable.jellyfin),
                painterTint = Color(0xFF00A4DC),
                onClick = {
                    expandedProvider = null
                    jellyfinExpanded = !jellyfinExpanded
                },
                selected = jellyfinExpanded
            )
            listOf(MediaProviderType.Emby, MediaProviderType.Navidrome, MediaProviderType.MusicAssistant).forEach { provider ->
                DesktopProviderChip(
                    text = provider.providerButtonLabel() +
                        if (provider == MediaProviderType.MusicAssistant) " (alpha / not fully supported)" else "",
                    painter = when (provider) {
                        MediaProviderType.Emby -> painterResource(Res.drawable.emby)
                        MediaProviderType.Navidrome -> painterResource(Res.drawable.navidrome)
                        MediaProviderType.MusicAssistant -> painterResource(Res.drawable.musicassistant)
                        else -> null
                    },
                    painterTint = when (provider) {
                        MediaProviderType.Emby -> Color(0xFF52B54B)
                        MediaProviderType.Navidrome -> Color(0xFF0084FF)
                        MediaProviderType.MusicAssistant -> Color(0xFF03A9F4)
                        else -> null
                    },
                    onClick = {
                        jellyfinExpanded = false
                        expandedProvider = if (expandedProvider == provider) null else provider
                    },
                    selected = expandedProvider == provider
                )
            }
            if (pinCode != null) {
                DesktopProviderChip(
                    text = "Finish: $pinCode",
                    icon = PhoebeIcon.Check,
                    onClick = onFinishSignIn,
                    selected = true
                )
            }
            DesktopProviderChip(
                text = "Radio",
                icon = PhoebeIcon.Radio,
                onClick = onOpenRadio,
                selected = false,
            )
        }
        AnimatedVisibility(expandedProvider != null) {
            val provider = expandedProvider
            Column(
                Modifier
                    .padding(top = 14.dp)
                    .widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (provider == MediaProviderType.MusicAssistant) {
                    Text(
                        "Alpha: Music Assistant sync is experimental. Playback controls an existing Music Assistant player; local playback in Phoebe is not supported yet.",
                        color = PhoebeUi.secondaryText,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
                AuthTextField(providerUrl, { providerUrl = it }, "Server URL")
                AuthTextField(providerUser, { providerUser = it }, if (provider == MediaProviderType.MusicAssistant) "Username or token" else "Username")
                AuthTextField(providerPassword, { providerPassword = it }, if (provider == MediaProviderType.MusicAssistant) "Password or token" else "Password", isPassword = true)
                if (provider == MediaProviderType.Navidrome) {
                    SyncModeButtons(
                        providerName = MediaProviderType.Navidrome.providerButtonLabel(),
                        busy = authInProgress,
                        enabled = providerUrl.isNotBlank() && providerUser.isNotBlank() && providerPassword.isNotBlank(),
                        onMode = { mode -> onSignInProvider(MediaProviderType.Navidrome, providerUrl, providerUser, providerPassword, mode) },
                    )
                } else {
                    ProviderSignInButton(
                        label = "Sign in to ${provider?.providerButtonLabel() ?: "provider"}",
                        enabled = provider != null &&
                            providerUrl.isNotBlank() &&
                            (providerUser.isNotBlank() || providerPassword.isNotBlank()),
                        inProgress = authInProgress,
                        onClick = { provider?.let { onSignInProvider(it, providerUrl, providerUser, providerPassword, null) } },
                    )
                }
                AuthInlineStatus(message)
            }
        }
        AnimatedVisibility(jellyfinExpanded) {
            Column(
                Modifier
                    .padding(top = 14.dp)
                    .widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AuthTextField(jellyfinUrl, { jellyfinUrl = it }, JellyfinServerUrlPlaceholder)
                JellyfinServerDiscoveryControls(
                    servers = jellyfinServers,
                    loading = jellyfinDiscoveryLoading,
                    onDiscover = onDiscoverJellyfinServers,
                    onSelect = { jellyfinUrl = it.uri },
                )
                AuthTextField(jellyfinUser, { jellyfinUser = it }, "Username")
                AuthTextField(jellyfinPassword, { jellyfinPassword = it }, "Password", isPassword = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { onStartJellyfinQuickConnect(jellyfinUrl) },
                        enabled = jellyfinUrl.isNotBlank() && !authInProgress,
                    ) { Text("Quick Connect", fontSize = 14.sp) }
                    if (jellyfinQuickConnect != null) {
                        OutlinedButton(onClick = onFinishJellyfinQuickConnect, enabled = !authInProgress) {
                            Text("Finish: ${jellyfinQuickConnect.Code}", fontSize = 14.sp)
                        }
                    }
                }
                ProviderSignInButton(
                    label = "Sign in to Jellyfin",
                    enabled = jellyfinUrl.isNotBlank() && jellyfinUser.isNotBlank(),
                    inProgress = authInProgress,
                    onClick = { onSignInJellyfin(jellyfinUrl, jellyfinUser, jellyfinPassword) },
                )
                AuthInlineStatus(message)
            }
        }
        if (showLocalFolderHint) {
            Spacer(Modifier.height(28.dp))
            Text(
                "You can also expand the profile row at the bottom of the sidebar and add a local music folder.",
                color = PhoebeUi.mutedText,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 380.dp),
            )
        }
    }
}

@Composable
private fun DesktopProviderChip(
    text: String,
    icon: PhoebeIcon? = null,
    painter: androidx.compose.ui.graphics.painter.Painter? = null,
    painterTint: Color? = null,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    val lightMode = LocalPhoebePalette.current == PhoebePaletteLight
    val chipBackground = if (selected) PhoebeUi.accent.copy(alpha = 0.22f) else if (lightMode) PhoebeUi.glass else PhoebeUi.subtleFill
    val borderColor = if (selected) PhoebeUi.accent else PhoebeUi.border
    val contentColor = if (selected) PhoebeUi.accent else PhoebeUi.primaryText

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(chipBackground)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            PhoebeIconView(icon, tint = contentColor, modifier = Modifier.size(16.dp))
        } else if (painter != null) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                colorFilter = ColorFilter.tint(painterTint ?: contentColor),
            )
        }
        Text(text, color = contentColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun MediaProviderType.providerButtonLabel(): String = when (this) {
    MediaProviderType.Plex -> "Plex"
    MediaProviderType.Jellyfin -> "Jellyfin"
    MediaProviderType.Emby -> "Emby"
    MediaProviderType.Navidrome -> "Subsonic (Navidrome)"
    MediaProviderType.MusicAssistant -> "Music Assistant"
}

private const val JellyfinServerUrlPlaceholder = "http://hostname:8096"

@Composable
private fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.phoebe_bird),
        contentDescription = "Phoebe",
        modifier = modifier.size(size),
    )
}

@Composable
private fun SyncModeButtons(
    providerName: String,
    busy: Boolean,
    enabled: Boolean,
    onMode: (JellyfinSyncMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Quick sync starts browsing with paged $providerName results. Full sync fetches the whole library first.",
            color = PhoebeUi.mutedText,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { onMode(JellyfinSyncMode.Quick) }, enabled = enabled && !busy) {
                Text("Quick sync")
            }
            OutlinedButton(onClick = { onMode(JellyfinSyncMode.Full) }, enabled = enabled && !busy) {
                Text("Full sync")
            }
        }
    }
}

@Composable
fun MobileSignInWelcomeScreen(
    message: String,
    pinCode: String?,
    jellyfinServers: List<PlexServer>,
    jellyfinDiscoveryLoading: Boolean,
    jellyfinQuickConnect: JellyfinQuickConnectResult?,
    authInProgress: Boolean = false,
    onStartSignIn: () -> Unit,
    onFinishSignIn: () -> Unit,
    onSignInJellyfin: (String, String, String) -> Unit,
    onSignInProvider: (MediaProviderType, String, String, String, JellyfinSyncMode?) -> Unit,
    onDiscoverJellyfinServers: () -> Unit,
    onStartJellyfinQuickConnect: (String) -> Unit,
    onFinishJellyfinQuickConnect: () -> Unit,
    onAddLocalFolder: (String?) -> Unit,
    onOpenRadio: () -> Unit = {},
    signInError: String? = null,
    onClearSignInError: () -> Unit = {},
    initialConnectProvider: MediaProviderType? = null,
    modifier: Modifier = Modifier,
) {
    var connectProvider by remember { mutableStateOf(initialConnectProvider) }
    val clearError = { if (signInError != null) onClearSignInError() }
    val serverUrls = remember { mutableStateMapOf<MediaProviderType, String>() }
    val usernames = remember { mutableStateMapOf<MediaProviderType, String>() }
    val passwords = remember { mutableStateMapOf<MediaProviderType, String>() }
    val pickLocalFolder = rememberPickLocalFolder(onPicked = onAddLocalFolder)

    PlatformBackHandler(enabled = connectProvider != null, onBack = {
        clearError()
        connectProvider = null
    })

    Box(
        modifier
            .fillMaxSize()
            .background(AuthFlowBackgroundColor()),
    ) {
        AnimatedContent(
            targetState = connectProvider,
            transitionSpec = {
                val forward = targetState != null
                val enter = slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { w -> if (forward) w / 5 else -w / 5 } +
                    fadeIn(tween(220))
                val exit = slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { w -> if (forward) -w / 5 else w / 5 } +
                    fadeOut(tween(160))
                enter togetherWith exit
            },
            label = "mobileSignInPage",
        ) { provider ->
            if (provider == null) {
                SignInProviderPicker(
                    message = message,
                    pinCode = pinCode,
                    onPlex = { if (pinCode == null) onStartSignIn() else onFinishSignIn() },
                    onServer = { selected ->
                        clearError()
                        connectProvider = selected
                    },
                    onLocalFiles = { pickLocalFolder() },
                    onOpenRadio = onOpenRadio,
                )
            } else {
                ServerConnectPage(
                    provider = provider,
                    url = serverUrls[provider].orEmpty(),
                    onUrl = {
                        clearError()
                        serverUrls[provider] = it
                    },
                    username = usernames[provider].orEmpty(),
                    onUsername = {
                        clearError()
                        usernames[provider] = it
                    },
                    password = passwords[provider].orEmpty(),
                    onPassword = {
                        clearError()
                        passwords[provider] = it
                    },
                    signInError = signInError,
                    authInProgress = authInProgress,
                    jellyfinServers = jellyfinServers,
                    jellyfinDiscoveryLoading = jellyfinDiscoveryLoading,
                    jellyfinQuickConnect = jellyfinQuickConnect,
                    onBack = {
                        clearError()
                        connectProvider = null
                    },
                    onSignIn = { mode ->
                        val url = serverUrls[provider].orEmpty()
                        val user = usernames[provider].orEmpty()
                        val pass = passwords[provider].orEmpty()
                        if (provider == MediaProviderType.Jellyfin) {
                            onSignInJellyfin(url, user, pass)
                        } else {
                            onSignInProvider(provider, url, user, pass, mode)
                        }
                    },
                    onDiscoverJellyfinServers = onDiscoverJellyfinServers,
                    onStartJellyfinQuickConnect = { onStartJellyfinQuickConnect(serverUrls[provider].orEmpty()) },
                    onFinishJellyfinQuickConnect = onFinishJellyfinQuickConnect,
                )
            }
        }
    }
}

@Composable
private fun SignInProviderPicker(
    message: String,
    pinCode: String?,
    onPlex: () -> Unit,
    onServer: (MediaProviderType) -> Unit,
    onLocalFiles: () -> Unit,
    onOpenRadio: () -> Unit,
) {
    val lightMode = LocalPhoebePalette.current == PhoebePaletteLight
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val compact = maxHeight < 720.dp
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 20.dp, vertical = if (compact) 16.dp else 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BrandMark(size = if (compact) 26.dp else 28.dp)
                    Text(
                        "phoebe",
                        color = PhoebeUi.primaryText,
                        fontSize = if (compact) 22.sp else 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(if (compact) 16.dp else 20.dp))
                Image(
                    painter = painterResource(Res.drawable.phoebe_icon_rounded),
                    contentDescription = "Phoebe app icon",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(if (compact) 128.dp else 160.dp)
                        .clip(RoundedCornerShape(24.dp)),
                )
                Spacer(Modifier.height(if (compact) 16.dp else 20.dp))
                Text(
                    "Your music.\nBeautifully played.",
                    color = PhoebeUi.primaryText,
                    fontSize = if (compact) 23.sp else 26.sp,
                    lineHeight = if (compact) 28.sp else 31.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message,
                        color = PhoebeUi.secondaryText,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 340.dp),
                    )
                }

                Spacer(Modifier.height(if (compact) 20.dp else 28.dp))

                if (pinCode != null) {
                    PlexPendingBanner(pinCode = pinCode, onClick = onPlex)
                    Spacer(Modifier.height(16.dp))
                }

                Text(
                    "CONNECT YOUR MUSIC",
                    color = PhoebeUi.mutedText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.1.em,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 10.dp),
                )

                val tiles = listOf(
                    SignInTileSpec(
                        name = "Plex",
                        caption = if (pinCode == null) "Plex account" else "Waiting for approval",
                        tooltip = if (pinCode == null) {
                            "Sign in with your Plex account"
                        } else {
                            "Approve code $pinCode in your browser, then tap to finish"
                        },
                        painter = painterResource(Res.drawable.plex),
                        tint = PlexBrandColor,
                        onClick = onPlex,
                    ),
                ) + SignInServerProviders.map { provider ->
                    SignInTileSpec(
                        name = provider.tileName(),
                        caption = provider.tileCaption(),
                        tooltip = provider.tileTooltip(),
                        painter = provider.logoPainter(),
                        tint = provider.brandColor(),
                        onClick = { onServer(provider) },
                    )
                } + SignInTileSpec(
                    name = "Local files",
                    caption = "On this device",
                    tooltip = "Choose a folder of music stored on this device",
                    icon = PhoebeIcon.Library,
                    onClick = onLocalFiles,
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    tiles.chunked(2).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            row.forEach { tile ->
                                SignInProviderTile(tile, lightMode = lightMode, modifier = Modifier.weight(1f).fillMaxHeight())
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }

                Spacer(Modifier.height(if (compact) 12.dp else 18.dp))

                TextButton(onClick = onOpenRadio) {
                    PhoebeIconView(PhoebeIcon.Radio, tint = PhoebeUi.accentLight, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Just listening? Try internet radio", color = PhoebeUi.secondaryText, fontSize = 14.sp)
                }
            }
        }
    }
}

private class SignInTileSpec(
    val name: String,
    val caption: String,
    /** Full description, shown on long-press (hover on desktop) since the caption is kept short. */
    val tooltip: String,
    val painter: androidx.compose.ui.graphics.painter.Painter? = null,
    val icon: PhoebeIcon? = null,
    val tint: Color? = null,
    val highlighted: Boolean = false,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignInProviderTile(
    spec: SignInTileSpec,
    lightMode: Boolean,
    modifier: Modifier = Modifier,
) {
    // The caller's modifier carries the Row weight; TooltipBox wraps its content in its own layout and
    // drops that parent data, so the weight goes on this outer Box instead.
    Box(modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(spec.tooltip) } },
            state = rememberTooltipState(),
            modifier = Modifier.fillMaxSize(),
        ) {
            SignInProviderTileContent(spec, lightMode, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SignInProviderTileContent(
    spec: SignInTileSpec,
    lightMode: Boolean,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val background = when {
        spec.highlighted -> PhoebeUi.accent.copy(alpha = 0.16f)
        lightMode -> PhoebeUi.glass
        else -> PhoebeUi.subtleFill
    }
    val shadow = if (lightMode) Modifier.shadow(10.dp, shape, ambientColor = Color(0x14141820), spotColor = Color(0x14141820)) else Modifier
    Row(
        modifier
            .heightIn(min = 68.dp)
            .then(shadow)
            .clip(shape)
            .background(background)
            .border(BorderStroke(1.dp, if (spec.highlighted) PhoebeUi.accent else PhoebeUi.border), shape)
            .clickable(onClick = spec.onClick)
            .semantics { contentDescription = spec.name }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProviderLogoBadge(painter = spec.painter, icon = spec.icon, tint = spec.tint, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                spec.name,
                color = PhoebeUi.primaryText,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                spec.caption,
                color = if (spec.highlighted) PhoebeUi.accentLight else PhoebeUi.mutedText,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProviderLogoBadge(
    painter: androidx.compose.ui.graphics.painter.Painter?,
    icon: PhoebeIcon?,
    tint: Color?,
    size: Dp,
) {
    val color = tint ?: PhoebeUi.accentLight
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = size * 0.56f
        if (painter != null) {
            Image(painter = painter, contentDescription = null, modifier = Modifier.size(glyph), colorFilter = ColorFilter.tint(color))
        } else if (icon != null) {
            PhoebeIconView(icon, tint = color, modifier = Modifier.size(glyph))
        }
    }
}

@Composable
private fun PlexPendingBanner(pinCode: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PhoebeUi.accent.copy(alpha = 0.16f))
            .border(BorderStroke(1.dp, PhoebeUi.accent), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProviderLogoBadge(painter = painterResource(Res.drawable.plex), icon = null, tint = PlexBrandColor, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Finish Plex sign-in", color = PhoebeUi.primaryText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "Approve code $pinCode in your browser, then tap here.",
                color = PhoebeUi.secondaryText,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        PhoebeIconView(PhoebeIcon.Check, tint = PhoebeUi.accentLight, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ServerConnectPage(
    provider: MediaProviderType,
    url: String,
    onUrl: (String) -> Unit,
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    signInError: String?,
    authInProgress: Boolean,
    jellyfinServers: List<PlexServer>,
    jellyfinDiscoveryLoading: Boolean,
    jellyfinQuickConnect: JellyfinQuickConnectResult?,
    onBack: () -> Unit,
    onSignIn: (JellyfinSyncMode?) -> Unit,
    onDiscoverJellyfinServers: () -> Unit,
    onStartJellyfinQuickConnect: () -> Unit,
    onFinishJellyfinQuickConnect: () -> Unit,
) {
    var syncMode by remember(provider) { mutableStateOf(JellyfinSyncMode.Quick) }
    val isJellyfin = provider == MediaProviderType.Jellyfin
    val isMusicAssistant = provider == MediaProviderType.MusicAssistant
    val canSignIn = when (provider) {
        MediaProviderType.Jellyfin -> url.isNotBlank() && username.isNotBlank()
        MediaProviderType.Navidrome -> url.isNotBlank() && username.isNotBlank() && password.isNotBlank()
        else -> url.isNotBlank() && (username.isNotBlank() || password.isNotBlank())
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            DetailBackButton(onBack = onBack, modifier = Modifier.semantics { contentDescription = "Back" })
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
                ProviderLogoBadge(painter = provider.logoPainter(), icon = null, tint = provider.brandColor(), size = 56.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Connect to ${provider.tileName()}",
                    color = PhoebeUi.primaryText,
                    fontSize = 26.sp,
                    lineHeight = 31.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    provider.connectDescription(),
                    color = PhoebeUi.secondaryText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                if (isMusicAssistant) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Alpha: Music Assistant sync is experimental. Playback controls an existing Music Assistant player; local playback in Phoebe is not supported yet.",
                        color = PhoebeUi.secondaryText,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(PhoebeUi.subtleFill)
                            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    )
                }

                Spacer(Modifier.height(24.dp))

                AuthFieldLabel("Server URL")
                AuthTextField(
                    url,
                    onUrl,
                    provider.serverUrlPlaceholder(),
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    height = 50.dp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "A host name or IP is enough — http:// and port ${provider.defaultPortHint()} are tried if you leave them out.",
                    color = PhoebeUi.mutedText,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(start = 2.dp),
                )
                if (isJellyfin) {
                    Spacer(Modifier.height(6.dp))
                    JellyfinServerDiscoveryControls(
                        servers = jellyfinServers,
                        loading = jellyfinDiscoveryLoading,
                        onDiscover = onDiscoverJellyfinServers,
                        onSelect = { onUrl(it.uri) },
                    )
                }

                Spacer(Modifier.height(16.dp))
                AuthFieldLabel(if (isMusicAssistant) "Username or token" else "Username")
                AuthTextField(username, onUsername, "", height = 50.dp)

                Spacer(Modifier.height(16.dp))
                AuthFieldLabel(if (isMusicAssistant) "Password or token" else "Password")
                AuthTextField(password, onPassword, "", isPassword = true, height = 50.dp)

                if (provider == MediaProviderType.Navidrome) {
                    Spacer(Modifier.height(16.dp))
                    AuthFieldLabel("Library sync")
                    SyncModeSelector(selected = syncMode, enabled = !authInProgress, onSelect = { syncMode = it })
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (syncMode == JellyfinSyncMode.Quick) {
                            "Start browsing right away with paged results."
                        } else {
                            "Fetch the whole library before browsing. Slower for large libraries."
                        },
                        color = PhoebeUi.mutedText,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }

                Spacer(Modifier.height(24.dp))
                PrimaryAuthButton(
                    text = "Sign in",
                    enabled = canSignIn,
                    inProgress = authInProgress,
                    onClick = { onSignIn(if (provider == MediaProviderType.Navidrome) syncMode else null) },
                )
                if (signInError != null && !authInProgress) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        signInError,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (isJellyfin) {
                    Spacer(Modifier.height(18.dp))
                    AuthOrDivider()
                    Spacer(Modifier.height(18.dp))
                    if (jellyfinQuickConnect == null) {
                        OutlinedButton(
                            onClick = onStartJellyfinQuickConnect,
                            enabled = url.isNotBlank() && !authInProgress,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, PhoebeUi.border),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PhoebeUi.primaryText),
                        ) { Text("Use Quick Connect", fontSize = 15.sp) }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Approve this device from another signed-in Jellyfin app — no password needed.",
                            color = PhoebeUi.mutedText,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        QuickConnectCodeCard(
                            code = jellyfinQuickConnect.Code,
                            busy = authInProgress,
                            onFinish = onFinishJellyfinQuickConnect,
                            onRestart = onStartJellyfinQuickConnect,
                        )
                    }
                }

            }
        }
    }
}

@Composable
private fun AuthFieldLabel(text: String) {
    Text(
        text,
        color = PhoebeUi.secondaryText,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
    )
}

@Composable
private fun AuthOrDivider() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(PhoebeUi.border))
        Text("or", color = PhoebeUi.mutedText, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(PhoebeUi.border))
    }
}

@Composable
private fun SyncModeSelector(
    selected: JellyfinSyncMode,
    enabled: Boolean,
    onSelect: (JellyfinSyncMode) -> Unit,
) {
    val outer = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(outer)
            .background(PhoebeUi.elevatedFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), outer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(JellyfinSyncMode.Quick to "Quick sync", JellyfinSyncMode.Full to "Full sync").forEach { (mode, label) ->
            val active = mode == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) PhoebeUi.accent.copy(alpha = 0.24f) else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(mode) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (active) PhoebeUi.primaryText else PhoebeUi.mutedText,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun QuickConnectCodeCard(
    code: String,
    busy: Boolean,
    onFinish: () -> Unit,
    onRestart: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PhoebeUi.accent.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, PhoebeUi.accent.copy(alpha = 0.6f)), shape)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Quick Connect code", color = PhoebeUi.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(code, color = PhoebeUi.primaryText, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.18.em)
        Text(
            "Enter this code in another Jellyfin app under Quick Connect, then continue.",
            color = PhoebeUi.mutedText,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        PrimaryAuthButton(text = "I've approved it", enabled = true, inProgress = busy, onClick = onFinish)
        TextButton(onClick = onRestart, enabled = !busy) {
            Text("Get a new code", color = PhoebeUi.secondaryText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PrimaryAuthButton(
    text: String,
    enabled: Boolean,
    inProgress: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val active = enabled && !inProgress
    val background = if (active || inProgress) {
        Brush.linearGradient(listOf(PhoebeUi.accentLight, PhoebeUi.accent))
    } else {
        SolidColor(PhoebeUi.subtleFill)
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(background)
            .border(BorderStroke(1.dp, if (active || inProgress) Color.Transparent else PhoebeUi.border), shape)
            .clickable(enabled = active, onClick = onClick)
            .semantics { contentDescription = text },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (inProgress) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            if (inProgress) "Connecting…" else text,
            color = if (active || inProgress) Color.White else PhoebeUi.mutedText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private val SignInServerProviders = listOf(
    MediaProviderType.Jellyfin,
    MediaProviderType.Emby,
    MediaProviderType.Navidrome,
    MediaProviderType.MusicAssistant,
)

private val PlexBrandColor = Color(0xFFE5A00D)

@Composable
private fun MediaProviderType.logoPainter(): androidx.compose.ui.graphics.painter.Painter = when (this) {
    MediaProviderType.Plex -> painterResource(Res.drawable.plex)
    MediaProviderType.Jellyfin -> painterResource(Res.drawable.jellyfin)
    MediaProviderType.Emby -> painterResource(Res.drawable.emby)
    MediaProviderType.Navidrome -> painterResource(Res.drawable.navidrome)
    MediaProviderType.MusicAssistant -> painterResource(Res.drawable.musicassistant)
}

private fun MediaProviderType.brandColor(): Color = when (this) {
    MediaProviderType.Plex -> PlexBrandColor
    MediaProviderType.Jellyfin -> Color(0xFF00A4DC)
    MediaProviderType.Emby -> Color(0xFF52B54B)
    MediaProviderType.Navidrome -> Color(0xFF0084FF)
    MediaProviderType.MusicAssistant -> Color(0xFF03A9F4)
}

private fun MediaProviderType.tileName(): String = when (this) {
    MediaProviderType.Navidrome -> "Subsonic"
    else -> providerButtonLabel()
}

private fun MediaProviderType.tileCaption(): String = when (this) {
    MediaProviderType.Plex -> "Plex account"
    MediaProviderType.Jellyfin, MediaProviderType.Emby -> "Self-hosted"
    MediaProviderType.Navidrome -> "Navidrome"
    MediaProviderType.MusicAssistant -> "Alpha"
}

private fun MediaProviderType.tileTooltip(): String = when (this) {
    MediaProviderType.Plex -> "Sign in with your Plex account"
    MediaProviderType.Jellyfin -> "Connect to your self-hosted Jellyfin server"
    MediaProviderType.Emby -> "Connect to your self-hosted Emby server"
    MediaProviderType.Navidrome -> "Navidrome, Gonic, Airsonic and other Subsonic-compatible servers"
    MediaProviderType.MusicAssistant -> "Alpha: sync library items and control Music Assistant players"
}

private fun MediaProviderType.connectDescription(): String = when (this) {
    MediaProviderType.Plex -> "Stream from your Plex music library."
    MediaProviderType.Jellyfin -> "Sign in with your Jellyfin server account."
    MediaProviderType.Emby -> "Sign in with your Emby server account."
    MediaProviderType.Navidrome -> "Navidrome, Gonic, Airsonic and other Subsonic-compatible servers."
    MediaProviderType.MusicAssistant -> "Sync library items and control Music Assistant players."
}

private fun MediaProviderType.defaultPortHint(): Int = when (this) {
    MediaProviderType.Jellyfin, MediaProviderType.Emby -> 8096
    MediaProviderType.Navidrome -> 4533
    MediaProviderType.MusicAssistant -> 8095
    MediaProviderType.Plex -> 32400
}

private fun MediaProviderType.serverUrlPlaceholder(): String = when (this) {
    MediaProviderType.Jellyfin -> JellyfinServerUrlPlaceholder
    MediaProviderType.Emby -> "http://hostname:8096"
    MediaProviderType.Navidrome -> "http://hostname:4533"
    MediaProviderType.MusicAssistant -> "http://hostname:8095"
    MediaProviderType.Plex -> "http://hostname:32400"
}

@Composable
private fun JellyfinServerDiscoveryControls(
    servers: List<PlexServer>,
    loading: Boolean,
    onDiscover: () -> Unit,
    onSelect: (PlexServer) -> Unit,
) {
    // Only report "none found" after a search this screen started has actually run.
    var searchRan by remember { mutableStateOf(false) }
    var sawLoading by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) sawLoading = true else if (sawLoading) searchRan = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    searchRan = false
                    sawLoading = false
                    onDiscover()
                },
                enabled = !loading,
            ) {
                Text(if (loading) "Searching…" else "Find servers on my network", fontSize = 13.sp)
            }
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = PhoebeUi.accent,
                )
            }
        }
        if (searchRan && !loading && servers.isEmpty()) {
            Text(
                "No Jellyfin servers found on this network. Enter the server address above.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
        servers.take(4).forEach { server ->
            Surface(
                color = PhoebeUi.elevatedFill,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, PhoebeUi.border),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(server) },
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(server.name, color = PhoebeUi.primaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(server.uri, color = PhoebeUi.mutedText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun AuthInlineStatus(message: String) {
    if (message.isBlank()) return
    Text(
        message,
        color = PhoebeUi.secondaryText,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProviderSignInButton(
    label: String,
    enabled: Boolean,
    inProgress: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!enabled && !inProgress) {
            Text(
                "Enter your server URL and credentials to continue.",
                color = PhoebeUi.mutedText,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled && !inProgress,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = PhoebeUi.accent.copy(alpha = 0.22f),
                contentColor = PhoebeUi.primaryText,
                disabledContainerColor = PhoebeUi.subtleFill,
                disabledContentColor = PhoebeUi.mutedText,
            ),
        ) {
            if (inProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = PhoebeUi.accent,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(label, fontSize = 14.sp)
        }
    }
}

@Composable
fun AuthFlowBackgroundColor(): Color =
    if (LocalPhoebePalette.current == PhoebePaletteLight) Color.White else PhoebeUi.canvasBackground

@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    height: Dp = 44.dp,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = PhoebeUi.primaryText, fontSize = 14.sp),
        cursorBrush = SolidColor(PhoebeUi.accentLight),
        visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (isPassword) androidx.compose.ui.text.input.KeyboardType.Password else keyboardType,
            autoCorrectEnabled = false,
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(10.dp))
            .background(PhoebeUi.elevatedFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (value.isBlank()) {
                    Text(placeholder, color = PhoebeUi.mutedText, fontSize = 14.sp)
                }
                inner()
            }
        },
    )
}

@Composable
fun GradientActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lightMode = LocalPhoebePalette.current == PhoebePaletteLight
    val shadowColor = if (lightMode) {
        PhoebeUi.accent.copy(alpha = 0.22f)
    } else {
        PhoebeUi.accent.copy(alpha = 0.32f)
    }
    Box(
        modifier
            .height(62.dp)
            .shadow(18.dp, RoundedCornerShape(18.dp), ambientColor = shadowColor, spotColor = shadowColor)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(PhoebeUi.accentLight, PhoebeUi.accent)))
            .clickable(onClick = onClick)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PlexServerPickerPanel(
    servers: List<PlexServer>,
    busy: Boolean,
    serversLoading: Boolean = false,
    onSelectServer: (PlexServer) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(AuthFlowBackgroundColor())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Choose a Plex server", color = PhoebeUi.primaryText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "Select the server that stores your music. You need a Plex server with a music library on your account.",
                color = PhoebeUi.mutedText,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            if (serversLoading && servers.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = PhoebeUi.accentLight,
                        strokeWidth = 2.5.dp,
                        trackColor = PhoebeUi.progressTrack,
                    )
                    Text("Finding your Plex servers…", color = PhoebeUi.secondaryText, fontSize = 14.sp)
                }
            } else if (servers.isEmpty()) {
                Text("No servers were found for this Plex account.", color = PhoebeUi.secondaryText, fontSize = 14.sp)
                FilledTonalButton(
                    onClick = onRetry,
                    enabled = !busy && !serversLoading,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = PhoebeUi.accent.copy(alpha = 0.22f),
                        contentColor = PhoebeUi.primaryText,
                    ),
                ) { Text("Retry") }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    items(servers, key = { it.id }) { server ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !busy && !serversLoading) { onSelectServer(server) }
                                .background(PhoebeUi.elevatedFill)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(server.name, color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(server.uri, color = PhoebeUi.mutedText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Cancel sign-in") }
        }

        if (busy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = PhoebeUi.elevatedFill,
                    border = BorderStroke(1.dp, PhoebeUi.border),
                    modifier = Modifier.padding(32.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(
                            color = PhoebeUi.accent,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "Connecting to server...",
                            color = PhoebeUi.primaryText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlexLibraryPickerPanel(
    libraries: List<MusicLibrary>,
    serverName: String?,
    providerType: MediaProviderType = MediaProviderType.Plex,
    busy: Boolean,
    librariesLoading: Boolean = false,
    librariesLoadError: String? = null,
    isJellyfin: Boolean = false,
    onSelectLibrary: (MusicLibrary, JellyfinSyncMode?) -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingJellyfinLibrary by remember { mutableStateOf<MusicLibrary?>(null) }
    val pickerKind = when (providerType) {
        MediaProviderType.Navidrome -> "music folder"
        MediaProviderType.MusicAssistant -> "source"
        else -> "music library"
    }
    val pickerKindPlural = when (providerType) {
        MediaProviderType.Navidrome -> "music folders"
        MediaProviderType.MusicAssistant -> "sources"
        else -> "music libraries"
    }
    val providerName = providerType.providerButtonLabel()
    Column(
        modifier
            .fillMaxSize()
            .background(AuthFlowBackgroundColor())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DetailBackButton(onBack = onBack, enabled = !busy)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose a $pickerKind", color = PhoebeUi.primaryText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            serverName?.let { n ->
                Text("Server: $n", color = PhoebeUi.mutedText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                when {
                    isJellyfin -> "Pick a $providerName music library, then choose how much to sync before browsing."
                    providerType == MediaProviderType.Navidrome -> "Pick a Subsonic music folder to browse in Phoebe."
                    providerType == MediaProviderType.MusicAssistant -> "Pick the Music Assistant source to browse in Phoebe."
                    else -> "Pick the $providerName music library to browse in Phoebe."
                },
                color = PhoebeUi.mutedText,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
        if (librariesLoading && libraries.isEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = PhoebeUi.accent,
                    trackColor = PhoebeUi.progressTrack,
                )
                Text("Finding $pickerKindPlural...", color = PhoebeUi.secondaryText, fontSize = 14.sp)
            }
        } else if (libraries.isEmpty()) {
            Text(
                librariesLoadError ?: "No $pickerKindPlural found on this server.",
                color = PhoebeUi.secondaryText,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            FilledTonalButton(
                onClick = onRetry,
                enabled = !busy && !librariesLoading,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = PhoebeUi.accent.copy(alpha = 0.22f),
                    contentColor = PhoebeUi.primaryText,
                ),
            ) { Text("Retry") }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(libraries, key = { it.key }) { lib ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !busy && !librariesLoading) {
                                if (isJellyfin) {
                                    pendingJellyfinLibrary = lib
                                } else {
                                    onSelectLibrary(lib, null)
                                }
                            }
                            .background(PhoebeUi.elevatedFill)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(lib.title, color = PhoebeUi.primaryText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        pendingJellyfinLibrary?.let { library ->
            JellyfinSyncModePrompt(
                library = library,
                busy = busy,
                onMode = { mode -> onSelectLibrary(library, mode) },
                onDismiss = { pendingJellyfinLibrary = null },
            )
        }
        OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Cancel sign-in") }
    }
}

@Composable
private fun JellyfinSyncModePrompt(
    library: MusicLibrary,
    busy: Boolean,
    onMode: (JellyfinSyncMode) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Sync ${library.title}", color = PhoebeUi.primaryText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            "Quick sync starts browsing with paged server results. Full sync fetches the whole library first and can take much longer for large libraries.",
            color = PhoebeUi.mutedText,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        SyncModeButtons(
            providerName = "server",
            busy = busy,
            enabled = true,
            onMode = onMode,
        )
        TextButton(onClick = onDismiss, enabled = !busy) {
            Text("Cancel")
        }
    }
}
