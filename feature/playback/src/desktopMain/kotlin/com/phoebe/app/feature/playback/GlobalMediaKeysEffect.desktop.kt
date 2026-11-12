package com.phoebe.app.feature.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.dispatcher.SwingDispatchService
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import com.phoebe.app.domain.PlayerState
import com.phoebe.app.media.MacMediaSession
import com.phoebe.app.media.MprisMediaSession
import com.phoebe.app.media.NowPlayingSnapshot
import com.phoebe.app.media.loadMacMediaDylib
import com.phoebe.app.platform.PhoebeLog
import java.util.function.LongConsumer
import java.util.logging.Level
import java.util.logging.LogManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow

private val isMacOs: Boolean
    get() = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

private val isLinux: Boolean
    get() = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

@Composable
actual fun GlobalMediaKeysEffect(
    playerFlow: StateFlow<PlayerState>,
    onTogglePlayPause: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val toggle = rememberUpdatedState(onTogglePlayPause)
    val play = rememberUpdatedState(onPlay)
    val pause = rememberUpdatedState(onPause)
    val next = rememberUpdatedState(onNext)
    val previous = rememberUpdatedState(onPrevious)
    val seek = rememberUpdatedState(onSeek)

    // Set when MPRIS cannot register, so the composable falls through to the key hook.
    // Exactly one path is ever live, so a key press can never be handled twice.
    var mprisUnavailable by remember { mutableStateOf(false) }

    if (isMacOs) {
        LaunchedEffect(playerFlow) {
            if (!loadMacMediaDylib()) {
                PhoebeLog.d("Phoebe") {
                    "macOS media bridge dylib not found. Run a desktop build on a Mac first " +
                        "(e.g. ./gradlew :composeApp:compileMacMediaKeysNative) so libPhoebeMediaKeys.dylib exists."
                }
                return@LaunchedEffect
            }
            MacMediaSession.onToggle = Runnable { toggle.value.invoke() }
            MacMediaSession.onPlay = Runnable { play.value.invoke() }
            MacMediaSession.onPause = Runnable { pause.value.invoke() }
            MacMediaSession.onNext = Runnable { next.value.invoke() }
            MacMediaSession.onPrevious = Runnable { previous.value.invoke() }
            MacMediaSession.onSeek = LongConsumer { positionMs -> seek.value.invoke(positionMs) }
            runCatching {
                MacMediaSession.nativeInit()
            }.onFailure { e ->
                PhoebeLog.d("Phoebe") { "macOS media session init failed: ${e.message}" }
                return@LaunchedEffect
            }
            try {
                playerFlow
                    .map { it.toNowPlayingSnapshot() }
                    .distinctUntilChanged()
                    .collectLatest { snapshot ->
                        MacMediaSession.nativeUpdateNowPlaying(
                            snapshot.title,
                            snapshot.artist,
                            snapshot.album,
                            snapshot.artworkUrl,
                            snapshot.positionBucketMs * 1_000L,
                            snapshot.durationMs,
                            snapshot.playing,
                        )
                    }
            } finally {
                runCatching { MacMediaSession.nativeShutdown() }
            }
        }
    } else if (isLinux && !mprisUnavailable) {
        LaunchedEffect(playerFlow) {
            MprisMediaSession.onToggle = { toggle.value.invoke() }
            MprisMediaSession.onPlay = { play.value.invoke() }
            MprisMediaSession.onPause = { pause.value.invoke() }
            MprisMediaSession.onNext = { next.value.invoke() }
            MprisMediaSession.onPrevious = { previous.value.invoke() }
            MprisMediaSession.onStop = { pause.value.invoke() }
            MprisMediaSession.onSeek = { positionMs -> seek.value.invoke(positionMs) }

            if (!MprisMediaSession.connect()) {
                mprisUnavailable = true
                return@LaunchedEffect
            }

            try {
                playerFlow
                    .map { it.toNowPlayingSnapshot() to it.volume }
                    .distinctUntilChanged()
                    .collectLatest { (snapshot, volume) -> MprisMediaSession.update(snapshot, volume) }
            } finally {
                MprisMediaSession.shutdown()
            }
        }
    } else {
        DisposableEffect(Unit) {
            try {
                runCatching {
                    LogManager.getLogManager()?.getLogger("com.github.kwhat.jnativehook")?.level = Level.WARNING
                }

                GlobalScreen.setEventDispatcher(SwingDispatchService())

                val registeredHookHere = if (!GlobalScreen.isNativeHookRegistered()) {
                    GlobalScreen.registerNativeHook()
                    true
                } else {
                    false
                }

                val listener = object : NativeKeyListener {
                    override fun nativeKeyPressed(nativeKeyEvent: NativeKeyEvent) {
                        runCatching {
                            when (nativeKeyEvent.keyCode) {
                                NativeKeyEvent.VC_MEDIA_PLAY -> toggle.value.invoke()
                                NativeKeyEvent.VC_MEDIA_NEXT -> next.value.invoke()
                                NativeKeyEvent.VC_MEDIA_PREVIOUS -> previous.value.invoke()
                                NativeKeyEvent.VC_MEDIA_STOP -> pause.value.invoke()
                                else -> Unit
                            }
                        }
                    }

                    override fun nativeKeyReleased(nativeKeyEvent: NativeKeyEvent) = Unit

                    override fun nativeKeyTyped(nativeKeyEvent: NativeKeyEvent) = Unit
                }

                GlobalScreen.addNativeKeyListener(listener)
                DesktopGlobalMediaKeyHook.isActive = true

                onDispose {
                    DesktopGlobalMediaKeyHook.isActive = false
                    GlobalScreen.removeNativeKeyListener(listener)
                    if (registeredHookHere && GlobalScreen.isNativeHookRegistered()) {
                        runCatching {
                            GlobalScreen.unregisterNativeHook()
                        }.onFailure { e ->
                            PhoebeLog.d("Phoebe") { "Failed to unregister global media key hook: ${e.message}" }
                        }
                    }
                }
            } catch (t: Throwable) {
                DesktopGlobalMediaKeyHook.isActive = false
                PhoebeLog.d("Phoebe") { "Global media keys unavailable: ${t.message}" }
                t.printStackTrace()
                onDispose { }
            }
        }
    }
}

internal fun PlayerState.toNowPlayingSnapshot(): NowPlayingSnapshot {
    val track = currentTrack
    val durationMs = when {
        this.durationMs > 0L -> this.durationMs
        track != null && track.durationMs > 0L -> track.durationMs
        else -> 0L
    }
    return NowPlayingSnapshot(
        trackId = track?.id.orEmpty(),
        title = track?.title.orEmpty(),
        artist = track?.artist.orEmpty(),
        album = track?.album.orEmpty(),
        artworkUrl = track?.localArtworkUri?.takeIf { it.isNotBlank() } ?: track?.thumbUrl.orEmpty(),
        positionBucketMs = positionMs / 1_000L,
        durationMs = durationMs,
        playing = isPlaying,
    )
}
