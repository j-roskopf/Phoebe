@file:OptIn(ExperimentalWasmJsInterop::class)

package com.phoebe.app.feature.playback

internal actual fun setVisualizerHtmlOverlaysSuppressed(suppressed: Boolean) {
    butterchurnSetAllOverlaysSuppressed(suppressed)
}

@JsFun(
    """
    (suppressed) => {
      try {
        const hide = !!suppressed;
        document.querySelectorAll('canvas[id^="phoebe-butterchurn-"]').forEach((canvas) => {
          canvas.style.display = hide ? 'none' : 'block';
          canvas.style.visibility = hide ? 'hidden' : 'visible';
          canvas.style.pointerEvents = 'none';
          if (hide) {
            canvas.style.zIndex = '0';
          } else {
            canvas.style.zIndex = '10';
          }
        });
      } catch (e) {}
    }
    """,
)
private external fun butterchurnSetAllOverlaysSuppressed(suppressed: Boolean)
