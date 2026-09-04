package com.phoebe.app.player

import android.app.Application
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.SessionResult
import com.phoebe.app.data.ArtworkAuthHolder
import com.phoebe.app.data.ArtworkOriginHolder
import com.phoebe.app.domain.Track
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowseMediaItemsTest {
    @Test
    fun playbackMediaItemIncludesCarDisplayMetadata() {
        val item = playbackMediaItem(
            Track(
                id = "track-1",
                title = "Foam",
                artist = "Divine Sweater",
                album = "Down Deep",
                durationMs = 188_000,
                streamUrl = "https://example.test/audio.mp3",
                downloadUrl = "https://example.test/download.mp3",
                thumbUrl = "https://example.test/art.jpg",
            ),
        )

        val metadata = item.mediaMetadata

        assertEquals("track-1", item.mediaId)
        assertEquals("Foam", metadata.title.toString())
        assertEquals("Foam", metadata.displayTitle.toString())
        assertEquals("Divine Sweater", metadata.artist.toString())
        assertEquals("Divine Sweater", metadata.albumArtist.toString())
        assertEquals("Divine Sweater", metadata.subtitle.toString())
        assertEquals("Down Deep", metadata.albumTitle.toString())
        assertEquals("Divine Sweater - Down Deep", metadata.description.toString())
        assertEquals(188_000, metadata.durationMs)
        assertTrue(metadata.isPlayable == true)
        assertFalse(metadata.isBrowsable == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, metadata.mediaType)
    }

    @Test
    fun browseTrackItemHandlesMissingPlayableUri() {
        val item = browseTrackItem(
            Track(
                id = "missing-uri",
                title = "Missing URI",
                artist = "Fixture",
                album = "Tests",
                durationMs = 0,
                streamUrl = "",
                downloadUrl = "",
                localUri = null,
            ),
        )

        assertEquals("missing-uri", item.mediaId)
        assertEquals("", item.localConfiguration?.uri.toString())
    }

    @Test
    fun pagedPlaylistSelectionExpandsToFullParentQueue() = runTest {
        val parentId = BrowseMediaIds.playlist("playlist-1")
        val tracks = (0 until 150).map { index ->
            Track(
                id = "track-$index",
                title = "Track $index",
                artist = "Artist",
                album = "Album",
                durationMs = 180_000,
                streamUrl = "https://example.test/$index.mp3",
                downloadUrl = "",
            )
        }
        val firstPage = tracks.take(100).map { track ->
            browseTrackItem(track, BrowseMediaIds.track(parentId, track.id))
        }
        val source = FakeCatalogBrowseSource(tracks)

        val expanded = expandPlaybackMediaItems(source, firstPage, startIndex = 99)

        assertEquals(150, expanded?.tracks?.size)
        assertEquals(150, expanded?.items?.size)
        assertEquals(99, expanded?.startIndex)
        assertEquals("track-149", expanded?.tracks?.last()?.id)
    }

    @Test
    fun ordinaryMultiItemQueuesAreNotExpanded() = runTest {
        val tracks = (0 until 3).map { index ->
            Track(
                id = "track-$index",
                title = "Track $index",
                artist = "Artist",
                album = "Album",
                durationMs = 180_000,
                streamUrl = "https://example.test/$index.mp3",
                downloadUrl = "",
            )
        }
        val searchResultItems = tracks.map { browseTrackItem(it) }
        val source = FakeCatalogBrowseSource(tracks)

        val expanded = expandPlaybackMediaItems(source, searchResultItems, startIndex = 1)

        assertNull(expanded)
    }

    /**
     * Android Auto renders artwork in its own process, so a catalog-relative thumb path has to
     * be bound to the live base and token before it leaves the service.
     */
    @Test
    fun browseArtworkBindsRelativePlexThumbsToTheLiveOrigin() = withPlexOrigin("https://pms.test:32400", "abc123") {
        val item = browseTrackItem(plexTrack())

        assertEquals(
            "https://pms.test:32400/library/metadata/42/thumb/1700000000?X-Plex-Token=abc123",
            item.mediaMetadata.artworkUri.toString(),
        )
    }

    @Test
    fun browseArtworkIsOmittedRatherThanLeftRelativeWhenNoOriginIsBound() =
        withPlexOrigin(origin = null, token = null) {
            assertNull(browseTrackItem(plexTrack()).mediaMetadata.artworkUri)
        }

    @Test
    fun browseArtworkPassesThroughAbsoluteNonPlexUrls() = withPlexOrigin(origin = null, token = null) {
        val item = browseTrackItem(
            plexTrack().copy(thumbUrl = "https://cdn.example.test/art.jpg"),
        )

        assertEquals("https://cdn.example.test/art.jpg", item.mediaMetadata.artworkUri.toString())
    }

    @Test
    fun playbackUriBindsRelativePlexStreamPathToTheLiveOrigin() =
        withPlexOrigin("https://pms.test:32400", "abc123") {
            val item = browseTrackItem(plexTrack())

            assertEquals(
                "https://pms.test:32400/library/parts/99/file.flac?X-Plex-Token=abc123",
                item.localConfiguration?.uri.toString(),
            )
        }

    /**
     * Without an origin the URI stays host-less; ExoPlayer treats that as a local file and the
     * car shows a bare "Source error". Fail the session request instead.
     */
    @Test
    fun unboundPlaybackUriFailsTheRequestInsteadOfReachingExoPlayer() =
        withPlexOrigin(origin = null, token = null) {
            val items = listOf(browseTrackItem(plexTrack()))

            assertFailsWith<UnsupportedOperationException> { items.requireBoundUris() }
        }

    @Test
    fun boundPlaybackUrisPassThroughUnchanged() = withPlexOrigin("https://pms.test:32400", "abc123") {
        val items = listOf(browseTrackItem(plexTrack()))

        assertEquals(items, items.requireBoundUris())
    }

    @Test
    fun externalNextCommandUsesPhoebeQueueWhenPlatformWindowHasNoNext() {
        var localNextCalls = 0
        var castNextCalls = 0

        val result = handleExternalQueueNavigationCommand(
            playerCommand = Player.COMMAND_SEEK_TO_NEXT,
            isCastActive = false,
            hasNextTrack = true,
            hasPreviousTrack = false,
            onSkipNext = { localNextCalls++ },
            onSkipPrevious = null,
            onCastSkipNext = { castNextCalls++ },
            onCastSkipPrevious = null,
        )

        assertEquals(SessionResult.RESULT_INFO_SKIPPED, result)
        assertEquals(1, localNextCalls)
        assertEquals(0, castNextCalls)
    }

    @Test
    fun externalNextCommandFallsThroughAtEndOfPhoebeQueue() {
        var localNextCalls = 0

        val result = handleExternalQueueNavigationCommand(
            playerCommand = Player.COMMAND_SEEK_TO_NEXT,
            isCastActive = false,
            hasNextTrack = false,
            hasPreviousTrack = false,
            onSkipNext = { localNextCalls++ },
            onSkipPrevious = null,
            onCastSkipNext = null,
            onCastSkipPrevious = null,
        )

        assertNull(result)
        assertEquals(0, localNextCalls)
    }

    @Test
    fun externalCastNextCommandKeepsUsingCastQueueHandler() {
        var localNextCalls = 0
        var castNextCalls = 0

        val result = handleExternalQueueNavigationCommand(
            playerCommand = Player.COMMAND_SEEK_TO_NEXT,
            isCastActive = true,
            hasNextTrack = true,
            hasPreviousTrack = false,
            onSkipNext = { localNextCalls++ },
            onSkipPrevious = null,
            onCastSkipNext = { castNextCalls++ },
            onCastSkipPrevious = null,
        )

        assertEquals(SessionResult.RESULT_INFO_SKIPPED, result)
        assertEquals(0, localNextCalls)
        assertEquals(1, castNextCalls)
    }

}

