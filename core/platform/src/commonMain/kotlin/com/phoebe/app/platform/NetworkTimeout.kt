package com.phoebe.app.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wall-clock budget for a network call.
 *
 * Deliberately not a bare [withTimeoutOrNull]: connect/read budgets are measured against the
 * real clock, and a plain [withTimeoutOrNull] inherits whatever [kotlinx.coroutines.Delay] is
 * in the caller's context. Under `runTest` that is the virtual-time scheduler, which skips
 * straight to the deadline and reports every mocked request as a timeout.
 */
suspend fun <T> withNetworkTimeoutOrNull(timeoutMs: Long, block: suspend () -> T): T? =
    withContext(Dispatchers.Default) {
        withTimeoutOrNull(timeoutMs) { block() }
    }
