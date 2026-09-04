package com.phoebe.app

import com.phoebe.app.data.ArtworkAuthHolder
import com.phoebe.app.data.ArtworkOriginHolder
import com.phoebe.app.domain.PlayerState
import com.phoebe.app.domain.Track
import com.phoebe.app.player.CastState
import com.phoebe.app.player.EmptyChromecastQueueMessage
import com.phoebe.app.player.asPlayerState
import com.phoebe.app.player.isChromecastPlayable
import com.phoebe.app.player.isChromecastPlayableQueue
import com.phoebe.app.player.matchesCastMedia
import com.phoebe.app.player.castTrackFromMediaFields
import com.phoebe.app.player.chromecastQueueSupport
import com.phoebe.app.player.isCastReceiverLoadableUrl
import com.phoebe.app.player.shouldClearEmptyCastState
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
    fun relativePlexStreamIsBoundBeforeCastValidationAndLoad() {
        val liveOrigin = "https://plex.example:32400"
        ArtworkOriginHolder.update(liveOrigin)
        ArtworkAuthHolder.update("token")
        try {
            val track = Track(
                id = "plex:track:1",
                title = "One",
                artist = "Artist",
                album = "Album",
                durationMs = 60_000,
                streamUrl = "/library/parts/1/file.mp3",
                downloadUrl = "/library/parts/1/file.mp3",
            )

            assertTrue(track.isChromecastPlayable())
            assertEquals(
                "$liveOrigin/library/parts/1/file.mp3?X-Plex-Token=token",
                track.toCastMediaDescriptor().castUrl,
            )
        } finally {
            ArtworkOriginHolder.clear()
            ArtworkAuthHolder.clear()
        }
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
    fun tracksWithoutARemoteStreamAreNotChromecastPlayable() {
        val localFolderTrack = Track(
            id = "local_1:track:1",
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
    fun queueSupportAcceptsEveryProvidersHttpStreams() {
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
        val navidrome = Track(
            id = "navidrome:track:1",
            title = "Three",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "http://navidrome.example/rest/stream.view?u=user&t=token&s=salt&id=1",
            downloadUrl = "",
            audioCodec = "MP3",
        )

        assertTrue(plex.isChromecastPlayable())
        assertTrue(jellyfin.isChromecastPlayable())
        assertTrue(navidrome.isChromecastPlayable())
        assertTrue(listOf(plex, jellyfin, navidrome).chromecastQueueSupport().isSupported)
    }

    @Test
    fun queueSupportAcceptsRadioStreamsAsLiveMedia() {
        val radio = Track(
            id = "radio:station-1",
            title = "Some Station",
            artist = "Radio",
            album = "Radio",
            durationMs = 0L,
            streamUrl = "https://stream.example/zc1201/hls.m3u8",
            downloadUrl = "https://stream.example/zc1201/hls.m3u8",
        )

        val descriptor = radio.toCastMediaDescriptor()

        assertTrue(listOf(radio).chromecastQueueSupport().isSupported)
        assertEquals("https://stream.example/zc1201/hls.m3u8", descriptor.castUrl)
        assertEquals("application/x-mpegurl", descriptor.contentType)
        assertTrue(descriptor.isLiveStream)
    }

    @Test
    fun queueSupportRejectsLocalAndNonReceiverUrls() {
        val localOnly = Track(
            id = "local_1:track:1",
            title = "Local",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "",
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

        assertFalse(localOnly.isChromecastPlayable())
        assertTrue(downloadedRemote.isChromecastPlayable())
        assertFalse(listOf(fileUrl).chromecastQueueSupport().isSupported)
        assertFalse(listOf(localOnly, webBlob).chromecastQueueSupport().isSupported)
        assertTrue(listOf(downloadedRemote).chromecastQueueSupport().isSupported)
    }

    @Test
    fun queueSupportNamesTheSongBlockingTheCast() {
        val streamable = Track(
            id = "navidrome:track:1",
            title = "Streamable",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "http://navidrome.example/rest/stream.view?id=1",
            downloadUrl = "",
        )
        val localOnly = Track(
            id = "local_1:track:1",
            title = "On This Laptop",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "",
            downloadUrl = "",
            localUri = "file:///music/local.mp3",
        )

        val support = listOf(streamable, localOnly).chromecastQueueSupport()

        assertFalse(support.isSupported)
        assertEquals("“On This Laptop” plays from this device, so it can't be cast.", support.message)
        assertEquals(
            EmptyChromecastQueueMessage,
            emptyList<Track>().chromecastQueueSupport().message,
        )
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
    fun emptyConfirmedReceiverClearsStaleCastState() {
        assertTrue(
            shouldClearEmptyCastState(
                hasRemoteStatus = true,
                hasRemoteMedia = false,
                isCastConnected = true,
                hasPendingHandoff = false,
            ),
        )
    }

    @Test
    fun emptyReceiverDoesNotClearBeforeStatusOrDuringHandoff() {
        assertFalse(
            shouldClearEmptyCastState(
                hasRemoteStatus = false,
                hasRemoteMedia = false,
                isCastConnected = true,
                hasPendingHandoff = false,
            ),
        )
        assertFalse(
            shouldClearEmptyCastState(
                hasRemoteStatus = true,
                hasRemoteMedia = false,
                isCastConnected = true,
                hasPendingHandoff = true,
            ),
        )
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

    @Test
    fun relativePlexArtworkIsBoundToReceiverLoadableUrl() {
        val liveOrigin = "https://plex.example:32400"
        ArtworkOriginHolder.update(liveOrigin)
        ArtworkAuthHolder.update("token")
        try {
            val track = Track(
                id = "plex:track:1",
                title = "One",
                artist = "Artist",
                album = "Album",
                durationMs = 60_000,
                streamUrl = "/library/parts/1/file.mp3",
                downloadUrl = "/library/parts/1/file.mp3",
                thumbUrl = "/library/metadata/1/thumb/2",
            )

            val descriptor = track.toCastMediaDescriptor()
            assertEquals(
                "$liveOrigin/library/metadata/1/thumb/2?X-Plex-Token=token",
                descriptor.thumbUrl,
            )
            assertTrue(descriptor.thumbUrl.orEmpty().isCastReceiverLoadableUrl())
        } finally {
            ArtworkOriginHolder.clear()
            ArtworkAuthHolder.clear()
        }
    }

    @Test
    fun plexArtworkResolvesOriginAndTokenFromStreamUrlIfArtworkOriginHolderEmpty() {
        ArtworkOriginHolder.clear()
        ArtworkAuthHolder.clear()
        val track = Track(
            id = "plex:track:1",
            title = "One",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "https://192-168-1-50.abc.plex.direct:32400/library/parts/1/file.mp3?X-Plex-Token=fresh-token",
            downloadUrl = "",
            thumbUrl = "/library/metadata/1/thumb/2",
        )
        val descriptor = track.toCastMediaDescriptor()
        assertEquals(
            "https://192-168-1-50.abc.plex.direct:32400/library/metadata/1/thumb/2?X-Plex-Token=fresh-token",
            descriptor.thumbUrl,
        )
        assertTrue(descriptor.thumbUrl.orEmpty().isCastReceiverLoadableUrl())
    }

    @Test
    fun downloadedPlexTrackBindsArtworkForCast() {
        val liveOrigin = "https://plex.example:32400"
        ArtworkOriginHolder.update(liveOrigin)
        ArtworkAuthHolder.update("token")
        try {
            val downloadedTrack = Track(
                id = "plex:track:1",
                title = "One",
                artist = "Artist",
                album = "Album",
                durationMs = 60_000,
                streamUrl = "/library/parts/1/file.mp3",
                downloadUrl = "",
                localUri = "file:///music/one.mp3",
                localArtworkUri = "file:///music/one.jpg",
                thumbUrl = "/library/metadata/1/thumb/2",
            )

            val descriptor = downloadedTrack.toCastMediaDescriptor()
            assertEquals(
                "$liveOrigin/library/metadata/1/thumb/2?X-Plex-Token=token",
                descriptor.thumbUrl,
            )
            assertTrue(descriptor.thumbUrl.orEmpty().isCastReceiverLoadableUrl())
            assertEquals(
                "$liveOrigin/library/parts/1/file.mp3?X-Plex-Token=token",
                descriptor.castUrl,
            )
        } finally {
            ArtworkOriginHolder.clear()
            ArtworkAuthHolder.clear()
        }
    }

    @Test
    fun localFileArtworkIsNotPassedToCastReceiver() {
        val track = Track(
            id = "local:1",
            title = "One",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "https://example.com/audio.mp3",
            downloadUrl = "",
            localArtworkUri = "file:///local/cover.jpg",
            thumbUrl = "file:///local/cover.jpg",
        )
        val descriptor = track.toCastMediaDescriptor()
        assertEquals(null, descriptor.thumbUrl)
    }

    @Test
    fun remoteNonPlexArtworkKeepsRemoteUrl() {
        val track = Track(
            id = "jellyfin:1",
            title = "One",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            streamUrl = "https://jellyfin.example/audio.mp3",
            downloadUrl = "",
            thumbUrl = "https://jellyfin.example/Items/1/Images/Primary?api_key=token",
        )
        val descriptor = track.toCastMediaDescriptor()
        assertEquals(
            "https://jellyfin.example/Items/1/Images/Primary?api_key=token",
            descriptor.thumbUrl,
        )
        assertTrue(descriptor.thumbUrl.orEmpty().isCastReceiverLoadableUrl())
    }
}
