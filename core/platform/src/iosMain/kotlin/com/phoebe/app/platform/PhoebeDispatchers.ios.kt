package com.phoebe.app.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual object PhoebeDispatchers {
    // Kotlin/Native does not expose a separate IO dispatcher in this artifact.
    actual val io: CoroutineDispatcher = Dispatchers.Default
}
