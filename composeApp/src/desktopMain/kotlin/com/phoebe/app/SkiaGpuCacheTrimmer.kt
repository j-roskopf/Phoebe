package com.phoebe.app

import com.phoebe.app.platform.PhoebeLog
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.Timer
import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.SkiaLayer

/**
 * Empties Skia's GPU resource cache while the window is minimized or has sat in the
 * background for [BackgroundTrimDelayMs].
 *
 * Skia fills whatever budget `skiko.gpu.resourceCacheLimit` allows and never trims
 * below it, so a hidden window otherwise keeps the whole budget resident. Returning
 * costs a few frames of texture re-uploads.
 *
 * Skiko does not expose its [DirectContext], so this reaches it by reflection and
 * takes the redrawer's `drawLock` (the lock every frame renders under) to keep the
 * purge off a frame in flight. Any lookup failure disables trimming for this window.
 *
 * @return a disposer that removes the listeners.
 */
internal fun installSkiaGpuCacheTrimmer(window: Window): () -> Unit {
    val trimAfterBackground = Timer(BackgroundTrimDelayMs) { trimSkiaGpuCache(window, "background") }
        .apply { isRepeats = false }
    val listener = object : WindowAdapter() {
        override fun windowIconified(event: WindowEvent) {
            trimAfterBackground.stop()
            trimSkiaGpuCache(window, "minimized")
        }

        override fun windowDeactivated(event: WindowEvent) {
            trimAfterBackground.restart()
        }

        override fun windowActivated(event: WindowEvent) {
            trimAfterBackground.stop()
        }
    }
    window.addWindowListener(listener)
    return {
        trimAfterBackground.stop()
        window.removeWindowListener(listener)
    }
}

private fun trimSkiaGpuCache(window: Window, reason: String) {
    val target = runCatching { findSkiaGpuContext(window) }
        .onFailure { error -> PhoebeLog.d("Phoebe") { "skia cache trim unavailable: ${error.message}" } }
        .getOrNull() ?: return
    synchronized(target.drawLock) {
        val budget = target.context.resourceCacheLimit
        // Shrinking the budget purges every unlocked resource; restoring it
        // allocates nothing until the next frame needs textures again.
        target.context.resourceCacheLimit = 0L
        target.context.resourceCacheLimit = budget
    }
    PhoebeLog.d("Phoebe") { "skia gpu cache trimmed ($reason)" }
}

private class SkiaGpuContext(val context: DirectContext, val drawLock: Any)

private fun findSkiaGpuContext(window: Window): SkiaGpuContext? {
    val layer = findSkiaLayer(window) ?: return null
    val redrawer = SkiaLayer::class.java.getMethod("getRedrawer\$skiko").invoke(layer) ?: return null
    val drawLock = redrawer.fieldValue("drawLock") ?: return null
    val handler = redrawer.fieldValue("contextHandler") ?: return null
    val getContext = generateSequence<Class<*>>(handler.javaClass) { it.superclass }
        .firstNotNullOfOrNull { type -> type.declaredMethods.firstOrNull { it.name == "getContext" } }
        ?: return null
    getContext.isAccessible = true
    val context = getContext.invoke(handler) as? DirectContext ?: return null
    return SkiaGpuContext(context, drawLock)
}

private fun Any.fieldValue(name: String): Any? =
    runCatching { javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) }.getOrNull()

private fun findSkiaLayer(component: Component): SkiaLayer? {
    if (component is SkiaLayer) return component
    if (component !is Container) return null
    return component.components.firstNotNullOfOrNull(::findSkiaLayer)
}

private const val BackgroundTrimDelayMs = 30_000
