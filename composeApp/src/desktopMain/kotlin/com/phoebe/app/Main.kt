package com.phoebe.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.phoebe.app.platform.PhoebeLog
import com.phoebe.app.platform.configureWindowsDesktopRendering
import com.phoebe.app.platform.WindowsUndecoratedWindowSupport
import com.phoebe.app.platform.appDisplayName
import com.phoebe.app.platform.isDebugBuild
import com.phoebe.app.ui.DesktopWindowTitleBar
import com.phoebe.app.ui.LocalDesktopMergesTitleBar
import com.phoebe.app.ui.RegisterDesktopWindowKeyDispatcher
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Component
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.RootPaneContainer
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private val desktopAppState = AtomicReference<AppState?>()
private val desktopShutdownStarted = AtomicBoolean(false)
private val desktopProcessExitScheduled = AtomicBoolean(false)

fun main(args: Array<String>) {
    configureDesktopApplicationName()
    configureDesktopApplicationIcon(isDebugBuild())
    configureSkiaGpuResourceCache()
    configureWindowsDesktopRendering()
    configureSandboxedNativeLibraries()
    if (runDesktopPlaybackSmokeIfRequested(args)) return

    installMacQuitHandler()
    applyMacDockIcon(isDebugBuild())
    application {
        PhoebeLog.d("Phoebe") { "desktop launched (debug=${isDebugBuild()})" }
        val windowState = rememberWindowState(width = 1480.dp, height = 880.dp)
        val isMacOs = isMacOs()
        val useCustomWindowsTitleBar = isWindows()
        // macOS bakes the squircle shape into app icons (unlike iOS/Android, which auto-mask),
        // so on Mac we use a pre-rounded variant. Other desktops keep the full-bleed square.
        val debugSuffix = if (isDebugBuild()) "-debug" else ""
        val iconResource = if (isMacOs) {
            "icon-macos$debugSuffix.png"
        } else {
            "icon$debugSuffix.png"
        }
        val iconImage = remember(iconResource) { loadDesktopResourceImage(iconResource) }
        val icon = remember(iconImage) { BitmapPainter(iconImage.toComposeImageBitmap()) }
        DisposableEffect(iconImage) {
            applyDesktopApplicationIcon(iconImage)
            onDispose {}
        }
        var useLightAppearance by remember { mutableStateOf(false) }
        var appState by remember { mutableStateOf<AppState?>(null) }
        val initialNavigationPath = remember { desktopInitialNavigationPath() }
        val closeApplication = {
            requestDesktopShutdown(appState)
            exitApplication()
            scheduleMacProcessExit()
        }
        Window(
            onCloseRequest = closeApplication,
            title = if (useCustomWindowsTitleBar) "" else appDisplayName(),
            state = windowState,
            icon = icon,
            undecorated = useCustomWindowsTitleBar,
            // Pairing transparent + undecorated avoids the Swing/Skiko layered-pane flicker
            // that shows up on Windows when hovering interactive elements.
            transparent = useCustomWindowsTitleBar,
        ) {
            DisposableEffect(window, iconImage) {
                window.iconImages = listOf(iconImage)
                onDispose {}
            }
            RegisterDesktopWindowKeyDispatcher(window)
            ApplyDesktopWindowChrome()
            CompositionLocalProvider(LocalDesktopMergesTitleBar provides useCustomWindowsTitleBar) {
                Box(Modifier.fillMaxSize()) {
                    App(
                        onAppearanceChange = { light ->
                            useLightAppearance = light
                            if (useCustomWindowsTitleBar) {
                                WindowsWindowChrome.apply(window, light)
                            }
                        },
                        onAppStateReady = {
                            appState = it
                            desktopAppState.set(it)
                        },
                        navigationPath = initialNavigationPath,
                    )
                    if (useCustomWindowsTitleBar) {
                        DesktopWindowTitleBar(
                            useLightAppearance = useLightAppearance,
                            onClose = closeApplication,
                        )
                    }
                }
            }
        }
    }
}

private fun desktopInitialNavigationPath(): String? =
    System.getProperty("phoebe.desktop.navigationPath")
        ?.takeIf { it.isNotBlank() }

