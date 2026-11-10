package app.howlingdog

import org.junit.Assert.assertEquals
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
}
