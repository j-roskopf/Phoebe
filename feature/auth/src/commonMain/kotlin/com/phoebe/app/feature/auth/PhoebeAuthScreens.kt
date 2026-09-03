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
                    if (jellyfinUrl.isBlank()) {
                        jellyfinUrl = DefaultJellyfinServerUrl
                    }
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
                        if (providerUrl.isBlank()) {
                            providerUrl = when (provider) {
                                MediaProviderType.Emby -> DefaultEmbyServerUrl
                                MediaProviderType.Navidrome -> DefaultNavidromeServerUrl
                                else -> providerUrl
                            }
                        }
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
    MediaProviderType.Navidrome -> "Subsonic (Navidrome, etc)"
    MediaProviderType.MusicAssistant -> "Music Assistant"
}

private const val DefaultNavidromeServerUrl = "http://192.168.4.26:30043/"
private const val DefaultJellyfinServerUrl = "http://192.168.4.26:30013/"
private const val DefaultEmbyServerUrl = "http://192.168.4.26:36983/"
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
    initialProvidersExpanded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var providersExpanded by remember { mutableStateOf(initialProvidersExpanded) }
    var jellyfinExpanded by remember { mutableStateOf(false) }
    var expandedProvider by remember { mutableStateOf<MediaProviderType?>(null) }
    var jellyfinUrl by remember { mutableStateOf("") }
    var jellyfinUser by remember { mutableStateOf("") }
    var jellyfinPassword by remember { mutableStateOf("") }
    var providerUrl by remember { mutableStateOf("") }
    var providerUser by remember { mutableStateOf("") }
    var providerPassword by remember { mutableStateOf("") }
    var scrollToProvidersAfterExpand by remember { mutableStateOf(false) }
    var providerChoicesTopPx by remember { mutableStateOf<Float?>(null) }
    val pickLocalFolder = rememberPickLocalFolder(onPicked = onAddLocalFolder)
    val lightMode = LocalPhoebePalette.current == PhoebePaletteLight
    val scrollState = rememberScrollState()
    val providerChoicesTopInsetPx = with(LocalDensity.current) { 16.dp.toPx() }
    LaunchedEffect(scrollToProvidersAfterExpand, providersExpanded, providerChoicesTopPx, providerChoicesTopInsetPx) {
        val choicesTop = providerChoicesTopPx
        if (scrollToProvidersAfterExpand && providersExpanded && choicesTop != null) {
            delay(80)
            val target = (scrollState.value + choicesTop - providerChoicesTopInsetPx)
                .roundToInt()
                .coerceIn(0, scrollState.maxValue)
            scrollState.animateScrollTo(
                value = target,
                animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
            )
            scrollToProvidersAfterExpand = false
            providerChoicesTopPx = null
        }
    }
    LaunchedEffect(jellyfinExpanded, expandedProvider) {
        if (jellyfinExpanded || expandedProvider != null) {
            delay(180)
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(AuthFlowBackgroundColor()),
    ) {
        val compactHeight = maxHeight < 820.dp
        val heroMaxSize = if (compactHeight) 282.dp else 342.dp
        val topPadding = if (compactHeight) 18.dp else 28.dp
        val brandTopPadding = if (compactHeight) 8.dp else 16.dp
        val brandSpacer = if (compactHeight) 22.dp else 34.dp
        val heroSpacer = if (compactHeight) 22.dp else 28.dp
        val featureSpacer = if (compactHeight) 20.dp else 24.dp
        val ctaSpacer = if (compactHeight) 22.dp else 28.dp
        val titleSize = if (compactHeight) 30.sp else 32.sp
        val titleLineHeight = if (compactHeight) 35.sp else 38.sp

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .padding(start = 24.dp, top = topPadding, end = 24.dp, bottom = 24.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.padding(top = brandTopPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BrandMark(size = if (compactHeight) 30.dp else 34.dp)
                Text(
                    "phoebe",
                    color = PhoebeUi.primaryText,
                    fontSize = if (compactHeight) 25.sp else 28.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(brandSpacer))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = heroMaxSize)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(Res.drawable.phoebe_icon_rounded),
                    contentDescription = "Phoebe app icon",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.height(heroSpacer))

            Text(
                "Your music.\nBeautifully played.",
                color = PhoebeUi.primaryText,
                fontSize = titleSize,
                lineHeight = titleLineHeight,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                message.ifBlank { "High-fidelity playback, rich metadata, and a listening experience that puts your music first." },
                color = PhoebeUi.secondaryText,
                fontSize = 16.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 350.dp),
            )

            Spacer(Modifier.height(featureSpacer))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                WelcomeFeatureChip(PhoebeIcon.Music, "Lossless", lightMode = lightMode)
                WelcomeFeatureChip(PhoebeIcon.Library, "Local", lightMode = lightMode)
                WelcomeFeatureChip(PhoebeIcon.Settings, "Metadata", lightMode = lightMode)
            }

            Spacer(Modifier.height(ctaSpacer))

            AnimatedVisibility(
                visible = !providersExpanded,
                enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(180)),
            ) {
                GradientActionButton(
                    text = "Add media provider",
                    onClick = {
                        providersExpanded = true
                        providerChoicesTopPx = null
                        scrollToProvidersAfterExpand = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedVisibility(
                visible = !providersExpanded,
                enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(180)),
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onOpenRadio,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (lightMode) PhoebeUi.primaryText else Color.White,
                        ),
                        border = null,
                    ) {
                        PhoebeIconView(PhoebeIcon.Radio, tint = if (lightMode) PhoebeUi.primaryText else Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Listen to radio", fontSize = 14.sp)
                    }
                }
            }

            AnimatedVisibility(
                visible = providersExpanded,
                enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(240)),
                exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(180)),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned {
                            if (scrollToProvidersAfterExpand && providerChoicesTopPx == null) {
                                providerChoicesTopPx = it.positionInRoot().y
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProviderChoiceRow(
                        painter = painterResource(Res.drawable.plex),
                        painterTint = Color(0xFFE5A00D),
                        title = if (pinCode == null) "Sign in with Plex" else "Finish Plex sign-in",
                        subtitle = if (pinCode == null) "Stream from your Plex music library" else "Approve code $pinCode in your browser first",
                        lightMode = lightMode,
                        onClick = {
                            if (pinCode == null) onStartSignIn() else onFinishSignIn()
                        },
                    )
                    ProviderChoiceRow(
                        painter = painterResource(Res.drawable.jellyfin),
                        painterTint = Color(0xFF00A4DC),
                        title = "Sign in with Jellyfin",
                        subtitle = if (jellyfinExpanded) "Enter your server details below" else "Connect to a Jellyfin music server",
                        lightMode = lightMode,
                        expanded = jellyfinExpanded,
                        onClick = {
                            expandedProvider = null
                            if (jellyfinUrl.isBlank()) {
                                jellyfinUrl = DefaultJellyfinServerUrl
                            }
                            jellyfinExpanded = !jellyfinExpanded
                        },
                    )
                    AnimatedVisibility(jellyfinExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AuthTextField(jellyfinUrl, { jellyfinUrl = it }, JellyfinServerUrlPlaceholder)
                            JellyfinServerDiscoveryControls(
                                servers = jellyfinServers,
                                loading = jellyfinDiscoveryLoading,
                                onDiscover = onDiscoverJellyfinServers,
                                onSelect = { jellyfinUrl = it.uri },
                            )
                            AuthTextField(jellyfinUser, { jellyfinUser = it }, "Username")
                            AuthTextField(jellyfinPassword, { jellyfinPassword = it }, "Password", isPassword = true)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedButton(
                                    onClick = { onStartJellyfinQuickConnect(jellyfinUrl) },
                                    enabled = jellyfinUrl.isNotBlank() && !authInProgress,
                                    modifier = Modifier.weight(1f),
                                ) { Text("Quick Connect") }
                                if (jellyfinQuickConnect != null) {
                                    OutlinedButton(
                                        onClick = onFinishJellyfinQuickConnect,
                                        enabled = !authInProgress,
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Finish: ${jellyfinQuickConnect.Code}") }
                                }
                            }
                            ProviderSignInButton(
                                label = "Sign in to Jellyfin",
                                enabled = jellyfinUrl.isNotBlank() && jellyfinUser.isNotBlank(),
                                inProgress = authInProgress,
                                onClick = { onSignInJellyfin(jellyfinUrl, jellyfinUser, jellyfinPassword) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            AuthInlineStatus(message)
                        }
                    }
                    listOf(MediaProviderType.Emby, MediaProviderType.Navidrome, MediaProviderType.MusicAssistant).forEach { provider ->
                        val providerExpanded = expandedProvider == provider
                        ProviderChoiceRow(
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
                            title = "Sign in with ${provider.providerButtonLabel()}" +
                                if (provider == MediaProviderType.MusicAssistant) " (alpha / not fully supported)" else "",
                            subtitle = when {
                                providerExpanded -> "Enter your server details below"
                                provider == MediaProviderType.Emby -> "Connect to an Emby music server"
                                provider == MediaProviderType.Navidrome -> "Connect via the Subsonic API"
                                provider == MediaProviderType.MusicAssistant -> "Alpha: sync library items and control MA players"
                                else -> ""
                            },
                            lightMode = lightMode,
                            expanded = providerExpanded,
                            onClick = {
                                jellyfinExpanded = false
                                if (providerUrl.isBlank()) {
                                    providerUrl = when (provider) {
                                        MediaProviderType.Emby -> DefaultEmbyServerUrl
                                        MediaProviderType.Navidrome -> DefaultNavidromeServerUrl
                                        else -> providerUrl
                                    }
                                }
                                expandedProvider = if (expandedProvider == provider) null else provider
                            },
                        )
                        AnimatedVisibility(providerExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                        providerName = provider.providerButtonLabel(),
                                        busy = authInProgress,
                                        enabled = providerUrl.isNotBlank() && providerUser.isNotBlank() && providerPassword.isNotBlank(),
                                        onMode = { mode -> onSignInProvider(provider, providerUrl, providerUser, providerPassword, mode) },
                                    )
                                } else {
                                    ProviderSignInButton(
                                        label = "Sign in to ${provider.providerButtonLabel()}",
                                        enabled = providerUrl.isNotBlank() && (providerUser.isNotBlank() || providerPassword.isNotBlank()),
                                        inProgress = authInProgress,
                                        onClick = { onSignInProvider(provider, providerUrl, providerUser, providerPassword, null) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                AuthInlineStatus(message)
                            }
                        }
                    }
                    ProviderChoiceRow(
                        icon = PhoebeIcon.Radio,
                        title = "Listen to radio",
                        subtitle = "Browse internet radio without signing in",
                        lightMode = lightMode,
                        onClick = onOpenRadio,
                    )
                    ProviderChoiceRow(
                        icon = PhoebeIcon.Plus,
                        title = "Add local files",
                        subtitle = "Choose music stored on this device",
                        lightMode = lightMode,
                        onClick = { pickLocalFolder() },
                    )
                }
            }
        }
    }
}

