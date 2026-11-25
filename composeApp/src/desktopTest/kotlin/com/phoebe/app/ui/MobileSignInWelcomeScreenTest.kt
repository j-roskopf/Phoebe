package com.phoebe.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.feature.auth.MobileSignInWelcomeScreen
import kotlin.test.Test
import kotlin.test.assertTrue

class MobileSignInWelcomeScreenTest {
    @Composable
    private fun Screen(onSignInProvider: (MediaProviderType) -> Unit = {}) {
        PhoebeTheme {
            Box(Modifier.size(412.dp, 860.dp)) {
                MobileSignInWelcomeScreen(
                    message = "",
                    pinCode = null,
                    jellyfinServers = emptyList(),
                    jellyfinDiscoveryLoading = false,
                    jellyfinQuickConnect = null,
                    onStartSignIn = {},
                    onFinishSignIn = {},
                    onSignInJellyfin = { _, _, _ -> },
                    onSignInProvider = { provider, _, _, _, _ -> onSignInProvider(provider) },
                    onDiscoverJellyfinServers = {},
                    onStartJellyfinQuickConnect = {},
                    onFinishJellyfinQuickConnect = {},
                    onAddLocalFolder = {},
                    modifier = Modifier.size(412.dp, 860.dp),
                )
            }
        }
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun allProviderChoicesAreVisibleWithoutExpanding() = runDesktopComposeUiTest(width = 412, height = 860) {
        setContent { Screen() }
        waitForIdle()

        listOf("Plex", "Jellyfin", "Emby", "Subsonic", "Music Assistant", "Local files").forEach { name ->
            onNodeWithContentDescription(name).assertIsDisplayed()
        }
        onNodeWithText("Just listening? Try internet radio").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun tappingServerProviderOpensItsConnectPageAndBackReturns() = runDesktopComposeUiTest(width = 412, height = 860) {
        setContent { Screen() }
        waitForIdle()

        onNodeWithContentDescription("Subsonic").performClick()
        mainClock.advanceTimeBy(400)
        waitForIdle()

        onNodeWithText("Connect to Subsonic").assertIsDisplayed()
        onNodeWithText("Server URL").assertIsDisplayed()
        onNodeWithText("Quick sync").assertIsDisplayed()
        assertTrue(onAllNodesWithText("Local files").fetchSemanticsNodes().isEmpty())

        onNodeWithContentDescription("Back").performClick()
        mainClock.advanceTimeBy(400)
        waitForIdle()

        onNodeWithContentDescription("Jellyfin").assertIsDisplayed()
    }
}
