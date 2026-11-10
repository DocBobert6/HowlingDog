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
