package com.phoebe.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual object PhoebeDispatchers {
    // Kotlin/Native does not expose a separate IO dispatcher in this artifact.
    actual val io: CoroutineDispatcher = Dispatchers.Default
}

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { bytes ->
    bytes.usePinned { pinned ->
        platform.Security.SecRandomCopyBytes(null, size.toULong(), pinned.addressOf(0))
    }
}
