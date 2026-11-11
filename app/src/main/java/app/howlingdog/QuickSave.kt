package app.howlingdog

import java.io.File

/**
 * Save-state slots: three slots per game, in the saves folder.
 * Pure Kotlin (no Android), unit-tested on the JVM (QuickSaveTest).
 *
 * Filename scheme: slot 1 is the legacy file, `<mainFile>.state`,
 * so saves made before slots existed keep working untouched; slots
 * 2 and 3 append the slot number, `<mainFile>.state2` and
 * `<mainFile>.state3`.
 *
 * The quick path (the SAVE/LOAD buttons in the title row and the
 * L1+R1 / L2+R2 gamepad combos) always uses slot 1, the same file
 * the pause menu used before slots existed. The pause menu lets the
 * user pick a slot first, then Save / Load acts on that slot.
 * A quick load with no save file does nothing ([loadAvailable] is
 * false), so the game is never paused and no state changes.
 */
object QuickSave {

    /** How many save-state slots each game has. */
    const val SLOT_COUNT = 3

    /** The slot the quick save/load path always uses. */
    const val QUICK_SLOT = 1

    /**
     * The state file for a game and slot, given the saves dir and
     * the game's main file name. Slot 1 is the legacy
     * `<mainFile>.state` file; slots 2 and 3 are
     * `<mainFile>.state2` / `<mainFile>.state3`.
     */
    fun stateFile(savesDir: File, mainFile: String, slot: Int = QUICK_SLOT): File {
        require(slot in 1..SLOT_COUNT) { "slot must be 1..$SLOT_COUNT, was $slot" }
        return if (slot == QUICK_SLOT) {
            File(savesDir, "$mainFile.state")
        } else {
            File(savesDir, "$mainFile.state$slot")
        }
    }

    /** True when a quick load can proceed: a save file exists for this game. */
    fun loadAvailable(stateFile: File): Boolean = stateFile.isFile

    /** True when the given slot holds a save for this game. */
    fun slotOccupied(savesDir: File, mainFile: String, slot: Int): Boolean =
        loadAvailable(stateFile(savesDir, mainFile, slot))

    /** The slots that hold a save for this game, in slot order. */
    fun occupiedSlots(savesDir: File, mainFile: String): List<Int> =
        (1..SLOT_COUNT).filter { slotOccupied(savesDir, mainFile, it) }
}
