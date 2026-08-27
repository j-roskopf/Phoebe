package com.phoebe.app.feature.playback

import com.phoebe.app.ui.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import com.phoebe.app.domain.Track
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SwipeableMobileArtwork(
    track: Track,
    nextTrack: Track?,
    previousTrack: Track?,
    swipeOffset: Float,
    modifier: Modifier = Modifier,
    trackContent: @Composable (Track) -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .semantics {
                contentDescription = "Album artwork. Swipe left for next track, swipe right for previous track."
            },
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            // Keep neighbors mounted off-screen. Page offsets are relative to the center
            // queue item ([previousTrack], track, [nextTrack]) so a track change with
            // swipeOffset == 0 keeps the new center tile visually stable.
            val pages = remember(track, nextTrack, previousTrack) {
                buildList {
                    if (previousTrack != null) {
                        add(previousTrack)
                    }
                    add(track)
                    if (nextTrack != null) {
                        add(nextTrack)
                    }
                }
            }
            val centerPageIndex = when {
                previousTrack != null -> 1
                else -> 0
            }

            for ((index, pageTrack) in pages.withIndex()) {
                key(pageTrack.id) {
                    val pageOffsetPx = (index - centerPageIndex) * widthPx + swipeOffset
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset {
                                IntOffset(pageOffsetPx.roundToInt(), 0)
                            }
                            .graphicsLayer {
                                if (index == centerPageIndex) {
                                    val dragProgress = (abs(swipeOffset) / widthPx).coerceIn(0f, 1f)
                                    val scale = 1f - dragProgress * 0.03f
                                    scaleX = scale
                                    scaleY = scale
                                }
                            },
                    ) {
                        trackContent(pageTrack)
                    }
                }
            }
        }
    }
}
