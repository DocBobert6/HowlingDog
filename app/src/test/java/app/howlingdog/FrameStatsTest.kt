package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM unit test for the frame-brightness sampling used by the
 * no-video diagnostic overlay (FrameStats). Geometry is the Neo Geo
 * frame size the core delivers: 304x224.
 */
class FrameStatsTest {

    private val w = 304
    private val h = 224

    @Test
    fun blackFrameIsZero() {
        val pixels = IntArray(w * h) { 0xFF000000.toInt() }
        val b = sampleFrameBrightness(pixels, w, h)
        assertEquals(0, b.mean)
        assertEquals(0, b.centreR)
        assertEquals(0, b.centreG)
        assertEquals(0, b.centreB)
    }

    @Test
    fun whiteFrameIsMax() {
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        val b = sampleFrameBrightness(pixels, w, h)
        assertEquals(255, b.mean)
        assertEquals(255, b.centreR)
        assertEquals(255, b.centreG)
        assertEquals(255, b.centreB)
    }

    @Test
    fun singleBrightCentrePixel() {
        val pixels = IntArray(w * h) { 0xFF000000.toInt() }
        pixels[(h / 2) * w + (w / 2)] = 0xFFFF0000.toInt()
        val b = sampleFrameBrightness(pixels, w, h)
        // The centre pixel is on the 8x8 grid, so it is the only
        // non-zero sample of 64: mean = 255 / (3 * 64) = 1.
        assertEquals(1, b.mean)
        assertEquals(255, b.centreR)
        assertEquals(0, b.centreG)
        assertEquals(0, b.centreB)
    }

    @Test
    fun halfWhiteFrameIsMid() {
        val pixels = IntArray(w * h) { i ->
            if (i < (h / 2) * w) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        val b = sampleFrameBrightness(pixels, w, h)
        // Half the 64 grid samples are white: mean = 32*765 / 192 = 127.
        assertEquals(127, b.mean)
        // The centre pixel sits in the black half.
        assertEquals(0, b.centreR)
        assertEquals(0, b.centreG)
        assertEquals(0, b.centreB)
    }

    @Test
    fun invalidInputIsZero() {
        val b = sampleFrameBrightness(IntArray(0), w, h)
        assertEquals(0, b.mean)
        assertEquals(0, b.centreR)
        val b2 = sampleFrameBrightness(IntArray(16), 0, 0)
        assertEquals(0, b2.mean)
    }
}
