package app.howlingdog

import java.io.File
import java.util.Locale

/**
 * 2.0, feature 3 — the virtual Neo Geo memory card manager.
 * Pure Kotlin (no Android), unit-tested on the JVM
 * (MemoryCardTest).
 *
 * The core writes a game's battery / memory-card saves to the
 * save directory (filesDir/saves/) as `<mainFile>.mcr` (the Neo
 * Geo memory card, 2K), `<mainFile>.nv` (NVRAM), `<mainFile>.srm`
 * (save RAM), and — for CD games — `<mainFile>.brm` (CD backup
 * RAM, 8K). The core loads them on game load and writes them
 * back on unload, and flushes them on every pause. Save states
 * (`<mainFile>.state*`), RA sidecars (`.ra`) and thumbnails
 * (`.png`) live in the same folder but are NOT memory-card saves
 * and are never listed here.
 *
 * [listEntries] reads the actual files in the saves directory —
 * nothing is invented. A game is matched by its main file name;
 * a save whose file belongs to no game in the library (a game
 * removed since it last saved) is listed under its own file name
 * as an unknown game, so it can still be deleted.
 */
object MemoryCard {

    /** The memory-card / battery-save extensions the core writes. */
    val SAVE_EXTENSIONS: List<String> = listOf(".mcr", ".nv", ".srm", ".brm")

    /** One save file on the card. */
    data class SaveFile(
        val name: String,
        val sizeBytes: Long,
        val lastModifiedMillis: Long,
    )

    /** One game and the saves it holds on the card. */
    data class Entry(
        val mainFile: String,
        val displayName: String,
        val files: List<SaveFile>,
    ) {
        /** The game's total on the card, in bytes. */
        val totalBytes: Long get() = files.sumOf { it.sizeBytes }
    }

    /**
     * True when this file name is a memory-card save (not a save
     * state, an RA sidecar, or a thumbnail).
     */
    fun isSaveFile(name: String): Boolean =
        SAVE_EXTENSIONS.any { name.endsWith(it) }

    /**
     * The game a save file belongs to: the file name minus its
     * save extension, or null when it is not a save file.
     */
    fun mainFileFor(name: String): String? {
        val ext = SAVE_EXTENSIONS.firstOrNull { name.endsWith(it) } ?: return null
        return name.dropLast(ext.length).takeIf { it.isNotEmpty() }
    }

    /**
     * The saves on the card, grouped by game. Games in the
     * library come first, in library order, then saves for games
     * no longer in the library, by file name. Files within a game
     * are in name order. A missing or unreadable saves directory
     * lists nothing.
     */
    fun listEntries(savesDir: File, games: List<GameEntry>): List<Entry> {
        val files = try {
            savesDir.listFiles()?.filter { it.isFile && isSaveFile(it.name) } ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        val byGame = files.groupBy { mainFileFor(it.name)!! }
        val known = games.associateBy { it.mainFile }
        val entries = byGame.map { (mainFile, gameFiles) ->
            val game = known[mainFile]
            Entry(
                mainFile = mainFile,
                displayName = game?.effectiveName ?: mainFile,
                files = gameFiles
                    .map { SaveFile(it.name, it.length(), it.lastModified()) }
                    .sortedBy { it.name },
            )
        }
        val libraryOrder = games.map { it.mainFile }
        return entries.sortedWith(
            compareBy<Entry> { entry ->
                val index = libraryOrder.indexOf(entry.mainFile)
                if (index >= 0) index else Int.MAX_VALUE
            }.thenBy { it.mainFile },
        )
    }

    /**
     * Delete one save file by name. Returns false when it does
     * not exist, is not a save file, or cannot be deleted.
     */
    fun deleteFile(savesDir: File, name: String): Boolean {
        if (!isSaveFile(name)) return false
        val file = File(savesDir, name)
        return try {
            file.isFile && file.delete()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Delete every save file for one game. Returns the number of
     * files actually deleted.
     */
    fun deleteGame(savesDir: File, mainFile: String): Int {
        var deleted = 0
        for (ext in SAVE_EXTENSIONS) {
            if (deleteFile(savesDir, mainFile + ext)) deleted++
        }
        return deleted
    }

    /** A byte count as a short human size ("2 KB", "64 KB", "1.5 MB", "512 B"). */
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = "KB"
        for (u in units) {
            value /= 1024.0
            unit = u
            if (value < 1024.0) break
        }
        val text = if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
        return "$text $unit"
    }
}
