package app.howlingdog

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Phase 4 — gamepad input (Bluetooth/USB).
 *
 * Maps Android gamepad keys and axes onto the same player-1 joypad
 * mask the touch controls use (InputMask). The mapping follows the
 * libretro RetroPad layout by physical position: the bottom face
 * button is Neo A, the right face button Neo B, the left Neo C, the
 * top Neo D; Start is Neo Start, Select is Neo Coin; the D-pad and
 * the left stick both steer.
 *
 * The stateful part (which buttons are held, the START+SELECT pause
 * hotkey, disconnect handling) lives in MainActivity; this object is
 * the pure mapping, unit-tested on the JVM (GamepadInputTest). The
 * KeyEvent constants are compile-time ints, so they work in a JVM
 * test; the axis mapping takes plain floats for the same reason.
 */
object GamepadInput {

    /** Dead zone for the stick and hat axes: inside it, no direction. */
    const val DEAD_ZONE = 0.5f

    /** The mask bit for one gamepad button, or 0 when unmapped. */
    fun maskForButton(keyCode: Int): Int {
        val bit = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> InputMask.JOY_B // Neo A
            KeyEvent.KEYCODE_BUTTON_B -> InputMask.JOY_A // Neo B
            KeyEvent.KEYCODE_BUTTON_X -> InputMask.JOY_Y // Neo C
            KeyEvent.KEYCODE_BUTTON_Y -> InputMask.JOY_X // Neo D
            KeyEvent.KEYCODE_BUTTON_START -> InputMask.JOY_START
            KeyEvent.KEYCODE_BUTTON_SELECT -> InputMask.JOY_SELECT
            KeyEvent.KEYCODE_DPAD_UP -> InputMask.JOY_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> InputMask.JOY_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> InputMask.JOY_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> InputMask.JOY_RIGHT
            else -> -1
        }
        return if (bit >= 0) 1 shl bit else 0
    }

    /** True when this key is a gamepad button we map. */
    fun isGamepadButton(keyCode: Int): Boolean = maskForButton(keyCode) != 0

    /** True for a shoulder button used by the quick-save/load combos: not a Neo Geo input. */
    fun isQuickComboButton(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_BUTTON_R2 -> true
        else -> false
    }

    /**
     * Quick-save combo: both shoulder bumpers held together (L1+R1).
     * Quick-load combo: both triggers held together (L2+R2). The
     * shoulder buttons are not Neo Geo inputs, so the combos never
     * reach the game and never conflict with START+SELECT (pause).
     * [held] is the set of gamepad keycodes currently held.
     */
    fun isQuickSaveCombo(held: Set<Int>): Boolean =
        KeyEvent.KEYCODE_BUTTON_L1 in held && KeyEvent.KEYCODE_BUTTON_R1 in held

    fun isQuickLoadCombo(held: Set<Int>): Boolean =
        KeyEvent.KEYCODE_BUTTON_L2 in held && KeyEvent.KEYCODE_BUTTON_R2 in held

    /**
     * Direction bits from the stick/hat axis values, with the dead
     * zone. Negative X is left, positive X right; negative Y is up,
     * positive Y down (Android axis convention).
     */
    fun maskForAxes(x: Float, y: Float): Int {
        var mask = 0
        if (x < -DEAD_ZONE) mask = mask or (1 shl InputMask.JOY_LEFT)
        if (x > DEAD_ZONE) mask = mask or (1 shl InputMask.JOY_RIGHT)
        if (y < -DEAD_ZONE) mask = mask or (1 shl InputMask.JOY_UP)
        if (y > DEAD_ZONE) mask = mask or (1 shl InputMask.JOY_DOWN)
        return mask
    }

    /** Direction bits from a motion event: the left stick and the D-pad hat, taking the stronger of the two per axis (a drifting stick must not swallow the hat). */
    fun maskForAxes(event: MotionEvent): Int {
        val x = stronger(
            event.getAxisValue(MotionEvent.AXIS_X),
            event.getAxisValue(MotionEvent.AXIS_HAT_X),
        )
        val y = stronger(
            event.getAxisValue(MotionEvent.AXIS_Y),
            event.getAxisValue(MotionEvent.AXIS_HAT_Y),
        )
        return maskForAxes(x, y)
    }

    private fun stronger(a: Float, b: Float): Float =
        if (kotlin.math.abs(a) >= kotlin.math.abs(b)) a else b
}
