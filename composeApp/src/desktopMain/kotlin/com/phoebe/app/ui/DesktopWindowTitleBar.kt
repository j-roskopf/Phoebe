package com.phoebe.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowScope
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame

private val SidebarWidth = 236.dp
private val ControlButtonWidth = 56.dp

@Composable
fun WindowScope.DesktopWindowTitleBar(
    useLightAppearance: Boolean,
    onClose: () -> Unit,
) {
    val palette = if (useLightAppearance) PhoebePaletteLight else PhoebePaletteDark
    val captionHeight = desktopTitleBarHeight()
    val composeWindow = window as? ComposeWindow
    var isMaximized by remember(composeWindow) {
        mutableStateOf(((composeWindow?.extendedState ?: 0) and JFrame.MAXIMIZED_BOTH) != 0)
    }
    DisposableEffect(composeWindow) {
        val target = composeWindow ?: return@DisposableEffect onDispose {}
        val listener = object : WindowAdapter() {
            override fun windowStateChanged(event: WindowEvent) {
                isMaximized = target.extendedState and JFrame.MAXIMIZED_BOTH != 0
            }
        }
        target.addWindowListener(listener)
        isMaximized = target.extendedState and JFrame.MAXIMIZED_BOTH != 0
        onDispose { target.removeWindowListener(listener) }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(captionHeight),
    ) {
        // Keep in sync with PhoebeRoot's compact breakpoint.
        val compactLayout = maxWidth < 1200.dp
        Row(Modifier.fillMaxSize()) {
            if (!compactLayout) {
                WindowDraggableArea(
                    modifier = Modifier
                        .width(SidebarWidth)
                        .fillMaxHeight(),
                ) {}
            }
            WindowDraggableArea(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {}
            Row(
                modifier = Modifier.fillMaxHeight(),
            ) {
            TitleBarControlButton(
                label = "−",
                contentColor = palette.primaryText,
                idleBackground = Color.Transparent,
                hoverBackground = palette.elevatedFill,
                onClick = { composeWindow?.isMinimized = true },
            )
            TitleBarControlButton(
                label = if (isMaximized) "❐" else "□",
                contentColor = palette.primaryText,
                idleBackground = Color.Transparent,
                hoverBackground = palette.elevatedFill,
                onClick = {
                    composeWindow?.let { target ->
                        val next = !isMaximized
                        target.extendedState = if (next) {
                            target.extendedState or JFrame.MAXIMIZED_BOTH
                        } else {
                            target.extendedState and JFrame.MAXIMIZED_BOTH.inv()
                        }
                        isMaximized = next
                    }
                },
            )
            TitleBarControlButton(
                label = "×",
                contentColor = palette.primaryText,
                idleBackground = Color.Transparent,
                hoverBackground = Color(0xFFE81123),
                hoverForeground = Color.White,
                onClick = onClose,
            )
            }
        }
    }
}

@Composable
private fun TitleBarControlButton(
    label: String,
    contentColor: Color,
    idleBackground: Color,
    hoverBackground: Color,
    onClick: () -> Unit,
    hoverForeground: Color = contentColor,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .width(ControlButtonWidth)
            .fillMaxHeight()
            .hoverable(interactionSource)
            .background(if (hovered) hoverBackground else idleBackground)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (hovered) hoverForeground else contentColor,
            fontSize = 16.sp,
        )
    }
}
