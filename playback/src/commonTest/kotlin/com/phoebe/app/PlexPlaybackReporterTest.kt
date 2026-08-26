package com.phoebe.app

import com.phoebe.app.data.JellyfinClient
import com.phoebe.app.data.MusicProviderRegistry
import com.phoebe.app.data.NavidromeProviderAdapter
import com.phoebe.app.data.PlexClient
import com.phoebe.app.data.PlexPlaybackReporter
import com.phoebe.app.data.SubsonicClient
import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.domain.MediaProviderType
import com.phoebe.app.domain.MusicLibrary
import com.phoebe.app.domain.PlayerState
import com.phoebe.app.domain.PlexServer
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import com.phoebe.app.player.AudioPlayer
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class PlexPlaybackReporterTest {
    @Test
    fun plexRatingKeyStripsPrefix() {
        assertEquals("46171", PlexPlaybackReporter.plexRatingKey("plex:46171"))
    }

    @Test
    fun plexRatingKeyIgnoresNonPlexIds() {
        assertNull(PlexPlaybackReporter.plexRatingKey("local:track-1"))
    }

    @Test
    fun reportsStoppedWhenTrackReachesEndWithoutTrackChange() = runTest {
        val timelineStates = MutableStateFlow<List<String>>(emptyList())
        val requests = MutableStateFlow<List<String>>(emptyList())
        val audioPlayer = FakeAudioPlayer()
        val reporter = newReporter(timelineEngine(timelineStates, requests = requests), audioPlayer)
        val scopeJob = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val track = plexTrack()

        try {
            reporter.start(scope, includePeriodicTimeline = false)

            audioPlayer.mutableState.value = PlayerState(
                queue = listOf(track),
                currentIndex = 0,
                isPlaying = true,
                positionMs = 1_000L,
                durationMs = track.durationMs,
            )
            advanceUntilIdle()
            timelineStates.awaitSize(1)

            audioPlayer.mutableState.value = PlayerState(
                queue = listOf(track),
                currentIndex = 0,
                isPlaying = false,
                positionMs = track.durationMs,
                durationMs = track.durationMs,
            )
            advanceUntilIdle()
            timelineStates.awaitSize(2)

            assertEquals(listOf("playing", "stopped"), timelineStates.value, "requests=${requests.value}")
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun emitsPlayHistoryChangedAfterSuccessfulStoppedReport() = runTest {
        val timelineStates = MutableStateFlow<List<String>>(emptyList())
        val audioPlayer = FakeAudioPlayer()
        val reporter = newReporter(timelineEngine(timelineStates), audioPlayer)
        val scopeJob = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val track = plexTrack()

        try {
            reporter.start(scope, includePeriodicTimeline = false)
            val changed = async(UnconfinedTestDispatcher(testScheduler)) {
                reporter.playHistoryChanged.first()
            }

            audioPlayer.mutableState.value = PlayerState(
                queue = listOf(track),
                currentIndex = 0,
                isPlaying = true,
                positionMs = 1_000L,
                durationMs = track.durationMs,
            )
            advanceUntilIdle()
            timelineStates.awaitSize(1)

            audioPlayer.mutableState.value = PlayerState(
                queue = listOf(track),
                currentIndex = 0,
                isPlaying = false,
                positionMs = track.durationMs,
                durationMs = track.durationMs,
            )
            advanceUntilIdle()
            timelineStates.awaitSize(2)
            advanceUntilIdle()

            withTimeout(2_000L) {
                changed.await()
            }
            assertEquals(listOf("playing", "stopped"), timelineStates.value)
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun markPlayedPersistsPlexPlayAndEmitsHistoryChanged() = runTest {
        val requests = MutableStateFlow<List<String>>(emptyList())
        val keys = MutableStateFlow<List<String>>(emptyList())
        val audioPlayer = FakeAudioPlayer()
        val reporter = newReporter(
            MockEngine { request ->
                requests.update { it + request.url.encodedPath }
                when (request.url.encodedPath) {
                    "/:/scrobble" -> {
                        keys.update { it + request.url.parameters["key"].orEmpty() }
                        respondJson("""{"MediaContainer":{"size":0}}""")
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
            audioPlayer,
        )
        val changed = async {
            reporter.playHistoryChanged.first()
        }

        reporter.markPlayed(plexTrack(), playedAtMs = 123_000L)

        withTimeout(2_000L) {
            changed.await()
        }
        assertEquals(listOf("/:/scrobble"), requests.value)
        assertEquals(listOf("123"), keys.value)
    }

    @Test
    fun reportsStoppedWhenReporterScopeCancelsDuringRemotePlayback() = runTest {
        val timelineStates = MutableStateFlow<List<String>>(emptyList())
        val continuingValues = MutableStateFlow<List<String?>>(emptyList())
        val requests = MutableStateFlow<List<String>>(emptyList())
        val audioPlayer = FakeAudioPlayer()
        val reporter = newReporter(timelineEngine(timelineStates, continuingValues, requests), audioPlayer)
        val scopeJob = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val track = plexTrack()

        reporter.start(scope, includePeriodicTimeline = false)

        audioPlayer.mutableState.value = PlayerState(
            queue = listOf(track),
            currentIndex = 0,
            isPlaying = true,
            positionMs = 42_000L,
            durationMs = track.durationMs,
        )
        advanceUntilIdle()
        timelineStates.awaitSize(1)

        scopeJob.cancelAndJoin()
        advanceUntilIdle()
        timelineStates.awaitSize(2)

        assertEquals(listOf("playing", "stopped"), timelineStates.value, "requests=${requests.value}")
        assertEquals(listOf(null, "0"), continuingValues.value, "requests=${requests.value}")
    }

    @Test
    fun navidromeImmediateMarkDoesNotDuplicateThresholdScrobble() = runTest {
        val scrobbles = MutableStateFlow<List<Map<String, String>>>(emptyList())
        val audioPlayer = FakeAudioPlayer()
        val reporter = newNavidromeReporter(subsonicEngine(scrobbles), audioPlayer)
        val scopeJob = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val track = navidromeTrack()

        try {
            reporter.markPlayed(track, playedAtMs = 123_000L)
            scrobbles.awaitSize(1)
            reporter.start(scope, includePeriodicTimeline = false)

            audioPlayer.mutableState.value = PlayerState(
                queue = listOf(track),
                currentIndex = 0,
                isPlaying = true,
                positionMs = 91_000L,
                durationMs = track.durationMs,
            )
            advanceUntilIdle()

            assertEquals(1, scrobbles.value.size)
            assertEquals("123000", scrobbles.value.single()["time"])
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun navidromeScrobblesWhenPlaybackPassesThreshold() = runTest {
        val scrobbles = MutableStateFlow<List<Map<String, String>>>(emptyList())
        val audioPlayer = FakeAudioPlayer()
        val reporter = newNavidromeReporter(subsonicEngine(scrobbles), audioPlayer)
        val scopeJob = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val track = navidromeTrack()

        try {
            reporter.start(scope, includePeriodicTimeline = false)

            audioPlayer.mutableState.value = PlayerState(
                queue = listOf(track),
                currentIndex = 0,
                isPlaying = true,
                positionMs = 91_000L,
                durationMs = track.durationMs,
            )
            advanceUntilIdle()
            scrobbles.awaitSize(1)

            assertEquals("tr1", scrobbles.value.single()["id"])
            assertEquals("true", scrobbles.value.single()["submission"])
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun navidromeDoesNotDuplicateThresholdScrobbleOnStop() = runTest {
        val scrobbles = MutableStateFlow<List<Map<String, String>>>(emptyList())
        val audioPlayer = FakeAudioPlayer()
        val reporter = newNavidromeReporter(subsonicEngine(scrobbles), audioPlayer)
        val scopeJob = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val track = navidromeTrack()

        try {
            reporter.start(scope, includePeriodicTimeline = false)

            audioPlayer.mutableState.value = PlayerState(
                queue = listOf(track),
                currentIndex = 0,
                isPlaying = true,
                positionMs = 91_000L,
                durationMs = track.durationMs,
            )
            advanceUntilIdle()
            scrobbles.awaitSize(1)

            audioPlayer.mutableState.value = PlayerState(
                queue = listOf(track),
                currentIndex = 0,
                isPlaying = false,
                positionMs = track.durationMs,
                durationMs = track.durationMs,
            )
            advanceUntilIdle()

            assertEquals(1, scrobbles.value.size)
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    private fun newReporter(engine: MockEngine, audioPlayer: AudioPlayer): PlexPlaybackReporter {
        val httpClient = testHttpClient(engine)
        return PlexPlaybackReporter(
            plexClient = PlexClient.withoutResolver(httpClient),
            jellyfinClient = JellyfinClient(httpClient),
            audioPlayer = audioPlayer,
            session = MutableStateFlow(
                PlexSession(
                    token = "user-token",
                    selectedServer = PlexServer(
                        id = "server",
                        name = "Plex",
                        uri = "https://plex.example:32400",
                        owned = true,
                    ),
                    selectedLibrary = MusicLibrary("1", "Music"),
                ),
            ),
        )
    }

    private fun newNavidromeReporter(engine: MockEngine, audioPlayer: AudioPlayer): PlexPlaybackReporter {
        val httpClient = testHttpClient(engine)
        return PlexPlaybackReporter(
            plexClient = PlexClient.withoutResolver(httpClient),
            jellyfinClient = JellyfinClient(httpClient),
            providerRegistry = MusicProviderRegistry(
                listOf(NavidromeProviderAdapter(SubsonicClient(httpClient))),
            ),
            audioPlayer = audioPlayer,
            session = MutableStateFlow(
                PlexSession(
                    token = "secret",
                    userName = "ada",
                    selectedServer = PlexServer(
                        id = "navidrome:server",
                        name = "Navidrome",
                        uri = "https://navidrome.example",
                        owned = true,
                    ),
                    selectedLibrary = MusicLibrary("all", "All Music"),
                    providerType = MediaProviderType.Navidrome,
                    userId = "ada",
                ),
            ),
        )
    }

    private fun timelineEngine(
        timelineStates: MutableStateFlow<List<String>>,
        continuingValues: MutableStateFlow<List<String?>> = MutableStateFlow(emptyList()),
        requests: MutableStateFlow<List<String>> = MutableStateFlow(emptyList()),
    ): MockEngine = MockEngine { request ->
        requests.update { it + request.url.encodedPath }
        when {
            request.url.encodedPath == "/identity" -> respondJson(
                """{"MediaContainer":{"machineIdentifier":"machine"}}""",
            )
            request.url.encodedPath == "/playQueues" -> respondJson(
                """{"MediaContainer":{"playQueueID":1,"Metadata":[{"ratingKey":"123","playQueueItemID":11,"title":"Song"}]}}""",
            )
            request.url.encodedPath.contains("timeline") -> {
                timelineStates.update { it + request.url.parameters["state"].orEmpty() }
                continuingValues.update { it + request.url.parameters["continuing"] }
                respondJson("""{"MediaContainer":{"size":0}}""")
            }
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private fun subsonicEngine(
        scrobbles: MutableStateFlow<List<Map<String, String>>>,
    ): MockEngine = MockEngine { request ->
        when (request.url.encodedPath) {
            "/rest/scrobble.view" -> {
                scrobbles.update {
                    it + request.url.parameters.entries().associate { entry ->
                        entry.key to entry.value.last()
                    }
                }
                respondJson("""{ "subsonic-response": { "status": "ok" } }""")
            }
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun plexTrack(durationMs: Long = 180_000L): Track =
        Track(
            id = "plex:123",
            title = "Song",
            artist = "Artist",
            album = "Album",
            durationMs = durationMs,
            streamUrl = "https://plex.example:32400/library/parts/123/file.mp3",
            downloadUrl = "https://plex.example:32400/library/parts/123/file.mp3",
        )

    private fun navidromeTrack(durationMs: Long = 180_000L): Track =
        Track(
            id = "navidrome:tr1",
            title = "Song",
            artist = "Artist",
            album = "Album",
            durationMs = durationMs,
            streamUrl = "https://navidrome.example/rest/stream.view?id=tr1",
            downloadUrl = "https://navidrome.example/rest/download.view?id=tr1",
        )

    private suspend fun <T> StateFlow<List<T>>.awaitSize(size: Int) {
        withContext(Dispatchers.Default) {
            withTimeout(2_000L) {
                first { it.size >= size }
            }
        }
    }

    private class FakeAudioPlayer : AudioPlayer {
        val mutableState = MutableStateFlow(PlayerState())
        override val state: StateFlow<PlayerState> = mutableState

        override fun play(queue: List<Track>, startIndex: Int) {
            mutableState.value = PlayerState(queue = queue, currentIndex = startIndex, isPlaying = true)
        }

        override fun togglePlayPause() = Unit
        override fun clearQueue() = Unit
        override fun stopPlayback() = Unit
        override fun addToUpNext(track: Track) = Unit
        override fun appendToQueue(tracks: List<Track>) = Unit
        override fun moveUpNext(fromIndex: Int, toIndex: Int) = Unit
        override fun removeUpNext(index: Int) = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setShuffle(enabled: Boolean) = Unit
        override fun setRepeat(mode: RepeatMode) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun setCrossfadeDurationMs(durationMs: Long) = Unit
        override fun setEqualizer(profile: EqualizerProfile) = Unit
        override fun setUnityOutputVolume() = Unit
        override fun updateReportedVolume(volume: Float) = Unit
    }
}