/** A catalog row as Plex stores it: host-less part key and thumb, no token. */
private fun plexTrack(): Track = Track(
    id = "plex:1234",
    title = "Foam",
    artist = "Divine Sweater",
    album = "Down Deep",
    durationMs = 188_000,
    streamUrl = "/library/parts/99/file.flac",
    downloadUrl = "/library/parts/99/file.flac",
    thumbUrl = "/library/metadata/42/thumb/1700000000",
)

private fun withPlexOrigin(origin: String?, token: String?, body: () -> Unit) {
    val previousOrigin = ArtworkOriginHolder.liveOrigin
    val previousToken = ArtworkAuthHolder.plexToken
    ArtworkOriginHolder.update(origin)
    ArtworkAuthHolder.update(token)
    try {
        body()
    } finally {
        ArtworkOriginHolder.update(previousOrigin)
        ArtworkAuthHolder.update(previousToken)
    }
}

private class FakeCatalogBrowseSource(
    private val tracks: List<Track>,
) : CatalogBrowseSource {
    override suspend fun getLibraryRoot() = browseFolderItem(BrowseMediaIds.ROOT, "Phoebe")

    override suspend fun getChildren(parentId: String) = emptyList<MediaItem>()

    override suspend fun getItem(mediaId: String) = null

    override suspend fun resolveTracks(mediaItems: List<MediaItem>): List<Track> =
        mediaItems.mapNotNull { item ->
            val trackId = BrowseMediaIds.parseTrackId(item.mediaId)?.trackId ?: item.mediaId
            tracks.firstOrNull { it.id == trackId }
        }

    override suspend fun expandPlayableItem(mediaItem: MediaItem): List<Track> =
        if (BrowseMediaIds.parseTrackId(mediaItem.mediaId) != null) {
            tracks
        } else {
            resolveTracks(listOf(mediaItem))
        }

    override fun startIndexForMediaItem(
        mediaItem: MediaItem,
        tracks: List<Track>,
        fallback: Int,
    ): Int {
        val trackId = BrowseMediaIds.parseTrackId(mediaItem.mediaId)?.trackId ?: mediaItem.mediaId
        return tracks.indexOfFirst { it.id == trackId }
            .takeIf { it >= 0 }
            ?: fallback.takeIf { it in tracks.indices }
            ?: 0
    }

    override suspend fun searchTracks(
        query: String,
        extras: Bundle?,
    ): List<Track> = tracks
}
