@file:OptIn(ExperimentalWasmJsInterop::class)

package com.phoebe.app.player

import com.phoebe.app.domain.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebAudioPlayerLifecycleTest {
    @Test
    fun rejectedBrowserPlayClearsBufferingAndReportsFailure() = runTest {
        installMockWebAudioElement(mode = "reject", durationSeconds = 0.25)
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = createWebAudioPlayerForTests(diagnostics)
        try {
            val track = playbackTrack(
                id = "web-rejected-play",
                streamUrl = "https://music.example.test/rejected.mp3",
                durationMs = 250L,
            )

            player.play(listOf(track), 0)

            assertTrue(
                waitUntil {
                    player.state.value.playbackErrorSerial > 0 &&
                        !player.state.value.isBuffering &&
                        !player.state.value.isPlaying
                },
                "Rejected browser play() should surface a playback failure; state=${player.state.value} " +
                    "errors=${diagnostics.errors}",
            )
            assertTrue(diagnostics.errors.any { it.contains("Mock play rejected") })
        } finally {
            player.stopPlayback()
            restoreMockWebAudioElement()
        }
    }

    @Test
    fun chromeAutoplayBlockKeepsTrackReadyForUserPlay() = runTest {
        browserAutoplayBlockKeepsTrackReadyForUserPlay(mode = "autoplay-blocked-chrome-then-complete")
    }

    @Test
    fun safariAutoplayBlockKeepsTrackReadyForUserPlay() = runTest {
        browserAutoplayBlockKeepsTrackReadyForUserPlay(mode = "autoplay-blocked-safari-then-complete")
    }

    @Test
    fun firefoxAutoplayBlockKeepsTrackReadyForUserPlay() = runTest {
        browserAutoplayBlockKeepsTrackReadyForUserPlay(mode = "autoplay-blocked-firefox-then-complete")
    }

    private suspend fun browserAutoplayBlockKeepsTrackReadyForUserPlay(mode: String) {
        installMockWebAudioElement(mode = mode, durationSeconds = 0.25)
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = createWebAudioPlayerForTests(diagnostics)
        try {
            val track = playbackTrack(
                id = "web-$mode",
                streamUrl = "https://music.example.test/autoplay-blocked.mp3",
                durationMs = 250L,
            )

            player.play(listOf(track), 0)

            assertTrue(
                waitUntil {
                    val state = player.state.value
                    state.currentTrack?.id == track.id &&
                        !state.isBuffering &&
                        !state.isPlaying &&
                        state.playbackErrorSerial == 0
                },
                "Autoplay block should leave the track ready without surfacing a failure; " +
                    "state=${player.state.value} errors=${diagnostics.errors}",
            )
            assertFalse(diagnostics.errors.any { it.contains("NotAllowedError") })

            player.togglePlayPause()

            assertTrue(
                waitUntil {
                    diagnostics.hasPlayingEvent &&
                        player.state.value.isPlaying &&
                        player.state.value.positionMs > 0L
                },
                "User play should resume after an autoplay block; state=${player.state.value} " +
                    "progress=${diagnostics.progress} errors=${diagnostics.errors}",
            )
        } finally {
            player.stopPlayback()
            restoreMockWebAudioElement()
        }
    }

    @Test
    fun localWebAudioSongPlaysThroughToEnd() = runTest {
        installMockWebAudioElement(mode = "complete", durationSeconds = 0.25)
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = createWebAudioPlayerForTests(diagnostics)
        try {
            val track = playbackTrack(
                id = "web-local-complete",
                localUri = "phoebe-test://music/local-complete.wav",
                durationMs = 250L,
            )

            player.play(listOf(track), 0)

            assertTrue(
                waitUntil {
                    diagnostics.hasPlayingEvent &&
                        player.state.value.isPlaying &&
                        player.state.value.positionMs > 0L
                },
                "Local web audio did not produce real playback progress; state=${player.state.value} " +
                    "progress=${diagnostics.progress}",
            )
            assertTrue(
                waitUntil(timeoutMs = 2_000L) {
                    val state = player.state.value
                    state.currentTrack?.id == track.id &&
                        !state.isPlaying &&
                        !state.isBuffering &&
                        state.positionMs >= track.durationMs
                },
                "Local web audio did not play through to completion; state=${player.state.value} " +
                    "progress=${diagnostics.progress}",
            )
        } finally {
            player.stopPlayback()
            restoreMockWebAudioElement()
        }
    }

    @Test
    fun remoteWebAudioSongPlaysThroughToEnd() = runTest {
        installMockWebAudioElement(mode = "complete", durationSeconds = 0.25)
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = createWebAudioPlayerForTests(diagnostics)
        try {
            val track = playbackTrack(
                id = "web-remote-complete",
                streamUrl = "https://music.example.test/remote-complete.mp3",
                durationMs = 250L,
            )

            player.play(listOf(track), 0)

            assertTrue(
                waitUntil {
                    diagnostics.hasPlayingEvent &&
                        player.state.value.isPlaying &&
                        player.state.value.positionMs > 0L
                },
                "Remote web audio did not produce real playback progress; state=${player.state.value} " +
                    "progress=${diagnostics.progress}",
            )
            assertTrue(
                waitUntil(timeoutMs = 2_000L) {
                    val state = player.state.value
                    state.currentTrack?.id == track.id &&
                        !state.isPlaying &&
                        !state.isBuffering &&
                        state.positionMs >= track.durationMs
                },
                "Remote web audio did not play through to completion; state=${player.state.value} " +
                    "progress=${diagnostics.progress}",
            )
        } finally {
            player.stopPlayback()
            restoreMockWebAudioElement()
        }
    }

    @Test
    fun queuedWebAudioAdvancesWhenCanplayArrivesAfterInitialPlayAttempt() = runTest {
        installMockWebAudioElement(mode = "delayed-canplay", durationSeconds = 0.2)
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = createWebAudioPlayerForTests(diagnostics)
        try {
            val first = playbackTrack(
                id = "web-delayed-canplay-first",
                streamUrl = "https://music.example.test/delayed-canplay-first.mp3",
                durationMs = 200L,
            )
            val second = playbackTrack(
                id = "web-delayed-canplay-second",
                streamUrl = "https://music.example.test/delayed-canplay-second.mp3",
                durationMs = 200L,
            )

            player.play(listOf(first, second), 0)

            assertTrue(
                waitUntil(timeoutMs = 3_000L) {
                    player.state.value.currentTrack?.id == second.id
                },
                "Delayed canplay should still advance to the next track; state=${player.state.value} " +
                    "errors=${diagnostics.errors}",
            )
            assertTrue(
                diagnostics.errors.isEmpty(),
                "Delayed canplay queue advance should not surface playback errors; errors=${diagnostics.errors}",
            )
        } finally {
            player.stopPlayback()
            restoreMockWebAudioElement()
        }
    }

    @Test
    fun clearedSourceErrorDuringRemoteTrackTransitionDoesNotStopQueue() = runTest {
        installMockWebAudioElement(mode = "clear-src-error-on-empty-load", durationSeconds = 0.2)
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = createWebAudioPlayerForTests(diagnostics)
        try {
            val tracks = (1..4).map { index ->
                playbackTrack(
                    id = "web-clear-src-$index",
                    streamUrl = "https://music.example.test/clear-src-$index.mp3",
                    durationMs = 200L,
                )
            }

            player.play(tracks, 0)

            assertTrue(
                waitUntil(timeoutMs = 4_000L) {
                    player.state.value.currentTrack?.id == tracks.last().id
                },
                "Queue should advance past cleared-source errors; state=${player.state.value} " +
                    "errors=${diagnostics.errors}",
            )
            assertTrue(
                diagnostics.errors.isEmpty(),
                "Cleared-source transition errors should be ignored; errors=${diagnostics.errors}",
            )
        } finally {
            player.stopPlayback()
            restoreMockWebAudioElement()
        }
    }

    @Test
    fun queuedWebAudioAdvancesThroughSeveralTracksWithoutFailure() = runTest {
        installMockWebAudioElement(mode = "complete", durationSeconds = 0.2)
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = createWebAudioPlayerForTests(diagnostics)
        try {
            val tracks = (1..5).map { index ->
                playbackTrack(
                    id = "web-queue-$index",
                    streamUrl = "https://music.example.test/queue-$index.mp3",
                    durationMs = 200L,
                )
            }

            player.play(tracks, 0)

            assertTrue(
                waitUntil(timeoutMs = 2_500L) {
                    player.state.value.currentTrack?.id == tracks.last().id
                },
                "Web audio should advance through a shuffled-length queue; state=${player.state.value} " +
                    "errors=${diagnostics.errors}",
            )
            assertTrue(
                diagnostics.errors.isEmpty(),
                "Sequential web track advances should not surface playback errors; errors=${diagnostics.errors}",
            )
        } finally {
            player.stopPlayback()
            restoreMockWebAudioElement()
        }
    }

    @Test
    fun queuedWebAudioPublishesEndedPositionBeforeAdvancing() = runTest {
        installMockWebAudioElement(mode = "ended-without-final-timeupdate", durationSeconds = 0.25)
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = createWebAudioPlayerForTests(diagnostics)
        try {
            val first = playbackTrack(
                id = "web-ended-sync-first",
                streamUrl = "https://music.example.test/ended-sync-first.mp3",
                durationMs = 250L,
            )
            val second = playbackTrack(
                id = "web-ended-sync-second",
                streamUrl = "https://music.example.test/ended-sync-second.mp3",
                durationMs = 250L,
            )

            player.play(listOf(first, second), 0)

            assertTrue(
                waitUntil(timeoutMs = 1_500L) {
                    player.state.value.currentTrack?.id == second.id &&
                        diagnostics.progress.contains(first.durationMs)
                },
                "Web audio should publish the completed first-track position before advancing; " +
                    "state=${player.state.value} progress=${diagnostics.progress}",
            )
        } finally {
            player.stopPlayback()
            restoreMockWebAudioElement()
        }
    }

    @Test
    fun queuedWebAudioAdvancesWhenOnendedNeverFires() = runTest {
        installMockWebAudioElement(mode = "end-without-onended", durationSeconds = 0.2)
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = createWebAudioPlayerForTests(diagnostics)
        try {
            val first = playbackTrack(
                id = "web-no-onended-first",
                streamUrl = "https://music.example.test/no-onended-first.mp3",
                durationMs = 200L,
            )
            val second = playbackTrack(
                id = "web-no-onended-second",
                streamUrl = "https://music.example.test/no-onended-second.mp3",
                durationMs = 200L,
            )

            player.play(listOf(first, second), 0)

            assertTrue(
                waitUntil(timeoutMs = 3_000L) {
                    player.state.value.currentTrack?.id == second.id
                },
                "Position poll should advance when onended never fires; state=${player.state.value} " +
                    "errors=${diagnostics.errors}",
            )
            assertTrue(
                diagnostics.errors.isEmpty(),
                "Missing onended should not surface playback errors; errors=${diagnostics.errors}",
            )
        } finally {
            player.stopPlayback()
            restoreMockWebAudioElement()
        }
    }

    @Test
    fun webCrossfadeRampsIncomingAudioAndCommitsToNextTrack() = runTest {
        installMockWebAudioElement(mode = "crossfade", durationSeconds = 1.0)
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = createWebAudioPlayerForTests(diagnostics)
        try {
            val first = playbackTrack(
                id = "web-crossfade-first",
                localUri = "phoebe-test://music/crossfade-first.mp3",
                durationMs = 1_000L,
            )
            val second = playbackTrack(
                id = "web-crossfade-second",
                localUri = "phoebe-test://music/crossfade-second.mp3",
                durationMs = 1_000L,
            )

            player.setCrossfadeDurationMs(500L)
            player.play(listOf(first, second), 0)

            assertTrue(
                waitUntil(timeoutMs = 1_500L) {
                    player.state.value.currentTrack?.id == second.id &&
                        diagnostics.committedTrackIds.contains(second.id)
                },
                "Web crossfade did not commit to the incoming track; state=${player.state.value} " +
                    "volumes=${diagnostics.crossfadeVolumes} errors=${diagnostics.errors}",
            )
            assertTrue(
                diagnostics.crossfadeVolumes.size >= 4,
                "Expected several web crossfade volume samples; volumes=${diagnostics.crossfadeVolumes}",
            )
            assertTrue(
                diagnostics.crossfadeVolumes.zipWithNext().all { (left, right) ->
                    left.outgoingVolume >= right.outgoingVolume
                },
                "Outgoing web volume should ramp down; volumes=${diagnostics.crossfadeVolumes}",
            )
            assertTrue(
                diagnostics.crossfadeVolumes.zipWithNext().all { (left, right) ->
                    left.incomingVolume <= right.incomingVolume
                },
                "Incoming web volume should ramp up; volumes=${diagnostics.crossfadeVolumes}",
            )
            assertTrue(player.state.value.isPlaying)
        } finally {
            player.stopPlayback()
            restoreMockWebAudioElement()
        }
    }

    @Test
    fun staleRejectedPlayPromiseFromOutgoingCrossfadeAudioIsIgnored() = runTest {
        installMockWebAudioElement(mode = "crossfade-stale-source-reject", durationSeconds = 1.0)
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = createWebAudioPlayerForTests(diagnostics)
        try {
            val first = playbackTrack(
                id = "web-crossfade-stale-first",
                localUri = "phoebe-test://music/crossfade-stale-first.mp3",
                durationMs = 1_000L,
            )
            val second = playbackTrack(
                id = "web-crossfade-stale-second",
                localUri = "phoebe-test://music/crossfade-stale-second.mp3",
                durationMs = 1_000L,
            )

            player.setCrossfadeDurationMs(500L)
            player.play(listOf(first, second), 0)

            assertTrue(
                waitUntil(timeoutMs = 1_500L) {
                    player.state.value.currentTrack?.id == second.id &&
                        diagnostics.committedTrackIds.contains(second.id)
                },
                "Web crossfade did not commit before stale play() rejection; state=${player.state.value} " +
                    "errors=${diagnostics.errors}",
            )
            delay(400L)
            assertTrue(
                player.state.value.playbackErrorSerial == 0,
                "A stale outgoing play() rejection should not surface a playback error; " +
                    "state=${player.state.value} errors=${diagnostics.errors}",
            )
            assertTrue(
                diagnostics.errors.none { it.contains("no supported source", ignoreCase = true) },
                "Stale outgoing play() rejection should not be reported; errors=${diagnostics.errors}",
            )
        } finally {
            player.stopPlayback()
            restoreMockWebAudioElement()
        }
    }

    @Test
    fun remoteWebAudioRetriesWithoutCorsWhenCorsPlaybackRejects() = runTest {
        installMockWebAudioElement(mode = "cors-reject-then-complete", durationSeconds = 0.25)
        val diagnostics = RecordingPlaybackDiagnostics()
        val player = createWebAudioPlayerForTests(diagnostics)
        try {
            val track = playbackTrack(
                id = "web-remote-cors-fallback",
                streamUrl = "https://plex.example.test/library/parts/1/file?X-Plex-Token=test",
                durationMs = 250L,
            )

            player.play(listOf(track), 0)

            assertTrue(
                waitUntil {
                    diagnostics.hasPlayingEvent &&
                        player.state.value.isPlaying &&
                        player.state.value.positionMs > 0L &&
                        player.state.value.playbackErrorSerial == 0
                },
                "Remote web audio did not recover after CORS-mode play() rejected; " +
                    "state=${player.state.value} progress=${diagnostics.progress} errors=${diagnostics.errors}",
            )
            assertTrue(
                waitUntil(timeoutMs = 2_000L) {
                    val state = player.state.value
                    state.currentTrack?.id == track.id &&
                        !state.isPlaying &&
                        !state.isBuffering &&
                        state.positionMs >= track.durationMs &&
                        state.playbackErrorSerial == 0
                },
                "Remote web audio did not complete after no-CORS fallback; state=${player.state.value} " +
                    "progress=${diagnostics.progress} errors=${diagnostics.errors}",
            )
        } finally {
            player.stopPlayback()
            restoreMockWebAudioElement()
        }
    }

    private suspend fun waitUntil(timeoutMs: Long = 1_000L, condition: () -> Boolean): Boolean {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow()
        while (deadline.elapsedNow().inWholeMilliseconds <= timeoutMs) {
            if (condition()) return true
            delay(10)
        }
        return condition()
    }

    private fun playbackTrack(
        id: String,
        streamUrl: String = "",
        localUri: String? = null,
        durationMs: Long,
    ): Track =
        Track(
            id = id,
            title = id,
            artist = "Web fixture",
            album = "Playback lifecycle",
            durationMs = durationMs,
            streamUrl = streamUrl,
            downloadUrl = "",
            localUri = localUri,
            audioCodec = "mp3",
        )

    private class RecordingPlaybackDiagnostics : PlaybackDiagnostics {
        var hasPlayingEvent = false
            private set
        var progress: List<Long> = emptyList()
            private set
        var errors: List<String> = emptyList()
            private set
        var crossfadeVolumes: List<CrossfadeVolume> = emptyList()
            private set
        var committedTrackIds: List<String> = emptyList()
            private set

        override fun platformPlaying(engine: PlaybackEnginePath, positionMs: Long, durationMs: Long) {
            hasPlayingEvent = true
        }

        override fun playbackProgress(engine: PlaybackEnginePath, positionMs: Long, durationMs: Long) {
            progress = (progress + positionMs).takeLast(16)
        }

        override fun playbackError(engine: PlaybackEnginePath, message: String?) {
            errors = errors + (message ?: "unknown playback error")
        }

        override fun crossfadeVolume(
            engine: PlaybackEnginePath,
            step: Int,
            outgoingVolume: Float,
            incomingVolume: Float,
        ) {
            crossfadeVolumes = crossfadeVolumes + CrossfadeVolume(step, outgoingVolume, incomingVolume)
        }

        override fun crossfadeCommitted(engine: PlaybackEnginePath, incomingTrackId: String) {
            committedTrackIds = committedTrackIds + incomingTrackId
        }
    }

    private data class CrossfadeVolume(
        val step: Int,
        val outgoingVolume: Float,
        val incomingVolume: Float,
    )
}

