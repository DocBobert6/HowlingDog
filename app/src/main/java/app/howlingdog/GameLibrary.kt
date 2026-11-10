package app.howlingdog

import java.io.File

/**
 * Phase 3 — the game library. Plain file-based store, no database:
 * the games live in [gamesDir] (app-private files, not cache — the
 * system may clear cacheDir, and the library must survive), and the
 * index lives in [storeFile], one game per line:
 *
 *   mainFile | displayName | addedAtMillis | file1,file2,...
 *
 * Filenames are sanitized (see [sanitize]: letters, digits, '.', '_',
 * '-' only), so '|' and ',' can never appear in a stored name and the
 * format needs no escaping; the reader still skips malformed lines.
 *
 * A game is a game: the library never shows or sorts by media type.
 * The kind is derived from the main file's extension only where the
 * app must know it internally (which system files a game needs to
 * boot). Code may say cd; the UI never does.
 *
 * BIOS files live in [biosDir] (the spike's filesDir/bios/). The
 * library only reads them; picking/copying stays in the UI (SAF).
 * The four files, by role (code names; the UI shows the role names,
 * never the raw zip names for the two 1990s systems):
 * - aes.zip     -> "Home console"  (the default system for carts)
 * - neogeo.zip  -> "Arcade"
 * - neocd.zip   -> "1994 system"   (the 1994 disc system)
 * - neocdz.zip  -> "1995 system"   (the 1995 disc system)
 */
data class GameEntry(
    /** Stored main file name, e.g. "neobombe.neo" or "game.cue". */
    val mainFile: String,
    /** Display name, derived from the file name (no invented titles). */
    val displayName: String,
    /** Every stored file belonging to this game (cue + its data files). */
    val files: List<String>,
    /** Epoch millis the game was (first) added; stable sort tiebreak. */
    val addedAt: Long,
) {
    /** The file the core loads: the main file's path under [gamesDir]. */
    fun mainPath(gamesDir: File): File = File(gamesDir, mainFile)

    /** True when the main file is a cue sheet (its data files travel with it). */
    val isCue: Boolean get() = mainFile.lowercase().endsWith(".cue")
}

data class BiosStatus(
    val home: Boolean, // aes.zip
    val arcade: Boolean, // neogeo.zip
    val system1994: Boolean, // neocd.zip
    val system1995: Boolean, // neocdz.zip
) {
    val readyCount: Int get() = listOf(home, arcade, system1994, system1995).count { it }
    val totalCount: Int get() = 4
}

/** File-name sanitizer (moved from the Phase 2 spike UI, unchanged). */
fun sanitize(name: String): String {
    return name.map { c ->
        if (c.isLetterOrDigit() || c in "._-") c else '_'
    }.joinToString("").take(120).ifBlank { "game.neo" }
}

/** Display name for a stored game file: stem with separators as spaces. */
fun displayNameFor(mainFile: String): String {
    val stem = mainFile.substringBeforeLast('.', mainFile)
    return stem.replace('_', ' ').replace('-', ' ')
        .split(' ').filter { it.isNotEmpty() }.joinToString(" ").trim()
        .ifBlank { mainFile }
}

/**
 * The data-file names a cue sheet references, in order, deduplicated.
 * A FILE line is `FILE "name.bin" BINARY` or `FILE name.bin BINARY`;
 * the name may carry a directory part, which is dropped (the files
 * travel next to the cue). Keyword match is case-insensitive; a cue
 * with no FILE lines yields an empty list.
 */
fun parseCueBinFiles(cueText: String): List<String> {
    val pattern = Regex("""(?im)^\s*FILE\s+(?:"([^"]+)"|(\S+))""")
    val seen = LinkedHashSet<String>()
    for (m in pattern.findAll(cueText)) {
        val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        if (base.isNotEmpty()) seen.add(base)
    }
    return seen.toList()
}

