package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the turbo/autofire pulse (Phase 9,
 * item 6): the ~12 Hz pulse while held, the clean stop on release,
 * and the never-sticks guarantee. No Android framework calls — the
 * clock is an explicit parameter.
 */
class TurboInputTest {

    private val a = InputMask.neoA()
    private val b = InputMask.neoB()
    private val c = InputMask.neoC()
    private val d = InputMask.neoD()
    private val up = 1 shl InputMask.JOY_UP

    @Test
    fun turboOffPassesTheRawMaskThrough() {
        val state = TurboState()
        // Held for a whole second: no turbo enabled, nothing pulses.
        for (t in 1000L..2000L step 42L) {
            assertEquals(a or up, state.apply(a or up, 0, t))
        }
    }

    @Test
    fun turboButtonPulsesAtTwelveHertz() {
        val state = TurboState()
        // Press A at t=1000 with turbo on: ON for the first half of
        // each 84 ms period, OFF for the second half.
        assertEquals(a, state.apply(a, a, 1000L)) // press: ON
        assertEquals(a, state.apply(a, a, 1041L)) // last ms of the ON half
        assertEquals(0, state.apply(a, a, 1042L)) // OFF half starts
        assertEquals(0, state.apply(a, a, 1083L)) // last ms of the OFF half
        assertEquals(a, state.apply(a, a, 1084L)) // second period: ON
        assertEquals(a, state.apply(a, a, 1125L))
        assertEquals(0, state.apply(a, a, 1126L)) // OFF again
        assertEquals(0, state.apply(a, a, 1167L))
        assertEquals(a, state.apply(a, a, 1168L)) // third period: ON
    }

    @Test
    fun releaseStopsThePulseAndStaysStopped() {
        val state = TurboState()
        assertEquals(a, state.apply(a, a, 1000L))
        assertEquals(0, state.apply(a, a, 1042L))
        // Released mid-pulse: OFF at once, and still OFF long after.
        assertEquals(0, state.apply(0, a, 1050L))
        assertEquals(0, state.apply(0, a, 2000L))
        assertEquals(0, state.apply(0, a, 10000L))
    }

    @Test
    fun releaseInTheOnHalfStopsAtOnce() {
        val state = TurboState()
        assertEquals(a, state.apply(a, a, 1000L))
        // Released while the pulse is ON: the button goes OFF at
        // once, it does not finish the half-period.
        assertEquals(0, state.apply(0, a, 1010L))
    }

    @Test
    fun nonTurboButtonsAreUnaffectedByThePulse() {
        val state = TurboState()
        // A is turbo, B and UP are not: B and UP stay held through
        // the whole pulse, only A toggles.
        assertEquals(a or b or up, state.apply(a or b or up, a, 1000L))
        assertEquals(b or up, state.apply(a or b or up, a, 1042L))
        assertEquals(a or b or up, state.apply(a or b or up, a, 1084L))
        assertEquals(b or up, state.apply(a or b or up, a, 1126L))
    }

    @Test
    fun repressRestartsThePulseOn() {
        val state = TurboState()
        assertEquals(a, state.apply(a, a, 1000L))
        assertEquals(0, state.apply(a, a, 1042L))
        assertEquals(0, state.apply(0, a, 1050L))
        // A fresh press starts a fresh pulse, ON first — even in
        // the middle of what would have been an OFF half.
        assertEquals(a, state.apply(a, a, 1060L))
        assertEquals(a, state.apply(a, a, 1101L))
        assertEquals(0, state.apply(a, a, 1102L))
    }

    @Test
    fun twoButtonsPulseIndependently() {
        val state = TurboState()
        // A pressed at 1000, B pressed at 1021: each button keeps
        // its own phase.
        assertEquals(a, state.apply(a, a or b, 1000L))
        assertEquals(a or b, state.apply(a or b, a or b, 1021L))
        assertEquals(b, state.apply(a or b, a or b, 1042L)) // A OFF, B ON
        assertEquals(0, state.apply(a or b, a or b, 1063L)) // B OFF too
        assertEquals(a, state.apply(a or b, a or b, 1084L)) // A ON again
        assertEquals(a or b, state.apply(a or b, a or b, 1105L)) // B ON again
    }

    @Test
    fun releaseAllForgetsThePhase() {
        val state = TurboState()
        assertEquals(a, state.apply(a, a, 1000L))
        assertEquals(0, state.apply(a, a, 1042L))
        // Pause/quit/background: the phase is forgotten. A button
        // still held afterwards (the callers clear the held state
        // too, so this is belt and braces) starts a fresh pulse.
        state.releaseAll()
        assertEquals(a, state.apply(a, a, 1042L))
        assertEquals(a, state.apply(a, a, 1083L))
        assertEquals(0, state.apply(a, a, 1084L))
    }

    @Test
    fun everyButtonCanBeTurbo() {
        val state = TurboState()
        val all = a or b or c or d
        assertEquals(all, state.apply(all, all, 1000L))
        assertEquals(0, state.apply(all, all, 1042L))
        assertEquals(all, state.apply(all, all, 1084L))
        assertEquals(0, state.apply(0, all, 1100L))
    }

    @Test
    fun turboRateIsTwelveHertz() {
        // The fixed rate: an 84 ms period, in the 10–15 Hz band.
        assertEquals(84L, TurboInput.PULSE_PERIOD_MS)
        assertEquals(42L, TurboInput.HALF_PERIOD_MS)
        assertTrue(TurboInput.PULSE_PERIOD_MS in 67L..100L)
    }

    @Test
    fun bitForMapsOnlyABCD() {
        assertEquals(a, TurboInput.bitFor("A"))
        assertEquals(b, TurboInput.bitFor("B"))
        assertEquals(c, TurboInput.bitFor("C"))
        assertEquals(d, TurboInput.bitFor("D"))
        assertEquals(0, TurboInput.bitFor("Coin"))
        assertEquals(0, TurboInput.bitFor("Start"))
        assertEquals(0, TurboInput.bitFor(""))
    }
}
