package com.phoebe.app.player

import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.remote.RemoteControlClient
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@SingleIn(AppScope::class)
@Inject
class PlaybackTransportService(
    private val audioPlayer: AudioPlayer,
    private val castController: CastController,
    private val systemVolumeController: SystemVolumeController,
    private val remoteControlClient: RemoteControlClient,
) {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun togglePlayPause() {
        val clientState = remoteControlClient.state.value
        com.phoebe.app.platform.PhoebeLog.d("PlaybackTransport", "togglePlayPause called, isRemote=${clientState.isConnected}, status=${clientState.status}")
        if (clientState.isConnected) {
            serviceScope.launch { remoteControlClient.togglePlayPause() }
        } else if (clientState.status == com.phoebe.app.remote.RemoteConnectionStatus.AwaitingApproval) {
            com.phoebe.app.platform.PhoebeLog.d("PlaybackTransport", "togglePlayPause ignored: waiting for pairing approval on ${clientState.hostName}")
        } else if (castController.state.value.isPlaybackActive) {
            castController.togglePlayPause()
        } else {
            audioPlayer.togglePlayPause()
        }
    }

    fun clearQueue() {
        if (remoteControlClient.state.value.isConnected) {
            remoteControlClient.disconnect()
        } else if (castController.state.value.isPlaybackActive) {
            castController.disconnect()
        } else {
            audioPlayer.clearQueue()
        }
    }

    fun stopPlayback() {
        remoteControlClient.disconnect()
        castController.disconnect()
        audioPlayer.stopPlayback()
    }

    fun addToUpNext(track: Track) {
        val clientState = remoteControlClient.state.value
        if (clientState.isConnected && clientState.sameAccount) {
            serviceScope.launch { remoteControlClient.insertNext(track) }
        } else {
            audioPlayer.addToUpNext(track)
        }
    }

    fun appendToQueue(tracks: List<Track>) {
        val clientState = remoteControlClient.state.value
        if (clientState.isConnected && clientState.sameAccount) {
            serviceScope.launch { remoteControlClient.appendToQueue(tracks) }
        } else {
            audioPlayer.appendToQueue(tracks)
        }
    }

    fun moveUpNext(fromIndex: Int, toIndex: Int) = audioPlayer.moveUpNext(fromIndex, toIndex)

    fun removeUpNext(index: Int) = audioPlayer.removeUpNext(index)

    fun playTrack(index: Int) {
        val clientState = remoteControlClient.state.value
        com.phoebe.app.platform.PhoebeLog.d("PlaybackTransport", "playTrack($index) called, isRemote=${clientState.isConnected}")
        if (clientState.isConnected) {
            serviceScope.launch { remoteControlClient.jumpToIndex(index) }
        } else if (clientState.status == com.phoebe.app.remote.RemoteConnectionStatus.AwaitingApproval) {
            com.phoebe.app.platform.PhoebeLog.d("PlaybackTransport", "playTrack ignored: waiting for pairing approval on ${clientState.hostName}")
        } else {
            val queue = audioPlayer.state.value.queue
            if (index in queue.indices) {
                audioPlayer.play(queue, index)
            }
        }
    }

    fun next() {
        val clientState = remoteControlClient.state.value
        com.phoebe.app.platform.PhoebeLog.d("PlaybackTransport", "next() called, isRemote=${clientState.isConnected}")
        if (clientState.isConnected) {
            serviceScope.launch { remoteControlClient.next() }
        } else if (clientState.status == com.phoebe.app.remote.RemoteConnectionStatus.AwaitingApproval) {
            com.phoebe.app.platform.PhoebeLog.d("PlaybackTransport", "next ignored: waiting for pairing approval on ${clientState.hostName}")
        } else if (castController.state.value.isPlaybackActive) {
            castController.next()
        } else {
            audioPlayer.next()
        }
    }

    fun previous() {
        val clientState = remoteControlClient.state.value
        com.phoebe.app.platform.PhoebeLog.d("PlaybackTransport", "previous() called, isRemote=${clientState.isConnected}")
        if (clientState.isConnected) {
            serviceScope.launch { remoteControlClient.previous() }
        } else if (clientState.status == com.phoebe.app.remote.RemoteConnectionStatus.AwaitingApproval) {
            com.phoebe.app.platform.PhoebeLog.d("PlaybackTransport", "previous ignored: waiting for pairing approval on ${clientState.hostName}")
        } else if (castController.state.value.isPlaybackActive) {
            castController.previous()
        } else {
            audioPlayer.previous()
        }
    }

    fun seekTo(positionMs: Long) {
        val clientState = remoteControlClient.state.value
        com.phoebe.app.platform.PhoebeLog.d("PlaybackTransport", "seekTo($positionMs) called, isRemote=${clientState.isConnected}")
        if (clientState.isConnected) {
            serviceScope.launch { remoteControlClient.seekTo(positionMs) }
        } else if (clientState.status == com.phoebe.app.remote.RemoteConnectionStatus.AwaitingApproval) {
            com.phoebe.app.platform.PhoebeLog.d("PlaybackTransport", "seekTo ignored: waiting for pairing approval on ${clientState.hostName}")
        } else if (castController.state.value.isPlaybackActive) {
            castController.seekTo(positionMs)
        } else {
            audioPlayer.seekTo(positionMs)
        }
    }

    fun toggleShuffle(currentShuffle: Boolean) {
        if (remoteControlClient.state.value.isConnected) {
            serviceScope.launch { remoteControlClient.setShuffle(!currentShuffle) }
        } else {
            audioPlayer.setShuffle(!currentShuffle)
        }
    }

    fun cycleRepeat(currentRepeat: RepeatMode) {
        val next = when (currentRepeat) {
            RepeatMode.Off -> RepeatMode.One
            RepeatMode.One -> RepeatMode.All
            RepeatMode.All -> RepeatMode.Off
        }
        if (remoteControlClient.state.value.isConnected) {
            serviceScope.launch { remoteControlClient.setRepeat(next) }
        } else {
            audioPlayer.setRepeat(next)
        }
    }

    fun setVolume(volume: Float) {
        if (remoteControlClient.state.value.isConnected) {
            serviceScope.launch { remoteControlClient.setVolume(volume) }
        } else if (castController.state.value.isPlaybackActive) {
            castController.setVolume(volume)
        } else {
            systemVolumeController.setVolume(volume)
            audioPlayer.setVolume(volume)
        }
    }
}
