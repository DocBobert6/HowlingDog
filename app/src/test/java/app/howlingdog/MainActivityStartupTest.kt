package app.howlingdog

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Startup-path gate: the activity must launch and render its library
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
    fun activityLaunchesAndShowsLibrary() {
        composeRule.onNodeWithText("HowlingDog").assertIsDisplayed()
        composeRule.onNodeWithText("Add game").assertIsDisplayed()
        composeRule.onNodeWithText("Add folder").assertIsDisplayed()
        composeRule.onNodeWithText("Pick BIOS folder").assertIsDisplayed()
    }

    @Test
    fun emptyLibraryShowsEmptyState() {
        // Nothing imported yet (Robolectric has no files on disk):
        // the empty state is the launch gate for the library home.
        // The screen scrolls, so scroll the node into view first —
        // in the small test window it sits below the fold.
        composeRule.onNodeWithText("No games yet. Add a game to start.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * Regression guard for the Phase 2 device bug: tapping Start
     * flashed back to a fresh setup screen with no files picked.
     * Cause: MainActivity declared no configChanges, so entering the
     * game screen's forced landscape recreated the activity, and the
     * picked state lived in plain remember. This test pins the
     * primary fix — rotation must not recreate the activity.
     */
    @Test
    fun mainActivityDeclaresConfigChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0,
        )
        val flags = info.configChanges
        assertTrue("orientation must not recreate MainActivity",
            flags and ActivityInfo.CONFIG_ORIENTATION != 0)
        assertTrue("screenSize must not recreate MainActivity",
            flags and ActivityInfo.CONFIG_SCREEN_SIZE != 0)
        assertTrue("screenLayout must not recreate MainActivity",
            flags and ActivityInfo.CONFIG_SCREEN_LAYOUT != 0)
        assertTrue("keyboardHidden must not recreate MainActivity",
            flags and ActivityInfo.CONFIG_KEYBOARD_HIDDEN != 0)
        assertTrue("smallestScreenSize must not recreate MainActivity",
            flags and ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE != 0)
    }

    /**
     * If the activity IS ever recreated, the fallback must be a clean
     * library screen, not a crash. (The library itself lives on disk,
     * so a recreation loses nothing; the pickers need SAF, which
     * Robolectric cannot drive — the manifest test above is the guard
     * for the rotation fix.)
     */
    @Test
    fun activitySurvivesRecreation() {
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("HowlingDog").assertIsDisplayed()
        composeRule.onNodeWithText("No games yet. Add a game to start.")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
