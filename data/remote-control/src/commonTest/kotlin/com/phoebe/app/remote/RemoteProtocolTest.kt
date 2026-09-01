package com.phoebe.app.remote

import com.phoebe.app.domain.RepeatMode
import com.phoebe.app.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteProtocolTest {

    @Test
    fun testRemoteFrameSerializationRoundTrip() {
        val remoteTrack = Track(
            id = "plex:456",
            title = "Song B",
            artist = "Artist B",
            album = "Album B",
            durationMs = 180000L,
            streamUrl = "",
            downloadUrl = "",
        )
        val frames: List<RemoteFrame> = listOf(
            RemoteFrame.Hello("dev-1", "My Phone", 1),
            RemoteFrame.Hello("dev-1", "My Phone", 1, accountId = "Plex:user-1:server-1"),
            RemoteFrame.AuthResponse("abcdef0123456789"),
            RemoteFrame.Command(RemoteCommand.TogglePlayPause),
            RemoteFrame.Command(RemoteCommand.Next),
            RemoteFrame.Command(RemoteCommand.Previous),
            RemoteFrame.Command(RemoteCommand.SeekTo(42000L)),
            RemoteFrame.Command(RemoteCommand.SetVolume(0.85f)),
            RemoteFrame.Command(RemoteCommand.JumpToIndex(3)),
            RemoteFrame.Command(RemoteCommand.SetShuffle(true)),
            RemoteFrame.Command(RemoteCommand.SetRepeat(RepeatMode.All)),
            RemoteFrame.Command(RemoteCommand.ReplaceQueue(listOf(remoteTrack), 0, shuffle = true)),
            RemoteFrame.Command(RemoteCommand.AppendToQueue(listOf(remoteTrack))),
            RemoteFrame.Command(RemoteCommand.InsertNext(remoteTrack)),
            RemoteFrame.Ping,
            RemoteFrame.Challenge("nonce-123", "Living Room Mac", "host-dev-99"),
            RemoteFrame.AuthResult(true, "secret-abc", "Welcome"),
            RemoteFrame.AuthResult(false, null, "Denied"),
            RemoteFrame.AuthResult(true, sameAccount = true),
            RemoteFrame.AwaitingApproval("Living Room Mac", "host-dev-99"),
            RemoteFrame.PositionTick(15000L, 240000L, true),
            RemoteFrame.Pong,
            RemoteFrame.Bye("Session ended by host"),
            RemoteFrame.Snapshot(
                RemoteSnapshot(
                    queue = listOf(
                        Track(
                            id = "plex:123",
                            title = "Song A",
                            artist = "Artist A",
                            album = "Album A",
                            durationMs = 240000L,
                            streamUrl = "",
                            downloadUrl = "",
                        ),
                    ),
                    currentIndex = 0,
                    isPlaying = true,
                    isBuffering = false,
                    positionMs = 12000L,
                    durationMs = 240000L,
                    shuffle = false,
                    repeat = RepeatMode.Off,
                    volume = 0.75f,
                    hostName = "Living Room Mac",
                ),
            ),
        )

        for (frame in frames) {
            val encoded = RemoteJson.encodeToString(RemoteFrame.serializer(), frame)
            val decoded = RemoteJson.decodeFromString(RemoteFrame.serializer(), encoded)
            assertEquals(frame, decoded)
        }
    }

    @Test
    fun testTrackSanitization() {
        val track = Track(
            id = "plex:track-1",
            title = "Secret Track",
            artist = "Artist",
            album = "Album",
            durationMs = 180000L,
            streamUrl = "http://192.168.1.50:32400/audio/file.mp3?X-Plex-Token=SECRET_TOKEN",
            downloadUrl = "http://192.168.1.50:32400/download/file.mp3",
            thumbUrl = "http://192.168.1.50:32400/photo/thumb.jpg",
            localArtworkUri = "/local/path/thumb.jpg",
            localUri = "/local/music/track.mp3",
            filepath = "/Volumes/Music/track.mp3",
            playbackFallbackUrls = listOf("http://10.0.0.1:32400/stream"),
        )

        val sanitized = track.sanitizeForRemote()

        assertEquals("plex:track-1", sanitized.id)
        assertEquals("Secret Track", sanitized.title)
        assertEquals("", sanitized.streamUrl)
        assertEquals("", sanitized.downloadUrl)
        assertNull(sanitized.localArtworkUri)
        assertNull(sanitized.localUri)
        assertNull(sanitized.filepath)
        assertTrue(sanitized.playbackFallbackUrls.isEmpty())
        assertNull(sanitized.thumbUrl)
    }

    @Test
    fun testSnapshotToPlayerState() {
        val track = Track(
            id = "t1",
            title = "Track 1",
            artist = "Artist 1",
            album = "Album 1",
            durationMs = 200000L,
            streamUrl = "",
            downloadUrl = "",
        )
        val snapshot = RemoteSnapshot(
            queue = listOf(track),
            currentIndex = 0,
            isPlaying = true,
            isBuffering = false,
            positionMs = 50000L,
            durationMs = 200000L,
            shuffle = true,
            repeat = RepeatMode.One,
            volume = 0.9f,
            hostName = "Host PC",
        )

        val playerState = snapshot.toPlayerState()

        assertEquals(1, playerState.queue.size)
        assertEquals(0, playerState.currentIndex)
        assertTrue(playerState.isPlaying)
        assertEquals(50000L, playerState.positionMs)
        assertEquals(200000L, playerState.durationMs)
        assertTrue(playerState.shuffle)
        assertEquals(RepeatMode.One, playerState.repeat)
        assertEquals(0.9f, playerState.volume)
    }
}
