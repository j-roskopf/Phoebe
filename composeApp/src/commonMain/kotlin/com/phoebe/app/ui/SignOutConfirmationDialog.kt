package com.phoebe.app.ui

import androidx.compose.runtime.Composable

/** Asks before signing out, since sign-out wipes this device's library cache, history and settings. */
@Composable
fun SignOutConfirmationDialog(
    serverName: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDeleteDownloadsDialog(
        title = serverName?.takeIf { it.isNotBlank() }?.let { "Sign out of $it?" } ?: "Sign out?",
        body = "Playback will stop, and this device's library cache, play history and settings will be cleared. " +
            "Last.fm and ListenBrainz will be disconnected. Music on your server isn't affected.",
        confirmLabel = "Sign out",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}
