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
    fun lastPlayedPersistsAndSevenFieldLinesLoadAsNeverPlayed() {
        // v1.5, feature 3 — the 8th store field: marking a game
        // played survives a reload, and a line written before
        // the field existed (7 fields, with a custom name) still
        // loads — unplayed, with its rename intact.
        val dir = tempDir("lib-lastplayed")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "neobombe.neo").writeText("fake")
        File(games, "kof98.neo").writeText("fake")
        val entry = lib.register("neobombe.neo", listOf("neobombe.neo"))
        lib.register("kof98.neo", listOf("kof98.neo"))
        val played = lib.setLastPlayed(entry, 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, played.lastPlayed)
        val loaded = library(dir).load()
        assertEquals(1_700_000_000_000L, loaded.first { it.mainFile == "neobombe.neo" }.lastPlayed)
        assertEquals(0L, loaded.first { it.mainFile == "kof98.neo" }.lastPlayed)
        // A 7-field line (mode + pin + custom name, no last
        // played) loads with its rename and as never played.
        // The other game file is removed first, so the load does
        // not adopt it as an orphan.
        File(games, "neobombe.neo").delete()
        val store = File(dir, "library.txt")
        store.writeText("kof98.neo|kof98|1000|kof98.neo|aes|1|My Favourite\n")
        val legacy = library(dir).load()
        assertEquals(1, legacy.size)
        assertEquals("My Favourite", legacy[0].customName)
        assertEquals(0L, legacy[0].lastPlayed)
        assertEquals(true, legacy[0].pinned)
    }

    @Test
    fun totalPlayMillisPersistsAndEightFieldLinesLoadAsZero() {
        // 2.0, feature 1 — the 9th store field: setting a game's
        // total playtime survives a reload, and a line written
        // before the field existed (8 fields, with last played)
        // still loads — with its last played intact and no
        // playtime.
        val dir = tempDir("lib-playtime")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "neobombe.neo").writeText("fake")
        File(games, "kof98.neo").writeText("fake")
        val entry = lib.register("neobombe.neo", listOf("neobombe.neo"))
        lib.register("kof98.neo", listOf("kof98.neo"))
        val updated = lib.setTotalPlayMillis(entry, 12 * 3_600_000L + 34 * 60_000L)
        assertEquals(12 * 3_600_000L + 34 * 60_000L, updated.totalPlayMillis)
        val loaded = library(dir).load()
        assertEquals(12 * 3_600_000L + 34 * 60_000L, loaded.first { it.mainFile == "neobombe.neo" }.totalPlayMillis)
        assertEquals(0L, loaded.first { it.mainFile == "kof98.neo" }.totalPlayMillis)
        // An 8-field line (mode + pin + custom name + last
        // played, no playtime) loads with its last played and
        // no playtime. The other game file is removed first,
        // so the load does not adopt it as an orphan.
        File(games, "neobombe.neo").delete()
        val store = File(dir, "library.txt")
        store.writeText("kof98.neo|kof98|1000|kof98.neo|aes|1|My Favourite|1700000000000\n")
        val legacy = library(dir).load()
        assertEquals(1, legacy.size)
        assertEquals(1_700_000_000_000L, legacy[0].lastPlayed)
        assertEquals(0L, legacy[0].totalPlayMillis)
        // A 9-field line loads its playtime.
        store.writeText("kof98.neo|kof98|1000|kof98.neo|aes|1|My Favourite|1700000000000|4560000\n")
        val withPlaytime = library(dir).load()
        assertEquals(1_700_000_000_000L, withPlaytime[0].lastPlayed)
        assertEquals(4_560_000L, withPlaytime[0].totalPlayMillis)
    }

    @Test
    fun reimportKeepsTotalPlayMillis() {
        val dir = tempDir("lib-playtime-reimport")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "neobombe.neo").writeText("fake")
        val entry = lib.register("neobombe.neo", listOf("neobombe.neo"))
        lib.setTotalPlayMillis(entry, 90_000L)
        lib.register("neobombe.neo", listOf("neobombe.neo"))
        assertEquals(90_000L, lib.load()[0].totalPlayMillis)
    }

    @Test
    fun setTotalPlayMillisClampsNegativeToZero() {
        val dir = tempDir("lib-playtime-clamp")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "neobombe.neo").writeText("fake")
        val entry = lib.register("neobombe.neo", listOf("neobombe.neo"))
        assertEquals(0L, lib.setTotalPlayMillis(entry, -5_000L).totalPlayMillis)
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
        // The disc default is Neo Geo CD, which needs neocd.zip
        // (absent here) — not CDZ, even though neocdz.zip is present.
        assertFalse(lib.biosReadyFor(disc, status))
        assertTrue(lib.biosReadyFor(disc.copy(mode = "cdz"), status))
        assertFalse(lib.biosReadyFor(disc, status.copy(system1995 = false)))
        assertFalse(lib.biosReadyFor(cart, status.copy(home = false)))
    }

    @Test
    fun knownBiosFilesOnly() {
        // Audit fix F2: the BIOS picker copies only these four.
        assertEquals(4, KNOWN_BIOS_FILES.size)
        assertTrue(isKnownBiosFile("aes.zip"))
        assertTrue(isKnownBiosFile("neogeo.zip"))
        assertTrue(isKnownBiosFile("neocd.zip"))
        assertTrue(isKnownBiosFile("neocdz.zip"))
        // Case-insensitive: a MAME set may carry upper-case names.
        assertTrue(isKnownBiosFile("AES.ZIP"))
        assertTrue(isKnownBiosFile("NeoGeo.Zip"))
        // Anything else is not a system file, even if it is a zip.
        assertFalse(isKnownBiosFile("irrmaze.zip"))
        assertFalse(isKnownBiosFile("mame.zip"))
        assertFalse(isKnownBiosFile("aes.bin"))
        assertFalse(isKnownBiosFile(""))
    }

    // --- per-game mode ---

    @Test
    fun effectiveModeDefaultsByType() {
        val cart = GameEntry("neobombe.neo", "neobombe", listOf("neobombe.neo"), 0L)
        val cue = GameEntry("game.cue", "game", listOf("game.cue"), 0L)
        val chd = GameEntry("game.chd", "game", listOf("game.chd"), 0L)
        assertEquals("aes", cart.effectiveMode)
        assertEquals("cd", cue.effectiveMode)
        assertEquals("cd", chd.effectiveMode)
        assertEquals(listOf("aes", "mvs", "uni"), modesFor(cart))
        assertEquals(listOf("cd", "cdz", "cdz_unibios"), modesFor(cue))
        assertEquals("Home console", modeLabel("aes"))
        assertEquals("Arcade", modeLabel("mvs"))
        assertEquals("Universe BIOS", modeLabel("uni"))
        assertEquals("Neo Geo CD", modeLabel("cd"))
        assertEquals("Neo Geo CDZ", modeLabel("cdz"))
        assertEquals("Universe BIOS (CDZ)", modeLabel("cdz_unibios"))
    }

    @Test
    fun universeBiosModePersistsAndGatesOnItsZip() {
        // Phase 9, item 9 — Universe BIOS is a stored per-game
        // mode, and its ROMs live in the Arcade / CDZ zips: a cart
        // needs neogeo.zip, a CDZ disc needs neocdz.zip.
        val dir = tempDir("lib-mode-uni")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "neobombe.neo").writeText("fake")
        File(games, "game.cue").writeText("fake cue")
        val cart = lib.register("neobombe.neo", listOf("neobombe.neo"))
        val disc = lib.register("game.cue", listOf("game.cue"))
        lib.setMode(cart, "uni")
        lib.setMode(disc, "cdz_unibios")
        val loaded = library(dir).load()
        assertEquals("uni", loaded.first { it.mainFile == "neobombe.neo" }.mode)
        assertEquals("cdz_unibios", loaded.first { it.mainFile == "game.cue" }.mode)
        val bios = File(dir, "bios")
        File(bios, "neogeo.zip").writeText("fake")
        assertTrue(lib.biosReadyFor(loaded.first { it.mainFile == "neobombe.neo" }, lib.biosStatus()))
        assertFalse(lib.biosReadyFor(loaded.first { it.mainFile == "game.cue" }, lib.biosStatus()))
        File(bios, "neocdz.zip").writeText("fake")
        assertTrue(lib.biosReadyFor(loaded.first { it.mainFile == "game.cue" }, lib.biosStatus()))
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
        // Neo Geo CD (the default) needs neocd.zip, which is present.
        assertTrue(lib.biosReadyFor(disc, status))
        // Neo Geo CDZ needs neocdz.zip, which is absent.
        assertFalse(lib.biosReadyFor(disc.copy(mode = "cdz"), status))
    }

    @Test
    fun storedDiscModeSurvivesDefaultChange() {
        val dir = tempDir("lib-mode-disc")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "game.cue").writeText("fake cue")
        val entry = lib.register("game.cue", listOf("game.cue"))
        // A new disc defaults to Neo Geo CD.
        assertEquals("cd", entry.effectiveMode)
        // Setting CDZ sticks across a reload — the default change
        // must never rewrite a stored per-game choice.
        lib.setMode(entry, "cdz")
        val loaded = library(dir).load()
        assertEquals("cdz", loaded[0].mode)
        assertEquals("cdz", loaded[0].effectiveMode)
        // And a disc stored as CD stays CD.
        lib.setMode(loaded[0], "cd")
        assertEquals("cd", library(dir).load()[0].effectiveMode)
    }

    // --- pin favourites (Phase 9, item 1) ---

    @Test
    fun pinPersistsAndSortsPinnedFirst() {
        val dir = tempDir("lib-pin")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "aaa.neo").writeText("fake")
        File(games, "mmm.neo").writeText("fake")
        File(games, "zzz.neo").writeText("fake")
        lib.register("aaa.neo", listOf("aaa.neo"))
        lib.register("mmm.neo", listOf("mmm.neo"))
        val zzz = lib.register("zzz.neo", listOf("zzz.neo"))
        // Name order before any pin.
        assertEquals(listOf("aaa", "mmm", "zzz"), lib.load().map { it.displayName })
        // Pinning the alphabetically-last game pulls it to the top.
        val pinned = lib.setPinned(zzz, true)
        assertTrue(pinned.pinned)
        assertEquals(listOf("zzz", "aaa", "mmm"), lib.load().map { it.displayName })
        // A fresh instance reads the pin back from the store.
        assertTrue(library(dir).load()[0].pinned)
        assertEquals("zzz", library(dir).load()[0].displayName)
    }

    @Test
    fun pinnedGamesSortByNameAmongThemselves() {
        val dir = tempDir("lib-pin-order")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "aaa.neo").writeText("fake")
        File(games, "mmm.neo").writeText("fake")
        File(games, "zzz.neo").writeText("fake")
        val aaa = lib.register("aaa.neo", listOf("aaa.neo"))
        lib.register("mmm.neo", listOf("mmm.neo"))
        val zzz = lib.register("zzz.neo", listOf("zzz.neo"))
        // Pin zzz first, then aaa: both pinned, name order within.
        lib.setPinned(zzz, true)
        lib.setPinned(aaa, true)
        assertEquals(listOf("aaa", "zzz", "mmm"), lib.load().map { it.displayName })
    }

    @Test
    fun unpinRestoresNameOrder() {
        val dir = tempDir("lib-unpin")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "aaa.neo").writeText("fake")
        File(games, "zzz.neo").writeText("fake")
        lib.register("aaa.neo", listOf("aaa.neo"))
        val zzz = lib.register("zzz.neo", listOf("zzz.neo"))
        lib.setPinned(zzz, true)
        assertEquals(listOf("zzz", "aaa"), lib.load().map { it.displayName })
        val unpinned = lib.setPinned(lib.load()[0], false)
        assertFalse(unpinned.pinned)
        assertEquals(listOf("aaa", "zzz"), lib.load().map { it.displayName })
    }

    @Test
    fun reimportKeepsPinned() {
        val dir = tempDir("lib-pin-reimport")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "neobombe.neo").writeText("fake")
        val entry = lib.register("neobombe.neo", listOf("neobombe.neo"))
        lib.setPinned(entry, true)
        lib.register("neobombe.neo", listOf("neobombe.neo"))
        assertTrue(lib.load()[0].pinned)
    }

    // --- Phase 9, item 4: manual rename ---

    @Test
    fun renamePersistsAndShowsCustomNameButKeepsOriginal() {
        val dir = tempDir("lib-rename")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "metal-slug-2.neo").writeText("fake")
        val entry = lib.register("metal-slug-2.neo", listOf("metal-slug-2.neo"))
        assertEquals("metal slug 2", entry.displayName)
        assertEquals("metal slug 2", entry.effectiveName)
        val renamed = lib.setCustomName(entry, "My Favourite Slug")
        assertEquals("My Favourite Slug", renamed.customName)
        assertEquals("My Favourite Slug", renamed.effectiveName)
        // The original never changes: it is what art matching reads.
        assertEquals("metal slug 2", renamed.displayName)
        // A fresh instance reads the custom name back from the store.
        val reloaded = library(dir).load()[0]
        assertEquals("My Favourite Slug", reloaded.customName)
        assertEquals("My Favourite Slug", reloaded.effectiveName)
        assertEquals("metal slug 2", reloaded.displayName)
    }

    @Test
    fun emptyOrBlankNameRevertsToOriginal() {
        val dir = tempDir("lib-rename-clear")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "neobombe.neo").writeText("fake")
        val entry = lib.register("neobombe.neo", listOf("neobombe.neo"))
        lib.setCustomName(entry, "Bomber")
        assertEquals("Bomber", lib.load()[0].effectiveName)
        // Empty, blank, and null all clear the custom name.
        val cleared = lib.setCustomName(lib.load()[0], "")
        assertEquals(null, cleared.customName)
        assertEquals("neobombe", cleared.effectiveName)
        lib.setCustomName(lib.load()[0], "Bomber")
        lib.setCustomName(lib.load()[0], "   ")
        assertEquals(null, lib.load()[0].customName)
        assertEquals("neobombe", lib.load()[0].effectiveName)
        lib.setCustomName(lib.load()[0], "Bomber")
        lib.setCustomName(lib.load()[0], null)
        assertEquals(null, library(dir).load()[0].customName)
        assertEquals("neobombe", library(dir).load()[0].effectiveName)
    }

    @Test
    fun renameKeepsArtMatchingOnTheOriginalName() {
        val dir = tempDir("lib-rename-art")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "metal-slug-5.neo").writeText("fake")
        val entry = lib.register("metal-slug-5.neo", listOf("metal-slug-5.neo"))
        val index = listOf(
            "Metal Slug 5 (NGH-2680).png",
            "Metal Slug 2 (NGH-2410).png",
            "Neo Bomberman.png",
        )
        val renamed = lib.setCustomName(entry, "My Slug")
        // Art matching reads the ORIGINAL name: it still finds the art.
        assertEquals(
            "Metal Slug 5 (NGH-2680).png",
            NeoHeader.matchArtFile(renamed.displayName, null, index),
        )
        // The custom name would NOT find it — so nothing in the app
        // may ever feed the custom name to the art matcher.
        assertEquals(null, NeoHeader.matchArtFile(renamed.effectiveName, null, index))
    }

    @Test
    fun reimportKeepsCustomName() {
        val dir = tempDir("lib-rename-reimport")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "neobombe.neo").writeText("fake")
        val entry = lib.register("neobombe.neo", listOf("neobombe.neo"))
        lib.setCustomName(entry, "Bomber")
        lib.register("neobombe.neo", listOf("neobombe.neo"))
        assertEquals("Bomber", lib.load()[0].customName)
        assertEquals("Bomber", lib.load()[0].effectiveName)
    }

    @Test
    fun renamedGameSortsByItsShownName() {
        val dir = tempDir("lib-rename-sort")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "aaa.neo").writeText("fake")
        File(games, "zzz.neo").writeText("fake")
        lib.register("aaa.neo", listOf("aaa.neo"))
        val zzz = lib.register("zzz.neo", listOf("zzz.neo"))
        assertEquals(listOf("aaa", "zzz"), lib.load().map { it.displayName })
        lib.setCustomName(zzz, "000 first")
        assertEquals(listOf("000 first", "aaa"), lib.load().map { it.effectiveName })
    }

    @Test
    fun legacyLinesLoadUnrenamedAndSeventhFieldLoadsCustomName() {
        val dir = tempDir("lib-rename-legacy")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "aaa.neo").writeText("fake")
        File(games, "mmm.neo").writeText("fake")
        File(games, "zzz.neo").writeText("fake")
        // Four/six fields (before renames) load unrenamed; a
        // seven-field line loads its custom name.
        File(dir, "library.txt").writeText(
            "aaa.neo|aaa|100|aaa.neo\n" +
                "mmm.neo|mmm|200|mmm.neo||1\n" +
                "zzz.neo|zzz|300|zzz.neo||1|Zed Game\n",
        )
        val loaded = lib.load()
        assertEquals(null, loaded.find { it.mainFile == "aaa.neo" }?.customName)
        assertEquals(null, loaded.find { it.mainFile == "mmm.neo" }?.customName)
        val zzz = loaded.find { it.mainFile == "zzz.neo" }!!
        assertEquals("Zed Game", zzz.customName)
        assertEquals("Zed Game", zzz.effectiveName)
        assertEquals("zzz", zzz.displayName)
    }

    @Test
    fun legacyLinesLoadUnpinnedAndSixthFieldLoadsPinned() {
        val dir = tempDir("lib-pin-legacy")
        val lib = library(dir)
        val games = File(dir, "games")
        File(games, "aaa.neo").writeText("fake")
        File(games, "mmm.neo").writeText("fake")
        File(games, "zzz.neo").writeText("fake")
        // Four fields (before modes) and five fields (before pins)
        // both load unpinned; a six-field line with "1" loads pinned.
        File(dir, "library.txt").writeText(
            "aaa.neo|aaa|100|aaa.neo\n" +
                "mmm.neo|mmm|200|mmm.neo|\n" +
                "zzz.neo|zzz|300|zzz.neo||1\n",
        )
        val loaded = lib.load()
        assertEquals(listOf("zzz", "aaa", "mmm"), loaded.map { it.displayName })
        assertTrue(loaded[0].pinned)
        assertFalse(loaded[1].pinned)
        assertFalse(loaded[2].pinned)
    }
}
