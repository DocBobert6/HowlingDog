package app.howlingdog

import java.io.File

/**
 * Quick save/load: the per-game state slot and the no-save check.
 * Pure Kotlin (no Android), unit-tested on the JVM (QuickSaveTest).
 *
 * The quick path uses the SAME slot the pause menu uses — one state
 * file per game, in the saves folder — so the two paths always see
 * the same state. There is no second slot. A quick load with no
 * save file does nothing ([loadAvailable] is false), so the game is
 * never paused and no state changes.
 */
object QuickSave {

    /** The state file for a game, given the saves dir and the game's main file name. */
    fun stateFile(savesDir: File, mainFile: String): File =
        File(savesDir, "$mainFile.state")

    /** True when a quick load can proceed: a save file exists for this game. */
    fun loadAvailable(stateFile: File): Boolean = stateFile.isFile
}
