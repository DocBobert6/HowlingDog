package app.howlingdog

/**
 * v1.5, feature 1 — fast-forward / slow motion.
 *
 * The speed is a multiplier on the frame pace: the emu thread's
 * frame sleep is frameNs / factor, so 2x runs two frames per
 * real-time frame slot (loading screens and long intros pass in
 * half the time) and 0.5x runs one frame per two slots (a hard
 * section at half speed). The factor is a plain number the UI
 * pushes into the session; the session reads it every frame, so
 * a hold starts and stops the speed at once.
 *
 * Audio is muted while the speed is not 1x (see PlayerSession):
 * at 2x/4x the core produces two to four times the audio, which
 * would screech, and at 0.5x the track would starve. Muting is
 * what other emulators do, and the sound returns the moment the
 * speed returns to normal.
 *
 * Pure Kotlin, unit-tested on the JVM (SpeedControlTest).
 */
object SpeedControl {
    /** Fast-forward off. */
    const val FF_OFF = 0.0
    /** Fast-forward at double speed. */
    const val FF_2X = 2.0
    /** Fast-forward at quadruple speed. */
    const val FF_4X = 4.0
    /** Slow motion at half speed. */
    const val SLOW_HALF = 0.5
    /** Normal speed. */
    const val NORMAL = 1.0

    /**
     * The factor for the current holds: fast-forward wins when
     * both are held (a slow-motion hold under a fast-forward hold
     * does nothing — the two never fight). A hold only counts
     * while its speed is enabled in Settings: a fast-forward hold
     * with the speed off, or a slow-motion hold with slow motion
     * off, runs at normal speed.
     */
    fun factor(
        ffHeld: Boolean,
        slowHeld: Boolean,
        ffSpeed: Double,
        slowOn: Boolean,
    ): Double {
        if (ffHeld && ffSpeed > NORMAL) return ffSpeed
        if (slowHeld && slowOn) return SLOW_HALF
        return NORMAL
    }

    /** The frame sleep for a factor: the base frame time divided by it (half the sleep at 2x, double at 0.5x). */
    fun frameSleepNs(frameNs: Long, factor: Double): Long {
        if (factor <= 0.0) return frameNs
        return (frameNs / factor).toLong()
    }

    /** True when the audio is muted: any speed other than normal. */
    fun audioMuted(factor: Double): Boolean = factor != NORMAL

    /** The stored fast-forward setting ("off" / "2" / "4") as a factor; anything else (including absent) is off. */
    fun parseFfSetting(stored: String?): Double = when (stored?.trim()) {
        "2" -> FF_2X
        "4" -> FF_4X
        else -> FF_OFF
    }

    /** A fast-forward factor as the stored setting. */
    fun storeFfSetting(factor: Double): String = when (factor) {
        FF_2X -> "2"
        FF_4X -> "4"
        else -> "off"
    }

    /** The stored slow-motion setting ("on" / "off"); anything else (including absent) is off. */
    fun parseSlowSetting(stored: String?): Boolean = stored?.trim()?.lowercase() == "on"

    /** A slow-motion flag as the stored setting. */
    fun storeSlowSetting(on: Boolean): String = if (on) "on" else "off"
}
