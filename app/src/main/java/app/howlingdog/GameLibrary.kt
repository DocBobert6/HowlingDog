package app.howlingdog

import java.io.File

/**
 * Phase 3 — the game library. Plain file-based store, no database:
 * the games live in [gamesDir] (app-private files, not cache — the
 * system may clear cacheDir, and the library must survive), and the
 * index lives in [storeFile], one game per line:
 *
 *   mainFile | displayName | addedAtMillis | file1,file2,... | mode | pinned | customName | lastPlayedMillis | totalPlayMillis
 *
 * The mode field is the per-game system mode (or empty for the type
 * default); lines written before the mode existed have only four
 * fields and still load, with the mode unset. The pinned field is
 * "1" for a favourite, empty otherwise; lines written before pins
 * existed have four or five fields and still load, unpinned.
 * The customName field (Phase 9, item 4) is the name the user typed
 * in the rename dialog, or empty for the original name; lines
 * written before renames existed have six or fewer fields and still
 * load, unrenamed. It is a display-only override: [displayName]
 * stays the filename-derived original, and cover-art matching
 * (CoverArt / NeoHeader) keeps using that original, so a renamed
 * game keeps its art. Pinned games sort to the top of the library, then by name.
 * The lastPlayed field (v1.5, feature 3) is the epoch millis the
 * game was last started, or 0 for never played; lines written
 * before it existed have seven or fewer fields and still load,
 * never played. It drives the Recently played sort.
 * The totalPlayMillis field (2.0, feature 1) is the total
 * wall-clock time the game has been played, in milliseconds, or
 * 0 for never played; lines written before it existed have
 * eight or fewer fields and still load, with no playtime.
 * It is the value the Arcade Manager (2.0, feature 6) shows
 * and totals.
 *
 * Filenames are sanitized (see [sanitize]: letters, digits, '.', '_',
 * '-' only), so '|' and ',' can never appear in a stored name and the
 * format needs no escaping; the reader still skips malformed lines.
 *
 * A game is a game: the library never shows or sorts by media type.
 * The kind is derived from the main file's extension only where the
 * app must know it internally (which system files a game needs to
 * boot). The UI may say CD now (rule lifted 2026-11-09); it never
 * shows disc imagery or sorts by media.
 *
 * BIOS files live in [biosDir] (the spike's filesDir/bios/). The
 * library only reads them; picking/copying stays in the UI (SAF).
 * The four files, by role (code names; the UI shows the role names,
 * never the raw zip names for the two 1990s systems):
 * - aes.zip     -> "Home console"  (the default system for carts)
 * - neogeo.zip  -> "Arcade"
 * - neocd.zip   -> "Neo Geo CD"    (the Neo Geo CD system)
 * - neocdz.zip  -> "Neo Geo CDZ"   (the Neo Geo CDZ system)
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
    /** Per-game system mode ("aes", "mvs", "uni", "cd", "cdz", "cdz_unibios"), or null for the type default. */
    val mode: String? = null,
    /** Favourite: pinned games sit at the top of the library grid. */
    val pinned: Boolean = false,
    /**
     * Phase 9, item 4 — the name the user typed for this game, or
     * null for the original. Display-only: [displayName] keeps the
     * filename-derived original, so cover-art matching (which reads
     * [displayName], never this) still works after a rename.
     */
    val customName: String? = null,
    /**
     * v1.5, feature 3 — the epoch millis this game was last
     * started, or 0 for never played. Drives the Recently
     * played sort; a game never played sorts last there.
     */
    val lastPlayed: Long = 0,
    /**
     * 2.0, feature 1 — the total wall-clock time this game
     * has been played, in milliseconds, or 0 for never
     * played. Counted while the game is running, never while
     * paused or in the library (see PlaytimeTracker).
     */
    val totalPlayMillis: Long = 0,
) {
    /** The name the library shows: the custom name when set, else the original. */
    val effectiveName: String get() = customName?.takeIf { it.isNotBlank() } ?: displayName

    /** The file the core loads: the main file's path under [gamesDir]. */
    fun mainPath(gamesDir: File): File = File(gamesDir, mainFile)

    /** True when the main file is a cue sheet (its data files travel with it). */
    val isCue: Boolean get() = mainFile.lowercase().endsWith(".cue")

    /** True for the disc games (cue sheets and single-file images). */
    val isDisc: Boolean get() = isCue || mainFile.lowercase().endsWith(".chd")

    /** The mode this game boots with: the stored mode, or the type default (Home console for carts, Neo Geo CD for discs — the original system the games were made for; CDZ is a per-game pick, never the default). */
    val effectiveMode: String get() = mode ?: if (isDisc) "cd" else "aes"
}

