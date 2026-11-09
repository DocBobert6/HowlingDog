package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM unit test for the player-1 joypad bitmask (InputMask).
 * The core reads the mask through RETRO_DEVICE_ID_JOYPAD_MASK; bit i
 * is (1 << id), with the ids from libretro.h (B=0, Y=1, SELECT=2,
 * START=3, UP=4, DOWN=5, LEFT=6, RIGHT=7, A=8, X=9).
 */
class InputMaskTest {

    @Test
    fun neoButtonsMapToJoypadBits() {
        // Neo A -> joypad B (id 0), Neo B -> joypad A (id 8),
        // Neo C -> joypad Y (id 1), Neo D -> joypad X (id 9).
        assertEquals(1 shl InputMask.JOY_B, InputMask.neoA())
        assertEquals(1 shl InputMask.JOY_A, InputMask.neoB())
        assertEquals(1 shl InputMask.JOY_Y, InputMask.neoC())
        assertEquals(1 shl InputMask.JOY_X, InputMask.neoD())
    }

    @Test
    fun buildCombinesHeldButtons() {
        val mask = InputMask.build(up = true, a = true, start = true, coin = true)
        val expected = (1 shl InputMask.JOY_UP) or
            (1 shl InputMask.JOY_B) or
            (1 shl InputMask.JOY_START) or
            (1 shl InputMask.JOY_SELECT)
        assertEquals(expected, mask)
    }

    @Test
    fun buildWithNothingHeldIsZero() {
        assertEquals(0, InputMask.build())
    }

    @Test
    fun allButtonsAreDistinctBits() {
        val masks = listOf(
            InputMask.build(up = true),
            InputMask.build(down = true),
            InputMask.build(left = true),
            InputMask.build(right = true),
            InputMask.build(a = true),
            InputMask.build(b = true),
            InputMask.build(c = true),
            InputMask.build(d = true),
            InputMask.build(start = true),
            InputMask.build(coin = true),
        )
        // Each single-button mask is exactly one bit, and no two share it.
        var combined = 0
        for (m in masks) {
            assertEquals(1, Integer.bitCount(m))
            assertEquals(0, combined and m)
            combined = combined or m
        }
        assertEquals(10, Integer.bitCount(combined))
    }
}
