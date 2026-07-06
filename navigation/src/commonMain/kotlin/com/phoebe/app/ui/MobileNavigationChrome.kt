package com.phoebe.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.phoebe.app.domain.MobileBottomTab

fun MobileBottomTab.browseSection(): BrowseSection = when (this) {
    MobileBottomTab.Home -> BrowseSection.Home
    MobileBottomTab.Search -> BrowseSection.Search
    MobileBottomTab.Library -> BrowseSection.Library
    MobileBottomTab.Playlists -> BrowseSection.Playlists
    MobileBottomTab.Radio -> BrowseSection.Radio
}

fun BrowseSection.mobileBottomTab(): MobileBottomTab? = when (this) {
    BrowseSection.Home -> MobileBottomTab.Home
    BrowseSection.Search -> MobileBottomTab.Search
    BrowseSection.Library -> MobileBottomTab.Library
    BrowseSection.Playlists -> MobileBottomTab.Playlists
    BrowseSection.Radio -> MobileBottomTab.Radio
    BrowseSection.Lyrics,
    BrowseSection.Downloads,
    BrowseSection.Settings,
    -> null
}

fun MobileBottomTab.iconLabel(): Pair<PhoebeIcon, String> = when (this) {
    MobileBottomTab.Home -> PhoebeIcon.Home to "Home"
    MobileBottomTab.Search -> PhoebeIcon.Search to "Search"
    MobileBottomTab.Library -> PhoebeIcon.Library to "Library"
    MobileBottomTab.Playlists -> PhoebeIcon.PlaylistPlay to "Playlists"
    MobileBottomTab.Radio -> PhoebeIcon.Radio to "Radio"
}

@Composable
fun MobileBottomNavigation(
    section: BrowseSection,
    onSection: (BrowseSection) -> Unit,
    attachedToMiniPlayer: Boolean = false,
    tabs: List<MobileBottomTab> = MobileBottomTab.defaultOrder,
) {
    val visibleTabs = tabs.ifEmpty { MobileBottomTab.defaultOrder }
    val topShape = if (attachedToMiniPlayer) {
        RoundedCornerShape(0.dp)
    } else {
        RoundedCornerShape(topStart = PhoebeUi.shapes.sheetTopRadius, topEnd = PhoebeUi.shapes.sheetTopRadius)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MobileBottomNavChromeHeight)
            .clip(topShape)
            .background(PhoebeUi.navBar, topShape)
            .then(if (attachedToMiniPlayer) Modifier else Modifier.border(BorderStroke(1.dp, PhoebeUi.border), topShape)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            visibleTabs.forEach { tab ->
                val target = tab.browseSection()
                val (icon, label) = tab.iconLabel()
                val active = section == target
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(PhoebeUi.shapes.controlRadius))
                        .clickable { onSection(target) }
                        .padding(vertical = 6.dp, horizontal = 2.dp)
                        .semantics { contentDescription = label },
                ) {
                    PhoebeIconView(icon, tint = if (active) PhoebeUi.accentLight else PhoebeUi.secondaryText, modifier = Modifier.size(19.dp))
                    Text(
                        label.uppercase(),
                        color = if (active) PhoebeUi.primaryText else PhoebeUi.mutedText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.06.em,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

fun mobileSectionTitle(section: BrowseSection): String = when (section) {
    BrowseSection.Home -> "Home"
    BrowseSection.Search -> "Search"
    BrowseSection.Library -> "Library"
    BrowseSection.Radio -> "Radio"
    BrowseSection.Lyrics -> "Lyrics"
    BrowseSection.Playlists -> "Playlists"
    BrowseSection.Downloads -> "Downloads"
    BrowseSection.Settings -> "Settings"
}

@PreviewLightDark
@PreviewScreenSizes
@Composable
private fun MobileBottomNavigationPreview() {
    PhoebeTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PhoebeUi.shellTop),
            verticalArrangement = Arrangement.Bottom,
        ) {
            MobileBottomNavigation(
                section = BrowseSection.Library,
                onSection = {},
                attachedToMiniPlayer = false,
            )
        }
    }
}
