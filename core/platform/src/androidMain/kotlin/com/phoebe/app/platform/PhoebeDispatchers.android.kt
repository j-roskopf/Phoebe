package com.phoebe.app.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual object PhoebeDispatchers {
    actual val io: CoroutineDispatcher = Dispatchers.IO
}

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also {
    java.security.SecureRandom().nextBytes(it)
}
