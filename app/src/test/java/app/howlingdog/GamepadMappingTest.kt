package app.howlingdog

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for gamepad button remapping: the defaults
 * are exactly today's physical-position mapping, assigning swaps
 * (the mapping stays a permutation), and a remapped button reaches
 * the game. No Android framework calls — the KeyEvent constants
 * are compile-time ints.
 */
class GamepadMappingTest {

    @Test
    fun defaultsAreTodaysPhysicalPositionMapping() {
        // Bottom face = Neo A, right = B, left = C, top = D,
        // Start = Neo Start, Select = Neo Coin.
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, GamepadMapping.DEFAULT.getValue("A"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, GamepadMapping.DEFAULT.getValue("B"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, GamepadMapping.DEFAULT.getValue("C"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, GamepadMapping.DEFAULT.getValue("D"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_START, GamepadMapping.DEFAULT.getValue("Start"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_SELECT, GamepadMapping.DEFAULT.getValue("Coin"))
    }

    @Test
    fun defaultMaskMatchesTheOldHardcodedMapping() {
        // The default mapping must produce exactly the mask the
        // old hardcoded GamepadInput.maskForButton produced.
        assertEquals(InputMask.neoA(), GamepadMapping.maskForKeyCode(KeyEvent.KEYCODE_BUTTON_A, GamepadMapping.DEFAULT))
        assertEquals(InputMask.neoB(), GamepadMapping.maskForKeyCode(KeyEvent.KEYCODE_BUTTON_B, GamepadMapping.DEFAULT))
        assertEquals(InputMask.neoC(), GamepadMapping.maskForKeyCode(KeyEvent.KEYCODE_BUTTON_X, GamepadMapping.DEFAULT))
        assertEquals(InputMask.neoD(), GamepadMapping.maskForKeyCode(KeyEvent.KEYCODE_BUTTON_Y, GamepadMapping.DEFAULT))
        assertEquals(1 shl InputMask.JOY_START, GamepadMapping.maskForKeyCode(KeyEvent.KEYCODE_BUTTON_START, GamepadMapping.DEFAULT))
        assertEquals(1 shl InputMask.JOY_SELECT, GamepadMapping.maskForKeyCode(KeyEvent.KEYCODE_BUTTON_SELECT, GamepadMapping.DEFAULT))
        // A shoulder is not a Neo input.
        assertEquals(0, GamepadMapping.maskForKeyCode(KeyEvent.KEYCODE_BUTTON_L1, GamepadMapping.DEFAULT))
    }

    @Test
    fun assigningAnUnassignedButtonJustMovesIt() {
        // Move Neo A from the bottom face (BUTTON_A) to the top
        // face (BUTTON_Y): D was on BUTTON_Y, so D takes A's old
        // button — a swap, not a duplicate.
        val next = GamepadMapping.assign(GamepadMapping.DEFAULT, "A", KeyEvent.KEYCODE_BUTTON_Y)
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, next.getValue("A"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, next.getValue("D"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, next.getValue("B"))
        // The swapped button now reaches the game as Neo A.
        assertEquals(InputMask.neoA(), GamepadMapping.maskForKeyCode(KeyEvent.KEYCODE_BUTTON_Y, next))
        // And the old button reaches it as Neo D.
        assertEquals(InputMask.neoD(), GamepadMapping.maskForKeyCode(KeyEvent.KEYCODE_BUTTON_A, next))
    }

    @Test
    fun assigningTheSameButtonIsANoOp() {
        val next = GamepadMapping.assign(GamepadMapping.DEFAULT, "A", KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(GamepadMapping.DEFAULT, next)
    }

    @Test
    fun assigningAShoulderOrAnUnknownIdChangesNothing() {
        assertEquals(
            GamepadMapping.DEFAULT,
            GamepadMapping.assign(GamepadMapping.DEFAULT, "A", KeyEvent.KEYCODE_BUTTON_L1),
        )
        assertEquals(
            GamepadMapping.DEFAULT,
            GamepadMapping.assign(GamepadMapping.DEFAULT, "Z", KeyEvent.KEYCODE_BUTTON_Y),
        )
    }

    @Test
    fun theMappingStaysAPermutationAfterSwaps() {
        var mapping = GamepadMapping.DEFAULT
        mapping = GamepadMapping.assign(mapping, "A", KeyEvent.KEYCODE_BUTTON_Y)
        mapping = GamepadMapping.assign(mapping, "Start", KeyEvent.KEYCODE_BUTTON_A)
        mapping = GamepadMapping.assign(mapping, "Coin", KeyEvent.KEYCODE_BUTTON_B)
        // Six Neo inputs, six distinct physical buttons.
        assertEquals(6, mapping.size)
        assertEquals(6, mapping.values.toSet().size)
        assertEquals(GamepadMapping.REMAPPABLE_KEYCODES.toSet(), mapping.values.toSet())
    }

    @Test
    fun isRemappableOnlyForTheSixButtons() {
        for (code in GamepadMapping.REMAPPABLE_KEYCODES) {
            assertTrue(GamepadMapping.isRemappable(code))
        }
        assertFalse(GamepadMapping.isRemappable(KeyEvent.KEYCODE_BUTTON_L1))
        assertFalse(GamepadMapping.isRemappable(KeyEvent.KEYCODE_BUTTON_R2))
        assertFalse(GamepadMapping.isRemappable(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun keyCodeNamesRoundTrip() {
        for (code in GamepadMapping.REMAPPABLE_KEYCODES) {
            assertEquals(code, GamepadMapping.keyCodeForName(GamepadMapping.keyCodeName(code)))
        }
        assertNull(GamepadMapping.keyCodeForName("BUTTON_L1"))
        assertNull(GamepadMapping.keyCodeForName(null))
        assertNull(GamepadMapping.keyCodeForName("nonsense"))
    }

    @Test
    fun displayNamesAreThePadsOwnLabels() {
        assertEquals("A", GamepadMapping.displayName(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("B", GamepadMapping.displayName(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals("X", GamepadMapping.displayName(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals("Y", GamepadMapping.displayName(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals("Start", GamepadMapping.displayName(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals("Select", GamepadMapping.displayName(KeyEvent.KEYCODE_BUTTON_SELECT))
    }

    @Test
    fun bitForNeoCoversAllSixInputs() {
        assertEquals(InputMask.neoA(), GamepadMapping.bitForNeo("A"))
        assertEquals(InputMask.neoB(), GamepadMapping.bitForNeo("B"))
        assertEquals(InputMask.neoC(), GamepadMapping.bitForNeo("C"))
        assertEquals(InputMask.neoD(), GamepadMapping.bitForNeo("D"))
        assertEquals(1 shl InputMask.JOY_START, GamepadMapping.bitForNeo("Start"))
        assertEquals(1 shl InputMask.JOY_SELECT, GamepadMapping.bitForNeo("Coin"))
        assertEquals(0, GamepadMapping.bitForNeo("Z"))
    }
}
