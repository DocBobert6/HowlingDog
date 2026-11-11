package app.howlingdog

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the Phase 4 gamepad mapping: face buttons
 * to Neo buttons by physical position, Start/Select, D-pad, the
 * stick dead zone, and unmapped keys. The KeyEvent constants are
 * compile-time ints, so they work on the JVM; the axis mapping takes
 * plain floats for the same reason.
 */
class GamepadInputTest {

    @Test
    fun faceButtonsMapByPosition() {
        // Bottom face button (pad A) is Neo A (joypad B); right
        // (pad B) is Neo B (joypad A); left (pad X) is Neo C
        // (joypad Y); top (pad Y) is Neo D (joypad X).
        assertEquals(InputMask.neoA(), GamepadInput.maskForButton(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(InputMask.neoB(), GamepadInput.maskForButton(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(InputMask.neoC(), GamepadInput.maskForButton(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(InputMask.neoD(), GamepadInput.maskForButton(KeyEvent.KEYCODE_BUTTON_Y))
    }

    @Test
    fun startSelectAndDpad() {
        assertEquals(
            1 shl InputMask.JOY_START,
            GamepadInput.maskForButton(KeyEvent.KEYCODE_BUTTON_START),
        )
        assertEquals(
            1 shl InputMask.JOY_SELECT,
            GamepadInput.maskForButton(KeyEvent.KEYCODE_BUTTON_SELECT),
        )
        assertEquals(
            1 shl InputMask.JOY_UP,
            GamepadInput.maskForButton(KeyEvent.KEYCODE_DPAD_UP),
        )
        assertEquals(
            1 shl InputMask.JOY_DOWN,
            GamepadInput.maskForButton(KeyEvent.KEYCODE_DPAD_DOWN),
        )
        assertEquals(
            1 shl InputMask.JOY_LEFT,
            GamepadInput.maskForButton(KeyEvent.KEYCODE_DPAD_LEFT),
        )
        assertEquals(
            1 shl InputMask.JOY_RIGHT,
            GamepadInput.maskForButton(KeyEvent.KEYCODE_DPAD_RIGHT),
        )
    }

    @Test
    fun unmappedKeysAreZero() {
        assertEquals(0, GamepadInput.maskForButton(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(0, GamepadInput.maskForButton(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(0, GamepadInput.maskForButton(KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertEquals(0, GamepadInput.maskForButton(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(GamepadInput.isGamepadButton(KeyEvent.KEYCODE_BUTTON_L1))
        assertTrue(GamepadInput.isGamepadButton(KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test
    fun axesRespectTheDeadZone() {
        assertEquals(0, GamepadInput.maskForAxes(0f, 0f))
        assertEquals(0, GamepadInput.maskForAxes(0.5f, 0f))
        assertEquals(0, GamepadInput.maskForAxes(0f, -0.5f))
        assertEquals(0, GamepadInput.maskForAxes(0.4f, 0.4f))
        assertEquals(1 shl InputMask.JOY_RIGHT, GamepadInput.maskForAxes(0.6f, 0f))
        assertEquals(1 shl InputMask.JOY_LEFT, GamepadInput.maskForAxes(-0.6f, 0f))
        assertEquals(1 shl InputMask.JOY_DOWN, GamepadInput.maskForAxes(0f, 0.6f))
        assertEquals(1 shl InputMask.JOY_UP, GamepadInput.maskForAxes(0f, -0.6f))
        // Diagonals combine.
        val diag = GamepadInput.maskForAxes(0.8f, -0.8f)
        assertEquals(
            (1 shl InputMask.JOY_RIGHT) or (1 shl InputMask.JOY_UP),
            diag,
        )
    }

    @Test
    fun quickSaveLoadCombos() {
        // The shoulder buttons are not game inputs (they map to no
        // joypad bit), so the combos never reach the game and never
        // conflict with START+SELECT (pause).
        val l1 = KeyEvent.KEYCODE_BUTTON_L1
        val r1 = KeyEvent.KEYCODE_BUTTON_R1
        val l2 = KeyEvent.KEYCODE_BUTTON_L2
        val r2 = KeyEvent.KEYCODE_BUTTON_R2
        assertTrue(GamepadInput.isQuickComboButton(l1))
        assertTrue(GamepadInput.isQuickComboButton(r1))
        assertTrue(GamepadInput.isQuickComboButton(l2))
        assertTrue(GamepadInput.isQuickComboButton(r2))
        assertFalse(GamepadInput.isQuickComboButton(KeyEvent.KEYCODE_BUTTON_A))
        assertFalse(GamepadInput.isQuickComboButton(KeyEvent.KEYCODE_BUTTON_START))
        // Quick save: both bumpers together. Extra held buttons do
        // not cancel it — the activity fires the pair that just
        // completed, one key-down event at a time.
        assertTrue(GamepadInput.isQuickSaveCombo(setOf(l1, r1)))
        assertTrue(GamepadInput.isQuickSaveCombo(setOf(l1, r1, l2)))
        assertFalse(GamepadInput.isQuickSaveCombo(setOf(l1)))
        assertFalse(GamepadInput.isQuickSaveCombo(setOf(r1)))
        assertFalse(GamepadInput.isQuickSaveCombo(setOf(l2, r2)))
        assertFalse(GamepadInput.isQuickSaveCombo(emptySet()))
        // Quick load: both triggers together.
        assertTrue(GamepadInput.isQuickLoadCombo(setOf(l2, r2)))
        assertTrue(GamepadInput.isQuickLoadCombo(setOf(l2, r2, l1)))
        assertFalse(GamepadInput.isQuickLoadCombo(setOf(l2)))
        assertFalse(GamepadInput.isQuickLoadCombo(setOf(r2)))
        assertFalse(GamepadInput.isQuickLoadCombo(setOf(l1, r1)))
        assertFalse(GamepadInput.isQuickLoadCombo(emptySet()))
    }

    @Test
    fun speedButtonsAreTheStickClicksOnly() {
        // L3 = fast-forward, R3 = slow motion. They are not game
        // inputs, not quick-combo buttons, and not remappable —
        // the hold never reaches the game and never conflicts
        // with the shoulder combos or START+SELECT (pause).
        assertTrue(GamepadInput.isSpeedButton(KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertTrue(GamepadInput.isSpeedButton(KeyEvent.KEYCODE_BUTTON_THUMBR))
        assertFalse(GamepadInput.isSpeedButton(KeyEvent.KEYCODE_BUTTON_A))
        assertFalse(GamepadInput.isSpeedButton(KeyEvent.KEYCODE_BUTTON_L1))
        assertFalse(GamepadInput.isSpeedButton(KeyEvent.KEYCODE_BUTTON_R2))
        assertFalse(GamepadInput.isSpeedButton(KeyEvent.KEYCODE_BUTTON_START))
        assertFalse(GamepadInput.isGamepadButton(KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertFalse(GamepadInput.isGamepadButton(KeyEvent.KEYCODE_BUTTON_THUMBR))
        assertFalse(GamepadInput.isQuickComboButton(KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertFalse(GamepadInput.isQuickComboButton(KeyEvent.KEYCODE_BUTTON_THUMBR))
    }

    @Test
    fun padMaskCombinesWithTouchMask() {
        // The activity ORs the pad mask with the touch mask; the two
        // never share a bit for the same physical input.
        val touch = InputMask.build(a = true, up = true)
        val pad = GamepadInput.maskForButton(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(touch, touch or pad) // pad A is Neo A, already held by touch
        val padB = GamepadInput.maskForButton(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(touch or InputMask.neoB(), touch or padB)
    }

    @Test
    fun remappedButtonReachesTheGame() {
        // Swap Neo A and Neo C (v1.3.0, part 1): the left face
        // button (pad X) now fires Neo A, and the bottom face
        // button (pad A) fires Neo C. The D-pad is unaffected by
        // the mapping, and the default mapping is unchanged.
        val swapped = GamepadMapping.assign(
            GamepadMapping.DEFAULT,
            "A",
            KeyEvent.KEYCODE_BUTTON_X,
        )
        assertEquals(
            InputMask.neoA(),
            GamepadInput.maskForButton(KeyEvent.KEYCODE_BUTTON_X, swapped),
        )
        assertEquals(
            InputMask.neoC(),
            GamepadInput.maskForButton(KeyEvent.KEYCODE_BUTTON_A, swapped),
        )
        assertEquals(
            1 shl InputMask.JOY_UP,
            GamepadInput.maskForButton(KeyEvent.KEYCODE_DPAD_UP, swapped),
        )
        assertEquals(
            InputMask.neoA(),
            GamepadInput.maskForButton(KeyEvent.KEYCODE_BUTTON_A),
        )
    }
}