private fun requestDesktopShutdown(appState: AppState?) {
    if (desktopShutdownStarted.compareAndSet(false, true)) {
        appState?.dispose()
    }
}

private fun installMacQuitHandler() {
    if (!isMacOs()) return
    runCatching {
        if (!Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) return
        desktop.setQuitHandler { _, response ->
            requestDesktopShutdown(desktopAppState.get())
            response.cancelQuit()
            scheduleMacProcessExit()
        }
    }.onFailure { error ->
        PhoebeLog.d("Phoebe") { "macOS quit handler install failed: ${error.message}" }
    }
}

private fun scheduleMacProcessExit() {
    if (!isMacOs()) return
    if (!desktopProcessExitScheduled.compareAndSet(false, true)) return

    thread(name = "Phoebe-mac-exit", isDaemon = false) {
        thread(name = "Phoebe-mac-exit-halt", isDaemon = true) {
            Thread.sleep(2_000L)
            Runtime.getRuntime().halt(0)
        }
        Thread.sleep(500L)
        exitProcess(0)
    }
}

private fun configureDesktopApplicationName() {
    val displayName = appDisplayName()
    System.setProperty("apple.awt.application.name", displayName)
    System.setProperty("com.apple.mrj.application.apple.menu.about.name", displayName)
}

/**
 * Sizes Skia's GPU resource cache from the largest attached display.
 *
 * The cache holds every texture and offscreen layer surface the renderer touches
 * in a frame. Compose nests a layer per `graphicsLayer`/clip, and each one is a
 * full-window RGBA8 surface — roughly 20 MB on a 3440x1440 screen. When the
 * budget can't hold one frame's working set, Skia evicts and re-uploads album art
 * on every frame, which pins a core or more during playback.
 *
 * Budgeting ~[SkiaGpuCacheFullScreenLayers] full-screen layers keeps a realistic
 * frame resident. Respects an explicit `-Dskiko.gpu.resourceCacheLimit` override.
 */
private fun configureSkiaGpuResourceCache() {
    if (System.getProperty("skiko.gpu.resourceCacheLimit") != null) return
    val limitBytes = runCatching {
        val screenBytes = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .screenDevices
            .maxOf { device ->
                val config = device.defaultConfiguration
                val bounds = config.bounds
                val transform = config.defaultTransform
                val widthPx = bounds.width * transform.scaleX
                val heightPx = bounds.height * transform.scaleY
                widthPx * heightPx * BytesPerPixel
            }
        (screenBytes * SkiaGpuCacheFullScreenLayers).toLong()
    }.getOrDefault(SkiaGpuCacheMinBytes)
        .coerceIn(SkiaGpuCacheMinBytes, SkiaGpuCacheMaxBytes)

    val limitMb = (limitBytes / (1024L * 1024L)).coerceAtLeast(1L)
    System.setProperty("skiko.gpu.resourceCacheLimit", "${limitMb}M")
    PhoebeLog.d("Phoebe") { "skia gpu resource cache limit: ${limitMb}M" }
}

private const val BytesPerPixel = 4.0
private const val SkiaGpuCacheFullScreenLayers = 16.0
private const val SkiaGpuCacheMinBytes = 128L * 1024L * 1024L
private const val SkiaGpuCacheMaxBytes = 512L * 1024L * 1024L

private fun configureDesktopApplicationIcon(debug: Boolean) {
    if (!isMacOs()) return
    val resourceName = if (debug) "icon-macos-debug.png" else "icon-macos.png"
    val iconFile = runCatching {
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resourceName)
            ?: return@runCatching null
        val tempFile = Files.createTempFile("phoebe-app-icon-", ".png")
        val file = tempFile.toFile().apply { deleteOnExit() }
        stream.use { Files.copy(it, tempFile, StandardCopyOption.REPLACE_EXISTING) }
        file
    }.onFailure { error ->
        PhoebeLog.d("Phoebe") { "macOS application icon setup failed: ${error.message}" }
    }.getOrNull() ?: return

    System.setProperty("apple.awt.application.icon", iconFile.absolutePath)
}

private fun loadDesktopResourceImage(resourcePath: String) =
    checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)) {
        "Missing desktop resource: $resourcePath"
    }.use { stream ->
        checkNotNull(ImageIO.read(stream)) {
            "Unable to decode desktop resource: $resourcePath"
        }
    }

