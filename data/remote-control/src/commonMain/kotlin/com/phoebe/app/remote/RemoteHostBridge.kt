package com.phoebe.app.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface RemoteHostBridge {
    val snapshotFlow: Flow<RemoteSnapshot>
    val currentSnapshot: RemoteSnapshot
    val isPlaying: Boolean
    val positionMs: Long
    val durationMs: Long

    suspend fun execute(command: RemoteCommand)
}
