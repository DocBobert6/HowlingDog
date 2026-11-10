package app.howlingdog

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The arcade stick's direction math (pure Kotlin, no
 * Android dependencies, unit-tested on the JVM).
 *
 * The stick is fixed in place: the thumb drags a knob inside a base
 * ring, and the direction comes from the drag. Square-gate rule,
 * like the real arcade stick: a diagonal is the corner, where both
 * switches close. Once past the dead zone, if the smaller axis is
 * at least 0.35 of the larger, both directions are on; otherwise
 * only the dominant axis is. That makes the diagonal band wide
 * (about 19 to 71 degrees off horizontal, ~51 degrees) instead of
 * an exact 45-degree angle, so a thumb pushing "up and to the
 * right" lands on up-right even a fair way off centre. The first
 * square-gate attempt used 0.5 (a ~37-degree band, narrower than
 * the original 45-degree sectors) and still did not perfect
 * diagonals on device in Metal Slug 5, so Phase 6 widens it to
 * 0.35: a deliberate straight push still reads as its cardinal
 * (each cardinal keeps a ~19-degree band), while a push into the
 * corner reads as a diagonal across a much wider range.
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

    /** Ratio of the smaller axis to the larger at which a push
        counts as a diagonal (both directions on). 0.35 gives a
        diagonal band of about 19 to 71 degrees off horizontal
        (~51 degrees); a deliberate straight push keeps its
        cardinal (each cardinal keeps a ~19-degree band). */
    const val DIAGONAL_RATIO = 0.35f

    /** Direction bits from the knob offset (dx, dy), each in units of full travel. */
    fun direction(dx: Float, dy: Float): Int {
        val ax = abs(dx)
        val ay = abs(dy)
        val mag = sqrt(dx * dx + dy * dy)
        if (mag < DEAD_ZONE) return 0
        // Diagonal when both axes are substantially engaged: the
        // smaller at least DIAGONAL_RATIO of the larger (about
        // 19-71 degrees off horizontal). Otherwise the dominant
        // axis alone.
        return if (minOf(ax, ay) >= DIAGONAL_RATIO * maxOf(ax, ay)) {
            var mask = 0
            if (dy < 0) mask = mask or (1 shl InputMask.JOY_UP)
            else mask = mask or (1 shl InputMask.JOY_DOWN)
            if (dx < 0) mask = mask or (1 shl InputMask.JOY_LEFT)
            else mask = mask or (1 shl InputMask.JOY_RIGHT)
            mask
        } else if (ax >= ay) {
            if (dx < 0) 1 shl InputMask.JOY_LEFT else 1 shl InputMask.JOY_RIGHT
        } else {
            if (dy < 0) 1 shl InputMask.JOY_UP else 1 shl InputMask.JOY_DOWN
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
