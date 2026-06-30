package com.phoebe.app.feature.radio

import androidx.compose.ui.awt.SwingPanel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.phoebe.app.domain.RadioMapViewport
import com.phoebe.app.ui.LocalPhoebePalette
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.EnumProgress
import org.cef.CefApp
import org.cef.CefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter

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

    val currentItems = rememberUpdatedState(items)
    val currentOnItemSelected = rememberUpdatedState(onItemSelected)
    val currentOnItemPlay = rememberUpdatedState(onItemPlay)
    val currentOnMapZoomChanged = rememberUpdatedState(onMapZoomChanged)
    val currentOnMapViewportChanged = rememberUpdatedState(onMapViewportChanged)
    val currentOnMapSearchArea = rememberUpdatedState(onMapSearchArea)
    val markerTintCssHex = remember(markerTintColor) { markerTintColor.toRadioMapCssHex() }
    val useLightTheme = LocalPhoebePalette.current.canvasBackground.luminance() > 0.5f
    val server = remember(googleMapsApiKey, markerTintCssHex, useLightTheme) {
        DesktopRadioMapBrowserServer(
            googleMapsApiKey = googleMapsApiKey,
            markerTintCssHex = markerTintCssHex,
            useLightTheme = useLightTheme,
            onSelected = { itemId ->
                currentItems.value.findRadioMapItem(itemId)?.let { item ->
                    currentOnItemSelected.value(item)
                }
            },
            onPlay = { itemId ->
                currentItems.value.findRadioMapItem(itemId)?.let { item ->
                    currentOnItemSelected.value(item)
                    if (item is RadioMapItem.Station) {
                        currentOnItemPlay.value(item)
                    }
                }
            },
            onZoomChanged = { zoom -> currentOnMapZoomChanged.value(zoom) },
            onViewportChanged = { viewport -> currentOnMapViewportChanged.value(viewport) },
            onSearchArea = { viewport -> currentOnMapSearchArea.value(viewport) },
        )
    }
    DisposableEffect(server) {
        onDispose { server.dispose() }
    }

    if (!desktopRadioMapInlineBrowserEnabled()) {
        SwingPanel(
            modifier = modifier,
            factory = {
                DesktopRadioMapExternalLauncherPanel(onOpenExternal = { server.openInBrowser() }).also {
                    server.update(items, selectedItem = null, startingStationIds = startingStationIds, mapLoading = mapLoading)
                }
            },
            update = {
                server.update(items, selectedItem = null, startingStationIds = startingStationIds, mapLoading = mapLoading)
            },
        )
        return
    }

    val browserHolder = remember(server) {
        DesktopRadioMapChromiumHolder(
            initialUrl = server.cacheBustedUrl,
            onOpenExternal = { server.openInBrowser() },
        )
    }

    DisposableEffect(browserHolder) {
        onDispose { browserHolder.dispose() }
    }

    SwingPanel(
        modifier = modifier,
        factory = {
            browserHolder.panel.also {
                val snapshot = server.update(items, selectedItem, startingStationIds, mapLoading)
                browserHolder.update(snapshot)
            }
        },
        update = {
            val snapshot = server.update(items, selectedItem, startingStationIds, mapLoading)
            browserHolder.update(snapshot)
        },
    )
}

internal actual fun radioMapGoogleMapsApiKey(): String? =
    if (System.getProperty("org.gradle.test.worker") != null) {
        null
    } else {
        System.getenv("PHOEBE_GOOGLE_MAPS_DESKTOP_API_KEY")?.takeIf { it.isNotBlank() }
            ?: RadioMapBuildConfig.googleMapsDesktopApiKey.takeIf { it.isNotBlank() }
            ?: System.getenv("PHOEBE_GOOGLE_MAPS_WEB_API_KEY")?.takeIf { it.isNotBlank() }
            ?: RadioMapBuildConfig.googleMapsWebApiKey.takeIf { it.isNotBlank() }
            ?: System.getenv("PHOEBE_GOOGLE_MAPS_API_KEY")?.takeIf { it.isNotBlank() }
            ?: RadioMapBuildConfig.googleMapsApiKey.takeIf { it.isNotBlank() }
    }

internal actual fun radioMapUsesExternalBrowser(): Boolean = !desktopRadioMapInlineBrowserEnabled()

internal actual fun radioMapUsesMinimalEmbeddedChrome(): Boolean = desktopRadioMapInlineBrowserEnabled()

private fun desktopRadioMapInlineBrowserEnabled(): Boolean =
    System.getProperty("phoebe.radioMap.inlineBrowser")?.toBooleanStrictOrNull()
        ?: System.getenv("PHOEBE_RADIO_MAP_INLINE_BROWSER")?.toBooleanStrictOrNull()
        ?: true

