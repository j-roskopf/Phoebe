package com.phoebe.app.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual object PhoebeDispatchers {
    // Kotlin/Wasm has no separate IO dispatcher; keep the common API portable.
    actual val io: CoroutineDispatcher = Dispatchers.Default
}

@JsFun("(length) => Array.from(crypto.getRandomValues(new Uint8Array(length))).join(',')")
private external fun secureRandomValuesJs(length: Int): String

actual fun secureRandomBytes(size: Int): ByteArray =
    secureRandomValuesJs(size).split(',').map { it.toInt().toByte() }.toByteArray()
