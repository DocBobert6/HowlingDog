package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the top-bar auto-hide state machine
 * (Phase 9, item 7): hides after start, reveals on swipe, re-hides
 * after idle, and never hides while the controls are being adjusted.
 * No Android framework calls — the hide timer is the Activity's job;
 * this machine only decides what each event means.
 */
class TopBarVisibilityTest {

    @Test
    fun startsVisibleWithTheStartDelay() {
        val state = TopBarVisibility()
        state.onGameStarted()
        assertTrue(state.visible)
        assertEquals(TopBarVisibility.HIDE_AFTER_START_MS, state.hideDelayMs)
    }

    @Test
    fun hidesWhenTheHideTimerFiresAfterStart() {
        val state = TopBarVisibility()
        state.onGameStarted()
        state.onHideDue()
        assertFalse(state.visible)
    }

    @Test
    fun swipeDownRevealsWithTheIdleDelay() {
        val state = TopBarVisibility()
        state.onGameStarted()
        state.onHideDue()
        assertFalse(state.visible)
        state.onSwipeDown()
        assertTrue(state.visible)
        assertEquals(TopBarVisibility.REHIDE_AFTER_IDLE_MS, state.hideDelayMs)
    }

    @Test
    fun reHidesWhenTheHideTimerFiresAfterAReveal() {
        val state = TopBarVisibility()
        state.onGameStarted()
        state.onHideDue()
        state.onSwipeDown()
        state.onHideDue()
        assertFalse(state.visible)
    }

    @Test
    fun neverHidesWhileAdjustingControls() {
        val state = TopBarVisibility()
        state.onGameStarted()
        state.onEditModeChanged(true)
        assertTrue(state.visible)
        state.onHideDue()
        assertTrue(state.visible)
        // Still visible after repeated timer firings.
        state.onHideDue()
        assertTrue(state.visible)
    }

    @Test
    fun enteringEditModeRevealsAHiddenBar() {
        val state = TopBarVisibility()
        state.onGameStarted()
        state.onHideDue()
        assertFalse(state.visible)
        state.onEditModeChanged(true)
        assertTrue(state.visible)
    }

    @Test
    fun leavingEditModeReArmsTheIdleTimer() {
        val state = TopBarVisibility()
        state.onGameStarted()
        state.onEditModeChanged(true)
        state.onEditModeChanged(false)
        assertTrue(state.visible)
        assertEquals(TopBarVisibility.REHIDE_AFTER_IDLE_MS, state.hideDelayMs)
        state.onHideDue()
        assertFalse(state.visible)
    }

    @Test
    fun pauseRevealsAHiddenBar() {
        val state = TopBarVisibility()
        state.onGameStarted()
        state.onHideDue()
        assertFalse(state.visible)
        state.onPaused()
        assertTrue(state.visible)
    }

    @Test
    fun resumeShowsThenHidesAfterTheStartDelay() {
        val state = TopBarVisibility()
        state.onGameStarted()
        state.onHideDue()
        state.onPaused()
        state.onResumed()
        assertTrue(state.visible)
        assertEquals(TopBarVisibility.HIDE_AFTER_START_MS, state.hideDelayMs)
        state.onHideDue()
        assertFalse(state.visible)
    }

    @Test
    fun everyRevealBumpsTheGenerationSoTheTimerRestarts() {
        val state = TopBarVisibility()
        state.onGameStarted()
        val afterStart = state.generation
        state.onSwipeDown()
        assertTrue(state.generation > afterStart)
        val afterSwipe = state.generation
        state.onEditModeChanged(true)
        assertTrue(state.generation > afterSwipe)
        val afterEdit = state.generation
        state.onPaused()
        assertTrue(state.generation > afterEdit)
        val afterPause = state.generation
        state.onResumed()
        assertTrue(state.generation > afterPause)
    }

    @Test
    fun swipeWhileVisibleStillReArmsTheTimer() {
        val state = TopBarVisibility()
        state.onGameStarted()
        val before = state.generation
        state.onSwipeDown()
        assertTrue(state.visible)
        assertTrue(state.generation > before)
        assertEquals(TopBarVisibility.REHIDE_AFTER_IDLE_MS, state.hideDelayMs)
    }

    @Test
    fun quitResetsToShownForTheNextGame() {
        val state = TopBarVisibility()
        state.onGameStarted()
        state.onHideDue()
        state.onQuit()
        assertTrue(state.visible)
        assertFalse(state.editing)
        assertEquals(TopBarVisibility.HIDE_AFTER_START_MS, state.hideDelayMs)
    }

    @Test
    fun gestureGeometryIsPinned() {
        // The swipe must start in a narrow strip at the very top
        // edge, and needs real downward travel — so a stray touch
        // on the game can never reveal the bar by accident.
        assertEquals(28f, TopBarVisibility.EDGE_HEIGHT_DP, 0f)
        assertEquals(20f, TopBarVisibility.SWIPE_MIN_DP, 0f)
        assertTrue(TopBarVisibility.SWIPE_MIN_DP < TopBarVisibility.EDGE_HEIGHT_DP)
        assertEquals(2000L, TopBarVisibility.HIDE_AFTER_START_MS)
        assertEquals(4000L, TopBarVisibility.REHIDE_AFTER_IDLE_MS)
    }
}
