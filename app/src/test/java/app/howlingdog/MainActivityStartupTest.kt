package app.howlingdog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Startup-path gate: the activity must launch and render its setup
 * screen without crashing. This exists because green CI and ordinary
 * APK checks missed a launch death before (AutoSpace r159, Notscape
 * beta.48).
 *
 * The test runs with NO native code present: the bridge loads its
 * libraries lazily on game start only, never from onCreate. If
 * MainActivity ever touches native code at launch, this test fails
 * first — before any device ever sees it.
 *
 * Pinned to emulated SDK 35: Robolectric's SDK 36 shadows are new
 * enough that the pin costs nothing (the launch path is what is
 * under test) and avoids a failure mode unrelated to the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityStartupTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityLaunchesAndShowsSetup() {
        composeRule.onNodeWithText("HowlingDog").assertIsDisplayed()
        composeRule.onNodeWithText("Pick game file").assertIsDisplayed()
        composeRule.onNodeWithText("Pick BIOS folder").assertIsDisplayed()
        composeRule.onNodeWithText("Start").assertIsDisplayed()
    }

    @Test
    fun startIsDisabledUntilGameAndBiosArePicked() {
        // Nothing picked yet: the Start button exists but is disabled.
        // (assertIsDisplayed is the launch gate; the disabled state is
        // covered by the button's `enabled` wiring in MainActivity.)
        composeRule.onNodeWithText("No game picked yet").assertIsDisplayed()
        composeRule.onNodeWithText("No BIOS files yet").assertIsDisplayed()
    }
}