private class DesktopRadioMapBrowserServer(
    private val googleMapsApiKey: String,
    private val markerTintCssHex: String,
    private val useLightTheme: Boolean,
    private val onSelected: (String) -> Unit,
    private val onPlay: (String) -> Unit,
    private val onZoomChanged: (Double) -> Unit,
    private val onViewportChanged: (RadioMapViewport) -> Unit,
    private val onSearchArea: (RadioMapViewport) -> Unit,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Phoebe-radio-map-browser").apply { isDaemon = true }
    }
    private val server: HttpServer = createDesktopRadioMapServer()
    val url: String = "http://127.0.0.1:${server.address.port}/radio-map"

    @Volatile
    private var items: List<RadioMapItem> = emptyList()

    @Volatile
    private var selectedItem: RadioMapItem? = null

    @Volatile
    private var startingStationIds: Set<String> = emptySet()

    @Volatile
    private var mapLoading: Boolean = false

    @Volatile
    private var revision: Long = 0L

    val cacheBustedUrl: String
        get() = "$url?revision=$revision"

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            when (exchange.requestURI.path) {
                "/", "/radio-map" -> exchange.sendHtml(currentHtml())
                "/select" -> exchange.handleItemAction(onSelected)
                "/play" -> exchange.handleItemAction(onPlay)
                "/zoom" -> exchange.handleZoom()
                "/viewport" -> exchange.handleViewport()
                "/searchArea" -> exchange.handleSearchArea()
                else -> exchange.sendText("Not found", status = 404)
            }
        }
        server.start()
    }

    fun update(
        items: List<RadioMapItem>,
        selectedItem: RadioMapItem?,
        startingStationIds: Set<String>,
        mapLoading: Boolean,
    ): DesktopRadioMapSnapshot {
        if (items != this.items) {
            revision += 1
        }
        this.items = items
        this.selectedItem = selectedItem
        this.startingStationIds = startingStationIds
        this.mapLoading = mapLoading
        return DesktopRadioMapSnapshot(
            url = cacheBustedUrl,
            markersJson = items.toRadioMapMarkerJson(),
            selectedId = selectedItem?.id,
            startingIdsJson = startingStationIds.toRadioMapStartingIdsJson(),
            mapLoading = mapLoading,
        )
    }

    fun openInBrowser() {
        val desktop = runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null }.getOrNull()
        if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
            runCatching { desktop.browse(URI(url)) }
        }
    }

    fun dispose() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun currentHtml(): String =
        radioMapHtml(
            items = items,
            selectedItem = selectedItem,
            startingStationIds = startingStationIds,
            mapLoading = mapLoading,
            googleMapsApiKey = googleMapsApiKey,
            markerTintCssHex = markerTintCssHex,
            desktopBridgeBaseUrl = url.substringBeforeLast('/'),
            useLightTheme = useLightTheme,
        )

    private fun HttpExchange.handleItemAction(action: (String) -> Unit) {
        val itemId = queryParameters()["id"].orEmpty()
        if (itemId.isNotBlank()) {
            SwingUtilities.invokeLater { action(itemId) }
        }
        sendText("ok")
    }

    private fun HttpExchange.handleZoom() {
        val zoom = queryParameters()["zoom"]?.toDoubleOrNull()
        if (zoom != null && zoom.isFinite()) {
            SwingUtilities.invokeLater { onZoomChanged(zoom) }
        }
        sendText("ok")
    }

    private fun HttpExchange.handleViewport() {
        val params = queryParameters()
        val viewport = RadioMapViewport(
            north = params["north"]?.toDoubleOrNull() ?: Double.NaN,
            south = params["south"]?.toDoubleOrNull() ?: Double.NaN,
            east = params["east"]?.toDoubleOrNull() ?: Double.NaN,
            west = params["west"]?.toDoubleOrNull() ?: Double.NaN,
            zoom = params["zoom"]?.toDoubleOrNull() ?: Double.NaN,
        )
        if (viewport.isValid) {
            SwingUtilities.invokeLater { onViewportChanged(viewport) }
        }
        sendText("ok")
    }

    private fun HttpExchange.handleSearchArea() {
        val params = queryParameters()
        val viewport = RadioMapViewport(
            north = params["north"]?.toDoubleOrNull() ?: Double.NaN,
            south = params["south"]?.toDoubleOrNull() ?: Double.NaN,
            east = params["east"]?.toDoubleOrNull() ?: Double.NaN,
            west = params["west"]?.toDoubleOrNull() ?: Double.NaN,
            zoom = params["zoom"]?.toDoubleOrNull() ?: Double.NaN,
        )
        if (viewport.isValid) {
            SwingUtilities.invokeLater { onSearchArea(viewport) }
        }
        sendText("ok")
    }
}

private data class DesktopRadioMapSnapshot(
    val url: String,
    val markersJson: String,
    val selectedId: String?,
    val startingIdsJson: String,
    val mapLoading: Boolean,
)

