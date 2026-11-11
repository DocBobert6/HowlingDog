package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the input display's history
 * (2.0, feature 4): only mask changes are recorded, the newest
 * N events are kept, and the display labels read as a fightstick
 * strip would. No Android framework calls.
 */
class InputHistoryTest {

    private fun mask(
        up: Boolean = false,
        down: Boolean = false,
        left: Boolean = false,
        right: Boolean = false,
        a: Boolean = false,
        b: Boolean = false,
        c: Boolean = false,
        d: Boolean = false,
        start: Boolean = false,
        coin: Boolean = false,
    ): Int = InputMask.build(up, down, left, right, a, b, c, d, start, coin)

    @Test
    fun emptyHistoryHasNoEvents() {
        val history = InputHistory()
        assertTrue(history.snapshot().isEmpty())
        assertNull(history.latest())
    }

    @Test
    fun onlyChangesAreRecorded() {
        val history = InputHistory()
        assertTrue(history.record(mask(right = true), 1000L))
        // The same mask again is not a new input.
        assertFalse(history.record(mask(right = true), 1100L))
        assertFalse(history.record(mask(right = true), 1200L))
        assertEquals(1, history.snapshot().size)
        // A change is recorded, and so is the release.
        assertTrue(history.record(mask(right = true, a = true), 1300L))
        assertTrue(history.record(mask(), 1400L))
        assertEquals(3, history.snapshot().size)
        assertEquals(mask(), history.latest()?.mask)
    }

    @Test
    fun capacityKeepsOnlyTheNewest() {
        val history = InputHistory(capacity = 3)
        history.record(mask(up = true), 1000L)
        history.record(mask(down = true), 1100L)
        history.record(mask(left = true), 1200L)
        history.record(mask(right = true), 1300L)
        history.record(mask(a = true), 1400L)
        val events = history.snapshot()
        assertEquals(3, events.size)
        assertEquals(mask(left = true), events[0].mask)
        assertEquals(mask(right = true), events[1].mask)
        assertEquals(mask(a = true), events[2].mask)
    }

    @Test
    fun clearEmptiesTheHistory() {
        val history = InputHistory()
        history.record(mask(up = true), 1000L)
        history.record(mask(), 1100L)
        history.clear()
        assertTrue(history.snapshot().isEmpty())
        // After a clear, the first mask records again even if it
        // was the last one before the clear.
        assertTrue(history.record(mask(), 1200L))
    }

    @Test
    fun labelForDirections() {
        assertEquals("↑", InputHistory.labelFor(mask(up = true)))
        assertEquals("↓", InputHistory.labelFor(mask(down = true)))
        assertEquals("←", InputHistory.labelFor(mask(left = true)))
        assertEquals("→", InputHistory.labelFor(mask(right = true)))
        assertEquals("↖", InputHistory.labelFor(mask(up = true, left = true)))
        assertEquals("↗", InputHistory.labelFor(mask(up = true, right = true)))
        assertEquals("↙", InputHistory.labelFor(mask(down = true, left = true)))
        assertEquals("↘", InputHistory.labelFor(mask(down = true, right = true)))
    }

    @Test
    fun labelForButtons() {
        assertEquals("A", InputHistory.labelFor(mask(a = true)))
        assertEquals("B", InputHistory.labelFor(mask(b = true)))
        assertEquals("C", InputHistory.labelFor(mask(c = true)))
        assertEquals("D", InputHistory.labelFor(mask(d = true)))
        assertEquals("ST", InputHistory.labelFor(mask(start = true)))
        assertEquals("CO", InputHistory.labelFor(mask(coin = true)))
        assertEquals("A B", InputHistory.labelFor(mask(a = true, b = true)))
        assertEquals("↖ A", InputHistory.labelFor(mask(up = true, left = true, a = true)))
        assertEquals("→ B C", InputHistory.labelFor(mask(right = true, b = true, c = true)))
    }

    @Test
    fun labelForNeutralIsADot() {
        assertEquals("·", InputHistory.labelFor(mask()))
        assertEquals("·", InputHistory.labelFor(0))
    }
}
