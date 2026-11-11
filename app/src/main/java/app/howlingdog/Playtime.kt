package app.howlingdog

/**
 * 2.0, feature 1 — playtime tracking. A pure, clock-injected
 * accumulator: the caller passes "now" explicitly, so the tests
 * can drive it with a fake clock and no Android framework is
 * needed.
 *
 * Accounting (the decision, documented here so it is not
 * re-litigated):
 * - Playtime is WALL-CLOCK time while the game is running and
 *   not paused. It counts from game start (startGame) to quit,
 *   and it does NOT count time in the pause menu or the library.
 * - Fast-forward and slow motion do not change the accounting:
 *   the player experiences more game per wall-clock second at
 *   2x, but the time played is still wall-clock time (the same
 *   convention as Steam playtime).
 * - The tracker is created per game start with the game's stored
 *   total as its initial value, so [totalMillis] is always the
 *   FULL total (stored + this session). Persisting is a plain
 *   overwrite of the stored total — never an add — so pausing
 *   and quitting can both persist without double-counting.
 * - The total is persisted on pause AND on quit, so a kill
 *   mid-pause loses nothing; a kill mid-game loses only the
 *   running segment since the last pause.
 *
 * A segment is the stretch between a start/resume and a pause/
 * stop. Ending a segment twice (pause, then stop, or pause,
 * then pause) is a no-op — the second call adds nothing.
 */
class PlaytimeTracker(initialTotalMillis: Long = 0) {
    /** The full total: the stored total plus this session's time. */
    var totalMillis: Long = initialTotalMillis.coerceAtLeast(0)
        private set

    /** The start of the current segment, or null when not playing. */
    private var segmentStartMillis: Long? = null

    /** True while a segment is running (the game is playing). */
    val isPlaying: Boolean get() = segmentStartMillis != null

    /** Begin the first segment of a session. A no-op if already playing. */
    fun start(nowMillis: Long) {
        if (segmentStartMillis == null) segmentStartMillis = nowMillis
    }

    /** End the current segment and fold it into the total. A no-op when not playing. */
    fun pause(nowMillis: Long) {
        val start = segmentStartMillis ?: return
        totalMillis += (nowMillis - start).coerceAtLeast(0)
        segmentStartMillis = null
    }

    /** Begin a new segment after a pause. A no-op if already playing. */
    fun resume(nowMillis: Long) {
        if (segmentStartMillis == null) segmentStartMillis = nowMillis
    }

    /** End the current segment (if any) and return the full total. */
    fun stop(nowMillis: Long): Long {
        pause(nowMillis)
        return totalMillis
    }
}

/**
 * The display form for a total: "12h 34m" for an hour or more,
 * "45m" for minutes, "30s" for under a minute. Hours never show
 * a zero minute part ("2h 0m" reads fine and stays explicit).
 */
fun formatPlaytime(totalMillis: Long): String {
    val totalSeconds = (totalMillis.coerceAtLeast(0) / 1000)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}
