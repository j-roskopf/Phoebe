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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.phoebe.app.remote.DiscoveredHost
import com.phoebe.app.remote.PendingPairingRequest
import com.phoebe.app.remote.RemoteConnectionStatus
import com.phoebe.app.remote.RemoteControlSessionState

@Composable
internal fun RemoteControlPairingApprovalDialog(
    request: PendingPairingRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDeny,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.92f)
                .clip(shape)
                .background(PhoebeUi.panel)
                .border(BorderStroke(1.dp, PhoebeUi.border), shape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(
                    "Remote Control Request",
                    color = PhoebeUi.primaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "A device on your local network wants to control playback",
                    color = PhoebeUi.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            val cardShape = RoundedCornerShape(10.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(PhoebeUi.modalField)
                    .border(BorderStroke(1.dp, PhoebeUi.border), cardShape)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    request.deviceName,
                    color = PhoebeUi.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Device ID: ${request.deviceId}",
                    color = PhoebeUi.mutedText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                "Allowing will let this device start, stop, skip, and change volume on this player.",
                color = PhoebeUi.mutedText,
                fontSize = 12.sp,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDeny) {
                    Text("Deny", color = PhoebeUi.mutedText, fontSize = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
                val buttonShape = RoundedCornerShape(8.dp)
                Box(
                    Modifier
                        .clip(buttonShape)
                        .clickable(onClick = onApprove)
                        .background(PhoebeUi.accentLight)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Allow",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RemoteControlHostPickerDialog(
    discoveredHosts: List<DiscoveredHost>,
    clientState: RemoteControlSessionState,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onConnect: (host: String, port: Int) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    DisposableEffect(Unit) {
        onStartDiscovery()
        onDispose {
            onStopDiscovery()
        }
    }

    var manualIp by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("8765") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth(0.92f)
                .clip(shape)
                .background(PhoebeUi.panel)
                .border(BorderStroke(1.dp, PhoebeUi.border), shape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "Connect to Device",
                        color = PhoebeUi.primaryText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Remote control playback on another Phoebe instance",
                        color = PhoebeUi.secondaryText,
                        fontSize = 12.sp,
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Done", color = PhoebeUi.accentLight, fontWeight = FontWeight.SemiBold)
                }
            }

            if (clientState.isConnected || clientState.status == RemoteConnectionStatus.Connecting || clientState.status == RemoteConnectionStatus.AwaitingApproval) {
                val activeShape = RoundedCornerShape(10.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(activeShape)
                        .background(PhoebeUi.accent.copy(alpha = 0.12f))
                        .border(BorderStroke(1.dp, PhoebeUi.accent.copy(alpha = 0.32f)), activeShape)
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            clientState.hostName ?: "Remote Host",
                            color = PhoebeUi.primaryText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        val statusText = when (clientState.status) {
                            RemoteConnectionStatus.Connecting -> "Connecting…"
                            RemoteConnectionStatus.AwaitingApproval -> "Waiting for approval on player…"
                            RemoteConnectionStatus.Connected -> "Connected"
                            else -> ""
                        }
                        Text(
                            statusText,
                            color = PhoebeUi.accentLight,
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = onDisconnect) {
                        Text("Disconnect", color = PhoebeUi.accentLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Text(
                "DISCOVERED PLAYERS",
                color = PhoebeUi.accentLight,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )

            if (discoveredHosts.isEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = PhoebeUi.accentLight,
                    )
                    Text(
                        "Searching local network for Phoebe players…",
                        color = PhoebeUi.mutedText,
                        fontSize = 12.sp,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    discoveredHosts.forEach { host ->
                        val hostCardShape = RoundedCornerShape(10.dp)
                        val isCurrentTarget = clientState.hostAddress == host.hostAddress && clientState.hostPort == host.tcpPort
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(hostCardShape)
                                .background(PhoebeUi.modalField)
                                .border(BorderStroke(1.dp, PhoebeUi.border), hostCardShape)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    host.name,
                                    color = PhoebeUi.primaryText,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${host.hostAddress}:${host.tcpPort}",
                                    color = PhoebeUi.mutedText,
                                    fontSize = 11.sp,
                                )
                            }
                            if (isCurrentTarget && clientState.isConnected) {
                                TextButton(onClick = onDisconnect) {
                                    Text("Disconnect", color = PhoebeUi.accentLight, fontSize = 12.sp)
                                }
                            } else {
                                val buttonShape = RoundedCornerShape(6.dp)
                                Box(
                                    Modifier
                                        .clip(buttonShape)
                                        .clickable { onConnect(host.hostAddress, host.tcpPort) }
                                        .background(PhoebeUi.accentLight)
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("Connect", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "MANUAL CONNECTION",
                color = PhoebeUi.accentLight,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val inputShape = RoundedCornerShape(8.dp)
                BasicTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it },
                    singleLine = true,
                    textStyle = TextStyle(color = PhoebeUi.primaryText, fontSize = 13.sp),
                    cursorBrush = SolidColor(PhoebeUi.accentLight),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(inputShape)
                        .background(PhoebeUi.modalField)
                        .border(BorderStroke(1.dp, PhoebeUi.border), inputShape)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    decorationBox = { innerTextField ->
                        if (manualIp.isBlank()) {
                            Text("192.168.1.xxx", color = PhoebeUi.mutedText, fontSize = 13.sp)
                        }
                        innerTextField()
                    },
                )
                BasicTextField(
                    value = manualPort,
                    onValueChange = { manualPort = it },
                    singleLine = true,
                    textStyle = TextStyle(color = PhoebeUi.primaryText, fontSize = 13.sp),
                    cursorBrush = SolidColor(PhoebeUi.accentLight),
                    modifier = Modifier
                        .width(70.dp)
                        .height(40.dp)
                        .clip(inputShape)
                        .background(PhoebeUi.modalField)
                        .border(BorderStroke(1.dp, PhoebeUi.border), inputShape)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                )
                val connectButtonShape = RoundedCornerShape(8.dp)
                val canConnect = manualIp.isNotBlank() && (manualPort.toIntOrNull() ?: 0) in 1..65535
                Box(
                    Modifier
                        .height(40.dp)
                        .clip(connectButtonShape)
                        .clickable(enabled = canConnect) {
                            onConnect(manualIp.trim(), manualPort.toIntOrNull() ?: 8765)
                        }
                        .background(if (canConnect) PhoebeUi.accentLight else PhoebeUi.subtleFill)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Connect",
                        color = if (canConnect) Color.White else PhoebeUi.mutedText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
