package com.phoebe.app.remote

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RemoteConnection(
    val socket: Socket,
    val readChannel: ByteReadChannel = socket.openReadChannel(),
    val writeChannel: ByteWriteChannel = socket.openWriteChannel(autoFlush = false),
) {
    private val sendMutex = Mutex()
    private var closed = false

    val isClosed: Boolean
        get() = closed || readChannel.isClosedForRead || writeChannel.isClosedForWrite

    suspend fun send(frame: RemoteFrame) {
        if (isClosed) return
        val json = RemoteJson.encodeToString(RemoteFrame.serializer(), frame)
        withContext(NonCancellable) {
            sendMutex.withLock {
                try {
                    writeChannel.writeStringUtf8(json + "\n")
                    writeChannel.flush()
                } catch (e: Throwable) {
                    close()
                    if (e is CancellationException) throw e
                    throw e
                }
            }
        }
    }

    suspend fun receive(): RemoteFrame? {
        while (!isClosed) {
            try {
                val line = readChannel.readUTF8Line() ?: run {
                    close()
                    return null
                }
                if (line.isBlank()) continue
                return try {
                    RemoteJson.decodeFromString(RemoteFrame.serializer(), line)
                } catch (e: Throwable) {
                    com.phoebe.app.platform.PhoebeLog.d("RemoteConnection", "Failed to decode frame from line (length=${line.length}): ${e.message}")
                    continue
                }
            } catch (e: Throwable) {
                close()
                if (e is CancellationException) throw e
                return null
            }
        }
        return null
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }
}