@Composable
private fun JellyfinServerDiscoveryControls(
    servers: List<PlexServer>,
    loading: Boolean,
    onDiscover: () -> Unit,
    onSelect: (PlexServer) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onDiscover, enabled = !loading) {
                Text(if (loading) "Searching…" else "Search local servers", fontSize = 13.sp)
            }
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = PhoebeUi.accent,
                )
            }
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
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = PhoebeUi.primaryText, fontSize = 14.sp),
        cursorBrush = SolidColor(PhoebeUi.accentLight),
        visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (isPassword) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Text),
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
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
fun WelcomeFeatureChip(icon: PhoebeIcon, label: String, lightMode: Boolean) {
    val chipBackground = if (lightMode) PhoebeUi.glass else PhoebeUi.subtleFill
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(chipBackground)
            .border(BorderStroke(1.dp, PhoebeUi.border), CircleShape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        PhoebeIconView(icon, tint = PhoebeUi.accentLight, modifier = Modifier.size(15.dp))
        Text(label, color = PhoebeUi.primaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
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
fun ProviderChoiceRow(
    icon: PhoebeIcon? = null,
    painter: androidx.compose.ui.graphics.painter.Painter? = null,
    painterTint: Color? = null,
    title: String,
    subtitle: String,
    lightMode: Boolean,
    expanded: Boolean = false,
    onClick: () -> Unit,
) {
    val rowShape = RoundedCornerShape(18.dp)
    val rowBackground = when {
        expanded -> PhoebeUi.accent.copy(alpha = 0.14f)
        lightMode -> PhoebeUi.glass
        else -> PhoebeUi.subtleFill
    }
    val rowShadow = if (lightMode) Modifier.shadow(12.dp, rowShape, ambientColor = Color(0x14141820), spotColor = Color(0x14141820)) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(rowShadow)
            .clip(rowShape)
            .background(rowBackground)
            .border(BorderStroke(1.dp, if (expanded) PhoebeUi.accent else PhoebeUi.border), rowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(PhoebeUi.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                PhoebeIconView(icon, tint = PhoebeUi.accentLight, modifier = Modifier.size(19.dp))
            } else if (painter != null) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    colorFilter = ColorFilter.tint(painterTint ?: PhoebeUi.accentLight),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = PhoebeUi.primaryText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = PhoebeUi.secondaryText, fontSize = 12.sp, lineHeight = 16.sp)
        }
        PhoebeIconView(
            if (expanded) PhoebeIcon.ChevronUp else PhoebeIcon.Forward,
            tint = PhoebeUi.mutedText,
            modifier = Modifier.size(18.dp),
        )
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
