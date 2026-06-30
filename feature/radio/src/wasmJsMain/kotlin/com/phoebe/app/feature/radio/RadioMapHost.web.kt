package com.phoebe.app.feature.radio

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.phoebe.app.domain.RadioMapViewport
import com.phoebe.app.ui.LocalPhoebePalette

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
    if (googleMapsApiKey.isNullOrBlank()) {
        fallback(modifier)
        return
    }

    var bounds by remember { mutableStateOf(RadioMapWebBounds()) }
    val currentItems = rememberUpdatedState(items)
    val currentOnItemSelected = rememberUpdatedState(onItemSelected)
    val currentOnItemPlay = rememberUpdatedState(onItemPlay)
    val currentOnMapZoomChanged = rememberUpdatedState(onMapZoomChanged)
    val currentOnMapViewportChanged = rememberUpdatedState(onMapViewportChanged)
    val currentOnMapSearchArea = rememberUpdatedState(onMapSearchArea)
    val markerTintCssHex = remember(markerTintColor) { markerTintColor.toRadioMapCssHex() }
    val useLightTheme = LocalPhoebePalette.current.canvasBackground.luminance() > 0.5f
    val initialHtml = remember(googleMapsApiKey, markerTintCssHex, useLightTheme) {
        radioMapHtml(
            items = items,
            selectedItem = selectedItem,
            startingStationIds = startingStationIds,
            mapLoading = mapLoading,
            googleMapsApiKey = googleMapsApiKey,
            markerTintCssHex = markerTintCssHex,
            useLightTheme = useLightTheme,
        )
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            val position = coordinates.positionInWindow()
            val size = coordinates.size
            bounds = RadioMapWebBounds(
                x = position.x,
                y = position.y,
                width = size.width.toFloat(),
                height = size.height.toFloat(),
            )
        },
    )

    var iframeId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(googleMapsApiKey, markerTintCssHex, useLightTheme) {
        val nextIframeId = createRadioMapIframe(
            html = initialHtml,
            onSelected = { itemId ->
                val item = currentItems.value.findRadioMapItem(itemId) ?: return@createRadioMapIframe
                currentOnItemSelected.value(item)
            },
            onPlay = { itemId ->
                val item = currentItems.value.findRadioMapItem(itemId) ?: return@createRadioMapIframe
                currentOnItemSelected.value(item)
                if (item is RadioMapItem.Station) {
                    currentOnItemPlay.value(item)
                }
            },
            onZoomChanged = { zoom ->
                currentOnMapZoomChanged.value(zoom)
            },
            onViewportChanged = { north, south, east, west, zoom ->
                currentOnMapViewportChanged.value(RadioMapViewport(north, south, east, west, zoom))
            },
            onSearchArea = { north, south, east, west, zoom ->
                currentOnMapSearchArea.value(RadioMapViewport(north, south, east, west, zoom))
            },
        )
        iframeId = nextIframeId
        onDispose {
            removeRadioMapIframe(nextIframeId)
        }
    }

    LaunchedEffect(iframeId, bounds) {
        val currentIframeId = iframeId ?: return@LaunchedEffect
        updateRadioMapIframeBounds(
            id = currentIframeId,
            x = bounds.x,
            y = bounds.y,
            width = bounds.width,
            height = bounds.height,
        )
    }

    LaunchedEffect(iframeId, items, selectedItem, startingStationIds, mapLoading) {
        val currentIframeId = iframeId ?: return@LaunchedEffect
        updateRadioMapIframeData(
            id = currentIframeId,
            jsonString = items.toRadioMapMarkerJson(),
            selectedId = selectedItem?.id,
            startingIdsJson = startingStationIds.toRadioMapStartingIdsJson(),
            mapLoading = mapLoading,
            onSelected = { itemId ->
                val item = currentItems.value.findRadioMapItem(itemId) ?: return@updateRadioMapIframeData
                currentOnItemSelected.value(item)
            },
            onPlay = { itemId ->
                val item = currentItems.value.findRadioMapItem(itemId) ?: return@updateRadioMapIframeData
                currentOnItemSelected.value(item)
                if (item is RadioMapItem.Station) {
                    currentOnItemPlay.value(item)
                }
            },
            onZoomChanged = { zoom ->
                currentOnMapZoomChanged.value(zoom)
            },
            onViewportChanged = { north, south, east, west, zoom ->
                currentOnMapViewportChanged.value(RadioMapViewport(north, south, east, west, zoom))
            },
            onSearchArea = { north, south, east, west, zoom ->
                currentOnMapSearchArea.value(RadioMapViewport(north, south, east, west, zoom))
            },
        )
    }
}

