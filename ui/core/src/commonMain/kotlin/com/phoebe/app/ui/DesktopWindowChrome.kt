package com.phoebe.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phoebe.app.platform.isDesktopPlatform

/** Matches sidebar clearance on macOS desktop with a transparent title bar. */
private val DesktopMacTitleBarClearance = 54.dp

private val DesktopMinTitleBarHeight = 40.dp

/** Height of the custom Windows caption bar; 0 when the app does not merge title-bar chrome. */
@Composable
fun desktopTitleBarHeight(): Dp {
    if (!LocalDesktopMergesTitleBar.current) return 0.dp
    val density = LocalDensity.current
    return with(density) {
        WindowInsets.captionBar.getTop(density).toDp().coerceAtLeast(DesktopMinTitleBarHeight)
    }
}

@Composable
fun desktopWindowTopPadding(): Dp {
    return windowTopPadding()
}

@Composable
fun windowTopPadding(): Dp {
    val density = LocalDensity.current
    if (!isDesktopPlatform()) {
        return with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    }
    if (LocalDesktopMergesTitleBar.current) {
        return 0.dp
    }
    return maxOf(
        DesktopMacTitleBarClearance,
        with(density) { WindowInsets.safeDrawing.getTop(density).toDp() },
        with(density) { WindowInsets.captionBar.getTop(density).toDp() },
    )
}

@Composable
fun Modifier.mobileWindowTopPadding(): Modifier = padding(top = windowTopPadding())

/** Top inset for scrollable mobile-style content; desktop compact shell already handles window chrome. */
@Composable
fun mobileContentTopPadding(base: Dp = 0.dp): Dp =
    if (isDesktopPlatform()) {
        base
    } else {
        base + windowTopPadding()
    }
