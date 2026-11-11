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

    /** The mask bit for one gamepad button, or 0 when unmapped. The
        face buttons follow [mapping] (the remapping, v1.3.0 part 1);
        the D-pad always steers and is not remappable. The default
        mapping is today's physical-position mapping, so a call with
        no mapping behaves exactly as before. */
    fun maskForButton(keyCode: Int, mapping: Map<String, Int> = GamepadMapping.DEFAULT): Int {
        val bit = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> InputMask.JOY_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> InputMask.JOY_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> InputMask.JOY_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> InputMask.JOY_RIGHT
            else -> -1
        }
        if (bit >= 0) return 1 shl bit
        return GamepadMapping.maskForKeyCode(keyCode, mapping)
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
     * v1.5, feature 1 — true for a stick-click button used by
     * the speed controls: L3 = fast-forward, R3 = slow motion.
     * Not a Neo Geo input and not a quick-combo button, so the
     * hold never reaches the game and never conflicts with the
     * shoulder combos or START+SELECT (pause).
     */
    fun isSpeedButton(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR -> true
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
