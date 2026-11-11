package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the fast-forward / slow-motion speed control (v1.5, feature 1). */
class SpeedControlTest {

    @Test
    fun normalSpeedWithNothingHeld() {
        assertEquals(1.0, SpeedControl.factor(false, false, SpeedControl.FF_2X, true), 0.0)
    }

    @Test
    fun fastForwardHoldRunsAtTheConfiguredSpeed() {
        assertEquals(2.0, SpeedControl.factor(true, false, SpeedControl.FF_2X, false), 0.0)
        assertEquals(4.0, SpeedControl.factor(true, false, SpeedControl.FF_4X, false), 0.0)
    }

    @Test
    fun fastForwardHoldWithTheSpeedOffRunsNormal() {
        assertEquals(1.0, SpeedControl.factor(true, false, SpeedControl.FF_OFF, false), 0.0)
    }

    @Test
    fun slowMotionHoldRunsAtHalfSpeed() {
        assertEquals(0.5, SpeedControl.factor(false, true, SpeedControl.FF_OFF, true), 0.0)
    }

    @Test
    fun slowMotionHoldWithSlowMotionOffRunsNormal() {
        assertEquals(1.0, SpeedControl.factor(false, true, SpeedControl.FF_OFF, false), 0.0)
    }

    @Test
    fun fastForwardWinsWhenBothAreHeld() {
        assertEquals(2.0, SpeedControl.factor(true, true, SpeedControl.FF_2X, true), 0.0)
    }

    @Test
    fun frameSleepHalvesAt2xQuartersAt4xDoublesAtHalfSpeed() {
        assertEquals(8_333_333L, SpeedControl.frameSleepNs(16_666_666L, 2.0))
        assertEquals(4_166_666L, SpeedControl.frameSleepNs(16_666_666L, 4.0))
        assertEquals(33_333_332L, SpeedControl.frameSleepNs(16_666_666L, 0.5))
        assertEquals(16_666_666L, SpeedControl.frameSleepNs(16_666_666L, 1.0))
    }

    @Test
    fun badFactorFallsBackToTheBaseFrameTime() {
        assertEquals(16_666_666L, SpeedControl.frameSleepNs(16_666_666L, 0.0))
        assertEquals(16_666_666L, SpeedControl.frameSleepNs(16_666_666L, -2.0))
    }

    @Test
    fun audioMutedAtAnySpeedExceptNormal() {
        assertFalse(SpeedControl.audioMuted(1.0))
        assertTrue(SpeedControl.audioMuted(2.0))
        assertTrue(SpeedControl.audioMuted(4.0))
        assertTrue(SpeedControl.audioMuted(0.5))
    }

    @Test
    fun fastForwardSettingRoundTrips() {
        assertEquals(2.0, SpeedControl.parseFfSetting(SpeedControl.storeFfSetting(2.0)), 0.0)
        assertEquals(4.0, SpeedControl.parseFfSetting(SpeedControl.storeFfSetting(4.0)), 0.0)
        assertEquals(0.0, SpeedControl.parseFfSetting(SpeedControl.storeFfSetting(0.0)), 0.0)
        assertEquals("2", SpeedControl.storeFfSetting(2.0))
        assertEquals("4", SpeedControl.storeFfSetting(4.0))
        assertEquals("off", SpeedControl.storeFfSetting(0.0))
    }

    @Test
    fun fastForwardSettingDefaultsToOff() {
        assertEquals(0.0, SpeedControl.parseFfSetting(null), 0.0)
        assertEquals(0.0, SpeedControl.parseFfSetting(""), 0.0)
        assertEquals(0.0, SpeedControl.parseFfSetting("3"), 0.0)
        assertEquals(0.0, SpeedControl.parseFfSetting("maybe"), 0.0)
    }

    @Test
    fun slowMotionSettingRoundTripsAndDefaultsToOff() {
        assertTrue(SpeedControl.parseSlowSetting(SpeedControl.storeSlowSetting(true)))
        assertFalse(SpeedControl.parseSlowSetting(SpeedControl.storeSlowSetting(false)))
        assertEquals("on", SpeedControl.storeSlowSetting(true))
        assertEquals("off", SpeedControl.storeSlowSetting(false))
        assertFalse(SpeedControl.parseSlowSetting(null))
        assertFalse(SpeedControl.parseSlowSetting("yes"))
    }
}
