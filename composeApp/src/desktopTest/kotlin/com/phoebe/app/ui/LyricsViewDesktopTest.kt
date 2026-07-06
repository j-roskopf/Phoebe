package com.phoebe.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.phoebe.app.domain.LyricsAnnotation
import com.phoebe.app.domain.LyricsAnnotationTarget
import com.phoebe.app.domain.LyricsAnnotations
import com.phoebe.app.domain.LyricsDocument
import com.phoebe.app.domain.LyricsLine
import com.phoebe.app.domain.LyricsLoadState
import com.phoebe.app.domain.LyricsSource
import com.phoebe.app.domain.Track
import com.phoebe.app.feature.lyrics.LyricsView
import kotlin.test.Test

class LyricsViewDesktopTest {
    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun compactAnnotatedLyricTapShowsGeniusSheet() = runDesktopComposeUiTest(width = 430, height = 760) {
        setContent {
            PhoebeTheme {
                Box(Modifier.size(430.dp, 760.dp)) {
                    LyricsView(
                        track = lyricsTrack,
                        currentTrackId = lyricsTrack.id,
                        positionMs = 0L,
                        state = LyricsLoadState.Loaded(annotatedLyricsDocument),
                        onRetry = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        onNodeWithContentDescription("Annotated lyric. 1 Genius annotations").performClick()
        mainClock.advanceTimeBy(320)
        waitForIdle()

        onNodeWithText("This is the annotation body.").assertIsDisplayed()
    }

    private companion object {
        val lyricsTrack = Track(
            id = "lyrics-test-track",
            title = "Lyrics Test",
            artist = "Phoebe",
            album = "Regression Tests",
            durationMs = 180_000L,
            streamUrl = "https://example.test/audio.mp3",
            downloadUrl = "https://example.test/audio.mp3",
        )

        val annotatedLyricsDocument = LyricsDocument(
            trackFingerprint = "lyrics-test-track",
            lines = listOf(
                LyricsLine(startMs = null, text = "Annotated lyric"),
                LyricsLine(startMs = null, text = "Plain lyric"),
            ),
            source = LyricsSource.Lrclib,
            synced = false,
            annotations = LyricsAnnotations(
                songId = 42L,
                songUrl = "https://genius.com/test-song",
                songTitle = "Lyrics Test",
                artistName = "Phoebe",
                fetchedAtMs = 0L,
                annotations = listOf(
                    LyricsAnnotation(
                        id = 100L,
                        referentId = 200L,
                        fragment = "Annotated lyric",
                        body = "This is the annotation body.",
                        target = LyricsAnnotationTarget(lineIndexes = listOf(0)),
                        authorName = "Genius tester",
                        votesTotal = 3,
                        url = "https://genius.com/test-song#note-100",
                    ),
                ),
            ),
        )
    }
}
