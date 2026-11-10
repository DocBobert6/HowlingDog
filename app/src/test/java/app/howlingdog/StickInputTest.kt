package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the flat-outline stick's direction math:
 * the 8 sectors, the diagonals, the centre dead zone, and the
 * knob clamp. No Android framework calls.
 */
class StickInputTest {

    private val up = 1 shl InputMask.JOY_UP
    private val down = 1 shl InputMask.JOY_DOWN
    private val left = 1 shl InputMask.JOY_LEFT
    private val right = 1 shl InputMask.JOY_RIGHT

    @Test
    fun centredIsNoDirection() {
        assertEquals(0, StickInput.direction(0f, 0f))
    }

    @Test
    fun insideTheDeadZoneIsNoDirection() {
        // 0.21 of full travel in each diagonal: magnitude 0.30,
        // still inside the 0.22 dead zone on each axis alone.
        assertEquals(0, StickInput.direction(0.15f, 0f))
        assertEquals(0, StickInput.direction(0f, -0.15f))
        assertEquals(0, StickInput.direction(0.15f, 0.15f))
    }

    @Test
    fun cardinalsAreSingleDirections() {
        assertEquals(right, StickInput.direction(1f, 0f))
        assertEquals(left, StickInput.direction(-1f, 0f))
        assertEquals(down, StickInput.direction(0f, 1f))
        assertEquals(up, StickInput.direction(0f, -1f))
    }

    @Test
    fun diagonalsAreTwoDirections() {
        assertEquals(right or down, StickInput.direction(0.8f, 0.8f))
        assertEquals(left or down, StickInput.direction(-0.8f, 0.8f))
        assertEquals(left or up, StickInput.direction(-0.8f, -0.8f))
        assertEquals(right or up, StickInput.direction(0.8f, -0.8f))
    }

    /** Knob offset at `degrees` from horizontal (0 = right, 90 = down, y down), magnitude 0.9. */
    private fun at(degrees: Double): Pair<Float, Float> {
        val r = Math.toRadians(degrees)
        return (0.9f * kotlin.math.cos(r)).toFloat() to (0.9f * kotlin.math.sin(r)).toFloat()
    }

    @Test
    fun diagonalsAcrossAWideBand() {
        // Square-gate rule: a diagonal holds from 30 to 60 degrees
        // off horizontal, not just at exactly 45. Each diagonal is
        // tested at all three angles.
        for (deg in listOf(30.0, 45.0, 60.0)) {
            val (x, y) = at(deg)
            assertEquals("down-right at $deg", right or down, StickInput.direction(x, y))
        }
        for (deg in listOf(120.0, 135.0, 150.0)) {
            val (x, y) = at(deg)
            assertEquals("down-left at $deg", left or down, StickInput.direction(x, y))
        }
        for (deg in listOf(210.0, 225.0, 240.0)) {
            val (x, y) = at(deg)
            assertEquals("up-left at $deg", left or up, StickInput.direction(x, y))
        }
        for (deg in listOf(300.0, 315.0, 330.0)) {
            val (x, y) = at(deg)
            assertEquals("up-right at $deg", right or up, StickInput.direction(x, y))
        }
    }

    @Test
    fun mostlyCardinalIsCardinalAlone() {
        // 10 degrees off a cardinal is still that cardinal alone —
        // the wide diagonal band must not swallow near-cardinal pushes.
        val (rx, ry) = at(10.0)
        assertEquals(right, StickInput.direction(rx, ry))
        val (dx, dy) = at(100.0)
        assertEquals(down, StickInput.direction(dx, dy))
        val (lx, ly) = at(170.0)
        assertEquals(left, StickInput.direction(lx, ly))
        val (ux, uy) = at(280.0)
        assertEquals(up, StickInput.direction(ux, uy))
    }

    @Test
    fun justPastADiagonalBoundaryIsDiagonal() {
        // 30 degrees (down-right sector, which starts at 22.5).
        val a = 0.9f
        val dx = (a * 0.866f) // cos 30
        val dy = (a * 0.5f) // sin 30
        assertEquals(right or down, StickInput.direction(dx, dy))
    }

    @Test
    fun justBeforeADiagonalBoundaryIsCardinal() {
        // 15 degrees (right sector, which ends at 22.5).
        val a = 0.9f
        val dx = (a * 0.966f) // cos 15
        val dy = (a * 0.259f) // sin 15
        assertEquals(right, StickInput.direction(dx, dy))
    }

    @Test
    fun clampKeepsTheKnobInsideTheBase() {
        val (x, y) = StickInput.clamp(2f, 0f)
        assertEquals(1f, x, 0.001f)
        assertEquals(0f, y, 0.001f)
        val (x2, y2) = StickInput.clamp(1f, 1f)
        val mag = kotlin.math.sqrt(x2 * x2 + y2 * y2)
        assertEquals(1f, mag, 0.001f)
        assertTrue(x2 > 0f && y2 > 0f)
        // Inside the base, the offset is untouched.
        val (x3, y3) = StickInput.clamp(0.5f, -0.5f)
        assertEquals(0.5f, x3, 0.001f)
        assertEquals(-0.5f, y3, 0.001f)
    }
}
