package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the first-run SYSTEM SETUP screen's status derivation. */
class SetupGuideTest {

    private fun fresh() = SetupState(biosReady = 0, gameCount = 0, pickedName = null)

    // --- row statuses on a fresh install ---

    @Test
    fun freshInstallAllWaiting() {
        val state = fresh()
        assertEquals("NOT SET", state.systemFilesStatus())
        assertEquals("0 GAMES", state.addGamesStatus())
        assertEquals("NOT PICKED", state.pickGameStatus())
        assertEquals("WAITING", state.playStatus())
        assertFalse(state.systemFilesDone)
        assertFalse(state.addGamesDone)
        assertFalse(state.pickGameDone)
        assertFalse(state.playReady)
    }

    @Test
    fun systemFilesStatusCountsUp() {
        assertEquals("1/4 READY", fresh().copy(biosReady = 1).systemFilesStatus())
        assertEquals("4/4 READY", fresh().copy(biosReady = 4).systemFilesStatus())
        assertTrue(fresh().copy(biosReady = 4).systemFilesDone)
        assertFalse(fresh().copy(biosReady = 3).systemFilesDone)
    }

    @Test
    fun addGamesStatusCountsUp() {
        assertEquals("1 GAMES", fresh().copy(gameCount = 1).addGamesStatus())
        assertEquals("13 GAMES", fresh().copy(gameCount = 13).addGamesStatus())
        assertTrue(fresh().copy(gameCount = 1).addGamesDone)
    }

    @Test
    fun pickGameStatusShowsTheName() {
        assertEquals(
            "Metal Slug 3",
            fresh().copy(pickedName = "Metal Slug 3").pickGameStatus(),
        )
        assertTrue(fresh().copy(pickedName = "Metal Slug 3").pickGameDone)
    }

    // --- PLAY is ready only when rows 1-3 are all done ---

    @Test
    fun playReadyNeedsAllThreeRows() {
        val done = SetupState(biosReady = 4, gameCount = 5, pickedName = "KOF 98")
        assertTrue(done.playReady)
        assertEquals("READY", done.playStatus())
        // Each row undone on its own keeps PLAY waiting.
        assertFalse(done.copy(biosReady = 3).playReady)
        assertFalse(done.copy(gameCount = 0).playReady)
        assertFalse(done.copy(pickedName = null).playReady)
        assertEquals("WAITING", done.copy(biosReady = 3).playStatus())
    }

    // --- show-once condition ---

    @Test
    fun showSetupOnlyOnAFreshInstall() {
        // Fresh install, never seen: show it.
        assertTrue(shouldShowSetup(biosReady = 0, gameCount = 0, setupSeen = false))
        // Seen once: never again on its own.
        assertFalse(shouldShowSetup(biosReady = 0, gameCount = 0, setupSeen = true))
        // Games present: the user is past setup.
        assertFalse(shouldShowSetup(biosReady = 0, gameCount = 1, setupSeen = false))
        // System files set: the user is past setup.
        assertFalse(shouldShowSetup(biosReady = 4, gameCount = 0, setupSeen = false))
        assertFalse(shouldShowSetup(biosReady = 1, gameCount = 0, setupSeen = false))
    }
}
