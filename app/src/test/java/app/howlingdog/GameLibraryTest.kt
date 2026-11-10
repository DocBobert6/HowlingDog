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
        assertTrue(lib.biosReadyFor("neobombe.neo", status))
        assertTrue(lib.biosReadyFor("game.cue", status))
        assertTrue(lib.biosReadyFor("game.chd", status))
        assertFalse(lib.biosReadyFor("game.cue", status.copy(system1995 = false)))
        assertFalse(lib.biosReadyFor("neobombe.neo", status.copy(home = false)))
    }
}
