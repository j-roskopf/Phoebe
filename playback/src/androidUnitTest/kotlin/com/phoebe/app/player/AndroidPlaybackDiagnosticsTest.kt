package com.phoebe.app.player

import com.phoebe.app.domain.RepeatMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidPlaybackDiagnosticsTest {
    @Test
    fun platformQueueWindowKeepsUpcomingSkipsOnTheExistingMedia3Player() {
        assertEquals(24, AndroidPlatformQueueWindowSize)
        assertEquals(24, platformQueueWindowEndExclusive(0, 50, RepeatMode.Off))
        assertEquals(50, platformQueueWindowEndExclusive(42, 50, RepeatMode.All))
    }

    @Test
    fun platformQueueWindowKeepsRepeatOneOnTheCurrentTrack() {
        assertEquals(43, platformQueueWindowEndExclusive(42, 50, RepeatMode.One))
    }

    @Test
    fun media3LoadControlUsesRelaxedProfileForForegroundUnmeteredPlayback() {
        val profile = PhoebeLoadControlConfig.profileFor(
            engine = PlaybackEnginePath.Media3,
            constrainedNetwork = false,
            uiVisible = true,
        )

        assertEquals(PhoebeLoadControlConfig.RelaxedMainMinBufferMs, profile.minBufferMs)
        assertEquals(PhoebeLoadControlConfig.RelaxedMainMaxBufferMs, profile.maxBufferMs)
        assertEquals(PhoebeLoadControlConfig.RelaxedMainTargetBufferBytes, profile.targetBufferBytes)
    }

    @Test
    fun media3LoadControlBuildsMoreRunwayOnConstrainedNetwork() {
        val wifi = PhoebeLoadControlConfig.profileFor(
            engine = PlaybackEnginePath.Media3,
            constrainedNetwork = false,
            uiVisible = true,
        )
        val cellular = PhoebeLoadControlConfig.profileFor(
            engine = PlaybackEnginePath.Media3,
            constrainedNetwork = true,
            uiVisible = true,
        )

        assertTrue(cellular.minBufferMs > wifi.minBufferMs)
        assertTrue(cellular.maxBufferMs > wifi.maxBufferMs)
        assertTrue(cellular.targetBufferBytes < wifi.targetBufferBytes)
        assertEquals(PhoebeLoadControlConfig.ConstrainedMainMinBufferMs, cellular.minBufferMs)
        assertEquals(PhoebeLoadControlConfig.ConstrainedMainMaxBufferMs, cellular.maxBufferMs)
        assertEquals(PhoebeLoadControlConfig.ConstrainedMainTargetBufferBytes, cellular.targetBufferBytes)
    }

    @Test
    fun media3LoadControlUsesResilientBufferWhenUiIsHidden() {
        val foreground = PhoebeLoadControlConfig.profileFor(
            engine = PlaybackEnginePath.Media3,
            constrainedNetwork = false,
            uiVisible = true,
        )
        val background = PhoebeLoadControlConfig.profileFor(
            engine = PlaybackEnginePath.Media3,
            constrainedNetwork = false,
            uiVisible = false,
        )

        assertTrue(background.minBufferMs > foreground.minBufferMs)
        assertTrue(background.maxBufferMs > foreground.maxBufferMs)
        assertTrue(background.targetBufferBytes < foreground.targetBufferBytes)
        assertEquals(PhoebeLoadControlConfig.ConstrainedMainMinBufferMs, background.minBufferMs)
        assertEquals(PhoebeLoadControlConfig.ConstrainedMainMaxBufferMs, background.maxBufferMs)
        assertEquals(PhoebeLoadControlConfig.ConstrainedMainTargetBufferBytes, background.targetBufferBytes)
    }

    @Test
    fun media3CrossfadeUsesShortLivedBufferProfile() {
        val main = PhoebeLoadControlConfig.profileFor(PlaybackEnginePath.Media3)
        val crossfade = PhoebeLoadControlConfig.profileFor(PlaybackEnginePath.Media3Crossfade)

        assertTrue(crossfade.minBufferMs < main.minBufferMs)
        assertTrue(crossfade.maxBufferMs < main.maxBufferMs)
        assertTrue(crossfade.targetBufferBytes < main.targetBufferBytes)
        assertTrue(crossfade.maxBufferMs <= 12_000)
    }

    @Test
    fun media3LoadControlUsesTighterBuffersForDataSaver() {
        val constrained = PhoebeLoadControlConfig.profileFor(
            engine = PlaybackEnginePath.Media3,
            constrainedNetwork = true,
            uiVisible = true,
            dataSaver = false,
        )
        val dataSaver = PhoebeLoadControlConfig.profileFor(
            engine = PlaybackEnginePath.Media3,
            constrainedNetwork = true,
            uiVisible = true,
            dataSaver = true,
        )
        assertTrue(dataSaver.minBufferMs < constrained.minBufferMs)
        assertTrue(dataSaver.maxBufferMs < constrained.maxBufferMs)
        assertTrue(dataSaver.targetBufferBytes < constrained.targetBufferBytes)
        assertEquals(PhoebeLoadControlConfig.DataSaverMainMinBufferMs, dataSaver.minBufferMs)
        assertEquals(PhoebeLoadControlConfig.DataSaverMainMaxBufferMs, dataSaver.maxBufferMs)
        assertEquals(PhoebeLoadControlConfig.DataSaverMainTargetBufferBytes, dataSaver.targetBufferBytes)
    }

    @Test
    fun bufferDurationsRemainValidForMedia3Builder() {
        val profiles = listOf(
            PhoebeLoadControlConfig.profileFor(
                engine = PlaybackEnginePath.Media3,
                constrainedNetwork = false,
                uiVisible = true,
            ),
            PhoebeLoadControlConfig.profileFor(
                engine = PlaybackEnginePath.Media3,
                constrainedNetwork = true,
                uiVisible = false,
            ),
            PhoebeLoadControlConfig.profileFor(
                engine = PlaybackEnginePath.Media3,
                constrainedNetwork = true,
                uiVisible = true,
                dataSaver = true,
            ),
            PhoebeLoadControlConfig.profileFor(PlaybackEnginePath.Media3Crossfade),
        )

        profiles.forEach { profile ->
            assertTrue(
                PhoebeLoadControlConfig.bufferForPlaybackAfterRebufferMs(profile) <= profile.minBufferMs,
            )
            assertTrue(profile.maxBufferMs >= profile.minBufferMs)
            assertTrue(profile.targetBufferBytes > 0)
        }

        assertEquals(
            PhoebeLoadControlConfig.BufferForPlaybackAfterRebufferMs,
            PhoebeLoadControlConfig.bufferForPlaybackAfterRebufferMs(profiles.first()),
        )
        assertEquals(
            PhoebeLoadControlConfig.CrossfadeMinBufferMs,
            PhoebeLoadControlConfig.bufferForPlaybackAfterRebufferMs(profiles.last()),
        )
    }
}
