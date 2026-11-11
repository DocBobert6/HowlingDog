package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9, item 9 — the injected UniBIOS menu combo is exactly
 * START+SELECT (no game buttons, no directions), the hold window
 * is what the release timer relies on, and only the two Universe
 * BIOS modes count as UniBIOS.
 */
class UniBiosMenuTest {

    @Test
    fun comboIsExactlyStartPlusSelect() {
        val mask = UniBiosMenu.comboMask()
        val expected = (1 shl InputMask.JOY_START) or (1 shl InputMask.JOY_SELECT)
        assertEquals(expected, mask)
        // No direction and no Neo A/B/C/D rides along: the game
        // must not see a stray button if the menu fails to open.
        assertEquals(0, mask and (1 shl InputMask.JOY_UP))
        assertEquals(0, mask and (1 shl InputMask.JOY_DOWN))
        assertEquals(0, mask and (1 shl InputMask.JOY_LEFT))
        assertEquals(0, mask and (1 shl InputMask.JOY_RIGHT))
        assertEquals(0, mask and InputMask.neoA())
        assertEquals(0, mask and InputMask.neoB())
        assertEquals(0, mask and InputMask.neoC())
        assertEquals(0, mask and InputMask.neoD())
    }

    @Test
    fun holdLengthIsPinned() {
        assertEquals(500L, UniBiosMenu.HOLD_MS)
    }

    @Test
    fun holdIsActiveInsideTheWindowOnly() {
        val until = 10_000L
        assertTrue(UniBiosMenu.holdActive(until, 9_999L))
        assertFalse(UniBiosMenu.holdActive(until, 10_000L))
        assertFalse(UniBiosMenu.holdActive(until, 10_500L))
        // No injection armed: never active.
        assertFalse(UniBiosMenu.holdActive(0L, 9_999L))
    }

    @Test
    fun onlyUniverseBiosModesCount() {
        assertTrue(UniBiosMenu.isUniBiosMode("uni"))
        assertTrue(UniBiosMenu.isUniBiosMode("cdz_unibios"))
        assertFalse(UniBiosMenu.isUniBiosMode("aes"))
        assertFalse(UniBiosMenu.isUniBiosMode("mvs"))
        assertFalse(UniBiosMenu.isUniBiosMode("cd"))
        assertFalse(UniBiosMenu.isUniBiosMode("cdz"))
        assertFalse(UniBiosMenu.isUniBiosMode(""))
    }
}
