package com.phoebe.app.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlexUrlBindingTest {
    @AfterTest
    fun tearDown() {
        ArtworkAuthHolder.clear()
        ArtworkOriginHolder.clear()
    }

    @Test
    fun plexAssetPathKeepsRelativeCatalogThumbsHostFree() {
        assertEquals(
            "/library/metadata/1/thumb/2",
            plexAssetPath("/library/metadata/1/thumb/2"),
        )
        assertEquals(
            "/library/metadata/1/thumb/2",
            plexAssetPath("library/metadata/1/thumb/2"),
        )
    }

    @Test
    fun plexAssetPathStripsLegacyAbsoluteHostsAndTokens() {
        assertEquals(
            "/library/metadata/1/thumb/2",
            plexAssetPath(
                "https://23-239-17-63.abc.plex.direct:8443/library/metadata/1/thumb/2?X-Plex-Token=old",
            ),
        )
        assertEquals(
            "/library/parts/1/file.mp3?download=1",
            plexAssetPath(
                "https://plex.example:32400/library/parts/1/file.mp3?download=1&X-Plex-Token=old",
            ),
        )
    }

    @Test
    fun bindPlexUrlRebuildsRelativeAndLegacyAbsoluteOntoLiveBase() {
        val live = "http://192.168.1.9:32400"
        assertEquals(
            "http://192.168.1.9:32400/library/metadata/1/thumb/2?X-Plex-Token=fresh",
            bindPlexUrl("/library/metadata/1/thumb/2", live, "fresh"),
        )
        assertEquals(
            "http://192.168.1.9:32400/library/metadata/1/thumb/2?X-Plex-Token=fresh",
            bindPlexUrl(
                "https://23-239-17-63.abc.plex.direct:8443/library/metadata/1/thumb/2?X-Plex-Token=old",
                live,
                "fresh",
            ),
        )
    }

    @Test
    fun bindPlexUrlLeavesNonPlexUrlsAlone() {
        val radio = "https://kexp.streamguys1.com/kexp128.mp3"
        assertEquals(radio, bindPlexUrl(radio, "http://192.168.1.9:32400", "token"))
        assertNull(plexAssetPath(radio))
    }

    @Test
    fun bindPlexCoverArtUsesPhotoTranscodeForSizedListTiles() {
        val url = bindPlexCoverArt(
            "/library/metadata/1/thumb/2",
            "http://192.168.1.9:32400",
            "fresh",
            size = 160,
        )
        assertTrue(url.startsWith("http://192.168.1.9:32400/photo/:/transcode?"))
        assertTrue("width=160" in url)
        assertTrue("height=160" in url)
        assertTrue("minSize=1" in url)
        assertTrue("upscale=1" in url)
        assertTrue("url=%2Flibrary%2Fmetadata%2F1%2Fthumb%2F2" in url)
        assertTrue("X-Plex-Token=fresh" in url)
    }

    @Test
    fun bindPlexCoverArtFullSizeIsDirectThumb() {
        assertEquals(
            "http://192.168.1.9:32400/library/metadata/1/thumb/2?X-Plex-Token=fresh",
            bindPlexCoverArt("/library/metadata/1/thumb/2", "http://192.168.1.9:32400", "fresh"),
        )
    }

    @Test
    fun relativePathsAreTreatedAsPlexMedia() {
        assertTrue("/library/metadata/1/thumb/2".isPlexMediaPathOrUrl())
        assertTrue("/library/parts/1/file.mp3".isPlexMediaPathOrUrl())
        assertTrue(
            "https://23-239-17-63.abc.plex.direct:8443/library/metadata/1/thumb/2?X-Plex-Token=t"
                .isPlexMediaPathOrUrl(),
        )
    }
}
