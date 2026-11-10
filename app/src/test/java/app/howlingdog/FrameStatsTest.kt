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

    @Test
    fun diagTextRendersSrcProbeLine() {
        val s = FrameStats(
            frames = 179, drawn = 179, lastW = 304, lastH = 224,
            avW = 304, avH = 224, fps = 59.6,
            brightMean = 0, centreR = 0, centreG = 0, centreB = 0,
            srcCalls = 179, srcNonzero = 0,
            srcFirst = 0, srcFirst100 = 0, srcFirst1000 = 0,
            srcW = 304, srcH = 224, srcPitch = 1280,
        )
        assertEquals(
            "DIAG frames=179 drawn=179 last=304x224\n" +
                "AV 304x224 @ 59.6fps\n" +
                "bright mean=0 centre=(0,0,0)\n" +
                "SRC nonzero=0 first=0x00000000 100=0x00000000 " +
                "1000=0x00000000 304x224 pitch=1280",
            formatDiagText(s),
        )
    }

    @Test
    fun diagTextShowsNonZeroSourceAndError() {
        // A raw XRGB word with the top bit set is a negative Int;
        // the SRC line must still show its 8 hex digits.
        val s = FrameStats(
            frames = 890, drawn = 890, lastW = 304, lastH = 224,
            avW = 304, avH = 224, fps = 59.6,
            brightMean = 0, centreR = 0, centreG = 0, centreB = 0,
            srcCalls = 890, srcNonzero = 68096,
            srcFirst = 0xFF123456.toInt(), srcFirst100 = 0,
            srcFirst1000 = 0x00ABCDEF,
            srcW = 304, srcH = 224, srcPitch = 1280,
            lastError = "Failed to load bios",
        )
        assertEquals(
            "DIAG frames=890 drawn=890 last=304x224\n" +
                "AV 304x224 @ 59.6fps\n" +
                "bright mean=0 centre=(0,0,0)\n" +
                "SRC nonzero=68096 first=0xFF123456 100=0x00000000 " +
                "1000=0x00ABCDEF 304x224 pitch=1280\n" +
                "ERR Failed to load bios",
            formatDiagText(s),
        )
    }

    @Test
    fun diagTextShowsPalLine() {
        // TEMPORARY DIAGNOSTIC (no-video bug): palette snapshot with
        // non-zero writes, a non-zero palette, and the PC in game ROM.
        val s = FrameStats(
            frames = 239, drawn = 239, lastW = 304, lastH = 224,
            avW = 304, avH = 224, fps = 59.6,
            brightMean = 0, centreR = 0, centreG = 0, centreB = 0,
            srcCalls = 239, srcNonzero = 68096,
            srcFirst = 0xFF000000.toInt(),
            srcFirst100 = 0xFF000000.toInt(),
            srcFirst1000 = 0xFF000000.toInt(),
            srcW = 304, srcH = 224, srcPitch = 1280,
            palWrites = 12345, vramWrites = 67890,
            palBank = 1, palPc = 0x001234,
            pal = intArrayOf(
                0x0000, 0x7FFF, 0x1234, 0x0000,
                0x0000, 0x0000, 0x0000, 0x0000,
            ),
        )
        assertEquals(
            "DIAG frames=239 drawn=239 last=304x224\n" +
                "AV 304x224 @ 59.6fps\n" +
                "bright mean=0 centre=(0,0,0)\n" +
                "SRC nonzero=68096 first=0xFF000000 100=0xFF000000 " +
                "1000=0xFF000000 304x224 pitch=1280\n" +
                "PAL writes=12345 VRAM writes=67890 " +
                "pal[0..7]=0x0000,0x7FFF,0x1234,0x0000,0x0000,0x0000,0x0000,0x0000 " +
                "bank=1 PC=0x001234",
            formatDiagText(s),
        )
    }

    @Test
    fun diagTextShowsPalLineWithZeroWrites() {
        // TEMPORARY DIAGNOSTIC (no-video bug): no palette/VRAM writes
        // at all, palette all zero, PC in BIOS code — the black-screen
        // case the probe is trying to separate.
        val s = FrameStats(
            frames = 60, drawn = 60, lastW = 304, lastH = 224,
            avW = 304, avH = 224, fps = 59.6,
            brightMean = 0, centreR = 0, centreG = 0, centreB = 0,
            srcCalls = 60, srcNonzero = 68096,
            srcFirst = 0xFF000000.toInt(),
            srcFirst100 = 0xFF000000.toInt(),
            srcFirst1000 = 0xFF000000.toInt(),
            srcW = 304, srcH = 224, srcPitch = 1280,
            palWrites = 0, vramWrites = 0,
            palBank = 0, palPc = 0xC00000,
            pal = IntArray(8),
        )
        assertEquals(
            "DIAG frames=60 drawn=60 last=304x224\n" +
                "AV 304x224 @ 59.6fps\n" +
                "bright mean=0 centre=(0,0,0)\n" +
                "SRC nonzero=68096 first=0xFF000000 100=0xFF000000 " +
                "1000=0xFF000000 304x224 pitch=1280\n" +
                "PAL writes=0 VRAM writes=0 " +
                "pal[0..7]=0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000 " +
                "bank=0 PC=0xC00000",
            formatDiagText(s),
        )
    }
}
