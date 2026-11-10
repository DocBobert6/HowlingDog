package app.howlingdog

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The flat-outline arcade stick's direction math (pure Kotlin, no
 * Android dependencies, unit-tested on the JVM).
 *
 * The stick is fixed in place: the thumb drags a knob inside a base
 * ring, and the direction comes from the drag angle, in 8 sectors
 * of 45 degrees each (up, down, left, right, and the four
 * diagonals). That is the whole point versus the old 4-button
 * D-pad: rolling the thumb around the edge changes direction
 * smoothly, and diagonals just work.
 *
 * Coordinates: dx/dy are the knob offset from the base centre, in
 * units of the stick's maximum travel (1.0 = the knob at the edge
 * of the base). +x is right, +y is down (Android touch
 * convention). The returned value is a direction mask using the
 * InputMask joypad bits (JOY_UP / JOY_DOWN / JOY_LEFT / JOY_RIGHT),
 * so it slots straight into the Held state the touch buttons and
 * the gamepad already feed.
 */
object StickInput {

    /** Inside this fraction of full travel, no direction is active (a thumb resting near the centre does nothing). */
    const val DEAD_ZONE = 0.22f

    /** Direction bits from the knob offset (dx, dy), each in units of full travel. */
    fun direction(dx: Float, dy: Float): Int {
        val mag = sqrt(dx * dx + dy * dy)
        if (mag < DEAD_ZONE) return 0
        // Angle in degrees, 0 = right, increasing clockwise (y down).
        var angle = atan2(dy, dx) * 180.0 / PI
        if (angle < 0) angle += 360.0
        // Sectors of 45 degrees, centred on the 8 directions:
        // 0 right, 1 down-right, 2 down, 3 down-left, 4 left,
        // 5 up-left, 6 up, 7 up-right.
        val sector = ((angle + 22.5) / 45.0).toInt() % 8
        return when (sector) {
            0 -> 1 shl InputMask.JOY_RIGHT
            1 -> (1 shl InputMask.JOY_RIGHT) or (1 shl InputMask.JOY_DOWN)
            2 -> 1 shl InputMask.JOY_DOWN
            3 -> (1 shl InputMask.JOY_DOWN) or (1 shl InputMask.JOY_LEFT)
            4 -> 1 shl InputMask.JOY_LEFT
            5 -> (1 shl InputMask.JOY_LEFT) or (1 shl InputMask.JOY_UP)
            6 -> 1 shl InputMask.JOY_UP
            7 -> (1 shl InputMask.JOY_UP) or (1 shl InputMask.JOY_RIGHT)
            else -> 0
        }
    }

    /**
     * Clamp a knob offset to the stick's maximum travel, so the knob
     * never leaves the base ring. Returns the clamped (dx, dy), still
     * in units of full travel.
     */
    fun clamp(dx: Float, dy: Float): Pair<Float, Float> {
        val mag = sqrt(dx * dx + dy * dy)
        if (mag <= 1.0f) return dx to dy
        return (dx / mag) to (dy / mag)
    }
}