@JsFun(
    """
    (mode, durationSeconds) => {
        const previousRestore = globalThis.__phoebeRestoreMockWebAudioElement;
        if (typeof previousRestore === "function") previousRestore();

        const proto = HTMLMediaElement.prototype;
        const descriptors = {
            play: Object.getOwnPropertyDescriptor(proto, "play"),
            pause: Object.getOwnPropertyDescriptor(proto, "pause"),
            load: Object.getOwnPropertyDescriptor(proto, "load"),
            duration: Object.getOwnPropertyDescriptor(proto, "duration"),
            currentTime: Object.getOwnPropertyDescriptor(proto, "currentTime"),
            paused: Object.getOwnPropertyDescriptor(proto, "paused"),
            buffered: Object.getOwnPropertyDescriptor(proto, "buffered"),
            src: Object.getOwnPropertyDescriptor(proto, "src")
        };
        const timers = new Set();
        let playCalls = 0;
        const stateByElement = new WeakMap();
        const stateFor = (audio) => {
            let state = stateByElement.get(audio);
            if (!state) {
                state = {
                    currentTime: 0,
                    duration: Number(durationSeconds) || 0.25,
                    paused: true,
                    src: "",
                    timer: null,
                    playAttempts: 0,
                };
                stateByElement.set(audio, state);
            }
            return state;
        };
        const event = (name) => {
            try { return new Event(name); } catch (_) { return { type: name }; }
        };
        const call = (audio, handler, name) => {
            if (typeof handler === "function") {
                try { handler.call(audio, event(name)); } catch (error) { setTimeout(() => { throw error; }, 0); }
            }
        };
        const define = (name, descriptor) => {
            Object.defineProperty(proto, name, { configurable: true, ...descriptor });
        };

        define("duration", { get() { return stateFor(this).duration; } });
        define("currentTime", {
            get() { return stateFor(this).currentTime; },
            set(value) {
                const state = stateFor(this);
                const next = Number(value);
                state.currentTime = Number.isFinite(next) ? Math.max(0, Math.min(next, state.duration)) : 0;
            }
        });
        define("paused", { get() { return stateFor(this).paused; } });
        define("ended", {
            get() {
                const state = stateFor(this);
                return state.duration > 0 && state.currentTime >= state.duration;
            }
        });
        define("src", {
            get() { return stateFor(this).src; },
            set(value) { stateFor(this).src = String(value || ""); }
        });
        define("buffered", {
            get() {
                const state = stateFor(this);
                return {
                    length: 1,
                    start: () => 0,
                    end: () => Math.max(state.currentTime, state.duration)
                };
            }
        });

        proto.load = function() {
            const audio = this;
            const state = stateFor(audio);
            if (mode === "clear-src-error-on-empty-load" && !state.src) {
                setTimeout(() => {
                    try { audio.error = { code: 4 }; } catch (_) {}
                    call(audio, audio.onerror, "error");
                }, 0);
                return;
            }
            state.currentTime = 0;
            const emitReady = () => {
                call(audio, audio.onloadedmetadata, "loadedmetadata");
                call(audio, audio.ondurationchange, "durationchange");
                call(audio, audio.onloadeddata, "loadeddata");
                call(audio, audio.oncanplay, "canplay");
                call(audio, audio.oncanplaythrough, "canplaythrough");
            };
            if (mode === "delayed-canplay") {
                setTimeout(emitReady, 30);
            } else {
                setTimeout(emitReady, 0);
            }
        };
        proto.play = function() {
            const audio = this;
            const state = stateFor(audio);
            const playCall = ++playCalls;
            state.playAttempts = (state.playAttempts || 0) + 1;
            if (mode === "delayed-canplay" && state.playAttempts === 1) {
                return Promise.resolve();
            }
            if (mode === "reject") {
                return Promise.reject(new Error("Mock play rejected"));
            }
            if (mode === "autoplay-blocked-chrome-then-complete" && playCall === 1) {
                const error = new Error("play() failed because the user didn't interact with the document first.");
                error.name = "NotAllowedError";
                return Promise.reject(error);
            }
            if (mode === "autoplay-blocked-safari-then-complete" && playCall === 1) {
                return Promise.reject(
                    new Error("The play method is not allowed by the user agent or the platform in the current context, possibly because the user denied permission.")
                );
            }
            if (mode === "autoplay-blocked-firefox-then-complete" && playCall === 1) {
                return Promise.reject(
                    new Error("Playback cannot begin. The user must interact with the document before this element can play.")
                );
            }
            if (mode === "cors-reject-then-complete" && String(audio.crossOrigin || "").toLowerCase() === "anonymous") {
                return Promise.reject(new Error("Mock CORS playback rejected"));
            }
            state.paused = false;
            setTimeout(() => call(audio, audio.onplaying, "playing"), 0);
            if (state.timer) {
                clearInterval(state.timer);
                timers.delete(state.timer);
            }
            state.timer = setInterval(() => {
                if (state.paused) return;
                const crossfadeTiming = mode === "crossfade" || mode === "crossfade-stale-source-reject";
                const stepSeconds = crossfadeTiming ? 0.025 : Math.max(0.04, state.duration / 5);
                const nextTime = Math.min(state.duration, state.currentTime + stepSeconds);
                const reachedEnd = nextTime >= state.duration;
                state.currentTime = nextTime;
                if (!reachedEnd || mode !== "ended-without-final-timeupdate") {
                    call(audio, audio.ontimeupdate, "timeupdate");
                }
                if (reachedEnd) {
                    clearInterval(state.timer);
                    timers.delete(state.timer);
                    state.timer = null;
                    state.paused = true;
                    if (mode !== "end-without-onended") {
                        call(audio, audio.onended, "ended");
                    }
                }
            }, 20);
            timers.add(state.timer);
            if (mode === "crossfade-stale-source-reject" && playCall === 1) {
                return new Promise((_, reject) => {
                    const timer = setTimeout(() => {
                        timers.delete(timer);
                        reject(new Error("Failed to load because no supported source was found."));
                    }, 950);
                    timers.add(timer);
                });
            }
            return Promise.resolve();
        };
        proto.pause = function() {
            const state = stateFor(this);
            state.paused = true;
            if (state.timer) {
                clearInterval(state.timer);
                timers.delete(state.timer);
                state.timer = null;
            }
            call(this, this.onpause, "pause");
        };

        globalThis.__phoebeRestoreMockWebAudioElement = () => {
            for (const timer of Array.from(timers)) {
                clearTimeout(timer);
                clearInterval(timer);
            }
            timers.clear();
            for (const [name, descriptor] of Object.entries(descriptors)) {
                if (descriptor) Object.defineProperty(proto, name, descriptor);
                else delete proto[name];
            }
            delete globalThis.__phoebeRestoreMockWebAudioElement;
        };
    }
    """,
)
private external fun installMockWebAudioElement(mode: String, durationSeconds: Double)

@JsFun(
    """
    () => {
        const restore = globalThis.__phoebeRestoreMockWebAudioElement;
        if (typeof restore === "function") restore();
    }
    """,
)
private external fun restoreMockWebAudioElement()