/** Display label for a system mode value. */
fun modeLabel(mode: String): String = when (mode) {
    "aes" -> "Home console"
    "mvs" -> "Arcade"
    "uni" -> "Universe BIOS"
    "cd" -> "Neo Geo CD"
    "cdz" -> "Neo Geo CDZ"
    "cdz_unibios" -> "Universe BIOS (CDZ)"
    else -> mode
}

/**
 * The modes a game can boot with: the cart systems (Home console,
 * Arcade, Universe BIOS), or the disc systems (Neo Geo CD, Neo Geo
 * CDZ, Universe BIOS on CDZ). Phase 9, item 9 — Universe BIOS is
 * a boot mode so a game can run under the UniBIOS at all, which
 * is what makes its in-game menu reachable mid-game.
 */
fun modesFor(entry: GameEntry): List<String> =
    if (entry.isDisc) listOf("cd", "cdz", "cdz_unibios") else listOf("aes", "mvs", "uni")

/** The four system files, by name — the only zips the BIOS picker copies in (audit fix F2). */
val KNOWN_BIOS_FILES: Set<String> = setOf("aes.zip", "neogeo.zip", "neocd.zip", "neocdz.zip")

/** True when [name] is one of the four system files (case-insensitive) — the BIOS picker's filter. */
fun isKnownBiosFile(name: String): Boolean = name.lowercase() in KNOWN_BIOS_FILES

/** The system modes a stored mode value may hold. */
val KNOWN_MODES: Set<String> = setOf("aes", "mvs", "uni", "cd", "cdz", "cdz_unibios")

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

