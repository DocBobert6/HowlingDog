package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the haptic mapping: three levels that
 * are clearly distinct from each other, and OFF meaning nothing.
 * No Vibrator is touched here — only the level-to-effect mapping.
 */
class HapticsTest {

    @Test
    fun offMapsToNothing() {
        assertNull(HapticEffect.predefined(HapticLevel.OFF))
        assertNull(HapticEffect.oneShot(HapticLevel.OFF))
    }

    @Test
    fun predefinedEffectsAreThreeDistinctEffects() {
        val light = HapticEffect.predefined(HapticLevel.LIGHT)
        val medium = HapticEffect.predefined(HapticLevel.MEDIUM)
        val heavy = HapticEffect.predefined(HapticLevel.HEAVY)
        assertTrue(light != null && medium != null && heavy != null)
        assertTrue(light != medium && medium != heavy && light != heavy)
    }

    @Test
    fun oneShotAmplitudesRiseStrictlyWithStrength() {
        val light = HapticEffect.oneShot(HapticLevel.LIGHT)!!
        val medium = HapticEffect.oneShot(HapticLevel.MEDIUM)!!
        val heavy = HapticEffect.oneShot(HapticLevel.HEAVY)!!
        assertTrue(light.second < medium.second)
        assertTrue(medium.second < heavy.second)
        assertEquals(255, heavy.second)
        // Durations rise too: a tick, a click, a thud.
        assertTrue(light.first < medium.first)
        assertTrue(medium.first < heavy.first)
    }

    // 2.0, feature 5 — the microswitch actuation detector.

    private fun dir(up: Boolean = false, down: Boolean = false, left: Boolean = false, right: Boolean = false): Int =
        InputMask.build(up, down, left, right)

    @Test
    fun neutralToDirectionClicks() {
        assertTrue(ActuationDetector.isActuation(0, dir(up = true)))
        assertTrue(ActuationDetector.isActuation(0, dir(up = true, right = true)))
        assertTrue(ActuationDetector.isActuation(0, dir(down = true, left = true)))
    }

    @Test
    fun oneDirectionToAnotherClicks() {
        assertTrue("UP to UP+RIGHT is a new actuation", ActuationDetector.isActuation(dir(up = true), dir(up = true, right = true)))
        assertTrue("UP+RIGHT to RIGHT is a new actuation", ActuationDetector.isActuation(dir(up = true, right = true), dir(right = true)))
        assertTrue("UP to DOWN is a new actuation", ActuationDetector.isActuation(dir(up = true), dir(down = true)))
        assertTrue("LEFT to RIGHT is a new actuation", ActuationDetector.isActuation(dir(left = true), dir(right = true)))
    }

    @Test
    fun noChangeAndReleaseDoNotClick() {
        assertFalse("holding UP is not a new actuation", ActuationDetector.isActuation(dir(up = true), dir(up = true)))
        assertFalse("holding UP+RIGHT is not a new actuation", ActuationDetector.isActuation(dir(up = true, right = true), dir(up = true, right = true)))
        assertFalse("a release back to neutral does not click", ActuationDetector.isActuation(dir(up = true), 0))
        assertFalse("neutral to neutral does not click", ActuationDetector.isActuation(0, 0))
    }

    @Test
    fun buttonBitsNeverClickTheStick() {
        val up = dir(up = true)
        assertFalse("pressing A while holding UP is not a stick actuation", ActuationDetector.isActuation(up, up or InputMask.neoA()))
        assertFalse("releasing A while holding UP is not a stick actuation", ActuationDetector.isActuation(up or InputMask.neoA(), up))
        assertFalse("a button alone is not a stick actuation", ActuationDetector.isActuation(0, InputMask.neoA()))
        assertTrue("a direction with a button still clicks on actuation", ActuationDetector.isActuation(0, up or InputMask.neoA()))
    }

    @Test
    fun coinThudIsHeavierThanEveryLevelClick() {
        assertEquals(60, HapticEffect.COIN_THUD_DURATION_MS)
        assertEquals(255, HapticEffect.COIN_THUD_AMPLITUDE)
        // Longer than the heaviest level's one-shot, at full
        // amplitude — so the coin drop is distinct at every level.
        assertTrue(HapticEffect.COIN_THUD_DURATION_MS > HapticEffect.oneShot(HapticLevel.HEAVY)!!.first)
        assertEquals(255, HapticEffect.oneShot(HapticLevel.HEAVY)!!.second)
    }
}