internal actual fun radioMapGoogleMapsApiKey(): String? =
    RadioMapBuildConfig.googleMapsWebApiKey.takeIf { it.isNotBlank() }
        ?: RadioMapBuildConfig.googleMapsApiKey.takeIf { it.isNotBlank() }

internal actual fun radioMapUsesExternalBrowser(): Boolean = false

internal actual fun radioMapUsesMinimalEmbeddedChrome(): Boolean = true

private data class RadioMapWebBounds(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
    (html, onSelected, onPlay, onZoomChanged, onViewportChanged, onSearchArea) => {
      const id = "phoebe-radio-map-" + Math.random().toString(36).slice(2);
      const iframe = document.createElement("iframe");
      iframe.id = id;
      iframe.srcdoc = html;
      iframe.setAttribute("title", "Radio map");
      iframe.style.position = "fixed";
      iframe.style.left = "0px";
      iframe.style.top = "0px";
      iframe.style.width = "0px";
      iframe.style.height = "0px";
      iframe.style.border = "0";
      iframe.style.borderRadius = "8px";
      iframe.style.overflow = "hidden";
      iframe.style.background = "#080b12";
      iframe.style.zIndex = "2";
      iframe.style.pointerEvents = "auto";
      document.body.appendChild(iframe);
      const bridgeHost = typeof window !== "undefined" ? window : globalThis;
      const bridge = bridgeHost.PhoebeRadioMap || {};
      bridgeHost.PhoebeRadioMap = bridge;
      globalThis.PhoebeRadioMap = bridge;
      bridge.selectItem = (itemId) => onSelected(String(itemId || ""));
      bridge.playItem = (itemId) => onPlay(String(itemId || ""));
      bridge.zoomChanged = (zoom) => {
        const parsed = Number(zoom);
        if (Number.isFinite(parsed)) onZoomChanged(parsed);
      };
      bridge.viewportChanged = (north, south, east, west, zoom) => {
        const parsedNorth = Number(north);
        const parsedSouth = Number(south);
        const parsedEast = Number(east);
        const parsedWest = Number(west);
        const parsedZoom = Number(zoom);
        if ([parsedNorth, parsedSouth, parsedEast, parsedWest, parsedZoom].every(Number.isFinite)) {
          onViewportChanged(parsedNorth, parsedSouth, parsedEast, parsedWest, parsedZoom);
        }
      };
      bridge.searchArea = (north, south, east, west, zoom) => {
        const parsedNorth = Number(north);
        const parsedSouth = Number(south);
        const parsedEast = Number(east);
        const parsedWest = Number(west);
        const parsedZoom = Number(zoom);
        if ([parsedNorth, parsedSouth, parsedEast, parsedWest, parsedZoom].every(Number.isFinite)) {
          onSearchArea(parsedNorth, parsedSouth, parsedEast, parsedWest, parsedZoom);
        }
      };
      const handleMessage = (event) => {
        const data = event.data || {};
        if (data.phoebeRadioMap !== true) return;
        const itemId = String(data.itemId || "");
        if (data.action === "selectItem") {
          bridge.selectItem(itemId);
        } else if (data.action === "playItem") {
          bridge.playItem(itemId);
        } else if (data.action === "zoomChanged") {
          bridge.zoomChanged(data.zoom);
        } else if (data.action === "viewportChanged" && data.viewport) {
          bridge.viewportChanged(data.viewport.north, data.viewport.south, data.viewport.east, data.viewport.west, data.viewport.zoom);
        } else if (data.action === "searchArea" && data.viewport) {
          bridge.searchArea(data.viewport.north, data.viewport.south, data.viewport.east, data.viewport.west, data.viewport.zoom);
        }
      };
      window.addEventListener("message", handleMessage);
      bridge.iframeMessageHandlers = bridge.iframeMessageHandlers || {};
      bridge.iframeMessageHandlers[id] = handleMessage;
      return id;
    }
    """,
)
private external fun createRadioMapIframe(
    html: String,
    onSelected: (String) -> Unit,
    onPlay: (String) -> Unit,
    onZoomChanged: (Double) -> Unit,
    onViewportChanged: (Double, Double, Double, Double, Double) -> Unit,
    onSearchArea: (Double, Double, Double, Double, Double) -> Unit,
): String

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
    (id, x, y, width, height) => {
      const iframe = document.getElementById(id);
      if (!iframe) return;
      const scale = window.devicePixelRatio || 1;
      const left = Math.max(0, (Number(x) || 0) / scale);
      const top = Math.max(0, (Number(y) || 0) / scale);
      const requestedWidth = Math.max(0, (Number(width) || 0) / scale);
      const requestedHeight = Math.max(0, (Number(height) || 0) / scale);
      const right = Math.min(window.innerWidth || left, left + requestedWidth);
      const bottom = Math.min(window.innerHeight || top, top + requestedHeight);
      const clippedWidth = Math.max(0, right - left);
      const clippedHeight = Math.max(0, bottom - top);
      iframe.style.left = left + "px";
      iframe.style.top = top + "px";
      iframe.style.width = clippedWidth + "px";
      iframe.style.height = clippedHeight + "px";
      iframe.style.display = clippedWidth > 0 && clippedHeight > 0 ? "block" : "none";
      if (iframe.contentWindow) {
        iframe.contentWindow.dispatchEvent(new Event("resize"));
      }
    }
    """,
)
private external fun updateRadioMapIframeBounds(
    id: String,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
    (id) => {
      const iframe = document.getElementById(id);
      const bridgeHost = typeof window !== "undefined" ? window : globalThis;
      const bridge = bridgeHost.PhoebeRadioMap || {};
      const handleMessage = bridge.iframeMessageHandlers && bridge.iframeMessageHandlers[id];
      if (handleMessage) {
        window.removeEventListener("message", handleMessage);
        delete bridge.iframeMessageHandlers[id];
      }
      if (iframe) iframe.remove();
    }
    """,
)
private external fun removeRadioMapIframe(id: String)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
    (id, jsonString, selectedId, startingIdsJson, mapLoading, onSelected, onPlay, onZoomChanged, onViewportChanged, onSearchArea) => {
      const markers = JSON.parse(jsonString);
      const startingIds = JSON.parse(startingIdsJson);
      const bridgeHost = typeof window !== "undefined" ? window : globalThis;
      const bridge = bridgeHost.PhoebeRadioMap || {};
      bridgeHost.PhoebeRadioMap = bridge;
      globalThis.PhoebeRadioMap = bridge;
      bridge.selectItem = (itemId) => onSelected(String(itemId || ""));
      bridge.playItem = (itemId) => onPlay(String(itemId || ""));
      bridge.zoomChanged = (zoom) => {
        const parsed = Number(zoom);
        if (Number.isFinite(parsed)) onZoomChanged(parsed);
      };
      bridge.viewportChanged = (north, south, east, west, zoom) => {
        const parsedNorth = Number(north);
        const parsedSouth = Number(south);
        const parsedEast = Number(east);
        const parsedWest = Number(west);
        const parsedZoom = Number(zoom);
        if ([parsedNorth, parsedSouth, parsedEast, parsedWest, parsedZoom].every(Number.isFinite)) {
          onViewportChanged(parsedNorth, parsedSouth, parsedEast, parsedWest, parsedZoom);
        }
      };
      bridge.searchArea = (north, south, east, west, zoom) => {
        const parsedNorth = Number(north);
        const parsedSouth = Number(south);
        const parsedEast = Number(east);
        const parsedWest = Number(west);
        const parsedZoom = Number(zoom);
        if ([parsedNorth, parsedSouth, parsedEast, parsedWest, parsedZoom].every(Number.isFinite)) {
          onSearchArea(parsedNorth, parsedSouth, parsedEast, parsedWest, parsedZoom);
        }
      };
      bridge.getLatestData = () => ({ markers: markers, selectedId: selectedId, startingIds: startingIds });
      const iframe = document.getElementById(id);
      if (iframe && iframe.contentWindow && iframe.contentWindow.updateRadioMapMarkers) {
        iframe.contentWindow.updateRadioMapMarkers(markers, selectedId);
      }
      if (iframe && iframe.contentWindow && iframe.contentWindow.setRadioMapSearchLoading) {
        iframe.contentWindow.setRadioMapSearchLoading(Boolean(mapLoading));
      }
      if (iframe && iframe.contentWindow && iframe.contentWindow.setRadioMapStartingStationIds) {
        iframe.contentWindow.setRadioMapStartingStationIds(startingIds);
      }
    }
    """,
)
private external fun updateRadioMapIframeData(
    id: String,
    jsonString: String,
    selectedId: String?,
    startingIdsJson: String,
    mapLoading: Boolean,
    onSelected: (String) -> Unit,
    onPlay: (String) -> Unit,
    onZoomChanged: (Double) -> Unit,
    onViewportChanged: (Double, Double, Double, Double, Double) -> Unit,
    onSearchArea: (Double, Double, Double, Double, Double) -> Unit,
)
