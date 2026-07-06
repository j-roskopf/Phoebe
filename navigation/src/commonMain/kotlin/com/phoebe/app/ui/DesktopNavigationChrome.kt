package com.phoebe.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NavRow(
    icon: PhoebeIcon,
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val showActive = active && enabled
    val iconTint = when {
        !enabled -> PhoebeUi.mutedText.copy(alpha = 0.45f)
        showActive -> PhoebeUi.accentLight
        else -> PhoebeUi.secondaryText
    }
    val labelColor = when {
        !enabled -> PhoebeUi.mutedText.copy(alpha = 0.45f)
        showActive -> PhoebeUi.primaryText
        else -> PhoebeUi.secondaryText
    }
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhoebeUi.shapes.controlRadius))
            .background(if (showActive) PhoebeUi.elevatedFill else Color.Transparent)
            .phoebeClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            PhoebeIconView(icon, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Text(label, color = labelColor, fontSize = 14.sp)
    }
}

@PreviewLightDark
@PreviewScreenSizes
@Composable
private fun NavRowPreview() {
    PhoebeTheme {
        Column(
            modifier = Modifier
                .width(236.dp)
                .fillMaxHeight()
                .background(PhoebeUi.sidebar)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NavRow(
                icon = PhoebeIcon.Home,
                label = "Home",
                active = true,
                onClick = {},
            )
            NavRow(
                icon = PhoebeIcon.Search,
                label = "Search",
                active = false,
                onClick = {},
            )
            NavRow(
                icon = PhoebeIcon.Library,
                label = "Your Library",
                active = false,
                enabled = false,
                onClick = {},
            )
        }
    }
}
