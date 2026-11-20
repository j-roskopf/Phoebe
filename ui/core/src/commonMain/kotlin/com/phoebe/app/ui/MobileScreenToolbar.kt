package com.phoebe.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Collapsible search that lives in the toolbar instead of taking its own row. */
@Immutable
class MobileToolbarSearch(
    val query: String,
    val onQueryChange: (String) -> Unit,
    val expanded: Boolean,
    val onExpandedChange: (Boolean) -> Unit,
    val placeholder: String,
)

/** Heart toggle that narrows the current screen to favorites. */
@Immutable
class MobileToolbarFavoritesFilter(
    val enabled: Boolean,
    val onEnabledChange: (Boolean) -> Unit,
)

private val ToolbarButtonSize = 44.dp
private val ToolbarActionSize = 40.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MobileScreenToolbar(
    title: String,
    onBack: (() -> Unit)? = null,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    menuContent: @Composable () -> Unit,
    showMenu: Boolean = true,
    menuTint: Color = PhoebeUi.primaryText,
    onMenuLongClick: (() -> Unit)? = null,
    search: MobileToolbarSearch? = null,
    favoritesFilter: MobileToolbarFavoritesFilter? = null,
) {
    val searching = search != null && (search.expanded || search.query.isNotBlank())
    if (search != null && searching) {
        MobileToolbarSearchRow(search = search, favoritesFilter = favoritesFilter)
        return
    }
    val leadingActionCount = listOfNotNull(search, favoritesFilter).size
    val leadingWidth = (if (onBack != null) ToolbarButtonSize else 0.dp) + ToolbarActionSize * leadingActionCount
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 8.dp, end = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Center the title on the bar when it fits; otherwise slide it into whatever room is left
        // between the leading actions and the menu rather than truncating it.
        Layout(
            content = {
                Text(
                    title,
                    color = PhoebeUi.primaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val start = leadingWidth.roundToPx()
            val end = ToolbarButtonSize.roundToPx()
            val symmetric = maxOf(start, end)
            val measurable = measurables.first()
            val fitsCentered = measurable.maxIntrinsicWidth(constraints.maxHeight) <= width - 2 * symmetric
            val (x, slotWidth) = if (fitsCentered) {
                symmetric to width - 2 * symmetric
            } else {
                start to width - start - end
            }
            val titlePlaceable = measurable.measure(Constraints.fixedWidth(slotWidth.coerceAtLeast(0)))
            layout(width, titlePlaceable.height) { titlePlaceable.place(x, 0) }
        }
        Row(
            Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                ToolbarIconButton(
                    icon = PhoebeIcon.Back,
                    contentDescription = "Back",
                    onClick = onBack,
                )
            }
            if (search != null) {
                ToolbarIconButton(
                    icon = PhoebeIcon.Search,
                    contentDescription = "Search",
                    onClick = { search.onExpandedChange(true) },
                    size = ToolbarActionSize,
                )
            }
            if (favoritesFilter != null) {
                FavoritesFilterButton(favoritesFilter)
            }
        }
        Box(Modifier.align(Alignment.CenterEnd)) {
            if (showMenu) {
                Box(
                    Modifier
                        .size(ToolbarButtonSize)
                        .clip(CircleShape)
                        .then(
                            if (onMenuLongClick != null) {
                                Modifier.combinedClickable(
                                    onClick = { onMenuExpandedChange(true) },
                                    onLongClick = onMenuLongClick,
                                )
                            } else {
                                Modifier.clickable { onMenuExpandedChange(true) }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    PhoebeIconView(
                        PhoebeIcon.More,
                        tint = menuTint,
                        modifier = Modifier.size(22.dp),
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { onMenuExpandedChange(false) },
                    ) {
                        menuContent()
                    }
                }
            } else {
                Spacer(Modifier.size(ToolbarButtonSize))
            }
        }
    }
}

@Composable
private fun MobileToolbarSearchRow(
    search: MobileToolbarSearch,
    favoritesFilter: MobileToolbarFavoritesFilter?,
) {
    val focusRequester = remember { FocusRequester() }
    // Only grab focus when the user just opened search, not when a query restores the expanded row.
    LaunchedEffect(search.expanded) {
        if (search.expanded && search.query.isEmpty()) {
            // Wait a frame so the field (inside a lazy list header) is attached before focusing it.
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
    val collapse = {
        search.onQueryChange("")
        search.onExpandedChange(false)
    }
    PlatformBackHandler(enabled = true, onBack = collapse)
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToolbarIconButton(
            icon = PhoebeIcon.Back,
            contentDescription = "Close search",
            onClick = collapse,
        )
        SearchPill(
            query = search.query,
            onQueryChange = search.onQueryChange,
            modifier = Modifier.weight(1f).height(40.dp),
            placeholder = search.placeholder,
            focusRequester = focusRequester,
        )
        if (favoritesFilter != null) {
            FavoritesFilterButton(favoritesFilter)
        }
    }
}

@Composable
private fun FavoritesFilterButton(filter: MobileToolbarFavoritesFilter) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(ToolbarActionSize)
            .clip(shape)
            .background(if (filter.enabled) PhoebeUi.accent.copy(alpha = 0.18f) else Color.Transparent)
            .toggleable(
                value = filter.enabled,
                role = Role.Switch,
                onValueChange = filter.onEnabledChange,
            )
            .semantics { contentDescription = "Show favorites only" },
        contentAlignment = Alignment.Center,
    ) {
        PhoebeIconView(
            PhoebeIcon.Heart,
            tint = if (filter.enabled) PhoebeUi.accent else PhoebeUi.primaryText,
            modifier = Modifier.size(21.dp),
            filled = filter.enabled,
        )
    }
}

@Composable
private fun ToolbarIconButton(
    icon: PhoebeIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = ToolbarButtonSize,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        PhoebeIconView(icon, tint = PhoebeUi.primaryText, modifier = Modifier.size(22.dp))
    }
}

@PreviewLightDark
@PreviewScreenSizes
@Composable
private fun MobileScreenToolbarPreview() {
    PhoebeTheme {
        var expanded by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PhoebeUi.shellTop),
        ) {
            MobileScreenToolbar(
                title = "Library",
                onBack = {},
                menuExpanded = expanded,
                onMenuExpandedChange = { expanded = it },
                menuContent = {
                    DropdownMenuItem(text = { Text("Settings") }, onClick = {})
                    DropdownMenuItem(text = { Text("Refresh library") }, onClick = {})
                },
            )
        }
    }
}
