package com.phoebe.app.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.phoebe.app.domain.CatalogSnapshot
import com.phoebe.app.domain.SmartPlaylistTemplate
import com.phoebe.app.ui.LocalPlaylistActions
import com.phoebe.app.ui.PhoebeIcon
import com.phoebe.app.ui.PhoebeIconView
import com.phoebe.app.ui.PhoebeUi

@Composable
internal fun SmartPlaylistCreateButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        PhoebeIconView(PhoebeIcon.InterwovenArrows, tint = PhoebeUi.accentLight, modifier = Modifier.size(15.dp))
        Text(
            "Create Smart Playlist",
            color = PhoebeUi.accentLight,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
internal fun SmartPlaylistTemplateDialog(
    catalog: CatalogSnapshot,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playlistActions = LocalPlaylistActions.current
    val sections = remember(catalog, playlistActions.smartPlaylistTemplates) {
        smartPlaylistTemplateSections(catalog, playlistActions.smartPlaylistTemplates)
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PhoebeUi.panel)
                .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(12.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(PhoebeUi.accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    PhoebeIconView(PhoebeIcon.InterwovenArrows, tint = PhoebeUi.accentLight, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("New smart playlist", color = PhoebeUi.primaryText, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Text(
                        "Start from a template and Phoebe will keep it updated.",
                        color = PhoebeUi.mutedText,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEach { section ->
                    item(key = "section-${section.title}", contentType = "section") {
                        Text(section.title, color = PhoebeUi.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    items(section.templates, key = { "${section.title}-${it.title}" }) { template ->
                        SmartPlaylistTemplateRow(
                            template = template,
                            onClick = {
                                playlistActions.onCreateSmartPlaylist(template, template.title)
                                onDismiss()
                            },
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = PhoebeUi.secondaryText, fontSize = 13.sp)
                }
            }
        }
    }
}

private data class SmartPlaylistTemplateSection(
    val title: String,
    val templates: List<SmartPlaylistTemplate>,
)

private fun smartPlaylistTemplateSections(
    catalog: CatalogSnapshot,
    defaults: List<SmartPlaylistTemplate>,
): List<SmartPlaylistTemplateSection> {
    val listening = listOf(
        SmartPlaylistTemplate.RecentlyPlayed,
        SmartPlaylistTemplate.MostPlayed,
        SmartPlaylistTemplate.NotPlayedRecently,
    )
    val decades = catalog.smartPlaylistDecades().map(SmartPlaylistTemplate::byDecade)
    // Genre labels like "Hip Hop" / "Hip-Hop" share a slug id but match distinct spellings,
    // so keep every punctuation variant and key the list by the original title instead of the id.
    val genres = catalog.smartPlaylistGenres().map(SmartPlaylistTemplate::byGenre)
    val starter = defaults.filterNot { template ->
        template.id in setOf(
            SmartPlaylistTemplate.RecentlyPlayed.id,
            SmartPlaylistTemplate.MostPlayed.id,
            SmartPlaylistTemplate.NotPlayedRecently.id,
            SmartPlaylistTemplate.ByDecade.id,
        )
    }
    return listOf(
        SmartPlaylistTemplateSection("Listening", listening),
        SmartPlaylistTemplateSection("Decades", decades),
        SmartPlaylistTemplateSection("Genres", genres),
        SmartPlaylistTemplateSection("Starter templates", starter),
    ).filter { it.templates.isNotEmpty() }
}

private fun CatalogSnapshot.smartPlaylistDecades(): List<Int> {
    val catalogDecades = tracksByParent.values
        .asSequence()
        .flatten()
        .mapNotNull { track -> track.year }
        .map { year -> year - (year % 10) }
        .filter { it in 1900..2090 }
        .toSet()
    return (catalogDecades + (1950..2020 step 10)).sortedDescending()
}

private fun CatalogSnapshot.smartPlaylistGenres(): List<String> {
    val rawGenres = artists.asSequence().mapNotNull { it.genre } +
        albums.asSequence().mapNotNull { it.genre } +
        tracksByParent.values.asSequence().flatten().mapNotNull { it.genre }
    return rawGenres
        .flatMap { genre -> genre.split(',', ';', '/') }
        .map { it.trim() }
        .filter { it.length >= 2 }
        // Collapse exact duplicates (same spelling/casing) but keep punctuation variants like
        // "Hip Hop" vs "Hip-Hop"; their byGenre ids collide but they match distinct track tags.
        .distinctBy { it.lowercase() }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        .toList()
}

@Composable
private fun SmartPlaylistTemplateRow(
    template: SmartPlaylistTemplate,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(PhoebeUi.sidebar)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PhoebeIconView(PhoebeIcon.PlaylistPlay, tint = PhoebeUi.accentLight, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                template.title,
                color = PhoebeUi.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                template.description,
                color = PhoebeUi.mutedText,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PhoebeIconView(PhoebeIcon.Forward, tint = PhoebeUi.mutedText, modifier = Modifier.size(12.dp))
    }
}