/** The library order: pinned favourites first, then by the shown name, then by when added. */
val libraryOrder: Comparator<GameEntry> =
    compareByDescending<GameEntry> { it.pinned }
        .thenBy { it.effectiveName.lowercase() }
        .thenBy { it.addedAt }

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
        val all = (kept + adopted).sortedWith(libraryOrder)
        if (all.size != stored.size || adopted.isNotEmpty()) save(all)
        return all
    }

    /** Add or replace a game (deduplicate by main file name). Files must already be in [gamesDir]. A re-import keeps the game's stored mode, pin, and custom name. */
    fun register(mainFile: String, files: List<String>): GameEntry {
        val existing = load().find { it.mainFile == mainFile }
        val entry = GameEntry(
            mainFile = mainFile,
            displayName = displayNameFor(mainFile),
            files = files,
            addedAt = System.currentTimeMillis(),
            mode = existing?.mode,
            pinned = existing?.pinned ?: false,
            customName = existing?.customName,
            lastPlayed = existing?.lastPlayed ?: 0,
            totalPlayMillis = existing?.totalPlayMillis ?: 0,
        )
        val all = load().filter { it.mainFile != mainFile } + entry
        save(all.sortedWith(libraryOrder))
        return entry
    }

    /** Set (or clear, with null) a game's system mode; returns the updated entry. */
    fun setMode(entry: GameEntry, mode: String?): GameEntry {
        require(mode == null || mode in KNOWN_MODES) { "unknown mode: $mode" }
        val updated = entry.copy(mode = mode)
        val all = load().filter { it.mainFile != entry.mainFile } + updated
        save(all.sortedWith(libraryOrder))
        return updated
    }

    /** Pin or unpin a game; returns the updated entry. Pinned games sort to the top of the library. */
    fun setPinned(entry: GameEntry, pinned: Boolean): GameEntry {
        val updated = entry.copy(pinned = pinned)
        val all = load().filter { it.mainFile != entry.mainFile } + updated
        save(all.sortedWith(libraryOrder))
        return updated
    }

    /**
     * Phase 9, item 4 — rename a game; returns the updated entry.
     * The name is trimmed, and '|' / newlines are replaced (they
     * would break the store line); a blank or null name clears the
     * custom name, so the game shows its original name again. The
     * original [GameEntry.displayName] never changes, so cover-art
     * matching keeps using it and a renamed game keeps its art.
     */
    fun setCustomName(entry: GameEntry, name: String?): GameEntry {
        val cleaned = name?.replace('|', ' ')?.replace('\n', ' ')?.replace('\r', ' ')
            ?.trim()?.take(120)?.takeIf { it.isNotEmpty() }
        val updated = entry.copy(customName = cleaned)
        val all = load().filter { it.mainFile != entry.mainFile } + updated
        save(all.sortedWith(libraryOrder))
        return updated
    }

    /**
     * v1.5, feature 3 — mark a game played now (called when a
     * game starts); returns the updated entry. The library
     * reload on returning to it picks the new time up, so the
     * Recently played sort orders it first.
     */
    fun setLastPlayed(entry: GameEntry, atMillis: Long = System.currentTimeMillis()): GameEntry {
        val updated = entry.copy(lastPlayed = atMillis)
        val all = load().filter { it.mainFile != entry.mainFile } + updated
        save(all.sortedWith(libraryOrder))
        return updated
    }

    /**
     * 2.0, feature 1 — overwrite a game's total playtime (the
     * tracker holds the full total, so this is a plain write,
     * never an add — pausing and quitting can both persist
     * without double-counting); returns the updated entry.
     */
    fun setTotalPlayMillis(entry: GameEntry, totalMillis: Long): GameEntry {
        val updated = entry.copy(totalPlayMillis = totalMillis.coerceAtLeast(0))
        val all = load().filter { it.mainFile != entry.mainFile } + updated
        save(all.sortedWith(libraryOrder))
        return updated
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
     * Whether a game can boot under its effective mode: Home console
     * needs aes.zip, Arcade and Universe BIOS (carts) need
     * neogeo.zip — the UniBIOS ROM lives in it — Neo Geo CD needs
     * neocd.zip, Neo Geo CDZ and Universe BIOS (CDZ) need
     * neocdz.zip. If this is false the UI says so instead of
     * starting a game that cannot load; the core's own load error
     * still surfaces if it fails.
     */
    fun biosReadyFor(entry: GameEntry, status: BiosStatus): Boolean {
        return when (entry.effectiveMode) {
            "aes" -> status.home
            "mvs", "uni" -> status.arcade
            "cd" -> status.system1994
            "cdz", "cdz_unibios" -> status.system1995
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
                // Four fields: written before per-game modes
                // existed. Five: before pins. Six: mode + pin.
                // Seven: mode + pin + custom name. Eight: plus
                // last played (v1.5, feature 3). Nine: plus total
                // playtime (2.0, feature 1).
                if (parts.size !in 4..9) continue
                val main = parts[0].trim()
                val name = parts[1].trim()
                val added = parts[2].trim().toLongOrNull() ?: continue
                val files = parts[3].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (main.isEmpty() || name.isEmpty() || files.isEmpty()) continue
                if (files[0] != main) continue
                val mode = if (parts.size >= 5) {
                    parts[4].trim().ifEmpty { null }?.takeIf { it in KNOWN_MODES }
                } else {
                    null
                }
                // Sixth field, written since pins existed: "1" is
                // pinned; anything else (including absent) is not.
                val pinned = parts.size >= 6 &&
                    (parts[5].trim() == "1" || parts[5].trim().equals("true", ignoreCase = true))
                // Seventh field, written since renames existed: the
                // custom name, or empty/absent for the original.
                // Present in both 7- and 8-field lines (index 6).
                val customName = if (parts.size >= 7) {
                    parts[6].trim().takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                // Eighth field, written since last-played existed
                // (v1.5, feature 3): the epoch millis the game was
                // last started, or 0 / absent for never played.
                // Present in both 8- and 9-field lines (index 7).
                val lastPlayed = if (parts.size >= 8) {
                    parts[7].trim().toLongOrNull() ?: 0
                } else {
                    0
                }
                // Ninth field, written since playtime existed
                // (2.0, feature 1): the total wall-clock millis
                // the game has been played, or 0 / absent for
                // never played.
                val totalPlayMillis = if (parts.size == 9) {
                    parts[8].trim().toLongOrNull()?.coerceAtLeast(0) ?: 0
                } else {
                    0
                }
                out.add(GameEntry(main, name, files, added, mode, pinned, customName, lastPlayed, totalPlayMillis))
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
                val safeCustom = e.customName?.replace('|', ' ')?.replace('\n', ' ')?.trim() ?: ""
                "${e.mainFile}|$safeName|${e.addedAt}|${e.files.joinToString(",")}|${e.mode ?: ""}|${if (e.pinned) "1" else ""}|$safeCustom|${e.lastPlayed}|${e.totalPlayMillis}"
            }
            storeFile.writeText(if (text.isEmpty()) "" else text + "\n")
        } catch (_: Exception) {
            // The in-memory list is still returned to the UI; the
            // next load re-reads disk, so a failed save degrades to
            // "not remembered" rather than a crash.
        }
    }
}
