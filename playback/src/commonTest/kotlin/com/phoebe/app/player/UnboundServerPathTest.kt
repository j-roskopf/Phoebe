package com.phoebe.app.player

import com.phoebe.app.domain.Track
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A host-less server path must never reach the platform player. ExoPlayer reinterprets it as a
 * local file and reports "open failed: ENOENT" only once the startup watchdog expires, so the
 * user sees a ~9s hang followed by a wrong error instead of "can't reach the server".
 */
class UnboundServerPathTest {
    @Test
    fun relativePlexPartKeyIsUnbound() {
        assertTrue(isUnboundServerPath("/library/parts/36576/file.mp3"))
        assertTrue(isUnboundServerPath("/library/parts/36576/file.mp3?download=1"))
        assertTrue(isUnboundServerPath("/music/:/transcode/universal/start.mp3?maxAudioBitrate=320"))
        assertTrue(isUnboundServerPath("/photo/:/transcode?width=512"))
        assertTrue(isUnboundServerPath("/:/timeline"))
        assertTrue(isUnboundServerPath("/playlists/12/items"))
    }

    @Test
    fun boundAbsoluteUrlIsPlayable() {
        assertFalse(
            isUnboundServerPath(
                "https://45-79-210-225.abc.plex.direct:8443/library/parts/36576/file.mp3?X-Plex-Token=t",
            ),
        )
        assertFalse(isUnboundServerPath("http://192.168.1.20:32400/library/parts/1/file.flac"))
    }

    /**
     * The regression this guard exists for: [com.phoebe.app.data.isPlexMediaPathOrUrl] matches any
     * scheme-less string merely *containing* `/library/`, so a downloaded file living under a
     * `library` folder would have been misread as an unplayable server path.
     */
    @Test
    fun localFilesAreNeverTreatedAsUnbound() {
        assertFalse(isUnboundServerPath("file:///Users/joe/Music/library/song.flac"))
        assertFalse(isUnboundServerPath("content://media/external/audio/media/42"))
        assertFalse(isUnboundServerPath("/storage/emulated/0/Music/library/song.flac"))
        assertFalse(isUnboundServerPath("/Users/joe/Library/Phoebe/downloads/song.mp3"))
        assertFalse(isUnboundServerPath(""))
    }

    @Test
    fun downloadedTrackDoesNotHoldARelativePathEvenWithAServerStreamUrl() {
        val downloaded = Track(
            id = "track:1",
            title = "One",
            artist = "Artist",
            album = "Album",
            durationMs = 1_000,
            streamUrl = "/library/parts/36576/file.mp3",
            downloadUrl = "/library/parts/36576/file.mp3",
            localUri = "file:///data/user/0/com.phoebe.app/files/downloads/one.mp3",
        )

        // A downloaded song must stay playable while the server is unreachable.
        assertFalse(downloaded.holdsRelativePlexPath())
    }

    @Test
    fun streamingTrackWithNoOriginHoldsARelativePath() {
        val streaming = Track(
            id = "track:2",
            title = "Two",
            artist = "Artist",
            album = "Album",
            durationMs = 1_000,
            streamUrl = "/library/parts/36576/file.mp3",
            downloadUrl = "/library/parts/36576/file.mp3",
        )

        assertTrue(streaming.holdsRelativePlexPath())
    }
}
