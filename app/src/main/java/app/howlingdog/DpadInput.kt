package app.howlingdog

/**
 * The 8-way D-pad's direction math (pure Kotlin, no Android
 * dependencies, unit-tested on the plain JVM).
 *
 * This is NOT the old four-way D-pad (Robert called that one too
 * simple): it is an eight-way pad with working diagonals, using
 * exactly the stick's logic — the same 0.22 dead zone and the
 * same 0.35 square-gate diagonal band (StickInput). A press in a
 * corner of the pad reports both directions (UP+RIGHT), a press
 * on an edge reports the one direction, and a press near the
 * centre reports nothing.
 *
 * Coordinates: (x, y) is the press position inside the pad's
 * square touch area, in px; [size] is that square's side, also
 * in px. +x is right, +y is down (Android touch convention).
 * The direction comes from the press offset from the centre,
 * normalised by the pad's active radius, and is returned as a
 * direction mask using the InputMask joypad bits — the same
 * mask the stick and the touch buttons feed, so it slots
 * straight into the Held state.
 */
object DpadInput {

    /**
     * The pad's active radius, as a fraction of its half-size:
     * pressing at or beyond this is full deflection (the
     * normalised offset is 1.0). The last 10% to the edge is
     * margin, so a thumb at the pad's edge is fully deflected
     * rather than just past the diagonal boundary.
     */
    const val ACTIVE_RADIUS = 0.9f

    /** Direction bits from a press at (x, y) inside a pad of [size] px. */
    fun directionFromPress(x: Float, y: Float, size: Float): Int {
        if (size <= 0f) return 0
        val half = size / 2f
        val active = half * ACTIVE_RADIUS
        if (active <= 0f) return 0
        val dx = ((x - half) / active).coerceIn(-1f, 1f)
        val dy = ((y - half) / active).coerceIn(-1f, 1f)
        return StickInput.direction(dx, dy)
    }
}
