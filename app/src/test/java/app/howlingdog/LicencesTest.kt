package app.howlingdog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Licences screen gate (Phase 8): Settings → About → Licences
 * opens the screen and shows the full NOTICE text — every
 * compiled-core licence, the GPL-3.0 frontend note, and the SNK
 * disclaimer. Pinned to emulated SDK 35, like the startup gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LicencesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun licencesScreenShowsAllLicenceTexts() {
        composeRule.onNodeWithText("PRESS START").performClick()
        // A fresh install shows the first-run SYSTEM SETUP screen
        // after the first library load — dismiss it to reach the
        // library, like the startup gate does.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("HOWLINGDOG — SYSTEM SETUP").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("SKIP").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        // The About section sits at the bottom of the scrollable
        // Settings screen — scroll the button into view first.
        composeRule.onNodeWithText("Licences").performScrollTo().performClick()
        composeRule.onNodeWithText("Licences").assertIsDisplayed()
        // The full NOTICE text is one very tall Text node, so
        // scrolling to a substring and asserting it is displayed
        // is not robust for a node taller than the viewport.
        // assertExists is the right check here — it proves each
        // required licence and the disclaimer are in the composed
        // tree, i.e. shown on this screen. (The disclaimer
        // substring is kept on one line in NOTICE so it matches
        // contiguously; it used to wrap across two lines, which
        // is why the first version of this test went red.)
        for (text in listOf(
            "not affiliated with or endorsed by SNK",
            "Geolith (the core itself)",
            "NEOGEO System Font (the display font)",
            "everiux365",
            "Creative Commons Attribution 3.0",
            "ymfm (FM sound synthesis",
            "Speex resampler",
            "zstd (deps/zstd)",
            "Musashi (Motorola 68000 emulation",
            "Jolly Good Z80",
            "miniz (deps/miniz)",
            "libretro API header",
            "LZMA (deps/lzma)",
        )) {
            composeRule.onNodeWithText(text, substring = true).assertExists()
        }
    }

    @Test
    fun licencesBackReturnsToSettings() {
        composeRule.onNodeWithText("PRESS START").performClick()
        // Fresh install: dismiss the first-run SYSTEM SETUP screen
        // first (see the test above).
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("HOWLINGDOG — SYSTEM SETUP").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("SKIP").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        // Probe (merge diagnosis): split the Licences chain so the
        // failing step is named by its line — lookup, scroll, click.
        composeRule.onNodeWithText("Licences").assertExists()
        composeRule.onNodeWithText("Licences").performScrollTo()
        composeRule.onNodeWithText("Licences").performClick()
        composeRule.onNodeWithText("Back").performScrollTo().performClick()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }
}
