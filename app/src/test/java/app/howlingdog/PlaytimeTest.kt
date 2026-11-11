package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for 2.0, feature 1 (playtime tracking):
 * the clock-injected accumulator and the display format. No
 * Android framework calls — the tracker takes "now" explicitly.
 */
class PlaytimeTest {

    // --- accumulation ---

    @Test
    fun startToStopAccumulatesOneSegment() {
        val t = PlaytimeTracker()
        t.start(1_000L)
        assertEquals(5_000L, t.stop(6_000L))
        assertFalse(t.isPlaying)
    }

    @Test
    fun pauseEndsSegmentAndResumeStartsAnother() {
        val t = PlaytimeTracker()
        t.start(0L)
        t.pause(10_000L)
        assertEquals(10_000L, t.totalMillis)
        assertFalse(t.isPlaying)
        t.resume(20_000L)
        assertTrue(t.isPlaying)
        t.pause(25_000L)
        assertEquals(15_000L, t.totalMillis)
    }

    @Test
    fun doublePauseAndDoubleStopNeverDoubleCount() {
        // Ending a segment twice (pause, then stop, or pause,
        // then pause) adds nothing the second time.
        val t = PlaytimeTracker()
        t.start(0L)
        t.pause(10_000L)
        t.pause(20_000L)
        assertEquals(10_000L, t.totalMillis)
        t.start(30_000L)
        t.stop(40_000L)
        assertEquals(20_000L, t.stop(50_000L))
        assertEquals(20_000L, t.totalMillis)
    }

    @Test
    fun startAndResumeWhilePlayingAreNoOps() {
        val t = PlaytimeTracker()
        t.start(0L)
        t.start(5_000L)
        t.resume(8_000L)
        assertEquals(10_000L, t.stop(10_000L))
    }

    @Test
    fun initialTotalIsPreservedAndAccumulatesOnTop() {
        // The tracker is seeded with the stored total, so its
        // total is always the FULL total (stored + this session).
        val t = PlaytimeTracker(3_600_000L)
        assertEquals(3_600_000L, t.totalMillis)
        t.start(0L)
        t.pause(60_000L)
        assertEquals(3_660_000L, t.totalMillis)
    }

    @Test
    fun negativeInitialTotalClampsToZero() {
        assertEquals(0L, PlaytimeTracker(-5_000L).totalMillis)
    }

    @Test
    fun clockGoingBackwardsAddsNothing() {
        val t = PlaytimeTracker()
        t.start(10_000L)
        t.pause(5_000L)
        assertEquals(0L, t.totalMillis)
    }

    @Test
    fun pauseWithoutPlayingIsANoOp() {
        val t = PlaytimeTracker(7_000L)
        t.pause(9_000L)
        assertEquals(7_000L, t.totalMillis)
        assertEquals(7_000L, t.stop(11_000L))
    }

    // --- display format ---

    @Test
    fun formatPlaytimeHoursMinutesSeconds() {
        assertEquals("0s", formatPlaytime(0L))
        assertEquals("30s", formatPlaytime(30_000L))
        assertEquals("59s", formatPlaytime(59_999L))
        assertEquals("1m", formatPlaytime(60_000L))
        assertEquals("45m", formatPlaytime(45 * 60_000L))
        assertEquals("59m", formatPlaytime(59 * 60_000L + 59_000L))
        assertEquals("1h 0m", formatPlaytime(3_600_000L))
        assertEquals("1h 5m", formatPlaytime(3_600_000L + 5 * 60_000L))
        assertEquals("12h 34m", formatPlaytime(12 * 3_600_000L + 34 * 60_000L))
        assertEquals("100h 0m", formatPlaytime(100 * 3_600_000L))
    }

    @Test
    fun formatPlaytimeNegativeClampsToZero() {
        assertEquals("0s", formatPlaytime(-1_000L))
    }
}
