package com.phoebe.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.phoebe.app.EventsBackendHealthState
import com.phoebe.app.domain.EventSettings
import com.phoebe.app.domain.EventsBackendTarget
import com.phoebe.app.platform.isDebugBuild

@Composable
internal fun EventsDebugMenuDialog(
    settings: EventSettings,
    resolvedUrl: String?,
    healthState: EventsBackendHealthState,
    onSettings: (EventSettings) -> Unit,
    onTestConnection: (EventSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isDebugBuild()) return
    val normalized = settings.normalized()
    var localUrl by remember(normalized.localBackendUrl) { mutableStateOf(normalized.localBackendUrl.orEmpty()) }
    var localTarget by remember(normalized.backendTarget) { mutableStateOf(normalized.backendTarget) }
    fun pendingSettings(): EventSettings = normalized.copy(
        backendTarget = localTarget,
        localBackendUrl = localUrl.ifBlank { null },
    )
    Dialog(
        onDismissRequest = {
            onSettings(pendingSettings())
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(18.dp))
                .background(PhoebeUi.panel)
                .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(18.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Phoebe Backend", color = PhoebeUi.primaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Debug server target", color = PhoebeUi.secondaryText, fontSize = 12.sp)
                }
                TextButton(
                    onClick = {
                        onSettings(pendingSettings())
                        onDismiss()
                    },
                ) {
                    Text("Done", color = PhoebeUi.accentLight, fontWeight = FontWeight.SemiBold)
                }
            }
            DebugTargetControl(
                selected = localTarget,
                onSelected = { target ->
                    localTarget = target
                    onSettings(pendingSettings().copy(backendTarget = target))
                },
            )
            Text("Localhost URL", color = PhoebeUi.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            BasicTextField(
                value = localUrl,
                onValueChange = { value -> localUrl = value },
                singleLine = true,
                textStyle = TextStyle(color = PhoebeUi.primaryText, fontSize = 13.sp),
                cursorBrush = SolidColor(PhoebeUi.accentLight),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PhoebeUi.subtleFill)
                    .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                decorationBox = { innerTextField ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (localUrl.isBlank()) {
                            Text("Platform default localhost", color = PhoebeUi.mutedText, fontSize = 13.sp)
                        }
                        innerTextField()
                    }
                },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PhoebeUi.subtleFill)
                    .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Resolved URL", color = PhoebeUi.mutedText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    resolvedUrl ?: "Production URL is not configured",
                    color = PhoebeUi.primaryText,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (healthState.checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = PhoebeUi.accentLight,
                            strokeWidth = 2.dp,
                        )
                    }
                    healthState.message?.let { message ->
                        Text(
                            message,
                            color = if (healthState.success == false) PhoebeUi.accentLight else PhoebeUi.secondaryText,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(
                    enabled = !healthState.checking,
                    onClick = {
                        val updated = pendingSettings()
                        onSettings(updated)
                        onTestConnection(updated)
                    },
                ) {
                    Text(
                        "Test connection",
                        color = if (healthState.checking) PhoebeUi.mutedText else PhoebeUi.accentLight,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugTargetControl(
    selected: EventsBackendTarget,
    onSelected: (EventsBackendTarget) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EventsBackendTarget.entries.forEach { target ->
            val active = target == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelected(target) }
                    .background(if (active) PhoebeUi.accent.copy(alpha = 0.16f) else Color.Transparent)
                    .border(
                        BorderStroke(
                            1.dp,
                            if (active) PhoebeUi.accent.copy(alpha = 0.32f) else Color.Transparent,
                        ),
                        RoundedCornerShape(8.dp),
                    ),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    target.label,
                    color = if (active) PhoebeUi.accentLight else PhoebeUi.secondaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private val EventsBackendTarget.label: String
    get() = when (this) {
        EventsBackendTarget.Production -> "Production"
        EventsBackendTarget.Localhost -> "Localhost"
    }
