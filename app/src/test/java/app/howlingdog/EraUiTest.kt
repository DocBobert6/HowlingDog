package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Phase 5 — pure-JVM tests for the era skin's logic (EraUi).
 * No Compose, no Android: these run on the plain JVM.
 */
@RunWith(JUnit4::class)
class EraUiTest {

    @Test
    fun tileColourIsStablePerName() {
        val first = EraUi.tileColorArgb("Neo Bomberman")
        assertEquals(first, EraUi.tileColorArgb("Neo Bomberman"))
        assertEquals(first, EraUi.tileColorArgb("Neo Bomberman"))
    }

    @Test
    fun tileColourIsInThePalette() {
        for (name in listOf("Neo Bomberman", "Metal Slug 5", "King of Fighters 94", "Samurai Shodown", "Fatal Fury", "Art of Fighting", "Magician Lord", "Nam 1975")) {
            assertTrue(
                "colour for $name must be one of the palette colours",
                EraUi.tileColorArgb(name) in EraUi.TILE_COLORS,
            )
        }
    }

    @Test
    fun tileColoursSpreadAcrossNames() {
        val colours = listOf(
            "Neo Bomberman", "Metal Slug 5", "King of Fighters 94", "Samurai Shodown",
            "Fatal Fury", "Art of Fighting", "Magician Lord", "Nam 1975",
            "Baseball Stars", "Windjammers", "Pulstar", "Blazing Star",
        ).map { EraUi.tileColorArgb(it) }.toSet()
        assertTrue("names should spread across at least 4 tile colours, got ${colours.size}", colours.size >= 4)
    }

    @Test
    fun monogramIsFirstLetterOrDigit() {
        assertEquals("N", EraUi.monogram("Neo Bomberman"))
        assertEquals("M", EraUi.monogram("  metal slug 5"))
        assertEquals("5", EraUi.monogram("5th Game"))
        assertEquals("?", EraUi.monogram(""))
        assertEquals("?", EraUi.monogram("   "))
    }

    @Test
    fun countdownCountsDownThenWraps() {
        assertEquals(8, EraUi.countdownNext(9, 9))
        assertEquals(0, EraUi.countdownNext(1, 9))
        assertEquals(9, EraUi.countdownNext(0, 9))
        assertEquals(5, EraUi.countdownNext(0, 5))
        assertEquals(4, EraUi.countdownNext(5, 5))
    }

    @Test
    fun countdownNeverGoesNegative() {
        // The timer is display only: whatever value it is fed, the
        // next value stays in 0..max — there is no path from the
        // countdown to starting, quitting, or resuming anything.
        for (start in -3..9) {
            val next = EraUi.countdownNext(start, 9)
            assertTrue("countdownNext($start, 9) = $next must be in 0..9", next in 0..9)
        }
    }

    @Test
    fun countdownRejectsNonPositiveMax() {
        try {
            EraUi.countdownNext(5, 0)
            fail("countdownNext with max 0 must throw")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
