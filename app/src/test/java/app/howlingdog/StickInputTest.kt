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
        // Square-gate rule, Phase 6 ratio 0.35: a diagonal holds
        // from about 19 to 71 degrees off horizontal, not just at
        // exactly 45. Each diagonal is tested at five angles.
        for (deg in listOf(20.0, 30.0, 45.0, 60.0, 70.0)) {
            val (x, y) = at(deg)
            assertEquals("down-right at $deg", right or down, StickInput.direction(x, y))
        }
        for (deg in listOf(110.0, 120.0, 135.0, 150.0, 160.0)) {
            val (x, y) = at(deg)
            assertEquals("down-left at $deg", left or down, StickInput.direction(x, y))
        }
        for (deg in listOf(200.0, 210.0, 225.0, 240.0, 250.0)) {
            val (x, y) = at(deg)
            assertEquals("up-left at $deg", left or up, StickInput.direction(x, y))
        }
        for (deg in listOf(290.0, 300.0, 315.0, 330.0, 340.0)) {
            val (x, y) = at(deg)
            assertEquals("up-right at $deg", right or up, StickInput.direction(x, y))
        }
    }

    @Test
    fun mostlyCardinalIsCardinalAlone() {
        // 10 and 15 degrees off a cardinal is still that cardinal
        // alone — the wide diagonal band must not swallow
        // near-cardinal pushes. The diagonal boundary is at
        // about 19.3 degrees (arctan 0.35).
        for ((deg, want) in listOf(
            10.0 to right, 15.0 to right,
            75.0 to down, 80.0 to down, 100.0 to down, 105.0 to down,
            165.0 to left, 170.0 to left,
            195.0 to left, 255.0 to up, 260.0 to up,
            280.0 to up, 285.0 to up, 345.0 to right, 350.0 to right,
        )) {
            val (x, y) = at(deg)
            assertEquals("cardinal at $deg", want, StickInput.direction(x, y))
        }
    }

    @Test
    fun justPastADiagonalBoundaryIsDiagonal() {
        // 20 degrees: just past the diagonal boundary (~19.3).
        val a = 0.9f
        val dx = (a * 0.940f) // cos 20
        val dy = (a * 0.342f) // sin 20
        assertEquals(right or down, StickInput.direction(dx, dy))
    }

    @Test
    fun justBeforeADiagonalBoundaryIsCardinal() {
        // 15 degrees: just before the diagonal boundary (~19.3).
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
