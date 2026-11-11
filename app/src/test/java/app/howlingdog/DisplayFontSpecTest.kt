package app.howlingdog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Display-font spec gate: the NEOGEO System Font's pixel grid and
 * character coverage. If a display size stops being a multiple of
 * the 16sp grid, or a display string uses a character the font does
 * not have, the font renders blurred or falls back to the default
 * font for that glyph — both break the era look.
 */
class DisplayFontSpecTest {

    @Test
    fun allDisplaySizesAreMultiplesOfThePixelGrid() {
        for (sp in DisplayFontSpec.ALL_SIZES) {
            assertTrue(
                "display size $sp sp is not a multiple of the 16sp pixel grid",
                DisplayFontSpec.isCrispSize(sp),
            )
        }
    }

    @Test
    fun everyDisplayStringIsCoveredByTheFont() {
        for (text in DisplayFontSpec.DISPLAY_STRINGS) {
            assertTrue(
                "display string not covered by the NEOGEO System Font: \"$text\"",
                DisplayFontSpec.covers(text),
            )
        }
        // The countdown runs 09..00 — every digit is covered.
        for (n in 0..9) {
            assertTrue(
                "countdown value not covered: $n",
                DisplayFontSpec.covers("TIME %02d".format(n)),
            )
            assertTrue(
                "pause countdown value not covered: $n",
                DisplayFontSpec.covers("%d".format(n)),
            )
        }
    }

    @Test
    fun charactersOutsideTheFontAreKnown() {
        // These are NOT in the font's cmap, so no display string may
        // use them — a string that does would render with a fallback
        // glyph from the default font, breaking the pixel look.
        for (c in listOf(
            '"', '*', ';', '@', '[', ']', '^', '_', '`',
            '{', '|', '}', '~', '★', '●', '○', '⚙', '✓', '…', '–',
        )) {
            assertFalse(
                "character '$c' should be outside the NEOGEO System Font",
                DisplayFontSpec.covers(c.toString()),
            )
        }
    }
}
