package com.phoebe.app

import com.phoebe.app.platform.PhoebeAppDataRevision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppDataRevisionResetDesktopTest {
    @Test
    fun freshInstallResetsAndRecordsTheCurrentRevision() = runTest {
        var wipes = 0
        var written: String? = null

        val reset = runAppDataRevisionReset(
            readStoredRevision = { null },
            writeStoredRevision = { written = it },
            signOutAndWipe = { wipes++ },
        )

        assertTrue(reset)
        assertEquals(1, wipes)
        assertEquals(PhoebeAppDataRevision.toString(), written)
    }

    @Test
    fun olderRevisionSignsOutOnceAndThenStopsResetting() = runTest {
        var stored: String? = (PhoebeAppDataRevision - 1).toString()
        var wipes = 0

        repeat(3) {
            runAppDataRevisionReset(
                readStoredRevision = { stored },
                writeStoredRevision = { stored = it },
                signOutAndWipe = { wipes++ },
            )
        }

        // The forced sign-out is a one-time event, not something every launch repeats.
        assertEquals(1, wipes)
        assertEquals(PhoebeAppDataRevision.toString(), stored)
    }

    @Test
    fun matchingRevisionLeavesStoredDataAlone() = runTest {
        var wipes = 0

        val reset = runAppDataRevisionReset(
            readStoredRevision = { PhoebeAppDataRevision.toString() },
            writeStoredRevision = { error("must not rewrite an already-current revision") },
            signOutAndWipe = { wipes++ },
        )

        assertFalse(reset)
        assertEquals(0, wipes)
    }

    @Test
    fun newerRevisionFromALaterBuildIsNotWipedByAnOlderOne() = runTest {
        var wipes = 0

        val reset = runAppDataRevisionReset(
            readStoredRevision = { (PhoebeAppDataRevision + 1).toString() },
            writeStoredRevision = { error("must not downgrade the recorded revision") },
            signOutAndWipe = { wipes++ },
        )

        assertFalse(reset)
        assertEquals(0, wipes)
    }

    @Test
    fun unreadableRevisionMarkerIsTreatedAsAFreshInstall() = runTest {
        var wipes = 0
        var written: String? = null

        runAppDataRevisionReset(
            readStoredRevision = { "not-a-number" },
            writeStoredRevision = { written = it },
            signOutAndWipe = { wipes++ },
        )

        assertEquals(1, wipes)
        assertEquals(PhoebeAppDataRevision.toString(), written)
    }

    @Test
    fun aFailedWipeIsRetriedOnTheNextLaunchInsteadOfBeingMarkedDone() = runTest {
        var stored: String? = null
        var wipes = 0

        assertFailsWith<IllegalStateException> {
            runAppDataRevisionReset(
                readStoredRevision = { stored },
                writeStoredRevision = { stored = it },
                signOutAndWipe = {
                    wipes++
                    error("database busy")
                },
            )
        }

        // Recording the revision before the wipe finished would strand the user on data the new
        // build cannot read, with nothing left to trigger a second attempt.
        assertNull(stored)
        assertEquals(1, wipes)
    }
}
