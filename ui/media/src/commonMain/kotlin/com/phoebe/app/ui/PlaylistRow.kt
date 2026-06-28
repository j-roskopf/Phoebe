package com.phoebe.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PlaylistRow(
    icon: PhoebeIcon?,
    title: String,
    subtitle: String?,
    thumbUrl: String? = null,
    accent: Boolean = false,
    active: Boolean = false,
    useContentRowBackground: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val contentCellStyle = useContentRowBackground
    val shape = RoundedCornerShape(8.dp)
    val artworkSize = if (contentCellStyle) 38.dp else 36.dp
    val artworkRadius = if (contentCellStyle) 8.dp else 6.dp
    val rowBackground = when {
        active -> PhoebeUi.accent.copy(alpha = 0.09f)
        contentCellStyle -> PhoebeUi.elevatedFill
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(rowBackground)
            .then(
                if (contentCellStyle) {
                    Modifier.border(BorderStroke(1.dp, PhoebeUi.border), shape)
                } else {
                    Modifier
                },
            )
            .phoebeCombinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(
                horizontal = if (contentCellStyle) 12.dp else 2.dp,
                vertical = if (contentCellStyle) 11.dp else 2.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            ArtworkImage(title, thumbUrl, Modifier.size(artworkSize), radius = artworkRadius)
            if (accent || icon != null) {
                Box(
                    Modifier
                        .size(artworkSize)
                        .clip(RoundedCornerShape(artworkRadius))
                        .background(
                            if (accent) {
                                Brush.linearGradient(
                                    listOf(
                                        PhoebeUi.accentLight.copy(alpha = 0.82f),
                                        Color(0xCC6D45E8),
                                    ),
                                )
                            } else {
                                Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon != null) {
                        PhoebeIconView(icon, tint = PhoebeUi.primaryText, modifier = Modifier.size(18.dp), filled = accent)
                    }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (contentCellStyle) PhoebeUi.primaryText else PhoebeUi.secondaryText,
                fontSize = if (contentCellStyle) 14.sp else 13.sp,
                fontWeight = if (contentCellStyle) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = if (contentCellStyle) PhoebeUi.secondaryText else PhoebeUi.mutedText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailingContent?.invoke()
    }
}
