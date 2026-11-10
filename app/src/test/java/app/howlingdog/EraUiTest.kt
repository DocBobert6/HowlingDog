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
    fun paletteMatchesTheSampledIconColours() {
        // The palette is sampled from Robert's icon: deep maroon,
        // gold, dark charcoal. If the icon is ever re-sampled,
        // these values move with it — the test pins the current
        // samples so a typo cannot silently shift the theme.
        assertEquals(0xFF12171A.toInt(), EraUi.Palette.BACKGROUND)
        assertEquals(0xFF241014.toInt(), EraUi.Palette.SURFACE)
        assertEquals(0xFF311218.toInt(), EraUi.Palette.SURFACE_RAISED)
        assertEquals(0xFF552732.toInt(), EraUi.Palette.MAROON)
        assertEquals(0xFF6E3B45.toInt(), EraUi.Palette.MAROON_LIGHT)
        assertEquals(0xFFE8C88F.toInt(), EraUi.Palette.GOLD)
        assertEquals(0xFFAB8D5A.toInt(), EraUi.Palette.GOLD_MUTED)
        assertEquals(0xFFF3E7CE.toInt(), EraUi.Palette.TEXT)
        assertEquals(0xFFC9B18A.toInt(), EraUi.Palette.TEXT_MUTED)
        assertEquals(0xFFC25B4A.toInt(), EraUi.Palette.WARNING)
        assertEquals(0xFF311218.toInt(), EraUi.Palette.ON_GOLD)
    }

    @Test
    fun tileColoursAreAllFromTheIconPalette() {
        // No bright arcade primaries (red/blue/green/magenta)
        // anywhere in the tile palette: every tile colour must
        // be one of the icon's maroon, bronze or charcoal shades.
        val allowed = setOf(
            0xFF311218.toInt(), 0xFF552732.toInt(), 0xFF6E3B45.toInt(), 0xFF241014.toInt(),
            0xFF775F47.toInt(), 0xFF12171A.toInt(), 0xFF6B4E33.toInt(), 0xFF3D1F26.toInt(),
        )
        assertEquals(allowed, EraUi.TILE_COLORS.toSet())
    }

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
