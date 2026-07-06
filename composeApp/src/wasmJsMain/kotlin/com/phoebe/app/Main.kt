package com.phoebe.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.phoebe.app.e2e.PhoebeWasmE2eApp
import com.phoebe.app.ui.PhoebeScreenshotApp
import com.phoebe.app.ui.PhoebeScreenshotScenario
import kotlinx.browser.window

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val queryParams = window.location.search
        .removePrefix("?")
        .split("&")
        .mapNotNull { part ->
            val key = part.substringBefore("=", missingDelimiterValue = "")
            val value = part.substringAfter("=", missingDelimiterValue = "")
            if (key.isBlank()) null else key to value
        }
        .toMap()
    val screenshotName = queryParams["screenshot"]
    val screenshotScenario = screenshotName
        ?.let { raw -> PhoebeScreenshotScenario.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
    val forceCustomLibraryScrollIndex = screenshotName.equals(
        PhoebeScreenshotScenario.LibraryScrollbar.name,
        ignoreCase = true,
    )
    val useLightAppearance = queryParams["theme"] == "light"
    val designId = queryParams["design"]
    val e2eMode = queryParams["e2e"]
    ComposeViewport(viewportContainerId = "composeApp") {
        when {
            e2eMode != null -> PhoebeWasmE2eApp(e2eMode = e2eMode)
            screenshotScenario != null -> {
                PhoebeScreenshotApp(
                    scenario = screenshotScenario,
                    useLightAppearance = useLightAppearance,
                    designId = designId ?: com.phoebe.app.ui.PhoebeDesignSystem.Default.id,
                    forceCustomLibraryScrollIndex = forceCustomLibraryScrollIndex,
                )
            }
            else -> BrowserRoutedApp()
        }
    }
}

@Composable
private fun BrowserRoutedApp() {
    var navigationPath by remember { mutableStateOf(currentBrowserPath()) }
    DisposableEffect(Unit) {
        installPhoebePopStateListener { path ->
            navigationPath = path
        }
        onDispose {
            removePhoebePopStateListener()
        }
    }
    App(
        navigationPath = navigationPath,
        onNavigationPathChange = { path, replace ->
            updatePhoebeBrowserPath(path, replace)
            navigationPath = currentBrowserPath()
        },
    )
}

private fun currentBrowserPath(): String = window.location.pathname.ifBlank { "/" }

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
    (callback) => {
      const listener = () => callback(location.pathname || "/");
      if (globalThis.__phoebePopStateListener) {
        window.removeEventListener("popstate", globalThis.__phoebePopStateListener);
      }
      globalThis.__phoebePopStateListener = listener;
      window.addEventListener("popstate", listener);
    }
    """,
)
private external fun installPhoebePopStateListener(callback: (String) -> Unit)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
    () => {
      if (globalThis.__phoebePopStateListener) {
        window.removeEventListener("popstate", globalThis.__phoebePopStateListener);
        globalThis.__phoebePopStateListener = null;
      }
    }
    """,
)
private external fun removePhoebePopStateListener()

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
    (path, replace) => {
      const nextPath = path || "/";
      if ((location.pathname || "/") === nextPath) return;
      const method = replace ? "replaceState" : "pushState";
      history[method](null, "", nextPath);
    }
    """,
)
private external fun updatePhoebeBrowserPath(path: String, replace: Boolean)
