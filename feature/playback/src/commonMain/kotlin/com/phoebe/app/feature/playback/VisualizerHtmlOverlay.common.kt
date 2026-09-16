package com.phoebe.app.feature.playback

/**
 * Hides HTML visualizer overlays (Butterchurn canvases) so Compose popups are
 * not covered. No-op on native targets.
 */
internal expect fun setVisualizerHtmlOverlaysSuppressed(suppressed: Boolean)
