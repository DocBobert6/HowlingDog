package app.howlingdog

/**
 * Phase 9, item 7 — top bar auto-hide during play.
 *
 * The title row on the game screen (game name + SAVE / LOAD /
 * Pause) hides a couple of seconds after a game starts and comes
 * back on a swipe down from the top edge; once revealed it hides
 * again after a few idle seconds, so the screen goes back to being
 * clean. The bar never hides while the controls are being adjusted
 * (the Together / Reset / Done buttons live there), and it comes
 * back whenever the game pauses — so Pause stays reachable by
 * gamepad START+SELECT and the back gesture, and touch users swipe
 * down from the top edge to reveal it first.
 *
 * This is the pure state machine: it holds no Compose state and no
 * Android dependency, so it is unit-testable on the JVM
 * (TopBarVisibilityTest). The Activity drives it — start, pause,
 * resume, edit mode, and the hide timer — and mirrors `visible`
 * into Compose state, so a reveal always re-arms the timer via the
 * `generation` counter (the timer effect keys on it). The swipe
 * itself is detected by a separate observe-only pointer-input
 * detector in MainActivity, which never consumes a touch, so it
 * cannot swallow game input.
 *
 * Timings: hide 2 s after a game starts or resumes (long enough to
 * see the bar first); re-hide 4 s after a swipe-down reveal or
 * after leaving edit mode (long enough to tap SAVE, LOAD, or Pause).
 *
 * The show/hide state. One instance lives in the game screen
 * (remembered); the Activity calls one method per event and mirrors
 * `visible` / `generation` into Compose state.
 */
class TopBarVisibility {
    companion object {
        /** Delay after a game starts (or resumes) before the bar hides. */
        const val HIDE_AFTER_START_MS = 2000L
        /** Idle delay after a swipe-down reveal (or leaving edit mode) before the bar hides again. */
        const val REHIDE_AFTER_IDLE_MS = 4000L
        /** Height of the top-edge strip a swipe down must start in. */
        const val EDGE_HEIGHT_DP = 28f
        /** Downward travel needed before a top-edge swipe counts as a reveal. */
        const val SWIPE_MIN_DP = 20f
    }

    /** Whether the bar is shown right now. Starts shown. */
    var visible: Boolean = true
        private set

    /** Whether the controls are being adjusted (edit mode). */
    var editing: Boolean = false
        private set

    /** How long the Activity's timer waits before calling [onHideDue]. */
    var hideDelayMs: Long = HIDE_AFTER_START_MS
        private set

    /**
     * Bumped on every reveal, so the Activity's hide-timer effect
     * restarts and the bar gets its full idle window again.
     */
    var generation: Int = 0
        private set

    /** A game started: show the bar, then hide it after the start delay. */
    fun onGameStarted() {
        visible = true
        editing = false
        hideDelayMs = HIDE_AFTER_START_MS
        generation++
    }

    /** The hide timer fired: hide, unless the controls are being adjusted. */
    fun onHideDue() {
        if (!editing) visible = false
    }

    /** A swipe down from the top edge: reveal, then hide after the idle delay. */
    fun onSwipeDown() {
        visible = true
        hideDelayMs = REHIDE_AFTER_IDLE_MS
        generation++
    }

    /** Edit mode entered or left. Entering always shows the bar; leaving re-arms the idle timer. */
    fun onEditModeChanged(editing: Boolean) {
        this.editing = editing
        if (editing) {
            visible = true
        } else {
            hideDelayMs = REHIDE_AFTER_IDLE_MS
        }
        generation++
    }

    /** The game paused: show the bar, so SAVE / LOAD stay reachable. */
    fun onPaused() {
        visible = true
        generation++
    }

    /** The game resumed: show the bar, then hide it after the start delay. */
    fun onResumed() {
        visible = true
        hideDelayMs = HIDE_AFTER_START_MS
        generation++
    }

    /** Left the game screen: reset to the shown state for the next game. */
    fun onQuit() {
        visible = true
        editing = false
        hideDelayMs = HIDE_AFTER_START_MS
    }
}
