package app.howlingdog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        composeRule.onNodeWithText("Settings").performClick()
        // The About section sits at the bottom of the scrollable
        // Settings screen — scroll the button into view first.
        composeRule.onNodeWithText("Licences").performScrollTo().performClick()
        composeRule.onNodeWithText("Licences").assertIsDisplayed()
        // The full NOTICE text is one Text node; substring matches
        // prove each required licence and the disclaimer are in it.
        // Assert in document order (top to bottom): the screen
        // scrolls monotonically, and a substring near the top
        // (the SNK disclaimer, in the preamble) cannot be
        // scrolled back into view once the column is at the
        // bottom, because the node is a single tall Text.
        composeRule.onNodeWithText("not affiliated with or endorsed by SNK", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Geolith (the core itself)", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("ymfm (FM sound synthesis", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Speex resampler", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("zstd (deps/zstd)", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Musashi (Motorola 68000 emulation", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Jolly Good Z80", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("miniz (deps/miniz)", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("libretro API header", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("LZMA (deps/lzma)", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun licencesBackReturnsToSettings() {
        composeRule.onNodeWithText("PRESS START").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Licences").performScrollTo().performClick()
        composeRule.onNodeWithText("Back").performScrollTo().performClick()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }
}
