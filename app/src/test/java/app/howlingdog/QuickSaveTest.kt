package app.howlingdog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the quick save/load slot: the quick path
 * uses the same per-game file the pause menu uses (one slot per
 * game, no second slot), and a quick load with no save file does
 * nothing.
 */
class QuickSaveTest {

    @Test
    fun stateFileIsThePauseMenuSlot() {
        // The slot is "<mainFile>.state" in the saves folder — the
        // same path the pause menu's save/load uses, so the two
        // paths always see the same state.
        val saves = File("/tmp/hd-test-saves")
        assertEquals(
            File(saves, "kof98.neo.state"),
            QuickSave.stateFile(saves, "kof98.neo"),
        )
        assertEquals(
            File(saves, "shocktro.neo.state"),
            QuickSave.stateFile(saves, "shocktro.neo"),
        )
    }

    @Test
    fun loadAvailableOnlyWhenAFileExists() {
        val dir = File("/tmp/hd-test-saves-quick")
        dir.mkdirs()
        val missing = File(dir, "nosuchgame.neo.state")
        assertFalse(QuickSave.loadAvailable(missing))
        // A directory with the slot name is not a loadable save.
        val asDir = File(dir, "adir.neo.state")
        asDir.mkdirs()
        assertFalse(QuickSave.loadAvailable(asDir))
        val present = File(dir, "kof98.neo.state")
        present.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(QuickSave.loadAvailable(present))
        present.delete()
        assertFalse(QuickSave.loadAvailable(present))
        asDir.delete()
        dir.delete()
    }
}
