package com.phoebe.app.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual object PhoebeDispatchers {
    // Kotlin/Wasm has no separate IO dispatcher; keep the common API portable.
    actual val io: CoroutineDispatcher = Dispatchers.Default
}

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size) { kotlin.js.Math.random().times(256).toInt().toByte() }
