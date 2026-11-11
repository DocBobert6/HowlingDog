package app.howlingdog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private fun setMenu(
        width: Int,
        height: Int,
        selectedSlot: Int = QuickSave.QUICK_SLOT,
        occupiedSlots: List<Int> = emptyList(),
        onSelectSlot: (Int) -> Unit = {},
        showUniBiosMenu: Boolean = false,
        onUniBiosMenu: () -> Unit = {},
        onCheats: () -> Unit = {},
    ) {
        composeRule.setContent {
            Box(Modifier.size(width.dp, height.dp)) {
                PauseMenu(
                    timeLeft = 9,
                    selectedSlot = selectedSlot,
                    occupiedSlots = occupiedSlots,
                    onSelectSlot = onSelectSlot,
                    onResume = {},
                    showUniBiosMenu = showUniBiosMenu,
                    onUniBiosMenu = onUniBiosMenu,
                    onSaveState = {},
                    onLoadState = {},
                    onCheats = onCheats,
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
        // tightened menu fits without scrolling.
        setMenu(width = 800, height = 360)
        composeRule.onNodeWithText("CONTINUE?").assertIsDisplayed()
        composeRule.onNodeWithText("RESUME").assertIsDisplayed()
        composeRule.onNodeWithText("SAVE STATE").assertIsDisplayed()
        composeRule.onNodeWithText("LOAD STATE").assertIsDisplayed()
        // v1.5, feature 4 — CHEATS shares its row with SETTINGS,
        // so the menu still fits the 360dp game area whole.
        composeRule.onNodeWithText("CHEATS").assertIsDisplayed()
        composeRule.onNodeWithText("SETTINGS").assertIsDisplayed()
        composeRule.onNodeWithText("ADJUST CONTROLS").assertIsDisplayed()
        composeRule.onNodeWithText("QUIT TO LIBRARY").assertIsDisplayed()
    }

    @Test
    fun cheatsButtonOpensOnTap() {
        // v1.5, feature 4 — the CHEATS half of the shared row
        // taps through to the Cheats screen.
        var opened = false
        setMenu(width = 800, height = 360, onCheats = { opened = true })
        composeRule.onNodeWithText("CHEATS")
            .assertIsDisplayed()
            .performClick()
        assertEquals(true, opened)
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

    @Test
    fun slotPickerShowsOccupiedAndEmptySlots() {
        // Phase 9, item 3: a filled dot marks a slot that holds
        // a save, an open dot an empty one. Asserting the chips
        // displayed at 360dp also guards the landscape fit —
        // the picker must not push the menu past the game area.
        setMenu(width = 800, height = 360, occupiedSlots = listOf(1, 3))
        composeRule.onNodeWithText("SLOT 1 ●").assertIsDisplayed()
        composeRule.onNodeWithText("SLOT 2 ○").assertIsDisplayed()
        composeRule.onNodeWithText("SLOT 3 ●").assertIsDisplayed()
    }

    @Test
    fun tappingASlotChipSelectsIt() {
        var picked = -1
        setMenu(
            width = 800,
            height = 360,
            occupiedSlots = listOf(1),
            onSelectSlot = { picked = it },
        )
        composeRule.onNodeWithText("SLOT 2 ○").performClick()
        assertEquals(2, picked)
    }

    @Test
    fun uniBiosMenuButtonHiddenUnlessEnabled() {
        // Phase 9, item 9 — under any system but Universe BIOS
        // there is no UniBIOS menu to open, so the row is not
        // shown at all and the six-button fit is unchanged.
        setMenu(width = 800, height = 360)
        assertTrue(
            composeRule.onAllNodesWithText("UNIVERSE BIOS MENU")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun uniBiosMenuButtonOpensOnTap() {
        // Phase 9, item 9 — in Universe BIOS mode the row shows
        // and taps through. At 420dp the seven-row menu fits
        // whole, so QUIT is displayed alongside it.
        var opened = false
        setMenu(
            width = 800,
            height = 420,
            showUniBiosMenu = true,
            onUniBiosMenu = { opened = true },
        )
        composeRule.onNodeWithText("UNIVERSE BIOS MENU")
            .assertIsDisplayed()
            .performClick()
        assertEquals(true, opened)
        composeRule.onNodeWithText("QUIT TO LIBRARY").assertIsDisplayed()
    }

    @Test
    fun uniBiosMenuButtonStillLeavesQuitReachableAt360() {
        // Phase 9, item 9 — with the seventh row the menu is
        // taller than a 360dp game area, so it scrolls — and QUIT
        // stays reachable, like every other short-screen menu.
        setMenu(width = 800, height = 360, showUniBiosMenu = true)
        composeRule.onNodeWithText("UNIVERSE BIOS MENU").assertIsDisplayed()
        composeRule.onNodeWithText("QUIT TO LIBRARY")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
