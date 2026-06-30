package com.phoebe.app.feature.radio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.interop.UIKitView
import com.phoebe.app.domain.RadioMapViewport
import com.phoebe.app.player.IosRadioMapNativeBridge
import com.phoebe.app.ui.LocalPhoebePalette
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun RadioMapHost(
    items: List<RadioMapItem>,
    selectedItem: RadioMapItem?,
    startingStationIds: Set<String>,
    mapLoading: Boolean,
    markerTintColor: Color,
    googleMapsApiKey: String?,
    onItemSelected: (RadioMapItem) -> Unit,
    onItemPlay: (RadioMapItem) -> Unit,
    onMapZoomChanged: (Double) -> Unit,
    onMapViewportChanged: (RadioMapViewport) -> Unit,
    onMapSearchArea: (RadioMapViewport) -> Unit,
    modifier: Modifier,
    fallback: @Composable (Modifier) -> Unit,
) {
    val factory = IosRadioMapNativeBridge.factory
    if (googleMapsApiKey.isNullOrBlank() || factory == null) {
        fallback(modifier)
        return
    }

    val currentItems = rememberUpdatedState(items)
    val currentOnItemSelected = rememberUpdatedState(onItemSelected)
    val currentOnMapZoomChanged = rememberUpdatedState(onMapZoomChanged)
    val currentOnMapViewportChanged = rememberUpdatedState(onMapViewportChanged)
    val markerTintArgb = remember(markerTintColor) { markerTintColor.toRadioMapArgbInt() }
    val useLightTheme = LocalPhoebePalette.current.canvasBackground.luminance() > 0.5f
    val itemsJson = remember(items) { items.toRadioMapMarkerJson() }
    val selectedItemId = selectedItem?.id

    UIKitView(
        factory = {
            factory.create(
                markersJson = itemsJson,
                selectedStationId = selectedItemId,
                markerTintArgb = markerTintArgb,
                useLightTheme = useLightTheme,
                googleMapsApiKey = googleMapsApiKey,
                onMarkerSelected = markerSelected@ { itemId ->
                    val item = currentItems.value.findRadioMapItem(itemId) ?: return@markerSelected
                    currentOnItemSelected.value(item)
                },
                onMarkerPlay = markerPlay@ { itemId ->
                    val item = currentItems.value.findRadioMapItem(itemId) ?: return@markerPlay
                    currentOnItemSelected.value(item)
                },
                onMapZoomChanged = { zoom ->
                    currentOnMapZoomChanged.value(zoom)
                },
                onMapViewportChanged = { north, south, east, west, zoom ->
                    currentOnMapViewportChanged.value(RadioMapViewport(north, south, east, west, zoom))
                },
            )
        },
        modifier = modifier,
        update = { view ->
            factory.update(
                view = view,
                markersJson = itemsJson,
                selectedStationId = selectedItemId,
                markerTintArgb = markerTintArgb,
                useLightTheme = useLightTheme,
                googleMapsApiKey = googleMapsApiKey,
            )
        },
    )
}

internal actual fun radioMapGoogleMapsApiKey(): String? =
    RadioMapBuildConfig.googleMapsIosApiKey.takeIf { it.isNotBlank() }
        ?: RadioMapBuildConfig.googleMapsApiKey.takeIf { it.isNotBlank() }

internal actual fun radioMapUsesExternalBrowser(): Boolean = false

internal actual fun radioMapUsesMinimalEmbeddedChrome(): Boolean = true
