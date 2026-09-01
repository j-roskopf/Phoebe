package com.phoebe.app.player

import com.phoebe.app.data.SessionRepository
import com.phoebe.app.domain.PlayerState
import com.phoebe.app.domain.Track
import com.phoebe.app.remote.RemoteCommand
import com.phoebe.app.remote.RemoteHostBridge
import com.phoebe.app.remote.RemoteSnapshot
import com.phoebe.app.remote.sanitizeForRemote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class PlaybackRemoteHostBridge(
    private val audioPlayer: AudioPlayer,
    private val sessionRepository: SessionRepository,
    private val hostNameProvider: () -> String = { "" },
) : RemoteHostBridge {

    /** Re-resolves tracks pushed by a same-account controller against this host's own session. */
    private fun resolveIncomingQueue(tracks: List<Track>): List<Track> =
        tracks.withFreshPlaybackUrls(sessionRepository.session.value)

    override val snapshotFlow: Flow<RemoteSnapshot> =
        audioPlayer.state.map { it.toRemoteSnapshot(hostNameProvider()) }
            .distinctUntilChanged { old, new -> old.isSameStructuralState(new) }

    override val currentSnapshot: RemoteSnapshot
        get() = audioPlayer.state.value.toRemoteSnapshot(hostNameProvider())

    override val isPlaying: Boolean
        get() = audioPlayer.state.value.isPlaying

    override val positionMs: Long
        get() = audioPlayer.state.value.positionMs

    override val durationMs: Long
        get() = audioPlayer.state.value.durationMs

    override suspend fun execute(command: RemoteCommand) {
        com.phoebe.app.platform.PhoebeLog.d("RemoteHostBridge", "Bridge executing command: $command on audioPlayer")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            when (command) {
                is RemoteCommand.TogglePlayPause -> audioPlayer.togglePlayPause()
                is RemoteCommand.Next -> audioPlayer.next()
                is RemoteCommand.Previous -> audioPlayer.previous()
                is RemoteCommand.SeekTo -> audioPlayer.seekTo(command.positionMs)
                is RemoteCommand.SetVolume -> audioPlayer.setVolume(command.volume)
                is RemoteCommand.JumpToIndex -> {
                    val queue = audioPlayer.state.value.queue
                    if (command.index in queue.indices) {
                        audioPlayer.play(queue, command.index)
                    }
                }
                is RemoteCommand.SetShuffle -> audioPlayer.setShuffle(command.shuffle)
                is RemoteCommand.SetRepeat -> audioPlayer.setRepeat(command.repeat)
                is RemoteCommand.ReplaceQueue -> {
                    val resolved = resolveIncomingQueue(command.tracks)
                    if (resolved.isNotEmpty()) {
                        val startIndex = command.startIndex.coerceIn(resolved.indices)
                        if (command.shuffle) {
                            audioPlayer.playShuffled(resolved, startIndex)
                        } else {
                            audioPlayer.play(resolved, startIndex)
                        }
                    }
                }
                is RemoteCommand.AppendToQueue -> {
                    val resolved = resolveIncomingQueue(command.tracks)
                    if (resolved.isNotEmpty()) {
                        audioPlayer.appendToQueue(resolved)
                    }
                }
                is RemoteCommand.InsertNext -> {
                    val resolved = resolveIncomingQueue(listOf(command.track)).single()
                    audioPlayer.addToUpNext(resolved)
                }
            }
        }
    }
}

fun PlayerState.toRemoteSnapshot(hostName: String): RemoteSnapshot =
    RemoteSnapshot(
        queue = queue.sanitizeForRemote(),
        currentIndex = currentIndex,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        positionMs = positionMs,
        durationMs = durationMs,
        shuffle = shuffle,
        repeat = repeat,
        volume = volume,
        hostName = hostName,
    )
