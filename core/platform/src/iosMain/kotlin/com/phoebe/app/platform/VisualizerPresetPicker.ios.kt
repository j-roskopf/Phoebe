package com.phoebe.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.posix.memcpy
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import kotlin.coroutines.resume

@Composable
actual fun rememberPickVisualizerPresetFiles(
    onPicked: (List<PickedVisualizerPresetFile>) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    return remember(onPicked) {
        {
            scope.launch {
                onPicked(pickVisualizerPresetsIos())
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private suspend fun pickVisualizerPresetsIos(): List<PickedVisualizerPresetFile> =
    suspendCancellableCoroutine { cont ->
        val root = topViewController()
        if (root == null) {
            cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }
        val types = listOfNotNull(
            UTType.typeWithFilenameExtension("milk"),
            UTType.typeWithFilenameExtension("json"),
            UTType.typeWithIdentifier("public.json"),
            UTType.typeWithIdentifier("public.plain-text"),
        )
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
        picker.allowsMultipleSelection = true
        val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(
                controller: UIDocumentPickerViewController,
                didPickDocumentsAtURLs: List<*>,
            ) {
                associatedDelegates.remove(controller.hashCode())
                val files = didPickDocumentsAtURLs.mapNotNull { any ->
                    val url = any as? NSURL ?: return@mapNotNull null
                    val name = url.lastPathComponent ?: "preset"
                    val lower = name.lowercase()
                    if (!lower.endsWith(".milk") && !lower.endsWith(".json")) return@mapNotNull null
                    val text = NSData.create(contentsOfURL = url)?.toUtf8String()
                        ?: return@mapNotNull null
                    PickedVisualizerPresetFile(fileName = name, text = text)
                }
                cont.resume(files)
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                associatedDelegates.remove(controller.hashCode())
                cont.resume(emptyList())
            }
        }
        associatedDelegates[picker.hashCode()] = delegate
        picker.delegate = delegate
        root.presentViewController(picker, animated = true, completion = null)
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toUtf8String(): String {
    val bytes = ByteArray(length.toInt())
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, length)
    }
    return bytes.decodeToString()
}

private fun topViewController(): UIViewController? {
    val app = UIApplication.sharedApplication
    val window = app.keyWindow
        ?: (app.windows.firstOrNull() as? platform.UIKit.UIWindow)
    var current = window?.rootViewController
    while (current?.presentedViewController != null) {
        current = current.presentedViewController
    }
    return current
}

private val associatedDelegates = mutableMapOf<Int, Any>()
