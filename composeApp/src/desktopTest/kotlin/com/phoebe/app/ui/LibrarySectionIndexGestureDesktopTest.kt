package com.phoebe.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.phoebe.app.feature.library.LibraryScrollIndexEntry
import com.phoebe.app.feature.library.LibrarySectionIndex
import com.phoebe.app.feature.library.LibrarySectionIndexMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mobile scrub rail sits on top of the list along the right edge. It must only claim vertical
 * drags, and let taps and horizontal swipes (back gestures) through to whatever is underneath.
 */
@OptIn(ExperimentalTestApi::class)
class LibrarySectionIndexGestureDesktopTest {
    private val entries = ('A'..'Z').mapIndexed { index, c -> LibraryScrollIndexEntry(c.toString(), index * 10) }

    private class Recorder {
        var underlyingTaps = 0
        val selected = mutableListOf<LibraryScrollIndexEntry>()
    }

    private fun runRailTest(
        exclusionTopPx: Float = 0f,
        gesture: TouchInjectionScope.() -> Unit,
        verify: (Recorder) -> Unit,
    ) = runDesktopComposeUiTest(width = 400, height = 800) {
        val recorder = Recorder()
        setContent {
            PhoebeTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    Box(Modifier.fillMaxSize().clickable { recorder.underlyingTaps++ })
                    LibrarySectionIndex(
                        entries = entries,
                        onEntrySelected = { recorder.selected += it },
                        mode = LibrarySectionIndexMode.MobileScrollbar,
                        touchExclusionTopPx = { exclusionTopPx },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
        waitForIdle()
        onRoot().performTouchInput(gesture)
        waitForIdle()
        verify(recorder)
    }

    private fun TouchInjectionScope.railX() = width - 10f

    @Test
    fun tapOnRailPassesThroughToContentUnderneath() = runRailTest(
        gesture = { click(Offset(railX(), height / 2f)) },
        verify = {
            assertEquals(1, it.underlyingTaps, "Tap on the rail should reach the content underneath")
            assertTrue(it.selected.isEmpty(), "Tap should not jump the list")
        },
    )

    @Test
    fun horizontalSwipeFromRailDoesNotScrub() = runRailTest(
        gesture = {
            down(Offset(railX(), height / 2f))
            repeat(10) { moveBy(Offset(-20f, 1f)) }
            up()
        },
        verify = { assertTrue(it.selected.isEmpty(), "Horizontal (back) swipe should not jump the list") },
    )

    @Test
    fun verticalDragOnRailScrubs() = runRailTest(
        gesture = {
            down(Offset(railX(), height * 0.2f))
            repeat(10) { moveBy(Offset(0f, 40f)) }
            up()
        },
        verify = {
            assertTrue(it.selected.isNotEmpty(), "Vertical drag on the rail should scrub")
            assertEquals(0, it.underlyingTaps, "A scrub should not also click the content underneath")
        },
    )

    @Test
    fun dragStartingInExcludedHeaderAreaDoesNotScrub() = runRailTest(
        exclusionTopPx = 200f,
        gesture = {
            down(Offset(railX(), 100f))
            repeat(10) { moveBy(Offset(0f, 40f)) }
            up()
        },
        verify = { assertTrue(it.selected.isEmpty(), "Drags that start on the header should be ignored") },
    )
}
