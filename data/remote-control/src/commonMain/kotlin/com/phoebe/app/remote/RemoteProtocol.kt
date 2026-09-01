package com.phoebe.app.remote

import com.phoebe.app.domain.PlayerState
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val REMOTE_PROTOCOL_VERSION = 1
const val DEFAULT_REMOTE_TCP_PORT = 8765
const val DEFAULT_REMOTE_DISCOVERY_PORT = 8766

val RemoteJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

@Serializable
sealed interface RemoteFrame {

    // Client -> Host
    @Serializable
    @SerialName("hello")
    data class Hello(
        val deviceId: String,
        val deviceName: String,
        val protocolVersion: Int = REMOTE_PROTOCOL_VERSION,
        /** Stable identity of the signed-in account on this device (provider + user + server). */
        val accountId: String? = null,
    ) : RemoteFrame

    @Serializable
    @SerialName("auth_response")
    data class AuthResponse(
        val mac: String,
    ) : RemoteFrame

    @Serializable
    @SerialName("command")
    data class Command(
        val command: RemoteCommand,
    ) : RemoteFrame

    @Serializable
    @SerialName("ping")
    data object Ping : RemoteFrame

    // Host -> Client
    @Serializable
    @SerialName("challenge")
    data class Challenge(
        val nonce: String,
        val hostName: String,
        val hostDeviceId: String,
    ) : RemoteFrame

    @Serializable
    @SerialName("auth_result")
    data class AuthResult(
        val success: Boolean,
        val pairingSecret: String? = null,
        val message: String? = null,
        /** True when the host detected the connecting client is signed into the same account. */
        val sameAccount: Boolean = false,
    ) : RemoteFrame

    @Serializable
    @SerialName("awaiting_approval")
    data class AwaitingApproval(
        val hostName: String,
        val hostDeviceId: String,
    ) : RemoteFrame

    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        val snapshot: RemoteSnapshot,
    ) : RemoteFrame

    @Serializable
    @SerialName("position_tick")
    data class PositionTick(
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean,
    ) : RemoteFrame

    @Serializable
    @SerialName("pong")
    data object Pong : RemoteFrame

    @Serializable
    @SerialName("bye")
    data class Bye(
        val reason: String,
    ) : RemoteFrame
}

@Serializable
sealed interface RemoteCommand {
    @Serializable
    @SerialName("toggle_play_pause")
    data object TogglePlayPause : RemoteCommand

    @Serializable
    @SerialName("next")
    data object Next : RemoteCommand

    @Serializable
    @SerialName("previous")
    data object Previous : RemoteCommand

    @Serializable
    @SerialName("seek_to")
    data class SeekTo(val positionMs: Long) : RemoteCommand

    @Serializable
    @SerialName("set_volume")
    data class SetVolume(val volume: Float) : RemoteCommand

    @Serializable
    @SerialName("jump_to_index")
    data class JumpToIndex(val index: Int) : RemoteCommand

    @Serializable
    @SerialName("set_shuffle")
    data class SetShuffle(val shuffle: Boolean) : RemoteCommand

    @Serializable
    @SerialName("set_repeat")
    data class SetRepeat(val repeat: RepeatMode) : RemoteCommand

    /** Replaces the host's entire queue and starts playback, sent by a same-account controller. */
    @Serializable
    @SerialName("replace_queue")
    data class ReplaceQueue(
        val tracks: List<Track>,
        val startIndex: Int,
        val shuffle: Boolean = false,
    ) : RemoteCommand

    /** Appends tracks to the end of the host's current queue without interrupting playback. */
    @Serializable
    @SerialName("append_to_queue")
    data class AppendToQueue(val tracks: List<Track>) : RemoteCommand

    /** Inserts a track immediately after the host's current track ("Up Next"). */
    @Serializable
    @SerialName("insert_next")
    data class InsertNext(val track: Track) : RemoteCommand
}

@Serializable
data class RemoteSnapshot(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val volume: Float = 1f,
    val hostName: String = "",
) {
    val currentTrack: Track?
        get() = queue.getOrNull(currentIndex)

    fun isSameStructuralState(other: RemoteSnapshot): Boolean =
        currentIndex == other.currentIndex &&
        isPlaying == other.isPlaying &&
        isBuffering == other.isBuffering &&
        durationMs == other.durationMs &&
        shuffle == other.shuffle &&
        repeat == other.repeat &&
        volume == other.volume &&
        hostName == other.hostName &&
        queue == other.queue
}

/**
 * Strips sensitive local fields and server tokens from tracks before sending over the wire.
 */
fun Track.sanitizeForRemote(): Track = copy(
    streamUrl = "",
    downloadUrl = "",
    localArtworkUri = null,
    localUri = null,
    filepath = null,
    playbackFallbackUrls = emptyList(),
    thumbUrl = null,
)

fun List<Track>.sanitizeForRemote(): List<Track> = map { it.sanitizeForRemote() }

fun RemoteSnapshot.toPlayerState(fallback: PlayerState = PlayerState()): PlayerState =
    fallback.copy(
        queue = queue,
        currentIndex = currentIndex,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        positionMs = positionMs,
        durationMs = durationMs.takeIf { it > 0L } ?: currentTrack?.durationMs ?: fallback.durationMs,
        shuffle = shuffle,
        repeat = repeat,
        volume = volume,
    )
