package com.phoebe.app.platform

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberPickVisualizerPresetFiles(
    onPicked: (List<PickedVisualizerPresetFile>) -> Unit,
): () -> Unit {
    val activity = checkNotNull(LocalActivity.current) {
        "rememberPickVisualizerPresetFiles must be hosted in an Activity."
    }
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) {
            onPicked(emptyList())
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val name = queryDisplayName(activity, uri) ?: uri.lastPathSegment ?: "preset"
                    val lower = name.lowercase()
                    if (!lower.endsWith(".milk") && !lower.endsWith(".json")) return@mapNotNull null
                    val text = activity.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: return@mapNotNull null
                    PickedVisualizerPresetFile(fileName = name, text = text)
                }
            }
            onPicked(files)
        }
    }
    return remember(launcher) {
        {
            launcher.launch(arrayOf("*/*"))
        }
    }
}

private fun queryDisplayName(activity: android.app.Activity, uri: Uri): String? {
    val cursor = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?: return null
    cursor.use {
        if (!it.moveToFirst()) return null
        val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index < 0) return null
        return it.getString(index)
    }
}
