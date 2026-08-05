package com.phoebe.app

import com.phoebe.app.domain.PlayerState
import com.phoebe.app.domain.Track
import com.phoebe.app.player.CastState
import com.phoebe.app.player.asPlayerState
import com.phoebe.app.player.isChromecastPlayable
import com.phoebe.app.player.isChromecastPlayableQueue
import com.phoebe.app.player.matchesCastMedia
import com.phoebe.app.player.plexChromecastQueueSupport
import com.phoebe.app.player.castTrackFromMediaFields
import com.phoebe.app.player.isCastReceiverLoadableUrl
import com.phoebe.app.player.isRemoteChromecastPlayable
import com.phoebe.app.player.remoteChromecastQueueSupport
import com.phoebe.app.player.toCastMediaDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CastControllerTest {
    @Test
    fun plexStreamTrackIsChromecastPlayable() {
        val track = Track(
            id = "plex:track:1",
            title = "One",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "https://plex.example/library/parts/1?X-Plex-Token=token",
            downloadUrl = "",
        )

        assertTrue(track.isChromecastPlayable())
        assertTrue(listOf(track).isChromecastPlayableQueue())
    }

    @Test
    fun downloadedPlexTracksStayChromecastPlayableViaStreamUrl() {
        val plexDownload = Track(
            id = "plex:track:1",
            title = "One",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "https://plex.example/library/parts/1?X-Plex-Token=token",
            downloadUrl = "",
            localUri = "file:///music/one.flac",
        )

        assertTrue(plexDownload.isChromecastPlayable())
        assertTrue(listOf(plexDownload).isChromecastPlayableQueue())
    }

    @Test
    fun localOrNonPlexTracksAreNotChromecastPlayable() {
        val localFolderTrack = Track(
            id = "local:track:1",
            title = "Two",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "",
            downloadUrl = "",
            localUri = "file:///music/two.mp3",
        )
        val plexWithoutStream = Track(
            id = "plex:track:2",
            title = "Three",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "",
            downloadUrl = "",
            localUri = "file:///music/three.flac",
        )

        assertFalse(localFolderTrack.isChromecastPlayable())
        assertFalse(plexWithoutStream.isChromecastPlayable())
        assertFalse(listOf(localFolderTrack, plexWithoutStream).isChromecastPlayableQueue())
    }

    @Test
    fun plexQueueSupportRejectsNonPlexRemoteStreams() {
        val jellyfin = Track(
            id = "jellyfin:track:1",
            title = "Two",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "https://jellyfin.example/Audio/1/stream",
            downloadUrl = "",
        )

        val support = listOf(jellyfin).plexChromecastQueueSupport()

        assertFalse(support.isSupported)
        assertEquals("Chromecast can play Plex streaming songs only.", support.message)
    }

    @Test
    fun remoteChromecastQueueSupportAcceptsRemoteHttpStreams() {
        val plex = Track(
            id = "plex:track:1",
            title = "One",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "https://plex.example/library/parts/1.mp3",
            downloadUrl = "",
        )
        val jellyfin = Track(
            id = "jellyfin:track:1",
            title = "Two",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "http://jellyfin.example/Audio/1/stream",
            downloadUrl = "",
        )

        assertTrue(plex.isRemoteChromecastPlayable())
        assertTrue(jellyfin.isRemoteChromecastPlayable())
        assertTrue(listOf(plex, jellyfin).remoteChromecastQueueSupport().isSupported)
    }

    @Test
    fun remoteChromecastQueueSupportRejectsLocalAndNonReceiverUrls() {
        val local = Track(
            id = "local:track:1",
            title = "Local",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "https://example.test/local.mp3",
            downloadUrl = "",
            localUri = "file:///music/local.mp3",
        )
        val fileUrl = Track(
            id = "plex:track:2",
            title = "File",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "file:///music/file.mp3",
            downloadUrl = "",
        )
        val webBlob = Track(
            id = "plex:track:3",
            title = "Blob",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "phoebe-web-picked-file",
            downloadUrl = "",
        )

        assertFalse("".isCastReceiverLoadableUrl())
        assertFalse(fileUrl.streamUrl.isCastReceiverLoadableUrl())
        assertFalse(webBlob.streamUrl.isCastReceiverLoadableUrl())
        val downloadedRemote = Track(
            id = "jellyfin:track:4",
            title = "Downloaded",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "https://jellyfin.example/Audio/4/stream.mp3",
            downloadUrl = "",
            localUri = "file:///music/downloaded.mp3",
        )

        assertFalse(local.isRemoteChromecastPlayable())
        assertTrue(downloadedRemote.isRemoteChromecastPlayable())
        assertFalse(listOf(fileUrl).remoteChromecastQueueSupport().isSupported)
        assertFalse(listOf(local, webBlob).remoteChromecastQueueSupport().isSupported)
        assertTrue(listOf(downloadedRemote).remoteChromecastQueueSupport().isSupported)
    }

    @Test
    fun connectedCastStateMapsToPlayerStateForSharedTransportUi() {
        val track = Track(
            id = "plex:track:1",
            title = "One",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "https://plex.example/library/parts/1?X-Plex-Token=token",
            downloadUrl = "",
        )
        val fallback = PlayerState(volume = 0.42f)
        val castState = CastState(
            isAvailable = true,
            isConnected = true,
            queue = listOf(track),
            currentIndex = 0,
            isPlaying = true,
            positionMs = 12_000,
            durationMs = 60_000,
        )

        val playerState = castState.asPlayerState(fallback)

        assertEquals(track, playerState.currentTrack)
        assertTrue(playerState.isPlaying)
        assertEquals(12_000, playerState.positionMs)
        assertEquals(0.42f, playerState.volume)
    }

    @Test
    fun directChromecastPlayableCodecsKeepOriginalStreamUrl() {
        val mp3 = Track(
            id = "plex:123",
            title = "One",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "https://plex.example/library/parts/1.mp3?X-Plex-Token=token",
            downloadUrl = "",
            audioCodec = "mp3",
        ).toCastMediaDescriptor()
        val m4a = Track(
            id = "plex:124",
            title = "Two",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "https://plex.example/library/parts/2.m4a?X-Plex-Token=token",
            downloadUrl = "",
            audioCodec = "aac",
        ).toCastMediaDescriptor()

        assertEquals(mp3.streamUrl, mp3.castUrl)
        assertEquals("audio/mpeg", mp3.contentType)
        assertEquals(m4a.streamUrl, m4a.castUrl)
        assertEquals("audio/aac", m4a.contentType)
    }

    @Test
    fun unsupportedPlexCodecsUseUniversalMp3TranscodeUrl() {
        val descriptor = Track(
            id = "plex:12345",
            title = "One",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "https://plex.example:32400/library/parts/1.flac?X-Plex-Token=token",
            downloadUrl = "",
            filepath = "/music/one.flac",
            audioCodec = "flac",
        ).toCastMediaDescriptor()

        assertEquals("audio/mpeg", descriptor.contentType)
        assertEquals(
            "https://plex.example:32400/music/:/transcode/universal/start.mp3?path=%2Flibrary%2Fmetadata%2F12345&mediaIndex=0&partIndex=0&protocol=https&format=mp3&audioCodec=mp3&directPlay=0&directStream=0&X-Plex-Token=token",
            descriptor.castUrl,
        )
    }

    @Test
    fun castMediaMatchAcceptsGeneratedTranscodeUrl() {
        val original = Track(
            id = "plex:12345",
            title = "One",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "https://plex.example:32400/library/parts/1.flac?X-Plex-Token=token",
            downloadUrl = "",
            filepath = "/music/one.flac",
            audioCodec = "flac",
        )
        val descriptor = original.toCastMediaDescriptor()
        val remote = castTrackFromMediaFields(
            trackId = null,
            title = "One",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = descriptor.castUrl,
            castUrl = descriptor.castUrl,
            downloadUrl = null,
            thumbUrl = null,
            filepath = null,
            audioCodec = null,
        )

        assertTrue(original.matchesCastMedia(remote, descriptor.castUrl))
    }
}
