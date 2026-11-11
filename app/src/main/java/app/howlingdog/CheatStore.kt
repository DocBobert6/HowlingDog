package app.howlingdog

import java.io.File

/**
 * v1.5, feature 4 — one stored cheat: the code, the name the
 * user gave it, and whether it is enabled. Enabled codes are
 * written to the game's RAM after every frame; disabled ones
 * are kept but do nothing.
 */
data class StoredCheat(
    val code: CheatCode,
    val name: String,
    val enabled: Boolean,
)

/**
 * The per-game cheat store: one file per game in [cheatsDir],
 * `<mainFile>.txt`, one `code|name|enabled` line per code.
 * Plain file IO, no Android — unit-tested on the plain JVM
 * (CheatStoreTest). No bundled cheat database: the user types
 * codes in, and this is where they persist, per game.
 */
class CheatStore(private val cheatsDir: File) {

    /** The store file for a game. The main file name is already sanitized by the library, so it is a safe file name. */
    fun storeFile(mainFile: String): File = File(cheatsDir, "$mainFile.txt")

    /** The codes for a game, in the order they were added. A missing file means no codes; a malformed line is skipped. */
    fun load(mainFile: String): List<StoredCheat> {
        val file = storeFile(mainFile)
        if (!file.isFile) return emptyList()
        val out = ArrayList<StoredCheat>()
        try {
            for (line in file.readLines()) {
                if (line.isBlank()) continue
                val parts = line.split('|')
                if (parts.size != 3) continue
                val code = CheatCode.parse(parts[0]) ?: continue
                val name = parts[1].trim().takeIf { it.isNotEmpty() } ?: code.format()
                val enabled = parts[2].trim() == "1"
                out.add(StoredCheat(code, name, enabled))
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return out
    }

    /** Write a game's codes back, in order. Best effort, like the library store: a failed save degrades to "not remembered" rather than a crash. */
    fun save(mainFile: String, cheats: List<StoredCheat>) {
        try {
            val file = storeFile(mainFile)
            file.parentFile?.mkdirs()
            val text = cheats.joinToString("\n") { stored ->
                val safeName = stored.name.replace('|', ' ').replace('\n', ' ').replace('\r', ' ')
                    .trim().take(60).ifEmpty { stored.code.format() }
                "${stored.code.format()}|$safeName|${if (stored.enabled) "1" else "0"}"
            }
            file.writeText(if (text.isEmpty()) "" else text + "\n")
        } catch (_: Exception) {
            // The in-memory list is still shown; the next load
            // re-reads disk, so a failed save degrades to "not
            // remembered" rather than a crash.
        }
    }
}
