package app.howlingdog

/**
 * Run-ahead latency reduction (Phase 10). The number of frames
 * the core runs ahead of the display: 0 is off (the v1.0.0 frame
 * path, exactly), 1 or 2 frames ahead. The value is stored as a
 * string ("off" / "1" / "2") so it fits the settings store, and
 * parsed here so the parsing is unit-testable on the JVM.
 */
object Runahead {
    const val FRAMES_OFF = 0
    const val FRAMES_1 = 1
    const val FRAMES_2 = 2

    /** The stored values the Settings dropdown offers. */
    val OPTIONS: List<Pair<String, String>> = listOf(
        "off" to "Off",
        "1" to "1 frame",
        "2" to "2 frames",
    )

    /** Parse a stored value; anything unknown (including null) is off. */
    fun parse(value: String?): Int = when (value?.lowercase()) {
        "1" -> FRAMES_1
        "2" -> FRAMES_2
        else -> FRAMES_OFF
    }

    /** The stored string for a frame count (0 is "off"). */
    fun store(frames: Int): String = when (frames) {
        FRAMES_1 -> "1"
        FRAMES_2 -> "2"
        else -> "off"
    }
}
