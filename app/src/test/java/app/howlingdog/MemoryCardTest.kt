package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pure-JVM unit tests for the virtual memory card manager
 * (2.0, feature 3): the listing reads the actual save files in
 * the saves directory, groups them by game, ignores save states
 * and thumbnails, and deletes by file or by game. No Android
 * framework calls — the saves directory is a plain folder, so
 * these run on the JVM.
 */
class MemoryCardTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    private fun entry(mainFile: String, displayName: String) =
        GameEntry(mainFile, displayName, listOf(mainFile), 0L)

    private fun write(dir: File, name: String, size: Int) {
        File(dir, name).writeBytes(ByteArray(size))
    }

    @Test
    fun emptySavesDirListsNothing() {
        val dir = tempDir("memcard-empty")
        assertTrue(MemoryCard.listEntries(dir, emptyList()).isEmpty())
        assertTrue(MemoryCard.listEntries(File(dir, "missing"), emptyList()).isEmpty())
    }

    @Test
    fun saveFilesAreListedGroupedByGame() {
        val dir = tempDir("memcard-list")
        write(dir, "kof98.neo.mcr", 2048)
        write(dir, "kof98.neo.nv", 65536)
        write(dir, "mslug3.neo.srm", 8192)
        val games = listOf(entry("kof98.neo", "KOF 98"), entry("mslug3.neo", "Metal Slug 3"))
        val entries = MemoryCard.listEntries(dir, games)
        assertEquals(2, entries.size)
        assertEquals("kof98.neo", entries[0].mainFile)
        assertEquals("KOF 98", entries[0].displayName)
        assertEquals(listOf("kof98.neo.mcr", "kof98.neo.nv"), entries[0].files.map { it.name })
        assertEquals(2048L + 65536L, entries[0].totalBytes)
        assertEquals("mslug3.neo", entries[1].mainFile)
        assertEquals(listOf("mslug3.neo.srm"), entries[1].files.map { it.name })
        assertEquals(8192L, entries[1].totalBytes)
    }

    @Test
    fun cdBackupRamIsListed() {
        val dir = tempDir("memcard-brm")
        write(dir, "kof98.cue.brm", 8192)
        val entries = MemoryCard.listEntries(dir, listOf(entry("kof98.cue", "KOF 98 CD")))
        assertEquals(1, entries.size)
        assertEquals(listOf("kof98.cue.brm"), entries[0].files.map { it.name })
    }

    @Test
    fun saveStatesSidecarsAndThumbnailsAreNeverListed() {
        val dir = tempDir("memcard-ignore")
        write(dir, "kof98.neo.state", 100000)
        write(dir, "kof98.neo.state2", 100000)
        write(dir, "kof98.neo.state.ra", 100)
        write(dir, "kof98.neo.state.png", 5000)
        write(dir, "kof98.neo.mcr", 2048)
        val entries = MemoryCard.listEntries(dir, listOf(entry("kof98.neo", "KOF 98")))
        assertEquals(1, entries.size)
        assertEquals(listOf("kof98.neo.mcr"), entries[0].files.map { it.name })
    }

    @Test
    fun unknownGameSavesAreListedUnderTheirFileName() {
        val dir = tempDir("memcard-unknown")
        write(dir, "gone.neo.mcr", 2048)
        write(dir, "gone.neo.srm", 8192)
        val entries = MemoryCard.listEntries(dir, listOf(entry("kof98.neo", "KOF 98")))
        assertEquals(1, entries.size)
        assertEquals("gone.neo", entries[0].mainFile)
        assertEquals("gone.neo", entries[0].displayName)
        assertEquals(2, entries[0].files.size)
    }

    @Test
    fun libraryOrderWinsOverFileNameOrder() {
        val dir = tempDir("memcard-order")
        write(dir, "zgame.neo.mcr", 2048)
        write(dir, "agame.neo.mcr", 2048)
        val games = listOf(entry("zgame.neo", "Z Game"), entry("agame.neo", "A Game"))
        val entries = MemoryCard.listEntries(dir, games)
        assertEquals(listOf("zgame.neo", "agame.neo"), entries.map { it.mainFile })
    }

    @Test
    fun isSaveFileAndMainFileFor() {
        assertTrue(MemoryCard.isSaveFile("kof98.neo.mcr"))
        assertTrue(MemoryCard.isSaveFile("kof98.neo.nv"))
        assertTrue(MemoryCard.isSaveFile("kof98.neo.srm"))
        assertTrue(MemoryCard.isSaveFile("kof98.cue.brm"))
        assertFalse(MemoryCard.isSaveFile("kof98.neo.state"))
        assertFalse(MemoryCard.isSaveFile("kof98.neo.state.ra"))
        assertFalse(MemoryCard.isSaveFile("kof98.neo.state.png"))
        assertFalse(MemoryCard.isSaveFile("kof98.neo"))
        assertEquals("kof98.neo", MemoryCard.mainFileFor("kof98.neo.mcr"))
        assertEquals("kof98.cue", MemoryCard.mainFileFor("kof98.cue.brm"))
        assertEquals(null, MemoryCard.mainFileFor("kof98.neo.state"))
        assertEquals(null, MemoryCard.mainFileFor(".mcr"))
    }

    @Test
    fun deleteFileRemovesOneSaveOnly() {
        val dir = tempDir("memcard-del-file")
        write(dir, "kof98.neo.mcr", 2048)
        write(dir, "kof98.neo.nv", 65536)
        assertTrue(MemoryCard.deleteFile(dir, "kof98.neo.mcr"))
        assertFalse(File(dir, "kof98.neo.mcr").exists())
        assertTrue(File(dir, "kof98.neo.nv").exists())
        assertFalse("a second delete finds nothing", MemoryCard.deleteFile(dir, "kof98.neo.mcr"))
        assertFalse("a state file is never deleted as a save", MemoryCard.deleteFile(dir, "kof98.neo.state"))
        assertFalse(MemoryCard.deleteFile(dir, "missing.neo.mcr"))
    }

    @Test
    fun deleteGameRemovesEverySaveForThatGame() {
        val dir = tempDir("memcard-del-game")
        write(dir, "kof98.neo.mcr", 2048)
        write(dir, "kof98.neo.nv", 65536)
        write(dir, "kof98.neo.srm", 8192)
        write(dir, "mslug3.neo.mcr", 2048)
        assertEquals(3, MemoryCard.deleteGame(dir, "kof98.neo"))
        assertFalse(File(dir, "kof98.neo.mcr").exists())
        assertFalse(File(dir, "kof98.neo.nv").exists())
        assertFalse(File(dir, "kof98.neo.srm").exists())
        assertTrue("the other game's save is untouched", File(dir, "mslug3.neo.mcr").exists())
        assertEquals(0, MemoryCard.deleteGame(dir, "kof98.neo"))
    }

    @Test
    fun formatSize() {
        assertEquals("0 B", MemoryCard.formatSize(0))
        assertEquals("512 B", MemoryCard.formatSize(512))
        assertEquals("2 KB", MemoryCard.formatSize(2048))
        assertEquals("64 KB", MemoryCard.formatSize(65536))
        assertEquals("1.5 KB", MemoryCard.formatSize(1536))
        assertEquals("2 MB", MemoryCard.formatSize(2 * 1024 * 1024))
        assertEquals("1.5 MB", MemoryCard.formatSize((1.5 * 1024 * 1024).toLong()))
    }
}
