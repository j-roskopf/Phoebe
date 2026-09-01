package com.phoebe.app.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual object PhoebeDispatchers {
    // Kotlin/Native does not expose a separate IO dispatcher in this artifact.
    actual val io: CoroutineDispatcher = Dispatchers.Default
}

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also {
    platform.Security.SecRandomCopyBytes(null, size.toULong(), it.refTo(0))
}
