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
    fun padMaskCombinesWithTouchMask() {
        // The activity ORs the pad mask with the touch mask; the two
        // never share a bit for the same physical input.
        val touch = InputMask.build(a = true, up = true)
        val pad = GamepadInput.maskForButton(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(touch, touch or pad) // pad A is Neo A, already held by touch
        val padB = GamepadInput.maskForButton(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(touch or InputMask.neoB(), touch or padB)
    }
}