private fun configureSandboxedNativeLibraries() {
    if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) return
    if (System.getProperty("jnativehook.lib.path") != null) return

    val cacheRoot = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("user.home")?.plus("/.cache")
        ?: return
    val cacheFolder = if (isDebugBuild()) "phoebe-debug" else "phoebe"
    val nativeLibDir = java.io.File(cacheRoot, "$cacheFolder/native").apply { mkdirs() }
    System.setProperty("jnativehook.lib.path", nativeLibDir.absolutePath)
}

private fun isMacOs(): Boolean =
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

/** macOS Dock icon comes from {@code -Xdock:icon} in packaged runs; override it for dev/debug. */
private fun applyMacDockIcon(debug: Boolean) {
    if (!isMacOs()) return
    val resourceName = if (debug) "icon-macos-debug.png" else "icon-macos.png"
    val dockImage = Thread.currentThread().contextClassLoader
        .getResourceAsStream(resourceName)
        ?.use(ImageIO::read)
        ?: return
    runCatching {
        val applicationClass = Class.forName("com.apple.eawt.Application")
        val application = applicationClass.getMethod("getApplication").invoke(null)
        applicationClass
            .getMethod("setDockIconImage", Image::class.java)
            .invoke(application, dockImage)
    }
}

private fun applyDesktopApplicationIcon(iconImage: Image) {
    runCatching {
        if (java.awt.Taskbar.isTaskbarSupported()) {
            java.awt.Taskbar.getTaskbar().iconImage = iconImage
        }
    }
    if (!isMacOs()) return
    runCatching {
        val applicationClass = Class.forName("com.apple.eawt.Application")
        val application = applicationClass.getMethod("getApplication").invoke(null)
        applicationClass
            .getMethod("setDockIconImage", Image::class.java)
            .invoke(application, iconImage)
    }
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

@Composable
private fun WindowScope.ApplyDesktopWindowChrome() {
    if (!isMacOs() && !isWindows()) return

    DisposableEffect(window) {
        MacWindowChrome.apply(window)
        if (isWindows()) {
            WindowsWindowChrome.apply(window, useLightAppearance = false)
            WindowsUndecoratedWindowSupport.install(window)
        }
        onDispose {}
    }
}

private object MacWindowChrome {
    fun apply(window: java.awt.Window) {
        if (!isMacOs()) return

        val rootPane = (window as? RootPaneContainer)?.rootPane
        rootPane?.putClientProperty("apple.awt.fullWindowContent", true)
        rootPane?.putClientProperty("apple.awt.transparentTitleBar", true)
        rootPane?.putClientProperty("apple.awt.windowTitleVisible", false)
    }
}

private object WindowsWindowChrome {
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19
    private const val DWMWA_BORDER_COLOR = 34

    private const val GA_ROOT = 2
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020

    private const val DARK_BORDER = 0xFF0A0D14.toInt()
    private const val LIGHT_BORDER = 0xFFE5E7EC.toInt()
    private const val MAX_APPLY_ATTEMPTS = 12

    fun apply(
        window: java.awt.Window,
        useLightAppearance: Boolean,
        attempt: Int = 0,
        deferred: Boolean = false,
    ) {
        if (!isWindows()) return

        if (!EventQueue.isDispatchThread()) {
            EventQueue.invokeLater { apply(window, useLightAppearance, attempt, deferred = true) }
            return
        }
        if (window.isShowing && !deferred) {
            EventQueue.invokeLater { apply(window, useLightAppearance, attempt, deferred = true) }
            return
        }
        if (!window.isShowing) {
            window.addWindowListener(object : WindowAdapter() {
                override fun windowOpened(event: WindowEvent) {
                    window.removeWindowListener(this)
                    apply(window, useLightAppearance, attempt)
                }
            })
            window.addHierarchyListener(object : HierarchyListener {
                override fun hierarchyChanged(event: HierarchyEvent) {
                    val showingChanged = event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L
                    if (!showingChanged || !window.isShowing) return
                    window.removeHierarchyListener(this)
                    apply(window, useLightAppearance, attempt)
                }
            })
            return
        }

        when (applyDwmAttributes(window, useLightAppearance)) {
            ApplyResult.Applied -> Unit
            ApplyResult.HwndNotReady -> {
                if (attempt < MAX_APPLY_ATTEMPTS) {
                    EventQueue.invokeLater {
                        apply(window, useLightAppearance, attempt + 1, deferred = true)
                    }
                }
            }
            ApplyResult.Failed -> Unit
        }
    }

    private enum class ApplyResult {
        Applied,
        HwndNotReady,
        Failed,
    }

    private fun applyDwmAttributes(window: java.awt.Window, useLightAppearance: Boolean): ApplyResult {
        val hwnd = resolveHwnd(window) ?: return ApplyResult.HwndNotReady
        return runCatching {
            val border = if (useLightAppearance) LIGHT_BORDER else DARK_BORDER
            val useDarkChrome = !useLightAppearance
            setBooleanAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, useDarkChrome)
            setBooleanAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1, useDarkChrome)
            setColorAttribute(hwnd, DWMWA_BORDER_COLOR, border)
            refreshWindowFrame(hwnd)
            ApplyResult.Applied
        }.onFailure { error ->
            PhoebeLog.d("Phoebe") { "Windows window chrome unavailable: ${error.message}" }
        }.getOrDefault(ApplyResult.Failed)
    }

    private fun resolveHwnd(window: java.awt.Window): Pointer? {
        val componentPointer = runCatching { Native.getComponentPointer(window) }
            .getOrNull()
            ?.takeIf { it != Pointer.NULL }
        if (componentPointer != null) {
            return rootHwnd(componentPointer)
        }
        return awtPeerHwnd(window)?.let(::rootHwnd)
    }

    private fun rootHwnd(hwnd: Pointer): Pointer {
        val root = runCatching { WinUser32.INSTANCE.GetAncestor(hwnd, GA_ROOT) }.getOrNull()
        return root?.takeIf { it != Pointer.NULL } ?: hwnd
    }

    private fun awtPeerHwnd(window: java.awt.Window): Pointer? = runCatching {
        val peerField = Component::class.java.getDeclaredField("peer")
        peerField.isAccessible = true
        val peer = peerField.get(window) ?: return@runCatching null
        val hwndMethod = peer.javaClass.methods.firstOrNull { method ->
            method.name == "getHWnd" && method.parameterCount == 0
        } ?: return@runCatching null
        val hwnd = (hwndMethod.invoke(peer) as Number).toLong()
        if (hwnd == 0L) null else Pointer(hwnd)
    }.getOrNull()

    private fun refreshWindowFrame(hwnd: Pointer) {
        runCatching {
            WinUser32.INSTANCE.SetWindowPos(
                hwnd,
                null,
                0,
                0,
                0,
                0,
                SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED,
            )
        }
    }

    private fun setBooleanAttribute(hwnd: Pointer, attribute: Int, enabled: Boolean): Int {
        val value = intArrayOf(if (enabled) 1 else 0)
        return DwmApi.INSTANCE.DwmSetWindowAttribute(hwnd, attribute, value, Int.SIZE_BYTES)
    }

    private fun setColorAttribute(hwnd: Pointer, attribute: Int, argb: Int): Int {
        val value = intArrayOf(argb.toColorRef())
        return DwmApi.INSTANCE.DwmSetWindowAttribute(hwnd, attribute, value, Int.SIZE_BYTES)
    }

    private fun Int.toColorRef(): Int {
        val red = this shr 16 and 0xFF
        val green = this shr 8 and 0xFF
        val blue = this and 0xFF
        return red or (green shl 8) or (blue shl 16)
    }

    private interface DwmApi : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntArray, size: Int): Int

        companion object {
            val INSTANCE: DwmApi = Native.load("dwmapi", DwmApi::class.java)
        }
    }

    private interface WinUser32 : Library {
        fun GetAncestor(hwnd: Pointer, flags: Int): Pointer
        fun SetWindowPos(
            hwnd: Pointer,
            hwndInsertAfter: Pointer?,
            x: Int,
            y: Int,
            cx: Int,
            cy: Int,
            flags: Int,
        ): Boolean

        companion object {
            val INSTANCE: WinUser32 = Native.load("user32", WinUser32::class.java)
        }
    }
}
