@file:OptIn(ExperimentalWasmJsInterop::class)

package com.phoebe.app.feature.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

/**
 * Butterchurn WebGL2 host for wasmJs.
 *
 * Skiko owns an opaque full-window canvas, so Butterchurn is an HTML overlay
 * (same pattern as the radio map iframe) positioned over the Compose slot.
 *
 * Important: createVisualizer must run only after the canvas has a non-zero CSS
 * size — a 0×0 WebGL context stays black even after later setRendererSize calls.
 */
@Composable
actual fun ProjectMVisualizerHost(
    presetPath: String?,
    presetData: String?,
    locked: Boolean,
    isPlaying: Boolean,
    modifier: Modifier,
    suspendRendering: Boolean,
) {
    val hostId = remember { butterchurnNewHostId() }
    var bounds by remember { mutableStateOf(ButterchurnBounds()) }

    DisposableEffect(hostId) {
        onDispose { butterchurnStop(hostId) }
    }

    LaunchedEffect(hostId, bounds.x, bounds.y, bounds.width, bounds.height) {
        if (bounds.width <= 1f || bounds.height <= 1f) return@LaunchedEffect
        butterchurnEnsureStarted(
            hostId,
            bounds.x,
            bounds.y,
            bounds.width,
            bounds.height,
        )
        // Menu may already be open when the canvas is created.
        if (VisualizerHtmlOverlayGate.isSuppressed) {
            setVisualizerHtmlOverlaysSuppressed(true)
        }
    }

    LaunchedEffect(
        hostId,
        presetData,
        presetPath,
        locked,
        isPlaying,
        bounds.width,
        bounds.height,
    ) {
        if (bounds.width <= 1f || bounds.height <= 1f) return@LaunchedEffect
        butterchurnEnsureStarted(
            hostId,
            bounds.x,
            bounds.y,
            bounds.width,
            bounds.height,
        )
        butterchurnLoadPreset(hostId, presetData, presetPath, locked, isPlaying)
    }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                val size = coordinates.size
                bounds = ButterchurnBounds(
                    x = position.x,
                    y = position.y,
                    width = size.width.toFloat(),
                    height = size.height.toFloat(),
                )
            },
    )
}

private data class ButterchurnBounds(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
)

@JsFun("() => 'phoebe-butterchurn-' + Math.floor(Math.random() * 1e9)")
private external fun butterchurnNewHostId(): String

@JsFun(
    """
    (hostId, x, y, width, height) => {
      try {
        const g = globalThis;
        g.__phoebeButterchurn = g.__phoebeButterchurn || {};
        const dpr = window.devicePixelRatio || 1;
        const cssW = Math.max(2, Math.round((Number(width) || 0) / dpr));
        const cssH = Math.max(2, Math.round((Number(height) || 0) / dpr));
        const cssX = Math.max(0, (Number(x) || 0) / dpr);
        const cssY = Math.max(0, (Number(y) || 0) / dpr);

        let canvas = document.getElementById(hostId);
        if (!canvas) {
          canvas = document.createElement('canvas');
          canvas.id = hostId;
          canvas.style.position = 'fixed';
          canvas.style.zIndex = '10';
          canvas.style.pointerEvents = 'none';
          canvas.style.border = '0';
          canvas.style.background = '#000';
          document.body.appendChild(canvas);
        }
        canvas.style.left = cssX + 'px';
        canvas.style.top = cssY + 'px';
        canvas.style.width = cssW + 'px';
        canvas.style.height = cssH + 'px';
        canvas.width = Math.max(2, Math.round(cssW * dpr));
        canvas.height = Math.max(2, Math.round(cssH * dpr));

        const existing = g.__phoebeButterchurn[hostId];
        if (existing && existing.visualizer) {
          if (typeof existing.visualizer.setRendererSize === 'function') {
            existing.visualizer.setRendererSize(cssW, cssH);
          }
          return;
        }

        const Butterchurn = g.butterchurn && (g.butterchurn.default || g.butterchurn);
        if (!Butterchurn || typeof Butterchurn.createVisualizer !== 'function') {
          console.warn('butterchurn missing createVisualizer', g.butterchurn);
          return;
        }

        const AudioCtx = window.AudioContext || window.webkitAudioContext;
        if (!AudioCtx) {
          console.warn('butterchurn: AudioContext unavailable');
          return;
        }
        const eq = g.__phoebeEqualizer;
        const audioCtx = (eq && eq.context) || g.__phoebeAudioCtx || new AudioCtx();
        if (!g.__phoebeAudioCtx) g.__phoebeAudioCtx = audioCtx;
        const resume = () => {
          try { audioCtx.resume && audioCtx.resume(); } catch (e) {}
        };
        resume();
        window.addEventListener('pointerdown', resume, { once: false });

        const visualizer = Butterchurn.createVisualizer(audioCtx, canvas, {
          width: cssW,
          height: cssH,
          pixelRatio: dpr
        });
        const entry = { visualizer: visualizer, playing: false, connectedAudio: null };
        g.__phoebeButterchurn[hostId] = entry;

        const connectRealAudio = () => {
          if (typeof visualizer.connectAudio !== 'function') return;
          try {
            let liveEq = g.__phoebeEqualizer;
            const audio = g.__phoebeActiveAudio;
            if (!audio) return;
            if (entry.connectedAudio === audio) return;
            // Build the same MediaElementSource → gain graph the equalizer uses, so
            // playback keeps working and Butterchurn can tap real media PCM.
            if (!liveEq || liveEq.audio !== audio || !liveEq.gain) {
              const Ctx = window.AudioContext || window.webkitAudioContext;
              if (!Ctx) return;
              const context = (liveEq && liveEq.context) || g.__phoebeAudioCtx || new Ctx();
              if (!g.__phoebeAudioCtx) g.__phoebeAudioCtx = context;
              let source = liveEq && liveEq.audio === audio ? liveEq.source : null;
              if (!source) {
                try {
                  source = context.createMediaElementSource(audio);
                } catch (e) {
                  return;
                }
              }
              const gain = context.createGain();
              gain.gain.value = (liveEq && liveEq.audio === audio && Number.isFinite(liveEq.gainValue))
                ? liveEq.gainValue
                : 1;
              try { source.disconnect(); } catch (e) {}
              source.connect(gain);
              gain.connect(context.destination);
              liveEq = {
                audio: audio,
                context: context,
                source: source,
                nodes: [],
                gain: gain,
                gainValue: gain.gain.value
              };
              g.__phoebeEqualizer = liveEq;
            }
            try { liveEq.context && liveEq.context.resume && liveEq.context.resume(); } catch (e) {}
            visualizer.connectAudio(liveEq.gain);
            entry.connectedAudio = audio;
          } catch (e) {
            console.warn('butterchurn connectAudio failed', e);
          }
        };
        connectRealAudio();
        entry.connectRealAudio = connectRealAudio;

        const loop = () => {
          const current = g.__phoebeButterchurn[hostId];
          if (!current || !current.visualizer) return;
          if (current.playing) {
            try { current.connectRealAudio && current.connectRealAudio(); } catch (e) {}
            try { current.visualizer.render(); } catch (e) {}
          }
          requestAnimationFrame(loop);
        };
        requestAnimationFrame(loop);
      } catch (e) {
        console.warn('butterchurn start failed', e);
      }
    }
    """,
)
private external fun butterchurnEnsureStarted(
    hostId: String,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
)

