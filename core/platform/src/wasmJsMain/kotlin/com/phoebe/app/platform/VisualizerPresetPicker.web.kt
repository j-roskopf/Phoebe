@file:OptIn(ExperimentalWasmJsInterop::class)

package com.phoebe.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberPickVisualizerPresetFiles(
    onPicked: (List<PickedVisualizerPresetFile>) -> Unit,
): () -> Unit =
    remember(onPicked) {
        {
            webPickVisualizerPresetFiles { encoded ->
                onPicked(decodePickedVisualizerFiles(encoded))
            }
        }
    }

/**
 * Encodes picked files as `name\\u0001text\\u0000name\\u0001text…` so wasm JS interop
 * only needs a single string callback parameter.
 */
private fun decodePickedVisualizerFiles(encoded: String): List<PickedVisualizerPresetFile> {
    if (encoded.isEmpty()) return emptyList()
    return encoded.split('\u0000').mapNotNull { entry ->
        if (entry.isEmpty()) return@mapNotNull null
        val name = entry.substringBefore('\u0001', missingDelimiterValue = "")
        val text = entry.substringAfter('\u0001', missingDelimiterValue = "")
        if (name.isEmpty() || text.isEmpty()) return@mapNotNull null
        PickedVisualizerPresetFile(fileName = name, text = text)
    }
}

@JsFun(
    """
    (onDone) => {
      try {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = '.milk,.json,application/json,text/plain';
        input.multiple = true;
        input.style.display = 'none';
        document.body.appendChild(input);
        input.onchange = async () => {
          const files = Array.from(input.files || []);
          document.body.removeChild(input);
          const parts = [];
          for (const file of files) {
            const name = file.name || 'preset';
            const lower = name.toLowerCase();
            if (!lower.endsWith('.milk') && !lower.endsWith('.json')) continue;
            try {
              const text = await file.text();
              parts.push(name + '\u0001' + text);
            } catch (e) {}
          }
          onDone(parts.join('\u0000'));
        };
        input.click();
      } catch (e) {
        onDone('');
      }
    }
    """,
)
private external fun webPickVisualizerPresetFiles(onDone: (String) -> Unit)
