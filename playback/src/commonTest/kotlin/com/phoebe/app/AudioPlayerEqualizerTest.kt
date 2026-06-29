package com.phoebe.app

import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.player.SimpleAudioPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioPlayerEqualizerTest {
    @Test
    fun setEqualizerNormalizesBeforeApplyingToPlatform() {
        val player = EqualizerRecordingPlayer()

        player.setEqualizer(
            EqualizerProfile(
                enabled = true,
                bandCount = 9,
                gainsDb = listOf(20f),
            ),
        )

        val applied = player.appliedProfiles.single()
        assertEquals(10, applied.bandCount)
        assertEquals(12f, applied.gainsDb.first())
        assertEquals(10, applied.gainsDb.size)
        assertTrue(applied.enabled)
    }

    @Test
    fun setEqualizerSkipsUnchangedNormalizedProfile() {
        val player = EqualizerRecordingPlayer()
        val profile = EqualizerProfile(
            enabled = true,
            bandCount = 10,
            gainsDb = List(10) { 1f },
        )

        player.setEqualizer(profile)
        player.setEqualizer(profile.copy(gainsDb = List(10) { 1.1f }))

        assertEquals(1, player.appliedProfiles.size)
    }
}

private class EqualizerRecordingPlayer : SimpleAudioPlayer() {
    val appliedProfiles = mutableListOf<EqualizerProfile>()

    override fun playUri(uri: String) = Unit

    override fun applyEqualizer(profile: EqualizerProfile) {
        appliedProfiles += profile
    }
}
