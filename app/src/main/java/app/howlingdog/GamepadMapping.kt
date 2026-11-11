package app.howlingdog

import android.view.KeyEvent

/**
 * Gamepad button remapping (v1.3.0, part 1): which physical
 * gamepad button drives each Neo Geo input.
 *
 * The six Neo Geo inputs (A, B, C, D, Start, Coin) are remapped as
 * a PERMUTATION of the six physical gamepad buttons that are Neo
 * inputs today — the face buttons and the menu buttons. Each Neo
 * input gets exactly one physical button and each physical button
 * maps to exactly one Neo input, so every input stays reachable
 * and there are never two inputs on one button. Assigning a button
 * that is already assigned to another input SWAPS the two.
 *
 * The default is exactly today's mapping, so nothing changes for
 * a user who never opens the remapping screen: bottom face = Neo A,
 * right face = Neo B, left face = Neo C, top face = Neo D, Start =
 * Neo Start, Select = Neo Coin. (The joypad bits follow the core's
 * input descriptors — Neo A is joypad B, Neo B is joypad A, Neo C
 * is joypad Y, Neo D is joypad X; see InputMask.)
 *
 * NOT remappable, by design: the shoulder buttons (L1/R1/L2/R2)
 * stay as quick save (L1+R1) and quick load (L2+R2) — if a Neo
 * input could be assigned to a shoulder, pressing it would both
 * fire the input and start a quick-save combo. The D-pad and the
 * left stick stay as directions. The pause combo (START+SELECT)
 * follows the remapped Start and Coin buttons: pause is the two
 * physical buttons assigned to Neo Start and Neo Coin, held
 * together.
 *
 * Pure Kotlin (the KeyEvent constants are compile-time ints), so
 * it is unit-tested on the JVM (GamepadMappingTest).
 */
object GamepadMapping {

    /** The six Neo Geo inputs, in the order the remap screen lists them. */
    val NEO_IDS: List<String> = listOf("A", "B", "C", "D", "Start", "Coin")

    /** The six physical buttons that can be assigned, by Android keycode. */
    val REMAPPABLE_KEYCODES: List<Int> = listOf(
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT,
    )

    /** The default mapping: Neo id -> physical keycode. */
    val DEFAULT: Map<String, Int> = mapOf(
        "A" to KeyEvent.KEYCODE_BUTTON_A,
        "B" to KeyEvent.KEYCODE_BUTTON_B,
        "C" to KeyEvent.KEYCODE_BUTTON_X,
        "D" to KeyEvent.KEYCODE_BUTTON_Y,
        "Start" to KeyEvent.KEYCODE_BUTTON_START,
        "Coin" to KeyEvent.KEYCODE_BUTTON_SELECT,
    )

    /** The physical button's own label, as printed on the pad. */
    fun displayName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> "A"
        KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_BUTTON_START -> "Start"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
        else -> "Button $keyCode"
    }

    /** The KeyEvent constant's name, for storage (human-readable). */
    fun keyCodeName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> "BUTTON_A"
        KeyEvent.KEYCODE_BUTTON_B -> "BUTTON_B"
        KeyEvent.KEYCODE_BUTTON_X -> "BUTTON_X"
        KeyEvent.KEYCODE_BUTTON_Y -> "BUTTON_Y"
        KeyEvent.KEYCODE_BUTTON_START -> "BUTTON_START"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "BUTTON_SELECT"
        else -> "BUTTON_$keyCode"
    }

    /** The keycode for a stored name, or null when it is not one of the six. */
    fun keyCodeForName(name: String?): Int? = when (name) {
        "BUTTON_A" -> KeyEvent.KEYCODE_BUTTON_A
        "BUTTON_B" -> KeyEvent.KEYCODE_BUTTON_B
        "BUTTON_X" -> KeyEvent.KEYCODE_BUTTON_X
        "BUTTON_Y" -> KeyEvent.KEYCODE_BUTTON_Y
        "BUTTON_START" -> KeyEvent.KEYCODE_BUTTON_START
        "BUTTON_SELECT" -> KeyEvent.KEYCODE_BUTTON_SELECT
        else -> null
    }

    /** True when this physical button is one of the six remappable ones. */
    fun isRemappable(keyCode: Int): Boolean = keyCode in REMAPPABLE_KEYCODES

    /** The joypad mask bit for a Neo input id, or 0 for an unknown id. */
    fun bitForNeo(neoId: String): Int = when (neoId) {
        "A" -> InputMask.neoA()
        "B" -> InputMask.neoB()
        "C" -> InputMask.neoC()
        "D" -> InputMask.neoD()
        "Start" -> 1 shl InputMask.JOY_START
        "Coin" -> 1 shl InputMask.JOY_SELECT
        else -> 0
    }

    /** The physical keycode assigned to a Neo input, or the default when it is not in the map, or 0 for an unknown id. */
    fun keyCodeFor(neoId: String, mapping: Map<String, Int>): Int =
        mapping[neoId] ?: DEFAULT[neoId] ?: 0

    /** The Neo input a physical keycode is assigned to, or null when it is not assigned. */
    fun neoForKeyCode(keyCode: Int, mapping: Map<String, Int>): String? =
        mapping.entries.firstOrNull { it.value == keyCode }?.key

    /** The joypad mask for a physical keycode under this mapping, or 0 when it is not a Neo input. */
    fun maskForKeyCode(keyCode: Int, mapping: Map<String, Int>): Int =
        neoForKeyCode(keyCode, mapping)?.let { bitForNeo(it) } ?: 0

    /**
     * Assign [keyCode] to [neoId], returning the new mapping. When
     * the button is already assigned to another input, the two
     * swap, so the mapping stays a permutation. An unknown Neo id
     * or a non-remappable button leaves the mapping unchanged.
     */
    fun assign(
        mapping: Map<String, Int>,
        neoId: String,
        keyCode: Int,
    ): Map<String, Int> {
        if (neoId !in NEO_IDS || !isRemappable(keyCode)) return mapping
        val current = keyCodeFor(neoId, mapping)
        if (current == keyCode) return mapping
        val other = neoForKeyCode(keyCode, mapping)
        val next = mapping.toMutableMap()
        next[neoId] = keyCode
        if (other != null) next[other] = current
        return next
    }
}
