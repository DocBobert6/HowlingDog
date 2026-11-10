package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Pure-JVM unit tests for the per-system overlay styling:
 * the theme per mode, the rainbow button colours, the button
 * arrangements, and the cluster geometry. No Android calls.
 */
class OverlayThemeTest {

    @Test
    fun themeForEachMode() {
        assertEquals(OverlayTheme.ButtonLayout.ARC, OverlayTheme.themeFor("aes").layout)
        assertEquals(OverlayTheme.ButtonLayout.BCD_A, OverlayTheme.themeFor("mvs").layout)
        assertEquals(OverlayTheme.ButtonLayout.DIAMOND, OverlayTheme.themeFor("cd").layout)
        assertEquals(OverlayTheme.ButtonLayout.DIAMOND, OverlayTheme.themeFor("cdz").layout)
        // Unknown modes fall back to the home console look.
        assertEquals(OverlayTheme.ButtonLayout.ARC, OverlayTheme.themeFor("").layout)
        assertEquals(OverlayTheme.ButtonLayout.ARC, OverlayTheme.themeFor("atari").layout)
        // Case-insensitive.
        assertEquals(OverlayTheme.ButtonLayout.BCD_A, OverlayTheme.themeFor("MVS").layout)
    }

    @Test
    fun rainbowButtonColours() {
        // The Neo Geo rainbow on every system: A red, B yellow,
        // C green, D blue.
        for (mode in listOf("aes", "mvs", "cd", "cdz")) {
            val styles = OverlayTheme.themeFor(mode).buttonStyles
            assertEquals(0xFFC0392B.toInt(), styles.getValue("A").background)
            assertEquals(0xFFE8C33F.toInt(), styles.getValue("B").background)
            assertEquals(0xFF2E7D32.toInt(), styles.getValue("C").background)
            assertEquals(0xFF2E6FB0.toInt(), styles.getValue("D").background)
        }
        // Labels read on their backgrounds: dark on the yellow,
        // white on the red/green/blue.
        val aes = OverlayTheme.themeFor("aes").buttonStyles
        assertEquals(0xFF311218.toInt(), aes.getValue("B").label)
        assertEquals(0xFFFFFFFF.toInt(), aes.getValue("A").label)
        assertEquals(0xFFFFFFFF.toInt(), aes.getValue("C").label)
        assertEquals(0xFFFFFFFF.toInt(), aes.getValue("D").label)
    }

    @Test
    fun stickColoursDifferPerSystem() {
        // AES/MVS: the red ball-top on the maroon stick.
        val aes = OverlayTheme.themeFor("aes")
        assertEquals(0xFF552732.toInt(), aes.stickBaseTop)
        assertEquals(0xFFA83A2A.toInt(), aes.stickNubTop)
        assertEquals(aes.stickBaseTop, OverlayTheme.themeFor("mvs").stickBaseTop)
        assertEquals(aes.stickNubTop, OverlayTheme.themeFor("mvs").stickNubTop)
        // CD/CDZ: the grey pad shell, not maroon.
        val cd = OverlayTheme.themeFor("cd")
        assertEquals(0xFF6E7378.toInt(), cd.stickBaseTop)
        assertEquals(0xFF8A9096.toInt(), cd.stickNubTop)
        assertEquals(cd.stickBaseTop, OverlayTheme.themeFor("cdz").stickBaseTop)
        // The gold hairline is the same on every system.
        for (mode in listOf("aes", "mvs", "cd", "cdz")) {
            assertEquals(EraUi.Palette.GOLD, OverlayTheme.themeFor(mode).accent)
        }
    }

    @Test
    fun gridOffsetsMatchTheCurrentCluster() {
        // The 2x2 grid is the layout the cluster already has:
        // A/B centres 28dp, C/D 92dp, Coin/Start 156dp in a
        // 120x180dp box (centre 60/90).
        val grid = OverlayTheme.buttonOffsets(OverlayTheme.ButtonLayout.GRID)
        assertEquals(-32f to -62f, grid.getValue("A"))
        assertEquals(32f to -62f, grid.getValue("B"))
        assertEquals(-32f to 2f, grid.getValue("C"))
        assertEquals(32f to 2f, grid.getValue("D"))
        assertEquals(-32f to 66f, grid.getValue("Coin"))
        assertEquals(32f to 66f, grid.getValue("Start"))
    }