private fun DesktopRadioMapSnapshot.toJavaScript(): String {
    val markerPayload = markersJson.escapeJs()
    val selected = selectedId?.let { """"${it.escapeJs()}"""" } ?: "null"
    val startingIdsPayload = startingIdsJson.escapeJs()
    return """
        (function() {
          const markers = JSON.parse("$markerPayload");
          const selectedId = $selected;
          const startingIds = JSON.parse("$startingIdsPayload");
          window.PhoebeRadioMapLatest = { markers: markers, selectedId: selectedId, startingIds: startingIds };
          if (window.updateRadioMapMarkers) {
            window.updateRadioMapMarkers(markers, selectedId);
          }
          if (window.setRadioMapSearchLoading) {
            window.setRadioMapSearchLoading($mapLoading);
          }
          if (window.setRadioMapStartingStationIds) {
            window.setRadioMapStartingStationIds(startingIds);
          }
        })();
    """.trimIndent()
}

private class DesktopRadioMapExternalLauncherPanel(
    onOpenExternal: () -> Unit,
) : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(Box.createVerticalGlue())
        add(
            JLabel("Open the radio map in your browser.").apply {
                alignmentX = CENTER_ALIGNMENT
            },
        )
        add(Box.createVerticalStrut(12))
        add(
            JButton("Open map in browser").apply {
                alignmentX = CENTER_ALIGNMENT
                addActionListener { onOpenExternal() }
            },
        )
        add(Box.createVerticalGlue())
    }
}

private const val PreferredDesktopRadioMapPort = 41473

private fun createDesktopRadioMapServer(): HttpServer =
    try {
        HttpServer.create(InetSocketAddress("127.0.0.1", PreferredDesktopRadioMapPort), 0)
    } catch (_: IOException) {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    }

private fun desktopRadioMapReferrerInstruction(url: String): String {
    val port = runCatching { URI(url).port }
        .getOrDefault(PreferredDesktopRadioMapPort)
        .takeIf { it > 0 }
        ?: PreferredDesktopRadioMapPort
    return if (port == PreferredDesktopRadioMapPort) {
        "allow 127.0.0.1:$port/*"
    } else {
        "allow 127.0.0.1:$port/*, or free port $PreferredDesktopRadioMapPort and restart Phoebe to use the stable desktop map port"
    }
}

