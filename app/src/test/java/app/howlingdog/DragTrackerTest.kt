package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM unit tests for the drag lifecycle ([DragTracker]): the
 * stick/D-pad/button must track one pointer and one pointer only,
 * so a second finger lifting can never end the drag — that was the
 * "lets go / loses track of my finger" bug. No Android framework
 * calls.
 */
class DragTrackerTest {

    // Pointer ids: 1 = the stick thumb, 2 = a face-button finger.
    private val stick = 1L
    private val button = 2L

    @Test
    fun freshPressClaimsTheDrag() {
        val tracker = DragTracker(releaseOnExit = false)
        assertEquals(DragAction.CLAIM, tracker.onChange(stick, true, false, false))
        assertEquals(stick, tracker.activeId)
    }

    @Test
    fun moveOfTheClaimedPointerUpdates() {
        val tracker = DragTracker(releaseOnExit = false)
        tracker.onChange(stick, true, false, false)
        assertEquals(DragAction.UPDATE, tracker.onChange(stick, true, true, false))
        assertEquals(stick, tracker.activeId)
    }

    @Test
    fun releaseOfAnotherPointerDoesNotEndTheDrag() {
        // The bug: a batch Release (a face button lifting) ended
        // the stick's drag because the handler only checked that
        // the stick's id appeared in the event's changes. Per
        // change, the other pointer's lift is not this drag.
        val tracker = DragTracker(releaseOnExit = false)
        tracker.onChange(stick, true, false, false)
        // The button finger was already down (previousPressed) and
        // now lifts — its change must not touch the drag.
        assertEquals(DragAction.IGNORE, tracker.onChange(button, false, true, false))
        assertEquals(stick, tracker.activeId)
        // The stick thumb is still down and still tracked.
        assertEquals(DragAction.UPDATE, tracker.onChange(stick, true, true, false))
    }

    @Test
    fun releaseOfTheClaimedPointerEndsTheDrag() {
        val tracker = DragTracker(releaseOnExit = false)
        tracker.onChange(stick, true, false, false)
        assertEquals(DragAction.END, tracker.onChange(stick, false, true, false))
        assertNull(tracker.activeId)
    }

    @Test
    fun afterAReleaseAFreshPressClaimsAgain() {
        val tracker = DragTracker(releaseOnExit = false)
        tracker.onChange(stick, true, false, false)
        tracker.onChange(stick, false, true, false)
        assertEquals(DragAction.CLAIM, tracker.onChange(stick, true, false, false))
        assertEquals(stick, tracker.activeId)
    }

    @Test
    fun aPointerAlreadyDownCannotTakeOver() {
        // The button finger was down before the tracker started
        // (previousPressed): it is not a fresh press, so it must
        // never claim the drag — the stick would start steering
        // from a button.
        val tracker = DragTracker(releaseOnExit = false)
        assertEquals(DragAction.IGNORE, tracker.onChange(button, true, true, false))
        assertNull(tracker.activeId)
    }

    @Test
    fun aFreshPressOutsideTheBoundsDoesNotClaim() {
        // A press that started outside the control (a scope
        // restart while a finger is down elsewhere) must not
        // claim the drag.
        val tracker = DragTracker(releaseOnExit = false)
        assertEquals(DragAction.IGNORE, tracker.onChange(button, true, false, true))
        assertNull(tracker.activeId)
    }

    @Test
    fun aSecondFreshPressWhileDraggingIsIgnored() {
        val tracker = DragTracker(releaseOnExit = false)
        tracker.onChange(stick, true, false, false)
        assertEquals(DragAction.IGNORE, tracker.onChange(button, true, false, false))
        assertEquals(stick, tracker.activeId)
    }

    @Test
    fun stickKeepsTrackingOutOfBounds() {
        // The stick ignores Exit: the finger sliding out of the
        // touch area while still down keeps the direction.
        val tracker = DragTracker(releaseOnExit = false)
        tracker.onChange(stick, true, false, false)
        assertEquals(DragAction.UPDATE, tracker.onChange(stick, true, true, true))
        assertEquals(stick, tracker.activeId)
    }

    @Test
    fun buttonReleasesWhenTheFingerSlidesOff() {
        // A button must let go when the finger slides off it —
        // that is correct button behaviour, not the stick bug.
        val tracker = DragTracker(releaseOnExit = true)
        tracker.onChange(button, true, false, false)
        assertEquals(DragAction.END, tracker.onChange(button, true, true, true))
        assertNull(tracker.activeId)
    }

    @Test
    fun buttonLiftEndsTheHold() {
        val tracker = DragTracker(releaseOnExit = true)
        tracker.onChange(button, true, false, false)
        assertEquals(DragAction.END, tracker.onChange(button, false, true, false))
        assertNull(tracker.activeId)
    }

    @Test
    fun resetEndsALiveDragAndIsANoOpWhenIdle() {
        val tracker = DragTracker(releaseOnExit = false)
        assertEquals(DragAction.IGNORE, tracker.reset())
        tracker.onChange(stick, true, false, false)
        assertEquals(DragAction.END, tracker.reset())
        assertNull(tracker.activeId)
        assertEquals(DragAction.IGNORE, tracker.reset())
    }

    @Test
    fun aCancelEndsTheDrag() {
        // A Cancel arrives as the pointer no longer being down
        // (pressed false), so it ends the drag like a lift.
        val tracker = DragTracker(releaseOnExit = false)
        tracker.onChange(stick, true, false, false)
        assertEquals(DragAction.END, tracker.onChange(stick, false, true, false))
        assertNull(tracker.activeId)
    }
}
