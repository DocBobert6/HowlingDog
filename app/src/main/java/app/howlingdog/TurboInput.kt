package app.howlingdog

/**
 * Phase 9, item 6 — turbo/autofire (opt-in per-button, default off).
 *
 * A held turbo button pulses: pressed for one half-period, released
 * for the next, at a fixed ~12 Hz (an 84 ms period — in the 10–15 Hz
 * band that suits shooters and run-and-guns). There is no rate
 * setting; the fixed rate is the feature.
 *
 * The pulse is a pure function of the raw held mask, the
 * turbo-enabled mask, and the clock: a button that is not held is
 * never on, so a released button can never stick — the only state
 * is each button's press start time, which sets the phase. A
 * re-press starts a fresh pulse, ON first, so the button feels
 * responsive.
 *
 * The state machine lives at the shared input point (MainActivity
 * pushMask), where the touch mask and the gamepad mask are already
 * ORed together — so one wiring covers both. The Activity drives it
 * with a ticker (re-push every half-period while a turbo button is
 * held) and calls releaseAll() on pause, quit, and background, so
 * the phase can never survive a hold ending.
 *
 * Pure Kotlin, no Android dependencies, unit-tested on the JVM
 * (TurboInputTest) with an explicit clock.
 */
object TurboInput {
    /** Full pulse period: ~12 Hz. */
    const val PULSE_PERIOD_MS = 84L
    /** Half period: the ticker re-pushes at this rate. */
    const val HALF_PERIOD_MS = PULSE_PERIOD_MS / 2

    /** The joypad mask bits a turbo toggle can cover (Neo A/B/C/D). */
    val TURBO_BITS: List<Int> = listOf(
        InputMask.neoA(), InputMask.neoB(), InputMask.neoC(), InputMask.neoD(),
    )

    /** The joypad mask bit for a Neo button id ("A".."D"), or 0. */
    fun bitFor(button: String): Int = when (button) {
        "A" -> InputMask.neoA()
        "B" -> InputMask.neoB()
        "C" -> InputMask.neoC()
        "D" -> InputMask.neoD()
        else -> 0
    }
}

/**
 * The per-button pulse state. One instance lives in the game screen
 * (remembered), fed by pushMask on every input change and every
 * ticker tick.
 */
class TurboState {
    /** When each turbo button's current hold began (joypad bit -> uptime ms). */
    private val pressStartMs = HashMap<Int, Long>()
    private var lastRawMask = 0

    /**
     * The effective mask for this frame: non-turbo bits pass through
     * unchanged; a held turbo button is ON in the first half of each
     * period after its press and OFF in the second half. A button
     * that is not held is always OFF.
     */
    fun apply(rawMask: Int, turboMask: Int, nowMs: Long): Int {
        for (bit in TurboInput.TURBO_BITS) {
            val wasHeld = lastRawMask and bit != 0
            val isHeld = rawMask and bit != 0
            if (isHeld && !wasHeld) pressStartMs[bit] = nowMs
            if (!isHeld) pressStartMs.remove(bit)
        }
        lastRawMask = rawMask
        var out = rawMask
        for (bit in TurboInput.TURBO_BITS) {
            if (rawMask and bit != 0 && turboMask and bit != 0) {
                val start = pressStartMs[bit] ?: nowMs
                if (((nowMs - start) / TurboInput.HALF_PERIOD_MS) % 2 == 1L) {
                    out = out and bit.inv()
                }
            }
        }
        return out
    }

    /**
     * Forget every press: called when the game pauses, quits, or the
     * app backgrounds. The next apply after a releaseAll treats any
     * still-held button as a fresh press (ON first) — and the callers
     * always clear the held state alongside, so in practice nothing
     * is held after this.
     */
    fun releaseAll() {
        pressStartMs.clear()
        lastRawMask = 0
    }
}
