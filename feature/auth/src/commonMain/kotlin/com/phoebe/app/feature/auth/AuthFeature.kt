package com.phoebe.app.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.phoebe.app.data.JellyfinQuickConnectResult
import com.phoebe.app.domain.JellyfinSyncMode
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlexServer

@Immutable
data class AuthWelcomeRouteState(
    val message: String,
    val pinCode: String?,
    val jellyfinServers: List<PlexServer>,
    val jellyfinDiscoveryLoading: Boolean,
    val jellyfinQuickConnect: JellyfinQuickConnectResult?,
    val authInProgress: Boolean,
    val showLocalFolderHint: Boolean = true,
    val initialConnectProvider: MediaProviderType? = null,
)

class AuthWelcomeRouteActions(
    val onStartSignIn: () -> Unit,
    val onFinishSignIn: () -> Unit,
    val onSignInJellyfin: (String, String, String) -> Unit,
    val onSignInProvider: (MediaProviderType, String, String, String, JellyfinSyncMode?) -> Unit,
    val onDiscoverJellyfinServers: () -> Unit,
    val onStartJellyfinQuickConnect: (String) -> Unit,
    val onFinishJellyfinQuickConnect: () -> Unit,
    val onAddLocalFolder: (String?) -> Unit = {},
    val onOpenRadio: () -> Unit = {},
)

@Immutable
data class PlexServerPickerRouteState(
    val servers: List<PlexServer>,
    val busy: Boolean,
    val serversLoading: Boolean = false,
)

class PlexServerPickerRouteActions(
    val onSelectServer: (PlexServer) -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
)

@Immutable
data class PlexLibraryPickerRouteState(
    val libraries: List<MusicLibrary>,
    val serverName: String?,
    val providerType: MediaProviderType = MediaProviderType.Plex,
    val busy: Boolean,
    val librariesLoading: Boolean = false,
    val librariesLoadError: String? = null,
    val isJellyfin: Boolean = false,
)

class PlexLibraryPickerRouteActions(
    val onSelectLibrary: (MusicLibrary, JellyfinSyncMode?) -> Unit,
    val onBack: () -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
)

@Composable
fun AuthWelcomeDesktopRoute(
    state: AuthWelcomeRouteState,
    actions: AuthWelcomeRouteActions,
    modifier: Modifier = Modifier,
) {
    SignInWelcomeScreen(
        message = state.message,
        pinCode = state.pinCode,
        jellyfinServers = state.jellyfinServers,
        jellyfinDiscoveryLoading = state.jellyfinDiscoveryLoading,
        jellyfinQuickConnect = state.jellyfinQuickConnect,
        authInProgress = state.authInProgress,
        onStartSignIn = actions.onStartSignIn,
        onFinishSignIn = actions.onFinishSignIn,
        onSignInJellyfin = actions.onSignInJellyfin,
        onSignInProvider = actions.onSignInProvider,
        onDiscoverJellyfinServers = actions.onDiscoverJellyfinServers,
        onStartJellyfinQuickConnect = actions.onStartJellyfinQuickConnect,
        onFinishJellyfinQuickConnect = actions.onFinishJellyfinQuickConnect,
        onOpenRadio = actions.onOpenRadio,
        showLocalFolderHint = state.showLocalFolderHint,
        modifier = modifier,
    )
}

@Composable
fun AuthWelcomeMobileRoute(
    state: AuthWelcomeRouteState,
    actions: AuthWelcomeRouteActions,
    modifier: Modifier = Modifier,
) {
    MobileSignInWelcomeScreen(
        message = state.message,
        pinCode = state.pinCode,
        jellyfinServers = state.jellyfinServers,
        jellyfinDiscoveryLoading = state.jellyfinDiscoveryLoading,
        jellyfinQuickConnect = state.jellyfinQuickConnect,
        authInProgress = state.authInProgress,
        onStartSignIn = actions.onStartSignIn,
        onFinishSignIn = actions.onFinishSignIn,
        onSignInJellyfin = actions.onSignInJellyfin,
        onSignInProvider = actions.onSignInProvider,
        onDiscoverJellyfinServers = actions.onDiscoverJellyfinServers,
        onStartJellyfinQuickConnect = actions.onStartJellyfinQuickConnect,
        onFinishJellyfinQuickConnect = actions.onFinishJellyfinQuickConnect,
        onAddLocalFolder = actions.onAddLocalFolder,
        onOpenRadio = actions.onOpenRadio,
        initialConnectProvider = state.initialConnectProvider,
        modifier = modifier,
    )
}

@Composable
fun PlexServerPickerRoute(
    state: PlexServerPickerRouteState,
    actions: PlexServerPickerRouteActions,
    modifier: Modifier = Modifier,
) {
    PlexServerPickerPanel(
        servers = state.servers,
        busy = state.busy,
        serversLoading = state.serversLoading,
        onSelectServer = actions.onSelectServer,
        onCancel = actions.onCancel,
        onRetry = actions.onRetry,
        modifier = modifier,
    )
}

@Composable
fun PlexLibraryPickerRoute(
    state: PlexLibraryPickerRouteState,
    actions: PlexLibraryPickerRouteActions,
    modifier: Modifier = Modifier,
) {
    PlexLibraryPickerPanel(
        libraries = state.libraries,
        serverName = state.serverName,
        providerType = state.providerType,
        busy = state.busy,
        librariesLoading = state.librariesLoading,
        librariesLoadError = state.librariesLoadError,
        isJellyfin = state.isJellyfin,
        onSelectLibrary = actions.onSelectLibrary,
        onBack = actions.onBack,
        onCancel = actions.onCancel,
        onRetry = actions.onRetry,
        modifier = modifier,
    )
}
