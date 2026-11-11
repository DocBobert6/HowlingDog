package app.howlingdog

/**
 * The outcome of feeding one pointer change to a [DragTracker].
 */
enum class DragAction {
    /** Not for this tracker: another pointer, or a change that ends nothing. */
    IGNORE,
    /** A fresh press claims the drag (the caller applies the position). */
    CLAIM,
    /** The tracked pointer moved or is still down (the caller applies the position). */
    UPDATE,
    /** The tracked pointer released, slid off (buttons only), or was reset — the drag is over. */
    END,
}

/**
 * Pure drag-lifecycle state machine for one pointer. The on-screen
 * controls (PsStick, Dpad, HoldButton) all used to switch on the
 * batch [PointerEventType] — Release, Press, Move — and that is
 * what let the stick go: a batch Release fires when ANY finger
 * lifts, and the handler only checked that the tracked pointer's id
 * merely appeared in the event's changes, not that it had actually
 * lifted. So a face button lifting while the stick was dragged
 * ended the drag, zeroed the knob, and cleared the direction — the
 * "lets go / loses track of my finger" report. The Press handler
 * had the mirror fault: it claimed the first pressed change in the
 * list, which could be a button finger already down elsewhere, so
 * the stick could start steering from a button.
 *
 * This tracker decides per CHANGE, not per batch:
 * - a change is claimed only when it is a FRESH press (pressed and
 *   not previously pressed) that starts INSIDE the control's bounds
 *   (not out of bounds) — a finger already down elsewhere can
 *   never take the drag over;
 * - the tracked pointer keeps tracking while it is still down,
 *   even out of bounds (the stick and the D-pad ignore Exit);
 * - only the tracked pointer itself ending (pressed -> released,
 *   which is also how a Cancel arrives) ends the drag;
 * - a button ([releaseOnExit] = true) also ends when the tracked
 *   pointer slides out of bounds while still down — a button must
 *   let go when the finger slides off it, a direction must not.
 * The caller feeds every change in every event to [onChange] and
 * applies the position on CLAIM/UPDATE, clears on END. [reset]
 * ends a live drag (pause) so no stale pointer survives a scope
 * restart. Pure, so it is unit-tested on the JVM.
 */
class DragTracker(private val releaseOnExit: Boolean) {
    /** The pointer id currently driving the drag, or null. */
    var activeId: Long? = null
        private set

    /**
     * Feed one pointer change. [id] is the pointer's id,
     * [pressed] whether it is down now, [previousPressed] whether
     * it was down in the previous event, [outOfBounds] whether its
     * position is outside the control's bounds.
     */
    fun onChange(
        id: Long,
        pressed: Boolean,
        previousPressed: Boolean,
        outOfBounds: Boolean,
    ): DragAction {
        if (id == activeId) {
            // The tracked pointer: only its own lift ends the
            // drag. A lift of ANY other pointer is not this
            // change, so it is ignored here.
            if (!pressed) {
                activeId = null
                return DragAction.END
            }
            if (releaseOnExit && outOfBounds) {
                activeId = null
                return DragAction.END
            }
            return DragAction.UPDATE
        }
        // A fresh press inside the bounds claims the drag. The
        // previousPressed guard keeps a finger that was already
        // down (a button, the other thumb) from taking over, and
        // the outOfBounds guard keeps a press that started outside
        // the control from claiming it on a scope restart.
        if (activeId == null && pressed && !previousPressed && !outOfBounds) {
            activeId = id
            return DragAction.CLAIM
        }
        return DragAction.IGNORE
    }

    /** End a live drag (pause, scope restart). END if one was live, else IGNORE. */
    fun reset(): DragAction {
        return if (activeId != null) {
            activeId = null
            DragAction.END
        } else {
            DragAction.IGNORE
        }
    }
}