    @Test
    fun arcIsACurvedRowMiddleHigher() {
        val arc = OverlayTheme.buttonOffsets(OverlayTheme.ButtonLayout.ARC)
        // Four in a row, left to right, middle higher than the ends.
        assertTrue(arc.getValue("A").first < arc.getValue("B").first)
        assertTrue(arc.getValue("B").first < arc.getValue("C").first)
        assertTrue(arc.getValue("C").first < arc.getValue("D").first)
        assertEquals(arc.getValue("A").second, arc.getValue("D").second, 0.001f)
        assertTrue(arc.getValue("B").second < arc.getValue("A").second)
        assertTrue(arc.getValue("C").second < arc.getValue("A").second)
    }

    @Test
    fun mvsPutsAInTheThumbPosition() {
        val mvs = OverlayTheme.buttonOffsets(OverlayTheme.ButtonLayout.BCD_A)
        // B C D share a row above; A sits below B (same x, lower).
        assertEquals(mvs.getValue("B").second, mvs.getValue("C").second, 0.001f)
        assertEquals(mvs.getValue("C").second, mvs.getValue("D").second, 0.001f)
        assertEquals(mvs.getValue("A").first, mvs.getValue("B").first, 0.001f)
        assertTrue(mvs.getValue("A").second > mvs.getValue("B").second)
    }

    @Test
    fun diamondPutsAAtTheBottomAndCAtTheTop() {
        val diamond = OverlayTheme.buttonOffsets(OverlayTheme.ButtonLayout.DIAMOND)
        // C top, A bottom, B left, D right.
        assertTrue(diamond.getValue("C").second < diamond.getValue("B").second)
        assertTrue(diamond.getValue("B").second < diamond.getValue("A").second)
        assertEquals(diamond.getValue("B").second, diamond.getValue("D").second, 0.001f)
        assertTrue(diamond.getValue("B").first < diamond.getValue("A").first)
        assertTrue(diamond.getValue("D").first > diamond.getValue("A").first)
        assertEquals(diamond.getValue("A").first, diamond.getValue("C").first, 0.001f)
    }

    @Test
    fun clusterSizes() {
        assertEquals(120f to 180f, OverlayTheme.clusterSize(OverlayTheme.ButtonLayout.GRID))
        assertEquals(248f to 128f, OverlayTheme.clusterSize(OverlayTheme.ButtonLayout.ARC))
        assertEquals(184f to 180f, OverlayTheme.clusterSize(OverlayTheme.ButtonLayout.BCD_A))
        assertEquals(152f to 212f, OverlayTheme.clusterSize(OverlayTheme.ButtonLayout.DIAMOND))
    }

    @Test
    fun buttonsNeverOverlapInAnyLayout() {
        // The smallest centre distance between two buttons must
        // hold both: a main button (56dp) next to a small one
        // (48dp) needs at least (56+48)/2 = 52dp.
        for (layout in OverlayTheme.ButtonLayout.entries) {
            val offsets = OverlayTheme.buttonOffsets(layout)
            val ids = offsets.keys.toList()
            var min = Float.MAX_VALUE
            for (i in ids.indices) {
                for (j in i + 1 until ids.size) {
                    val a = offsets.getValue(ids[i])
                    val b = offsets.getValue(ids[j])
                    val dx = a.first - b.first
                    val dy = a.second - b.second
                    min = minOf(min, sqrt(dx * dx + dy * dy))
                }
            }
            assertTrue("$layout min centre distance $min", min >= 52f)
        }
    }

    @Test
    fun clusterIsCentredOnTheOrigin() {
        // Every layout's bounding box is centred on (0, 0), so the
        // cluster position is its centre in every system.
        for (layout in OverlayTheme.ButtonLayout.entries) {
            val offsets = OverlayTheme.buttonOffsets(layout)
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for ((id, off) in offsets) {
                val half = OverlayTheme.buttonSize(id) / 2f
                minX = minOf(minX, off.first - half)
                maxX = maxOf(maxX, off.first + half)
                minY = minOf(minY, off.second - half)
                maxY = maxOf(maxY, off.second + half)
            }
            assertEquals("$layout centre x", 0f, (minX + maxX) / 2f, 0.001f)
            assertEquals("$layout centre y", 0f, (minY + maxY) / 2f, 0.001f)
        }
    }
}
