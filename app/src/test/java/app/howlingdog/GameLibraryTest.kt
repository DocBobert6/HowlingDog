package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pure-JVM unit tests for the Phase 3 library: cue parsing, display
 * names, the file-based store round-trip, dedup, removal, BIOS
 * gating, and the legacy cacheDir migration. No Android framework
 * calls — the store is a plain text file, so these run on the JVM.
 */
class GameLibraryTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    private fun library(dir: File): GameLibrary {
        val games = File(dir, "games").apply { mkdirs() }
        val bios = File(dir, "bios").apply { mkdirs() }
        return GameLibrary(games, bios, File(dir, "library.txt"), File(dir, "legacy"))
    }

    // --- cue parsing ---

    @Test
    fun cueQuotedFiles() {
        val cue = """
            FILE "game (Track 1).bin" BINARY
              TRACK 1 MODE2/2352
                INDEX 01 00:00:00
            FILE "game (Track 2).bin" BINARY
              TRACK 2 AUDIO
        """.trimIndent()
        assertEquals(
            listOf("game (Track 1).bin", "game (Track 2).bin"),
            parseCueBinFiles(cue),
        )
    }

    @Test
    fun cueUnquotedAndPathedFiles() {
        val cue = "FILE game.bin BINARY\nFILE sub\\other.BIN BINARY\n"
        assertEquals(listOf("game.bin", "other.BIN"), parseCueBinFiles(cue))
    }

    @Test
    fun cueNoFilesIsEmpty() {
        assertEquals(emptyList<String>(), parseCueBinFiles("REM just a comment\n"))
    }

    @Test
    fun cueDuplicateFileListedOnce() {
        val cue = "FILE \"a.bin\" BINARY\nFILE \"a.bin\" BINARY\n"
        assertEquals(listOf("a.bin"), parseCueBinFiles(cue))
    }

    // --- display names ---

    @Test
    fun displayNameFromFile() {
        assertEquals("neobombe", displayNameFor("neobombe.neo"))
        assertEquals("metal slug 2", displayNameFor("metal-slug-2.neo"))
        assertEquals("kof 94", displayNameFor("kof_94.neo"))
        assertEquals("game", displayNameFor("game.cue"))
    }

    @Test
    fun sanitizeKeepsSafeChars() {
        assertEquals("My_Game_.neo", sanitize("My Game!.neo"))
        // Empty input falls back to game.neo; spaces map to '_' first,
        // so an all-spaces name becomes underscores, not the fallback.
        assertEquals("game.neo", sanitize(""))
        assertEquals("___", sanitize("   "))
        assertEquals(120, sanitize("a".repeat(200)).length)
    }

    // --- store round-trip, dedup, removal ---

    @Test
    fun storeRoundTrip() {
        val dir = tempDir("lib-roundtrip")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "neobombe.neo").writeText("fake")
        File(games, "mslug.neo").writeText("fake")
        lib.register("neobombe.neo", listOf("neobombe.neo"))
        lib.register("mslug.neo", listOf("mslug.neo"))
        // A fresh instance reads the same store back.
        val loaded = library(dir).load()
        assertEquals(listOf("mslug", "neobombe"), loaded.map { it.displayName })
        assertEquals(listOf("mslug.neo"), loaded[0].files)
    }

    @Test
    fun reimportReplacesInsteadOfDuplicating() {
        val dir = tempDir("lib-dedup")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "neobombe.neo").writeText("fake")
        lib.register("neobombe.neo", listOf("neobombe.neo"))
        lib.register("neobombe.neo", listOf("neobombe.neo"))
        assertEquals(1, lib.load().size)
    }

    @Test
    fun removeDeletesFilesAndEntry() {
        val dir = tempDir("lib-remove")
        val lib = library(dir)
        val games = File(dir, "games")
        val cue = File(games, "game.cue").apply { writeText("FILE \"game.bin\" BINARY\n") }
        val bin = File(games, "game.bin").apply { writeText("fake") }
        lib.register("game.cue", listOf("game.cue", "game.bin"))
        assertEquals(1, lib.load().size)
        lib.remove(lib.load()[0])
        assertFalse(cue.exists())
        assertFalse(bin.exists())
        assertEquals(emptyList<GameEntry>(), lib.load())
    }

    @Test
    fun staleEntryDroppedAndOrphanAdopted() {
        val dir = tempDir("lib-reconcile")
        val lib = library(dir)
        val games = File(dir, "games")
        // An entry whose file vanished is dropped on load.
        lib.register("gone.neo", listOf("gone.neo"))
        File(games, "gone.neo").delete()
        // A file with no entry is adopted.
        File(games, "orphan.neo").writeText("fake")
        val loaded = lib.load()
        assertEquals(listOf("orphan"), loaded.map { it.displayName })
    }

    @Test
    fun cueWithoutBinsIsNotAdopted() {
        val dir = tempDir("lib-cue-orphan")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "game.cue").writeText("FILE \"missing.bin\" BINARY\n")
        assertEquals(emptyList<GameEntry>(), lib.load())
    }

    @Test
    fun malformedStoreLinesSkipped() {
        val dir = tempDir("lib-malformed")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "good.neo").writeText("fake")
        File(dir, "library.txt").writeText(
            "not a valid line\n" +
                "good.neo|good|123|good.neo\n" +
                "bad.neo|bad|notanumber|bad.neo\n",
        )
        val loaded = lib.load()
        assertEquals(listOf("good"), loaded.map { it.displayName })
    }

    // --- legacy migration ---

    @Test
    fun legacyCacheDirMigrates() {
        val dir = tempDir("lib-migrate")
        val lib = library(dir)
        val legacy = File(dir, "legacy").apply { mkdirs() }
        File(legacy, "neobombe.neo").writeText("fake")
        File(legacy, "game.cue").writeText("FILE \"game.bin\" BINARY\n")
        File(legacy, "game.bin").writeText("fake")
        File(legacy, "stray.bin").writeText("fake")
        val loaded = lib.load()
        assertEquals(listOf("game", "neobombe"), loaded.map { it.displayName })
        // The main files and the referenced bin moved; the stray did not.
        assertTrue(File(dir, "games/neobombe.neo").exists())
        assertTrue(File(dir, "games/game.bin").exists())
        assertFalse(File(dir, "games/stray.bin").exists())
        assertFalse(File(legacy, "neobombe.neo").exists())
    }

    // --- BIOS ---

    @Test
    fun biosStatusAndGating() {
        val dir = tempDir("lib-bios")
        val lib = library(dir)
        val bios = File(dir, "bios")
        File(bios, "aes.zip").writeText("fake")
        File(bios, "neocdz.zip").writeText("fake")
        val status = lib.biosStatus()
        assertTrue(status.home)
        assertFalse(status.arcade)
        assertFalse(status.system1994)
        assertTrue(status.system1995)
        assertEquals(2, status.readyCount)
        val cart = GameEntry("neobombe.neo", "neobombe", listOf("neobombe.neo"), 0L)
        val disc = GameEntry("game.cue", "game", listOf("game.cue"), 0L)
        assertTrue(lib.biosReadyFor(cart, status))
        assertTrue(lib.biosReadyFor(disc, status))
        assertFalse(lib.biosReadyFor(disc, status.copy(system1995 = false)))
        assertFalse(lib.biosReadyFor(cart, status.copy(home = false)))
    }

    // --- per-game mode ---

    @Test
    fun effectiveModeDefaultsByType() {
        val cart = GameEntry("neobombe.neo", "neobombe", listOf("neobombe.neo"), 0L)
        val cue = GameEntry("game.cue", "game", listOf("game.cue"), 0L)
        val chd = GameEntry("game.chd", "game", listOf("game.chd"), 0L)
        assertEquals("aes", cart.effectiveMode)
        assertEquals("cdz", cue.effectiveMode)
        assertEquals("cdz", chd.effectiveMode)
        assertEquals(listOf("aes", "mvs"), modesFor(cart))
        assertEquals(listOf("cd", "cdz"), modesFor(cue))
        assertEquals("Home console", modeLabel("aes"))
        assertEquals("Arcade", modeLabel("mvs"))
        assertEquals("Neo Geo CD", modeLabel("cd"))
        assertEquals("Neo Geo CDZ", modeLabel("cdz"))
    }

    @Test
    fun setModePersistsAndRoundTrips() {
        val dir = tempDir("lib-mode")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "neobombe.neo").writeText("fake")
        val entry = lib.register("neobombe.neo", listOf("neobombe.neo"))
        val updated = lib.setMode(entry, "mvs")
        assertEquals("mvs", updated.effectiveMode)
        // A fresh instance reads the stored mode back.
        val loaded = library(dir).load()
        assertEquals("mvs", loaded[0].mode)
        // Clearing the mode restores the type default.
        lib.setMode(loaded[0], null)
        assertEquals("aes", library(dir).load()[0].effectiveMode)
    }

    @Test
    fun legacyFourFieldLinesLoadWithNoMode() {
        val dir = tempDir("lib-mode-legacy")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "neobombe.neo").writeText("fake")
        File(dir, "library.txt").writeText("neobombe.neo|neobombe|123|neobombe.neo\n")
        val loaded = lib.load()
        assertEquals(1, loaded.size)
        assertEquals(null, loaded[0].mode)
        assertEquals("aes", loaded[0].effectiveMode)
    }

    @Test
    fun reimportKeepsStoredMode() {
        val dir = tempDir("lib-mode-reimport")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "neobombe.neo").writeText("fake")
        val entry = lib.register("neobombe.neo", listOf("neobombe.neo"))
        lib.setMode(entry, "mvs")
        lib.register("neobombe.neo", listOf("neobombe.neo"))
        assertEquals("mvs", lib.load()[0].mode)
    }

    @Test
    fun biosGateFollowsEffectiveMode() {
        val dir = tempDir("lib-mode-gate")
        val lib = library(dir)
        val bios = File(dir, "bios")
        File(bios, "neogeo.zip").writeText("fake")
        File(bios, "neocd.zip").writeText("fake")
        val status = lib.biosStatus()
        val cart = GameEntry("neobombe.neo", "neobombe", listOf("neobombe.neo"), 0L)
        // Home console (default) needs aes.zip, which is absent.
        assertFalse(lib.biosReadyFor(cart, status))
        // Arcade mode needs neogeo.zip, which is present.
        assertTrue(lib.biosReadyFor(cart.copy(mode = "mvs"), status))
        val disc = GameEntry("game.cue", "game", listOf("game.cue"), 0L)
        // Neo Geo CDZ (default) needs neocdz.zip, which is absent.
        assertFalse(lib.biosReadyFor(disc, status))
        // Neo Geo CD needs neocd.zip, which is present.
        assertTrue(lib.biosReadyFor(disc.copy(mode = "cd"), status))
    }
}
