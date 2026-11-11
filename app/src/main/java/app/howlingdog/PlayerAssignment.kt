package app.howlingdog

/**
 * v1.5, feature 5 — two-player multiplayer: which gamepad is
 * player 1 and which is player 2.
 *
 * The first gamepad to send input is player 1, the second
 * distinct gamepad is player 2, and a third gamepad is ignored.
 * A gamepad keeps its player until it disconnects (see [release]),
 * so the two pads never swap mid-game. The touch overlay is always
 * player 1, so a single gamepad plays as player 1 exactly as
 * before — the second pad only appears when a second gamepad
 * actually sends input.
 *
 * Pure (no Android types — device ids are plain ints), so it is
 * unit-tested on the JVM (PlayerAssignmentTest). The Activity
 * owns one instance and calls [playerFor] from its key and motion
 * handlers, and [release] from its device listener.
 */
class PlayerAssignment {

    /** No gamepad assigned to this player yet. */
    var deviceP1: Int = NONE
        private set

    /** No gamepad assigned to this player yet. */
    var deviceP2: Int = NONE
        private set

    /**
     * The player for this gamepad device id: [PLAYER_1],
     * [PLAYER_2], or [IGNORED] (a third gamepad). An assigned
     * device keeps its player; a new device takes the first free
     * slot (player 1, then player 2).
     */
    fun playerFor(deviceId: Int): Int {
        if (deviceId == deviceP1) return PLAYER_1
        if (deviceId == deviceP2) return PLAYER_2
        if (deviceP1 == NONE) {
            deviceP1 = deviceId
            return PLAYER_1
        }
        if (deviceP2 == NONE) {
            deviceP2 = deviceId
            return PLAYER_2
        }
        return IGNORED
    }

    /** Free the slot held by this device (on disconnect). */
    fun release(deviceId: Int) {
        if (deviceId == deviceP1) deviceP1 = NONE
        if (deviceId == deviceP2) deviceP2 = NONE
    }

    /**
     * Forget both assignments. Not used on quit or background —
     * the pads keep their players across games, and a slot is
     * only freed when its device disconnects (see [release]).
     * Kept for a full reset (and tested): if the app ever needs
     * to forget both pads at once.
     */
    fun clear() {
        deviceP1 = NONE
        deviceP2 = NONE
    }

    companion object {
        const val NONE = -1
        const val PLAYER_1 = 1
        const val PLAYER_2 = 2

        /** A third gamepad: its input is ignored. */
        const val IGNORED = 0
    }
}
