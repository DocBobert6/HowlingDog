package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM unit tests for the 8-way D-pad's direction math:
 * the diagonal zones report both directions (never a four-way
 * pad), the edges report one direction, and the centre is dead.
 * No Android framework calls.
 */
class DpadInputTest {

    private val up = 1 shl InputMask.JOY_UP
    private val down = 1 shl InputMask.JOY_DOWN
    private val left = 1 shl InputMask.JOY_LEFT
    private val right = 1 shl InputMask.JOY_RIGHT
    private val size = 400f // a 400px pad

    @Test
    fun centrePressIsNoDirection() {
        assertEquals(0, DpadInput.directionFromPress(200f, 200f, size))
    }

    @Test
    fun justInsideTheDeadZoneIsNoDirection() {
        // 0.11 of full travel right of centre: inside the 0.22
        // dead zone, so a resting thumb does nothing.
        assertEquals(0, DpadInput.directionFromPress(220f, 200f, size))
        assertEquals(0, DpadInput.directionFromPress(200f, 180f, size))
    }

    @Test
    fun edgeCentresAreSingleDirections() {
        assertEquals(right, DpadInput.directionFromPress(380f, 200f, size))
        assertEquals(left, DpadInput.directionFromPress(20f, 200f, size))
        assertEquals(down, DpadInput.directionFromPress(200f, 380f, size))
        assertEquals(up, DpadInput.directionFromPress(200f, 20f, size))
    }

    @Test
    fun cornerZonesAreDiagonals() {
        // The diagonal zones: pressing a corner reports BOTH
        // directions. This is what makes it an 8-way pad, not
        // the old four-way one.
        assertEquals(right or up, DpadInput.directionFromPress(340f, 60f, size))
        assertEquals(left or up, DpadInput.directionFromPress(60f, 60f, size))
        assertEquals(right or down, DpadInput.directionFromPress(340f, 340f, size))
        assertEquals(left or down, DpadInput.directionFromPress(60f, 340f, size))
    }

    @Test
    fun shallowDiagonalIsStillDiagonal() {
        // Mostly right but clearly up too (about 20 degrees off
        // horizontal): the 0.35 band keeps it a diagonal.
        assertEquals(right or up, DpadInput.directionFromPress(360f, 140f, size))
    }

    @Test
    fun nearCardinalStaysCardinalAlone() {
        // Almost straight right, barely up (about 6 degrees off):
        // the wide diagonal band must not swallow near-cardinal
        // presses.
        assertEquals(right, DpadInput.directionFromPress(360f, 180f, size))
        assertEquals(up, DpadInput.directionFromPress(220f, 20f, size))
    }

    @Test
    fun zeroOrNegativeSizeIsNoDirection() {
        assertEquals(0, DpadInput.directionFromPress(200f, 200f, 0f))
        assertEquals(0, DpadInput.directionFromPress(200f, 200f, -10f))
    }

    @Test
    fun pressOutsideThePadStillReportsTheEdgeDirection() {
        // A drag that slides off the pad's edge keeps its
        // direction (the offset clamps to full deflection):
        // this is what lets the stick/D-pad hold a drag past
        // the touch bounds instead of dropping it on Exit.
        // size is 400, so the pad spans 0..400; 700 is well
        // past the right edge, and -300 well past the left.
        assertEquals(right, DpadInput.directionFromPress(700f, 200f, size))
        assertEquals(left, DpadInput.directionFromPress(-300f, 200f, size))
        assertEquals(up, DpadInput.directionFromPress(200f, -300f, size))
        assertEquals(down, DpadInput.directionFromPress(200f, 700f, size))
        assertEquals(right or up, DpadInput.directionFromPress(700f, -300f, size))
    }
}
