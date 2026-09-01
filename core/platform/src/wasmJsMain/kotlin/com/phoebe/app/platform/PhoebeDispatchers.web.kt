package com.phoebe.app.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual object PhoebeDispatchers {
    // Kotlin/Wasm has no separate IO dispatcher; keep the common API portable.
    actual val io: CoroutineDispatcher = Dispatchers.Default
}