class GameLibrary(
    private val gamesDir: File,
    private val biosDir: File,
    private val storeFile: File,
    /** The spike's cacheDir/games/ — moved over once, then ignored. */
    private val legacyGamesDir: File? = null,
) {
    /** Load the library: migrate the legacy dir, reconcile, sort by name. */
    fun load(): List<GameEntry> {
        gamesDir.mkdirs()
        migrateLegacy()
        val stored = readStore()
        val onDisk = gamesDir.listFiles()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.toSet() ?: emptySet()
        // Drop entries whose main file is gone.
        val kept = stored.filter { it.mainFile in onDisk }
        val keptNames = kept.map { it.mainFile }.toSet()
        // Adopt orphan main files (a game copied in by hand, or left
        // over from an interrupted import) so the list matches disk.
        val adopted = onDisk
            .filter { it !in keptNames && isMainFile(it) && cueBinsPresent(it, onDisk) }
            .map { name ->
                GameEntry(
                    mainFile = name,
                    displayName = displayNameFor(name),
                    files = filesFor(name, onDisk),
                    addedAt = System.currentTimeMillis(),
                )
            }
        val all = (kept + adopted).sortedWith(
            compareBy({ it.displayName.lowercase() }, { it.addedAt }),
        )
        if (all.size != stored.size || adopted.isNotEmpty()) save(all)
        return all
    }

    /** Add or replace a game (deduplicate by main file name). Files must already be in [gamesDir]. */
    fun register(mainFile: String, files: List<String>): GameEntry {
        val entry = GameEntry(
            mainFile = mainFile,
            displayName = displayNameFor(mainFile),
            files = files,
            addedAt = System.currentTimeMillis(),
        )
        val all = load().filter { it.mainFile != mainFile } + entry
        save(all.sortedWith(compareBy({ it.displayName.lowercase() }, { it.addedAt })))
        return entry
    }

    /** Remove a game from the library and delete its stored files. */
    fun remove(entry: GameEntry) {
        for (name in entry.files) {
            try {
                File(gamesDir, name).delete()
            } catch (_: SecurityException) {
                // Best effort: the entry still goes from the index.
            }
        }
        val all = load().filter { it.mainFile != entry.mainFile }
        save(all)
    }

    /** Which of the four system files are present in [biosDir]. */
    fun biosStatus(): BiosStatus {
        val present = biosDir.listFiles()
            ?.filter { it.isFile }
            ?.map { it.name.lowercase() }
            ?.toSet() ?: emptySet()
        return BiosStatus(
            home = "aes.zip" in present,
            arcade = "neogeo.zip" in present,
            system1994 = "neocd.zip" in present,
            system1995 = "neocdz.zip" in present,
        )
    }

    /**
     * Whether a game can boot under the core's current defaults:
     * carts boot on the home console (aes.zip); cue/chd games boot
     * on the 1995 system (neocdz.zip, the core's default). If this
     * is false the UI says so instead of starting a game that cannot
     * load; the core's own load error still surfaces if it fails.
     */
    fun biosReadyFor(mainFile: String, status: BiosStatus): Boolean {
        return when (mainFile.substringAfterLast('.', "").lowercase()) {
            "neo" -> status.home
            "cue", "chd" -> status.system1995
            else -> false
        }
    }

    private fun isMainFile(name: String): Boolean {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "neo", "chd", "cue" -> true
            else -> false
        }
    }

    /** A cue is only adoptable when every data file it names is present. */
    private fun cueBinsPresent(mainFile: String, onDisk: Set<String>): Boolean {
        if (!mainFile.lowercase().endsWith(".cue")) return true
        val cue = File(gamesDir, mainFile)
        if (!cue.isFile) return false
        val bins = try {
            parseCueBinFiles(cue.readText())
        } catch (_: Exception) {
            return false
        }
        val lower = onDisk.map { it.lowercase() }.toSet()
        return bins.all { it.lowercase() in lower }
    }

    /** The stored files for a main file: itself, plus a cue's data files. */
    private fun filesFor(mainFile: String, onDisk: Set<String>): List<String> {
        if (!mainFile.lowercase().endsWith(".cue")) return listOf(mainFile)
        val cue = File(gamesDir, mainFile)
        val bins = try {
            parseCueBinFiles(cue.readText())
        } catch (_: Exception) {
            return listOf(mainFile)
        }
        val byLower = onDisk.associateBy { it.lowercase() }
        val found = bins.mapNotNull { byLower[it.lowercase()] }
        return listOf(mainFile) + found
    }

    /**
     * One-time move of the spike's cacheDir/games/ into [gamesDir]:
     * main files (.neo/.chd/.cue) plus the data files their cues name.
     * A file already present in [gamesDir] wins; the legacy copy is
     * deleted either way so the move never repeats.
     */
    private fun migrateLegacy() {
        val legacy = legacyGamesDir ?: return
        if (!legacy.isDirectory) return
        val names = legacy.listFiles()?.filter { it.isFile }?.map { it.name } ?: return
        for (name in names) {
            val src = File(legacy, name)
            val dest = File(gamesDir, name)
            val isMain = isMainFile(name)
            val isBin = !isMain && name.lowercase().endsWith(".bin") &&
                names.filter { it.lowercase().endsWith(".cue") }.any { cueName ->
                    try {
                        parseCueBinFiles(File(legacy, cueName).readText())
                            .any { it.equals(name, ignoreCase = true) }
                    } catch (_: Exception) {
                        false
                    }
                }
            if (!isMain && !isBin) continue
            if (!dest.exists()) {
                try {
                    src.copyTo(dest)
                } catch (_: Exception) {
                    continue
                }
            }
            try {
                src.delete()
            } catch (_: Exception) {
                // Leave it; the next load retries.
            }
        }
    }

    private fun readStore(): List<GameEntry> {
        if (!storeFile.isFile) return emptyList()
        val out = ArrayList<GameEntry>()
        try {
            for (line in storeFile.readLines()) {
                if (line.isBlank()) continue
                val parts = line.split('|')
                if (parts.size != 4) continue
                val main = parts[0].trim()
                val name = parts[1].trim()
                val added = parts[2].trim().toLongOrNull() ?: continue
                val files = parts[3].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (main.isEmpty() || name.isEmpty() || files.isEmpty()) continue
                if (files[0] != main) continue
                out.add(GameEntry(main, name, files, added))
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return out
    }

    private fun save(entries: List<GameEntry>) {
        try {
            storeFile.parentFile?.mkdirs()
            val text = entries.joinToString("\n") { e ->
                val safeName = e.displayName.replace('|', ' ').replace('\n', ' ').trim()
                "${e.mainFile}|$safeName|${e.addedAt}|${e.files.joinToString(",")}"
            }
            storeFile.writeText(if (text.isEmpty()) "" else text + "\n")
        } catch (_: Exception) {
            // The in-memory list is still returned to the UI; the
            // next load re-reads disk, so a failed save degrades to
            // "not remembered" rather than a crash.
        }
    }
}
