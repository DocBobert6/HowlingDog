package app.howlingdog

/**
 * 2.0, feature 4 — the on-screen input display's history: the
 * recent inputs as they happen, like a fightstick display.
 * Pure Kotlin (no Android), unit-tested on the JVM
 * (InputHistoryTest).
 *
 * The history records the input mask at the shared pushMask()
 * point (touch + gamepad, before the turbo pulse — the display
 * shows what the player is holding, not the autofire flicker).
 * Only CHANGES are recorded, so holding a button does not flood
 * the strip; a release is recorded as a neutral entry, so the
 * strip always ends with the current state. The newest N events
 * are kept (capacity 8).
 */
class InputHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    /** One recorded input: the mask and when it started. */
    data class InputEvent(val mask: Int, val atMillis: Long)

    private val events = ArrayDeque<InputEvent>()
    private var lastMask: Int? = null

    /**
     * Record a mask, but only when it differs from the last one
     * recorded. Returns true when an event was added.
     */
    fun record(mask: Int, nowMillis: Long): Boolean {
        if (mask == lastMask) return false
        lastMask = mask
        events.addLast(InputEvent(mask, nowMillis))
        while (events.size > capacity) events.removeFirst()
        return true
    }

    /** The recorded events, oldest first. */
    fun snapshot(): List<InputEvent> = events.toList()

    /** The most recent event, or null when nothing is recorded. */
    fun latest(): InputEvent? = events.lastOrNull()

    fun clear() {
        events.clear()
        lastMask = null
    }

    companion object {
        /** How many recent inputs the strip shows. */
        const val DEFAULT_CAPACITY = 8

        /**
         * The display label for one mask: the direction arrow
         * then the buttons ("↖ A", "→ B C", "ST"), or "·" for
         * neutral (nothing held). Diagonals get a single arrow.
         */
        fun labelFor(mask: Int): String {
            val up = (mask and (1 shl InputMask.JOY_UP)) != 0
            val down = (mask and (1 shl InputMask.JOY_DOWN)) != 0
            val left = (mask and (1 shl InputMask.JOY_LEFT)) != 0
            val right = (mask and (1 shl InputMask.JOY_RIGHT)) != 0
            val direction = when {
                up && left -> "↖"
                up && right -> "↗"
                down && left -> "↙"
                down && right -> "↘"
                up -> "↑"
                down -> "↓"
                left -> "←"
                right -> "→"
                else -> ""
            }
            val buttons = mutableListOf<String>()
            if ((mask and InputMask.neoA()) != 0) buttons.add("A")
            if ((mask and InputMask.neoB()) != 0) buttons.add("B")
            if ((mask and InputMask.neoC()) != 0) buttons.add("C")
            if ((mask and InputMask.neoD()) != 0) buttons.add("D")
            if ((mask and (1 shl InputMask.JOY_START)) != 0) buttons.add("ST")
            if ((mask and (1 shl InputMask.JOY_SELECT)) != 0) buttons.add("CO")
            val parts = mutableListOf<String>()
            if (direction.isNotEmpty()) parts.add(direction)
            parts.addAll(buttons)
            return if (parts.isEmpty()) "·" else parts.joinToString(" ")
        }
    }
}
