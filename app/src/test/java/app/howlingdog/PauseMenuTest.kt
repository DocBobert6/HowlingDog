package app.howlingdog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The CONTINUE? pause menu must fit a landscape screen and every
 * button must stay reachable: the menu scrolls, and the tightened
 * header/countdown/spacing lets it fit a landscape game area
 * without scrolling. (Robert, 2026-11-10: the six-button column
 * overflowed in landscape — ADJUST CONTROLS was clipped at the
 * bottom edge and QUIT TO LIBRARY was off-screen entirely.)
 *
 * The menu is tested in isolation: a running game needs native
 * code, which Robolectric does not have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PauseMenuTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setMenu(width: Int, height: Int) {
        composeRule.setContent {
            Box(Modifier.size(width.dp, height.dp)) {
                PauseMenu(
                    timeLeft = 9,
                    onResume = {},
                    onSaveState = {},
                    onLoadState = {},
                    onSettings = {},
                    onAdjustControls = {},
                    onQuit = {},
                )
            }
        }
    }

    @Test
    fun allButtonsFitALandscapeGameArea() {
        // A Pixel 10a landscape game area is ~355dp tall; the
        // tightened menu is ~340dp, so it fits without scrolling.
        setMenu(width = 800, height = 360)
        composeRule.onNodeWithText("CONTINUE?").assertIsDisplayed()
        composeRule.onNodeWithText("RESUME").assertIsDisplayed()
        composeRule.onNodeWithText("SAVE STATE").assertIsDisplayed()
        composeRule.onNodeWithText("LOAD STATE").assertIsDisplayed()
        composeRule.onNodeWithText("SETTINGS").assertIsDisplayed()
        composeRule.onNodeWithText("ADJUST CONTROLS").assertIsDisplayed()
        composeRule.onNodeWithText("QUIT TO LIBRARY").assertIsDisplayed()
    }

    @Test
    fun quitIsReachableByScrollingOnAShortScreen() {
        // On a screen too short for the whole menu, the list
        // scrolls and the last button is reachable.
        setMenu(width = 800, height = 300)
        composeRule.onNodeWithText("QUIT TO LIBRARY")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