private class DesktopRadioMapChromiumHolder(
    initialUrl: String,
    private val onOpenExternal: () -> Unit,
) {
    val panel: JPanel = JPanel(BorderLayout())
    private var browser: CefBrowser? = null
    private var client: CefClient? = null
    private var pendingUrl: String = initialUrl
    private var loadedUrl: String? = null
    private val cefExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Phoebe-radio-map-cef").apply { isDaemon = true }
    }
    @Volatile
    private var disposed: Boolean = false

    init {
        showMessage("Loading radio map browser...")
        cefExecutor.execute {
            runCatching {
                log("starting jcefmaven browser")
                DesktopRadioMapCef.instance()
            }.onSuccess { app ->
                SwingUtilities.invokeLater {
                    if (!disposed) {
                        createBrowser(app)
                    }
                }
            }.onFailure { error ->
                log("start failed: ${error.stackTraceToString()}")
                showFallback("Could not start inline browser: ${error.message ?: error::class.simpleName}")
            }
        }
    }

    private fun createBrowser(app: CefApp) {
        runCatching {
            val nextClient = app.createClient()
            nextClient.addLifeSpanHandler(
                object : CefLifeSpanHandlerAdapter() {
                    override fun onAfterCreated(createdBrowser: CefBrowser) {
                        log("created browser; loading $pendingUrl")
                        val url = pendingUrl
                        loadedUrl = url
                        createdBrowser.loadURL(url)
                    }

                    override fun onBeforeClose(closingBrowser: CefBrowser) {
                        log("browser closing")
                    }
                },
            )
            nextClient.addDisplayHandler(
                object : CefDisplayHandlerAdapter() {
                    override fun onConsoleMessage(
                        browser: CefBrowser,
                        level: CefSettings.LogSeverity,
                        message: String,
                        source: String,
                        line: Int,
                    ): Boolean {
                        log("console [$level] $source:$line $message")
                        if (message.contains("Google Maps JavaScript API error", ignoreCase = true)) {
                            val referrerInstruction = desktopRadioMapReferrerInstruction(loadedUrl ?: pendingUrl)
                            showFallback(
                                "Google Maps rejected the desktop map key. Use Website restrictions, not IP address restrictions, and $referrerInstruction for the desktop key.",
                            )
                        }
                        return false
                    }
                },
            )
            nextClient.addLoadHandler(
                object : CefLoadHandlerAdapter() {
                    override fun onLoadError(
                        browser: CefBrowser,
                        frame: CefFrame,
                        errorCode: CefLoadHandler.ErrorCode,
                        errorText: String,
                        failedUrl: String,
                    ) {
                        if (errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) {
                            log("ignored aborted load for $failedUrl")
                            return
                        }
                        if (frame.isMain) {
                            showFallback("Could not load inline map: $errorText")
                        }
                        log("load error [$errorCode] $failedUrl $errorText")
                    }
                },
            )
            val nextBrowser = nextClient.createBrowser("about:blank", true, false)
            client = nextClient
            browser = nextBrowser
            showBrowser(nextBrowser.uiComponent)
            nextBrowser.createImmediately()
        }.onFailure { error ->
            log("start failed: ${error.stackTraceToString()}")
            showFallback("Could not start inline browser: ${error.message ?: error::class.simpleName}")
        }
    }

    fun update(snapshot: DesktopRadioMapSnapshot) {
        pendingUrl = snapshot.url
        val currentBrowser = browser
        if (currentBrowser != null && loadedUrl == null) {
            loadedUrl = snapshot.url
            currentBrowser.loadURL(snapshot.url)
        } else if (currentBrowser != null) {
            currentBrowser.executeJavaScript(snapshot.toJavaScript(), loadedUrl.orEmpty(), 0)
        }
    }

    fun dispose() {
        disposed = true
        cefExecutor.shutdownNow()
        SwingUtilities.invokeLater {
            runCatching { browser?.close(true) }
            runCatching { client?.dispose() }
            browser = null
            client = null
        }
    }

    private fun showBrowser(component: Component) {
        SwingUtilities.invokeLater {
            panel.removeAll()
            panel.add(component, BorderLayout.CENTER)
            panel.revalidate()
            panel.repaint()
        }
    }

    private fun showMessage(message: String) {
        SwingUtilities.invokeLater {
            panel.removeAll()
            panel.add(JLabel(message, JLabel.CENTER), BorderLayout.CENTER)
            panel.revalidate()
            panel.repaint()
        }
    }

    private fun showFallback(message: String) {
        SwingUtilities.invokeLater {
            panel.removeAll()
            panel.add(
                JPanel(BorderLayout()).apply {
                    add(JLabel(message, JLabel.CENTER), BorderLayout.CENTER)
                    add(
                        javax.swing.JButton("Open map in browser").apply {
                            addActionListener { onOpenExternal() }
                        },
                        BorderLayout.SOUTH,
                    )
                },
                BorderLayout.CENTER,
            )
            panel.revalidate()
            panel.repaint()
        }
    }

    private fun log(message: String) {
        println("radio-map desktop browser: $message")
    }
}

private object DesktopRadioMapCef {
    @Volatile
    private var app: CefApp? = null

    fun instance(): CefApp {
        app?.let { return it }
        synchronized(this) {
            app?.let { return it }
            val builder = CefAppBuilder().apply {
                setInstallDir(File(System.getProperty("user.home"), ".phoebe/jcef-maven/146.0.10"))
                setProgressHandler { progress, value ->
                    val suffix = if (value == EnumProgress.NO_ESTIMATION) "" else " ${value.toInt()}%"
                    println("radio-map desktop browser: jcefmaven $progress$suffix")
                }
                addJcefArgs(
                    "--disable-gpu",
                    "--disable-features=FontationsFontBackend",
                )
                getCefSettings().apply {
                        windowless_rendering_enabled = true
                    cache_path = File(System.getProperty("user.home"), ".phoebe/jcef-maven-cache").absolutePath
                    log_file = File(System.getProperty("user.home"), ".phoebe/jcef-maven.log").absolutePath
                    log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING
                }
            }
            println("radio-map desktop browser: building jcefmaven app")
            return builder.build().also { app = it }
        }
    }
}

private fun HttpExchange.queryParameters(): Map<String, String> {
    val query = requestURI.rawQuery ?: return emptyMap()
    return query.split('&')
        .filter { it.isNotBlank() }
        .associate { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            key.urlDecode() to value.urlDecode()
        }
}

private fun String.urlDecode(): String =
    URLDecoder.decode(this, StandardCharsets.UTF_8.name())

private fun HttpExchange.sendHtml(html: String) {
    responseHeaders.add("Content-Type", "text/html; charset=utf-8")
    responseHeaders.add("Cache-Control", "no-store")
    sendBytes(html.encodeToByteArray())
}

private fun HttpExchange.sendText(text: String, status: Int = 200) {
    responseHeaders.add("Access-Control-Allow-Origin", "*")
    responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    sendBytes(text.encodeToByteArray(), status)
}

private fun HttpExchange.sendBytes(bytes: ByteArray, status: Int = 200) {
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { body -> body.write(bytes) }
}