@JsFun(
    """
    (hostId) => {
      try {
        const g = globalThis;
        if (g.__phoebeButterchurn) delete g.__phoebeButterchurn[hostId];
        const el = document.getElementById(hostId);
        if (el) el.remove();
      } catch (e) {}
    }
    """,
)
private external fun butterchurnStop(hostId: String)

@JsFun(
    """
    (hostId, presetData, presetPath, locked, isPlaying) => {
      try {
        const g = globalThis;
        const entry = g.__phoebeButterchurn && g.__phoebeButterchurn[hostId];
        if (!entry || !entry.visualizer) return;
        const v = entry.visualizer;
        entry.playing = !!isPlaying;
        if (entry.playing) {
          try { entry.connectRealAudio && entry.connectRealAudio(); } catch (e) {}
        }
        const blend = locked ? 0 : 2.0;
        const apply = (obj) => {
          try { v.loadPreset(obj, blend); } catch (e) {
            console.warn('butterchurn loadPreset apply failed', e);
          }
        };
        if (presetData && typeof presetData === 'string' && presetData.trim().startsWith('{')) {
          apply(JSON.parse(presetData));
          return;
        }
        const path = (presetPath && String(presetPath).trim()) || '';
        if (path) {
          const url = path.startsWith('/') || path.startsWith('http') ? path : ('/' + path);
          fetch(url).then((r) => {
            if (!r.ok) throw new Error('HTTP ' + r.status + ' for ' + url);
            return r.text();
          }).then((text) => {
            apply(JSON.parse(text));
          }).catch((e) => {
            console.warn('butterchurn fetch preset failed', e);
          });
          return;
        }
        const packsApi = g.butterchurnPresets && (g.butterchurnPresets.default || g.butterchurnPresets);
        let packs = null;
        if (typeof packsApi === 'function') {
          packs = packsApi();
        } else if (packsApi && typeof packsApi.getPresets === 'function') {
          packs = packsApi.getPresets();
        }
        if (packs) {
          const keys = Object.keys(packs);
          if (keys.length) {
            const preferred = keys.find((k) => /geiss|flexi|martin/i.test(k)) || keys[0];
            apply(packs[preferred]);
          }
        }
      } catch (e) {
        console.warn('butterchurn loadPreset failed', e);
      }
    }
    """,
)
private external fun butterchurnLoadPreset(
    hostId: String,
    presetData: String?,
    presetPath: String?,
    locked: Boolean,
    isPlaying: Boolean,
)
